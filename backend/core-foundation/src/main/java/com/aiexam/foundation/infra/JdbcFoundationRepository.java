package com.aiexam.foundation.infra;

import com.aiexam.common.api.PageResponse;
import com.aiexam.foundation.dto.ChapterNodeDto;
import com.aiexam.foundation.dto.ExamRuleDto;
import com.aiexam.foundation.dto.FoundationPageQuery;
import com.aiexam.foundation.dto.KnowledgeRelationDto;
import com.aiexam.foundation.dto.KnowledgeTagDto;
import com.aiexam.foundation.dto.QualificationDto;
import com.aiexam.foundation.dto.SubjectDto;
import com.aiexam.foundation.dto.SyllabusNodeDto;
import com.aiexam.foundation.dto.request.ChapterNodeCreateRequest;
import com.aiexam.foundation.dto.request.ExamRuleCreateRequest;
import com.aiexam.foundation.dto.request.KnowledgeRelationCreateRequest;
import com.aiexam.foundation.dto.request.KnowledgeTagCreateRequest;
import com.aiexam.foundation.dto.request.QualificationCreateRequest;
import com.aiexam.foundation.dto.request.QualificationUpdateRequest;
import com.aiexam.foundation.dto.request.SubjectCreateRequest;
import com.aiexam.foundation.dto.request.SyllabusNodeCreateRequest;
import com.aiexam.foundation.repository.FoundationRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFoundationRepository implements FoundationRepository {
  private final JdbcTemplate jdbcTemplate;

  public JdbcFoundationRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public PageResponse<QualificationDto> findEnabledQualifications(FoundationPageQuery pageQuery) {
    long total = count("SELECT COUNT(*) FROM exam_qualification WHERE deleted = 0 AND status = 'ENABLED'");
    List<QualificationDto> items = jdbcTemplate.query("""
            SELECT id, qualification_code, qualification_name, level_code, status, sort_order
            FROM exam_qualification
            WHERE deleted = 0 AND status = 'ENABLED'
            ORDER BY sort_order ASC, id ASC
            LIMIT ? OFFSET ?
            """,
        this::qualification,
        pageQuery.pageSize(),
        pageQuery.offset());
    return page(items, pageQuery, total);
  }

  @Override
  public Optional<QualificationDto> findQualificationById(Long id) {
    return optional("""
        SELECT id, qualification_code, qualification_name, level_code, status, sort_order
        FROM exam_qualification
        WHERE id = ? AND deleted = 0
        """, this::qualification, id);
  }

  @Override
  public Optional<QualificationDto> findEnabledQualificationById(Long id) {
    return optional("""
        SELECT id, qualification_code, qualification_name, level_code, status, sort_order
        FROM exam_qualification
        WHERE id = ? AND deleted = 0 AND status = 'ENABLED'
        """, this::qualification, id);
  }

  @Override
  public QualificationDto createQualification(Long id, QualificationCreateRequest request, String status, int sortOrder) {
    Timestamp now = now();
    jdbcTemplate.update("""
            INSERT INTO exam_qualification (
              id, qualification_code, domain_code, qualification_name, official_name,
              level_code, source_url, verified_at, verification_status, catalog_version,
              catalog_status, status, sort_order,
              created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 0)
            """,
        id,
        request.qualificationCode(),
        request.domainCode(),
        request.qualificationName(),
        request.officialName(),
        request.levelCode(),
        request.sourceUrl(),
        timestamp(request.verifiedAt()),
        request.verificationStatus(),
        request.catalogVersion(),
        request.catalogStatus(),
        status,
        sortOrder,
        now,
        now);
    return findQualificationById(id).orElseThrow();
  }

  @Override
  public QualificationDto updateQualification(Long id, QualificationUpdateRequest request, int sortOrder) {
    jdbcTemplate.update("""
            UPDATE exam_qualification
            SET qualification_name = ?, level_code = ?, status = ?, sort_order = ?, updated_at = ?
            WHERE id = ? AND deleted = 0
            """,
        request.qualificationName(),
        request.levelCode(),
        request.status(),
        sortOrder,
        now(),
        id);
    return findQualificationById(id).orElseThrow();
  }

  @Override
  public PageResponse<SubjectDto> findEnabledSubjects(Long qualificationId, FoundationPageQuery pageQuery) {
    long total = count(
        "SELECT COUNT(*) FROM exam_subject WHERE deleted = 0 AND status = 'ENABLED' AND qualification_id = ?",
        qualificationId);
    List<SubjectDto> items = jdbcTemplate.query("""
            SELECT id, qualification_id, subject_code, subject_name, subject_type, sort_order, status
            FROM exam_subject
            WHERE deleted = 0 AND status = 'ENABLED' AND qualification_id = ?
            ORDER BY sort_order ASC, id ASC
            LIMIT ? OFFSET ?
            """,
        this::subject,
        qualificationId,
        pageQuery.pageSize(),
        pageQuery.offset());
    return page(items, pageQuery, total);
  }

  @Override
  public Optional<SubjectDto> findSubjectById(Long id) {
    return optional("""
        SELECT id, qualification_id, subject_code, subject_name, subject_type, sort_order, status
        FROM exam_subject
        WHERE id = ? AND deleted = 0
        """, this::subject, id);
  }

  @Override
  public Optional<SubjectDto> findEnabledSubjectById(Long id) {
    return optional("""
        SELECT id, qualification_id, subject_code, subject_name, subject_type, sort_order, status
        FROM exam_subject
        WHERE id = ? AND deleted = 0 AND status = 'ENABLED'
        """, this::subject, id);
  }

  @Override
  public SubjectDto createSubject(Long id, SubjectCreateRequest request, String status, int sortOrder) {
    Timestamp now = now();
    jdbcTemplate.update("""
            INSERT INTO exam_subject (
              id, qualification_id, subject_code, subject_name, official_name, subject_type,
              sort_order, status,
              created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 0)
            """,
        id,
        request.qualificationId(),
        request.subjectCode(),
        request.subjectName(),
        request.officialName(),
        request.subjectType(),
        sortOrder,
        status,
        now,
        now);
    return findSubjectById(id).orElseThrow();
  }

  @Override
  public List<SyllabusNodeDto> findEnabledSyllabusNodes(Long subjectId) {
    return jdbcTemplate.query("""
            SELECT id, qualification_id, subject_id, parent_id, syllabus_version, node_code,
                   node_name, level_no, sort_order, status
            FROM kp_syllabus_node
            WHERE deleted = 0 AND status = 'ENABLED' AND subject_id = ?
            ORDER BY level_no ASC, sort_order ASC, id ASC
            """,
        this::syllabusNode,
        subjectId);
  }

  @Override
  public PageResponse<SyllabusNodeDto> findEnabledRootSyllabusNodes(Long subjectId, FoundationPageQuery pageQuery) {
    long total = count("""
        SELECT COUNT(*)
        FROM kp_syllabus_node
        WHERE deleted = 0 AND status = 'ENABLED' AND subject_id = ? AND parent_id IS NULL
        """, subjectId);
    List<SyllabusNodeDto> items = jdbcTemplate.query("""
            SELECT id, qualification_id, subject_id, parent_id, syllabus_version, node_code,
                   node_name, level_no, sort_order, status
            FROM kp_syllabus_node
            WHERE deleted = 0 AND status = 'ENABLED' AND subject_id = ? AND parent_id IS NULL
            ORDER BY sort_order ASC, id ASC
            LIMIT ? OFFSET ?
            """,
        this::syllabusNode,
        subjectId,
        pageQuery.pageSize(),
        pageQuery.offset());
    return page(items, pageQuery, total);
  }

  @Override
  public List<SyllabusNodeDto> findEnabledSyllabusChildren(Long subjectId, List<Long> parentIds) {
    if (parentIds.isEmpty()) {
      return List.of();
    }
    List<Object> args = new ArrayList<>();
    args.add(subjectId);
    args.addAll(parentIds);
    return jdbcTemplate.query("""
            SELECT id, qualification_id, subject_id, parent_id, syllabus_version, node_code,
                   node_name, level_no, sort_order, status
            FROM kp_syllabus_node
            WHERE deleted = 0 AND status = 'ENABLED' AND subject_id = ? AND parent_id IN (%s)
            ORDER BY level_no ASC, sort_order ASC, id ASC
            """.formatted(placeholders(parentIds.size())),
        this::syllabusNode,
        args.toArray());
  }

  @Override
  public Optional<SyllabusNodeDto> findSyllabusNodeById(Long id) {
    return optional("""
        SELECT id, qualification_id, subject_id, parent_id, syllabus_version, node_code,
               node_name, level_no, sort_order, status
        FROM kp_syllabus_node
        WHERE id = ? AND deleted = 0
        """, this::syllabusNode, id);
  }

  @Override
  public SyllabusNodeDto createSyllabusNode(Long id, SyllabusNodeCreateRequest request, String status, int sortOrder) {
    Timestamp now = now();
    jdbcTemplate.update("""
            INSERT INTO kp_syllabus_node (
              id, qualification_id, subject_id, parent_id, syllabus_version, node_code, node_name,
              level_no, sort_order, status, created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 0)
            """,
        id,
        request.qualificationId(),
        request.subjectId(),
        request.parentId(),
        request.syllabusVersion(),
        request.nodeCode(),
        request.nodeName(),
        request.levelNo(),
        sortOrder,
        status,
        now,
        now);
    return findSyllabusNodeById(id).orElseThrow();
  }

  @Override
  public SyllabusNodeDto updateSyllabusNode(
      Long id, SyllabusNodeCreateRequest request, String status, int sortOrder
  ) {
    jdbcTemplate.update("""
            UPDATE kp_syllabus_node
            SET qualification_id = ?, subject_id = ?, parent_id = ?, syllabus_version = ?,
                node_code = ?, node_name = ?, level_no = ?, sort_order = ?, status = ?,
                updated_at = ?
            WHERE id = ? AND deleted = 0
            """,
        request.qualificationId(), request.subjectId(), request.parentId(),
        request.syllabusVersion(), request.nodeCode(), request.nodeName(),
        request.levelNo(), sortOrder, status, now(), id);
    return findSyllabusNodeById(id).orElseThrow();
  }

  @Override
  public List<ChapterNodeDto> findEnabledChapterNodes(Long subjectId) {
    return jdbcTemplate.query("""
            SELECT id, qualification_id, subject_id, parent_id, book_version, course_code,
                   chapter_code, chapter_name, level_no, sort_order, status
            FROM kp_chapter_node
            WHERE deleted = 0 AND status = 'ENABLED' AND subject_id = ?
            ORDER BY level_no ASC, sort_order ASC, id ASC
            """,
        this::chapterNode,
        subjectId);
  }

  @Override
  public PageResponse<ChapterNodeDto> findEnabledRootChapterNodes(Long subjectId, FoundationPageQuery pageQuery) {
    long total = count("""
        SELECT COUNT(*)
        FROM kp_chapter_node
        WHERE deleted = 0 AND status = 'ENABLED' AND subject_id = ? AND parent_id IS NULL
        """, subjectId);
    List<ChapterNodeDto> items = jdbcTemplate.query("""
            SELECT id, qualification_id, subject_id, parent_id, book_version, course_code,
                   chapter_code, chapter_name, level_no, sort_order, status
            FROM kp_chapter_node
            WHERE deleted = 0 AND status = 'ENABLED' AND subject_id = ? AND parent_id IS NULL
            ORDER BY sort_order ASC, id ASC
            LIMIT ? OFFSET ?
            """,
        this::chapterNode,
        subjectId,
        pageQuery.pageSize(),
        pageQuery.offset());
    return page(items, pageQuery, total);
  }

  @Override
  public List<ChapterNodeDto> findEnabledChapterChildren(Long subjectId, List<Long> parentIds) {
    if (parentIds.isEmpty()) {
      return List.of();
    }
    List<Object> args = new ArrayList<>();
    args.add(subjectId);
    args.addAll(parentIds);
    return jdbcTemplate.query("""
            SELECT id, qualification_id, subject_id, parent_id, book_version, course_code,
                   chapter_code, chapter_name, level_no, sort_order, status
            FROM kp_chapter_node
            WHERE deleted = 0 AND status = 'ENABLED' AND subject_id = ? AND parent_id IN (%s)
            ORDER BY level_no ASC, sort_order ASC, id ASC
            """.formatted(placeholders(parentIds.size())),
        this::chapterNode,
        args.toArray());
  }

  @Override
  public Optional<ChapterNodeDto> findChapterNodeById(Long id) {
    return optional("""
        SELECT id, qualification_id, subject_id, parent_id, book_version, course_code,
               chapter_code, chapter_name, level_no, sort_order, status
        FROM kp_chapter_node
        WHERE id = ? AND deleted = 0
        """, this::chapterNode, id);
  }

  @Override
  public ChapterNodeDto createChapterNode(Long id, ChapterNodeCreateRequest request, String status, int sortOrder) {
    Timestamp now = now();
    jdbcTemplate.update("""
            INSERT INTO kp_chapter_node (
              id, qualification_id, subject_id, parent_id, book_version, course_code, chapter_code,
              chapter_name, level_no, sort_order, status, created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 0)
            """,
        id,
        request.qualificationId(),
        request.subjectId(),
        request.parentId(),
        request.bookVersion(),
        request.courseCode(),
        request.chapterCode(),
        request.chapterName(),
        request.levelNo(),
        sortOrder,
        status,
        now,
        now);
    return findChapterNodeById(id).orElseThrow();
  }

  @Override
  public ChapterNodeDto updateChapterNode(Long id, ChapterNodeCreateRequest request, String status, int sortOrder) {
    jdbcTemplate.update("""
            UPDATE kp_chapter_node
               SET parent_id = ?, book_version = ?, course_code = ?, chapter_code = ?, chapter_name = ?,
                   level_no = ?, sort_order = ?, status = ?, updated_at = ?
             WHERE id = ? AND deleted = 0
            """,
        request.parentId(), request.bookVersion(), request.courseCode(), request.chapterCode(),
        request.chapterName(), request.levelNo(), sortOrder, status, now(), id);
    return findChapterNodeById(id).orElseThrow();
  }

  @Override
  public PageResponse<KnowledgeTagDto> findEnabledTags(
      Long qualificationId,
      Long subjectId,
      FoundationPageQuery pageQuery
  ) {
    List<Object> args = new ArrayList<>();
    String where = tagWhere(qualificationId, subjectId, args);
    long total = count("SELECT COUNT(*) FROM kp_tag " + where, args.toArray());
    args.add(pageQuery.pageSize());
    args.add(pageQuery.offset());
    List<KnowledgeTagDto> items = jdbcTemplate.query("""
            SELECT id, qualification_id, subject_id, tag_code, tag_name, tag_type, status
            FROM kp_tag
            %s
            ORDER BY id ASC
            LIMIT ? OFFSET ?
            """.formatted(where),
        this::tag,
        args.toArray());
    return page(items, pageQuery, total);
  }

  @Override
  public Optional<KnowledgeTagDto> findTagById(Long id) {
    return optional("""
        SELECT id, qualification_id, subject_id, tag_code, tag_name, tag_type, status
        FROM kp_tag
        WHERE id = ? AND deleted = 0
        """, this::tag, id);
  }

  @Override
  public KnowledgeTagDto createTag(Long id, KnowledgeTagCreateRequest request, String status) {
    Timestamp now = now();
    jdbcTemplate.update("""
            INSERT INTO kp_tag (
              id, qualification_id, subject_id, tag_code, tag_name, tag_type, status,
              created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 0)
            """,
        id,
        request.qualificationId(),
        request.subjectId(),
        request.tagCode(),
        request.tagName(),
        request.tagType(),
        status,
        now,
        now);
    return findTagById(id).orElseThrow();
  }

  @Override
  public KnowledgeTagDto updateTag(Long id, KnowledgeTagCreateRequest request, String status) {
    jdbcTemplate.update("""
            UPDATE kp_tag
               SET qualification_id = ?, subject_id = ?, tag_code = ?, tag_name = ?, tag_type = ?,
                   status = ?, updated_at = ?
             WHERE id = ? AND deleted = 0
            """,
        request.qualificationId(), request.subjectId(), request.tagCode(), request.tagName(),
        request.tagType(), status, now(), id);
    return findTagById(id).orElseThrow();
  }

  @Override
  public KnowledgeRelationDto createRelation(Long id, KnowledgeRelationCreateRequest request) {
    jdbcTemplate.update("""
            INSERT INTO kp_relation (
              id, source_type, source_id, target_type, target_id, relation_type, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
        id,
        request.sourceType(),
        request.sourceId(),
        request.targetType(),
        request.targetId(),
        request.relationType(),
        now());
    return new KnowledgeRelationDto(
        id,
        request.sourceType(),
        request.sourceId(),
        request.targetType(),
        request.targetId(),
        request.relationType());
  }

  @Override
  public PageResponse<ExamRuleDto> findPublishedExamRules(
      Long qualificationId,
      Long subjectId,
      FoundationPageQuery pageQuery
  ) {
    List<Object> args = new ArrayList<>();
    args.add(qualificationId);
    String where = "WHERE deleted = 0 AND status = 'PUBLISHED' AND qualification_id = ?";
    if (subjectId != null) {
      where += " AND subject_id = ?";
      args.add(subjectId);
    }
    long total = count("SELECT COUNT(*) FROM exam_rule " + where, args.toArray());
    args.add(pageQuery.pageSize());
    args.add(pageQuery.offset());
    List<ExamRuleDto> items = jdbcTemplate.query("""
            SELECT id, qualification_id, subject_id, rule_code, rule_name, rule_version, rule_json, status
            FROM exam_rule
            %s
            ORDER BY id ASC
            LIMIT ? OFFSET ?
            """.formatted(where),
        this::examRule,
        args.toArray());
    return page(items, pageQuery, total);
  }

  @Override
  public ExamRuleDto createExamRule(Long id, ExamRuleCreateRequest request, String status) {
    Timestamp now = now();
    jdbcTemplate.update("""
            INSERT INTO exam_rule (
              id, qualification_id, subject_id, rule_code, rule_name, rule_version, rule_json,
              status, effective_at, period_code, effective_from, effective_to, published_at,
              version, created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, NULL, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 0)
            """,
        id,
        request.qualificationId(),
        request.ruleCode(),
        request.ruleName(),
        request.ruleVersion(),
        request.ruleJson(),
        status,
        request.periodCode(),
        timestamp(request.effectiveFrom()),
        timestamp(request.effectiveTo()),
        timestamp(request.publishedAt()),
        request.version(),
        now,
        now);
    return optional("""
        SELECT id, qualification_id, subject_id, rule_code, rule_name, rule_version, rule_json, status
        FROM exam_rule
        WHERE id = ? AND deleted = 0
        """, this::examRule, id).orElseThrow();
  }

  private String tagWhere(Long qualificationId, Long subjectId, List<Object> args) {
    String where = "WHERE deleted = 0 AND status = 'ENABLED'";
    if (qualificationId != null) {
      where += " AND qualification_id = ?";
      args.add(qualificationId);
    }
    if (subjectId != null) {
      where += " AND subject_id = ?";
      args.add(subjectId);
    }
    return where;
  }

  private QualificationDto qualification(ResultSet rs, int rowNum) throws SQLException {
    return new QualificationDto(
        rs.getLong("id"),
        rs.getString("qualification_code"),
        rs.getString("qualification_name"),
        rs.getString("level_code"),
        rs.getString("status"),
        rs.getInt("sort_order"));
  }

  private SubjectDto subject(ResultSet rs, int rowNum) throws SQLException {
    return new SubjectDto(
        rs.getLong("id"),
        rs.getLong("qualification_id"),
        rs.getString("subject_code"),
        rs.getString("subject_name"),
        rs.getString("subject_type"),
        rs.getInt("sort_order"),
        rs.getString("status"));
  }

  private SyllabusNodeDto syllabusNode(ResultSet rs, int rowNum) throws SQLException {
    return new SyllabusNodeDto(
        rs.getLong("id"),
        rs.getLong("qualification_id"),
        rs.getLong("subject_id"),
        nullableLong(rs, "parent_id"),
        rs.getString("syllabus_version"),
        rs.getString("node_code"),
        rs.getString("node_name"),
        rs.getInt("level_no"),
        rs.getInt("sort_order"),
        rs.getString("status"),
        List.of());
  }

  private ChapterNodeDto chapterNode(ResultSet rs, int rowNum) throws SQLException {
    return new ChapterNodeDto(
        rs.getLong("id"),
        rs.getLong("qualification_id"),
        rs.getLong("subject_id"),
        nullableLong(rs, "parent_id"),
        rs.getString("book_version"),
        rs.getString("course_code"),
        rs.getString("chapter_code"),
        rs.getString("chapter_name"),
        rs.getInt("level_no"),
        rs.getInt("sort_order"),
        rs.getString("status"),
        List.of());
  }

  private KnowledgeTagDto tag(ResultSet rs, int rowNum) throws SQLException {
    return new KnowledgeTagDto(
        rs.getLong("id"),
        nullableLong(rs, "qualification_id"),
        nullableLong(rs, "subject_id"),
        rs.getString("tag_code"),
        rs.getString("tag_name"),
        rs.getString("tag_type"),
        rs.getString("status"));
  }

  private ExamRuleDto examRule(ResultSet rs, int rowNum) throws SQLException {
    return new ExamRuleDto(
        rs.getLong("id"),
        rs.getLong("qualification_id"),
        nullableLong(rs, "subject_id"),
        rs.getString("rule_code"),
        rs.getString("rule_name"),
        rs.getString("rule_version"),
        rs.getString("rule_json"),
        rs.getString("status"));
  }

  private Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
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

  private <T> PageResponse<T> page(List<T> items, FoundationPageQuery pageQuery, long total) {
    return new PageResponse<>(items, pageQuery.pageNo(), pageQuery.pageSize(), total, pageQuery.offset() + items.size() < total);
  }

  private String placeholders(int size) {
    return String.join(",", java.util.Collections.nCopies(size, "?"));
  }

  private Timestamp now() {
    return Timestamp.from(Instant.now());
  }

  private Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }
}
