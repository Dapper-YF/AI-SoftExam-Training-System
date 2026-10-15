package com.aiexam.app.exam;

import com.aiexam.common.api.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ExamSimulatorController.class)
public class ExamSimulatorExceptionHandler {
  @ExceptionHandler(ExamSimulatorApplicationService.ExamSimulatorException.class)
  ResponseEntity<ApiResponse<Object>> exam(ExamSimulatorApplicationService.ExamSimulatorException exception) {
    HttpStatus status;
    switch (exception.code()) {
      case "EXAM_INVALID_CREDENTIALS", "EXAM_TOKEN_REQUIRED", "EXAM_TOKEN_INVALID" -> status = HttpStatus.UNAUTHORIZED;
      case "EXAM_ENTRY_NOT_FOUND", "SUB_QUESTION_NOT_FOUND" -> status = HttpStatus.NOT_FOUND;
      case "EXAM_ENTRY_NOT_PUBLISHED", "EXAM_ENTRY_SUBJECT_NOT_CONFIGURED",
           "EXAM_REAL_PAPER_REQUIRED", "EXAM_LOGIN_WINDOW_CLOSED",
           "EXAM_QUALIFICATION_MISMATCH", "EXAM_STATE_CONFLICT",
           "ANSWERS_LOCKED", "QUALIFICATION_NOT_BOUND", "PREPARATION_PROFILE_REQUIRED" -> status = HttpStatus.CONFLICT;
      case "EXAM_LOGIN_RATE_LIMITED" -> status = HttpStatus.TOO_MANY_REQUESTS;
      case "EXAM_CREDENTIAL_KEY_UNAVAILABLE" -> status = HttpStatus.SERVICE_UNAVAILABLE;
      default -> status = exception.code().contains("UNAVAILABLE") ? HttpStatus.SERVICE_UNAVAILABLE
          : exception.code().contains("MISMATCH") ? HttpStatus.CONFLICT : HttpStatus.UNAUTHORIZED;
    }
    return ResponseEntity.status(status).body(ApiResponse.fail(exception.code(), exception.getMessage(), null));
  }
}
