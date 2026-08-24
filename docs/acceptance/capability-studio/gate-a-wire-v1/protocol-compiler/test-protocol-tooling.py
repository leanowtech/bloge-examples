#!/usr/bin/env python3
"""High-value boundary attacks for the sealed Release Authority Bundle."""

from __future__ import annotations

import argparse
import copy
import hashlib
import importlib.util
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from typing import Any


HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[4]
PROTOCOL_RELATIVE = pathlib.Path("docs/acceptance/capability-studio/gate-a-wire-v1/protocol-compiler")
SCHEMA_RELATIVE = pathlib.Path("docs/schemas/resource-gateway-capability-studio")
FINGERPRINT_PROFILE_RELATIVE = pathlib.Path("docs/acceptance/capability-studio/gate-a-wire-v1/canonicalization/fingerprint-profile-v1.json")
AUTHORITY_NAME = "gate-a-protocol-authority-v1.json"

spec = importlib.util.spec_from_file_location("release_authority_bundle", HERE / "release_authority_bundle.py")
if spec is None or spec.loader is None:
    raise SystemExit("TOOLING_BUNDLE_MODULE_UNAVAILABLE")
bundle = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = bundle
spec.loader.exec_module(bundle)


def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"TOOLING_JSON_DUPLICATE_MEMBER:{key}")
        result[key] = value
    return result


def load_json(path: pathlib.Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=reject_duplicates)


def canonical(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")


def write_json(path: pathlib.Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(canonical(value) + b"\n")


def copy_repository_shape(root: pathlib.Path) -> pathlib.Path:
    protocol_source = REPO / PROTOCOL_RELATIVE
    schema_source = REPO / SCHEMA_RELATIVE
    protocol_target = root / PROTOCOL_RELATIVE
    schema_target = root / SCHEMA_RELATIVE
    protocol_target.parent.mkdir(parents=True, exist_ok=True)
    schema_target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(protocol_source, protocol_target, ignore=shutil.ignore_patterns("__pycache__"))
    shutil.copytree(schema_source, schema_target)
    profile_target = root / FINGERPRINT_PROFILE_RELATIVE
    profile_target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(REPO / FINGERPRINT_PROFILE_RELATIVE, profile_target)
    return root


def write_jar(path: pathlib.Path, role: dict[str, Any], *, compression_attack: bool = False) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED if compression_attack else zipfile.ZIP_STORED) as archive:
        archive.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")
        if role.get("profilePath") is not None:
            archive.writestr(role["profilePath"], b"{}\n")
        if compression_attack:
            archive.writestr("payload.bin", b"0" * 1_048_576)
        else:
            archive.writestr("role/marker.txt", role["role"].encode("ascii"))


def fixture_repository(target_slice_id: str = "A1.7") -> tuple[tempfile.TemporaryDirectory[str], pathlib.Path, dict[str, Any], dict[str, pathlib.Path]]:
    temporary = tempfile.TemporaryDirectory(prefix=".gate-a-bundle-tooling-", dir=str(REPO))
    root = copy_repository_shape(pathlib.Path(temporary.name))
    authority = load_json(root / PROTOCOL_RELATIVE / AUTHORITY_NAME)
    roles = {role["role"]: role for role in authority["roleContracts"]}
    target_slice = next(item for item in authority["deliverySlices"] if item["sliceId"] == target_slice_id)
    role_by_path = {role["artifactPath"]: role["role"] for role in authority["roleContracts"]}
    implementation_roles = target_slice["implementationRoles"]
    closure_roles = sorted({role_by_path[path] for path in target_slice["acceptanceContract"]["requiredArtifactPaths"]})
    paths: dict[str, pathlib.Path] = {}
    for role_name in closure_roles:
        path = root / roles[role_name]["artifactPath"]
        write_jar(path, roles[role_name])
        paths[role_name] = path
    return temporary, root, authority, paths


def load_bundle_module(root: pathlib.Path) -> Any:
    path = root / PROTOCOL_RELATIVE / "release_authority_bundle.py"
    name = f"release_authority_bundle_fixture_{hashlib.sha256(os.fspath(root).encode()).hexdigest()}"
    module_spec = importlib.util.spec_from_file_location(name, path)
    if module_spec is None or module_spec.loader is None:
        raise SystemExit("TOOLING_FIXTURE_BUNDLE_MODULE_UNAVAILABLE")
    module = importlib.util.module_from_spec(module_spec)
    sys.modules[name] = module
    module_spec.loader.exec_module(module)
    return module


def run_cli(command: list[str], cwd: pathlib.Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=cwd, capture_output=True, text=True)


def compiler_command(root: pathlib.Path, paths: dict[str, pathlib.Path], output_root: pathlib.Path, target_slice_id: str = "A1.7") -> list[str]:
    command = [
        sys.executable,
        str(root / PROTOCOL_RELATIVE / "compile-role-self-test-fixtures.py"),
        "--authority",
        str(root / PROTOCOL_RELATIVE / AUTHORITY_NAME),
        "--output-root",
        str(output_root),
        "--target-slice-id",
        target_slice_id,
    ]
    for role_name in sorted(paths):
        command.extend(("--role-jar", f"{role_name}={paths[role_name]}"))
    return command


def root_from(result: subprocess.CompletedProcess[str]) -> str:
    match = re.search(r"root=(sha256:[0-9a-f]{64})", result.stdout)
    if result.returncode != 0 or match is None:
        raise SystemExit(f"TOOLING_COMPILE_FAILED:{result.stdout}\n{result.stderr}")
    return match.group(1)


def verifier_command(root: pathlib.Path, output: pathlib.Path, expected: str) -> list[str]:
    return [
        sys.executable,
        str(root / PROTOCOL_RELATIVE / "verify-release-authority-bundle.py"),
        "--bundle-root",
        str(output),
        "--expected-root-fingerprint",
        expected,
    ]


def release_command(root: pathlib.Path, output: pathlib.Path, expected: str) -> list[str]:
    return [
        sys.executable,
        str(root / PROTOCOL_RELATIVE / "run-a1-release-gate.py"),
        "--bundle-root",
        str(output),
        "--expected-bundle-root-fingerprint",
        expected,
    ]


def expect_rejection(label: str, result: subprocess.CompletedProcess[str], token: str) -> None:
    output = f"{result.stdout}\n{result.stderr}"
    if result.returncode == 0:
        raise SystemExit(f"TOOLING_ATTACK_ACCEPTED:{label}")
    if token not in output:
        raise SystemExit(f"TOOLING_ATTACK_WRONG_FAILURE:{label}:{output.strip()}")


def mutate_manifest(root: pathlib.Path, output: pathlib.Path, mutate: Any) -> tuple[dict[str, Any], str]:
    manifest_path = output / bundle.ROOT_MANIFEST
    manifest = load_json(manifest_path)
    mutate(manifest)
    manifest["bundleRootFingerprint"]["value"] = None
    expected = bundle.committed(bundle.ROOT_DOMAIN, manifest)
    manifest["bundleRootFingerprint"]["value"] = expected
    write_json(manifest_path, manifest)
    return manifest, expected


def update_entry_commitments(output: pathlib.Path, manifest: dict[str, Any], relative: str) -> None:
    path = output / relative
    entry = next(item for item in manifest["files"] if item["relativePath"] == relative)
    raw = path.read_bytes()
    entry["byteLength"] = len(raw)
    entry["rawFingerprint"] = bundle.raw_fingerprint(raw)
    manifest["closedTreeFingerprint"] = bundle._tree_commitment(manifest["files"], bundle.TREE_DOMAIN)


def main() -> None:
    argparse.ArgumentParser().parse_args()
    attacks = 0
    with tempfile.TemporaryDirectory(prefix=".gate-a-bundle-tooling-suite-", dir=str(REPO)):
        temporary, root, authority, paths = fixture_repository()
        try:
            output = root / "bundle"
            compiled = run_cli(compiler_command(root, paths, output), root)
            expected = root_from(compiled)
            if "BUNDLE_COMPILED:" not in compiled.stdout or not (output / bundle.ROOT_MANIFEST).is_file():
                raise SystemExit("TOOLING_HAPPY_COMPILE_NOT_SEALED")
            verified = run_cli(verifier_command(root, output, expected), root)
            if verified.returncode != 0 or "BUNDLE_VERIFIED:" not in verified.stdout:
                raise SystemExit(f"TOOLING_HAPPY_VERIFY_FAILED:{verified.stdout}:{verified.stderr}")
            snapshot = bundle.verify_bundle(output, expected)
            authority_bytes = snapshot.bytes_for("authority/protocol-authority.json")
            if authority_bytes != (root / PROTOCOL_RELATIVE / AUTHORITY_NAME).read_bytes():
                raise SystemExit("TOOLING_BUNDLE_SNAPSHOT_BYTES_DRIFT")
            for view in snapshot.manifest["roleViews"]:
                oracle_path = f"parent-private/oracles/{view['role']}.json"
                receipt = json.loads(snapshot.bytes_for(oracle_path))
                if (
                    receipt["roleViewFingerprint"]["value"] != view["roleViewFingerprint"]
                    or receipt["inputTreeFingerprint"]["value"] != view["inputTreeFingerprint"]
                ):
                    raise SystemExit(f"TOOLING_ORACLE_VIEW_BINDING_DRIFT:{view['role']}")
            release = run_cli(release_command(root, output, expected), root)
            expect_rejection(
                "draft dependency cannot release",
                release,
                "A1_RELEASE_DEPENDENCY_AUTHORITY_NOT_ACTIVE:DRAFT_UNPINNED",
            )
            attacks += 1

            authority_path = root / PROTOCOL_RELATIVE / AUTHORITY_NAME
            authority_path.write_bytes(b"workspace-authority-must-not-be-reopened\n")
            release_after_workspace_drift = run_cli(release_command(root, output, expected), root)
            expect_rejection(
                "release reopens workspace Authority",
                release_after_workspace_drift,
                "A1_RELEASE_DEPENDENCY_AUTHORITY_NOT_ACTIVE:DRAFT_UNPINNED",
            )
            attacks += 1
        finally:
            temporary.cleanup()

        temporary, root, authority, paths = fixture_repository("A1.1")
        try:
            target = next(item for item in authority["deliverySlices"] if item["sliceId"] == "A1.1")
            target["implementationRoles"] = ["IMPLEMENTATION_CANDIDATE", "TCK_PROVIDER"]
            try:
                bundle._target_slice_and_closure(authority, "A1.1")
            except bundle.BundleError as error:
                if "BUNDLE_IMPLEMENTATION_ROLES_NOT_IN_ARTIFACT_CLOSURE" not in str(error):
                    raise SystemExit(f"TOOLING_IMPLEMENTATION_ROLE_CLOSURE_ATTACK_WRONG_FAILURE:{error}") from error
            else:
                raise SystemExit("TOOLING_IMPLEMENTATION_ROLE_CLOSURE_ATTACK_ACCEPTED")
            attacks += 1
        finally:
            temporary.cleanup()

        temporary, root, authority, paths = fixture_repository("A1.1")
        try:
            output = root / "bundle"
            bundle_fp = root_from(run_cli(compiler_command(root, paths, output, target_slice_id="A1.1"), root))
            release = run_cli(release_command(root, output, bundle_fp), root)
            expect_rejection(
                "A1.1 Bundle cannot enter A1 release",
                release,
                "A1_RELEASE_TARGET_SLICE_MISMATCH",
            )
            attacks += 1
            _, rebound_root = mutate_manifest(root, output, lambda value: value.__setitem__("targetSliceId", "A1.2"))
            expect_rejection("A1.1 Bundle rebound to A1.2", run_cli(verifier_command(root, output, rebound_root), root), "BUNDLE_ROLE_VIEW_SET_DRIFT")
            attacks += 1
        finally:
            temporary.cleanup()

        temporary, root, authority, paths = fixture_repository("A1.2")
        try:
            output = root / "bundle"
            root_from(run_cli(compiler_command(root, paths, output, target_slice_id="A1.2"), root))
            _, rebound_root = mutate_manifest(root, output, lambda value: value.__setitem__("targetSliceId", "A1.1"))
            expect_rejection("A1.2 Bundle rebound to A1.1", run_cli(verifier_command(root, output, rebound_root), root), "BUNDLE_ROLE_VIEW_SET_DRIFT")
            attacks += 1
        finally:
            temporary.cleanup()

        temporary, root, authority, paths = fixture_repository()
        try:
            fixture_bundle = load_bundle_module(root)
            original_read_stable = fixture_bundle.read_stable
            profile_reads = 0

            def drifting_read(path: pathlib.Path, limit: int, code: str) -> bytes:
                nonlocal profile_reads
                raw = original_read_stable(path, limit, code)
                if pathlib.Path(path) == fixture_bundle.FINGERPRINT_PROFILE:
                    profile_reads += 1
                    if profile_reads > 1:
                        return raw + b"source-generation-drift"
                return raw

            fixture_bundle.read_stable = drifting_read
            output = root / "drifted-bundle"
            try:
                fixture_bundle.compile_bundle(root / PROTOCOL_RELATIVE / AUTHORITY_NAME, output, paths, "A1.7")
            except fixture_bundle.BundleError as error:
                if "BUNDLE_SOURCE_SNAPSHOT_DRIFT" not in str(error):
                    raise SystemExit(f"TOOLING_ATTACK_WRONG_FAILURE:source snapshot drift:{error}") from error
            else:
                raise SystemExit("TOOLING_ATTACK_ACCEPTED:source snapshot drift")
            if output.exists():
                raise SystemExit("TOOLING_SOURCE_DRIFT_CREATED_OUTPUT")
            attacks += 1
        finally:
            temporary.cleanup()

        temporary, root, authority, paths = fixture_repository()
        try:
            stale_projection = root / PROTOCOL_RELATIVE / "compiled" / authority["compilerContract"]["projectionPlan"][0]["outputPath"]
            stale_projection.write_bytes(b"stale-workspace-projection\n")
            output = root / "bundle"
            expected = root_from(run_cli(compiler_command(root, paths, output), root))
            snapshot = bundle.verify_bundle(output, expected)
            bundled_projection = snapshot.bytes_for(f"projections/{stale_projection.name}")
            if bundled_projection == stale_projection.read_bytes() or b"stale-workspace-projection" in bundled_projection:
                raise SystemExit("TOOLING_STALE_WORKSPACE_PROJECTION_CONSUMED")
            attacks += 1
        finally:
            temporary.cleanup()

        temporary, root, authority, paths = fixture_repository()
        try:
            output = root / "bundle"
            expected = root_from(run_cli(compiler_command(root, paths, output), root))
            manifest = load_json(output / bundle.ROOT_MANIFEST)
            manifest["bundleRootFingerprint"]["value"] = "sha256:" + "0" * 64
            write_json(output / bundle.ROOT_MANIFEST, manifest)
            expect_rejection("seal self drift", run_cli(verifier_command(root, output, expected), root), "BUNDLE_EXTERNAL_ROOT_PIN_MISMATCH")
            attacks += 1
        finally:
            temporary.cleanup()

        temporary, root, authority, paths = fixture_repository()
        try:
            output = root / "bundle"
            old_root = root_from(run_cli(compiler_command(root, paths, output), root))
            manifest = load_json(output / bundle.ROOT_MANIFEST)
            relative = manifest["files"][0]["relativePath"]
            (output / relative).write_bytes((output / relative).read_bytes() + b"drift")
            new_root = mutate_manifest(root, output, lambda value: update_entry_commitments(output, value, relative))
            expect_rejection("entry rebound with external root unchanged", run_cli(verifier_command(root, output, old_root), root), "BUNDLE_EXTERNAL_ROOT_PIN_MISMATCH")
            if new_root[1] == old_root:
                raise SystemExit("TOOLING_ENTRY_REBOUND_ROOT_DID_NOT_CHANGE")
            attacks += 1
        finally:
            temporary.cleanup()

        temporary, root, authority, paths = fixture_repository()
        try:
            output = root / "bundle"
            expected = root_from(run_cli(compiler_command(root, paths, output), root))
            manifest = load_json(output / bundle.ROOT_MANIFEST)
            missing = manifest["files"][0]["relativePath"]
            (output / missing).unlink()
            expect_rejection("missing file", run_cli(verifier_command(root, output, expected), root), "BUNDLE_PHYSICAL_CLOSED_SET_DRIFT")
            attacks += 1
        finally:
            temporary.cleanup()

        temporary, root, authority, paths = fixture_repository()
        try:
            output = root / "bundle"
            expected = root_from(run_cli(compiler_command(root, paths, output), root))
            (output / "unexpected.txt").write_bytes(b"unexpected")
            expect_rejection("unknown file", run_cli(verifier_command(root, output, expected), root), "BUNDLE_PHYSICAL_CLOSED_SET_DRIFT")
            attacks += 1
        finally:
            temporary.cleanup()

        for label, mutate in (
            ("role view references oracle", lambda value: value["roleViews"][0]["visibleFileRefs"].__setitem__(0, value["oracleCompartment"]["fileRefs"][0])),
            ("oracle is visible through parent-private path", lambda value: value["roleViews"][0]["visibleFileRefs"].__setitem__(0, value["oracleCompartment"]["fileRefs"][0])),
        ):
            temporary, root, authority, paths = fixture_repository()
            try:
                output = root / "bundle"
                root_from(run_cli(compiler_command(root, paths, output), root))
                _, rebound_root = mutate_manifest(root, output, mutate)
                expect_rejection(label, run_cli(verifier_command(root, output, rebound_root), root), "BUNDLE_ROLE_VIEW_ORACLE_REFERENCE")
                attacks += 1
            finally:
                temporary.cleanup()

        for label, fingerprint_field, token in (
            ("role view fingerprint rebound", "roleViewFingerprint", "BUNDLE_ROLE_VIEW_FINGERPRINT_DRIFT"),
            ("role input tree rebound", "inputTreeFingerprint", "BUNDLE_ROLE_INPUT_TREE_DRIFT"),
        ):
            temporary, root, authority, paths = fixture_repository()
            try:
                output = root / "bundle"
                root_from(run_cli(compiler_command(root, paths, output), root))
                manifest = load_json(output / bundle.ROOT_MANIFEST)
                view = manifest["roleViews"][0]
                view_path = view["manifestPath"]
                view_document = load_json(output / view_path)
                replacement = "sha256:" + "1" * 64
                view_document[fingerprint_field] = replacement
                rebound_view_fingerprint = view_document["roleViewFingerprint"]
                if fingerprint_field == "inputTreeFingerprint":
                    view_material = dict(view_document)
                    view_material["roleViewFingerprint"] = None
                    rebound_view_fingerprint = bundle.committed(bundle.VIEW_DOMAIN, view_material)
                    view_document["roleViewFingerprint"] = rebound_view_fingerprint
                write_json(output / view_path, view_document)

                def rebind_view(value: dict[str, Any]) -> None:
                    value["roleViews"][0][fingerprint_field] = replacement
                    value["roleViews"][0]["roleViewFingerprint"] = rebound_view_fingerprint
                    update_entry_commitments(output, value, view_path)

                _, rebound_root = mutate_manifest(root, output, rebind_view)
                expect_rejection(label, run_cli(verifier_command(root, output, rebound_root), root), token)
                attacks += 1
            finally:
                temporary.cleanup()

        temporary, root, authority, paths = fixture_repository()
        try:
            output = root / "bundle"
            root_from(run_cli(compiler_command(root, paths, output), root))
            schema_path = f"schemas/{bundle.BUNDLE_SCHEMA_NAME}"
            schema = load_json(output / schema_path)
            schema["title"] = "attacker-controlled bundle schema"
            write_json(output / schema_path, schema)

            def rebind_schema(value: dict[str, Any]) -> None:
                update_entry_commitments(output, value, schema_path)

            _, rebound_root = mutate_manifest(root, output, rebind_schema)
            expect_rejection(
                "bundled schema self interpretation",
                run_cli(verifier_command(root, output, rebound_root), root),
                "BUNDLE_SCHEMA_NOT_SUPPORTED_BY_VERIFIER",
            )
            attacks += 1
        finally:
            temporary.cleanup()

        for label, field, token in (
            ("projection aggregate rebound", "projectionSetFingerprint", "BUNDLE_PROJECTION_SET_COMMITMENT_DRIFT"),
            ("schema aggregate rebound", "schemaSetFingerprint", "BUNDLE_SCHEMA_SET_COMMITMENT_DRIFT"),
            ("dependency authority rebound", "dependencyAuthorityRawFingerprint", "BUNDLE_DEPENDENCY_AUTHORITY_COMMITMENT_DRIFT"),
            ("toolchain policy rebound", "toolchainPolicyFingerprint", "BUNDLE_TOOLCHAIN_POLICY_COMMITMENT_DRIFT"),
        ):
            temporary, root, authority, paths = fixture_repository()
            try:
                output = root / "bundle"
                root_from(run_cli(compiler_command(root, paths, output), root))
                _, rebound_root = mutate_manifest(
                    root,
                    output,
                    lambda value, field=field: value.__setitem__(field, "sha256:" + "2" * 64),
                )
                expect_rejection(label, run_cli(verifier_command(root, output, rebound_root), root), token)
                attacks += 1
            finally:
                temporary.cleanup()

        temporary, root, authority, paths = fixture_repository()
        try:
            output = root / "bundle"
            root_from(run_cli(compiler_command(root, paths, output), root))
            manifest = load_json(output / bundle.ROOT_MANIFEST)
            view = manifest["roleViews"][0]
            role_name = view["role"]
            oracle_path = f"parent-private/oracles/{role_name}.json"
            receipt = load_json(output / oracle_path)
            receipt["roleViewFingerprint"]["value"] = "sha256:" + "3" * 64
            receipt_material = copy.deepcopy(receipt)
            receipt_material["receiptFingerprint"] = None
            receipt["receiptFingerprint"]["value"] = bundle.committed(
                b"RG-CS-GATE-A-ROLE-SELF-TEST-RECEIPT-v1",
                receipt_material,
            )
            write_json(output / oracle_path, receipt)

            def rebind_oracle(value: dict[str, Any]) -> None:
                update_entry_commitments(output, value, oracle_path)

            _, rebound_root = mutate_manifest(root, output, rebind_oracle)
            expect_rejection(
                "self-consistent oracle binding drift",
                run_cli(verifier_command(root, output, rebound_root), root),
                "BUNDLE_ORACLE_RECEIPT_BINDING_DRIFT",
            )
            attacks += 1
        finally:
            temporary.cleanup()

        temporary, root, authority, paths = fixture_repository()
        try:
            output = root / "bundle"
            root_from(run_cli(compiler_command(root, paths, output), root))
            binding_path = bundle.BINDING_MANIFEST
            binding = load_json(output / binding_path)
            role_names = sorted(binding["bindings"])
            binding["bindings"][role_names[0]]["oracle"] = copy.deepcopy(binding["bindings"][role_names[1]]["oracle"])
            binding_material = copy.deepcopy(binding)
            binding_material["bindingFingerprint"] = None
            binding["bindingFingerprint"]["value"] = bundle.committed(
                b"RG-CS-GATE-A-ROLE-BLACK-BOX-BINDINGS-v1",
                binding_material,
            )
            write_json(output / binding_path, binding)

            def rebind_binding(value: dict[str, Any]) -> None:
                update_entry_commitments(output, value, binding_path)

            _, rebound_root = mutate_manifest(root, output, rebind_binding)
            expect_rejection(
                "self-consistent fixture binding drift",
                run_cli(verifier_command(root, output, rebound_root), root),
                "BUNDLE_FIXTURE_BINDING_CONTENT_DRIFT",
            )
            attacks += 1
        finally:
            temporary.cleanup()

        for label, operation, token in (
            ("symlink", "symlink", "BUNDLE_SYMLINK_PRESENT"),
            ("hardlink", "hardlink", "BUNDLE_HARDLINK_PRESENT"),
        ):
            temporary, root, authority, paths = fixture_repository()
            try:
                output = root / "bundle"
                expected = root_from(run_cli(compiler_command(root, paths, output), root))
                target = output / load_json(output / bundle.ROOT_MANIFEST)["files"][0]["relativePath"]
                source = output / "replacement.bin"
                source.write_bytes(b"replacement")
                original = target.read_bytes()
                target.unlink()
                if operation == "symlink":
                    target.symlink_to(source)
                else:
                    target.hardlink_to(source)
                expect_rejection(label, run_cli(verifier_command(root, output, expected), root), token)
                if operation == "hardlink" and original == source.read_bytes():
                    raise SystemExit("TOOLING_HARDLINK_FIXTURE_INVALID")
                attacks += 1
            finally:
                temporary.cleanup()

        temporary, root, authority, paths = fixture_repository()
        try:
            output = root / "bundle"
            old_root = root_from(run_cli(compiler_command(root, paths, output), root))
            manifest = load_json(output / bundle.ROOT_MANIFEST)
            relative = next(item["relativePath"] for item in manifest["files"] if item["kind"] == "SCHEMA")
            (output / relative).write_bytes(b"x" * (manifest["limits"]["maxFileBytes"] + 1))
            def rebind_oversized_file(value: dict[str, Any]) -> None:
                entry = next(item for item in value["files"] if item["relativePath"] == relative)
                entry["rawFingerprint"] = bundle.raw_fingerprint((output / relative).read_bytes())
                value["closedTreeFingerprint"] = bundle._tree_commitment(value["files"], bundle.TREE_DOMAIN)

            _, new_root = mutate_manifest(root, output, rebind_oversized_file)
            expect_rejection("file budget", run_cli(verifier_command(root, output, new_root), root), "BUNDLE_FILE_SIZE_LIMIT")
            if new_root == old_root:
                raise SystemExit("TOOLING_BUDGET_ROOT_DID_NOT_CHANGE")
            attacks += 1
        finally:
            temporary.cleanup()

        temporary, root, authority, paths = fixture_repository()
        try:
            output = root / "bundle"
            command = compiler_command(root, paths, root / "rejected")
            command[command.index("--output-root") + 1] = f"{root / 'rejected'}/"
            failed = run_cli(command, root)
            expect_rejection("trailing output alias", failed, "BUNDLE_OUTPUT_ROOT_PATH_ALIAS")
            if (root / "rejected").exists():
                raise SystemExit("TOOLING_ALIAS_CREATED_OUTPUT")
            attacks += 1
        finally:
            temporary.cleanup()

        temporary, root, authority, paths = fixture_repository()
        try:
            output = root / "rejected"
            paths["IMPLEMENTATION_CANDIDATE"].write_bytes(b"not-a-jar")
            failed = run_cli(compiler_command(root, paths, output), root)
            expect_rejection("preflight no output", failed, "BUNDLE_ROLE_JAR_MALFORMED")
            if output.exists():
                raise SystemExit("TOOLING_PREFLIGHT_LEFT_OUTPUT_ROOT")
            attacks += 1
        finally:
            temporary.cleanup()

        temporary, root, authority, paths = fixture_repository()
        try:
            output = root / "collision"
            run_cli(compiler_command(root, paths, output), root)
            failed = run_cli(compiler_command(root, paths, output), root)
            expect_rejection("output collision", failed, "BUNDLE_OUTPUT_ROOT_EXISTS")
            attacks += 1
        finally:
            temporary.cleanup()

        temporary, root, authority, paths = fixture_repository()
        try:
            first = root / "first"
            second = root / "second"
            first_root = root_from(run_cli(compiler_command(root, paths, first), root))
            second_root = root_from(run_cli(compiler_command(root, paths, second), root))
            first_files = sorted(path.relative_to(first) for path in first.rglob("*") if path.is_file())
            second_files = sorted(path.relative_to(second) for path in second.rglob("*") if path.is_file())
            if first_root != second_root or first_files != second_files or any((first / path).read_bytes() != (second / path).read_bytes() for path in first_files):
                raise SystemExit("TOOLING_DOUBLE_COMPILE_NOT_BYTE_IDENTICAL")
            attacks += 1
        finally:
            temporary.cleanup()

    print(f"Gate A sealed Bundle tooling PASS: {attacks} high-value attacks rejected.")


if __name__ == "__main__":
    main()
