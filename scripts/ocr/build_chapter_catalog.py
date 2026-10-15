"""Recover chapter/page catalogs from page-addressable OCR Markdown."""

from __future__ import annotations

import argparse
import re
from collections import OrderedDict
from pathlib import Path


PAGE_PATTERN = re.compile(r"^## Page (\d+)$")
TEXTBOOK_CHAPTER = re.compile(r"^第\s*(\d+)\s*章\s*(.+)$")
ESSAY_SECTION = re.compile(r"^(\d+)\s*([分析设计组织].+)$")


def page_lines(path: Path) -> list[tuple[int, str]]:
    page = 0
    rows: list[tuple[int, str]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        marker = PAGE_PATTERN.match(line)
        if marker:
            page = int(marker.group(1))
        elif page:
            rows.append((page, line.strip()))
    return rows


def textbook_catalog(rows: list[tuple[int, str]]) -> list[tuple[str, int]]:
    chapters: OrderedDict[int, tuple[str, int]] = OrderedDict()
    body_started = False
    for page, line in rows:
        if page <= 10:
            continue
        match = TEXTBOOK_CHAPTER.match(line)
        if not match:
            continue
        number = int(match.group(1))
        title = re.sub(r"\s+", "", match.group(2))
        # The OCR table of contents can extend past page 10.  The first body
        # chapter is chapter 1, so ignore all apparent headings before it.
        if number == 1:
            body_started = True
        if not body_started:
            continue
        if number not in chapters and title:
            chapters[number] = (f"第{number}章 {title}", page)
    return list(chapters.values())


def essay_catalog(rows: list[tuple[int, str]]) -> list[tuple[str, int]]:
    sections: OrderedDict[int, tuple[str, int]] = OrderedDict()
    for page, line in rows:
        if page <= 2:
            continue
        match = ESSAY_SECTION.match(line)
        if not match:
            continue
        number = int(match.group(1))
        title = re.sub(r"\s+", "", match.group(2))
        if number not in (1, 2, 3):
            continue
        if number not in sections and title:
            sections[number] = (f"{number} {title}", page)
    return list(sections.values())


def render_catalog(title: str, source_note: str, entries: list[tuple[str, int]]) -> str:
    lines = [
        "---",
        f"title: {title}",
        "type: source-catalog",
        "status: pending-review",
        f"source: \"[[{source_note}]]\"",
        "---",
        "",
        f"# {title}",
        "",
        "页码为 OCR PDF 页码，后续章节审核以原始 PDF 页面和教材印刷页码交叉确认。",
        "",
        "| 章节 | OCR 起始页 | OCR 结束页 |",
        "|---|---:|---:|",
    ]
    for index, (chapter, start) in enumerate(entries):
        end = entries[index + 1][1] - 1 if index + 1 < len(entries) else "文档末页"
        lines.append(f"| {chapter} | {start} | {end} |")
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--kind", choices=("textbook", "essay"), required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source-note", required=True)
    args = parser.parse_args()

    rows = page_lines(args.input)
    entries = textbook_catalog(rows) if args.kind == "textbook" else essay_catalog(rows)
    if not entries:
        raise RuntimeError("No chapter headings found")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    title = "信息系统项目管理师（第四版）章节目录" if args.kind == "textbook" else "论文基础章节目录"
    args.output.write_text(render_catalog(title, args.source_note, entries), encoding="utf-8", newline="\n")
    print(f"Wrote {len(entries)} entries to {args.output}")


if __name__ == "__main__":
    main()
