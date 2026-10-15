"""Validate retrieval-test references against Obsidian knowledge cards."""

from __future__ import annotations

import argparse
import re
from pathlib import Path

import yaml


FRONTMATTER = re.compile(r"\A---\s*\n(.*?)\n---\s*\n", re.DOTALL)


def read_frontmatter(path: Path) -> dict:
    match = FRONTMATTER.match(path.read_text(encoding="utf-8"))
    if not match:
        raise ValueError(f"Missing frontmatter: {path}")
    value = yaml.safe_load(match.group(1))
    if not isinstance(value, dict):
        raise ValueError(f"Invalid frontmatter: {path}")
    return value


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vault", type=Path, default=Path("knowledge-base"))
    args = parser.parse_args()

    learning = args.vault / "learning"
    tests_path = args.vault / "tests" / "retrieval-tests.yaml"
    cards = {path.stem: read_frontmatter(path) for path in learning.glob("*.md")}
    ids = {card.get("knowledge_id") for card in cards.values() if card.get("knowledge_id")}
    concept_ids = {
        str(concept_id)
        for card in cards.values()
        for concept_id in (card.get("concepts", {}) or {}).values()
    }
    dataset = yaml.safe_load(tests_path.read_text(encoding="utf-8"))
    if not isinstance(dataset, dict) or not isinstance(dataset.get("tests"), list):
        raise ValueError("retrieval-tests.yaml must contain a tests list")

    failures: list[str] = []
    seen_ids: set[str] = set()
    for case in dataset["tests"]:
        case_id = case.get("id")
        if not isinstance(case_id, str) or not case_id:
            failures.append("Test without a non-empty id")
            continue
        if case_id in seen_ids:
            failures.append(f"Duplicate test id: {case_id}")
        seen_ids.add(case_id)
        for field in ("query", "qualification", "subject", "expected_note"):
            if not isinstance(case.get(field), str) or not case[field].strip():
                failures.append(f"{case_id}: missing {field}")
        if case.get("expected_note") not in cards:
            failures.append(f"{case_id}: unknown expected_note {case.get('expected_note')!r}")
        for knowledge_id in case.get("expected_knowledge", []):
            if knowledge_id not in ids:
                failures.append(f"{case_id}: unknown knowledge_id {knowledge_id!r}")
        for concept_id in case.get("expected_concepts", []):
            if concept_id not in concept_ids:
                failures.append(f"{case_id}: unknown concept_id {concept_id!r}")
        if not case.get("expected_terms"):
            failures.append(f"{case_id}: expected_terms is empty")

    if failures:
        raise ValueError("\n".join(failures))
    acceptance = dataset.get("acceptance", {})
    print(
        f"Validated {len(dataset['tests'])} retrieval tests against "
        f"{len(cards)} knowledge cards; top_k={acceptance.get('top_k', 'unset')}"
    )


if __name__ == "__main__":
    main()
