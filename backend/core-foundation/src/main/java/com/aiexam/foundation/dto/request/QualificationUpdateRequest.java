package com.aiexam.foundation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record QualificationUpdateRequest(
    @NotBlank @Size(max = 96) String qualificationName,
    @NotBlank @Pattern(regexp = "PRIMARY|INTERMEDIATE|ADVANCED") String levelCode,
    @NotBlank @Pattern(regexp = "DRAFT|ENABLED|DISABLED") String status,
    Integer sortOrder
) {
}
