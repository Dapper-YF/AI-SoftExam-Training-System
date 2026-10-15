package com.aiexam.app.aiassistant;

import static com.aiexam.app.aiassistant.AiSmartPaperDtos.*;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.common.security.CurrentUserProvider;
import com.aiexam.common.security.PlatformAuthenticationException;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai-smart-papers")
public class AiSmartPaperController {
  private final AiSmartPaperService service;
  private final CurrentUserProvider currentUser;
  public AiSmartPaperController(AiSmartPaperService service, CurrentUserProvider currentUser) { this.service = service; this.currentUser = currentUser; }
  @PostMapping public ApiResponse<SmartPaperDto> create(@RequestBody CreateRequest request) { return ApiResponse.ok(service.create(userId(), request)); }
  @GetMapping public ApiResponse<List<SmartPaperDto>> list(@RequestParam(defaultValue = "10") int limit) { return ApiResponse.ok(service.list(userId(), limit)); }
  private long userId() { return currentUser.currentPrincipal().orElseThrow(PlatformAuthenticationException::new).userId(); }
}
