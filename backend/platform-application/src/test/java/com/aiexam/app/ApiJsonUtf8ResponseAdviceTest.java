package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiexam.common.api.ApiJsonUtf8ResponseAdvice;
import com.aiexam.common.api.ApiResponse;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;

class ApiJsonUtf8ResponseAdviceTest {
  @Test
  void apiResponseForcesJsonUtf8ContentTypeForChineseMessages() {
    ApiJsonUtf8ResponseAdvice advice = new ApiJsonUtf8ResponseAdvice();
    SimpleServerHttpResponse response = new SimpleServerHttpResponse();

    Object body = advice.beforeBodyWrite(
        ApiResponse.fail("AUTH_INVALID_CREDENTIALS", "手机号或邮箱、密码错误", null),
        null,
        MediaType.APPLICATION_JSON,
        null,
        null,
        response);

    assertThat(body).isInstanceOf(ApiResponse.class);
    assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/json;charset=UTF-8");
  }

  private static final class SimpleServerHttpResponse implements ServerHttpResponse {
    private final HttpHeaders headers = new HttpHeaders();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();

    @Override
    public void setStatusCode(HttpStatusCode status) {
    }

    @Override
    public HttpHeaders getHeaders() {
      return headers;
    }

    @Override
    public OutputStream getBody() {
      return body;
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
  }
}
