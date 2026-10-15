ALTER TABLE exam_qualification
  MODIFY COLUMN domain_code VARCHAR(32) NOT NULL,
  MODIFY COLUMN official_name VARCHAR(96) NOT NULL,
  MODIFY COLUMN verification_status VARCHAR(32) NOT NULL,
  MODIFY COLUMN catalog_version VARCHAR(32) NOT NULL,
  MODIFY COLUMN catalog_status VARCHAR(24) NOT NULL,
  ADD CONSTRAINT ck_exam_qualification_domain CHECK (domain_code = 'SOFT_EXAM'),
  ADD CONSTRAINT ck_exam_qualification_level CHECK (level_code IN ('PRIMARY','INTERMEDIATE','ADVANCED')),
  ADD CONSTRAINT ck_exam_qualification_verification CHECK ((verification_status = 'OFFICIAL_VERIFIED' AND source_url IS NOT NULL AND verified_at IS NOT NULL) OR verification_status IN ('THIRD_PARTY_CANDIDATE','VERIFICATION_EXPIRED')),
  ADD CONSTRAINT ck_exam_qualification_catalog_status CHECK (catalog_status IN ('ACTIVE','RETIRED')),
  ADD CONSTRAINT ck_exam_qualification_operational_status CHECK (status IN ('DRAFT','ENABLED','DISABLED') AND NOT (catalog_status = 'RETIRED' AND status = 'ENABLED')),
  ADD KEY idx_exam_qualification_tree (domain_code,level_code,status,sort_order,id),
  ADD KEY idx_exam_qualification_verification (verification_status,verified_at);
