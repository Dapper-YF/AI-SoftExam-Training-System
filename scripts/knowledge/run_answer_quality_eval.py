"""Run a small end-to-end answer quality evaluation against the local assistant."""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.request
from pathlib import Path

import yaml


def post(url: str, token: str, payload: object) -> dict:
    request = urllib.request.Request(url, data=json.dumps(payload, ensure_ascii=False).encode(), method="POST",
                                     headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=120) as response:
        return json.loads(response.read().decode())


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--tests", type=Path, default=Path("knowledge-base/tests/answer-quality-tests.yaml"))
    parser.add_argument("--base-url", default="http://localhost:8000/api/v1/ai-assistant")
    parser.add_argument("--token", default="dev-student-token")
    parser.add_argument("--report", type=Path, default=Path("knowledge-base/index/answer-quality-report.json"))
    args = parser.parse_args()
    tests = yaml.safe_load(args.tests.read_text(encoding="utf-8"))["tests"]
    results = []
    for case in tests:
        started = time.perf_counter()
        session = post(f"{args.base_url}/sessions", args.token, {"title": "答案质量评测"})["data"]
        message = post(f"{args.base_url}/sessions/{session['id']}/messages", args.token, {"content": case["query"]})["data"]
        answer = message.get("content") or ""
        terms = [term for term in case.get("required_terms", []) if term not in answer]
        alternatives = case.get("required_any_terms", [])
        if alternatives and not any(term in answer for term in alternatives):
            terms.append("one-of:" + ",".join(alternatives))
        citations = message.get("citations") or []
        forbidden_tokens = ["fragment_id", "fragmentKey", "知识片段", "教材页码", *case.get("forbidden_terms", [])]
        forbidden = [token for token in forbidden_tokens if token in answer]
        passed = message.get("answerStatus") == "ANSWERED" and not terms and not forbidden
        if case.get("require_citations"):
            passed = passed and bool(citations)
        if case.get("forbid_citations"):
            passed = passed and not citations
        results.append({"id": case["id"], "category": case.get("category", "knowledge"),
                        "status": message.get("answerStatus"), "passed": passed,
                        "missing_terms": terms, "citation_count": len(citations), "forbidden_output": forbidden,
                        "answer_excerpt": answer[:240],
                        "latency_ms": round((time.perf_counter() - started) * 1000, 2)})
    categories = {}
    for item in results:
        category = categories.setdefault(item["category"], {"test_count": 0, "passed": 0})
        category["test_count"] += 1
        category["passed"] += int(item["passed"])
    report = {"generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "test_count": len(results),
              "passed": sum(item["passed"] for item in results), "categories": categories, "results": results}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
