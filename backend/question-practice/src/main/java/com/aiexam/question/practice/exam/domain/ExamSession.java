package com.aiexam.question.practice.exam.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ExamSession {
  private final Clock clock;
  private final Instant linkedStartAtAnchor;
  private final Instant linkedDeadlineAtAnchor;
  private final ExamTimer timer;
  private final List<ExamExceptionalResult> exceptionalResults = new ArrayList<>();
  private ExamTimingWindow timingWindow;
  private ExamSessionState state = ExamSessionState.CREATED;
  private boolean rulesAcknowledged;
  private ExamSubmissionMode submissionMode;
  private ExamSubmissionContext submissionContext;
  private ExamTimingWindow lastActivatedNextWindow;
  private NoNextSubjectFact noNextSubjectCompletionFact;
  private PersistedNextSubjectAbandonment abandonmentCompletionFact;

  private ExamSession(ExamTimingWindow timingWindow, Clock clock) {
    if (timingWindow == null) {
      throw ExamDomainException.invalidTime("Exam timing window is required");
    }
    if (clock == null) {
      throw ExamDomainException.invalidTime("Clock is required");
    }
    this.timingWindow = timingWindow;
    this.linkedStartAtAnchor = timingWindow.linkedStartAt();
    this.linkedDeadlineAtAnchor = timingWindow.linkedDeadlineAt();
    this.clock = clock;
    this.timer = new ExamTimer(clock);
  }

  public static ExamSession create(ExamTimingWindow timingWindow, Clock clock) {
    return new ExamSession(timingWindow, clock);
  }

  public synchronized ExamSessionState state() {
    return state;
  }

  public synchronized ExamTimingWindow timingWindow() {
    return timingWindow;
  }

  public synchronized ExamTimingSnapshot timingSnapshot() {
    return timer.snapshot(timingWindow);
  }

  public synchronized boolean rulesAcknowledged() {
    return rulesAcknowledged;
  }

  public synchronized List<ExamExceptionalResult> exceptionalResults() {
    return List.copyOf(exceptionalResults);
  }

  public synchronized Optional<ExamSubmissionMode> submissionMode() {
    return Optional.ofNullable(submissionMode);
  }

  public synchronized Optional<ExamSubmissionContext> submissionContext() {
    return Optional.ofNullable(submissionContext);
  }

  public synchronized boolean answersLocked() {
    return submissionContext != null && switch (state) {
      case SUBMITTING, SUBMITTED, SUBJECT_TRANSITION, COMPLETED -> true;
      default -> false;
    };
  }

  public synchronized ExamTransition candidateLoggedIn() {
    return transition(ExamSessionState.CREATED, ExamSessionState.CANDIDATE_LOGGED_IN);
  }

  public synchronized ExamTransition confirmCandidate() {
    return transition(ExamSessionState.CANDIDATE_LOGGED_IN, ExamSessionState.CANDIDATE_CONFIRMED);
  }

  public synchronized ExamTransition beginRuleReading() {
    return transition(ExamSessionState.CANDIDATE_CONFIRMED, ExamSessionState.RULE_READING);
  }

  public synchronized ExamTransition acknowledgeRules() {
    if (state == ExamSessionState.WAITING_START && rulesAcknowledged) {
      return unchanged();
    }
    requireState(ExamSessionState.RULE_READING, ExamSessionState.WAITING_START);
    rulesAcknowledged = true;
    return changeTo(ExamSessionState.WAITING_START);
  }

  public synchronized ExamTransition beginPaperLoading() {
    requireRulesAcknowledged();
    return transition(ExamSessionState.WAITING_START, ExamSessionState.LOADING_PAPER);
  }

  public synchronized ExamTransition startExam() {
    requireRulesAcknowledged();
    requireActiveSession(ExamSessionState.IN_PROGRESS);
    if (state == ExamSessionState.IN_PROGRESS) {
      return unchanged();
    }
    requireState(ExamSessionState.LOADING_PAPER, ExamSessionState.IN_PROGRESS);
    if (clock.instant().isBefore(timingWindow.subjectStartAt())) {
      throw new ExamDomainException(
          ExamDomainErrorCode.EXAM_NOT_STARTED,
          "Exam cannot start before scheduled start time");
    }
    return changeTo(ExamSessionState.IN_PROGRESS);
  }

  public synchronized ExamSubmissionStartResult beginSubmitting(ExamSubmissionContext context) {
    requireSubmissionMode(context, ExamSubmissionMode.MANUAL);
    if (state == ExamSessionState.SUBMITTING) {
      return existingSubmission();
    }
    requireActiveSession(ExamSessionState.SUBMITTING);
    requireState(ExamSessionState.IN_PROGRESS, ExamSessionState.SUBMITTING);
    ExamTimingSnapshot snapshot = timingSnapshot();
    if (!snapshot.canSubmit() || snapshot.forceSubmit()) {
      throw new ExamDomainException(
          ExamDomainErrorCode.SUBMISSION_NOT_ALLOWED,
          "Manual submission is not allowed at the current server time");
    }
    return reserveSubmission(context);
  }

  public synchronized ExamSubmissionStartResult beginForceSubmitting(ExamSubmissionContext context) {
    requireSubmissionMode(context, ExamSubmissionMode.FORCED);
    if (state == ExamSessionState.SUBMITTING) {
      return existingSubmission();
    }
    requireActiveSession(ExamSessionState.SUBMITTING);
    requireState(ExamSessionState.IN_PROGRESS, ExamSessionState.SUBMITTING);
    if (!timingSnapshot().forceSubmit()) {
      throw new ExamDomainException(
          ExamDomainErrorCode.FORCE_SUBMISSION_NOT_DUE,
          "Force submission is not due at the current server time");
    }
    return reserveSubmission(context);
  }

  public synchronized ExamExceptionalResult markSubmissionFailed(ExamSubmissionContext context) {
    requireState(ExamSessionState.SUBMITTING, ExamSessionState.SUBMITTING);
    requireOriginalSubmissionContext(context);
    return appendExceptionalResult(ExamExceptionalResultCode.SUBMIT_FAILED);
  }

  public synchronized ExamSubmissionContext retrySubmission(ExamSubmissionContext context) {
    requireState(ExamSessionState.SUBMITTING, ExamSessionState.SUBMITTING);
    requireOriginalSubmissionContext(context);
    return submissionContext;
  }

  public synchronized ExamTransition markSubmitted(ExamSubmissionContext context) {
    if (state == ExamSessionState.SUBMITTED) {
      requireOriginalSubmissionContext(context);
      return unchanged();
    }
    requireState(ExamSessionState.SUBMITTING, ExamSessionState.SUBMITTED);
    requireOriginalSubmissionContext(context);
    ExamTransition transition = changeTo(ExamSessionState.SUBMITTED);
    if (submissionMode == ExamSubmissionMode.FORCED) {
      appendExceptionalResult(ExamExceptionalResultCode.FORCE_SUBMITTED);
    }
    return transition;
  }

  public synchronized ExamTransition beginSubjectTransition() {
    return transition(ExamSessionState.SUBMITTED, ExamSessionState.SUBJECT_TRANSITION);
  }

  public synchronized ExamTransition completeFinalSubject(NoNextSubjectFact fact) {
    if (fact == null) {
      throw new ExamDomainException(
          ExamDomainErrorCode.NO_NEXT_SUBJECT_FACT_REQUIRED,
          "No-next-subject fact is required to complete a final subject");
    }
    if (state == ExamSessionState.COMPLETED) {
      if (fact.equals(noNextSubjectCompletionFact)) {
        return unchanged();
      }
      throw completionFactMismatch();
    }
    requireState(ExamSessionState.SUBMITTED, ExamSessionState.COMPLETED);
    noNextSubjectCompletionFact = fact;
    return changeTo(ExamSessionState.COMPLETED);
  }

  public synchronized ExamTransition activateNextSubject(ExamTimingWindow nextWindow) {
    if (state == ExamSessionState.COMPLETED) {
      throw new ExamDomainException(
          ExamDomainErrorCode.SESSION_COMPLETED_CANNOT_RESUME,
          "A completed exam session cannot activate another subject");
    }
    if (state == ExamSessionState.IN_PROGRESS && lastActivatedNextWindow != null) {
      if (lastActivatedNextWindow.equals(nextWindow)) {
        return unchanged();
      }
      throw nextSubjectWindowMismatch("A different next-subject window is already active");
    }
    Instant authoritativeStartAt = clock.instant();
    requireState(ExamSessionState.SUBJECT_TRANSITION, ExamSessionState.IN_PROGRESS);
    validateNextSubjectWindow(nextWindow, authoritativeStartAt);
    timingWindow = nextWindow;
    lastActivatedNextWindow = nextWindow;
    submissionContext = null;
    submissionMode = null;
    return changeTo(ExamSessionState.IN_PROGRESS);
  }

  public synchronized ExamTransition completeAfterPersistedAbandonment(
      PersistedNextSubjectAbandonment fact
  ) {
    if (fact == null) {
      throw new ExamDomainException(
          ExamDomainErrorCode.PERSISTED_ABANDONMENT_FACT_REQUIRED,
          "Persisted next-subject abandonment fact is required");
    }
    if (state == ExamSessionState.COMPLETED) {
      if (fact.equals(abandonmentCompletionFact)) {
        return unchanged();
      }
      throw completionFactMismatch();
    }
    requireState(ExamSessionState.SUBJECT_TRANSITION, ExamSessionState.COMPLETED);
    abandonmentCompletionFact = fact;
    return changeTo(ExamSessionState.COMPLETED);
  }

  public synchronized ExamExceptionalResult recordExceptionalResult(ExamExceptionalResultCode code) {
    if (code == null) {
      throw new ExamDomainException(
          ExamDomainErrorCode.INVALID_EXCEPTIONAL_RESULT,
          "Exceptional result code is required");
    }
    if (code == ExamExceptionalResultCode.ABANDONED
        || code == ExamExceptionalResultCode.FORCE_SUBMITTED
        || code == ExamExceptionalResultCode.SUBMIT_FAILED) {
      throw new ExamDomainException(
          ExamDomainErrorCode.INVALID_EXCEPTIONAL_RESULT,
          code + " cannot be recorded through the public exceptional-result entry");
    }
    return appendExceptionalResult(code);
  }

  private ExamTransition transition(ExamSessionState requiredState, ExamSessionState targetState) {
    if (state == targetState) {
      return unchanged();
    }
    requireState(requiredState, targetState);
    return changeTo(targetState);
  }

  private void requireState(ExamSessionState requiredState, ExamSessionState targetState) {
    if (state != requiredState) {
      throw new ExamDomainException(
          ExamDomainErrorCode.INVALID_STATE_TRANSITION,
          "Cannot transition exam session from " + state + " to " + targetState);
    }
  }

  private void requireRulesAcknowledged() {
    if (!rulesAcknowledged) {
      throw new ExamDomainException(
          ExamDomainErrorCode.RULES_NOT_ACKNOWLEDGED,
          "Exam rules must be acknowledged before loading or starting the paper");
    }
  }

  private void requireSubmissionMode(
      ExamSubmissionContext context,
      ExamSubmissionMode expectedMode
  ) {
    if (context == null || context.mode() != expectedMode) {
      throw new ExamDomainException(
          ExamDomainErrorCode.SUBMISSION_MODE_MISMATCH,
          "Submission command requires " + expectedMode + " context");
    }
  }

  private void requireOriginalSubmissionContext(ExamSubmissionContext context) {
    if (submissionContext == null || !submissionContext.equals(context)) {
      throw new ExamDomainException(
          ExamDomainErrorCode.SUBMISSION_CONTEXT_MISMATCH,
          "Submission retry and completion must use the original context");
    }
  }

  private void requireActiveSession(ExamSessionState targetState) {
    if (state == ExamSessionState.COMPLETED) {
      throw new ExamDomainException(
          ExamDomainErrorCode.SESSION_COMPLETED_CANNOT_RESUME,
          "A completed exam session cannot transition to " + targetState);
    }
  }

  private ExamSubmissionStartResult reserveSubmission(ExamSubmissionContext context) {
    submissionContext = context;
    submissionMode = context.mode();
    return new ExamSubmissionStartResult(
        changeTo(ExamSessionState.SUBMITTING),
        submissionContext,
        true);
  }

  private ExamSubmissionStartResult existingSubmission() {
    return new ExamSubmissionStartResult(unchanged(), submissionContext, false);
  }

  private void validateNextSubjectWindow(
      ExamTimingWindow nextWindow,
      Instant authoritativeStartAt
  ) {
    if (nextWindow == null
        || !linkedStartAtAnchor.equals(nextWindow.linkedStartAt())
        || !linkedDeadlineAtAnchor.equals(nextWindow.linkedDeadlineAt())) {
      throw nextSubjectWindowMismatch(
          "Next-subject window must inherit the linked exam anchors");
    }
    if (!nextWindow.subjectStartAt().equals(authoritativeStartAt)) {
      throw new ExamDomainException(
          ExamDomainErrorCode.NEXT_SUBJECT_START_TIME_MISMATCH,
          "Next-subject start time must equal the authoritative server time");
    }
    if (nextWindow.subjectStartAt().isBefore(timingWindow.subjectStartAt())
        || nextWindow.subjectStartAt().isAfter(linkedDeadlineAtAnchor)) {
      throw nextSubjectWindowMismatch(
          "Next-subject start must follow the current subject and not exceed the linked deadline");
    }
  }

  private ExamDomainException nextSubjectWindowMismatch(String message) {
    return new ExamDomainException(ExamDomainErrorCode.NEXT_SUBJECT_WINDOW_MISMATCH, message);
  }

  private ExamDomainException completionFactMismatch() {
    return new ExamDomainException(
        ExamDomainErrorCode.COMPLETION_FACT_MISMATCH,
        "A completed session cannot be overwritten by a different completion fact");
  }

  private ExamTransition changeTo(ExamSessionState targetState) {
    ExamSessionState previousState = state;
    state = targetState;
    return new ExamTransition(previousState, state, true);
  }

  private ExamTransition unchanged() {
    return new ExamTransition(state, state, false);
  }

  private ExamExceptionalResult appendExceptionalResult(ExamExceptionalResultCode code) {
    ExamExceptionalResult result = new ExamExceptionalResult(code, clock.instant());
    exceptionalResults.add(result);
    return result;
  }
}
