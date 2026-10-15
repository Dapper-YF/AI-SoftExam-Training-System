"""Reliable local PDF batch OCR client with checkpoint/resume support."""

from __future__ import annotations

import argparse
import base64
import json
import time
import urllib.request
from pathlib import Path

try:
    import fitz  # PyMuPDF
except ImportError as exc:  # pragma: no cover
    raise SystemExit("PyMuPDF is required: python -m pip install pymupdf") from exc


def atomic_write(path: Path, value: object) -> None:
    temp = path.with_name(path.name + ".tmp")
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    temp.replace(path)


def request_ocr(endpoint: str, image: bytes, timeout: int) -> dict:
    payload = json.dumps({"imageBase64": base64.b64encode(image).decode("ascii"), "contentType": "image/png"}).encode()
    request = urllib.request.Request(endpoint, payload, {"Content-Type": "application/json"})
    # Local model services must bypass system/Docker proxy variables.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=timeout) as response:
        return json.loads(response.read())


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pdf", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--endpoint", default="http://127.0.0.1:18080/ocr")
    parser.add_argument("--checkpoint", type=Path)
    parser.add_argument("--dpi", type=int, default=180)
    parser.add_argument("--retries", type=int, default=3)
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--start-page", type=int, default=1)
    parser.add_argument("--end-page", type=int)
    args = parser.parse_args()
    checkpoint = args.checkpoint or args.output.with_suffix(args.output.suffix + ".checkpoint.json")
    if not args.pdf.exists():
        raise SystemExit(f"PDF not found: {args.pdf}")

    state = {"pdf": str(args.pdf), "pages": {}}
    if checkpoint.exists():
        state = json.loads(checkpoint.read_text(encoding="utf-8"))
    state.setdefault("pages", {})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    checkpoint.parent.mkdir(parents=True, exist_ok=True)

    document = fitz.open(args.pdf)
    total = len(document)
    scale = args.dpi / 72
    matrix = fitz.Matrix(scale, scale)
    end_page = min(args.end_page or total, total)
    for page_index in range(args.start_page - 1, end_page):
        page_number = page_index + 1
        key = str(page_number)
        existing = state["pages"].get(key)
        if existing and existing.get("status") == "ok":
            continue
        page = document.load_page(page_index)
        image = page.get_pixmap(matrix=matrix, alpha=False).tobytes("png")
        error = None
        for attempt in range(1, args.retries + 1):
            try:
                result = request_ocr(args.endpoint, image, args.timeout)
                state["pages"][key] = {"status": "ok", "page": page_number, "text": result.get("text", ""),
                                       "confidence": result.get("confidence"), "attempts": attempt}
                error = None
                break
            except Exception as exc:  # keep the page resumable after transient failures
                error = str(exc)
                time.sleep(min(2 ** attempt, 10))
        if error:
            state["pages"][key] = {"status": "error", "page": page_number, "error": error, "attempts": args.retries}
        atomic_write(checkpoint, state)
        print(f"page {page_number}/{total}: {state['pages'][key]['status']}", flush=True)

    pages = [state["pages"][str(index)] for index in range(1, total + 1) if str(index) in state["pages"]]
    output = {"pdf": str(args.pdf), "total_pages": total, "completed_pages": sum(p["status"] == "ok" for p in pages),
              "failed_pages": [p["page"] for p in pages if p["status"] != "ok"], "pages": pages}
    atomic_write(args.output, output)
    print(json.dumps({"total_pages": total, "completed_pages": output["completed_pages"], "failed_pages": output["failed_pages"]}, ensure_ascii=False))
    if output["failed_pages"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
