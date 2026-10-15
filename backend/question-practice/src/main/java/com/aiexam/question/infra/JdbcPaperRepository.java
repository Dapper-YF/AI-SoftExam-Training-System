package com.aiexam.question.infra;

import com.aiexam.common.api.PageResponse;
import com.aiexam.question.dto.PaperDetailDto;
import com.aiexam.question.dto.PaperQuestionSummaryDto;
import com.aiexam.question.dto.PaperSummaryDto;
import com.aiexam.question.dto.request.PaperCreateRequest;
import com.aiexam.question.dto.request.PaperQuestionAddRequest;
import com.aiexam.question.dto.request.PaperUpdateRequest;
import com.aiexam.question.repository.PaperRepository;
import java.math.BigDecimal;
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
public class JdbcPaperRepository implements PaperRepository {
  private final JdbcTemplate jdbcTemplate;

  public JdbcPaperRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void insertPaper(Long paperId, PaperCreateRequest request, String status) {
    Timestamp now = now();
    jdbcTemplate.update("""
            INSERT INTO q_paper (
              id, qualification_id, subject_id, paper_type, paper_name, year_no, total_score,
              duration_minutes, rule_snapshot, status, created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, NULL, NULL, 0)
            """,
        paperId,
        request.qualificationId(),
        request.subjectId(),
        upper(request.paperType()),
        request.paperName(),
        request.yearNo(),
        request.totalScore(),
        request.durationMinutes(),
        status,
        now,
        now);
  }

  @Override
  public void updatePaper(Long paperId, PaperUpdateRequest request) {
    jdbcTemplate.update("""
            UPDATE q_paper
            SET qualification_id = COALESCE(?, qualification_id),
                subject_id = COALESCE(?, subject_id),
                paper_type = COALESCE(?, paper_type),
                paper_name = COALESCE(?, paper_name),
                year_no = COALESCE(?, year_no),
                total_score = COALESCE(?, total_score),
                duration_minutes = COALESCE(?, duration_minutes),
                updated_at = ?
            WHERE id = ? AND deleted = 0
            """,
        request.qualificationId(),
        request.subjectId(),
        upper(request.paperType()),
        request.paperName(),
        request.yearNo(),
        request.totalScore(),
        request.durationMinutes(),
        now(),
        paperId);
  }

  @Override
  public void insertPaperQuestion(
      Long paperQuestionId,
      Long paperId,
      PaperQuestionAddRequest request,
      String questionSnapshot
  ) {
    Timestamp now = now();
    jdbcTemplate.update("""
            INSERT INTO q_paper_question (
              id, paper_id, question_id, section_code, question_no, score, question_snapshot,
              created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 0)
            """,
        paperQuestionId,
        paperId,
        request.questionId(),
        request.sectionCode(),
        request.questionNo(),
        request.score(),
        questionSnapshot,
        now,
        now);
  }

  @Override
  public void publishPaper(Long paperId, String ruleSnapshot) {
    jdbcTemplate.update("""
            UPDATE q_paper
            SET status = 'PUBLISHED',
                rule_snapshot = ?,
                updated_at = ?
            WHERE id = ? AND deleted = 0
            """,
        ruleSnapshot,
        now(),
        paperId);
  }

  @Override
  public Optional<PaperSummaryDto> findPaperSummary(Long paperId) {
    return optional("""
            SELECT id, qualification_id, subject_id, paper_name, paper_type, year_no, total_score,
                   duration_minutes, status
            FROM q_paper
            WHERE id = ? AND deleted = 0
            """,
        this::summary,
        paperId);
  }

  @Override
  public Optional<PaperDetailDto> findAdminPaperDetail(Long paperId) {
    return findPaperSummary(paperId).map(summary -> detail(summary, findPaperQuestions(paperId)));
  }

  @Override
  public Optional<PaperDetailDto> findPublishedPaperDetail(Long paperId) {
    return optional("""
            SELECT id, qualification_id, subject_id, paper_name, paper_type, year_no, total_score,
                   duration_minutes, status
            FROM q_paper
            WHERE id = ? AND deleted = 0 AND status = 'PUBLISHED'
            """,
        this::summary,
        paperId).map(summary -> detail(summary, findPaperQuestions(paperId)));
  }

  @Override
  public PageResponse<PaperSummaryDto> findPublishedPapers(
      Long qualificationId,
      Long subjectId,
      String paperType,
      Integer yearNo,
      long pageNo,
      long pageSize
  ) {
    StringBuilder where = new StringBuilder(" WHERE deleted = 0 AND status = 'PUBLISHED'");
    List<Object> args = new ArrayList<>();
    if (qualificationId != null) {
      where.append(" AND qualification_id = ?");
      args.add(qualificationId);
    }
    if (subjectId != null) {
      where.append(" AND subject_id = ?");
      args.add(subjectId);
    }
    if (paperType != null && !paperType.isBlank()) {
      where.append(" AND paper_type = ?");
      args.add(upper(paperType));
    }
    if (yearNo != null) {
      where.append(" AND year_no = ?");
      args.add(yearNo);
    }

    Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM q_paper" + where, Long.class, args.toArray());
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(pageSize);
    pageArgs.add(offset(pageNo, pageSize));
    List<PaperSummaryDto> items = jdbcTemplate.query(publishedPapersPageSql(where.toString()),
        this::summary,
        pageArgs.toArray());
    long safeTotal = total == null ? 0 : total;
    return new PageResponse<>(items, pageNo, pageSize, safeTotal, offset(pageNo, pageSize) + items.size() < safeTotal);
  }

  @Override
  public void updatePaperQuestionSnapshot(Long paperId, Long questionId,
      String questionSnapshot) {
    int updated = jdbcTemplate.update("""
        UPDATE q_paper_question
        SET question_snapshot = ?, updated_at = ?
        WHERE paper_id = ? AND question_id = ? AND deleted = 0
        """, questionSnapshot, now(), paperId, questionId);
    if (updated != 1) {
      throw new IllegalStateException("Paper question snapshot could not be refreshed");
    }
  }

  @Override
  public PageResponse<PaperSummaryDto> findAdminPapers(
      String status, String paperType, Long qualificationId, Long subjectId, String keyword, Integer yearNo,
      long pageNo, long pageSize) {
    StringBuilder where = new StringBuilder(" WHERE deleted = 0");
    List<Object> args = new ArrayList<>();
    if (status != null && !status.isBlank()) { where.append(" AND status = ?"); args.add(upper(status)); }
    if (paperType != null && !paperType.isBlank()) { where.append(" AND paper_type = ?"); args.add(upper(paperType)); }
    if (qualificationId != null) { where.append(" AND qualification_id = ?"); args.add(qualificationId); }
    if (subjectId != null) { where.append(" AND subject_id = ?"); args.add(subjectId); }
    if (keyword != null && !keyword.isBlank()) { where.append(" AND paper_name LIKE ?"); args.add("%" + keyword.trim() + "%"); }
    if (yearNo != null) { where.append(" AND year_no = ?"); args.add(yearNo); }
    Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM q_paper" + where, Long.class, args.toArray());
    List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(pageSize); pageArgs.add(offset(pageNo, pageSize));
    List<PaperSummaryDto> items = jdbcTemplate.query("""
        SELECT id, qualification_id, subject_id, paper_name, paper_type, year_no, total_score,
               duration_minutes, status
        FROM q_paper
        """ + where + " ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?", this::summary, pageArgs.toArray());
    long safeTotal = total == null ? 0 : total;
    return new PageResponse<>(items, pageNo, pageSize, safeTotal, offset(pageNo, pageSize) + items.size() < safeTotal);
  }

  static String publishedPapersPageSql(String where) {
    return """
        SELECT id, qualification_id, subject_id, paper_name, paper_type, year_no, total_score,
               duration_minutes, status
        FROM q_paper
        """ + where + " " + """
        ORDER BY updated_at DESC, id DESC
        LIMIT ? OFFSET ?
        """;
  }

  @Override
  public List<PaperQuestionSummaryDto> findPaperQuestions(Long paperId) {
    return jdbcTemplate.query("""
            SELECT pq.question_id, pq.question_no, q.question_type, q.title, pq.score, q.subject_id, q.content_version
            FROM q_paper_question pq
            JOIN q_question q ON q.id = pq.question_id AND q.deleted = 0
            WHERE pq.paper_id = ? AND pq.deleted = 0
            ORDER BY pq.question_no ASC, pq.id ASC
            """,
        this::paperQuestion,
        paperId);
  }

  @Override
  public boolean existsPaperQuestionNo(Long paperId, Integer questionNo) {
    return count("SELECT COUNT(*) FROM q_paper_question WHERE paper_id = ? AND question_no = ? AND deleted = 0", paperId, questionNo) > 0;
  }

  @Override
  public boolean existsPaperQuestionId(Long paperId, Long questionId) {
    return count("SELECT COUNT(*) FROM q_paper_question WHERE paper_id = ? AND question_id = ? AND deleted = 0", paperId, questionId) > 0;
  }

  @Override
  public BigDecimal sumPaperQuestionScores(Long paperId) {
    BigDecimal sum = jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(score), 0) FROM q_paper_question WHERE paper_id = ? AND deleted = 0",
        BigDecimal.class,
        paperId);
    return sum == null ? BigDecimal.ZERO : sum;
  }

  @Override
  public long countPaperQuestions(Long paperId) {
    return count("SELECT COUNT(*) FROM q_paper_question WHERE paper_id = ? AND deleted = 0", paperId);
  }

  @Override
  public long countUnpublishedPaperQuestions(Long paperId) {
    return count("""
            SELECT COUNT(*)
            FROM q_paper_question pq
            JOIN q_question q ON q.id = pq.question_id AND q.deleted = 0
            WHERE pq.paper_id = ? AND pq.deleted = 0 AND q.status <> 'PUBLISHED'
            """,
        paperId);
  }

  @Override
  public long countPaperQuestionsWithoutKnowledgeTag(Long paperId) {
    return count("""
            SELECT COUNT(*)
            FROM q_paper_question pq
            JOIN q_question q ON q.id = pq.question_id AND q.deleted = 0
            WHERE pq.paper_id = ? AND pq.deleted = 0
              AND NOT EXISTS (
                SELECT 1
                FROM q_question_knowledge_binding binding
                WHERE binding.question_id = q.id
                  AND binding.question_version_id =
                    COALESCE(q.current_working_version_id, q.current_published_version_id)
                  AND binding.knowledge_type = 'TAG'
                  AND binding.deleted = 0
              )
            """,
        paperId);
  }

  @Override
  public List<String> findPaperStemContents(Long paperId) {
    return jdbcTemplate.query("""
            SELECT stem.content
            FROM q_paper_question pq
            JOIN q_question question ON question.id = pq.question_id AND question.deleted = 0
            JOIN q_question_stem stem
              ON stem.question_id = question.id
             AND stem.question_version_id =
               COALESCE(question.current_working_version_id, question.current_published_version_id)
             AND stem.deleted = 0
            WHERE pq.paper_id = ? AND pq.deleted = 0
            ORDER BY pq.question_no, stem.sort_order, stem.id
            """, (rs, ignored) -> rs.getString("content"), paperId);
  }

  @Override
  public void discardDraftPaper(Long paperId) {
    Timestamp timestamp = now();
    jdbcTemplate.update("""
        UPDATE q_paper_question
        SET deleted = 1, updated_at = ?
        WHERE paper_id = ? AND deleted = 0
        """, timestamp, paperId);
    int updated = jdbcTemplate.update("""
        UPDATE q_paper
        SET deleted = 1, updated_at = ?
        WHERE id = ? AND status = 'DRAFT' AND deleted = 0
        """, timestamp, paperId);
    if (updated != 1) {
      throw new IllegalStateException("Draft paper changed before rejection");
    }
  }

  @Override
  public void clearPaperQuestions(Long paperId) {
    jdbcTemplate.update("""
        DELETE FROM q_paper_question
        WHERE paper_id = ?
          AND EXISTS (
            SELECT 1 FROM q_paper
            WHERE q_paper.id = q_paper_question.paper_id
              AND q_paper.status = 'DRAFT'
              AND q_paper.deleted = 0
          )
        """, paperId);
  }

  private PaperSummaryDto summary(ResultSet rs, int rowNum) throws SQLException {
    return new PaperSummaryDto(
        rs.getLong("id"),
        rs.getLong("qualification_id"),
        nullableLong(rs, "subject_id"),
        rs.getString("paper_name"),
        rs.getString("paper_type"),
        nullableInt(rs, "year_no"),
        rs.getBigDecimal("total_score"),
        nullableInt(rs, "duration_minutes"),
        rs.getString("status"));
  }

  private PaperQuestionSummaryDto paperQuestion(ResultSet rs, int rowNum) throws SQLException {
    return new PaperQuestionSummaryDto(
        rs.getLong("question_id"),
        rs.getInt("question_no"),
        rs.getString("question_type"),
        rs.getString("title"),
        rs.getBigDecimal("score"),
        rs.getLong("subject_id"),
        rs.getInt("content_version"));
  }

  private PaperDetailDto detail(PaperSummaryDto summary, List<PaperQuestionSummaryDto> questions) {
    return new PaperDetailDto(
        summary.paperId(),
        summary.qualificationId(),
        summary.subjectId(),
        summary.paperName(),
        summary.paperType(),
        summary.yearNo(),
        summary.totalScore(),
        summary.durationMinutes(),
        summary.status(),
        questions);
  }

  private <T> Optional<T> optional(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject(sql, mapper, args));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  private long count(String sql, Object... args) {
    Long total = jdbcTemplate.queryForObject(sql, Long.class, args);
    return total == null ? 0 : total;
  }

  private long offset(long pageNo, long pageSize) {
    return Math.max(0, (pageNo - 1) * pageSize);
  }

  private Timestamp now() {
    return Timestamp.from(Instant.now());
  }

  private String upper(String value) {
    return value == null ? null : value.trim().toUpperCase();
  }

  private Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private Integer nullableInt(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }
}
