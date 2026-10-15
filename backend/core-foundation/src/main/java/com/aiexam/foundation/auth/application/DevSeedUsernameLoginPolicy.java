package com.aiexam.foundation.auth.application;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class DevSeedUsernameLoginPolicy {
  private static final String ENABLED_PROPERTY = "aiexam.auth.dev-seed-username-login.enabled";
  private static final Set<String> ALLOWED_USERNAMES = Set.of("student", "admin", "question_reviewer");

  private final Environment environment;
  private final boolean enabled;
  private final boolean enabledConfigured;

  public DevSeedUsernameLoginPolicy(
      Environment environment,
      @Value("${aiexam.auth.dev-seed-username-login.enabled:false}") boolean enabled
  ) {
    this.environment = environment;
    this.enabled = enabled;
    this.enabledConfigured = environment.containsProperty(ENABLED_PROPERTY);
  }

  @PostConstruct
  public void failFastWhenForbidden() {
    if (enabled && !allowedByProfileAndProperty()) {
      throw new IllegalStateException("AUTH_DEV_SEED_USERNAME_LOGIN_NOT_ALLOWED");
    }
  }

  public boolean devSeedUsernameLoginEnabled() {
    return enabled && allowedByProfileAndProperty();
  }

  public boolean adminRegistrationEnabled() {
    return enabled && allowedByProfileAndProperty();
  }

  public static boolean isAllowedSeedUsername(String username) {
    return ALLOWED_USERNAMES.contains(username);
  }

  private boolean allowedByProfileAndProperty() {
    String[] activeProfiles = environment.getActiveProfiles();
    if (Arrays.stream(activeProfiles).anyMatch(DevSeedUsernameLoginPolicy::forbidsDevSeedUsernameLogin)) {
      return false;
    }
    return enabledConfigured && Arrays.stream(activeProfiles)
        .anyMatch(DevSeedUsernameLoginPolicy::allowsDevSeedUsernameLogin);
  }

  private static boolean forbidsDevSeedUsernameLogin(String profile) {
    String normalizedProfile = normalizeProfile(profile);
    return "prod".equalsIgnoreCase(normalizedProfile)
        || "production".equalsIgnoreCase(normalizedProfile);
  }

  private static boolean allowsDevSeedUsernameLogin(String profile) {
    String normalizedProfile = normalizeProfile(profile);
    return "dev".equalsIgnoreCase(normalizedProfile)
        || "test".equalsIgnoreCase(normalizedProfile)
        || "local-dev".equalsIgnoreCase(normalizedProfile);
  }

  private static String normalizeProfile(String profile) {
    return profile == null ? "" : profile.trim();
  }
}
