package com.aiexam.foundation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record KnowledgeRelationCreateRequest(
    @NotBlank @Size(max = 32) String sourceType,
    @NotNull @Positive Long sourceId,
    @NotBlank @Size(max = 32) String targetType,
    @NotNull @Positive Long targetId,
    @NotBlank @Size(max = 32) String relationType
) {
}
