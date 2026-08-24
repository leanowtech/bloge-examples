#!/usr/bin/env python3
"""Validate, compile twice, and independently rebind Gate A protocol projections."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import tempfile
from typing import Any
from urllib.parse import urldefrag, urljoin

from jsonschema import Draft202012Validator


HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[4]
SCHEMA_ROOT = HERE.parents[3] / "schemas" / "resource-gateway-capability-studio"
SOURCE = HERE / "gate-a-protocol-authority-v1.json"
AUTHORITY_SCHEMA = SCHEMA_ROOT / "capability-studio-gate-a-protocol-authority-v1.schema.json"
PROJECTION_SCHEMA = SCHEMA_ROOT / "capability-studio-gate-a-protocol-projection-v1.schema.json"
MANIFEST_SCHEMA = SCHEMA_ROOT / "capability-studio-gate-a-protocol-compilation-manifest-v1.schema.json"
COMPILER = HERE / "compile-protocol-authority.py"

parser = argparse.ArgumentParser()
parser.add_argument("--check", action="store_true")
parser.add_argument("--self-test", action="store_true", help="Run self-test suite including 60-attack catalog.")
arguments = parser.parse_args()


def reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"PROTOCOL_JSON_DUPLICATE_MEMBER: {key}")
        result[key] = value
    return result


def reject_non_finite(value: str) -> None:
    raise ValueError(f"PROTOCOL_JSON_NON_FINITE_NUMBER: {value}")


def load_strict(path: pathlib.Path) -> Any:
    return json.loads(
        path.read_text(encoding="utf-8"),
        object_pairs_hook=reject_duplicate_pairs,
        parse_constant=reject_non_finite,
    )


def sha256(path: pathlib.Path) -> str:
    return f"sha256:{hashlib.sha256(path.read_bytes()).hexdigest()}"


def validate(schema_path: pathlib.Path, document: Any, label: str) -> None:
    schema = load_strict(schema_path)
    Draft202012Validator.check_schema(schema)
    errors = sorted(
        Draft202012Validator(schema).iter_errors(document),
        key=lambda error: (list(error.absolute_path), error.message),
    )
    if not errors:
        return
    first = errors[0]
    location = "$" + "".join(
        f"[{part}]" if isinstance(part, int) else f".{part}"
        for part in first.absolute_path
    )
    raise SystemExit(
        f"{label}_SCHEMA_VALIDATION_FAILED: {location} "
        f"[{first.validator}] {first.message}"
    )


def iter_refs(value: Any):
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "$ref" and isinstance(child, str):
                yield child
            yield from iter_refs(child)
    elif isinstance(value, list):
        for child in value:
            yield from iter_refs(child)


def validate_schema_inventory(authority: dict[str, Any]) -> None:
    policy = authority["schemaInventoryPolicy"]
    gate_names = policy["gateASchemas"]
    reviewer_names = policy["requiredReviewerSchemas"]
    actual_gate_names = sorted(path.name for path in SCHEMA_ROOT.glob("capability-studio-gate-a-*.schema.json"))
    reviewer_pattern = re.compile(r"^capability-studio-(?:review-body-v1|reviewer-.*)\.schema\.json$")
    actual_reviewer_names = sorted(
        path.name
        for path in SCHEMA_ROOT.iterdir()
        if path.is_file() and reviewer_pattern.fullmatch(path.name)
    )
    if gate_names != actual_gate_names:
        raise SystemExit("PROTOCOL_GATE_SCHEMA_CLOSED_SET_DRIFT")
    if policy.get("gateASchemaPattern") != "capability-studio-gate-a-*.schema.json":
        raise SystemExit("PROTOCOL_GATE_SCHEMA_PATTERN_DRIFT")
    if policy.get("reviewerSchemaPattern") != "capability-studio-(review-body-v1|reviewer-.*).schema.json":
        raise SystemExit("PROTOCOL_REVIEWER_SCHEMA_PATTERN_DRIFT")
    if reviewer_names != actual_reviewer_names:
        raise SystemExit("PROTOCOL_REVIEWER_SCHEMA_FILESYSTEM_CLOSED_SET_DRIFT")
    documents: dict[str, Any] = {}
    id_to_name: dict[str, str] = {}
    for name in [*gate_names, *reviewer_names]:
        document = load_strict(SCHEMA_ROOT / name)
        Draft202012Validator.check_schema(document)
        schema_id = document.get("$id")
        if not isinstance(schema_id, str) or schema_id in id_to_name:
            raise SystemExit(f"PROTOCOL_SCHEMA_ID_MISSING_OR_REUSED: {name}")
        id_to_name[schema_id] = name
        documents[name] = document
    known_ids = set(id_to_name)
    for name, document in documents.items():
        schema_id = document["$id"]
        for reference in iter_refs(document):
            if reference.startswith("#"):
                continue
            base, _ = urldefrag(urljoin(schema_id, reference))
            if base not in known_ids:
                raise SystemExit(f"PROTOCOL_SCHEMA_REF_OUTSIDE_REGISTRY: {name} -> {reference}")
    print(f"Gate A Schema registry PASS: {len(gate_names)} Gate + {len(reviewer_names)} Reviewer Schemas.")


def resolve_pointer(document: Any, pointer: str) -> Any:
    current = document
    for raw_part in pointer.removeprefix("/").split("/"):
        part = raw_part.replace("~1", "/").replace("~0", "~")
        if not isinstance(current, dict) or part not in current:
            raise SystemExit(f"PROTOCOL_PROJECTION_SELECTOR_MISSING: {pointer}")
        current = current[part]
    return current


def expected_content(authority: dict[str, Any], selectors: list[dict[str, Any]]) -> Any:
    if len(selectors) == 1 and selectors[0]["key"] is None:
        return resolve_pointer(authority, selectors[0]["sourcePointer"])
    return {
        selector["key"]: resolve_pointer(authority, selector["sourcePointer"])
        for selector in selectors
    }


def compile_to(output_root: pathlib.Path) -> None:
    # Use a subdirectory within output_root to avoid COMPILER_OUTPUT_ROOT_EXISTS
    # when output_root already exists (e.g., from tempfile.TemporaryDirectory).
    target = output_root / "protocol-output"
    subprocess.run(
        [sys.executable, str(COMPILER), "--output-root", str(target)],
        cwd=REPO,
        check=True,
        capture_output=True,
        text=True,
    )


def compare_trees(first: pathlib.Path, second: pathlib.Path) -> None:
    first_files = sorted(path.relative_to(first / "protocol-output") for path in (first / "protocol-output").rglob("*") if path.is_file())
    second_files = sorted(path.relative_to(second / "protocol-output") for path in (second / "protocol-output").rglob("*") if path.is_file())
    if first_files != second_files:
        raise SystemExit("PROTOCOL_DOUBLE_COMPILE_FILE_SET_DRIFT")
    for relative_path in first_files:
        if (first / "protocol-output" / relative_path).read_bytes() != (second / "protocol-output" / relative_path).read_bytes():
            raise SystemExit(f"PROTOCOL_DOUBLE_COMPILE_BYTES_DRIFT: {relative_path}")


def verify_outputs(root: pathlib.Path, authority: dict[str, Any]) -> None:
    source_raw = sha256(SOURCE)
    plan = authority["compilerContract"]["projectionPlan"]
    manifest_path = root / "protocol-output" / "protocol-compilation-manifest-v1.json"
    manifest = load_strict(manifest_path)
    validate(MANIFEST_SCHEMA, manifest, "PROTOCOL_COMPILATION_MANIFEST")
    if manifest["sourceRawFingerprint"] != source_raw:
        raise SystemExit("PROTOCOL_COMPILATION_SOURCE_DRIFT")
    if manifest["authoritySchemaRawFingerprint"] != sha256(AUTHORITY_SCHEMA):
        raise SystemExit("PROTOCOL_COMPILATION_AUTHORITY_SCHEMA_DRIFT")
    if manifest["projectionSchemaRawFingerprint"] != sha256(PROJECTION_SCHEMA):
        raise SystemExit("PROTOCOL_COMPILATION_PROJECTION_SCHEMA_DRIFT")
    manifest_entries = {entry["projectionId"]: entry for entry in manifest["projections"]}
    if list(manifest_entries) != [entry["projectionId"] for entry in plan]:
        raise SystemExit("PROTOCOL_COMPILATION_PROJECTION_ORDER_DRIFT")
    for plan_entry in plan:
        path = root / "protocol-output" / plan_entry["outputPath"]
        projection = load_strict(path)
        validate(PROJECTION_SCHEMA, projection, "PROTOCOL_PROJECTION")
        if projection["projectionId"] != plan_entry["projectionId"]:
            raise SystemExit("PROTOCOL_PROJECTION_ID_DRIFT")
        if projection["sourceRawFingerprint"] != source_raw:
            raise SystemExit("PROTOCOL_PROJECTION_SOURCE_DRIFT")
        if projection["sourceSelectors"] != plan_entry["selectors"]:
            raise SystemExit("PROTOCOL_PROJECTION_SELECTOR_DRIFT")
        if projection["content"] != expected_content(authority, plan_entry["selectors"]):
            raise SystemExit("PROTOCOL_PROJECTION_CONTENT_DRIFT")
        manifest_entry = manifest_entries[plan_entry["projectionId"]]
        if manifest_entry["path"] != plan_entry["outputPath"]:
            raise SystemExit("PROTOCOL_PROJECTION_PATH_DRIFT")
        if manifest_entry["jarEntryPath"] != plan_entry["jarEntryPath"]:
            raise SystemExit("PROTOCOL_PROJECTION_JAR_ENTRY_DRIFT")
        file_canon = json.dumps(json.loads(path.read_text(encoding="utf-8")), ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
        if manifest_entry["rawFingerprint"] != f"sha256:{hashlib.sha256(file_canon).hexdigest()}":
            raise SystemExit("PROTOCOL_PROJECTION_RAW_DRIFT")


authority = load_strict(SOURCE)
validate(AUTHORITY_SCHEMA, authority, "PROTOCOL_AUTHORITY")
validate_schema_inventory(authority)

primary_args = [sys.executable, str(COMPILER), "--self-test"]
if arguments.check:
    primary_args.append("--check")
subprocess.run(primary_args, cwd=REPO, check=True)

# ---- G: Authority fingerprint pre-check against trust pin ----
TRUST_PIN_PATH = HERE.parents[3] / "trust-build" / "valid-challenge-trust-pin.json"
if TRUST_PIN_PATH.exists():
    trust_pin = load_strict(TRUST_PIN_PATH)
    expected_authority_fp = trust_pin.get("expectedProtocolAuthorityRawFingerprint")
    if expected_authority_fp:
        actual_authority_fp = {"kind": "RAW_BYTES", "algorithm": "SHA-256", "value": sha256(SOURCE)}
        # The trust pin stores the raw fingerprint as a string like "sha256:..."
        expected_value = expected_authority_fp.get("value") if isinstance(expected_authority_fp, dict) else expected_authority_fp
        if expected_value and expected_value != sha256(SOURCE):
            raise SystemExit("PROTOCOL_AUTHORITY_FINGERPRINT_TRUST_PIN_MISMATCH")
        print(f"Gate A Protocol Authority fingerprint pre-check PASS: matches trust-build/valid-challenge-trust-pin.json")

with tempfile.TemporaryDirectory(prefix="gate-a-protocol-first-") as first_raw, tempfile.TemporaryDirectory(prefix="gate-a-protocol-second-") as second_raw:
    first = pathlib.Path(first_raw)
    second = pathlib.Path(second_raw)
    compile_to(first)
    compile_to(second)
    compare_trees(first, second)
    verify_outputs(first, authority)
    verify_outputs(second, authority)

if arguments.check:
    tooling_test = HERE / "test-protocol-tooling.py"
    subprocess.run([sys.executable, str(tooling_test)], cwd=REPO, check=True)
    cli_boundary_test = HERE / "test-protocol-cli-boundaries.py"
    subprocess.run([sys.executable, str(cli_boundary_test)], cwd=REPO, check=True)
    slice_receipt_test = HERE / "test-slice-acceptance-receipt.py"
    subprocess.run([sys.executable, str(slice_receipt_test)], cwd=REPO, check=True)

if arguments.self_test:
    # G: run attack catalog (60/60) against production ProtocolSemanticValidator
    print()
    print("=" * 70)
    print("PRODUCTION ATTACKS: 60/60 — ProtocolSemanticValidator gate")
    print("=" * 70)
    attack_test = HERE / "test-compiler-attack-catalog.py"
    subprocess.run([sys.executable, str(attack_test)], cwd=REPO, check=True)
    print("PRODUCTION ATTACKS: 60/60 PASS — all attacks rejected with correct error codes")
    print()
    tooling_test = HERE / "test-protocol-tooling.py"
    subprocess.run([sys.executable, str(tooling_test)], cwd=REPO, check=True)
    cli_boundary_test = HERE / "test-protocol-cli-boundaries.py"
    subprocess.run([sys.executable, str(cli_boundary_test)], cwd=REPO, check=True)
    slice_receipt_test = HERE / "test-slice-acceptance-receipt.py"
    subprocess.run([sys.executable, str(slice_receipt_test)], cwd=REPO, check=True)

print("Gate A Protocol Authority and projection Schemas PASS; fingerprint pre-checked; double compile byte-identical; all requested tooling gates passed.")
