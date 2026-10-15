ALTER TABLE exam_rule
  ADD COLUMN period_code VARCHAR(32) NULL AFTER subject_id,
  ADD COLUMN effective_from DATETIME(3) NULL AFTER effective_at,
  ADD COLUMN effective_to DATETIME(3) NULL AFTER effective_from,
  ADD COLUMN published_at DATETIME(3) NULL AFTER effective_to,
  ADD COLUMN version BIGINT NULL AFTER published_at;
