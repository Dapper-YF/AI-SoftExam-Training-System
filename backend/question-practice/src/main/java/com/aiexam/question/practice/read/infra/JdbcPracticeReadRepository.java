package com.aiexam.question.practice.read.infra;

import com.aiexam.common.api.PageResponse;
import com.aiexam.question.practice.read.repository.PracticeReadRepository;
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
public class JdbcPracticeReadRepository implements PracticeReadRepository {
  private final JdbcTemplate jdbcTemplate;

  public JdbcPracticeReadRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public PageResponse<MistakeReadModel> findMistakes(
      Long userId,
      Long qualificationId,
      Long subjectId,
      String status,
      long pageNo,
      long pageSize
  ) {
    StringBuilder where = new StringBuilder(" WHERE m.user_id = ? AND m.deleted = 0");
    List<Object> parameters = new ArrayList<>();
    parameters.add(userId);
    if (qualificationId != null) {
      where.append(" AND m.qualification_id = ?");
      parameters.add(qualificationId);
    }
    if (subjectId != null) {
      where.append(" AND m.subject_id = ?");
      parameters.add(subjectId);
    }
    if (status != null) {
      where.append(" AND m.status = ?");
      parameters.add(status);
    }
    Long total = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM practice_mistake m" + where, Long.class, parameters.toArray());
    List<Object> pageParameters = new ArrayList<>(parameters);
    pageParameters.add(pageSize);
    pageParameters.add((pageNo - 1) * pageSize);
    List<MistakeReadModel> items = jdbcTemplate.query("""
            SELECT m.question_id, m.qualification_id, m.subject_id, m.last_attempt_id,
                   m.mistake_count, m.last_wrong_at, m.status,
                   paq.question_type, paq.title AS question_title
            FROM practice_mistake m
            LEFT JOIN practice_attempt pa
              ON pa.id = m.last_attempt_id AND pa.user_id = m.user_id
             AND pa.deleted = 0 AND pa.exam_session_subject_id IS NULL
            LEFT JOIN practice_attempt_question paq
              ON paq.attempt_id = pa.id AND paq.question_id = m.question_id
            """ + where + "\n" + """
            ORDER BY COALESCE(m.last_wrong_at, m.updated_at) DESC, m.id DESC
            LIMIT ? OFFSET ?
            """, this::mistake, pageParameters.toArray());
    long safeTotal = total == null ? 0 : total;
    return new PageResponse<>(items, pageNo, pageSize, safeTotal,
        (pageNo - 1) * pageSize + items.size() < safeTotal);
  }

  @Override
  public Optional<MistakeReadModel> findSettledMistake(Long userId, Long questionId) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject("""
          SELECT m.question_id, m.qualification_id, m.subject_id, m.last_attempt_id,
                 m.mistake_count, m.last_wrong_at, m.status,
                 paq.question_type, paq.title AS question_title
          FROM practice_mistake m
          JOIN practice_attempt pa
            ON pa.id = m.last_attempt_id AND pa.user_id = m.user_id
           AND pa.deleted = 0 AND pa.exam_session_subject_id IS NULL
          JOIN practice_attempt_question paq
            ON paq.attempt_id = pa.id AND paq.question_id = m.question_id
           AND paq.settlement_status = 'SETTLED'
          WHERE m.user_id = ? AND m.question_id = ? AND m.deleted = 0
          """, this::mistake, userId, questionId));
    } catch (EmptyResultDataAccessException exception) {
      return Optional.empty();
    }
  }

  @Override
  public ModuleHomeReadModel moduleHome(Long userId) {
    List<AttemptReadModel> recentPractice = attempts(userId, false);
    List<AttemptReadModel> recentScores = attempts(userId, true);
    Long currentQualificationId = recentPractice.isEmpty()
        ? null
        : recentPractice.get(0).qualificationId();
    return new ModuleHomeReadModel(
        currentQualificationId,
        recentPractice,
        recentScores,
        count("SELECT COUNT(*) FROM practice_attempt WHERE user_id = ? AND deleted = 0 "
            + "AND exam_session_subject_id IS NULL", userId),
        count("SELECT COUNT(*) FROM practice_mistake WHERE user_id = ? AND deleted = 0 AND status = 'ACTIVE'",
            userId),
        count("SELECT COUNT(*) FROM practice_favorite WHERE user_id = ? AND deleted = 0", userId));
  }

  @Override
  public PracticeCatalogReadModel catalog(Long qualificationId) {
    List<CatalogQuestionReadModel> questions = jdbcTemplate.query("""
        SELECT q.subject_id, s.subject_name, s.subject_type, q.question_type, COUNT(*) AS question_count
        FROM q_question q
        JOIN q_question_version qv
          ON qv.id = q.current_published_version_id
         AND qv.question_id = q.id AND qv.status = 'PUBLISHED' AND qv.deleted = 0
        JOIN exam_subject s
          ON s.id = q.subject_id AND s.qualification_id = q.qualification_id
         AND s.status = 'ENABLED' AND s.deleted = 0
        WHERE q.qualification_id = ? AND q.status = 'PUBLISHED' AND q.deleted = 0
        GROUP BY q.subject_id, s.subject_name, s.subject_type, q.question_type
        ORDER BY s.sort_order, q.subject_id, q.question_type
        """, (rs, rowNum) -> new CatalogQuestionReadModel(
        rs.getLong("subject_id"),
        rs.getString("subject_name"),
        rs.getString("subject_type"),
        rs.getString("question_type"),
        rs.getLong("question_count")), qualificationId);
    long paperCount = count("""
        SELECT COUNT(*) FROM q_paper
        WHERE qualification_id = ? AND status = 'PUBLISHED' AND deleted = 0
        """, qualificationId);
    List<Integer> paperYears = jdbcTemplate.queryForList("""
        SELECT DISTINCT year_no FROM q_paper
        WHERE qualification_id = ? AND status = 'PUBLISHED' AND deleted = 0 AND year_no IS NOT NULL
        ORDER BY year_no DESC
        """, Integer.class, qualificationId);
    return new PracticeCatalogReadModel(questions, paperCount, paperYears);
  }

  private List<AttemptReadModel> attempts(Long userId, boolean scoresOnly) {
    String terminalClause = scoresOnly ? " AND status IN ('SUBMITTED','PENDING_REVIEW')" : "";
    return jdbcTemplate.query("""
        SELECT id, qualification_id, subject_id, mode, status,
               COALESCE(question_count, 0) AS question_count, started_at, submitted_at, updated_at,
               CASE WHEN status IN ('SUBMITTED','PENDING_REVIEW') THEN total_score ELSE NULL END AS total_score,
               CASE WHEN status IN ('SUBMITTED','PENDING_REVIEW') THEN max_score ELSE NULL END AS max_score
        FROM practice_attempt
        WHERE user_id = ? AND deleted = 0 AND exam_session_subject_id IS NULL
        """ + terminalClause + "\n" + """
        ORDER BY updated_at DESC, id DESC
        LIMIT 5
        """, this::attempt, userId);
  }

  private MistakeReadModel mistake(ResultSet rs, int rowNum) throws SQLException {
    return new MistakeReadModel(
        rs.getLong("question_id"),
        rs.getLong("qualification_id"),
        nullableLong(rs, "subject_id"),
        rs.getString("question_type"),
        rs.getString("question_title"),
        nullableLong(rs, "last_attempt_id"),
        rs.getInt("mistake_count"),
        instant(rs, "last_wrong_at"),
        rs.getString("status"));
  }

  private AttemptReadModel attempt(ResultSet rs, int rowNum) throws SQLException {
    return new AttemptReadModel(
        rs.getLong("id"),
        rs.getLong("qualification_id"),
        nullableLong(rs, "subject_id"),
        rs.getString("mode"),
        rs.getString("status"),
        rs.getInt("question_count"),
        instant(rs, "started_at"),
        instant(rs, "submitted_at"),
        instant(rs, "updated_at"),
        rs.getBigDecimal("total_score"),
        rs.getBigDecimal("max_score"));
  }

  private long count(String sql, Object... arguments) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
    return value == null ? 0 : value;
  }

  private Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
