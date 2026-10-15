package com.aiexam.ai.controller;

import com.aiexam.ai.application.AiTaskApplicationService;
import com.aiexam.ai.dto.AiTaskStatusDto;
import com.aiexam.common.api.ApiResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/ai/tasks")
public class AiTaskController {
  private final AiTaskApplicationService aiTaskApplicationService;

  public AiTaskController(AiTaskApplicationService aiTaskApplicationService) {
    this.aiTaskApplicationService = aiTaskApplicationService;
  }

  @GetMapping("/{id}")
  public ApiResponse<AiTaskStatusDto> detail(@PathVariable("id") @Positive Long id) {
    return ApiResponse.ok(aiTaskApplicationService.getTaskStatus(id));
  }
}
