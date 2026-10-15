ALTER TABLE q_question_import_batch
  ADD COLUMN paper_id BIGINT NULL AFTER client_mutation_id,
  ADD COLUMN source_file_name VARCHAR(255) NULL AFTER input_sha256,
  ADD COLUMN source_file_sha256 CHAR(64) NULL AFTER source_file_name,
  ADD COLUMN reviewed_by BIGINT NULL AFTER row_version,
  ADD COLUMN reviewed_at DATETIME(3) NULL AFTER reviewed_by,
  ADD COLUMN review_note VARCHAR(1000) NULL AFTER reviewed_at,
  ADD COLUMN published_at DATETIME(3) NULL AFTER completed_at,
  ADD UNIQUE KEY uk_q_import_paper (paper_id);

ALTER TABLE q_question_import_batch
  DROP CHECK ck_q_import_status;

UPDATE q_question_import_batch batch
SET batch.status = CASE
  WHEN batch.status = 'ROLLED_BACK' THEN 'ROLLED_BACK'
  WHEN EXISTS (
    SELECT 1
    FROM q_question_import_item item
    WHERE item.import_batch_id = batch.id
      AND item.item_status = 'PUBLISHED'
  ) AND NOT EXISTS (
    SELECT 1
    FROM q_question_import_item item
    WHERE item.import_batch_id = batch.id
      AND item.item_status = 'IMPORTED_DRAFT'
  ) THEN 'PUBLISHED'
  ELSE 'PENDING_REVIEW'
END;

ALTER TABLE q_question_import_batch
  ADD CONSTRAINT ck_q_import_status
    CHECK (status IN ('PENDING_REVIEW', 'PUBLISHED', 'REJECTED', 'ROLLED_BACK'));

CREATE TABLE q_question_import_asset (
  id BIGINT NOT NULL AUTO_INCREMENT,
  import_batch_id BIGINT NOT NULL,
  source_relation_id VARCHAR(160) NOT NULL,
  source_order INT NOT NULL,
  content_sha256 CHAR(64) NOT NULL,
  stored_file_name VARCHAR(64) NOT NULL,
  media_type VARCHAR(80) NOT NULL,
  byte_size BIGINT NOT NULL,
  width_px INT NULL,
  height_px INT NULL,
  bound_question_no INT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_q_import_asset_relation (import_batch_id, source_relation_id),
  KEY idx_q_import_asset_hash (content_sha256),
  KEY idx_q_import_asset_question (import_batch_id, bound_question_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Word题卷导入图片及其题目绑定';
