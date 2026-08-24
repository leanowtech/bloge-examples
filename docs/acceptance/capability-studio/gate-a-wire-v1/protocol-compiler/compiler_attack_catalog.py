#!/usr/bin/env python3
"""Gate A Protocol Compiler — Attack Catalog (A1.3 self-test).

Exactly 60 attack vectors:
  38 RELATION       — one per authorityRelations fact
  2  TRUST_PROJECTION — trustBoundary drift + AUTHORITY_MATRIX projection selector drift
  19 BOUNDARY       — dependency(8) / provided ABI(5) / hermetic(4) / slice(1) / strict-JSON(1)
  1  ACTIVE_WITH_NULL_PINS — DRAFT_UNPINNED baseline + status=ACTIVE, external pins null

Each AttackVector: id / category / expected_code / mutate
  mutate(authority) -> mutated_copy   (acts on deepcopy; original untouched)
  mutate MUST change canonical bytes (test enforces this).

The catalog also supports raw-bytes mutations via mutate_bytes(authority) -> bytes.
When mutate_bytes is set, the production validator calls validate(raw_bytes) to exercise
strict_parse directly (e.g. BOUNDARY_19 injects a JSON duplicate key).
"""
from __future__ import annotations

import copy
import hashlib
import json
from dataclasses import dataclass, field
from typing import Any, Callable

# ---------------------------------------------------------------------------
# Typedefs
# ---------------------------------------------------------------------------

Catalog = list["AttackVector"]


@dataclass(frozen=True, slots=True)
class AttackVector:
    id: str
    category: str          # RELATION | TRUST_PROJECTION | BOUNDARY | ACTIVE_WITH_NULL_PINS
    expected_code: str
    mutate: Callable[[dict[str, Any]], dict[str, Any]]
    description: str = ""
    # When set, the production validator uses these raw bytes instead of
    # re-serialising mutate(authority).  Used for strict-JSON injection
    # attacks where the Python dict representation is ambiguous.
    mutate_bytes: Callable[[dict[str, Any]], bytes] | None = field(default=None, hash=False, compare=False)

    def apply(self, authority: dict[str, Any]) -> dict[str, Any]:
        return self.mutate(copy.deepcopy(authority))

    def apply_bytes(self, authority: dict[str, Any]) -> bytes:
        """Return mutated raw bytes for strict_parse validation.

        Uses mutate_bytes if provided, otherwise canonicalises the mutated dict."""
        if self.mutate_bytes is not None:
            return self.mutate_bytes(copy.deepcopy(authority))
        mutated = self.apply(authority)
        return json.dumps(
            mutated,
            ensure_ascii=False,
            indent=2,
            sort_keys=False,
            separators=(",", ": "),
        ).encode("utf-8") + b"\n"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _canon(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")


def _fp(value: Any) -> str:
    return f"sha256:{hashlib.sha256(_canon(value)).hexdigest()}"


def _derive_rel_id(fact: str, idx: int) -> str:
    """Stable unique ID from 1-based index + fact name."""
    return f"RELATION_{idx:02d}_{fact}"


def _rel_expected_code(fact: str) -> str:
    return f"PROTOCOL_RELATION_{fact}"


# ---------------------------------------------------------------------------
# Mutation factory helpers (plain functions, no closure shadowing)
# ---------------------------------------------------------------------------

def _mut_rel_change_authority(fact: str, new_authority: str) -> Callable[[dict[str, Any]], dict[str, Any]]:
    """RELATION mutation: change authority field."""
    def mut(aut: dict[str, Any]) -> dict[str, Any]:
        for r in aut["authorityRelations"]:
            if r["fact"] == fact:
                r["authority"] = new_authority
                break
        return aut
    return mut


def _mut_rel_change_consumer(fact: str, new_consumer: str) -> Callable[[dict[str, Any]], dict[str, Any]]:
    def mut(aut: dict[str, Any]) -> dict[str, Any]:
        for r in aut["authorityRelations"]:
            if r["fact"] == fact:
                r["consumer"] = new_consumer
                break
        return aut
    return mut


def _mut_rel_change_eq_policy(fact: str, new_policy: str) -> Callable[[dict[str, Any]], dict[str, Any]]:
    def mut(aut: dict[str, Any]) -> dict[str, Any]:
        for r in aut["authorityRelations"]:
            if r["fact"] == fact:
                r["equalityPolicy"] = new_policy
                break
        return aut
    return mut


def _mut_rel_change_comparison_time(fact: str, new_time: str) -> Callable[[dict[str, Any]], dict[str, Any]]:
    def mut(aut: dict[str, Any]) -> dict[str, Any]:
        for r in aut["authorityRelations"]:
            if r["fact"] == fact:
                r["comparisonTime"] = new_time
                break
        return aut
    return mut


def _mut_rel_change_forbidden_source(fact: str) -> Callable[[dict[str, Any]], dict[str, Any]]:
    def mut(aut: dict[str, Any]) -> dict[str, Any]:
        for r in aut["authorityRelations"]:
            if r["fact"] == fact:
                r["forbiddenSource"] = "AUTHORITY_SELF_DESCRIPTOR"
                break
        return aut
    return mut


# ---------------------------------------------------------------------------
# Semantic registry (Oracle ground truth — never self-generated)
# ---------------------------------------------------------------------------

def build_semantic_registry(authority: dict[str, Any]) -> dict[str, Any]:
    """Freeze baseline semantics for Oracle comparison. NOT derived from mutations."""
    # Build projectionPlan registry with AUTHORITY_MATRIX selector comparison
    plan = authority.get("compilerContract", {}).get("projectionPlan", [])
    projection_plan_registry = {}
    for entry in plan:
        pid = entry.get("projectionId")
        if pid:
            projection_plan_registry[pid] = entry.get("selectors", [])
    return {
        "authority_relations": [
            {k: v for k, v in rel.items() if k != "forbiddenSource"}
            for rel in authority.get("authorityRelations", [])
        ],
        "trust_boundary": copy.deepcopy(authority.get("trustBoundary", {})),
        "dependency_authority": copy.deepcopy(authority.get("dependencyAuthority", {})),
        "hermetic_execution": copy.deepcopy(authority.get("hermeticExecutionContract", {})),
        "role_contracts": {rc["role"]: rc for rc in authority.get("roleContracts", [])},
        "delivery_slices": {s["sliceId"]: s for s in authority.get("deliverySlices", [])},
        "canonicalization_gate": copy.deepcopy(authority.get("canonicalizationGate", {})),
        "schema_inventory": copy.deepcopy(authority.get("schemaInventoryPolicy", {})),
        "relation_count": len(authority.get("authorityRelations", [])),
        "projection_plan": projection_plan_registry,
    }


# ---------------------------------------------------------------------------
# 38 RELATION attacks — one per authorityRelations fact
# ---------------------------------------------------------------------------

def _build_relation_attacks(authority: dict[str, Any]) -> list[AttackVector]:
    """Generate exactly 38 RELATION AttackVectors, one per authorityRelations entry."""
    relations = authority.get("authorityRelations", [])
    assert len(relations) == 38, f"Expected 38 authorityRelations, got {len(relations)}"
    attacks: list[AttackVector] = []

    for idx, rel in enumerate(relations, start=1):
        fact = rel["fact"]
        eq_policy = rel.get("equalityPolicy", "")

        # Route to the appropriate mutation factory
        if fact == "PROTOCOL_AUTHORITY_RAW":
            mutate = _mut_rel_change_authority(fact, "CALLOUT_FORBIDDEN_AUTH_SOURCE")
        elif fact == "SCHEMA_SET_RAW":
            mutate = _mut_rel_change_authority(fact, "CALLOUT_FORBIDDEN_AUTH_SOURCE")
        elif fact == "TCK_DEFINITION_RAW":
            mutate = _mut_rel_change_authority(fact, "CALLOUT_TAMPERED_AUTH")
        elif fact == "REPLAY_PROFILE_RAW":
            mutate = _mut_rel_change_authority(fact, "CALLOUT_TAMPERED_AUTH")
        elif fact == "HARNESS_PROFILE_RAW":
            mutate = _mut_rel_change_authority(fact, "FORBIDDEN_CHALLENGE_PIN")
        elif fact == "ADMISSION_PROFILE_RAW":
            mutate = _mut_rel_change_authority(fact, "FORBIDDEN_ADMISSION_PIN")
        elif fact == "CANDIDATE_CODESOURCE_RAW":
            mutate = _mut_rel_change_consumer(fact, "CALLOUT_INCOMPATIBLE_ROLE")
        elif fact == "A1_CODESOURCE_RAW":
            mutate = _mut_rel_change_consumer(fact, "CALLOUT_INCOMPATIBLE_ROLE")
        elif fact == "HARNESS_CODESOURCE_RAW":
            mutate = _mut_rel_change_authority(fact, "FORBIDDEN_OUTER_AUTH")
        elif fact == "A2_CODESOURCE_RAW":
            mutate = _mut_rel_change_authority(fact, "FORBIDDEN_OUTER_AUTH")
        elif fact == "PROVIDER_CODESOURCE_RAW":
            mutate = _mut_rel_change_consumer(fact, "CALLOUT_INCOMPATIBLE_ROLE")
        elif fact == "CHILD_PROCESS_EXIT":
            mutate = _mut_rel_change_authority(fact, "CALLOUT_FORBIDDEN_AUTH")
        elif fact == "OUTER_PROCESS_EXIT":
            mutate = _mut_rel_change_authority(fact, "FORBIDDEN_OUTER_AUTH")
        elif fact == "ROLE_ARTIFACT_INDEPENDENCE":
            mutate = _mut_rel_change_eq_policy(fact, "EXACT_RAW_EQUAL")
        elif fact == "DEPENDENCY_LOCK_RAW":
            mutate = _mut_rel_change_authority(fact, "CALLOUT_TAMPERED_DEP")
        elif fact == "PROVIDED_ABI_EXTERNAL":
            mutate = _mut_rel_change_eq_policy(fact, "EXACT_RAW_EQUAL")
        elif fact == "CANDIDATE_RUNTIME_CLOSURE":
            mutate = _mut_rel_change_authority(fact, "CALLOUT_TAMPERED_DEP")
        elif fact == "CANDIDATE_BUILD_PROFILE":
            mutate = _mut_rel_change_authority(fact, "CALLOUT_TAMPERED_DEP")
        elif fact == "LEGACY_CLI_COMPATIBILITY":
            mutate = _mut_rel_change_eq_policy(fact, "EXACT_RAW_EQUAL")
        elif fact == "HERMETIC_LAUNCHER_IDENTITY":
            mutate = _mut_rel_change_comparison_time(fact, "NEVER")
        elif fact == "HERMETIC_ORACLE_VISIBILITY":
            mutate = _mut_rel_change_eq_policy(fact, "EXACT_RAW_EQUAL")
        elif fact == "BOUNDED_CAPTURE_POLICY":
            mutate = _mut_rel_change_comparison_time(fact, "NEVER")
        elif fact == "DESCENDANT_KILL_POLICY":
            mutate = _mut_rel_change_comparison_time(fact, "NEVER")
        elif fact == "SLICE_ACCEPTANCE_ID":
            mutate = _mut_rel_change_comparison_time(fact, "NEVER")
        elif fact == "SLICE_TEST_SET":
            mutate = _mut_rel_change_comparison_time(fact, "NEVER")
        elif fact == "GATE_SCHEMA_FILESYSTEM_CLOSED_SET":
            mutate = _mut_rel_change_eq_policy(fact, "ROLE_SPECIFIC")
        elif fact == "REVIEWER_SCHEMA_FILESYSTEM_CLOSED_SET":
            mutate = _mut_rel_change_eq_policy(fact, "ROLE_SPECIFIC")
        elif fact == "HERMETIC_LAUNCHER_OBSERVATION_NONCE":
            mutate = _mut_rel_change_comparison_time(fact, "NEVER")
        elif fact == "HERMETIC_LAUNCHER_OBSERVATION_INVOCATION":
            mutate = _mut_rel_change_eq_policy(fact, "EXACT_RAW_EQUAL")
        elif fact == "HERMETIC_PROCESS_OBSERVATION":
            mutate = _mut_rel_change_comparison_time(fact, "NEVER")
        elif fact == "SLICE_AUTHORITY_BINDING":
            mutate = _mut_rel_change_authority(fact, "AUTHORITY_SELF_REPORT")
        elif fact == "SLICE_SOURCE_TREE":
            mutate = _mut_rel_change_comparison_time(fact, "NEVER")
        elif fact == "SLICE_TOOLCHAIN_IDENTITY":
            mutate = _mut_rel_change_comparison_time(fact, "NEVER")
        elif fact == "SLICE_PREDECESSOR_RECEIPT":
            mutate = _mut_rel_change_comparison_time(fact, "NEVER")
        elif fact == "SLICE_RECEIPT_SELF_FINGERPRINT":
            mutate = _mut_rel_change_authority(fact, "RECEIPT_SELF_DECLARATION")
        elif fact == "RUNTIME_DEPENDENCY_CLOSURE":
            mutate = _mut_rel_change_authority(fact, "CALLOUT_TAMPERED_DEP")
        elif fact == "PROVIDER_DESCRIPTOR_VS_INTERFACE":
            mutate = _mut_rel_change_authority(fact, "PROVIDER_SELF_DESCRIPTOR")
        elif fact == "HERMETIC_SCHEMA_NAMESPACE":
            mutate = _mut_rel_change_comparison_time(fact, "NEVER")
        else:
            mutate = _mut_rel_change_authority(fact, "CALLOUT_TAMPERED_AUTH_SOURCE")

        attacks.append(AttackVector(
            id=_derive_rel_id(fact, idx),
            category="RELATION",
            expected_code=_rel_expected_code(fact),
            mutate=mutate,
            description=f"RELATION-{idx} {fact} [{eq_policy}]",
        ))

    return attacks


# ---------------------------------------------------------------------------
# 2 TRUST_PROJECTION attacks
# ---------------------------------------------------------------------------

def _build_trust_projection_attacks() -> list[AttackVector]:
    attacks: list[AttackVector] = []

    # TP-01: trustBoundary hostile list drift
    def mutate_trust_boundary(aut: dict[str, Any]) -> dict[str, Any]:
        aut["trustBoundary"]["hostile"].append("TRUST_BOUNDARY_DRIFT_ATTACK")
        return aut

    attacks.append(AttackVector(
        id="TRUST_PROJECTION_01_TRUST_BOUNDARY_DRIFT",
        category="TRUST_PROJECTION",
        expected_code="PROTOCOL_TRUST_BOUNDARY_DRIFT",
        mutate=mutate_trust_boundary,
        description="trustBoundary hostile list drift",
    ))

    # TP-02: AUTHORITY_MATRIX projection selector sourcePointer drift
    def mutate_authority_matrix_selector(aut: dict[str, Any]) -> dict[str, Any]:
        plan = aut.get("compilerContract", {}).get("projectionPlan", [])
        for entry in plan:
            if entry.get("projectionId") == "AUTHORITY_MATRIX":
                for sel in entry.get("selectors", []):
                    if sel.get("key") == "trustBoundary":
                        sel["sourcePointer"] = "/trustBoundary_DRIFTED"
                        break
                break
        return aut

    attacks.append(AttackVector(
        id="TRUST_PROJECTION_02_PROJECTION_SELECTOR_DRIFT",
        category="TRUST_PROJECTION",
        expected_code="PROTOCOL_PROJECTION_SELECTOR_DRIFT",
        mutate=mutate_authority_matrix_selector,
        description="AUTHORITY_MATRIX projection selector sourcePointer drift (trustBoundary selector path changed)",
    ))

    return attacks


# ---------------------------------------------------------------------------
# 19 BOUNDARY attacks
# ---------------------------------------------------------------------------

def _build_boundary_attacks() -> list[AttackVector]:
    attacks: list[AttackVector] = []

    # --- 8 dependencyAuthority attacks (BA-01 to BA-08) ---
    dep_mutations: list[tuple[str, str, Callable[[dict], None], str]] = [
        (
            "BOUNDARY_01_DEP_MODE", "PROTOCOL_DEPENDENCY_AUTHORITY_MODE_DRIFT",
            lambda aut: aut["dependencyAuthority"].__setitem__("authorityMode", "DRIFT_MODE"),
            "dependencyAuthority.authorityMode drift",
        ),
        (
            "BOUNDARY_02_DEP_CLOSED", "PROTOCOL_DEPENDENCY_CLOSED_SET_DRIFT",
            lambda aut: aut["dependencyAuthority"].__setitem__("closed", False),
            "dependencyAuthority.closed=false drift",
        ),
        (
            # BA-03: status=ACTIVE with parentLockFingerprint but external pins still null
            # → validator sees parentLockFingerprint present + pins unpinned → prior HEAD missing
            "BOUNDARY_03_DEP_ACTIVE", "PROTOCOL_DEPENDENCY_ACTIVE_REQUIRES_PREVIOUS_HEAD",
            lambda aut: (
                aut["dependencyAuthority"].__setitem__("status", "ACTIVE"),
                aut["dependencyAuthority"].__setitem__("parentLockFingerprint", {
                    "kind": "RAW_BYTES",
                    "algorithm": "SHA-256",
                    "domain": "RG-CS-GATE-A-PARENT-LOCK-v1",
                    "value": "sha256:" + "a" * 64,
                }),
            ),
            "dependencyAuthority.status=ACTIVE with parentLockFingerprint but external pins unpinned",
        ),
        (
            "BOUNDARY_04_DEP_SNAP_STATUS", "PROTOCOL_DEPENDENCY_SNAPSHOT_STATUS_DRIFT",
            lambda aut: aut["dependencyAuthority"].get("sourceRepositorySnapshotId", {}).__setitem__("status", "DRIFT_PINNED"),
            "dependencyAuthority.sourceRepositorySnapshotId.status drift",
        ),
        (
            "BOUNDARY_05_DEP_TREE_FP", "PROTOCOL_DEPENDENCY_TREE_FINGERPRINT_DRIFT",
            lambda aut: aut["dependencyAuthority"].get("dependencyTreeFingerprint", {}).__setitem__("value", "sha256:" + "d" * 64),
            "dependencyAuthority.dependencyTreeFingerprint.value drift",
        ),
        (
            "BOUNDARY_06_DEP_POM_FP", "PROTOCOL_DEPENDENCY_POM_FINGERPRINT_DRIFT",
            lambda aut: aut["dependencyAuthority"].get("sourcePomRawFingerprint", {}).__setitem__("value", "sha256:" + "e" * 64),
            "dependencyAuthority.sourcePomRawFingerprint.value drift",
        ),
        (
            "BOUNDARY_07_DEP_EXTRA_ENTRY", "PROTOCOL_DEPENDENCY_EXTRA_ENTRY",
            lambda aut: aut["dependencyAuthority"]["dependencies"].append(
                {"lockId": "DRIFT_EXTRA_DEP", "scope": "compile", "entryPath": "DRIFT/extra.jar"}
            ),
            "dependencyAuthority.dependencies extra entry",
        ),
        (
            "BOUNDARY_08_DEP_TOOLCHAIN_EXTRA", "PROTOCOL_DEPENDENCY_TOOLCHAIN_EXTRA_ENTRY",
            lambda aut: aut["dependencyAuthority"]["toolchainPluginLockRefs"].append(
                {"lockId": "DRIFT_EXTRA_PLUGIN", "mode": "REQUIRED_EXTERNAL_PIN",
                 "requiredExternalPinId": "GATE_A_DRIFT_EXTRA_PLUGIN_V1",
                 "status": "UNAVAILABLE_UNTIL_CALLER_PINNED"}
            ),
            "dependencyAuthority.toolchainPluginLockRefs extra entry",
        ),
    ]

    for aid, code, mut_fn, desc in dep_mutations:
        def make_mut(f: type(mut_fn)) -> Callable[[dict[str, Any]], dict[str, Any]]:
            def inner(aut: dict[str, Any]) -> dict[str, Any]:
                f(aut)
                return aut
            return inner
        attacks.append(AttackVector(
            id=aid, category="BOUNDARY", expected_code=code,
            mutate=make_mut(mut_fn), description=desc,
        ))

    # --- 5 provided ABI attacks (BA-09 to BA-13) targeting
    # TCK_PROVIDER.packagingContract.providedAbiDependencies ---
    #
    # The TCK_PROVIDER role has exactly one entry:
    #   packagingContract.providedAbiDependencies[0]:
    #     providedId: "CANDIDATE_STAGE_ACCEPTANCE_SPI"
    #     candidateCoordinate: {groupId, artifactId, version, classifier}
    #     candidateArtifactFingerprintPinField / candidateClassFingerprintPinField
    #     embeddingPolicy: "PROVIDED_ABI_NOT_EMBEDDED"
    #     candidateSpiInterfaceClass: "com.leanowtech.bloge.gateway.testkit.CapabilityStudioStage..."
    #
    # Mutations target these exact semantic positions.

    # Helper: find TCK_PROVIDER role contract in authority
    def _find_tck(aut: dict[str, Any]) -> dict[str, Any] | None:
        for rc in aut.get("roleContracts", []):
            if rc.get("role") == "TCK_PROVIDER":
                return rc
        return None

    def _abi_mutate_desc(aut: dict[str, Any]) -> dict[str, Any]:
        tck = _find_tck(aut)
        if tck is None:
            return aut
        tck["packagingContract"]["providedAbiDependencies"] = [
            {
                "providedId": "CANDIDATE_STAGE_ACCEPTANCE_SPI",
                "candidateRole": "IMPLEMENTATION_CANDIDATE",
                "candidateCoordinate": {
                    "groupId": "DRIFT.GRP",
                    "artifactId": "drift-art",
                    "version": "99.99.99",
                    "classifier": "gate-a-candidate",
                },
                "candidateArtifactFingerprintPinField": "expectedImplementationCandidateRawFingerprint",
                "candidateClassEntryPath": "com/drift/Spi.class",
                "candidateClassFingerprintPinField": "expectedCandidateSpiClassRawFingerprint",
                "embeddingPolicy": "PROVIDED_ABI_NOT_EMBEDDED",
                "candidateSpiInterfaceClassEntryPath": "com/drift/Spi.class",
                "candidateSpiInterfaceClass": "drift.Spi",
                "providerServiceDescriptorEntryPath": "META-INF/services/drift.Spi",
                "providerServiceDescriptorInterfaceClass": "drift.Spi",
            }
        ]
        return aut

    def _abi_mutate_gav(aut: dict[str, Any]) -> dict[str, Any]:
        tck = _find_tck(aut)
        if tck is None:
            return aut
        abi = list(tck["packagingContract"]["providedAbiDependencies"])
        abi[0]["candidateCoordinate"]["groupId"] = "DRIFT_GAV"
        abi[0]["candidateCoordinate"]["artifactId"] = "x"
        tck["packagingContract"]["providedAbiDependencies"] = abi
        return aut

    def _abi_mutate_spi(aut: dict[str, Any]) -> dict[str, Any]:
        tck = _find_tck(aut)
        if tck is None:
            return aut
        abi = list(tck["packagingContract"]["providedAbiDependencies"])
        # Only change candidateClassEntryPath, not candidateSpiInterfaceClass
        # (candidateSpiInterfaceClass is checked in DESCRIPTOR_DRIFT before SPI_ENTRY_DRIFT)
        abi[0]["candidateClassEntryPath"] = "com/DRIFT/SPI.class"
        tck["packagingContract"]["providedAbiDependencies"] = abi
        return aut

    def _abi_mutate_overlap(aut: dict[str, Any]) -> dict[str, Any]:
        tck = _find_tck(aut)
        if tck is None:
            return aut
        # Get a real dependency coordinate from dependencyAuthority
        deps = aut.get("dependencyAuthority", {}).get("dependencies", [])
        if not deps:
            return aut
        real_coord = deps[0].get("coordinate", {})
        # Set ABI candidateCoordinate to match embedded dependency (real overlap)
        abi = list(tck["packagingContract"]["providedAbiDependencies"])
        abi[0]["candidateCoordinate"]["groupId"] = real_coord.get("groupId", "")
        abi[0]["candidateCoordinate"]["artifactId"] = real_coord.get("artifactId", "")
        abi[0]["candidateCoordinate"]["version"] = real_coord.get("version", "")
        tck["packagingContract"]["providedAbiDependencies"] = abi
        return aut

    def _abi_mutate_spi_missing(aut: dict[str, Any]) -> dict[str, Any]:
        tck = _find_tck(aut)
        if tck is None:
            return aut
        abi = list(tck["packagingContract"]["providedAbiDependencies"])
        # Only remove fields checked in SPI_MISSING (not candidateSpiInterfaceClass
        # which is checked in DESCRIPTOR_DRIFT before SPI_MISSING)
        abi[0].pop("candidateSpiInterfaceClassEntryPath", None)
        abi[0].pop("providerServiceDescriptorEntryPath", None)
        abi[0].pop("providerServiceDescriptorInterfaceClass", None)
        tck["packagingContract"]["providedAbiDependencies"] = abi
        return aut

    abi_attacks = [
        ("BOUNDARY_09_ABI_DESC", "PROTOCOL_PROVIDED_ABI_DESCRIPTOR_DRIFT", _abi_mutate_desc,
         "providedAbiDependencies descriptor drift (TCK_PROVIDER.packagingContract)"),
        ("BOUNDARY_10_ABI_GAV", "PROTOCOL_PROVIDED_ABI_GAV_DRIFT", _abi_mutate_gav,
         "providedAbiDependencies candidateCoordinate groupId/artifactId drift"),
        ("BOUNDARY_11_ABI_SPI", "PROTOCOL_PROVIDED_ABI_SPI_ENTRY_DRIFT", _abi_mutate_spi,
         "providedAbiDependencies candidateClassEntryPath drift"),
        ("BOUNDARY_12_ABI_OVERLAP", "PROTOCOL_PROVIDED_ABI_EMBEDDED_OVERLAP", _abi_mutate_overlap,
         "providedAbiDependencies candidateCoordinate overlaps baseline groupId"),
        ("BOUNDARY_13_ABI_SPI_MISSING", "PROTOCOL_PROVIDED_ABI_SPI_MISSING", _abi_mutate_spi_missing,
         "providedAbiDependencies missing required SPI interface fields"),
    ]

    for aid, code, mut_fn, desc in abi_attacks:
        attacks.append(AttackVector(
            id=aid, category="BOUNDARY", expected_code=code,
            mutate=mut_fn, description=desc,
        ))

    # --- 4 hermetic attacks (BA-14 to BA-17) ---
    hermetic_mutations: list[tuple[str, str, Callable[[dict], None], str]] = [
        (
            "BOUNDARY_14_HERM_POLICY", "PROTOCOL_HERMETIC_LAUNCHER_POLICY_DRIFT",
            lambda aut: aut["hermeticExecutionContract"].__setitem__("launcherPolicy", "PATH_LOOKUP_ALLOWED"),
            "hermeticExecutionContract.launcherPolicy drift",
        ),
        (
            "BOUNDARY_15_HERM_MISSING", "PROTOCOL_HERMETIC_MISSING_LAUNCHER_DRIFT",
            lambda aut: aut["hermeticExecutionContract"].__setitem__("missingLauncherPolicy", "PERMISSIVE_OPEN"),
            "hermeticExecutionContract.missingLauncherPolicy drift",
        ),
        (
            "BOUNDARY_16_HERM_NETWORK", "PROTOCOL_HERMETIC_NETWORK_ISOLATION_DRIFT",
            lambda aut: aut["hermeticExecutionContract"].__setitem__("network", {"mode": "FULL", "dns": "ALLOW", "unixSockets": "ALLOW"}),
            "hermeticExecutionContract.network drift",
        ),
        (
            "BOUNDARY_17_HERM_CAPTURE", "PROTOCOL_HERMETIC_CAPTURE_LIMIT_DRIFT",
            lambda aut: aut["hermeticExecutionContract"].get("capture", {}).__setitem__("stdoutLimitBytes", 999999999),
            "hermeticExecutionContract.capture stdoutLimitBytes drift",
        ),
    ]

    for aid, code, mut_fn, desc in hermetic_mutations:
        def make_herm_mut(f: type(mut_fn)) -> Callable[[dict[str, Any]], dict[str, Any]]:
            def inner(aut: dict[str, Any]) -> dict[str, Any]:
                f(aut)
                return aut
            return inner
        attacks.append(AttackVector(
            id=aid, category="BOUNDARY", expected_code=code,
            mutate=make_herm_mut(mut_fn), description=desc,
        ))

    # --- 1 slice topology attack (BA-18) ---
    def mutate_slice_topology(aut: dict[str, Any]) -> dict[str, Any]:
        for s in aut.get("deliverySlices", []):
            if s["sliceId"] == "A1.3":
                s["allowedPaths"].append("**/DRIFT/**")
                break
        return aut

    attacks.append(AttackVector(
        id="BOUNDARY_18_SLICE_TOPOLOGY_RECEIPT",
        category="BOUNDARY",
        expected_code="PROTOCOL_SLICE_TOPOLOGY_RECEIPT_DRIFT",
        mutate=mutate_slice_topology,
        description="A1.3 deliverySlice allowedPaths topology drift",
    ))

    # --- 1 strict JSON duplicate-key attack (BA-19) ---
    # Dict assignment CANNOT create a JSON duplicate key because the last
    # value silently overwrites the first.  We use raw bytes mutation so
    # strict_parse is called on bytes that genuinely contain a duplicate.



    def _strict_json_duplicate_mutate_bytes(aut: dict[str, Any]) -> bytes:
        import copy as _copy

        base = _copy.deepcopy(aut)
        base["_DUP_INJECT"] = "first"

        # Serialize with strict format (indent=2, no trailing comma on last property)
        raw_str = json.dumps(
            base,
            ensure_ascii=False,
            indent=2,
            sort_keys=False,
            separators=(",", ": "),
        ) + "\n"

        # The _DUP_INJECT key line looks like:
        #   "  _DUP_INJECT": "first"
        # (no trailing comma; file ends "...first"\n}"\n")
        key_line = '  "_DUP_INJECT": "first"'
        pos = raw_str.find(key_line)
        if pos < 0:
            raise AssertionError("key not found in serialised authority")

        # Find where this line ends (the newline after the value)
        line_end = raw_str.find("\n", pos)
        if line_end < 0:
            raise AssertionError("newline not found after key line")

        # prefix = everything up to and including the key line
        prefix = raw_str[:line_end]   # "...first"  (no comma, no newline)
        # suffix = from the newline onwards: "\n}"\n"
        suffix = raw_str[line_end:]    # "\n}"\n"

        # Rebuild so that _DUP_INJECT appears twice:
        #   "  _DUP_INJECT": "first",   <- original key with comma added
        #   "  _DUP_INJECT": "second"   <- duplicate key (now last property, NO comma)
        #   "}"
        duplicate_entry = key_line.replace('"first"', '"second"')  # "second" value
        rebuilt = (
            prefix     # "...first"            (no trailing comma)
            + ","     # "...first,"            (comma for first key)
            + "\n"   # "...first," + newline   (after first key)
            + duplicate_entry
            + "\n"   # duplicate entry (no trailing comma - last property)
            + "}"     # closing brace
            + "\n"   # final newline
        )
        return rebuilt.encode("utf-8")
    def _strict_json_duplicate_dict_mutate(aut: dict[str, Any]) -> dict[str, Any]:
        # Defensive: the dict-level mutate is a no-op for the byte-change test,
        # but required by the AttackVector interface.
        # The real mutation is in _strict_json_duplicate_mutate_bytes.
        return aut

    attacks.append(AttackVector(
        id="BOUNDARY_19_STRICT_JSON_DUPLICATE",
        category="BOUNDARY",
        expected_code="PROTOCOL_JSON_DUPLICATE_MEMBER",
        mutate=_strict_json_duplicate_dict_mutate,
        mutate_bytes=_strict_json_duplicate_mutate_bytes,
        description="Strict JSON duplicate key injection via raw bytes",
    ))

    return attacks


# ---------------------------------------------------------------------------
# 1 ACTIVE_WITH_NULL_PINS attack
# ---------------------------------------------------------------------------

def _build_active_with_null_pins_attack() -> AttackVector:
    # ACTIVE_WITH_NULL_PINS: status=ACTIVE with external pins still null
    # (no parentLockFingerprint).  ProtocolSemanticValidator sees ACTIVE with
    # parentLockFingerprint=None and pins unpinned → STATUS_NOT_PINNED.
    def mutate(aut: dict[str, Any]) -> dict[str, Any]:
        aut["dependencyAuthority"]["status"] = "ACTIVE"
        # parentLockFingerprint intentionally left as null
        return aut

    return AttackVector(
        id="ACTIVE_WITH_NULL_PINS",
        category="ACTIVE_WITH_NULL_PINS",
        expected_code="PROTOCOL_DEPENDENCY_STATUS_NOT_PINNED",
        mutate=mutate,
        description="dependencyAuthority.status=ACTIVE with external pins still null (DRAFT_UNPINNED baseline)",
    )


# ---------------------------------------------------------------------------
# Public factory
# ---------------------------------------------------------------------------

def build_attack_catalog(baseline_authority: dict[str, Any]) -> tuple[AttackVector, ...]:
    """Build exactly 60 attack vectors from baseline authority."""
    rel = _build_relation_attacks(baseline_authority)
    assert len(rel) == 38, f"RELATION count={len(rel)}, expected 38"

    tp = _build_trust_projection_attacks()
    assert len(tp) == 2, f"TRUST_PROJECTION count={len(tp)}, expected 2"

    bnd = _build_boundary_attacks()
    assert len(bnd) == 19, f"BOUNDARY count={len(bnd)}, expected 19"

    anp = _build_active_with_null_pins_attack()

    all_vecs: list[AttackVector] = rel + tp + bnd + [anp]
    assert len(all_vecs) == 60, f"Total count={len(all_vecs)}, expected 60"

    return tuple(all_vecs)


# ---------------------------------------------------------------------------
# Validation helper (catalog integrity — not production semantic validation)
# ---------------------------------------------------------------------------

def validate_catalog(
    catalog: tuple[AttackVector, ...],
    baseline_authority: dict[str, Any],
) -> list[str]:
    """Validate catalog integrity. Returns list of failure messages; empty = pass."""
    failures: list[str] = []

    if len(catalog) != 60:
        failures.append(f"TOTAL_COUNT: got {len(catalog)}, expected 60")

    cats = [v.category for v in catalog]
    for cat, expected in [("RELATION", 38), ("TRUST_PROJECTION", 2), ("BOUNDARY", 19), ("ACTIVE_WITH_NULL_PINS", 1)]:
        actual = cats.count(cat)
        if actual != expected:
            failures.append(f"CATEGORY_COUNT_{cat}: got {actual}, expected {expected}")

    ids = [v.id for v in catalog]
    if len(ids) != len(set(ids)):
        dupes = sorted({i for i in ids if ids.count(i) > 1})
        failures.append(f"DUPLICATE_IDS: {dupes}")

    id_code_pairs = [(v.id, v.expected_code) for v in catalog]
    seen: set[tuple[str, str]] = set()
    for pair in id_code_pairs:
        if pair in seen:
            failures.append(f"DUPLICATE_ID_CODE_PAIR: {pair}")
        seen.add(pair)

    orig_fp = _fp(baseline_authority)
    for vec in catalog:
        # For raw-only mutations (like B19 strict-JSON duplicate), use apply_bytes
        # For dict mutations, use apply then re-serialize
        if vec.mutate_bytes is not None:
            mutated_bytes = vec.apply_bytes(baseline_authority)
            if orig_fp == _fp(json.loads(mutated_bytes.decode("utf-8"))):
                failures.append(f"MUTATION_NOOP:{vec.id} — canonical bytes unchanged")
        else:
            mutated = vec.apply(baseline_authority)
            if orig_fp == _fp(mutated):
                failures.append(f"MUTATION_NOOP:{vec.id} — canonical bytes unchanged")

    after_fp = _fp(baseline_authority)
    if orig_fp != after_fp:
        failures.append("BASELINE_MUTATED — original authority was modified")

    expected_facts = [r["fact"] for r in baseline_authority.get("authorityRelations", [])]
    for idx, fact in enumerate(expected_facts, start=1):
        expected_id = _derive_rel_id(fact, idx)
        matching = [v for v in catalog if v.id == expected_id]
        if len(matching) != 1:
            failures.append(f"MISSING_RELATION_FACT:{fact} (expected id={expected_id})")
        else:
            if _rel_expected_code(fact) != matching[0].expected_code:
                failures.append(
                    f"WRONG_CODE:{expected_id}: "
                    f"got {matching[0].expected_code}, "
                    f"expected {_rel_expected_code(fact)}"
                )

    valid_cats = {"RELATION", "TRUST_PROJECTION", "BOUNDARY", "ACTIVE_WITH_NULL_PINS"}
    for vec in catalog:
        if vec.category not in valid_cats:
            failures.append(f"INVALID_CATEGORY:{vec.id}:{vec.category}")

    return failures
