package com.aiexam.app.practice;

import com.aiexam.common.security.CurrentUserProvider;
import com.aiexam.common.security.PlatformAuthenticationException;
import com.aiexam.common.security.PlatformPrincipal;
import com.aiexam.question.practice.attempt.application.PracticeCurrentUserProvider;
import com.aiexam.question.practice.attempt.application.PracticePrincipal;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PlatformPracticeCurrentUserProvider implements PracticeCurrentUserProvider {
  private final CurrentUserProvider currentUserProvider;

  public PlatformPracticeCurrentUserProvider(CurrentUserProvider currentUserProvider) {
    this.currentUserProvider = currentUserProvider;
  }

  @Override
  public PracticePrincipal requireCurrentUser() {
    PlatformPrincipal principal = currentUserProvider.currentPrincipal()
        .orElseThrow(PlatformAuthenticationException::new);
    return new PracticePrincipal(principal.userId(), principal.username(), Set.copyOf(principal.permissions()));
  }
}
