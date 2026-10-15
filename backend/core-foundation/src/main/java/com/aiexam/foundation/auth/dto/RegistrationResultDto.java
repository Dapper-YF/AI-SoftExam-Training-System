package com.aiexam.foundation.auth.dto;

import java.time.Instant;

public record RegistrationResultDto(
    Long userId,
    String username,
    String registrationStatus,
    Instant createdAt
) {
}
