package com.aiexam.question.practice.attempt.domain;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class PracticeAnswerCanonicalizer {
  private PracticeAnswerCanonicalizer() {
  }

  public static PracticeAnswerPayload canonicalize(String questionType, PracticeAnswerPayload payload) {
    if (payload == null) {
      return null;
    }
    String type = questionType == null ? "" : questionType.trim().toUpperCase(Locale.ROOT);
    return switch (type) {
      case "SINGLE_CHOICE" -> new PracticeAnswerPayload(singleKey(payload), null, null, null);
      case "MULTI_CHOICE" -> new PracticeAnswerPayload(null, optionKeys(payload), null, null);
      case "JUDGE" -> {
        if (payload.value() == null) {
          throw new IllegalArgumentException("judge answer must use value");
        }
        yield new PracticeAnswerPayload(null, null, payload.value(), null);
      }
      case "CALCULATION", "SHORT_ANSWER", "ESSAY_WRITING", "ARCHITECTURE_DESIGN", "SERVICE_DESIGN" -> {
        if (payload.text() == null) {
          throw new IllegalArgumentException("subjective answer must use text");
        }
        yield new PracticeAnswerPayload(null, null, null,
            Normalizer.normalize(payload.text(), Normalizer.Form.NFC).trim());
      }
      case "CODE_GAP", "FILL_BLANK", "FLOWCHART", "UML_GRAPH", "REQUIREMENT_SPEC", "METRIC_CONFIGURATION" -> {
        if (payload.structured() == null || payload.structured().isEmpty()) {
          throw new IllegalArgumentException("structured answer must use structured payload");
        }
        yield new PracticeAnswerPayload(null, null, null, null, payload.structured());
      }
      default -> payload;
    };
  }

  public static boolean matches(String questionType, PracticeAnswerPayload actual,
                                PracticeAnswerPayload expected) {
    if (actual == null || expected == null) {
      return false;
    }
    return Objects.equals(canonicalize(questionType, actual), canonicalize(questionType, expected));
  }

  private static String singleKey(PracticeAnswerPayload payload) {
    if (payload.optionKey() != null) {
      return normalizeKey(payload.optionKey());
    }
    if (payload.optionKeys() != null && payload.optionKeys().size() == 1) {
      return normalizeKey(payload.optionKeys().get(0));
    }
    throw new IllegalArgumentException("single choice answer must contain exactly one option key");
  }

  private static List<String> optionKeys(PracticeAnswerPayload payload) {
    List<String> keys = payload.optionKeys();
    if (keys == null && payload.optionKey() != null) {
      keys = List.of(payload.optionKey());
    }
    if (keys == null || keys.isEmpty()) {
      throw new IllegalArgumentException("multi choice answer must contain option keys");
    }
    return keys.stream().map(PracticeAnswerCanonicalizer::normalizeKey).distinct().sorted().toList();
  }

  private static String normalizeKey(String raw) {
    if (raw == null || raw.trim().isEmpty()) {
      throw new IllegalArgumentException("option key must not be blank");
    }
    return raw.trim().toUpperCase(Locale.ROOT);
  }
}
