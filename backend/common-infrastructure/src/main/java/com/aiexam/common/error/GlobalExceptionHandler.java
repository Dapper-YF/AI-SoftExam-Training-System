package com.aiexam.common.error;

import com.aiexam.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiResponse<Object>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
    FieldError fieldError = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
    if (fieldError != null) {
      log.warn("Request validation failed, field={}, reason={}", fieldError.getField(), fieldError.getDefaultMessage());
    }
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ApiResponse.fail(ErrorCode.VALIDATION_ERROR, safeMessage(ErrorCode.VALIDATION_ERROR, null), null));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ApiResponse<Object>> handleConstraintViolation(ConstraintViolationException ex) {
    ex.getConstraintViolations().stream().findFirst()
        .ifPresent(violation -> log.warn(
            "Request constraint violation, path={}, reason={}",
            violation.getPropertyPath(),
            violation.getMessage()));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ApiResponse.fail(ErrorCode.VALIDATION_ERROR, safeMessage(ErrorCode.VALIDATION_ERROR, null), null));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ApiResponse<Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    log.warn("Request argument type mismatch, name={}, requiredType={}",
        ex.getName(), ex.getRequiredType());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ApiResponse.fail(ErrorCode.VALIDATION_ERROR, safeMessage(ErrorCode.VALIDATION_ERROR, null), null));
  }

  @ExceptionHandler(BusinessException.class)
  ResponseEntity<ApiResponse<Object>> handleBusiness(BusinessException ex) {
    log.warn("Business exception handled, code={}, type={}", ex.code(), ex.getClass().getSimpleName());
    return ResponseEntity.status(ex.status())
        .body(ApiResponse.fail(ex.code(), safeMessage(ex.code(), ex.getMessage()), null));
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiResponse<Object>> handleAccessDenied(AccessDeniedException ex) {
    log.warn("Access denied by method security, type={}", ex.getClass().getSimpleName());
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ApiResponse.fail(ErrorCode.FORBIDDEN, safeMessage(ErrorCode.FORBIDDEN, null), null));
  }

  @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
  ResponseEntity<ApiResponse<Object>> handleNoHandler(Exception ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiResponse.fail(ErrorCode.NOT_FOUND, "资源不存在", null));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiResponse<Object>> handleException(Exception ex, HttpServletRequest request) {
    log.error("Unhandled backend exception, path={}, type={}", request.getRequestURI(), ex.getClass().getSimpleName(), ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, safeMessage(ErrorCode.INTERNAL_ERROR, null), null));
  }

  private String safeMessage(String code, String fallback) {
    return switch (code) {
      case ErrorCode.VALIDATION_ERROR -> "提交的信息有误，请检查后重试";
      case ErrorCode.AUTH_REQUEST_INVALID -> "提交的信息有误，请检查后重试";
      case ErrorCode.AUTH_PASSWORD_POLICY_VIOLATION -> passwordPolicyMessage(fallback);
      case ErrorCode.AUTH_INVALID_CREDENTIALS -> "手机号或邮箱、密码错误";
      case ErrorCode.AUTH_USERNAME_CONFLICT -> "昵称已存在";
      case ErrorCode.AUTH_MOBILE_CONFLICT, ErrorCode.AUTH_IDENTIFIER_CONFLICT -> "手机号或邮箱已被使用";
      case ErrorCode.AUTH_CHALLENGE_INVALID -> "验证码无效";
      case ErrorCode.AUTH_RATE_LIMITED, ErrorCode.RATE_LIMITED -> "请求过于频繁，请稍后重试";
      case ErrorCode.AUTH_SMS_UNAVAILABLE -> "短信服务暂不可用，请稍后重试。";
      case ErrorCode.AUTH_PROVIDER_UNAVAILABLE -> "认证服务暂不可用，请稍后重试";
      case ErrorCode.AUTH_ROLE_FIELD_FORBIDDEN -> "提交的信息有误，请检查后重试";
      case ErrorCode.AUTH_SESSION_EXPIRED, ErrorCode.UNAUTHORIZED -> "未登录或登录已过期";
      case ErrorCode.AUTH_ACCOUNT_DISABLED -> "账号已停用";
      case ErrorCode.AUTH_ACCOUNT_LOCKED -> "账号已锁定";
      case ErrorCode.FORBIDDEN -> "无权限访问";
      case ErrorCode.INTERNAL_ERROR -> "系统暂时不可用，请稍后重试";
      default -> fallback == null || fallback.isBlank() ? "请求处理失败，请稍后重试" : fallback;
    };
  }

  private String passwordPolicyMessage(String fallback) {
    if ("密码过长，请缩短后重试".equals(fallback)) {
      return fallback;
    }
    return "密码至少8位，且包含大写字母、小写字母和数字";
  }
}
