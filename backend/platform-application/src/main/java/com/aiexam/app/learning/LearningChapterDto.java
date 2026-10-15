package com.aiexam.app.learning;

public record LearningChapterDto(
    Long id,
    Long versionId,
    Long parentId,
    String chapterCode,
    String chapterTitle,
    int levelNo,
    int pageNo,
    Integer endPageNo,
    int sortOrder
) {}
