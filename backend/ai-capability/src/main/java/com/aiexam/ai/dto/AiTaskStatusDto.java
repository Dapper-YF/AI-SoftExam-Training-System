package com.aiexam.ai.dto;

import java.time.OffsetDateTime;

public record AiTaskStatusDto(
    Long id,
    String bizType,
    Long bizId,
    String scenario,
    String status,
    int retryCount,
    OffsetDateTime createdAt
) {
}
