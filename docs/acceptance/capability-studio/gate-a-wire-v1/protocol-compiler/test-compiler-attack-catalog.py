#!/usr/bin/env python3
"""Tests for compiler_attack_catalog.py.

Demonstrates:
  - Denominator is exactly 60 (38+2+19+1)
  - 38 RELATION attacks: exact equality with authorityRelations fact set
  - Each mutation changes canonical bytes
  - Baseline authority is never modified
  - Wrong category classification fails
  - Duplicate IDs / missing entries fail
  - PRODUCTION: ProtocolSemanticValidator rejects all 60 with actual==expected

Run:
  python test-compiler-attack-catalog.py
  python test-compiler-attack-catalog.py --skip-production   # skip production tests
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import pathlib
import sys

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

HERE = pathlib.Path(__file__).resolve().parent
AUTHORITY_PATH = HERE / "gate-a-protocol-authority-v1.json"

sys.path.insert(0, str(HERE))
from compiler_attack_catalog import (
    build_attack_catalog,
    validate_catalog,
    AttackVector,
    _derive_rel_id,
    _rel_expected_code,
    build_semantic_registry,
    _fp,
    _canon,
)
from compiler_core import (
    ProtocolSemanticValidator,
    build_graph,
    link_graph,
    LinkedProtocolModel,
    _authority_to_strict_bytes,
)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

def load_authority() -> dict:
    return json.loads(AUTHORITY_PATH.read_text(encoding="utf-8"))


def canonical(value) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")


# ---------------------------------------------------------------------------
# Tests — catalog integrity (unchanged)
# ---------------------------------------------------------------------------

def test_denominator_exact_60():
    """Total catalog size must be exactly 60."""
    authority = load_authority()
    catalog = build_attack_catalog(authority)
    total = len(catalog)
    assert total == 60, f"Expected 60 attacks, got {total}"
    print(f"  PASS  test_denominator_exact_60: total={total}")


def test_category_counts():
    """38 RELATION + 2 TRUST_PROJECTION + 19 BOUNDARY + 1 ACTIVE_WITH_NULL_PINS."""
    authority = load_authority()
    catalog = build_attack_catalog(authority)
    cats = {v.category for v in catalog}
    assert cats == {"RELATION", "TRUST_PROJECTION", "BOUNDARY", "ACTIVE_WITH_NULL_PINS"}, (
        f"Unexpected categories: {cats}"
    )
    rel = sum(1 for v in catalog if v.category == "RELATION")
    tp = sum(1 for v in catalog if v.category == "TRUST_PROJECTION")
    bnd = sum(1 for v in catalog if v.category == "BOUNDARY")
    anp = sum(1 for v in catalog if v.category == "ACTIVE_WITH_NULL_PINS")
    assert rel == 38, f"RELATION: expected 38, got {rel}"
    assert tp == 2,   f"TRUST_PROJECTION: expected 2, got {tp}"
    assert bnd == 19, f"BOUNDARY: expected 19, got {bnd}"
    assert anp == 1,  f"ACTIVE_WITH_NULL_PINS: expected 1, got {anp}"
    print(f"  PASS  test_category_counts: RELATION=38, TRUST_PROJECTION=2, BOUNDARY=19, ACTIVE_WITH_NULL_PINS=1")


def test_relation_38_exact_coverage():
    """Each of the 38 authorityRelations facts has exactly one RELATION attack."""
    authority = load_authority()
    catalog = build_attack_catalog(authority)
    relations = authority.get("authorityRelations", [])
    assert len(relations) == 38, f"Expected 38 authorityRelations, got {len(relations)}"

    for idx, rel in enumerate(relations, start=1):
        fact = rel["fact"]
        expected_id = _derive_rel_id(fact, idx)
        matching = [v for v in catalog if v.id == expected_id]
        assert len(matching) == 1, (
            f"Fact {idx} '{fact}': expected exactly 1 attack with id={expected_id}, "
            f"got {len(matching)}"
        )
        vec = matching[0]
        assert vec.category == "RELATION", f"{expected_id}: expected category RELATION, got {vec.category}"
        assert vec.expected_code == _rel_expected_code(fact), (
            f"{expected_id}: expected code {_rel_expected_code(fact)}, got {vec.expected_code}"
        )
    print(f"  PASS  test_relation_38_exact_coverage: all 38 facts covered exactly once")


def test_mutation_changes_canonical_bytes():
    """Every mutation must change the authority's canonical JSON bytes."""
    authority = load_authority()
    catalog = build_attack_catalog(authority)
    orig_fp = _fp(authority)

    failures = []
    for vec in catalog:
        # For raw-only mutations (like B19 strict-JSON duplicate), use apply_bytes
        # For dict mutations, use apply then re-serialize
        if vec.mutate_bytes is not None:
            mutated_bytes = vec.apply_bytes(authority)
            mutated = json.loads(mutated_bytes.decode("utf-8"))
        else:
            mutated = vec.apply(authority)
        mut_fp = _fp(mutated)
        if orig_fp == mut_fp:
            failures.append(vec.id)

    assert not failures, (
        f"Mutations with no byte-change (canonical fingerprint unchanged): {failures}"
    )
    print(f"  PASS  test_mutation_changes_canonical_bytes: all {len(catalog)} mutations change bytes")


def test_baseline_not_modified():
    """Applying any mutation must not modify the original authority dict."""
    authority = load_authority()
    catalog = build_attack_catalog(authority)
    orig_fp = _fp(authority)

    for vec in catalog:
        _ = vec.apply(authority)

    after_fp = _fp(authority)
    assert orig_fp == after_fp, "Baseline authority was modified by mutation"
    print(f"  PASS  test_baseline_not_modified: all {len(catalog)} mutations are non-destructive")


def test_each_mutation_is_semantically_different():
    """Each mutation must produce a semantically distinct authority."""
    authority = load_authority()
    catalog = build_attack_catalog(authority)
    registry = build_semantic_registry(authority)

    failures = []
    for vec in catalog:
        # Skip raw-only mutations like B19 (strict-JSON duplicate) which don't change dict structure
        if vec.mutate_bytes is not None:
            continue
        mutated = vec.apply(authority)
        mut_registry = build_semantic_registry(mutated)
        if registry == mut_registry:
            failures.append(vec.id)

    assert not failures, (
        f"Mutations that produce semantically identical authority: {failures}"
    )
    print(f"  PASS  test_each_mutation_is_semantically_different: all {len(catalog)} are semantically distinct")


def test_validate_catalog_pass():
    authority = load_authority()
    catalog = build_attack_catalog(authority)
    failures = validate_catalog(catalog, authority)
    assert not failures, f"Catalog integrity failures: {failures}"
    print(f"  PASS  test_validate_catalog_pass")


def test_validate_catalog_wrong_category():
    """A catalog with a wrong category must be detected."""
    authority = load_authority()
    catalog = build_attack_catalog(authority)
    # Replace one entry with a wrong category
    wrong_cat = catalog[0]
    from compiler_attack_catalog import AttackVector
    wrong = AttackVector(
        id=wrong_cat.id,
        category="WRONG_CATEGORY",
        expected_code=wrong_cat.expected_code,
        mutate=wrong_cat.mutate,
    )
    bad_catalog = (*catalog[:0], wrong, *catalog[1:])
    failures = validate_catalog(bad_catalog, authority)
    assert any("INVALID_CATEGORY" in f for f in failures), (
        f"Expected INVALID_CATEGORY failure, got: {failures}"
    )
    print(f"  PASS  test_validate_catalog_wrong_category")


def test_validate_catalog_duplicate_id():
    """A catalog with duplicate IDs must be detected."""
    authority = load_authority()
    catalog = build_attack_catalog(authority)
    dup = (*catalog, catalog[0])
    failures = validate_catalog(dup, authority)
    assert any("DUPLICATE_ID" in f for f in failures), (
        f"Expected DUPLICATE_ID failure, got: {failures}"
    )
    print(f"  PASS  test_validate_catalog_duplicate_id")


def test_validate_catalog_missing_relation_fact():
    """A catalog missing a required relation fact must be detected."""
    authority = load_authority()
    catalog = build_attack_catalog(authority)
    # Drop the first RELATION entry
    first_rel = next(v for v in catalog if v.category == "RELATION")
    bad = tuple(v for v in catalog if v.id != first_rel.id)
    failures = validate_catalog(bad, authority)
    assert any("MISSING_RELATION_FACT" in f for f in failures), (
        f"Expected MISSING_RELATION_FACT failure, got: {failures}"
    )
    print(f"  PASS  test_validate_catalog_missing_relation_fact")


def test_trust_projection_ids_and_codes():
    """TRUST_PROJECTION attacks must have correct IDs and expected codes."""
    authority = load_authority()
    catalog = build_attack_catalog(authority)
    tp = [v for v in catalog if v.category == "TRUST_PROJECTION"]
    assert len(tp) == 2
    expected = {
        "TRUST_PROJECTION_01_TRUST_BOUNDARY_DRIFT": "PROTOCOL_TRUST_BOUNDARY_DRIFT",
        "TRUST_PROJECTION_02_PROJECTION_SELECTOR_DRIFT": "PROTOCOL_PROJECTION_SELECTOR_DRIFT",
    }
    for vec in tp:
        assert vec.id in expected, f"Unexpected TP id: {vec.id}"
        assert vec.expected_code == expected[vec.id], (
            f"{vec.id}: expected {expected[vec.id]}, got {vec.expected_code}"
        )
    print(f"  PASS  test_trust_projection_ids_and_codes")


def test_boundary_19_ids_and_codes():
    """BOUNDARY_19 must have correct ID, code, and raw bytes mutation."""
    authority = load_authority()
    catalog = build_attack_catalog(authority)
    b19 = next((v for v in catalog if v.id == "BOUNDARY_19_STRICT_JSON_DUPLICATE"), None)
    assert b19 is not None, "BOUNDARY_19_STRICT_JSON_DUPLICATE not found"
    assert b19.expected_code == "PROTOCOL_JSON_DUPLICATE_MEMBER"
    assert b19.mutate_bytes is not None, "BOUNDARY_19 must have mutate_bytes"
    # Verify mutate_bytes produces actual duplicate-key JSON
    raw_bytes = b19.apply_bytes(authority)
    # Check that the raw bytes contain two occurrences of "_DUP_INJECT"
    decoded = raw_bytes.decode("utf-8")
    count = decoded.count('"_DUP_INJECT"')
    assert count == 2, f"BOUNDARY_19 raw bytes must have 2 occurrences of _DUP_INJECT, got {count}"
    print(f"  PASS  test_boundary_19_ids_and_codes: duplicate-key raw bytes confirmed")


def test_active_with_null_pins_code():
    """ACTIVE_WITH_NULL_PINS must have correct expected code."""
    authority = load_authority()
    catalog = build_attack_catalog(authority)
    anp = next((v for v in catalog if v.category == "ACTIVE_WITH_NULL_PINS"), None)
    assert anp is not None, "No ACTIVE_WITH_NULL_PINS attack found"
    assert anp.id == "ACTIVE_WITH_NULL_PINS"
    assert anp.expected_code == "PROTOCOL_DEPENDENCY_STATUS_NOT_PINNED", (
        f"Expected PROTOCOL_DEPENDENCY_STATUS_NOT_PINNED, got {anp.expected_code}"
    )
    # Verify baseline is DRAFT_UNPINNED
    assert authority["dependencyAuthority"]["status"] == "DRAFT_UNPINNED"
    # Verify mutation produces ACTIVE
    mutated = anp.apply(authority)
    assert mutated["dependencyAuthority"]["status"] == "ACTIVE", (
        f"Mutation did not set status=ACTIVE"
    )
    # Verify parentLockFingerprint is NOT set (distinguishes from BOUNDARY_03)
    assert mutated["dependencyAuthority"].get("parentLockFingerprint") is None, (
        "ACTIVE_WITH_NULL_PINS must NOT set parentLockFingerprint"
    )
    print(f"  PASS  test_active_with_null_pins_code")


def test_relation_mutation_id_drift():
    """RELATION attack IDs must be derived from fact names, not arbitrary strings."""
    authority = load_authority()
    relations = authority.get("authorityRelations", [])
    for idx, rel in enumerate(relations, start=1):
        fact = rel["fact"]
        derived = _derive_rel_id(fact, idx)
        assert fact in derived, f"ID {derived} does not contain fact name '{fact}'"
        assert str(idx) in derived, f"ID {derived} does not contain index {idx}"
    print(f"  PASS  test_relation_mutation_id_drift")


def test_semantic_registry_not_self_generated():
    """Semantic registry must be built from baseline, not from mutated authority."""
    authority = load_authority()
    catalog = build_attack_catalog(authority)
    registry = build_semantic_registry(authority)

    assert len(registry["authority_relations"]) == 38
    for rel in registry["authority_relations"]:
        assert "forbiddenSource" not in rel, "Registry must not contain forbiddenSource"

    rel_attack = next(v for v in catalog if v.category == "RELATION")
    _ = rel_attack.apply(authority)
    registry_after = build_semantic_registry(authority)
    assert registry == registry_after, "Semantic registry changed after mutation"
    print(f"  PASS  test_semantic_registry_not_self_generated")


def test_no_authority_field_invention():
    """Mutations must not invent new top-level authority fields.

    Exclude BOUNDARY_19 which uses raw-bytes mutation for strict-JSON injection.
    """
    authority = load_authority()
    catalog = build_attack_catalog(authority)
    baseline_keys = set(authority.keys())
    non_boundary19 = [v for v in catalog if v.id != "BOUNDARY_19_STRICT_JSON_DUPLICATE"]

    for vec in non_boundary19:
        mutated = vec.apply(authority)
        mutated_keys = set(mutated.keys())
        new_keys = mutated_keys - baseline_keys
        if new_keys:
            assert not new_keys, (
                f"{vec.id}: mutation introduced new top-level authority keys: {new_keys}"
            )
    print(f"  PASS  test_no_authority_field_invention")


# ---------------------------------------------------------------------------
# PRODUCTION TESTS — ProtocolSemanticValidator against all 60 attacks
# ---------------------------------------------------------------------------

def _run_production_test(authority: dict, catalog: tuple[AttackVector, ...]) -> dict:
    """Run production validation for all 60 attacks. Returns summary dict."""
    validator = ProtocolSemanticValidator(authority)
    results = []
    passed = 0
    failed = 0
    errors: list[str] = []

    for vec in catalog:
        # Produce raw bytes for validation
        raw_bytes = vec.apply_bytes(authority)
        ok, code = validator.validate(raw_bytes)

        if not ok and code == vec.expected_code:
            passed += 1
            results.append({"id": vec.id, "status": "PASS", "code": code})
        else:
            failed += 1
            actual = code if not ok else "VALID (should have been rejected)"
            results.append({
                "id": vec.id,
                "status": "FAIL",
                "expected": vec.expected_code,
                "actual": actual,
            })
            errors.append(
                f"  FAIL  {vec.id}: expected={vec.expected_code}, actual={actual}"
            )

    return {
        "total": len(catalog),
        "passed": passed,
        "failed": failed,
        "errors": errors,
        "results": results,
    }


def test_production_60_rejected():
    """ProtocolSemanticValidator must reject all 60 attacks with actual==expected."""
    authority = load_authority()
    catalog = build_attack_catalog(authority)
    summary = _run_production_test(authority, catalog)

    print(f"  --- Production validation: {summary['passed']}/{summary['total']} passed ---")
    for err in summary["errors"]:
        print(err)

    if summary["errors"]:
        print()
        print(f"FAIL — {summary['failed']}/{summary['total']} production tests failed")
        for err in summary["errors"]:
            print(err)
        assert False, f"{summary['failed']} production tests failed"

    print(f"  PASS  test_production_60_rejected: {summary['passed']}/{summary['total']} production attacks rejected with correct code")


def test_production_38_relation_semantic():
    """Each RELATION attack must trigger PROTOCOL_RELATION_<fact>."""
    authority = load_authority()
    catalog = build_attack_catalog(authority)
    validator = ProtocolSemanticValidator(authority)

    failures = []
    for vec in catalog:
        if vec.category != "RELATION":
            continue
        raw_bytes = vec.apply_bytes(authority)
        ok, code = validator.validate(raw_bytes)
        if ok or code != vec.expected_code:
            failures.append(f"  {vec.id}: expected={vec.expected_code}, actual={code if not ok else 'VALID'}")

    assert not failures, (
        f"RELATION production tests that did not match expected code:\n"
        + "\n".join(failures)
    )
    print(f"  PASS  test_production_38_relation_semantic: all 38 RELATION attacks rejected correctly")


def test_production_trust_projection():
    """TRUST_PROJECTION attacks must trigger correct drift codes."""
    authority = load_authority()
    catalog = build_attack_catalog(authority)
    validator = ProtocolSemanticValidator(authority)

    expected = {
        "TRUST_PROJECTION_01_TRUST_BOUNDARY_DRIFT": "PROTOCOL_TRUST_BOUNDARY_DRIFT",
        "TRUST_PROJECTION_02_PROJECTION_SELECTOR_DRIFT": "PROTOCOL_PROJECTION_SELECTOR_DRIFT",
    }

    failures = []
    for vec in catalog:
        if vec.category != "TRUST_PROJECTION":
            continue
        raw_bytes = vec.apply_bytes(authority)
        ok, code = validator.validate(raw_bytes)
        if ok or code != expected.get(vec.id):
            failures.append(
                f"  {vec.id}: expected={expected.get(vec.id)}, actual={code if not ok else 'VALID'}"
            )

    assert not failures, "TRUST_PROJECTION production failures:\n" + "\n".join(failures)
    print(f"  PASS  test_production_trust_projection: 2 TRUST_PROJECTION attacks rejected correctly")


def test_production_boundary_19_raw_bytes():
    """BOUNDARY_19 must be rejected via strict_parse duplicate-key path."""
    authority = load_authority()
    catalog = build_attack_catalog(authority)
    validator = ProtocolSemanticValidator(authority)

    b19 = next(v for v in catalog if v.id == "BOUNDARY_19_STRICT_JSON_DUPLICATE")
    raw_bytes = b19.apply_bytes(authority)
    ok, code = validator.validate(raw_bytes)

    assert not ok, f"BOUNDARY_19 must be rejected, got VALID"
    assert code == "PROTOCOL_JSON_DUPLICATE_MEMBER", (
        f"BOUNDARY_19: expected PROTOCOL_JSON_DUPLICATE_MEMBER, got {code}"
    )
    print(f"  PASS  test_production_boundary_19_raw_bytes: BOUNDARY_19 rejected via strict_parse")


def test_production_boundary_distinct_active_codes():
    """BOUNDARY_03 and ACTIVE_WITH_NULL_PINS must produce distinct error codes.

    BOUNDARY_03:  status=ACTIVE + parentLockFingerprint set + external pins null
                  → ACTIVE_REQUIRES_PREVIOUS_HEAD
    ACTIVE_NULL:  status=ACTIVE + parentLockFingerprint NOT set + external pins null
                  → STATUS_NOT_PINNED
    """
    authority = load_authority()
    catalog = build_attack_catalog(authority)
    validator = ProtocolSemanticValidator(authority)

    b03 = next(v for v in catalog if v.id == "BOUNDARY_03_DEP_ACTIVE")
    anp = next(v for v in catalog if v.category == "ACTIVE_WITH_NULL_PINS")

    _, b03_code = validator.validate(b03.apply_bytes(authority))
    _, anp_code = validator.validate(anp.apply_bytes(authority))

    assert b03_code == "PROTOCOL_DEPENDENCY_ACTIVE_REQUIRES_PREVIOUS_HEAD", (
        f"BOUNDARY_03: expected ACTIVE_REQUIRES_PREVIOUS_HEAD, got {b03_code}"
    )
    assert anp_code == "PROTOCOL_DEPENDENCY_STATUS_NOT_PINNED", (
        f"ACTIVE_WITH_NULL_PINS: expected STATUS_NOT_PINNED, got {anp_code}"
    )
    assert b03_code != anp_code, (
        f"BOUNDARY_03 and ACTIVE_WITH_NULL_PINS must have distinct codes, both got {b03_code}"
    )
    print(f"  PASS  test_production_boundary_distinct_active_codes: B03={b03_code}, ANP={anp_code}")


def test_production_abi_tck_provider_target():
    """ABI attacks must target TCK_PROVIDER.packagingContract.providedAbiDependencies."""
    authority = load_authority()
    catalog = build_attack_catalog(authority)
    validator = ProtocolSemanticValidator(authority)

    abi_ids = [
        "BOUNDARY_09_ABI_DESC",
        "BOUNDARY_10_ABI_GAV",
        "BOUNDARY_11_ABI_SPI",
        "BOUNDARY_12_ABI_OVERLAP",
        "BOUNDARY_13_ABI_SPI_MISSING",
    ]
    expected_codes = {
        "BOUNDARY_09_ABI_DESC": "PROTOCOL_PROVIDED_ABI_DESCRIPTOR_DRIFT",
        "BOUNDARY_10_ABI_GAV": "PROTOCOL_PROVIDED_ABI_GAV_DRIFT",
        "BOUNDARY_11_ABI_SPI": "PROTOCOL_PROVIDED_ABI_SPI_ENTRY_DRIFT",
        "BOUNDARY_12_ABI_OVERLAP": "PROTOCOL_PROVIDED_ABI_EMBEDDED_OVERLAP",
        "BOUNDARY_13_ABI_SPI_MISSING": "PROTOCOL_PROVIDED_ABI_SPI_MISSING",
    }

    failures = []
    for aid in abi_ids:
        vec = next(v for v in catalog if v.id == aid)
        raw_bytes = vec.apply_bytes(authority)
        ok, code = validator.validate(raw_bytes)
        if ok or code != expected_codes[aid]:
            failures.append(
                f"  {aid}: expected={expected_codes[aid]}, actual={code if not ok else 'VALID'}"
            )

    assert not failures, "ABI production failures:\n" + "\n".join(failures)
    print(f"  PASS  test_production_abi_tck_provider_target: all 5 ABI attacks rejected correctly")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# R01 / R02 targeted tests — outside the 60-attack catalog denominator
# ---------------------------------------------------------------------------

def _get_verifier_contract(authority: dict) -> dict:
    for rc in authority.get("roleContracts", []):
        if rc["role"] == "INDEPENDENT_VERIFIER":
            return rc
    raise AssertionError("INDEPENDENT_VERIFIER role contract not found")


# ---- R01: INDEPENDENT_VERIFIER requiredJarEntries checks ----

def test_r01_baseline_link_no_errors():
    """Baseline authority produces no R01/R02 errors from link_graph."""
    authority = load_authority()
    graph = build_graph(authority)
    errors = link_graph(authority, graph)
    # Filter only R01/R02 codes (no SCHEMA_SET_ENTRY warnings)
    r_codes = [e for e in errors
               if e.startswith("PROTOCOL_DEPENDENCY") or e.startswith("PROTOCOL_RELATION")]
    assert not r_codes, f"Baseline should have no R01/R02 errors, got: {r_codes}"
    print(f"  PASS  test_r01_baseline_link_no_errors")


def test_r01_remove_provider_entry_path():
    """Removing providerEntryPath from INDEPENDENT_VERIFIER produces R01 errors."""
    authority = load_authority()
    rc = _get_verifier_contract(authority)
    orig_path = rc.get("providerEntryPath")
    assert orig_path, "baseline has providerEntryPath"

    # Remove providerEntryPath
    del rc["providerEntryPath"]
    graph = build_graph(authority)
    errors = link_graph(authority, graph)

    # Expected: PROTOCOL_DEPENDENCY_IDENTITY_PATH_PROVIDER_MISSING (provider JAR not in observed)
    # + PROTOCOL_DEPENDENCY_REQUIRED_JAR_MISSING:<path> (derived says it's required)
    # + PROTOCOL_DEPENDENCY_REQUIRED_JAR_COUNT_27 (observed is now 27)
    has_missing_path = any(orig_path in e for e in errors)
    has_count_mismatch = any("PROTOCOL_DEPENDENCY_REQUIRED_JAR_COUNT_27" in e for e in errors)
    has_provider_missing = any("PROTOCOL_DEPENDENCY_IDENTITY_PATH_PROVIDER_MISSING" in e for e in errors)
    assert has_missing_path or has_count_mismatch or has_provider_missing, (
        f"Expected R01 errors for removed providerEntryPath, got: {errors}"
    )
    print(f"  PASS  test_r01_remove_provider_entry_path")


def test_r01_remove_identity_entry_path():
    """Removing providerIdentityEntryPath produces R01 identity-path-missing error."""
    authority = load_authority()
    rc = _get_verifier_contract(authority)
    orig_id_path = rc.get("providerIdentityEntryPath")
    assert orig_id_path, "baseline has providerIdentityEntryPath"

    del rc["providerIdentityEntryPath"]
    graph = build_graph(authority)
    errors = link_graph(authority, graph)

    has_missing_path = any(orig_id_path in e for e in errors)
    has_id_missing = any("PROTOCOL_DEPENDENCY_IDENTITY_PATH_PROVIDER_ID_MISSING" in e for e in errors)
    has_count_mismatch = any("PROTOCOL_DEPENDENCY_REQUIRED_JAR_COUNT_27" in e for e in errors)
    assert has_missing_path or has_id_missing or has_count_mismatch, (
        f"Expected R01 errors for removed identity path, got: {errors}"
    )
    print(f"  PASS  test_r01_remove_identity_entry_path")


def test_r01_add_legacy_path():
    """Adding the stale legacy provider JAR path to requiredJarEntries triggers R01 error."""
    authority = load_authority()
    rc = _get_verifier_contract(authority)
    legacy = "META-INF/gate-a/provider/provider.jar"
    assert legacy not in rc.get("requiredJarEntries", []), "legacy not in baseline"
    rc["requiredJarEntries"] = rc.get("requiredJarEntries", []) + [legacy]

    graph = build_graph(authority)
    errors = link_graph(authority, graph)

    has_legacy_error = any("PROTOCOL_DEPENDENCY_IDENTITY_PATH_LEGACY_STALE" in e for e in errors)
    has_extra = any("PROTOCOL_DEPENDENCY_REQUIRED_JAR_EXTRA:" in e for e in errors)
    assert has_legacy_error or has_extra, (
        f"Expected R01 error for legacy path addition, got: {errors}"
    )
    print(f"  PASS  test_r01_add_legacy_path")


def test_r01_add_arbitrary_extra():
    """Adding an arbitrary extra entry to requiredJarEntries triggers R01 EXTRA error."""
    authority = load_authority()
    rc = _get_verifier_contract(authority)
    extra = "META-INF/extra/arbitrary-extra-entry.class"
    rc["requiredJarEntries"] = rc.get("requiredJarEntries", []) + [extra]

    graph = build_graph(authority)
    errors = link_graph(authority, graph)

    has_extra = any("PROTOCOL_DEPENDENCY_REQUIRED_JAR_EXTRA:" in e for e in errors)
    has_count = any("PROTOCOL_DEPENDENCY_REQUIRED_JAR_COUNT_29" in e for e in errors)
    assert has_extra or has_count, (
        f"Expected R01 EXTRA or COUNT error for arbitrary extra, got: {errors}"
    )
    print(f"  PASS  test_r01_add_arbitrary_extra")


def test_r01_mutate_provider_entry_path_unchanged_assertion():
    """Mutating providerEntryPath while requiredJarEntries stays baseline triggers R01 error.

    The derived set changes (provider JAR path is now different) but the observed
    assertion list still contains the old path — mismatch is detected.
    """
    authority = load_authority()
    rc = _get_verifier_contract(authority)
    orig_path = rc.get("providerEntryPath")
    mutated_path = orig_path + ".spoofed"

    rc["providerEntryPath"] = mutated_path
    # requiredJarEntries is NOT updated — it still has the original path
    graph = build_graph(authority)
    errors = link_graph(authority, graph)

    has_missing = any(orig_path in e and "MISSING" in e for e in errors)
    has_count = any("PROTOCOL_DEPENDENCY_REQUIRED_JAR_COUNT_27" in e for e in errors)
    assert has_missing or has_count, (
        f"Expected R01 MISSING/COUNT error for mutated providerEntryPath, got: {errors}"
    )
    print(f"  PASS  test_r01_mutate_provider_entry_path_unchanged_assertion")


def test_r01_mutate_provider_identity_entry_path():
    """Mutating providerIdentityEntryPath triggers R01 MISSING for the original path."""
    authority = load_authority()
    rc = _get_verifier_contract(authority)
    orig_id_path = rc.get("providerIdentityEntryPath")
    mutated_id_path = orig_id_path + ".spoofed"

    rc["providerIdentityEntryPath"] = mutated_id_path
    # requiredJarEntries NOT updated — still has original path
    graph = build_graph(authority)
    errors = link_graph(authority, graph)

    has_missing = any(orig_id_path in e and "MISSING" in e for e in errors)
    has_count = any("PROTOCOL_DEPENDENCY_REQUIRED_JAR_COUNT_27" in e for e in errors)
    assert has_missing or has_count, (
        f"Expected R01 MISSING/COUNT error for mutated identity path, got: {errors}"
    )
    print(f"  PASS  test_r01_mutate_provider_identity_entry_path")


def test_r01_assertion_only_list_mutation_preserves_fingerprint():
    """Modifying requiredJarEntries alone leaves graph/linked-model fingerprint baseline.

    requiredJarEntries is an Authority-authored assertion; it does NOT affect
    the graph derived from declared contract fields.  Both graph_fingerprint()
    and linked_model_fingerprint() must remain stable.
    """
    authority = load_authority()
    rc = _get_verifier_contract(authority)

    # Capture baseline fingerprints
    graph_baseline = build_graph(authority)
    fp_baseline = graph_baseline.graph_fingerprint()
    raw_baseline = _authority_to_strict_bytes(authority)
    model_baseline = LinkedProtocolModel.from_authority(raw_baseline)
    lmf_baseline = model_baseline.linked_model_fingerprint()

    # Mutate only requiredJarEntries — add an arbitrary extra entry
    rc["requiredJarEntries"] = rc.get("requiredJarEntries", []) + [
        "META-INF/assertion-only-mutation.class"
    ]

    # Build model from mutated authority bytes
    raw_mutated = _authority_to_strict_bytes(authority)
    model_mutated = LinkedProtocolModel.from_authority(raw_mutated)

    # graph_fingerprint must be identical (graph derived from declared fields only)
    fp_mutated = model_mutated.graph.graph_fingerprint()
    assert fp_mutated == fp_baseline, (
        f"graph_fingerprint changed after requiredJarEntries mutation: "
        f"baseline={fp_baseline}, mutated={fp_mutated}"
    )

    # linked_model_fingerprint must also be identical
    lmf_mutated = model_mutated.linked_model_fingerprint()
    assert lmf_mutated == lmf_baseline, (
        f"linked_model_fingerprint changed after requiredJarEntries mutation: "
        f"baseline={lmf_baseline}, mutated={lmf_mutated}"
    )

    # But link_graph must catch the assertion drift (extra entry)
    errors = model_mutated.link_errors
    has_extra = any("PROTOCOL_DEPENDENCY_REQUIRED_JAR_EXTRA:" in e for e in errors)
    assert has_extra, f"Expected R01 EXTRA error from assertion mutation, got: {errors}"
    print(f"  PASS  test_r01_assertion_only_list_mutation_preserves_fingerprint")


def main():
    parser = argparse.ArgumentParser(description="compiler_attack_catalog tests")
    parser.add_argument("--skip-production", action="store_true",
                        help="Skip production validation tests")
    args = parser.parse_args()

    print("=" * 70)
    print("compiler_attack_catalog self-test (A1.3 compiler attack catalog)")
    print("=" * 70)
    print()

    # Catalog integrity tests (always run)
    integrity_tests = [
        test_denominator_exact_60,
        test_category_counts,
        test_relation_38_exact_coverage,
        test_mutation_changes_canonical_bytes,
        test_baseline_not_modified,
        test_each_mutation_is_semantically_different,
        test_validate_catalog_pass,
        test_validate_catalog_wrong_category,
        test_validate_catalog_duplicate_id,
        test_validate_catalog_missing_relation_fact,
        test_trust_projection_ids_and_codes,
        test_boundary_19_ids_and_codes,
        test_active_with_null_pins_code,
        test_relation_mutation_id_drift,
        test_semantic_registry_not_self_generated,
        test_no_authority_field_invention,
        # R01/R02 targeted tests (outside 60-attack catalog denominator)
        test_r01_baseline_link_no_errors,
        test_r01_remove_provider_entry_path,
        test_r01_remove_identity_entry_path,
        test_r01_add_legacy_path,
        test_r01_add_arbitrary_extra,
        test_r01_mutate_provider_entry_path_unchanged_assertion,
        test_r01_mutate_provider_identity_entry_path,
        test_r01_assertion_only_list_mutation_preserves_fingerprint,
    ]

    # Production semantic tests
    production_tests = [
        test_production_60_rejected,
        test_production_38_relation_semantic,
        test_production_trust_projection,
        test_production_boundary_19_raw_bytes,
        test_production_boundary_distinct_active_codes,
        test_production_abi_tck_provider_target,
    ]

    all_tests = integrity_tests + (production_tests if not args.skip_production else [])

    passed = 0
    failed = 0
    for test_fn in all_tests:
        try:
            test_fn()
            passed += 1
        except AssertionError as e:
            print(f"  FAIL  {test_fn.__name__}: {e}")
            failed += 1
        except Exception as e:
            print(f"  ERROR  {test_fn.__name__}: {type(e).__name__}: {e}")
            failed += 1

    print()
    print("=" * 70)
    if not args.skip_production:
        print(f"Catalog integrity: {len(integrity_tests)} tests")
        print(f"Production semantic: {len(production_tests)} tests")
    print(f"Total: {passed} passed, {failed} failed")
    if failed:
        print("FAILED")
        sys.exit(1)
    else:
        print("ALL PASS")
        sys.exit(0)


if __name__ == "__main__":
    main()
