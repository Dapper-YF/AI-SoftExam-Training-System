"""Conservatively audit NEEDS_REVIEW source fragments against the source PDF."""

from __future__ import annotations

import argparse
import base64
import difflib
import json
import re
import urllib.request
from datetime import date
from pathlib import Path

from pypdf import PdfReader
import fitz


def clean(value: str) -> str:
    value = re.sub(r"\s+", "", value or "")
    value = re.sub(r"[|丨]+", "", value)
    return value


def page_text(reader: PdfReader, start: int, end: int) -> str:
    parts = []
    for number in range(start - 1, min(end, len(reader.pages))):
        parts.append(reader.pages[number].extract_text() or "")
    return clean("".join(parts))


def ocr_page(doc: fitz.Document, page_number: int, endpoint: str) -> str:
    page = doc[page_number - 1]
    pixels = page.get_pixmap(matrix=fitz.Matrix(1.5, 1.5), alpha=False).tobytes("png")
    request = urllib.request.Request(
        endpoint,
        json.dumps({"imageBase64": base64.b64encode(pixels).decode("ascii"), "contentType": "image/png"}).encode(),
        {"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=300) as response:
        return clean(json.loads(response.read()).get("text", ""))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pdf", type=Path, required=True)
    parser.add_argument("--index", type=Path, default=Path("knowledge-base/index/textbook-source-subfragments.json"))
    parser.add_argument("--output", type=Path, default=Path("knowledge-base/review/source-review-records.json"))
    parser.add_argument("--audit-index", type=Path, default=Path("knowledge-base/index/textbook-source-subfragments-audited.json"))
    parser.add_argument("--approve-ratio", type=float, default=0.86)
    parser.add_argument("--ocr-endpoint", default="")
    parser.add_argument("--batch-json", type=Path)
    args = parser.parse_args()

    reader = PdfReader(str(args.pdf))
    visual_doc = fitz.open(str(args.pdf)) if args.ocr_endpoint else None
    batch_pages = {}
    if args.batch_json:
        batch = json.loads(args.batch_json.read_text(encoding="utf-8"))
        batch_pages = {int(item["page"]): item.get("text", "") for item in batch.get("pages", []) if item.get("status") == "ok"}
    data = json.loads(args.index.read_text(encoding="utf-8"))
    records = []
    audited = []
    for item in data["fragments"]:
        if item.get("quality_status") not in {"NEEDS_REVIEW", "NEEDS_CORRECTION"}:
            audited.append(item)
            if item.get("review_status"):
                records.append({
                    "fragment_id": item["fragment_id"],
                    "source_pages": item["source_pages"],
                    "average_confidence": item.get("average_confidence"),
                    "review_status": item.get("review_status"),
                    "review_method": item.get("review_method", "AI_PDF_COMPARISON"),
                    "issues": item.get("review_issues", ""),
                    "reviewed_at": item.get("reviewed_at"),
                    "similarity": item.get("review_similarity"),
                })
            continue
        source = page_text(reader, int(item["start_page"]), int(item["end_page"]))
        method = "AI_PDF_COMPARISON"
        if visual_doc is not None:
            source = ocr_page(visual_doc, int(item["start_page"]), args.ocr_endpoint)
            method = "AI_PDF_COMPARISON"
        elif batch_pages:
            source = clean("".join(batch_pages.get(page, "") for page in range(int(item["start_page"]), int(item["end_page"]) + 1)))
            method = "AI_PDF_COMPARISON"
        ocr = clean(item.get("content", ""))
        ratio = difflib.SequenceMatcher(None, ocr, source).ratio() if source else 0.0
        # A high ratio is only a text-level approval. Anything uncertain stays
        # blocked; this prevents low-confidence OCR from entering publication.
        approved = bool(source) and ratio >= args.approve_ratio
        status = "APPROVED" if approved else "NEEDS_REVIEW"
        review_status = "APPROVED" if approved else "NEEDS_CORRECTION"
        issue = "" if approved else ("PDF page OCR unavailable" if not source else f"OCR/PDF similarity {ratio:.3f} below threshold")
        updated = dict(item)
        updated["quality_status"] = status
        updated["review_status"] = review_status
        updated["review_method"] = method
        updated["reviewed_at"] = date.today().isoformat()
        updated["review_similarity"] = round(ratio, 4)
        updated["review_issues"] = issue
        audited.append(updated)
        records.append({
            "fragment_id": item["fragment_id"],
            "source_pages": item["source_pages"],
            "average_confidence": item.get("average_confidence"),
            "review_status": review_status,
            "review_method": method,
            "issues": issue,
            "reviewed_at": date.today().isoformat(),
            "similarity": round(ratio, 4),
        })
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.audit_index.write_text(json.dumps({"fragments": audited}, ensure_ascii=False, indent=2), encoding="utf-8")
    report = {"review_method": "AI_PDF_COMPARISON", "reviewed_count": len(records),
              "approved": sum(x["review_status"] == "APPROVED" for x in records),
              "needs_correction": sum(x["review_status"] == "NEEDS_CORRECTION" for x in records),
              "approve_ratio": args.approve_ratio, "records": records}
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("reviewed_count", "approved", "needs_correction", "approve_ratio")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
