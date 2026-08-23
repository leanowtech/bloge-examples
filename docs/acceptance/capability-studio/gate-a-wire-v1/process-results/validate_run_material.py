#!/usr/bin/env python3
"""Recompute Gate A1 evidence closure from exact run-material bytes."""

from __future__ import annotations

import copy
import errno
import hashlib
import io
import json
import math
import os
import pathlib
import re
import stat
import sys
import tempfile
import zipfile
from contextlib import contextmanager
from dataclasses import dataclass
from typing import Any

from jsonschema import Draft202012Validator
from referencing import Registry, Resource


HERE = pathlib.Path(__file__).resolve().parent
WIRE_ROOT = HERE.parent
TRUST_BUILD = WIRE_ROOT / "trust-build"
RUN_MATERIAL = HERE / "run-material"
CHALLENGE_INPUT = HERE / "challenge-input"
RUN_MATERIAL_ATTACKS = HERE / "run-material-attack-vectors.json"
SCHEMA_ROOT = HERE.parents[3] / "schemas" / "resource-gateway-capability-studio"
PROTOCOL_COMPILER_ROOT = WIRE_ROOT / "protocol-compiler"
PROTOCOL_SOURCE = PROTOCOL_COMPILER_ROOT / "gate-a-protocol-authority-v1.json"
PROTOCOL_COMPILED = PROTOCOL_COMPILER_ROOT / "compiled" / "launch-contract-v1.json"
PROTOCOL_MANIFEST = PROTOCOL_COMPILER_ROOT / "compiled" / "protocol-compilation-manifest-v1.json"
PROTOCOL_REPLAY_COMPILED = PROTOCOL_COMPILER_ROOT / "compiled" / "replay-vector-registry-v1.json"
PROTOCOL_AUTHORITY_SCHEMA = SCHEMA_ROOT / "capability-studio-gate-a-protocol-authority-v1.schema.json"
DOCS_ROOT = HERE.parents[3]

SCHEMA_BY_MESSAGE_VERSION = {
    "resource-gateway.capability-studio.gate-a.a1-bootstrap-response.v1": "capability-studio-gate-a-a1-bootstrap-response-v1.schema.json",
    "resource-gateway.capability-studio.gate-a.a1-invocation-record.v1": "capability-studio-gate-a-a1-invocation-record-v1.schema.json",
    "resource-gateway.capability-studio.gate-a.abnormal-attempt.v1": "capability-studio-gate-a-abnormal-attempt-v1.schema.json",
    "resource-gateway.capability-studio.gate-a.candidate-challenge-request.v1": "capability-studio-gate-a-candidate-challenge-request-v1.schema.json",
    "resource-gateway.capability-studio.gate-a.candidate-challenge-response.v1": "capability-studio-gate-a-candidate-challenge-response-v1.schema.json",
    "resource-gateway.capability-studio.gate-a.candidate-replay-result.v1": "capability-studio-gate-a-candidate-replay-result-v1.schema.json",
    "resource-gateway.capability-studio.gate-a.harness-invocation-record.v1": "capability-studio-gate-a-harness-invocation-record-v1.schema.json",
    "resource-gateway.capability-studio.gate-a.harness-process-transcript.v1": "capability-studio-gate-a-harness-process-transcript-v1.schema.json",
    "resource-gateway.capability-studio.gate-a.independent-verification-result.v1": "capability-studio-gate-a-independent-verification-result-v1.schema.json",
    "resource-gateway.capability-studio.gate-a.process-command-record.v1": "capability-studio-gate-a-process-command-record-v1.schema.json",
    "resource-gateway.capability-studio.gate-a.process-observation.v1": "capability-studio-gate-a-process-observation-v1.schema.json",
    "resource-gateway.capability-studio.gate-a.process-transcript.v1": "capability-studio-gate-a-process-transcript-v1.schema.json",
    "resource-gateway.capability-studio.gate-a.provider-materialization-observation.v1": "capability-studio-gate-a-provider-materialization-observation-v1.schema.json",
    "resource-gateway.capability-studio.gate-a.replay-proof-envelope.v1": "capability-studio-gate-a-replay-proof-envelope-v1.schema.json",
    "resource-gateway.capability-studio.gate-a.replay-verification-result.v1": "capability-studio-gate-a-replay-verification-result-v1.schema.json",
}
_SCHEMA_REGISTRY: Registry | None = None
_SCHEMA_VALIDATORS: dict[str, Draft202012Validator] = {}
_LAUNCH_CONTRACTS: dict[str, dict[str, Any]] | None = None
_REPLAY_VECTOR_REGISTRY: dict[str, Any] | None = None
_MATERIAL_LIMITS: dict[str, int] | None = None
_ACTIVE_READ_BUDGET: int | None = None

DOMAINS = {
    "command": "RG-CS-GATE-A-PROCESS-COMMAND-v1",
    "request": "RG-CS-GATE-A-CANDIDATE-CHALLENGE-REQUEST-v1",
    "response": "RG-CS-GATE-A-CANDIDATE-CHALLENGE-RESPONSE-v1",
    "operation": "RG-CS-GATE-A-OPERATION-RESULT-v1",
    "a0": "RG-CS-GATE-A0-RESULT-v1",
    "replay": "RG-CS-GATE-A1-REPLAY-RESULT-v1",
    "independent": "RG-CS-GATE-A1-REPORT-v1",
    "independent_envelope": "RG-CS-GATE-A1-INDEPENDENT-PROOF-ENVELOPE-v1",
    "transcript": "RG-CS-GATE-A-PROCESS-TRANSCRIPT-v1",
    "harness_transcript": "RG-CS-GATE-A-HARNESS-PROCESS-TRANSCRIPT-v1",
    "invocation": "RG-CS-GATE-A-A1-INVOCATION-v1",
    "harness_invocation": "RG-CS-GATE-A-HARNESS-INVOCATION-v1",
    "bootstrap": "RG-CS-GATE-A-A1-BOOTSTRAP-RESPONSE-v1",
    "envelope": "RG-CS-GATE-A1-PROOF-ENVELOPE-v1",
    "tree": "RG-CS-GATE-A-RUN-MATERIAL-ROOT-v1",
    "challenge_input_tree": "RG-CS-GATE-A-CHALLENGE-INPUT-ROOT-v1",
    "provider_materialization": "RG-CS-GATE-A-PROVIDER-MATERIALIZATION-OBSERVATION-v1",
    "process_aggregate": "RG-CS-GATE-A-PROCESS-MATERIAL-AGGREGATE-v1",
    "evidence_aggregate": "RG-CS-GATE-A-TEST-EVIDENCE-AGGREGATE-v1",
    "abnormal_attempt": "RG-CS-GATE-A-ABNORMAL-ATTEMPT-v1",
}


class MaterialError(AssertionError):
    def __init__(self, code: str, detail: str = "") -> None:
        super().__init__(f"{code}: {detail}" if detail else code)
        self.code = code


def require(condition: bool, code: str, detail: str = "") -> None:
    if not condition:
        raise MaterialError(code, detail)


def read_json(path: pathlib.Path) -> Any:
    value, _ = stable_file_read(path)
    return json.loads(value, object_pairs_hook=_reject_duplicate_pairs)


def _reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        require(key not in result, "A1_JSON_DUPLICATE_MEMBER", key)
        result[key] = value
    return result


def schema_registry() -> Registry:
    global _SCHEMA_REGISTRY
    if _SCHEMA_REGISTRY is None:
        registry = Registry()
        for path in sorted(SCHEMA_ROOT.glob("capability-studio-gate-a-*.schema.json")):
            schema_bytes, _ = stable_file_read(path)
            schema = json.loads(schema_bytes, object_pairs_hook=_reject_duplicate_pairs)
            Draft202012Validator.check_schema(schema)
            registry = registry.with_resource(schema["$id"], Resource.from_contents(schema))
        _SCHEMA_REGISTRY = registry
    return _SCHEMA_REGISTRY


def validate_typed_document(document: dict[str, Any], source: pathlib.Path) -> None:
    message_version = document.get("messageVersion")
    require(message_version is not None, "A1_MISSING_MESSAGE_VERSION", str(source))
    require(isinstance(message_version, str), "A1_UNKNOWN_MESSAGE_VERSION", repr(message_version))
    schema_name = SCHEMA_BY_MESSAGE_VERSION.get(message_version)
    require(schema_name is not None, "A1_UNKNOWN_MESSAGE_VERSION", str(message_version))
    validator = _SCHEMA_VALIDATORS.get(message_version)
    if validator is None:
        schema_bytes, _ = stable_file_read(SCHEMA_ROOT / schema_name)
        schema = json.loads(schema_bytes, object_pairs_hook=_reject_duplicate_pairs)
        validator = Draft202012Validator(schema, registry=schema_registry())
        _SCHEMA_VALIDATORS[message_version] = validator
    errors = sorted(validator.iter_errors(document), key=lambda error: (list(error.absolute_path), error.message))
    if errors:
        first = errors[0]
        location = "$" + "".join(f"[{part}]" if isinstance(part, int) else f".{part}" for part in first.absolute_path)
        raise MaterialError("A1_SCHEMA_VALIDATION_FAILED", f"{source}: {location} [{first.validator}] {first.message}")


def _utf16_sort_key(value: str) -> bytes:
    return value.encode("utf-16-be", "surrogatepass")


def canonicalize(value: Any) -> str:
    if value is None or isinstance(value, bool):
        return json.dumps(value, separators=(",", ":"))
    if isinstance(value, int) and not isinstance(value, bool):
        return str(value)
    if isinstance(value, float):
        require(math.isfinite(value), "A1_CANONICAL_NON_FINITE")
        if value == 0:
            return "0"
        if value.is_integer() and abs(value) < 1e21:
            return str(int(value))
        rendered = json.dumps(value, allow_nan=False, separators=(",", ":"))
        if "e" in rendered.lower():
            mantissa, exponent = rendered.lower().split("e")
            exponent_value = int(exponent)
            rendered = mantissa + "e" + ("+" if exponent_value >= 0 else "") + str(exponent_value)
        return rendered
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    if isinstance(value, list):
        return "[" + ",".join(canonicalize(item) for item in value) + "]"
    if isinstance(value, dict):
        fields = [
            canonicalize(str(key)) + ":" + canonicalize(value[key])
            for key in sorted(value, key=_utf16_sort_key)
        ]
        return "{" + ",".join(fields) + "}"
    raise MaterialError("A1_CANONICAL_UNSUPPORTED_TYPE", repr(type(value)))


def canonical_bytes(value: Any) -> bytes:
    return canonicalize(value).encode("utf-8")


def digest_bytes(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def typed_fingerprint(kind: str, value: str) -> dict[str, str]:
    return {"kind": kind, "algorithm": "SHA-256", "value": value}


def raw_fingerprint(value: bytes) -> dict[str, str]:
    return typed_fingerprint("RAW_BYTES", digest_bytes(value))


def document_fingerprint(document: dict[str, Any], field: str | None, domain: str) -> dict[str, str]:
    candidate = copy.deepcopy(document)
    if field is not None:
        require(field in candidate, "A1_SELF_FINGERPRINT_FIELD_MISSING", field)
        candidate[field] = None
    payload = domain.encode("ascii") + b"\0" + canonical_bytes(candidate)
    return typed_fingerprint("CANONICAL_DOCUMENT", digest_bytes(payload))


def commitment(value: Any, domain: str, kind: str) -> dict[str, str]:
    payload = domain.encode("ascii") + b"\0" + canonical_bytes(value)
    return typed_fingerprint(kind, digest_bytes(payload))


def verify_protocol_projection_bundle(
    projection_id: str,
    source_bytes: bytes,
    projection_bytes: bytes,
    manifest_bytes: bytes,
    pin_bytes: bytes,
) -> dict[str, Any]:
    source = json.loads(source_bytes, object_pairs_hook=_reject_duplicate_pairs)
    projection = json.loads(projection_bytes, object_pairs_hook=_reject_duplicate_pairs)
    manifest = json.loads(manifest_bytes, object_pairs_hook=_reject_duplicate_pairs)
    pin = json.loads(pin_bytes, object_pairs_hook=_reject_duplicate_pairs)
    schema_bytes, _ = stable_file_read(PROTOCOL_AUTHORITY_SCHEMA)
    schema = json.loads(schema_bytes, object_pairs_hook=_reject_duplicate_pairs)
    errors = sorted(
        Draft202012Validator(schema, registry=schema_registry()).iter_errors(source),
        key=lambda error: (list(error.absolute_path), error.message),
    )
    require(not errors, "A1_PROTOCOL_AUTHORITY_SCHEMA_INVALID", errors[0].message if errors else "")
    source_raw = digest_bytes(source_bytes)
    require(
        pin["expectedProtocolAuthorityRawFingerprint"] == raw_fingerprint(source_bytes),
        "A1_PROTOCOL_AUTHORITY_PIN_DRIFT",
    )
    require(projection["projectionId"] == projection_id, "A1_PROTOCOL_PROJECTION_ID_DRIFT")
    require(projection["authorityRevision"] == source["revision"], "A1_LAUNCH_PROJECTION_REVISION_DRIFT")
    require(projection["sourceRawFingerprint"] == source_raw, "A1_LAUNCH_SOURCE_FINGERPRINT_DRIFT")
    require(manifest["sourceRawFingerprint"] == source_raw, "A1_LAUNCH_MANIFEST_SOURCE_DRIFT")
    require(manifest["authorityRevision"] == source["revision"], "A1_LAUNCH_MANIFEST_REVISION_DRIFT")
    manifest_entry = next(
        (item for item in manifest["projections"] if item["projectionId"] == projection_id),
        None,
    )
    require(manifest_entry is not None, "A1_PROTOCOL_MANIFEST_PROJECTION_MISSING", projection_id)
    expected_projection_name = {
        "LAUNCH_CONTRACT": PROTOCOL_COMPILED.name,
        "REPLAY_VECTOR_REGISTRY": PROTOCOL_REPLAY_COMPILED.name,
    }[projection_id]
    require(manifest_entry["path"] == expected_projection_name, "A1_PROTOCOL_MANIFEST_PATH_DRIFT", projection_id)
    require(manifest_entry["rawFingerprint"] == digest_bytes(projection_bytes), "A1_PROTOCOL_PROJECTION_RAW_FINGERPRINT_DRIFT", projection_id)
    expected_content = source["launchContracts"] if projection_id == "LAUNCH_CONTRACT" else {
        "inputSets": source["inputSets"],
        "replayVectors": source["replayVectors"],
    }
    require(projection["content"] == expected_content, "A1_PROTOCOL_PROJECTION_CONTENT_DRIFT", projection_id)
    return projection["content"]


def protocol_projection(projection_id: str) -> dict[str, Any]:
    projection_path = PROTOCOL_COMPILED if projection_id == "LAUNCH_CONTRACT" else PROTOCOL_REPLAY_COMPILED
    source_bytes, _ = stable_file_read(PROTOCOL_SOURCE)
    projection_bytes, _ = stable_file_read(projection_path)
    manifest_bytes, _ = stable_file_read(PROTOCOL_MANIFEST)
    pin_bytes, _ = stable_file_read(TRUST_BUILD / "valid-challenge-trust-pin.json")
    return verify_protocol_projection_bundle(projection_id, source_bytes, projection_bytes, manifest_bytes, pin_bytes)


def protocol_launch_contracts() -> dict[str, dict[str, Any]]:
    """Load and authenticate the compiled launch projection before using it."""
    global _LAUNCH_CONTRACTS
    if _LAUNCH_CONTRACTS is not None:
        return _LAUNCH_CONTRACTS
    content = protocol_projection("LAUNCH_CONTRACT")
    _LAUNCH_CONTRACTS = {item["launchKind"]: item for item in content}
    require(len(_LAUNCH_CONTRACTS) == len(content), "A1_LAUNCH_KIND_REUSED")
    return _LAUNCH_CONTRACTS


def protocol_replay_vector_registry() -> dict[str, Any]:
    global _REPLAY_VECTOR_REGISTRY
    if _REPLAY_VECTOR_REGISTRY is None:
        _REPLAY_VECTOR_REGISTRY = protocol_projection("REPLAY_VECTOR_REGISTRY")
        verify_replay_vector_registry_content(_REPLAY_VECTOR_REGISTRY)
    return _REPLAY_VECTOR_REGISTRY


def verify_replay_vector_registry_content(content: dict[str, Any]) -> None:
    input_sets = content["inputSets"]
    vectors = content["replayVectors"]
    input_set_ids = [item["inputSetId"] for item in input_sets]
    require(len(input_set_ids) == len(set(input_set_ids)), "A1_REPLAY_VECTOR_REGISTRY_DRIFT")
    require([item["ordinal"] for item in vectors] == list(range(1, 10)), "A1_REPLAY_VECTOR_REGISTRY_DRIFT")
    require(len({item["testId"] for item in vectors}) == len(vectors), "A1_REPLAY_VECTOR_REGISTRY_DRIFT")
    require(all(item["inputSetId"] in input_set_ids for item in vectors), "A1_REPLAY_VECTOR_REGISTRY_DRIFT")
    require(all(item["mutationRecipe"]["variantId"] == item["testId"] for item in vectors), "A1_REPLAY_VECTOR_REGISTRY_DRIFT")


def replay_vector_for(authority: dict[str, Any], test_id: str) -> dict[str, Any]:
    vector = next((item for item in authority["replayRegistry"]["replayVectors"] if item["testId"] == test_id), None)
    require(vector is not None, "A1_REPLAY_VECTOR_UNKNOWN", test_id)
    return vector


def replay_input_set_for(authority: dict[str, Any], input_set_id: str) -> dict[str, Any]:
    input_set = next((item for item in authority["replayRegistry"]["inputSets"] if item["inputSetId"] == input_set_id), None)
    require(input_set is not None, "A1_REPLAY_INPUT_SET_UNKNOWN", input_set_id)
    return input_set


def verify_tck_normal_projection(authority: dict[str, Any]) -> None:
    normal_tck = [test for test in read_json(HERE / "valid-tck.json")["tests"] if test["execution"] == "NORMAL_CHILD"]
    vectors = authority["replayRegistry"]["replayVectors"]
    require(len(normal_tck) == len(vectors) == 9, "A1_TCK_NORMAL_COUNT_DRIFT")
    require([test["testId"] for test in normal_tck] == [vector["testId"] for vector in vectors], "A1_TCK_NORMAL_ORDER_DRIFT")
    for test, vector in zip(normal_tck, vectors, strict=True):
        require(test["expectedTerminal"] == vector["expectedTerminal"], "A1_TCK_NORMAL_TERMINAL_DRIFT", test["testId"])
        require(test["expectedExitCode"] == vector["expectedExitCode"], "A1_TCK_NORMAL_EXIT_DRIFT", test["testId"])
        require(test["expectedReasonCode"] == vector["expectedReasonCode"], "A1_TCK_NORMAL_REASON_DRIFT", test["testId"])


def _absolute_path(path: pathlib.Path) -> pathlib.Path:
    """Normalize only lexical components; never resolve symlinks in material paths."""
    return pathlib.Path(os.path.abspath(os.fspath(path)))


def _anchor_for(path: pathlib.Path) -> tuple[pathlib.Path, tuple[str, ...]]:
    absolute = _absolute_path(path)
    anchors = sorted((HERE, WIRE_ROOT, DOCS_ROOT), key=lambda item: len(item.parts), reverse=True)
    for anchor in anchors:
        anchor = _absolute_path(anchor)
        try:
            relative = absolute.relative_to(anchor)
        except ValueError:
            continue
        parts = relative.parts if str(relative) != "." else ()
        require(all(part not in ("", ".", "..") for part in parts), "A1_MATERIAL_PATH_ESCAPE", str(path))
        return anchor, parts
    raise MaterialError("A1_MATERIAL_PATH_ESCAPE", str(path))


def _open_anchor_fd(anchor: pathlib.Path) -> int:
    flags = os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0)
    try:
        descriptor = os.open(anchor, flags)
    except OSError as error:
        raise MaterialError("A1_TREE_SCOPE_MISSING", str(anchor)) from error
    metadata = os.fstat(descriptor)
    require(stat.S_ISDIR(metadata.st_mode), "A1_TREE_SCOPE_MISSING", str(anchor))
    return descriptor


def _open_componentwise(path: pathlib.Path, final_flags: int) -> int:
    """Open a path below a held anchor FD, refusing symlink components and `..`."""
    anchor, parts = _anchor_for(path)
    current = _open_anchor_fd(anchor)
    try:
        if not parts:
            if final_flags & os.O_DIRECTORY:
                return current
            raise MaterialError("A1_REFERENCED_MATERIAL_UNAVAILABLE", str(path))
        directory_flags = os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0)
        for index, part in enumerate(parts):
            flags = final_flags if index == len(parts) - 1 else directory_flags
            try:
                child = os.open(part, flags, dir_fd=current)
            except OSError as error:
                if error.errno in (errno.ELOOP, errno.ENOTDIR):
                    try:
                        component_metadata = os.stat(part, dir_fd=current, follow_symlinks=False)
                    except OSError:
                        component_metadata = None
                    if component_metadata is not None and stat.S_ISLNK(component_metadata.st_mode):
                        raise MaterialError("A1_MATERIAL_SYMLINK_REJECTED", str(path)) from error
                raise MaterialError("A1_REFERENCED_MATERIAL_UNAVAILABLE", str(path)) from error
            os.close(current)
            current = child
        return current
    except Exception:
        try:
            os.close(current)
        except OSError:
            pass
        raise


def _stat_child(directory_fd: int, name: str, unavailable_code: str) -> os.stat_result:
    try:
        return os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
    except OSError as error:
        raise MaterialError(unavailable_code, name) from error


def _path_relative_to_anchor(path: pathlib.Path, anchor: pathlib.Path) -> tuple[str, ...]:
    absolute = _absolute_path(path)
    try:
        relative = absolute.relative_to(_absolute_path(anchor))
    except ValueError as error:
        raise MaterialError("A1_MATERIAL_PATH_ESCAPE", str(path)) from error
    return relative.parts if str(relative) != "." else ()


def _safe_existing_path(relative: str, base: pathlib.Path, unavailable_code: str) -> pathlib.Path:
    require(not relative.startswith("/"), "A1_ABSOLUTE_MATERIAL_URI", relative)
    parts = pathlib.PurePosixPath(relative).parts
    require(parts and all(part not in ("", ".", "..") for part in parts), "A1_MATERIAL_PATH_ESCAPE", relative)
    base = _absolute_path(base)
    path = base.joinpath(*parts)
    try:
        descriptor = _open_componentwise(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0))
    except MaterialError as error:
        if error.code == "A1_REFERENCED_MATERIAL_UNAVAILABLE":
            raise MaterialError(unavailable_code, relative) from error
        raise
    os.close(descriptor)
    return path


def resolve_uri(uri: str, base: pathlib.Path = HERE) -> pathlib.Path:
    path = _safe_existing_path(uri, base, "A1_REFERENCED_MATERIAL_UNAVAILABLE")
    descriptor = _open_componentwise(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0))
    try:
        metadata = os.fstat(descriptor)
    finally:
        os.close(descriptor)
    require(stat.S_ISREG(metadata.st_mode), "A1_REFERENCED_MATERIAL_UNAVAILABLE", uri)
    return path


def _authority_fixture_limits() -> dict[str, int]:
    global _MATERIAL_LIMITS
    if _MATERIAL_LIMITS is not None:
        return _MATERIAL_LIMITS
    # The Authority's artifact limit is the only bootstrap bound used to read the Authority itself.
    authority_bytes, _ = stable_file_read(PROTOCOL_SOURCE, budget=16 * 1024 * 1024)
    authority = json.loads(authority_bytes, object_pairs_hook=_reject_duplicate_pairs)
    candidate = next(item for item in authority["roleContracts"] if item["role"] == "IMPLEMENTATION_CANDIDATE")
    limits = candidate["blackBoxFixtureContract"]["fixtureLimits"]
    required = ("maxBindingManifestBytes", "maxFileBytes", "maxTreeEntries", "maxTreeEntryBytes", "maxTreeTotalBytes")
    require(all(isinstance(limits.get(key), int) and limits[key] > 0 for key in required), "A1_MATERIAL_LIMITS_INVALID")
    _MATERIAL_LIMITS = {key: limits[key] for key in required}
    return _MATERIAL_LIMITS


def _material_file_limit() -> int:
    return _authority_fixture_limits()["maxFileBytes"]


def _consume_budget(amount: int) -> None:
    global _ACTIVE_READ_BUDGET
    if _ACTIVE_READ_BUDGET is None:
        return
    require(amount <= _ACTIVE_READ_BUDGET, "A1_MATERIAL_TREE_TOTAL_BYTES_LIMIT_EXCEEDED")
    _ACTIVE_READ_BUDGET -= amount


@contextmanager
def material_read_budget(limit: int):
    global _ACTIVE_READ_BUDGET
    previous = _ACTIVE_READ_BUDGET
    _ACTIVE_READ_BUDGET = limit if previous is None else min(previous, limit)
    try:
        yield
    finally:
        _ACTIVE_READ_BUDGET = previous


def stable_file_read(path: pathlib.Path, budget: int | None = None) -> tuple[bytes, os.stat_result]:
    """Read a regular file from a component-wise no-follow FD with hard byte bounds."""
    file_limit = 16 * 1024 * 1024 if _MATERIAL_LIMITS is None else _material_file_limit()
    if budget is not None:
        file_limit = min(file_limit, budget)
    descriptor = _open_componentwise(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0))
    try:
        before = os.fstat(descriptor)
        require(stat.S_ISREG(before.st_mode), "A1_REFERENCED_MATERIAL_UNAVAILABLE", str(path))
        require(before.st_nlink == 1, "A1_TREE_HARD_LINK_REJECTED", str(path))
        require(before.st_size <= file_limit, "A1_MATERIAL_FILE_SIZE_LIMIT_EXCEEDED", str(path))
        remaining = before.st_size
        chunks: list[bytes] = []
        while remaining:
            read_budget = remaining
            if _ACTIVE_READ_BUDGET is not None:
                require(_ACTIVE_READ_BUDGET > 0, "A1_MATERIAL_TREE_TOTAL_BYTES_LIMIT_EXCEEDED", str(path))
                read_budget = min(read_budget, _ACTIVE_READ_BUDGET)
            try:
                chunk = os.read(descriptor, min(1024 * 1024, read_budget))
            except OSError as error:
                raise MaterialError("A1_REFERENCED_MATERIAL_UNAVAILABLE", str(path)) from error
            require(chunk, "A1_STABLE_READ_UNSTABLE", str(path))
            _consume_budget(len(chunk))
            chunks.append(chunk)
            remaining -= len(chunk)
        value = b"".join(chunks)
        after = os.fstat(descriptor)
    finally:
        os.close(descriptor)
    require(
        (before.st_dev, before.st_ino, before.st_size, before.st_nlink)
        == (after.st_dev, after.st_ino, after.st_size, after.st_nlink),
        "A1_STABLE_READ_TOCTOU_DRIFT",
        str(path),
    )
    require(after.st_nlink == 1 and len(value) == after.st_size, "A1_STABLE_READ_UNSTABLE", str(path))
    return value, after


def stable_file_stat(path: pathlib.Path, unavailable_code: str = "A1_REFERENCED_MATERIAL_UNAVAILABLE") -> os.stat_result:
    descriptor = _open_componentwise(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0))
    try:
        metadata = os.fstat(descriptor)
    finally:
        os.close(descriptor)
    require(stat.S_ISREG(metadata.st_mode), unavailable_code, str(path))
    require(metadata.st_nlink == 1, "A1_TREE_HARD_LINK_REJECTED", str(path))
    require(metadata.st_size <= _material_file_limit(), "A1_MATERIAL_FILE_SIZE_LIMIT_EXCEEDED", str(path))
    return metadata


def _unlink_componentwise(path: pathlib.Path) -> None:
    parent_fd = _open_componentwise(path.parent, os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0))
    try:
        try:
            os.unlink(path.name, dir_fd=parent_fd)
        except OSError as error:
            raise MaterialError("A1_REFERENCED_MATERIAL_UNAVAILABLE", str(path)) from error
    finally:
        os.close(parent_fd)


def _write_new_componentwise(path: pathlib.Path, value: bytes) -> None:
    parent_fd = _open_componentwise(path.parent, os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0))
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0)
    try:
        descriptor = os.open(path.name, flags, 0o600, dir_fd=parent_fd)
        try:
            view = memoryview(value)
            while view:
                written = os.write(descriptor, view)
                require(written > 0, "A1_STABLE_READ_UNSTABLE", str(path))
                view = view[written:]
        finally:
            os.close(descriptor)
    except OSError as error:
        raise MaterialError("A1_REFERENCED_MATERIAL_UNAVAILABLE", str(path)) from error
    finally:
        os.close(parent_fd)


def _rmdir_componentwise(path: pathlib.Path) -> None:
    parent_fd = _open_componentwise(path.parent, os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0))
    try:
        try:
            os.rmdir(path.name, dir_fd=parent_fd)
        except OSError as error:
            raise MaterialError("A1_REFERENCED_MATERIAL_UNAVAILABLE", str(path)) from error
    finally:
        os.close(parent_fd)


def _symlink_componentwise(path: pathlib.Path, target: str) -> None:
    parent_fd = _open_componentwise(path.parent, os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0))
    try:
        try:
            os.symlink(target, path.name, dir_fd=parent_fd)
        except OSError as error:
            raise MaterialError("A1_REFERENCED_MATERIAL_UNAVAILABLE", str(path)) from error
    finally:
        os.close(parent_fd)


@dataclass(frozen=True)
class PhysicalInventory:
    root_identity: tuple[int, int]
    entries: dict[str, tuple[str, int, int, int, int, int, int]]
    files: set[pathlib.Path]
    total_file_bytes: int


def _inventory_directory(directory_fd: int, prefix: str, inventory: dict[str, tuple[str, int, int, int, int, int, int]], files: set[pathlib.Path], root: pathlib.Path, total: list[int], limits: dict[str, int]) -> None:
    try:
        names = sorted(os.listdir(directory_fd))
    except OSError as error:
        raise MaterialError("A1_TREE_SCOPE_MISSING", prefix or str(root)) from error
    for name in names:
        require(name not in ("", ".", ".."), "A1_MATERIAL_PATH_ESCAPE", name)
        child_relative = f"{prefix}/{name}" if prefix else name
        metadata = _stat_child(directory_fd, name, "A1_TREE_ENTRY_MISSING")
        mode = metadata.st_mode
        if stat.S_ISLNK(mode):
            raise MaterialError("A1_TREE_SYMLINK_REJECTED", child_relative)
        if stat.S_ISDIR(mode):
            child_fd = _open_componentwise_child(directory_fd, name, directory=True, detail=child_relative)
            try:
                opened = os.fstat(child_fd)
                require(_same_identity(metadata, opened), "A1_TREE_ENTRY_REPLACED", child_relative)
                inventory[child_relative] = _entry_identity("DIR", opened)
                require(len(inventory) <= limits["maxTreeEntries"], "A1_MATERIAL_TREE_ENTRY_COUNT_LIMIT_EXCEEDED", str(root))
                _inventory_directory(child_fd, child_relative, inventory, files, root, total, limits)
            finally:
                os.close(child_fd)
            continue
        require(stat.S_ISREG(mode), "A1_TREE_ENTRY_KIND_INVALID", child_relative)
        require(metadata.st_nlink == 1, "A1_TREE_HARD_LINK_REJECTED", child_relative)
        require(metadata.st_size <= limits["maxTreeEntryBytes"], "A1_MATERIAL_FILE_SIZE_LIMIT_EXCEEDED", child_relative)
        total[0] += metadata.st_size
        require(total[0] <= limits["maxTreeTotalBytes"], "A1_MATERIAL_TREE_TOTAL_BYTES_LIMIT_EXCEEDED", str(root))
        child_fd = _open_componentwise_child(directory_fd, name, directory=False, detail=child_relative)
        try:
            opened = os.fstat(child_fd)
            require(_same_identity(metadata, opened), "A1_TREE_ENTRY_REPLACED", child_relative)
        finally:
            os.close(child_fd)
        inventory[child_relative] = _entry_identity("FILE", metadata)
        files.add(root / pathlib.PurePosixPath(child_relative))
        require(len(inventory) <= limits["maxTreeEntries"], "A1_MATERIAL_TREE_ENTRY_COUNT_LIMIT_EXCEEDED", str(root))


def _open_componentwise_child(parent_fd: int, name: str, *, directory: bool, detail: str) -> int:
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0)
    if directory:
        flags |= os.O_DIRECTORY
    try:
        return os.open(name, flags, dir_fd=parent_fd)
    except OSError as error:
        if error.errno == errno.ELOOP:
            raise MaterialError("A1_MATERIAL_SYMLINK_REJECTED", detail) from error
        raise MaterialError("A1_TREE_ENTRY_REPLACED", detail) from error


def _entry_identity(kind: str, metadata: os.stat_result) -> tuple[str, int, int, int, int, int, int]:
    return (
        kind,
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_size,
        metadata.st_nlink,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def _same_identity(left: os.stat_result, right: os.stat_result) -> bool:
    return (left.st_dev, left.st_ino, left.st_mode, left.st_size, left.st_nlink) == (
        right.st_dev, right.st_ino, right.st_mode, right.st_size, right.st_nlink
    )


def physical_inventory(root: pathlib.Path, limits: dict[str, int] | None = None) -> PhysicalInventory:
    """Inventory a sealed tree from held directory FDs without following links."""
    root = _absolute_path(root)
    limits = limits or _authority_fixture_limits()
    root_fd = _open_componentwise(root, os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0))
    try:
        root_metadata = os.fstat(root_fd)
        require(stat.S_ISDIR(root_metadata.st_mode), "A1_TREE_SCOPE_MISSING", str(root))
        entries: dict[str, tuple[str, int, int, int, int, int, int]] = {}
        files: set[pathlib.Path] = set()
        total = [0]
        _inventory_directory(root_fd, "", entries, files, root, total, limits)
        return PhysicalInventory(
            root_identity=(root_metadata.st_dev, root_metadata.st_ino),
            entries=entries,
            files=files,
            total_file_bytes=total[0],
        )
    finally:
        os.close(root_fd)


def require_inventory_stable(before: PhysicalInventory, after: PhysicalInventory, detail: str) -> None:
    require(before.root_identity == after.root_identity, "A1_TREE_ROOT_REPLACED", detail)
    require(before.entries == after.entries, "A1_TREE_ENTRY_SET_DRIFT", detail)


def physical_files(root: pathlib.Path) -> set[pathlib.Path]:
    return physical_inventory(root).files


def verify_raw_ref(ref: dict[str, Any]) -> tuple[pathlib.Path, bytes]:
    path = resolve_uri(ref["uri"])
    value, _ = stable_file_read(path)
    require(ref["rawFingerprint"] == raw_fingerprint(value), "A1_RAW_FINGERPRINT_MISMATCH", ref["uri"])
    return path, value


def material_run_root(uri: str) -> str:
    parts = pathlib.PurePosixPath(uri).parts
    require(len(parts) >= 4 and parts[:2] == ("run-material", "runs"), "A1_RUN_URI_INVALID", uri)
    return "/".join(parts[:3])


def verify_same_run_root(expected: str, *uris: str) -> None:
    for uri in uris:
        require(material_run_root(uri) == expected, "A1_RUN_ROOT_DRIFT", uri)


def read_canonical_document(path: pathlib.Path, *, typed: bool = True, budget: int | None = None) -> tuple[dict[str, Any], bytes]:
    value, _ = stable_file_read(path, budget=budget)
    document = json.loads(value, object_pairs_hook=_reject_duplicate_pairs)
    require(isinstance(document, dict), "A1_DOCUMENT_ROOT_NOT_OBJECT", str(path))
    require(value == canonical_bytes(document), "A1_NON_CANONICAL_MATERIAL", str(path))
    if typed:
        validate_typed_document(document, path)
    return document, value


CALLER_RUN_FILES = {
    "command.json",
    "stdout",
    "stderr",
    "response.json",
    "process-observation.json",
    "process-transcript.json",
}
PRODUCER_CHILD_FILES = {
    "command.json",
    "request.json",
    "stdout",
    "stderr",
    "response.json",
    "process-observation.json",
    "process-transcript.json",
}
OLD_RUN_LAYOUT_FILES = {"invocation.json", "provider-materialization.json"}
EARLY_BOOTSTRAP_PURPOSES = {
    "WRONG_VERIFIER_DIGEST_A1",
    "REGISTRY_MUTATION_A1",
    "TCK_MISMATCH_A1",
}
HARNESS_REPLAY_PROOF_URI = "run-material/reports/A1-REPORT-GENERATED-001/derived/replay-proof-envelope.json"
HARNESS_REPLAY_RUN_ROOT = "run-material/runs/RUN-A1-INDEPENDENT-NORMAL-001"


def child_slug(test_id: str) -> str:
    return "-".join(part for part in "".join(char.lower() if char.isalnum() else " " for char in test_id).split())


CHILD_DIRECTORY_PATTERN = re.compile(r"^(?P<ordinal>[0-9]{2})-(?P<slug>[a-z0-9][a-z0-9-]*)$")


def material_binding(path: pathlib.Path) -> dict[str, Any]:
    relative = path.absolute().relative_to(RUN_MATERIAL.absolute())
    parts = relative.parts
    require(len(parts) >= 2 and parts[0] == "runs", "A1_RUN_ROLE_PATH_INVALID", str(path))
    parent_run_id = parts[1]
    if len(parts) >= 4 and parts[2:4] == ("producer", "children"):
        require(len(parts) >= 5, "A1_CHILD_RUN_ROOT_INVALID", str(path))
        match = CHILD_DIRECTORY_PATTERN.fullmatch(parts[4])
        require(match is not None, "A1_CHILD_ORDINAL_INVALID", str(path))
        ordinal = int(match.group("ordinal"))
        child_run_id = f"{parent_run_id}-CHILD-{ordinal:02d}"
        return {"parentRunId": parent_run_id, "runId": child_run_id, "ordinal": ordinal, "childDir": parts[4]}
    return {"parentRunId": parent_run_id, "runId": parent_run_id, "ordinal": None, "childDir": None}


def verify_launch_command(
    command: dict[str, Any],
    run_id: str,
    launch_kind: str,
    process_path: pathlib.Path,
    run_purpose: str | None = None,
) -> None:
    contracts = protocol_launch_contracts()
    require(launch_kind in contracts, "A1_LAUNCH_KIND_UNKNOWN", launch_kind)
    contract = contracts[launch_kind]
    require(command["runId"] == run_id, "A1_COMMAND_RUN_ID_DRIFT")
    require(command["executable"] == contract["executable"], "A1_JAVA_EXECUTABLE_PATH_DRIFT")
    binding = material_binding(process_path)
    substitutions = {
        "runId": run_id,
        "outerRunId": binding["parentRunId"],
        "childDir": binding["childDir"],
        "runPurpose": run_purpose,
    }

    def expand(value: str) -> str:
        def replace(match: re.Match[str]) -> str:
            name = match.group(1)
            replacement = substitutions.get(name)
            require(replacement is not None, "A1_LAUNCH_TEMPLATE_UNBOUND", name)
            return replacement

        return re.sub(r"\{([A-Za-z][A-Za-z0-9]*)\}", replace, value)

    expected_arguments = [expand(argument) for argument in contract["argumentTemplate"]]
    expected_working_directory = expand(contract["workingDirectoryTemplate"])
    if launch_kind == "CANDIDATE_CHILD":
        request_path = process_path.parent / "request.json"
        stable_file_stat(request_path, "A1_REQUEST_ROLE_PATH_DRIFT")
        require(expected_arguments[-2:] == ["--request", "/tmp/gate-a/" + binding["parentRunId"] + "/producer/children/" + binding["childDir"] + "/request.json"], "A1_LAUNCH_TEMPLATE_REQUEST_BINDING_DRIFT")
    require(command["arguments"] == expected_arguments, "A1_LAUNCH_ARGUMENTS_DRIFT")
    require(command["workingDirectory"] == expected_working_directory, "A1_LAUNCH_WORKING_DIRECTORY_DRIFT")


def launch_artifact_path(launch_kind: str) -> str:
    arguments = protocol_launch_contracts()[launch_kind]["argumentTemplate"]
    if arguments[0] == "-jar":
        return arguments[1]
    classpath = arguments[1].split(":")
    require(classpath[-1].startswith("/opt/") and classpath[-1].endswith(".jar"), "A1_LAUNCH_ARTIFACT_TEMPLATE_DRIFT")
    return classpath[-1]


def verify_run_material_layout(entry_base: pathlib.Path, actual_paths: set[str]) -> None:
    """Enforce the caller/producer capability split before following any refs."""
    require(OLD_RUN_LAYOUT_FILES.isdisjoint(actual_paths), "A1_RUN_OLD_LAYOUT_REJECTED")
    caller_paths = set(CALLER_RUN_FILES)
    require(caller_paths <= actual_paths, "A1_RUN_CALLER_ALLOWLIST_MISSING")
    producer_invocation = entry_base / "producer" / "invocation.json"
    stable_file_stat(producer_invocation, "A1_RUN_PRODUCER_ALLOWLIST_MISSING")
    invocation, _ = read_canonical_document(producer_invocation)
    require(invocation.get("runId") == entry_base.name, "A1_INVOCATION_RUN_ID_DRIFT")
    require(invocation.get("messageVersion") in {
        "resource-gateway.capability-studio.gate-a.a1-invocation-record.v1",
        "resource-gateway.capability-studio.gate-a.harness-invocation-record.v1",
    }, "A1_RUN_PRODUCER_INVOCATION_KIND_INVALID")

    child_dirs = {
        pathlib.PurePosixPath(path).parts[2]
        for path in actual_paths
        if path.startswith("producer/children/")
    }
    is_harness = invocation["messageVersion"].endswith("harness-invocation-record.v1")
    if is_harness:
        require(not child_dirs, "A1_HARNESS_CHILD_MATERIAL_FORBIDDEN")
        require("producer/provider-materialization.json" not in actual_paths, "A1_HARNESS_PROVIDER_MATERIAL_FORBIDDEN")
        producer_paths = {"producer/invocation.json"}
    else:
        if invocation.get("runPurpose") in EARLY_BOOTSTRAP_PURPOSES:
            require(
                "producer/provider-materialization.json" not in actual_paths,
                "A1_EARLY_BOOTSTRAP_PROVIDER_FORBIDDEN",
            )
        else:
            require("producer/provider-materialization.json" in actual_paths, "A1_RUN_PROVIDER_ALLOWLIST_MISSING")
        replay_registry = protocol_replay_vector_registry()
        expected_child_dirs = {
            f"{vector['ordinal']:02d}-{child_slug(vector['testId'])}"
            for vector in replay_registry["replayVectors"]
        }
        require(child_dirs in (set(), expected_child_dirs), "A1_RUN_CHILD_ALLOWLIST_INVALID")
        producer_paths = {"producer/invocation.json"}
        if "producer/provider-materialization.json" in actual_paths:
            producer_paths.add("producer/provider-materialization.json")
        for child_dir in child_dirs:
            child_files = {
                pathlib.PurePosixPath(path).name
                for path in actual_paths
                if path.startswith(f"producer/children/{child_dir}/")
            }
            expected_files = set(PRODUCER_CHILD_FILES)
            vector = next(
                (item for item in replay_registry["replayVectors"] if child_dir.endswith("-" + child_slug(item["testId"]))),
                None,
            )
            require(vector is not None, "A1_RUN_CHILD_ALLOWLIST_INVALID", child_dir)
            expected_files.update(vector["allowedExtraMaterial"])
            require(child_files == expected_files, "A1_RUN_CHILD_ALLOWLIST_INVALID", child_dir)
            producer_paths.update({f"producer/children/{child_dir}/{name}" for name in child_files})

    expected_paths = caller_paths | producer_paths
    require(actual_paths == expected_paths, "A1_RUN_ROLE_ALLOWLIST_VIOLATION")


def verify_self(document: dict[str, Any], field: str, domain: str) -> None:
    require(document[field] == document_fingerprint(document, field, domain), "A1_DOCUMENT_FINGERPRINT_MISMATCH", field)


def fingerprint_value(value: dict[str, str]) -> str:
    return value["value"]


def expected_authority() -> dict[str, Any]:
    pin_path = TRUST_BUILD / "valid-challenge-trust-pin.json"
    pin = read_json(pin_path)
    provider = read_json(TRUST_BUILD / "valid-tck-provider-identity.json")
    sandbox_path = HERE / "valid-challenge-sandbox-profile.json"
    schema_set_path = TRUST_BUILD / "valid-schema-set-manifest.json"
    authority = {
        "pin": pin,
        "pinRaw": raw_fingerprint(stable_file_read(pin_path)[0]),
        "provider": provider,
        "providerIdentityRaw": raw_fingerprint(stable_file_read(TRUST_BUILD / "valid-tck-provider-identity.json")[0]),
        "sandboxRaw": raw_fingerprint(stable_file_read(sandbox_path)[0]),
        "schemaSetRaw": raw_fingerprint(stable_file_read(schema_set_path)[0]),
        "launchContracts": protocol_launch_contracts(),
        "replayRegistry": protocol_replay_vector_registry(),
    }
    protocol_source = read_json(PROTOCOL_SOURCE)
    require(
        pin["expectedProtocolAuthorityRawFingerprint"] == raw_fingerprint(stable_file_read(PROTOCOL_SOURCE)[0]),
        "A1_PROTOCOL_AUTHORITY_PIN_DRIFT",
    )
    authority["abnormalTransitions"] = protocol_source["abnormalTransitions"]
    authority["challengeInput"] = verify_challenge_input_authority(pin)
    input_set = replay_input_set_for(authority, authority["replayRegistry"]["replayVectors"][0]["inputSetId"])
    require(authority["challengeInput"]["treeUri"] == input_set["fixtureRootUri"], "A1_REPLAY_INPUT_ROOT_URI_DRIFT")
    require(authority["challengeInput"]["rootFingerprint"]["value"] == input_set["fixtureRootFingerprint"], "A1_REPLAY_INPUT_ROOT_DRIFT")
    for item in input_set["exactRefs"]:
        actual = authority["challengeInput"]["exactRefs"].get(item["uri"])
        require(actual == {"uri": item["uri"], "rawFingerprint": typed_fingerprint("RAW_BYTES", item["rawFingerprint"])}, "A1_REPLAY_INPUT_EXACT_REF_DRIFT", item["uri"])
    return authority


def resolve_challenge_input_uri(uri: str) -> pathlib.Path:
    require(uri.startswith("inputs/") and not uri.startswith("/"), "A1_INPUT_URI_INVALID", uri)
    return _safe_existing_path(uri, CHALLENGE_INPUT, "A1_INPUT_MATERIAL_UNAVAILABLE")


def verify_challenge_input_authority(pin: dict[str, Any]) -> dict[str, Any]:
    tree_uri = "inputs/challenge-root.tree"
    tree_path = resolve_challenge_input_uri(tree_uri)
    _authority_fixture_limits()
    inventory_before = physical_inventory(tree_path.parent)
    with material_read_budget(_authority_fixture_limits()["maxTreeTotalBytes"]):
        manifest, _ = read_canonical_document(tree_path, typed=False, budget=_authority_fixture_limits()["maxBindingManifestBytes"])
        require(manifest.get("rootKind") == "GATE_A_CHALLENGE_INPUT", "A1_INPUT_TREE_KIND_INVALID")
        entries = manifest.get("entries")
        require(isinstance(entries, list) and entries, "A1_INPUT_TREE_EMPTY")
        require(entries == sorted(entries, key=lambda entry: entry["relativePath"]), "A1_INPUT_TREE_NOT_SORTED")
        relative_paths = [entry["relativePath"] for entry in entries]
        require(len(relative_paths) == len(set(relative_paths)), "A1_INPUT_TREE_DUPLICATE_ENTRY")
        actual_paths = {
            path.relative_to(tree_path.parent).as_posix()
            for path in inventory_before.files
            if path != tree_path
        }
        require(actual_paths == set(relative_paths), "A1_INPUT_TREE_ENTRY_SET_DRIFT")
        exact_refs: dict[str, dict[str, Any]] = {}
        identities: set[tuple[int, int]] = set()
        for entry in entries:
            require(entry["kind"] == "FILE", "A1_INPUT_TREE_ENTRY_KIND_INVALID")
            uri = "inputs/" + entry["relativePath"]
            path = resolve_challenge_input_uri(uri)
            value, metadata = stable_file_read(path)
            require(metadata.st_nlink == 1, "A1_INPUT_HARD_LINK_REJECTED", uri)
            identity = (metadata.st_dev, metadata.st_ino)
            require(identity not in identities, "A1_INPUT_FILE_IDENTITY_REUSED", uri)
            identities.add(identity)
            require(entry["byteLength"] == len(value), "A1_INPUT_LENGTH_DRIFT", uri)
            require(entry["rawFingerprint"] == raw_fingerprint(value), "A1_INPUT_RAW_DRIFT", uri)
            exact_refs[uri] = {"uri": uri, "rawFingerprint": raw_fingerprint(value)}
        expected_root = commitment(entries, DOMAINS["challenge_input_tree"], "TREE_COMMITMENT")
        require(manifest["rootFingerprint"] == expected_root, "A1_INPUT_TREE_FINGERPRINT_DRIFT")
        require(pin["expectedChallengeInputRootFingerprint"] == expected_root, "A1_INPUT_PIN_ROOT_DRIFT")
    inventory_after = physical_inventory(tree_path.parent)
    require_inventory_stable(inventory_before, inventory_after, str(tree_path.parent))
    return {"treeUri": tree_uri, "rootFingerprint": expected_root, "exactRefs": exact_refs}


def verify_tree_ref(tree_ref: dict[str, Any]) -> tuple[dict[str, Any], set[str], set[tuple[int, int]]]:
    limits = _authority_fixture_limits()
    path = resolve_uri(tree_ref["uri"])
    inventory_root = path.parent if tree_ref["uri"].startswith("run-material/runs/") else RUN_MATERIAL
    inventory_before = physical_inventory(inventory_root)
    with material_read_budget(limits["maxTreeTotalBytes"]):
        manifest, _ = read_canonical_document(path, typed=False, budget=limits["maxBindingManifestBytes"])
        entries = manifest["entries"]
        require(entries == sorted(entries, key=lambda entry: entry["relativePath"]), "A1_TREE_NOT_SORTED")
        relative_paths = [entry["relativePath"] for entry in entries]
        require(len(relative_paths) == len(set(relative_paths)), "A1_TREE_DUPLICATE_ENTRY")
        expected = commitment(entries, DOMAINS["tree"], "TREE_COMMITMENT")
        require(manifest["rootFingerprint"] == expected, "A1_TREE_FINGERPRINT_MISMATCH")
        require(tree_ref["fingerprint"] == expected, "A1_TREE_REF_FINGERPRINT_MISMATCH")

        if manifest["rootKind"] == "GATE_A_RUN_MATERIAL":
            entry_base = path.parent
            require(manifest["runId"] == entry_base.name, "A1_TREE_RUN_ID_DRIFT")
            actual_paths = {
                item.relative_to(entry_base).as_posix()
                for item in inventory_before.files
                if item != path
            }
            require(actual_paths == set(relative_paths), "A1_TREE_ENTRY_SET_DRIFT")
            scope_inventories = {entry_base: inventory_before}
        else:
            require(manifest["rootKind"] == "GATE_A_INDEPENDENT_REPORT_MATERIAL", "A1_TREE_KIND_INVALID")
            entry_base = RUN_MATERIAL
            scope_roots = manifest.get("scopeRoots")
            require(isinstance(scope_roots, list) and scope_roots, "A1_REPORT_SCOPE_ROOTS_MISSING")
            require(len(scope_roots) == len(set(scope_roots)), "A1_REPORT_SCOPE_ROOT_REUSED")
            require(
                all(
                    not (left + "/").startswith(right + "/")
                    for left in scope_roots
                    for right in scope_roots
                    if left != right
                ),
                "A1_REPORT_SCOPE_ROOT_OVERLAP",
            )
            actual_paths: set[str] = set()
            scope_inventories: dict[pathlib.Path, PhysicalInventory] = {}
            for scope_root in scope_roots:
                try:
                    scope = _safe_existing_path(scope_root, RUN_MATERIAL, "A1_REPORT_SCOPE_MISSING")
                except MaterialError as error:
                    if error.code == "A1_MATERIAL_SYMLINK_REJECTED":
                        raise MaterialError("A1_TREE_SYMLINK_REJECTED", scope_root) from error
                    raise MaterialError("A1_REPORT_SCOPE_MISSING", scope_root) from error
                scope_fd = _open_componentwise(scope, os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0))
                try:
                    scope_metadata = os.fstat(scope_fd)
                finally:
                    os.close(scope_fd)
                require(stat.S_ISDIR(scope_metadata.st_mode), "A1_REPORT_SCOPE_MISSING", scope_root)
                scope_inventory = physical_inventory(scope)
                scope_inventories[scope] = scope_inventory
                actual_paths.update(
                    item.relative_to(RUN_MATERIAL).as_posix()
                    for item in scope_inventory.files
                )
            require(actual_paths == set(relative_paths), "A1_TREE_ENTRY_SET_DRIFT")

        material_uris: set[str] = set()
        file_identities: set[tuple[int, int]] = set()
        for entry in entries:
            target = _safe_existing_path(entry["relativePath"], entry_base, "A1_TREE_ENTRY_MISSING")
            metadata = stable_file_stat(target, "A1_TREE_ENTRY_MISSING")
            require(stat.S_ISREG(metadata.st_mode), "A1_TREE_ENTRY_MISSING", entry["relativePath"])
            value, metadata = stable_file_read(target)
            require(metadata.st_nlink == 1, "A1_TREE_HARD_LINK_REJECTED", entry["relativePath"])
            identity = (metadata.st_dev, metadata.st_ino)
            require(identity not in file_identities, "A1_TREE_FILE_IDENTITY_REUSED", entry["relativePath"])
            file_identities.add(identity)
            require(entry["kind"] == "FILE", "A1_TREE_ENTRY_KIND_INVALID")
            require(entry["byteLength"] == len(value), "A1_TREE_ENTRY_LENGTH_DRIFT", entry["relativePath"])
            require(entry["rawFingerprint"] == raw_fingerprint(value), "A1_TREE_ENTRY_RAW_DRIFT", entry["relativePath"])
            material_uris.add("run-material/" + target.relative_to(RUN_MATERIAL).as_posix())
        if manifest["rootKind"] == "GATE_A_RUN_MATERIAL":
            verify_run_material_layout(entry_base, {
                item.relative_to(entry_base).as_posix()
                for item in inventory_before.files
                if item.name != "material-root.tree"
            })
        else:
            for scope_root, scope_inventory in scope_inventories.items():
                if scope_root.parts[-2] == "runs":
                    verify_run_material_layout(scope_root, {
                        item.relative_to(scope_root).as_posix()
                        for item in scope_inventory.files
                        if item.name != "material-root.tree"
                    })
    inventory_after = physical_inventory(inventory_root)
    require_inventory_stable(inventory_before, inventory_after, str(inventory_root))
    return manifest, material_uris, file_identities


def verify_provider_materialization(
    materialization_ref: dict[str, Any],
    expected_run_root: str,
    authority: dict[str, Any],
) -> dict[str, Any]:
    verify_same_run_root(expected_run_root, materialization_ref["uri"])
    observation_path, observation_bytes = verify_raw_ref(materialization_ref)
    require(
        observation_path == resolve_uri(expected_run_root + "/producer/provider-materialization.json"),
        "A1_PROVIDER_MATERIALIZATION_ROLE_PATH_DRIFT",
    )
    observation, _ = read_canonical_document(observation_path)
    verify_self(observation, "observationFingerprint", DOMAINS["provider_materialization"])
    expected_run_id = expected_run_root.removeprefix("run-material/runs/")
    require(observation["runId"] == expected_run_id, "A1_PROVIDER_MATERIALIZATION_RUN_ID_DRIFT")
    provider_raw = authority["pin"]["expectedTckProviderRawFingerprint"]
    for field in ("sourcePreReadRawFingerprint", "materializedRawFingerprint", "sourcePostReadRawFingerprint"):
        require(observation[field] == provider_raw, "A1_PROVIDER_MATERIALIZATION_RAW_DRIFT", field)
    require(observation["providerIdentityRawFingerprint"] == authority["providerIdentityRaw"], "A1_PROVIDER_IDENTITY_RAW_DRIFT")
    source_path, source_bytes = verify_raw_ref(observation["sourceJarRef"])
    require(source_path == HERE / "provider-fixture" / "gate-a-tck-provider.jar", "A1_PROVIDER_SOURCE_ROLE_PATH_DRIFT")
    require(raw_fingerprint(source_bytes) == provider_raw, "A1_PROVIDER_SOURCE_PIN_DRIFT")
    require(observation["createMode"] == "CREATE_NEW", "A1_PROVIDER_CREATE_MODE_DRIFT")
    require(observation["destinationPath"] == "/work/provider.jar", "A1_PROVIDER_DESTINATION_DRIFT")
    require(observation["scratchBeforeCount"] == observation["scratchAfterCount"] == 0, "A1_PROVIDER_SCRATCH_NOT_EMPTY")
    require(observation["deletionStatus"] == "VERIFIED" and observation["residualPaths"] == [], "A1_PROVIDER_RESIDUE_DETECTED")

    create_receipt = observation["destinationCreateReceipt"]
    read_receipt = observation["destinationOpenReadReceipt"]
    delete_receipt = observation["destinationDeleteReceipt"]
    require(create_receipt["path"] == read_receipt["path"] == delete_receipt["path"] == observation["destinationPath"], "A1_PROVIDER_RECEIPT_PATH_DRIFT")
    require(create_receipt["createMode"] == observation["createMode"] == "CREATE_NEW", "A1_PROVIDER_CREATE_MODE_DRIFT")
    require(create_receipt["createNew"] and not create_receipt["preExisting"] and create_receipt["outcome"] == "CREATED", "A1_PROVIDER_CREATE_RECEIPT_INVALID")
    require(read_receipt["openMode"] == "READ_NOFOLLOW" and read_receipt["readMode"] == "FSTAT_READ_FSTAT", "A1_PROVIDER_READ_RECEIPT_INVALID")
    require(read_receipt["byteLength"] == len(source_bytes), "A1_PROVIDER_DESTINATION_LENGTH_DRIFT")
    require(read_receipt["rawFingerprint"] == observation["materializedRawFingerprint"] == raw_fingerprint(source_bytes), "A1_PROVIDER_DESTINATION_RAW_DRIFT")
    require(read_receipt["preRead"] == read_receipt["postRead"], "A1_PROVIDER_DESTINATION_TOCTOU_DRIFT")
    verify_provider_materialization_identity_snapshots(observation)
    for snapshot in (create_receipt["identity"], read_receipt["preRead"], read_receipt["postRead"]):
        require(snapshot["resolvedPath"] == observation["destinationPath"], "A1_PROVIDER_DESTINATION_PATH_DRIFT")
        require(snapshot["linkCount"] == 1, "A1_PROVIDER_DESTINATION_HARD_LINK_REJECTED")
        require(int(snapshot["posixMode"], 8) & 0o022 == 0, "A1_PROVIDER_DESTINATION_GROUP_OTHER_WRITABLE")
        require(snapshot["fileSize"] == len(source_bytes), "A1_PROVIDER_DESTINATION_SIZE_DRIFT")
        require(snapshot["readRawFingerprint"] == observation["materializedRawFingerprint"], "A1_PROVIDER_DESTINATION_SNAPSHOT_RAW_DRIFT")
    require(delete_receipt["deleteMode"] == "UNLINK" and delete_receipt["outcome"] == "DELETED", "A1_PROVIDER_DELETE_RECEIPT_INVALID")
    require(delete_receipt["missingAfterDelete"] is True and delete_receipt["residualPaths"] == [], "A1_PROVIDER_DELETE_NOT_VERIFIED")

    try:
        with zipfile.ZipFile(io.BytesIO(source_bytes)) as archive:
            infos = archive.infolist()
            names = [info.filename for info in infos]
            require(len(names) == len(set(names)), "A1_PROVIDER_ZIP_DUPLICATE_ENTRY")
            require(names == observation["jarEntryPaths"], "A1_PROVIDER_ZIP_INVENTORY_DRIFT")
            require(
                all(
                    name and not name.startswith("/") and ".." not in pathlib.PurePosixPath(name).parts
                    and pathlib.PurePosixPath(name).as_posix() == name and not info.is_dir()
                    for name, info in zip(names, infos, strict=True)
                ),
                "A1_PROVIDER_ZIP_PATH_INVALID",
            )
            provider = authority["provider"]
            descriptor_path = provider["serviceDescriptorPath"]
            class_path = provider["implementationClassEntryPath"]
            descriptor_bytes = archive.read(descriptor_path)
            class_bytes = archive.read(class_path)
    except (OSError, zipfile.BadZipFile, KeyError) as error:
        raise MaterialError("A1_PROVIDER_ZIP_INVALID", str(error)) from error
    require(descriptor_bytes == (authority["provider"]["providerClass"] + "\n").encode("utf-8"), "A1_PROVIDER_DESCRIPTOR_CONTENT_DRIFT")
    require(raw_fingerprint(descriptor_bytes) == authority["provider"]["serviceDescriptorFingerprint"], "A1_PROVIDER_DESCRIPTOR_RAW_DRIFT")
    require(raw_fingerprint(class_bytes) == authority["provider"]["implementationClassRawFingerprint"], "A1_PROVIDER_CLASS_RAW_DRIFT")
    require(observation["serviceDescriptor"] == {
        "path": authority["provider"]["serviceDescriptorPath"],
        "rawFingerprint": authority["provider"]["serviceDescriptorFingerprint"],
    }, "A1_PROVIDER_DESCRIPTOR_OBSERVATION_DRIFT")
    require(observation["implementationClass"] == {
        "path": authority["provider"]["implementationClassEntryPath"],
        "rawFingerprint": authority["provider"]["implementationClassRawFingerprint"],
    }, "A1_PROVIDER_CLASS_OBSERVATION_DRIFT")
    return {"observation": observation, "observationBytes": observation_bytes}


def verify_provider_materialization_identity_snapshots(observation: dict[str, Any]) -> None:
    create_identity = observation["destinationCreateReceipt"]["identity"]
    read_receipt = observation["destinationOpenReadReceipt"]
    require(
        create_identity == read_receipt["preRead"] == read_receipt["postRead"],
        "A1_PROVIDER_DESTINATION_IDENTITY_DRIFT",
    )


def verify_process_transcript(
    transcript_ref: dict[str, Any],
    expected_run_root: str,
    expected_launch_kind: str,
    expected_code_source: dict[str, str],
    authority: dict[str, Any],
    expected_run_purpose: str | None = None,
) -> dict[str, Any]:
    transcript_path, transcript_bytes = verify_raw_ref(transcript_ref)
    transcript, _ = read_canonical_document(transcript_path)
    transcript_domain = DOMAINS["harness_transcript"] if expected_launch_kind == "CONFORMANCE_HARNESS" else DOMAINS["transcript"]
    verify_self(transcript, "transcriptFingerprint", transcript_domain)
    verify_same_run_root(
        expected_run_root,
        transcript_ref["uri"],
        transcript["commandRef"]["uri"],
        transcript["processObservationRef"]["uri"],
        transcript["stdoutRef"]["uri"],
        transcript["stderrRef"]["uri"],
    )
    command_path, _ = verify_raw_ref(transcript["commandRef"])
    command, _ = read_canonical_document(command_path)
    verify_self(command, "commandFingerprint", DOMAINS["command"])
    observation_path, observation_bytes = verify_raw_ref(transcript["processObservationRef"])
    observation, _ = read_canonical_document(observation_path)
    stdout_path, stdout = verify_raw_ref(transcript["stdoutRef"])
    stderr_path, stderr = verify_raw_ref(transcript["stderrRef"])

    transcript_parent = transcript_path.parent
    require(transcript_path.name == "process-transcript.json", "A1_TRANSCRIPT_ROLE_PATH_DRIFT")
    require(command_path == transcript_parent / "command.json", "A1_COMMAND_ROLE_PATH_DRIFT")
    require(observation_path == transcript_parent / "process-observation.json", "A1_OBSERVATION_ROLE_PATH_DRIFT")
    require(stdout_path == transcript_parent / "stdout", "A1_STDOUT_ROLE_PATH_DRIFT")
    require(stderr_path == transcript_parent / "stderr", "A1_STDERR_ROLE_PATH_DRIFT")

    binding = material_binding(transcript_path)
    require(binding["parentRunId"] == expected_run_root.removeprefix("run-material/runs/"), "A1_PROCESS_RUN_ROOT_DRIFT")
    require(transcript["runId"] == binding["runId"], "A1_TRANSCRIPT_RUN_ID_DRIFT")
    require(command["runId"] == transcript["runId"] == observation["runId"], "A1_PROCESS_RUN_ID_DRIFT")
    verify_launch_command(command, transcript["runId"], expected_launch_kind, transcript_path, expected_run_purpose)
    require(command["environment"] == [], "A1_LAUNCHER_ENVIRONMENT_NOT_EMPTY")
    require(observation["environmentNames"] == [], "A1_OBSERVED_ENVIRONMENT_NOT_EMPTY")
    require(observation["jvmInputArguments"] == [], "A1_JVM_ARGUMENTS_NOT_EMPTY")
    require(observation["launchKind"] == expected_launch_kind, "A1_LAUNCH_KIND_DRIFT")
    require(observation["sandboxProfileRawFingerprint"] == authority["sandboxRaw"], "A1_SANDBOX_PROFILE_DRIFT")
    require(observation["processTree"]["processTreeQuiescent"], "A1_PROCESS_TREE_NOT_QUIESCENT")
    require(observation["processTree"]["descendantsAfterTermination"] == [], "A1_PROCESS_DESCENDANTS_REMAIN")
    require(observation["stdoutCapture"]["observedBytes"] == len(stdout), "A1_STDOUT_LENGTH_DRIFT")
    require(observation["stderrCapture"]["observedBytes"] == len(stderr), "A1_STDERR_LENGTH_DRIFT")
    for capture in (observation["stdoutCapture"], observation["stderrCapture"]):
        require(capture["observedBytes"] <= capture["limitBytes"], "A1_OUTPUT_LIMIT_EXCEEDED")
        require(capture["limitBytes"] <= 1048576, "A1_OUTPUT_LIMIT_PROFILE_DRIFT")
        require(capture["complete"] and not capture["overflow"], "A1_OUTPUT_CAPTURE_INCOMPLETE")
        require(capture["leakScan"] == "PASS", "A1_OUTPUT_LEAK_SCAN_FAILED")
    require(stderr == b"", "A1_STDERR_NOT_EMPTY")
    require(transcript["codeSource"]["rawFingerprint"] == expected_code_source, "A1_TRANSCRIPT_CODESOURCE_DRIFT")
    expected_artifact_path = launch_artifact_path(expected_launch_kind)
    require(transcript["codeSource"]["artifactPath"] == expected_artifact_path, "A1_TRANSCRIPT_CODESOURCE_PATH_DRIFT")
    require(transcript["processState"] == "COMPLETED", "A1_PROCESS_NOT_COMPLETED")
    for phase in ("preRead", "postRead"):
        snapshot = transcript["codeSourceObservation"][phase]
        require(snapshot["resolvedPath"] == transcript["codeSource"]["artifactPath"], "A1_CODESOURCE_PATH_TOCTOU_DRIFT")
        require(snapshot["fileKey"] == transcript["codeSource"]["fileKey"], "A1_CODESOURCE_FILE_KEY_TOCTOU_DRIFT")
        require(snapshot["fileSize"] == transcript["codeSource"]["fileSize"], "A1_CODESOURCE_SIZE_TOCTOU_DRIFT")
        require(snapshot["readRawFingerprint"] == transcript["codeSource"]["rawFingerprint"], "A1_CODESOURCE_RAW_TOCTOU_DRIFT")
        require(snapshot["linkCount"] == 1, "A1_CODESOURCE_HARD_LINK_REJECTED")
        require(int(snapshot["posixMode"], 8) & 0o022 == 0, "A1_CODESOURCE_GROUP_OTHER_WRITABLE")
    require(
        transcript["codeSourceObservation"]["preRead"] == transcript["codeSourceObservation"]["postRead"],
        "A1_CODESOURCE_TOCTOU_DRIFT",
    )
    require(
        observation["javaExecutableObservation"]["preRead"] == observation["javaExecutableObservation"]["postRead"],
        "A1_JAVA_EXECUTABLE_TOCTOU_DRIFT",
    )
    require(
        observation["javaExecutableObservation"]["preRead"]["resolvedPath"] == command["executable"],
        "A1_JAVA_EXECUTABLE_PATH_DRIFT",
    )
    require(observation["processTree"]["rootProcess"]["startInstant"] == transcript["startedAt"], "A1_PROCESS_START_INSTANT_DRIFT")
    application_entry = observation["effectiveClasspath"][-1]
    require(application_entry["artifactPath"] == expected_artifact_path, "A1_CLASSPATH_ARTIFACT_PATH_DRIFT")
    require(application_entry["rawFingerprint"] == expected_code_source, "A1_CLASSPATH_CODESOURCE_DRIFT")
    for origin in observation["admittedClassOrigins"]:
        expected_origin_raw = authority["pin"]["expectedTckProviderRawFingerprint"] if origin["role"] == "TCK_PROVIDER" else expected_code_source
        require(origin["codeSourceRawFingerprint"] == expected_origin_raw, "A1_CLASS_ORIGIN_CODESOURCE_DRIFT", origin["role"])
    require(raw_fingerprint(transcript_bytes) == transcript_ref["rawFingerprint"], "A1_TRANSCRIPT_RAW_DRIFT")
    return {
        "transcript": transcript,
        "transcriptBytes": transcript_bytes,
        "command": command,
        "commandPath": command_path,
        "observation": observation,
        "observationBytes": observation_bytes,
        "stdout": stdout,
        "stdoutPath": stdout_path,
        "stderr": stderr,
    }


SPECIAL_TEST_IDS = {
    "VERIFIER_DIGEST_MUTATION_REJECTED",
    "REGISTRY_MUTATION_REJECTED",
    "VERIFIER_TCK_MISMATCH_REJECTED",
}


def verify_case(slot: dict[str, Any], expected_run_root: str, authority: dict[str, Any]) -> dict[str, Any]:
    uris = [slot[field]["uri"] for field in ("commandRecordRef", "requestRef", "responseRef", "processTranscriptRef")]
    verify_same_run_root(expected_run_root, *uris)
    command_path, command_bytes = verify_raw_ref(slot["commandRecordRef"])
    request_path, request_bytes = verify_raw_ref(slot["requestRef"])
    response_path, response_bytes = verify_raw_ref(slot["responseRef"])
    command, _ = read_canonical_document(command_path)
    request, _ = read_canonical_document(request_path)
    response, _ = read_canonical_document(response_path)
    verify_self(command, "commandFingerprint", DOMAINS["command"])
    verify_self(request, "requestFingerprint", DOMAINS["request"])
    verify_self(response, "responseFingerprint", DOMAINS["response"])

    pin = authority["pin"]
    provider = authority["provider"]
    vector = replay_vector_for(authority, slot["testId"] if slot["testId"] not in SPECIAL_TEST_IDS else "PLACEHOLDER_REJECTED")
    input_set = replay_input_set_for(authority, vector["inputSetId"])
    candidate_raw = pin["expectedImplementationCandidateRawFingerprint"]
    provider_raw = pin["expectedTckProviderRawFingerprint"]
    process = verify_process_transcript(
        slot["processTranscriptRef"], expected_run_root, "CANDIDATE_CHILD", candidate_raw, authority
    )
    transcript = process["transcript"]
    child_dir = process["stdoutPath"].parent
    binding = material_binding(request_path)
    require(binding["parentRunId"] == expected_run_root.removeprefix("run-material/runs/"), "A1_CHILD_RUN_ROOT_DRIFT")
    require(binding["runId"] == transcript["runId"] and binding["ordinal"] is not None, "A1_CHILD_ORDINAL_DRIFT")
    require(request_path == child_dir / "request.json", "A1_REQUEST_ROLE_PATH_DRIFT")
    require(response_path == child_dir / "response.json", "A1_RESPONSE_ROLE_PATH_DRIFT")
    if "runId" in request:
        require(request["runId"] == transcript["runId"], "A1_REQUEST_RUN_ID_DRIFT")
    if "runId" in response:
        require(response["runId"] == transcript["runId"], "A1_RESPONSE_RUN_ID_DRIFT")
    observation = process["observation"]
    require(transcript["commandRef"] == slot["commandRecordRef"], "A1_COMMAND_REF_DRIFT")
    require(slot["commandFingerprint"] == command["commandFingerprint"], "A1_COMMAND_DOCUMENT_DRIFT")
    require(slot["requestFingerprint"] == request["requestFingerprint"], "A1_REQUEST_DOCUMENT_DRIFT")
    require(slot["responseFingerprint"] == response["responseFingerprint"], "A1_RESPONSE_DOCUMENT_DRIFT")
    require(slot["transcriptRawFingerprint"] == raw_fingerprint(process["transcriptBytes"]), "A1_TRANSCRIPT_REPORT_DRIFT")
    if "processObservationRawFingerprint" in slot:
        require(
            slot["processObservationRawFingerprint"] == raw_fingerprint(process["observationBytes"]),
            "A1_PROCESS_OBSERVATION_FINGERPRINT_MISMATCH",
        )
    require(slot["stdoutRawFingerprint"] == raw_fingerprint(process["stdout"]), "A1_STDOUT_REPORT_DRIFT")
    require(slot["stderrRawFingerprint"] == raw_fingerprint(process["stderr"]), "A1_STDERR_REPORT_DRIFT")
    require(process["stdout"] == response_bytes + b"\n", "A1_STDOUT_RESPONSE_DRIFT")
    require(request["challengeId"] == response["challengeId"], "A1_CHALLENGE_PAIR_ID_DRIFT")
    require(request["operation"] == response["operation"] == vector["operation"], "A1_OPERATION_DRIFT")
    require(request["tckVectorId"] == response["tckVectorId"], "A1_TCK_VECTOR_DRIFT")
    wire_test_id = "PLACEHOLDER_REJECTED" if slot["testId"] in SPECIAL_TEST_IDS else slot["testId"]
    require(request["tckVectorId"] == wire_test_id == vector["testId"], "A1_CASE_TEST_ID_DRIFT")
    require(slot["mutationVector"] == vector["mutationVector"], "A1_REPLAY_VECTOR_MUTATION_DRIFT")
    require(slot["expectedMechanism"] == vector["expectedMechanism"], "A1_REPLAY_VECTOR_MECHANISM_DRIFT")
    require(request["candidateRawFingerprint"] == candidate_raw, "A1_REQUEST_CANDIDATE_DRIFT")
    require(request["replayProfileRawFingerprint"] == pin["expectedReplayProfileRawFingerprint"], "A1_REPLAY_PROFILE_DRIFT")
    input_authority = authority["challengeInput"]
    require(request["fixtureRootRef"]["uri"] == "inputs/" + input_set["fixtureRootUri"].removeprefix("inputs/"), "A1_INPUT_TREE_URI_DRIFT")
    require(request["fixtureRootRef"]["fingerprint"] == typed_fingerprint("TREE_COMMITMENT", input_set["fixtureRootFingerprint"]), "A1_INPUT_TREE_REF_DRIFT")
    expected_input_refs = [
        {
            "inputRole": item["role"],
            "exactRef": {"uri": item["uri"], "rawFingerprint": typed_fingerprint("RAW_BYTES", item["rawFingerprint"])},
        }
        for item in input_set["exactRefs"]
    ]
    require(request["inputExactRefs"] == expected_input_refs, "A1_INPUT_EXACT_REF_DRIFT")
    require(request["inputExactRefs"] == [
        {"inputRole": item["inputRole"], "exactRef": input_authority["exactRefs"][item["exactRef"]["uri"]]}
        for item in request["inputExactRefs"]
    ], "A1_INPUT_PHYSICAL_REF_DRIFT")
    require(response["candidateCodeSourceRawFingerprint"] == candidate_raw, "A1_RESPONSE_CANDIDATE_DRIFT")
    require(slot["targetArtifactRawFingerprint"] == candidate_raw, "A1_CASE_TARGET_AUTHORITY_DRIFT")
    require(slot["inputPinRawFingerprint"] == authority["pinRaw"], "A1_CASE_INPUT_PIN_DRIFT")
    require(slot["harnessRawFingerprint"] == pin["expectedConformanceHarnessRawFingerprint"], "A1_CASE_HARNESS_DRIFT")
    require(transcript["exitCode"] == slot["processExitCode"], "A1_CASE_EXIT_DRIFT")
    require(response["observedTerminal"] == slot["observedTerminal"], "A1_CASE_TERMINAL_DRIFT")
    require(slot["expectedExitCode"] == vector["expectedExitCode"], "A1_REPLAY_VECTOR_EXIT_DRIFT")
    require(slot["expectedTerminal"] == vector["expectedTerminal"], "A1_REPLAY_VECTOR_TERMINAL_DRIFT")
    require(slot["expectedReasonCode"] == vector["expectedReasonCode"], "A1_REPLAY_VECTOR_REASON_DRIFT")
    expected_extra = set(vector["allowedExtraMaterial"])
    fixed_material = {"request.json", "response.json", "stdout", "stderr", "command.json", "process-observation.json", "process-transcript.json"}
    actual_extra = {
        item.relative_to(child_dir).as_posix()
        for item in physical_files(child_dir)
        if item.relative_to(child_dir).as_posix() not in fixed_material
    }
    require(actual_extra == expected_extra, "A1_REPLAY_EXTRA_MATERIAL_DRIFT")
    require(response["operationResultFingerprint"] == document_fingerprint(response["operationResult"], None, DOMAINS["operation"]), "A1_OPERATION_RESULT_FINGERPRINT_DRIFT")

    classpath = observation["effectiveClasspath"]
    require([item["role"] for item in classpath] == ["TCK_PROVIDER", "CANDIDATE"], "A1_CHILD_CLASSPATH_DRIFT")
    require(classpath[0]["artifactPath"] == "/work/provider.jar", "A1_PROVIDER_CLASSPATH_PATH_DRIFT")
    require(classpath[1]["artifactPath"] == "/opt/candidate.jar", "A1_CANDIDATE_CLASSPATH_PATH_DRIFT")
    require(classpath[0]["rawFingerprint"] == provider_raw, "A1_PROVIDER_CLASSPATH_DRIFT")
    require(classpath[1]["rawFingerprint"] == candidate_raw, "A1_CANDIDATE_CLASSPATH_DRIFT")
    origins = {item["role"]: item for item in observation["admittedClassOrigins"]}
    require(origins["CANDIDATE_SPI"]["classRawFingerprint"] == pin["expectedCandidateSpiClassRawFingerprint"], "A1_SPI_CLASS_ORIGIN_DRIFT")
    require(origins["TCK_PROVIDER"]["binaryName"] == provider["providerClass"], "A1_PROVIDER_FQCN_DRIFT")
    require(origins["TCK_PROVIDER"]["classRawFingerprint"] == provider["implementationClassRawFingerprint"], "A1_PROVIDER_CLASS_ORIGIN_DRIFT")
    require(origins["TCK_PROVIDER"]["codeSourceRawFingerprint"] == provider_raw, "A1_PROVIDER_CODESOURCE_DRIFT")
    if response["operationResultKind"] == "LEGACY_ACCEPTANCE_RESULT":
        require(response["closedReasonCode"] == slot["closedReasonCode"], "A1_CASE_REASON_DRIFT")
        require(response["authorityProviderFqcn"] == origins["TCK_PROVIDER"]["binaryName"], "A1_RESPONSE_PROVIDER_ORIGIN_DRIFT")
        require(response["authorityProviderClassRawFingerprint"] == origins["TCK_PROVIDER"]["classRawFingerprint"], "A1_RESPONSE_PROVIDER_ORIGIN_DRIFT")
        require(response["authorityProviderCodeSourceRawFingerprint"] == origins["TCK_PROVIDER"]["codeSourceRawFingerprint"], "A1_RESPONSE_PROVIDER_ORIGIN_DRIFT")
    else:
        expected_wrapper_reason = {
            "INVALID": "A0_INVALID",
            "UNAVAILABLE": "A0_UNAVAILABLE",
            "INCOMPLETE": "A0_INCOMPLETE",
        }[response["observedTerminal"]]
        require(response["closedReasonCode"] == expected_wrapper_reason, "A1_TYPED_WRAPPER_REASON_DRIFT")
        require(
            response["operationResult"]["challengeInputRootRef"] == request["fixtureRootRef"],
            "A1_OPERATION_INPUT_ROOT_DRIFT",
        )

    a0_path = child_dir / "a0-candidate-result.json"
    if slot["testId"] == "HONEST_INCOMPLETE_ACCEPTED":
        try:
            a0_bytes, _ = stable_file_read(a0_path)
        except MaterialError as error:
            raise MaterialError("A1_HONEST_A0_RESULT_MISSING") from error
        require(a0_bytes == canonical_bytes(response["operationResult"]), "A1_HONEST_A0_RESULT_DRIFT")
    else:
        try:
            stable_file_stat(a0_path)
        except MaterialError:
            pass
        else:
            raise MaterialError("A1_UNEXPECTED_A0_RESULT")

    return {
        "challengeId": request["challengeId"],
        "observationRaw": raw_fingerprint(process["observationBytes"]),
        "startedAt": transcript["startedAt"],
        "endedAt": transcript["endedAt"],
        "uris": uris + [transcript["processObservationRef"]["uri"], transcript["stdoutRef"]["uri"], transcript["stderrRef"]["uri"]],
        "raw": {
            "command": raw_fingerprint(command_bytes),
            "request": raw_fingerprint(request_bytes),
            "response": raw_fingerprint(response_bytes),
            "observation": raw_fingerprint(process["observationBytes"]),
            "stdout": raw_fingerprint(process["stdout"]),
            "stderr": raw_fingerprint(process["stderr"]),
            "transcript": raw_fingerprint(process["transcriptBytes"]),
        },
    }


def verify_replay_envelope(name: str, authority: dict[str, Any]) -> dict[str, Any]:
    envelope_path = HERE / name
    envelope = read_json(envelope_path)
    validate_typed_document(envelope, envelope_path)
    return verify_replay_proof_envelope(envelope, authority)


def verify_replay_proof_envelope(
    envelope: dict[str, Any],
    authority: dict[str, Any],
    expected_run_root: str | None = None,
) -> dict[str, Any]:
    verify_tck_normal_projection(authority)
    verify_self(envelope, "envelopeFingerprint", DOMAINS["envelope"])
    require(envelope["challengeTrustPinRawFingerprint"] == authority["pinRaw"], "A1_ENVELOPE_PIN_DRIFT")
    result_path, result_bytes = verify_raw_ref(envelope["replayResultRef"])
    result, _ = read_canonical_document(result_path)
    verify_self(result, "resultFingerprint", DOMAINS["replay"])
    run_root = material_run_root(envelope["replayResultRef"]["uri"])
    if expected_run_root is not None:
        require(run_root == expected_run_root, "A1_HARNESS_REPLAY_PROOF_RUN_DRIFT")
    verify_same_run_root(
        run_root,
        envelope["replayResultRef"]["uri"],
        envelope["producerProcessTranscriptRef"]["uri"],
        envelope["producerMaterialRootRef"]["uri"],
    )
    _, material_uris, file_identities = verify_tree_ref(envelope["producerMaterialRootRef"])
    require(result_path == resolve_uri(envelope["producerMaterialRootRef"]["uri"]).parent / "response.json", "A1_REPLAY_RESULT_ROLE_PATH_DRIFT")
    invocation_path = resolve_uri(run_root + "/producer/invocation.json")
    invocation, _ = read_canonical_document(invocation_path)
    verify_self(invocation, "invocationFingerprint", DOMAINS["invocation"])
    process = verify_process_transcript(
        envelope["producerProcessTranscriptRef"], run_root, "A1_VERIFIER", authority["pin"]["expectedIndependentVerifierRawFingerprint"], authority,
        invocation["runPurpose"],
    )
    require(invocation["runId"] == run_root.removeprefix("run-material/runs/"), "A1_PRODUCER_INVOCATION_RUN_ID_DRIFT")
    require(invocation["commandRef"] == process["transcript"]["commandRef"], "A1_PRODUCER_INVOCATION_COMMAND_DRIFT")
    require(process["stdout"] == result_bytes + b"\n", "A1_OUTER_STDOUT_RESULT_DRIFT")
    require(envelope["observedProcessState"] == process["transcript"]["processState"], "A1_ENVELOPE_PROCESS_STATE_DRIFT")
    require(envelope["observedExitCode"] == process["transcript"]["exitCode"], "A1_ENVELOPE_EXIT_DRIFT")
    require(envelope["observedTerminal"] == result["terminal"], "A1_ENVELOPE_TERMINAL_DRIFT")
    require(envelope["expectedProducerCodeSourceRawFingerprint"] == authority["pin"]["expectedIndependentVerifierRawFingerprint"], "A1_EXPECTED_VERIFIER_DRIFT")
    require(envelope["observedProducerCodeSourceRawFingerprint"] == process["transcript"]["codeSource"]["rawFingerprint"], "A1_OBSERVED_VERIFIER_DRIFT")
    require(result["challengeTrustPinRawFingerprint"] == authority["pinRaw"], "A1_RESULT_PIN_DRIFT")
    require(result["candidateCodeSource"]["rawFingerprint"] == authority["pin"]["expectedImplementationCandidateRawFingerprint"], "A1_RESULT_CANDIDATE_DRIFT")
    require(result["verifierCodeSource"]["rawFingerprint"] == authority["pin"]["expectedIndependentVerifierRawFingerprint"], "A1_RESULT_VERIFIER_DRIFT")
    require(result["replayProfileRef"]["rawFingerprint"] == authority["pin"]["expectedReplayProfileRawFingerprint"], "A1_RESULT_PROFILE_DRIFT")
    require(result["tckDefinitionRef"]["rawFingerprint"] == authority["pin"]["expectedTckDefinitionRawFingerprint"], "A1_RESULT_TCK_DRIFT")
    provider_materialization = verify_provider_materialization(result["providerMaterializationRef"], run_root, authority)
    require(result["providerMaterializationRef"]["uri"] in material_uris, "A1_PROVIDER_MATERIALIZATION_OUTSIDE_TREE")

    cases = [verify_case(slot, run_root, authority) for slot in result["testRuns"]]
    require(result["startedAt"] == process["transcript"]["startedAt"], "A1_REPLAY_STARTED_AT_DRIFT")
    require(result["endedAt"] == process["transcript"]["endedAt"], "A1_REPLAY_ENDED_AT_DRIFT")
    require(all(result["startedAt"] <= case["startedAt"] <= case["endedAt"] <= result["endedAt"] for case in cases), "A1_REPLAY_CHILD_TIME_OUTSIDE_PARENT")
    challenge_ids = [case["challengeId"] for case in cases]
    require(len(challenge_ids) == len(set(challenge_ids)) == 9, "A1_CHALLENGE_ID_REUSED_WITHIN_RUN")
    normal_tck = authority["replayRegistry"]["replayVectors"]
    require(
        [slot["testId"] for slot in result["testRuns"]] == [test["testId"] for test in normal_tck],
        "A1_REPLAY_SLOT_ORDER_DRIFT",
    )
    statuses: list[str] = []
    for slot, expected in zip(result["testRuns"], normal_tck, strict=True):
        require(slot["skipped"] is False, "A1_REPLAY_SLOT_SKIPPED", slot["testId"])
        matches = (
            slot["processExitCode"] == expected["expectedExitCode"]
            and slot["observedTerminal"] == expected["expectedTerminal"]
            and slot["closedReasonCode"] == expected["expectedReasonCode"]
            and slot["mutationVector"] == expected["mutationVector"]
            and slot["expectedMechanism"] == expected["expectedMechanism"]
        )
        expected_status = "PASS" if matches else "FAIL"
        require(slot["status"] == expected_status, "A1_REPLAY_SLOT_STATUS_DRIFT", slot["testId"])
        statuses.append(expected_status)
    passed_count = statuses.count("PASS")
    failed_count = statuses.count("FAIL")
    require(result["testCount"] == len(statuses) == 9, "A1_REPLAY_TEST_COUNT_DRIFT")
    require(result["passedCount"] == passed_count, "A1_REPLAY_PASSED_COUNT_DRIFT")
    require(result["failedCount"] == failed_count, "A1_REPLAY_FAILED_COUNT_DRIFT")
    require(result["skippedCount"] == 0, "A1_REPLAY_SKIPPED_COUNT_DRIFT")
    any_unavailable = any(slot["status"] == "FAIL" and slot["observedTerminal"] == "UNAVAILABLE" for slot in result["testRuns"])
    derived_terminal = "VERIFIED" if failed_count == 0 else "UNAVAILABLE" if any_unavailable else "INVALID"
    derived_reason = {
        "VERIFIED": "A1_REPLAY_VERIFIED",
        "INVALID": "A1_REPLAY_INVALID",
        "UNAVAILABLE": "A1_REPLAY_UNAVAILABLE",
    }[derived_terminal]
    require(result["terminal"] == derived_terminal, "A1_REPLAY_TERMINAL_PRECEDENCE_DRIFT")
    require(result["reasonCode"] == derived_reason, "A1_REPLAY_REASON_PRECEDENCE_DRIFT")
    referenced_uris = {uri for case in cases for uri in case["uris"]}
    referenced_uris.update({
        envelope["replayResultRef"]["uri"],
        envelope["producerProcessTranscriptRef"]["uri"],
        process["transcript"]["commandRef"]["uri"],
        process["transcript"]["processObservationRef"]["uri"],
        process["transcript"]["stdoutRef"]["uri"],
        process["transcript"]["stderrRef"]["uri"],
    })
    require(referenced_uris <= material_uris, "A1_REFERENCED_MATERIAL_OUTSIDE_TREE")
    expected_exit = {"VERIFIED": 0, "INVALID": 2, "UNAVAILABLE": 3}[result["terminal"]]
    require(process["transcript"]["exitCode"] == expected_exit, "A1_REPLAY_OUTCOME_DRIFT")
    return {
        "envelope": envelope,
        "result": result,
        "runRoot": run_root,
        "challenges": challenge_ids,
        "fileIdentities": file_identities,
        "providerMaterialization": provider_materialization,
    }


def verify_harness_replay_proof(authority: dict[str, Any], report_material_uris: set[str]) -> dict[str, Any]:
    envelope_path = RUN_MATERIAL / "reports" / "A1-REPORT-GENERATED-001" / "derived" / "replay-proof-envelope.json"
    stable_file_stat(envelope_path, "A1_HARNESS_REPLAY_PROOF_MISSING")
    require(HARNESS_REPLAY_PROOF_URI in report_material_uris, "A1_HARNESS_REPLAY_PROOF_OUTSIDE_REPORT_ROOT")
    envelope, _ = read_canonical_document(envelope_path)
    return verify_replay_proof_envelope(envelope, authority, HARNESS_REPLAY_RUN_ROOT)


def verify_outer_run(entry: dict[str, Any], authority: dict[str, Any], material_uris: set[str]) -> dict[str, Any]:
    run_root = material_run_root(entry["processTranscriptRef"]["uri"])
    require(entry["runId"] == run_root.removeprefix("run-material/runs/"), "A1_OUTER_ENTRY_RUN_ID_DRIFT")
    refs = [entry[field] for field in ("commandRecordRef", "invocationRecordRef", "responseRef", "processTranscriptRef")]
    verify_same_run_root(run_root, *(ref["uri"] for ref in refs))
    for ref in refs:
        verify_raw_ref(ref)
        require(ref["uri"] in material_uris, "A1_REPORT_REF_OUTSIDE_ROOT", ref["uri"])
    process = verify_process_transcript(
        entry["processTranscriptRef"], run_root, "A1_VERIFIER", authority["pin"]["expectedIndependentVerifierRawFingerprint"], authority,
        entry["runPurpose"],
    )
    require(process["transcript"]["commandRef"] == entry["commandRecordRef"], "A1_OUTER_COMMAND_REF_DRIFT")
    require(entry["processObservationRawFingerprint"] == raw_fingerprint(process["observationBytes"]), "A1_PROCESS_OBSERVATION_FINGERPRINT_MISMATCH")
    require(entry["processExitCode"] == process["transcript"]["exitCode"], "A1_OUTER_EXIT_DRIFT")
    outer_dir = process["stdoutPath"].parent
    invocation_path, invocation_bytes = verify_raw_ref(entry["invocationRecordRef"])
    require(invocation_path == outer_dir / "producer" / "invocation.json", "A1_OUTER_INVOCATION_ROLE_PATH_DRIFT")
    invocation, _ = read_canonical_document(invocation_path)
    verify_self(invocation, "invocationFingerprint", DOMAINS["invocation"])
    require(invocation["runId"] == run_root.removeprefix("run-material/runs/"), "A1_OUTER_INVOCATION_RUN_ID_DRIFT")
    require(invocation["commandRef"] == entry["commandRecordRef"], "A1_OUTER_INVOCATION_COMMAND_DRIFT")
    require(invocation["challengeTrustPinRawFingerprint"] == authority["pinRaw"], "A1_OUTER_INVOCATION_PIN_DRIFT")
    response_path, response_bytes = verify_raw_ref(entry["responseRef"])
    require(response_path == outer_dir / "response.json", "A1_OUTER_RESPONSE_ROLE_PATH_DRIFT")
    response, _ = read_canonical_document(response_path)
    if "runId" in response:
        require(response["runId"] == run_root.removeprefix("run-material/runs/"), "A1_OUTER_RESPONSE_RUN_ID_DRIFT")
    require(process["stdout"] == response_bytes + b"\n", "A1_OUTER_STDOUT_RESPONSE_DRIFT")
    if response["messageVersion"] == "resource-gateway.capability-studio.gate-a.replay-verification-result.v1":
        verify_self(response, "resultFingerprint", DOMAINS["replay"])
        verify_provider_materialization(response["providerMaterializationRef"], run_root, authority)
        require(response["providerMaterializationRef"]["uri"] in material_uris, "A1_PROVIDER_MATERIALIZATION_OUTSIDE_TREE")
        require(entry["observedTerminal"] == response["terminal"], "A1_OUTER_TERMINAL_DRIFT")
        require(entry["closedReasonCode"] == response["reasonCode"], "A1_OUTER_REASON_DRIFT")
    else:
        require(response["messageVersion"] == "resource-gateway.capability-studio.gate-a.a1-bootstrap-response.v1", "A1_OUTER_RESPONSE_KIND_DRIFT")
        verify_self(response, "responseFingerprint", DOMAINS["bootstrap"])
        require(response["bootstrapStatus"] == "REJECTED", "A1_OUTER_BOOTSTRAP_STATUS_DRIFT")
        require(entry["observedTerminal"] == "INVALID" and entry["processExitCode"] == 2, "A1_OUTER_BOOTSTRAP_OUTCOME_DRIFT")
    require(entry["commandRawFingerprint"] == raw_fingerprint(stable_file_read(resolve_uri(entry["commandRecordRef"]["uri"]))[0]), "A1_OUTER_COMMAND_RAW_DRIFT")
    require(entry["invocationRawFingerprint"] == raw_fingerprint(invocation_bytes), "A1_OUTER_INVOCATION_RAW_DRIFT")
    require(entry["responseRawFingerprint"] == raw_fingerprint(response_bytes), "A1_OUTER_RESPONSE_RAW_DRIFT")
    require(entry["stdoutRawFingerprint"] == raw_fingerprint(process["stdout"]), "A1_OUTER_STDOUT_RAW_DRIFT")
    require(entry["stderrRawFingerprint"] == raw_fingerprint(process["stderr"]), "A1_OUTER_STDERR_RAW_DRIFT")
    require(entry["transcriptRawFingerprint"] == raw_fingerprint(process["transcriptBytes"]), "A1_OUTER_TRANSCRIPT_RAW_DRIFT")
    require(entry["startedAt"] == process["transcript"]["startedAt"], "A1_OUTER_STARTED_AT_DRIFT")
    require(entry["endedAt"] == process["transcript"]["endedAt"], "A1_OUTER_ENDED_AT_DRIFT")
    require(entry["timedOut"] == process["transcript"]["timedOut"], "A1_OUTER_TIMEOUT_DRIFT")
    require(entry["cancelled"] == process["transcript"]["cancelled"], "A1_OUTER_CANCELLED_DRIFT")
    derived_raw: dict[str, Any] = {}
    for field in ("derivedPinRef", "derivedVerifierArtifactRef", "derivedCandidateArtifactRef"):
        if entry[field] is not None:
            _, derived_bytes = verify_raw_ref(entry[field])
            require(entry[field]["uri"] in material_uris, "A1_DERIVED_REF_OUTSIDE_ROOT", entry[field]["uri"])
            derived_raw[field] = raw_fingerprint(derived_bytes)
        else:
            derived_raw[field] = None
    aggregate = {
        "runId": entry["runId"],
        "commandRawFingerprint": entry["commandRawFingerprint"],
        "invocationRawFingerprint": raw_fingerprint(invocation_bytes),
        "responseRawFingerprint": raw_fingerprint(response_bytes),
        "processObservationRawFingerprint": raw_fingerprint(process["observationBytes"]),
        "stdoutRawFingerprint": raw_fingerprint(process["stdout"]),
        "stderrRawFingerprint": raw_fingerprint(process["stderr"]),
        "transcriptRawFingerprint": raw_fingerprint(process["transcriptBytes"]),
        "derivedPinRawFingerprint": derived_raw["derivedPinRef"],
        "derivedVerifierArtifactRawFingerprint": derived_raw["derivedVerifierArtifactRef"],
        "derivedCandidateArtifactRawFingerprint": derived_raw["derivedCandidateArtifactRef"],
    }
    return {"entry": entry, "process": process, "invocation": invocation, "response": response, "aggregate": aggregate}


def verify_special_projection(
    slot: dict[str, Any],
    outer: dict[str, Any],
    material_uris: set[str],
    authority: dict[str, Any],
) -> dict[str, Any]:
    entry = outer["entry"]
    expected_refs = {
        "commandRecordRef": entry["commandRecordRef"],
        "requestRef": entry["invocationRecordRef"],
        "responseRef": entry["responseRef"],
        "processTranscriptRef": entry["processTranscriptRef"],
    }
    for field, expected in expected_refs.items():
        require(slot[field] == expected, "A1_SPECIAL_PROJECTION_REF_DRIFT", field)
        require(slot[field]["uri"] in material_uris, "A1_SPECIAL_PROJECTION_OUTSIDE_ROOT", field)
    require(slot["commandFingerprint"] == outer["process"]["command"]["commandFingerprint"], "A1_SPECIAL_COMMAND_FINGERPRINT_DRIFT")
    require(slot["requestFingerprint"] == outer["invocation"]["invocationFingerprint"], "A1_SPECIAL_INVOCATION_FINGERPRINT_DRIFT")
    require(slot["responseFingerprint"] == outer["response"]["responseFingerprint"], "A1_SPECIAL_RESPONSE_FINGERPRINT_DRIFT")
    require(slot["processObservationRawFingerprint"] == entry["processObservationRawFingerprint"], "A1_SPECIAL_OBSERVATION_DRIFT")
    require(slot["transcriptRawFingerprint"] == entry["transcriptRawFingerprint"], "A1_SPECIAL_TRANSCRIPT_DRIFT")
    require(slot["processExitCode"] == entry["processExitCode"] == 2, "A1_SPECIAL_EXIT_DRIFT")
    require(slot["observedTerminal"] == entry["observedTerminal"] == "INVALID", "A1_SPECIAL_TERMINAL_DRIFT")
    require(slot["closedReasonCode"] == entry["closedReasonCode"], "A1_SPECIAL_REASON_DRIFT")
    require(slot["targetArtifactRawFingerprint"] == authority["pin"]["expectedIndependentVerifierRawFingerprint"], "A1_SPECIAL_TARGET_AUTHORITY_DRIFT")
    require(slot["inputPinRawFingerprint"] == authority["pinRaw"], "A1_SPECIAL_INPUT_PIN_DRIFT")
    require(slot["harnessRawFingerprint"] == authority["pin"]["expectedConformanceHarnessRawFingerprint"], "A1_SPECIAL_HARNESS_DRIFT")
    return {
        "observationRaw": slot["processObservationRawFingerprint"],
        "uris": [ref["uri"] for ref in expected_refs.values()],
        "projection": True,
        "startedAt": outer["process"]["transcript"]["startedAt"],
        "endedAt": outer["process"]["transcript"]["endedAt"],
        "raw": {
            "command": entry["commandRawFingerprint"],
            "request": entry["invocationRawFingerprint"],
            "response": entry["responseRawFingerprint"],
            "observation": entry["processObservationRawFingerprint"],
            "stdout": entry["stdoutRawFingerprint"],
            "stderr": entry["stderrRawFingerprint"],
            "transcript": entry["transcriptRawFingerprint"],
        },
    }


def verify_independent_result(authority: dict[str, Any]) -> dict[str, Any]:
    verify_tck_normal_projection(authority)
    result = read_json(HERE / "valid-independent-verification-result.json")
    verify_self(result, "resultFingerprint", DOMAINS["independent"])
    report_tree_ref = {
        "uri": f"run-material/reports/{result['resultId']}/material-root.tree",
        "fingerprint": result["runMaterialRootFingerprint"],
    }
    _, material_uris, _ = verify_tree_ref(report_tree_ref)
    pin = authority["pin"]
    bindings = {
        "challengeTrustPinRawFingerprint": authority["pinRaw"],
        "candidateRawFingerprint": pin["expectedImplementationCandidateRawFingerprint"],
        "candidateSpiRawFingerprint": pin["expectedCandidateSpiArtifactRawFingerprint"],
        "verifierRawFingerprint": pin["expectedIndependentVerifierRawFingerprint"],
        "harnessRawFingerprint": pin["expectedConformanceHarnessRawFingerprint"],
        "providerRawFingerprint": pin["expectedTckProviderRawFingerprint"],
        "replayProfileRawFingerprint": pin["expectedReplayProfileRawFingerprint"],
        "harnessProfileRawFingerprint": pin["expectedHarnessProfileRawFingerprint"],
        "schemaSetRawFingerprint": authority["schemaSetRaw"],
        "tckRawFingerprint": pin["expectedTckDefinitionRawFingerprint"],
    }
    for field, expected in bindings.items():
        require(result[field] == expected, "A1_REPORT_AUTHORITY_DRIFT", field)
    require(result["verifierCodeSource"]["rawFingerprint"] == bindings["verifierRawFingerprint"], "A1_REPORT_VERIFIER_ORIGIN_DRIFT")
    require(result["harnessCodeSource"]["rawFingerprint"] == bindings["harnessRawFingerprint"], "A1_REPORT_HARNESS_ORIGIN_DRIFT")

    outer_runs = [verify_outer_run(entry, authority, material_uris) for entry in result["verificationProcessRuns"]]
    harness_replay_proof = verify_harness_replay_proof(authority, material_uris)
    require(
        harness_replay_proof["envelope"]["replayResultRef"] == outer_runs[0]["entry"]["responseRef"],
        "A1_HARNESS_REPLAY_PROOF_RESULT_DRIFT",
    )
    require(
        harness_replay_proof["envelope"]["producerProcessTranscriptRef"] == outer_runs[0]["entry"]["processTranscriptRef"],
        "A1_HARNESS_REPLAY_PROOF_TRANSCRIPT_DRIFT",
    )
    require(len({entry["processTranscriptRef"]["uri"] for entry in result["verificationProcessRuns"]}) == 5, "A1_OUTER_TRANSCRIPT_REF_REUSED")
    special_outer_index = {
        "VERIFIER_DIGEST_MUTATION_REJECTED": 1,
        "REGISTRY_MUTATION_REJECTED": 2,
        "VERIFIER_TCK_MISMATCH_REJECTED": 3,
    }
    cases = []
    for slot in result["testRuns"]:
        if slot["testId"] in special_outer_index:
            case = verify_special_projection(slot, outer_runs[special_outer_index[slot["testId"]]], material_uris, authority)
        else:
            run_root = material_run_root(slot["processTranscriptRef"]["uri"])
            case = verify_case(slot, run_root, authority)
        require(set(case["uris"]) <= material_uris, "A1_CASE_REF_OUTSIDE_REPORT_ROOT")
        cases.append(case)
    require(len(cases) == 12, "A1_REPORT_TEST_COUNT_DRIFT")
    tck_tests = read_json(HERE / "valid-tck.json")["tests"]
    require([slot["testId"] for slot in result["testRuns"]] == [test["testId"] for test in tck_tests], "A1_REPORT_SLOT_ORDER_DRIFT")
    for slot, expected in zip(result["testRuns"], tck_tests, strict=True):
        require(slot["status"] == "PASS" and slot["skipped"] is False, "A1_REPORT_SLOT_STATUS_DRIFT", slot["testId"])
        require(slot["processExitCode"] == expected["expectedExitCode"], "A1_REPORT_SLOT_EXIT_DRIFT", slot["testId"])
        require(slot["observedTerminal"] == expected["expectedTerminal"], "A1_REPORT_SLOT_TERMINAL_DRIFT", slot["testId"])
        require(slot["closedReasonCode"] == expected["expectedReasonCode"], "A1_REPORT_SLOT_REASON_DRIFT", slot["testId"])
        if slot["testId"] not in special_outer_index:
            vector = replay_vector_for(authority, slot["testId"])
            require(slot["mutationVector"] == vector["mutationVector"], "A1_REPORT_VECTOR_MUTATION_DRIFT", slot["testId"])
            require(slot["expectedMechanism"] == vector["expectedMechanism"], "A1_REPORT_VECTOR_MECHANISM_DRIFT", slot["testId"])
            require(slot["processExitCode"] == vector["expectedExitCode"], "A1_REPORT_VECTOR_EXIT_DRIFT", slot["testId"])
            require(slot["observedTerminal"] == vector["expectedTerminal"], "A1_REPORT_VECTOR_TERMINAL_DRIFT", slot["testId"])
            require(slot["closedReasonCode"] == vector["expectedReasonCode"], "A1_REPORT_VECTOR_REASON_DRIFT", slot["testId"])
    for field in ("commandRecordRef", "requestRef", "responseRef", "processTranscriptRef"):
        uris = [slot[field]["uri"] for slot in result["testRuns"]]
        require(len(uris) == len(set(uris)), "A1_INDEPENDENT_REF_REUSED", field)
    outer_observations = [fingerprint_value(entry["processObservationRawFingerprint"]) for entry in result["verificationProcessRuns"]]
    require(len(outer_observations) == len(set(outer_observations)), "A1_PROCESS_OBSERVATION_FINGERPRINT_REUSED")
    normal_slots = [slot for slot in result["testRuns"] if slot["testId"] not in special_outer_index]
    normal_challenges = [case["challengeId"] for case in cases if "challengeId" in case]
    require(len(normal_challenges) == len(set(normal_challenges)) == 9, "A1_CHALLENGE_ID_REUSED_WITHIN_RUN")
    normal_observations = [fingerprint_value(slot["processObservationRawFingerprint"]) for slot in normal_slots]
    require(len(normal_observations) == len(set(normal_observations)), "A1_PROCESS_OBSERVATION_FINGERPRINT_REUSED")
    require(set(normal_observations).isdisjoint(outer_observations), "A1_PROCESS_OBSERVATION_FINGERPRINT_REUSED")
    normal_case_indexes = [index for index, slot in enumerate(result["testRuns"]) if slot["testId"] not in special_outer_index]
    starts = [outer["process"]["transcript"]["startedAt"] for outer in outer_runs]
    ends = [outer["process"]["transcript"]["endedAt"] for outer in outer_runs]
    starts.extend(cases[index]["startedAt"] for index in normal_case_indexes)
    ends.extend(cases[index]["endedAt"] for index in normal_case_indexes)
    require(result["startedAt"] == min(starts), "A1_REPORT_STARTED_AT_CLOSURE_DRIFT")
    require(result["endedAt"] == max(ends), "A1_REPORT_ENDED_AT_CLOSURE_DRIFT")
    for test_id, outer_index in special_outer_index.items():
        slot = next(item for item in result["testRuns"] if item["testId"] == test_id)
        require(
            slot["processObservationRawFingerprint"] == result["verificationProcessRuns"][outer_index]["processObservationRawFingerprint"],
            "A1_SPECIAL_OBSERVATION_DRIFT",
        )
    guard_ref = result["mandatoryGuards"]["providerNamespaceCollision"]["runRef"]
    verify_raw_ref(guard_ref)
    require(guard_ref == result["verificationProcessRuns"][4]["processTranscriptRef"], "A1_PROVIDER_GUARD_RUN_DRIFT")

    aggregate_path = RUN_MATERIAL / "reports" / result["resultId"] / "derived" / "process-material-aggregate.json"
    aggregate_doc, _ = read_canonical_document(aggregate_path, typed=False)
    require(
        aggregate_doc["aggregateFingerprint"] == commitment(aggregate_doc["aggregate"], DOMAINS["process_aggregate"], "AGGREGATE_COMMITMENT"),
        "A1_PROCESS_AGGREGATE_FINGERPRINT_DRIFT",
    )
    aggregate = aggregate_doc["aggregate"]
    require(len(aggregate["verificationProcessRuns"]) == 5 and len(aggregate["testRuns"]) == 12, "A1_PROCESS_AGGREGATE_COUNT_DRIFT")
    expected_process_aggregate = {
        "verificationProcessRuns": [outer["aggregate"] for outer in outer_runs],
        "testRuns": [
            {
                "testId": slot["testId"],
                "commandRawFingerprint": case["raw"]["command"],
                "requestRawFingerprint": case["raw"]["request"],
                "responseRawFingerprint": case["raw"]["response"],
                "processObservationRawFingerprint": case["raw"]["observation"],
                "stdoutRawFingerprint": case["raw"]["stdout"],
                "stderrRawFingerprint": case["raw"]["stderr"],
                "transcriptRawFingerprint": case["raw"]["transcript"],
                "derivedPinRawFingerprint": None,
                "derivedVerifierArtifactRawFingerprint": None,
                "derivedCandidateArtifactRawFingerprint": None,
            }
            for slot, case in zip(result["testRuns"], cases, strict=True)
        ],
    }
    require(aggregate == expected_process_aggregate, "A1_PROCESS_AGGREGATE_MATERIAL_DRIFT")

    evidence_path = RUN_MATERIAL / "reports" / result["resultId"] / "derived" / "test-evidence-aggregate.json"
    evidence, _ = read_canonical_document(evidence_path, typed=False)
    require(evidence["aggregateFingerprint"] == commitment(evidence["entries"], DOMAINS["evidence_aggregate"], "AGGREGATE_COMMITMENT"), "A1_EVIDENCE_AGGREGATE_FINGERPRINT_DRIFT")
    expected_evidence = [
        {"testId": slot["testId"], "evidenceRawFingerprint": slot["transcriptRawFingerprint"]}
        for slot in result["testRuns"]
    ]
    require(evidence["entries"] == expected_evidence, "A1_EVIDENCE_AGGREGATE_DRIFT")
    return {"result": result, "outer": outer_runs, "cases": cases}


def verify_independent_proof_envelope(name: str, authority: dict[str, Any]) -> dict[str, Any]:
    envelope = read_json(HERE / name)
    verify_self(envelope, "envelopeFingerprint", DOMAINS["independent_envelope"])
    require(envelope["challengeTrustPinRawFingerprint"] == authority["pinRaw"], "A1_INDEPENDENT_ENVELOPE_PIN_DRIFT")
    require(
        envelope["harnessProfileRawFingerprint"] == authority["pin"]["expectedHarnessProfileRawFingerprint"],
        "A1_INDEPENDENT_ENVELOPE_PROFILE_DRIFT",
    )
    run_root = material_run_root(envelope["independentResultRef"]["uri"])
    verify_same_run_root(
        run_root,
        envelope["independentResultRef"]["uri"],
        envelope["harnessProcessTranscriptRef"]["uri"],
        envelope["harnessMaterialRootRef"]["uri"],
    )
    _, material_uris, file_identities = verify_tree_ref(envelope["harnessMaterialRootRef"])
    result_path, result_bytes = verify_raw_ref(envelope["independentResultRef"])
    result, _ = read_canonical_document(result_path)
    require(
        result["messageVersion"] == envelope["independentResultMessageVersion"],
        "A1_INDEPENDENT_RESULT_MESSAGE_VERSION_DRIFT",
    )
    verify_self(result, "resultFingerprint", DOMAINS["independent"])
    process = verify_process_transcript(
        envelope["harnessProcessTranscriptRef"],
        run_root,
        "CONFORMANCE_HARNESS",
        authority["pin"]["expectedConformanceHarnessRawFingerprint"],
        authority,
    )
    require(
        process["transcript"]["messageVersion"] == envelope["harnessProcessMessageVersion"],
        "A1_HARNESS_PROCESS_MESSAGE_VERSION_DRIFT",
    )
    invocation_ref = process["transcript"]["invocationRef"]
    verify_same_run_root(run_root, invocation_ref["uri"])
    invocation_path, _ = verify_raw_ref(invocation_ref)
    require(invocation_path == result_path.parent / "producer" / "invocation.json", "A1_HARNESS_INVOCATION_ROLE_PATH_DRIFT")
    invocation, _ = read_canonical_document(invocation_path)
    verify_self(invocation, "invocationFingerprint", DOMAINS["harness_invocation"])
    require(invocation["runId"] == run_root.removeprefix("run-material/runs/"), "A1_HARNESS_INVOCATION_RUN_ID_DRIFT")
    require(invocation["commandRef"] == process["transcript"]["commandRef"], "A1_HARNESS_INVOCATION_COMMAND_DRIFT")
    require(invocation["challengeTrustPinRawFingerprint"] == authority["pinRaw"], "A1_HARNESS_PIN_DRIFT")
    require(process["stdout"] == result_bytes + b"\n", "A1_HARNESS_STDOUT_REPORT_DRIFT")
    require(envelope["observedProcessState"] == process["transcript"]["processState"], "A1_HARNESS_PROCESS_STATE_DRIFT")
    require(envelope["observedExitCode"] == process["transcript"]["exitCode"], "A1_HARNESS_EXIT_DRIFT")
    require(
        envelope["expectedHarnessCodeSourceRawFingerprint"] == authority["pin"]["expectedConformanceHarnessRawFingerprint"],
        "A1_EXPECTED_HARNESS_DRIFT",
    )
    require(
        envelope["observedHarnessCodeSourceRawFingerprint"] == process["transcript"]["codeSource"]["rawFingerprint"],
        "A1_OBSERVED_HARNESS_DRIFT",
    )
    referenced_uris = {
        envelope["independentResultRef"]["uri"],
        envelope["harnessProcessTranscriptRef"]["uri"],
        process["transcript"]["commandRef"]["uri"],
        process["transcript"]["invocationRef"]["uri"],
        process["transcript"]["processObservationRef"]["uri"],
        process["transcript"]["stdoutRef"]["uri"],
        process["transcript"]["stderrRef"]["uri"],
    }
    require(referenced_uris <= material_uris, "A1_HARNESS_MATERIAL_OUTSIDE_ROOT")
    canonical_report = canonical_bytes(read_json(HERE / "valid-independent-verification-result.json"))
    require(result_bytes == canonical_report, "A1_INDEPENDENT_RESULT_TEMPLATE_DRIFT")
    return {
        "envelope": envelope,
        "result": result,
        "runRoot": run_root,
        "fileIdentities": file_identities,
    }


def verify_harness_bootstrap_attack(authority: dict[str, Any]) -> None:
    try:
        verify_independent_proof_envelope("invalid-independent-proof-envelope-harness-bootstrap.json", authority)
    except MaterialError as error:
        require(error.code == "A1_HARNESS_STDOUT_REPORT_DRIFT", "A1_HARNESS_BOOTSTRAP_ATTACK_WRONG_FAILURE", error.code)
        return
    raise MaterialError("A1_HARNESS_BOOTSTRAP_ATTACK_ACCEPTED")


def verify_early_bootstrap_provider_attack() -> None:
    run_root = RUN_MATERIAL / "runs" / "RUN-A1-INDEPENDENT-DIGEST-001"
    inventory = physical_inventory(run_root)
    actual_paths = {
        item.relative_to(run_root).as_posix()
        for item in inventory.files
        if item.name != "material-root.tree"
    }
    try:
        verify_run_material_layout(run_root, actual_paths | {"producer/provider-materialization.json"})
    except MaterialError as error:
        require(error.code == "A1_EARLY_BOOTSTRAP_PROVIDER_FORBIDDEN", "A1_EARLY_BOOTSTRAP_PROVIDER_ATTACK_WRONG_FAILURE", error.code)
        return
    raise MaterialError("A1_EARLY_BOOTSTRAP_PROVIDER_ATTACK_ACCEPTED")


def verify_parent_unavailable_attack(authority: dict[str, Any]) -> None:
    envelope = read_json(HERE / "invalid-replay-proof-envelope-parent-unavailable.json")
    verify_self(envelope, "envelopeFingerprint", DOMAINS["envelope"])
    run_root = material_run_root(envelope["producerProcessTranscriptRef"]["uri"])
    try:
        verify_process_transcript(
            envelope["producerProcessTranscriptRef"], run_root, "A1_VERIFIER", authority["pin"]["expectedIndependentVerifierRawFingerprint"], authority,
            "NORMAL_A1",
        )
    except MaterialError as error:
        require(error.code == "A1_PROCESS_NOT_COMPLETED", "A1_PARENT_UNAVAILABLE_ATTACK_WRONG_FAILURE", error.code)
        return
    raise MaterialError("A1_PARENT_UNAVAILABLE_ATTACK_ACCEPTED")


def verify_abnormal_attempts(authority: dict[str, Any]) -> None:
    expected = {item["event"]: item for item in authority["abnormalTransitions"]}
    names = sorted(HERE.glob("valid-abnormal-attempt-*.json"))
    require(len(names) == len(expected), "A1_ABNORMAL_ATTEMPT_COUNT_DRIFT")
    events: list[str] = []
    for path in names:
        attempt = read_json(path)
        validate_typed_document(attempt, path)
        verify_self(attempt, "attemptFingerprint", DOMAINS["abnormal_attempt"])
        event = attempt["event"]
        require(event in expected, "A1_ABNORMAL_EVENT_UNKNOWN", event)
        require(event not in events, "A1_ABNORMAL_EVENT_REUSED", event)
        events.append(event)
        policy = expected[event]
        for field in (
            "stopRemainingSlots",
            "drainStreams",
            "reapProcessTree",
            "formedMaterialPolicy",
            "remainingMaterialPolicy",
            "resultPolicy",
            "stdoutPolicy",
            "harnessProjection",
        ):
            require(attempt[field] == policy[field], "A1_ABNORMAL_POLICY_DRIFT", f"{event}.{field}")
        require(attempt["firstErrorReason"] == event, "A1_ABNORMAL_FIRST_ERROR_REASON_DRIFT", event)
        require(attempt["a1ExitCode"] == policy["a1ExitCode"], "A1_ABNORMAL_A1_EXIT_DRIFT", event)
        require(attempt["semanticResultRef"] is None, "A1_ABNORMAL_RESULT_PRESENT", event)
        require(attempt["laterMaterialRefs"] == [], "A1_ABNORMAL_LATER_MATERIAL_DECLARED", event)

        refs = attempt["formedMaterialRefs"]
        ref_uris = [ref["uri"] for ref in refs]
        require(len(ref_uris) == len(set(ref_uris)), "A1_ABNORMAL_MATERIAL_REF_REUSED", event)
        folder_uri = f"run-material/abnormal-attempts/{attempt['attemptId']}"
        require(all(uri.startswith(folder_uri + "/") for uri in ref_uris), "A1_ABNORMAL_MATERIAL_ROOT_DRIFT", event)
        folder = resolve_uri(attempt["commandRef"]["uri"]).parent
        require(folder == resolve_uri(attempt["processTranscriptRef"]["uri"]).parent, "A1_ABNORMAL_PROCESS_ROOT_DRIFT", event)
        require(
            {item.relative_to(folder).as_posix() for item in physical_files(folder)}
            == {pathlib.PurePosixPath(uri).name for uri in ref_uris},
            "A1_ABNORMAL_LATER_MATERIAL_PRESENT",
            event,
        )
        require(set(ref_uris) == {attempt[field]["uri"] for field in ("commandRef", "processObservationRef", "processTranscriptRef")} | {
            f"{folder_uri}/stdout",
            f"{folder_uri}/stderr",
        }, "A1_ABNORMAL_FORMED_MATERIAL_CLOSURE_DRIFT", event)
        for ref in refs:
            verify_raw_ref(ref)

        command_path, _ = verify_raw_ref(attempt["commandRef"])
        observation_path, _ = verify_raw_ref(attempt["processObservationRef"])
        transcript_path, transcript_bytes = verify_raw_ref(attempt["processTranscriptRef"])
        require(command_path.name == "command.json", "A1_ABNORMAL_COMMAND_ROLE_DRIFT", event)
        require(observation_path.name == "process-observation.json", "A1_ABNORMAL_OBSERVATION_ROLE_DRIFT", event)
        require(transcript_path.name == "process-transcript.json", "A1_ABNORMAL_TRANSCRIPT_ROLE_DRIFT", event)
        transcript, _ = read_canonical_document(transcript_path)
        verify_self(transcript, "transcriptFingerprint", DOMAINS["transcript"])
        observation, _ = read_canonical_document(observation_path)
        command, _ = read_canonical_document(command_path)
        require(transcript["runId"] == command["runId"] == observation["runId"], "A1_ABNORMAL_RUN_ID_DRIFT", event)
        require(transcript["runId"] == attempt["runId"], "A1_ABNORMAL_ATTEMPT_RUN_ID_DRIFT", event)
        require(transcript["exitCode"] == attempt["observedExitCode"] == attempt["a1ExitCode"], "A1_ABNORMAL_TRANSCRIPT_EXIT_DRIFT", event)
        require(transcript["processState"] == attempt["observedProcessState"], "A1_ABNORMAL_TRANSCRIPT_STATE_DRIFT", event)
        require(transcript["timedOut"] == attempt["observedTimedOut"], "A1_ABNORMAL_TRANSCRIPT_TIMEOUT_DRIFT", event)
        require(transcript["cancelled"] == attempt["observedCancelled"], "A1_ABNORMAL_TRANSCRIPT_CANCEL_DRIFT", event)
        require(raw_fingerprint(transcript_bytes) == attempt["processTranscriptRef"]["rawFingerprint"], "A1_ABNORMAL_TRANSCRIPT_RAW_DRIFT", event)

        stdout = stable_file_read(resolve_uri(f"{folder_uri}/stdout"))[0]
        stderr = stable_file_read(resolve_uri(f"{folder_uri}/stderr"))[0]
        require(stdout == stderr == b"", "A1_ABNORMAL_STREAM_NOT_EMPTY", event)
        if event == "STDOUT_TRUNCATED":
            require(
                observation["stdoutCapture"]["overflow"] and not observation["stdoutCapture"]["complete"],
                "A1_ABNORMAL_TRUNCATION_OBSERVATION_MISSING",
            )
        else:
            require(
                not observation["stdoutCapture"]["overflow"] and observation["stdoutCapture"]["complete"],
                "A1_ABNORMAL_UNEXPECTED_OUTPUT_TRUNCATION",
                event,
            )
        if event == "PROCESS_TREE_RESIDUE":
            require(
                not observation["processTree"]["processTreeQuiescent"]
                and observation["processTree"]["descendantsAfterTermination"],
                "A1_ABNORMAL_TREE_RESIDUE_OBSERVATION_MISSING",
            )
        else:
            require(
                observation["processTree"]["processTreeQuiescent"]
                and not observation["processTree"]["descendantsAfterTermination"],
                "A1_ABNORMAL_UNEXPECTED_TREE_RESIDUE",
                event,
            )
        require(
            attempt["codeSourceObservationAvailable"] == (event != "CODESOURCE_OBSERVATION_UNAVAILABLE"),
            "A1_ABNORMAL_CODESOURCE_AVAILABILITY_DRIFT",
            event,
        )
    require(sorted(events) == sorted(expected), "A1_ABNORMAL_EVENT_SET_DRIFT")


def verify_real_run_material_attacks(authority: dict[str, Any]) -> list[str]:
    failures: list[str] = []
    vectors = {vector["vectorId"]: vector for vector in read_json(RUN_MATERIAL_ATTACKS)}

    def execute(vector_id: str, attack: Any) -> None:
        vector = vectors[vector_id]
        stable_file_stat(resolve_uri(vector["source"]), "A1_ATTACK_SOURCE_MISSING")
        try:
            attack()
        except MaterialError as error:
            if error.code != vector["expectedCode"]:
                failures.append(f"{vector_id}: expected {vector['expectedCode']}, got {error.code}")
            else:
                print(f"run material attack matched: {vector_id} -> {error.code}")
            return
        failures.append(f"{vector_id}: attack was accepted")

    def response_provider_origin_rebound() -> None:
        response_path = resolve_uri(vectors["RESPONSE_PROVIDER_ORIGIN_REBOUND"]["source"])
        response = read_json(response_path)
        transcript = read_json(response_path.parent / "process-transcript.json")
        observation_path, _ = verify_raw_ref(transcript["processObservationRef"])
        observation = read_json(observation_path)
        provider_origin = next(item for item in observation["admittedClassOrigins"] if item["role"] == "TCK_PROVIDER")
        response["authorityProviderFqcn"] = "com.example.ReboundProvider"
        response["responseFingerprint"] = document_fingerprint(response, "responseFingerprint", DOMAINS["response"])
        verify_self(response, "responseFingerprint", DOMAINS["response"])
        require(response["authorityProviderFqcn"] == provider_origin["binaryName"], "A1_RESPONSE_PROVIDER_ORIGIN_DRIFT")

    def replay_cross_run_response_stitching() -> None:
        verified = read_json(HERE / "valid-replay-verification-result.json")
        unavailable = read_json(HERE / "valid-replay-verification-result-unavailable.json")
        slot = copy.deepcopy(verified["testRuns"][1])
        slot["responseRef"] = copy.deepcopy(unavailable["testRuns"][1]["responseRef"])
        slot["responseFingerprint"] = copy.deepcopy(unavailable["testRuns"][1]["responseFingerprint"])
        verify_case(slot, "run-material/runs/RUN-A1-REPLAY-VERIFIED-001", authority)

    def independent_observation_rebound() -> None:
        report = read_json(HERE / "valid-independent-verification-result.json")
        slot = copy.deepcopy(report["testRuns"][0])
        slot["processObservationRawFingerprint"] = copy.deepcopy(report["testRuns"][1]["processObservationRawFingerprint"])
        verify_case(slot, material_run_root(slot["processTranscriptRef"]["uri"]), authority)

    def independent_exit_rebound() -> None:
        report = read_json(HERE / "valid-independent-verification-result.json")
        slot = copy.deepcopy(report["testRuns"][0])
        slot["processExitCode"] = 0
        report["testRuns"][0] = slot
        report["resultFingerprint"] = document_fingerprint(report, "resultFingerprint", DOMAINS["independent"])
        verify_self(report, "resultFingerprint", DOMAINS["independent"])
        verify_case(slot, material_run_root(slot["processTranscriptRef"]["uri"]), authority)

    def independent_provider_authority_rebound() -> None:
        report = read_json(HERE / "valid-independent-verification-result.json")
        report["providerRawFingerprint"] = copy.deepcopy(authority["pin"]["expectedImplementationCandidateRawFingerprint"])
        report["resultFingerprint"] = document_fingerprint(report, "resultFingerprint", DOMAINS["independent"])
        verify_self(report, "resultFingerprint", DOMAINS["independent"])
        require(
            report["providerRawFingerprint"] == authority["pin"]["expectedTckProviderRawFingerprint"],
            "A1_REPORT_AUTHORITY_DRIFT",
        )

    def independent_response_ref_reuse() -> None:
        report = read_json(HERE / "valid-independent-verification-result.json")
        report["testRuns"][1]["responseRef"] = copy.deepcopy(report["testRuns"][0]["responseRef"])
        report["resultFingerprint"] = document_fingerprint(report, "resultFingerprint", DOMAINS["independent"])
        verify_self(report, "resultFingerprint", DOMAINS["independent"])
        uris = [slot["responseRef"]["uri"] for slot in report["testRuns"]]
        require(len(uris) == len(set(uris)), "A1_INDEPENDENT_REF_REUSED")

    def harness_replay_proof_digest_rebound() -> None:
        envelope = read_json(resolve_uri(vectors["HARNESS_REPLAY_PROOF_DIGEST_REBOUND"]["source"]))
        envelope["replayResultRef"]["rawFingerprint"] = copy.deepcopy(
            envelope["producerProcessTranscriptRef"]["rawFingerprint"]
        )
        envelope["envelopeFingerprint"] = document_fingerprint(envelope, "envelopeFingerprint", DOMAINS["envelope"])
        verify_replay_proof_envelope(envelope, authority, HARNESS_REPLAY_RUN_ROOT)

    def replay_proof_missing_message_version() -> None:
        envelope = read_json(resolve_uri(vectors["REPLAY_PROOF_MISSING_MESSAGE_VERSION"]["source"]))
        del envelope["messageVersion"]
        envelope["envelopeFingerprint"] = document_fingerprint(envelope, "envelopeFingerprint", DOMAINS["envelope"])
        verify_self(envelope, "envelopeFingerprint", DOMAINS["envelope"])
        validate_typed_document(envelope, resolve_uri(vectors["REPLAY_PROOF_MISSING_MESSAGE_VERSION"]["source"]))

    def run_tree_hard_link_alias() -> None:
        envelope = read_json(HERE / "valid-replay-proof-envelope.json")
        run_root = resolve_uri(envelope["producerProcessTranscriptRef"]["uri"]).parent
        source = run_root / "command.json"
        target = run_root / "producer" / "invocation.json"
        original_target = stable_file_read(target)[0]
        _unlink_componentwise(target)
        try:
            source_parent_fd = _open_componentwise(source.parent, os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0))
            target_parent_fd = _open_componentwise(target.parent, os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0))
            try:
                os.link(source.name, target.name, src_dir_fd=source_parent_fd, dst_dir_fd=target_parent_fd, follow_symlinks=False)
            finally:
                os.close(source_parent_fd)
                os.close(target_parent_fd)
            verify_tree_ref(envelope["producerMaterialRootRef"])
        finally:
            try:
                _unlink_componentwise(target)
            except MaterialError:
                pass
            _write_new_componentwise(target, original_target)

    def material_file_budget() -> None:
        with tempfile.TemporaryDirectory(prefix=".material-file-budget-", dir=HERE) as temporary:
            path = pathlib.Path(temporary) / "payload"
            _write_new_componentwise(path, b"12345")
            stable_file_read(path, budget=4)

    def material_tree_entry_budget() -> None:
        with tempfile.TemporaryDirectory(prefix=".material-entry-budget-", dir=HERE) as temporary:
            root = pathlib.Path(temporary)
            _write_new_componentwise(root / "one", b"1")
            _write_new_componentwise(root / "two", b"2")
            limits = dict(_authority_fixture_limits())
            limits["maxTreeEntries"] = 1
            physical_inventory(root, limits)

    def material_tree_total_budget() -> None:
        with tempfile.TemporaryDirectory(prefix=".material-total-budget-", dir=HERE) as temporary:
            root = pathlib.Path(temporary)
            _write_new_componentwise(root / "one", b"123")
            _write_new_componentwise(root / "two", b"456")
            limits = dict(_authority_fixture_limits())
            limits["maxTreeTotalBytes"] = 5
            physical_inventory(root, limits)

    def material_parent_directory_symlink() -> None:
        with tempfile.TemporaryDirectory(prefix=".material-parent-symlink-", dir=HERE) as temporary:
            root = pathlib.Path(temporary)
            nested = root / "nested"
            nested.mkdir()
            _write_new_componentwise(nested / "payload", b"payload")
            _unlink_componentwise(nested / "payload")
            _rmdir_componentwise(nested)
            _symlink_componentwise(nested, ".")
            stable_file_read(nested / "payload")

    def material_parent_directory_replacement() -> None:
        with tempfile.TemporaryDirectory(prefix=".material-parent-replacement-", dir=HERE) as temporary:
            root = pathlib.Path(temporary)
            nested = root / "nested"
            nested.mkdir()
            _write_new_componentwise(nested / "payload", b"payload")
            before = physical_inventory(root)
            _unlink_componentwise(nested / "payload")
            _rmdir_componentwise(nested)
            nested.mkdir()
            _write_new_componentwise(nested / "payload", b"payload")
            after = physical_inventory(root)
            require_inventory_stable(before, after, str(root))

    def material_post_inventory_entry_drift() -> None:
        with tempfile.TemporaryDirectory(prefix=".material-entry-drift-", dir=HERE) as temporary:
            root = pathlib.Path(temporary)
            _write_new_componentwise(root / "one", b"1")
            before = physical_inventory(root)
            _write_new_componentwise(root / "two", b"2")
            after = physical_inventory(root)
            require_inventory_stable(before, after, str(root))

    def child_launch_request_ordinal_stitch() -> None:
        command_path = resolve_uri(vectors["CHILD_LAUNCH_REQUEST_ORDINAL_STITCH"]["source"])
        command, _ = read_canonical_document(command_path)
        process_path = command_path.parent / "process-transcript.json"
        command["arguments"][-1] = "/tmp/gate-a/RUN-A1-REPLAY-VERIFIED-001-CHILD-02/request.json"
        command["workingDirectory"] = "/tmp/gate-a/RUN-A1-REPLAY-VERIFIED-001-CHILD-02"
        command["commandFingerprint"] = None
        command["commandFingerprint"] = document_fingerprint(command, "commandFingerprint", DOMAINS["command"])
        verify_self(command, "commandFingerprint", DOMAINS["command"])
        verify_launch_command(command, "RUN-A1-REPLAY-VERIFIED-001-CHILD-01", "CANDIDATE_CHILD", process_path)

    def outer_launch_argument_stitch() -> None:
        command_path = resolve_uri(vectors["OUTER_LAUNCH_ARGUMENT_STITCH"]["source"])
        command, _ = read_canonical_document(command_path)
        command["arguments"][-1] = "/tmp/gate-a/RUN-A1-REPLAY-VERIFIED-001/producer/other-scratch"
        command["commandFingerprint"] = None
        command["commandFingerprint"] = document_fingerprint(command, "commandFingerprint", DOMAINS["command"])
        verify_self(command, "commandFingerprint", DOMAINS["command"])
        verify_launch_command(
            command,
            "RUN-A1-REPLAY-VERIFIED-001",
            "A1_VERIFIER",
            command_path.parent / "process-transcript.json",
            "NORMAL_A1",
        )

    def provider_destination_identity_replacement() -> None:
        observation_path = resolve_uri(vectors["PROVIDER_DESTINATION_IDENTITY_REPLACEMENT"]["source"])
        observation, _ = read_canonical_document(observation_path)
        observation["destinationOpenReadReceipt"]["preRead"]["fileKey"] = "dev:1:ino:49999"
        observation["destinationOpenReadReceipt"]["postRead"]["fileKey"] = "dev:1:ino:49999"
        verify_provider_materialization_identity_snapshots(observation)

    def protocol_source_projection_rebound() -> None:
        source_bytes, _ = stable_file_read(PROTOCOL_SOURCE)
        projection_bytes, _ = stable_file_read(PROTOCOL_REPLAY_COMPILED)
        manifest_bytes, _ = stable_file_read(PROTOCOL_MANIFEST)
        pin_bytes, _ = stable_file_read(TRUST_BUILD / "valid-challenge-trust-pin.json")
        source = json.loads(source_bytes, object_pairs_hook=_reject_duplicate_pairs)
        projection = json.loads(projection_bytes, object_pairs_hook=_reject_duplicate_pairs)
        manifest = json.loads(manifest_bytes, object_pairs_hook=_reject_duplicate_pairs)
        source["trustBoundary"]["trusted"][0] += " rebound"
        rebound_source_bytes = canonical_bytes(source)
        projection["sourceRawFingerprint"] = digest_bytes(rebound_source_bytes)
        rebound_projection_bytes = canonical_bytes(projection)
        manifest["sourceRawFingerprint"] = digest_bytes(rebound_source_bytes)
        for item in manifest["projections"]:
            if item["projectionId"] == "REPLAY_VECTOR_REGISTRY":
                item["rawFingerprint"] = digest_bytes(rebound_projection_bytes)
        rebound_manifest_bytes = canonical_bytes(manifest)
        verify_protocol_projection_bundle(
            "REPLAY_VECTOR_REGISTRY",
            rebound_source_bytes,
            rebound_projection_bytes,
            rebound_manifest_bytes,
            pin_bytes,
        )

    def replay_vector_recipe_inputset_rebound() -> None:
        content = copy.deepcopy(authority["replayRegistry"])
        content["replayVectors"][0]["inputSetId"] = "REBOUND_INPUT_SET"
        content["replayVectors"][0]["mutationRecipe"]["variantId"] = "REBOUND_RECIPE"
        verify_replay_vector_registry_content(content)

    attacks = {
        "RESPONSE_PROVIDER_ORIGIN_REBOUND": response_provider_origin_rebound,
        "REPLAY_CROSS_RUN_RESPONSE_STITCHING": replay_cross_run_response_stitching,
        "INDEPENDENT_OBSERVATION_REBOUND": independent_observation_rebound,
        "INDEPENDENT_EXIT_REBOUND": independent_exit_rebound,
        "INDEPENDENT_PROVIDER_AUTHORITY_REBOUND": independent_provider_authority_rebound,
        "INDEPENDENT_RESPONSE_REF_REUSE": independent_response_ref_reuse,
        "HARNESS_REPLAY_PROOF_DIGEST_REBOUND": harness_replay_proof_digest_rebound,
        "REPLAY_PROOF_MISSING_MESSAGE_VERSION": replay_proof_missing_message_version,
        "RUN_TREE_HARD_LINK_ALIAS": run_tree_hard_link_alias,
        "CHILD_LAUNCH_REQUEST_ORDINAL_STITCH": child_launch_request_ordinal_stitch,
        "OUTER_LAUNCH_ARGUMENT_STITCH": outer_launch_argument_stitch,
        "PROVIDER_DESTINATION_IDENTITY_REPLACEMENT": provider_destination_identity_replacement,
        "PROTOCOL_SOURCE_PROJECTION_REBOUND": protocol_source_projection_rebound,
        "REPLAY_VECTOR_RECIPE_INPUTSET_REBOUND": replay_vector_recipe_inputset_rebound,
        "MATERIAL_FILE_BUDGET": material_file_budget,
        "MATERIAL_TREE_ENTRY_BUDGET": material_tree_entry_budget,
        "MATERIAL_TREE_TOTAL_BUDGET": material_tree_total_budget,
        "MATERIAL_PARENT_DIRECTORY_SYMLINK": material_parent_directory_symlink,
        "MATERIAL_PARENT_DIRECTORY_REPLACEMENT": material_parent_directory_replacement,
        "MATERIAL_POST_INVENTORY_ENTRY_DRIFT": material_post_inventory_entry_drift,
    }
    require(set(vectors) == set(attacks), "A1_RUN_MATERIAL_ATTACK_MANIFEST_DRIFT")
    for vector_id, attack in attacks.items():
        execute(vector_id, attack)
    return failures


def validate_all_run_material() -> list[str]:
    failures: list[str] = []
    try:
        authority = expected_authority()
        verified = verify_replay_envelope("valid-replay-proof-envelope.json", authority)
        unavailable = verify_replay_envelope("valid-replay-proof-envelope-unavailable.json", authority)
        require(verified["runRoot"] != unavailable["runRoot"], "A1_CROSS_RUN_ROOT_REUSED")
        require(verified["challenges"] == unavailable["challenges"], "A1_CROSS_RUN_REPLAY_CHALLENGE_DRIFT")
        require(verified["fileIdentities"].isdisjoint(unavailable["fileIdentities"]), "A1_CROSS_RUN_FILE_IDENTITY_REUSED")
        independent = verify_independent_proof_envelope("valid-independent-proof-envelope.json", authority)
        verify_independent_result(authority)
        require(verified["fileIdentities"].isdisjoint(independent["fileIdentities"]), "A1_CROSS_RUN_FILE_IDENTITY_REUSED")
        require(unavailable["fileIdentities"].isdisjoint(independent["fileIdentities"]), "A1_CROSS_RUN_FILE_IDENTITY_REUSED")
        verify_harness_bootstrap_attack(authority)
        verify_early_bootstrap_provider_attack()
        verify_parent_unavailable_attack(authority)
        verify_abnormal_attempts(authority)
        failures.extend(verify_real_run_material_attacks(authority))
        print(f"run material closure valid: replay=2, independent=1, cross-run-replay=PASS, harness-bootstrap=REJECTED, early-bootstrap-provider=REJECTED, parent-unavailable=REJECTED, abnormal-transitions={len(authority['abnormalTransitions'])}, attacks={len(read_json(RUN_MATERIAL_ATTACKS))}")
    except MaterialError as error:
        failures.append(str(error))
    return failures


def main() -> int:
    failures = validate_all_run_material()
    if failures:
        print("Gate A run material validation failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
