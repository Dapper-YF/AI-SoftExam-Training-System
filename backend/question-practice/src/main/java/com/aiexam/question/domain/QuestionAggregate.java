package com.aiexam.question.domain;

public record QuestionAggregate(
    Long questionId,
    String questionType,
    int contentVersion
) {
}
