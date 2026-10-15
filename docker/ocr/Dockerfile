FROM aiexam-ocr-paddle:3.7.0-ppocrv5-mobile

ARG OCR_MODEL_REVISION=paddlex-local-cache

ENV OCR_GPU_REQUIRED=true \
    PADDLE_PDX_CACHE_HOME=/opt/models/paddle \
    PADDLE_OCR_LANG=ch \
    PADDLE_OCR_MODEL=PP-OCRv5

# The base image already contains PaddlePaddle, PaddleOCR and the offline model
# cache. This layer only supplies OpenCV's runtime libraries.
USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 libgomp1 \
 && rm -rf /var/lib/apt/lists/*

COPY scripts/ocr/paddle_ocr_server.py /app/paddle_ocr_server.py
LABEL org.aiexam.ocr.model="PP-OCRv5" \
      org.aiexam.ocr.revision="${OCR_MODEL_REVISION}"

EXPOSE 18080
CMD ["python3", "/app/paddle_ocr_server.py"]
