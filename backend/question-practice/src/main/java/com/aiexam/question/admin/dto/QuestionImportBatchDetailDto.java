package com.aiexam.question.admin.dto;

import java.util.List;

public record QuestionImportBatchDetailDto(
    QuestionImportBatchSummaryDto batch,
    List<QuestionImportItemDto> items
) {
  public QuestionImportBatchDetailDto {
    items = List.copyOf(items);
  }
}
