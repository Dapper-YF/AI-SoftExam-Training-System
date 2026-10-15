package com.aiexam.question.practice.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.common.error.BusinessException;
import com.aiexam.question.practice.attempt.application.PracticeAttemptApplicationService;
import com.aiexam.question.practice.attempt.domain.PracticeAnswerPayload;
import com.aiexam.question.practice.attempt.dto.CreatePracticeAttemptRequest;
import com.aiexam.question.practice.attempt.dto.SavePracticeAnswerRequest;
import com.aiexam.question.practice.attempt.dto.SettlePracticeQuestionRequest;
import com.aiexam.question.practice.attempt.dto.SubmitPracticeAttemptRequest;
import com.aiexam.question.practice.attempt.infra.JdbcPracticeAttemptRepository;
import com.aiexam.question.practice.read.application.PracticeReadApplicationService;
import com.aiexam.question.practice.read.infra.JdbcPracticeReadRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcPracticeAttemptRepositoryTest {
  private JdbcTemplate jdbcTemplate;
  private JdbcPracticeAttemptRepository repository;
  private PracticeAttemptApplicationService service;
  private PracticeReadApplicationService readService;
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  @BeforeEach
  void setUp() {
    String databaseName = "practice_attempt_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    jdbcTemplate = new JdbcTemplate(dataSource);
    createSchema();
    seedQuestion();
    repository = new JdbcPracticeAttemptRepository(jdbcTemplate);
    service = new PracticeAttemptApplicationService(repository,
        PracticeAttemptFixtures::student,
        PracticeAttemptFixtures.permissiveFoundation(),
        Clock.fixed(Instant.parse("2026-07-16T03:00:00Z"), ZoneOffset.UTC));
    readService = new PracticeReadApplicationService(
        new JdbcPracticeReadRepository(jdbcTemplate), PracticeAttemptFixtures::student, service);
  }

  @Test
  void returnsAValidEmptyModuleHomeSnapshot() {
    var snapshot = readService.moduleHome();

    assertThat(snapshot.currentQualificationId()).isNull();
    assertThat(snapshot.recentTasks()).isEmpty();
    assertThat(snapshot.recentPractice()).isEmpty();
    assertThat(snapshot.recentScores()).isEmpty();
    assertThat(snapshot.mistakeCount()).isZero();
    assertThat(snapshot.favoriteCount()).isZero();
    assertThat(snapshot.stats()).extracting(stat -> stat.value()).containsExactly("0", "0", "0");
    assertThat(snapshot.availableEntries()).isNotEmpty();
  }

  @Test
  void exposesOnlyPublishedQuestionsInThePracticeCatalog() {
    var catalog = readService.catalog(1L);

    assertThat(catalog.qualificationId()).isEqualTo(1L);
    assertThat(catalog.questionCount()).isEqualTo(1);
    assertThat(catalog.paperCount()).isEqualTo(1);
    assertThat(catalog.paperYears()).containsExactly(2026);
    assertThat(catalog.subjects()).singleElement().satisfies(subject -> {
      assertThat(subject.subjectName()).isEqualTo("综合知识");
      assertThat(subject.questionCount()).isEqualTo(1);
      assertThat(subject.questionTypes()).singleElement().satisfies(type -> {
        assertThat(type.questionType()).isEqualTo("SINGLE_CHOICE");
        assertThat(type.count()).isEqualTo(1);
      });
    });
  }

  @Test
  void dailyPracticeUsesPublishedQuestionsAndDoesNotRepeatThemOnTheSameDay() {
    var first = service.create(new CreatePracticeAttemptRequest(
        UUID.randomUUID(), 1L, 11L, "PRACTICE", "PER_QUESTION", 1,
        new CreatePracticeAttemptRequest.Source("DAILY", null, null, null, null)));

    assertThat(first.questions()).extracting(question -> question.questionId()).containsExactly(1001L);
    assertThatThrownBy(() -> service.create(new CreatePracticeAttemptRequest(
        UUID.randomUUID(), 1L, 11L, "PRACTICE", "PER_QUESTION", 1,
        new CreatePracticeAttemptRequest.Source("DAILY", null, null, null, null))))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("no published question available");
  }

  @Test
  void startsOnlyACompletePublishedRealPaperForPastExamPractice() {
    var attempt = service.create(new CreatePracticeAttemptRequest(
        UUID.randomUUID(), 1L, 11L, "PAST_EXAM", "WHOLE_PAPER", 100,
        new CreatePracticeAttemptRequest.Source("PAPER", null, null, null, 601L)));

    assertThat(attempt.questions()).hasSize(1);
    assertThat(attempt.requestedCount()).isEqualTo(1);
    assertThat(attempt.partial()).isFalse();
  }

  @Test
  void rejectsMockOrIncompletePaperFromPastExamPractice() {
    jdbcTemplate.update("UPDATE q_paper SET paper_type = 'MOCK' WHERE id = 601");
    var mockRequest = new CreatePracticeAttemptRequest(
        UUID.randomUUID(), 1L, 11L, "PAST_EXAM", "WHOLE_PAPER", 100,
        new CreatePracticeAttemptRequest.Source("PAPER", null, null, null, 601L));
    assertThatThrownBy(() -> service.create(mockRequest))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("完整真题卷");

    jdbcTemplate.update("UPDATE q_paper SET paper_type = 'REAL' WHERE id = 601");
    jdbcTemplate.update("UPDATE q_paper_question SET question_snapshot = NULL WHERE paper_id = 601");
    var incompleteRequest = new CreatePracticeAttemptRequest(
        UUID.randomUUID(), 1L, 11L, "PAST_EXAM", "WHOLE_PAPER", 100,
        new CreatePracticeAttemptRequest.Source("PAPER", null, null, null, 601L));
    assertThatThrownBy(() -> service.create(incompleteRequest))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("完整真题卷");
  }

  @Test
  void filtersAttemptHistoryAndKeepsItUserScopedWithoutLeakingScores() {
    var created = service.create(new CreatePracticeAttemptRequest(
        UUID.randomUUID(), 1L, 11L, "PRACTICE", "WHOLE_PAPER", 1,
        new CreatePracticeAttemptRequest.Source("KNOWLEDGE", "CHAPTER", List.of(101L), null, null)));
    insertForeignAttempt(8001L, 3L, 1L, 11L, "IN_PROGRESS");

    assertThat(service.history(1L, 11L, "in_progress", 1, 20).items())
        .singleElement()
        .satisfies(item -> {
          assertThat(item.attemptId()).isEqualTo(created.attemptId());
          assertThat(item.totalScore()).isNull();
          assertThat(item.maxScore()).isNull();
        });
    assertThat(service.history(2L, null, null, 1, 20).items()).isEmpty();
    assertThat(service.history(null, 12L, null, 1, 20).items()).isEmpty();
    assertThat(service.history(null, null, "SUBMITTED", 1, 20).items()).isEmpty();
    assertThat(service.history(null, null, null, 1, 20).total()).isEqualTo(1);
  }

  @Test
  void listsOwnedMistakesAndReadsOnlyTheSettledAttemptSnapshot() {
    var created = service.create(new CreatePracticeAttemptRequest(
        UUID.randomUUID(), 1L, 11L, "PRACTICE", "PER_QUESTION", 1,
        new CreatePracticeAttemptRequest.Source("KNOWLEDGE", "CHAPTER", List.of(101L), null, null)));
    service.saveAnswer(created.attemptId(), 9001L, new SavePracticeAnswerRequest(
        UUID.randomUUID(), 0, new PracticeAnswerPayload("B", null, null, null), false, 12));
    service.settleQuestion(created.attemptId(), 1001L, new SettlePracticeQuestionRequest(UUID.randomUUID(), 0));
    insertForeignAttempt(8002L, 3L, 1L, 11L, "SUBMITTED");
    jdbcTemplate.update("""
        INSERT INTO practice_mistake (
          user_id, question_id, qualification_id, subject_id, first_attempt_id, last_attempt_id,
          mistake_count, last_wrong_at, status, created_at, updated_at, created_by, updated_by, deleted
        ) VALUES (3, 1001, 1, 11, 8002, 8002, 9, CURRENT_TIMESTAMP, 'ACTIVE',
                  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 3, 3, 0)
        """);

    assertThat(readService.mistakes(1L, 11L, "active", 1, 20).items())
        .singleElement()
        .satisfies(item -> {
          assertThat(item.questionTitle()).isEqualTo("published title");
          assertThat(item.lastAttemptId()).isEqualTo(created.attemptId());
          assertThat(item.mistakeCount()).isEqualTo(1);
        });
    assertThat(readService.mistakes(2L, null, null, 1, 20).items()).isEmpty();
    assertThat(readService.mistakes(null, null, "ARCHIVED", 1, 20).items()).isEmpty();

    var detail = readService.mistake(1001L);
    assertThat(detail.question().title()).isEqualTo("published title");
    assertThat(detail.question().settlementStatus().name()).isEqualTo("SETTLED");
    assertThat(detail.question().settlementVersion()).isEqualTo(1);
    assertThat(detail.question().stems()).extracting(stem -> stem.content())
        .containsExactly("published stem");
    assertThat(detail.question().subQuestions()).singleElement().satisfies(subQuestion -> {
      assertThat(subQuestion.content()).isEqualTo("published sub-question");
      assertThat(subQuestion.answer().answerPayload().optionKey()).isEqualTo("B");
      assertThat(subQuestion.grading().standardAnswer().optionKey()).isEqualTo("A");
      assertThat(subQuestion.solution()).isEqualTo("published solution");
    });

    jdbcTemplate.update("""
        INSERT INTO practice_favorite (
          id, user_id, question_id, qualification_id, subject_id, created_at, updated_at, deleted
        ) VALUES (71, 2, 1001, 1, 11, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
                 (72, 3, 1001, 1, 11, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
    var home = readService.moduleHome();
    assertThat(home.currentQualificationId()).isEqualTo(1L);
    assertThat(home.recentTasks()).singleElement().satisfies(task -> {
      assertThat(task.routePath()).isEqualTo("/app/practice/attempts/" + created.attemptId());
      assertThat(task.score()).isNull();
    });
    assertThat(home.mistakeCount()).isEqualTo(1);
    assertThat(home.favoriteCount()).isEqualTo(1);
    assertThat(home.recentScores()).isEmpty();
  }

  @Test
  void persistsSnapshotIdempotencySettlementMistakeAndHistory() {
    UUID createMutation = UUID.randomUUID();
    CreatePracticeAttemptRequest createRequest = new CreatePracticeAttemptRequest(
        createMutation, 1L, 11L, "PRACTICE", "PER_QUESTION", 1,
        new CreatePracticeAttemptRequest.Source("KNOWLEDGE", "CHAPTER", List.of(101L), null, null));

    var created = service.create(createRequest);
    var replayedCreate = service.create(createRequest);

    assertThat(replayedCreate.attemptId()).isEqualTo(created.attemptId());
    assertThat(created.questions()).singleElement().satisfies(question -> {
      assertThat(question.title()).isEqualTo("published title");
      assertThat(question.stems()).extracting(stem -> stem.content())
          .containsExactly("published stem");
      assertThat(question.subQuestions()).extracting(sub -> sub.content())
          .containsExactly("published sub-question");
      assertThat(question.knowledgeBindings()).extracting(binding -> binding.knowledgeId())
          .containsExactly(101L);
    });
    assertThat(created.totalScore()).isNull();
    assertThat(created.maxScore()).isNull();
    assertThat(created.correctCount()).isNull();
    assertThat(created.wrongCount()).isNull();
    assertThat(created.pendingReviewCount()).isNull();
    assertThat(created.questions().get(0).earnedScore()).isNull();
    assertThat(created.questions().get(0).subQuestions().get(0).grading()).isNull();
    assertThat(created.questions().get(0).subQuestions().get(0).solution()).isNull();
    assertThat(jdbcTemplate.queryForObject("""
        SELECT question_content_version FROM practice_attempt_question WHERE attempt_id = ?
        """, Integer.class, created.attemptId())).isEqualTo(3);

    UUID saveMutation = UUID.randomUUID();
    SavePracticeAnswerRequest wrongAnswer = new SavePracticeAnswerRequest(saveMutation, 0,
        new PracticeAnswerPayload("B", null, null, null), false, 9);
    var saved = service.saveAnswer(created.attemptId(), 9001L, wrongAnswer);
    var replayedSave = service.saveAnswer(created.attemptId(), 9001L, wrongAnswer);
    assertThat(saved.answerVersion()).isEqualTo(1);
    assertThat(replayedSave.idempotentReplay()).isTrue();
    assertThat(service.get(created.attemptId()).questions().get(0).subQuestions().get(0)
        .answer().gradingStatus()).isNull();

    UUID settleMutation = UUID.randomUUID();
    SettlePracticeQuestionRequest settleRequest = new SettlePracticeQuestionRequest(settleMutation, 0);
    var settled = service.settleQuestion(created.attemptId(), 1001L, settleRequest);
    var replayedSettle = service.settleQuestion(created.attemptId(), 1001L, settleRequest);

    assertThat(settled.totalScore()).isNull();
    assertThat(settled.maxScore()).isNull();
    assertThat(settled.correctCount()).isNull();
    assertThat(settled.wrongCount()).isNull();
    assertThat(settled.pendingReviewCount()).isNull();
    assertThat(replayedSettle.idempotentReplay()).isTrue();
    assertThat(service.get(created.attemptId()).questions().get(0).subQuestions().get(0)
        .answer().gradingStatus()).hasToString("WRONG");
    assertThat(service.explanation(created.attemptId(), 1001L).subQuestions()).hasSize(1);
    assertThat(jdbcTemplate.queryForObject("""
        SELECT mistake_count FROM practice_mistake WHERE user_id = 2 AND question_id = 1001
        """, Integer.class)).isEqualTo(1);

    var submitted = service.submit(created.attemptId(),
        new SubmitPracticeAttemptRequest(UUID.randomUUID(), 1));

    assertThat(submitted.wrongCount()).isEqualTo(1);
    assertThat(submitted.totalScore()).isEqualByComparingTo("0.00");
    assertThat(submitted.maxScore()).isEqualByComparingTo("2.00");
    assertThat(jdbcTemplate.queryForObject("""
        SELECT mistake_count FROM practice_mistake WHERE user_id = 2 AND question_id = 1001
        """, Integer.class)).isEqualTo(1);
    assertThat(service.history(1, 20).items()).singleElement().satisfies(history -> {
      assertThat(history.totalScore()).isEqualByComparingTo("0.00");
      assertThat(history.maxScore()).isEqualByComparingTo("2.00");
    });
  }

  @Test
  void preservesFullQuestionSnapshotFieldsAfterCreateAndReload() {
    var created = service.create(new CreatePracticeAttemptRequest(
        UUID.randomUUID(), 1L, 11L, "PRACTICE", "WHOLE_PAPER", 1,
        new CreatePracticeAttemptRequest.Source("KNOWLEDGE", "CHAPTER", List.of(101L), null, null)));

    var reloaded = repository.findAttempt(2L, created.attemptId()).orElseThrow();

    assertThat(reloaded.questions()).singleElement().satisfies(question -> {
      assertThat(question.questionVersionId()).isEqualTo(7001L);
      assertThat(question.stems()).singleElement().satisfies(stem -> {
        assertThat(stem.stemType()).isEqualTo("MAIN_STEM");
        assertThat(stem.content()).isEqualTo("published stem");
      });
      assertThat(question.subQuestions()).singleElement().satisfies(subQuestion -> {
        assertThat(subQuestion.answerFormat()).isEqualTo("OPTION");
        assertThat(subQuestion.metadata()).containsEntry("source", "published");
        assertThat(subQuestion.scoringPoints()).singleElement().satisfies(point -> {
          assertThat(point.pointCode()).isEqualTo("P1");
          assertThat(point.pointText()).isEqualTo("published scoring point");
          assertThat(point.score()).isEqualByComparingTo("2.00");
        });
      });
    });
  }

  @Test
  void loadsAttempt8ShapedLegacySnapshotWithMissingSingleChoiceAnswerFormat() throws Exception {
    long attemptId = createPracticeAttempt();
    rewriteQuestionSnapshot(attemptId, snapshot -> firstSubQuestion(snapshot).remove("answerFormat"));

    var detail = service.get(attemptId);

    assertThat(detail.questions()).singleElement().satisfies(question -> {
      assertThat(question.questionVersionId()).isEqualTo(7001L);
      assertThat(question.subQuestions()).singleElement().satisfies(subQuestion -> {
        assertThat(subQuestion.subQuestionType()).isEqualTo("SINGLE_CHOICE");
        assertThat(subQuestion.answerFormat()).isEqualTo("OPTION");
        assertThat(subQuestion.grading()).isNull();
        assertThat(subQuestion.solution()).isNull();
      });
    });
  }

  @Test
  void loadsLegacySnapshotWithMissingQuestionVersionIdFromQuestionContentVersion() throws Exception {
    long attemptId = createPracticeAttempt();
    rewriteQuestionSnapshot(attemptId, snapshot -> snapshot.remove("questionVersionId"));

    var detail = service.get(attemptId);

    assertThat(detail.questions()).singleElement().satisfies(question -> {
      assertThat(question.questionVersionId()).isEqualTo(7001L);
      assertThat(question.subQuestions()).singleElement().satisfies(subQuestion -> {
        assertThat(subQuestion.answerFormat()).isEqualTo("OPTION");
        assertThat(subQuestion.metadata()).containsEntry("source", "published");
        assertThat(subQuestion.grading()).isNull();
        assertThat(subQuestion.solution()).isNull();
        assertThat(subQuestion.scoringPoints()).isNull();
      });
    });
  }

  @Test
  void rejectsPresentNullOrInvalidQuestionVersionId() throws Exception {
    long nullAttemptId = createPracticeAttempt();
    rewriteQuestionSnapshot(nullAttemptId, snapshot ->
        snapshot.set("questionVersionId", objectMapper.nullNode()));
    long stringAttemptId = createPracticeAttempt();
    rewriteQuestionSnapshot(stringAttemptId, snapshot -> snapshot.put("questionVersionId", "SENSITIVE_VALUE"));
    long floatAttemptId = createPracticeAttempt();
    rewriteQuestionSnapshot(floatAttemptId, snapshot -> snapshot.put("questionVersionId", 7001.5));
    long zeroAttemptId = createPracticeAttempt();
    rewriteQuestionSnapshot(zeroAttemptId, snapshot -> snapshot.put("questionVersionId", 0));
    long negativeAttemptId = createPracticeAttempt();
    rewriteQuestionSnapshot(negativeAttemptId, snapshot -> snapshot.put("questionVersionId", -1));

    assertInvalidQuestionVersionSnapshot(() -> service.get(nullAttemptId));
    assertInvalidQuestionVersionSnapshot(() -> service.get(stringAttemptId));
    assertInvalidQuestionVersionSnapshot(() -> service.get(floatAttemptId));
    assertInvalidQuestionVersionSnapshot(() -> service.get(zeroAttemptId));
    assertInvalidQuestionVersionSnapshot(() -> service.get(negativeAttemptId));
  }

  @Test
  void rejectsMissingQuestionVersionIdWhenNoActiveVersionCanBeResolved() throws Exception {
    long noRowAttemptId = createPracticeAttempt();
    rewriteQuestionSnapshot(noRowAttemptId, snapshot -> snapshot.remove("questionVersionId"));
    jdbcTemplate.update("DELETE FROM q_question_version WHERE id = 7001");

    assertInvalidQuestionVersionSnapshot(() -> service.get(noRowAttemptId));
  }

  @Test
  void rejectsMissingQuestionVersionIdWhenOnlyDeletedVersionCanBeResolved() throws Exception {
    long attemptId = createPracticeAttempt();
    rewriteQuestionSnapshot(attemptId, snapshot -> snapshot.remove("questionVersionId"));
    jdbcTemplate.update("UPDATE q_question_version SET deleted = 1 WHERE id = 7001");

    assertInvalidQuestionVersionSnapshot(() -> service.get(attemptId));
  }

  @Test
  void rejectsMissingQuestionVersionIdWhenMultipleActiveVersionsMatch() throws Exception {
    long attemptId = createPracticeAttempt();
    rewriteQuestionSnapshot(attemptId, snapshot -> snapshot.remove("questionVersionId"));
    jdbcTemplate.update("""
        INSERT INTO q_question_version (id, question_id, version_no, status, title, year_no, deleted)
        VALUES (7999, 1001, 3, 'PUBLISHED', 'duplicate drift row', 2026, 0)
        """);

    assertInvalidQuestionVersionSnapshot(() -> service.get(attemptId));
  }

  @Test
  void preservesFormalPositiveQuestionVersionIdWithoutFallbackLookup() throws Exception {
    long attemptId = createPracticeAttempt();
    rewriteQuestionSnapshot(attemptId, snapshot -> snapshot.put("questionVersionId", 8888));
    jdbcTemplate.update("DELETE FROM q_question_version WHERE version_no = 3");

    assertThat(service.get(attemptId).questions())
        .singleElement()
        .extracting(question -> question.questionVersionId())
        .isEqualTo(8888L);
  }

  @Test
  void mapsOnlyMissingLegacyChoiceSnapshotAnswerFormatToOption() throws Exception {
    long multiAttemptId = createPracticeAttempt();
    rewriteQuestionSnapshot(multiAttemptId, snapshot -> {
      snapshot.put("questionType", "MULTI_CHOICE");
      ObjectNode subQuestion = firstSubQuestion(snapshot);
      subQuestion.put("subQuestionType", "MULTI_CHOICE");
      subQuestion.remove("answerFormat");
    });

    assertThat(service.get(multiAttemptId).questions().get(0).subQuestions().get(0).answerFormat())
        .isEqualTo("OPTION");
  }

  @Test
  void keepsFormalCaseAndEssaySnapshotAnswerFormats() throws Exception {
    long caseAttemptId = createPracticeAttempt();
    rewriteQuestionSnapshot(caseAttemptId, snapshot -> {
      snapshot.put("questionType", "CASE");
      ObjectNode subQuestion = firstSubQuestion(snapshot);
      subQuestion.put("subQuestionType", "SHORT_ANSWER");
      subQuestion.put("answerFormat", "TEXT");
    });
    long essayAttemptId = createPracticeAttempt();
    rewriteQuestionSnapshot(essayAttemptId, snapshot -> {
      snapshot.put("questionType", "ESSAY");
      ObjectNode subQuestion = firstSubQuestion(snapshot);
      subQuestion.put("subQuestionType", "ESSAY_WRITING");
      subQuestion.put("answerFormat", "TEXT");
    });

    assertThat(service.get(caseAttemptId).questions().get(0).subQuestions().get(0).answerFormat())
        .isEqualTo("TEXT");
    assertThat(service.get(essayAttemptId).questions().get(0).subQuestions().get(0).answerFormat())
        .isEqualTo("TEXT");
  }

  @Test
  void rejectsPresentNullUnknownStringOrUnknownMissingSnapshotAnswerFormat() throws Exception {
    long nullFormatAttemptId = createPracticeAttempt();
    rewriteQuestionSnapshot(nullFormatAttemptId, snapshot ->
        firstSubQuestion(snapshot).set("answerFormat", objectMapper.nullNode()));
    long unknownFormatAttemptId = createPracticeAttempt();
    rewriteQuestionSnapshot(unknownFormatAttemptId, snapshot -> firstSubQuestion(snapshot).put("answerFormat",
        "OPTION_KEYS"));
    long missingFormatAttemptId = createPracticeAttempt();
    rewriteQuestionSnapshot(missingFormatAttemptId, snapshot -> {
      ObjectNode subQuestion = firstSubQuestion(snapshot);
      subQuestion.put("subQuestionType", "LEGACY_UNKNOWN");
      subQuestion.remove("answerFormat");
    });

    assertInvalidSnapshot(() -> service.get(nullFormatAttemptId));
    assertInvalidSnapshot(() -> service.get(unknownFormatAttemptId));
    assertInvalidSnapshot(() -> service.get(missingFormatAttemptId));
  }

  @Test
  void canonicalizesArrayAnswerKeysAcrossDatabaseRestoreAndSettlementReplay() {
    jdbcTemplate.update("""
        UPDATE q_answer_key
        SET answer_value = NULL, answer_json = '["B"]'
        WHERE id = 9201
        """);
    var created = service.create(new CreatePracticeAttemptRequest(
        UUID.randomUUID(), 1L, 11L, "PRACTICE", "PER_QUESTION", 1,
        new CreatePracticeAttemptRequest.Source("KNOWLEDGE", "CHAPTER", List.of(101L), null, null)));
    service.saveAnswer(created.attemptId(), 9001L, new SavePracticeAnswerRequest(
        UUID.randomUUID(), 0, new PracticeAnswerPayload("B", null, null, null), false, 4));
    assertThat(service.get(created.attemptId()).questions().get(0).subQuestions().get(0)
        .answer().gradingStatus()).isNull();

    var restoredService = new PracticeAttemptApplicationService(
        new JdbcPracticeAttemptRepository(jdbcTemplate), PracticeAttemptFixtures::student,
        PracticeAttemptFixtures.permissiveFoundation(),
        Clock.fixed(Instant.parse("2026-07-16T03:01:00Z"), ZoneOffset.UTC));
    var settleRequest = new SettlePracticeQuestionRequest(UUID.randomUUID(), 0);
    var settled = restoredService.settleQuestion(created.attemptId(), 1001L, settleRequest);
    var replayed = restoredService.settleQuestion(created.attemptId(), 1001L, settleRequest);
    var detail = restoredService.get(created.attemptId());

    assertThat(replayed).usingRecursiveComparison().ignoringFields("idempotentReplay").isEqualTo(settled);
    assertThat(detail.questions().get(0).subQuestions().get(0).answer().gradingStatus())
        .hasToString("CORRECT");
    assertThat(detail.questions().get(0).subQuestions().get(0).grading().standardAnswer().optionKey())
        .isEqualTo("B");
    assertThat(jdbcTemplate.queryForObject("""
        SELECT is_correct FROM practice_attempt_answer WHERE attempt_id = ? AND sub_question_id = 9001
        """, Integer.class, created.attemptId())).isEqualTo(1);

    jdbcTemplate.update("""
        UPDATE practice_attempt_answer
        SET grading_status = 'WRONG', is_correct = 0
        WHERE attempt_id = ? AND sub_question_id = 9001
        """, created.attemptId());
    assertThat(restoredService.get(created.attemptId()).questions().get(0).subQuestions().get(0)
        .answer().gradingStatus()).hasToString("CORRECT");
  }

  @Test
  void rejectsAStaleSaveAfterTheAttemptWasSubmitted() {
    var created = service.create(new CreatePracticeAttemptRequest(
        UUID.randomUUID(), 1L, 11L, "PRACTICE", "WHOLE_PAPER", 1,
        new CreatePracticeAttemptRequest.Source("KNOWLEDGE", "CHAPTER", List.of(101L), null, null)));
    var stale = repository.findAttempt(2L, created.attemptId()).orElseThrow();

    service.submit(created.attemptId(), new SubmitPracticeAttemptRequest(UUID.randomUUID(), 0));

    assertThatThrownBy(() -> repository.saveAttempt(stale))
        .hasMessageContaining("attempt version conflict");
    assertThat(repository.findAttempt(2L, created.attemptId()).orElseThrow().status().name())
        .isEqualTo("SUBMITTED");
    assertThat(repository.findAttempt(2L, created.attemptId()).orElseThrow().version()).isEqualTo(1);
  }

  private long createPracticeAttempt() {
    return service.create(new CreatePracticeAttemptRequest(
        UUID.randomUUID(), 1L, 11L, "PRACTICE", "WHOLE_PAPER", 1,
        new CreatePracticeAttemptRequest.Source("KNOWLEDGE", "CHAPTER", List.of(101L), null, null)))
        .attemptId();
  }

  private void rewriteQuestionSnapshot(long attemptId, Consumer<ObjectNode> editor) throws Exception {
    String snapshot = jdbcTemplate.queryForObject("""
        SELECT question_snapshot_json FROM practice_attempt_question WHERE attempt_id = ?
        """, String.class, attemptId);
    ObjectNode snapshotNode = (ObjectNode) objectMapper.readTree(snapshot);
    editor.accept(snapshotNode);
    String updated = objectMapper.writeValueAsString(snapshotNode);
    jdbcTemplate.update("""
        UPDATE practice_attempt_question
        SET question_snapshot_json = ?, grading_snapshot_json = ?, solution_snapshot_json = ?,
            knowledge_snapshot_json = ?
        WHERE attempt_id = ?
        """, updated, updated, updated, updated, attemptId);
  }

  private ObjectNode firstSubQuestion(ObjectNode snapshot) {
    return (ObjectNode) ((ArrayNode) snapshot.get("subQuestions")).get(0);
  }

  private void assertInvalidSnapshot(Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.code()).isEqualTo("PRACTICE_ATTEMPT_SNAPSHOT_CONTRACT_INVALID");
          assertThat(exception.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        });
  }

  private void assertInvalidQuestionVersionSnapshot(Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.code()).isEqualTo("PRACTICE_ATTEMPT_SNAPSHOT_CONTRACT_INVALID");
          assertThat(exception.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
          assertThat(exception.getMessage())
              .doesNotContain("SENSITIVE_VALUE", "SELECT", "question_snapshot_json", "standardAnswer", "solution");
        });
  }

  private void createSchema() {
    jdbcTemplate.execute("""
        CREATE TABLE exam_subject (
          id BIGINT PRIMARY KEY, qualification_id BIGINT NOT NULL, subject_name VARCHAR(96) NOT NULL,
          subject_type VARCHAR(32) NOT NULL, sort_order INT NOT NULL, status VARCHAR(24) NOT NULL,
          deleted TINYINT NOT NULL
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE q_paper (
          id BIGINT PRIMARY KEY, qualification_id BIGINT NOT NULL, subject_id BIGINT,
          paper_type VARCHAR(24) NOT NULL, year_no INT,
          status VARCHAR(24) NOT NULL, deleted TINYINT NOT NULL
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE q_paper_question (
          id BIGINT PRIMARY KEY, paper_id BIGINT NOT NULL, question_id BIGINT NOT NULL,
          question_no INT NOT NULL, question_snapshot CLOB, deleted TINYINT NOT NULL
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE q_question (
          id BIGINT PRIMARY KEY, qualification_id BIGINT NOT NULL, subject_id BIGINT NOT NULL,
          question_type VARCHAR(32) NOT NULL, title VARCHAR(180), year_no INT, status VARCHAR(24) NOT NULL,
          content_version INT NOT NULL, current_working_version_id BIGINT,
          current_published_version_id BIGINT, deleted TINYINT NOT NULL
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE q_question_version (
          id BIGINT PRIMARY KEY, question_id BIGINT NOT NULL, version_no INT NOT NULL,
          status VARCHAR(24) NOT NULL, title VARCHAR(180), year_no INT, deleted TINYINT NOT NULL
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE q_question_stem (
          id BIGINT PRIMARY KEY, question_id BIGINT NOT NULL, question_version_id BIGINT NOT NULL,
          stem_type VARCHAR(32) NOT NULL, sort_order INT NOT NULL,
          content CLOB, deleted TINYINT NOT NULL
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE q_sub_question (
          id BIGINT PRIMARY KEY, question_id BIGINT NOT NULL, question_version_id BIGINT NOT NULL,
          sub_question_no INT NOT NULL,
          sub_question_type VARCHAR(32) NOT NULL, content CLOB NOT NULL, score DECIMAL(8,2),
          answer_format VARCHAR(32) NOT NULL, sort_order INT NOT NULL, metadata_json CLOB, deleted TINYINT NOT NULL
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE q_choice_option (
          id BIGINT PRIMARY KEY, sub_question_id BIGINT NOT NULL, option_key VARCHAR(16) NOT NULL,
          option_content CLOB NOT NULL, sort_order INT NOT NULL, deleted TINYINT NOT NULL
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE q_answer_key (
          id BIGINT PRIMARY KEY, sub_question_id BIGINT NOT NULL, answer_format VARCHAR(32) NOT NULL,
          answer_value CLOB, answer_json CLOB, version_no INT NOT NULL, current_flag TINYINT NOT NULL,
          deleted TINYINT NOT NULL
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE q_solution (
          id BIGINT PRIMARY KEY, question_id BIGINT NOT NULL, question_version_id BIGINT NOT NULL,
          sub_question_id BIGINT,
          solution_type VARCHAR(32) NOT NULL, content CLOB NOT NULL, version_no INT NOT NULL,
          deleted TINYINT NOT NULL
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE q_question_knowledge_binding (
          id BIGINT PRIMARY KEY, question_id BIGINT NOT NULL, question_version_id BIGINT NOT NULL,
          knowledge_type VARCHAR(32) NOT NULL,
          knowledge_id BIGINT NOT NULL, deleted TINYINT NOT NULL
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE q_scoring_point (
          id BIGINT PRIMARY KEY, question_version_id BIGINT NOT NULL, sub_question_id BIGINT NOT NULL,
          point_code VARCHAR(64) NOT NULL, point_text CLOB NOT NULL, score DECIMAL(8,2) NOT NULL,
          sort_order INT NOT NULL, deleted TINYINT NOT NULL
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE practice_attempt (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, user_id BIGINT,
          qualification_id BIGINT NOT NULL, subject_id BIGINT,
          paper_id BIGINT, exam_session_subject_id BIGINT, mode VARCHAR(32) NOT NULL, source_type VARCHAR(32),
          source_spec_json CLOB, feedback_mode VARCHAR(24), requested_count INT, question_count INT,
          partial_flag TINYINT NOT NULL, max_score DECIMAL(8,2), pending_review_count INT, version BIGINT NOT NULL,
          status VARCHAR(24) NOT NULL, started_at TIMESTAMP(3) NOT NULL, submitted_at TIMESTAMP(3),
          duration_seconds INT,
          total_score DECIMAL(8,2), correct_count INT, wrong_count INT,
          created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL,
          created_by BIGINT, updated_by BIGINT, deleted TINYINT NOT NULL
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE practice_attempt_question (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, attempt_id BIGINT NOT NULL,
          question_id BIGINT NOT NULL, question_order INT NOT NULL, question_content_version INT NOT NULL,
          question_type VARCHAR(32) NOT NULL, title VARCHAR(180), max_score DECIMAL(8,2) NOT NULL,
          question_snapshot_json CLOB NOT NULL, grading_snapshot_json CLOB NOT NULL,
          solution_snapshot_json CLOB NOT NULL, knowledge_snapshot_json CLOB NOT NULL,
          settlement_status VARCHAR(24) NOT NULL, settlement_version BIGINT NOT NULL,
          settled_at TIMESTAMP(3), earned_score DECIMAL(8,2), correct_count INT, wrong_count INT,
          pending_review_count INT, snapshot_hash VARBINARY(32) NOT NULL,
          created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL,
          UNIQUE(attempt_id, question_order), UNIQUE(attempt_id, question_id)
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE practice_attempt_answer (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, attempt_id BIGINT NOT NULL,
          question_id BIGINT NOT NULL,
          sub_question_id BIGINT NOT NULL, answer_json CLOB, grading_status VARCHAR(24),
          answer_version BIGINT NOT NULL, is_correct TINYINT, used_seconds INT, marked TINYINT NOT NULL,
          saved_at TIMESTAMP(3), created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL,
          created_by BIGINT, updated_by BIGINT, deleted TINYINT NOT NULL,
          UNIQUE(attempt_id, sub_question_id)
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE practice_attempt_idempotency (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, user_id BIGINT NOT NULL,
          attempt_scope_id BIGINT NOT NULL, attempt_id BIGINT, operation_code VARCHAR(32) NOT NULL,
          client_mutation_id CHAR(36) NOT NULL, request_hash VARBINARY(32) NOT NULL,
          status VARCHAR(16) NOT NULL, owner_token CHAR(36) NOT NULL, execution_epoch BIGINT NOT NULL,
          lease_expires_at TIMESTAMP(3) NOT NULL, http_status SMALLINT, response_code VARCHAR(64),
          response_snapshot_json CLOB, created_at TIMESTAMP(3) NOT NULL, completed_at TIMESTAMP(3),
          expires_at TIMESTAMP(3) NOT NULL,
          UNIQUE(user_id, operation_code, attempt_scope_id, client_mutation_id)
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE practice_mistake (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, user_id BIGINT NOT NULL,
          question_id BIGINT NOT NULL,
          qualification_id BIGINT NOT NULL, subject_id BIGINT, first_attempt_id BIGINT, last_attempt_id BIGINT,
          mistake_count INT NOT NULL, last_wrong_at TIMESTAMP(3), status VARCHAR(24) NOT NULL,
          created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL,
          created_by BIGINT, updated_by BIGINT, deleted TINYINT NOT NULL,
          UNIQUE(user_id, question_id)
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE practice_favorite (
          id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, question_id BIGINT NOT NULL,
          qualification_id BIGINT NOT NULL, subject_id BIGINT,
          created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL, deleted TINYINT NOT NULL
        )
        """);
  }

  private void insertForeignAttempt(
      Long attemptId,
      Long userId,
      Long qualificationId,
      Long subjectId,
      String status
  ) {
    jdbcTemplate.update("""
        INSERT INTO practice_attempt (
          id, user_id, qualification_id, subject_id, mode, source_type, source_spec_json,
          feedback_mode, requested_count, question_count, partial_flag, max_score,
          pending_review_count, version, status, started_at, submitted_at, total_score,
          correct_count, wrong_count, created_at, updated_at, created_by, updated_by, deleted
        ) VALUES (?, ?, ?, ?, 'PRACTICE', 'KNOWLEDGE', '{}', 'WHOLE_PAPER', 1, 0, 0, 1.00,
                  0, 0, ?, CURRENT_TIMESTAMP, NULL, 0, 0, 0,
                  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, 0)
        """, attemptId, userId, qualificationId, subjectId, status, userId, userId);
  }

  private void seedQuestion() {
    jdbcTemplate.update("""
        INSERT INTO exam_subject
          (id, qualification_id, subject_name, subject_type, sort_order, status, deleted)
        VALUES (11, 1, '综合知识', 'COMPREHENSIVE', 1, 'ENABLED', 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_paper
          (id, qualification_id, subject_id, paper_type, year_no, status, deleted)
        VALUES (601, 1, 11, 'REAL', 2026, 'PUBLISHED', 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_paper_question
          (id, paper_id, question_id, question_no, question_snapshot, deleted)
        VALUES (6101, 601, 1001, 1, '{"questionId":1001}', 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_question
          (id, qualification_id, subject_id, question_type, title, year_no, status, content_version,
           current_working_version_id, current_published_version_id, deleted)
        VALUES (1001, 1, 11, 'SINGLE_CHOICE', 'published summary', 2026, 'PUBLISHED', 3,
                7002, 7001, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_question_version (id, question_id, version_no, status, title, year_no, deleted)
        VALUES (7001, 1001, 3, 'PUBLISHED', 'published title', 2026, 0),
               (7002, 1001, 4, 'DRAFT', 'draft leak title', 2027, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_question_stem
          (id, question_id, question_version_id, stem_type, sort_order, content, deleted)
        VALUES (501, 1001, 7001, 'MAIN_STEM', 1, 'published stem', 0),
               (502, 1001, 7002, 'MAIN_STEM', 1, 'draft leak stem', 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_sub_question
          (id, question_id, question_version_id, sub_question_no, sub_question_type, content,
           score, answer_format, sort_order, metadata_json, deleted)
        VALUES (9001, 1001, 7001, 1, 'SINGLE_CHOICE', 'published sub-question', 2.00,
                'OPTION', 1, '{"source":"published"}', 0),
               (9002, 1001, 7002, 1, 'SINGLE_CHOICE', 'draft leak sub-question', 99.00,
                'OPTION', 1, '{"source":"draft"}', 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_choice_option
          (id, sub_question_id, option_key, option_content, sort_order, deleted)
        VALUES (9101, 9001, 'A', '甘特图', 1, 0), (9102, 9001, 'B', '鱼骨图', 2, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_answer_key
          (id, sub_question_id, answer_format, answer_value, answer_json, version_no, current_flag, deleted)
        VALUES (9201, 9001, 'OPTION', 'A', NULL, 1, 1, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_solution
          (id, question_id, question_version_id, sub_question_id, solution_type, content,
           version_no, deleted)
        VALUES (9301, 1001, 7001, 9001, 'REFERENCE_ANSWER', 'published solution', 1, 0),
               (9302, 1001, 7002, 9002, 'STANDARD', 'draft leak solution', 1, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_question_knowledge_binding
          (id, question_id, question_version_id, knowledge_type, knowledge_id, deleted)
        VALUES (9401, 1001, 7001, 'CHAPTER', 101, 0),
               (9402, 1001, 7002, 'CHAPTER', 999, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_scoring_point
          (id, question_version_id, sub_question_id, point_code, point_text, score, sort_order, deleted)
        VALUES (9501, 7001, 9001, 'P1', 'published scoring point', 2.00, 1, 0),
               (9502, 7002, 9002, 'D1', 'draft leak scoring point', 99.00, 1, 0)
        """);
  }
}
