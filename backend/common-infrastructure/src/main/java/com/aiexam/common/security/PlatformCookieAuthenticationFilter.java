package com.aiexam.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class PlatformCookieAuthenticationFilter extends OncePerRequestFilter {
  private static final String EXAM_SIMULATOR_API_PREFIX = "/api/v1/exam-simulator/";

  private final PlatformSessionAuthenticator sessionAuthenticator;
  private final Clock clock;

  @Autowired
  public PlatformCookieAuthenticationFilter(PlatformSessionAuthenticator sessionAuthenticator) {
    this(sessionAuthenticator, Clock.systemUTC());
  }

  PlatformCookieAuthenticationFilter(PlatformSessionAuthenticator sessionAuthenticator, Clock clock) {
    this.sessionAuthenticator = sessionAuthenticator;
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    // The exam simulator owns an independent sessionStorage/Bearer boundary.
    // Never let an ambient platform cookie authenticate this namespace.
    if (request.getRequestURI().startsWith(EXAM_SIMULATOR_API_PREFIX)) {
      filterChain.doFilter(request, response);
      return;
    }
    String token = cookie(request, PlatformCookieNames.ACCESS);
    if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
      sessionAuthenticator.authenticateAccessToken(token, clock.instant())
          .ifPresent(session -> authenticate(session.principal()));
    }
    filterChain.doFilter(request, response);
  }

  private void authenticate(PlatformPrincipal principal) {
    List<SimpleGrantedAuthority> roleAuthorities = principal.roles().stream()
        .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
        .map(SimpleGrantedAuthority::new)
        .toList();
    List<SimpleGrantedAuthority> permissionAuthorities = principal.permissions().stream()
        .map(SimpleGrantedAuthority::new)
        .toList();
    List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>(roleAuthorities);
    authorities.addAll(permissionAuthorities);
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(principal, null, authorities);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private String cookie(HttpServletRequest request, String name) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
        return cookie.getValue();
      }
    }
    return null;
  }
}
