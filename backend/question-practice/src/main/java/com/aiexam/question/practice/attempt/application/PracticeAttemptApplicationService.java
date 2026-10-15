package com.aiexam.question.practice.attempt.application;

import com.aiexam.common.api.PageResponse;
import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ErrorCode;
import com.aiexam.common.error.ResourceNotFoundException;
import com.aiexam.foundation.application.FoundationApplicationService;
import com.aiexam.question.practice.attempt.domain.AttemptStatus;
import com.aiexam.question.practice.attempt.domain.FeedbackMode;
import com.aiexam.question.practice.attempt.domain.GradingStatus;
import com.aiexam.question.practice.attempt.domain.PracticeAnswer;
import com.aiexam.question.practice.attempt.domain.PracticeAnswerCanonicalizer;
import com.aiexam.question.practice.attempt.domain.PracticeAnswerPayload;
import com.aiexam.question.practice.attempt.domain.PracticeAttempt;
import com.aiexam.question.practice.attempt.domain.PracticeQuestionSnapshot;
import com.aiexam.question.practice.attempt.domain.QuestionSettlementStatus;
import com.aiexam.question.practice.attempt.dto.CreatePracticeAttemptRequest;
import com.aiexam.question.practice.attempt.dto.CreateCaseAttemptRequest;
import com.aiexam.question.practice.attempt.dto.PracticeAnswerSaveResultDto;
import com.aiexam.question.practice.attempt.dto.PracticeAttemptDetailDto;
import com.aiexam.question.practice.attempt.dto.PracticeAttemptExplanationDto;
import com.aiexam.question.practice.attempt.dto.PracticeAttemptHistoryDto;
import com.aiexam.question.practice.attempt.dto.PracticeAttemptSubmitResultDto;
import com.aiexam.question.practice.attempt.dto.SavePracticeAnswerRequest;
import com.aiexam.question.practice.attempt.dto.SettlePracticeQuestionRequest;
import com.aiexam.question.practice.attempt.dto.SubmitPracticeAttemptRequest;
import com.aiexam.question.practice.attempt.repository.PracticeAttemptRepository;
import com.aiexam.question.practice.attempt.repository.PracticeAttemptRepository.AttemptHistoryProjection;
import com.aiexam.question.practice.attempt.repository.PracticeAttemptRepository.MutationCommand;
import com.aiexam.question.practice.attempt.repository.PracticeAttemptRepository.NewAttempt;
import com.aiexam.question.practice.attempt.repository.PracticeAttemptRepository.QuestionSelection;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PracticeAttemptApplicationService {
  private final PracticeAttemptRepository repository;
  private final PracticeCurrentUserProvider currentUserProvider;
  private final FoundationApplicationService foundationApplicationService;
  private final Clock clock;
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  public PracticeAttemptApplicationService(
      PracticeAttemptRepository repository,
      PracticeCurrentUserProvider currentUserProvider,
      FoundationApplicationService foundationApplicationService,
      Clock clock
  ) {
    this.repository = repository;
    this.currentUserProvider = currentUserProvider;
    this.foundationApplicationService = foundationApplicationService;
    this.clock = clock;
  }

  @Transactional
  public PracticeAttemptDetailDto create(CreatePracticeAttemptRequest request) {
    PracticePrincipal user = currentUserProvider.requireCurrentUser();
    foundationApplicationService.validateStudentQualification(user.userId(), request.qualificationId(), user.permissions());
    Instant now = clock.instant();
    SourceSpec source = sourceSpec(request);
    if (request.subjectId() == null && !"PAPER".equals(source.type())) {
      throw validation("subjectId is required for non-paper practice");
    }
    validateSourceScope(request, source);
    FeedbackMode feedbackMode = feedbackMode(request.feedbackMode());
    String mode = normalizeRequired(request.mode(), "mode");
    if ("PAST_EXAM".equals(mode) && !"PAPER".equals(source.type())) {
      throw validation("历史真题练习必须选择一张完整试卷");
    }
    if ("PAST_EXAM".equals(mode) && feedbackMode != FeedbackMode.WHOLE_PAPER) {
      throw validation("历史真题必须使用整卷作答模式");
    }
    int requestedCount = request.count() == null ? 20 : request.count();
    if (requestedCount < 1 || requestedCount > 100) {
      throw validation("count must be between 1 and 100");
    }
    String hash = hash("CREATE_ATTEMPT", request);
    return repository.runIdempotent(mutation(user.userId(), "CREATE_ATTEMPT", 0L,
            request.clientMutationId(), hash, now), PracticeAttemptDetailDto.class, () -> {
          List<PracticeQuestionSnapshot> questions = repository.selectQuestions(new QuestionSelection(
              user.userId(), request.qualificationId(), request.subjectId(), mode, source.type(),
              source.spec(), requestedCount));
          if (questions.isEmpty()) {
            if ("PAPER".equals(source.type())) {
              throw validation("该试卷不是已发布的完整真题卷，或资格、科目与当前选择不一致");
            }
            throw validation("no published question available for practice source");
          }
          int persistedRequestedCount = "PAPER".equals(source.type())
              ? questions.size() : requestedCount;
          boolean partial = !"PAPER".equals(source.type()) && questions.size() < requestedCount;
          PracticeAttempt attempt = repository.createAttempt(new NewAttempt(user.userId(),
              request.qualificationId(), request.subjectId(), mode, source.type(), source.spec(),
              feedbackMode.name(), persistedRequestedCount, partial, now, questions));
          return detail(attempt, now);
        });
  }

  @Transactional
  public PracticeAttemptDetailDto createCaseAttempt(CreateCaseAttemptRequest request) {
    PracticePrincipal user = currentUserProvider.requireCurrentUser();
    foundationApplicationService.validateStudentQualification(user.userId(), request.qualificationId(), user.permissions());
    Instant now = clock.instant();
    String hash = hash("CREATE_CASE_ATTEMPT", request);
    return repository.runIdempotent(mutation(user.userId(), "CREATE_CASE_ATTEMPT", 0L,
            request.clientMutationId(), hash, now), PracticeAttemptDetailDto.class, () -> {
          Map<String, Object> selectionSpec = Map.of("questionId", request.questionId());
          QuestionSelection selection = new QuestionSelection(user.userId(), request.qualificationId(),
              request.subjectId(), "PRACTICE", "QUESTION", selectionSpec, 1);
          PracticeQuestionSnapshot question = repository.selectPublishedQuestionById(selection, request.questionId())
              .orElseThrow(() -> new BusinessException("PRACTICE_QUESTION_NOT_FOUND",
                  "题目不存在、未发布或不属于当前资格科目", HttpStatus.NOT_FOUND));
          Map<String, Object> sourceSpec = Map.of(
              "questionId", request.questionId(),
              "questionType", question.questionType());
          PracticeAttempt attempt = repository.createAttempt(new NewAttempt(user.userId(),
              request.qualificationId(), request.subjectId(), "PRACTICE", "QUESTION", sourceSpec,
              FeedbackMode.WHOLE_PAPER.name(), 1, false, now, List.of(question)));
          return detail(attempt, now);
        });
  }

  public PracticeAttemptDetailDto get(Long attemptId) {
    PracticePrincipal user = currentUserProvider.requireCurrentUser();
    return detail(load(user.userId(), attemptId), clock.instant());
  }

  public PageResponse<PracticeAttemptHistoryDto> history(long pageNo, long pageSize) {
    return history(null, null, null, pageNo, pageSize);
  }

  public PageResponse<PracticeAttemptHistoryDto> history(
      Long qualificationId,
      Long subjectId,
      String status,
      long pageNo,
      long pageSize
  ) {
    PracticePrincipal user = currentUserProvider.requireCurrentUser();
    PageResponse<AttemptHistoryProjection> page = repository.findAttemptHistory(
        user.userId(), qualificationId, subjectId, attemptStatus(status), pageNo(pageNo), pageSize(pageSize));
    List<PracticeAttemptHistoryDto> items = page.items().stream()
        .map(item -> new PracticeAttemptHistoryDto(
            item.attemptId(), item.status(), item.sourceType(), item.sourceSpec(), item.questionCount(),
            item.startedAt(), item.submittedAt(), item.durationSeconds(), item.totalScore(), item.maxScore()))
        .toList();
    return new PageResponse<>(items, page.pageNo(), page.pageSize(), page.total(), page.hasNext());
  }

  @Transactional
  public PracticeAnswerSaveResultDto saveAnswer(Long attemptId, Long subQuestionId,
                                                SavePracticeAnswerRequest request) {
    PracticePrincipal user = currentUserProvider.requireCurrentUser();
    Instant now = clock.instant();
    String hash = hash("SAVE_ANSWER", Map.of("attemptId", attemptId, "subQuestionId", subQuestionId,
        "request", request));
    return repository.runIdempotent(mutation(user.userId(), "SAVE_ANSWER", attemptId,
            request.clientMutationId(), hash, now), PracticeAnswerSaveResultDto.class, () -> {
          PracticeAttempt attempt = load(user.userId(), attemptId);
          if (attempt.terminal()) {
            throw conflict("practice attempt is already submitted");
          }
          PracticeQuestionSnapshot question = attempt.questionBySubQuestion(subQuestionId);
          if (question == null) {
            throw notFound("sub question not found in attempt");
          }
          if (question.settlementStatus() == QuestionSettlementStatus.SETTLED) {
            throw conflict("practice question is already settled");
          }
          PracticeQuestionSnapshot.SubQuestion subQuestion = subQuestion(question, subQuestionId);
          PracticeAnswer existing = attempt.answers().get(subQuestionId);
          long currentVersion = existing == null ? 0 : existing.answerVersion();
          if (currentVersion != request.baseVersion()) {
            throw conflict("answer version conflict");
          }
          PracticeAnswerPayload payload = normalizePayload(subQuestion, request.answerPayload());
          PracticeAnswer answer = (existing == null
              ? new PracticeAnswer(subQuestionId, question.questionId(), payload, request.marked(),
                  request.usedSeconds(), 0, GradingStatus.SAVED, now)
              : existing).withSavedPayload(payload, request.marked(), request.usedSeconds(),
                  currentVersion + 1, now);
          Map<Long, PracticeAnswer> answers = new LinkedHashMap<>(attempt.answers());
          answers.put(subQuestionId, answer);
          repository.saveAttempt(attempt.withQuestionsAndAnswers(attempt.questions(), answers,
              attempt.version()));
          return new PracticeAnswerSaveResultDto(attemptId, subQuestionId, answer.answerVersion(), now, false);
        });
  }

  @Transactional
  public PracticeAttemptSubmitResultDto settleQuestion(Long attemptId, Long questionId,
                                                       SettlePracticeQuestionRequest request) {
    PracticePrincipal user = currentUserProvider.requireCurrentUser();
    Instant now = clock.instant();
    String hash = hash("SETTLE_QUESTION", Map.of("attemptId", attemptId, "questionId", questionId,
        "request", request));
    return repository.runIdempotent(mutation(user.userId(), "SETTLE_QUESTION", attemptId,
            request.clientMutationId(), hash, now), PracticeAttemptSubmitResultDto.class, () -> {
          PracticeAttempt attempt = load(user.userId(), attemptId);
          if (attempt.terminal()) {
            throw conflict("practice attempt is already submitted");
          }
          if (attempt.feedbackMode() != FeedbackMode.PER_QUESTION) {
            throw conflict("single question settlement is disabled for this attempt");
          }
          PracticeQuestionSnapshot target = attempt.question(questionId);
          if (target == null) {
            throw notFound("question not found in attempt");
          }
          if (hasSubjectiveSubQuestion(target)) {
            throw new BusinessException("PRACTICE_SUBJECTIVE_REQUIRES_ATTEMPT_SUBMISSION",
                "主观题必须整次提交后查看参考答案和评分点", HttpStatus.CONFLICT);
          }
          if (target.settlementStatus() == QuestionSettlementStatus.SETTLED) {
            return submitResult(attempt, questionId, true);
          }
          if (target.settlementVersion() != request.baseVersion()) {
            throw conflict("question settlement version conflict");
          }
          QuestionGrade grade = grade(target, attempt.answers());
          List<PracticeQuestionSnapshot> questions = attempt.questions().stream()
              .map(question -> question.questionId().equals(questionId)
                  ? question.settle(grade.earnedScore(), grade.correctCount(), grade.wrongCount(),
                      grade.pendingReviewCount())
                  : question)
              .toList();
          Map<Long, PracticeAnswer> answers = new LinkedHashMap<>(attempt.answers());
          answers.putAll(grade.answers());
          PracticeAttempt next = attempt.withQuestionsAndAnswers(questions, answers, attempt.version() + 1);
          repository.saveAttempt(next);
          return submitResult(next, questionId, false);
        });
  }

  @Transactional
  public PracticeAttemptSubmitResultDto submit(Long attemptId, SubmitPracticeAttemptRequest request) {
    PracticePrincipal user = currentUserProvider.requireCurrentUser();
    Instant now = clock.instant();
    String hash = hash("SUBMIT_ATTEMPT", Map.of("attemptId", attemptId, "request", request));
    return repository.runIdempotent(mutation(user.userId(), "SUBMIT_ATTEMPT", attemptId,
            request.clientMutationId(), hash, now), PracticeAttemptSubmitResultDto.class, () -> {
          PracticeAttempt attempt = load(user.userId(), attemptId);
          if (attempt.terminal()) {
            return submitResult(attempt, null, true);
          }
          if (attempt.version() != request.baseVersion()) {
            throw conflict("attempt version conflict");
          }
          SettledAttempt settled = settleAll(attempt);
          PracticeAttempt submitted = attempt.submit(settled.questions(), settled.answers(), now,
              settled.totalScore(), settled.correctCount(), settled.wrongCount(), settled.pendingReviewCount());
          repository.saveAttempt(submitted);
          return submitResult(submitted, null, false);
        });
  }

  public PracticeAttemptExplanationDto explanation(Long attemptId, Long questionId) {
    PracticePrincipal user = currentUserProvider.requireCurrentUser();
    PracticeAttempt attempt = load(user.userId(), attemptId);
    PracticeQuestionSnapshot question = attempt.question(questionId);
    if (question == null) {
      throw notFound("question not found in attempt");
    }
    if (!attempt.terminal() && question.settlementStatus() != QuestionSettlementStatus.SETTLED) {
      throw new BusinessException("PRACTICE_EXPLANATION_NOT_AVAILABLE",
          "题目尚未结算，解析不可见", HttpStatus.FORBIDDEN);
    }
    List<PracticeAttemptExplanationDto.SubQuestionExplanationDto> subQuestions = question.subQuestions().stream()
        .map(subQuestion -> {
          PracticeExplanationSafety.SafeText solution = PracticeExplanationSafety.sanitize(subQuestion.solution());
          SafeExplanationScoringPoints scoringPoints = safeScoringPoints(subQuestion.scoringPoints());
          String solutionStatus = solution.sanitized() || scoringPoints.sanitized()
              ? PracticeExplanationSafety.IMPROVING : PracticeExplanationSafety.READY;
          return new PracticeAttemptExplanationDto.SubQuestionExplanationDto(
              subQuestion.subQuestionId(), PracticeAnswerCanonicalizer.canonicalize(
                  subQuestion.subQuestionType(), subQuestion.standardAnswer()), solution.content(),
              solutionStatus, scoringPoints.items());
        })
        .toList();
    String status = subQuestions.stream()
        .anyMatch(item -> PracticeExplanationSafety.IMPROVING.equals(item.solutionStatus()))
        ? PracticeExplanationSafety.IMPROVING : PracticeExplanationSafety.READY;
    return new PracticeAttemptExplanationDto(attemptId, questionId, status, subQuestions);
  }

  private SettledAttempt settleAll(PracticeAttempt attempt) {
    List<PracticeQuestionSnapshot> settledQuestions = new ArrayList<>();
    Map<Long, PracticeAnswer> answers = new LinkedHashMap<>(attempt.answers());
    BigDecimal totalScore = BigDecimal.ZERO;
    int correct = 0;
    int wrong = 0;
    int pending = 0;
    for (PracticeQuestionSnapshot question : attempt.questions()) {
      QuestionGrade grade = grade(question, answers);
      if (question.settlementStatus() == QuestionSettlementStatus.SETTLED) {
        settledQuestions.add(question);
      } else {
        settledQuestions.add(question.settle(grade.earnedScore(), grade.correctCount(), grade.wrongCount(),
            grade.pendingReviewCount()));
      }
      answers.putAll(grade.answers());
      totalScore = totalScore.add(grade.earnedScore());
      correct += grade.correctCount();
      wrong += grade.wrongCount();
      pending += grade.pendingReviewCount();
    }
    return new SettledAttempt(settledQuestions, answers, totalScore, correct, wrong, pending);
  }

  private QuestionGrade grade(PracticeQuestionSnapshot question, Map<Long, PracticeAnswer> answers) {
    Map<Long, PracticeAnswer> gradedAnswers = new LinkedHashMap<>();
    BigDecimal score = BigDecimal.ZERO;
    int correct = 0;
    int wrong = 0;
    int pending = 0;
    for (PracticeQuestionSnapshot.SubQuestion subQuestion : question.subQuestions()) {
      PracticeAnswer answer = answers.get(subQuestion.subQuestionId());
      if (isSubjective(subQuestion.subQuestionType())) {
        pending++;
        if (answer != null) {
          gradedAnswers.put(subQuestion.subQuestionId(), answer.withGrading(GradingStatus.PENDING_REVIEW));
        }
      } else if (answer != null && PracticeAnswerCanonicalizer.matches(subQuestion.subQuestionType(),
          answer.answerPayload(), subQuestion.standardAnswer())) {
        correct++;
        score = score.add(subQuestion.score());
        gradedAnswers.put(subQuestion.subQuestionId(), answer.withGrading(GradingStatus.CORRECT));
      } else {
        wrong++;
        if (answer != null) {
          gradedAnswers.put(subQuestion.subQuestionId(), answer.withGrading(GradingStatus.WRONG));
        }
      }
    }
    return new QuestionGrade(gradedAnswers, score, correct, wrong, pending);
  }

  private PracticeAttemptDetailDto detail(PracticeAttempt attempt, Instant serverNow) {
    boolean terminal = attempt.terminal();
    List<PracticeAttemptDetailDto.QuestionDto> questions = attempt.questions().stream()
        .sorted(Comparator.comparingInt(PracticeQuestionSnapshot::questionOrder))
        .map(question -> questionDto(attempt, question, terminal))
        .toList();
    return new PracticeAttemptDetailDto(attempt.attemptId(), "普通练习", attempt.qualificationId(),
        attempt.subjectId(), attempt.mode(), attempt.sourceType(), attempt.sourceSpec(), attempt.feedbackMode(),
        attempt.status(), attempt.requestedCount(), attempt.questions().size(), attempt.partial(),
        attempt.startedAt(), attempt.submittedAt(), serverNow, attempt.version(),
        terminal ? attempt.totalScore() : null,
        terminal ? attempt.maxScore() : null,
        terminal ? attempt.correctCount() : null,
        terminal ? attempt.wrongCount() : null,
        terminal ? attempt.pendingReviewCount() : null,
        terminal ? scoreStatus(attempt) : null, questions);
  }

  private PracticeAttemptDetailDto.QuestionDto questionDto(PracticeAttempt attempt,
                                                           PracticeQuestionSnapshot question,
                                                           boolean terminal) {
    boolean reveal = terminal || question.settlementStatus() == QuestionSettlementStatus.SETTLED;
    return new PracticeAttemptDetailDto.QuestionDto(question.questionId(), question.questionVersionId(),
        question.questionOrder(), question.questionType(), question.title(), question.settlementStatus(),
        question.settlementVersion(), reveal ? question.earnedScore() : null,
        question.stems().stream()
            .map(stem -> new PracticeAttemptDetailDto.StemDto(stem.stemId(), stem.stemType(),
                stem.sortOrder(), stem.content()))
            .toList(),
        question.subQuestions().stream().map(subQuestion -> subQuestionDto(attempt, subQuestion, reveal))
            .toList(),
        question.knowledgeBindings().stream()
            .map(binding -> new PracticeAttemptDetailDto.KnowledgeBindingDto(binding.knowledgeType(),
                binding.knowledgeId(), binding.knowledgeName()))
            .toList());
  }

  private PracticeAttemptDetailDto.SubQuestionDto subQuestionDto(PracticeAttempt attempt,
                                                                 PracticeQuestionSnapshot.SubQuestion subQuestion,
                                                                 boolean reveal) {
    PracticeAnswer answer = attempt.answers().get(subQuestion.subQuestionId());
    PracticeAttemptDetailDto.AnswerDto answerDto = answer == null ? null
        : new PracticeAttemptDetailDto.AnswerDto(answer.answerPayload(), answer.marked(),
            answer.answerVersion(), reveal ? authoritativeGradingStatus(subQuestion, answer) : null,
            answer.savedAt());
    PracticeAttemptDetailDto.GradingDto grading = reveal
        ? new PracticeAttemptDetailDto.GradingDto(PracticeAnswerCanonicalizer.canonicalize(
            subQuestion.subQuestionType(), subQuestion.standardAnswer()), subQuestion.score(), null)
        : null;
    PracticeExplanationSafety.SafeText safeSolution = reveal
        ? PracticeExplanationSafety.sanitize(subQuestion.solution())
        : new PracticeExplanationSafety.SafeText(null, null, false, false);
    SafeDetailScoringPoints scoringPoints = reveal
        ? safeScoringPointsForDetail(subQuestion.scoringPoints())
        : new SafeDetailScoringPoints(null, false);
    String solutionStatus = reveal && (safeSolution.sanitized() || scoringPoints.sanitized())
        ? PracticeExplanationSafety.IMPROVING : safeSolution.status();
    return new PracticeAttemptDetailDto.SubQuestionDto(subQuestion.subQuestionId(),
        subQuestion.subQuestionType(), subQuestion.answerFormat(), subQuestion.content(), subQuestion.metadata(),
        subQuestion.options().stream()
            .map(option -> new PracticeAttemptDetailDto.OptionDto(option.optionKey(), option.content(),
                option.sortOrder()))
            .toList(), answerDto, grading, safeSolution.content(), solutionStatus, scoringPoints.items());
  }

  private SafeExplanationScoringPoints safeScoringPoints(
      List<PracticeQuestionSnapshot.ScoringPoint> scoringPoints
  ) {
    List<SafeScoringPoint> safePoints = scoringPoints.stream()
        .map(point -> new SafeScoringPoint(point, PracticeExplanationSafety.sanitize(point.pointText())))
        .filter(point -> point.text().content() != null)
        .toList();
    boolean sanitized = safePoints.size() != scoringPoints.size()
        || safePoints.stream().anyMatch(point -> point.text().sanitized());
    return new SafeExplanationScoringPoints(safePoints.stream()
        .map(point -> new PracticeAttemptExplanationDto.ScoringPointDto(point.source().scoringPointId(),
            point.source().pointCode(), point.text().content(), point.source().score(), point.source().sortOrder()))
        .toList(), sanitized);
  }

  private SafeDetailScoringPoints safeScoringPointsForDetail(
      List<PracticeQuestionSnapshot.ScoringPoint> scoringPoints
  ) {
    List<SafeScoringPoint> safePoints = scoringPoints.stream()
        .map(point -> new SafeScoringPoint(point, PracticeExplanationSafety.sanitize(point.pointText())))
        .filter(point -> point.text().content() != null)
        .toList();
    boolean sanitized = safePoints.size() != scoringPoints.size()
        || safePoints.stream().anyMatch(point -> point.text().sanitized());
    return new SafeDetailScoringPoints(safePoints.stream()
        .map(point -> new PracticeAttemptDetailDto.ScoringPointDto(point.source().scoringPointId(),
            point.source().pointCode(), point.text().content(), point.source().score(), point.source().sortOrder()))
        .toList(), sanitized);
  }

  private record SafeScoringPoint(
      PracticeQuestionSnapshot.ScoringPoint source,
      PracticeExplanationSafety.SafeText text
  ) {
  }

  private record SafeExplanationScoringPoints(
      List<PracticeAttemptExplanationDto.ScoringPointDto> items,
      boolean sanitized
  ) {
  }

  private record SafeDetailScoringPoints(
      List<PracticeAttemptDetailDto.ScoringPointDto> items,
      boolean sanitized
  ) {
  }

  private PracticeAttempt load(Long userId, Long attemptId) {
    return repository.findAttempt(userId, attemptId)
        .orElseThrow(() -> new ResourceNotFoundException("练习记录不存在"));
  }

  private SourceSpec sourceSpec(CreatePracticeAttemptRequest request) {
    if (request.source() == null || request.source().type() == null) {
      throw validation("practice source is required");
    }
    String type = normalizeRequired(request.source().type(), "source.type");
    Map<String, Object> spec = new LinkedHashMap<>();
    switch (type) {
      case "KNOWLEDGE" -> {
        if (request.source().knowledgeType() == null || request.source().knowledgeIds() == null
            || request.source().knowledgeIds().isEmpty()) {
          throw validation("knowledge source requires knowledgeType and knowledgeIds");
        }
        spec.put("knowledgeType", canonicalKnowledgeType(
            normalizeRequired(request.source().knowledgeType(), "knowledgeType")));
        spec.put("knowledgeIds", request.source().knowledgeIds());
      }
      case "YEAR" -> {
        if (request.source().yearNo() == null) {
          throw validation("year source requires yearNo");
        }
        spec.put("yearNo", request.source().yearNo());
      }
      case "PAPER" -> {
        if (request.source().paperId() == null) {
          throw validation("paper source requires paperId");
        }
        spec.put("paperId", request.source().paperId());
      }
      case "DAILY" -> {
        spec.put("daily", true);
        spec.put("practiceDate", LocalDate.now(clock).toString());
      }
      default -> throw validation("unsupported practice source type");
    }
    return new SourceSpec(type, spec);
  }

  private void validateSourceScope(CreatePracticeAttemptRequest request, SourceSpec source) {
    if ("PAPER".equals(source.type())) {
      foundationApplicationService.validatePaperScope(request.qualificationId(), request.subjectId());
      return;
    }
    foundationApplicationService.validateQuestionScope(request.qualificationId(), request.subjectId());
    if (!"KNOWLEDGE".equals(source.type())) {
      return;
    }
    String knowledgeType = (String) source.spec().get("knowledgeType");
    Object ids = source.spec().get("knowledgeIds");
    if (!(ids instanceof List<?> knowledgeIds)) {
      throw validation("knowledge source requires knowledgeIds");
    }
    for (Object id : knowledgeIds) {
      if (!(id instanceof Long knowledgeId) || knowledgeId <= 0) {
        throw validation("knowledgeIds must be positive");
      }
      foundationApplicationService.validateQuestionKnowledge(
          request.qualificationId(), request.subjectId(), knowledgeType, knowledgeId);
    }
  }

  private String canonicalKnowledgeType(String knowledgeType) {
    return switch (knowledgeType.toUpperCase(Locale.ROOT)) {
      case "CHAPTER", "CHAPTER_NODE" -> "CHAPTER_NODE";
      case "SYLLABUS", "SYLLABUS_NODE" -> "SYLLABUS_NODE";
      case "TAG" -> "TAG";
      default -> throw validation("unsupported knowledge type");
    };
  }

  private PracticeAnswerPayload normalizePayload(PracticeQuestionSnapshot.SubQuestion subQuestion,
                                                 PracticeAnswerPayload payload) {
    int branches = (payload.optionKey() == null ? 0 : 1)
        + (payload.optionKeys() == null ? 0 : 1)
        + (payload.value() == null ? 0 : 1)
        + (payload.text() == null ? 0 : 1)
        + (payload.structured() == null ? 0 : 1);
    if (branches != 1) {
      throw validation("answerPayload must match exactly one answer branch");
    }
    String type = normalizeRequired(subQuestion.subQuestionType(), "subQuestionType");
    if ("SINGLE_CHOICE".equals(type)) {
      String key = optionKey(payload.optionKey(), subQuestion);
      return new PracticeAnswerPayload(key, null, null, null);
    }
    if ("MULTI_CHOICE".equals(type)) {
      if (payload.optionKeys() == null || payload.optionKeys().isEmpty()) {
        throw validation("multi choice answer requires optionKeys");
      }
      List<String> keys = payload.optionKeys().stream()
          .map(key -> optionKey(key, subQuestion))
          .sorted()
          .toList();
      if (keys.stream().distinct().count() != keys.size()) {
        throw validation("duplicate option key is not allowed");
      }
      return new PracticeAnswerPayload(null, keys, null, null);
    }
    if ("JUDGE".equals(type)) {
      if (payload.value() == null) {
        throw validation("judge answer requires boolean value");
      }
      return new PracticeAnswerPayload(null, null, payload.value(), null);
    }
    if ("JSON".equalsIgnoreCase(subQuestion.answerFormat())) {
      if (payload.structured() == null || payload.structured().isEmpty()) {
        throw validation("structured answer requires a non-empty JSON payload");
      }
      Object schemaVersion = payload.structured().get("schemaVersion");
      if (!(schemaVersion instanceof String value) || value.isBlank()) {
        throw validation("structured answer requires schemaVersion");
      }
      validateStructuredAnswer(type, payload.structured());
      return new PracticeAnswerPayload(null, null, null, null, payload.structured());
    }
    if (payload.text() == null || payload.text().trim().isEmpty()) {
      throw validation("subjective answer requires text");
    }
    return new PracticeAnswerPayload(null, null, null, payload.text().trim());
  }

  private void validateStructuredAnswer(String type, Map<String, Object> structured) {
    if (("FLOWCHART".equals(type) || "UML_GRAPH".equals(type))
        && (!(structured.get("nodes") instanceof List<?>) || !(structured.get("edges") instanceof List<?>))) {
      throw validation("diagram answer requires nodes and edges arrays");
    }
    if ("METRIC_CONFIGURATION".equals(type) && !(structured.get("metrics") instanceof List<?>)) {
      throw validation("metric answer requires a metrics array");
    }
    if (("CODE_GAP".equals(type) || "FILL_BLANK".equals(type)
        || "REQUIREMENT_SPEC".equals(type) || "ARCHITECTURE_DESIGN".equals(type)
        || "SERVICE_DESIGN".equals(type)) && !(structured.get("content") instanceof String)) {
      throw validation("structured answer requires content text");
    }
  }

  private String optionKey(String raw, PracticeQuestionSnapshot.SubQuestion subQuestion) {
    if (raw == null || raw.trim().isEmpty()) {
      throw validation("optionKey is required");
    }
    String key = raw.trim().toUpperCase(Locale.ROOT);
    boolean exists = subQuestion.options().stream().anyMatch(option -> option.optionKey().equals(key));
    if (!exists) {
      throw validation("optionKey does not belong to this sub question");
    }
    return key;
  }

  private boolean isSubjective(String type) {
    String normalized = normalizeRequired(type, "subQuestionType");
    return "SHORT_ANSWER".equals(normalized) || "ESSAY_WRITING".equals(normalized)
        || "CALCULATION".equals(normalized) || "CODE_GAP".equals(normalized)
        || "FILL_BLANK".equals(normalized) || "FLOWCHART".equals(normalized)
        || "UML_GRAPH".equals(normalized) || "REQUIREMENT_SPEC".equals(normalized)
        || "ARCHITECTURE_DESIGN".equals(normalized) || "SERVICE_DESIGN".equals(normalized)
        || "METRIC_CONFIGURATION".equals(normalized);
  }

  private boolean hasSubjectiveSubQuestion(PracticeQuestionSnapshot question) {
    return question.subQuestions().stream().anyMatch(item -> isSubjective(item.subQuestionType()));
  }

  private GradingStatus authoritativeGradingStatus(PracticeQuestionSnapshot.SubQuestion subQuestion,
                                                    PracticeAnswer answer) {
    if (isSubjective(subQuestion.subQuestionType())) {
      return GradingStatus.PENDING_REVIEW;
    }
    return PracticeAnswerCanonicalizer.matches(subQuestion.subQuestionType(), answer.answerPayload(),
        subQuestion.standardAnswer()) ? GradingStatus.CORRECT : GradingStatus.WRONG;
  }

  private PracticeQuestionSnapshot.SubQuestion subQuestion(PracticeQuestionSnapshot question, Long subQuestionId) {
    return question.subQuestions().stream()
        .filter(item -> item.subQuestionId().equals(subQuestionId))
        .findFirst()
        .orElseThrow(() -> notFound("sub question not found"));
  }

  private MutationCommand mutation(Long userId, String operation, Long scope, UUID clientMutationId,
                                   String requestHash, Instant now) {
    if (clientMutationId == null) {
      throw validation("clientMutationId is required");
    }
    return new MutationCommand(userId, operation, scope, clientMutationId, requestHash, now);
  }

  private PracticeAttemptSubmitResultDto submitResult(PracticeAttempt attempt, Long questionId, boolean replay) {
    boolean terminal = attempt.terminal();
    return new PracticeAttemptSubmitResultDto(attempt.attemptId(), questionId, attempt.status(),
        terminal ? attempt.totalScore() : null,
        terminal ? attempt.maxScore() : null,
        terminal ? attempt.correctCount() : null,
        terminal ? attempt.wrongCount() : null,
        terminal ? attempt.pendingReviewCount() : null,
        attempt.version(), attempt.submittedAt(), replay);
  }

  private String scoreStatus(PracticeAttempt attempt) {
    return attempt.pendingReviewCount() > 0 ? "PENDING_REVIEW" : "GRADED";
  }

  private FeedbackMode feedbackMode(String value) {
    try {
      return FeedbackMode.valueOf(normalizeRequired(value, "feedbackMode"));
    } catch (IllegalArgumentException exception) {
      throw validation("unsupported feedbackMode");
    }
  }

  private AttemptStatus attemptStatus(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return AttemptStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw validation("unsupported attempt status");
    }
  }

  private long pageNo(long pageNo) {
    return Math.max(1, pageNo);
  }

  private long pageSize(long pageSize) {
    return pageSize < 1 ? 20 : Math.min(pageSize, 100);
  }

  private String normalizeRequired(String value, String field) {
    if (value == null || value.trim().isEmpty()) {
      throw validation(field + " is required");
    }
    return value.trim().toUpperCase(Locale.ROOT);
  }

  private String hash(String operation, Object request) {
    try {
      String json = objectMapper.writeValueAsString(Map.of("operation", operation, "request", request));
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("cannot hash practice request", exception);
    }
  }

  private BusinessException validation(String message) {
    return new BusinessException(ErrorCode.VALIDATION_ERROR, message, HttpStatus.BAD_REQUEST);
  }

  private BusinessException conflict(String message) {
    return new BusinessException("CONFLICT", message, HttpStatus.CONFLICT);
  }

  private BusinessException notFound(String message) {
    return new ResourceNotFoundException(message);
  }

  private record SourceSpec(String type, Map<String, Object> spec) {
  }

  private record QuestionGrade(Map<Long, PracticeAnswer> answers, BigDecimal earnedScore, int correctCount,
                               int wrongCount, int pendingReviewCount) {
  }

  private record SettledAttempt(List<PracticeQuestionSnapshot> questions, Map<Long, PracticeAnswer> answers,
                                BigDecimal totalScore, int correctCount, int wrongCount,
                                int pendingReviewCount) {
  }
}
