package com.aiexam.foundation.auth.infra;

import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ErrorCode;
import com.aiexam.common.security.PlatformPrincipal;
import com.aiexam.foundation.auth.domain.AuthClientEvidence;
import com.aiexam.foundation.auth.domain.IssuedOpaqueTokens;
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
import com.aiexam.foundation.auth.repository.AuthRuntimeRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "aiexam.auth", name = "persistence", havingValue = "unavailable")
public class UnavailableAuthRuntimeRepository implements AuthRuntimeRepository {
  @Override
  public boolean isHealthy() {
    return false;
  }

  @Override
  public com.aiexam.foundation.auth.domain.IssuedChallenge createRegistrationChallenge(
      String normalizedMobile,
      String requestId,
      String codeHash,
      Instant requestNow
  ) {
    throw unavailable();
  }

  @Override
  public RegistrationResultDto registerStudent(
      RegisterRequest request,
      String normalizedNickname,
      String displayName,
      String normalizedMobile,
      String normalizedEmail,
      String passwordHash,
      Instant requestNow,
      boolean verificationRequired,
      boolean adminRegistration
  ) {
    throw unavailable();
  }

  @Override
  public Optional<LoginUserSnapshot> lockUserForLogin(String identifierType, String normalizedIdentifier, Instant requestNow) {
    return Optional.empty();
  }

  @Override
  public void recordLoginFailure(String identifierType, String normalizedIdentifier, Instant requestNow) {
  }

  @Override
  public CommittedAuthSession createLoginSessionUserFirst(
      LoginUserSnapshot user,
      IssuedOpaqueTokens tokens,
      AuthClientEvidence clientEvidence,
      Instant requestNow
  ) {
    throw unavailable();
  }

  @Override
  public Optional<AuthContext> findAuthContext(PlatformPrincipal principal, Instant requestNow) {
    return Optional.empty();
  }

  @Override
  public Optional<PlatformPrincipal> findPrincipalByAccessTokenHash(String accessTokenHash, Instant requestNow) {
    return Optional.empty();
  }

  @Override
  public RotatedAuthSession rotateRefresh(String refreshTokenHash, IssuedOpaqueTokens nextTokens, Instant requestNow) {
    throw unavailable();
  }

  @Override
  public void logout(PlatformPrincipal principal, String refreshTokenHash, Instant requestNow) {
  }

  @Override
  public com.aiexam.foundation.auth.domain.IssuedChallenge createSmsLoginChallenge(
      String normalizedMobile,
      String requestId,
      String codeHash,
      Instant requestNow
  ) {
    throw unavailable();
  }

  @Override
  public Optional<LoginUserSnapshot> consumeSmsLoginChallenge(
      String normalizedMobile,
      SmsLoginRequest request,
      String verificationCodeHash,
      Instant requestNow
  ) {
    return Optional.empty();
  }

  @Override
  public com.aiexam.foundation.auth.domain.IssuedChallenge createPasswordResetChallenge(
      String identifierType,
      String normalizedIdentifier,
      String requestId,
      String codeHash,
      Instant requestNow
  ) {
    throw unavailable();
  }

  @Override
  public Optional<PasswordResetAccountContext> findPasswordResetAccountContext(
      String identifierType,
      String normalizedIdentifier,
      PasswordResetRequest request,
      String verificationCodeHash,
      Instant requestNow
  ) {
    return Optional.empty();
  }

  @Override
  public void resetPassword(
      String identifierType,
      String normalizedIdentifier,
      PasswordResetRequest request,
      String passwordHash,
      Instant requestNow
  ) {
    throw unavailable();
  }

  private BusinessException unavailable() {
    return new BusinessException(
        ErrorCode.AUTH_WRITE_BUSY,
        "认证V10持久化适配器尚未接入",
        HttpStatus.SERVICE_UNAVAILABLE);
  }
}
