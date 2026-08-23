#!/usr/bin/env python3
"""Validate Gate A trust/build fixtures and their exact rejection targets."""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import stat
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


# --- Gate-checking helpers ---
_ACTIVE_DIR_NAME = ".fixture-publish-active"
_OWNER_SIZE_LIMIT = 16 * 1024  # 16 KiB max owner.json


def _validate_arg_root(path: pathlib.Path | None, label: str) -> None:
    """Validate an explicit root arg: must be absolute, chain-free of symlinks, and a directory."""
    if path is None:
        return
    if not path.is_absolute():
        raise SystemExit(f"{label} must be absolute: {path}")
    if not _path_chain_free_of_symlinks(path, label):
        raise SystemExit(f"{label} ({path}) has symlink in chain")
    if not path.is_dir():
        raise SystemExit(f"{label} is not an existing directory: {path}")


def _read_owner_json(owner_path: pathlib.Path, cli_token: str) -> None:
    """Bounded owner.json read via os.open + O_NOFOLLOW. Raises on rejection."""
    NOFOLLOW = getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(str(owner_path), os.O_RDONLY | NOFOLLOW)
    except OSError as e:
        raise SystemExit(f"transaction token rejected: owner.json not accessible [{e}]")
    try:
        st = os.fstat(fd)
        if not stat.S_ISREG(st.st_mode):
            raise SystemExit("transaction token rejected: owner.json is not a regular file")
        if st.st_size <= 0:
            raise SystemExit("transaction token rejected: owner.json is empty")
        if st.st_size > _OWNER_SIZE_LIMIT:
            raise SystemExit(f"transaction token rejected: owner.json exceeds {_OWNER_SIZE_LIMIT} bytes")
        content = b""
        while len(content) < st.st_size:
            chunk = os.read(fd, _OWNER_SIZE_LIMIT - len(content) + 1)
            if not chunk:
                break
            content += chunk
        if len(content) != st.st_size:
            raise SystemExit(f"transaction token rejected: owner.json size mismatch (expected {st.st_size}, got {len(content)})")
    finally:
        os.close(fd)
    try:
        owner = json.loads(content.decode("utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError):
        raise SystemExit("transaction token rejected: owner.json not valid JSON")
    if owner.get("token") != cli_token:
        raise SystemExit("transaction token rejected: owner.json token mismatch")


_GATE_CHECKED = False


def _check_gate(wire_root: pathlib.Path, cli_token: str | None,
                trust_root: pathlib.Path | None, schema_root: pathlib.Path | None) -> None:
    """Fail closed when active dir exists without valid transaction override."""
    global _GATE_CHECKED
    if _GATE_CHECKED:
        return
    active_root = wire_root / _ACTIVE_DIR_NAME
    if not active_root.exists():
        _GATE_CHECKED = True
        return  # normal operation, active absent

    # Active dir present: validate override (raises on failure, returns on success)
    if not cli_token:
        raise SystemExit("active gate: .fixture-publish-active exists; --transaction-token required")
    env_token = os.environ.get("GATE_A_FIXTURE_TRANSACTION_TOKEN", None)
    if env_token is None or cli_token != env_token:
        raise SystemExit("transaction token mismatch: CLI token present but environment mismatch")

    _read_owner_json(active_root / "owner.json", cli_token)

    explicit = [(trust_root, "trust_root"), (schema_root, "schema_root")]
    for root, label in explicit:
        if root is not None:
            _validate_arg_root(root, label)
    print(f"transaction override active: {sum(1 for r,_ in explicit if r is not None)} explicit roots validated")
    _GATE_CHECKED = True


def _path_chain_free_of_symlinks(path: pathlib.Path, label: str) -> bool:
    current: pathlib.Path | None = path
    while current is not None:
        try:
            if current.is_symlink():
                return False
        except OSError:
            return False
        parent = current.parent
        if parent == current:  # filesystem root
            break
        current = parent
    return True


def _run_gate(trust_root_arg: pathlib.Path | None, schema_root_arg: pathlib.Path | None,
              gate_root_arg: pathlib.Path | None, token: str | None) -> None:
    """Determine wire root and gate root; validate override if active present."""
    # --gate-root is the wire root (gate-a-wire-v1 directory); active path = wire_root / .fixture-publish-active
    if gate_root_arg is not None:
        wire_root = gate_root_arg
    elif trust_root_arg is not None:
        wire_root = trust_root_arg.parent
    else:
        wire_root = pathlib.Path(__file__).resolve().parents[1]

    _validate_arg_root(wire_root, "--gate-root")
    _validate_arg_root(trust_root_arg, "--trust-root")
    _validate_arg_root(schema_root_arg, "--schema-root")

    _check_gate(wire_root, token, trust_root_arg, schema_root_arg)


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
    global HERE, SCHEMA_ROOT
    parser = argparse.ArgumentParser(description="Gate A trust/build fixture validator")
    parser.add_argument("--trust-root", type=pathlib.Path, default=None)
    parser.add_argument("--schema-root", type=pathlib.Path, default=None)
    parser.add_argument("--gate-root", type=pathlib.Path, default=None)
    parser.add_argument("--transaction-token", type=str, default=None)
    args = parser.parse_args()

    # Validate root args and check gate BEFORE reading any fixtures
    _run_gate(args.trust_root, args.schema_root, args.gate_root, args.transaction_token)

    # Apply configured roots to this module's fixture reads
    if args.trust_root is not None:
        HERE = args.trust_root
    if args.schema_root is not None:
        SCHEMA_ROOT = args.schema_root

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
