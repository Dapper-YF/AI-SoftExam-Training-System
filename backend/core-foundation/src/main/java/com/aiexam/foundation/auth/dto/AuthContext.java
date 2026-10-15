package com.aiexam.foundation.auth.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AuthContext(
    AuthUserDto user,
    List<String> roles,
    List<String> permissions,
    List<AuthMenuDto> menus,
    Map<String, Boolean> featureFlags,
    Instant accessExpiresAt,
    Instant refreshExpiresAt
) {
  public AuthContext {
    roles = roles == null ? List.of() : List.copyOf(roles);
    permissions = permissions == null ? List.of() : List.copyOf(permissions);
    menus = menus == null ? List.of() : List.copyOf(menus);
    featureFlags = featureFlags == null ? Map.of() : Map.copyOf(featureFlags);
  }

  public record AuthUserDto(
      Long id,
      String username,
      String displayName,
      String maskedMobile,
      String status
  ) {
  }

  public record AuthMenuDto(
      String code,
      String name,
      String routePath,
      String icon
  ) {
  }
}
