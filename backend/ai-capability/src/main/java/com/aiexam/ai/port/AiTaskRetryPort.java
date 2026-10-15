package com.aiexam.ai.port;

public interface AiTaskRetryPort {
  AiTaskStatusView retryFailed(
      Long taskId,
      String expectedBizType,
      Long expectedBizId,
      Long expectedUserId,
      long expectedVersion,
      String reason);
}
