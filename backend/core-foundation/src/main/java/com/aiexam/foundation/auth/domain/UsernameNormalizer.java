package com.aiexam.foundation.auth.domain;

import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ErrorCode;
import java.text.Normalizer;
import org.springframework.http.HttpStatus;

public final class UsernameNormalizer {
  private UsernameNormalizer() {
  }

  public static String normalize(String username) {
    String value = username == null ? "" : Normalizer.normalize(username, Normalizer.Form.NFC);
    int codePoints = value.codePointCount(0, value.length());
    if (value.isBlank()
        || !value.equals(value.trim())
        || codePoints < 1
        || codePoints > 32
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new BusinessException(ErrorCode.AUTH_REQUEST_INVALID, "昵称格式不合法", HttpStatus.BAD_REQUEST);
    }
    return value;
  }

  public static int minLength() {
    return 1;
  }

  public static int maxLength() {
    return 32;
  }
}
