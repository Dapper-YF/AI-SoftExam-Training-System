"""Derive reviewable semantic source subfragments from OCR parent fragments."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path

from build_source_fragments import PageContent, read_pages


SUBHEADING = re.compile(r"^(?:\d+\.\d+(?:\.\d+)?|第\d+章)\s*(.+)$")
SENTENCE_END = re.compile(r"(?<=[。！？；])")
PAGE_NOISE = re.compile(r"^(?:Page\s+\d+|第\s*\d+\s*页)$", re.IGNORECASE)
MIN_FRAGMENT_CHARS = 600


@dataclass
class SourceSubfragment:
    fragment_id: str
    parent_fragment_id: str
    source_id: str
    content_kind: str
    chapter: str
    section: str
    heading_path: list[str]
    source_pages: str
    start_page: int
    end_page: int
    start_line: int
    end_line: int
    average_confidence: float | None
    minimum_confidence: float | None
    quality_status: str
    content_hash: str
    char_count: int
    content: str


def select_lines(pages: list[PageContent], parent: dict) -> list[tuple[int, int, str, float | None]]:
    selected: list[tuple[int, int, str, float | None]] = []
    for page in pages:
        if not parent["start_page"] <= page.page <= parent["end_page"]:
            continue
        first = parent["start_line"] if page.page == parent["start_page"] else 0
        last = parent["end_line"] if page.page == parent["end_page"] else len(page.lines)
        for line_no, line in enumerate(page.lines[first:last], start=first):
            selected.append((page.page, line_no, line, page.confidence))
    return selected


def split_long(
    block: list[tuple[int, int, str, float | None]],
    limit: int,
    overlap: int,
) -> list[list[tuple[int, int, str, float | None]]]:
    text = "\n".join(line[2] for line in block).strip()
    if len(text) <= limit:
        return [block]
    pieces: list[list[tuple[int, int, str, float | None]]] = []
    current: list[tuple[int, int, str, float | None]] = []
    size = 0
    for entry in block:
        line_size = len(entry[2])
        if current and size + line_size > limit:
            pieces.append(current)
            current, size = [], 0
        if line_size > limit:
            sentences = [value for value in SENTENCE_END.split(entry[2]) if value]
            for sentence in sentences:
                if len(sentence) > limit:
                    if current:
                        pieces.append(current)
                        current, size = [], 0
                    for index in range(0, len(sentence), limit):
                        pieces.append([(entry[0], entry[1], sentence[index : index + limit], entry[3])])
                    continue
                if current and size + len(sentence) > limit:
                    pieces.append(current)
                    current, size = [], 0
                current.append((entry[0], entry[1], sentence, entry[3]))
                size += len(sentence)
        else:
            current.append(entry)
            size += line_size
    if current:
        pieces.append(current)
    for index in range(1, len(pieces)):
        carried: list[tuple[int, int, str, float | None]] = []
        carried_size = 0
        for previous in reversed(pieces[index - 1]):
            if carried_size + len(previous[2]) + len(
                "\n".join(item[2] for item in pieces[index])
            ) > limit:
                break
            carried.insert(0, previous)
            carried_size += len(previous[2])
            if carried_size >= overlap:
                break
        if carried:
            pieces[index] = carried + pieces[index]
    return pieces


def semantic_blocks(
    lines: list[tuple[int, int, str, float | None]],
    chapter: str,
    section: str,
    limit: int,
    overlap: int,
) -> list[tuple[list[str], list[tuple[int, int, str, float | None]]]]:
    heading_path = [chapter, section]
    current: list[tuple[int, int, str, float | None]] = []
    blocks: list[tuple[list[str], list[tuple[int, int, str, float | None]]]] = []

    def flush() -> None:
        nonlocal current
        useful = [
            entry for entry in current
            if entry[2].strip() and not PAGE_NOISE.match(entry[2].strip())
        ]
        if useful:
            for piece in split_long(useful, limit, overlap):
                text = "\n".join(entry[2] for entry in piece).strip()
                if len(text) <= limit:
                    blocks.append((heading_path.copy(), piece))
                    continue
                # OCR diagrams occasionally arrive as one logical line without
                # sentence boundaries. This is the only character fallback.
                for index in range(0, len(text), limit):
                    blocks.append((heading_path.copy(), [(piece[0][0], piece[0][1], text[index : index + limit], piece[0][3])]))
        current = []

    for entry in lines:
        text = entry[2].strip()
        if not text:
            flush()
            continue
        heading = SUBHEADING.match(text)
        if heading and len(text) < 80:
            flush()
            heading_path = [chapter, section, text]
            continue
        current.append((entry[0], entry[1], text, entry[3]))
    flush()
    cleaned: list[tuple[list[str], list[tuple[int, int, str, float | None]]]] = []
    for path, block in blocks:
        if len("\n".join(item[2] for item in block).strip()) == 1:
            if cleaned and len("\n".join(item[2] for item in cleaned[-1][1])) + 2 <= limit:
                cleaned[-1][1].extend(block)
            continue
        cleaned.append((path, block))

    return cleaned


def create_subfragments(
    pages: list[PageContent],
    parents: list[dict],
    threshold: float,
    limit: int,
    overlap: int,
) -> list[SourceSubfragment]:
    result: list[SourceSubfragment] = []
    for parent in parents:
        lines = select_lines(pages, parent)
        for index, (heading_path, block) in enumerate(
            semantic_blocks(lines, parent["chapter"], parent["section"], limit, overlap),
            start=1,
        ):
            content = "\n".join(entry[2] for entry in block).strip()
            confidences = [entry[3] for entry in block if entry[3] is not None]
            minimum = round(min(confidences), 4) if confidences else None
            result.append(
                SourceSubfragment(
                    fragment_id=f"{parent['fragment_id']}:SEG-{index:03d}",
                    parent_fragment_id=parent["fragment_id"],
                    source_id=parent["source_id"],
                    content_kind="SOURCE",
                    chapter=parent["chapter"],
                    section=parent["section"],
                    heading_path=heading_path,
                    source_pages=f"OCR PDF 第{block[0][0]}-{block[-1][0]}页",
                    start_page=block[0][0],
                    end_page=block[-1][0],
                    start_line=block[0][1],
                    end_line=block[-1][1] + 1,
                    average_confidence=round(sum(confidences) / len(confidences), 4) if confidences else None,
                    minimum_confidence=minimum,
                    quality_status="QUALIFIED" if minimum is not None and minimum >= threshold else "NEEDS_REVIEW",
                    content_hash=hashlib.sha256(content.encode("utf-8")).hexdigest(),
                    char_count=len(content),
                    content=content,
                )
            )
    for index, fragment in enumerate(result):
        if fragment.char_count >= MIN_FRAGMENT_CHARS:
            continue
        candidates = [
            (result[neighbor_index], False)
            for neighbor_index in range(index + 1, len(result))
            if result[neighbor_index].source_id == fragment.source_id
        ]
        candidates.extend(
            (result[neighbor_index], True)
            for neighbor_index in range(index - 1, -1, -1)
            if result[neighbor_index].source_id == fragment.source_id
        )
        for neighbor, prepend in candidates:
            available = limit - fragment.char_count - 2
            needed = MIN_FRAGMENT_CHARS - fragment.char_count
            take = min(available, max(needed, overlap))
            if take <= 0:
                continue
            excerpt = neighbor.content[-take:] if prepend else neighbor.content[:take]
            fragment.content = f"{excerpt}\n\n{fragment.content}" if prepend else f"{fragment.content}\n\n{excerpt}"
            fragment.char_count = len(fragment.content)
            fragment.content_hash = hashlib.sha256(fragment.content.encode("utf-8")).hexdigest()
            fragment.start_page = min(fragment.start_page, neighbor.start_page)
            fragment.end_page = max(fragment.end_page, neighbor.end_page)
            fragment.source_pages = f"OCR PDF 第{fragment.start_page}-{fragment.end_page}页"
            if fragment.char_count >= MIN_FRAGMENT_CHARS:
                break
    return result


def render_manifest(fragments: list[SourceSubfragment]) -> str:
    lines = [
        "---",
        "title: 信息系统项目管理师（第四版）来源子片段清单",
        "type: source-subfragment-catalog",
        "status: pending-review",
        'source: "[[信息系统项目管理师第四版教材]]"',
        "---",
        "",
        "# 信息系统项目管理师（第四版）来源子片段清单",
        "",
        "子片段按标题和段落语义切分；低 OCR 置信度片段标记为 `NEEDS_REVIEW`，不能直接发布。",
        "",
        "| 子片段 ID | 父片段 | 章节/小节 | OCR 页码 | 置信度 | 质量状态 | 字符数 |",
        "|---|---|---|---|---:|---|---:|",
    ]
    for item in fragments:
        confidence = f"{item.minimum_confidence:.4f}" if item.minimum_confidence is not None else "-"
        lines.append(
            f"| {item.fragment_id} | {item.parent_fragment_id} | {item.section} | {item.source_pages} | "
            f"{confidence} | {item.quality_status} | {item.char_count} |"
        )
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ocr", type=Path, required=True)
    parser.add_argument("--parents", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--index", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--confidence-threshold", type=float, default=0.90)
    parser.add_argument("--max-chars", type=int, default=1200)
    parser.add_argument("--overlap-chars", type=int, default=100)
    args = parser.parse_args()

    parents = json.loads(args.parents.read_text(encoding="utf-8"))["fragments"]
    if not 80 <= args.overlap_chars <= 150:
        raise ValueError("--overlap-chars must be between 80 and 150")
    fragments = create_subfragments(
        read_pages(args.ocr),
        parents,
        args.confidence_threshold,
        args.max_chars,
        args.overlap_chars,
    )
    if not fragments:
        raise RuntimeError("No source subfragments found")
    ids = [fragment.fragment_id for fragment in fragments]
    if len(ids) != len(set(ids)):
        raise RuntimeError("Duplicate source subfragment id")
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.index.parent.mkdir(parents=True, exist_ok=True)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(render_manifest(fragments), encoding="utf-8", newline="\n")
    args.index.write_text(json.dumps({"fragments": [asdict(item) for item in fragments]}, ensure_ascii=False, indent=2), encoding="utf-8")
    report = {
        "parent_fragments": len(parents),
        "source_subfragments": len(fragments),
        "qualified": sum(item.quality_status == "QUALIFIED" for item in fragments),
        "needs_review": sum(item.quality_status == "NEEDS_REVIEW" for item in fragments),
        "max_char_count": max(item.char_count for item in fragments),
        "duplicate_fragment_ids": False,
        "confidence_threshold": args.confidence_threshold,
        "overlap_chars": args.overlap_chars,
        "shorter_than_600": sum(item.char_count < 600 for item in fragments),
    }
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
