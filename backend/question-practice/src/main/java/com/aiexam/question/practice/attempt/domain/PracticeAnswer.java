package com.aiexam.question.practice.attempt.domain;

import java.time.Instant;

public record PracticeAnswer(
    Long subQuestionId,
    Long questionId,
    PracticeAnswerPayload answerPayload,
    boolean marked,
    int usedSeconds,
    long answerVersion,
    GradingStatus gradingStatus,
    Instant savedAt
) {
  public PracticeAnswer withSavedPayload(PracticeAnswerPayload payload, boolean marked, int usedSeconds,
                                         long nextVersion, Instant savedAt) {
    return new PracticeAnswer(subQuestionId, questionId, payload, marked, usedSeconds, nextVersion,
        GradingStatus.SAVED, savedAt);
  }

  public PracticeAnswer withGrading(GradingStatus status) {
    return new PracticeAnswer(subQuestionId, questionId, answerPayload, marked, usedSeconds, answerVersion,
        status, savedAt);
  }
}
