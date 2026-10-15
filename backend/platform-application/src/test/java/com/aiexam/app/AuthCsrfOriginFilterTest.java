package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiexam.common.security.AuthCsrfOriginFilter;
import com.aiexam.common.security.PlatformCookieNames;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthCsrfOriginFilterTest {
  @Test
  void anonymousMutationWithoutPlatformCookieContinuesToAuthenticationGate() throws Exception {
    AuthCsrfOriginFilter filter = new AuthCsrfOriginFilter(new ObjectMapper());
    MockHttpServletRequest request = request("/api/v1/practice/attempts");
    MockHttpServletResponse response = new MockHttpServletResponse();
    CountingChain chain = new CountingChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(chain.calls).isEqualTo(1);
  }

  @Test
  void unsafeRequestWithAccessCookieMissingCsrfIsRejected() throws Exception {
    AuthCsrfOriginFilter filter = new AuthCsrfOriginFilter(new ObjectMapper());
    MockHttpServletRequest request = request("/api/v1/practice/attempts");
    request.setCookies(new MockCookie(PlatformCookieNames.ACCESS, "access-token"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    CountingChain chain = new CountingChain();

    filter.doFilter(request, response, chain);

    assertCsrfInvalid(response);
    assertThat(chain.calls).isZero();
  }

  @Test
  void unsafeRequestWithRefreshCookieMissingCsrfIsRejected() throws Exception {
    AuthCsrfOriginFilter filter = new AuthCsrfOriginFilter(new ObjectMapper());
    MockHttpServletRequest request = request("/api/v1/auth/refresh");
    request.setCookies(new MockCookie(PlatformCookieNames.REFRESH, "refresh-token"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    CountingChain chain = new CountingChain();

    filter.doFilter(request, response, chain);

    assertCsrfInvalid(response);
    assertThat(chain.calls).isZero();
  }

  @Test
  void unsafeRequestWithPlatformCookieAndCrossOriginIsRejected() throws Exception {
    AuthCsrfOriginFilter filter = new AuthCsrfOriginFilter(new ObjectMapper());
    MockHttpServletRequest request = request("/api/v1/practice/attempts");
    request.setCookies(
        new MockCookie(PlatformCookieNames.ACCESS, "access-token"),
        new MockCookie(PlatformCookieNames.CSRF, "csrf-token"));
    request.addHeader(PlatformCookieNames.CSRF_HEADER, "csrf-token");
    request.removeHeader("Origin");
    request.addHeader("Origin", "https://evil.example.com");
    MockHttpServletResponse response = new MockHttpServletResponse();
    CountingChain chain = new CountingChain();

    filter.doFilter(request, response, chain);

    assertCsrfInvalid(response);
    assertThat(chain.calls).isZero();
  }

  @Test
  void unsafeRequestWithDoubleSubmitAndSameOriginProceeds() throws Exception {
    AuthCsrfOriginFilter filter = new AuthCsrfOriginFilter(new ObjectMapper());
    MockHttpServletRequest request = request("/api/v1/practice/attempts");
    request.setCookies(
        new MockCookie(PlatformCookieNames.ACCESS, "access-token"),
        new MockCookie(PlatformCookieNames.CSRF, "csrf-token"));
    request.addHeader(PlatformCookieNames.CSRF_HEADER, "csrf-token");
    MockHttpServletResponse response = new MockHttpServletResponse();
    CountingChain chain = new CountingChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(chain.calls).isEqualTo(1);
  }

  @Test
  void examSimulatorIndependentSessionNamespaceDoesNotDependOnPlatformCsrfCookie() throws Exception {
    AuthCsrfOriginFilter filter = new AuthCsrfOriginFilter(new ObjectMapper());
    MockHttpServletRequest request =
        request("/api/v1/exam-simulator/sessions/session-1/enter");
    request.setCookies(new MockCookie(PlatformCookieNames.ACCESS, "ambient-platform-access-token"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    CountingChain chain = new CountingChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(chain.calls).isEqualTo(1);
  }

  private MockHttpServletRequest request(String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setScheme("https");
    request.setServerName("app.example.com");
    request.setServerPort(443);
    request.addHeader("Host", "app.example.com");
    request.addHeader("Origin", "https://app.example.com");
    return request;
  }

  private void assertCsrfInvalid(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
    assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
        .contains("\"errorCode\":\"CSRF_INVALID\"", "CSRF校验失败");
  }

  private static final class CountingChain implements FilterChain {
    private int calls;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response) throws IOException {
      calls++;
    }
  }
}
