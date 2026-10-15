package com.aiexam.question.controller;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.common.api.PageResponse;
import com.aiexam.question.admin.application.QuestionAdminApplicationService;
import com.aiexam.question.admin.dto.QuestionActionResult;
import com.aiexam.question.admin.dto.QuestionAdminDashboardDto;
import com.aiexam.question.admin.dto.QuestionAnomalyDto;
import com.aiexam.question.admin.dto.QuestionAuditEventDto;
import com.aiexam.question.admin.dto.QuestionCopyRequest;
import com.aiexam.question.admin.dto.QuestionReviewRequest;
import com.aiexam.question.admin.dto.QuestionReviewRecordDto;
import com.aiexam.question.admin.dto.QuestionVersionDetailDto;
import com.aiexam.question.admin.dto.QuestionVersionHistoryDto;
import com.aiexam.question.admin.dto.QuestionVersionSummaryDto;
import com.aiexam.question.admin.dto.QuestionVersionWriteRequest;
import com.aiexam.question.admin.dto.QuestionWorkflowRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping("/api/v1/admin/questions")
public class QuestionAdminController {
  private final QuestionAdminApplicationService service;

  public QuestionAdminController(QuestionAdminApplicationService service) {
    this.service = service;
  }

  @GetMapping("/dashboard")
  @PreAuthorize("hasAuthority('question:read')")
  public ApiResponse<QuestionAdminDashboardDto> dashboard() {
    return ApiResponse.ok(service.dashboard());
  }

  @GetMapping("/anomalies")
  @PreAuthorize("hasAuthority('question:read')")
  public ApiResponse<List<QuestionAnomalyDto>> anomalies(
      @RequestParam(name = "anomalyType", required = false) String anomalyType,
      @RequestParam(name = "status", defaultValue = "OPEN") String status) {
    return ApiResponse.ok(service.anomalies(anomalyType, status));
  }

  @GetMapping("/anomalies/count")
  @PreAuthorize("hasAuthority('question:read')")
  public ApiResponse<Long> anomalyCount() {
    return ApiResponse.ok(service.openAnomalyCount());
  }

  @PostMapping("/anomalies/{anomalyId}/resolve")
  @PreAuthorize("hasAuthority('question:update')")
  public ApiResponse<Void> resolveAnomaly(@PathVariable long anomalyId,
      @RequestParam String status, @RequestParam(required = false) String note) {
    service.resolveAnomaly(anomalyId, status, note);
    return ApiResponse.ok(null);
  }

  @PostMapping
  @PreAuthorize("hasAuthority('question:create')")
  public ResponseEntity<ApiResponse<QuestionActionResult>> create(
      @Valid @RequestBody QuestionVersionWriteRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(service.create(request)));
  }

  @PutMapping("/{questionId}/versions")
  @PreAuthorize("hasAuthority('question:update')")
  public ResponseEntity<ApiResponse<QuestionActionResult>> createRevision(
      @PathVariable("questionId") @Positive long questionId,
      @Valid @RequestBody QuestionVersionWriteRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.created(service.createRevision(questionId, request)));
  }

  @GetMapping
  @PreAuthorize("hasAuthority('question:read')")
  public ApiResponse<PageResponse<QuestionVersionSummaryDto>> list(
      @RequestParam(name = "pageNo", defaultValue = "1") long pageNo,
      @RequestParam(name = "pageSize", defaultValue = "20") long pageSize,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "questionId", required = false) @Positive Long questionId,
      @RequestParam(name = "qualificationId", required = false) Long qualificationId,
      @RequestParam(name = "subjectId", required = false) Long subjectId,
      @RequestParam(name = "keyword", required = false) String keyword,
      @RequestParam(name = "questionType", required = false) String questionType,
      @RequestParam(name = "sourceType", required = false) String sourceType,
      @RequestParam(name = "copyrightStatus", required = false) String copyrightStatus) {
    return ApiResponse.ok(service.list(pageNo, pageSize, status, questionId, qualificationId, subjectId,
        keyword, questionType, sourceType, copyrightStatus));
  }

  @GetMapping("/{questionId}/versions")
  @PreAuthorize("hasAuthority('question:read')")
  public ApiResponse<List<QuestionVersionHistoryDto>> versions(
      @PathVariable("questionId") @Positive long questionId) {
    return ApiResponse.ok(service.versionHistory(questionId));
  }

  @GetMapping("/{questionId}/versions/{versionId}")
  @PreAuthorize("hasAuthority('question:read')")
  public ApiResponse<QuestionVersionDetailDto> detail(
      @PathVariable("questionId") @Positive long questionId,
      @PathVariable("versionId") @Positive long versionId) {
    return ApiResponse.ok(service.detail(questionId, versionId));
  }

  @GetMapping("/{questionId}/versions/{versionId}/reviews")
  @PreAuthorize("hasAuthority('question:read')")
  public ApiResponse<List<QuestionReviewRecordDto>> reviews(
      @PathVariable("questionId") @Positive long questionId,
      @PathVariable("versionId") @Positive long versionId) {
    return ApiResponse.ok(service.reviewRecords(questionId, versionId));
  }

  @GetMapping("/{questionId}/audit-events")
  @PreAuthorize("hasAuthority('question:read')")
  public ApiResponse<List<QuestionAuditEventDto>> auditEvents(
      @PathVariable("questionId") @Positive long questionId) {
    return ApiResponse.ok(service.auditEvents(questionId));
  }

  @PostMapping("/{questionId}/versions/{versionId}/copy")
  @PreAuthorize("hasAuthority('question:update')")
  public ResponseEntity<ApiResponse<QuestionActionResult>> copyAsDraft(
      @PathVariable("questionId") @Positive long questionId,
      @PathVariable("versionId") @Positive long versionId,
      @Valid @RequestBody QuestionCopyRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.created(service.copyAsDraft(questionId, versionId, request)));
  }

  @PostMapping("/{questionId}/versions/{versionId}/submit-review")
  @PreAuthorize("hasAuthority('question:submit-review')")
  public ApiResponse<QuestionActionResult> submitReview(
      @PathVariable("questionId") @Positive long questionId,
      @PathVariable("versionId") @Positive long versionId,
      @Valid @RequestBody QuestionWorkflowRequest request) {
    return ApiResponse.ok(service.submitReview(questionId, versionId, request));
  }

  @PostMapping("/{questionId}/versions/{versionId}/review")
  @PreAuthorize("!hasRole('ADMIN') and hasAuthority('question:review')")
  public ApiResponse<QuestionActionResult> review(
      @PathVariable("questionId") @Positive long questionId,
      @PathVariable("versionId") @Positive long versionId,
      @Valid @RequestBody QuestionReviewRequest request) {
    return ApiResponse.ok(service.review(questionId, versionId, request));
  }

  @PostMapping("/{questionId}/versions/{versionId}/publish")
  @PreAuthorize("!hasRole('ADMIN') and hasAuthority('question:publish')")
  public ApiResponse<QuestionActionResult> publish(
      @PathVariable("questionId") @Positive long questionId,
      @PathVariable("versionId") @Positive long versionId,
      @Valid @RequestBody QuestionWorkflowRequest request) {
    return ApiResponse.ok(service.publish(questionId, versionId, request));
  }

  @PostMapping("/{questionId}/versions/{versionId}/retire")
  @PreAuthorize("!hasRole('ADMIN') and hasAuthority('question:publish')")
  public ApiResponse<QuestionActionResult> retire(
      @PathVariable("questionId") @Positive long questionId,
      @PathVariable("versionId") @Positive long versionId,
      @Valid @RequestBody QuestionWorkflowRequest request) {
    return ApiResponse.ok(service.retire(questionId, versionId, request));
  }
}
