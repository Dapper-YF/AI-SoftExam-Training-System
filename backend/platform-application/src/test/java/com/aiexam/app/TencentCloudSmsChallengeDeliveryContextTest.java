package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.aiexam.foundation.auth.repository.ChallengeDeliveryPort;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class TencentCloudSmsChallengeDeliveryContextTest {
  @Test
  void productionContextFailsFastWhenTencentSmsConfigurationIsIncomplete() {
    Throwable thrown = catchThrowable(() -> withSystemProperties(Map.of(
        "spring.profiles.active", "production",
        "aiexam.auth.challenge.tencent-sms.enabled", "true",
        "aiexam.auth.challenge.tencent-sms.secret-id", "",
        "aiexam.auth.challenge.tencent-sms.secret-key", "",
        "aiexam.auth.challenge.tencent-sms.sdk-app-id", "1400006666",
        "aiexam.auth.challenge.tencent-sms.sign-name", "TencentCloud",
        "aiexam.auth.challenge.tencent-sms.template-id", "1110",
        "aiexam.auth.challenge.tencent-sms.region", "ap-guangzhou",
        "aiexam.auth.registration.verification-required", "true")
        , () -> new SpringApplicationBuilder(AiExamPlatformApplication.class)
        .web(WebApplicationType.NONE)
        .properties(
            "spring.main.banner-mode=off",
            "aiexam.auth.persistence=unavailable",
            "aiexam.auth.password-adapter=unavailable",
            "aiexam.auth.registration.verification-required=true",
            "spring.profiles.active=production",
            "aiexam.auth.challenge.tencent-sms.enabled=true",
            "aiexam.auth.challenge.tencent-sms.secret-id=",
            "aiexam.auth.challenge.tencent-sms.secret-key=",
            "aiexam.auth.challenge.tencent-sms.sdk-app-id=1400006666",
            "aiexam.auth.challenge.tencent-sms.sign-name=TencentCloud",
            "aiexam.auth.challenge.tencent-sms.template-id=1110",
            "aiexam.auth.challenge.tencent-sms.region=ap-guangzhou")
        .run()));
    assertThat(thrown).isInstanceOf(Exception.class);
    assertThat(rootCauseMessage(thrown)).contains("TENCENT_SMS_CONFIGURATION_INCOMPLETE");
  }

  @Test
  void productionContextStartsWhenTencentSmsConfigurationIsComplete() {
    try (ConfigurableApplicationContext context = withSystemProperties(Map.of(
        "spring.profiles.active", "production",
        "aiexam.auth.challenge.tencent-sms.enabled", "true",
        "aiexam.auth.challenge.tencent-sms.secret-id", "test-secret-id",
        "aiexam.auth.challenge.tencent-sms.secret-key", "test-secret-key",
        "aiexam.auth.challenge.tencent-sms.sdk-app-id", "1400006666",
        "aiexam.auth.challenge.tencent-sms.sign-name", "TencentCloud",
        "aiexam.auth.challenge.tencent-sms.template-id", "1110",
        "aiexam.auth.challenge.tencent-sms.region", "ap-guangzhou",
        "aiexam.auth.registration.verification-required", "true")
        , () -> new SpringApplicationBuilder(AiExamPlatformApplication.class)
        .web(WebApplicationType.NONE)
        .properties(
            "spring.main.banner-mode=off",
            "aiexam.auth.persistence=unavailable",
            "aiexam.auth.password-adapter=unavailable",
            "aiexam.auth.registration.verification-required=true",
            "spring.profiles.active=production",
            "aiexam.auth.challenge.tencent-sms.enabled=true",
            "aiexam.auth.challenge.tencent-sms.secret-id=test-secret-id",
            "aiexam.auth.challenge.tencent-sms.secret-key=test-secret-key",
            "aiexam.auth.challenge.tencent-sms.sdk-app-id=1400006666",
            "aiexam.auth.challenge.tencent-sms.sign-name=TencentCloud",
            "aiexam.auth.challenge.tencent-sms.template-id=1110",
            "aiexam.auth.challenge.tencent-sms.region=ap-guangzhou")
        .run())) {
      assertThat(context.getBean(ChallengeDeliveryPort.class).mobileCapability().provider())
          .isEqualTo("TENCENT_CLOUD_SMS");
      assertThat(context.getBean(ChallengeDeliveryPort.class).mobileCapability().available()).isTrue();
      assertThat(context.getBeanNamesForType(ChallengeDeliveryPort.class)).hasSize(1);
    }
  }

  @Test
  void mixedProductionAndDevProfilesRejectDevelopmentSmsDelivery() {
    Throwable thrown = catchThrowable(() -> withSystemProperties(Map.of(
        "spring.profiles.active", "dev,production",
        "aiexam.auth.challenge.dev-delivery.enabled", "true",
        "aiexam.auth.registration.verification-required", "true")
        , () -> new SpringApplicationBuilder(AiExamPlatformApplication.class)
        .web(WebApplicationType.NONE)
        .properties(
            "spring.main.banner-mode=off",
            "aiexam.auth.persistence=unavailable",
            "aiexam.auth.password-adapter=unavailable",
            "aiexam.auth.registration.verification-required=true",
            "aiexam.auth.challenge.dev-delivery.enabled=true",
            "spring.profiles.active=dev,production")
        .run()));
    assertThat(thrown).isInstanceOf(Exception.class);
    assertThat(rootCauseMessage(thrown)).contains("AUTH_DEV_CHALLENGE_DELIVERY_NOT_ALLOWED");
  }

  private static <T> T withSystemProperties(Map<String, String> properties, ThrowingSupplier<T> supplier) {
    Map<String, String> previousValues = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : properties.entrySet()) {
      previousValues.put(entry.getKey(), System.getProperty(entry.getKey()));
      if (entry.getValue() == null) {
        System.clearProperty(entry.getKey());
      } else {
        System.setProperty(entry.getKey(), entry.getValue());
      }
    }
    try {
      return supplier.get();
    } finally {
      for (Map.Entry<String, String> entry : previousValues.entrySet()) {
        if (entry.getValue() == null) {
          System.clearProperty(entry.getKey());
        } else {
          System.setProperty(entry.getKey(), entry.getValue());
        }
      }
    }
  }

  private static String rootCauseMessage(Throwable throwable) {
    Throwable current = throwable;
    while (current != null && current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current == null ? null : current.getMessage();
  }

  @FunctionalInterface
  private interface ThrowingSupplier<T> {
    T get();
  }
}
