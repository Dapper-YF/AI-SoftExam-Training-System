package com.aiexam.question.practice.exam.domain;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;

public final class ExamTimer {
  private final Clock clock;

  public ExamTimer(Clock clock) {
    if (clock == null) {
      throw ExamDomainException.invalidTime("Clock is required");
    }
    this.clock = clock;
  }

  public ExamTimingWindow openFirstSubject(Instant scheduledStartAt, ExamTimingRule rule) {
    return openNextSubject(scheduledStartAt, scheduledStartAt, rule);
  }

  public ExamTimingWindow openNextSubject(
      Instant linkedStartAt,
      Instant subjectStartAt,
      ExamTimingRule rule
  ) {
    if (linkedStartAt == null || subjectStartAt == null) {
      throw ExamDomainException.invalidTime("Linked and subject start times are required");
    }
    if (rule == null) {
      throw ExamDomainException.invalidTime("Exam timing rule is required");
    }

    Instant linkedDeadlineAt = plus(linkedStartAt, rule.linkedExamDuration());
    Instant forceSubmitAt = rule.inheritLinkedDeadline()
        ? linkedDeadlineAt
        : earlierOf(
            plus(subjectStartAt, rule.maximumSubjectDuration().orElseThrow()),
            linkedDeadlineAt);
    Instant minimumSubmitAt = plus(subjectStartAt, rule.minimumBeforeSubmit());

    return new ExamTimingWindow(
        linkedStartAt,
        subjectStartAt,
        linkedDeadlineAt,
        minimumSubmitAt,
        forceSubmitAt,
        rule.earlySubmitAllowed());
  }

  public ExamTimingSnapshot snapshot(ExamTimingWindow window) {
    if (window == null) {
      throw ExamDomainException.invalidTime("Exam timing window is required");
    }

    Instant serverNow = clock.instant();
    long remainingSeconds = serverNow.isBefore(window.forceSubmitAt())
        ? Duration.between(serverNow, window.forceSubmitAt()).getSeconds()
        : 0;
    boolean forceSubmit = !serverNow.isBefore(window.forceSubmitAt());
    boolean canSubmit = forceSubmit
        || (window.earlySubmitAllowed()
            && !serverNow.isBefore(window.subjectStartAt())
            && !serverNow.isBefore(window.minimumSubmitAt()));

    return new ExamTimingSnapshot(serverNow, remainingSeconds, canSubmit, forceSubmit);
  }

  private Instant plus(Instant instant, Duration duration) {
    try {
      return instant.plus(duration);
    } catch (DateTimeException | ArithmeticException exception) {
      throw ExamDomainException.invalidTime("Exam time exceeds the supported instant range");
    }
  }

  private Instant earlierOf(Instant first, Instant second) {
    return first.isBefore(second) ? first : second;
  }
}
