ALTER TABLE practice_attempt
  MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT,
  ADD COLUMN source_type VARCHAR(32) NULL AFTER mode,
  ADD COLUMN source_spec_json JSON NULL AFTER source_type,
  ADD COLUMN feedback_mode VARCHAR(24) NULL AFTER source_spec_json,
  ADD COLUMN requested_count INT NULL AFTER feedback_mode,
  ADD COLUMN question_count INT NULL AFTER requested_count,
  ADD COLUMN partial_flag TINYINT NOT NULL DEFAULT 0 AFTER question_count,
  ADD COLUMN max_score DECIMAL(8,2) NULL AFTER partial_flag,
  ADD COLUMN pending_review_count INT NULL AFTER max_score,
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER pending_review_count,
  ADD KEY idx_practice_attempt_user_status_time (user_id, status, created_at, id),
  ADD CONSTRAINT chk_practice_attempt_requested_count CHECK (requested_count IS NULL OR requested_count > 0),
  ADD CONSTRAINT chk_practice_attempt_question_count CHECK (question_count IS NULL OR question_count > 0),
  ADD CONSTRAINT chk_practice_attempt_score CHECK (max_score IS NULL OR max_score >= 0),
  ADD CONSTRAINT chk_practice_attempt_pending_review CHECK (
    pending_review_count IS NULL OR pending_review_count >= 0
  );

ALTER TABLE practice_attempt_answer
  MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT,
  ADD COLUMN grading_status VARCHAR(24) NULL AFTER answer_json,
  ADD KEY idx_practice_answer_grading (attempt_id, grading_status, sub_question_id),
  ADD CONSTRAINT chk_practice_answer_grading_status CHECK (
    grading_status IS NULL OR grading_status IN ('SAVED','CORRECT','WRONG','PENDING_REVIEW')
  );

CREATE TABLE practice_attempt_question (
  id BIGINT NOT NULL AUTO_INCREMENT,
  attempt_id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  question_order INT NOT NULL,
  question_content_version INT NOT NULL,
  question_type VARCHAR(32) NOT NULL,
  title VARCHAR(180) NULL,
  max_score DECIMAL(8,2) NOT NULL,
  question_snapshot_json JSON NOT NULL,
  grading_snapshot_json JSON NOT NULL,
  solution_snapshot_json JSON NOT NULL,
  knowledge_snapshot_json JSON NOT NULL,
  settlement_status VARCHAR(24) NOT NULL DEFAULT 'UNSETTLED',
  settlement_version BIGINT NOT NULL DEFAULT 0,
  settled_at DATETIME(3) NULL,
  earned_score DECIMAL(8,2) NULL,
  correct_count INT NULL,
  wrong_count INT NULL,
  pending_review_count INT NULL,
  snapshot_hash BINARY(32) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_practice_attempt_question_order (attempt_id, question_order),
  UNIQUE KEY uk_practice_attempt_question (attempt_id, question_id),
  KEY idx_practice_attempt_question_source (question_id, attempt_id),
  KEY idx_practice_attempt_question_settlement (attempt_id, settlement_status, question_order),
  CONSTRAINT chk_practice_attempt_question_order CHECK (question_order >= 1),
  CONSTRAINT chk_practice_attempt_question_score CHECK (max_score >= 0),
  CONSTRAINT chk_practice_attempt_question_settlement CHECK (
    settlement_status IN ('UNSETTLED','SETTLED')
  ),
  CONSTRAINT chk_practice_attempt_question_counts CHECK (
    (correct_count IS NULL OR correct_count >= 0)
    AND (wrong_count IS NULL OR wrong_count >= 0)
    AND (pending_review_count IS NULL OR pending_review_count >= 0)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='普通练习稳定题集快照';

CREATE TABLE practice_attempt_idempotency (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  attempt_scope_id BIGINT NOT NULL COMMENT 'CREATE=0；SAVE/SETTLE/SUBMIT=路径attemptId',
  attempt_id BIGINT NULL,
  operation_code VARCHAR(32) NOT NULL,
  client_mutation_id CHAR(36) NOT NULL,
  request_hash BINARY(32) NOT NULL,
  status VARCHAR(16) NOT NULL,
  owner_token CHAR(36) NOT NULL,
  execution_epoch BIGINT NOT NULL DEFAULT 1,
  lease_expires_at DATETIME(3) NOT NULL,
  http_status SMALLINT NULL,
  response_code VARCHAR(64) NULL,
  response_snapshot_json JSON NULL,
  created_at DATETIME(3) NOT NULL,
  completed_at DATETIME(3) NULL,
  expires_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_practice_attempt_idempotency (
    user_id, operation_code, attempt_scope_id, client_mutation_id
  ),
  KEY idx_practice_attempt_idempotency_lease (status, lease_expires_at, id),
  KEY idx_practice_attempt_idempotency_attempt (attempt_id, created_at),
  KEY idx_practice_attempt_idempotency_expiry (expires_at, id),
  CONSTRAINT chk_practice_attempt_idempotency_operation CHECK (
    operation_code IN ('CREATE_ATTEMPT','SAVE_ANSWER','SETTLE_QUESTION','SUBMIT_ATTEMPT')
  ),
  CONSTRAINT chk_practice_attempt_idempotency_scope CHECK (
    (operation_code = 'CREATE_ATTEMPT' AND attempt_scope_id = 0)
    OR (operation_code IN ('SAVE_ANSWER','SETTLE_QUESTION','SUBMIT_ATTEMPT') AND attempt_scope_id > 0)
  ),
  CONSTRAINT chk_practice_attempt_idempotency_status CHECK (status IN ('PROCESSING','COMPLETED')),
  CONSTRAINT chk_practice_attempt_idempotency_epoch CHECK (execution_epoch >= 1),
  CONSTRAINT chk_practice_attempt_idempotency_snapshot CHECK (
    (status = 'PROCESSING' AND http_status IS NULL AND response_code IS NULL
      AND response_snapshot_json IS NULL AND completed_at IS NULL)
    OR
    (status = 'COMPLETED' AND http_status IS NOT NULL AND response_code IS NOT NULL
      AND response_snapshot_json IS NOT NULL
      AND JSON_TYPE(response_snapshot_json) = 'OBJECT' AND completed_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='普通练习幂等结果';

ALTER TABLE practice_mistake
  MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT,
  ADD COLUMN last_wrong_at DATETIME(3) NULL AFTER mistake_count;
