package com.aiexam.common.security;

import java.time.Instant;
import java.util.Optional;

public interface PlatformSessionAuthenticator {
  Optional<AuthenticatedPlatformSession> authenticateAccessToken(String opaqueAccessToken, Instant requestNow);
}
