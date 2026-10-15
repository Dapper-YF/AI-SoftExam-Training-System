package com.aiexam.question.practice.read.controller;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.question.practice.read.application.PracticeReadApplicationService;
import com.aiexam.question.practice.read.dto.ModuleHomeSnapshotDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
public class PlatformModuleHomeController {
  private final PracticeReadApplicationService service;

  public PlatformModuleHomeController(PracticeReadApplicationService service) {
    this.service = service;
  }

  @GetMapping("/module-home")
  public ApiResponse<ModuleHomeSnapshotDto> moduleHome() {
    return ApiResponse.ok(service.moduleHome());
  }
}
