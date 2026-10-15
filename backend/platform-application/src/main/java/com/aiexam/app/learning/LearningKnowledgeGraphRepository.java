package com.aiexam.app.learning;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LearningKnowledgeGraphRepository {
  private final JdbcTemplate jdbc;

  public LearningKnowledgeGraphRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public List<LearningKnowledgeGraphEdgeDto> edges(long qualificationId) {
    return jdbc.query("""
        SELECT e.id, e.qualification_id, e.source_point_id, s.point_name source_name,
               e.target_point_id, t.point_name target_name, e.relation_type
        FROM learning_knowledge_point_edge e
        JOIN learning_knowledge_point s ON s.id = e.source_point_id AND s.deleted = 0
        JOIN learning_knowledge_point t ON t.id = e.target_point_id AND t.deleted = 0
        WHERE e.qualification_id = ? ORDER BY e.id
        """, (rs, ignored) -> new LearningKnowledgeGraphEdgeDto(rs.getLong("id"),
        rs.getLong("qualification_id"), rs.getLong("source_point_id"), rs.getString("source_name"),
        rs.getLong("target_point_id"), rs.getString("target_name"), rs.getString("relation_type")), qualificationId);
  }
}
