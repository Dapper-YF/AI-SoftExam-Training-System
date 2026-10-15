package com.aiexam.essay.dto.request;

public record EssayBackgroundUpdateRequest(
    String title,
    String industry,
    String projectScale,
    String projectCycle,
    String techStack,
    String teamSize,
    String backgroundContent,
    String status
) {
}
