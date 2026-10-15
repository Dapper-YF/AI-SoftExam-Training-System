package com.aiexam.essay.runtime;

import java.util.Set;

public record EssayDraftRuntimeView(
    Long draftId,
    Long userId,
    Long qualificationId,
    String title,
    String content,
    long version,
    Set<String> paragraphIds
) {
  public EssayDraftRuntimeView(Long draftId, Long userId, Long qualificationId, String title, String content) {
    this(draftId, userId, qualificationId, title, content, 1L, Set.of("P-1"));
  }

  public EssayDraftRuntimeView {
    paragraphIds = paragraphIds == null ? Set.of() : Set.copyOf(paragraphIds);
  }
}
