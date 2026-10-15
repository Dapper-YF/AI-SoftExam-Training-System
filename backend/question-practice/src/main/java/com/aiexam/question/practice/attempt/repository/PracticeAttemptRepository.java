package com.aiexam.question.practice.attempt.repository;

import com.aiexam.common.api.PageResponse;
import com.aiexam.question.practice.attempt.domain.AttemptStatus;
import com.aiexam.question.practice.attempt.domain.PracticeAttempt;
import com.aiexam.question.practice.attempt.domain.PracticeQuestionSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface PracticeAttemptRepository {
  List<PracticeQuestionSnapshot> selectQuestions(QuestionSelection selection);

  Optional<PracticeQuestionSnapshot> selectPublishedQuestionById(QuestionSelection selection, Long questionId);

  PracticeAttempt createAttempt(NewAttempt attempt);

  Optional<PracticeAttempt> findAttempt(Long userId, Long attemptId);

  default PageResponse<PracticeAttempt> findAttempts(Long userId, long pageNo, long pageSize) {
    return findAttempts(userId, null, null, null, pageNo, pageSize);
  }

  PageResponse<PracticeAttempt> findAttempts(
      Long userId,
      Long qualificationId,
      Long subjectId,
      AttemptStatus status,
      long pageNo,
      long pageSize
  );

  PageResponse<AttemptHistoryProjection> findAttemptHistory(
      Long userId,
      Long qualificationId,
      Long subjectId,
      AttemptStatus status,
      long pageNo,
      long pageSize
  );

  PracticeAttempt saveAttempt(PracticeAttempt attempt);

  <T> T runIdempotent(MutationCommand command, Class<T> resultType, Supplier<T> action);

  record QuestionSelection(
      Long userId,
      Long qualificationId,
      Long subjectId,
      String mode,
      String sourceType,
      Map<String, Object> sourceSpec,
      int count
  ) {
  }

  record NewAttempt(
      Long userId,
      Long qualificationId,
      Long subjectId,
      String mode,
      String sourceType,
      Map<String, Object> sourceSpec,
      String feedbackMode,
      int requestedCount,
      boolean partial,
      Instant startedAt,
      List<PracticeQuestionSnapshot> questions
  ) {
  }

  record MutationCommand(
      Long userId,
      String operationCode,
      Long attemptScopeId,
      UUID clientMutationId,
      String requestHash,
      Instant now
  ) {
  }

  record AttemptHistoryProjection(
      Long attemptId,
      AttemptStatus status,
      String sourceType,
      Map<String, Object> sourceSpec,
      int questionCount,
      Instant startedAt,
      Instant submittedAt,
      Integer durationSeconds,
      BigDecimal totalScore,
      BigDecimal maxScore
  ) {
  }
}
