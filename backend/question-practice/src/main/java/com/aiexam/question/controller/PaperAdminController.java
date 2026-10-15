package com.aiexam.question.controller;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.common.api.PageResponse;
import com.aiexam.question.application.PaperApplicationService;
import com.aiexam.question.dto.PaperDetailDto;
import com.aiexam.question.dto.PaperSummaryDto;
import com.aiexam.question.dto.request.PaperCreateRequest;
import com.aiexam.question.dto.request.PaperQuestionAddRequest;
import com.aiexam.question.dto.request.PaperReviewDraftRequest;
import com.aiexam.question.dto.request.PaperUpdateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/papers")
@PreAuthorize("hasRole('ADMIN')")
public class PaperAdminController {
  private final PaperApplicationService paperApplicationService;

  public PaperAdminController(PaperApplicationService paperApplicationService) {
    this.paperApplicationService = paperApplicationService;
  }

  @GetMapping
  public ApiResponse<PageResponse<PaperSummaryDto>> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String paperType,
      @RequestParam(required = false) Long qualificationId,
      @RequestParam(required = false) Long subjectId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer yearNo,
      @RequestParam(defaultValue = "1") long pageNo,
      @RequestParam(defaultValue = "20") long pageSize) {
    return ApiResponse.ok(paperApplicationService.listAdminPapers(
        status, paperType, qualificationId, subjectId, keyword, yearNo, pageNo, pageSize));
  }

  @GetMapping("/{id}")
  public ApiResponse<PaperDetailDto> detail(@PathVariable("id") @Positive Long id) {
    return ApiResponse.ok(paperApplicationService.getAdminPaper(id));
  }

  @PostMapping("/{id}/copy")
  public ResponseEntity<ApiResponse<PaperDetailDto>> copy(
      @PathVariable("id") @Positive Long id,
      @RequestParam(required = false) String paperName) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.created(paperApplicationService.copyPaper(id, paperName)));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<PaperDetailDto>> create(@Valid @RequestBody PaperCreateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.created(paperApplicationService.createPaper(request)));
  }

  @PutMapping("/{id}")
  public ApiResponse<PaperDetailDto> update(
      @PathVariable("id") @Positive Long id,
      @Valid @RequestBody PaperUpdateRequest request
  ) {
    return ApiResponse.ok(paperApplicationService.updatePaper(id, request));
  }

  @PostMapping("/{id}/questions")
  public ResponseEntity<ApiResponse<PaperDetailDto>> addQuestion(
      @PathVariable("id") @Positive Long id,
      @Valid @RequestBody PaperQuestionAddRequest request
  ) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.created(paperApplicationService.addQuestion(id, request)));
  }

  @PutMapping("/{id}/review-draft")
  public ApiResponse<PaperDetailDto> saveReviewDraft(
      @PathVariable("id") @Positive Long id,
      @Valid @RequestBody PaperReviewDraftRequest request
  ) {
    return ApiResponse.ok(paperApplicationService.saveReviewDraft(id, request));
  }

  @PostMapping("/{id}/publish")
  public ApiResponse<PaperDetailDto> publish(@PathVariable("id") @Positive Long id) {
    return ApiResponse.ok(paperApplicationService.publishPaper(id));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> discard(@PathVariable("id") @Positive Long id) {
    paperApplicationService.discardDraftPaper(id);
    return ApiResponse.ok(null);
  }
}
