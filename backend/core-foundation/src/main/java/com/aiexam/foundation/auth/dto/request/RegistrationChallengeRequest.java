package com.aiexam.foundation.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RegistrationChallengeRequest(
    @NotBlank String mobile,
    @NotNull UUID requestId
) {
}
