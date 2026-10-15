package com.aiexam.foundation.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SmsLoginRequest(
    @NotBlank String mobile,
    @NotNull UUID challengeId,
    @NotBlank String verificationCode,
    @NotNull UUID requestId
) {
}
