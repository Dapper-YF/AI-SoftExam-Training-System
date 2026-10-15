package com.aiexam.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.common.error.BusinessException;
import com.aiexam.question.application.QuestionApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class QuestionExplanationAccessPolicyTest {
  private final QuestionApplicationService service = new QuestionApplicationService(null, null, null);

  @Test
  void blocksThePublicStandardExplanationBypass() {
    assertThatThrownBy(() -> service.getExplanation(1001L))
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.code()).isEqualTo("PRACTICE_ATTEMPT_CONTEXT_REQUIRED");
          assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN);
        });
  }

  @Test
  void blocksThePublicAiExplanationBypass() {
    assertThatThrownBy(() -> service.triggerAiExplanation(1001L))
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.code()).isEqualTo("PRACTICE_ATTEMPT_CONTEXT_REQUIRED");
          assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN);
        });
  }
}
