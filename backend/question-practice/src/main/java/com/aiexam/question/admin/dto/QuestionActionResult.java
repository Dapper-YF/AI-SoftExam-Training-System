package com.aiexam.question.admin.dto;

import java.time.Instant;

public record QuestionActionResult(
    long questionId,
    long questionVersionId,
    int versionNo,
    String status,
    long rowVersion,
    boolean replayed,
    Instant changedAt
) {
  public QuestionActionResult replayedAt(Instant replayedAt) {
    return new QuestionActionResult(questionId, questionVersionId, versionNo, status,
        rowVersion, true, replayedAt);
  }
}
