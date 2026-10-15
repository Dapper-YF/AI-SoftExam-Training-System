package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiexam.common.security.JsonAuthenticationEntryPoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

class JsonAuthenticationEntryPointTest {
  @Test
  void unauthenticatedApiUsesFormalSessionExpiredCode() throws Exception {
    JsonAuthenticationEntryPoint entryPoint = new JsonAuthenticationEntryPoint(new ObjectMapper());
    MockHttpServletResponse response = new MockHttpServletResponse();

    entryPoint.commence(
        new MockHttpServletRequest("GET", "/api/v1/questions"),
        response,
        new BadCredentialsException("bad"));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
    assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
        .contains("\"errorCode\":\"AUTH_SESSION_EXPIRED\"", "未登录或登录已过期")
        .doesNotContain("BadCredentialsException", "java.lang", "SQL");
  }
}
