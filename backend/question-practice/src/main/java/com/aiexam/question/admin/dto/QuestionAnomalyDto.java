package com.aiexam.question.admin.dto;

import java.time.Instant;
public record QuestionAnomalyDto(long anomalyId, long questionId, long questionVersionId,
    String questionType, String title, String anomalyType, String status,
    Integer originalQuestionNo, Instant updatedAt) {
}
