package com.aiexam.foundation.auth.dto;

import java.time.Instant;

public record SessionExpiryDto(
    Instant accessExpiresAt,
    Instant refreshExpiresAt
) {
}
