package com.aiexam.app.exam;

import com.aiexam.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exam-simulator")
public class ExamSimulatorController {
  private final ExamSimulatorApplicationService service;

  public ExamSimulatorController(ExamSimulatorApplicationService service) { this.service = service; }

  @PostMapping("/login")
  public ApiResponse<ExamSimulatorApplicationService.LoginResult> login(
      @Valid @RequestBody LoginRequest request) {
    return ApiResponse.ok(service.login(new ExamSimulatorApplicationService.LoginCommand(
        request.qualificationCode(), request.entryCode(), request.ruleVersion(),
        request.clientLoginId() == null ? UUID.randomUUID().toString() : request.clientLoginId(),
        request.admissionTicket(), request.identityNumber())));
  }

  @GetMapping("/sessions/{sessionId}/candidate")
  public ApiResponse<java.util.Map<String, Object>> candidate(@PathVariable String sessionId, @RequestHeader("Authorization") String authorization) {
    service.authorize(sessionId, authorization);
    return ApiResponse.ok(service.candidate(sessionId));
  }

  @GetMapping("/sessions/{sessionId}/state")
  public ApiResponse<java.util.Map<String, Object>> state(@PathVariable String sessionId, @RequestHeader("Authorization") String authorization) {
    service.authorize(sessionId, authorization);
    return ApiResponse.ok(service.state(sessionId));
  }

  @GetMapping("/sessions/{sessionId}/rules")
  public ApiResponse<java.util.Map<String, Object>> rules(@PathVariable String sessionId, @RequestHeader("Authorization") String authorization) {
    service.authorize(sessionId, authorization);
    return ApiResponse.ok(service.rules(sessionId));
  }

  @GetMapping("/sessions/{sessionId}/paper")
  public ApiResponse<java.util.Map<String, Object>> paper(@PathVariable String sessionId, @RequestHeader("Authorization") String authorization) {
    service.authorize(sessionId, authorization);
    return ApiResponse.ok(service.paper(sessionId));
  }

  @PostMapping("/sessions/{sessionId}/confirm-candidate")
  public ApiResponse<java.util.Map<String, Object>> confirmCandidate(@PathVariable String sessionId, @RequestHeader("Authorization") String authorization) {
    service.authorize(sessionId, authorization);
    return ApiResponse.ok(service.confirmCandidate(sessionId));
  }

  @PostMapping("/sessions/{sessionId}/acknowledge-rules")
  public ApiResponse<java.util.Map<String, Object>> acknowledgeRules(@PathVariable String sessionId, @RequestHeader("Authorization") String authorization) {
    service.authorize(sessionId, authorization);
    return ApiResponse.ok(service.acknowledgeRules(sessionId));
  }

  @PostMapping("/sessions/{sessionId}/enter")
  public ApiResponse<java.util.Map<String, Object>> enter(@PathVariable String sessionId, @RequestHeader("Authorization") String authorization) {
    service.authorize(sessionId, authorization);
    return ApiResponse.ok(service.enter(sessionId));
  }

  @PostMapping("/sessions/{sessionId}/submit")
  public ApiResponse<java.util.Map<String, Object>> submit(@PathVariable String sessionId, @RequestHeader("Authorization") String authorization, @RequestBody(required = false) SubmitRequest request) {
    service.authorize(sessionId, authorization);
    return ApiResponse.ok(service.submit(sessionId, request == null ? "MANUAL" : request.mode()));
  }

  @PostMapping("/sessions/{sessionId}/continue-next-subject")
  public ApiResponse<java.util.Map<String, Object>> continueNextSubject(@PathVariable String sessionId, @RequestHeader("Authorization") String authorization) {
    service.authorize(sessionId, authorization);
    return ApiResponse.ok(service.continueNextSubject(sessionId));
  }

  @PutMapping("/sessions/{sessionId}/answers/{subQuestionId}")
  public ApiResponse<java.util.Map<String, Object>> saveAnswer(@PathVariable String sessionId, @PathVariable long subQuestionId, @RequestHeader("Authorization") String authorization,
      @RequestBody AnswerRequest request) {
    service.authorize(sessionId, authorization);
    return ApiResponse.ok(service.saveAnswer(sessionId, subQuestionId, request.value()));
  }

  @PutMapping("/sessions/{sessionId}/marks/{subQuestionId}")
  public ApiResponse<java.util.Map<String, Object>> changeMark(@PathVariable String sessionId, @PathVariable long subQuestionId, @RequestHeader("Authorization") String authorization,
      @RequestBody MarkRequest request) {
    service.authorize(sessionId, authorization);
    return ApiResponse.ok(service.changeMark(sessionId, subQuestionId, request.marked()));
  }

  public record LoginRequest(
      @NotBlank String qualificationCode,
      @NotBlank String entryCode,
      String ruleVersion,
      String clientLoginId,
      @NotBlank String admissionTicket,
      @NotBlank String identityNumber) {}
  public record SubmitRequest(String mode, String clientMutationId) {}
  public record AnswerRequest(String value, String clientMutationId) {}
  public record MarkRequest(boolean marked, String clientMutationId) {}
}
