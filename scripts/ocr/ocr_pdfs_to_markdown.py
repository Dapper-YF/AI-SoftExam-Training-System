"""Render local PDFs and write page-addressable PaddleOCR Markdown output."""

from __future__ import annotations

import argparse
import json
import os
import re
import time
from pathlib import Path

import numpy as np
import pypdfium2 as pdfium

# Avoid the known Windows oneDNN/PIR failure with PaddleOCR 3.7 CPU inference.
os.environ.setdefault("FLAGS_use_mkldnn", "0")

from paddleocr import PaddleOCR


def result_payload(result: object) -> dict:
    value = getattr(result, "json", result)
    if callable(value):
        value = value()
    if isinstance(value, str):
        value = json.loads(value)
    if isinstance(value, dict):
        return value.get("res", value)
    return {}


def safe_name(path: Path) -> str:
    return re.sub(r"[^\w\-.\u4e00-\u9fff]+", "-", path.stem).strip("-")


def ocr_page(engine: PaddleOCR, page: pdfium.PdfPage, scale: float) -> tuple[str, float | None]:
    bitmap = page.render(scale=scale)
    image = bitmap.to_numpy()
    if image.shape[-1] == 4:
        image = image[:, :, :3]
    results = engine.predict(image)
    texts: list[str] = []
    scores: list[float] = []
    for result in results:
        payload = result_payload(result)
        texts.extend(str(item).strip() for item in payload.get("rec_texts", []) if str(item).strip())
        scores.extend(float(item) for item in payload.get("rec_scores", []) if item is not None)
    return "\n".join(texts), (sum(scores) / len(scores) if scores else None)


def process_pdf(engine: PaddleOCR, source: Path, output_root: Path, scale: float) -> None:
    output_dir = output_root / safe_name(source)
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / "ocr.md"
    document = pdfium.PdfDocument(str(source))
    total = len(document)
    temporary = output.with_suffix(".md.partial")
    if output.exists():
        print(f"SKIP complete {output}", flush=True)
        document.close()
        return
    existing_page_count = 0
    if temporary.exists():
        existing_page_count = max(
            [int(match) for match in re.findall(r"^## Page (\d+)$", temporary.read_text(encoding="utf-8"), re.MULTILINE)]
            or [0]
        )
    started = time.time()
    mode = "a" if existing_page_count else "w"
    with temporary.open(mode, encoding="utf-8", newline="\n") as handle:
        if not existing_page_count:
            handle.write("---\n")
            handle.write(f"title: {source.stem}\n")
            handle.write("type: ocr-document\n")
            handle.write("status: pending-review\n")
            handle.write(f"source_file: {source.as_posix()}\n")
            handle.write(f"page_count: {total}\n")
            handle.write("ocr_engine: paddleocr\n")
            handle.write(f"ocr_model: {os.getenv('PADDLE_OCR_MODEL', 'PP-OCRv5_mobile')}\n")
            handle.write("---\n\n")
            handle.write(f"# OCR - {source.stem}\n\n")
        if existing_page_count:
            print(f"RESUME {source.name} from page {existing_page_count + 1}/{total}", flush=True)
        for index in range(existing_page_count, total):
            page = document[index]
            text, confidence = ocr_page(engine, page, scale)
            handle.write(f"## Page {index + 1}\n\n")
            handle.write(f"- source_page: {index + 1}\n")
            handle.write(f"- confidence: {confidence if confidence is not None else 'null'}\n\n")
            handle.write(text or "[NO_TEXT_RECOGNIZED]")
            handle.write("\n\n")
            page.close()
            if (index + 1) % 5 == 0 or index + 1 == total:
                elapsed = time.time() - started
                rate = (index + 1) / elapsed if elapsed else 0
                print(f"{source.name}: {index + 1}/{total} pages, {rate:.2f} pages/sec", flush=True)
    temporary.replace(output)
    document.close()
    print(f"DONE {output}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", action="append", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, default=Path("knowledge-base/sources/ocr"))
    parser.add_argument("--scale", type=float, default=1.5)
    args = parser.parse_args()
    model = os.getenv("PADDLE_OCR_MODEL", "PP-OCRv5_mobile")
    engine = PaddleOCR(
        lang=os.getenv("PADDLE_OCR_LANG", "ch"),
        text_detection_model_name=f"{model}_det",
        text_recognition_model_name=f"{model}_rec",
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
        enable_mkldnn=False,
    )
    for source in args.source:
        process_pdf(engine, source, args.output_root, args.scale)


if __name__ == "__main__":
    main()
