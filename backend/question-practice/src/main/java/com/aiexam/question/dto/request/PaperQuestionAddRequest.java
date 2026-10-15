package com.aiexam.question.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record PaperQuestionAddRequest(
    @NotNull @Positive Long questionId,
    @Size(max = 64) String sectionCode,
    @NotNull @Positive Integer questionNo,
    @NotNull @DecimalMin(value = "0.01") BigDecimal score
) {
}
