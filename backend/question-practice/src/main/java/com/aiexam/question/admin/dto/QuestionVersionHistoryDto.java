package com.aiexam.question.admin.dto;

import java.time.Instant;

public record QuestionVersionHistoryDto(
    long questionId,
    long questionVersionId,
    int versionNo,
    String status,
    long rowVersion,
    Long authorId,
    Long submittedBy,
    Long reviewedBy,
    String reviewDecision,
    Long publishedBy,
    Long retiredBy,
    boolean currentWorking,
    boolean currentPublished,
    boolean deleted,
    Instant createdAt,
    Instant updatedAt
) {
}
