ALTER TABLE ai_assistant_message
  ADD COLUMN answer_mode VARCHAR(24) NULL COMMENT 'GROUNDED/SUPPLEMENTARY/INSUFFICIENT_EVIDENCE' AFTER answer_status,
  ADD COLUMN cache_hit TINYINT NOT NULL DEFAULT 0 AFTER cache_key,
  ADD COLUMN scenario VARCHAR(48) NULL AFTER prompt_version;

ALTER TABLE ai_assistant_attachment
  ADD COLUMN content_hash CHAR(64) NULL AFTER file_size,
  ADD COLUMN detected_payload_json JSON NULL AFTER detected_question_type,
  ADD COLUMN error_message VARCHAR(500) NULL AFTER detected_payload_json;

ALTER TABLE knowledge_document
  ADD COLUMN reviewed_by BIGINT NULL AFTER copyright_basis,
  ADD COLUMN reviewed_at DATETIME(3) NULL AFTER reviewed_by,
  ADD COLUMN published_at DATETIME(3) NULL AFTER reviewed_at,
  ADD COLUMN invalidated_at DATETIME(3) NULL AFTER published_at;

ALTER TABLE knowledge_fragment
  ADD COLUMN source_start_offset INT NULL AFTER content_hash,
  ADD COLUMN source_end_offset INT NULL AFTER source_start_offset,
  ADD COLUMN index_status VARCHAR(24) NOT NULL DEFAULT 'READY' AFTER status;

CREATE TABLE knowledge_document_version (
  id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  raw_content LONGTEXT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  created_by BIGINT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_knowledge_document_version (document_id, version_no),
  UNIQUE KEY uk_knowledge_document_version_hash (document_id, content_hash),
  KEY idx_knowledge_document_version_document (document_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='knowledge document version';

CREATE TABLE ai_assistant_call_log (
  id BIGINT NOT NULL,
  assistant_message_id BIGINT NULL,
  user_id BIGINT NOT NULL,
  scenario VARCHAR(48) NOT NULL,
  provider_code VARCHAR(64) NOT NULL,
  model_code VARCHAR(96) NULL,
  success TINYINT NOT NULL,
  duration_ms INT NULL,
  error_code VARCHAR(96) NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_ai_assistant_call_log_user (user_id, created_at),
  KEY idx_ai_assistant_call_log_message (assistant_message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='assistant call log';
