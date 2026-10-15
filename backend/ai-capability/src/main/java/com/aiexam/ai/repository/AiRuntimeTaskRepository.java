package com.aiexam.ai.repository;

import com.aiexam.ai.port.AiTaskRef;
import com.aiexam.ai.port.AiTaskResultAckCommand;
import com.aiexam.ai.port.AiTaskResultConsumptionView;
import com.aiexam.ai.port.AiTaskResultExpireCommand;
import com.aiexam.ai.port.AiTaskResultView;
import com.aiexam.ai.port.AiTaskStatusView;
import com.aiexam.ai.port.AiTaskSubmitCommand;
import java.util.Optional;

public interface AiRuntimeTaskRepository {
  SubmitReservation reserveSubmission(Long taskId, AiTaskSubmitCommand command);

  AiTaskRef markDispatched(Long taskId, String status, String providerReference);

  AiTaskRef markSubmissionFailed(Long taskId, String failureCode, String failureMessage);

  Optional<AiTaskStatusView> findStatus(Long taskId);

  Optional<AiTaskResultView> findSuccessfulResult(Long taskId);

  AiTaskResultConsumptionView acknowledgeConsumed(AiTaskResultAckCommand command);

  AiTaskResultConsumptionView expireUnconsumed(AiTaskResultExpireCommand command);

  AiTaskStatusView retryFailed(Long taskId, long expectedVersion, String reason);
}
