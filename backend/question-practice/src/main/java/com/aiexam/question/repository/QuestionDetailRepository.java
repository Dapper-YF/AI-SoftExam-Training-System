package com.aiexam.question.repository;

import com.aiexam.common.api.PageResponse;
import com.aiexam.question.dto.QuestionDetailDto;
import com.aiexam.question.dto.QuestionExplanationDto;
import com.aiexam.question.dto.QuestionSummaryDto;
import com.aiexam.question.dto.request.QuestionCreateRequest;
import com.aiexam.question.dto.request.QuestionUpdateRequest;
import java.util.List;
import java.util.Optional;

public interface QuestionDetailRepository {
  Optional<QuestionDetailDto> findPublishedDetail(Long questionId);

  Optional<QuestionDetailDto> findAdminDetail(Long questionId);

  Optional<QuestionExplanationDto> findPublishedExplanation(Long questionId);

  PageResponse<QuestionSummaryDto> findAdminQuestions(long pageNo, long pageSize);

  PageResponse<QuestionSummaryDto> findPublishedQuestions(long pageNo, long pageSize);

  void insertQuestion(Long questionId, QuestionCreateRequest request, String status);

  void updateQuestion(Long questionId, QuestionUpdateRequest request);

  void insertStem(Long stemId, Long questionId, QuestionCreateRequest.StemRequest request);

  void insertSubQuestion(Long subQuestionId, Long questionId, QuestionCreateRequest.SubQuestionRequest request);

  void insertOption(Long optionId, Long subQuestionId, QuestionCreateRequest.OptionRequest request);

  void insertAnswerKey(Long answerKeyId, Long subQuestionId, QuestionCreateRequest.AnswerKeyRequest request);

  void insertSolution(Long solutionId, Long questionId, Long subQuestionId, QuestionCreateRequest.SolutionRequest request);

  void insertScoringRule(Long ruleId, Long subQuestionId, QuestionCreateRequest.ScoringRuleRequest request);

  void insertKnowledgeBinding(
      Long bindingId,
      Long questionId,
      Long subQuestionId,
      String targetType,
      Long targetId,
      QuestionCreateRequest.KnowledgeBindingRequest request);

  void publish(Long questionId);

  Optional<QuestionSummaryDto> findQuestionSummary(Long questionId);

  boolean subQuestionBelongsToQuestion(Long subQuestionId, Long questionId);

  List<QuestionCreateRequest.KnowledgeBindingRequest> findKnowledgeBindings(Long questionId);

  List<SubQuestionValidationRow> findSubQuestionValidationRows(Long questionId);

  long countStems(Long questionId);

  record SubQuestionValidationRow(
      Long subQuestionId,
      String subQuestionType,
      String gradingMode,
      long optionCount,
      long answerKeyCount
  ) {
  }
}
