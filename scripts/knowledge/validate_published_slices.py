"""Validate the semantic slice set that will feed formal vectorization."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--projection", type=Path, default=Path("knowledge-base/index/published-projection.json"))
    parser.add_argument("--output", type=Path, default=Path("knowledge-base/index/published-slice-report.json"))
    args = parser.parse_args()
    projection = json.loads(args.projection.read_text(encoding="utf-8"))
    fragments = projection.get("fragments", [])
    ids = [item.get("fragment_key") or item.get("fragment_id") for item in fragments]
    hashes = [item.get("content_hash") for item in fragments]
    issues = []
    if len(ids) != len(set(ids)):
        issues.append("duplicate fragment_id")
    if any(not value for value in ids):
        issues.append("missing fragment_id")
    if any(not value for value in hashes):
        issues.append("missing content_hash")
    if any(item.get("status") not in {"published", "reviewed"} for item in fragments):
        issues.append("non-publishable status in projection")
    if any(
        item.get("content_kind") == "SOURCE"
        and item.get("quality_status") not in {"QUALIFIED", "APPROVED"}
        for item in fragments
    ):
        issues.append("non-publishable source quality status in projection")
    report = {
        "projection": str(args.projection),
        "fragment_count": len(fragments),
        "card_count": len(projection.get("cards", [])),
        "source_count": len(projection.get("sources", [])),
        "unique_fragment_ids": len(set(ids)),
        "unique_content_hashes": len(set(hashes)),
        "issues": issues,
        "ready_for_formal_embedding": not issues and bool(fragments),
    }
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
    if issues or not fragments:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
