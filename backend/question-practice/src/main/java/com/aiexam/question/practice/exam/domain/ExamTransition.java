package com.aiexam.question.practice.exam.domain;

public record ExamTransition(
    ExamSessionState previousState,
    ExamSessionState currentState,
    boolean changed
) {
}
