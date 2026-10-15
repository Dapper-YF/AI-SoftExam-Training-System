"""批量生成题目到章节/知识点的候选归类结果。

默认只读数据库并输出 JSONL/CSV 报告；设置 APPLY=1 才会写入
q_question_knowledge_binding。低置信度题目不会自动写入。

用法:
  python scripts/classify_questions.py
  $env:APPLY='1'; python scripts/classify_questions.py
"""

import csv
import json
import os
import re
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
APPLY = os.environ.get("APPLY", "0") == "1"
MIN_SCORE = float(os.environ.get("CLASSIFY_MIN_SCORE", "0.72"))
MIN_MARGIN = float(os.environ.get("CLASSIFY_MIN_MARGIN", "0.12"))


def env(name, default):
    return os.environ.get(name, default)


def normalize(value):
    return re.sub(r"[^0-9a-zA-Z\u4e00-\u9fff]+", "", str(value or "")).lower()


def content_text(content):
    if isinstance(content, (dict, list)):
        return json.dumps(content, ensure_ascii=False)
    return str(content or "")


def report_text(value):
    """Remove database control characters so generated JSON/CSV remains valid."""
    return re.sub(r"[\x00-\x1f\x7f]", "", str(value or ""))


def node_terms(node):
    name = normalize(node["name"])
    code = normalize(node["code"])
    terms = {term for term in (name, code) if len(term) >= 2}
    # 过短的中文目录名容易造成大量误匹配，只保留编码或长度足够的名称。
    if len(name) < 3:
        terms.discard(name)
    if len(name) >= 4:
        terms.update(name[index:index + 2] for index in range(len(name) - 1))
    return terms


def candidates(text, nodes):
    normalized = normalize(text)
    scored = []
    for node in nodes:
        hits = [term for term in node["terms"] if term and term in normalized]
        if not hits:
            continue
        name = normalize(node["name"])
        name_bigrams = {name[index:index + 2] for index in range(max(0, len(name) - 1))}
        bigram_hits = len(name_bigrams.intersection(hits))
        score = min(1.0, max(len(hit) for hit in hits) / 12.0)
        if name_bigrams:
            score = max(score, min(1.0, bigram_hits / max(2, min(8, len(name_bigrams)))))
        if normalize(node["code"]) in normalized:
            score = min(1.0, score + 0.35)
        scored.append((score, len(max(hits, key=len)), node, hits))
    return sorted(scored, key=lambda x: (x[0], x[1]), reverse=True)


def load_nodes(cur):
    cur.execute("""
        SELECT id, qualification_id, subject_id, chapter_code AS code,
               chapter_name AS name, 'CHAPTER_NODE' AS knowledge_type
        FROM kp_chapter_node
        WHERE deleted = 0 AND status = 'ENABLED'
        UNION ALL
        SELECT id, qualification_id, subject_id, node_code AS code,
               node_name AS name, 'SYLLABUS_NODE' AS knowledge_type
        FROM kp_syllabus_node
        WHERE deleted = 0 AND status = 'ENABLED'
    """)
    nodes = {}
    for row in cur.fetchall():
        row["terms"] = node_terms(row)
        nodes.setdefault((row["qualification_id"], row["subject_id"], row["knowledge_type"]), []).append(row)
    return nodes


def load_questions(cur):
    cur.execute("""
        SELECT q.id AS question_id, q.qualification_id, q.subject_id,
               eq.qualification_code, eq.qualification_name,
               es.subject_code, es.subject_name,
               COALESCE(qv.id, q.current_working_version_id, q.current_published_version_id) AS question_version_id,
               q.title, q.question_type, q.year_no, q.source_type, q.source_ref, q.import_external_key,
               qv.source_type AS version_source_type, qv.source_ref AS version_source_ref,
               qv.source_file, qv.source_locator, qv.syllabus_basis, qv.external_key,
               COALESCE((SELECT GROUP_CONCAT(s.content SEPARATOR '\\n')
                         FROM q_question_stem s
                         WHERE s.question_id = q.id AND s.deleted = 0), '') AS stem_text,
               COALESCE((SELECT GROUP_CONCAT(sq.content SEPARATOR '\\n')
                         FROM q_sub_question sq
                         WHERE sq.question_id = q.id AND sq.deleted = 0), '') AS sub_question_text,
               COALESCE((SELECT GROUP_CONCAT(sol.content SEPARATOR '\\n')
                         FROM q_solution sol
                         WHERE sol.question_id = q.id AND sol.deleted = 0), '') AS solution_text,
               COALESCE(qv.content_json, JSON_OBJECT()) AS content_json
        FROM q_question q
        LEFT JOIN exam_qualification eq ON eq.id = q.qualification_id
        LEFT JOIN exam_subject es ON es.id = q.subject_id
        LEFT JOIN q_question_version qv ON qv.id = COALESCE(q.current_working_version_id, q.current_published_version_id)
        WHERE q.deleted = 0
    """)
    return cur.fetchall()


def classify_question(question, nodes):
    try:
        content = question["content_json"]
        if isinstance(content, str):
            content = json.loads(content)
    except (TypeError, json.JSONDecodeError):
        content = question["content_json"]
    text = content_text(content) + "\n" + "\n".join(
        str(question.get(field) or "")
        for field in ("title", "question_type", "year_no", "source_type", "source_ref", "import_external_key",
                      "version_source_type", "version_source_ref", "source_file", "source_locator",
                      "syllabus_basis", "external_key", "stem_text", "sub_question_text", "solution_text")
    )
    result = {"question_id": question["question_id"], "question_version_id": question["question_version_id"],
              "qualification_id": question["qualification_id"], "subject_id": question["subject_id"],
              "qualification_code": question.get("qualification_code"),
              "qualification_name": question.get("qualification_name"),
              "subject_code": question.get("subject_code"),
              "subject_name": question.get("subject_name"),
              "chapter": None, "syllabus": None, "status": "UNMATCHED"}
    statuses = []
    for kind, key in (("CHAPTER_NODE", "chapter"), ("SYLLABUS_NODE", "syllabus")):
        ranked = candidates(text, nodes.get((question["qualification_id"], question["subject_id"], kind), []))
        if not ranked:
            continue
        best = ranked[0]
        margin = best[0] - ranked[1][0] if len(ranked) > 1 else best[0]
        result[key] = {"knowledge_type": kind, "knowledge_id": best[2]["id"], "knowledge_code": best[2]["code"],
                       "knowledge_name": best[2]["name"], "score": round(best[0], 4),
                       "margin": round(margin, 4), "hits": best[3]}
        statuses.append(best[0] >= MIN_SCORE and margin >= MIN_MARGIN)
    if statuses and all(statuses):
        result["status"] = "AUTO_MATCHED"
    elif any(result[key] for key in ("chapter", "syllabus")):
        result["status"] = "REVIEW"
    return result


def write_outputs(results):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    jsonl = OUT_DIR / f"classification-{stamp}.jsonl"
    csv_path = OUT_DIR / f"classification-{stamp}.csv"
    report = OUT_DIR / f"report-{stamp}.json"
    gap_csv = OUT_DIR / f"gaps-{stamp}.csv"
    with jsonl.open("w", encoding="utf-8") as handle:
        for item in results:
            handle.write(json.dumps(item, ensure_ascii=False) + "\n")
    fields = ["question_id", "question_version_id", "qualification_id", "qualification_code", "qualification_name",
              "subject_id", "subject_code", "subject_name", "status",
              "chapter", "syllabus"]
    with csv_path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for item in results:
            row = {key: item[key] for key in fields[:5]}
            for key in ("chapter", "syllabus"):
                row[key] = (item[key] or {}).get("knowledge_code", "")
            writer.writerow(row)
    gap_fields = ["question_id", "qualification_id", "qualification_code", "qualification_name",
                  "subject_id", "subject_code", "subject_name", "status", "chapter", "syllabus"]
    with gap_csv.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=gap_fields)
        writer.writeheader()
        for item in results:
            if item["status"] == "AUTO_MATCHED":
                continue
            row = {key: report_text(item.get(key)) for key in gap_fields[:8]}
            row["chapter"] = (item["chapter"] or {}).get("knowledge_code", "")
            row["syllabus"] = (item["syllabus"] or {}).get("knowledge_code", "")
            writer.writerow(row)
    counts = {}
    by_scope = {}
    for item in results:
        counts[item["status"]] = counts.get(item["status"], 0) + 1
        scope_key = (item.get("qualification_id"), item.get("subject_id"))
        scope = by_scope.setdefault(scope_key, {
            "qualification_id": item.get("qualification_id"),
            "qualification_code": report_text(item.get("qualification_code")),
            "qualification_name": report_text(item.get("qualification_name")),
            "subject_id": item.get("subject_id"),
            "subject_code": report_text(item.get("subject_code")),
            "subject_name": report_text(item.get("subject_name")),
            "question_count": 0, "counts": {}, "chapter_matched": 0, "syllabus_matched": 0,
        })
        scope["question_count"] += 1
        scope["counts"][item["status"]] = scope["counts"].get(item["status"], 0) + 1
        scope["chapter_matched"] += bool(item.get("chapter"))
        scope["syllabus_matched"] += bool(item.get("syllabus"))
    scopes = sorted(by_scope.values(), key=lambda x: (
        -(x["counts"].get("UNMATCHED", 0) + x["counts"].get("REVIEW", 0)),
        str(x.get("qualification_name") or ""), str(x.get("subject_name") or "")))
    gaps = [scope for scope in scopes if scope["counts"].get("UNMATCHED", 0) or scope["counts"].get("REVIEW", 0)]
    report.write_text(json.dumps({"total": len(results), "counts": counts, "scope_count": len(scopes),
                                  "scopes": scopes, "gaps": gaps, "min_score": MIN_SCORE,
                                  "min_margin": MIN_MARGIN, "generated_at": datetime.now(timezone.utc).isoformat(),
                                  "jsonl": str(jsonl), "csv": str(csv_path), "gap_csv": str(gap_csv)}, ensure_ascii=False, indent=2), encoding="utf-8")
    return jsonl, csv_path, report, gap_csv, counts


def apply_results(cur, results):
    # Remove legacy values produced by earlier script revisions; the application contract uses *_NODE.
    cur.execute("""
        UPDATE q_question_knowledge_binding
        SET deleted = 1, updated_at = CURRENT_TIMESTAMP(3)
        WHERE deleted = 0 AND knowledge_type IN ('CHAPTER', 'SYLLABUS')
    """)
    rows = []
    for item in results:
        if not item["question_version_id"]:
            continue
        for key in ("chapter", "syllabus"):
            binding = item[key]
            if binding and binding["score"] >= MIN_SCORE and binding["margin"] >= MIN_MARGIN:
                rows.append((item["question_id"], item["question_version_id"], binding["knowledge_type"],
                             binding["knowledge_id"], binding["knowledge_code"], binding["score"]))
    cur.executemany("""
        INSERT INTO q_question_knowledge_binding
          (target_type, target_id, question_id, question_version_id, knowledge_type,
           knowledge_id, knowledge_code, weight, sort_order, created_at, updated_at, deleted)
        VALUES ('QUESTION', %s, %s, %s, %s, %s, %s, %s, 1, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0)
        ON DUPLICATE KEY UPDATE knowledge_code = VALUES(knowledge_code), weight = VALUES(weight),
          updated_at = CURRENT_TIMESTAMP(3), deleted = 0
    """, [(r[0], r[0], r[1], r[2], r[3], r[4], r[5]) for r in rows])
    return len(rows)


def main():
    conn = pymysql.connect(host=env("MYSQL_HOST", "127.0.0.1"), port=int(env("MYSQL_PORT", "3306")),
                           user=env("MYSQL_USER", "root"), password=env("MYSQL_PASSWORD", "root"),
                           database=env("MYSQL_DATABASE", "aiexam"), charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor)
    try:
        with conn.cursor() as cur:
            nodes = load_nodes(cur)
            questions = load_questions(cur)
            results = [classify_question(question, nodes) for question in questions]
            outputs = write_outputs(results)
            applied = apply_results(cur, results) if APPLY else 0
            if APPLY:
                conn.commit()
            print(json.dumps({"questions": len(questions), "nodes": sum(map(len, nodes.values())),
                              "applied_bindings": applied, "outputs": [str(path) for path in outputs]}, ensure_ascii=False, indent=2))
    finally:
        conn.close()


if __name__ == "__main__":
    main()
