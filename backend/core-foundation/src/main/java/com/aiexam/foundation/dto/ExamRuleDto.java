package com.aiexam.foundation.dto;

public record ExamRuleDto(
    Long id,
    Long qualificationId,
    Long subjectId,
    String ruleCode,
    String ruleName,
    String ruleVersion,
    String ruleJson,
    String status
) {
}
