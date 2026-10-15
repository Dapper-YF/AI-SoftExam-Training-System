package com.aiexam.question.admin.dto;

import com.aiexam.question.dto.request.QuestionCreateRequest;
import java.time.Instant;

public record QuestionVersionDetailDto(
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
    String contentHash,
    String sourceFile,
    String sourceSha256,
    String sourceLocator,
    Integer originalQuestionNo,
    String paperKind,
    String copyrightStatus,
    String usageRestriction,
    Instant createdAt,
    Instant updatedAt,
    QuestionCreateRequest payload
) {
}
