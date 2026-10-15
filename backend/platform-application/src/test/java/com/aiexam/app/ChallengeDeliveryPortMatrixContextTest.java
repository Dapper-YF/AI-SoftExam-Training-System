package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ErrorCode;
import com.aiexam.foundation.auth.infra.DevChallengeDeliveryPort;
import com.aiexam.foundation.auth.infra.TencentCloudSmsChallengeDeliveryPort;
import com.aiexam.foundation.auth.infra.TencentCloudSmsProductionGuard;
import com.aiexam.foundation.auth.infra.UnavailableChallengeDeliveryPort;
import com.aiexam.foundation.auth.repository.ChallengeDeliveryPort;
import com.aiexam.foundation.auth.infra.tencent.TencentCloudSmsClient;
import com.aiexam.foundation.auth.infra.tencent.TencentCloudSmsClient.TencentCloudSmsSendCommand;
import com.aiexam.foundation.auth.infra.tencent.TencentCloudSmsClient.TencentCloudSmsSendResult;
import com.aiexam.foundation.auth.infra.tencent.TencentCloudSmsClient.TencentCloudSmsSendStatus;
import com.aiexam.foundation.auth.infra.tencent.TencentCloudSmsClientFactory;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;

class ChallengeDeliveryPortMatrixContextTest {
  @Test
  void defaultProfileUsesUnavailableChallengeDeliveryPort() {
    assertSingleChallengeDeliveryPort(
        "default",
        false,
        "MOBILE_SMS",
        "aiexam.auth.registration.verification-required=true");
  }

  @Test
  void mysqlOnlyProfileUsesUnavailableChallengeDeliveryPort() {
    assertSingleChallengeDeliveryPort(
        "mysql-only",
        false,
        "MOBILE_SMS",
        "spring.profiles.active=mysql",
        "aiexam.auth.registration.verification-required=true");
  }

  @Test
  void devProfileDisabledUsesUnavailableChallengeDeliveryPort() {
    assertSingleChallengeDeliveryPort(
        "dev-false",
        false,
        "MOBILE_SMS",
        "spring.profiles.active=dev",
        "aiexam.auth.challenge.dev-delivery.enabled=false",
        "aiexam.auth.registration.verification-required=true");
  }

  @Test
  void devProfileEnabledUsesUniqueDevChallengeDeliveryPort() {
    assertSingleChallengeDeliveryPort(
        "dev-true",
        true,
        "DEV_MOBILE",
        "spring.profiles.active=dev",
        "aiexam.auth.challenge.dev-delivery.enabled=true",
        "aiexam.auth.registration.verification-required=true");
  }

  @Test
  void productionDisabledFailsFast() {
    assertFailsWithMessage(
        "TENCENT_SMS_DISABLED_IN_PRODUCTION",
        "spring.profiles.active=production",
        "aiexam.auth.challenge.tencent-sms.enabled=false",
        "aiexam.auth.registration.verification-required=true");
  }

  @Test
  void stagingDisabledFailsFast() {
    assertFailsWithMessage(
        "TENCENT_SMS_DISABLED_IN_PRODUCTION",
        "spring.profiles.active=staging",
        "aiexam.auth.challenge.tencent-sms.enabled=false",
        "aiexam.auth.registration.verification-required=true");
  }

  @Test
  void productionEnabledButIncompleteConfigFailsFast() {
    assertFailsWithMessage(
        "TENCENT_SMS_CONFIGURATION_INCOMPLETE",
        "spring.profiles.active=production",
        "aiexam.auth.challenge.tencent-sms.enabled=true",
        "aiexam.auth.challenge.tencent-sms.secret-id=",
        "aiexam.auth.challenge.tencent-sms.secret-key=",
        "aiexam.auth.challenge.tencent-sms.sdk-app-id=1400006666",
        "aiexam.auth.challenge.tencent-sms.sign-name=TencentCloud",
        "aiexam.auth.challenge.tencent-sms.template-id=1110",
        "aiexam.auth.challenge.tencent-sms.region=ap-guangzhou",
        "aiexam.auth.registration.verification-required=true");
  }

  @Test
  void stagingEnabledAndCompleteUsesUniqueTencentChallengeDeliveryPort() {
    assertSingleChallengeDeliveryPort(
        "staging-tencent",
        true,
        "TENCENT_CLOUD_SMS",
        "spring.profiles.active=staging",
        "aiexam.auth.challenge.tencent-sms.enabled=true",
        "aiexam.auth.challenge.tencent-sms.secret-id=test-secret-id",
        "aiexam.auth.challenge.tencent-sms.secret-key=test-secret-key",
        "aiexam.auth.challenge.tencent-sms.sdk-app-id=1400006666",
        "aiexam.auth.challenge.tencent-sms.sign-name=TencentCloud",
        "aiexam.auth.challenge.tencent-sms.template-id=1110",
        "aiexam.auth.challenge.tencent-sms.region=ap-guangzhou",
        "aiexam.auth.registration.verification-required=true");
  }

  @Test
  void mixedDevAndStagingProfilesRejectFallbackProviders() {
    assertFailsWithMessage(
        "TENCENT_SMS_DISABLED_IN_PRODUCTION",
        "spring.profiles.active=dev,staging",
        "aiexam.auth.challenge.dev-delivery.enabled=false",
        "aiexam.auth.challenge.tencent-sms.enabled=false",
        "aiexam.auth.registration.verification-required=true");
  }

  @Test
  void mixedDevAndStagingProfilesRejectDevelopmentSmsDelivery() {
    assertFailsWithMessage(
        "AUTH_DEV_CHALLENGE_DELIVERY_NOT_ALLOWED",
        "spring.profiles.active=dev,staging",
        "aiexam.auth.challenge.dev-delivery.enabled=true",
        "aiexam.auth.challenge.tencent-sms.enabled=true",
        "aiexam.auth.challenge.tencent-sms.secret-id=test-secret-id",
        "aiexam.auth.challenge.tencent-sms.secret-key=test-secret-key",
        "aiexam.auth.challenge.tencent-sms.sdk-app-id=1400006666",
        "aiexam.auth.challenge.tencent-sms.sign-name=TencentCloud",
        "aiexam.auth.challenge.tencent-sms.template-id=1110",
        "aiexam.auth.challenge.tencent-sms.region=ap-guangzhou",
        "aiexam.auth.registration.verification-required=true");
  }

  @Test
  void unavailableChallengeDeliveryPortFailsClosedOnDeliveryAttempt() {
    try (ConfigurableApplicationContext context = runContext(
        "aiexam.auth.registration.verification-required=true")) {
      ChallengeDeliveryPort deliveryPort = context.getBean(ChallengeDeliveryPort.class);
      assertThat(deliveryPort.mobileCapability().available()).isFalse();
      assertThatThrownBy(() -> deliveryPort.deliverMobileCode(
          "REGISTER",
          UUID.fromString("018fa7d9-0000-7000-8000-000000000401"),
          "+8613812345678",
          "123456"))
          .isInstanceOfSatisfying(BusinessException.class, exception ->
              assertThat(exception.code()).isEqualTo(ErrorCode.AUTH_PROVIDER_UNAVAILABLE));
    }
  }

  private void assertSingleChallengeDeliveryPort(
      String scenario,
      boolean expectedAvailable,
      String expectedProvider,
      String... properties
  ) {
    try (ConfigurableApplicationContext context = runContext(properties)) {
      String[] beanNames = context.getBeanNamesForType(ChallengeDeliveryPort.class);
      assertThat(beanNames).as(scenario + " bean names").hasSize(1);
      ChallengeDeliveryPort deliveryPort = context.getBean(ChallengeDeliveryPort.class);
      assertThat(deliveryPort.mobileCapability().available())
          .as(scenario + " available")
          .isEqualTo(expectedAvailable);
      assertThat(deliveryPort.mobileCapability().provider())
          .as(scenario + " provider")
          .isEqualTo(expectedProvider);
      assertThat(deliveryPort.emailCapability().available()).isFalse();
      assertThat(deliveryPort.wechatCapability().available()).isFalse();
    }
  }

  private void assertFailsWithMessage(String expectedMessage, String... properties) {
    Throwable thrown = catchThrowable(() -> {
      try (ConfigurableApplicationContext ignored = runContext(properties)) {
        // Startup is expected to fail before this context can be used.
      }
    });
    assertThat(thrown).as(Arrays.toString(properties)).isNotNull();
    assertThat(rootCauseMessage(thrown)).contains(expectedMessage);
  }

  private ConfigurableApplicationContext runContext(String... properties) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.getEnvironment().setActiveProfiles(activeProfiles(properties));
    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
        "test-properties",
        propertyMap(properties)));
    context.register(ChallengePortMatrixTestConfiguration.class);
    context.refresh();
    return context;
  }

  private String[] activeProfiles(String... properties) {
    return Arrays.stream(properties)
        .filter(property -> property.startsWith("spring.profiles.active="))
        .findFirst()
        .map(property -> property.substring("spring.profiles.active=".length()).split(","))
        .orElseGet(() -> new String[0]);
  }

  private Map<String, Object> propertyMap(String... properties) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("aiexam.auth.registration.verification-required", "true");
    for (String property : properties) {
      int equals = property.indexOf('=');
      if (equals > 0) {
        values.put(property.substring(0, equals), property.substring(equals + 1));
      }
    }
    return values;
  }

  private static String rootCauseMessage(Throwable throwable) {
    Throwable current = throwable;
    while (current != null && current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current == null ? null : current.getMessage();
  }

  @Configuration(proxyBeanMethods = false)
  @Import({
      DevChallengeDeliveryPort.class,
      UnavailableChallengeDeliveryPort.class,
      TencentCloudSmsChallengeDeliveryPort.class,
      TencentCloudSmsProductionGuard.class
  })
  static class ChallengePortMatrixTestConfiguration {
    @Bean
    @Primary
    @SuppressWarnings("unused")
    TencentCloudSmsClientFactory testTencentCloudSmsClientFactory() {
      return configuration -> new TencentCloudSmsClient() {
        @Override
        public TencentCloudSmsSendResult send(TencentCloudSmsSendCommand command) {
          String phoneNumber = command.phoneNumberSet().isEmpty() ? null : command.phoneNumberSet().get(0);
          return new TencentCloudSmsSendResult(
              "request-mock",
              List.of(new TencentCloudSmsSendStatus("Ok", "mock", phoneNumber)));
        }
      };
    }
  }
}
