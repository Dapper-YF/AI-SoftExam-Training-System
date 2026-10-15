CREATE TABLE essay_project_background (
  id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  qualification_id BIGINT NOT NULL,
  title VARCHAR(160) NOT NULL,
  industry VARCHAR(96) NULL,
  project_scale VARCHAR(96) NULL,
  project_cycle VARCHAR(96) NULL,
  tech_stack VARCHAR(255) NULL,
  team_size VARCHAR(64) NULL,
  background_content LONGTEXT NOT NULL,
  source_type VARCHAR(32) NOT NULL DEFAULT 'AI',
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_essay_bg_user (user_id, qualification_id, created_at),
  KEY idx_essay_bg_status (qualification_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文项目背景';

CREATE TABLE essay_material_category (
  id BIGINT NOT NULL,
  qualification_id BIGINT NOT NULL,
  parent_id BIGINT NULL,
  category_code VARCHAR(96) NOT NULL,
  category_name VARCHAR(128) NOT NULL,
  category_type VARCHAR(32) NOT NULL COMMENT 'PROCESS/TOOL/TOPIC/ARGUMENT',
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL DEFAULT 'ENABLED',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_essay_material_category (qualification_id, category_code),
  KEY idx_essay_material_category_parent (parent_id),
  KEY idx_essay_material_category_type (qualification_id, category_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文素材分类';

CREATE TABLE essay_material (
  id BIGINT NOT NULL,
  user_id BIGINT NULL,
  qualification_id BIGINT NOT NULL,
  background_id BIGINT NULL,
  category_id BIGINT NULL,
  material_type VARCHAR(32) NOT NULL,
  title VARCHAR(160) NOT NULL,
  content LONGTEXT NOT NULL,
  source_type VARCHAR(32) NOT NULL DEFAULT 'AI',
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  tags VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_essay_material_filter (qualification_id, category_id, material_type, status),
  KEY idx_essay_material_user (user_id, qualification_id, updated_at),
  KEY idx_essay_material_background (background_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文素材';

CREATE TABLE essay_draft (
  id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  qualification_id BIGINT NOT NULL,
  background_id BIGINT NULL,
  title VARCHAR(180) NOT NULL,
  outline LONGTEXT NULL,
  content LONGTEXT NULL,
  word_count INT NOT NULL DEFAULT 0,
  writing_seconds INT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFTING',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_essay_draft_user (user_id, qualification_id, status, updated_at),
  KEY idx_essay_draft_background (background_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文草稿';

CREATE TABLE essay_draft_material (
  draft_id BIGINT NOT NULL,
  material_id BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (draft_id, material_id),
  KEY idx_essay_draft_material_material (material_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文草稿素材关系';

CREATE TABLE essay_draft_revision (
  id BIGINT NOT NULL,
  draft_id BIGINT NOT NULL,
  revision_no INT NOT NULL,
  content_snapshot LONGTEXT NULL,
  summary VARCHAR(500) NULL,
  word_count INT NULL,
  created_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_essay_draft_revision (draft_id, revision_no),
  KEY idx_essay_draft_revision_draft (draft_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文草稿版本摘要';

CREATE TABLE essay_review_rubric (
  id BIGINT NOT NULL,
  qualification_id BIGINT NOT NULL,
  rubric_code VARCHAR(96) NOT NULL,
  rubric_name VARCHAR(160) NOT NULL,
  rubric_version VARCHAR(32) NOT NULL,
  rubric_json JSON NOT NULL,
  total_score DECIMAL(8,2) NOT NULL,
  pass_score DECIMAL(8,2) NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_essay_rubric_version (qualification_id, rubric_code, rubric_version),
  KEY idx_essay_rubric_status (qualification_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文批改规则';

CREATE TABLE essay_review_task (
  id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  draft_id BIGINT NULL,
  qualification_id BIGINT NOT NULL,
  rubric_id BIGINT NOT NULL,
  ai_task_id BIGINT NULL,
  submitted_content LONGTEXT NOT NULL,
  word_count INT NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  submitted_at DATETIME(3) NOT NULL,
  completed_at DATETIME(3) NULL,
  error_message VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_essay_review_task_user (user_id, status, created_at),
  KEY idx_essay_review_task_draft (draft_id, status),
  KEY idx_essay_review_task_ai (ai_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文批改任务';

CREATE TABLE essay_review_result (
  id BIGINT NOT NULL,
  task_id BIGINT NOT NULL,
  rubric_id BIGINT NOT NULL,
  model_code VARCHAR(64) NULL,
  prompt_version VARCHAR(64) NULL,
  total_score DECIMAL(8,2) NOT NULL,
  dimension_scores JSON NULL,
  summary LONGTEXT NULL,
  suggestions LONGTEXT NULL,
  result_json JSON NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_essay_review_result_task (task_id),
  KEY idx_essay_review_result_score (total_score, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文批改结果';

CREATE TABLE essay_review_issue (
  id BIGINT NOT NULL,
  result_id BIGINT NOT NULL,
  paragraph_no INT NULL,
  issue_type VARCHAR(64) NOT NULL,
  severity VARCHAR(24) NOT NULL DEFAULT 'MEDIUM',
  original_text TEXT NULL,
  issue_desc TEXT NOT NULL,
  suggestion TEXT NULL,
  rewrite_example TEXT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_essay_review_issue_result (result_id, issue_type),
  KEY idx_essay_review_issue_severity (severity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文批改问题';
