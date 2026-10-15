package com.aiexam.question.dto;

import java.util.List;

public record QuestionExplanationDto(
    Long questionId,
    String questionType,
    List<SolutionDto> standardSolutions,
    List<SubQuestionSolutionDto> subQuestionSolutions,
    List<SolutionDto> aiEnhancedSolutions
) {
  public record SolutionDto(
      Long solutionId,
      Long questionId,
      Long subQuestionId,
      String solutionType,
      String content
  ) {
  }

  public record SubQuestionSolutionDto(
      Long subQuestionId,
      List<SolutionDto> solutions
  ) {
  }
}
