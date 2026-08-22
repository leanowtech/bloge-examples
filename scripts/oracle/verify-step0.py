#!/usr/bin/env python3
"""Fail-closed verifier for Gate A1 Step0 authority artifacts."""

from __future__ import annotations

import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
from pathlib import Path, PurePosixPath
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
BASELINE_ROOT = REPO_ROOT / "docs/acceptance/capability-studio/gate-a1-step0"
MANIFEST_PATH = BASELINE_ROOT / "step0-manifest-v1.json"
QUARANTINE_ROOT = BASELINE_ROOT / "non-release/quarantine"
FRAGMENTS_ROOT = QUARANTINE_ROOT / "fragments"
MAPPING_PATH = REPO_ROOT / "docs/schemas/migration-mapping-v1.json"
LEGACY_INVENTORY_PATH = BASELINE_ROOT / "legacy-authority-inventory-v1.json"
LEGACY_ROOT_TEXT = "docs/schemas/resource-gateway-capability-studio"
TARGET_ROOT_TEXT = "docs/schemas/resource-gateway-capability-studio-a1"
LEGACY_ROOT = REPO_ROOT / LEGACY_ROOT_TEXT
TARGET_ROOT = REPO_ROOT / TARGET_ROOT_TEXT

SOURCE_ALLOWLIST = frozenset(
    {
        "docs/acceptance/capability-studio/gate-a-wire-v1/protocol-compiler/compile-protocol-authority.py",
        "docs/acceptance/capability-studio/gate-a-wire-v1/trust-build/__pycache__/validate-fixtures.cpython-314.pyc",
        "docs/acceptance/capability-studio/gate-a-wire-v1/trust-build/validate-fixtures.py",
    }
)
TARGET_FILENAMES = frozenset(
    {
        "acceptance-receipt-v1.schema.json",
        "attack-case-v1.schema.json",
        "compiler-manifest-v1.schema.json",
        "evidence-catalog-entry-v1.schema.json",
        "hermetic-observation-v1.schema.json",
        "ledger-entry-v1.schema.json",
        "normative-primitives-v1.schema.json",
        "observation-receipt-v1.schema.json",
        "observer-failure-v1.schema.json",
        "oracle-manifest-v1.schema.json",
        "revocation-record-v1.schema.json",
        "source-package-v1.schema.json",
        "source-unit-v1.schema.json",
    }
)
EXPECTED_DOMAINS = frozenset(
    {
        "rg.gatea.artifact-closure.v1",
        "rg.gatea.artifact.v1",
        "rg.gatea.compiler-manifest.v1",
        "rg.gatea.decision-input.v1",
        "rg.gatea.genesis.v1",
        "rg.gatea.invocation-key.v1",
        "rg.gatea.ledger-entry.v1",
        "rg.gatea.ledger-head.v1",
        "rg.gatea.merkle-leaf.v1",
        "rg.gatea.merkle-node.v1",
        "rg.gatea.observation-receipt.v1",
        "rg.gatea.observer-failure.v1",
        "rg.gatea.package.v1",
        "rg.gatea.ra-list.v1",
        "rg.gatea.reducer-output.v1",
        "rg.gatea.result.v1",
        "rg.gatea.revocation-payload.v1",
        "rg.gatea.revocation-record.v1",
        "rg.gatea.slice.v1",
    }
)
SEMANTIC_PAIRS = frozenset(
    {
        (
            "https://leanowtech.com/schemas/resource-gateway-capability-studio/"
            "capability-studio-gate-a-process-observation-v1.schema.json",
            "urn:studio:schema:hermetic-observation:v1",
        ),
        (
            "https://leanowtech.com/schemas/resource-gateway-capability-studio/"
            "capability-studio-gate-a-protocol-compilation-manifest-v1.schema.json",
            "urn:studio:schema:compiler-manifest:v1",
        ),
        (
            "https://leanowtech.com/schemas/resource-gateway-capability-studio/"
            "capability-studio-gate-a-slice-acceptance-receipt-v1.schema.json",
            "urn:studio:schema:acceptance-receipt:v1",
        ),
    }
)
MAPPED_LEGACY_FILENAMES = frozenset(pair[0].rsplit("/", 1)[-1] for pair in SEMANTIC_PAIRS)
MAPPING_KEYS = frozenset(
    {
        "schemaVersion",
        "transformationVersion",
        "legacyAuthorityRoot",
        "targetAuthorityRoot",
        "mappings",
        "legacyDispositions",
        "targetDispositions",
    }
)
MAP_RECORD_KEYS = frozenset(
    {
        "legacySchemaId",
        "legacyRawDigest",
        "targetSchemaId",
        "targetWireDigest",
        "transformationVersion",
    }
)
DISPOSITION_KEYS = frozenset({"schemaId", "digest", "disposition", "reason"})
LEGACY_DISPOSITION = "NO_TARGET_EQUIVALENT"
TARGET_DISPOSITION = "TARGET_ONLY"
LEGACY_REASON = "Legacy Gate A wire schema without semantic equivalent in Target A1 schema set"
TARGET_REASON = "Target A1 schema without legacy equivalent"
WIRE_DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")
INSTANT_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
MISSING = object()
IEEE754_SAFE_INTEGER_MAX = 9_007_199_254_740_991
AUTHORITY_SCRIPT_PATHS = frozenset(
    {
        "scripts/oracle/verify-baseline.sh",
        "scripts/oracle/verify-step0.py",
        "scripts/oracle/verify-step0.sh",
    }
)


class VerificationError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise VerificationError(message)


def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def reject_float(value: str) -> None:
    fail(f"floating-point JSON number is outside the Step0 numeric input profile: {value}")


def parse_safe_integer(value: str) -> int:
    parsed = int(value)
    if not -IEEE754_SAFE_INTEGER_MAX <= parsed <= IEEE754_SAFE_INTEGER_MAX:
        fail(f"integer is outside the IEEE-754 safe-integer range: {value}")
    return parsed


def strict_load_json(path: Path, *, enforce_numeric_profile: bool = True) -> Any:
    try:
        with path.open("r", encoding="utf-8") as handle:
            return json.load(
                handle,
                object_pairs_hook=reject_duplicates,
                parse_int=parse_safe_integer if enforce_numeric_profile else int,
                parse_float=reject_float,
                parse_constant=reject_float,
            )
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        fail(f"cannot read strict JSON {path}: {exc}")


def normalize_surrogates(value: str) -> str:
    normalized = []
    index = 0
    while index < len(value):
        code = ord(value[index])
        if 0xD800 <= code <= 0xDBFF:
            if index + 1 >= len(value):
                fail("JCS string contains an unpaired high surrogate")
            low = ord(value[index + 1])
            if not 0xDC00 <= low <= 0xDFFF:
                fail("JCS string contains an unpaired high surrogate")
            normalized.append(chr(0x10000 + ((code - 0xD800) << 10) + low - 0xDC00))
            index += 2
            continue
        if 0xDC00 <= code <= 0xDFFF:
            fail("JCS string contains an unpaired low surrogate")
        normalized.append(value[index])
        index += 1
    return "".join(normalized)


def utf16_sort_key(value: str) -> tuple[int, ...]:
    encoded = normalize_surrogates(value).encode("utf-16-be", errors="strict")
    return tuple(int.from_bytes(encoded[i : i + 2], "big") for i in range(0, len(encoded), 2))


def ecmascript_integer(value: int) -> str:
    number = float(value)
    if not (-float("inf") < number < float("inf")):
        fail(f"integer cannot be represented as a finite ECMAScript Number: {value}")
    rendered = repr(number).lower()
    if "e" not in rendered:
        return rendered.removesuffix(".0")
    mantissa, exponent_text = rendered.split("e", 1)
    exponent = int(exponent_text)
    negative = mantissa.startswith("-")
    digits = mantissa.removeprefix("-").replace(".", "")
    decimal_position = exponent + 1
    sign = "-" if negative else ""
    if -6 < decimal_position <= 21:
        if decimal_position <= 0:
            return sign + "0." + "0" * (-decimal_position) + digits
        if decimal_position >= len(digits):
            return sign + digits + "0" * (decimal_position - len(digits))
        return sign + digits[:decimal_position] + "." + digits[decimal_position:]
    coefficient = digits[0]
    if len(digits) > 1:
        coefficient += "." + digits[1:]
    exponent_sign = "+" if exponent >= 0 else ""
    return f"{sign}{coefficient}e{exponent_sign}{exponent}"


def jcs(data: Any, *, enforce_numeric_profile: bool = True) -> str:
    if data is None:
        return "null"
    if isinstance(data, bool):
        return "true" if data else "false"
    if isinstance(data, int):
        if enforce_numeric_profile and not -IEEE754_SAFE_INTEGER_MAX <= data <= IEEE754_SAFE_INTEGER_MAX:
            fail(f"integer is outside the IEEE-754 safe-integer range: {data}")
        return str(data) if -IEEE754_SAFE_INTEGER_MAX <= data <= IEEE754_SAFE_INTEGER_MAX else ecmascript_integer(data)
    if isinstance(data, float):
        fail("floating-point values are outside the Step0 numeric input profile")
    if isinstance(data, str):
        try:
            return json.dumps(normalize_surrogates(data), ensure_ascii=False, separators=(",", ":"))
        except (UnicodeEncodeError, ValueError) as exc:
            fail(f"invalid JCS string: {exc}")
    if isinstance(data, list):
        return "[" + ",".join(jcs(item, enforce_numeric_profile=enforce_numeric_profile) for item in data) + "]"
    if isinstance(data, dict):
        if not all(isinstance(key, str) for key in data):
            fail("JCS object keys must be strings")
        members = []
        for key in sorted(data, key=utf16_sort_key):
            members.append(
                jcs(key, enforce_numeric_profile=enforce_numeric_profile)
                + ":"
                + jcs(data[key], enforce_numeric_profile=enforce_numeric_profile)
            )
        return "{" + ",".join(members) + "}"
    fail(f"unsupported JCS value type: {type(data).__name__}")


def wire_digest(data: Any, *, enforce_numeric_profile: bool = True) -> str:
    try:
        canonical = jcs(data, enforce_numeric_profile=enforce_numeric_profile).encode(
            "utf-8", errors="strict"
        )
    except UnicodeEncodeError as exc:
        fail(f"invalid Unicode in JCS value: {exc}")
    return "sha256:" + hashlib.sha256(canonical).hexdigest()


def content_digest(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as exc:
        fail(f"cannot hash {path}: {exc}")
    return "sha256:" + digest.hexdigest()


def relative_parts(value: str, label: str) -> tuple[str, ...]:
    if not isinstance(value, str) or not value or "\\" in value:
        fail(f"{label} must be a non-empty POSIX relative path")
    raw_parts = value.split("/")
    if any(part in ("", ".", "..") for part in raw_parts):
        fail(f"{label} is not a normalized relative path: {value}")
    path = PurePosixPath(value)
    if path.is_absolute() or tuple(raw_parts) != path.parts:
        fail(f"{label} is not a normalized relative path: {value}")
    return path.parts


def contained_path(root: Path, value: str, label: str, must_exist: bool = True) -> Path:
    parts = relative_parts(value, label)
    root_real = root.resolve(strict=True)
    current = root
    for part in parts:
        current = current / part
        if current.is_symlink():
            fail(f"{label} traverses a symlink: {value}")
    if must_exist and not current.exists():
        fail(f"{label} does not exist: {value}")
    resolved = current.resolve(strict=must_exist)
    if not resolved.is_relative_to(root_real):
        fail(f"{label} escapes its authority root: {value}")
    return current


def require_regular(path: Path, label: str) -> None:
    try:
        mode = path.stat(follow_symlinks=False).st_mode
    except OSError as exc:
        fail(f"{label} is unavailable: {exc}")
    if stat.S_ISLNK(mode) or not stat.S_ISREG(mode):
        fail(f"{label} must be a regular non-symlink file: {path}")


def resolve_pointer(document: Any, fragment: str, label: str) -> Any:
    if fragment in ("", None):
        return document
    if not fragment.startswith("/"):
        fail(f"{label} uses a non-JSON-Pointer fragment: #{fragment}")
    current = document
    for raw in fragment[1:].split("/"):
        decoded = []
        index = 0
        while index < len(raw):
            if raw[index] != "~":
                decoded.append(raw[index])
                index += 1
                continue
            if index + 1 >= len(raw) or raw[index + 1] not in ("0", "1"):
                fail(f"{label} uses an invalid JSON Pointer escape: #{fragment}")
            decoded.append("~" if raw[index + 1] == "0" else "/")
            index += 2
        token = "".join(decoded)
        if isinstance(current, dict):
            current = current.get(token, MISSING)
        elif (
            isinstance(current, list)
            and (token == "0" or (token and token[0] != "0" and token.isdigit()))
            and int(token) < len(current)
        ):
            current = current[int(token)]
        else:
            current = MISSING
        if current is MISSING:
            fail(f"{label} has an unresolved fragment: #{fragment}")
    return current


def walk_refs(value: Any):
    if isinstance(value, dict):
        ref = value.get("$ref")
        if ref is not None:
            if not isinstance(ref, str) or not ref:
                fail("$ref must be a non-empty string")
            yield ref
        for child in value.values():
            yield from walk_refs(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk_refs(child)


def validate_ref(
    ref: str,
    current_path: Path,
    current_schema: Any,
    registry: dict[str, tuple[Path, Any]],
    target_root: Path,
) -> None:
    label = f"{current_path.name} $ref {ref}"
    if ref.startswith("#"):
        resolve_pointer(current_schema, ref[1:], label)
        return
    if ref.startswith("http://") or ref.startswith("https://"):
        fail(f"{label} uses a forbidden network reference")
    base, separator, fragment = ref.partition("#")
    if base.startswith("urn:studio:schema:"):
        entry = registry.get(base)
        if entry is None:
            fail(f"{label} names an unknown schema URN")
        resolve_pointer(entry[1], fragment if separator else "", label)
        return
    if ":" in base:
        fail(f"{label} uses an unsupported external scheme")
    base_parts = relative_parts(base, label)
    current_parent = current_path.relative_to(target_root).parent
    candidate_value = "/".join((*current_parent.parts, *base_parts))
    candidate = contained_path(
        target_root,
        candidate_value,
        label,
    )
    require_regular(candidate, label)
    candidate_real = candidate.resolve()
    matching = [entry for entry in registry.values() if entry[0].resolve() == candidate_real]
    if len(matching) != 1:
        fail(f"{label} does not resolve to exactly one registered target schema")
    resolve_pointer(matching[0][1], fragment if separator else "", label)


def iter_object_schemas(value: Any, path: str = "$"):
    if isinstance(value, dict):
        if value.get("type") == "object":
            yield path, value
        for key, child in value.items():
            yield from iter_object_schemas(child, f"{path}/{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from iter_object_schemas(child, f"{path}/{index}")


def schema_registry(
    root: Path,
    expected_names: frozenset[str] | None = None,
    *,
    enforce_numeric_profile: bool = True,
):
    if root.is_symlink() or not root.is_dir():
        fail(f"schema authority root must be a real directory: {root}")
    files = []
    for directory, dirs, names in os.walk(root, topdown=True, followlinks=False):
        directory_path = Path(directory)
        for name in dirs:
            if (directory_path / name).is_symlink():
                fail(f"schema authority contains a symlink directory: {directory_path / name}")
        for name in names:
            if not name.endswith(".schema.json"):
                continue
            path = directory_path / name
            require_regular(path, "schema")
            if directory_path != root:
                fail(f"schema authority contains a nested schema: {path}")
            files.append(path)
    files.sort()
    names = {path.name for path in files}
    if expected_names is not None and names != expected_names:
        fail(
            "target schema file set mismatch; "
            f"missing={sorted(expected_names - names)}, extra={sorted(names - expected_names)}"
        )
    registry: dict[str, tuple[Path, Any]] = {}
    digests: dict[str, str] = {}
    for path in files:
        schema = strict_load_json(path, enforce_numeric_profile=enforce_numeric_profile)
        if not isinstance(schema, dict):
            fail(f"schema root must be an object: {path.name}")
        schema_id = schema.get("$id")
        if not isinstance(schema_id, str) or not schema_id:
            fail(f"schema has no $id: {path.name}")
        if schema_id in registry:
            fail(f"duplicate schema $id: {schema_id}")
        registry[schema_id] = (path, schema)
        digests[schema_id] = wire_digest(
            schema, enforce_numeric_profile=enforce_numeric_profile
        )
    return registry, digests


def validate_target_schemas() -> tuple[dict[str, tuple[Path, Any]], dict[str, str]]:
    registry, digests = schema_registry(TARGET_ROOT, TARGET_FILENAMES)
    if len(registry) != 13:
        fail(f"target schema registry must contain exactly 13 unique IDs, got {len(registry)}")
    for schema_id, (path, schema) in registry.items():
        if schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
            fail(f"{path.name} is not Draft 2020-12")
        if schema.get("additionalProperties") is not False:
            fail(f"{path.name} root must set additionalProperties=false")
        for object_path, object_schema in iter_object_schemas(schema):
            is_declared_dynamic_value = object_path.endswith(("/properties/content", "/properties/payload"))
            if "additionalProperties" not in object_schema and not is_declared_dynamic_value:
                fail(f"{path.name} object schema {object_path} has implicit extension semantics")
        for ref in walk_refs(schema):
            validate_ref(ref, path, schema, registry, TARGET_ROOT)
        if path.name != "normative-primitives-v1.schema.json":
            for key, value in walk_key_values(schema):
                if key == "pattern" and isinstance(value, str) and (
                    "sha256:" in value
                ):
                    fail(f"{path.name} locally redefines the normative SHA256 wire pattern")
    normative = registry.get("urn:studio:schema:normative-primitives:v1")
    if normative is None:
        fail("normative-primitives schema ID is missing")
    domains = (
        normative[1].get("$defs", {}).get("domainLabel", {}).get("enum")
    )
    if not isinstance(domains, list) or frozenset(domains) != EXPECTED_DOMAINS or len(domains) != 19:
        fail("normative domain registry is not the exact frozen 19-domain set")
    return registry, digests


def walk_key_values(value: Any):
    if isinstance(value, dict):
        for key, child in value.items():
            yield key, child
            yield from walk_key_values(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk_key_values(child)


def validate_mapping_document(
    mapping: Any, legacy_digests: dict[str, str], target_digests: dict[str, str]
) -> None:
    if not isinstance(mapping, dict) or frozenset(mapping) != MAPPING_KEYS:
        fail("migration mapping top-level keys are not exact")
    exact_values = {
        "schemaVersion": "1.0",
        "transformationVersion": "1.0.0",
        "legacyAuthorityRoot": LEGACY_ROOT_TEXT,
        "targetAuthorityRoot": TARGET_ROOT_TEXT,
    }
    for key, expected in exact_values.items():
        if mapping.get(key) != expected:
            fail(f"migration mapping {key} must equal {expected}")

    records = mapping.get("mappings")
    if not isinstance(records, list) or len(records) != 3:
        fail("migration mapping must contain exactly three authority pairs")
    seen_pairs: set[tuple[str, str]] = set()
    mapped_legacy: set[str] = set()
    mapped_target: set[str] = set()
    for index, record in enumerate(records):
        if not isinstance(record, dict) or frozenset(record) != MAP_RECORD_KEYS:
            fail(f"migration mapping record {index} fields are not exact")
        pair = (record["legacySchemaId"], record["targetSchemaId"])
        if pair not in SEMANTIC_PAIRS or pair in seen_pairs:
            fail(f"migration mapping record {index} is not one of the three frozen unique pairs")
        seen_pairs.add(pair)
        mapped_legacy.add(pair[0])
        mapped_target.add(pair[1])
        if record["transformationVersion"] != "1.0.0":
            fail(f"migration mapping record {index} has an unknown transformation version")
        if record["legacyRawDigest"] != legacy_digests.get(pair[0]):
            fail(f"migration mapping record {index} has a stale legacy digest")
        if record["targetWireDigest"] != target_digests.get(pair[1]):
            fail(f"migration mapping record {index} has a stale target digest")
    if seen_pairs != SEMANTIC_PAIRS:
        fail("migration mapping authority pair set is incomplete")

    validate_dispositions(
        mapping.get("legacyDispositions"),
        legacy_digests,
        mapped_legacy,
        LEGACY_DISPOSITION,
        LEGACY_REASON,
        "legacy",
    )
    validate_dispositions(
        mapping.get("targetDispositions"),
        target_digests,
        mapped_target,
        TARGET_DISPOSITION,
        TARGET_REASON,
        "target",
    )


def validate_dispositions(
    records: Any,
    all_digests: dict[str, str],
    mapped_ids: set[str],
    expected_disposition: str,
    expected_reason: str,
    label: str,
) -> None:
    expected_ids = set(all_digests) - mapped_ids
    if not isinstance(records, list) or len(records) != len(expected_ids):
        fail(f"{label} disposition count does not provide exact schema coverage")
    seen: set[str] = set()
    for index, record in enumerate(records):
        if not isinstance(record, dict) or frozenset(record) != DISPOSITION_KEYS:
            fail(f"{label} disposition {index} fields are not exact")
        schema_id = record["schemaId"]
        if schema_id in seen or schema_id not in expected_ids:
            fail(f"{label} disposition {index} is duplicate, mapped, or unknown")
        seen.add(schema_id)
        if record["digest"] != all_digests[schema_id] or not WIRE_DIGEST_RE.fullmatch(record["digest"]):
            fail(f"{label} disposition {index} has a stale or malformed digest")
        if record["disposition"] != expected_disposition:
            fail(f"{label} disposition {index} uses an unknown disposition")
        if record["reason"] != expected_reason:
            fail(f"{label} disposition {index} uses a non-canonical reason")
    if seen != expected_ids:
        fail(f"{label} dispositions do not exactly cover all unmapped schemas")


def validate_schema_authority_and_mapping() -> None:
    contained_path(REPO_ROOT, LEGACY_ROOT_TEXT, "legacyAuthorityRoot")
    contained_path(REPO_ROOT, TARGET_ROOT_TEXT, "targetAuthorityRoot")
    _, live_legacy_digests = schema_registry(LEGACY_ROOT, enforce_numeric_profile=False)
    legacy_digests, _ = validate_legacy_inventory()
    if live_legacy_digests != legacy_digests:
        fail("live Legacy authority differs from the frozen Step0 inventory")
    _, target_digests = validate_target_schemas()
    mapping = strict_load_json(MAPPING_PATH)
    validate_mapping_document(mapping, legacy_digests, target_digests)


def legacy_inventory_document(
    legacy_registry: dict[str, tuple[Path, Any]], legacy_digests: dict[str, str]
) -> dict[str, Any]:
    descriptors = [
        {
            "schemaId": schema_id,
            "sourcePath": legacy_registry[schema_id][0].relative_to(REPO_ROOT).as_posix(),
            "wireDigest": digest,
        }
        for schema_id, digest in sorted(legacy_digests.items())
    ]
    return {
        "schemaVersion": "1.0",
        "authorityRoot": LEGACY_ROOT_TEXT,
        "schemas": descriptors,
        "inventoryRootDigest": wire_digest(descriptors, enforce_numeric_profile=False),
    }


def validate_legacy_inventory() -> tuple[dict[str, str], set[str]]:
    inventory = strict_load_json(LEGACY_INVENTORY_PATH, enforce_numeric_profile=False)
    if not isinstance(inventory, dict) or frozenset(inventory) != frozenset(
        {"schemaVersion", "authorityRoot", "schemas", "inventoryRootDigest"}
    ):
        fail("Legacy authority inventory fields are not exact")
    if inventory["schemaVersion"] != "1.0" or inventory["authorityRoot"] != LEGACY_ROOT_TEXT:
        fail("Legacy authority inventory version or root is not frozen")
    descriptors = inventory["schemas"]
    if not isinstance(descriptors, list) or not descriptors:
        fail("Legacy authority inventory is empty")
    digests: dict[str, str] = {}
    source_paths: set[str] = set()
    for index, descriptor in enumerate(descriptors):
        if not isinstance(descriptor, dict) or frozenset(descriptor) != frozenset(
            {"schemaId", "sourcePath", "wireDigest"}
        ):
            fail(f"Legacy authority descriptor {index} fields are not exact")
        schema_id = descriptor["schemaId"]
        digest = descriptor["wireDigest"]
        source_path = descriptor["sourcePath"]
        if not isinstance(schema_id, str) or not schema_id or schema_id in digests:
            fail(f"Legacy authority descriptor {index} has an invalid or duplicate schemaId")
        if not isinstance(digest, str) or not WIRE_DIGEST_RE.fullmatch(digest):
            fail(f"Legacy authority descriptor {index} has a malformed digest")
        parts = relative_parts(source_path, f"Legacy authority descriptor {index} sourcePath")
        if not source_path.startswith(LEGACY_ROOT_TEXT + "/") or len(parts) != 4:
            fail(f"Legacy authority descriptor {index} sourcePath is outside the flat authority root")
        if source_path in source_paths or not source_path.endswith(".schema.json"):
            fail(f"Legacy authority descriptor {index} has a duplicate or invalid sourcePath")
        digests[schema_id] = digest
        source_paths.add(source_path)
    if descriptors != [
        {
            "schemaId": schema_id,
            "sourcePath": next(
                descriptor["sourcePath"]
                for descriptor in descriptors
                if descriptor["schemaId"] == schema_id
            ),
            "wireDigest": digest,
        }
        for schema_id, digest in sorted(digests.items())
    ]:
        fail("Legacy authority descriptors are not canonically ordered")
    expected_root = wire_digest(descriptors, enforce_numeric_profile=False)
    if inventory["inventoryRootDigest"] != expected_root:
        fail("Legacy authority inventory root digest is stale")
    return digests, source_paths


def validate_indexed_mapped_legacy_sources(legacy_digests: dict[str, str]) -> None:
    for filename in sorted(MAPPED_LEGACY_FILENAMES):
        path = LEGACY_ROOT / filename
        schema = strict_load_json(path, enforce_numeric_profile=False)
        schema_id = schema.get("$id") if isinstance(schema, dict) else None
        if schema_id not in legacy_digests:
            fail(f"mapped Legacy source has an unknown $id: {filename}")
        if wire_digest(schema, enforce_numeric_profile=False) != legacy_digests[schema_id]:
            fail(f"mapped Legacy source differs from frozen inventory: {filename}")


def validate_index_schema_authority_and_mapping() -> None:
    _, target_digests = validate_target_schemas()
    legacy_digests, _ = validate_legacy_inventory()
    _, indexed_legacy_digests = schema_registry(LEGACY_ROOT, enforce_numeric_profile=False)
    if indexed_legacy_digests != legacy_digests:
        fail("indexed Legacy authority differs from the frozen Step0 inventory")
    mapping = strict_load_json(MAPPING_PATH)
    validate_mapping_document(mapping, legacy_digests, target_digests)


def authority_index_paths() -> set[str]:
    paths = set(AUTHORITY_SCRIPT_PATHS)
    paths.add(MANIFEST_PATH.relative_to(REPO_ROOT).as_posix())
    paths.add(MAPPING_PATH.relative_to(REPO_ROOT).as_posix())
    paths.add(LEGACY_INVENTORY_PATH.relative_to(REPO_ROOT).as_posix())
    _, legacy_source_paths = validate_legacy_inventory()
    paths.update(legacy_source_paths)
    paths.update(
        (TARGET_ROOT / name).relative_to(REPO_ROOT).as_posix()
        for name in TARGET_FILENAMES
    )

    manifest = strict_load_json(MANIFEST_PATH)
    artifacts = manifest.get("artifacts") if isinstance(manifest, dict) else None
    if not isinstance(artifacts, list):
        fail("baseline manifest artifacts are unavailable for index authority")
    for index, artifact in enumerate(artifacts):
        if not isinstance(artifact, dict):
            fail(f"baseline artifact {index} is unavailable for index authority")
        quarantine_path = artifact.get("quarantinePath")
        relative_parts(quarantine_path, f"artifact {index} quarantinePath")
        paths.add(quarantine_path)
    return paths


def validate_index_authority() -> None:
    expected_paths = authority_index_paths()
    index_pathspecs = sorted(expected_paths | {LEGACY_ROOT_TEXT, TARGET_ROOT_TEXT})
    result = subprocess.run(
        [
            "git",
            "-C",
            str(REPO_ROOT),
            "ls-files",
            "--stage",
            "-z",
            "--",
            *index_pathspecs,
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=10,
        check=False,
    )
    if result.returncode != 0:
        fail(f"cannot inspect Git index: {result.stderr.decode('utf-8', errors='replace').strip()}")

    entries: dict[str, tuple[str, str]] = {}
    for raw_entry in result.stdout.split(b"\0"):
        if not raw_entry:
            continue
        try:
            metadata, raw_path = raw_entry.split(b"\t", 1)
            mode, object_id, stage = metadata.decode("ascii").split(" ")
            path = raw_path.decode("utf-8", errors="strict")
        except (ValueError, UnicodeError) as exc:
            fail(f"malformed Git index entry: {exc}")
        if path not in expected_paths:
            fail(f"unexpected Git index path while checking authority: {path}")
        if path in entries:
            fail(f"authority path has multiple Git index stages: {path}")
        if stage != "0":
            fail(f"authority path is not at Git index stage 0: {path}")
        if mode not in {"100644", "100755"}:
            fail(f"authority path has forbidden Git index mode {mode}: {path}")
        entries[path] = (mode, object_id)

    missing = expected_paths - set(entries)
    if missing:
        fail(f"authority paths are not tracked in the Git index: {sorted(missing)}")

    for path, (_, index_object_id) in entries.items():
        workspace_path = contained_path(REPO_ROOT, path, f"indexed authority path {path}")
        require_regular(workspace_path, f"indexed authority path {path}")
        digest = subprocess.run(
            ["git", "-C", str(REPO_ROOT), "hash-object", "--no-filters", "--", path],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=10,
            check=False,
        )
        workspace_object_id = digest.stdout.strip()
        if digest.returncode != 0 or not re.fullmatch(r"[0-9a-f]{40,64}", workspace_object_id):
            fail(f"cannot hash workspace authority bytes for {path}: {digest.stderr.strip()}")
        if workspace_object_id != index_object_id:
            fail(f"workspace authority bytes differ from the Git index blob: {path}")


def validate_baseline(capture_check: bool) -> None:
    manifest = strict_load_json(MANIFEST_PATH)
    expected_manifest_keys = frozenset(
        {"releaseStatus", "parentCommitSha", "generatedAt", "artifacts", "corpusRootDigest"}
    )
    if not isinstance(manifest, dict) or frozenset(manifest) != expected_manifest_keys:
        fail("baseline manifest top-level keys are not exact")
    if manifest["releaseStatus"] != "NON_RELEASE":
        fail("baseline releaseStatus must be NON_RELEASE")
    parent = manifest["parentCommitSha"]
    if not isinstance(parent, str) or not COMMIT_RE.fullmatch(parent):
        fail("baseline parentCommitSha must be a lowercase SHA-1 commit ID")
    if not isinstance(manifest["generatedAt"], str) or not INSTANT_RE.fullmatch(manifest["generatedAt"]):
        fail("baseline generatedAt must be a canonical UTC second instant")
    result = subprocess.run(
        ["git", "-C", str(REPO_ROOT), "merge-base", "--is-ancestor", parent, "HEAD"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        timeout=10,
        check=False,
    )
    if result.returncode != 0:
        fail(f"baseline parentCommitSha is not an ancestor of HEAD: {parent}")

    artifacts = manifest["artifacts"]
    descriptor_keys = frozenset({"contentDigest", "quarantinePath", "size", "sourcePath"})
    if not isinstance(artifacts, list) or len(artifacts) != 3:
        fail("baseline manifest must contain exactly three artifacts")
    source_paths = []
    expected_files: set[Path] = set()
    expected_dirs: set[Path] = {FRAGMENTS_ROOT}
    for index, artifact in enumerate(artifacts):
        if not isinstance(artifact, dict) or frozenset(artifact) != descriptor_keys:
            fail(f"baseline artifact {index} fields are not exact")
        source = artifact["sourcePath"]
        relative_parts(source, f"artifact {index} sourcePath")
        source_paths.append(source)
        digest = artifact["contentDigest"]
        if not isinstance(digest, str) or not WIRE_DIGEST_RE.fullmatch(digest):
            fail(f"baseline artifact {index} contentDigest is malformed")
        if not isinstance(artifact["size"], int) or isinstance(artifact["size"], bool) or artifact["size"] < 0:
            fail(f"baseline artifact {index} size is invalid")
        digest_hex = digest.removeprefix("sha256:")
        expected_qp = (
            f"docs/acceptance/capability-studio/gate-a1-step0/non-release/quarantine/"
            f"fragments/{digest_hex}/{PurePosixPath(source).name}"
        )
        if artifact["quarantinePath"] != expected_qp:
            fail(f"baseline artifact {index} quarantinePath is not canonical")
        quarantine_file = contained_path(REPO_ROOT, expected_qp, f"artifact {index} quarantinePath")
        require_regular(quarantine_file, f"artifact {index} quarantine file")
        expected_files.add(quarantine_file.resolve())
        expected_dirs.add(quarantine_file.parent.resolve())
        if quarantine_file.stat().st_size != artifact["size"]:
            fail(f"baseline artifact {index} size differs from quarantine bytes")
        if content_digest(quarantine_file) != digest:
            fail(f"baseline artifact {index} digest differs from quarantine bytes")
    if frozenset(source_paths) != SOURCE_ALLOWLIST or len(set(source_paths)) != 3:
        fail("baseline sourcePath set must equal the exact three-path allowlist")

    actual_files: set[Path] = set()
    actual_dirs: set[Path] = {FRAGMENTS_ROOT.resolve()}
    for root, dirs, files in os.walk(FRAGMENTS_ROOT, topdown=True, followlinks=False):
        root_path = Path(root)
        for name in dirs:
            child = root_path / name
            if child.is_symlink():
                fail(f"quarantine contains a symlink directory: {child}")
            actual_dirs.add(child.resolve())
        for name in files:
            child = root_path / name
            require_regular(child, "quarantine entry")
            actual_files.add(child.resolve())
    if actual_files != expected_files or actual_dirs != expected_dirs:
        fail("quarantine tree contains missing, extra, or nested entries")

    descriptors = sorted(artifacts, key=lambda item: item["sourcePath"].encode("utf-8"))
    if manifest["corpusRootDigest"] != wire_digest(descriptors):
        fail("baseline corpusRootDigest does not match quarantine descriptors")

    if capture_check:
        for index, artifact in enumerate(artifacts):
            source = contained_path(REPO_ROOT, artifact["sourcePath"], f"artifact {index} live source")
            require_regular(source, f"artifact {index} live source")
            if source.stat().st_size != artifact["size"] or content_digest(source) != artifact["contentDigest"]:
                fail(f"artifact {index} live source differs from the quarantine baseline")


def expect_failure(action, label: str) -> None:
    try:
        action()
    except VerificationError:
        return
    fail(f"negative self-test did not reject {label}")


def run_self_tests() -> None:
    chinese = {"中文": "你好", "a": "值"}
    if jcs(chinese) != '{"a":"值","中文":"你好"}':
        fail("JCS Chinese/ensure_ascii=false self-test failed")
    non_bmp = {"\ue000": 2, "\U00010000": 1}
    if jcs(non_bmp) != '{"𐀀":1,"":2}':
        fail("JCS UTF-16 non-BMP ordering self-test failed")
    escaped_pair = json.loads('"\\ud83d\\ude00"')
    if jcs(escaped_pair) != '"😀"':
        fail("JCS escaped surrogate-pair normalization self-test failed")
    expect_failure(lambda: jcs("\ud83d"), "unpaired surrogate")
    node_vector = {"€": 1, "\r": 2, "דּ": 3, "1": 4, "😀": 5, "\u0080": 6, "ö": 7}
    expected_node = '{"\\r":2,"1":4,"\u0080":6,"ö":7,"€":1,"😀":5,"דּ":3}'
    if jcs(node_vector) != expected_node:
        fail("JCS Node/ECMAScript key-order vector self-test failed")
    expect_failure(lambda: jcs({"nested": [1.25]}), "floating-point JCS input")
    expect_failure(lambda: jcs(9_007_199_254_740_993), "unsafe integer JCS input")
    if jcs(9_223_372_036_854_775_807, enforce_numeric_profile=False) != "9223372036854776000":
        fail("legacy RFC 8785 integer canonicalization self-test failed")
    expect_failure(
        lambda: json.loads("9007199254740993", parse_int=parse_safe_integer),
        "unsafe integer JSON input",
    )
    expect_failure(
        lambda: json.loads('{"a":1,"a":2}', object_pairs_hook=reject_duplicates),
        "duplicate JSON keys",
    )
    for bad_path in (
        "../escape", "a/../escape", "/absolute", "a\\b",
        "a//b", "a/./b", "a/b/",
    ):
        expect_failure(lambda value=bad_path: relative_parts(value, "self-test path"), bad_path)
    expect_failure(
        lambda: resolve_pointer({"a~2b": 1}, "/a~2b", "self-test pointer"),
        "invalid JSON Pointer escape",
    )
    expect_failure(
        lambda: resolve_pointer([0, 1], "/01", "self-test pointer"),
        "non-canonical JSON Pointer array index",
    )
    expect_failure(
        lambda: validate_ref(
            "https://example.invalid/schema.json",
            TARGET_ROOT / "x.schema.json",
            {},
            {},
            TARGET_ROOT,
        ),
        "network schema reference",
    )
    expect_failure(
        lambda: validate_ref(
            "urn:studio:schema:unknown:v1#/$defs/X",
            TARGET_ROOT / "x.schema.json",
            {},
            {},
            TARGET_ROOT,
        ),
        "unknown schema URN",
    )
    expect_failure(
        lambda: validate_ref(
            "../escape.schema.json",
            TARGET_ROOT / "x.schema.json",
            {},
            {},
            TARGET_ROOT,
        ),
        "escaping relative schema reference",
    )
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        regular = root / "regular"
        regular.write_text("x", encoding="utf-8")
        link = root / "link"
        try:
            link.symlink_to(regular)
        except OSError:
            pass
        else:
            expect_failure(lambda: contained_path(root, "link", "self-test symlink"), "symlink path")

    minimal_legacy = {pair[0]: "sha256:" + "1" * 64 for pair in SEMANTIC_PAIRS}
    minimal_target = {pair[1]: "sha256:" + "2" * 64 for pair in SEMANTIC_PAIRS}
    base_mapping = {
        "schemaVersion": "1.0",
        "transformationVersion": "1.0.0",
        "legacyAuthorityRoot": LEGACY_ROOT_TEXT,
        "targetAuthorityRoot": TARGET_ROOT_TEXT,
        "mappings": [
            {
                "legacySchemaId": legacy,
                "legacyRawDigest": minimal_legacy[legacy],
                "targetSchemaId": target,
                "targetWireDigest": minimal_target[target],
                "transformationVersion": "1.0.0",
            }
            for legacy, target in sorted(SEMANTIC_PAIRS)
        ],
        "legacyDispositions": [],
        "targetDispositions": [],
    }
    validate_mapping_document(base_mapping, minimal_legacy, minimal_target)
    for key, bad in (
        ("legacyAuthorityRoot", "../legacy"),
        ("schemaVersion", "2.0"),
        ("transformationVersion", "1.1.0"),
    ):
        changed = dict(base_mapping)
        changed[key] = bad
        expect_failure(
            lambda document=changed: validate_mapping_document(
                document, minimal_legacy, minimal_target
            ),
            f"mapping {key}",
        )
    expanded_legacy = dict(minimal_legacy, extra="sha256:" + "3" * 64)
    bad_disposition = dict(base_mapping)
    bad_disposition["legacyDispositions"] = [
        {
            "schemaId": "extra",
            "digest": expanded_legacy["extra"],
            "disposition": "UNKNOWN",
            "reason": LEGACY_REASON,
        }
    ]
    expect_failure(
        lambda: validate_mapping_document(bad_disposition, expanded_legacy, minimal_target),
        "unknown mapping disposition",
    )
    bad_reason = json.loads(json.dumps(bad_disposition))
    bad_reason["legacyDispositions"][0]["disposition"] = LEGACY_DISPOSITION
    bad_reason["legacyDispositions"][0]["reason"] = "ad hoc"
    expect_failure(
        lambda: validate_mapping_document(bad_reason, expanded_legacy, minimal_target),
        "non-canonical disposition reason",
    )


def main(argv: list[str]) -> int:
    modes = {
        "--baseline-only",
        "--capture-check",
        "--static-only",
        "--index-check",
        "--self-test",
        "--capture-legacy-inventory",
    }
    if len(argv) != 1 or argv[0] not in modes:
        print("VERIFY_STEP0_FAIL: expected exactly one supported mode", file=sys.stderr)
        return 2
    try:
        run_self_tests()
        mode = argv[0]
        if mode == "--self-test":
            print("SELF_TEST_PASS")
        elif mode == "--capture-legacy-inventory":
            legacy_registry, legacy_digests = schema_registry(
                LEGACY_ROOT, enforce_numeric_profile=False
            )
            LEGACY_INVENTORY_PATH.write_text(
                json.dumps(
                    legacy_inventory_document(legacy_registry, legacy_digests), indent=2
                ) + "\n",
                encoding="utf-8",
            )
            print("LEGACY_INVENTORY_CAPTURED")
        elif mode in ("--baseline-only", "--capture-check"):
            validate_baseline(capture_check=mode == "--capture-check")
            print("BASELINE_PASS")
        elif mode == "--index-check":
            validate_baseline(capture_check=False)
            validate_index_authority()
            validate_index_schema_authority_and_mapping()
            print("STEP0_INDEX_PASS")
        else:
            validate_schema_authority_and_mapping()
            print("STEP0_STATIC_PASS")
        return 0
    except (VerificationError, OSError, subprocess.SubprocessError) as exc:
        print(f"VERIFY_STEP0_FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
