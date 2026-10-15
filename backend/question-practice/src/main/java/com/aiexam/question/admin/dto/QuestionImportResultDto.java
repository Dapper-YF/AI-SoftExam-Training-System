package com.aiexam.question.admin.dto;

import java.util.List;

public record QuestionImportResultDto(
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
    List<Item> items
) {
  public record Item(int lineNo, String externalKey, boolean eligible, String errorCode,
                     Long questionId, Long questionVersionId) {
  }

  public QuestionImportResultDto asReplay() {
    return new QuestionImportResultDto(batchId, paperId, inputSha256, totalCount, eligibleCount,
        ineligibleCount, importedCount, reusedCount, conflictCount, true, rightsStatus,
        usageRestriction, items);
  }
}
