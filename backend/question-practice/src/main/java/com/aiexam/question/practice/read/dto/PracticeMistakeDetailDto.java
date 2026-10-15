package com.aiexam.question.practice.read.dto;

import com.aiexam.question.practice.attempt.dto.PracticeAttemptDetailDto;

public record PracticeMistakeDetailDto(
    PracticeMistakeDto mistake,
    PracticeAttemptDetailDto.QuestionDto question
) {
}
