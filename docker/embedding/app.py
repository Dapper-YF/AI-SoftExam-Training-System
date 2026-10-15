"""GPU-only Qwen3 embedding service with an OpenAI-compatible endpoint."""

from __future__ import annotations

import asyncio
import os
from dataclasses import dataclass

import torch
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from transformers import AutoModel, AutoTokenizer


MODEL_ID = os.environ["EMBEDDING_MODEL_ID"]
MODEL_REVISION = os.environ["EMBEDDING_MODEL_REVISION"]
MODEL_PATH = os.getenv("EMBEDDING_MODEL_PATH", "/opt/models/qwen3-embedding-0.6b")
TOKEN = os.getenv("EMBEDDING_TOKEN", "")
MAX_BATCH = int(os.getenv("EMBEDDING_MAX_BATCH", "32"))
MAX_LENGTH = int(os.getenv("EMBEDDING_MAX_LENGTH", "2048"))
QUERY_INSTRUCTION = os.getenv("EMBEDDING_QUERY_INSTRUCTION", "为这个问题检索相关的中文信息系统项目管理知识：")


class EmbeddingRequest(BaseModel):
    input: str | list[str]
    model: str
    input_type: str = Field(pattern="^(DOCUMENT|QUERY)$")


@dataclass
class Engine:
    tokenizer: object
    model: object
    dimension: int


engine: Engine | None = None
lock = asyncio.Lock()
app = FastAPI(title="AI Exam Qwen3 Embedding", version="1.0")


def require_gpu() -> None:
    if not torch.cuda.is_available():
        raise RuntimeError("GPU_REQUIRED: CUDA is unavailable")


def last_token_pool(hidden: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
    left_padding = attention_mask[:, -1].sum() == attention_mask.shape[0]
    if left_padding:
        return hidden[:, -1]
    sequence_lengths = attention_mask.sum(dim=1) - 1
    return hidden[torch.arange(hidden.shape[0], device=hidden.device), sequence_lengths]


def load_engine() -> Engine:
    require_gpu()
    tokenizer = AutoTokenizer.from_pretrained(
        MODEL_PATH, revision=MODEL_REVISION, trust_remote_code=True, local_files_only=True
    )
    model = AutoModel.from_pretrained(
        MODEL_PATH,
        revision=MODEL_REVISION,
        trust_remote_code=True,
        local_files_only=True,
        torch_dtype=torch.bfloat16,
    ).to("cuda").eval()
    with torch.inference_mode():
        sample = tokenizer(["模型预热"], padding=True, truncation=True, max_length=MAX_LENGTH, return_tensors="pt")
        sample = {key: value.to("cuda") for key, value in sample.items()}
        dimension = int(last_token_pool(model(**sample).last_hidden_state, sample["attention_mask"]).shape[-1])
    return Engine(tokenizer, model, dimension)


async def get_engine() -> Engine:
    global engine
    if engine is None:
        async with lock:
            if engine is None:
                engine = load_engine()
    return engine


def authenticate(authorization: str | None) -> None:
    if not TOKEN:
        return
    if authorization != f"Bearer {TOKEN}":
        raise HTTPException(status_code=401, detail="invalid embedding token")


def encode(model_engine: Engine, inputs: list[str], input_type: str) -> list[list[float]]:
    if input_type == "QUERY":
        inputs = [QUERY_INSTRUCTION + value for value in inputs]
    encoded = model_engine.tokenizer(inputs, padding=True, truncation=True, max_length=MAX_LENGTH, return_tensors="pt")
    encoded = {key: value.to("cuda") for key, value in encoded.items()}
    with torch.inference_mode():
        outputs = model_engine.model(**encoded)
        vectors = last_token_pool(outputs.last_hidden_state, encoded["attention_mask"])
        vectors = torch.nn.functional.normalize(vectors.float(), p=2, dim=1)
    return vectors.cpu().tolist()


@app.get("/health")
async def health() -> dict:
    try:
        current = await get_engine()
        return {
            "status": "UP",
            "gpuRequired": True,
            "gpu": torch.cuda.get_device_name(0),
            "model": MODEL_ID,
            "revision": MODEL_REVISION,
            "dimension": current.dimension,
            "modelLoaded": True,
        }
    except Exception as error:
        return {"status": "DOWN", "gpuRequired": True, "error": str(error), "modelLoaded": False}


@app.get("/v1/models")
async def models(authorization: str | None = Header(default=None)) -> dict:
    authenticate(authorization)
    current = await get_engine()
    return {"object": "list", "data": [{"id": MODEL_ID, "revision": MODEL_REVISION, "dimension": current.dimension}]}


@app.post("/v1/embeddings")
async def embeddings(request: EmbeddingRequest, authorization: str | None = Header(default=None)) -> dict:
    authenticate(authorization)
    if request.model != MODEL_ID:
        raise HTTPException(status_code=400, detail="unsupported embedding model")
    inputs = [request.input] if isinstance(request.input, str) else request.input
    if not inputs or len(inputs) > MAX_BATCH or any(not value.strip() for value in inputs):
        raise HTTPException(status_code=400, detail="input must contain 1..max_batch non-empty strings")
    current = await get_engine()
    vectors = encode(current, inputs, request.input_type)
    return {
        "object": "list",
        "model": MODEL_ID,
        "embedding_revision": MODEL_REVISION,
        "data": [{"object": "embedding", "index": index, "embedding": vector} for index, vector in enumerate(vectors)],
    }
