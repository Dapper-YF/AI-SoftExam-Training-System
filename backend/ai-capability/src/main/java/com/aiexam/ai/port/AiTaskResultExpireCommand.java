package com.aiexam.ai.port;

public record AiTaskResultExpireCommand(
    Long taskId,
    String expectedBizType,
    Long expectedBizId,
    Long expectedUserId,
    String reason
) {
}
