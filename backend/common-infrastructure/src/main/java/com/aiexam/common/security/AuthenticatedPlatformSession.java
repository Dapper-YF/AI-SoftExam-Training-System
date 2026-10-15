package com.aiexam.common.security;

import java.time.Instant;

public record AuthenticatedPlatformSession(
    PlatformPrincipal principal,
    Instant accessExpiresAt,
    Instant refreshExpiresAt
) {
}
