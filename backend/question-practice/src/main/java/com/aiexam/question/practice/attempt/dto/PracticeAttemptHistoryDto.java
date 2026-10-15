package com.aiexam.question.practice.attempt.dto;

import com.aiexam.question.practice.attempt.domain.AttemptStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record PracticeAttemptHistoryDto(
    Long attemptId,
    AttemptStatus status,
    String sourceType,
    Map<String, Object> sourceSpec,
    int questionCount,
    Instant startedAt,
    Instant submittedAt,
    Integer durationSeconds,
    BigDecimal totalScore,
    BigDecimal maxScore
) {
}
