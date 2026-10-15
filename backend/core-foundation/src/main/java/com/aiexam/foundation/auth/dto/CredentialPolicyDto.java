package com.aiexam.foundation.auth.dto;

public record CredentialPolicyDto(
    RangeDto nickname,
    RangeDto password
) {
  public record RangeDto(
      int minLength,
      int maxLength
  ) {
  }
}
