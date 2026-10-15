package com.aiexam.question.practice.review.application;

import com.aiexam.question.practice.attempt.application.PracticeCurrentUserProvider;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PracticeMistakeReasonApplicationService {
  private static final Set<String> ALLOWED = Set.of("CONCEPT", "CALCULATION", "MISREADING");
  private final JdbcTemplate jdbcTemplate;
  private final PracticeCurrentUserProvider currentUserProvider;
  public PracticeMistakeReasonApplicationService(JdbcTemplate jdbcTemplate, PracticeCurrentUserProvider currentUserProvider) {
    this.jdbcTemplate = jdbcTemplate; this.currentUserProvider = currentUserProvider;
  }
  @Transactional public List<ReasonItem> replace(Long questionId, List<String> reasonCodes) {
    Long userId = userId();
    Set<String> normalized = new LinkedHashSet<>();
    for (String value : reasonCodes == null ? List.<String>of() : reasonCodes) {
      String code = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
      if (!ALLOWED.contains(code)) throw new IllegalArgumentException("unsupported mistake reason");
      normalized.add(code);
    }
    jdbcTemplate.update("DELETE FROM practice_mistake_reason WHERE user_id = ? AND question_id = ?", userId, questionId);
    for (String code : normalized) {
      jdbcTemplate.update("INSERT INTO practice_mistake_reason (user_id, question_id, reason_code, source, confirmed_at) "
          + "VALUES (?, ?, ?, 'STUDENT', CURRENT_TIMESTAMP(3))", userId, questionId, code);
      jdbcTemplate.update("INSERT INTO practice_mistake_reason_audit "
          + "(user_id, question_id, reason_code, action, actor_user_id) VALUES (?, ?, ?, 'CONFIRMED', ?)",
          userId, questionId, code, userId);
    }
    return reasons(questionId);
  }
  public List<ReasonItem> reasons(Long questionId) {
    try {
      Long userId = userId();
      return jdbcTemplate.query("SELECT reason_code, source, suggested_at, confirmed_at FROM practice_mistake_reason "
        + "WHERE user_id = ? AND question_id = ? ORDER BY reason_code", (rs, i) -> new ReasonItem(
        rs.getString(1), rs.getString(2), rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(),
        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()), userId, questionId);
    } catch (DataAccessException | IllegalArgumentException ex) {
      return List.of();
    }
  }
  public List<ReasonStat> statistics() {
    return jdbcTemplate.query("SELECT reason_code, COUNT(*) FROM practice_mistake_reason WHERE user_id = ? "
        + "GROUP BY reason_code ORDER BY reason_code", (rs, i) -> new ReasonStat(rs.getString(1), rs.getLong(2)), userId());
  }
  private Long userId() { return currentUserProvider.requireCurrentUser().userId(); }
  public record ReasonItem(String reasonCode, String source, java.time.Instant suggestedAt, java.time.Instant confirmedAt) { }
  public record ReasonStat(String reasonCode, long count) { }
}
