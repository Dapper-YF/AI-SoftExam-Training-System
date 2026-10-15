package com.aiexam.question.practice.attempt.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PracticeAttempt(
    Long attemptId,
    Long userId,
    Long qualificationId,
    Long subjectId,
    String mode,
    String sourceType,
    Map<String, Object> sourceSpec,
    FeedbackMode feedbackMode,
    AttemptStatus status,
    int requestedCount,
    boolean partial,
    Instant startedAt,
    Instant submittedAt,
    long version,
    BigDecimal totalScore,
    BigDecimal maxScore,
    int correctCount,
    int wrongCount,
    int pendingReviewCount,
    List<PracticeQuestionSnapshot> questions,
    Map<Long, PracticeAnswer> answers
) {
  public PracticeAttempt {
    sourceSpec = sourceSpec == null ? Map.of() : Map.copyOf(sourceSpec);
    questions = questions == null ? List.of() : List.copyOf(questions);
    answers = answers == null ? Map.of() : Map.copyOf(answers);
    status = status == null ? AttemptStatus.IN_PROGRESS : status;
    feedbackMode = feedbackMode == null ? FeedbackMode.WHOLE_PAPER : feedbackMode;
    totalScore = totalScore == null ? BigDecimal.ZERO : totalScore;
    maxScore = maxScore == null ? BigDecimal.ZERO : maxScore;
  }

  public boolean terminal() {
    return status == AttemptStatus.SUBMITTED || status == AttemptStatus.PENDING_REVIEW;
  }

  public PracticeQuestionSnapshot question(Long questionId) {
    return questions.stream()
        .filter(question -> question.questionId().equals(questionId))
        .findFirst()
        .orElse(null);
  }

  public PracticeQuestionSnapshot questionBySubQuestion(Long subQuestionId) {
    return questions.stream()
        .filter(question -> question.subQuestions().stream()
            .anyMatch(subQuestion -> subQuestion.subQuestionId().equals(subQuestionId)))
        .findFirst()
        .orElse(null);
  }

  public PracticeAttempt withQuestionsAndAnswers(List<PracticeQuestionSnapshot> nextQuestions,
                                                 Map<Long, PracticeAnswer> nextAnswers,
                                                 long nextVersion) {
    BigDecimal settledScore = nextQuestions.stream()
        .filter(question -> question.settlementStatus() == QuestionSettlementStatus.SETTLED)
        .map(PracticeQuestionSnapshot::earnedScore)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    int settledCorrect = nextQuestions.stream().mapToInt(PracticeQuestionSnapshot::correctCount).sum();
    int settledWrong = nextQuestions.stream().mapToInt(PracticeQuestionSnapshot::wrongCount).sum();
    int settledPending = nextQuestions.stream().mapToInt(PracticeQuestionSnapshot::pendingReviewCount).sum();
    return new PracticeAttempt(attemptId, userId, qualificationId, subjectId, mode, sourceType, sourceSpec,
        feedbackMode, status, requestedCount, partial, startedAt, submittedAt, nextVersion, settledScore,
        maxScore, settledCorrect, settledWrong, settledPending, nextQuestions, nextAnswers);
  }

  public PracticeAttempt submit(List<PracticeQuestionSnapshot> settledQuestions, Map<Long, PracticeAnswer> nextAnswers,
                                Instant submittedAt, BigDecimal totalScore, int correctCount, int wrongCount,
                                int pendingReviewCount) {
    AttemptStatus nextStatus = pendingReviewCount > 0 ? AttemptStatus.PENDING_REVIEW : AttemptStatus.SUBMITTED;
    return new PracticeAttempt(attemptId, userId, qualificationId, subjectId, mode, sourceType, sourceSpec,
        feedbackMode, nextStatus, requestedCount, partial, startedAt, submittedAt, version + 1, totalScore,
        maxScore, correctCount, wrongCount, pendingReviewCount, settledQuestions, nextAnswers);
  }
}
