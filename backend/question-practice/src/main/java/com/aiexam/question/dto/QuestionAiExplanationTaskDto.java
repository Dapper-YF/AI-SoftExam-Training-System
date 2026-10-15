package com.aiexam.question.dto;

import java.time.OffsetDateTime;

public record QuestionAiExplanationTaskDto(
    Long taskId,
    String bizType,
    Long bizId,
    String scenario,
    String status,
    int retryCount,
    OffsetDateTime createdAt
) {
}
