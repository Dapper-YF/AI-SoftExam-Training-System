"""Idempotently normalize the three development real-paper display names."""

from __future__ import annotations

import json
import os

import pymysql


NAMES = {
    910001: "2026年信息系统项目管理师综合知识真题",
    910002: "2026年信息系统项目管理师案例分析真题",
    910003: "2026年信息系统项目管理师论文真题",
}

connection = pymysql.connect(
    host=os.getenv("MYSQL_HOST", "127.0.0.1"),
    port=int(os.getenv("MYSQL_PORT", "33307")),
    user=os.getenv("MYSQL_USER", "aiexam_app"),
    password=os.getenv("MYSQL_PASSWORD", "123123"),
    database=os.getenv("MYSQL_DATABASE", "ai_exam_platform"),
    charset="utf8mb4",
    autocommit=False,
)
try:
    with connection.cursor() as cursor:
        for paper_id, paper_name in NAMES.items():
            cursor.execute(
                "UPDATE q_paper SET paper_name=%s, updated_at=CURRENT_TIMESTAMP(3) "
                "WHERE id=%s AND paper_type='REAL' AND deleted=0",
                (paper_name, paper_id),
            )
        cursor.execute(
            "SELECT id,paper_name FROM q_paper "
            "WHERE id IN (910001,910002,910003) ORDER BY id"
        )
        rows = cursor.fetchall()
        if dict(rows) != NAMES:
            raise RuntimeError(f"real paper readiness mismatch: {rows}")
    connection.commit()
    print(json.dumps({"updated_papers": len(NAMES), "papers": rows}, ensure_ascii=False))
except Exception:
    connection.rollback()
    raise
finally:
    connection.close()
