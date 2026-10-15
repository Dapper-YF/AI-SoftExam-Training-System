"""Copy password hash from student account to new accounts."""
import pymysql, os, time

pwd = os.environ.get("MYSQL_PASSWORD", "123123")
conn = pymysql.connect(host="127.0.0.1", port=3306, user="root", password=pwd,
                        database="ai_exam_platform", autocommit=True)
cur = conn.cursor()

# Get student's password hash and credential
cur.execute("SELECT u.password_hash FROM sys_user u WHERE u.username='student' AND u.deleted=0")
student_hash = cur.fetchone()
if not student_hash:
    print("student account not found!")
    exit(1)

hash_val = student_hash[0]
print(f"student password_hash: {hash_val[:40]}...")

cur.execute("SELECT secret_hash FROM sys_auth_credential WHERE user_id=(SELECT id FROM sys_user WHERE username='student') AND credential_type='PASSWORD' AND status='ACTIVE'")
cred = cur.fetchone()
cred_hash = cred[0] if cred else None
print(f"student credential secret_hash: {cred_hash[:40] if cred_hash else 'NONE'}...")

# Fix new accounts
for username in ["12312312312", "12121212121"]:
    cur.execute("SELECT id FROM sys_user WHERE username=%s AND deleted=0", (username,))
    row = cur.fetchone()
    if not row:
        print(f"{username}: not found")
        continue
    uid = row[0]

    # Update password hash
    cur.execute("UPDATE sys_user SET password_hash=%s WHERE id=%s", (hash_val, uid))
    print(f"{username}: password_hash updated")

    # Insert or update password credential
    cur.execute("SELECT id FROM sys_auth_credential WHERE user_id=%s AND credential_type='PASSWORD' AND status='ACTIVE'", (uid,))
    existing = cur.fetchone()
    if existing:
        cur.execute("UPDATE sys_auth_credential SET secret_hash=%s WHERE id=%s", (cred_hash, existing[0]))
        print(f"{username}: credential updated")
    elif cred_hash:
        cid = int(time.time() * 1000) * 100000 + hash(username) % 100000
        cur.execute("INSERT INTO sys_auth_credential (id,user_id,credential_type,credential_subject,secret_hash,status,changed_at,created_at,updated_at,version) VALUES (%s,%s,'PASSWORD',%s,%s,'ACTIVE',NOW(3),NOW(3),NOW(3),0)",
            (cid, uid, username, cred_hash))
        print(f"{username}: credential created")

print("\nDone! Passwords should now work: 12312312312/123456 and 12121212121/123456")
conn.close()
