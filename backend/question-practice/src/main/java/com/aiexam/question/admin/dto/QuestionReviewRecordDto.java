package com.aiexam.question.admin.dto;

import java.time.Instant;

public record QuestionReviewRecordDto(
    long reviewId,
    long questionId,
    long questionVersionId,
    long submittedBy,
    long reviewerId,
    String decision,
    String reason,
    Instant createdAt
) {
}
