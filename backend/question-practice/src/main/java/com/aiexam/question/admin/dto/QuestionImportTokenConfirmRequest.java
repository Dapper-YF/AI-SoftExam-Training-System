package com.aiexam.question.admin.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record QuestionImportTokenConfirmRequest(
    @NotNull UUID clientMutationId,
    Long baseVersion
) {
}
