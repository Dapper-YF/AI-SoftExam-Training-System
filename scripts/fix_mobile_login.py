"""Fix mobile login identifiers to match updated phone numbers."""
import pymysql, os

pwd = os.environ.get("MYSQL_PASSWORD", "123123")
conn = pymysql.connect(host="127.0.0.1", port=3306, user="root", password=pwd,
                        database="ai_exam_platform", autocommit=True)
cur = conn.cursor()

# The normalized mobile login identifiers need to match what users type
# User types: 13812312312 → normalized to +8613812312312
# User types: 13912121212 → normalized to +8613912121212

users = {
    "13812312312": "+8613812312312",
    "13912121212": "+8613912121212",
}

for username, norm_mobile in users.items():
    cur.execute("SELECT id, mobile FROM sys_user WHERE username=%s", (username,))
    row = cur.fetchone()
    if not row:
        print(f"{username}: not found!")
        continue
    uid, current_mobile = row
    print(f"{username}: uid={uid}, mobile={current_mobile}")

    # Update sys_user.mobile to match normalized format
    if current_mobile != norm_mobile:
        cur.execute("UPDATE sys_user SET mobile=%s WHERE id=%s", (norm_mobile, uid))
        print(f"  Updated sys_user.mobile: {current_mobile} → {norm_mobile}")

    # Fix MOBILE login identifier
    cur.execute("SELECT id, normalized_identifier FROM sys_auth_login_identifier WHERE user_id=%s AND identifier_type='MOBILE'", (uid,))
    for li_id, li_val in cur.fetchall():
        if li_val != norm_mobile:
            cur.execute("UPDATE sys_auth_login_identifier SET normalized_identifier=%s WHERE id=%s", (norm_mobile, li_id))
            print(f"  Updated MOBILE login identifier {li_id}: {li_val} → {norm_mobile}")
        else:
            print(f"  MOBILE login identifier {li_id}: OK ({li_val})")

# Also fix the student/admin accounts
cur.execute("SELECT id, username, mobile FROM sys_user WHERE username IN ('student','admin')")
for uid, uname, mobile in cur.fetchall():
    norm = mobile  # already in +86 format
    cur.execute("SELECT id, normalized_identifier FROM sys_auth_login_identifier WHERE user_id=%s AND identifier_type='MOBILE'", (uid,))
    for li_id, li_val in cur.fetchall():
        if li_val != norm:
            cur.execute("UPDATE sys_auth_login_identifier SET normalized_identifier=%s WHERE id=%s", (norm, li_id))
            print(f"{uname}: Fixed MOBILE identifier {li_id}: {li_val} → {norm}")
        else:
            print(f"{uname}: MOBILE identifier OK ({norm})")

print("\nDone!")
conn.close()
