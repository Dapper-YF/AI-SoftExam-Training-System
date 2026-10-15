package com.aiexam.foundation.auth.domain;

import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

public final class MobileNormalizer {
  private MobileNormalizer() {
  }

  public static String normalizeMainlandChina(String mobile) {
    String value = mobile == null ? "" : mobile.trim().replace(" ", "");
    if (value.matches("1[3-9]\\d{9}")) {
      value = "+86" + value;
    }
    if (!value.matches("\\+861[3-9]\\d{9}")) {
      throw new BusinessException(ErrorCode.AUTH_REQUEST_INVALID, "手机号格式不合法", HttpStatus.BAD_REQUEST);
    }
    return value;
  }
}
