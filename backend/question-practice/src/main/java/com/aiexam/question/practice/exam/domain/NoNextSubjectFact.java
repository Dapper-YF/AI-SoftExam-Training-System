package com.aiexam.question.practice.exam.domain;

public record NoNextSubjectFact(
    String currentSubjectKey,
    int currentSubjectOrder,
    long decisionVersion
) {
  public NoNextSubjectFact {
    if (currentSubjectKey == null
        || currentSubjectKey.isBlank()
        || currentSubjectOrder <= 0
        || decisionVersion < 0) {
      throw new ExamDomainException(
          ExamDomainErrorCode.NO_NEXT_SUBJECT_FACT_REQUIRED,
          "Current subject key, positive order and non-negative decision version are required");
    }
    currentSubjectKey = currentSubjectKey.trim();
  }
}
