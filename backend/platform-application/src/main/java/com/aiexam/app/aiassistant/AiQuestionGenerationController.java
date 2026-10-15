package com.aiexam.app.aiassistant;

import static com.aiexam.app.aiassistant.AiQuestionGenerationDtos.*;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.common.security.CurrentUserProvider;
import com.aiexam.common.security.PlatformAuthenticationException;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai-question-generation")
public class AiQuestionGenerationController {
  private final AiQuestionGenerationService service;
  private final CurrentUserProvider currentUser;
  public AiQuestionGenerationController(AiQuestionGenerationService service, CurrentUserProvider currentUser) { this.service = service; this.currentUser = currentUser; }
  @PostMapping public ApiResponse<GeneratedQuestionDto> generate(@RequestBody GenerateRequest request) { return ApiResponse.ok(service.generate(userId(), request)); }
  @GetMapping("/weak-points") public ApiResponse<List<WeakPointDto>> weakPoints(@RequestParam @Positive Long qualificationId, @RequestParam @Positive Long subjectId, @RequestParam(defaultValue = "10") int limit) { return ApiResponse.ok(service.weakPoints(userId(), qualificationId, subjectId, limit)); }
  @GetMapping("/adaptive-difficulty") public ApiResponse<AdaptiveDifficultyDto> adaptiveDifficulty(@RequestParam @Positive Long qualificationId, @RequestParam @Positive Long subjectId, @RequestParam String knowledgeType, @RequestParam @Positive Long knowledgeId) { return ApiResponse.ok(service.adaptiveDifficulty(userId(), qualificationId, subjectId, knowledgeType, knowledgeId)); }
  @PostMapping("/{id}/feedback") public ApiResponse<Void> feedback(@PathVariable @Positive long id, @RequestBody FeedbackRequest request) { service.feedback(userId(), id, request); return ApiResponse.ok(null); }
  @GetMapping("/reusable") public ApiResponse<List<GeneratedQuestionDto>> reusable(@RequestParam @Positive Long qualificationId, @RequestParam @Positive Long subjectId, @RequestParam(defaultValue = "20") int limit) { return ApiResponse.ok(service.reusable(qualificationId, subjectId, limit)); }
  private long userId() { return currentUser.currentPrincipal().orElseThrow(PlatformAuthenticationException::new).userId(); }
}
