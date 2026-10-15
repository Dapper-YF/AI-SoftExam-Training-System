package com.aiexam.question.practice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PracticeNoteCreateRequest(
    @NotBlank String targetType,
    @NotNull @Positive Long targetId,
    @Positive Long qualificationId,
    @Positive Long subjectId,
    @NotBlank String noteContent
) {
}
