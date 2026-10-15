CREATE TABLE essay_knowledge_source (
  id BIGINT NOT NULL,
  source_code VARCHAR(96) NOT NULL,
  source_name VARCHAR(160) NOT NULL,
  scope_type VARCHAR(24) NOT NULL COMMENT 'PUBLIC/PRIVATE_USER',
  owner_id BIGINT NULL,
  qualification_id BIGINT NOT NULL,
  subject_id BIGINT NULL,
  source_type VARCHAR(32) NOT NULL,
  copyright_type VARCHAR(32) NOT NULL,
  authorization_basis VARCHAR(500) NOT NULL,
  source_url VARCHAR(1000) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ENABLED',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_essay_knowledge_source_code (source_code),
  KEY idx_essay_knowledge_source_scope (scope_type, owner_id, qualification_id, subject_id, status),
  CONSTRAINT chk_essay_knowledge_source_owner CHECK ((scope_type = 'PUBLIC' AND owner_id IS NULL) OR (scope_type = 'PRIVATE_USER' AND owner_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文知识源';

CREATE TABLE essay_knowledge_document (
  id BIGINT NOT NULL,
  source_id BIGINT NOT NULL,
  document_code VARCHAR(96) NOT NULL,
  title VARCHAR(255) NOT NULL,
  scope_type VARCHAR(24) NOT NULL COMMENT 'PUBLIC/PRIVATE_USER',
  owner_id BIGINT NULL,
  owner_scope_key BIGINT GENERATED ALWAYS AS (IF(scope_type = 'PUBLIC', 0, owner_id)) STORED COMMENT 'PUBLIC固定0，PRIVATE_USER为owner_id',
  qualification_id BIGINT NOT NULL,
  subject_id BIGINT NULL,
  document_type VARCHAR(32) NOT NULL,
  current_version_id BIGINT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_essay_knowledge_document_code (scope_type, owner_scope_key, document_code),
  KEY idx_essay_knowledge_document_source (source_id, status),
  KEY idx_essay_knowledge_document_owner (owner_id, updated_at),
  CONSTRAINT chk_essay_knowledge_document_owner CHECK ((scope_type = 'PUBLIC' AND owner_id IS NULL) OR (scope_type = 'PRIVATE_USER' AND owner_id IS NOT NULL)),
  CONSTRAINT chk_essay_knowledge_document_scope_key CHECK (owner_scope_key IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文知识文档';

CREATE TABLE essay_knowledge_document_version (
  id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  version_label VARCHAR(64) NULL,
  raw_content LONGTEXT NOT NULL,
  normalized_content LONGTEXT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  version_status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_essay_knowledge_document_version (document_id, version_no),
  UNIQUE KEY uk_essay_knowledge_document_hash (document_id, content_hash),
  KEY idx_essay_knowledge_version_status (document_id, version_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文知识文档版本';

CREATE TABLE essay_knowledge_section (
  id BIGINT NOT NULL,
  document_version_id BIGINT NOT NULL,
  parent_id BIGINT NULL,
  section_path VARCHAR(500) NOT NULL,
  section_title VARCHAR(255) NOT NULL,
  level_no INT NOT NULL,
  start_offset INT NOT NULL,
  end_offset INT NOT NULL,
  normalized_content LONGTEXT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_essay_knowledge_section_path (document_version_id, section_path),
  KEY idx_essay_knowledge_section_version (document_version_id, level_no, start_offset),
  CONSTRAINT chk_essay_knowledge_section_range CHECK (start_offset >= 0 AND end_offset > start_offset)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文知识章节';

CREATE TABLE essay_knowledge_fragment (
  id BIGINT NOT NULL,
  document_version_id BIGINT NOT NULL,
  section_id BIGINT NOT NULL,
  fragment_no INT NOT NULL,
  scope_type VARCHAR(24) NOT NULL COMMENT 'PUBLIC/PRIVATE_USER',
  owner_id BIGINT NULL,
  qualification_id BIGINT NOT NULL,
  subject_id BIGINT NULL,
  content LONGTEXT NOT NULL,
  normalized_content LONGTEXT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  start_offset INT NOT NULL,
  end_offset INT NOT NULL,
  audit_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  publish_status VARCHAR(24) NOT NULL DEFAULT 'UNPUBLISHED',
  index_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  invalidated_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  updated_by BIGINT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_essay_knowledge_fragment_no (document_version_id, fragment_no),
  UNIQUE KEY uk_essay_knowledge_fragment_range (document_version_id, start_offset, end_offset),
  KEY idx_essay_knowledge_fragment_recall (scope_type, owner_id, qualification_id, subject_id, audit_status, publish_status, index_status, invalidated_at),
  FULLTEXT KEY ft_essay_knowledge_fragment_text (normalized_content) WITH PARSER ngram,
  CONSTRAINT chk_essay_knowledge_fragment_owner CHECK ((scope_type = 'PUBLIC' AND owner_id IS NULL) OR (scope_type = 'PRIVATE_USER' AND owner_id IS NOT NULL)),
  CONSTRAINT chk_essay_knowledge_fragment_range CHECK (start_offset >= 0 AND end_offset > start_offset)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文知识片段';

CREATE TABLE essay_knowledge_fragment_binding (
  id BIGINT NOT NULL,
  fragment_id BIGINT NOT NULL,
  binding_type VARCHAR(24) NOT NULL COMMENT 'QUALIFICATION/SUBJECT/SYLLABUS/CHAPTER/TAG',
  binding_id BIGINT NOT NULL,
  relation_role VARCHAR(24) NOT NULL DEFAULT 'SUPPORTING' COMMENT 'PRIMARY/SUPPORTING',
  created_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_essay_knowledge_fragment_binding (fragment_id, binding_type, binding_id),
  KEY idx_essay_knowledge_binding_lookup (binding_type, binding_id, fragment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文知识片段基础知识体系绑定';

CREATE TABLE essay_knowledge_lifecycle_event (
  id BIGINT NOT NULL,
  record_kind VARCHAR(16) NOT NULL COMMENT 'COMMAND/STATE_EVENT',
  actor_id BIGINT NULL,
  operation_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  response_snapshot_json JSON NULL,
  result_ref VARCHAR(255) NULL,
  request_dedup_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (
    CASE WHEN record_kind = 'COMMAND'
      THEN CONCAT(actor_id, ':', operation_code, ':', request_id)
      ELSE NULL
    END
  ) STORED,
  event_code VARCHAR(32) NULL,
  resource_type VARCHAR(24) NULL COMMENT 'SOURCE/DOCUMENT/DOCUMENT_VERSION/FRAGMENT',
  resource_id BIGINT NULL,
  event_sequence BIGINT NULL,
  related_command_event_id BIGINT NULL,
  before_status JSON NULL,
  after_status JSON NULL,
  occurred_at DATETIME(3) NULL,
  archived_at DATETIME(3) NULL,
  archive_payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_essay_knowledge_lifecycle_request_dedup (request_dedup_key),
  UNIQUE KEY uk_essay_knowledge_lifecycle_resource_sequence (resource_type, resource_id, event_sequence),
  KEY idx_essay_knowledge_lifecycle_related_command (related_command_event_id),
  KEY idx_essay_knowledge_lifecycle_resource_time (resource_type, resource_id, occurred_at),
  CONSTRAINT chk_essay_knowledge_lifecycle_kind CHECK (record_kind IN ('COMMAND', 'STATE_EVENT')),
  CONSTRAINT chk_essay_knowledge_lifecycle_command CHECK (
    record_kind <> 'COMMAND' OR (
      actor_id IS NOT NULL AND operation_code IS NOT NULL AND request_id IS NOT NULL AND request_hash IS NOT NULL
      AND event_code IS NULL AND event_sequence IS NULL AND related_command_event_id IS NULL
      AND before_status IS NULL AND after_status IS NULL AND occurred_at IS NULL
    )
  ),
  CONSTRAINT chk_essay_knowledge_lifecycle_state_event CHECK (
    record_kind <> 'STATE_EVENT' OR (
      request_id IS NULL AND request_hash IS NULL AND response_snapshot_json IS NULL AND operation_code IS NULL
      AND event_code IS NOT NULL AND resource_type IS NOT NULL AND resource_id IS NOT NULL
      AND event_sequence IS NOT NULL AND related_command_event_id IS NOT NULL
      AND after_status IS NOT NULL AND occurred_at IS NOT NULL
    )
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文知识生命周期事件';

CREATE TABLE essay_knowledge_processing_task (
  id BIGINT NOT NULL,
  task_type VARCHAR(32) NOT NULL COMMENT 'PARSE_DOCUMENT/SPLIT_SECTION/REBUILD_INDEX',
  target_type VARCHAR(24) NOT NULL COMMENT 'DOCUMENT_VERSION/SECTION/FRAGMENT',
  target_id BIGINT NOT NULL,
  operation_code VARCHAR(64) NOT NULL,
  request_id VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  response_snapshot_json JSON NULL,
  result_ref VARCHAR(255) NULL,
  related_command_event_id BIGINT NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  payload_json JSON NULL,
  requested_by BIGINT NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  max_retry_count INT NOT NULL DEFAULT 3,
  started_at DATETIME(3) NULL,
  completed_at DATETIME(3) NULL,
  error_code VARCHAR(64) NULL,
  error_message VARCHAR(500) NULL,
  archived_at DATETIME(3) NULL,
  archive_payload_hash CHAR(64) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_essay_knowledge_processing_request (requested_by, operation_code, request_id),
  UNIQUE KEY uk_essay_knowledge_processing_idempotency (idempotency_key),
  KEY idx_essay_knowledge_processing_status (task_type, status, created_at),
  KEY idx_essay_knowledge_processing_target (target_type, target_id, created_at),
  KEY idx_essay_knowledge_processing_related_command (related_command_event_id),
  CONSTRAINT chk_essay_knowledge_processing_task_type CHECK (task_type IN ('PARSE_DOCUMENT', 'SPLIT_SECTION', 'REBUILD_INDEX'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文知识处理任务';

CREATE TABLE essay_knowledge_retrieval_log (
  id BIGINT NOT NULL,
  retrieval_no VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  scope_type VARCHAR(24) NOT NULL,
  caller_scene VARCHAR(64) NOT NULL,
  query_summary VARCHAR(500) NOT NULL,
  query_hash CHAR(64) NOT NULL,
  filter_json JSON NOT NULL,
  hit_count INT NOT NULL DEFAULT 0,
  top_fragment_ids_json JSON NOT NULL,
  rank_snapshot_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_essay_knowledge_retrieval_no (retrieval_no),
  KEY idx_essay_knowledge_retrieval_user (user_id, caller_scene, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文知识检索日志';

CREATE TABLE essay_knowledge_citation_log (
  id BIGINT NOT NULL,
  retrieval_id BIGINT NOT NULL,
  biz_type VARCHAR(64) NOT NULL,
  biz_id BIGINT NOT NULL,
  citation_scene VARCHAR(64) NOT NULL,
  document_version_id BIGINT NOT NULL,
  section_id BIGINT NOT NULL,
  fragment_id BIGINT NOT NULL,
  start_offset INT NOT NULL,
  end_offset INT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  rank_no INT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_essay_knowledge_citation_biz (biz_type, biz_id, created_at),
  KEY idx_essay_knowledge_citation_retrieval (retrieval_id, rank_no),
  KEY idx_essay_knowledge_citation_fragment (fragment_id, created_at),
  CONSTRAINT chk_essay_knowledge_citation_range CHECK (start_offset >= 0 AND end_offset > start_offset)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='论文知识引用日志';
