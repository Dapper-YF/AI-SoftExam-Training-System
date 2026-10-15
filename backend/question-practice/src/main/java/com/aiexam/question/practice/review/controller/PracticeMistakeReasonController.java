package com.aiexam.question.practice.review.controller;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.question.practice.review.application.PracticeMistakeReasonApplicationService;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/practice/mistake-reasons")
public class PracticeMistakeReasonController {
  private final PracticeMistakeReasonApplicationService service;
  public PracticeMistakeReasonController(PracticeMistakeReasonApplicationService service) { this.service = service; }
  @GetMapping("/statistics") public ApiResponse<List<PracticeMistakeReasonApplicationService.ReasonStat>> statistics() { return ApiResponse.ok(service.statistics()); }
  @GetMapping("/{questionId}") public ApiResponse<List<PracticeMistakeReasonApplicationService.ReasonItem>> reasons(@PathVariable("questionId") @Positive Long questionId) { return ApiResponse.ok(service.reasons(questionId)); }
  @PutMapping("/{questionId}") public ApiResponse<List<PracticeMistakeReasonApplicationService.ReasonItem>> replace(@PathVariable("questionId") @Positive Long questionId, @RequestBody ReasonRequest request) { return ApiResponse.ok(service.replace(questionId, request.reasonCodes())); }
  public record ReasonRequest(List<String> reasonCodes) { }
}
