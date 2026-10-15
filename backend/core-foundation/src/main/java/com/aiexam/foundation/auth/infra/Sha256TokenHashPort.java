package com.aiexam.foundation.auth.infra;

import com.aiexam.foundation.auth.repository.TokenHashPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class Sha256TokenHashPort implements TokenHashPort {
  @Override
  public String hash(String opaqueToken) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(opaqueToken.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required by the platform runtime", ex);
    }
  }
}
