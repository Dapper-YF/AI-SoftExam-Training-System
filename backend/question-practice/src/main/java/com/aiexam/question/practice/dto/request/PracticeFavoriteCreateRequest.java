package com.aiexam.question.practice.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PracticeFavoriteCreateRequest(
    @NotNull @Positive Long questionId,
    @Size(max = 96) String favoriteGroup
) {
}
