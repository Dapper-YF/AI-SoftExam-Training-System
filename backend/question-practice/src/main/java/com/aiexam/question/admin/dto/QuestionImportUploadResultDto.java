package com.aiexam.question.admin.dto;

import java.util.List;

public record QuestionImportUploadResultDto(
    String token,
    String schemaVersion,
    String status,
    Long batchId,
    Long paperId,
    String inputSha256,
    int totalCount,
    int eligibleCount,
    int ineligibleCount,
    int importedCount,
    int reusedCount,
    int conflictCount,
    boolean replayed,
    String rightsStatus,
    String usageRestriction,
    List<QuestionImportResultDto.Item> items
) {
  public static QuestionImportUploadResultDto from(
      String token,
      String status,
      QuestionImportResultDto result
  ) {
    return new QuestionImportUploadResultDto(token, "question-import/1.0", status,
        result.batchId(), result.paperId(), result.inputSha256(), result.totalCount(),
        result.eligibleCount(), result.ineligibleCount(), result.importedCount(),
        result.reusedCount(), result.conflictCount(), result.replayed(), result.rightsStatus(),
        result.usageRestriction(), result.items());
  }
}
