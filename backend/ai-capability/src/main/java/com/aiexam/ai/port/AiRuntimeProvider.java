package com.aiexam.ai.port;

public interface AiRuntimeProvider {
  boolean supports(String scenario);

  ProviderDispatchResult dispatch(AiTaskExecutionCommand command);
}
