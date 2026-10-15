package com.aiexam.question.practice.attempt;

import com.aiexam.common.api.PageResponse;
import com.aiexam.common.error.BusinessException;
import com.aiexam.question.practice.attempt.domain.FeedbackMode;
import com.aiexam.question.practice.attempt.domain.AttemptStatus;
import com.aiexam.question.practice.attempt.domain.PracticeAttempt;
import com.aiexam.question.practice.attempt.domain.PracticeQuestionSnapshot;
import com.aiexam.question.practice.attempt.repository.PracticeAttemptRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;

final class InMemoryPracticeAttemptRepository implements PracticeAttemptRepository {
  private final AtomicLong attemptIds = new AtomicLong(1000);
  private final Map<Long, PracticeAttempt> attempts = new LinkedHashMap<>();
  private final Map<String, StoredMutation> mutations = new HashMap<>();
  private final Map<Long, Integer> mistakeSyncCounts = new HashMap<>();
  private List<PracticeQuestionSnapshot> questionBank = List.of(PracticeAttemptFixtures.singleChoiceQuestion());

  void questionBank(List<PracticeQuestionSnapshot> questionBank) {
    this.questionBank = List.copyOf(questionBank);
  }

  @Override
  public List<PracticeQuestionSnapshot> selectQuestions(QuestionSelection selection) {
    return questionBank.stream()
        .limit(selection.count())
        .toList();
  }

  @Override
  public Optional<PracticeQuestionSnapshot> selectPublishedQuestionById(QuestionSelection selection,
                                                                        Long questionId) {
    return questionBank.stream()
        .filter(question -> question.questionId().equals(questionId))
        .findFirst();
  }

  @Override
  public PracticeAttempt createAttempt(NewAttempt attempt) {
    long id = attemptIds.incrementAndGet();
    BigDecimal maxScore = attempt.questions().stream()
        .flatMap(question -> question.subQuestions().stream())
        .map(PracticeQuestionSnapshot.SubQuestion::score)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    PracticeAttempt created = new PracticeAttempt(id, attempt.userId(), attempt.qualificationId(),
        attempt.subjectId(), attempt.mode(), attempt.sourceType(), attempt.sourceSpec(),
        FeedbackMode.valueOf(attempt.feedbackMode()), com.aiexam.question.practice.attempt.domain.AttemptStatus.IN_PROGRESS,
        attempt.requestedCount(), attempt.partial(), attempt.startedAt(), null, 0, BigDecimal.ZERO,
        maxScore, 0, 0, 0, attempt.questions(), Map.of());
    attempts.put(id, created);
    return created;
  }

  @Override
  public Optional<PracticeAttempt> findAttempt(Long userId, Long attemptId) {
    PracticeAttempt attempt = attempts.get(attemptId);
    return attempt != null && attempt.userId().equals(userId) ? Optional.of(attempt) : Optional.empty();
  }

  @Override
  public PageResponse<PracticeAttempt> findAttempts(
      Long userId,
      Long qualificationId,
      Long subjectId,
      AttemptStatus status,
      long pageNo,
      long pageSize
  ) {
    List<PracticeAttempt> all = attempts.values().stream()
        .filter(attempt -> attempt.userId().equals(userId))
        .filter(attempt -> qualificationId == null || attempt.qualificationId().equals(qualificationId))
        .filter(attempt -> subjectId == null || Objects.equals(attempt.subjectId(), subjectId))
        .filter(attempt -> status == null || attempt.status() == status)
        .sorted(Comparator.comparing(PracticeAttempt::startedAt).reversed())
        .toList();
    int from = (int) Math.min(all.size(), (pageNo - 1) * pageSize);
    int to = (int) Math.min(all.size(), from + pageSize);
    return new PageResponse<>(new ArrayList<>(all.subList(from, to)), pageNo, pageSize, all.size(), to < all.size());
  }

  @Override
  public PageResponse<AttemptHistoryProjection> findAttemptHistory(
      Long userId,
      Long qualificationId,
      Long subjectId,
      AttemptStatus status,
      long pageNo,
      long pageSize
  ) {
    List<PracticeAttempt> all = attempts.values().stream()
        .filter(attempt -> attempt.userId().equals(userId))
        .filter(attempt -> qualificationId == null || attempt.qualificationId().equals(qualificationId))
        .filter(attempt -> subjectId == null || Objects.equals(attempt.subjectId(), subjectId))
        .filter(attempt -> status == null || attempt.status() == status)
        .sorted(Comparator.comparing(PracticeAttempt::startedAt).reversed())
        .toList();
    int from = (int) Math.min(all.size(), (pageNo - 1) * pageSize);
    int to = (int) Math.min(all.size(), from + pageSize);
    List<AttemptHistoryProjection> items = all.subList(from, to).stream()
        .map(attempt -> new AttemptHistoryProjection(
            attempt.attemptId(),
            attempt.status(),
            attempt.sourceType(),
            attempt.sourceSpec(),
            attempt.questions().size(),
            attempt.startedAt(),
            attempt.submittedAt(),
            attempt.submittedAt() == null
                ? null
                : (int) Math.max(
                    0,
                    java.time.Duration.between(attempt.startedAt(), attempt.submittedAt()).getSeconds()),
            attempt.terminal() ? attempt.totalScore() : null,
            attempt.terminal() ? attempt.maxScore() : null))
        .toList();
    return new PageResponse<>(items, pageNo, pageSize, all.size(), to < all.size());
  }

  @Override
  public PracticeAttempt saveAttempt(PracticeAttempt attempt) {
    PracticeAttempt current = attempts.get(attempt.attemptId());
    if (current != null) {
      Map<Long, PracticeQuestionSnapshot> before = current.questions().stream()
          .collect(java.util.stream.Collectors.toMap(PracticeQuestionSnapshot::questionId, question -> question));
      attempt.questions().stream()
          .filter(question -> question.settlementStatus()
              == com.aiexam.question.practice.attempt.domain.QuestionSettlementStatus.SETTLED)
          .filter(question -> question.wrongCount() > 0)
          .filter(question -> before.get(question.questionId()) != null
              && before.get(question.questionId()).settlementStatus()
              != com.aiexam.question.practice.attempt.domain.QuestionSettlementStatus.SETTLED)
          .forEach(question -> mistakeSyncCounts.merge(question.questionId(), 1, Integer::sum));
    }
    attempts.put(attempt.attemptId(), attempt);
    return attempt;
  }

  int mistakeSyncCount(Long questionId) {
    return mistakeSyncCounts.getOrDefault(questionId, 0);
  }

  @Override
  public <T> T runIdempotent(MutationCommand command, Class<T> resultType, Supplier<T> action) {
    String key = command.userId() + "|" + command.operationCode() + "|" + command.attemptScopeId()
        + "|" + command.clientMutationId();
    StoredMutation existing = mutations.get(key);
    if (existing != null) {
      if (!Objects.equals(existing.requestHash(), command.requestHash())) {
        throw new BusinessException("PRACTICE_IDEMPOTENCY_KEY_REUSED",
            "clientMutationId was already used with different request", HttpStatus.CONFLICT);
      }
      return resultType.cast(existing.response());
    }
    T result = action.get();
    mutations.put(key, new StoredMutation(command.requestHash(), result));
    return result;
  }

  private record StoredMutation(String requestHash, Object response) {
  }
}
