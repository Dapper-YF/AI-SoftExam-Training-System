CREATE TABLE sys_user (
  id BIGINT NOT NULL,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  display_name VARCHAR(64) NOT NULL,
  mobile VARCHAR(32) NULL,
  email VARCHAR(128) NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ENABLED',
  last_login_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_user_username (username),
  KEY idx_sys_user_mobile (mobile),
  KEY idx_sys_user_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户';

CREATE TABLE sys_role (
  id BIGINT NOT NULL,
  role_code VARCHAR(64) NOT NULL,
  role_name VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ENABLED',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色';

CREATE TABLE sys_user_role (
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (user_id, role_id),
  KEY idx_sys_user_role_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色关系';

CREATE TABLE sys_menu (
  id BIGINT NOT NULL,
  parent_id BIGINT NULL,
  menu_code VARCHAR(96) NOT NULL,
  menu_name VARCHAR(96) NOT NULL,
  menu_type VARCHAR(24) NOT NULL COMMENT 'MENU/BUTTON/API',
  route_path VARCHAR(255) NULL,
  permission_code VARCHAR(128) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1,
  module_code VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_menu_code (menu_code),
  KEY idx_sys_menu_parent (parent_id),
  KEY idx_sys_menu_module (module_code, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜单权限';

CREATE TABLE sys_role_menu (
  role_id BIGINT NOT NULL,
  menu_id BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (role_id, menu_id),
  KEY idx_sys_role_menu_menu (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色菜单关系';

CREATE TABLE sys_feature_flag (
  id BIGINT NOT NULL,
  feature_code VARCHAR(96) NOT NULL,
  feature_name VARCHAR(128) NOT NULL,
  module_code VARCHAR(64) NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  config_json JSON NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_feature_code (feature_code),
  KEY idx_sys_feature_module (module_code, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='功能开关';

CREATE TABLE sys_config (
  id BIGINT NOT NULL,
  config_key VARCHAR(128) NOT NULL,
  config_value TEXT NULL,
  value_type VARCHAR(24) NOT NULL DEFAULT 'STRING',
  module_code VARCHAR(64) NOT NULL,
  description VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_config_key (config_key),
  KEY idx_sys_config_module (module_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统配置';

CREATE TABLE exam_qualification (
  id BIGINT NOT NULL,
  qualification_code VARCHAR(64) NOT NULL,
  qualification_name VARCHAR(96) NOT NULL,
  level_code VARCHAR(32) NOT NULL COMMENT 'HIGH/MIDDLE/LOW',
  status VARCHAR(24) NOT NULL DEFAULT 'ENABLED',
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_exam_qualification_code (qualification_code),
  KEY idx_exam_qualification_status (status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资格配置';

CREATE TABLE exam_subject (
  id BIGINT NOT NULL,
  qualification_id BIGINT NOT NULL,
  subject_code VARCHAR(64) NOT NULL,
  subject_name VARCHAR(96) NOT NULL,
  subject_type VARCHAR(32) NOT NULL COMMENT 'KNOWLEDGE/CASE/ESSAY',
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL DEFAULT 'ENABLED',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_exam_subject_code (qualification_id, subject_code),
  KEY idx_exam_subject_qualification (qualification_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='科目配置';

CREATE TABLE kp_syllabus_node (
  id BIGINT NOT NULL,
  qualification_id BIGINT NOT NULL,
  subject_id BIGINT NOT NULL,
  parent_id BIGINT NULL,
  syllabus_version VARCHAR(32) NOT NULL,
  node_code VARCHAR(96) NOT NULL,
  node_name VARCHAR(128) NOT NULL,
  level_no INT NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL DEFAULT 'ENABLED',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_kp_syllabus_code (qualification_id, subject_id, syllabus_version, node_code),
  KEY idx_kp_syllabus_parent (parent_id),
  KEY idx_kp_syllabus_subject (qualification_id, subject_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='考纲知识点';

CREATE TABLE kp_chapter_node (
  id BIGINT NOT NULL,
  qualification_id BIGINT NOT NULL,
  subject_id BIGINT NOT NULL,
  parent_id BIGINT NULL,
  book_version VARCHAR(64) NULL,
  course_code VARCHAR(64) NULL,
  chapter_code VARCHAR(96) NOT NULL,
  chapter_name VARCHAR(128) NOT NULL,
  level_no INT NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL DEFAULT 'ENABLED',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_kp_chapter_code (qualification_id, subject_id, chapter_code),
  KEY idx_kp_chapter_parent (parent_id),
  KEY idx_kp_chapter_subject (qualification_id, subject_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='章节知识点';

CREATE TABLE kp_tag (
  id BIGINT NOT NULL,
  qualification_id BIGINT NULL,
  subject_id BIGINT NULL,
  tag_code VARCHAR(96) NOT NULL,
  tag_name VARCHAR(128) NOT NULL,
  tag_type VARCHAR(32) NOT NULL COMMENT 'HIGH_FREQUENCY/ERROR_PRONE/CALCULATION/CASE/ESSAY_MATERIAL/ABILITY',
  status VARCHAR(24) NOT NULL DEFAULT 'ENABLED',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_kp_tag_code (tag_code),
  KEY idx_kp_tag_filter (qualification_id, subject_id, tag_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识点标签';

CREATE TABLE kp_relation (
  id BIGINT NOT NULL,
  source_type VARCHAR(32) NOT NULL COMMENT 'SYLLABUS/CHAPTER/TAG',
  source_id BIGINT NOT NULL,
  target_type VARCHAR(32) NOT NULL COMMENT 'SYLLABUS/CHAPTER/TAG',
  target_id BIGINT NOT NULL,
  relation_type VARCHAR(32) NOT NULL COMMENT 'MAPS_TO/RELATED_TO/TAGGED_AS',
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_kp_relation (source_type, source_id, target_type, target_id, relation_type),
  KEY idx_kp_relation_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识点映射关系';

CREATE TABLE kp_mastery_stat (
  id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  qualification_id BIGINT NOT NULL,
  subject_id BIGINT NULL,
  knowledge_type VARCHAR(32) NOT NULL COMMENT 'SYLLABUS/CHAPTER/TAG',
  knowledge_id BIGINT NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  correct_count INT NOT NULL DEFAULT 0,
  wrong_count INT NOT NULL DEFAULT 0,
  mastery_score DECIMAL(6,2) NOT NULL DEFAULT 0,
  last_attempt_at DATETIME(3) NULL,
  reason_stats JSON NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_kp_mastery_user_node (user_id, knowledge_type, knowledge_id),
  KEY idx_kp_mastery_user (user_id, qualification_id, subject_id, mastery_score),
  KEY idx_kp_mastery_node (knowledge_type, knowledge_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户知识点掌握度';

CREATE TABLE exam_rule (
  id BIGINT NOT NULL,
  qualification_id BIGINT NOT NULL,
  subject_id BIGINT NULL,
  rule_code VARCHAR(96) NOT NULL,
  rule_name VARCHAR(128) NOT NULL,
  rule_version VARCHAR(32) NOT NULL,
  rule_json JSON NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  effective_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_exam_rule_version (qualification_id, rule_code, rule_version),
  KEY idx_exam_rule_subject (qualification_id, subject_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='考试规则';
