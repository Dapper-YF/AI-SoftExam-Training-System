package com.aiexam.app.controller;

import com.aiexam.app.learning.LearningResourceDto;
import com.aiexam.app.learning.LearningResourceRepository;
import com.aiexam.app.learning.LearningResourceStatus;
import com.aiexam.app.learning.LearningResourceVersionDto;
import com.aiexam.app.learning.LearningChapterDto;
import com.aiexam.app.learning.LearningResourcePdfUploadService;
import com.aiexam.app.learning.LearningResourceSearchResult;
import com.aiexam.app.learning.LearningKnowledgeGraphEdgeDto;
import com.aiexam.app.learning.LearningResourceService;
import com.aiexam.common.api.ApiResponse;
import com.aiexam.common.api.PageResponse;
import java.util.List;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/learning/resources")
public class LearningResourcePublicController {
  private final LearningResourceRepository repository;
  private final LearningResourcePdfUploadService pdfService;
  private final LearningResourceService service;

  public LearningResourcePublicController(LearningResourceRepository repository, LearningResourcePdfUploadService pdfService,
      LearningResourceService service) {
    this.repository = repository;
    this.pdfService = pdfService;
    this.service = service;
  }

  @GetMapping
  public ApiResponse<PageResponse<LearningResourceDto>> resources(
      @RequestParam(name = "qualificationId", required = false) @Positive Long qualificationId,
      @RequestParam(name = "pageNo", defaultValue = "1") long pageNo,
      @RequestParam(name = "pageSize", defaultValue = "20") long pageSize) {
    return ApiResponse.ok(repository.findPage(qualificationId, null, LearningResourceStatus.PUBLISHED, pageNo, pageSize));
  }

  @GetMapping("/{id}")
  public ApiResponse<LearningResourceDto> detail(@PathVariable(name = "id") @Positive long id) {
    LearningResourceDto resource = repository.find(id);
    if (resource.status() != LearningResourceStatus.PUBLISHED) {
      throw new com.aiexam.common.error.BusinessException("LEARNING_RESOURCE_NOT_FOUND", "Learning resource does not exist",
          org.springframework.http.HttpStatus.NOT_FOUND);
    }
    return ApiResponse.ok(resource);
  }

  @GetMapping("/{id}/versions")
  public ApiResponse<List<LearningResourceVersionDto>> versions(@PathVariable(name = "id") @Positive long id) {
    if (repository.find(id).status() != LearningResourceStatus.PUBLISHED) {
      throw new com.aiexam.common.error.BusinessException("LEARNING_RESOURCE_NOT_FOUND", "Learning resource does not exist",
          org.springframework.http.HttpStatus.NOT_FOUND);
    }
    return ApiResponse.ok(repository.versions(id).stream().filter(version -> "PUBLISHED".equals(version.status())).toList());
  }

  @GetMapping("/{id}/versions/{versionId}/chapters")
  public ApiResponse<List<LearningChapterDto>> chapters(@PathVariable(name = "id") @Positive long id, @PathVariable(name = "versionId") @Positive long versionId) {
    if (repository.find(id).status() != LearningResourceStatus.PUBLISHED
        || repository.versions(id).stream().noneMatch(version -> version.id().equals(versionId) && "PUBLISHED".equals(version.status()))) {
      throw new com.aiexam.common.error.BusinessException("LEARNING_RESOURCE_NOT_FOUND", "Learning resource does not exist",
          org.springframework.http.HttpStatus.NOT_FOUND);
    }
    return ApiResponse.ok(repository.chapters(versionId));
  }

  @GetMapping("/knowledge-graph")
  public ApiResponse<List<LearningKnowledgeGraphEdgeDto>> knowledgeGraph(
      @RequestParam(name = "qualificationId") @Positive long qualificationId) {
    return ApiResponse.ok(service.knowledgeGraph(qualificationId));
  }

  @GetMapping("/{id}/search")
  public ApiResponse<List<LearningResourceSearchResult>> search(@PathVariable(name = "id") @Positive long id,
      @RequestParam(name = "q") String keyword) {
    var resource = repository.find(id);
    if (resource.status() != LearningResourceStatus.PUBLISHED) {
      throw new com.aiexam.common.error.BusinessException("LEARNING_RESOURCE_NOT_FOUND", "Learning resource does not exist",
          org.springframework.http.HttpStatus.NOT_FOUND);
    }
    var version = repository.versions(id).stream().filter(item -> "PUBLISHED".equals(item.status())).findFirst()
        .orElseThrow(() -> new com.aiexam.common.error.BusinessException("LEARNING_RESOURCE_VERSION_NOT_FOUND", "Published version does not exist",
            org.springframework.http.HttpStatus.NOT_FOUND));
    String marker = "/resource-files/";
    int start = version.fileUrl().indexOf(marker);
    if (start < 0) return ApiResponse.ok(List.of());
    return ApiResponse.ok(pdfService.search(version.fileUrl().substring(start + marker.length()), keyword));
  }
}
