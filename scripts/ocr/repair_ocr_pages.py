"""Repair missing page blocks in a completed OCR Markdown document."""

from __future__ import annotations

import argparse
import os
import re
from pathlib import Path

import pypdfium2 as pdfium

os.environ.setdefault("FLAGS_use_mkldnn", "0")

from paddleocr import PaddleOCR

from ocr_pdfs_to_markdown import ocr_page


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--page", type=int, action="append", required=True)
    parser.add_argument("--scale", type=float, default=1.0)
    args = parser.parse_args()

    engine = PaddleOCR(
        text_detection_model_name="PP-OCRv5_mobile_det",
        text_recognition_model_name="PP-OCRv5_mobile_rec",
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
        enable_mkldnn=False,
    )
    document = pdfium.PdfDocument(str(args.source))
    content = args.output.read_text(encoding="utf-8")
    for page_number in sorted(set(args.page)):
        marker = f"## Page {page_number + 1}"
        if re.search(rf"(?m)^## Page {page_number}$", content):
            print(f"SKIP existing page {page_number}")
            continue
        text, confidence = ocr_page(engine, document[page_number - 1], args.scale)
        block = (
            f"## Page {page_number}\n\n"
            f"- source_page: {page_number}\n"
            f"- confidence: {confidence if confidence is not None else 'null'}\n\n"
            f"{text or '[NO_TEXT_RECOGNIZED]'}\n\n"
        )
        if marker not in content:
            raise RuntimeError(f"Cannot find insertion marker: {marker}")
        content = content.replace(marker, block + marker, 1)
        print(f"REPAIRED page {page_number}")
    args.output.write_text(content, encoding="utf-8", newline="\n")
    document.close()


if __name__ == "__main__":
    main()
