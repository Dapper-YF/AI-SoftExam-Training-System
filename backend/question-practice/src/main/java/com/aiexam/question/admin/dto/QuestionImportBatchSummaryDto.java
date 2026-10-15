package com.aiexam.question.admin.dto;

import java.time.Instant;

public record QuestionImportBatchSummaryDto(
    Long batchId,
    Long actorUserId,
    String clientMutationId,
    Long paperId,
    String inputPath,
    String inputSha256,
    String sourceFileName,
    String sourceFileSha256,
    String schemaVersion,
    String rightsStatus,
    String usageRestriction,
    String status,
    int totalCount,
    int eligibleCount,
    int importedCount,
    Instant createdAt,
    Instant completedAt,
    Instant rolledBackAt,
    Long reviewedBy,
    Instant reviewedAt,
    String reviewNote,
    Instant publishedAt
) {
}
