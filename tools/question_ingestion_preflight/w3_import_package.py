from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import time
import tracemalloc
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, TextIO


CANONICAL_SCHEMA = "question-import/1.0"
EXPLANATION_SCHEMA = "question-explanation-final/1.0"
PACKAGE_SCHEMA = "w3-question-import-package/1.0"
DEFAULT_CANONICAL_SHA256 = (
    "7b1bb38b23826361ff41a023062d2ae788abc6c86f773e744cac91fec95577ab"
)


@dataclass(frozen=True)
class ExpectedCounts:
    source_total: int
    usable: int
    duplicates: int
    anomalies: int
    local_recoveries: int

    def validate(self) -> None:
        if self.usable + self.duplicates + self.anomalies != self.source_total:
            raise ValueError("expected count reconciliation does not close")


@dataclass
class OutputChunk:
    index: int
    path: Path
    handle: TextIO
    sha256: hashlib._Hash
    total: int = 0
    eligible: int = 0
    ineligible: int = 0
    first_source_row: int | None = None
    last_source_row: int | None = None


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_jsonl(path: Path) -> Iterator[tuple[int, dict[str, object]]]:
    with path.open(encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            if not line.strip():
                raise ValueError(f"blank JSONL row at {path}:{line_no}")
            try:
                value = json.loads(line)
            except json.JSONDecodeError as exception:
                raise ValueError(f"invalid JSON at {path}:{line_no}") from exception
            if not isinstance(value, dict):
                raise ValueError(f"JSONL row is not an object at {path}:{line_no}")
            yield line_no, value


def canonical_bytes(value: object) -> bytes:
    return (
        json.dumps(
            value,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        + "\n"
    ).encode("utf-8")


def answer_projection_from_canonical(record: dict[str, object]) -> dict[str, object]:
    question = required_object(record, "question")
    frozen = required_object(question, "frozenAnswer")
    sub_questions = question.get("subQuestions")
    grouped_single_choice = (
        question.get("questionType") == "SINGLE_CHOICE"
        and isinstance(sub_questions, list)
        and len(sub_questions) > 1
    )
    keys = normalized_keys(
        frozen.get("optionKeys"), allow_duplicates=grouped_single_choice
    )
    if keys:
        return {"optionKeys": keys}
    boolean_value = frozen.get("booleanValue")
    if isinstance(boolean_value, bool):
        return {"booleanValue": boolean_value}
    return {"normalized": normalized_scalar(frozen.get("normalized"))}


def answer_projection_from_explanation(record: dict[str, object]) -> dict[str, object]:
    keys = normalized_keys(record.get("frozenAnswerKeys"))
    if keys:
        return {"optionKeys": keys}
    frozen = record.get("frozenAnswer")
    if isinstance(frozen, bool):
        return {"booleanValue": frozen}
    normalized = normalized_scalar(frozen)
    if normalized in {"true", "correct", "yes", "1", "right"}:
        return {"booleanValue": True}
    if normalized in {"false", "incorrect", "no", "0", "wrong"}:
        return {"booleanValue": False}
    return {"normalized": normalized}


def answer_hash(projection: dict[str, object]) -> str:
    payload = json.dumps(
        projection, ensure_ascii=True, separators=(",", ":"), sort_keys=True
    ).encode("ascii")
    return hashlib.sha256(payload).hexdigest()


def normalized_keys(value: object, *, allow_duplicates: bool = False) -> list[str]:
    if not isinstance(value, list):
        return []
    result = [str(item).strip().upper() for item in value if str(item).strip()]
    if not allow_duplicates and len(result) != len(set(result)):
        raise ValueError("frozen answer contains duplicate option keys")
    return result


def is_recovered_grouped_first_answer(
    canonical: dict[str, object], accepted_answer: dict[str, object]
) -> bool:
    question = required_object(canonical, "question")
    trace = required_object(canonical, "sourceTrace")
    sub_questions = question.get("subQuestions")
    if (
        not isinstance(trace.get("localRecovery"), dict)
        or question.get("questionType") != "SINGLE_CHOICE"
        or not isinstance(sub_questions, list)
        or len(sub_questions) < 2
    ):
        return False
    grouped_keys: list[str] = []
    for raw_sub_question in sub_questions:
        if not isinstance(raw_sub_question, dict):
            return False
        answers = raw_sub_question.get("answers")
        if not isinstance(answers, list) or len(answers) != 1:
            return False
        answer = answers[0]
        if not isinstance(answer, dict):
            return False
        keys = normalized_keys(answer.get("optionKeys"))
        if len(keys) != 1:
            return False
        grouped_keys.extend(keys)
    canonical_answer = answer_projection_from_canonical(canonical)
    return (
        canonical_answer == {"optionKeys": grouped_keys}
        and accepted_answer == {"optionKeys": grouped_keys[:1]}
    )


def normalized_scalar(value: object) -> str:
    if value is None:
        return ""
    text = re.sub(r"\s+", "", str(value)).casefold()
    return text.replace("，", ",").replace("、", ",").strip(",")


def required_object(parent: dict[str, object], field: str) -> dict[str, object]:
    value = parent.get(field)
    if not isinstance(value, dict):
        raise ValueError(f"{field} must be an object")
    return value


def required_list(parent: dict[str, object], field: str) -> list[object]:
    value = parent.get(field)
    if not isinstance(value, list):
        raise ValueError(f"{field} must be an array")
    return value


def required_text(parent: dict[str, object], field: str) -> str:
    value = parent.get(field)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field} must be non-empty text")
    return value.strip()


def required_int(parent: dict[str, object], field: str) -> int:
    value = parent.get(field)
    if not isinstance(value, int) or isinstance(value, bool):
        raise ValueError(f"{field} must be an integer")
    return value


def load_json_object(path: Path) -> dict[str, object]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return value


def verify_production_inventory(path: Path) -> dict[str, dict[str, object]]:
    manifest = load_json_object(path)
    inventory = manifest.get("fileInventory")
    if not isinstance(inventory, list) or not inventory:
        raise ValueError("production import manifest has no file inventory")
    result: dict[str, dict[str, object]] = {}
    for raw in inventory:
        if not isinstance(raw, dict):
            raise ValueError("production file inventory entry must be an object")
        name = required_text(raw, "name")
        artifact = Path(required_text(raw, "path")).resolve(strict=True)
        if artifact.name != name or name in result:
            raise ValueError("production file inventory name/path is invalid")
        actual_size = artifact.stat().st_size
        actual_sha = sha256_file(artifact)
        if required_int(raw, "bytes") != actual_size:
            raise ValueError(f"production inventory byte count drifted for {name}")
        if required_text(raw, "sha256").lower() != actual_sha:
            raise ValueError(f"production inventory SHA drifted for {name}")
        result[name] = {**raw, "resolvedPath": str(artifact)}
    return result


def require_inventory_artifact(
    inventory: dict[str, dict[str, object]], path: Path
) -> None:
    item = inventory.get(path.name)
    if item is None or Path(str(item["resolvedPath"])) != path:
        raise ValueError(f"{path.name} is not the production manifest artifact")


def verify_control_files(
    canonical_path: Path,
    canonical_manifest_path: Path,
    checkpoint_path: Path,
    reconciliation_path: Path,
    canonical_sha256: str,
    expected: ExpectedCounts,
) -> tuple[dict[str, object], dict[str, object], dict[str, object]]:
    manifest = load_json_object(canonical_manifest_path)
    checkpoint = load_json_object(checkpoint_path)
    reconciliation = load_json_object(reconciliation_path)
    artifact = required_object(required_object(manifest, "outputArtifacts"),
                               "canonicalExplanationInput")
    if required_text(artifact, "sha256").lower() != canonical_sha256:
        raise ValueError("canonical manifest SHA does not match canonical input")
    if required_int(artifact, "recordCount") != expected.source_total:
        raise ValueError("canonical manifest record count mismatch")
    components = required_object(required_object(manifest, "countReconciliation"),
                                 "components")
    manifest_counts = (
        required_int(components, "usableCanonicalQuestions"),
        required_int(components, "exactDuplicateMappings"),
        required_int(components, "explicitAnomalySourceRows"),
    )
    if manifest_counts != (expected.usable, expected.duplicates, expected.anomalies):
        raise ValueError("canonical manifest reconciliation mismatch")
    for name, control in (("checkpoint", checkpoint), ("reconciliation", reconciliation)):
        if required_text(control, "inputSha256").lower() != canonical_sha256:
            raise ValueError(f"{name} is not bound to canonical v1.3 SHA")
        if required_int(control, "sourceTotal") != expected.source_total:
            raise ValueError(f"{name} source total mismatch")
        if required_int(control, "accepted") != expected.usable:
            raise ValueError(f"{name} accepted count mismatch")
    if required_int(checkpoint, "duplicateMappings") != expected.duplicates:
        raise ValueError("checkpoint duplicate count mismatch")
    if required_int(checkpoint, "escalation") != expected.anomalies:
        raise ValueError("checkpoint escalation count mismatch")
    if required_int(reconciliation, "reconciledTotal") != expected.source_total:
        raise ValueError("final reconciliation does not close")
    return manifest, checkpoint, reconciliation


def validate_recovery(trace: dict[str, object]) -> bool:
    recovery = trace.get("localRecovery")
    if recovery is None:
        return False
    if not isinstance(recovery, dict):
        raise ValueError("sourceTrace.localRecovery must be an object")
    required_text(recovery, "sourcePath")
    source_sha = required_text(recovery, "sourceSha256").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", source_sha):
        raise ValueError("local recovery source SHA is invalid")
    required_text(recovery, "anchor")
    if not isinstance(recovery.get("pageNo"), int):
        raise ValueError("local recovery page number is missing")
    return True


def validate_and_enrich(
    canonical: dict[str, object],
    explanation: dict[str, object],
    accepted_sha256: str,
) -> None:
    if explanation.get("schemaVersion") != EXPLANATION_SCHEMA:
        raise ValueError("accepted explanation schema mismatch")
    if explanation.get("decision") != "accepted":
        raise ValueError("accepted input contains a non-accepted decision")
    if explanation.get("importEligible") is not True:
        raise ValueError("accepted explanation is not import eligible")
    quality = required_object(canonical, "quality")
    if quality.get("importEligible") is not True or quality.get("sourceStatus") != "CANONICAL":
        raise ValueError("accepted explanation points to an ineligible canonical row")
    trace = required_object(canonical, "sourceTrace")
    question = required_object(canonical, "question")
    source_row = required_int(trace, "rowNo")
    identity_pairs = (
        (str(source_row), str(required_int(explanation, "sourceRow"))),
        (required_text(question, "externalKey"), required_text(explanation, "externalKey")),
        (required_text(question, "contentHash").lower(),
         required_text(explanation, "contentHash").lower()),
        (str(trace.get("sourceQuestionId")), str(explanation.get("sourceQuestionId"))),
        (required_text(trace, "locator"), required_text(explanation, "canonicalQuestionId")),
    )
    if any(left != right for left, right in identity_pairs):
        raise ValueError(f"accepted identity mismatch at source row {source_row}")
    explanation_trace = required_object(explanation, "sourceTrace")
    if required_text(explanation_trace, "documentSha256").lower() != required_text(
        trace, "documentSha256"
    ).lower():
        raise ValueError(f"accepted source SHA mismatch at source row {source_row}")
    canonical_answer = answer_projection_from_canonical(canonical)
    accepted_answer = answer_projection_from_explanation(explanation)
    canonical_answer_hash = answer_hash(canonical_answer)
    accepted_answer_hash = answer_hash(accepted_answer)
    first_sub_question_only = False
    if canonical_answer_hash != accepted_answer_hash:
        first_sub_question_only = is_recovered_grouped_first_answer(
            canonical, accepted_answer
        )
    if canonical_answer_hash != accepted_answer_hash and not first_sub_question_only:
        raise ValueError(f"frozen answer mismatch at source row {source_row}")
    generated = required_text(explanation, "explanation")
    sub_questions = required_list(question, "subQuestions")
    if not sub_questions:
        raise ValueError(f"canonical row {source_row} has no sub-question")
    solution_targets = sub_questions[:1] if first_sub_question_only else sub_questions
    for value in solution_targets:
        if not isinstance(value, dict):
            raise ValueError(f"invalid sub-question at source row {source_row}")
        value["solutions"] = [
            {"solutionType": "ANALYSIS", "content": generated, "sortOrder": 1}
        ]
    canonical["explanationTrace"] = {
        "acceptedArtifactSha256": accepted_sha256,
        "acceptedSchemaVersion": EXPLANATION_SCHEMA,
        "acceptedAnswerHash": accepted_answer_hash,
        "answerHash": canonical_answer_hash,
        "explanationScope": (
            "FIRST_SUBQUESTION_ONLY" if first_sub_question_only else "ALL_SUBQUESTIONS"
        ),
        "generationMode": explanation.get("generationMode"),
        "model": explanation.get("model"),
        "promptVersion": explanation.get("promptVersion"),
        "sourceRow": source_row,
    }


def validate_ledger_identity(
    canonical: dict[str, object],
    ledger: dict[str, object],
    *,
    schema: str,
    status: str,
) -> None:
    if ledger.get("schemaVersion") != schema or ledger.get("sourceStatus") != status:
        raise ValueError(f"{status} ledger schema/status mismatch")
    trace = required_object(canonical, "sourceTrace")
    question = required_object(canonical, "question")
    ledger_trace = required_object(ledger, "sourceTrace")
    pairs = (
        (str(required_int(trace, "rowNo")), str(required_int(ledger, "sourceRow"))),
        (required_text(question, "externalKey"), required_text(ledger, "externalKey")),
        (required_text(question, "contentHash").lower(),
         required_text(ledger, "contentHash").lower()),
        (str(trace.get("sourceQuestionId")), str(ledger.get("sourceQuestionId"))),
        (required_text(trace, "locator"), required_text(ledger_trace, "locator")),
        (required_text(trace, "documentSha256").lower(),
         required_text(ledger_trace, "documentSha256").lower()),
    )
    if any(left != right for left, right in pairs):
        raise ValueError(f"{status} ledger identity mismatch at source row {trace.get('rowNo')}")


def open_chunk(staging: Path, index: int) -> OutputChunk:
    directory = staging / "chunks" / f"{index:05d}"
    directory.mkdir(parents=True)
    path = directory / "canonical-questions.jsonl"
    return OutputChunk(index, path, path.open("wb"), hashlib.sha256())


def close_chunk(chunk: OutputChunk, staging: Path) -> dict[str, object]:
    chunk.handle.flush()
    os.fsync(chunk.handle.fileno())
    chunk.handle.close()
    return {
        "eligibleCount": chunk.eligible,
        "firstSourceRow": chunk.first_source_row,
        "index": chunk.index,
        "ineligibleCount": chunk.ineligible,
        "lastSourceRow": chunk.last_source_row,
        "relativePath": chunk.path.relative_to(staging).as_posix(),
        "sha256": chunk.sha256.hexdigest(),
        "sizeBytes": chunk.path.stat().st_size,
        "totalCount": chunk.total,
    }


def build_import_package(
    canonical_path: Path,
    canonical_manifest_path: Path,
    production_manifest_path: Path,
    accepted_path: Path,
    duplicate_mappings_path: Path,
    escalation_path: Path,
    checkpoint_path: Path,
    reconciliation_path: Path,
    output_path: Path,
    *,
    expected_canonical_sha256: str,
    expected: ExpectedCounts,
    chunk_size: int = 500,
) -> dict[str, object]:
    expected.validate()
    if chunk_size < 1 or chunk_size > 1000:
        raise ValueError("chunk size must be between 1 and 1000")
    paths = [canonical_path, canonical_manifest_path, production_manifest_path,
             accepted_path, duplicate_mappings_path, escalation_path,
             checkpoint_path, reconciliation_path]
    paths = [path.resolve(strict=True) for path in paths]
    (canonical_path, canonical_manifest_path, production_manifest_path,
     accepted_path, duplicate_mappings_path, escalation_path,
     checkpoint_path, reconciliation_path) = paths
    expected_sha = expected_canonical_sha256.lower()
    canonical_sha = sha256_file(canonical_path)
    if canonical_sha != expected_sha:
        raise ValueError("canonical input SHA is not the accepted v1.3 SHA")
    verify_control_files(
        canonical_path,
        canonical_manifest_path,
        checkpoint_path,
        reconciliation_path,
        canonical_sha,
        expected,
    )
    inventory = verify_production_inventory(production_manifest_path)
    for artifact in (accepted_path, duplicate_mappings_path, escalation_path,
                     checkpoint_path, reconciliation_path):
        require_inventory_artifact(inventory, artifact)
    accepted_sha = sha256_file(accepted_path)
    duplicate_sha = sha256_file(duplicate_mappings_path)
    escalation_sha = sha256_file(escalation_path)
    output_path = output_path.resolve()
    if output_path.exists():
        raise ValueError("output directory already exists")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    staging = output_path.parent / f".{output_path.name}.building-{uuid.uuid4().hex}"
    if staging.resolve().parent != output_path.parent.resolve():
        raise ValueError("staging directory escaped output parent")
    staging.mkdir()
    started = time.perf_counter()
    tracemalloc.start()
    counts = {"usable": 0, "duplicates": 0, "anomalies": 0, "recoveries": 0}
    chunk_manifests: list[dict[str, object]] = []
    master_path = staging / "canonical-questions.jsonl"
    accepted_iterator = iter(read_jsonl(accepted_path))
    accepted_item = next(accepted_iterator, None)
    duplicate_iterator = iter(read_jsonl(duplicate_mappings_path))
    duplicate_item = next(duplicate_iterator, None)
    escalation_iterator = iter(read_jsonl(escalation_path))
    escalation_item = next(escalation_iterator, None)
    previous_source_row = 0
    chunk: OutputChunk | None = None
    try:
        with master_path.open("wb") as master:
            master_hash = hashlib.sha256()
            for canonical_line, canonical in read_jsonl(canonical_path):
                if canonical.get("schemaVersion") != CANONICAL_SCHEMA:
                    raise ValueError(f"canonical schema mismatch at line {canonical_line}")
                if canonical.get("rightsStatus") != "UNVERIFIED":
                    raise ValueError(f"rights status mismatch at line {canonical_line}")
                trace = required_object(canonical, "sourceTrace")
                source_row = required_int(trace, "rowNo")
                if source_row <= previous_source_row:
                    raise ValueError("canonical source rows are not strictly increasing")
                previous_source_row = source_row
                if validate_recovery(trace):
                    counts["recoveries"] += 1
                quality = required_object(canonical, "quality")
                status = quality.get("sourceStatus")
                eligible = quality.get("importEligible") is True
                if eligible:
                    if status != "CANONICAL":
                        raise ValueError(f"eligible status mismatch at source row {source_row}")
                    if accepted_item is None:
                        raise ValueError("accepted explanations ended before canonical input")
                    _, explanation = accepted_item
                    accepted_source_row = required_int(explanation, "sourceRow")
                    if accepted_source_row != source_row:
                        raise ValueError(
                            f"accepted/canonical source row mismatch: {accepted_source_row} != {source_row}"
                        )
                    validate_and_enrich(canonical, explanation, accepted_sha)
                    accepted_item = next(accepted_iterator, None)
                    counts["usable"] += 1
                elif status == "DUPLICATE_SOURCE":
                    if duplicate_item is None:
                        raise ValueError("duplicate ledger ended before canonical input")
                    _, duplicate = duplicate_item
                    validate_ledger_identity(
                        canonical,
                        duplicate,
                        schema="question-explanation-duplicate-mapping/1.0",
                        status="DUPLICATE_SOURCE",
                    )
                    canonical["duplicateTrace"] = {
                        "artifactSha256": duplicate_sha,
                        "duplicateOfExternalKey": required_text(
                            duplicate, "duplicateOfExternalKey"
                        ),
                        "duplicateOfSourceQuestionId": str(
                            duplicate.get("duplicateOfSourceQuestionId")
                        ),
                        "duplicateOfSourceRow": required_int(
                            duplicate, "duplicateOfSourceRow"
                        ),
                    }
                    duplicate_item = next(duplicate_iterator, None)
                    counts["duplicates"] += 1
                elif status == "SOURCE_ERROR":
                    if escalation_item is None:
                        raise ValueError("escalation ledger ended before canonical input")
                    _, escalation = escalation_item
                    validate_ledger_identity(
                        canonical,
                        escalation,
                        schema="question-explanation-invalid-source/1.0",
                        status="SOURCE_ERROR",
                    )
                    ledger_errors = sorted(str(code) for code in required_list(
                        escalation, "errorCodes"
                    ))
                    canonical_errors = sorted(
                        required_text(error, "code")
                        for error in required_list(quality, "validationErrors")
                        if isinstance(error, dict)
                    )
                    if ledger_errors != canonical_errors:
                        raise ValueError(
                            f"escalation error codes mismatch at source row {source_row}"
                        )
                    canonical["governanceTrace"] = {
                        "artifactSha256": escalation_sha,
                        "errorCodes": ledger_errors,
                        "practiceEligible": False,
                        "publishable": False,
                    }
                    escalation_item = next(escalation_iterator, None)
                    counts["anomalies"] += 1
                else:
                    raise ValueError(f"unknown canonical status at source row {source_row}")
                payload = canonical_bytes(canonical)
                master.write(payload)
                master_hash.update(payload)
                if chunk is None:
                    chunk = open_chunk(staging, len(chunk_manifests) + 1)
                chunk.handle.write(payload)
                chunk.sha256.update(payload)
                chunk.total += 1
                chunk.eligible += int(eligible)
                chunk.ineligible += int(not eligible)
                chunk.first_source_row = chunk.first_source_row or source_row
                chunk.last_source_row = source_row
                if chunk.total == chunk_size:
                    chunk_manifests.append(close_chunk(chunk, staging))
                    chunk = None
            master.flush()
            os.fsync(master.fileno())
        if chunk is not None:
            chunk_manifests.append(close_chunk(chunk, staging))
            chunk = None
        if accepted_item is not None:
            raise ValueError("accepted explanations contain rows not present in canonical input")
        if duplicate_item is not None:
            raise ValueError("duplicate ledger contains rows not present in canonical input")
        if escalation_item is not None:
            raise ValueError("escalation ledger contains rows not present in canonical input")
        actual = (
            counts["usable"], counts["duplicates"], counts["anomalies"],
            counts["recoveries"],
        )
        wanted = (
            expected.usable, expected.duplicates, expected.anomalies,
            expected.local_recoveries,
        )
        if actual != wanted:
            raise ValueError(f"package reconciliation mismatch: {actual} != {wanted}")
        _, peak_allocated = tracemalloc.get_traced_memory()
        manifest = {
            "acceptedInput": {
                "path": str(accepted_path),
                "sha256": accepted_sha,
                "sizeBytes": accepted_path.stat().st_size,
            },
            "canonicalInput": {
                "path": str(canonical_path),
                "sha256": canonical_sha,
                "sizeBytes": canonical_path.stat().st_size,
            },
            "checkpoint": {
                "path": str(checkpoint_path),
                "sha256": sha256_file(checkpoint_path),
            },
            "chunks": chunk_manifests,
            "countReconciliation": {
                "anomalies": counts["anomalies"],
                "duplicates": counts["duplicates"],
                "localRecoveries": counts["recoveries"],
                "sourceTotal": sum(counts[key] for key in ("usable", "duplicates", "anomalies")),
                "usable": counts["usable"],
            },
            "importPolicy": {
                "anomaliesPracticeEligible": False,
                "anomaliesPublishable": False,
                "rightsStatus": "UNVERIFIED",
                "usageRestriction": "INTERNAL_ONLY",
            },
            "masterArtifact": {
                "relativePath": "canonical-questions.jsonl",
                "sha256": master_hash.hexdigest(),
                "sizeBytes": master_path.stat().st_size,
            },
            "performance": {
                "chunkSize": chunk_size,
                "elapsedSeconds": round(time.perf_counter() - started, 3),
                "peakPythonAllocatedBytes": peak_allocated,
                "streamingMerge": True,
            },
            "reconciliation": {
                "path": str(reconciliation_path),
                "sha256": sha256_file(reconciliation_path),
            },
            "productionArtifacts": {
                "duplicateMappings": {
                    "path": str(duplicate_mappings_path),
                    "sha256": duplicate_sha,
                },
                "escalationQueue": {
                    "path": str(escalation_path),
                    "sha256": escalation_sha,
                },
                "importManifest": {
                    "path": str(production_manifest_path),
                    "sha256": sha256_file(production_manifest_path),
                },
            },
            "schemaVersion": PACKAGE_SCHEMA,
        }
        manifest_path = staging / "import-manifest.json"
        manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        staging.replace(output_path)
        return manifest
    except Exception:
        if chunk is not None and not chunk.handle.closed:
            chunk.handle.close()
        if staging.exists() and staging.resolve().parent == output_path.parent.resolve():
            shutil.rmtree(staging)
        raise
    finally:
        tracemalloc.stop()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build a SHA-bound, fully tracked W3 question import package"
    )
    parser.add_argument("--canonical", type=Path, required=True)
    parser.add_argument("--canonical-manifest", type=Path, required=True)
    parser.add_argument("--production-manifest", type=Path, required=True)
    parser.add_argument("--accepted", type=Path, required=True)
    parser.add_argument("--duplicate-mappings", type=Path, required=True)
    parser.add_argument("--escalation", type=Path, required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--reconciliation", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--canonical-sha256", default=DEFAULT_CANONICAL_SHA256)
    parser.add_argument("--source-total", type=int, default=29538)
    parser.add_argument("--usable", type=int, default=26284)
    parser.add_argument("--duplicates", type=int, default=25)
    parser.add_argument("--anomalies", type=int, default=3229)
    parser.add_argument("--local-recoveries", type=int, default=14)
    parser.add_argument("--chunk-size", type=int, default=500)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    manifest = build_import_package(
        args.canonical,
        args.canonical_manifest,
        args.production_manifest,
        args.accepted,
        args.duplicate_mappings,
        args.escalation,
        args.checkpoint,
        args.reconciliation,
        args.output,
        expected_canonical_sha256=args.canonical_sha256,
        expected=ExpectedCounts(
            args.source_total,
            args.usable,
            args.duplicates,
            args.anomalies,
            args.local_recoveries,
        ),
        chunk_size=args.chunk_size,
    )
    print(json.dumps(manifest, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
