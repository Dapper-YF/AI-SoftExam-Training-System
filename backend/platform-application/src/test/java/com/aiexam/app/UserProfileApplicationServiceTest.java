package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.common.error.BusinessException;
import com.aiexam.common.security.CurrentUserProvider;
import com.aiexam.common.security.PlatformPrincipal;
import com.aiexam.foundation.profile.application.UserProfileApplicationService;
import com.aiexam.foundation.profile.dto.UpdateUserProfileRequest;
import com.aiexam.foundation.profile.dto.UserProfileDto;
import com.aiexam.foundation.profile.repository.UserProfileRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserProfileApplicationServiceTest {
  @Test
  void returnsOnlyCurrentUsersProfile() {
    InMemoryProfileRepository repository = new InMemoryProfileRepository();
    UserProfileApplicationService service = new UserProfileApplicationService(currentUser(1001L), repository);

    UserProfileDto profile = service.me();

    assertThat(profile.userId()).isEqualTo(1001L);
    assertThat(profile.maskedMobile()).contains("****");
    assertThat(profile.maskedEmail()).contains("***@");
    assertThat(profile).hasNoNullFieldsOrPropertiesExcept("maskedMobile", "maskedEmail");
  }

  @Test
  void updateRequiresMatchingVersion() {
    InMemoryProfileRepository repository = new InMemoryProfileRepository();
    UserProfileApplicationService service = new UserProfileApplicationService(currentUser(1001L), repository);

    assertThatThrownBy(() -> service.updateMe(new UpdateUserProfileRequest("Student New", 98L)))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("PROFILE_CONFLICT");

    UserProfileDto updated = service.updateMe(new UpdateUserProfileRequest("Student New", 7L));

    assertThat(updated.displayName()).isEqualTo("Student New");
    assertThat(updated.version()).isEqualTo(8L);
  }

  private CurrentUserProvider currentUser(Long userId) {
    return () -> Optional.of(new PlatformPrincipal(
        userId,
        "student",
        "session",
        List.of("STUDENT"),
        List.of(),
        0));
  }

  private static final class InMemoryProfileRepository implements UserProfileRepository {
    private UserProfileDto profile = new UserProfileDto(
        1001L,
        "student",
        "Student",
        "+86139****2001",
        "s***@aiexam.local",
        "ENABLED",
        7L,
        Instant.parse("2026-07-19T00:00:00Z"));

    @Override
    public Optional<UserProfileDto> findByUserId(Long userId) {
      return profile.userId().equals(userId) ? Optional.of(profile) : Optional.empty();
    }

    @Override
    public boolean updateDisplayName(Long userId, String displayName, Long expectedVersion) {
      if (!profile.userId().equals(userId) || !profile.version().equals(expectedVersion)) {
        return false;
      }
      profile = new UserProfileDto(
          profile.userId(),
          profile.username(),
          displayName,
          profile.maskedMobile(),
          profile.maskedEmail(),
          profile.status(),
          profile.version() + 1,
          Instant.parse("2026-07-19T00:01:00Z"));
      return true;
    }
  }
}
