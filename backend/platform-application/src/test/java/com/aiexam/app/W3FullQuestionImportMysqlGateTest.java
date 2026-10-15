package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.common.error.BusinessException;
import com.aiexam.common.security.CurrentUserProvider;
import com.aiexam.common.security.PlatformPrincipal;
import com.aiexam.app.migration.deploymentcontrol.AttemptState;
import com.aiexam.app.migration.v9.V9AuthorizedDatabaseOrchestrator;
import com.aiexam.app.migration.v9.V9ExecutionFence;
import com.aiexam.app.migration.v9.V9MigrationOnlyRunner;
import com.aiexam.question.admin.application.QuestionAdminApplicationService;
import com.aiexam.question.admin.application.QuestionAdminCurrentUser;
import com.aiexam.question.admin.application.QuestionImportApplicationService;
import com.aiexam.question.admin.dto.QuestionImportRequest;
import com.aiexam.question.admin.dto.QuestionImportResultDto;
import com.aiexam.question.admin.dto.QuestionReviewRequest;
import com.aiexam.question.admin.dto.QuestionWorkflowRequest;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository;
import com.aiexam.foundation.application.FoundationApplicationService;
import com.aiexam.foundation.infra.JdbcFoundationRepository;
import com.aiexam.question.practice.attempt.application.PracticeAttemptApplicationService;
import com.aiexam.question.practice.attempt.application.PracticePrincipal;
import com.aiexam.question.practice.attempt.domain.PracticeAnswerPayload;
import com.aiexam.question.practice.attempt.dto.CreatePracticeAttemptRequest;
import com.aiexam.question.practice.attempt.dto.SavePracticeAnswerRequest;
import com.aiexam.question.practice.attempt.dto.SettlePracticeQuestionRequest;
import com.aiexam.question.practice.attempt.dto.SubmitPracticeAttemptRequest;
import com.aiexam.question.practice.attempt.infra.JdbcPracticeAttemptRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "W3_FULL_IMPORT_MYSQL_GATE", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class W3FullQuestionImportMysqlGateTest {
  private static final String CANONICAL_SHA256 =
      "7b1bb38b23826361ff41a023062d2ae788abc6c86f773e744cac91fec95577ab";
  private static final int SOURCE_TOTAL = 29_538;
  private static final int USABLE = 26_284;
  private static final int DUPLICATES = 25;
  private static final int ANOMALIES = 3_229;
  private static final int BASELINE_EXISTING = 149;
  private static final int EXPECTED_EXACT_REUSE = 0;
  private static final int NEW_ACCEPTED = USABLE;
  private static final int FINAL_QUESTION_COUNT = BASELINE_EXISTING + NEW_ACCEPTED;
  private static final String LEGACY_FIXTURE_DOCUMENT_SHA256 =
      "5f00f482a5bf059d7d5c7a4d6eb4ae5df4640759e3fb98dd6828322c8ec94e79";
  private static final String SHARED_BASELINE_FORENSIC_SHA256 =
      "91edd0813d292aca5d7a870b60f6ca50c10ba95ac371236ee886efb7e0b90720";
  private static final String SCHEMA = "w3_question_import_" + ProcessHandle.current().pid()
      + "_" + System.currentTimeMillis() + "_test";
  private static final Mysql MYSQL = Mysql.fromEnvironment();
  private static final List<String> ADMIN_PERMISSIONS = List.of(
      "question:create", "question:update", "question:submit-review", "question:review",
      "question:publish", "question:read", "question:import");

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final Map<String, Object> report = new LinkedHashMap<>();
  private JdbcTemplate jdbc;
  private QuestionImportApplicationService importService;
  private QuestionAdminApplicationService adminService;
  private AtomicReference<PlatformPrincipal> principal;
  private Path packageRoot;
  private Path scratchRoot;
  private Path reportPath;
  private String acceptedSha256;
  private String baselineIdentitySha256;
  private List<Chunk> chunks;
  private boolean schemaCreated;

  @BeforeAll
  void createRandomTestSchemaAndVerifyPackage() throws Exception {
    packageRoot = requiredPackageRoot();
    reportPath = packageRoot.resolve("mysql-gate-disjoint-baseline-report.json");
    JsonNode packageManifest = mapper.readTree(packageRoot.resolve("import-manifest.json").toFile());
    verifyPackageManifest(packageManifest);
    chunks = loadAndVerifyChunks(packageManifest);
    scratchRoot = Files.createDirectory(packageRoot.getParent().resolve(
        ".w3-question-import-gate-" + UUID.randomUUID()));

    requireTestSchema(SCHEMA);
    MYSQL.requireTestControlDatabase();
    MYSQL.createSchema(SCHEMA);
    schemaCreated = true;
    try {
      var dataSource = new DriverManagerDataSource(
          MYSQL.url(SCHEMA), MYSQL.username(), MYSQL.password());
      AtomicReference<AttemptState> v9State = new AtomicReference<>(AttemptState.FLYWAY_STARTED);
      V9ExecutionFence v9Fence = new V9ExecutionFence() {
        @Override
        public void verifyBoundary(String boundary, AttemptState expectedState) {
          assertThat(v9State.get()).as("V9 boundary %s", boundary).isEqualTo(expectedState);
        }

        @Override
        public void transition(AttemptState from, AttemptState to, String evidenceDigest) {
          assertThat(evidenceDigest).isNotBlank();
          assertThat(v9State.compareAndSet(from, to))
              .as("V9 transition %s -> %s", from, to).isTrue();
        }
      };
      Flyway flyway = V9MigrationOnlyRunner.build(
          MYSQL.url(SCHEMA), MYSQL.username(), MYSQL.password(),
          (connection, bundle) -> new V9AuthorizedDatabaseOrchestrator(
              v9Fence, () -> openConnection(dataSource)).migrate(connection, bundle));
      flyway.migrate();
      flyway.validate();
      assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("12");
      assertThat(v9State.get()).isEqualTo(AttemptState.COMPLETED);

      jdbc = new JdbcTemplate(dataSource);
      assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(SCHEMA);
      assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(33307);
      assertThat(jdbc.queryForObject(
          "SELECT COUNT(*) FROM exam_qualification WHERE deleted=0", Integer.class)).isEqualTo(20);
      assertThat(jdbc.queryForObject(
          "SELECT COUNT(*) FROM exam_subject WHERE deleted=0", Integer.class)).isEqualTo(45);

      JdbcQuestionAdminRepository repository = new JdbcQuestionAdminRepository(
          jdbc, new DataSourceTransactionManager(dataSource), mapper);
      principal = new AtomicReference<>(admin(881001L));
      CurrentUserProvider provider = () -> Optional.ofNullable(principal.get());
      QuestionAdminCurrentUser currentUser = new QuestionAdminCurrentUser(provider);
      importService = new QuestionImportApplicationService(
          repository, currentUser, mapper, packageRoot.getParent());
      adminService = new QuestionAdminApplicationService(repository, currentUser, mapper);
    } catch (Exception exception) {
      MYSQL.dropSchema(SCHEMA);
      schemaCreated = false;
      throw exception;
    }
  }

  private static Connection openConnection(DriverManagerDataSource dataSource) {
    try {
      return dataSource.getConnection();
    } catch (SQLException exception) {
      throw new IllegalStateException("cannot open V9 read-only verification connection", exception);
    }
  }

  @AfterAll
  void removeRandomSchemaAndScratchFiles() throws Exception {
    Exception failure = null;
    boolean schemaCleaned = !schemaCreated;
    boolean scratchCleaned = scratchRoot == null;
    try {
      if (schemaCreated) {
        MYSQL.dropSchema(SCHEMA);
        schemaCleaned = !MYSQL.schemaExists(SCHEMA);
      }
    } catch (Exception exception) {
      failure = exception;
    }
    try {
      if (scratchRoot != null && Files.exists(scratchRoot)) {
        deleteScratchTree(scratchRoot);
      }
      scratchCleaned = scratchRoot == null || !Files.exists(scratchRoot);
    } catch (Exception exception) {
      if (failure == null) {
        failure = exception;
      } else {
        failure.addSuppressed(exception);
      }
    }
    report.put("randomSchema", SCHEMA);
    report.put("randomSchemaCleaned", schemaCleaned);
    report.put("scratchFilesCleaned", scratchCleaned);
    if (reportPath != null && packageRoot != null && Files.isDirectory(packageRoot)) {
      mapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
    }
    if (failure != null) {
      throw failure;
    }
  }

  @Test
  void importsEverySourceRowWithRecoveryIdempotencyAndAnswerLeakageGates() throws Exception {
    QuestionImportResultDto baseline = importDisjointLegacyBaseline();
    Set<Long> baselineQuestionIds = new LinkedHashSet<>();
    Set<Long> baselineVersionIds = new LinkedHashSet<>();
    baseline.items().forEach(item -> {
      baselineQuestionIds.add(item.questionId());
      baselineVersionIds.add(item.questionVersionId());
    });
    assertThat(baselineQuestionIds).hasSize(BASELINE_EXISTING);
    assertThat(baselineVersionIds).hasSize(BASELINE_EXISTING);
    assertThat(count("q_question")).isEqualTo((long) BASELINE_EXISTING);

    provePracticeAndWholePaperFeedback(baseline.items().get(0));
    Map<String, Long> baselineBusinessSentinel = practiceBusinessCounts();

    RunStats interrupted = importChunks(chunks.subList(0, Math.min(3, chunks.size())));
    assertThat(interrupted.replayedCount()).isZero();
    proveFailedChunkIsAtomic(chunks.get(Math.min(3, chunks.size() - 1)));

    long started = System.nanoTime();
    RunStats resumed = importChunks(chunks);
    double elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0;
    assertThat(resumed.replayedCount()).isEqualTo(Math.min(3, chunks.size()));
    assertThat(resumed.totalCount()).isEqualTo(SOURCE_TOTAL);
    assertThat(resumed.eligibleCount()).isEqualTo(USABLE);
    assertThat(resumed.mappedExistingCount()).isEqualTo(EXPECTED_EXACT_REUSE);
    assertThat(resumed.importedCount()).isEqualTo(NEW_ACCEPTED);
    assertThat(resumed.batchIds()).hasSize(chunks.size());

    verifyFullDatabaseReconciliation(resumed.batchIds(), baselineQuestionIds, baselineVersionIds);
    long questionsBeforeReplay = count("q_question");
    long versionsBeforeReplay = count("q_question_version");
    long itemsBeforeReplay = count("q_question_import_item");
    RunStats replay = importChunks(chunks);
    assertThat(replay.replayedCount()).isEqualTo(chunks.size());
    assertThat(count("q_question")).isEqualTo(questionsBeforeReplay);
    assertThat(count("q_question_version")).isEqualTo(versionsBeforeReplay);
    assertThat(count("q_question_import_item")).isEqualTo(itemsBeforeReplay);
    assertThat(practiceBusinessCounts()).isEqualTo(baselineBusinessSentinel);

    verifyPointerClosureAndOrphans();
    populateReport(resumed, replay, elapsedSeconds);
  }

  private QuestionImportResultDto importDisjointLegacyBaseline() throws IOException {
    Path directory = Files.createDirectory(scratchRoot.resolve("legacy-disjoint-149"));
    Path target = directory.resolve("canonical-questions.jsonl");
    Set<String> acceptedIdentities = acceptedIdentities();
    Set<String> legacyIdentities = new LinkedHashSet<>();
    int written = 0;
    try (BufferedReader reader = Files.newBufferedReader(
             packageRoot.resolve("canonical-questions.jsonl"), StandardCharsets.UTF_8);
         BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null && written < BASELINE_EXISTING) {
        JsonNode record = mapper.readTree(line);
        if (record.path("quality").path("importEligible").asBoolean(false)) {
          ObjectNode legacy = disjointLegacyFixture((ObjectNode) record, ++written);
          String externalKey = legacy.path("question").path("externalKey").asText();
          String contentHash = legacy.path("question").path("contentHash").asText();
          assertThat(acceptedIdentities).doesNotContain(externalKey + "|" + contentHash);
          assertThat(legacyIdentities.add(externalKey + "|" + contentHash)).isTrue();
          writer.write(mapper.writeValueAsString(legacy));
          writer.newLine();
        }
      }
    }
    assertThat(written).isEqualTo(BASELINE_EXISTING);
    baselineIdentitySha256 = sha256(target);
    QuestionImportResultDto result = importService.confirm(new QuestionImportRequest(
        directory.toString(), mutation("legacy-disjoint-baseline-149")));
    assertThat(result.totalCount()).isEqualTo(BASELINE_EXISTING);
    assertThat(result.importedCount()).isEqualTo(BASELINE_EXISTING);
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM q_question
        WHERE import_external_key LIKE 'LEGACY_W3_DISJOINT_%' AND deleted=0
        """, Long.class)).isEqualTo((long) BASELINE_EXISTING);
    return result;
  }

  private Set<String> acceptedIdentities() throws IOException {
    Set<String> identities = new LinkedHashSet<>();
    try (BufferedReader reader = Files.newBufferedReader(
             packageRoot.resolve("canonical-questions.jsonl"), StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        JsonNode record = mapper.readTree(line);
        if (record.path("quality").path("importEligible").asBoolean(false)) {
          JsonNode question = record.path("question");
          identities.add(question.path("externalKey").asText() + "|"
              + question.path("contentHash").asText());
        }
      }
    }
    assertThat(identities).hasSize(USABLE);
    return identities;
  }

  private ObjectNode disjointLegacyFixture(ObjectNode source, int number) {
    ObjectNode legacy = source.deepCopy();
    String suffix = String.format("%03d", number);
    legacy.put("qualificationCode", "MIDDLE_DATABASE_SYSTEM_ENGINEER");
    legacy.put("subjectCode", "BASIC_KNOWLEDGE");
    ObjectNode question = (ObjectNode) legacy.path("question");
    question.put("externalKey", "LEGACY_W3_DISJOINT_" + suffix);
    question.put("contentHash", sha256Text("legacy-w3-disjoint-content-" + suffix));
    question.put("title", "Legacy disjoint development fixture " + suffix);
    ObjectNode sourceInfo = (ObjectNode) question.path("source");
    sourceInfo.put("sourceRef", "sha256:" + LEGACY_FIXTURE_DOCUMENT_SHA256);
    for (JsonNode stem : question.path("stems")) {
      ((ObjectNode) stem).put("content", "Legacy development baseline stem " + suffix);
    }
    for (JsonNode subQuestion : question.path("subQuestions")) {
      ObjectNode sub = (ObjectNode) subQuestion;
      sub.put("content", "Legacy development baseline sub-question " + suffix);
      for (JsonNode option : sub.path("options")) {
        ObjectNode choice = (ObjectNode) option;
        choice.put("content", "Legacy " + suffix + " option " + choice.path("optionKey").asText());
      }
      for (JsonNode solution : sub.path("solutions")) {
        ((ObjectNode) solution).put("content", "Legacy development solution " + suffix);
      }
    }
    ObjectNode trace = (ObjectNode) legacy.path("sourceTrace");
    trace.put("documentSha256", LEGACY_FIXTURE_DOCUMENT_SHA256);
    trace.put("sourcePath", "C:\\versioned-fixtures\\legacy-disjoint-149.jsonl");
    trace.put("locator", "legacy-disjoint-149:question:" + number);
    trace.put("sourceQuestionId", "LEGACY-" + suffix);
    trace.put("rowNo", number);
    trace.put("questionNo", number);
    ((ObjectNode) legacy.path("paper")).put("sourceDocumentSha256", LEGACY_FIXTURE_DOCUMENT_SHA256);
    return legacy;
  }

  private RunStats importChunks(List<Chunk> selected) {
    List<Long> batchIds = new ArrayList<>();
    int total = 0;
    int eligible = 0;
    int imported = 0;
    int mapped = 0;
    int replayed = 0;
    long peakHeap = usedHeap();
    for (Chunk chunk : selected) {
      QuestionImportResultDto result;
      try {
        result = importService.confirm(new QuestionImportRequest(
            chunk.path().toString(), mutation("chunk-" + chunk.index())));
      } catch (BusinessException failure) {
        throw new IllegalStateException(
            "chunk " + chunk.index() + " import failed; " + identityDiagnostics(chunk), failure);
      }
      assertThat(result.inputSha256()).isEqualTo(chunk.sha256());
      assertThat(result.totalCount()).isEqualTo(chunk.totalCount());
      assertThat(result.eligibleCount()).isEqualTo(chunk.eligibleCount());
      total += result.totalCount();
      eligible += result.eligibleCount();
      imported += result.importedCount();
      mapped += (int) result.items().stream()
          .filter(item -> "EXACT_EXISTING_QUESTION".equals(item.errorCode()))
          .count();
      replayed += result.replayed() ? 1 : 0;
      batchIds.add(result.batchId());
      peakHeap = Math.max(peakHeap, usedHeap());
    }
    return new RunStats(total, eligible, imported, mapped, replayed,
        List.copyOf(batchIds), peakHeap);
  }

  private String identityDiagnostics(Chunk chunk) {
    List<String> conflicts = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(chunk.path(), StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null && conflicts.size() < 5) {
        JsonNode record = mapper.readTree(line);
        if (!record.path("quality").path("importEligible").asBoolean(false)) {
          continue;
        }
        JsonNode question = record.path("question");
        String qualification = record.path("qualificationCode").asText();
        String subject = record.path("subjectCode").asText();
        String externalKey = question.path("externalKey").asText();
        String contentHash = question.path("contentHash").asText();
        List<Map<String, Object>> matches = jdbc.queryForList("""
            SELECT q.import_external_key, q.import_content_hash
            FROM q_question q
            JOIN exam_qualification qualification ON qualification.id=q.qualification_id
            JOIN exam_subject subject ON subject.id=q.subject_id
            WHERE qualification.qualification_code=? AND subject.subject_code=?
              AND q.deleted=0
              AND (q.import_external_key=? OR q.import_content_hash=?)
            """, qualification, subject, externalKey, contentHash);
        if (!matches.isEmpty()) {
          boolean exact = matches.size() == 1
              && externalKey.equals(matches.get(0).get("import_external_key"))
              && contentHash.equalsIgnoreCase(String.valueOf(
                  matches.get(0).get("import_content_hash")));
          conflicts.add(record.path("sourceTrace").path("rowNo").asInt()
              + ":matches=" + matches.size() + ":exact=" + exact
              + ":externalKey=" + externalKey + ":contentHash=" + contentHash);
        }
      }
      return "existing identities=" + conflicts;
    } catch (Exception diagnosticFailure) {
      return "identity diagnostics failed: " + diagnosticFailure.getMessage();
    }
  }

  private void proveFailedChunkIsAtomic(Chunk source) throws IOException {
    Path directory = Files.createDirectory(scratchRoot.resolve("failed-chunk"));
    Path target = directory.resolve("canonical-questions.jsonl");
    List<ObjectNode> eligible = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(source.path(), StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null && eligible.size() < 2) {
        JsonNode record = mapper.readTree(line);
        if (record.path("quality").path("importEligible").asBoolean(false)) {
          eligible.add((ObjectNode) record);
        }
      }
    }
    assertThat(eligible).hasSize(2);
    eligible.get(1).put("subjectCode", "NOT_CONFIGURED");
    try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
      for (ObjectNode record : eligible) {
        writer.write(mapper.writeValueAsString(record));
        writer.newLine();
      }
    }
    Map<String, Long> before = rowCounts();
    assertThatThrownBy(() -> importService.confirm(new QuestionImportRequest(
        directory.toString(), mutation("intentional-failure"))))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("does not map to an enabled scope");
    assertThat(rowCounts()).isEqualTo(before);
  }

  private void verifyFullDatabaseReconciliation(List<Long> batchIds,
      Set<Long> baselineQuestionIds, Set<Long> baselineVersionIds) {
    assertThat(sumBatches(batchIds, "total_count")).isEqualTo(SOURCE_TOTAL);
    assertThat(sumBatches(batchIds, "eligible_count")).isEqualTo(USABLE);
    assertThat(sumBatches(batchIds, "imported_count")).isEqualTo(NEW_ACCEPTED);
    assertThat(countItems(batchIds, "1=1")).isEqualTo(SOURCE_TOTAL);
    assertThat(countItems(batchIds,
        "item_status='IMPORTED_DRAFT' AND question_id IS NOT NULL AND question_version_id IS NOT NULL"))
        .isEqualTo(NEW_ACCEPTED);
    assertThat(countItems(batchIds,
        "error_code='EXACT_EXISTING_QUESTION' AND import_eligible=1 "
            + "AND question_id IS NOT NULL AND question_version_id IS NOT NULL"))
        .isEqualTo(EXPECTED_EXACT_REUSE);
    assertThat(countItems(batchIds,
        "JSON_UNQUOTE(JSON_EXTRACT(canonical_json,'$.quality.sourceStatus'))='DUPLICATE_SOURCE' "
            + "AND question_id IS NULL AND question_version_id IS NULL"))
        .isEqualTo(DUPLICATES);
    assertThat(countItems(batchIds,
        "JSON_UNQUOTE(JSON_EXTRACT(canonical_json,'$.quality.sourceStatus'))='SOURCE_ERROR' "
            + "AND question_id IS NULL AND question_version_id IS NULL"))
        .isEqualTo(ANOMALIES);
    assertThat(countItems(batchIds,
        "JSON_EXTRACT(canonical_json,'$.sourceTrace.localRecovery') IS NOT NULL "
            + "AND JSON_TYPE(JSON_EXTRACT(canonical_json,'$.sourceTrace.localRecovery'))='OBJECT' "
            + "AND JSON_UNQUOTE(JSON_EXTRACT(canonical_json,'$.sourceTrace.localRecovery.sourcePath'))<>'' "
            + "AND JSON_UNQUOTE(JSON_EXTRACT(canonical_json,'$.sourceTrace.localRecovery.sourceSha256')) "
            + "REGEXP '^[0-9a-f]{64}$' "
            + "AND JSON_UNQUOTE(JSON_EXTRACT(canonical_json,'$.sourceTrace.localRecovery.anchor'))<>'' "
            + "AND JSON_EXTRACT(canonical_json,'$.sourceTrace.localRecovery.pageNo') IS NOT NULL"))
        .isEqualTo(14);
    assertThat(countDistinctSourceRows(batchIds)).isEqualTo(SOURCE_TOTAL);
    assertThat(minSourceRow(batchIds)).isEqualTo(2);
    assertThat(maxSourceRow(batchIds)).isEqualTo(29_539);

    assertThat(count("q_question")).isEqualTo(FINAL_QUESTION_COUNT);
    assertThat(count("q_question_version")).isEqualTo(FINAL_QUESTION_COUNT);
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM q_question_version
        WHERE copyright_status <> 'UNVERIFIED' OR usage_restriction <> 'INTERNAL_ONLY'
        """, Long.class)).isZero();
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM q_question_import_batch
        WHERE rights_status <> 'UNVERIFIED' OR usage_restriction <> 'INTERNAL_ONLY'
        """, Long.class)).isZero();
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM q_sub_question sub
        WHERE sub.deleted=0 AND NOT EXISTS (
          SELECT 1 FROM q_answer_key answer
          WHERE answer.sub_question_id=sub.id
            AND answer.deleted=0 AND answer.current_flag=1)
        """, Long.class)).isZero();
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*)
        FROM q_answer_key answer
        JOIN JSON_TABLE(answer.answer_json, '$[*]'
          COLUMNS(option_key VARCHAR(16) PATH '$')) answer_option
        LEFT JOIN q_choice_option choice
          ON choice.sub_question_id=answer.sub_question_id
         AND choice.option_key=answer_option.option_key AND choice.deleted=0
        WHERE answer.deleted=0 AND answer.answer_format='OPTION' AND choice.id IS NULL
        """, Long.class)).isZero();
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM q_question_import_item item
        JOIN q_question_import_batch batch ON batch.id=item.import_batch_id
        JOIN exam_qualification qualification
          ON qualification.id=(SELECT q.qualification_id FROM q_question q WHERE q.id=item.question_id)
        WHERE batch.id IN (%s)
          AND JSON_UNQUOTE(JSON_EXTRACT(item.canonical_json,
              '$.sourceTrace.sourceQualificationName'))='数据库系统工程师'
          AND qualification.qualification_code<>'MIDDLE_DATABASE_SYSTEM_ENGINEER'
        """.formatted(placeholders(batchIds.size())), Long.class, batchIds.toArray())).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question WHERE id IN (%s) AND deleted=0"
        .formatted(placeholders(baselineQuestionIds.size())), Long.class,
        baselineQuestionIds.toArray())).isEqualTo((long) BASELINE_EXISTING);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question_version WHERE id IN (%s) AND deleted=0"
        .formatted(placeholders(baselineVersionIds.size())), Long.class,
        baselineVersionIds.toArray())).isEqualTo((long) BASELINE_EXISTING);
  }

  private void provePracticeAndWholePaperFeedback(QuestionImportResultDto.Item imported) {
    long questionId = imported.questionId();
    long versionId = imported.questionVersionId();
    principal.set(admin(881001L));
    var submitted = adminService.submitReview(questionId, versionId,
        new QuestionWorkflowRequest(UUID.randomUUID(), 0, null));
    principal.set(admin(881002L));
    var approved = adminService.review(questionId, versionId,
        new QuestionReviewRequest(UUID.randomUUID(), submitted.rowVersion(), "APPROVED", "gate review"));
    principal.set(admin(881003L));
    adminService.publish(questionId, versionId,
        new QuestionWorkflowRequest(UUID.randomUUID(), approved.rowVersion(), null));

    Map<String, Object> scope = jdbc.queryForMap(
        "SELECT qualification_id, subject_id FROM q_question WHERE id=?", questionId);
    long qualificationId = ((Number) scope.get("qualification_id")).longValue();
    long subjectId = ((Number) scope.get("subject_id")).longValue();
    var practice = new PracticeAttemptApplicationService(
        new JdbcPracticeAttemptRepository(jdbc),
        () -> new PracticePrincipal(881004L, "student", Set.of("STUDENT")),
        new FoundationApplicationService(new JdbcFoundationRepository(jdbc)),
        Clock.fixed(Instant.parse("2026-07-17T10:00:00Z"), ZoneOffset.UTC));

    var perQuestion = practice.create(attemptRequest(
        qualificationId, subjectId, "PRACTICE", "PER_QUESTION"));
    var question = perQuestion.questions().get(0);
    var subQuestion = question.subQuestions().get(0);
    assertThat(subQuestion.grading()).isNull();
    assertThat(subQuestion.solution()).isNull();
    assertThatThrownBy(() -> practice.explanation(perQuestion.attemptId(), question.questionId()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("解析不可见");
    String correct = jdbc.queryForObject("""
        SELECT answer_value FROM q_answer_key
        WHERE sub_question_id=? AND current_flag=1 AND deleted=0
        """, String.class, subQuestion.subQuestionId());
    String chosen = subQuestion.options().stream()
        .map(option -> option.optionKey())
        .filter(option -> !option.equals(correct))
        .findFirst()
        .orElseThrow();
    practice.saveAnswer(perQuestion.attemptId(), subQuestion.subQuestionId(),
        new SavePracticeAnswerRequest(UUID.randomUUID(), 0,
            new PracticeAnswerPayload(chosen, null, null, null), false, 5));
    practice.settleQuestion(perQuestion.attemptId(), question.questionId(),
        new SettlePracticeQuestionRequest(UUID.randomUUID(), 0));
    var settled = practice.get(perQuestion.attemptId());
    assertThat(settled.questions().get(0).subQuestions().get(0).grading()).isNotNull();
    assertThat(settled.questions().get(0).subQuestions().get(0).solution()).isNotBlank();
    assertThat(practice.explanation(perQuestion.attemptId(), question.questionId()).subQuestions())
        .isNotEmpty();

    var wholePaper = practice.create(attemptRequest(
        qualificationId, subjectId, "MOCK_EXAM", "WHOLE_PAPER"));
    var wholeQuestion = wholePaper.questions().get(0);
    assertThat(wholeQuestion.subQuestions().get(0).grading()).isNull();
    assertThat(wholeQuestion.subQuestions().get(0).solution()).isNull();
    assertThatThrownBy(() -> practice.settleQuestion(wholePaper.attemptId(),
        wholeQuestion.questionId(), new SettlePracticeQuestionRequest(UUID.randomUUID(), 0)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("disabled");
    practice.submit(wholePaper.attemptId(), new SubmitPracticeAttemptRequest(UUID.randomUUID(), 0));
    var completed = practice.get(wholePaper.attemptId());
    assertThat(completed.questions().get(0).subQuestions().get(0).grading()).isNotNull();
    assertThat(completed.questions().get(0).subQuestions().get(0).solution()).isNotBlank();
  }

  private CreatePracticeAttemptRequest attemptRequest(long qualificationId, long subjectId,
      String mode, String feedbackMode) {
    return new CreatePracticeAttemptRequest(UUID.randomUUID(), qualificationId, subjectId,
        mode, feedbackMode, 1,
        new CreatePracticeAttemptRequest.Source("DAILY", null, null, null, null));
  }

  private void verifyPointerClosureAndOrphans() {
    List<String> orphanQueries = List.of(
        "SELECT COUNT(*) FROM q_question_version v LEFT JOIN q_question q ON q.id=v.question_id "
            + "WHERE q.id IS NULL",
        "SELECT COUNT(*) FROM q_question q LEFT JOIN q_question_version v "
            + "ON v.id=q.current_working_version_id AND v.question_id=q.id "
            + "WHERE q.current_working_version_id IS NOT NULL AND v.id IS NULL",
        "SELECT COUNT(*) FROM q_question q LEFT JOIN q_question_version v "
            + "ON v.id=q.current_published_version_id AND v.question_id=q.id "
            + "WHERE q.current_published_version_id IS NOT NULL AND v.id IS NULL",
        "SELECT COUNT(*) FROM q_question_stem child LEFT JOIN q_question_version parent "
            + "ON parent.id=child.question_version_id AND parent.question_id=child.question_id "
            + "WHERE parent.id IS NULL",
        "SELECT COUNT(*) FROM q_sub_question child LEFT JOIN q_question_version parent "
            + "ON parent.id=child.question_version_id AND parent.question_id=child.question_id "
            + "WHERE parent.id IS NULL",
        "SELECT COUNT(*) FROM q_choice_option child LEFT JOIN q_sub_question parent "
            + "ON parent.id=child.sub_question_id "
            + "WHERE parent.id IS NULL",
        "SELECT COUNT(*) FROM q_answer_key child LEFT JOIN q_sub_question parent "
            + "ON parent.id=child.sub_question_id "
            + "WHERE parent.id IS NULL",
        "SELECT COUNT(*) FROM q_solution child LEFT JOIN q_sub_question parent "
            + "ON parent.id=child.sub_question_id AND parent.question_version_id=child.question_version_id "
            + "WHERE child.sub_question_id IS NOT NULL AND parent.id IS NULL",
        "SELECT COUNT(*) FROM q_question_import_item item LEFT JOIN q_question q "
            + "ON q.id=item.question_id LEFT JOIN q_question_version version "
            + "ON version.id=item.question_version_id AND version.question_id=item.question_id "
            + "WHERE item.question_id IS NOT NULL AND (q.id IS NULL OR version.id IS NULL)"
    );
    for (String query : orphanQueries) {
      assertThat(jdbc.queryForObject(query, Long.class)).isZero();
    }
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM q_question q
        JOIN q_question_version v ON v.id=q.current_published_version_id
        WHERE q.status='PUBLISHED' AND v.status='PUBLISHED'
        """, Long.class)).isEqualTo(1);
  }

  private void populateReport(RunStats resumed, RunStats replay, double elapsedSeconds) {
    report.put("schemaVersion", "w3-full-question-import-mysql-gate/1.1-disjoint-baseline");
    report.put("canonicalSha256", CANONICAL_SHA256);
    report.put("acceptedSha256", acceptedSha256);
    report.put("sourceTotal", SOURCE_TOTAL);
    report.put("usableCanonical", USABLE);
    report.put("duplicateMappings", DUPLICATES);
    report.put("explicitAnomalies", ANOMALIES);
    report.put("baselineExisting", BASELINE_EXISTING);
    report.put("baselineIdentitySha256", baselineIdentitySha256);
    report.put("sharedBaselineForensicIdentitySha256", SHARED_BASELINE_FORENSIC_SHA256);
    report.put("baselineAcceptedIdentityIntersection", EXPECTED_EXACT_REUSE);
    report.put("exactReuse", resumed.mappedExistingCount());
    report.put("newAccepted", resumed.importedCount());
    report.put("finalQuestionCount", count("q_question"));
    report.put("idempotentReplayChunks", replay.replayedCount());
    report.put("idempotentReplayNewQuestions", 0);
    report.put("chunkCount", chunks.size());
    report.put("elapsedSeconds", Math.round(elapsedSeconds * 1000.0) / 1000.0);
    report.put("recordsPerSecond", Math.round(SOURCE_TOTAL / elapsedSeconds * 100.0) / 100.0);
    report.put("peakObservedHeapBytes", resumed.peakHeapBytes());
    report.put("rightsStatus", "UNVERIFIED");
    report.put("usageRestriction", "INTERNAL_ONLY");
    report.put("answerLeakageBeforeSettlement", false);
    report.put("orphanCount", 0);
    report.put("qualificationDistribution", distribution("""
        SELECT qualification.qualification_code AS code, COUNT(*) AS amount
        FROM q_question question
        JOIN exam_qualification qualification ON qualification.id=question.qualification_id
        WHERE question.deleted=0 GROUP BY qualification.qualification_code ORDER BY code
        """));
    report.put("subjectDistribution", distribution("""
        SELECT subject.subject_code AS code, COUNT(*) AS amount
        FROM q_question question JOIN exam_subject subject ON subject.id=question.subject_id
        WHERE question.deleted=0 GROUP BY subject.subject_code ORDER BY code
        """));
    report.put("questionTypeDistribution", distribution("""
        SELECT question_type AS code, COUNT(*) AS amount
        FROM q_question WHERE deleted=0 GROUP BY question_type ORDER BY code
        """));
  }

  private Map<String, Long> distribution(String sql) {
    Map<String, Long> values = new LinkedHashMap<>();
    jdbc.query(sql, rs -> {
      values.put(rs.getString("code"), rs.getLong("amount"));
    });
    return values;
  }

  private void verifyPackageManifest(JsonNode manifest) throws IOException {
    assertThat(manifest.path("schemaVersion").asText())
        .isEqualTo("w3-question-import-package/1.0");
    JsonNode canonical = manifest.path("canonicalInput");
    assertThat(canonical.path("sha256").asText()).isEqualTo(CANONICAL_SHA256);
    assertThat(sha256(Path.of(canonical.path("path").asText())))
        .isEqualTo(CANONICAL_SHA256);
    JsonNode counts = manifest.path("countReconciliation");
    assertThat(counts.path("sourceTotal").asInt()).isEqualTo(SOURCE_TOTAL);
    assertThat(counts.path("usable").asInt()).isEqualTo(USABLE);
    assertThat(counts.path("duplicates").asInt()).isEqualTo(DUPLICATES);
    assertThat(counts.path("anomalies").asInt()).isEqualTo(ANOMALIES);
    assertThat(counts.path("localRecoveries").asInt()).isEqualTo(14);
    JsonNode policy = manifest.path("importPolicy");
    assertThat(policy.path("rightsStatus").asText()).isEqualTo("UNVERIFIED");
    assertThat(policy.path("usageRestriction").asText()).isEqualTo("INTERNAL_ONLY");
    assertThat(policy.path("anomaliesPracticeEligible").asBoolean()).isFalse();
    assertThat(policy.path("anomaliesPublishable").asBoolean()).isFalse();
    Path master = packageRoot.resolve(manifest.path("masterArtifact").path("relativePath").asText());
    assertThat(sha256(master)).isEqualTo(manifest.path("masterArtifact").path("sha256").asText());
    acceptedSha256 = manifest.path("acceptedInput").path("sha256").asText();
    assertThat(acceptedSha256).matches("[0-9a-f]{64}");
    assertThat(sha256(Path.of(manifest.path("acceptedInput").path("path").asText())))
        .isEqualTo(acceptedSha256);
  }

  private List<Chunk> loadAndVerifyChunks(JsonNode manifest) throws IOException {
    List<Chunk> result = new ArrayList<>();
    int total = 0;
    int eligible = 0;
    int expectedIndex = 1;
    for (JsonNode value : manifest.path("chunks")) {
      int index = value.path("index").asInt();
      assertThat(index).isEqualTo(expectedIndex++);
      Path path = packageRoot.resolve(value.path("relativePath").asText()).normalize();
      assertThat(path.startsWith(packageRoot)).isTrue();
      assertThat(path.getFileName().toString()).isEqualTo("canonical-questions.jsonl");
      String digest = value.path("sha256").asText();
      assertThat(sha256(path)).isEqualTo(digest);
      Chunk chunk = new Chunk(index, path, digest, value.path("totalCount").asInt(),
          value.path("eligibleCount").asInt());
      result.add(chunk);
      total += chunk.totalCount();
      eligible += chunk.eligibleCount();
    }
    assertThat(total).isEqualTo(SOURCE_TOTAL);
    assertThat(eligible).isEqualTo(USABLE);
    return List.copyOf(result);
  }

  private Path requiredPackageRoot() throws IOException {
    String configured = System.getenv("W3_IMPORT_PACKAGE");
    if (configured == null || configured.isBlank()) {
      throw new IllegalStateException("W3_IMPORT_PACKAGE is required for the full import Gate");
    }
    Path root = Path.of(configured).toRealPath();
    if (!Files.isDirectory(root) || !Files.isRegularFile(root.resolve("import-manifest.json"))) {
      throw new IllegalStateException("W3_IMPORT_PACKAGE is not a verified package directory");
    }
    return root;
  }

  private long sumBatches(List<Long> batchIds, String column) {
    return jdbc.queryForObject("SELECT COALESCE(SUM(%s),0) FROM q_question_import_batch WHERE id IN (%s)"
        .formatted(column, placeholders(batchIds.size())), Long.class, batchIds.toArray());
  }

  private long countItems(List<Long> batchIds, String where) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM q_question_import_item WHERE import_batch_id IN (%s) "
        .formatted(placeholders(batchIds.size())) + "AND " + where, Long.class, batchIds.toArray());
  }

  private long countDistinctSourceRows(List<Long> batchIds) {
    return jdbc.queryForObject("""
        SELECT COUNT(DISTINCT JSON_UNQUOTE(JSON_EXTRACT(canonical_json,'$.sourceTrace.rowNo')))
        FROM q_question_import_item WHERE import_batch_id IN (%s)
        """.formatted(placeholders(batchIds.size())), Long.class, batchIds.toArray());
  }

  private int minSourceRow(List<Long> batchIds) {
    return jdbc.queryForObject("""
        SELECT MIN(CAST(JSON_UNQUOTE(JSON_EXTRACT(canonical_json,'$.sourceTrace.rowNo')) AS UNSIGNED))
        FROM q_question_import_item WHERE import_batch_id IN (%s)
        """.formatted(placeholders(batchIds.size())), Integer.class, batchIds.toArray());
  }

  private int maxSourceRow(List<Long> batchIds) {
    return jdbc.queryForObject("""
        SELECT MAX(CAST(JSON_UNQUOTE(JSON_EXTRACT(canonical_json,'$.sourceTrace.rowNo')) AS UNSIGNED))
        FROM q_question_import_item WHERE import_batch_id IN (%s)
        """.formatted(placeholders(batchIds.size())), Integer.class, batchIds.toArray());
  }

  private String placeholders(int count) {
    return String.join(",", java.util.Collections.nCopies(count, "?"));
  }

  private long count(String table) {
    if (!Set.of("q_question", "q_question_version", "q_question_import_batch",
        "q_question_import_item", "q_question_mutation").contains(table)) {
      throw new IllegalArgumentException("unsupported count table");
    }
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  private Map<String, Long> rowCounts() {
    Map<String, Long> values = new LinkedHashMap<>();
    for (String table : List.of("q_question", "q_question_version", "q_question_import_batch",
        "q_question_import_item", "q_question_mutation")) {
      values.put(table, count(table));
    }
    return values;
  }

  private Map<String, Long> practiceBusinessCounts() {
    Map<String, Long> values = new LinkedHashMap<>();
    for (String table : List.of("practice_attempt", "practice_attempt_answer", "practice_mistake")) {
      values.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
    }
    return values;
  }

  private long usedHeap() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }

  private UUID mutation(String scope) {
    return UUID.nameUUIDFromBytes((acceptedSha256 + ":" + scope).getBytes(StandardCharsets.UTF_8));
  }

  private PlatformPrincipal admin(long id) {
    return new PlatformPrincipal(id, "w3-admin-" + id, "w3-session-" + id,
        List.of("QUESTION_ADMIN"), ADMIN_PERMISSIONS, 1);
  }

  private String sha256(Path path) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var stream = Files.newInputStream(path)) {
        byte[] buffer = new byte[1024 * 1024];
        int count;
        while ((count = stream.read(buffer)) >= 0) {
          if (count > 0) {
            digest.update(buffer, 0, count);
          }
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private String sha256Text(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private void deleteScratchTree(Path root) throws IOException {
    Path expectedParent = packageRoot.getParent().toRealPath();
    Path real = root.toRealPath();
    if (!real.getParent().equals(expectedParent)
        || !real.getFileName().toString().startsWith(".w3-question-import-gate-")) {
      throw new IllegalStateException("Refusing to delete unexpected Gate scratch path: " + real);
    }
    try (var paths = Files.walk(real)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static void requireTestSchema(String schema) {
    if (schema == null || !schema.matches("w3_question_import_[0-9]+_[0-9]+_test")) {
      throw new IllegalStateException("Refusing non-test schema: " + schema);
    }
  }

  private record Chunk(int index, Path path, String sha256, int totalCount, int eligibleCount) {
  }

  private record RunStats(int totalCount, int eligibleCount, int importedCount,
                          int mappedExistingCount, int replayedCount, List<Long> batchIds,
                          long peakHeapBytes) {
  }

  private record Mysql(String baseUrl, String username, String password) {
    static Mysql fromEnvironment() {
      return new Mysql(value("MYSQL_TEST_URL",
          "jdbc:mysql://127.0.0.1:33307/ai_exam_platform_test?useUnicode=true&characterEncoding=utf8"
              + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"),
          value("MYSQL_TEST_USERNAME", "root"), value("MYSQL_TEST_PASSWORD", ""));
    }

    void requireTestControlDatabase() {
      String plain = baseUrl.contains("?") ? baseUrl.substring(0, baseUrl.indexOf('?')) : baseUrl;
      String database = plain.substring(plain.lastIndexOf('/') + 1);
      if (!database.endsWith("_test")) {
        throw new IllegalStateException("MYSQL_TEST_URL must name a _test control database");
      }
    }

    String serverUrl() {
      int query = baseUrl.indexOf('?');
      String plain = query < 0 ? baseUrl : baseUrl.substring(0, query);
      String suffix = query < 0 ? "" : baseUrl.substring(query);
      return plain.substring(0, plain.lastIndexOf('/') + 1) + suffix;
    }

    String url(String schema) {
      requireTestSchema(schema);
      int query = baseUrl.indexOf('?');
      String plain = query < 0 ? baseUrl : baseUrl.substring(0, query);
      String suffix = query < 0 ? "" : baseUrl.substring(query);
      return plain.substring(0, plain.lastIndexOf('/') + 1) + schema + suffix;
    }

    void createSchema(String schema) throws Exception {
      requireTestSchema(schema);
      try (Connection connection = DriverManager.getConnection(serverUrl(), username, password);
           Statement statement = connection.createStatement()) {
        statement.execute("CREATE DATABASE `" + schema
            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
      }
    }

    void dropSchema(String schema) throws Exception {
      requireTestSchema(schema);
      try (Connection connection = DriverManager.getConnection(serverUrl(), username, password);
           Statement statement = connection.createStatement()) {
        statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
      }
    }

    boolean schemaExists(String schema) throws Exception {
      requireTestSchema(schema);
      try (Connection connection = DriverManager.getConnection(serverUrl(), username, password);
           var statement = connection.prepareStatement(
               "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
        statement.setString(1, schema);
        try (var result = statement.executeQuery()) {
          result.next();
          return result.getInt(1) != 0;
        }
      }
    }

    private static String value(String name, String fallback) {
      String value = System.getenv(name);
      return value == null || value.isBlank() ? fallback : value;
    }
  }
}
