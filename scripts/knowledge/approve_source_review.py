"""Record explicit human approval for reviewed OCR source subfragments."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--index", type=Path, default=Path("knowledge-base/index/textbook-source-subfragments.json"))
    parser.add_argument("--manifest", type=Path, default=Path("knowledge-base/sources/信息系统项目管理师第四版教材-来源子片段清单.md"))
    parser.add_argument("--reviewer", default="用户确认")
    parser.add_argument("--confirm", action="store_true", help="required to change NEEDS_REVIEW to APPROVED")
    args = parser.parse_args()
    if not args.confirm:
        raise SystemExit("Refusing to change source states without --confirm")

    data = json.loads(args.index.read_text(encoding="utf-8"))
    rows = data["fragments"]
    pending = [row for row in rows if row.get("quality_status") == "NEEDS_REVIEW"]
    if not pending:
        print("No NEEDS_REVIEW source fragments found")
        return
    for row in pending:
        row["quality_status"] = "APPROVED"
        row["reviewer"] = args.reviewer
        row["reviewed_at"] = "2026-07-25"
    args.index.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.manifest.exists():
        text = args.manifest.read_text(encoding="utf-8")
        args.manifest.write_text(text.replace("| NEEDS_REVIEW |", "| APPROVED |"), encoding="utf-8")
    report = Path("knowledge-base/review/source-review-record.json")
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps({"reviewer": args.reviewer, "reviewed_at": "2026-07-25", "approved_count": sum(row.get("quality_status") == "APPROVED" for row in rows),
                                  "remaining_needs_review": sum(row.get("quality_status") == "NEEDS_REVIEW" for row in rows)},
                                 ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"approved": len(pending), "remaining_needs_review": sum(row.get("quality_status") == "NEEDS_REVIEW" for row in rows)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
