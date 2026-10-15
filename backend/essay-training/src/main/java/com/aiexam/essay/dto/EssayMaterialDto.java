package com.aiexam.essay.dto;

import java.time.Instant;

public record EssayMaterialDto(
    Long id,
    Long qualificationId,
    Long backgroundId,
    Long categoryId,
    String materialType,
    String title,
    String content,
    String sourceType,
    String status,
    String tags,
    Instant createdAt,
    Instant updatedAt
) {
}
