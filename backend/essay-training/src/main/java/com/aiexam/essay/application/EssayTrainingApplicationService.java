package com.aiexam.essay.application;

import com.aiexam.common.api.PageResponse;
import com.aiexam.common.error.ResourceNotFoundException;
import com.aiexam.essay.dto.EssayBackgroundDto;
import com.aiexam.essay.dto.EssayDraftDto;
import com.aiexam.essay.dto.EssayMaterialDto;
import com.aiexam.essay.dto.request.EssayBackgroundCreateRequest;
import com.aiexam.essay.dto.request.EssayBackgroundGenerateRequest;
import com.aiexam.essay.dto.request.EssayBackgroundUpdateRequest;
import com.aiexam.essay.dto.request.EssayDraftCreateRequest;
import com.aiexam.essay.dto.request.EssayDraftMaterialBindRequest;
import com.aiexam.essay.dto.request.EssayDraftUpdateRequest;
import com.aiexam.essay.dto.request.EssayMaterialCreateRequest;
import com.aiexam.essay.dto.request.EssayMaterialGenerateRequest;
import com.aiexam.essay.dto.request.EssayMaterialUpdateRequest;
import com.aiexam.essay.repository.EssayTrainingRepository;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EssayTrainingApplicationService {
  private static final AtomicLong ID_SEQUENCE = new AtomicLong();

  private final EssayTrainingRepository essayTrainingRepository;

  public EssayTrainingApplicationService(EssayTrainingRepository essayTrainingRepository) {
    this.essayTrainingRepository = essayTrainingRepository;
  }

  public String moduleStatus() {
    return "BOUNDARY_ONLY";
  }

  @Transactional
  public EssayBackgroundDto generateBackground(Long userId, EssayBackgroundGenerateRequest request) {
    EssayBackgroundCreateRequest createRequest = new EssayBackgroundCreateRequest(
        request.qualificationId(),
        generatedBackgroundTitle(request),
        request.industry(),
        request.projectScale(),
        request.projectCycle(),
        request.techStack(),
        request.teamSize(),
        generatedBackgroundContent(request),
        "ACTIVE");
    return essayTrainingRepository.insertBackground(nextId(), userId, createRequest, "AI");
  }

  public PageResponse<EssayBackgroundDto> listBackgrounds(Long userId, long pageNo, long pageSize) {
    return essayTrainingRepository.findBackgrounds(userId, pageNo(pageNo), pageSize(pageSize));
  }

  @Transactional
  public EssayBackgroundDto createBackground(Long userId, EssayBackgroundCreateRequest request) {
    return essayTrainingRepository.insertBackground(nextId(), userId, request, "MANUAL");
  }

  @Transactional
  public EssayBackgroundDto updateBackground(Long userId, Long backgroundId, EssayBackgroundUpdateRequest request) {
    requireBackground(userId, backgroundId);
    return essayTrainingRepository.updateBackground(backgroundId, userId, request);
  }

  @Transactional
  public EssayMaterialDto generateMaterial(Long userId, EssayMaterialGenerateRequest request) {
    if (request.backgroundId() != null) {
      requireBackground(userId, request.backgroundId());
    }
    String title = request.title() == null || request.title().isBlank()
        ? "Mock " + request.materialType() + "素材"
        : request.title();
    EssayMaterialCreateRequest createRequest = new EssayMaterialCreateRequest(
        request.qualificationId(),
        request.backgroundId(),
        request.categoryId(),
        request.materialType(),
        title,
        generatedMaterialContent(request, title),
        "DRAFT",
        request.tags());
    return essayTrainingRepository.insertMaterial(nextId(), userId, createRequest, "AI");
  }

  public PageResponse<EssayMaterialDto> listMaterials(Long userId, long pageNo, long pageSize) {
    return essayTrainingRepository.findMaterials(userId, pageNo(pageNo), pageSize(pageSize));
  }

  @Transactional
  public EssayMaterialDto createMaterial(Long userId, EssayMaterialCreateRequest request) {
    if (request.backgroundId() != null) {
      requireBackground(userId, request.backgroundId());
    }
    return essayTrainingRepository.insertMaterial(nextId(), userId, request, "MANUAL");
  }

  @Transactional
  public EssayMaterialDto updateMaterial(Long userId, Long materialId, EssayMaterialUpdateRequest request) {
    requireMaterial(userId, materialId);
    if (request.backgroundId() != null) {
      requireBackground(userId, request.backgroundId());
    }
    return essayTrainingRepository.updateMaterial(materialId, userId, request);
  }

  @Transactional
  public EssayDraftDto createDraft(Long userId, EssayDraftCreateRequest request) {
    if (request.backgroundId() != null) {
      requireBackground(userId, request.backgroundId());
    }
    return essayTrainingRepository.insertDraft(nextId(), userId, request, wordCount(request.content()));
  }

  public PageResponse<EssayDraftDto> listDrafts(Long userId, long pageNo, long pageSize) {
    return essayTrainingRepository.findDrafts(userId, pageNo(pageNo), pageSize(pageSize));
  }

  public EssayDraftDto getDraft(Long userId, Long draftId) {
    return requireDraft(userId, draftId);
  }

  @Transactional
  public EssayDraftDto updateDraft(Long userId, Long draftId, EssayDraftUpdateRequest request) {
    EssayDraftDto current = requireDraft(userId, draftId);
    if (request.backgroundId() != null) {
      requireBackground(userId, request.backgroundId());
    }
    essayTrainingRepository.insertDraftRevision(
        nextId(),
        draftId,
        essayTrainingRepository.nextRevisionNo(draftId),
        current.content(),
        "保存草稿前自动快照",
        current.wordCount(),
        userId);
    String contentForCount = request.content() == null ? current.content() : request.content();
    return essayTrainingRepository.updateDraft(draftId, userId, request, wordCount(contentForCount));
  }

  @Transactional
  public EssayDraftDto bindDraftMaterials(Long userId, Long draftId, EssayDraftMaterialBindRequest request) {
    requireDraft(userId, draftId);
    List<Long> materialIds = request.materialIds().stream().distinct().toList();
    if (!essayTrainingRepository.allMaterialsAccessible(userId, materialIds)) {
      throw new ResourceNotFoundException("论文素材不存在");
    }
    essayTrainingRepository.replaceDraftMaterials(draftId, materialIds);
    return requireDraft(userId, draftId);
  }

  private EssayBackgroundDto requireBackground(Long userId, Long backgroundId) {
    return essayTrainingRepository.findBackground(backgroundId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("项目背景不存在"));
  }

  private EssayMaterialDto requireMaterial(Long userId, Long materialId) {
    return essayTrainingRepository.findMaterial(materialId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("论文素材不存在"));
  }

  private EssayDraftDto requireDraft(Long userId, Long draftId) {
    return essayTrainingRepository.findDraft(draftId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("论文草稿不存在"));
  }

  private String generatedBackgroundTitle(EssayBackgroundGenerateRequest request) {
    String industry = request.industry() == null || request.industry().isBlank() ? "项目" : request.industry();
    return industry + "项目背景";
  }

  private String generatedBackgroundContent(EssayBackgroundGenerateRequest request) {
    return """
        Mock项目背景：本项目面向%s场景，项目规模为%s，周期%s，技术栈包括%s，团队规模%s。
        项目目标是建设稳定可用的信息系统，支撑业务流程线上化、数据沉淀和运营分析。
        """
        .formatted(
            value(request.industry()),
            value(request.projectScale()),
            value(request.projectCycle()),
            value(request.techStack()),
            value(request.teamSize()))
        .trim();
  }

  private String generatedMaterialContent(EssayMaterialGenerateRequest request, String title) {
    return """
        Mock论文素材：%s。
        围绕%s主题，可描述目标分解、过程执行、工具使用、问题跟踪和成果物沉淀。
        在论文中建议结合项目背景补充可量化结果，避免泛泛而谈。
        """
        .formatted(title, value(request.topic()))
        .trim();
  }

  private String value(String value) {
    return value == null || value.isBlank() ? "未填写" : value;
  }

  private int wordCount(String content) {
    if (content == null || content.isBlank()) {
      return 0;
    }
    return (int) content.codePoints()
        .filter(codePoint -> !Character.isWhitespace(codePoint))
        .count();
  }

  private long pageNo(long pageNo) {
    return pageNo < 1 ? 1 : pageNo;
  }

  private long pageSize(long pageSize) {
    if (pageSize < 1) {
      return 20;
    }
    return Math.min(pageSize, 100);
  }

  private Long nextId() {
    return System.currentTimeMillis() * 100_000 + Math.floorMod(ID_SEQUENCE.getAndIncrement(), 100_000);
  }
}
