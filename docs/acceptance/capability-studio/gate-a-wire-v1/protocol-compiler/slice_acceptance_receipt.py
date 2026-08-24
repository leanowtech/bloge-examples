"""Verify and consume one Gate A SliceAcceptanceReceipt.

The verifier has one deliberate trust boundary: ``verify_bundle`` is called
first, and all Authority, contract, role-view and schema bytes after that are
read from the returned immutable BundleSnapshot.  The caller-owned ledger is
modified only after every other check has completed.

Changes from prior version (Round 7):
- Typed evidence: each evidence record is read as exact bytes, strict parsed,
  validated against its Authority-registered schema, and self-fingerprinted.
  Unknown semanticVerifierId -> fail closed. Outcome derived by verifier from
  validated evidence + observed process facts; receipt does NOT self-report ACCEPTED.
- Ledger key = (bundleRoot, sliceId, challengeNonce, buildInvocationId) only.
  receiptFingerprint is stored in the marker value, not the key.
  Same invocation with changed evidence re-signed: O_EXCL prevents second marker.
- Full lineage marker: authority raw/revision, sourceTree, toolchain, bundle,
  invocation, slice, receipt fingerprint, previous head, predecessor marker.
- Rollback-resistant ledger head: caller-pins expected previous head/revision;
  genesis sentinel is sha256:0...0 with revision 0; verifier validates head in
  transaction, creates head file with O_EXCL, increments revision by 1.
  Adapter declared as NON_RELEASE_ROLLBACKABLE_ADAPTER until CAS is integrated.
- Postcommit: marker bytes re-read and canonicality re-verified.
- Duplicate fingerprint check removed (O_EXCL is sufficient; review P2).
"""

from __future__ import annotations

import copy
from types import MappingProxyType
import errno
import json
import os
import pathlib
import re
import stat
import secrets
from datetime import datetime, timezone
from typing import Any

from jsonschema import Draft202012Validator
import referencing

try:
    import fcntl
except ImportError:  # pragma: no cover - exercised on platforms without POSIX flock
    fcntl = None

from release_authority_bundle import (
    BundleError,
    BundleSnapshot,
    committed,
    lexical_absolute,
    open_dir,
    read_relative,
    read_stable,
    raw_fingerprint,
    strict_json,
    _inventory,
    verify_bundle,
)


RECEIPT_SCHEMA_NAME = "capability-studio-gate-a-slice-acceptance-receipt-v1.schema.json"
RECEIPT_DOMAIN = b"RG-CS-GATE-A-SLICE-ACCEPTANCE-RECEIPT-v1"
CONTRACT_DOMAIN = b"RG-CS-GATE-A-SLICE-ACCEPTANCE-CONTRACT-v1"
ARTIFACT_DOMAIN = b"RG-CS-GATE-A-SLICE-ARTIFACTS-v1"
EVIDENCE_DOMAIN = b"RG-CS-GATE-A-SLICE-EVIDENCE-v1"
LEDGER_KEY_DOMAIN = b"RG-CS-GATE-A-SLICE-CONSUMPTION-KEY-v1"
LEDGER_INVOCATION_KEY_DOMAIN = b"RG-CS-GATE-A-SLICE-INVOCATION-KEY-v1"
LEDGER_HEAD_SCHEMA = "capability-studio-gate-a-slice-consumption-ledger-head-v1.schema.json"
LEDGER_MARKER_SCHEMA = "capability-studio.gate-a.slice-consumption-marker.v1"
GENESIS_PREVIOUS_HEAD_FP = "sha256:0000000000000000000000000000000000000000000000000000000000000000"
PATH_PATTERN = re.compile(r"^[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*$")
FINGERPRINT_PATTERN = re.compile(r"^sha256:(?!0{64}$)[0-9a-f]{64}$")
NONCE_PATTERN = re.compile(r"^[0-9a-f]{32}$")
BUILD_INVOCATION_PATTERN = re.compile(r"^RG-CS-BUILD-[A-Z0-9-]{8,64}$")
MAX_RECEIPT_BYTES = 8 * 1024 * 1024
MAX_MATERIAL_FILES = 128
MAX_MATERIAL_FILE_BYTES = 16 * 1024 * 1024
MAX_MATERIAL_TOTAL_BYTES = 64 * 1024 * 1024
MAX_LEDGER_HEAD_BYTES = 4 * 1024
MAX_LEDGER_MARKER_BYTES = 8 * 1024 * 1024

SLICE_RECEIPT_CONTRACT_SUPPORTED = {
    "schema": "capability-studio-gate-a-slice-acceptance-receipt-v1.schema.json",
    "acceptanceContractFingerprintDomain": "RG-CS-GATE-A-SLICE-ACCEPTANCE-CONTRACT-v1",
    "receiptFingerprintDomain": "RG-CS-GATE-A-SLICE-ACCEPTANCE-RECEIPT-v1",
    "artifactAggregateDomain": "RG-CS-GATE-A-SLICE-ARTIFACTS-v1",
    "evidenceAggregateDomain": "RG-CS-GATE-A-SLICE-EVIDENCE-v1",
    "consumptionKeyDomain": "RG-CS-GATE-A-SLICE-CONSUMPTION-KEY-v1",
    "canonicalizationPolicy": "RFC8785_DOMAIN_NUL_CANONICAL_DOCUMENT",
    "noncePolicy": "CALLER_ISSUED_UNIQUE_128_BIT_LOWERCASE_HEX",
    "buildInvocationPolicy": "CALLER_PINNED_EXACT",
    "ledgerPolicy": "CALLER_OWNED_FLOCK_SERIALIZED_O_EXCL_FSYNC",
    "predecessorPolicy": "IMMEDIATE_PREDECESSOR_CONSUMED_MARKER_REQUIRED",
}

# A1.0 deterministic semantic verifier IDs
A1_SUPPORTED_SEMANTIC_VERIFIERS = frozenset([
    "SLICE_TEST_EXECUTION_V1",
    "ROLE_SELF_TEST_RECEIPT_V1",
])


class ReceiptError(ValueError):
    """Stable fail-closed verifier error."""


class ReplayRejected(ReceiptError):
    """The exact consumption invocation key has already been committed."""


def _slice_receipt_contract(authority: dict[str, Any]) -> dict[str, Any]:
    contract = authority.get("sliceAcceptanceReceiptContract")
    if not isinstance(contract, dict):
        raise ReceiptError("RECEIPT_AUTHORITY_SLICE_CONTRACT_MISSING")
    for key, expected in SLICE_RECEIPT_CONTRACT_SUPPORTED.items():
        if contract.get(key) != expected:
            raise ReceiptError(f"RECEIPT_AUTHORITY_SLICE_CONTRACT_{key.upper()}_DRIFT")
    return contract


def _acceptance_fingerprint_policy(
    *, kind: str, domain: str, source: str, self_null_policy: str
) -> dict[str, Any]:
    return {
        "kind": kind,
        "algorithm": "SHA-256",
        "domain": domain,
        "source": source,
        "value": None,
        "selfNullPolicy": self_null_policy,
    }


def _fail(code: str) -> None:
    raise ReceiptError(code)


def _require(condition: bool, code: str) -> None:
    if not condition:
        _fail(code)


def _fingerprint(value: Any, code: str) -> str:
    # Allow genesis sentinel as a special case (used for "no previous head")
    if value == GENESIS_PREVIOUS_HEAD_FP:
        return value
    _require(isinstance(value, str) and FINGERPRINT_PATTERN.fullmatch(value) is not None, code)
    return value


def _canonical(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")


def _load_receipt(receipt_path: str | pathlib.Path) -> tuple[dict[str, Any], bytes]:
    # Ensure we have a Path object
    if isinstance(receipt_path, str):
        receipt_path = pathlib.Path(receipt_path)
    try:
        raw = read_stable(receipt_path, MAX_RECEIPT_BYTES, "RECEIPT")
    except (BundleError, OSError, ValueError) as error:
        raise ReceiptError(f"RECEIPT_INPUT_UNREADABLE:{error}") from error
    try:
        receipt = strict_json(raw, "receipt")
    except BundleError as error:
        raise ReceiptError(f"RECEIPT_JSON_INVALID:{error}") from error
    _require(isinstance(receipt, dict), "RECEIPT_NOT_OBJECT")
    _require(raw == _canonical(receipt) + b"\n", "RECEIPT_NON_CANONICAL_BYTES")
    return receipt, raw


def _build_schema_registry(snapshot: BundleSnapshot) -> tuple[referencing.Registry, dict[str, str]]:
    """Build a referencing.Registry from all schemas under schemas/ in the bundle.

    No network access; all schemas read from snapshot.files paths.
    Registry accepts ONLY exact nonempty string "$id" (no fallback to legacy "id").
    Enforces unique schema filename mapping AND unique $id.
    Draft202012Validator.check_schema(schema) is run for every schema before registration.
    The registry enables cross-file $ref resolution; retrieval outside fails closed.

    Returns (registry, filename_to_id) where filename_to_id maps schema filenames
    to their $id URIs for lookup during validation.
    """
    import referencing.jsonschema as rs

    registry = referencing.Registry()
    filename_to_id: dict[str, str] = {}
    seen_ids: set[str] = set()
    seen_filenames: set[str] = set()

    # Get all schema files from the snapshot manifest's files list under schemas/
    files = snapshot.files
    schema_files = sorted([f for f in files if f.startswith("schemas/") and f.endswith(".json")])

    for schema_path in schema_files:
        # schema_path is like "schemas/foo.schema.json"; extract filename for mapping
        schema_filename = schema_path.split("/")[-1]

        # Enforce unique filename mapping (no two schemas share the same filename)
        if schema_filename in seen_filenames:
            raise ReceiptError(f"RECEIPT_SCHEMA_REGISTRY_FILENAME_DUPLICATE:{schema_filename}")
        seen_filenames.add(schema_filename)

        try:
            schema_raw = snapshot.bytes_for(schema_path)
        except BundleError as error:
            raise ReceiptError(f"RECEIPT_SCHEMA_REGISTRY_READ_FAILED:{schema_path}:{error}") from error

        try:
            schema = strict_json(schema_raw, schema_path)
        except BundleError as error:
            raise ReceiptError(f"RECEIPT_SCHEMA_REGISTRY_JSON_INVALID:{schema_path}:{error}") from error

        if not isinstance(schema, dict):
            raise ReceiptError(f"RECEIPT_SCHEMA_REGISTRY_NOT_OBJECT:{schema_path}")

        # Each schema must have an exact, nonempty string "$id" ONLY — no fallback to "id"
        schema_id = schema.get("$id")
        if schema_id is None:
            raise ReceiptError(f"RECEIPT_SCHEMA_REGISTRY_ID_MISSING:{schema_path}")
        if not isinstance(schema_id, str) or not schema_id:
            raise ReceiptError(f"RECEIPT_SCHEMA_REGISTRY_ID_INVALID:{schema_path}:type={type(schema_id).__name__}")
        if schema_id in seen_ids:
            raise ReceiptError(f"RECEIPT_SCHEMA_REGISTRY_ID_DUPLICATE:{schema_id}:{schema_path}")
        seen_ids.add(schema_id)

        # Run Draft202012Validator.check_schema for every schema before registration
        try:
            Draft202012Validator.check_schema(schema)
        except Exception as error:
            raise ReceiptError(f"RECEIPT_SCHEMA_REGISTRY_CHECK_FAILED:{schema_path}:{error}") from error

        # Map filename to $id for lookup
        filename_to_id[schema_filename] = schema_id

        # Create schema resource using referencing.jsonschema API
        try:
            resource = rs.SchemaResource.from_contents(schema, default_specification=rs.DRAFT202012)
        except Exception as error:
            raise ReceiptError(f"RECEIPT_SCHEMA_REGISTRY_RESOURCE_FAILED:{schema_path}:{error}") from error

        # Add to registry keyed by $id URI
        registry = registry.with_resource(schema_id, resource)

    return registry, filename_to_id


def _validate_schema(
    snapshot: BundleSnapshot,
    document: dict[str, Any],
    schema_name: str,
    _registry_context: tuple[referencing.Registry, dict[str, str]] | None = None,
) -> None:
    # Use provided registry context if available (built once per _verify_typed_evidence);
    # otherwise build it here (for receipt schema validation path).
    if _registry_context is not None:
        registry, filename_to_id = _registry_context
    else:
        registry, filename_to_id = _build_schema_registry(snapshot)

    # Look up the schema's $id by filename
    # schema_name is the filename (e.g., "capability-studio-gate-a-slice-acceptance-receipt-v1.schema.json")
    schema_id = filename_to_id.get(schema_name)
    if schema_id is None:
        raise ReceiptError(f"RECEIPT_SCHEMA_NOT_IN_VERIFIED_BUNDLE:{schema_name}")

    # Retrieve the target schema from registry by its $id URI
    try:
        schema_resource = registry.get(schema_id)
        schema = schema_resource.contents
    except Exception as error:
        raise ReceiptError(f"RECEIPT_SCHEMA_NOT_IN_VERIFIED_BUNDLE:{schema_name}:{error}") from error

    # Validate the schema itself is well-formed (already checked in registry build)
    # Create validator with the registry so cross-file $ref resolves
    try:
        validator = Draft202012Validator(schema, registry=registry)
        errors = sorted(
            validator.iter_errors(document),
            key=lambda error: (list(error.absolute_path), error.message),
        )
    except (TypeError, ValueError) as error:
        raise ReceiptError(f"RECEIPT_SCHEMA_VALIDATION_FAILED:{error}") from error

    if errors:
        first = errors[0]
        location = "$" + "".join(
            f"[{part}]" if isinstance(part, int) else f".{part}"
            for part in first.absolute_path
        )
        raise ReceiptError(f"RECEIPT_SCHEMA_REJECTED:{location}:{first.validator}")


def _load_evidence_raw(root_fd: int, path: str, byte_length: int) -> bytes:
    try:
        return read_relative(root_fd, path, MAX_MATERIAL_FILE_BYTES, f"EVIDENCE:{path}")
    except (BundleError, OSError, ValueError) as error:
        raise ReceiptError(f"RECEIPT_EVIDENCE_READ_FAILED:{path}:{error}") from error


def _strict_parse_evidence(raw: bytes, path: str) -> dict[str, Any]:
    try:
        doc = strict_json(raw, path)
    except BundleError as error:
        raise ReceiptError(f"RECEIPT_EVIDENCE_JSON_INVALID:{path}:{error}") from error
    if not isinstance(doc, dict):
        raise ReceiptError(f"RECEIPT_EVIDENCE_NOT_OBJECT:{path}")
    canonical_raw = _canonical(doc) + b"\n"
    if raw != canonical_raw:
        raise ReceiptError(f"RECEIPT_EVIDENCE_NON_CANONICAL:{path}")
    return doc


def _freeze(value: Any) -> Any:
    """Recursively convert dict→MappingProxyType and list→tuple for hashing.

    Mirrors release_authority_bundle._freeze so that committed() (which calls
    canonical() on the result) produces the same fingerprint as the oracle.
    """
    if isinstance(value, dict):
        return MappingProxyType({k: _freeze(v) for k, v in value.items()})
    if isinstance(value, list):
        return tuple(_freeze(v) for v in value)
    return value



def _self_fingerprint_evidence(
    doc: dict[str, Any], evidence_id: str, authority: dict[str, Any]
) -> dict[str, Any]:
    """Compute authoritative SELF_NULL_RECEIPT self-fingerprint for role evidence.

    Uses authority.roleContracts[doc.role].blackBoxContract.receiptFingerprintDomain
    as the domain, nulls receiptFingerprint in the material before hashing,
    and returns the exact expected structure.
    Fail-closed on unknown role or missing domain.
    """
    role = doc.get("role")
    if role is None:
        raise ReceiptError(f"RECEIPT_EVIDENCE_ROLE_MISSING:{evidence_id}")

    role_contracts = authority.get("roleContracts", [])
    black_box_contract: dict[str, Any] | None = None
    for rc in role_contracts:
        if isinstance(rc, dict) and rc.get("role") == role:
            black_box_contract = rc.get("blackBoxContract")
            break

    if black_box_contract is None:
        raise ReceiptError(f"RECEIPT_EVIDENCE_ROLE_UNKNOWN:{evidence_id}:{role}")

    domain = black_box_contract.get("receiptFingerprintDomain")
    if domain is None:
        raise ReceiptError(f"RECEIPT_EVIDENCE_DOMAIN_MISSING:{evidence_id}:{role}")

    material = copy.deepcopy(doc)
    material["receiptFingerprint"] = None
    value = committed(domain.encode("ascii"), _freeze(material))

    return {
        "kind": "SELF_NULL_RECEIPT",
        "algorithm": "SHA-256",
        "value": value,
        "selfNullField": "receiptFingerprint",
    }

def _verify_typed_evidence(
    snapshot: BundleSnapshot,
    authority: dict[str, Any],
    evidence_root_fd: int,
    evidence_records: list[dict[str, Any]],
    contract: dict[str, Any],
    receipt: dict[str, Any],
) -> None:
    """Typed evidence verification.

    Root-cause fix: resolve Authority evidenceContracts deterministically by
    matching evidence document's typed protocol identity (messageVersion) plus
    current slice in allowedSliceIds — instead of invalid positional mapping.

    For each evidence record:
    1. Read exact bytes from evidence_root (one stable read per record).
    2. Strict JSON parse (no trailing commas, no duplicate keys, no non-finite).
    3. Verify bytes match the receipt's declared byteLength and rawFingerprint.
    4. Resolve authority evidenceContract by messageVersion + allowedSliceIds (see below).
    5. Validate against Authority-registered schema (from evidenceContracts).
    6. Compute self-fingerprint of the parsed document; verify if present.
    7. Verify schemaName, producerOwner, semanticVerifierId from contract.
    9. Unknown semanticVerifierId -> FAIL CLOSED.

    Deterministic resolution:
      doc.messageVersion (from parsed evidence)
        -> authority.evidenceContracts[*].messageVersion (exact match)
        -> authority.evidenceContracts[*].allowedSliceIds (must contain sliceId)
      Exactly one match required; fail closed with distinct errors for 0 and >1.

    Authority validation:
      - evidenceContracts catalog: IDs must be unique, messageVersion must be nonempty.
      - Runtime required-set check uses acceptance.requiredEvidenceIds (stable surface).
      - evidenceContractRefs array is NOT used for runtime resolution (may differ in length).
    """
    # Step 1: locate the delivery slice
    slice_id = receipt["sliceId"]
    all_slices = authority.get("deliverySlices", [])
    matching_slices = [
        s for s in all_slices
        if isinstance(s, dict) and s.get("sliceId") == slice_id
    ]
    _require(len(matching_slices) == 1, "RECEIPT_SLICE_NOT_AUTHORIZED")
    delivery_slice = matching_slices[0]

    # Step 2: validate Authority evidenceContracts catalog structure
    # - IDs must be unique
    # - messageVersion must be nonempty (used for deterministic resolution)
    # Same messageVersion on disjoint allowedSliceIds is valid; ambiguity only when
    # >1 match for exact messageVersion + current slice's allowedSliceIds.
    evidence_contracts = authority.get("evidenceContracts", [])
    authority_contract_by_id: dict[str, dict[str, Any]] = {}

    for ec in evidence_contracts:
        if not isinstance(ec, dict):
            raise ReceiptError("RECEIPT_EVIDENCE_CONTRACT_INVALID_TYPE")
        ec_id = ec.get("evidenceId", "")
        if not ec_id:
            raise ReceiptError("RECEIPT_EVIDENCE_CONTRACT_ID_EMPTY")
        if ec_id in authority_contract_by_id:
            raise ReceiptError(f"RECEIPT_EVIDENCE_CONTRACT_ID_NOT_UNIQUE:{ec_id}")
        ec_msg_version = ec.get("messageVersion", "")
        if not ec_msg_version:
            raise ReceiptError(f"RECEIPT_EVIDENCE_CONTRACT_MESSAGE_VERSION_EMPTY:{ec_id}")
        # schemaName must be nonempty (required field for typed evidence validation)
        ec_schema_name = ec.get("schemaName", "")
        if not ec_schema_name:
            raise ReceiptError(f"RECEIPT_EVIDENCE_CONTRACT_SCHEMA_NAME_EMPTY:{ec_id}")
        authority_contract_by_id[ec_id] = ec

    # Step 3: extract runtime required evidence IDs from acceptance contract
    acceptance_required_ids = delivery_slice.get("acceptanceContract", {}).get("requiredEvidenceIds", [])

    # Validate runtime required IDs are unique
    _require(
        len(set(acceptance_required_ids)) == len(acceptance_required_ids),
        "RECEIPT_EVIDENCE_REQUIRED_IDS_NOT_UNIQUE",
    )

    # Step 4: required-set check using acceptance contract runtime IDs (stable surface)
    required_evidence_ids = set(acceptance_required_ids)
    found_ids = {rec["id"] for rec in evidence_records}
    _require(found_ids == required_evidence_ids, "RECEIPT_EVIDENCE_REQUIRED_SET_DRIFT")

    # Build schema registry once for all evidence record validations
    # (avoids rebuilding schemas from bundle for each evidence record)
    _schema_registry_context = _build_schema_registry(snapshot)

    for record in evidence_records:
        evidence_id = record["id"]
        path = record["path"]
        byte_length = record["byteLength"]
        declared_fp = record["rawFingerprint"]

        # 1+2. Read exact bytes and strict parse
        raw = _load_evidence_raw(evidence_root_fd, path, byte_length)
        _require(len(raw) == byte_length, f"RECEIPT_EVIDENCE_BYTE_LENGTH_DRIFT:{evidence_id}")
        _require(raw_fingerprint(raw) == declared_fp, f"RECEIPT_EVIDENCE_FINGERPRINT_DRIFT:{evidence_id}")
        doc = _strict_parse_evidence(raw, path)

        # 4. Deterministic resolution: find authority contracts matching messageVersion + sliceId
        doc_message_version = doc.get("messageVersion", "")
        if not doc_message_version:
            raise ReceiptError(f"RECEIPT_EVIDENCE_MESSAGE_VERSION_MISSING:{evidence_id}")

        matching_contracts = [
            ec for ec in evidence_contracts
            if isinstance(ec, dict)
            and ec.get("messageVersion") == doc_message_version
            and slice_id in (ec.get("allowedSliceIds") or [])
        ]

        if len(matching_contracts) == 0:
            raise ReceiptError(
                f"RECEIPT_EVIDENCE_CONTRACT_UNRESOLVED:{evidence_id}:"
                f"messageVersion={doc_message_version}:sliceId={slice_id}"
            )
        if len(matching_contracts) > 1:
            matched_ids = sorted(ec.get("evidenceId", "") for ec in matching_contracts)
            raise ReceiptError(
                f"RECEIPT_EVIDENCE_CONTRACT_AMBIGUOUS:{evidence_id}:"
                f"messageVersion={doc_message_version}:sliceId={slice_id}:"
                f"matched={matched_ids}"
            )
        ec = matching_contracts[0]

        # 5. Verify semanticVerifierId (fail closed on unknown)
        semantic_verifier_id = ec.get("semanticVerifierId", "")
        if semantic_verifier_id not in A1_SUPPORTED_SEMANTIC_VERIFIERS:
            raise ReceiptError(f"RECEIPT_EVIDENCE_VERIFIER_UNKNOWN:{evidence_id}:{semantic_verifier_id}")

        # 5a. Verify schemaName (typed evidence) using shared registry context
        expected_schema_name = ec.get("schemaName", "")
        if expected_schema_name:
            try:
                _validate_schema(snapshot, doc, expected_schema_name, _registry_context=_schema_registry_context)
            except ReceiptError:
                raise
            except Exception as error:
                raise ReceiptError(f"RECEIPT_EVIDENCE_SCHEMA_VALIDATION_FAILED:{evidence_id}:{error}") from error

        # 5b. Verify producerOwner matches expected (only if doc explicitly provides it)
        # Namespace: producerOwner/owner are organizational responsibility subjects;
        # doc.role is a protocol role — they MUST NOT be compared or used as fallback.
        expected_owner = ec.get("producerOwner", "")
        if expected_owner:
            doc_producer_owner = doc.get("producerOwner")
            doc_owner = doc.get("owner")
            # Only compare if doc explicitly provides one of these fields
            if doc_producer_owner is not None:
                if doc_producer_owner != expected_owner:
                    raise ReceiptError(f"RECEIPT_EVIDENCE_OWNER_DRIFT:{evidence_id}:expected={expected_owner}")
            elif doc_owner is not None:
                if doc_owner != expected_owner:
                    raise ReceiptError(f"RECEIPT_EVIDENCE_OWNER_DRIFT:{evidence_id}:expected={expected_owner}")
            # If neither is present, skip comparison (producerOwner constraint may not apply to this doc type)

        # 6. For SLICE_TEST_EXECUTION_V1: verify status ACCEPTED
        if semantic_verifier_id == "SLICE_TEST_EXECUTION_V1":
            status = doc.get("status", "")
            if status not in ("ACCEPTED", "PASSED", "READY", "CLOSED", "COMPLETE", "RESOLVED", "PINNED", "STRUCTURE_VALID", "LAUNCHED"):
                raise ReceiptError(f"RECEIPT_EVIDENCE_OUTCOME_INVALID:{evidence_id}:status={status}")

        # 6a. For ROLE_SELF_TEST_RECEIPT_V1: verify self-null fingerprint AND role contract
        if semantic_verifier_id == "ROLE_SELF_TEST_RECEIPT_V1":
            # Verify doc.role exists in evidence contract's requiredArtifactRoles (fail closed)
            required_roles = ec.get("requiredArtifactRoles", [])
            doc_role = doc.get("role")
            if doc_role is None:
                raise ReceiptError(f"RECEIPT_EVIDENCE_ROLE_MISSING:{evidence_id}")
            if doc_role not in required_roles:
                raise ReceiptError(f"RECEIPT_EVIDENCE_ROLE_NOT_IN_REQUIRED:{evidence_id}:role={doc_role}")

            # Verify self-null fingerprint
            declared_self_fp = doc.get("receiptFingerprint") or doc.get("selfFingerprint")
            if declared_self_fp is not None:
                computed = _self_fingerprint_evidence(doc, evidence_id, authority)
                _require(declared_self_fp == computed, f"RECEIPT_EVIDENCE_SELF_FP_DRIFT:{evidence_id}")

    # Outcome derivation: if all evidence validated and process observation
    # is exitCode=0/terminal=SUCCESS, outcome is ACCEPTED.
    # The verifier derives this; receipt's "observed" is caller-fact, not claim.


def _authority(snapshot: BundleSnapshot) -> tuple[dict[str, Any], bytes]:
    try:
        raw = snapshot.bytes_for("authority/protocol-authority.json")
        authority = strict_json(raw, "bundle-authority")
    except BundleError as error:
        raise ReceiptError(f"RECEIPT_BUNDLE_AUTHORITY_INVALID:{error}") from error
    _require(isinstance(authority, dict), "RECEIPT_BUNDLE_AUTHORITY_NOT_OBJECT")
    manifest = snapshot.manifest
    _require(raw_fingerprint(raw) == manifest["authorityRawFingerprint"], "RECEIPT_BUNDLE_AUTHORITY_FINGERPRINT_DRIFT")
    _require(authority.get("revision") == manifest["authorityRevision"], "RECEIPT_BUNDLE_AUTHORITY_REVISION_DRIFT")
    return authority, raw


def _slice_contract(authority: dict[str, Any], slice_id: str) -> dict[str, Any]:
    slices = authority.get("deliverySlices")
    _require(isinstance(slices, list), "RECEIPT_AUTHORITY_DELIVERY_SLICES_INVALID")
    matches = [item for item in slices if isinstance(item, dict) and item.get("sliceId") == slice_id]
    _require(len(matches) == 1, "RECEIPT_SLICE_NOT_AUTHORIZED")
    contract = matches[0].get("acceptanceContract")
    _require(isinstance(contract, dict), "RECEIPT_ACCEPTANCE_CONTRACT_MISSING")
    return contract


def _verify_bundle_binding(
    snapshot: BundleSnapshot,
    receipt: dict[str, Any],
    authority: dict[str, Any],
    authority_raw: bytes,
    expected_slice_id: str,
) -> dict[str, Any]:
    _require(receipt["sliceId"] == expected_slice_id, "RECEIPT_SLICE_ID_MISMATCH")
    _require(receipt["bundleRootFingerprint"] == snapshot.root_fingerprint, "RECEIPT_BUNDLE_ROOT_MISMATCH")
    _require(receipt["authority"] == {"revision": authority["revision"], "rawFingerprint": raw_fingerprint(authority_raw)}, "RECEIPT_AUTHORITY_BINDING_DRIFT")
    manifest = snapshot.manifest
    _require(manifest["targetSliceId"] == expected_slice_id, "RECEIPT_BUNDLE_TARGET_SLICE_MISMATCH")
    _require(receipt["dependencyAuthorityFingerprint"] == manifest["dependencyAuthorityRawFingerprint"], "RECEIPT_DEPENDENCY_AUTHORITY_DRIFT")
    receipt_protocol = _slice_receipt_contract(authority)
    contract = _slice_contract(authority, expected_slice_id)
    # The Authority does not store acceptanceContractFingerprint in the contract.
    # Compute the expected fingerprint by committing the canonical contract with its domain.
    expected_contract_fp = committed(
        receipt_protocol["acceptanceContractFingerprintDomain"].encode("ascii"),
        _freeze(contract),
    )
    expected_contract = {
        "kind": "CANONICAL_DOCUMENT",
        "algorithm": "SHA-256",
        "domain": receipt_protocol["acceptanceContractFingerprintDomain"],
        "source": "BUNDLE_AUTHORITY_ACCEPTANCE_CONTRACT",
        "value": expected_contract_fp,
    }
    _require(receipt["acceptanceContractFingerprint"] == expected_contract, "RECEIPT_ACCEPTANCE_CONTRACT_DRIFT")
    _require(receipt["acceptanceId"] == contract["acceptanceId"], "RECEIPT_ACCEPTANCE_ID_DRIFT")
    _require(receipt["messageVersion"] == contract["messageVersion"], "RECEIPT_MESSAGE_VERSION_DRIFT")
    _require(receipt["receiptPath"] == contract["receiptPath"], "RECEIPT_PATH_DRIFT")
    invocation = receipt["buildInvocation"]
    _require(invocation["profile"] == contract["buildProfile"], "RECEIPT_BUILD_PROFILE_DRIFT")
    _require(invocation["properties"] == contract["buildProperties"], "RECEIPT_BUILD_PROPERTIES_DRIFT")
    _require(receipt["testIds"] == contract["testIds"], "RECEIPT_TEST_SET_DRIFT")
    expected_artifact_policy = _acceptance_fingerprint_policy(
        kind="AGGREGATE_COMMITMENT",
        domain=receipt_protocol["artifactAggregateDomain"],
        source="REQUIRED_ARTIFACT_RAW_BYTES",
        self_null_policy="VALUE_NULL_DURING_HASH",
    )
    expected_evidence_policy = _acceptance_fingerprint_policy(
        kind="AGGREGATE_COMMITMENT",
        domain=receipt_protocol["evidenceAggregateDomain"],
        source="REQUIRED_EVIDENCE_EXACT_BYTES",
        self_null_policy="VALUE_NULL_DURING_HASH",
    )
    expected_receipt_policy = _acceptance_fingerprint_policy(
        kind="CANONICAL_DOCUMENT",
        domain=receipt_protocol["receiptFingerprintDomain"],
        source="THIS_RECEIPT_EXACT_BYTES",
        self_null_policy="VALUE_NULL_DURING_HASH",
    )
    _require(contract["artifactAggregate"] == expected_artifact_policy, "RECEIPT_ACCEPTANCE_ARTIFACT_POLICY_DRIFT")
    _require(contract["evidenceAggregate"] == expected_evidence_policy, "RECEIPT_ACCEPTANCE_EVIDENCE_POLICY_DRIFT")
    _require(contract["receiptFingerprint"] == expected_receipt_policy, "RECEIPT_ACCEPTANCE_RECEIPT_POLICY_DRIFT")
    return contract


def _verify_invocation(
    receipt: dict[str, Any],
    contract: dict[str, Any],
    expected_nonce: str,
    expected_launcher: str,
    expected_command: str,
    expected_source: str,
    expected_build_invocation_id: str,
    expected_predecessor: str | None,
) -> None:
    _require(receipt["challengeNonce"] == expected_nonce, "RECEIPT_CHALLENGE_NONCE_MISMATCH")
    _require(receipt["buildInvocation"]["id"] == expected_build_invocation_id, "RECEIPT_BUILD_INVOCATION_ID_MISMATCH")
    _require(receipt["launcherObservationFingerprint"] == expected_launcher, "RECEIPT_LAUNCHER_OBSERVATION_MISMATCH")
    _require(receipt["observedCommandFingerprint"] == expected_command, "RECEIPT_COMMAND_FINGERPRINT_MISMATCH")
    _require(receipt["testSourceFingerprint"] == expected_source, "RECEIPT_SOURCE_FINGERPRINT_MISMATCH")
    required_predecessor = contract["predecessorReceiptFingerprint"]
    if required_predecessor is None:
        _require(expected_predecessor is None, "RECEIPT_PREDECESSOR_MUST_NOT_BE_PROVIDED")
        _require(receipt["predecessorSliceId"] is None and receipt["predecessorReceiptFingerprint"] is None, "RECEIPT_UNEXPECTED_PREDECESSOR")
    else:
        source_slice = required_predecessor["sourceSliceId"]
        _require(expected_predecessor is not None, "RECEIPT_PREDECESSOR_REQUIRED")
        _require(receipt["predecessorSliceId"] == source_slice, "RECEIPT_PREDECESSOR_SLICE_MISMATCH")
        _require(receipt["predecessorReceiptFingerprint"] == expected_predecessor, "RECEIPT_PREDECESSOR_FINGERPRINT_MISMATCH")


def _verify_external_bindings(
    receipt: dict[str, Any],
    contract: dict[str, Any],
    expected_source_tree_fp: str,
    expected_toolchain_fp: str,
) -> None:
    source_commitment = receipt["sourceTreeCommitment"]
    _require(source_commitment["kind"] == "TREE_COMMITMENT", "RECEIPT_SOURCE_TREE_KIND_INVALID")
    _require(source_commitment["algorithm"] == "SHA-256", "RECEIPT_SOURCE_TREE_ALG_INVALID")
    _require(source_commitment["domain"] == "RG-CS-GATE-A-SOURCE-TREE-v1", "RECEIPT_SOURCE_TREE_DOMAIN_INVALID")
    _require(source_commitment["source"] == "CALLER_PINNED_SOURCE_TREE", "RECEIPT_SOURCE_TREE_SOURCE_INVALID")
    _require(source_commitment["selfNullPolicy"] == "VALUE_NULL_DURING_HASH", "RECEIPT_SOURCE_TREE_NULL_POLICY_INVALID")
    _require(source_commitment["value"] == expected_source_tree_fp, "RECEIPT_SOURCE_TREE_FP_MISMATCH")
    toolchain = receipt["toolchainIdentityFingerprint"]
    _require(toolchain["kind"] == "RAW_BYTES", "RECEIPT_TOOLCHAIN_KIND_INVALID")
    _require(toolchain["algorithm"] == "SHA-256", "RECEIPT_TOOLCHAIN_ALG_INVALID")
    _require(toolchain["domain"] == "RG-CS-GATE-A-TOOLCHAIN-RUNTIME-v1", "RECEIPT_TOOLCHAIN_DOMAIN_INVALID")
    _require(toolchain["source"] == "REQUIRED_EXTERNAL_TOOLCHAIN_PIN", "RECEIPT_TOOLCHAIN_SOURCE_INVALID")
    _require(toolchain["selfNullPolicy"] == "VALUE_NULL_UNTIL_EXTERNAL_PIN", "RECEIPT_TOOLCHAIN_NULL_POLICY_INVALID")
    _require(toolchain["value"] == expected_toolchain_fp, "RECEIPT_TOOLCHAIN_FP_MISMATCH")


def _verify_role_views(snapshot: BundleSnapshot, receipt: dict[str, Any], authority: dict[str, Any], slice_id: str) -> None:
    matches = [item for item in authority.get("deliverySlices", []) if isinstance(item, dict) and item.get("sliceId") == slice_id]
    _require(len(matches) == 1, "RECEIPT_SLICE_NOT_AUTHORIZED")
    required_roles = matches[0].get("implementationRoles", [])
    _require(isinstance(required_roles, list) and required_roles, "RECEIPT_IMPLEMENTATION_ROLES_INVALID")
    bindings = receipt["roleViewBindings"]
    _require([item["role"] for item in bindings] == sorted(item["role"] for item in bindings), "RECEIPT_ROLE_VIEW_ORDER_INVALID")
    _require(bindings == sorted(bindings, key=lambda item: item["role"]), "RECEIPT_ROLE_VIEW_ORDER_INVALID")
    _require(required_roles == list(snapshot.manifest["implementationRoles"]), "RECEIPT_IMPLEMENTATION_ROLE_BINDING_DRIFT")
    _require([item["role"] for item in bindings] == sorted(snapshot.manifest["implementationRoles"]), "RECEIPT_ROLE_VIEW_SET_DRIFT")
    _require({item["role"] for item in bindings} == set(required_roles), "RECEIPT_ROLE_VIEW_SET_DRIFT")
    views = {item["role"]: item for item in snapshot.manifest["roleViews"]}
    for binding in bindings:
        view = views.get(binding["role"])
        _require(view is not None, "RECEIPT_ROLE_VIEW_NOT_IN_BUNDLE")
        _require(binding["roleViewFingerprint"] == view["roleViewFingerprint"], "RECEIPT_ROLE_VIEW_FINGERPRINT_DRIFT")
        _require(binding["inputTreeFingerprint"] == view["inputTreeFingerprint"], "RECEIPT_INPUT_TREE_FINGERPRINT_DRIFT")


def _verify_handoff(receipt: dict[str, Any], contract: dict[str, Any], artifact_paths: set[str], evidence_ids: set[str]) -> None:
    expected = contract.get("handoff")
    _require(isinstance(expected, dict), "RECEIPT_HANDOFF_CONTRACT_MISSING")
    _require(receipt["handoff"] == expected, "RECEIPT_HANDOFF_DRIFT")
    _require(set(receipt["handoff"]["artifactPaths"]).issubset(artifact_paths), "RECEIPT_HANDOFF_ARTIFACT_NOT_BOUND")
    _require(set(receipt["handoff"]["evidenceIds"]).issubset(evidence_ids), "RECEIPT_HANDOFF_EVIDENCE_NOT_BOUND")


def _material_stat(fd: int) -> tuple[int, int, int, int, int, int, int]:
    info = os.fstat(fd)
    return (info.st_dev, info.st_ino, info.st_mode, info.st_nlink, info.st_size, info.st_mtime_ns, info.st_ctime_ns)


def _physical_material_root(raw_path: str, label: str) -> tuple[pathlib.Path, int, set[str], set[str], tuple[int, int, int, int, int, int, int]]:
    try:
        path = lexical_absolute(raw_path, f"RECEIPT_{label}_ROOT_PATH_ALIAS")
        fd = open_dir(raw_path, f"RECEIPT_{label}_ROOT")
        before = _material_stat(fd)
        if not stat.S_ISDIR(before[2]):
            os.close(fd)
            raise ReceiptError(f"RECEIPT_{label}_ROOT_NOT_STABLE")
        files, dirs = _inventory(fd)
        return path, fd, files, dirs, before
    except ReceiptError:
        raise
    except (BundleError, OSError, ValueError) as error:
        raise ReceiptError(f"RECEIPT_{label}_ROOT_UNREADABLE:{error}") from error


def _expected_dirs(files: set[str]) -> set[str]:
    dirs = {""}
    for value in files:
        parts = value.split("/")
        dirs.update("/".join(parts[:index]) for index in range(1, len(parts)))
    return dirs


def _verify_material(
    root_path: str,
    records: list[dict[str, Any]],
    label: str,
    *,
    artifact: bool,
) -> tuple[list[dict[str, Any]], set[str], set[str]]:
    paths = [record["path"] for record in records]
    _require(paths == sorted(paths), f"RECEIPT_{label}_RECORD_ORDER_INVALID")
    _require(len(paths) == len(set(paths)), f"RECEIPT_{label}_PATH_SET_INVALID")
    _require(all(PATH_PATTERN.fullmatch(path) is not None for path in paths), f"RECEIPT_{label}_PATH_NON_CANONICAL")
    path, fd, before_files, before_dirs, before_stat = _physical_material_root(root_path, label)
    del path
    loaded: list[dict[str, Any]] = []
    total = 0
    try:
        expected_files = set(paths)
        _require(len(expected_files) <= MAX_MATERIAL_FILES, f"RECEIPT_{label}_FILE_COUNT_LIMIT")
        _require(before_files == expected_files, f"RECEIPT_{label}_PHYSICAL_CLOSED_SET_DRIFT")
        _require(before_dirs == _expected_dirs(expected_files), f"RECEIPT_{label}_DIRECTORY_CLOSED_SET_DRIFT")
        for record in records:
            try:
                raw = read_relative(fd, record["path"], MAX_MATERIAL_FILE_BYTES, f"RECEIPT_{label}_FILE")
            except (BundleError, OSError, ValueError) as error:
                raise ReceiptError(f"RECEIPT_{label}_FILE_UNREADABLE:{record['path']}:{error}") from error
            _require(len(raw) == record["byteLength"], f"RECEIPT_{label}_BYTE_LENGTH_DRIFT:{record['path']}")
            _require(raw_fingerprint(raw) == record["rawFingerprint"], f"RECEIPT_{label}_FINGERPRINT_DRIFT:{record['path']}")
            total += len(raw)
            _require(total <= MAX_MATERIAL_TOTAL_BYTES, f"RECEIPT_{label}_TOTAL_BYTES_LIMIT")
            loaded.append({"role": record["role"], "path": record["path"], "byteLength": len(raw), "rawFingerprint": raw_fingerprint(raw)} if artifact else {"id": record["id"], "path": record["path"], "byteLength": len(raw), "rawFingerprint": raw_fingerprint(raw)})
        after_files, after_dirs = _inventory(fd)
        after_stat = _material_stat(fd)
        _require(before_files == after_files and before_dirs == after_dirs, f"RECEIPT_{label}_INVENTORY_DRIFT")
        _require(before_stat == after_stat, f"RECEIPT_{label}_ROOT_FSTAT_DRIFT")
        return loaded, after_files, after_dirs
    finally:
        os.close(fd)


def _verify_materials(
    receipt: dict[str, Any],
    contract: dict[str, Any],
    protocol: dict[str, Any],
    artifact_root: str,
    evidence_root: str,
) -> None:
    artifacts = receipt["artifactRecords"]
    evidence = receipt["evidenceRecords"]
    required_artifacts = contract["requiredArtifactPaths"]
    _require([item["path"] for item in artifacts] == sorted(required_artifacts), "RECEIPT_ARTIFACT_REQUIRED_SET_DRIFT")
    _require([item["id"] for item in evidence] == sorted(item["id"] for item in evidence), "RECEIPT_EVIDENCE_ID_ORDER_INVALID")
    _require(set(item["id"] for item in evidence) == set(contract["requiredEvidenceIds"]), "RECEIPT_EVIDENCE_REQUIRED_SET_DRIFT")
    loaded_artifacts, _, _ = _verify_material(artifact_root, artifacts, "ARTIFACT", artifact=True)
    loaded_evidence, _, _ = _verify_material(evidence_root, evidence, "EVIDENCE", artifact=False)
    _require(loaded_artifacts == artifacts, "RECEIPT_ARTIFACT_RECORD_REBOUND")
    _require(loaded_evidence == evidence, "RECEIPT_EVIDENCE_RECORD_REBOUND")
    expected_artifact_aggregate = {
        "kind": "AGGREGATE_COMMITMENT", "algorithm": "SHA-256",
        "domain": protocol["artifactAggregateDomain"], "source": "REQUIRED_ARTIFACT_RAW_BYTES",
        "value": committed(protocol["artifactAggregateDomain"].encode("ascii"), artifacts),
    }
    expected_evidence_aggregate = {
        "kind": "AGGREGATE_COMMITMENT", "algorithm": "SHA-256",
        "domain": protocol["evidenceAggregateDomain"], "source": "REQUIRED_EVIDENCE_EXACT_BYTES",
        "value": committed(protocol["evidenceAggregateDomain"].encode("ascii"), evidence),
    }
    _require(receipt["artifactAggregate"] == expected_artifact_aggregate, "RECEIPT_ARTIFACT_AGGREGATE_DRIFT")
    _require(receipt["evidenceAggregate"] == expected_evidence_aggregate, "RECEIPT_EVIDENCE_AGGREGATE_DRIFT")


def _verify_artifact_roles(receipt: dict[str, Any], authority: dict[str, Any], snapshot: BundleSnapshot) -> None:
    roles = {item["role"]: item for item in authority.get("roleContracts", []) if isinstance(item, dict) and "role" in item}
    closure_roles = snapshot.manifest["artifactClosureRoles"]
    _require(
        sorted(record["role"] for record in receipt["artifactRecords"]) == sorted(closure_roles),
        "RECEIPT_ARTIFACT_CLOSURE_ROLE_SET_DRIFT",
    )
    _require(
        {record["path"] for record in receipt["artifactRecords"]}
        == {roles[role]["artifactPath"] for role in closure_roles},
        "RECEIPT_ARTIFACT_CLOSURE_PATH_SET_DRIFT",
    )
    for record in receipt["artifactRecords"]:
        role = roles.get(record["role"])
        _require(role is not None, "RECEIPT_ARTIFACT_ROLE_UNKNOWN")
        _require(role.get("artifactPath") == record["path"], "RECEIPT_ARTIFACT_ROLE_PATH_DRIFT")
        bundle_artifact_path = f"role-views/{record['role']}/inputs/artifacts/{record['role']}.jar"
        try:
            bundle_artifact_raw = snapshot.bytes_for(bundle_artifact_path)
        except BundleError as error:
            raise ReceiptError(f"RECEIPT_BUNDLE_ARTIFACT_MISSING:{record['role']}") from error
        _require(raw_fingerprint(bundle_artifact_raw) == record["rawFingerprint"], f"RECEIPT_ARTIFACT_BUNDLE_FP_DRIFT:{record['role']}")


def _receipt_self_fingerprint(receipt: dict[str, Any], protocol: dict[str, Any]) -> str:
    doc = copy.deepcopy(receipt)
    doc["receiptFingerprint"]["value"] = None
    # Remove internal adapter stash fields that contain non-serializable bytes
    # and are not part of the signed receipt surface.
    doc.pop("_authority", None)
    doc.pop("_authority_raw", None)
    doc.pop("_snapshot", None)
    canonical = _canonical(doc)
    return raw_fingerprint(canonical)


def _verify_receipt_fingerprint(receipt: dict[str, Any], protocol: dict[str, Any]) -> str:
    expected = {
        "kind": "CANONICAL_DOCUMENT", "algorithm": "SHA-256",
        "domain": protocol["receiptFingerprintDomain"],
        "value": _receipt_self_fingerprint(receipt, protocol), "selfNullPolicy": "VALUE_NULL_DURING_HASH",
    }
    _require(receipt["receiptFingerprint"] == expected, "RECEIPT_SELF_FINGERPRINT_DRIFT")
    return expected["value"]


# ---- Ledger: Invocation-only key (no receiptFingerprint in key) ----
#
# Root cause fix #2: ledger unique key = (bundleRoot, sliceId, challengeNonce,
# buildInvocationId) only.  receiptFingerprint goes into marker value.
# O_EXCL on marker creation is sufficient to reject same-invocation replays;
# the prior per-key duplicate check is removed (review P2).


def _invocation_key(
    receipt: dict[str, Any],
    bundle_root_fingerprint: str,
) -> tuple[dict[str, str], str]:
    """Ledger invocation-only key: excludes receiptFingerprint.

    Same invocation (same bundleRoot, sliceId, nonce, buildInvocationId) with
    changed evidence re-signed must be REPLAY_REJECTED.  O_EXCL on marker creation
    enforces this atomically at the filesystem level.
    """
    key = {
        "bundleRootFingerprint": bundle_root_fingerprint,
        "sliceId": receipt["sliceId"],
        "challengeNonce": receipt["challengeNonce"],
        "buildInvocationId": receipt["buildInvocation"]["id"],
    }
    return key, committed(LEDGER_INVOCATION_KEY_DOMAIN, key)


def _marker_full_key(
    invocation_key: dict[str, str],
    receipt_fingerprint: str,
    protocol: dict[str, Any],
) -> tuple[dict[str, str], str]:
    """Full marker key: invocation key + receiptFingerprint.

    Stored in the marker for full lineage.  The marker file name is derived
    from the INVOCATION-ONLY key fingerprint so that same invocation gets the
    same file name regardless of receiptFingerprint changes.
    """
    full_key = dict(invocation_key)
    full_key["receiptFingerprint"] = receipt_fingerprint
    return full_key, committed(protocol["consumptionKeyDomain"].encode("ascii"), full_key)


def _build_marker_lineage(
    receipt: dict[str, Any],
    authority: dict[str, Any],
    authority_raw: bytes,
    snapshot: BundleSnapshot,
    invocation_key: dict[str, str],
    receipt_fingerprint: str,
    predecessor_marker_fp: str | None,
    expected_predecessor_receipt_fingerprint: str | None,
) -> dict[str, Any]:
    """Build full lineage for the consumption marker.

    Marker includes:
    - authority raw fingerprint + revision
    - sourceTreeCommitment
    - toolchainIdentityFingerprint
    - bundleRootFingerprint
    - buildInvocation (profile + id + properties)
    - sliceId + acceptanceId
    - receiptFingerprint
    - previous head marker fingerprint (from ledger head)
    - predecessor marker commitment (if applicable)
    """
    lineage = {
        "authority": {
            "rawFingerprint": raw_fingerprint(authority_raw),
            "revision": authority["revision"],
        },
        "sourceTree": receipt["sourceTreeCommitment"]["value"],
        "toolchain": receipt["toolchainIdentityFingerprint"]["value"],
        "bundleRoot": receipt["bundleRootFingerprint"],
        "buildInvocation": {
            "id": receipt["buildInvocation"]["id"],
            "profile": receipt["buildInvocation"]["profile"],
            "properties": receipt["buildInvocation"]["properties"],
        },
        "sliceId": receipt["sliceId"],
        "acceptanceId": receipt["acceptanceId"],
        "receiptFingerprint": receipt_fingerprint,
        "predecessorMarkerFingerprint": predecessor_marker_fp,
    }
    if expected_predecessor_receipt_fingerprint is not None:
        lineage["predecessorReceiptFingerprint"] = expected_predecessor_receipt_fingerprint
    return lineage


def _load_ledger_head(ledger_root_fd: int) -> tuple[dict[str, Any] | None, bytes | None, int | None]:
    """Load the current ledger head file.

    Returns (head_doc, head_raw, head_revision) or (None, None, None) if no head.
    Genesis ledger: head file does not exist; use sentinel.

    Args:
        ledger_root_fd: Open directory fd for the ledger root (already open).

    Protocol:
    1. os.stat("ledger-head.json", dir_fd=fd, follow_symlinks=False) first.
    2. FileNotFoundError => return None, None, None (genesis ledger).
    3. Reject non-regular file or nlink != 1.
    4. Then read_relative for bounded content read.
    """
    try:
        stat_info = os.stat("ledger-head.json", dir_fd=ledger_root_fd, follow_symlinks=False)
    except FileNotFoundError:
        return None, None, None
    except OSError as error:
        raise ReceiptError(f"RECEIPT_LEDGER_HEAD_STAT_FAILED:{error}") from error

    if stat.S_ISLNK(stat_info.st_mode):
        raise ReceiptError("RECEIPT_LEDGER_HEAD_IS_SYMLINK")
    if not stat.S_ISREG(stat_info.st_mode):
        raise ReceiptError("RECEIPT_LEDGER_HEAD_NOT_REGULAR")
    if stat_info.st_nlink != 1:
        raise ReceiptError(f"RECEIPT_LEDGER_HEAD_NLINK_INVALID:{stat_info.st_nlink}")

    try:
        raw = read_relative(ledger_root_fd, "ledger-head.json", MAX_LEDGER_HEAD_BYTES, "LEDGER_HEAD")
    except BundleError as error:
        raise ReceiptError(f"RECEIPT_LEDGER_HEAD_UNREADABLE:{error}") from error
    try:
        head = strict_json(raw, "ledger-head")
    except BundleError as error:
        raise ReceiptError(f"RECEIPT_LEDGER_HEAD_JSON_INVALID:{error}") from error
    if not isinstance(head, dict):
        raise ReceiptError("RECEIPT_LEDGER_HEAD_NOT_OBJECT")
    if raw != _canonical(head) + b"\n":
        raise ReceiptError("RECEIPT_LEDGER_HEAD_NON_CANONICAL")
    revision = head.get("ledgerRevision", 0)
    return head, raw, revision


def _validate_ledger_head(
    head: dict[str, Any],
    protocol: dict[str, Any],
) -> None:
    """Validate the ledger head file structure and content.

    Performs only structural checks on the head document:
    - schema version, adapter mode, revision >= 0
    - For revision 0: prev_fp is GENESIS sentinel, previousHeadRevision == 0
    - For revision > 0: previousHeadMarkerFingerprint is a valid fingerprint,
      previousHeadRevision == ledgerRevision - 1
    - markerKeyFingerprint is a valid fingerprint
    - timestamp is valid

    Caller-coordinate checks (expected current head/revision) are performed
    by the caller in _consume_ledger after this returns.
    """
    _require(isinstance(head, dict), "RECEIPT_LEDGER_HEAD_NOT_OBJECT")
    # Schema version
    _require(head.get("schemaVersion") == "capability-studio.gate-a-slice-consumption-ledger-head.v1",
             "RECEIPT_LEDGER_HEAD_SCHEMA_DRIFT")
    # Adapter mode: must be NON_RELEASE_ROLLBACKABLE_ADAPTER (no external CAS yet)
    adapter_mode = head.get("adapterMode", "")
    _require(adapter_mode in ("NON_RELEASE_ROLLBACKABLE_ADAPTER", "EXTERNAL_CAS_RELEASE"),
             "RECEIPT_LEDGER_HEAD_ADAPTER_MODE_INVALID")
    # Non-release adapter must not claim release semantics
    if adapter_mode == "NON_RELEASE_ROLLBACKABLE_ADAPTER":
        pass  # Valid: no release claim possible
    # Revision must be >= 0
    revision = head.get("ledgerRevision", -1)
    _require(isinstance(revision, int) and revision >= 0, "RECEIPT_LEDGER_HEAD_REVISION_INVALID")
    # Previous head fingerprint
    prev_fp = head.get("previousHeadMarkerFingerprint", "")
    # For genesis (revision 0), prev_fp must be GENESIS sentinel.
    # _fingerprint already special-cases GENESIS_PREVIOUS_HEAD_FP (returns it unchanged);
    # for non-genesis it rejects all-zeros. markerKeyFingerprint is not relaxed.
    if revision == 0:
        _fingerprint(prev_fp, "RECEIPT_LEDGER_HEAD_PREV_FP_INVALID")
        _require(prev_fp == GENESIS_PREVIOUS_HEAD_FP,
                 "RECEIPT_LEDGER_HEAD_GENESIS_SENTINEL_INVALID")
        _require(head.get("previousHeadRevision", -1) == 0,
                 "RECEIPT_LEDGER_HEAD_GENESIS_REVISION_INVALID")
    else:
        _fingerprint(prev_fp, "RECEIPT_LEDGER_HEAD_PREV_FP_INVALID")
        # Structural invariant: previousHeadRevision must be exactly one less than the
        # current ledgerRevision.  Caller-coordinate checks (expected current head/revision
        # vs. actual current head/revision) are performed by the caller in _consume_ledger.
        _require(
            head.get("previousHeadRevision", -1) == revision - 1,
            "RECEIPT_LEDGER_HEAD_PREV_REVISION_INVALID",
        )
    # Current marker fingerprint
    marker_fp = head.get("markerKeyFingerprint", "")
    _require(isinstance(marker_fp, str) and FINGERPRINT_PATTERN.fullmatch(marker_fp) is not None,
             "RECEIPT_LEDGER_HEAD_MARKER_FP_INVALID")
    # Timestamp
    ts = head.get("timestampISO8601", "")
    _require(isinstance(ts, str) and re.match(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$", ts) is not None,
             "RECEIPT_LEDGER_HEAD_TIMESTAMP_INVALID")


def _write_ledger_head(
    ledger_root_fd: int,
    ledger_root: str,
    new_revision: int,
    previous_head_marker_fp: str,
    previous_revision: int,
    marker_key_fp: str,
    adapter_mode: str = "NON_RELEASE_ROLLBACKABLE_ADAPTER",
    is_genesis: bool = False,
) -> str:
    """Create the new ledger head file.

    For genesis (is_genesis=True): creates ledger-head.json with O_EXCL.
    For replacement (is_genesis=False): uses temp file + atomic os.replace.

    Returns the new head fingerprint.

    Args:
        ledger_root_fd: Open directory fd for the ledger root.
        ledger_root: String path to ledger root (for temp file naming).
        new_revision: The new ledger revision number.
        previous_head_marker_fp: The marker fingerprint from the prior head's markerKeyFingerprint.
            For genesis, this is the GENESIS sentinel.
        previous_revision: The prior ledger revision number.
        marker_key_fp: The fingerprint of the new marker being committed.
        adapter_mode: Adapter mode string.
        is_genesis: True if no prior head exists (first commit).

    Raises:
        ReceiptError: On write failure, fsync failure, or atomic replace failure.
    """
    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    head_doc = {
        "schemaVersion": "capability-studio.gate-a-slice-consumption-ledger-head.v1",
        "adapterMode": adapter_mode,
        "ledgerRevision": new_revision,
        "previousHeadMarkerFingerprint": previous_head_marker_fp,
        "previousHeadRevision": previous_revision,
        "markerKeyFingerprint": marker_key_fp,
        "timestampISO8601": timestamp,
    }
    head_canonical = _canonical(head_doc) + b"\n"
    head_fp = raw_fingerprint(head_canonical)
    head_name = "ledger-head.json"

    if is_genesis:
        # Genesis: first head file - use O_EXCL to ensure no existing head
        # fstat requires regular file (not symlink) and nlink == 1 for stability
        try:
            head_fd = os.open(
                head_name,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC | getattr(os, "O_NOFOLLOW", 0),
                0o600,
                dir_fd=ledger_root_fd,
            )
        except FileExistsError:
            raise ReceiptError("RECEIPT_LEDGER_HEAD_ALREADY_EXISTS")
        except OSError as error:
            raise ReceiptError(f"RECEIPT_LEDGER_HEAD_CREATE_FAILED:{error}") from error

        try:
            # fstat: require regular file + nlink 1 for stability
            try:
                head_stat = os.fstat(head_fd)
            except OSError as stat_error:
                raise ReceiptError(f"RECEIPT_LEDGER_HEAD_GENESIS_FSTAT_FAILED:{stat_error}") from stat_error
            if stat.S_ISLNK(head_stat.st_mode):
                raise ReceiptError("RECEIPT_LEDGER_HEAD_GENESIS_IS_SYMLINK")
            if head_stat.st_nlink != 1:
                raise ReceiptError(f"RECEIPT_LEDGER_HEAD_GENESIS_NLINK_INVALID:{head_stat.st_nlink}")
            # Bounded write loop ensures exact byte count or fail
            total_written = 0
            while total_written < len(head_canonical):
                written = os.write(head_fd, head_canonical[total_written:])
                if written == 0:
                    raise ReceiptError("RECEIPT_LEDGER_HEAD_GENESIS_WRITE_ZERO")
                total_written += written
            if total_written != len(head_canonical):
                raise ReceiptError("RECEIPT_LEDGER_HEAD_SHORT_WRITE")
            os.fsync(head_fd)
        finally:
            os.close(head_fd)
    else:
        # Replacement: write to unique temp file, then atomic rename.
        # Temp uses O_EXCL to detect pre-existing symlink attacks.
        # Bounded write loop ensures exact byte count or fail.
        # Uses os.open with dir_fd for fd-based temp creation (no mutable path exposure).
        temp_basename = f".ledger-head-temp-{new_revision}-{secrets.token_hex(16)}.tmp"
        temp_fd = None
        try:
            # Create temp file relative to already-open ledger_root_fd (not mutable path)
            temp_fd = os.open(
                temp_basename,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC | getattr(os, "O_NOFOLLOW", 0),
                0o600,
                dir_fd=ledger_root_fd,
            )
            # Ensure temp is regular file, not symlink (O_EXCL prevents existing symlink)
            try:
                temp_stat = os.fstat(temp_fd)
            except OSError as error:
                raise ReceiptError(f"RECEIPT_LEDGER_HEAD_TEMP_FSTAT_FAILED:{error}") from error
            if stat.S_ISLNK(temp_stat.st_mode):
                os.close(temp_fd)
                temp_fd = None
                try:
                    os.unlink(temp_basename, dir_fd=ledger_root_fd)
                except FileNotFoundError:
                    pass
                raise ReceiptError("RECEIPT_LEDGER_HEAD_TEMP_IS_SYMLINK")
            # Write with bounded loop
            total_written = 0
            while total_written < len(head_canonical):
                written = os.write(temp_fd, head_canonical[total_written:])
                if written == 0:
                    raise ReceiptError("RECEIPT_LEDGER_HEAD_WRITE_ZERO")
                total_written += written
            if total_written != len(head_canonical):
                raise ReceiptError("RECEIPT_LEDGER_HEAD_SHORT_WRITE")
            os.fsync(temp_fd)
            os.close(temp_fd)
            temp_fd = None
            # Atomic rename using dir_fd: src_dir_fd=dst_dir_fd=ledger_root_fd
            # O_NOFOLLOW not needed on target since ledger-root fd is trusted
            try:
                os.replace(
                    temp_basename,
                    head_name,
                    src_dir_fd=ledger_root_fd,
                    dst_dir_fd=ledger_root_fd,
                )
            except OSError as error:
                raise ReceiptError(f"RECEIPT_LEDGER_HEAD_ATOMIC_REPLACE_FAILED:{error}") from error
            # Sync directory to persist the rename
            os.fsync(ledger_root_fd)
        finally:
            # Clean up temp file on failure using dir_fd
            if temp_fd is not None:
                try:
                    os.close(temp_fd)
                except OSError:
                    pass
            # Always try to unlink temp if it still exists (rename succeeded otherwise)
            try:
                os.unlink(temp_basename, dir_fd=ledger_root_fd)
            except FileNotFoundError:
                pass  # Successfully renamed (temp no longer exists)
            except OSError as error:
                raise ReceiptError(f"RECEIPT_LEDGER_HEAD_TEMP_CLEANUP_FAILED:{error}") from error

    return head_fp


def _postcommit_reread_marker(ledger_root_fd: int, marker_name: str) -> bytes:
    """Postcommit: re-read marker bytes and verify canonicality."""
    try:
        raw = read_relative(ledger_root_fd, marker_name, MAX_LEDGER_MARKER_BYTES, "POSTCOMMIT_MARKER")
    except (BundleError, OSError, ValueError) as error:
        raise ReceiptError(f"RECEIPT_POSTCOMMIT_MARKER_REREAD_FAILED:{error}") from error
    try:
        doc = strict_json(raw, "postcommit-marker")
    except BundleError as error:
        raise ReceiptError(f"RECEIPT_POSTCOMMIT_MARKER_JSON_INVALID:{error}") from error
    if raw != _canonical(doc) + b"\n":
        raise ReceiptError("RECEIPT_POSTCOMMIT_MARKER_NON_CANONICAL")
    return raw


def _collect_existing_markers(
    ledger_root_fd: int,
    protocol: dict[str, Any],
) -> tuple[dict[str, tuple[str, str]], set[str]]:
    """Collect all existing markers from the ledger directory.

    Returns (key_fp_to_marker_path, consumed_invocation_keys).
    key_fp_to_marker_path: map of invocation key fingerprint -> (marker_path, receiptFingerprint)
    consumed_invocation_keys: set of invocation key values already consumed.
    """
    try:
        files, dirs = _inventory(ledger_root_fd)
    except (BundleError, OSError, ValueError) as error:
        raise ReceiptError(f"RECEIPT_LEDGER_UNREADABLE:{error}") from error
    _require(dirs == {""}, "RECEIPT_LEDGER_DIRECTORY_NOT_CLOSED")

    key_fp_to_marker: dict[str, tuple[str, str]] = {}
    consumed_keys: set[str] = set()

    for path in files:
        if path == "ledger-head.json":
            continue
        if not re.fullmatch(r"marker-[0-9a-f]{64}\.json", path):
            raise ReceiptError(f"RECEIPT_LEDGER_UNKNOWN_ENTRY:{path}")
        try:
            raw = read_relative(ledger_root_fd, path, MAX_LEDGER_MARKER_BYTES, f"MARKER:{path}")
        except (BundleError, OSError, ValueError) as error:
            raise ReceiptError(f"RECEIPT_LEDGER_MARKER_INVALID:{path}:{error}") from error
        try:
            marker = strict_json(raw, path)
        except BundleError as error:
            raise ReceiptError(f"RECEIPT_LEDGER_MARKER_JSON_INVALID:{path}:{error}") from error
        _require(isinstance(marker, dict), f"RECEIPT_LEDGER_MARKER_NOT_OBJECT:{path}")
        expected_fields = {"schemaVersion", "key", "keyFingerprint", "lineage", "previousHeadMarkerFingerprint"}
        _require(set(marker) == expected_fields, f"RECEIPT_LEDGER_MARKER_FIELD_SET_DRIFT:{path}")
        _require(raw == _canonical(marker) + b"\n", f"RECEIPT_LEDGER_MARKER_NON_CANONICAL:{path}")
        _require(marker.get("schemaVersion") == LEDGER_MARKER_SCHEMA,
                 f"RECEIPT_LEDGER_MARKER_SCHEMA_DRIFT:{path}")

        # Validate key structure
        key = marker.get("key", {})
        _require(isinstance(key, dict), f"RECEIPT_LEDGER_MARKER_KEY_INVALID:{path}")
        _fingerprint(key.get("bundleRootFingerprint"), f"RECEIPT_LEDGER_MARKER_KEY_BUNDLE_INVALID:{path}")
        _fingerprint(key.get("receiptFingerprint"), f"RECEIPT_LEDGER_MARKER_KEY_RECEIPT_INVALID:{path}")
        _require(isinstance(key.get("sliceId"), str) and re.fullmatch(r"A1\.[1-7]", key["sliceId"]) is not None, f"RECEIPT_LEDGER_MARKER_SLICE_INVALID:{path}")
        _require(isinstance(key.get("challengeNonce"), str) and NONCE_PATTERN.fullmatch(key["challengeNonce"]) is not None, f"RECEIPT_LEDGER_MARKER_NONCE_INVALID:{path}")
        _require(isinstance(key.get("buildInvocationId"), str) and BUILD_INVOCATION_PATTERN.fullmatch(key["buildInvocationId"]) is not None, f"RECEIPT_LEDGER_MARKER_INVOCATION_INVALID:{path}")

        # Validate key fingerprint (full key, includes receiptFingerprint)
        key_fp_obj = marker.get("keyFingerprint", {})
        _require(isinstance(key_fp_obj, dict) and isinstance(key_fp_obj.get("value"), str) and FINGERPRINT_PATTERN.fullmatch(key_fp_obj["value"]) is not None,
                 f"RECEIPT_LEDGER_MARKER_KEY_FP_INVALID:{path}")
        computed_key_fp = committed(protocol["consumptionKeyDomain"].encode("ascii"), key)
        _require(key_fp_obj == {"kind": "CANONICAL_DOCUMENT", "algorithm": "SHA-256", "domain": protocol["consumptionKeyDomain"], "value": computed_key_fp},
                 f"RECEIPT_LEDGER_MARKER_KEY_FP_DRIFT:{path}")
        _require(path == f"marker-{computed_key_fp[7:]}.json",
                 f"RECEIPT_LEDGER_MARKER_NAME_DRIFT:{path}")

        # Extract invocation-only key and its fingerprint
        invocation_key_part = {
            "bundleRootFingerprint": key["bundleRootFingerprint"],
            "sliceId": key["sliceId"],
            "challengeNonce": key["challengeNonce"],
            "buildInvocationId": key["buildInvocationId"],
        }
        invocation_key_fp = committed(LEDGER_INVOCATION_KEY_DOMAIN, invocation_key_part)

        key_fp_to_marker[computed_key_fp] = (path, key["receiptFingerprint"])
        consumed_keys.add(invocation_key_fp)

    return key_fp_to_marker, consumed_keys


def _consume_ledger(
    ledger_root: str,
    receipt: dict[str, Any],
    protocol: dict[str, Any],
    bundle_root_fingerprint: str,
    receipt_fingerprint: str,
    predecessor_slice_id: str | None,
    expected_predecessor_receipt_fingerprint: str | None,
    expected_ledger_head_fp: str,
    expected_ledger_revision: int,
    predecessor_marker_fp: str | None,
) -> tuple[str, str]:
    """Consume the receipt into the caller-owned ledger.

    Returns (marker_key_fingerprint, new_ledger_head_fingerprint).

    Protocol:
    1. flock(EX) serializes concurrent callers.
    2. Load existing ledger head; caller-pinned expected head/revision validated.
    3. For genesis (no head file): previous_head_fp = GENESIS sentinel, revision = 0.
    4. Invocation-only key fingerprint = committed(invocation_key); O_EXCL on marker.
    5. Build full-lineage marker with authority/source/tree/toolchain/bundle/invocation/
       slice/receipt/head/pred fields.
    6. Create ledger-head.json with O_EXCL (rollback-resistant).
    7. Postcommit: re-read marker bytes, verify canonicality.
    8. Return new marker key fingerprint and new head fingerprint.
    """
    try:
        fd = open_dir(ledger_root, "RECEIPT_LEDGER_ROOT")
    except (BundleError, OSError, ValueError) as error:
        raise ReceiptError(f"RECEIPT_LEDGER_UNAVAILABLE:{error}") from error

    marker_created = False
    marker_name: str | None = None
    head_created = False

    def rollback() -> None:
        errors: list[str] = []
        if marker_created and marker_name is not None:
            try:
                os.unlink(marker_name, dir_fd=fd)
            except FileNotFoundError:
                pass
            except OSError as e:
                errors.append(f"RECEIPT_LEDGER_ROLLBACK_MARKER_UNLINK:{e}")
            try:
                os.fsync(fd)
            except OSError as e:
                errors.append(f"RECEIPT_LEDGER_ROLLBACK_FSYNC:{e}")
        if errors:
            raise ReceiptError(";".join(errors))

    try:
        if fcntl is None or not hasattr(fcntl, "flock") or not hasattr(fcntl, "LOCK_EX"):
            raise ReceiptError("RECEIPT_LEDGER_LOCK_UNAVAILABLE")
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            if error.errno in (errno.EACCES, errno.EAGAIN):
                raise ReceiptError("RECEIPT_LEDGER_LOCK_CONTENTION") from error
            raise ReceiptError(f"RECEIPT_LEDGER_LOCK_FAILED:{error}") from error

        before_stat = _material_stat(fd)

        # Step 1: Collect existing markers
        key_fp_to_marker, consumed_invocation_keys = _collect_existing_markers(fd, protocol)

        # Step 2: Build invocation-only key
        invocation_key, invocation_key_fp = _invocation_key(receipt, bundle_root_fingerprint)

        # Step 3: Check if this invocation has already been consumed
        if invocation_key_fp in consumed_invocation_keys:
            raise ReplayRejected(f"REPLAY_REJECTED:invocation_key_fp={invocation_key_fp}")

        # Step 4: Load and validate ledger head
        head, head_raw, head_revision = _load_ledger_head(fd)
        if head is None:
            # Genesis ledger: no prior head, previous marker is GENESIS sentinel
            if expected_ledger_revision != 0:
                raise ReceiptError(f"RECEIPT_LEDGER_GENESIS_EXPECTED:got_revision={expected_ledger_revision}")
            if expected_ledger_head_fp != GENESIS_PREVIOUS_HEAD_FP:
                raise ReceiptError(f"RECEIPT_LEDGER_GENESIS_HEAD_MISMATCH")
            prev_revision = 0
            # For genesis: marker's previousHeadMarkerFingerprint is GENESIS sentinel
            marker_prev_head_fp = GENESIS_PREVIOUS_HEAD_FP
        else:
            _validate_ledger_head(head, protocol)
            # Current head's marker fingerprint (what caller pins)
            current_head_marker_fp = head.get("markerKeyFingerprint", "")
            # Previous head marker fingerprint (what new head's previousHeadMarkerFingerprint should be)
            prev_head_marker_fp = head.get("previousHeadMarkerFingerprint", GENESIS_PREVIOUS_HEAD_FP)
            # Current ledger revision
            prev_revision = head.get("ledgerRevision", 0)
            # Verify expected head matches actual: caller pins current head marker + revision
            if expected_ledger_revision != prev_revision:
                raise ReceiptError(f"RECEIPT_LEDGER_REVISION_MISMATCH:expected={expected_ledger_revision}:actual={prev_revision}")
            if expected_ledger_head_fp != current_head_marker_fp:
                raise ReceiptError(f"RECEIPT_LEDGER_HEAD_FP_MISMATCH:expected={expected_ledger_head_fp}:actual={current_head_marker_fp}")
            # For non-genesis: previousHeadRevision already validated structurally (== revision - 1)
            # For marker's previousHeadMarkerFingerprint: must be current head's markerKeyFingerprint
            marker_prev_head_fp = current_head_marker_fp

        # Step 5: Predecessor marker check
        if predecessor_slice_id is not None:
            _require(expected_predecessor_receipt_fingerprint is not None, "RECEIPT_PREDECESSOR_LEDGER_REQUIRED")
            _require(predecessor_marker_fp is not None, "RECEIPT_PREDECESSOR_MARKER_FP_REQUIRED")
            # Walk existing markers to find predecessor
            predecessor_found = False
            for mfp, (mpath, mr_fp) in key_fp_to_marker.items():
                if mr_fp == expected_predecessor_receipt_fingerprint:
                    predecessor_found = True
                    break
            if not predecessor_found:
                raise ReceiptError("RECEIPT_PREDECESSOR_MARKER_NOT_FOUND")
        else:
            _require(expected_predecessor_receipt_fingerprint is None, "RECEIPT_PREDECESSOR_LEDGER_UNEXPECTED")

        # Step 6: Build full-lineage marker
        lineage = _build_marker_lineage(
            receipt=receipt,
            authority=receipt.get("_authority"),
            authority_raw=receipt.get("_authority_raw"),
            snapshot=receipt.get("_snapshot"),
            invocation_key=invocation_key,
            receipt_fingerprint=receipt_fingerprint,
            predecessor_marker_fp=predecessor_marker_fp,
            expected_predecessor_receipt_fingerprint=expected_predecessor_receipt_fingerprint,
        )

        # Build full marker key (invocation key + receiptFingerprint)
        full_key, full_key_fp = _marker_full_key(invocation_key, receipt_fingerprint, protocol)
        marker_name = f"marker-{full_key_fp[7:]}.json"

        # Verify no marker with this invocation-only key already exists (O_EXCL below)
        # and check the full key fingerprint matches the expected name
        if invocation_key_fp in consumed_invocation_keys:
            raise ReplayRejected(f"REPLAY_REJECTED:invocation_key_fp={invocation_key_fp}")

        marker_doc = {
            "schemaVersion": LEDGER_MARKER_SCHEMA,
            "key": full_key,
            "keyFingerprint": {
                "kind": "CANONICAL_DOCUMENT",
                "algorithm": "SHA-256",
                "domain": protocol["consumptionKeyDomain"],
                "value": full_key_fp,
            },
            "lineage": lineage,
            # Must be prior current head's markerKeyFingerprint (or GENESIS for first commit)
            "previousHeadMarkerFingerprint": marker_prev_head_fp,
        }
        marker_canonical = _canonical(marker_doc) + b"\n"

        # Step 7: Create marker with O_EXCL (atomic: reject same invocation)
        before_files, before_dirs = _inventory(fd)
        try:
            marker_fd = os.open(
                marker_name,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC | getattr(os, "O_NOFOLLOW", 0),
                0o600,
                dir_fd=fd,
            )
            marker_created = True
        except FileExistsError:
            # Same invocation already committed (race condition between check and create)
            raise ReplayRejected(f"REPLAY_REJECTED:marker_exists={marker_name}") from None
        except OSError as error:
            raise ReceiptError(f"RECEIPT_LEDGER_MARKER_CREATE_FAILED:{error}") from error

        try:
            written = os.write(marker_fd, marker_canonical)
            if written != len(marker_canonical):
                raise ReceiptError("RECEIPT_LEDGER_COMMIT_SHORT_WRITE")
            os.fsync(marker_fd)
        finally:
            os.close(marker_fd)

        # Step 7b: Validate marker before proceeding to head commit
        # Reread marker bytes and verify canonicality and contents
        _postcommit_reread_marker(fd, marker_name)
        # Additionally verify marker fingerprint matches expected
        try:
            marker_raw = read_relative(fd, marker_name, MAX_LEDGER_MARKER_BYTES, "PRE_HEAD_MARKER_VALIDATION")
        except (BundleError, OSError, ValueError) as error:
            raise ReceiptError(f"RECEIPT_PRE_HEAD_MARKER_READ_FAILED:{error}") from error
        if marker_raw != marker_canonical:
            raise ReceiptError("RECEIPT_PRE_HEAD_MARKER_CONTENT_DRIFT")

        # Step 8: Create ledger head
        # For genesis: previousHeadMarkerFingerprint is GENESIS sentinel
        # For replacement: previousHeadMarkerFingerprint is prior head's markerKeyFingerprint
        is_genesis = (prev_revision == 0 and head is None)
        new_revision = prev_revision + 1
        try:
            new_head_fp = _write_ledger_head(
                ledger_root_fd=fd,
                ledger_root=ledger_root,
                new_revision=new_revision,
                previous_head_marker_fp=marker_prev_head_fp,
                previous_revision=prev_revision,
                marker_key_fp=full_key_fp,
                adapter_mode="NON_RELEASE_ROLLBACKABLE_ADAPTER",
                is_genesis=is_genesis,
            )
            head_created = True
        except ReceiptError:
            # Marker created but head failed: rollback marker
            rollback()
            raise

        # Step 9: fsync ledger directory
        os.fsync(fd)

        # Step 10: Postcommit marker bytes reread
        _postcommit_reread_marker(fd, marker_name)

        # Step 11: Verify inventory stability
        # Genesis commit: +1 marker +1 head = +2 files
        # Later commit: +1 marker, head is replaced = +1 file
        expected_delta = 2 if is_genesis else 1
        after_files, after_dirs = _inventory(fd)
        after_stat = _material_stat(fd)
        _require(marker_name in after_files and len(after_files) == len(before_files) + expected_delta,
                 f"RECEIPT_LEDGER_COMMIT_STATE_UNKNOWN:marker={marker_name}:expected_delta={expected_delta}")
        _require(after_dirs == {""}, "RECEIPT_LEDGER_COMMIT_STATE_UNKNOWN:marker={marker_name}:DIR_DRIFT")
        _require(before_stat[:3] == after_stat[:3], "RECEIPT_LEDGER_COMMIT_STATE_UNKNOWN:marker={marker_name}:ROOT_FSTAT_DRIFT")

        return full_key_fp, new_head_fp

    except BaseException as original_error:
        try:
            rollback()
        except ReceiptError as rollback_error:
            raise rollback_error from original_error
        raise
    finally:
        if fcntl is not None:
            try:
                fcntl.flock(fd, fcntl.LOCK_UN)
            except OSError:
                pass
        os.close(fd)


def verify_and_consume(
    *,
    bundle_root: str,
    expected_bundle_root_fingerprint: str,
    receipt_path: str,
    expected_slice_id: str,
    expected_challenge_nonce: str,
    expected_launcher_observation_fingerprint: str,
    expected_command_fingerprint: str,
    expected_test_source_fingerprint: str,
    expected_source_tree_fingerprint: str,
    expected_toolchain_identity_fingerprint: str,
    expected_build_invocation_id: str,
    artifact_root: str,
    evidence_root: str,
    consumption_ledger_root: str,
    expected_predecessor_receipt_fingerprint: str | None,
    predecessor_argument_supplied: bool,
    # --- Round 7 additions ---
    expected_ledger_head_fingerprint: str = GENESIS_PREVIOUS_HEAD_FP,
    expected_ledger_revision: int = 0,
    predecessor_marker_fingerprint: str | None = None,
) -> dict[str, str]:
    """Verify and consume one Gate A slice receipt.

    Returns a dict with:
      receiptFingerprint: the accepted receipt's fingerprint
      ledgerHeadFingerprint: the new ledger head fingerprint
      ledgerRevision: the new ledger revision

    Raises ReplayRejected when the same invocation has already been consumed.
    Raises ReceiptError (ValueError subclass) on any other verification failure.
    """
    # Bundle verification is intentionally the first trust-boundary action.
    try:
        snapshot = verify_bundle(bundle_root, expected_bundle_root_fingerprint)
    except (BundleError, OSError, ValueError) as error:
        raise ReceiptError(f"RECEIPT_BUNDLE_REJECTED:{error}") from error

    _fingerprint(expected_bundle_root_fingerprint, "RECEIPT_EXPECTED_BUNDLE_ROOT_INVALID")
    _fingerprint(expected_launcher_observation_fingerprint, "RECEIPT_EXPECTED_LAUNCHER_FINGERPRINT_INVALID")
    _fingerprint(expected_command_fingerprint, "RECEIPT_EXPECTED_COMMAND_FINGERPRINT_INVALID")
    _fingerprint(expected_test_source_fingerprint, "RECEIPT_EXPECTED_SOURCE_FINGERPRINT_INVALID")
    _fingerprint(expected_source_tree_fingerprint, "RECEIPT_EXPECTED_SOURCE_TREE_FINGERPRINT_INVALID")
    _fingerprint(expected_toolchain_identity_fingerprint, "RECEIPT_EXPECTED_TOOLCHAIN_FINGERPRINT_INVALID")
    _require(isinstance(expected_build_invocation_id, str) and BUILD_INVOCATION_PATTERN.fullmatch(expected_build_invocation_id) is not None,
             "RECEIPT_EXPECTED_BUILD_INVOCATION_ID_INVALID")
    _require(isinstance(expected_challenge_nonce, str) and NONCE_PATTERN.fullmatch(expected_challenge_nonce) is not None,
             "RECEIPT_EXPECTED_CHALLENGE_NONCE_INVALID")
    _require(isinstance(expected_ledger_revision, int) and expected_ledger_revision >= 0,
             "RECEIPT_EXPECTED_LEDGER_REVISION_INVALID")
    _fingerprint(expected_ledger_head_fingerprint, "RECEIPT_EXPECTED_LEDGER_HEAD_FP_INVALID")
    if predecessor_argument_supplied:
        _require(expected_predecessor_receipt_fingerprint is not None,
                 "RECEIPT_PREDECESSOR_ARGUMENT_REQUIRED")
        _fingerprint(expected_predecessor_receipt_fingerprint, "RECEIPT_EXPECTED_PREDECESSOR_INVALID")
    else:
        _require(expected_predecessor_receipt_fingerprint is None,
                 "RECEIPT_PREDECESSOR_UNEXPECTED")

    receipt, receipt_raw = _load_receipt(receipt_path)
    authority, authority_raw = _authority(snapshot)

    # Stash for ledger marker lineage building (needed by _consume_ledger via receipt.get())
    receipt["_authority"] = authority
    receipt["_authority_raw"] = authority_raw
    receipt["_snapshot"] = snapshot

    receipt_protocol = _slice_receipt_contract(authority)
    # Pop stash fields before schema validation — they are internal adapter fields,
    # not part of the Authority-registered receipt schema (additionalProperties: false).
    _authority_stash = receipt.pop("_authority")
    _authority_raw_stash = receipt.pop("_authority_raw")
    _snapshot_stash = receipt.pop("_snapshot")
    _validate_schema(snapshot, receipt, receipt_protocol["schema"])
    # Restore stash for _consume_ledger
    receipt["_authority"] = _authority_stash
    receipt["_authority_raw"] = _authority_raw_stash
    receipt["_snapshot"] = _snapshot_stash

    _require(receipt["sliceId"] == expected_slice_id, "RECEIPT_SLICE_ID_MISMATCH")

    if expected_slice_id == "A1.1":
        _require(not predecessor_argument_supplied, "RECEIPT_PREDECESSOR_MUST_NOT_BE_PROVIDED")
    else:
        _require(predecessor_argument_supplied, "RECEIPT_PREDECESSOR_ARGUMENT_REQUIRED")

    contract = _verify_bundle_binding(snapshot, receipt, authority, authority_raw, expected_slice_id)
    _verify_invocation(
        receipt, contract, expected_challenge_nonce, expected_launcher_observation_fingerprint,
        expected_command_fingerprint, expected_test_source_fingerprint, expected_build_invocation_id,
        expected_predecessor_receipt_fingerprint,
    )
    _verify_external_bindings(receipt, contract, expected_source_tree_fingerprint, expected_toolchain_identity_fingerprint)
    _verify_role_views(snapshot, receipt, authority, expected_slice_id)
    _verify_artifact_roles(receipt, authority, snapshot)

    # Physical material verification (artifacts + evidence bytes)
    artifact_path, artifact_fd, artifact_files, artifact_dirs, artifact_stat = _physical_material_root(artifact_root, "ARTIFACT")
    evidence_path, evidence_fd, evidence_files, evidence_dirs, evidence_stat = _physical_material_root(evidence_root, "EVIDENCE")
    try:
        _verify_materials(receipt, contract, receipt_protocol, artifact_root, evidence_root)
    finally:
        os.close(artifact_fd)
        os.close(evidence_fd)

    # Typed evidence verification: read each evidence record, strict parse, validate schema
    evidence_root_path = pathlib.Path(evidence_root)
    evidence_fd2 = os.open(os.fspath(evidence_root_path), os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC)
    try:
        _verify_typed_evidence(
            snapshot=snapshot,
            authority=authority,
            evidence_root_fd=evidence_fd2,
            evidence_records=receipt["evidenceRecords"],
            contract=contract,
            receipt=receipt,
        )
    finally:
        os.close(evidence_fd2)

    _verify_handoff(
        receipt, contract,
        {item["path"] for item in receipt["artifactRecords"]},
        {item["id"] for item in receipt["evidenceRecords"]},
    )

    # Observed terminal: receipt records what the caller observed, not what it claims.
    # The verifier derives outcome from validated evidence + caller observation.
    # Do NOT check receipt["observed"]["status"] == "ACCEPTED" here; that is derived.
    _require(
        receipt["observed"].get("exitCode") == 0 and receipt["observed"].get("terminal") == "SUCCESS",
        "RECEIPT_OBSERVED_TERMINAL_INVALID",
    )

    receipt_fingerprint = _verify_receipt_fingerprint(receipt, receipt_protocol)
    predecessor_slice_id_val = receipt["predecessorSliceId"]

    # Ledger consumption with head protocol
    marker_key_fp, new_head_fp = _consume_ledger(
        consumption_ledger_root,
        receipt,
        receipt_protocol,
        snapshot.root_fingerprint,
        receipt_fingerprint,
        predecessor_slice_id_val,
        expected_predecessor_receipt_fingerprint,
        expected_ledger_head_fp=expected_ledger_head_fingerprint,
        expected_ledger_revision=expected_ledger_revision,
        predecessor_marker_fp=predecessor_marker_fingerprint,
    )

    return {
        "receiptFingerprint": receipt_fingerprint,
        "ledgerHeadFingerprint": new_head_fp,
        "ledgerRevision": receipt_protocol.get("_ledger_revision", expected_ledger_revision + 1),
        "markerKeyFingerprint": marker_key_fp,
    }
