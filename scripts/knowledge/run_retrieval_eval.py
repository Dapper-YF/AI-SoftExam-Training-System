"""Run the published retrieval test set against the local backend."""

from __future__ import annotations

import argparse
import json
import re
import time
import urllib.request
from pathlib import Path

import yaml


def call(endpoint: str, token: str, payload: dict) -> dict:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        endpoint,
        data=body,
        method="POST",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=90) as response:
        return json.loads(response.read().decode("utf-8"))


def knowledge_hit(rows: list[dict], case: dict) -> bool:
    expected_knowledge = set(case.get("expected_knowledge", []))
    return bool(expected_knowledge) and any(row.get("knowledgeId") in expected_knowledge for row in rows)


def concept_hit(rows: list[dict], case: dict) -> bool:
    expected_concepts = set(case.get("expected_concepts", []))
    return bool(expected_concepts) and any(row.get("conceptId") in expected_concepts for row in rows)


def hit(rows: list[dict], case: dict) -> bool:
    return knowledge_hit(rows, case) or concept_hit(rows, case)


def metrics(rows_by_case: list[tuple[dict, list[dict]]]) -> dict:
    top1 = sum(hit(rows[:1], case) for case, rows in rows_by_case)
    top3 = sum(hit(rows[:3], case) for case, rows in rows_by_case)
    knowledge_cases = [(case, rows) for case, rows in rows_by_case if case.get("expected_knowledge")]
    concept_cases = [(case, rows) for case, rows in rows_by_case if case.get("expected_concepts")]
    duplicate_count = sum(
        len(rows) - len({row.get("fragmentKey") for row in rows})
        for _, rows in rows_by_case
    )
    returned_count = sum(len(rows[:3]) for _, rows in rows_by_case)
    irrelevant_count = sum(
        not hit([row], case)
        for case, rows in rows_by_case
        for row in rows[:3]
    )
    return {
        "top1_hit_rate": round(top1 / len(rows_by_case), 4) if rows_by_case else 0,
        "top3_hit_rate": round(top3 / len(rows_by_case), 4) if rows_by_case else 0,
        "knowledge_top1_hit_rate": round(
            sum(knowledge_hit(rows[:1], case) for case, rows in knowledge_cases) / len(knowledge_cases), 4
        ) if knowledge_cases else None,
        "knowledge_top3_hit_rate": round(
            sum(knowledge_hit(rows[:3], case) for case, rows in knowledge_cases) / len(knowledge_cases), 4
        ) if knowledge_cases else None,
        "concept_top1_hit_rate": round(
            sum(concept_hit(rows[:1], case) for case, rows in concept_cases) / len(concept_cases), 4
        ) if concept_cases else None,
        "concept_top3_hit_rate": round(
            sum(concept_hit(rows[:3], case) for case, rows in concept_cases) / len(concept_cases), 4
        ) if concept_cases else None,
        "duplicate_fragment_count": duplicate_count,
        "irrelevant_result_rate": round(irrelevant_count / returned_count, 4) if returned_count else 0,
    }


def page_intervals(value: object) -> list[tuple[int, int]]:
    values = value if isinstance(value, list) else [value]
    intervals: list[tuple[int, int]] = []
    for item in values:
        numbers = [int(number) for number in re.findall(r"\d+", str(item or ""))]
        if not numbers:
            continue
        start, end = numbers[0], numbers[-1]
        intervals.append((min(start, end), max(start, end)))
    return intervals


def pages_overlap(left: list[tuple[int, int]], right: list[tuple[int, int]]) -> bool:
    return any(max(left_start, right_start) <= min(left_end, right_end)
               for left_start, left_end in left
               for right_start, right_end in right)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tests", type=Path, default=Path("knowledge-base/tests/retrieval-tests.yaml"))
    parser.add_argument("--endpoint", default="http://localhost:8000/api/v1/ai-assistant/knowledge/retrieval-test")
    parser.add_argument("--token", default="dev-student-token")
    parser.add_argument("--report", type=Path, default=Path("knowledge-base/index/retrieval-report.json"))
    parser.add_argument("--projection", type=Path, default=Path("knowledge-base/index/published-projection.json"))
    args = parser.parse_args()

    dataset = yaml.safe_load(args.tests.read_text(encoding="utf-8"))
    cases = [case for case in dataset["tests"] if case.get("enabled", True)]
    projection = json.loads(args.projection.read_text(encoding="utf-8"))
    pages_by_knowledge: dict[str, set[str]] = {}
    for fragment in projection.get("cards", []):
        knowledge_id = fragment.get("knowledge_id")
        source_pages = fragment.get("source_pages")
        if knowledge_id and source_pages:
            pages_by_knowledge.setdefault(str(knowledge_id), set()).add(str(source_pages))
    channels = {"bm25Results": [], "vectorResults": [], "hybridResults": []}
    top1_failures: list[dict] = []
    top3_failures: list[dict] = []
    latencies: list[float] = []
    for case in cases:
        started = time.perf_counter()
        response = call(args.endpoint, args.token, {"query": case["query"], "qualification": case["qualification"],
                                                      "subject": case["subject"], "topK": 3})
        elapsed = round((time.perf_counter() - started) * 1000, 2)
        latencies.append(elapsed)
        data = response.get("data", {})
        for channel in channels:
            rows = data.get(channel, [])
            channels[channel].append((case, rows))
            if not hit(rows[:1], case):
                top1_failures.append(
                    {"id": case["id"], "channel": channel,
                     "expectedKnowledge": case.get("expected_knowledge", []),
                     "expectedConcepts": case.get("expected_concepts", []),
                     "actual": [row.get("knowledgeId") or row.get("conceptId") for row in rows[:1]]}
                )
            if not hit(rows[:3], case):
                top3_failures.append(
                    {"id": case["id"], "channel": channel,
                     "expectedKnowledge": case.get("expected_knowledge", []),
                     "expectedConcepts": case.get("expected_concepts", []),
                     "actual": [row.get("knowledgeId") or row.get("conceptId") for row in rows[:3]]}
                )

    channel_metrics = {channel: metrics(rows) for channel, rows in channels.items()}
    hybrid_metrics = channel_metrics["hybridResults"]
    hybrid_rows = channels["hybridResults"]
    page_cases: list[tuple[dict, list[dict], list[tuple[int, int]]]] = []
    for case, rows in hybrid_rows:
        expected_page_values = case.get("expected_source_pages", [])
        if not expected_page_values:
            expected_page_values = sorted({
                page
                for knowledge_id in case.get("expected_knowledge", [])
                for page in pages_by_knowledge.get(str(knowledge_id), set())
            })
        expected_intervals = page_intervals(expected_page_values)
        if expected_intervals:
            page_cases.append((case, rows, expected_intervals))
    page_top1 = sum(
        any(pages_overlap(expected, page_intervals(row.get("sourcePages"))) for row in rows[:1])
        for _, rows, expected in page_cases
    )
    page_top3 = sum(
        any(pages_overlap(expected, page_intervals(row.get("sourcePages"))) for row in rows[:3])
        for _, rows, expected in page_cases
    )
    page_top1_rate = round(page_top1 / len(page_cases), 4) if page_cases else None
    page_top3_rate = round(page_top3 / len(page_cases), 4) if page_cases else None
    concept_top1_failures = [
        {"id": case["id"], "expectedConcepts": case.get("expected_concepts", []),
         "actualConcepts": [row.get("conceptId") for row in rows[:1]]}
        for case, rows in hybrid_rows
        if case.get("expected_concepts") and not concept_hit(rows[:1], case)
    ]
    concept_top3_failures = [
        {"id": case["id"], "expectedConcepts": case.get("expected_concepts", []),
         "actualConcepts": [row.get("conceptId") for row in rows[:3]]}
        for case, rows in hybrid_rows
        if case.get("expected_concepts") and not concept_hit(rows[:3], case)
    ]
    report = {
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "endpoint": args.endpoint,
        "test_count": len(cases),
        "channels": channel_metrics,
        "source_page_evaluation": {
            "evaluated_count": len(page_cases),
            "top1_hit_rate": page_top1_rate,
            "top3_hit_rate": page_top3_rate,
            "expectation_source": "explicit expected_source_pages, otherwise published card source_pages",
        },
        "average_latency_ms": round(sum(latencies) / len(latencies), 2) if latencies else 0,
        "p95_latency_ms": sorted(latencies)[max(0, int(len(latencies) * 0.95) - 1)] if latencies else 0,
        "acceptance": {
            "hybrid_top1_at_least_85_percent": hybrid_metrics["top1_hit_rate"] >= 0.85,
            "hybrid_top3_at_least_95_percent": hybrid_metrics["top3_hit_rate"] >= 0.95,
            "hybrid_concept_top1_at_least_85_percent":
                hybrid_metrics["concept_top1_hit_rate"] is not None
                and hybrid_metrics["concept_top1_hit_rate"] >= 0.85,
            "hybrid_concept_top3_at_least_95_percent":
                hybrid_metrics["concept_top3_hit_rate"] is not None
                and hybrid_metrics["concept_top3_hit_rate"] >= 0.95,
            "source_page_top3_at_least_95_percent":
                page_top3_rate is not None and page_top3_rate >= 0.95,
            "duplicate_fragment_count_is_zero": hybrid_metrics["duplicate_fragment_count"] == 0,
        },
        "hybrid_concept_top1_failures": concept_top1_failures,
        "hybrid_concept_top3_failures": concept_top3_failures,
        "top1_failures": top1_failures,
        "top3_failures": top3_failures,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
