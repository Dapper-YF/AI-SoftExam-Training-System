package com.aiexam.question.admin.dto;

import java.util.List;

public record QuestionWorkbookValidationDto(
    boolean compatible,
    int sheetCount,
    int columnCount,
    List<String> sheetNames
) {
}
