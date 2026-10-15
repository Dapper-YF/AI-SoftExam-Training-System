package com.aiexam.app.aiassistant;

import static com.aiexam.app.aiassistant.AiQuestionGenerationDtos.*;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.common.security.CurrentUserProvider;
import com.aiexam.common.security.PlatformAuthenticationException;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/ai-question-generation")
@PreAuthorize("hasRole('ADMIN')")
public class AiQuestionGenerationAdminController {
  private final AiQuestionGenerationService service;
  private final CurrentUserProvider currentUser;
  public AiQuestionGenerationAdminController(AiQuestionGenerationService service, CurrentUserProvider currentUser) { this.service = service; this.currentUser = currentUser; }
  @GetMapping public ApiResponse<List<GeneratedQuestionDto>> list(@RequestParam(defaultValue = "50") int limit) { return ApiResponse.ok(service.adminList(limit)); }
  @PutMapping("/{id}/review") public ApiResponse<GeneratedQuestionDto> review(@PathVariable @Positive long id, @RequestBody ReviewRequest request) { return ApiResponse.ok(service.review(userId(), id, request)); }
  private long userId() { return currentUser.currentPrincipal().orElseThrow(PlatformAuthenticationException::new).userId(); }
}
