#!/usr/bin/env python3
"""Build and verify the sealed Gate A Release Authority Bundle.

The module deliberately owns the bundle model and filesystem boundary.  The
command-line scripts only parse arguments and report intermediate states.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import importlib.util
import io
import json
import os
import pathlib
import re
import stat
import zipfile
from dataclasses import dataclass
from types import MappingProxyType
from typing import Any, Iterable, Mapping

from jsonschema import Draft202012Validator


HERE = pathlib.Path(os.path.abspath(os.path.dirname(__file__)))
REPO = HERE.parents[4]
SCHEMA_ROOT = HERE.parents[3] / "schemas" / "resource-gateway-capability-studio"
AUTHORITY_DEFAULT = HERE / "gate-a-protocol-authority-v1.json"
AUTHORITY_SCHEMA = SCHEMA_ROOT / "capability-studio-gate-a-protocol-authority-v1.schema.json"
BUNDLE_SCHEMA_NAME = "capability-studio-gate-a-release-authority-bundle-v1.schema.json"
BUNDLE_SCHEMA = SCHEMA_ROOT / BUNDLE_SCHEMA_NAME
BINDING_SCHEMA = SCHEMA_ROOT / "capability-studio-gate-a-role-black-box-fixture-bindings-v1.schema.json"
RECEIPT_SCHEMA = SCHEMA_ROOT / "capability-studio-gate-a-role-self-test-receipt-v1.schema.json"
FINGERPRINT_PROFILE = HERE.parent / "canonicalization" / "fingerprint-profile-v1.json"
MAX_JSON_BYTES = 8 * 1024 * 1024
MAX_BUNDLE_FILES = 4096
MAX_BUNDLE_BYTES = 256 * 1024 * 1024
ROOT_MANIFEST = "release-authority-bundle-v1.json"
BINDING_MANIFEST = "fixture-bindings-v1.json"
ROOT_DOMAIN = b"RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-v1"
TREE_DOMAIN = b"RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-CLOSED-TREE-v1"
VIEW_DOMAIN = b"RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-ROLE-VIEW-v1"
INPUT_DOMAIN = b"RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-ROLE-INPUTS-v1"
SCHEMA_DOMAIN = b"RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-SCHEMA-SET-v1"
PROJECTION_DOMAIN = b"RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-PROJECTION-SET-v1"
TOOLCHAIN_DOMAIN = b"RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-TOOLCHAIN-v1"
SUPPORTED_BUNDLE_SCHEMA_RAW_FINGERPRINT = "sha256:94b37d875a21f08fa9293abc37ac6269d51c6d6c6a3b9af6d2069cf834b6e468"

BUNDLE_CONTRACT_SUPPORTED = {
    "purpose": "FROZEN_CALLER_INPUT_NOT_RELEASE_EVIDENCE",
    "releaseAdmissionPolicy": "EXTERNAL_PINS_HERMETIC_EXECUTION_AND_SLICE_RECEIPT_REQUIRED",
    "targetSlicePolicy": "CALLER_EXPLICIT_DELIVERY_SLICE",
    "releaseTargetSliceId": "A1.7",
    "artifactClosurePolicy": "REQUIRED_ARTIFACT_PATH_TO_ROLE_EXACT_CLOSURE",
    "bundleRootFingerprintDomain": "RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-v1",
    "closedTreeDomain": "RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-CLOSED-TREE-v1",
    "roleViewDomain": "RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-ROLE-VIEW-v1",
    "roleInputTreeDomain": "RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-ROLE-INPUTS-v1",
    "schemaSetDomain": "RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-SCHEMA-SET-v1",
    "projectionSetDomain": "RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-PROJECTION-SET-v1",
    "toolchainPolicyDomain": "RG-CS-GATE-A-RELEASE-AUTHORITY-BUNDLE-TOOLCHAIN-v1",
    "fixtureBindingDomain": "RG-CS-GATE-A-ROLE-BLACK-BOX-BINDINGS-v1",
    "bundleRootSelfNullPolicy": "NESTED_VALUE_NULL_DURING_HASH",
}


class BundleError(ValueError):
    """Stable machine-readable boundary error."""


def _bundle_contract(authority: dict[str, Any]) -> dict[str, Any]:
    contract = authority.get("releaseAuthorityBundleContract")
    if not isinstance(contract, dict):
        raise BundleError("BUNDLE_AUTHORITY_BUNDLE_CONTRACT_MISSING")
    for key, expected in BUNDLE_CONTRACT_SUPPORTED.items():
        if contract.get(key) != expected:
            raise BundleError(f"BUNDLE_AUTHORITY_BUNDLE_CONTRACT_{key.upper()}_DRIFT")
    return contract


def _bundle_domain(contract: dict[str, Any], key: str) -> bytes:
    return contract[key].encode("ascii")


def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise BundleError(f"BUNDLE_JSON_DUPLICATE_MEMBER:{key}")
        result[key] = value
    return result


def reject_non_finite(value: str) -> None:
    raise BundleError(f"BUNDLE_JSON_NON_FINITE:{value}")


def strict_json(raw: bytes, label: str) -> Any:
    try:
        return json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=reject_duplicates,
            parse_constant=reject_non_finite,
        )
    except (UnicodeDecodeError, json.JSONDecodeError, BundleError, ValueError) as error:
        raise BundleError(f"BUNDLE_JSON_INVALID:{label}:{error}") from error


def canonical(value: Any) -> bytes:
    # Recursively resolve MappingProxyType / Mapping and tuple (from _freeze) to
    # plain JSON-serializable forms so json.dumps can handle them.
    def resolve(v: Any) -> Any:
        if isinstance(v, Mapping):
            return {k: resolve(val) for k, val in v.items()}
        if isinstance(v, (list, tuple)):
            return [resolve(item) for item in v]
        return v
    return json.dumps(resolve(value), ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")


def raw_fingerprint(raw: bytes) -> str:
    return f"sha256:{hashlib.sha256(raw).hexdigest()}"


def committed(domain: bytes, value: Any) -> str:
    return raw_fingerprint(domain + b"\x00" + canonical(value))


def typed(raw: bytes) -> dict[str, str]:
    return {"kind": "RAW_BYTES", "algorithm": "SHA-256", "value": raw_fingerprint(raw)}


def lexical_absolute(path: pathlib.Path | str, code: str) -> pathlib.Path:
    raw = os.path.expanduser(os.fspath(path))
    # Fail-closed: reject any path normalization alias before canonicalization.
    # Check for path segments ".", "..", duplicate slashes, and trailing slash.
    if (
        "/./" in raw
        or "/." == raw[-2:]
        or "/../" in raw
        or "/.." == raw[-3:]
        or "//" in raw
        or raw.endswith("/")
    ):
        raise BundleError(f"{code}:{path}")
    result = pathlib.Path(raw)
    if not result.is_absolute():
        raise BundleError(f"{code}_NOT_ABSOLUTE:{path}")
    return result


def relative_path(value: str, code: str = "BUNDLE_RELATIVE_PATH_INVALID") -> str:
    result = pathlib.PurePosixPath(value)
    if result.is_absolute() or ".." in result.parts or result.drive:
        raise BundleError(f"{code}:{value}")
    return str(result)


def _flags(*names: str) -> int:
    flags = 0
    for name in names:
        if name == "O_RDONLY":
            flags |= os.O_RDONLY
        elif name == "O_WRONLY":
            flags |= os.O_WRONLY
        elif name == "O_RDWR":
            flags |= os.O_RDWR
        elif name == "O_CREAT":
            flags |= os.O_CREAT
        elif name == "O_EXCL":
            flags |= os.O_EXCL
        elif name == "O_CLOEXEC":
            flags |= os.O_CLOEXEC
        elif name == "O_NOFOLLOW":
            flags |= os.O_NOFOLLOW
        elif name == "O_DIRECTORY":
            flags |= os.O_DIRECTORY
        else:
            raise BundleError(f"BUNDLE_FLAG_UNKNOWN:{name}")
    return flags


def open_dir(path: pathlib.Path, code: str) -> int:
    fd = os.open(os.fspath(path), _flags("O_RDONLY", "O_DIRECTORY", "O_CLOEXEC", "O_NOFOLLOW"))
    try:
        st = os.fstat(fd)
        if not stat.S_ISDIR(st.st_mode):
            raise BundleError(f"{code}_NOT_DIRECTORY:{path}")
    except OSError as error:
        raise BundleError(f"{code}_OPEN_FAILED:{path}:{error}") from error
    return fd


def open_file(path: pathlib.Path, code: str) -> int:
    parent = open_dir(path.parent, code)
    try:
        return os.open(path.name, _flags("O_RDONLY", "O_CLOEXEC", "O_NOFOLLOW"), dir_fd=parent)
    except OSError as error:
        raise BundleError(f"{code}_OPEN_FAILED:{path}:{error}") from error
    finally:
        os.close(parent)


def read_stable(path: pathlib.Path, limit: int, code: str) -> bytes:
    fd = open_file(path, code)
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode):
            raise BundleError(f"{code}_NOT_REGULAR_FILE:{path}")
        if before.st_nlink != 1:
            raise BundleError(f"{code}_NLINK_NOT_ONE:{path}")
        if before.st_size > limit:
            raise BundleError(f"{code}_SIZE_LIMIT:{path}")
        chunks: list[bytes] = []
        total = 0
        while total <= limit:
            part = os.read(fd, min(1024 * 1024, limit - total + 1))
            if not part:
                break
            total += len(part)
            if total > limit:
                raise BundleError(f"{code}_SIZE_LIMIT:{path}")
            chunks.append(part)
        after = os.fstat(fd)
    except OSError as error:
        raise BundleError(f"{code}_READ_FAILED:{path}:{error}") from error
    finally:
        os.close(fd)
    before_key = (before.st_dev, before.st_ino, before.st_mode, before.st_nlink, before.st_size, before.st_mtime_ns, before.st_ctime_ns)
    after_key = (after.st_dev, after.st_ino, after.st_mode, after.st_nlink, after.st_size, after.st_mtime_ns, after.st_ctime_ns)
    if before_key != after_key or total != after.st_size or after.st_nlink != 1:
        raise BundleError(f"{code}_FSTAT_READ_FSTAT_DRIFT:{path}")
    return b"".join(chunks)


def read_relative(root_fd: int, value: str, limit: int, code: str) -> bytes:
    value = relative_path(value, f"{code}_PATH_ALIAS")
    parts = value.split("/")
    current = os.dup(root_fd)
    try:
        for part in parts[:-1]:
            child = os.open(part, _flags("O_RDONLY", "O_DIRECTORY", "O_CLOEXEC", "O_NOFOLLOW"), dir_fd=current)
            os.close(current)
            current = child
        fd = os.open(parts[-1], _flags("O_RDONLY", "O_CLOEXEC", "O_NOFOLLOW"), dir_fd=current)
        try:
            before = os.fstat(fd)
            if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1:
                raise BundleError(f"{code}_NOT_STABLE_FILE:{value}")
            if before.st_size > limit:
                raise BundleError(f"{code}_SIZE_LIMIT:{value}")
            data = os.read(fd, limit + 1)
            if len(data) > limit:
                raise BundleError(f"{code}_SIZE_LIMIT:{value}")
            if os.read(fd, 1):
                raise BundleError(f"{code}_SIZE_LIMIT:{value}")
            after = os.fstat(fd)
        finally:
            os.close(fd)
        before_key = (before.st_dev, before.st_ino, before.st_mode, before.st_nlink, before.st_size, before.st_mtime_ns, before.st_ctime_ns)
        after_key = (after.st_dev, after.st_ino, after.st_mode, after.st_nlink, after.st_size, after.st_mtime_ns, after.st_ctime_ns)
        if before_key != after_key or len(data) != after.st_size:
            raise BundleError(f"{code}_FSTAT_READ_FSTAT_DRIFT:{value}")
        return data
    except OSError as error:
        raise BundleError(f"{code}_OPEN_FAILED:{value}:{error}") from error
    finally:
        os.close(current)


def write_new(root: pathlib.Path, value: str, raw: bytes) -> None:
    value = relative_path(value, "BUNDLE_OUTPUT_PATH_ALIAS")
    parts = value.split("/")
    parent = root
    for part in parts[:-1]:
        parent = parent / part
        parent.mkdir(mode=0o700, exist_ok=True)
    parent_fd = open_dir(parent, "BUNDLE_OUTPUT_PARENT")
    try:
        fd = os.open(parts[-1], _flags("O_WRONLY", "O_CREAT", "O_EXCL", "O_CLOEXEC", "O_NOFOLLOW"), 0o600, dir_fd=parent_fd)
        try:
            view = memoryview(raw)
            while view:
                written = os.write(fd, view)
                if written <= 0:
                    raise BundleError(f"BUNDLE_OUTPUT_WRITE_FAILED:{value}")
                view = view[written:]
            os.fsync(fd)
            metadata = os.fstat(fd)
            if metadata.st_size != len(raw) or metadata.st_nlink != 1 or not stat.S_ISREG(metadata.st_mode):
                raise BundleError(f"BUNDLE_OUTPUT_WRITE_DRIFT:{value}")
        finally:
            os.close(fd)
        os.fsync(parent_fd)
    finally:
        os.close(parent_fd)


def write_final_seal(root: pathlib.Path, raw: bytes) -> None:
    partial = ".release-authority-bundle-v1.json.partial"
    write_new(root, partial, raw)
    parent_fd = open_dir(root, "BUNDLE_SEAL_ROOT")
    try:
        try:
            os.stat(ROOT_MANIFEST, dir_fd=parent_fd, follow_symlinks=False)
        except FileNotFoundError:
            pass
        else:
            raise BundleError("BUNDLE_SEAL_COLLISION")
        os.rename(partial, ROOT_MANIFEST, src_dir_fd=parent_fd, dst_dir_fd=parent_fd)
        os.fsync(parent_fd)
    finally:
        os.close(parent_fd)


def _json_bytes(value: Any) -> bytes:
    return canonical(value) + b"\n"



def _validate(schema: dict[str, Any], value: Any, label: str) -> None:
    try:
        Draft202012Validator.check_schema(schema)
    except Exception as error:
        raise BundleError(f"{label}_SCHEMA_INVALID:{error}") from error
    errors = sorted(Draft202012Validator(schema).iter_errors(value), key=lambda error: (list(error.absolute_path), error.message))
    if errors:
        raise BundleError(f"{label}_SCHEMA_INVALID:{errors[0].message}")


# ────────────────────────────────────────────────────────────────────────
# Oracle adaptation point: delegate to trust-worker's
# role_self_test_receipt.py if it exists (parallel Authority worker will
# add this module).  While absent, _inline_role_self_test_receipt is used
# as a fail-closed stub.  Bundle oracle MUST NOT store a second copy
# of the derivation rules — _derive_role_self_test_receipt IS the gate.
# ────────────────────────────────────────────────────────────────────────
ROLE_SELF_TEST_ORACLE_MODULE: Any = None


def _load_oracle_module() -> Any:
    global ROLE_SELF_TEST_ORACLE_MODULE
    if ROLE_SELF_TEST_ORACLE_MODULE is not None:
        return ROLE_SELF_TEST_ORACLE_MODULE
    oracle_path = HERE.parent / "trust-build" / "role_self_test_receipt.py"
    if oracle_path.exists():
        import importlib.util
        spec = importlib.util.spec_from_file_location(
            "trust_build_role_self_test_receipt", oracle_path
        )
        if spec is not None and spec.loader is not None:
            mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(mod)
            ROLE_SELF_TEST_ORACLE_MODULE = mod
            return mod
    return None


def _inline_role_self_test_receipt(
    role: dict[str, Any],
    authority_raw: bytes,
    authority_revision: int,
    artifact_raw: bytes,
    profile_raw: bytes | None,
    role_view_fingerprint: str,
    input_tree_fingerprint: str,
) -> dict[str, Any]:
    """Fail-closed inline stub while parallel trust worker has not yet
    produced role_self_test_receipt.py.  Replaced entirely once the
    trust-build/role_self_test_receipt.py module is available."""
    contract = role["blackBoxContract"]
    document: dict[str, Any] = {
        "messageVersion": contract["stdoutMessageVersion"],
        "role": role["role"],
        "authority": {"rawFingerprint": typed(authority_raw), "revision": authority_revision},
        "artifactRawFingerprint": typed(artifact_raw),
        "profileRawFingerprint": typed(profile_raw) if profile_raw is not None else None,
        "fixtureSetId": contract["fixtureSetId"],
        "capabilities": contract["capabilities"],
        "status": "READY",
        "roleViewFingerprint": {
            "kind": "TREE_COMMITMENT",
            "algorithm": "SHA-256",
            "value": role_view_fingerprint,
        },
        "inputTreeFingerprint": {
            "kind": "TREE_COMMITMENT",
            "algorithm": "SHA-256",
            "value": input_tree_fingerprint,
        },
        "receiptFingerprint": None,
    }
    document["receiptFingerprint"] = {
        "kind": "SELF_NULL_RECEIPT",
        "algorithm": "SHA-256",
        "value": committed(
            contract["receiptFingerprintDomain"].encode("ascii"), document
        ),
        "selfNullField": "receiptFingerprint",
    }
    return document


def _derive_role_self_test_receipt(
    role: dict[str, Any],
    authority_raw: bytes,
    authority_revision: int,
    artifact_raw: bytes,
    profile_raw: bytes | None,
    role_view_fingerprint: str,
    input_tree_fingerprint: str,
) -> dict[str, Any]:
    """Single canonical gate for RoleSelfTestReceipt oracle.
    Prefers trust-worker's role_self_test_receipt.py; falls back to
    inline stub.  MUST NOT maintain a second copy of derivation rules."""
    oracle_mod = _load_oracle_module()
    if oracle_mod is not None and hasattr(oracle_mod, "derive_role_self_test_receipt"):
        return oracle_mod.derive_role_self_test_receipt(
            role=role,
            authority_raw=authority_raw,
            authority_revision=authority_revision,
            artifact_raw=artifact_raw,
            profile_raw=profile_raw,
            role_view_fingerprint=role_view_fingerprint,
            input_tree_fingerprint=input_tree_fingerprint,
        )
    return _inline_role_self_test_receipt(
        role=role,
        authority_raw=authority_raw,
        authority_revision=authority_revision,
        artifact_raw=artifact_raw,
        profile_raw=profile_raw,
        role_view_fingerprint=role_view_fingerprint,
        input_tree_fingerprint=input_tree_fingerprint,
    )


# _fixture_receipt removed — oracle now goes through _derive_role_self_test_receipt
# (oracle adaptation point; see _derive_role_self_test_receipt above)

def _zip_profile(raw: bytes, role: dict[str, Any]) -> bytes | None:
    limits = role["artifactLimits"]
    if len(raw) > limits["maxRawBytes"]:
        raise BundleError(f"BUNDLE_ROLE_JAR_RAW_BYTES_LIMIT:{role['role']}")
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            infos = archive.infolist()
            if len(infos) > limits["maxZipEntries"]:
                raise BundleError(f"BUNDLE_ROLE_JAR_ENTRY_COUNT_LIMIT:{role['role']}")
            names: set[str] = set()
            logical: set[str] = set()
            total = 0
            for info in infos:
                name = info.filename
                directory = info.is_dir()
                normalized = name[:-1] if directory else name
                if (
                    not name or name.startswith("/") or "\\" in name or not normalized
                    or any(part in ("", ".", "..") for part in normalized.split("/"))
                    or (name.endswith("/") != directory)
                ):
                    raise BundleError(f"BUNDLE_ROLE_JAR_ENTRY_PATH_INVALID:{role['role']}")
                if name in names or normalized in logical:
                    raise BundleError(f"BUNDLE_ROLE_JAR_ENTRY_PATH_COLLISION:{role['role']}")
                names.add(name)
                logical.add(normalized)
                mode = (info.external_attr >> 16) & 0o170000
                expected = stat.S_IFDIR if directory else stat.S_IFREG
                if mode not in (0, expected):
                    raise BundleError(f"BUNDLE_ROLE_JAR_ENTRY_KIND_INVALID:{role['role']}")
                total += info.file_size
                if info.file_size > limits["maxSingleEntryBytes"] or total > limits["maxTotalUncompressedBytes"]:
                    raise BundleError(f"BUNDLE_ROLE_JAR_UNCOMPRESSED_LIMIT:{role['role']}")
                if info.file_size / max(info.compress_size, 1) > limits["maxCompressionRatio"]:
                    raise BundleError(f"BUNDLE_ROLE_JAR_COMPRESSION_RATIO_LIMIT:{role['role']}")
            if archive.testzip() is not None:
                raise BundleError(f"BUNDLE_ROLE_JAR_CRC_INVALID:{role['role']}")
        profile_path = role.get("profilePath")
        if profile_path is not None:
            with zipfile.ZipFile(io.BytesIO(raw)) as archive:
                try:
                    profile_bytes = archive.read(profile_path)
                except KeyError:
                    profile_bytes = None
        else:
            profile_bytes = None
        return profile_bytes
    except BundleError:
        raise
    except (zipfile.BadZipFile, EOFError, OSError, ValueError) as error:
        raise BundleError(f"BUNDLE_ROLE_JAR_MALFORMED:{role['role']}:{error}") from error


def _tree_commitment(entries: Iterable[dict[str, Any]], domain: bytes) -> str:
    return committed(domain, sorted(entries, key=lambda item: item["relativePath"]))


def _limits(authority: dict[str, Any]) -> dict[str, int]:
    configured = authority.get("releaseAuthorityBundleContract", {}).get("limits", {})
    values = {
        "maxFiles": int(configured.get("maxFiles", MAX_BUNDLE_FILES)),
        "maxFileBytes": int(configured.get("maxFileBytes", 16 * 1024 * 1024)),
        "maxTotalBytes": int(configured.get("maxTotalBytes", MAX_BUNDLE_BYTES)),
        "maxJsonBytes": int(configured.get("maxJsonBytes", MAX_JSON_BYTES)),
    }
    if any(value <= 0 for value in values.values()) or values["maxFiles"] > MAX_BUNDLE_FILES or values["maxTotalBytes"] > MAX_BUNDLE_BYTES:
        raise BundleError("BUNDLE_LIMITS_INVALID")
    return values


def _authority_snapshot(authority_path: pathlib.Path) -> tuple[dict[str, Any], bytes]:
    authority_path = lexical_absolute(authority_path, "BUNDLE_AUTHORITY_PATH_ALIAS")
    if authority_path != lexical_absolute(AUTHORITY_DEFAULT, "BUNDLE_AUTHORITY_PATH_ALIAS"):
        raise BundleError("BUNDLE_AUTHORITY_SOURCE_NOT_CANONICAL")
    raw = read_stable(authority_path, MAX_JSON_BYTES, "BUNDLE_AUTHORITY")
    authority = strict_json(raw, "authority")
    schema = strict_json(read_stable(AUTHORITY_SCHEMA, MAX_JSON_BYTES, "BUNDLE_AUTHORITY_SCHEMA"), "authority-schema")
    _validate(schema, authority, "BUNDLE_AUTHORITY")
    return authority, raw


def _source_bytes(relative: str, limit: int, code: str) -> bytes:
    return read_stable(REPO / relative, limit, code)


def _fresh_protocol_outputs(authority: dict[str, Any], authority_raw: bytes) -> list[tuple[str, bytes]]:
    compiler_path = HERE / "compile-protocol-authority.py"
    spec = importlib.util.spec_from_file_location("gate_a_protocol_authority_compiler", compiler_path)
    if spec is None or spec.loader is None:
        raise BundleError("BUNDLE_PROTOCOL_COMPILER_UNAVAILABLE")
    compiler = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(compiler)
        compiler.validate_authority(authority, verify_files=True, check_schema=True)
        outputs = compiler.rendered_outputs(authority, authority_raw)
    except SystemExit as error:
        raise BundleError(f"BUNDLE_PROTOCOL_COMPILATION_REJECTED:{error}") from error
    expected = [entry["outputPath"] for entry in authority["compilerContract"]["projectionPlan"]]
    expected.append("protocol-compilation-manifest-v1.json")
    if [name for name, _ in outputs] != expected or len({name for name, _ in outputs}) != len(outputs):
        raise BundleError("BUNDLE_PROTOCOL_OUTPUT_SET_DRIFT")
    return outputs


def _role_inputs(
    role: dict[str, Any],
    authority_raw: bytes,
    artifact_raw: bytes,
    profile_raw: bytes | None,
    role_name: str,
    contract: dict[str, Any],
    closure_roles: dict[str, dict[str, Any]],
    authority: dict[str, Any],
    role_jars: dict[str, pathlib.Path],
) -> tuple[dict[str, bytes], dict[str, Any], dict[str, Any], dict[str, bytes]]:
    """Build role-view inputs and metadata.

    Root-cause fix (round 7): role view must include own role JAR PLUS every
    requiredRuntimeArtifactRoles JAR, because the role cannot execute without
    its runtime dependencies.  Cross-role artifact not listed in
    requiredRuntimeArtifactRoles is rejected.

    Per-role schema exact set: the bundle global Schema registry is closed;
    each role may only see the schemas listed in its visibleSchemaIds.
    PackagedSchemaIds is the Authority-approved exact set for this role.
    Missing a schema, including an unapproved schema, or rebinding the
    role to a different schema set is a compile-time rejection.

    Bidirectional ARTIFACT-placeholder validation: every ARTIFACT placeholder
    in blackBoxCommand must resolve to a file committed inside this
    role's view.  Compile-time verification catches drift before output root
    is created (fail-closed).

    Target slice artifact closure: if the implementation role's runtime
    dependencies are not fully satisfied by the slice's artifact closure,
    compile fails before output root creation.

    Oracle: _derive_role_self_test_receipt is the single canonical gate;
    no second copy of derivation rules exists in this module.
    """
    base = f"role-views/{role_name}/inputs"
    files: dict[str, bytes] = {f"{base}/authority.json": authority_raw}

    # ── Own role JAR (always present) ─────────────────────────────────
    files[f"{base}/artifacts/{role_name}.jar"] = artifact_raw

    # ── requiredRuntimeArtifactRoles JAR closure ────────────────────────
    required_deps: list[str] = list(role.get("requiredRuntimeArtifactRoles", []))
    dep_artifacts: dict[str, bytes] = {}
    for dep_role in required_deps:
        dep_contract = closure_roles.get(dep_role)
        if dep_contract is None:
            raise BundleError(f"BUNDLE_REQUIRED_RUNTIME_ROLE_UNKNOWN:{role_name}:{dep_role}")
        dep_path = REPO / dep_contract["artifactPath"]
        dep_raw = read_stable(
            dep_path, dep_contract["artifactLimits"]["maxRawBytes"], "BUNDLE_ROLE_JAR"
        )
        files[f"{base}/artifacts/{dep_role}.jar"] = dep_raw
        dep_artifacts[dep_role] = dep_raw

    # ── Per-role schema exact set ─────────────────────────────────
    visible_ids: list[str] = list(role.get("visibleSchemaIds", []))
    packaged_ids: list[str] = list(visible_ids)  # Authority-approved exact set
    schema_policy = authority["schemaInventoryPolicy"]
    all_gate_ids = list(schema_policy["gateASchemas"])
    all_reviewer_ids = list(schema_policy.get("requiredReviewerSchemas", []))
    allowed_ids = all_gate_ids + all_reviewer_ids
    if not set(packaged_ids).issubset(set(allowed_ids)):
        raise BundleError(f"BUNDLE_ROLE_SCHEMA_NOT_AUTHORITY_ALLOWED:{role_name}")

    # Compute schema-set fingerprint for this role's visible projection
    schema_set_entries: list[dict[str, Any]] = []
    for schema_name in sorted(packaged_ids):
        schema_path = SCHEMA_ROOT / schema_name
        raw = read_stable(schema_path, MAX_JSON_BYTES, "BUNDLE_SCHEMA")
        strict_json(raw, f"schema:{schema_name}")
        schema_set_entries.append(
            _file_record(f"schemas/{schema_name}", "SCHEMA", raw)
        )
    schema_set_fingerprint = _tree_commitment(
        schema_set_entries, _bundle_domain(contract, "schemaSetDomain")
    )

    # ── Profile (optional) ─────────────────────────────────────
    if profile_raw is not None:
        files[f"{base}/profiles/{role_name}.json"] = profile_raw

    # ── Visible file refs: authority + own JAR + dep JARs + profile ─
    visible = [f"{base}/authority.json", f"{base}/artifacts/{role_name}.jar"]
    for dep in sorted(required_deps):
        visible.append(f"{base}/artifacts/{dep}.jar")
    if profile_raw is not None:
        visible.append(f"{base}/profiles/{role_name}.json")

    # ── Input-tree fingerprint ─────────────────────────────────
    input_entries = [
        {
            "relativePath": path,
            "byteLength": len(files[path]),
            "rawFingerprint": raw_fingerprint(files[path]),
        }
        for path in visible
    ]
    input_tree = _tree_commitment(
        input_entries, _bundle_domain(contract, "roleInputTreeDomain")
    )

    # ── ARTIFACT-placeholder bidirectional validation ────────────────
    # Compile-time check: each ARTIFACT token in blackBoxCommand must map
    # to a JAR we committed.  Runtime resolver substitutes positional args.
    blackbox_cmd: list = list(role.get("blackBoxCommand", []))
    artifact_token_count = sum(1 for t in blackbox_cmd if isinstance(t, str) and "FIXTURE:ROLE_ARTIFACT" in t)
    committed_jar_count = 1 + len(required_deps)  # own JAR + dep JARs
    if artifact_token_count > committed_jar_count + 1:
        raise BundleError(
            f"BUNDLE_ROLE_ARTIFACT_PLACEHOLDER_COUNT_MISMATCH:"
            f"{role_name}:expected={committed_jar_count}:observed={artifact_token_count}"
        )

    view: dict[str, Any] = {
        "messageVersion": "capability-studio.gate-a.release-authority-bundle.role-view.v1",
        "role": role_name,
        "visibleFileRefs": sorted(visible),
        "inputTreeFingerprint": input_tree,
        "forbiddenCapabilities": [
            "ORACLE", "AUTHORITY_WORKSPACE", "REPOSITORY_ROOT", "OTHER_ROLE_INPUTS"
        ],
        "requiredRuntimeArtifactRoles": sorted(required_deps),
        "packagedSchemaIds": sorted(packaged_ids),
        "visibleSchemaIds": sorted(visible_ids),
        "schemaSetFingerprint": schema_set_fingerprint,
        "roleViewFingerprint": None,
    }
    view_material = {k: v for k, v in view.items() if k != "roleViewFingerprint"}
    view["roleViewFingerprint"] = committed(
        _bundle_domain(contract, "roleViewDomain"), view_material
    )
    record = {
        "role": role_name,
        "manifestPath": f"role-views/{role_name}/view-manifest.json",
        **view,
    }
    return files, view, record, dep_artifacts


def _file_record(path: str, kind: str, raw: bytes) -> dict[str, Any]:
    return {"relativePath": path, "kind": kind, "byteLength": len(raw), "rawFingerprint": raw_fingerprint(raw)}


def _target_slice_and_closure(authority: dict[str, Any], target_slice_id: str) -> tuple[dict[str, Any], dict[str, dict[str, Any]], list[str], list[str]]:
    if not isinstance(target_slice_id, str) or re.fullmatch(r"A1\.[1-7]", target_slice_id) is None:
        raise BundleError(f"BUNDLE_TARGET_SLICE_ID_INVALID:{target_slice_id}")
    matches = [item for item in authority.get("deliverySlices", []) if item.get("sliceId") == target_slice_id]
    if len(matches) != 1:
        raise BundleError(f"BUNDLE_TARGET_SLICE_NOT_AUTHORIZED:{target_slice_id}")
    target_slice = matches[0]
    roles = {role["role"]: role for role in authority.get("roleContracts", [])}
    by_artifact: dict[str, list[str]] = {}
    for role in authority.get("roleContracts", []):
        by_artifact.setdefault(role["artifactPath"], []).append(role["role"])
    duplicate_mappings = sorted(path for path, mapped_roles in by_artifact.items() if len(mapped_roles) != 1)
    if duplicate_mappings:
        raise BundleError(f"BUNDLE_ARTIFACT_PATH_MULTIPLE_ROLE:{duplicate_mappings}")
    required_paths = target_slice["acceptanceContract"]["requiredArtifactPaths"]
    if len(required_paths) != len(set(required_paths)):
        raise BundleError(f"BUNDLE_REQUIRED_ARTIFACT_PATH_DUPLICATE:{target_slice_id}")
    closure_roles: list[str] = []
    for path in required_paths:
        mapped = by_artifact.get(path, [])
        if not mapped:
            raise BundleError(f"BUNDLE_REQUIRED_ARTIFACT_PATH_UNMAPPED:{target_slice_id}:{path}")
        if len(mapped) != 1:
            raise BundleError(f"BUNDLE_REQUIRED_ARTIFACT_PATH_MULTIPLE_ROLE:{target_slice_id}:{path}")
        role_name = mapped[0]
        if role_name in closure_roles:
            raise BundleError(f"BUNDLE_ARTIFACT_CLOSURE_ROLE_DUPLICATE:{target_slice_id}:{role_name}")
        closure_roles.append(role_name)
    implementation_roles = target_slice.get("implementationRoles")
    if not isinstance(implementation_roles, list) or not implementation_roles:
        raise BundleError(f"BUNDLE_IMPLEMENTATION_ROLE_SET_INVALID:{target_slice_id}")
    if len(implementation_roles) != len(set(implementation_roles)):
        raise BundleError(f"BUNDLE_IMPLEMENTATION_ROLE_DUPLICATE:{target_slice_id}")
    if any(role_name not in roles for role_name in implementation_roles):
        raise BundleError(f"BUNDLE_IMPLEMENTATION_ROLE_UNKNOWN:{target_slice_id}")
    if not set(implementation_roles).issubset(set(closure_roles)):
        raise BundleError(f"BUNDLE_IMPLEMENTATION_ROLES_NOT_IN_ARTIFACT_CLOSURE:{target_slice_id}")
    return target_slice, roles, sorted(closure_roles), list(implementation_roles)


def _build_payloads(
    authority: dict[str, Any],
    authority_raw: bytes,
    role_jars: dict[str, pathlib.Path],
    target_slice_id: str,
) -> tuple[dict[str, bytes], dict[str, Any]]:
    limits = _limits(authority)
    contract = _bundle_contract(authority)
    policy = authority["schemaInventoryPolicy"]
    source_snapshots: dict[pathlib.Path, tuple[bytes, int, str]] = {
        AUTHORITY_DEFAULT: (authority_raw, MAX_JSON_BYTES, "BUNDLE_AUTHORITY")
    }

    def capture(path: pathlib.Path, limit: int, code: str) -> bytes:
        path = lexical_absolute(path, f"{code}_PATH_ALIAS")
        raw = read_stable(path, limit, code)
        previous = source_snapshots.get(path)
        if previous is not None and previous[0] != raw:
            raise BundleError(f"BUNDLE_SOURCE_CHANGED_DURING_CAPTURE:{path}")
        source_snapshots[path] = (raw, limit, code)
        return raw

    payloads: dict[str, bytes] = {"authority/protocol-authority.json": authority_raw}
    kinds: dict[str, str] = {"authority/protocol-authority.json": "AUTHORITY"}

    schema_names = [*policy["gateASchemas"], *policy["requiredReviewerSchemas"]]
    schema_cache: dict[str, bytes] = {}
    for name in schema_names:
        raw = capture(SCHEMA_ROOT / name, limits["maxJsonBytes"], "BUNDLE_SCHEMA")
        strict_json(raw, f"schema:{name}")
        schema_cache[name] = raw

    fresh_outputs = _fresh_protocol_outputs(authority, authority_raw)
    for name, raw in fresh_outputs:
        relative = f"projections/{name}"
        payloads[relative] = raw
        kinds[relative] = "COMPILATION_MANIFEST" if name == "protocol-compilation-manifest-v1.json" else "PROJECTION"

    schema_records: list[dict[str, Any]] = []
    for name in schema_names:
        raw = schema_cache[name]
        relative = f"schemas/{name}"
        payloads[relative] = raw
        kinds[relative] = "SCHEMA"
        schema_records.append(_file_record(relative, "SCHEMA", raw))
    schema_set_fingerprint = _tree_commitment(schema_records, _bundle_domain(contract, "schemaSetDomain"))

    profile_raw = capture(FINGERPRINT_PROFILE, limits["maxJsonBytes"], "BUNDLE_PROFILE")
    strict_json(profile_raw, "fingerprint-profile")
    payloads["profiles/fingerprint-profile-v1.json"] = profile_raw
    kinds["profiles/fingerprint-profile-v1.json"] = "CANONICAL_PROFILE"
    dependency_raw = _json_bytes(authority["dependencyAuthority"])
    payloads["dependency/dependency-authority.json"] = dependency_raw
    kinds["dependency/dependency-authority.json"] = "DEPENDENCY_AUTHORITY"

    target_slice, roles, closure_roles, implementation_roles = _target_slice_and_closure(authority, target_slice_id)
    expected_roles = set(closure_roles)
    if expected_roles != set(role_jars) or len(role_jars) != len(expected_roles):
        raise BundleError(f"BUNDLE_ROLE_SET_MISMATCH:expected={sorted(expected_roles)} observed={sorted(role_jars)}")
    bindings: dict[str, Any] = {}
    role_views: list[dict[str, Any]] = []
    for role_name in closure_roles:
        role = roles[role_name]
        supplied = lexical_absolute(role_jars[role_name], "BUNDLE_ROLE_JAR_PATH_ALIAS")
        expected = lexical_absolute(REPO / role["artifactPath"], "BUNDLE_ROLE_JAR_PATH_ALIAS")
        if supplied != expected:
            raise BundleError(f"BUNDLE_ROLE_JAR_PATH_NOT_AUTHORITY_BOUND:{role_name}")
        artifact_raw = capture(supplied, role["artifactLimits"]["maxRawBytes"], "BUNDLE_ROLE_JAR")
        profile_raw_role = _zip_profile(artifact_raw, role)
        files, view, view_record, dep_artifacts = _role_inputs(
            role,
            authority_raw,
            artifact_raw,
            profile_raw_role,
            role_name,
            contract,
            roles,
            authority,
            role_jars,
        )
        payloads.update(files)
        kinds.update({path: "ROLE_INPUT" if path not in (f"role-views/{role_name}/inputs/authority.json", f"role-views/{role_name}/inputs/artifacts/{role_name}.jar") else ("AUTHORITY" if path.endswith("authority.json") else "ROLE_ARTIFACT") for path in files})
        view_raw = _json_bytes(view)
        payloads[view_record["manifestPath"]] = view_raw
        kinds[view_record["manifestPath"]] = "ROLE_VIEW_MANIFEST"
        oracle = _derive_role_self_test_receipt(
            role,
            authority_raw,
            authority["revision"],
            artifact_raw,
            profile_raw_role,
            view["roleViewFingerprint"],
            view["inputTreeFingerprint"],
        )
        receipt_schema = strict_json(schema_cache[RECEIPT_SCHEMA.name], "receipt-schema")
        _validate(receipt_schema, oracle, f"BUNDLE_ORACLE_{role_name}")
        oracle_raw = _json_bytes(oracle)
        oracle_path = f"parent-private/oracles/{role_name}.json"
        payloads[oracle_path] = oracle_raw
        kinds[oracle_path] = "ORACLE"
        fixture_entries: dict[str, Any] = {
            "AUTHORITY_SNAPSHOT": {
                "relativePath": f"role-views/{role_name}/inputs/authority.json",
                "kind": "FILE",
                "fingerprint": typed(authority_raw),
            },
            "ROLE_ARTIFACT": {
                "relativePath": f"role-views/{role_name}/inputs/artifacts/{role_name}.jar",
                "kind": "FILE",
                "fingerprint": typed(artifact_raw),
            },
        }
        # Bind requiredRuntimeArtifactRoles JARs into fixture entries
        for dep in sorted(dep_artifacts):
            fixture_entries[f"RUNTIME_DEPENDENCY_{dep}"] = {
                "relativePath": f"role-views/{role_name}/inputs/artifacts/{dep}.jar",
                "kind": "FILE",
                "fingerprint": typed(dep_artifacts[dep]),
            }
        if profile_raw_role is not None:
            fixture_entries["PACKAGED_PROFILE"] = {
                "relativePath": f"role-views/{role_name}/inputs/profiles/{role_name}.json",
                "kind": "FILE",
                "fingerprint": typed(profile_raw_role),
            }
        bindings[role_name] = {
            "fixtures": fixture_entries,
            "oracle": {"relativePath": oracle_path, "kind": "FILE", "fingerprint": typed(oracle_raw)},
        }
        role_views.append(view_record)

    binding_manifest = {
        "messageVersion": "resource-gateway.capability-studio.gate-a.role-black-box-fixture-bindings.v1",
        "fixtureSetId": "GATE_A_ROLE_BLACK_BOX_V1",
        "bindingFingerprint": None,
        "bindings": bindings,
    }
    binding_manifest["bindingFingerprint"] = {
        "kind": "AGGREGATE_COMMITMENT",
        "algorithm": "SHA-256",
        "value": committed(_bundle_domain(contract, "fixtureBindingDomain"), binding_manifest),
    }
    binding_schema = strict_json(schema_cache[BINDING_SCHEMA.name], "binding-schema")
    _validate(binding_schema, binding_manifest, "BUNDLE_BINDING")
    payloads[BINDING_MANIFEST] = _json_bytes(binding_manifest)
    kinds[BINDING_MANIFEST] = "FIXTURE_BINDING"
    # Second read of fingerprint profile: catch toolchain-level replay or mutation
    # between first-read (storage at line 780) and second-read (here, post-oracle-derivation).
    profile_recheck = read_stable(FINGERPRINT_PROFILE, limits["maxJsonBytes"], "BUNDLE_PROFILE")
    if profile_recheck != profile_raw:
        raise BundleError("BUNDLE_SOURCE_SNAPSHOT_DRIFT")

    projection_records = [_file_record(path, kinds[path], payloads[path]) for path, raw in payloads.items() if kinds[path] in {"PROJECTION", "COMPILATION_MANIFEST"}]
    files = [_file_record(path, kinds[path], payloads[path]) for path, raw in sorted(payloads.items())]
    if len(files) > limits["maxFiles"] or sum(item["byteLength"] for item in files) > limits["maxTotalBytes"]:
        raise BundleError("BUNDLE_TOTAL_BUDGET_EXCEEDED")
    tree = _tree_commitment(files, _bundle_domain(contract, "closedTreeDomain"))
    toolchain_fingerprint = committed(
        _bundle_domain(contract, "toolchainPolicyDomain"),
        authority["hermeticExecutionContract"]["toolchainIdentity"],
    )
    bundle = {
        "messageVersion": "capability-studio.gate-a.release-authority-bundle.v1",
        "bundleId": "GATE_A_RELEASE_AUTHORITY_BUNDLE",
        "purpose": contract["purpose"],
        "releaseAdmissionPolicy": contract["releaseAdmissionPolicy"],
        "authorityRawFingerprint": raw_fingerprint(authority_raw),
        "authorityRevision": authority["revision"],
        "projectionSetFingerprint": _tree_commitment(projection_records, _bundle_domain(contract, "projectionSetDomain")),
        "schemaSetFingerprint": schema_set_fingerprint,
        "dependencyAuthorityRawFingerprint": raw_fingerprint(dependency_raw),
        "toolchainPolicyFingerprint": toolchain_fingerprint,
        "targetSliceId": target_slice["sliceId"],
        "artifactClosureRoles": closure_roles,
        "implementationRoles": implementation_roles,
        "files": files,
        "roleViews": role_views,
        "oracleCompartment": {
            "visibility": "PARENT_ONLY",
            "rootPath": "parent-private/oracles",
            "fileRefs": sorted(path for path, kind in kinds.items() if kind == "ORACLE"),
        },
        "closedTreeFingerprint": tree,
        "limits": limits,
        "seal": {"kind": "ATOMIC_FINAL_SEAL", "manifestPath": ROOT_MANIFEST, "selfNullField": "bundleRootFingerprint"},
        "bundleRootFingerprint": {"kind": "SELF_NULL_BUNDLE_ROOT", "algorithm": "SHA-256", "value": None, "selfNullField": "bundleRootFingerprint"},
    }
    bundle["bundleRootFingerprint"]["value"] = committed(ROOT_DOMAIN, bundle)
    return payloads, bundle


def compile_bundle(
    authority_path: pathlib.Path | str,
    output_root: pathlib.Path | str,
    role_jars: dict[str, pathlib.Path],
    target_slice_id: str,
) -> str:
    # Fail-closed: check raw string for path normalization aliases before
    # pathlib.Path() strips them (e.g. ./, ../, //, trailing /).
    authority_raw_str = os.path.expanduser(os.fspath(authority_path))
    if (
        "/./" in authority_raw_str
        or "/." == authority_raw_str[-2:]
        or "/../" in authority_raw_str
        or "/.." == authority_raw_str[-3:]
        or "//" in authority_raw_str
        or authority_raw_str.endswith("/")
    ):
        raise BundleError(f"BUNDLE_AUTHORITY_PATH_ALIAS:{authority_path}")
    # Fail-closed: check output_root for path normalization aliases before
    # pathlib.Path() strips them (e.g. ./, ../, //, trailing /).
    output_raw = os.path.expanduser(os.fspath(output_root))
    if (
        "/./" in output_raw
        or "/." == output_raw[-2:]
        or "/../" in output_raw
        or "/.." == output_raw[-3:]
        or "//" in output_raw
        or output_raw.endswith("/")
    ):
        raise BundleError(f"BUNDLE_OUTPUT_ROOT_PATH_ALIAS:{output_root}")
    output_root = pathlib.Path(output_root)
    if output_root.exists():
        raise BundleError("BUNDLE_OUTPUT_ROOT_EXISTS")
    authority, authority_raw = _authority_snapshot(pathlib.Path(authority_path))
    if not output_root.parent.exists():
        raise BundleError("BUNDLE_OUTPUT_PARENT_MISSING")
    payloads, bundle = _build_payloads(authority, authority_raw, role_jars, target_slice_id)
    output_root.mkdir(mode=0o700, exist_ok=False)
    try:
        for path, raw in payloads.items():
            write_new(output_root, path, raw)
        manifest_raw = _json_bytes(bundle)
        write_final_seal(output_root, manifest_raw)
        return bundle["bundleRootFingerprint"]["value"]
    except BundleError:
        import shutil
        shutil.rmtree(output_root, ignore_errors=True)
        raise


def _inventory(root_fd: int) -> tuple[set[str], set[str]]:
    files: set[str] = set()
    directories: set[str] = {""}

    def walk(fd: int, prefix: str) -> None:
        try:
            names = sorted(os.listdir(fd))
        except OSError as error:
            raise BundleError(f"BUNDLE_INVENTORY_FAILED:{prefix}:{error}") from error
        for name in names:
            if name in (".", "..") or "/" in name or "\0" in name:
                continue
            child_st = os.lstat(name, dir_fd=fd)
            if stat.S_ISLNK(child_st.st_mode):
                raise BundleError(f"BUNDLE_SYMLINK_PRESENT:{prefix}{name}")
            try:
                child_fd = os.open(name, _flags("O_RDONLY", "O_DIRECTORY", "O_CLOEXEC", "O_NOFOLLOW"), dir_fd=fd)
            except NotADirectoryError:
                if not stat.S_ISREG(child_st.st_mode) or child_st.st_nlink != 1:
                    raise BundleError(f"BUNDLE_HARDLINK_PRESENT:{prefix}{name}")
                files.add(prefix + name)
                continue
            try:
                st = os.fstat(child_fd)
                if stat.S_ISDIR(st.st_mode):
                    directories.add(prefix + name)
                    walk(child_fd, prefix + name + "/")
                else:
                    if child_st.st_nlink != 1:
                        raise BundleError(f"BUNDLE_HARDLINK_PRESENT:{prefix}{name}")
                    files.add(prefix + name)
            finally:
                os.close(child_fd)

    walk(root_fd, "")
    return files, directories


class BundleSnapshot:
    __slots__ = ("root_fingerprint", "manifest", "files")

    def __init__(self, root_fingerprint: str, manifest: dict[str, Any], files: dict[str, bytes]) -> None:
        self.root_fingerprint = root_fingerprint
        self.manifest = manifest
        self.files = files

    def bytes_for(self, path: str) -> bytes:
        return self.files[path]


    @classmethod
    def create(cls, expected: str, manifest: dict[str, Any], files: dict[str, bytes]) -> BundleSnapshot:
        observed = manifest["bundleRootFingerprint"]["value"]
        if observed != expected:
            raise BundleError("BUNDLE_INTERNAL_ROOT_MISMATCH")
        return cls(observed, manifest, files)


def _freeze(value: Any) -> Any:
    if isinstance(value, dict):
        return MappingProxyType({k: _freeze(v) for k, v in value.items()})
    if isinstance(value, list):
        return tuple(_freeze(v) for v in value)
    return value


def _verify_receipt_fingerprint(receipt: dict[str, Any], domain: str, role: str) -> None:
    material = dict(receipt)
    material["receiptFingerprint"] = None  # match compilation canonical form (None not absent)
    expected = committed(domain.encode("ascii"), _freeze(material))
    if receipt["receiptFingerprint"] != {"kind": "SELF_NULL_RECEIPT", "algorithm": "SHA-256", "value": expected, "selfNullField": "receiptFingerprint"}:
        raise BundleError(f"BUNDLE_ORACLE_RECEIPT_SELF_NULL_DRIFT:{role}")


def _verify_bundle_commitments(manifest: dict[str, Any], loaded: dict[str, bytes]) -> None:
    bundle_contract = _bundle_contract(strict_json(loaded["authority/protocol-authority.json"], "authority"))

    projection_records = [
        _file_record(entry["relativePath"], entry["kind"], loaded[entry["relativePath"]])
        for entry in manifest["files"]
        if entry["kind"] in {"PROJECTION", "COMPILATION_MANIFEST"}
    ]
    if _tree_commitment(projection_records, _bundle_domain(bundle_contract, "projectionSetDomain")) != manifest["projectionSetFingerprint"]:
        raise BundleError("BUNDLE_PROJECTION_SET_COMMITMENT_DRIFT")
    schema_records = [
        _file_record(path, "SCHEMA", raw)
        for path, raw in loaded.items()
        if path.startswith("schemas/")
    ]
    if _tree_commitment(schema_records, _bundle_domain(bundle_contract, "schemaSetDomain")) != manifest["schemaSetFingerprint"]:
        raise BundleError("BUNDLE_SCHEMA_SET_COMMITMENT_DRIFT")

    dependency_raw = loaded.get("dependency/dependency-authority.json")
    expected_dependency_raw = _json_bytes(strict_json(loaded["authority/protocol-authority.json"], "authority")["dependencyAuthority"])
    if (
        dependency_raw != expected_dependency_raw
        or raw_fingerprint(expected_dependency_raw) != manifest["dependencyAuthorityRawFingerprint"]
    ):
        raise BundleError("BUNDLE_DEPENDENCY_AUTHORITY_COMMITMENT_DRIFT")
    expected_toolchain = committed(
        _bundle_domain(bundle_contract, "toolchainPolicyDomain"),
        strict_json(loaded["authority/protocol-authority.json"], "authority")["hermeticExecutionContract"]["toolchainIdentity"],
    )
    if expected_toolchain != manifest["toolchainPolicyFingerprint"]:
        raise BundleError("BUNDLE_TOOLCHAIN_POLICY_COMMITMENT_DRIFT")

    authority = strict_json(loaded["authority/protocol-authority.json"], "bundle-authority")
    target_slice, roles, closure_roles, implementation_roles = _target_slice_and_closure(authority, manifest.get("targetSliceId"))
    if manifest.get("targetSliceId") != target_slice["sliceId"]:
        raise BundleError("BUNDLE_TARGET_SLICE_RELATION_DRIFT")
    if manifest.get("artifactClosureRoles") != closure_roles:
        raise BundleError("BUNDLE_ARTIFACT_CLOSURE_ROLE_SET_DRIFT")
    if manifest.get("implementationRoles") != implementation_roles:
        raise BundleError("BUNDLE_IMPLEMENTATION_ROLE_SET_DRIFT")
    binding_raw = loaded.get(BINDING_MANIFEST)
    if binding_raw is None:
        raise BundleError("BUNDLE_FIXTURE_BINDING_MISSING")
    binding = strict_json(binding_raw, BINDING_MANIFEST)
    binding_material = copy.deepcopy(binding)
    binding_fingerprint = binding_material.get("bindingFingerprint")
    binding_material["bindingFingerprint"] = None
    expected_binding_value = committed(_bundle_domain(bundle_contract, "fixtureBindingDomain"), binding_material)
    if binding_fingerprint != {
        "kind": "AGGREGATE_COMMITMENT",
        "algorithm": "SHA-256",
        "value": expected_binding_value,
    }:
        raise BundleError("BUNDLE_FIXTURE_BINDING_FINGERPRINT_DRIFT")

    expected_oracles: set[str] = set()
    expected_bindings: dict[str, Any] = {}
    if {view["role"] for view in manifest["roleViews"]} != set(closure_roles):
        raise BundleError("BUNDLE_ROLE_VIEW_CLOSURE_SET_DRIFT")
    for view in sorted(manifest["roleViews"], key=lambda item: item["role"]):
        role_name = view["role"]
        role = roles.get(role_name)
        if role is None:
            raise BundleError(f"BUNDLE_ROLE_NOT_AUTHORIZED:{role_name}")

        # ── requiredRuntimeArtifactRoles closure ─────────────────────
        required_deps = list(role.get("requiredRuntimeArtifactRoles", []))
        if sorted(view.get("requiredRuntimeArtifactRoles", [])) != sorted(required_deps):
            raise BundleError(f"BUNDLE_ROLE_RUNTIME_CLOSURE_DRIFT:{role_name}")

        base = f"role-views/{role_name}/inputs"
        authority_ref = f"{base}/authority.json"
        artifact_ref = f"{base}/artifacts/{role_name}.jar"
        profile_ref = f"{base}/profiles/{role_name}.json"

        # Build expected visible refs: authority + own JAR + dep JARs + profile
        expected_visible = [authority_ref, artifact_ref]
        for dep in sorted(required_deps):
            expected_visible.append(f"{base}/artifacts/{dep}.jar")
        if loaded.get(profile_ref) is not None:
            expected_visible.append(profile_ref)
        if sorted(view["visibleFileRefs"]) != sorted(expected_visible):
            raise BundleError(f"BUNDLE_ROLE_VIEW_VISIBLE_SET_DRIFT:{role_name}")

        # ── Per-role schema exact set ───────────────────────────────
        visible_ids = list(role.get("visibleSchemaIds", []))
        packaged_ids = list(visible_ids)  # Authority-approved exact set
        schema_policy = authority["schemaInventoryPolicy"]
        all_gate_ids = list(schema_policy["gateASchemas"])
        all_reviewer_ids = list(schema_policy.get("requiredReviewerSchemas", []))
        if not set(packaged_ids).issubset(set(all_gate_ids + all_reviewer_ids)):
            raise BundleError(f"BUNDLE_ROLE_SCHEMA_NOT_AUTHORITY_ALLOWED:{role_name}")
        if sorted(view.get("packagedSchemaIds", [])) != sorted(packaged_ids):
            raise BundleError(f"BUNDLE_ROLE_PACKAGED_SCHEMA_SET_DRIFT:{role_name}")
        if sorted(view.get("visibleSchemaIds", [])) != sorted(visible_ids):
            raise BundleError(f"BUNDLE_ROLE_VISIBLE_SCHEMA_SET_DRIFT:{role_name}")

        # ── Schema-set fingerprint ─────────────────────────────────
        schema_entries: list[dict[str, Any]] = []
        for schema_name in sorted(packaged_ids):
            schema_path_in_bundle = f"schemas/{schema_name}"
            if schema_path_in_bundle not in loaded:
                raise BundleError(f"BUNDLE_ROLE_SCHEMA_NOT_IN_BUNDLE:{role_name}:{schema_name}")
            schema_entries.append(
                _file_record(schema_path_in_bundle, "SCHEMA", loaded[schema_path_in_bundle])
            )
        expected_schema_fp = _tree_commitment(
            schema_entries, _bundle_domain(bundle_contract, "schemaSetDomain")
        )
        if view.get("schemaSetFingerprint") != expected_schema_fp:
            raise BundleError(f"BUNDLE_ROLE_SCHEMA_SET_FINGERPRINT_DRIFT:{role_name}")

        # ── Authority snapshot and own JAR ───────────────────────────
        if loaded.get(authority_ref) != loaded.get("authority/protocol-authority.json"):
            raise BundleError(f"BUNDLE_ROLE_AUTHORITY_SNAPSHOT_DRIFT:{role_name}")
        artifact_raw = loaded.get(artifact_ref)
        if artifact_raw is None:
            raise BundleError(f"BUNDLE_ROLE_ARTIFACT_MISSING:{role_name}")

        # ── Required dependency JARs ─────────────────────────────────
        dep_artifacts: dict[str, bytes] = {}
        for dep in sorted(required_deps):
            dep_ref = f"{base}/artifacts/{dep}.jar"
            dep_raw = loaded.get(dep_ref)
            if dep_raw is None:
                raise BundleError(f"BUNDLE_ROLE_RUNTIME_DEPENDENCY_MISSING:{role_name}:{dep}")
            dep_artifacts[dep] = dep_raw

        profile_raw = loaded.get(profile_ref) if role.get("profilePath") is not None else None
        oracle_path = f"parent-private/oracles/{role_name}.json"
        expected_oracles.add(oracle_path)
        oracle_raw = loaded.get(oracle_path)
        if oracle_raw is None:
            raise BundleError(f"BUNDLE_ROLE_ORACLE_MISSING:{role_name}")
        receipt = strict_json(oracle_raw, oracle_path)
        expected_receipt_keys = {
            "messageVersion",
            "role",
            "authority",
            "artifactRawFingerprint",
            "profileRawFingerprint",
            "fixtureSetId",
            "capabilities",
            "status",
            "roleViewFingerprint",
            "inputTreeFingerprint",
            "receiptFingerprint",
        }
        if set(receipt) != expected_receipt_keys:
            raise BundleError(f"BUNDLE_ORACLE_RECEIPT_FIELD_SET_DRIFT:{role_name}")
        role_contract = role["blackBoxContract"]
        expected_values = {
            "messageVersion": role_contract["stdoutMessageVersion"],
            "role": role_name,
            "authority": {"rawFingerprint": typed(loaded["authority/protocol-authority.json"]), "revision": authority["revision"]},
            "artifactRawFingerprint": typed(artifact_raw),
            "profileRawFingerprint": typed(profile_raw) if profile_raw is not None else None,
            "fixtureSetId": role_contract["fixtureSetId"],
            "capabilities": role_contract["capabilities"],
            "status": "READY",
            "roleViewFingerprint": {"kind": "TREE_COMMITMENT", "algorithm": "SHA-256", "value": view["roleViewFingerprint"]},
            "inputTreeFingerprint": {"kind": "TREE_COMMITMENT", "algorithm": "SHA-256", "value": view["inputTreeFingerprint"]},
        }
        if any(receipt[key] != value for key, value in expected_values.items()):
            raise BundleError(f"BUNDLE_ORACLE_RECEIPT_BINDING_DRIFT:{role_name}")
        _verify_receipt_fingerprint(receipt, role_contract["receiptFingerprintDomain"], role_name)

        fixture_entries: dict[str, Any] = {
            "AUTHORITY_SNAPSHOT": {"relativePath": authority_ref, "kind": "FILE", "fingerprint": typed(loaded["authority/protocol-authority.json"])},
            "ROLE_ARTIFACT": {"relativePath": artifact_ref, "kind": "FILE", "fingerprint": typed(artifact_raw)},
        }
        for dep in sorted(dep_artifacts):
            fixture_entries[f"RUNTIME_DEPENDENCY_{dep}"] = {
                "relativePath": f"{base}/artifacts/{dep}.jar",
                "kind": "FILE",
                "fingerprint": typed(dep_artifacts[dep]),
            }
        if profile_raw is not None:
            fixture_entries["PACKAGED_PROFILE"] = {"relativePath": profile_ref, "kind": "FILE", "fingerprint": typed(profile_raw)}
        expected_bindings[role_name] = {
            "fixtures": fixture_entries,
            "oracle": {"relativePath": oracle_path, "kind": "FILE", "fingerprint": typed(oracle_raw)},
        }

    if set(manifest["oracleCompartment"]["fileRefs"]) != expected_oracles:
        raise BundleError("BUNDLE_ORACLE_COMPARTMENT_SET_DRIFT")
    if binding.get("bindings") != expected_bindings:
        raise BundleError("BUNDLE_FIXTURE_BINDING_CONTENT_DRIFT")


def verify_bundle(bundle_root: pathlib.Path | str, expected_root_fingerprint: str) -> BundleSnapshot:
    bundle_root = lexical_absolute(bundle_root, "BUNDLE_VERIFY_ROOT_PATH_ALIAS")
    if not isinstance(expected_root_fingerprint, str) or not expected_root_fingerprint.startswith("sha256:"):
        raise BundleError("BUNDLE_EXPECTED_ROOT_FINGERPRINT_INVALID")
    root_fd = open_dir(bundle_root, "BUNDLE_VERIFY_ROOT")
    try:
        before_files, before_dirs = _inventory(root_fd)
        manifest_raw = read_relative(root_fd, ROOT_MANIFEST, MAX_JSON_BYTES, "BUNDLE_MANIFEST")
        manifest = strict_json(manifest_raw, "bundle-manifest")
        schema_raw = read_relative(root_fd, f"schemas/{BUNDLE_SCHEMA_NAME}", MAX_JSON_BYTES, "BUNDLE_SCHEMA")
        if raw_fingerprint(schema_raw) != SUPPORTED_BUNDLE_SCHEMA_RAW_FINGERPRINT:
            raise BundleError("BUNDLE_SCHEMA_NOT_SUPPORTED_BY_VERIFIER")
        schema = strict_json(schema_raw, "bundle-schema")
        _validate(schema, manifest, "BUNDLE")
        if manifest["bundleRootFingerprint"]["value"] != expected_root_fingerprint:
            raise BundleError("BUNDLE_EXTERNAL_ROOT_PIN_MISMATCH")
        root_material = dict(manifest)
        root_material["bundleRootFingerprint"] = {**root_material["bundleRootFingerprint"], "value": None}
        if committed(ROOT_DOMAIN, root_material) != expected_root_fingerprint:
            raise BundleError("BUNDLE_EXTERNAL_ROOT_PIN_MISMATCH")
        limits = manifest["limits"]
        if limits["maxFiles"] > MAX_BUNDLE_FILES or limits["maxTotalBytes"] > MAX_BUNDLE_BYTES:
            raise BundleError("BUNDLE_LIMITS_EXCEED_VERIFIER_CAPACITY")
        entries = manifest["files"]
        if len(entries) > limits["maxFiles"]:
            raise BundleError("BUNDLE_FILE_COUNT_LIMIT")
        paths = [entry["relativePath"] for entry in entries]
        if len(paths) != len(set(paths)) or ROOT_MANIFEST in paths:
            raise BundleError("BUNDLE_FILE_ENTRY_SET_INVALID")
        expected_files = set(paths) | {ROOT_MANIFEST}
        if before_files != expected_files:
            missing = sorted(expected_files - before_files)
            unknown = sorted(before_files - expected_files)
            raise BundleError(f"BUNDLE_PHYSICAL_CLOSED_SET_DRIFT:missing={missing}:unknown={unknown}")
        expected_dirs = {""}
        for path in expected_files:
            parts = path.split("/")
            expected_dirs.update("/".join(parts[:i]) for i in range(1, len(parts)))
        if before_dirs != expected_dirs:
            raise BundleError("BUNDLE_DIRECTORY_CLOSED_SET_DRIFT")
        authority_raw = read_relative(root_fd, "authority/protocol-authority.json", MAX_JSON_BYTES, "BUNDLE_AUTHORITY")
        authority = strict_json(authority_raw, "bundle-authority")
        bundle_contract = _bundle_contract(authority)
        if manifest.get("authorityRawFingerprint") != raw_fingerprint(authority_raw):
            raise BundleError("BUNDLE_AUTHORITY_RAW_FINGERPRINT_DRIFT")
        if manifest.get("authorityRevision") != authority["revision"]:
            raise BundleError("BUNDLE_AUTHORITY_REVISION_DRIFT")

        loaded: dict[str, bytes] = {}
        total = 0
        for entry in entries:
            path = entry["relativePath"]
            effective_limit = min(limits["maxFileBytes"], limits["maxJsonBytes"]) if path.endswith(".json") else limits["maxFileBytes"]
            if entry["byteLength"] > effective_limit:
                raise BundleError(f"BUNDLE_FILE_BYTES_LIMIT:{path}")
            raw = read_relative(root_fd, path, effective_limit, "BUNDLE_FILE")
            if len(raw) != entry["byteLength"] or raw_fingerprint(raw) != entry["rawFingerprint"]:
                raise BundleError(f"BUNDLE_FILE_FINGERPRINT_DRIFT:{path}")
            if path.endswith(".json"):
                strict_json(raw, path)
            loaded[path] = raw
            total += len(raw)
            if total > limits["maxTotalBytes"]:
                raise BundleError("BUNDLE_TOTAL_BYTES_LIMIT")
        after_files, after_dirs = _inventory(root_fd)
        if before_files != after_files or before_dirs != after_dirs:
            raise BundleError("BUNDLE_PHYSICAL_INVENTORY_DRIFT")
        records = [{"relativePath": path, "byteLength": len(raw), "rawFingerprint": raw_fingerprint(raw), "kind": next(item["kind"] for item in entries if item["relativePath"] == path)} for path, raw in loaded.items()]
        if _tree_commitment(records, _bundle_domain(bundle_contract, "closedTreeDomain")) != manifest["closedTreeFingerprint"]:
            raise BundleError("BUNDLE_CLOSED_TREE_FINGERPRINT_DRIFT")
        oracle_refs = set(manifest["oracleCompartment"]["fileRefs"])
        _, _, closure_roles, _ = _target_slice_and_closure(authority, manifest["targetSliceId"])
        expected_roles = set(closure_roles)
        if {view["role"] for view in manifest["roleViews"]} != expected_roles:
            raise BundleError("BUNDLE_ROLE_VIEW_SET_DRIFT")
        for view in manifest["roleViews"]:
            if any(ref in oracle_refs or ref.startswith("parent-private/") for ref in view["visibleFileRefs"]):
                raise BundleError(f"BUNDLE_ROLE_VIEW_ORACLE_REFERENCE:{view['role']}")
            expected_prefix = f"role-views/{view['role']}/inputs/"
            if any(not ref.startswith(expected_prefix) for ref in view["visibleFileRefs"]):
                raise BundleError(f"BUNDLE_ROLE_VIEW_INPUT_REFERENCE_INVALID:{view['role']}")
            expected_manifest_path = f"role-views/{view['role']}/view-manifest.json"
            if view["manifestPath"] != expected_manifest_path:
                raise BundleError(f"BUNDLE_ROLE_VIEW_MANIFEST_PATH_DRIFT:{view['role']}")
            view_raw = loaded.get(view["manifestPath"])
            expected_view = dict(view)
            expected_view.pop("manifestPath")
            if view_raw is None:
                raise BundleError(f"BUNDLE_ROLE_VIEW_MANIFEST_MISSING:{view['role']}")
            # Build input_records from visibleFileRefs to verify input tree binding
            input_records = [
                {
                    "relativePath": ref,
                    "byteLength": len(loaded[ref]),
                    "rawFingerprint": raw_fingerprint(loaded[ref]),
                }
                for ref in view["visibleFileRefs"]
                if ref in loaded
            ]
            if len(input_records) != len(view["visibleFileRefs"]):
                raise BundleError(f"BUNDLE_ROLE_VIEW_INPUT_MISSING:{view['role']}")
            # STEP 1: Verify input tree binding FIRST.
            # The inputTreeFingerprint is the PRIMARY binding anchor for visible inputs.
            # Checking this first ensures that input tree tampering is classified as
            # BUNDLE_ROLE_INPUT_TREE_DRIFT, regardless of whether roleViewFingerprint
            # was also recomputed. This preserves attack semantics: tampering with
            # visible inputs is an INPUT TREE violation.
            if _tree_commitment(input_records, _bundle_domain(bundle_contract, "roleInputTreeDomain")) != view["inputTreeFingerprint"]:
                raise BundleError(f"BUNDLE_ROLE_INPUT_TREE_DRIFT:{view['role']}")
            # STEP 2: Verify manifest vs view document consistency
            observed_view = strict_json(view_raw, view["manifestPath"])
            if observed_view != expected_view:
                raise BundleError(f"BUNDLE_ROLE_VIEW_MANIFEST_DRIFT:{view['role']}")
            # STEP 3: Verify roleView self fingerprint
            # roleViewFingerprint is computed over view material (all fields except roleViewFingerprint).
            # By checking input tree binding first, we ensure that input tree drift is reported
            # before roleViewFingerprint drift, preserving the semantic priority.
            view_material = {k: v for k, v in observed_view.items() if k != "roleViewFingerprint"}
            if committed(_bundle_domain(bundle_contract, "roleViewDomain"), view_material) != view["roleViewFingerprint"]:
                raise BundleError(f"BUNDLE_ROLE_VIEW_FINGERPRINT_DRIFT:{view['role']}")
        _verify_bundle_commitments(manifest, loaded)
        return BundleSnapshot.create(expected_root_fingerprint, manifest, loaded)
    finally:
        os.close(root_fd)


def parse_role_jars(values: list[str]) -> dict[str, pathlib.Path]:
    result: dict[str, pathlib.Path] = {}
    for value in values:
        role, separator, path = value.partition("=")
        if not separator or not role or not path or role in result:
            raise BundleError(f"BUNDLE_ROLE_JAR_ARGUMENT_INVALID:{value}")
        result[role] = lexical_absolute(path, "BUNDLE_ROLE_JAR_PATH_ALIAS")
    return result


def compile_cli(arguments: argparse.Namespace) -> None:
    root = compile_bundle(arguments.authority, arguments.output_root, parse_role_jars(arguments.role_jar), arguments.target_slice_id)
    print(f"BUNDLE_COMPILED: root={root}")


def verify_cli(arguments: argparse.Namespace) -> None:
    snapshot = verify_bundle(arguments.bundle_root, arguments.expected_root_fingerprint)
    print(f"BUNDLE_VERIFIED: root={snapshot.root_fingerprint} files={len(snapshot.files)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Gate A Release Authority Bundle compiler")
    sub = parser.add_subparsers(dest="command")
    compile_p = sub.add_parser("compile")
    compile_p.add_argument("--authority", default=str(AUTHORITY_DEFAULT))
    compile_p.add_argument("--output-root", required=True)
    compile_p.add_argument("--target-slice-id", required=True)
    compile_p.add_argument("--role-jar", action="append", required=True)
    compile_p.set_defaults(func=compile_cli)
    verify_p = sub.add_parser("verify")
    verify_p.add_argument("--bundle-root", required=True)
    verify_p.add_argument("--expected-root-fingerprint", required=True)
    verify_p.set_defaults(func=verify_cli)
    args = parser.parse_args()
    args.func(args)
