# Stage 5 observation lifecycle v2 external-proof design

**Implemented non-production preview (2026-07-20): lifecycle v1 remains compatible while every
exported floor-retirement transition carries the exact external immutable-archive receipt set that
authorized local deletion. A consumer must be able to verify the ledger, retirement, archive,
receipt topology, retention policy, and all detached signatures without importing Resource Gateway
server classes or trusting the Gateway database as the external witness.**

## 1. Root problem

The write path now refuses to retire active compact observations until an external archive authority
returns a verified receipt set. That closes local deletion authority, but lifecycle v1 exports only
the retirement and same-database archive. A governance consumer therefore cannot distinguish:

1. a retirement that was externally archived before deletion;
2. a legacy retirement committed before external admission existed;
3. a database operator that fabricated or replaced the local receipt projection; or
4. a valid receipt signed by an authority outside the consumer's approved topology or policy.

Adding a Boolean such as `externallyArchived=true` would preserve the defect. It would only turn an
unverifiable database assertion into a signed Gateway assertion. The missing artifact is the exact
third-party proof plus caller-controlled trust policy.

## 2. Decision

Introduce a receipt-aware lifecycle response generation while leaving v1 byte and behavior
compatible:

- request generation remains `LifecyclePageRequest.v1`; cursor and snapshot semantics do not
  change;
- lifecycle page, outer attestation, and response advance to wire generation v2;
- a dedicated v2 endpoint avoids ambiguous body-based dispatch and accidental client downgrade;
- each v2 page carries the ordered v1 retirements and an equal-length ordered list of the exact
  persisted `ExternalArchiveReceiptSet.v1` records;
- the v2 page fingerprint covers both lists and every nested field;
- the v2 outer signature carries ordered archive references binding retirement identity,
  receipt-set identity, copy threshold, and receipt count;
- the independent test-kit requires an archive trust policy supplied outside the Gateway response.

The deliberate duplicate retirement inside each receipt set's challenge-bound request is retained.
Replacing it with a projection would no longer export the exact object that passed write admission.
Pages remain bounded to ten retirements and all compact observations are payload-free, so verifiable
fidelity is preferred over a smaller but weaker envelope.

## 3. Ownership boundaries

| Boundary | Owns | Must not claim |
|---|---|---|
| Lifecycle v1 service | suite authorization, classification clearance, snapshot-pinned retirement page, nested retirement verification | external durability |
| Lifecycle v2 assembler | exact receipt lookup, retirement/receipt pairing, complete v2 page closure and outer signature | authority trust chosen by the consumer |
| Database repository | immutable exact receipt projection and corruption detection | validity of an external signing key |
| Test-kit lifecycle verifier | observations, entries, floors, head, retirement chain, page identity, lifecycle signatures, continuation checkpoint | Gateway database truth without proofs |
| Test-kit archive verifier | request/receipt/set identities, topology, retention assertions, authority pins, receipt signatures | provider certification or physical media inspection |
| Governance consumer | accepted policy revisions, minimum copies, required retention horizon, authority/failure-domain/key pins | delegating trust policy back to Gateway output |
| Future production adapter | HTTPS identity, remote write/read-after-write, provider compliance mode, historical key lifecycle | publish-gate policy |

## 4. Wire protocol

### 4.1 Page v2

`bloge.testSuiteStabilityObservationLedgerLifecyclePage.v2` contains all v1 page fields and adds:

```text
externalArchiveReceiptSets[0..n-1]
```

For every index `i`:

```text
retirements[i]
  == externalArchiveReceiptSets[i].request.retirement
```

Equality is canonical complete-record equality, not only retirement-id equality. Both lists have the
same bounded size and generation order.

### 4.2 Outer archive reference

The v2 attestation replaces v1 `retirementRefs` with ordered `archiveRefs`:

```text
retirementGeneration
retirementId
retirementFingerprint
receiptSetId
receiptSetFingerprint
requiredCopies
receiptCount
```

The receipt-set fingerprint already covers the challenge-bound request and every full signed
receipt. Explicit threshold/count fields make policy drift and partial-set omission visible without
weakening canonical verification.

### 4.3 Endpoint

The test/staging preview adds:

```text
POST /api/testing/suites/{suiteId}/
     stability-observation-ledger-lifecycle-archive-pages
```

The existing lifecycle-pages endpoint continues to return v1. Both remain profile- and
feature-isolated behind `gateway.testing.stability-cross-retention-preview-enabled=true` and remain
absent in production.

## 5. Consumer-supplied archive trust policy

The Gateway response is evidence, not its own trust root. The test-kit verifier therefore requires
a policy supplied by CI, ANEKE, or another governance authority containing:

- exact trust domain and archive-set id;
- an allowlist of historical retention-policy fingerprints;
- minimum independent copy count;
- an absolute `requiredRetainUntil` horizon for the current decision;
- authority id to pinned failure domain mapping;
- authority id to one or more pinned Ed25519 public keys.

Every receipt in the exported set must be trusted and verified. Extra untrusted receipts are not
ignored because silently selecting a subset would verify material different from the signed
receipt-set fingerprint. Authority ids and failure domains are both unique.

The policy is intentionally not fetched from Resource Gateway. If the producer can choose both the
evidence and the trust policy, independent verification collapses into self-attestation.

## 6. Time semantics

Four different times must not be conflated:

| Time | Meaning | Verification rule |
|---|---|---|
| request `requestedAt/expiresAt` | short anti-replay admission window | local confirmation is inside this window |
| receipt `issuedAt/expiresAt` | short signature admission window | local confirmation is inside every receipt window |
| `storedAt` | external object commit time | no later than receipt issue; may predate a retried request for an existing immutable object |
| `retainUntil` | external immutable retention deadline | no earlier than request and caller-required decision horizon |

A receipt's short admission expiry does not invalidate historical proof after successful commit.
Verification proves that it was valid at `confirmedAt`. Whether the object must still exist today is
an explicit `requiredRetainUntil` policy decision, not an accidental comparison with wall-clock now.

The current v1 receipt-set constructor must also reject confirmation at or after any receipt expiry.
Without that check a correctly signed but already expired receipt could cross the local deletion
gate even though the request itself had not yet expired.

## 7. Mandatory invariants

1. Lifecycle v1 types, endpoint, schema constants, and verifier behavior remain unchanged.
2. V2 page and response identities are domain-separated by v2 schema versions.
3. Request fingerprint, page fingerprint, page id, receipt request/receipt/set fingerprints, object
   id, archive id, retirement id, floor/head fingerprints, and successor floors are recomputed.
4. V2 retirement and receipt-set counts are equal, ordered, bounded, and pairwise exact.
5. Every receipt set is canonically valid and refers to the page's exact retirement and archive.
6. Every local confirmation is before both request expiry and every receipt expiry.
7. Every authority id and failure domain is unique within a set and matches caller-pinned policy.
8. `requiredCopies` and actual receipt count meet both protocol and caller policy.
9. Every receipt policy fingerprint is caller-approved and every retention deadline reaches the
   caller-required horizon.
10. Every receipt key is pinned to its authority and valid for verification at `issuedAt`.
11. Every receipt signature, nested observation signature, retirement signature, and v2 page
    signature verifies independently.
12. A continuation checkpoint is emitted only after all lifecycle and archive layers pass.
13. Missing legacy receipts, malformed projections, unavailable keys, policy rejection, or any
    signature failure produces no trusted checkpoint.

## 8. Failure taxonomy

| Failure | Outcome | Checkpoint |
|---|---|---|
| v2 page missing a receipt set | `INVALID / EXTERNAL_ARCHIVE_RECEIPT_SET_MISSING` | none |
| retirement/set canonical mismatch | `INVALID / EXTERNAL_ARCHIVE_BINDING_INVALID` | none |
| request, receipt, set, object, or outer fingerprint tamper | `INVALID` with layer-specific code | none |
| unknown authority, domain mismatch, policy revision rejected, insufficient copies, short retention | `POLICY_REJECTED` | none |
| pinned archive key unavailable | `KEY_UNAVAILABLE` | none |
| receipt signature invalid | `INVALID / EXTERNAL_ARCHIVE_RECEIPT_SIGNATURE_INVALID` | none |
| lifecycle signature/key/checkpoint failure | existing lifecycle outcome and reason | none |
| all layers verified | `VERIFIED` with counts and continuation checkpoint | emitted |

Transport and service errors continue to use bounded integration problem codes. No error body emits
business payload, receipt signature bytes, or trust-key material.

## 9. Compatibility and rollout

1. Add strict v2 definitions to the authoritative testing-control-plane Schema; do not mutate v1.
2. Add v2 server records, integrity service, attestation service, assembler, and isolated endpoint.
3. Add a strict independent test-kit projection, caller-owned trust policy, verifier, and client
   methods.
4. Keep capability advertisement false and production wiring absent.
5. Exercise v1 and v2 side by side; a v1 consumer sees no field or behavior change.
6. Only after the test/staging HTTPS adapter is backed by certified production providers,
   historical archive trust publication, legal hold,
   backup/DR continuity, and witnessed non-equivocation exist may product capability be reconsidered.

There is no synthetic migration for legacy retirements. A v2 read crossing a retirement without an
authentic receipt fails closed. Operators must restore evidence from the real authority or keep that
scope on lifecycle v1; manufacturing a receipt would destroy the meaning of v2.

## 10. Verification matrix

Server tests must prove:

- v1 response remains stable;
- a canonical v2 page pairs every retirement with its exact persisted set;
- missing, reordered, duplicated, rebound, expired-at-confirmation, and structurally tampered sets
  are rejected;
- repository read failure and signing outage do not emit a partial v2 response;
- profile/feature isolation and the dedicated endpoint remain fail closed;
- strict Schema accepts complete v2 and rejects missing/unknown fields.

Independent test-kit tests must prove:

- direct-key and pinned lifecycle-key-set verification both work;
- request/receipt/set/object/page identities are recomputed without server classes;
- retirement, archive, observations, receipt signatures, and outer signatures all verify;
- authority, failure-domain, copy threshold, policy revision, and retention-horizon rejection are
  distinct from key unavailability and cryptographic invalidity;
- a one-page and multi-page chain emits a checkpoint only after external proofs pass;
- an attacker who recomputes all Gateway-local fingerprints and the outer signature still fails
  when an external receipt is rebound or forged;
- client helpers call only the v2 endpoint and never silently downgrade to v1.

## 11. Implementation evidence

The receipt-aware server gate executes 42 tests with zero failures, errors, or skips. It covers v2
page/attestation identities, exact receipt pairing, missing/reordered/rebound proofs, request and
receipt expiry, repository/signing failure, strict Schema, controller errors, and profile/feature
isolation. The full Resource Gateway `clean verify` executes 2,885 tests with zero failures, zero
errors, two existing conditional skips, real-browser regressions, and executable JAR repackaging.

The independent test-kit `clean verify` executes 228 tests with zero failures, errors, or skips and
passes ordinary/shaded JAR, packaged authoritative Schema, and strict public Javadoc gates. Its v2
tests use separate lifecycle and archive key pairs and prove direct/pinned lifecycle verification,
multi-page checkpoint continuity, caller-policy rejection, external-key unavailability, and receipt
forgery/rebinding even after every Gateway-local hash and outer signature has been regenerated.

## 12. Residual boundary after this increment

Receipt-aware lifecycle v2 closes independent proof portability, not physical storage deployment.
The remaining ordered gates are:

1. certify the implemented strict HTTPS multi-authority adapter's provider identity, account/region
   independence, and historical trust publication before production wiring;
2. external orphan inventory and reconciliation with no early-delete authority;
3. legal hold and governed erasure state machines across active, WORM, backup, and replica copies;
4. backup purge evidence and disaster-recovery restore continuity drills;
5. externally witnessed lifecycle-generation non-equivocation and rollback detection;
6. leased scheduler, readiness/SLO, bounded backlog, and production capability gates.

Until those gates are evidenced, Resource Gateway can prove that a signed external authority
acknowledged each deletion, but it must not advertise enterprise-grade permanent retention or global
non-equivocation.
