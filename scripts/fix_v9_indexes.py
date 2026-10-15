import pymysql, os
pwd = os.environ.get("MYSQL_PASSWORD", "123123")
conn = pymysql.connect(host="127.0.0.1", port=3306, user="root", password=pwd,
                        database="ai_exam_platform", charset="utf8mb4", autocommit=True)
cur = conn.cursor()

def run(sql, label):
    try:
        cur.execute(sql)
        print(f"  OK: {label}")
    except Exception as e:
        print(f"  SKIP: {label} - {e}")

# Missing CHECK
run("ALTER TABLE exam_rule ADD CONSTRAINT ck_exam_rule_effective_range CHECK (effective_from < effective_to)", "ck_effective_range")

# Required indexes
run("ALTER TABLE exam_qualification ADD KEY idx_exam_qualification_tree (domain_code,level_code,status,sort_order,id)", "idx_qual_tree")
run("ALTER TABLE exam_qualification ADD KEY idx_exam_qualification_verification (verification_status,verified_at)", "idx_qual_verification")
run("ALTER TABLE exam_subject ADD KEY idx_exam_subject_tree (qualification_id,status,sort_order,id)", "idx_subj_tree")
run("ALTER TABLE exam_rule ADD UNIQUE KEY uk_exam_rule_period_version (qualification_id,period_code,rule_code,rule_version)", "uk_rule_period")
run("ALTER TABLE exam_rule ADD KEY idx_exam_rule_active (qualification_id,period_code,status,effective_from,effective_to)", "idx_rule_active")

# Drop obsolete indexes
run("ALTER TABLE exam_subject DROP INDEX idx_exam_subject_qualification", "drop idx_subj_qual")
run("ALTER TABLE exam_rule DROP INDEX uk_exam_rule_version", "drop uk_rule_version")
run("ALTER TABLE exam_rule DROP INDEX idx_exam_rule_subject", "drop idx_rule_subject")

# Ensure rule rows have effective dates
cur.execute("UPDATE exam_rule SET period_code='2026-H2', effective_from='2026-01-01', effective_to='2026-12-31' WHERE period_code IS NULL OR effective_from IS NULL")
print(f"  Updated {cur.rowcount} exam_rule rows")

print("Done!")
conn.close()
