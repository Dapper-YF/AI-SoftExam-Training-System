package com.aiexam.question.practice.read.controller;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.common.api.PageResponse;
import com.aiexam.question.practice.read.application.PracticeReadApplicationService;
import com.aiexam.question.practice.read.dto.PracticeMistakeDetailDto;
import com.aiexam.question.practice.read.dto.PracticeMistakeDto;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/practice/mistakes")
public class PracticeMistakeController {
  private final PracticeReadApplicationService service;

  public PracticeMistakeController(PracticeReadApplicationService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<PageResponse<PracticeMistakeDto>> mistakes(
      @RequestParam(name = "qualificationId", required = false) @Positive Long qualificationId,
      @RequestParam(name = "subjectId", required = false) @Positive Long subjectId,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "pageNo", defaultValue = "1") @Positive long pageNo,
      @RequestParam(name = "pageSize", defaultValue = "20") @Positive long pageSize
  ) {
    return ApiResponse.ok(service.mistakes(qualificationId, subjectId, status, pageNo, pageSize));
  }

  @GetMapping("/{questionId}")
  public ApiResponse<PracticeMistakeDetailDto> detail(
      @PathVariable("questionId") @Positive Long questionId
  ) {
    return ApiResponse.ok(service.mistake(questionId));
  }
}
