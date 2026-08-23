#!/usr/bin/env python3
"""Run Gate A attacks against materialized bytes, paths, archives and signatures.

This runner intentionally accepts only the manifest as case metadata. It never
loads semantic-guard vectors or accepts normalized present/available/matches
observations. Every result below is derived after re-reading a temporary
material root.
"""

from __future__ import annotations

import argparse
import base64
import copy
import datetime as dt
import hashlib
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Any

import jsonschema
from referencing import Registry, Resource


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[4]
MANIFEST = HERE / "manifest.json"
MANIFEST_SCHEMA = REPO / "docs/schemas/resource-gateway-capability-studio/capability-studio-gate-a-material-attack-manifest-v1.schema.json"
ADMISSION_TRUST_PIN = REPO / "docs/acceptance/capability-studio/gate-a-wire-v1/trust-build/valid-admission-trust-pin.json"
SCHEMA_DIR = REPO / "docs/schemas/resource-gateway-capability-studio"
GUARD_CATALOG = HERE.parent / "semantic-guards" / "guard-catalog-v1.json"
GUARDS: list[str] = []
TARGETS: dict[str, str] = {}

DOMAINS = {
    "a0": "RG-CS-GATE-A0-RESULT-v1",
    "a1": "RG-CS-GATE-A1-REPLAY-RESULT-v1",
    "a2": "RG-CS-GATE-A2-RESULT-v1",
    "challenge": "RG-CS-GATE-A-CHALLENGE-TRUST-PIN-v1",
    "process": "RG-CS-GATE-A-PROCESS-TRANSCRIPT-v1",
    "a1Envelope": "RG-CS-GATE-A1-PROOF-ENVELOPE-v1",
    "reviewBody": "RG-CS-REVIEW-BODY-v1",
    "reviewEnvelope": "RG-CS-REVIEW-ENVELOPE-v1",
    "reviewPolicy": "RG-CS-REVIEWER-TRUST-POLICY-v1",
    "reviewRevocation": "RG-CS-REVIEWER-REVOCATION-SNAPSHOT-v1",
}
TREE_DOMAINS = {
    "challenge": "RG-CS-GATE-A-CHALLENGE-INPUT-ROOT-v1",
    "admission": "RG-CS-GATE-A-ADMISSION-EVIDENCE-ROOT-v1",
    "runMaterial": "RG-CS-GATE-A-RUN-MATERIAL-ROOT-v1",
}
MAX_TREE_FILE_BYTES = 16 * 1024 * 1024
MAX_ADMISSION_TRUST_PIN_BYTES = 64 * 1024
RESIGNED_REVIEW_SEMANTIC_ATTACKS = {
    "REAL-REVIEW-COUNT-UNDERREPORT",
    "REAL-REVIEW-CHECK-FINDING-MISMATCH",
    "REAL-REVIEW-DUPLICATE-FINDING-ID",
    "REAL-REVIEW-FINDING-ORDER-DRIFT",
    "REAL-REVIEW-COUNT-OPEN-P1-UNDERREPORT",
    "REAL-REVIEW-COUNT-SKIPPED-UNDERREPORT",
    "REAL-REVIEW-CANDIDATE-BINDING-DRIFT",
    "REAL-REVIEW-BODY-ENVELOPE-REVIEWED-AT-DRIFT",
    "REAL-REVIEW-REVOCATION-ISSUED-AFTER-REVIEW",
    "REAL-REVIEW-EXPIRED-REVIEW",
    "REAL-REVIEW-EXPIRED-POLICY",
    "REAL-REVIEW-EXPIRED-REVOCATION",
}


def fail(message: str) -> None:
    raise AssertionError(message)


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _strict_json_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    document: dict[str, Any] = {}
    for key, value in pairs:
        if key in document:
            raise json.JSONDecodeError(f"duplicate JSON member: {key}", "", 0)
        document[key] = value
    return document


def _parse_strict_json_snapshot(content: bytes) -> Any:
    text = content.decode("utf-8")
    decoder = json.JSONDecoder(object_pairs_hook=_strict_json_pairs)
    start = len(text) - len(text.lstrip())
    value, end = decoder.raw_decode(text, start)
    if text[end:].strip():
        raise json.JSONDecodeError("trailing JSON content", text, end)
    return value


def _stable_file_identity(before: os.stat_result, after: os.stat_result) -> bool:
    return (
        (before.st_dev, before.st_ino, before.st_mode, before.st_nlink,
         before.st_size, before.st_mtime_ns, before.st_ctime_ns)
        == (after.st_dev, after.st_ino, after.st_mode, after.st_nlink,
            after.st_size, after.st_mtime_ns, after.st_ctime_ns)
    )


def read_stable_admission_trust_pin(path: Path) -> dict[str, Any]:
    before = os.lstat(path)
    if not stat.S_ISREG(before.st_mode) or before.st_size > MAX_ADMISSION_TRUST_PIN_BYTES:
        fail(f"trusted admission pin is not a bounded regular file: {path}")
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        opened = os.fstat(fd)
        if not stat.S_ISREG(opened.st_mode) or not _stable_file_identity(before, opened):
            fail(f"trusted admission pin changed while opening: {path}")
        chunks: list[bytes] = []
        total = 0
        while True:
            chunk = os.read(fd, min(8192, MAX_ADMISSION_TRUST_PIN_BYTES - total + 1))
            if not chunk:
                break
            total += len(chunk)
            if total > MAX_ADMISSION_TRUST_PIN_BYTES:
                fail(f"trusted admission pin exceeds bounded read limit: {path}")
            chunks.append(chunk)
        after = os.fstat(fd)
        if not _stable_file_identity(opened, after) or total != after.st_size:
            fail(f"trusted admission pin changed during read: {path}")
        document = _parse_strict_json_snapshot(b"".join(chunks))
        if not isinstance(document, dict):
            fail("trusted admission pin must be a JSON object")
        return document
    finally:
        os.close(fd)


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")


def _utf16_sort_key(value: str) -> bytes:
    return value.encode("utf-16-be", "surrogatepass")


def jcs_canonicalize(value: Any) -> str:
    """Port the reference-fingerprint.mjs canonicalize() byte-for-byte."""
    if value is None or isinstance(value, bool):
        return json.dumps(value, separators=(",", ":"))
    if isinstance(value, int) and not isinstance(value, bool):
        return str(value)
    if isinstance(value, float):
        if not (value == value and abs(value) != float("inf")):
            fail("non-finite number in canonical material")
        if value == 0:
            return "0"
        if value.is_integer() and abs(value) < 1e21:
            return str(int(value))
        rendered = json.dumps(value, allow_nan=False, separators=(",", ":"))
        if "e" in rendered or "E" in rendered:
            mantissa, exponent = rendered.lower().split("e")
            exponent_value = int(exponent)
            rendered = mantissa + "e" + ("+" if exponent_value >= 0 else "") + str(exponent_value)
        return rendered
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    if isinstance(value, list):
        return "[" + ",".join(jcs_canonicalize(item) for item in value) + "]"
    if isinstance(value, dict):
        fields = []
        for key in sorted(value.keys(), key=_utf16_sort_key):
            fields.append(jcs_canonicalize(str(key)) + ":" + jcs_canonicalize(value[key]))
        return "{" + ",".join(fields) + "}"
    fail(f"unsupported canonical value: {type(value)!r}")


def canonical_bytes(value: Any) -> bytes:
    return jcs_canonicalize(value).encode("utf-8")


def digest_bytes(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def raw_ref(value: bytes) -> dict[str, str]:
    return {"kind": "RAW_BYTES", "algorithm": "SHA-256", "value": digest_bytes(value)}


def document_fingerprint(document: dict[str, Any], field: str | None, domain: str) -> dict[str, str]:
    candidate = copy.deepcopy(document)
    if field is not None:
        if field not in candidate:
            fail(f"self field missing: {field}")
        candidate[field] = None
    payload = domain.encode("ascii") + b"\x00" + canonical_bytes(candidate)
    return {"kind": "CANONICAL_DOCUMENT", "algorithm": "SHA-256", "value": digest_bytes(payload)}


def tree_fingerprint(root: Path, domain: str = TREE_DOMAINS["admission"]) -> dict[str, str]:
    root_stat = os.lstat(root)
    if not stat.S_ISDIR(root_stat.st_mode) or stat.S_ISLNK(root_stat.st_mode):
        fail(f"tree root is not a real directory: {root}")
    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | os.O_NOFOLLOW
    root_fd = os.open(root, flags)
    entries: list[dict[str, str]] = []
    canonical_root = os.path.realpath(root)
    canonical_paths = {canonical_root}
    relative_paths: set[str] = set()
    file_keys: set[tuple[int, int]] = set()
    try:
        stack: list[tuple[str, int, str, Any]] = [("enter", root_fd, "", None)]
        while stack:
            operation, directory_fd, relative_directory, state = stack.pop()
            if operation == "enter":
                before = os.fstat(directory_fd)
                if not stat.S_ISDIR(before.st_mode):
                    fail(f"tree contains a non-directory: {root / relative_directory}")
                with os.scandir(directory_fd) as scan:
                    names = [entry.name for entry in scan]
                stack.append(("exit", directory_fd, relative_directory, before))
                for name in reversed(names):
                    stack.append(("child", directory_fd, relative_directory, name))
                continue
            if operation == "exit":
                after = os.fstat(directory_fd)
                stable = _tree_identity(before=state, after=after)
                if directory_fd != root_fd:
                    os.close(directory_fd)
                if stable:
                    continue
                fail(f"unstable tree directory identity: {root / relative_directory}")

            name = state
            child_relative = f"{relative_directory}/{name}" if relative_directory else name
            child_path = root / child_relative
            child_stat = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
            if stat.S_ISLNK(child_stat.st_mode):
                fail(f"tree contains a symbolic link: {child_path}")
            canonical = os.path.realpath(child_path)
            if os.path.commonpath((canonical_root, canonical)) != canonical_root:
                fail(f"tree canonical path escapes root: {child_path}")
            if canonical in canonical_paths or child_relative in relative_paths:
                fail(f"tree contains a duplicate canonical path: {child_path}")
            canonical_paths.add(canonical)
            relative_paths.add(child_relative)

            if stat.S_ISDIR(child_stat.st_mode):
                child_fd = os.open(name, flags, dir_fd=directory_fd)
                opened = os.fstat(child_fd)
                if not _tree_identity(child_stat, opened):
                    os.close(child_fd)
                    fail(f"unstable tree directory identity: {child_path}")
                stack.append(("enter", child_fd, child_relative, None))
                continue
            if not stat.S_ISREG(child_stat.st_mode):
                fail(f"tree contains a non-regular file: {child_path}")
            if child_stat.st_nlink != 1 or (child_stat.st_dev, child_stat.st_ino) in file_keys:
                fail(f"tree contains a hard-linked or duplicate file: {child_path}")
            file_keys.add((child_stat.st_dev, child_stat.st_ino))
            content = _read_stable_tree_file(directory_fd, name, child_stat, child_path)
            entries.append({
                "relativePath": child_relative,
                "kind": "FILE",
                "byteLength": len(content),
                "rawFingerprint": raw_ref(content),
            })
        entries.sort(key=lambda entry: _utf16_sort_key(entry["relativePath"]))
    finally:
        os.close(root_fd)
    payload = domain.encode("ascii") + b"\x00" + canonical_bytes(entries)
    return {"kind": "TREE_COMMITMENT", "algorithm": "SHA-256", "value": digest_bytes(payload)}


def _tree_identity(before: os.stat_result, after: os.stat_result) -> bool:
    return (
        (before.st_dev, before.st_ino, before.st_mode, before.st_nlink,
         before.st_size, before.st_mtime_ns, before.st_ctime_ns)
        == (after.st_dev, after.st_ino, after.st_mode, after.st_nlink,
            after.st_size, after.st_mtime_ns, after.st_ctime_ns)
    )


def _read_stable_tree_file(directory_fd: int, name: str, before: os.stat_result,
                           display_path: Path) -> bytes:
    if before.st_size > MAX_TREE_FILE_BYTES:
        fail(f"tree file exceeds bounded read limit: {display_path}")
    fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=directory_fd)
    try:
        opened = os.fstat(fd)
        if not _tree_identity(before, opened):
            fail(f"unstable tree file identity: {display_path}")
        chunks: list[bytes] = []
        total = 0
        while total <= MAX_TREE_FILE_BYTES:
            chunk = os.read(fd, min(64 * 1024, MAX_TREE_FILE_BYTES + 1 - total))
            if not chunk:
                break
            chunks.append(chunk)
            total += len(chunk)
            if total > MAX_TREE_FILE_BYTES:
                fail(f"tree file exceeds bounded read limit: {display_path}")
        content = b"".join(chunks)
        after_fd = os.fstat(fd)
        after_path = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
        if (not _tree_identity(before, after_fd)
                or not _tree_identity(before, after_path)
                or len(content) != before.st_size):
            fail(f"unstable tree file identity or size: {display_path}")
        return content
    finally:
        os.close(fd)


def set_pointer(document: Any, pointer: str, value: Any) -> None:
    if not pointer.startswith("/"):
        fail(f"invalid JSON pointer: {pointer}")
    tokens = [token.replace("~1", "/").replace("~0", "~") for token in pointer[1:].split("/")]
    current = document
    for token in tokens[:-1]:
        current = current[int(token)] if isinstance(current, list) else current[token]
    last = tokens[-1]
    if isinstance(current, list):
        current[int(last)] = value
    else:
        current[last] = value


def schema_validate(instance: Any, schema_path: Path) -> None:
    schema = read_json(schema_path)
    validator_class = jsonschema.validators.validator_for(schema)
    validator_class.check_schema(schema)
    registry = Registry()
    for candidate in SCHEMA_DIR.glob("*.schema.json"):
        document = read_json(candidate)
        if "$id" in document:
            registry = registry.with_resource(document["$id"], Resource.from_contents(document))
    errors = sorted(validator_class(schema, registry=registry).iter_errors(instance), key=lambda error: list(error.path))
    if errors:
        fail(f"schema invalid: {schema_path}: {errors[0].message} at {list(errors[0].path)}")


def material_path(root: Path, relative: str) -> Path:
    if (
        not isinstance(relative, str)
        or not relative
        or relative.startswith(("/", "\\"))
        or "\\" in relative
        or "\x00" in relative
        or any(part in {"", ".", ".."} for part in relative.split("/"))
    ):
        fail(f"invalid material URI: {relative!r}")
    path = (root / relative).resolve()
    root_path = root.resolve()
    if root_path not in path.parents:
        fail(f"material path escapes root: {relative}")
    return path


def load_guard_catalog() -> dict[str, Any]:
    catalog = read_json(GUARD_CATALOG)
    schema_validate(catalog, SCHEMA_DIR / "capability-studio-gate-a-semantic-guard-catalog-v1.schema.json")
    guards = [entry["guardId"] for entry in catalog["guards"]]
    targets = {entry["guardId"]: entry["admissionTarget"] for entry in catalog["guards"]}
    if len(guards) != 18 or len(set(guards)) != len(guards):
        fail("Guard Catalog must contain exactly 18 unique Guards")
    global GUARDS, TARGETS
    GUARDS = guards
    TARGETS = targets
    return catalog


def is_raw_ref(value: Any) -> bool:
    return isinstance(value, dict) and set(value) == {"uri", "rawFingerprint"}


def is_tree_ref(value: Any) -> bool:
    return isinstance(value, dict) and set(value) == {"uri", "fingerprint"} and value.get("fingerprint", {}).get("kind") == "TREE_COMMITMENT"


def visit_refs(value: Any, callback: Any) -> None:
    if is_raw_ref(value) or is_tree_ref(value):
        callback(value)
        return
    if isinstance(value, dict):
        for child in value.values():
            visit_refs(child, callback)
    elif isinstance(value, list):
        for child in value:
            visit_refs(child, callback)


def materialize_declared_refs(material: "Material", document: dict[str, Any]) -> None:
    """Turn fixture-only refs into real files while preserving their wire shape."""
    def materialize(ref: dict[str, Any]) -> None:
        path = material_path(material.root, ref["uri"])
        if is_tree_ref(ref):
            if path.exists() and not path.is_dir():
                fail(f"tree ref points at a file: {ref['uri']}")
            path.mkdir(parents=True, exist_ok=True)
            marker = path / "material-root.txt"
            if not marker.exists():
                marker.write_bytes(f"material-root:{ref['uri']}\n".encode("ascii"))
            ref["fingerprint"] = tree_fingerprint(path, TREE_DOMAINS["challenge"])
            return
        if path.exists() and not path.is_file():
            fail(f"raw ref points at a non-file: {ref['uri']}")
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(f"material:{ref['uri']}\n".encode("utf-8"))
        ref["rawFingerprint"] = raw_ref(path.read_bytes())

    visit_refs(document, materialize)


def reference_closure(root: Path, document: Any) -> bool:
    closed = True

    def check(ref: dict[str, Any]) -> None:
        nonlocal closed
        try:
            path = material_path(root, ref["uri"])
            if is_tree_ref(ref):
                if tree_fingerprint(path, TREE_DOMAINS["challenge"]) != ref["fingerprint"]:
                    closed = False
                return
            if path.is_symlink() or not path.is_file():
                closed = False
                return
            first = path.stat()
            content = path.read_bytes()
            second = path.stat()
            identity_stable = (
                (first.st_dev, first.st_ino, first.st_size, first.st_mtime_ns)
                == (second.st_dev, second.st_ino, second.st_size, second.st_mtime_ns)
            )
            closed = closed and identity_stable and raw_ref(content) == ref["rawFingerprint"]
        except (AssertionError, FileNotFoundError, OSError):
            closed = False

    visit_refs(document, check)
    return closed


class Material:
    def __init__(self, root: Path, case: dict[str, Any], admission_verification_time: dt.datetime):
        self.root = root
        self.case = case
        self.admission_verification_time = admission_verification_time
        self.docs: dict[str, Path] = {}
        self.pinned_paths: dict[str, Path] = {}

    def copy_documents(self) -> None:
        for descriptor in self.case["sourceMaterial"]["documents"]:
            target = descriptor["target"]
            destination = material_path(self.root, target)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPO / descriptor["baseFixture"], destination)
            self.docs[target] = destination

    def document(self, target: str) -> dict[str, Any]:
        return read_json(self.docs[target])

    def find_document(self, message_version: str) -> tuple[str, dict[str, Any]]:
        for target in self.docs:
            document = self.document(target)
            if document.get("messageVersion") == message_version or document.get("schemaVersion") == message_version:
                return target, document
        raise KeyError(f"material document not found: {message_version}")

    def save_document(self, target: str, document: dict[str, Any]) -> None:
        write_json(self.docs[target], document)

    def mutate_document(self, target: str, pointer: str, value: Any) -> None:
        document = self.document(target)
        set_pointer(document, pointer, value)
        self.save_document(target, document)

    def write_bytes(self, target: str, value: bytes) -> Path:
        path = material_path(self.root, target)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value)
        return path

    def rebind_document(self, target: str, field: str, domain: str) -> None:
        document = self.document(target)
        document[field] = document_fingerprint(document, field, domain)
        self.save_document(target, document)


def create_deterministic_jar(path: Path, extra_entries: list[tuple[str, bytes]] | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    entries = [
        ("META-INF/services/com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider", b"com.leanowtech.bloge.gatetckprovider.GateATckProvider\n"),
        ("com/leanowtech/bloge/gatetckprovider/GateATckProvider.class", b"gate-a-provider-class-v1\n"),
    ]
    entries.extend(extra_entries or [])
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, value in entries:
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, value)


def set_raw_ref(document: dict[str, Any], pointer: str, value: bytes) -> None:
    set_pointer(document, pointer, raw_ref(value))


def public_key_pem(raw_public_key: bytes) -> bytes:
    der = bytes.fromhex("302a300506032b6570032100") + raw_public_key
    encoded = base64.b64encode(der).decode("ascii")
    return ("-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----\n").encode("ascii")


def signed_payload(envelope: dict[str, Any]) -> bytes:
    claims = copy.deepcopy(envelope)
    claims.pop("signature", None)
    claims.pop("envelopeFingerprint", None)
    return hashlib.sha256(b"RG-CS-REVIEW-ENVELOPE-SIGNING-v1\x00" + canonical_bytes(claims)).digest()


def verify_ed25519(envelope: dict[str, Any], policy: dict[str, Any], root: Path) -> bool:
    if envelope.get("signatureAlgorithm") != "Ed25519" or policy.get("signatureAlgorithm") != "Ed25519":
        return False
    matching_keys = [key for key in policy.get("allowedKeys", []) if key.get("keyId") == envelope.get("keyId")]
    if len(matching_keys) != 1:
        return False
    try:
        key = matching_keys[0]["publicKeyBase64Url"]
        raw_key = base64.urlsafe_b64decode(key + "===")
        signature = base64.urlsafe_b64decode(envelope["signature"] + "===")
    except (KeyError, ValueError):
        return False
    public_path = root / "review/public.pem"
    digest_path = root / "review/signature-payload.digest"
    signature_path = root / "review/signature.bin"
    public_path.write_bytes(public_key_pem(raw_key))
    digest_path.write_bytes(signed_payload(envelope))
    signature_path.write_bytes(signature)
    process = subprocess.run(
        ["openssl", "pkeyutl", "-verify", "-pubin", "-inkey", str(public_path), "-rawin", "-in", str(digest_path), "-sigfile", str(signature_path)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    return process.returncode == 0


def assert_cryptographic_signature_remains_valid(material: Material, case_id: str) -> None:
    envelope = material.document("review/envelope.json")
    policy = material.document("review/policy.json")
    if not verify_ed25519(envelope, policy, material.root):
        fail(f"{case_id} must retain a cryptographically valid authorized signature")


def run_signed_review_helper(root: Path, mode: str) -> None:
    helper = HERE / "prepare-signed-review-count.mjs"
    process = subprocess.run(["node", str(helper), str(root), mode], stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if process.returncode != 0:
        fail(f"signed review helper failed: {process.stderr.decode('utf-8', 'replace')}")


def verify_canonicalization_reference() -> None:
    vectors = read_json(HERE.parent / "canonicalization" / "canonicalization-vectors-v1.json")
    checked = 0
    for vector in vectors["vectors"]:
        source = vector["sourceText"].encode("utf-8")
        parsed = json.loads(vector["sourceText"])
        canonical_input = copy.deepcopy(parsed)
        if vector["selfField"] is not None:
            canonical_input[vector["selfField"]] = None
        canonical = jcs_canonicalize(canonical_input)
        if canonical != vector["expectedCanonical"]:
            fail(f"Python JCS differs from reference vector {vector['id']}")
        field = vector["selfField"]
        actual = document_fingerprint(parsed, field, vector["domain"])["value"]
        if actual != vector["expectedDocumentFingerprint"]:
            fail(f"Python document fingerprint differs from reference vector {vector['id']}")
        if digest_bytes(source) != vector["expectedRawFingerprint"]:
            fail(f"Python raw fingerprint differs from reference vector {vector['id']}")
        checked += 1
    print(json.dumps({"canonicalizationReference": "PASS", "vectors": checked}, sort_keys=True))


def update_code_source(document: dict[str, Any], field: str, path: Path) -> None:
    stat = path.stat()
    document[field] = {
        "artifactPath": str(path.resolve()),
        "rawFingerprint": raw_ref(path.read_bytes()),
        "fileSize": stat.st_size,
        "fileKey": f"dev:{stat.st_dev}:ino:{stat.st_ino}",
    }


def update_process_code_source(process: dict[str, Any], path: Path) -> None:
    stat = path.stat()
    fingerprint = raw_ref(path.read_bytes())
    file_key = f"dev:{stat.st_dev}:ino:{stat.st_ino}"
    snapshot = {
        "resolvedPath": str(path.resolve()),
        "fileKey": file_key,
        "owner": f"uid:{stat.st_uid}",
        "group": f"gid:{stat.st_gid}",
        "linkCount": stat.st_nlink,
        "posixMode": f"{stat.st_mode & 0o7777:04o}",
        "fileSize": stat.st_size,
        "readRawFingerprint": fingerprint,
    }
    process["codeSource"] = {
        "artifactPath": str(path.resolve()),
        "rawFingerprint": fingerprint,
        "fileSize": stat.st_size,
        "fileKey": file_key,
    }
    process["codeSourceObservation"] = {"preRead": snapshot, "postRead": copy.deepcopy(snapshot)}


def prepare_a1_result(material: Material, target: str) -> None:
    document = material.document(target)
    materialize_declared_refs(material, document)
    material.save_document(target, document)
    material.rebind_document(target, "resultFingerprint", DOMAINS["a1"])


def prepare_a1_proof(material: Material) -> None:
    envelope_target, envelope = material.find_document(
        "resource-gateway.capability-studio.gate-a.replay-proof-envelope.v1"
    )
    result_target, result = material.find_document(
        "resource-gateway.capability-studio.gate-a.replay-verification-result.v1"
    )
    process_target, process = material.find_document(
        "resource-gateway.capability-studio.gate-a.process-transcript.v1"
    )
    envelope["replayResultRef"]["uri"] = result_target
    envelope["producerProcessTranscriptRef"]["uri"] = process_target
    material.save_document(envelope_target, envelope)

    materialize_declared_refs(material, result)
    candidate = material.write_bytes("artifacts/a1-candidate.jar", b"a1-candidate-material-v1\n")
    verifier = material.write_bytes("artifacts/a1-verifier.jar", b"a1-verifier-material-v1\n")
    update_code_source(result, "candidateCodeSource", candidate)
    update_code_source(result, "verifierCodeSource", verifier)
    result_path = material_path(material.root, result_target)
    write_json(result_path, result)
    material.docs.setdefault(result_target, result_path)
    material.rebind_document(result_target, "resultFingerprint", DOMAINS["a1"])

    process = read_json(material_path(material.root, process_target))
    producer = material.write_bytes("artifacts/a1-producer.jar", b"a1-producer-material-v1\n")
    update_process_code_source(process, producer)
    materialize_declared_refs(material, process)
    process_path = material_path(material.root, process_target)
    write_json(process_path, process)
    material.docs.setdefault(process_target, process_path)
    material.rebind_document(process_target, "transcriptFingerprint", DOMAINS["process"])

    result = read_json(result_path)
    process = read_json(process_path)
    envelope = read_json(material_path(material.root, envelope_target))
    envelope["replayResultRef"]["rawFingerprint"] = raw_ref(result_path.read_bytes())
    envelope["producerProcessTranscriptRef"]["rawFingerprint"] = raw_ref(process_path.read_bytes())
    envelope["producerMaterialRootRef"] = {
        "uri": "run-material",
        "fingerprint": tree_fingerprint(material_path(material.root, "run-material"), TREE_DOMAINS["runMaterial"]),
    }
    producer_fingerprint = process["codeSource"]["rawFingerprint"]
    envelope["expectedProducerCodeSourceRawFingerprint"] = copy.deepcopy(producer_fingerprint)
    envelope["observedProducerCodeSourceRawFingerprint"] = copy.deepcopy(producer_fingerprint)
    envelope["observedProcessState"] = process["processState"]
    envelope["observedExitCode"] = process["exitCode"]
    envelope["observedTerminal"] = {
        "VERIFIED": "VERIFIED",
        "INVALID": "INVALID",
        "UNAVAILABLE": "UNAVAILABLE",
    }.get(result["terminal"], "UNAVAILABLE")
    write_json(material_path(material.root, envelope_target), envelope)
    material.docs.setdefault(envelope_target, material_path(material.root, envelope_target))
    material.rebind_document(envelope_target, "envelopeFingerprint", DOMAINS["a1Envelope"])


def prepare_material(material: Material, guard: str) -> None:
    material.copy_documents()
    if guard.startswith("A0_"):
        material.write_bytes("artifacts/candidate.jar", b"candidate-artifact-v1\n")
        document = material.document("a0/result.json")
        materialize_declared_refs(material, document)
        material.save_document("a0/result.json", document)
        material.rebind_document("a0/result.json", "resultFingerprint", DOMAINS["a0"])
        return
    if guard in {"A1_SLOT_COUNT_PROJECTION", "A1_SLOT_OUTCOME_BINDING", "A1_RESULT_FINGERPRINT"}:
        prepare_a1_result(material, "a1/replay.json")
        return
    if guard in {"A1_PROCESS_MATERIAL_CLOSURE", "HARNESS_PROOF_COMPLETENESS"}:
        prepare_a1_proof(material)
        return
    if guard == "PROVIDER_NAMESPACE_COLLISION_REJECTED":
        create_deterministic_jar(material_path(material.root, "provider/provider.jar"))
        return
    if guard == "PIN_LIFECYCLE_BINDING":
        candidate = material.write_bytes("artifacts/candidate.jar", b"candidate-artifact-v1\n")
        pin = material.document("pins/challenge.json")
        pin["expectedImplementationCandidateRawFingerprint"] = raw_ref(candidate.read_bytes())
        material.save_document("pins/challenge.json", pin)
        return
    if guard == "ADMISSION_EVIDENCE_ROOT_CLOSURE":
        evidence_root = material_path(material.root, "admission-evidence/root.tree")
        evidence_root.mkdir(parents=True, exist_ok=True)
        material.write_bytes("admission-evidence/root.tree/TEST_REPORT.json", b"canonical test report\n")
        result = material.document("admission/result.json")
        result["admissionEvidenceRootRef"]["uri"] = "admission-evidence/root.tree"
        result["admissionEvidenceRootRef"]["fingerprint"] = tree_fingerprint(evidence_root, TREE_DOMAINS["admission"])
        material.save_document("admission/result.json", result)
        material.rebind_document("admission/result.json", "resultFingerprint", DOMAINS["a2"])
        return
    if guard == "CODESOURCE_INDEPENDENCE":
        harness = material.write_bytes("artifacts/harness.jar", b"same-code-source-bytes\n")
        material.write_bytes("artifacts/harness-alias.jar", harness.read_bytes())
        identity = harness.stat()
        file_key = f"dev:{identity.st_dev}:ino:{identity.st_ino}"
        snapshot = {
            "resolvedPath": str(harness.resolve()),
            "fileKey": file_key,
            "owner": f"uid:{identity.st_uid}",
            "group": f"gid:{identity.st_gid}",
            "linkCount": identity.st_nlink,
            "posixMode": f"{identity.st_mode & 0o7777:04o}",
            "fileSize": identity.st_size,
            "readRawFingerprint": raw_ref(harness.read_bytes()),
        }
        transcript = material.document("process/harness.json")
        transcript["codeSource"]["artifactPath"] = str(harness.resolve())
        transcript["codeSource"]["rawFingerprint"] = raw_ref(harness.read_bytes())
        transcript["codeSource"]["fileSize"] = identity.st_size
        transcript["codeSource"]["fileKey"] = file_key
        transcript["codeSourceObservation"] = {"preRead": snapshot, "postRead": copy.deepcopy(snapshot)}
        material.save_document("process/harness.json", transcript)
        material.pinned_paths["codeSource"] = harness.resolve()
        return
    if guard == "REVIEW_SIGNATURE_AUTHORITY":
        run_signed_review_helper(material.root, "baseline")
        return
    if guard == "REVIEW_COUNT_CONSISTENCY_REJECTED":
        run_signed_review_helper(material.root, "baseline")
        return
    if guard == "ROLLBACK_BINDING":
        instruction = material.write_bytes("rollback/gate-a.md", b"rollback candidate-v1\n")
        result = material.document("admission/result.json")
        result["rollback"]["instructionRef"]["rawFingerprint"] = raw_ref(instruction.read_bytes())
        material.save_document("admission/result.json", result)
        material.rebind_document("admission/result.json", "resultFingerprint", DOMAINS["a2"])
        return
    if guard in {"A2_CONCLUSION_PRECEDENCE", "A2_RESULT_FINGERPRINT"}:
        material.rebind_document("admission/result.json", "resultFingerprint", DOMAINS["a2"])
        return
    fail(f"no material preparer for {guard}")


def apply_mutation(material: Material, case: dict[str, Any]) -> None:
    guard = case["guardId"]
    case_id = case["caseId"]
    if guard == "A0_SLOT_COUNT_PROJECTION":
        material.mutate_document("a0/result.json", "/adapterVerifiedCount", 2)
        material.rebind_document("a0/result.json", "resultFingerprint", DOMAINS["a0"])
    elif guard == "A0_TERMINAL_DERIVATION":
        document = material.document("a0/result.json")
        document["terminal"] = "STRUCTURE_VERIFIED"
        document["reasonCode"] = "A0_STRUCTURE_VERIFIED"
        material.save_document("a0/result.json", document)
        material.rebind_document("a0/result.json", "resultFingerprint", DOMAINS["a0"])
    elif guard == "A0_REFERENCE_CLOSURE":
        pointer = "/candidateArtifactRef/rawFingerprint/value" if case_id == "REAL-A0-RAW-FINGERPRINT" else "/candidateArtifactRef/uri"
        value = "sha256:" + "a" * 64 if case_id == "REAL-A0-RAW-FINGERPRINT" else "artifacts/missing.jar"
        material.mutate_document("a0/result.json", pointer, value)
        material.rebind_document("a0/result.json", "resultFingerprint", DOMAINS["a0"])
    elif guard == "A0_RESULT_FINGERPRINT":
        material.mutate_document("a0/result.json", "/resultFingerprint/value", "sha256:" + "f" * 64)
    elif guard == "A1_SLOT_COUNT_PROJECTION":
        material.mutate_document("a1/replay.json", "/passedCount", 8)
        material.rebind_document("a1/replay.json", "resultFingerprint", DOMAINS["a1"])
    elif guard == "A1_SLOT_OUTCOME_BINDING":
        document = material.document("a1/replay.json")
        document["terminal"] = "INVALID"
        document["reasonCode"] = "A1_REPLAY_INVALID"
        material.save_document("a1/replay.json", document)
        material.rebind_document("a1/replay.json", "resultFingerprint", DOMAINS["a1"])
    elif guard == "A1_PROCESS_MATERIAL_CLOSURE":
        if case_id == "REAL-A1-OUTER-TRANSCRIPT-CRASH":
            document = material.document("run-material/transcripts/a1-process.json")
            document["processState"] = "FAILED"
            document["exitCode"] = 1
            material.save_document("run-material/transcripts/a1-process.json", document)
        else:
            envelope_target, envelope = material.find_document(
                "resource-gateway.capability-studio.gate-a.replay-proof-envelope.v1"
            )
            if case_id == "REAL-A1-PROOF-WRONG-RESULT-REF":
                envelope["replayResultRef"]["uri"] = "run-material/results/missing.json"
            elif case_id == "REAL-A1-PROOF-RESULT-DIGEST":
                envelope["replayResultRef"]["rawFingerprint"]["value"] = "sha256:" + "b" * 64
            elif case_id == "REAL-A1-PROOF-TRANSCRIPT-DIGEST":
                envelope["producerProcessTranscriptRef"]["rawFingerprint"]["value"] = "sha256:" + "c" * 64
            elif case_id == "REAL-A1-PROOF-MATERIAL-ROOT":
                envelope["producerMaterialRootRef"]["fingerprint"]["value"] = "sha256:" + "d" * 64
            elif case_id == "REAL-A1-PROOF-TERMINAL-EXIT":
                envelope["observedTerminal"] = "INVALID"
                envelope["observedExitCode"] = 2
            else:
                fail(f"unknown A1 proof material attack: {case_id}")
            material.save_document(envelope_target, envelope)
            material.rebind_document(envelope_target, "envelopeFingerprint", DOMAINS["a1Envelope"])
    elif guard == "A1_RESULT_FINGERPRINT":
        material.mutate_document("a1/replay.json", "/resultFingerprint/value", "sha256:" + "e" * 64)
    elif guard == "HARNESS_PROOF_COMPLETENESS":
        material_path(material.root, "run-material/results/a1-replay-result.json").unlink()
    elif guard == "PROVIDER_NAMESPACE_COLLISION_REJECTED":
        create_deterministic_jar(material_path(material.root, "provider/provider.jar"), [
            ("com/leanowtech/bloge/gateway/testkit/ShadowProvider.class", b"forbidden-provider-class-v1\n")
        ])
    elif guard == "PIN_LIFECYCLE_BINDING":
        material_path(material.root, "artifacts/candidate.jar").write_bytes(b"candidate-artifact-v1-tampered\n")
    elif guard == "ADMISSION_EVIDENCE_ROOT_CLOSURE":
        if case_id.endswith("SYMLINK"):
            root = material_path(material.root, "admission-evidence/root.tree")
            outside = material.write_bytes("outside/symlink-target.txt", b"symlink target\n")
            link = root / ("linked-directory" if case_id.endswith("DIRECTORY-SYMLINK") else "linked-file.txt")
            if case_id.endswith("DIRECTORY-SYMLINK"):
                outside = material_path(material.root, "outside/symlink-target.tree")
                outside.mkdir(parents=True, exist_ok=True)
            os.symlink(os.path.relpath(outside, link.parent), link)
        else:
            material_path(material.root, "admission-evidence/root.tree/TEST_REPORT.json").write_bytes(b"tampered test report\n")
    elif guard == "CODESOURCE_INDEPENDENCE":
        alias = material_path(material.root, "artifacts/harness-alias.jar").resolve()
        material.mutate_document("process/harness.json", "/codeSource/artifactPath", str(alias))
    elif guard == "REVIEW_SIGNATURE_AUTHORITY":
        if case_id == "REAL-REVIEW-SIGNATURE-TAMPER":
            material.mutate_document("review/envelope.json", "/signature", base64.urlsafe_b64encode(b"\x00" * 64).decode("ascii").rstrip("="))
        elif case_id == "REAL-REVIEW-CHECK-FINDING-MISMATCH":
            run_signed_review_helper(material.root, "check-mismatch")
        elif case_id == "REAL-REVIEW-DUPLICATE-FINDING-ID":
            run_signed_review_helper(material.root, "duplicate-finding-id")
        elif case_id == "REAL-REVIEW-FINDING-ORDER-DRIFT":
            run_signed_review_helper(material.root, "finding-order-drift")
        elif case_id == "REAL-REVIEW-CANDIDATE-BINDING-DRIFT":
            run_signed_review_helper(material.root, "candidate-binding-drift")
        elif case_id == "REAL-REVIEW-BODY-ENVELOPE-REVIEWED-AT-DRIFT":
            run_signed_review_helper(material.root, "body-envelope-reviewed-at-drift")
        elif case_id == "REAL-REVIEW-REVOCATION-ISSUED-AFTER-REVIEW":
            run_signed_review_helper(material.root, "revocation-issued-after-review")
        elif case_id == "REAL-REVIEW-EXPIRED-REVIEW":
            run_signed_review_helper(material.root, "expired-review")
        elif case_id == "REAL-REVIEW-EXPIRED-POLICY":
            run_signed_review_helper(material.root, "expired-policy")
        elif case_id == "REAL-REVIEW-EXPIRED-REVOCATION":
            run_signed_review_helper(material.root, "expired-revocation")
        elif case_id == "REAL-REVIEW-KEYID-DRIFT":
            material.mutate_document("review/envelope.json", "/keyId", "key:unknown-fixture")
            material.rebind_document("review/envelope.json", "envelopeFingerprint", DOMAINS["reviewEnvelope"])
        elif case_id == "REAL-REVIEW-ISSUER-DRIFT":
            material.mutate_document("review/envelope.json", "/issuer", "issuer:untrusted-fixture")
            material.rebind_document("review/envelope.json", "envelopeFingerprint", DOMAINS["reviewEnvelope"])
        elif case_id == "REAL-REVIEW-AUTHORITY-DRIFT":
            material.mutate_document("review/envelope.json", "/authorityId", "authority:untrusted-fixture")
            material.rebind_document("review/envelope.json", "envelopeFingerprint", DOMAINS["reviewEnvelope"])
        elif case_id == "REAL-REVIEW-REVOCATION-DRIFT":
            material.mutate_document("review/envelope.json", "/revocationSnapshotRawFingerprint/value", "sha256:" + "e" * 64)
            material.rebind_document("review/envelope.json", "envelopeFingerprint", DOMAINS["reviewEnvelope"])
        elif case_id == "REAL-REVIEW-POLICY-FINGERPRINT-DRIFT":
            material.mutate_document("review/policy.json", "/reviewerTrustPolicyFingerprint/value", "sha256:" + "f" * 64)
        else:
            fail(f"unknown signed review semantic attack: {case_id}")
    elif guard == "REVIEW_COUNT_CONSISTENCY_REJECTED":
        mode = {
            "REAL-REVIEW-COUNT-UNDERREPORT": "underreport",
            "REAL-REVIEW-COUNT-OPEN-P1-UNDERREPORT": "underreport-open-p1",
            "REAL-REVIEW-COUNT-SKIPPED-UNDERREPORT": "underreport-skipped-count",
        }[case_id]
        run_signed_review_helper(material.root, mode)
    elif guard == "ROLLBACK_BINDING":
        material_path(material.root, "rollback/gate-a.md").write_bytes(b"rollback candidate-v2 unauthorized\n")
    elif guard == "A2_CONCLUSION_PRECEDENCE":
        document = material.document("admission/result.json")
        if case_id == "REAL-A2-REQUIREMENT-SLOT-DRIFT":
            document["requirements"][0]["status"] = "FAIL"
        else:
            document["semanticGuardResults"][0]["status"] = "FAIL"
            document["semanticGuardResults"][0]["reasonCode"] = "GUARD_MISMATCH"
        material.save_document("admission/result.json", document)
        material.rebind_document("admission/result.json", "resultFingerprint", DOMAINS["a2"])
    elif guard == "A2_RESULT_FINGERPRINT":
        material.mutate_document("admission/result.json", "/resultFingerprint/value", "sha256:" + "d" * 64)
    else:
        fail(f"no material mutation for {guard}")


def get_document(material: Material, target: str) -> dict[str, Any]:
    return material.document(target)


def replay_result_target(material: Material) -> str:
    if "a1/replay.json" in material.docs:
        return "a1/replay.json"
    if "run-material/results/a1-replay-result.json" in material.docs:
        return "run-material/results/a1-replay-result.json"
    raise KeyError("A1 replay result is not part of this material case")


def result(guard: str, status: str, conclusion: str, reason: str, exit_code: int) -> dict[str, Any]:
    return {
        "guardId": guard,
        "status": status,
        "admissionTarget": TARGETS[guard],
        "conclusion": conclusion,
        "reason": reason,
        "exit": exit_code,
    }


def actual_raw_ref(material: Material, ref: dict[str, Any]) -> tuple[Path, bool]:
    path = material_path(material.root, ref["uri"])
    if path.is_symlink() or not path.is_file():
        return path, False
    first = path.stat()
    content = path.read_bytes()
    second = path.stat()
    stable = (first.st_dev, first.st_ino, first.st_size, first.st_mtime_ns) == (
        second.st_dev, second.st_ino, second.st_size, second.st_mtime_ns
    )
    return path, stable and raw_ref(content) == ref["rawFingerprint"]


def a1_proof_closure(material: Material) -> tuple[bool, str, str, int]:
    try:
        _, envelope = material.find_document(
            "resource-gateway.capability-studio.gate-a.replay-proof-envelope.v1"
        )
    except KeyError:
        raise
    try:
        result_ref = envelope["replayResultRef"]
        process_ref = envelope["producerProcessTranscriptRef"]
        result_path, result_bound = actual_raw_ref(material, result_ref)
        if not result_path.exists():
            reason = (
                "A1_REPLAY_PROOF_MISSING"
                if result_ref.get("uri") == "run-material/results/a1-replay-result.json"
                else "A1_REPLAY_RESULT_REF_CLOSURE"
            )
            return False, reason, "FAIL", 2
        if not result_bound:
            return False, "A1_REPLAY_RESULT_RAW_FINGERPRINT", "FAIL", 2
        process_candidate = material_path(material.root, process_ref["uri"])
        if not process_candidate.exists():
            return False, "A1_PROCESS_TRANSCRIPT_MISSING", "FAIL", 2
        process_candidate_document = read_json(process_candidate)
        if process_candidate_document.get("processState") == "FAILED":
            return False, "A1_OUTER_TRANSCRIPT_CRASHED", "UNAVAILABLE", 3
        process_path, process_bound = actual_raw_ref(material, process_ref)
        if not process_path.exists():
            return False, "A1_PROCESS_TRANSCRIPT_MISSING", "FAIL", 2
        if not process_bound:
            return False, "A1_PROCESS_TRANSCRIPT_RAW_FINGERPRINT", "FAIL", 2
        result_document = read_json(result_path)
        process_document = read_json(process_path)
        if result_document.get("messageVersion") != envelope["replayResultMessageVersion"]:
            return False, "A1_REPLAY_RESULT_REF_CLOSURE", "FAIL", 2
        if process_document.get("messageVersion") != envelope["producerProcessMessageVersion"]:
            return False, "A1_PROCESS_TRANSCRIPT_REF_CLOSURE", "FAIL", 2
        if not reference_closure(material.root, result_document) or not reference_closure(material.root, process_document):
            return False, "A1_PROCESS_MATERIAL_CLOSURE", "FAIL", 2
        if result_document["resultFingerprint"] != document_fingerprint(
            result_document, "resultFingerprint", DOMAINS["a1"]
        ):
            return False, "A1_REPLAY_RESULT_FINGERPRINT", "FAIL", 2
        if process_document["transcriptFingerprint"] != document_fingerprint(
            process_document, "transcriptFingerprint", DOMAINS["process"]
        ):
            return False, "A1_PROCESS_TRANSCRIPT_FINGERPRINT", "FAIL", 2
        if envelope["envelopeFingerprint"] != document_fingerprint(
            envelope, "envelopeFingerprint", DOMAINS["a1Envelope"]
        ):
            return False, "A1_PROOF_ENVELOPE_FINGERPRINT", "FAIL", 2
        root_path = material_path(material.root, envelope["producerMaterialRootRef"]["uri"])
        if tree_fingerprint(root_path, TREE_DOMAINS["runMaterial"]) != envelope["producerMaterialRootRef"]["fingerprint"]:
            return False, "A1_MATERIAL_ROOT_CLOSURE", "FAIL", 2
        code_source = process_document["codeSource"]
        code_path = Path(code_source["artifactPath"]).resolve()
        if code_path.is_symlink() or not code_path.is_file() or raw_ref(code_path.read_bytes()) != code_source["rawFingerprint"]:
            return False, "A1_PROCESS_MATERIAL_CLOSURE", "FAIL", 2
        if envelope["expectedProducerCodeSourceRawFingerprint"] != code_source["rawFingerprint"]:
            return False, "A1_PRODUCER_CODE_SOURCE_CLOSURE", "FAIL", 2
        if envelope["observedProducerCodeSourceRawFingerprint"] != code_source["rawFingerprint"]:
            return False, "A1_PRODUCER_CODE_SOURCE_CLOSURE", "FAIL", 2
        if envelope["observedProcessState"] != process_document["processState"] or envelope["observedExitCode"] != process_document["exitCode"]:
            if process_document["processState"] == "FAILED":
                return False, "A1_OUTER_TRANSCRIPT_CRASHED", "UNAVAILABLE", 3
            return False, "A1_TERMINAL_EXIT_MAPPING", "FAIL", 2
        expected_terminal = {0: "VERIFIED", 2: "INVALID", 3: "UNAVAILABLE"}.get(process_document["exitCode"])
        if expected_terminal != envelope["observedTerminal"] or expected_terminal != result_document["terminal"]:
            return False, "A1_TERMINAL_EXIT_MAPPING", "FAIL", 2
        if process_document["processState"] != "COMPLETED" or envelope["closureStatus"] != "CLOSED":
            if process_document["processState"] == "FAILED":
                return False, "A1_OUTER_TRANSCRIPT_CRASHED", "UNAVAILABLE", 3
            return False, "A1_PROCESS_MATERIAL_CLOSURE", "FAIL", 2
        return True, "A1_PROCESS_MATERIAL_CLOSURE", "PASS", 2
    except (AssertionError, FileNotFoundError, KeyError, OSError, json.JSONDecodeError):
        return False, "A1_PROCESS_MATERIAL_CLOSURE", "FAIL", 2


def a1_slot_state(document: dict[str, Any]) -> tuple[dict[str, int], bool]:
    counts = {"PASS": 0, "FAIL": 0, "SKIPPED": 0}
    coherent = document.get("testCount") == len(document.get("testRuns", [])) == 9
    for slot in document.get("testRuns", []):
        matches = (
            slot.get("processExitCode") == slot.get("expectedExitCode")
            and slot.get("observedTerminal") == slot.get("expectedTerminal")
            and slot.get("closedReasonCode") == slot.get("expectedReasonCode")
            and bool(slot.get("expectedMechanism"))
        )
        state = "SKIPPED" if slot.get("skipped") else "PASS" if slot.get("status") == "PASS" and matches else "FAIL"
        counts[state] += 1
        coherent = coherent and (state != "FAIL")
    return counts, coherent


def admission_document(material: Material) -> tuple[str, dict[str, Any]] | None:
    try:
        return material.find_document("resource-gateway.capability-studio.gate-a.admission-verification-result.v1")
    except KeyError:
        return None


def conclusion_state(value: Any) -> str:
    status = value.get("status") if isinstance(value, dict) else None
    if status == "UNAVAILABLE":
        return "UNAVAILABLE"
    if status == "FAIL":
        return "FAIL"
    if status == "MISSING":
        return "OPEN"
    if status == "PASS":
        if value.get("skipped") is True or any(value.get(key, 0) != 0 for key in ("openP0", "openP1", "skippedCount")):
            return "OPEN"
        return "PASS"
    return "UNAVAILABLE"


def reduce_a2_conclusion(document: dict[str, Any]) -> str:
    states = [conclusion_state(slot) for slot in document["requirements"]]
    states += [conclusion_state(slot) for slot in document["artifacts"]]
    states += [conclusion_state(slot) for slot in document["tests"]]
    states += [conclusion_state(slot) for slot in document["mandatoryGuards"]]
    states.append(conclusion_state(document["trustedReview"]))
    expected_guard_ids = GUARDS
    semantic = document["semanticGuardResults"]
    if [entry.get("guardId") for entry in semantic] != expected_guard_ids:
        return "UNAVAILABLE"
    if any(entry.get("admissionTarget") != TARGETS[entry["guardId"]] for entry in semantic):
        return "FAIL"
    states += [conclusion_state(entry) for entry in semantic]
    return max(states, key={"PASS": 0, "OPEN": 1, "FAIL": 2, "UNAVAILABLE": 3}.get)


def review_counts(material: Material, body: dict[str, Any], envelope: dict[str, Any]) -> tuple[bool, dict[str, int]]:
    counts = {
        "openP0": sum(finding["severity"] == "P0" and finding["status"] == "OPEN" for finding in body["findings"]),
        "openP1": sum(finding["severity"] == "P1" and finding["status"] == "OPEN" for finding in body["findings"]),
        "skippedCount": sum(check["status"] == "SKIPPED" for check in body["reviewChecks"]),
    }
    projections: list[dict[str, Any]] = [body, envelope]
    admission = admission_document(material)
    if admission is not None:
        trusted = admission[1].get("trustedReview", {})
        if all(key in trusted for key in counts):
            projections.append(trusted)
        gate_ref = admission[1].get("gateResultRef")
        if isinstance(gate_ref, dict) and isinstance(gate_ref.get("uri"), str):
            try:
                gate_result = read_json(material_path(material.root, gate_ref["uri"]))
                if all(key in gate_result for key in counts):
                    projections.append(gate_result)
            except (AssertionError, FileNotFoundError, OSError, json.JSONDecodeError):
                pass
    return all(all(projection.get(key) == value for key, value in counts.items()) for projection in projections), counts


def parse_time(value: str) -> dt.datetime:
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))


def read_admission_verification_time(path: Path = ADMISSION_TRUST_PIN) -> dt.datetime:
    pin = read_stable_admission_trust_pin(path)
    if not isinstance(pin.get("admissionContext"), dict):
        fail("trusted admission pin has no strict verification context")
    value = pin["admissionContext"].get("admissionVerificationTime")
    if not isinstance(value, str) or not value.endswith("Z"):
        fail("trusted admission pin has no strict verification time")
    parsed = parse_time(value)
    if parsed.tzinfo is None:
        fail("trusted admission pin verification time has no timezone")
    return parsed


def verify_admission_pin_duplicate_negative_case() -> None:
    duplicate_pin = (
        b'{"admissionContext":{"admissionVerificationTime":"2026-08-21T09:32:00Z",'
        b'"admissionVerificationTime":"2026-08-21T09:32:01Z"}}'
    )
    with tempfile.TemporaryDirectory(prefix="rggatea-pin-negative-", dir="/tmp") as directory:
        path = Path(directory) / "duplicate-admission-trust-pin.json"
        path.write_bytes(duplicate_pin)
        try:
            read_admission_verification_time(path)
        except (AssertionError, OSError, ValueError):
            return
    fail("duplicate admission trust pin member was accepted")


def reviewer_authority_valid(material: Material) -> bool:
    body_path = material_path(material.root, "review/body.json")
    body = material.document("review/body.json")
    envelope = material.document("review/envelope.json")
    policy = material.document("review/policy.json")
    revocation = material.document("review/revocation.json")
    try:
        checks = body["reviewChecks"]
        finding_ids = [finding["findingId"] for finding in body["findings"]]
        findings_by_check = {check["checkId"]: [finding for finding in body["findings"] if finding["checkId"] == check["checkId"]] for check in checks}
        check_relations = all(
            (check["status"] == "FINDING" and findings_by_check[check["checkId"]])
            or (check["status"] in {"PASS", "SKIPPED"} and not findings_by_check[check["checkId"]])
            for check in checks
        )
        matching_keys = [key for key in policy["allowedKeys"] if key["keyId"] == envelope["keyId"]]
        review_time = parse_time(envelope["reviewedAt"])
        valid_until = parse_time(envelope["validUntil"])
        policy_valid_until = parse_time(policy["validUntil"])
        verification_time = material.admission_verification_time
        policy_not_before = parse_time(policy["notBefore"])
        revocation_issued_at = parse_time(revocation["issuedAt"])
        revocation_valid_until = parse_time(revocation["validUntil"])
        valid_window = policy_not_before <= review_time <= valid_until <= policy_valid_until
        pinned_policy_window = policy_not_before <= verification_time <= policy_valid_until
        pinned_review_window = review_time <= verification_time <= valid_until
        pinned_revocation_window = revocation_issued_at <= verification_time <= revocation_valid_until
        body_review_time_bound = body["reviewedAt"] == envelope["reviewedAt"]
        revocation_window = revocation_issued_at <= review_time <= revocation_valid_until
        root_bound = body["reviewedMaterialRootFingerprint"] == envelope["reviewedMaterialRootFingerprint"]
        policy_bound = (
            envelope["reviewScope"] == policy["reviewScope"]
            and envelope["candidateRawFingerprint"] == policy["candidateSubject"]
            and envelope["admissionProfileRawFingerprint"] == policy["admissionProfileRawFingerprint"]
            and [check["checkId"] for check in checks] == policy["requiredCheckIds"]
        )
        revocation_bound = (
            envelope["issuer"] == policy["issuer"] == revocation["issuer"]
            and envelope["revocationSnapshotRawFingerprint"] == policy["revocationSnapshotRawFingerprint"] == raw_ref(material_path(material.root, "review/revocation.json").read_bytes())
            and envelope["keyId"] not in revocation["revokedKeyIds"]
            and envelope["authorityId"] not in revocation["revokedAuthorityIds"]
            and parse_time(envelope["validUntil"]) <= parse_time(revocation["validUntil"])
        )
        fingerprint_bound = (
            body["reviewBodyFingerprint"] == document_fingerprint(body, "reviewBodyFingerprint", DOMAINS["reviewBody"])
            and envelope["envelopeFingerprint"] == document_fingerprint(envelope, "envelopeFingerprint", DOMAINS["reviewEnvelope"])
            and policy["reviewerTrustPolicyFingerprint"] == document_fingerprint(policy, "reviewerTrustPolicyFingerprint", DOMAINS["reviewPolicy"])
            and revocation["reviewerRevocationSnapshotFingerprint"] == document_fingerprint(revocation, "reviewerRevocationSnapshotFingerprint", DOMAINS["reviewRevocation"])
        )
        authority_bound = (
            envelope["issuer"] == policy["issuer"]
            and envelope["authorityId"] in policy["allowedAuthorities"]
            and envelope["gateId"] == body["gateId"] == "GATE-A"
            and envelope["gateRevision"] == body["gateRevision"] == 1
            and envelope["reviewBodyRawFingerprint"] == raw_ref(body_path.read_bytes())
            and root_bound
            and policy_bound
            and revocation_bound
            and fingerprint_bound
        )
        semantic_bound = (
            len(matching_keys) == 1
            and envelope["signatureAlgorithm"] == policy["signatureAlgorithm"] == "Ed25519"
            and len(finding_ids) == len(set(finding_ids))
            and finding_ids == sorted(finding_ids)
            and check_relations
            and valid_window
            and pinned_policy_window
            and pinned_review_window
            and pinned_revocation_window
            and body_review_time_bound
            and revocation_window
        )
        return bool(authority_bound and semantic_bound and verify_ed25519(envelope, policy, material.root))
    except (AssertionError, FileNotFoundError, KeyError, OSError, TypeError, ValueError):
        return False


def collect(material: Material, guard: str) -> dict[str, Any]:
    if guard.startswith("A0_"):
        document = get_document(material, "a0/result.json")
        adapter_statuses = [entry["status"] for entry in document["adapterResults"]]
        obligation_statuses = [entry["status"] for entry in document["obligationResults"]]
        if guard == "A0_SLOT_COUNT_PROJECTION":
            expected = {
                "adapterVerifiedCount": adapter_statuses.count("VERIFIED"),
                "adapterInvalidCount": adapter_statuses.count("INVALID"),
                "adapterUnavailableCount": adapter_statuses.count("UNAVAILABLE"),
                "adapterNotRunCount": adapter_statuses.count("NOT_RUN"),
                "obligationFailedCount": obligation_statuses.count("FAILED"),
                "obligationBlockedCount": obligation_statuses.count("BLOCKED"),
                "obligationNotRunCount": obligation_statuses.count("NOT_RUN"),
            }
            passed = all(document[key] == value for key, value in expected.items())
            return result(guard, "PASS" if passed else "FAIL", "FAIL", "A0_SLOT_COUNT_PROJECTION", 2)
        if guard == "A0_TERMINAL_DERIVATION":
            derived = "UNAVAILABLE" if "UNAVAILABLE" in adapter_statuses else "INVALID" if "INVALID" in adapter_statuses else "STRUCTURE_VERIFIED" if "VERIFIED" in adapter_statuses else "INCOMPLETE"
            reason = {"UNAVAILABLE": "A0_UNAVAILABLE", "INVALID": "A0_INVALID", "STRUCTURE_VERIFIED": "A0_STRUCTURE_VERIFIED", "INCOMPLETE": "A0_INCOMPLETE"}[derived]
            passed = document["terminal"] == derived and document["reasonCode"] == reason
            return result(guard, "PASS" if passed else "FAIL", "FAIL", "A0_TERMINAL_DERIVATION", 2)
        if guard == "A0_REFERENCE_CLOSURE":
            return result(guard, "PASS" if reference_closure(material.root, document) else "FAIL", "FAIL", "A0_REFERENCE_CLOSURE", 2)
        actual = document_fingerprint(document, "resultFingerprint", DOMAINS["a0"])["value"]
        return result(guard, "PASS" if document["resultFingerprint"]["value"] == actual else "FAIL", "FAIL", "A0_RESULT_FINGERPRINT", 2)

    if guard.startswith("A1_") and guard != "A1_PROCESS_MATERIAL_CLOSURE":
        document = get_document(material, replay_result_target(material))
        counts, coherent = a1_slot_state(document)
        if guard == "A1_SLOT_COUNT_PROJECTION":
            passed = coherent and document["passedCount"] == counts["PASS"] and document["failedCount"] == counts["FAIL"] and document["skippedCount"] == counts["SKIPPED"]
            return result(guard, "PASS" if passed else "FAIL", "FAIL", "A1_SLOT_COUNT_PROJECTION", 2)
        if guard == "A1_SLOT_OUTCOME_BINDING":
            terminal = "VERIFIED" if coherent and counts["PASS"] == 9 else "INVALID"
            reason_code = "A1_REPLAY_VERIFIED" if terminal == "VERIFIED" else "A1_REPLAY_INVALID"
            passed = coherent and document["terminal"] == terminal and document["reasonCode"] == reason_code
            return result(guard, "PASS" if passed else "FAIL", "FAIL", "A1_SLOT_OUTCOME_BINDING", 2)
        actual = document_fingerprint(document, "resultFingerprint", DOMAINS["a1"])["value"]
        return result(guard, "PASS" if document["resultFingerprint"]["value"] == actual else "FAIL", "FAIL", "A1_RESULT_FINGERPRINT", 2)

    if guard in {"A1_PROCESS_MATERIAL_CLOSURE", "HARNESS_PROOF_COMPLETENESS"}:
        passed, reason, status, exit_code = a1_proof_closure(material)
        if guard == "HARNESS_PROOF_COMPLETENESS" and reason == "A1_REPLAY_PROOF_MISSING":
            return result(guard, "FAIL", "FAIL", reason, 2)
        if guard == "HARNESS_PROOF_COMPLETENESS":
            return result(guard, "PASS" if passed else "FAIL", "FAIL", "HARNESS_PROOF_COMPLETENESS", 2)
        return result(guard, status, status, reason, exit_code)

    if guard == "PROVIDER_NAMESPACE_COLLISION_REJECTED":
        jar = material_path(material.root, "provider/provider.jar")
        with zipfile.ZipFile(jar) as archive:
            forbidden = [name for name in archive.namelist() if name.startswith("com/leanowtech/bloge/gateway/testkit/")]
        return result(guard, "FAIL" if forbidden else "PASS", "FAIL", "PROVIDER_NAMESPACE_COLLISION_REJECTED", 2)

    if guard == "PIN_LIFECYCLE_BINDING":
        pin = get_document(material, "pins/challenge.json")
        actual = raw_ref(material_path(material.root, "artifacts/candidate.jar").read_bytes())
        return result(guard, "FAIL" if pin["expectedImplementationCandidateRawFingerprint"] != actual else "PASS", "FAIL", "PIN_LIFECYCLE_BINDING", 2)

    if guard == "ADMISSION_EVIDENCE_ROOT_CLOSURE":
        document = get_document(material, "admission/result.json")
        try:
            root = material_path(material.root, document["admissionEvidenceRootRef"]["uri"])
            if not root.exists():
                raise KeyError("admission evidence root is not part of this material case")
            actual = tree_fingerprint(root, TREE_DOMAINS["admission"])
            passed = actual == document["admissionEvidenceRootRef"]["fingerprint"]
        except KeyError:
            raise
        except (AssertionError, FileNotFoundError, OSError):
            passed = False
        return result(guard, "PASS" if passed else "FAIL", "FAIL", "ADMISSION_EVIDENCE_ROOT_CLOSURE", 2)

    if guard == "CODESOURCE_INDEPENDENCE":
        document = get_document(material, "process/harness.json")
        expected = material.pinned_paths["codeSource"]
        observed = Path(document["codeSource"]["artifactPath"])
        try:
            expected_stat = expected.stat()
            observed_stat = observed.stat()
            closed = (
                (expected_stat.st_dev, expected_stat.st_ino) == (observed_stat.st_dev, observed_stat.st_ino)
                and os.path.realpath(expected) == os.path.realpath(observed)
                and expected.read_bytes() == observed.read_bytes()
            )
        except (FileNotFoundError, OSError):
            closed = False
        return result(guard, "PASS" if closed else "FAIL", "FAIL", "CODESOURCE_INDEPENDENCE", 2)

    if guard in {"REVIEW_SIGNATURE_AUTHORITY", "REVIEW_COUNT_CONSISTENCY_REJECTED"}:
        envelope = get_document(material, "review/envelope.json")
        policy = get_document(material, "review/policy.json")
        signature_valid = verify_ed25519(envelope, policy, material.root)
        if guard == "REVIEW_SIGNATURE_AUTHORITY":
            return result(guard, "PASS" if reviewer_authority_valid(material) else "FAIL", "FAIL", "REVIEW_SIGNATURE_AUTHORITY", 2)
        body = get_document(material, "review/body.json")
        consistent, _ = review_counts(material, body, envelope)
        return result(guard, "FAIL" if signature_valid and not consistent else "PASS", "FAIL", "REVIEW_COUNT_CONSISTENCY_REJECTED", 2)

    if guard == "ROLLBACK_BINDING":
        document = get_document(material, "admission/result.json")
        try:
            path = material_path(material.root, document["rollback"]["instructionRef"]["uri"])
            if not path.exists():
                raise KeyError("rollback instruction is not part of this material case")
            _, closed = actual_raw_ref(material, document["rollback"]["instructionRef"])
        except KeyError:
            raise
        except (AssertionError, OSError):
            closed = False
        return result(guard, "PASS" if closed else "FAIL", "FAIL", "ROLLBACK_BINDING", 2)

    if guard == "A2_CONCLUSION_PRECEDENCE":
        document = get_document(material, "admission/result.json")
        derived = reduce_a2_conclusion(document)
        return result(guard, "PASS" if document["conclusion"] == derived else "FAIL", "FAIL", "A2_CONCLUSION_PRECEDENCE", 2)

    if guard == "A2_RESULT_FINGERPRINT":
        document = get_document(material, "admission/result.json")
        actual = document_fingerprint(document, "resultFingerprint", DOMAINS["a2"])["value"]
        return result(guard, "PASS" if document["resultFingerprint"]["value"] == actual else "FAIL", "FAIL", "A2_RESULT_FINGERPRINT", 2)

    fail(f"no collector for {guard}")


def validate_case_documents(material: Material, case: dict[str, Any]) -> None:
    for descriptor in case["sourceMaterial"]["documents"]:
        target = descriptor["target"]
        path = material.docs[target]
        if not path.exists():
            # A raw-file deletion attack intentionally removes the addressed
            # proof. Its companion documents are still validated below.
            continue
        schema_validate(read_json(path), REPO / descriptor["schema"])


def applicable_guards(guard: str) -> list[str]:
    if guard.startswith("A0_"):
        return [item for item in GUARDS if item.startswith("A0_")]
    if guard in {"A1_SLOT_COUNT_PROJECTION", "A1_SLOT_OUTCOME_BINDING", "A1_RESULT_FINGERPRINT"}:
        return ["A1_SLOT_COUNT_PROJECTION", "A1_SLOT_OUTCOME_BINDING", "A1_RESULT_FINGERPRINT"]
    if guard == "A1_PROCESS_MATERIAL_CLOSURE":
        return ["A1_PROCESS_MATERIAL_CLOSURE", "A1_RESULT_FINGERPRINT", "HARNESS_PROOF_COMPLETENESS"]
    if guard == "HARNESS_PROOF_COMPLETENESS":
        return [guard]
    if guard == "PROVIDER_NAMESPACE_COLLISION_REJECTED":
        return [guard]
    if guard == "PIN_LIFECYCLE_BINDING":
        return [guard]
    if guard == "ADMISSION_EVIDENCE_ROOT_CLOSURE":
        return ["ADMISSION_EVIDENCE_ROOT_CLOSURE", "A2_CONCLUSION_PRECEDENCE", "A2_RESULT_FINGERPRINT"]
    if guard == "ROLLBACK_BINDING":
        return ["ROLLBACK_BINDING", "A2_CONCLUSION_PRECEDENCE", "A2_RESULT_FINGERPRINT"]
    if guard == "CODESOURCE_INDEPENDENCE":
        return [guard]
    if guard in {"REVIEW_SIGNATURE_AUTHORITY", "REVIEW_COUNT_CONSISTENCY_REJECTED"}:
        return ["REVIEW_SIGNATURE_AUTHORITY", "REVIEW_COUNT_CONSISTENCY_REJECTED"]
    if guard in {"A2_CONCLUSION_PRECEDENCE", "A2_RESULT_FINGERPRINT"}:
        return ["A2_CONCLUSION_PRECEDENCE", "A2_RESULT_FINGERPRINT"]
    fail(f"no applicable guard set for {guard}")


def assert_baseline_pass(material: Material, guard: str) -> None:
    for applicable in applicable_guards(guard):
        observed = collect(material, applicable)
        if observed["status"] != "PASS":
            fail(f"{guard} baseline is not PASS for {applicable}: {observed}")


def assert_unique_hit(material: Material, target: str) -> None:
    hits: list[str] = []
    not_applicable: list[str] = []
    for guard in GUARDS:
        try:
            observed = collect(material, guard)
        except (KeyError, FileNotFoundError, zipfile.BadZipFile):
            not_applicable.append(guard)
            continue
        if observed["status"] != "PASS":
            hits.append(guard)
    related = {target}
    if target in {"A1_PROCESS_MATERIAL_CLOSURE", "HARNESS_PROOF_COMPLETENESS"}:
        related.update({"A1_PROCESS_MATERIAL_CLOSURE", "HARNESS_PROOF_COMPLETENESS"})
    if target in {"REVIEW_SIGNATURE_AUTHORITY", "REVIEW_COUNT_CONSISTENCY_REJECTED"}:
        related.update({"REVIEW_SIGNATURE_AUTHORITY", "REVIEW_COUNT_CONSISTENCY_REJECTED"})
    if target not in hits or any(hit not in related for hit in hits):
        fail(f"{target} did not hit its declared guard family: {hits}; notApplicable={not_applicable}")


def load_manifest() -> dict[str, Any]:
    load_guard_catalog()
    manifest = read_json(MANIFEST)
    schema_validate(manifest, MANIFEST_SCHEMA)
    expected_catalog = "docs/acceptance/capability-studio/gate-a-wire-v1/semantic-guards/guard-catalog-v1.json"
    if manifest["guardCatalog"] != expected_catalog:
        fail("manifest guardCatalog does not identify the production Guard Catalog")
    primary_guards = [case["guardId"] for case in manifest["cases"][:len(GUARDS)]]
    if primary_guards != GUARDS:
        fail("manifest primary case order is not the frozen 18-Guard order")
    if any(case["caseClass"] != "PRIMARY_GUARD_ATTACK" for case in manifest["cases"][:len(GUARDS)]):
        fail("manifest primary cases must be explicitly classified PRIMARY_GUARD_ATTACK")
    if any(case["caseClass"] != "SUPPLEMENTAL_ATTACK" for case in manifest["cases"][len(GUARDS):]):
        fail("manifest supplemental cases must be explicitly classified SUPPLEMENTAL_ATTACK")
    if any(case["expected"]["admissionTarget"] != TARGETS[case["guardId"]] for case in manifest["cases"]):
        fail("manifest admission target drifted from Guard Catalog")
    if any(case["mutation"]["singleMutation"] is not True for case in manifest["cases"]):
        fail("manifest contains a non-single mutation")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the deterministic Gate A real material attack pack")
    parser.add_argument("--json", action="store_true", dest="json_output", help="emit one JSON result object per case")
    args = parser.parse_args()
    verify_canonicalization_reference()
    verify_admission_pin_duplicate_negative_case()
    manifest = load_manifest()
    admission_verification_time = read_admission_verification_time()
    outputs: list[dict[str, Any]] = []
    for case in manifest["cases"]:
        guard = case["guardId"]
        with tempfile.TemporaryDirectory(prefix="rggatea-", dir="/tmp") as directory:
            material = Material(Path(directory), case, admission_verification_time)
            prepare_material(material, guard)
            validate_case_documents(material, case)
            assert_baseline_pass(material, guard)
            apply_mutation(material, case)
            validate_case_documents(material, case)
            if case["caseId"] in RESIGNED_REVIEW_SEMANTIC_ATTACKS:
                assert_cryptographic_signature_remains_valid(material, case["caseId"])
            assert_unique_hit(material, guard)
            observed = collect(material, guard)
            expected = case["expected"]
            expected_output = {
                "guardId": guard,
                "status": expected["status"],
                "admissionTarget": expected["admissionTarget"],
                "conclusion": expected["conclusion"],
                "reason": expected["reason"],
                "exit": expected["exitCode"],
            }
            if observed != expected_output:
                fail(f"{case['caseId']} observed {observed}, expected {expected_output}")
            outputs.append(observed)
            if args.json_output:
                print(json.dumps(observed, sort_keys=True))
            else:
                print(f"PASS {guard}: status={observed['status']} target={observed['admissionTarget']} conclusion={observed['conclusion']} reason={observed['reason']} exit={observed['exit']}")
    print(json.dumps({
        "status": "PASS",
        "realAttacks": len(outputs),
        "primaryGuardAttacks": len(GUARDS),
        "supplementalAttacks": len(outputs) - len(GUARDS),
        "normalizedReducerVectors": "EXCLUDED",
        "guards": GUARDS,
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError, json.JSONDecodeError, subprocess.SubprocessError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
