package com.aiexam.foundation.auth.repository;

import java.util.Optional;
import java.util.UUID;

public interface DevChallengeCodeLookupPort {
  Optional<String> findCode(UUID challengeId);
}
