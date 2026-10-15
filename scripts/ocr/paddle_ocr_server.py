"""Local PaddleOCR gateway for image question answering and document tooling."""

import base64
import json
import os
from typing import Any

# PaddlePaddle 3.3.1 on Windows can fail in the oneDNN PIR path for OCR models.
# The plain CPU executor is slower but stable for this local gateway.
os.environ.setdefault("FLAGS_use_mkldnn", "0")

import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from paddleocr import PaddleOCR
import paddle


class OcrRequest(BaseModel):
    imageBase64: str
    contentType: str | None = None


app = FastAPI(title="AI Exam Local OCR", version="1.0.0")
ocr: PaddleOCR | None = None


def get_ocr() -> PaddleOCR:
    global ocr
    if ocr is None:
        if os.getenv("OCR_GPU_REQUIRED", "false").lower() == "true" and not paddle.device.is_compiled_with_cuda():
            raise RuntimeError("GPU_REQUIRED: PaddlePaddle was installed without CUDA support")
        if os.getenv("OCR_GPU_REQUIRED", "false").lower() == "true":
            paddle.set_device("gpu:0")
        ocr = PaddleOCR(
            lang=os.getenv("PADDLE_OCR_LANG", "ch"),
            ocr_version=os.getenv("PADDLE_OCR_MODEL", "PP-OCRv5"),
            use_doc_orientation_classify=False,
            use_doc_unwarping=False,
            use_textline_orientation=False,
            enable_mkldnn=False,
        )
    return ocr


def result_payload(result: Any) -> dict[str, Any]:
    if hasattr(result, "json"):
        value = result.json
        value = value() if callable(value) else value
        if isinstance(value, str):
            return json.loads(value)
        if isinstance(value, dict):
            return value.get("res", value)
    if isinstance(result, dict):
        return result.get("res", result)
    return {}


def recognize_text(image: np.ndarray) -> tuple[str, float | None]:
    results = get_ocr().predict(image)
    texts: list[str] = []
    scores: list[float] = []
    for result in results:
        payload = result_payload(result)
        texts.extend(str(item).strip() for item in payload.get("rec_texts", []) if str(item).strip())
        scores.extend(float(item) for item in payload.get("rec_scores", []) if item is not None)
    return "\n".join(texts), (sum(scores) / len(scores) if scores else None)


@app.get("/health")
def health() -> dict[str, Any]:
    return {"status": "UP", "engine": "paddleocr", "modelLoaded": ocr is not None}


@app.post("/ocr")
def recognize(request: OcrRequest) -> dict[str, Any]:
    try:
        image_bytes = base64.b64decode(request.imageBase64, validate=True)
        if not image_bytes:
            raise ValueError("empty image")
        import cv2

        image = cv2.imdecode(np.frombuffer(image_bytes, dtype=np.uint8), cv2.IMREAD_COLOR)
        if image is None:
            raise ValueError("invalid image")
        text, confidence = recognize_text(image)
        return {"text": text, "confidence": confidence, "engine": "paddleocr"}
    except Exception as error:
        raise HTTPException(status_code=422, detail=f"OCR failed: {error}") from error


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        app,
        host=os.getenv("OCR_HOST", "127.0.0.1"),
        port=int(os.getenv("OCR_PORT", "18080")),
    )
