package com.aiexam.ai.adapter;

import com.aiexam.ai.config.AiCapabilityProperties;
import com.aiexam.ai.port.KnowledgeRetrievalPort;
import com.aiexam.ai.port.KnowledgeRetrievalPort.Evidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class HybridKnowledgeRetrievalAdapter implements KnowledgeRetrievalPort {
  private static final Logger log = LoggerFactory.getLogger(HybridKnowledgeRetrievalAdapter.class);

  private final JdbcTemplate jdbc;
  private final AiCapabilityProperties properties;
  private final ObjectMapper json;
  private final HttpClient http = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .build();

  public HybridKnowledgeRetrievalAdapter(
      JdbcTemplate jdbc,
      AiCapabilityProperties properties,
      ObjectMapper json
  ) {
    this.jdbc = jdbc;
    this.properties = properties;
    this.json = json;
  }

  @Override
  public RetrievalDecision retrieve(String query, int topK) {
    int limit = Math.max(1, Math.min(topK, 20));
    int candidateLimit = Math.max(limit, properties.ragCandidateTopK());
    List<Evidence> bm25 = bm25(query, candidateLimit);
    List<Evidence> vector = vector(query, candidateLimit);
    List<Evidence> hybrid = hybrid(bm25, vector, limit);
    Double vectorScore = vector.isEmpty() ? null : vector.get(0).score();
    Double bm25Score = bm25.isEmpty() ? null : bm25.get(0).score();
    double vectorThreshold = !vector.isEmpty() && "CARD".equalsIgnoreCase(vector.get(0).contentKind())
        ? properties.ragVectorCardMinScore()
        : properties.ragVectorMinScore();
    boolean vectorHit = vectorScore != null
        && vectorScore >= vectorThreshold
        && scoreMarginPasses(vector);
    boolean bm25Hit = bm25Score != null && bm25Score >= properties.ragBm25MinScore();
    boolean grounded = isGrounded(vectorHit, bm25Hit);
    String reason = vectorHit
        ? "VECTOR_SCORE"
        : bm25Hit ? "BM25_SCORE" : "LOW_CONFIDENCE";
    return new RetrievalDecision(
        bm25.stream().limit(limit).toList(),
        vector.stream().limit(limit).toList(),
        hybrid,
        grounded,
        reason);
  }

  private boolean scoreMarginPasses(List<Evidence> rows) {
    if (properties.ragMinScoreMargin() <= 0 || rows.size() < 2) return true;
    return rows.get(0).score() - rows.get(1).score() >= properties.ragMinScoreMargin();
  }

  private List<Evidence> hybrid(List<Evidence> bm25, List<Evidence> vector, int limit) {
    Map<String, Evidence> merged = new LinkedHashMap<>();
    Map<String, Double> scores = new HashMap<>();
    fuse(bm25, properties.ragBm25Weight(), merged, scores);
    fuse(vector, properties.ragVectorWeight(), merged, scores);
    List<Evidence> ranked = merged.entrySet().stream()
        .sorted((left, right) -> {
          int score = Double.compare(scores.get(right.getKey()), scores.get(left.getKey()));
          if (score != 0) return score;
          return Boolean.compare(
              "CARD".equals(right.getValue().contentKind()),
              "CARD".equals(left.getValue().contentKind()));
        })
        .map(entry -> withScore(entry.getValue(), scores.get(entry.getKey())))
        .toList();
    return diversify(ranked, limit);
  }

  private void fuse(
      List<Evidence> rows,
      double channelWeight,
      Map<String, Evidence> merged,
      Map<String, Double> scores
  ) {
    for (int index = 0; index < rows.size(); index++) {
      Evidence row = rows.get(index);
      merged.putIfAbsent(row.fragmentKey(), row);
      // Source fragments cover the complete textbook and should not be
      // displaced indefinitely by a single curated card.
      double contentBoost = contentBoost(row.contentKind());
      scores.merge(
          row.fragmentKey(),
          channelWeight * contentBoost / (properties.ragRrfK() + index + 1),
          Double::sum);
    }
  }

  private List<Evidence> diversify(List<Evidence> rows, int limit) {
    List<Evidence> result = new ArrayList<>();
    Map<String, Integer> concepts = new HashMap<>();
    for (Evidence row : rows) {
      String concept = row.conceptId() == null || row.conceptId().isBlank()
          ? row.knowledgeId()
          : row.conceptId();
      if (concept != null && concepts.getOrDefault(concept, 0) >= 2) continue;
      if (concept != null) concepts.merge(concept, 1, Integer::sum);
      result.add(row);
      if (result.size() >= limit) break;
    }
    return result;
  }

  private List<Evidence> bm25(String query, int limit) {
    try {
      return jdbc.query("""
          SELECT COALESCE(fragment_key,CAST(id AS CHAR)),
                 COALESCE(knowledge_code,CAST(knowledge_id AS CHAR)),
                 concept_code,content_kind,source_pages,content,
                 MATCH(content) AGAINST (? IN NATURAL LANGUAGE MODE) score
          FROM knowledge_fragment
          WHERE status='PUBLISHED'
            AND index_status='INDEXED'
            AND invalidated_at IS NULL
            AND deleted=0
            AND MATCH(content) AGAINST (? IN NATURAL LANGUAGE MODE)
          ORDER BY score DESC
          LIMIT ?
          """, (rs, rowNumber) -> new Evidence(
          rs.getString(1),
          rs.getString(2),
          rs.getString(3),
          rs.getString(4),
          rs.getString(5),
          excerpt(rs.getString(6)),
          rs.getDouble(7)), query, query, limit);
    } catch (RuntimeException ignored) {
      return List.of();
    }
  }

  private List<Evidence> vector(String query, int limit) {
    if (blank(properties.embeddingEndpoint()) || blank(properties.qdrantEndpoint())) return List.of();
    try {
      String vectorPayload = json.writeValueAsString(Map.of(
          "input", List.of(query),
          "model", properties.embeddingModel(),
          "input_type", "QUERY"));
      JsonNode embedded = call(properties.embeddingEndpoint(), vectorPayload);
      JsonNode embedding = embedded.at("/data/0/embedding");
      if (!embedding.isArray()) return List.of();
      List<Double> values = new ArrayList<>();
      embedding.forEach(node -> values.add(node.asDouble()));
      String payload = json.writeValueAsString(Map.of(
          "vector", values,
          "limit", limit,
          "with_payload", true));
      JsonNode root = call(
          properties.qdrantEndpoint().replaceAll("/+$", "")
              + "/collections/" + properties.qdrantCollection() + "/points/search",
          payload);
      List<Evidence> rows = new ArrayList<>();
      for (JsonNode item : root.path("result")) {
        JsonNode value = item.path("payload");
        rows.add(new Evidence(
            value.path("fragment_id").asText(),
            value.path("knowledge_id").asText(null),
            value.path("concept_id").asText(null),
            value.path("content_kind").asText(null),
            value.path("source_pages").asText(null),
            value.path("heading_path").toString(),
            item.path("score").asDouble()));
      }
      return rows;
    } catch (Exception error) {
      log.warn("Vector retrieval is unavailable: {}", error.getMessage());
      return List.of();
    }
  }

  private JsonNode call(String uri, String body) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri))
        .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
        .header("Content-Type", "application/json");
    if (!blank(properties.embeddingToken())) {
      builder.header("Authorization", "Bearer " + properties.embeddingToken());
    }
    HttpResponse<String> response = http.send(
        builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("HTTP " + response.statusCode() + " from " + uri);
    }
    return json.readTree(response.body());
  }

  private static Evidence withScore(Evidence row, double score) {
    return new Evidence(
        row.fragmentKey(),
        row.knowledgeId(),
        row.conceptId(),
        row.contentKind(),
        row.sourcePages(),
        row.excerpt(),
        score);
  }

  static boolean isGrounded(boolean vectorHit, boolean bm25Hit) {
    return vectorHit || bm25Hit;
  }

  static double contentBoost(String contentKind) {
    return "CARD".equalsIgnoreCase(contentKind) ? 0.95d : 1.05d;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String excerpt(String value) {
    return value == null ? "" : value.substring(0, Math.min(value.length(), 500));
  }
}
