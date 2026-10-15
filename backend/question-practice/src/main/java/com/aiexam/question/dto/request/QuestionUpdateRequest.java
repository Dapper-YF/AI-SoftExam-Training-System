package com.aiexam.question.dto.request;

import jakarta.validation.constraints.Size;

public record QuestionUpdateRequest(
    @Size(max = 180) String title,
    Integer yearNo,
    @Size(max = 24) String difficulty,
    @Size(max = 32) String sourceType,
    @Size(max = 255) String sourceRef
) {
}
