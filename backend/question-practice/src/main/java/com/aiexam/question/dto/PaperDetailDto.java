package com.aiexam.question.dto;

import java.math.BigDecimal;
import java.util.List;

public record PaperDetailDto(
    Long paperId,
    Long qualificationId,
    Long subjectId,
    String paperName,
    String paperType,
    Integer yearNo,
    BigDecimal totalScore,
    Integer durationMinutes,
    String status,
    List<PaperQuestionSummaryDto> questions
) {
}
