package com.aiexam.app.learning;

public record LearningKnowledgeGraphEdgeDto(
    Long id,
    Long qualificationId,
    Long sourcePointId,
    String sourcePointName,
    Long targetPointId,
    String targetPointName,
    String relationType
) {}
