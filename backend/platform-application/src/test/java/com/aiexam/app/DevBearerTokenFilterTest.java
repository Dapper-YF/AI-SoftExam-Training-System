package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiexam.common.security.DevBearerTokenFilter;
import com.aiexam.common.security.PlatformPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class DevBearerTokenFilterTest {
  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void devBearerDoesNotOverrideExistingPlatformPrincipal() throws Exception {
    PlatformPrincipal platformPrincipal =
        new PlatformPrincipal(1001L, "student_01", "session-1", List.of("STUDENT"), List.of(), 1);
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(platformPrincipal, null, List.of()));
    DevBearerTokenFilter filter = new DevBearerTokenFilter();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/questions");
    request.addHeader("Authorization", "Bearer dev-admin-token");

    filter.doFilter(request, new MockHttpServletResponse(), new CountingChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isSameAs(platformPrincipal);
  }

  @Test
  void devBearerCreatesPlatformPrincipalWithBackendPermissions() throws Exception {
    DevBearerTokenFilter filter = new DevBearerTokenFilter();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/qualifications");
    request.addHeader("Authorization", "Bearer dev-admin-token");

    filter.doFilter(request, new MockHttpServletResponse(), new CountingChain());

    Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    assertThat(principal).isInstanceOf(PlatformPrincipal.class);
    PlatformPrincipal platformPrincipal = (PlatformPrincipal) principal;
    assertThat(platformPrincipal.roles()).contains("ADMIN", "QUESTION_AUTHOR");
    assertThat(platformPrincipal.permissions()).contains("foundation:write", "question:create");
    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .extracting(Object::toString)
        .contains("ROLE_ADMIN", "foundation:write");
  }

  private static final class CountingChain implements FilterChain {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response) throws IOException {
    }
  }
}
