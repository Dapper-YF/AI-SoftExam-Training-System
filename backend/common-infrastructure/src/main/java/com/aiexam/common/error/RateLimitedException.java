package com.aiexam.common.error;

import org.springframework.http.HttpStatus;

public class RateLimitedException extends BusinessException {
  public RateLimitedException(String message) {
    super(ErrorCode.RATE_LIMITED, message, HttpStatus.TOO_MANY_REQUESTS);
  }
}
