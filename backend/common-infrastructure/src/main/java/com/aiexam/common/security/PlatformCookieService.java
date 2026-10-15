package com.aiexam.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class PlatformCookieService {
  public void setAuthCookies(
      HttpServletResponse response,
      String accessToken,
      Instant accessExpiresAt,
      String refreshToken,
      Instant refreshExpiresAt,
      Instant now
  ) {
    response.addHeader("Set-Cookie", authCookie(PlatformCookieNames.ACCESS, accessToken, "/api/", accessExpiresAt, now));
    response.addHeader("Set-Cookie", authCookie(PlatformCookieNames.REFRESH, refreshToken, "/api/v1/auth/", refreshExpiresAt, now));
  }

  public void clearAuthCookies(HttpServletResponse response) {
    response.addHeader("Set-Cookie", expired(PlatformCookieNames.ACCESS, "/api/", true));
    response.addHeader("Set-Cookie", expired(PlatformCookieNames.REFRESH, "/api/v1/auth/", true));
  }

  public void refreshCsrfCookie(HttpServletRequest request, HttpServletResponse response, String csrfToken) {
    response.addHeader("Set-Cookie", ResponseCookie.from(PlatformCookieNames.CSRF, csrfToken)
        .httpOnly(false)
        .secure(isSecure(request))
        .sameSite("Lax")
        .path("/")
        .maxAge(Duration.ofHours(2))
        .build()
        .toString());
  }

  private String authCookie(String name, String value, String path, Instant expiresAt, Instant now) {
    long maxAgeSeconds = Math.max(0, Duration.between(now, expiresAt).getSeconds());
    return ResponseCookie.from(name, value)
        .httpOnly(true)
        .secure(true)
        .sameSite("Lax")
        .path(path)
        .maxAge(Duration.ofSeconds(maxAgeSeconds))
        .build()
        .toString();
  }

  private String expired(String name, String path, boolean httpOnly) {
    return ResponseCookie.from(name, "")
        .httpOnly(httpOnly)
        .secure(true)
        .sameSite("Lax")
        .path(path)
        .maxAge(Duration.ZERO)
        .build()
        .toString();
  }

  private boolean isSecure(HttpServletRequest request) {
    return request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
  }
}
