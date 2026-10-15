package com.aiexam.foundation.auth.domain;

public record Argon2idPasswordPolicy(
    int memoryKiB,
    int iterations,
    int parallelism,
    int saltBytes
) {
  public static Argon2idPasswordPolicy frozenP0() {
    return new Argon2idPasswordPolicy(19_456, 2, 1, 16);
  }
}
