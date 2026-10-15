package com.aiexam.foundation.auth.repository;

import com.aiexam.foundation.auth.dto.AuthProviderCapabilityDto;
import java.util.UUID;

public interface ChallengeDeliveryPort {
  AuthProviderCapabilityDto mobileCapability();

  AuthProviderCapabilityDto emailCapability();

  AuthProviderCapabilityDto wechatCapability();

  void deliverMobileCode(String purpose, UUID challengeId, String normalizedMobile, String code);

  void deliverEmailCode(String purpose, UUID challengeId, String normalizedEmail, String code);
}
