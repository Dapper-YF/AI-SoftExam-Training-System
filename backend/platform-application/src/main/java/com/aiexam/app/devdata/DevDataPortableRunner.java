package com.aiexam.app.devdata;

import com.aiexam.common.security.PlatformPrincipal;
import com.aiexam.question.admin.application.QuestionAdminApplicationService;
import com.aiexam.question.admin.application.QuestionImportApplicationService;
import com.aiexam.question.admin.dto.QuestionImportRequest;
import com.aiexam.question.admin.dto.QuestionReviewRequest;
import com.aiexam.question.admin.dto.QuestionWorkflowRequest;
import com.aiexam.question.admin.dto.QuestionImportResultDto.Item;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "aiexam.dev-data", name = "enabled", havingValue = "true")
public class DevDataPortableRunner implements ApplicationRunner {
  private static final String CANONICAL_FILE = "canonical-questions.jsonl";
  private static final String CANONICAL_GZ = "canonical-questions.jsonl.gz";
  private static final String MANIFEST = "manifest.json";
  private static final Set<String> COMMANDS = Set.of("export", "bootstrap");

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final ObjectMapper canonicalMapper;
  private final QuestionImportApplicationService importService;
  private final QuestionAdminApplicationService adminService;
  private final String command;
  private final String datasetVersion;
  private final Path outputDir;
  private final Path inputDir;
  private final Path importRoot;
  private final String sourceCommit;

  public DevDataPortableRunner(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      QuestionImportApplicationService importService,
      QuestionAdminApplicationService adminService,
      @Value("${aiexam.dev-data.command:}") String command,
      @Value("${aiexam.dev-data.dataset-version:}") String datasetVersion,
      @Value("${aiexam.dev-data.output-dir:}") String outputDir,
      @Value("${aiexam.dev-data.input-dir:}") String inputDir,
      @Value("${aiexam.dev-data.import-root:}") String importRoot,
      @Value("${aiexam.runtime.commit:unknown}") String sourceCommit
  ) {
    this.jdbc = jdbc;
    this.mapper = mapper.copy().findAndRegisterModules();
    this.canonicalMapper = this.mapper.copy()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    this.importService = importService;
    this.adminService = adminService;
    this.command = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
    this.datasetVersion = requireText(datasetVersion, "aiexam.dev-data.dataset-version");
    this.outputDir = blank(outputDir) ? null : Path.of(outputDir);
    this.inputDir = blank(inputDir) ? null : Path.of(inputDir);
    this.importRoot = blank(importRoot) ? null : Path.of(importRoot);
    this.sourceCommit = sourceCommit;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    if (!COMMANDS.contains(command)) {
      throw new IllegalStateException("DEV_DATA_COMMAND_INVALID:" + command);
    }
    if ("export".equals(command)) {
      exportDataset();
    } else {
      bootstrapDataset();
    }
  }

  private void exportDataset() throws Exception {
    if (outputDir == null) {
      throw new IllegalStateException("DEV_DATA_OUTPUT_DIR_REQUIRED");
    }
    Files.createDirectories(outputDir);
    Path jsonl = outputDir.resolve(CANONICAL_FILE);
    Path gzip = outputDir.resolve(CANONICAL_GZ);
    Path manifest = outputDir.resolve(MANIFEST);

    List<String> rows = jdbc.query("""
        SELECT i.canonical_json
        FROM q_question_import_item i
        JOIN q_question_version v ON v.id = i.question_version_id
        JOIN q_question q ON q.id = v.question_id
        JOIN exam_qualification eq ON eq.id = q.qualification_id
        JOIN exam_subject es ON es.id = q.subject_id
        WHERE i.item_status = 'IMPORTED_DRAFT'
          AND v.status = 'PUBLISHED'
          AND v.copyright_status = 'UNVERIFIED'
          AND v.usage_restriction = 'INTERNAL_ONLY'
          AND i.import_eligible = 1
        ORDER BY eq.qualification_code, es.subject_code, q.question_type, i.external_key
        """, (rs, rowNum) -> sanitizeCanonical(rs.getString(1)));
    if (rows.isEmpty()) {
      throw new IllegalStateException("DEV_DATA_EXPORT_EMPTY");
    }

    try (BufferedWriter writer = Files.newBufferedWriter(jsonl, StandardCharsets.UTF_8)) {
      for (String row : rows) {
        writer.write(row);
        writer.write('\n');
      }
    }
    gzip(jsonl, gzip);

    DatasetManifest payload = manifestPayload(rows.size(), jsonl, gzip);
    writeJson(manifest, payload.asMap());

    System.out.println("DEV_DATA_EXPORT_DATASET=" + datasetVersion);
    System.out.println("DEV_DATA_EXPORT_ROWS=" + rows.size());
    System.out.println("DEV_DATA_EXPORT_JSONL=" + jsonl);
    System.out.println("DEV_DATA_EXPORT_GZIP=" + gzip);
    System.out.println("DEV_DATA_EXPORT_MANIFEST=" + manifest);
  }

  private String sanitizeCanonical(String canonicalJson) {
    try {
      JsonNode root = mapper.readTree(canonicalJson);
      if (root instanceof ObjectNode object) {
        JsonNode trace = object.path("sourceTrace");
        if (trace instanceof ObjectNode traceObject) {
          String sha = trace.path("documentSha256").asText("unknown");
          traceObject.put("sourcePath", "dev-data://question-bank/" + datasetVersion + "/" + sha);
        }
      }
      return canonicalMapper.writeValueAsString(root);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("DEV_DATA_CANONICAL_JSON_INVALID", exception);
    }
  }

  private DatasetManifest manifestPayload(int rows, Path jsonl, Path gzip) throws IOException {
    List<Map<String, Object>> scopeStats = jdbc.query("""
        SELECT eq.qualification_code, es.subject_code, q.question_type, COUNT(*) AS question_count
        FROM q_question_version v
        JOIN q_question q ON q.id = v.question_id
        JOIN exam_qualification eq ON eq.id = q.qualification_id
        JOIN exam_subject es ON es.id = q.subject_id
        WHERE v.deleted = 0 AND v.status = 'PUBLISHED'
          AND v.copyright_status = 'UNVERIFIED'
          AND v.usage_restriction = 'INTERNAL_ONLY'
        GROUP BY eq.qualification_code, es.subject_code, q.question_type
        ORDER BY eq.qualification_code, es.subject_code, q.question_type
        """, (rs, rowNum) -> orderedMap(
            "qualificationCode", rs.getString("qualification_code"),
            "subjectCode", rs.getString("subject_code"),
            "questionType", rs.getString("question_type"),
            "questionCount", rs.getLong("question_count")));
    Map<String, Long> tableCounts = new LinkedHashMap<>();
    for (String table : List.of("q_question", "q_question_version", "q_question_stem",
        "q_sub_question", "q_choice_option", "q_answer_key", "q_solution", "q_scoring_rule",
        "q_scoring_point", "q_question_knowledge_binding", "q_paper", "q_paper_question")) {
      tableCounts.put(table, count("SELECT COUNT(*) FROM " + table));
    }
    Map<String, String> files = new LinkedHashMap<>();
    files.put(CANONICAL_FILE, sha256(jsonl));
    files.put(CANONICAL_GZ, sha256(gzip));
    return new DatasetManifest(datasetVersion, sourceCommit, rows, tableCounts, scopeStats, files,
        Files.size(jsonl), Files.size(gzip));
  }

  private void bootstrapDataset() throws Exception {
    if (inputDir == null || importRoot == null) {
      throw new IllegalStateException("DEV_DATA_INPUT_AND_IMPORT_ROOT_REQUIRED");
    }
    Path manifest = inputDir.resolve(MANIFEST);
    Path gzip = inputDir.resolve(CANONICAL_GZ);
    if (!Files.isRegularFile(manifest) || !Files.isRegularFile(gzip)) {
      throw new IllegalStateException("DEV_DATA_PACKAGE_MISSING:" + inputDir);
    }
    JsonNode manifestJson = mapper.readTree(Files.readAllBytes(manifest));
    String manifestVersion = manifestJson.path("datasetVersion").asText();
    if (!datasetVersion.equals(manifestVersion)) {
      throw new IllegalStateException("DEV_DATA_VERSION_MISMATCH:" + manifestVersion);
    }
    String expectedGzip = manifestJson.path("files").path(CANONICAL_GZ).asText();
    if (!expectedGzip.equals(sha256(gzip))) {
      throw new IllegalStateException("DEV_DATA_GZIP_SHA_MISMATCH");
    }

    Files.createDirectories(importRoot);
    Path canonical = importRoot.resolve(CANONICAL_FILE);
    gunzip(gzip, canonical);
    String expectedJsonl = manifestJson.path("files").path(CANONICAL_FILE).asText();
    if (!expectedJsonl.equals(sha256(canonical))) {
      throw new IllegalStateException("DEV_DATA_JSONL_SHA_MISMATCH");
    }

    int packageRows = manifestJson.path("recordCount").asInt();
    int existingRows = countExistingPackageRows(canonical);
    if (existingRows > 0) {
      if (existingRows != packageRows) {
        throw new IllegalStateException(
            "DEV_DATA_PARTIAL_DATASET_DETECTED:" + existingRows + "/" + packageRows);
      }
      printBootstrapSummary(expectedJsonl, packageRows, 0, true, 0, 0);
      return;
    }

    PlatformPrincipal admin = principal("admin");
    PlatformPrincipal reviewer = principal("question_reviewer");
    withPrincipal(admin);
    UUID importMutation = namedUuid("dev-data:" + datasetVersion + ":confirm");
    var imported = importService.confirm(new QuestionImportRequest(canonical.toString(), importMutation));

    int submitted = 0;
    int published = 0;
    for (Item item : imported.items()) {
      if (!item.eligible() || item.questionId() == null || item.questionVersionId() == null) {
        continue;
      }
      long questionId = item.questionId();
      long versionId = item.questionVersionId();
      withPrincipal(admin);
      var submittedResult = adminService.submitReview(questionId, versionId,
          new QuestionWorkflowRequest(namedUuid("dev-data:" + datasetVersion + ":submit:" + item.externalKey()),
              0, "dev data bootstrap"));
      submitted++;
      withPrincipal(reviewer);
      var reviewed = adminService.review(questionId, versionId,
          new QuestionReviewRequest(namedUuid("dev-data:" + datasetVersion + ":review:" + item.externalKey()),
              submittedResult.rowVersion(), "APPROVED", "dev data bootstrap"));
      adminService.publish(questionId, versionId,
          new QuestionWorkflowRequest(namedUuid("dev-data:" + datasetVersion + ":publish:" + item.externalKey()),
              reviewed.rowVersion(), "dev data bootstrap"));
      published++;
    }
    SecurityContextHolder.clearContext();

    printBootstrapSummary(imported.inputSha256(), imported.totalCount(), imported.importedCount(),
        imported.replayed(), submitted, published);
  }

  private int countExistingPackageRows(Path canonical) throws IOException {
    int existing = 0;
    for (String line : Files.readAllLines(canonical, StandardCharsets.UTF_8)) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode row = mapper.readTree(line);
      String qualificationCode = requireText(row.path("qualificationCode").asText(),
          "qualificationCode");
      String subjectCode = requireText(row.path("subjectCode").asText(), "subjectCode");
      String externalKey = requireText(row.path("question").path("externalKey").asText(),
          "question.externalKey");
      String contentHash = requireText(row.path("question").path("contentHash").asText(),
          "question.contentHash");
      Long matches = jdbc.queryForObject("""
          SELECT COUNT(*)
          FROM q_question_version v
          JOIN q_question q ON q.id = v.question_id
          JOIN exam_qualification eq ON eq.id = q.qualification_id
          JOIN exam_subject es ON es.id = q.subject_id
          WHERE eq.qualification_code = ? AND es.subject_code = ?
            AND v.external_key = ? AND v.content_hash = ?
            AND v.status = 'PUBLISHED' AND v.deleted = 0 AND q.deleted = 0
          """, Long.class, qualificationCode, subjectCode, externalKey, contentHash);
      if (matches != null && matches > 0) {
        existing++;
      }
    }
    return existing;
  }

  private void printBootstrapSummary(String inputSha256, int totalCount, int importedCount,
      boolean replayed, int submitted, int published) throws JsonProcessingException {
    Map<String, Object> summary = orderedMap(
        "datasetVersion", datasetVersion,
        "inputSha256", inputSha256,
        "totalCount", totalCount,
        "eligibleCount", totalCount,
        "importedCount", importedCount,
        "replayed", replayed,
        "submitted", submitted,
        "published", published,
        "publishedQuestionCount", count("SELECT COUNT(*) FROM q_question_version WHERE status='PUBLISHED' AND deleted=0"),
        "accounts", List.of("student", "admin", "question_reviewer"));
    System.out.println("DEV_DATA_BOOTSTRAP_SUMMARY=" + mapper.writeValueAsString(summary));
  }

  private PlatformPrincipal principal(String username) {
    return jdbc.queryForObject("""
        SELECT id, username, auth_version FROM sys_user
        WHERE username = ? AND status = 'ENABLED' AND deleted = 0
        """, (rs, rowNum) -> {
          long userId = rs.getLong("id");
          List<String> roles = jdbc.queryForList("""
              SELECT r.role_code FROM sys_user_role ur
              JOIN sys_role r ON r.id = ur.role_id
              WHERE ur.user_id = ? AND r.deleted = 0 AND r.status = 'ENABLED'
              ORDER BY r.role_code
              """, String.class, userId);
          List<String> permissions = jdbc.queryForList("""
              SELECT DISTINCT m.permission_code FROM sys_user_role ur
              JOIN sys_role_menu rm ON rm.role_id = ur.role_id
              JOIN sys_menu m ON m.id = rm.menu_id
              WHERE ur.user_id = ? AND m.deleted = 0 AND m.enabled = 1
                AND m.permission_code IS NOT NULL
              ORDER BY m.permission_code
              """, String.class, userId);
          return new PlatformPrincipal(userId, rs.getString("username"),
              "dev-data-bootstrap", roles, permissions, rs.getLong("auth_version"));
        }, username);
  }

  private void withPrincipal(PlatformPrincipal principal) {
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(principal, "dev-data-bootstrap", List.of()));
  }

  private long count(String sql) {
    Long value = jdbc.queryForObject(sql, Long.class);
    return value == null ? 0 : value;
  }

  private void gzip(Path source, Path target) throws IOException {
    try (InputStream in = Files.newInputStream(source);
         OutputStream out = new GZIPOutputStream(Files.newOutputStream(target))) {
      in.transferTo(out);
    }
  }

  private void gunzip(Path source, Path target) throws IOException {
    try (InputStream in = new GZIPInputStream(Files.newInputStream(source));
         OutputStream out = Files.newOutputStream(target)) {
      in.transferTo(out);
    }
  }

  private void writeJson(Path target, Object value) throws IOException {
    Files.writeString(target, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)
        + System.lineSeparator(), StandardCharsets.UTF_8);
  }

  private String sha256(Path path) throws IOException {
    try (InputStream in = Files.newInputStream(path)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) >= 0) {
        digest.update(buffer, 0, read);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private UUID namedUuid(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String requireText(String value, String name) {
    if (blank(value)) {
      throw new IllegalStateException(name + "_REQUIRED");
    }
    return value.trim();
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  @SafeVarargs
  private static <T> Map<String, Object> orderedMap(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) {
      result.put(String.valueOf(values[i]), values[i + 1]);
    }
    return result;
  }

  private record DatasetManifest(
      String datasetVersion,
      String sourceCommit,
      int rows,
      Map<String, Long> tableCounts,
      List<Map<String, Object>> scopeStats,
      Map<String, String> files,
      long jsonlBytes,
      long gzipBytes
  ) {
    Map<String, Object> asMap() {
      return orderedMap(
          "datasetVersion", datasetVersion,
          "schemaVersion", "question-import/1.0",
          "sourceCommit", sourceCommit,
          "generatedAt", Instant.now().toString(),
          "rightsStatus", "UNVERIFIED",
          "visibility", "INTERNAL_ONLY",
          "recordCount", rows,
          "tableCounts", tableCounts,
          "scopeStats", scopeStats,
          "compression", "gzip",
          "jsonlBytes", jsonlBytes,
          "gzipBytes", gzipBytes,
          "files", files,
          "datasetKind", "STABLE_DEVELOPMENT_SAMPLE",
          "fullDatasetReference", orderedMap(
              "version", "W3-v1.3",
              "canonicalSha256", "7B1BB38B...577AB",
              "accepted", 26284,
              "duplicateMappings", 25,
              "anomalies", 3229,
              "availability", "ARTIFACT_AFTER_RANDOM_MYSQL_GATE"),
          "notice", "149-question stable development sample; not the W3-v1.3 full dataset. Private Codeup internal development use only; publication prohibited.");
    }
  }
}
