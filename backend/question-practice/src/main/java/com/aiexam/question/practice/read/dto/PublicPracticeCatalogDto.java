package com.aiexam.question.practice.read.dto;

import java.util.List;

public record PublicPracticeCatalogDto(
    Long qualificationId,
    boolean available,
    String unavailableReason,
    List<SubjectDto> subjects,
    PaperCatalogDto papers
) {
  public record SubjectDto(
      Long subjectId,
      String subjectName,
      String subjectType,
      boolean available,
      String unavailableReason
  ) {
  }

  public record PaperCatalogDto(
      boolean available,
      String unavailableReason
  ) {
  }
}
