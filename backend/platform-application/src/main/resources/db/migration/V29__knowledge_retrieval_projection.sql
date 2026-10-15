CREATE TABLE knowledge_source (
  id BIGINT NOT NULL,
  source_code VARCHAR(128) NOT NULL,
  source_name VARCHAR(255) NOT NULL,
  source_kind VARCHAR(32) NOT NULL COMMENT 'OCR_TEXTBOOK/MARKDOWN_CARD/QUESTION_BANK',
  markdown_path VARCHAR(512) NOT NULL,
  content_hash CHAR(64) NOT NULL,
  quality_status VARCHAR(24) NOT NULL DEFAULT 'NEEDS_REVIEW',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_knowledge_source_code (source_code),
  KEY idx_knowledge_source_quality (quality_status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='knowledge markdown source projection';

ALTER TABLE knowledge_document
  ADD COLUMN source_id BIGINT NULL AFTER subject_id,
  ADD COLUMN markdown_path VARCHAR(512) NULL AFTER source_url,
  ADD KEY idx_knowledge_document_source (source_id, status);

ALTER TABLE knowledge_fragment
  ADD COLUMN fragment_key VARCHAR(191) NULL AFTER id,
  ADD COLUMN knowledge_code VARCHAR(128) NULL AFTER knowledge_id,
  ADD COLUMN concept_code VARCHAR(128) NULL AFTER knowledge_code,
  ADD COLUMN content_kind VARCHAR(24) NULL COMMENT 'CARD/SOURCE' AFTER concept_code,
  ADD COLUMN heading_path JSON NULL AFTER content_kind,
  ADD COLUMN source_pages VARCHAR(128) NULL AFTER heading_path,
  ADD COLUMN source_confidence DECIMAL(6,4) NULL AFTER source_pages,
  ADD COLUMN quality_status VARCHAR(24) NOT NULL DEFAULT 'NEEDS_REVIEW' AFTER source_confidence,
  ADD COLUMN embedding_model VARCHAR(128) NULL AFTER index_status,
  ADD COLUMN embedding_revision VARCHAR(128) NULL AFTER embedding_model,
  ADD COLUMN embedding_version VARCHAR(64) NULL AFTER embedding_revision,
  ADD COLUMN embedding_dimension INT NULL AFTER embedding_version,
  ADD COLUMN qdrant_point_id VARCHAR(191) NULL AFTER embedding_dimension,
  ADD COLUMN embedded_at DATETIME(3) NULL AFTER qdrant_point_id,
  ADD COLUMN invalidated_at DATETIME(3) NULL AFTER embedded_at,
  ADD UNIQUE KEY uk_knowledge_fragment_key (fragment_key),
  ADD KEY idx_knowledge_fragment_published_recall (status, quality_status, content_kind, knowledge_code, concept_code, invalidated_at),
  ADD KEY idx_knowledge_fragment_vector_state (status, index_status, embedding_version, invalidated_at);

CREATE TABLE knowledge_fragment_binding (
  id BIGINT NOT NULL,
  fragment_id BIGINT NOT NULL,
  binding_type VARCHAR(32) NOT NULL COMMENT 'QUESTION/QUALIFICATION/SUBJECT/CHAPTER/TAG',
  binding_id VARCHAR(128) NOT NULL,
  binding_role VARCHAR(32) NOT NULL DEFAULT 'PRIMARY',
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_knowledge_fragment_binding (fragment_id, binding_type, binding_id, binding_role),
  KEY idx_knowledge_fragment_binding_lookup (binding_type, binding_id, fragment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='knowledge fragment binding';

CREATE TABLE knowledge_review_record (
  id BIGINT NOT NULL,
  fragment_id BIGINT NOT NULL,
  review_status VARCHAR(24) NOT NULL COMMENT 'REVIEWED/REJECTED',
  reviewer_id BIGINT NULL,
  review_note VARCHAR(2000) NULL,
  source_verified TINYINT NOT NULL DEFAULT 0,
  retrieval_verified TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_knowledge_review_fragment (fragment_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='knowledge review audit';

CREATE TABLE knowledge_publish_version (
  id BIGINT NOT NULL,
  release_code VARCHAR(128) NOT NULL,
  embedding_model VARCHAR(128) NOT NULL,
  embedding_revision VARCHAR(128) NOT NULL,
  embedding_version VARCHAR(64) NOT NULL,
  qdrant_collection VARCHAR(191) NOT NULL,
  manifest_hash CHAR(64) NOT NULL,
  retrieval_report_json JSON NOT NULL,
  published_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_knowledge_publish_release (release_code),
  KEY idx_knowledge_publish_time (published_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='knowledge release version';
