package com.aiexam.foundation.auth.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @JsonAlias("username") @NotBlank String identifier,
    @NotBlank String password
) {
}
