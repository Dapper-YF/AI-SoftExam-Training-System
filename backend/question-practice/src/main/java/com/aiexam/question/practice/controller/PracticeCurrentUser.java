package com.aiexam.question.practice.controller;

import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

final class PracticeCurrentUser {
  private PracticeCurrentUser() {
  }

  static Long requireUserId(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录", HttpStatus.UNAUTHORIZED);
    }
    String name = authentication.getName();
    if ("anonymousUser".equals(name)) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录", HttpStatus.UNAUTHORIZED);
    }
    if ("dev-admin".equals(name)) {
      return 1L;
    }
    if ("dev-student".equals(name)) {
      return 2L;
    }
    try {
      return Long.valueOf(name);
    } catch (NumberFormatException ignored) {
      return Integer.toUnsignedLong(name.hashCode());
    }
  }
}
