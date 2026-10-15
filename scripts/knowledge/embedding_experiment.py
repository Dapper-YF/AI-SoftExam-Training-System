"""Run a small, non-publishing BM25/vector/RRF embedding experiment."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import time
import urllib.request
from collections import Counter, defaultdict
from dataclasses import dataclass, asdict
from pathlib import Path

import yaml

from markdown_retrieval import tokenize


@dataclass
class Document:
    fragment_id: str
    knowledge_id: str
    concept_id: str | None
    content_kind: str
    heading_path: list[str]
    source_pages: str | None
    content: str
    content_hash: str


def card_documents(vault: Path) -> list[Document]:
    path = vault / "index" / "markdown-bm25.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    return [Document(
        fragment_id=item["fragment_id"], knowledge_id=item["knowledge_id"],
        concept_id=item.get("concept_id"), content_kind="CARD",
        heading_path=item.get("heading_path", []), source_pages=item.get("source_pages"),
        content=item["content"], content_hash=item["content_hash"],
    ) for item in data["fragments"]]


def source_documents(vault: Path, limit: int) -> list[Document]:
    data = json.loads((vault / "index" / "textbook-source-subfragments.json").read_text(encoding="utf-8"))
    rows = [row for row in data["fragments"] if row.get("quality_status") == "QUALIFIED"][:limit]
    return [Document(
        fragment_id=row["fragment_id"], knowledge_id=row.get("parent_fragment_id", row["fragment_id"]),
        concept_id=None, content_kind="SOURCE", heading_path=row.get("heading_path", []),
        source_pages=row.get("source_pages"), content=row["content"],
        content_hash=row.get("content_hash") or hashlib.sha256(row["content"].encode()).hexdigest(),
    ) for row in rows]


def bm25(docs: list[Document], query: str, top_k: int) -> list[tuple[float, Document]]:
    tokens = [tokenize(" ".join(d.heading_path) + " " + d.content) for d in docs]
    df: defaultdict[str, int] = defaultdict(int)
    for values in tokens:
        for token in set(values): df[token] += 1
    avg = sum(map(len, tokens)) / len(tokens) if tokens else 1
    q = tokenize(query)
    results = []
    for doc, values in zip(docs, tokens):
        counts = Counter(values); score = 0.0
        for term in q:
            f = counts.get(term, 0)
            if f:
                idf = math.log(1 + (len(docs) - df[term] + .5) / (df[term] + .5))
                score += idf * f * 2.2 / (f + 1.2 * (.25 + .75 * len(values) / avg))
        if score: results.append((score, doc))
    return sorted(results, key=lambda x: x[0], reverse=True)[:top_k]


def post_json(url: str, payload: dict) -> dict:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, body, {"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=300) as response:
        return json.loads(response.read())


def get_json(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=30) as response:
        return json.loads(response.read())


def expected_hit(results: list[Document], case: dict) -> bool:
    ids = set(case.get("expected_knowledge", []))
    concepts = set(case.get("expected_concepts", []))
    return any(d.knowledge_id in ids or (d.concept_id and d.concept_id in concepts) for d in results)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vault", type=Path, default=Path("knowledge-base"))
    parser.add_argument("--endpoint", default="http://localhost:18081/v1/embeddings")
    parser.add_argument("--model", default="Qwen/Qwen3-Embedding-0.6B")
    parser.add_argument("--source-limit", type=int, default=50)
    parser.add_argument("--top-k", type=int, default=3)
    args = parser.parse_args()

    cards = card_documents(args.vault)
    sources = source_documents(args.vault, args.source_limit)
    docs = cards + sources
    tests = yaml.safe_load((args.vault / "tests" / "retrieval-tests.yaml").read_text(encoding="utf-8"))["tests"]
    started = time.perf_counter()
    model_info = get_json(args.endpoint.replace("/v1/embeddings", "/v1/models"))
    model_data = model_info["data"][0]
    vectors = []
    for offset in range(0, len(docs), 16):
        batch = docs[offset:offset + 16]
        response = post_json(args.endpoint, {"input": [d.content for d in batch], "model": args.model, "input_type": "DOCUMENT"})
        vectors.extend(item["embedding"] for item in response["data"])
    document_ms = (time.perf_counter() - started) * 1000

    def vector_search(query: str) -> list[tuple[float, Document]]:
        response = post_json(args.endpoint, {"input": [query], "model": args.model, "input_type": "QUERY"})
        q = response["data"][0]["embedding"]
        scored = [(sum(a * b for a, b in zip(q, vector)), doc) for doc, vector in zip(docs, vectors)]
        return sorted(scored, key=lambda x: x[0], reverse=True)[:args.top_k]

    def rrf(bm, vec):
        merged = {}
        for rank, (_, doc) in enumerate(bm, 1): merged.setdefault(doc.fragment_id, [doc, 0.0])[1] += 1 / (60 + rank)
        for rank, (_, doc) in enumerate(vec, 1): merged.setdefault(doc.fragment_id, [doc, 0.0])[1] += 1 / (60 + rank)
        return sorted(((score, item[0]) for item in merged.values() for score in [item[1]]), key=lambda x: x[0], reverse=True)[:args.top_k]

    rows = []; bm_pass = vec_pass = rrf_pass = 0
    query_started = time.perf_counter()
    for case in tests:
        bm = bm25(docs, case["query"], args.top_k)
        vec = vector_search(case["query"])
        hybrid = rrf(bm, vec)
        bm_docs = [d for _, d in bm]; vec_docs = [d for _, d in vec]; hybrid_docs = [d for _, d in hybrid]
        bp, vp, hp = expected_hit(bm_docs, case), expected_hit(vec_docs, case), expected_hit(hybrid_docs, case)
        bm_pass += bp; vec_pass += vp; rrf_pass += hp
        rows.append({"id": case["id"], "query": case["query"], "bm25_hit": bp, "vector_hit": vp, "rrf_hit": hp,
                     "bm25": [asdict(d) | {"score": round(s, 6)} for s, d in bm],
                     "vector": [asdict(d) | {"score": round(s, 6)} for s, d in vec],
                     "hybrid": [asdict(d) | {"score": round(s, 6)} for s, d in hybrid]})
    report = {"experiment": "phase-6-small-embedding", "model": model_data, "document_count": len(docs),
              "card_count": len(cards), "source_count": len(sources), "source_limit": args.source_limit,
              "dimension": len(vectors[0]) if vectors else 0, "document_embedding_ms": round(document_ms, 2),
              "query_count": len(tests), "query_ms": round((time.perf_counter() - query_started) * 1000, 2),
              "top_k": args.top_k, "rrf_k": 60, "bm25_hit_rate": bm_pass / len(tests),
              "vector_hit_rate": vec_pass / len(tests), "rrf_hit_rate": rrf_pass / len(tests), "tests": rows}
    out = args.vault / "index"; out.mkdir(exist_ok=True)
    (out / "embedding-experiment-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "embedding-experiment.json").write_text(json.dumps({"model": model_data, "documents": [asdict(d) for d in docs], "vectors": vectors}, ensure_ascii=False), encoding="utf-8")
    print(f"Phase 6: {len(docs)} documents ({len(cards)} cards + {len(sources)} sources), dimension={report['dimension']}")
    print(f"BM25={bm_pass}/{len(tests)}  vector={vec_pass}/{len(tests)}  RRF={rrf_pass}/{len(tests)}")


if __name__ == "__main__":
    main()
