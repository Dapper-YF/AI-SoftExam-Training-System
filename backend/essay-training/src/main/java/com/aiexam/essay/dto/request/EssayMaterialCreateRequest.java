package com.aiexam.essay.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EssayMaterialCreateRequest(
    @NotNull Long qualificationId,
    Long backgroundId,
    Long categoryId,
    @NotBlank String materialType,
    @NotBlank String title,
    @NotBlank String content,
    String status,
    String tags
) {
}
