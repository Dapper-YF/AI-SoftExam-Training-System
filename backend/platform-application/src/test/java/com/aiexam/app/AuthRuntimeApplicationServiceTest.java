package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ErrorCode;
import com.aiexam.common.security.PlatformPrincipal;
import com.aiexam.foundation.auth.application.DevSeedUsernameLoginPolicy;
import com.aiexam.foundation.auth.application.AuthRegistrationVerificationPolicy;
import com.aiexam.foundation.auth.application.AuthRuntimeApplicationService;
import com.aiexam.foundation.auth.domain.Argon2idPasswordPolicy;
import com.aiexam.foundation.auth.domain.AuthClientEvidence;
import com.aiexam.foundation.auth.domain.IssuedChallenge;
import com.aiexam.foundation.auth.domain.IssuedOpaqueTokens;
import com.aiexam.foundation.auth.domain.LoginIdentifierNormalizer;
import com.aiexam.foundation.auth.domain.LoginUserSnapshot;
import com.aiexam.foundation.auth.domain.PasswordResetAccountContext;
import com.aiexam.foundation.auth.domain.PasswordPolicy;
import com.aiexam.foundation.auth.dto.AuthProviderCapabilityDto;
import com.aiexam.foundation.auth.dto.AuthContext;
import com.aiexam.foundation.auth.dto.AuthContext.AuthMenuDto;
import com.aiexam.foundation.auth.dto.AuthContext.AuthUserDto;
import com.aiexam.foundation.auth.dto.ChallengeDto;
import com.aiexam.foundation.auth.dto.CommittedAuthSession;
import com.aiexam.foundation.auth.dto.RegistrationResultDto;
import com.aiexam.foundation.auth.dto.RotatedAuthSession;
import com.aiexam.foundation.auth.dto.SessionExpiryDto;
import com.aiexam.foundation.auth.dto.request.LoginRequest;
import com.aiexam.foundation.auth.dto.request.PasswordResetChallengeRequest;
import com.aiexam.foundation.auth.dto.request.PasswordResetRequest;
import com.aiexam.foundation.auth.dto.request.RegisterRequest;
import com.aiexam.foundation.auth.dto.request.SmsLoginChallengeRequest;
import com.aiexam.foundation.auth.dto.request.SmsLoginRequest;
import com.aiexam.foundation.auth.repository.AuthRuntimeRepository;
import com.aiexam.foundation.auth.repository.ChallengeDeliveryPort;
import com.aiexam.foundation.auth.repository.OpaqueTokenPort;
import com.aiexam.foundation.auth.repository.PasswordHashingPort;
import com.aiexam.foundation.auth.repository.TokenHashPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AuthRuntimeApplicationServiceTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-15T02:00:00Z"), ZoneOffset.UTC);
  private static final String STRONG_PASSWORD = "CorrectHorseNFC1";
  private static final String WRONG_PASSWORD = "IncorrectHorseNFC1";

  @Test
  void registerCreatesStudentButDoesNotCreateSession() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    AuthRuntimeApplicationService service = service(repository, new FakePasswordHashingPort());
    RegisterRequest request = registerRequest(null);

    RegistrationResultDto first = service.register(request);
    RegistrationResultDto replay = service.register(request);

    assertThat(first).isEqualTo(replay);
    assertThat(first.registrationStatus()).isEqualTo("REGISTERED");
    assertThat(repository.registerCalls).isEqualTo(2);
    assertThat(repository.sessionCreations).isZero();
    assertThat(repository.registeredPasswordHash).isEqualTo("argon2id:" + STRONG_PASSWORD);
  }

  @Test
  void registerRejectsRoleFieldBeforeCreatingUser() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    AuthRuntimeApplicationService service = service(repository, new FakePasswordHashingPort());

    assertThatThrownBy(() -> service.register(registerRequest("ADMIN")))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_ROLE_FIELD_FORBIDDEN));
    assertThat(repository.registerCalls).isZero();
    assertThat(repository.sessionCreations).isZero();
  }

  @Test
  void registerTreatsUsernameAsUnicodeNicknameAndRejectsOuterWhitespace() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    AuthRuntimeApplicationService service = service(repository, new FakePasswordHashingPort());

    service.register(new RegisterRequest(
        "张三 Cafe\u0301",
        STRONG_PASSWORD,
        "Student One",
        "13800138000",
        null,
        UUID.fromString("018fa7d9-0000-7000-8000-000000000001"),
        "123456",
        UUID.fromString("018fa7d9-0000-7000-8000-000000000002"),
        null));

    assertThat(repository.registrationResult.username()).isEqualTo("张三 Café");
    assertThatThrownBy(() -> service.register(new RegisterRequest(
        " Student One",
        STRONG_PASSWORD,
        "Student One",
        "13800138001",
        null,
        UUID.fromString("018fa7d9-0000-7000-8000-000000000003"),
        "123456",
        UUID.fromString("018fa7d9-0000-7000-8000-000000000004"),
        null)))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_REQUEST_INVALID));
  }

  @Test
  void devRegistrationCanCreateStudentWithoutChallengeWhenExplicitlyDisabled() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    AuthRuntimeApplicationService service = new AuthRuntimeApplicationService(
        repository,
        new FakeChallengeDeliveryPort(false),
        new FakePasswordHashingPort(),
        new DeterministicOpaqueTokenPort(),
        opaqueToken -> "sha256:" + opaqueToken,
        verificationPolicy(false, "dev"),
        devSeedUsernameLoginPolicy(false, "dev"),
        FIXED_CLOCK);

    RegistrationResultDto result = service.register(new RegisterRequest(
        "Student_01",
        STRONG_PASSWORD,
        "Student One",
        "13800138000",
        null,
        null,
        null,
        UUID.fromString("018fa7d9-0000-7000-8000-000000000002"),
        null));

    assertThat(result.registrationStatus()).isEqualTo("REGISTERED");
    assertThat(repository.lastVerificationRequired).isFalse();
    assertThat(repository.sessionCreations).isZero();
  }

  @Test
  void productionFailsFastWhenRegistrationVerificationIsDisabled() {
    AuthRegistrationVerificationPolicy policy = verificationPolicy(false, "production");

    assertThatThrownBy(policy::failFastWhenProductionBypassIsEnabled)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_REGISTRATION_VERIFICATION_BYPASS_NOT_ALLOWED");
  }

  @Test
  void registrationVerificationDefaultsToRequiredWhenMissingOrTrue() {
    AuthRegistrationVerificationPolicy missing = verificationPolicyWithoutProperty();
    AuthRegistrationVerificationPolicy explicitTrue = verificationPolicy(true);

    missing.failFastWhenProductionBypassIsEnabled();
    explicitTrue.failFastWhenProductionBypassIsEnabled();

    assertThat(missing.registrationVerificationRequired()).isTrue();
    assertThat(explicitTrue.registrationVerificationRequired()).isTrue();
  }

  @Test
  void registrationVerificationDefaultProfileFalseFailsFast() {
    AuthRegistrationVerificationPolicy policy = verificationPolicy(false);

    assertThatThrownBy(policy::failFastWhenProductionBypassIsEnabled)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_REGISTRATION_VERIFICATION_BYPASS_NOT_ALLOWED");
  }

  @Test
  void registrationVerificationMysqlOnlyFalseFailsFast() {
    AuthRegistrationVerificationPolicy policy = verificationPolicy(false, "mysql");

    assertThatThrownBy(policy::failFastWhenProductionBypassIsEnabled)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_REGISTRATION_VERIFICATION_BYPASS_NOT_ALLOWED");
  }

  @Test
  void registrationVerificationUnknownProfileFalseFailsFast() {
    AuthRegistrationVerificationPolicy policy = verificationPolicy(false, "staging");
    AuthRegistrationVerificationPolicy blank = verificationPolicyWithRawProfiles(false, " ");
    AuthRegistrationVerificationPolicy nullProfile = verificationPolicyWithRawProfiles(false, (String) null);

    assertThatThrownBy(policy::failFastWhenProductionBypassIsEnabled)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_REGISTRATION_VERIFICATION_BYPASS_NOT_ALLOWED");
    assertThatThrownBy(blank::failFastWhenProductionBypassIsEnabled)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_REGISTRATION_VERIFICATION_BYPASS_NOT_ALLOWED");
    assertThatThrownBy(nullProfile::failFastWhenProductionBypassIsEnabled)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_REGISTRATION_VERIFICATION_BYPASS_NOT_ALLOWED");
  }

  @Test
  void registrationVerificationProductionProfilesOverrideAllowedDevProfiles() {
    AuthRegistrationVerificationPolicy devProduction = verificationPolicy(false, "dev", " production ");
    AuthRegistrationVerificationPolicy localDevProd = verificationPolicy(false, "local-dev", " prod ");

    assertThatThrownBy(devProduction::failFastWhenProductionBypassIsEnabled)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_REGISTRATION_VERIFICATION_BYPASS_NOT_ALLOWED");
    assertThatThrownBy(localDevProd::failFastWhenProductionBypassIsEnabled)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_REGISTRATION_VERIFICATION_BYPASS_NOT_ALLOWED");
  }

  @Test
  void registrationVerificationDevAndTestFalseAreAllowedOnlyWhenExplicit() {
    AuthRegistrationVerificationPolicy dev = verificationPolicy(false, " dev ", "mysql");
    AuthRegistrationVerificationPolicy test = verificationPolicy(false, "test");
    AuthRegistrationVerificationPolicy localDev = verificationPolicy(false, "local-dev");

    dev.failFastWhenProductionBypassIsEnabled();
    test.failFastWhenProductionBypassIsEnabled();
    localDev.failFastWhenProductionBypassIsEnabled();

    assertThat(dev.registrationVerificationRequired()).isFalse();
    assertThat(test.registrationVerificationRequired()).isFalse();
    assertThat(localDev.registrationVerificationRequired()).isFalse();
  }

  @Test
  void registrationVerificationDevWithoutExplicitFalseStillRequiresVerification() {
    AuthRegistrationVerificationPolicy policy = verificationPolicyWithoutProperty("dev", "mysql");

    policy.failFastWhenProductionBypassIsEnabled();

    assertThat(policy.registrationVerificationRequired()).isTrue();
  }

  @Test
  void productionRegistrationRejectsMissingChallengeWhenVerificationIsRequired() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    AuthRuntimeApplicationService service = new AuthRuntimeApplicationService(
        repository,
        new FakeChallengeDeliveryPort(true),
        new FakePasswordHashingPort(),
        new DeterministicOpaqueTokenPort(),
        opaqueToken -> "sha256:" + opaqueToken,
        verificationPolicy(true, "production"),
        devSeedUsernameLoginPolicy(false, "production"),
        FIXED_CLOCK);

    assertThatThrownBy(() -> service.register(new RegisterRequest(
        "Student_01",
        STRONG_PASSWORD,
        "Student One",
        "13800138000",
        null,
        null,
        null,
        UUID.fromString("018fa7d9-0000-7000-8000-000000000002"),
        null)))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_CHALLENGE_INVALID);
          assertThat(ex.getMessage()).isEqualTo("验证码无效");
        });
    assertThat(repository.registerCalls).isZero();
  }

  @Test
  void explicitLoginCreatesOpaqueSessionThroughUserFirstPort() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    AuthRuntimeApplicationService service = service(repository, new FakePasswordHashingPort());

    CommittedAuthSession session = service.login(
        new LoginRequest("13800138000", STRONG_PASSWORD),
        new AuthClientEvidence("ip-hash", "JUnit"));

    assertThat(repository.lockOrder).containsExactly("MOBILE:+8613800138000", "sessions");
    assertThat(repository.sessionCreations).isEqualTo(1);
    assertThat(repository.prunedFamilies).isEqualTo(1);
    assertThat(repository.activeFamiliesAfterLogin).isEqualTo(5);
    assertThat(session.accessToken()).isEqualTo("access-token-1");
    assertThat(session.refreshToken()).isEqualTo("refresh-token-1");
    assertThat(session.authContext().user().username()).isEqualTo("student_01");
  }

  @Test
  void invalidLoginUsesUniformCredentialErrorAndNoSession() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    AuthRuntimeApplicationService service = service(repository, new FakePasswordHashingPort(false));

    assertThatThrownBy(() -> service.login(
        new LoginRequest("13800138000", WRONG_PASSWORD),
        AuthClientEvidence.empty()))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS));
    assertThat(repository.failureRecords).isEqualTo(1);
    assertThat(repository.sessionCreations).isZero();
  }

  @Test
  void usernameIdentifierNoLongerFallsBackToLoginCredential() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    AuthRuntimeApplicationService service = service(repository, new FakePasswordHashingPort());

    assertThatThrownBy(() -> service.login(
        new LoginRequest("student_01", STRONG_PASSWORD),
        AuthClientEvidence.empty()))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_REQUEST_INVALID));
    assertThat(repository.lockOrder).isEmpty();
    assertThat(repository.sessionCreations).isZero();
  }

  @Test
  void devSeedUsernameLoginAllowsOnlyStudentAndAdminWhenExplicitlyEnabled() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    repository.loginUsers.put("DEV_SEED_USERNAME:student", new LoginUserSnapshot(
        1001L,
        "student",
        "统一测试学员",
        "+86 139****2001",
        "hash:" + STRONG_PASSWORD,
        "ENABLED",
        null,
        7));
    repository.loginUsers.put("DEV_SEED_USERNAME:admin", new LoginUserSnapshot(
        1002L,
        "admin",
        "题库验收提交人",
        "+86 139****2002",
        "hash:" + STRONG_PASSWORD,
        "ENABLED",
        null,
        11));
    AuthRuntimeApplicationService service = service(
        repository,
        new FakePasswordHashingPort(),
        devSeedUsernameLoginPolicy(true, "dev"));

    CommittedAuthSession student = service.login(new LoginRequest("student", STRONG_PASSWORD), AuthClientEvidence.empty());
    CommittedAuthSession admin = service.login(new LoginRequest("admin", STRONG_PASSWORD), AuthClientEvidence.empty());

    assertThat(repository.lockOrder).containsExactly(
        "DEV_SEED_USERNAME:student",
        "sessions",
        "DEV_SEED_USERNAME:admin",
        "sessions");
    assertThat(student.authContext().user().username()).isEqualTo("student");
    assertThat(student.authContext().roles()).containsExactly("STUDENT");
    assertThat(admin.authContext().user().username()).isEqualTo("admin");
    assertThat(admin.authContext().roles()).contains("ADMIN");
  }

  @Test
  void devSeedUsernameLoginRejectsWrongPasswordAndArbitraryUsernameSafely() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    repository.loginUsers.put("DEV_SEED_USERNAME:student", new LoginUserSnapshot(
        1001L,
        "student",
        "统一测试学员",
        "+86 139****2001",
        "hash:" + STRONG_PASSWORD,
        "ENABLED",
        null,
        7));
    AuthRuntimeApplicationService service = service(
        repository,
        new FakePasswordHashingPort(false),
        devSeedUsernameLoginPolicy(true, "test"));

    assertThatThrownBy(() -> service.login(new LoginRequest("student", WRONG_PASSWORD), AuthClientEvidence.empty()))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS));
    assertThatThrownBy(() -> service.login(new LoginRequest("teacher", STRONG_PASSWORD), AuthClientEvidence.empty()))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_REQUEST_INVALID));

    assertThat(repository.failureRecords).isEqualTo(1);
    assertThat(repository.sessionCreations).isZero();
  }

  @Test
  void devSeedUsernameLoginPolicyRequiresPropertyAndSafeProfile() {
    assertThat(devSeedUsernameLoginPolicy(false, "dev").devSeedUsernameLoginEnabled()).isFalse();
    assertThat(devSeedUsernameLoginPolicy(true, "dev").devSeedUsernameLoginEnabled()).isTrue();
    assertThat(devSeedUsernameLoginPolicy(true, "test").devSeedUsernameLoginEnabled()).isTrue();
    assertThat(devSeedUsernameLoginPolicy(true, "local-dev").devSeedUsernameLoginEnabled()).isTrue();
    assertThat(devSeedUsernameLoginPolicy(true, "dev", "mysql").devSeedUsernameLoginEnabled()).isTrue();

    assertThatThrownBy(() -> devSeedUsernameLoginPolicy(true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_DEV_SEED_USERNAME_LOGIN_NOT_ALLOWED");
    assertThatThrownBy(() -> devSeedUsernameLoginPolicy(true, "mysql"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_DEV_SEED_USERNAME_LOGIN_NOT_ALLOWED");
    assertThatThrownBy(() -> devSeedUsernameLoginPolicy(true, "qa"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_DEV_SEED_USERNAME_LOGIN_NOT_ALLOWED");
    assertThatThrownBy(() -> devSeedUsernameLoginPolicy(true, "dev", "production"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_DEV_SEED_USERNAME_LOGIN_NOT_ALLOWED");
  }

  @Test
  void phoneAndEmailLoginContinueToUseIdentifierTableWhenDevSeedUsernameLoginIsEnabled() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    AuthRuntimeApplicationService service = service(
        repository,
        new FakePasswordHashingPort(),
        devSeedUsernameLoginPolicy(true, "local-dev"));

    service.login(new LoginRequest("13800138000", STRONG_PASSWORD), AuthClientEvidence.empty());
    service.login(new LoginRequest("student@Example.COM", STRONG_PASSWORD), AuthClientEvidence.empty());

    assertThat(repository.lockOrder).containsExactly(
        "MOBILE:+8613800138000",
        "sessions",
        "EMAIL:student@example.com",
        "sessions");
  }

  @Test
  void emailIdentifierLogsInThroughCanonicalIdentifierTable() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    AuthRuntimeApplicationService service = service(repository, new FakePasswordHashingPort());

    CommittedAuthSession session = service.login(
        new LoginRequest("student@Example.COM", STRONG_PASSWORD),
        AuthClientEvidence.empty());

    assertThat(repository.lockOrder).containsExactly("EMAIL:student@example.com", "sessions");
    assertThat(repository.sessionCreations).isEqualTo(1);
    assertThat(session.authContext().user().username()).isEqualTo("student_01");
  }

  @Test
  void emailIdentifierNormalizationPreservesLocalPartAndRejectsUnsafeShapes() {
    assertThat(LoginIdentifierNormalizer.normalizeEmail(" Local.Part@Example.COM "))
        .isEqualTo("Local.Part@example.com");
    assertThatThrownBy(() -> LoginIdentifierNormalizer.normalizeEmail("local part@example.com"))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_REQUEST_INVALID));
    assertThatThrownBy(() -> LoginIdentifierNormalizer.normalizeEmail("local@bad-.com"))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_REQUEST_INVALID));
  }

  @Test
  void disabledAndLockedAccountsReturnSafeSpecificErrors() {
    FakeAuthRuntimeRepository disabledRepository = new FakeAuthRuntimeRepository();
    disabledRepository.loginStatus = "DISABLED";
    AuthRuntimeApplicationService disabledService = service(
        disabledRepository,
        new FakePasswordHashingPort());

    assertThatThrownBy(() -> disabledService.login(
        new LoginRequest("13800138000", STRONG_PASSWORD),
        AuthClientEvidence.empty()))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_ACCOUNT_DISABLED);
          assertThat(ex.getMessage()).isEqualTo("账号已停用");
        });

    FakeAuthRuntimeRepository lockedRepository = new FakeAuthRuntimeRepository();
    lockedRepository.lockedUntil = FIXED_CLOCK.instant().plusSeconds(60);
    AuthRuntimeApplicationService lockedService = service(
        lockedRepository,
        new FakePasswordHashingPort());

    assertThatThrownBy(() -> lockedService.login(
        new LoginRequest("13800138000", STRONG_PASSWORD),
        AuthClientEvidence.empty()))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_ACCOUNT_LOCKED);
          assertThat(ex.getMessage()).isEqualTo("账号已锁定");
        });
  }

  @Test
  void accessAuthenticationUsesOpaqueHashAndPlatformPrincipal() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    AuthRuntimeApplicationService service = service(repository, new FakePasswordHashingPort());

    Optional<com.aiexam.common.security.AuthenticatedPlatformSession> authenticated =
        service.authenticateAccessToken("browser-access-token", FIXED_CLOCK.instant());

    assertThat(authenticated).isPresent();
    assertThat(repository.lastAccessHash).isEqualTo("sha256:browser-access-token");
    assertThat(authenticated.get().principal().userId()).isEqualTo(1001L);
    assertThat(authenticated.get().principal().roles()).containsExactly("STUDENT");
  }

  @Test
  void smsLoginChallengeUsesItsOwnPurposeAndNormalizesMainlandMobile() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    FakeChallengeDeliveryPort delivery = new FakeChallengeDeliveryPort(true);
    AuthRuntimeApplicationService service = new AuthRuntimeApplicationService(
        repository,
        delivery,
        new FakePasswordHashingPort(),
        new DeterministicOpaqueTokenPort(),
        opaqueToken -> "sha256:" + opaqueToken,
        verificationPolicy(true, "test"),
        devSeedUsernameLoginPolicy(false, "test"),
        FIXED_CLOCK);

    service.createSmsLoginChallenge(new SmsLoginChallengeRequest(
        "13800138000",
        UUID.fromString("018fa7d9-0000-7000-8000-000000000201")));

    assertThat(repository.smsLoginChallengeMobiles).containsExactly("+8613800138000");
    assertThat(delivery.mobileDeliveries).containsExactly("SMS_LOGIN:+8613800138000");
  }

  @Test
  void smsLoginCreatesTheSameFormalSessionAsPasswordLogin() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    AuthRuntimeApplicationService service = service(repository, new FakePasswordHashingPort());

    CommittedAuthSession session = service.smsLogin(new SmsLoginRequest(
        "13800138000",
        UUID.fromString("018fa7d9-0000-7000-8000-000000000202"),
        "246810",
        UUID.fromString("018fa7d9-0000-7000-8000-000000000203")), AuthClientEvidence.empty());

    assertThat(repository.smsLoginConsumeMobiles).containsExactly("+8613800138000");
    assertThat(repository.sessionCreations).isEqualTo(1);
    assertThat(session.accessToken()).isEqualTo("access-token-1");
    assertThat(session.refreshToken()).isEqualTo("refresh-token-1");
  }

  @Test
  void invalidSmsChallengeUsesUniformChallengeErrorAndCreatesNoSession() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    repository.smsLoginUser = Optional.empty();
    AuthRuntimeApplicationService service = service(repository, new FakePasswordHashingPort());

    assertThatThrownBy(() -> service.smsLogin(new SmsLoginRequest(
        "13800138000",
        UUID.fromString("018fa7d9-0000-7000-8000-000000000204"),
        "000000",
        UUID.fromString("018fa7d9-0000-7000-8000-000000000205")), AuthClientEvidence.empty()))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_CHALLENGE_INVALID));
    assertThat(repository.sessionCreations).isZero();
  }

  @Test
  void refreshReturnsRotatedOpaqueTokensFromRepositoryResult() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    AuthRuntimeApplicationService service = service(repository, new FakePasswordHashingPort());

    RotatedAuthSession rotated = service.refresh("old-refresh-token");

    assertThat(repository.lastRefreshHash).isEqualTo("sha256:old-refresh-token");
    assertThat(rotated.accessToken()).isEqualTo("access-token-1");
    assertThat(rotated.refreshToken()).isEqualTo("refresh-token-1");
    assertThat(rotated.expiry().accessExpiresAt()).isEqualTo(FIXED_CLOCK.instant().plusSeconds(1800));
  }

  @Test
  void capabilitiesExposeUnavailableMobileWhenProviderIsNotConfigured() {
    AuthRuntimeApplicationService service = new AuthRuntimeApplicationService(
        new FakeAuthRuntimeRepository(),
        new FakeChallengeDeliveryPort(false),
        new FakePasswordHashingPort(),
        new DeterministicOpaqueTokenPort(),
        opaqueToken -> "sha256:" + opaqueToken,
        verificationPolicy(true, "test"),
        devSeedUsernameLoginPolicy(false, "test"),
        FIXED_CLOCK);

    var capabilities = service.capabilities();

    assertThat(capabilities.usernamePassword().status()).isEqualTo("AVAILABLE");
    assertThat(capabilities.usernamePassword().provider()).isEqualTo("IDENTIFIER_PASSWORD");
    assertThat(capabilities.mobile().status()).isEqualTo("UNAVAILABLE");
    assertThat(capabilities.email().status()).isEqualTo("DISABLED");
    assertThat(capabilities.wechat().status()).isEqualTo("DISABLED");
    assertThat(capabilities.registrationVerificationRequired()).isTrue();
    assertThat(capabilities.credentialPolicy().nickname().minLength()).isEqualTo(1);
    assertThat(capabilities.credentialPolicy().nickname().maxLength()).isEqualTo(32);
    assertThat(capabilities.credentialPolicy().password().minLength()).isEqualTo(8);
    assertThat(capabilities.credentialPolicy().password().maxLength()).isEqualTo(64);
  }

  @Test
  void passwordResetChallengeUsesMobileIdentifierAndSmsDelivery() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    FakeChallengeDeliveryPort delivery = new FakeChallengeDeliveryPort(true);
    AuthRuntimeApplicationService service = new AuthRuntimeApplicationService(
        repository,
        delivery,
        new FakePasswordHashingPort(),
        new DeterministicOpaqueTokenPort(),
        opaqueToken -> "sha256:" + opaqueToken,
        verificationPolicy(true, "test"),
        devSeedUsernameLoginPolicy(false, "test"),
        FIXED_CLOCK);

    service.createPasswordResetChallenge(new PasswordResetChallengeRequest(
        "13800138000",
        UUID.fromString("018fa7d9-0000-7000-8000-000000000101")));

    assertThat(repository.passwordResetChallengeKeys).containsExactly("MOBILE:+8613800138000");
    assertThat(delivery.mobileDeliveries).containsExactly("PASSWORD_RESET:+8613800138000");
    assertThat(delivery.emailDeliveries).isEmpty();
  }

  @Test
  void passwordResetChallengeUsesEmailProviderOnlyWhenAvailable() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    FakeChallengeDeliveryPort delivery = new FakeChallengeDeliveryPort(true, true);
    AuthRuntimeApplicationService service = new AuthRuntimeApplicationService(
        repository,
        delivery,
        new FakePasswordHashingPort(),
        new DeterministicOpaqueTokenPort(),
        opaqueToken -> "sha256:" + opaqueToken,
        verificationPolicy(true, "test"),
        devSeedUsernameLoginPolicy(false, "test"),
        FIXED_CLOCK);

    service.createPasswordResetChallenge(new PasswordResetChallengeRequest(
        "Student@Example.COM",
        UUID.fromString("018fa7d9-0000-7000-8000-000000000102")));

    assertThat(repository.passwordResetChallengeKeys).containsExactly("EMAIL:Student@example.com");
    assertThat(delivery.emailDeliveries).containsExactly("PASSWORD_RESET:Student@example.com");
    assertThat(delivery.mobileDeliveries).isEmpty();
  }

  @Test
  void passwordResetEmailChallengeFailsClosedWhenEmailProviderUnavailable() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    AuthRuntimeApplicationService service = new AuthRuntimeApplicationService(
        repository,
        new FakeChallengeDeliveryPort(true, false),
        new FakePasswordHashingPort(),
        new DeterministicOpaqueTokenPort(),
        opaqueToken -> "sha256:" + opaqueToken,
        verificationPolicy(true, "test"),
        devSeedUsernameLoginPolicy(false, "test"),
        FIXED_CLOCK);

    assertThatThrownBy(() -> service.createPasswordResetChallenge(new PasswordResetChallengeRequest(
        "student@example.com",
        UUID.fromString("018fa7d9-0000-7000-8000-000000000103"))))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_PROVIDER_UNAVAILABLE));
    assertThat(repository.passwordResetChallengeKeys).isEmpty();
  }

  @Test
  void passwordResetRejectsBareUsernameAndDoesNotLookupUsernames() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    AuthRuntimeApplicationService service = service(repository, new FakePasswordHashingPort());

    assertThatThrownBy(() -> service.createPasswordResetChallenge(new PasswordResetChallengeRequest(
        "student",
        UUID.fromString("018fa7d9-0000-7000-8000-000000000104"))))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_REQUEST_INVALID));
    assertThat(repository.passwordResetChallengeKeys).isEmpty();
  }

  @Test
  void passwordResetUsernameAliasStillMustBePhoneOrEmail() throws Exception {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    AuthRuntimeApplicationService service = service(repository, new FakePasswordHashingPort());
    PasswordResetChallengeRequest request = JSON.readValue(
        """
            {
              "username": "student",
              "requestId": "018fa7d9-0000-7000-8000-000000000107"
            }
            """,
        PasswordResetChallengeRequest.class);

    assertThatThrownBy(() -> service.createPasswordResetChallenge(request))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_REQUEST_INVALID));
    assertThat(repository.passwordResetChallengeKeys).isEmpty();
  }

  @Test
  void resetPasswordUsesIdentifierAccountContextForPasswordPolicy() {
    FakeAuthRuntimeRepository repository = new FakeAuthRuntimeRepository();
    repository.passwordResetAccountContext = Optional.of(new PasswordResetAccountContext(
        1001L,
        "student",
        "+8613800138000",
        "student@example.com"));
    AuthRuntimeApplicationService service = service(repository, new FakePasswordHashingPort());

    assertThatThrownBy(() -> service.resetPassword(new PasswordResetRequest(
        "13800138000",
        UUID.fromString("018fa7d9-0000-7000-8000-000000000105"),
        "123456",
        "student reset password",
        UUID.fromString("018fa7d9-0000-7000-8000-000000000106"))))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_PASSWORD_POLICY_VIOLATION));
    assertThat(repository.resetCalls).isZero();
  }

  @Test
  void passwordPolicyFollowsProductLengthCompositionBlocklistAndUnicodeNormalization() {
    assertThatThrownBy(() -> PasswordPolicy.validateAndNormalize(
        "123456", "student", "+8613800138000", "student@example.com"))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_PASSWORD_POLICY_VIOLATION));
    assertThatThrownBy(() -> PasswordPolicy.validateAndNormalize(
        "Aa12345", "student", "+8613800138000", "student@example.com"))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_PASSWORD_POLICY_VIOLATION));
    assertThatThrownBy(() -> PasswordPolicy.validateAndNormalize(
        "abcdefg1", "student", "+8613800138000", "student@example.com"))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_PASSWORD_POLICY_VIOLATION));
    assertThatThrownBy(() -> PasswordPolicy.validateAndNormalize(
        "ABCDEFG1", "student", "+8613800138000", "student@example.com"))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_PASSWORD_POLICY_VIOLATION));
    assertThatThrownBy(() -> PasswordPolicy.validateAndNormalize(
        "Abcdefgh", "student", "+8613800138000", "student@example.com"))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_PASSWORD_POLICY_VIOLATION));

    assertThat(PasswordPolicy.validateAndNormalize(
        "Abcdefg1", "student", "+8613800138000", "student@example.com"))
        .isEqualTo("Abcdefg1");
    assertThat(PasswordPolicy.validateAndNormalize(
        "A" + "b".repeat(62) + "1", "student", "+8613800138000", "student@example.com"))
        .hasSize(64);
    assertThatThrownBy(() -> PasswordPolicy.validateAndNormalize(
        "A" + "b".repeat(63) + "1", "student", "+8613800138000", "student@example.com"))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_PASSWORD_POLICY_VIOLATION);
          assertThat(ex.getMessage()).isEqualTo("密码过长，请缩短后重试");
        });
    assertThat(PasswordPolicy.validateAndNormalize(
        "Cafe\u0301Ab1!", "student", "+8613800138000", "student@example.com"))
        .isEqualTo("CaféAb1!");
    assertThat(PasswordPolicy.validateAndNormalize(
        "Space Ok1", "student", "+8613800138000", "student@example.com"))
        .isEqualTo("Space Ok1");
    assertThat(PasswordPolicy.validateAndNormalize(
        "密码Abc123!", "student", "+8613800138000", "student@example.com"))
        .isEqualTo("密码Abc123!");
  }

  @Test
  void passwordPolicyRejectsExpectedProjectAndAccountDerivedSecrets() {
    assertThatThrownBy(() -> PasswordPolicy.validateAndNormalize(
        "Aiexam1!", "student", "+8613800138000", "student@example.com"))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_PASSWORD_POLICY_VIOLATION));
    assertThatThrownBy(() -> PasswordPolicy.validateAndNormalize(
        "studentA1!", "student", "+8613800138000", "student@example.com"))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_PASSWORD_POLICY_VIOLATION));
    assertThatThrownBy(() -> PasswordPolicy.validateAndNormalize(
        "Aa13800138000", "student", "+8613800138000", "student@example.com"))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_PASSWORD_POLICY_VIOLATION));
  }

  @Test
  void registrationChallengeFailsClosedWhenMobileProviderIsUnavailable() {
    AuthRuntimeApplicationService service = new AuthRuntimeApplicationService(
        new FakeAuthRuntimeRepository(),
        new FakeChallengeDeliveryPort(false),
        new FakePasswordHashingPort(),
        new DeterministicOpaqueTokenPort(),
        opaqueToken -> "sha256:" + opaqueToken,
        verificationPolicy(true, "test"),
        devSeedUsernameLoginPolicy(false, "test"),
        FIXED_CLOCK);

    assertThatThrownBy(() -> service.createRegistrationChallenge(
        new com.aiexam.foundation.auth.dto.request.RegistrationChallengeRequest(
            "13800138000",
            UUID.fromString("018fa7d9-0000-7000-8000-000000000099"))))
        .isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_PROVIDER_UNAVAILABLE));
  }

  private AuthRuntimeApplicationService service(
      FakeAuthRuntimeRepository repository,
      PasswordHashingPort passwordHashingPort
  ) {
    return service(repository, passwordHashingPort, devSeedUsernameLoginPolicy(false, "test"));
  }

  private AuthRuntimeApplicationService service(
      FakeAuthRuntimeRepository repository,
      PasswordHashingPort passwordHashingPort,
      DevSeedUsernameLoginPolicy devSeedUsernameLoginPolicy
  ) {
    return new AuthRuntimeApplicationService(
        repository,
        new FakeChallengeDeliveryPort(true),
        passwordHashingPort,
        new DeterministicOpaqueTokenPort(),
        opaqueToken -> "sha256:" + opaqueToken,
        verificationPolicy(true, "test"),
        devSeedUsernameLoginPolicy,
        FIXED_CLOCK);
  }

  private AuthRegistrationVerificationPolicy verificationPolicy(boolean required, String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    environment.setProperty("aiexam.auth.registration.verification-required", Boolean.toString(required));
    AuthRegistrationVerificationPolicy policy = new AuthRegistrationVerificationPolicy(environment, required);
    if (required) {
      policy.failFastWhenProductionBypassIsEnabled();
    }
    return policy;
  }

  private AuthRegistrationVerificationPolicy verificationPolicyWithRawProfiles(boolean required, String... profiles) {
    MockEnvironment environment = new MockEnvironment() {
      @Override
      public String[] getActiveProfiles() {
        return profiles;
      }
    };
    environment.setProperty("aiexam.auth.registration.verification-required", Boolean.toString(required));
    return new AuthRegistrationVerificationPolicy(environment, required);
  }

  private AuthRegistrationVerificationPolicy verificationPolicyWithoutProperty(String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    return new AuthRegistrationVerificationPolicy(environment, true);
  }

  private DevSeedUsernameLoginPolicy devSeedUsernameLoginPolicy(boolean enabled, String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    environment.setProperty("aiexam.auth.dev-seed-username-login.enabled", Boolean.toString(enabled));
    DevSeedUsernameLoginPolicy policy = new DevSeedUsernameLoginPolicy(environment, enabled);
    policy.failFastWhenForbidden();
    return policy;
  }

  private RegisterRequest registerRequest(String role) {
    return new RegisterRequest(
        "Student_01",
        STRONG_PASSWORD,
        "Student One",
        "13800138000",
        null,
        UUID.fromString("018fa7d9-0000-7000-8000-000000000001"),
        "123456",
        UUID.fromString("018fa7d9-0000-7000-8000-000000000002"),
        role);
  }

  private static final class FakePasswordHashingPort implements PasswordHashingPort {
    private final boolean matches;

    private FakePasswordHashingPort() {
      this(true);
    }

    private FakePasswordHashingPort(boolean matches) {
      this.matches = matches;
    }

    @Override
    public String hash(String password, Argon2idPasswordPolicy policy) {
      assertThat(policy).isEqualTo(Argon2idPasswordPolicy.frozenP0());
      return "argon2id:" + password;
    }

    @Override
    public boolean verify(String password, String passwordHash) {
      return matches && ("hash:" + password).equals(passwordHash);
    }
  }

  private static final class FakeChallengeDeliveryPort implements ChallengeDeliveryPort {
    private final boolean mobileAvailable;
    private final boolean emailAvailable;
    private final List<String> mobileDeliveries = new ArrayList<>();
    private final List<String> emailDeliveries = new ArrayList<>();

    private FakeChallengeDeliveryPort(boolean available) {
      this(available, false);
    }

    private FakeChallengeDeliveryPort(boolean mobileAvailable, boolean emailAvailable) {
      this.mobileAvailable = mobileAvailable;
      this.emailAvailable = emailAvailable;
    }

    @Override
    public AuthProviderCapabilityDto mobileCapability() {
      return mobileAvailable
          ? AuthProviderCapabilityDto.available("DEV_MOBILE", "开发环境验证码投递已启用")
          : AuthProviderCapabilityDto.unavailable("MOBILE_SMS", "短信认证服务未配置");
    }

    @Override
    public AuthProviderCapabilityDto emailCapability() {
      return emailAvailable
          ? AuthProviderCapabilityDto.available("EMAIL_SMTP", "邮箱验证码投递已启用")
          : AuthProviderCapabilityDto.disabled("EMAIL", "邮箱认证未启用");
    }

    @Override
    public AuthProviderCapabilityDto wechatCapability() {
      return AuthProviderCapabilityDto.disabled("WECHAT", "微信认证未启用");
    }

    @Override
    public void deliverMobileCode(String purpose, java.util.UUID challengeId, String normalizedMobile, String code) {
      mobileDeliveries.add(purpose + ":" + normalizedMobile);
    }

    @Override
    public void deliverEmailCode(String purpose, java.util.UUID challengeId, String normalizedEmail, String code) {
      emailDeliveries.add(purpose + ":" + normalizedEmail);
    }
  }

  private static final class DeterministicOpaqueTokenPort implements OpaqueTokenPort {
    private int next = 1;

    @Override
    public String newToken() {
      int current = next++;
      int family = (current + 1) / 2;
      return (current % 2 == 1 ? "access-token-" : "refresh-token-") + family;
    }
  }

  private static final class FakeAuthRuntimeRepository implements AuthRuntimeRepository {
    private final List<String> lockOrder = new ArrayList<>();
    private int registerCalls;
    private int sessionCreations;
    private int failureRecords;
    private int prunedFamilies;
    private int activeFamiliesAfterLogin;
    private String registeredPasswordHash;
    private String lastAccessHash;
    private String lastRefreshHash;
    private boolean lastVerificationRequired = true;
    private RegistrationResultDto registrationResult;
    private String loginStatus = "ENABLED";
    private Instant lockedUntil;
    private final Map<String, LoginUserSnapshot> loginUsers = new java.util.HashMap<>();
    private final List<String> passwordResetChallengeKeys = new ArrayList<>();
    private final List<String> smsLoginChallengeMobiles = new ArrayList<>();
    private final List<String> smsLoginConsumeMobiles = new ArrayList<>();
    private Optional<LoginUserSnapshot> smsLoginUser = Optional.of(new LoginUserSnapshot(
        1001L, "student_01", "Student One", "+86 138****8000", "hash:" + STRONG_PASSWORD,
        "ENABLED", null, 3));
    private Optional<PasswordResetAccountContext> passwordResetAccountContext = Optional.of(
        new PasswordResetAccountContext(1001L, "student_01", "+8613800138000", "student@example.com"));
    private int resetCalls;

    @Override
    public boolean isHealthy() {
      return true;
    }

    @Override
    public IssuedChallenge createRegistrationChallenge(
        String normalizedMobile,
        String requestId,
        String codeHash,
        Instant requestNow
    ) {
      return new IssuedChallenge(new ChallengeDto(UUID.fromString(requestId), requestNow.plusSeconds(300), 60), true, normalizedMobile);
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
      registerCalls++;
      registeredPasswordHash = passwordHash;
      lastVerificationRequired = verificationRequired;
      if (registrationResult == null) {
        registrationResult = new RegistrationResultDto(1001L, normalizedNickname, "REGISTERED", requestNow);
      }
      return registrationResult;
    }

    @Override
    public Optional<LoginUserSnapshot> lockUserForLogin(String identifierType, String normalizedIdentifier, Instant requestNow) {
      lockOrder.add(identifierType + ":" + normalizedIdentifier);
      LoginUserSnapshot defaultUser = new LoginUserSnapshot(
          1001L,
          "student_01",
          "Student One",
          "+86 138****8000",
          "hash:" + STRONG_PASSWORD,
          loginStatus,
          lockedUntil,
          3);
      return Optional.ofNullable(loginUsers.getOrDefault(identifierType + ":" + normalizedIdentifier, defaultUser));
    }

    @Override
    public void recordLoginFailure(String identifierType, String normalizedIdentifier, Instant requestNow) {
      failureRecords++;
    }

    @Override
    public CommittedAuthSession createLoginSessionUserFirst(
        LoginUserSnapshot user,
        IssuedOpaqueTokens tokens,
        AuthClientEvidence clientEvidence,
        Instant requestNow
    ) {
      lockOrder.add("sessions");
      sessionCreations++;
      prunedFamilies = 1;
      activeFamiliesAfterLogin = 5;
      return new CommittedAuthSession(authContext(user, tokens), tokens.accessToken(), tokens.refreshToken());
    }

    @Override
    public Optional<AuthContext> findAuthContext(PlatformPrincipal principal, Instant requestNow) {
      return Optional.of(authContext(principal, requestNow));
    }

    @Override
    public Optional<PlatformPrincipal> findPrincipalByAccessTokenHash(String accessTokenHash, Instant requestNow) {
      lastAccessHash = accessTokenHash;
      return Optional.of(new PlatformPrincipal(1001L, "student_01", "session-1", List.of("STUDENT"), List.of(), 3));
    }

    @Override
    public RotatedAuthSession rotateRefresh(String refreshTokenHash, IssuedOpaqueTokens nextTokens, Instant requestNow) {
      lastRefreshHash = refreshTokenHash;
      return new RotatedAuthSession(
          new SessionExpiryDto(nextTokens.accessExpiresAt(), nextTokens.refreshExpiresAt()),
          nextTokens.accessToken(),
          nextTokens.refreshToken());
    }

    @Override
    public void logout(PlatformPrincipal principal, String refreshTokenHash, Instant requestNow) {
    }

    @Override
    public IssuedChallenge createSmsLoginChallenge(
        String normalizedMobile,
        String requestId,
        String codeHash,
        Instant requestNow
    ) {
      smsLoginChallengeMobiles.add(normalizedMobile);
      return new IssuedChallenge(
          new ChallengeDto(UUID.fromString(requestId), requestNow.plusSeconds(300), 60),
          true,
          normalizedMobile);
    }

    @Override
    public Optional<LoginUserSnapshot> consumeSmsLoginChallenge(
        String normalizedMobile,
        SmsLoginRequest request,
        String verificationCodeHash,
        Instant requestNow
    ) {
      smsLoginConsumeMobiles.add(normalizedMobile);
      return smsLoginUser;
    }

    @Override
    public IssuedChallenge createPasswordResetChallenge(
        String identifierType,
        String normalizedIdentifier,
        String requestId,
        String codeHash,
        Instant requestNow
    ) {
      passwordResetChallengeKeys.add(identifierType + ":" + normalizedIdentifier);
      return new IssuedChallenge(
          new ChallengeDto(UUID.fromString(requestId), requestNow.plusSeconds(300), 60),
          true,
          identifierType,
          normalizedIdentifier);
    }

    @Override
    public Optional<PasswordResetAccountContext> findPasswordResetAccountContext(
        String identifierType,
        String normalizedIdentifier,
        PasswordResetRequest request,
        String verificationCodeHash,
        Instant requestNow
    ) {
      return passwordResetAccountContext;
    }

    @Override
    public void resetPassword(
        String identifierType,
        String normalizedIdentifier,
        PasswordResetRequest request,
        String passwordHash,
        Instant requestNow
    ) {
      resetCalls++;
    }

    private AuthContext authContext(LoginUserSnapshot user, IssuedOpaqueTokens tokens) {
      return new AuthContext(
          new AuthUserDto(user.userId(), user.username(), user.displayName(), user.maskedMobile(), user.status()),
          "admin".equals(user.username()) ? List.of("ADMIN", "STUDENT", "QUESTION_AUTHOR") : List.of("STUDENT"),
          "admin".equals(user.username()) ? List.of("question:read", "question:create") : List.of("practice:read"),
          List.of(new AuthMenuDto("practice", "刷题", "/app/practice", "book")),
          Map.of("practice", true),
          tokens.accessExpiresAt(),
          tokens.refreshExpiresAt());
    }

    private AuthContext authContext(PlatformPrincipal principal, Instant requestNow) {
      return new AuthContext(
          new AuthUserDto(principal.userId(), principal.username(), "Student One", null, "ENABLED"),
          principal.roles(),
          principal.permissions(),
          List.of(),
          Map.of(),
          requestNow.plusSeconds(1800),
          requestNow.plusSeconds(604800));
    }
  }
}
