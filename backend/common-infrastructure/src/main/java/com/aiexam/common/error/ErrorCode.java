package com.aiexam.common.error;

public final class ErrorCode {
  public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
  public static final String UNAUTHORIZED = "UNAUTHORIZED";
  public static final String FORBIDDEN = "FORBIDDEN";
  public static final String NOT_FOUND = "NOT_FOUND";
  public static final String RATE_LIMITED = "RATE_LIMITED";
  public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
  public static final String AUTH_REQUEST_INVALID = "AUTH_REQUEST_INVALID";
  public static final String AUTH_CHALLENGE_INVALID = "AUTH_CHALLENGE_INVALID";
  public static final String AUTH_ROLE_FIELD_FORBIDDEN = "AUTH_ROLE_FIELD_FORBIDDEN";
  public static final String AUTH_INVALID_CREDENTIALS = "AUTH_INVALID_CREDENTIALS";
  public static final String AUTH_ACCOUNT_DISABLED = "AUTH_ACCOUNT_DISABLED";
  public static final String AUTH_ACCOUNT_LOCKED = "AUTH_ACCOUNT_LOCKED";
  public static final String AUTH_SESSION_EXPIRED = "AUTH_SESSION_EXPIRED";
  public static final String CSRF_INVALID = "CSRF_INVALID";
  public static final String AUTH_USERNAME_CONFLICT = "AUTH_USERNAME_CONFLICT";
  public static final String AUTH_MOBILE_CONFLICT = "AUTH_MOBILE_CONFLICT";
  public static final String AUTH_IDENTIFIER_CONFLICT = "AUTH_IDENTIFIER_CONFLICT";
  public static final String AUTH_IDEMPOTENCY_CONFLICT = "AUTH_IDEMPOTENCY_CONFLICT";
  public static final String AUTH_REQUEST_PROCESSING = "AUTH_REQUEST_PROCESSING";
  public static final String AUTH_PASSWORD_POLICY_VIOLATION = "AUTH_PASSWORD_POLICY_VIOLATION";
  public static final String AUTH_RATE_LIMITED = "AUTH_RATE_LIMITED";
  public static final String AUTH_CAPACITY_BUSY = "AUTH_CAPACITY_BUSY";
  public static final String AUTH_SMS_UNAVAILABLE = "AUTH_SMS_UNAVAILABLE";
  public static final String AUTH_PROVIDER_UNAVAILABLE = "AUTH_PROVIDER_UNAVAILABLE";
  public static final String AUTH_WRITE_BUSY = "AUTH_WRITE_BUSY";

  private ErrorCode() {
  }
}
