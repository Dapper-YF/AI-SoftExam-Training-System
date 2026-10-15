package com.aiexam.foundation.auth.infra;

import com.aiexam.common.security.DevOnlyProfiles;
import jakarta.annotation.PostConstruct;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "aiexam.auth.dev-accounts",
    name = "enabled",
    havingValue = "true"
)
public class DevAccountInitializationProductionGuard {
  private final Environment environment;

  public DevAccountInitializationProductionGuard(Environment environment) {
    this.environment = environment;
  }

  @PostConstruct
  public void rejectAccountsOutsideDevOrTest() {
    Set<String> supportedProfiles = Set.of("dev", "test", "local-dev");
    if (!DevOnlyProfiles.allowed(environment, supportedProfiles)) {
      throw new IllegalStateException("DEV_ACCOUNT_INITIALIZATION_FORBIDDEN_OUTSIDE_DEV_OR_TEST");
    }
  }
}
