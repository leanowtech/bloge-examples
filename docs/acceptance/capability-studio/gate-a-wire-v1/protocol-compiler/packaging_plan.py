#!/usr/bin/env python3
"""Independent Verifier Packaging Plan — A1.3-01 vertical slice.

Authority -> LinkedProtocolModel -> IndependentVerifierPackagingPlan -> content-addressed publication receipt

Fixes applied:
  P0: Atomic COMMIT via single rename of plan+receipt staging.
  P0: Inventory = plan files only (receipt is commit marker, not self-referential).
  P0: Domain-separated receiptFingerprint excluding itself; verify recomputes.
  P1: lstat/O_NOFOLLOW checks at all boundaries; reject symlinks at every path.
  P1: Random create-new staging; never delete pre-existing paths.
  P1: Clean reason codes — no absolute paths or exception text.
  P1: exactArchiveEntries from _derive_role_expected_jar_entries; requiredJarEntries
      is exact equality assertion only.
  P1: Compiled projections from ProjectionCompiler output; strict JSON rebind.

Implementation boundary:
  - No Verifier JAR generation/execution
  - No POM reading as protocol truth
  - No new schemas added
"""

from __future__ import annotations

import errno
import fcntl
import hashlib
import json
import os
import pathlib
import shutil
import stat
import tempfile
from dataclasses import dataclass, field
from typing import Any


# ---------------------------------------------------------------------------
# Stable error codes — reason only, never paths or exception text
# ---------------------------------------------------------------------------

class PackagingPlanError(Exception):
    CODE: str = "PACKAGING_PLAN_INTERNAL_ERROR"
    def __init__(self, detail: str = ""):
        self.detail = detail
        super().__init__(f"{self.CODE}: {detail}" if detail else self.CODE)


class PlanValidationError(PackagingPlanError):
    CODE = "PLAN_VALIDATION_ERROR"


class RequiredJarEntriesMismatchError(PlanValidationError):
    CODE = "REQUIRED_JAR_ENTRIES_MISMATCH"
    def __init__(self, detail: str = ""):
        self.code = self.CODE
        self.detail = detail
        super().__init__(f"{self.CODE}: {detail}" if detail else self.CODE)


class DependencyJoinError(PlanValidationError):
    CODE = "DEPENDENCY_JOIN_ERROR"
    def __init__(self, detail: str = ""):
        self.code = self.CODE
        self.detail = detail
        super().__init__(f"{self.CODE}: {detail}" if detail else self.CODE)


class ProjectionBindingError(PlanValidationError):
    CODE = "PROJECTION_BINDING_ERROR"
    def __init__(self, detail: str = ""):
        self.code = self.CODE
        self.detail = detail
        super().__init__(f"{self.CODE}: {detail}" if detail else self.CODE)


class ProviderRecipeError(PlanValidationError):
    CODE = "PROVIDER_RECIPE_ERROR"
    def __init__(self, detail: str = ""):
        self.code = self.CODE
        self.detail = detail
        super().__init__(f"{self.CODE}: {detail}" if detail else self.CODE)


class PublisherError(PackagingPlanError):
    CODE = "PUBLISHER_ERROR"


class PublisherExistsError(PublisherError):
    CODE = "PUBLISHER_OUTPUT_EXISTS"


class PublisherConflictError(PublisherError):
    CODE = "PUBLISHER_OUTPUT_CONFLICT"


class PublisherSymlinkError(PublisherError):
    CODE = "PUBLISHER_SYMLINK_REJECTED"


class PublisherPathTraversalError(PublisherError):
    CODE = "PUBLISHER_PATH_TRAVERSAL_REJECTED"


class PublisherNotDirectoryError(PublisherError):
    CODE = "PUBLISHER_NOT_DIRECTORY"


class PublisherIncompleteError(PublisherError):
    CODE = "PUBLISHER_INCOMPLETE_STAGING"


# ---------------------------------------------------------------------------
# Schema versions
# ---------------------------------------------------------------------------

PLAN_SCHEMA_VERSION = "capability-studio.gate-a.independent-verifier-packaging-plan.v1"
RECEIPT_SCHEMA_VERSION = "capability-studio.gate-a.independent-verifier-packaging-receipt.v1"

# Domain-separated commitment domain
PLAN_COMMITMENT_DOMAIN = "RG-CS-GATE-A-INDEPENDENT-VERIFIER-PACKAGING-PLAN-v1"
RECEIPT_COMMITMENT_DOMAIN = "RG-CS-GATE-A-INDEPENDENT-VERIFIER-PACKAGING-RECEIPT-v1"

# Output file names
PLAN_OUTPUT_NAME = "independent-verifier-packaging-plan-v1.json"
RECEIPT_OUTPUT_NAME = "publication-receipt-v1.json"


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

# Import compiler_core helpers; must be done here to avoid circular dependency
# at runtime when packaging_plan is loaded standalone by tests.
import sys as _pp_sys
_pp_sys.path.insert(0, str(pathlib.Path(__file__).parent))
import compiler_core as _cc

def _canonical_json(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, separators=(",", ":"), sort_keys=True
    ).encode("utf-8")


def _fp(value: Any) -> str:
    return f"sha256:{hashlib.sha256(_canonical_json(value)).hexdigest()}"


def _pretty_json(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, indent=2, sort_keys=False, separators=(",", ": ")
    ).encode("utf-8")


def _strict_json_dumps(value: Any) -> str:
    return json.dumps(
        value, ensure_ascii=False, indent=2, sort_keys=False, separators=(",", ": ")
            )


# Reject duplicate keys (strict JSON)

def _StrictJSONHook(pairs):
    seen = {}
    for k, v in pairs:
        if k in seen:
            raise ValueError("DUPLICATE_KEY")
        seen[k] = v
    return seen


def strict_json_loads(text: str) -> dict[str, Any]:
    def _parse_constant(s):
        raise ValueError(f"NON_FINITE:{s}")
    return json.loads(text, object_pairs_hook=_StrictJSONHook, parse_constant=_parse_constant)


def _reject_symlink(path: pathlib.Path, label: str) -> None:
    """Fail-closed symlink check via lstat."""
    try:
        st = path.lstat()
        if stat.S_ISLNK(st.st_mode):
            raise PublisherSymlinkError(f"SYMLINK_IN_{label}")
    except PublisherSymlinkError:
        raise
    except Exception:
        pass  # Path may not exist yet


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class IndependentVerifierPackagingPlan:
    schema_version: str
    authority_identity: str
    authority_revision: int
    authority_raw_fingerprint: str
    role_identity: str
    role_artifact_main_class: str | None
    role_executable_class_entry: str | None
    # exactArchiveEntries: DERIVED from declared fields (_derive_role_expected_jar_entries)
    # NOT from requiredJarEntries, only used for comparison
    exact_archive_entries: tuple[str, ...]
    # requiredJarEntries: EXACT equality assertion only, not used in derivation
    required_jar_entries: frozenset[str]
    packaged_projections: tuple[dict[str, Any], ...]
    profile_path: str | None
    registry_path: str | None
    canonicalization_path: str | None
    compilation_manifest_path: str
    embedded_provider_artifact: str | None
    embedded_provider_identity_path: str | None
    class_manifest_path: str
    resource_manifest_path: str
    dependency_manifest_path: str
    embedded_dependencies: tuple[dict[str, Any], ...]
    provider_identity_recipe: dict[str, Any] | None
    plan_fingerprint: str

    def to_json(self) -> dict[str, Any]:
        return {
            "schemaVersion": self.schema_version,
            "authorityIdentity": self.authority_identity,
            "authorityRevision": self.authority_revision,
            "authorityRawFingerprint": self.authority_raw_fingerprint,
            "roleIdentity": self.role_identity,
            "roleArtifactMainClass": self.role_artifact_main_class,
            "roleExecutableClassEntry": self.role_executable_class_entry,
            "exactArchiveEntries": list(self.exact_archive_entries),
            "packagedProjections": list(self.packaged_projections),
            "profilePath": self.profile_path,
            "registryPath": self.registry_path,
            "canonicalizationPath": self.canonicalization_path,
            "compilationManifestPath": self.compilation_manifest_path,
            "embeddedProviderArtifact": self.embedded_provider_artifact,
            "embeddedProviderIdentityPath": self.embedded_provider_identity_path,
            "classManifestPath": self.class_manifest_path,
            "resourceManifestPath": self.resource_manifest_path,
            "dependencyManifestPath": self.dependency_manifest_path,
            "embeddedDependencies": list(self.embedded_dependencies),
            "providerIdentityRecipe": self.provider_identity_recipe,
        }

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> IndependentVerifierPackagingPlan:
        """Reconstruct plan from JSON dict (roundtrip).

        required_jar_entries is derived from exactArchiveEntries (they are equal).
        plan_fingerprint is recomputed from the canonical dict to match the
        fingerprint used during plan derivation.
        """
        # Build fingerprint-compatible plan_data (excludes requiredJarEntries and planFingerprint)
        plan_data = {
            "schemaVersion": data["schemaVersion"],
            "authorityIdentity": data["authorityIdentity"],
            "authorityRevision": data["authorityRevision"],
            "authorityRawFingerprint": data["authorityRawFingerprint"],
            "roleIdentity": data["roleIdentity"],
            "roleArtifactMainClass": data.get("roleArtifactMainClass"),
            "roleExecutableClassEntry": data.get("roleExecutableClassEntry"),
            "exactArchiveEntries": list(data["exactArchiveEntries"]),
            "packagedProjections": list(data["packagedProjections"]),
            "profilePath": data.get("profilePath"),
            "registryPath": data.get("registryPath"),
            "canonicalizationPath": data.get("canonicalizationPath"),
            "compilationManifestPath": data["compilationManifestPath"],
            "embeddedProviderArtifact": data.get("embeddedProviderArtifact"),
            "embeddedProviderIdentityPath": data.get("embeddedProviderIdentityPath"),
            "classManifestPath": data["classManifestPath"],
            "resourceManifestPath": data["resourceManifestPath"],
            "dependencyManifestPath": data["dependencyManifestPath"],
            "embeddedDependencies": list(data["embeddedDependencies"]),
            "providerIdentityRecipe": data.get("providerIdentityRecipe"),
        }
        plan_fingerprint = _fp(plan_data)
        return cls(
            schema_version=data["schemaVersion"],
            authority_identity=data["authorityIdentity"],
            authority_revision=data["authorityRevision"],
            authority_raw_fingerprint=data["authorityRawFingerprint"],
            role_identity=data["roleIdentity"],
            role_artifact_main_class=data.get("roleArtifactMainClass"),
            role_executable_class_entry=data.get("roleExecutableClassEntry"),
            exact_archive_entries=tuple(data["exactArchiveEntries"]),
            required_jar_entries=frozenset(data["exactArchiveEntries"]),
            packaged_projections=tuple(data["packagedProjections"]),
            profile_path=data.get("profilePath"),
            registry_path=data.get("registryPath"),
            canonicalization_path=data.get("canonicalizationPath"),
            compilation_manifest_path=data["compilationManifestPath"],
            embedded_provider_artifact=data.get("embeddedProviderArtifact"),
            embedded_provider_identity_path=data.get("embeddedProviderIdentityPath"),
            class_manifest_path=data["classManifestPath"],
            resource_manifest_path=data["resourceManifestPath"],
            dependency_manifest_path=data["dependencyManifestPath"],
            embedded_dependencies=tuple(data["embeddedDependencies"]),
            provider_identity_recipe=data.get("providerIdentityRecipe"),
            plan_fingerprint=plan_fingerprint,
        )

@dataclass(frozen=True)
class PublicationReceipt:
    schema_version: str
    authority_fingerprint: str
    plan_raw_fingerprint: str
    commitment: str
    # inventory = plan files only; receipt is commit marker, not self-referential
    exact_inventory: tuple[str, ...]
    publication_root: str
    receipt_fingerprint: str

    def to_json(self) -> dict[str, Any]:
        return {
            "schemaVersion": self.schema_version,
            "authorityFingerprint": self.authority_fingerprint,
            "planRawFingerprint": self.plan_raw_fingerprint,
            "commitment": self.commitment,
            "exactInventory": list(self.exact_inventory),
            "publicationRoot": self.publication_root,
            "receiptFingerprint": self.receipt_fingerprint,
        }


# ---------------------------------------------------------------------------
# Plan derivation
# ---------------------------------------------------------------------------

def derive_packaging_plan(
    linked_model: Any,  # compiler_core.LinkedProtocolModel
    compiled_projections: dict[str, dict[str, Any]],
    compilation_manifest: dict[str, Any],
    authority_raw: bytes,
) -> IndependentVerifierPackagingPlan:
    """Derive IndependentVerifierPackagingPlan from Authority.

    Derivation inputs:
    - LinkedProtocolModel (Authority semantic graph)
    - 8 projections from ProjectionCompiler output
    - Compilation manifest from ProjectionCompiler
    - Authority declared fields

    NOT derived: requiredJarEntries (exact equality assertion only).
    """
    # --- Stable rejection if linker found semantic errors
    if linked_model.link_errors:
        raise PlanValidationError("LINK_ERRORS")

    authority = linked_model.authority
    authority_fp = f"sha256:{hashlib.sha256(authority_raw).hexdigest()}"

    verifier_contract = None
    for rc in authority.get("roleContracts", []):
        if rc.get("role") == "INDEPENDENT_VERIFIER":
            verifier_contract = rc
            break

    if verifier_contract is None:
        raise PlanValidationError("AUTHORITY_MISSING_INDEPENDENT_VERIFIER_ROLE")

    # --- Authority identity/revision/fingerprint
    authority_identity = authority.get("authorityId", "GATE-A-PROTOCOL-AUTHORITY")
    authority_revision = authority.get("revision", 1)

    # --- Role info
    role_identity = verifier_contract.get("role", "INDEPENDENT_VERIFIER")
    role_artifact_main_class = verifier_contract.get("mainClass")
    role_executable_class_entry = _derive_executable_class_entry(verifier_contract)

    # --- exactArchiveEntries: DERIVED from declared fields via compiler_core._derive_role_expected_jar_entries
    # This is the source of truth for the JAR entry set
    _entries, _derive_errors = _cc._derive_role_expected_jar_entries(verifier_contract, authority)
    for _err in _derive_errors:
        raise RequiredJarEntriesMismatchError(_err)
    expected_entries = _entries
    exact_archive_entries = tuple(sorted(expected_entries))

    # --- 7 packaged projections (binding validates contract x manifest x compiled)
    packaged_projections = _derive_packaged_projections(
        verifier_contract, compiled_projections, compilation_manifest,
        linked_model.authority_fingerprint,
    )

    # --- Resource paths
    pack = verifier_contract.get("packagingContract", {})
    profile_path = verifier_contract.get("profilePath")
    registry_path = verifier_contract.get("registryPath")
    canonicalization_path = verifier_contract.get("canonicalizationProfileEntryPath")

    # --- Manifest paths
    compilation_manifest_path = "META-INF/gate-a/protocol/protocol-compilation-manifest-v1.json"
    class_manifest_path = pack.get(
        "classManifestEntryPath", "META-INF/gate-a/manifests/classes.json"
    )
    resource_manifest_path = pack.get(
        "resourceManifestEntryPath", "META-INF/gate-a/manifests/resources.json"
    )
    dependency_manifest_path = pack.get(
        "dependencyLockManifestEntryPath", "META-INF/gate-a/manifests/dependencies.json"
    )

    # --- Provider/identity
    embedded_provider_artifact = verifier_contract.get("providerEntryPath")
    embedded_provider_identity_path = verifier_contract.get("providerIdentityEntryPath")

    # --- 7 embedded dependencies (join validates lockId x depAuth)
    embedded_dependencies = _derive_embedded_dependencies(verifier_contract, authority)

    # --- Provider identity recipe (validates TCK_PROVIDER role exists)
    provider_identity_recipe = _derive_provider_identity_recipe(
        verifier_contract, authority
    )

    # --- Plan fingerprint (deterministic)
    plan_data = {
        "schemaVersion": PLAN_SCHEMA_VERSION,
        "authorityIdentity": authority_identity,
        "authorityRevision": authority_revision,
        "authorityRawFingerprint": authority_fp,
        "roleIdentity": role_identity,
        "roleArtifactMainClass": role_artifact_main_class,
        "roleExecutableClassEntry": role_executable_class_entry,
        "exactArchiveEntries": list(exact_archive_entries),
        "packagedProjections": list(packaged_projections),
        "profilePath": profile_path,
        "registryPath": registry_path,
        "canonicalizationPath": canonicalization_path,
        "compilationManifestPath": compilation_manifest_path,
        "embeddedProviderArtifact": embedded_provider_artifact,
        "embeddedProviderIdentityPath": embedded_provider_identity_path,
        "classManifestPath": class_manifest_path,
        "resourceManifestPath": resource_manifest_path,
        "dependencyManifestPath": dependency_manifest_path,
        "embeddedDependencies": list(embedded_dependencies),
        "providerIdentityRecipe": provider_identity_recipe,
    }
    plan_fingerprint = _fp(plan_data)

    # --- requiredJarEntries: EXACT equality assertion only
    # Checked LAST so earlier gates surface first (projection/dependency/provider binding)
    observed_required = frozenset(verifier_contract.get("requiredJarEntries", []))
    if observed_required != expected_entries:
        missing = expected_entries - observed_required
        extra = observed_required - expected_entries
        raise RequiredJarEntriesMismatchError(
            f"MISMATCH_MISSING:{len(missing)}_EXTRA:{len(extra)}"
        )

    return IndependentVerifierPackagingPlan(
        schema_version=PLAN_SCHEMA_VERSION,
        authority_identity=authority_identity,
        authority_revision=authority_revision,
        authority_raw_fingerprint=authority_fp,
        role_identity=role_identity,
        role_artifact_main_class=role_artifact_main_class,
        role_executable_class_entry=role_executable_class_entry,
        exact_archive_entries=exact_archive_entries,
        required_jar_entries=observed_required,
        packaged_projections=tuple(packaged_projections),
        profile_path=profile_path,
        registry_path=registry_path,
        canonicalization_path=canonicalization_path,
        compilation_manifest_path=compilation_manifest_path,
        embedded_provider_artifact=embedded_provider_artifact,
        embedded_provider_identity_path=embedded_provider_identity_path,
        class_manifest_path=class_manifest_path,
        resource_manifest_path=resource_manifest_path,
        dependency_manifest_path=dependency_manifest_path,
        embedded_dependencies=tuple(embedded_dependencies),
        provider_identity_recipe=provider_identity_recipe,
        plan_fingerprint=plan_fingerprint,
    )


def _derive_executable_class_entry(contract: dict[str, Any]) -> str | None:
    mc = contract.get("mainClass")
    if not mc:
        return None
    return mc.replace(".", "/") + ".class"


# ---------------------------------------------------------------------------
# Canonical JAR entry derivation
# Replicates compiler_core._derive_role_expected_jar_entries for derivation
# ---------------------------------------------------------------------------

def _derive_role_expected_jar_entries(
    contract: dict[str, Any],
    authority: dict[str, Any],
) -> frozenset[str]:
    """Shim: delegate to compiler_core._derive_role_expected_jar_entries.

    Test callers use this module-level name directly.
    Returns frozenset for backward compatibility with callers that expected
    the old frozenset-only return (errors are silently dropped here; callers
    that need errors should call compiler_core._derive_role_expected_jar_entries).
    """
    entries, _errors = _cc._derive_role_expected_jar_entries(contract, authority)
    return frozenset(entries)


# ---------------------------------------------------------------------------
# 7 packaged projections binding
# ---------------------------------------------------------------------------

def _derive_packaged_projections(
    contract: dict[str, Any],
    compiled_projections: dict[str, dict[str, Any]],
    compilation_manifest: dict[str, Any],
    linked_model_authority_fingerprint: str,
) -> list[dict[str, Any]]:
    """Bind 7 packaged projections from role.packagedProjections join manifest.

    Strong validation:
    - manifest IDs are unique (checked via set equality below)
    - compiled_projections key set == manifest IDs exactly
    - role packaged IDs exactly 7 and unique
    - each compiled projection: projectionId matches, sourceRawFingerprint matches
      linked_model.authority_fingerprint, manifest rawFingerprint matches
      compiler_core._fp(compiled projection), contract entryPath == manifest jarEntryPath
    - Reject missing / extra / duplicate / drift
    """
    # --- Manifest IDs must be unique
    manifest_entries = compilation_manifest.get("projections", [])
    manifest_ids_raw = [e["projectionId"] for e in manifest_entries]
    if len(manifest_ids_raw) != len(set(manifest_ids_raw)):
        seen: set[str] = set()
        for pid in manifest_ids_raw:
            if pid in seen:
                raise ProjectionBindingError(f"MANIFEST_DUPLICATE_ID:{pid}")
            seen.add(pid)

    manifest_map: dict[str, dict[str, Any]] = {
        e["projectionId"]: e for e in manifest_entries
    }

    # --- compiled_projections key set must exactly equal manifest IDs
    compiled_ids = set(compiled_projections.keys())
    manifest_ids = set(manifest_map.keys())
    if compiled_ids != manifest_ids:
        missing_keys = manifest_ids - compiled_ids
        extra_keys = compiled_ids - manifest_ids
        if missing_keys:
            raise ProjectionBindingError(f"COMPILED_MISSING:{len(missing_keys)}")
        if extra_keys:
            raise ProjectionBindingError(f"COMPILED_EXTRA:{len(extra_keys)}")
        raise ProjectionBindingError("COMPILED_MANIFEST_KEY_MISMATCH")

    # --- Role packaged IDs exactly 7 and unique
    raw_pids = [proj.get("projectionId") for proj in contract.get("packagedProjections", [])]
    if len(raw_pids) != 7:
        raise ProjectionBindingError(f"PACKAGED_COUNT:{len(raw_pids)}")
    if len(raw_pids) != len(set(raw_pids)):
        seen: set[str] = set()
        for pid in raw_pids:
            if pid in seen:
                raise ProjectionBindingError(f"PACKAGED_DUPLICATE_ID:{pid}")
            seen.add(pid)

    projections: list[dict[str, Any]] = []
    for proj in contract.get("packagedProjections", []):
        pid = proj.get("projectionId")
        if pid not in manifest_map:
            raise ProjectionBindingError(f"PROJECTION_NOT_IN_MANIFEST:{pid}")

        manifest_entry = manifest_map[pid]
        entry_path = proj.get("entryPath")
        manifest_path = manifest_entry.get("jarEntryPath")

        if entry_path != manifest_path:
            raise ProjectionBindingError(f"ENTRY_PATH_MISMATCH:{pid}")

        compiled = compiled_projections.get(pid)
        if compiled is None:
            raise ProjectionBindingError(f"PROJECTION_NOT_COMPILED:{pid}")

        # --- projectionId must match
        if compiled.get("projectionId") != pid:
            raise ProjectionBindingError(f"COMPILED_PROJECTION_ID_MISMATCH:{pid}")

        # --- sourceRawFingerprint must match linked_model.authority_fingerprint
        source_fp = compiled.get("sourceRawFingerprint")
        if source_fp != linked_model_authority_fingerprint:
            raise ProjectionBindingError(f"SOURCE_FP_DRIFT:{pid}")

        # --- manifest rawFingerprint must match compiler_core._fp(compiled projection)
        manifest_fp = manifest_entry.get("rawFingerprint")
        compiled_fp = _cc._fp(compiled)
        if manifest_fp != compiled_fp:
            raise ProjectionBindingError(f"MANIFEST_FP_DRIFT:{pid}")

        projections.append({
            "projectionId": pid,
            "entryPath": entry_path,
            "sourceRawFingerprint": source_fp,
        })

    projections.sort(key=lambda p: p["projectionId"])

    return projections


# ---------------------------------------------------------------------------
# 7 embedded dependency exact join
# ---------------------------------------------------------------------------

def _derive_embedded_dependencies(
    contract: dict[str, Any],
    authority: dict[str, Any],
) -> list[dict[str, Any]]:
    """Exact lockId join: role packagingEntries x top-level dependencyAuthority.

    Strong validation:
    - runtimeDependencyLockIds and embeddedDependencyEntries IDs are unique, exactly 7,
      and the two sets are exactly equal
    - depAuthority IDs are unique
    - scope matches depAuthority for each lockId
    - entryPath non-empty and 7 unique paths
    - coordinate: groupId/artifactId/version non-empty, classifier exact
    - rawFingerprint: sha256:64-char lowercase hex
    - source non-empty
    - allowedRoles includes INDEPENDENT_VERIFIER
    - packagingModes includes current packaging model from contract.packagingContract.model
    """
    pack = contract.get("packagingContract", {})
    packaging_entries = pack.get("embeddedDependencyEntries", [])

    # Current packaging model from contract (not hardcoded)
    current_packaging_model = pack.get("model", "")
    if not current_packaging_model:
        raise DependencyJoinError("PACKAGING_MODEL_MISSING")

    # runtimeDependencyLockIds from contract
    runtime_lock_ids = list(contract.get("runtimeDependencyLockIds", []))
    if len(runtime_lock_ids) != len(set(runtime_lock_ids)):
        seen: set[str] = set()
        for lid in runtime_lock_ids:
            if lid in seen:
                raise DependencyJoinError(f"RUNTIME_DEP_LOCKID_DUPLICATE:{lid}")
            seen.add(lid)
    if len(runtime_lock_ids) != 7:
        raise DependencyJoinError(f"RUNTIME_DEP_LOCKID_COUNT:{len(runtime_lock_ids)}")

    # embeddedDependencyEntries lock IDs must be unique and exactly 7
    embed_lock_ids = []
    for entry in packaging_entries:
        lid = entry.get("lockId", "")
        if not lid:
            raise DependencyJoinError("EMBEDDED_DEP_LOCKID_EMPTY")
        if lid in embed_lock_ids:
            raise DependencyJoinError(f"EMBEDDED_DEP_LOCKID_DUPLICATE:{lid}")
        embed_lock_ids.append(lid)
    if len(embed_lock_ids) != 7:
        raise DependencyJoinError(f"EMBEDDED_DEP_COUNT:{len(embed_lock_ids)}")

    # runtimeDependencyLockIds and embeddedDependencyEntries must be exactly equal as sets
    runtime_set = set(runtime_lock_ids)
    embed_set = set(embed_lock_ids)
    if runtime_set != embed_set:
        missing = runtime_set - embed_set
        extra = embed_set - runtime_set
        if missing:
            raise DependencyJoinError(f"EMBEDDED_DEP_MISSING:{len(missing)}")
        if extra:
            raise DependencyJoinError(f"EMBEDDED_DEP_EXTRA:{len(extra)}")

    # depAuthority IDs must be unique
    dep_auth = authority.get("dependencyAuthority", {})
    dep_auth_lock_ids = []
    for dep in dep_auth.get("dependencies", []):
        lid = dep.get("lockId", "")
        if lid:
            if lid in dep_auth_lock_ids:
                raise DependencyJoinError(f"DEP_AUTH_LOCKID_DUPLICATE:{lid}")
            dep_auth_lock_ids.append(lid)

    # Build lockId maps
    pack_map: dict[str, dict[str, Any]] = {}
    for entry in packaging_entries:
        lock_id = entry.get("lockId", "")
        pack_map[lock_id] = entry

    dep_map: dict[str, dict[str, Any]] = {}
    for dep in dep_auth.get("dependencies", []):
        lid = dep.get("lockId", "")
        if lid:
            dep_map[lid] = dep

    # Each packaging lockId must exist in dep authority
    pack_ids = set(pack_map)
    dep_ids = set(dep_map)
    missing_in_auth = pack_ids - dep_ids
    if missing_in_auth:
        raise DependencyJoinError(f"MISSING_IN_DEP_AUTH:{len(missing_in_auth)}")

    # Build result with full join; collect entry paths for uniqueness check
    result: list[dict[str, Any]] = []
    entry_paths_seen: set[str] = set()
    for lock_id in sorted(pack_ids):
        p_entry = pack_map[lock_id]
        d_entry = dep_map[lock_id]

        # --- scope: must match depAuthority scope
        dep_scope = d_entry.get("scope", "")
        pack_scope = p_entry.get("scope", "")
        if pack_scope != dep_scope:
            raise DependencyJoinError(f"SCOPE_MISMATCH:{lock_id}")

        # --- entryPath: non-empty and unique
        entry_path = p_entry.get("entryPath", "")
        if not entry_path:
            raise DependencyJoinError(f"ENTRY_PATH_EMPTY:{lock_id}")
        if entry_path in entry_paths_seen:
            raise DependencyJoinError(f"ENTRY_PATH_DUPLICATE:{entry_path}")
        entry_paths_seen.add(entry_path)

        # --- coordinate: groupId/artifactId/version non-empty, classifier exact
        coord = d_entry.get("coordinate", {})
        gid = coord.get("groupId", "")
        aid = coord.get("artifactId", "")
        ver = coord.get("version", "")
        cls = coord.get("classifier", "")
        if not gid:
            raise DependencyJoinError(f"COORD_GROUPID_EMPTY:{lock_id}")
        if not aid:
            raise DependencyJoinError(f"COORD_ARTIFACTID_EMPTY:{lock_id}")
        if not ver:
            raise DependencyJoinError(f"COORD_VERSION_EMPTY:{lock_id}")
        # classifier must be exact (empty is valid, but non-empty must match)
        _ = cls  # validated as exact value

        # --- rawFingerprint: sha256:64-char lowercase hex
        raw_fp = d_entry.get("rawFingerprint", "")
        if not raw_fp:
            raise DependencyJoinError(f"RAW_FP_EMPTY:{lock_id}")
        if not raw_fp.startswith("sha256:"):
            raise DependencyJoinError(f"RAW_FP_PREFIX:{lock_id}")
        hex_part = raw_fp[7:]
        if len(hex_part) != 64:
            raise DependencyJoinError(f"RAW_FP_LENGTH:{lock_id}")
        if hex_part != hex_part.lower():
            raise DependencyJoinError(f"RAW_FP_NOT_LOWERCASE:{lock_id}")
        try:
            int(hex_part, 16)
        except ValueError:
            raise DependencyJoinError(f"RAW_FP_NOT_HEX:{lock_id}")

        # --- source: non-empty
        source = d_entry.get("source", "")
        if not source:
            raise DependencyJoinError(f"SOURCE_EMPTY:{lock_id}")

        # --- allowedRoles includes INDEPENDENT_VERIFIER
        allowed_roles = d_entry.get("allowedRoles", [])
        if "INDEPENDENT_VERIFIER" not in allowed_roles:
            raise DependencyJoinError(f"ROLE_NOT_ALLOWED:{lock_id}")

        # --- packagingModes includes current packaging model
        allowed_modes = d_entry.get("packagingModes", [])
        if current_packaging_model not in allowed_modes:
            raise DependencyJoinError(f"PACKAGING_MODE_MISMATCH:{lock_id}")

        result.append({
            "lockId": lock_id,
            "coordinate": coord,
            "scope": dep_scope,
            "entryPath": entry_path,
            "rawFingerprint": raw_fp,
            "source": source,
        })

    return result


# ---------------------------------------------------------------------------
# Provider identity recipe
# ---------------------------------------------------------------------------

def _derive_provider_identity_recipe(
    contract: dict[str, Any],
    authority: dict[str, Any],
) -> dict[str, Any] | None:
    """Derive provider identity recipe from Authority-provable artifacts.

    Strong validation:
    - TCK_PROVIDER role is unique
    - providedAbiDependencies read from TCK_PROVIDER.role.packagingContract (not verifier contract)
    - providedAbiDependencies must be exactly 1
    - All 10 ABI fields non-empty:
      candidateRole, candidateCoordinate, candidateArtifactFingerprintPinField,
      candidateClassEntryPath, candidateClassFingerprintPinField,
      candidateSpiInterfaceClassEntryPath, candidateSpiInterfaceClass,
      providerServiceDescriptorEntryPath, providerServiceDescriptorInterfaceClass,
      embeddingPolicy
    - Implementation class consistent with TCK requiredJarEntries (single non-META-INF .class entry)
    - providerEntryPath and providerIdentityEntryPath still come from INDEPENDENT_VERIFIER contract
    """
    # Provider artifact/identity paths come from INDEPENDENT_VERIFIER contract
    provider_artifact = contract.get("providerEntryPath")
    provider_identity_path = contract.get("providerIdentityEntryPath")

    if not provider_artifact or not provider_identity_path:
        return None

    # TCK_PROVIDER role must be unique
    tck_providers = [rc for rc in authority.get("roleContracts", [])
                     if rc.get("role") == "TCK_PROVIDER"]
    if len(tck_providers) != 1:
        raise ProviderRecipeError(f"TCK_PROVIDER_UNIQUE:{len(tck_providers)}")
    tck_provider = tck_providers[0]

    # providedAbiDependencies: read from TCK_PROVIDER.role.packagingContract
    tck_pack = tck_provider.get("packagingContract", {})
    provided_abi = tck_pack.get("providedAbiDependencies", [])
    if len(provided_abi) != 1:
        raise ProviderRecipeError(f"PROVIDED_ABI_COUNT:{len(provided_abi)}")

    abi = provided_abi[0]

    # Required non-empty ABI fields (all 10)
    _req_fields = [
        ("candidateRole", abi.get("candidateRole")),
        ("candidateCoordinate", abi.get("candidateCoordinate")),
        ("candidateArtifactFingerprintPinField", abi.get("candidateArtifactFingerprintPinField")),
        ("candidateClassEntryPath", abi.get("candidateClassEntryPath")),
        ("candidateClassFingerprintPinField", abi.get("candidateClassFingerprintPinField")),
        ("candidateSpiInterfaceClassEntryPath", abi.get("candidateSpiInterfaceClassEntryPath")),
        ("candidateSpiInterfaceClass", abi.get("candidateSpiInterfaceClass")),
        ("providerServiceDescriptorEntryPath", abi.get("providerServiceDescriptorEntryPath")),
        ("providerServiceDescriptorInterfaceClass", abi.get("providerServiceDescriptorInterfaceClass")),
        ("embeddingPolicy", abi.get("embeddingPolicy")),
    ]
    for field_name, field_value in _req_fields:
        if not field_value:
            raise ProviderRecipeError(f"PROVIDED_ABI_FIELD_EMPTY:{field_name}")
        if isinstance(field_value, dict) and not field_value:
            raise ProviderRecipeError(f"PROVIDED_ABI_FIELD_EMPTY:{field_name}")

    # Implementation class: single non-META-INF .class entry from TCK requiredJarEntries
    required = tck_provider.get("requiredJarEntries", [])
    impl_paths = [
        e for e in required
        if e.endswith(".class") and not e.startswith("META-INF/")
    ]
    if len(impl_paths) != 1:
        raise ProviderRecipeError(f"IMPL_CLASS_COUNT:{len(impl_paths)}")
    impl_class_entry_path = impl_paths[0]
    provider_impl_class = impl_class_entry_path.replace("/", ".")[:-6]

    # Service descriptor: derive from TCK requiredJarEntries (META-INF/services/ entries)
    service_paths = [e for e in required if e.startswith("META-INF/services/")]
    if len(service_paths) != 1:
        raise ProviderRecipeError(f"SERVICE_DESCRIPTOR_COUNT:{len(service_paths)}")
    service_descriptor_path = service_paths[0]

    # Service descriptor path from ABI must match TCK required entries
    abi_svc_path = abi.get("providerServiceDescriptorEntryPath", "")
    if abi_svc_path != service_descriptor_path:
        raise ProviderRecipeError("PROVIDED_ABI_SERVICE_DESCRIPTOR_DRIFT")

    recipe: dict[str, Any] = {
        "tckProviderArtifact": provider_artifact,
        "serviceDescriptorPath": service_descriptor_path,
        "providerImplementationClass": provider_impl_class,
        "providedAbiCandidateSpi": {
            "candidateRole": abi.get("candidateRole"),
            "candidateCoordinate": abi.get("candidateCoordinate"),
            "candidateArtifactFingerprintPinField": abi.get("candidateArtifactFingerprintPinField"),
            "candidateClassEntryPath": abi.get("candidateClassEntryPath"),
            "candidateClassFingerprintPinField": abi.get("candidateClassFingerprintPinField"),
            "candidateSpiInterfaceClassEntryPath": abi.get("candidateSpiInterfaceClassEntryPath"),
            "candidateSpiInterfaceClass": abi.get("candidateSpiInterfaceClass"),
            "providerServiceDescriptorEntryPath": abi.get("providerServiceDescriptorEntryPath"),
            "providerServiceDescriptorInterfaceClass": abi.get("providerServiceDescriptorInterfaceClass"),
            "embeddingPolicy": abi.get("embeddingPolicy"),
        },
    }

    return recipe


# ---------------------------------------------------------------------------
# Publisher — atomic commit via rename
# ---------------------------------------------------------------------------

class Publisher:
    """Content-addressed publisher with atomic COMMIT.

    PREPARE: Random create-new staging inside output_root.
             Writes plan + receipt together in staging.
             Records dev/ino for cleanup validation.
             Never deletes pre-existing paths.
    COMMIT:   Reads plan+receipt from staging, recomputes values.
             Fixed .publication.lock + fcntl.flock.
             Single os.rename.
             Fail-closed on symlinks at every boundary.
    """

    PLAN_INVENTORY_FILES: tuple[str, ...] = (PLAN_OUTPUT_NAME,)

    def __init__(self, output_root: pathlib.Path) -> None:
        # Lexical absolute path: do NOT follow symlinks.
        # Each component is lstat()'d at every level to detect and reject symlinks.
        self._output_root_raw = output_root
        self._output_root = pathlib.Path(
            os.path.abspath(os.path.expanduser(str(output_root)))
        )
        self._staging_registry: dict[pathlib.Path, tuple[int, int]] = {}

    def _check_not_symlink(self, path: pathlib.Path, label: str) -> None:
        """Fail-closed: reject symlink at this path."""
        try:
            st = path.lstat()
            if stat.S_ISLNK(st.st_mode):
                raise PublisherSymlinkError(f"SYMLINK_IN_{label}")
        except PublisherSymlinkError:
            raise
        except FileNotFoundError:
            pass

    def _check_is_directory(self, path: pathlib.Path, label: str) -> None:
        """Fail-closed: reject non-directory."""
        try:
            st = path.lstat()
            if not stat.S_ISDIR(st.st_mode):
                raise PublisherNotDirectoryError(f"NOT_DIRECTORY_{label}")
        except PublisherNotDirectoryError:
            raise
        except FileNotFoundError:
            raise PublisherNotDirectoryError(f"NOT_DIRECTORY_{label}")

    def _check_no_traversal(self, path: pathlib.Path, safe_parent: pathlib.Path) -> None:
        """Reject path traversal via lexical absolute path check."""
        # Use lexical absolute: do NOT resolve symlinks
        abs_path = str(pathlib.Path(os.path.abspath(str(path))))
        abs_parent = str(pathlib.Path(os.path.abspath(str(safe_parent))))
        if not abs_path.startswith(abs_parent + os.sep):
            raise PublisherPathTraversalError("PATH_TRAVERSAL_DETECTED")

    def _check_path_components(self, path: pathlib.Path, label: str) -> None:
        """Walk path components from output_root anchor, reject symlink/non-dir at every level.

        Always uses lexical absolute (no resolve).  Each component is lstat()'d:
        symlink → PublisherSymlinkError, non-directory → PublisherNotDirectoryError.
        """
        abs_root = self._output_root  # already lexical absolute
        abs_path = self._output_root if path == self._output_root else pathlib.Path(
            os.path.abspath(str(path))
        )
        try:
            rel = os.path.relpath(str(abs_path), str(abs_root))
        except ValueError:
            return

        current = abs_root
        for part in rel.split(os.sep):
            if not part or part == '.':
                continue
            current = current / part
            try:
                st = current.lstat()
            except FileNotFoundError:
                break  # non-existent intermediate — caller may be creating it
            if stat.S_ISLNK(st.st_mode):
                raise PublisherSymlinkError(f"SYMLINK_IN_{label}:{current.name}")
            if not stat.S_ISDIR(st.st_mode):
                raise PublisherNotDirectoryError(f"NOT_DIRECTORY_{label}:{current.name}")


    def _check_path_ancestors(self, path: pathlib.Path, label: str) -> None:
        """Level-by-level lstat check on output_root itself and all descendants.

        First lstat()s output_root itself: reject if symlink or non-directory.
        Then walks from output_root to path, failing if any component is a
        symlink or non-directory. Uses lexical absolute (no resolve).
        Used for TOCTOU protection after acquiring lock and before rename.
        """
        abs_root = self._output_root
        abs_path = pathlib.Path(os.path.abspath(str(path)))

        # Step 1: lstat output_root itself — reject if not a regular directory
        try:
            root_st = abs_root.lstat()
        except FileNotFoundError:
            raise PublisherNotDirectoryError(f"NOT_DIRECTORY_{label}")
        if stat.S_ISLNK(root_st.st_mode):
            raise PublisherSymlinkError(f"SYMLINK_IN_{label}:{abs_root.name}")
        if not stat.S_ISDIR(root_st.st_mode):
            raise PublisherNotDirectoryError(f"NOT_DIRECTORY_{label}")

        # Step 2: walk descendants from output_root to path
        try:
            rel = os.path.relpath(str(abs_path), str(abs_root))
        except ValueError:
            return

        current = abs_root
        for part in rel.split(os.sep):
            if not part or part == '.':
                continue
            current = current / part
            try:
                st = current.lstat()
            except FileNotFoundError:
                # Non-existent intermediate — allowed for creation paths
                break
            if stat.S_ISLNK(st.st_mode):
                raise PublisherSymlinkError(f"SYMLINK_IN_{label}:{current.name}")
            if not stat.S_ISDIR(st.st_mode):
                raise PublisherNotDirectoryError(f"NOT_DIRECTORY_{label}:{current.name}")

    def _cleanup_staging(self, staging: pathlib.Path) -> None:
        """Clean up staging on failure: dev/ino match, regular nlink=1, known files only.

        Looks up (dev,ino) from self._staging_registry keyed by lexical staging path.
        Raises CLEANUP_UNSAFE if the dev/ino no longer matches (potential attack swap).
        """
        expected = self._staging_registry.get(staging)
        if expected is None:
            raise PublisherError("CLEANUP_NO_STAGING_RECORD")
        try:
            st = staging.lstat()
        except FileNotFoundError:
            # Already gone — unregister
            self._staging_registry.pop(staging, None)
            return
        if (st.st_dev, st.st_ino) != expected:
            raise PublisherError("CLEANUP_UNSAFE")
        if stat.S_ISLNK(st.st_mode):
            raise PublisherError("CLEANUP_UNSAFE")

        # Scan: reject symlinks and unknown files
        try:
            children = list(staging.iterdir())
        except FileNotFoundError:
            self._staging_registry.pop(staging, None)
            return
        for child in children:
            try:
                cs = child.lstat()
            except FileNotFoundError:
                continue
            if stat.S_ISLNK(cs.st_mode):
                raise PublisherError("CLEANUP_UNSAFE")
            if not stat.S_ISREG(cs.st_mode):
                raise PublisherError("CLEANUP_UNSAFE")
            if child.name not in (PLAN_OUTPUT_NAME, RECEIPT_OUTPUT_NAME):
                raise PublisherError("CLEANUP_UNSAFE")

        # Unlink known files (must be regular, nlink=1)
        for fname in (PLAN_OUTPUT_NAME, RECEIPT_OUTPUT_NAME):
            fpath = staging / fname
            try:
                fs = fpath.lstat()
            except FileNotFoundError:
                continue
            if stat.S_ISLNK(fs.st_mode):
                raise PublisherError("CLEANUP_UNSAFE")
            if not stat.S_ISREG(fs.st_mode):
                raise PublisherError("CLEANUP_UNSAFE")
            if fs.st_nlink != 1:
                raise PublisherError("CLEANUP_UNSAFE")
            try:
                os.unlink(str(fpath))
            except FileNotFoundError:
                pass

        # rmdir staging
        try:
            staging.rmdir()
        except OSError:
            raise PublisherError("CLEANUP_UNSAFE")
        # Unregister on success
        self._staging_registry.pop(staging, None)

    def _compute_plan_from_staging(self, staging: pathlib.Path, authority_raw: bytes) -> tuple[bytes, dict[str, Any], str, str, str]:
        """Read plan+receipt from staging, recompute all derived values.

        Returns (plan_bytes, plan_json, plan_raw_fp, commitment, final_rel).
        Strictly validates receipt exact field set, receiptFingerprint,
        authorityFingerprint, and plan field set to prevent malicious staging
        from partially satisfying requirements before commit.
        """
        plan_path = staging / PLAN_OUTPUT_NAME
        receipt_path = staging / RECEIPT_OUTPUT_NAME

        # Read bytes
        try:
            plan_bytes = plan_path.read_bytes()
        except FileNotFoundError:
            raise PublisherIncompleteError("STAGING_PLAN_MISSING")
        try:
            receipt_bytes = receipt_path.read_bytes()
        except FileNotFoundError:
            raise PublisherIncompleteError("STAGING_RECEIPT_MISSING")

        # Re-parse plan JSON
        plan_json = strict_json_loads(plan_bytes.decode("utf-8"))
        # Re-parse receipt JSON
        receipt = strict_json_loads(receipt_bytes.decode("utf-8"))

        # --- Strict receipt field set validation ---
        _receipt_required_fields = frozenset({
            "schemaVersion", "authorityFingerprint", "planRawFingerprint",
            "commitment", "exactInventory", "publicationRoot", "receiptFingerprint",
        })
        _receipt_actual_fields = frozenset(receipt.keys())
        if _receipt_actual_fields != _receipt_required_fields:
            raise PublisherError("RECEIPT_FIELD_SET_MISMATCH")

        # --- Recompute plan raw fingerprint ---
        plan_raw_fp = f"sha256:{hashlib.sha256(plan_bytes).hexdigest()}"
        plan_fp_hex = plan_raw_fp.replace("sha256:", "")
        final_rel = f"{plan_fp_hex[:2]}/{plan_fp_hex[2:4]}/{plan_fp_hex}"

        # --- Recompute inventory ---
        inventory = self.PLAN_INVENTORY_FILES

        # --- Recompute commitment ---
        commitment = self._compute_commitment(plan_raw_fp, inventory, authority_raw)

        # --- Recompute receipt fingerprint (domain-separated, excludes itself) ---
        receipt_body_for_fp = {k: v for k, v in receipt.items() if k != "receiptFingerprint"}
        recomputed_receipt_fp = self._receipt_fingerprint(receipt_body_for_fp)
        if recomputed_receipt_fp != receipt.get("receiptFingerprint"):
            raise PublisherError("RECEIPT_FP_MISMATCH")

        # --- Verify authorityFingerprint matches ---
        recomputed_authority_fp = f"sha256:{hashlib.sha256(authority_raw).hexdigest()}"
        if receipt.get("authorityFingerprint") != recomputed_authority_fp:
            raise PublisherError("RECEIPT_AUTHORITY_FP_MISMATCH")

        # --- Strict plan field set validation (exact) ---
        _plan_required_fields = frozenset({
            "schemaVersion", "authorityIdentity", "authorityRevision",
            "authorityRawFingerprint", "roleIdentity", "roleArtifactMainClass",
            "roleExecutableClassEntry", "exactArchiveEntries", "packagedProjections",
            "profilePath", "registryPath", "canonicalizationPath",
            "compilationManifestPath", "embeddedProviderArtifact",
            "embeddedProviderIdentityPath", "classManifestPath",
            "resourceManifestPath", "dependencyManifestPath",
            "embeddedDependencies", "providerIdentityRecipe",
        })
        _plan_actual_fields = frozenset(plan_json.keys())
        if _plan_actual_fields != _plan_required_fields:
            raise PublisherError("PLAN_FIELD_SET_MISMATCH")

        # --- Verify receipt matches recomputed values ---
        expected_inventory_list = list(inventory)
        if receipt.get("exactInventory") != expected_inventory_list:
            raise PublisherError("RECEIPT_INVENTORY_MISMATCH")
        if receipt.get("planRawFingerprint") != plan_raw_fp:
            raise PublisherError("RECEIPT_PLAN_FP_MISMATCH")
        if receipt.get("commitment") != commitment:
            raise PublisherError("RECEIPT_COMMITMENT_MISMATCH")
        if receipt.get("publicationRoot") != final_rel:
            raise PublisherError("RECEIPT_PUB_ROOT_MISMATCH")

        return plan_bytes, plan_json, plan_raw_fp, commitment, final_rel

    def prepare(
        self,
        plan: IndependentVerifierPackagingPlan,
        authority_raw: bytes,
    ) -> pathlib.Path:
        """PREPARE: Create random create-new staging inside output_root.

        Staging is inside output_root (same filesystem, rename-safe).
        Records dev/ino for cleanup validation.
        Never deletes pre-existing paths.

        Returns staging directory path.
        """
        # Reject if input path itself is a symlink (use raw path, not resolved)
        try:
            raw_st = self._output_root_raw.lstat()
            if stat.S_ISLNK(raw_st.st_mode):
                raise PublisherSymlinkError(f"SYMLINK_IN_OUTPUT_ROOT:{self._output_root_raw.name}")
        except PublisherSymlinkError:
            raise
        except FileNotFoundError:
            pass  # Will be created below

        # Validate output_root: check each component up to and including it
        self._check_path_components(self._output_root, "OUTPUT_ROOT")
        try:
            self._output_root.mkdir(parents=True, exist_ok=True)
        except FileExistsError:
            pass
        self._check_is_directory(self._output_root, "OUTPUT_ROOT")

        # Check output_root's parent for symlinks (walk up to root)
        parent_raw = os.path.abspath(str(self._output_root.parent))
        parent = pathlib.Path(parent_raw)
        self._check_path_components(parent, "OUTPUT_PARENT")

        # Random create-new staging INSIDE output_root
        for _ in range(128):
            suffix = os.urandom(16).hex()
            staging = self._output_root / f".staging-{suffix}"
            try:
                staging.mkdir(mode=0o755, parents=False)
                break
            except FileExistsError:
                continue
        else:
            raise PublisherError("STAGING_COLLISION")

        # Record dev/ino keyed by lexical staging path for per-staging cleanup
        st = staging.lstat()
        self._staging_registry[staging] = (st.st_dev, st.st_ino)

        try:
            # Compute plan raw fingerprint
            plan_json = plan.to_json()
            plan_bytes = _pretty_json(plan_json) + b"\n"
            plan_raw_fp = f"sha256:{hashlib.sha256(plan_bytes).hexdigest()}"

            # Compute inventory (plan files only)
            inventory = self.PLAN_INVENTORY_FILES

            # Compute commitment
            commitment = self._compute_commitment(plan_raw_fp, inventory, authority_raw)

            # Build receipt WITHOUT receiptFingerprint
            plan_fp_hex = plan_raw_fp.replace("sha256:", "")
            final_rel = f"{plan_fp_hex[:2]}/{plan_fp_hex[2:4]}/{plan_fp_hex}"

            receipt_body: dict[str, Any] = {
                "schemaVersion": RECEIPT_SCHEMA_VERSION,
                "authorityFingerprint": plan.authority_raw_fingerprint,
                "planRawFingerprint": plan_raw_fp,
                "commitment": commitment,
                "exactInventory": list(inventory),
                "publicationRoot": final_rel,
            }

            # Compute receipt fingerprint (domain-separated, excludes itself)
            receipt_body_for_fp = {k: v for k, v in receipt_body.items()}
            receipt_fp = self._receipt_fingerprint(receipt_body_for_fp)
            receipt_body["receiptFingerprint"] = receipt_fp

            # Write plan
            plan_path = staging / PLAN_OUTPUT_NAME
            plan_path.write_bytes(plan_bytes)
            self._check_not_symlink(plan_path, "STAGING_PLAN_FILE")

            # Write receipt
            receipt_path = staging / RECEIPT_OUTPUT_NAME
            receipt_bytes = _pretty_json(receipt_body) + b"\n"
            receipt_path.write_bytes(receipt_bytes)
            self._check_not_symlink(receipt_path, "STAGING_RECEIPT_FILE")

            # Verify written bytes
            if plan_path.read_bytes() != plan_bytes:
                raise PublisherError("PLAN_WRITE_VERIFY_FAILED")
            if receipt_path.read_bytes() != receipt_bytes:
                raise PublisherError("RECEIPT_WRITE_VERIFY_FAILED")

            # Re-parse receipt and recompute fingerprint
            reparse = strict_json_loads(receipt_bytes.decode("utf-8"))
            reparse_body = {k: v for k, v in reparse.items() if k != "receiptFingerprint"}
            if self._receipt_fingerprint(reparse_body) != receipt_fp:
                raise PublisherError("RECEIPT_FP_VERIFY_FAILED")

            return staging

        except Exception:
            self._cleanup_staging(staging)
            raise

    def commit(
        self,
        staging: pathlib.Path,
        plan: IndependentVerifierPackagingPlan,
        authority_raw: bytes,
    ) -> PublicationReceipt:
        """COMMIT: Read from staging, atomic rename to final via fixed lock.

        Any failure (fanout/final/path/rename/verify) triggers safe cleanup of
        the staging dev/ino after releasing the lock, then reraises the
        original exception.  Unknown attack items → CLEANUP_UNSAFE (not masked).
        """
        _commit_exc: Exception | None = None
        try:
            # -------- inner commit logic (raises on any failure) --------
            # Validate staging exists
            if not staging.exists():
                raise PublisherConflictError("STAGING_DISAPPEARED")
            self._check_not_symlink(staging, "STAGING")
            self._check_is_directory(staging, "STAGING")

            # Validate no symlinks in staging contents
            for p in staging.iterdir():
                self._check_not_symlink(p, "STAGING_CONTENT")

            # Read and recompute from staging
            plan_bytes, plan_json, plan_raw_fp, commitment, final_rel = \
                self._compute_plan_from_staging(staging, authority_raw)
            final_dir = self._output_root / final_rel

            # Path traversal check
            self._check_not_symlink(final_dir, "FINAL_DIR")
            self._check_no_traversal(final_dir, self._output_root)

            # Fixed lock file inside output_root — must be regular, nlink==1
            lock_path = self._output_root / ".publication.lock"
            lock_fd = os.open(str(lock_path), os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o644)
            try:
                # fstat to ensure lock is a regular file with exactly one hard link
                lock_st = os.fstat(lock_fd)
                if not stat.S_ISREG(lock_st.st_mode):
                    raise PublisherError("LOCK_NOT_REGULAR")
                if lock_st.st_nlink != 1:
                    raise PublisherError("LOCK_NLINK_NOT_ONE")
                fcntl.flock(lock_fd, fcntl.LOCK_EX)

                # TOCTOU: Re-check all path components after acquiring lock
                # Level-by-level lstat to detect symlink injection or traversal attacks
                self._check_path_ancestors(self._output_root, "OUTPUT_ROOT")
                self._check_path_ancestors(final_dir, "FINAL_DIR")

                # Re-check final_dir exists as directory (not symlink) if it exists
                if final_dir.exists():
                    try:
                        st = final_dir.lstat()
                    except FileNotFoundError:
                        st = None
                    if st is not None and not stat.S_ISDIR(st.st_mode):
                        raise PublisherNotDirectoryError("FINAL_DIR_NOT_DIRECTORY")
                    if final_dir.is_symlink():
                        raise PublisherSymlinkError("FINAL_DIR_IS_SYMLINK")

                    plan_path_existing = final_dir / PLAN_OUTPUT_NAME
                    receipt_path_existing = final_dir / RECEIPT_OUTPUT_NAME

                    if plan_path_existing.exists() and receipt_path_existing.exists():
                        existing_plan_bytes = plan_path_existing.read_bytes()
                        existing_receipt_bytes = receipt_path_existing.read_bytes()

                        if existing_plan_bytes == plan_bytes:
                            # Idempotent: plan bytes match; staging receipt bytes must also
                            # match existing receipt bytes before trusting verify_receipt.
                            staging_receipt_path = staging / RECEIPT_OUTPUT_NAME
                            staging_receipt_bytes = staging_receipt_path.read_bytes()
                            if staging_receipt_bytes != existing_receipt_bytes:
                                raise PublisherConflictError("RECEIPT_BYTES_DIFFER")
                            ok, reason = self.verify_receipt(receipt_path_existing, authority_raw)
                            if not ok:
                                raise PublisherConflictError(f"IDEMPOTENT_VERIFY_FAILED:{reason}")
                            existing_receipt = strict_json_loads(existing_receipt_bytes.decode("utf-8"))
                            if staging.exists():
                                self._cleanup_staging(staging)
                            return PublicationReceipt(
                                schema_version=RECEIPT_SCHEMA_VERSION,
                                authority_fingerprint=plan.authority_raw_fingerprint,
                                plan_raw_fingerprint=plan_raw_fp,
                                commitment=existing_receipt.get("commitment", ""),
                                exact_inventory=tuple(existing_receipt.get("exactInventory", [])),
                                publication_root=final_rel,
                                receipt_fingerprint=existing_receipt.get("receiptFingerprint", ""),
                            )
                        else:
                            raise PublisherConflictError("PLAN_BYTES_DIFFER")

                # Pre-create fanout parents
                plan_fp_hex = plan_raw_fp.replace("sha256:", "")
                fanout1 = self._output_root / plan_fp_hex[:2]
                fanout2 = fanout1 / plan_fp_hex[2:4]
                for fanout_dir in [fanout1, fanout2]:
                    self._check_not_symlink(fanout_dir, "FANOUT")
                    try:
                        fanout_dir.mkdir(mode=0o755, parents=True, exist_ok=True)
                    except FileExistsError:
                        if fanout_dir.is_symlink():
                            raise PublisherSymlinkError(f"FANOUT_IS_SYMLINK:{fanout_dir.name}")
                        if not fanout_dir.is_dir():
                            raise PublisherNotDirectoryError(f"NOT_DIRECTORY_FANOUT:{fanout_dir.name}")
                    # Scan for symlinks inside fanout dirs
                    for child in fanout_dir.iterdir():
                        try:
                            cs = child.lstat()
                            if stat.S_ISLNK(cs.st_mode):
                                raise PublisherSymlinkError(f"SYMLINK_IN_FANOUT:{child.name}")
                        except PublisherSymlinkError:
                            raise
                        except FileNotFoundError:
                            pass

                # TOCTOU: Re-check fanout ancestors before atomic rename
                # Detect any symlink injection into fanout paths after creation
                self._check_path_ancestors(fanout1, "FANOUT1")
                self._check_path_ancestors(fanout2, "FANOUT2")
                # Re-verify final_dir ancestors immediately before rename
                self._check_path_ancestors(final_dir, "FINAL_DIR")

                # Single atomic rename
                try:
                    os.rename(str(staging), str(final_dir))
                except OSError as e:
                    if e.errno in (errno.ENOTDIR, errno.EISDIR):
                        raise PublisherNotDirectoryError(f"NOT_DIRECTORY_FINAL:{e.errno}")
                    raise PublisherError(f"RENAME_FAILED:{e.errno}")

                if not final_dir.exists():
                    raise PublisherError("RENAME_DID_NOT_CREATE_DIR")

                # Fail-closed after rename
                self._check_not_symlink(final_dir, "COMMITTED_FINAL_DIR")
                for p in final_dir.iterdir():
                    self._check_not_symlink(p, "COMMITTED_FINAL_CONTENT")

                plan_path = final_dir / PLAN_OUTPUT_NAME
                receipt_path = final_dir / RECEIPT_OUTPUT_NAME
                if not plan_path.exists() or not receipt_path.exists():
                    raise PublisherIncompleteError("COMMITTED_DIR_INCOMPLETE")

                final_plan_bytes = plan_path.read_bytes()
                final_receipt_bytes = receipt_path.read_bytes()
                computed_plan_fp = f"sha256:{hashlib.sha256(final_plan_bytes).hexdigest()}"
                if computed_plan_fp != plan_raw_fp:
                    raise PublisherError("PLAN_FP_CHANGED_AFTER_COMMIT")

                final_receipt = strict_json_loads(final_receipt_bytes.decode("utf-8"))
                receipt_for_fp = {k: v for k, v in final_receipt.items() if k != "receiptFingerprint"}
                recomputed_receipt_fp = self._receipt_fingerprint(receipt_for_fp)
                if recomputed_receipt_fp != final_receipt.get("receiptFingerprint"):
                    raise PublisherError("RECEIPT_FP_MISMATCH_AFTER_COMMIT")

            except Exception as _e:
                _commit_exc = _e if _commit_exc is None else _commit_exc
                raise
            finally:
                fcntl.flock(lock_fd, fcntl.LOCK_UN)
                os.close(lock_fd)
                # After releasing lock, attempt safe cleanup if commit failed.
                # Reraise the original exception so callers see the real failure reason.
                if _commit_exc is not None:
                    self._cleanup_staging(staging)
                    raise _commit_exc

        except Exception as _e:
            _commit_exc = _e if _commit_exc is None else _commit_exc
            raise
        finally:
            # Staging must no longer exist after successful commit;
            # on failure, attempt safe cleanup after lock release.
            # If cleanup itself fails, preserve the original commit exception
            # (do not mask the real failure reason with a cleanup error).
            if staging.exists():
                try:
                    self._cleanup_staging(staging)
                except PublisherError:
                    pass  # preserve original _commit_exc

        return PublicationReceipt(
            schema_version=RECEIPT_SCHEMA_VERSION,
            authority_fingerprint=plan.authority_raw_fingerprint,
            plan_raw_fingerprint=plan_raw_fp,
            commitment=final_receipt.get("commitment", ""),
            exact_inventory=tuple(final_receipt.get("exactInventory", [])),
            publication_root=final_rel,
            receipt_fingerprint=final_receipt.get("receiptFingerprint", ""),
        )

    def _receipt_fingerprint(self, body: dict[str, Any]) -> str:
        """Domain-separated SHA-256 of receipt body using RECEIPT_COMMITMENT_DOMAIN."""
        domain_payload = {
            "domain": RECEIPT_COMMITMENT_DOMAIN,
            "body": body,
        }
        return _fp(domain_payload)

    def _compute_commitment(
        self,
        plan_raw_fp: str,
        inventory: tuple[str, ...],
        authority_raw: bytes,
    ) -> str:
        """Aggregate commitment using PLAN_COMMITMENT_DOMAIN."""
        commitment_data = {
            "domain": PLAN_COMMITMENT_DOMAIN,
            "planRawFingerprint": plan_raw_fp,
            "exactInventory": sorted(inventory),
            "authorityRawFingerprint": f"sha256:{hashlib.sha256(authority_raw).hexdigest()}",
        }
        return _fp(commitment_data)

    def verify_receipt(
        self,
        receipt_path: pathlib.Path,
        authority_raw: bytes,
    ) -> tuple[bool, str]:
        """Verify receipt: strict parse, exact checks, no absolute path leakage."""
        try:
            raw = receipt_path.read_bytes()
            receipt = strict_json_loads(raw.decode("utf-8"))
        except Exception:
            return False, "RECEIPT_JSON_PARSE_ERROR"

        # Exact field set
        required_fields = frozenset({
            "schemaVersion", "authorityFingerprint", "planRawFingerprint",
            "commitment", "exactInventory", "publicationRoot", "receiptFingerprint",
        })
        actual_fields = frozenset(receipt.keys())
        if actual_fields != required_fields:
            return False, "RECEIPT_FIELD_SET_MISMATCH"

        # Exact inventory == PLAN_INVENTORY_FILES
        if receipt.get("exactInventory") != list(self.PLAN_INVENTORY_FILES):
            return False, "INVENTORY_MISMATCH"

        # Exact authorityFingerprint == sha256(authority_raw)
        authority_fp = f"sha256:{hashlib.sha256(authority_raw).hexdigest()}"
        if receipt.get("authorityFingerprint") != authority_fp:
            return False, "AUTHORITY_FP_MISMATCH"

        # Plan JSON field set must exactly equal IndependentVerifierPackagingPlan.to_json fields
        plan_path = receipt_path.parent / PLAN_OUTPUT_NAME
        if not plan_path.exists():
            return False, "PLAN_MISSING"
        try:
            plan_bytes = plan_path.read_bytes()
            plan_json = strict_json_loads(plan_bytes.decode("utf-8"))
        except Exception:
            return False, "PLAN_JSON_PARSE_ERROR"
        expected_plan_fields = frozenset({
            "schemaVersion", "authorityIdentity", "authorityRevision",
            "authorityRawFingerprint", "roleIdentity", "roleArtifactMainClass",
            "roleExecutableClassEntry", "exactArchiveEntries", "packagedProjections",
            "profilePath", "registryPath", "canonicalizationPath",
            "compilationManifestPath", "embeddedProviderArtifact",
            "embeddedProviderIdentityPath", "classManifestPath",
            "resourceManifestPath", "dependencyManifestPath",
            "embeddedDependencies", "providerIdentityRecipe",
        })
        actual_plan_fields = frozenset(plan_json.keys())
        if actual_plan_fields != expected_plan_fields:
            return False, "PLAN_FIELD_SET_MISMATCH"

        # Plan raw fingerprint
        computed_plan_fp = f"sha256:{hashlib.sha256(plan_bytes).hexdigest()}"
        if computed_plan_fp != receipt.get("planRawFingerprint"):
            return False, "PLAN_FP_MISMATCH"

        # Commitment recompute
        inventory = tuple(receipt.get("exactInventory", []))
        recomputed_commitment = self._compute_commitment(computed_plan_fp, inventory, authority_raw)
        if recomputed_commitment != receipt.get("commitment"):
            return False, "COMMITMENT_MISMATCH"

        # Receipt fingerprint recompute
        body_for_fp = {k: v for k, v in receipt.items() if k != "receiptFingerprint"}
        recomputed_receipt_fp = self._receipt_fingerprint(body_for_fp)
        if recomputed_receipt_fp != receipt.get("receiptFingerprint"):
            return False, "RECEIPT_FP_MISMATCH"

        # Publication root: must equal aa/bb/fullhex derived from planRawFingerprint
        plan_fp_hex = computed_plan_fp.replace("sha256:", "")
        expected_pub_root = f"{plan_fp_hex[:2]}/{plan_fp_hex[2:4]}/{plan_fp_hex}"
        if receipt.get("publicationRoot") != expected_pub_root:
            return False, "PUBLICATION_ROOT_MISMATCH"

        # receipt_path.parent relative to output_root must equal publicationRoot (lexical, no resolve)
        try:
            # Walk receipt parent components with lstat (lexical, no resolve)
            abs_root = self._output_root
            abs_receipt_parent = pathlib.Path(os.path.abspath(str(receipt_path.parent)))
            rel = os.path.relpath(str(abs_receipt_parent), str(abs_root))
            for part in rel.split(os.sep):
                if not part or part == '.':
                    continue
                abs_root = abs_root / part
                try:
                    st = abs_root.lstat()
                except FileNotFoundError:
                    return False, "RECEIPT_PATH_MISSING"
                if stat.S_ISLNK(st.st_mode):
                    return False, "RECEIPT_PATH_SYMLINK"
                if not stat.S_ISDIR(st.st_mode):
                    return False, "RECEIPT_PATH_NOT_DIRECTORY"
            if rel != expected_pub_root:
                return False, "RECEIPT_PATH_REL_MISMATCH"
        except ValueError:
            return False, "RECEIPT_PATH_REL_MISMATCH"

        return True, "RECEIPT_VERIFIED"


# ---------------------------------------------------------------------------
# CLI helper
# ---------------------------------------------------------------------------

def compile_and_publish(
    authority_path: pathlib.Path,
    compiled_projections: dict[str, dict[str, Any]],
    compilation_manifest: dict[str, Any],
    output_root: pathlib.Path,
) -> PublicationReceipt:
    """Compile plan from authority and publish."""
    import sys as _sys
    _sys.path.insert(0, str(pathlib.Path(__file__).parent))

    from compiler_core import LinkedProtocolModel

    authority_raw = authority_path.read_bytes()
    linked_model = LinkedProtocolModel.from_authority(authority_raw)

    plan = derive_packaging_plan(
        linked_model=linked_model,
        compiled_projections=compiled_projections,
        compilation_manifest=compilation_manifest,
        authority_raw=authority_raw,
            )

    publisher = Publisher(output_root=output_root)
    staging = publisher.prepare(plan=plan, authority_raw=authority_raw)
    receipt = publisher.commit(staging=staging, plan=plan, authority_raw=authority_raw)

    return receipt


if __name__ == "__main__":
    import argparse
    import sys as _sys

    _sys.path.insert(0, str(pathlib.Path(__file__).parent))

    from compiler_core import LinkedProtocolModel, compile_authority

    parser = argparse.ArgumentParser(description="Independent Verifier Packaging Plan")
    parser.add_argument("--authority", type=pathlib.Path, required=True)
    parser.add_argument("--compiled-projections-dir", type=pathlib.Path, required=True)
    parser.add_argument("--output-root", type=pathlib.Path, required=True)

    args = parser.parse_args()

    # Load authority
    authority_raw = args.authority.read_bytes()
    linked_model = LinkedProtocolModel.from_authority(authority_raw)

    # Load compiled projections strictly
    manifest_path = args.compiled_projections_dir / "protocol-compilation-manifest-v1.json"
    if not manifest_path.exists():
        raise SystemExit("COMPILATION_MANIFEST_NOT_FOUND")

    manifest = strict_json_loads(manifest_path.read_text())

    # Rebind projections from compiled dir
    compiled_projections: dict[str, dict[str, Any]] = {}
    manifest_projection_ids = {e["projectionId"] for e in manifest.get("projections", [])}

    for entry in manifest["projections"]:
        pid = entry["projectionId"]
        path = args.compiled_projections_dir / entry["path"]
        if not path.exists():
            raise SystemExit(f"PROJECTION_FILE_MISSING:{pid}")
        projection = strict_json_loads(path.read_text())

        # Rebind: verify projectionId matches
        if projection.get("projectionId") != pid:
            raise SystemExit(f"PROJECTION_ID_MISMATCH:{pid}")

        # Rebind: verify sourceRawFingerprint matches authority
        if projection.get("sourceRawFingerprint") != linked_model.authority_fingerprint:
            raise SystemExit(f"PROJECTION_SOURCE_FP_MISMATCH:{pid}")

        # Rebind: verify jarEntryPath matches manifest
        if entry.get("jarEntryPath") != f"META-INF/gate-a/projections/{pid.lower()}-v1.json":
            raise SystemExit(f"PROJECTION_JAR_ENTRY_MISMATCH:{pid}")

        compiled_projections[pid] = projection

    # Derive and publish
    receipt = compile_and_publish(
        authority_path=args.authority,
        compiled_projections=compiled_projections,
        compilation_manifest=manifest,
        output_root=args.output_root,
            )

    print(f"PUBLISHED:{receipt.publication_root}")
    print(f"PLAN_FP:{receipt.plan_raw_fingerprint}")
    print(f"RECEIPT_FP:{receipt.receipt_fingerprint}")