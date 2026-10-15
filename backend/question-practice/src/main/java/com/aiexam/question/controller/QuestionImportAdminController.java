package com.aiexam.question.controller;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.common.api.PageResponse;
import com.aiexam.question.admin.application.QuestionImportApplicationService;
import com.aiexam.question.admin.application.QuestionImportUploadService;
import com.aiexam.question.admin.dto.QuestionImportBatchDetailDto;
import com.aiexam.question.admin.dto.QuestionImportBatchSummaryDto;
import com.aiexam.question.admin.dto.QuestionImportRequest;
import com.aiexam.question.admin.dto.QuestionImportPublishResultDto;
import com.aiexam.question.admin.dto.QuestionImportResultDto;
import com.aiexam.question.admin.dto.QuestionImportTokenConfirmRequest;
import com.aiexam.question.admin.dto.QuestionImportUploadResultDto;
import com.aiexam.question.admin.dto.QuestionDocumentPreviewDto;
import com.aiexam.question.admin.application.QuestionDocumentPreviewService;
import com.aiexam.question.admin.dto.QuestionWorkbookValidationDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/v1/admin/question-imports")
@PreAuthorize("hasAuthority('question:import')")
public class QuestionImportAdminController {
  private final QuestionImportApplicationService service;
  private final QuestionImportUploadService uploadService;
  private final QuestionDocumentPreviewService documentPreviewService;

  public QuestionImportAdminController(
      QuestionImportApplicationService service,
      QuestionImportUploadService uploadService,
      QuestionDocumentPreviewService documentPreviewService
  ) {
    this.service = service;
    this.uploadService = uploadService;
    this.documentPreviewService = documentPreviewService;
  }

  @GetMapping
  public ApiResponse<PageResponse<QuestionImportBatchSummaryDto>> list(
      @RequestParam(name = "pageNo", defaultValue = "1") long pageNo,
      @RequestParam(name = "pageSize", defaultValue = "20") long pageSize,
      @RequestParam(name = "status", required = false) String status) {
    return ApiResponse.ok(service.listBatches(pageNo, pageSize, status));
  }

  @GetMapping("/{batchId}")
  public ApiResponse<QuestionImportBatchDetailDto> detail(
      @PathVariable("batchId") @Positive long batchId) {
    return ApiResponse.ok(service.batchDetail(batchId));
  }

  @GetMapping(value = "/{batchId}/errors", produces = "text/csv")
  public ResponseEntity<byte[]> downloadErrors(
      @PathVariable("batchId") @Positive long batchId) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=question-import-errors-" + batchId + ".csv")
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .body(service.errorCsv(batchId));
  }

  @PostMapping(value = "/preflight", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<QuestionImportResultDto> preflight(
      @Valid @RequestBody QuestionImportRequest request) {
    return ApiResponse.ok(service.preflight(request));
  }

  @PostMapping(value = "/preflight", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<QuestionImportUploadResultDto> preflightUpload(
      @RequestParam("schemaVersion") String schemaVersion,
      @RequestParam("clientMutationId") UUID clientMutationId,
      @RequestParam("file") MultipartFile file
  ) {
    return ApiResponse.ok(uploadService.preflight(schemaVersion, clientMutationId, file));
  }

  @PostMapping("/validate-xlsx")
  public ApiResponse<QuestionWorkbookValidationDto> validateWorkbook(
      @Valid @RequestBody QuestionImportRequest request) {
    return ApiResponse.ok(service.validateWorkbook(request));
  }

  @PostMapping("/confirm")
  public ApiResponse<QuestionImportResultDto> confirm(
      @Valid @RequestBody QuestionImportRequest request) {
    return ApiResponse.ok(service.confirm(request));
  }

  @PostMapping("/{token}/confirm")
  public ApiResponse<QuestionImportUploadResultDto> confirmUpload(
      @PathVariable("token") String token,
      @Valid @RequestBody QuestionImportTokenConfirmRequest request
  ) {
    return ApiResponse.ok(uploadService.confirm(token, request));
  }

  @PostMapping("/{batchId}/rollback")
  public ApiResponse<QuestionImportResultDto> rollback(
      @PathVariable("batchId") @Positive long batchId,
      @Valid @RequestBody QuestionImportRequest.Rollback request) {
    return ApiResponse.ok(service.rollback(batchId, request));
  }

  @GetMapping("/by-paper/{paperId}")
  public ApiResponse<QuestionImportBatchDetailDto> detailByPaper(
      @PathVariable("paperId") @Positive long paperId) {
    return ApiResponse.ok(service.batchDetailByPaper(paperId));
  }

  @PostMapping(value = "/document-preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<QuestionDocumentPreviewDto> previewDocument(@RequestPart("file") MultipartFile file) {
    return ApiResponse.ok(documentPreviewService.preview(file));
  }

  @PostMapping("/{batchId}/publish")
  @PreAuthorize("hasAuthority('question:publish')")
  public ApiResponse<QuestionImportPublishResultDto> publish(
      @PathVariable("batchId") @Positive long batchId,
      @Valid @RequestBody QuestionImportRequest.Publish request) {
    return ApiResponse.ok(service.publish(batchId, request));
  }

  @PostMapping("/{batchId}/reject")
  @PreAuthorize("hasAuthority('question:publish')")
  public ApiResponse<QuestionImportBatchDetailDto> reject(
      @PathVariable("batchId") @Positive long batchId,
      @Valid @RequestBody QuestionImportRequest.Reject request) {
    service.reject(batchId, request);
    return ApiResponse.ok(service.batchDetail(batchId));
  }
}
