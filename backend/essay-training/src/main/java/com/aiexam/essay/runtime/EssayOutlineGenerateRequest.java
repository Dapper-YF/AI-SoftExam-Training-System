package com.aiexam.essay.runtime;

import java.util.List;

public record EssayOutlineGenerateRequest(
    String requestId,
    Long draftId,
    String topic,
    List<String> requiredSections
) {
  public EssayOutlineGenerateRequest(Long draftId, String topic, List<String> requiredSections) {
    this("outline-" + draftId, draftId, topic, requiredSections);
  }

  public EssayOutlineGenerateRequest {
    requiredSections = requiredSections == null ? List.of() : List.copyOf(requiredSections);
  }
}
