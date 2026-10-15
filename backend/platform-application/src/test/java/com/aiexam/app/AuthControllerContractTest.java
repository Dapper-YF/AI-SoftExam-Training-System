package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiexam.common.security.CurrentUserProvider;
import com.aiexam.common.security.PlatformCookieNames;
import com.aiexam.common.security.PlatformCookieService;
import com.aiexam.common.security.PlatformCsrfTokenService;
import com.aiexam.common.security.PlatformPrincipal;
import com.aiexam.foundation.auth.application.AuthRuntimeUseCase;
import com.aiexam.foundation.auth.controller.AuthController;
import com.aiexam.foundation.auth.domain.AuthClientEvidence;
import com.aiexam.foundation.auth.dto.AuthCapabilitiesDto;
import com.aiexam.foundation.auth.dto.AuthContext;
import com.aiexam.foundation.auth.dto.AuthContext.AuthUserDto;
import com.aiexam.foundation.auth.dto.AuthProviderCapabilityDto;
import com.aiexam.foundation.auth.dto.ChallengeDto;
import com.aiexam.foundation.auth.dto.CommittedAuthSession;
import com.aiexam.foundation.auth.dto.CredentialPolicyDto;
import com.aiexam.foundation.auth.dto.RegistrationResultDto;
import com.aiexam.foundation.auth.dto.SessionExpiryDto;
import com.aiexam.foundation.auth.dto.request.LoginRequest;
import com.aiexam.foundation.auth.dto.request.PasswordResetChallengeRequest;
import com.aiexam.foundation.auth.dto.request.PasswordResetRequest;
import com.aiexam.foundation.auth.dto.request.RegisterRequest;
import com.aiexam.foundation.auth.dto.request.RegistrationChallengeRequest;
import com.aiexam.foundation.auth.dto.request.SmsLoginChallengeRequest;
import com.aiexam.foundation.auth.dto.request.SmsLoginRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthControllerContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void csrfEndpointPreventsTunnelAndBrowserCachesFromReusingAnOldToken() {
    AuthController controller = controller(new FakeUseCase(), Optional.empty());
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/csrf");
    MockHttpServletResponse response = new MockHttpServletResponse();

    ResponseEntity<Void> result = controller.csrf(request, response);

    assertThat(result.getStatusCode().value()).isEqualTo(204);
    assertThat(result.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store, max-age=0");
    assertThat(result.getHeaders().getFirst("Pragma")).isEqualTo("no-cache");
    assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie ->
        assertThat(cookie).startsWith(PlatformCookieNames.CSRF + "="));
  }

  @Test
  void loginSetsOpaqueHttpOnlyCookiesAfterUseCaseReturnsCommittedSession() {
    AuthController controller = controller(new FakeUseCase(), Optional.empty());
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.login(new LoginRequest("13800138000", "CorrectHorseNFC1"), request, response);

    assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie -> {
      assertThat(cookie).contains(PlatformCookieNames.ACCESS + "=access-token");
      assertThat(cookie).contains("HttpOnly");
      assertThat(cookie).contains("Path=/api/");
    });
    assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie -> {
      assertThat(cookie).contains(PlatformCookieNames.REFRESH + "=refresh-token");
      assertThat(cookie).contains("HttpOnly");
      assertThat(cookie).contains("Path=/api/v1/auth/");
    });
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
  }

  @Test
  void smsLoginSetsTheSameOpaqueCookieSessionAsPasswordLogin() {
    AuthController controller = controller(new FakeUseCase(), Optional.empty());
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/sms-login");
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.smsLogin(new SmsLoginRequest(
        "+8613800138000",
        UUID.randomUUID(),
        "246810",
        UUID.randomUUID()), request, response);

    assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie ->
        assertThat(cookie).contains(PlatformCookieNames.ACCESS + "=access-token").contains("HttpOnly"));
    assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie ->
        assertThat(cookie).contains(PlatformCookieNames.REFRESH + "=refresh-token").contains("HttpOnly"));
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
  }

  @Test
  void capabilitiesSerializeCredentialPolicyAsNestedSingleSource() throws Exception {
    AuthController controller = controller(new FakeUseCase(), Optional.empty());

    JsonNode root = JSON.valueToTree(controller.capabilities());
    JsonNode policy = root.path("data").path("credentialPolicy");

    assertThat(policy.path("nickname").path("minLength").asInt()).isEqualTo(1);
    assertThat(policy.path("nickname").path("maxLength").asInt()).isEqualTo(32);
    assertThat(policy.path("password").path("minLength").asInt()).isEqualTo(8);
    assertThat(policy.path("password").path("maxLength").asInt()).isEqualTo(64);
    assertThat(root.path("data").has("devSeedUsernameLoginEnabled")).isFalse();
    assertThat(policy.has("nicknameMinLength")).isFalse();
    assertThat(policy.has("nicknameMaxLength")).isFalse();
    assertThat(policy.has("passwordMinLength")).isFalse();
    assertThat(policy.has("passwordMaxLength")).isFalse();
  }

  @Test
  void refreshRewritesBothOpaqueCookiesAfterUseCaseReturnsRotatedTokens() {
    AuthController controller = controller(new FakeUseCase(), Optional.empty());
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
    request.setCookies(new jakarta.servlet.http.Cookie(PlatformCookieNames.REFRESH, "old-refresh-token"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.refresh(request, response);

    assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie -> {
      assertThat(cookie).contains(PlatformCookieNames.ACCESS + "=rotated-access-token");
      assertThat(cookie).contains("HttpOnly");
      assertThat(cookie).contains("Path=/api/");
    });
    assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie -> {
      assertThat(cookie).contains(PlatformCookieNames.REFRESH + "=rotated-refresh-token");
      assertThat(cookie).contains("HttpOnly");
      assertThat(cookie).contains("Path=/api/v1/auth/");
    });
    assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie ->
        assertThat(cookie).contains(PlatformCookieNames.CSRF + "="));
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
  }

  @Test
  void registerReturns201AndOnlyRefreshesCsrfCookie() {
    AuthController controller = controller(new FakeUseCase(), Optional.empty());
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/register");
    MockHttpServletResponse response = new MockHttpServletResponse();

    ResponseEntity<?> result = controller.register(
        new RegisterRequest(
            "student_01",
            "CorrectHorseNFC1",
            "Student One",
            "13800138000",
            null,
            UUID.randomUUID(),
            "123456",
            UUID.randomUUID(),
            null),
        request,
        response);

    assertThat(result.getStatusCode().value()).isEqualTo(201);
    assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie ->
        assertThat(cookie).contains(PlatformCookieNames.CSRF + "="));
    assertThat(response.getHeaders("Set-Cookie")).noneSatisfy(cookie ->
        assertThat(cookie).contains(PlatformCookieNames.ACCESS + "="));
    assertThat(response.getHeaders("Set-Cookie")).noneSatisfy(cookie ->
        assertThat(cookie).contains(PlatformCookieNames.REFRESH + "="));
  }

  @Test
  void meUsesPlatformPrincipalFromCurrentUserProvider() {
    PlatformPrincipal principal = new PlatformPrincipal(1001L, "student_01", "session-1", List.of("STUDENT"), List.of(), 1);
    AuthController controller = controller(new FakeUseCase(), Optional.of(principal));

    AuthContext context = controller.me().data();

    assertThat(context.user().id()).isEqualTo(1001L);
    assertThat(context.roles()).containsExactly("STUDENT");
  }

  private AuthController controller(FakeUseCase useCase, Optional<PlatformPrincipal> principal) {
    CurrentUserProvider currentUserProvider = () -> principal;
    return new AuthController(
        useCase,
        currentUserProvider,
        new PlatformCookieService(),
        new PlatformCsrfTokenService());
  }

  private static final class FakeUseCase implements AuthRuntimeUseCase {
    @Override
    public AuthCapabilitiesDto capabilities() {
      return new AuthCapabilitiesDto(
          AuthProviderCapabilityDto.available("IDENTIFIER_PASSWORD", "手机号或邮箱密码登录可用"),
          AuthProviderCapabilityDto.unavailable("MOBILE_SMS", "短信认证服务未配置"),
          AuthProviderCapabilityDto.disabled("EMAIL", "邮箱认证未启用"),
          AuthProviderCapabilityDto.disabled("WECHAT", "微信认证未启用"),
          true,
          new CredentialPolicyDto(
              new CredentialPolicyDto.RangeDto(1, 32),
              new CredentialPolicyDto.RangeDto(8, 64)),
          false);
    }

    @Override
    public ChallengeDto createRegistrationChallenge(RegistrationChallengeRequest request) {
      return new ChallengeDto(request.requestId(), Instant.now().plusSeconds(300), 60);
    }

    @Override
    public RegistrationResultDto register(RegisterRequest request) {
      return new RegistrationResultDto(1001L, request.nickname(), "REGISTERED", Instant.now());
    }

    @Override
    public CommittedAuthSession login(LoginRequest request, AuthClientEvidence clientEvidence) {
      return new CommittedAuthSession(context(), "access-token", "refresh-token");
    }

    @Override
    public ChallengeDto createSmsLoginChallenge(SmsLoginChallengeRequest request) {
      return new ChallengeDto(request.requestId(), Instant.now().plusSeconds(300), 60);
    }

    @Override
    public CommittedAuthSession smsLogin(SmsLoginRequest request, AuthClientEvidence clientEvidence) {
      return new CommittedAuthSession(context(), "access-token", "refresh-token");
    }

    @Override
    public AuthContext me(PlatformPrincipal principal) {
      return new AuthContext(
          new AuthUserDto(principal.userId(), principal.username(), "Student One", null, "ENABLED"),
          principal.roles(),
          principal.permissions(),
          List.of(),
          Map.of(),
          Instant.now().plusSeconds(1800),
          Instant.now().plusSeconds(604800));
    }

    @Override
    public com.aiexam.foundation.auth.dto.RotatedAuthSession refresh(String refreshToken) {
      return new com.aiexam.foundation.auth.dto.RotatedAuthSession(
          new SessionExpiryDto(Instant.now().plusSeconds(1800), Instant.now().plusSeconds(604800)),
          "rotated-access-token",
          "rotated-refresh-token");
    }

    @Override
    public void logout(PlatformPrincipal principal, String refreshToken) {
    }

    @Override
    public ChallengeDto createPasswordResetChallenge(PasswordResetChallengeRequest request) {
      return new ChallengeDto(request.requestId(), Instant.now().plusSeconds(300), 60);
    }

    @Override
    public void resetPassword(PasswordResetRequest request) {
    }

    private AuthContext context() {
      return new AuthContext(
          new AuthUserDto(1001L, "student_01", "Student One", "+86 138****8000", "ENABLED"),
          List.of("STUDENT"),
          List.of("practice:read"),
          List.of(),
          Map.of("practice", true),
          Instant.now().plusSeconds(1800),
          Instant.now().plusSeconds(604800));
    }
  }
}
