package com.aiexam.app.learning;

import com.aiexam.common.api.PageResponse;
import com.aiexam.common.error.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LearningResourceRepository {
  private static final AtomicLong IDS = new AtomicLong(Instant.now().toEpochMilli() * 1_000L);
  private final JdbcTemplate jdbc;

  public LearningResourceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public PageResponse<LearningResourceDto> findPage(Long qualificationId, LearningResourceType type,
      LearningResourceStatus status, long pageNo, long pageSize) {
    StringBuilder where = new StringBuilder(" WHERE r.deleted = 0");
    List<Object> args = new ArrayList<>();
    if (qualificationId != null) { where.append(" AND r.qualification_id = ?"); args.add(qualificationId); }
    if (type != null) { where.append(" AND r.resource_type = ?"); args.add(type.name()); }
    if (status != null) { where.append(" AND r.status = ?"); args.add(status.name()); }
    Long total = jdbc.queryForObject("SELECT COUNT(*) FROM learning_resource r" + where, Long.class, args.toArray());
    long offset = (pageNo - 1) * pageSize;
    List<LearningResourceDto> items = jdbc.query("""
        SELECT r.id, r.qualification_id, r.resource_code, r.resource_type, r.title, r.summary,
               r.status, r.copyright_status, r.current_version_id, r.sort_order, r.created_at, r.updated_at
        FROM learning_resource r%s
        ORDER BY r.sort_order, r.updated_at DESC, r.id DESC LIMIT %d OFFSET %d
        """.formatted(where, pageSize, offset), (rs, ignored) -> resourceRow(rs), args.toArray());
    long count = total == null ? 0 : total;
    return new PageResponse<>(items, pageNo, pageSize, count, pageNo * pageSize < count);
  }

  public LearningResourceDto find(long id) {
    try {
      return jdbc.queryForObject("""
          SELECT id, qualification_id, resource_code, resource_type, title, summary,
                 status, copyright_status, current_version_id, sort_order, created_at, updated_at
          FROM learning_resource WHERE id = ? AND deleted = 0
          """, (rs, ignored) -> resourceRow(rs), id);
    } catch (EmptyResultDataAccessException exception) {
      throw notFound("LEARNING_RESOURCE_NOT_FOUND", "Learning resource does not exist");
    }
  }

  public LearningResourceDto insert(LearningResourceCreateRequest request, Instant now) {
    long id = nextId();
    try {
      jdbc.update("""
          INSERT INTO learning_resource
            (id, qualification_id, resource_code, resource_type, title, summary, status,
             copyright_status, sort_order, created_at, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?)
          """, id, request.qualificationId(), request.resourceCode().trim(), request.resourceType().name(),
          request.title().trim(), nullable(request.summary()), copyright(request.copyrightStatus()),
          request.sortOrder() == null ? 0 : request.sortOrder(), Timestamp.from(now), Timestamp.from(now));
    } catch (org.springframework.dao.DuplicateKeyException exception) {
      throw new BusinessException("LEARNING_RESOURCE_CODE_EXISTS", "Resource code already exists", HttpStatus.CONFLICT);
    }
    return find(id);
  }

  public LearningResourceDto update(long id, LearningResourceUpdateRequest request, Instant now) {
    int updated = jdbc.update("""
        UPDATE learning_resource SET title = ?, summary = ?, copyright_status = ?, sort_order = ?, updated_at = ?, row_version = row_version + 1
        WHERE id = ? AND deleted = 0
        """, request.title().trim(), nullable(request.summary()), copyright(request.copyrightStatus()),
        request.sortOrder() == null ? 0 : request.sortOrder(), Timestamp.from(now), id);
    if (updated == 0) throw notFound("LEARNING_RESOURCE_NOT_FOUND", "Learning resource does not exist");
    return find(id);
  }

  public LearningResourceDto changeStatus(long id, LearningResourceStatus status, Instant now) {
    int updated = jdbc.update("UPDATE learning_resource SET status = ?, updated_at = ?, row_version = row_version + 1 WHERE id = ? AND deleted = 0",
        status.name(), Timestamp.from(now), id);
    if (updated == 0) throw notFound("LEARNING_RESOURCE_NOT_FOUND", "Learning resource does not exist");
    return find(id);
  }

  public void delete(long id, Instant now) {
    int updated = jdbc.update("UPDATE learning_resource SET deleted = 1, updated_at = ?, row_version = row_version + 1 WHERE id = ? AND deleted = 0",
        Timestamp.from(now), id);
    if (updated == 0) throw notFound("LEARNING_RESOURCE_NOT_FOUND", "Learning resource does not exist");
  }

  public LearningResourceVersionDto addVersion(long resourceId, LearningResourceVersionRequest request, Instant now) {
    find(resourceId);
    Integer version = jdbc.queryForObject("SELECT COALESCE(MAX(version_no), 0) + 1 FROM learning_resource_version WHERE resource_id = ?", Integer.class, resourceId);
    long id = nextId();
    try {
      jdbc.update("""
          INSERT INTO learning_resource_version
            (id, resource_id, version_no, version_label, source_name, source_ref, file_url, file_name,
             mime_type, file_size, file_sha256, page_count, cover_url, usage_restriction)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """, id, resourceId, version, nullable(request.versionLabel()), nullable(request.sourceName()), nullable(request.sourceRef()),
          request.fileUrl().trim(), request.fileName().trim(), request.mimeType().trim(), request.fileSize(), request.fileSha256().trim(),
          request.pageCount(), nullable(request.coverUrl()), restriction(request.usageRestriction()));
    } catch (org.springframework.dao.DuplicateKeyException exception) {
      throw new BusinessException("LEARNING_RESOURCE_FILE_EXISTS", "The same resource file already exists", HttpStatus.CONFLICT);
    }
    return findVersion(id);
  }

  public LearningResourceVersionDto findVersion(long id) {
    try {
      return jdbc.queryForObject("""
          SELECT id, resource_id, version_no, version_label, source_name, source_ref, file_url, file_name,
                 mime_type, file_size, file_sha256, page_count, cover_url, extracted_text_status,
                 status, usage_restriction, created_at
          FROM learning_resource_version WHERE id = ? AND deleted = 0
          """, (rs, ignored) -> versionRow(rs), id);
    } catch (EmptyResultDataAccessException exception) {
      throw notFound("LEARNING_RESOURCE_VERSION_NOT_FOUND", "Learning resource version does not exist");
    }
  }

  public LearningResourceVersionDto updatePreview(long versionId, Integer pageCount, String coverUrl, Instant now) {
    int updated = jdbc.update("UPDATE learning_resource_version SET page_count = ?, cover_url = ?, updated_at = ? WHERE id = ? AND deleted = 0",
        pageCount, nullable(coverUrl), Timestamp.from(now), versionId);
    if (updated == 0) throw notFound("LEARNING_RESOURCE_VERSION_NOT_FOUND", "Learning resource version does not exist");
    return findVersion(versionId);
  }

  public List<LearningResourceVersionDto> versions(long resourceId) {
    find(resourceId);
    return jdbc.query("""
        SELECT id, resource_id, version_no, version_label, source_name, source_ref, file_url, file_name,
               mime_type, file_size, file_sha256, page_count, cover_url, extracted_text_status,
               status, usage_restriction, created_at
        FROM learning_resource_version WHERE resource_id = ? AND deleted = 0 ORDER BY version_no DESC
        """, (rs, ignored) -> versionRow(rs), resourceId);
  }

  public void publishVersion(long resourceId, long versionId, Instant now) {
    find(resourceId);
    int updated = jdbc.update("UPDATE learning_resource_version SET status = 'PUBLISHED', updated_at = ? WHERE id = ? AND resource_id = ? AND deleted = 0",
        Timestamp.from(now), versionId, resourceId);
    if (updated == 0) throw notFound("LEARNING_RESOURCE_VERSION_NOT_FOUND", "Learning resource version does not exist");
    jdbc.update("UPDATE learning_resource SET current_version_id = ?, status = 'PUBLISHED', updated_at = ?, row_version = row_version + 1 WHERE id = ? AND deleted = 0",
        versionId, Timestamp.from(now), resourceId);
  }

  public List<LearningChapterDto> chapters(long versionId) {
    findVersion(versionId);
    return jdbc.query("""
        SELECT id, version_id, parent_id, chapter_code, chapter_title, level_no, page_no, end_page_no, sort_order
        FROM learning_resource_chapter WHERE version_id = ? AND deleted = 0 ORDER BY sort_order, id
        """, (rs, ignored) -> new LearningChapterDto(rs.getLong("id"), rs.getLong("version_id"), nullableLong(rs, "parent_id"),
        rs.getString("chapter_code"), rs.getString("chapter_title"), rs.getInt("level_no"), rs.getInt("page_no"),
        nullableInt(rs, "end_page_no"), rs.getInt("sort_order")), versionId);
  }

  public List<LearningChapterDto> replaceChapters(long versionId, List<LearningChapterWriteRequest> chapters) {
    findVersion(versionId);
    jdbc.update("DELETE FROM learning_resource_chapter WHERE version_id = ?", versionId);
    Map<String, Long> ids = new java.util.HashMap<>();
    for (LearningChapterWriteRequest chapter : chapters) {
      Long parentId = chapter.parentChapterCode() == null ? null : ids.get(chapter.parentChapterCode());
      long id = nextId();
      jdbc.update("""
          INSERT INTO learning_resource_chapter
            (id, version_id, parent_id, chapter_code, chapter_title, level_no, page_no, end_page_no, sort_order)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
          """, id, versionId, parentId, chapter.chapterCode().trim(), chapter.chapterTitle().trim(), chapter.levelNo(), chapter.pageNo(),
          chapter.endPageNo(), chapter.sortOrder());
      ids.put(chapter.chapterCode(), id);
    }
    return chapters(versionId);
  }

  public List<LearningKnowledgePointDto> knowledgePoints(long qualificationId) {
    return jdbc.query("""
        SELECT id, qualification_id, parent_id, point_code, point_name, description, level_no, sort_order, status
        FROM learning_knowledge_point WHERE qualification_id = ? AND deleted = 0 ORDER BY sort_order, id
        """, (rs, ignored) -> new LearningKnowledgePointDto(rs.getLong("id"), rs.getLong("qualification_id"), nullableLong(rs, "parent_id"),
        rs.getString("point_code"), rs.getString("point_name"), rs.getString("description"), rs.getInt("level_no"), rs.getInt("sort_order"), rs.getString("status")), qualificationId);
  }

  public List<LearningKnowledgePointDto> chapterKnowledgePoints(long chapterId) {
    return jdbc.query("""
        SELECT p.id, p.qualification_id, p.parent_id, p.point_code, p.point_name, p.description, p.level_no, p.sort_order, p.status
        FROM learning_knowledge_point p
        JOIN learning_chapter_knowledge_point cp ON cp.knowledge_point_id = p.id
        WHERE cp.chapter_id = ? AND p.deleted = 0 ORDER BY p.sort_order, p.id
        """, (rs, ignored) -> new LearningKnowledgePointDto(rs.getLong("id"), rs.getLong("qualification_id"), nullableLong(rs, "parent_id"),
        rs.getString("point_code"), rs.getString("point_name"), rs.getString("description"), rs.getInt("level_no"), rs.getInt("sort_order"), rs.getString("status")), chapterId);
  }

  public List<LearningKnowledgePointDto> replaceChapterKnowledgePoints(long chapterId, List<Long> pointIds) {
    jdbc.queryForObject("SELECT id FROM learning_resource_chapter WHERE id = ? AND deleted = 0", Long.class, chapterId);
    jdbc.update("DELETE FROM learning_chapter_knowledge_point WHERE chapter_id = ?", chapterId);
    for (Long pointId : pointIds) {
      jdbc.update("INSERT INTO learning_chapter_knowledge_point (chapter_id, knowledge_point_id, relation_type) VALUES (?, ?, 'COVERS')",
          chapterId, pointId);
    }
    return chapterKnowledgePoints(chapterId);
  }

  public LearningKnowledgePointDto createKnowledgePoint(LearningKnowledgePointRequest request, Instant now) {
    Long parentId = request.parentPointCode() == null ? null : jdbc.queryForObject(
        "SELECT id FROM learning_knowledge_point WHERE qualification_id = ? AND point_code = ? AND deleted = 0",
        Long.class, request.qualificationId(), request.parentPointCode());
    long id = nextId();
    jdbc.update("""
        INSERT INTO learning_knowledge_point
          (id, qualification_id, parent_id, point_code, point_name, description, level_no, sort_order, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, id, request.qualificationId(), parentId, request.pointCode().trim(), request.pointName().trim(), nullable(request.description()),
        request.levelNo(), request.sortOrder(), Timestamp.from(now), Timestamp.from(now));
    return jdbc.queryForObject("""
        SELECT id, qualification_id, parent_id, point_code, point_name, description, level_no, sort_order, status
        FROM learning_knowledge_point WHERE id = ?
        """, (rs, ignored) -> new LearningKnowledgePointDto(rs.getLong("id"), rs.getLong("qualification_id"), nullableLong(rs, "parent_id"),
        rs.getString("point_code"), rs.getString("point_name"), rs.getString("description"), rs.getInt("level_no"), rs.getInt("sort_order"), rs.getString("status")), id);
  }

  private LearningResourceDto resourceRow(ResultSet rs) throws SQLException {
    return new LearningResourceDto(rs.getLong("id"), rs.getLong("qualification_id"), rs.getString("resource_code"),
        resourceType(rs.getString("resource_type")), rs.getString("title"), rs.getString("summary"),
        resourceStatus(rs.getString("status")), rs.getString("copyright_status"), nullableLong(rs, "current_version_id"),
        rs.getInt("sort_order"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
  }

  private static LearningResourceType resourceType(String value) {
    try { return LearningResourceType.valueOf(value); }
    catch (IllegalArgumentException | NullPointerException exception) { return LearningResourceType.COURSE_HANDOUT; }
  }

  private static LearningResourceStatus resourceStatus(String value) {
    try { return LearningResourceStatus.valueOf(value); }
    catch (IllegalArgumentException | NullPointerException exception) { return LearningResourceStatus.DRAFT; }
  }

  private LearningResourceVersionDto versionRow(ResultSet rs) throws SQLException {
    return new LearningResourceVersionDto(rs.getLong("id"), rs.getLong("resource_id"), rs.getInt("version_no"), rs.getString("version_label"),
        rs.getString("source_name"), rs.getString("source_ref"), rs.getString("file_url"), rs.getString("file_name"), rs.getString("mime_type"),
        rs.getLong("file_size"), rs.getString("file_sha256"), nullableInt(rs, "page_count"), rs.getString("cover_url"),
        rs.getString("extracted_text_status"), rs.getString("status"), rs.getString("usage_restriction"), rs.getTimestamp("created_at").toInstant());
  }

  private static Long nullableLong(ResultSet rs, String column) throws SQLException { long value = rs.getLong(column); return rs.wasNull() ? null : value; }
  private static Integer nullableInt(ResultSet rs, String column) throws SQLException { int value = rs.getInt(column); return rs.wasNull() ? null : value; }
  private static String nullable(String value) { return value == null || value.isBlank() ? null : value.trim(); }
  private static String copyright(String value) { return value == null || value.isBlank() ? "UNVERIFIED" : value.trim(); }
  private static String restriction(String value) { return value == null || value.isBlank() ? "INTERNAL_ONLY" : value.trim(); }
  private static long nextId() { return IDS.incrementAndGet(); }
  private static BusinessException notFound(String code, String message) { return new BusinessException(code, message, HttpStatus.NOT_FOUND); }
}
