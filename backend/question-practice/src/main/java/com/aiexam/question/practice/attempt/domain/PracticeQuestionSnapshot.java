package com.aiexam.question.practice.attempt.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record PracticeQuestionSnapshot(
    Long questionId,
    Long questionVersionId,
    int questionOrder,
    int contentVersion,
    String questionType,
    String title,
    List<Stem> stems,
    List<SubQuestion> subQuestions,
    List<KnowledgeBinding> knowledgeBindings,
    QuestionSettlementStatus settlementStatus,
    long settlementVersion,
    BigDecimal earnedScore,
    int correctCount,
    int wrongCount,
    int pendingReviewCount
) {
  public PracticeQuestionSnapshot(
      Long questionId,
      int questionOrder,
      int contentVersion,
      String questionType,
      String title,
      List<Stem> stems,
      List<SubQuestion> subQuestions,
      List<KnowledgeBinding> knowledgeBindings,
      QuestionSettlementStatus settlementStatus,
      long settlementVersion,
      BigDecimal earnedScore,
      int correctCount,
      int wrongCount,
      int pendingReviewCount
  ) {
    this(questionId, null, questionOrder, contentVersion, questionType, title, stems, subQuestions,
        knowledgeBindings, settlementStatus, settlementVersion, earnedScore, correctCount, wrongCount,
        pendingReviewCount);
  }

  public PracticeQuestionSnapshot {
    stems = stems == null ? List.of() : List.copyOf(stems);
    subQuestions = subQuestions == null ? List.of() : List.copyOf(subQuestions);
    knowledgeBindings = knowledgeBindings == null ? List.of() : List.copyOf(knowledgeBindings);
    settlementStatus = settlementStatus == null ? QuestionSettlementStatus.UNSETTLED : settlementStatus;
    earnedScore = earnedScore == null ? BigDecimal.ZERO : earnedScore;
  }

  public PracticeQuestionSnapshot settle(BigDecimal earnedScore, int correctCount, int wrongCount,
                                         int pendingReviewCount) {
    return new PracticeQuestionSnapshot(questionId, questionVersionId, questionOrder, contentVersion, questionType,
        title, stems, subQuestions, knowledgeBindings, QuestionSettlementStatus.SETTLED, settlementVersion + 1,
        earnedScore, correctCount, wrongCount, pendingReviewCount);
  }

  public record Stem(Long stemId, String stemType, int sortOrder, String content) {
    public Stem(Long stemId, int sortOrder, String content) {
      this(stemId, "MAIN_STEM", sortOrder, content);
    }
  }

  public record SubQuestion(
      Long subQuestionId,
      String subQuestionType,
      String answerFormat,
      String content,
      Map<String, Object> metadata,
      List<Option> options,
      PracticeAnswerPayload standardAnswer,
      String solution,
      List<ScoringPoint> scoringPoints,
      BigDecimal score
  ) {
    public SubQuestion(
        Long subQuestionId,
        String subQuestionType,
        String content,
        List<Option> options,
        PracticeAnswerPayload standardAnswer,
        String solution,
        BigDecimal score
    ) {
      this(subQuestionId, subQuestionType, inferAnswerFormat(subQuestionType), content, Map.of(), options,
          standardAnswer, solution, List.of(), score);
    }

    public SubQuestion {
      metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
      options = options == null ? List.of() : List.copyOf(options);
      scoringPoints = scoringPoints == null ? List.of() : List.copyOf(scoringPoints);
      score = score == null ? BigDecimal.ZERO : score;
    }
  }

  public record Option(String optionKey, String content, int sortOrder) {
  }

  public record ScoringPoint(Long scoringPointId, String pointCode, String pointText, BigDecimal score,
                             int sortOrder) {
    public ScoringPoint {
      score = score == null ? BigDecimal.ZERO : score;
    }
  }

  public record KnowledgeBinding(String knowledgeType, Long knowledgeId, String knowledgeName) {
  }

  private static String inferAnswerFormat(String subQuestionType) {
    if ("JUDGE".equals(subQuestionType)) {
      return "BOOLEAN";
    }
    if ("SINGLE_CHOICE".equals(subQuestionType) || "MULTI_CHOICE".equals(subQuestionType)) {
      return "OPTION";
    }
    return "TEXT";
  }
}
