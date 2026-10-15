CREATE TABLE app_id_sequence (
  entity_name VARCHAR(96) NOT NULL,
  next_id BIGINT NOT NULL,
  PRIMARY KEY (entity_name),
  CONSTRAINT chk_app_id_sequence_next CHECK (next_id >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='事务安全的应用主键段分配器';

INSERT INTO app_id_sequence (entity_name, next_id)
SELECT 'practice_exam_session', COALESCE(MAX(id), 0) + 1 FROM practice_exam_session
UNION ALL SELECT 'practice_exam_rule_snapshot', COALESCE(MAX(id), 0) + 1 FROM practice_exam_rule_snapshot
UNION ALL SELECT 'practice_exam_session_subject', COALESCE(MAX(id), 0) + 1 FROM practice_exam_session_subject
UNION ALL SELECT 'practice_exam_event', COALESCE(MAX(id), 0) + 1 FROM practice_exam_event
UNION ALL SELECT 'practice_exam_idempotency', COALESCE(MAX(id), 0) + 1 FROM practice_exam_idempotency
UNION ALL SELECT 'practice_attempt', COALESCE(MAX(id), 0) + 1 FROM practice_attempt
UNION ALL SELECT 'practice_attempt_answer', COALESCE(MAX(id), 0) + 1 FROM practice_attempt_answer;
