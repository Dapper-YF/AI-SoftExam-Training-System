package com.aiexam.question.practice.dto;

import java.time.Instant;

public record PracticeNoteDto(
    Long id,
    String targetType,
    Long targetId,
    Long qualificationId,
    Long subjectId,
    String noteContent,
    Instant createdAt,
    Instant updatedAt
) {
}
