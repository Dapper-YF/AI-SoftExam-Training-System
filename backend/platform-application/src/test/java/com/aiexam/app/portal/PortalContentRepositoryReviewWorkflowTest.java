package com.aiexam.app.portal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PortalContentRepositoryReviewWorkflowTest {
  private JdbcTemplate jdbc;
  private PortalContentRepository repository;
  private PortalContentClassifier.Classification classification;

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:portal-review;DB_CLOSE_DELAY=-1"));
    jdbc.execute("DROP TABLE IF EXISTS portal_content");
    jdbc.execute("""
        CREATE TABLE portal_content (
          id BIGINT PRIMARY KEY, source_name VARCHAR(100), source_url VARCHAR(700) UNIQUE,
          title VARCHAR(500), summary VARCHAR(1000), published_at TIMESTAMP,
          category VARCHAR(20), content_type VARCHAR(20), original_title VARCHAR(500),
          original_summary VARCHAR(1000), source_published_at TIMESTAMP,
          previous_content_hash VARCHAR(64), review_category VARCHAR(20), review_content_type VARCHAR(20),
          content_hash VARCHAR(64), status VARCHAR(20), first_seen_at TIMESTAMP,
          last_crawled_at TIMESTAMP, change_summary VARCHAR(1000), updated_at TIMESTAMP
        )
        """);
    repository = new PortalContentRepository(jdbc);
    classification = new PortalContentClassifier().classify("2026年软考报名通知");
  }

  @Test
  void newAndChangedContentStayPendingWithoutOverwritingApprovedFields() {
    Instant now = Instant.parse("2026-07-21T00:00:00Z");
    PortalCrawlCandidate first = new PortalCrawlCandidate(
        "2026年软考报名通知", "首次官方摘要", "https://www.ruankao.org.cn/notice/review", now);

    assertThat(repository.upsert(first, classification, now)).isEqualTo(PortalContentRepository.UpsertResult.CREATED);
    assertThat(status()).isEqualTo("PENDING_REVIEW");

    jdbc.update("UPDATE portal_content SET title = '人工确认标题', summary = '人工确认摘要', status = 'PUBLISHED'");
    PortalCrawlCandidate changed = new PortalCrawlCandidate(
        "2026年软考报名通知（更新）", "新的官方摘要", first.sourceUrl(), now.plusSeconds(60));

    assertThat(repository.upsert(changed, classification, now.plusSeconds(60)))
        .isEqualTo(PortalContentRepository.UpsertResult.UPDATED);
    var row = jdbc.queryForMap("SELECT title, summary, original_title, original_summary, status, previous_content_hash, change_summary FROM portal_content");
    assertThat(row).containsEntry("TITLE", "人工确认标题");
    assertThat(row).containsEntry("SUMMARY", "人工确认摘要");
    assertThat(row).containsEntry("ORIGINAL_TITLE", "2026年软考报名通知（更新）");
    assertThat(row).containsEntry("ORIGINAL_SUMMARY", "新的官方摘要");
    assertThat(row).containsEntry("STATUS", "PENDING_REVIEW");
    assertThat(row.get("PREVIOUS_CONTENT_HASH")).isNotNull();
    assertThat(row).containsEntry("CHANGE_SUMMARY", "内容哈希变化，等待人工审核");
  }

  private String status() {
    return jdbc.queryForObject("SELECT status FROM portal_content", String.class);
  }
}
