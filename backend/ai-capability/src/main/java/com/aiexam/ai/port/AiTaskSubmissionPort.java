package com.aiexam.ai.port;

public interface AiTaskSubmissionPort {
  AiTaskRef submit(AiTaskSubmitCommand command);
}
