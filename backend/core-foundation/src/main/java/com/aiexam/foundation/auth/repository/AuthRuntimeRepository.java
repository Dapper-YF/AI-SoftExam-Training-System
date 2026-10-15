package com.aiexam.foundation.auth.repository;

import com.aiexam.common.security.PlatformPrincipal;
import com.aiexam.foundation.auth.domain.AuthClientEvidence;
import com.aiexam.foundation.auth.domain.IssuedOpaqueTokens;
import com.aiexam.foundation.auth.domain.IssuedChallenge;
import com.aiexam.foundation.auth.domain.LoginUserSnapshot;
import com.aiexam.foundation.auth.domain.PasswordResetAccountContext;
import com.aiexam.foundation.auth.dto.AuthContext;
import com.aiexam.foundation.auth.dto.ChallengeDto;
import com.aiexam.foundation.auth.dto.CommittedAuthSession;
import com.aiexam.foundation.auth.dto.RegistrationResultDto;
import com.aiexam.foundation.auth.dto.RotatedAuthSession;
import com.aiexam.foundation.auth.dto.SessionExpiryDto;
import com.aiexam.foundation.auth.dto.request.PasswordResetRequest;
import com.aiexam.foundation.auth.dto.request.RegisterRequest;
import com.aiexam.foundation.auth.dto.request.SmsLoginRequest;
import java.time.Instant;
import java.util.Optional;

public interface AuthRuntimeRepository {
  boolean isHealthy();

  IssuedChallenge createRegistrationChallenge(
      String normalizedMobile,
      String requestId,
      String codeHash,
      Instant requestNow);

  RegistrationResultDto registerStudent(
      RegisterRequest request,
      String normalizedNickname,
      String displayName,
      String normalizedMobile,
      String normalizedEmail,
      String passwordHash,
      Instant requestNow,
      boolean verificationRequired,
      boolean adminRegistration);

  Optional<LoginUserSnapshot> lockUserForLogin(String identifierType, String normalizedIdentifier, Instant requestNow);

  void recordLoginFailure(String identifierType, String normalizedIdentifier, Instant requestNow);

  CommittedAuthSession createLoginSessionUserFirst(
      LoginUserSnapshot user,
      IssuedOpaqueTokens tokens,
      AuthClientEvidence clientEvidence,
      Instant requestNow);

  default IssuedChallenge createSmsLoginChallenge(
      String normalizedMobile,
      String requestId,
      String codeHash,
      Instant requestNow) {
    throw new UnsupportedOperationException("SMS login is not configured");
  }

  default Optional<LoginUserSnapshot> consumeSmsLoginChallenge(
      String normalizedMobile,
      SmsLoginRequest request,
      String verificationCodeHash,
      Instant requestNow) {
    return Optional.empty();
  }

  Optional<AuthContext> findAuthContext(PlatformPrincipal principal, Instant requestNow);

  Optional<PlatformPrincipal> findPrincipalByAccessTokenHash(String accessTokenHash, Instant requestNow);

  RotatedAuthSession rotateRefresh(String refreshTokenHash, IssuedOpaqueTokens nextTokens, Instant requestNow);

  void logout(PlatformPrincipal principal, String refreshTokenHash, Instant requestNow);

  IssuedChallenge createPasswordResetChallenge(
      String identifierType,
      String normalizedIdentifier,
      String requestId,
      String codeHash,
      Instant requestNow);

  Optional<PasswordResetAccountContext> findPasswordResetAccountContext(
      String identifierType,
      String normalizedIdentifier,
      PasswordResetRequest request,
      String verificationCodeHash,
      Instant requestNow);

  void resetPassword(
      String identifierType,
      String normalizedIdentifier,
      PasswordResetRequest request,
      String passwordHash,
      Instant requestNow);
}
