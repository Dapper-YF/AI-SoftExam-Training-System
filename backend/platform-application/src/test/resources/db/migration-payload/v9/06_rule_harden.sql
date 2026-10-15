ALTER TABLE exam_rule
  MODIFY COLUMN period_code VARCHAR(32) NOT NULL,
  MODIFY COLUMN effective_from DATETIME(3) NOT NULL,
  MODIFY COLUMN effective_to DATETIME(3) NOT NULL,
  MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_exam_rule_subject_scope CHECK (subject_id IS NULL),
  ADD CONSTRAINT ck_exam_rule_status CHECK (status IN ('DRAFT','PUBLISHED','RETIRED')),
  ADD CONSTRAINT ck_exam_rule_effective_range CHECK (effective_from < effective_to),
  DROP INDEX uk_exam_rule_version,
  DROP INDEX idx_exam_rule_subject,
  ADD UNIQUE KEY uk_exam_rule_period_version (qualification_id,period_code,rule_code,rule_version),
  ADD KEY idx_exam_rule_active (qualification_id,period_code,status,effective_from,effective_to);
