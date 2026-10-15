CREATE TABLE ai_smart_paper (
  id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  qualification_id BIGINT NOT NULL,
  subject_id BIGINT NOT NULL,
  paper_name VARCHAR(160) NOT NULL,
  question_count INT NOT NULL,
  target_exam_date DATE NULL,
  question_ids_json JSON NOT NULL,
  weak_points_json JSON NOT NULL,
  plan_json JSON NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_ai_smart_paper_user (user_id, updated_at),
  KEY idx_ai_smart_paper_scope (qualification_id, subject_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI smart practice paper and study plan';
