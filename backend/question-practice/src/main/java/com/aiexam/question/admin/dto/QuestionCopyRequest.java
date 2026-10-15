package com.aiexam.question.admin.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record QuestionCopyRequest(
    @NotNull UUID clientMutationId
) {
}
