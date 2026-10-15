package com.aiexam.question.practice.exam.domain;

import java.time.Instant;

public record ExamExceptionalResult(
    ExamExceptionalResultCode code,
    Instant occurredAt
) {
  public ExamExceptionalResult {
    if (code == null || occurredAt == null) {
      throw new ExamDomainException(
          ExamDomainErrorCode.INVALID_EXCEPTIONAL_RESULT,
          "Exceptional result code and occurrence time are required");
    }
  }
}
