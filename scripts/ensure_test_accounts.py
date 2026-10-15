"""Create test accounts student/123456 and admin/123456 with proper login identifiers."""
import pymysql, os, hashlib, time

pwd = os.environ.get("MYSQL_PASSWORD", "123123")
conn = pymysql.connect(host="127.0.0.1", port=3306, user="root", password=pwd,
                        database="ai_exam_platform", charset="utf8mb4", autocommit=True)
cur = conn.cursor()

now_ms = int(time.time() * 1000)
seq = [0]

def next_id():
    seq[0] += 1
    return now_ms * 100000 + seq[0]

def sha256(s):
    return hashlib.sha256(s.encode()).hexdigest()

# Step 1: create missing auth tables if needed
print("=== Ensuring auth tables ===")
auth_tables = {
    "sys_auth_credential": """CREATE TABLE IF NOT EXISTS sys_auth_credential (
        id BIGINT NOT NULL, user_id BIGINT NOT NULL, credential_type VARCHAR(32) NOT NULL,
        credential_subject VARCHAR(128) NULL, secret_hash VARCHAR(255) NOT NULL,
        status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE', changed_at DATETIME(3) NOT NULL,
        expires_at DATETIME(3) NULL, created_at DATETIME(3) NOT NULL,
        updated_at DATETIME(3) NOT NULL, version INT NOT NULL DEFAULT 0,
        PRIMARY KEY (id), KEY idx_credential_user (user_id, credential_type, status))""",
    "sys_auth_session": """CREATE TABLE IF NOT EXISTS sys_auth_session (
        id BIGINT NOT NULL, user_id BIGINT NOT NULL, session_token_hash VARCHAR(128) NOT NULL,
        refresh_token_hash VARCHAR(128) NULL, access_token_hash VARCHAR(128) NULL,
        client_ip VARCHAR(64) NULL, user_agent_hash VARCHAR(128) NULL,
        csrf_token_hash VARCHAR(128) NULL, issued_at DATETIME(3) NOT NULL,
        expires_at DATETIME(3) NOT NULL, last_active_at DATETIME(3) NOT NULL,
        revoked_at DATETIME(3) NULL, status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
        created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL, version INT NOT NULL DEFAULT 0,
        PRIMARY KEY (id), KEY idx_session_user (user_id, status),
        KEY idx_session_token (session_token_hash))""",
    "sys_auth_idempotency": """CREATE TABLE IF NOT EXISTS sys_auth_idempotency (
        id BIGINT NOT NULL, operation VARCHAR(64) NOT NULL, actor_scope VARCHAR(128) NOT NULL,
        request_id VARCHAR(128) NOT NULL, request_hash VARCHAR(128) NOT NULL,
        status VARCHAR(24) NOT NULL DEFAULT 'PROCESSING', error_code VARCHAR(64) NULL,
        http_status INT NULL, created_at DATETIME(3) NOT NULL, completed_at DATETIME(3) NULL,
        PRIMARY KEY (id), UNIQUE KEY uk_idempotency_replay (operation, actor_scope, request_id))""",
    "sys_auth_challenge": """CREATE TABLE IF NOT EXISTS sys_auth_challenge (
        id BIGINT NOT NULL, challenge_no VARCHAR(64) NOT NULL, purpose VARCHAR(32) NOT NULL,
        subject_ref VARCHAR(32) NOT NULL, identifier_type VARCHAR(32) NOT NULL,
        delivery_hash VARCHAR(128) NOT NULL, mobile_hmac VARCHAR(128) NULL,
        code_hash VARCHAR(255) NOT NULL, request_id VARCHAR(128) NOT NULL,
        status VARCHAR(24) NOT NULL DEFAULT 'PENDING', synthetic TINYINT NOT NULL DEFAULT 0,
        created_at DATETIME(3) NOT NULL, expires_at DATETIME(3) NOT NULL,
        consumed_at DATETIME(3) NULL, updated_at DATETIME(3) NOT NULL, version INT NOT NULL DEFAULT 0,
        idempotency_id BIGINT NULL,
        PRIMARY KEY (id), KEY idx_challenge_no (challenge_no),
        KEY idx_challenge_rate (purpose, mobile_hmac, created_at, status),
        KEY idx_challenge_idempotency (idempotency_id))""",
    "sys_login_audit": """CREATE TABLE IF NOT EXISTS sys_login_audit (
        id BIGINT NOT NULL, user_id BIGINT NULL, event_type VARCHAR(32) NOT NULL,
        identifier_hint VARCHAR(128) NULL, ip_address VARCHAR(64) NULL,
        user_agent_hash VARCHAR(128) NULL, success TINYINT NOT NULL DEFAULT 0,
        failure_reason VARCHAR(64) NULL, created_at DATETIME(3) NOT NULL,
        PRIMARY KEY (id), KEY idx_login_audit_user (user_id, created_at))""",
    "sys_auth_login_identifier": """CREATE TABLE IF NOT EXISTS sys_auth_login_identifier (
        id BIGINT NOT NULL, user_id BIGINT NOT NULL,
        identifier_type VARCHAR(32) NOT NULL COMMENT 'USERNAME/MOBILE/EMAIL',
        normalized_identifier VARCHAR(128) NOT NULL,
        verified TINYINT NOT NULL DEFAULT 0, enabled TINYINT NOT NULL DEFAULT 1,
        verified_at DATETIME(3) NULL, disabled_at DATETIME(3) NULL,
        created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL, version INT NOT NULL DEFAULT 0,
        PRIMARY KEY (id), UNIQUE KEY uk_login_identifier (identifier_type, normalized_identifier),
        KEY idx_login_identifier_user (user_id))""",
}

for table, ddl in auth_tables.items():
    cur.execute(ddl)
    print(f"  {table}: OK")

# Step 2: ensure roles exist
print("\n=== Ensuring roles ===")
roles = {"ADMIN": "管理员", "STUDENT": "考生"}
cur.execute("SELECT role_code FROM sys_role WHERE deleted=0")
existing_roles = {r[0] for r in cur.fetchall()}
for code, name in roles.items():
    if code not in existing_roles:
        cur.execute("INSERT INTO sys_role (id, role_code, role_name, status, created_at, updated_at, deleted) VALUES (%s,%s,%s,'ENABLED',NOW(3),NOW(3),0)", (next_id(), code, name))
        print(f"  Created role: {code}")
    else:
        print(f"  Role exists: {code}")

# Step 3: get role IDs
cur.execute("SELECT id, role_code FROM sys_role WHERE deleted=0")
role_map = {r[1]: r[0] for r in cur.fetchall()}
print(f"  Role IDs: {role_map}")

# Step 4: create test accounts
print("\n=== Ensuring test accounts ===")
# password "123456" - we need a proper argon2id hash. The DevTestAccountInitializer uses
# Argon2idPasswordPolicy.frozenP0() which uses specific parameters.
# For now, let's use a simple sha256 placeholder and let the initializer fix it.
# Actually, the DevTestAccountInitializer already created users with proper argon2id hash.
# We just need to add the login identifiers.

accounts = [
    ("student", "student", "测试学员", "+8613900002001", "STUDENT"),
    ("admin", "admin", "测试管理员", "+8613900002002", "ADMIN"),
]

now_sql = "NOW(3)"

for username, pwd, display, mobile, role in accounts:
    # Check if user exists
    cur.execute("SELECT id, password_hash FROM sys_user WHERE username=%s AND deleted=0", (username,))
    row = cur.fetchone()

    if row:
        uid = row[0]
        print(f"  User {username} exists, id={uid}")
    else:
        uid = next_id()
        # Use the same password hash algorithm. For dev, we insert a placeholder
        # and let DevTestAccountInitializer fix it on restart. But since we need it NOW:
        # We use the credentials from the initializer's hash
        cur.execute("""INSERT INTO sys_user (id, username, password_hash, password_changed_at, display_name, mobile, mobile_verified_at, email, status, last_login_at, failed_login_count, last_failed_login_at, locked_until, auth_version, version, created_at, updated_at, created_by, updated_by, deleted)
            VALUES (%s,%s,'PLACEHOLDER',NOW(3),%s,%s,NOW(3),NULL,'ENABLED',NULL,0,NULL,NULL,0,0,NOW(3),NOW(3),NULL,NULL,0)""",
            (uid, username, display, mobile))
        print(f"  Created user {username}, id={uid}")

    # Ensure login identifiers (USERNAME + MOBILE)
    ident_types = [
        ("USERNAME", username),
        ("MOBILE", mobile),
    ]
    for itype, ivalue in ident_types:
        cur.execute("SELECT id FROM sys_auth_login_identifier WHERE user_id=%s AND identifier_type=%s AND enabled=1", (uid, itype))
        if cur.fetchone():
            print(f"    Login identifier {itype}:{ivalue} exists")
        else:
            cur.execute("""INSERT INTO sys_auth_login_identifier (id, user_id, identifier_type, normalized_identifier, verified, enabled, verified_at, created_at, updated_at, version)
                VALUES (%s,%s,%s,%s,1,1,NOW(3),NOW(3),NOW(3),0)""",
                (next_id(), uid, itype, ivalue))
            print(f"    Created login identifier {itype}:{ivalue}")

    # Ensure role
    cur.execute("SELECT 1 FROM sys_user_role WHERE user_id=%s AND role_id=%s", (uid, role_map[role]))
    if cur.fetchone():
        print(f"    Role {role} exists")
    else:
        cur.execute("INSERT INTO sys_user_role (user_id, role_id, created_at) VALUES (%s,%s,NOW(3))", (uid, role_map[role]))
        print(f"    Added role {role}")

    # Ensure password credential - we need the ACTUAL argon2id hash.
    # Let's check if it exists first
    cur.execute("SELECT id FROM sys_auth_credential WHERE user_id=%s AND credential_type='PASSWORD' AND status='ACTIVE'", (uid,))
    if not cur.fetchone():
        # The DevTestAccountInitializer should create this on startup.
        # For now, flag it
        print(f"    WARNING: No password credential for {username} - needs DevTestAccountInitializer to run")

print("\n=== Done! ===")
print("If password credential is missing, restart backend and DevTestAccountInitializer will fix it.")
conn.close()
