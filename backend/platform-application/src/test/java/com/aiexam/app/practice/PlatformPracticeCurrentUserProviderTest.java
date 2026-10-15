package com.aiexam.app.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.common.security.CurrentUserProvider;
import com.aiexam.common.security.PlatformAuthenticationException;
import com.aiexam.common.security.PlatformPrincipal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlatformPracticeCurrentUserProviderTest {
  @Test
  void adaptsTheAuthenticatedPlatformPrincipal() {
    CurrentUserProvider currentUser = () -> Optional.of(new PlatformPrincipal(
        42L, "student-42", "session-1", List.of("STUDENT"), List.of("practice:read"), 3));

    var principal = new PlatformPracticeCurrentUserProvider(currentUser).requireCurrentUser();

    assertThat(principal.userId()).isEqualTo(42L);
    assertThat(principal.username()).isEqualTo("student-42");
    assertThat(principal.permissions()).containsExactly("practice:read");
  }

  @Test
  void keepsThePlatformAuthenticationFailureContract() {
    CurrentUserProvider anonymous = Optional::<PlatformPrincipal>empty;

    assertThatThrownBy(() -> new PlatformPracticeCurrentUserProvider(anonymous).requireCurrentUser())
        .isInstanceOf(PlatformAuthenticationException.class);
  }
}
