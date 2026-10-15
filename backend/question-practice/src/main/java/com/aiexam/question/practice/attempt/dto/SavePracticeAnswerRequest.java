package com.aiexam.question.practice.attempt.dto;

import com.aiexam.question.practice.attempt.domain.PracticeAnswerPayload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SavePracticeAnswerRequest(
    @NotNull UUID clientMutationId,
    @Min(0) long baseVersion,
    @NotNull @Valid PracticeAnswerPayload answerPayload,
    boolean marked,
    @Min(0) int usedSeconds
) {
}
