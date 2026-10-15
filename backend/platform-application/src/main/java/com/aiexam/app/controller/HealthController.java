package com.aiexam.app.controller;

import com.aiexam.common.api.ApiResponse;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {
  @GetMapping("/health")
  public ApiResponse<Map<String, Object>> health() {
    return ApiResponse.ok(Map.of(
        "status", "UP",
        "application", "ai-exam-platform",
        "time", OffsetDateTime.now().toString()
    ));
  }
}
