package com.aiexam.question.practice.attempt.domain;

import java.util.List;
import java.util.Map;

/** A versioned answer envelope. Structured answers keep their editor-specific JSON in one safe branch. */
public record PracticeAnswerPayload(String optionKey, List<String> optionKeys, Boolean value, String text,
                                    Map<String, Object> structured) {
  public PracticeAnswerPayload {
    optionKeys = optionKeys == null ? null : List.copyOf(optionKeys);
    structured = structured == null ? null : Map.copyOf(structured);
  }

  public PracticeAnswerPayload(String optionKey, List<String> optionKeys, Boolean value, String text) {
    this(optionKey, optionKeys, value, text, null);
  }
}
