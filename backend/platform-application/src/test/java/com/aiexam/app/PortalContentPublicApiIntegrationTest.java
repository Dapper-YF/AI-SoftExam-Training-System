package com.aiexam.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
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
        "aiexam.portal.crawler.enabled=false",
        "aiexam.test.context=portal-public-api"
    }
)
class PortalContentPublicApiIntegrationTest {
  @LocalServerPort
  int port;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  TestRestTemplate restTemplate;

  @BeforeEach
  void prepareContentTable() {
    jdbc.execute("DROP TABLE IF EXISTS portal_content");
    jdbc.execute("""
        CREATE TABLE portal_content (
          id BIGINT PRIMARY KEY, source_name VARCHAR(100), source_url VARCHAR(100), title VARCHAR(500),
          summary VARCHAR(1000), published_at TIMESTAMP, category VARCHAR(20), content_type VARCHAR(20),
          original_title VARCHAR(500), original_summary VARCHAR(1000), source_published_at TIMESTAMP,
          previous_content_hash VARCHAR(64), review_category VARCHAR(20), review_content_type VARCHAR(20),
          content_hash VARCHAR(64), status VARCHAR(20), first_seen_at TIMESTAMP, last_crawled_at TIMESTAMP,
          change_summary VARCHAR(1000), updated_at TIMESTAMP
        )
        """);
    Instant now = Instant.parse("2026-07-01T00:00:00Z");
    insert(1L, "报名通知", "REGISTRATION", "NEWS", now);
    insert(2L, "考试大纲", "OFFICIAL_RESOURCE", "RESOURCE", now);
    insert(3L, "待审核报名通知", "REGISTRATION", "NEWS", now, "PENDING_REVIEW");
  }

  @Test
  void anonymousVisitorsReceiveOnlyPublishedPortalMetadata() {
    ResponseEntity<Map> news = restTemplate.getForEntity(url("/api/v1/public/portal/news?category=REGISTRATION"), Map.class);
    ResponseEntity<Map> resources = restTemplate.getForEntity(url("/api/v1/public/portal/resources"), Map.class);

    assertThat(news.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(news.getBody()).containsEntry("success", true);
    assertThat((java.util.List<?>) news.getBody().get("data")).hasSize(1);
    Map<String, Object> item = (Map<String, Object>) ((java.util.List<?>) news.getBody().get("data")).get(0);
    assertThat(item).containsKeys("title", "summary", "publishedAt", "category", "tag", "sourceName", "sourceUrl");
    assertThat(item).doesNotContainKey("content");

    assertThat(resources.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat((java.util.List<?>) resources.getBody().get("data")).hasSize(1);
  }

  private void insert(long id, String title, String category, String type, Instant publishedAt) {
    insert(id, title, category, type, publishedAt, "PUBLISHED");
  }

  private void insert(long id, String title, String category, String type, Instant publishedAt, String status) {
    jdbc.update("""
        INSERT INTO portal_content (
          id, source_name, source_url, title, summary, published_at, category, content_type,
          original_title, original_summary, source_published_at, review_category, review_content_type,
          content_hash, status, first_seen_at, last_crawled_at, updated_at
        ) VALUES (?, '中国计算机技术职业资格网', ?, ?, '官方摘要', ?, ?, ?, ?, '官方摘要', ?, ?, ?, 'hash', ?, ?, ?, ?)
        """, id, "https://www.ruankao.org.cn/notice/" + id, title, Timestamp.from(publishedAt), category, type,
        title, Timestamp.from(publishedAt), category, type, status, Timestamp.from(publishedAt), Timestamp.from(publishedAt),
        Timestamp.from(publishedAt));
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
