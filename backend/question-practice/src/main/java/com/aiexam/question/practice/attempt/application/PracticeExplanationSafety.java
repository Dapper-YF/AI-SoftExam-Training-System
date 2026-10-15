package com.aiexam.question.practice.attempt.application;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class PracticeExplanationSafety {
  static final String READY = "READY";
  static final String IMPROVING = "IMPROVING";

  private static final List<Pattern> UNSAFE_MARKERS = List.of(
      Pattern.compile("\\b[A-Z0-9_]+_GENERIC\\b", Pattern.CASE_INSENSITIVE),
      Pattern.compile("(^|[\\s,;{}\\[\\]（(])stem\\s*=", Pattern.CASE_INSENSITIVE),
      Pattern.compile("(^|[\\s,;{}\\[\\]（(])answer\\s*=", Pattern.CASE_INSENSITIVE),
      Pattern.compile("(^|[\\s,;{}\\[\\]（(])selected\\s*=", Pattern.CASE_INSENSITIVE),
      Pattern.compile("frozen\\s+answer", Pattern.CASE_INSENSITIVE),
      Pattern.compile("reference\\s+aligns", Pattern.CASE_INSENSITIVE),
      Pattern.compile("internal[_\\s-]*template", Pattern.CASE_INSENSITIVE)
  );

  private PracticeExplanationSafety() {
  }

  static SafeText sanitize(String raw) {
    boolean unsafeDetected = raw != null && containsUnsafeMarker(raw);
    if (raw == null || raw.trim().isEmpty() || unsafeDetected) {
      return new SafeText(null, IMPROVING, unsafeDetected, true);
    }
    return new SafeText(raw, READY, false, false);
  }

  static boolean unsafe(String raw) {
    return raw != null && containsUnsafeMarker(raw);
  }

  private static boolean containsUnsafeMarker(String raw) {
    String normalized = raw.replace('\u00a0', ' ').toLowerCase(Locale.ROOT);
    return UNSAFE_MARKERS.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
  }

  record SafeText(String content, String status, boolean unsafeDetected, boolean sanitized) {
  }
}
