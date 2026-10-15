package com.aiexam.app.aiassistant;

import com.aiexam.common.error.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Builds the vector projection of knowledge fragments.
 *
 * <p>MySQL remains the source of truth. Qdrant only stores a replaceable retrieval projection,
 * while model, dimension, point id and processing state are written back to each fragment.</p>
 */
@Service
public class KnowledgeIndexingService {
  private static final int EMBEDDING_BATCH_SIZE = 24;

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final AiAssistantProperties properties;
  private final HttpClient http;

  public KnowledgeIndexingService(JdbcTemplate jdbc, ObjectMapper json, AiAssistantProperties properties) {
    this.jdbc = jdbc;
    this.json = json;
    this.properties = properties;
    this.http = HttpClient.newBuilder()
        // 本地 FastAPI 网关仅稳定支持 HTTP/1.1；h2c 升级会导致 POST 请求体丢失。
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
        .build();
  }

  public AiAssistantDtos.KnowledgeDocumentDto indexDocument(long documentId) {
    requireRuntime();
    List<FragmentRow> fragments = fragments(documentId);
    if (fragments.isEmpty()) {
      throw new BusinessException("AI_KNOWLEDGE_EMPTY", "知识资料没有可索引切片", HttpStatus.BAD_REQUEST);
    }

    jdbc.update("""
        UPDATE knowledge_fragment
           SET index_status='PROCESSING', updated_at=?, invalidated_at=NULL
         WHERE document_id=? AND deleted=0
        """, Timestamp.from(Instant.now()), documentId);
    try {
      List<IndexedFragment> indexed = new ArrayList<>();
      for (int offset = 0; offset < fragments.size(); offset += EMBEDDING_BATCH_SIZE) {
        List<FragmentRow> batch = fragments.subList(offset, Math.min(fragments.size(), offset + EMBEDDING_BATCH_SIZE));
        List<List<Double>> vectors = embed(batch.stream().map(FragmentRow::content).toList());
        if (vectors.size() != batch.size()) {
          throw new IllegalStateException("Embedding 返回数量与切片数量不一致");
        }
        for (int index = 0; index < batch.size(); index++) {
          indexed.add(new IndexedFragment(batch.get(index), vectors.get(index)));
        }
      }

      int dimension = indexed.get(0).vector().size();
      ensureCollection(dimension);
      upsert(indexed);
      Instant now = Instant.now();
      for (IndexedFragment item : indexed) {
        jdbc.update("""
            UPDATE knowledge_fragment
               SET index_status='INDEXED', quality_status=CASE
                     WHEN quality_status='REJECTED' THEN quality_status ELSE 'NEEDS_REVIEW' END,
                   embedding_model=?, embedding_revision=?, embedding_version=?,
                   embedding_dimension=?, qdrant_point_id=?, embedded_at=?,
                   invalidated_at=NULL, updated_at=?
             WHERE id=? AND document_id=? AND deleted=0
            """, properties.embeddingModel(), blankToNull(properties.embeddingRevision()),
            embeddingVersion(), dimension, item.row().pointId(), Timestamp.from(now), Timestamp.from(now),
            item.row().id(), documentId);
      }
      return document(documentId);
    } catch (Exception exception) {
      jdbc.update("""
          UPDATE knowledge_fragment
             SET index_status='FAILED', updated_at=?
           WHERE document_id=? AND deleted=0 AND index_status='PROCESSING'
          """, Timestamp.from(Instant.now()), documentId);
      if (exception instanceof BusinessException businessException) throw businessException;
      throw new BusinessException("AI_KNOWLEDGE_INDEX_FAILED",
          "知识资料向量化失败：" + safeMessage(exception), HttpStatus.BAD_GATEWAY);
    }
  }

  public void invalidateDocument(long documentId) {
    List<Long> pointIds = jdbc.query("""
        SELECT qdrant_point_id FROM knowledge_fragment
         WHERE document_id=? AND deleted=0 AND qdrant_point_id IS NOT NULL
        """, (rs, row) -> rs.getLong(1), documentId);
    if (!pointIds.isEmpty() && !blank(properties.qdrantEndpoint())) {
      try {
        String payload = json.writeValueAsString(Map.of("points", pointIds));
        request("POST", qdrantBase() + "/collections/" + properties.qdrantCollection()
            + "/points/delete?wait=true", payload);
      } catch (Exception exception) {
        throw new BusinessException("AI_KNOWLEDGE_VECTOR_DELETE_FAILED",
            "知识资料向量下架失败：" + safeMessage(exception), HttpStatus.BAD_GATEWAY);
      }
    }
    Instant now = Instant.now();
    jdbc.update("""
        UPDATE knowledge_fragment
           SET index_status='INVALIDATED', invalidated_at=?, updated_at=?
         WHERE document_id=? AND deleted=0
        """, Timestamp.from(now), Timestamp.from(now), documentId);
  }

  private List<FragmentRow> fragments(long documentId) {
    return jdbc.query("""
        SELECT f.id, f.fragment_key, f.content, f.knowledge_code, f.concept_code,
               f.content_kind, f.source_pages, f.heading_path, d.qualification_id, d.subject_id
          FROM knowledge_fragment f
          JOIN knowledge_document d ON d.id=f.document_id AND d.deleted=0
         WHERE f.document_id=? AND f.deleted=0
         ORDER BY f.fragment_no
        """, (rs, row) -> {
      long id = rs.getLong("id");
      String fragmentKey = rs.getString("fragment_key");
      if (blank(fragmentKey)) fragmentKey = "admin:" + documentId + ":" + id;
      return new FragmentRow(id, fragmentKey, rs.getString("content"), rs.getString("knowledge_code"),
          rs.getString("concept_code"), valueOr(rs.getString("content_kind"), "SOURCE"),
          rs.getString("source_pages"), rs.getString("heading_path"),
          rs.getLong("qualification_id"), rs.getObject("subject_id", Long.class), String.valueOf(id));
    }, documentId);
  }

  private List<List<Double>> embed(List<String> contents) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("input", contents);
    body.put("model", properties.embeddingModel());
    body.put("input_type", "DOCUMENT");
    JsonNode response = request("POST", properties.embeddingEndpoint(), json.writeValueAsString(body));
    JsonNode data = response.path("data");
    if (!data.isArray()) throw new IllegalStateException("Embedding 服务未返回 data");
    List<List<Double>> vectors = new ArrayList<>();
    for (JsonNode item : data) {
      JsonNode embedding = item.path("embedding");
      if (!embedding.isArray()) throw new IllegalStateException("Embedding 服务未返回向量");
      List<Double> vector = new ArrayList<>(embedding.size());
      embedding.forEach(value -> vector.add(value.asDouble()));
      vectors.add(vector);
    }
    return vectors;
  }

  private void ensureCollection(int dimension) throws Exception {
    String collectionUrl = qdrantBase() + "/collections/" + properties.qdrantCollection();
    HttpRequest get = HttpRequest.newBuilder(URI.create(collectionUrl))
        .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds())).GET().build();
    HttpResponse<String> response = http.send(get, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 200) return;
    if (response.statusCode() != 404) {
      throw new IllegalStateException("Qdrant 集合检查失败：" + response.statusCode());
    }
    request("PUT", collectionUrl, json.writeValueAsString(
        Map.of("vectors", Map.of("size", dimension, "distance", "Cosine"))));
  }

  private void upsert(List<IndexedFragment> indexed) throws Exception {
    List<Map<String, Object>> points = new ArrayList<>();
    for (IndexedFragment item : indexed) {
      FragmentRow row = item.row();
      Map<String, Object> payload = new LinkedHashMap<>();
      // RetrievalResultDto currently uses fragment_id as the stable fragment key.
      payload.put("fragment_id", row.fragmentKey());
      payload.put("knowledge_id", row.knowledgeCode());
      payload.put("concept_id", row.conceptCode());
      payload.put("content_kind", row.contentKind());
      payload.put("source_pages", row.sourcePages());
      payload.put("heading_path", parseHeadingPath(row.headingPath()));
      payload.put("qualification_id", row.qualificationId());
      payload.put("subject_id", row.subjectId());
      payload.values().removeIf(value -> value == null);
      points.add(Map.of("id", Long.parseLong(row.pointId()), "vector", item.vector(), "payload", payload));
    }
    request("PUT", qdrantBase() + "/collections/" + properties.qdrantCollection()
        + "/points?wait=true", json.writeValueAsString(Map.of("points", points)));
  }

  private Object parseHeadingPath(String value) {
    if (blank(value)) return List.of();
    try { return json.readTree(value); }
    catch (Exception ignored) { return List.of(value); }
  }

  private JsonNode request(String method, String uri, String body) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri))
        .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
        .header("Content-Type", "application/json");
    if (!blank(properties.embeddingToken()) && uri.startsWith(properties.embeddingEndpoint())) {
      builder.header("Authorization", "Bearer " + properties.embeddingToken());
    }
    HttpRequest request = "PUT".equals(method)
        ? builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build()
        : builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("HTTP " + response.statusCode() + "：" + response.body());
    }
    return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
  }

  private AiAssistantDtos.KnowledgeDocumentDto document(long documentId) {
    return jdbc.queryForObject("""
        SELECT d.id,d.document_code,d.title,d.status,COUNT(f.id),
               SUM(CASE WHEN f.index_status='INDEXED' THEN 1 ELSE 0 END),
               SUM(CASE WHEN f.quality_status='APPROVED' THEN 1 ELSE 0 END),
               SUM(CASE WHEN f.index_status='FAILED' THEN 1 ELSE 0 END),MAX(d.updated_at)
          FROM knowledge_document d
          LEFT JOIN knowledge_fragment f ON f.document_id=d.id AND f.deleted=0
         WHERE d.id=? AND d.deleted=0
         GROUP BY d.id,d.document_code,d.title,d.status
        """, (rs, row) -> new AiAssistantDtos.KnowledgeDocumentDto(
        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
        rs.getInt(6), rs.getInt(7), rs.getInt(8),
        processingStatus(rs.getInt(5), rs.getInt(6), rs.getInt(8)),
        rs.getTimestamp(9).toInstant()), documentId);
  }

  private void requireRuntime() {
    if (blank(properties.embeddingEndpoint()) || blank(properties.qdrantEndpoint())) {
      throw new BusinessException("AI_KNOWLEDGE_INDEX_UNAVAILABLE",
          "Embedding 或 Qdrant 服务尚未配置", HttpStatus.SERVICE_UNAVAILABLE);
    }
  }

  static String processingStatus(int total, int indexed, int failed) {
    if (failed > 0) return "FAILED";
    if (total > 0 && indexed == total) return "INDEXED";
    if (indexed > 0) return "PARTIAL";
    return "READY";
  }

  private String embeddingVersion() {
    String model = properties.embeddingModel();
    int separator = model.lastIndexOf('/');
    String modelName = (separator >= 0 ? model.substring(separator + 1) : model).toLowerCase();
    if (blank(properties.embeddingRevision())) return modelName;
    String revision = properties.embeddingRevision().trim();
    return modelName + "-" + revision.substring(0, Math.min(revision.length(), 8));
  }

  private String qdrantBase() { return properties.qdrantEndpoint().replaceAll("/$", ""); }
  private static boolean blank(String value) { return value == null || value.isBlank(); }
  private static String blankToNull(String value) { return blank(value) ? null : value.trim(); }
  private static String valueOr(String value, String fallback) { return blank(value) ? fallback : value; }
  private static String safeMessage(Exception exception) {
    String message = exception.getMessage();
    return message == null || message.isBlank() ? exception.getClass().getSimpleName()
        : message.substring(0, Math.min(message.length(), 300));
  }

  private record FragmentRow(long id, String fragmentKey, String content, String knowledgeCode,
                             String conceptCode, String contentKind, String sourcePages, String headingPath,
                             long qualificationId, Long subjectId, String pointId) { }
  private record IndexedFragment(FragmentRow row, List<Double> vector) { }
}
