package com.aiexam.question.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record QuestionWorkflowRequest(
    @NotNull UUID clientMutationId,
    @PositiveOrZero long expectedRowVersion,
    @Size(max = 500) String reason
) {
}
