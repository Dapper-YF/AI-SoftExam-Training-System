package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
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
        "aiexam.security.dev-bearer.enabled=true"
    }
)
class FoundationCrudIntegrationTest {
  @LocalServerPort
  int port;

  @Autowired
  TestRestTemplate restTemplate;

  @Autowired
  JdbcTemplate jdbcTemplate;

  @BeforeEach
  void resetFoundationTables() {
    createTables();
    for (String table : List.of(
        "exam_rule",
        "kp_relation",
        "kp_tag",
        "kp_chapter_node",
        "kp_syllabus_node",
        "exam_subject",
        "exam_qualification")) {
      jdbcTemplate.execute("DELETE FROM " + table);
    }
  }

  @Test
  void adminCreatesQualificationSuccessfully() {
    ResponseEntity<Map> response = postAdmin("/api/v1/admin/qualifications", Map.of(
        "qualificationCode", "GAOXIANG",
        "domainCode", "SOFT_EXAM",
        "qualificationName", "高项",
        "officialName", "信息系统项目管理师",
        "levelCode", "ADVANCED",
        "verificationStatus", "THIRD_PARTY_CANDIDATE",
        "catalogVersion", "2026-CUSTOM",
        "catalogStatus", "ACTIVE",
        "sortOrder", 1));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).containsEntry("success", true);
    assertThat(response.getBody()).containsKeys("data", "traceId");
    Map<String, Object> data = data(response);
    assertThat(data).containsEntry("qualificationCode", "GAOXIANG");
  }

  @Test
  void duplicateQualificationCodeReturnsUnifiedConflictEnvelope() {
    Map<String, Object> payload = qualificationPayload("GAOXIANG");
    assertThat(postAdmin("/api/v1/admin/qualifications", payload).getStatusCode()).isEqualTo(HttpStatus.CREATED);

    ResponseEntity<Map> duplicate = postAdmin("/api/v1/admin/qualifications", payload);

    assertThat(duplicate.getStatusCode()).isIn(HttpStatus.CONFLICT, HttpStatus.BAD_REQUEST);
    assertThat(duplicate.getBody()).containsEntry("success", false);
    assertThat(duplicate.getBody()).containsKeys("errorCode", "message", "traceId");
  }

  @Test
  void createdSubjectsCanBeQueriedByQualificationId() {
    Long qualificationId = createQualification("GAOXIANG");
    createSubject(qualificationId, "COMPREHENSIVE", "综合知识", "COMPREHENSIVE");
    createSubject(qualificationId, "CASE", "案例分析", "CASE");
    createSubject(qualificationId, "ESSAY", "论文", "ESSAY");

    ResponseEntity<Map> response = getStudent("/api/v1/foundation/qualifications/" + qualificationId + "/subjects");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> page = data(response);
    assertThat(page).containsKeys("items", "pageNo", "pageSize", "total", "hasNext");
    assertThat((List<?>) page.get("items")).hasSize(3);
  }

  @Test
  void createdSyllabusTreeCanBeQueriedWithChildren() {
    Long qualificationId = createQualification("GAOXIANG");
    Long subjectId = createSubject(qualificationId, "COMPREHENSIVE", "综合知识", "COMPREHENSIVE");
    Long rootId = createSyllabusNode(qualificationId, subjectId, null, "2026", "ROOT", "信息化基础", 1);
    createSyllabusNode(qualificationId, subjectId, rootId, "2026", "ROOT-01", "信息系统", 2);

    ResponseEntity<Map> response = getStudent("/api/v1/foundation/subjects/" + subjectId + "/syllabus-nodes");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> page = data(response);
    List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
    assertThat(items).hasSize(1);
    assertThat((List<?>) items.get(0).get("children")).hasSize(1);
  }

  @Test
  void sameSubjectParentChildNodesCanBeCreated() {
    Long qualificationId = createQualification("GAOXIANG");
    Long subjectId = createSubject(qualificationId, "COMPREHENSIVE", "综合知识", "COMPREHENSIVE");

    Long syllabusRootId = createSyllabusNode(qualificationId, subjectId, null, "2026", "SY-ROOT", "范围管理", 1);
    Long syllabusChildId = createSyllabusNode(qualificationId, subjectId, syllabusRootId, "2026", "SY-CHILD", "范围定义", 2);
    Long chapterRootId = createChapterNode(qualificationId, subjectId, null, "CH-ROOT", "第1章", 1);
    Long chapterChildId = createChapterNode(qualificationId, subjectId, chapterRootId, "CH-CHILD", "第1节", 2);

    assertThat(syllabusChildId).isPositive();
    assertThat(chapterChildId).isPositive();
  }

  @Test
  void syllabusParentFromOtherSubjectIsRejectedWithUnifiedEnvelope() {
    Long qualificationId = createQualification("GAOXIANG");
    Long subjectA = createSubject(qualificationId, "COMPREHENSIVE", "综合知识", "COMPREHENSIVE");
    Long subjectB = createSubject(qualificationId, "CASE", "案例分析", "CASE");
    Long otherSubjectParentId = createSyllabusNode(qualificationId, subjectA, null, "2026", "SY-A", "范围管理", 1);

    Map<String, Object> payload = syllabusNodePayload(
        qualificationId, subjectB, otherSubjectParentId, "2026", "SY-B-CHILD", "跨科目子节点", 2);
    ResponseEntity<Map> response = postAdmin("/api/v1/admin/syllabus-nodes", payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "VALIDATION_ERROR");
    assertThat(response.getBody()).containsKey("traceId");
  }

  @Test
  void chapterParentFromOtherQualificationIsRejectedWithUnifiedEnvelope() {
    Long qualificationA = createQualification("GAOXIANG");
    Long subjectA = createSubject(qualificationA, "COMPREHENSIVE", "综合知识", "COMPREHENSIVE");
    Long qualificationB = createQualification("RUANSHE");
    Long subjectB = createSubject(qualificationB, "COMPREHENSIVE", "综合知识", "COMPREHENSIVE");
    Long otherQualificationParentId = createChapterNode(qualificationA, subjectA, null, "CH-A", "第1章", 1);

    Map<String, Object> payload = chapterNodePayload(qualificationB, subjectB, otherQualificationParentId, "CH-B-CHILD", "跨资格子节点", 2);
    ResponseEntity<Map> response = postAdmin("/api/v1/admin/chapter-nodes", payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "VALIDATION_ERROR");
    assertThat(response.getBody()).containsKey("traceId");
  }

  @Test
  void knowledgeTagSubjectMustBelongToRequestedQualification() {
    Long qualificationA = createQualification("GAOXIANG");
    createSubject(qualificationA, "COMPREHENSIVE", "缁煎悎鐭ヨ瘑", "COMPREHENSIVE");
    Long qualificationB = createQualification("RUANSHE");
    Long subjectB = createSubject(qualificationB, "CASE", "妗堜緥鍒嗘瀽", "CASE");

    ResponseEntity<Map> response = postAdmin("/api/v1/admin/knowledge-tags", Map.of(
        "qualificationId", qualificationA,
        "subjectId", subjectB,
        "tagCode", "CROSS-SCOPE",
        "tagName", "Cross Scope",
        "tagType", "CASE"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "VALIDATION_ERROR");
  }

  @Test
  void knowledgeRelationsCannotCrossQualifications() {
    Long qualificationA = createQualification("REL-Q-A");
    Long subjectA = createSubject(qualificationA, "REL-S-A", "Subject A", "COMPREHENSIVE");
    Long sourceId = createSyllabusNode(qualificationA, subjectA, null, "2026", "REL-SY-A", "Source", 1);
    Long qualificationB = createQualification("REL-Q-B");
    Long subjectB = createSubject(qualificationB, "REL-S-B", "Subject B", "CASE");
    Long targetId = createChapterNode(qualificationB, subjectB, null, "REL-CH-B", "Target", 1);

    ResponseEntity<Map> response = postAdmin(
        "/api/v1/admin/knowledge-relations",
        relationPayload("SYLLABUS", sourceId, "CHAPTER", targetId));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("errorCode", "VALIDATION_ERROR");
    assertThat(relationCount()).isZero();
  }

  @Test
  void knowledgeRelationsCannotCrossSubjectsWithinQualification() {
    Long qualificationId = createQualification("REL-Q-SUBJECT");
    Long subjectA = createSubject(qualificationId, "REL-S-1", "Subject One", "COMPREHENSIVE");
    Long subjectB = createSubject(qualificationId, "REL-S-2", "Subject Two", "CASE");
    Long sourceId = createSyllabusNode(qualificationId, subjectA, null, "2026", "REL-SY-1", "Source", 1);
    Long targetId = createChapterNode(qualificationId, subjectB, null, "REL-CH-2", "Target", 1);

    ResponseEntity<Map> response = postAdmin(
        "/api/v1/admin/knowledge-relations",
        relationPayload("SYLLABUS", sourceId, "CHAPTER", targetId));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("errorCode", "VALIDATION_ERROR");
    assertThat(relationCount()).isZero();
  }

  @Test
  void knowledgeRelationsRejectDisabledNodesBeforeWrite() {
    Long qualificationId = createQualification("REL-Q-DISABLED");
    Long subjectId = createSubject(qualificationId, "REL-S-DISABLED", "Subject", "COMPREHENSIVE");
    Long sourceId = createSyllabusNode(qualificationId, subjectId, null, "2026", "REL-SY-D", "Source", 1);
    Long targetId = createChapterNode(qualificationId, subjectId, null, "REL-CH-D", "Target", 1);
    jdbcTemplate.update("UPDATE kp_chapter_node SET status = 'DISABLED' WHERE id = ?", targetId);

    ResponseEntity<Map> response = postAdmin(
        "/api/v1/admin/knowledge-relations",
        relationPayload("SYLLABUS", sourceId, "CHAPTER", targetId));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).containsEntry("errorCode", "NOT_FOUND");
    assertThat(relationCount()).isZero();
  }

  @Test
  void knowledgeRelationsRejectDisabledParentQualificationBeforeWrite() {
    Long qualificationId = createQualification("REL-Q-PARENT-OFF");
    Long subjectId = createSubject(qualificationId, "REL-S-PARENT-Q", "Subject", "COMPREHENSIVE");
    Long sourceId = createSyllabusNode(qualificationId, subjectId, null, "2026", "REL-SY-PQ", "Source", 1);
    Long targetId = createChapterNode(qualificationId, subjectId, null, "REL-CH-PQ", "Target", 1);
    jdbcTemplate.update("UPDATE exam_qualification SET status = 'DISABLED' WHERE id = ?", qualificationId);

    ResponseEntity<Map> response = postAdmin(
        "/api/v1/admin/knowledge-relations",
        relationPayload("SYLLABUS", sourceId, "CHAPTER", targetId));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).containsEntry("errorCode", "NOT_FOUND");
    assertThat(relationCount()).isZero();
  }

  @Test
  void knowledgeRelationsRejectDisabledParentSubjectBeforeWrite() {
    Long qualificationId = createQualification("REL-Q-PARENT-S");
    Long subjectId = createSubject(qualificationId, "REL-S-PARENT-OFF", "Subject", "COMPREHENSIVE");
    Long sourceId = createSyllabusNode(qualificationId, subjectId, null, "2026", "REL-SY-PS", "Source", 1);
    Long targetId = createChapterNode(qualificationId, subjectId, null, "REL-CH-PS", "Target", 1);
    jdbcTemplate.update("UPDATE exam_subject SET status = 'DISABLED' WHERE id = ?", subjectId);

    ResponseEntity<Map> response = postAdmin(
        "/api/v1/admin/knowledge-relations",
        relationPayload("SYLLABUS", sourceId, "CHAPTER", targetId));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).containsEntry("errorCode", "NOT_FOUND");
    assertThat(relationCount()).isZero();
  }

  @Test
  void knowledgeRelationsRejectNodeWhoseSubjectBelongsToAnotherQualification() {
    Long qualificationA = createQualification("REL-Q-PARENT-A");
    Long subjectA = createSubject(qualificationA, "REL-S-PARENT-A", "Subject A", "COMPREHENSIVE");
    Long sourceId = createSyllabusNode(qualificationA, subjectA, null, "2026", "REL-SY-MISMATCH", "Source", 1);
    Long targetId = createChapterNode(qualificationA, subjectA, null, "REL-CH-MATCH", "Target", 1);
    Long qualificationB = createQualification("REL-Q-PARENT-B");
    jdbcTemplate.update("UPDATE kp_syllabus_node SET qualification_id = ? WHERE id = ?", qualificationB, sourceId);

    ResponseEntity<Map> response = postAdmin(
        "/api/v1/admin/knowledge-relations",
        relationPayload("SYLLABUS", sourceId, "CHAPTER", targetId));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("errorCode", "VALIDATION_ERROR");
    assertThat(relationCount()).isZero();
  }

  @Test
  void knowledgeRelationsRejectTagsWithoutCompleteScope() {
    Long qualificationId = createQualification("REL-Q-TAG");
    Long subjectId = createSubject(qualificationId, "REL-S-TAG", "Subject", "COMPREHENSIVE");
    Long sourceId = createSyllabusNode(qualificationId, subjectId, null, "2026", "REL-SY-T", "Source", 1);
    ResponseEntity<Map> tagResponse = postAdmin("/api/v1/admin/knowledge-tags", Map.of(
        "tagCode", "REL-UNSCOPED",
        "tagName", "Unscoped",
        "tagType", "TOPIC"));
    assertThat(tagResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Long targetId = id(tagResponse);

    ResponseEntity<Map> response = postAdmin(
        "/api/v1/admin/knowledge-relations",
        relationPayload("SYLLABUS", sourceId, "TAG", targetId));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("errorCode", "VALIDATION_ERROR");
    assertThat(relationCount()).isZero();
  }

  @Test
  void syllabusTreePaginatesRootNodesAndKeepsSubtree() {
    Long qualificationId = createQualification("GAOXIANG");
    Long subjectId = createSubject(qualificationId, "COMPREHENSIVE", "综合知识", "COMPREHENSIVE");
    Long rootA = createSyllabusNode(qualificationId, subjectId, null, "2026", "SY-A", "根A", 1);
    createSyllabusNode(qualificationId, subjectId, rootA, "2026", "SY-A-1", "子A1", 2);
    createSyllabusNode(qualificationId, subjectId, null, "2026", "SY-B", "根B", 1);

    ResponseEntity<Map> response = getStudent("/api/v1/foundation/subjects/" + subjectId + "/syllabus-nodes?pageNo=1&pageSize=1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> page = data(response);
    List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
    assertThat(items).hasSize(1);
    assertThat(((Number) page.get("total")).longValue()).isEqualTo(2L);
    assertThat((List<?>) items.get(0).get("children")).hasSize(1);
  }

  @Test
  void chapterTreePaginatesRootNodesAndKeepsSubtree() {
    Long qualificationId = createQualification("GAOXIANG");
    Long subjectId = createSubject(qualificationId, "COMPREHENSIVE", "综合知识", "COMPREHENSIVE");
    Long rootA = createChapterNode(qualificationId, subjectId, null, "CH-A", "根A", 1);
    createChapterNode(qualificationId, subjectId, rootA, "CH-A-1", "子A1", 2);
    createChapterNode(qualificationId, subjectId, null, "CH-B", "根B", 1);

    ResponseEntity<Map> response = getStudent("/api/v1/foundation/subjects/" + subjectId + "/chapter-nodes?pageNo=1&pageSize=1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> page = data(response);
    List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
    assertThat(items).hasSize(1);
    assertThat(((Number) page.get("total")).longValue()).isEqualTo(2L);
    assertThat((List<?>) items.get(0).get("children")).hasSize(1);
  }

  @Test
  void createdExamRuleCanBeQueriedByQualificationId() {
    Long qualificationId = createQualification("GAOXIANG");
    createExamRule(qualificationId);

    ResponseEntity<Map> response = getStudent("/api/v1/foundation/exam-rules?qualificationId=" + qualificationId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> page = data(response);
    List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
    assertThat(items).hasSize(1);
    assertThat(items.get(0)).containsEntry("ruleCode", "EXAM_PERIOD_RULE");
  }

  @Test
  void nonAdminCannotAccessAdminFoundationApi() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("dev-student-token");

    ResponseEntity<Map> response = restTemplate.exchange(
        url("/api/v1/admin/qualifications"),
        HttpMethod.POST,
        new HttpEntity<>(Map.of("qualificationCode", "X"), headers),
        Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "FORBIDDEN");
    assertThat(response.getBody()).containsKey("traceId");
  }

  @Test
  void resourceErrorsKeepUnifiedEnvelope() {
    ResponseEntity<Map> response = getStudent("/api/v1/foundation/qualifications/999999/subjects");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "NOT_FOUND");
    assertThat(response.getBody()).containsKey("traceId");
  }

  private Long createQualification(String code) {
    ResponseEntity<Map> response = postAdmin(
        "/api/v1/admin/qualifications", qualificationPayload(code));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return id(response);
  }

  private Long createSubject(Long qualificationId, String code, String name, String type) {
    ResponseEntity<Map> response = postAdmin("/api/v1/admin/subjects", Map.of(
        "qualificationId", qualificationId,
        "subjectCode", code,
        "subjectName", name,
        "officialName", name,
        "subjectType", type));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return id(response);
  }

  private Long createSyllabusNode(
      Long qualificationId,
      Long subjectId,
      Long parentId,
      String version,
      String code,
      String name,
      int levelNo
  ) {
    ResponseEntity<Map> response = postAdmin(
        "/api/v1/admin/syllabus-nodes",
        syllabusNodePayload(qualificationId, subjectId, parentId, version, code, name, levelNo));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return id(response);
  }

  private Map<String, Object> syllabusNodePayload(
      Long qualificationId,
      Long subjectId,
      Long parentId,
      String version,
      String code,
      String name,
      int levelNo
  ) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("qualificationId", qualificationId);
    payload.put("subjectId", subjectId);
    payload.put("parentId", parentId);
    payload.put("syllabusVersion", version);
    payload.put("nodeCode", code);
    payload.put("nodeName", name);
    payload.put("levelNo", levelNo);
    return payload;
  }

  private Long createChapterNode(
      Long qualificationId,
      Long subjectId,
      Long parentId,
      String code,
      String name,
      int levelNo
  ) {
    ResponseEntity<Map> response = postAdmin(
        "/api/v1/admin/chapter-nodes",
        chapterNodePayload(qualificationId, subjectId, parentId, code, name, levelNo));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return id(response);
  }

  private Map<String, Object> chapterNodePayload(
      Long qualificationId,
      Long subjectId,
      Long parentId,
      String code,
      String name,
      int levelNo
  ) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("qualificationId", qualificationId);
    payload.put("subjectId", subjectId);
    payload.put("parentId", parentId);
    payload.put("chapterCode", code);
    payload.put("chapterName", name);
    payload.put("levelNo", levelNo);
    return payload;
  }

  private Long createExamRule(Long qualificationId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("qualificationId", qualificationId);
    payload.put("ruleCode", "EXAM_PERIOD_RULE");
    payload.put("ruleName", "高项考试规则");
    payload.put("ruleVersion", "2026-H1-v1");
    payload.put("ruleJson", "{\"schemaVersion\":\"1.0\"}");
    payload.put("periodCode", "2026-H1");
    payload.put("effectiveFrom", "2026-01-01T00:00:00Z");
    payload.put("effectiveTo", "2026-07-01T00:00:00Z");
    payload.put("publishedAt", "2026-01-01T00:00:00Z");
    payload.put("version", 0);
    payload.put("status", "PUBLISHED");
    ResponseEntity<Map> response = postAdmin("/api/v1/admin/exam-rules", payload);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return id(response);
  }

  private Map<String, Object> relationPayload(
      String sourceType,
      Long sourceId,
      String targetType,
      Long targetId
  ) {
    return Map.of(
        "sourceType", sourceType,
        "sourceId", sourceId,
        "targetType", targetType,
        "targetId", targetId,
        "relationType", "RELATED");
  }

  private long relationCount() {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM kp_relation", Long.class);
  }

  private Map<String, Object> qualificationPayload(String code) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("qualificationCode", code);
    payload.put("domainCode", "SOFT_EXAM");
    payload.put("qualificationName", "高项");
    payload.put("officialName", "信息系统项目管理师");
    payload.put("levelCode", "ADVANCED");
    payload.put("verificationStatus", "THIRD_PARTY_CANDIDATE");
    payload.put("catalogVersion", "2026-CUSTOM");
    payload.put("catalogStatus", "ACTIVE");
    return payload;
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

  private Long id(ResponseEntity<Map> response) {
    return ((Number) data(response).get("id")).longValue();
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
          deleted TINYINT NOT NULL DEFAULT 0,
          CONSTRAINT uk_exam_qualification_code UNIQUE (qualification_code)
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
          deleted TINYINT NOT NULL DEFAULT 0,
          CONSTRAINT uk_exam_subject_code UNIQUE (qualification_id, subject_code)
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
          deleted TINYINT NOT NULL DEFAULT 0,
          CONSTRAINT uk_kp_syllabus_code UNIQUE (qualification_id, subject_id, syllabus_version, node_code)
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
          deleted TINYINT NOT NULL DEFAULT 0,
          CONSTRAINT uk_kp_chapter_code UNIQUE (qualification_id, subject_id, chapter_code)
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
          deleted TINYINT NOT NULL DEFAULT 0,
          CONSTRAINT uk_kp_tag_code UNIQUE (tag_code)
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS kp_relation (
          id BIGINT NOT NULL PRIMARY KEY,
          source_type VARCHAR(32) NOT NULL,
          source_id BIGINT NOT NULL,
          target_type VARCHAR(32) NOT NULL,
          target_id BIGINT NOT NULL,
          relation_type VARCHAR(32) NOT NULL,
          created_at TIMESTAMP NOT NULL,
          CONSTRAINT uk_kp_relation UNIQUE (source_type, source_id, target_type, target_id, relation_type)
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS exam_rule (
          id BIGINT NOT NULL PRIMARY KEY,
          qualification_id BIGINT NOT NULL,
          subject_id BIGINT NULL,
          rule_code VARCHAR(96) NOT NULL,
          rule_name VARCHAR(128) NOT NULL,
          rule_version VARCHAR(32) NOT NULL,
          rule_json LONGTEXT NOT NULL,
          status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
          effective_at TIMESTAMP NULL,
          period_code VARCHAR(32) NOT NULL,
          effective_from TIMESTAMP NOT NULL,
          effective_to TIMESTAMP NOT NULL,
          published_at TIMESTAMP NULL,
          version BIGINT NOT NULL DEFAULT 0,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0,
          CONSTRAINT uk_exam_rule_period_version UNIQUE (
            qualification_id, period_code, rule_code, rule_version
          )
        )
        """);
  }
}
