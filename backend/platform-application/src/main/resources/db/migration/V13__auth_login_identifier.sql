-- PM裁决：V13 belongs to authentication login identifiers.
-- 论文 runtime 顺延 V14，案例自评持久化顺延 V15。

DROP PROCEDURE IF EXISTS auth_v13_validate_login_identifiers;

DELIMITER //
CREATE PROCEDURE auth_v13_validate_login_identifiers()
BEGIN
  DECLARE invalid_mobile_count BIGINT DEFAULT 0;
  DECLARE duplicate_mobile_count BIGINT DEFAULT 0;
  DECLARE invalid_email_count BIGINT DEFAULT 0;
  DECLARE duplicate_email_count BIGINT DEFAULT 0;

  SELECT COUNT(*)
    INTO invalid_mobile_count
    FROM sys_user
   WHERE deleted = 0
     AND mobile IS NOT NULL
     AND mobile NOT REGEXP '^\\+861[3-9][0-9]{9}$';

  SELECT COUNT(*)
    INTO duplicate_mobile_count
    FROM (
      SELECT mobile
        FROM sys_user
       WHERE deleted = 0
         AND mobile IS NOT NULL
       GROUP BY mobile
      HAVING COUNT(*) > 1
    ) duplicate_mobiles;

  SELECT COUNT(*)
    INTO invalid_email_count
    FROM sys_user
   WHERE deleted = 0
     AND email IS NOT NULL
     AND (
       TRIM(email) = ''
       OR CHAR_LENGTH(TRIM(email)) > 254
       OR CHAR_LENGTH(TRIM(email)) - CHAR_LENGTH(REPLACE(TRIM(email), '@', '')) <> 1
       OR CHAR_LENGTH(SUBSTRING_INDEX(TRIM(email), '@', 1)) > 64
       OR TRIM(email) NOT REGEXP '^[^[:space:]@]+@[^[:space:]@]+\\.[^[:space:]@]+$'
       OR SUBSTRING_INDEX(TRIM(email), '@', 1) LIKE '.%'
       OR SUBSTRING_INDEX(TRIM(email), '@', 1) LIKE '%.'
       OR SUBSTRING_INDEX(TRIM(email), '@', 1) LIKE '%..%'
       OR LOWER(SUBSTRING_INDEX(TRIM(email), '@', -1))
          NOT REGEXP '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$'
     );

  SELECT COUNT(*)
    INTO duplicate_email_count
    FROM (
      SELECT CONCAT(
               SUBSTRING_INDEX(TRIM(email), '@', 1),
               '@',
               LOWER(SUBSTRING_INDEX(TRIM(email), '@', -1))
             ) COLLATE utf8mb4_0900_bin AS normalized_email
        FROM sys_user
       WHERE deleted = 0
         AND email IS NOT NULL
       GROUP BY normalized_email
      HAVING COUNT(*) > 1
    ) duplicate_emails;

  IF invalid_mobile_count > 0 OR duplicate_mobile_count > 0
     OR invalid_email_count > 0 OR duplicate_email_count > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'AUTH_V13_LOGIN_IDENTIFIER_PRECHECK_FAILED';
  END IF;
END //
DELIMITER ;

CALL auth_v13_validate_login_identifiers();
DROP PROCEDURE auth_v13_validate_login_identifiers;

UPDATE sys_user
   SET email = CONCAT(
       SUBSTRING_INDEX(TRIM(email), '@', 1),
       '@',
       LOWER(SUBSTRING_INDEX(TRIM(email), '@', -1))
     )
 WHERE deleted = 0
   AND email IS NOT NULL;

CREATE TABLE sys_auth_login_identifier (
  id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  identifier_type VARCHAR(16) NOT NULL,
  normalized_identifier VARCHAR(320) COLLATE utf8mb4_0900_bin NOT NULL,
  verified TINYINT(1) NOT NULL DEFAULT 0,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  verified_at DATETIME(3) NULL,
  disabled_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_auth_login_identifier_type_value (identifier_type, normalized_identifier),
  UNIQUE KEY uk_auth_login_identifier_user_type (user_id, identifier_type),
  KEY idx_auth_login_identifier_user_enabled (user_id, enabled),
  KEY idx_auth_login_identifier_type_enabled (identifier_type, enabled),
  CONSTRAINT fk_auth_login_identifier_user
    FOREIGN KEY (user_id) REFERENCES sys_user(id)
    ON UPDATE RESTRICT
    ON DELETE RESTRICT,
  CONSTRAINT ck_auth_login_identifier_type CHECK (identifier_type IN ('MOBILE', 'EMAIL')),
  CONSTRAINT ck_auth_login_identifier_verified CHECK (verified IN (0, 1)),
  CONSTRAINT ck_auth_login_identifier_enabled CHECK (enabled IN (0, 1)),
  CONSTRAINT ck_auth_login_identifier_nonblank CHECK (CHAR_LENGTH(TRIM(normalized_identifier)) > 0),
  CONSTRAINT ck_auth_login_identifier_mobile CHECK (
    identifier_type <> 'MOBILE' OR normalized_identifier REGEXP '^\\+861[3-9][0-9]{9}$'
  ),
  CONSTRAINT ck_auth_login_identifier_email CHECK (
    identifier_type <> 'EMAIL'
    OR (
      CHAR_LENGTH(normalized_identifier) <= 254
      AND CHAR_LENGTH(normalized_identifier) - CHAR_LENGTH(REPLACE(normalized_identifier, '@', '')) = 1
      AND CHAR_LENGTH(SUBSTRING_INDEX(normalized_identifier, '@', 1)) <= 64
      AND normalized_identifier REGEXP '^[^[:space:]@]+@[^[:space:]@]+\\.[^[:space:]@]+$'
      AND SUBSTRING_INDEX(normalized_identifier, '@', 1) NOT LIKE '.%'
      AND SUBSTRING_INDEX(normalized_identifier, '@', 1) NOT LIKE '%.'
      AND SUBSTRING_INDEX(normalized_identifier, '@', 1) NOT LIKE '%..%'
      AND LOWER(SUBSTRING_INDEX(normalized_identifier, '@', -1))
          REGEXP '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$'
    )
  ),
  CONSTRAINT ck_auth_login_identifier_verified_at CHECK (
    (verified = 0 AND verified_at IS NULL) OR (verified = 1 AND verified_at IS NOT NULL)
  ),
  CONSTRAINT ck_auth_login_identifier_disabled_at CHECK (
    (enabled = 1 AND disabled_at IS NULL) OR (enabled = 0 AND disabled_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Canonical login identifiers. Physical deletes are restricted; disabling uses enabled=0.';

INSERT INTO sys_auth_login_identifier
  (id, user_id, identifier_type, normalized_identifier, verified, enabled,
   verified_at, disabled_at, created_at, updated_at, version)
SELECT 1300000000000000000 + ROW_NUMBER() OVER (ORDER BY id),
       id,
       'MOBILE',
       mobile,
       CASE WHEN mobile_verified_at IS NULL THEN 0 ELSE 1 END,
       CASE WHEN status = 'ENABLED' THEN 1 ELSE 0 END,
       mobile_verified_at,
       CASE WHEN status = 'ENABLED' THEN NULL ELSE updated_at END,
       created_at,
       updated_at,
       0
  FROM sys_user
 WHERE deleted = 0
   AND mobile IS NOT NULL;

INSERT INTO sys_auth_login_identifier
  (id, user_id, identifier_type, normalized_identifier, verified, enabled,
   verified_at, disabled_at, created_at, updated_at, version)
SELECT 1300000001000000000 + ROW_NUMBER() OVER (ORDER BY id),
       id,
       'EMAIL',
       email,
       0,
       CASE WHEN status = 'ENABLED' THEN 1 ELSE 0 END,
       NULL,
       CASE WHEN status = 'ENABLED' THEN NULL ELSE updated_at END,
       created_at,
       updated_at,
       0
  FROM sys_user
 WHERE deleted = 0
   AND email IS NOT NULL;
