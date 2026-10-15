package com.aiexam.essay.runtime;

import com.aiexam.ai.port.AiTaskSubmissionPort;
import com.aiexam.ai.port.AiTaskRef;
import com.aiexam.ai.port.AiTaskSubmitCommand;
import com.aiexam.ai.port.CapabilityQueryPort;
import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ResourceNotFoundException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;

public class EssayAiRuntimeApplicationService {
  private final EssayAiRuntimeRepository repository;
  private final AiTaskSubmissionPort aiTaskSubmissionPort;
  private final CapabilityQueryPort capabilityQueryPort;
  private final EssayAiFeatureSwitch featureSwitch;
  private final EssaySnapshotCanonicalizer snapshotCanonicalizer;
  private final EssayAiRuntimeAuditPort auditPort;

  public EssayAiRuntimeApplicationService(
      EssayAiRuntimeRepository repository,
      AiTaskSubmissionPort aiTaskSubmissionPort,
      CapabilityQueryPort capabilityQueryPort,
      EssayAiFeatureSwitch featureSwitch,
      EssaySnapshotCanonicalizer snapshotCanonicalizer,
      EssayAiRuntimeAuditPort auditPort
  ) {
    this.repository = repository;
    this.aiTaskSubmissionPort = aiTaskSubmissionPort;
    this.capabilityQueryPort = capabilityQueryPort;
    this.featureSwitch = featureSwitch;
    this.snapshotCanonicalizer = snapshotCanonicalizer;
    this.auditPort = auditPort;
  }

  public EssayAiTaskDto generateOutline(EssayCurrentUser currentUser, EssayOutlineGenerateRequest request) {
    Long userId = requirePermission(currentUser, "essay:workspace:view");
    requireEnabled();
    EssayDraftRuntimeView draft = requireDraft(userId, request.draftId());
    ObjectNode snapshot = object();
    snapshot.put("draftId", draft.draftId());
    snapshot.put("draftVersion", draft.version());
    snapshot.put("qualificationId", draft.qualificationId());
    ArrayNode sections = snapshot.putArray("requiredSections");
    request.requiredSections().forEach(sections::add);
    snapshot.put("topic", nullToEmpty(request.topic()));
    AiTaskRef task = submit(
        userId,
        "ESSAY_DRAFT",
        draft.draftId(),
        "ESSAY_OUTLINE_GENERATE",
        request.requestId(),
        snapshotCanonicalizer.requestHash("POST", "/api/v1/essay/drafts/" + draft.draftId() + "/outline", snapshot),
        snapshotCanonicalizer.canonicalJson(snapshot));
    return task(task, "QUEUED");
  }

  public EssayAiTaskDto generateParagraph(EssayCurrentUser currentUser, EssayParagraphGenerateRequest request) {
    Long userId = requirePermission(currentUser, "essay:write");
    requireEnabled();
    EssayDraftRuntimeView draft = requireDraft(userId, request.draftId());
    if (request.baseVersion() != draft.version()) {
      throw new BusinessException("ESSAY_DRAFT_VERSION_CONFLICT", "草稿版本冲突", HttpStatus.CONFLICT);
    }
    if (!draft.paragraphIds().contains(request.paragraphId())) {
      throw new ResourceNotFoundException("论文段落不存在");
    }
    ObjectNode snapshot = object();
    snapshot.put("baseVersion", request.baseVersion());
    snapshot.put("draftId", draft.draftId());
    snapshot.put("instruction", nullToEmpty(request.instruction()));
    snapshot.put("paragraphId", nullToEmpty(request.paragraphId()));
    AiTaskRef task = submit(
        userId,
        "ESSAY_DRAFT",
        draft.draftId(),
        "ESSAY_PARAGRAPH_GENERATE",
        request.requestId(),
        snapshotCanonicalizer.requestHash("POST", "/api/v1/essay/drafts/" + draft.draftId() + "/paragraphs/" + request.paragraphId(), snapshot),
        snapshotCanonicalizer.canonicalJson(snapshot));
    return task(task, "QUEUED");
  }

  public EssayReviewTaskDto submitReview(EssayCurrentUser currentUser, EssayReviewSubmitRequest request) {
    Long userId = requirePermission(currentUser, "essay:review");
    requireEnabled();
    EssayDraftRuntimeView draft = requireDraft(userId, request.draftId());
    if (!draft.qualificationId().equals(request.qualificationId())) {
      throw new BusinessException("ESSAY_REVIEW_SCOPE_INVALID", "批改资格与草稿不一致", HttpStatus.UNPROCESSABLE_ENTITY);
    }
    if (request.rubricId() == null || request.content() == null || request.content().isBlank()) {
      throw new BusinessException("ESSAY_REVIEW_REQUEST_INVALID", "批改规则和正文不能为空", HttpStatus.BAD_REQUEST);
    }
    EssayReviewTaskDto reviewTask = repository.createReviewTask(userId, draft.draftId());
    ObjectNode snapshot = object();
    snapshot.put("content", request.content());
    snapshot.put("draftId", draft.draftId());
    snapshot.put("qualificationId", request.qualificationId());
    snapshot.put("reviewTaskId", reviewTask.reviewTaskId());
    snapshot.put("rubricId", request.rubricId());
    try {
      AiTaskRef task = submit(
          userId,
          "ESSAY_REVIEW_TASK",
          reviewTask.reviewTaskId(),
          "ESSAY_REVIEW",
          request.requestId(),
          snapshotCanonicalizer.requestHash("POST", "/api/v1/essay/review-tasks", snapshot),
          snapshotCanonicalizer.canonicalJson(snapshot));
      return repository.bindReviewAiTask(userId, reviewTask.reviewTaskId(), task.taskId());
    } catch (BusinessException ex) {
      markReviewTaskFailedPreserving(userId, reviewTask.reviewTaskId(), ex.code(), ex);
      throw ex;
    } catch (RuntimeException ex) {
      markReviewTaskFailedPreserving(userId, reviewTask.reviewTaskId(), "AI_TASK_UNAVAILABLE", ex);
      throw ex;
    }
  }

  public EssayReviewResultDto getReviewResult(EssayCurrentUser currentUser, Long reviewTaskId) {
    Long userId = requirePermission(currentUser, "essay:review");
    repository.findReviewTask(userId, reviewTaskId)
        .orElseThrow(() -> new ResourceNotFoundException("批改任务不存在"));
    return repository.findReviewResult(userId, reviewTaskId)
        .orElseThrow(() -> new BusinessException(
            "ESSAY_REVIEW_RESULT_NOT_READY",
            "批改结果尚未完成",
            HttpStatus.NOT_FOUND));
  }

  public EssayRuntimePage<EssayReviewResultDto> listReviewHistory(EssayCurrentUser currentUser, int pageNo, int pageSize) {
    Long userId = requirePermission(currentUser, "essay:review");
    return repository.listReviewHistory(userId, Math.max(pageNo, 1), Math.min(Math.max(pageSize, 1), 100));
  }

  public EssayAiTaskDto askIssueQuestion(EssayCurrentUser currentUser, EssayIssueQuestionRequest request) {
    Long userId = requirePermission(currentUser, "essay:review");
    requireEnabled();
    EssayReviewTaskDto task = repository.findReviewTask(userId, request.reviewTaskId())
        .orElseThrow(() -> new ResourceNotFoundException("批改任务不存在"));
    EssayReviewResultDto result = repository.findReviewResult(userId, request.reviewTaskId())
        .filter(value -> value.resultId().equals(request.resultId()))
        .orElseThrow(() -> new ResourceNotFoundException("批改结果不存在"));
    boolean issueExists = result.issues().stream().anyMatch(issue -> issue.issueId().equals(request.issueId()));
    if (!issueExists) {
      throw new ResourceNotFoundException("批改问题不存在");
    }
    ObjectNode snapshot = object();
    snapshot.put("issueId", request.issueId());
    snapshot.put("question", request.question());
    snapshot.put("resultId", result.resultId());
    snapshot.put("reviewTaskId", task.reviewTaskId());
    AiTaskRef aiTask = submit(
        userId,
        "ESSAY_REVIEW_ISSUE",
        task.reviewTaskId(),
        "ESSAY_REVIEW_ISSUE_QUESTION",
        request.requestId(),
        snapshotCanonicalizer.requestHash("POST", "/api/v1/essay/review-tasks/" + task.reviewTaskId() + "/issues/" + request.issueId() + "/questions", snapshot),
        snapshotCanonicalizer.canonicalJson(snapshot));
    return task(aiTask, "QUEUED");
  }

  public EssayAiCapabilityStatusDto capabilityStatus(EssayCurrentUser currentUser) {
    requireUserId(currentUser);
    return new EssayAiCapabilityStatusDto(
        featureSwitch.enabled(),
        capability(currentUser, "essay:workspace:view", "ESSAY_OUTLINE_GENERATE"),
        capability(currentUser, "essay:write", "ESSAY_PARAGRAPH_GENERATE"),
        capability(currentUser, "essay:review", "ESSAY_REVIEW"),
        capability(currentUser, "essay:review", "ESSAY_REVIEW_ISSUE_QUESTION"));
  }

  private AiTaskRef submit(
      Long userId,
      String bizType,
      Long bizId,
      String scenario,
      String requestId,
      String requestHash,
      String inputSnapshotJson
  ) {
    return aiTaskSubmissionPort.submit(new AiTaskSubmitCommand(userId, bizType, bizId, scenario, requestId, requestHash, inputSnapshotJson));
  }

  private EssayAiTaskDto task(AiTaskRef ref, String businessStatus) {
    return new EssayAiTaskDto(
        ref.taskId(),
        ref.bizType(),
        ref.bizId(),
        ref.scenario(),
        businessStatus,
        ref.createdAt());
  }

  private EssayDraftRuntimeView requireDraft(Long userId, Long draftId) {
    return repository.findDraft(userId, draftId)
        .orElseThrow(() -> new ResourceNotFoundException("论文草稿不存在"));
  }

  private Long requireUserId(EssayCurrentUser currentUser) {
    if (currentUser == null || currentUser.userId() == null) {
      throw new BusinessException("AUTH_SESSION_EXPIRED", "未登录", HttpStatus.UNAUTHORIZED);
    }
    return currentUser.userId();
  }

  private Long requirePermission(EssayCurrentUser currentUser, String permission) {
    Long userId = requireUserId(currentUser);
    if (!currentUser.permissions().contains(permission)) {
      throw new BusinessException("FORBIDDEN", "无权访问论文AI能力", HttpStatus.FORBIDDEN);
    }
    return userId;
  }

  private void requireEnabled() {
    if (!featureSwitch.enabled()) {
      throw new BusinessException("ESSAY_AI_RUNTIME_DISABLED", "AI论文运行时能力未开放", HttpStatus.FORBIDDEN);
    }
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private ObjectNode object() {
    return JsonNodeFactory.instance.objectNode();
  }

  private String capability(EssayCurrentUser currentUser, String permission, String scenario) {
    if (!featureSwitch.enabled()) {
      return "DISABLED";
    }
    if (!currentUser.permissions().contains(permission)) {
      return "FORBIDDEN";
    }
    if (!capabilityQueryPort.available(scenario)) {
      return "UNAVAILABLE";
    }
    return "AVAILABLE";
  }

  private void markReviewTaskFailedPreserving(Long userId, Long reviewTaskId, String originalCode, RuntimeException original) {
    try {
      repository.markReviewTaskFailed(userId, reviewTaskId, originalCode, original.getMessage());
    } catch (RuntimeException compensationFailure) {
      try {
        auditPort.recordReviewTaskCompensationFailure(userId, reviewTaskId, originalCode, compensationFailure);
      } catch (RuntimeException ignored) {
        // Preserve the original exception; audit failure must not mask the submit failure.
      }
    }
  }
}
