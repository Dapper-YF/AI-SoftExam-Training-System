package com.aiexam.common.security;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;

public final class DevOnlyProfiles {
  private static final Set<String> FORBIDDEN_PROFILES = Set.of("prod", "production", "staging");

  private DevOnlyProfiles() {
  }

  public static boolean allowed(Environment environment, Set<String> supportedProfiles) {
    Set<String> activeProfiles = normalizedActiveProfiles(environment);
    if (activeProfiles.stream().anyMatch(FORBIDDEN_PROFILES::contains)) {
      return false;
    }
    return activeProfiles.stream().anyMatch(supportedProfiles::contains);
  }

  public static Set<String> normalizedActiveProfiles(Environment environment) {
    return Arrays.stream(environment.getActiveProfiles())
        .map(DevOnlyProfiles::normalizeProfile)
        .filter(profile -> !profile.isBlank())
        .collect(Collectors.toSet());
  }

  public static String normalizeProfile(String profile) {
    return profile == null ? "" : profile.trim().toLowerCase(java.util.Locale.ROOT);
  }
}
