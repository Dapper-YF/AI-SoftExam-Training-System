package com.aiexam.ai.port;

public interface AiTaskResultPort {
  AiTaskResultView getSuccessfulResult(Long taskId, String expectedBizType, Long expectedBizId, Long expectedUserId);

  AiTaskResultConsumptionView acknowledgeConsumed(AiTaskResultAckCommand command);

  AiTaskResultConsumptionView expireUnconsumed(AiTaskResultExpireCommand command);
}
