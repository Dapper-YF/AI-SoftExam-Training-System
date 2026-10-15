package com.aiexam.question.admin.dto;

import com.aiexam.question.dto.request.QuestionCreateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record QuestionVersionWriteRequest(
    @NotNull UUID clientMutationId,
    @Positive Long baseVersionId,
    @NotNull @Valid QuestionCreateRequest payload,
    @Size(max = 32) String copyrightStatus,
    @Size(max = 255) String usageRestriction
) {
  public QuestionVersionWriteRequest(UUID clientMutationId, Long baseVersionId,
      QuestionCreateRequest payload) {
    this(clientMutationId, baseVersionId, payload, null, null);
  }
}
