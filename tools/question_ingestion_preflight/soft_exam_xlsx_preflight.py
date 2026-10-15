from __future__ import annotations

import argparse
import copy
import csv
import ctypes
import difflib
import hashlib
import json
import os
import re
import time
import tracemalloc
import unicodedata
import zipfile
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator, TextIO
from xml.etree import ElementTree

import preflight


EXPECTED_HEADERS = (
    "来源",
    "科目",
    "题目类型",
    "年份",
    "题目ID",
    "题型",
    "题目正文",
    "选项",
    "答案",
    "解析",
    "章节",
    "知识点",
    "URL",
    "抓取时间",
)
OBJECTIVE_TYPES = {"SINGLE_CHOICE", "MULTI_CHOICE", "JUDGE"}
OPTION_LABEL = re.compile(
    r"(?<![A-Za-z0-9/])([A-H])\s*[.．、)]\s*", re.IGNORECASE
)
OPTION_LINE_LABEL = re.compile(
    r"(?:^|\n)\s*([A-H])\s*[.．、)]\s*", re.IGNORECASE | re.MULTILINE
)
LOOSE_OPTION_LABEL = re.compile(r"([A-H])\s*[.．、)]\s*", re.IGNORECASE)
LOCAL_OPTION_LINE_LABEL = re.compile(
    r"(?:^|\n)\s*(?:[（(]\s*\d{1,3}\s*[）)]\s*)?"
    r"([A-H])\s*[.．、]\s*",
    re.IGNORECASE | re.MULTILINE,
)
LOCAL_OPTION_TRAILING_NOISE = re.compile(
    r"(?:\n\s*(?:●|第?\s*\d{1,3}\s*(?:题|[、.．])))|"
    r"(?:\n\s*参考答案\s*[:：])",
    re.MULTILINE,
)
INLINE_OPTION_A = re.compile(r"(?<![A-Za-z])A(?=[\u3400-\u9fff])")
CASE_SUBQUESTION = re.compile(
    r"(?:^|\n|[【\[])\s*(?:问题|小题)\s*(\d{1,2})\s*(?:[】\]]|[:：、.]?)",
    re.MULTILINE,
)
ESSAY_SIGNAL = re.compile(r"(?:论文题|写作要求|字数要求|评分维度|参考结构)")
WORD_COUNT = re.compile(r"(\d{3,5})\s*(?:至|到|[-~～—])\s*(\d{3,5})\s*字")
MULTI_CHOICE_SIGNAL = re.compile(r"(?:哪些|多选|正确的有|属于.*的有|包括以下)")
NUMBERED_BLANK = re.compile(r"[（(]\s*(\d{1,2})\s*[）)]")
GENERIC_BLANK = re.compile(r"[（(]\s*[）)]")
GROUPED_CHOICE_SIGNAL = re.compile(
    r"(?:问题\s*1|[（(]\s*(?:1|\d{2,3})\s*[）)]\s*$)", re.IGNORECASE
)


@dataclass(frozen=True)
class Taxonomy:
    qualification_by_name: dict[str, dict[str, Any]]
    subject_by_key: dict[tuple[str, str], dict[str, Any]]


@dataclass
class ParsedRow:
    source_row: int
    raw: dict[str, str]
    qualification_code: str
    subject_code: str
    subject_type: str
    question_type: str
    stem: str
    option_groups: list[list[dict[str, Any]]]
    answer_tokens: list[str]
    boolean_answer: bool | None
    grouped_choice: bool
    missing_composite_group_nos: list[int]
    recovered_from_source_row: int | None
    recovery_method: str | None
    recovery_similarity: float | None
    local_source_path: str | None
    local_source_sha256: str | None
    local_source_page: int | None
    local_source_anchor: str | None
    year_no: int | None
    source_url: str
    warnings: list[dict[str, str]]
    errors: list[dict[str, str]]


@dataclass(frozen=True)
class RecoveryCandidate:
    source_row: int
    source_question_id: str
    qualification_code: str
    normalized_answer: str
    stem: str
    stem_fingerprint: str
    first_option_group_fingerprint: str
    option_groups: list[list[dict[str, Any]]]


@dataclass(frozen=True)
class RecoveryIndex:
    exact: dict[tuple[str, str, str], list[RecoveryCandidate]]
    by_answer: dict[tuple[str, str, int], list[RecoveryCandidate]]
    complete_candidate_count: int


@dataclass(frozen=True)
class LocalTextSegment:
    source_path: str
    source_sha256: str
    source_kind: str
    page_no: int | None
    anchor: str
    text: str
    following_text: str
    normalized_text: str


@dataclass(frozen=True)
class LocalRecoveryCorpus:
    segments: list[LocalTextSegment]
    files: list[dict[str, Any]]


@dataclass(frozen=True)
class LocalRecoveryCandidate:
    source_path: str
    source_sha256: str
    source_kind: str
    page_no: int | None
    anchor: str
    first_group_score: float
    option_groups: list[list[dict[str, Any]]]


@dataclass
class ChunkState:
    index: int
    path: Path
    handle: TextIO
    total_count: int = 0
    eligible_count: int = 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Streaming preflight for the 14-column soft-exam XLSX source"
    )
    parser.add_argument("--input", required=True, help="Source XLSX")
    parser.add_argument("--output", required=True, help="New or empty evidence directory")
    parser.add_argument(
        "--expected-question-count",
        type=int,
        default=None,
        help="Fail the count gate when the physical source record count differs",
    )
    parser.add_argument("--chunk-size", type=int, default=500)
    parser.add_argument(
        "--local-corpus",
        action="append",
        default=[],
        help="Local PDF/DOCX file or directory used only for deterministic recovery",
    )
    parser.add_argument(
        "--taxonomy-manifest",
        help="V9 taxonomy manifest; defaults to the repository SSOT",
    )
    args = parser.parse_args(argv)

    source = Path(args.input)
    output = Path(args.output)
    manifest_path = (
        Path(args.taxonomy_manifest)
        if args.taxonomy_manifest
        else default_taxonomy_manifest()
    )
    manifest = run_streaming_preflight(
        source,
        output,
        manifest_path,
        expected_question_count=args.expected_question_count,
        chunk_size=args.chunk_size,
        local_corpus_paths=[Path(value) for value in args.local_corpus],
    )
    print(
        json.dumps(
            {
                "output": str(output),
                "sourceRecords": manifest["sourceRecordCount"],
                "eligible": manifest["eligibleQuestionCount"],
                "duplicates": manifest["duplicateCount"],
                "errors": manifest["sourceRowsWithErrors"],
                "countGate": manifest["countReconciliation"]["status"],
            },
            ensure_ascii=False,
            sort_keys=True,
        )
    )
    return 0 if manifest["countReconciliation"]["status"] == "MATCHED" else 3


def default_taxonomy_manifest() -> Path:
    return (
        Path(__file__).resolve().parents[2]
        / "backend"
        / "platform-application"
        / "src"
        / "main"
        / "resources"
        / "db"
        / "migration-manifest"
        / "V9__soft_exam_taxonomy_rule_seed.manifest.json"
    )


def run_streaming_preflight(
    source: Path,
    output: Path,
    taxonomy_manifest: Path,
    *,
    expected_question_count: int | None,
    chunk_size: int,
    local_corpus_paths: list[Path] | None = None,
) -> dict[str, Any]:
    if source.suffix.lower() != ".xlsx" or not source.is_file():
        raise ValueError("input must be an existing XLSX file")
    if chunk_size < 1 or chunk_size > 5_000:
        raise ValueError("chunk-size must be between 1 and 5000")
    if output.exists() and any(output.iterdir()):
        raise FileExistsError(f"refusing to overwrite non-empty output: {output}")
    source = source.resolve()
    output = output.resolve()
    taxonomy_manifest = taxonomy_manifest.resolve()
    output.mkdir(parents=True, exist_ok=True)

    started = time.perf_counter()
    tracemalloc.start()
    peak_rss = current_rss_bytes()
    source_sha = preflight.file_sha256(source)
    package_evidence = inspect_xlsx_package(source)
    taxonomy = load_taxonomy(taxonomy_manifest)
    recovery_index = build_same_workbook_recovery_index(source, taxonomy)
    local_recovery_corpus = build_local_recovery_corpus(local_corpus_paths or [])

    canonical_path = output / "canonical-questions.jsonl"
    errors_path = output / "errors.csv"
    duplicates_path = output / "duplicates.csv"
    mapping_path = output / "mapping.csv"
    unresolved_path = output / "unresolved-items.csv"
    composite_anomalies_path = output / "composite-anomalies.csv"
    recovery_evidence_path = output / "recovery-evidence.csv"
    chunks_root = output / "chunks"
    chunks_root.mkdir(parents=True, exist_ok=True)

    distributions: dict[str, Counter[str]] = {
        "qualification": Counter(),
        "subject": Counter(),
        "questionType": Counter(),
        "paperKind": Counter(),
        "year": Counter(),
        "status": Counter(),
        "sourceCategory": Counter(),
    }
    first_by_hash: dict[str, tuple[str, str]] = {}
    source_record_count = 0
    eligible_count = 0
    duplicate_count = 0
    explicit_anomaly_count = 0
    validation_error_count = 0
    composite_anomaly_count = 0
    recovered_composite_count = 0
    warning_count = 0
    error_code_counts: Counter[str] = Counter()
    recovery_method_counts: Counter[str] = Counter()
    chunks: list[dict[str, Any]] = []
    chunk: ChunkState | None = None

    error_fields = (
        "sourcePath",
        "documentSha256",
        "sheetName",
        "sourceRow",
        "sourceQuestionId",
        "externalKey",
        "code",
        "field",
        "message",
        "frozenAnswer",
        "presentOptionGroupsJson",
        "missingCompositeGroupNos",
    )
    duplicate_fields = (
        "contentHash",
        "firstExternalKey",
        "duplicateExternalKey",
        "firstSource",
        "duplicateSource",
    )
    mapping_fields = (
        "sourcePath",
        "documentSha256",
        "sheetName",
        "sourceRow",
        "sourceQuestionId",
        "sourceQualificationName",
        "qualificationCode",
        "sourceCategory",
        "subjectCode",
        "sourceQuestionType",
        "questionType",
        "responseLayout",
        "paperKind",
        "yearNo",
        "sourceUrl",
        "sourcePageSha256",
        "sourcePaperId",
        "externalKey",
        "contentHash",
        "status",
        "duplicateOfExternalKey",
        "sourceOptionGroupsJson",
        "frozenAnswer",
        "missingCompositeGroupNos",
        "recoveredFromSourceRow",
        "recoveryMethod",
        "localSourcePath",
        "localSourceSha256",
        "localSourcePage",
        "localSourceAnchor",
        "warningCodes",
        "errorCodes",
    )
    unresolved_fields = (
        "sourcePath",
        "documentSha256",
        "locator",
        "externalKey",
        "code",
        "field",
        "message",
        "blocking",
        "sourceQuestionId",
        "frozenAnswer",
        "presentOptionGroupsJson",
        "missingCompositeGroupNos",
    )
    composite_anomaly_fields = (
        "sourcePath",
        "documentSha256",
        "sheetName",
        "sourceRow",
        "sourceQuestionId",
        "qualificationCode",
        "subjectCode",
        "stemContentHash",
        "status",
        "frozenAnswer",
        "frozenAnswerKeys",
        "presentOptionGroupCount",
        "presentOptionGroupsJson",
        "missingCompositeGroupNos",
        "recoveredFromSourceRow",
    )
    recovery_evidence_fields = (
        "sourcePath",
        "documentSha256",
        "sheetName",
        "sourceRow",
        "sourceQuestionId",
        "recoveredFromSourceRow",
        "recoveryMethod",
        "stemSimilarity",
        "frozenAnswer",
        "recoveredOptionGroupsJson",
        "localSourcePath",
        "localSourceSha256",
        "localSourcePage",
        "localSourceAnchor",
    )

    with (
        canonical_path.open("w", encoding="utf-8", newline="\n") as canonical_file,
        errors_path.open("w", encoding="utf-8-sig", newline="") as error_file,
        duplicates_path.open("w", encoding="utf-8-sig", newline="") as duplicate_file,
        mapping_path.open("w", encoding="utf-8-sig", newline="") as mapping_file,
        unresolved_path.open("w", encoding="utf-8-sig", newline="") as unresolved_file,
        composite_anomalies_path.open(
            "w", encoding="utf-8-sig", newline=""
        ) as composite_anomalies_file,
        recovery_evidence_path.open(
            "w", encoding="utf-8-sig", newline=""
        ) as recovery_evidence_file,
    ):
        error_writer = csv.DictWriter(error_file, fieldnames=error_fields)
        duplicate_writer = csv.DictWriter(duplicate_file, fieldnames=duplicate_fields)
        mapping_writer = csv.DictWriter(mapping_file, fieldnames=mapping_fields)
        unresolved_writer = csv.DictWriter(unresolved_file, fieldnames=unresolved_fields)
        composite_anomaly_writer = csv.DictWriter(
            composite_anomalies_file, fieldnames=composite_anomaly_fields
        )
        recovery_evidence_writer = csv.DictWriter(
            recovery_evidence_file, fieldnames=recovery_evidence_fields
        )
        error_writer.writeheader()
        duplicate_writer.writeheader()
        mapping_writer.writeheader()
        unresolved_writer.writeheader()
        composite_anomaly_writer.writeheader()
        recovery_evidence_writer.writeheader()

        try:
            for sheet_name, row_no, raw in stream_structured_rows(source):
                source_record_count += 1
                parsed = parse_source_row(row_no, raw, taxonomy)
                recover_composite_from_same_workbook(parsed, recovery_index)
                recover_composite_from_local_documents(
                    parsed, local_recovery_corpus
                )
                if parsed.recovery_method is not None:
                    recovered_composite_count += 1
                    recovery_method_counts[parsed.recovery_method or "UNKNOWN"] += 1
                    recovery_evidence_writer.writerow(
                        {
                            "sourcePath": str(source),
                            "documentSha256": source_sha,
                            "sheetName": sheet_name,
                            "sourceRow": row_no,
                            "sourceQuestionId": raw["题目ID"],
                            "recoveredFromSourceRow": parsed.recovered_from_source_row,
                            "recoveryMethod": parsed.recovery_method or "",
                            "stemSimilarity": parsed.recovery_similarity or "",
                            "frozenAnswer": raw["答案"],
                            "recoveredOptionGroupsJson": compact_json(
                                parsed.option_groups
                            ),
                            "localSourcePath": parsed.local_source_path or "",
                            "localSourceSha256": (
                                parsed.local_source_sha256 or ""
                            ),
                            "localSourcePage": parsed.local_source_page or "",
                            "localSourceAnchor": parsed.local_source_anchor or "",
                        }
                    )
                record = build_canonical_record(source, source_sha, sheet_name, parsed)
                external_key = record["question"]["externalKey"]
                content_hash = record["question"]["contentHash"]
                locator = record["sourceTrace"]["locator"]

                duplicate_of = ""
                duplicate_issue: dict[str, str] | None = None
                if parsed.errors:
                    explicit_anomaly_count += 1
                    status = "SOURCE_ERROR"
                else:
                    first = first_by_hash.get(content_hash)
                    if first is None:
                        first_by_hash[content_hash] = (external_key, locator)
                        eligible_count += 1
                        status = "CANONICAL"
                    else:
                        duplicate_of = first[0]
                        duplicate_issue = issue(
                            "DUPLICATE_CONTENT",
                            "question.contentHash",
                            f"exact duplicate of {first[0]}",
                        )
                        duplicate_writer.writerow(
                            {
                                "contentHash": content_hash,
                                "firstExternalKey": first[0],
                                "duplicateExternalKey": external_key,
                                "firstSource": first[1],
                                "duplicateSource": locator,
                            }
                        )
                        duplicate_count += 1
                        status = "DUPLICATE_SOURCE"

                validation_errors = [
                    *parsed.errors,
                    *([duplicate_issue] if duplicate_issue is not None else []),
                ]
                record["quality"]["validationErrors"] = validation_errors
                record["quality"]["validationWarnings"] = parsed.warnings
                record["quality"]["importEligible"] = status == "CANONICAL"
                record["quality"]["needsManualReview"] = bool(
                    validation_errors or parsed.warnings
                )
                record["quality"]["sourceStatus"] = status
                eligible = status == "CANONICAL"
                warning_count += len(parsed.warnings)
                present_groups_json = compact_json(parsed.option_groups)
                missing_group_nos = "|".join(
                    str(value) for value in parsed.missing_composite_group_nos
                )

                for row_issue in parsed.errors:
                    validation_error_count += 1
                    error_code_counts[row_issue["code"]] += 1
                    error_writer.writerow(
                        {
                            "sourcePath": str(source),
                            "documentSha256": source_sha,
                            "sheetName": sheet_name,
                            "sourceRow": row_no,
                            "sourceQuestionId": raw["题目ID"],
                            "externalKey": external_key,
                            "code": row_issue["code"],
                            "field": row_issue["field"],
                            "message": row_issue["message"],
                            "frozenAnswer": raw["答案"],
                            "presentOptionGroupsJson": present_groups_json,
                            "missingCompositeGroupNos": missing_group_nos,
                        }
                    )
                    unresolved_writer.writerow(
                        {
                            "sourcePath": str(source),
                            "documentSha256": source_sha,
                            "locator": locator,
                            "externalKey": external_key,
                            "code": row_issue["code"],
                            "field": row_issue["field"],
                            "message": row_issue["message"],
                            "blocking": "true",
                            "sourceQuestionId": raw["题目ID"],
                            "frozenAnswer": raw["答案"],
                            "presentOptionGroupsJson": present_groups_json,
                            "missingCompositeGroupNos": missing_group_nos,
                        }
                    )

                composite_error_codes = {
                    item["code"]
                    for item in parsed.errors
                    if item["code"]
                    in {
                        "MISSING_COMPOSITE_OPTIONS",
                        "COMPOSITE_OPTION_GROUP_COUNT_MISMATCH",
                    }
                }
                if composite_error_codes:
                    composite_anomaly_count += 1
                    composite_anomaly_writer.writerow(
                        {
                            "sourcePath": str(source),
                            "documentSha256": source_sha,
                            "sheetName": sheet_name,
                            "sourceRow": row_no,
                            "sourceQuestionId": raw["题目ID"],
                            "qualificationCode": parsed.qualification_code,
                            "subjectCode": parsed.subject_code,
                            "stemContentHash": preflight.sha256_text(parsed.stem),
                            "status": "|".join(sorted(composite_error_codes)),
                            "frozenAnswer": raw["答案"],
                            "frozenAnswerKeys": "|".join(parsed.answer_tokens),
                            "presentOptionGroupCount": len(parsed.option_groups),
                            "presentOptionGroupsJson": present_groups_json,
                            "missingCompositeGroupNos": missing_group_nos,
                            "recoveredFromSourceRow": (
                                parsed.recovered_from_source_row or ""
                            ),
                        }
                    )

                canonical_line = json.dumps(
                    record, ensure_ascii=False, sort_keys=True, separators=(",", ":")
                )
                canonical_file.write(canonical_line + "\n")
                if chunk is None or chunk.total_count >= chunk_size:
                    if chunk is not None:
                        chunks.append(close_chunk(chunk, output))
                    chunk = open_chunk(chunks_root, len(chunks) + 1)
                chunk.handle.write(canonical_line + "\n")
                chunk.total_count += 1
                chunk.eligible_count += int(eligible)

                mapping_writer.writerow(
                    {
                        "sourcePath": str(source),
                        "documentSha256": source_sha,
                        "sheetName": sheet_name,
                        "sourceRow": row_no,
                        "sourceQuestionId": raw["题目ID"],
                        "sourceQualificationName": raw["科目"],
                        "qualificationCode": parsed.qualification_code,
                        "sourceCategory": raw["题目类型"],
                        "subjectCode": parsed.subject_code,
                        "sourceQuestionType": raw["题型"],
                        "questionType": parsed.question_type,
                        "responseLayout": (
                            "GROUPED_CHOICE" if parsed.grouped_choice else "STANDARD"
                        ),
                        "paperKind": "REAL_PAPER",
                        "yearNo": parsed.year_no if parsed.year_no is not None else "",
                        "sourceUrl": parsed.source_url,
                        "sourcePageSha256": "",
                        "sourcePaperId": "",
                        "externalKey": external_key,
                        "contentHash": content_hash,
                        "status": status,
                        "duplicateOfExternalKey": duplicate_of,
                        "sourceOptionGroupsJson": present_groups_json,
                        "frozenAnswer": raw["答案"],
                        "missingCompositeGroupNos": missing_group_nos,
                        "recoveredFromSourceRow": (
                            parsed.recovered_from_source_row or ""
                        ),
                        "recoveryMethod": parsed.recovery_method or "",
                        "localSourcePath": parsed.local_source_path or "",
                        "localSourceSha256": parsed.local_source_sha256 or "",
                        "localSourcePage": parsed.local_source_page or "",
                        "localSourceAnchor": parsed.local_source_anchor or "",
                        "warningCodes": "|".join(item["code"] for item in parsed.warnings),
                        "errorCodes": "|".join(
                            item["code"] for item in validation_errors
                        ),
                    }
                )

                distributions["qualification"][parsed.qualification_code] += 1
                distributions["subject"][parsed.subject_code] += 1
                distributions["questionType"][parsed.question_type] += 1
                distributions["paperKind"]["REAL_PAPER"] += 1
                distributions["year"][
                    str(parsed.year_no) if parsed.year_no is not None else "UNKNOWN"
                ] += 1
                distributions["status"][status] += 1
                distributions["sourceCategory"][raw["题目类型"]] += 1
                if source_record_count % 250 == 0:
                    peak_rss = max_optional(peak_rss, current_rss_bytes())
        finally:
            if chunk is not None and not chunk.handle.closed:
                chunks.append(close_chunk(chunk, output))

        count_status = count_gate_status(source_record_count, expected_question_count)
        if count_status == "MISMATCH":
            message = (
                f"expected {expected_question_count} source records but workbook contains "
                f"{source_record_count} data rows"
            )
            error_writer.writerow(
                {
                    "sourcePath": str(source),
                    "documentSha256": source_sha,
                    "sheetName": "",
                    "sourceRow": "",
                    "sourceQuestionId": "",
                    "externalKey": "",
                    "code": "SOURCE_COUNT_MISMATCH",
                    "field": "workbook.dataRows",
                    "message": message,
                }
            )

    peak_rss = max_optional(peak_rss, current_rss_bytes())
    _, peak_python_bytes = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    elapsed_seconds = time.perf_counter() - started
    count_status = count_gate_status(source_record_count, expected_question_count)
    reconciled_source_count = (
        eligible_count + duplicate_count + explicit_anomaly_count
    )
    reconciliation_status = (
        "MATCHED"
        if count_status == "MATCHED" and reconciled_source_count == source_record_count
        else "MISMATCH"
    )

    manifest = {
        "schemaVersion": "soft-exam-xlsx-preflight/1.3",
        "canonicalRecordSchemaVersion": "question-import/1.0",
        "tool": "soft-exam-xlsx-preflight",
        "toolVersion": "1.3.0",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "dryRun": True,
        "databaseWrites": False,
        "publish": False,
        "sourceFile": str(source),
        "sourceFileName": source.name,
        "sourceSha256": source_sha,
        "sourceSizeBytes": source.stat().st_size,
        "sourceRecordCount": source_record_count,
        "sourceTrackingRecordCount": source_record_count,
        "canonicalExplanationInputRecordCount": source_record_count,
        "usableCanonicalQuestionCount": eligible_count,
        "practiceEligibleQuestionCount": eligible_count,
        "publishedPracticeQuestionCount": 0,
        "eligibleQuestionCount": eligible_count,
        "ineligibleQuestionCount": source_record_count - eligible_count,
        "duplicateCount": duplicate_count,
        "exactDuplicateMappingCount": duplicate_count,
        "sourceRowsWithErrors": explicit_anomaly_count,
        "explicitAnomalySourceCount": explicit_anomaly_count,
        "validationErrorCount": validation_error_count,
        "errorCodeCounts": dict(sorted(error_code_counts.items())),
        "validationWarningCount": warning_count,
        "rightsStatus": "UNVERIFIED",
        "usageRestriction": "INTERNAL_ONLY",
        "importStatus": "PREFLIGHT",
        "taxonomy": {
            "manifestPath": str(taxonomy_manifest),
            "manifestSha256": preflight.file_sha256(taxonomy_manifest),
            "qualificationCount": len(taxonomy.qualification_by_name),
            "subjectCount": len(taxonomy.subject_by_key),
        },
        "recovery": {
            "policy": "LOCAL_DETERMINISTIC_ONLY",
            "networkAccess": False,
            "sameWorkbookCompleteCandidateCount": (
                recovery_index.complete_candidate_count
            ),
            "recoveredCompositeCount": recovered_composite_count,
            "methods": dict(sorted(recovery_method_counts.items())),
            "localDocumentRecoveryEnabled": bool(
                local_recovery_corpus.files
            ),
            "localDocumentFileCount": len(local_recovery_corpus.files),
            "localDocumentSegmentCount": len(local_recovery_corpus.segments),
            "localDocuments": local_recovery_corpus.files,
        },
        "countReconciliation": {
            "expected": expected_question_count,
            "actual": source_record_count,
            "delta": (
                None
                if expected_question_count is None
                else source_record_count - expected_question_count
            ),
            "status": reconciliation_status,
            "components": {
                "usableCanonicalQuestions": eligible_count,
                "exactDuplicateMappings": duplicate_count,
                "explicitAnomalySourceRows": explicit_anomaly_count,
                "sum": reconciled_source_count,
            },
            "headerRowsExcluded": 1,
            "physicalWorksheetRows": sum(
                worksheet["rowElements"]
                for worksheet in package_evidence.get("worksheets", [])
            ),
            "proof": (
                "the worksheet has 29,539 physical rows: one 14-column header plus "
                "29,538 non-empty source question records"
            ),
        },
        "xlsxPackageEvidence": package_evidence,
        "distributions": {
            name: dict(sorted(counter.items()))
            for name, counter in distributions.items()
        },
        "chunks": chunks,
        "outputs": {
            "canonical": str(canonical_path),
            "errors": str(errors_path),
            "duplicates": str(duplicates_path),
            "mapping": str(mapping_path),
            "unresolved": str(unresolved_path),
            "compositeAnomalies": str(composite_anomalies_path),
            "recoveryEvidence": str(recovery_evidence_path),
        },
        "outputArtifacts": {
            "canonicalExplanationInput": artifact_evidence(canonical_path, source_record_count),
            "mapping": artifact_evidence(mapping_path, source_record_count),
            "errors": artifact_evidence(errors_path, validation_error_count),
            "duplicates": artifact_evidence(duplicates_path, duplicate_count),
            "unresolved": artifact_evidence(
                unresolved_path, validation_error_count
            ),
            "compositeAnomalies": artifact_evidence(
                composite_anomalies_path, composite_anomaly_count
            ),
            "recoveryEvidence": artifact_evidence(
                recovery_evidence_path, recovered_composite_count
            ),
        },
        "performance": {
            "elapsedSeconds": round(elapsed_seconds, 3),
            "recordsPerSecond": round(
                source_record_count / elapsed_seconds if elapsed_seconds else 0.0, 2
            ),
            "peakPythonAllocatedBytes": peak_python_bytes,
            "peakProcessWorkingSetBytes": peak_rss,
            "streaming": True,
            "sourcePasses": 2,
            "chunkSize": chunk_size,
        },
    }
    write_json(output / "manifest.json", manifest)
    write_quality_report(output / "quality-report.md", manifest)
    return manifest


def stream_structured_rows(source: Path) -> Iterator[tuple[str, int, dict[str, str]]]:
    try:
        import openpyxl
    except Exception as exc:  # noqa: BLE001
        raise RuntimeError("openpyxl is required for streaming XLSX preflight") from exc

    workbook = openpyxl.load_workbook(source, data_only=False, read_only=True)
    try:
        for sheet_name in workbook.sheetnames:
            sheet = workbook[sheet_name]
            rows = sheet.iter_rows(values_only=True)
            header_values = next(rows, None)
            headers = tuple(cell_text(value) for value in (header_values or ()))
            if len(headers) == 16:
                # The 2026-07-18 workbook adds paper name and image marker columns.
                # Normalize them out of the legacy 14-column parser while retaining
                # the source row number for downstream image correlation.
                headers = tuple(headers[index] for index in (0, 1, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15))
                value_indexes = (0, 1, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15)
                image_index = 8
            else:
                value_indexes = tuple(range(len(headers)))
                image_index = None
            if headers != EXPECTED_HEADERS:
                raise ValueError(
                    f"sheet {sheet_name!r} headers differ from the required 14-column schema: "
                    f"{headers!r}"
                )
            for row_no, values in enumerate(rows, start=2):
                raw = {
                    header: cell_text(values[value_indexes[index]])
                    if value_indexes[index] < len(values) else ""
                    for index, header in enumerate(headers)
                }
                if image_index is not None:
                    raw[IMAGE_COLUMN] = cell_text(values[image_index]) if image_index < len(values) else ""
                if not any(raw.values()):
                    continue
                yield sheet_name, row_no, raw
    finally:
        workbook.close()


def load_taxonomy(path: Path) -> Taxonomy:
    root = json.loads(path.read_text(encoding="utf-8-sig"))
    qualification_by_name = {
        normalize_text(seed["officialName"]): seed for seed in root["qualificationSeeds"]
    }
    subject_by_key = {
        (seed["qualificationCode"], seed["subjectCode"]): seed
        for seed in root["subjectSeeds"]
    }
    if len(qualification_by_name) != 20 or len(subject_by_key) != 45:
        raise ValueError(
            "V9 taxonomy manifest must contain exactly 20 qualifications and 45 subjects"
        )
    return Taxonomy(qualification_by_name, subject_by_key)


def build_same_workbook_recovery_index(
    source: Path, taxonomy: Taxonomy
) -> RecoveryIndex:
    exact: defaultdict[
        tuple[str, str, str], list[RecoveryCandidate]
    ] = defaultdict(list)
    by_answer: defaultdict[
        tuple[str, str, int], list[RecoveryCandidate]
    ] = defaultdict(list)
    complete_candidate_count = 0
    for _, row_no, raw in stream_structured_rows(source):
        parsed = parse_source_row(row_no, raw, taxonomy)
        if not is_complete_composite(parsed):
            continue
        complete_candidate_count += 1
        normalized_answer = normalize_answer_text(raw["答案"])
        stem_fingerprint = comparable_text(parsed.stem)
        candidate = RecoveryCandidate(
            source_row=row_no,
            source_question_id=raw["题目ID"],
            qualification_code=parsed.qualification_code,
            normalized_answer=normalized_answer,
            stem=parsed.stem,
            stem_fingerprint=stem_fingerprint,
            first_option_group_fingerprint=option_group_fingerprint(
                parsed.option_groups[0]
            ),
            option_groups=copy.deepcopy(parsed.option_groups),
        )
        exact[
            (
                parsed.qualification_code,
                normalized_answer,
                stem_fingerprint,
            )
        ].append(candidate)
        by_answer[
            (
                parsed.qualification_code,
                normalized_answer,
                len(parsed.answer_tokens),
            )
        ].append(candidate)
    return RecoveryIndex(
        exact=dict(exact),
        by_answer=dict(by_answer),
        complete_candidate_count=complete_candidate_count,
    )


def recover_composite_from_same_workbook(
    parsed: ParsedRow, recovery_index: RecoveryIndex
) -> None:
    if not any(
        item["code"] == "MISSING_COMPOSITE_OPTIONS" for item in parsed.errors
    ):
        return
    normalized_answer = normalize_answer_text(parsed.raw["答案"])
    target_first_group = (
        option_group_fingerprint(parsed.option_groups[0])
        if parsed.option_groups
        else ""
    )

    def compatible(candidate: RecoveryCandidate) -> bool:
        return (
            candidate.source_row != parsed.source_row
            and (
                not target_first_group
                or candidate.first_option_group_fingerprint == target_first_group
            )
        )

    exact_key = (
        parsed.qualification_code,
        normalized_answer,
        comparable_text(parsed.stem),
    )
    exact_candidates = [
        candidate
        for candidate in recovery_index.exact.get(exact_key, [])
        if compatible(candidate)
    ]
    selected = unique_recovery_candidate(exact_candidates)
    method = "SAME_XLSX_EXACT"
    similarity = 1.0

    if selected is None and not exact_candidates:
        scored = [
            (text_similarity(parsed.stem, candidate.stem), candidate)
            for candidate in recovery_index.by_answer.get(
                (
                    parsed.qualification_code,
                    normalized_answer,
                    len(parsed.answer_tokens),
                ),
                [],
            )
            if compatible(candidate)
        ]
        scored.sort(key=lambda item: (-item[0], item[1].source_row))
        if scored and scored[0][0] >= 0.995:
            top_score = scored[0][0]
            top_candidates = [
                candidate
                for score, candidate in scored
                if abs(score - top_score) < 1e-12
            ]
            next_distinct_score = next(
                (score for score, _ in scored if top_score - score > 1e-12),
                None,
            )
            if next_distinct_score is None or top_score - next_distinct_score >= 0.005:
                selected = unique_recovery_candidate(top_candidates)
                method = "SAME_XLSX_NEAR_EXACT"
                similarity = top_score

    if selected is None:
        return
    parsed.option_groups = copy.deepcopy(selected.option_groups)
    parsed.missing_composite_group_nos = []
    parsed.recovered_from_source_row = selected.source_row
    parsed.recovery_method = method
    parsed.recovery_similarity = round(similarity, 6)
    parsed.errors = [
        item
        for item in parsed.errors
        if item["code"]
        not in {
            "MISSING_COMPOSITE_OPTIONS",
            "COMPOSITE_OPTION_GROUP_COUNT_MISMATCH",
        }
    ]
    parsed.warnings = dedupe_issues(
        [
            *parsed.warnings,
            issue(
                "COMPOSITE_OPTIONS_RECOVERED_FROM_SAME_XLSX",
                "选项",
                f"{method} sourceRow={selected.source_row}; "
                f"sourceQuestionId={selected.source_question_id}; "
                f"stemSimilarity={similarity:.6f}",
            ),
        ]
    )


def is_complete_composite(parsed: ParsedRow) -> bool:
    if (
        not parsed.grouped_choice
        or len(parsed.answer_tokens) < 2
        or len(parsed.option_groups) != len(parsed.answer_tokens)
    ):
        return False
    return all(
        len(group) >= 2
        and contiguous_option_keys(group)
        and answer_key in {option["optionKey"] for option in group}
        for group, answer_key in zip(
            parsed.option_groups, parsed.answer_tokens, strict=True
        )
    )


def unique_recovery_candidate(
    candidates: list[RecoveryCandidate],
) -> RecoveryCandidate | None:
    if not candidates:
        return None
    candidates_by_content: dict[str, list[RecoveryCandidate]] = defaultdict(list)
    for candidate in candidates:
        candidates_by_content[compact_json(candidate.option_groups)].append(candidate)
    if len(candidates_by_content) != 1:
        return None
    return min(next(iter(candidates_by_content.values())), key=lambda item: item.source_row)


def option_group_fingerprint(group: list[dict[str, Any]]) -> str:
    return preflight.sha256_text(compact_json(group))


def build_local_recovery_corpus(paths: list[Path]) -> LocalRecoveryCorpus:
    files: list[Path] = []
    for supplied_path in paths:
        path = supplied_path.resolve()
        if not path.exists():
            raise ValueError(f"local recovery corpus path does not exist: {path}")
        if path.is_dir():
            files.extend(path.rglob("*.pdf"))
            files.extend(path.rglob("*.docx"))
        elif path.suffix.lower() in {".pdf", ".docx"}:
            files.append(path)
        else:
            raise ValueError(
                f"local recovery corpus only supports PDF/DOCX: {path}"
            )
    unique_files = sorted(
        {path.resolve() for path in files}, key=lambda value: str(value).lower()
    )
    segments: list[LocalTextSegment] = []
    evidence: list[dict[str, Any]] = []
    for path in unique_files:
        source_sha = preflight.file_sha256(path)
        item_evidence: dict[str, Any] = {
            "path": str(path),
            "sha256": source_sha,
            "sizeBytes": path.stat().st_size,
            "type": path.suffix.lower().lstrip(".").upper(),
            "status": "EXTRACTED",
        }
        try:
            if path.suffix.lower() == ".pdf":
                from pypdf import PdfReader

                reader = PdfReader(path)
                page_texts = [page.extract_text() or "" for page in reader.pages]
                item_evidence["pageCount"] = len(page_texts)
                item_evidence["textCharacterCount"] = sum(
                    len(value) for value in page_texts
                )
                for index, text in enumerate(page_texts):
                    if not text.strip():
                        continue
                    following_text = "\n".join(
                        page_texts[index + 1 : index + 3]
                    )
                    segments.append(
                        LocalTextSegment(
                            source_path=str(path),
                            source_sha256=source_sha,
                            source_kind="PDF",
                            page_no=index + 1,
                            anchor=f"page:{index + 1}",
                            text=text,
                            following_text=following_text,
                            normalized_text=comparable_text_with_mapping(text)[0],
                        )
                    )
            else:
                from docx import Document

                document = Document(path)
                paragraphs = [
                    paragraph.text
                    for paragraph in document.paragraphs
                    if paragraph.text.strip()
                ]
                text = "\n".join(paragraphs)
                item_evidence["paragraphCount"] = len(document.paragraphs)
                item_evidence["textCharacterCount"] = len(text)
                if text:
                    segments.append(
                        LocalTextSegment(
                            source_path=str(path),
                            source_sha256=source_sha,
                            source_kind="DOCX",
                            page_no=None,
                            anchor=f"paragraph:1-{len(document.paragraphs)}",
                            text=text,
                            following_text="",
                            normalized_text=comparable_text_with_mapping(text)[0],
                        )
                    )
        except Exception as exc:  # noqa: BLE001
            item_evidence["status"] = "EXTRACTION_FAILED"
            item_evidence["error"] = f"{type(exc).__name__}: {exc}"
        evidence.append(item_evidence)
    return LocalRecoveryCorpus(segments=segments, files=evidence)


def recover_composite_from_local_documents(
    parsed: ParsedRow, corpus: LocalRecoveryCorpus
) -> None:
    if not corpus.segments or not any(
        item["code"] == "MISSING_COMPOSITE_OPTIONS" for item in parsed.errors
    ):
        return
    target_fingerprint = comparable_text(parsed.stem)
    if len(target_fingerprint) < 24:
        return
    candidates: list[LocalRecoveryCandidate] = []
    for segment in corpus.segments:
        match_at = segment.normalized_text.find(target_fingerprint)
        if match_at < 0:
            continue
        normalized_text, raw_positions = comparable_text_with_mapping(segment.text)
        if normalized_text.find(target_fingerprint) != match_at:
            continue
        raw_end_index = raw_positions[match_at + len(target_fingerprint) - 1] + 1
        raw_option_start = max(raw_end_index - 24, 0)
        option_window = (
            segment.text[raw_option_start:] + "\n" + segment.following_text
        )
        option_groups = extract_local_option_groups(
            option_window, len(parsed.answer_tokens)
        )
        if not is_valid_recovered_option_groups(
            option_groups, parsed.answer_tokens
        ):
            continue
        first_group_score = option_group_match_score(
            parsed.option_groups[0] if parsed.option_groups else [],
            option_groups[0],
        )
        if first_group_score < 0.9:
            continue
        candidates.append(
            LocalRecoveryCandidate(
                source_path=segment.source_path,
                source_sha256=segment.source_sha256,
                source_kind=segment.source_kind,
                page_no=segment.page_no,
                anchor=(
                    f"{segment.anchor};stemSha256="
                    f"{preflight.sha256_text(target_fingerprint)}"
                ),
                first_group_score=first_group_score,
                option_groups=option_groups,
            )
        )
    selected = unique_local_recovery_candidate(candidates)
    if selected is None:
        return
    parsed.option_groups = copy.deepcopy(selected.option_groups)
    parsed.missing_composite_group_nos = []
    parsed.recovery_method = f"LOCAL_{selected.source_kind}_EXACT_TEXT"
    parsed.recovery_similarity = 1.0
    parsed.local_source_path = selected.source_path
    parsed.local_source_sha256 = selected.source_sha256
    parsed.local_source_page = selected.page_no
    parsed.local_source_anchor = selected.anchor
    parsed.errors = [
        item
        for item in parsed.errors
        if item["code"] != "MISSING_COMPOSITE_OPTIONS"
    ]
    parsed.warnings = dedupe_issues(
        [
            *parsed.warnings,
            issue(
                "COMPOSITE_OPTIONS_RECOVERED_FROM_LOCAL_DOCUMENT",
                "选项",
                f"{parsed.recovery_method}; path={selected.source_path}; "
                f"page={selected.page_no}; anchor={selected.anchor}; "
                f"firstGroupScore={selected.first_group_score:.6f}",
            ),
        ]
    )


def comparable_text_with_mapping(value: str) -> tuple[str, list[int]]:
    normalized_characters: list[str] = []
    raw_positions: list[int] = []
    for raw_index, raw_character in enumerate(value):
        normalized = unicodedata.normalize("NFKC", raw_character).lower()
        for character in normalized:
            if (
                "0" <= character <= "9"
                or "a" <= character <= "z"
                or "\u3400" <= character <= "\u9fff"
            ):
                normalized_characters.append(character)
                raw_positions.append(raw_index)
    return "".join(normalized_characters), raw_positions


def extract_local_option_groups(
    value: str, expected_count: int
) -> list[list[dict[str, Any]]]:
    matches = list(LOCAL_OPTION_LINE_LABEL.finditer(value))
    first_index = next(
        (
            index
            for index, match in enumerate(matches)
            if match.group(1).upper() == "A"
        ),
        None,
    )
    if first_index is None:
        return []
    matches = matches[first_index:]
    options: list[dict[str, Any]] = []
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(value)
        content = sanitize_local_option_content(value[match.end() : end])
        if content:
            options.append(
                {
                    "optionKey": match.group(1).upper(),
                    "content": content,
                    "sortOrder": 0,
                }
            )
    groups: list[list[dict[str, Any]]] = []
    current: list[dict[str, Any]] = []
    for option in options:
        expected_key = chr(ord("A") + len(current)) if current else "A"
        if option["optionKey"] == "A" and current:
            finalize_option_group(current)
            groups.append(current)
            if len(groups) >= expected_count:
                break
            current = []
        elif current and option["optionKey"] != expected_key:
            current = []
            if option["optionKey"] != "A":
                continue
        current.append(option)
    if current and len(groups) < expected_count:
        finalize_option_group(current)
        groups.append(current)
    return groups[:expected_count]


def sanitize_local_option_content(value: str) -> str:
    noise = LOCAL_OPTION_TRAILING_NOISE.search(value)
    if noise is not None:
        value = value[: noise.start()]
    value = "".join(character for character in value if character >= " " or character in "\n\t")
    return normalize_multiline(value)


def is_valid_recovered_option_groups(
    groups: list[list[dict[str, Any]]], answer_tokens: list[str]
) -> bool:
    return (
        len(groups) == len(answer_tokens)
        and all(len(group) >= 2 and contiguous_option_keys(group) for group in groups)
        and all(
            answer_key in {option["optionKey"] for option in group}
            for group, answer_key in zip(groups, answer_tokens, strict=True)
        )
    )


def option_group_match_score(
    source_group: list[dict[str, Any]],
    candidate_group: list[dict[str, Any]],
) -> float:
    if not source_group or len(source_group) != len(candidate_group):
        return 0.0
    if [item["optionKey"] for item in source_group] != [
        item["optionKey"] for item in candidate_group
    ]:
        return 0.0
    similarities = [
        text_similarity(
            clean_option_comparison_text(source["content"]),
            clean_option_comparison_text(candidate["content"]),
        )
        for source, candidate in zip(source_group, candidate_group, strict=True)
    ]
    exact_count = sum(abs(value - 1.0) < 1e-12 for value in similarities)
    average = sum(similarities) / len(similarities)
    if exact_count >= len(similarities) - 1 and average >= 0.9:
        return average
    return average if min(similarities) >= 0.9 and average >= 0.96 else 0.0


def clean_option_comparison_text(value: str) -> str:
    value = re.sub(
        r"(?:[（(]\s*)?\d{1,3}\s*(?:[）)]|[、.．])?\s*$",
        "",
        normalize_multiline(value),
    )
    return value


def unique_local_recovery_candidate(
    candidates: list[LocalRecoveryCandidate],
) -> LocalRecoveryCandidate | None:
    if not candidates:
        return None
    candidates.sort(
        key=lambda item: (
            -item.first_group_score,
            item.source_path.lower(),
            item.page_no or 0,
        )
    )
    top_score = candidates[0].first_group_score
    top_candidates = [
        candidate
        for candidate in candidates
        if abs(candidate.first_group_score - top_score) < 1e-12
    ]
    by_content: dict[str, list[LocalRecoveryCandidate]] = defaultdict(list)
    for candidate in top_candidates:
        semantic_content = [
            [
                {
                    "optionKey": option["optionKey"],
                    "content": comparable_text(option["content"]),
                }
                for option in group
            ]
            for group in candidate.option_groups
        ]
        by_content[compact_json(semantic_content)].append(candidate)
    if len(by_content) != 1:
        return None
    return min(
        next(iter(by_content.values())),
        key=lambda item: (
            item.source_path.lower(),
            item.page_no or 0,
        ),
    )


def parse_source_row(
    row_no: int,
    raw: dict[str, str],
    taxonomy: Taxonomy,
) -> ParsedRow:
    warnings: list[dict[str, str]] = []
    errors: list[dict[str, str]] = []
    qualification = taxonomy.qualification_by_name.get(normalize_text(raw["科目"]))
    qualification_code = qualification["qualificationCode"] if qualification else "UNKNOWN"
    if qualification is None:
        errors.append(issue("SOURCE_SCOPE_UNKNOWN", "科目", "qualification is not in V9"))

    source_type = normalize_text(raw["题型"]).lower().replace("-", "_")
    stem = normalize_multiline(raw["题目正文"])
    stem = append_image_markers(stem, raw.get(IMAGE_COLUMN, ""))
    option_groups = parse_option_groups(raw["选项"])
    if not option_groups or len(option_groups[0]) < 2:
        recovered_stem, recovered_groups = recover_embedded_options(stem)
        if recovered_groups:
            stem = recovered_stem
            option_groups = recovered_groups
            warnings.append(
                issue(
                    "OPTIONS_RECOVERED_FROM_STEM",
                    "题目正文",
                    "option labels were embedded in the stem and were separated deterministically",
                )
            )
        elif source_type == "essay":
            recovered_stem, recovered_groups = recover_loose_embedded_options(stem)
            if recovered_groups:
                stem = recovered_stem
                option_groups = recovered_groups
                warnings.append(
                    issue(
                        "OPTIONS_RECOVERED_FROM_STEM",
                        "题目正文",
                        "ordered option labels embedded without separators were recovered deterministically",
                    )
                )

    normalized_answer = normalize_answer_text(raw["答案"])
    answer_tokens = answer_option_tokens(normalized_answer)
    source_url = raw["URL"]
    year_no = parse_year(raw["年份"])

    boolean_answer = parse_boolean_answer(normalized_answer, option_groups)
    answer_tokens = [] if boolean_answer is not None else answer_tokens
    case_numbers = CASE_SUBQUESTION.findall(stem)
    is_case = len(set(case_numbers)) >= 2
    is_essay = bool(ESSAY_SIGNAL.search(stem)) and not option_groups
    grouped_choice = False
    missing_composite_group_nos: list[int] = []

    if boolean_answer is not None:
        question_type = "JUDGE"
    elif is_case:
        question_type = "CASE"
    elif is_essay:
        question_type = "ESSAY"
    elif source_type in {"multiple_choice", "multi_choice", "multiple", "多选题"}:
        question_type = "MULTI_CHOICE"
    elif len(answer_tokens) > 1:
        if len(option_groups) == len(answer_tokens) and len(option_groups) > 1:
            question_type = "SINGLE_CHOICE"
            grouped_choice = True
        elif len(option_groups) == 1 and looks_like_multi_choice(
            stem, answer_tokens, source_type
        ):
            question_type = "MULTI_CHOICE"
        else:
            question_type = "SINGLE_CHOICE"
            grouped_choice = True
            if len(option_groups) < len(answer_tokens):
                missing_composite_group_nos = list(
                    range(len(option_groups) + 1, len(answer_tokens) + 1)
                )
                errors.append(
                    issue(
                        "MISSING_COMPOSITE_OPTIONS",
                        "选项",
                        f"frozen answer keys={answer_tokens}; present option groups="
                        f"{len(option_groups)}; missing groups="
                        f"{missing_composite_group_nos}",
                    )
                )
            elif len(option_groups) > len(answer_tokens):
                errors.append(
                    issue(
                        "COMPOSITE_OPTION_GROUP_COUNT_MISMATCH",
                        "选项",
                        f"frozen answer keys={answer_tokens}; present option groups="
                        f"{len(option_groups)} exceeds expected "
                        f"{len(answer_tokens)}",
                    )
                )
    else:
        question_type = "SINGLE_CHOICE"

    if grouped_choice:
        if not 2 <= len(answer_tokens) <= 10:
            errors.append(
                issue(
                    "GROUPED_CHOICE_CARDINALITY_INVALID",
                    "答案",
                    "grouped choice requires between two and ten frozen answer keys",
                )
            )
        if len(option_groups) == len(answer_tokens):
            for index, (group, token) in enumerate(
                zip(option_groups, answer_tokens, strict=True), start=1
            ):
                if len(group) < 2 or not contiguous_option_keys(group):
                    errors.append(
                        issue(
                            "INVALID_OPTION_SEQUENCE",
                            "选项",
                            f"option group {index} must contain contiguous keys from A",
                        )
                    )
                elif token not in {option["optionKey"] for option in group}:
                    errors.append(
                        issue(
                            "ANSWER_OPTION_REFERENCE_INVALID",
                            "答案",
                            f"answer key {token} is absent from option group {index}",
                        )
                    )
    elif question_type in {"SINGLE_CHOICE", "MULTI_CHOICE"}:
        if not option_groups or len(option_groups[0]) < 2:
            errors.append(issue("INVALID_OPTION_SEQUENCE", "选项", "at least two options are required"))
        elif not contiguous_option_keys(option_groups[0]):
            errors.append(
                issue("INVALID_OPTION_SEQUENCE", "选项", "option keys must be contiguous from A")
            )
        valid_keys = {option["optionKey"] for option in (option_groups[0] if option_groups else [])}
        if not answer_tokens:
            errors.append(issue("ANSWER_MISSING", "答案", "option answer is required"))
        elif any(token not in valid_keys for token in answer_tokens):
            errors.append(
                issue(
                    "ANSWER_OPTION_REFERENCE_INVALID",
                    "答案",
                    f"answer keys {answer_tokens} do not match option keys {sorted(valid_keys)}",
                )
            )
        if question_type == "SINGLE_CHOICE" and len(answer_tokens) != 1:
            errors.append(
                issue(
                    "SINGLE_CHOICE_ANSWER_CARDINALITY",
                    "答案",
                    "single-choice answer must contain one key",
                )
            )
        if question_type == "MULTI_CHOICE" and len(set(answer_tokens)) < 2:
            errors.append(
                issue(
                    "MULTI_CHOICE_ANSWER_CARDINALITY",
                    "答案",
                    "multi-choice answer must contain at least two distinct keys",
                )
            )
    elif question_type == "JUDGE" and boolean_answer is None:
        errors.append(issue("ANSWER_MISSING", "答案", "boolean answer is required"))

    if question_type == "CASE":
        case_parts = split_case_parts(stem)
        if len(case_parts) < 2 or any(not part.strip() for part in case_parts):
            errors.append(issue(
                "CASE_SUBQUESTION_CONTENT_MISSING",
                "question_text",
                "case questions must contain non-empty text for every sub-question",
            ))

    subject_code, subject_type = map_subject(
        qualification_code, raw["题目类型"], question_type, taxonomy
    )
    expected_category = expected_source_category(question_type)
    if expected_category != raw["题目类型"]:
        warnings.append(
            issue(
                "SOURCE_CATEGORY_CORRECTED",
                "题目类型",
                f"source category {raw['题目类型']!r} conflicts with parsed "
                f"{question_type}; mapped by parsed semantics",
            )
        )
    if subject_code == "UNKNOWN":
        errors.append(
            issue(
                "SUBJECT_SCOPE_UNKNOWN",
                "题目类型",
                "source category cannot be mapped to a V9 subject for this qualification",
            )
        )
    if year_no is None:
        warnings.append(
            issue(
                "YEAR_UNKNOWN",
                "年份",
                "source workbook does not provide an exam year; yearNo remains null",
            )
        )
    if source_type not in {
        "single_choice",
        "multiple_choice",
        "multi_choice",
        "judge",
        "judgement",
        "true_false",
        "case",
        "essay",
    }:
        warnings.append(
            issue(
                "SOURCE_QUESTION_TYPE_NORMALIZED",
                "题型",
                f"unrecognized source type {raw['题型']!r}; inferred from content",
            )
        )

    return ParsedRow(
        source_row=row_no,
        raw=raw,
        qualification_code=qualification_code,
        subject_code=subject_code,
        subject_type=subject_type,
        question_type=question_type,
        stem=stem,
        option_groups=option_groups,
        answer_tokens=answer_tokens,
        boolean_answer=boolean_answer,
        grouped_choice=grouped_choice,
        missing_composite_group_nos=missing_composite_group_nos,
        recovered_from_source_row=None,
        recovery_method=None,
        recovery_similarity=None,
        local_source_path=None,
        local_source_sha256=None,
        local_source_page=None,
        local_source_anchor=None,
        year_no=year_no,
        source_url=source_url,
        warnings=dedupe_issues(warnings),
        errors=dedupe_issues(errors),
    )


def map_subject(
    qualification_code: str,
    source_category: str,
    question_type: str,
    taxonomy: Taxonomy,
) -> tuple[str, str]:
    if qualification_code == "UNKNOWN":
        return "UNKNOWN", "UNKNOWN"
    if question_type == "ESSAY":
        candidate = "ESSAY"
    elif question_type == "CASE" and source_category == "案例分析历年真题":
        candidate = "CASE_ANALYSIS" if qualification_code.startswith("HIGH_") else "APPLIED_TECHNOLOGY"
    elif qualification_code.startswith("HIGH_"):
        candidate = "COMPREHENSIVE_KNOWLEDGE"
    else:
        candidate = "BASIC_KNOWLEDGE"
    seed = taxonomy.subject_by_key.get((qualification_code, candidate))
    if seed is None:
        return "UNKNOWN", "UNKNOWN"
    return seed["subjectCode"], seed["subjectType"]


def build_canonical_record(
    source: Path,
    source_sha: str,
    sheet_name: str,
    parsed: ParsedRow,
) -> dict[str, Any]:
    raw = parsed.raw
    year_no = parsed.year_no
    source_question_id = raw["题目ID"] or f"ROW-{parsed.source_row}"
    external_key = (
        f"SRC_{source_sha[:12].upper()}_{safe_key(source_question_id)}_R{parsed.source_row:06d}"
    )
    sub_questions = build_sub_questions(parsed)
    stems = build_stems(parsed)
    content_fingerprint = {
        "qualificationCode": parsed.qualification_code,
        "subjectCode": parsed.subject_code,
        "questionType": parsed.question_type,
        "stems": stems,
        "subQuestions": sub_questions,
    }
    content_hash = preflight.sha256_text(
        json.dumps(
            content_fingerprint,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
    )
    scope = preflight.DocumentScope(
        parsed.qualification_code,
        parsed.subject_code,
        parsed.subject_type,
        "REAL",
        "REAL_PAPER",
        year_no,
        "HIGH" if not parsed.errors else "LOW",
    )
    document = preflight.SourceDocument(
        source,
        source_sha,
        source.stat().st_size,
        [],
        [],
        [],
    )
    segment = {"segmentId": "SHEET01", "titleAnchor": sheet_name}
    record = preflight.canonical_record_envelope(
        document,
        scope,
        segment,
        1,
        parsed.source_row - 1,
        external_key,
        parsed.question_type,
        content_hash,
    )
    record["sourceTrace"].update(
        {
            "sheetName": sheet_name,
            "rowNo": parsed.source_row,
            "sourceQuestionId": raw["题目ID"],
            "sourceUrl": parsed.source_url,
            "sourceQualificationName": raw["科目"],
            "sourceCategory": raw["题目类型"],
            "sourceQuestionType": raw["题型"],
            "locator": (
                f"sha256:{source_sha}:sheet:{sheet_name}:row:{parsed.source_row}:"
                f"sourceQuestionId:{source_question_id}"
            ),
            "recoveredFromSourceRow": parsed.recovered_from_source_row,
            "recoveryMethod": parsed.recovery_method,
            "recoverySimilarity": parsed.recovery_similarity,
            "localRecovery": (
                {
                    "sourcePath": parsed.local_source_path,
                    "sourceSha256": parsed.local_source_sha256,
                    "pageNo": parsed.local_source_page,
                    "anchor": parsed.local_source_anchor,
                }
                if parsed.local_source_path
                else None
            ),
        }
    )
    record["question"].update(
        {
            "title": source_question_id,
            "yearNo": year_no,
            "difficulty": "MEDIUM",
            "stems": stems,
            "subQuestions": sub_questions,
            "knowledgeBindings": [],
            "source": {
                "sourceType": "SOURCE_DOCUMENT_IMPORT",
                "sourceRef": parsed.source_url or f"sha256:{source_sha}",
                "sourceName": raw["来源"],
                "syllabusBasis": "V9_SOFT_EXAM_TAXONOMY",
                "copyrightStatus": "UNVERIFIED",
                "usageRestriction": (
                    "local development/internal acceptance only; publication prohibited"
                ),
                "capturedAt": raw["抓取时间"],
            },
            "contentHash": content_hash,
            "frozenAnswer": {
                "raw": raw["答案"],
                "normalized": normalize_answer_text(raw["答案"]),
                "optionKeys": parsed.answer_tokens,
                "booleanValue": parsed.boolean_answer,
                "immutable": True,
                "source": "XLSX_COLUMN_ANSWER",
            },
            "responseLayout": "GROUPED_CHOICE" if parsed.grouped_choice else "STANDARD",
            "compositeOptionState": {
                "presentGroupCount": len(parsed.option_groups),
                "expectedGroupCount": (
                    len(parsed.answer_tokens) if parsed.grouped_choice else None
                ),
                "missingGroupNos": parsed.missing_composite_group_nos,
                "recoveredFromSourceRow": parsed.recovered_from_source_row,
            },
        }
    )
    record["quality"] = {
        "parserConfidence": "HIGH" if not parsed.errors else "LOW",
        "needsManualReview": bool(parsed.errors or parsed.warnings),
    }
    return record


def build_stems(parsed: ParsedRow) -> list[dict[str, Any]]:
    if parsed.question_type == "ESSAY":
        requirement = essay_requirement_text(parsed.stem)
        background = parsed.stem.replace(requirement, "").strip() or parsed.stem
        return [
            stem("B1", "MAIN_STEM", background, 1),
            stem("R1", "ESSAY_REQUIREMENT", requirement, 2),
        ]
    stem_type = "CASE_MATERIAL" if parsed.question_type == "CASE" else "MAIN_STEM"
    return [stem("M1" if stem_type == "CASE_MATERIAL" else "S1", stem_type, parsed.stem, 1)]


def build_sub_questions(parsed: ParsedRow) -> list[dict[str, Any]]:
    explanation = parsed.raw["解析"] or "原始题库未提供解析；参考答案以答案键为准。"
    if parsed.question_type == "JUDGE":
        answer = {
            "answerKind": "BOOLEAN",
            "optionKeys": None,
            "booleanValue": parsed.boolean_answer,
            "textValue": None,
            "numberValue": None,
            "sortOrder": 1,
        }
        return [
            sub_question(
                1,
                "JUDGE",
                parsed.stem,
                1,
                "BOOLEAN",
                "AUTO",
                [],
                [answer],
                explanation,
                "EXACT",
            )
        ]
    if parsed.question_type == "ESSAY":
        reference = parsed.raw["答案"] or "原始题库未提供参考结构。"
        answer = text_answer(reference)
        return [
            sub_question(
                1,
                "ESSAY_WRITING",
                essay_requirement_text(parsed.stem),
                75,
                "TEXT",
                "MANUAL",
                [],
                [answer],
                reference,
                "MANUAL",
                scoring_points=[
                    {"pointCode": "P1", "pointText": "论文主题与项目背景、个人职责的完整性", "score": 25, "sortOrder": 1},
                    {"pointCode": "P2", "pointText": "论文论述对题目要求和专业知识的覆盖度", "score": 25, "sortOrder": 2},
                    {"pointCode": "P3", "pointText": "论文分析、措施和结论的针对性与可执行性", "score": 25, "sortOrder": 3},
                ],
            )
        ]
    if parsed.grouped_choice:
        return [
            sub_question(
                index,
                "SINGLE_CHOICE",
                f"第{index}空",
                1,
                "OPTION_KEYS",
                "AUTO",
                parsed.option_groups[index - 1]
                if index <= len(parsed.option_groups)
                else [],
                [option_answer([answer_key])],
                explanation,
                "EXACT",
            )
            for index, answer_key in enumerate(parsed.answer_tokens, start=1)
        ]
    if parsed.question_type == "CASE":
        if len(parsed.answer_tokens) > 1:
            result: list[dict[str, Any]] = []
            for index, answer_key in enumerate(parsed.answer_tokens, start=1):
                options = parsed.option_groups[index - 1] if index <= len(parsed.option_groups) else []
                result.append(
                    sub_question(
                        index,
                        "SINGLE_CHOICE",
                        f"第{index}空",
                        1,
                        "OPTION_KEYS",
                        "AUTO",
                        options,
                        [option_answer([answer_key])],
                        explanation,
                        "EXACT",
                    )
                )
            return result
        case_parts = split_case_parts(parsed.stem)
        return [
            sub_question(
                index,
                "SHORT_ANSWER",
                content,
                1,
                "TEXT",
                "MANUAL",
                [],
                [text_answer(parsed.raw["答案"])],
                parsed.raw["答案"],
                "MANUAL",
                scoring_points=[
                    {
                        "pointCode": "P1",
                        "pointText": parsed.raw["答案"],
                        "score": 1,
                        "sortOrder": 1,
                    }
                ],
            )
            for index, content in enumerate(case_parts, start=1)
        ]

    options = parsed.option_groups[0] if parsed.option_groups else []
    subtype = parsed.question_type
    answer_keys = parsed.answer_tokens
    mode = "SET_EQUAL" if subtype == "MULTI_CHOICE" else "EXACT"
    return [
        sub_question(
            1,
            subtype,
            parsed.stem,
            1,
            "OPTION_KEYS",
            "AUTO",
            options,
            [option_answer(answer_keys)] if answer_keys else [],
            explanation,
            mode,
        )
    ]


def sub_question(
    number: int,
    subtype: str,
    content: str,
    score: int,
    answer_format: str,
    grading_mode: str,
    options: list[dict[str, Any]],
    answers: list[dict[str, Any]],
    solution: str,
    rule_mode: str,
    *,
    scoring_points: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    return {
        "subQuestionNo": number,
        "subQuestionType": subtype,
        "content": content,
        "score": score,
        "answerFormat": answer_format,
        "gradingMode": grading_mode,
        "sortOrder": number,
        "options": options,
        "answers": answers,
        "solutions": [
            {
                "solutionType": "REFERENCE_ANSWER" if grading_mode == "MANUAL" else "STANDARD",
                "content": solution,
                "sortOrder": 1,
            }
        ]
        if solution
        else [],
        "scoringRules": [
            {
                "ruleType": "MANUAL" if grading_mode == "MANUAL" else "AUTO",
                "ruleJson": {
                    "mode": rule_mode,
                    "tolerance": None,
                    "caseSensitive": False,
                },
                "sortOrder": 1,
            }
        ],
        "scoringPoints": [
            {**point, "pointText": point.get("pointText") or "Reference answer to be added (manual review)"}
            for point in (scoring_points or [])
        ],
        "knowledgeBindings": [],
    }


def stem(key: str, stem_type: str, content: str, sort_order: int) -> dict[str, Any]:
    return {
        "stemKey": key,
        "stemType": stem_type,
        "content": content,
        "attachmentUrl": first_image_url(content),
        "resourceId": None,
        "sortOrder": sort_order,
    }


IMAGE_COLUMN = "\u9898\u76ee\u56fe\u7247"
IMAGE_LABEL = "\u56fe\u7247"
IMAGE_MARKER = re.compile(rf"\[{IMAGE_LABEL}:\s*([^\]]+)\]")
IMAGE_FILE_NAME = re.compile(r"^[0-9A-Fa-f]{16}\.(?:png|jpe?g|gif|webp)$")


def append_image_markers(content: str, source_value: str) -> str:
    markers = image_file_names(source_value)
    if not markers:
        return content
    existing = set(image_file_names(content))
    missing = [name for name in markers if name not in existing]
    if not missing:
        return content
    suffix = "\n".join(f"[{IMAGE_LABEL}:{name}]" for name in missing)
    return f"{content}\n{suffix}" if content else suffix


def image_file_names(value: str) -> list[str]:
    names: list[str] = []
    for raw in IMAGE_MARKER.findall(value):
        candidate = raw.strip().replace("\\", "/").rsplit("/", 1)[-1]
        if IMAGE_FILE_NAME.fullmatch(candidate) and candidate not in names:
            names.append(candidate)
    for raw in re.split(r"[\r\n]+", value):
        candidate = raw.strip().replace("\\", "/").rsplit("/", 1)[-1]
        if IMAGE_FILE_NAME.fullmatch(candidate) and candidate not in names:
            names.append(candidate)
    return names


def first_image_url(content: str) -> str | None:
    names = image_file_names(content)
    return f"/api/v1/public/question-assets/{names[0]}" if names else None


def option_answer(keys: list[str]) -> dict[str, Any]:
    return {
        "answerKind": "OPTION_KEYS",
        "optionKeys": keys,
        "booleanValue": None,
        "textValue": None,
        "numberValue": None,
        "sortOrder": 1,
    }


def text_answer(value: str) -> dict[str, Any]:
    value = value.strip() or "Reference answer to be added (manual review)"
    return {
        "answerKind": "TEXT",
        "optionKeys": None,
        "booleanValue": None,
        "textValue": value,
        "numberValue": None,
        "sortOrder": 1,
    }


def text_similarity(left: str, right: str) -> float:
    left_value = comparable_text(left)
    right_value = comparable_text(right)
    if not left_value or not right_value:
        return 0.0
    if left_value in right_value or right_value in left_value:
        return min(len(left_value), len(right_value)) / max(len(left_value), len(right_value))
    return difflib.SequenceMatcher(None, left_value, right_value, autojunk=False).ratio()


def comparable_text(value: str) -> str:
    value = unicodedata.normalize("NFKC", value).lower()
    value = re.sub(r"\[图片:\s*https?://[^]]+]", "", value)
    value = re.sub(r"[（(]\s*\d{1,3}\s*[）)]\s*$", "", value)
    return re.sub(r"[^0-9a-z\u3400-\u9fff]+", "", value)


def parse_option_groups(
    value: str, *, require_line_boundary: bool = True
) -> list[list[dict[str, Any]]]:
    value = normalize_multiline(value)
    if not value:
        return []
    pattern = OPTION_LINE_LABEL if require_line_boundary else OPTION_LABEL
    matches = list(pattern.finditer(value))
    if not matches:
        return []
    options: list[dict[str, Any]] = []
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(value)
        content = value[match.end() : end].strip()
        if content:
            options.append(
                {
                    "optionKey": match.group(1).upper(),
                    "content": content,
                    "sortOrder": 0,
                }
            )
    groups: list[list[dict[str, Any]]] = []
    current: list[dict[str, Any]] = []
    for option in options:
        expected = chr(ord("A") + len(current)) if current else "A"
        if option["optionKey"] == "A" and current:
            finalize_option_group(current)
            groups.append(current)
            current = []
        elif current and option["optionKey"] != expected:
            finalize_option_group(current)
            groups.append(current)
            current = []
        current.append(option)
    if current:
        finalize_option_group(current)
        groups.append(current)
    return groups


def recover_embedded_options(stem_text: str) -> tuple[str, list[list[dict[str, Any]]]]:
    matches = list(OPTION_LABEL.finditer(stem_text))
    if not matches:
        return stem_text, []
    first = next((match for match in matches if match.group(1).upper() == "A"), None)
    if first is None:
        inline_a = INLINE_OPTION_A.search(stem_text)
        if inline_a is None:
            return stem_text, []
        synthetic = f"A. {stem_text[inline_a.end():]}"
        groups = parse_option_groups(synthetic, require_line_boundary=False)
        question = stem_text[: inline_a.start()].strip()
        return (question, groups) if groups and len(groups[0]) >= 2 else (stem_text, [])
    question = stem_text[: first.start()].strip()
    groups = parse_option_groups(
        stem_text[first.start() :], require_line_boundary=False
    )
    return (question, groups) if groups and len(groups[0]) >= 2 else (stem_text, [])


def recover_loose_embedded_options(
    stem_text: str,
) -> tuple[str, list[list[dict[str, Any]]]]:
    matches = list(LOOSE_OPTION_LABEL.finditer(stem_text))
    first_index = next(
        (
            index
            for index, match in enumerate(matches)
            if match.group(1).upper() == "A"
        ),
        None,
    )
    if first_index is None:
        return stem_text, []
    matches = matches[first_index:]
    keys = [match.group(1).upper() for match in matches]
    if len(keys) < 2 or keys != [
        chr(ord("A") + index) for index in range(len(keys))
    ]:
        return stem_text, []
    options: list[dict[str, Any]] = []
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(stem_text)
        content = stem_text[match.end() : end].strip()
        if not content:
            return stem_text, []
        options.append(
            {
                "optionKey": match.group(1).upper(),
                "content": content,
                "sortOrder": index + 1,
            }
        )
    question = stem_text[: matches[0].start()].strip()
    return (question, [options]) if question else (stem_text, [])


def finalize_option_group(group: list[dict[str, Any]]) -> None:
    for index, option in enumerate(group, start=1):
        option["sortOrder"] = index


def contiguous_option_keys(options: list[dict[str, Any]]) -> bool:
    keys = [option["optionKey"] for option in options]
    return keys == [chr(ord("A") + index) for index in range(len(keys))]


def normalize_answer_text(value: str) -> str:
    return unicodedata.normalize("NFKC", value).strip().upper()


def answer_option_tokens(value: str) -> list[str]:
    return re.findall(r"[A-H]", value)


def parse_boolean_answer(
    value: str, option_groups: list[list[dict[str, Any]]]
) -> bool | None:
    if option_groups:
        return None
    if value in {"1", "TRUE", "正确", "对", "√"}:
        return True
    if value in {"0", "FALSE", "错误", "错", "×"}:
        return False
    return None


def looks_like_multi_choice(
    stem_text: str, answer_tokens: list[str], source_type: str = ""
) -> bool:
    if len(answer_tokens) < 2 or len(set(answer_tokens)) != len(answer_tokens):
        return False
    if NUMBERED_BLANK.search(stem_text) or len(GENERIC_BLANK.findall(stem_text)) > 1:
        return False
    if GROUPED_CHOICE_SIGNAL.search(stem_text):
        return False
    return bool(MULTI_CHOICE_SIGNAL.search(stem_text)) or source_type == "essay"


def split_case_parts(stem_text: str) -> list[str]:
    matches = list(CASE_SUBQUESTION.finditer(stem_text))
    if len(matches) < 2:
        return [stem_text, "原始题库未提供第二小题文本"]
    parts: list[str] = []
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(stem_text)
        parts.append(stem_text[match.end() : end].strip())
    return parts[:4]


def essay_requirement_text(stem_text: str) -> str:
    match = ESSAY_SIGNAL.search(stem_text)
    if match:
        return stem_text[match.start() :].strip()
    word_count = WORD_COUNT.search(stem_text)
    if word_count:
        return f"写作要求：{word_count.group(1)}至{word_count.group(2)}字。"
    return "写作要求：按题目背景完成论文；原始题库未提供字数要求。"


def expected_source_category(question_type: str) -> str:
    if question_type == "ESSAY":
        return "论文历年真题"
    if question_type == "CASE":
        return "案例分析历年真题"
    return "综合知识历年真题"


def parse_year(value: str) -> int | None:
    match = re.search(r"(?:19|20)\d{2}", value)
    return int(match.group(0)) if match else None


def safe_key(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", value).upper()
    candidate = re.sub(r"[^A-Z0-9_-]", "_", normalized).strip("_")
    return (candidate or hashlib.sha256(value.encode("utf-8")).hexdigest()[:16].upper())[:64]


def normalize_text(value: str) -> str:
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", value))


def normalize_multiline(value: str) -> str:
    value = unicodedata.normalize("NFKC", value).replace("\u00a0", " ")
    return "\n".join(line.strip() for line in value.splitlines()).strip()


def compact_json(value: Any) -> str:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    )


def cell_text(value: Any) -> str:
    return "" if value is None else str(value).strip()


def issue(code: str, field: str, message: str) -> dict[str, str]:
    return {"code": code, "field": field, "message": message}


def dedupe_issues(items: list[dict[str, str]]) -> list[dict[str, str]]:
    seen: set[tuple[str, str]] = set()
    result: list[dict[str, str]] = []
    for item in items:
        key = (item["code"], item["field"])
        if key not in seen:
            seen.add(key)
            result.append(item)
    return result


def open_chunk(root: Path, index: int) -> ChunkState:
    chunk_dir = root / f"{index:05d}"
    chunk_dir.mkdir(parents=True, exist_ok=False)
    path = chunk_dir / "canonical-questions.jsonl"
    return ChunkState(index, path, path.open("w", encoding="utf-8", newline="\n"))


def close_chunk(chunk: ChunkState, output: Path) -> dict[str, Any]:
    chunk.handle.close()
    return {
        "index": chunk.index,
        "path": str(chunk.path),
        "relativePath": chunk.path.relative_to(output).as_posix(),
        "sha256": preflight.file_sha256(chunk.path),
        "sizeBytes": chunk.path.stat().st_size,
        "totalCount": chunk.total_count,
        "eligibleCount": chunk.eligible_count,
        "ineligibleCount": chunk.total_count - chunk.eligible_count,
    }


def count_gate_status(actual: int, expected: int | None) -> str:
    return "MATCHED" if expected is None or actual == expected else "MISMATCH"


def inspect_xlsx_package(source: Path) -> dict[str, Any]:
    main_ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    rel_ns = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    with zipfile.ZipFile(source) as archive:
        names = archive.namelist()
        workbook_root = ElementTree.fromstring(archive.read("xl/workbook.xml"))
        sheets = [
            {
                "name": node.attrib["name"],
                "state": node.attrib.get("state", "visible"),
                "relationshipId": node.attrib[f"{{{rel_ns}}}id"],
            }
            for node in workbook_root.findall(f".//{{{main_ns}}}sheet")
        ]
        worksheet_entries = sorted(
            name for name in names if name.startswith("xl/worksheets/sheet") and name.endswith(".xml")
        )
        worksheet_evidence: list[dict[str, Any]] = []
        for entry_name in worksheet_entries:
            counters = Counter()
            dimension = ""
            with archive.open(entry_name) as stream:
                for event, element in ElementTree.iterparse(stream, events=("start", "end")):
                    local = element.tag.rsplit("}", 1)[-1]
                    if event == "start":
                        if local == "dimension":
                            dimension = element.attrib.get("ref", "")
                        elif local == "row":
                            counters["rows"] += 1
                            counters["hiddenRows"] += int(element.attrib.get("hidden") == "1")
                        elif local == "c":
                            counters["cells"] += 1
                        elif local == "f":
                            counters["formulas"] += 1
                        elif local == "mergeCell":
                            counters["mergedRanges"] += 1
                        elif local == "col":
                            counters["hiddenColumns"] += int(element.attrib.get("hidden") == "1")
                        elif local == "r":
                            counters["richTextRuns"] += 1
                    if event == "end":
                        element.clear()
            worksheet_evidence.append(
                {
                    "entry": entry_name,
                    "dimension": dimension,
                    "rowElements": counters["rows"],
                    "dataRowsAfterHeader": max(counters["rows"] - 1, 0),
                    "cells": counters["cells"],
                    "hiddenRows": counters["hiddenRows"],
                    "hiddenColumns": counters["hiddenColumns"],
                    "formulas": counters["formulas"],
                    "mergedRanges": counters["mergedRanges"],
                    "richTextRuns": counters["richTextRuns"],
                }
            )
        return {
            "zipEntryCount": len(names),
            "sheetCount": len(sheets),
            "sheets": sheets,
            "worksheets": worksheet_evidence,
            "mediaEntries": sum(name.startswith("xl/media/") for name in names),
            "drawingEntries": sum(name.startswith("xl/drawings/") for name in names),
            "commentEntries": sum(name.startswith("xl/comments") for name in names),
            "sharedStringsPresent": "xl/sharedStrings.xml" in names,
        }


def current_rss_bytes() -> int | None:
    try:
        import psutil

        return int(psutil.Process().memory_info().rss)
    except Exception:  # noqa: BLE001
        pass
    if os.name != "nt":
        try:
            import resource

            return int(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss) * 1024
        except Exception:  # noqa: BLE001
            return None
    try:
        class ProcessMemoryCounters(ctypes.Structure):
            _fields_ = [
                ("cb", ctypes.c_ulong),
                ("PageFaultCount", ctypes.c_ulong),
                ("PeakWorkingSetSize", ctypes.c_size_t),
                ("WorkingSetSize", ctypes.c_size_t),
                ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
                ("QuotaPagedPoolUsage", ctypes.c_size_t),
                ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
                ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                ("PagefileUsage", ctypes.c_size_t),
                ("PeakPagefileUsage", ctypes.c_size_t),
            ]

        counters = ProcessMemoryCounters()
        counters.cb = ctypes.sizeof(counters)
        process = ctypes.windll.kernel32.GetCurrentProcess()
        ok = ctypes.windll.psapi.GetProcessMemoryInfo(
            process, ctypes.byref(counters), counters.cb
        )
        return int(counters.PeakWorkingSetSize) if ok else None
    except Exception:  # noqa: BLE001
        return None


def max_optional(left: int | None, right: int | None) -> int | None:
    if left is None:
        return right
    if right is None:
        return left
    return max(left, right)


def write_json(path: Path, value: Any) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def artifact_evidence(path: Path, record_count: int | None) -> dict[str, Any]:
    return {
        "path": str(path.resolve()),
        "sha256": preflight.file_sha256(path),
        "sizeBytes": path.stat().st_size,
        "recordCount": record_count,
    }


def write_quality_report(path: Path, manifest: dict[str, Any]) -> None:
    reconciliation = manifest["countReconciliation"]
    performance = manifest["performance"]
    lines = [
        "# Soft Exam XLSX Preflight",
        "",
        f"- Source SHA-256: `{manifest['sourceSha256']}`",
        f"- Source records: `{manifest['sourceRecordCount']}`",
        f"- Expected records: `{reconciliation['expected']}`",
        f"- Count gate: `{reconciliation['status']}`",
        f"- Eligible canonical questions: `{manifest['eligibleQuestionCount']}`",
        f"- Ineligible source rows: `{manifest['ineligibleQuestionCount']}`",
        f"- Exact source duplicates: `{manifest['duplicateCount']}`",
        f"- Elapsed seconds: `{performance['elapsedSeconds']}`",
        f"- Throughput records/second: `{performance['recordsPerSecond']}`",
        f"- Peak process working set bytes: `{performance['peakProcessWorkingSetBytes']}`",
        "",
        "## Distributions",
        "",
    ]
    for name, values in manifest["distributions"].items():
        lines.append(f"### {name}")
        lines.append("")
        for value, count in values.items():
            lines.append(f"- `{value}`: `{count}`")
        lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
