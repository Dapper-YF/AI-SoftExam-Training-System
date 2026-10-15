package com.aiexam.app.learning;

import java.time.Instant;

public record LearningResourceDto(
    Long id,
    Long qualificationId,
    String resourceCode,
    LearningResourceType resourceType,
    String title,
    String summary,
    LearningResourceStatus status,
    String copyrightStatus,
    Long currentVersionId,
    int sortOrder,
    Instant createdAt,
    Instant updatedAt
) {}
