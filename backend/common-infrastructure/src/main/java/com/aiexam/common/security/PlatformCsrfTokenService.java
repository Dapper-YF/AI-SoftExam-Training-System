package com.aiexam.common.security;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class PlatformCsrfTokenService {
  private final SecureRandom secureRandom = new SecureRandom();

  public String newToken() {
    byte[] value = new byte[32];
    secureRandom.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }
}
