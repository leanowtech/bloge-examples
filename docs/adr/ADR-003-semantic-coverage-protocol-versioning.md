# ADR-003: Semantic Coverage Protocol Versioning

## Header

- **Status:** Accepted
- **Date:** 2026-07-16
- **Deciders:** Resource Gateway architecture and testability maintainers

## Context

Stage 3 must promote branch, decision-rule, retry, fallback, timeout, and compensation observations
from informal trace interpretation into signed suite coverage. The current immutable artifacts are
already security-sensitive protocols:

- `bloge.testSuite.v1` is content fingerprinted and exact-revision addressed;
- `bloge.testSuiteRunEvidence.v1` is canonically fingerprinted;
- `bloge.testSuiteRunAttestation.v1` signs that aggregate fingerprint;
- persisted v1 checkpoints and terminal bundles are reserialized and reverified on read;
- the independent test-kit recomputes the same material offline.

Adding optional Java record fields to either v1 object changes its canonical JSON after
deserialization. Historical evidence that was signed before those fields existed would then fail
verification even when the new fields default to empty. This is a protocol break, not an additive
implementation detail.

Decision drivers, in order:

1. historical signatures and exact suite fingerprints must remain verifiable;
2. semantic policy and verdicts must be typed, schema-valid, and signed;
3. old and new server/test-kit releases need an explicit compatibility matrix;
4. a coverage gate must fail closed when a required semantic fact is unavailable;
5. migration must not silently reinterpret a reviewed v1 suite.

## Options Considered

### A. Add optional fields to the v1 records

Smallest code change, but canonical reserialization invalidates historical fingerprints and
attestations. Rejected.

### B. Store semantic policy and verdicts in existing `metadata`

This preserves the outer wire shape, but turns a correctness gate into an untyped reserved-map
convention. Generic consumers could ignore it, bounds and invariants become scattered, and the
domain model would lie about metadata being provenance rather than executable policy. Rejected.

### C. Create detached semantic reports referenced only by aggregate metadata

This can preserve v1, but a consumer must trust an unversioned reference convention and prove that
the aggregate coverage status actually committed to the report. It creates two signing and lifecycle
paths before there is evidence that separate retention is useful. Rejected as the target design.

### D. Introduce typed suite/evidence v2 with dual readers

Keep v1 Java and canonical shapes frozen. Add new records and schemas for typed semantic policy and
verdicts. Persist and attest each artifact according to its own schema version. Existing v1 remains
readable and verifiable; semantic promotion requires v2. Accepted.

## Comparison Summary

| Dimension | Optional v1 fields | Metadata convention | Detached report | Typed v2 |
|---|---|---|---|---|
| Historical signature safety | Broken | Preserved | Preserved | Preserved |
| Type/schema safety | Medium | Low | High | High |
| Consumer clarity | Misleading | Implicit | Complex join | Explicit |
| Migration cost | Low initially, high incident cost | Medium | High | Medium |
| Long-term entropy | High | Very high | Medium | Low |

## Decision

Semantic coverage will be introduced through explicit v2 artifacts, not by mutating v1:

1. Keep `TestSuite`, `CoveragePolicy`, `TestSuiteRunEvidence`, and `CoverageVerdict` v1 canonical
   projections frozen.
2. Add `bloge.testSuite.v2` with a typed `SemanticCoveragePolicy` containing stable requirement ids
   and typed branch, decision-rule, resilience, timeout, and compensation coordinates.
3. Add `bloge.testSuiteRunEvidence.v2` with required, observed, missing, and unavailable semantic
   facts. `SATISFIED` is impossible when a required fact is missing or cannot be derived from FULL
   signed child evidence.
4. Add v2 attestation material that binds the exact v2 aggregate fingerprint and ordered child
   closure. Do not reinterpret a v1 attestation as v2.
5. Store `schemaVersion` beside canonical JSON and dispatch fingerprint/verification through a
   versioned codec. Never fingerprint a historical v1 value by first converting it to v2.
6. Expose v1/v2 as explicit JSON Schema `oneOf` variants and capability versions. New semantic suite
   registration requires v2; existing v1 revisions remain immutable and executable under v1 rules.
7. Extend the independent test-kit with a typed versioned projection. A caller that requires semantic
   coverage must reject v1 as `SEMANTIC_COVERAGE_UNAVAILABLE`, not treat absent facts as empty success.

## Semantic Fact Identity

The v2 policy will use typed requirements with a stable `requirementId`. At minimum it must support:

| Kind | Required coordinate | Evidence source |
|---|---|---|
| `BRANCH_TRANSFERRED` / `BRANCH_SKIPPED` | from/to invocation sites | edge status |
| `DECISION_RULE` | site + output JSON Pointer + expected scalar | sanitized node output |
| `RETRY` | site + minimum attempt count | ordered attempt trace |
| `FALLBACK` | site | failed final delegate attempt plus successful node result |
| `TIMEOUT` | site + optional stable error code | node/attempt status |
| `COMPENSATION` | compensation invocation site | compensation node trace |

Decision-rule extraction must return `UNAVAILABLE`, not false, when sanitization removes the declared
path. Requirement ids and coordinates are part of the signed suite revision; observed values alone
can never create requirements.

## Consequences

Positive:

- old evidence remains independently verifiable;
- publish gates can distinguish unsatisfied from unobservable semantics;
- server, ANEKE, and test-kit share one typed contract;
- future mutation score or schema-boundary coverage can extend a new version deliberately.

Costs:

- server and client temporarily carry dual protocol readers;
- persistence, schema, capability, API, and test-kit matrices grow;
- v1 suites need an explicit new revision to adopt semantic policy.

Residual risks and mitigations:

- version-dispatch drift: one authoritative codec registry and cross-version golden vectors;
- accidental v1 canonical changes: byte-for-byte historical fixture tests in CI;
- large semantic policies: bounded requirement count and coordinate length;
- sanitized decision output: explicit unavailable facts and promotion blocking;
- old consumer false green: capability negotiation and v2-required gate policy.

## Why Not the Others

The optional-field option is rejected because cryptographic material cannot be “mostly backward
compatible.” The metadata option is rejected because correctness policy is a first-class domain
entity. The detached-report option is reserved only as a future storage optimization after v2
semantics are proven; it is not the initial protocol.

## Evolution Path

1. Freeze historical v1 canonical golden vectors and introduce the versioned codec boundary.
2. Add suite v2 semantic policy, registry validation, and dual-read persistence.
3. Add aggregate/attestation v2, fail-closed aggregator, test-kit verification, and ANEKE projection.
4. Make semantic v2 mandatory only for policies that claim semantic promotion; retain v1 read and
   diagnostic execution for the documented compatibility window.

## Thought Evolution

The initial temptation was to extend the existing `CoveragePolicy` and `CoverageVerdict` records.
The key lifecycle work made the hidden constraint visible: those values participate in signed,
offline-recomputed fingerprints. Once historical verification is treated as an invariant, a new
protocol generation is cheaper and safer than an apparently additive v1 edit.

## Follow-ups

- [x] preserve v1 concrete canonical records and verify v1 JSON/fingerprint round-trip;
- [x] implement versioned suite/evidence codec dispatch before adding semantic fields;
- [x] define bounded v2 JSON Schemas and negative generation-compatibility tests;
- [x] add branch/decision/retry/fallback/timeout/compensation aggregation tests from certifiable evidence;
- [x] add independent test-kit N/N-1 projection and fail-closed v1 semantic rejection;
- [x] update capability negotiation with explicit supported generations;
- [ ] add ANEKE N/N-1 consumer conformance and workbook projection;
- [ ] define deprecation telemetry and minimum compatibility window before any future v1 retirement.

Implementation and reproducible evidence are recorded in
[Stage 3 semantic coverage verification](../resource-gateway-execution-data-control-plane-stage3-semantic-coverage-verification.md).
