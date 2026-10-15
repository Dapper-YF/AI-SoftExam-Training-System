package com.aiexam.app.learning;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record LearningResourceCreateRequest(
    @NotNull @Positive Long qualificationId,
    @NotBlank @Size(max = 120) String resourceCode,
    @NotNull LearningResourceType resourceType,
    @NotBlank @Size(max = 300) String title,
    @Size(max = 1000) String summary,
    @Size(max = 32) String copyrightStatus,
    Integer sortOrder
) {}
