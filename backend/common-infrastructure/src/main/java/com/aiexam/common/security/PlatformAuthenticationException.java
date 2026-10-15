package com.aiexam.common.security;

import com.aiexam.common.error.ErrorCode;
import org.springframework.security.core.AuthenticationException;

public class PlatformAuthenticationException extends AuthenticationException {
  public PlatformAuthenticationException() {
    super(ErrorCode.AUTH_SESSION_EXPIRED);
  }
}
