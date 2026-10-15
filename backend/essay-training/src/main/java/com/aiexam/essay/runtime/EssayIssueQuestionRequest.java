package com.aiexam.essay.runtime;

public record EssayIssueQuestionRequest(
    String requestId,
    Long reviewTaskId,
    Long resultId,
    String issueId,
    String question
) {
  public EssayIssueQuestionRequest(Long reviewTaskId, Long resultId, String issueId, String question) {
    this("issue-" + reviewTaskId + "-" + issueId, reviewTaskId, resultId, issueId, question);
  }
}
