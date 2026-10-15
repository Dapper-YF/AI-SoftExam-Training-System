package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

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
@Disabled("Superseded by attempt-scoped explanation and versioned question contract gates")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "aiexam.auth.persistence=unavailable",
        "aiexam.auth.password-adapter=unavailable",
        "aiexam.auth.challenge.dev-delivery.enabled=true",
        "aiexam.security.dev-bearer.enabled=true"
    }
)
class QuestionExplanationIntegrationTest {
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
        "ai_result_cache",
        "ai_task",
        "q_explanation_cache",
        "q_question_knowledge_binding",
        "q_scoring_rule",
        "q_solution",
        "q_answer_key",
        "q_choice_option",
        "q_sub_question",
        "q_question_stem",
        "q_question",
        "kp_tag",
        "kp_chapter_node",
        "kp_syllabus_node",
        "exam_subject",
        "exam_qualification")) {
      jdbcTemplate.execute("DELETE FROM " + table);
    }
    seedFoundationData();
  }

  @Test
  void publishedQuestionCannotBypassAttemptScopedExplanation() {
    Long questionId = createQuestion(singleChoicePayload("已发布解析单选题"));
    publish(questionId);

    ResponseEntity<Map> response = getStudent("/api/v1/questions/" + questionId + "/explanation");

    assertAttemptContextRequired(response);
  }

  @Test
  void unpublishedQuestionAlsoDoesNotRevealExistenceThroughExplanation() {
    Long questionId = createQuestion(singleChoicePayload("草稿解析题"));

    ResponseEntity<Map> response = getStudent("/api/v1/questions/" + questionId + "/explanation");

    assertAttemptContextRequired(response);
  }

  @Test
  void caseQuestionCannotBypassAttemptScopedExplanation() {
    Long questionId = createQuestion(casePayload());
    jdbcTemplate.update("""
        INSERT INTO q_solution (
          id, question_id, sub_question_id, solution_type, content, created_at, updated_at, deleted
        ) VALUES (90001, ?, NULL, 'STANDARD', '整题解析', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """, questionId);
    publish(questionId);

    ResponseEntity<Map> response = getStudent("/api/v1/questions/" + questionId + "/explanation");

    assertAttemptContextRequired(response);
  }

  @Test
  void missingAiEnhancedExplanationStillRequiresAttemptContext() {
    Long questionId = createQuestion(singleChoicePayload("无 AI 增强解析题"));
    publish(questionId);

    ResponseEntity<Map> response = getStudent("/api/v1/questions/" + questionId + "/explanation");

    assertAttemptContextRequired(response);
  }

  @Test
  void triggeringAiExplanationCannotBypassAttemptContext() {
    Long questionId = createQuestion(singleChoicePayload("AI 解析任务题"));
    publish(questionId);

    ResponseEntity<Map> response = postStudent("/api/v1/questions/" + questionId + "/ai-explanation", Map.of());

    assertAttemptContextRequired(response);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM ai_task WHERE biz_type = 'QUESTION_EXPLANATION' AND biz_id = ?",
        Integer.class, questionId)).isZero();
  }

  @Test
  void repeatedAiExplanationBypassDoesNotCreateTasks() {
    Long questionId = createQuestion(singleChoicePayload("AI 解析幂等题"));
    publish(questionId);

    ResponseEntity<Map> first = postStudent("/api/v1/questions/" + questionId + "/ai-explanation", Map.of());
    ResponseEntity<Map> second = postStudent("/api/v1/questions/" + questionId + "/ai-explanation", Map.of());

    assertAttemptContextRequired(first);
    assertAttemptContextRequired(second);
    Integer taskCount = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM ai_task WHERE biz_type = 'QUESTION_EXPLANATION' AND biz_id = ?",
        Integer.class,
        questionId);
    assertThat(taskCount).isZero();
  }

  private void assertAttemptContextRequired(ResponseEntity<Map> response) {
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "PRACTICE_ATTEMPT_CONTEXT_REQUIRED");
  }

  private Long createQuestion(Map<String, Object> payload) {
    ResponseEntity<Map> response = postAdmin("/api/v1/admin/questions", payload);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return ((Number) data(response).get("questionId")).longValue();
  }

  private void publish(Long questionId) {
    assertThat(postAdmin("/api/v1/admin/questions/" + questionId + "/publish", Map.of()).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  private Map<String, Object> singleChoicePayload(String title) {
    return new LinkedHashMap<>(Map.of(
        "qualificationId", 1,
        "subjectId", 1,
        "questionType", "SINGLE_CHOICE",
        "title", title,
        "yearNo", 2026,
        "difficulty", "MEDIUM",
        "stems", List.of(Map.of("stemType", "MAIN_STEM", "content", "项目范围管理的主要输出是？", "sortOrder", 1)),
        "subQuestions", List.of(new LinkedHashMap<>(Map.of(
            "subQuestionNo", 1,
            "subQuestionType", "SINGLE_CHOICE",
            "content", "请选择正确答案。",
            "score", 1,
            "answerFormat", "OPTION",
            "gradingMode", "AUTO",
            "options", List.of(
                Map.of("optionKey", "A", "optionContent", "范围基准", "sortOrder", 1),
                Map.of("optionKey", "B", "optionContent", "风险登记册", "sortOrder", 2)),
            "answerKeys", List.of(Map.of("answerFormat", "OPTION", "answerValue", "[\"A\"]")),
            "solutions", List.of(Map.of("solutionType", "STANDARD", "content", "范围基准是范围管理的重要输出。")),
            "knowledgeBindings", List.of(Map.of("knowledgeType", "SYLLABUS", "knowledgeId", 1001)))))));
  }

  private Map<String, Object> casePayload() {
    return Map.of(
        "qualificationId", 1,
        "subjectId", 2,
        "questionType", "CASE",
        "title", "项目进度管理案例题",
        "stems", List.of(
            Map.of("stemType", "CASE_MATERIAL", "content", "某项目背景材料一。", "sortOrder", 1),
            Map.of("stemType", "CASE_MATERIAL", "content", "补充材料二。", "sortOrder", 2)),
        "subQuestions", List.of(
            Map.of(
                "subQuestionNo", 1,
                "subQuestionType", "JUDGE",
                "content", "该项目是否存在关键路径变化？",
                "score", 2,
                "answerFormat", "BOOLEAN",
                "gradingMode", "AUTO",
                "answerKeys", List.of(Map.of("answerFormat", "BOOLEAN", "answerValue", "true")),
                "solutions", List.of(Map.of("solutionType", "STANDARD", "content", "关键路径需要重新计算。")),
                "knowledgeBindings", List.of(Map.of("knowledgeType", "SYLLABUS", "knowledgeId", 1002))),
            Map.of(
                "subQuestionNo", 2,
                "subQuestionType", "SHORT_ANSWER",
                "content", "说明可采取的进度压缩措施。",
                "score", 8,
                "answerFormat", "TEXT",
                "gradingMode", "AI_ASSISTED",
                "solutions", List.of(Map.of("solutionType", "STANDARD", "content", "可采用赶工和快速跟进。")),
                "knowledgeBindings", List.of(Map.of("knowledgeType", "CHAPTER", "knowledgeId", 2002)))));
  }

  private ResponseEntity<Map> postAdmin(String path, Object body) {
    return restTemplate.exchange(url(path), HttpMethod.POST,
        new HttpEntity<>(body, mutationHeaders("dev-admin-token")), Map.class);
  }

  private ResponseEntity<Map> postStudent(String path, Object body) {
    return restTemplate.exchange(url(path), HttpMethod.POST,
        new HttpEntity<>(body, mutationHeaders("dev-student-token")), Map.class);
  }

  private ResponseEntity<Map> getStudent(String path) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("dev-student-token");
    return restTemplate.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
  }

  private HttpHeaders mutationHeaders(String bearerToken) {
    String csrfToken = "question-explanation-test-csrf";
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
    createAiTables();
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
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS kp_chapter_node (
          id BIGINT NOT NULL PRIMARY KEY,
          qualification_id BIGINT NOT NULL,
          subject_id BIGINT NOT NULL,
          parent_id BIGINT NULL,
          book_version VARCHAR(64) NULL,
          course_code VARCHAR(64) NULL,
          chapter_code VARCHAR(96) NOT NULL,
          chapter_name VARCHAR(128) NOT NULL,
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
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS kp_tag (
          id BIGINT NOT NULL PRIMARY KEY,
          qualification_id BIGINT NULL,
          subject_id BIGINT NULL,
          tag_code VARCHAR(96) NOT NULL,
          tag_name VARCHAR(128) NOT NULL,
          tag_type VARCHAR(32) NOT NULL,
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
          deleted TINYINT NOT NULL DEFAULT 0,
          CONSTRAINT uk_q_sub_question_no_exp UNIQUE (question_id, sub_question_no)
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
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS q_explanation_cache (
          id BIGINT NOT NULL PRIMARY KEY,
          question_id BIGINT NOT NULL,
          sub_question_id BIGINT NULL,
          cache_type VARCHAR(32) NOT NULL,
          cache_key VARCHAR(160) NOT NULL,
          content LONGTEXT NOT NULL,
          ai_task_id BIGINT NULL,
          expires_at TIMESTAMP NULL,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
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
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS ai_result_cache (
          id BIGINT NOT NULL PRIMARY KEY,
          cache_key VARCHAR(160) NOT NULL,
          scenario VARCHAR(96) NOT NULL,
          model_code VARCHAR(64) NULL,
          prompt_version VARCHAR(64) NULL,
          input_hash VARCHAR(160) NOT NULL,
          result_content LONGTEXT NOT NULL,
          result_json LONGTEXT NULL,
          expires_at TIMESTAMP NULL,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
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
           CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO exam_subject (
          id, qualification_id, subject_code, subject_name, official_name, subject_type,
          sort_order, status, created_at, updated_at, deleted
        ) VALUES
          (1, 1, 'COMPREHENSIVE', '综合知识', '综合知识', 'COMPREHENSIVE', 1, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
          (2, 1, 'CASE', '案例分析', '案例分析', 'CASE', 2, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO kp_syllabus_node (
          id, qualification_id, subject_id, parent_id, syllabus_version, node_code, node_name, level_no,
          sort_order, status, created_at, updated_at, deleted
        ) VALUES
          (1001, 1, 1, NULL, '2026', 'SY-1', '范围管理', 1, 1, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
          (1002, 1, 2, NULL, '2026', 'SY-2', '案例分析', 1, 1, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO kp_chapter_node (
          id, qualification_id, subject_id, parent_id, book_version, course_code, chapter_code, chapter_name,
          level_no, sort_order, status, created_at, updated_at, deleted
        ) VALUES
          (2001, 1, 1, NULL, '2026', NULL, 'CH-1', '第1章', 1, 1, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
          (2002, 1, 2, NULL, '2026', NULL, 'CH-2', '案例章', 1, 1, 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
    jdbcTemplate.update("""
        INSERT INTO kp_tag (
          id, qualification_id, subject_id, tag_code, tag_name, tag_type, status, created_at, updated_at, deleted
        ) VALUES
          (3001, 1, 1, 'TAG-1', '高频', 'HIGH_FREQUENCY', 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
          (3002, 1, 2, 'TAG-2', '案例', 'CASE', 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
  }
}
