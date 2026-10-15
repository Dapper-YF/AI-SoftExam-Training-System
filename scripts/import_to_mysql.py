"""
直接连接 MySQL 导入 20 个软考资格的章节/考纲数据。
绕过 REST API，适用于后端未启动或 API 认证未配置的场景。

用法:
  python scripts/import_to_mysql.py

环境变量（可选，默认连本地 root/root）:
  MYSQL_HOST=127.0.0.1
  MYSQL_PORT=3306
  MYSQL_USER=root
  MYSQL_PASSWORD=root
  MYSQL_DATABASE=aiexam

  DRY_RUN=1  只打印 SQL 不执行

依赖: pip install pymysql
"""

import json
import os
import sys
from datetime import datetime, timezone

try:
    import pymysql
except ImportError:
    print("需要安装 pymysql: pip install pymysql")
    sys.exit(1)

# ── 配置 ──────────────────────────────────────────────

MYSQL_HOST = os.environ.get("MYSQL_HOST", "127.0.0.1")
MYSQL_PORT = int(os.environ.get("MYSQL_PORT", "3306"))
MYSQL_USER = os.environ.get("MYSQL_USER", "root")
MYSQL_PASSWORD = os.environ.get("MYSQL_PASSWORD", "root")
MYSQL_DATABASE = os.environ.get("MYSQL_DATABASE", "aiexam")

JSON_PATH = r"F:\网页下载2\miaoda-code-ruankao_20_qualifications.json.json"

DRY_RUN = os.environ.get("DRY_RUN", "0") == "1"

# ── 映射表 ──────────────────────────────────────────────

LEVEL_MAP = {"高级": "ADVANCED", "中级": "INTERMEDIATE", "初级": "PRIMARY"}

ADVANCED_SUBJECT_ORDER = {"综合知识": 1, "案例分析": 2, "论文": 3}
STANDARD_SUBJECT_ORDER = {"基础知识": 1, "应用技术": 2}

SUBJECT_CODE_MAP = {
    "综合知识": "COMPREHENSIVE_KNOWLEDGE", "案例分析": "CASE_ANALYSIS",
    "论文": "ESSAY", "基础知识": "BASIC_KNOWLEDGE", "应用技术": "APPLIED_TECHNOLOGY",
}

SUBJECT_TYPE_MAP = {
    "综合知识": "COMPREHENSIVE", "案例分析": "CASE", "论文": "ESSAY",
    "基础知识": "COMPREHENSIVE", "应用技术": "APPLICATION",
}

QUAL_SORT_ORDER = {
    "HIGH_PM": 10, "HIGH_SYSTEM_PLANNING_MANAGER": 20,
    "HIGH_SYSTEM_ANALYST": 30, "HIGH_SYSTEM_ARCHITECT": 40,
    "HIGH_NETWORK_PLANNER": 50, "MIDDLE_SOFTWARE_DESIGNER": 110,
    "MIDDLE_NETWORK_ENGINEER": 120, "MIDDLE_INFORMATION_SYSTEM_SUPERVISOR": 130,
    "MIDDLE_SYSTEM_INTEGRATION_PM": 140, "MIDDLE_EMBEDDED_SYSTEM_DESIGNER": 150,
    "MIDDLE_ECOMMERCE_DESIGNER": 160, "MIDDLE_DATABASE_SYSTEM_ENGINEER": 170,
    "MIDDLE_SOFTWARE_TESTER": 180, "MIDDLE_INFORMATION_SYSTEM_MANAGER": 190,
    "MIDDLE_INFORMATION_SECURITY_ENGINEER": 200, "MIDDLE_MULTIMEDIA_APPLICATION_DESIGNER": 210,
    "LOW_PROGRAMMER": 310, "LOW_NETWORK_ADMINISTRATOR": 320,
    "LOW_INFORMATION_SYSTEM_OPERATOR": 330, "LOW_INFORMATION_PROCESSING_TECHNICIAN": 340,
}

# ID 基址
QUAL_ID_BASE = 1
SUBJECT_ID_BASE = 1
CHAPTER_ID_BASE = 50000
SYLLABUS_ID_BASE = 60000


def main():
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")

    with open(JSON_PATH, "r", encoding="utf-8") as f:
        data = json.load(f)

    if DRY_RUN:
        print("[DRY RUN] 不会实际写入数据库\n")
    else:
        print(f"连接 MySQL: {MYSQL_HOST}:{MYSQL_PORT}/{MYSQL_DATABASE} ...")
        conn = pymysql.connect(
            host=MYSQL_HOST, port=MYSQL_PORT, user=MYSQL_USER,
            password=MYSQL_PASSWORD, database=MYSQL_DATABASE,
            charset="utf8mb4", autocommit=False,
        )
        cur = conn.cursor()

    try:
        qualifications = data["qualifications"]

        # ── 1. 资格 ─────────────────────────────────
        qual_rows = []
        qual_code_to_id = {}
        for idx, q in enumerate(qualifications):
            qual_id = QUAL_ID_BASE + idx
            qual_code_to_id[q["code"]] = qual_id
            level = LEVEL_MAP[q["level"]]
            sort_order = QUAL_SORT_ORDER.get(q["code"], idx * 10 + 10)
            qual_rows.append((
                qual_id, q["code"], q["name"], level, sort_order,
                now, now,
            ))

        print(f"资格: {len(qual_rows)} 条")
        if not DRY_RUN:
            cur.execute("DELETE FROM kp_chapter_node")
            cur.execute("DELETE FROM kp_syllabus_node")
            cur.execute("DELETE FROM kp_tag")
            cur.execute("DELETE FROM exam_subject")
            cur.execute("DELETE FROM exam_qualification")
            cur.executemany(
                "INSERT INTO exam_qualification (id, qualification_code, domain_code, qualification_name, official_name, level_code, verification_status, catalog_version, catalog_status, status, sort_order, created_at, updated_at, created_by, updated_by, deleted) "
                "VALUES (%s, %s, 'SOFT_EXAM', %s, %s, %s, 'THIRD_PARTY_CANDIDATE', '2026', 'ACTIVE', 'ENABLED', %s, %s, %s, NULL, NULL, 0)",
                [(qid, code, name, name, level, sort, now, now) for qid, code, name, level, sort, now, now in qual_rows],
            )

        # ── 2. 科目 ─────────────────────────────────
        subj_rows = []
        subject_map = {}
        next_subj_id = SUBJECT_ID_BASE
        seen = set()
        for q in qualifications:
            qual_id = qual_code_to_id[q["code"]]
            for s in q["subjects"]:
                key = (qual_id, s["subjectName"])
                if key in seen:
                    continue
                seen.add(key)
                sid = next_subj_id
                next_subj_id += 1
                subject_map[key] = sid
                subj_code = SUBJECT_CODE_MAP.get(s["subjectName"], s["subjectName"])
                subj_type = SUBJECT_TYPE_MAP.get(s["subjectName"], "COMPREHENSIVE")
                sort_order = ADVANCED_SUBJECT_ORDER.get(s["subjectName"]) or STANDARD_SUBJECT_ORDER.get(s["subjectName"]) or 0
                subj_rows.append((
                    sid, qual_id, subj_code, s["subjectName"], s["subjectName"], subj_type, sort_order,
                    now, now,
                ))

        print(f"科目: {len(subj_rows)} 条")
        if not DRY_RUN:
            cur.executemany(
                "INSERT INTO exam_subject (id, qualification_id, subject_code, subject_name, official_name, subject_type, sort_order, status, created_at, updated_at, created_by, updated_by, deleted) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, 'ENABLED', %s, %s, NULL, NULL, 0)",
                subj_rows,
            )

        # ── 3. 章节 + 考纲（树形展平） ────────────
        chapter_id_gen = iter(range(CHAPTER_ID_BASE, CHAPTER_ID_BASE + 100000))
        syllabus_id_gen = iter(range(SYLLABUS_ID_BASE, SYLLABUS_ID_BASE + 100000))
        chapter_rows = []
        syllabus_rows = []

        def flatten(nodes, qual_id, subj_id, id_gen, parent_id=None):
            result = []
            for idx, node in enumerate(nodes):
                nid = next(id_gen)
                result.append((nid, qual_id, subj_id, parent_id, node["code"], node["name"], node["levelNo"], idx + 1))
                children = node.get("children", [])
                if children:
                    result.extend(flatten(children, qual_id, subj_id, id_gen, parent_id=nid))
            return result

        for q in qualifications:
            qual_id = qual_code_to_id[q["code"]]
            for s in q["subjects"]:
                subj_id = subject_map[(qual_id, s["subjectName"])]
                chapters = s.get("chapters", [])
                if chapters:
                    chapter_rows.extend(flatten(chapters, qual_id, subj_id, chapter_id_gen))
                syllabus = s.get("syllabus", [])
                if syllabus:
                    syllabus_rows.extend(flatten(syllabus, qual_id, subj_id, syllabus_id_gen))

        print(f"章节节点: {len(chapter_rows)} 条")
        print(f"考纲节点: {len(syllabus_rows)} 条")

        if not DRY_RUN:
            # 章节插入
            chapter_sql = (
                "INSERT INTO kp_chapter_node (id, qualification_id, subject_id, parent_id, "
                "book_version, course_code, chapter_code, chapter_name, level_no, sort_order, "
                "status, created_at, updated_at, created_by, updated_by, deleted) "
                "VALUES (%s, %s, %s, %s, '2026', NULL, %s, %s, %s, %s, 'ENABLED', %s, %s, NULL, NULL, 0)"
            )
            for i in range(0, len(chapter_rows), 200):
                batch = chapter_rows[i:i + 200]
                cur.executemany(chapter_sql, [
                    (nid, qid, sid, pid, code, name, lvl, sort, now, now)
                    for nid, qid, sid, pid, code, name, lvl, sort in batch
                ])

            # 考纲插入
            syllabus_sql = (
                "INSERT INTO kp_syllabus_node (id, qualification_id, subject_id, parent_id, "
                "syllabus_version, node_code, node_name, level_no, sort_order, "
                "status, created_at, updated_at, created_by, updated_by, deleted) "
                "VALUES (%s, %s, %s, %s, '2026', %s, %s, %s, %s, 'ENABLED', %s, %s, NULL, NULL, 0)"
            )
            for i in range(0, len(syllabus_rows), 200):
                batch = syllabus_rows[i:i + 200]
                cur.executemany(syllabus_sql, [
                    (nid, qid, sid, pid, code, name, lvl, sort, now, now)
                    for nid, qid, sid, pid, code, name, lvl, sort in batch
                ])

            conn.commit()

    except Exception as e:
        if not DRY_RUN:
            conn.rollback()
        print(f"\n错误: {e}", file=sys.stderr)
        raise
    finally:
        if not DRY_RUN:
            cur.close()
            conn.close()

    print(f"\n{'[DRY RUN] ' if DRY_RUN else ''}导入完成！")
    print(f"  资格:     {len(qual_rows)}")
    print(f"  科目:     {len(subj_rows)}")
    print(f"  章节节点: {len(chapter_rows)}")
    print(f"  考纲节点: {len(syllabus_rows)}")


if __name__ == "__main__":
    main()
