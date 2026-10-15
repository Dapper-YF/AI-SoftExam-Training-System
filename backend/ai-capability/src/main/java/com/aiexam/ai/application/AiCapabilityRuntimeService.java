package com.aiexam.ai.application;

import com.aiexam.ai.port.AiRuntimeProvider;
import com.aiexam.ai.port.AiTaskExecutionCommand;
import com.aiexam.ai.port.CapabilityQueryPort;
import com.aiexam.ai.port.AiTaskQueryPort;
import com.aiexam.ai.port.AiTaskRef;
import com.aiexam.ai.port.AiTaskResultAckCommand;
import com.aiexam.ai.port.AiTaskResultConsumptionView;
import com.aiexam.ai.port.AiTaskResultExpireCommand;
import com.aiexam.ai.port.AiTaskResultPort;
import com.aiexam.ai.port.AiTaskResultView;
import com.aiexam.ai.port.AiTaskRetryPort;
import com.aiexam.ai.port.AiTaskStatusView;
import com.aiexam.ai.port.AiTaskSubmissionPort;
import com.aiexam.ai.port.AiTaskSubmitCommand;
import com.aiexam.ai.port.ProviderDispatchResult;
import com.aiexam.ai.repository.AiRuntimeTaskRepository;
import com.aiexam.ai.repository.SubmitReservation;
import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ResourceNotFoundException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;

public class AiCapabilityRuntimeService implements
    AiTaskSubmissionPort,
    AiTaskQueryPort,
    AiTaskResultPort,
    AiTaskRetryPort,
    CapabilityQueryPort {
  private static final AtomicLong ID_SEQUENCE = new AtomicLong();

  private final AiRuntimeTaskRepository repository;
  private final List<AiRuntimeProvider> providers;

  public AiCapabilityRuntimeService(AiRuntimeTaskRepository repository, List<AiRuntimeProvider> providers) {
    this.repository = repository;
    this.providers = providers == null ? List.of() : List.copyOf(providers);
  }

  public AiCapabilityRuntimeService(AiRuntimeTaskRepository repository) {
    this(repository, List.of());
  }

  @Override
  public AiTaskRef submit(AiTaskSubmitCommand command) {
    AiRuntimeProvider provider = providers.stream()
        .filter(candidate -> candidate.supports(command.scenario()))
        .findFirst()
        .orElseThrow(() -> unavailable("AI能力未配置，不能创建伪任务或伪结果"));

    SubmitReservation reservation;
    try {
      reservation = repository.reserveSubmission(nextId(), command);
    } catch (BusinessException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      throw new BusinessException("AI_TASK_UNAVAILABLE", "AI任务基础设施暂不可用", HttpStatus.SERVICE_UNAVAILABLE);
    }
    if (!reservation.newlyCreated()) {
      return reservation.task();
    }

    try {
      ProviderDispatchResult dispatch = provider.dispatch(new AiTaskExecutionCommand(
          reservation.task().taskId(),
          command.userId(),
          command.bizType(),
          command.bizId(),
          command.scenario(),
          command.requestId(),
          command.requestHash(),
          command.inputSnapshotJson()));
      return repository.markDispatched(
          reservation.task().taskId(),
          dispatch.status() == null || dispatch.status().isBlank() ? "PENDING" : dispatch.status(),
          dispatch.providerReference());
    } catch (BusinessException ex) {
      repository.markSubmissionFailed(reservation.task().taskId(), ex.code(), ex.getMessage());
      throw ex;
    } catch (RuntimeException ex) {
      try {
        repository.markSubmissionFailed(reservation.task().taskId(), "AI_TASK_UNAVAILABLE", "AI任务调度失败");
      } catch (RuntimeException ignored) {
        // Preserve the dispatch failure as the caller-visible error.
      }
      throw new BusinessException("AI_TASK_UNAVAILABLE", "AI任务调度失败", HttpStatus.SERVICE_UNAVAILABLE);
    }
  }

  @Override
  public AiTaskStatusView get(Long taskId, String expectedBizType, Long expectedBizId, Long expectedUserId) {
    AiTaskStatusView status = repository.findStatus(taskId)
        .orElseThrow(() -> new ResourceNotFoundException("AI任务不存在"));
    requireOwnership(status.userId(), status.bizType(), status.bizId(), expectedUserId, expectedBizType, expectedBizId);
    return status;
  }

  @Override
  public AiTaskResultView getSuccessfulResult(Long taskId, String expectedBizType, Long expectedBizId, Long expectedUserId) {
    AiTaskStatusView status = get(taskId, expectedBizType, expectedBizId, expectedUserId);
    if (!"SUCCESS".equals(status.status())) {
      throw new BusinessException("AI_TASK_NOT_SUCCESS", "AI任务尚未成功", HttpStatus.CONFLICT);
    }
    return repository.findSuccessfulResult(taskId)
        .orElseThrow(() -> new BusinessException("AI_TASK_RESULT_NOT_AVAILABLE", "AI任务结果不可用", HttpStatus.CONFLICT));
  }

  @Override
  public AiTaskResultConsumptionView acknowledgeConsumed(AiTaskResultAckCommand command) {
    get(command.taskId(), command.expectedBizType(), command.expectedBizId(), command.expectedUserId());
    return repository.acknowledgeConsumed(command);
  }

  @Override
  public AiTaskResultConsumptionView expireUnconsumed(AiTaskResultExpireCommand command) {
    get(command.taskId(), command.expectedBizType(), command.expectedBizId(), command.expectedUserId());
    return repository.expireUnconsumed(command);
  }

  @Override
  public AiTaskStatusView retryFailed(
      Long taskId,
      String expectedBizType,
      Long expectedBizId,
      Long expectedUserId,
      long expectedVersion,
      String reason
  ) {
    AiTaskStatusView status = get(taskId, expectedBizType, expectedBizId, expectedUserId);
    if (!"FAILED".equals(status.status())) {
      throw new BusinessException("AI_TASK_STATE_CONFLICT", "当前AI任务状态不允许重试", HttpStatus.CONFLICT);
    }
    return repository.retryFailed(taskId, expectedVersion, reason);
  }

  @Override
  public boolean available(String scenario) {
    return providers.stream().anyMatch(provider -> provider.supports(scenario));
  }

  private void requireOwnership(
      Long actualUserId,
      String actualBizType,
      Long actualBizId,
      Long expectedUserId,
      String expectedBizType,
      Long expectedBizId
  ) {
    if (!equals(actualUserId, expectedUserId)
        || !equals(actualBizType, expectedBizType)
        || !equals(actualBizId, expectedBizId)) {
      throw new BusinessException(
          "AI_TASK_OWNERSHIP_MISMATCH",
          "AI任务归属不匹配",
          HttpStatus.NOT_FOUND);
    }
  }

  private boolean equals(Object left, Object right) {
    return left == null ? right == null : left.equals(right);
  }

  private BusinessException unavailable(String message) {
    return new BusinessException("AI_CONFIGURATION_UNAVAILABLE", message, HttpStatus.SERVICE_UNAVAILABLE);
  }

  private Long nextId() {
    return System.currentTimeMillis() * 100_000 + Math.floorMod(ID_SEQUENCE.getAndIncrement(), 100_000);
  }
}
