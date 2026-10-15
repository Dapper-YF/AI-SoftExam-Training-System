package com.aiexam.essay.infra;

import com.aiexam.common.api.PageResponse;
import com.aiexam.essay.dto.EssayBackgroundDto;
import com.aiexam.essay.dto.EssayDraftDto;
import com.aiexam.essay.dto.EssayMaterialDto;
import com.aiexam.essay.dto.request.EssayBackgroundCreateRequest;
import com.aiexam.essay.dto.request.EssayBackgroundUpdateRequest;
import com.aiexam.essay.dto.request.EssayDraftCreateRequest;
import com.aiexam.essay.dto.request.EssayDraftUpdateRequest;
import com.aiexam.essay.dto.request.EssayMaterialCreateRequest;
import com.aiexam.essay.dto.request.EssayMaterialUpdateRequest;
import com.aiexam.essay.repository.EssayTrainingRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEssayTrainingRepository implements EssayTrainingRepository {
  private final JdbcTemplate jdbcTemplate;

  public JdbcEssayTrainingRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public EssayBackgroundDto insertBackground(Long id, Long userId, EssayBackgroundCreateRequest request, String sourceType) {
    Timestamp now = now();
    jdbcTemplate.update("""
            INSERT INTO essay_project_background (
              id, user_id, qualification_id, title, industry, project_scale, project_cycle,
              tech_stack, team_size, background_content, source_type, status,
              created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            """,
        id,
        userId,
        request.qualificationId(),
        request.title(),
        request.industry(),
        request.projectScale(),
        request.projectCycle(),
        request.techStack(),
        request.teamSize(),
        request.backgroundContent(),
        upper(sourceType),
        statusOr(request.status(), "ACTIVE"),
        now,
        now,
        userId,
        userId);
    return findBackground(id, userId).orElseThrow();
  }

  @Override
  public Optional<EssayBackgroundDto> findBackground(Long id, Long userId) {
    return optional("""
        SELECT id, qualification_id, title, industry, project_scale, project_cycle, tech_stack, team_size,
               background_content, source_type, status, created_at, updated_at
        FROM essay_project_background
        WHERE id = ? AND user_id = ? AND deleted = 0
        """, this::background, id, userId);
  }

  @Override
  public PageResponse<EssayBackgroundDto> findBackgrounds(Long userId, long pageNo, long pageSize) {
    long total = count("SELECT COUNT(*) FROM essay_project_background WHERE user_id = ? AND deleted = 0", userId);
    List<EssayBackgroundDto> items = jdbcTemplate.query("""
            SELECT id, qualification_id, title, industry, project_scale, project_cycle, tech_stack, team_size,
                   background_content, source_type, status, created_at, updated_at
            FROM essay_project_background
            WHERE user_id = ? AND deleted = 0
            ORDER BY updated_at DESC, id DESC
            LIMIT ? OFFSET ?
            """,
        this::background,
        userId,
        pageSize,
        offset(pageNo, pageSize));
    return page(items, pageNo, pageSize, total);
  }

  @Override
  public EssayBackgroundDto updateBackground(Long id, Long userId, EssayBackgroundUpdateRequest request) {
    jdbcTemplate.update("""
            UPDATE essay_project_background
            SET title = COALESCE(?, title),
                industry = COALESCE(?, industry),
                project_scale = COALESCE(?, project_scale),
                project_cycle = COALESCE(?, project_cycle),
                tech_stack = COALESCE(?, tech_stack),
                team_size = COALESCE(?, team_size),
                background_content = COALESCE(?, background_content),
                status = COALESCE(?, status),
                updated_at = ?,
                updated_by = ?
            WHERE id = ? AND user_id = ? AND deleted = 0
            """,
        blankToNull(request.title()),
        blankToNull(request.industry()),
        blankToNull(request.projectScale()),
        blankToNull(request.projectCycle()),
        blankToNull(request.techStack()),
        blankToNull(request.teamSize()),
        blankToNull(request.backgroundContent()),
        upper(blankToNull(request.status())),
        now(),
        userId,
        id,
        userId);
    return findBackground(id, userId).orElseThrow();
  }

  @Override
  public EssayMaterialDto insertMaterial(Long id, Long userId, EssayMaterialCreateRequest request, String sourceType) {
    Timestamp now = now();
    jdbcTemplate.update("""
            INSERT INTO essay_material (
              id, user_id, qualification_id, background_id, category_id, material_type, title, content,
              source_type, status, tags, created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            """,
        id,
        userId,
        request.qualificationId(),
        request.backgroundId(),
        request.categoryId(),
        upper(request.materialType()),
        request.title(),
        request.content(),
        upper(sourceType),
        statusOr(request.status(), "DRAFT"),
        request.tags(),
        now,
        now,
        userId,
        userId);
    return findMaterial(id, userId).orElseThrow();
  }

  @Override
  public Optional<EssayMaterialDto> findMaterial(Long id, Long userId) {
    return optional("""
        SELECT id, qualification_id, background_id, category_id, material_type, title, content,
               source_type, status, tags, created_at, updated_at
        FROM essay_material
        WHERE id = ? AND deleted = 0 AND (user_id = ? OR user_id IS NULL)
        """, this::material, id, userId);
  }

  @Override
  public PageResponse<EssayMaterialDto> findMaterials(Long userId, long pageNo, long pageSize) {
    long total = count("SELECT COUNT(*) FROM essay_material WHERE deleted = 0 AND (user_id = ? OR user_id IS NULL)", userId);
    List<EssayMaterialDto> items = jdbcTemplate.query("""
            SELECT id, qualification_id, background_id, category_id, material_type, title, content,
                   source_type, status, tags, created_at, updated_at
            FROM essay_material
            WHERE deleted = 0 AND (user_id = ? OR user_id IS NULL)
            ORDER BY updated_at DESC, id DESC
            LIMIT ? OFFSET ?
            """,
        this::material,
        userId,
        pageSize,
        offset(pageNo, pageSize));
    return page(items, pageNo, pageSize, total);
  }

  @Override
  public EssayMaterialDto updateMaterial(Long id, Long userId, EssayMaterialUpdateRequest request) {
    jdbcTemplate.update("""
            UPDATE essay_material
            SET background_id = COALESCE(?, background_id),
                category_id = COALESCE(?, category_id),
                material_type = COALESCE(?, material_type),
                title = COALESCE(?, title),
                content = COALESCE(?, content),
                status = COALESCE(?, status),
                tags = COALESCE(?, tags),
                updated_at = ?,
                updated_by = ?
            WHERE id = ? AND user_id = ? AND deleted = 0
            """,
        request.backgroundId(),
        request.categoryId(),
        upper(blankToNull(request.materialType())),
        blankToNull(request.title()),
        blankToNull(request.content()),
        upper(blankToNull(request.status())),
        blankToNull(request.tags()),
        now(),
        userId,
        id,
        userId);
    return findMaterial(id, userId).orElseThrow();
  }

  @Override
  public EssayDraftDto insertDraft(Long id, Long userId, EssayDraftCreateRequest request, int wordCount) {
    Timestamp now = now();
    jdbcTemplate.update("""
            INSERT INTO essay_draft (
              id, user_id, qualification_id, background_id, title, outline, content, word_count,
              writing_seconds, status, created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            """,
        id,
        userId,
        request.qualificationId(),
        request.backgroundId(),
        request.title(),
        request.outline(),
        request.content(),
        wordCount,
        request.writingSeconds(),
        statusOr(request.status(), "DRAFTING"),
        now,
        now,
        userId,
        userId);
    return findDraft(id, userId).orElseThrow();
  }

  @Override
  public Optional<EssayDraftDto> findDraft(Long id, Long userId) {
    Optional<DraftRow> row = optional("""
        SELECT id, qualification_id, background_id, title, outline, content, word_count, writing_seconds,
               status, created_at, updated_at
        FROM essay_draft
        WHERE id = ? AND user_id = ? AND deleted = 0
        """, this::draftRow, id, userId);
    return row.map(value -> draft(value, findDraftMaterials(value.id(), userId)));
  }

  @Override
  public PageResponse<EssayDraftDto> findDrafts(Long userId, long pageNo, long pageSize) {
    long total = count("SELECT COUNT(*) FROM essay_draft WHERE user_id = ? AND deleted = 0", userId);
    List<EssayDraftDto> items = jdbcTemplate.query("""
            SELECT id, qualification_id, background_id, title, outline, content, word_count, writing_seconds,
                   status, created_at, updated_at
            FROM essay_draft
            WHERE user_id = ? AND deleted = 0
            ORDER BY updated_at DESC, id DESC
            LIMIT ? OFFSET ?
            """,
        (rs, rowNum) -> draft(draftRow(rs, rowNum), List.of()),
        userId,
        pageSize,
        offset(pageNo, pageSize));
    return page(items, pageNo, pageSize, total);
  }

  @Override
  public EssayDraftDto updateDraft(Long id, Long userId, EssayDraftUpdateRequest request, int wordCount) {
    jdbcTemplate.update("""
            UPDATE essay_draft
            SET background_id = COALESCE(?, background_id),
                title = COALESCE(?, title),
                outline = COALESCE(?, outline),
                content = COALESCE(?, content),
                word_count = ?,
                writing_seconds = COALESCE(?, writing_seconds),
                status = COALESCE(?, status),
                updated_at = ?,
                updated_by = ?
            WHERE id = ? AND user_id = ? AND deleted = 0
            """,
        request.backgroundId(),
        blankToNull(request.title()),
        request.outline(),
        request.content(),
        wordCount,
        request.writingSeconds(),
        upper(blankToNull(request.status())),
        now(),
        userId,
        id,
        userId);
    return findDraft(id, userId).orElseThrow();
  }

  @Override
  public int nextRevisionNo(Long draftId) {
    Integer max = jdbcTemplate.queryForObject(
        "SELECT COALESCE(MAX(revision_no), 0) FROM essay_draft_revision WHERE draft_id = ?",
        Integer.class,
        draftId);
    return max == null ? 1 : max + 1;
  }

  @Override
  public void insertDraftRevision(
      Long id,
      Long draftId,
      int revisionNo,
      String contentSnapshot,
      String summary,
      Integer wordCount,
      Long userId
  ) {
    jdbcTemplate.update("""
            INSERT INTO essay_draft_revision (
              id, draft_id, revision_no, content_snapshot, summary, word_count, created_at, created_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
        id,
        draftId,
        revisionNo,
        contentSnapshot,
        summary,
        wordCount,
        now(),
        userId);
  }

  @Override
  public boolean allMaterialsAccessible(Long userId, List<Long> materialIds) {
    if (materialIds.isEmpty()) {
      return true;
    }
    List<Object> args = new ArrayList<>(materialIds);
    args.add(userId);
    Long count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM essay_material
            WHERE id IN (%s) AND deleted = 0 AND (user_id = ? OR user_id IS NULL)
            """.formatted(placeholders(materialIds.size())),
        Long.class,
        args.toArray());
    return count != null && count == materialIds.stream().distinct().count();
  }

  @Override
  public void replaceDraftMaterials(Long draftId, List<Long> materialIds) {
    jdbcTemplate.update("DELETE FROM essay_draft_material WHERE draft_id = ?", draftId);
    for (Long materialId : materialIds.stream().distinct().toList()) {
      jdbcTemplate.update("""
          INSERT INTO essay_draft_material (draft_id, material_id, created_at)
          VALUES (?, ?, ?)
          """, draftId, materialId, now());
    }
  }

  private List<EssayMaterialDto> findDraftMaterials(Long draftId, Long userId) {
    return jdbcTemplate.query("""
            SELECT m.id, m.qualification_id, m.background_id, m.category_id, m.material_type, m.title,
                   m.content, m.source_type, m.status, m.tags, m.created_at, m.updated_at
            FROM essay_draft_material dm
            JOIN essay_material m ON m.id = dm.material_id
            WHERE dm.draft_id = ? AND m.deleted = 0 AND (m.user_id = ? OR m.user_id IS NULL)
            ORDER BY dm.created_at ASC, m.id ASC
            """,
        this::material,
        draftId,
        userId);
  }

  private EssayBackgroundDto background(ResultSet rs, int rowNum) throws SQLException {
    return new EssayBackgroundDto(
        rs.getLong("id"),
        rs.getLong("qualification_id"),
        rs.getString("title"),
        rs.getString("industry"),
        rs.getString("project_scale"),
        rs.getString("project_cycle"),
        rs.getString("tech_stack"),
        rs.getString("team_size"),
        rs.getString("background_content"),
        rs.getString("source_type"),
        rs.getString("status"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private EssayMaterialDto material(ResultSet rs, int rowNum) throws SQLException {
    return new EssayMaterialDto(
        rs.getLong("id"),
        rs.getLong("qualification_id"),
        nullableLong(rs, "background_id"),
        nullableLong(rs, "category_id"),
        rs.getString("material_type"),
        rs.getString("title"),
        rs.getString("content"),
        rs.getString("source_type"),
        rs.getString("status"),
        rs.getString("tags"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private DraftRow draftRow(ResultSet rs, int rowNum) throws SQLException {
    return new DraftRow(
        rs.getLong("id"),
        rs.getLong("qualification_id"),
        nullableLong(rs, "background_id"),
        rs.getString("title"),
        rs.getString("outline"),
        rs.getString("content"),
        rs.getInt("word_count"),
        nullableInt(rs, "writing_seconds"),
        rs.getString("status"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private EssayDraftDto draft(DraftRow row, List<EssayMaterialDto> materials) {
    return new EssayDraftDto(
        row.id(),
        row.qualificationId(),
        row.backgroundId(),
        row.title(),
        row.outline(),
        row.content(),
        row.wordCount(),
        row.writingSeconds(),
        row.status(),
        materials,
        row.createdAt(),
        row.updatedAt());
  }

  private <T> PageResponse<T> page(List<T> items, long pageNo, long pageSize, long total) {
    return new PageResponse<>(items, pageNo, pageSize, total, offset(pageNo, pageSize) + items.size() < total);
  }

  private long count(String sql, Object... args) {
    Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
    return count == null ? 0 : count;
  }

  private <T> Optional<T> optional(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject(sql, mapper, args));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  private Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private Integer nullableInt(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }

  private Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private long offset(long pageNo, long pageSize) {
    return (pageNo - 1) * pageSize;
  }

  private String placeholders(int size) {
    return String.join(",", java.util.Collections.nCopies(size, "?"));
  }

  private String statusOr(String value, String defaultValue) {
    return value == null || value.isBlank() ? defaultValue : upper(value);
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private String upper(String value) {
    return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
  }

  private Timestamp now() {
    return Timestamp.from(Instant.now());
  }

  private record DraftRow(
      Long id,
      Long qualificationId,
      Long backgroundId,
      String title,
      String outline,
      String content,
      int wordCount,
      Integer writingSeconds,
      String status,
      Instant createdAt,
      Instant updatedAt
  ) {
  }
}
