package com.aiexam.foundation.auth.domain;

import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ErrorCode;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;

public final class PasswordPolicy {
  private static final int MIN_LENGTH = 8;
  private static final int MAX_LENGTH = 64;
  private static final List<String> COMMON_WEAK_PASSWORDS = List.of(
      "123456",
      "123456789",
      "1234567890",
      "12345678",
      "password",
      "password1",
      "password123",
      "qwerty123",
      "admin123",
      "admin123456",
      "letmein123456",
      "iloveyou123456",
      "11111111",
      "aaaaaaaa",
      "abc12345",
      "abcd1234");

  private PasswordPolicy() {
  }

  public static void validate(String password, String normalizedUsername, String normalizedMobile) {
    validateAndNormalize(password, normalizedUsername, normalizedMobile, "");
  }

  public static String validateAndNormalize(
      String password,
      String normalizedUsername,
      String normalizedMobile,
      String normalizedEmail
  ) {
    String value = normalizeForAuthentication(password);
    int codePoints = value.codePointCount(0, value.length());
    if (codePoints > MAX_LENGTH) {
      throw tooLong();
    }
    if (codePoints < MIN_LENGTH
        || value.codePoints().anyMatch(Character::isISOControl)
        || !containsAsciiUppercase(value)
        || !containsAsciiLowercase(value)
        || !containsAsciiDigit(value)) {
      throw violation();
    }
    String folded = value.toLowerCase(Locale.ROOT);
    if (COMMON_WEAK_PASSWORDS.contains(folded)
        || folded.contains("aiexam")
        || folded.contains("ai高项")
        || containsDerivedSecret(folded, normalizedUsername)
        || containsDerivedSecret(folded, normalizedMobile)
        || containsDerivedSecret(folded, digitsOnly(normalizedMobile))
        || containsDerivedSecret(folded, mainlandLocalDigits(normalizedMobile))
        || containsDerivedSecret(folded, normalizedEmail)
        || containsDerivedSecret(folded, emailLocalPart(normalizedEmail))) {
      throw violation();
    }
    return value;
  }

  public static String normalizeForAuthentication(String password) {
    return Normalizer.normalize(password == null ? "" : password, Normalizer.Form.NFC);
  }

  public static int minLength() {
    return MIN_LENGTH;
  }

  public static int maxLength() {
    return MAX_LENGTH;
  }

  private static boolean containsDerivedSecret(String foldedPassword, String candidate) {
    if (candidate == null || candidate.isBlank()) {
      return false;
    }
    String foldedCandidate = Normalizer.normalize(candidate, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    return foldedCandidate.length() >= 4 && foldedPassword.contains(foldedCandidate);
  }

  private static String digitsOnly(String value) {
    return value == null ? "" : value.replaceAll("\\D", "");
  }

  private static String mainlandLocalDigits(String value) {
    String digits = digitsOnly(value);
    return digits.startsWith("86") && digits.length() == 13 ? digits.substring(2) : digits;
  }

  private static String emailLocalPart(String email) {
    if (email == null) {
      return "";
    }
    int at = email.indexOf('@');
    return at <= 0 ? "" : email.substring(0, at);
  }

  private static boolean containsAsciiUppercase(String value) {
    return value.chars().anyMatch(ch -> ch >= 'A' && ch <= 'Z');
  }

  private static boolean containsAsciiLowercase(String value) {
    return value.chars().anyMatch(ch -> ch >= 'a' && ch <= 'z');
  }

  private static boolean containsAsciiDigit(String value) {
    return value.chars().anyMatch(ch -> ch >= '0' && ch <= '9');
  }

  private static BusinessException violation() {
    return new BusinessException(
        ErrorCode.AUTH_PASSWORD_POLICY_VIOLATION,
        "密码至少8位，且包含大写字母、小写字母和数字",
        HttpStatus.UNPROCESSABLE_ENTITY);
  }

  private static BusinessException tooLong() {
    return new BusinessException(
        ErrorCode.AUTH_PASSWORD_POLICY_VIOLATION,
        "密码过长，请缩短后重试",
        HttpStatus.UNPROCESSABLE_ENTITY);
  }
}
