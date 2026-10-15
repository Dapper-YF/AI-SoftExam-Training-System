ALTER TABLE exam_subject
  MODIFY COLUMN official_name VARCHAR(96) NOT NULL,
  ADD CONSTRAINT ck_exam_subject_type CHECK (subject_type IN ('COMPREHENSIVE','CASE','ESSAY','APPLICATION')),
  ADD CONSTRAINT ck_exam_subject_status CHECK (status IN ('DRAFT','ENABLED','DISABLED')),
  DROP INDEX idx_exam_subject_qualification,
  ADD KEY idx_exam_subject_tree (qualification_id,status,sort_order,id);
