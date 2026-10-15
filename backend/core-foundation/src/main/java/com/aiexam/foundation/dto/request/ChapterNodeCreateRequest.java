package com.aiexam.foundation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ChapterNodeCreateRequest(
    @NotNull @Positive Long qualificationId,
    @NotNull @Positive Long subjectId,
    @Positive Long parentId,
    @Size(max = 64) String bookVersion,
    @Size(max = 64) String courseCode,
    @NotBlank @Size(max = 96) String chapterCode,
    @NotBlank @Size(max = 128) String chapterName,
    @NotNull @Positive Integer levelNo,
    @PositiveOrZero Integer sortOrder,
    @Size(max = 24) String status
) {
}
