package com.aiexam.question.admin.dto;

import java.util.List;

public record QuestionAdminDashboardDto(
    long totalCount,
    long draftCount,
    long inReviewCount,
    long publishedCount,
    long retiredCount,
    List<QualificationCount> byQualification,
    List<QuestionTypeCount> byQuestionType,
    List<QuestionImportBatchSummaryDto> recentImports,
    List<QuestionVersionSummaryDto> pendingReviews
) {
  public QuestionAdminDashboardDto {
    byQualification = List.copyOf(byQualification);
    byQuestionType = List.copyOf(byQuestionType);
    recentImports = List.copyOf(recentImports);
    pendingReviews = List.copyOf(pendingReviews);
  }

  public record QualificationCount(long qualificationId, long count) {
  }

  public record QuestionTypeCount(String questionType, long count) {
  }
}
