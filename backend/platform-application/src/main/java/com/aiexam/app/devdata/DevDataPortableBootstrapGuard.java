package com.aiexam.app.devdata;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "aiexam.dev-data", name = "enabled", havingValue = "true")
public class DevDataPortableBootstrapGuard {
  private final Environment environment;

  public DevDataPortableBootstrapGuard(Environment environment) {
    this.environment = environment;
  }

  @PostConstruct
  public void rejectOutsideDevOrTest() {
    Set<String> activeProfiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
    boolean supported = activeProfiles.contains("dev") || activeProfiles.contains("test");
    boolean production = activeProfiles.contains("prod") || activeProfiles.contains("production");
    if (production || !supported) {
      throw new IllegalStateException("DEV_DATA_BOOTSTRAP_FORBIDDEN_OUTSIDE_DEV_OR_TEST");
    }
  }
}
