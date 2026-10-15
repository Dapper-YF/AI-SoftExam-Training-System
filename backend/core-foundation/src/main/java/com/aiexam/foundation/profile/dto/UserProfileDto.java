package com.aiexam.foundation.profile.dto;

import java.time.Instant;

public record UserProfileDto(
    Long userId,
    String username,
    String displayName,
    String maskedMobile,
    String maskedEmail,
    Long preparationQualificationId,
    String preparationQualificationName,
    String examRegionCode,
    String examRegionName,
    String status,
    Long version,
    Instant updatedAt
) {
  public UserProfileDto(Long userId, String username, String displayName, String maskedMobile,
                        String maskedEmail, String status, Long version, Instant updatedAt) {
    this(userId, username, displayName, maskedMobile, maskedEmail, 0L, "", "", "", status, version, updatedAt);
  }
}
