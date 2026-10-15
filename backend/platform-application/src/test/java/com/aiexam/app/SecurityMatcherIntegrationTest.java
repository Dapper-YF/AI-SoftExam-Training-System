package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiexam.common.security.PlatformCookieNames;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
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

@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "aiexam.auth.persistence=unavailable",
        "aiexam.auth.password-adapter=unavailable",
        "aiexam.auth.challenge.dev-delivery.enabled=true",
        "aiexam.security.dev-bearer.enabled=true",
        "aiexam.test.context=security-matcher"
    }
)
class SecurityMatcherIntegrationTest {
  @LocalServerPort
  int port;

  @Autowired
  TestRestTemplate restTemplate;

  @Autowired
  JdbcTemplate jdbcTemplate;

  @BeforeEach
  void resetQuestionPracticeData() {
    createTables();
    for (String table : List.of(
        "q_paper_question",
        "q_paper",
        "q_question_knowledge_binding",
        "q_solution",
        "q_answer_key",
        "q_choice_option",
        "q_sub_question",
        "q_question_stem",
        "q_question_version",
        "q_question",
        "kp_tag",
        "kp_chapter_node",
        "kp_syllabus_node",
        "exam_subject",
        "exam_qualification")) {
      jdbcTemplate.execute("DELETE FROM " + table);
    }
    seedFoundationCatalog();
    seedPublishedQuestionAndPaper();
  }

  @Test
  void anonymousGetAdminQuestionsReturns401() {
    ResponseEntity<Map> response = getAnonymous("/api/v1/admin/questions");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "AUTH_SESSION_EXPIRED");
  }

  @Test
  void studentGetAdminQuestionsReturns403() {
    ResponseEntity<Map> response = getWithToken("/api/v1/admin/questions", "dev-student-token");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "FORBIDDEN");
  }

  @Test
  void anonymousGetQuestionsReturns401() {
    ResponseEntity<Map> response = getAnonymous("/api/v1/questions");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "AUTH_SESSION_EXPIRED");
  }

  @Test
  void anonymousGetQuestionDetailReturns401() {
    ResponseEntity<Map> response = getAnonymous("/api/v1/questions/10001");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).containsEntry("errorCode", "AUTH_SESSION_EXPIRED");
  }

  @Test
  void anonymousGetQuestionExplanationReturns401() {
    ResponseEntity<Map> response = getAnonymous("/api/v1/questions/10001/explanation");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).containsEntry("errorCode", "AUTH_SESSION_EXPIRED");
  }

  @Test
  void anonymousGetPapersReturns401() {
    ResponseEntity<Map> response = getAnonymous("/api/v1/papers");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).containsEntry("errorCode", "AUTH_SESSION_EXPIRED");
  }

  @Test
  void anonymousGetPaperDetailReturns401() {
    ResponseEntity<Map> response = getAnonymous("/api/v1/papers/20001");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).containsEntry("errorCode", "AUTH_SESSION_EXPIRED");
  }

  @Test
  void anonymousCanReadPublicFoundationAndPracticeCatalogWithoutAnswerFields() {
    for (String path : List.of(
        "/api/v1/foundation/qualifications",
        "/api/v1/foundation/qualifications/1/subjects",
        "/api/v1/foundation/subjects/1/chapter-nodes",
        "/api/v1/foundation/subjects/1/syllabus-nodes",
        "/api/v1/foundation/knowledge-tags?qualificationId=1&subjectId=1",
        "/api/v1/public/practice/catalog?qualificationId=1",
        "/api/v1/public/papers?qualificationId=1")) {
      ResponseEntity<Map> response = getAnonymous(path);

      assertThat(response.getStatusCode())
          .as(path)
          .isEqualTo(HttpStatus.OK);
      assertThat(response.getBody())
          .as(path)
          .containsEntry("success", true);
      assertThat(response.getBody().toString())
          .as(path)
          .doesNotContain("answer")
          .doesNotContain("Answer")
          .doesNotContain("solution")
          .doesNotContain("scoringPoint")
          .doesNotContain("Question stem");
    }
  }

  @Test
  void publicPapersDoNotExposePageTotalOrInventoryFields() {
    ResponseEntity<Map> response = getAnonymous("/api/v1/public/papers?qualificationId=1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> page = data(response);
    assertThat(page).containsOnlyKeys("items", "pageNo", "pageSize", "hasNext");
    assertThat(page).doesNotContainKeys("total", "count", "questionCount", "paperCount", "questionTypes");
    assertThat(page.toString())
        .doesNotContain("total")
        .doesNotContain("count")
        .doesNotContain("questionCount")
        .doesNotContain("paperCount")
        .doesNotContain("questionTypes");
    assertThat((List<?>) page.get("items")).allSatisfy(item ->
        assertThat((Map<String, Object>) item)
            .doesNotContainKeys("totalScore", "questionCount", "paperCount", "questionTypes"));
  }

  @Test
  void anonymousCannotCreateOrReadPersonalPracticeState() throws Exception {
    HttpStatus create = postAnonymousStatus("/api/v1/practice/attempts");
    ResponseEntity<Map> history = getAnonymous("/api/v1/practice/attempts");
    ResponseEntity<Map> mistakes = getAnonymous("/api/v1/practice/mistakes");
    ResponseEntity<Map> favorites = getAnonymous("/api/v1/practice/favorites");

    assertThat(create).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(history.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(mistakes.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(favorites.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void anonymousExamSimulatorNamespacePassesPlatformSecurityGate() throws Exception {
    HttpStatus login = postAnonymousStatus("/api/v1/exam-simulator/login");

    assertThat(login).isNotEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void csrfRejectsPlatformCookieUnsafeRequestsBeforeBusinessWrite() throws Exception {
    long before = questionCount();

    HttpResult missingCsrf = postStatus("/api/v1/practice/attempts", Map.of(
        "Cookie", PlatformCookieNames.ACCESS + "=stale-access-token"));
    HttpResult refreshMissingCsrf = postStatus("/api/v1/auth/refresh", Map.of(
        "Cookie", PlatformCookieNames.REFRESH + "=stale-refresh-token"));
    HttpResult missingOrigin = postStatus("/api/v1/practice/attempts", Map.of(
        "Cookie", PlatformCookieNames.ACCESS + "=stale-access-token; " + PlatformCookieNames.CSRF + "=csrf-token",
        PlatformCookieNames.CSRF_HEADER, "csrf-token"));
    HttpResult crossOrigin = postStatus("/api/v1/practice/attempts", Map.of(
        "Cookie", PlatformCookieNames.ACCESS + "=stale-access-token; " + PlatformCookieNames.CSRF + "=csrf-token",
        PlatformCookieNames.CSRF_HEADER, "csrf-token",
        "Origin", "https://evil.example.com"));

    assertThat(missingCsrf.status()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(refreshMissingCsrf.status()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(missingOrigin.status()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(crossOrigin.status()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(missingCsrf.body()).contains("CSRF_INVALID");
    assertThat(refreshMissingCsrf.body()).contains("CSRF_INVALID");
    assertThat(missingOrigin.body()).contains("CSRF_INVALID");
    assertThat(crossOrigin.body()).contains("CSRF_INVALID");
    assertThat(questionCount()).isEqualTo(before);
  }

  @Test
  void validCsrfDoubleSubmitReachesDownstreamAuthenticationGateWithoutBusinessWrite() throws Exception {
    long before = questionCount();
    HttpResult response = postStatus("/api/v1/practice/attempts", Map.of(
        "Cookie", PlatformCookieNames.ACCESS + "=stale-access-token; " + PlatformCookieNames.CSRF + "=csrf-token",
        PlatformCookieNames.CSRF_HEADER, "csrf-token",
        "Referer", "http://localhost:" + port + "/app/practice/start"));

    assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.body()).doesNotContain("CSRF_INVALID");
    assertThat(questionCount()).isEqualTo(before);
  }

  @Test
  void studentTokenCanAccessAuthenticatedQuestionAndPaperEndpoints() {
    for (String path : List.of(
        "/api/v1/questions",
        "/api/v1/questions/10001",
        "/api/v1/papers",
        "/api/v1/papers/20001")) {
      ResponseEntity<Map> response = getWithToken(path, "dev-student-token");

      assertThat(response.getStatusCode())
          .as(path)
          .isEqualTo(HttpStatus.OK);
      assertThat(response.getBody())
          .as(path)
          .containsEntry("success", true);
    }
  }

  @Test
  void studentCannotAccessLegacyExplanationWithoutSettledAttempt() {
    ResponseEntity<Map> response = getWithToken(
        "/api/v1/questions/10001/explanation", "dev-student-token");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody())
        .containsEntry("errorCode", "PRACTICE_ATTEMPT_CONTEXT_REQUIRED");
  }

  @Test
  void questionDetailAndPaperStructureDoNotExposeAnswersOrAnalysis() {
    ResponseEntity<Map> questionDetail = getWithToken("/api/v1/questions/10001", "dev-student-token");
    ResponseEntity<Map> paperDetail = getWithToken("/api/v1/papers/20001", "dev-student-token");

    assertThat(questionDetail.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(paperDetail.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> question = data(questionDetail);
    Map<String, Object> paper = data(paperDetail);
    assertThat(question).doesNotContainKeys("answerKeys", "solutions");
    assertThat(paper).doesNotContainKeys("answerKeys", "solutions", "questionSnapshot", "ruleSnapshot");
    assertThat((List<?>) paper.get("questions")).allSatisfy(item ->
        assertThat((Map<String, Object>) item).doesNotContainKeys("answerKeys", "solutions", "questionSnapshot"));
  }

  private Map<String, Object> data(ResponseEntity<Map> response) {
    return (Map<String, Object>) response.getBody().get("data");
  }

  private void seedFoundationCatalog() {
    if (columnPresent("exam_qualification", "domain_code")) {
      jdbcTemplate.update("""
          INSERT INTO exam_qualification (
            id, qualification_code, domain_code, qualification_name, official_name, level_code,
            verification_status, catalog_version, catalog_status, status, sort_order,
            created_at, updated_at, deleted
          ) VALUES (1, 'HIGH_PM', 'SOFT_EXAM', 'HIGH_PM', 'HIGH_PM', 'ADVANCED',
            'THIRD_PARTY_CANDIDATE', '2026-CUSTOM', 'ACTIVE', 'ENABLED', 1,
            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
          """);
      jdbcTemplate.update("""
          INSERT INTO exam_subject (
            id, qualification_id, subject_code, subject_name, official_name, subject_type, sort_order, status,
            created_at, updated_at, deleted
          ) VALUES (1, 1, 'COMPREHENSIVE', 'COMPREHENSIVE', 'COMPREHENSIVE', 'COMPREHENSIVE', 1, 'ENABLED',
            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
          """);
    } else {
    jdbcTemplate.update("""
        INSERT INTO exam_qualification (
          id, qualification_code, qualification_name, level_code, status, sort_order,
          created_at, updated_at, deleted
        ) VALUES (1, 'HIGH_PM', '信息系统项目管理师', 'ADVANCED', 'ENABLED', 1,
          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO exam_subject (
          id, qualification_id, subject_code, subject_name, subject_type, sort_order, status,
          created_at, updated_at, deleted
        ) VALUES (1, 1, 'COMPREHENSIVE', '综合知识', 'COMPREHENSIVE', 1, 'ENABLED',
          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
    }
    jdbcTemplate.update("""
        INSERT INTO kp_syllabus_node (
          id, qualification_id, subject_id, parent_id, syllabus_version, node_code, node_name,
          level_no, sort_order, status, created_at, updated_at, deleted
        ) VALUES (30001, 1, 1, NULL, '2026', 'S-ROOT', '基础目录', 1, 1, 'ENABLED',
          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO kp_chapter_node (
          id, qualification_id, subject_id, parent_id, book_version, course_code,
          chapter_code, chapter_name, level_no, sort_order, status, created_at, updated_at, deleted
        ) VALUES (31001, 1, 1, NULL, '2026', 'C-ROOT', 'C1', '第一章', 1, 1, 'ENABLED',
          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO kp_tag (
          id, qualification_id, subject_id, tag_code, tag_name, tag_type, status,
          created_at, updated_at, deleted
        ) VALUES (32001, 1, 1, 'T1', '重点知识', 'TOPIC', 'ENABLED',
          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
  }

  private void seedPublishedQuestionAndPaper() {
    jdbcTemplate.update("""
        INSERT INTO q_question (
          id, qualification_id, subject_id, question_type, title, year_no, difficulty,
          source_type, source_ref, status, content_version, current_published_version_id,
          created_at, updated_at, deleted
        ) VALUES (10001, 1, 1, 'SINGLE_CHOICE', 'Auth regression question', 2026, 'MEDIUM',
          'MANUAL', NULL, 'PUBLISHED', 1, 16001, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_question_version (
          id, question_id, version_no, status, title, deleted
        ) VALUES (16001, 10001, 1, 'PUBLISHED', 'Auth regression question', 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_question_stem (
          id, question_id, question_version_id, stem_type, content, sort_order,
          created_at, updated_at, deleted
        ) VALUES (11001, 10001, 16001, 'MAIN_STEM', 'Question stem', 1,
          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_sub_question (
          id, question_id, question_version_id, sub_question_no, sub_question_type, content, score,
          answer_format, grading_mode, sort_order, created_at, updated_at, deleted
        ) VALUES (12001, 10001, 16001, 1, 'SINGLE_CHOICE', 'Choose one', 10.00,
          'OPTION', 'AUTO', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_choice_option (
          id, sub_question_id, option_key, option_content, sort_order, created_at, updated_at, deleted
        ) VALUES (13001, 12001, 'A', 'Option A', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_answer_key (
          id, sub_question_id, answer_format, answer_value, version_no, current_flag,
          created_at, updated_at, deleted
        ) VALUES (14001, 12001, 'OPTION', 'A', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_solution (
          id, question_id, question_version_id, sub_question_id, solution_type, content,
          created_at, updated_at, deleted
        ) VALUES (15001, 10001, 16001, 12001, 'STANDARD', 'Internal analysis',
          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_question_knowledge_binding (
          id, target_type, target_id, question_id, question_version_id, sub_question_id,
          knowledge_type, knowledge_id, created_at, deleted
        ) VALUES (30011, 'QUESTION', 10001, 10001, 16001, NULL, 'CHAPTER', 31001,
          CURRENT_TIMESTAMP, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_paper (
          id, qualification_id, subject_id, paper_type, paper_name, year_no, total_score,
          duration_minutes, rule_snapshot, status, created_at, updated_at, deleted
        ) VALUES (20001, 1, 1, 'MOCK', 'Auth regression paper', 2026, 10.00,
          60, '{"hidden":"rule"}', 'PUBLISHED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO q_paper_question (
          id, paper_id, question_id, question_no, score, question_snapshot,
          created_at, updated_at, deleted
        ) VALUES (21001, 20001, 10001, 1, 10.00, '{"hidden":"snapshot"}',
          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
  }

  @Test
  void anonymousGetHealthReturns200() {
    ResponseEntity<Map> response = getAnonymous("/api/v1/health");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsEntry("success", true);
  }

  private ResponseEntity<Map> getAnonymous(String path) {
    return restTemplate.getForEntity(url(path), Map.class);
  }

  private ResponseEntity<Map> getWithToken(String path, String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return restTemplate.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
  }

  private HttpStatus postAnonymousStatus(String path) throws Exception {
    return postStatus(path, Map.of()).status();
  }

  private HttpResult postStatus(String path, Map<String, String> headers) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url(path)))
        .header("Content-Type", "application/json");
    headers.forEach(builder::header);
    HttpResponse<String> response = HttpClient.newHttpClient()
        .send(
            builder.POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
            HttpResponse.BodyHandlers.ofString());
    return new HttpResult(HttpStatus.valueOf(response.statusCode()), response.body());
  }

  private long questionCount() {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM q_question", Long.class);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private void createTables() {
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS exam_qualification (
          id BIGINT NOT NULL PRIMARY KEY,
          qualification_code VARCHAR(64) NOT NULL,
          qualification_name VARCHAR(120) NOT NULL,
          level_code VARCHAR(32) NOT NULL,
          status VARCHAR(24) NOT NULL,
          sort_order INT NULL,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS exam_subject (
          id BIGINT NOT NULL PRIMARY KEY,
          qualification_id BIGINT NOT NULL,
          subject_code VARCHAR(64) NOT NULL,
          subject_name VARCHAR(120) NOT NULL,
          subject_type VARCHAR(32) NOT NULL,
          sort_order INT NULL,
          status VARCHAR(24) NOT NULL,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS kp_syllabus_node (
          id BIGINT NOT NULL PRIMARY KEY,
          qualification_id BIGINT NOT NULL,
          subject_id BIGINT NOT NULL,
          parent_id BIGINT NULL,
          syllabus_version VARCHAR(64) NULL,
          node_code VARCHAR(64) NOT NULL,
          node_name VARCHAR(180) NOT NULL,
          level_no INT NOT NULL,
          sort_order INT NULL,
          status VARCHAR(24) NOT NULL,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS kp_chapter_node (
          id BIGINT NOT NULL PRIMARY KEY,
          qualification_id BIGINT NOT NULL,
          subject_id BIGINT NOT NULL,
          parent_id BIGINT NULL,
          book_version VARCHAR(64) NULL,
          course_code VARCHAR(64) NULL,
          chapter_code VARCHAR(64) NOT NULL,
          chapter_name VARCHAR(180) NOT NULL,
          level_no INT NOT NULL,
          sort_order INT NULL,
          status VARCHAR(24) NOT NULL,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS kp_tag (
          id BIGINT NOT NULL PRIMARY KEY,
          qualification_id BIGINT NULL,
          subject_id BIGINT NULL,
          tag_code VARCHAR(64) NOT NULL,
          tag_name VARCHAR(120) NOT NULL,
          tag_type VARCHAR(32) NULL,
          status VARCHAR(24) NOT NULL,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
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
          question_version_id BIGINT NOT NULL,
          stem_type VARCHAR(32) NOT NULL,
          content TEXT NULL,
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
          question_version_id BIGINT NOT NULL,
          sub_question_no INT NOT NULL,
          sub_question_type VARCHAR(32) NOT NULL,
          content TEXT NOT NULL,
          score DECIMAL(8,2) NULL,
          answer_format VARCHAR(32) NOT NULL,
          grading_mode VARCHAR(32) NOT NULL DEFAULT 'AUTO',
          sort_order INT NOT NULL DEFAULT 0,
          metadata_json TEXT NULL,
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
          option_content TEXT NOT NULL,
          sort_order INT NOT NULL DEFAULT 0,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS q_answer_key (
          id BIGINT NOT NULL PRIMARY KEY,
          sub_question_id BIGINT NOT NULL,
          answer_format VARCHAR(32) NOT NULL,
          answer_value TEXT NULL,
          answer_json TEXT NULL,
          version_no INT NOT NULL DEFAULT 1,
          current_flag TINYINT NOT NULL DEFAULT 1,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS q_solution (
          id BIGINT NOT NULL PRIMARY KEY,
          question_id BIGINT NOT NULL,
          question_version_id BIGINT NOT NULL,
          sub_question_id BIGINT NULL,
          solution_type VARCHAR(32) NOT NULL,
          content TEXT NOT NULL,
          ai_task_id BIGINT NULL,
          prompt_version VARCHAR(64) NULL,
          model_code VARCHAR(64) NULL,
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
          target_type VARCHAR(32) NOT NULL,
          target_id BIGINT NOT NULL,
          question_id BIGINT NOT NULL,
          question_version_id BIGINT NOT NULL,
          sub_question_id BIGINT NULL,
          knowledge_type VARCHAR(32) NOT NULL,
          knowledge_id BIGINT NOT NULL,
          created_at TIMESTAMP NOT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS q_paper (
          id BIGINT NOT NULL PRIMARY KEY,
          qualification_id BIGINT NOT NULL,
          subject_id BIGINT NULL,
          paper_type VARCHAR(32) NOT NULL,
          paper_name VARCHAR(180) NOT NULL,
          year_no INT NULL,
          total_score DECIMAL(8,2) NOT NULL,
          duration_minutes INT NULL,
          rule_snapshot TEXT NULL,
          status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS q_paper_question (
          id BIGINT NOT NULL PRIMARY KEY,
          paper_id BIGINT NOT NULL,
          question_id BIGINT NOT NULL,
          section_code VARCHAR(64) NULL,
          question_no INT NOT NULL,
          score DECIMAL(8,2) NULL,
          question_snapshot TEXT NULL,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    setColumnDefaultIfPresent("exam_qualification", "domain_code", "'SOFT_EXAM'");
    setColumnDefaultIfPresent("exam_qualification", "official_name", "''");
    setColumnDefaultIfPresent("exam_qualification", "verification_status", "'THIRD_PARTY_CANDIDATE'");
    setColumnDefaultIfPresent("exam_qualification", "catalog_version", "'2026-CUSTOM'");
    setColumnDefaultIfPresent("exam_qualification", "catalog_status", "'ACTIVE'");
    setColumnDefaultIfPresent("exam_subject", "official_name", "''");
  }

  private void setColumnDefaultIfPresent(String tableName, String columnName, String defaultExpression) {
    if (columnPresent(tableName, columnName)) {
      jdbcTemplate.execute("ALTER TABLE " + tableName + " ALTER COLUMN " + columnName
          + " SET DEFAULT " + defaultExpression);
    }
  }

  private boolean columnPresent(String tableName, String columnName) {
    try {
      jdbcTemplate.queryForObject("SELECT COUNT(" + columnName + ") FROM " + tableName, Integer.class);
      return true;
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private record HttpResult(HttpStatus status, String body) {
  }
}
