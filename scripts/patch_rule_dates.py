import pymysql, os
c = pymysql.connect(host="127.0.0.1", port=3306, user="root",
                     password=os.environ.get("MYSQL_PASSWORD","123123"),
                     database="ai_exam_platform")
cur = c.cursor()
cur.execute("UPDATE exam_rule SET effective_from='2026-01-01', effective_to='2026-12-31' WHERE effective_from IS NULL")
print(f"Patched {cur.rowcount} rows")
c.commit()
c.close()
