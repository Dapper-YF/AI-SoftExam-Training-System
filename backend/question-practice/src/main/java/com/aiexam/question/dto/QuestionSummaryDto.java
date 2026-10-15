package com.aiexam.question.dto;

public record QuestionSummaryDto(
    Long questionId,
    Long qualificationId,
    Long subjectId,
    String questionType,
    String title,
    Integer yearNo,
    String difficulty,
    String status,
    Integer contentVersion
) {
}
