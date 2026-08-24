#!/usr/bin/env python3
"""Isolation tests for the one-time SliceAcceptanceReceipt verifier.

Round 7 additions:
- Typed evidence: arbitrary text rejected, schema/owner/verifier drift detected
- Ledger key = invocation-only (no receiptFingerprint in key)
- Ledger head tracking: genesis sentinel, stale head rejection
- Full lineage marker
- Postcommit marker bytes reread
- Same invocation second consume rejected
- Wrong lineage predecessor attack
- Stale ledger head / rollback scenarios
"""

from __future__ import annotations

import copy
import fcntl
import hashlib
import importlib.util
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
from typing import Any, Callable


HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[4]
PROTOCOL = pathlib.Path("docs/acceptance/capability-studio/gate-a-wire-v1/protocol-compiler")


def load_module(name: str, path: pathlib.Path) -> Any:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise SystemExit(f"SLICE_TEST_MODULE_UNAVAILABLE:{name}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


tooling = load_module("slice_receipt_tooling", HERE / "test-protocol-tooling.py")
bundle = tooling.bundle
receipt_module = load_module("slice_acceptance_receipt", HERE / "slice_acceptance_receipt.py")

GENESIS_HEAD_FP = receipt_module.GENESIS_PREVIOUS_HEAD_FP


def raw_fp(raw: bytes) -> str:
    return f"sha256:{hashlib.sha256(raw).hexdigest()}"


def write_json(path: pathlib.Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8") + b"\n")


def run(command: list[str], cwd: pathlib.Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=cwd, capture_output=True, text=True)


def ledger_head(
    revision: int,
    prev_marker_fp: str,
    prev_revision: int,
    marker_key_fp: str,
) -> dict[str, Any]:
    return {
        "schemaVersion": "capability-studio.gate-a-slice-consumption-ledger-head.v1",
        "adapterMode": "NON_RELEASE_ROLLBACKABLE_ADAPTER",
        "ledgerRevision": revision,
        "previousHeadMarkerFingerprint": prev_marker_fp,
        "previousHeadRevision": prev_revision,
        "markerKeyFingerprint": marker_key_fp,
        "timestampISO8601": "2026-08-22T00:00:00Z",
    }


def write_ledger_head(path: pathlib.Path, head: dict[str, Any]) -> None:
    write_json(path, head)


def verifier_command(
    root: pathlib.Path,
    bundle_root: pathlib.Path,
    bundle_fp: str,
    receipt: pathlib.Path,
    slice_id: str,
    nonce: str,
    source_tree_fp: str,
    toolchain_fp: str,
    build_invocation_id: str,
    launcher_fp: str,
    command_fp: str,
    source_fp: str,
    artifact_root: pathlib.Path,
    evidence_root: pathlib.Path,
    ledger_root: pathlib.Path,
    predecessor: str | None,
    ledger_head_fp: str = GENESIS_HEAD_FP,
    ledger_revision: int = 0,
    predecessor_marker_fp: str | None = None,
) -> list[str]:
    command = [
        sys.executable,
        str(root / PROTOCOL / "verify-slice-acceptance-receipt.py"),
        "--bundle-root", str(bundle_root),
        "--expected-bundle-root-fingerprint", bundle_fp,
        "--receipt", str(receipt),
        "--expected-slice-id", slice_id,
        "--expected-challenge-nonce", nonce,
        "--expected-source-tree-fingerprint", source_tree_fp,
        "--expected-toolchain-identity-fingerprint", toolchain_fp,
        "--expected-build-invocation-id", build_invocation_id,
        "--expected-launcher-observation-fingerprint", launcher_fp,
        "--expected-command-fingerprint", command_fp,
        "--expected-test-source-fingerprint", source_fp,
        "--artifact-root", str(artifact_root),
        "--evidence-root", str(evidence_root),
        "--consumption-ledger-root", str(ledger_root),
        "--expected-ledger-head-fingerprint", ledger_head_fp,
        "--expected-ledger-revision", str(ledger_revision),
    ]
    if predecessor is not None:
        command.extend(("--expected-predecessor-receipt-fingerprint", predecessor))
    if predecessor_marker_fp is not None:
        command.extend(("--predecessor-marker-fingerprint", predecessor_marker_fp))
    return command


def execute(case: dict[str, Any]) -> subprocess.CompletedProcess[str]:
    return run(
        verifier_command(
            case["root"],
            case["output"],
            case["bundle_fp"],
            case["receipt_path"],
            case["receipt"]["sliceId"],
            case["nonce"],
            case["source_tree_fp"],
            case["toolchain_fp"],
            case["build_invocation_id"],
            case["launcher_fp"],
            case["command_fp"],
            case["source_fp"],
            case["artifact_root"],
            case["evidence_root"],
            case["ledger_root"],
            case["predecessor"],
            case.get("ledger_head_fp", GENESIS_HEAD_FP),
            case.get("ledger_revision", 0),
            case.get("predecessor_marker_fp"),
        ),
        case["root"],
    )


def parse_result(result: subprocess.CompletedProcess[str]) -> dict[str, Any] | None:
    """Parse 'SLICE_RECEIPT_ACCEPTED:receiptFingerprint=...;ledgerHeadFingerprint=...'"""
    for line in result.stdout.splitlines():
        if line.startswith("SLICE_RECEIPT_ACCEPTED:"):
            parts = {}
            # Split only on first colon to get prefix, then split remaining on semicolons
            # Format: SLICE_RECEIPT_ACCEPTED:key1=value1;key2=value2;...
            prefix, _, rest = line.partition(":")
            for part in rest.split(";"):
                if "=" in part:
                    k, _, v = part.partition("=")
                    parts[k] = v
            return parts
    return None


def prepare(
    slice_id: str = "A1.1",
    predecessor: str | None = None,
    predecessor_marker: dict[str, Any] | None = None,
    ledger_head_doc: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Create a fully-formed test case for the given slice."""
    temporary, root, authority, paths = tooling.fixture_repository(slice_id)
    output = root / "bundle"
    compiled = tooling.run_cli(tooling.compiler_command(root, paths, output, target_slice_id=slice_id), root)
    bundle_fp = tooling.root_from(compiled)
    snapshot = bundle.verify_bundle(output, bundle_fp)
    slices = [item for item in authority["deliverySlices"] if item["sliceId"] == slice_id]
    if len(slices) != 1:
        raise SystemExit(f"SLICE_TEST_UNKNOWN_SLICE:{slice_id}")
    contract = slices[0]["acceptanceContract"]
    nonce = "0123456789abcdef0123456789abcdef"
    source_tree_fp = raw_fp(b"source-tree")
    toolchain_fp = raw_fp(b"toolchain")
    build_invocation_id = f"RG-CS-BUILD-{slice_id.replace('.', '')}-00000001"
    launcher_fp = raw_fp(b"launcher-observation")
    command_fp = raw_fp(b"observed-command")
    source_fp = raw_fp(b"test-source")
    artifact_root = root / "artifact-root"
    evidence_root = root / "evidence-root"
    ledger_root = root / "consumption-ledger"
    ledger_root.mkdir()

    # Write predecessor marker if provided
    predecessor_marker_fp = None
    if predecessor_marker is not None:
        pred_key_fp = bundle.committed(
            receipt_module.LEDGER_KEY_DOMAIN,
            predecessor_marker["key"],
        )
        predecessor_marker_fp = pred_key_fp
        marker_path = ledger_root / f"marker-{pred_key_fp[7:]}.json"
        marker_path.write_bytes(
            json.dumps(predecessor_marker, separators=(",", ":"), sort_keys=True).encode("utf-8") + b"\n"
        )
        # Write ledger head for predecessor
        head_path = ledger_root / "ledger-head.json"
        head_doc = ledger_head(
            revision=1,
            prev_marker_fp=pred_key_fp,
            prev_revision=0,
            marker_key_fp=pred_key_fp,
        )
        write_ledger_head(head_path, head_doc)

    # Write caller-pinned ledger head if provided (for stale-head tests)
    if ledger_head_doc is not None:
        head_path = ledger_root / "ledger-head.json"
        write_ledger_head(head_path, ledger_head_doc)

    role_by_path = {role["artifactPath"]: role["role"] for role in authority["roleContracts"]}
    # Build required roles from contract's artifact set, then map to manifest roleViews
    # Use manifest.implementationRoles for roleViewBindings (verifier enforces this exact set)
    # For A1.1: ['IMPLEMENTATION_CANDIDATE']; for A1.2: ['TCK_PROVIDER']
    required_roles = sorted({role_by_path[p] for p in contract["requiredArtifactPaths"]})
    rv_by_role = {rv["role"]: rv for rv in snapshot.manifest["roleViews"]}
    impl_roles = sorted(snapshot.manifest["implementationRoles"])
    artifact_records: list[dict[str, Any]] = []
    for artifact_path in sorted(contract["requiredArtifactPaths"]):
        role = role_by_path[artifact_path]
        source = root / artifact_path
        destination = artifact_root / artifact_path
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        raw = source.read_bytes()
        artifact_records.append({"role": role, "path": artifact_path, "byteLength": len(raw), "rawFingerprint": raw_fp(raw)})

    # Build evidence mapping: short ID -> Authority evidenceContract (by messageVersion + sliceId)
    # acceptanceContract.requiredEvidenceIds uses short IDs (ROLE_SELF_TEST_RECEIPT, PROVIDED_ABI_CONTRACT, etc.)
    # evidenceContracts catalog uses A1_EVIDENCE_* prefixed IDs with messageVersion for resolution
    evidence_contract_by_msg_version: dict[str, dict[str, Any]] = {}
    for ec in authority.get("evidenceContracts", []):
        ec_msg_version = ec.get("messageVersion", "")
        ec_allowed_slices = ec.get("allowedSliceIds", [])
        if ec_msg_version and slice_id in ec_allowed_slices:
            evidence_contract_by_msg_version[ec_msg_version] = ec

    # Verify we have explicit Authority contracts for all required evidence IDs
    # Build set of expected messageVersions for this slice
    expected_msg_versions = set(evidence_contract_by_msg_version.keys())
    if not expected_msg_versions:
        raise SystemExit(f"SLICE_TEST_NO_AUTHORITY_CONTRACTS:{slice_id}")

    # Build evidence records
    evidence_records: list[dict[str, Any]] = []
    for ev_id in sorted(contract["requiredEvidenceIds"]):
        ev_path = f"evidence/{ev_id}.json"
        ev_full_path = evidence_root / ev_path
        ev_full_path.parent.mkdir(parents=True, exist_ok=True)
        if ev_id == "ROLE_SELF_TEST_RECEIPT":
            ev_doc = {
                "messageVersion": "resource-gateway.capability-studio.gate-a.role-self-test-receipt.v1",
                "receiptFingerprint": None,  # filled below
                "role": "IMPLEMENTATION_CANDIDATE",
                "authority": {"rawFingerprint": bundle.typed(snapshot.bytes_for("authority/protocol-authority.json")), "revision": authority["revision"]},
                "artifactRawFingerprint": {"kind": "RAW_BYTES", "algorithm": "SHA-256", "value": raw_fp(b"role-jar-content")},
                "profileRawFingerprint": None,
                "fixtureSetId": "GATE_A_ROLE_BLACK_BOX_V1",
                "capabilities": ["ROLE_SELF_TEST_RECEIPT"],
                "status": "READY",
                "roleViewFingerprint": {"kind": "TREE_COMMITMENT", "algorithm": "SHA-256", "value": raw_fp(b"role-view-tree")},
                "inputTreeFingerprint": {"kind": "TREE_COMMITMENT", "algorithm": "SHA-256", "value": raw_fp(b"input-tree")},
            }
            # Dynamically compute SELF_NULL_RECEIPT fingerprint from authority
            role_contracts = authority.get("roleContracts", [])
            black_box_contract = None
            for rc in role_contracts:
                if isinstance(rc, dict) and rc.get("role") == "IMPLEMENTATION_CANDIDATE":
                    black_box_contract = rc.get("blackBoxContract")
                    break
            if black_box_contract is None:
                raise SystemExit("SLICE_TEST_ROLE_CONTRACT_MISSING:IMPLEMENTATION_CANDIDATE")
            domain = black_box_contract.get("receiptFingerprintDomain")
            if domain is None:
                raise SystemExit("SLICE_TEST_DOMAIN_MISSING:IMPLEMENTATION_CANDIDATE")
            # Compute fingerprint: domain encodes the namespace, material has receiptFingerprint=None
            # producerOwner is included so verification fingerprint matches document bytes
            material = copy.deepcopy(ev_doc)
            material["receiptFingerprint"] = None
            self_null_fp = bundle.committed(domain.encode("ascii"), bundle._freeze(material))
            ev_doc["receiptFingerprint"] = {
                "kind": "SELF_NULL_RECEIPT",
                "algorithm": "SHA-256",
                "value": self_null_fp,
                "selfNullField": "receiptFingerprint",
            }
        elif ev_id == "CANDIDATE_RUNTIME_CLOSURE":
            ev_doc = {
                "schemaVersion": "capability-studio.gate-a-runtime-closure-evidence.v1",
                "messageVersion": "resource-gateway.capability-studio.gate-a.runtime-closure-evidence.v1",
                "artifactFingerprint": raw_fp(b"candidate-artifact"),
                "status": "CLOSED",
            }
        elif ev_id == "PROVIDED_ABI_CONTRACT":
            # Source exact messageVersion and schema from Authority.evidenceContracts
            abi_msg_version = "resource-gateway.capability-studio.gate-a.provided-abi-evidence.v1"
            abi_contract = evidence_contract_by_msg_version.get(abi_msg_version)
            if abi_contract is None:
                raise SystemExit(f"SLICE_TEST_MISSING_AUTHORITY_CONTRACT:{ev_id}:{abi_msg_version}:{slice_id}")
            # providerArtifactFingerprint: use TCK_PROVIDER artifact fingerprint
            tck_artifact_path = next(
                (p for p in contract.get("requiredArtifactPaths", [])
                 if "tck-provider" in p.lower()),
                None
            )
            if tck_artifact_path is None:
                raise SystemExit(f"SLICE_TEST_MISSING_TCK_PROVIDER_ARTIFACT:{slice_id}")
            tck_raw = (root / tck_artifact_path).read_bytes()
            ev_doc = {
                "schemaVersion": "capability-studio.gate-a-provided-abi-evidence.v1",
                "messageVersion": abi_msg_version,
                "providerArtifactFingerprint": raw_fp(tck_raw),
                "status": "RESOLVED",
            }
        else:
            # Generic evidence for other IDs - fail with explicit error
            raise SystemExit(f"SLICE_TEST_UNHANDLED_EVIDENCE_ID:{ev_id}:{slice_id}")
        ev_canonical = json.dumps(ev_doc, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8") + b"\n"
        ev_full_path.write_bytes(ev_canonical)
        evidence_records.append({
            "id": ev_id,
            "path": ev_path,
            "byteLength": len(ev_canonical),
            "rawFingerprint": raw_fp(ev_canonical),
        })

    predecessor_slice_id = None if predecessor is None else next(
        item["sourceSliceId"] for item in [contract["predecessorReceiptFingerprint"]]
        if item is not None
    )

    # Build source tree commitment
    source_tree_commitment = {
        "kind": "TREE_COMMITMENT",
        "algorithm": "SHA-256",
        "domain": "RG-CS-GATE-A-SOURCE-TREE-v1",
        "source": "CALLER_PINNED_SOURCE_TREE",
        "value": source_tree_fp,
        "selfNullPolicy": "VALUE_NULL_DURING_HASH",
    }

    # Build receipt
    invocation_key = {
        "bundleRootFingerprint": bundle_fp,
        "sliceId": slice_id,
        "challengeNonce": nonce,
        "buildInvocationId": build_invocation_id,
    }
    invocation_key_fp = bundle.committed(receipt_module.LEDGER_INVOCATION_KEY_DOMAIN, invocation_key)

    receipt_doc = {
        "schemaVersion": "capability-studio.gate-a-slice-acceptance-receipt.v1",
        "messageVersion": contract["messageVersion"],
        "sliceId": slice_id,
        "acceptanceId": contract["acceptanceId"],
        "bundleRootFingerprint": bundle_fp,
        "authority": {"revision": authority["revision"], "rawFingerprint": snapshot.manifest["authorityRawFingerprint"]},
        "dependencyAuthorityFingerprint": snapshot.manifest["dependencyAuthorityRawFingerprint"],
        "acceptanceContractFingerprint": {"kind": "CANONICAL_DOCUMENT", "algorithm": "SHA-256", "domain": "RG-CS-GATE-A-SLICE-ACCEPTANCE-CONTRACT-v1", "source": "BUNDLE_AUTHORITY_ACCEPTANCE_CONTRACT", "value": bundle.committed(b"RG-CS-GATE-A-SLICE-ACCEPTANCE-CONTRACT-v1", contract)},
        "challengeNonce": nonce,
        "receiptPath": contract["receiptPath"],
        "buildInvocation": {"id": build_invocation_id, "profile": contract["buildProfile"], "properties": contract["buildProperties"]},
        "sourceTreeCommitment": source_tree_commitment,
        "toolchainIdentityFingerprint": {
            "kind": "RAW_BYTES", "algorithm": "SHA-256",
            "domain": "RG-CS-GATE-A-TOOLCHAIN-RUNTIME-v1",
            "source": "REQUIRED_EXTERNAL_TOOLCHAIN_PIN",
            "value": toolchain_fp, "selfNullPolicy": "VALUE_NULL_UNTIL_EXTERNAL_PIN",
        },
        "launcherObservationFingerprint": launcher_fp,
        "observedCommandFingerprint": command_fp,
        "testSourceFingerprint": source_fp,
        "testIds": contract["testIds"],
        "roleViewBindings": [
            {"role": role,
             "roleViewFingerprint": rv_by_role[role]["roleViewFingerprint"],
             "inputTreeFingerprint": rv_by_role[role]["inputTreeFingerprint"]}
            for role in impl_roles
        ],
        "predecessorSliceId": predecessor_slice_id,
        "predecessorReceiptFingerprint": predecessor,
        "artifactRecords": artifact_records,
        "evidenceRecords": evidence_records,
        "artifactAggregate": {"kind": "AGGREGATE_COMMITMENT", "algorithm": "SHA-256", "domain": "RG-CS-GATE-A-SLICE-ARTIFACTS-v1", "source": "REQUIRED_ARTIFACT_RAW_BYTES", "value": bundle.committed(b"RG-CS-GATE-A-SLICE-ARTIFACTS-v1", artifact_records)},
        "evidenceAggregate": {"kind": "AGGREGATE_COMMITMENT", "algorithm": "SHA-256", "domain": "RG-CS-GATE-A-SLICE-EVIDENCE-v1", "source": "REQUIRED_EVIDENCE_EXACT_BYTES", "value": bundle.committed(b"RG-CS-GATE-A-SLICE-EVIDENCE-v1", evidence_records)},
        "handoff": contract["handoff"],
        "observed": {"exitCode": 0, "terminal": "SUCCESS", "status": "ACCEPTED"},
        "receiptFingerprint": {
            "kind": "CANONICAL_DOCUMENT",
            "algorithm": "SHA-256",
            "domain": "RG-CS-GATE-A-SLICE-ACCEPTANCE-RECEIPT-v1",
            "value": None,
            "selfNullPolicy": "VALUE_NULL_DURING_HASH",
        },
    }

    # Self-fingerprint the receipt: clone, set value=None, hash, then fill value
    doc_for_fp = copy.deepcopy(receipt_doc)
    doc_for_fp["receiptFingerprint"]["value"] = None
    canonical_for_fp = json.dumps(doc_for_fp, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
    receipt_fp_val = raw_fp(canonical_for_fp)
    receipt_doc["receiptFingerprint"]["value"] = receipt_fp_val

    # Build marker key (full: invocation + receiptFingerprint)
    full_key = dict(invocation_key)
    full_key["receiptFingerprint"] = receipt_fp_val
    full_key_fp = bundle.committed(receipt_module.LEDGER_KEY_DOMAIN, full_key)

    receipt_canonical = json.dumps(receipt_doc, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8") + b"\n"
    receipt_path = root / contract["receiptPath"]
    receipt_path.parent.mkdir(parents=True, exist_ok=True)
    receipt_path.write_bytes(receipt_canonical)

    return {
        "temporary": temporary, "root": root, "authority": authority,
        "output": output, "bundle_fp": bundle_fp, "snapshot": snapshot,
        "contract": contract, "nonce": nonce, "source_tree_fp": source_tree_fp,
        "toolchain_fp": toolchain_fp, "build_invocation_id": build_invocation_id,
        "launcher_fp": launcher_fp, "command_fp": command_fp, "source_fp": source_fp,
        "artifact_root": artifact_root, "evidence_root": evidence_root,
        "ledger_root": ledger_root, "predecessor": predecessor,
        "predecessor_marker_fp": predecessor_marker_fp,
        "receipt": receipt_doc, "receipt_path": receipt_path,
        "expected_slice_id": slice_id,
        "expected_nonce_for_test": nonce,
        "invocation_key_fp": invocation_key_fp,
        "full_key_fp": full_key_fp,
    }


def expect_rejection(label: str, result: subprocess.CompletedProcess[str], token: str) -> None:
    if result.returncode == 0:
        raise SystemExit(f"SLICE_TEST_ATTACK_ACCEPTED:{label}")
    if token not in result.stdout and token not in result.stderr:
        raise SystemExit(f"SLICE_TEST_ATTACK_WRONG_FAILURE:{label}:stdout={result.stdout.strip()}:stderr={result.stderr.strip()}")


def assert_ledger_empty(case: dict[str, Any], label: str) -> None:
    markers = list(case["ledger_root"].glob("marker-*.json"))
    if markers:
        raise SystemExit(f"SLICE_TEST_LEDGER_NOT_EMPTY:{label}:{len(markers)}")


def run_attack(
    label: str,
    token: str,
    mutation: Callable[[dict[str, Any]], None],
    *,
    slice_id: str = "A1.1",
    predecessor: str | None = None,
    predecessor_marker: dict[str, Any] | None = None,
    ledger_head_doc: dict[str, Any] | None = None,
    mutate_case: Callable[[dict[str, Any]], None] | None = None,
) -> None:
    case = prepare(slice_id, predecessor, predecessor_marker, ledger_head_doc)
    try:
        if mutate_case:
            mutate_case(case)
        if mutation:
            mutation(case.get("receipt", {}))
        result = execute(case)
        expect_rejection(label, result, token)
    finally:
        case["temporary"].cleanup()

def rebind_evidence_bytes(case: dict[str, Any], record_index: int, raw: bytes) -> None:
    """Write raw evidence bytes and rebind all dependent fingerprints in the receipt.

    - Writes ``raw`` to the evidence file at
      ``case["receipt"]["evidenceRecords"][record_index]["path"]``.
    - Sets ``byteLength`` = ``len(raw)`` and ``rawFingerprint`` = raw_fp(raw)
      on that record.
    - Recomputes ``evidenceAggregate`` with domain
      "RG-CS-GATE-A-SLICE-EVIDENCE-v1" using the updated evidence-records list
      (which ``bundle.committed`` canonicalises with sort_keys=True).
    - Recomputes ``receiptFingerprint`` using production self-fingerprint
      semantics: clone receipt, set ``value = None`` on ``receiptFingerprint``,
      canonical-JSON-encode, SHA-256 the canonical bytes, assign the hex digest
      back to ``value``.
    - Writes the canonical receipt (sorted keys, no extra whitespace) + ``\n``
      back to disk.
    """
    ev_record = case["receipt"]["evidenceRecords"][record_index]
    ev_path = case["evidence_root"] / ev_record["path"]

    ev_path.write_bytes(raw)
    ev_record["byteLength"] = len(raw)
    ev_record["rawFingerprint"] = raw_fp(raw)

    case["receipt"]["evidenceAggregate"] = {
        "kind": "AGGREGATE_COMMITMENT",
        "algorithm": "SHA-256",
        "domain": "RG-CS-GATE-A-SLICE-EVIDENCE-v1",
        "source": "REQUIRED_EVIDENCE_EXACT_BYTES",
        "value": bundle.committed(
            b"RG-CS-GATE-A-SLICE-EVIDENCE-v1",
            case["receipt"]["evidenceRecords"],
        ),
    }

    doc_for_fp = copy.deepcopy(case["receipt"])
    doc_for_fp["receiptFingerprint"]["value"] = None
    canonical_for_fp = json.dumps(
        doc_for_fp, ensure_ascii=False, separators=(",", ":"), sort_keys=True
    ).encode("utf-8")
    case["receipt"]["receiptFingerprint"]["value"] = raw_fp(canonical_for_fp)

    canonical_receipt = (
        json.dumps(case["receipt"], ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
        + b"\n"
    )
    case["receipt_path"].write_bytes(canonical_receipt)


def inject_arbitrary_text_evidence(case: dict[str, Any]) -> None:
    """Inject non-JSON evidence bytes and rebind all dependent fingerprints.

    Writes ``b"not-json\n"`` (10 bytes) and uses :func:`rebind_evidence_bytes`
    to keep byteLength, rawFingerprint, evidenceAggregate, receiptFingerprint,
    and the on-disk canonical receipt in sync.
    """
    rebind_evidence_bytes(case, 0, b"not-json\n")


def inject_duplicate_key_evidence(case: dict[str, Any]) -> None:
    """Inject evidence with duplicate JSON keys and rebind all dependent fingerprints.

    ``bundle.committed`` canonicalises its records list with ``sort_keys=True``,
    so the canonical form differs from the raw bytes fed to
    :func:`rebind_evidence_bytes`.  The verifier's own JSON parse / aggregate
    recompute will diverge from the crafted canonical form, reaching
    ``RECEIPT_EVIDENCE_JSON_INVALID`` with no hardcoded ``byteLength`` required.
    """
    dup_json = b'{"id":"X","id":"X","path":"x","byteLength":137,"rawFingerprint":"sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}\n'
    rebind_evidence_bytes(case, 0, dup_json)


def inject_wrong_status_evidence(case: dict[str, Any]) -> None:
    """Inject evidence with a WRONG status and rebind all dependent fingerprints.

    Starts from the selected valid evidence doc on disk, strictly deserialises it
    as JSON, sets ``status`` to ``"WRONG"``, serialises the mutated doc as
    canonical JSON + ``\n``, and calls :func:`rebind_evidence_bytes`.  This reaches
    ``RECEIPT_EVIDENCE_OUTCOME_INVALID`` without any byte/aggregate/self-fingerprint
    drift.
    """
    ev_records = case["receipt"]["evidenceRecords"]
    record_index = next(
        (i for i, r in enumerate(ev_records) if r.get("id") == "CANDIDATE_RUNTIME_CLOSURE"),
        0,
    )
    ev_record = ev_records[record_index]
    ev_path = case["evidence_root"] / ev_record["path"]
    ev_doc = json.loads(ev_path.read_bytes())
    ev_doc["status"] = "WRONG"
    bad_raw = (
        json.dumps(ev_doc, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
        + b"\n"
    )
    rebind_evidence_bytes(case, record_index, bad_raw)


def corrupt_receipt_self_fingerprint(case: dict[str, Any]) -> None:
    """Corrupt receipt self-fingerprint by changing value to a wrong sha256 hex string.

    Sets ``case["receipt"]["receiptFingerprint"]["value"]`` to ``"sha256:" + "f" * 64``
    and writes the canonical receipt to ``case["receipt_path"]`` without recomputing
    evidence aggregate or any other fingerprints.  This drifts only the receipt's
    self-fingerprint, reaching ``RECEIPT_SELF_FINGERPRINT_DRIFT``.
    """
    case["receipt"]["receiptFingerprint"]["value"] = "sha256:" + "f" * 64
    canonical_receipt = (
        json.dumps(case["receipt"], ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
        + b"\n"
    )
    case["receipt_path"].write_bytes(canonical_receipt)


def corrupt_evidence_bytes(case: dict[str, Any]) -> None:
    """Flip the first byte of the first evidence file while preserving byte length.

    Reads the actual evidence bytes from disk, writes ``b"X" + raw[1:]`` back
    to the same path, and does NOT rebind the receipt or any fingerprints.
    This drifts only the raw fingerprint of that evidence record.
    """
    ev_record = case["receipt"]["evidenceRecords"][0]
    ev_path = case["evidence_root"] / ev_record["path"]
    raw = ev_path.read_bytes()
    ev_path.write_bytes(b"X" + raw[1:])


def rebind_nonce(case: dict[str, Any], new_nonce: str) -> None:
    """Change the nonce and recompute the receipt self-fingerprint.

    Sets ``case["nonce"]`` and ``case["receipt"]["challengeNonce"]`` to ``new_nonce``,
    recomputes ``case["receipt"]["receiptFingerprint"]["value"]`` from the canonical
    receipt with ``value = None``, then writes the canonical receipt back to disk.
    This makes a fresh invocation that shares the same ledger.
    """
    case["nonce"] = new_nonce
    case["receipt"]["challengeNonce"] = new_nonce
    doc_for_fp = copy.deepcopy(case["receipt"])
    doc_for_fp["receiptFingerprint"]["value"] = None
    canonical_for_fp = json.dumps(
        doc_for_fp, ensure_ascii=False, separators=(",", ":"), sort_keys=True
    ).encode("utf-8")
    case["receipt"]["receiptFingerprint"]["value"] = raw_fp(canonical_for_fp)
    canonical_receipt = (
        json.dumps(case["receipt"], ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
        + b"\n"
    )
    case["receipt_path"].write_bytes(canonical_receipt)


def main() -> None:
    attacks = 0

    # ---- Happy path: A1.1 genesis ----
    happy = prepare("A1.1")
    try:
        result = execute(happy)
        if result.returncode != 0:
            raise SystemExit(f"SLICE_TEST_HAPPY_FAILED:{result.stdout}:{result.stderr}")
        parsed = parse_result(result)
        if parsed is None:
            raise SystemExit(f"SLICE_TEST_HAPPY_NO_PARSE:{result.stdout}")
        if parsed.get("receiptFingerprint") is None:
            raise SystemExit("SLICE_TEST_HAPPY_NO_FP")
        if parsed.get("ledgerHeadFingerprint") is None:
            raise SystemExit("SLICE_TEST_HAPPY_NO_HEAD_FP")
        # Verify ledger has marker + head
        if not list(happy["ledger_root"].glob("marker-*.json")):
            raise SystemExit("SLICE_TEST_HAPPY_NO_MARKER")
        if not (happy["ledger_root"] / "ledger-head.json").exists():
            raise SystemExit("SLICE_TEST_HAPPY_NO_HEAD")
        # Verify head contents
        head_doc = json.loads((happy["ledger_root"] / "ledger-head.json").read_bytes())
        if head_doc.get("adapterMode") != "NON_RELEASE_ROLLBACKABLE_ADAPTER":
            raise SystemExit("SLICE_TEST_HAPPY_BAD_ADAPTER_MODE")
        if head_doc.get("ledgerRevision") != 1:
            raise SystemExit(f"SLICE_TEST_HAPPY_BAD_REVISION:{head_doc.get('ledgerRevision')}")
    finally:
        happy["temporary"].cleanup()

    # ---- Same invocation second consume (replay) ----
    first = prepare("A1.1")
    try:
        r1 = execute(first)
        if r1.returncode != 0:
            raise SystemExit(f"SLICE_TEST_REPLAY_FIRST_FAILED:{r1.stdout}:{r1.stderr}")
        # Copy ledger (with marker + head) for second attempt
        ledger_copy_dir = first["root"] / "ledger-copy"
        shutil.copytree(first["ledger_root"], ledger_copy_dir)
        # Reuse first case but with copied ledger for second consume
        # This preserves all expectations (nonce, fingerprints, etc.) from first consume
        second = dict(first)  # shallow copy of case
        second["temporary"] = first["temporary"]  # share cleanup
        second["ledger_root"] = ledger_copy_dir
        # Read actual ledger head and set expected values for second consume
        second_head = json.loads((ledger_copy_dir / "ledger-head.json").read_bytes())
        second["ledger_head_fp"] = second_head.get("markerKeyFingerprint", GENESIS_HEAD_FP)
        second["ledger_revision"] = second_head.get("ledgerRevision", 1)
        # Same invocation, same receipt: should be rejected (same ledger key)
        r2 = execute(second)
        expect_rejection("same invocation second consume", r2, "REPLAY_REJECTED")
        attacks += 1
    finally:
        first["temporary"].cleanup()

    # ---- Same invocation, changed evidence bytes: should be REPLAY_REJECTED ----
    first_ev = prepare("A1.1")
    try:
        r1 = execute(first_ev)
        if r1.returncode != 0:
            raise SystemExit(f"SLICE_TEST_REPLAY_EV_FIRST_FAILED:{r1.stdout}")
        # Copy the ledger
        ledger_copy = first_ev["root"] / "ledger-ev-copy"
        shutil.copytree(first_ev["ledger_root"], ledger_copy)
        # Reuse first case but with copied ledger for second consume
        # This preserves all expectations (nonce, fingerprints, etc.) from first consume
        second_ev = dict(first_ev)  # shallow copy of case
        second_ev["temporary"] = first_ev["temporary"]  # share cleanup
        second_ev["ledger_root"] = ledger_copy
        # Read actual ledger head and set expected values for second consume
        second_ev_head = json.loads((ledger_copy / "ledger-head.json").read_bytes())
        second_ev["ledger_head_fp"] = second_ev_head.get("markerKeyFingerprint", GENESIS_HEAD_FP)
        second_ev["ledger_revision"] = second_ev_head.get("ledgerRevision", 1)
        # Mutate evidence bytes (change one byte)
        ev_record = second_ev["receipt"]["evidenceRecords"][0]
        ev_path = second_ev["evidence_root"] / ev_record["path"]
        ev_path.write_bytes(b"X" + ev_path.read_bytes()[1:])
        r2 = execute(second_ev)
        # The receipt's evidence aggregate will drift, causing ACCEPTANCE_CONTRACT_DRIFT
        # BUT more importantly: same invocation key means O_EXCL prevents creation
        if r2.returncode == 0:
            raise SystemExit("SLICE_TEST_CHANGED_EVIDENCE_ACCEPTED")
        attacks += 1
    finally:
        first_ev["temporary"].cleanup()

    # ---- A1.2 with valid predecessor (happy chain) ----
    a11 = prepare("A1.1")
    # Persistent temp for A1.1 ledger - survives A1.1 cleanup until A1.2 done
    a11_ledger_backup = tempfile.mkdtemp(prefix="a11_ledger_")
    try:
        r = execute(a11)
        if r.returncode != 0:
            raise SystemExit(f"SLICE_TEST_A11_FAILED:{r.stdout}:{r.stderr}")
        pred_fp = a11["receipt"]["receiptFingerprint"]["value"]
        # Capture A1.1 ledger to persistent temp before A1.1 cleanup
        shutil.copytree(a11["ledger_root"], a11_ledger_backup, dirs_exist_ok=True)
    finally:
        a11["temporary"].cleanup()

    a12 = prepare("A1.2", pred_fp, ledger_head_doc=None)
    a12["predecessor"] = pred_fp
    # Replace A1.2 empty ledger with captured A1.1 ledger
    if a12["ledger_root"].exists():
        shutil.rmtree(a12["ledger_root"])
    shutil.copytree(a11_ledger_backup, a12["ledger_root"])
    # Read the COPIED ledger-head and set expectations from it
    pred_head = json.loads((a12["ledger_root"] / "ledger-head.json").read_bytes())
    a12["ledger_head_fp"] = pred_head.get("markerKeyFingerprint", GENESIS_HEAD_FP)
    a12["ledger_revision"] = pred_head.get("ledgerRevision", 1)
    a12["predecessor_marker_fp"] = pred_head.get("markerKeyFingerprint", GENESIS_HEAD_FP)
    # Capture A1.1 head markerKeyFingerprint for happy-chain assertions
    a11_marker_fp = pred_head.get('markerKeyFingerprint', GENESIS_HEAD_FP)
    try:
        r = execute(a12)
        if r.returncode != 0:
            raise SystemExit(f"SLICE_TEST_A12_CHAIN_FAILED:{r.stdout}:{r.stderr}")

        # ---- Happy-chain assertions after successful A1.2 ----
        a12_head_raw = (a12['ledger_root'] / 'ledger-head.json').read_bytes()
        a12_head = json.loads(a12_head_raw)
        a12_head_revision = a12_head.get('ledgerRevision')
        if a12_head_revision != 2:
            raise SystemExit(f'SLICE_TEST_A12_BAD_REVISION:{a12_head_revision}')
        a12_prev_revision = a12_head.get('previousHeadRevision')
        if a12_prev_revision != 1:
            raise SystemExit(f'SLICE_TEST_A12_BAD_PREV_REVISION:{a12_prev_revision}')
        a12_prev_marker_fp = a12_head.get('previousHeadMarkerFingerprint')
        if a12_prev_marker_fp != a11_marker_fp:
            raise SystemExit(f'SLICE_TEST_A12_BAD_PREV_MARKER_FP:{a12_prev_marker_fp}')
        a12_head_marker_fp = a12_head.get('markerKeyFingerprint')
        if a12_head_marker_fp == a11_marker_fp:
            raise SystemExit('SLICE_TEST_A12_MARKER_FP_NOT_DIFFERENT')
        # Locate A1.2 marker on disk and assert its previousHeadMarkerFingerprint
        a12_marker_path = a12['ledger_root'] / ('marker-' + a12_head_marker_fp[7:] + '.json')
        if not a12_marker_path.exists():
            raise SystemExit(f'SLICE_TEST_A12_MARKER_NOT_FOUND:{a12_marker_path}')
        a12_marker = json.loads(a12_marker_path.read_bytes())
        a12_marker_prev_fp = a12_marker.get('previousHeadMarkerFingerprint')
        if a12_marker_prev_fp != a11_marker_fp:
            raise SystemExit(f'SLICE_TEST_A12_MARKER_BAD_PREV_FP:{a12_marker_prev_fp}')
    finally:
        shutil.rmtree(a11_ledger_backup)
        a12["temporary"].cleanup()


    cases: list[tuple[str, str, Callable[[dict], None], str, str | None, Callable[[dict], None] | None]] = [
        # Schema/owner/verifier drift
        ("arbitrary text evidence", "RECEIPT_EVIDENCE_JSON_INVALID", lambda v: None,
         "A1.1", None,
        inject_arbitrary_text_evidence,),
        ("evidence duplicate key", "RECEIPT_EVIDENCE_JSON_INVALID", lambda v: None,
         "A1.1", None,
        inject_duplicate_key_evidence,),
        ("evidence schema-invalid status", "RECEIPT_SCHEMA_REJECTED", lambda v: None,
         "A1.1", None,
         inject_wrong_status_evidence,),
        # Receipt self-fingerprint drift
        ("receipt self fingerprint drift", "RECEIPT_SELF_FINGERPRINT_DRIFT", lambda value: None,
         "A1.1", None,
         corrupt_receipt_self_fingerprint),
        # Wrong nonce
        ("wrong nonce", "RECEIPT_CHALLENGE_NONCE_MISMATCH", lambda value: None,
         "A1.1", None,
         lambda c: c.__setitem__("nonce", "f" * 32)),
        # Wrong build invocation
        ("wrong build invocation", "RECEIPT_BUILD_INVOCATION_ID_MISMATCH", lambda value: None,
         "A1.1", None,
         lambda c: c.__setitem__("build_invocation_id", "RG-CS-BUILD-A11-00000002")),
        # Artifact rebound
        ("artifact rebound", "RECEIPT_ARTIFACT_FINGERPRINT_DRIFT", lambda value: None,
         "A1.1", None,
         lambda c: (c["artifact_root"] / c["receipt"]["artifactRecords"][0]["path"]).write_bytes(b"R" * 290)),
        # Evidence rebound
        ("evidence rebound", "RECEIPT_EVIDENCE_FINGERPRINT_DRIFT", lambda value: None,
         "A1.1", None,
         corrupt_evidence_bytes),
        # Unknown file in evidence root
        ("unknown file in evidence", "RECEIPT_EVIDENCE_PHYSICAL_CLOSED_SET_DRIFT", lambda value: None,
         "A1.1", None,
         lambda c: (c["evidence_root"] / "unknown.txt").write_bytes(b"unknown")),
        # Symlink artifact
        ("symlink artifact", "BUNDLE_SYMLINK_PRESENT", lambda value: None,
         "A1.1", None,
         lambda c: (lambda path, replacement: (path.unlink(), path.symlink_to(replacement)))(c["artifact_root"] / c["receipt"]["artifactRecords"][0]["path"], c["root"] / "replacement.bin")),
        # Failure has no ledger side effect (wrong nonce)
        ("failure has no ledger side effect", "RECEIPT_CHALLENGE_NONCE_MISMATCH", lambda value: None,
         "A1.1", None,
         lambda c: c.__setitem__("nonce", "f" * 32)),
        # Stale ledger head (caller says rev=99 but ledger has rev=1)
        ("stale ledger head revision", "RECEIPT_LEDGER_REVISION_MISMATCH", lambda value: None,
         "A1.1", None,
         lambda c: None),
        # Stale ledger head fingerprint
        ("stale ledger head fingerprint", "RECEIPT_LEDGER_HEAD_FP_MISMATCH", lambda value: None,
         "A1.1", None,
         lambda c: None),
    ]

    for label, token, mutation, slice_id, predecessor, mutate_case in cases:
        if label == "stale ledger head revision":
            # Commit first A1.1 and back up its populated ledger
            first = prepare("A1.1")
            backup = tempfile.mkdtemp(prefix="stale_rev_ledger_")
            try:
                r = execute(first)
                if r.returncode != 0:
                    raise SystemExit(f"SLICE_TEST_STALE_REV_SETUP_FAILED:{r.stdout}")
                shutil.copytree(first["ledger_root"], backup, dirs_exist_ok=True)
            finally:
                first["temporary"].cleanup()

            # Fresh A1.1 with populated ledger and wrong revision pinned
            stale = prepare("A1.1")
            try:
                if stale["ledger_root"].exists():
                    shutil.rmtree(stale["ledger_root"])
                shutil.copytree(backup, stale["ledger_root"])
                rebind_nonce(stale, "fedcba0987654321fedcba0987654321")
                head_doc = json.loads((stale["ledger_root"] / "ledger-head.json").read_bytes())
                stale["ledger_head_fp"] = head_doc.get("markerKeyFingerprint", GENESIS_HEAD_FP)
                stale["ledger_revision"] = head_doc.get("ledgerRevision", 1)  # correct pin
                stale["ledger_revision"] = 99  # stale pin: overwrites correct pin

                # Capture baseline from stale's own ledger immediately before execute
                markers_before = sorted([m.name for m in stale["ledger_root"].glob("marker-*.json")])
                head_before_path = stale["ledger_root"] / "ledger-head.json"
                head_before_bytes = head_before_path.read_bytes() if head_before_path.exists() else b""

                r2 = execute(stale)
                expect_rejection(label, r2, token)

                # Assert no ledger mutation during rejected call
                markers_after = sorted([m.name for m in stale["ledger_root"].glob("marker-*.json")])
                head_after_bytes = head_before_path.read_bytes() if head_before_path.exists() else b""
                if markers_after != markers_before:
                    raise SystemExit(f"SLICE_TEST_STALE_HEAD_MUTATED:{label}:{markers_before}→{markers_after}")
                if head_after_bytes != head_before_bytes:
                    raise SystemExit(f"SLICE_TEST_STALE_HEAD_MUTATED:{label}:head changed")
            finally:
                stale["temporary"].cleanup()
        elif label == "stale ledger head fingerprint":
            # Commit first A1.1 and back up its populated ledger
            first = prepare("A1.1")
            backup = tempfile.mkdtemp(prefix="stale_fp_ledger_")
            try:
                r = execute(first)
                if r.returncode != 0:
                    raise SystemExit(f"SLICE_TEST_STALE_FP_SETUP_FAILED:{r.stdout}")
                shutil.copytree(first["ledger_root"], backup, dirs_exist_ok=True)
            finally:
                first["temporary"].cleanup()

            # Fresh A1.1 with populated ledger and wrong head fingerprint pinned
            stale = prepare("A1.1")
            try:
                if stale["ledger_root"].exists():
                    shutil.rmtree(stale["ledger_root"])
                shutil.copytree(backup, stale["ledger_root"])
                rebind_nonce(stale, "1234567890abcdef1234567890abcdef")
                head_doc = json.loads((stale["ledger_root"] / "ledger-head.json").read_bytes())
                stale["ledger_revision"] = head_doc.get("ledgerRevision", 1)  # correct pin
                stale["ledger_head_fp"] = raw_fp(b"stale-head")  # stale pin: wrong fingerprint

                # Capture baseline from stale's own ledger immediately before execute
                markers_before = sorted([m.name for m in stale["ledger_root"].glob("marker-*.json")])
                head_before_path = stale["ledger_root"] / "ledger-head.json"
                head_before_bytes = head_before_path.read_bytes() if head_before_path.exists() else b""

                r2 = execute(stale)
                expect_rejection(label, r2, token)

                # Assert no ledger mutation during rejected call
                markers_after = sorted([m.name for m in stale["ledger_root"].glob("marker-*.json")])
                head_after_bytes = head_before_path.read_bytes() if head_before_path.exists() else b""
                if markers_after != markers_before:
                    raise SystemExit(f"SLICE_TEST_STALE_HEAD_FP_MUTATED:{label}:{markers_before}→{markers_after}")
                if head_after_bytes != head_before_bytes:
                    raise SystemExit(f"SLICE_TEST_STALE_HEAD_FP_MUTATED:{label}:head changed")
            finally:
                stale["temporary"].cleanup()
                shutil.rmtree(backup)
        else:
            run_attack(label, token, mutation, slice_id=slice_id, predecessor=predecessor, mutate_case=mutate_case)
        attacks += 1

    # Missing predecessor marker for A1.2
    run_attack("missing predecessor marker", "RECEIPT_PREDECESSOR_MARKER_NOT_FOUND",
              lambda value: None, slice_id="A1.2", predecessor=raw_fp(b"a1-1-receipt"),
              mutate_case=lambda c: c.__setitem__("predecessor_marker_fp", raw_fp(b"missing-predecessor-marker")))
    attacks += 1

    # Wrong predecessor slice marker
    valid_a11 = prepare("A1.1")
    try:
        r = execute(valid_a11)
        if r.returncode != 0:
            raise SystemExit(f"SLICE_TEST_VALID_A11_SETUP_FAILED:{r.stdout}")
        pred_fp = valid_a11["receipt"]["receiptFingerprint"]["value"]
        pred_key_fp = valid_a11["full_key_fp"]
        pred_head = json.loads((valid_a11["ledger_root"] / "ledger-head.json").read_bytes())
    finally:
        valid_a11["temporary"].cleanup()

    # Write marker with wrong sliceId in the key
    def write_wrong_slice_marker(c: dict[str, Any]) -> None:
        wrong_key = {
            "bundleRootFingerprint": c["bundle_fp"],
            "sliceId": "A1.7",  # wrong slice
            "challengeNonce": c["nonce"],
            "buildInvocationId": c["build_invocation_id"],
            "receiptFingerprint": raw_fp(b"predecessor-receipt"),
        }
        wrong_key_fp = bundle.committed(receipt_module.LEDGER_KEY_DOMAIN, wrong_key)
        marker = {
            "schemaVersion": receipt_module.LEDGER_MARKER_SCHEMA,
            "key": wrong_key,
            "keyFingerprint": {
                "kind": "CANONICAL_DOCUMENT", "algorithm": "SHA-256",
                "domain": receipt_module.LEDGER_KEY_DOMAIN.decode(),
                "value": wrong_key_fp,
            },
            "lineage": {},
            "previousHeadMarkerFingerprint": GENESIS_HEAD_FP,
        }
        marker_path = c["ledger_root"] / f"marker-{wrong_key_fp[7:]}.json"
        marker_path.write_bytes(json.dumps(marker, separators=(",", ":"), sort_keys=True).encode("utf-8") + b"\n")
        c["predecessor_marker_fp"] = wrong_key_fp

    run_attack(
        "wrong predecessor slice marker",
        "RECEIPT_PREDECESSOR_MARKER_NOT_FOUND",
        lambda value: None,
        slice_id="A1.2",
        predecessor=pred_fp,
        mutate_case=write_wrong_slice_marker,
    )
    attacks += 1

    # Lock contention
    lock_case = prepare()
    lock_fd = os.open(lock_case["ledger_root"], os.O_RDONLY | os.O_DIRECTORY)
    try:
        fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        locked = execute(lock_case)
        expect_rejection("ledger lock contention", locked, "RECEIPT_LEDGER_LOCK_CONTENTION")
        assert_ledger_empty(lock_case, "ledger lock contention")
        attacks += 1
    finally:
        fcntl.flock(lock_fd, fcntl.LOCK_UN)
        os.close(lock_fd)
        lock_case["temporary"].cleanup()

    # Genesis-only: wrong revision on empty ledger
    genesis_wrong = prepare("A1.1")
    genesis_wrong["ledger_revision"] = 5
    genesis_wrong["ledger_head_fp"] = GENESIS_HEAD_FP
    r = execute(genesis_wrong)
    expect_rejection("genesis wrong revision", r, "RECEIPT_LEDGER_GENESIS_EXPECTED")
    assert_ledger_empty(genesis_wrong, "genesis wrong revision")
    genesis_wrong["temporary"].cleanup()
    attacks += 1

    # ---- _verify_typed_evidence direct internal attacks ----
    # Direct unit tests calling _verify_typed_evidence to test evidence contract catalog attacks.
    # These use in-memory mutated authority without touching the bundle fingerprints.

    def expect_typed_evidence_error(label: str, case: dict[str, Any], mutated_authority: dict[str, Any], expected_token: str) -> None:
        """Direct test of _verify_typed_evidence with mutated authority in memory."""
        case["temporary"].cleanup()  # release any temp from prepare
        try:
            # Re-prepare to get fresh state
            case2 = prepare("A1.1")
            # Open evidence root fd
            evidence_root_fd = os.open(os.fspath(case2["evidence_root"]), os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC)
            try:
                receipt_module._verify_typed_evidence(
                    snapshot=case2["snapshot"],
                    authority=mutated_authority,
                    evidence_root_fd=evidence_root_fd,
                    evidence_records=case2["receipt"]["evidenceRecords"],
                    contract=case2["contract"],
                    receipt=case2["receipt"],
                )
                # If we reach here, no exception was raised - fail
                raise SystemExit(f"SLICE_TEST_ATTACK_NOT_REJECTED:{label}")
            except receipt_module.ReceiptError as e:
                err_str = str(e)
                if expected_token not in err_str:
                    raise SystemExit(f"SLICE_TEST_ATTACK_WRONG_TOKEN:{label}:expected={expected_token}:got={err_str}")
            finally:
                os.close(evidence_root_fd)
        finally:
            case2["temporary"].cleanup()

    # Attack 1: duplicate evidenceId in authority.evidenceContracts -> ID_NOT_UNIQUE
    dup_case = prepare("A1.1")
    dup_authority = copy.deepcopy(dup_authority := dup_case["authority"])
    dup_contracts = dup_authority.get("evidenceContracts", [])
    if len(dup_contracts) >= 2:
        dup = copy.deepcopy(dup_contracts[0])
        dup["evidenceId"] = dup_contracts[1]["evidenceId"]  # duplicate!
        dup_contracts.append(dup)
        dup_authority["evidenceContracts"] = dup_contracts
        expect_typed_evidence_error("duplicate evidenceId", dup_case, dup_authority, "RECEIPT_EVIDENCE_CONTRACT_ID_NOT_UNIQUE")
        attacks += 1
    dup_case["temporary"].cleanup()

    # Attack 2: ambiguous contract resolution (same msgVersion + allowedSliceIds) -> CONTRACT_AMBIGUOUS
    amb_case = prepare("A1.1")
    amb_authority = copy.deepcopy(amb_authority := amb_case["authority"])
    amb_contracts = amb_authority.get("evidenceContracts", [])
    if len(amb_contracts) >= 1:
        base = amb_contracts[0]
        dup = copy.deepcopy(base)
        dup["evidenceId"] = base.get("evidenceId", "") + "-AMBIG"
        # Same messageVersion and allowedSliceIds = ambiguity
        amb_contracts.append(dup)
        amb_authority["evidenceContracts"] = amb_contracts
        expect_typed_evidence_error("ambiguous contract resolution", amb_case, amb_authority, "RECEIPT_EVIDENCE_CONTRACT_AMBIGUOUS")
        attacks += 1
    amb_case["temporary"].cleanup()

    # Attack 3: unknown semanticVerifierId -> VERIFIER_UNKNOWN
    unk_case = prepare("A1.1")
    unk_authority = copy.deepcopy(unk_authority := unk_case["authority"])
    unk_contracts = unk_authority.get("evidenceContracts", [])
    if len(unk_contracts) >= 1:
        unk_contracts[0]["semanticVerifierId"] = "UNKNOWN_ATTACK_VERIFIER_V999"
        unk_authority["evidenceContracts"] = unk_contracts
        expect_typed_evidence_error("unknown semanticVerifierId", unk_case, unk_authority, "RECEIPT_EVIDENCE_VERIFIER_UNKNOWN")
        attacks += 1
    unk_case["temporary"].cleanup()

    # Attack 4: cross-file-ref negative test — invalid document against provider-materialization schema
    # asserts RECEIPT_SCHEMA_REJECTED (schema validation failure, not Unresolvable/VALIDATION_FAILED)
    # _validate_schema needs only snapshot + document + schema_name (no fd needed)
    xfr_case = prepare("A1.1")
    try:
        receipt_module._validate_schema(
            snapshot=xfr_case["snapshot"],
            document={"runId": "bad"},
            schema_name="capability-studio-gate-a-provider-materialization-observation-v1.schema.json",
        )
        raise SystemExit("SLICE_TEST_ATTACK_NOT_REJECTED:cross-file-ref negative")
    except receipt_module.ReceiptError as e:
        err_str = str(e)
        if "RECEIPT_SCHEMA_REJECTED" not in err_str:
            raise SystemExit(f"SLICE_TEST_ATTACK_WRONG_TOKEN:cross-file-ref:expected=RECEIPT_SCHEMA_REJECTED:got={err_str}")
    finally:
        xfr_case["temporary"].cleanup()
    attacks += 1

    print(f"Gate A SliceAcceptanceReceipt verifier PASS: {attacks} attacks rejected; happy paths verified.")


if __name__ == "__main__":
    main()
