package com.aiexam.essay.runtime;

public record EssayAiCapabilityStatusDto(
    boolean enabled,
    String outlineGenerate,
    String paragraphGenerate,
    String review,
    String issueQuestion
) {
}
