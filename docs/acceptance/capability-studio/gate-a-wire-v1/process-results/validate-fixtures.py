#!/usr/bin/env python3
"""Validate Gate A process/result fixtures with Draft 2020-12."""

from __future__ import annotations

import json
import pathlib
import sys
from datetime import datetime
from typing import Any

from jsonschema import Draft202012Validator
from referencing import Registry, Resource


HERE = pathlib.Path(__file__).resolve().parent
SCHEMA_ROOT = HERE.parents[3] / "schemas" / "resource-gateway-capability-studio"
NEGATIVE_EXPECTATIONS = HERE / "negative-fixture-expectations.json"

POSITIVE_SCHEMAS = {
    "valid-candidate-challenge-request.json": "capability-studio-gate-a-candidate-challenge-request-v1.schema.json",
    "valid-candidate-challenge-response.json": "capability-studio-gate-a-candidate-challenge-response-v1.schema.json",
    "valid-candidate-challenge-response-legacy.json": "capability-studio-gate-a-candidate-challenge-response-v1.schema.json",
    "valid-candidate-challenge-response-legacy-missing-store.json": "capability-studio-gate-a-candidate-challenge-response-v1.schema.json",
    "valid-challenge-sandbox-profile.json": "capability-studio-gate-a-challenge-sandbox-profile-v1.schema.json",
    "valid-process-command.json": "capability-studio-gate-a-process-command-record-v1.schema.json",
    "valid-a1-invocation.json": "capability-studio-gate-a-a1-invocation-record-v1.schema.json",
    "valid-a1-bootstrap-response.json": "capability-studio-gate-a-a1-bootstrap-response-v1.schema.json",
    "valid-process-transcript.json": "capability-studio-gate-a-process-transcript-v1.schema.json",
    "valid-process-transcript-a1-timeout.json": "capability-studio-gate-a-process-transcript-v1.schema.json",
    "valid-process-transcript-cancelled.json": "capability-studio-gate-a-process-transcript-v1.schema.json",
    "valid-process-transcript-failed.json": "capability-studio-gate-a-process-transcript-v1.schema.json",
    "valid-process-transcript-unavailable.json": "capability-studio-gate-a-process-transcript-v1.schema.json",
    "valid-process-transcript-a2.json": "capability-studio-gate-a-process-transcript-v1.schema.json",
    "valid-harness-invocation.json": "capability-studio-gate-a-harness-invocation-record-v1.schema.json",
    "valid-harness-process-transcript.json": "capability-studio-gate-a-harness-process-transcript-v1.schema.json",
    "valid-replay-profile.json": "capability-studio-gate-a-replay-profile-v1.schema.json",
    "valid-harness-profile.json": "capability-studio-gate-a-harness-profile-v1.schema.json",
    "valid-admission-profile.json": "capability-studio-gate-a-admission-profile-v1.schema.json",
    "valid-tck.json": "capability-studio-gate-a-tck-v1.schema.json",
    "valid-role-registry.json": "capability-studio-gate-a-role-registry-v1.schema.json",
    "valid-candidate-replay-result.json": "capability-studio-gate-a-candidate-replay-result-v1.schema.json",
    "valid-replay-verification-result.json": "capability-studio-gate-a-replay-verification-result-v1.schema.json",
    "valid-replay-verification-result-invalid.json": "capability-studio-gate-a-replay-verification-result-v1.schema.json",
    "valid-replay-verification-result-unavailable.json": "capability-studio-gate-a-replay-verification-result-v1.schema.json",
    "valid-replay-proof-envelope.json": "capability-studio-gate-a-replay-proof-envelope-v1.schema.json",
    "valid-replay-proof-envelope-unavailable.json": "capability-studio-gate-a-replay-proof-envelope-v1.schema.json",
    "valid-admission-proof-envelope.json": "capability-studio-gate-a-admission-proof-envelope-v1.schema.json",
    "valid-independent-verification-result.json": "capability-studio-gate-a-independent-verification-result-v1.schema.json",
    "valid-admission-verification-result.json": "capability-studio-gate-a-admission-verification-result-v1.schema.json",
    "valid-admission-verification-result-open.json": "capability-studio-gate-a-admission-verification-result-v1.schema.json",
    "valid-admission-verification-result-fail.json": "capability-studio-gate-a-admission-verification-result-v1.schema.json",
    "valid-admission-verification-result-unavailable.json": "capability-studio-gate-a-admission-verification-result-v1.schema.json",
}


def load_json(path: pathlib.Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def load_registry() -> Registry:
    registry = Registry()
    for path in sorted(SCHEMA_ROOT.glob("capability-studio-gate-a-*.schema.json")):
        schema = load_json(path)
        Draft202012Validator.check_schema(schema)
        registry = registry.with_resource(schema["$id"], Resource.from_contents(schema))
    return registry


def validator(schema_name: str, registry: Registry) -> Draft202012Validator:
    schema = load_json(SCHEMA_ROOT / schema_name)
    return Draft202012Validator(schema, registry=registry)


def diff_paths(expected: Any, mutated: Any, path: str = "$") -> list[str]:
    if type(expected) is not type(mutated):
        return [path]
    if isinstance(expected, dict):
        paths: list[str] = []
        for key in sorted(expected.keys() | mutated.keys()):
            child = f"{path}.{key}"
            if key not in expected or key not in mutated:
                paths.append(child)
            else:
                paths.extend(diff_paths(expected[key], mutated[key], child))
        return paths
    if isinstance(expected, list):
        if len(expected) != len(mutated):
            return [path]
        paths = []
        for index, (left, right) in enumerate(zip(expected, mutated, strict=True)):
            paths.extend(diff_paths(left, right, f"{path}[{index}]"))
        return paths
    return [] if expected == mutated else [path]


def is_within(path: str, mutation_path: str) -> bool:
    return path == mutation_path or path.startswith(f"{mutation_path}.") or path.startswith(f"{mutation_path}[")


def validate_positive(name: str, schema_name: str, registry: Registry) -> list[str]:
    document = load_json(HERE / name)
    errors = list(validator(schema_name, registry).iter_errors(document))
    failures = [f"{name}: {error.json_path} [{error.validator}] {error.message}" for error in errors]
    failures.extend(f"{name}: semantic {code}" for code in semantic_errors(name, document, schema_name))
    return failures


def parse_timestamp(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def local_ref(uri: str) -> pathlib.Path | None:
    candidate = (HERE / uri).resolve()
    try:
        candidate.relative_to(HERE.resolve())
    except ValueError:
        return None
    return candidate if candidate.is_file() else None


def semantic_errors(name: str, document: Any, schema_name: str) -> list[str]:
    errors: list[str] = []
    if schema_name == "capability-studio-gate-a-process-transcript-v1.schema.json":
        if parse_timestamp(document["startedAt"]) > parse_timestamp(document["endedAt"]):
            errors.append("PROCESS_STARTED_AFTER_ENDED")
    if schema_name == "capability-studio-gate-a-admission-proof-envelope-v1.schema.json":
        result_path = local_ref(document["admissionResultRef"]["uri"])
        transcript_path = local_ref(document["admissionProcessTranscriptRef"]["uri"])
        if result_path is None or transcript_path is None:
            errors.append("A2_REFERENCED_MATERIAL_UNAVAILABLE")
            return errors
        result = load_json(result_path)
        transcript = load_json(transcript_path)
        if result.get("messageVersion") != "resource-gateway.capability-studio.gate-a.admission-verification-result.v1":
            errors.append("A2_RESULT_KIND_MISMATCH")
        if transcript.get("messageVersion") != "resource-gateway.capability-studio.gate-a.process-transcript.v1":
            errors.append("A2_PROCESS_TRANSCRIPT_KIND_MISMATCH")
        if transcript.get("processState") != "COMPLETED" or transcript.get("exitCode") != 0:
            errors.append("A2_PROCESS_NOT_COMPLETED")
            return errors
        if document["observedProcessState"] != transcript.get("processState") or document["observedExitCode"] != transcript.get("exitCode"):
            errors.append("A2_PROCESS_OUTCOME_DRIFT")
        if document["admissionResultConclusion"] != result.get("conclusion") or document["observedConclusion"] != result.get("conclusion"):
            errors.append("A2_CONCLUSION_DRIFT")
        if document["expectedAdmissionCodeSource"] != document["observedAdmissionCodeSource"]:
            errors.append("A2_EXPECTED_OBSERVED_CODESOURCE_DRIFT")
        if document["observedAdmissionCodeSource"] != transcript.get("codeSource"):
            errors.append("A2_CODESOURCE_TRANSCRIPT_DRIFT")
    return errors


def validate_negative(name: str, expectation: dict[str, str], registry: Registry) -> list[str]:
    failures: list[str] = []
    base = load_json(HERE / expectation["base"])
    mutated = load_json(HERE / name)
    changes = diff_paths(base, mutated)
    mutation_path = expectation["mutationPath"]
    if not changes:
        failures.append(f"{name}: is identical to {expectation['base']}")
    elif any(not is_within(path, mutation_path) for path in changes):
        failures.append(f"{name}: changes {changes} escape declared mutation {mutation_path}")

    base_errors = list(validator(expectation["schema"], registry).iter_errors(base))
    if base_errors:
        failures.append(f"{name}: base fixture {expectation['base']} is not valid")

    errors = list(validator(expectation["schema"], registry).iter_errors(mutated))
    if expectation.get("validationMode") == "semantic":
        if errors:
            rendered = [f"{error.json_path} [{error.validator}] {error.message}" for error in errors]
            failures.append(f"{name}: semantic negative must remain Schema-valid: {rendered}")
        observed = semantic_errors(name, mutated, expectation["schema"])
        expected = expectation["expectedSemanticCode"]
        if observed != [expected]:
            failures.append(f"{name}: expected semantic [{expected}], got {observed}")
        else:
            print(f"negative matched: {name} -> semantic {expected}")
        return failures
    if len(errors) != 1:
        rendered = [f"{error.json_path} [{error.validator}] {error.message}" for error in errors]
        failures.append(f"{name}: expected exactly one validation error, got {len(errors)}: {rendered}")
        return failures

    error = errors[0]
    actual_path = str(error.json_path)
    if error.validator != expectation["expectedKeyword"]:
        failures.append(f"{name}: expected keyword {expectation['expectedKeyword']}, got {error.validator}")
    if actual_path != expectation["expectedPath"]:
        failures.append(f"{name}: expected path {expectation['expectedPath']}, got {actual_path}")
    if expectation["expectedMessageContains"] not in error.message:
        failures.append(
            f"{name}: error message does not contain {expectation['expectedMessageContains']!r}: {error.message}"
        )
    if not failures:
        print(f"negative matched: {name} -> {error.validator} {actual_path}")
    return failures


def main() -> int:
    registry = load_registry()
    expectations = load_json(NEGATIVE_EXPECTATIONS)
    failures: list[str] = []

    positive_names = {path.name for path in HERE.glob("valid-*.json")}
    if positive_names != POSITIVE_SCHEMAS.keys():
        failures.append(
            f"positive mapping drift: files={sorted(positive_names)} mapping={sorted(POSITIVE_SCHEMAS)}"
        )
    negative_names = {path.name for path in HERE.glob("invalid-*.json")}
    if negative_names != expectations.keys():
        failures.append(
            f"negative mapping drift: files={sorted(negative_names)} mapping={sorted(expectations)}"
        )

    for name, schema_name in POSITIVE_SCHEMAS.items():
        failures.extend(validate_positive(name, schema_name, registry))
    for name, expectation in expectations.items():
        failures.extend(validate_negative(name, expectation, registry))

    if failures:
        print("Gate A fixture validation failed:", file=sys.stderr)
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(f"Gate A fixtures valid: {len(POSITIVE_SCHEMAS)} positive, {len(expectations)} negative")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
