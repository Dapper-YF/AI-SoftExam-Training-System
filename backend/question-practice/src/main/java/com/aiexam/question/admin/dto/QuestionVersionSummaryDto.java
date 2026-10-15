package com.aiexam.question.admin.dto;

import java.time.Instant;

public record QuestionVersionSummaryDto(
    long questionId,
    long questionVersionId,
    int versionNo,
    long qualificationId,
    long subjectId,
    String questionType,
    String title,
    Integer yearNo,
    String difficulty,
    String status,
    long rowVersion,
    Long authorId,
    Long submittedBy,
    Long reviewedBy,
    String reviewDecision,
    Long publishedBy,
    Long retiredBy,
    String sourceType,
    String paperKind,
    String copyrightStatus,
    String usageRestriction,
    boolean currentWorking,
    boolean currentPublished,
    Instant updatedAt
) {
}
