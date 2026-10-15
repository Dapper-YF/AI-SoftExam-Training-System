package com.aiexam.question.practice.attempt.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PracticeExplanationSafetyTest {
  @Test
  void detectsW3InternalTemplateMarkersAndDoesNotReturnRawText() {
    var unsafe = PracticeExplanationSafety.sanitize(
        "CASE_GENERIC stem=foo answer=A selected=B frozen answer: A; reference aligns");

    assertThat(unsafe.status()).isEqualTo(PracticeExplanationSafety.IMPROVING);
    assertThat(unsafe.content()).isNull();
    assertThat(unsafe.unsafeDetected()).isTrue();
    assertThat(unsafe.sanitized()).isTrue();
  }

  @Test
  void doesNotFlagTheLegacyExplanationSampleOfEightyNineRows() {
    assertThat(IntStream.rangeClosed(1, 89)
        .mapToObj(index -> PracticeExplanationSafety.sanitize(
            "legacy explanation " + index + ": option A is correct because the schedule tool fits the stem."))
        .filter(item -> PracticeExplanationSafety.READY.equals(item.status())))
        .hasSize(89);
  }

  @Test
  void treatsEmptyExplanationAsImprovingSafeState() {
    var empty = PracticeExplanationSafety.sanitize("   ");

    assertThat(empty.status()).isEqualTo(PracticeExplanationSafety.IMPROVING);
    assertThat(empty.content()).isNull();
    assertThat(empty.unsafeDetected()).isFalse();
    assertThat(empty.sanitized()).isTrue();
  }
}
