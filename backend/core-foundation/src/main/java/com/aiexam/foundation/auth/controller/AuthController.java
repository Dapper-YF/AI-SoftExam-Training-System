package com.aiexam.foundation.auth.controller;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.common.error.BusinessException;
import com.aiexam.common.error.ErrorCode;
import com.aiexam.common.security.CurrentUserProvider;
import com.aiexam.common.security.PlatformCookieNames;
import com.aiexam.common.security.PlatformCookieService;
import com.aiexam.common.security.PlatformCsrfTokenService;
import com.aiexam.common.security.PlatformPrincipal;
import com.aiexam.foundation.auth.application.AuthRuntimeUseCase;
import com.aiexam.foundation.auth.domain.AuthClientEvidence;
import com.aiexam.foundation.auth.dto.AuthCapabilitiesDto;
import com.aiexam.foundation.auth.dto.AuthContext;
import com.aiexam.foundation.auth.dto.ChallengeDto;
import com.aiexam.foundation.auth.dto.CommittedAuthSession;
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
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthRuntimeUseCase authRuntimeUseCase;
  private final CurrentUserProvider currentUserProvider;
  private final PlatformCookieService cookieService;
  private final PlatformCsrfTokenService csrfTokenService;

  public AuthController(
      AuthRuntimeUseCase authRuntimeUseCase,
      CurrentUserProvider currentUserProvider,
      PlatformCookieService cookieService,
      PlatformCsrfTokenService csrfTokenService
  ) {
    this.authRuntimeUseCase = authRuntimeUseCase;
    this.currentUserProvider = currentUserProvider;
    this.cookieService = cookieService;
    this.csrfTokenService = csrfTokenService;
  }

  @GetMapping("/csrf")
  public ResponseEntity<Void> csrf(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
    cookieService.refreshCsrfCookie(httpRequest, httpResponse, csrfTokenService.newToken());
    return ResponseEntity.noContent()
        .header(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0")
        .header(HttpHeaders.PRAGMA, "no-cache")
        .build();
  }

  @GetMapping("/capabilities")
  public ApiResponse<AuthCapabilitiesDto> capabilities() {
    return ApiResponse.ok(authRuntimeUseCase.capabilities());
  }

  @PostMapping("/registration-challenges")
  public ResponseEntity<ApiResponse<ChallengeDto>> registrationChallenge(
      @Valid @RequestBody RegistrationChallengeRequest request
  ) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(ApiResponse.ok(authRuntimeUseCase.createRegistrationChallenge(request)));
  }

  @PostMapping("/register")
  public ResponseEntity<ApiResponse<RegistrationResultDto>> register(
      @Valid @RequestBody RegisterRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse
  ) {
    RegistrationResultDto result = authRuntimeUseCase.register(request);
    cookieService.refreshCsrfCookie(httpRequest, httpResponse, csrfTokenService.newToken());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(result));
  }

  @PostMapping("/login")
  public ApiResponse<AuthContext> login(
      @Valid @RequestBody LoginRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse
  ) {
    CommittedAuthSession session = authRuntimeUseCase.login(request, clientEvidence(httpRequest));
    Instant now = Instant.now();
    cookieService.setAuthCookies(
        httpResponse,
        session.accessToken(),
        session.authContext().accessExpiresAt(),
        session.refreshToken(),
        session.authContext().refreshExpiresAt(),
        now);
    cookieService.refreshCsrfCookie(httpRequest, httpResponse, csrfTokenService.newToken());
    httpResponse.setHeader("Cache-Control", "no-store");
    return ApiResponse.ok(session.authContext());
  }

  @PostMapping("/sms-login-challenges")
  public ResponseEntity<ApiResponse<ChallengeDto>> smsLoginChallenge(
      @Valid @RequestBody SmsLoginChallengeRequest request
  ) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(ApiResponse.ok(authRuntimeUseCase.createSmsLoginChallenge(request)));
  }

  @PostMapping("/sms-login")
  public ApiResponse<AuthContext> smsLogin(
      @Valid @RequestBody SmsLoginRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse
  ) {
    CommittedAuthSession session = authRuntimeUseCase.smsLogin(request, clientEvidence(httpRequest));
    Instant now = Instant.now();
    cookieService.setAuthCookies(
        httpResponse,
        session.accessToken(),
        session.authContext().accessExpiresAt(),
        session.refreshToken(),
        session.authContext().refreshExpiresAt(),
        now);
    cookieService.refreshCsrfCookie(httpRequest, httpResponse, csrfTokenService.newToken());
    httpResponse.setHeader("Cache-Control", "no-store");
    return ApiResponse.ok(session.authContext());
  }

  @GetMapping("/me")
  public ApiResponse<AuthContext> me() {
    PlatformPrincipal principal = currentUserProvider.currentPrincipal()
        .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED, "会话已过期", HttpStatus.UNAUTHORIZED));
    return ApiResponse.ok(authRuntimeUseCase.me(principal));
  }

  @PostMapping("/refresh")
  public ApiResponse<SessionExpiryDto> refresh(HttpServletRequest request, HttpServletResponse response) {
    RotatedAuthSession rotated = authRuntimeUseCase.refresh(cookie(request, PlatformCookieNames.REFRESH));
    Instant now = Instant.now();
    cookieService.setAuthCookies(
        response,
        rotated.accessToken(),
        rotated.expiry().accessExpiresAt(),
        rotated.refreshToken(),
        rotated.expiry().refreshExpiresAt(),
        now);
    cookieService.refreshCsrfCookie(request, response, csrfTokenService.newToken());
    response.setHeader("Cache-Control", "no-store");
    return ApiResponse.ok(rotated.expiry());
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
    PlatformPrincipal principal = currentUserProvider.currentPrincipal().orElse(null);
    authRuntimeUseCase.logout(principal, cookie(request, PlatformCookieNames.REFRESH));
    cookieService.clearAuthCookies(response);
    cookieService.refreshCsrfCookie(request, response, csrfTokenService.newToken());
    response.setHeader("Cache-Control", "no-store");
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/password-reset-challenges")
  public ResponseEntity<ApiResponse<ChallengeDto>> passwordResetChallenge(
      @Valid @RequestBody PasswordResetChallengeRequest request
  ) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(ApiResponse.ok(authRuntimeUseCase.createPasswordResetChallenge(request)));
  }

  @PostMapping("/password/reset")
  public ResponseEntity<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
    authRuntimeUseCase.resetPassword(request);
    return ResponseEntity.noContent().build();
  }

  private AuthClientEvidence clientEvidence(HttpServletRequest request) {
    return new AuthClientEvidence(request.getRemoteAddr(), request.getHeader("User-Agent"));
  }

  private String cookie(HttpServletRequest request, String name) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (name.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }
}
