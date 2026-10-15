"""Build page-traceable textbook source fragments from OCR Markdown.

The Markdown manifest is intended for Obsidian review. The JSON output keeps
the OCR content for staging retrieval and remains in the ignored index folder.
"""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path


PAGE = re.compile(r"^## Page (\d+)$")
CONFIDENCE = re.compile(r"^- confidence: ([0-9.]+)$")
CHAPTER = re.compile(r"^第\s*(\d+)\s*章\s*(.+)$")
SECTION = re.compile(r"^(\d+)\.(\d+)(?:\.(\d+))?\s*(.+)$")
SECTION_ONLY = re.compile(r"^(\d+)\.(\d+)$")


@dataclass
class PageContent:
    page: int
    confidence: float | None
    lines: list[str]


@dataclass
class SourceFragment:
    fragment_id: str
    source_id: str
    source_type: str
    chapter: str
    section: str
    source_pages: str
    start_page: int
    end_page: int
    start_line: int
    end_line: int
    page_count: int
    average_confidence: float | None
    minimum_confidence: float | None
    source_path: str
    content: str


def read_pages(path: Path) -> list[PageContent]:
    pages: list[PageContent] = []
    current: PageContent | None = None
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        marker = PAGE.match(raw_line)
        if marker:
            current = PageContent(page=int(marker.group(1)), confidence=None, lines=[])
            pages.append(current)
            continue
        if current is None:
            continue
        confidence = CONFIDENCE.match(raw_line)
        if confidence:
            current.confidence = float(confidence.group(1))
        elif not raw_line.startswith("- source_page:"):
            current.lines.append(raw_line)
    return pages


def find_chapters(pages: list[PageContent]) -> list[tuple[int, str, int]]:
    chapters: dict[int, tuple[str, int]] = {}
    body_started = False
    for page in pages:
        # OCR pages 1-10 contain the table of contents. The body begins at
        # chapter 1, after which the remaining chapter headings are valid.
        if page.page <= 10:
            continue
        for line in page.lines:
            match = CHAPTER.match(line.strip())
            if not match:
                continue
            number = int(match.group(1))
            title = re.sub(r"\s+", "", match.group(2))
            if number == 1:
                body_started = True
            if body_started and number not in chapters:
                chapters[number] = (f"第{number}章 {title}", page.page)
    return [(number, title, start) for number, (title, start) in sorted(chapters.items())]


def pages_between(pages: list[PageContent], start: int, end: int) -> list[PageContent]:
    return [page for page in pages if start <= page.page <= end]


def make_fragment(
    source_id: str,
    chapter: str,
    section: str,
    start: int,
    end: int,
    pages: list[PageContent],
    sequence: int,
    source_path: str,
    start_line: int = 0,
    end_anchor: tuple[int, int] | None = None,
) -> SourceFragment:
    selected = pages_between(pages, start, end)
    confidence_values = [page.confidence for page in selected if page.confidence is not None]
    content_pages: list[str] = []
    for page in selected:
        first = start_line if page.page == start else 0
        last = len(page.lines)
        if end_anchor and page.page == end_anchor[0]:
            last = min(last, end_anchor[1])
        text = "\n".join(page.lines[first:last]).strip()
        if text:
            content_pages.append(text)
    content = "\n\n".join(content_pages).strip()
    final_line = end_anchor[1] if end_anchor and end_anchor[0] == end else len(selected[-1].lines)
    return SourceFragment(
        fragment_id=f"{source_id}:SEC-{sequence:03d}",
        source_id=source_id,
        source_type="textbook-section",
        chapter=chapter,
        section=section,
        source_pages=f"OCR PDF 第{start}-{end}页",
        start_page=start,
        end_page=end,
        start_line=start_line,
        end_line=final_line,
        page_count=end - start + 1,
        average_confidence=round(sum(confidence_values) / len(confidence_values), 4) if confidence_values else None,
        minimum_confidence=round(min(confidence_values), 4) if confidence_values else None,
        source_path=source_path,
        content=content,
    )


def build_fragments(pages: list[PageContent], source_id: str, source_path: str) -> list[SourceFragment]:
    chapters = find_chapters(pages)
    fragments: list[SourceFragment] = []
    sequence = 1
    for index, (chapter_number, chapter_title, chapter_start) in enumerate(chapters):
        chapter_end = chapters[index + 1][2] - 1 if index + 1 < len(chapters) else pages[-1].page
        sections: list[tuple[str, int, int]] = []
        seen: set[str] = set()
        for page in pages_between(pages, chapter_start, chapter_end):
            for line_index, line in enumerate(page.lines):
                cleaned = line.strip()
                match = SECTION.match(cleaned)
                number: str | None = None
                title = ""
                if match and int(match.group(1)) == chapter_number and not match.group(3):
                    number = f"{match.group(1)}.{match.group(2)}"
                    title = re.sub(r"\s+", "", match.group(4))
                    # OCR can misread a following third-level number as the title.
                    if title.startswith(".") or not re.search(r"[\u4e00-\u9fff]", title):
                        number = None
                else:
                    number_only = SECTION_ONLY.match(cleaned)
                    if number_only and int(number_only.group(1)) == chapter_number:
                        number = f"{number_only.group(1)}.{number_only.group(2)}"
                        for candidate in page.lines[line_index + 1 :]:
                            candidate = candidate.strip()
                            if not candidate:
                                continue
                            if SECTION.match(candidate) or SECTION_ONLY.match(candidate):
                                break
                            if re.search(r"[\u4e00-\u9fff]", candidate):
                                title = re.sub(r"\s+", "", candidate)
                            break
                        if not title:
                            number = None
                if number and number not in seen:
                    sections.append((f"{number} {title}", page.page, line_index))
                    seen.add(number)
        if not sections:
            fragments.append(make_fragment(source_id, chapter_title, "未识别一级小节", chapter_start, chapter_end, pages, sequence, source_path))
            sequence += 1
            continue
        for section_index, (section_title, section_start, section_start_line) in enumerate(sections):
            if section_index + 1 < len(sections):
                next_page, next_line = sections[section_index + 1][1:]
                section_end = next_page if next_line else next_page - 1
                section_end = max(section_start, section_end)
                end_anchor = (next_page, next_line)
            else:
                section_end = chapter_end
                end_anchor = None
            fragments.append(
                make_fragment(
                    source_id,
                    chapter_title,
                    section_title,
                    section_start,
                    section_end,
                    pages,
                    sequence,
                    source_path,
                    section_start_line,
                    end_anchor,
                )
            )
            sequence += 1
    return fragments


def render_manifest(fragments: list[SourceFragment], source_note: str) -> str:
    lines = [
        "---",
        "title: 信息系统项目管理师（第四版）来源片段清单",
        "type: source-fragment-catalog",
        "status: pending-review",
        f'source: "[[{source_note}]]"',
        "source_id: PM-TEXTBOOK-4TH-0502",
        "---",
        "",
        "# 信息系统项目管理师（第四版）来源片段清单",
        "",
        "按教材正文的章节和一级小节切分。正文保存在对应 OCR 文件中；本清单用于审核、页码追溯和构建本地来源索引。",
        "",
        "| 片段 ID | 章节 | 小节 | OCR 页码 | 页数 | 平均置信度 | 最低置信度 |",
        "|---|---|---|---|---:|---:|---:|",
    ]
    for fragment in fragments:
        avg = f"{fragment.average_confidence:.4f}" if fragment.average_confidence is not None else "-"
        low = f"{fragment.minimum_confidence:.4f}" if fragment.minimum_confidence is not None else "-"
        lines.append(
            f"| {fragment.fragment_id} | {fragment.chapter} | {fragment.section} | "
            f"{fragment.source_pages} | {fragment.page_count} | {avg} | {low} |"
        )
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--index", type=Path, required=True)
    parser.add_argument("--source-id", default="PM-TEXTBOOK-4TH-0502")
    parser.add_argument("--source-note", default="信息系统项目管理师第四版教材")
    args = parser.parse_args()

    pages = read_pages(args.input)
    fragments = build_fragments(pages, args.source_id, args.input.as_posix())
    if not fragments:
        raise RuntimeError("No source fragments found")
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.index.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(render_manifest(fragments, args.source_note), encoding="utf-8", newline="\n")
    args.index.write_text(json.dumps({"fragments": [asdict(item) for item in fragments]}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Built {len(fragments)} source fragments from {len(pages)} OCR pages")


if __name__ == "__main__":
    main()
