package com.aiexam.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

class GlobalExceptionHandlerTest {
  private GlobalExceptionHandler handler;

  @BeforeEach
  void setUp() {
    handler = new GlobalExceptionHandler();
  }

  @Test
  void mapsMethodSecurityDenialToForbiddenEnvelope() {
    var response = handler.handleAccessDenied(new AccessDeniedException("denied"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
    assertThat(response.getBody().success()).isFalse();
    assertThat(response.getBody().data()).isNull();
  }

  @Test
  void businessExceptionResponseUsesSafeChineseMessage() {
    var response = handler.handleBusiness(new BusinessException(
        ErrorCode.AUTH_INVALID_CREDENTIALS,
        "java.sql.SQLIntegrityConstraintViolationException: password table leaked",
        HttpStatus.UNAUTHORIZED));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().errorCode()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
    assertThat(response.getBody().message()).isEqualTo("手机号或邮箱、密码错误");
    assertThat(response.getBody().message()).doesNotContain("SQL", "Exception", "password table");
  }

  @Test
  void smsUnavailableResponseUsesSafeChineseMessage() {
    var response = handler.handleBusiness(new BusinessException(
        ErrorCode.AUTH_SMS_UNAVAILABLE,
        "com.tencentcloudapi.common.exception.TencentCloudSDKException: leaked secret",
        HttpStatus.SERVICE_UNAVAILABLE));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().errorCode()).isEqualTo(ErrorCode.AUTH_SMS_UNAVAILABLE);
    assertThat(response.getBody().message()).isEqualTo("短信服务暂不可用，请稍后重试。");
    assertThat(response.getBody().message()).doesNotContain("TencentCloudSDKException", "secret");
  }

  @Test
  void validationResponseUsesSingleSafeChineseMessage() {
    var response = handler.handleBusiness(new BusinessException(
        ErrorCode.VALIDATION_ERROR,
        "Failed to convert property value of type java.lang.String",
        HttpStatus.BAD_REQUEST));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message()).isEqualTo("提交的信息有误，请检查后重试");
    assertThat(response.getBody().message()).doesNotContain("java.lang");
  }

  @Test
  void duplicateIdentityResponsesUseSafeChineseMessages() {
    var username = handler.handleBusiness(new BusinessException(
        ErrorCode.AUTH_USERNAME_CONFLICT,
        "Duplicate entry 'student_01' for key 'uk_sys_user_username'",
        HttpStatus.CONFLICT));
    var mobile = handler.handleBusiness(new BusinessException(
        ErrorCode.AUTH_MOBILE_CONFLICT,
        "SQLIntegrityConstraintViolationException: uk_sys_user_mobile",
        HttpStatus.CONFLICT));

    assertThat(username.getBody()).isNotNull();
    assertThat(username.getBody().message()).isEqualTo("昵称已存在");
    assertThat(username.getBody().message()).doesNotContain("Duplicate", "uk_");
    assertThat(mobile.getBody()).isNotNull();
    assertThat(mobile.getBody().message()).isEqualTo("手机号或邮箱已被使用");
    assertThat(mobile.getBody().message()).doesNotContain("SQL", "uk_");
  }

  @Test
  void passwordPolicyResponsesDoNotExposeMaxLengthInRegularPromptButSeparateTooLong() {
    var regular = handler.handleBusiness(new BusinessException(
        ErrorCode.AUTH_PASSWORD_POLICY_VIOLATION,
        "密码至少8位，且包含大写字母、小写字母和数字",
        HttpStatus.UNPROCESSABLE_ENTITY));
    var tooLong = handler.handleBusiness(new BusinessException(
        ErrorCode.AUTH_PASSWORD_POLICY_VIOLATION,
        "密码过长，请缩短后重试",
        HttpStatus.UNPROCESSABLE_ENTITY));

    assertThat(regular.getBody()).isNotNull();
    assertThat(regular.getBody().message()).isEqualTo("密码至少8位，且包含大写字母、小写字母和数字");
    assertThat(regular.getBody().message()).doesNotContain("64");
    assertThat(tooLong.getBody()).isNotNull();
    assertThat(tooLong.getBody().message()).isEqualTo("密码过长，请缩短后重试");
    assertThat(tooLong.getBody().message()).doesNotContain("64");
  }

  @Test
  void unknownExceptionResponseDoesNotExposeDebugDetails() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/probe");

    var response = handler.handleException(new IllegalStateException("jdbc:mysql://secret-host/internal"), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
    assertThat(response.getBody().message()).isEqualTo("系统暂时不可用，请稍后重试");
    assertThat(response.getBody().message()).doesNotContain("jdbc", "secret-host", "IllegalStateException");
  }
}
