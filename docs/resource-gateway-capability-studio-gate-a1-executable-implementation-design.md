# Resource Gateway Capability Studio — Gate A1 Executable Implementation Design

**Status:** `A1_DESIGN_PASS`
**Effective:** 2026-08-23
**Scope:** Gate A1 design freeze; Step0 Baseline Preservation now permitted

---

## Objective

Produce a hermetic, review-gated, NON_RELEASE-isolated implementation of the Capability Studio
Gate A1 wire protocol. Artifacts survive migration from LEGACY_GATE_A_WIRE_V1 to TARGET A1
compiler with zero unplanned artifact claims and zero implicit re-interpretation of legacy wire
semantics.

---

## Principles

1. **No in-place reinterpretation.** Every LEGACY→TARGET transformation produces an explicit
   migration map record. Legacy bytes are never silently reinterpreted as TARGET types.
2. **Oracle isolation.** CompilerGoldenOracle and TrustBehaviorOracle are NON_RELEASE; they do not
   drive production verdicts. Production verdict is CI gate + signed review record.
3. **Fail-closed dependency authority.** Any `DRAFT_UNPINNED` dependency triggers CI gate rejection;
   no automatic passthrough.
4. **LEGACY oracle corpus immutable; TARGET compiler output is new content-addressed artifact.**
   LEGACY sealed golden in `docs/acceptance/…/compiled/` are permanent and byte-identical; no
   regeneration of LEGACY sealed artifacts. TARGET compiler produces fresh, independently content-
   addressed outputs — not regenerating the LEGACY golden corpus, not reusing the LEGACY `compiled/`.
5. **Schema as Step0 exit, not design prerequisite.** Target schema artifacts in
   `docs/schemas/resource-gateway-capability-studio-a1/` are created by Step0.

---

## Design Documents (00–04)

| Doc | File | Purpose |
|-----|------|---------|
| 00 | [00-normative-conventions.md](capability-studio-gate-a1-design/00-normative-conventions.md) | ENC/JCS/WireDigest/TypedFP/Merkle conventions |
| 01 | [01-source-package-and-compiler.md](capability-studio-gate-a1-design/01-source-package-and-compiler.md) | SourcePackage, Compiler, Merkle, Role Visibility, 13 target schemas |
| 02 | [02-evidence-receipt-and-ledger.md](capability-studio-gate-a1-design/02-evidence-receipt-and-ledger.md) | Evidence Catalog, ObservationReceipt, LedgerEntry, AcceptanceReceipt, T-001..T-032, A-RA1..A-RA4, A1..A31 |
| 03 | [03-hermetic-runtime-and-role-closure.md](capability-studio-gate-a1-design/03-hermetic-runtime-and-role-closure.md) | HermeticRuntime, Observer, Role Closure, A1_Hermetic_001..020 |
| 04 | [04-migration-oracle-and-acceptance.md](capability-studio-gate-a1-design/04-migration-oracle-and-acceptance.md) | Oracle Bundle, Legacy Ingestion, Migration Mapping, Phase Sequence |

---

## Target vs. LEGACY

| Aspect | LEGACY_GATE_A_WIRE_V1 | TARGET A1 |
|--------|----------------------|-----------|
| Compiler | `compile-protocol-authority.py` (998 B, damaged) | New compiler; TARGET outputs are content-addressed and enumerated by compiler-manifest-v1 |
| Validator | `validate-fixtures.py` stub (5,683 B) | New independent implementation; does NOT execute legacy pyc |
| Sealed golden | `docs/acceptance/…/compiled/` (permanent NON_RELEASE) | Not reused; TARGET emits independent content-addressed outputs |
| pyc | `validate-fixtures.cpython-314.pyc` (247,211 B, SHA-256 `d6dab90a…`) | Retained NON_RELEASE until conditions in 04 §1.2 met; not in release |
| Source package | `projections/` + `fixtures/` (old compiler output) | `docs/schemas/resource-gateway-capability-studio-a1/` (Step0 output) |
| Oracle | CompilerGoldenOracle + TrustBehaviorOracle | Unchanged; NON_RELEASE only |
| Dependency | — | `DRAFT_UNPINNED` (fail-closed gate) |

---

## Architecture Text Flow

```
SourcePackage (authored)
  → Compiler (TARGET, per 01; manifest schema produced in Step0)
      → CompilerManifest + TARGET content-addressed outputs
      → Evidence catalog entries (provenance)
  → ObservationReceipt (HermeticRuntime execution, 03)
  → LedgerEntry (immutable append-only, 02)
  → AcceptanceReceipt (gate verdict, 02)
  → [if revoked] RevocationRecord + role re-closure
```

TrustBehaviorOracle executes offline against the sealed corpus to confirm compatibility;
it does not gate production. Production gates are CI + signed review record.

---

## Immutable Decisions (A1_DESIGN_PASS sealed)

- LEGACY sealed golden in `docs/acceptance/…/compiled/` are permanent and byte-identical;
  no regeneration of LEGACY sealed artifacts.
- Oracle bundle is NON_RELEASE; never enters release artifact.
- Migration map format: `legacySchemaId / legacyRawDigest → targetSchemaId / targetWireDigest`
  with explicit `transformationVersion`; in-place reinterpretation prohibited.
- pyc artifact retained NON_RELEASE until all three conditions in 04 §1.2 are met.
- `docs/schemas/resource-gateway-capability-studio-a1/` target schema artifacts are Step0 outputs,
  not A1_DESIGN_PASS prerequisites.

---

## Implementation Sequence (aligned with 04 §4)

Prerequisite: `A1_DESIGN_PASS` (passed 2026-08-23)

### Step0 — Baseline Preservation + Source Authority

> Step0 scope per 04 §4: legacy-fragment baseline encapsulation + quarantine + 13 schemas + migration map.
> This is a single Step0 with two parallel workstreams.

| Field | Value |
|-------|-------|
| **Input** | A1_DESIGN_PASS record; `compile-protocol-authority.py` (998 B); `validate-fixtures.py` (5,683 B); pyc (247,211 B / SHA-256 `d6dab90a…`) |
| **Output** | (a) Digest-named copies in NON_RELEASE quarantine (`quarantine/fragments/{digest}/…`); (b) `docs/schemas/resource-gateway-capability-studio-a1/` — 13 schemas; (c) Migration map record工件 |
| **Gate** | (a) Digest-named copies confirmed on disk; audit log entry written; (b) 13 schema files schema-valid (Draft 2020-12); (c) Migration map工件 created |
| **Rollback** | Delete new quarantine copies and Step0 output artifacts; original artifact bytes on disk are not modified; original file paths unchanged |
| **Commit** | All three output artifacts present and verified |

13 schemas (01 §10.2):
- `normative-primitives-v1` — WireDigest 模式、closed enum、genesis-zero sentinel
- `source-unit-v1` — SourceUnit 结构（unitType/unitId/roleVisibility/content）
- `source-package-v1` — SourcePackage manifest（treeRoot、packageFP）
- `compiler-manifest-v1` — Compiler Manifest（packageFP/compilerVersion/products[]）
- `oracle-manifest-v1` — Oracle Manifest（oracleId/oracleType/releaseStatus）
- `attack-case-v1` — 已知攻击向量测试用例结构
- `evidence-catalog-entry-v1` — Evidence Catalog 条目（policy.READ_CATALOG_ONLY）
- `observation-receipt-v1` — ObservationReceipt payload/envelope 结构
- `acceptance-receipt-v1` — AcceptanceReceipt（invocationKeyFP/decisionInputFP/status）
- `ledger-entry-v1` — LedgerEntry（packageFP/sliceFP/frozenInputs/effectiveStatus）
- `revocation-record-v1` — RevocationRecord（raSignature/revocationPayloadFP）
- `hermetic-observation-v1` — Hermetic Observation（clock/random/network 全 false）
- `observer-failure-v1` — ObserverFailureReceipt payload/envelope（03 §9.1/§9.2）

### Step1 — Compiler

| Field | Value |
|-------|-------|
| **Input** | SourcePackage schemas (Step0 output); LEGACY sealed golden reference |
| **Output** | New `compile-protocol-authority.py` (functional replacement); TARGET compiler output |
| **Gate** | Compatibility projections byte-match LEGACY golden; TARGET-only artifacts independently validated against TARGET compiler manifest |
| **Rollback** | Delete new compiler output; original digest-named quarantine copies remain untouched; original bytes not modified |
| **Commit** | Oracle verification passed; new compiler active |

### Step2 — Evidence

| Field | Value |
|-------|-------|
| **Input** | Compiler output (Step1); TrustBehaviorOracle test corpus |
| **Output** | Evidence catalog entries with contentDigest per entry |
| **Gate** | All expectedOutcome entries pass; all expectedErrorCode entries produce correct codes |
| **Rollback** | Discard evidence catalog; no ledger entry written |
| **Commit** | Evidence catalog sealed; entries point to immutable compiler artifact |

### Step3 — Receipt / Ledger

| Field | Value |
|-------|-------|
| **Input** | Evidence catalog; ObservationReceipt from HermeticRuntime execution |
| **Output** | LedgerEntry (immutable append); AcceptanceReceipt |
| **Gate** | LedgerEntry SHA-256 matches expected digest; AcceptanceReceipt gate field = PASS |
| **Rollback** | LedgerEntry remains; AcceptanceReceipt marked REVOKED; new LedgerEntry for revocation |
| **Commit** | Ledger append-only; AcceptanceReceipt published |

### Step4 — Runtime

| Field | Value |
|-------|-------|
| **Input** | AcceptanceReceipt; HermeticRuntime per 03 |
| **Output** | Live execution log; revocation snapshot if needed |
| **Gate** | Runtime execution completes without unexpected exception; revocation path tested |
| **Rollback** | RevocationRecord written; role re-closure executed |
| **Commit** | Runtime log retained in NON_RELEASE; no production path modification |

### Step5 — Fresh Review / A1_IMPLEMENTATION_PASS

| Field | Value |
|-------|-------|
| **Input** | All step artifacts; signed review record |
| **Output** | Final review attestation; A1_IMPLEMENTATION_PASS gate record |
| **Gate** | P0 findings = 0; P1 findings = 0; A1_IMPLEMENTATION_PASS issued |
| **Rollback** | Review halted; all artifacts retained pending re-review |
| **Commit** | A1_IMPLEMENTATION_PASS record sealed; Step6 gate unlocked |

### Step6 — Oracle Excision

| Field | Value |
|-------|-------|
| **Input** | A1_IMPLEMENTATION_PASS record; all step artifacts |
| **Output** | Oracle artifacts excised to NON_RELEASE; replay/audit paths preserved |
| **Gate** | Replay path intact; audit trail continuous; no production path modification |
| **Rollback** | Restore oracle from NON_RELEASE archive; verify replay path |
| **Commit** | Excision record sealed; release artifact set final |

---

## Current Facts (As-Is Snapshot, 2026-08-23)

| Item | Value |
|------|-------|
| `compile-protocol-authority.py` | **998 bytes**, damaged, 无可信构建轨迹 |
| `validate-fixtures.py` | **5,683 bytes**, sealed in NON_RELEASE |
| `validate-fixtures.cpython-314.pyc` | **247,211 bytes**, SHA-256 `d6dab90a53ea9b353c1e6ebabca7660eb34324d57bf8526d9e0f7539901de280`, Python 3.14+ only |
| Dependency authority | `DRAFT_UNPINNED` — fail-closed on any unresolved draft dep |
| Broken/pending blockers | damaged compiler; pending conformance; DRAFT_UNPINNED — all fail-closed |
| Planned artifacts | None claimed; all schema artifacts are Step0 outputs |
| A1_DESIGN_PASS | **PASSED 2026-08-23** |
| Step0 status | **May begin**; not yet started; not yet passed |

---

## Gate Status

| Gate | Status | Blocks Step0? | Notes |
|------|--------|--------------|-------|
| Design Freeze | **CLEARED** | No | A1_DESIGN_PASS issued 2026-08-23 |
| A1_DESIGN_PASS | **PASSED** | No | 2026-08-23 |
| Damaged compiler | **BLOCKER** | No | Step0 does not execute the legacy compiler; it copies artifacts and creates schemas/migration map |
| Pending conformance | **BLOCKER** | No | Step0 does not admit production conformance |
| DRAFT_UNPINNED authority | **BLOCKER** | No | Step0 artifact isolation only; no conformance enforcement |
| Step0 exit | **PENDING** | Yes (Step1+) | quarantine + 13 schemas + migration map not yet created |
| A1_IMPLEMENTATION_PASS | **PENDING** | Yes (Step6+) | Requires Step5 pass first |
| Step6 Oracle Excision | **PENDING** | Yes (release) | Requires Step5 pass first |

Step0 gate is open. Broken/pending blockers do not block Step0 initiation; they block admission to Step1 and later steps.

---

## No Planned Artifact Claims

This document records sequencing and gate structure only. All schema artifact names, field
definitions, and wire formats are defined by the design documents (00–04) and realized by
Step0.
