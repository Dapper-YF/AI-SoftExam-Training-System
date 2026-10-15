package com.aiexam.foundation.auth.domain;

public record LoginIdentifier(
    Kind kind,
    String normalizedValue
) {
  public String persistenceType() {
    return switch (kind) {
      case PHONE -> "MOBILE";
      case EMAIL -> "EMAIL";
      case DEV_SEED_USERNAME -> "DEV_SEED_USERNAME";
    };
  }

  public enum Kind {
    PHONE,
    EMAIL,
    DEV_SEED_USERNAME
  }
}
