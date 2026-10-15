package com.aiexam.question.practice.attempt.application;

import java.util.Set;

public record PracticePrincipal(Long userId, String username, Set<String> permissions) {
  public PracticePrincipal {
    if (userId == null || userId <= 0) {
      throw new IllegalArgumentException("userId is required");
    }
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
  }
}
