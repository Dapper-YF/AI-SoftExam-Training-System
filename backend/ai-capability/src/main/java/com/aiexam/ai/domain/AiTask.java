package com.aiexam.ai.domain;

public record AiTask(
    Long id,
    String bizType,
    Long bizId,
    String scenario,
    String status
) {
}
