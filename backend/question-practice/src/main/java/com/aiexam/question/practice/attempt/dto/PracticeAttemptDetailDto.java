package com.aiexam.question.practice.attempt.dto;

import com.aiexam.question.practice.attempt.domain.AttemptStatus;
import com.aiexam.question.practice.attempt.domain.FeedbackMode;
import com.aiexam.question.practice.attempt.domain.GradingStatus;
import com.aiexam.question.practice.attempt.domain.PracticeAnswerPayload;
import com.aiexam.question.practice.attempt.domain.QuestionSettlementStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PracticeAttemptDetailDto(
    Long attemptId,
    String title,
    Long qualificationId,
    Long subjectId,
    String mode,
    String sourceType,
    Map<String, Object> sourceSpec,
    FeedbackMode feedbackMode,
    AttemptStatus status,
    int requestedCount,
    int actualCount,
    boolean partial,
    Instant startedAt,
    Instant submittedAt,
    Instant serverNow,
    long version,
    BigDecimal totalScore,
    BigDecimal maxScore,
    Integer correctCount,
    Integer wrongCount,
    Integer pendingReviewCount,
    String scoreStatus,
    List<QuestionDto> questions
) {
  public record QuestionDto(
      Long questionId,
      Long questionVersionId,
      int questionOrder,
      String questionType,
      String title,
      QuestionSettlementStatus settlementStatus,
      long settlementVersion,
      BigDecimal earnedScore,
      List<StemDto> stems,
      List<SubQuestionDto> subQuestions,
      List<KnowledgeBindingDto> knowledgeBindings
  ) {
  }

  public record StemDto(Long stemId, String stemType, int sortOrder, String content) {
  }

  public record SubQuestionDto(
      Long subQuestionId,
      String subQuestionType,
      String answerFormat,
      String content,
      Map<String, Object> metadata,
      List<OptionDto> options,
      AnswerDto answer,
      GradingDto grading,
      String solution,
      String solutionStatus,
      List<ScoringPointDto> scoringPoints
  ) {
  }

  public record OptionDto(String optionKey, String content, int sortOrder) {
  }

  public record AnswerDto(PracticeAnswerPayload answerPayload, boolean marked, long answerVersion,
                          GradingStatus gradingStatus, Instant savedAt) {
  }

  public record GradingDto(PracticeAnswerPayload standardAnswer, BigDecimal score, BigDecimal earnedScore) {
  }

  public record ScoringPointDto(Long scoringPointId, String pointCode, String pointText, BigDecimal score,
                                int sortOrder) {
  }

  public record KnowledgeBindingDto(String knowledgeType, Long knowledgeId, String knowledgeName) {
  }
}
