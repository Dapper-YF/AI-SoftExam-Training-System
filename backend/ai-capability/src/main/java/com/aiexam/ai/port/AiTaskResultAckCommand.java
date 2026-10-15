package com.aiexam.ai.port;

public record AiTaskResultAckCommand(
    Long taskId,
    String expectedBizType,
    Long expectedBizId,
    Long expectedUserId,
    String consumerBizType,
    Long consumerBizId,
    String expectedResultHash
) {
}
