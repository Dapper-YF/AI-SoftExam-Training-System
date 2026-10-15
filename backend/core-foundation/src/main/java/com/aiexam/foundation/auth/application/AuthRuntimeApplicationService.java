package com.aiexam.foundation.auth.application;

import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ErrorCode;
import com.aiexam.common.security.AuthenticatedPlatformSession;
import com.aiexam.common.security.PlatformPrincipal;
import com.aiexam.common.security.PlatformSessionAuthenticator;
import com.aiexam.foundation.auth.domain.Argon2idPasswordPolicy;
import com.aiexam.foundation.auth.domain.AuthClientEvidence;
import com.aiexam.foundation.auth.domain.IssuedOpaqueTokens;
import com.aiexam.foundation.auth.domain.IssuedChallenge;
import com.aiexam.foundation.auth.domain.LoginIdentifier;
import com.aiexam.foundation.auth.domain.LoginIdentifierNormalizer;
import com.aiexam.foundation.auth.domain.LoginUserSnapshot;
import com.aiexam.foundation.auth.domain.MobileNormalizer;
import com.aiexam.foundation.auth.domain.PasswordResetAccountContext;
import com.aiexam.foundation.auth.domain.PasswordPolicy;
import com.aiexam.foundation.auth.domain.UsernameNormalizer;
import com.aiexam.foundation.auth.dto.AuthContext;
import com.aiexam.foundation.auth.dto.AuthCapabilitiesDto;
import com.aiexam.foundation.auth.dto.AuthProviderCapabilityDto;
import com.aiexam.foundation.auth.dto.ChallengeDto;
import com.aiexam.foundation.auth.dto.CommittedAuthSession;
import com.aiexam.foundation.auth.dto.CredentialPolicyDto;
import com.aiexam.foundation.auth.dto.RegistrationResultDto;
import com.aiexam.foundation.auth.dto.RotatedAuthSession;
import com.aiexam.foundation.auth.dto.SessionExpiryDto;
import com.aiexam.foundation.auth.dto.request.LoginRequest;
import com.aiexam.foundation.auth.dto.request.PasswordResetChallengeRequest;
import com.aiexam.foundation.auth.dto.request.PasswordResetRequest;
import com.aiexam.foundation.auth.dto.request.RegisterRequest;
import com.aiexam.foundation.auth.dto.request.RegistrationChallengeRequest;
import com.aiexam.foundation.auth.dto.request.SmsLoginChallengeRequest;
import com.aiexam.foundation.auth.dto.request.SmsLoginRequest;
import com.aiexam.foundation.auth.repository.AuthRuntimeRepository;
import com.aiexam.foundation.auth.repository.ChallengeDeliveryPort;
import com.aiexam.foundation.auth.repository.OpaqueTokenPort;
import com.aiexam.foundation.auth.repository.PasswordHashingPort;
import com.aiexam.foundation.auth.repository.TokenHashPort;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthRuntimeApplicationService implements AuthRuntimeUseCase, PlatformSessionAuthenticator {
  private static final Duration ACCESS_TTL = Duration.ofMinutes(30);
  private static final Duration REFRESH_TTL = Duration.ofDays(7);
  private static final String DUMMY_ARGON2ID_HASH =
      "$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHQxMjM0NTY3OA$wBq5VfEucGpZ3QmujRz0tg";

  private final AuthRuntimeRepository authRuntimeRepository;
  private final ChallengeDeliveryPort challengeDeliveryPort;
  private final PasswordHashingPort passwordHashingPort;
  private final OpaqueTokenPort opaqueTokenPort;
  private final TokenHashPort tokenHashPort;
  private final AuthRegistrationVerificationPolicy registrationVerificationPolicy;
  private final DevSeedUsernameLoginPolicy devSeedUsernameLoginPolicy;
  private final Clock clock;

  @Autowired
  public AuthRuntimeApplicationService(
      AuthRuntimeRepository authRuntimeRepository,
      ChallengeDeliveryPort challengeDeliveryPort,
      PasswordHashingPort passwordHashingPort,
      OpaqueTokenPort opaqueTokenPort,
      TokenHashPort tokenHashPort,
      AuthRegistrationVerificationPolicy registrationVerificationPolicy,
      DevSeedUsernameLoginPolicy devSeedUsernameLoginPolicy
  ) {
    this(authRuntimeRepository, challengeDeliveryPort, passwordHashingPort, opaqueTokenPort, tokenHashPort,
        registrationVerificationPolicy, devSeedUsernameLoginPolicy, Clock.systemUTC());
  }

  public AuthRuntimeApplicationService(
      AuthRuntimeRepository authRuntimeRepository,
      ChallengeDeliveryPort challengeDeliveryPort,
      PasswordHashingPort passwordHashingPort,
      OpaqueTokenPort opaqueTokenPort,
      TokenHashPort tokenHashPort,
      AuthRegistrationVerificationPolicy registrationVerificationPolicy,
      DevSeedUsernameLoginPolicy devSeedUsernameLoginPolicy,
      Clock clock
  ) {
    this.authRuntimeRepository = authRuntimeRepository;
    this.challengeDeliveryPort = challengeDeliveryPort;
    this.passwordHashingPort = passwordHashingPort;
    this.opaqueTokenPort = opaqueTokenPort;
    this.tokenHashPort = tokenHashPort;
    this.registrationVerificationPolicy = registrationVerificationPolicy;
    this.devSeedUsernameLoginPolicy = devSeedUsernameLoginPolicy;
    this.clock = clock;
  }

  @Override
  public AuthCapabilitiesDto capabilities() {
    AuthProviderCapabilityDto usernamePassword = authRuntimeRepository.isHealthy()
        ? AuthProviderCapabilityDto.available("IDENTIFIER_PASSWORD", "手机号或邮箱密码登录可用")
        : AuthProviderCapabilityDto.unavailable("IDENTIFIER_PASSWORD", "认证存储不可用");
    return new AuthCapabilitiesDto(
        usernamePassword,
        challengeDeliveryPort.mobileCapability(),
        challengeDeliveryPort.emailCapability(),
        challengeDeliveryPort.wechatCapability(),
        registrationVerificationPolicy.registrationVerificationRequired(),
        new CredentialPolicyDto(
            new CredentialPolicyDto.RangeDto(
                UsernameNormalizer.minLength(),
                UsernameNormalizer.maxLength()),
            new CredentialPolicyDto.RangeDto(
                PasswordPolicy.minLength(),
                PasswordPolicy.maxLength())),
        devSeedUsernameLoginPolicy.adminRegistrationEnabled());
  }

  @Override
  @Transactional
  public ChallengeDto createRegistrationChallenge(RegistrationChallengeRequest request) {
    String mobile = MobileNormalizer.normalizeMainlandChina(request.mobile());
    String code = newVerificationCode();
    IssuedChallenge issued = authRuntimeRepository.createRegistrationChallenge(
        mobile,
        request.requestId().toString(),
        tokenHashPort.hash(code),
        now());
    deliverIfNeeded("REGISTER", issued, code);
    return issued.challenge();
  }

  @Override
  @Transactional
  public RegistrationResultDto register(RegisterRequest request) {
    boolean adminRegistration = "ADMIN".equalsIgnoreCase(request.role());
    if (request.role() != null && !request.role().isBlank() && !adminRegistration) {
      throw new BusinessException(
          ErrorCode.AUTH_ROLE_FIELD_FORBIDDEN,
          "注册请求不得提交角色",
          HttpStatus.BAD_REQUEST);
    }
    if (adminRegistration && !devSeedUsernameLoginPolicy.adminRegistrationEnabled()) {
      throw new BusinessException(
          ErrorCode.AUTH_ROLE_FIELD_FORBIDDEN,
          "绠＄悊鍛樻敞鍐屽姛鑳芥湭鍦ㄥ綋鍓嶇幆澧冨紑鍚?",
          HttpStatus.FORBIDDEN);
    }
    String nickname = UsernameNormalizer.normalize(request.nickname());
    String displayName = request.displayName() == null || request.displayName().isBlank()
        ? nickname
        : UsernameNormalizer.normalize(request.displayName());
    String mobile = MobileNormalizer.normalizeMainlandChina(request.phone());
    String email = request.email() == null || request.email().isBlank()
        ? null
        : LoginIdentifierNormalizer.normalizeEmail(request.email());
    boolean verificationRequired = registrationVerificationPolicy.registrationVerificationRequired();
    if (verificationRequired && (request.challengeId() == null
        || request.verificationCode() == null
        || request.verificationCode().isBlank())) {
      throw new BusinessException(
          ErrorCode.AUTH_CHALLENGE_INVALID,
          "验证码无效",
          HttpStatus.BAD_REQUEST);
    }
    String normalizedPassword = PasswordPolicy.validateAndNormalize(request.password(), nickname, mobile, email == null ? "" : email);
    String passwordHash = passwordHashingPort.hash(normalizedPassword, Argon2idPasswordPolicy.frozenP0());
    return authRuntimeRepository.registerStudent(request, nickname, displayName, mobile, email, passwordHash, now(), verificationRequired, adminRegistration);
  }

  @Override
  @Transactional
  public CommittedAuthSession login(LoginRequest request, AuthClientEvidence clientEvidence) {
    Instant requestNow = now();
    LoginIdentifier identifier = LoginIdentifierNormalizer.normalize(
        request.identifier(),
        devSeedUsernameLoginPolicy.devSeedUsernameLoginEnabled());
    String normalizedPassword = PasswordPolicy.normalizeForAuthentication(request.password());
    Optional<LoginUserSnapshot> maybeUser = authRuntimeRepository.lockUserForLogin(
        identifier.persistenceType(),
        identifier.normalizedValue(),
        requestNow);
    if (maybeUser.isEmpty()) {
      passwordHashingPort.verify(normalizedPassword, DUMMY_ARGON2ID_HASH);
      throw invalidCredentials();
    }
    LoginUserSnapshot user = maybeUser.get();
    if (!user.enabled()) {
      passwordHashingPort.verify(normalizedPassword, DUMMY_ARGON2ID_HASH);
      throw accountDisabled();
    }
    if (user.lockedAt(requestNow)) {
      passwordHashingPort.verify(normalizedPassword, DUMMY_ARGON2ID_HASH);
      throw accountLocked();
    }
    if (!passwordHashingPort.verify(normalizedPassword, user.passwordHash())) {
      authRuntimeRepository.recordLoginFailure(identifier.persistenceType(), identifier.normalizedValue(), requestNow);
      throw invalidCredentials();
    }
    return authRuntimeRepository.createLoginSessionUserFirst(user, issueTokens(requestNow), clientEvidence, requestNow);
  }

  @Override
  @Transactional
  public ChallengeDto createSmsLoginChallenge(SmsLoginChallengeRequest request) {
    if (!challengeDeliveryPort.mobileCapability().available()) {
      throw new BusinessException(
          ErrorCode.AUTH_PROVIDER_UNAVAILABLE,
          "短信认证服务暂不可用",
          HttpStatus.SERVICE_UNAVAILABLE);
    }
    String mobile = MobileNormalizer.normalizeMainlandChina(request.mobile());
    String code = newVerificationCode();
    IssuedChallenge issued = authRuntimeRepository.createSmsLoginChallenge(
        mobile,
        request.requestId().toString(),
        tokenHashPort.hash(code),
        now());
    deliverIfNeeded("SMS_LOGIN", issued, code);
    return issued.challenge();
  }

  @Override
  @Transactional(noRollbackFor = BusinessException.class)
  public CommittedAuthSession smsLogin(SmsLoginRequest request, AuthClientEvidence clientEvidence) {
    Instant requestNow = now();
    String mobile = MobileNormalizer.normalizeMainlandChina(request.mobile());
    LoginUserSnapshot user = authRuntimeRepository.consumeSmsLoginChallenge(
            mobile,
            request,
            tokenHashPort.hash(request.verificationCode()),
            requestNow)
        .orElseThrow(this::challengeInvalid);
    return authRuntimeRepository.createLoginSessionUserFirst(
        user,
        issueTokens(requestNow),
        clientEvidence,
        requestNow);
  }

  @Override
  public AuthContext me(PlatformPrincipal principal) {
    return authRuntimeRepository.findAuthContext(principal, now())
        .orElseThrow(this::sessionExpired);
  }

  @Override
  @Transactional(noRollbackFor = BusinessException.class)
  public RotatedAuthSession refresh(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw sessionExpired();
    }
    Instant requestNow = now();
    return authRuntimeRepository.rotateRefresh(tokenHashPort.hash(refreshToken), issueTokens(requestNow), requestNow);
  }

  @Override
  @Transactional
  public void logout(PlatformPrincipal principal, String refreshToken) {
    String refreshHash = refreshToken == null || refreshToken.isBlank() ? null : tokenHashPort.hash(refreshToken);
    authRuntimeRepository.logout(principal, refreshHash, now());
  }

  @Override
  @Transactional
  public ChallengeDto createPasswordResetChallenge(PasswordResetChallengeRequest request) {
    LoginIdentifier identifier = LoginIdentifierNormalizer.normalize(request.identifier());
    ensureResetProviderAvailable(identifier);
    String code = newVerificationCode();
    IssuedChallenge issued = authRuntimeRepository.createPasswordResetChallenge(
        identifier.persistenceType(),
        identifier.normalizedValue(),
        request.requestId().toString(),
        tokenHashPort.hash(code),
        now());
    deliverIfNeeded("PASSWORD_RESET", issued, code);
    return issued.challenge();
  }

  @Override
  @Transactional
  public void resetPassword(PasswordResetRequest request) {
    LoginIdentifier identifier = LoginIdentifierNormalizer.normalize(request.identifier());
    PasswordResetAccountContext accountContext = authRuntimeRepository.findPasswordResetAccountContext(
            identifier.persistenceType(),
            identifier.normalizedValue(),
            request,
            tokenHashPort.hash(request.verificationCode()),
            now())
        .orElseThrow(() -> challengeInvalid());
    String password = PasswordPolicy.validateAndNormalize(
        request.newPassword(),
        accountContext.nickname(),
        accountContext.normalizedMobile(),
        accountContext.normalizedEmail());
    String passwordHash = passwordHashingPort.hash(password, Argon2idPasswordPolicy.frozenP0());
    authRuntimeRepository.resetPassword(
        identifier.persistenceType(),
        identifier.normalizedValue(),
        request,
        passwordHash,
        now());
  }

  @Override
  public Optional<AuthenticatedPlatformSession> authenticateAccessToken(String opaqueAccessToken, Instant requestNow) {
    return authRuntimeRepository.findPrincipalByAccessTokenHash(tokenHashPort.hash(opaqueAccessToken), requestNow)
        .map(principal -> new AuthenticatedPlatformSession(
            principal,
            requestNow.plus(ACCESS_TTL),
            requestNow.plus(REFRESH_TTL)));
  }

  private IssuedOpaqueTokens issueTokens(Instant requestNow) {
    String access = opaqueTokenPort.newToken();
    String refresh = opaqueTokenPort.newToken();
    return new IssuedOpaqueTokens(
        access,
        tokenHashPort.hash(access),
        requestNow.plus(ACCESS_TTL),
        refresh,
        tokenHashPort.hash(refresh),
        requestNow.plus(REFRESH_TTL));
  }

  private Instant now() {
    return clock.instant();
  }

  private void deliverIfNeeded(String purpose, IssuedChallenge issued, String code) {
    if (!issued.deliverable()) {
      return;
    }
    if ("EMAIL".equals(issued.deliveryChannel())) {
      AuthProviderCapabilityDto email = challengeDeliveryPort.emailCapability();
      if (!email.available()) {
        throw new BusinessException(
            ErrorCode.AUTH_PROVIDER_UNAVAILABLE,
            "认证服务暂不可用",
            HttpStatus.SERVICE_UNAVAILABLE);
      }
      challengeDeliveryPort.deliverEmailCode(
          purpose,
          issued.challenge().challengeId(),
          issued.deliveryMobile(),
          code);
      return;
    }
    AuthProviderCapabilityDto mobile = challengeDeliveryPort.mobileCapability();
    if (!mobile.available()) {
      throw new BusinessException(
          ErrorCode.AUTH_PROVIDER_UNAVAILABLE,
          "认证服务暂不可用",
          HttpStatus.SERVICE_UNAVAILABLE);
    }
    challengeDeliveryPort.deliverMobileCode(purpose, issued.challenge().challengeId(), issued.deliveryMobile(), code);
  }

  private void ensureResetProviderAvailable(LoginIdentifier identifier) {
    AuthProviderCapabilityDto capability = identifier.kind() == LoginIdentifier.Kind.EMAIL
        ? challengeDeliveryPort.emailCapability()
        : challengeDeliveryPort.mobileCapability();
    if (!capability.available()) {
      throw new BusinessException(
          ErrorCode.AUTH_PROVIDER_UNAVAILABLE,
          "认证服务暂不可用",
          HttpStatus.SERVICE_UNAVAILABLE);
    }
  }

  private String newVerificationCode() {
    int value = SecureRandomHolder.INSTANCE.nextInt(1_000_000);
    return "%06d".formatted(value);
  }

  private static final class SecureRandomHolder {
    private static final SecureRandom INSTANCE = new SecureRandom();
  }

  private BusinessException invalidCredentials() {
    return new BusinessException(
        ErrorCode.AUTH_INVALID_CREDENTIALS,
        "手机号或邮箱、密码错误",
        HttpStatus.UNAUTHORIZED);
  }

  private BusinessException accountDisabled() {
    return new BusinessException(
        ErrorCode.AUTH_ACCOUNT_DISABLED,
        "账号已停用",
        HttpStatus.FORBIDDEN);
  }

  private BusinessException accountLocked() {
    return new BusinessException(
        ErrorCode.AUTH_ACCOUNT_LOCKED,
        "账号已锁定",
        HttpStatus.LOCKED);
  }

  private BusinessException sessionExpired() {
    return new BusinessException(
        ErrorCode.AUTH_SESSION_EXPIRED,
        "会话已过期",
        HttpStatus.UNAUTHORIZED);
  }

  private BusinessException challengeInvalid() {
    return new BusinessException(
        ErrorCode.AUTH_CHALLENGE_INVALID,
        "验证码无效",
        HttpStatus.BAD_REQUEST);
  }
}
