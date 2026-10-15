package com.aiexam.question.practice.dto.request;

import jakarta.validation.constraints.NotBlank;

public record PracticeNoteUpdateRequest(
    @NotBlank String noteContent
) {
}
