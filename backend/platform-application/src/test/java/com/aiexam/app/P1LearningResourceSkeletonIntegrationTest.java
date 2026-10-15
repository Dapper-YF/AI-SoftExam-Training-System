package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "aiexam.auth.persistence=unavailable",
        "aiexam.auth.password-adapter=unavailable",
        "aiexam.auth.challenge.dev-delivery.enabled=true",
        "aiexam.security.dev-bearer.enabled=true",
        "aiexam.test.context=p1-learning-resource"
    }
)
class P1LearningResourceSkeletonIntegrationTest {
  @LocalServerPort
  int port;

  private final TestRestTemplate restTemplate = new TestRestTemplate();

  @Test
  void learningResourceListReturnsUnifiedFeatureDisabledEnvelope() {
    ResponseEntity<Map> response = get("/api/v1/learning/resources");

    assertFeatureDisabled(response, "P1_LEARNING_RESOURCE");
  }

  @Test
  void essayExampleListReturnsUnifiedFeatureDisabledEnvelope() {
    ResponseEntity<Map> response = get("/api/v1/essay-learning/examples");

    assertFeatureDisabled(response, "P1_ESSAY_EXAMPLE");
  }

  @Test
  void essayTemplateListReturnsUnifiedFeatureDisabledEnvelope() {
    ResponseEntity<Map> response = get("/api/v1/essay-learning/templates");

    assertFeatureDisabled(response, "P1_ESSAY_TEMPLATE");
  }

  private void assertFeatureDisabled(ResponseEntity<Map> response, String featureCode) {
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "FEATURE_DISABLED");
    assertThat(response.getBody()).containsEntry("message", "功能未启用");
    assertThat(response.getBody()).containsKeys("data", "traceId");

    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    assertThat(data).containsEntry("featureCode", featureCode);
    assertThat(data).containsEntry("enabled", false);
  }

  private ResponseEntity<Map> get(String path) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("dev-student-token");
    return restTemplate.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
