package com.aiexam.app.controller;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.common.error.RateLimitedException;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/security")
public class SecurityProbeController {
  @GetMapping("/authenticated-probe")
  public ApiResponse<Map<String, String>> authenticatedProbe() {
    return ApiResponse.ok(Map.of("status", "authenticated"));
  }

  @GetMapping("/admin-probe")
  public ApiResponse<Map<String, String>> adminProbe() {
    return ApiResponse.ok(Map.of("status", "admin"));
  }

  @GetMapping("/rate-limit-probe")
  public ApiResponse<Map<String, String>> rateLimitProbe() {
    throw new RateLimitedException("AI调用过于频繁，请稍后重试");
  }

  @GetMapping("/internal-error-probe")
  public ApiResponse<Map<String, String>> internalErrorProbe() {
    throw new IllegalStateException("probe");
  }
}
