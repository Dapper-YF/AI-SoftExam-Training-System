package com.aiexam.question.practice.attempt.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SubmitPracticeAttemptRequest(@NotNull UUID clientMutationId, @Min(0) long baseVersion) {
}
