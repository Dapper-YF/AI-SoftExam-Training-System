ALTER TABLE q_question_import_item
  DROP CHECK ck_q_import_item_status,
  ADD CONSTRAINT ck_q_import_item_status
    CHECK (item_status IN ('SKIPPED_INELIGIBLE', 'IMPORTED_DRAFT', 'PUBLISHED', 'ROLLED_BACK'));
