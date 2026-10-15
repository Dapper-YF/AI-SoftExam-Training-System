package com.aiexam.question.dto;

import java.math.BigDecimal;

public record PaperQuestionSummaryDto(
    Long questionId,
    Integer questionNo,
    String questionType,
    String title,
    BigDecimal score,
    Long subjectId,
    Integer contentVersion
) {
}
