# Stage 5 observation-floor lifecycle protocol design

**Implementation status (2026-07-20): strict lifecycle request/page/attestation Schema, an
authorized default-disabled HTTP adapter, database-snapshot pagination, server-side retirement and
page verification, an independent test-kit verifier, and write-side external archive-receipt
admission are implemented. Lifecycle v1 intentionally remains local-chain-only; the dedicated v2
endpoint now exports exact receipt sets and has an independent caller-policy verifier. The
advertised `crossRetentionSuiteStabilityTrend` capability remains false because a production WORM
adapter, historical archive trust publication, orphan reconciliation, legal hold and erasure,
backup purge, disaster-recovery continuity, and witnessed non-equivocation are not closed.**

## 1. Strongest judgment

Signed floor retirement solved safe local deletion but exposed a protocol dead end: the existing
cross-retention preview requires its first request to use `afterSequence=0`. Once retention advances
the active floor beyond sequence one, that request correctly fails closed, but a remote consumer has
no trusted way to discover the new floor or prove how it was reached.

Changing the range endpoint to accept an arbitrary floor would hide the missing history instead of
proving it. Returning only the latest floor would let a producer omit, reorder, or fork retirement
generations. Reusing the mutable head as a pagination token would also allow a consumer to join pages
from different database snapshots.

The implemented remedy is a separate floor-lifecycle protocol. It proves the ordered transition
from generation zero to one pinned current floor, then hands that verified floor and head to the
existing compact-range protocol. This closes discovery and local proof continuity. It deliberately
does not turn the same-database archive into an externally durable or non-equivocating ledger.

## 2. Responsibility and trust boundaries

| Boundary | Owns | Does not prove |
| --- | --- | --- |
| Database repository | Exact-suite lock, complete generation reads, indexed/JSON integrity, one snapshot floor/head | Signature trust or external durability |
| Lifecycle service | Immutable-suite authorization, classification clearance, stale-pin handling, retirement verification, page sealing | Enterprise legal-hold or WORM policy |
| Retirement signer | Exact retirement evidence and archive transition signature | That all consumers saw the same generation |
| Lifecycle signer | Exact page, snapshot pins, and ordered retirement refs | That an omitted alternative history does not exist |
| Independent test-kit | Strict Schema, canonical identities, all nested signatures, transitions, and cross-page checkpoint | Producer database completeness beyond signed material |
| External archive authority | Pre-delete WORM acknowledgement and receipt signatures | Public lifecycle receipt export or governance release decision |
| External governance plane | Future receipt/key pins, hold/erasure policy, witness quorum, release decision | Resource Gateway business execution |

The server and test-kit intentionally implement canonical verification independently. The test-kit
does not depend on Resource Gateway server classes, Spring Boot, or retired full stability records.

## 3. Protocol model

### 3.1 Request

`bloge.testSuiteStabilityObservationLedgerLifecyclePageRequest.v1` contains:

- exact immutable `suiteId + revision + fingerprint`;
- exclusive `afterRetirementGeneration` cursor;
- `maximumRetirements` bounded to `1..10`;
- expected current-floor and head fingerprints.

Generation zero is the only legal first-page cursor and requires both expected fingerprints to be
blank. Every non-zero continuation requires both pins. The request fingerprint covers the complete
request, including the page budget and pins.

### 3.2 Page

`bloge.testSuiteStabilityObservationLedgerLifecyclePage.v1` contains:

- exact request and request fingerprint;
- payload-free exact-suite scope fingerprint;
- `startingFloor` at the exclusive cursor;
- zero to ten contiguous signed retirement records;
- `terminalFloor` derived by applying this page;
- snapshot-wide `currentFloor` and `head`;
- `hasMore`, database `observedAt`, and complete page fingerprint.

An empty page is legal only when the cursor already equals the current generation. `hasMore` is not
a producer hint: it must equal `terminalGeneration < currentGeneration`. A terminal page must close
exactly on `currentFloor`.

### 3.3 Page attestation

`bloge.testSuiteStabilityObservationLedgerLifecycleAttestation.v1` signs a separate domain over:

- deterministic lifecycle page id;
- request, page, scope, starting-floor, terminal-floor, current-floor, and head fingerprints;
- ordered `(generation, retirementId, retirementFingerprint)` references;
- signing time.

The page id is content addressed by page schema version, request fingerprint, and page fingerprint.
The outer signature cannot make a forged inner retirement valid; the service and independent client
verify every retirement and compact observation separately.

### 3.4 Response

`bloge.testSuiteStabilityObservationLedgerLifecyclePageResponse.v1` carries the page id, duplicated
page fingerprint, complete page, and detached attestation. All new and reused definitions in the
authoritative Schema use `additionalProperties=false`. No fixture, business input/output, credential,
actor token, diagnostic body, or tenant identifier is included.

## 4. Formal transition invariants

For page cursor floor `F0`, retirements `R1..Rn`, terminal floor `Ft`, snapshot current floor `Fc`,
and head `H`, acceptance requires:

1. `F0.generation = request.afterRetirementGeneration`.
2. `Ri.generation = F0.generation + i` with no duplicates or gaps.
3. `Ri.previousFloor = successor(Ri-1)`, where `R1.previousFloor = F0`.
4. Every archive is contiguous, begins at its previous floor, and includes the immediate surviving
   successor.
5. Every archived compact observation has valid canonical evidence, attestation, entry fingerprint,
   deterministic observation id, and Ed25519 signature.
6. Every retirement has valid archive id/fingerprint, evidence id/fingerprint, attestation
   fingerprint/signature, and whole-record fingerprint.
7. `Ft = successor(Rn)`; for an empty page, `Ft = F0`.
8. `hasMore = Ft.generation < Fc.generation`.
9. `!hasMore` implies `Ft = Fc`.
10. `H.coverageFrom = Fc.coverageFrom` and `H.latestSequence >= Fc.floorSequence`.
11. Request continuation pins equal the page's `Fc` and `H` fingerprints.
12. `observedAt` is not before the current floor or head database update time.

`successor(R)` is a public pure derivation shared by repository and server integrity checks. The
independent test-kit reimplements that derivation rather than trusting a server-projected successor.

## 5. Database snapshot algorithm

The repository performs one read under the exact-suite database lock:

1. resolve and verify the generation-zero rollout floor;
2. read and verify the current floor and committed head;
3. reject a cursor beyond the current generation;
4. verify retirement/archive count, minimum, and maximum generations against the current floor;
5. verify that the latest retirement and archive close exactly on the current floor;
6. read the exact predecessor at the continuation cursor and its archive;
7. read at most `maximumRetirements + 1` generations after the cursor;
8. reject missing, duplicate, out-of-order, or indexed-to-JSON inconsistent lifecycle records;
9. verify the predecessor-derived starting floor and every in-page successor transition;
10. derive `hasMore` from the extra record while returning only the bounded page;
11. freeze one database `observedAt`, current floor, and head into the page fingerprint.

The server proves one bounded local closure: complete generation cardinality, the current tail, the
cursor predecessor, and every transition in the returned page. It does not replay all historical
generations for every continuation. The independent consumer starts at generation zero and carries
a verified checkpoint (`terminalFloor`, pinned current floor, and pinned head) across pages; the
combination proves the complete prefix without making page latency grow linearly with total history.
The maximum public page is ten retirements and each archive contains at most one hundred retired
entries, bounding one response to at most 1,010 independently verified compact observations. A
future large-history generation needs authenticated checkpoints or a Merkle accumulator before
raising these limits.

## 6. Authorization and failure semantics

The HTTP adapter is present only when all conditions hold:

- Spring profile is `test` or `staging`, never `production`;
- `gateway.testing.stability-cross-retention-preview-enabled=true`;
- workload identity is complete and purpose is `TEST_EXECUTION` or `TEST_REPLAY`;
- tenant, organization, project, environment, exact suite fingerprint, and classification clearance
  pass the existing stability-trend read authorization boundary.

| Condition | Stable outcome |
| --- | --- |
| Invalid path/request/cursor | `400` lifecycle request or cursor code |
| Suite or ledger absent | `404` without materializing a synthetic floor |
| Suite fingerprint drift | `409` exact-suite conflict |
| Current floor or head differs from continuation pins | `409 RG.TEST.STABILITY_LIFECYCLE_SNAPSHOT_CHANGED` |
| Page, archive, transition, or retirement signature invalid | `409`, never a partial page |
| Repository corruption or signer/key outage | `503`, no unsigned fallback |
| Wrong purpose, environment, or clearance | `403` before lifecycle material is returned |
| Production profile or default flag | route and beans are absent |

The service verifies stale snapshot pins before sealing, verifies whole-page structure, then verifies
every retirement signature, then signs and immediately verifies the outer page. It never degrades to
producer counters or unsigned JSON.

## 7. Independent consumer algorithm

Release-oriented consumers use a key-set fingerprint pinned outside Resource Gateway output:

```java
TestSuiteStabilityObservationLedgerLifecycleRequest request =
        TestSuiteStabilityObservationLedgerLifecycleRequest.firstPage(
                suiteId, revision, suiteFingerprint, 10);
TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier.LifecycleCheckpoint checkpoint = null;
TestSuiteStabilityObservationLedgerLifecyclePage finalPage;

while (true) {
    finalPage = client.readSuiteStabilityObservationLedgerLifecyclePage(request);
    var result = verifier.verify(finalPage, checkpoint, trustedKeySet, trustedKeySetFingerprint);
    if (!result.verified()) {
        throw new IllegalStateException(result.reasonCode());
    }
    checkpoint = result.checkpoint();
    if (checkpoint.complete()) {
        break;
    }
    request = request.continueAfter(finalPage);
}
```

The consumer must create a continuation only after verifying the prior page. The checkpoint binds
exact suite, scope, snapshot current floor/head, terminal generation, and terminal floor fingerprint.
It rejects a completed checkpoint, a page from another suite or snapshot, a skipped generation, or a
continuation whose request pins do not match.

The verifier performs these layers independently:

1. strict authoritative Schema and request/response identity binding;
2. page id plus request/page/floor/head canonical fingerprints;
3. every compact observation identity, evidence/attestation/entry fingerprint, signature, and
   signing-time key policy;
4. every archive identity/fingerprint and exact entry closure;
5. every retirement identity, evidence/attestation/whole-record fingerprint and signature;
6. deterministic successor-floor derivation and page terminal/current closure;
7. outer ordered retirement-reference closure and lifecycle-page signature;
8. cross-page checkpoint continuity.

Canonical JSON comparison is used for transition objects. This is important across a real HTTP
round trip, where a numerically identical JSON integer may be represented by a different in-memory
number-node width without changing its protocol meaning.

After the final lifecycle page is verified, its current floor provides the safe range cursor:
`afterSequence = currentFloor.floorSequence - 1`, with the verified current head fingerprint as the
range pin. The existing cross-retention trend verifier then proves active compact entries. Lifecycle
verification and range verification are separate evidence objects and both are required.

## 8. Concurrency, replay, and restore analysis

| Race or fault | Protection | Residual boundary |
| --- | --- | --- |
| Retirement between pages | first-page current floor pin; continuation returns `409` | caller restarts at generation zero |
| Append between pages | first-page head pin; continuation returns `409` | caller restarts to obtain a new head |
| Exact page retry | deterministic request/page/page-id under unchanged snapshot | signing time makes a newly sealed response distinct if snapshot read is repeated |
| Missing middle generation | generation cardinality plus bounded page continuity | fails `503` as repository corruption |
| Valid but disconnected predecessor | exact predecessor-derived cursor floor | fails `503` before page signing |
| Reordered or duplicated generation | generation and previous-floor closure | fails before page signing |
| Outer page re-signed over forged inner data | independent inner observation and retirement verification | trusted key compromise remains an external incident |
| Database restore rolls floor backward | local fingerprints may still verify | external witness/checkpoint is required to detect rollback |
| Producer serves two internally valid forks | one database authority prevents local concurrent fork | cross-authority split view needs witnessed non-equivocation |
| Lifecycle v1 omits external receipt sets | write side still requires a persisted exact receipt set | use the dedicated v2 endpoint and caller-pinned receipt verifier when the decision requires external proof |

## 9. Verification coverage

Executable tests cover:

- generation-zero empty closure and a real two-retirement, two-page lifecycle;
- exact current floor/head pinning and positive continuation checkpoint advancement;
- missing generation, invalid cursor, stale pins, wrong suite/path, purpose, environment, and
  classification clearance;
- canonical page and deterministic page-id tampering;
- bad compact-observation, retirement, and outer page signatures;
- archive, retirement ref, successor-floor, terminal-floor, and checkpoint divergence;
- unavailable keys, wrong external key-set pin, signing-time policy rejection, and unknown fields;
- HTTP serialization of integer coordinates without in-memory numeric-width false negatives;
- default-disabled, test/staging-only composition and production bean/route isolation.

Current full gates: Resource Gateway `clean verify` executes 2,885 tests with zero failures, zero errors,
and two existing conditional skips, including real-browser flows and executable JAR repackaging.
The independent test-kit `clean verify` executes 228 tests with zero failures, errors, or skips and
also passes ordinary/shaded JAR, authoritative-Schema packaging, and strict public Javadoc checks.
Receipt-aware v2 specifics and its separate 42-test server gate are documented in the
[external-proof design](resource-gateway-execution-data-control-plane-stage5-observation-lifecycle-v2-external-proof-design.md).

## 10. Deliberately unclaimed and next gates

This increment closes public local floor discovery and independent local-chain verification. It does
not close the following industrial requirements:

1. strict HTTPS multi-authority WORM adapter, certified failure-domain independence, and staging
   required wiring;
2. external orphan inventory and reconciliation without early-delete authority;
3. legal-hold precedence, authorized hold release, erasure proof, and backup purge evidence;
4. externally witnessed generation checkpoints, gossip, and rollback/fork/split-view detection;
5. multi-region restore continuity and disaster-recovery conformance tests;
6. managed key lifecycle and compromise response tested across archived signing generations;
7. database-leased retirement scheduling, backlog/freshness SLO, readiness, and bounded repair;
8. externally pinned lifecycle checkpoints retained independently of Resource Gateway;
9. a scalable authenticated accumulator before lifecycle histories exceed bounded linear verification;
10. cross-version producer/consumer conformance and independent implementation certification.

Until those gates close, the endpoint remains a default-disabled test/staging preview, production
wiring remains absent, and `crossRetentionSuiteStabilityTrend` remains false. The honest product
claim is: Resource Gateway can export and independently verify its local signed floor-retirement
lifecycle and recorded external acknowledgements; it cannot yet prove physical enterprise-grade
historical permanence or global non-equivocation.
