FROM nvidia/cuda:12.4.1-cudnn-runtime-ubuntu22.04

ARG EMBEDDING_MODEL_ID=Qwen/Qwen3-Embedding-0.6B
ARG EMBEDDING_MODEL_REVISION
ARG EMBEDDING_MODEL_SHA256=unverified

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/opt/model-cache \
    EMBEDDING_MODEL_ID=${EMBEDDING_MODEL_ID} \
    EMBEDDING_MODEL_REVISION=${EMBEDDING_MODEL_REVISION} \
    EMBEDDING_MODEL_PATH=/opt/models/qwen3-embedding-0.6b

RUN test -n "$EMBEDDING_MODEL_REVISION" \
 && apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-pip ca-certificates \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN python3 -m pip install --no-cache-dir --upgrade pip \
 && python3 -m pip install --no-cache-dir torch==2.6.0 --index-url https://download.pytorch.org/whl/cu124 \
 && python3 -m pip install --no-cache-dir -r requirements.txt

# The build context is prepared from F:\\AIExamRuntime\\models. No network access is
# required for model weights during an offline image build.
COPY model/ ${EMBEDDING_MODEL_PATH}/
LABEL org.aiexam.embedding.model="${EMBEDDING_MODEL_ID}" \
      org.aiexam.embedding.revision="${EMBEDDING_MODEL_REVISION}" \
      org.aiexam.embedding.sha256="${EMBEDDING_MODEL_SHA256}"

COPY app.py .
EXPOSE 18081
CMD ["python3", "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "18081"]
