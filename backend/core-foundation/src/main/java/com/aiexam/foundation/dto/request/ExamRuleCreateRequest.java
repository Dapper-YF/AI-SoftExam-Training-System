package com.aiexam.foundation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record ExamRuleCreateRequest(
    @NotNull @Positive Long qualificationId,
    @Positive Long subjectId,
    @NotBlank @Pattern(regexp = "EXAM_PERIOD_RULE") String ruleCode,
    @NotBlank @Size(max = 128) String ruleName,
    @NotBlank @Size(max = 32) String ruleVersion,
    @NotBlank String ruleJson,
    @NotBlank @Size(max = 32) String periodCode,
    @NotNull Instant effectiveFrom,
    @NotNull Instant effectiveTo,
    Instant publishedAt,
    @NotNull @PositiveOrZero Long version,
    @Pattern(regexp = "DRAFT|PUBLISHED|RETIRED") String status
) {
}
