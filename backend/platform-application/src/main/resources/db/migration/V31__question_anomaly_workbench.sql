CREATE TABLE q_question_anomaly (
  id BIGINT NOT NULL AUTO_INCREMENT,
  question_id BIGINT NOT NULL,
  question_version_id BIGINT NOT NULL,
  anomaly_type VARCHAR(40) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  resolution_note VARCHAR(500) NULL,
  resolved_by BIGINT NULL,
  resolved_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_q_anomaly (question_version_id, anomaly_type),
  KEY idx_q_anomaly_status (status, anomaly_type, updated_at),
  CONSTRAINT ck_q_anomaly_type CHECK (anomaly_type IN ('MISSING_QUESTION_NO','INCOMPLETE_STEM','INCOMPLETE_OPTION','MISSING_IMAGE')),
  CONSTRAINT ck_q_anomaly_status CHECK (status IN ('OPEN','IN_PROGRESS','FIXED','IGNORED','FALSE_POSITIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
