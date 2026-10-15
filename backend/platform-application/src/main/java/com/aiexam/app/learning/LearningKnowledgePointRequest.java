package com.aiexam.app.learning;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record LearningKnowledgePointRequest(
    @NotNull @Positive Long qualificationId,
    @Size(max = 120) String parentPointCode,
    @NotBlank @Size(max = 120) String pointCode,
    @NotBlank @Size(max = 300) String pointName,
    @Size(max = 2000) String description,
    @Positive int levelNo,
    int sortOrder
) {}
