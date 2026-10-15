package com.aiexam.essay.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EssayMaterialGenerateRequest(
    @NotNull Long qualificationId,
    Long backgroundId,
    Long categoryId,
    @NotBlank String materialType,
    String title,
    String topic,
    String tags
) {
}
