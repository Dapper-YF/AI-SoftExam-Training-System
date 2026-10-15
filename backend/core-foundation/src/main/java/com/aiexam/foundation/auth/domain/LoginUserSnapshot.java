package com.aiexam.foundation.auth.domain;

import java.time.Instant;

public record LoginUserSnapshot(
    Long userId,
    String username,
    String displayName,
    String maskedMobile,
    String passwordHash,
    String status,
    Instant lockedUntil,
    long authVersion
) {
  public boolean enabled() {
    return "ENABLED".equals(status);
  }

  public boolean lockedAt(Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }
}
