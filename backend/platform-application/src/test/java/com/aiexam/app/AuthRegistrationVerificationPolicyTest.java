package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.foundation.auth.application.AuthRegistrationVerificationPolicy;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;

class AuthRegistrationVerificationPolicyTest {
  @ParameterizedTest(name = "{0}")
  @MethodSource("blockedMixedProfiles")
  void stagingAlwaysBlocksVerificationBypass(String description, String[] profiles) {
    assertBlocked(false, profiles);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("normalizedProfiles")
  void stagingProfilesAreTrimmedAndCaseInsensitive(String description, String[] profiles) {
    assertBlocked(false, profiles);
  }

  @Test
  void blankProfileStillBlocksVerificationBypass() {
    assertBlockedWithRawProfiles(false, " ");
  }

  private static Stream<Arguments> blockedMixedProfiles() {
    return Stream.of(
        Arguments.of("staging alone", new String[]{"staging"}),
        Arguments.of("dev + staging", new String[]{"dev", "staging"}),
        Arguments.of("local-dev + staging", new String[]{"local-dev", "staging"}),
        Arguments.of("test + staging", new String[]{"test", "staging"})
    );
  }

  private static Stream<Arguments> normalizedProfiles() {
    return Stream.of(
        Arguments.of("uppercase staging", new String[]{"STAGING"}),
        Arguments.of("trimmed staging", new String[]{" staging "}),
        Arguments.of("mixed dev and uppercase staging", new String[]{"dev", " STAGING "})
    );
  }

  private void assertBlocked(boolean required, String... profiles) {
    AuthRegistrationVerificationPolicy policy = policy(required, profiles);
    assertThatThrownBy(policy::failFastWhenProductionBypassIsEnabled)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_REGISTRATION_VERIFICATION_BYPASS_NOT_ALLOWED");
  }

  private void assertBlockedWithRawProfiles(boolean required, String... profiles) {
    AuthRegistrationVerificationPolicy policy = policyWithRawProfiles(required, profiles);
    assertThatThrownBy(policy::failFastWhenProductionBypassIsEnabled)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_REGISTRATION_VERIFICATION_BYPASS_NOT_ALLOWED");
  }

  private AuthRegistrationVerificationPolicy policy(boolean required, String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    environment.setProperty("aiexam.auth.registration.verification-required", Boolean.toString(required));
    return new AuthRegistrationVerificationPolicy(environment, required);
  }

  private AuthRegistrationVerificationPolicy policyWithRawProfiles(boolean required, String... profiles) {
    MockEnvironment environment = new MockEnvironment() {
      @Override
      public String[] getActiveProfiles() {
        return profiles;
      }
    };
    environment.setProperty("aiexam.auth.registration.verification-required", Boolean.toString(required));
    return new AuthRegistrationVerificationPolicy(environment, required);
  }
}
