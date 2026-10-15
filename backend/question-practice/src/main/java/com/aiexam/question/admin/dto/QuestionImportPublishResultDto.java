package com.aiexam.question.admin.dto;

/** Outcome of releasing a structurally validated import batch to student practice. */
public record QuestionImportPublishResultDto(
    long batchId,
    Long paperId,
    int publishedCount,
    String status
) {
}
