CREATE INDEX idx_q_question_stem_version_lookup
  ON q_question_stem (question_version_id, deleted, sort_order, id);
