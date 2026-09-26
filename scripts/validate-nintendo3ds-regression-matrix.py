#!/usr/bin/env python3
"""Validate the N3DS-13 matrix without exposing private content identities."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any, Iterable


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = PROJECT_ROOT / "config" / "nintendo3ds-regression-matrix.json"
OPAQUE_SLOT = re.compile(r"^(?:PRIVATE_OWNED_DUMP|OPEN_HOMEBREW)_[1-9][0-9]*$")
WINDOWS_PATH = re.compile(r"(?i)(?:^|\s)[a-z]:[\\/]")
UNIX_PRIVATE_PATH = re.compile(r"(?:^|\s)/(?:data|sdcard|storage|home|users?)/")
ROM_FILENAME = re.compile(r"(?i)\.(?:3ds|3dsx|cia|cci|cxi)(?:$|\s)")
SHA256_HEX = re.compile(r"(?i)(?:^|[^0-9a-f])[0-9a-f]{64}(?:$|[^0-9a-f])")
VALID_CASE_STATUS = {"PASSED", "FAILED", "NOT_RUN"}
VALID_MATRIX_STATUS = {"PASSED", "FAILED", "NOT_RUN", "EXTERNAL_GATE"}


def _duplicates(values: Iterable[str]) -> set[str]:
    seen: set[str] = set()
    duplicates: set[str] = set()
    for value in values:
        if value in seen:
            duplicates.add(value)
        seen.add(value)
    return duplicates


def _walk(value: Any):
    if isinstance(value, dict):
        for key, child in value.items():
            yield key, child
            yield from _walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from _walk(child)


def validate_contract(contract: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if contract.get("schemaVersion") != 1 or contract.get("planItem") != "N3DS-13":
        errors.append("regression contract identity changed")
    if contract.get("privacyMode") != "OPAQUE_SLOT_IDS_ONLY":
        errors.append("private content must use opaque slot IDs only")
    if contract.get("minimumDistinctNintendo3DsContents") != 10:
        errors.append("N3DS-13 requires at least ten distinct Nintendo 3DS contents")
    if contract.get("maximumDistinctNintendo3DsContents") != 20:
        errors.append("N3DS-13 maximum corpus must remain twenty contents")

    slots = contract.get("currentlyAvailableSlots", [])
    if not isinstance(slots, list) or not slots:
        errors.append("available opaque content slots are missing")
    elif any(not isinstance(slot, str) or not OPAQUE_SLOT.fullmatch(slot) for slot in slots):
        errors.append("available content slot is not opaque")
    if _duplicates(slots):
        errors.append("available content slots must be unique")
    if contract.get("requiredExistingSystems") != ["GB", "GBC", "GBA", "NDS"]:
        errors.append("existing-system regression order changed")
    if contract.get("acceptanceSlices") != [
        "N3DS-13A_MATRIX_AND_SANITIZED_REPORT_CONTRACT",
        "N3DS-13B_AVAILABLE_GALAXY_CORPUS_AND_EXISTING_SYSTEMS",
        "N3DS-13C_SAVE_UPDATE_LIFECYCLE_AND_LONG_RUN",
        "N3DS-13D_TEN_CONTENTS_AND_PHYSICAL_ADRENO",
    ]:
        errors.append("N3DS-13 acceptance slices changed")

    report_contract = contract.get("reportContract", {})
    fragments = report_contract.get("forbiddenKeyFragments", [])
    expected_fragments = [
        "path", "file", "name", "title", "productcode", "hash", "digest", "uri"
    ]
    if fragments != expected_fragments:
        errors.append("private report forbidden-key policy changed")
    return errors


def _privacy_errors(report: dict[str, Any], forbidden_fragments: list[str]) -> list[str]:
    errors: list[str] = []
    for key, value in _walk(report):
        lowered_key = str(key).lower().replace("_", "")
        if any(fragment in lowered_key for fragment in forbidden_fragments):
            errors.append(f"report contains forbidden private field: {key}")
        if isinstance(value, str) and (
            WINDOWS_PATH.search(value)
            or UNIX_PRIVATE_PATH.search(value)
            or ROM_FILENAME.search(value)
            or SHA256_HEX.search(value)
        ):
            errors.append("report contains a private path, ROM filename, or content digest")
    return errors


def _unexpected_fields(value: dict[str, Any], allowed: list[str], label: str) -> list[str]:
    unexpected = sorted(set(value) - set(allowed))
    return [f"{label} contains unapproved fields: {', '.join(unexpected)}"] if unexpected else []


def validate_report(report: dict[str, Any], contract: dict[str, Any]) -> list[str]:
    errors = validate_contract(contract)
    report_contract = contract.get("reportContract", {})
    errors.extend(
        _unexpected_fields(
            report, report_contract.get("allowedTopLevelFields", []), "report"
        )
    )
    errors.extend(
        _privacy_errors(report, report_contract.get("forbiddenKeyFragments", []))
    )
    if report.get("schemaVersion") != 1 or report.get("planItem") != "N3DS-13":
        errors.append("regression report identity changed")
    status = report.get("status")
    if status not in {"PASSED", "PARTIAL_EXTERNAL_GATES", "FAILED"}:
        errors.append("regression report status is invalid")

    cases = report.get("cases", [])
    if not isinstance(cases, list):
        errors.append("regression cases must be a list")
        cases = []
    slot_ids: list[str] = []
    passed_cases = 0
    for case in cases:
        if not isinstance(case, dict):
            errors.append("regression case must be an object")
            continue
        errors.extend(
            _unexpected_fields(
                case, report_contract.get("allowedCaseFields", []), "regression case"
            )
        )
        slot_id = case.get("slotId")
        if not isinstance(slot_id, str) or not OPAQUE_SLOT.fullmatch(slot_id):
            errors.append("regression case slot is not opaque")
        else:
            slot_ids.append(slot_id)
        if case.get("status") not in VALID_CASE_STATUS:
            errors.append("regression case status is invalid")
        if case.get("status") == "PASSED":
            passed_cases += 1
            for metric in ("frames", "inputPolls", "elapsedMillis"):
                if not isinstance(case.get(metric), int) or case[metric] <= 0:
                    errors.append(f"passed regression case lacks positive {metric}")
            if case.get("fatalSignals") != 0:
                errors.append("passed regression case contains a fatal signal")
    if _duplicates(slot_ids):
        errors.append("regression report contains duplicate content slots")

    existing_systems = report.get("existingSystems", [])
    observed_systems: list[str] = []
    for system_result in existing_systems if isinstance(existing_systems, list) else []:
        if not isinstance(system_result, dict):
            errors.append("existing-system result must be an object")
            continue
        errors.extend(
            _unexpected_fields(
                system_result,
                report_contract.get("allowedExistingSystemFields", []),
                "existing-system result",
            )
        )
        observed_systems.append(system_result.get("system"))
    if observed_systems and observed_systems != contract["requiredExistingSystems"]:
        errors.append("existing-system report order or coverage changed")

    matrix_evidence = report.get("matrixEvidence", [])
    for evidence in matrix_evidence if isinstance(matrix_evidence, list) else []:
        if not isinstance(evidence, dict):
            errors.append("matrix evidence must be an object")
            continue
        errors.extend(
            _unexpected_fields(
                evidence,
                report_contract.get("allowedMatrixEvidenceFields", []),
                "matrix evidence",
            )
        )
        if evidence.get("status") not in VALID_MATRIX_STATUS:
            errors.append("matrix evidence status is invalid")

    summary = report.get("summary", {})
    if not isinstance(summary, dict):
        errors.append("regression summary must be an object")
        summary = {}
    else:
        errors.extend(
            _unexpected_fields(
                summary, report_contract.get("allowedSummaryFields", []), "summary"
            )
        )
    if summary.get("distinctNintendo3DsContents") != len(set(slot_ids)):
        errors.append("summary distinct-content count does not match cases")
    if summary.get("passedNintendo3DsContents") != passed_cases:
        errors.append("summary passed-content count does not match cases")
    if status == "PASSED":
        minimum = contract["minimumDistinctNintendo3DsContents"]
        if len(set(slot_ids)) < minimum or passed_cases != len(cases):
            errors.append("PASSED requires at least ten distinct passing contents")
        if observed_systems != contract["requiredExistingSystems"] or any(
            item.get("status") != "PASSED" for item in existing_systems
        ):
            errors.append("PASSED requires all four existing systems")
        expected_evidence = set(contract["requiredMatrixEvidence"])
        passed_evidence = {
            item.get("evidence")
            for item in matrix_evidence
            if item.get("status") == "PASSED"
        }
        if not expected_evidence.issubset(passed_evidence):
            errors.append("PASSED requires every matrix-level evidence item")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    contract = json.loads(args.contract.read_text(encoding="utf-8"))
    errors = validate_contract(contract)
    if args.report:
        report = json.loads(args.report.read_text(encoding="utf-8"))
        errors = validate_report(report, contract)
    result = {
        "status": "PASSED" if not errors else "FAILED",
        "contract": str(args.contract),
        "report": str(args.report) if args.report else None,
        "errors": errors,
    }
    print(json.dumps(result, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
