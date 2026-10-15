package com.aiexam.question.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record QuestionReviewRequest(
    @NotNull UUID clientMutationId,
    @PositiveOrZero long expectedRowVersion,
    @NotBlank String decision,
    @Size(max = 500) String reason
) {
}
