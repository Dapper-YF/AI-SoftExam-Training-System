from __future__ import annotations

import csv
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import preflight  # noqa: E402


def test_pdf_docx_xlsx_dry_run_outputs_canonical_files(tmp_path: Path) -> None:
    pdf = tmp_path / "2019年数据库系统工程师上午真题及答案解析.pdf"
    docx = tmp_path / "2021年上半年数据库系统工程师下午真题及答案解析.docx"
    xlsx = tmp_path / "2026年信息系统项目管理师模拟综合知识.xlsx"
    make_pdf(pdf)
    make_docx(docx)
    make_xlsx(xlsx)

    output = tmp_path / "out"
    result = preflight.run_preflight([pdf, docx, xlsx], output)

    assert result["manifest"]["databaseWrites"] is False
    assert result["manifest"]["publish"] is False
    assert result["manifest"]["documentCount"] == 3
    assert {path.name for path in output.iterdir()} == {
        "assets.json",
        "canonical-questions.jsonl",
        "duplicates.csv",
        "errors.csv",
        "manifest.json",
        "quality-report.md",
        "unresolved-items.csv",
    }

    records = [
        json.loads(line)
        for line in (output / "canonical-questions.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    assert records
    assert {record["schemaVersion"] for record in records} == {"question-import/1.0"}
    assert {record["rightsStatus"] for record in records} == {"UNVERIFIED"}
    assert {record["importStatus"] for record in records} == {"PREFLIGHT"}
    assert any(record["qualificationCode"] == "MIDDLE_DATABASE_SYSTEM_ENGINEER" for record in records)
    assert any(record["subjectCode"] == "BASIC_KNOWLEDGE" for record in records)
    assert any(record["subjectCode"] == "APPLIED_TECHNOLOGY" for record in records)
    assert any(record["paper"]["paperKind"] == "MOCK_PAPER" for record in records)
    assert all(record["sourceTrace"]["documentSha256"] for record in records)
    assert all(record["sourceTrace"]["questionNo"] for record in records)


def test_single_multi_judge_are_classified_and_validated(tmp_path: Path) -> None:
    records = records_for_text(
        tmp_path,
        "2026年信息系统项目管理师综合知识真题.pdf",
        """
1. 下列哪一项是项目章程的作用？
A. 正式授权项目
B. 记录全部风险
答案：A
2、以下哪些属于项目文件？
A. 风险登记册
B. 问题日志
C. 采购工作说明书
答案：AB
第3题 该陈述是否正确？
答案：正确
""",
    )

    assert [record["question"]["questionType"] for record in records] == [
        "SINGLE_CHOICE",
        "MULTI_CHOICE",
        "JUDGE",
    ]
    assert all(record["quality"]["importEligible"] for record in records)


def test_case_material_has_multiple_ordered_subquestions(tmp_path: Path) -> None:
    records = records_for_text(
        tmp_path,
        "2021年上半年数据库系统工程师下午真题及答案解析.pdf",
        """
案例一
[说明]
某系统需要完成数据库迁移，共享材料用于以下问题。
[问题1]（5分）请说明停机窗口风险。
参考答案：应提前演练并设置回退方案。
[问题2]（10分）请说明数据校验方法。
参考答案：核对行数、校验和与关键业务字段。
""",
    )

    assert len(records) == 1
    question = records[0]["question"]
    assert question["questionType"] == "CASE"
    assert "共享材料" in question["stems"][0]["content"]
    assert [sub["subQuestionNo"] for sub in question["subQuestions"]] == [1, 2]
    assert [sub["score"] for sub in question["subQuestions"]] == [5, 10]
    assert all(sub["answers"] for sub in question["subQuestions"])
    assert all(sub["scoringPoints"] for sub in question["subQuestions"])
    assert [sub["scoringPoints"][0]["score"] for sub in question["subQuestions"]] == [5, 10]
    assert records[0]["quality"]["importEligible"] is True


def test_case_without_multiple_scored_subquestions_is_ineligible(tmp_path: Path) -> None:
    records = records_for_text(
        tmp_path,
        "2019年上半年数据库系统工程师下午真题及答案解析.pdf",
        """
案例一
[说明]
某系统需要完成数据库迁移。
[问题1] 请说明风险。
参考答案：应提前演练。
""",
    )

    assert len(records) == 1
    assert records[0]["quality"]["importEligible"] is False
    codes = {issue["code"] for issue in records[0]["quality"]["validationErrors"]}
    assert "CASE_SUBQUESTION_COUNT_INVALID" in codes
    assert "CASE_SCORE_MISSING" in codes
    assert "CASE_SCORING_POINTS_MISSING" in codes


def test_essay_preserves_requirements_word_count_and_reference_structure(tmp_path: Path) -> None:
    records = records_for_text(
        tmp_path,
        "2025年信息系统项目管理师论文真题.pdf",
        """
论文题：论信息系统项目的风险管理
写作要求：结合本人参与的真实项目论述风险识别、分析和应对。
字数要求：2000至2500字。
参考结构：项目概述；风险识别；定性与定量分析；风险应对；总结。
""",
    )

    assert len(records) == 1
    question = records[0]["question"]
    assert question["questionType"] == "ESSAY"
    assert question["essayRequirements"]["minWords"] == 2000
    assert question["essayRequirements"]["maxWords"] == 2500
    assert "风险识别" in question["subQuestions"][0]["solutions"][0]["content"]


def test_missing_answer_invalid_option_and_unknown_scope_are_unresolved(tmp_path: Path) -> None:
    source = tmp_path / "2021年数据库系统工程师上午真题及答案解析.docx"
    make_docx_with_text(
        source,
        """
1. 缺少答案的题目？
A. 选项一
B. 选项二
2. 选项排序断裂的题目？
A. 选项一
C. 选项三
答案：C
""",
    )
    unknown = tmp_path / "2026年未知资格题库.docx"
    make_docx_with_text(unknown, "1. 未知范围题？\nA. 是\nB. 否\n答案：A")

    result = preflight.run_preflight([source, unknown], tmp_path / "out")
    unresolved = read_csv(tmp_path / "out" / "unresolved-items.csv")

    assert result["manifest"]["unresolvedCount"] >= 3
    assert {row["code"] for row in unresolved} >= {
        "ANSWER_MISSING",
        "INVALID_OPTION_SEQUENCE",
        "SOURCE_SCOPE_UNKNOWN",
    }
    assert all(row["blocking"] == "true" for row in unresolved)


def test_duplicate_questions_are_reported_by_normalized_content(tmp_path: Path) -> None:
    source = tmp_path / "2021年数据库系统工程师上午真题及答案解析.docx"
    make_docx_with_text(
        source,
        """
1. 数据库事务的特性是？
A. 原子性
B. 随机性
答案：A
1. 数据库事务的特性是？
A. 原子性
B. 随机性
答案：A
""",
    )

    result = preflight.run_preflight([source], tmp_path / "out")

    assert result["manifest"]["duplicateCount"] == 1
    assert len(read_csv(tmp_path / "out" / "duplicates.csv")) == 1


def test_structured_xlsx_rows_keep_sheet_row_source_and_explanation(tmp_path: Path) -> None:
    source = tmp_path / "soft_exam_final_20260716_094114.xlsx"
    make_structured_xlsx(source)

    result = preflight.run_preflight([source], tmp_path / "out")

    assert result["manifest"]["questionCount"] == 3
    first = result["records"][0]
    assert first["qualificationCode"] == "HIGH_PM"
    assert first["subjectCode"] == "COMPREHENSIVE_KNOWLEDGE"
    assert first["paper"]["paperKind"] == "REAL_PAPER"
    assert first["sourceTrace"]["sheetName"] == "软考题目"
    assert first["sourceTrace"]["rowNo"] == 2
    assert first["sourceTrace"]["sourceQuestionId"] == "Q-1"
    assert first["question"]["subQuestions"][0]["solutions"][0]["content"] == "项目章程正式授权项目。"


def test_xlsx_structure_report_has_distributions_duplicates_and_mapping_gaps(tmp_path: Path) -> None:
    source = tmp_path / "soft_exam_final_20260716_094114.xlsx"
    make_structured_xlsx(source)

    report = preflight.build_xlsx_structure_report(source)

    assert report["sheetCount"] == 1
    assert report["sheets"][0]["name"] == "软考题目"
    assert report["sheets"][0]["columnCount"] == 14
    assert report["questionCount"] == 3
    assert report["questionTypeDistribution"] == {"SINGLE_CHOICE": 3}
    assert report["qualificationDistribution"]["HIGH_PM"] == 2
    assert report["qualificationDistribution"]["UNKNOWN"] == 1
    assert report["withAnswerCount"] == 3
    assert report["withExplanationCount"] == 2
    assert report["duplicateCount"] == 1
    assert report["unmappedCount"] == 1
    assert report["nineSheetTemplateCompatible"] is False
    assert report["sixtyOneColumnTemplateCompatible"] is False


def test_legacy_doc_reports_converter_capability_without_parsing(tmp_path: Path) -> None:
    doc = tmp_path / "中级数据库系统工程师上午试题模拟11.doc"
    doc.write_bytes(b"\xd0\xcf\x11\xe0legacy doc")
    output = tmp_path / "out"

    result = preflight.run_preflight([doc], output)

    assert result["manifest"]["questionCount"] == 0
    rows = read_csv(output / "errors.csv")
    assert rows
    assert rows[0]["code"] in {"DOC_CONVERTER_UNAVAILABLE", "DOC_CONVERTER_AVAILABLE"}


def test_discover_files_keeps_real_and_mock_sources_separate(tmp_path: Path) -> None:
    real = tmp_path / "2018年数据库系统工程师上午真题及答案解析.pdf"
    mock = tmp_path / "2026年信息系统项目管理师模拟综合知识.pdf"
    make_pdf(real)
    make_pdf(mock)

    files = preflight.discover_files([tmp_path], {2018, 2026}, include_doc=False)
    result = preflight.run_preflight(files, tmp_path / "out")

    kinds = result["manifest"]["paperKindCounts"]
    assert kinds["REAL_PAPER"] >= 1
    assert kinds["MOCK_PAPER"] >= 1


def test_discover_files_keeps_explicit_file_outside_directory_year_filter(tmp_path: Path) -> None:
    source_dir = tmp_path / "database"
    source_dir.mkdir()
    real = source_dir / "2018骞存暟鎹簱绯荤粺宸ョ▼甯堜笂鍗堢湡棰樺強绛旀瑙ｆ瀽.pdf"
    mock = tmp_path / "2026骞翠俊鎭郴缁熼」鐩鐞嗗笀妯℃嫙缁煎悎鐭ヨ瘑.pdf"
    make_pdf(real)
    make_pdf(mock)

    files = preflight.discover_files([source_dir, mock], {2017, 2018, 2019, 2020, 2021}, include_doc=False)

    assert real in files
    assert mock in files


def test_mixed_database_source_emits_auditable_segments(tmp_path: Path) -> None:
    document = source_document(
        tmp_path / "2019年上半年数据库系统工程师上午+下午真题及参考答案.pdf",
        [
            (1, "上午综合试卷\n试题(1) 数据库事务的特性是？\nA. 示例选项\n答案：A"),
            (2, "下午案例分析\n试题(1) 阅读以下材料，回答问题。\n答案：控制风险"),
        ],
    )
    scope = preflight.infer_scope(document.path)

    split_plan = preflight.build_split_plan(document, scope)
    records = preflight.build_question_records(document, scope, split_plan)

    assert split_plan["status"] == "SEGMENTED"
    assert [segment["subjectCode"] for segment in split_plan["segments"]] == [
        "BASIC_KNOWLEDGE",
        "APPLIED_TECHNOLOGY",
    ]
    assert {record["subjectCode"] for record in records} == {"BASIC_KNOWLEDGE", "APPLIED_TECHNOLOGY"}
    assert all(record["sourceTrace"]["segmentId"] for record in records)


def test_mixed_advanced_pm_source_emits_three_segments(tmp_path: Path) -> None:
    document = source_document(
        tmp_path / "2026年信息系统项目管理师一模试卷-知乎知学堂（有答案）.pdf",
        [
            (1, "综合知识\n试题(1) 项目章程的作用是？\nA. 示例选项\n答案：A"),
            (2, "案例分析\n试题(1) 阅读材料，回答问题。\n答案：说明原因"),
            (3, "论文\n试题(1) 论信息系统项目的风险管理。\n答案：论述"),
        ],
    )
    scope = preflight.infer_scope(document.path)

    split_plan = preflight.build_split_plan(document, scope)
    records = preflight.build_question_records(document, scope, split_plan)

    assert split_plan["status"] == "SEGMENTED"
    assert [segment["subjectCode"] for segment in split_plan["segments"]] == [
        "COMPREHENSIVE_KNOWLEDGE",
        "CASE_ANALYSIS",
        "ESSAY",
    ]
    assert {record["subjectCode"] for record in records} == {
        "COMPREHENSIVE_KNOWLEDGE",
        "CASE_ANALYSIS",
        "ESSAY",
    }


def test_mixed_advanced_pm_split_keeps_content_on_shared_boundary_pages(tmp_path: Path) -> None:
    document = source_document(
        tmp_path / "2026年信息系统项目管理师一模试卷-知乎知学堂（有答案）.pdf",
        [
            (
                1,
                "软 考 高 级-1 模-2026 上 半 年-选 择 题\n"
                "75. 最后一题？\nA. 是\nB. 否\n答案：A\n"
                "软 考 高 级-1 模-2026 上 半 年-案 例 题\n"
                "试题一\n【说明】共享案例材料。\n【问题1】（5分）问题？\n参考答案：案例答案。",
            ),
            (
                2,
                "【问题2】（5分）第二问？\n参考答案：第二问答案。\n"
                "软 考 高 级-1 模-2026 上 半 年-论 文\n"
                "论文题：论项目风险管理\n写作要求：结合实际项目。\n"
                "字数要求：2000至2500字。\n参考结构：识别、分析、应对。",
            ),
        ],
    )
    scope = preflight.infer_scope(document.path)

    split_plan = preflight.build_split_plan(document, scope)
    records = preflight.build_question_records(document, scope, split_plan)

    assert split_plan["status"] == "SEGMENTED"
    assert [record["subjectCode"] for record in records] == [
        "COMPREHENSIVE_KNOWLEDGE",
        "CASE_ANALYSIS",
        "ESSAY",
    ]
    assert records[0]["sourceTrace"]["questionNo"] == 75
    assert len(records[1]["question"]["subQuestions"]) == 2


def test_mixed_source_without_reliable_anchors_stays_fail_closed(tmp_path: Path) -> None:
    document = source_document(
        tmp_path / "2019年上半年数据库系统工程师上午+下午真题及参考答案.pdf",
        [(1, "试题(1) 数据库事务的特性是？\nA. 示例选项\n答案：A")],
    )
    scope = preflight.infer_scope(document.path)

    split_plan = preflight.build_split_plan(document, scope)
    records = preflight.build_question_records(document, scope, split_plan)

    assert split_plan["status"] == "FAILED_CLOSED"
    assert split_plan["errors"][0]["code"] == "DATABASE_MIXED_SUBJECT_SOURCE"
    assert records == []


def make_pdf(path: Path) -> None:
    reportlab = pytest.importorskip("reportlab.pdfgen.canvas")
    pdf_path = ascii_pdf_target(path)
    canvas = reportlab.Canvas(str(pdf_path))
    canvas.drawString(72, 750, "试题(1) 数据库事务的特性是？")
    canvas.drawString(72, 730, "A. 原子性")
    canvas.drawString(72, 710, "B. 随机性")
    canvas.drawString(72, 690, "答案：A")
    canvas.showPage()
    canvas.save()
    if pdf_path != path:
        pdf_path.replace(path)


def ascii_pdf_target(path: Path) -> Path:
    if str(path).isascii():
        return path
    return path.with_name("fixture.pdf")


def source_document(path: Path, pages: list[tuple[int, str]]) -> preflight.SourceDocument:
    return preflight.SourceDocument(
        path=path,
        sha256="a" * 64,
        size_bytes=1024,
        pages=[preflight.ExtractedPage(page_no, text) for page_no, text in pages],
        assets=[],
        warnings=[],
    )


def records_for_text(tmp_path: Path, file_name: str, text: str) -> list[dict[str, object]]:
    document = source_document(tmp_path / file_name, [(1, text.strip())])
    scope = preflight.infer_scope(document.path)
    split_plan = preflight.build_split_plan(document, scope)
    return preflight.build_question_records(document, scope, split_plan)


def make_docx(path: Path) -> None:
    docx = pytest.importorskip("docx")
    document = docx.Document()
    document.add_paragraph("试题(1) 阅读以下材料，回答问题。")
    document.add_paragraph("某系统需要完成数据迁移，请说明风险。")
    document.add_paragraph("答案：控制停机窗口。")
    document.save(path)


def make_docx_with_text(path: Path, text: str) -> None:
    docx = pytest.importorskip("docx")
    document = docx.Document()
    for line in text.strip().splitlines():
        document.add_paragraph(line)
    document.save(path)


def make_xlsx(path: Path) -> None:
    openpyxl = pytest.importorskip("openpyxl")
    workbook = openpyxl.Workbook()
    sheet = workbook.active
    sheet.title = "Questions"
    sheet.append(["question"])
    sheet.append(["试题(1) 项目管理一模选择题？"])
    sheet.append(["A. 范围基准"])
    sheet.append(["B. 风险登记册"])
    sheet.append(["答案：A"])
    workbook.save(path)


def make_structured_xlsx(path: Path) -> None:
    openpyxl = pytest.importorskip("openpyxl")
    workbook = openpyxl.Workbook()
    sheet = workbook.active
    sheet.title = "软考题目"
    sheet.append([
        "来源", "科目", "科目名称", "年份", "题目ID", "类型", "题目内容",
        "选项", "答案", "解析", "章节", "知识点", "URL", "抓取时间",
    ])
    sheet.append([
        "软考通（真题）", "信息系统项目管理师", "综合知识（选择题）", 2024, "Q-1",
        "single_choice", "项目章程的作用是？", "A. 正式授权项目\nB. 记录全部风险", "A",
        "项目章程正式授权项目。", "项目整合管理", "项目章程", "https://example.test/q/1", "2026-07-16",
    ])
    sheet.append([
        "软考通（真题）", "信息系统项目管理师", "综合知识（选择题）", 2024, "Q-2",
        "single_choice", "项目章程的作用是？", "A. 正式授权项目\nB. 记录全部风险", "A",
        "", "项目整合管理", "项目章程", "https://example.test/q/2", "2026-07-16",
    ])
    sheet.append([
        "社区题库", "未知资格", "未知科目", "", "Q-3", "single_choice", "未知范围题？",
        "A. 是\nB. 否", "A", "待核验。", "", "", "https://example.test/q/3", "2026-07-16",
    ])
    workbook.save(path)


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))
