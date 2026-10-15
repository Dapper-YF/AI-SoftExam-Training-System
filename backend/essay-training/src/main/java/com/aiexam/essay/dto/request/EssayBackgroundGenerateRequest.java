package com.aiexam.essay.dto.request;

import jakarta.validation.constraints.NotNull;

public record EssayBackgroundGenerateRequest(
    @NotNull Long qualificationId,
    String industry,
    String projectScale,
    String projectCycle,
    String techStack,
    String teamSize
) {
}
