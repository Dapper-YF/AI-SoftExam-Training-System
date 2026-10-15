package com.aiexam.question.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record PaperUpdateRequest(
    @Positive Long qualificationId,
    @Positive Long subjectId,
    @Size(max = 160) String paperName,
    @Size(max = 32) String paperType,
    Integer yearNo,
    @DecimalMin(value = "0.01") BigDecimal totalScore,
    @Positive Integer durationMinutes
) {
}
