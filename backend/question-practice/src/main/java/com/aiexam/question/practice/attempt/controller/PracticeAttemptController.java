package com.aiexam.question.practice.attempt.controller;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.common.api.PageResponse;
import com.aiexam.question.practice.attempt.application.PracticeAttemptApplicationService;
import com.aiexam.question.practice.attempt.dto.CreatePracticeAttemptRequest;
import com.aiexam.question.practice.attempt.dto.PracticeAnswerSaveResultDto;
import com.aiexam.question.practice.attempt.dto.PracticeAttemptDetailDto;
import com.aiexam.question.practice.attempt.dto.PracticeAttemptExplanationDto;
import com.aiexam.question.practice.attempt.dto.PracticeAttemptHistoryDto;
import com.aiexam.question.practice.attempt.dto.PracticeAttemptSubmitResultDto;
import com.aiexam.question.practice.attempt.dto.SavePracticeAnswerRequest;
import com.aiexam.question.practice.attempt.dto.SettlePracticeQuestionRequest;
import com.aiexam.question.practice.attempt.dto.SubmitPracticeAttemptRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/practice/attempts")
public class PracticeAttemptController {
  private final PracticeAttemptApplicationService service;

  public PracticeAttemptController(PracticeAttemptApplicationService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<PracticeAttemptDetailDto>> create(
      @Valid @RequestBody CreatePracticeAttemptRequest request
  ) {
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(service.create(request)));
  }

  @GetMapping("/{attemptId}")
  public ApiResponse<PracticeAttemptDetailDto> detail(
      @PathVariable("attemptId") @Positive Long attemptId
  ) {
    return ApiResponse.ok(service.get(attemptId));
  }

  @PutMapping("/{attemptId}/answers/{subQuestionId}")
  public ApiResponse<PracticeAnswerSaveResultDto> saveAnswer(
      @PathVariable("attemptId") @Positive Long attemptId,
      @PathVariable("subQuestionId") @Positive Long subQuestionId,
      @Valid @RequestBody SavePracticeAnswerRequest request
  ) {
    return ApiResponse.ok(service.saveAnswer(attemptId, subQuestionId, request));
  }

  @PostMapping("/{attemptId}/submit")
  public ApiResponse<PracticeAttemptSubmitResultDto> submit(
      @PathVariable("attemptId") @Positive Long attemptId,
      @Valid @RequestBody SubmitPracticeAttemptRequest request
  ) {
    return ApiResponse.ok(service.submit(attemptId, request));
  }

  @PostMapping("/{attemptId}/questions/{questionId}/settle")
  public ApiResponse<PracticeAttemptSubmitResultDto> settleQuestion(
      @PathVariable("attemptId") @Positive Long attemptId,
      @PathVariable("questionId") @Positive Long questionId,
      @Valid @RequestBody SettlePracticeQuestionRequest request
  ) {
    return ApiResponse.ok(service.settleQuestion(attemptId, questionId, request));
  }

  @GetMapping("/{attemptId}/questions/{questionId}/explanation")
  public ApiResponse<PracticeAttemptExplanationDto> explanation(
      @PathVariable("attemptId") @Positive Long attemptId,
      @PathVariable("questionId") @Positive Long questionId
  ) {
    return ApiResponse.ok(service.explanation(attemptId, questionId));
  }

  @GetMapping
  public ApiResponse<PageResponse<PracticeAttemptHistoryDto>> history(
      @RequestParam(name = "qualificationId", required = false) @Positive Long qualificationId,
      @RequestParam(name = "subjectId", required = false) @Positive Long subjectId,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "pageNo", defaultValue = "1") @Positive long pageNo,
      @RequestParam(name = "pageSize", defaultValue = "20") @Positive long pageSize
  ) {
    return ApiResponse.ok(service.history(qualificationId, subjectId, status, pageNo, pageSize));
  }
}
