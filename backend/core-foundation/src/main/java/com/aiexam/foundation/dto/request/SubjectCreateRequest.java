package com.aiexam.foundation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SubjectCreateRequest(
    @NotNull @Positive Long qualificationId,
    @NotBlank @Size(max = 64) String subjectCode,
    @NotBlank @Size(max = 96) String subjectName,
    @NotBlank @Size(max = 96) String officialName,
    @NotBlank @Pattern(regexp = "COMPREHENSIVE|CASE|ESSAY|APPLICATION") String subjectType,
    Integer sortOrder,
    @Pattern(regexp = "DRAFT|ENABLED|DISABLED") String status
) {
}
