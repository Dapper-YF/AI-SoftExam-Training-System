package com.aiexam.foundation.auth.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record PasswordResetRequest(
    @JsonAlias("username") @NotBlank String identifier,
    @NotNull UUID challengeId,
    @NotBlank String verificationCode,
    @NotBlank String newPassword,
    @NotNull UUID requestId
) {
}
