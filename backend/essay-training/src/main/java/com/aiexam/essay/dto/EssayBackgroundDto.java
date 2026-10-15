package com.aiexam.essay.dto;

import java.time.Instant;

public record EssayBackgroundDto(
    Long id,
    Long qualificationId,
    String title,
    String industry,
    String projectScale,
    String projectCycle,
    String techStack,
    String teamSize,
    String backgroundContent,
    String sourceType,
    String status,
    Instant createdAt,
    Instant updatedAt
) {
}
