package com.aiexam.app.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

class PortalOfficialCrawlerTest {
  @Test
  void retainsOnlyOfficialHttpsNoticeLinksAndExtractsACompactSummary() {
    PortalCrawlerProperties properties = properties();
    PortalOfficialCrawler crawler = new PortalOfficialCrawler(properties, HttpClient.newHttpClient());

    assertThat(crawler.extractLinks("""
        <a href='/notice/1'>2026年上半年软考报名通知</a>
        <a href='https://example.com/a'>第三方软考资讯链接</a>
        """, java.net.URI.create("https://www.ruankao.org.cn/")))
        .containsOnlyKeys("https://www.ruankao.org.cn/notice/1");

    PortalCrawlCandidate candidate = crawler.parseDetail("""
        <h1>2026年上半年软考报名通知</h1>
        <article>发布时间：2026年07月01日。本通知用于安排报名、缴费和资格审核。</article>
        """, "https://www.ruankao.org.cn/notice/1", "fallback");
    assertThat(candidate.title()).isEqualTo("2026年上半年软考报名通知");
    assertThat(candidate.summary()).contains("资格审核");
    assertThat(candidate.publishedAt()).isBefore(java.time.Instant.parse("2026-07-02T00:00:00Z"));
  }

  @Test
  void rejectsNonOfficialUrls() {
    PortalOfficialCrawler crawler = new PortalOfficialCrawler(properties(), HttpClient.newHttpClient());
    assertThatThrownBy(() -> crawler.parseDetail("<h1>x</h1>", "http://www.ruankao.org.cn/a", "x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void detectsPdfByContentTypeOrFileSignature() {
    assertThat(new PortalOfficialCrawler.FetchedContent(
        "not important".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        "application/pdf").isPdf()).isTrue();
    assertThat(new PortalOfficialCrawler.FetchedContent(
        "%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        "application/octet-stream").isPdf()).isTrue();
    assertThat(new PortalOfficialCrawler.FetchedContent(
        "<html/>".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        "text/html").isPdf()).isFalse();
  }

  private PortalCrawlerProperties properties() {
    PortalCrawlerProperties properties = new PortalCrawlerProperties();
    properties.setAllowedHost("www.ruankao.org.cn");
    return properties;
  }
}
