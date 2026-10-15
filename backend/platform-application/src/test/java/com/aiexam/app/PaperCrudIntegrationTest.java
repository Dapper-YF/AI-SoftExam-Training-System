package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@Disabled("Superseded by the versioned question and paper contract gates")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "aiexam.auth.persistence=unavailable",
        "aiexam.auth.password-adapter=unavailable",
        "aiexam.auth.challenge.dev-delivery.enabled=true",
        "aiexam.security.dev-bearer.enabled=true"
    }
)
class PaperCrudIntegrationTest {
  @LocalServerPort
  int port;

  @Autowired
  TestRestTemplate restTemplate;

  @Autowired
  JdbcTemplate jdbcTemplate;

  @BeforeEach
  void resetTables() {
    createTables();
    for (String table : List.of(
        "q_paper_question",
        "q_paper",
        "q_question_knowledge_binding",
        "q_scoring_rule",
        "q_solution",
        "q_answer_key",
        "q_choice_option",
        "q_sub_question",
        "q_question_stem",
        "q_question",
        "kp_syllabus_node",
        "exam_subject",
        "exam_qualification")) {
      jdbcTemplate.execute("DELETE FROM " + table);
    }
    seedFoundationData();
  }

  @Test
  void adminCreatesDraftPaperSuccessfully() {
    ResponseEntity<Map> response = postAdmin("/api/v1/admin/papers", paperPayload(1, BigDecimal.TEN));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).containsEntry("success", true);
    Map<String, Object> data = data(response);
    assertThat(data).containsEntry("paperName", "2026 高项综合模拟卷");
    assertThat(data).containsEntry("status", "DRAFT");
  }

  @Test
  void studentCannotCreatePaper() {
    ResponseEntity<Map> response = postStudent("/api/v1/admin/papers", paperPayload(1, BigDecimal.TEN));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "FORBIDDEN");
  }

  @Test
  void adminAddsPublishedQuestionToDraftPaperSuccessfully() {
    Long questionId = createAndPublishQuestion(1, "可入卷题");
    Long paperId = createPaper(1, BigDecimal.TEN);

    ResponseEntity<Map> response = postAdmin("/api/v1/admin/papers/" + paperId + "/questions", paperQuestionPayload(questionId, 1, "10.00"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> data = data(response);
    assertThat((List<?>) data.get("questions")).hasSize(1);
  }

  @Test
  void addingUnpublishedQuestionFails() {
    Long questionId = createQuestion(1, "草稿题");
    Long paperId = createPaper(1, BigDecimal.TEN);

    ResponseEntity<Map> response = postAdmin("/api/v1/admin/papers/" + paperId + "/questions", paperQuestionPayload(questionId, 1, "10.00"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "VALIDATION_ERROR");
  }

  @Test
  void addingCrossQualificationQuestionFails() {
    Long questionId = createAndPublishQuestion(3, "软设题");
    Long paperId = createPaper(1, BigDecimal.TEN);

    ResponseEntity<Map> response = postAdmin("/api/v1/admin/papers/" + paperId + "/questions", paperQuestionPayload(questionId, 1, "10.00"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("errorCode", "VALIDATION_ERROR");
  }

  @Test
  void addingCrossSubjectQuestionFails() {
    Long questionId = createAndPublishQuestion(2, "案例题");
    Long paperId = createPaper(1, BigDecimal.TEN);

    ResponseEntity<Map> response = postAdmin("/api/v1/admin/papers/" + paperId + "/questions", paperQuestionPayload(questionId, 1, "10.00"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("errorCode", "VALIDATION_ERROR");
  }

  @Test
  void duplicateQuestionNoInSamePaperFails() {
    Long questionA = createAndPublishQuestion(1, "题A");
    Long questionB = createAndPublishQuestion(1, "题B");
    Long paperId = createPaper(1, BigDecimal.valueOf(20));
    assertThat(postAdmin("/api/v1/admin/papers/" + paperId + "/questions", paperQuestionPayload(questionA, 1, "10.00")).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    ResponseEntity<Map> response = postAdmin("/api/v1/admin/papers/" + paperId + "/questions", paperQuestionPayload(questionB, 1, "10.00"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).containsEntry("errorCode", "CONFLICT");
  }

  @Test
  void duplicateQuestionIdInSamePaperFails() {
    Long questionId = createAndPublishQuestion(1, "重复题");
    Long paperId = createPaper(1, BigDecimal.valueOf(20));
    assertThat(postAdmin("/api/v1/admin/papers/" + paperId + "/questions", paperQuestionPayload(questionId, 1, "10.00")).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    ResponseEntity<Map> response = postAdmin("/api/v1/admin/papers/" + paperId + "/questions", paperQuestionPayload(questionId, 2, "10.00"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).containsEntry("errorCode", "CONFLICT");
  }

  @Test
  void publishingEmptyPaperFails() {
    Long paperId = createPaper(1, BigDecimal.TEN);

    ResponseEntity<Map> response = postAdmin("/api/v1/admin/papers/" + paperId + "/publish", Map.of());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("errorCode", "VALIDATION_ERROR");
  }

  @Test
  void publishingPaperWithScoreMismatchFails() {
    Long questionId = createAndPublishQuestion(1, "分值不匹配题");
    Long paperId = createPaper(1, BigDecimal.valueOf(20));
    assertThat(postAdmin("/api/v1/admin/papers/" + paperId + "/questions", paperQuestionPayload(questionId, 1, "10.00")).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    ResponseEntity<Map> response = postAdmin("/api/v1/admin/papers/" + paperId + "/publish", Map.of());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("errorCode", "VALIDATION_ERROR");
  }

  @Test
  void publishedPaperAppearsInStudentPage() {
    Long paperId = createPublishedPaper();

    ResponseEntity<Map> response = getStudent("/api/v1/papers");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> page = data(response);
    List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
    assertThat(items).extracting(item -> ((Number) item.get("paperId")).longValue()).contains(paperId);
  }

  @Test
  void publishedPaperDetailReturnsQuestionSummaryWithoutAnswerOrExplanation() {
    Long paperId = createPublishedPaper();

    ResponseEntity<Map> response = getStudent("/api/v1/papers/" + paperId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> data = data(response);
    List<Map<String, Object>> questions = (List<Map<String, Object>>) data.get("questions");
    assertThat(questions).hasSize(1);
    assertThat(questions.get(0)).containsKeys("questionId", "questionNo", "questionType", "title", "score", "subjectId", "contentVersion");
    assertThat(questions.get(0)).doesNotContainKeys("answerKeys", "solutions");
  }

  @Test
  void unpublishedPaperIsInvisibleToStudent() {
    Long paperId = createPaper(1, BigDecimal.TEN);

    ResponseEntity<Map> response = getStudent("/api/v1/papers/" + paperId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "NOT_FOUND");
  }

  @Test
  void updatingPublishedPaperReturnsConflict() {
    Long paperId = createPublishedPaper();

    ResponseEntity<Map> response = putAdmin("/api/v1/admin/papers/" + paperId, paperPayload(1, BigDecimal.TEN));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).containsEntry("errorCode", "CONFLICT");
  }

  private Long createPublishedPaper() {
    Long questionId = createAndPublishQuestion(1, "已发布入卷题");
    Long paperId = createPaper(1, BigDecimal.TEN);
    assertThat(postAdmin("/api/v1/admin/papers/" + paperId + "/questions", paperQuestionPayload(questionId, 1, "10.00")).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertThat(postAdmin("/api/v1/admin/papers/" + paperId + "/publish", Map.of()).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    return paperId;
  }

  private Long createPaper(Integer subjectId, BigDecimal totalScore) {
    ResponseEntity<Map> response = postAdmin("/api/v1/admin/papers", paperPayload(subjectId, totalScore));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return ((Number) data(response).get("paperId")).longValue();
  }

  private Long createAndPublishQuestion(Integer subjectId, String title) {
    Long questionId = createQuestion(subjectId, title);
    assertThat(postAdmin("/api/v1/admin/questions/" + questionId + "/publish", Map.of()).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    return questionId;
  }

  private Long createQuestion(Integer subjectId, String title) {
    ResponseEntity<Map> response = postAdmin("/api/v1/admin/questions", questionPayload(subjectId, title));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return ((Number) data(response).get("questionId")).longValue();
  }

  private Map<String, Object> paperPayload(Integer subjectId, BigDecimal totalScore) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("qualificationId", subjectId != null && subjectId == 3 ? 2 : 1);
    payload.put("subjectId", subjectId);
    payload.put("paperName", "2026 高项综合模拟卷");
    payload.put("paperType", "MOCK");
    payload.put("yearNo", 2026);
    payload.put("totalScore", totalScore);
    payload.put("durationMinutes", 150);
    return payload;
  }

  private Map<String, Object> paperQuestionPayload(Long questionId, int questionNo, String score) {
    return Map.of(
        "questionId", questionId,
        "questionNo", questionNo,
        "score", score);
  }

  private Map<String, Object> questionPayload(Integer subjectId, String title) {
    long qualificationId = subjectId != null && subjectId == 3 ? 2L : 1L;
    long syllabusId = subjectId != null && subjectId == 3 ? 1003L : subjectId == 2 ? 1002L : 1001L;
    return new LinkedHashMap<>(Map.of(
        "qualificationId", qualificationId,
        "subjectId", subjectId,
        "questionType", "SINGLE_CHOICE",
        "title", title,
        "yearNo", 2026,
        "difficulty", "MEDIUM",
        "stems", List.of(Map.of("stemType", "MAIN_STEM", "content", "题干", "sortOrder", 1)),
        "subQuestions", List.of(new LinkedHashMap<>(Map.of(
            "subQuestionNo", 1,
            "subQuestionType", "SINGLE_CHOICE",
            "content", "请选择。",
            "score", 10,
            "answerFormat", "OPTION",
            "gradingMode", "AUTO",
            "options", List.of(
                Map.of("optionKey", "A", "optionContent", "A", "sortOrder", 1),
                Map.of("optionKey", "B", "optionContent", "B", "sortOrder", 2)),
            "answerKeys", List.of(Map.of("answerFormat", "OPTION", "answerValue", "[\"A\"]")),
            "solutions", List.of(Map.of("solutionType", "STANDARD", "content", "解析")),
            "knowledgeBindings", List.of(Map.of("knowledgeType", "SYLLABUS", "knowledgeId", syllabusId)))))));
  }

  private ResponseEntity<Map> postAdmin(String path, Object body) {
    return restTemplate.exchange(
        url(path),
        HttpMethod.POST,
        new HttpEntity<>(body, mutationHeaders("dev-admin-token")),
        Map.class);
  }

  private ResponseEntity<Map> putAdmin(String path, Object body) {
    return restTemplate.exchange(
        url(path),
        HttpMethod.PUT,
        new HttpEntity<>(body, mutationHeaders("dev-admin-token")),
        Map.class);
  }

  private ResponseEntity<Map> postStudent(String path, Object body) {
    return restTemplate.exchange(
        url(path),
        HttpMethod.POST,
        new HttpEntity<>(body, mutationHeaders("dev-student-token")),
        Map.class);
  }

  private ResponseEntity<Map> getStudent(String path) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("dev-student-token");
    return restTemplate.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
  }

  private HttpHeaders mutationHeaders(String bearerToken) {
    String csrfToken = "paper-crud-test-csrf";
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(bearerToken);
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfToken);
    headers.set("X-XSRF-TOKEN", csrfToken);
    headers.set(HttpHeaders.REFERER, "http://localhost:" + port + "/");
    return headers;
  }

  private Map<String, Object> data(ResponseEntity<Map> response) {
    return (Map<String, Object>) response.getBody().get("data");
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private void createTables() {
    createFoundationTables();
    createQuestionTables();
    createPaperTables();
  }

  private void createFoundationTables() {
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS exam_qualification (
          id BIGINT NOT NULL PRIMARY KEY,
          qualification_code VARCHAR(64) NOT NULL,
          domain_code VARCHAR(32) NOT NULL,
          qualification_name VARCHAR(96) NOT NULL,
          official_name VARCHAR(96) NOT NULL,
          level_code VARCHAR(32) NOT NULL,
          source_url VARCHAR(512) NULL,
          verified_at TIMESTAMP NULL,
          verification_status VARCHAR(32) NOT NULL,
          catalog_version VARCHAR(32) NOT NULL,
          catalog_status VARCHAR(24) NOT NULL,
          status VARCHAR(24) NOT NULL DEFAULT 'ENABLED',
          sort_order INT NOT NULL DEFAULT 0,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS exam_subject (
          id BIGINT NOT NULL PRIMARY KEY,
          qualification_id BIGINT NOT NULL,
          subject_code VARCHAR(64) NOT NULL,
          subject_name VARCHAR(96) NOT NULL,
          official_name VARCHAR(96) NOT NULL,
          subject_type VARCHAR(32) NOT NULL,
          sort_order INT NOT NULL DEFAULT 0,
          status VARCHAR(24) NOT NULL DEFAULT 'ENABLED',
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS kp_syllabus_node (
          id BIGINT NOT NULL PRIMARY KEY,
          qualification_id BIGINT NOT NULL,
          subject_id BIGINT NOT NULL,
          parent_id BIGINT NULL,
          syllabus_version VARCHAR(32) NOT NULL,
          node_code VARCHAR(96) NOT NULL,
          node_name VARCHAR(128) NOT NULL,
          level_no INT NOT NULL DEFAULT 1,
          sort_order INT NOT NULL DEFAULT 0,
          status VARCHAR(24) NOT NULL DEFAULT 'ENABLED',
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
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
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS q_question_stem (
          id BIGINT NOT NULL PRIMARY KEY,
          question_id BIGINT NOT NULL,
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
        CREATE TABLE IF NOT EXISTS q_answer_key (
          id BIGINT NOT NULL PRIMARY KEY,
          sub_question_id BIGINT NOT NULL,
          answer_format VARCHAR(32) NOT NULL,
          answer_value LONGTEXT NULL,
          answer_json LONGTEXT NULL,
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
          sub_question_id BIGINT NULL,
          solution_type VARCHAR(32) NOT NULL,
          content LONGTEXT NOT NULL,
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
        CREATE TABLE IF NOT EXISTS q_scoring_rule (
          id BIGINT NOT NULL PRIMARY KEY,
          sub_question_id BIGINT NOT NULL,
          rule_type VARCHAR(32) NOT NULL,
          rule_json LONGTEXT NOT NULL,
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
          sub_question_id BIGINT NULL,
          target_type VARCHAR(32) NOT NULL,
          target_id BIGINT NOT NULL,
          knowledge_type VARCHAR(32) NOT NULL,
          knowledge_id BIGINT NOT NULL,
          created_at TIMESTAMP NOT NULL
        )
        """);
  }

  private void createPaperTables() {
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS q_paper (
          id BIGINT NOT NULL PRIMARY KEY,
          qualification_id BIGINT NOT NULL,
          subject_id BIGINT NULL,
          paper_type VARCHAR(32) NOT NULL,
          paper_name VARCHAR(160) NOT NULL,
          year_no INT NULL,
          total_score DECIMAL(8,2) NULL,
          duration_minutes INT NULL,
          rule_snapshot LONGTEXT NULL,
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
          question_snapshot LONGTEXT NULL,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0,
          CONSTRAINT uk_q_paper_question_no_test UNIQUE (paper_id, question_no)
        )
        """);
  }

  private void seedFoundationData() {
    jdbcTemplate.update("""
        INSERT INTO exam_qualification (
          id, qualification_code, domain_code, qualification_name, official_name, level_code,
          verification_status, catalog_version, catalog_status, status, sort_order,
          created_at, updated_at, deleted
        ) VALUES
          (1, 'GAOXIANG', 'SOFT_EXAM', '高项', '信息系统项目管理师', 'ADVANCED',
           'THIRD_PARTY_CANDIDATE', '2026-TEST', 'ACTIVE', 'ENABLED', 1,
           CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
          (2, 'RUANSHE', 'SOFT_EXAM', '软设', '软件设计师', 'INTERMEDIATE',
           'THIRD_PARTY_CANDIDATE', '2026-TEST', 'ACTIVE', 'ENABLED', 2,
           CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO exam_subject (
          id, qualification_id, subject_code, subject_name, official_name, subject_type,
          sort_order, status, created_at, updated_at, deleted
        ) VALUES
          (1, 1, 'COMPREHENSIVE', '综合知识', '综合知识', 'COMPREHENSIVE', 1, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
          (2, 1, 'CASE', '案例分析', '案例分析', 'CASE', 2, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
          (3, 2, 'COMPREHENSIVE', '综合知识', '综合知识', 'COMPREHENSIVE', 1, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO kp_syllabus_node (
          id, qualification_id, subject_id, parent_id, syllabus_version, node_code, node_name, level_no,
          sort_order, status, created_at, updated_at, deleted
        ) VALUES
          (1001, 1, 1, NULL, '2026', 'SY-1', '范围管理', 1, 1, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
          (1002, 1, 2, NULL, '2026', 'SY-2', '案例分析', 1, 1, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
          (1003, 2, 3, NULL, '2026', 'SY-3', '软设知识', 1, 1, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
  }
}
