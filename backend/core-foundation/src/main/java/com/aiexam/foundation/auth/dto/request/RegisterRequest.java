package com.aiexam.foundation.auth.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RegisterRequest(
    @JsonAlias("username") @NotBlank String nickname,
    @NotBlank String password,
    String displayName,
    @JsonAlias("mobile") @NotBlank String phone,
    String email,
    UUID challengeId,
    String verificationCode,
    @NotNull UUID requestId,
    String role
) {
}
