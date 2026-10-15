package com.aiexam.question.repository;

import com.aiexam.common.api.PageResponse;
import com.aiexam.question.dto.PaperDetailDto;
import com.aiexam.question.dto.PaperQuestionSummaryDto;
import com.aiexam.question.dto.PaperSummaryDto;
import com.aiexam.question.dto.request.PaperCreateRequest;
import com.aiexam.question.dto.request.PaperQuestionAddRequest;
import com.aiexam.question.dto.request.PaperUpdateRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PaperRepository {
  void insertPaper(Long paperId, PaperCreateRequest request, String status);

  void updatePaper(Long paperId, PaperUpdateRequest request);

  void insertPaperQuestion(
      Long paperQuestionId,
      Long paperId,
      PaperQuestionAddRequest request,
      String questionSnapshot);

  void publishPaper(Long paperId, String ruleSnapshot);

  void updatePaperQuestionSnapshot(Long paperId, Long questionId, String questionSnapshot);

  Optional<PaperSummaryDto> findPaperSummary(Long paperId);

  Optional<PaperDetailDto> findAdminPaperDetail(Long paperId);

  Optional<PaperDetailDto> findPublishedPaperDetail(Long paperId);

  PageResponse<PaperSummaryDto> findPublishedPapers(
      Long qualificationId,
      Long subjectId,
      String paperType,
      Integer yearNo,
      long pageNo,
      long pageSize);

  PageResponse<PaperSummaryDto> findAdminPapers(
      String status, String paperType, Long qualificationId, Long subjectId, String keyword, Integer yearNo,
      long pageNo, long pageSize);

  List<PaperQuestionSummaryDto> findPaperQuestions(Long paperId);

  boolean existsPaperQuestionNo(Long paperId, Integer questionNo);

  boolean existsPaperQuestionId(Long paperId, Long questionId);

  BigDecimal sumPaperQuestionScores(Long paperId);

  long countPaperQuestions(Long paperId);

  long countUnpublishedPaperQuestions(Long paperId);

  long countPaperQuestionsWithoutKnowledgeTag(Long paperId);

  List<String> findPaperStemContents(Long paperId);

  void discardDraftPaper(Long paperId);

  void clearPaperQuestions(Long paperId);
}
