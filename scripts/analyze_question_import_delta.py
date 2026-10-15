from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
from collections import Counter
from pathlib import Path


def mysql_rows(mysql: str, database: str, query: str) -> list[tuple[str, ...]]:
    command = [mysql, "--protocol=tcp", "--host=127.0.0.1", "--port=33307",
               "--user=aiexam_dev", "--default-character-set=utf8mb4", "--batch",
               "--skip-column-names", database, "-e", query]
    completed = subprocess.run(command, check=True, capture_output=True, text=True,
                               encoding="utf-8", env={**os.environ, "MYSQL_PWD": "dev-only-pass"})
    return [tuple(line.split("\t")) for line in completed.stdout.splitlines() if line]


def fingerprint(question: dict[str, object]) -> str:
    def clean(value: object) -> str:
        value = re.sub(r"第\s*\d+\s*题\s*[:：]?", "", str(value or ""), flags=re.IGNORECASE)
        return re.sub(r"[^0-9a-z\u3400-\u9fff]+", "", value.lower())

    parts = [clean(question.get("questionType"))]
    for stem in question.get("stems") or []:
        if isinstance(stem, dict):
            parts.append(clean(stem.get("content")))
    for sub in question.get("subQuestions") or []:
        if not isinstance(sub, dict):
            continue
        parts.append(clean(sub.get("content")))
    return "\x1f".join(parts)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--canonical", type=Path, required=True)
    parser.add_argument("--mysql", default=r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe")
    parser.add_argument("--database", default="ai_exam_platform")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    existing_rows = mysql_rows(args.mysql, args.database, """SELECT q.qualification_id, q.subject_id,
        q.question_type, qv.content_json, qv.external_key, qv.content_hash, qv.source_locator
        FROM q_question_version qv JOIN q_question q ON q.id=qv.question_id WHERE qv.deleted=0""")
    scope_rows = mysql_rows(args.mysql, args.database, """SELECT q.id, q.qualification_code,
        s.id, s.subject_code FROM exam_qualification q JOIN exam_subject s
        ON s.qualification_id=q.id WHERE q.deleted=0 AND s.deleted=0""")
    scope_names = {(row[0], row[2]): (row[1], row[3]) for row in scope_rows}
    existing_keys = {row[4] for row in existing_rows if len(row) > 4 and row[4]}
    existing_hashes = {row[5] for row in existing_rows if len(row) > 5 and row[5]}
    existing_fingerprints: set[tuple[str, str, str, str]] = set()
    existing_source_ids: set[tuple[str, str, str, str]] = set()
    for row in existing_rows:
        if len(row) < 4 or (row[0], row[1]) not in scope_names:
            continue
        try:
            existing_fingerprints.add((*scope_names[(row[0], row[1])], row[2], fingerprint(json.loads(row[3]))))
        except (json.JSONDecodeError, TypeError):
            pass
        match = re.search(r"sourceQuestionId:([^:]+)$", row[6] if len(row) > 6 else "")
        if match:
            existing_source_ids.add((*scope_names[(row[0], row[1])], row[2], match.group(1)))

    total = eligible = exact_key = exact_hash = existing_content = new = 0
    distributions: Counter[tuple[str, str, str, str]] = Counter()
    new_records: list[dict[str, object]] = []
    for line in args.canonical.read_text(encoding="utf-8").splitlines():
        record = json.loads(line)
        total += 1
        if not record.get("quality", {}).get("importEligible"):
            continue
        eligible += 1
        question = record["question"]
        scope = (record["qualificationCode"], record["subjectCode"], question["questionType"])
        source_id = record.get("sourceTrace", {}).get("sourceQuestionId")
        source_key = (*scope, source_id) if source_id else None
        if question["externalKey"] in existing_keys:
            exact_key += 1; status = "EXISTING_EXTERNAL_KEY"
        elif question["contentHash"] in existing_hashes:
            exact_hash += 1; status = "EXISTING_CONTENT_HASH"
        elif (*scope, fingerprint(question)) in existing_fingerprints:
            existing_content += 1; status = "EXISTING_CONTENT"
        elif source_key in existing_source_ids:
            existing_content += 1; status = "EXISTING_SOURCE_ID"
        else:
            new += 1; status = "NEW"; new_records.append(record)
        distributions[(*scope, status)] += 1

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({
        "databaseVersionRows": len(existing_rows), "sourceRecords": total,
        "eligibleRecords": eligible, "existingExternalKey": exact_key,
        "existingContentHash": exact_hash, "existingContent": existing_content,
        "newRecords": new,
        "distributions": [{"qualificationCode": q, "subjectCode": s, "questionType": t,
                            "status": status, "count": count}
                           for (q, s, t, status), count in sorted(distributions.items())],
        "newRecordsPath": str(args.output.with_name("new-question-records.jsonl")),
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    with args.output.with_name("new-question-records.jsonl").open("w", encoding="utf-8", newline="\n") as handle:
        for record in new_records:
            handle.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(json.dumps({"total": total, "eligible": eligible, "existingExternalKey": exact_key,
                      "existingContentHash": exact_hash, "existingContent": existing_content,
                      "new": new}, ensure_ascii=False))


if __name__ == "__main__":
    main()
