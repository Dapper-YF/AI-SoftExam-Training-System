package com.aiexam.essay.controller;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.common.api.PageResponse;
import com.aiexam.common.error.BusinessException;
import com.aiexam.essay.application.EssayTrainingApplicationService;
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
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/essay")
public class EssayTrainingController {
  private final EssayTrainingApplicationService essayTrainingApplicationService;

  public EssayTrainingController(EssayTrainingApplicationService essayTrainingApplicationService) {
    this.essayTrainingApplicationService = essayTrainingApplicationService;
  }

  @PostMapping("/backgrounds/generate")
  public ResponseEntity<ApiResponse<EssayBackgroundDto>> generateBackground(
      @Valid @RequestBody EssayBackgroundGenerateRequest request
  ) {
    EssayBackgroundDto data = essayTrainingApplicationService.generateBackground(currentUserId(), request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(data));
  }

  @GetMapping("/backgrounds")
  public ApiResponse<PageResponse<EssayBackgroundDto>> listBackgrounds(
      @RequestParam(name = "pageNo", defaultValue = "1") @Positive long pageNo,
      @RequestParam(name = "pageSize", defaultValue = "20") @Positive long pageSize
  ) {
    return ApiResponse.ok(essayTrainingApplicationService.listBackgrounds(currentUserId(), pageNo, pageSize));
  }

  @PostMapping("/backgrounds")
  public ResponseEntity<ApiResponse<EssayBackgroundDto>> createBackground(
      @Valid @RequestBody EssayBackgroundCreateRequest request
  ) {
    EssayBackgroundDto data = essayTrainingApplicationService.createBackground(currentUserId(), request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(data));
  }

  @PutMapping("/backgrounds/{id}")
  public ApiResponse<EssayBackgroundDto> updateBackground(
      @PathVariable("id") @Positive Long id,
      @RequestBody EssayBackgroundUpdateRequest request
  ) {
    return ApiResponse.ok(essayTrainingApplicationService.updateBackground(currentUserId(), id, request));
  }

  @PostMapping("/materials/generate")
  public ResponseEntity<ApiResponse<EssayMaterialDto>> generateMaterial(
      @Valid @RequestBody EssayMaterialGenerateRequest request
  ) {
    EssayMaterialDto data = essayTrainingApplicationService.generateMaterial(currentUserId(), request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(data));
  }

  @GetMapping("/materials")
  public ApiResponse<PageResponse<EssayMaterialDto>> listMaterials(
      @RequestParam(name = "pageNo", defaultValue = "1") @Positive long pageNo,
      @RequestParam(name = "pageSize", defaultValue = "20") @Positive long pageSize
  ) {
    return ApiResponse.ok(essayTrainingApplicationService.listMaterials(currentUserId(), pageNo, pageSize));
  }

  @PostMapping("/materials")
  public ResponseEntity<ApiResponse<EssayMaterialDto>> createMaterial(
      @Valid @RequestBody EssayMaterialCreateRequest request
  ) {
    EssayMaterialDto data = essayTrainingApplicationService.createMaterial(currentUserId(), request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(data));
  }

  @PutMapping("/materials/{id}")
  public ApiResponse<EssayMaterialDto> updateMaterial(
      @PathVariable("id") @Positive Long id,
      @RequestBody EssayMaterialUpdateRequest request
  ) {
    return ApiResponse.ok(essayTrainingApplicationService.updateMaterial(currentUserId(), id, request));
  }

  @PostMapping("/drafts")
  public ResponseEntity<ApiResponse<EssayDraftDto>> createDraft(
      @Valid @RequestBody EssayDraftCreateRequest request
  ) {
    EssayDraftDto data = essayTrainingApplicationService.createDraft(currentUserId(), request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(data));
  }

  @GetMapping("/drafts")
  public ApiResponse<PageResponse<EssayDraftDto>> listDrafts(
      @RequestParam(name = "pageNo", defaultValue = "1") @Positive long pageNo,
      @RequestParam(name = "pageSize", defaultValue = "20") @Positive long pageSize
  ) {
    return ApiResponse.ok(essayTrainingApplicationService.listDrafts(currentUserId(), pageNo, pageSize));
  }

  @GetMapping("/drafts/{id}")
  public ApiResponse<EssayDraftDto> getDraft(@PathVariable("id") @Positive Long id) {
    return ApiResponse.ok(essayTrainingApplicationService.getDraft(currentUserId(), id));
  }

  @PutMapping("/drafts/{id}")
  public ApiResponse<EssayDraftDto> updateDraft(
      @PathVariable("id") @Positive Long id,
      @RequestBody EssayDraftUpdateRequest request
  ) {
    return ApiResponse.ok(essayTrainingApplicationService.updateDraft(currentUserId(), id, request));
  }

  @PostMapping("/drafts/{id}/materials")
  public ApiResponse<EssayDraftDto> bindDraftMaterials(
      @PathVariable("id") @Positive Long id,
      @Valid @RequestBody EssayDraftMaterialBindRequest request
  ) {
    return ApiResponse.ok(essayTrainingApplicationService.bindDraftMaterials(currentUserId(), id, request));
  }

  private Long currentUserId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
      throw new BusinessException("UNAUTHORIZED", "未登录", HttpStatus.UNAUTHORIZED);
    }
    String username = authentication.getName();
    if ("anonymousUser".equals(username)) {
      throw new BusinessException("UNAUTHORIZED", "未登录", HttpStatus.UNAUTHORIZED);
    }
    if ("dev-admin".equals(username)) {
      return 1L;
    }
    if ("dev-student".equals(username)) {
      return 2L;
    }
    try {
      return Long.valueOf(username);
    } catch (NumberFormatException ignored) {
      return Integer.toUnsignedLong(username.hashCode());
    }
  }
}
