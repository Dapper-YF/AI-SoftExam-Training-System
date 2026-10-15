package com.aiexam.question.practice.dto;

public record PracticeFavoriteDeleteDto(
    Long questionId,
    boolean removed
) {
}
