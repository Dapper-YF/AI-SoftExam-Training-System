package com.aiexam.foundation.auth.infra;

import com.aiexam.foundation.auth.repository.OpaqueTokenPort;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class SecureRandomOpaqueTokenPort implements OpaqueTokenPort {
  private final SecureRandom secureRandom = new SecureRandom();

  @Override
  public String newToken() {
    byte[] value = new byte[32];
    secureRandom.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }
}
