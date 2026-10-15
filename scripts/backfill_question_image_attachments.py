from __future__ import annotations

import argparse
import os
import re
import subprocess
from collections import defaultdict
from pathlib import Path


IMAGE_FILE = re.compile(r"^[0-9A-Fa-f]{16}\.(?:png|jpe?g|gif|webp)$")
SOURCE_ID = re.compile(r"sourceQuestionId:([^:]+)$")
IMAGE_LABEL = "\u56fe\u7247"


def mysql(mysql: str, database: str, query: str) -> str:
    completed = subprocess.run(
        [mysql, "--protocol=tcp", "--host=127.0.0.1", "--port=33307", "--user=aiexam_dev",
         "--default-character-set=utf8mb4", "--batch", "--skip-column-names", database],
        input=query,
        capture_output=True,
        text=True,
        encoding="utf-8",
        env={**os.environ, "MYSQL_PWD": "dev-only-pass"},
    )
    if completed.returncode:
        raise RuntimeError(completed.stderr.strip() or "mysql command failed")
    return completed.stdout


def cell_text(value: object) -> str:
    if value is None:
        return ""
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value).strip()


def image_names(value: str) -> list[str]:
    result: list[str] = []
    for entry in re.split(r"[\r\n]+", value):
        name = entry.strip().replace("\\", "/").rsplit("/", 1)[-1]
        if IMAGE_FILE.fullmatch(name) and name not in result:
            result.append(name)
    return result


def sql_text(value: str) -> str:
    return value.replace("'", "''")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--images", type=Path, required=True)
    parser.add_argument("--mysql", default=r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe")
    parser.add_argument("--database", default="ai_exam_platform")
    args = parser.parse_args()

    import openpyxl

    worksheet = openpyxl.load_workbook(args.input, read_only=True, data_only=True).active
    rows = worksheet.iter_rows(values_only=True)
    headers = [cell_text(value) for value in next(rows)]
    id_index = headers.index("\u9898\u76eeID")
    image_index = headers.index("\u9898\u76ee\u56fe\u7247")
    images_by_source_id: dict[str, list[str]] = {}
    missing_files: set[str] = set()
    for row in rows:
        source_id = cell_text(row[id_index] if id_index < len(row) else None)
        names = image_names(cell_text(row[image_index] if image_index < len(row) else None))
        names = [name for name in names if (args.images / name).is_file()]
        if source_id and names:
            images_by_source_id[source_id] = names
        elif source_id:
            for name in image_names(cell_text(row[image_index] if image_index < len(row) else None)):
                if not (args.images / name).is_file():
                    missing_files.add(name)

    rows_text = mysql(args.mysql, args.database, """
        SELECT s.id, v.source_locator
        FROM q_question_stem s
        JOIN q_question_version v ON v.id = s.question_version_id
        JOIN q_question q ON q.id = v.question_id
        WHERE q.current_published_version_id = v.id
          AND q.status = 'PUBLISHED' AND q.deleted = 0
          AND v.status = 'PUBLISHED' AND v.deleted = 0
    """)
    stems_by_source_id: defaultdict[str, list[int]] = defaultdict(list)
    for line in rows_text.splitlines():
        stem_id, locator = line.split("\t", 1)
        match = SOURCE_ID.search(locator)
        if match:
            stems_by_source_id[match.group(1)].append(int(stem_id))

    updates: list[str] = []
    mapped_stems = 0
    for source_id, names in images_by_source_id.items():
        marker_sql = ", ".join(
            "CASE WHEN content LIKE '%[{label}:{name}]%' THEN '' ELSE '\\n[{label}:{name}]' END".format(
                label=IMAGE_LABEL, name=name
            )
            for name in names
        )
        attachment = f"/api/v1/public/question-assets/{names[0]}"
        for stem_id in stems_by_source_id.get(source_id, []):
            updates.append(
                "UPDATE q_question_stem SET attachment_url=COALESCE(NULLIF(attachment_url,''),'{url}'), "
                "content=CONCAT(content, {markers}) WHERE id={stem_id};".format(
                    url=sql_text(attachment), markers=marker_sql, stem_id=stem_id
                )
            )
            mapped_stems += 1
    if updates:
        mysql(args.mysql, args.database, "START TRANSACTION;\n" + "\n".join(updates) + "\nCOMMIT;")
    print({
        "sourceQuestionsWithImages": len(images_by_source_id),
        "publishedStemsUpdated": mapped_stems,
        "unmatchedSourceQuestionIds": len(set(images_by_source_id) - set(stems_by_source_id)),
        "missingImageFiles": sorted(missing_files),
    })


if __name__ == "__main__":
    main()
