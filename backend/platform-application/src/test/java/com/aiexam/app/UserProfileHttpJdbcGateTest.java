package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiexam.common.security.PlatformCookieNames;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "aiexam.auth.persistence=unavailable",
        "aiexam.auth.password-adapter=unavailable",
        "aiexam.auth.challenge.dev-delivery.enabled=true",
        "aiexam.security.dev-bearer.enabled=true",
        "spring.flyway.enabled=false"
    }
)
class UserProfileHttpJdbcGateTest {
  private static final String DATABASE_NAME = "profile_gate_" + UUID.randomUUID().toString().replace("-", "");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:h2:mem:" + DATABASE_NAME + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
  }

  @LocalServerPort
  int port;

  @Autowired
  TestRestTemplate restTemplate;

  @Autowired
  JdbcTemplate jdbcTemplate;

  @BeforeEach
  void resetUsers() {
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS sys_user (
          id BIGINT PRIMARY KEY,
          username VARCHAR(64) NOT NULL UNIQUE,
          password_hash VARCHAR(255) NOT NULL,
          password_changed_at TIMESTAMP NULL,
          display_name VARCHAR(64) NOT NULL,
          mobile VARCHAR(32) NULL,
          mobile_verified_at TIMESTAMP NULL,
          email VARCHAR(128) NULL,
          status VARCHAR(24) NOT NULL,
          last_login_at TIMESTAMP NULL,
          failed_login_count INT NOT NULL DEFAULT 0,
          last_failed_login_at TIMESTAMP NULL,
          locked_until TIMESTAMP NULL,
          auth_version BIGINT NOT NULL DEFAULT 0,
          version BIGINT NOT NULL DEFAULT 0,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbcTemplate.execute("DELETE FROM sys_user");
    insertUser(1L, "dev-admin", "Owner", "+8613900001234", "owner@example.com", 7L);
    insertUser(2L, "other-user", "Other", "+8613900005678", "other@example.com", 3L);
  }

  @Test
  void anonymousProfileRequestReturns401() {
    ResponseEntity<Map> response = restTemplate.getForEntity(url("/api/v1/profile/me"), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "AUTH_SESSION_EXPIRED");
  }

  @Test
  void profileResponseMasksSensitiveFieldsAndNeverReturnsCredentials() {
    ResponseEntity<Map> response = getAsAdmin();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> data = data(response);
    assertThat(data).containsEntry("userId", 1);
    assertThat(data).containsEntry("maskedMobile", "+86139****1234");
    assertThat(data).containsEntry("maskedEmail", "o***@example.com");
    assertThat(response.getBody().toString())
        .doesNotContain("+8613900001234", "owner@example.com", "dev-admin-secret-hash", "passwordHash");
  }

  @Test
  void ownerUpdateChangesOnlyAuthenticatedUser() {
    ResponseEntity<Map> response = putAsAdmin(Map.of("displayName", "Owner Updated", "version", 7));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(response)).containsEntry("displayName", "Owner Updated").containsEntry("version", 8);
    assertThat(jdbcTemplate.queryForObject("SELECT display_name FROM sys_user WHERE id = 1", String.class))
        .isEqualTo("Owner Updated");
    assertThat(jdbcTemplate.queryForObject("SELECT display_name FROM sys_user WHERE id = 2", String.class))
        .isEqualTo("Other");
  }

  @Test
  void platformCookieMutationWithoutCsrfIsRejectedBeforeOwnerWrite() {
    HttpHeaders headers = adminHeaders();
    headers.set(HttpHeaders.COOKIE, PlatformCookieNames.ACCESS + "=stale-access-token");
    ResponseEntity<Map> response = restTemplate.exchange(
        url("/api/v1/profile/me"),
        HttpMethod.PUT,
        new HttpEntity<>(Map.of("displayName", "Blocked", "version", 7), headers),
        Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody()).containsEntry("errorCode", "CSRF_INVALID");
    assertThat(jdbcTemplate.queryForObject("SELECT display_name FROM sys_user WHERE id = 1", String.class))
        .isEqualTo("Owner");
  }

  @Test
  void staleVersionReturnsSafe409EnvelopeWithoutWriting() {
    ResponseEntity<Map> response = putAsAdmin(Map.of("displayName", "Stale", "version", 6));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "PROFILE_CONFLICT");
    assertThat(response.getBody()).containsKeys("message", "traceId");
    assertThat(response.getBody().toString()).doesNotContain("owner@example.com", "+8613900001234");
    assertThat(jdbcTemplate.queryForObject("SELECT display_name FROM sys_user WHERE id = 1", String.class))
        .isEqualTo("Owner");
  }

  private ResponseEntity<Map> getAsAdmin() {
    return restTemplate.exchange(
        url("/api/v1/profile/me"),
        HttpMethod.GET,
        new HttpEntity<>(adminHeaders()),
        Map.class);
  }

  private ResponseEntity<Map> putAsAdmin(Map<String, Object> body) {
    return restTemplate.exchange(
        url("/api/v1/profile/me"),
        HttpMethod.PUT,
        new HttpEntity<>(body, adminHeaders()),
        Map.class);
  }

  private HttpHeaders adminHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("dev-admin-token");
    return headers;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> data(ResponseEntity<Map> response) {
    return (Map<String, Object>) response.getBody().get("data");
  }

  private void insertUser(
      long id,
      String username,
      String displayName,
      String mobile,
      String email,
      long version
  ) {
    Timestamp now = Timestamp.from(Instant.parse("2026-07-19T00:00:00Z"));
    jdbcTemplate.update("""
        INSERT INTO sys_user (
          id, username, password_hash, password_changed_at, display_name, mobile,
          mobile_verified_at, email, status, failed_login_count, auth_version, version,
          created_at, updated_at, deleted
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ENABLED', 0, 0, ?, ?, ?, 0)
        """,
        id, username, username + "-secret-hash", now, displayName, mobile, now, email, version, now, now);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
