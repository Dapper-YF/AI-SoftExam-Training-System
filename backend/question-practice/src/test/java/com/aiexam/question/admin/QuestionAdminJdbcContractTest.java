package com.aiexam.question.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.common.error.BusinessException;
import com.aiexam.common.security.CurrentUserProvider;
import com.aiexam.common.security.PlatformPrincipal;
import com.aiexam.question.admin.application.QuestionAdminApplicationService;
import com.aiexam.question.admin.application.QuestionAdminCurrentUser;
import com.aiexam.question.admin.application.QuestionImportApplicationService;
import com.aiexam.question.admin.application.QuestionImportUploadService;
import com.aiexam.question.application.PaperApplicationService;
import com.aiexam.question.admin.dto.QuestionImportRequest;
import com.aiexam.question.admin.dto.QuestionImportTokenConfirmRequest;
import com.aiexam.question.admin.dto.QuestionCopyRequest;
import com.aiexam.question.admin.dto.QuestionReviewRequest;
import com.aiexam.question.admin.dto.QuestionVersionSummaryDto;
import com.aiexam.question.admin.dto.QuestionVersionWriteRequest;
import com.aiexam.question.admin.dto.QuestionWorkflowRequest;
import com.aiexam.question.admin.infra.JdbcQuestionAdminRepository;
import com.aiexam.question.dto.request.QuestionCreateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

class QuestionAdminJdbcContractTest {
  private static final List<String> ALL_PERMISSIONS = List.of(
      "question:create", "question:update", "question:submit-review", "question:review",
      "question:publish", "question:read", "question:import");

  @TempDir
  Path importRoot;

  private JdbcTemplate jdbc;
  private JdbcQuestionAdminRepository repository;
  private QuestionAdminApplicationService adminService;
  private QuestionImportApplicationService importService;
  private QuestionImportUploadService uploadService;
  private QuestionAdminCurrentUser currentUser;
  private AtomicReference<PlatformPrincipal> principal;

  @BeforeEach
  void setUp() {
    var dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:question_admin_" + UUID.randomUUID().toString().replace("-", "")
            + "_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    jdbc = new JdbcTemplate(dataSource);
    createSchema();
    seedScope();
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    repository = new JdbcQuestionAdminRepository(jdbc, new DataSourceTransactionManager(dataSource), mapper);
    principal = new AtomicReference<>(user(1000L));
    CurrentUserProvider provider = () -> Optional.ofNullable(principal.get());
    currentUser = new QuestionAdminCurrentUser(provider);
    adminService = new QuestionAdminApplicationService(repository, currentUser, mapper);
    importService = new QuestionImportApplicationService(repository, currentUser, mapper, importRoot);
    uploadService = new QuestionImportUploadService(importService, currentUser, importRoot);
  }

  @Test
  void multipartPreflightUsesPersistentOpaqueTokenAndConfirmWritesTheRealBatch() {
    byte[] canonical = eligibleCanonical("UPLOAD-1", "d".repeat(64),
        "MIDDLE_DATABASE_SYSTEM_ENGINEER", "BASIC_KNOWLEDGE").getBytes(StandardCharsets.UTF_8);

    var preflight = uploadService.preflight("question-import/1.0", UUID.randomUUID(),
        new BytesMultipartFile("questions.jsonl", canonical));

    assertThat(preflight.token()).matches(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    assertThat(preflight.status()).isEqualTo("READY_TO_CONFIRM");
    assertThat(preflight.eligibleCount()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question_import_batch", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question", Integer.class)).isZero();
    Path persisted = importRoot.resolve(".uploads").resolve(preflight.token() + "--questions.jsonl");
    assertThat(persisted).isRegularFile();

    var restarted = new QuestionImportUploadService(importService, currentUser, importRoot);
    var completed = restarted.confirm(preflight.token(),
        new QuestionImportTokenConfirmRequest(UUID.randomUUID(), 0L));

    assertThat(completed.status()).isEqualTo("COMPLETED");
    assertThat(completed.batchId()).isPositive();
    assertThat(completed.importedCount()).isEqualTo(1);
    assertThat(persisted).isRegularFile();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question_import_batch", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question", Integer.class)).isEqualTo(1);
  }

  @Test
  void allowsTheImportingAdministratorToPublishWithAuditTrail() throws Exception {
    principal.set(user(1000L, List.of("ADMIN"), ALL_PERMISSIONS));
    Path releaseDirectory = Files.createDirectory(importRoot.resolve("release"));
    Path canonical = releaseDirectory.resolve("canonical-questions.jsonl");
    Files.writeString(canonical, eligibleCanonical("RELEASE-1", "a".repeat(64),
        "MIDDLE_DATABASE_SYSTEM_ENGINEER", "BASIC_KNOWLEDGE"), StandardCharsets.UTF_8);
    var imported = importService.confirm(new QuestionImportRequest(canonical.toString(), UUID.randomUUID()));

    var released = importService.publish(imported.batchId(), new QuestionImportRequest.Publish(UUID.randomUUID()));

    assertThat(released.publishedCount()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT status FROM q_question", String.class)).isEqualTo("PUBLISHED");
    assertThat(jdbc.queryForObject("SELECT status FROM q_question_version", String.class)).isEqualTo("PUBLISHED");
    assertThat(jdbc.queryForObject("SELECT reviewer_id FROM q_question_review", Long.class)).isEqualTo(1000L);
    assertThat(jdbc.queryForMap("""
        SELECT status, reviewed_by, published_at
        FROM q_question_import_batch WHERE id = ?
        """, imported.batchId()))
        .containsEntry("status", "PUBLISHED")
        .containsEntry("reviewed_by", 1000L)
        .containsKey("published_at");
    assertThat(jdbc.queryForObject("SELECT item_status FROM q_question_import_item", String.class))
        .isEqualTo("PUBLISHED");
    assertThat(jdbc.queryForList("SELECT event_type FROM q_question_audit_event", String.class))
        .contains("BATCH_IMPORT_PUBLISH");
  }

  @Test
  void rollsBackPublishedQuestionsWhenPaperPublicationFails() throws Exception {
    principal.set(user(1000L, List.of("ADMIN"), ALL_PERMISSIONS));
    Path releaseDirectory = Files.createDirectory(importRoot.resolve("atomic-release"));
    Path canonical = releaseDirectory.resolve("canonical-questions.jsonl");
    Files.writeString(canonical, eligibleCanonical("ATOMIC-RELEASE-1", "e".repeat(64),
        "MIDDLE_DATABASE_SYSTEM_ENGINEER", "BASIC_KNOWLEDGE"), StandardCharsets.UTF_8);
    PaperApplicationService failingPaperService = new PaperApplicationService(null, null, null) {
      @Override
      public com.aiexam.question.dto.PaperDetailDto publishImportedPaper(Long paperId) {
        throw new IllegalStateException("simulated paper publication failure");
      }
    };
    var atomicService = new QuestionImportApplicationService(
        repository, currentUser, new ObjectMapper().findAndRegisterModules(),
        importRoot.toString(), failingPaperService);
    var imported = atomicService.confirm(
        new QuestionImportRequest(canonical.toString(), UUID.randomUUID()));
    jdbc.update("UPDATE q_question_import_batch SET paper_id = 9001 WHERE id = ?",
        imported.batchId());

    assertThatThrownBy(() -> atomicService.publish(imported.batchId(),
        new QuestionImportRequest.Publish(UUID.randomUUID())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated paper publication failure");
    assertThat(jdbc.queryForObject("SELECT status FROM q_question", String.class))
        .isEqualTo("DRAFT");
    assertThat(jdbc.queryForObject("SELECT status FROM q_question_version", String.class))
        .isEqualTo("DRAFT");
    assertThat(jdbc.queryForObject("SELECT item_status FROM q_question_import_item", String.class))
        .isEqualTo("IMPORTED_DRAFT");
    assertThat(jdbc.queryForObject("SELECT status FROM q_question_import_batch", String.class))
        .isEqualTo("PENDING_REVIEW");
  }

  @Test
  void keepsAnImportedDraftPublishableAfterAdministratorEditsIt() throws Exception {
    principal.set(user(1000L, List.of("ADMIN"), ALL_PERMISSIONS));
    Path reviewDirectory = Files.createDirectory(importRoot.resolve("review-edit"));
    Path canonical = reviewDirectory.resolve("canonical-questions.jsonl");
    Files.writeString(canonical, eligibleCanonical("REVIEW-EDIT-1", "f".repeat(64),
        "MIDDLE_DATABASE_SYSTEM_ENGINEER", "BASIC_KNOWLEDGE"), StandardCharsets.UTF_8);
    var imported = importService.confirm(
        new QuestionImportRequest(canonical.toString(), UUID.randomUUID()));
    var original = imported.items().get(0);

    var revised = adminService.createRevision(original.questionId(),
        new QuestionVersionWriteRequest(UUID.randomUUID(), original.questionVersionId(),
            payload("管理员审核后修订"), "UNVERIFIED", "INTERNAL_ONLY"));
    var refreshed = importService.batchDetail(imported.batchId());

    assertThat(refreshed.items()).singleElement().satisfies(item -> {
      assertThat(item.questionVersionId()).isEqualTo(revised.questionVersionId());
      assertThat(item.contentHash()).isEqualTo(jdbc.queryForObject(
          "SELECT content_hash FROM q_question_version WHERE id = ?",
          String.class, revised.questionVersionId()));
    });
    var published = importService.publish(imported.batchId(),
        new QuestionImportRequest.Publish(UUID.randomUUID()));
    assertThat(published.publishedCount()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT title FROM q_question WHERE id = ?",
        String.class, original.questionId())).isEqualTo("管理员审核后修订");
  }

  @Test
  void multipartBoundaryRejectsUnsafeTypesTraversalTokensAndMissingRoot() {
    var body = new BytesMultipartFile("questions.exe", "x".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> uploadService.preflight("question-import/1.0", UUID.randomUUID(), body))
        .hasMessageContaining("Only .xlsx");
    assertThat(Files.exists(importRoot.resolve(".uploads"))).isFalse();

    assertThatThrownBy(() -> uploadService.confirm("../outside",
        new QuestionImportTokenConfirmRequest(UUID.randomUUID(), null)))
        .hasMessageContaining("canonical UUID");

    var failClosed = new QuestionImportUploadService(importService, currentUser, (Path) null);
    assertThatThrownBy(() -> failClosed.preflight("question-import/1.0", UUID.randomUUID(),
        new BytesMultipartFile("questions.jsonl", "{}".getBytes(StandardCharsets.UTF_8))))
        .hasMessageContaining("allowed root is not configured");
  }

  @Test
  void persistsStrictWorkflowRejectsSelfReviewAndAtomicallyReplacesPublishedVersion() {
    UUID createMutation = UUID.randomUUID();
    var created = adminService.create(new QuestionVersionWriteRequest(createMutation, null,
        payload("published-v1")));
    var replayed = adminService.create(new QuestionVersionWriteRequest(createMutation, null,
        payload("published-v1")));
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.questionId()).isEqualTo(created.questionId());

    var submitted = adminService.submitReview(created.questionId(), created.questionVersionId(),
        workflow(0));
    assertThat(submitted.status()).isEqualTo("IN_REVIEW");
    assertThatThrownBy(() -> adminService.review(created.questionId(), created.questionVersionId(),
        new QuestionReviewRequest(UUID.randomUUID(), 1, "APPROVED", "self")))
        .hasMessageContaining("submitter cannot review");

    principal.set(user(2000L));
    var approved = adminService.review(created.questionId(), created.questionVersionId(),
        new QuestionReviewRequest(UUID.randomUUID(), 1, "APPROVED", "reviewed"));
    assertThat(approved.rowVersion()).isEqualTo(2);

    principal.set(user(1000L));
    assertThatThrownBy(() -> adminService.publish(created.questionId(), created.questionVersionId(),
        workflow(2))).hasMessageContaining("submitter cannot publish");

    principal.set(user(3000L));
    var firstPublished = adminService.publish(created.questionId(), created.questionVersionId(), workflow(2));
    assertThat(firstPublished.status()).isEqualTo("PUBLISHED");
    assertThat(jdbc.queryForObject("SELECT title FROM q_question WHERE id=?", String.class,
        created.questionId())).isEqualTo("published-v1");

    principal.set(user(1000L));
    var revision = adminService.createRevision(created.questionId(),
        new QuestionVersionWriteRequest(UUID.randomUUID(), created.questionVersionId(), payload("published-v2")));
    assertThat(jdbc.queryForObject("SELECT title FROM q_question WHERE id=?", String.class,
        created.questionId())).isEqualTo("published-v1");
    var revisionSubmitted = adminService.submitReview(created.questionId(), revision.questionVersionId(), workflow(0));
    principal.set(user(2000L));
    var revisionApproved = adminService.review(created.questionId(), revision.questionVersionId(),
        new QuestionReviewRequest(UUID.randomUUID(), revisionSubmitted.rowVersion(), "APPROVED", "ok"));
    principal.set(user(3000L));
    var secondPublished = adminService.publish(created.questionId(), revision.questionVersionId(),
        workflow(revisionApproved.rowVersion()));

    assertThat(secondPublished.status()).isEqualTo("PUBLISHED");
    assertThat(jdbc.queryForObject("SELECT status FROM q_question_version WHERE id=?", String.class,
        created.questionVersionId())).isEqualTo("RETIRED");
    assertThat(jdbc.queryForObject("SELECT current_published_version_id FROM q_question WHERE id=?",
        Long.class, created.questionId())).isEqualTo(revision.questionVersionId());
    assertThat(jdbc.queryForObject("SELECT title FROM q_question WHERE id=?", String.class,
        created.questionId())).isEqualTo("published-v2");
  }

  @Test
  void replacesOnlyDraftWorkingVersionAndRejectsReplacingInReviewVersion() {
    var created = adminService.create(new QuestionVersionWriteRequest(UUID.randomUUID(), null,
        payload("draft-v1")));
    var revised = adminService.createRevision(created.questionId(),
        new QuestionVersionWriteRequest(UUID.randomUUID(), created.questionVersionId(), payload("draft-v2")));

    assertThat(jdbc.queryForObject("SELECT deleted FROM q_question_version WHERE id=?",
        Integer.class, created.questionVersionId())).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT current_working_version_id FROM q_question WHERE id=?",
        Long.class, created.questionId())).isEqualTo(revised.questionVersionId());

    adminService.submitReview(created.questionId(), revised.questionVersionId(), workflow(0));
    assertThatThrownBy(() -> adminService.createRevision(created.questionId(),
        new QuestionVersionWriteRequest(UUID.randomUUID(), revised.questionVersionId(), payload("draft-v3"))))
        .hasMessageContaining("IN_REVIEW");
  }

  @Test
  void persistsAllFiveQuestionTypesWithTheirRequiredContentGraphs() {
    List<QuestionCreateRequest> payloads = List.of(payload("single"), multiPayload(), judgePayload(),
        casePayload(), essayPayload());
    payloads.forEach(value -> adminService.create(
        new QuestionVersionWriteRequest(UUID.randomUUID(), null, value)));

    assertThat(jdbc.queryForList("SELECT question_type FROM q_question ORDER BY id", String.class))
        .containsExactly("SINGLE_CHOICE", "MULTI_CHOICE", "JUDGE", "CASE", "ESSAY");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question_version", Integer.class)).isEqualTo(5);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_scoring_point", Integer.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM q_question_stem WHERE stem_type IN ('CASE_MATERIAL','ESSAY_REQUIREMENT')
        """, Integer.class)).isEqualTo(2);
  }

  @Test
  void canonicalPreflightWritesNothingThenConfirmIsIdempotentAndRollbackRequiresPristineDraft()
      throws Exception {
    Path evidence = Files.createDirectory(importRoot.resolve("evidence"));
    Path canonical = evidence.resolve("canonical-questions.jsonl");
    Files.writeString(canonical, eligibleCanonical("EXT-1", "a".repeat(64),
        "MIDDLE_DATABASE_SYSTEM_ENGINEER", "BASIC_KNOWLEDGE"), StandardCharsets.UTF_8);

    var dryRun = importService.preflight(new QuestionImportRequest(evidence.toString(), null));
    assertThat(dryRun.eligibleCount()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question_import_batch", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question", Integer.class)).isZero();

    UUID confirmMutation = UUID.randomUUID();
    var confirmed = importService.confirm(new QuestionImportRequest(evidence.toString(), confirmMutation));
    var replayed = importService.confirm(new QuestionImportRequest(evidence.toString(), confirmMutation));
    assertThat(confirmed.importedCount()).isEqualTo(1);
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.batchId()).isEqualTo(confirmed.batchId());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForMap("""
        SELECT copyright_status, usage_restriction, source_file, source_sha256, source_locator,
               original_question_no, paper_kind
        FROM q_question_version WHERE id = ?
        """, confirmed.items().get(0).questionVersionId()))
        .containsEntry("copyright_status", "UNVERIFIED")
        .containsEntry("usage_restriction", "INTERNAL_ONLY")
        .containsEntry("source_file", evidence.resolve("original.pdf").toString())
        .containsEntry("paper_kind", "REAL_PAPER");
    assertThat(jdbc.queryForObject("SELECT score FROM q_sub_question", BigDecimal.class))
        .isEqualByComparingTo("1.00");
    assertThat(jdbc.queryForObject("SELECT answer_format FROM q_answer_key", String.class))
        .isEqualTo("OPTION");
    assertThat(jdbc.queryForObject("SELECT solution_type FROM q_solution", String.class))
        .isEqualTo("STANDARD");

    var rolledBack = importService.rollback(confirmed.batchId(),
        new QuestionImportRequest.Rollback(UUID.randomUUID()));
    assertThat(rolledBack.importedCount()).isZero();
    assertThat(jdbc.queryForObject("SELECT status FROM q_question_import_batch WHERE id=?",
        String.class, confirmed.batchId())).isEqualTo("ROLLED_BACK");
    assertThat(jdbc.queryForObject("SELECT deleted FROM q_question", Integer.class)).isEqualTo(1);
  }

  @Test
  void exactExistingQuestionIsMappedAcrossBatchesWithoutCreatingADuplicate() throws Exception {
    principal.set(user(1000L, List.of("ADMIN"), ALL_PERMISSIONS));
    Path first = Files.createDirectory(importRoot.resolve("exact-existing-first"));
    Path second = Files.createDirectory(importRoot.resolve("exact-existing-second"));
    String canonical = eligibleCanonical("EXACT-EXISTING", "9".repeat(64),
        "MIDDLE_DATABASE_SYSTEM_ENGINEER", "BASIC_KNOWLEDGE");
    Files.writeString(first.resolve("canonical-questions.jsonl"), canonical, StandardCharsets.UTF_8);
    Files.writeString(second.resolve("canonical-questions.jsonl"), canonical, StandardCharsets.UTF_8);

    var imported = importService.confirm(
        new QuestionImportRequest(first.toString(), UUID.randomUUID()));
    importService.publish(imported.batchId(),
        new QuestionImportRequest.Publish(UUID.randomUUID()));
    UUID mappingMutation = UUID.randomUUID();
    var mapped = importService.confirm(
        new QuestionImportRequest(second.toString(), mappingMutation));
    var replayed = importService.confirm(
        new QuestionImportRequest(second.toString(), mappingMutation));

    assertThat(imported.importedCount()).isEqualTo(1);
    assertThat(mapped.importedCount()).isZero();
    assertThat(mapped.items()).singleElement().satisfies(item -> {
      assertThat(item.errorCode()).isEqualTo("EXACT_EXISTING_QUESTION");
      assertThat(item.questionId()).isEqualTo(imported.items().get(0).questionId());
      assertThat(item.questionVersionId()).isEqualTo(imported.items().get(0).questionVersionId());
    });
    assertThat(replayed.replayed()).isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM q_question_import_item
        WHERE error_code = 'EXACT_EXISTING_QUESTION'
          AND item_status = 'SKIPPED_INELIGIBLE'
          AND import_eligible = 1
          AND question_id IS NOT NULL AND question_version_id IS NOT NULL
        """, Integer.class)).isEqualTo(1);
  }

  @Test
  void confirmationFailureRollsBackBatchItemsAndEarlierQuestions() throws Exception {
    Path evidence = Files.createDirectory(importRoot.resolve("failure"));
    Path canonical = evidence.resolve("canonical-questions.jsonl");
    Files.writeString(canonical,
        eligibleCanonical("EXT-1", "b".repeat(64), "MIDDLE_DATABASE_SYSTEM_ENGINEER", "BASIC_KNOWLEDGE")
            + System.lineSeparator()
            + eligibleCanonical("EXT-2", "c".repeat(64), "NOT_CONFIGURED", "NOT_CONFIGURED"),
        StandardCharsets.UTF_8);

    assertThatThrownBy(() -> importService.confirm(
        new QuestionImportRequest(evidence.toString(), UUID.randomUUID())))
        .hasMessageContaining("does not map to an enabled scope");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question_import_batch", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question_import_item", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question_mutation", Integer.class)).isZero();
  }

  @Test
  void xlsxPreflightWritesNothingAndConfirmPersistsTheValidatedNineSheetGraph() throws Exception {
    Path workbook = importRoot.resolve("question-import.xlsx");
    QuestionWorkbookImportReaderTest.writeValidWorkbook(workbook, ignored -> { });

    var preflight = importService.preflight(new QuestionImportRequest(workbook.toString(), null));
    assertThat(preflight.eligibleCount()).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question_import_batch", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question", Integer.class)).isZero();

    var confirmed = importService.confirm(new QuestionImportRequest(workbook.toString(), UUID.randomUUID()));
    assertThat(confirmed.importedCount()).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question_version", Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_scoring_point", Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_solution WHERE solution_type='STANDARD'",
        Integer.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question_version WHERE source_file=?",
        Integer.class, workbook.toRealPath().toString())).isEqualTo(2);
  }

  @Test
  void filtersByKeywordTypeSourceCopyrightAndExposesWorkflowColumns() {
    var alpha = adminService.create(new QuestionVersionWriteRequest(UUID.randomUUID(), null,
        withSource(payload("alpha single"), "ORIGINAL", null),
        "ORIGINAL_DECLARED", "INTERNAL_ONLY"));
    adminService.create(new QuestionVersionWriteRequest(UUID.randomUUID(), null,
        withSource(multiPayload(), "AUTHORIZED_IMPORT", "licensed:beta"),
        "AUTHORIZED", "INTERNAL_ONLY"));
    adminService.create(new QuestionVersionWriteRequest(UUID.randomUUID(), null,
        judgePayload()));

    var matches = adminService.list(1, 20, "draft", null, 1L, 11L, "ALPHA",
        "single_choice", "original", "original_declared");

    assertThat(matches.total()).isEqualTo(1);
    assertThat(matches.items()).singleElement().satisfies(item -> {
      assertThat(item.title()).isEqualTo("alpha single");
      assertThat(item.difficulty()).isEqualTo("MEDIUM");
      assertThat(item.rowVersion()).isZero();
      assertThat(item.currentWorking()).isTrue();
      assertThat(item.currentPublished()).isFalse();
      assertThat(item.copyrightStatus()).isEqualTo("ORIGINAL_DECLARED");
      assertThat(item.usageRestriction()).isEqualTo("INTERNAL_ONLY");
    });
    assertThat(adminService.list(1, 20, null, null, null, null, "beta", "MULTI_CHOICE",
        "AUTHORIZED_IMPORT", "AUTHORIZED").total()).isEqualTo(1);
    assertThat(adminService.list(1, 20, null, null, null, null, "missing", null,
        null, null).items()).isEmpty();
    assertThat(adminService.list(1, 20, null, alpha.questionId(), null, null, null,
        null, null, null).items()).singleElement()
        .extracting(QuestionVersionSummaryDto::questionId).isEqualTo(alpha.questionId());
  }

  @Test
  void keepsImportedMockPaperTitleInManagementListWhileDetailUsesTheRealStem() {
    var created = adminService.create(new QuestionVersionWriteRequest(UUID.randomUUID(), null,
        payload("MOCK_PAPER 75")));

    assertThat(adminService.list(1, 20, null, null, null, null, null, null, null, null).items())
        .singleElement()
        .satisfies(item -> assertThat(item.title()).isEqualTo("MOCK_PAPER 75"));
    assertThat(adminService.detail(created.questionId(), created.questionVersionId()).payload().title())
        .isEqualTo("MOCK_PAPER 75 stem");
  }

  @Test
  void rejectsWithoutReasonThenCopiesPublishedVersionAndReadsCompleteHistory() {
    var created = adminService.create(new QuestionVersionWriteRequest(UUID.randomUUID(), null,
        payload("history-source"), "ORIGINAL_DECLARED", "INTERNAL_ONLY"));
    var submitted = adminService.submitReview(created.questionId(), created.questionVersionId(),
        workflow(created.rowVersion()));
    principal.set(user(2000L));
    assertThatThrownBy(() -> adminService.review(created.questionId(), created.questionVersionId(),
        new QuestionReviewRequest(UUID.randomUUID(), submitted.rowVersion(), "REJECTED", "  ")))
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
          assertThat(exception.getMessage()).contains("rejection reason");
        });
    var rejected = adminService.review(created.questionId(), created.questionVersionId(),
        new QuestionReviewRequest(UUID.randomUUID(), submitted.rowVersion(), "REJECTED",
            "answer key needs correction"));
    principal.set(user(1000L));
    var resubmitted = adminService.submitReview(created.questionId(), created.questionVersionId(),
        workflow(rejected.rowVersion()));
    principal.set(user(2000L));
    var approved = adminService.review(created.questionId(), created.questionVersionId(),
        new QuestionReviewRequest(UUID.randomUUID(), resubmitted.rowVersion(), "APPROVED", "ok"));
    principal.set(user(3000L));
    var published = adminService.publish(created.questionId(), created.questionVersionId(),
        workflow(approved.rowVersion()));

    principal.set(user(1000L));
    UUID copyMutation = UUID.randomUUID();
    var copied = adminService.copyAsDraft(created.questionId(), published.questionVersionId(),
        new QuestionCopyRequest(copyMutation));
    var replayed = adminService.copyAsDraft(created.questionId(), published.questionVersionId(),
        new QuestionCopyRequest(copyMutation));

    assertThat(copied.status()).isEqualTo("DRAFT");
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.questionVersionId()).isEqualTo(copied.questionVersionId());
    assertThat(adminService.detail(created.questionId(), copied.questionVersionId()).payload().title())
        .isEqualTo("history-source");
    assertThat(adminService.versionHistory(created.questionId())).hasSize(2)
        .anySatisfy(version -> {
          assertThat(version.questionVersionId()).isEqualTo(created.questionVersionId());
          assertThat(version.currentPublished()).isTrue();
        })
        .anySatisfy(version -> {
          assertThat(version.questionVersionId()).isEqualTo(copied.questionVersionId());
          assertThat(version.currentWorking()).isTrue();
        });
    assertThat(adminService.reviewRecords(created.questionId(), created.questionVersionId()))
        .extracting(record -> record.decision()).containsExactly("APPROVED", "REJECTED");
    assertThat(adminService.auditEvents(created.questionId()))
        .extracting(event -> event.eventType())
        .contains("CREATE_QUESTION", "SUBMIT_REVIEW", "REJECTED_REVIEW", "APPROVED_REVIEW",
            "PUBLISH", "COPY_VERSION");
    assertThat(adminService.list(1, 20, null, null, null, null, null, null, null, null).items())
        .singleElement()
        .satisfies(item -> {
          assertThat(item.questionId()).isEqualTo(created.questionId());
          assertThat(item.questionVersionId()).isEqualTo(copied.questionVersionId());
          assertThat(item.currentWorking()).isTrue();
        });
  }

  @Test
  void enforcesStudentAdminReviewerPermissionMatrixAtApplicationBoundary() {
    principal.set(user(4100L, List.of("ROLE_STUDENT"), List.of()));
    assertForbidden(() -> adminService.list(1, 20, null, null, null, null, null, null, null, null));

    List<String> authorPermissions = List.of("question:read", "question:create",
        "question:update", "question:submit-review", "question:import");
    principal.set(user(4200L, List.of("ROLE_ADMIN", "QUESTION_AUTHOR"), authorPermissions));
    var created = adminService.create(new QuestionVersionWriteRequest(UUID.randomUUID(), null,
        payload("permission-matrix")));
    var submitted = adminService.submitReview(created.questionId(), created.questionVersionId(),
        workflow(created.rowVersion()));
    assertForbidden(() -> adminService.review(created.questionId(), created.questionVersionId(),
        new QuestionReviewRequest(UUID.randomUUID(), submitted.rowVersion(), "APPROVED", "no")));

    List<String> reviewerPermissions = List.of("question:read", "question:review", "question:publish");
    principal.set(user(4300L, List.of("QUESTION_REVIEWER", "QUESTION_PUBLISHER"),
        reviewerPermissions));
    assertForbidden(() -> adminService.create(new QuestionVersionWriteRequest(UUID.randomUUID(), null,
        payload("reviewer-cannot-create"))));
    assertForbidden(() -> adminService.submitReview(created.questionId(), created.questionVersionId(),
        workflow(submitted.rowVersion())));
    var approved = adminService.review(created.questionId(), created.questionVersionId(),
        new QuestionReviewRequest(UUID.randomUUID(), submitted.rowVersion(), "APPROVED", "ok"));
    assertThat(adminService.publish(created.questionId(), created.questionVersionId(),
        workflow(approved.rowVersion())).status()).isEqualTo("PUBLISHED");
  }

  @Test
  void dashboardUsesFullRepositoryAggregatesAndCurrentWorkflowPointers() {
    var single = adminService.create(new QuestionVersionWriteRequest(UUID.randomUUID(), null,
        payload("dashboard-single")));
    adminService.create(new QuestionVersionWriteRequest(UUID.randomUUID(), null, multiPayload()));
    adminService.create(new QuestionVersionWriteRequest(UUID.randomUUID(), null, casePayload()));
    adminService.submitReview(single.questionId(), single.questionVersionId(), workflow(0));

    var dashboard = adminService.dashboard();

    assertThat(dashboard.totalCount()).isEqualTo(3);
    assertThat(dashboard.draftCount()).isEqualTo(2);
    assertThat(dashboard.inReviewCount()).isEqualTo(1);
    assertThat(dashboard.publishedCount()).isZero();
    assertThat(dashboard.byQualification()).singleElement()
        .satisfies(item -> assertThat(item.count()).isEqualTo(3));
    assertThat(dashboard.byQuestionType()).extracting(item -> item.questionType())
        .containsExactlyInAnyOrder("SINGLE_CHOICE", "MULTI_CHOICE", "CASE");
    assertThat(dashboard.pendingReviews()).singleElement()
        .satisfies(item -> assertThat(item.questionId()).isEqualTo(single.questionId()));
  }

  @Test
  void listsImportBatchDetailDownloadsErrorsAndReflectsRollback() throws Exception {
    Path evidence = Files.createDirectory(importRoot.resolve("batch-read"));
    Path canonical = evidence.resolve("canonical-questions.jsonl");
    Files.writeString(canonical,
        eligibleCanonical("BATCH-OK", "e".repeat(64),
            "MIDDLE_DATABASE_SYSTEM_ENGINEER", "BASIC_KNOWLEDGE")
            + System.lineSeparator()
            + ineligibleCanonical("BATCH-BAD", "f".repeat(64)),
        StandardCharsets.UTF_8);

    var confirmed = importService.confirm(
        new QuestionImportRequest(evidence.toString(), UUID.randomUUID()));
    var batches = importService.listBatches(1, 20, "PENDING_REVIEW");
    var detail = importService.batchDetail(confirmed.batchId());

    assertThat(batches.total()).isEqualTo(1);
    assertThat(batches.items()).singleElement()
        .satisfies(batch -> assertThat(batch.importedCount()).isEqualTo(1));
    assertThat(detail.items()).hasSize(2)
        .anySatisfy(item -> {
          assertThat(item.externalKey()).isEqualTo("BATCH-BAD");
          assertThat(item.eligible()).isFalse();
          assertThat(item.errorCode()).isEqualTo("TEST_INVALID");
        });
    assertThat(new String(importService.errorCsv(confirmed.batchId()), StandardCharsets.UTF_8))
        .contains("lineNo,externalKey,errorCode,itemStatus", "BATCH-BAD", "TEST_INVALID")
        .doesNotContain("BATCH-OK");

    importService.rollback(confirmed.batchId(),
        new QuestionImportRequest.Rollback(UUID.randomUUID()));
    assertThat(importService.listBatches(1, 20, "ROLLED_BACK").items()).hasSize(1);
  }

  @Test
  void frozenJsonPreflightAndConfirmUseTheSameScoringMatrix() throws Exception {
    Path uploads = Files.createDirectories(importRoot.resolve(".uploads"));
    Path source = uploads.resolve(UUID.randomUUID() + "--scoring-matrix.json");
    Files.writeString(source, scoringMatrixJson(), StandardCharsets.UTF_8);

    var preflight = importService.preflight(new QuestionImportRequest(source.toString(), null));
    assertThat(preflight.eligibleCount()).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM q_question", Integer.class)).isZero();

    var confirmed = importService.confirm(
        new QuestionImportRequest(source.toString(), UUID.randomUUID()));

    assertThat(confirmed.importedCount()).isEqualTo(2);
    assertThat(jdbc.queryForList("SELECT rule_json FROM q_scoring_rule ORDER BY id", String.class))
        .anySatisfy(rule -> assertThat(rule).contains("SET_EQUAL"))
        .anySatisfy(rule -> assertThat(rule).contains("NUMERIC_TOLERANCE"))
        .anySatisfy(rule -> assertThat(rule).contains("KEYWORD"));
  }

  private QuestionWorkflowRequest workflow(long rowVersion) {
    return new QuestionWorkflowRequest(UUID.randomUUID(), rowVersion, null);
  }

  private PlatformPrincipal user(long id) {
    return new PlatformPrincipal(id, "user-" + id, "session-" + id, List.of(), ALL_PERMISSIONS, 1);
  }

  private PlatformPrincipal user(long id, List<String> roles, List<String> permissions) {
    return new PlatformPrincipal(id, "user-" + id, "session-" + id, roles, permissions, 1);
  }

  private void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
    assertThatThrownBy(operation).isInstanceOfSatisfying(BusinessException.class,
        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  private QuestionCreateRequest withSource(QuestionCreateRequest source, String sourceType,
      String sourceRef) {
    return new QuestionCreateRequest(source.qualificationId(), source.subjectId(),
        source.questionType(), source.title(), source.yearNo(), source.difficulty(), sourceType,
        sourceRef, source.stems(), source.subQuestions(), source.knowledgeBindings());
  }

  private QuestionCreateRequest payload(String title) {
    var stem = new QuestionCreateRequest.StemRequest("MAIN_STEM", title + " stem", null, 1,
        "S1", null);
    var options = List.of(
        new QuestionCreateRequest.OptionRequest("A", "answer A", 1),
        new QuestionCreateRequest.OptionRequest("B", "answer B", 2));
    var answer = new QuestionCreateRequest.AnswerKeyRequest("OPTION_KEYS", "A", "[\"A\"]");
    var sub = new QuestionCreateRequest.SubQuestionRequest(1, "SINGLE_CHOICE",
        title + " sub", new BigDecimal("1.00"), "OPTION_KEYS", "AUTO", 1, null,
        options, List.of(answer), List.of(),
        List.of(new QuestionCreateRequest.ScoringRuleRequest("AUTO", "{\"mode\":\"EXACT\"}", 1)),
        List.of(), List.of());
    return new QuestionCreateRequest(1L, 11L, "SINGLE_CHOICE", title, 2026, "MEDIUM",
        "MANUAL", "manual:test", List.of(stem), List.of(sub), List.of());
  }

  private QuestionCreateRequest multiPayload() {
    var stem = stem("multi", "MAIN_STEM");
    var sub = new QuestionCreateRequest.SubQuestionRequest(1, "MULTI_CHOICE", "multi sub",
        new BigDecimal("2.00"), "OPTION_KEYS", "AUTO", 1, null,
        List.of(option("A", 1), option("B", 2), option("C", 3)),
        List.of(new QuestionCreateRequest.AnswerKeyRequest("OPTION_KEYS", "A,B", "[\"A\",\"B\"]")),
        List.of(), List.of(rule("AUTO", "SET_EQUAL")), List.of(), List.of());
    return request("MULTI_CHOICE", "multi", stem, List.of(sub));
  }

  private QuestionCreateRequest judgePayload() {
    var sub = new QuestionCreateRequest.SubQuestionRequest(1, "JUDGE", "judge sub",
        new BigDecimal("1.00"), "BOOLEAN", "AUTO", 1, null, List.of(),
        List.of(new QuestionCreateRequest.AnswerKeyRequest("BOOLEAN", "true", "true")),
        List.of(), List.of(rule("AUTO", "EXACT")), List.of(), List.of());
    return request("JUDGE", "judge", stem("judge", "MAIN_STEM"), List.of(sub));
  }

  private QuestionCreateRequest casePayload() {
    var point1 = new QuestionCreateRequest.ScoringPointRequest("P1", "point one",
        new BigDecimal("5.00"), 1);
    var point2 = new QuestionCreateRequest.ScoringPointRequest("P1", "point two",
        new BigDecimal("5.00"), 1);
    var shortAnswer = new QuestionCreateRequest.SubQuestionRequest(1, "SHORT_ANSWER", "case sub 1",
        new BigDecimal("5.00"), "TEXT", "MANUAL", 1, null, List.of(),
        List.of(new QuestionCreateRequest.AnswerKeyRequest("TEXT", "reference", null)),
        List.of(), List.of(rule("MANUAL", "MANUAL")), List.of(point1), List.of());
    var calculation = new QuestionCreateRequest.SubQuestionRequest(2, "CALCULATION", "case sub 2",
        new BigDecimal("5.00"), "NUMBER", "MANUAL", 2, null, List.of(),
        List.of(new QuestionCreateRequest.AnswerKeyRequest("NUMBER", "10.00", "10.00")),
        List.of(), List.of(rule("MANUAL", "MANUAL")), List.of(point2), List.of());
    return request("CASE", "case", stem("case", "CASE_MATERIAL"),
        List.of(shortAnswer, calculation));
  }

  private QuestionCreateRequest essayPayload() {
    var point = new QuestionCreateRequest.ScoringPointRequest("P1", "essay structure",
        new BigDecimal("10.00"), 1);
    var sub = new QuestionCreateRequest.SubQuestionRequest(1, "ESSAY_WRITING", "essay requirement",
        new BigDecimal("10.00"), "TEXT", "MANUAL", 1, null, List.of(),
        List.of(new QuestionCreateRequest.AnswerKeyRequest("TEXT", "reference structure", null)),
        List.of(), List.of(rule("MANUAL", "MANUAL")), List.of(point), List.of());
    return request("ESSAY", "essay", stem("essay", "ESSAY_REQUIREMENT"), List.of(sub));
  }

  private QuestionCreateRequest request(String type, String title,
      QuestionCreateRequest.StemRequest stem,
      List<QuestionCreateRequest.SubQuestionRequest> subQuestions) {
    return new QuestionCreateRequest(1L, 11L, type, title, 2026, "MEDIUM", "MANUAL",
        "manual:test", List.of(stem), subQuestions, List.of());
  }

  private QuestionCreateRequest.StemRequest stem(String content, String type) {
    return new QuestionCreateRequest.StemRequest(type, content, null, 1, "S1", null);
  }

  private QuestionCreateRequest.OptionRequest option(String key, int order) {
    return new QuestionCreateRequest.OptionRequest(key, "option " + key, order);
  }

  private QuestionCreateRequest.ScoringRuleRequest rule(String type, String mode) {
    return new QuestionCreateRequest.ScoringRuleRequest(type,
        "{\"mode\":\"" + mode + "\"}", 1);
  }

  private String eligibleCanonical(String externalKey, String contentHash,
      String qualificationCode, String subjectCode) {
    String sourcePath = importRoot.resolve("evidence").resolve("original.pdf").toString()
        .replace("\\", "\\\\");
    return """
        {"schemaVersion":"question-import/1.0","recordType":"QUESTION_PREFLIGHT",
         "importStatus":"PREFLIGHT","rightsStatus":"UNVERIFIED",
         "qualificationCode":"%s","subjectCode":"%s",
         "quality":{"importEligible":true,"validationErrors":[]},
         "paper":{"paperKind":"REAL_PAPER","sourceFileName":"original.pdf","yearNo":2019},
         "sourceTrace":{"documentSha256":"%s","locator":"sha256:%s:page:2:question:1",
           "questionNo":1,"sourcePath":"%s"},
         "question":{"externalKey":"%s","contentHash":"%s","questionType":"SINGLE_CHOICE",
           "title":"REAL_PAPER 1","yearNo":2019,"difficulty":null,"knowledgeBindings":[],
           "source":{"sourceType":"SOURCE_DOCUMENT_IMPORT","sourceRef":"sha256:%s",
             "syllabusBasis":"UNVERIFIED_PREFLIGHT","copyrightStatus":"UNVERIFIED",
             "usageRestriction":"local development/internal acceptance only; publication prohibited"},
           "stems":[{"stemKey":"S1","stemType":"MAIN_STEM","content":"question stem",
             "resourceId":null,"sortOrder":1}],
           "subQuestions":[{"subQuestionNo":1,"subQuestionType":"SINGLE_CHOICE",
             "content":"question stem","score":null,"answerFormat":"OPTION_KEYS",
             "gradingMode":"AUTO","sortOrder":1,"knowledgeBindings":[],
             "options":[{"optionKey":"A","content":"one","sortOrder":1},
                        {"optionKey":"B","content":"two","sortOrder":2}],
             "answers":[{"answerKind":"OPTION_KEYS","optionKeys":["A"]}],
             "solutions":[{"solutionType":"REFERENCE_ANSWER","content":"why","sortOrder":1}],
             "scoringRules":[{"ruleType":"AUTO",
               "ruleJson":{"mode":"EXACT"},"sortOrder":1}],"scoringPoints":[]}]}}
        """.formatted(qualificationCode, subjectCode, "d".repeat(64), "d".repeat(64),
        sourcePath, externalKey, contentHash, "d".repeat(64)).replaceAll("\\R", "");
  }

  private String ineligibleCanonical(String externalKey, String contentHash) {
    return eligibleCanonical(externalKey, contentHash,
        "MIDDLE_DATABASE_SYSTEM_ENGINEER", "BASIC_KNOWLEDGE")
        .replace("\"importEligible\":true,\"validationErrors\":[]",
            "\"importEligible\":false,\"validationErrors\":[{\"code\":\"TEST_INVALID\"}]");
  }

  private String scoringMatrixJson() {
    return """
        {
          "schemaVersion":"question-import/1.0",
          "qualificationCode":"MIDDLE_DATABASE_SYSTEM_ENGINEER",
          "subjectCode":"BASIC_KNOWLEDGE",
          "questions":[
            {
              "externalKey":"JSON_MULTI_MATRIX",
              "questionType":"MULTI_CHOICE",
              "title":"multi matrix",
              "yearNo":2026,
              "difficulty":"MEDIUM",
              "source":{"sourceType":"ORIGINAL","sourceRef":null,"syllabusBasis":"test",
                "copyrightStatus":"ORIGINAL_DECLARED","usageRestriction":null},
              "stems":[{"stemKey":"S1","stemType":"MAIN_STEM","content":"choose many",
                "resourceId":null,"sortOrder":1}],
              "subQuestions":[{
                "subQuestionNo":1,"subQuestionType":"MULTI_CHOICE","content":"choose many",
                "score":4.00,"answerFormat":"OPTION_KEYS","gradingMode":"AUTO","sortOrder":1,
                "options":[{"optionKey":"A","content":"A","sortOrder":1},
                  {"optionKey":"B","content":"B","sortOrder":2},
                  {"optionKey":"C","content":"C","sortOrder":3}],
                "answers":[{"answerKind":"OPTION_KEYS","optionKeys":["A","B"],
                  "booleanValue":null,"textValue":null,"numberValue":null,"sortOrder":1}],
                "solutions":[{"solutionType":"ANALYSIS","content":"A and B","sortOrder":1}],
                "scoringRules":[{"ruleType":"AUTO","ruleJson":{"mode":"SET_EQUAL",
                  "tolerance":null,"caseSensitive":null},"sortOrder":1}],
                "scoringPoints":[],"knowledgeBindings":[]
              }],
              "knowledgeBindings":[]
            },
            {
              "externalKey":"JSON_CASE_MATRIX",
              "questionType":"CASE",
              "title":"case matrix",
              "yearNo":2026,
              "difficulty":"HARD",
              "source":{"sourceType":"ORIGINAL","sourceRef":null,"syllabusBasis":"test",
                "copyrightStatus":"ORIGINAL_DECLARED","usageRestriction":null},
              "stems":[{"stemKey":"S1","stemType":"CASE_MATERIAL","content":"case material",
                "resourceId":null,"sortOrder":1}],
              "subQuestions":[
                {
                  "subQuestionNo":1,"subQuestionType":"CALCULATION","content":"calculate",
                  "score":5.00,"answerFormat":"NUMBER","gradingMode":"AUTO","sortOrder":1,
                  "options":[],
                  "answers":[{"answerKind":"NUMBER","optionKeys":null,"booleanValue":null,
                    "textValue":null,"numberValue":10.00,"sortOrder":1}],
                  "solutions":[{"solutionType":"REFERENCE_ANSWER","content":"10","sortOrder":1}],
                  "scoringRules":[{"ruleType":"AUTO","ruleJson":{"mode":"NUMERIC_TOLERANCE",
                    "tolerance":0.01,"caseSensitive":null},"sortOrder":1}],
                  "scoringPoints":[{"pointCode":"P1","pointText":"correct result",
                    "score":5.00,"sortOrder":1}],"knowledgeBindings":[]
                },
                {
                  "subQuestionNo":2,"subQuestionType":"SHORT_ANSWER","content":"explain",
                  "score":5.00,"answerFormat":"TEXT","gradingMode":"AI_ASSISTED","sortOrder":2,
                  "options":[],
                  "answers":[{"answerKind":"TEXT","optionKeys":null,"booleanValue":null,
                    "textValue":"reference","numberValue":null,"sortOrder":1}],
                  "solutions":[{"solutionType":"REFERENCE_ANSWER","content":"reference","sortOrder":1}],
                  "scoringRules":[{"ruleType":"AI_ASSISTED","ruleJson":{"mode":"KEYWORD",
                    "tolerance":null,"caseSensitive":false},"sortOrder":1}],
                  "scoringPoints":[{"pointCode":"P1","pointText":"key point",
                    "score":5.00,"sortOrder":1}],"knowledgeBindings":[]
                }
              ],
              "knowledgeBindings":[]
            }
          ]
        }
        """;
  }

  private void seedScope() {
    jdbc.update("INSERT INTO exam_qualification VALUES (1,'MIDDLE_DATABASE_SYSTEM_ENGINEER','ENABLED',0)");
    jdbc.update("INSERT INTO exam_subject VALUES (11,1,'BASIC_KNOWLEDGE','ENABLED',0)");
  }

  private void createSchema() {
    jdbc.execute("CREATE TABLE exam_qualification (id BIGINT PRIMARY KEY, qualification_code VARCHAR(64), status VARCHAR(24), deleted TINYINT)");
    jdbc.execute("CREATE TABLE exam_subject (id BIGINT PRIMARY KEY, qualification_id BIGINT, subject_code VARCHAR(64), status VARCHAR(24), deleted TINYINT)");
    jdbc.execute("""
        CREATE TABLE q_question (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, qualification_id BIGINT NOT NULL,
          subject_id BIGINT NOT NULL, question_type VARCHAR(32) NOT NULL, title VARCHAR(180),
          year_no INT, difficulty VARCHAR(24), source_type VARCHAR(32), source_ref VARCHAR(500),
          status VARCHAR(24) NOT NULL, content_version INT NOT NULL,
          current_working_version_id BIGINT, current_published_version_id BIGINT,
          next_event_sequence BIGINT NOT NULL, import_external_key VARCHAR(160),
          import_content_hash CHAR(64), created_at TIMESTAMP(3), updated_at TIMESTAMP(3),
          created_by BIGINT, updated_by BIGINT, deleted TINYINT NOT NULL,
          UNIQUE(qualification_id,subject_id,import_external_key),
          UNIQUE(qualification_id,subject_id,import_content_hash))
        """);
    jdbc.execute("""
        CREATE TABLE q_question_version (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, question_id BIGINT NOT NULL,
          version_no INT NOT NULL, status VARCHAR(24) NOT NULL, external_key VARCHAR(160),
          title VARCHAR(180), year_no INT, difficulty VARCHAR(24), source_type VARCHAR(32),
          source_ref VARCHAR(500), source_file VARCHAR(500), source_sha256 CHAR(64),
          source_locator VARCHAR(1000), original_question_no INT, paper_kind VARCHAR(24),
          syllabus_basis VARCHAR(160), copyright_status VARCHAR(32), usage_restriction VARCHAR(255),
          content_hash CHAR(64), content_json CLOB, import_batch_id BIGINT, author_id BIGINT,
          submitted_by BIGINT, submitted_at TIMESTAMP(3), reviewed_by BIGINT,
          reviewed_at TIMESTAMP(3), review_decision VARCHAR(16), published_by BIGINT,
          published_at TIMESTAMP(3), retired_by BIGINT, retired_at TIMESTAMP(3),
          row_version BIGINT, created_at TIMESTAMP(3), updated_at TIMESTAMP(3), deleted TINYINT,
          UNIQUE(question_id,version_no))
        """);
    jdbc.execute("""
        CREATE TABLE q_question_stem (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, question_id BIGINT,
          question_version_id BIGINT, stem_key VARCHAR(64), stem_type VARCHAR(32), content CLOB,
          attachment_url VARCHAR(500), resource_id BIGINT, sort_order INT, created_at TIMESTAMP(3),
          updated_at TIMESTAMP(3), created_by BIGINT, updated_by BIGINT, deleted TINYINT)
        """);
    jdbc.execute("""
        CREATE TABLE q_sub_question (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, question_id BIGINT,
          question_version_id BIGINT, sub_question_no INT, sub_question_type VARCHAR(32), content CLOB,
          score DECIMAL(8,2), answer_format VARCHAR(32), grading_mode VARCHAR(32), sort_order INT,
          metadata_json CLOB, created_at TIMESTAMP(3), updated_at TIMESTAMP(3),
          created_by BIGINT, updated_by BIGINT, deleted TINYINT)
        """);
    jdbc.execute("""
        CREATE TABLE q_choice_option (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, sub_question_id BIGINT,
          option_key VARCHAR(16), option_content CLOB, sort_order INT, created_at TIMESTAMP(3),
          updated_at TIMESTAMP(3), created_by BIGINT, updated_by BIGINT, deleted TINYINT)
        """);
    jdbc.execute("""
        CREATE TABLE q_answer_key (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, sub_question_id BIGINT,
          answer_format VARCHAR(32), answer_value CLOB, answer_json CLOB, version_no INT,
          current_flag TINYINT, created_at TIMESTAMP(3), updated_at TIMESTAMP(3),
          created_by BIGINT, updated_by BIGINT, deleted TINYINT)
        """);
    jdbc.execute("""
        CREATE TABLE q_solution (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, question_id BIGINT,
          question_version_id BIGINT, sub_question_id BIGINT, solution_type VARCHAR(32), content CLOB,
          source_type VARCHAR(32), sort_order INT, version_no INT,
          created_at TIMESTAMP(3), updated_at TIMESTAMP(3),
          created_by BIGINT, updated_by BIGINT, deleted TINYINT)
        """);
    jdbc.execute("""
        CREATE TABLE q_scoring_rule (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, sub_question_id BIGINT,
          rule_type VARCHAR(32), rule_json CLOB, sort_order INT, version_no INT, current_flag TINYINT,
          created_at TIMESTAMP(3), updated_at TIMESTAMP(3), created_by BIGINT,
          updated_by BIGINT, deleted TINYINT)
        """);
    jdbc.execute("""
        CREATE TABLE q_scoring_point (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, question_version_id BIGINT,
          sub_question_id BIGINT, point_code VARCHAR(64), point_text CLOB, score DECIMAL(8,2),
          sort_order INT, created_at TIMESTAMP(3), created_by BIGINT, deleted TINYINT)
        """);
    jdbc.execute("""
        CREATE TABLE q_question_knowledge_binding (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, question_id BIGINT,
          question_version_id BIGINT, sub_question_id BIGINT, target_type VARCHAR(32), target_id BIGINT,
          knowledge_type VARCHAR(32), knowledge_id BIGINT, knowledge_code VARCHAR(96),
          weight DECIMAL(6,3), sort_order INT,
          created_at TIMESTAMP(3), updated_at TIMESTAMP(3), created_by BIGINT,
          updated_by BIGINT, deleted TINYINT)
        """);
    jdbc.execute("""
        CREATE TABLE q_question_review (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, question_id BIGINT,
          question_version_id BIGINT, submitted_by BIGINT, reviewer_id BIGINT,
          decision VARCHAR(16), reason VARCHAR(500), created_at TIMESTAMP(3))
        """);
    jdbc.execute("""
        CREATE TABLE q_question_audit_event (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, question_id BIGINT,
          question_version_id BIGINT, event_sequence BIGINT, event_type VARCHAR(48),
          actor_user_id BIGINT, client_mutation_id CHAR(36), event_payload_json CLOB,
          created_at TIMESTAMP(3), UNIQUE(question_id,event_sequence))
        """);
    jdbc.execute("""
        CREATE TABLE q_question_mutation (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, actor_user_id BIGINT,
          operation_code VARCHAR(48), target_scope_id VARCHAR(160), client_mutation_id CHAR(36),
          request_hash CHAR(64), status VARCHAR(16), response_json CLOB,
          created_at TIMESTAMP(3), completed_at TIMESTAMP(3),
          UNIQUE(actor_user_id,operation_code,target_scope_id,client_mutation_id))
        """);
    jdbc.execute("""
        CREATE TABLE q_question_import_batch (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, actor_user_id BIGINT,
          client_mutation_id CHAR(36), paper_id BIGINT, input_path VARCHAR(1000),
          input_sha256 CHAR(64), source_file_name VARCHAR(255), source_file_sha256 CHAR(64),
          schema_version VARCHAR(48), rights_status VARCHAR(32), usage_restriction VARCHAR(255),
          status VARCHAR(24), total_count INT, eligible_count INT, imported_count INT,
          row_version BIGINT, reviewed_by BIGINT, reviewed_at TIMESTAMP(3),
          review_note VARCHAR(1000), created_at TIMESTAMP(3), completed_at TIMESTAMP(3),
          published_at TIMESTAMP(3), rolled_back_at TIMESTAMP(3),
          UNIQUE(actor_user_id,client_mutation_id), UNIQUE(paper_id))
        """);
    jdbc.execute("""
        CREATE TABLE q_question_import_item (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, import_batch_id BIGINT,
          line_no INT, external_key VARCHAR(160), content_hash CHAR(64), import_eligible TINYINT,
          item_status VARCHAR(24), question_id BIGINT, question_version_id BIGINT,
          canonical_json CLOB, error_code VARCHAR(96), created_at TIMESTAMP(3),
          UNIQUE(import_batch_id,line_no), UNIQUE(import_batch_id,external_key))
        """);
  }

  private record BytesMultipartFile(String originalFilename, byte[] content) implements MultipartFile {
    @Override
    public String getName() {
      return "file";
    }

    @Override
    public String getOriginalFilename() {
      return originalFilename;
    }

    @Override
    public String getContentType() {
      return "application/octet-stream";
    }

    @Override
    public boolean isEmpty() {
      return content.length == 0;
    }

    @Override
    public long getSize() {
      return content.length;
    }

    @Override
    public byte[] getBytes() {
      return content.clone();
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(content);
    }

    @Override
    public void transferTo(File destination) throws IOException {
      Files.copy(getInputStream(), destination.toPath());
    }
  }
}
