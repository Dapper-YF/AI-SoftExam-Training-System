package com.aiexam.app.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LearningResourceRepositoryTest {
  private JdbcTemplate jdbc;
  private LearningResourceRepository repository;

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:learning_resource;MODE=MySQL;DB_CLOSE_DELAY=-1"));
    jdbc.execute("DROP ALL OBJECTS");
    jdbc.execute("CREATE TABLE exam_qualification (id BIGINT PRIMARY KEY)");
    jdbc.update("INSERT INTO exam_qualification (id) VALUES (1)");
    jdbc.execute("""
        CREATE TABLE learning_resource (
          id BIGINT PRIMARY KEY, qualification_id BIGINT NOT NULL, resource_code VARCHAR(120) NOT NULL,
          resource_type VARCHAR(32) NOT NULL, title VARCHAR(300) NOT NULL, summary VARCHAR(1000),
          status VARCHAR(24) NOT NULL, copyright_status VARCHAR(32) NOT NULL, current_version_id BIGINT,
          sort_order INT NOT NULL, created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL,
          deleted TINYINT NOT NULL DEFAULT 0, row_version BIGINT NOT NULL DEFAULT 0
        )
        """);
    jdbc.execute("""
        CREATE TABLE learning_resource_version (
          id BIGINT PRIMARY KEY, resource_id BIGINT NOT NULL, version_no INT NOT NULL,
          version_label VARCHAR(120), source_name VARCHAR(300), source_ref VARCHAR(700), file_url VARCHAR(1000) NOT NULL,
          file_name VARCHAR(300) NOT NULL, mime_type VARCHAR(120) NOT NULL, file_size BIGINT NOT NULL,
          file_sha256 VARCHAR(64) NOT NULL, page_count INT, cover_url VARCHAR(1000),
          extracted_text_status VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED', status VARCHAR(24) NOT NULL DEFAULT 'DRAFT', usage_restriction VARCHAR(255) NOT NULL,
          created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP, deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbc.execute("""
        CREATE TABLE learning_resource_chapter (
          id BIGINT PRIMARY KEY, version_id BIGINT NOT NULL, parent_id BIGINT, chapter_code VARCHAR(120) NOT NULL,
          chapter_title VARCHAR(300) NOT NULL, level_no INT NOT NULL, page_no INT NOT NULL, end_page_no INT,
          sort_order INT NOT NULL, status VARCHAR(24) NOT NULL DEFAULT 'ENABLED',
          created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
          deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    jdbc.execute("""
        CREATE TABLE learning_knowledge_point (
          id BIGINT PRIMARY KEY, qualification_id BIGINT NOT NULL, parent_id BIGINT, point_code VARCHAR(120) NOT NULL,
          point_name VARCHAR(300) NOT NULL, description VARCHAR(2000), level_no INT NOT NULL, sort_order INT NOT NULL,
          status VARCHAR(24) NOT NULL DEFAULT 'ENABLED', created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
          updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP, deleted TINYINT NOT NULL DEFAULT 0
        )
        """);
    repository = new LearningResourceRepository(jdbc);
  }

  @Test
  void createsPublishesAndIndexesResourceContent() {
    LearningResourceDto resource = repository.insert(new LearningResourceCreateRequest(
        1L, "HIGH_PM_TUTORIAL_4", LearningResourceType.OFFICIAL_TUTORIAL,
        "信息系统项目管理师教程（第四版）", "核心教材", null, 1), java.time.Instant.now());
    assertThat(resource.status()).isEqualTo(LearningResourceStatus.DRAFT);

    LearningResourceVersionDto version = repository.addVersion(resource.id(), new LearningResourceVersionRequest(
        "第4版", "测试来源", "internal://high-pm-4", "/learning/high-pm.pdf", "high-pm.pdf",
        "application/pdf", 1024L, "a".repeat(64), 745, "/learning/high-pm.png", "INTERNAL_ONLY"), java.time.Instant.now());
    repository.replaceChapters(version.id(), List.of(
        new LearningChapterWriteRequest("root", "目录", null, 1, 7, null, 1),
        new LearningChapterWriteRequest("ch-1", "信息化发展与数字化转型", "root", 2, 15, 40, 2)));
    repository.publishVersion(resource.id(), version.id(), java.time.Instant.now());

    LearningResourceDto published = repository.find(resource.id());
    assertThat(published.status()).isEqualTo(LearningResourceStatus.PUBLISHED);
    assertThat(published.currentVersionId()).isEqualTo(version.id());
    assertThat(repository.chapters(version.id())).extracting(LearningChapterDto::chapterTitle)
        .containsExactly("目录", "信息化发展与数字化转型");

    LearningKnowledgePointDto point = repository.createKnowledgePoint(new LearningKnowledgePointRequest(
        1L, null, "PM-01", "项目管理基础", "基础知识", 1, 1), java.time.Instant.now());
    assertThat(repository.knowledgePoints(1L)).extracting(LearningKnowledgePointDto::id).contains(point.id());
  }
}
