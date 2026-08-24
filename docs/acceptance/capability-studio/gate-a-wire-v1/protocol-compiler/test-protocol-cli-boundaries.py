#!/usr/bin/env python3
"""Boundary attacks for the protocol, Bundle, and A1 conformance CLIs.

This suite intentionally remains independent from ``test-protocol-tooling.py``.
It exercises the older CLI/conformance boundary contract while the latter owns
Release Authority Bundle invariants.
"""

from __future__ import annotations

import copy
import hashlib
import json
import os
import pathlib
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile
from typing import Any


HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[4]
PROTOCOL_RELATIVE = pathlib.Path("docs/acceptance/capability-studio/gate-a-wire-v1/protocol-compiler")
SCHEMA_RELATIVE = pathlib.Path("docs/schemas/resource-gateway-capability-studio")
PROFILE_RELATIVE = pathlib.Path("docs/acceptance/capability-studio/gate-a-wire-v1/canonicalization/fingerprint-profile-v1.json")
AUTHORITY_NAME = "gate-a-protocol-authority-v1.json"
CONFORMANCE = "run-a1-conformance.py"


def canonical(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")


def raw_fingerprint(raw: bytes) -> dict[str, str]:
    return {"kind": "RAW_BYTES", "algorithm": "SHA-256", "value": f"sha256:{hashlib.sha256(raw).hexdigest()}"}


def document_fingerprint(domain: bytes, value: dict[str, Any], field: str) -> dict[str, str]:
    material = copy.deepcopy(value)
    material[field] = None
    return {
        "kind": "CANONICAL_DOCUMENT",
        "algorithm": "SHA-256",
        "value": f"sha256:{hashlib.sha256(domain + b"\0" + canonical(material)).hexdigest()}",
    }


def tree_fingerprint(entries: list[dict[str, Any]]) -> dict[str, str]:
    return {
        "kind": "TREE_COMMITMENT",
        "algorithm": "SHA-256",
        "value": f"sha256:{hashlib.sha256(b"RG-CS-GATE-A-CHALLENGE-INPUT-ROOT-v1\0" + canonical(entries)).hexdigest()}",
    }


def load_json(path: pathlib.Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: pathlib.Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(canonical(value) + b"\n")


def run(command: list[str], cwd: pathlib.Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=cwd, stdin=subprocess.DEVNULL, capture_output=True, text=True)


def output(result: subprocess.CompletedProcess[str]) -> str:
    return f"{result.stdout}\n{result.stderr}"


def expect_rejection(label: str, result: subprocess.CompletedProcess[str], token: str) -> None:
    if result.returncode == 0:
        raise SystemExit(f"CLI_BOUNDARY_ATTACK_ACCEPTED:{label}")
    if token not in output(result):
        raise SystemExit(f"CLI_BOUNDARY_ATTACK_WRONG_FAILURE:{label}:{output(result).strip()}")


def assert_no_path(path: pathlib.Path, label: str) -> None:
    if path.exists() or path.is_symlink():
        raise SystemExit(f"CLI_BOUNDARY_SIDE_EFFECT:{label}:{path}")


def copy_repository_shape(root: pathlib.Path) -> pathlib.Path:
    shutil.copytree(REPO / PROTOCOL_RELATIVE, root / PROTOCOL_RELATIVE, ignore=shutil.ignore_patterns("__pycache__"))
    shutil.copytree(REPO / SCHEMA_RELATIVE, root / SCHEMA_RELATIVE)
    target = root / PROFILE_RELATIVE
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(REPO / PROFILE_RELATIVE, target)
    return root


def authority_at(root: pathlib.Path) -> pathlib.Path:
    return root / PROTOCOL_RELATIVE / AUTHORITY_NAME


def write_jar(path: pathlib.Path, role: dict[str, Any], *, compression: bool = False, entry_count: int = 0, special: bool = False, collision: bool = False) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    method = zipfile.ZIP_DEFLATED if compression else zipfile.ZIP_STORED
    with zipfile.ZipFile(path, "w", compression=method) as archive:
        archive.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")
        profile = role.get("profilePath")
        if profile is not None:
            archive.writestr(profile, b"{}\n")
        for index in range(entry_count):
            archive.writestr(f"entries/{index:04d}.txt", b"x")
        if compression:
            archive.writestr("payload.bin", b"0" * 1_048_576)
        elif special:
            info = zipfile.ZipInfo("special")
            info.external_attr = stat.S_IFIFO << 16
            archive.writestr(info, b"special")
        elif collision:
            archive.writestr("collision", b"file")
            directory = zipfile.ZipInfo("collision/")
            directory.external_attr = stat.S_IFDIR << 16
            archive.writestr(directory, b"")
        else:
            archive.writestr("role/marker.txt", role["role"].encode("ascii"))


def fixture_repository(target_slice_id: str = "A1.7") -> tuple[tempfile.TemporaryDirectory[str], pathlib.Path, dict[str, Any], dict[str, pathlib.Path]]:
    temporary = tempfile.TemporaryDirectory(prefix=".gate-a-cli-boundaries-", dir=str(REPO))
    root = copy_repository_shape(pathlib.Path(temporary.name))
    authority = load_json(authority_at(root))
    roles = {role["role"]: role for role in authority["roleContracts"]}
    target_slice = next(item for item in authority["deliverySlices"] if item["sliceId"] == target_slice_id)
    role_by_path = {role["artifactPath"]: role["role"] for role in authority["roleContracts"]}
    closure_roles = sorted({role_by_path[path] for path in target_slice["acceptanceContract"]["requiredArtifactPaths"]})
    paths: dict[str, pathlib.Path] = {}
    for role_name in closure_roles:
        path = root / roles[role_name]["artifactPath"]
        write_jar(path, roles[role_name])
        paths[role_name] = path
    return temporary, root, authority, paths


def compiler_command(root: pathlib.Path, output_root: pathlib.Path | str, *, self_test: bool = False) -> list[str]:
    command = [sys.executable, str(root / PROTOCOL_RELATIVE / "compile-protocol-authority.py"), "--output-root", os.fspath(output_root)]
    if self_test:
        command.append("--self-test")
    return command


def bundle_command(root: pathlib.Path, paths: dict[str, pathlib.Path], output_root: pathlib.Path | str, *, authority: pathlib.Path | str | None = None, target_slice_id: str = "A1.7") -> list[str]:
    command = [
        sys.executable,
        str(root / PROTOCOL_RELATIVE / "compile-role-self-test-fixtures.py"),
        "--authority",
        os.fspath(authority or authority_at(root)),
        "--output-root",
        os.fspath(output_root),
        "--target-slice-id",
        target_slice_id,
    ]
    for role_name in sorted(paths):
        command.extend(("--role-jar", f"{role_name}={paths[role_name]}"))
    return command


def compiler_boundary_tests() -> int:
    attacks = 0
    temporary, root, authority, _ = fixture_repository()
    try:
        result = run(compiler_command(root, root / "compiler-output", self_test=True), root)
        if result.returncode != 0 or "Gate A protocol compiler PASS:" not in result.stdout:
            raise SystemExit(f"CLI_BOUNDARY_HAPPY_COMPILER_FAILED:{output(result)}")
    finally:
        temporary.cleanup()

    temporary, root, authority, _ = fixture_repository()
    try:
        authority_at(root).write_bytes(json.dumps(authority, separators=(",", ":")).encode("utf-8"))
        result = run(compiler_command(root, root / "compiler-output"), root)
        expect_rejection("compiler noncanonical Authority", result, "PROTOCOL_JSON_FORMAT_OR_DUPLICATE_DRIFT")
        assert_no_path(root / "compiler-output", "compiler noncanonical Authority")
        attacks += 1
    finally:
        temporary.cleanup()

    temporary, root, authority, _ = fixture_repository()
    try:
        raw = authority_at(root).read_text(encoding="utf-8").replace('"revision": 1', '"revision": NaN', 1)
        authority_at(root).write_text(raw, encoding="utf-8")
        result = run(compiler_command(root, root / "compiler-output"), root)
        expect_rejection("compiler non-finite Authority JSON", result, "PROTOCOL_JSON_NON_FINITE_NUMBER")
        assert_no_path(root / "compiler-output", "compiler non-finite Authority JSON")
        attacks += 1
    finally:
        temporary.cleanup()
    return attacks


def bundle_boundary_tests() -> int:
    attacks = 0

    temporary, root, authority, paths = fixture_repository()
    try:
        output_root = root / "bundle"
        result = run(bundle_command(root, paths, output_root), root)
        if result.returncode != 0 or "BUNDLE_COMPILED:" not in result.stdout or not (output_root / "release-authority-bundle-v1.json").is_file():
            raise SystemExit(f"CLI_BOUNDARY_HAPPY_BUNDLE_FAILED:{output(result)}")
    finally:
        temporary.cleanup()

    for label, alias, token in (
        ("release noncanonical Authority", lambda root: str(authority_at(root)).replace("/protocol-compiler/", "/protocol-compiler/./"), "BUNDLE_AUTHORITY_PATH_ALIAS"),
    ):
        temporary, root, authority, paths = fixture_repository()
        try:
            output_root = root / "bundle"
            result = run(bundle_command(root, paths, output_root, authority=alias(root)), root)
            expect_rejection(label, result, token)
            assert_no_path(output_root, label)
            attacks += 1
        finally:
            temporary.cleanup()

    for label, operation, token in (("Bundle symlink JAR", "symlink", "BUNDLE_SYMLINK_PRESENT"), ("Bundle hardlink JAR", "hardlink", "BUNDLE_HARDLINK_PRESENT")):
        temporary, root, authority, paths = fixture_repository()
        try:
            output_root = root / "bundle"
            compiled = run(bundle_command(root, paths, output_root), root)
            if compiled.returncode != 0:
                raise SystemExit(f"CLI_BOUNDARY_FIXTURE_COMPILE_FAILED:{label}:{output(compiled)}")
            manifest = load_json(output_root / "release-authority-bundle-v1.json")
            target = output_root / manifest["files"][0]["relativePath"]
            replacement = output_root / "replacement.bin"
            replacement.write_bytes(b"replacement")
            target.unlink()
            if operation == "symlink":
                target.symlink_to(replacement)
            else:
                target.hardlink_to(replacement)
            expected = manifest["bundleRootFingerprint"]["value"]
            result = run([sys.executable, str(root / PROTOCOL_RELATIVE / "verify-release-authority-bundle.py"), "--bundle-root", str(output_root), "--expected-root-fingerprint", expected], root)
            expect_rejection(label, result, token)
            attacks += 1
        finally:
            temporary.cleanup()

    for label, kwargs, token in (
        ("Bundle entry capacity", {"entry_count": 513}, "BUNDLE_ROLE_JAR_ENTRY_COUNT_LIMIT"),
        ("Bundle compression capacity", {"compression": True}, "BUNDLE_ROLE_JAR_COMPRESSION_RATIO_LIMIT"),
        ("Bundle logical ZIP path collision", {"collision": True}, "BUNDLE_ROLE_JAR_ENTRY_PATH_COLLISION"),
        ("Bundle special ZIP entry", {"special": True}, "BUNDLE_ROLE_JAR_ENTRY_KIND_INVALID"),
    ):
        temporary, root, authority, paths = fixture_repository()
        try:
            candidate = paths["IMPLEMENTATION_CANDIDATE"]
            write_jar(candidate, {**next(role for role in authority["roleContracts"] if role["role"] == "IMPLEMENTATION_CANDIDATE"), **kwargs}, **kwargs)
            result = run(bundle_command(root, paths, root / "bundle"), root)
            expect_rejection(label, result, token)
            assert_no_path(root / "bundle", label)
            attacks += 1
        finally:
            temporary.cleanup()

    temporary, root, authority, paths = fixture_repository()
    try:
        paths["IMPLEMENTATION_CANDIDATE"].write_bytes(b"not-a-jar")
        output_root = root / "bundle"
        result = run(bundle_command(root, paths, output_root), root)
        expect_rejection("malformed profileless Candidate", result, "BUNDLE_ROLE_JAR_MALFORMED")
        assert_no_path(output_root, "malformed profileless Candidate")
        attacks += 1
    finally:
        temporary.cleanup()

    temporary, root, authority, paths = fixture_repository()
    try:
        output_root = root / "collision"
        first = run(bundle_command(root, paths, output_root), root)
        if first.returncode != 0:
            raise SystemExit(f"CLI_BOUNDARY_COLLISION_SETUP_FAILED:{output(first)}")
        second = run(bundle_command(root, paths, output_root), root)
        expect_rejection("Bundle create-new output collision", second, "BUNDLE_OUTPUT_ROOT_EXISTS")
        attacks += 1
    finally:
        temporary.cleanup()

    temporary, root, authority, paths = fixture_repository()
    try:
        output_root = root / "rejected"
        command = bundle_command(root, paths, f"{output_root}/")
        result = run(command, root)
        expect_rejection("Bundle trailing output alias", result, "BUNDLE_OUTPUT_ROOT_PATH_ALIAS")
        assert_no_path(output_root, "Bundle trailing output alias")
        attacks += 1
    finally:
        temporary.cleanup()
    return attacks


def conformance_command(root: pathlib.Path, paths: dict[str, pathlib.Path], pin: pathlib.Path, input_root: pathlib.Path, output_root: pathlib.Path | str, *, authority: pathlib.Path | str | None = None) -> list[str]:
    return [
        sys.executable,
        str(root / PROTOCOL_RELATIVE / CONFORMANCE),
        "--authority", os.fspath(authority or authority_at(root)),
        "--challenge-pin", str(pin),
        "--challenge-input-root", str(input_root),
        "--output-root", os.fspath(output_root),
        "--candidate", str(paths["IMPLEMENTATION_CANDIDATE"]),
        "--provider", str(paths["TCK_PROVIDER"]),
        "--verifier", str(paths["INDEPENDENT_VERIFIER"]),
        "--harness", str(paths["CONFORMANCE_HARNESS"]),
    ]


def prepare_conformance(root: pathlib.Path, authority: dict[str, Any], paths: dict[str, pathlib.Path]) -> tuple[pathlib.Path, pathlib.Path]:
    input_root = root / "challenge-input"
    inputs = input_root / "inputs"
    inputs.mkdir(parents=True)
    payloads = {"formal-evidence-bundle.json": b"{}\n", "formal-evidence-run-manifest.json": b"{}\n"}
    entries = [{"relativePath": name, "kind": "FILE", "byteLength": len(raw), "rawFingerprint": raw_fingerprint(raw)} for name, raw in sorted(payloads.items())]
    tree = {"rootKind": "GATE_A_CHALLENGE_INPUT", "entries": entries, "rootFingerprint": tree_fingerprint(entries)}
    write_json(inputs / "challenge-root.tree", tree)
    for name, raw in payloads.items():
        (inputs / name).write_bytes(raw)

    pin: dict[str, Any] = {
        "schemaVersion": "capability-studio.gate-a-challenge-trust-pin.v1",
        "gateId": "GATE-A", "gateRevision": 1, "createdAt": "2026-08-22T00:00:00Z", "semanticVerificationTime": "2026-08-22T00:00:01Z", "allowedGateRevision": 1,
    }
    zero = lambda index: raw_fingerprint(f"pin-{index}".encode("ascii"))
    for field, index in (
        ("expectedDesignRawFingerprint", 1), ("expectedAdmissionProfileRawFingerprint", 2), ("expectedGateVerifierRawFingerprint", 3),
        ("expectedSchemaSetManifestRawFingerprint", 4), ("expectedTckDefinitionRawFingerprint", 5), ("expectedCandidateSpiClassRawFingerprint", 6),
        ("expectedReviewerArtifactRawFingerprint", 7), ("expectedReviewerTrustPolicyRawFingerprint", 8), ("expectedReviewerRevocationSnapshotRawFingerprint", 9),
        ("expectedChallengeSandboxProfileRawFingerprint", 10),
    ):
        pin[field] = zero(index)
    pin["expectedProtocolAuthorityRawFingerprint"] = raw_fingerprint(authority_at(root).read_bytes())
    pin["expectedChallengeInputRootFingerprint"] = tree["rootFingerprint"]
    pin["expectedImplementationCandidateRawFingerprint"] = raw_fingerprint(paths["IMPLEMENTATION_CANDIDATE"].read_bytes())
    pin["expectedTckProviderRawFingerprint"] = raw_fingerprint(paths["TCK_PROVIDER"].read_bytes())
    verifier_raw = paths["INDEPENDENT_VERIFIER"].read_bytes()
    harness_raw = paths["CONFORMANCE_HARNESS"].read_bytes()
    pin["expectedIndependentVerifierRawFingerprint"] = raw_fingerprint(verifier_raw)
    pin["expectedConformanceHarnessRawFingerprint"] = raw_fingerprint(harness_raw)
    pin["expectedReplayProfileRawFingerprint"] = raw_fingerprint(b"{}\n")
    pin["expectedHarnessProfileRawFingerprint"] = raw_fingerprint(b"{}\n")
    pin["expectedCandidateSpiArtifactRawFingerprint"] = pin["expectedImplementationCandidateRawFingerprint"]
    pin["challengeTrustPinFingerprint"] = document_fingerprint(b"RG-CS-GATE-A-CHALLENGE-TRUST-PIN-v1", pin, "challengeTrustPinFingerprint")
    pin_path = root / "challenge-pin.json"
    write_json(pin_path, pin)
    return pin_path, input_root


def conformance_boundary_tests() -> int:
    attacks = 0

    temporary, root, authority, paths = fixture_repository()
    try:
        pin, input_root = prepare_conformance(root, authority, paths)
        output_root = root / "run-material"
        result = run(conformance_command(root, paths, pin, input_root, output_root), root)
        expect_rejection("complete conformance preflight exact pending", result, "A1_CONFORMANCE_RUNNER_IMPLEMENTATION_PENDING")
        assert_no_path(output_root, "complete conformance preflight exact pending")
    finally:
        temporary.cleanup()

    for label, alias, token in (
        ("conformance noncanonical Authority", lambda root: str(authority_at(root)).replace("/protocol-compiler/", "/protocol-compiler/./"), "A1_CONFORMANCE_AUTHORITY_PATH_ALIAS"),
        ("conformance double-root output alias", lambda root: "//" + str(root / "run-material").lstrip("/"), "A1_CONFORMANCE_OUTPUT_PATH_ALIAS"),
        ("conformance trailing output alias", lambda root: str(root / "run-material") + "/", "A1_CONFORMANCE_OUTPUT_PATH_ALIAS"),
    ):
        temporary, root, authority, paths = fixture_repository()
        try:
            pin, input_root = prepare_conformance(root, authority, paths)
            output_root = alias(root)
            command = conformance_command(root, paths, pin, input_root, output_root, authority=output_root if "Authority" in label else None)
            result = run(command, root)
            expect_rejection(label, result, token)
            assert_no_path(root / "run-material", label)
            attacks += 1
        finally:
            temporary.cleanup()

    temporary, root, authority, paths = fixture_repository()
    try:
        pin, input_root = prepare_conformance(root, authority, paths)
        pin_value = load_json(pin)
        pin_value["challengeTrustPinFingerprint"]["value"] = "sha256:" + "0" * 64
        write_json(pin, pin_value)
        result = run(conformance_command(root, paths, pin, input_root, root / "run-material"), root)
        expect_rejection("stale Challenge Pin self fingerprint", result, "A1_CONFORMANCE_CHALLENGE_PIN_SELF_FINGERPRINT_DRIFT")
        assert_no_path(root / "run-material", "stale Challenge Pin self fingerprint")
        attacks += 1
    finally:
        temporary.cleanup()

    for label, mutation, token in (
        ("unknown empty input directory", lambda inputs: (inputs / "empty").mkdir(), "A1_CONFORMANCE_INPUT_TREE_ENTRY_SET_DRIFT"),
        ("unknown input file", lambda inputs: (inputs / "unexpected.txt").write_bytes(b"unexpected"), "A1_CONFORMANCE_INPUT_TREE_ENTRY_SET_DRIFT"),
    ):
        temporary, root, authority, paths = fixture_repository()
        try:
            pin, input_root = prepare_conformance(root, authority, paths)
            mutation(input_root / "inputs")
            result = run(conformance_command(root, paths, pin, input_root, root / "run-material"), root)
            expect_rejection(label, result, token)
            assert_no_path(root / "run-material", label)
            attacks += 1
        finally:
            temporary.cleanup()

    temporary, root, authority, paths = fixture_repository()
    try:
        pin, input_root = prepare_conformance(root, authority, paths)
        output_root = root / "existing-output"
        output_root.mkdir()
        result = run(conformance_command(root, paths, pin, input_root, output_root), root)
        expect_rejection("preexisting conformance output root", result, "A1_CONFORMANCE_OUTPUT_ROOT_MUST_BE_CREATE_NEW")
        attacks += 1
    finally:
        temporary.cleanup()

    temporary, root, authority, paths = fixture_repository()
    try:
        pin, input_root = prepare_conformance(root, authority, paths)
        paths["IMPLEMENTATION_CANDIDATE"].write_bytes(b"not-a-jar")
        result = run(conformance_command(root, paths, pin, input_root, root / "run-material"), root)
        expect_rejection("malformed profileless Candidate", result, "A1_CONFORMANCE_ROLE_JAR_MALFORMED")
        assert_no_path(root / "run-material", "malformed profileless Candidate")
        attacks += 1
    finally:
        temporary.cleanup()

    for label, operation, token in (
        ("conformance symlink JAR", "symlink", "A1_CONFORMANCE_ARTIFACT_OPEN_FAILED"),
        ("conformance hardlink JAR", "hardlink", "A1_CONFORMANCE_ARTIFACT_NLINK_NOT_ONE"),
    ):
        temporary, root, authority, paths = fixture_repository()
        try:
            pin, input_root = prepare_conformance(root, authority, paths)
            candidate = paths["IMPLEMENTATION_CANDIDATE"]
            replacement = candidate.with_name("candidate-replacement.jar")
            replacement.write_bytes(candidate.read_bytes())
            candidate.unlink()
            if operation == "symlink":
                candidate.symlink_to(replacement)
            else:
                candidate.hardlink_to(replacement)
            result = run(conformance_command(root, paths, pin, input_root, root / "run-material"), root)
            expect_rejection(label, result, token)
            assert_no_path(root / "run-material", label)
            attacks += 1
        finally:
            temporary.cleanup()

    temporary, root, authority, paths = fixture_repository()
    try:
        pin, input_root = prepare_conformance(root, authority, paths)
        candidate_role = next(role for role in authority["roleContracts"] if role["role"] == "IMPLEMENTATION_CANDIDATE")
        write_jar(paths["IMPLEMENTATION_CANDIDATE"], candidate_role, entry_count=candidate_role["artifactLimits"]["maxZipEntries"] + 1)
        result = run(conformance_command(root, paths, pin, input_root, root / "run-material"), root)
        expect_rejection("conformance entry capacity", result, "A1_CONFORMANCE_ROLE_JAR_ENTRY_COUNT_LIMIT")
        attacks += 1
    finally:
        temporary.cleanup()

    for label, kwargs, token in (
        ("conformance compression capacity", {"compression": True}, "A1_CONFORMANCE_ROLE_JAR_COMPRESSION_RATIO_LIMIT"),
        ("conformance logical ZIP path collision", {"collision": True}, "A1_CONFORMANCE_ROLE_JAR_ENTRY_PATH_COLLISION"),
        ("conformance special ZIP entry", {"special": True}, "A1_CONFORMANCE_ROLE_JAR_ENTRY_KIND_INVALID"),
    ):
        temporary, root, authority, paths = fixture_repository()
        try:
            pin, input_root = prepare_conformance(root, authority, paths)
            candidate_role = next(role for role in authority["roleContracts"] if role["role"] == "IMPLEMENTATION_CANDIDATE")
            write_jar(paths["IMPLEMENTATION_CANDIDATE"], candidate_role, **kwargs)
            result = run(conformance_command(root, paths, pin, input_root, root / "run-material"), root)
            expect_rejection(label, result, token)
            attacks += 1
        finally:
            temporary.cleanup()

    temporary, root, authority, paths = fixture_repository()
    try:
        pin, input_root = prepare_conformance(root, authority, paths)
        payload = input_root / "inputs" / "formal-evidence-bundle.json"
        payload.write_bytes(b"x" * (8 * 1024 * 1024 + 1))
        result = run(conformance_command(root, paths, pin, input_root, root / "run-material"), root)
        expect_rejection("conformance file capacity", result, "A1_CONFORMANCE_INPUT_FILE_SIZE_LIMIT")
        attacks += 1
    finally:
        temporary.cleanup()

    temporary, root, authority, paths = fixture_repository()
    try:
        raw = authority_at(root).read_text(encoding="utf-8").replace('"revision": 1', '"revision": NaN', 1)
        authority_at(root).write_text(raw, encoding="utf-8")
        pin, input_root = prepare_conformance(root, authority, paths)
        result = run(conformance_command(root, paths, pin, input_root, root / "run-material"), root)
        expect_rejection("conformance non-finite Authority JSON", result, "A1_CONFORMANCE_JSON_NON_FINITE")
        assert_no_path(root / "run-material", "conformance non-finite Authority JSON")
        attacks += 1
    finally:
        temporary.cleanup()
    return attacks


def main() -> None:
    compiler_attacks = compiler_boundary_tests()
    bundle_attacks = bundle_boundary_tests()
    conformance_attacks = conformance_boundary_tests()
    total = compiler_attacks + bundle_attacks + conformance_attacks
    print(f"Gate A protocol CLI/conformance boundary PASS: {total} attacks rejected ({compiler_attacks} compiler, {bundle_attacks} Bundle, {conformance_attacks} conformance); happy paths verified.")


if __name__ == "__main__":
    main()
