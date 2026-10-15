package com.aiexam.foundation.dto;

import java.util.List;

public record ChapterNodeDto(
    Long id,
    Long qualificationId,
    Long subjectId,
    Long parentId,
    String bookVersion,
    String courseCode,
    String chapterCode,
    String chapterName,
    int levelNo,
    Integer sortOrder,
    String status,
    List<ChapterNodeDto> children
) {
}
