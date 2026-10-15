package com.aiexam.foundation.dto;

public record KnowledgeTagDto(
    Long id,
    Long qualificationId,
    Long subjectId,
    String tagCode,
    String tagName,
    String tagType,
    String status
) {
}
