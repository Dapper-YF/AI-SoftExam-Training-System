package com.aiexam.ai.port;

public record ChatModelResult(
    String content,
    String provider,
    String model,
    String status,
    long durationMs,
    String errorCode
) {
  public boolean succeeded() {
    return "SUCCEEDED".equals(status) && content != null && !content.isBlank();
  }
}
