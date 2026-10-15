package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

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
        "aiexam.test.context=essay-basic"
    }
)
class EssayBasicIntegrationTest {
  @LocalServerPort
  int port;

  @Autowired
  TestRestTemplate restTemplate;

  @Autowired
  JdbcTemplate jdbcTemplate;

  @BeforeEach
  void resetEssayTables() {
    createTables();
    for (String table : List.of(
        "essay_draft_revision",
        "essay_draft_material",
        "essay_draft",
        "essay_material",
        "essay_material_category",
        "essay_project_background",
        "exam_qualification")) {
      jdbcTemplate.execute("DELETE FROM " + table);
    }
    seedFoundationData();
  }

  @Test
  void anonymousBackgroundListReturnsUnifiedUnauthorizedEnvelope() {
    ResponseEntity<Map> response = getAnonymous("/api/v1/essay/backgrounds");

    assertUnauthorizedEnvelope(response);
  }

  @Test
  void anonymousMaterialListReturnsUnifiedUnauthorizedEnvelope() {
    ResponseEntity<Map> response = getAnonymous("/api/v1/essay/materials");

    assertUnauthorizedEnvelope(response);
  }

  @Test
  void anonymousDraftListReturnsUnifiedUnauthorizedEnvelope() {
    ResponseEntity<Map> response = getAnonymous("/api/v1/essay/drafts");

    assertUnauthorizedEnvelope(response);
  }

  @Test
  void generatedBackgroundIsPersistedAndListedForCurrentUser() {
    ResponseEntity<Map> generated = postStudent("/api/v1/essay/backgrounds/generate", backgroundGeneratePayload());

    assertThat(generated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> background = data(generated);
    assertThat(background).containsEntry("qualificationId", 1);
    assertThat(background).containsEntry("sourceType", "AI");
    assertThat((String) background.get("backgroundContent")).contains("Mock");

    ResponseEntity<Map> listed = getStudent("/api/v1/essay/backgrounds?pageNo=1&pageSize=10");

    assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> page = data(listed);
    assertThat(page).containsEntry("pageNo", 1);
    assertThat(page).containsEntry("pageSize", 10);
    assertThat((List<?>) page.get("items")).hasSize(1);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM essay_project_background", Long.class)).isEqualTo(1);
  }

  @Test
  void generatedMaterialIsPersistedForEssayDomain() {
    Long backgroundId = id(postStudent("/api/v1/essay/backgrounds/generate", backgroundGeneratePayload()));

    ResponseEntity<Map> generated = postStudent("/api/v1/essay/materials/generate", Map.of(
        "qualificationId", 1,
        "backgroundId", backgroundId,
        "materialType", "TOOL",
        "title", "进度控制工具",
        "topic", "进度管理"));

    assertThat(generated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> material = data(generated);
    assertThat(material).containsEntry("backgroundId", backgroundId);
    assertThat(material).containsEntry("sourceType", "AI");
    assertThat((String) material.get("content")).contains("Mock");

    ResponseEntity<Map> listed = getStudent("/api/v1/essay/materials?pageNo=1&pageSize=10");

    assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat((List<?>) data(listed).get("items")).hasSize(1);
  }

  @Test
  void draftCanBeCreatedWithWordCountAndBindOwnMaterial() {
    Long backgroundId = id(postStudent("/api/v1/essay/backgrounds/generate", backgroundGeneratePayload()));
    Long materialId = id(postStudent("/api/v1/essay/materials/generate", Map.of(
        "qualificationId", 1,
        "backgroundId", backgroundId,
        "materialType", "PROCESS",
        "title", "整体管理素材",
        "topic", "整体管理")));

    ResponseEntity<Map> created = postStudent("/api/v1/essay/drafts", Map.of(
        "qualificationId", 1,
        "backgroundId", backgroundId,
        "title", "论信息系统项目整体管理",
        "outline", "一、背景\n二、过程\n三、总结",
        "content", "项目启动后我负责章程制定和干系人协调。",
        "status", "DRAFTING"));

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> draft = data(created);
    assertThat(((Number) draft.get("wordCount")).intValue()).isGreaterThan(0);

    Long draftId = ((Number) draft.get("id")).longValue();
    ResponseEntity<Map> bound = postStudent("/api/v1/essay/drafts/" + draftId + "/materials", Map.of(
        "materialIds", List.of(materialId)));

    assertThat(bound.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> detail = data(bound);
    assertThat((List<?>) detail.get("materials")).hasSize(1);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM essay_draft_material", Long.class)).isEqualTo(1);
  }

  @Test
  void userCanOnlyAccessOwnEssayAssets() {
    Long adminBackgroundId = id(postAdmin("/api/v1/essay/backgrounds", Map.of(
        "qualificationId", 1,
        "title", "管理员背景",
        "backgroundContent", "管理员项目背景")));
    Long studentBackgroundId = id(postStudent("/api/v1/essay/backgrounds", Map.of(
        "qualificationId", 1,
        "title", "学生背景",
        "backgroundContent", "学生项目背景")));
    Long adminMaterialId = id(postAdmin("/api/v1/essay/materials", manualMaterialPayload(adminBackgroundId, "管理员素材")));
    Long studentMaterialId = id(postStudent("/api/v1/essay/materials", manualMaterialPayload(studentBackgroundId, "学生素材")));
    Long adminDraftId = id(postAdmin("/api/v1/essay/drafts", Map.of(
        "qualificationId", 1,
        "backgroundId", adminBackgroundId,
        "title", "管理员草稿",
        "content", "管理员草稿正文")));
    Long studentDraftId = id(postStudent("/api/v1/essay/drafts", Map.of(
        "qualificationId", 1,
        "backgroundId", studentBackgroundId,
        "title", "学生草稿",
        "content", "学生草稿正文")));

    ResponseEntity<Map> listedBackgrounds = getStudent("/api/v1/essay/backgrounds?pageNo=1&pageSize=10");
    ResponseEntity<Map> listedMaterials = getStudent("/api/v1/essay/materials?pageNo=1&pageSize=10");
    ResponseEntity<Map> listedDrafts = getStudent("/api/v1/essay/drafts?pageNo=1&pageSize=10");
    ResponseEntity<Map> updateOther = putStudent("/api/v1/essay/backgrounds/" + adminBackgroundId, Map.of(
        "title", "越权修改",
        "backgroundContent", "不应该成功"));

    List<Map<String, Object>> backgrounds = (List<Map<String, Object>>) data(listedBackgrounds).get("items");
    assertThat(backgrounds).extracting(item -> ((Number) item.get("id")).longValue())
        .containsExactly(studentBackgroundId);
    List<Map<String, Object>> materials = (List<Map<String, Object>>) data(listedMaterials).get("items");
    assertThat(materials).extracting(item -> ((Number) item.get("id")).longValue())
        .containsExactly(studentMaterialId);
    assertThat(materials).extracting(item -> ((Number) item.get("id")).longValue())
        .doesNotContain(adminMaterialId);
    List<Map<String, Object>> drafts = (List<Map<String, Object>>) data(listedDrafts).get("items");
    assertThat(drafts).extracting(item -> ((Number) item.get("id")).longValue())
        .containsExactly(studentDraftId);
    assertThat(drafts).extracting(item -> ((Number) item.get("id")).longValue())
        .doesNotContain(adminDraftId);
    assertThat(updateOther.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void materialListIsPagedForCurrentUser() {
    Long backgroundId = id(postStudent("/api/v1/essay/backgrounds/generate", backgroundGeneratePayload()));
    postStudent("/api/v1/essay/materials", manualMaterialPayload(backgroundId, "素材一"));
    postStudent("/api/v1/essay/materials", manualMaterialPayload(backgroundId, "素材二"));
    postAdmin("/api/v1/essay/materials", manualMaterialPayload(null, "其他用户素材"));

    ResponseEntity<Map> firstPage = getStudent("/api/v1/essay/materials?pageNo=1&pageSize=1");

    assertThat(firstPage.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> page = data(firstPage);
    assertThat((List<?>) page.get("items")).hasSize(1);
    assertThat(page).containsEntry("total", 2);
    assertThat(page).containsEntry("hasNext", true);
  }

  @Test
  void bindingOtherUsersMaterialReturnsNotFound() {
    Long draftId = id(postStudent("/api/v1/essay/drafts", Map.of(
        "qualificationId", 1,
        "title", "学生草稿",
        "content", "学生草稿正文")));
    Long adminMaterialId = id(postAdmin("/api/v1/essay/materials", manualMaterialPayload(null, "管理员素材")));

    ResponseEntity<Map> response = postStudent("/api/v1/essay/drafts/" + draftId + "/materials", Map.of(
        "materialIds", List.of(adminMaterialId)));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "NOT_FOUND");
  }

  private Map<String, Object> backgroundGeneratePayload() {
    return Map.of(
        "qualificationId", 1,
        "industry", "政务信息化",
        "projectScale", "中型项目",
        "projectCycle", "12个月",
        "techStack", "Spring Boot, Vue, MySQL, Redis",
        "teamSize", "12人");
  }

  private Map<String, Object> manualMaterialPayload(Long backgroundId, String title) {
    java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("qualificationId", 1);
    payload.put("backgroundId", backgroundId);
    payload.put("materialType", "TOOL");
    payload.put("title", title);
    payload.put("content", title + "内容");
    payload.put("tags", "进度,工具");
    return payload;
  }

  private ResponseEntity<Map> postStudent(String path, Object body) {
    return exchange(path, HttpMethod.POST, body, "dev-student-token");
  }

  private ResponseEntity<Map> postAdmin(String path, Object body) {
    return exchange(path, HttpMethod.POST, body, "dev-admin-token");
  }

  private ResponseEntity<Map> putStudent(String path, Object body) {
    return exchange(path, HttpMethod.PUT, body, "dev-student-token");
  }

  private ResponseEntity<Map> getStudent(String path) {
    return exchange(path, HttpMethod.GET, null, "dev-student-token");
  }

  private ResponseEntity<Map> getAnonymous(String path) {
    return restTemplate.exchange(url(path), HttpMethod.GET, HttpEntity.EMPTY, Map.class);
  }

  private ResponseEntity<Map> exchange(String path, HttpMethod method, Object body, String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return restTemplate.exchange(url(path), method, new HttpEntity<>(body, headers), Map.class);
  }

  private Long id(ResponseEntity<Map> response) {
    assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.CREATED);
    return ((Number) data(response).get("id")).longValue();
  }

  private Map<String, Object> data(ResponseEntity<Map> response) {
    return (Map<String, Object>) response.getBody().get("data");
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private void assertUnauthorizedEnvelope(ResponseEntity<Map> response) {
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(response.getBody()).containsEntry("errorCode", "AUTH_SESSION_EXPIRED");
    assertThat(response.getBody()).containsKeys("message", "traceId");
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
        CREATE TABLE IF NOT EXISTS essay_project_background (
          id BIGINT NOT NULL PRIMARY KEY,
          user_id BIGINT NOT NULL,
          qualification_id BIGINT NOT NULL,
          title VARCHAR(160) NOT NULL,
          industry VARCHAR(96) NULL,
          project_scale VARCHAR(96) NULL,
          project_cycle VARCHAR(96) NULL,
          tech_stack VARCHAR(255) NULL,
          team_size VARCHAR(64) NULL,
          background_content LONGTEXT NOT NULL,
          source_type VARCHAR(32) NOT NULL DEFAULT 'AI',
          status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS essay_material_category (
          id BIGINT NOT NULL PRIMARY KEY,
          qualification_id BIGINT NOT NULL,
          parent_id BIGINT NULL,
          category_code VARCHAR(96) NOT NULL,
          category_name VARCHAR(128) NOT NULL,
          category_type VARCHAR(32) NOT NULL,
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
        CREATE TABLE IF NOT EXISTS essay_material (
          id BIGINT NOT NULL PRIMARY KEY,
          user_id BIGINT NULL,
          qualification_id BIGINT NOT NULL,
          background_id BIGINT NULL,
          category_id BIGINT NULL,
          material_type VARCHAR(32) NOT NULL,
          title VARCHAR(160) NOT NULL,
          content LONGTEXT NOT NULL,
          source_type VARCHAR(32) NOT NULL DEFAULT 'AI',
          status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
          tags VARCHAR(255) NULL,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS essay_draft (
          id BIGINT NOT NULL PRIMARY KEY,
          user_id BIGINT NOT NULL,
          qualification_id BIGINT NOT NULL,
          background_id BIGINT NULL,
          title VARCHAR(180) NOT NULL,
          outline LONGTEXT NULL,
          content LONGTEXT NULL,
          word_count INT NOT NULL DEFAULT 0,
          writing_seconds INT NULL,
          status VARCHAR(32) NOT NULL DEFAULT 'DRAFTING',
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL,
          updated_by BIGINT NULL,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS essay_draft_material (
          draft_id BIGINT NOT NULL,
          material_id BIGINT NOT NULL,
          created_at TIMESTAMP NOT NULL,
          PRIMARY KEY (draft_id, material_id)
        )
        """);
    jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS essay_draft_revision (
          id BIGINT NOT NULL PRIMARY KEY,
          draft_id BIGINT NOT NULL,
          revision_no INT NOT NULL,
          content_snapshot LONGTEXT NULL,
          summary VARCHAR(500) NULL,
          word_count INT NULL,
          created_at TIMESTAMP NOT NULL,
          created_by BIGINT NULL
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
  }
}
