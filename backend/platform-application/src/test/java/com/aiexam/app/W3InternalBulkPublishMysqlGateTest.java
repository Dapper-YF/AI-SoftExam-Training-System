package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.app.migration.deploymentcontrol.AttemptState;
import com.aiexam.app.migration.v9.V9AuthorizedDatabaseOrchestrator;
import com.aiexam.app.migration.v9.V9ExecutionFence;
import com.aiexam.app.migration.v9.V9MigrationOnlyRunner;
import com.aiexam.common.error.BusinessException;
import com.aiexam.common.security.CurrentUserProvider;
import com.aiexam.common.security.PlatformPrincipal;
import com.aiexam.question.admin.application.QuestionAdminApplicationService;
import com.aiexam.question.admin.application.QuestionAdminCurrentUser;
import com.aiexam.question.admin.application.QuestionImportApplicationService;
import com.aiexam.question.admin.application.QuestionInternalBulkPublishRunner;
import com.aiexam.question.admin.application.QuestionInternalBulkPublishRunner.Actor;
import com.aiexam.question.admin.application.QuestionInternalBulkPublishRunner.BulkPublishCommand;
import com.aiexam.question.admin.application.QuestionInternalBulkPublishRunner.InjectedBulkPublishFailure;
import com.aiexam.question.admin.application.QuestionInternalBulkPublishRunner.PrincipalExecutor;
import com.aiexam.question.admin.dto.QuestionImportRequest;
import com.aiexam.question.admin.dto.QuestionImportResultDto;
import com.aiexam.question.admin.dto.QuestionReviewRequest;
import com.aiexam.question.admin.dto.QuestionWorkflowRequest;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository.BulkPublishCandidate;
import com.aiexam.foundation.application.FoundationApplicationService;
import com.aiexam.foundation.infra.JdbcFoundationRepository;
import com.aiexam.question.practice.attempt.application.PracticeAttemptApplicationService;
import com.aiexam.question.practice.attempt.application.PracticePrincipal;
import com.aiexam.question.practice.attempt.dto.CreatePracticeAttemptRequest;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "W3_INTERNAL_BULK_PUBLISH_MYSQL_GATE", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class W3InternalBulkPublishMysqlGateTest {
  private static final String CANONICAL_SHA256 =
      "7b1bb38b23826361ff41a023062d2ae788abc6c86f773e744cac91fec95577ab";
  private static final int SOURCE_TOTAL = 29_538;
  private static final int USABLE = 26_284;
  private static final int DUPLICATES = 25;
  private static final int ANOMALIES = 3_229;
  private static final int BASELINE_EXISTING = 149;
  private static final int FINAL_QUESTION_COUNT = BASELINE_EXISTING + USABLE;
  private static final String LEGACY_FIXTURE_DOCUMENT_SHA256 =
      "5f00f482a5bf059d7d5c7a4d6eb4ae5df4640759e3fb98dd6828322c8ec94e79";
  private static final String SCHEMA = "w3_internal_publish_" + ProcessHandle.current().pid()
      + "_" + System.currentTimeMillis() + "_test";
  private static final Mysql MYSQL = Mysql.fromEnvironment();
  private static final Actor AUTHOR = new Actor(881001L, "admin");
  private static final Actor REVIEWER = new Actor(881002L, "question_reviewer");

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final Map<String, Object> report = new LinkedHashMap<>();
  private JdbcTemplate jdbc;
  private QuestionImportApplicationService importService;
  private QuestionAdminApplicationService adminService;
  private QuestionInternalBulkPublishRunner runner;
  private AtomicReference<PlatformPrincipal> principal;
  private Path packageRoot;
  private Path scratchRoot;
  private Path reportPath;
  private String acceptedSha256;
  private List<Chunk> chunks;
  private boolean schemaCreated;

  @BeforeAll
  void createRandomSchemaAndMigrate() throws Exception {
    packageRoot = requiredPackageRoot();
    reportPath = packageRoot.resolve("mysql-gate-internal-bulk-publish-report.json");
    JsonNode manifest = mapper.readTree(packageRoot.resolve("import-manifest.json").toFile());
    verifyPackageManifest(manifest);
    chunks = loadAndVerifyChunks(manifest);
    scratchRoot = Files.createDirectory(packageRoot.getParent().resolve(
        ".w3-internal-bulk-publish-gate-" + UUID.randomUUID()));

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

      JdbcQuestionAdminRepository repository = new JdbcQuestionAdminRepository(
          jdbc, new DataSourceTransactionManager(dataSource), mapper);
      principal = new AtomicReference<>(importAdmin(AUTHOR.userId()));
      CurrentUserProvider provider = () -> Optional.ofNullable(principal.get());
      QuestionAdminCurrentUser currentUser = new QuestionAdminCurrentUser(provider);
      importService = new QuestionImportApplicationService(
          repository, currentUser, mapper, packageRoot.getParent());
      adminService = new QuestionAdminApplicationService(repository, currentUser, mapper);
      runner = new QuestionInternalBulkPublishRunner(repository, adminService,
          principalExecutor(), mapper);
    } catch (Exception exception) {
      MYSQL.dropSchema(SCHEMA);
      schemaCreated = false;
      throw exception;
    }
  }

  @AfterAll
  void cleanup() throws Exception {
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
  void publishesW3DraftsThroughTwoPersonRecoverableInternalGate() throws Exception {
    QuestionImportResultDto baseline = importDisjointLegacyBaseline();
    Set<Long> baselineQuestionIds = new LinkedHashSet<>();
    Set<Long> baselineVersionIds = new LinkedHashSet<>();
    baseline.items().forEach(item -> {
      baselineQuestionIds.add(item.questionId());
      baselineVersionIds.add(item.questionVersionId());
    });
    assertThat(baselineQuestionIds).hasSize(BASELINE_EXISTING);
    assertThat(count("q_question")).isEqualTo(BASELINE_EXISTING);

    RunStats imported = importChunks(chunks);
    assertThat(imported.totalCount()).isEqualTo(SOURCE_TOTAL);
    assertThat(imported.eligibleCount()).isEqualTo(USABLE);
    assertThat(imported.importedCount()).isEqualTo(USABLE);
    assertThat(count("q_question")).isEqualTo(FINAL_QUESTION_COUNT);
    verifyImportedDraftGate(imported.batchIds(), baselineQuestionIds, baselineVersionIds);
    Map<String, Long> practiceSentinel = practiceBusinessCounts();

    BulkPublishCandidate first = firstW3Candidate(imported.batchIds());
    proveTwoPersonPermissionsAndSelfRules(first);

    long reviewRowsBeforeBulk = count("q_question_review");
    long auditRowsBeforeBulk = count("q_question_audit_event");
    Path checkpoint = scratchRoot.resolve("w3-internal-bulk-publish-checkpoint.json");
    assertThatThrownBy(() -> runner.run(command(imported.batchIds(), checkpoint, 400, 450L,
        "w3-internal-bulk-publish-gate")))
        .isInstanceOf(InjectedBulkPublishFailure.class);
    long publishedAfterFailure = w3Published(imported.batchIds());
    assertThat(publishedAfterFailure).isBetween(450L, (long) USABLE - 1);
    assertThat(Files.exists(checkpoint)).isTrue();

    long started = System.nanoTime();
    var resumed = runner.run(command(imported.batchIds(), checkpoint, 400, null,
        "w3-internal-bulk-publish-gate"));
    double elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0;

    assertThat(w3Published(imported.batchIds())).isEqualTo(USABLE);
    assertThat(w3StatusCount(imported.batchIds(), "DRAFT")).isZero();
    assertThat(w3StatusCount(imported.batchIds(), "IN_REVIEW")).isZero();
    assertThat(w3PointerClosure(imported.batchIds())).isEqualTo(USABLE);
    assertThat(w3Rights(imported.batchIds())).containsExactly(Map.entry(
        "UNVERIFIED|INTERNAL_ONLY", (long) USABLE));
    assertThat(count("q_question")).isEqualTo(FINAL_QUESTION_COUNT);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question WHERE id IN (%s) AND deleted=0"
        .formatted(placeholders(baselineQuestionIds.size())), Long.class,
        baselineQuestionIds.toArray())).isEqualTo((long) BASELINE_EXISTING);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question_version WHERE id IN (%s) AND deleted=0"
        .formatted(placeholders(baselineVersionIds.size())), Long.class,
        baselineVersionIds.toArray())).isEqualTo((long) BASELINE_EXISTING);
    assertThat(practiceBusinessCounts()).isEqualTo(practiceSentinel);
    verifyPublishedAudit(imported.batchIds(), reviewRowsBeforeBulk, auditRowsBeforeBulk);
    verifyPointerClosureAndOrphans();
    proveStudentCanReadInternalPublishedWithoutAnswers(imported.batchIds());

    long versionsBeforeReplay = count("q_question_version");
    long reviewsBeforeReplay = count("q_question_review");
    long auditsBeforeReplay = count("q_question_audit_event");
    runner.run(command(imported.batchIds(), checkpoint, 400, null,
        "w3-internal-bulk-publish-gate"));
    Path freshCheckpoint = scratchRoot.resolve("w3-internal-bulk-publish-fresh-scan.json");
    var freshReplay = runner.run(command(imported.batchIds(), freshCheckpoint, 400, null,
        "w3-internal-bulk-publish-gate-fresh-scan"));
    assertThat(freshReplay.skippedAlreadyPublishedCount()).isEqualTo(USABLE);
    assertThat(count("q_question_version")).isEqualTo(versionsBeforeReplay);
    assertThat(count("q_question_review")).isEqualTo(reviewsBeforeReplay);
    assertThat(count("q_question_audit_event")).isEqualTo(auditsBeforeReplay);

    populateReport(imported, resumed, freshReplay, elapsedSeconds, checkpoint,
        freshCheckpoint, publishedAfterFailure);
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
    principal.set(importAdmin(AUTHOR.userId()));
    QuestionImportResultDto result = importService.confirm(new QuestionImportRequest(
        directory.toString(), mutation("legacy-disjoint-baseline-149")));
    assertThat(result.totalCount()).isEqualTo(BASELINE_EXISTING);
    assertThat(result.importedCount()).isEqualTo(BASELINE_EXISTING);
    return result;
  }

  private RunStats importChunks(List<Chunk> selected) {
    principal.set(importAdmin(AUTHOR.userId()));
    List<Long> batchIds = new ArrayList<>();
    int total = 0;
    int eligible = 0;
    int imported = 0;
    long peakHeap = usedHeap();
    for (Chunk chunk : selected) {
      QuestionImportResultDto result = importService.confirm(new QuestionImportRequest(
          chunk.path().toString(), mutation("chunk-" + chunk.index())));
      assertThat(result.inputSha256()).isEqualTo(chunk.sha256());
      assertThat(result.totalCount()).isEqualTo(chunk.totalCount());
      assertThat(result.eligibleCount()).isEqualTo(chunk.eligibleCount());
      total += result.totalCount();
      eligible += result.eligibleCount();
      imported += result.importedCount();
      batchIds.add(result.batchId());
      peakHeap = Math.max(peakHeap, usedHeap());
    }
    return new RunStats(total, eligible, imported, List.copyOf(batchIds), peakHeap);
  }

  private void proveTwoPersonPermissionsAndSelfRules(BulkPublishCandidate candidate) {
    principal.set(authorOnly());
    var submitted = adminService.submitReview(candidate.questionId(), candidate.questionVersionId(),
        new QuestionWorkflowRequest(UUID.randomUUID(), candidate.rowVersion(), "role probe"));
    assertForbidden(() -> adminService.review(candidate.questionId(), candidate.questionVersionId(),
        new QuestionReviewRequest(UUID.randomUUID(), submitted.rowVersion(), "APPROVED", "no")));
    assertForbidden(() -> adminService.publish(candidate.questionId(), candidate.questionVersionId(),
        new QuestionWorkflowRequest(UUID.randomUUID(), submitted.rowVersion(), "no")));

    principal.set(reviewerOnly());
    assertForbidden(() -> adminService.submitReview(candidate.questionId(),
        candidate.questionVersionId(), new QuestionWorkflowRequest(UUID.randomUUID(),
            submitted.rowVersion(), "reviewer cannot submit")));

    principal.set(blendedSameSubmitter());
    assertThatThrownBy(() -> adminService.review(candidate.questionId(), candidate.questionVersionId(),
        new QuestionReviewRequest(UUID.randomUUID(), submitted.rowVersion(), "APPROVED", "self")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).status())
            .isEqualTo(HttpStatus.FORBIDDEN))
        .hasMessageContaining("submitter cannot review");

    principal.set(reviewerOnly());
    var approved = adminService.review(candidate.questionId(), candidate.questionVersionId(),
        new QuestionReviewRequest(UUID.randomUUID(), submitted.rowVersion(), "APPROVED", "ok"));

    principal.set(blendedSameSubmitter());
    assertThatThrownBy(() -> adminService.publish(candidate.questionId(), candidate.questionVersionId(),
        new QuestionWorkflowRequest(UUID.randomUUID(), approved.rowVersion(), "self publish")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).status())
            .isEqualTo(HttpStatus.FORBIDDEN))
        .hasMessageContaining("submitter cannot publish");
  }

  private void verifyImportedDraftGate(List<Long> batchIds, Set<Long> baselineQuestionIds,
      Set<Long> baselineVersionIds) {
    assertThat(sumBatches(batchIds, "total_count")).isEqualTo(SOURCE_TOTAL);
    assertThat(sumBatches(batchIds, "eligible_count")).isEqualTo(USABLE);
    assertThat(sumBatches(batchIds, "imported_count")).isEqualTo(USABLE);
    assertThat(countItems(batchIds, "1=1")).isEqualTo(SOURCE_TOTAL);
    assertThat(countItems(batchIds, "item_status='IMPORTED_DRAFT'")).isEqualTo(USABLE);
    assertThat(countItems(batchIds, "error_code='EXACT_EXISTING_QUESTION'")).isZero();
    assertThat(countItems(batchIds,
        "JSON_UNQUOTE(JSON_EXTRACT(canonical_json,'$.quality.sourceStatus'))='DUPLICATE_SOURCE'"))
        .isEqualTo(DUPLICATES);
    assertThat(countItems(batchIds,
        "JSON_UNQUOTE(JSON_EXTRACT(canonical_json,'$.quality.sourceStatus'))='SOURCE_ERROR'"))
        .isEqualTo(ANOMALIES);
    assertThat(w3StatusCount(batchIds, "DRAFT")).isEqualTo(USABLE);
    assertThat(w3Published(batchIds)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question WHERE id IN (%s) AND deleted=0"
        .formatted(placeholders(baselineQuestionIds.size())), Long.class,
        baselineQuestionIds.toArray())).isEqualTo((long) BASELINE_EXISTING);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question_version WHERE id IN (%s) AND deleted=0"
        .formatted(placeholders(baselineVersionIds.size())), Long.class,
        baselineVersionIds.toArray())).isEqualTo((long) BASELINE_EXISTING);
  }

  private void verifyPublishedAudit(List<Long> batchIds, long reviewRowsBeforeBulk,
      long auditRowsBeforeBulk) {
    assertThat(count("q_question_review") - reviewRowsBeforeBulk).isEqualTo(USABLE - 1L);
    assertThat(count("q_question_audit_event") - auditRowsBeforeBulk)
        .isEqualTo((USABLE - 1L) * 3L + 1L);
    Map<String, Long> events = distribution("""
        SELECT event.event_type AS code, COUNT(*) AS amount
        FROM q_question_audit_event event
        JOIN q_question_version version ON version.id=event.question_version_id
        WHERE version.import_batch_id IN (%s)
        GROUP BY event.event_type ORDER BY event.event_type
        """.formatted(placeholders(batchIds.size())), batchIds.toArray());
    assertThat(events).containsEntry("SUBMIT_REVIEW", (long) USABLE)
        .containsEntry("APPROVED_REVIEW", (long) USABLE)
        .containsEntry("PUBLISH", (long) USABLE);
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM q_question_review review
        JOIN q_question_version version ON version.id=review.question_version_id
        WHERE version.import_batch_id IN (%s)
          AND review.submitted_by = review.reviewer_id
        """.formatted(placeholders(batchIds.size())), Long.class, batchIds.toArray())).isZero();
  }

  private void proveStudentCanReadInternalPublishedWithoutAnswers(List<Long> batchIds) {
    Map<String, Object> scope = jdbc.queryForMap("""
        SELECT q.qualification_id, q.subject_id
        FROM q_question q
        JOIN q_question_version v ON v.id=q.current_published_version_id
        WHERE v.import_batch_id IN (%s)
        ORDER BY q.id LIMIT 1
        """.formatted(placeholders(batchIds.size())), batchIds.toArray());
    long qualificationId = ((Number) scope.get("qualification_id")).longValue();
    long subjectId = ((Number) scope.get("subject_id")).longValue();
    var practice = new PracticeAttemptApplicationService(
        new JdbcPracticeAttemptRepository(jdbc),
        () -> new PracticePrincipal(881004L, "student", Set.of("STUDENT")),
        new FoundationApplicationService(new JdbcFoundationRepository(jdbc)),
        Clock.fixed(Instant.parse("2026-07-18T10:00:00Z"), ZoneOffset.UTC));
    var attempt = practice.create(new CreatePracticeAttemptRequest(UUID.randomUUID(),
        qualificationId, subjectId, "PRACTICE", "PER_QUESTION", 1,
        new CreatePracticeAttemptRequest.Source("DAILY", null, null, null, null)));
    assertThat(attempt.actualCount()).isEqualTo(1);
    var sub = attempt.questions().get(0).subQuestions().get(0);
    assertThat(sub.answer()).isNull();
    assertThat(sub.grading()).isNull();
    assertThat(sub.solution()).isNull();
  }

  private BulkPublishCandidate firstW3Candidate(List<Long> batchIds) {
    return jdbc.queryForObject("""
        SELECT q.id AS question_id, q.current_working_version_id AS question_version_id,
               v.status, v.row_version, v.author_id, v.submitted_by, v.reviewed_by,
               v.review_decision, v.copyright_status, v.usage_restriction,
               q.current_working_version_id, q.current_published_version_id
        FROM q_question q
        JOIN q_question_version v ON v.id=q.current_working_version_id
        WHERE v.import_batch_id IN (%s) AND v.status='DRAFT'
        ORDER BY q.id LIMIT 1
        """.formatted(placeholders(batchIds.size())),
        (rs, rowNum) -> new BulkPublishCandidate(
            rs.getLong("question_id"), rs.getLong("question_version_id"),
            rs.getString("status"), rs.getLong("row_version"),
            nullableLong(rs, "author_id"), nullableLong(rs, "submitted_by"),
            nullableLong(rs, "reviewed_by"), rs.getString("review_decision"),
            rs.getString("copyright_status"), rs.getString("usage_restriction"),
            nullableLong(rs, "current_working_version_id"),
            nullableLong(rs, "current_published_version_id")),
        batchIds.toArray());
  }

  private BulkPublishCommand command(List<Long> batchIds, Path checkpoint, int batchSize,
      Long failAfterPublished, String runId) {
    return new BulkPublishCommand(runId, batchIds, batchSize, USABLE, checkpoint, AUTHOR,
        REVIEWER, "UNVERIFIED", "INTERNAL_ONLY", failAfterPublished);
  }

  private PrincipalExecutor principalExecutor() {
    return new PrincipalExecutor() {
      @Override
      public <T> T callAs(Actor actor, java.util.function.Supplier<T> work) {
        PlatformPrincipal previous = principal.get();
        try {
          if (actor.userId() == AUTHOR.userId()) {
            principal.set(authorOnly());
          } else if (actor.userId() == REVIEWER.userId()) {
            principal.set(reviewerOnly());
          } else {
            throw new IllegalArgumentException("Unsupported bulk publish actor: " + actor.userId());
          }
          return work.get();
        } finally {
          principal.set(previous);
        }
      }
    };
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
        "SELECT COUNT(*) FROM q_sub_question child LEFT JOIN q_question_version parent "
            + "ON parent.id=child.question_version_id AND parent.question_id=child.question_id "
            + "WHERE parent.id IS NULL",
        "SELECT COUNT(*) FROM q_answer_key child LEFT JOIN q_sub_question parent "
            + "ON parent.id=child.sub_question_id WHERE parent.id IS NULL");
    for (String query : orphanQueries) {
      assertThat(jdbc.queryForObject(query, Long.class)).isZero();
    }
  }

  private void populateReport(RunStats imported,
      QuestionInternalBulkPublishRunner.BulkPublishResult resumed,
      QuestionInternalBulkPublishRunner.BulkPublishResult freshReplay,
      double elapsedSeconds, Path checkpoint, Path freshCheckpoint, long publishedAfterFailure)
      throws IOException {
    report.put("schemaVersion", "w3-internal-bulk-publish-mysql-gate/1.0");
    report.put("canonicalSha256", CANONICAL_SHA256);
    report.put("acceptedSha256", acceptedSha256);
    report.put("sourceTotal", SOURCE_TOTAL);
    report.put("usableCanonical", USABLE);
    report.put("duplicateMappings", DUPLICATES);
    report.put("explicitAnomalies", ANOMALIES);
    report.put("baselineExisting", BASELINE_EXISTING);
    report.put("finalQuestionCount", count("q_question"));
    report.put("bulkBatchSize", 400);
    report.put("publishedAfterInjectedFailure", publishedAfterFailure);
    report.put("w3Published", w3Published(imported.batchIds()));
    report.put("w3DraftRemaining", w3StatusCount(imported.batchIds(), "DRAFT"));
    report.put("w3InReviewRemaining", w3StatusCount(imported.batchIds(), "IN_REVIEW"));
    report.put("publishedPointerClosure", w3PointerClosure(imported.batchIds()));
    report.put("rightsStatus", "UNVERIFIED");
    report.put("usageRestriction", "INTERNAL_ONLY");
    report.put("checkpointPath", checkpoint.toAbsolutePath().normalize().toString());
    report.put("checkpointSha256", sha256(checkpoint));
    report.put("freshReplayCheckpointPath", freshCheckpoint.toAbsolutePath().normalize().toString());
    report.put("freshReplayCheckpointSha256", sha256(freshCheckpoint));
    report.put("resumeSubmittedCount", resumed.submittedCount());
    report.put("resumeApprovedCount", resumed.approvedCount());
    report.put("resumePublishedCount", resumed.publishedCount());
    report.put("freshReplaySkippedAlreadyPublished", freshReplay.skippedAlreadyPublishedCount());
    report.put("elapsedSeconds", Math.round(elapsedSeconds * 1000.0) / 1000.0);
    report.put("recordsPerSecond", Math.round(USABLE / elapsedSeconds * 100.0) / 100.0);
    report.put("peakObservedHeapBytes",
        Math.max(imported.peakHeapBytes(), resumed.peakObservedHeapBytes()));
    report.put("studentInternalPublishedVisible", true);
    report.put("answerLeakageBeforeSettlement", false);
    report.put("orphanCount", 0);
    report.put("questionTypeDistribution", distribution("""
        SELECT question_type AS code, COUNT(*) AS amount
        FROM q_question WHERE deleted=0 GROUP BY question_type ORDER BY code
        """));
  }

  private static Connection openConnection(DriverManagerDataSource dataSource) {
    try {
      return dataSource.getConnection();
    } catch (SQLException exception) {
      throw new IllegalStateException("cannot open V9 verification connection", exception);
    }
  }

  private void verifyPackageManifest(JsonNode manifest) throws IOException {
    assertThat(manifest.path("schemaVersion").asText())
        .isEqualTo("w3-question-import-package/1.0");
    JsonNode canonical = manifest.path("canonicalInput");
    assertThat(canonical.path("sha256").asText()).isEqualTo(CANONICAL_SHA256);
    assertThat(sha256(Path.of(canonical.path("path").asText()))).isEqualTo(CANONICAL_SHA256);
    JsonNode counts = manifest.path("countReconciliation");
    assertThat(counts.path("sourceTotal").asInt()).isEqualTo(SOURCE_TOTAL);
    assertThat(counts.path("usable").asInt()).isEqualTo(USABLE);
    assertThat(counts.path("duplicates").asInt()).isEqualTo(DUPLICATES);
    assertThat(counts.path("anomalies").asInt()).isEqualTo(ANOMALIES);
    JsonNode policy = manifest.path("importPolicy");
    assertThat(policy.path("rightsStatus").asText()).isEqualTo("UNVERIFIED");
    assertThat(policy.path("usageRestriction").asText()).isEqualTo("INTERNAL_ONLY");
    acceptedSha256 = manifest.path("acceptedInput").path("sha256").asText();
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
      throw new IllegalStateException("W3_IMPORT_PACKAGE is required for the publish Gate");
    }
    Path root = Path.of(configured).toRealPath();
    if (!Files.isDirectory(root) || !Files.isRegularFile(root.resolve("import-manifest.json"))) {
      throw new IllegalStateException("W3_IMPORT_PACKAGE is not a verified package directory");
    }
    return root;
  }

  private long w3Published(List<Long> batchIds) {
    return jdbc.queryForObject("""
        SELECT COUNT(*) FROM q_question q
        JOIN q_question_version v ON v.id=q.current_published_version_id
        WHERE v.import_batch_id IN (%s) AND q.status='PUBLISHED' AND v.status='PUBLISHED'
          AND q.deleted=0 AND v.deleted=0
        """.formatted(placeholders(batchIds.size())), Long.class, batchIds.toArray());
  }

  private long w3StatusCount(List<Long> batchIds, String status) {
    return jdbc.queryForObject("""
        SELECT COUNT(*) FROM q_question q
        JOIN q_question_version v
          ON v.id=COALESCE(q.current_working_version_id, q.current_published_version_id)
        WHERE v.import_batch_id IN (%s) AND v.status=? AND q.deleted=0 AND v.deleted=0
        """.formatted(placeholders(batchIds.size())), Long.class,
        append(batchIds, status));
  }

  private long w3PointerClosure(List<Long> batchIds) {
    return jdbc.queryForObject("""
        SELECT COUNT(*) FROM q_question q
        JOIN q_question_version v ON v.id=q.current_published_version_id
        WHERE v.import_batch_id IN (%s) AND q.current_working_version_id IS NULL
          AND q.status='PUBLISHED' AND v.status='PUBLISHED' AND q.deleted=0 AND v.deleted=0
        """.formatted(placeholders(batchIds.size())), Long.class, batchIds.toArray());
  }

  private Map<String, Long> w3Rights(List<Long> batchIds) {
    return distribution("""
        SELECT CONCAT(copyright_status, '|', usage_restriction) AS code, COUNT(*) AS amount
        FROM q_question_version WHERE import_batch_id IN (%s) AND deleted=0
        GROUP BY copyright_status, usage_restriction
        """.formatted(placeholders(batchIds.size())), batchIds.toArray());
  }

  private long sumBatches(List<Long> batchIds, String column) {
    return jdbc.queryForObject("SELECT COALESCE(SUM(%s),0) FROM q_question_import_batch WHERE id IN (%s)"
        .formatted(column, placeholders(batchIds.size())), Long.class, batchIds.toArray());
  }

  private long countItems(List<Long> batchIds, String where) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM q_question_import_item WHERE import_batch_id IN (%s) "
        .formatted(placeholders(batchIds.size())) + "AND " + where, Long.class, batchIds.toArray());
  }

  private Map<String, Long> distribution(String sql, Object... args) {
    Map<String, Long> values = new LinkedHashMap<>();
    jdbc.query(sql, rs -> {
      values.put(rs.getString("code"), rs.getLong("amount"));
    }, args);
    return values;
  }

  private Map<String, Long> practiceBusinessCounts() {
    Map<String, Long> values = new LinkedHashMap<>();
    for (String table : List.of("practice_attempt", "practice_attempt_answer", "practice_mistake")) {
      values.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
    }
    return values;
  }

  private Object[] append(List<Long> values, Object tail) {
    List<Object> arguments = new ArrayList<>(values);
    arguments.add(tail);
    return arguments.toArray();
  }

  private String placeholders(int count) {
    return String.join(",", java.util.Collections.nCopies(count, "?"));
  }

  private long count(String table) {
    if (!Set.of("q_question", "q_question_version", "q_question_import_batch",
        "q_question_import_item", "q_question_review", "q_question_audit_event",
        "q_question_mutation").contains(table)) {
      throw new IllegalArgumentException("unsupported count table");
    }
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  private long usedHeap() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }

  private UUID mutation(String scope) {
    return UUID.nameUUIDFromBytes((acceptedSha256 + ":" + scope).getBytes(StandardCharsets.UTF_8));
  }

  private void assertForbidden(Runnable runnable) {
    assertThatThrownBy(runnable::run)
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).status())
            .isEqualTo(HttpStatus.FORBIDDEN));
  }

  private PlatformPrincipal importAdmin(long id) {
    return new PlatformPrincipal(id, "w3-import-admin", "w3-import-session",
        List.of("QUESTION_ADMIN"), List.of("question:create", "question:update",
            "question:submit-review", "question:review", "question:publish", "question:read",
            "question:import"), 1);
  }

  private PlatformPrincipal authorOnly() {
    return new PlatformPrincipal(AUTHOR.userId(), AUTHOR.username(), "w3-author-session",
        List.of("QUESTION_AUTHOR"), List.of("question:read", "question:submit-review"), 1);
  }

  private PlatformPrincipal reviewerOnly() {
    return new PlatformPrincipal(REVIEWER.userId(), REVIEWER.username(), "w3-reviewer-session",
        List.of("QUESTION_REVIEWER"), List.of("question:read", "question:review",
            "question:publish"), 1);
  }

  private PlatformPrincipal blendedSameSubmitter() {
    return new PlatformPrincipal(AUTHOR.userId(), "bad-self-reviewer", "w3-self-session",
        List.of("QUESTION_AUTHOR", "QUESTION_REVIEWER"), List.of("question:read",
            "question:review", "question:publish"), 1);
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

  private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private void deleteScratchTree(Path root) throws IOException {
    Path expectedParent = packageRoot.getParent().toRealPath();
    Path real = root.toRealPath();
    if (!real.getParent().equals(expectedParent)
        || !real.getFileName().toString().startsWith(".w3-internal-bulk-publish-gate-")) {
      throw new IllegalStateException("Refusing to delete unexpected Gate scratch path: " + real);
    }
    try (var paths = Files.walk(real)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static void requireTestSchema(String schema) {
    if (schema == null || !schema.matches("w3_internal_publish_[0-9]+_[0-9]+_test")) {
      throw new IllegalStateException("Refusing non-test schema: " + schema);
    }
  }

  private record Chunk(int index, Path path, String sha256, int totalCount, int eligibleCount) {
  }

  private record RunStats(int totalCount, int eligibleCount, int importedCount,
                          List<Long> batchIds, long peakHeapBytes) {
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
