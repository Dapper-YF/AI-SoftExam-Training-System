package com.aiexam.essay.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EssayDraftCreateRequest(
    @NotNull Long qualificationId,
    Long backgroundId,
    @NotBlank String title,
    String outline,
    String content,
    Integer writingSeconds,
    String status
) {
}
