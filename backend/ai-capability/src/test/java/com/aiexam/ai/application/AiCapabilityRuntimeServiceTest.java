package com.aiexam.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.ai.port.AiRuntimeProvider;
import com.aiexam.ai.port.AiTaskExecutionCommand;
import com.aiexam.ai.port.AiTaskRef;
import com.aiexam.ai.port.AiTaskResultAckCommand;
import com.aiexam.ai.port.AiTaskResultConsumptionView;
import com.aiexam.ai.port.AiTaskResultExpireCommand;
import com.aiexam.ai.port.AiTaskResultView;
import com.aiexam.ai.port.AiTaskStatusView;
import com.aiexam.ai.port.AiTaskSubmitCommand;
import com.aiexam.ai.port.ProviderDispatchResult;
import com.aiexam.ai.repository.AiRuntimeTaskRepository;
import com.aiexam.ai.repository.SubmitReservation;
import com.aiexam.common.error.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AiCapabilityRuntimeServiceTest {
  @Test
  void submitFailsClosedBeforePersistingWhenNoRuntimeProviderIsConfigured() {
    InMemoryAiRuntimeTaskRepository repository = new InMemoryAiRuntimeTaskRepository();
    AiCapabilityRuntimeService service = new AiCapabilityRuntimeService(repository);

    assertThatThrownBy(() -> service.submit(command("req-1", "{\"draftId\":101}")))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("AI_CONFIGURATION_UNAVAILABLE");
    assertThat(repository.tasks).isEmpty();
  }

  @Test
  void submitPersistsInputSnapshotAndDispatchesControlledProvider() {
    InMemoryAiRuntimeTaskRepository repository = new InMemoryAiRuntimeTaskRepository();
    RecordingProvider provider = new RecordingProvider("ESSAY_OUTLINE_GENERATE");
    AiCapabilityRuntimeService service = new AiCapabilityRuntimeService(repository, List.of(provider));
    AiTaskSubmitCommand command = command("req-1", "{\"draftId\":101,\"topic\":\"progress\"}");

    AiTaskRef task = service.submit(command);

    assertThat(task.status()).isEqualTo("QUEUED");
    StoredTask stored = repository.tasks.get(task.taskId());
    assertThat(stored.requestId).isEqualTo("req-1");
    assertThat(stored.requestHash).isEqualTo(command.requestHash());
    assertThat(stored.inputSnapshotJson).isEqualTo(command.inputSnapshotJson());
    assertThat(provider.lastCommand.inputSnapshotJson()).isEqualTo(command.inputSnapshotJson());
  }

  @Test
  void submitReplaysSameRequestAndRejectsSameAnchorDifferentHash() {
    InMemoryAiRuntimeTaskRepository repository = new InMemoryAiRuntimeTaskRepository();
    AiCapabilityRuntimeService service = new AiCapabilityRuntimeService(repository, List.of(new RecordingProvider("ESSAY_OUTLINE_GENERATE")));

    AiTaskRef first = service.submit(command("req-1", "{\"draftId\":101,\"topic\":\"progress\"}"));
    AiTaskRef replay = service.submit(command("req-1", "{\"draftId\":101,\"topic\":\"progress\"}"));

    assertThat(replay.taskId()).isEqualTo(first.taskId());
    assertThatThrownBy(() -> service.submit(command("req-1", "{\"draftId\":101,\"topic\":\"scope\"}")))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("AI_TASK_IDEMPOTENCY_CONFLICT");
  }

  @Test
  void submitFailsClosedWhenRepositoryReserveThrowsRuntimeException() {
    InMemoryAiRuntimeTaskRepository repository = new InMemoryAiRuntimeTaskRepository();
    repository.failReserve = true;
    AiCapabilityRuntimeService service = new AiCapabilityRuntimeService(repository, List.of(new RecordingProvider("ESSAY_OUTLINE_GENERATE")));

    assertThatThrownBy(() -> service.submit(command("req-reserve-fail", "{\"draftId\":101}")))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("AI_TASK_UNAVAILABLE");
    assertThat(repository.tasks).isEmpty();
  }

  @Test
  void submitMarksTaskFailedWhenMarkDispatchedThrowsRuntimeException() {
    InMemoryAiRuntimeTaskRepository repository = new InMemoryAiRuntimeTaskRepository();
    repository.failMarkDispatched = true;
    AiCapabilityRuntimeService service = new AiCapabilityRuntimeService(repository, List.of(new RecordingProvider("ESSAY_OUTLINE_GENERATE")));

    assertThatThrownBy(() -> service.submit(command("req-dispatch-mark-fail", "{\"draftId\":101}")))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("AI_TASK_UNAVAILABLE");
    assertThat(repository.tasks.values()).extracting(task -> task.status).containsExactly("FAILED");
  }

  @Test
  void statusResultAckExpireAndRetryValidateExpectedOwnership() {
    InMemoryAiRuntimeTaskRepository repository = new InMemoryAiRuntimeTaskRepository();
    AiCapabilityRuntimeService service = new AiCapabilityRuntimeService(repository, List.of(new RecordingProvider("ESSAY_OUTLINE_GENERATE")));
    AiTaskRef task = service.submit(command("req-1", "{\"draftId\":101}"));
    repository.succeed(task.taskId(), "{\"schemaVersion\":1,\"content\":\"outline\"}", sha256("{\"schemaVersion\":1,\"content\":\"outline\"}"));

    AiTaskStatusView status = service.get(task.taskId(), "ESSAY_DRAFT", 101L, 7L);
    AiTaskResultView result = service.getSuccessfulResult(task.taskId(), "ESSAY_DRAFT", 101L, 7L);
    AiTaskResultConsumptionView ack = service.acknowledgeConsumed(new AiTaskResultAckCommand(
        task.taskId(), "ESSAY_DRAFT", 101L, 7L, "ESSAY_OUTLINE", 5001L, result.resultHash()));

    assertThat(status.status()).isEqualTo("SUCCESS");
    assertThat(result.consumptionStatus()).isEqualTo("AVAILABLE");
    assertThat(ack.consumptionStatus()).isEqualTo("CONSUMED");
    assertThatThrownBy(() -> service.get(task.taskId(), "ESSAY_DRAFT", 101L, 8L))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("AI_TASK_OWNERSHIP_MISMATCH");

    AiTaskRef failed = service.submit(command("req-2", "{\"draftId\":102}"));
    repository.fail(failed.taskId());
    long failedVersion = repository.tasks.get(failed.taskId()).version;
    AiTaskStatusView retried = service.retryFailed(failed.taskId(), "ESSAY_DRAFT", 101L, 7L, failedVersion, "retry");
    assertThat(retried.status()).isEqualTo("PENDING");

    AiTaskRef expiring = service.submit(command("req-3", "{\"draftId\":103}"));
    repository.succeed(expiring.taskId(), "{\"schemaVersion\":1,\"content\":\"late\"}", sha256("late"));
    AiTaskResultConsumptionView expired = service.expireUnconsumed(new AiTaskResultExpireCommand(
        expiring.taskId(), "ESSAY_DRAFT", 101L, 7L, "retention"));
    assertThat(expired.consumptionStatus()).isEqualTo("EXPIRED");
  }

  private AiTaskSubmitCommand command(String requestId, String snapshotJson) {
    return new AiTaskSubmitCommand(
        7L,
        "ESSAY_DRAFT",
        101L,
        "ESSAY_OUTLINE_GENERATE",
        requestId,
        sha256("POST\n/api/v1/essay/drafts/101/outline\n" + snapshotJson),
        snapshotJson);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static final class RecordingProvider implements AiRuntimeProvider {
    private final String scenario;
    private AiTaskExecutionCommand lastCommand;

    RecordingProvider(String scenario) {
      this.scenario = scenario;
    }

    @Override
    public boolean supports(String scenario) {
      return this.scenario.equals(scenario);
    }

    @Override
    public ProviderDispatchResult dispatch(AiTaskExecutionCommand command) {
      this.lastCommand = command;
      return new ProviderDispatchResult("QUEUED", "fake-provider-" + command.taskId());
    }
  }

  private static final class InMemoryAiRuntimeTaskRepository implements AiRuntimeTaskRepository {
    private final Map<Long, StoredTask> tasks = new HashMap<>();
    private final Map<String, Long> anchors = new HashMap<>();
    private boolean failReserve;
    private boolean failMarkDispatched;

    @Override
    public SubmitReservation reserveSubmission(Long taskId, AiTaskSubmitCommand command) {
      if (failReserve) {
        throw new IllegalStateException("reserve failed");
      }
      String anchor = command.userId() + "|" + command.bizType() + "|" + command.bizId() + "|" + command.scenario() + "|" + command.requestId();
      Long existingId = anchors.get(anchor);
      if (existingId != null) {
        StoredTask existing = tasks.get(existingId);
        if (!existing.requestHash.equals(command.requestHash())) {
          throw new BusinessException("AI_TASK_IDEMPOTENCY_CONFLICT", "同一requestId参数不一致", org.springframework.http.HttpStatus.CONFLICT);
        }
        return new SubmitReservation(existing.ref(), false);
      }
      StoredTask task = StoredTask.creating(taskId, command);
      tasks.put(taskId, task);
      anchors.put(anchor, taskId);
      return new SubmitReservation(task.ref(), true);
    }

    @Override
    public AiTaskRef markDispatched(Long taskId, String status, String providerReference) {
      if (failMarkDispatched) {
        throw new IllegalStateException("mark dispatched failed");
      }
      StoredTask task = tasks.get(taskId);
      task.status = status;
      task.providerReference = providerReference;
      task.version++;
      return task.ref();
    }

    @Override
    public AiTaskRef markSubmissionFailed(Long taskId, String failureCode, String failureMessage) {
      StoredTask task = tasks.get(taskId);
      task.status = "FAILED";
      task.version++;
      return task.ref();
    }

    @Override
    public Optional<AiTaskStatusView> findStatus(Long taskId) {
      return Optional.ofNullable(tasks.get(taskId)).map(StoredTask::statusView);
    }

    @Override
    public Optional<AiTaskResultView> findSuccessfulResult(Long taskId) {
      StoredTask task = tasks.get(taskId);
      return task == null || task.resultJson == null ? Optional.empty() : Optional.of(task.resultView());
    }

    @Override
    public AiTaskResultConsumptionView acknowledgeConsumed(AiTaskResultAckCommand command) {
      StoredTask task = tasks.get(command.taskId());
      if (!task.resultHash.equals(command.expectedResultHash())) {
        throw new BusinessException("AI_TASK_RESULT_CONSUMER_CONFLICT", "结果哈希不一致", org.springframework.http.HttpStatus.CONFLICT);
      }
      if ("CONSUMED".equals(task.consumptionStatus)
          && (!task.consumerBizType.equals(command.consumerBizType()) || !task.consumerBizId.equals(command.consumerBizId()))) {
        throw new BusinessException("AI_TASK_RESULT_CONSUMER_CONFLICT", "结果已被其他消费者确认", org.springframework.http.HttpStatus.CONFLICT);
      }
      task.consumptionStatus = "CONSUMED";
      task.consumerBizType = command.consumerBizType();
      task.consumerBizId = command.consumerBizId();
      return task.consumption();
    }

    @Override
    public AiTaskResultConsumptionView expireUnconsumed(AiTaskResultExpireCommand command) {
      StoredTask task = tasks.get(command.taskId());
      if ("CONSUMED".equals(task.consumptionStatus)) {
        throw new BusinessException("AI_TASK_RESULT_CONSUMER_CONFLICT", "结果已消费", org.springframework.http.HttpStatus.CONFLICT);
      }
      task.consumptionStatus = "EXPIRED";
      task.resultJson = null;
      return task.consumption();
    }

    @Override
    public AiTaskStatusView retryFailed(Long taskId, long expectedVersion, String reason) {
      StoredTask task = tasks.get(taskId);
      if (task.version != expectedVersion) {
        throw new BusinessException("AI_TASK_VERSION_CONFLICT", "版本冲突", org.springframework.http.HttpStatus.CONFLICT);
      }
      task.status = "PENDING";
      task.retryCount++;
      task.version++;
      return task.statusView();
    }

    void succeed(Long taskId, String resultJson, String resultHash) {
      StoredTask task = tasks.get(taskId);
      task.status = "SUCCESS";
      task.resultJson = resultJson;
      task.resultHash = resultHash;
      task.consumptionStatus = "AVAILABLE";
      task.version++;
    }

    void fail(Long taskId) {
      StoredTask task = tasks.get(taskId);
      task.status = "FAILED";
      task.version++;
    }
  }

  private static final class StoredTask {
    private final Long taskId;
    private final Long userId;
    private final String bizType;
    private final Long bizId;
    private final String scenario;
    private final String requestId;
    private final String requestHash;
    private final String inputSnapshotJson;
    private String status;
    private int retryCount;
    private long version;
    private String providerReference;
    private String resultJson;
    private String resultHash;
    private String consumptionStatus;
    private String consumerBizType;
    private Long consumerBizId;

    private StoredTask(AiTaskSubmitCommand command, Long taskId) {
      this.taskId = taskId;
      this.userId = command.userId();
      this.bizType = command.bizType();
      this.bizId = command.bizId();
      this.scenario = command.scenario();
      this.requestId = command.requestId();
      this.requestHash = command.requestHash();
      this.inputSnapshotJson = command.inputSnapshotJson();
      this.status = "CREATING";
    }

    static StoredTask creating(Long taskId, AiTaskSubmitCommand command) {
      return new StoredTask(command, taskId);
    }

    AiTaskRef ref() {
      return new AiTaskRef(taskId, userId, bizType, bizId, scenario, status, requestId, requestHash, retryCount, version, OffsetDateTime.now());
    }

    AiTaskStatusView statusView() {
      return new AiTaskStatusView(taskId, userId, bizType, bizId, scenario, status, retryCount, version, OffsetDateTime.now());
    }

    AiTaskResultView resultView() {
      return new AiTaskResultView(taskId, resultJson, resultHash, consumptionStatus);
    }

    AiTaskResultConsumptionView consumption() {
      return new AiTaskResultConsumptionView(taskId, consumptionStatus, consumerBizType, consumerBizId, resultHash);
    }
  }
}
