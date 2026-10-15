package com.aiexam.question.practice.exam.domain;

import java.time.Instant;

public record ExamTimingSnapshot(
    Instant serverNow,
    long remainingSeconds,
    boolean canSubmit,
    boolean forceSubmit
) {
  public ExamTimingSnapshot {
    if (serverNow == null) {
      throw ExamDomainException.invalidTime("Server time is required");
    }
    if (remainingSeconds < 0) {
      throw ExamDomainException.invalidTime("Remaining seconds cannot be negative");
    }
  }
}
