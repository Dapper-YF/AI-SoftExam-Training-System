package com.aiexam.question.practice.read.dto;

import java.util.List;

public record PracticeCatalogDto(
    Long qualificationId,
    long questionCount,
    long paperCount,
    List<Integer> paperYears,
    List<SubjectDto> subjects
) {
  public record SubjectDto(
      Long subjectId,
      String subjectName,
      String subjectType,
      long questionCount,
      List<QuestionTypeDto> questionTypes
  ) {
  }

  public record QuestionTypeDto(String questionType, long count) {
  }
}
