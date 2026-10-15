package com.aiexam.question.practice.attempt.controller;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.question.practice.attempt.application.PracticeAttemptApplicationService;
import com.aiexam.question.practice.attempt.dto.CreateCaseAttemptRequest;
import com.aiexam.question.practice.attempt.dto.PracticeAttemptDetailDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/practice/case-attempts")
public class PracticeCaseAttemptController {
  private final PracticeAttemptApplicationService service;

  public PracticeCaseAttemptController(PracticeAttemptApplicationService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<PracticeAttemptDetailDto>> create(
      @Valid @RequestBody CreateCaseAttemptRequest request
  ) {
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(service.createCaseAttempt(request)));
  }
}
