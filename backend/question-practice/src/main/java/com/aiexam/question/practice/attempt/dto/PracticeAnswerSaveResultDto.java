package com.aiexam.question.practice.attempt.dto;

import java.time.Instant;

public record PracticeAnswerSaveResultDto(
    Long attemptId,
    Long subQuestionId,
    long answerVersion,
    Instant savedAt,
    boolean idempotentReplay
) {
}
