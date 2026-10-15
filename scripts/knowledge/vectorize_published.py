"""Build the formal Qdrant index from the reviewed Obsidian publication projection."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

POINTS_PER_REQUEST = 128


def request(url: str, method: str = "GET", payload: object | None = None) -> dict:
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=body, method=method, headers={"Content-Type": "application/json"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=90) as response:
        return json.loads(response.read().decode("utf-8"))


def embedding_text(fragment: dict) -> str:
    heading_path = [str(value).strip() for value in fragment.get("heading_path", []) if str(value).strip()]
    heading = " > ".join(heading_path)
    content = str(fragment.get("content", "")).strip()
    if not heading:
        return content
    return f"标题路径：{heading}\n正文：{content}"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--projection", type=Path, default=Path("knowledge-base/index/published-projection.json"))
    parser.add_argument("--embedding-endpoint", default=os.getenv("EMBEDDING_ENDPOINT", "http://localhost:18081/v1/embeddings"))
    parser.add_argument("--qdrant-endpoint", default=os.getenv("QDRANT_ENDPOINT", "http://localhost:6333"))
    parser.add_argument("--collection", default=os.getenv("QDRANT_COLLECTION", "knowledge_published"))
    parser.add_argument("--report", type=Path, default=Path("knowledge-base/index/embedding-index-report.json"))
    parser.add_argument("--allow-empty", action="store_true")
    args = parser.parse_args()

    data = json.loads(args.projection.read_text(encoding="utf-8"))
    fragments = data.get("fragments", [])
    if not fragments and not args.allow_empty:
        raise SystemExit("No published fragments. Review and publish cards or approved source fragments first.")
    model = request(args.embedding_endpoint.rsplit("/embeddings", 1)[0] + "/models")
    model_info = model.get("data", [{}])[0]
    model_id = model_info.get("id") or os.getenv("EMBEDDING_MODEL_ID", "Qwen/Qwen3-Embedding-0.6B")
    embeddings = []
    for offset in range(0, len(fragments), 16):
        batch = fragments[offset : offset + 16]
        vectors = request(args.embedding_endpoint, "POST", {
            "input": [embedding_text(item) for item in batch],
            "model": model_id,
            "input_type": "DOCUMENT",
        })
        embeddings.extend(item["embedding"] for item in vectors.get("data", []))
    if len(embeddings) != len(fragments):
        raise SystemExit("Embedding response count does not match publication projection")
    dimension = len(embeddings[0]) if embeddings else 0
    base = args.qdrant_endpoint.rstrip("/")
    if dimension:
        # Rebuild the versioned collection atomically at the application level.
        # A rerun must not retain points from an earlier incomplete projection.
        try:
            request(f"{base}/collections/{args.collection}", "DELETE")
        except Exception as error:
            if "HTTP Error 404" not in str(error):
                raise
        request(f"{base}/collections/{args.collection}", "PUT", {"vectors": {"size": dimension, "distance": "Cosine"}})
        points = []
        for fragment, vector in zip(fragments, embeddings):
            key = fragment["fragment_id"]
            point_id = int(hashlib.sha256(key.encode("utf-8")).hexdigest()[:15], 16)
            payload = {name: value for name, value in fragment.items() if name != "content"}
            payload.update({"content_hash": hashlib.sha256(fragment["content"].encode("utf-8")).hexdigest(),
                            "embedding_model": model_info.get("id"), "embedding_revision": model_info.get("revision")})
            points.append({"id": point_id, "vector": vector, "payload": payload})
        for offset in range(0, len(points), POINTS_PER_REQUEST):
            request(f"{base}/collections/{args.collection}/points?wait=true", "PUT",
                    {"points": points[offset : offset + POINTS_PER_REQUEST]})
    report = {"generated_at": datetime.now(timezone.utc).isoformat(), "collection": args.collection,
              "fragment_count": len(fragments), "embedding_dimension": dimension,
              "embedding_model": model_info.get("id"), "embedding_revision": model_info.get("revision"), "failures": []}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
