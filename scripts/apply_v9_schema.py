"""Apply V9 DDL migrations (01-06) and populate defaults for existing data."""
import pymysql, os

pwd = os.environ.get("MYSQL_PASSWORD", "123123")
conn = pymysql.connect(host="127.0.0.1", port=3306, user="root", password=pwd,
                        database="ai_exam_platform", charset="utf8mb4")
cur = conn.cursor()

def run(sql, label):
    try:
        cur.execute(sql)
        print(f"  OK: {label}")
    except Exception as e:
        print(f"  SKIP: {label} — {e}")

# Step 1: Add V9 columns (NULL first)
print("Step 1: Adding V9 columns...")
run("ALTER TABLE exam_qualification ADD COLUMN domain_code VARCHAR(32) NULL AFTER qualification_code", "qualification.domain_code")
run("ALTER TABLE exam_qualification ADD COLUMN official_name VARCHAR(96) NULL AFTER qualification_name", "qualification.official_name")
run("ALTER TABLE exam_qualification ADD COLUMN source_url VARCHAR(512) NULL AFTER level_code", "qualification.source_url")
run("ALTER TABLE exam_qualification ADD COLUMN verified_at DATETIME(3) NULL AFTER source_url", "qualification.verified_at")
run("ALTER TABLE exam_qualification ADD COLUMN verification_status VARCHAR(32) NULL AFTER verified_at", "qualification.verification_status")
run("ALTER TABLE exam_qualification ADD COLUMN catalog_version VARCHAR(32) NULL AFTER verification_status", "qualification.catalog_version")
run("ALTER TABLE exam_qualification ADD COLUMN catalog_status VARCHAR(24) NULL AFTER catalog_version", "qualification.catalog_status")
run("ALTER TABLE exam_subject ADD COLUMN official_name VARCHAR(96) NULL AFTER subject_name", "subject.official_name")
run("ALTER TABLE exam_rule ADD COLUMN period_code VARCHAR(32) NULL AFTER subject_id", "rule.period_code")
run("ALTER TABLE exam_rule ADD COLUMN effective_from DATETIME(3) NULL AFTER effective_at", "rule.effective_from")
run("ALTER TABLE exam_rule ADD COLUMN effective_to DATETIME(3) NULL AFTER effective_from", "rule.effective_to")
run("ALTER TABLE exam_rule ADD COLUMN published_at DATETIME(3) NULL AFTER effective_to", "rule.published_at")
run("ALTER TABLE exam_rule ADD COLUMN version BIGINT NULL AFTER published_at", "rule.version")

# Step 2: Populate defaults for NOT NULL columns
print("\nStep 2: Populating defaults...")
cur.execute("UPDATE exam_qualification SET domain_code='SOFT_EXAM', official_name=qualification_name, verification_status='THIRD_PARTY_CANDIDATE', catalog_version='2026', catalog_status='ACTIVE'")
print(f"  Updated {cur.rowcount} qualifications")
cur.execute("UPDATE exam_subject SET official_name=subject_name")
print(f"  Updated {cur.rowcount} subjects")

# Step 3: Apply hardening (NOT NULL + CHECK)
print("\nStep 3: Hardening...")
run("ALTER TABLE exam_qualification MODIFY COLUMN domain_code VARCHAR(32) NOT NULL", "qual.domain_code NOT NULL")
run("ALTER TABLE exam_qualification MODIFY COLUMN official_name VARCHAR(96) NOT NULL", "qual.official_name NOT NULL")
run("ALTER TABLE exam_qualification MODIFY COLUMN verification_status VARCHAR(32) NOT NULL", "qual.verification_status NOT NULL")
run("ALTER TABLE exam_qualification MODIFY COLUMN catalog_version VARCHAR(32) NOT NULL", "qual.catalog_version NOT NULL")
run("ALTER TABLE exam_qualification MODIFY COLUMN catalog_status VARCHAR(24) NOT NULL", "qual.catalog_status NOT NULL")

run("ALTER TABLE exam_qualification ADD CONSTRAINT ck_exam_qualification_domain CHECK (domain_code = 'SOFT_EXAM')", "ck_domain")
run("ALTER TABLE exam_qualification ADD CONSTRAINT ck_exam_qualification_level CHECK (level_code IN ('PRIMARY','INTERMEDIATE','ADVANCED'))", "ck_level")
run("ALTER TABLE exam_qualification ADD CONSTRAINT ck_exam_qualification_verification CHECK ((verification_status = 'OFFICIAL_VERIFIED' AND source_url IS NOT NULL AND verified_at IS NOT NULL) OR verification_status IN ('THIRD_PARTY_CANDIDATE','VERIFICATION_EXPIRED'))", "ck_verification")
run("ALTER TABLE exam_qualification ADD CONSTRAINT ck_exam_qualification_catalog_status CHECK (catalog_status IN ('ACTIVE','RETIRED'))", "ck_catalog_status")
run("ALTER TABLE exam_qualification ADD CONSTRAINT ck_exam_qualification_operational_status CHECK (status IN ('DRAFT','ENABLED','DISABLED') AND NOT (catalog_status = 'RETIRED' AND status = 'ENABLED'))", "ck_operational_status")

run("ALTER TABLE exam_subject MODIFY COLUMN official_name VARCHAR(96) NOT NULL", "subj.official_name NOT NULL")
run("ALTER TABLE exam_subject ADD CONSTRAINT ck_exam_subject_type CHECK (subject_type IN ('COMPREHENSIVE','CASE','ESSAY','APPLICATION'))", "ck_subject_type")
run("ALTER TABLE exam_subject ADD CONSTRAINT ck_exam_subject_status CHECK (status IN ('DRAFT','ENABLED','DISABLED'))", "ck_subject_status")

run("ALTER TABLE exam_rule MODIFY COLUMN period_code VARCHAR(32) NOT NULL", "rule.period_code NOT NULL")
run("ALTER TABLE exam_rule MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0", "rule.version NOT NULL")
run("ALTER TABLE exam_rule ADD CONSTRAINT ck_exam_rule_subject_scope CHECK (subject_id IS NULL)", "ck_rule_subject_scope")
run("ALTER TABLE exam_rule ADD CONSTRAINT ck_exam_rule_status CHECK (status IN ('DRAFT','PUBLISHED','RETIRED'))", "ck_rule_status")

conn.commit()
print("\nDone! All V9 DDL applied.")
conn.close()
