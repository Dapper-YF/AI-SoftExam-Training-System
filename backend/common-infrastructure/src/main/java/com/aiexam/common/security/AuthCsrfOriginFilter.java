package com.aiexam.common.security;

import com.aiexam.common.api.ApiResponse;
import com.aiexam.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AuthCsrfOriginFilter extends OncePerRequestFilter {
  private static final Logger LOGGER = LoggerFactory.getLogger(AuthCsrfOriginFilter.class);
  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
  private static final String EXAM_SIMULATOR_API_PREFIX = "/api/v1/exam-simulator/";

  private final ObjectMapper objectMapper;

  public AuthCsrfOriginFilter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!requiresAuthCsrf(request)) {
      filterChain.doFilter(request, response);
      return;
    }
    boolean validDoubleSubmit = hasValidDoubleSubmitToken(request);
    boolean validSameOrigin = hasValidSameOriginBoundary(request);
    if (!validDoubleSubmit || !validSameOrigin) {
      LOGGER.warn("CSRF request rejected: method={}, path={}, doubleSubmit={}, sameOrigin={}, origin={}, expectedOrigin={}",
          request.getMethod(), request.getRequestURI(), validDoubleSubmit, validSameOrigin,
          request.getHeader("Origin"), expectedOrigin(request));
      reject(response);
      return;
    }
    filterChain.doFilter(request, response);
  }

  private boolean requiresAuthCsrf(HttpServletRequest request) {
    return request.getRequestURI().startsWith("/api/")
        // Exam simulator requests use their isolated non-cookie session boundary. The
        // PlatformCookieAuthenticationFilter must continue to ignore this namespace.
        && !request.getRequestURI().startsWith(EXAM_SIMULATOR_API_PREFIX)
        && !SAFE_METHODS.contains(request.getMethod().toUpperCase())
        && hasPlatformCookieCredential(request);
  }

  private boolean hasPlatformCookieCredential(HttpServletRequest request) {
    return cookie(request, PlatformCookieNames.ACCESS) != null
        || cookie(request, PlatformCookieNames.REFRESH) != null;
  }

  private boolean hasValidDoubleSubmitToken(HttpServletRequest request) {
    String cookie = cookie(request, PlatformCookieNames.CSRF);
    String header = request.getHeader(PlatformCookieNames.CSRF_HEADER);
    return cookie != null && !cookie.isBlank() && cookie.equals(header);
  }

  private boolean hasValidSameOriginBoundary(HttpServletRequest request) {
    String origin = request.getHeader("Origin");
    if (origin != null && !origin.isBlank()) {
      return expectedOrigin(request).equals(origin);
    }
    String referer = request.getHeader("Referer");
    if (referer == null || referer.isBlank()) {
      return false;
    }
    try {
      URI uri = URI.create(referer);
      String expected = expectedOrigin(request);
      String actual = uri.getScheme() + "://" + uri.getAuthority();
      return expected.equals(actual) && ("https".equals(uri.getScheme()) || isLoopback(request));
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private String expectedOrigin(HttpServletRequest request) {
    String proto = request.getHeader("X-Forwarded-Proto");
    String scheme = proto == null || proto.isBlank() ? request.getScheme() : proto;
    return scheme + "://" + request.getHeader("Host");
  }

  private boolean isLoopback(HttpServletRequest request) {
    String host = request.getServerName();
    return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
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

  private void reject(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setCharacterEncoding("UTF-8");
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write(objectMapper.writeValueAsString(
        ApiResponse.fail(ErrorCode.CSRF_INVALID, "CSRF校验失败", null)));
  }
}
