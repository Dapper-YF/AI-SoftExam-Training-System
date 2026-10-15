package com.aiexam.foundation.auth.application;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AuthRegistrationVerificationPolicy {
  private static final String VERIFICATION_REQUIRED_PROPERTY =
      "aiexam.auth.registration.verification-required";

  private final Environment environment;
  private final boolean registrationVerificationRequired;
  private final boolean verificationRequiredConfigured;

  public AuthRegistrationVerificationPolicy(
      Environment environment,
      @Value("${aiexam.auth.registration.verification-required:true}") boolean registrationVerificationRequired
  ) {
    this.environment = environment;
    this.registrationVerificationRequired = registrationVerificationRequired;
    this.verificationRequiredConfigured = environment.containsProperty(VERIFICATION_REQUIRED_PROPERTY);
  }

  @PostConstruct
  public void failFastWhenProductionBypassIsEnabled() {
    if (!registrationVerificationRequired && !verificationBypassAllowed()) {
      throw new IllegalStateException("AUTH_REGISTRATION_VERIFICATION_BYPASS_NOT_ALLOWED");
    }
  }

  public boolean registrationVerificationRequired() {
    return registrationVerificationRequired;
  }

  private boolean verificationBypassAllowed() {
    String[] activeProfiles = environment.getActiveProfiles();
    if (Arrays.stream(activeProfiles).anyMatch(AuthRegistrationVerificationPolicy::forbidsVerificationBypass)) {
      return false;
    }
    return verificationRequiredConfigured && Arrays.stream(activeProfiles)
        .anyMatch(AuthRegistrationVerificationPolicy::allowsVerificationBypass);
  }

  private static boolean forbidsVerificationBypass(String profile) {
    String normalizedProfile = normalizeProfile(profile);
    return "prod".equalsIgnoreCase(normalizedProfile)
        || "production".equalsIgnoreCase(normalizedProfile)
        || "staging".equalsIgnoreCase(normalizedProfile);
  }

  private static boolean allowsVerificationBypass(String profile) {
    String normalizedProfile = normalizeProfile(profile);
    return "dev".equalsIgnoreCase(normalizedProfile)
        || "test".equalsIgnoreCase(normalizedProfile)
        || "local-dev".equalsIgnoreCase(normalizedProfile);
  }

  private static String normalizeProfile(String profile) {
    return profile == null ? "" : profile.trim();
  }
}
