from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from build_source_fragments import PageContent  # noqa: E402
from build_source_subfragments import create_subfragments  # noqa: E402


class KnowledgePipelineTest(unittest.TestCase):
    def test_qualified_fragments_are_publishable_and_single_char_noise_is_removed(self) -> None:
        pages = [
            PageContent(
                page=11,
                confidence=0.95,
                lines=["1.1 示例", "甲", "这是第一段有效教材内容。" * 60, "Page 11", "这是第二段有效教材内容。" * 60],
            )
        ]
        parents = [{
            "fragment_id": "PARENT-1",
            "source_id": "BOOK",
            "chapter": "第1章 示例",
            "section": "1.1 示例",
            "start_page": 11,
            "end_page": 11,
            "start_line": 0,
            "end_line": len(pages[0].lines),
        }]

        fragments = create_subfragments(pages, parents, 0.90, 1200, 100)

        self.assertTrue(fragments)
        self.assertTrue(all(item.quality_status == "QUALIFIED" for item in fragments))
        self.assertTrue(all(item.char_count >= 600 for item in fragments))
        self.assertTrue(all(item.char_count <= 1200 for item in fragments))
        self.assertTrue(all(item.content.strip() != "甲" for item in fragments))
        self.assertTrue(all("Page 11" not in item.content for item in fragments))

    def test_projection_filter_accepts_qualified_and_approved_only(self) -> None:
        source = (SCRIPT_DIR / "build_published_projection.py").read_text(encoding="utf-8")
        self.assertIn('{"QUALIFIED", "APPROVED"}', source)
        self.assertNotIn('item["quality_status"] == "APPROVED"', source)

    def test_sync_aggregates_document_raw_content(self) -> None:
        source = (SCRIPT_DIR / "sync_published_to_mysql.py").read_text(encoding="utf-8")
        self.assertIn('"\\n\\n".join(document_contents[code])', source)
        self.assertIn("if fragment_numbers[document_id] == 1:", source)


if __name__ == "__main__":
    unittest.main()
