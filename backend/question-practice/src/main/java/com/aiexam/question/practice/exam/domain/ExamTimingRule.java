package com.aiexam.question.practice.exam.domain;

import java.time.Duration;
import java.util.Optional;

public record ExamTimingRule(
    Duration linkedExamDuration,
    Duration minimumBeforeSubmit,
    Optional<Duration> maximumSubjectDuration,
    boolean inheritLinkedDeadline,
    boolean earlySubmitAllowed
) {
  public ExamTimingRule {
    requirePositive(linkedExamDuration, "Linked exam duration");
    requireNonNegative(minimumBeforeSubmit, "Minimum submit duration");
    if (maximumSubjectDuration == null) {
      throw ExamDomainException.invalidTime("Maximum subject duration option is required");
    }
    if (inheritLinkedDeadline && maximumSubjectDuration.isPresent()) {
      throw ExamDomainException.invalidTime(
          "A linked-deadline subject cannot also define a fixed maximum duration");
    }
    if (!inheritLinkedDeadline && maximumSubjectDuration.isEmpty()) {
      throw ExamDomainException.invalidTime("A fixed-deadline subject requires a maximum duration");
    }
    maximumSubjectDuration.ifPresent(duration -> {
      requirePositive(duration, "Maximum subject duration");
      if (minimumBeforeSubmit.compareTo(duration) > 0) {
        throw ExamDomainException.invalidTime(
            "Minimum submit duration cannot exceed maximum subject duration");
      }
    });
  }

  public static ExamTimingRule fixedSubjectDeadline(
      Duration linkedExamDuration,
      Duration minimumBeforeSubmit,
      Duration maximumSubjectDuration,
      boolean earlySubmitAllowed
  ) {
    return new ExamTimingRule(
        linkedExamDuration,
        minimumBeforeSubmit,
        Optional.ofNullable(maximumSubjectDuration),
        false,
        earlySubmitAllowed);
  }

  public static ExamTimingRule linkedDeadline(
      Duration linkedExamDuration,
      Duration minimumBeforeSubmit,
      boolean earlySubmitAllowed
  ) {
    return new ExamTimingRule(
        linkedExamDuration,
        minimumBeforeSubmit,
        Optional.empty(),
        true,
        earlySubmitAllowed);
  }

  private static void requirePositive(Duration value, String fieldName) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw ExamDomainException.invalidTime(fieldName + " must be positive");
    }
  }

  private static void requireNonNegative(Duration value, String fieldName) {
    if (value == null || value.isNegative()) {
      throw ExamDomainException.invalidTime(fieldName + " cannot be negative");
    }
  }
}
