-- Fail closed before permanent DDL when an event references a missing session.
CREATE TEMPORARY TABLE flyway_v7_exam_event_orphan_guard (
  orphan_count BIGINT NOT NULL,
  CONSTRAINT chk_v7_exam_event_no_orphans CHECK (orphan_count = 0)
);

INSERT INTO flyway_v7_exam_event_orphan_guard (orphan_count)
SELECT COUNT(*)
FROM practice_exam_event event_row
LEFT JOIN practice_exam_session session_row ON session_row.id = event_row.session_id
WHERE session_row.id IS NULL;

DROP TEMPORARY TABLE flyway_v7_exam_event_orphan_guard;

ALTER TABLE practice_exam_session
  ADD COLUMN next_event_sequence BIGINT NOT NULL DEFAULT 1 AFTER version,
  ADD CONSTRAINT chk_exam_session_next_event_sequence
    CHECK (next_event_sequence >= 1);

-- Keep the column nullable only for the deterministic historical backfill.
ALTER TABLE practice_exam_event
  ADD COLUMN event_sequence BIGINT NULL AFTER session_subject_id;

UPDATE practice_exam_event event_row
JOIN (
  SELECT
    id,
    ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY occurred_at, id) AS allocated_sequence
  FROM practice_exam_event
) ranked_event ON ranked_event.id = event_row.id
SET event_row.event_sequence = ranked_event.allocated_sequence;

UPDATE practice_exam_session session_row
LEFT JOIN (
  SELECT
    session_id,
    COALESCE(MAX(event_sequence), 0) + 1 AS next_event_sequence
  FROM practice_exam_event
  GROUP BY session_id
) event_counter ON event_counter.session_id = session_row.id
SET session_row.next_event_sequence = COALESCE(event_counter.next_event_sequence, 1);

-- Validate the completed backfill before enforcing the final schema.
CREATE TEMPORARY TABLE flyway_v7_exam_event_backfill_guard (
  null_count BIGINT NOT NULL,
  non_positive_count BIGINT NOT NULL,
  duplicate_count BIGINT NOT NULL,
  CONSTRAINT chk_v7_exam_event_backfill_valid CHECK (
    null_count = 0
    AND non_positive_count = 0
    AND duplicate_count = 0
  )
);

INSERT INTO flyway_v7_exam_event_backfill_guard (
  null_count,
  non_positive_count,
  duplicate_count
)
SELECT
  COALESCE(SUM(CASE WHEN event_sequence IS NULL THEN 1 ELSE 0 END), 0),
  COALESCE(SUM(CASE WHEN event_sequence < 1 THEN 1 ELSE 0 END), 0),
  (
    SELECT COUNT(*)
    FROM (
      SELECT session_id, event_sequence
      FROM practice_exam_event
      GROUP BY session_id, event_sequence
      HAVING COUNT(*) > 1
    ) duplicated_sequence
  )
FROM practice_exam_event;

DROP TEMPORARY TABLE flyway_v7_exam_event_backfill_guard;

ALTER TABLE practice_exam_event
  MODIFY COLUMN event_sequence BIGINT NOT NULL,
  ADD UNIQUE KEY uk_exam_event_session_sequence (session_id, event_sequence),
  ADD KEY idx_exam_event_subject_sequence (session_subject_id, event_sequence);
