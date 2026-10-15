package com.aiexam.app.aiassistant;

import com.aiexam.app.aiassistant.AiAssistantDtos.*;
import com.aiexam.common.api.ApiResponse;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/knowledge-documents")
@PreAuthorize("hasRole('ADMIN')")
public class KnowledgeAdminController {
  private final AiAssistantService service;
  private final KnowledgeIndexingService indexingService;
  public KnowledgeAdminController(AiAssistantService service, KnowledgeIndexingService indexingService) {
    this.service = service;
    this.indexingService = indexingService;
  }
  @PostMapping public ApiResponse<KnowledgeDocumentDto> create(@RequestBody KnowledgeDocumentRequest request) {
    KnowledgeDocumentDto document = service.createKnowledge(request);
    return ApiResponse.ok(indexingService.indexDocument(document.id()));
  }
  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<KnowledgeDocumentDto> upload(
      @RequestParam Long qualificationId,
      @RequestParam(required = false) Long subjectId,
      @RequestParam String documentCode,
      @RequestParam String title,
      @RequestParam String sourceName,
      @RequestParam(required = false) String sourceUrl,
      @RequestParam String copyrightBasis,
      @RequestParam(required = false) String knowledgeType,
      @RequestParam(required = false) Long knowledgeId,
      @RequestPart("file") MultipartFile file
  ) {
    KnowledgeDocumentDto document = service.createKnowledgeFromUpload(new KnowledgeDocumentRequest(
        qualificationId, subjectId, documentCode, title, sourceName, sourceUrl, copyrightBasis,
        null, knowledgeType, knowledgeId), file);
    return ApiResponse.ok(indexingService.indexDocument(document.id()));
  }
  @PostMapping("/{id}/publish") public ApiResponse<KnowledgeDocumentDto> publish(@PathVariable Long id) { return ApiResponse.ok(service.publishKnowledge(id)); }
  @PostMapping("/{id}/offline") public ApiResponse<KnowledgeDocumentDto> offline(@PathVariable Long id) {
    KnowledgeDocumentDto document = service.offlineKnowledge(id);
    indexingService.invalidateDocument(id);
    return ApiResponse.ok(document);
  }
  @PostMapping("/{id}/reindex") public ApiResponse<KnowledgeDocumentDto> reindex(@PathVariable Long id) {
    return ApiResponse.ok(indexingService.indexDocument(id));
  }
  @PostMapping("/{id}/review") public ApiResponse<KnowledgeDocumentDto> review(@PathVariable Long id) {
    return ApiResponse.ok(service.reviewKnowledge(id));
  }
  @GetMapping("/{id}/fragments") public ApiResponse<List<KnowledgeFragmentDto>> fragments(@PathVariable Long id) {
    return ApiResponse.ok(service.listKnowledgeFragments(id));
  }
  @GetMapping public ApiResponse<List<KnowledgeDocumentDto>> list() { return ApiResponse.ok(service.listKnowledge()); }
}
