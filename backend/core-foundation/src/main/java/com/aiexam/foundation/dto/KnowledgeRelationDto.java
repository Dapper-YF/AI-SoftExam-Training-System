package com.aiexam.foundation.dto;

public record KnowledgeRelationDto(
    Long id,
    String sourceType,
    Long sourceId,
    String targetType,
    Long targetId,
    String relationType
) {
}
