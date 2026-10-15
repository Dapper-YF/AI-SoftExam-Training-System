package com.aiexam.foundation.auth.domain;

public record AuthClientEvidence(
    String clientIpHash,
    String userAgentSummary
) {
  public static AuthClientEvidence empty() {
    return new AuthClientEvidence(null, null);
  }
}
