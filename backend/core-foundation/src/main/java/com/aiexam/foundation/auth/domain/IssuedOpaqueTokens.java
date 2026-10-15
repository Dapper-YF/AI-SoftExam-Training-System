package com.aiexam.foundation.auth.domain;

import java.time.Instant;

public record IssuedOpaqueTokens(
    String accessToken,
    String accessTokenHash,
    Instant accessExpiresAt,
    String refreshToken,
    String refreshTokenHash,
    Instant refreshExpiresAt
) {
}
