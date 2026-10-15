package com.aiexam.ai.port;

public record AiTaskResultView(
    Long taskId,
    String resultJson,
    String resultHash,
    String consumptionStatus
) {
}
