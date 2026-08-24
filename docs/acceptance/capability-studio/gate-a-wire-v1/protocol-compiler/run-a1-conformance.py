#!/usr/bin/env python3
"""Caller-owned A1 conformance boundary; dynamic execution is intentionally future work."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import pathlib
import stat
import zipfile
from typing import Any

from jsonschema import Draft202012Validator


MAX_JSON_BYTES = 8 * 1024 * 1024
HERE = pathlib.Path(os.path.abspath(os.path.dirname(__file__)))
REPO = HERE.parents[4]
AUTHORITY_DEFAULT = HERE / "gate-a-protocol-authority-v1.json"
SCHEMA_ROOT = REPO / "docs/schemas/resource-gateway-capability-studio"
PIN_SCHEMA = SCHEMA_ROOT / "capability-studio-gate-a-challenge-trust-pin-v1.schema.json"
AUTHORITY_SCHEMA = SCHEMA_ROOT / "capability-studio-gate-a-protocol-authority-v1.schema.json"


def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"A1_CONFORMANCE_DUPLICATE_MEMBER:{key}")
        result[key] = value
    return result


def reject_non_finite(value: str) -> None:
    raise ValueError(f"A1_CONFORMANCE_JSON_NON_FINITE:{value}")


def lexical_absolute(path: pathlib.Path | str, code: str) -> pathlib.Path:
    raw = os.fspath(path)
    if (
        not raw
        or "\0" in raw
        or "\\" in raw
        or raw.startswith("//")
        or "//" in raw
        or (raw != "/" and raw.endswith("/"))
    ):
        raise SystemExit(code)
    segments = raw[1:].split("/") if raw.startswith("/") else raw.split("/")
    if any(part in (".", "..", "") for part in segments):
        raise SystemExit(code)
    return pathlib.Path(os.path.abspath(raw))


def _flags(*names: str) -> int:
    if "O_NOFOLLOW" in names and not hasattr(os, "O_NOFOLLOW"):
        raise SystemExit("A1_CONFORMANCE_NOFOLLOW_UNAVAILABLE")
    return sum(getattr(os, name, 0) for name in names)


def _open_dir(path: pathlib.Path, code: str) -> int:
    path = lexical_absolute(path, code)
    flags = _flags("O_RDONLY", "O_DIRECTORY", "O_CLOEXEC", "O_NOFOLLOW")
    current = os.open(path.anchor or "/", flags)
    try:
        for part in path.parts[1:]:
            child = os.open(part, flags, dir_fd=current)
            os.close(current)
            current = child
            if not stat.S_ISDIR(os.fstat(current).st_mode):
                raise SystemExit(f"{code}_NON_DIRECTORY:{path}")
        return current
    except BaseException:
        os.close(current)
        raise


def _open_file(path: pathlib.Path, code: str) -> int:
    path = lexical_absolute(path, code)
    parent = _open_dir(path.parent, code)
    try:
        fd = os.open(path.name, _flags("O_RDONLY", "O_CLOEXEC", "O_NOFOLLOW"), dir_fd=parent)
    except OSError as error:
        os.close(parent)
        raise SystemExit(f"{code}_OPEN_FAILED:{path}:{error}") from error
    os.close(parent)
    return fd


def stable_bytes(path: pathlib.Path, limit: int, code: str) -> bytes:
    fd = _open_file(path, code)
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode):
            raise SystemExit(f"{code}_NOT_REGULAR_FILE:{path}")
        if before.st_nlink != 1:
            raise SystemExit(f"{code}_NLINK_NOT_ONE:{path}")
        if before.st_size > limit:
            raise SystemExit(f"{code}_SIZE_LIMIT:{path}")
        chunks: list[bytes] = []
        total = 0
        while True:
            part = os.read(fd, min(1024 * 1024, limit - total + 1))
            if not part:
                break
            total += len(part)
            if total > limit:
                raise SystemExit(f"{code}_SIZE_LIMIT:{path}")
            chunks.append(part)
        after = os.fstat(fd)
    except OSError as error:
        raise SystemExit(f"{code}_READ_FAILED:{path}:{error}") from error
    finally:
        os.close(fd)
    before_key = (before.st_dev, before.st_ino, before.st_mode, before.st_nlink, before.st_size, before.st_mtime_ns, before.st_ctime_ns)
    after_key = (after.st_dev, after.st_ino, after.st_mode, after.st_nlink, after.st_size, after.st_mtime_ns, after.st_ctime_ns)
    if before_key != after_key or after.st_nlink != 1 or total != after.st_size:
        raise SystemExit(f"{code}_FSTAT_READ_FSTAT_DRIFT:{path}")
    return b"".join(chunks)


def raw_fingerprint(raw: bytes) -> dict[str, str]:
    return {"kind": "RAW_BYTES", "algorithm": "SHA-256", "value": f"sha256:{hashlib.sha256(raw).hexdigest()}"}


def canonical(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")


def document_fingerprint(domain: bytes, document: dict[str, Any], self_field: str) -> dict[str, str]:
    material = dict(document)
    material[self_field] = None
    payload = domain + b"\0" + canonical(material)
    return {"kind": "CANONICAL_DOCUMENT", "algorithm": "SHA-256", "value": f"sha256:{hashlib.sha256(payload).hexdigest()}"}


def tree_fingerprint(entries: Any) -> dict[str, str]:
    payload = b"RG-CS-GATE-A-CHALLENGE-INPUT-ROOT-v1\0" + canonical(entries)
    return {"kind": "TREE_COMMITMENT", "algorithm": "SHA-256", "value": f"sha256:{hashlib.sha256(payload).hexdigest()}"}


def validate_zip_and_profile(raw: bytes, role: dict[str, Any], role_name: str) -> bytes | None:
    limits = role["artifactLimits"]
    if len(raw) > limits["maxRawBytes"]:
        raise SystemExit(f"A1_CONFORMANCE_ROLE_JAR_RAW_BYTES_LIMIT:{role_name}")
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            infos = archive.infolist()
            if len(infos) > limits["maxZipEntries"]:
                raise SystemExit(f"A1_CONFORMANCE_ROLE_JAR_ENTRY_COUNT_LIMIT:{role_name}")
            names = [info.filename for info in infos]
            if len(names) != len(set(names)):
                raise SystemExit(f"A1_CONFORMANCE_ROLE_JAR_DUPLICATE_ENTRY:{role_name}")
            total = 0
            logical_paths: set[str] = set()
            for info in infos:
                name = info.filename
                is_directory = info.is_dir()
                normalized = name[:-1] if is_directory else name
                segments = normalized.split("/")
                if (
                    not name
                    or name.startswith("/")
                    or "\\" in name
                    or not normalized
                    or any(part in ("", ".", "..") for part in segments)
                    or (name.endswith("/") != is_directory)
                ):
                    raise SystemExit(f"A1_CONFORMANCE_ROLE_JAR_ENTRY_PATH_INVALID:{role_name}")
                if normalized in logical_paths:
                    raise SystemExit(f"A1_CONFORMANCE_ROLE_JAR_ENTRY_PATH_COLLISION:{role_name}")
                logical_paths.add(normalized)
                total += info.file_size
                if info.file_size > limits["maxSingleEntryBytes"] or total > limits["maxTotalUncompressedBytes"]:
                    raise SystemExit(f"A1_CONFORMANCE_ROLE_JAR_UNCOMPRESSED_LIMIT:{role_name}")
                if info.file_size / max(info.compress_size, 1) > limits["maxCompressionRatio"]:
                    raise SystemExit(f"A1_CONFORMANCE_ROLE_JAR_COMPRESSION_RATIO_LIMIT:{role_name}")
                mode = (info.external_attr >> 16) & 0o170000
                expected_mode = stat.S_IFDIR if is_directory else stat.S_IFREG
                if mode not in (0, expected_mode):
                    raise SystemExit(f"A1_CONFORMANCE_ROLE_JAR_ENTRY_KIND_INVALID:{role_name}")
            if archive.testzip() is not None:
                raise SystemExit(f"A1_CONFORMANCE_ROLE_JAR_CRC_INVALID:{role_name}")
            if role["profilePath"] is None:
                return None
            matches = [info for info in infos if info.filename == role["profilePath"]]
            if len(matches) != 1 or matches[0].is_dir():
                raise SystemExit(f"A1_CONFORMANCE_PROFILE_NOT_EXACT_ONE_REGULAR:{role_name}")
            mode = (matches[0].external_attr >> 16) & 0o170000
            if mode and mode != stat.S_IFREG:
                raise SystemExit(f"A1_CONFORMANCE_PROFILE_NOT_REGULAR:{role_name}")
            profile = archive.read(matches[0])
            if len(profile) != matches[0].file_size:
                raise SystemExit(f"A1_CONFORMANCE_PROFILE_SIZE_DRIFT:{role_name}")
            return profile
    except (zipfile.BadZipFile, EOFError, OSError, ValueError) as error:
        raise SystemExit(f"A1_CONFORMANCE_ROLE_JAR_MALFORMED:{role_name}:{error}") from error


def challenge_input_inventory(inputs: pathlib.Path) -> tuple[set[str], set[str]]:
    observed_files: set[str] = set()
    observed_directories: set[str] = set()
    for directory, directories, files in os.walk(inputs, topdown=True, followlinks=False):
        directory_path = pathlib.Path(directory)
        for name in directories + files:
            child = directory_path / name
            metadata = os.lstat(child)
            relative = child.relative_to(inputs).as_posix()
            if stat.S_ISLNK(metadata.st_mode):
                raise SystemExit(f"A1_CONFORMANCE_INPUT_SYMLINK_REJECTED:{child}")
            if name in directories:
                if not stat.S_ISDIR(metadata.st_mode):
                    raise SystemExit(f"A1_CONFORMANCE_INPUT_ENTRY_KIND_INVALID:{child}")
                observed_directories.add(relative)
            else:
                if not stat.S_ISREG(metadata.st_mode):
                    raise SystemExit(f"A1_CONFORMANCE_INPUT_ENTRY_KIND_INVALID:{child}")
                observed_files.add(relative)
    return observed_files, observed_directories


def validate_challenge_input(root: pathlib.Path, pin: dict[str, Any]) -> None:
    inputs = root / "inputs"
    input_fd = _open_dir(inputs, "A1_CONFORMANCE_INPUT_ROOT")
    os.close(input_fd)
    tree_path = inputs / "challenge-root.tree"
    tree_raw = stable_bytes(tree_path, MAX_JSON_BYTES, "A1_CONFORMANCE_INPUT_TREE")
    try:
        tree = json.loads(tree_raw.decode("utf-8"), object_pairs_hook=reject_duplicates, parse_constant=reject_non_finite)
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
        raise SystemExit(f"A1_CONFORMANCE_INPUT_TREE_JSON_INVALID:{error}") from error
    if not isinstance(tree, dict):
        raise SystemExit("A1_CONFORMANCE_INPUT_TREE_INVALID")
    entries = tree.get("entries")
    if tree.get("rootKind") != "GATE_A_CHALLENGE_INPUT" or not isinstance(entries, list) or not entries:
        raise SystemExit("A1_CONFORMANCE_INPUT_TREE_INVALID")
    relative_paths: list[str] = []
    for entry in entries:
        if not isinstance(entry, dict):
            raise SystemExit("A1_CONFORMANCE_INPUT_TREE_INVALID")
        relative = entry.get("relativePath")
        if not isinstance(relative, str) or not relative or relative.startswith("/") or "\\" in relative or any(part in ("", ".", "..") for part in pathlib.PurePosixPath(relative).parts):
            raise SystemExit("A1_CONFORMANCE_INPUT_PATH_INVALID")
        relative_paths.append(relative)
    if relative_paths != sorted(relative_paths) or len(relative_paths) != len(set(relative_paths)):
        raise SystemExit("A1_CONFORMANCE_INPUT_TREE_ORDER_OR_DUPLICATE")
    expected_files = {"challenge-root.tree", *relative_paths}
    expected_directories = {
        parent.as_posix()
        for relative in relative_paths
        for parent in pathlib.PurePosixPath(relative).parents
        if parent.as_posix() != "."
    }
    actual_files, actual_directories = challenge_input_inventory(inputs)
    if actual_files != expected_files or actual_directories != expected_directories:
        raise SystemExit("A1_CONFORMANCE_INPUT_TREE_ENTRY_SET_DRIFT")
    for entry in entries:
        raw = stable_bytes(inputs / entry["relativePath"], MAX_JSON_BYTES, "A1_CONFORMANCE_INPUT_FILE")
        if entry.get("kind") != "FILE" or entry.get("byteLength") != len(raw) or entry.get("rawFingerprint") != raw_fingerprint(raw):
            raise SystemExit(f"A1_CONFORMANCE_INPUT_FILE_DRIFT:{entry['relativePath']}")
    if tree.get("rootFingerprint") != tree_fingerprint(entries) or pin.get("expectedChallengeInputRootFingerprint") != tree_fingerprint(entries):
        raise SystemExit("A1_CONFORMANCE_INPUT_ROOT_PIN_MISMATCH")

    observed_files_after, observed_directories_after = challenge_input_inventory(inputs)
    if observed_files_after != actual_files or observed_directories_after != actual_directories:
        raise SystemExit("A1_CONFORMANCE_INPUT_TREE_CHANGED_DURING_PREFLIGHT")


parser = argparse.ArgumentParser()
parser.add_argument("--authority", required=True)
parser.add_argument("--challenge-pin", required=True)
parser.add_argument("--challenge-input-root", required=True)
parser.add_argument("--output-root", required=True)
parser.add_argument("--candidate", required=True)
parser.add_argument("--provider", required=True)
parser.add_argument("--verifier", required=True)
parser.add_argument("--harness", required=True)
args = parser.parse_args()

authority_path = lexical_absolute(args.authority, "A1_CONFORMANCE_AUTHORITY_PATH_ALIAS")
if authority_path != lexical_absolute(AUTHORITY_DEFAULT, "A1_CONFORMANCE_AUTHORITY_PATH_ALIAS"):
    raise SystemExit("A1_CONFORMANCE_AUTHORITY_SOURCE_NOT_CANONICAL")
pin_path = lexical_absolute(args.challenge_pin, "A1_CONFORMANCE_PIN_PATH_ALIAS")
authority_raw = stable_bytes(authority_path, MAX_JSON_BYTES, "A1_CONFORMANCE_AUTHORITY")
pin_raw = stable_bytes(pin_path, MAX_JSON_BYTES, "A1_CONFORMANCE_PIN")
try:
    authority = json.loads(authority_raw.decode("utf-8"), object_pairs_hook=reject_duplicates, parse_constant=reject_non_finite)
    pin = json.loads(pin_raw.decode("utf-8"), object_pairs_hook=reject_duplicates, parse_constant=reject_non_finite)
    pin_schema = json.loads(stable_bytes(PIN_SCHEMA, MAX_JSON_BYTES, "A1_CONFORMANCE_SCHEMA").decode("utf-8"), object_pairs_hook=reject_duplicates, parse_constant=reject_non_finite)
    authority_schema = json.loads(stable_bytes(AUTHORITY_SCHEMA, MAX_JSON_BYTES, "A1_CONFORMANCE_SCHEMA").decode("utf-8"), object_pairs_hook=reject_duplicates, parse_constant=reject_non_finite)
except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
    raise SystemExit(f"A1_CONFORMANCE_JSON_INVALID:{error}") from error
errors = sorted(Draft202012Validator(authority_schema).iter_errors(authority), key=lambda error: list(error.absolute_path))
if errors:
    raise SystemExit(f"A1_CONFORMANCE_AUTHORITY_SCHEMA_INVALID:{errors[0].message}")
errors = sorted(Draft202012Validator(pin_schema).iter_errors(pin), key=lambda error: list(error.absolute_path))
if errors:
    raise SystemExit(f"A1_CONFORMANCE_CHALLENGE_PIN_SCHEMA_INVALID:{errors[0].message}")
if pin.get("challengeTrustPinFingerprint") != document_fingerprint(
    b"RG-CS-GATE-A-CHALLENGE-TRUST-PIN-v1",
    pin,
    "challengeTrustPinFingerprint",
):
    raise SystemExit("A1_CONFORMANCE_CHALLENGE_PIN_SELF_FINGERPRINT_DRIFT")
if pin.get("expectedProtocolAuthorityRawFingerprint") != raw_fingerprint(authority_raw):
    raise SystemExit("A1_CONFORMANCE_PROTOCOL_AUTHORITY_PIN_MISMATCH")
if authority.get("authorityId") != "GATE-A-PROTOCOL-AUTHORITY" or authority.get("revision") != pin.get("allowedGateRevision"):
    raise SystemExit("A1_CONFORMANCE_AUTHORITY_REVISION_MISMATCH")

input_root = lexical_absolute(args.challenge_input_root, "A1_CONFORMANCE_INPUT_PATH_ALIAS")
validate_challenge_input(input_root, pin)
output_root = lexical_absolute(args.output_root, "A1_CONFORMANCE_OUTPUT_PATH_ALIAS")
if output_root == pathlib.Path(output_root.anchor or "/"):
    raise SystemExit("A1_CONFORMANCE_OUTPUT_ROOT_INVALID")
output_parent = _open_dir(output_root.parent, "A1_CONFORMANCE_OUTPUT_PARENT")
try:
    try:
        os.stat(output_root.name, dir_fd=output_parent, follow_symlinks=False)
    except FileNotFoundError:
        pass
    else:
        raise SystemExit(f"A1_CONFORMANCE_OUTPUT_ROOT_MUST_BE_CREATE_NEW:{output_root}")
finally:
    os.close(output_parent)

roles = {role["role"]: role for role in authority["roleContracts"]}
artifact_arguments = {
    "IMPLEMENTATION_CANDIDATE": (args.candidate, "expectedImplementationCandidateRawFingerprint"),
    "TCK_PROVIDER": (args.provider, "expectedTckProviderRawFingerprint"),
    "INDEPENDENT_VERIFIER": (args.verifier, "expectedIndependentVerifierRawFingerprint"),
    "CONFORMANCE_HARNESS": (args.harness, "expectedConformanceHarnessRawFingerprint"),
}
snapshots: dict[str, bytes] = {}
profiles: dict[str, bytes] = {}
for role_name, (raw_path, pin_field) in artifact_arguments.items():
    path = lexical_absolute(raw_path, "A1_CONFORMANCE_ARTIFACT_PATH_ALIAS")
    role = roles[role_name]
    expected_path = lexical_absolute(REPO / role["artifactPath"], "A1_CONFORMANCE_ARTIFACT_PATH_ALIAS")
    if path != expected_path:
        raise SystemExit(f"A1_CONFORMANCE_ARTIFACT_PATH_NOT_AUTHORITY_BOUND:{role_name}")
    raw = stable_bytes(path, role["artifactLimits"]["maxRawBytes"], "A1_CONFORMANCE_ARTIFACT")
    profile = validate_zip_and_profile(raw, role, role_name)
    if raw_fingerprint(raw) != pin[pin_field]:
        raise SystemExit(f"A1_CONFORMANCE_ARTIFACT_PIN_MISMATCH:{role_name}")
    if profile is not None:
        profiles[role_name] = profile
    snapshots[role_name] = raw
if raw_fingerprint(profiles["INDEPENDENT_VERIFIER"]) != pin["expectedReplayProfileRawFingerprint"]:
    raise SystemExit("A1_CONFORMANCE_REPLAY_PROFILE_PIN_MISMATCH")
if raw_fingerprint(profiles["CONFORMANCE_HARNESS"]) != pin["expectedHarnessProfileRawFingerprint"]:
    raise SystemExit("A1_CONFORMANCE_HARNESS_PROFILE_PIN_MISMATCH")

# Dynamic candidate/provider/verifier/harness execution remains deliberately fail-closed.
raise SystemExit("A1_CONFORMANCE_RUNNER_IMPLEMENTATION_PENDING")
