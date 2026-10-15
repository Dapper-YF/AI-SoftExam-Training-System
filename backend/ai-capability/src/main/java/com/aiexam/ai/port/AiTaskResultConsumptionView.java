package com.aiexam.ai.port;

public record AiTaskResultConsumptionView(
    Long taskId,
    String consumptionStatus,
    String consumerBizType,
    Long consumerBizId,
    String resultHash
) {
}
