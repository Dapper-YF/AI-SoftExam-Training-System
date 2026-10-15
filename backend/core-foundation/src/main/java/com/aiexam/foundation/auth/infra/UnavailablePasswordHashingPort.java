package com.aiexam.foundation.auth.infra;

import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ErrorCode;
import com.aiexam.foundation.auth.domain.Argon2idPasswordPolicy;
import com.aiexam.foundation.auth.repository.PasswordHashingPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "aiexam.auth", name = "password-adapter", havingValue = "unavailable")
public class UnavailablePasswordHashingPort implements PasswordHashingPort {
  @Override
  public String hash(String password, Argon2idPasswordPolicy policy) {
    throw new BusinessException(
        ErrorCode.AUTH_CAPACITY_BUSY,
        "Argon2id密码策略端口尚未接入运行适配器",
        HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Override
  public boolean verify(String password, String passwordHash) {
    return false;
  }
}
