package com.aiexam.essay.runtime;

public interface EssayAiRuntimeAuditPort {
  void recordReviewTaskCompensationFailure(Long userId, Long reviewTaskId, String originalCode, RuntimeException compensationFailure);
}
