package com.aiexam.question.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record QuestionImportRequest(
    @NotBlank String inputPath,
    UUID clientMutationId
) {
  public record Rollback(@NotNull UUID clientMutationId) {
  }

  public record Publish(@NotNull UUID clientMutationId) {
  }

  public record Reject(
      @NotNull UUID clientMutationId,
      @jakarta.validation.constraints.NotBlank
      @jakarta.validation.constraints.Size(max = 1000)
      String reason
  ) {
  }
}
