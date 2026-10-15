package com.aiexam.essay.runtime;

import java.util.Set;

public record EssayCurrentUser(Long userId, Set<String> permissions) {
  public EssayCurrentUser(Long userId) {
    this(userId, Set.of());
  }

  public EssayCurrentUser {
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
  }
}
