package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.foundation.auth.infra.DevChallengeDeliveryPort;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DevChallengeDeliveryPortTest {
  @Test
  void devProviderStoresActualVerificationCodesInsteadOfUniversalCode() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("dev");
    DevChallengeDeliveryPort port = new DevChallengeDeliveryPort(environment);

    port.rejectProductionProfiles();
    port.deliverMobileCode("REGISTER", java.util.UUID.fromString("018fa7d9-0000-7000-8000-000000000301"), "+8613812345678", "123456");
    port.deliverMobileCode("REGISTER", java.util.UUID.fromString("018fa7d9-0000-7000-8000-000000000302"), "+8613812345678", "654321");

    assertThat(port.mobileCapability().provider()).isEqualTo("DEV_MOBILE");
    assertThat(port.mobileCapability().available()).isTrue();
    assertThat(port.findCode(java.util.UUID.fromString("018fa7d9-0000-7000-8000-000000000301"))).hasValue("123456");
    assertThat(port.findCode(java.util.UUID.fromString("018fa7d9-0000-7000-8000-000000000302"))).hasValue("654321");
    assertThat(port.findCode(java.util.UUID.fromString("018fa7d9-0000-7000-8000-000000000303"))).isEmpty();
  }

  @Test
  void devProviderRejectsMixedProductionProfiles() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("dev", "production");
    DevChallengeDeliveryPort port = new DevChallengeDeliveryPort(environment);

    assertThatThrownBy(port::rejectProductionProfiles)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_DEV_CHALLENGE_DELIVERY_NOT_ALLOWED");
  }
}
