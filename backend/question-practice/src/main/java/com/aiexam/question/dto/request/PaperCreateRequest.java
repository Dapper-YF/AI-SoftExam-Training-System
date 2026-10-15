package com.aiexam.question.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record PaperCreateRequest(
    @NotNull @Positive Long qualificationId,
    @Positive Long subjectId,
    @NotBlank @Size(max = 160) String paperName,
    @NotBlank @Size(max = 32) String paperType,
    Integer yearNo,
    @NotNull @DecimalMin(value = "0.01") BigDecimal totalScore,
    @NotNull @Positive Integer durationMinutes
) {
}
