package com.aiexam.question.practice.exam.domain;

import java.time.Instant;

public record PersistedNextSubjectAbandonment(
    String nextSubjectKey,
    int nextSubjectOrder,
    long persistedVersion,
    Instant abandonedAt
) {
  public PersistedNextSubjectAbandonment {
    if (nextSubjectKey == null
        || nextSubjectKey.isBlank()
        || nextSubjectOrder <= 0
        || persistedVersion < 0
        || abandonedAt == null) {
      throw new ExamDomainException(
          ExamDomainErrorCode.PERSISTED_ABANDONMENT_FACT_REQUIRED,
          "Next subject key, positive order, persisted version and abandonment time are required");
    }
    nextSubjectKey = nextSubjectKey.trim();
  }
}
