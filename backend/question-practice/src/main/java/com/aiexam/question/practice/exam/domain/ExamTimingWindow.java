package com.aiexam.question.practice.exam.domain;

import java.time.Instant;

public record ExamTimingWindow(
    Instant linkedStartAt,
    Instant subjectStartAt,
    Instant linkedDeadlineAt,
    Instant minimumSubmitAt,
    Instant forceSubmitAt,
    boolean earlySubmitAllowed
) {
  public ExamTimingWindow {
    if (linkedStartAt == null
        || subjectStartAt == null
        || linkedDeadlineAt == null
        || minimumSubmitAt == null
        || forceSubmitAt == null) {
      throw ExamDomainException.invalidTime("All exam timing window instants are required");
    }
    if (subjectStartAt.isBefore(linkedStartAt)) {
      throw ExamDomainException.invalidTime("Subject start cannot precede linked exam start");
    }
    if (linkedDeadlineAt.isBefore(linkedStartAt)) {
      throw ExamDomainException.invalidTime("Linked deadline cannot precede linked exam start");
    }
    if (forceSubmitAt.isBefore(subjectStartAt)) {
      throw ExamDomainException.invalidTime("Force-submit deadline cannot precede subject start");
    }
    if (forceSubmitAt.isAfter(linkedDeadlineAt)) {
      throw ExamDomainException.invalidTime("Force-submit deadline cannot exceed linked deadline");
    }
    if (minimumSubmitAt.isBefore(subjectStartAt) || minimumSubmitAt.isAfter(forceSubmitAt)) {
      throw ExamDomainException.invalidTime(
          "Minimum-submit time must be within the subject timing window");
    }
  }
}
