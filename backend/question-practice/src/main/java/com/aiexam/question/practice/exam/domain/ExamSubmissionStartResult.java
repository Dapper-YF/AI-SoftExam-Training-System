package com.aiexam.question.practice.exam.domain;

public record ExamSubmissionStartResult(
    ExamTransition transition,
    ExamSubmissionContext activeContext,
    boolean reservationCreated
) {
  public ExamSubmissionStartResult {
    if (transition == null || activeContext == null) {
      throw new ExamDomainException(
          ExamDomainErrorCode.INVALID_SUBMISSION_CONTEXT,
          "Submission start result requires a transition and active context");
    }
  }

  public ExamSubmissionMode activeMode() {
    return activeContext.mode();
  }
}
