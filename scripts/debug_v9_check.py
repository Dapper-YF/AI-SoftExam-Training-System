import pymysql, os
pwd = os.environ.get("MYSQL_PASSWORD", "123123")
conn = pymysql.connect(host="127.0.0.1", port=3306, user="root", password=pwd,
                        database="ai_exam_platform", charset="utf8mb4")
cur = conn.cursor()

# Exact validator queries
cur.execute("SELECT DATABASE()")
print(f"DATABASE() = {cur.fetchone()[0]}")

cur.execute("""SELECT COUNT(*) FROM information_schema.columns
WHERE table_schema=DATABASE() AND (
  (table_name='exam_qualification' AND column_name IN
    ('domain_code','official_name','source_url','verified_at','verification_status',
     'catalog_version','catalog_status')) OR
  (table_name='exam_subject' AND column_name='official_name') OR
  (table_name='exam_rule' AND column_name IN
    ('period_code','effective_from','effective_to','published_at','version')))
""")
print(f"Query1 (all columns): {cur.fetchone()[0]} (expected: 13)")

cur.execute("""SELECT COUNT(*) FROM information_schema.columns
WHERE table_schema=DATABASE() AND is_nullable='NO' AND (
  (table_name='exam_qualification' AND column_name IN
    ('domain_code','official_name','verification_status','catalog_version','catalog_status')) OR
  (table_name='exam_subject' AND column_name='official_name') OR
  (table_name='exam_rule' AND column_name IN
    ('period_code','effective_from','effective_to','version')))
""")
print(f"Query2 (NOT NULL columns): {cur.fetchone()[0]} (expected: 10)")

# Also list the columns found by query1
cur.execute("""SELECT table_name, column_name FROM information_schema.columns
WHERE table_schema=DATABASE() AND (
  (table_name='exam_qualification' AND column_name IN
    ('domain_code','official_name','source_url','verified_at','verification_status',
     'catalog_version','catalog_status')) OR
  (table_name='exam_subject' AND column_name='official_name') OR
  (table_name='exam_rule' AND column_name IN
    ('period_code','effective_from','effective_to','published_at','version')))
ORDER BY table_name, column_name
""")
for row in cur.fetchall():
    print(f"  {row[0]}.{row[1]}")

conn.close()
