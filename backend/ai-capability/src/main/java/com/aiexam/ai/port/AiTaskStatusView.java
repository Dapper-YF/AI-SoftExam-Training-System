package com.aiexam.ai.port;

import java.time.OffsetDateTime;

public record AiTaskStatusView(
    Long taskId,
    Long userId,
    String bizType,
    Long bizId,
    String scenario,
    String status,
    int retryCount,
    long version,
    OffsetDateTime updatedAt
) {
}
