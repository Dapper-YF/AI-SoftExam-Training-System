package com.aiexam.foundation.auth.dto;

public record CommittedAuthSession(
    AuthContext authContext,
    String accessToken,
    String refreshToken
) {
}
