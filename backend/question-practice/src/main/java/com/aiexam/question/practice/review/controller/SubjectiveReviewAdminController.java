package com.aiexam.question.practice.review.controller;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.question.practice.review.application.SubjectiveReviewApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/practice-reviews")
public class SubjectiveReviewAdminController {
  private final SubjectiveReviewApplicationService service;
  public SubjectiveReviewAdminController(SubjectiveReviewApplicationService service) { this.service = service; }
  @GetMapping public ApiResponse<List<SubjectiveReviewApplicationService.ReviewQueueItem>> queue(
      @RequestParam(required = false) String status, @RequestParam(required = false) @Positive Long qualificationId,
      @RequestParam(required = false) @Positive Long subjectId, @RequestParam(required = false) @Positive Long questionId) {
    return ApiResponse.ok(service.queue(status, qualificationId, subjectId, questionId));
  }
  @PostMapping("/{reviewId}/complete") public ApiResponse<SubjectiveReviewApplicationService.ReviewResult> complete(
      @PathVariable @Positive Long reviewId, @Valid @RequestBody CompleteReviewRequest request) {
    return ApiResponse.ok(service.complete(reviewId, new SubjectiveReviewApplicationService.CompleteReviewCommand(
        request.score(), request.feedback(), request.scoringPoints(), request.version())));
  }
  @PostMapping("/{reviewId}/return") public ApiResponse<SubjectiveReviewApplicationService.ReviewResult> returnReview(
      @PathVariable @Positive Long reviewId, @Valid @RequestBody ReturnReviewRequest request) {
    return ApiResponse.ok(service.returnReview(reviewId, new SubjectiveReviewApplicationService.ReturnReviewCommand(
        request.feedback(), request.scoringPoints(), request.version())));
  }
  public record CompleteReviewRequest(BigDecimal score, String feedback, Map<String, Object> scoringPoints, long version) { }
  public record ReturnReviewRequest(String feedback, Map<String, Object> scoringPoints, long version) { }
}
