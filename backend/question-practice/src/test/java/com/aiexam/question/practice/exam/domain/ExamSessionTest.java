package com.aiexam.question.practice.exam.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ExamSessionTest {
  private static final Instant START = Instant.parse("2026-05-23T01:00:00Z");
  private static final String MANUAL_SUBMISSION_KEY = "11111111-1111-1111-1111-111111111111";
  private static final String FORCED_SUBMISSION_KEY = "AUTO:1001:1779507000";

  @Test
  void followsTheCompleteLegalMainStateFlow() {
    MutableClock clock = clockAt(START);
    ExamSession session = newSession(comprehensiveRule(), clock);

    assertThat(session.state()).isEqualTo(ExamSessionState.CREATED);
    assertChanged(session.candidateLoggedIn(), ExamSessionState.CANDIDATE_LOGGED_IN);
    assertChanged(session.confirmCandidate(), ExamSessionState.CANDIDATE_CONFIRMED);
    assertChanged(session.beginRuleReading(), ExamSessionState.RULE_READING);
    assertChanged(session.acknowledgeRules(), ExamSessionState.WAITING_START);
    assertChanged(session.beginPaperLoading(), ExamSessionState.LOADING_PAPER);
    assertChanged(session.startExam(), ExamSessionState.IN_PROGRESS);
    clock.advance(Duration.ofMinutes(120));
    ExamSubmissionContext context = manualContext(clock);
    assertChanged(session.beginSubmitting(context).transition(), ExamSessionState.SUBMITTING);
    assertChanged(session.markSubmitted(context), ExamSessionState.SUBMITTED);
    assertChanged(
        session.completeFinalSubject(noNextSubjectFact()),
        ExamSessionState.COMPLETED);
  }

  @Test
  void repeatedCommandForTheSameTargetStateIsAcceptedIdempotently() {
    ExamSession session = newSession(comprehensiveRule(), clockAt(START));

    ExamTransition first = session.candidateLoggedIn();
    ExamTransition repeated = session.candidateLoggedIn();

    assertThat(first.changed()).isTrue();
    assertThat(repeated.changed()).isFalse();
    assertThat(repeated.previousState()).isEqualTo(ExamSessionState.CANDIDATE_LOGGED_IN);
    assertThat(repeated.currentState()).isEqualTo(ExamSessionState.CANDIDATE_LOGGED_IN);
  }

  @Test
  void everyMainFlowCommandIsIdempotentAfterReachingItsTarget() {
    MutableClock clock = clockAt(START);
    ExamSession session = newSession(comprehensiveRule(), clock);

    assertRepeatedCommandIsIdempotent(session.candidateLoggedIn(), session.candidateLoggedIn());
    assertRepeatedCommandIsIdempotent(session.confirmCandidate(), session.confirmCandidate());
    assertRepeatedCommandIsIdempotent(session.beginRuleReading(), session.beginRuleReading());
    assertRepeatedCommandIsIdempotent(session.acknowledgeRules(), session.acknowledgeRules());
    assertRepeatedCommandIsIdempotent(session.beginPaperLoading(), session.beginPaperLoading());
    assertRepeatedCommandIsIdempotent(session.startExam(), session.startExam());
    clock.advance(Duration.ofMinutes(120));
    ExamSubmissionContext context = manualContext(clock);
    assertRepeatedSubmissionStartIsIdempotent(
        session.beginSubmitting(context),
        session.beginSubmitting(context));
    assertRepeatedCommandIsIdempotent(
        session.markSubmitted(context),
        session.markSubmitted(context));
    assertRepeatedCommandIsIdempotent(
        session.completeFinalSubject(noNextSubjectFact()),
        session.completeFinalSubject(noNextSubjectFact()));
  }

  @Test
  void cannotSkipCandidateConfirmation() {
    ExamSession session = newSession(comprehensiveRule(), clockAt(START));
    session.candidateLoggedIn();

    assertInvalidTransition(session::beginRuleReading);
    assertThat(session.state()).isEqualTo(ExamSessionState.CANDIDATE_LOGGED_IN);
  }

  @Test
  void cannotLoadPaperBeforeRulesAreAcknowledged() {
    ExamSession session = newSession(comprehensiveRule(), clockAt(START));
    session.candidateLoggedIn();
    session.confirmCandidate();
    session.beginRuleReading();

    assertThatThrownBy(session::beginPaperLoading)
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.RULES_NOT_ACKNOWLEDGED);
    assertThat(session.state()).isEqualTo(ExamSessionState.RULE_READING);
  }

  @Test
  void cannotStartExamWithoutLoadingPaper() {
    ExamSession session = readyAndWaiting(comprehensiveRule(), clockAt(START));

    assertInvalidTransition(session::startExam);
    assertThat(session.state()).isEqualTo(ExamSessionState.WAITING_START);
  }

  @Test
  void cannotStartExamBeforeScheduledStart() {
    MutableClock beforeStart = clockAt(START.minusSeconds(1));
    ExamSession session = readyAndWaiting(comprehensiveRule(), beforeStart);
    session.beginPaperLoading();

    assertThatThrownBy(session::startExam)
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.EXAM_NOT_STARTED);
    assertThat(session.state()).isEqualTo(ExamSessionState.LOADING_PAPER);
  }

  @Test
  void submittedSessionCannotReenterInProgress() {
    MutableClock clock = clockAt(START);
    ExamSession session = inProgress(comprehensiveRule(), clock);
    clock.advance(Duration.ofMinutes(120));
    ExamSubmissionContext context = manualContext(clock);
    session.beginSubmitting(context);
    session.markSubmitted(context);

    assertInvalidTransition(session::startExam);
    assertThat(session.state()).isEqualTo(ExamSessionState.SUBMITTED);
  }

  @Test
  void completedSessionCannotReenterInProgress() {
    MutableClock clock = clockAt(START);
    ExamSession session = inProgress(comprehensiveRule(), clock);
    clock.advance(Duration.ofMinutes(120));
    ExamSubmissionContext context = manualContext(clock);
    session.beginSubmitting(context);
    session.markSubmitted(context);
    session.completeFinalSubject(noNextSubjectFact());

    assertThatThrownBy(session::startExam)
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.SESSION_COMPLETED_CANNOT_RESUME);
    assertThat(session.state()).isEqualTo(ExamSessionState.COMPLETED);
  }

  @Test
  void exceptionalResultsDoNotBecomeNormalSessionStates() {
    ExamSession session = readyAndWaiting(comprehensiveRule(), clockAt(START));
    session.beginPaperLoading();

    ExamExceptionalResult result = session.recordExceptionalResult(
        ExamExceptionalResultCode.PAPER_LOAD_FAILED);

    assertThat(session.state()).isEqualTo(ExamSessionState.LOADING_PAPER);
    assertThat(result.code()).isEqualTo(ExamExceptionalResultCode.PAPER_LOAD_FAILED);
    assertThat(result.occurredAt()).isEqualTo(START);
    assertThat(session.exceptionalResults()).containsExactly(result);

    ExamExceptionalResult degraded = session.recordExceptionalResult(
        ExamExceptionalResultCode.SAVE_DEGRADED);
    assertThat(degraded.code()).isEqualTo(ExamExceptionalResultCode.SAVE_DEGRADED);
  }

  @Test
  void sessionRequiresTimingWindowAndClock() {
    MutableClock clock = clockAt(START);
    ExamTimingWindow window = timingWindow(comprehensiveRule(), clock);

    assertThatThrownBy(() -> ExamSession.create(null, clock))
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.INVALID_TIME_CONFIGURATION);
    assertThatThrownBy(() -> ExamSession.create(window, null))
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.INVALID_TIME_CONFIGURATION);
  }

  @Test
  void comprehensiveKnowledgeCannotBeginManualSubmissionAtExamStart() {
    MutableClock clock = clockAt(START);
    ExamSession session = inProgress(comprehensiveRule(), clock);

    assertThatThrownBy(() -> session.beginSubmitting(manualContext(clock)))
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.SUBMISSION_NOT_ALLOWED);
    assertThat(session.state()).isEqualTo(ExamSessionState.IN_PROGRESS);
  }

  @Test
  void comprehensiveKnowledgeCanBeginManualSubmissionAt120MinuteBoundary() {
    MutableClock clock = clockAt(START);
    ExamSession session = inProgress(comprehensiveRule(), clock);
    clock.advance(Duration.ofMinutes(120));

    ExamSubmissionStartResult result = session.beginSubmitting(manualContext(clock));

    assertChanged(result.transition(), ExamSessionState.SUBMITTING);
    assertThat(result.reservationCreated()).isTrue();
    assertThat(result.activeMode()).isEqualTo(ExamSubmissionMode.MANUAL);
    assertThat(session.submissionMode()).contains(ExamSubmissionMode.MANUAL);
    assertThat(session.answersLocked()).isTrue();
  }

  @Test
  void essayCannotBeginManualSubmissionBeforeItsDeadline() {
    MutableClock clock = clockAt(START);
    ExamSession session = inProgress(essayRule(), clock);
    clock.advance(Duration.ofMinutes(119).plusSeconds(59));

    assertThatThrownBy(() -> session.beginSubmitting(manualContext(clock)))
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.SUBMISSION_NOT_ALLOWED);
    assertThat(session.state()).isEqualTo(ExamSessionState.IN_PROGRESS);
  }

  @Test
  void forceSubmissionCannotBeginBeforeDeadline() {
    MutableClock clock = clockAt(START);
    ExamSession session = inProgress(comprehensiveRule(), clock);
    clock.advance(Duration.ofMinutes(149).plusSeconds(59));

    assertThatThrownBy(() -> session.beginForceSubmitting(forcedContext(clock)))
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.FORCE_SUBMISSION_NOT_DUE);
    assertThat(session.state()).isEqualTo(ExamSessionState.IN_PROGRESS);
  }

  @Test
  void deadlineForcesSubmissionAndRecordsForceSubmittedOnlyAfterSuccess() {
    MutableClock clock = clockAt(START);
    ExamSession session = inProgress(essayRule(), clock);
    clock.advance(Duration.ofMinutes(120));

    ExamSubmissionContext context = forcedContext(clock);
    ExamSubmissionStartResult submitting = session.beginForceSubmitting(context);

    assertChanged(submitting.transition(), ExamSessionState.SUBMITTING);
    assertThat(session.submissionMode()).contains(ExamSubmissionMode.FORCED);
    assertThat(session.exceptionalResults()).isEmpty();

    ExamTransition submitted = session.markSubmitted(context);

    assertChanged(submitted, ExamSessionState.SUBMITTED);
    assertThat(session.exceptionalResults())
        .extracting(ExamExceptionalResult::code)
        .containsExactly(ExamExceptionalResultCode.FORCE_SUBMITTED);
  }

  @Test
  void manualSubmissionAtForceDeadlineMustUseForceSubmissionCommand() {
    MutableClock clock = clockAt(START);
    ExamSession session = inProgress(comprehensiveRule(), clock);
    clock.advance(Duration.ofMinutes(150));

    assertThatThrownBy(() -> session.beginSubmitting(manualContext(clock)))
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.SUBMISSION_NOT_ALLOWED);

    assertChanged(
        session.beginForceSubmitting(forcedContext(clock)).transition(),
        ExamSessionState.SUBMITTING);
    assertThat(session.submissionMode()).contains(ExamSubmissionMode.FORCED);
  }

  @Test
  void forceSubmittedResultCannotBeRecordedDirectlyOrDuplicated() {
    MutableClock clock = clockAt(START);
    ExamSession session = inProgress(essayRule(), clock);

    assertThatThrownBy(() -> session.recordExceptionalResult(
        ExamExceptionalResultCode.FORCE_SUBMITTED))
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.INVALID_EXCEPTIONAL_RESULT);
    assertThatThrownBy(() -> session.recordExceptionalResult(
        ExamExceptionalResultCode.SUBMIT_FAILED))
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.INVALID_EXCEPTIONAL_RESULT);

    clock.advance(Duration.ofMinutes(120));
    ExamSubmissionContext context = forcedContext(clock);
    assertRepeatedSubmissionStartIsIdempotent(
        session.beginForceSubmitting(context),
        session.beginForceSubmitting(context));
    assertRepeatedCommandIsIdempotent(
        session.markSubmitted(context),
        session.markSubmitted(context));
    assertThat(session.exceptionalResults())
        .extracting(ExamExceptionalResult::code)
        .containsExactly(ExamExceptionalResultCode.FORCE_SUBMITTED);
  }

  @Test
  void finalSubjectCanCompleteDirectlyAndOnlyTheSameFactIsIdempotent() {
    MutableClock clock = clockAt(START);
    ExamSession session = submitted(comprehensiveRule(), clock);
    NoNextSubjectFact fact = noNextSubjectFact();

    assertChanged(session.completeFinalSubject(fact), ExamSessionState.COMPLETED);
    assertThat(session.completeFinalSubject(fact).changed()).isFalse();
    assertThatThrownBy(() -> session.completeFinalSubject(
        new NoNextSubjectFact("subject-2", 2, 8)))
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.COMPLETION_FACT_MISMATCH);
  }

  @Test
  void essayAndIndependentSubjectCanCompleteDirectly() {
    MutableClock essayClock = clockAt(START);
    ExamSession essay = submitted(essayRule(), essayClock);
    MutableClock independentClock = clockAt(START);
    ExamSession independent = submitted(independentRule(), independentClock);

    assertChanged(essay.completeFinalSubject(
        new NoNextSubjectFact("essay", 1, 1)), ExamSessionState.COMPLETED);
    assertChanged(independent.completeFinalSubject(
        new NoNextSubjectFact("independent", 1, 1)), ExamSessionState.COMPLETED);
  }

  @Test
  void directCompletionRequiresSubmittedStateAndNoNextSubjectFact() {
    ExamSession session = inProgress(comprehensiveRule(), clockAt(START));

    assertInvalidTransition(() -> session.completeFinalSubject(noNextSubjectFact()));
    assertThatThrownBy(() -> session.completeFinalSubject(null))
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.NO_NEXT_SUBJECT_FACT_REQUIRED);
  }

  @Test
  void activatesNextSubjectWithInheritedLinkedDeadlineAndUnlocksNewAnswers() {
    MutableClock clock = clockAt(START);
    ExamSession session = submitted(comprehensiveRule(), clock);
    session.beginSubjectTransition();
    ExamTimingWindow nextWindow = nextSubjectWindow(clock);
    clock.resetInstantReads();

    ExamTransition transition = session.activateNextSubject(nextWindow);

    assertChanged(transition, ExamSessionState.IN_PROGRESS);
    assertThat(nextWindow.subjectStartAt()).isEqualTo(clock.currentInstant());
    assertThat(clock.instantReads()).isOne();
    assertThat(session.timingWindow()).isEqualTo(nextWindow);
    assertThat(session.timingWindow().linkedDeadlineAt())
        .isEqualTo(START.plus(Duration.ofMinutes(240)));
    assertThat(session.submissionContext()).isEmpty();
    assertThat(session.submissionMode()).isEmpty();
    assertThat(session.answersLocked()).isFalse();
    assertThat(session.activateNextSubject(nextWindow).changed()).isFalse();
  }

  @Test
  void rejectsHistoricalBackfillForNextSubjectStart() {
    MutableClock clock = clockAt(START);
    ExamSession session = submitted(comprehensiveRule(), clock);
    session.beginSubjectTransition();
    ExamTimingWindow historicalWindow = new ExamTimingWindow(
        START,
        START,
        START.plus(Duration.ofMinutes(240)),
        START,
        START.plus(Duration.ofMinutes(240)),
        true);

    assertThat(clock.currentInstant()).isEqualTo(START.plus(Duration.ofMinutes(120)));
    assertThatThrownBy(() -> session.activateNextSubject(historicalWindow))
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.NEXT_SUBJECT_START_TIME_MISMATCH);
    assertThat(session.state()).isEqualTo(ExamSessionState.SUBJECT_TRANSITION);
  }

  @Test
  void rejectsFuturePrefillForNextSubjectStart() {
    MutableClock clock = clockAt(START);
    ExamSession session = submitted(comprehensiveRule(), clock);
    session.beginSubjectTransition();
    Instant futureStart = clock.currentInstant().plusSeconds(60);
    ExamTimingWindow futureWindow = new ExamTimingWindow(
        START,
        futureStart,
        START.plus(Duration.ofMinutes(240)),
        futureStart,
        START.plus(Duration.ofMinutes(240)),
        true);

    assertThatThrownBy(() -> session.activateNextSubject(futureWindow))
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.NEXT_SUBJECT_START_TIME_MISMATCH);
    assertThat(session.state()).isEqualTo(ExamSessionState.SUBJECT_TRANSITION);
  }

  @Test
  void rejectsNextSubjectWindowFromAnotherLinkedExamOrDifferentRepeatedWindow() {
    MutableClock clock = clockAt(START);
    ExamSession session = submitted(comprehensiveRule(), clock);
    session.beginSubjectTransition();
    ExamTimingWindow nextWindow = nextSubjectWindow(clock);
    ExamTimingWindow otherLinkedExam = new ExamTimingWindow(
        START,
        clock.instant(),
        START.plus(Duration.ofMinutes(239)),
        clock.instant(),
        START.plus(Duration.ofMinutes(239)),
        true);

    assertThatThrownBy(() -> session.activateNextSubject(otherLinkedExam))
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.NEXT_SUBJECT_WINDOW_MISMATCH);

    session.activateNextSubject(nextWindow);
    ExamTimingWindow differentWindow = new ExamTimingWindow(
        START,
        clock.instant().plusSeconds(1),
        START.plus(Duration.ofMinutes(240)),
        clock.instant().plusSeconds(1),
        START.plus(Duration.ofMinutes(240)),
        true);
    assertThatThrownBy(() -> session.activateNextSubject(differentWindow))
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.NEXT_SUBJECT_WINDOW_MISMATCH);
  }

  @Test
  void persistedAbandonmentCompletesSessionAndCanNeverBeRecovered() {
    MutableClock clock = clockAt(START);
    ExamSession session = submitted(comprehensiveRule(), clock);
    session.beginSubjectTransition();
    PersistedNextSubjectAbandonment fact = abandonmentFact(clock);

    assertChanged(
        session.completeAfterPersistedAbandonment(fact),
        ExamSessionState.COMPLETED);
    assertThat(session.exceptionalResults())
        .extracting(ExamExceptionalResult::code)
        .doesNotContain(ExamExceptionalResultCode.ABANDONED);
    assertThat(session.completeAfterPersistedAbandonment(fact).changed()).isFalse();
    assertThatThrownBy(() -> session.activateNextSubject(nextSubjectWindow(clock)))
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.SESSION_COMPLETED_CANNOT_RESUME);
    assertThatThrownBy(() -> session.completeAfterPersistedAbandonment(
        new PersistedNextSubjectAbandonment("case", 2, 10, clock.instant())))
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.COMPLETION_FACT_MISMATCH);
  }

  @Test
  void abandonmentCompletionRequiresPersistedFact() {
    MutableClock clock = clockAt(START);
    ExamSession session = submitted(comprehensiveRule(), clock);
    session.beginSubjectTransition();

    assertThatThrownBy(() -> session.completeAfterPersistedAbandonment(null))
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.PERSISTED_ABANDONMENT_FACT_REQUIRED);
  }

  @Test
  void abandonedCannotBeRecordedDirectlyFromCreatedOrSubjectTransition() {
    ExamSession created = newSession(comprehensiveRule(), clockAt(START));

    assertExceptionalResultCannotBeRecorded(
        () -> created.recordExceptionalResult(ExamExceptionalResultCode.ABANDONED));
    assertThat(created.state()).isEqualTo(ExamSessionState.CREATED);

    MutableClock clock = clockAt(START);
    ExamSession transitioning = submitted(comprehensiveRule(), clock);
    transitioning.beginSubjectTransition();
    assertExceptionalResultCannotBeRecorded(
        () -> transitioning.recordExceptionalResult(ExamExceptionalResultCode.ABANDONED));
    assertThat(transitioning.state()).isEqualTo(ExamSessionState.SUBJECT_TRANSITION);
  }

  @Test
  void submissionFailureKeepsAnswersLockedAndRetriesOnlyOriginalContext() {
    MutableClock clock = clockAt(START);
    ExamSession session = inProgress(comprehensiveRule(), clock);
    clock.advance(Duration.ofMinutes(120));
    ExamSubmissionContext context = manualContext(clock);
    session.beginSubmitting(context);

    ExamExceptionalResult failed = session.markSubmissionFailed(context);

    assertThat(failed.code()).isEqualTo(ExamExceptionalResultCode.SUBMIT_FAILED);
    assertThat(session.state()).isEqualTo(ExamSessionState.SUBMITTING);
    assertThat(session.answersLocked()).isTrue();
    assertThat(session.retrySubmission(context)).isEqualTo(context);
    assertChanged(session.markSubmitted(context), ExamSessionState.SUBMITTED);
  }

  @Test
  void differentContextCannotFailRetryOrCompleteExistingSubmission() {
    MutableClock clock = clockAt(START);
    ExamSession session = inProgress(comprehensiveRule(), clock);
    clock.advance(Duration.ofMinutes(120));
    ExamSubmissionContext original = manualContext(clock);
    ExamSubmissionContext different = new ExamSubmissionContext(
        "33333333-3333-3333-3333-333333333333",
        ExamSubmissionMode.MANUAL,
        clock.instant());
    session.beginSubmitting(original);

    assertSubmissionContextMismatch(() -> session.markSubmissionFailed(different));
    assertSubmissionContextMismatch(() -> session.retrySubmission(different));
    assertSubmissionContextMismatch(() -> session.markSubmitted(different));
    assertThat(session.state()).isEqualTo(ExamSessionState.SUBMITTING);
  }

  @Test
  void manualReservationWinsAgainstLaterForcedSubmission() {
    MutableClock clock = clockAt(START);
    ExamSession session = inProgress(comprehensiveRule(), clock);
    clock.advance(Duration.ofMinutes(120));
    ExamSubmissionContext manual = manualContext(clock);

    ExamSubmissionStartResult first = session.beginSubmitting(manual);
    ExamSubmissionStartResult later = session.beginForceSubmitting(forcedContext(clock));

    assertThat(first.reservationCreated()).isTrue();
    assertThat(later.reservationCreated()).isFalse();
    assertThat(later.activeContext()).isEqualTo(manual);
    assertThat(later.activeMode()).isEqualTo(ExamSubmissionMode.MANUAL);
  }

  @Test
  void forcedReservationWinsAgainstLaterManualSubmissionAtDeadline() {
    MutableClock clock = clockAt(START);
    ExamSession session = inProgress(comprehensiveRule(), clock);
    clock.advance(Duration.ofMinutes(150));
    ExamSubmissionContext forced = forcedContext(clock);

    ExamSubmissionStartResult first = session.beginForceSubmitting(forced);
    ExamSubmissionStartResult later = session.beginSubmitting(manualContext(clock));

    assertThat(first.reservationCreated()).isTrue();
    assertThat(later.reservationCreated()).isFalse();
    assertThat(later.activeContext()).isEqualTo(forced);
    assertThat(later.activeMode()).isEqualTo(ExamSubmissionMode.FORCED);
  }

  @Test
  void submissionCommandRejectsContextWithWrongMode() {
    MutableClock clock = clockAt(START);
    ExamSession session = inProgress(comprehensiveRule(), clock);
    clock.advance(Duration.ofMinutes(120));

    assertThatThrownBy(() -> session.beginSubmitting(forcedContext(clock)))
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.SUBMISSION_MODE_MISMATCH);
    assertThatThrownBy(() -> session.beginForceSubmitting(manualContext(clock)))
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.SUBMISSION_MODE_MISMATCH);
  }

  @Test
  void submissionContextRequiresLegalManualOrForcedKey() {
    assertThatThrownBy(() -> new ExamSubmissionContext(
        "not-a-uuid",
        ExamSubmissionMode.MANUAL,
        START))
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.INVALID_SUBMISSION_CONTEXT);
    assertThatThrownBy(() -> new ExamSubmissionContext(
        "AUTO:subject-1:not-an-epoch",
        ExamSubmissionMode.FORCED,
        START))
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.INVALID_SUBMISSION_CONTEXT);
  }

  private ExamSession readyAndWaiting(ExamTimingRule rule, MutableClock clock) {
    ExamSession session = newSession(rule, clock);
    session.candidateLoggedIn();
    session.confirmCandidate();
    session.beginRuleReading();
    session.acknowledgeRules();
    return session;
  }

  private ExamSession inProgress(ExamTimingRule rule, MutableClock clock) {
    ExamSession session = readyAndWaiting(rule, clock);
    session.beginPaperLoading();
    session.startExam();
    return session;
  }

  private ExamSession submitted(ExamTimingRule rule, MutableClock clock) {
    ExamSession session = inProgress(rule, clock);
    clock.advance(rule.minimumBeforeSubmit());
    ExamSubmissionContext context = rule.earlySubmitAllowed()
        ? manualContext(clock)
        : forcedContext(clock);
    if (rule.earlySubmitAllowed()) {
      session.beginSubmitting(context);
    } else {
      session.beginForceSubmitting(context);
    }
    session.markSubmitted(context);
    return session;
  }

  private ExamSession newSession(ExamTimingRule rule, MutableClock clock) {
    return ExamSession.create(timingWindow(rule, clock), clock);
  }

  private ExamTimingWindow timingWindow(ExamTimingRule rule, Clock clock) {
    return new ExamTimer(clock).openFirstSubject(START, rule);
  }

  private ExamTimingRule comprehensiveRule() {
    return ExamTimingRule.fixedSubjectDeadline(
        Duration.ofMinutes(240),
        Duration.ofMinutes(120),
        Duration.ofMinutes(150),
        true);
  }

  private ExamTimingRule essayRule() {
    return ExamTimingRule.fixedSubjectDeadline(
        Duration.ofMinutes(120),
        Duration.ofMinutes(120),
        Duration.ofMinutes(120),
        false);
  }

  private ExamTimingRule independentRule() {
    return ExamTimingRule.fixedSubjectDeadline(
        Duration.ofMinutes(90),
        Duration.ofMinutes(30),
        Duration.ofMinutes(90),
        true);
  }

  private ExamTimingRule caseRule() {
    return ExamTimingRule.linkedDeadline(
        Duration.ofMinutes(240),
        Duration.ZERO,
        true);
  }

  private ExamTimingWindow nextSubjectWindow(MutableClock clock) {
    return new ExamTimer(clock).openNextSubject(START, clock.instant(), caseRule());
  }

  private ExamSubmissionContext manualContext(Clock clock) {
    return new ExamSubmissionContext(
        MANUAL_SUBMISSION_KEY,
        ExamSubmissionMode.MANUAL,
        clock.instant());
  }

  private ExamSubmissionContext forcedContext(Clock clock) {
    return new ExamSubmissionContext(
        FORCED_SUBMISSION_KEY,
        ExamSubmissionMode.FORCED,
        clock.instant());
  }

  private NoNextSubjectFact noNextSubjectFact() {
    return new NoNextSubjectFact("subject-1", 1, 7);
  }

  private PersistedNextSubjectAbandonment abandonmentFact(Clock clock) {
    return new PersistedNextSubjectAbandonment("case", 2, 9, clock.instant());
  }

  private MutableClock clockAt(Instant instant) {
    return new MutableClock(instant, ZoneOffset.UTC);
  }

  private void assertChanged(ExamTransition transition, ExamSessionState expectedState) {
    assertThat(transition.changed()).isTrue();
    assertThat(transition.currentState()).isEqualTo(expectedState);
  }

  private void assertRepeatedCommandIsIdempotent(
      ExamTransition first,
      ExamTransition repeated
  ) {
    assertThat(first.changed()).isTrue();
    assertThat(repeated.changed()).isFalse();
    assertThat(repeated.currentState()).isEqualTo(first.currentState());
  }

  private void assertRepeatedSubmissionStartIsIdempotent(
      ExamSubmissionStartResult first,
      ExamSubmissionStartResult repeated
  ) {
    assertThat(first.reservationCreated()).isTrue();
    assertThat(repeated.reservationCreated()).isFalse();
    assertThat(repeated.activeContext()).isEqualTo(first.activeContext());
  }

  private void assertSubmissionContextMismatch(Runnable command) {
    assertThatThrownBy(command::run)
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.SUBMISSION_CONTEXT_MISMATCH);
  }

  private void assertExceptionalResultCannotBeRecorded(Runnable command) {
    assertThatThrownBy(command::run)
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.INVALID_EXCEPTIONAL_RESULT);
  }

  private void assertInvalidTransition(Runnable command) {
    assertThatThrownBy(command::run)
        .isInstanceOf(ExamDomainException.class)
        .extracting(error -> ((ExamDomainException) error).code())
        .isEqualTo(ExamDomainErrorCode.INVALID_STATE_TRANSITION);
  }

  private static final class MutableClock extends Clock {
    private Instant currentInstant;
    private final ZoneId zone;
    private int instantReads;

    private MutableClock(Instant currentInstant, ZoneId zone) {
      this.currentInstant = currentInstant;
      this.zone = zone;
    }

    void advance(Duration duration) {
      currentInstant = currentInstant.plus(duration);
    }

    Instant currentInstant() {
      return currentInstant;
    }

    int instantReads() {
      return instantReads;
    }

    void resetInstantReads() {
      instantReads = 0;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId requestedZone) {
      return new MutableClock(currentInstant, requestedZone);
    }

    @Override
    public Instant instant() {
      instantReads++;
      return currentInstant;
    }
  }
}
