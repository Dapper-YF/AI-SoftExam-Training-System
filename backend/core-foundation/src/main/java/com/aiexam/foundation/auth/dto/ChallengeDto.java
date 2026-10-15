package com.aiexam.foundation.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record ChallengeDto(
    UUID challengeId,
    Instant expiresAt,
    int retryAfterSeconds
) {
}
