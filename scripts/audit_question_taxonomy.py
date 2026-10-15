"""Audit qualification/subject taxonomy coverage without changing the database.

Usage:
  python scripts/audit_question_taxonomy.py
"""

import csv
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

try:
    import pymysql
except ImportError:
    print("需要安装 pymysql: pip install pymysql", file=sys.stderr)
    raise SystemExit(1)

ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = Path(os.environ.get("CLASSIFY_OUTPUT_DIR", ROOT / "dev-data" / "question-classification"))


def env(name, default):
    return os.environ.get(name, default)


def integer(value):
    return int(value or 0)


def main():
    conn = pymysql.connect(
        host=env("MYSQL_HOST", "127.0.0.1"), port=int(env("MYSQL_PORT", "3306")),
        user=env("MYSQL_USER", "root"), password=env("MYSQL_PASSWORD", "root"),
        database=env("MYSQL_DATABASE", "aiexam"), charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )
    try:
        with conn.cursor() as cur:
            cur.execute("""
                SELECT eq.id AS qualification_id, eq.qualification_code, eq.qualification_name,
                       es.id AS subject_id, es.subject_code, es.subject_name,
                       COUNT(DISTINCT q.id) AS question_count,
                       SUM(q.id IS NOT NULL) AS scoped_question_count
                FROM exam_qualification eq
                JOIN exam_subject es ON es.qualification_id = eq.id AND es.deleted = 0
                LEFT JOIN q_question q ON q.qualification_id = eq.id AND q.subject_id = es.id AND q.deleted = 0
                WHERE eq.deleted = 0
                GROUP BY eq.id, eq.qualification_code, eq.qualification_name,
                         es.id, es.subject_code, es.subject_name
                ORDER BY eq.sort_order, es.sort_order, es.id
            """)
            scopes = cur.fetchall()
            for scope in scopes:
                scope["question_count"] = integer(scope["question_count"])
                scope["scoped_question_count"] = integer(scope["scoped_question_count"])
            cur.execute("""
                SELECT qualification_id, subject_id, knowledge_type, COUNT(*) AS node_count,
                       SUM(parent_id IS NULL) AS root_count, MAX(level_no) AS max_level
                FROM (
                  SELECT qualification_id, subject_id, 'CHAPTER_NODE' AS knowledge_type,
                         parent_id, level_no
                  FROM kp_chapter_node WHERE deleted = 0 AND status = 'ENABLED'
                  UNION ALL
                  SELECT qualification_id, subject_id, 'SYLLABUS_NODE' AS knowledge_type,
                         parent_id, level_no
                  FROM kp_syllabus_node WHERE deleted = 0 AND status = 'ENABLED'
                ) nodes
                GROUP BY qualification_id, subject_id, knowledge_type
            """)
            node_rows = {(r["qualification_id"], r["subject_id"], r["knowledge_type"]): {
                **r, "node_count": integer(r["node_count"]), "root_count": integer(r["root_count"]),
                "max_level": integer(r["max_level"])
            } for r in cur.fetchall()}
            cur.execute("""
                SELECT q.qualification_id, q.subject_id,
                       COUNT(DISTINCT q.id) AS question_count,
                       COUNT(DISTINCT CASE WHEN b.knowledge_type = 'CHAPTER_NODE' THEN q.id END) AS chapter_bound,
                       COUNT(DISTINCT CASE WHEN b.knowledge_type = 'SYLLABUS_NODE' THEN q.id END) AS syllabus_bound,
                       COUNT(DISTINCT CASE WHEN b.knowledge_type NOT IN ('CHAPTER_NODE', 'SYLLABUS_NODE') THEN q.id END) AS legacy_bound
                FROM q_question q
                LEFT JOIN q_question_knowledge_binding b
                  ON b.question_id = q.id AND b.deleted = 0
                WHERE q.deleted = 0
                GROUP BY q.qualification_id, q.subject_id
            """)
            binding_rows = {(r["qualification_id"], r["subject_id"]): {
                **r, "question_count": integer(r["question_count"]),
                "chapter_bound": integer(r["chapter_bound"]),
                "syllabus_bound": integer(r["syllabus_bound"]),
                "legacy_bound": integer(r["legacy_bound"])
            } for r in cur.fetchall()}
        audit = []
        for scope in scopes:
            key = (scope["qualification_id"], scope["subject_id"])
            chapter = node_rows.get((*key, "CHAPTER_NODE"), {"node_count": 0, "root_count": 0, "max_level": 0})
            syllabus = node_rows.get((*key, "SYLLABUS_NODE"), {"node_count": 0, "root_count": 0, "max_level": 0})
            bindings = binding_rows.get(key, {"question_count": 0, "chapter_bound": 0, "syllabus_bound": 0, "legacy_bound": 0})
            status = []
            if chapter["node_count"] == 0:
                status.append("MISSING_CHAPTER_TREE")
            elif chapter["root_count"] == 0:
                status.append("INVALID_CHAPTER_ROOT")
            if syllabus["node_count"] == 0:
                status.append("MISSING_SYLLABUS_TREE")
            elif syllabus["root_count"] == 0:
                status.append("INVALID_SYLLABUS_ROOT")
            if bindings["legacy_bound"]:
                status.append("LEGACY_BINDING_TYPE")
            audit.append({**scope, "chapter_nodes": chapter["node_count"], "chapter_roots": chapter["root_count"],
                          "syllabus_nodes": syllabus["node_count"], "syllabus_roots": syllabus["root_count"],
                          "chapter_bound_questions": bindings["chapter_bound"],
                          "syllabus_bound_questions": bindings["syllabus_bound"],
                          "legacy_bound_questions": bindings["legacy_bound"],
                          "status": ";".join(status) or "OK"})
        OUT_DIR.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        json_path = OUT_DIR / f"taxonomy-audit-{stamp}.json"
        csv_path = OUT_DIR / f"taxonomy-audit-{stamp}.csv"
        payload = {"generated_at": datetime.now(timezone.utc).isoformat(), "scope_count": len(audit),
                   "missing_scopes": [r for r in audit if r["status"] != "OK"], "scopes": audit,
                   "counts": {"missing_or_invalid": sum(r["status"] != "OK" for r in audit),
                              "ok": sum(r["status"] == "OK" for r in audit)}}
        json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        fields = list(audit[0]) if audit else []
        with csv_path.open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields)
            writer.writeheader()
            writer.writerows(audit)
        print(json.dumps({"scope_count": len(audit), "missing_or_invalid": len(payload["missing_scopes"]),
                          "json": str(json_path), "csv": str(csv_path)}, ensure_ascii=False, indent=2))
    finally:
        conn.close()


if __name__ == "__main__":
    main()
