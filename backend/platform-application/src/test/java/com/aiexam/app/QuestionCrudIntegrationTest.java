package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@Disabled("Superseded by the versioned question admin contract and its dedicated gates")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QuestionCrudIntegrationTest {
  @LocalServerPort
  int port;

  @Autowired
  TestRestTemplate restTemplate;

  @Autowired
  JdbcTemplate jdbcTemplate;

  @BeforeEach
  void resetQuestionTables() {
    createTables();
    for (String table : List.of(
        "q_question_knowledge_binding",
        "q_scoring_rule",
        "q_solution",
        "q_answer_key",
        "q_choice_option",
        "q_sub_question",
        "q_question_stem",
        "q_question")) {
      jdbcTemplate.execute("DELETE FROM " + table);
    }
    for (String table : List.of(
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
  void adminCreatesSingleChoiceQuestionSuccessfully() {
    ResponseEntity<Map> response = postAdmin("/api/v1/admin/questions", singleChoicePayload("范围管理单选题"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).containsEntry("success", true);
    Map<String, Object> data = data(response);
    assertThat(data).containsEntry("questionType", "SINGLE_CHOICE");
    assertThat((List<?>) data.get("subQuestions")).hasSize(1);
  }

  @Test
  void adminCreatesCaseQuestionWithMultipleMaterialsAndSubQuestions() {
    ResponseEntity<Map> response = postAdmin("/api/v1/admin/questions", casePayload());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> data = data(response);
    assertThat(data).containsEntry("questionType", "CASE");
    assertThat((List<?>) data.get("stems")).hasSize(2);
    assertThat((List<?>) data.get("subQuestions")).hasSize(2);
  }

  @Test
  void adminPublishesQuestionSuccessfully() {
    Long questionId = createQuestion(singleChoicePayload("可发布单选题"));

    ResponseEntity<Map> response = postAdmin("/api/v1/admin/questions/" + questionId + "/publish", Map.of());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(data(response)).containsEntry("questionId", questionId);
  }

  @Test
  void unpublishedQuestionIsInvisibleToStudentEndpoint() {
    Long questionId = createQuestion(singleChoicePayload("草稿题"));

    ResponseEntity<Map> response = getStudent("/api/v1/questions/" + questionId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("code", "NOT_FOUND");
  }

  @Test
  void publishedQuestionCanBeQueriedByStudentWithoutAnswerKey() {
    Long questionId = createQuestion(singleChoicePayload("已发布单选题"));
    assertThat(postAdmin("/api/v1/admin/questions/" + questionId + "/publish", Map.of()).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> response = getStudent("/api/v1/questions/" + questionId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> data = data(response);
    assertThat(data).containsKeys(
        "questionId",
        "qualificationId",
        "subjectId",
        "questionType",
        "title",
        "stems",
        "subQuestions",
        "options",
        "answerFormat",
        "knowledgeBindings");
    assertThat(data).doesNotContainKey("answerKeys");
    assertThat(data).doesNotContainKey("solutions");
  }

  @Test
  void studentCannotCreateQuestion() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("dev-student-token");

    ResponseEntity<Map> response = restTemplate.exchange(
        url("/api/v1/admin/questions"),
        HttpMethod.POST,
        new HttpEntity<>(singleChoicePayload("学生越权"), headers),
        Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("code", "FORBIDDEN");
  }

  @Test
  void choiceQuestionWithoutOptionsReturnsValidationError() {
    Map<String, Object> payload = Map.of(
        "qualificationId", 1,
        "subjectId", 1,
        "questionType", "SINGLE_CHOICE",
        "title", "无选项选择题",
        "stems", List.of(Map.of("stemType", "MAIN_STEM", "content", "题干", "sortOrder", 1)),
        "subQuestions", List.of(Map.of(
            "subQuestionNo", 1,
            "subQuestionType", "SINGLE_CHOICE",
            "content", "请选择。",
            "score", 1,
            "answerFormat", "OPTION",
            "gradingMode", "AUTO",
            "answerKeys", List.of(Map.of("answerFormat", "OPTION", "answerValue", "[\"A\"]")))));

    ResponseEntity<Map> response = postAdmin("/api/v1/admin/questions", payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("code", "VALIDATION_ERROR");
  }

  @Test
  void autoGradedQuestionWithoutAnswerReturnsValidationError() {
    Map<String, Object> payload = singleChoicePayload("无答案自动判分题");
    ((Map<String, Object>) ((List<?>) payload.get("subQuestions")).get(0)).remove("answerKeys");

    ResponseEntity<Map> response = postAdmin("/api/v1/admin/questions", payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("code", "VALIDATION_ERROR");
  }

  @Test
  void caseQuestionDetailKeepsSubQuestionId() {
    Long questionId = createQuestion(casePayload());
    assertThat(postAdmin("/api/v1/admin/questions/" + questionId + "/publish", Map.of()).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> response = getStudent("/api/v1/questions/" + questionId);

    List<Map<String, Object>> subQuestions = (List<Map<String, Object>>) data(response).get("subQuestions");
    assertThat(subQuestions).hasSize(2);
    assertThat(subQuestions).allSatisfy(subQuestion -> assertThat(subQuestion.get("subQuestionId")).isNotNull());
  }

  @Test
  void knowledgeBindingsAreSavedAndReturnedInDetail() {
    Long questionId = createQuestion(singleChoicePayload("知识点绑定题"));
    assertThat(postAdmin("/api/v1/admin/questions/" + questionId + "/publish", Map.of()).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> response = getStudent("/api/v1/questions/" + questionId);

    List<Map<String, Object>> bindings = (List<Map<String, Object>>) data(response).get("knowledgeBindings");
    assertThat(bindings).hasSize(1);
    assertThat(bindings.get(0)).containsEntry("knowledgeType", "SYLLABUS");
  }

  @Test
  void subQuestionKnowledgeBindingMustBelongToCurrentQuestion() {
    Long firstQuestionId = createQuestion(singleChoicePayload("第一题"));
    Long secondQuestionId = createQuestion(singleChoicePayload("第二题"));
    ResponseEntity<Map> secondDetail = postAdmin("/api/v1/admin/questions/" + secondQuestionId + "/publish", Map.of());
    List<Map<String, Object>> secondSubQuestions = (List<Map<String, Object>>) data(secondDetail).get("subQuestions");
    Long foreignSubQuestionId = ((Number) secondSubQuestions.get(0).get("subQuestionId")).longValue();

    ResponseEntity<Map> response = postAdmin(
        "/api/v1/admin/questions/" + firstQuestionId + "/knowledge-bindings",
        Map.of(
            "targetType", "SUB_QUESTION",
            "targetId", foreignSubQuestionId,
            "knowledgeType", "SYLLABUS",
            "knowledgeId", 1001));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("code", "VALIDATION_ERROR");
    assertThat(response.getBody()).containsKey("traceId");
  }

  @Test
  void creatingQuestionWithMissingQualificationReturnsValidationError() {
    Map<String, Object> payload = singleChoicePayload("无效资格题");
    payload.put("qualificationId", 9999);

    ResponseEntity<Map> response = postAdmin("/api/v1/admin/questions", payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("code", "VALIDATION_ERROR");
  }

  @Test
  void creatingQuestionWithSubjectOutsideQualificationReturnsValidationError() {
    Map<String, Object> payload = singleChoicePayload("跨资格科目题");
    payload.put("qualificationId", 2);
    payload.put("subjectId", 1);

    ResponseEntity<Map> response = postAdmin("/api/v1/admin/questions", payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("code", "VALIDATION_ERROR");
  }

  @Test
  void bindingMissingSyllabusKnowledgeReturnsValidationError() {
    Map<String, Object> payload = singleChoicePayload("无效考纲绑定题");
    Map<String, Object> subQuestion = (Map<String, Object>) ((List<?>) payload.get("subQuestions")).get(0);
    subQuestion.put("knowledgeBindings", List.of(Map.of("knowledgeType", "SYLLABUS", "knowledgeId", 9999)));

    ResponseEntity<Map> response = postAdmin("/api/v1/admin/questions", payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("code", "VALIDATION_ERROR");
  }

  @Test
  void bindingChapterKnowledgeFromOtherSubjectReturnsValidationError() {
    Map<String, Object> payload = singleChoicePayload("跨科目章节绑定题");
    Map<String, Object> subQuestion = (Map<String, Object>) ((List<?>) payload.get("subQuestions")).get(0);
    subQuestion.put("knowledgeBindings", List.of(Map.of("knowledgeType", "CHAPTER", "knowledgeId", 2002)));

    ResponseEntity<Map> response = postAdmin("/api/v1/admin/questions", payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("code", "VALIDATION_ERROR");
  }

  @Test
  void bindingTagFromOtherSubjectReturnsValidationError() {
    Map<String, Object> payload = singleChoicePayload("跨科目标签绑定题");
    Map<String, Object> subQuestion = (Map<String, Object>) ((List<?>) payload.get("subQuestions")).get(0);
    subQuestion.put("knowledgeBindings", List.of(Map.of("knowledgeType", "TAG", "knowledgeId", 3002)));

    ResponseEntity<Map> response = postAdmin("/api/v1/admin/questions", payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("code", "VALIDATION_ERROR");
  }

  private Long createQuestion(Map<String, Object> payload) {
    ResponseEntity<Map> response = postAdmin("/api/v1/admin/questions", payload);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return ((Number) data(response).get("questionId")).longValue();
  }

  private Map<String, Object> singleChoicePayload(String title) {
    return new java.util.LinkedHashMap<>(Map.of(
        "qualificationId", 1,
        "subjectId", 1,
        "questionType", "SINGLE_CHOICE",
        "title", title,
        "yearNo", 2026,
        "difficulty", "MEDIUM",
        "stems", List.of(Map.of("stemType", "MAIN_STEM", "content", "项目范围管理的主要输出是？", "sortOrder", 1)),
        "subQuestions", List.of(new java.util.LinkedHashMap<>(Map.of(
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
                "solutions", List.of(Map.of("solutionType", "STANDARD", "content", "关键路径需要重新计算。"))),
            Map.of(
                "subQuestionNo", 2,
                "subQuestionType", "SHORT_ANSWER",
                "content", "说明可采取的进度压缩措施。",
                "score", 8,
                "answerFormat", "TEXT",
                "gradingMode", "AI_ASSISTED",
                "solutions", List.of(Map.of("solutionType", "STANDARD", "content", "可采用赶工和快速跟进。")))));
  }

  private ResponseEntity<Map> postAdmin(String path, Object body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("dev-admin-token");
    return restTemplate.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
  }

  private ResponseEntity<Map> getStudent(String path) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("dev-student-token");
    return restTemplate.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
  }

  private Map<String, Object> data(ResponseEntity<Map> response) {
    return (Map<String, Object>) response.getBody().get("data");
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private void createTables() {
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
          CONSTRAINT uk_q_sub_question_no UNIQUE (question_id, sub_question_no)
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
          (3002, 1, 2, 'TAG-2', '案例', 'CASE', 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
          (3003, NULL, NULL, 'TAG-GLOBAL', '通用', 'ABILITY', 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """);
  }
}
