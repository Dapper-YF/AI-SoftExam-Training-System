"""Create phone-based test accounts with valid mobile format."""
import pymysql, os, time

pwd = os.environ.get("MYSQL_PASSWORD", "123123")
conn = pymysql.connect(host="127.0.0.1", port=3306, user="root", password=pwd,
                        database="ai_exam_platform", autocommit=True)
cur = conn.cursor()
ts = int(time.time() * 1000)
seq = [0]
def nid(): seq[0]+=1; return ts*100000+seq[0]

N = "NOW(3)"

# phone constraint: ^\+861[3-9][0-9]{9}$
accounts = [
    ("+8613812345678", "12312312312", "123456", "测试学员", "STUDENT"),
    ("+8613912345678", "12121212121", "123456", "测试管理员", "ADMIN"),
]

for mobile, login_name, password, display, role in accounts:
    uid = nid()

    cur.execute(
        f"INSERT INTO sys_user (id,username,password_hash,password_changed_at,display_name,mobile,mobile_verified_at,email,status,last_login_at,failed_login_count,last_failed_login_at,locked_until,auth_version,version,created_at,updated_at,created_by,updated_by,deleted) "
        f"VALUES (%s,%s,'PLACEHOLDER',{N},%s,%s,{N},NULL,'ENABLED',NULL,0,NULL,NULL,0,0,{N},{N},NULL,NULL,0)",
        (uid, login_name, display, mobile))
    print(f"Created {role}: username={login_name} mobile={mobile} uid={uid}")

    for itype, ival in [("MOBILE", mobile), ("USERNAME", login_name)]:
        cur.execute(
            f"INSERT INTO sys_auth_login_identifier (id,user_id,identifier_type,normalized_identifier,verified,enabled,verified_at,created_at,updated_at,version) "
            f"VALUES (%s,%s,%s,%s,1,1,{N},{N},{N},0)",
            (nid(), uid, itype, ival))
        print(f"  + login identifier {itype}:{ival}")

    rid = 2 if role == "STUDENT" else 1
    cur.execute(f"INSERT INTO sys_user_role (user_id,role_id,created_at) VALUES (%s,%s,{N})", (uid, rid))
    print(f"  + role {role}")

print("\nDone! Restart backend to fix password hashes via DevTestAccountInitializer")
conn.close()
