package com.aiexam.question.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

public record QuestionDetailDto(
    Long questionId,
    Long qualificationId,
    Long subjectId,
    String questionType,
    String title,
    List<StemDto> stems,
    List<SubQuestionDto> subQuestions,
    List<OptionDto> options,
    String answerFormat,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    List<SolutionDto> solutions,
    List<KnowledgeBindingDto> knowledgeBindings
) {
  public record StemDto(Long stemId, String stemType, String content, String attachmentUrl, int sortOrder) {
  }

  public record SubQuestionDto(
      Long subQuestionId,
      int subQuestionNo,
      String subQuestionType,
      String content,
      BigDecimal score,
      String answerFormat,
      List<OptionDto> options
  ) {
  }

  public record OptionDto(Long optionId, String optionKey, String optionContent, int sortOrder) {
  }

  public record SolutionDto(Long solutionId, Long subQuestionId, String solutionType, String content) {
  }

  public record KnowledgeBindingDto(
      Long bindingId,
      String targetType,
      Long targetId,
      String knowledgeType,
      Long knowledgeId
  ) {
  }
}
