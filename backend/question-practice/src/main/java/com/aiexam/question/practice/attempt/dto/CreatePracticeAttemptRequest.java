package com.aiexam.question.practice.attempt.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record CreatePracticeAttemptRequest(
    @NotNull UUID clientMutationId,
    @NotNull @Min(1) Long qualificationId,
    @Min(1) Long subjectId,
    @NotBlank String mode,
    @NotBlank String feedbackMode,
    @Min(1) @Max(100) Integer count,
    @NotNull Source source
) {
  public record Source(String type, String knowledgeType, List<Long> knowledgeIds, Integer yearNo, Long paperId) {
  }
}
