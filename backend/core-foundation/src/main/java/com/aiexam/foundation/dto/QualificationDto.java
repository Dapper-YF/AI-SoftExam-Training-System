package com.aiexam.foundation.dto;

public record QualificationDto(
    Long id,
    String qualificationCode,
    String qualificationName,
    String levelCode,
    String status,
    Integer sortOrder
) {
}
