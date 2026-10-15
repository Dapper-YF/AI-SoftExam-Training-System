package com.aiexam.ai.infra;

import com.aiexam.ai.dto.AiTaskStatusDto;
import com.aiexam.ai.repository.AiTaskRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAiTaskRepository implements AiTaskRepository {
  private static final ZoneOffset DEFAULT_OFFSET = ZoneOffset.ofHours(8);

  private final JdbcTemplate jdbcTemplate;

  public JdbcAiTaskRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<AiTaskStatusDto> findStatus(Long taskId) {
    return optional("""
        SELECT id, biz_type, biz_id, scenario, status, retry_count, created_at
        FROM ai_task
        WHERE id = ? AND deleted = 0
        """, taskId);
  }

  @Override
  public Optional<AiTaskStatusDto> findLatestByBiz(String bizType, Long bizId, String scenario) {
    try {
      return jdbcTemplate.query("""
              SELECT id, biz_type, biz_id, scenario, status, retry_count, created_at
              FROM ai_task
              WHERE biz_type = ? AND biz_id = ? AND scenario = ? AND deleted = 0
              ORDER BY created_at DESC, id DESC
              LIMIT 1
              """,
          this::status,
          bizType,
          bizId,
          scenario).stream().findFirst();
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  @Override
  public AiTaskStatusDto createTask(
      Long taskId,
      Long userId,
      String bizType,
      Long bizId,
      String scenario,
      String requestHash,
      String inputSnapshotJson
  ) {
    Timestamp now = Timestamp.from(Instant.now());
    jdbcTemplate.update("""
            INSERT INTO ai_task (
              id, user_id, biz_type, biz_id, scenario, model_code, prompt_template_id,
              request_hash, request_json, status, priority, retry_count, max_retry_count,
              started_at, completed_at, error_message, created_at, updated_at, created_by, updated_by, deleted
            ) VALUES (?, ?, ?, ?, ?, NULL, NULL, ?, ?, 'PENDING', 5, 0, 3, NULL, NULL, NULL, ?, ?, NULL, NULL, 0)
            """,
        taskId,
        userId,
        bizType,
        bizId,
        scenario,
        requestHash,
        inputSnapshotJson,
        now,
        now);
    return findStatus(taskId).orElseThrow();
  }

  private Optional<AiTaskStatusDto> optional(String sql, Object... args) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject(sql, this::status, args));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  private AiTaskStatusDto status(ResultSet rs, int rowNum) throws SQLException {
    return new AiTaskStatusDto(
        rs.getLong("id"),
        rs.getString("biz_type"),
        nullableLong(rs, "biz_id"),
        rs.getString("scenario"),
        rs.getString("status"),
        rs.getInt("retry_count"),
        rs.getTimestamp("created_at").toInstant().atOffset(DEFAULT_OFFSET));
  }

  private Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }
}
