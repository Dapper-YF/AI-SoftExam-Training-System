"""Exercise the complete local image-question flow: upload, OCR, confirm, RAG and answer."""

from __future__ import annotations

import argparse
import json
import mimetypes
import re
import sys
import time
import urllib.request
import uuid
from pathlib import Path


def request_json(request: urllib.request.Request, timeout: int = 120) -> dict:
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if payload.get("code") not in (None, "OK", 0, "0"):
        raise RuntimeError(payload)
    return payload["data"]


def json_request(url: str, token: str, method: str, payload: object) -> dict:
    return request_json(urllib.request.Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        method=method,
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    ))


def upload_request(url: str, token: str, image: Path) -> dict:
    boundary = f"----aiexam-{uuid.uuid4().hex}"
    content_type = mimetypes.guess_type(image.name)[0] or "application/octet-stream"
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{image.name}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode("utf-8") + image.read_bytes() + f"\r\n--{boundary}--\r\n".encode("ascii")
    return request_json(urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={"Authorization": f"Bearer {token}", "Content-Type": f"multipart/form-data; boundary={boundary}"},
    ))


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", type=Path, required=True)
    parser.add_argument("--base-url", default="http://localhost:8000/api/v1/ai-assistant")
    parser.add_argument("--token", default="dev-student-token")
    parser.add_argument("--question", default="请解答图片中的题目，并简要说明原因。")
    parser.add_argument("--expected-type")
    parser.add_argument("--expected-ocr-term", action="append", default=[])
    parser.add_argument("--expected-answer-term", action="append", default=[])
    parser.add_argument("--expected-answer-regex")
    parser.add_argument("--report", type=Path, default=Path("knowledge-base/index/ocr-qa-e2e-report.json"))
    args = parser.parse_args()
    if not args.image.is_file():
        raise FileNotFoundError(args.image)

    started = time.perf_counter()
    session = json_request(f"{args.base_url}/sessions", args.token, "POST", {"title": "OCR 图片端到端验收"})
    attachment = upload_request(
        f"{args.base_url}/sessions/{session['id']}/attachments", args.token, args.image,
    )
    ocr_text = (attachment.get("ocrText") or "").strip()
    confirm_request = urllib.request.Request(
        f"{args.base_url}/attachments/{attachment['id']}/ocr",
        data=ocr_text.encode("utf-8"),
        method="PUT",
        headers={"Authorization": f"Bearer {args.token}", "Content-Type": "text/plain; charset=utf-8"},
    )
    confirmed = request_json(confirm_request)
    answer = json_request(
        f"{args.base_url}/sessions/{session['id']}/messages",
        args.token,
        "POST",
        {"content": args.question, "attachmentId": attachment["id"]},
    )

    failures = []
    if attachment.get("ocrStatus") != "RECOGNIZED":
        failures.append(f"ocr_status={attachment.get('ocrStatus')}")
    if confirmed.get("ocrStatus") != "CONFIRMED":
        failures.append(f"confirmed_status={confirmed.get('ocrStatus')}")
    if args.expected_type and confirmed.get("detectedQuestionType") != args.expected_type:
        failures.append(f"question_type={confirmed.get('detectedQuestionType')}")
    failures.extend(f"missing_ocr_term={term}" for term in args.expected_ocr_term if term not in ocr_text)
    answer_text = answer.get("content") or ""
    failures.extend(f"missing_answer_term={term}" for term in args.expected_answer_term if term not in answer_text)
    if args.expected_answer_regex and not re.search(args.expected_answer_regex, answer_text):
        failures.append(f"answer_regex_not_matched={args.expected_answer_regex}")
    if answer.get("answerStatus") != "ANSWERED":
        failures.append(f"answer_status={answer.get('answerStatus')}")

    report = {
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "passed": not failures,
        "failures": failures,
        "image": str(args.image.resolve()),
        "session_id": session["id"],
        "attachment_id": attachment["id"],
        "ocr_status": attachment.get("ocrStatus"),
        "ocr_confidence": attachment.get("ocrConfidence"),
        "detected_question_type": confirmed.get("detectedQuestionType"),
        "ocr_text": ocr_text,
        "answer_status": answer.get("answerStatus"),
        "answer": answer_text,
        "citation_count": len(answer.get("citations") or []),
        "citations": answer.get("citations") or [],
        "latency_ms": round((time.perf_counter() - started) * 1000, 2),
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
