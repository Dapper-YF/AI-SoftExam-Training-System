package com.aiexam.foundation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record SyllabusNodeCreateRequest(
    @NotNull @Positive Long qualificationId,
    @NotNull @Positive Long subjectId,
    @Positive Long parentId,
    @NotBlank @Size(max = 32) String syllabusVersion,
    @NotBlank @Size(max = 96) String nodeCode,
    @NotBlank @Size(max = 128) String nodeName,
    @NotNull @Positive Integer levelNo,
    @PositiveOrZero Integer sortOrder,
    @Size(max = 24) String status
) {
}
