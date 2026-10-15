ALTER TABLE sys_auth_idempotency
  DROP CHECK ck_auth_idempotency_operation,
  ADD CONSTRAINT ck_auth_idempotency_operation CHECK (
    operation IN ('REGISTER_CHALLENGE', 'REGISTER', 'PASSWORD_RESET_CHALLENGE', 'PASSWORD_RESET', 'SMS_LOGIN_CHALLENGE')
  );

ALTER TABLE sys_auth_challenge
  DROP CHECK ck_auth_challenge_purpose,
  ADD CONSTRAINT ck_auth_challenge_purpose CHECK (
    purpose IN ('REGISTER', 'PASSWORD_RESET', 'SMS_LOGIN')
  );
