package com.aiexam.question.practice.infra;

import com.aiexam.common.api.PageResponse;
import com.aiexam.question.practice.dto.PracticeFavoriteDto;
import com.aiexam.question.practice.dto.PracticeNoteDto;
import com.aiexam.question.practice.repository.PracticeFavoriteNoteRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPracticeFavoriteNoteRepository implements PracticeFavoriteNoteRepository {
  private final JdbcTemplate jdbcTemplate;

  public JdbcPracticeFavoriteNoteRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<QuestionRef> findPublishedQuestion(Long questionId) {
    return optional("""
        SELECT id, qualification_id, subject_id, title
        FROM q_question
        WHERE id = ? AND status = 'PUBLISHED' AND deleted = 0
        """, this::questionRef, questionId);
  }

  @Override
  public Optional<FavoriteState> findFavoriteState(Long userId, Long questionId) {
    return optional("""
        SELECT id, deleted
        FROM practice_favorite
        WHERE user_id = ? AND question_id = ?
        """, (rs, rowNum) -> new FavoriteState(rs.getLong("id"), rs.getInt("deleted") != 0), userId, questionId);
  }

  @Override
  public void insertFavorite(Long id, Long userId, QuestionRef question, String favoriteGroup) {
    Timestamp now = now();
    jdbcTemplate.update("""
        INSERT INTO practice_favorite (
          id, user_id, question_id, qualification_id, subject_id, favorite_group,
          created_at, updated_at, created_by, updated_by, deleted
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
        """,
        id,
        userId,
        question.questionId(),
        question.qualificationId(),
        question.subjectId(),
        favoriteGroup,
        now,
        now,
        userId,
        userId);
  }

  @Override
  public void reactivateFavorite(Long id, String favoriteGroup) {
    jdbcTemplate.update("""
        UPDATE practice_favorite
        SET favorite_group = ?, deleted = 0, updated_at = ?
        WHERE id = ?
        """, favoriteGroup, now(), id);
  }

  @Override
  public boolean deleteFavorite(Long userId, Long questionId) {
    int updated = jdbcTemplate.update("""
        UPDATE practice_favorite
        SET deleted = 1, updated_at = ?, updated_by = ?
        WHERE user_id = ? AND question_id = ? AND deleted = 0
        """, now(), userId, userId, questionId);
    return updated > 0;
  }

  @Override
  public boolean updateFavoriteGroup(Long userId, Long questionId, String favoriteGroup) {
    return jdbcTemplate.update("""
        UPDATE practice_favorite
        SET favorite_group = ?, updated_at = ?, updated_by = ?
        WHERE user_id = ? AND question_id = ? AND deleted = 0
        """, favoriteGroup, now(), userId, userId, questionId) > 0;
  }

  @Override
  public Optional<PracticeFavoriteDto> findFavorite(Long userId, Long questionId) {
    return optional("""
        SELECT pf.id, pf.question_id, pf.qualification_id, pf.subject_id, q.title AS question_title,
               pf.favorite_group, pf.created_at
        FROM practice_favorite pf
        LEFT JOIN q_question q ON q.id = pf.question_id
        WHERE pf.user_id = ? AND pf.question_id = ? AND pf.deleted = 0
        """, this::favorite, userId, questionId);
  }

  @Override
  public PageResponse<PracticeFavoriteDto> findFavorites(Long userId, long pageNo, long pageSize) {
    long total = count("SELECT COUNT(*) FROM practice_favorite WHERE user_id = ? AND deleted = 0", userId);
    var items = jdbcTemplate.query("""
        SELECT pf.id, pf.question_id, pf.qualification_id, pf.subject_id, q.title AS question_title,
               pf.favorite_group, pf.created_at
        FROM practice_favorite pf
        LEFT JOIN q_question q ON q.id = pf.question_id
        WHERE pf.user_id = ? AND pf.deleted = 0
        ORDER BY pf.created_at DESC, pf.id DESC
        LIMIT ? OFFSET ?
        """, this::favorite, userId, pageSize, offset(pageNo, pageSize));
    return new PageResponse<>(items, pageNo, pageSize, total, offset(pageNo, pageSize) + items.size() < total);
  }

  @Override
  public void insertNote(
      Long id,
      Long userId,
      String targetType,
      Long targetId,
      Long qualificationId,
      Long subjectId,
      String noteContent
  ) {
    Timestamp now = now();
    jdbcTemplate.update("""
        INSERT INTO practice_note (
          id, user_id, target_type, target_id, qualification_id, subject_id, note_content,
          created_at, updated_at, created_by, updated_by, deleted
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
        """,
        id,
        userId,
        targetType,
        targetId,
        qualificationId,
        subjectId,
        noteContent,
        now,
        now,
        userId,
        userId);
  }

  @Override
  public boolean updateNote(Long userId, Long id, String noteContent) {
    int updated = jdbcTemplate.update("""
        UPDATE practice_note
        SET note_content = ?, updated_at = ?, updated_by = ?
        WHERE id = ? AND user_id = ? AND deleted = 0
        """, noteContent, now(), userId, id, userId);
    return updated > 0;
  }

  @Override
  public Optional<PracticeNoteDto> findNote(Long userId, Long id) {
    return optional("""
        SELECT id, target_type, target_id, qualification_id, subject_id, note_content, created_at, updated_at
        FROM practice_note
        WHERE id = ? AND user_id = ? AND deleted = 0
        """, this::note, id, userId);
  }

  @Override
  public PageResponse<PracticeNoteDto> findNotes(Long userId, long pageNo, long pageSize) {
    long total = count("SELECT COUNT(*) FROM practice_note WHERE user_id = ? AND deleted = 0", userId);
    var items = jdbcTemplate.query("""
        SELECT id, target_type, target_id, qualification_id, subject_id, note_content, created_at, updated_at
        FROM practice_note
        WHERE user_id = ? AND deleted = 0
        ORDER BY updated_at DESC, id DESC
        LIMIT ? OFFSET ?
        """, this::note, userId, pageSize, offset(pageNo, pageSize));
    return new PageResponse<>(items, pageNo, pageSize, total, offset(pageNo, pageSize) + items.size() < total);
  }

  private QuestionRef questionRef(ResultSet rs, int rowNum) throws SQLException {
    return new QuestionRef(
        rs.getLong("id"),
        rs.getLong("qualification_id"),
        rs.getLong("subject_id"),
        rs.getString("title"));
  }

  private PracticeFavoriteDto favorite(ResultSet rs, int rowNum) throws SQLException {
    return new PracticeFavoriteDto(
        rs.getLong("id"),
        rs.getLong("question_id"),
        rs.getLong("qualification_id"),
        nullableLong(rs, "subject_id"),
        rs.getString("question_title"),
        rs.getString("favorite_group"),
        instant(rs, "created_at"));
  }

  private PracticeNoteDto note(ResultSet rs, int rowNum) throws SQLException {
    return new PracticeNoteDto(
        rs.getLong("id"),
        rs.getString("target_type"),
        rs.getLong("target_id"),
        nullableLong(rs, "qualification_id"),
        nullableLong(rs, "subject_id"),
        rs.getString("note_content"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private long count(String sql, Object... args) {
    Long total = jdbcTemplate.queryForObject(sql, Long.class, args);
    return total == null ? 0 : total;
  }

  private <T> Optional<T> optional(String sql, org.springframework.jdbc.core.RowMapper<T> rowMapper, Object... args) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject(sql, rowMapper, args));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  private long offset(long pageNo, long pageSize) {
    return (pageNo - 1) * pageSize;
  }

  private Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp timestamp = rs.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private Timestamp now() {
    return Timestamp.from(Instant.now());
  }
}
