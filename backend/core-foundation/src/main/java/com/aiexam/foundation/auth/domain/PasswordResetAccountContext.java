package com.aiexam.foundation.auth.domain;

public record PasswordResetAccountContext(
    long userId,
    String nickname,
    String normalizedMobile,
    String normalizedEmail
) {
}
