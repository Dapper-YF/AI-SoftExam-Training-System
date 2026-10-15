ALTER TABLE exam_qualification
  ADD COLUMN domain_code VARCHAR(32) NULL AFTER qualification_code,
  ADD COLUMN official_name VARCHAR(96) NULL AFTER qualification_name,
  ADD COLUMN source_url VARCHAR(512) NULL AFTER level_code,
  ADD COLUMN verified_at DATETIME(3) NULL AFTER source_url,
  ADD COLUMN verification_status VARCHAR(32) NULL AFTER verified_at,
  ADD COLUMN catalog_version VARCHAR(32) NULL AFTER verification_status,
  ADD COLUMN catalog_status VARCHAR(24) NULL AFTER catalog_version;
