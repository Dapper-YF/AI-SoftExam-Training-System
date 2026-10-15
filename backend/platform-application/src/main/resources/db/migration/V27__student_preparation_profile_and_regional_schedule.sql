ALTER TABLE sys_user
  ADD COLUMN preparation_qualification_id BIGINT NULL,
  ADD COLUMN exam_region_code VARCHAR(32) NULL,
  ADD COLUMN exam_region_name VARCHAR(96) NULL;

CREATE TABLE exam_region_schedule (
  id BIGINT NOT NULL AUTO_INCREMENT,
  region_code VARCHAR(32) NOT NULL,
  region_name VARCHAR(96) NOT NULL,
  exam_period_code VARCHAR(32) NOT NULL,
  node_type VARCHAR(32) NOT NULL,
  scheduled_at DATETIME(3) NULL,
  note VARCHAR(255) NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_region_schedule (region_code, exam_period_code, node_type),
  KEY idx_region_schedule_public (region_code, exam_period_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
