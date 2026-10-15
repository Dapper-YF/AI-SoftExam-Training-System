package com.aiexam.question.controller;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.common.api.PageResponse;
import com.aiexam.question.application.QuestionApplicationService;
import com.aiexam.question.dto.QuestionAiExplanationTaskDto;
import com.aiexam.question.dto.QuestionDetailDto;
import com.aiexam.question.dto.QuestionExplanationDto;
import com.aiexam.question.dto.QuestionSummaryDto;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/questions")
public class QuestionController {
  private final QuestionApplicationService questionApplicationService;

  public QuestionController(QuestionApplicationService questionApplicationService) {
    this.questionApplicationService = questionApplicationService;
  }

  @GetMapping
  public ApiResponse<PageResponse<QuestionSummaryDto>> list(
      @RequestParam(name = "pageNo", defaultValue = "1") @Positive long pageNo,
      @RequestParam(name = "pageSize", defaultValue = "20") @Positive long pageSize
  ) {
    return ApiResponse.ok(questionApplicationService.listPublishedQuestions(pageNo, pageSize));
  }

  @GetMapping("/{id}")
  public ApiResponse<QuestionDetailDto> detail(@PathVariable("id") @Positive Long id) {
    return ApiResponse.ok(questionApplicationService.getQuestionDetail(id));
  }

  @GetMapping("/{id}/explanation")
  public ApiResponse<QuestionExplanationDto> explanation(@PathVariable("id") @Positive Long id) {
    return ApiResponse.ok(questionApplicationService.getExplanation(id));
  }

  @PostMapping("/{id}/ai-explanation")
  public ApiResponse<QuestionAiExplanationTaskDto> triggerAiExplanation(@PathVariable("id") @Positive Long id) {
    return ApiResponse.ok(questionApplicationService.triggerAiExplanation(id));
  }
}
