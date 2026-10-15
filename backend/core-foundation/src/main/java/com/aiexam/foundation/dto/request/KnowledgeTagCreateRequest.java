package com.aiexam.foundation.dto.request;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeTagCreateRequest(
    @Positive Long qualificationId,
    @Positive Long subjectId,
    @NotBlank @Size(max = 96) String tagCode,
    @NotBlank @Size(max = 128) String tagName,
    @NotBlank @Size(max = 32) String tagType,
    @Size(max = 24) String status
) {
}
