package com.aiexam.common.security;

import java.util.Optional;

public interface CurrentUserProvider {
  Optional<PlatformPrincipal> currentPrincipal();

  default Long requireUserId() {
    return currentPrincipal()
        .map(PlatformPrincipal::userId)
        .orElseThrow(() -> new PlatformAuthenticationException());
  }
}
