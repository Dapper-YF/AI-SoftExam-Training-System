package com.aiexam.ai.port;

import java.time.OffsetDateTime;

public record AiTaskRef(
    Long taskId,
    Long userId,
    String bizType,
    Long bizId,
    String scenario,
    String status,
    String requestId,
    String requestHash,
    int retryCount,
    long version,
    OffsetDateTime createdAt
) {
}
