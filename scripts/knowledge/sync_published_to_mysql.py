"""Idempotently project the published knowledge projection into MySQL."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path

import pymysql


def stable_id(value: str) -> int:
    return int(hashlib.sha256(value.encode("utf-8")).hexdigest()[:15], 16)


def qdrant_id(fragment_key: str) -> str:
    return str(stable_id(fragment_key))


CH_ABBREV = re.compile(r"^第(\d+)章")


def chapter_code(fragment: dict) -> str:
    match = CH_ABBREV.match((fragment.get("chapter") or "").strip())
    return f"CH{int(match.group(1)):02d}" if match else "NO_CHAPTER"


def source_group(fragment: dict) -> str:
    source_id = fragment.get("source_id") or "TEXTBOOK"
    return f"{source_id}/{chapter_code(fragment)}"


def group_key(fragment: dict) -> str:
    kind = fragment.get("content_kind", "SOURCE")
    if kind == "CARD":
        return fragment.get("knowledge_id") or "UNKNOWN"
    return source_group(fragment)


def document_title(fragment: dict, code: str) -> str:
    if fragment.get("content_kind", "SOURCE") == "SOURCE":
        return fragment.get("chapter") or fragment.get("section") or code
    return fragment.get("title") or fragment.get("section") or code


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--projection", type=Path, default=Path("knowledge-base/index/published-projection.json"))
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=33307)
    parser.add_argument("--user", default="aiexam_app")
    parser.add_argument("--password", default="123123")
    parser.add_argument("--database", default="ai_exam_platform")
    parser.add_argument("--qdrant-collection", default="knowledge_published_qwen3_97b0c614")
    parser.add_argument("--release-code", default=os.getenv("KNOWLEDGE_RELEASE", "knowledge-release-2026-07-25"))
    parser.add_argument("--embedding-model", default=os.getenv("EMBEDDING_MODEL", "Qwen/Qwen3-Embedding-0.6B"))
    parser.add_argument("--embedding-revision", default=os.getenv("EMBEDDING_REVISION", "97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3"))
    parser.add_argument("--embedding-version", default=os.getenv("EMBEDDING_VERSION", "qwen3-embedding-0.6b-97b0c614"))
    args = parser.parse_args()
    projection = json.loads(args.projection.read_text(encoding="utf-8"))
    fragments = projection.get("fragments", [])
    if not fragments:
        raise SystemExit("published projection is empty")
    expected_keys = {fragment.get("fragment_key") or fragment["fragment_id"] for fragment in fragments}
    document_ids = set()
    for fragment in fragments:
        kind = fragment.get("content_kind", "SOURCE")
        document_ids.add(stable_id(f"KB-{kind}-{group_key(fragment)}"))
    now = datetime.now(timezone.utc).replace(tzinfo=None)
    connection = pymysql.connect(host=args.host, port=args.port, user=args.user, password=args.password,
                                 database=args.database, charset="utf8mb4", autocommit=False)
    documents: dict[str, int] = {}
    fragment_numbers: dict[int, int] = {}
    document_contents: dict[str, list[str]] = {}
    document_hashes: dict[str, list[str]] = {}
    for fragment in fragments:
        kind = fragment.get("content_kind", "SOURCE")
        code = f"KB-{kind}-{group_key(fragment)}"
        document_contents.setdefault(code, []).append(fragment["content"])
        document_hashes.setdefault(code, []).append(
            fragment.get("content_hash") or hashlib.sha256(fragment["content"].encode("utf-8")).hexdigest()
        )
    try:
        with connection.cursor() as cursor:
            document_placeholders = ",".join(["%s"] * len(document_ids))
            cursor.execute(f"DELETE FROM knowledge_fragment WHERE deleted=0 AND document_id IN ({document_placeholders})",
                           tuple(document_ids))
            for fragment in fragments:
                key = fragment.get("fragment_key") or fragment["fragment_id"]
                kind = fragment.get("content_kind", "SOURCE")
                code = f"KB-{kind}-{group_key(fragment)}"
                document_id = stable_id(code)
                documents[code] = document_id
                fragment_numbers[document_id] = fragment_numbers.get(document_id, 0) + 1
                title = document_title(fragment, code)
                source_name = fragment.get("note") or fragment.get("source_id") or "Obsidian knowledge-base"
                source_hash = fragment.get("content_hash") or hashlib.sha256(fragment["content"].encode("utf-8")).hexdigest()
                cursor.execute("""
                    INSERT INTO knowledge_document
                      (id,content_domain,qualification_id,subject_id,document_code,title,source_name,source_url,copyright_basis,
                       reviewed_by,reviewed_at,published_at,status,current_version,created_at,updated_at,deleted)
                    VALUES (%s,'AI_ASSISTANT',0,NULL,%s,%s,%s,NULL,'PROJECT_KNOWLEDGE_BASE',NULL,%s,%s,'PUBLISHED',1,%s,%s,0)
                    ON DUPLICATE KEY UPDATE source_name=VALUES(source_name),status='PUBLISHED',
                      published_at=VALUES(published_at),updated_at=VALUES(updated_at),deleted=0
                """, (document_id, code, title, source_name, now, now, now, now))
                version_id = stable_id(code + ":v1")
                if fragment_numbers[document_id] == 1:
                    raw_content = "\n\n".join(document_contents[code])
                    document_hash = hashlib.sha256(
                        "\n".join(document_hashes[code]).encode("utf-8")
                    ).hexdigest()
                    cursor.execute("""
                        INSERT INTO knowledge_document_version (id,document_id,version_no,raw_content,content_hash,created_at,created_by)
                        VALUES (%s,%s,1,%s,%s,%s,NULL)
                        ON DUPLICATE KEY UPDATE raw_content=VALUES(raw_content),content_hash=VALUES(content_hash),created_at=VALUES(created_at)
                    """, (version_id, document_id, raw_content, document_hash, now))
                fragment_id = stable_id(key)
                status = "PUBLISHED"
                quality = fragment.get("quality_status", "APPROVED")
                cursor.execute("""
                    INSERT INTO knowledge_fragment
                      (id,fragment_key,document_id,fragment_no,knowledge_type,knowledge_id,knowledge_code,concept_code,content_kind,
                       content,content_hash,status,index_status,heading_path,source_pages,source_confidence,quality_status,
                       embedding_model,embedding_revision,embedding_version,embedding_dimension,qdrant_point_id,embedded_at,
                       created_at,updated_at,deleted)
                    VALUES (%s,%s,%s,%s,%s,NULL,%s,%s,%s,%s,%s,%s,'INDEXED',%s,%s,%s,%s,
                            %s,%s,%s,1024,%s,%s,%s,%s,0)
                    ON DUPLICATE KEY UPDATE document_id=VALUES(document_id),content=VALUES(content),content_hash=VALUES(content_hash),
                      knowledge_type=VALUES(knowledge_type),knowledge_code=VALUES(knowledge_code),
                      concept_code=VALUES(concept_code),content_kind=VALUES(content_kind),
                      status='PUBLISHED',index_status='INDEXED',heading_path=VALUES(heading_path),source_pages=VALUES(source_pages),
                      source_confidence=VALUES(source_confidence),quality_status=VALUES(quality_status),embedding_model=VALUES(embedding_model),
                      embedding_revision=VALUES(embedding_revision),embedding_version=VALUES(embedding_version),embedding_dimension=1024,
                      qdrant_point_id=VALUES(qdrant_point_id),embedded_at=VALUES(embedded_at),updated_at=VALUES(updated_at),deleted=0
                """, (fragment_id, key, document_id, fragment_numbers[document_id], kind, group_key(fragment), fragment.get("concept_id"), kind,
                      fragment["content"], source_hash, status, json.dumps(fragment.get("heading_path", []), ensure_ascii=False),
                      fragment.get("source_pages"), fragment.get("average_confidence"), quality,
                      args.embedding_model, args.embedding_revision, args.embedding_version, qdrant_id(key), now, now, now))
            key_placeholders = ",".join(["%s"] * len(expected_keys))
            cursor.execute(f"DELETE FROM knowledge_fragment WHERE deleted=0 AND fragment_key NOT IN ({key_placeholders})",
                           tuple(expected_keys))
            release_id = stable_id(args.release_code)
            manifest_hash = hashlib.sha256(args.projection.read_bytes()).hexdigest()
            cursor.execute("""
                INSERT INTO knowledge_publish_version
                  (id,release_code,embedding_model,embedding_revision,embedding_version,qdrant_collection,manifest_hash,retrieval_report_json,published_at)
                VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)
                ON DUPLICATE KEY UPDATE manifest_hash=VALUES(manifest_hash),retrieval_report_json=VALUES(retrieval_report_json),published_at=VALUES(published_at)
            """, (release_id, args.release_code, args.embedding_model, args.embedding_revision, args.embedding_version,
                   args.qdrant_collection, manifest_hash, json.dumps({"fragment_count": len(fragments)}), now))
            # 按章拆分后清理旧整本教材文档及其版本记录（防止成为孤儿）
            legacy_document_id = stable_id("KB-SOURCE-PM-TEXTBOOK-4TH-0502")
            cursor.execute("DELETE FROM knowledge_document_version WHERE document_id=%s", (legacy_document_id,))
            cursor.execute("DELETE FROM knowledge_document WHERE id=%s", (legacy_document_id,))
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()
    print(json.dumps({"documents": len(documents), "fragments": len(fragments), "release": args.release_code}, ensure_ascii=False))


if __name__ == "__main__":
    main()
