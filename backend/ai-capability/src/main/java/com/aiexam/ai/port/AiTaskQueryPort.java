package com.aiexam.ai.port;

public interface AiTaskQueryPort {
  AiTaskStatusView get(Long taskId, String expectedBizType, Long expectedBizId, Long expectedUserId);
}
