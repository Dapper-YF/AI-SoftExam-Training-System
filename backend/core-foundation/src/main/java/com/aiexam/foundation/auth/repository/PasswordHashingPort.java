package com.aiexam.foundation.auth.repository;

import com.aiexam.foundation.auth.domain.Argon2idPasswordPolicy;

public interface PasswordHashingPort {
  String hash(String password, Argon2idPasswordPolicy policy);

  boolean verify(String password, String passwordHash);
}
