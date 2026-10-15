package com.aiexam.common.security;

import java.util.List;

public record PlatformPrincipal(
    Long userId,
    String username,
    String sessionId,
    List<String> roles,
    List<String> permissions,
    long authVersion
) {
  public PlatformPrincipal {
    roles = roles == null ? List.of() : List.copyOf(roles);
    permissions = permissions == null ? List.of() : List.copyOf(permissions);
  }
}
