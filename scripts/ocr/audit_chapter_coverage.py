"""Compare OCR-detected textbook chapters with generated source fragments."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

from build_chapter_catalog import page_lines, textbook_catalog


CHAPTER_NUMBER = re.compile(r"第\s*(\d+)\s*章")


def chapter_number(title: str) -> int | None:
    match = CHAPTER_NUMBER.search(title)
    return int(match.group(1)) if match else None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ocr", type=Path, required=True)
    parser.add_argument("--fragments", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    catalog = textbook_catalog(page_lines(args.ocr))
    expected = {
        number: {"chapter": title, "start_page": page}
        for title, page in catalog
        if (number := chapter_number(title)) is not None
    }
    fragments = json.loads(args.fragments.read_text(encoding="utf-8"))["fragments"]
    covered_numbers = {
        number
        for item in fragments
        if (number := chapter_number(item.get("chapter", ""))) is not None
    }
    missing = [expected[number] for number in sorted(expected.keys() - covered_numbers)]
    report = {
        "expected_chapters": len(expected),
        "covered_chapters": len(expected.keys() & covered_numbers),
        "missing_chapters": missing,
        "coverage_complete": not missing,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(report, ensure_ascii=False, indent=2),
        encoding="utf-8",
        newline="\n",
    )
    print(json.dumps(report, ensure_ascii=False))
    if missing:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
