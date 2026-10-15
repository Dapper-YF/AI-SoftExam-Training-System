package com.aiexam.essay.runtime;

import java.util.Optional;

public interface EssayAiRuntimeRepository {
  Optional<EssayDraftRuntimeView> findDraft(Long userId, Long draftId);

  EssayReviewTaskDto createReviewTask(Long userId, Long draftId);

  EssayReviewTaskDto bindReviewAiTask(Long userId, Long reviewTaskId, Long aiTaskId);

  EssayReviewTaskDto markReviewTaskFailed(Long userId, Long reviewTaskId, String failureCode, String failureMessage);

  Optional<EssayReviewTaskDto> findReviewTask(Long userId, Long reviewTaskId);

  Optional<EssayReviewResultDto> findReviewResult(Long userId, Long reviewTaskId);

  EssayRuntimePage<EssayReviewResultDto> listReviewHistory(Long userId, int pageNo, int pageSize);
}
