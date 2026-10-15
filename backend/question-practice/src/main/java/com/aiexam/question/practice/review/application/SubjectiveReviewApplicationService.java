package com.aiexam.question.practice.review.application;

import com.aiexam.common.error.BusinessException;
import com.aiexam.common.security.PlatformPrincipal;
import com.aiexam.question.admin.application.QuestionAdminCurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Human review queue shared by all subjective and structured practice answers. */
@Service
public class SubjectiveReviewApplicationService {
  private final JdbcTemplate jdbcTemplate;
  private final QuestionAdminCurrentUser currentUser;
  private final ObjectMapper objectMapper;

  public SubjectiveReviewApplicationService(JdbcTemplate jdbcTemplate, QuestionAdminCurrentUser currentUser,
                                            ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.currentUser = currentUser;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public List<ReviewQueueItem> queue(String status, Long qualificationId, Long subjectId, Long questionId) {
    requireReviewer();
    materializePendingReviews();
    StringBuilder sql = new StringBuilder("""
        SELECT r.id, r.attempt_id, r.question_id, r.sub_question_id, r.student_user_id, r.status,
               r.score, r.feedback, r.version, r.created_at, a.qualification_id, a.subject_id,
               paa.answer_json, paq.question_snapshot_json
          FROM practice_subjective_review r
          JOIN practice_attempt a ON a.id = r.attempt_id AND a.deleted = 0
          JOIN practice_attempt_answer paa ON paa.attempt_id = r.attempt_id
             AND paa.sub_question_id = r.sub_question_id AND paa.deleted = 0
          JOIN practice_attempt_question paq ON paq.attempt_id = r.attempt_id
             AND paq.question_id = r.question_id
         WHERE 1 = 1
        """);
    java.util.ArrayList<Object> args = new java.util.ArrayList<>();
    if (status != null && !status.isBlank()) { sql.append(" AND r.status = ?"); args.add(status.trim().toUpperCase()); }
    if (qualificationId != null) { sql.append(" AND a.qualification_id = ?"); args.add(qualificationId); }
    if (subjectId != null) { sql.append(" AND a.subject_id = ?"); args.add(subjectId); }
    if (questionId != null) { sql.append(" AND r.question_id = ?"); args.add(questionId); }
    sql.append(" ORDER BY r.created_at ASC, r.id ASC LIMIT 200");
    return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new ReviewQueueItem(
        rs.getLong("id"), rs.getLong("attempt_id"), rs.getLong("question_id"), rs.getLong("sub_question_id"),
        rs.getLong("student_user_id"), rs.getLong("qualification_id"), rs.getObject("subject_id", Long.class),
        rs.getString("status"), rs.getBigDecimal("score"), rs.getString("feedback"), rs.getLong("version"),
        rs.getString("answer_json"), rs.getString("question_snapshot_json"), rs.getTimestamp("created_at").toInstant()),
        args.toArray());
  }

  @Transactional
  public ReviewResult complete(Long reviewId, CompleteReviewCommand command) {
    PlatformPrincipal reviewer = requireReviewer();
    if (command.score() == null || command.score().signum() < 0) {
      throw new BusinessException("PRACTICE_REVIEW_SCORE_INVALID", "score must be non-negative", HttpStatus.BAD_REQUEST);
    }
    validateScore(reviewId, command.score(), command.scoringPoints());
    int updated = jdbcTemplate.update("""
        UPDATE practice_subjective_review
           SET status = 'COMPLETED', reviewer_user_id = ?, score = ?, feedback = ?, scoring_json = ?,
               version = version + 1, reviewed_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3)
         WHERE id = ? AND status = 'PENDING' AND version = ?
        """, reviewer.userId(), command.score(), command.feedback(), writeJson(command.scoringPoints()), reviewId,
        command.version());
    if (updated == 0) {
      throw new BusinessException("PRACTICE_REVIEW_CONFLICT", "review is no longer pending", HttpStatus.CONFLICT);
    }
    ReviewRow row = jdbcTemplate.queryForObject("""
        SELECT attempt_id, question_id, sub_question_id FROM practice_subjective_review WHERE id = ?
        """, (rs, index) -> new ReviewRow(rs.getLong(1), rs.getLong(2), rs.getLong(3)), reviewId);
    jdbcTemplate.update("""
        UPDATE practice_attempt_answer
           SET score = ?, is_correct = CASE WHEN ? > 0 THEN 1 ELSE 0 END,
               grading_status = CASE WHEN ? > 0 THEN 'CORRECT' ELSE 'WRONG' END,
               updated_at = CURRENT_TIMESTAMP(3)
         WHERE attempt_id = ? AND sub_question_id = ? AND deleted = 0
        """, command.score(), command.score(), command.score(), row.attemptId(), row.subQuestionId());
    refreshAttempt(row.attemptId());
    return new ReviewResult(reviewId, "COMPLETED", command.score(), Instant.now());
  }

  @Transactional
  public ReviewResult returnReview(Long reviewId, ReturnReviewCommand command) {
    PlatformPrincipal reviewer = requireReviewer();
    int updated = jdbcTemplate.update("""
        UPDATE practice_subjective_review
           SET status = 'RETURNED', reviewer_user_id = ?, feedback = ?, scoring_json = ?,
               version = version + 1, reviewed_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3)
         WHERE id = ? AND status = 'PENDING' AND version = ?
        """, reviewer.userId(), command.feedback(), writeJson(command.scoringPoints()), reviewId, command.version());
    if (updated == 0) throw new BusinessException("PRACTICE_REVIEW_CONFLICT", "review is no longer pending", HttpStatus.CONFLICT);
    ReviewRow row = jdbcTemplate.queryForObject("""
        SELECT attempt_id, question_id, sub_question_id FROM practice_subjective_review WHERE id = ?
        """, (rs, index) -> new ReviewRow(rs.getLong(1), rs.getLong(2), rs.getLong(3)), reviewId);
    jdbcTemplate.update("""
        UPDATE practice_attempt_answer
           SET score = NULL, is_correct = NULL, grading_status = 'SAVED', updated_at = CURRENT_TIMESTAMP(3)
         WHERE attempt_id = ? AND sub_question_id = ? AND deleted = 0
        """, row.attemptId(), row.subQuestionId());
    jdbcTemplate.update("""
        UPDATE practice_attempt_question
           SET settlement_status = 'UNSETTLED', earned_score = 0, correct_count = 0, wrong_count = 0,
               pending_review_count = 0, updated_at = CURRENT_TIMESTAMP(3)
         WHERE attempt_id = ? AND question_id = ?
        """, row.attemptId(), row.questionId());
    jdbcTemplate.update("""
        UPDATE practice_attempt
           SET status = 'IN_PROGRESS', submitted_at = NULL,
               pending_review_count = GREATEST(COALESCE(pending_review_count, 0) - 1, 0),
               version = version + 1, updated_at = CURRENT_TIMESTAMP(3)
         WHERE id = ? AND status = 'PENDING_REVIEW'
        """, row.attemptId());
    return new ReviewResult(reviewId, "RETURNED", null, Instant.now());
  }

  private void materializePendingReviews() {
    jdbcTemplate.update("""
        INSERT INTO practice_subjective_review (
          attempt_id, question_id, sub_question_id, student_user_id, status, created_at, updated_at
        )
        SELECT a.id, paa.question_id, paa.sub_question_id, a.user_id, 'PENDING', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
          FROM practice_attempt a
          JOIN practice_attempt_answer paa ON paa.attempt_id = a.id AND paa.deleted = 0
         WHERE a.status = 'PENDING_REVIEW' AND paa.grading_status = 'PENDING_REVIEW'
        ON DUPLICATE KEY UPDATE
          status = CASE WHEN status = 'RETURNED' THEN 'PENDING' ELSE status END,
          reviewer_user_id = CASE WHEN status = 'PENDING' THEN NULL ELSE reviewer_user_id END,
          score = CASE WHEN status = 'PENDING' THEN NULL ELSE score END,
          feedback = CASE WHEN status = 'PENDING' THEN NULL ELSE feedback END,
          reviewed_at = CASE WHEN status = 'PENDING' THEN NULL ELSE reviewed_at END,
          version = CASE WHEN status = 'PENDING' THEN version + 1 ELSE version END,
          updated_at = CURRENT_TIMESTAMP(3)
        """);
  }

  private PlatformPrincipal requireReviewer() {
    PlatformPrincipal principal = currentUser.requirePrincipal();
    boolean permitted = currentUser.isAdmin(principal) || principal.permissions().stream()
        .anyMatch("question:review"::equalsIgnoreCase);
    if (!permitted) {
      throw new BusinessException("PRACTICE_REVIEW_FORBIDDEN", "review permission is required", HttpStatus.FORBIDDEN);
    }
    return principal;
  }

  private void refreshAttempt(Long attemptId) {
    jdbcTemplate.update("""
        UPDATE practice_attempt_question q
           SET earned_score = (SELECT COALESCE(SUM(a.score), 0) FROM practice_attempt_answer a
                                 WHERE a.attempt_id = q.attempt_id AND a.question_id = q.question_id AND a.deleted = 0),
               correct_count = (SELECT COUNT(*) FROM practice_attempt_answer a WHERE a.attempt_id = q.attempt_id
                                  AND a.question_id = q.question_id AND a.grading_status = 'CORRECT' AND a.deleted = 0),
               wrong_count = (SELECT COUNT(*) FROM practice_attempt_answer a WHERE a.attempt_id = q.attempt_id
                                AND a.question_id = q.question_id AND a.grading_status = 'WRONG' AND a.deleted = 0),
               pending_review_count = (SELECT COUNT(*) FROM practice_attempt_answer a WHERE a.attempt_id = q.attempt_id
                                         AND a.question_id = q.question_id AND a.grading_status = 'PENDING_REVIEW' AND a.deleted = 0),
               settlement_status = 'SETTLED', settlement_version = settlement_version + 1,
               settled_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3)
         WHERE q.attempt_id = ?
        """, attemptId);
    jdbcTemplate.update("""
        UPDATE practice_attempt a
           SET total_score = (SELECT COALESCE(SUM(q.earned_score), 0) FROM practice_attempt_question q WHERE q.attempt_id = a.id),
               correct_count = (SELECT COALESCE(SUM(q.correct_count), 0) FROM practice_attempt_question q WHERE q.attempt_id = a.id),
               wrong_count = (SELECT COALESCE(SUM(q.wrong_count), 0) FROM practice_attempt_question q WHERE q.attempt_id = a.id),
               pending_review_count = (SELECT COALESCE(SUM(q.pending_review_count), 0) FROM practice_attempt_question q WHERE q.attempt_id = a.id),
               status = CASE WHEN (SELECT COUNT(*) FROM practice_attempt_answer x WHERE x.attempt_id = a.id
                                    AND x.grading_status = 'PENDING_REVIEW' AND x.deleted = 0) = 0
                             THEN 'SUBMITTED' ELSE 'PENDING_REVIEW' END,
               version = version + 1, updated_at = CURRENT_TIMESTAMP(3)
         WHERE a.id = ?
        """, attemptId);
  }

  private String writeJson(Map<String, Object> value) {
    try { return value == null ? null : objectMapper.writeValueAsString(value); }
    catch (JsonProcessingException exception) { throw new BusinessException("PRACTICE_REVIEW_JSON_INVALID", "scoringPoints must be JSON serializable", HttpStatus.BAD_REQUEST); }
  }

  private void validateScore(Long reviewId, BigDecimal score, Map<String, Object> scoringPoints) {
    String snapshot = jdbcTemplate.queryForObject("""
        SELECT paq.question_snapshot_json FROM practice_subjective_review r
          JOIN practice_attempt_question paq ON paq.attempt_id = r.attempt_id AND paq.question_id = r.question_id
         WHERE r.id = ?
        """, String.class, reviewId);
    try {
      JsonNode root = objectMapper.readTree(snapshot);
      JsonNode subQuestions = root.path("subQuestions");
      BigDecimal max = null;
      Map<String, BigDecimal> maximums = new java.util.HashMap<>();
      for (JsonNode sub : subQuestions) {
        JsonNode points = sub.path("scoringPoints");
        for (JsonNode point : points) {
          String id = point.path("scoringPointId").asText();
          maximums.put(id, point.path("score").decimalValue());
        }
        if (sub.path("subQuestionId").asText().equals(
            jdbcTemplate.queryForObject("SELECT sub_question_id FROM practice_subjective_review WHERE id = ?", String.class, reviewId))) {
          max = sub.path("score").decimalValue();
        }
      }
      if (max != null && score.compareTo(max) > 0) throw scoreInvalid("score exceeds sub-question maximum");
      if (!maximums.isEmpty() && (scoringPoints == null || scoringPoints.isEmpty())) {
        throw scoreInvalid("scoring point scores are required");
      }
      if (scoringPoints != null && !scoringPoints.isEmpty()) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, Object> entry : scoringPoints.entrySet()) {
          BigDecimal pointScore = objectMapper.convertValue(entry.getValue(), JsonNode.class).path("score").decimalValue();
          BigDecimal pointMax = maximums.get(entry.getKey());
          if (pointMax == null || pointScore.signum() < 0 || pointScore.compareTo(pointMax) > 0) {
            throw scoreInvalid("scoring point score is invalid");
          }
          total = total.add(pointScore);
        }
        if (total.compareTo(score) != 0) throw scoreInvalid("scoring point total must equal score");
      }
    } catch (BusinessException exception) { throw exception; }
    catch (Exception exception) { throw scoreInvalid("question scoring snapshot is invalid"); }
  }

  private BusinessException scoreInvalid(String message) {
    return new BusinessException("PRACTICE_REVIEW_SCORE_INVALID", message, HttpStatus.BAD_REQUEST);
  }

  public record CompleteReviewCommand(BigDecimal score, String feedback, Map<String, Object> scoringPoints, long version) { }
  public record ReturnReviewCommand(String feedback, Map<String, Object> scoringPoints, long version) { }
  public record ReviewQueueItem(Long reviewId, Long attemptId, Long questionId, Long subQuestionId, Long studentUserId,
                                Long qualificationId, Long subjectId, String status, BigDecimal score, String feedback,
                                long version, String answerSnapshotJson, String questionSnapshotJson, Instant createdAt) { }
  public record ReviewResult(Long reviewId, String status, BigDecimal score, Instant reviewedAt) { }
  private record ReviewRow(Long attemptId, Long questionId, Long subQuestionId) { }
}
