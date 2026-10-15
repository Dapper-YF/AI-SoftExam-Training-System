ALTER TABLE practice_attempt_idempotency
  DROP CHECK chk_practice_attempt_idempotency_operation,
  DROP CHECK chk_practice_attempt_idempotency_scope;

ALTER TABLE practice_attempt_idempotency
  ADD CONSTRAINT chk_practice_attempt_idempotency_operation CHECK (
    operation_code IN (
      'CREATE_ATTEMPT',
      'CREATE_CASE_ATTEMPT',
      'SAVE_ANSWER',
      'SETTLE_QUESTION',
      'SUBMIT_ATTEMPT'
    )
  ),
  ADD CONSTRAINT chk_practice_attempt_idempotency_scope CHECK (
    (
      operation_code IN ('CREATE_ATTEMPT', 'CREATE_CASE_ATTEMPT')
      AND attempt_scope_id = 0
    )
    OR (
      operation_code IN ('SAVE_ANSWER', 'SETTLE_QUESTION', 'SUBMIT_ATTEMPT')
      AND attempt_scope_id > 0
    )
  );
