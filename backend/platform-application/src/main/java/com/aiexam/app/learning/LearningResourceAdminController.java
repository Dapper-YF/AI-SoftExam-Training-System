package com.aiexam.app.learning;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.common.api.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/v1/admin/learning/resources")
@PreAuthorize("hasRole('ADMIN')")
public class LearningResourceAdminController {
  private final LearningResourceService service;
  private final LearningResourcePdfUploadService uploadService;

  public LearningResourceAdminController(LearningResourceService service, LearningResourcePdfUploadService uploadService) {
    this.service = service;
    this.uploadService = uploadService;
  }

  @GetMapping
  public ApiResponse<PageResponse<LearningResourceDto>> page(
      @RequestParam(name = "qualificationId", required = false) @Positive Long qualificationId,
      @RequestParam(name = "type", required = false) String type,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "pageNo", defaultValue = "1") @Min(1) long pageNo,
      @RequestParam(name = "pageSize", defaultValue = "20") @Min(1) @Max(100) long pageSize) {
    LearningResourceType parsedType = type == null || type.isBlank() ? null : LearningResourceType.valueOf(type.trim().toUpperCase());
    LearningResourceStatus parsedStatus = status == null || status.isBlank() ? null : LearningResourceStatus.valueOf(status.trim().toUpperCase());
    return ApiResponse.ok(service.page(qualificationId, parsedType, parsedStatus, pageNo, pageSize));
  }

  @GetMapping("/{id}")
  public ApiResponse<LearningResourceDto> detail(@PathVariable(name = "id") @Positive long id) {
    return ApiResponse.ok(service.detail(id));
  }

  @PostMapping
  public ApiResponse<LearningResourceDto> create(@Valid @RequestBody LearningResourceCreateRequest request) {
    return ApiResponse.ok(service.create(request));
  }

  @PutMapping("/{id}")
  public ApiResponse<LearningResourceDto> update(@PathVariable(name = "id") @Positive long id,
      @Valid @RequestBody LearningResourceUpdateRequest request) {
    return ApiResponse.ok(service.update(id, request));
  }

  @PostMapping("/{id}/publish")
  public ApiResponse<LearningResourceDto> publish(@PathVariable(name = "id") @Positive long id) {
    return ApiResponse.ok(service.changeStatus(id, LearningResourceStatus.PUBLISHED));
  }

  @PostMapping("/{id}/offline")
  public ApiResponse<LearningResourceDto> offline(@PathVariable(name = "id") @Positive long id) {
    return ApiResponse.ok(service.changeStatus(id, LearningResourceStatus.OFFLINE));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable(name = "id") @Positive long id) {
    service.delete(id);
    return ApiResponse.ok(null);
  }

  @PostMapping("/{id}/versions")
  public ApiResponse<LearningResourceVersionDto> addVersion(@PathVariable(name = "id") @Positive long id,
      @Valid @RequestBody LearningResourceVersionRequest request) {
    return ApiResponse.ok(service.addVersion(id, request));
  }

  @PostMapping(value = "/{id}/versions/upload", consumes = "multipart/form-data")
  public ApiResponse<LearningResourceVersionDto> uploadVersion(@PathVariable(name = "id") @Positive long id,
      @RequestPart("file") MultipartFile file,
      @RequestPart(required = false) String versionLabel,
      @RequestPart(required = false) String sourceName) {
    var uploaded = uploadService.store(file);
    try {
      var request = new LearningResourceVersionRequest(versionLabel, sourceName, null,
          "/api/v1/learning/resource-files/" + uploaded.token(), uploaded.fileName(),
          "application/pdf", uploaded.fileSize(), uploaded.sha256(), uploaded.pageCount(),
          uploaded.coverToken() == null ? null : "/api/v1/learning/resource-covers/" + uploaded.coverToken(), "INTERNAL_ONLY");
      return ApiResponse.ok(service.addVersion(id, request));
    } catch (RuntimeException exception) {
      uploadService.delete(uploaded.token());
      throw exception;
    }
  }

  @GetMapping("/{id}/versions")
  public ApiResponse<List<LearningResourceVersionDto>> versions(@PathVariable(name = "id") @Positive long id) {
    return ApiResponse.ok(service.versions(id));
  }

  @PostMapping("/{id}/versions/{versionId}/publish")
  public ApiResponse<Void> publishVersion(@PathVariable(name = "id") @Positive long id, @PathVariable(name = "versionId") @Positive long versionId) {
    service.publishVersion(id, versionId);
    return ApiResponse.ok(null);
  }

  @PostMapping("/{id}/versions/{versionId}/rebuild-preview")
  public ApiResponse<LearningResourceVersionDto> rebuildPreview(@PathVariable(name = "id") @Positive long id,
      @PathVariable(name = "versionId") @Positive long versionId) {
    var version = service.versions(id).stream().filter(item -> item.id().equals(versionId)).findFirst()
        .orElseThrow(() -> new com.aiexam.common.error.BusinessException("LEARNING_RESOURCE_VERSION_NOT_FOUND",
            "Learning resource version does not exist", org.springframework.http.HttpStatus.NOT_FOUND));
    String marker = "/resource-files/";
    int start = version.fileUrl().indexOf(marker);
    if (start < 0) throw new com.aiexam.common.error.BusinessException("LEARNING_RESOURCE_FILE_NOT_LOCAL",
        "Only locally uploaded PDF files can rebuild preview", org.springframework.http.HttpStatus.BAD_REQUEST);
    String token = version.fileUrl().substring(start + marker.length());
    return ApiResponse.ok(service.rebuildPreview(versionId, uploadService.rebuildPreview(token)));
  }

  @PostMapping("/{id}/versions/{versionId}/extract-chapters")
  public ApiResponse<List<LearningChapterWriteRequest>> extractChapters(@PathVariable(name = "id") @Positive long id,
      @PathVariable(name = "versionId") @Positive long versionId) {
    var version = service.versions(id).stream().filter(item -> item.id().equals(versionId)).findFirst()
        .orElseThrow(() -> new com.aiexam.common.error.BusinessException("LEARNING_RESOURCE_VERSION_NOT_FOUND",
            "Learning resource version does not exist", org.springframework.http.HttpStatus.NOT_FOUND));
    String marker = "/resource-files/";
    int start = version.fileUrl().indexOf(marker);
    if (start < 0) throw new com.aiexam.common.error.BusinessException("LEARNING_RESOURCE_FILE_NOT_LOCAL",
        "Only locally uploaded PDF files can extract chapters", org.springframework.http.HttpStatus.BAD_REQUEST);
    return ApiResponse.ok(uploadService.extractChapters(version.fileUrl().substring(start + marker.length())));
  }

  @GetMapping("/versions/{versionId}/chapters")
  public ApiResponse<List<LearningChapterDto>> chapters(@PathVariable(name = "versionId") @Positive long versionId) {
    return ApiResponse.ok(service.chapters(versionId));
  }

  @GetMapping("/chapters/{chapterId}/knowledge-points")
  public ApiResponse<List<LearningKnowledgePointDto>> chapterKnowledgePoints(@PathVariable(name = "chapterId") @Positive long chapterId) {
    return ApiResponse.ok(service.chapterKnowledgePoints(chapterId));
  }

  @PutMapping("/chapters/{chapterId}/knowledge-points")
  public ApiResponse<List<LearningKnowledgePointDto>> replaceChapterKnowledgePoints(@PathVariable(name = "chapterId") @Positive long chapterId,
      @Valid @RequestBody LearningChapterKnowledgePointRequest request) {
    return ApiResponse.ok(service.replaceChapterKnowledgePoints(chapterId, request.knowledgePointIds()));
  }

  @PutMapping("/versions/{versionId}/chapters")
  public ApiResponse<List<LearningChapterDto>> replaceChapters(@PathVariable(name = "versionId") @Positive long versionId,
      @Valid @RequestBody List<@Valid LearningChapterWriteRequest> chapters) {
    return ApiResponse.ok(service.replaceChapters(versionId, chapters));
  }

  @GetMapping("/knowledge-points")
  public ApiResponse<List<LearningKnowledgePointDto>> knowledgePoints(@RequestParam @Positive long qualificationId) {
    return ApiResponse.ok(service.knowledgePoints(qualificationId));
  }

  @PostMapping("/knowledge-points")
  public ApiResponse<LearningKnowledgePointDto> createKnowledgePoint(@Valid @RequestBody LearningKnowledgePointRequest request) {
    return ApiResponse.ok(service.createKnowledgePoint(request));
  }
}
