package com.aiexam.app.learning;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LearningResourceUpdateRequest(
    @NotBlank @Size(max = 300) String title,
    @Size(max = 1000) String summary,
    @Size(max = 32) String copyrightStatus,
    Integer sortOrder
) {}
