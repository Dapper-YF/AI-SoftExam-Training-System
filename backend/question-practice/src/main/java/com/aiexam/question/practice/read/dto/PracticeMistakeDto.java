package com.aiexam.question.practice.read.dto;

import java.time.Instant;

public record PracticeMistakeDto(
    Long questionId,
    Long qualificationId,
    Long subjectId,
    String questionType,
    String questionTitle,
    Long lastAttemptId,
    int mistakeCount,
    Instant lastWrongAt,
    String status
) {
}
