package com.aiexam.foundation.auth.infra;

import com.aiexam.foundation.auth.domain.Argon2idPasswordPolicy;
import com.aiexam.foundation.auth.repository.PasswordHashingPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "aiexam.auth", name = "password-adapter", havingValue = "argon2id", matchIfMissing = true)
public class Argon2idPasswordHashingPort implements PasswordHashingPort {
  @Override
  public String hash(String password, Argon2idPasswordPolicy policy) {
    return encoder(policy).encode(password);
  }

  @Override
  public boolean verify(String password, String passwordHash) {
    return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().matches(password, passwordHash);
  }

  private Argon2PasswordEncoder encoder(Argon2idPasswordPolicy policy) {
    return new Argon2PasswordEncoder(policy.saltBytes(), 32, policy.parallelism(), policy.memoryKiB(), policy.iterations());
  }
}
