"""Build the canonical Markdown-to-runtime knowledge projection."""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict
from pathlib import Path

from markdown_retrieval import build_index


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vault", type=Path, default=Path("knowledge-base"))
    parser.add_argument("--output", type=Path, default=Path("knowledge-base/index/published-projection.json"))
    parser.add_argument(
        "--include-approved-sources",
        action="store_true",
        help="include automatically qualified and manually approved source fragments",
    )
    args = parser.parse_args()

    cards = [asdict(fragment) | {"fragment_key": fragment.fragment_id, "content_kind": "CARD"}
             for fragment in build_index(args.vault, {"published"}, 1200)]
    sources: list[dict] = []
    if args.include_approved_sources:
        source_path = args.vault / "index" / "textbook-source-subfragments.json"
        if source_path.exists():
            for item in json.loads(source_path.read_text(encoding="utf-8"))["fragments"]:
                if item["quality_status"] in {"QUALIFIED", "APPROVED"}:
                    sources.append(item | {"fragment_key": item["fragment_id"], "status": "published"})
    projection = {"cards": cards, "sources": sources, "fragments": cards + sources}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(projection, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"published_cards": len(cards), "publishable_sources": len(sources), "fragments": len(projection["fragments"])}, ensure_ascii=False))


if __name__ == "__main__":
    main()
