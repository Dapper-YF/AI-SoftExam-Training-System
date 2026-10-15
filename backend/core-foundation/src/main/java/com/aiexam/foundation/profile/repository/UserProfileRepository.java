package com.aiexam.foundation.profile.repository;

import com.aiexam.foundation.profile.dto.UserProfileDto;
import java.util.Optional;

public interface UserProfileRepository {
  Optional<UserProfileDto> findByUserId(Long userId);

  boolean updateDisplayName(Long userId, String displayName, Long expectedVersion);
  default boolean updatePreparation(Long userId, String displayName, Long qualificationId, String regionCode, String regionName, Long expectedVersion) {
    return updateDisplayName(userId, displayName, expectedVersion);
  }
}
