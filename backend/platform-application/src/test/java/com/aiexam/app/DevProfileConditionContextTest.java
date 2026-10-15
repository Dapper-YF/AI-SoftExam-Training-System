package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiexam.common.security.DevBearerTokenFilter;
import com.aiexam.foundation.auth.infra.DevTestAccountInitializer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DevProfileConditionContextTest {
  @ParameterizedTest
  @CsvSource({
      "dev,prod",
      "dev,production",
      "dev,staging",
      "dev, Production ",
      "DEV, STAGING "
  })
  void devBearerIsNotRegisteredWhenForbiddenProfilesAreMixed(String firstProfile, String secondProfile) {
    new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().setActiveProfiles(firstProfile, secondProfile))
        .withPropertyValues("aiexam.security.dev-bearer.enabled=true")
        .withUserConfiguration(DevBearerTokenFilter.class)
        .run(context -> assertThat(context).doesNotHaveBean(DevBearerTokenFilter.class));
  }

  @ParameterizedTest
  @CsvSource({
      "dev,prod",
      "dev,production",
      "dev,staging",
      "dev, Production ",
      "DEV, STAGING "
  })
  void devAccountInitializerIsNotRegisteredWhenForbiddenProfilesAreMixed(String firstProfile, String secondProfile) {
    new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().setActiveProfiles(firstProfile, secondProfile))
        .withPropertyValues("aiexam.auth.dev-accounts.enabled=true")
        .withUserConfiguration(DevTestAccountInitializer.class)
        .run(context -> assertThat(context).doesNotHaveBean(DevTestAccountInitializer.class));
  }
}
