package com.aiexam.question.practice.exam.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ExamTimerTest {
  private static final Instant START = Instant.parse("2026-05-23T01:00:00Z");

  @Test
  void comprehensiveKnowledgeUsesConfigured120_150_240MinuteRules() {
    ExamTimingWindow window = timerAt(START).openFirstSubject(START, comprehensiveRule());

    assertThat(window.linkedDeadlineAt()).isEqualTo(START.plus(Duration.ofMinutes(240)));
    assertThat(window.minimumSubmitAt()).isEqualTo(START.plus(Duration.ofMinutes(120)));
    assertThat(window.forceSubmitAt()).isEqualTo(START.plus(Duration.ofMinutes(150)));
  }

  @Test
  void remainingSecondsAlwaysUsesDeadlineMinusServerNow() {
    ExamTimingWindow window = timerAt(START).openFirstSubject(START, comprehensiveRule());

    ExamTimingSnapshot before = timerAt(START.minusSeconds(1)).snapshot(window);
    ExamTimingSnapshot atStart = timerAt(START).snapshot(window);

    assertThat(before.serverNow()).isEqualTo(START.minusSeconds(1));
    assertThat(before.remainingSeconds()).isEqualTo(Duration.ofMinutes(150).toSeconds() + 1);
    assertThat(before.canSubmit()).isFalse();
    assertThat(before.forceSubmit()).isFalse();
    assertThat(atStart.remainingSeconds()).isEqualTo(Duration.ofMinutes(150).toSeconds());
    assertThat(atStart.canSubmit()).isFalse();
  }

  @Test
  void comprehensiveKnowledgeCannotSubmitBefore120MinutesButCanAtBoundary() {
    ExamTimingWindow window = timerAt(START).openFirstSubject(START, comprehensiveRule());

    ExamTimingSnapshot before = timerAt(START.plus(Duration.ofMinutes(120)).minusSeconds(1)).snapshot(window);
    ExamTimingSnapshot atBoundary = timerAt(START.plus(Duration.ofMinutes(120))).snapshot(window);

    assertThat(before.canSubmit()).isFalse();
    assertThat(atBoundary.canSubmit()).isTrue();
    assertThat(atBoundary.forceSubmit()).isFalse();
    assertThat(atBoundary.remainingSeconds()).isEqualTo(Duration.ofMinutes(30).toSeconds());
  }

  @Test
  void comprehensiveKnowledgeForcesSubmissionAt150MinutesAndNeverReturnsNegativeRemainingTime() {
    ExamTimingWindow window = timerAt(START).openFirstSubject(START, comprehensiveRule());

    ExamTimingSnapshot justBefore = timerAt(START.plus(Duration.ofMinutes(150)).minusSeconds(1)).snapshot(window);
    ExamTimingSnapshot atDeadline = timerAt(START.plus(Duration.ofMinutes(150))).snapshot(window);
    ExamTimingSnapshot afterDeadline = timerAt(START.plus(Duration.ofMinutes(151))).snapshot(window);

    assertThat(justBefore.remainingSeconds()).isEqualTo(1);
    assertThat(justBefore.forceSubmit()).isFalse();
    assertThat(atDeadline.remainingSeconds()).isZero();
    assertThat(atDeadline.forceSubmit()).isTrue();
    assertThat(afterDeadline.remainingSeconds()).isZero();
    assertThat(afterDeadline.forceSubmit()).isTrue();
  }

  @Test
  void caseAnalysisInheritsRemainingLinkedExamTimeInsteadOfResettingDuration() {
    Instant comprehensiveSubmittedAt = START.plus(Duration.ofMinutes(130));
    ExamTimingWindow caseWindow = timerAt(comprehensiveSubmittedAt)
        .openNextSubject(START, comprehensiveSubmittedAt, caseRule());

    ExamTimingSnapshot snapshot = timerAt(comprehensiveSubmittedAt).snapshot(caseWindow);

    assertThat(caseWindow.linkedDeadlineAt()).isEqualTo(START.plus(Duration.ofMinutes(240)));
    assertThat(caseWindow.forceSubmitAt()).isEqualTo(START.plus(Duration.ofMinutes(240)));
    assertThat(snapshot.remainingSeconds()).isEqualTo(Duration.ofMinutes(110).toSeconds());
    assertThat(snapshot.canSubmit()).isTrue();
  }

  @Test
  void caseAnalysisIsForcedAtTheLinkedExamDeadline() {
    Instant caseStartedAt = START.plus(Duration.ofMinutes(150));
    ExamTimingWindow caseWindow = timerAt(caseStartedAt)
        .openNextSubject(START, caseStartedAt, caseRule());

    ExamTimingSnapshot justBefore = timerAt(START.plus(Duration.ofMinutes(240)).minusSeconds(1)).snapshot(caseWindow);
    ExamTimingSnapshot atDeadline = timerAt(START.plus(Duration.ofMinutes(240))).snapshot(caseWindow);

    assertThat(justBefore.remainingSeconds()).isEqualTo(1);
    assertThat(justBefore.forceSubmit()).isFalse();
    assertThat(atDeadline.remainingSeconds()).isZero();
    assertThat(atDeadline.forceSubmit()).isTrue();
  }

  @Test
  void essayUsesIndependent120MinutesAndCannotSubmitEarly() {
    ExamTimingWindow essayWindow = timerAt(START).openFirstSubject(START, essayRule());

    ExamTimingSnapshot justBefore = timerAt(START.plus(Duration.ofMinutes(120)).minusSeconds(1)).snapshot(essayWindow);
    ExamTimingSnapshot atDeadline = timerAt(START.plus(Duration.ofMinutes(120))).snapshot(essayWindow);

    assertThat(essayWindow.linkedDeadlineAt()).isEqualTo(START.plus(Duration.ofMinutes(120)));
    assertThat(justBefore.canSubmit()).isFalse();
    assertThat(justBefore.forceSubmit()).isFalse();
    assertThat(atDeadline.canSubmit()).isTrue();
    assertThat(atDeadline.forceSubmit()).isTrue();
  }

  @Test
  void refreshRecalculatesFromClockWithoutMutatingTheWindow() {
    ExamTimingWindow window = timerAt(START).openFirstSubject(START, comprehensiveRule());

    ExamTimingSnapshot firstRefresh = timerAt(START.plus(Duration.ofMinutes(10))).snapshot(window);
    ExamTimingSnapshot secondRefresh = timerAt(START.plus(Duration.ofMinutes(20))).snapshot(window);

    assertThat(firstRefresh.remainingSeconds()).isEqualTo(Duration.ofMinutes(140).toSeconds());
    assertThat(secondRefresh.remainingSeconds()).isEqualTo(Duration.ofMinutes(130).toSeconds());
    assertThat(window.forceSubmitAt()).isEqualTo(START.plus(Duration.ofMinutes(150)));
  }

  @Test
  void rejectsInvalidDurations() {
    assertInvalidTime(() -> ExamTimingRule.fixedSubjectDeadline(
        Duration.ZERO, Duration.ZERO, Duration.ofMinutes(1), true));
    assertInvalidTime(() -> ExamTimingRule.fixedSubjectDeadline(
        Duration.ofMinutes(10), Duration.ofSeconds(-1), Duration.ofMinutes(1), true));
    assertInvalidTime(() -> ExamTimingRule.fixedSubjectDeadline(
        Duration.ofMinutes(10), Duration.ofMinutes(2), Duration.ofMinutes(1), true));
    assertInvalidTime(() -> ExamTimingRule.linkedDeadline(
        Duration.ofMinutes(-1), Duration.ZERO, true));
    assertInvalidTime(() -> new ExamTimingRule(
        Duration.ofMinutes(10), Duration.ZERO, null, true, true));
  }

  @Test
  void rejectsNullTimesAndDeadlineBeforeSubjectStart() {
    assertInvalidTime(() -> timerAt(START).openFirstSubject(null, comprehensiveRule()));
    assertInvalidTime(() -> timerAt(START).openNextSubject(START, null, caseRule()));
    assertInvalidTime(() -> timerAt(START).openNextSubject(
        START,
        START.plus(Duration.ofMinutes(241)),
        caseRule()));
    assertInvalidTime(() -> new ExamTimingWindow(
        START,
        START.plusSeconds(10),
        START.plusSeconds(20),
        START.plusSeconds(10),
        START.plusSeconds(9),
        true));
  }

  @Test
  void timerRequiresInjectedClockAndWindow() {
    assertInvalidTime(() -> new ExamTimer(null));
    assertInvalidTime(() -> timerAt(START).snapshot(null));
  }

  private ExamTimer timerAt(Instant instant) {
    return new ExamTimer(Clock.fixed(instant, ZoneOffset.UTC));
  }

  private ExamTimingRule comprehensiveRule() {
    return ExamTimingRule.fixedSubjectDeadline(
        Duration.ofMinutes(240),
        Duration.ofMinutes(120),
        Duration.ofMinutes(150),
        true);
  }

  private ExamTimingRule caseRule() {
    return ExamTimingRule.linkedDeadline(
        Duration.ofMinutes(240),
        Duration.ZERO,
        true);
  }

  private ExamTimingRule essayRule() {
    return ExamTimingRule.fixedSubjectDeadline(
        Duration.ofMinutes(120),
        Duration.ofMinutes(120),
        Duration.ofMinutes(120),
        false);
  }

  private void assertInvalidTime(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.INVALID_TIME_CONFIGURATION);
  }
}
