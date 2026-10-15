package com.aiexam.foundation.auth.domain;

import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ErrorCode;
import com.aiexam.foundation.auth.application.DevSeedUsernameLoginPolicy;
import java.net.IDN;
import java.text.Normalizer;
import java.util.Locale;
import org.springframework.http.HttpStatus;

public final class LoginIdentifierNormalizer {
  private LoginIdentifierNormalizer() {
  }

  public static LoginIdentifier normalize(String identifier) {
    return normalize(identifier, false);
  }

  public static LoginIdentifier normalize(String identifier, boolean devSeedUsernameLoginEnabled) {
    String value = identifier == null ? "" : Normalizer.normalize(identifier.trim(), Normalizer.Form.NFC);
    String compact = value.replace(" ", "");
    if (compact.matches("1[3-9]\\d{9}") || compact.matches("\\+861[3-9]\\d{9}")) {
      return new LoginIdentifier(LoginIdentifier.Kind.PHONE, MobileNormalizer.normalizeMainlandChina(compact));
    }
    if (value.contains("@")) {
      return new LoginIdentifier(LoginIdentifier.Kind.EMAIL, normalizeEmail(value));
    }
    if (devSeedUsernameLoginEnabled && DevSeedUsernameLoginPolicy.isAllowedSeedUsername(value)) {
      return new LoginIdentifier(LoginIdentifier.Kind.DEV_SEED_USERNAME, value);
    }
    throw invalid();
  }

  public static String normalizeEmail(String email) {
    String value = email == null ? "" : Normalizer.normalize(email.trim(), Normalizer.Form.NFC);
    int at = value.lastIndexOf('@');
    if (at <= 0 || at != value.indexOf('@') || at == value.length() - 1) {
      throw invalid();
    }
    String local = value.substring(0, at);
    String domain = value.substring(at + 1);
    if (local.isBlank() || domain.isBlank() || local.length() > 64 || value.length() > 254
        || hasControl(value) || hasWhitespace(value) || local.startsWith(".") || local.endsWith(".") || local.contains("..")
        || domain.startsWith("-") || domain.endsWith("-") || !domain.contains(".")) {
      throw invalid();
    }
    String asciiDomain;
    try {
      asciiDomain = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException ex) {
      throw invalid();
    }
    if (asciiDomain.isBlank() || asciiDomain.length() > 253 || local.length() + 1 + asciiDomain.length() > 254
        || invalidDomainLabels(asciiDomain)) {
      throw invalid();
    }
    return local + "@" + asciiDomain;
  }

  private static boolean hasControl(String value) {
    return value.codePoints().anyMatch(Character::isISOControl);
  }

  private static boolean hasWhitespace(String value) {
    return value.codePoints().anyMatch(Character::isWhitespace);
  }

  private static boolean invalidDomainLabels(String asciiDomain) {
    String[] labels = asciiDomain.split("\\.");
    if (labels.length < 2) {
      return true;
    }
    for (String label : labels) {
      if (label.isBlank() || label.startsWith("-") || label.endsWith("-")
          || !label.matches("[a-z0-9-]+")) {
        return true;
      }
    }
    return false;
  }

  private static BusinessException invalid() {
    return new BusinessException(ErrorCode.AUTH_REQUEST_INVALID, "登录标识格式不合法", HttpStatus.BAD_REQUEST);
  }
}
