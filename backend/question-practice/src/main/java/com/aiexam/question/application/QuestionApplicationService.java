package com.aiexam.question.application;

import com.aiexam.ai.application.AiTaskApplicationService;
import com.aiexam.ai.dto.AiTaskStatusDto;
import com.aiexam.common.api.PageResponse;
import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ResourceNotFoundException;
import com.aiexam.foundation.application.FoundationApplicationService;
import com.aiexam.question.dto.QuestionAiExplanationTaskDto;
import com.aiexam.question.dto.QuestionDetailDto;
import com.aiexam.question.dto.QuestionExplanationDto;
import com.aiexam.question.dto.QuestionSummaryDto;
import com.aiexam.question.dto.request.QuestionCreateRequest;
import com.aiexam.question.dto.request.QuestionUpdateRequest;
import com.aiexam.question.repository.QuestionDetailRepository;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestionApplicationService {
  private static final AtomicLong ID_SEQUENCE = new AtomicLong();
  private static final Set<String> QUESTION_TYPES = Set.of("SINGLE_CHOICE", "MULTI_CHOICE", "JUDGE", "CASE", "ESSAY",
      "CODE_GAP", "FILL_BLANK", "FLOWCHART", "UML_GRAPH", "REQUIREMENT_SPEC", "ARCHITECTURE_DESIGN", "SERVICE_DESIGN", "METRIC_CONFIGURATION");
  private static final Set<String> SUB_QUESTION_TYPES = Set.of(
      "SINGLE_CHOICE", "MULTI_CHOICE", "JUDGE", "CALCULATION", "SHORT_ANSWER", "ESSAY_WRITING", "CODE_GAP", "FILL_BLANK",
      "FLOWCHART", "UML_GRAPH", "REQUIREMENT_SPEC", "ARCHITECTURE_DESIGN", "SERVICE_DESIGN", "METRIC_CONFIGURATION");
  private static final Set<String> ANSWER_FORMATS = Set.of("OPTION", "BOOLEAN", "TEXT", "NUMBER", "JSON");
  private static final Set<String> GRADING_MODES = Set.of("AUTO", "MANUAL", "AI_ASSISTED");
  private static final Set<String> KNOWLEDGE_TYPES = Set.of("SYLLABUS", "CHAPTER", "TAG");
  private static final Set<String> TARGET_TYPES = Set.of("QUESTION", "SUB_QUESTION");
  private static final Set<String> CHOICE_TYPES = Set.of("SINGLE_CHOICE", "MULTI_CHOICE");

  private final QuestionDetailRepository questionDetailRepository;
  private final FoundationApplicationService foundationApplicationService;
  private final AiTaskApplicationService aiTaskApplicationService;

  public QuestionApplicationService(
      QuestionDetailRepository questionDetailRepository,
      FoundationApplicationService foundationApplicationService,
      AiTaskApplicationService aiTaskApplicationService
  ) {
    this.questionDetailRepository = questionDetailRepository;
    this.foundationApplicationService = foundationApplicationService;
    this.aiTaskApplicationService = aiTaskApplicationService;
  }

  public QuestionDetailDto getQuestionDetail(Long questionId) {
    return questionDetailRepository.findPublishedDetail(questionId)
        .orElseThrow(() -> new ResourceNotFoundException("题目不存在"));
  }

  public PageResponse<QuestionSummaryDto> listPublishedQuestions(long pageNo, long pageSize) {
    return questionDetailRepository.findPublishedQuestions(pageNo(pageNo), pageSize(pageSize));
  }

  public PageResponse<QuestionSummaryDto> listAdminQuestions(long pageNo, long pageSize) {
    return questionDetailRepository.findAdminQuestions(pageNo(pageNo), pageSize(pageSize));
  }

  public QuestionExplanationDto getExplanation(Long questionId) {
    throw attemptContextRequired();
  }

  public QuestionAiExplanationTaskDto triggerAiExplanation(Long questionId) {
    throw attemptContextRequired();
  }

  @Transactional
  public QuestionDetailDto createQuestion(QuestionCreateRequest request) {
    validateCreateRequest(request);
    Long questionId = nextId();
    questionDetailRepository.insertQuestion(questionId, request, "DRAFT");
    for (QuestionCreateRequest.StemRequest stem : request.stems()) {
      questionDetailRepository.insertStem(nextId(), questionId, stem);
    }
    for (QuestionCreateRequest.SubQuestionRequest subQuestion : request.subQuestions()) {
      Long subQuestionId = nextId();
      questionDetailRepository.insertSubQuestion(subQuestionId, questionId, subQuestion);
      for (QuestionCreateRequest.OptionRequest option : safe(subQuestion.options())) {
        questionDetailRepository.insertOption(nextId(), subQuestionId, option);
      }
      for (QuestionCreateRequest.AnswerKeyRequest answerKey : safe(subQuestion.answerKeys())) {
        questionDetailRepository.insertAnswerKey(nextId(), subQuestionId, answerKey);
      }
      for (QuestionCreateRequest.SolutionRequest solution : safe(subQuestion.solutions())) {
        questionDetailRepository.insertSolution(nextId(), questionId, subQuestionId, solution);
      }
      for (QuestionCreateRequest.ScoringRuleRequest scoringRule : safe(subQuestion.scoringRules())) {
        questionDetailRepository.insertScoringRule(nextId(), subQuestionId, scoringRule);
      }
      for (QuestionCreateRequest.KnowledgeBindingRequest binding : safe(subQuestion.knowledgeBindings())) {
        questionDetailRepository.insertKnowledgeBinding(
            nextId(),
            questionId,
            subQuestionId,
            "SUB_QUESTION",
            subQuestionId,
            binding);
      }
    }
    for (QuestionCreateRequest.KnowledgeBindingRequest binding : safe(request.knowledgeBindings())) {
      questionDetailRepository.insertKnowledgeBinding(nextId(), questionId, null, "QUESTION", questionId, binding);
    }
    return adminDetail(questionId);
  }

  @Transactional
  public QuestionDetailDto updateQuestion(Long questionId, QuestionUpdateRequest request) {
    requireQuestion(questionId);
    questionDetailRepository.updateQuestion(questionId, request);
    return adminDetail(questionId);
  }

  @Transactional
  public QuestionDetailDto addStem(Long questionId, QuestionCreateRequest.StemRequest request) {
    requireQuestion(questionId);
    questionDetailRepository.insertStem(nextId(), questionId, request);
    return adminDetail(questionId);
  }

  @Transactional
  public QuestionDetailDto addSubQuestion(Long questionId, QuestionCreateRequest.SubQuestionRequest request) {
    QuestionSummaryDto question = requireQuestion(questionId);
    validateSubQuestion(request, question.qualificationId(), question.subjectId());
    Long subQuestionId = nextId();
    questionDetailRepository.insertSubQuestion(subQuestionId, questionId, request);
    for (QuestionCreateRequest.OptionRequest option : safe(request.options())) {
      questionDetailRepository.insertOption(nextId(), subQuestionId, option);
    }
    for (QuestionCreateRequest.AnswerKeyRequest answerKey : safe(request.answerKeys())) {
      questionDetailRepository.insertAnswerKey(nextId(), subQuestionId, answerKey);
    }
    for (QuestionCreateRequest.SolutionRequest solution : safe(request.solutions())) {
      questionDetailRepository.insertSolution(nextId(), questionId, subQuestionId, solution);
    }
    for (QuestionCreateRequest.ScoringRuleRequest scoringRule : safe(request.scoringRules())) {
      questionDetailRepository.insertScoringRule(nextId(), subQuestionId, scoringRule);
    }
    return adminDetail(questionId);
  }

  @Transactional
  public QuestionDetailDto addKnowledgeBinding(Long questionId, QuestionCreateRequest.KnowledgeBindingRequest request) {
    QuestionSummaryDto question = requireQuestion(questionId);
    validateKnowledgeBinding(request, question.qualificationId(), question.subjectId());
    String targetType = request.targetType() == null ? "QUESTION" : normalize(request.targetType());
    Long targetId = request.targetId() == null ? questionId : request.targetId();
    Long subQuestionId = null;
    if ("QUESTION".equals(targetType) && !questionId.equals(targetId)) {
      throw validation("题目级知识点绑定目标必须是当前题目");
    }
    if ("SUB_QUESTION".equals(targetType)) {
      subQuestionId = targetId;
      if (!questionDetailRepository.subQuestionBelongsToQuestion(subQuestionId, questionId)) {
        throw validation("小题级知识点绑定目标必须属于当前题目");
      }
    }
    questionDetailRepository.insertKnowledgeBinding(nextId(), questionId, subQuestionId, targetType, targetId, request);
    return adminDetail(questionId);
  }

  @Transactional
  public QuestionDetailDto publishQuestion(Long questionId) {
    QuestionSummaryDto question = requireQuestion(questionId);
    foundationApplicationService.validateQuestionScope(question.qualificationId(), question.subjectId());
    for (QuestionCreateRequest.KnowledgeBindingRequest binding : questionDetailRepository.findKnowledgeBindings(questionId)) {
      validateKnowledgeBinding(binding, question.qualificationId(), question.subjectId());
    }
    validatePublish(questionId);
    questionDetailRepository.publish(questionId);
    return adminDetail(questionId);
  }

  private QuestionDetailDto adminDetail(Long questionId) {
    return questionDetailRepository.findAdminDetail(questionId)
        .orElseThrow(() -> new ResourceNotFoundException("题目不存在"));
  }

  private QuestionSummaryDto requireQuestion(Long questionId) {
    return questionDetailRepository.findQuestionSummary(questionId)
        .orElseThrow(() -> new ResourceNotFoundException("题目不存在"));
  }

  private void validateCreateRequest(QuestionCreateRequest request) {
    foundationApplicationService.validateQuestionScope(request.qualificationId(), request.subjectId());
    requireEnum(QUESTION_TYPES, request.questionType(), "不支持的题目类型");
    for (QuestionCreateRequest.SubQuestionRequest subQuestion : request.subQuestions()) {
      validateSubQuestion(subQuestion, request.qualificationId(), request.subjectId());
    }
    for (QuestionCreateRequest.KnowledgeBindingRequest binding : safe(request.knowledgeBindings())) {
      validateKnowledgeBinding(binding, request.qualificationId(), request.subjectId());
    }
  }

  private void validateSubQuestion(QuestionCreateRequest.SubQuestionRequest subQuestion, Long qualificationId, Long subjectId) {
    String subQuestionType = normalize(subQuestion.subQuestionType());
    requireEnum(SUB_QUESTION_TYPES, subQuestionType, "不支持的子题类型");
    requireEnum(ANSWER_FORMATS, subQuestion.answerFormat(), "不支持的答案格式");
    requireEnum(GRADING_MODES, subQuestion.gradingMode(), "不支持的评分模式");
    if (CHOICE_TYPES.contains(subQuestionType) && safe(subQuestion.options()).isEmpty()) {
      throw validation("选择题必须包含选项");
    }
    if ("AUTO".equals(normalize(subQuestion.gradingMode())) && safe(subQuestion.answerKeys()).isEmpty()) {
      throw validation("自动判分题必须包含标准答案");
    }
    for (QuestionCreateRequest.KnowledgeBindingRequest binding : safe(subQuestion.knowledgeBindings())) {
      validateKnowledgeBinding(binding, qualificationId, subjectId);
    }
  }

  private void validateKnowledgeBinding(QuestionCreateRequest.KnowledgeBindingRequest binding, Long qualificationId, Long subjectId) {
    if (binding.targetType() != null) {
      requireEnum(TARGET_TYPES, binding.targetType(), "不支持的绑定目标类型");
    }
    requireEnum(KNOWLEDGE_TYPES, binding.knowledgeType(), "不支持的知识点类型");
    foundationApplicationService.validateQuestionKnowledge(
        qualificationId,
        subjectId,
        binding.knowledgeType(),
        binding.knowledgeId());
  }

  private void validatePublish(Long questionId) {
    if (questionDetailRepository.countStems(questionId) < 1) {
      throw validation("发布前至少需要一个题干或材料");
    }
    List<QuestionDetailRepository.SubQuestionValidationRow> rows =
        questionDetailRepository.findSubQuestionValidationRows(questionId);
    if (rows.isEmpty()) {
      throw validation("发布前至少需要一个子题");
    }
    for (QuestionDetailRepository.SubQuestionValidationRow row : rows) {
      if (CHOICE_TYPES.contains(normalize(row.subQuestionType())) && row.optionCount() < 1) {
        throw validation("选择题必须包含选项");
      }
      if ("AUTO".equals(normalize(row.gradingMode())) && row.answerKeyCount() < 1) {
        throw validation("自动判分题必须包含标准答案");
      }
    }
  }

  private void requireEnum(Set<String> allowed, String value, String message) {
    if (!allowed.contains(normalize(value))) {
      throw validation(message);
    }
  }

  private BusinessException validation(String message) {
    return new BusinessException("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
  }

  private BusinessException attemptContextRequired() {
    return new BusinessException("PRACTICE_ATTEMPT_CONTEXT_REQUIRED",
        "解析仅可通过已结算练习会话访问", HttpStatus.FORBIDDEN);
  }

  private <T> List<T> safe(List<T> items) {
    return items == null ? List.of() : items;
  }

  private String normalize(String value) {
    return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
  }

  private long pageNo(long pageNo) {
    return pageNo < 1 ? 1 : pageNo;
  }

  private long pageSize(long pageSize) {
    if (pageSize < 1) {
      return 20;
    }
    return Math.min(pageSize, 100);
  }

  private Long nextId() {
    return System.currentTimeMillis() * 100_000 + Math.floorMod(ID_SEQUENCE.getAndIncrement(), 100_000);
  }
}
