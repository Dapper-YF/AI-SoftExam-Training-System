package com.aiexam.question.practice.dto;

import java.time.Instant;

public record PracticeFavoriteDto(
    Long id,
    Long questionId,
    Long qualificationId,
    Long subjectId,
    String questionTitle,
    String favoriteGroup,
    Instant createdAt
) {
}
