from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import w3_import_package as package  # noqa: E402


def test_streaming_package_merges_accepted_and_tracks_every_source_row(
    tmp_path: Path,
) -> None:
    canonical = tmp_path / "canonical-questions.jsonl"
    records = [
        canonical_record(2, "CANONICAL", True, "A", recovery=True),
        canonical_record(3, "DUPLICATE_SOURCE", False, "B"),
        canonical_record(4, "SOURCE_ERROR", False, "C"),
    ]
    write_jsonl(canonical, records)
    canonical_sha = package.sha256_file(canonical)
    controls = write_controls(tmp_path, canonical, canonical_sha)
    accepted = tmp_path / "final-explanations.jsonl"
    write_jsonl(accepted, [accepted_record(records[0], "A")])
    duplicates = tmp_path / "duplicate-mappings.jsonl"
    write_jsonl(duplicates, [duplicate_record(records[1], records[0])])
    escalation = tmp_path / "escalation-queue.jsonl"
    write_jsonl(escalation, [escalation_record(records[2])])
    production_manifest = write_production_manifest(
        tmp_path, [accepted, duplicates, escalation, controls[1], controls[2]]
    )

    manifest = package.build_import_package(
        canonical,
        controls[0],
        production_manifest,
        accepted,
        duplicates,
        escalation,
        controls[1],
        controls[2],
        tmp_path / "package",
        expected_canonical_sha256=canonical_sha,
        expected=package.ExpectedCounts(3, 1, 1, 1, 1),
        chunk_size=2,
    )

    output = read_jsonl(tmp_path / "package" / "canonical-questions.jsonl")
    assert len(output) == 3
    assert output[0]["question"]["subQuestions"][0]["solutions"] == [
        {
            "solutionType": "ANALYSIS",
            "content": "deterministic generated explanation",
            "sortOrder": 1,
        }
    ]
    assert len(output[0]["explanationTrace"]["answerHash"]) == 64
    assert "explanationTrace" not in output[1]
    assert output[1]["duplicateTrace"]["duplicateOfSourceRow"] == 2
    assert output[2]["governanceTrace"]["practiceEligible"] is False
    assert manifest["countReconciliation"] == {
        "anomalies": 1,
        "duplicates": 1,
        "localRecoveries": 1,
        "sourceTotal": 3,
        "usable": 1,
    }
    assert [chunk["totalCount"] for chunk in manifest["chunks"]] == [2, 1]
    assert sum(chunk["eligibleCount"] for chunk in manifest["chunks"]) == 1
    assert package.sha256_file(tmp_path / "package" / "canonical-questions.jsonl") == (
        manifest["masterArtifact"]["sha256"]
    )


def test_frozen_answer_mismatch_fails_without_leaving_partial_output(
    tmp_path: Path,
) -> None:
    canonical = tmp_path / "canonical-questions.jsonl"
    record = canonical_record(2, "CANONICAL", True, "A")
    write_jsonl(canonical, [record])
    canonical_sha = package.sha256_file(canonical)
    controls = write_controls(
        tmp_path, canonical, canonical_sha, source_total=1, usable=1,
        duplicates=0, anomalies=0,
    )
    accepted = tmp_path / "final-explanations.jsonl"
    write_jsonl(accepted, [accepted_record(record, "B")])
    duplicates = tmp_path / "duplicate-mappings.jsonl"
    duplicates.write_text("", encoding="utf-8")
    escalation = tmp_path / "escalation-queue.jsonl"
    escalation.write_text("", encoding="utf-8")
    production_manifest = write_production_manifest(
        tmp_path, [accepted, duplicates, escalation, controls[1], controls[2]]
    )
    output = tmp_path / "package"

    with pytest.raises(ValueError, match="frozen answer mismatch"):
        package.build_import_package(
            canonical,
            controls[0],
            production_manifest,
            accepted,
            duplicates,
            escalation,
            controls[1],
            controls[2],
            output,
            expected_canonical_sha256=canonical_sha,
            expected=package.ExpectedCounts(1, 1, 0, 0, 0),
            chunk_size=1,
        )

    assert not output.exists()
    assert not list(tmp_path.glob(".package.building-*"))


def test_local_recovery_grouped_answer_keeps_full_canonical_answer(
    tmp_path: Path,
) -> None:
    canonical = tmp_path / "canonical-questions.jsonl"
    record = canonical_record(2, "CANONICAL", True, "A", recovery=True)
    question = record["question"]
    question["frozenAnswer"]["normalized"] = "A,A"
    question["frozenAnswer"]["optionKeys"] = ["A", "A"]
    question["subQuestions"] = [
        {
            "answers": [{"answerKind": "OPTION_KEYS", "optionKeys": [key]}],
            "solutions": [
                {"solutionType": "STANDARD", "content": f"standard-{index}", "sortOrder": 1}
            ],
        }
        for index, key in enumerate(("A", "A"), start=1)
    ]
    write_jsonl(canonical, [record])
    canonical_sha = package.sha256_file(canonical)
    controls = write_controls(
        tmp_path, canonical, canonical_sha, source_total=1, usable=1,
        duplicates=0, anomalies=0,
    )
    accepted = tmp_path / "final-explanations.jsonl"
    write_jsonl(accepted, [accepted_record(record, "A")])
    duplicates = tmp_path / "duplicate-mappings.jsonl"
    duplicates.write_text("", encoding="utf-8")
    escalation = tmp_path / "escalation-queue.jsonl"
    escalation.write_text("", encoding="utf-8")
    production_manifest = write_production_manifest(
        tmp_path, [accepted, duplicates, escalation, controls[1], controls[2]]
    )

    package.build_import_package(
        canonical, controls[0], production_manifest, accepted, duplicates,
        escalation, controls[1], controls[2], tmp_path / "package",
        expected_canonical_sha256=canonical_sha,
        expected=package.ExpectedCounts(1, 1, 0, 0, 1), chunk_size=1,
    )

    output = read_jsonl(tmp_path / "package" / "canonical-questions.jsonl")[0]
    assert output["question"]["frozenAnswer"]["optionKeys"] == ["A", "A"]
    assert output["question"]["subQuestions"][0]["solutions"][0]["solutionType"] == "ANALYSIS"
    assert output["question"]["subQuestions"][1]["solutions"][0]["content"] == "standard-2"
    assert output["explanationTrace"]["explanationScope"] == "FIRST_SUBQUESTION_ONLY"


def canonical_record(
    source_row: int,
    status: str,
    eligible: bool,
    answer: str,
    *,
    recovery: bool = False,
) -> dict[str, object]:
    content_hash = hashlib.sha256(f"content-{source_row}".encode()).hexdigest()
    document_sha = "2" * 64
    external_key = f"EXT-{source_row}"
    locator = f"sha256:{document_sha}:sheet:test:row:{source_row}:sourceQuestionId:{source_row}"
    errors = [] if eligible else [{"code": "TEST_ERROR"}]
    local_recovery = None
    if recovery:
        local_recovery = {
            "anchor": "page:9;stemSha256=" + "3" * 64,
            "pageNo": 9,
            "sourcePath": "C:\\local\\source.pdf",
            "sourceSha256": "4" * 64,
        }
    return {
        "schemaVersion": "question-import/1.0",
        "recordType": "QUESTION_PREFLIGHT",
        "importStatus": "PREFLIGHT",
        "rightsStatus": "UNVERIFIED",
        "qualificationCode": "MIDDLE_DATABASE_SYSTEM_ENGINEER",
        "subjectCode": "BASIC_KNOWLEDGE",
        "paper": {"paperKind": "REAL_PAPER"},
        "quality": {
            "importEligible": eligible,
            "sourceStatus": status,
            "validationErrors": errors,
        },
        "sourceTrace": {
            "documentSha256": document_sha,
            "localRecovery": local_recovery,
            "locator": locator,
            "rowNo": source_row,
            "sourcePath": "C:\\source.xlsx",
            "sourceQuestionId": str(source_row),
        },
        "question": {
            "contentHash": content_hash,
            "externalKey": external_key,
            "frozenAnswer": {
                "booleanValue": None,
                "normalized": answer,
                "optionKeys": [answer],
            },
            "questionType": "SINGLE_CHOICE",
            "source": {
                "copyrightStatus": "UNVERIFIED",
                "usageRestriction": "local development/internal acceptance only; publication prohibited",
            },
            "subQuestions": [
                {
                    "solutions": [
                        {"solutionType": "STANDARD", "content": "placeholder", "sortOrder": 1}
                    ]
                }
            ],
        },
    }


def accepted_record(canonical: dict[str, object], answer: str) -> dict[str, object]:
    trace = canonical["sourceTrace"]
    question = canonical["question"]
    return {
        "schemaVersion": "question-explanation-final/1.0",
        "recordType": "QUESTION_EXPLANATION",
        "decision": "accepted",
        "importEligible": True,
        "sourceStatus": "CANONICAL",
        "sourceRow": trace["rowNo"],
        "sourceQuestionId": trace["sourceQuestionId"],
        "canonicalQuestionId": trace["locator"],
        "externalKey": question["externalKey"],
        "contentHash": question["contentHash"],
        "frozenAnswer": answer,
        "frozenAnswerKeys": [answer],
        "explanation": "deterministic generated explanation",
        "generationMode": "PRODUCTION",
        "model": "test-model",
        "promptVersion": "test-prompt",
        "sourceTrace": {"documentSha256": trace["documentSha256"]},
    }


def duplicate_record(
    canonical: dict[str, object], target: dict[str, object]
) -> dict[str, object]:
    trace = canonical["sourceTrace"]
    question = canonical["question"]
    target_trace = target["sourceTrace"]
    target_question = target["question"]
    return {
        "schemaVersion": "question-explanation-duplicate-mapping/1.0",
        "sourceStatus": "DUPLICATE_SOURCE",
        "sourceRow": trace["rowNo"],
        "sourceQuestionId": trace["sourceQuestionId"],
        "externalKey": question["externalKey"],
        "contentHash": question["contentHash"],
        "sourceTrace": trace,
        "duplicateOfExternalKey": target_question["externalKey"],
        "duplicateOfSourceQuestionId": target_trace["sourceQuestionId"],
        "duplicateOfSourceRow": target_trace["rowNo"],
    }


def escalation_record(canonical: dict[str, object]) -> dict[str, object]:
    trace = canonical["sourceTrace"]
    question = canonical["question"]
    return {
        "schemaVersion": "question-explanation-invalid-source/1.0",
        "sourceStatus": "SOURCE_ERROR",
        "sourceRow": trace["rowNo"],
        "sourceQuestionId": trace["sourceQuestionId"],
        "externalKey": question["externalKey"],
        "contentHash": question["contentHash"],
        "sourceTrace": trace,
        "errorCodes": ["TEST_ERROR"],
    }


def write_controls(
    directory: Path,
    canonical: Path,
    canonical_sha: str,
    *,
    source_total: int = 3,
    usable: int = 1,
    duplicates: int = 1,
    anomalies: int = 1,
) -> tuple[Path, Path, Path]:
    manifest = directory / "manifest.json"
    checkpoint = directory / "production-checkpoint.json"
    reconciliation = directory / "final-reconciliation.json"
    write_json(
        manifest,
        {
            "outputArtifacts": {
                "canonicalExplanationInput": {
                    "path": str(canonical),
                    "sha256": canonical_sha,
                    "recordCount": source_total,
                }
            },
            "countReconciliation": {
                "components": {
                    "usableCanonicalQuestions": usable,
                    "exactDuplicateMappings": duplicates,
                    "explicitAnomalySourceRows": anomalies,
                }
            },
        },
    )
    write_json(
        checkpoint,
        {
            "inputSha256": canonical_sha,
            "sourceTotal": source_total,
            "accepted": usable,
            "duplicateMappings": duplicates,
            "escalation": anomalies,
        },
    )
    write_json(
        reconciliation,
        {
            "inputSha256": canonical_sha,
            "sourceTotal": source_total,
            "accepted": usable,
            "reconciledTotal": source_total,
        },
    )
    return manifest, checkpoint, reconciliation


def write_production_manifest(directory: Path, artifacts: list[Path]) -> Path:
    path = directory / "import-manifest.json"
    write_json(
        path,
        {
            "fileInventory": [
                {
                    "bytes": artifact.stat().st_size,
                    "name": artifact.name,
                    "path": str(artifact),
                    "sha256": package.sha256_file(artifact),
                }
                for artifact in artifacts
            ]
        },
    )
    return path


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")


def write_jsonl(path: Path, values: list[dict[str, object]]) -> None:
    path.write_text(
        "".join(
            json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n"
            for value in values
        ),
        encoding="utf-8",
    )


def read_jsonl(path: Path) -> list[dict[str, object]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
