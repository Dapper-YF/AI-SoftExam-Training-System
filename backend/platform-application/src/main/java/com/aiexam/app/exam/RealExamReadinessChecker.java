package com.aiexam.app.exam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Production startup guard for the real machine-exam inventory.
 * Dev data is deliberately excluded: production must import and publish real papers.
 */
@Component
@Profile("!dev")
public class RealExamReadinessChecker implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(RealExamReadinessChecker.class);
  private final JdbcTemplate jdbc;
  private final boolean required;

  public RealExamReadinessChecker(
      JdbcTemplate jdbc,
      @Value("${aiexam.exam-simulator.require-real-paper-on-startup:false}") boolean required
  ) {
    this.jdbc = jdbc;
    this.required = required;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!required) {
      log.info("Real exam startup guard is disabled for this runtime");
      return;
    }
    Integer ready = jdbc.queryForObject("""
        SELECT COUNT(DISTINCT p.id)
        FROM q_paper p
        JOIN practice_exam_entry_subject es
          ON es.paper_id = p.id AND es.paper_content_version = p.content_version
        JOIN practice_exam_entry e ON e.id = es.entry_id AND e.status = 'PUBLISHED'
        WHERE p.deleted = 0 AND p.status = 'PUBLISHED' AND p.paper_type = 'REAL'
          AND EXISTS (
            SELECT 1 FROM q_paper_question pq
            JOIN q_question q ON q.id = pq.question_id
              AND q.deleted = 0 AND q.status = 'PUBLISHED'
            WHERE pq.paper_id = p.id AND pq.deleted = 0
          )
        """, Integer.class);
    if (ready != null && ready > 0) {
      log.info("Real exam readiness check passed: {} published papers are bound to exam entries", ready);
      return;
    }
    String message = "No complete published REAL paper is bound to a published exam entry";
    throw new IllegalStateException("EXAM_REAL_PAPER_NOT_READY: " + message);
  }
}
