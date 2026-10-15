package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "aiexam.auth.persistence=unavailable",
        "aiexam.auth.password-adapter=unavailable",
        "aiexam.auth.challenge.dev-delivery.enabled=true",
        "aiexam.security.dev-bearer.enabled=true",
        "aiexam.test.context=api-envelope"
    }
)
class ApiEnvelopeIntegrationTest {
  @LocalServerPort
  int port;

  @Autowired
  TestRestTemplate restTemplate;

  @Autowired
  JdbcTemplate jdbcTemplate;

  @Autowired
  ApplicationContext applicationContext;

  @Autowired
  Environment environment;

  @BeforeEach
  void resetQuestionTables() {
    createQuestionTables();
    createAiTables();
    jdbcTemplate.execute("DELETE FROM ai_task");
    jdbcTemplate.execute("DELETE FROM q_question_knowledge_binding");
    jdbcTemplate.execute("DELETE FROM q_choice_option");
    jdbcTemplate.execute("DELETE FROM q_sub_question");
    jdbcTemplate.execute("DELETE FROM q_question_stem");
    jdbcTemplate.execute("DELETE FROM q_question_version");
    jdbcTemplate.execute("DELETE FROM q_question");
    insertAiTaskSample();
  }

  @Test
  void healthReturnsUnifiedSuccessEnvelope() {
    ResponseEntity<Map> response = get("/api/v1/health");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsEntry("success", true);
    assertThat(response.getBody()).containsEntry("errorCode", "OK");
    assertThat(response.getBody()).containsKey("traceId");
  }

  @Test
  void mysqlProfileExecutesProductionRuntimeSchemaValidator() {
    if (environment.matchesProfiles("mysql")) {
      assertThat(applicationContext.getBean("v9RuntimeSchemaValidated", Boolean.class)).isTrue();
    } else {
      assertThat(applicationContext.containsBean("v9RuntimeSchemaValidated")).isFalse();
    }
  }

  @Test
  void questionDetailReturnsUnifiedQuestionShape() {
    insertPublishedQuestionSample();

    ResponseEntity<Map> response = getStudent("/api/v1/questions/10001");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    assertThat(data).containsKeys(
        "questionId",
        "questionType",
        "stems",
        "subQuestions",
        "answerFormat",
        "knowledgeBindings");
    assertThat(data).doesNotContainKey("solutions");
  }

  @Test
  void validationErrorReturnsUnifiedEnvelope() {
    ResponseEntity<Map> response = getStudent("/api/v1/questions/0");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "VALIDATION_ERROR");
    assertThat(response.getBody()).containsKey("traceId");
  }

  @Test
  void resourceNotFoundReturnsUnifiedEnvelope() {
    ResponseEntity<Map> response = getStudent("/api/v1/questions/99999");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "NOT_FOUND");
  }

  @Test
  void unauthenticatedRequestReturnsUnifiedEnvelope() {
    ResponseEntity<Map> response = get("/api/v1/security/authenticated-probe");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "AUTH_SESSION_EXPIRED");
  }

  @Test
  void forbiddenRequestReturnsUnifiedEnvelope() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("dev-student-token");
    ResponseEntity<Map> response = restTemplate.exchange(
        url("/api/v1/security/admin-probe"),
        HttpMethod.GET,
        new HttpEntity<>(headers),
        Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "FORBIDDEN");
  }

  @Test
  void rateLimitedRequestReturnsUnifiedEnvelope() {
    ResponseEntity<Map> response = get("/api/v1/security/rate-limit-probe");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "RATE_LIMITED");
  }

  @Test
  void internalErrorReturnsUnifiedEnvelope() {
    ResponseEntity<Map> response = get("/api/v1/security/internal-error-probe");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "INTERNAL_ERROR");
  }

  @Test
  void aiTaskReturnsUnifiedEnvelope() {
    ResponseEntity<Map> response = getStudent("/api/v1/ai/tasks/8001");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    assertThat(data).containsKeys("id", "bizType", "bizId", "scenario", "status", "retryCount", "createdAt");
  }

  private ResponseEntity<Map> get(String path) {
    return restTemplate.getForEntity(url(path), Map.class);
  }

  private ResponseEntity<Map> getStudent(String path) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("dev-student-token");
    return restTemplate.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private void insertPublishedQuestionSample() {
    jdbcTemplate.update("""
        INSERT INTO q_question (
          id, qualification_id, subject_id, question_type, title, year_no, difficulty,
          status, content_version, created_at, updated_at, deleted
        ) VALUES (10001, 1, 2, 'CASE', '样例案例题', 2026, 'MEDIUM', 'PUBLISHED', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_question_stem (
          id, question_id, stem_type, content, sort_order, created_at, updated_at, deleted
        ) VALUES (11001, 10001, 'CASE_MATERIAL', '某项目正在进行进度压缩与风险复盘。', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_sub_question (
          id, question_id, sub_question_no, sub_question_type, content, score, answer_format, grading_mode,
          sort_order, created_at, updated_at, deleted
        ) VALUES (12001, 10001, 1, 'SHORT_ANSWER', '说明可以采用的进度压缩措施。', 8, 'TEXT', 'AI_ASSISTED',
          1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_question_knowledge_binding (
          id, question_id, sub_question_id, target_type, target_id, knowledge_type, knowledge_id, created_at
        ) VALUES (14001, 10001, 12001, 'SUB_QUESTION', 12001, 'SYLLABUS', 20001, CURRENT_TIMESTAMP)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_question_version (id, question_id, version_no, status, title, deleted)
        VALUES (16001, 10001, 1, 'PUBLISHED', 'Published sample', 0)
        """);
    jdbcTemplate.update("UPDATE q_question SET current_published_version_id = 16001 WHERE id = 10001");
    jdbcTemplate.update("UPDATE q_question_stem SET question_version_id = 16001 WHERE question_id = 10001");
    jdbcTemplate.update("UPDATE q_sub_question SET question_version_id = 16001 WHERE question_id = 10001");
    jdbcTemplate.update("""
        UPDATE q_question_knowledge_binding
        SET question_version_id = 16001
        WHERE question_id = 10001
        """);
  }

  private void createQuestionTables() {
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS q_question (
          id BIGINT NOT NULL PRIMARY KEY,
          qualification_id BIGINT NOT NULL,
          subject_id BIGINT NOT NULL,
          question_type VARCHAR(32) NOT NULL,
          title VARCHAR(180) NULL,
          year_no INT NULL,
          difficulty VARCHAR(24) NULL,
          source_type VARCHAR(32) NULL,
          source_ref VARCHAR(255) NULL,
          status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
          content_version INT NOT NULL DEFAULT 1,
          current_working_version_id BIGINT NULL,
          current_published_version_id BIGINT NULL,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS q_question_version (
          id BIGINT NOT NULL PRIMARY KEY,
          question_id BIGINT NOT NULL,
          version_no INT NOT NULL,
          status VARCHAR(24) NOT NULL,
          title VARCHAR(180) NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS q_question_stem (
          id BIGINT NOT NULL PRIMARY KEY,
          question_id BIGINT NOT NULL,
          question_version_id BIGINT NULL,
          stem_type VARCHAR(32) NOT NULL,
          content LONGTEXT NULL,
          attachment_url VARCHAR(500) NULL,
          sort_order INT NOT NULL DEFAULT 0,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS q_sub_question (
          id BIGINT NOT NULL PRIMARY KEY,
          question_id BIGINT NOT NULL,
          question_version_id BIGINT NULL,
          sub_question_no INT NOT NULL,
          sub_question_type VARCHAR(32) NOT NULL,
          content LONGTEXT NOT NULL,
          score DECIMAL(8,2) NULL,
          answer_format VARCHAR(32) NOT NULL,
          grading_mode VARCHAR(32) NOT NULL DEFAULT 'AUTO',
          sort_order INT NOT NULL DEFAULT 0,
          metadata_json LONGTEXT NULL,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS q_choice_option (
          id BIGINT NOT NULL PRIMARY KEY,
          sub_question_id BIGINT NOT NULL,
          option_key VARCHAR(16) NOT NULL,
          option_content LONGTEXT NOT NULL,
          sort_order INT NOT NULL DEFAULT 0,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS q_question_knowledge_binding (
          id BIGINT NOT NULL PRIMARY KEY,
          question_id BIGINT NOT NULL,
          question_version_id BIGINT NULL,
          sub_question_id BIGINT NULL,
          target_type VARCHAR(32) NOT NULL,
          target_id BIGINT NOT NULL,
          knowledge_type VARCHAR(32) NOT NULL,
          knowledge_id BIGINT NOT NULL,
          created_at TIMESTAMP NOT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
  }

  private void createAiTables() {
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS ai_task (
          id BIGINT NOT NULL PRIMARY KEY,
          user_id BIGINT NULL,
          biz_type VARCHAR(64) NOT NULL,
          biz_id BIGINT NULL,
          scenario VARCHAR(96) NOT NULL,
          model_code VARCHAR(64) NULL,
          prompt_template_id BIGINT NULL,
          request_hash VARCHAR(160) NULL,
          request_json LONGTEXT NULL,
          status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
          priority INT NOT NULL DEFAULT 5,
          retry_count INT NOT NULL DEFAULT 0,
          max_retry_count INT NOT NULL DEFAULT 3,
          started_at TIMESTAMP NULL,
          completed_at TIMESTAMP NULL,
          error_message VARCHAR(500) NULL,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
  }

  private void insertAiTaskSample() {
    jdbcTemplate.update("""
        INSERT INTO ai_task (
          id, user_id, biz_type, biz_id, scenario, request_hash, status, priority,
          retry_count, max_retry_count, created_at, updated_at, deleted
        ) VALUES (8001, NULL, 'ESSAY_REVIEW', 7001, 'ESSAY_REVIEW', 'sample', 'RUNNING', 5,
          0, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
  }
}
