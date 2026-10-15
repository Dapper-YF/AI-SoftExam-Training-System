package com.aiexam.foundation.controller;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.common.api.PageResponse;
import com.aiexam.foundation.application.FoundationApplicationService;
import com.aiexam.foundation.dto.ChapterNodeDto;
import com.aiexam.foundation.dto.ExamRuleDto;
import com.aiexam.foundation.dto.KnowledgeTagDto;
import com.aiexam.foundation.dto.QualificationDto;
import com.aiexam.foundation.dto.SubjectDto;
import com.aiexam.foundation.dto.SyllabusNodeDto;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/foundation")
public class FoundationController {
  private final FoundationApplicationService foundationApplicationService;

  public FoundationController(FoundationApplicationService foundationApplicationService) {
    this.foundationApplicationService = foundationApplicationService;
  }

  @GetMapping("/qualifications")
  public ApiResponse<PageResponse<QualificationDto>> qualifications(
      @RequestParam(name = "pageNo", defaultValue = "1") @Positive long pageNo,
      @RequestParam(name = "pageSize", defaultValue = "20") @Positive long pageSize
  ) {
    return ApiResponse.ok(foundationApplicationService.listQualifications(pageNo, pageSize));
  }

  @GetMapping("/qualifications/{id}/subjects")
  public ApiResponse<PageResponse<SubjectDto>> subjects(
      @PathVariable("id") @Positive Long id,
      @RequestParam(name = "pageNo", defaultValue = "1") @Positive long pageNo,
      @RequestParam(name = "pageSize", defaultValue = "20") @Positive long pageSize
  ) {
    return ApiResponse.ok(foundationApplicationService.listSubjects(id, pageNo, pageSize));
  }

  @GetMapping("/subjects/{id}/syllabus-nodes")
  public ApiResponse<PageResponse<SyllabusNodeDto>> syllabusNodes(
      @PathVariable("id") @Positive Long id,
      @RequestParam(name = "pageNo", defaultValue = "1") @Positive long pageNo,
      @RequestParam(name = "pageSize", defaultValue = "20") @Positive long pageSize
  ) {
    return ApiResponse.ok(foundationApplicationService.listSyllabusNodes(id, pageNo, pageSize));
  }

  @GetMapping("/subjects/{id}/chapter-nodes")
  public ApiResponse<PageResponse<ChapterNodeDto>> chapterNodes(
      @PathVariable("id") @Positive Long id,
      @RequestParam(name = "pageNo", defaultValue = "1") @Positive long pageNo,
      @RequestParam(name = "pageSize", defaultValue = "20") @Positive long pageSize
  ) {
    return ApiResponse.ok(foundationApplicationService.listChapterNodes(id, pageNo, pageSize));
  }

  @GetMapping("/knowledge-tags")
  public ApiResponse<PageResponse<KnowledgeTagDto>> knowledgeTags(
      @RequestParam(name = "qualificationId", required = false) @Positive Long qualificationId,
      @RequestParam(name = "subjectId", required = false) @Positive Long subjectId,
      @RequestParam(name = "pageNo", defaultValue = "1") @Positive long pageNo,
      @RequestParam(name = "pageSize", defaultValue = "20") @Positive long pageSize
  ) {
    return ApiResponse.ok(foundationApplicationService.listKnowledgeTags(qualificationId, subjectId, pageNo, pageSize));
  }

  @GetMapping("/exam-rules")
  public ApiResponse<PageResponse<ExamRuleDto>> examRules(
      @RequestParam(name = "qualificationId") @Positive Long qualificationId,
      @RequestParam(name = "subjectId", required = false) @Positive Long subjectId,
      @RequestParam(name = "pageNo", defaultValue = "1") @Positive long pageNo,
      @RequestParam(name = "pageSize", defaultValue = "20") @Positive long pageSize
  ) {
    return ApiResponse.ok(foundationApplicationService.listExamRules(qualificationId, subjectId, pageNo, pageSize));
  }
}
