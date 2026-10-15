package com.aiexam.essay.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EssayBackgroundCreateRequest(
    @NotNull Long qualificationId,
    @NotBlank String title,
    String industry,
    String projectScale,
    String projectCycle,
    String techStack,
    String teamSize,
    @NotBlank String backgroundContent,
    String status
) {
}
