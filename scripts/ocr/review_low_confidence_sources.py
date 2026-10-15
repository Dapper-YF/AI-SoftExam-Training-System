"""Compare low-confidence source fragments against fresh OCR of source PDF pages."""

from __future__ import annotations

import argparse
import base64
import difflib
import json
import re
import urllib.request
from pathlib import Path

import fitz


def normalize(value: str) -> str:
    return re.sub(r"\s+|[^\w\u4e00-\u9fff]", "", value).lower()


def ocr(endpoint: str, image: bytes, timeout: int) -> dict:
    body = json.dumps({"imageBase64": base64.b64encode(image).decode("ascii"), "contentType": "image/png"}).encode()
    request = urllib.request.Request(endpoint, body, {"Content-Type": "application/json"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=timeout) as response:
        return json.loads(response.read())


def coverage(fragment: str, page_text: str) -> float:
    source = normalize(fragment)
    target = normalize(page_text)
    if not source or not target:
        return 0.0
    match = difflib.SequenceMatcher(None, source, target).find_longest_match(0, len(source), 0, len(target))
    return match.size / len(source)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pdf", type=Path, required=True)
    parser.add_argument("--source-index", type=Path, default=Path("knowledge-base/index/textbook-source-subfragments.json"))
    parser.add_argument("--report", type=Path, default=Path("knowledge-base/review/low-confidence-review.json"))
    parser.add_argument("--approved-output", type=Path, default=Path("knowledge-base/index/approved-source-subfragments.json"))
    parser.add_argument("--endpoint", default="http://127.0.0.1:18080/ocr")
    parser.add_argument("--dpi", type=int, default=150)
    parser.add_argument("--threshold", type=float, default=0.85)
    args = parser.parse_args()
    fragments = json.loads(args.source_index.read_text(encoding="utf-8"))["fragments"]
    low = [item for item in fragments if item.get("quality_status") == "NEEDS_REVIEW"]
    pages = sorted({item["start_page"] for item in low})
    document = fitz.open(args.pdf)
    page_text: dict[int, str] = {}
    page_meta: dict[int, dict] = {}
    for page_number in pages:
        pixmap = document.load_page(page_number - 1).get_pixmap(matrix=fitz.Matrix(args.dpi / 72, args.dpi / 72), alpha=False)
        result = ocr(args.endpoint, pixmap.tobytes("png"), 300)
        page_text[page_number] = result.get("text", "")
        page_meta[page_number] = {"confidence": result.get("confidence"), "text_length": len(page_text[page_number])}
        print(f"page {page_number}: OCR chars={len(page_text[page_number])}", flush=True)

    rows = []
    approved = []
    for item in low:
        page = item["start_page"]
        score = coverage(item["content"], page_text.get(page, ""))
        issues = []
        if item["start_page"] != item["end_page"]:
            issues.append("cross-page fragment requires manual confirmation")
        if score < args.threshold:
            issues.append("fresh PDF OCR coverage below threshold")
        status = "APPROVED" if not issues else "NEEDS_REVIEW"
        row = {"fragment_id": item["fragment_id"], "source_pages": item["source_pages"],
               "average_confidence": item.get("average_confidence"), "review_status": status,
               "review_method": "AI_PDF_COMPARISON", "coverage": round(score, 4),
               "issues": issues, "reviewed_at": __import__("datetime").datetime.now().astimezone().isoformat(),
               "page_ocr": page_meta.get(page, {})}
        rows.append(row)
        if status == "APPROVED":
            approved.append(item | {"quality_status": "APPROVED", "review": row})
    report = {"review_method": "AI_PDF_COMPARISON", "threshold": args.threshold, "total": len(rows),
              "approved": len(approved), "needs_review": len(rows) - len(approved), "pages": pages, "fragments": rows}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    args.approved_output.parent.mkdir(parents=True, exist_ok=True)
    args.approved_output.write_text(json.dumps({"fragments": approved}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"total": len(rows), "approved": len(approved), "needs_review": len(rows) - len(approved)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
