package com.aiexam.app.learning;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record LearningChapterWriteRequest(
    @NotBlank @Size(max = 120) String chapterCode,
    @NotBlank @Size(max = 300) String chapterTitle,
    @Size(max = 120) String parentChapterCode,
    @Positive int levelNo,
    @Positive int pageNo,
    Integer endPageNo,
    int sortOrder
) {}
