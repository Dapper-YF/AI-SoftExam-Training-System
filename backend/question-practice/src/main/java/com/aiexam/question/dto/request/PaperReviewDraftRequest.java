package com.aiexam.question.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

public record PaperReviewDraftRequest(
    @Valid PaperUpdateRequest paper,
    @NotEmpty @Valid List<QuestionBinding> questions
) {
  public record QuestionBinding(
      @NotNull @Positive Long questionId,
      @NotNull @Positive Integer questionNo,
      String sectionCode,
      @NotNull @Positive BigDecimal score
  ) {
  }
}
