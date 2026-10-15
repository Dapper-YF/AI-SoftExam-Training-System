package com.aiexam.foundation.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record UpdateUserProfileRequest(
    @NotBlank @Size(max = 64) String displayName,
    @Positive Long preparationQualificationId,
    @Size(max = 32) String examRegionCode,
    @NotNull @PositiveOrZero Long version
) {
  public UpdateUserProfileRequest(String displayName, Long version) {
    this(displayName, null, null, version);
  }
}
