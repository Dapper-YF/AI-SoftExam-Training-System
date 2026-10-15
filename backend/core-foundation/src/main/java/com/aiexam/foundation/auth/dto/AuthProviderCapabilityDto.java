package com.aiexam.foundation.auth.dto;

public record AuthProviderCapabilityDto(
    String provider,
    String status,
    String message
) {
  public static AuthProviderCapabilityDto available(String provider, String message) {
    return new AuthProviderCapabilityDto(provider, "AVAILABLE", message);
  }

  public static AuthProviderCapabilityDto disabled(String provider, String message) {
    return new AuthProviderCapabilityDto(provider, "DISABLED", message);
  }

  public static AuthProviderCapabilityDto unavailable(String provider, String message) {
    return new AuthProviderCapabilityDto(provider, "UNAVAILABLE", message);
  }

  public boolean available() {
    return "AVAILABLE".equals(status);
  }
}
