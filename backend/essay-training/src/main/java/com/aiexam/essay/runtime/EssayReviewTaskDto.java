package com.aiexam.essay.runtime;

import java.time.Instant;

public record EssayReviewTaskDto(
    Long reviewTaskId,
    Long userId,
    Long draftId,
    Long aiTaskId,
    String status,
    Instant createdAt
) {
}
