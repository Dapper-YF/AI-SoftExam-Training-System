package com.aiexam.question.practice.attempt.dto;

import com.aiexam.question.practice.attempt.domain.AttemptStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record PracticeAttemptSubmitResultDto(
    Long attemptId,
    Long questionId,
    AttemptStatus status,
    BigDecimal totalScore,
    BigDecimal maxScore,
    Integer correctCount,
    Integer wrongCount,
    Integer pendingReviewCount,
    long version,
    Instant submittedAt,
    boolean idempotentReplay
) {
}
