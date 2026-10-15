package com.aiexam.ai.port;

public record AiTaskExecutionCommand(
    Long taskId,
    Long userId,
    String bizType,
    Long bizId,
    String scenario,
    String requestId,
    String requestHash,
    String inputSnapshotJson
) {
}
