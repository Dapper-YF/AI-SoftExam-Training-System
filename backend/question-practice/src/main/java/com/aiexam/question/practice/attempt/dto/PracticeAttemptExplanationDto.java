package com.aiexam.question.practice.attempt.dto;

import java.math.BigDecimal;
import java.util.List;

public record PracticeAttemptExplanationDto(
    Long attemptId,
    Long questionId,
    String status,
    List<SubQuestionExplanationDto> subQuestions
) {
  public record SubQuestionExplanationDto(
      Long subQuestionId,
      Object standardAnswer,
      String solution,
      String solutionStatus,
      List<ScoringPointDto> scoringPoints
  ) {
  }

  public record ScoringPointDto(Long scoringPointId, String pointCode, String pointText, BigDecimal score,
                                int sortOrder) {
  }
}
