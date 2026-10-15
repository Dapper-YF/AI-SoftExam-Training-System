package com.aiexam.essay.repository;

import com.aiexam.common.api.PageResponse;
import com.aiexam.essay.dto.EssayBackgroundDto;
import com.aiexam.essay.dto.EssayDraftDto;
import com.aiexam.essay.dto.EssayMaterialDto;
import com.aiexam.essay.dto.request.EssayBackgroundCreateRequest;
import com.aiexam.essay.dto.request.EssayBackgroundUpdateRequest;
import com.aiexam.essay.dto.request.EssayDraftCreateRequest;
import com.aiexam.essay.dto.request.EssayDraftUpdateRequest;
import com.aiexam.essay.dto.request.EssayMaterialCreateRequest;
import com.aiexam.essay.dto.request.EssayMaterialUpdateRequest;
import java.util.List;
import java.util.Optional;

public interface EssayTrainingRepository {
  EssayBackgroundDto insertBackground(Long id, Long userId, EssayBackgroundCreateRequest request, String sourceType);

  Optional<EssayBackgroundDto> findBackground(Long id, Long userId);

  PageResponse<EssayBackgroundDto> findBackgrounds(Long userId, long pageNo, long pageSize);

  EssayBackgroundDto updateBackground(Long id, Long userId, EssayBackgroundUpdateRequest request);

  EssayMaterialDto insertMaterial(Long id, Long userId, EssayMaterialCreateRequest request, String sourceType);

  Optional<EssayMaterialDto> findMaterial(Long id, Long userId);

  PageResponse<EssayMaterialDto> findMaterials(Long userId, long pageNo, long pageSize);

  EssayMaterialDto updateMaterial(Long id, Long userId, EssayMaterialUpdateRequest request);

  EssayDraftDto insertDraft(Long id, Long userId, EssayDraftCreateRequest request, int wordCount);

  Optional<EssayDraftDto> findDraft(Long id, Long userId);

  PageResponse<EssayDraftDto> findDrafts(Long userId, long pageNo, long pageSize);

  EssayDraftDto updateDraft(Long id, Long userId, EssayDraftUpdateRequest request, int wordCount);

  int nextRevisionNo(Long draftId);

  void insertDraftRevision(Long id, Long draftId, int revisionNo, String contentSnapshot, String summary, Integer wordCount, Long userId);

  boolean allMaterialsAccessible(Long userId, List<Long> materialIds);

  void replaceDraftMaterials(Long draftId, List<Long> materialIds);
}
