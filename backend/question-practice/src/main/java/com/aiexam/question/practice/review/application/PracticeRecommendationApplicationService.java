package com.aiexam.question.practice.review.application;

import com.aiexam.question.practice.attempt.application.PracticeCurrentUserProvider;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PracticeRecommendationApplicationService {
  private final JdbcTemplate jdbcTemplate;
  private final PracticeCurrentUserProvider users;
  public PracticeRecommendationApplicationService(JdbcTemplate jdbcTemplate, PracticeCurrentUserProvider users) { this.jdbcTemplate = jdbcTemplate; this.users = users; }
  public List<Recommendation> recommend(Long questionId, int limit) {
    try {
      Long userId = users.requireCurrentUser().userId();
      int size = Math.max(1, Math.min(limit, 20));
      return jdbcTemplate.query("""
        SELECT q.id, q.qualification_id, q.subject_id, q.question_type, q.title, q.difficulty,
               COUNT(DISTINCT kb.knowledge_id) AS overlap
          FROM q_question source
          JOIN q_question q ON q.qualification_id = source.qualification_id AND q.subject_id = source.subject_id
             AND q.id <> source.id AND q.status = 'PUBLISHED' AND q.deleted = 0
          LEFT JOIN q_question_knowledge_binding kb ON kb.question_id = q.id AND kb.deleted = 0
          LEFT JOIN q_question_knowledge_binding sk ON sk.question_id = source.id AND sk.deleted = 0
             AND sk.knowledge_type = kb.knowledge_type AND sk.knowledge_id = kb.knowledge_id
         WHERE source.id = ? AND source.status = 'PUBLISHED' AND source.deleted = 0
           AND NOT EXISTS (SELECT 1 FROM practice_attempt a JOIN practice_attempt_question aq ON aq.attempt_id = a.id
                            WHERE a.user_id = ? AND aq.question_id = q.id AND a.status IN ('SUBMITTED','PENDING_REVIEW'))
         GROUP BY q.id, q.qualification_id, q.subject_id, q.question_type, q.title, q.difficulty
         ORDER BY overlap DESC, q.question_type = source.question_type DESC, q.difficulty, q.id
         LIMIT ?
        """, (rs, i) -> new Recommendation(rs.getLong("id"), rs.getLong("qualification_id"),
        rs.getLong("subject_id"), rs.getString("question_type"), rs.getString("title"), rs.getString("difficulty"),
        rs.getLong("overlap")), questionId, userId, size);
    } catch (DataAccessException | IllegalArgumentException ex) {
      return List.of();
    }
  }
  public record Recommendation(Long questionId, Long qualificationId, Long subjectId, String questionType,
                               String title, String difficulty, long knowledgeOverlap) { }
}
