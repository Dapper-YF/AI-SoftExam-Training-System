package com.aiexam.question.admin.dto;

import java.time.Instant;

public record QuestionImportItemDto(
    long itemId,
    long lineNo,
    String externalKey,
    String contentHash,
    boolean eligible,
    String itemStatus,
    Long questionId,
    Long questionVersionId,
    String errorCode,
    Instant createdAt
) {
}
