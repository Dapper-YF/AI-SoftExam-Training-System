package com.aiexam.question.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record QuestionCreateRequest(
    @NotNull @Positive Long qualificationId,
    @NotNull @Positive Long subjectId,
    @NotBlank @Size(max = 32) String questionType,
    @Size(max = 180) String title,
    Integer yearNo,
    @Size(max = 24) String difficulty,
    @Size(max = 32) String sourceType,
    @Size(max = 255) String sourceRef,
    @NotEmpty @Valid List<StemRequest> stems,
    @NotEmpty @Valid List<SubQuestionRequest> subQuestions,
    @Valid List<KnowledgeBindingRequest> knowledgeBindings
) {
  public record StemRequest(
      @NotBlank @Size(max = 32) String stemType,
      String content,
      @Size(max = 500) String attachmentUrl,
      Integer sortOrder,
      @Size(max = 64) String stemKey,
      Long resourceId
  ) {
  }

  public record SubQuestionRequest(
      @NotNull @Positive Integer subQuestionNo,
      @NotBlank @Size(max = 32) String subQuestionType,
      @NotBlank String content,
      BigDecimal score,
      @NotBlank @Size(max = 32) String answerFormat,
      @NotBlank @Size(max = 32) String gradingMode,
      Integer sortOrder,
      String metadataJson,
      @Valid List<OptionRequest> options,
      @Valid List<AnswerKeyRequest> answerKeys,
      @Valid List<SolutionRequest> solutions,
      @Valid List<ScoringRuleRequest> scoringRules,
      @Valid List<ScoringPointRequest> scoringPoints,
      @Valid List<KnowledgeBindingRequest> knowledgeBindings
  ) {
  }

  public record OptionRequest(
      @NotBlank @Size(max = 16) String optionKey,
      @NotBlank String optionContent,
      Integer sortOrder
  ) {
  }

  public record AnswerKeyRequest(
      @NotBlank @Size(max = 32) String answerFormat,
      String answerValue,
      String answerJson
  ) {
  }

  public record SolutionRequest(
      @NotBlank @Size(max = 32) String solutionType,
      @NotBlank String content,
      Integer sortOrder
  ) {
  }

  public record ScoringRuleRequest(
      @NotBlank @Size(max = 32) String ruleType,
      @NotBlank String ruleJson,
      Integer sortOrder
  ) {
  }

  public record ScoringPointRequest(
      @NotBlank @Size(max = 64) String pointCode,
      @NotBlank String pointText,
      @NotNull @Positive BigDecimal score,
      @NotNull @Positive Integer sortOrder
  ) {
  }

  public record KnowledgeBindingRequest(
      @Size(max = 32) String targetType,
      Long targetId,
      @NotBlank @Size(max = 32) String knowledgeType,
      @NotNull @Positive Long knowledgeId,
      @Size(max = 96) String knowledgeCode,
      BigDecimal weight,
      Integer sortOrder
  ) {
  }
}
