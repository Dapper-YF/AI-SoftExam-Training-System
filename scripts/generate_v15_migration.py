"""
从 miaoda-code-ruankao_20_qualifications.json.json 生成
V15__seed_all_qualifications.sql Flyway 迁移脚本。

用法:
  python generate_v15_migration.py > ../backend/platform-application/src/main/resources/db/migration/V15__seed_all_qualifications.sql
"""

import json
from datetime import datetime, timezone

JSON_PATH = r"F:\网页下载2\miaoda-code-ruankao_20_qualifications.json.json"
NOW = "NOW(3)"

# ── 映射表 ──────────────────────────────────────────────

LEVEL_MAP = {"高级": "ADVANCED", "中级": "INTERMEDIATE", "初级": "PRIMARY"}

# 高级资格科目顺序
ADVANCED_SUBJECT_ORDER = {"综合知识": 1, "案例分析": 2, "论文": 3}

# 初中级资格科目顺序
STANDARD_SUBJECT_ORDER = {"基础知识": 1, "应用技术": 2}

SUBJECT_CODE_MAP = {
    "综合知识": "COMPREHENSIVE_KNOWLEDGE",
    "案例分析": "CASE_ANALYSIS",
    "论文": "ESSAY",
    "基础知识": "BASIC_KNOWLEDGE",
    "应用技术": "APPLIED_TECHNOLOGY",
}

SUBJECT_TYPE_MAP = {
    "综合知识": "COMPREHENSIVE",
    "案例分析": "CASE",
    "论文": "ESSAY",
    "基础知识": "COMPREHENSIVE",
    "应用技术": "APPLICATION",
}

QUAL_SORT_ORDER = {
    "HIGH_PM": 10,
    "HIGH_SYSTEM_PLANNING_MANAGER": 20,
    "HIGH_SYSTEM_ANALYST": 30,
    "HIGH_SYSTEM_ARCHITECT": 40,
    "HIGH_NETWORK_PLANNER": 50,
    "MIDDLE_SOFTWARE_DESIGNER": 110,
    "MIDDLE_NETWORK_ENGINEER": 120,
    "MIDDLE_INFORMATION_SYSTEM_SUPERVISOR": 130,
    "MIDDLE_SYSTEM_INTEGRATION_PM": 140,
    "MIDDLE_EMBEDDED_SYSTEM_DESIGNER": 150,
    "MIDDLE_ECOMMERCE_DESIGNER": 160,
    "MIDDLE_DATABASE_SYSTEM_ENGINEER": 170,
    "MIDDLE_SOFTWARE_TESTER": 180,
    "MIDDLE_INFORMATION_SYSTEM_MANAGER": 190,
    "MIDDLE_INFORMATION_SECURITY_ENGINEER": 200,
    "MIDDLE_MULTIMEDIA_APPLICATION_DESIGNER": 210,
    "LOW_PROGRAMMER": 310,
    "LOW_NETWORK_ADMINISTRATOR": 320,
    "LOW_INFORMATION_SYSTEM_OPERATOR": 330,
    "LOW_INFORMATION_PROCESSING_TECHNICIAN": 340,
}

# ID 基址
QUAL_ID_BASE = 1       # 与 V5 对齐, HIGH_PM=1
SUBJECT_ID_BASE = 1    # 与 V5 对齐
CHAPTER_ID_BASE = 50000
SYLLABUS_ID_BASE = 60000


def escape_sql(value: str) -> str:
    return value.replace("\\", "\\\\").replace("'", "\\'")


def load_json():
    with open(JSON_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


def subject_sort_order(subject_name: str) -> int:
    """返回科目在所属资格内的 sort_order（1-based）。"""
    return ADVANCED_SUBJECT_ORDER.get(subject_name) or STANDARD_SUBJECT_ORDER.get(subject_name) or 0


def generate_qualification_inserts(qualifications: list) -> tuple[list[str], dict]:
    """生成 exam_qualification INSERT，返回 (SQL行列表, code→id映射)"""
    lines = []
    code_to_id = {}

    for idx, q in enumerate(qualifications):
        qual_id = QUAL_ID_BASE + idx
        code_to_id[q["code"]] = qual_id
        level = LEVEL_MAP[q["level"]]
        sort_order = QUAL_SORT_ORDER.get(q["code"], idx * 10 + 10)
        name = escape_sql(q["name"])
        lines.append(
            f"({qual_id}, '{q['code']}', '{name}', '{level}', 'ENABLED', {sort_order}, "
            f"{NOW}, {NOW}, NULL, NULL, 0)"
        )

    return lines, code_to_id


def generate_subject_inserts(qualifications: list, qual_code_to_id: dict) -> tuple[list[str], dict]:
    """生成 exam_subject INSERT，返回 (SQL行列表, (qual_id, subject_name)→subject_id映射)"""
    lines = []
    subject_map = {}  # (qualification_id, subject_name) -> subject_id
    next_id = SUBJECT_ID_BASE

    seen = set()
    for q in qualifications:
        qual_id = qual_code_to_id[q["code"]]
        for s in q["subjects"]:
            key = (qual_id, s["subjectName"])
            if key in seen:
                continue
            seen.add(key)
            sid = next_id
            next_id += 1
            subject_map[key] = sid

            subj_code = SUBJECT_CODE_MAP.get(s["subjectName"], s["subjectName"])
            subj_type = SUBJECT_TYPE_MAP.get(s["subjectName"], "COMPREHENSIVE")
            name = escape_sql(s["subjectName"])
            sort_order = subject_sort_order(s["subjectName"])

            lines.append(
                f"({sid}, {qual_id}, '{subj_code}', '{name}', '{subj_type}', {sort_order}, "
                f"'ENABLED', {NOW}, {NOW}, NULL, NULL, 0)"
            )

    return lines, subject_map


def flatten_with_ids(nodes: list, qual_id: int, subj_id: int, id_gen, parent_id=None) -> list[dict]:
    """递归展平树形节点，直接分配 ID 和 parent_id。"""
    result = []
    for idx, node in enumerate(nodes):
        node_id = next(id_gen)
        row = {
            "id": node_id,
            "qualification_id": qual_id,
            "subject_id": subj_id,
            "parent_id": parent_id,
            "code": node["code"],
            "name": node["name"],
            "level_no": node["levelNo"],
            "sort_order": idx + 1,
        }
        result.append(row)
        children = node.get("children", [])
        if children:
            result.extend(flatten_with_ids(children, qual_id, subj_id, id_gen, parent_id=node_id))
    return result


def generate_sql():
    data = load_json()
    qualifications = data["qualifications"]

    qual_lines, code_to_id = generate_qualification_inserts(qualifications)
    subj_lines, subject_map = generate_subject_inserts(qualifications, code_to_id)

    # 生成章节和考纲 ID
    chapter_id_gen = iter(range(CHAPTER_ID_BASE, CHAPTER_ID_BASE + 100000))
    syllabus_id_gen = iter(range(SYLLABUS_ID_BASE, SYLLABUS_ID_BASE + 100000))

    chapter_rows = []
    syllabus_rows = []

    for q in qualifications:
        qual_id = code_to_id[q["code"]]
        for s in q["subjects"]:
            subj_id = subject_map[(qual_id, s["subjectName"])]

            chapters = s.get("chapters", [])
            if chapters:
                chapter_rows.extend(
                    flatten_with_ids(chapters, qual_id, subj_id, chapter_id_gen)
                )

            syllabus = s.get("syllabus", [])
            if syllabus:
                syllabus_rows.extend(
                    flatten_with_ids(syllabus, qual_id, subj_id, syllabus_id_gen)
                )

    # ── 输出 SQL ──────────────────────────────────────

    sql = []
    sql.append("-- ============================================================")
    sql.append("-- V15__seed_all_qualifications.sql")
    sql.append("-- 完整资格+科目+章节+考纲种子数据（20 个软考资格）")
    sql.append(f"-- 生成时间: {datetime.now(timezone.utc).isoformat()}")
    sql.append("--")
    sql.append("-- 注意：本迁移会清除旧的资格/科目/知识树种子数据并重新插入。")
    sql.append("-- 不影响 sys_*/ai_*/q_* 等运行时产生的数据。")
    sql.append("-- ============================================================")
    sql.append("")

    # 1. 清理旧种子数据（按外键依赖顺序：子表 → 父表）
    sql.append("-- 清理旧的种子知识树数据")
    sql.append("DELETE FROM kp_chapter_node;")
    sql.append("DELETE FROM kp_syllabus_node;")
    sql.append("DELETE FROM kp_tag;")
    sql.append("DELETE FROM exam_subject;")
    sql.append("DELETE FROM exam_qualification;")
    sql.append("")

    # 2. 资格
    sql.append("-- ============================================================")
    sql.append("-- 资格考试（20 个）")
    sql.append("-- ============================================================")
    sql.append("INSERT INTO exam_qualification (id, qualification_code, qualification_name, level_code, status, sort_order, created_at, updated_at, created_by, updated_by, deleted)")
    sql.append("VALUES")
    sql.append(",\n".join(f"  {line}" for line in qual_lines) + ";")
    sql.append("")

    # 3. 科目
    sql.append("-- ============================================================")
    sql.append(f"-- 考试科目（{len(subj_lines)} 个：5 高级×3 + 11 中级×2 + 4 初级×2）")
    sql.append("-- ============================================================")
    sql.append("INSERT INTO exam_subject (id, qualification_id, subject_code, subject_name, subject_type, sort_order, status, created_at, updated_at, created_by, updated_by, deleted)")
    sql.append("VALUES")
    sql.append(",\n".join(f"  {line}" for line in subj_lines) + ";")
    sql.append("")

    # 4. 章节节点（分批插入，避免单条语句过长）
    sql.append("-- ============================================================")
    sql.append(f"-- 章节知识点（{len(chapter_rows)} 个节点）")
    sql.append("-- ============================================================")

    chapter_value_lines = []
    for row in chapter_rows:
        pid = str(row["parent_id"]) if row["parent_id"] is not None else "NULL"
        code = escape_sql(row["code"])
        name = escape_sql(row["name"])
        chapter_value_lines.append(
            f"  ({row['id']}, {row['qualification_id']}, {row['subject_id']}, {pid}, "
            f"'2026', NULL, '{code}', '{name}', {row['level_no']}, {row['sort_order']}, "
            f"'ENABLED', {NOW}, {NOW}, NULL, NULL, 0)"
        )

    BATCH_SIZE = 50
    for i in range(0, len(chapter_value_lines), BATCH_SIZE):
        batch = chapter_value_lines[i:i + BATCH_SIZE]
        sql.append("INSERT INTO kp_chapter_node (id, qualification_id, subject_id, parent_id, book_version, course_code, chapter_code, chapter_name, level_no, sort_order, status, created_at, updated_at, created_by, updated_by, deleted)")
        sql.append("VALUES")
        sql.append(",\n".join(batch) + ";")
    sql.append("")

    # 5. 考纲节点
    sql.append("-- ============================================================")
    sql.append(f"-- 考纲知识点（{len(syllabus_rows)} 个节点）")
    sql.append("-- ============================================================")

    syllabus_value_lines = []
    for row in syllabus_rows:
        pid = str(row["parent_id"]) if row["parent_id"] is not None else "NULL"
        code = escape_sql(row["code"])
        name = escape_sql(row["name"])
        syllabus_value_lines.append(
            f"  ({row['id']}, {row['qualification_id']}, {row['subject_id']}, {pid}, "
            f"'2026', '{code}', '{name}', {row['level_no']}, {row['sort_order']}, "
            f"'ENABLED', {NOW}, {NOW}, NULL, NULL, 0)"
        )

    for i in range(0, len(syllabus_value_lines), BATCH_SIZE):
        batch = syllabus_value_lines[i:i + BATCH_SIZE]
        sql.append("INSERT INTO kp_syllabus_node (id, qualification_id, subject_id, parent_id, syllabus_version, node_code, node_name, level_no, sort_order, status, created_at, updated_at, created_by, updated_by, deleted)")
        sql.append("VALUES")
        sql.append(",\n".join(batch) + ";")

    sql.append("")

    return "\n".join(sql)


if __name__ == "__main__":
    import sys
    sys.stdout.reconfigure(encoding="utf-8")
    print(generate_sql())
