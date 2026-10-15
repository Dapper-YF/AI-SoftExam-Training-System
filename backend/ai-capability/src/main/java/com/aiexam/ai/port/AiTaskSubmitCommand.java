package com.aiexam.ai.port;

public record AiTaskSubmitCommand(
    Long userId,
    String bizType,
    Long bizId,
    String scenario,
    String requestId,
    String requestHash,
    String inputSnapshotJson
) {
}
