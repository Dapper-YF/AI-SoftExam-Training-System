package com.aiexam.foundation.auth.infra;

import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ErrorCode;
import com.aiexam.foundation.auth.dto.AuthProviderCapabilityDto;
import com.aiexam.foundation.auth.repository.ChallengeDeliveryPort;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod & !production & !staging")
@ConditionalOnProperty(
    prefix = "aiexam.auth.challenge.dev-delivery",
    name = "enabled",
    havingValue = "false",
    matchIfMissing = true)
public class UnavailableChallengeDeliveryPort implements ChallengeDeliveryPort {
  @Override
  public AuthProviderCapabilityDto mobileCapability() {
    return AuthProviderCapabilityDto.unavailable("MOBILE_SMS", "SMS challenge delivery is not configured");
  }

  @Override
  public AuthProviderCapabilityDto emailCapability() {
    return AuthProviderCapabilityDto.disabled("EMAIL", "Email challenge delivery is not enabled");
  }

  @Override
  public AuthProviderCapabilityDto wechatCapability() {
    return AuthProviderCapabilityDto.disabled("WECHAT", "WeChat challenge delivery is not enabled");
  }

  @Override
  public void deliverMobileCode(String purpose, UUID challengeId, String normalizedMobile, String code) {
    throw unavailable();
  }

  @Override
  public void deliverEmailCode(String purpose, UUID challengeId, String normalizedEmail, String code) {
    throw unavailable();
  }

  private BusinessException unavailable() {
    return new BusinessException(
        ErrorCode.AUTH_PROVIDER_UNAVAILABLE,
        "Authentication service is temporarily unavailable",
        HttpStatus.SERVICE_UNAVAILABLE);
  }
}
