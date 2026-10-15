package com.aiexam.question.dto;

import java.math.BigDecimal;

public record PaperSummaryDto(
    Long paperId,
    Long qualificationId,
    Long subjectId,
    String paperName,
    String paperType,
    Integer yearNo,
    BigDecimal totalScore,
    Integer durationMinutes,
    String status
) {
}
