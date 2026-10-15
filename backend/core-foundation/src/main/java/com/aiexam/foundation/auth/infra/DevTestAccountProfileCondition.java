package com.aiexam.foundation.auth.infra;

import com.aiexam.common.security.DevOnlyProfiles;
import java.util.Set;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class DevTestAccountProfileCondition implements Condition {
  private static final Set<String> SUPPORTED_PROFILES = Set.of("dev", "test", "local-dev");

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    return DevOnlyProfiles.allowed(context.getEnvironment(), SUPPORTED_PROFILES);
  }
}
