package com.aiexam.question.admin.application;

import com.aiexam.common.error.BusinessException;
import com.aiexam.common.security.CurrentUserProvider;
import com.aiexam.common.security.PlatformAuthenticationException;
import com.aiexam.common.security.PlatformPrincipal;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Bridges question administration to the authenticated platform principal. */
@Component
public class QuestionAdminCurrentUser {
  private final CurrentUserProvider currentUserProvider;

  public QuestionAdminCurrentUser(CurrentUserProvider currentUserProvider) {
    this.currentUserProvider = currentUserProvider;
  }

  public PlatformPrincipal requirePrincipal() {
    return currentUserProvider.currentPrincipal().orElseThrow(PlatformAuthenticationException::new);
  }

  public PlatformPrincipal require(String permission) {
    PlatformPrincipal principal = requirePrincipal();
    if (principal.permissions().stream().anyMatch(permission::equalsIgnoreCase)) {
      return principal;
    }
    throw new BusinessException("QUESTION_ADMIN_FORBIDDEN",
        "The current principal is not allowed to perform this question operation",
        HttpStatus.FORBIDDEN);
  }

  public boolean isAdmin(PlatformPrincipal principal) {
    return principal.roles().stream()
        .map(role -> role == null ? "" : role.trim().toUpperCase(Locale.ROOT))
        .anyMatch("ADMIN"::equals);
  }
}
