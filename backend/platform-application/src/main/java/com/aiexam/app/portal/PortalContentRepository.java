package com.aiexam.app.portal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.net.URI;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import com.aiexam.common.api.PageResponse;
import com.aiexam.common.error.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

@Repository
public class PortalContentRepository {
  private static final AtomicLong ID_SEQUENCE = new AtomicLong(Instant.now().toEpochMilli() * 1_000L);
  private final JdbcTemplate jdbc;

  public PortalContentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<PortalContentDto> findPublished(PortalContentType contentType, PortalContentCategory category, int limit) {
    String sql = """
        SELECT id, title, summary, published_at, category, source_name, source_url
        FROM portal_content
        WHERE status = 'PUBLISHED' AND content_type = ? AND category = ?
        ORDER BY is_pinned DESC, pin_order ASC, published_at DESC, id DESC LIMIT ?
        """;
    return jdbc.query(sql, (rs, ignored) -> new PortalContentDto(
        rs.getLong("id"), rs.getString("title"), rs.getString("summary"),
        rs.getTimestamp("published_at").toInstant(),
        PortalContentCategory.valueOf(rs.getString("category")),
        tag(PortalContentCategory.valueOf(rs.getString("category"))),
        rs.getString("source_name"), rs.getString("source_url")), contentType.name(), category.name(), limit);
  }

  public int countContent() {
    Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM portal_content", Integer.class);
    return value == null ? 0 : value;
  }

  public PageResponse<PortalContentAdminDto> findForReview(
      PortalContentStatus status, PortalContentCategory category, String keyword, long pageNo, long pageSize) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    if (status != null) { where.append(" AND status = ?"); args.add(status.name()); }
    if (category != null) { where.append(" AND category = ?"); args.add(category.name()); }
    if (keyword != null && !keyword.isBlank()) {
      String pattern = "%" + keyword.trim() + "%";
      where.append(" AND (title LIKE ? OR original_title LIKE ? OR source_name LIKE ? OR source_url LIKE ?)");
      args.add(pattern); args.add(pattern); args.add(pattern); args.add(pattern);
    }
    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM portal_content" + where, Long.class, args.toArray());
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(pageSize);
    pageArgs.add((pageNo - 1) * pageSize);
    List<PortalContentAdminDto> items = jdbc.query("""
        SELECT id, source_name, source_url, title, summary, original_title, original_summary,
               published_at, source_published_at, category, content_type, review_category,
               review_content_type, status, is_pinned, pin_order, change_summary, last_crawled_at, updated_at
        FROM portal_content%s
        ORDER BY is_pinned DESC, pin_order ASC, CASE WHEN status = 'PENDING_REVIEW' THEN 0 ELSE 1 END, updated_at DESC, id DESC
        LIMIT ? OFFSET ?
        """.formatted(where), (rs, ignored) -> adminRow(rs), pageArgs.toArray());
    long total = count == null ? 0 : count;
    return new PageResponse<>(items, pageNo, pageSize, total, pageNo * pageSize < total);
  }

  public PortalContentAdminDto findForReview(long id) {
    try {
      return jdbc.queryForObject("""
          SELECT id, source_name, source_url, title, summary, original_title, original_summary,
                 published_at, source_published_at, category, content_type, review_category,
                 review_content_type, status, is_pinned, pin_order, change_summary, last_crawled_at, updated_at
          FROM portal_content WHERE id = ?
          """, (rs, ignored) -> adminRow(rs), id);
    } catch (EmptyResultDataAccessException exception) {
      throw notFound();
    }
  }

  public PortalContentAdminDto saveReview(long id, PortalContentReviewRequest request, Instant now) {
    int updated = jdbc.update("""
        UPDATE portal_content
        SET title = ?, summary = ?, category = ?, content_type = ?, updated_at = ?
        WHERE id = ?
        """, request.title().trim(), request.summary().trim(), request.category().name(),
        request.contentType().name(), Timestamp.from(now), id);
    if (updated == 0) throw notFound();
    return findForReview(id);
  }

  public PortalContentAdminDto changeStatus(long id, PortalContentStatus status, Instant now) {
    int updated = jdbc.update("""
        UPDATE portal_content
        SET category = CASE WHEN ? = 'PUBLISHED' THEN review_category ELSE category END,
            content_type = CASE WHEN ? = 'PUBLISHED' THEN review_content_type ELSE content_type END,
            status = ?, updated_at = ?
        WHERE id = ?
        """, status.name(), status.name(), status.name(), Timestamp.from(now), id);
    if (updated == 0) throw notFound();
    return findForReview(id);
  }

  public PortalContentAdminDto setPinned(long id, boolean pinned, Instant now) {
    Integer nextOrder = jdbc.queryForObject("SELECT COALESCE(MAX(pin_order), 0) + 1 FROM portal_content WHERE is_pinned = 1", Integer.class);
    int updated = jdbc.update("UPDATE portal_content SET is_pinned = ?, pin_order = ?, updated_at = ? WHERE id = ? AND status = 'PUBLISHED'",
        pinned, pinned ? (nextOrder == null ? 1 : nextOrder) : 0, Timestamp.from(now), id);
    if (updated == 0) throw notFound();
    return findForReview(id);
  }

  public UpsertResult upsert(PortalCrawlCandidate candidate, PortalContentClassifier.Classification classification, Instant now) {
    String hash = sha256(candidate.title() + "\n" + candidate.summary() + "\n" + candidate.publishedAt());
    List<ExistingContent> existing = jdbc.query("SELECT id, content_hash, status FROM portal_content WHERE source_url = ?",
        (rs, ignored) -> new ExistingContent(rs.getLong("id"), rs.getString("content_hash"), rs.getString("status")), candidate.sourceUrl());
    if (existing.isEmpty()) {
      jdbc.update("""
          INSERT INTO portal_content (
            id, source_name, source_url, title, summary, published_at, category, content_type,
            original_title, original_summary, source_published_at, previous_content_hash,
            review_category, review_content_type, content_hash, status, last_crawled_at,
            change_summary, first_seen_at, updated_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, 'PENDING_REVIEW', ?, ?, ?, ?)
          """, nextId(), sourceName(candidate.sourceUrl()), candidate.sourceUrl(), candidate.title(), candidate.summary(),
          Timestamp.from(candidate.publishedAt()), classification.category().name(), classification.contentType().name(),
          candidate.title(), candidate.summary(), Timestamp.from(candidate.publishedAt()), classification.category().name(),
          classification.contentType().name(), hash, Timestamp.from(now), "首次抓取，等待人工审核", Timestamp.from(now), Timestamp.from(now));
      return UpsertResult.CREATED;
    }
    if (!existing.get(0).contentHash().equals(hash)) {
      jdbc.update("""
          UPDATE portal_content
          SET original_title = ?, original_summary = ?, source_published_at = ?, previous_content_hash = ?,
              review_category = ?, review_content_type = ?, content_hash = ?, status = 'PENDING_REVIEW',
              last_crawled_at = ?, change_summary = ?, updated_at = ?
          WHERE id = ?
          """, candidate.title(), candidate.summary(), Timestamp.from(candidate.publishedAt()), existing.get(0).contentHash(),
          classification.category().name(), classification.contentType().name(), hash, Timestamp.from(now),
          "内容哈希变化，等待人工审核", Timestamp.from(now), existing.get(0).id());
      return UpsertResult.UPDATED;
    }
    jdbc.update("UPDATE portal_content SET last_crawled_at = ?, updated_at = ? WHERE id = ?",
        Timestamp.from(now), Timestamp.from(now), existing.get(0).id());
    return UpsertResult.UNCHANGED;
  }

  public long startRun(Instant now) {
    long id = nextId();
    jdbc.update("""
        INSERT INTO portal_crawl_run (id, started_at, success, fetched_count, created_count, updated_count)
        VALUES (?, ?, FALSE, 0, 0, 0)
        """, id, Timestamp.from(now));
    return id;
  }

  public void completeRun(long id, boolean success, int fetched, int created, int updated, String errorSummary, Instant now) {
    jdbc.update("""
        UPDATE portal_crawl_run
        SET finished_at = ?, success = ?, fetched_count = ?, created_count = ?, updated_count = ?, error_summary = ?
        WHERE id = ?
        """, Timestamp.from(now), success, fetched, created, updated, errorSummary, id);
  }

  private static long nextId() {
    return ID_SEQUENCE.incrementAndGet();
  }

  private static PortalContentAdminDto adminRow(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new PortalContentAdminDto(
        rs.getLong("id"), rs.getString("source_name"), rs.getString("source_url"),
        rs.getString("title"), rs.getString("summary"), rs.getString("original_title"),
        rs.getString("original_summary"), instant(rs, "published_at"),
        instant(rs, "source_published_at"),
        category(rs.getString("category")), contentType(rs.getString("content_type")),
        category(rs.getString("review_category")), contentType(rs.getString("review_content_type")),
        status(rs.getString("status")), rs.getBoolean("is_pinned"), rs.getInt("pin_order"), rs.getString("change_summary"),
        instant(rs, "last_crawled_at"), instant(rs, "updated_at"));
  }

  private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static PortalContentCategory category(String value) {
    try { return value == null || value.isBlank() ? null : PortalContentCategory.valueOf(value); }
    catch (IllegalArgumentException exception) { return null; }
  }

  private static PortalContentType contentType(String value) {
    try { return value == null || value.isBlank() ? null : PortalContentType.valueOf(value); }
    catch (IllegalArgumentException exception) { return null; }
  }

  private static PortalContentStatus status(String value) {
    try { return value == null || value.isBlank() ? PortalContentStatus.PENDING_REVIEW : PortalContentStatus.valueOf(value); }
    catch (IllegalArgumentException exception) { return PortalContentStatus.PENDING_REVIEW; }
  }

  private static BusinessException notFound() {
    return new BusinessException("PORTAL_CONTENT_NOT_FOUND", "Portal content does not exist", HttpStatus.NOT_FOUND);
  }

  private static String sourceName(String sourceUrl) {
    String host = URI.create(sourceUrl).getHost();
    return "www.ruankao.org.cn".equalsIgnoreCase(host) ? "中国计算机技术职业资格网" : host;
  }

  private static String tag(PortalContentCategory category) {
    return switch (category) {
      case REGISTRATION -> "报名动态";
      case POLICY -> "政策资讯";
      case OFFICIAL_RESOURCE -> "官方资料";
    };
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private record ExistingContent(long id, String contentHash, String status) {}
  public enum UpsertResult { CREATED, UPDATED, UNCHANGED }
}
