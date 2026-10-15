package com.aiexam.essay.runtime;

import java.time.OffsetDateTime;

public record EssayAiTaskDto(
    Long aiTaskId,
    String bizType,
    Long bizId,
    String scenario,
    String status,
    OffsetDateTime createdAt
) {
}
