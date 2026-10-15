package com.aiexam.question.practice.review.controller;
import com.aiexam.common.api.ApiResponse;
import com.aiexam.question.practice.review.application.PracticeRecommendationApplicationService;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
@RestController
@RequestMapping("/api/v1/practice/questions")
public class PracticeRecommendationController {
  private final PracticeRecommendationApplicationService service;
  public PracticeRecommendationController(PracticeRecommendationApplicationService service) { this.service = service; }
  @GetMapping("/{questionId}/recommendations")
  public ApiResponse<List<PracticeRecommendationApplicationService.Recommendation>> recommend(
      @PathVariable("questionId") @Positive Long questionId, @RequestParam(defaultValue = "6") int limit) {
    return ApiResponse.ok(service.recommend(questionId, limit));
  }
}
