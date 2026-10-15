package com.aiexam.question.practice.exam.domain;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

public record ExamSubmissionContext(
    String submissionKey,
    ExamSubmissionMode mode,
    Instant initiatedAt
) {
  private static final Pattern FORCED_KEY_PATTERN = Pattern.compile("AUTO:[1-9]\\d*:\\d+");

  public ExamSubmissionContext {
    if (submissionKey == null || submissionKey.isBlank() || mode == null || initiatedAt == null) {
      throw new ExamDomainException(
          ExamDomainErrorCode.INVALID_SUBMISSION_CONTEXT,
          "Submission key, mode and initiation time are required");
    }
    submissionKey = submissionKey.trim();
    if (!isLegalKey(submissionKey, mode)) {
      throw new ExamDomainException(
          ExamDomainErrorCode.INVALID_SUBMISSION_CONTEXT,
          "Manual submission keys must be UUIDs and forced keys must use AUTO:{subjectId}:{epoch}");
    }
  }

  private static boolean isLegalKey(String submissionKey, ExamSubmissionMode mode) {
    if (mode == ExamSubmissionMode.FORCED) {
      return FORCED_KEY_PATTERN.matcher(submissionKey).matches();
    }
    try {
      return UUID.fromString(submissionKey).toString().equalsIgnoreCase(submissionKey);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }
}
