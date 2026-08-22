#!/usr/bin/env python3
"""Validate Gate A trust/build fixtures and their exact rejection targets."""

from __future__ import annotations

import json
import pathlib
import sys

from jsonschema import Draft202012Validator


HERE = pathlib.Path(__file__).resolve().parent
SCHEMA_ROOT = HERE.parents[3] / "schemas" / "resource-gateway-capability-studio"

VALID_SCHEMAS = {
    "valid-admission-trust-pin.json": "capability-studio-gate-a-admission-trust-pin-v1.schema.json",
    "valid-build-identity.json": "capability-studio-gate-a-build-identity-v1.schema.json",
    "valid-build-resource-manifest.json": "capability-studio-gate-a-build-resource-manifest-v1.schema.json",
    "valid-challenge-trust-pin.json": "capability-studio-gate-a-challenge-trust-pin-v1.schema.json",
    "valid-class-manifest.json": "capability-studio-gate-a-class-manifest-v1.schema.json",
    "valid-dependency-lock-manifest.json": "capability-studio-gate-a-dependency-lock-manifest-v1.schema.json",
    "valid-review-body.json": "capability-studio-review-body-v1.schema.json",
    "valid-reviewer-authority-envelope.json": "capability-studio-reviewer-authority-envelope-v1.schema.json",
    "valid-reviewer-trust-policy.json": "capability-studio-reviewer-trust-policy-v1.schema.json",
    "valid-revocation-snapshot.json": "capability-studio-reviewer-revocation-snapshot-v1.schema.json",
    "valid-schema-set-manifest.json": "capability-studio-gate-a-schema-set-manifest-v1.schema.json",
    "valid-source-manifest.json": "capability-studio-gate-a-source-manifest-v1.schema.json",
    "valid-tck-provider-identity.json": "capability-studio-gate-a-tck-provider-identity-v1.schema.json",
    "signed-review-count-guard/review-body.json": "capability-studio-review-body-v1.schema.json",
    "signed-review-count-guard/reviewer-authority-envelope.json": "capability-studio-reviewer-authority-envelope-v1.schema.json",
    "signed-review-count-guard/reviewer-trust-policy.json": "capability-studio-reviewer-trust-policy-v1.schema.json",
    "signed-review-count-guard/reviewer-revocation-snapshot.json": "capability-studio-reviewer-revocation-snapshot-v1.schema.json",
}

# Each structural negative is a complete valid document with exactly one field
# mutated. Matching one exact path and keyword prevents unrelated required-field
# failures from manufacturing a green negative test.
NEGATIVE_EXPECTATIONS = {
    "negative-admission-observation-kind.json": (
        "capability-studio-gate-a-admission-trust-pin-v1.schema.json",
        "$.observedOutputs.sealedGateResultRawFingerprint.kind",
        "const",
    ),
    "negative-build-role-profile.json": (
        "capability-studio-gate-a-build-identity-v1.schema.json",
        "$.replayProfileRawFingerprint",
        "type",
    ),
    "negative-challenge-kind-mismatch.json": (
        "capability-studio-gate-a-challenge-trust-pin-v1.schema.json",
        "$.expectedChallengeInputRootFingerprint.kind",
        "const",
    ),
    "negative-envelope-algorithm.json": (
        "capability-studio-reviewer-authority-envelope-v1.schema.json",
        "$.signatureAlgorithm",
        "const",
    ),
    "negative-policy-unknown-check.json": (
        "capability-studio-reviewer-trust-policy-v1.schema.json",
        "$.requiredCheckIds[1]",
        "const",
    ),
    "negative-provider-descriptor.json": (
        "capability-studio-gate-a-tck-provider-identity-v1.schema.json",
        "$.serviceDescriptorPath",
        "const",
    ),
    "negative-review-count-drift.json": (
        "capability-studio-review-body-v1.schema.json",
        "$.openP0",
        "const",
    ),
    "negative-revocation-duplicate-key.json": (
        "capability-studio-reviewer-revocation-snapshot-v1.schema.json",
        "$.revokedKeyIds",
        "uniqueItems",
    ),
    "negative-source-path.json": (
        "capability-studio-gate-a-source-manifest-v1.schema.json",
        "$.entries[0].relativeSourcePath",
        "pattern",
    ),
}


def load(path: pathlib.Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def json_path(parts) -> str:
    return "$" + "".join(
        f"[{part}]" if isinstance(part, int) else f".{part}" for part in parts
    )


def errors(fixture_name: str, schema_name: str):
    schema = load(SCHEMA_ROOT / schema_name)
    Draft202012Validator.check_schema(schema)
    return list(Draft202012Validator(schema).iter_errors(load(HERE / fixture_name)))


def main() -> int:
    failures: list[str] = []
    for fixture_name, schema_name in VALID_SCHEMAS.items():
        observed = errors(fixture_name, schema_name)
        if observed:
            failures.append(f"{fixture_name}: valid fixture produced {len(observed)} errors")

    matched: list[str] = []
    for fixture_name, (schema_name, expected_path, expected_keyword) in NEGATIVE_EXPECTATIONS.items():
        observed = errors(fixture_name, schema_name)
        actual = [(json_path(error.absolute_path), error.validator) for error in observed]
        expected = [(expected_path, expected_keyword)]
        if actual != expected:
            failures.append(f"{fixture_name}: expected {expected}, got {actual}")
        else:
            matched.append(f"{fixture_name} -> {expected_path} [{expected_keyword}]")

    if failures:
        print("Gate A trust/build fixture validation failed:", file=sys.stderr)
        print("\n".join(failures), file=sys.stderr)
        return 1

    print(
        f"Gate A trust/build fixtures valid: {len(VALID_SCHEMAS)} positive, "
        f"{len(NEGATIVE_EXPECTATIONS)} single-mutation negative"
    )
    print("\n".join(matched))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
