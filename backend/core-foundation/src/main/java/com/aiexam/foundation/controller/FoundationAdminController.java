package com.aiexam.foundation.controller;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.foundation.application.FoundationApplicationService;
import com.aiexam.foundation.dto.ChapterNodeDto;
import com.aiexam.foundation.dto.ExamRuleDto;
import com.aiexam.foundation.dto.KnowledgeRelationDto;
import com.aiexam.foundation.dto.KnowledgeTagDto;
import com.aiexam.foundation.dto.QualificationDto;
import com.aiexam.foundation.dto.SubjectDto;
import com.aiexam.foundation.dto.SyllabusNodeDto;
import com.aiexam.foundation.dto.request.ChapterNodeCreateRequest;
import com.aiexam.foundation.dto.request.ExamRuleCreateRequest;
import com.aiexam.foundation.dto.request.KnowledgeRelationCreateRequest;
import com.aiexam.foundation.dto.request.KnowledgeTagCreateRequest;
import com.aiexam.foundation.dto.request.QualificationCreateRequest;
import com.aiexam.foundation.dto.request.QualificationUpdateRequest;
import com.aiexam.foundation.dto.request.SubjectCreateRequest;
import com.aiexam.foundation.dto.request.SyllabusNodeCreateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class FoundationAdminController {
  private final FoundationApplicationService foundationApplicationService;

  public FoundationAdminController(FoundationApplicationService foundationApplicationService) {
    this.foundationApplicationService = foundationApplicationService;
  }

  @PostMapping("/qualifications")
  public ResponseEntity<ApiResponse<QualificationDto>> createQualification(@Valid @RequestBody QualificationCreateRequest request) {
    return created(foundationApplicationService.createQualification(request));
  }

  @PutMapping("/qualifications/{id}")
  public ApiResponse<QualificationDto> updateQualification(
      @PathVariable("id") @Positive Long id,
      @Valid @RequestBody QualificationUpdateRequest request
  ) {
    return ApiResponse.ok(foundationApplicationService.updateQualification(id, request));
  }

  @PostMapping("/subjects")
  public ResponseEntity<ApiResponse<SubjectDto>> createSubject(@Valid @RequestBody SubjectCreateRequest request) {
    return created(foundationApplicationService.createSubject(request));
  }

  @PostMapping("/syllabus-nodes")
  public ResponseEntity<ApiResponse<SyllabusNodeDto>> createSyllabusNode(@Valid @RequestBody SyllabusNodeCreateRequest request) {
    return created(foundationApplicationService.createSyllabusNode(request));
  }

  @PutMapping("/syllabus-nodes/{id}")
  public ApiResponse<SyllabusNodeDto> updateSyllabusNode(
      @PathVariable("id") @Positive Long id,
      @Valid @RequestBody SyllabusNodeCreateRequest request
  ) {
    return ApiResponse.ok(foundationApplicationService.updateSyllabusNode(id, request));
  }

  @PostMapping("/chapter-nodes")
  public ResponseEntity<ApiResponse<ChapterNodeDto>> createChapterNode(@Valid @RequestBody ChapterNodeCreateRequest request) {
    return created(foundationApplicationService.createChapterNode(request));
  }

  @PutMapping("/chapter-nodes/{id}")
  public ApiResponse<ChapterNodeDto> updateChapterNode(
      @PathVariable("id") @Positive Long id,
      @Valid @RequestBody ChapterNodeCreateRequest request
  ) {
    return ApiResponse.ok(foundationApplicationService.updateChapterNode(id, request));
  }

  @PostMapping("/knowledge-tags")
  public ResponseEntity<ApiResponse<KnowledgeTagDto>> createKnowledgeTag(@Valid @RequestBody KnowledgeTagCreateRequest request) {
    return created(foundationApplicationService.createKnowledgeTag(request));
  }

  @PutMapping("/knowledge-tags/{id}")
  public ApiResponse<KnowledgeTagDto> updateKnowledgeTag(
      @PathVariable("id") @Positive Long id,
      @Valid @RequestBody KnowledgeTagCreateRequest request
  ) {
    return ApiResponse.ok(foundationApplicationService.updateKnowledgeTag(id, request));
  }

  @PostMapping("/knowledge-relations")
  public ResponseEntity<ApiResponse<KnowledgeRelationDto>> createKnowledgeRelation(
      @Valid @RequestBody KnowledgeRelationCreateRequest request
  ) {
    return created(foundationApplicationService.createKnowledgeRelation(request));
  }

  @PostMapping("/exam-rules")
  public ResponseEntity<ApiResponse<ExamRuleDto>> createExamRule(@Valid @RequestBody ExamRuleCreateRequest request) {
    return created(foundationApplicationService.createExamRule(request));
  }

  private <T> ResponseEntity<ApiResponse<T>> created(T data) {
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(data));
  }
}
