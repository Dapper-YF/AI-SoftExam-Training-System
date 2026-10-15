package com.aiexam.question.practice.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.common.error.BusinessException;
import com.aiexam.foundation.application.FoundationApplicationService;
import com.aiexam.question.practice.attempt.application.PracticeAttemptApplicationService;
import com.aiexam.question.practice.attempt.application.PracticeCurrentUserProvider;
import com.aiexam.question.practice.attempt.domain.AttemptStatus;
import com.aiexam.question.practice.attempt.domain.PracticeAnswerPayload;
import com.aiexam.question.practice.attempt.domain.PracticeQuestionSnapshot;
import com.aiexam.question.practice.attempt.domain.QuestionSettlementStatus;
import com.aiexam.question.practice.attempt.dto.PracticeAttemptDetailDto;
import com.aiexam.question.practice.attempt.dto.PracticeAttemptExplanationDto;
import com.aiexam.question.practice.attempt.dto.SavePracticeAnswerRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PracticeAttemptApplicationServiceTest {
  private final InMemoryPracticeAttemptRepository repository = new InMemoryPracticeAttemptRepository();
  private final PracticeCurrentUserProvider currentUser = PracticeAttemptFixtures::student;
  private final PracticeAttemptApplicationService service = new PracticeAttemptApplicationService(
      repository, currentUser, PracticeAttemptFixtures.permissiveFoundation(),
      Clock.fixed(Instant.parse("2026-07-16T01:00:00Z"), ZoneOffset.UTC));

  @Test
  void createsStableAttemptSnapshotAndReplaysSameMutation() {
    UUID mutation = UUID.randomUUID();
    PracticeAttemptDetailDto first = service.create(PracticeAttemptFixtures.createRequest(mutation, "PER_QUESTION"));
    PracticeAttemptDetailDto replay = service.create(PracticeAttemptFixtures.createRequest(mutation, "PER_QUESTION"));

    assertThat(first.attemptId()).isEqualTo(replay.attemptId());
    assertThat(first.questions()).hasSize(1);
    assertThat(first.totalScore()).isNull();
    assertThat(first.maxScore()).isNull();
    assertThat(first.correctCount()).isNull();
    assertThat(first.wrongCount()).isNull();
    assertThat(first.pendingReviewCount()).isNull();
    assertThat(first.scoreStatus()).isNull();
    assertThat(first.questions().get(0).earnedScore()).isNull();
    assertThat(first.questions().get(0).subQuestions().get(0).grading()).isNull();
    assertThat(first.questions().get(0).subQuestions().get(0).solution()).isNull();
  }

  @Test
  void createsQualificationWidePaperWithoutInventingASubjectAndKeepsItsSource() {
    PracticeAttemptDetailDto attempt = service.create(
        new com.aiexam.question.practice.attempt.dto.CreatePracticeAttemptRequest(
            UUID.randomUUID(), 1L, null, "PAST_EXAM", "WHOLE_PAPER", 100,
            new com.aiexam.question.practice.attempt.dto.CreatePracticeAttemptRequest.Source(
                "PAPER", null, null, null, 501L)));

    assertThat(attempt.subjectId()).isNull();
    assertThat(attempt.requestedCount()).isEqualTo(1);
    assertThat(attempt.partial()).isFalse();
    var history = service.history(1, 20).items().get(0);
    assertThat(history.sourceType()).isEqualTo("PAPER");
    assertThat(history.sourceSpec()).containsEntry("paperId", 501L);
  }

  @Test
  void rejectsPastExamModeWithoutACompletePaperSource() {
    var request = new com.aiexam.question.practice.attempt.dto.CreatePracticeAttemptRequest(
        UUID.randomUUID(), 1L, 11L, "PAST_EXAM", "WHOLE_PAPER", 100,
        new com.aiexam.question.practice.attempt.dto.CreatePracticeAttemptRequest.Source(
            "DAILY", null, null, null, null));

    assertThatThrownBy(() -> service.create(request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("完整试卷");
  }

  @Test
  void rejectsPastExamWithPerQuestionFeedback() {
    var request = new com.aiexam.question.practice.attempt.dto.CreatePracticeAttemptRequest(
        UUID.randomUUID(), 1L, null, "PAST_EXAM", "PER_QUESTION", 100,
        new com.aiexam.question.practice.attempt.dto.CreatePracticeAttemptRequest.Source(
            "PAPER", null, null, null, 501L));

    assertThatThrownBy(() -> service.create(request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("整卷作答");
  }

  @Test
  void canonicalizesLegacyChapterSourceTypeBeforeSelectingQuestions() {
    service.create(new com.aiexam.question.practice.attempt.dto.CreatePracticeAttemptRequest(
        UUID.randomUUID(), 1L, 11L, "PRACTICE", "PER_QUESTION", 20,
        new com.aiexam.question.practice.attempt.dto.CreatePracticeAttemptRequest.Source(
            "KNOWLEDGE", "CHAPTER", List.of(101L), null, null)));

    assertThat(service.history(1, 20).items().get(0).sourceSpec())
        .containsEntry("knowledgeType", "CHAPTER_NODE");
  }

  @Test
  void stillRequiresASubjectForDailyPractice() {
    var request = new com.aiexam.question.practice.attempt.dto.CreatePracticeAttemptRequest(
        UUID.randomUUID(), 1L, null, "DAILY", "PER_QUESTION", 20,
        new com.aiexam.question.practice.attempt.dto.CreatePracticeAttemptRequest.Source(
            "DAILY", null, null, null, null));

    assertThatThrownBy(() -> service.create(request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("subjectId");
  }

  @Test
  void rejectsKnowledgeSourceOutsideConfirmedQualificationAndSubjectBeforeWriting() {
    PracticeAttemptApplicationService scopedService = new PracticeAttemptApplicationService(
        repository, currentUser, new FoundationApplicationService(null) {
          @Override
          public void validateQuestionScope(Long qualificationId, Long subjectId) {
          }

          @Override
          public void validateQuestionKnowledge(
              Long qualificationId,
              Long subjectId,
              String knowledgeType,
              Long knowledgeId
          ) {
            throw new BusinessException("VALIDATION_ERROR", "knowledge scope mismatch",
                org.springframework.http.HttpStatus.BAD_REQUEST);
          }
        },
        Clock.fixed(Instant.parse("2026-07-16T01:00:00Z"), ZoneOffset.UTC));

    assertThatThrownBy(() -> scopedService.create(PracticeAttemptFixtures.createRequest(
        UUID.randomUUID(), "PER_QUESTION")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("knowledge scope mismatch");
    assertThat(scopedService.history(1, 20).items()).isEmpty();
  }

  @Test
  void rejectsSameMutationWithDifferentPayloadBeforeWriting() {
    UUID mutation = UUID.randomUUID();
    service.create(PracticeAttemptFixtures.createRequest(mutation, "PER_QUESTION"));

    assertThatThrownBy(() -> service.create(PracticeAttemptFixtures.createRequest(mutation, "WHOLE_PAPER")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("clientMutationId");
  }

  @Test
  void savesAnswerWithBaseVersionAndRejectsStaleVersion() {
    PracticeAttemptDetailDto attempt = service.create(
        PracticeAttemptFixtures.createRequest(UUID.randomUUID(), "PER_QUESTION"));
    var saved = service.saveAnswer(attempt.attemptId(), 9001L,
        PracticeAttemptFixtures.answer(UUID.randomUUID(), 0, "a"));

    assertThat(saved.answerVersion()).isEqualTo(1);
    PracticeAttemptDetailDto detail = service.get(attempt.attemptId());
    assertThat(detail.questions().get(0).subQuestions().get(0).answer()).isNotNull();
    assertThat(detail.questions().get(0).subQuestions().get(0).answer().gradingStatus()).isNull();
    assertThatThrownBy(() -> service.saveAnswer(attempt.attemptId(), 9001L,
        PracticeAttemptFixtures.answer(UUID.randomUUID(), 0, "B")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("version conflict");
  }

  @Test
  void settlesSingleQuestionAndOpensExplanationOnlyForSettledQuestion() {
    PracticeAttemptDetailDto attempt = service.create(
        PracticeAttemptFixtures.createRequest(UUID.randomUUID(), "PER_QUESTION"));
    assertThatThrownBy(() -> service.explanation(attempt.attemptId(), 1001L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("解析不可见");

    service.saveAnswer(attempt.attemptId(), 9001L, PracticeAttemptFixtures.answer(UUID.randomUUID(), 0, "A"));
    var settled = service.settleQuestion(attempt.attemptId(), 1001L,
        PracticeAttemptFixtures.settle(UUID.randomUUID(), 0));

    assertThat(settled.questionId()).isEqualTo(1001L);
    assertThat(settled.totalScore()).isNull();
    assertThat(settled.maxScore()).isNull();
    assertThat(settled.correctCount()).isNull();
    assertThat(settled.wrongCount()).isNull();
    assertThat(settled.pendingReviewCount()).isNull();
    PracticeAttemptDetailDto detail = service.get(attempt.attemptId());
    assertThat(detail.questions().get(0).settlementStatus()).isEqualTo(QuestionSettlementStatus.SETTLED);
    assertThat(detail.questions().get(0).subQuestions().get(0).grading()).isNotNull();
    assertThat(service.explanation(attempt.attemptId(), 1001L).subQuestions()).hasSize(1);
  }

  @Test
  void hidesUnsafeGeneratedTemplateExplanationAtUserDtoBoundaries() {
    repository.questionBank(List.of(singleChoiceWithSolution(
        "CHOICE_GENERIC stem=raw answer=A selected=B frozen answer reference aligns internal template")));
    PracticeAttemptDetailDto attempt = service.create(
        PracticeAttemptFixtures.createRequest(UUID.randomUUID(), "PER_QUESTION"));
    service.saveAnswer(attempt.attemptId(), 9001L, PracticeAttemptFixtures.answer(UUID.randomUUID(), 0, "A"));
    service.settleQuestion(attempt.attemptId(), 1001L, PracticeAttemptFixtures.settle(UUID.randomUUID(), 0));

    var explanation = service.explanation(attempt.attemptId(), 1001L);
    var detailSubQuestion = service.get(attempt.attemptId()).questions().get(0).subQuestions().get(0);

    assertThat(explanation.status()).isEqualTo("IMPROVING");
    assertThat(explanation.subQuestions()).singleElement().satisfies(subQuestion -> {
      assertThat(subQuestion.solution()).isNull();
      assertThat(subQuestion.solutionStatus()).isEqualTo("IMPROVING");
      assertThat(subQuestion.standardAnswer()).isNotNull();
    });
    assertThat(detailSubQuestion.solution()).isNull();
    assertThat(detailSubQuestion.solutionStatus()).isEqualTo("IMPROVING");
  }

  @Test
  void keepsNormalLegacyExplanationReadyAndTreatsEmptyExplanationAsSafeState() {
    PracticeAttemptDetailDto normalAttempt = service.create(
        PracticeAttemptFixtures.createRequest(UUID.randomUUID(), "PER_QUESTION"));
    service.saveAnswer(normalAttempt.attemptId(), 9001L, PracticeAttemptFixtures.answer(UUID.randomUUID(), 0, "A"));
    service.settleQuestion(normalAttempt.attemptId(), 1001L,
        PracticeAttemptFixtures.settle(UUID.randomUUID(), 0));

    var normalExplanation = service.explanation(normalAttempt.attemptId(), 1001L);
    assertThat(normalExplanation.status()).isEqualTo("READY");
    assertThat(normalExplanation.subQuestions().get(0).solution()).isNotBlank();
    assertThat(normalExplanation.subQuestions().get(0).solutionStatus()).isEqualTo("READY");

    repository.questionBank(List.of(singleChoiceWithSolution("   ")));
    PracticeAttemptDetailDto emptyAttempt = service.create(
        PracticeAttemptFixtures.createRequest(UUID.randomUUID(), "PER_QUESTION"));
    service.saveAnswer(emptyAttempt.attemptId(), 9001L, PracticeAttemptFixtures.answer(UUID.randomUUID(), 0, "A"));
    service.settleQuestion(emptyAttempt.attemptId(), 1001L,
        PracticeAttemptFixtures.settle(UUID.randomUUID(), 0));

    var emptyExplanation = service.explanation(emptyAttempt.attemptId(), 1001L);
    assertThat(emptyExplanation.status()).isEqualTo("IMPROVING");
    assertThat(emptyExplanation.subQuestions().get(0).solution()).isNull();
    assertThat(emptyExplanation.subQuestions().get(0).solutionStatus()).isEqualTo("IMPROVING");
  }

  @Test
  void marksImprovingWhenOnlyScoringPointContainsUnsafeTemplateText() {
    repository.questionBank(List.of(singleChoiceWithSolutionAndScoringPoints("normal solution",
        List.of(new PracticeQuestionSnapshot.ScoringPoint(1L, "P1",
            "answer=A selected=B internal template", new BigDecimal("2.00"), 1)))));
    PracticeAttemptDetailDto attempt = service.create(
        PracticeAttemptFixtures.createRequest(UUID.randomUUID(), "PER_QUESTION"));
    service.saveAnswer(attempt.attemptId(), 9001L, PracticeAttemptFixtures.answer(UUID.randomUUID(), 0, "A"));
    service.settleQuestion(attempt.attemptId(), 1001L, PracticeAttemptFixtures.settle(UUID.randomUUID(), 0));

    var explanation = service.explanation(attempt.attemptId(), 1001L);
    var detailSubQuestion = service.get(attempt.attemptId()).questions().get(0).subQuestions().get(0);

    assertThat(explanation.status()).isEqualTo("IMPROVING");
    assertThat(explanation.subQuestions()).singleElement().satisfies(subQuestion -> {
      assertThat(subQuestion.solution()).isEqualTo("normal solution");
      assertThat(subQuestion.solutionStatus()).isEqualTo("IMPROVING");
      assertThat(subQuestion.scoringPoints()).isEmpty();
      assertThat(subQuestion.toString()).doesNotContain("answer=A", "selected=B", "internal template");
    });
    assertThat(detailSubQuestion.solution()).isEqualTo("normal solution");
    assertThat(detailSubQuestion.solutionStatus()).isEqualTo("IMPROVING");
    assertThat(detailSubQuestion.scoringPoints()).isEmpty();
  }

  @Test
  void keepsNormalChineseScoringPointReadyAndVisible() {
    repository.questionBank(List.of(singleChoiceWithSolutionAndScoringPoints("normal solution",
        List.of(new PracticeQuestionSnapshot.ScoringPoint(1L, "P1",
            "识别风险并给出应对措施", new BigDecimal("2.00"), 1)))));
    PracticeAttemptDetailDto attempt = service.create(
        PracticeAttemptFixtures.createRequest(UUID.randomUUID(), "PER_QUESTION"));
    service.saveAnswer(attempt.attemptId(), 9001L, PracticeAttemptFixtures.answer(UUID.randomUUID(), 0, "A"));
    service.settleQuestion(attempt.attemptId(), 1001L, PracticeAttemptFixtures.settle(UUID.randomUUID(), 0));

    var explanation = service.explanation(attempt.attemptId(), 1001L);
    var detailSubQuestion = service.get(attempt.attemptId()).questions().get(0).subQuestions().get(0);

    assertThat(explanation.status()).isEqualTo("READY");
    assertThat(explanation.subQuestions().get(0).solutionStatus()).isEqualTo("READY");
    assertThat(explanation.subQuestions().get(0).scoringPoints()).singleElement()
        .satisfies(point -> assertThat(point.pointText()).isEqualTo("识别风险并给出应对措施"));
    assertThat(detailSubQuestion.solutionStatus()).isEqualTo("READY");
    assertThat(detailSubQuestion.scoringPoints()).singleElement()
        .satisfies(point -> assertThat(point.pointText()).isEqualTo("识别风险并给出应对措施"));
  }

  @Test
  void aggregatesImprovingStatusAcrossMultipleSubQuestionsConsistently() {
    repository.questionBank(List.of(caseQuestionWithFirstScoringPointText("selected=B answer=A internal template")));
    PracticeAttemptDetailDto attempt = service.createCaseAttempt(
        new com.aiexam.question.practice.attempt.dto.CreateCaseAttemptRequest(
            UUID.randomUUID(), 1L, 11L, 2001L));
    service.saveAnswer(attempt.attemptId(), 9101L, textAnswer(UUID.randomUUID(), 0, "risk register"));
    service.saveAnswer(attempt.attemptId(), 9102L, textAnswer(UUID.randomUUID(), 0, "response plan"));
    service.submit(attempt.attemptId(), PracticeAttemptFixtures.submit(UUID.randomUUID(), 0));

    var explanation = service.explanation(attempt.attemptId(), 2001L);
    var detailSubQuestions = service.get(attempt.attemptId()).questions().get(0).subQuestions();

    assertThat(explanation.status()).isEqualTo("IMPROVING");
    assertThat(explanation.subQuestions()).extracting(
        PracticeAttemptExplanationDto.SubQuestionExplanationDto::solutionStatus)
        .containsExactly("IMPROVING", "READY");
    assertThat(explanation.subQuestions().get(0).scoringPoints())
        .extracting(PracticeAttemptExplanationDto.ScoringPointDto::pointText)
        .doesNotContain("selected=B answer=A internal template");
    assertThat(detailSubQuestions).extracting(PracticeAttemptDetailDto.SubQuestionDto::solutionStatus)
        .containsExactly("IMPROVING", "READY");
    assertThat(detailSubQuestions.get(0).scoringPoints())
        .extracting(PracticeAttemptDetailDto.ScoringPointDto::pointText)
        .doesNotContain("selected=B answer=A internal template");
  }

  @Test
  void gradesSingleChoiceByCanonicalOptionKeyAndReplaysTheSameSettlement() {
    repository.questionBank(List.of(singleChoiceWithArrayStandard()));
    PracticeAttemptDetailDto attempt = service.create(
        PracticeAttemptFixtures.createRequest(UUID.randomUUID(), "PER_QUESTION"));
    service.saveAnswer(attempt.attemptId(), 9001L,
        PracticeAttemptFixtures.answer(UUID.randomUUID(), 0, " b "));
    var refreshed = service.get(attempt.attemptId());
    assertThat(refreshed.questions().get(0).subQuestions().get(0).answer().answerPayload().optionKey())
        .isEqualTo("B");

    UUID settleMutation = UUID.randomUUID();
    var request = PracticeAttemptFixtures.settle(settleMutation, 0);
    var settled = service.settleQuestion(attempt.attemptId(), 1001L, request);
    var replayed = service.settleQuestion(attempt.attemptId(), 1001L, request);
    var detail = service.get(attempt.attemptId());

    assertThat(settled.questionId()).isEqualTo(1001L);
    assertThat(replayed).usingRecursiveComparison().ignoringFields("idempotentReplay").isEqualTo(settled);
    assertThat(detail.questions().get(0).subQuestions().get(0).answer().gradingStatus())
        .hasToString("CORRECT");
    assertThat(detail.questions().get(0).subQuestions().get(0).grading().standardAnswer().optionKey())
        .isEqualTo("B");
    assertThat(detail.questions().get(0).subQuestions().get(0).grading().standardAnswer().optionKeys())
        .isNull();
  }

  @Test
  void keepsWrongSingleChoiceWrongAndRejectsOptionIdsAtTheKeyBoundary() {
    repository.questionBank(List.of(singleChoiceWithArrayStandard()));
    PracticeAttemptDetailDto wrongAttempt = service.create(
        PracticeAttemptFixtures.createRequest(UUID.randomUUID(), "PER_QUESTION"));
    service.saveAnswer(wrongAttempt.attemptId(), 9001L,
        PracticeAttemptFixtures.answer(UUID.randomUUID(), 0, "A"));
    service.settleQuestion(wrongAttempt.attemptId(), 1001L,
        PracticeAttemptFixtures.settle(UUID.randomUUID(), 0));

    assertThat(service.get(wrongAttempt.attemptId()).questions().get(0).subQuestions().get(0)
        .answer().gradingStatus()).hasToString("WRONG");

    PracticeAttemptDetailDto boundaryAttempt = service.create(
        PracticeAttemptFixtures.createRequest(UUID.randomUUID(), "PER_QUESTION"));
    assertThatThrownBy(() -> service.saveAnswer(boundaryAttempt.attemptId(), 9001L,
        PracticeAttemptFixtures.answer(UUID.randomUUID(), 0, "9102")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("optionKey");
  }

  @Test
  void gradesMultiChoiceAsAnOrderIndependentSet() {
    repository.questionBank(List.of(multiChoiceWithUnsortedStandard()));
    PracticeAttemptDetailDto attempt = service.create(
        PracticeAttemptFixtures.createRequest(UUID.randomUUID(), "PER_QUESTION"));
    service.saveAnswer(attempt.attemptId(), 9001L, new SavePracticeAnswerRequest(
        UUID.randomUUID(), 0,
        new PracticeAnswerPayload(null, List.of("B", "A"), null, null), false, 0));
    service.settleQuestion(attempt.attemptId(), 1001L,
        PracticeAttemptFixtures.settle(UUID.randomUUID(), 0));

    assertThat(service.get(attempt.attemptId()).questions().get(0).subQuestions().get(0)
        .answer().gradingStatus()).hasToString("CORRECT");
  }

  @Test
  void keepsBooleanJudgeGradingUnchanged() {
    repository.questionBank(List.of(judgeQuestion()));
    PracticeAttemptDetailDto attempt = service.create(
        PracticeAttemptFixtures.createRequest(UUID.randomUUID(), "PER_QUESTION"));
    service.saveAnswer(attempt.attemptId(), 9001L, new SavePracticeAnswerRequest(
        UUID.randomUUID(), 0, new PracticeAnswerPayload(null, null, true, null), false, 0));
    service.settleQuestion(attempt.attemptId(), 1001L,
        PracticeAttemptFixtures.settle(UUID.randomUUID(), 0));

    assertThat(service.get(attempt.attemptId()).questions().get(0).subQuestions().get(0)
        .answer().gradingStatus()).hasToString("CORRECT");
  }

  @Test
  void synchronizesAWrongQuestionOnlyOnItsFirstSettlement() {
    PracticeAttemptDetailDto attempt = service.create(
        PracticeAttemptFixtures.createRequest(UUID.randomUUID(), "PER_QUESTION"));
    service.saveAnswer(attempt.attemptId(), 9001L,
        PracticeAttemptFixtures.answer(UUID.randomUUID(), 0, "B"));

    service.settleQuestion(attempt.attemptId(), 1001L,
        PracticeAttemptFixtures.settle(UUID.randomUUID(), 0));
    service.settleQuestion(attempt.attemptId(), 1001L,
        PracticeAttemptFixtures.settle(UUID.randomUUID(), 1));
    service.submit(attempt.attemptId(), PracticeAttemptFixtures.submit(UUID.randomUUID(), 1));

    assertThat(repository.mistakeSyncCount(1001L)).isEqualTo(1);
  }

  @Test
  void rejectsSingleQuestionSettlementForWholePaperMode() {
    PracticeAttemptDetailDto attempt = service.create(
        PracticeAttemptFixtures.createRequest(UUID.randomUUID(), "WHOLE_PAPER"));

    assertThatThrownBy(() -> service.settleQuestion(attempt.attemptId(), 1001L,
        PracticeAttemptFixtures.settle(UUID.randomUUID(), 0)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("disabled");
  }

  @Test
  void submitsWholeAttemptAndLocksFutureAnswerWrites() {
    repository.questionBank(List.of(PracticeAttemptFixtures.singleChoiceQuestion(),
        PracticeAttemptFixtures.essayQuestion()));
    PracticeAttemptDetailDto attempt = service.create(new com.aiexam.question.practice.attempt.dto.CreatePracticeAttemptRequest(
        UUID.randomUUID(), 1L, 11L, "PRACTICE", "WHOLE_PAPER", 2,
        new com.aiexam.question.practice.attempt.dto.CreatePracticeAttemptRequest.Source("KNOWLEDGE",
            "CHAPTER", List.of(101L), null, null)));
    service.saveAnswer(attempt.attemptId(), 9001L, PracticeAttemptFixtures.answer(UUID.randomUUID(), 0, "A"));

    var submitted = service.submit(attempt.attemptId(), PracticeAttemptFixtures.submit(UUID.randomUUID(), 0));

    assertThat(submitted.status()).isEqualTo(AttemptStatus.PENDING_REVIEW);
    assertThat(submitted.pendingReviewCount()).isEqualTo(1);
    assertThatThrownBy(() -> service.saveAnswer(attempt.attemptId(), 9001L,
        PracticeAttemptFixtures.answer(UUID.randomUUID(), 1, "A")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("submitted");
  }

  @Test
  void createsCaseAttemptWithMaterialsAndRevealsScoringPointsOnlyAfterSubmission() {
    repository.questionBank(List.of(caseQuestion()));
    PracticeAttemptDetailDto attempt = service.createCaseAttempt(new com.aiexam.question.practice.attempt.dto.CreateCaseAttemptRequest(
        UUID.randomUUID(), 1L, 11L, 2001L));

    assertThat(attempt.questions()).hasSize(1);
    assertThat(attempt.questions().get(0).questionVersionId()).isEqualTo(7001L);
    assertThat(attempt.questions().get(0).questionType()).isEqualTo("CASE");
    assertThat(attempt.questions().get(0).stems())
        .extracting(PracticeAttemptDetailDto.StemDto::stemType)
        .contains("CASE_MATERIAL");
    assertThat(attempt.questions().get(0).subQuestions()).hasSize(2);
    assertThat(attempt.questions().get(0).subQuestions().get(0).answerFormat()).isEqualTo("TEXT");
    assertThat(attempt.questions().get(0).subQuestions().get(0).grading()).isNull();
    assertThat(attempt.questions().get(0).subQuestions().get(0).solution()).isNull();
    assertThat(attempt.questions().get(0).subQuestions().get(0).scoringPoints()).isNull();

    service.saveAnswer(attempt.attemptId(), 9101L, textAnswer(UUID.randomUUID(), 0, "risk register"));
    service.saveAnswer(attempt.attemptId(), 9102L, textAnswer(UUID.randomUUID(), 0, "response plan"));
    PracticeAttemptDetailDto refreshed = service.get(attempt.attemptId());
    assertThat(refreshed.questions().get(0).subQuestions().get(0).answer().answerPayload().text())
        .isEqualTo("risk register");

    var submitted = service.submit(attempt.attemptId(), PracticeAttemptFixtures.submit(UUID.randomUUID(), 0));
    PracticeAttemptDetailDto reviewed = service.get(attempt.attemptId());

    assertThat(submitted.status()).isEqualTo(AttemptStatus.PENDING_REVIEW);
    assertThat(submitted.pendingReviewCount()).isEqualTo(2);
    assertThat(submitted.totalScore()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(reviewed.questions().get(0).subQuestions().get(0).answer().gradingStatus())
        .hasToString("PENDING_REVIEW");
    assertThat(reviewed.questions().get(0).subQuestions().get(0).grading().standardAnswer().text())
        .isEqualTo("reference answer 1");
    assertThat(reviewed.questions().get(0).subQuestions().get(0).scoringPoints())
        .extracting(PracticeAttemptDetailDto.ScoringPointDto::pointCode)
        .containsExactly("P1", "P2");
    assertThat(service.explanation(attempt.attemptId(), 2001L).subQuestions().get(0).scoringPoints())
        .hasSize(2);
  }

  @Test
  void savesAndSubmitsEssayWithoutLeakingRubricBeforeSubmission() {
    repository.questionBank(List.of(essayWithRubric()));
    PracticeAttemptDetailDto attempt = service.create(
        PracticeAttemptFixtures.createRequest(UUID.randomUUID(), "WHOLE_PAPER"));

    var essay = attempt.questions().get(0).subQuestions().get(0);
    assertThat(attempt.questions().get(0).stems())
        .extracting(PracticeAttemptDetailDto.StemDto::stemType)
        .contains("ESSAY_REQUIREMENT");
    assertThat(essay.metadata()).containsEntry("minWords", 2000).containsEntry("maxWords", 2500);
    assertThat(essay.scoringPoints()).isNull();
    assertThat(essay.grading()).isNull();

    service.saveAnswer(attempt.attemptId(), 9201L,
        textAnswer(UUID.randomUUID(), 0, "draft paragraph\nsecond paragraph"));
    PracticeAttemptDetailDto refreshed = service.get(attempt.attemptId());
    assertThat(refreshed.questions().get(0).subQuestions().get(0).answer().answerPayload().text())
        .isEqualTo("draft paragraph\nsecond paragraph");

    service.submit(attempt.attemptId(), PracticeAttemptFixtures.submit(UUID.randomUUID(), 0));
    PracticeAttemptDetailDto submitted = service.get(attempt.attemptId());

    assertThat(submitted.status()).isEqualTo(AttemptStatus.PENDING_REVIEW);
    assertThat(submitted.scoreStatus()).isEqualTo("PENDING_REVIEW");
    assertThat(submitted.questions().get(0).subQuestions().get(0).scoringPoints())
        .extracting(PracticeAttemptDetailDto.ScoringPointDto::pointCode)
        .containsExactly("D1", "D2");
    assertThat(submitted.questions().get(0).subQuestions().get(0).grading().standardAnswer().text())
        .isEqualTo("reference outline");
  }

  @Test
  void rejectsPerQuestionSettlementForSubjectiveQuestions() {
    repository.questionBank(List.of(caseQuestion()));
    PracticeAttemptDetailDto attempt = service.create(
        PracticeAttemptFixtures.createRequest(UUID.randomUUID(), "PER_QUESTION"));

    assertThatThrownBy(() -> service.settleQuestion(attempt.attemptId(), 2001L,
        PracticeAttemptFixtures.settle(UUID.randomUUID(), 0)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("主观题");
  }

  @Test
  void hidesAttemptsAcrossUsers() {
    PracticeAttemptDetailDto attempt = service.create(
        PracticeAttemptFixtures.createRequest(UUID.randomUUID(), "WHOLE_PAPER"));
    PracticeAttemptApplicationService otherUserService = new PracticeAttemptApplicationService(
        repository, () -> new com.aiexam.question.practice.attempt.application.PracticePrincipal(
        99L, "other", Set.of()), PracticeAttemptFixtures.permissiveFoundation(),
        Clock.fixed(Instant.parse("2026-07-16T01:00:00Z"), ZoneOffset.UTC));

    assertThatThrownBy(() -> otherUserService.get(attempt.attemptId()))
        .isInstanceOf(com.aiexam.common.error.ResourceNotFoundException.class);
    assertThatThrownBy(() -> otherUserService.saveAnswer(attempt.attemptId(), 9001L,
        PracticeAttemptFixtures.answer(UUID.randomUUID(), 0, "A")))
        .isInstanceOf(com.aiexam.common.error.ResourceNotFoundException.class);
  }

  @Test
  void historyDoesNotLeakScoresBeforeSubmission() {
    PracticeAttemptDetailDto attempt = service.create(
        PracticeAttemptFixtures.createRequest(UUID.randomUUID(), "WHOLE_PAPER"));

    var inProgress = service.history(1, 20).items().get(0);
    assertThat(inProgress.totalScore()).isNull();
    assertThat(inProgress.maxScore()).isNull();

    service.saveAnswer(attempt.attemptId(), 9001L,
        PracticeAttemptFixtures.answer(UUID.randomUUID(), 0, "A"));
    service.submit(attempt.attemptId(), PracticeAttemptFixtures.submit(UUID.randomUUID(), 0));

    var submitted = service.history(1, 20).items().get(0);
    assertThat(submitted.totalScore()).isEqualByComparingTo("2.00");
    assertThat(submitted.maxScore()).isEqualByComparingTo("2.00");
  }

  private PracticeQuestionSnapshot singleChoiceWithArrayStandard() {
    PracticeQuestionSnapshot base = PracticeAttemptFixtures.singleChoiceQuestion();
    PracticeQuestionSnapshot.SubQuestion subQuestion = base.subQuestions().get(0);
    PracticeQuestionSnapshot.SubQuestion arrayStandard = new PracticeQuestionSnapshot.SubQuestion(
        subQuestion.subQuestionId(), subQuestion.subQuestionType(), subQuestion.content(), subQuestion.options(),
        new PracticeAnswerPayload(null, List.of("B"), null, null), subQuestion.solution(), subQuestion.score());
    return new PracticeQuestionSnapshot(base.questionId(), base.questionOrder(), base.contentVersion(),
        base.questionType(), base.title(), base.stems(), List.of(arrayStandard), base.knowledgeBindings(),
        base.settlementStatus(), base.settlementVersion(), base.earnedScore(), base.correctCount(),
        base.wrongCount(), base.pendingReviewCount());
  }

  private PracticeQuestionSnapshot singleChoiceWithSolution(String solution) {
    PracticeQuestionSnapshot base = PracticeAttemptFixtures.singleChoiceQuestion();
    PracticeQuestionSnapshot.SubQuestion subQuestion = base.subQuestions().get(0);
    PracticeQuestionSnapshot.SubQuestion patched = new PracticeQuestionSnapshot.SubQuestion(
        subQuestion.subQuestionId(), subQuestion.subQuestionType(), subQuestion.answerFormat(),
        subQuestion.content(), subQuestion.metadata(), subQuestion.options(), subQuestion.standardAnswer(),
        solution, subQuestion.scoringPoints(), subQuestion.score());
    return new PracticeQuestionSnapshot(base.questionId(), base.questionVersionId(), base.questionOrder(),
        base.contentVersion(), base.questionType(), base.title(), base.stems(), List.of(patched),
        base.knowledgeBindings(), base.settlementStatus(), base.settlementVersion(), base.earnedScore(),
        base.correctCount(), base.wrongCount(), base.pendingReviewCount());
  }

  private PracticeQuestionSnapshot singleChoiceWithSolutionAndScoringPoints(
      String solution,
      List<PracticeQuestionSnapshot.ScoringPoint> scoringPoints
  ) {
    PracticeQuestionSnapshot base = PracticeAttemptFixtures.singleChoiceQuestion();
    PracticeQuestionSnapshot.SubQuestion subQuestion = base.subQuestions().get(0);
    PracticeQuestionSnapshot.SubQuestion patched = new PracticeQuestionSnapshot.SubQuestion(
        subQuestion.subQuestionId(), subQuestion.subQuestionType(), subQuestion.answerFormat(),
        subQuestion.content(), subQuestion.metadata(), subQuestion.options(), subQuestion.standardAnswer(),
        solution, scoringPoints, subQuestion.score());
    return new PracticeQuestionSnapshot(base.questionId(), base.questionVersionId(), base.questionOrder(),
        base.contentVersion(), base.questionType(), base.title(), base.stems(), List.of(patched),
        base.knowledgeBindings(), base.settlementStatus(), base.settlementVersion(), base.earnedScore(),
        base.correctCount(), base.wrongCount(), base.pendingReviewCount());
  }

  private PracticeQuestionSnapshot caseQuestionWithFirstScoringPointText(String pointText) {
    PracticeQuestionSnapshot base = caseQuestion();
    PracticeQuestionSnapshot.SubQuestion first = base.subQuestions().get(0);
    PracticeQuestionSnapshot.SubQuestion patchedFirst = new PracticeQuestionSnapshot.SubQuestion(
        first.subQuestionId(), first.subQuestionType(), first.answerFormat(), first.content(), first.metadata(),
        first.options(), first.standardAnswer(), first.solution(), List.of(
        new PracticeQuestionSnapshot.ScoringPoint(1L, "P1", pointText, new BigDecimal("3.00"), 1)),
        first.score());
    return new PracticeQuestionSnapshot(base.questionId(), base.questionVersionId(), base.questionOrder(),
        base.contentVersion(), base.questionType(), base.title(), base.stems(),
        List.of(patchedFirst, base.subQuestions().get(1)), base.knowledgeBindings(), base.settlementStatus(),
        base.settlementVersion(), base.earnedScore(), base.correctCount(), base.wrongCount(),
        base.pendingReviewCount());
  }

  private PracticeQuestionSnapshot multiChoiceWithUnsortedStandard() {
    return new PracticeQuestionSnapshot(1001L, 1, 3, "MULTI_CHOICE", "多选题",
        List.of(new PracticeQuestionSnapshot.Stem(501L, 1, "请选择正确选项")),
        List.of(new PracticeQuestionSnapshot.SubQuestion(9001L, "MULTI_CHOICE", "至少选择两项",
            List.of(
                new PracticeQuestionSnapshot.Option("A", "选项 A", 1),
                new PracticeQuestionSnapshot.Option("B", "选项 B", 2),
                new PracticeQuestionSnapshot.Option("C", "选项 C", 3)),
            new PracticeAnswerPayload(null, List.of("B", "A"), null, null),
            "A、B 均正确。", new BigDecimal("2.00"))),
        List.of(new PracticeQuestionSnapshot.KnowledgeBinding("CHAPTER", 101L, "项目管理")),
        QuestionSettlementStatus.UNSETTLED, 0, BigDecimal.ZERO, 0, 0, 0);
  }

  private PracticeQuestionSnapshot judgeQuestion() {
    return new PracticeQuestionSnapshot(1001L, 1, 3, "JUDGE", "判断题",
        List.of(new PracticeQuestionSnapshot.Stem(501L, 1, "该说法是否正确？")),
        List.of(new PracticeQuestionSnapshot.SubQuestion(9001L, "JUDGE", "请判断",
            List.of(), new PracticeAnswerPayload(null, null, true, null),
            "该说法正确。", new BigDecimal("2.00"))),
        List.of(new PracticeQuestionSnapshot.KnowledgeBinding("CHAPTER", 101L, "项目管理")),
        QuestionSettlementStatus.UNSETTLED, 0, BigDecimal.ZERO, 0, 0, 0);
  }

  private SavePracticeAnswerRequest textAnswer(UUID mutation, long baseVersion, String text) {
    return new SavePracticeAnswerRequest(mutation, baseVersion,
        new PracticeAnswerPayload(null, null, null, text), false, 30);
  }

  private PracticeQuestionSnapshot caseQuestion() {
    return new PracticeQuestionSnapshot(2001L, 7001L, 1, 1, "CASE", "Case question",
        List.of(
            new PracticeQuestionSnapshot.Stem(8101L, "MAIN_STEM", 1, "Project background"),
            new PracticeQuestionSnapshot.Stem(8102L, "CASE_MATERIAL", 2, "Case material")),
        List.of(
            new PracticeQuestionSnapshot.SubQuestion(9101L, "SHORT_ANSWER", "TEXT", "Analyze issue one",
                Map.of(), List.of(), new PracticeAnswerPayload(null, null, null, "reference answer 1"),
                "analysis 1", List.of(
                new PracticeQuestionSnapshot.ScoringPoint(1L, "P1", "identify risk", new BigDecimal("3.00"), 1),
                new PracticeQuestionSnapshot.ScoringPoint(2L, "P2", "give response", new BigDecimal("2.00"), 2)),
                new BigDecimal("5.00")),
            new PracticeQuestionSnapshot.SubQuestion(9102L, "SHORT_ANSWER", "TEXT", "Analyze issue two",
                Map.of(), List.of(), new PracticeAnswerPayload(null, null, null, "reference answer 2"),
                "analysis 2", List.of(
                new PracticeQuestionSnapshot.ScoringPoint(3L, "P3", "state measure", new BigDecimal("5.00"), 1)),
                new BigDecimal("5.00"))),
        List.of(new PracticeQuestionSnapshot.KnowledgeBinding("CHAPTER", 101L, "Risk")),
        QuestionSettlementStatus.UNSETTLED, 0, BigDecimal.ZERO, 0, 0, 0);
  }

  private PracticeQuestionSnapshot essayWithRubric() {
    return new PracticeQuestionSnapshot(3001L, 8001L, 1, 1, "ESSAY", "Essay question",
        List.of(
            new PracticeQuestionSnapshot.Stem(8201L, "MAIN_STEM", 1, "Scenario"),
            new PracticeQuestionSnapshot.Stem(8202L, "ESSAY_REQUIREMENT", 2, "Write about quality management")),
        List.of(new PracticeQuestionSnapshot.SubQuestion(9201L, "ESSAY_WRITING", "TEXT",
            "Write one essay", Map.of("minWords", 2000, "maxWords", 2500), List.of(),
            new PracticeAnswerPayload(null, null, null, "reference outline"), "rubric explanation",
            List.of(
                new PracticeQuestionSnapshot.ScoringPoint(11L, "D1", "structure", new BigDecimal("20.00"), 1),
                new PracticeQuestionSnapshot.ScoringPoint(12L, "D2", "argument", new BigDecimal("25.00"), 2)),
            new BigDecimal("45.00"))),
        List.of(new PracticeQuestionSnapshot.KnowledgeBinding("TAG", 302L, "Quality")),
        QuestionSettlementStatus.UNSETTLED, 0, BigDecimal.ZERO, 0, 0, 0);
  }
}
