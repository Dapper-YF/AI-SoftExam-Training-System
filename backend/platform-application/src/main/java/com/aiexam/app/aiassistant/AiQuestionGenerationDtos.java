package com.aiexam.app.aiassistant;

import java.time.Instant;
import java.util.List;

public final class AiQuestionGenerationDtos {
  private AiQuestionGenerationDtos() { }

  public record GenerateRequest(
      Long qualificationId, Long subjectId, String knowledgeType, Long knowledgeId, String knowledgeName,
      String generationMode, String difficulty, String weakPointSummary, Double targetAccuracy
  ) { }

  public record QuestionContent(String stem, List<Option> options, String answer, String analysis) { }
  public record Option(String key, String content) { }
  public record GeneratedQuestionDto(
      Long id, Long qualificationId, Long subjectId, String knowledgeType, Long knowledgeId, String knowledgeName,
      String generationMode, String difficulty, QuestionContent question, String status, Instant createdAt
  ) { }

  public record WeakPointDto(String knowledgeType, Long knowledgeId, String knowledgeName, long mistakeCount) { }
  public record AdaptiveDifficultyDto(String difficulty, long mistakeCount) { }

  public record FeedbackRequest(String feedbackCode, String comment) { }
  public record ReviewRequest(String status, QuestionContent question, String reviewNote) { }
}
