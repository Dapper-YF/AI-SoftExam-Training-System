package com.aiexam.question.practice.exam.domain;

public final class ExamDomainException extends RuntimeException {
  private final ExamDomainErrorCode code;

  public ExamDomainException(ExamDomainErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public ExamDomainErrorCode code() {
    return code;
  }

  static ExamDomainException invalidTime(String message) {
    return new ExamDomainException(ExamDomainErrorCode.INVALID_TIME_CONFIGURATION, message);
  }
}
