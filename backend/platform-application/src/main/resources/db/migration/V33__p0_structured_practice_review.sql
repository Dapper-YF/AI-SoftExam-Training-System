CREATE TABLE practice_subjective_review (
  id BIGINT NOT NULL AUTO_INCREMENT,
  attempt_id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  sub_question_id BIGINT NOT NULL,
  student_user_id BIGINT NOT NULL,
  reviewer_user_id BIGINT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  score DECIMAL(8,2) NULL,
  feedback TEXT NULL,
  scoring_json JSON NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  reviewed_at DATETIME(3) NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_practice_subjective_review_attempt_sub (attempt_id, sub_question_id),
  KEY idx_practice_subjective_review_queue (status, reviewer_user_id, created_at),
  KEY idx_practice_subjective_review_scope (student_user_id, attempt_id),
  CONSTRAINT ck_practice_subjective_review_status CHECK (status IN ('PENDING','COMPLETED','RETURNED')),
  CONSTRAINT ck_practice_subjective_review_score CHECK (score IS NULL OR score >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE practice_mistake_reason (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  reason_code VARCHAR(24) NOT NULL,
  source VARCHAR(24) NOT NULL,
  suggested_at DATETIME(3) NULL,
  confirmed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_practice_mistake_reason (user_id, question_id, reason_code),
  KEY idx_practice_mistake_reason_filter (user_id, reason_code, question_id),
  CONSTRAINT ck_practice_mistake_reason_code CHECK (reason_code IN ('CONCEPT','CALCULATION','MISREADING')),
  CONSTRAINT ck_practice_mistake_reason_source CHECK (source IN ('SYSTEM','STUDENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE practice_mistake_reason_audit (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  reason_code VARCHAR(24) NOT NULL,
  action VARCHAR(24) NOT NULL,
  actor_user_id BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_practice_mistake_reason_audit (user_id, question_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_q_question_recommendation
  ON q_question (qualification_id, subject_id, status, difficulty, deleted, id);
CREATE INDEX idx_q_knowledge_binding_recommendation
  ON q_question_knowledge_binding (knowledge_type, knowledge_id, question_id, deleted);
