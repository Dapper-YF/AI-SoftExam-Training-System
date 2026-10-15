package com.aiexam.app.learning;

public record LearningKnowledgePointDto(
    Long id,
    Long qualificationId,
    Long parentId,
    String pointCode,
    String pointName,
    String description,
    int levelNo,
    int sortOrder,
    String status
) {}
