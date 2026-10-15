package com.aiexam.essay.runtime;

public record EssayParagraphGenerateRequest(
    String requestId,
    Long draftId,
    String paragraphId,
    long baseVersion,
    String instruction
) {
  public EssayParagraphGenerateRequest(Long draftId, String paragraphId, String instruction) {
    this("paragraph-" + draftId + "-" + paragraphId, draftId, paragraphId, 1L, instruction);
  }
}
