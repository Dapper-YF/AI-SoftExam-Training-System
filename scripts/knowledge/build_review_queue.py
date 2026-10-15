"""Build an auditable phase-7 review queue without changing note statuses."""

from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict
from pathlib import Path

import yaml


FRONTMATTER = re.compile(r"\A---\s*\n(.*?)\n---\s*\n", re.DOTALL)
ITTO = ("输入", "工具与技术", "输出")


def metadata(path: Path) -> tuple[dict, str]:
    text = path.read_text(encoding="utf-8")
    match = FRONTMATTER.match(text)
    if not match:
        return {}, text
    return yaml.safe_load(match.group(1)) or {}, text[match.end():]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vault", type=Path, default=Path("knowledge-base"))
    parser.add_argument("--low-confidence", type=float, default=0.90)
    args = parser.parse_args()

    tests = yaml.safe_load((args.vault / "tests" / "retrieval-tests.yaml").read_text(encoding="utf-8"))["tests"]
    tested_ids = {value for case in tests for value in case.get("expected_knowledge", [])}
    cards = []
    ids = defaultdict(list)
    for path in sorted((args.vault / "learning").glob("*.md")):
        data, body = metadata(path)
        if not data.get("knowledge_id"):
            continue
        kid = str(data["knowledge_id"])
        ids[kid].append(path.name)
        issues = []
        if data.get("status") != "draft":
            issues.append(f"unexpected status: {data.get('status')}")
        if not data.get("source_pages"):
            issues.append("missing source_pages")
        if not data.get("source"):
            issues.append("missing source")
        if not data.get("concepts"):
            issues.append("missing concepts")
        # Older OCR-derived cards use one combined ITTO heading. Treat it as
        # structurally present; human review still verifies each list item.
        requires_itto = kid.startswith("PM-")
        combined_itto = re.search(r"^#+\s*.*(?:输入|杈撳叆).*(?:工具|宸ュ叿).*(?:输出|杈撳嚭)", body, re.MULTILINE)
        if requires_itto and not combined_itto:
            for heading in ITTO:
                if not re.search(rf"^#+\s*{re.escape(heading)}", body, re.MULTILINE):
                    issues.append(f"missing section: {heading}")
        if kid not in tested_ids:
            issues.append("not covered by retrieval tests")
        cards.append({"file": str(path.relative_to(args.vault)).replace("\\", "/"), "knowledge_id": kid,
                      "title": data.get("title", path.stem), "status": data.get("status"), "source_pages": data.get("source_pages"),
                      "issues": issues, "ready_for_human_review": not issues})

    duplicate_ids = {key: values for key, values in ids.items() if len(values) > 1}
    source_data = json.loads((args.vault / "index" / "textbook-source-subfragments.json").read_text(encoding="utf-8"))
    low_sources = [
        {key: row.get(key) for key in ("fragment_id", "parent_fragment_id", "heading_path", "source_pages", "average_confidence", "quality_status")}
        for row in source_data["fragments"] if float(row.get("average_confidence") or 0) < args.low_confidence
    ]
    report = {"phase": 7, "rule": "precheck only; human review required", "card_count": len(cards),
              "ready_for_human_review": sum(item["ready_for_human_review"] for item in cards),
              "blocked_cards": [item for item in cards if item["issues"]], "duplicate_knowledge_ids": duplicate_ids,
              "low_confidence_source_count": len(low_sources), "low_confidence_sources": low_sources,
              "review_batches": {"KB-REVIEW-001": ["范围管理", "进度管理", "风险管理"],
                                 "KB-REVIEW-002": ["整合管理", "成本管理", "质量管理", "干系人管理", "配置与变更管理"],
                                 "KB-REVIEW-003": ["低置信度来源子片段"]}}
    out = args.vault / "review"; out.mkdir(exist_ok=True)
    (out / "review-queue.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = ["---", "title: 阶段七审核队列", "type: knowledge-review-queue", "status: pending", "---", "",
             "# 阶段七：人工审核队列", "", "> [!warning] 说明", "> 此文件由预检脚本生成。预检通过不等于人工审核通过，不自动修改卡片状态。", "", "## 卡片预检", "",
             "| 文件 | knowledge_id | 状态 | 预检 | 问题 |", "|---|---|---|---|---|"]
    for item in cards:
        lines.append(f"| [[{Path(item['file']).stem}]] | `{item['knowledge_id']}` | `{item['status']}` | {'通过' if item['ready_for_human_review'] else '阻塞'} | {'；'.join(item['issues']) or '无'} |")
    lines += ["", "## 低置信度来源", "", f"共 {len(low_sources)} 条来源子片段低于置信度阈值 {args.low_confidence}，详见 `review-queue.json`。"]
    (out / "阶段七-人工审核队列.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Review queue: {len(cards)} cards, ready={report['ready_for_human_review']}, blocked={len(report['blocked_cards'])}")
    print(f"Low-confidence source fragments: {len(low_sources)}")


if __name__ == "__main__":
    main()
