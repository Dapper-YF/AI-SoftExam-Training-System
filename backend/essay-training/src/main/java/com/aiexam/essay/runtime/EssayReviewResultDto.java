package com.aiexam.essay.runtime;

import java.util.List;

public record EssayReviewResultDto(
    Long resultId,
    Long reviewTaskId,
    Integer totalScore,
    String summary,
    List<EssayReviewIssueDto> issues
) {
  public EssayReviewResultDto {
    issues = issues == null ? List.of() : List.copyOf(issues);
  }
}
