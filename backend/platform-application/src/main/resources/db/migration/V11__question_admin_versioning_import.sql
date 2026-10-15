-- W1 question administration/versioning/import persistence.
-- PM裁决：V11 belongs to question administration; essay runtime moves to V13 after DDL freeze.

-- MySQL DDL auto-commits. Reject legacy orphans before the first permanent ALTER so a
-- failed backfill cannot leave a half-migrated schema.
CREATE TEMPORARY TABLE flyway_v11_question_orphan_guard (
  stem_orphans BIGINT NOT NULL,
  sub_question_orphans BIGINT NOT NULL,
  option_orphans BIGINT NOT NULL,
  answer_orphans BIGINT NOT NULL,
  solution_orphans BIGINT NOT NULL,
  rule_orphans BIGINT NOT NULL,
  binding_orphans BIGINT NOT NULL,
  CONSTRAINT ck_v11_question_no_legacy_orphans CHECK (
    stem_orphans = 0 AND sub_question_orphans = 0 AND option_orphans = 0
    AND answer_orphans = 0 AND solution_orphans = 0 AND rule_orphans = 0
    AND binding_orphans = 0
  )
);

INSERT INTO flyway_v11_question_orphan_guard
SELECT
  (SELECT COUNT(*) FROM q_question_stem c LEFT JOIN q_question p ON p.id=c.question_id WHERE p.id IS NULL),
  (SELECT COUNT(*) FROM q_sub_question c LEFT JOIN q_question p ON p.id=c.question_id WHERE p.id IS NULL),
  (SELECT COUNT(*) FROM q_choice_option c LEFT JOIN q_sub_question p ON p.id=c.sub_question_id WHERE p.id IS NULL),
  (SELECT COUNT(*) FROM q_answer_key c LEFT JOIN q_sub_question p ON p.id=c.sub_question_id WHERE p.id IS NULL),
  (SELECT COUNT(*) FROM q_solution c LEFT JOIN q_question p ON p.id=c.question_id WHERE p.id IS NULL),
  (SELECT COUNT(*) FROM q_scoring_rule c LEFT JOIN q_sub_question p ON p.id=c.sub_question_id WHERE p.id IS NULL),
  (SELECT COUNT(*) FROM q_question_knowledge_binding c LEFT JOIN q_question p ON p.id=c.question_id WHERE p.id IS NULL);

DROP TEMPORARY TABLE flyway_v11_question_orphan_guard;

ALTER TABLE q_question
  MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT,
  ADD COLUMN current_working_version_id BIGINT NULL AFTER content_version,
  ADD COLUMN current_published_version_id BIGINT NULL AFTER current_working_version_id,
  ADD COLUMN next_event_sequence BIGINT NOT NULL DEFAULT 1 AFTER current_published_version_id,
  ADD COLUMN import_external_key VARCHAR(160) NULL AFTER next_event_sequence,
  ADD COLUMN import_content_hash CHAR(64) NULL AFTER import_external_key,
  ADD UNIQUE KEY uk_q_question_import_external
    (qualification_id, subject_id, import_external_key),
  ADD UNIQUE KEY uk_q_question_import_hash
    (qualification_id, subject_id, import_content_hash);

CREATE TABLE q_question_version (
  id BIGINT NOT NULL AUTO_INCREMENT,
  question_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  external_key VARCHAR(160) NULL,
  title VARCHAR(180) NULL,
  year_no INT NULL,
  difficulty VARCHAR(24) NULL,
  source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
  source_ref VARCHAR(500) NULL,
  source_file VARCHAR(500) NULL,
  source_sha256 CHAR(64) NULL,
  source_locator VARCHAR(1000) NULL,
  original_question_no INT NULL,
  paper_kind VARCHAR(24) NULL,
  syllabus_basis VARCHAR(160) NULL,
  copyright_status VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED',
  usage_restriction VARCHAR(255) NOT NULL DEFAULT 'INTERNAL_ONLY',
  content_hash CHAR(64) NOT NULL,
  content_json JSON NOT NULL,
  import_batch_id BIGINT NULL,
  author_id BIGINT NULL,
  submitted_by BIGINT NULL,
  submitted_at DATETIME(3) NULL,
  reviewed_by BIGINT NULL,
  reviewed_at DATETIME(3) NULL,
  review_decision VARCHAR(16) NULL,
  published_by BIGINT NULL,
  published_at DATETIME(3) NULL,
  retired_by BIGINT NULL,
  retired_at DATETIME(3) NULL,
  row_version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted TINYINT NOT NULL DEFAULT 0,
  working_slot TINYINT GENERATED ALWAYS AS (
    CASE WHEN deleted = 0 AND status IN ('DRAFT','IN_REVIEW') THEN 1 ELSE NULL END
  ) STORED,
  published_slot TINYINT GENERATED ALWAYS AS (
    CASE WHEN deleted = 0 AND status = 'PUBLISHED' THEN 1 ELSE NULL END
  ) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_q_question_version_no (question_id, version_no),
  UNIQUE KEY uk_q_question_working_slot (question_id, working_slot),
  UNIQUE KEY uk_q_question_published_slot (question_id, published_slot),
  KEY idx_q_question_version_status (status, updated_at, id),
  KEY idx_q_question_version_import (import_batch_id, id),
  KEY idx_q_question_version_external (external_key),
  KEY idx_q_question_version_provenance
    (paper_kind, year_no, copyright_status, source_sha256),
  CONSTRAINT ck_q_question_version_status
    CHECK (status IN ('DRAFT','IN_REVIEW','PUBLISHED','RETIRED')),
  CONSTRAINT ck_q_question_version_review
    CHECK (review_decision IS NULL OR review_decision IN ('APPROVED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Immutable question content versions and workflow state';

-- Existing installations are upgraded without fabricating rights authorization.
INSERT INTO q_question_version (
  question_id, version_no, status, external_key, title, year_no, difficulty,
  source_type, source_ref, source_file, source_sha256, source_locator,
  original_question_no, paper_kind, syllabus_basis, copyright_status, usage_restriction,
  content_hash, content_json, author_id, published_by, published_at,
  row_version, created_at, updated_at, deleted
)
SELECT
  q.id,
  GREATEST(q.content_version, 1),
  CASE WHEN q.status = 'PUBLISHED' THEN 'PUBLISHED'
       WHEN q.status IN ('ARCHIVED', 'RETIRED') THEN 'RETIRED'
       WHEN q.status IN ('REVIEWING', 'IN_REVIEW') THEN 'IN_REVIEW'
       ELSE 'DRAFT' END,
  CONCAT('LEGACY-', q.id),
  q.title,
  q.year_no,
  q.difficulty,
  COALESCE(q.source_type, 'MANUAL'),
  q.source_ref,
  NULL,
  NULL,
  NULL,
  NULL,
  NULL,
  'LEGACY_UNVERIFIED',
  'UNVERIFIED',
  'INTERNAL_ONLY',
  LOWER(SHA2(CONCAT_WS('|', q.id, q.content_version, COALESCE(q.title, ''),
    COALESCE(q.updated_at, q.created_at)), 256)),
  JSON_OBJECT('legacyQuestionId', q.id, 'migration', 'V11'),
  COALESCE(q.created_by, q.updated_by),
  CASE WHEN q.status = 'PUBLISHED' THEN COALESCE(q.updated_by, q.created_by) ELSE NULL END,
  CASE WHEN q.status = 'PUBLISHED' THEN q.updated_at ELSE NULL END,
  0,
  q.created_at,
  q.updated_at,
  q.deleted
FROM q_question q;

UPDATE q_question q
JOIN q_question_version qv ON qv.question_id = q.id
SET q.current_working_version_id = CASE
      WHEN qv.status IN ('DRAFT','IN_REVIEW') AND qv.deleted = 0 THEN qv.id ELSE NULL END,
    q.current_published_version_id = CASE
      WHEN qv.status = 'PUBLISHED' AND qv.deleted = 0 THEN qv.id ELSE NULL END,
    q.status = qv.status;

ALTER TABLE q_question_stem
  MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT,
  ADD COLUMN question_version_id BIGINT NULL AFTER question_id,
  ADD COLUMN stem_key VARCHAR(64) NULL AFTER question_version_id,
  ADD COLUMN resource_id BIGINT NULL AFTER attachment_url;

ALTER TABLE q_sub_question
  MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT,
  ADD COLUMN question_version_id BIGINT NULL AFTER question_id,
  DROP INDEX uk_q_sub_question_no,
  ADD UNIQUE KEY uk_q_sub_question_version_no (question_version_id, sub_question_no),
  ADD KEY idx_q_sub_question_question_version (question_id, question_version_id, sort_order);

ALTER TABLE q_choice_option MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE q_answer_key MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT;

ALTER TABLE q_solution
  MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT,
  ADD COLUMN question_version_id BIGINT NULL AFTER question_id,
  ADD COLUMN sort_order INT NOT NULL DEFAULT 1 AFTER source_type;

ALTER TABLE q_scoring_rule
  MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT,
  ADD COLUMN sort_order INT NOT NULL DEFAULT 1 AFTER rule_json;

ALTER TABLE q_question_knowledge_binding
  MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT,
  ADD COLUMN question_version_id BIGINT NULL AFTER question_id,
  ADD COLUMN knowledge_code VARCHAR(96) NULL AFTER knowledge_id,
  MODIFY COLUMN weight DECIMAL(6,3) NULL,
  ADD COLUMN sort_order INT NOT NULL DEFAULT 1 AFTER weight,
  DROP INDEX uk_q_knowledge_binding,
  ADD UNIQUE KEY uk_q_knowledge_binding_version
    (question_version_id, target_type, target_id, knowledge_type, knowledge_id),
  ADD KEY idx_q_knowledge_binding_version (question_version_id, id);

UPDATE q_question_stem s
JOIN q_question_version qv ON qv.question_id = s.question_id
SET s.question_version_id = qv.id,
    s.stem_key = COALESCE(s.stem_key, CONCAT('LEGACY-', s.id));

UPDATE q_sub_question sq
JOIN q_question_version qv ON qv.question_id = sq.question_id
SET sq.question_version_id = qv.id;

UPDATE q_solution sol
JOIN q_question_version qv ON qv.question_id = sol.question_id
SET sol.question_version_id = qv.id;

UPDATE q_question_knowledge_binding kb
JOIN q_question_version qv ON qv.question_id = kb.question_id
SET kb.question_version_id = qv.id;

CREATE TEMPORARY TABLE flyway_v11_question_backfill_guard (
  stem_invalid BIGINT NOT NULL,
  sub_question_invalid BIGINT NOT NULL,
  solution_invalid BIGINT NOT NULL,
  binding_invalid BIGINT NOT NULL,
  CONSTRAINT ck_v11_question_backfill_valid CHECK (
    stem_invalid = 0 AND sub_question_invalid = 0
    AND solution_invalid = 0 AND binding_invalid = 0
  )
);

INSERT INTO flyway_v11_question_backfill_guard
SELECT
  (SELECT COUNT(*) FROM q_question_stem c LEFT JOIN q_question_version v ON v.id=c.question_version_id
     WHERE v.id IS NULL OR v.question_id <> c.question_id),
  (SELECT COUNT(*) FROM q_sub_question c LEFT JOIN q_question_version v ON v.id=c.question_version_id
     WHERE v.id IS NULL OR v.question_id <> c.question_id),
  (SELECT COUNT(*) FROM q_solution c LEFT JOIN q_question_version v ON v.id=c.question_version_id
     WHERE v.id IS NULL OR v.question_id <> c.question_id),
  (SELECT COUNT(*) FROM q_question_knowledge_binding c LEFT JOIN q_question_version v ON v.id=c.question_version_id
     WHERE v.id IS NULL OR v.question_id <> c.question_id);

DROP TEMPORARY TABLE flyway_v11_question_backfill_guard;

ALTER TABLE q_question_stem MODIFY COLUMN question_version_id BIGINT NOT NULL;
ALTER TABLE q_sub_question MODIFY COLUMN question_version_id BIGINT NOT NULL;
ALTER TABLE q_solution MODIFY COLUMN question_version_id BIGINT NOT NULL;
ALTER TABLE q_question_knowledge_binding MODIFY COLUMN question_version_id BIGINT NOT NULL;

CREATE TABLE q_scoring_point (
  id BIGINT NOT NULL AUTO_INCREMENT,
  question_version_id BIGINT NOT NULL,
  sub_question_id BIGINT NOT NULL,
  point_code VARCHAR(64) NOT NULL,
  point_text TEXT NOT NULL,
  score DECIMAL(8,2) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT NOT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_q_scoring_point_code (question_version_id, sub_question_id, point_code),
  KEY idx_q_scoring_point_sub (sub_question_id, sort_order),
  CONSTRAINT ck_q_scoring_point_score CHECK (score > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE q_question_review (
  id BIGINT NOT NULL AUTO_INCREMENT,
  question_id BIGINT NOT NULL,
  question_version_id BIGINT NOT NULL,
  submitted_by BIGINT NOT NULL,
  reviewer_id BIGINT NOT NULL,
  decision VARCHAR(16) NOT NULL,
  reason VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_q_review_version (question_version_id, created_at),
  CONSTRAINT ck_q_review_decision CHECK (decision IN ('APPROVED','REJECTED')),
  CONSTRAINT ck_q_review_no_self CHECK (reviewer_id <> submitted_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE q_question_audit_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  question_id BIGINT NOT NULL,
  question_version_id BIGINT NULL,
  event_sequence BIGINT NOT NULL,
  event_type VARCHAR(48) NOT NULL,
  actor_user_id BIGINT NOT NULL,
  client_mutation_id CHAR(36) NOT NULL,
  event_payload_json JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_q_audit_sequence (question_id, event_sequence),
  KEY idx_q_audit_version (question_version_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE q_question_mutation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  actor_user_id BIGINT NOT NULL,
  operation_code VARCHAR(48) NOT NULL,
  target_scope_id VARCHAR(160) NOT NULL,
  client_mutation_id CHAR(36) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  response_json JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_q_mutation_request
    (actor_user_id, operation_code, target_scope_id, client_mutation_id),
  CONSTRAINT ck_q_mutation_status CHECK (status IN ('PROCESSING','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE q_question_import_batch (
  id BIGINT NOT NULL AUTO_INCREMENT,
  actor_user_id BIGINT NOT NULL,
  client_mutation_id CHAR(36) NOT NULL,
  input_path VARCHAR(1000) NOT NULL,
  input_sha256 CHAR(64) NOT NULL,
  schema_version VARCHAR(48) NOT NULL,
  rights_status VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED',
  usage_restriction VARCHAR(255) NOT NULL DEFAULT 'INTERNAL_ONLY',
  status VARCHAR(24) NOT NULL,
  total_count INT NOT NULL,
  eligible_count INT NOT NULL,
  imported_count INT NOT NULL DEFAULT 0,
  row_version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at DATETIME(3) NULL,
  rolled_back_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_q_import_confirm (actor_user_id, client_mutation_id),
  KEY idx_q_import_status (status, created_at),
  CONSTRAINT ck_q_import_status CHECK (status IN ('CONFIRMED','ROLLED_BACK')),
  CONSTRAINT ck_q_import_rights CHECK (rights_status = 'UNVERIFIED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE q_question_import_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  import_batch_id BIGINT NOT NULL,
  line_no INT NOT NULL,
  external_key VARCHAR(160) NOT NULL,
  content_hash CHAR(64) NOT NULL,
  import_eligible TINYINT NOT NULL,
  item_status VARCHAR(24) NOT NULL,
  question_id BIGINT NULL,
  question_version_id BIGINT NULL,
  canonical_json JSON NOT NULL,
  error_code VARCHAR(96) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_q_import_item_line (import_batch_id, line_no),
  UNIQUE KEY uk_q_import_item_external (import_batch_id, external_key),
  KEY idx_q_import_item_question (question_id, question_version_id),
  CONSTRAINT ck_q_import_item_status
    CHECK (item_status IN ('SKIPPED_INELIGIBLE','IMPORTED_DRAFT','ROLLED_BACK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
