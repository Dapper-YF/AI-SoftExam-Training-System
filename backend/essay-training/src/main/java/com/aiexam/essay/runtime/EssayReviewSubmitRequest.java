package com.aiexam.essay.runtime;

public record EssayReviewSubmitRequest(
    String requestId,
    Long draftId,
    Long qualificationId,
    Long rubricId,
    String content
) {
  public EssayReviewSubmitRequest(Long draftId, Long qualificationId, Long rubricId, String content) {
    this("review-" + draftId, draftId, qualificationId, rubricId, content);
  }
}
