package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexam.app.migration.deploymentcontrol.AttemptState;
import com.aiexam.app.migration.v9.V9AuthorizedDatabaseOrchestrator;
import com.aiexam.app.migration.v9.V9ExecutionFence;
import com.aiexam.app.migration.v9.V9MigrationOnlyRunner;
import com.aiexam.common.security.PlatformCookieNames;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "AUTH_REAL_MYSQL_GATE", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthRealV10MysqlE2EGateTest {
  private static final String SCHEMA =
      "auth_real_v13_e2e_" + ProcessHandle.current().pid() + "_test";
  private static final Mysql MYSQL = Mysql.fromEnvironment();
  private static final String DEVELOPMENT_PASSWORD = "123456";
  private static final String DEV_STUDENT_PHONE = "13900002001";
  private static final String DEV_ADMIN_EMAIL = "admin.dev@aiexam.local";
  private static final String DEV_REVIEWER_EMAIL = "question-reviewer.dev@aiexam.local";
  private static String developmentFingerprint;

  @DynamicPropertySource
  static void mysqlProperties(DynamicPropertyRegistry registry) throws Exception {
    developmentFingerprint = MYSQL.developmentFingerprint();
    MYSQL.recreateSchema(SCHEMA);
    MYSQL.flyway(SCHEMA).migrate();
    registry.add("MYSQL_URL", () -> MYSQL.url(SCHEMA));
    registry.add("MYSQL_USERNAME", () -> MYSQL.username());
    registry.add("MYSQL_PASSWORD", () -> MYSQL.password());
    registry.add("spring.datasource.url", () -> MYSQL.url(SCHEMA));
    registry.add("spring.datasource.username", MYSQL::username);
    registry.add("spring.datasource.password", MYSQL::password);
    registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    registry.add("spring.flyway.enabled", () -> "false");
    registry.add("aiexam.auth.persistence", () -> "jdbc");
    registry.add("aiexam.auth.password-adapter", () -> "argon2id");
    registry.add("aiexam.auth.challenge.dev-delivery.enabled", () -> "true");
    registry.add("aiexam.auth.dev-accounts.enabled", () -> "true");
  }

  @LocalServerPort
  int port;

  @Autowired
  TestRestTemplate rest;

  @Autowired
  JdbcTemplate jdbc;

  private final CookieJar cookies = new CookieJar();

  @BeforeEach
  void useNonRetryingHttpClient() {
    rest.getRestTemplate().setRequestFactory(
        new JdkClientHttpRequestFactory(HttpClient.newHttpClient()));
  }

  @AfterAll
  void cleanup() throws Exception {
    MYSQL.dropSchema(SCHEMA);
    assertThat(MYSQL.schemaExists(SCHEMA)).isFalse();
    assertThat(MYSQL.developmentFingerprint()).isEqualTo(developmentFingerprint);
  }

  @Test
  void flywayValidateAndSecondMigrateAreCleanAtV13() {
    Flyway flyway = MYSQL.flyway(SCHEMA);
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
  }

  @Test
  void seededQuestionSubmitterAndIndependentReviewerPublisherLoadExactAuthorities() {
    CookieJar submitter = new CookieJar();
    csrf(submitter);
    assertOkJson(login(DEV_ADMIN_EMAIL, DEVELOPMENT_PASSWORD, submitter));
    ResponseEntity<Map> submitterContext = get("/api/v1/auth/me", submitter);
    assertOkJson(submitterContext);
    assertThat((List<String>) data(submitterContext).get("roles"))
        .containsExactly("ADMIN", "QUESTION_AUTHOR", "STUDENT");
    assertThat((List<String>) data(submitterContext).get("permissions"))
        .containsExactly("question:create", "question:import", "question:read",
            "question:submit-review", "question:update")
        .doesNotContain("question:review", "question:publish");

    CookieJar reviewer = new CookieJar();
    csrf(reviewer);
    assertOkJson(login(DEV_REVIEWER_EMAIL, DEVELOPMENT_PASSWORD, reviewer));
    ResponseEntity<Map> reviewerContext = get("/api/v1/auth/me", reviewer);
    assertOkJson(reviewerContext);
    assertThat((List<String>) data(reviewerContext).get("permissions"))
        .containsExactly("question:publish", "question:read", "question:review")
        .doesNotContain("question:create", "question:update", "question:submit-review");

    CookieJar unauthorized = new CookieJar();
    csrf(unauthorized);
    assertOkJson(login(DEV_STUDENT_PHONE, DEVELOPMENT_PASSWORD, unauthorized));
    ResponseEntity<Map> unauthorizedContext = get("/api/v1/auth/me", unauthorized);
    assertOkJson(unauthorizedContext);
    assertThat((List<String>) data(unauthorizedContext).get("roles")).containsExactly("STUDENT");
    assertThat((List<String>) data(unauthorizedContext).get("permissions")).isEmpty();
    assertAllDevelopmentCredentialsAreArgon2idOnly();
  }

  @Test
  void realCookieAuthorizationKeepsStudentOutAndRequiresTwoActorsForReviewPublish() {
    CookieJar admin = new CookieJar();
    csrf(admin);
    assertOkJson(login(DEV_ADMIN_EMAIL, DEVELOPMENT_PASSWORD, admin));

    CookieJar reviewer = new CookieJar();
    csrf(reviewer);
    assertOkJson(login(DEV_REVIEWER_EMAIL, DEVELOPMENT_PASSWORD, reviewer));

    CookieJar student = new CookieJar();
    csrf(student);
    assertOkJson(login(DEV_STUDENT_PHONE, DEVELOPMENT_PASSWORD, student));

    assertError(get("/api/v1/admin/questions", student), HttpStatus.FORBIDDEN, "FORBIDDEN");
    assertOkJson(get("/api/v1/admin/questions", admin));
    assertOkJson(get("/api/v1/admin/questions", reviewer));

    ResponseEntity<Map> adminSubmit = post("/api/v1/admin/questions/1/versions/1/submit-review",
        workflowBody(0), admin);
    assertThat(adminSubmit.getStatusCode()).isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);

    assertError(post("/api/v1/admin/questions/1/versions/1/review", reviewBody(0), admin),
        HttpStatus.FORBIDDEN, "FORBIDDEN");

    ResponseEntity<Map> reviewerPublish = post("/api/v1/admin/questions/1/versions/1/publish",
        workflowBody(0), reviewer);
    assertThat(reviewerPublish.getStatusCode()).isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
  }

  @Test
  void realRegisterLoginCookieRefreshLogoutAndNegativeCases() {
    csrf(cookies);

    ResponseEntity<Map> capabilities = get("/api/v1/auth/capabilities", cookies);
    assertOkJson(capabilities);
    Map<String, Object> capabilityData = data(capabilities);
    assertThat(((Map<?, ?>) capabilityData.get("usernamePassword")).get("status")).isEqualTo("AVAILABLE");
    assertThat(((Map<?, ?>) capabilityData.get("mobile")).get("status")).isEqualTo("AVAILABLE");
    assertThat(((Map<?, ?>) capabilityData.get("email")).get("status")).isEqualTo("DISABLED");
    assertThat(((Map<?, ?>) capabilityData.get("wechat")).get("status")).isEqualTo("DISABLED");
    assertV13EmailCheckRejectsInvalidEmailRules();

    String username = "student_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
    String mobile = "13800138000";
    String email = username + "@Example.COM";
    String password = randomPassword();
    register(username, mobile, email, password);

    assertConflict(registerAttempt(username, "13800138001", username + ".dupe@example.com", randomPassword()));
    assertConflict(registerAttempt(username + "x", mobile, username + ".mobile-dupe@example.com", randomPassword()));
    assertConflict(registerAttempt(username + "y", "13800138004", email, randomPassword()));
    assertError(registerAttempt(username + "bademail", "13800138005", "not-an-email", randomPassword()),
        HttpStatus.BAD_REQUEST, "AUTH_REQUEST_INVALID");

    ResponseEntity<Map> firstRateLimit = challenge("13800138002");
    assertThat(firstRateLimit.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    ResponseEntity<Map> rateLimited = challenge("13800138002");
    assertError(rateLimited, HttpStatus.TOO_MANY_REQUESTS, "AUTH_RATE_LIMITED");

    ResponseEntity<Map> invalidChallenge = registerWithCode(
        username + "bad", "13800138003", username + ".bad@example.com", randomPassword(), "000000");
    assertError(invalidChallenge, HttpStatus.BAD_REQUEST, "AUTH_CHALLENGE_INVALID");

    assertError(login(username, password, cookies), HttpStatus.BAD_REQUEST,
        "AUTH_REQUEST_INVALID");
    assertError(post("/api/v1/auth/password-reset-challenges", Map.of(
            "identifier", username,
            "requestId", UUID.randomUUID().toString()), cookies),
        HttpStatus.BAD_REQUEST,
        "AUTH_REQUEST_INVALID");
    assertError(post("/api/v1/auth/password-reset-challenges", Map.of(
            "identifier", email,
            "requestId", UUID.randomUUID().toString()), cookies),
        HttpStatus.SERVICE_UNAVAILABLE,
        "AUTH_PROVIDER_UNAVAILABLE");
    assertError(login(mobile, randomPassword(), cookies), HttpStatus.UNAUTHORIZED,
        "AUTH_INVALID_CREDENTIALS");

    ResponseEntity<Map> login = login(mobile, password, cookies);
    assertOkJson(login);
    String firstAccess = cookies.value(PlatformCookieNames.ACCESS);
    String firstRefresh = cookies.value(PlatformCookieNames.REFRESH);
    assertThat(firstAccess).isNotBlank();
    assertThat(firstRefresh).isNotBlank();

    ResponseEntity<Map> me = get("/api/v1/auth/me", cookies);
    assertOkJson(me);
    assertThat(((Map<?, ?>) data(me).get("user")).get("username")).isEqualTo(username);

    ResponseEntity<Map> refresh = post("/api/v1/auth/refresh", Map.of(), cookies);
    assertOkJson(refresh);
    String rotatedAccess = cookies.value(PlatformCookieNames.ACCESS);
    String rotatedRefresh = cookies.value(PlatformCookieNames.REFRESH);
    assertThat(rotatedAccess).isNotEqualTo(firstAccess);
    assertThat(rotatedRefresh).isNotEqualTo(firstRefresh);

    CookieJar oldAccessOnly = cookies.copy();
    oldAccessOnly.put(PlatformCookieNames.ACCESS, firstAccess);
    assertError(get("/api/v1/auth/me", oldAccessOnly), HttpStatus.UNAUTHORIZED, "AUTH_SESSION_EXPIRED");

    CookieJar oldRefreshReplay = cookies.copy();
    oldRefreshReplay.put(PlatformCookieNames.REFRESH, firstRefresh);
    assertError(post("/api/v1/auth/refresh", Map.of(), oldRefreshReplay), HttpStatus.UNAUTHORIZED, "AUTH_SESSION_EXPIRED");
    assertError(get("/api/v1/auth/me", cookies), HttpStatus.UNAUTHORIZED, "AUTH_SESSION_EXPIRED");

    ResponseEntity<Map> loginAgain = login(email, password, cookies);
    assertOkJson(loginAgain);

    String replacementPassword = randomPassword();
    resetPassword(mobile, replacementPassword);
    assertError(get("/api/v1/auth/me", cookies), HttpStatus.UNAUTHORIZED, "AUTH_SESSION_EXPIRED");
    assertError(login(email, password, cookies), HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS");
    assertOkJson(login(email, replacementPassword, cookies));

    ResponseEntity<Map> logout = post("/api/v1/auth/logout", Map.of(), cookies);
    assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertError(get("/api/v1/auth/me", cookies), HttpStatus.UNAUTHORIZED, "AUTH_SESSION_EXPIRED");
  }

  private void register(String username, String mobile, String email, String password) {
    ResponseEntity<Map> response = registerAttempt(username, mobile, email, password);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(cookies.value(PlatformCookieNames.ACCESS)).isNull();
    assertThat(cookies.value(PlatformCookieNames.REFRESH)).isNull();
  }

  private ResponseEntity<Map> registerAttempt(String username, String mobile, String email, String password) {
    ResponseEntity<Map> challenge = challenge(mobile);
    assertThat(challenge.getStatusCode())
        .as("registration challenge response: %s", challenge.getBody())
        .isEqualTo(HttpStatus.ACCEPTED);
    String challengeId = (String) data(challenge).get("challengeId");
    String code = devCode(challengeId);
    return post("/api/v1/auth/register", Map.of(
        "username", username,
        "password", password,
        "displayName", "学生" + username,
        "mobile", mobile,
        "email", email,
        "challengeId", challengeId,
        "verificationCode", code,
        "requestId", UUID.randomUUID().toString()), cookies);
  }

  private ResponseEntity<Map> registerWithCode(String username, String mobile, String email, String password, String code) {
    ResponseEntity<Map> challenge = challenge(mobile);
    assertThat(challenge.getStatusCode())
        .as("registration challenge response: %s", challenge.getBody())
        .isEqualTo(HttpStatus.ACCEPTED);
    String challengeId = (String) data(challenge).get("challengeId");
    return post("/api/v1/auth/register", Map.of(
        "username", username,
        "password", password,
        "displayName", "学生" + username,
        "mobile", mobile,
        "email", email,
        "challengeId", challengeId,
        "verificationCode", code,
        "requestId", UUID.randomUUID().toString()), cookies);
  }

  private void resetPassword(String identifier, String newPassword) {
    ResponseEntity<Map> challenge = post("/api/v1/auth/password-reset-challenges", Map.of(
        "identifier", identifier,
        "requestId", UUID.randomUUID().toString()), cookies);
    assertThat(challenge.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    String challengeId = (String) data(challenge).get("challengeId");
    String code = devCode(challengeId);
    ResponseEntity<Map> reset = post("/api/v1/auth/password/reset", Map.of(
        "identifier", identifier,
        "challengeId", challengeId,
        "verificationCode", code,
        "newPassword", newPassword,
        "requestId", UUID.randomUUID().toString()), cookies);
    assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  private ResponseEntity<Map> challenge(String mobile) {
    return post("/api/v1/auth/registration-challenges", Map.of(
        "mobile", mobile,
        "requestId", UUID.randomUUID().toString()), cookies);
  }

  private ResponseEntity<Map> login(String identifier, String password, CookieJar jar) {
    return post("/api/v1/auth/login", Map.of("identifier", identifier, "password", password), jar);
  }

  private static String randomPassword() {
    return UUID.randomUUID().toString().replace("-", "") + "Aa9!";
  }

  private void csrf(CookieJar jar) {
    ResponseEntity<Void> response = rest.exchange(url("/api/v1/auth/csrf"), HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Void.class);
    jar.store(response.getHeaders());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(jar.value("XSRF-TOKEN")).isNotBlank();
  }

  private String devCode(String challengeId) {
    ResponseEntity<Map> response = get("/api/v1/auth/dev/challenges/" + challengeId + "/verification-code", cookies);
    assertOkJson(response);
    return (String) data(response).get("verificationCode");
  }

  private ResponseEntity<Map> get(String path, CookieJar jar) {
    ResponseEntity<Map> response = rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers(jar, false)), Map.class);
    jar.store(response.getHeaders());
    return response;
  }

  private ResponseEntity<Map> post(String path, Map<String, Object> body, CookieJar jar) {
    ResponseEntity<Map> response = rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers(jar, true)), Map.class);
    jar.store(response.getHeaders());
    return response;
  }

  private HttpHeaders headers(CookieJar jar, boolean csrf) {
    HttpHeaders headers = new HttpHeaders();
    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setHost(InetSocketAddress.createUnresolved("127.0.0.1", port));
    headers.set(HttpHeaders.REFERER, "http://127.0.0.1:" + port + "/");
    String cookie = jar.cookieHeader();
    if (!cookie.isBlank()) {
      headers.set(HttpHeaders.COOKIE, cookie);
    }
    if (csrf) {
      headers.add("X-XSRF-TOKEN", jar.value("XSRF-TOKEN"));
    }
    return headers;
  }

  private String url(String path) {
    return "http://127.0.0.1:" + port + path;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> data(ResponseEntity<Map> response) {
    return (Map<String, Object>) response.getBody().get("data");
  }

  private void assertOkJson(ResponseEntity<Map> response) {
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/json;charset=UTF-8");
    assertThat(response.getBody()).containsEntry("success", true);
  }

  private void assertConflict(ResponseEntity<Map> response) {
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody().get("errorCode")).as("errorCode")
        .isIn("AUTH_USERNAME_CONFLICT", "AUTH_MOBILE_CONFLICT", "AUTH_IDENTIFIER_CONFLICT");
  }

  private void assertError(ResponseEntity<Map> response, HttpStatus status, String errorCode) {
    assertThat(response.getStatusCode()).isEqualTo(status);
    assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/json;charset=UTF-8");
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", errorCode);
    assertThat(String.valueOf(response.getBody().get("message"))).doesNotContain("Exception", "java.", "SQL");
    assertThat(response.getBody()).containsKey("traceId");
  }

  private Map<String, Object> workflowBody(long expectedRowVersion) {
    return Map.of(
        "clientMutationId", UUID.randomUUID().toString(),
        "expectedRowVersion", expectedRowVersion);
  }

  private Map<String, Object> reviewBody(long expectedRowVersion) {
    return Map.of(
        "clientMutationId", UUID.randomUUID().toString(),
        "expectedRowVersion", expectedRowVersion,
        "decision", "APPROVED",
        "reason", "permission boundary");
  }

  private void assertAllDevelopmentCredentialsAreArgon2idOnly() {
    List<String> hashes = jdbc.queryForList("""
        SELECT c.secret_hash
          FROM sys_user u
          JOIN sys_auth_credential c ON c.user_id = u.id
         WHERE u.username IN ('student','admin','question_reviewer')
           AND c.credential_type = 'PASSWORD'
           AND c.status = 'ACTIVE'
         ORDER BY u.username
        """, String.class);
    assertThat(hashes).hasSize(3);
    assertThat(hashes).allSatisfy(hash -> assertThat(hash)
        .startsWith("$argon2id$")
        .doesNotContain(DEVELOPMENT_PASSWORD));
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*)
          FROM sys_user u
         WHERE u.username IN ('student','admin','question_reviewer')
           AND u.password_hash = ?
        """, Integer.class, DEVELOPMENT_PASSWORD)).isZero();
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*)
          FROM sys_auth_credential c
         WHERE c.credential_subject IN ('student','admin','question_reviewer')
           AND c.secret_hash = ?
        """, Integer.class, DEVELOPMENT_PASSWORD)).isZero();
  }

  private void assertV13EmailCheckRejectsInvalidEmailRules() {
    Long userId = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1300130000 FROM sys_user", Long.class);
    jdbc.update(
        """
            INSERT INTO sys_user
              (id, username, password_hash, display_name, mobile, email, status, last_login_at,
               created_at, updated_at, created_by, updated_by, deleted)
            VALUES (?, ?, ?, ?, NULL, NULL, 'ENABLED', NULL, NOW(3), NOW(3), NULL, NULL, 0)
            """,
        userId,
        "email_check_" + userId,
        "$argon2id$v=19$m=19456,t=2,p=1$manual$manual",
        "邮箱CHECK测试");
    List<InvalidEmailCase> invalidEmails = List.of(
        new InvalidEmailCase("local dot start", ".bad@example.com"),
        new InvalidEmailCase("local dot end", "bad.@example.com"),
        new InvalidEmailCase("local consecutive dots", "bad..name@example.com"),
        new InvalidEmailCase("local part too long", "a".repeat(65) + "@example.com"),
        new InvalidEmailCase("overall email too long",
            "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61) + ".com"),
        new InvalidEmailCase("single-label domain", "student@example"),
        new InvalidEmailCase("domain label leading hyphen", "student@-example.com"),
        new InvalidEmailCase("domain label trailing hyphen", "student@example-.com"),
        new InvalidEmailCase("domain empty label consecutive dots", "student@example..com"),
        new InvalidEmailCase("domain empty label leading dot", "student@.example.com"),
        new InvalidEmailCase("domain empty label trailing dot", "student@example.com."),
        new InvalidEmailCase("domain illegal label character", "student@exa_mple.com"));
    for (int index = 0; index < invalidEmails.size(); index++) {
      long identifierId = userId + index + 1;
      InvalidEmailCase invalidEmail = invalidEmails.get(index);
      assertThatThrownBy(() -> jdbc.update(
          """
              INSERT INTO sys_auth_login_identifier
                (id, user_id, identifier_type, normalized_identifier, verified, enabled,
                 verified_at, disabled_at, created_at, updated_at, version)
              VALUES (?, ?, 'EMAIL', ?, 1, 1, NOW(3), NULL, NOW(3), NOW(3), 0)
          """,
          identifierId,
          userId,
          invalidEmail.value()))
          .as("V13 EMAIL CHECK must reject %s: %s", invalidEmail.label(), invalidEmail.value())
          .isInstanceOfSatisfying(DataAccessException.class, ex ->
              assertThat(rootCause(ex).getMessage()).contains("ck_auth_login_identifier_email"));
      assertThat(jdbc.queryForObject(
          "SELECT COUNT(*) FROM sys_auth_login_identifier WHERE id = ?",
          Integer.class,
          identifierId))
          .as("failed CHECK insert must not leave identifier row for %s", invalidEmail.label())
          .isZero();
    }
  }

  private static Throwable rootCause(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private record InvalidEmailCase(String label, String value) {
  }

  private static final class CookieJar {
    private final Map<String, String> values = new LinkedHashMap<>();

    void store(HttpHeaders headers) {
      for (String value : headers.getOrEmpty(HttpHeaders.SET_COOKIE)) {
        String pair = value.split(";", 2)[0];
        int equals = pair.indexOf('=');
        if (equals > 0) {
          String name = pair.substring(0, equals);
          String cookieValue = pair.substring(equals + 1);
          if (cookieValue.isBlank()) {
            values.remove(name);
          } else {
            values.put(name, cookieValue);
          }
        }
      }
    }

    String value(String name) {
      return values.get(name);
    }

    void put(String name, String value) {
      values.put(name, value);
    }

    CookieJar copy() {
      CookieJar copy = new CookieJar();
      copy.values.putAll(values);
      return copy;
    }

    String cookieHeader() {
      return values.entrySet().stream()
          .map(entry -> entry.getKey() + "=" + entry.getValue())
          .reduce((left, right) -> left + "; " + right)
          .orElse("");
    }
  }

  private record Mysql(String testUrl, String username, String password) {
    static Mysql fromEnvironment() {
      return new Mysql(
          valueOrDefault("MYSQL_TEST_URL", "jdbc:mysql://127.0.0.1:33307/ai_exam_platform_test?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"),
          valueOrDefault("MYSQL_TEST_USERNAME", "root"),
          valueOrDefault("MYSQL_TEST_PASSWORD", ""));
    }

    String serverUrl() {
      int queryAt = testUrl.indexOf('?');
      String base = queryAt >= 0 ? testUrl.substring(0, queryAt) : testUrl;
      String query = queryAt >= 0 ? testUrl.substring(queryAt) : "";
      return base.substring(0, base.lastIndexOf('/') + 1) + query;
    }

    String url(String schema) {
      requireTestSchema(schema);
      int queryAt = testUrl.indexOf('?');
      String base = queryAt >= 0 ? testUrl.substring(0, queryAt) : testUrl;
      String query = queryAt >= 0 ? testUrl.substring(queryAt) : "";
      return base.substring(0, base.lastIndexOf('/') + 1) + schema + query;
    }

    Flyway flyway(String schema) {
      requireV9PayloadOverridesInTestClasspath();
      String schemaUrl = url(schema);
      TestExecutionFence fence = new TestExecutionFence();
      return V9MigrationOnlyRunner.build(schemaUrl, username, password,
          new V9AuthorizedDatabaseOrchestrator(fence, () -> {
            try {
              return DriverManager.getConnection(schemaUrl, username, password);
            } catch (SQLException exception) {
              throw new IllegalStateException("Cannot open V9 read-only verification connection",
                  exception);
            }
          }));
    }

    private static void requireV9PayloadOverridesInTestClasspath() {
      List.of(
              "01_qualification_expand.sql",
              "02_subject_expand.sql",
              "03_rule_expand.sql",
              "04_qualification_harden.sql",
              "05_subject_harden.sql",
              "06_rule_harden.sql",
              "d_data.sql")
          .forEach(name -> {
            try {
              String resourcePath = "db/migration-payload/v9/" + name;
              var resource = AuthRealV10MysqlE2EGateTest.class.getClassLoader()
                  .getResource(resourcePath);
              if (resource == null) {
                throw new IllegalStateException("Missing V9 test payload override: " + name);
              }
              if (!resource.toString().contains("/target/test-classes/")
                  && !resource.toString().contains("\\target\\test-classes\\")) {
                throw new IllegalStateException("V9 test payload override is not first on classpath: "
                    + resource);
              }
              try (var input = resource.openStream()) {
                String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                if (content.contains("\r\n")) {
                  throw new IllegalStateException("V9 test payload override is not LF-only: " + name);
                }
              }
            } catch (Exception exception) {
              throw new IllegalStateException("Cannot verify V9 test payload override " + name,
                  exception);
            }
          });
    }

    void recreateSchema(String schema) throws SQLException {
      dropSchema(schema);
      try (Connection connection = DriverManager.getConnection(serverUrl(), username, password);
          Statement statement = connection.createStatement()) {
        statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
      }
    }

    void dropSchema(String schema) throws SQLException {
      requireTestSchema(schema);
      try (Connection connection = DriverManager.getConnection(serverUrl(), username, password);
          Statement statement = connection.createStatement()) {
        statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
      }
    }

    boolean schemaExists(String schema) throws SQLException {
      requireTestSchema(schema);
      try (Connection connection = DriverManager.getConnection(serverUrl(), username, password);
          var statement = connection.prepareStatement(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?")) {
        statement.setString(1, schema);
        try (var rs = statement.executeQuery()) {
          return rs.next() && rs.getInt(1) > 0;
        }
      }
    }

    String developmentFingerprint() throws SQLException {
      try (Connection connection = DriverManager.getConnection(serverUrl(), username, password);
          Statement statement = connection.createStatement();
          var rs = statement.executeQuery("""
              SELECT COALESCE(SHA2(GROUP_CONCAT(CONCAT(schema_name, ':', table_count) ORDER BY schema_name SEPARATOR '|'), 256), 'EMPTY')
                FROM (
                  SELECT s.schema_name, COUNT(t.table_name) AS table_count
                    FROM information_schema.schemata s
                    LEFT JOIN information_schema.tables t ON t.table_schema = s.schema_name
                   WHERE s.schema_name NOT LIKE 'auth\\_real\\_v10\\_e2e%\\_test'
                     AND s.schema_name NOT IN ('information_schema','performance_schema','mysql','sys')
                   GROUP BY s.schema_name
                ) fp
              """)) {
        assertThat(rs.next()).isTrue();
        return rs.getString(1);
      }
    }

    private static String valueOrDefault(String name, String fallback) {
      String value = System.getenv(name);
      return value == null || value.isBlank() ? fallback : value;
    }

    private static void requireTestSchema(String schema) {
      if (!schema.equals(SCHEMA) || !schema.endsWith("_test")) {
        throw new IllegalArgumentException("Refusing non-auth-real test schema: " + schema);
      }
    }
  }

  private static final class TestExecutionFence implements V9ExecutionFence {
    private AttemptState state = AttemptState.FLYWAY_STARTED;

    @Override
    public void verifyBoundary(String boundary, AttemptState expectedState) {
      if (state != expectedState) {
        throw new IllegalStateException(
            "Unexpected V9 test boundary " + boundary + ": " + state + " != " + expectedState);
      }
    }

    @Override
    public void transition(AttemptState from, AttemptState to, String evidenceDigest) {
      if (state != from || evidenceDigest == null || evidenceDigest.isBlank()) {
        throw new IllegalStateException("Invalid V9 test transition " + from + " -> " + to);
      }
      state = to;
    }
  }
}
