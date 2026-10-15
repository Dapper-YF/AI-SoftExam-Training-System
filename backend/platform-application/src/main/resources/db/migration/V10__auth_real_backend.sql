DROP PROCEDURE IF EXISTS auth_v10_validate_mobile;

DELIMITER //
CREATE PROCEDURE auth_v10_validate_mobile()
BEGIN
  DECLARE invalid_mobile_count BIGINT DEFAULT 0;
  DECLARE duplicate_mobile_count BIGINT DEFAULT 0;

  SELECT COUNT(*)
    INTO invalid_mobile_count
    FROM sys_user
   WHERE mobile IS NOT NULL
     AND mobile NOT REGEXP '^1[3-9][0-9]{9}$'
     AND mobile NOT REGEXP '^\\+861[3-9][0-9]{9}$';

  SELECT COUNT(*)
    INTO duplicate_mobile_count
    FROM (
      SELECT CASE
               WHEN mobile REGEXP '^1[3-9][0-9]{9}$' THEN CONCAT('+86', mobile)
               ELSE mobile
             END AS normalized_mobile
        FROM sys_user
       WHERE mobile IS NOT NULL
       GROUP BY normalized_mobile
      HAVING COUNT(*) > 1
    ) duplicate_mobiles;

  IF invalid_mobile_count > 0 OR duplicate_mobile_count > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'AUTH_V10_MOBILE_PRECHECK_FAILED';
  END IF;
END //
DELIMITER ;

CALL auth_v10_validate_mobile();
DROP PROCEDURE auth_v10_validate_mobile;

UPDATE sys_user
   SET mobile = CONCAT('+86', mobile)
 WHERE mobile REGEXP '^1[3-9][0-9]{9}$';

ALTER TABLE sys_user
  ADD COLUMN mobile_verified_at DATETIME(3) NULL AFTER mobile,
  ADD COLUMN password_changed_at DATETIME(3) NULL AFTER password_hash,
  ADD COLUMN failed_login_count INT NOT NULL DEFAULT 0 AFTER last_login_at,
  ADD COLUMN last_failed_login_at DATETIME(3) NULL AFTER failed_login_count,
  ADD COLUMN locked_until DATETIME(3) NULL AFTER last_failed_login_at,
  ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0 AFTER locked_until,
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER auth_version,
  ADD CONSTRAINT ck_sys_user_mobile_format CHECK (mobile IS NULL OR mobile REGEXP '^\\+861[3-9][0-9]{9}$'),
  ADD CONSTRAINT ck_sys_user_auth_status CHECK (status IN ('ENABLED', 'DISABLED')),
  ADD CONSTRAINT ck_sys_user_failed_login_count CHECK (failed_login_count >= 0),
  ADD CONSTRAINT ck_sys_user_auth_version CHECK (auth_version >= 0);

ALTER TABLE sys_user
  DROP INDEX idx_sys_user_mobile,
  ADD UNIQUE KEY uk_sys_user_mobile (mobile);

CREATE TABLE sys_auth_credential (
  id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  credential_type VARCHAR(24) NOT NULL,
  credential_subject VARCHAR(128) NOT NULL,
  secret_hash VARCHAR(255) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  active_credential_key VARCHAR(192) GENERATED ALWAYS AS (
    IF(status = 'ACTIVE', CONCAT(user_id, ':', credential_type), NULL)
  ) STORED,
  changed_at DATETIME(3) NOT NULL,
  expires_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_auth_credential_active (active_credential_key),
  KEY idx_auth_credential_user_type (user_id, credential_type, status),
  KEY idx_auth_credential_subject (credential_type, credential_subject, status),
  CONSTRAINT ck_auth_credential_type CHECK (credential_type IN ('PASSWORD')),
  CONSTRAINT ck_auth_credential_status CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED')),
  CONSTRAINT ck_auth_credential_expiry CHECK (expires_at IS NULL OR expires_at > changed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO sys_auth_credential
  (id, user_id, credential_type, credential_subject, secret_hash, status, changed_at, expires_at, created_at, updated_at, version)
SELECT id * 1000 + 10, id, 'PASSWORD', username, password_hash, 'ACTIVE', updated_at, NULL, created_at, updated_at, 0
  FROM sys_user
 WHERE deleted = 0;

CREATE TABLE sys_auth_session (
  id BIGINT NOT NULL,
  session_no VARCHAR(36) NOT NULL,
  family_id VARCHAR(36) NOT NULL,
  generation INT NOT NULL,
  parent_session_id BIGINT NULL,
  user_id BIGINT NOT NULL,
  access_token_hash CHAR(64) NOT NULL,
  refresh_token_hash CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  family_revoked_at DATETIME(3) NULL,
  family_revoke_reason VARCHAR(64) NULL,
  auth_version BIGINT NOT NULL,
  issued_at DATETIME(3) NOT NULL,
  access_expires_at DATETIME(3) NOT NULL,
  refresh_expires_at DATETIME(3) NOT NULL,
  last_used_at DATETIME(3) NULL,
  rotated_at DATETIME(3) NULL,
  revoked_at DATETIME(3) NULL,
  replayed_at DATETIME(3) NULL,
  revoke_reason VARCHAR(64) NULL,
  client_ip_hash CHAR(64) NULL,
  user_agent_summary VARCHAR(255) NULL,
  active_family_key VARCHAR(36) GENERATED ALWAYS AS (IF(status = 'ACTIVE', family_id, NULL)) STORED,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  archived_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_auth_session_no (session_no),
  UNIQUE KEY uk_auth_session_access_token_hash (access_token_hash),
  UNIQUE KEY uk_auth_session_refresh_token_hash (refresh_token_hash),
  UNIQUE KEY uk_auth_session_family_generation (family_id, generation),
  UNIQUE KEY uk_auth_session_active_family (active_family_key),
  KEY idx_auth_session_user_status_refresh_expiry (user_id, status, refresh_expires_at),
  KEY idx_auth_session_user_archive_family_generation (user_id, archived_at, family_id, generation),
  KEY idx_auth_session_family_generation (family_id, generation),
  KEY idx_auth_session_status_refresh_expiry (status, refresh_expires_at),
  KEY idx_auth_session_parent (parent_session_id),
  CONSTRAINT ck_auth_session_status CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED', 'EXPIRED', 'REPLAYED')),
  CONSTRAINT ck_auth_session_generation CHECK (generation >= 1),
  CONSTRAINT ck_auth_session_parent_generation CHECK (
    (generation = 1 AND parent_session_id IS NULL) OR (generation > 1 AND parent_session_id IS NOT NULL)
  ),
  CONSTRAINT ck_auth_session_status_times CHECK (
    (status <> 'ACTIVE' OR last_used_at IS NOT NULL)
    AND (status <> 'ROTATED' OR rotated_at IS NOT NULL)
    AND (status <> 'REVOKED' OR revoked_at IS NOT NULL)
    AND (status <> 'REPLAYED' OR replayed_at IS NOT NULL)
  ),
  CONSTRAINT ck_auth_session_family_revoke_pair CHECK (
    (family_revoked_at IS NULL AND family_revoke_reason IS NULL)
    OR (family_revoked_at IS NOT NULL AND family_revoke_reason IS NOT NULL)
  ),
  CONSTRAINT ck_auth_session_expiry_order CHECK (
    access_expires_at > issued_at AND refresh_expires_at > issued_at AND refresh_expires_at >= access_expires_at
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_auth_idempotency (
  id BIGINT NOT NULL,
  operation VARCHAR(32) NOT NULL,
  actor_scope VARCHAR(128) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  owner_token VARCHAR(64) NULL,
  execution_epoch BIGINT NOT NULL DEFAULT 0,
  lease_expires_at DATETIME(3) NULL,
  http_status SMALLINT NULL,
  response_snapshot_json JSON NULL,
  result_ref VARCHAR(128) NULL,
  error_code VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  archived_at DATETIME(3) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_auth_idempotency_scope (operation, actor_scope, request_id),
  KEY idx_auth_idempotency_status_lease (status, lease_expires_at),
  KEY idx_auth_idempotency_expiry_status (expires_at, status),
  KEY idx_auth_idempotency_result_ref (result_ref),
  CONSTRAINT ck_auth_idempotency_operation CHECK (operation IN ('REGISTER_CHALLENGE', 'REGISTER', 'PASSWORD_RESET_CHALLENGE', 'PASSWORD_RESET')),
  CONSTRAINT ck_auth_idempotency_status CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
  CONSTRAINT ck_auth_idempotency_status_payload CHECK (
    (status = 'PROCESSING' AND owner_token IS NOT NULL AND lease_expires_at IS NOT NULL AND http_status IS NULL AND response_snapshot_json IS NULL)
    OR (status = 'COMPLETED' AND http_status IS NOT NULL AND response_snapshot_json IS NOT NULL AND error_code IS NULL)
    OR (status = 'FAILED' AND http_status IS NOT NULL AND response_snapshot_json IS NOT NULL AND error_code IS NOT NULL)
  ),
  CONSTRAINT ck_auth_idempotency_lease_order CHECK (lease_expires_at IS NULL OR lease_expires_at > created_at),
  CONSTRAINT ck_auth_idempotency_expiry_order CHECK (expires_at > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_auth_challenge (
  id BIGINT NOT NULL,
  challenge_no VARCHAR(36) NOT NULL,
  idempotency_id BIGINT NOT NULL,
  purpose VARCHAR(24) NOT NULL,
  subject_ref VARCHAR(128) NULL,
  is_synthetic TINYINT(1) NOT NULL DEFAULT 0,
  mobile_hmac CHAR(64) NULL,
  masked_mobile VARCHAR(32) NULL,
  code_hash CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  delivery_status VARCHAR(24) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  consumed_at DATETIME(3) NULL,
  locked_at DATETIME(3) NULL,
  delivery_attempt_count INT NOT NULL DEFAULT 0,
  next_delivery_at DATETIME(3) NULL,
  client_ip_hash CHAR(64) NULL,
  device_fingerprint_hash CHAR(64) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_auth_challenge_no (challenge_no),
  UNIQUE KEY uk_auth_challenge_idempotency (idempotency_id),
  KEY idx_auth_challenge_mobile_purpose_status_expiry (mobile_hmac, purpose, status, expires_at),
  KEY idx_auth_challenge_delivery_next (delivery_status, next_delivery_at),
  KEY idx_auth_challenge_status_expiry (status, expires_at),
  CONSTRAINT ck_auth_challenge_purpose CHECK (purpose IN ('REGISTER', 'PASSWORD_RESET')),
  CONSTRAINT ck_auth_challenge_synthetic CHECK (is_synthetic IN (0, 1)),
  CONSTRAINT ck_auth_challenge_status CHECK (status IN ('PENDING', 'VERIFIED', 'CONSUMED', 'EXPIRED', 'LOCKED')),
  CONSTRAINT ck_auth_challenge_delivery_status CHECK (delivery_status IN ('QUEUED', 'PROCESSING', 'SENT', 'SYNTHETIC_DONE', 'FAILED')),
  CONSTRAINT ck_auth_challenge_attempt_count CHECK (attempt_count >= 0 AND attempt_count <= 5),
  CONSTRAINT ck_auth_challenge_delivery_attempt_count CHECK (delivery_attempt_count >= 0),
  CONSTRAINT ck_auth_challenge_expiry_order CHECK (expires_at > created_at),
  CONSTRAINT ck_auth_challenge_consumed_status CHECK (consumed_at IS NULL OR status IN ('CONSUMED', 'LOCKED')),
  CONSTRAINT ck_auth_challenge_locked_status CHECK (locked_at IS NULL OR status = 'LOCKED'),
  CONSTRAINT ck_auth_challenge_delivery_synthetic CHECK (
    (is_synthetic = 0 AND delivery_status <> 'SYNTHETIC_DONE')
    OR (is_synthetic = 1 AND delivery_status IN ('SYNTHETIC_DONE', 'FAILED'))
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_login_audit (
  id BIGINT NOT NULL,
  event_no VARCHAR(36) NOT NULL,
  user_id BIGINT NULL,
  session_id BIGINT NULL,
  idempotency_id BIGINT NULL,
  event_type VARCHAR(32) NOT NULL,
  outcome VARCHAR(16) NOT NULL,
  reason_code VARCHAR(64) NULL,
  identifier_hash CHAR(64) NULL,
  masked_mobile VARCHAR(32) NULL,
  client_ip_hash CHAR(64) NULL,
  user_agent_summary VARCHAR(255) NULL,
  trace_id VARCHAR(64) NULL,
  occurred_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_login_audit_event_no (event_no),
  KEY idx_login_audit_user_time (user_id, occurred_at),
  KEY idx_login_audit_session_time (session_id, occurred_at),
  KEY idx_login_audit_outcome_time (outcome, occurred_at),
  CONSTRAINT ck_login_audit_event_type CHECK (event_type IN ('REGISTER', 'LOGIN', 'REFRESH', 'LOGOUT', 'PASSWORD_RESET')),
  CONSTRAINT ck_login_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
