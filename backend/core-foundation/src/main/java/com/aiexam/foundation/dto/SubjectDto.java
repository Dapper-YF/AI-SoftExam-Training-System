package com.aiexam.foundation.dto;

public record SubjectDto(
    Long id,
    Long qualificationId,
    String subjectCode,
    String subjectName,
    String subjectType,
    Integer sortOrder,
    String status
) {
}
