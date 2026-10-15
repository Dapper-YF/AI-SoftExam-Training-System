"""Read-only runtime data audit for the production-readiness checklist."""

from __future__ import annotations

import json
import os

import pymysql


connection = pymysql.connect(
    host=os.getenv("MYSQL_HOST", "127.0.0.1"),
    port=int(os.getenv("MYSQL_PORT", "33307")),
    user=os.getenv("MYSQL_USER", "aiexam_app"),
    password=os.getenv("MYSQL_PASSWORD", "123123"),
    database=os.getenv("MYSQL_DATABASE", "ai_exam_platform"),
    charset="utf8mb4",
)
try:
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT * FROM q_paper "
            "WHERE id IN (910001,910002,910003) ORDER BY id"
        )
        paper_columns = [column[0] for column in cursor.description]
        papers = [dict(zip(paper_columns, row)) for row in cursor.fetchall()]
        cursor.execute(
            "SELECT COUNT(*) FROM knowledge_fragment "
            "WHERE status='PUBLISHED' AND deleted=0"
        )
        published_fragments = cursor.fetchone()[0]
        cursor.execute("SELECT COUNT(*) FROM kp_syllabus_node WHERE deleted=0")
        knowledge_points = cursor.fetchone()[0]
        query = "信息系统项目管理师考试中十大知识领域是什么"
        cursor.execute(
            "SELECT fragment_key,content_kind,source_pages,"
            "MATCH(content) AGAINST (%s IN NATURAL LANGUAGE MODE) score "
            "FROM knowledge_fragment WHERE status='PUBLISHED' "
            "AND index_status='INDEXED' AND invalidated_at IS NULL AND deleted=0 "
            "AND MATCH(content) AGAINST (%s IN NATURAL LANGUAGE MODE) "
            "ORDER BY score DESC LIMIT 8",
            (query, query),
        )
        bm25_top8 = cursor.fetchall()
    print(
        json.dumps(
            {
                "papers": papers,
                "published_fragments": published_fragments,
                "knowledge_points": knowledge_points,
                "bm25_top8": bm25_top8,
            },
            ensure_ascii=False,
            default=str,
        )
    )
finally:
    connection.close()
