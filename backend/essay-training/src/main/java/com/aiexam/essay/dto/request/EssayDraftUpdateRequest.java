package com.aiexam.essay.dto.request;

public record EssayDraftUpdateRequest(
    Long backgroundId,
    String title,
    String outline,
    String content,
    Integer writingSeconds,
    String status
) {
}
