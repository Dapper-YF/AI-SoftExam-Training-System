package com.aiexam.question.practice.attempt.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateCaseAttemptRequest(
    @NotNull UUID clientMutationId,
    @NotNull @Min(1) Long qualificationId,
    @NotNull @Min(1) Long subjectId,
    @NotNull @Min(1) Long questionId
) {
}
