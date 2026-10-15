CREATE TABLE q_question (
  id BIGINT NOT NULL,
  qualification_id BIGINT NOT NULL,
  subject_id BIGINT NOT NULL,
  question_type VARCHAR(32) NOT NULL,
  title VARCHAR(180) NULL,
  year_no INT NULL,
  difficulty VARCHAR(24) NULL,
  source_type VARCHAR(32) NULL COMMENT 'MANUAL/IMPORT/AI/CRAWLED',
  source_ref VARCHAR(255) NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  content_version INT NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_q_question_filter (qualification_id, subject_id, question_type, status),
  KEY idx_q_question_year (qualification_id, year_no, difficulty, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='题目主表';

CREATE TABLE q_question_stem (
  id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  stem_type VARCHAR(32) NOT NULL COMMENT 'MAIN_STEM/CASE_MATERIAL/ATTACHMENT/ESSAY_REQUIREMENT',
  content LONGTEXT NULL,
  attachment_url VARCHAR(500) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_q_question_stem_question (question_id, stem_type, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='题干与材料';

CREATE TABLE q_sub_question (
  id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  sub_question_no INT NOT NULL,
  sub_question_type VARCHAR(32) NOT NULL COMMENT 'SINGLE_CHOICE/MULTI_CHOICE/JUDGE/CALCULATION/SHORT_ANSWER/ESSAY_WRITING',
  content LONGTEXT NOT NULL,
  score DECIMAL(8,2) NULL,
  answer_format VARCHAR(32) NOT NULL COMMENT 'OPTION/BOOLEAN/TEXT/NUMBER/JSON',
  grading_mode VARCHAR(32) NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO/MANUAL/AI_ASSISTED',
  sort_order INT NOT NULL DEFAULT 0,
  metadata_json JSON NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_q_sub_question_no (question_id, sub_question_no),
  KEY idx_q_sub_question_type (question_id, sub_question_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='子题';

CREATE TABLE q_choice_option (
  id BIGINT NOT NULL,
  sub_question_id BIGINT NOT NULL,
  option_key VARCHAR(16) NOT NULL,
  option_content TEXT NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_q_choice_option_key (sub_question_id, option_key),
  KEY idx_q_choice_option_sub_question (sub_question_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='选择题选项';

CREATE TABLE q_answer_key (
  id BIGINT NOT NULL,
  sub_question_id BIGINT NOT NULL,
  answer_format VARCHAR(32) NOT NULL COMMENT 'OPTION/BOOLEAN/TEXT/NUMBER/JSON',
  answer_value LONGTEXT NULL,
  answer_json JSON NULL,
  version_no INT NOT NULL DEFAULT 1,
  current_flag TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_q_answer_key_sub_question (sub_question_id, current_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准答案';

CREATE TABLE q_solution (
  id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  sub_question_id BIGINT NULL,
  solution_type VARCHAR(32) NOT NULL COMMENT 'STANDARD/AI_ENHANCED/SCORING_GUIDE/WRITING_HINT',
  content LONGTEXT NOT NULL,
  ai_task_id BIGINT NULL,
  prompt_version VARCHAR(64) NULL,
  model_code VARCHAR(64) NULL,
  source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
  version_no INT NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_q_solution_question (question_id, solution_type),
  KEY idx_q_solution_sub_question (sub_question_id, solution_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='解析';

CREATE TABLE q_scoring_rule (
  id BIGINT NOT NULL,
  sub_question_id BIGINT NOT NULL,
  rule_type VARCHAR(32) NOT NULL COMMENT 'MULTI_CHOICE/CALCULATION/SHORT_ANSWER/ESSAY',
  rule_json JSON NOT NULL,
  version_no INT NOT NULL DEFAULT 1,
  current_flag TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_q_scoring_rule_sub_question (sub_question_id, current_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评分规则';

CREATE TABLE q_question_knowledge_binding (
  id BIGINT NOT NULL,
  target_type VARCHAR(32) NOT NULL COMMENT 'QUESTION/SUB_QUESTION',
  target_id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  sub_question_id BIGINT NULL,
  knowledge_type VARCHAR(32) NOT NULL COMMENT 'SYLLABUS/CHAPTER/TAG',
  knowledge_id BIGINT NOT NULL,
  weight DECIMAL(6,2) NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_q_knowledge_binding (target_type, target_id, knowledge_type, knowledge_id),
  KEY idx_q_knowledge_binding_question (question_id),
  KEY idx_q_knowledge_binding_knowledge (knowledge_type, knowledge_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='题目知识点绑定';

CREATE TABLE q_explanation_cache (
  id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  sub_question_id BIGINT NULL,
  cache_key VARCHAR(160) NOT NULL,
  ai_task_id BIGINT NULL,
  content LONGTEXT NOT NULL,
  model_code VARCHAR(64) NULL,
  prompt_version VARCHAR(64) NULL,
  expires_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_q_explanation_cache_key (cache_key),
  KEY idx_q_explanation_question (question_id, sub_question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI解析缓存';

CREATE TABLE q_paper (
  id BIGINT NOT NULL,
  qualification_id BIGINT NOT NULL,
  subject_id BIGINT NULL,
  paper_type VARCHAR(32) NOT NULL COMMENT 'REAL/MOCK/SMART/DAILY',
  paper_name VARCHAR(160) NOT NULL,
  year_no INT NULL,
  total_score DECIMAL(8,2) NULL,
  duration_minutes INT NULL,
  rule_snapshot JSON NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_q_paper_filter (qualification_id, subject_id, paper_type, year_no),
  KEY idx_q_paper_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='试卷';

CREATE TABLE q_paper_question (
  id BIGINT NOT NULL,
  paper_id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  section_code VARCHAR(64) NULL,
  question_no INT NOT NULL,
  score DECIMAL(8,2) NULL,
  question_snapshot JSON NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_q_paper_question_no (paper_id, question_no),
  KEY idx_q_paper_question_question (question_id),
  KEY idx_q_paper_question_paper (paper_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='试卷题目';

CREATE TABLE practice_attempt (
  id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  qualification_id BIGINT NOT NULL,
  subject_id BIGINT NULL,
  paper_id BIGINT NULL,
  mode VARCHAR(32) NOT NULL COMMENT 'PRACTICE/REAL_PAPER/MOCK_EXAM/DAILY',
  status VARCHAR(24) NOT NULL DEFAULT 'IN_PROGRESS',
  started_at DATETIME(3) NOT NULL,
  submitted_at DATETIME(3) NULL,
  duration_seconds INT NULL,
  total_score DECIMAL(8,2) NULL,
  correct_count INT NULL,
  wrong_count INT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_practice_attempt_user (user_id, qualification_id, mode, created_at),
  KEY idx_practice_attempt_paper (paper_id, status),
  KEY idx_practice_attempt_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='作答记录';

CREATE TABLE practice_attempt_answer (
  id BIGINT NOT NULL,
  attempt_id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  sub_question_id BIGINT NOT NULL,
  answer_content LONGTEXT NULL,
  answer_json JSON NULL,
  is_correct TINYINT NULL,
  score DECIMAL(8,2) NULL,
  used_seconds INT NULL,
  marked TINYINT NOT NULL DEFAULT 0,
  viewed_analysis TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_practice_attempt_answer (attempt_id, sub_question_id),
  KEY idx_practice_answer_question (question_id, sub_question_id),
  KEY idx_practice_answer_correct (attempt_id, is_correct)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='作答明细';

CREATE TABLE practice_exam_report (
  id BIGINT NOT NULL,
  attempt_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  qualification_id BIGINT NOT NULL,
  report_json JSON NOT NULL,
  knowledge_point_stats JSON NULL,
  generated_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_practice_report_attempt (attempt_id),
  KEY idx_practice_report_user (user_id, qualification_id, generated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模考报告';

CREATE TABLE practice_mistake (
  id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  qualification_id BIGINT NOT NULL,
  subject_id BIGINT NULL,
  first_attempt_id BIGINT NULL,
  last_attempt_id BIGINT NULL,
  mistake_count INT NOT NULL DEFAULT 1,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  reason_code VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_practice_mistake_user_question (user_id, question_id),
  KEY idx_practice_mistake_user (user_id, qualification_id, status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='错题';

CREATE TABLE practice_favorite (
  id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  qualification_id BIGINT NOT NULL,
  subject_id BIGINT NULL,
  favorite_group VARCHAR(96) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_practice_favorite_user_question (user_id, question_id),
  KEY idx_practice_favorite_user (user_id, qualification_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='收藏题';

CREATE TABLE practice_note (
  id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  target_type VARCHAR(32) NOT NULL COMMENT 'QUESTION/KNOWLEDGE_POINT/PAPER',
  target_id BIGINT NOT NULL,
  qualification_id BIGINT NULL,
  subject_id BIGINT NULL,
  note_content LONGTEXT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_practice_note_target (user_id, target_type, target_id),
  KEY idx_practice_note_user (user_id, qualification_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='学习笔记';
