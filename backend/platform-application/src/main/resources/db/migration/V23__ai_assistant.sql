CREATE TABLE ai_assistant_session (
  id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  qualification_id BIGINT NULL,
  subject_id BIGINT NULL,
  question_id BIGINT NULL,
  title VARCHAR(160) NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_ai_assistant_session_user (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='assistant session';

CREATE TABLE ai_assistant_message (
  id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  role_code VARCHAR(16) NOT NULL COMMENT 'USER/ASSISTANT',
  content LONGTEXT NOT NULL,
  answer_status VARCHAR(24) NULL COMMENT 'ANSWERED/INSUFFICIENT_EVIDENCE',
  cache_key CHAR(64) NULL,
  model_code VARCHAR(96) NULL,
  prompt_version VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_ai_assistant_message_session (session_id, created_at),
  KEY idx_ai_assistant_message_cache (cache_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='assistant message';

CREATE TABLE ai_assistant_attachment (
  id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  storage_key VARCHAR(500) NOT NULL,
  original_name VARCHAR(255) NOT NULL,
  content_type VARCHAR(128) NOT NULL,
  file_size BIGINT NOT NULL,
  ocr_text LONGTEXT NULL,
  ocr_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  detected_question_type VARCHAR(32) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_ai_assistant_attachment_session (session_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='attachment';

CREATE TABLE knowledge_document (
  id BIGINT NOT NULL,
  content_domain VARCHAR(32) NOT NULL COMMENT 'PRACTICE/ESSAY',
  qualification_id BIGINT NOT NULL,
  subject_id BIGINT NULL,
  document_code VARCHAR(96) NOT NULL,
  title VARCHAR(255) NOT NULL,
  source_name VARCHAR(255) NOT NULL,
  source_url VARCHAR(1000) NULL,
  copyright_basis VARCHAR(500) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  current_version INT NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_knowledge_document_code (content_domain, document_code),
  KEY idx_knowledge_document_scope (content_domain, qualification_id, subject_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='knowledge document';

CREATE TABLE knowledge_fragment (
  id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,
  fragment_no INT NOT NULL,
  knowledge_type VARCHAR(32) NULL,
  knowledge_id BIGINT NULL,
  content LONGTEXT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_knowledge_fragment_no (document_id, fragment_no),
  KEY idx_knowledge_fragment_recall (document_id, status, knowledge_type, knowledge_id),
  FULLTEXT KEY ft_knowledge_fragment_content (content) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='knowledge fragment';

CREATE TABLE ai_assistant_citation (
  id BIGINT NOT NULL,
  assistant_message_id BIGINT NOT NULL,
  fragment_id BIGINT NOT NULL,
  rank_no INT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_ai_assistant_citation_message (assistant_message_id, rank_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='knowledge document';

CREATE TABLE ai_assistant_feedback (
  id BIGINT NOT NULL,
  message_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  feedback_code VARCHAR(24) NOT NULL COMMENT 'HELPFUL/NOT_HELPFUL/REPORT',
  reason VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_assistant_feedback_user_message (message_id, user_id),
  KEY idx_ai_assistant_feedback_code (feedback_code, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='assistant feedback';
