package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiexam.common.security.AuthenticatedPlatformSession;
import com.aiexam.common.security.PlatformCookieAuthenticationFilter;
import com.aiexam.common.security.PlatformCookieNames;
import com.aiexam.common.security.PlatformPrincipal;
import com.aiexam.common.security.PlatformSessionAuthenticator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class PlatformCookieAuthenticationFilterTest {
  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void platformPrincipalPermissionsAreIncludedAsAuthorities() throws Exception {
    PlatformSessionAuthenticator authenticator = (token, now) -> Optional.of(new AuthenticatedPlatformSession(
        new PlatformPrincipal(1001L, "student_01", "session-1", List.of("STUDENT"), List.of("practice:answer"), 1),
        Instant.now().plusSeconds(1800),
        Instant.now().plusSeconds(604800)));
    PlatformCookieAuthenticationFilter filter = new PlatformCookieAuthenticationFilter(authenticator);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/questions");
    request.setCookies(new MockCookie(PlatformCookieNames.ACCESS, "access-token"));

    filter.doFilter(request, new MockHttpServletResponse(), new CountingChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .extracting(Object::toString)
        .contains("ROLE_STUDENT", "practice:answer");
  }

  @Test
  void examSimulatorNamespaceNeverAuthenticatesAmbientPlatformCookie() throws Exception {
    AtomicInteger authenticationCalls = new AtomicInteger();
    PlatformSessionAuthenticator authenticator = (token, now) -> {
      authenticationCalls.incrementAndGet();
      return Optional.of(new AuthenticatedPlatformSession(
          new PlatformPrincipal(1001L, "student_01", "session-1", List.of("STUDENT"), List.of(), 1),
          Instant.now().plusSeconds(1800),
          Instant.now().plusSeconds(604800)));
    };
    PlatformCookieAuthenticationFilter filter = new PlatformCookieAuthenticationFilter(authenticator);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/exam-simulator/sessions/session-1/enter");
    request.addHeader("Authorization", "Bearer invalid-or-expired-exam-token");
    request.setCookies(new MockCookie(PlatformCookieNames.ACCESS, "ambient-platform-access-token"));
    CountingChain chain = new CountingChain();

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(authenticationCalls).hasValue(0);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(chain.calls).isEqualTo(1);
  }

  private static final class CountingChain implements FilterChain {
    private int calls;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response) throws IOException {
      calls++;
    }
  }
}
