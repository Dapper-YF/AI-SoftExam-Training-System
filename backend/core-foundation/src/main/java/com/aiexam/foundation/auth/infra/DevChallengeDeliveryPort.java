package com.aiexam.foundation.auth.infra;

import com.aiexam.foundation.auth.dto.AuthProviderCapabilityDto;
import com.aiexam.foundation.auth.repository.ChallengeDeliveryPort;
import com.aiexam.foundation.auth.repository.DevChallengeCodeLookupPort;
import jakarta.annotation.PostConstruct;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile({"dev", "test", "local-dev"})
@ConditionalOnProperty(prefix = "aiexam.auth.challenge.dev-delivery", name = "enabled", havingValue = "true")
public class DevChallengeDeliveryPort implements ChallengeDeliveryPort, DevChallengeCodeLookupPort {
  private static final Logger log = LoggerFactory.getLogger(DevChallengeDeliveryPort.class);
  private static final String DEV_PROVIDER_CODE = "DEV_MOBILE";

  private final Environment environment;
  private final ConcurrentMap<UUID, String> deliveredCodes = new ConcurrentHashMap<>();

  public DevChallengeDeliveryPort(Environment environment) {
    this.environment = environment;
  }

  @PostConstruct
  public void rejectProductionProfiles() {
    if (isProductionLike()) {
      throw new IllegalStateException("AUTH_DEV_CHALLENGE_DELIVERY_NOT_ALLOWED");
    }
  }

  @Override
  public AuthProviderCapabilityDto mobileCapability() {
    return AuthProviderCapabilityDto.available(DEV_PROVIDER_CODE, "Development mobile challenge delivery enabled");
  }

  @Override
  public AuthProviderCapabilityDto emailCapability() {
    return AuthProviderCapabilityDto.disabled("EMAIL", "Email challenge delivery not enabled");
  }

  @Override
  public AuthProviderCapabilityDto wechatCapability() {
    return AuthProviderCapabilityDto.disabled("WECHAT", "WeChat challenge delivery not enabled");
  }

  @Override
  public void deliverMobileCode(String purpose, UUID challengeId, String normalizedMobile, String code) {
    deliveredCodes.put(challengeId, code);
    log.info("Dev auth challenge delivered, purpose={}, challengeId={}, mobile={}",
        purpose, challengeId, mask(normalizedMobile));
  }

  @Override
  public void deliverEmailCode(String purpose, UUID challengeId, String normalizedEmail, String code) {
    throw new UnsupportedOperationException("Dev email challenge delivery is not enabled");
  }

  @Override
  public Optional<String> findCode(UUID challengeId) {
    return Optional.ofNullable(deliveredCodes.get(challengeId));
  }

  private String mask(String mobile) {
    if (mobile == null || mobile.length() < 8) {
      return "null";
    }
    return mobile.substring(0, Math.min(6, mobile.length() - 4)) + "****" + mobile.substring(mobile.length() - 4);
  }

  private boolean isProductionLike() {
    for (String profile : environment.getActiveProfiles()) {
      String normalized = profile == null ? "" : profile.trim();
      if ("prod".equalsIgnoreCase(normalized)
          || "production".equalsIgnoreCase(normalized)
          || "staging".equalsIgnoreCase(normalized)) {
        return true;
      }
    }
    return false;
  }
}
