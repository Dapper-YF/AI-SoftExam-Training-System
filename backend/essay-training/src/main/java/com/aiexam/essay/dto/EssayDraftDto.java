package com.aiexam.essay.dto;

import java.time.Instant;
import java.util.List;

public record EssayDraftDto(
    Long id,
    Long qualificationId,
    Long backgroundId,
    String title,
    String outline,
    String content,
    int wordCount,
    Integer writingSeconds,
    String status,
    List<EssayMaterialDto> materials,
    Instant createdAt,
    Instant updatedAt
) {
}
