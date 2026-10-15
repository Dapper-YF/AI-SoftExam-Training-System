"""Build and evaluate a local BM25 index from Obsidian knowledge cards.

This is a staging evaluator for Markdown knowledge cards. It keeps heading
context with each paragraph and only splits text exceeding the configured
limit at Chinese sentence boundaries.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path

import yaml


FRONTMATTER = re.compile(r"\A---\s*\n(.*?)\n---\s*\n", re.DOTALL)
HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*$")
SENTENCE_END = re.compile(r"(?<=[。！？；])")
CHINESE = re.compile(r"[\u4e00-\u9fff]+")
LATIN = re.compile(r"[a-z0-9]+", re.IGNORECASE)
CONCEPT_ALIASES = {
    "PM-COST-FOUNDATION": ("项目成本管理包括", "成本管理包含", "成本管理包括", "成本管理的过程"),
    "PM-QUALITY-FOUNDATION": ("项目质量管理包括", "三个过程", "三个过程分别", "质量管理包括"),
    "PM-QUALITY-MANAGE": ("管理质量关注过程", "管理质量"),
    "PM-QUALITY-CONTROL": ("控制质量关注结果", "控制质量"),
    "PM-QUALITY-VERIFIED-DELIVERABLES": ("核实的可交付成果", "核实可交付成果"),
    "PM-STAKEHOLDER-MONITOR": ("持续监督", "监督干系人参与"),
    "PM-STAKEHOLDER-ENGAGEMENT": ("参与度评估矩阵",),
    "PM-STAKEHOLDER-IDENTIFY": ("干系人登记册",),
    "PM-CONFIG-AUDIT": ("配置核实", "配置审计"),
    "PM-INTEGRATION-CHANGE-CONTROL": ("实施整体变更控制审查", "审查全部变更请求"),
    "PM-CHANGE-CONTROL": ("整体变更控制", "变更控制"),
    "PM-CHANGE-REQUEST": ("变更请求是", "正式提出的"),
}
QUERY_EXPANSIONS = {
    "共同控制": "整体变更控制",
    "持续监督": "监督干系人参与",
    "配置控制": "配置管理 变更控制",
    "变更请求": "变更请求与控制",
    "全部变更请求": "实施整体变更控制",
    "配置控制和整体变更控制": "配置控制 变更控制",
}


@dataclass
class Fragment:
    fragment_id: str
    knowledge_id: str
    concept_id: str | None
    note: str
    title: str
    heading_path: list[str]
    content: str
    source: list[str]
    source_pages: str | None
    status: str
    content_hash: str
    char_count: int
    token_count: int


def read_note(path: Path) -> tuple[dict, str]:
    text = path.read_text(encoding="utf-8")
    match = FRONTMATTER.match(text)
    if not match:
        raise ValueError(f"Missing frontmatter: {path}")
    metadata = yaml.safe_load(match.group(1))
    if not isinstance(metadata, dict):
        raise ValueError(f"Invalid frontmatter: {path}")
    return metadata, text[match.end() :]


def tokenize(text: str) -> list[str]:
    tokens: list[str] = []
    for value in CHINESE.findall(text):
        tokens.extend(value[index : index + 2] for index in range(len(value) - 1))
        tokens.extend(value)
    tokens.extend(value.lower() for value in LATIN.findall(text))
    return tokens


def split_long_text(text: str, limit: int) -> list[str]:
    if len(text) <= limit:
        return [text]
    sentences = [item.strip() for item in SENTENCE_END.split(text) if item.strip()]
    chunks: list[str] = []
    current = ""
    for sentence in sentences:
        if len(sentence) > limit:
            if current:
                chunks.append(current)
                current = ""
            chunks.extend(sentence[index : index + limit] for index in range(0, len(sentence), limit))
        elif current and len(current) + len(sentence) > limit:
            chunks.append(current)
            current = sentence
        else:
            current += sentence
    if current:
        chunks.append(current)
    return chunks


def note_fragments(path: Path, max_chars: int) -> list[Fragment]:
    metadata, body = read_note(path)
    title = str(metadata.get("title") or path.stem)
    knowledge_id = str(metadata.get("knowledge_id") or "")
    if not knowledge_id:
        return []
    headings: list[str] = []
    blocks: list[tuple[list[str], str]] = []
    lines: list[str] = []

    def flush() -> None:
        nonlocal lines
        content = "\n".join(lines).strip()
        if content:
            blocks.append((headings.copy(), content))
        lines = []

    for line in body.splitlines():
        match = HEADING.match(line)
        if match:
            flush()
            level = len(match.group(1))
            headings = headings[: level - 1] + [match.group(2)]
        elif line.strip():
            lines.append(line.strip())
        else:
            flush()
    flush()

    fragments: list[Fragment] = []
    concepts = metadata.get("concepts", {})
    if not isinstance(concepts, dict):
        raise ValueError(f"concepts must be a mapping: {path}")
    source = metadata.get("source", [])
    if isinstance(source, str):
        source = [source]
    if not isinstance(source, list):
        raise ValueError(f"source must be a string or list: {path}")

    def concept_id_for(path_headings: list[str], content: str) -> str | None:
        for heading in reversed(path_headings):
            concept = concepts.get(heading)
            if concept:
                return str(concept)
        # A single Markdown heading may contain several sibling concepts.
        # Prefer the concept name explicitly present in the fragment body.
        for value in concepts.values():
            aliases = CONCEPT_ALIASES.get(str(value), ())
            if any(alias in content for alias in aliases):
                return str(value)
        body_matches = [(str(name), str(value)) for name, value in concepts.items() if str(name) in content]
        if len(body_matches) == 1:
            return body_matches[0][1]
        if len(body_matches) > 1:
            return max(body_matches, key=lambda item: len(item[0]))[1]
        # OCR can corrupt heading text while frontmatter remains readable.
        # For unmatched headings, bind by the stable order of top-level
        # knowledge sections in this note; exact heading matches still win.
        if concepts and path_headings:
            top_level = path_headings[0]
            ordered_headings = []
            for candidate, _ in blocks:
                if candidate and candidate[0] not in ordered_headings:
                    ordered_headings.append(candidate[0])
            if top_level in ordered_headings:
                position = ordered_headings.index(top_level)
                sequence = metadata.get("concept_sequence", [])
                if isinstance(sequence, list) and position < len(sequence):
                    return str(sequence[position])
                values = list(concepts.values())
                if position < len(values):
                    return str(values[position])
        return None

    for block_number, (path_headings, content) in enumerate(blocks, start=1):
        for piece_number, piece in enumerate(split_long_text(content, max_chars), start=1):
            suffix = f"{block_number}-{piece_number}"
            content_hash = hashlib.sha256(piece.encode("utf-8")).hexdigest()
            fragments.append(
                Fragment(
                    fragment_id=f"{knowledge_id}:{suffix}",
                    knowledge_id=knowledge_id,
                    concept_id=concept_id_for(path_headings, piece),
                    note=path.stem,
                    title=title,
                    heading_path=path_headings,
                    content=piece,
                    source=[str(value) for value in source],
                    source_pages=str(metadata["source_pages"]) if metadata.get("source_pages") else None,
                    status=str(metadata.get("status") or ""),
                    content_hash=content_hash,
                    char_count=len(piece),
                    token_count=len(tokenize(piece)),
                )
            )
    return fragments


def build_index(vault: Path, statuses: set[str], max_chars: int) -> list[Fragment]:
    fragments: list[Fragment] = []
    for path in sorted((vault / "learning").glob("*.md")):
        metadata, _ = read_note(path)
        if metadata.get("status") in statuses:
            fragments.extend(note_fragments(path, max_chars))
    return fragments


def search(fragments: list[Fragment], query: str, top_k: int) -> list[tuple[float, Fragment]]:
    document_tokens = [tokenize(fragment.title + " " + " ".join(fragment.heading_path) + " " + fragment.content) for fragment in fragments]
    total = len(document_tokens)
    document_frequency: dict[str, int] = defaultdict(int)
    for tokens in document_tokens:
        for token in set(tokens):
            document_frequency[token] += 1
    average_length = sum(len(tokens) for tokens in document_tokens) / total if total else 0
    expanded_query = query + " " + " ".join(value for key, value in QUERY_EXPANSIONS.items() if key in query)
    query_tokens = tokenize(expanded_query)
    k1, b = 1.2, 0.75
    results: list[tuple[float, Fragment]] = []
    for fragment, tokens in zip(fragments, document_tokens):
        frequencies = Counter(tokens)
        score = 0.0
        for token in query_tokens:
            frequency = frequencies.get(token, 0)
            if not frequency:
                continue
            idf = math.log(1 + (total - document_frequency[token] + 0.5) / (document_frequency[token] + 0.5))
            denominator = frequency + k1 * (1 - b + b * len(tokens) / average_length)
            score += idf * frequency * (k1 + 1) / denominator
        # The leaf heading distinguishes sibling sections with shared body
        # vocabulary, without globally over-weighting the note title.
        leaf_tokens = set(tokenize(fragment.heading_path[-1])) if fragment.heading_path else set()
        score += 3.0 * sum(token in leaf_tokens for token in query_tokens)
        if score:
            results.append((round(score, 6), fragment))
    return sorted(results, key=lambda value: value[0], reverse=True)[:top_k]


def evaluate(fragments: list[Fragment], tests_path: Path) -> tuple[dict, bool]:
    dataset = yaml.safe_load(tests_path.read_text(encoding="utf-8"))
    acceptance = dataset.get("acceptance", {})
    top_k = int(acceptance.get("top_k", 3))
    rows = []
    for case in dataset["tests"]:
        results = search(fragments, case["query"], top_k)
        expected = set(case["expected_knowledge"])
        actual = [result.knowledge_id for _, result in results]
        expected_concepts = set(case.get("expected_concepts", []))
        actual_concepts = [result.concept_id for _, result in results if result.concept_id]
        knowledge_passed = bool(expected.intersection(actual))
        concept_passed = not expected_concepts or bool(expected_concepts.intersection(actual_concepts))
        rows.append(
            {
                "id": case["id"],
                "query": case["query"],
                "expected_knowledge": sorted(expected),
                "actual_knowledge": actual,
                "unique_knowledge_ids": sorted(set(actual)),
                "expected_concepts": sorted(expected_concepts),
                "actual_concepts": actual_concepts,
                "knowledge_passed": knowledge_passed,
                "concept_passed": concept_passed,
                "passed": knowledge_passed and concept_passed,
                "results": [
                    {
                        "score": score,
                        "fragment_id": result.fragment_id,
                        "knowledge_id": result.knowledge_id,
                        "concept_id": result.concept_id,
                        "note": result.note,
                        "heading_path": result.heading_path,
                        "source": result.source,
                        "source_pages": result.source_pages,
                        "status": result.status,
                        "content_hash": result.content_hash,
                        "char_count": result.char_count,
                        "token_count": result.token_count,
                        "content": result.content,
                    }
                    for score, result in results
                ],
            }
        )
    passed = sum(row["passed"] for row in rows)
    hit_rate = passed / len(rows) if rows else 0.0
    report = {"top_k": top_k, "passed": passed, "total": len(rows), "hit_rate": hit_rate, "tests": rows}
    return report, hit_rate >= float(acceptance.get("required_hit_rate", 1.0))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vault", type=Path, default=Path("knowledge-base"))
    parser.add_argument("--statuses", default="draft,reviewed,published")
    parser.add_argument("--max-chars", type=int, default=1200)
    parser.add_argument("--output", type=Path, default=Path("knowledge-base/index"))
    args = parser.parse_args()

    statuses = {value.strip() for value in args.statuses.split(",") if value.strip()}
    fragments = build_index(args.vault, statuses, args.max_chars)
    if not fragments:
        raise ValueError("No eligible knowledge fragments")
    report, accepted = evaluate(fragments, args.vault / "tests" / "retrieval-tests.yaml")
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "markdown-bm25.json").write_text(
        json.dumps({"statuses": sorted(statuses), "fragments": [asdict(item) for item in fragments]}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (args.output / "retrieval-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Indexed {len(fragments)} fragments from statuses: {', '.join(sorted(statuses))}")
    print(f"Retrieval evaluation: {report['passed']}/{report['total']} ({report['hit_rate']:.0%})")
    if not accepted:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
