package com.aiexam.essay.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.ai.port.AiTaskSubmissionPort;
import com.aiexam.ai.port.AiTaskRef;
import com.aiexam.ai.port.AiTaskSubmitCommand;
import com.aiexam.ai.port.CapabilityQueryPort;
import com.aiexam.common.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EssayAiRuntimeApplicationServiceTest {
  private InMemoryEssayAiRuntimeRepository repository;
  private RecordingAiRuntimePort aiRuntimePort;
  private RecordingAuditPort auditPort;
  private JacksonJcsEssaySnapshotCanonicalizer canonicalizer;
  private MutableEssayAiFeatureSwitch featureSwitch;
  private EssayAiRuntimeApplicationService service;

  @BeforeEach
  void setUp() {
    repository = new InMemoryEssayAiRuntimeRepository();
    aiRuntimePort = new RecordingAiRuntimePort();
    auditPort = new RecordingAuditPort();
    canonicalizer = new JacksonJcsEssaySnapshotCanonicalizer(new ObjectMapper());
    featureSwitch = new MutableEssayAiFeatureSwitch(true);
    service = new EssayAiRuntimeApplicationService(repository, aiRuntimePort, aiRuntimePort, featureSwitch, canonicalizer, auditPort);
    repository.saveDraft(new EssayDraftRuntimeView(101L, 7L, 1L, "论项目进度管理", "正文", 3L, Set.of("P-1")));
  }

  @Test
  void outlineGenerationSubmitsAiTaskThroughCapabilityPortForCurrentUserDraft() {
    EssayAiTaskDto task = service.generateOutline(
        user("essay:workspace:view"),
        new EssayOutlineGenerateRequest("req-outline-1", 101L, "进度管理", List.of("项目背景", "过程实践")));

    assertThat(task.status()).isEqualTo("QUEUED");
    assertThat(task.scenario()).isEqualTo("ESSAY_OUTLINE_GENERATE");
    assertThat(aiRuntimePort.submissions()).hasSize(1);
    AiTaskSubmitCommand command = aiRuntimePort.submissions().get(0);
    assertThat(command.userId()).isEqualTo(7L);
    assertThat(command.bizType()).isEqualTo("ESSAY_DRAFT");
    assertThat(command.bizId()).isEqualTo(101L);
    assertThat(command.requestId()).isEqualTo("req-outline-1");
    assertThat(command.requestHash()).matches("[0-9a-f]{64}");
    assertThat(command.inputSnapshotJson()).contains("\"draftId\":101");
    assertThat(command.inputSnapshotJson()).contains("\"requiredSections\":[\"项目背景\",\"过程实践\"]");
  }

  @Test
  void snapshotCanonicalizationHandlesEscapesArraysAndFieldOrder() {
    EssayAiTaskDto task = service.generateOutline(
        user("essay:workspace:view"),
        new EssayOutlineGenerateRequest(
            "req-outline-escape",
            101L,
            "进度\"管理\n控制",
            List.of("A,B", "A", "B")));

    AiTaskSubmitCommand command = aiRuntimePort.submissions().get(0);
    assertThat(task.status()).isEqualTo("QUEUED");
    assertThat(command.inputSnapshotJson()).contains("\\\"管理\\n控制");
    assertThat(command.inputSnapshotJson()).contains("\"requiredSections\":[\"A,B\",\"A\",\"B\"]");

    ObjectNode equivalent = JsonNodeFactory.instance.objectNode();
    equivalent.put("topic", "进度\"管理\n控制");
    equivalent.put("qualificationId", 1L);
    equivalent.put("draftVersion", 3L);
    equivalent.put("draftId", 101L);
    equivalent.putArray("requiredSections").add("A,B").add("A").add("B");
    String equivalentHash = canonicalizer.requestHash("POST", "/api/v1/essay/drafts/101/outline", equivalent);
    assertThat(command.requestHash()).isEqualTo(equivalentHash);
  }

  @Test
  void paragraphGenerationUsesCurrentDraftAndNeverCallsModelSdkDirectly() {
    EssayAiTaskDto task = service.generateParagraph(
        user("essay:write"),
        new EssayParagraphGenerateRequest("req-paragraph-1", 101L, "P-1", 3L, "请强化计划控制段落"));

    assertThat(task.scenario()).isEqualTo("ESSAY_PARAGRAPH_GENERATE");
    assertThat(aiRuntimePort.submissions()).extracting(AiTaskSubmitCommand::scenario)
        .containsExactly("ESSAY_PARAGRAPH_GENERATE");
  }

  @Test
  void paragraphGenerationValidatesParagraphOwnershipAndBaseVersion() {
    assertThatThrownBy(() -> service.generateParagraph(
        user("essay:write"),
        new EssayParagraphGenerateRequest("req-paragraph-2", 101L, "P-404", 3L, "越界段落")))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("NOT_FOUND");

    assertThatThrownBy(() -> service.generateParagraph(
        user("essay:write"),
        new EssayParagraphGenerateRequest("req-paragraph-3", 101L, "P-1", 2L, "旧版本")))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("ESSAY_DRAFT_VERSION_CONFLICT");
  }

  @Test
  void featureSwitchDisabledRejectsBeforeAiSubmission() {
    featureSwitch.setEnabled(false);

    assertThatThrownBy(() -> service.generateOutline(
        user("essay:workspace:view"),
        new EssayOutlineGenerateRequest(101L, "进度管理", List.of("项目背景"))))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("ESSAY_AI_RUNTIME_DISABLED");
    assertThat(aiRuntimePort.submissions()).isEmpty();
  }

  @Test
  void reviewTaskDoesNotExposeFakeScoreUntilResultIsMaterialized() {
    EssayReviewTaskDto task = service.submitReview(
        user("essay:review"),
        new EssayReviewSubmitRequest("req-review-1", 101L, 1L, 9001L, "论文正文快照"));

    assertThat(task.status()).isEqualTo("QUEUED");
    assertThat(task.aiTaskId()).isNotNull();
    AiTaskSubmitCommand reviewCommand = aiRuntimePort.submissions().get(aiRuntimePort.submissions().size() - 1);
    assertThat(reviewCommand.inputSnapshotJson()).contains("\"content\":\"论文正文快照\"");
    assertThat(reviewCommand.inputSnapshotJson()).contains("\"qualificationId\":1");
    assertThat(reviewCommand.inputSnapshotJson()).contains("\"rubricId\":9001");

    assertThatThrownBy(() -> service.getReviewResult(user("essay:review"), task.reviewTaskId()))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("ESSAY_REVIEW_RESULT_NOT_READY");

    repository.materializeReviewResult(task.reviewTaskId(), new EssayReviewResultDto(
        3001L,
        task.reviewTaskId(),
        43,
        "结构完整，实践细节仍需加强",
        List.of(new EssayReviewIssueDto("ISS-1", 3, "DETAIL_WEAK", "补充工具产物"))));

    EssayReviewResultDto result = service.getReviewResult(user("essay:review"), task.reviewTaskId());
    assertThat(result.totalScore()).isEqualTo(43);
    assertThat(service.listReviewHistory(user("essay:review"), 1, 10).items()).hasSize(1);
  }

  @Test
  void reviewTaskFailureMarksBusinessTaskFailedForCompensation() {
    aiRuntimePort.failWith(new BusinessException("AI_CONFIGURATION_UNAVAILABLE", "不可用", org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));

    assertThatThrownBy(() -> service.submitReview(
        user("essay:review"),
        new EssayReviewSubmitRequest("req-review-fail", 101L, 1L, 9001L, "论文正文快照")))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("AI_CONFIGURATION_UNAVAILABLE");

    assertThat(repository.reviewTasks.values()).extracting(EssayReviewTaskDto::status).contains("FAILED");
  }

  @Test
  void reviewTaskRuntimeFailureMarksFailedAndPreservesOriginalWhenCompensationFails() {
    aiRuntimePort.failRuntime(new IllegalStateException("reserve failed"));
    repository.failMarkReviewTask = true;

    assertThatThrownBy(() -> service.submitReview(
        user("essay:review"),
        new EssayReviewSubmitRequest("req-review-runtime", 101L, 1L, 9001L, "论文正文快照")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("reserve failed");

    assertThat(auditPort.events).containsExactly("2001:AI_TASK_UNAVAILABLE:mark failed");
  }

  @Test
  void issueQuestionMustBindReviewTaskResultAndIssueOwner() {
    EssayReviewTaskDto task = service.submitReview(
        user("essay:review"),
        new EssayReviewSubmitRequest(101L, 1L, 9001L, "论文正文快照"));
    repository.materializeReviewResult(task.reviewTaskId(), new EssayReviewResultDto(
        3001L,
        task.reviewTaskId(),
        43,
        "结构完整",
        List.of(new EssayReviewIssueDto("ISS-1", 3, "DETAIL_WEAK", "补充工具产物"))));

    EssayAiTaskDto questionTask = service.askIssueQuestion(
        user("essay:review"),
        new EssayIssueQuestionRequest(task.reviewTaskId(), 3001L, "ISS-1", "如何展开 WBS？"));

    assertThat(questionTask.scenario()).isEqualTo("ESSAY_REVIEW_ISSUE_QUESTION");
    assertThat(aiRuntimePort.submissions().get(aiRuntimePort.submissions().size() - 1).bizType())
        .isEqualTo("ESSAY_REVIEW_ISSUE");
    assertThatThrownBy(() -> service.askIssueQuestion(
        new EssayCurrentUser(8L, Set.of("essay:review")),
        new EssayIssueQuestionRequest(task.reviewTaskId(), 3001L, "ISS-1", "越权追问")))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("NOT_FOUND");
  }

  @Test
  void anonymousUserIsRejectedBeforeAnyRuntimeAction() {
    assertThatThrownBy(() -> service.generateOutline(
        null,
        new EssayOutlineGenerateRequest(101L, "进度管理", List.of("项目背景"))))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("AUTH_SESSION_EXPIRED");
  }

  @Test
  void missingPermissionIsRejectedBeforeAiSubmission() {
    assertThatThrownBy(() -> service.generateOutline(
        new EssayCurrentUser(7L, Set.of("essay:write")),
        new EssayOutlineGenerateRequest(101L, "进度管理", List.of("项目背景"))))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("FORBIDDEN");
    assertThat(aiRuntimePort.submissions()).isEmpty();
  }

  @Test
  void capabilityStatusReportsSwitchAndProviderState() {
    EssayAiCapabilityStatusDto status = service.capabilityStatus(user("essay:workspace:view", "essay:write", "essay:review"));

    assertThat(status.enabled()).isTrue();
    assertThat(status.outlineGenerate()).isEqualTo("AVAILABLE");
    assertThat(status.paragraphGenerate()).isEqualTo("AVAILABLE");
    assertThat(status.review()).isEqualTo("AVAILABLE");
  }

  @Test
  void capabilityStatusCombinesPermissionSwitchAndProviderAvailabilityPerAction() {
    aiRuntimePort.unavailableScenarios.add("ESSAY_REVIEW_ISSUE_QUESTION");

    EssayAiCapabilityStatusDto limited = service.capabilityStatus(user("essay:workspace:view"));
    assertThat(limited.outlineGenerate()).isEqualTo("AVAILABLE");
    assertThat(limited.paragraphGenerate()).isEqualTo("FORBIDDEN");
    assertThat(limited.review()).isEqualTo("FORBIDDEN");
    assertThat(limited.issueQuestion()).isEqualTo("FORBIDDEN");

    EssayAiCapabilityStatusDto unavailable = service.capabilityStatus(user("essay:workspace:view", "essay:write", "essay:review"));
    assertThat(unavailable.issueQuestion()).isEqualTo("UNAVAILABLE");

    featureSwitch.setEnabled(false);
    EssayAiCapabilityStatusDto disabled = service.capabilityStatus(user("essay:workspace:view", "essay:write", "essay:review"));
    assertThat(disabled.outlineGenerate()).isEqualTo("DISABLED");
    assertThat(disabled.review()).isEqualTo("DISABLED");
  }

  private EssayCurrentUser user(String... permissions) {
    return new EssayCurrentUser(7L, Set.of(permissions));
  }

  private static final class RecordingAiRuntimePort implements AiTaskSubmissionPort, CapabilityQueryPort {
    private final List<AiTaskSubmitCommand> submissions = new ArrayList<>();
    private final Set<String> unavailableScenarios = new java.util.HashSet<>();
    private long nextTaskId = 8000L;
    private BusinessException failure;
    private RuntimeException runtimeFailure;

    @Override
    public AiTaskRef submit(AiTaskSubmitCommand command) {
      if (failure != null) {
        throw failure;
      }
      if (runtimeFailure != null) {
        throw runtimeFailure;
      }
      submissions.add(command);
      return new AiTaskRef(
          ++nextTaskId,
          command.userId(),
          command.bizType(),
          command.bizId(),
          command.scenario(),
          "PENDING",
          command.requestId(),
          command.requestHash(),
          0,
          1L,
          OffsetDateTime.now());
    }

    List<AiTaskSubmitCommand> submissions() {
      return submissions;
    }

    void failWith(BusinessException failure) {
      this.failure = failure;
    }

    void failRuntime(RuntimeException runtimeFailure) {
      this.runtimeFailure = runtimeFailure;
    }

    @Override
    public boolean available(String scenario) {
      return !unavailableScenarios.contains(scenario);
    }
  }

  private static final class RecordingAuditPort implements EssayAiRuntimeAuditPort {
    private final List<String> events = new ArrayList<>();

    @Override
    public void recordReviewTaskCompensationFailure(Long userId, Long reviewTaskId, String originalCode, RuntimeException compensationFailure) {
      events.add(reviewTaskId + ":" + originalCode + ":" + compensationFailure.getMessage());
    }
  }

  private static final class MutableEssayAiFeatureSwitch implements EssayAiFeatureSwitch {
    private boolean enabled;

    MutableEssayAiFeatureSwitch(boolean enabled) {
      this.enabled = enabled;
    }

    @Override
    public boolean enabled() {
      return enabled;
    }

    void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }
  }

  private static final class InMemoryEssayAiRuntimeRepository implements EssayAiRuntimeRepository {
    private final Map<Long, EssayDraftRuntimeView> drafts = new HashMap<>();
    private final Map<Long, EssayReviewTaskDto> reviewTasks = new HashMap<>();
    private final Map<Long, EssayReviewResultDto> reviewResults = new HashMap<>();
    private long nextReviewTaskId = 2000L;
    private boolean failMarkReviewTask;

    void saveDraft(EssayDraftRuntimeView draft) {
      drafts.put(draft.draftId(), draft);
    }

    void materializeReviewResult(Long reviewTaskId, EssayReviewResultDto result) {
      reviewResults.put(reviewTaskId, result);
      EssayReviewTaskDto current = reviewTasks.get(reviewTaskId);
      reviewTasks.put(reviewTaskId, new EssayReviewTaskDto(
          current.reviewTaskId(),
          current.userId(),
          current.draftId(),
          current.aiTaskId(),
          "SUCCEEDED",
          current.createdAt()));
    }

    @Override
    public Optional<EssayDraftRuntimeView> findDraft(Long userId, Long draftId) {
      EssayDraftRuntimeView draft = drafts.get(draftId);
      return draft != null && draft.userId().equals(userId) ? Optional.of(draft) : Optional.empty();
    }

    @Override
    public EssayReviewTaskDto createReviewTask(Long userId, Long draftId) {
      EssayReviewTaskDto task = new EssayReviewTaskDto(++nextReviewTaskId, userId, draftId, null, "CREATING", Instant.now());
      reviewTasks.put(task.reviewTaskId(), task);
      return task;
    }

    @Override
    public EssayReviewTaskDto bindReviewAiTask(Long userId, Long reviewTaskId, Long aiTaskId) {
      EssayReviewTaskDto current = findReviewTask(userId, reviewTaskId).orElseThrow();
      EssayReviewTaskDto updated = new EssayReviewTaskDto(
          current.reviewTaskId(),
          current.userId(),
          current.draftId(),
          aiTaskId,
          "QUEUED",
          current.createdAt());
      reviewTasks.put(reviewTaskId, updated);
      return updated;
    }

    @Override
    public EssayReviewTaskDto markReviewTaskFailed(Long userId, Long reviewTaskId, String failureCode, String failureMessage) {
      if (failMarkReviewTask) {
        throw new IllegalStateException("mark failed");
      }
      EssayReviewTaskDto current = findReviewTask(userId, reviewTaskId).orElseThrow();
      EssayReviewTaskDto failed = new EssayReviewTaskDto(
          current.reviewTaskId(),
          current.userId(),
          current.draftId(),
          current.aiTaskId(),
          "FAILED",
          current.createdAt());
      reviewTasks.put(reviewTaskId, failed);
      return failed;
    }

    @Override
    public Optional<EssayReviewTaskDto> findReviewTask(Long userId, Long reviewTaskId) {
      EssayReviewTaskDto task = reviewTasks.get(reviewTaskId);
      return task != null && task.userId().equals(userId) ? Optional.of(task) : Optional.empty();
    }

    @Override
    public Optional<EssayReviewResultDto> findReviewResult(Long userId, Long reviewTaskId) {
      return findReviewTask(userId, reviewTaskId).flatMap(task -> Optional.ofNullable(reviewResults.get(reviewTaskId)));
    }

    @Override
    public EssayRuntimePage<EssayReviewResultDto> listReviewHistory(Long userId, int pageNo, int pageSize) {
      List<EssayReviewResultDto> items = reviewResults.values().stream()
          .filter(result -> findReviewTask(userId, result.reviewTaskId()).isPresent())
          .toList();
      return new EssayRuntimePage<>(items, pageNo, pageSize, items.size(), false);
    }
  }
}
