package com.aiexam.foundation.auth.dto;

public record RotatedAuthSession(
    SessionExpiryDto expiry,
    String accessToken,
    String refreshToken
) {
}
