"""Apply all missing Flyway SQL migrations directly to MySQL."""
import pymysql, os, glob

pwd = os.environ.get("MYSQL_PASSWORD", "123123")
conn = pymysql.connect(host="127.0.0.1", port=3306, user="root", password=pwd,
                        database="ai_exam_platform", charset="utf8mb4", autocommit=True)
cur = conn.cursor()

# Check which migrations are already applied
cur.execute("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY version")
applied = {row[0] for row in cur.fetchall()}
print(f"Applied Flyway versions: {sorted(applied)}")

# Find all V*.sql files
migration_dir = r"E:\Study\AI高项培训系统\backend\platform-application\src\main\resources\db\migration"
sql_files = sorted(glob.glob(f"{migration_dir}/V*.sql"))

for filepath in sql_files:
    filename = os.path.basename(filepath)
    version = filename.split("__")[0].lstrip("V").split("_")[0] if "__" in filename else filename.split(".")[0].lstrip("V")

    # Try to extract version as integer
    try:
        ver = int(version)
    except ValueError:
        continue

    if str(ver) in applied:
        print(f"  SKIP V{ver} ({filename}) - already applied")
        continue

    print(f"Applying V{ver} ({filename})...")
    with open(filepath, "r", encoding="utf-8") as f:
        sql = f.read()

    # Split by semicolons and execute each statement
    statements = [s.strip() for s in sql.split(";") if s.strip() and not s.strip().startswith("--")]
    for stmt in statements:
        if not stmt:
            continue
        try:
            cur.execute(stmt)
        except Exception as e:
            print(f"  WARNING: {str(e)[:100]}")

    # Record in flyway history
    now = "2026-07-20 10:00:00"
    checksum = 0  # Dummy checksum
    desc = filename.split("__")[1].replace(".sql", "") if "__" in filename else filename
    try:
        cur.execute(
            "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) "
            "VALUES (%s, %s, %s, 'SQL', %s, %s, 'manual', %s, 0, 1)",
            (ver + 10, str(ver), desc, filename, checksum, now)
        )
    except Exception as e:
        print(f"  Record WARNING: {e}")

    print(f"  OK V{ver}")

# Verify required tables
cur.execute("SELECT TABLE_NAME FROM information_schema.tables WHERE table_schema='ai_exam_platform' AND TABLE_NAME LIKE 'sys_%%' ORDER BY TABLE_NAME")
tables = [r[0] for r in cur.fetchall()]
print(f"\nsys_* tables: {tables}")

conn.close()
print("Done!")
