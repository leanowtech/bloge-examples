# Execution Data Control Plane Stage 3 Suite Attestation Verification

> Verification date: 2026-07-16
>
> Scope: signed suite checkpoints, terminal aggregate closure, portable payload-free evidence, and
> consumer-side Ed25519 verification.
> Result: implemented and fail-closed. A subsequent Stage 3 increment added signed, externally
> pinned key lifecycle snapshots; semantic coverage, payload replay attachment, transparency proof,
> and ANEKE projection remain outside this increment.

## 1. Closed risk

Signed child runs alone did not prove that a consumer received the intended suite, request, case
order, and aggregate verdict. An attacker or corrupt store could substitute a child reference,
reorder cases, alter aggregate coverage, or recover an abandoned checkpoint from modified progress
while every original child signature remained valid. A producer-side `VERIFIED` string also could
not establish trust for an offline CI or governance consumer.

This increment introduces two separate trust boundaries:

1. Resource Gateway signs and verifies every suite checkpoint and terminal aggregate before a
   persistence or response boundary.
2. The independent test-kit fetches the exact public key and verifies the exported terminal bundle
   without depending on Resource Gateway implementation classes or trusting producer claims.

## 2. Protocols and endpoints

| Contract | Version | Purpose |
|---|---|---|
| Suite execution response | `bloge.testSuiteExecutionResponse.v2` | Aggregate evidence plus checkpoint/terminal attestation |
| Suite attestation | `bloge.testSuiteRunAttestation.v1` | Domain-separated signature over suite/request/aggregate/child closure |
| Portable evidence bundle | `bloge.testSuiteEvidenceBundle.v1` | Terminal aggregate and attestation with `payloadPolicy=OMITTED` |
| Verification key | `toolStudio.resourceGateway.evidenceVerificationKey.v1` | Exact Ed25519 public key and lifecycle state |
| Verification key set | `toolStudio.resourceGateway.evidenceVerificationKeySet.v1` | Atomic key history, external trust pin, and signing-time policy |

The machine-authoritative shapes live in
`docs/schemas/resource-gateway-testing/testing-control-plane-v1.schema.json`.

| Operation | Endpoint | Purpose header |
|---|---|---|
| Execute immutable suite | `POST /api/testing/suites/{suiteId}/executions` | `TEST_EXECUTION` |
| Read checkpoint/terminal | `GET /api/testing/suite-executions/{suiteRunId}` | `TEST_EXECUTION` |
| Export terminal bundle | `GET /api/testing/suite-executions/{suiteRunId}/evidence-bundle` | `TEST_EXECUTION` |
| Resolve exact public key | `GET /api/integration/evidence-keys/{keyId}` | `TEST_EXECUTION` |
| Resolve atomic key policy | `GET /api/integration/evidence-keys` | `TEST_EXECUTION` |

Capabilities advertise both suite response v1 and v2 for migration, plus the attestation, bundle,
key, and endpoint feature identifiers. New execution responses are v2. V1 is historical unsigned
data and cannot become trusted by client inference.

## 3. Signed material and invariants

The attestation signature material is:

```text
canonicalSha256({
  schemaVersion,
  scope,
  suiteRunId,
  suiteRef,
  requestFingerprint,
  aggregateEvidenceFingerprint,
  childEvidenceRefs[],
  signedAt
})
```

Ed25519 signs the ASCII `sha256:<64-lowercase-hex>` result. Canonicalization recursively sorts
object properties, preserves array order, emits ISO-8601 timestamps, and hashes the exact UTF-8 JSON
bytes. This is a distinct domain from child-run evidence signatures.

The following invariants are enforced:

| Invariant | Enforcement |
|---|---|
| No case executes before durable trust exists | Initial `RUNNING` checkpoint is signed before the first repository write or child run |
| Progress cannot be silently changed | Every checkpoint is re-fingerprinted, re-signed, and written under the owner lease/version fence |
| Terminal verdict closes exact child identity | Ordered `{caseId, runId, evidenceFingerprint}` references are covered by `TERMINAL` signature |
| Store cannot accept unsigned normal state | Repository rejects unsigned/structurally inconsistent checkpoint and terminal records |
| Read cannot bless altered storage | Find and idempotent-read paths recompute and verify aggregate, request, scope, identity, and closure |
| Crash recovery cannot launder corruption | Reconciliation verifies the signed checkpoint before producing a newly signed fail-closed terminal |
| Signer failure cannot produce promotable evidence | Execution becomes `EVIDENCE_INCOMPLETE`, promotion is `BLOCKED`, and no trusted bundle is exported |
| Request metadata does not leak into evidence | Aggregate metadata stores only `requestMetadataFingerprint` |
| Portable export is terminal and payload-free | Bundle requires a verified `TERMINAL` attestation and declares `payloadPolicy=OMITTED` |

The one allowed unsigned constructor exists only for source and wire migration. Current persistence
and export paths reject it. Historical records must be re-executed under a current signer; they are
not back-signed because doing so would manufacture contemporaneous trust for old mutable facts.

## 4. Consumer verification

The test-kit provides:

```java
TestSuiteEvidenceBundle findSuiteEvidenceBundle(String suiteRunId);
EvidenceVerificationKey findEvidenceVerificationKey(String keyId);
TestSuiteEvidenceVerifier.VerificationResult verifySuiteEvidence(String suiteRunId);
EvidenceVerificationKeySet findEvidenceVerificationKeySet();
TestSuiteEvidenceVerifier.VerificationResult verifySuiteEvidence(
        String suiteRunId, String trustedKeySetFingerprint);
```

`TestSuiteEvidenceVerifier` independently performs:

1. terminal attestation and `payloadPolicy=OMITTED` checks;
2. externally pinned key-set material, freshness, and active-key attestation checks;
3. unique key/event identity and complete lifecycle-state coherence checks;
4. exact evidence key membership and validity-window checks;
5. activation, retirement, disablement, and prospective/retroactive revocation at signing time;
6. aggregate evidence fingerprint recomputation;
7. ordered aggregate case/run closure comparison;
8. bundle fingerprint recomputation over `{payloadPolicy, attestation, evidence}`;
9. signature-material fingerprint recomputation and Ed25519 verification.

The bounded outcomes are `VERIFIED`, `INVALID`, `KEY_UNAVAILABLE`, and `POLICY_REJECTED`. Results
contain only suite run id, key id, and stable reason code. A missing key is distinct from invalid
cryptographic material, so CI can retry availability failures without treating tampering as a
transient outage.

The exact-key overload remains a compatibility path. It cannot prove that separately fetched keys
came from one rotation generation, and it cannot distinguish prospective from retroactive
revocation. Release gates must use the key-set overload with a pin obtained outside the Resource
Gateway response. Full lifecycle invariants and threat analysis are recorded in
[Stage 3 key lifecycle verification](resource-gateway-execution-data-control-plane-stage3-key-lifecycle-verification.md).

## 5. Negative verification matrix

| Threat or failure | Expected result |
|---|---|
| Aggregate status, coverage, promotion, or metadata changed | Aggregate fingerprint invalid; server read/export rejects and offline verifier returns `INVALID` |
| Child order or reference changed | Attestation signature or closure invalid |
| Request identity or suite revision changed | Signature material invalid |
| Signature bytes changed | Ed25519 verification invalid |
| Wrong path-bound key id | Key-id mismatch, `INVALID` |
| Unsupported algorithm or disallowed key state | `POLICY_REJECTED` |
| Key absent/provider unavailable | `KEY_UNAVAILABLE`; never treated as verified |
| Key-set pin mismatch, stale snapshot, or incomplete lifecycle | `POLICY_REJECTED`; never falls back to exact-key trust |
| Evidence signed after retirement/disable/revocation | signing-time policy rejection with a stable reason |
| Retroactive compromise invalidates older evidence | rejected from the declared `invalidFrom` time |
| Initial signer unavailable | No child execution and no ordinary checkpoint write |
| Terminal signer unavailable | Fail-closed terminal `EVIDENCE_INCOMPLETE + BLOCKED` only |
| Unsigned checkpoint submitted directly to JDBC repository | Persistence rejects before serialization |
| Tampered abandoned checkpoint | Reconciliation rejects; no trusted recovered terminal |
| Non-terminal bundle request | Export rejects; checkpoint is never a release fact |
| Producer says `VERIFIED` but material is wrong | Consumer recomputation rejects producer claim |

## 6. Implementation map

| Responsibility | Implementation |
|---|---|
| Attestation domain | `TestSuiteRunAttestation` |
| Server signing and verification | `TestSuiteRunAttestationService` |
| Runner checkpoint/terminal integration | `TestSuiteExecutionService` |
| Recovery verification | `TestSuiteRunReconciliationService` |
| Persistence boundary | `DatabaseTestSuiteRunRepository` |
| Portable export | `TestSuiteEvidenceBundle`, `TestExecutionController` |
| Consumer protocol types | `TestSuiteRunAttestation`, `TestSuiteEvidenceBundle`, `EvidenceVerificationKey`, `EvidenceVerificationKeySet` in test-kit |
| Offline verifier | `TestSuiteEvidenceVerifier` |
| Authoritative wire validation | `testing-control-plane-v1.schema.json` |

## 7. Reproduction gates

Run from the repository root:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestingControlProtocolSchemaTest,TestabilityCapabilitiesTest,TestExecutionControllerTest,TestSuiteExecutionServiceTest,TestSuiteRunAttestationServiceTest,TestSuiteRunReconciliationServiceTest,TestRuntimePersistenceTest,TestRuntimeApplicationIntegrationTest \
  test

mvn -f resource-gateway-test-kit/pom.xml clean verify
mvn -f resource-gateway-examples/pom.xml clean verify
```

The focused server matrix passes 49 tests with zero failures or errors. The independent test-kit
passes all 42 tests with zero failures or errors. The full Resource Gateway `clean verify` gate
passes 1806 tests with zero failures or errors and 34 conditional skips, then packages the Spring
Boot JAR successfully. The focused server matrix covers signature domains, child closure order, aggregate/request tamper,
signer failure before child execution, persistence rejection, reconciliation, endpoint wiring,
capabilities, JSON Schema, and a real Spring Boot HTTP round trip. The test-kit matrix covers exact
paths and purpose headers, typed decoding, key retrieval, valid offline verification, aggregate and
bundle tamper, child reorder, wrong/missing/disallowed keys, and invalid signatures. `clean verify`
also runs JavaDoc/doclint and packaging gates.

## 8. Explicit non-claims and next hard problems

This increment proves integrity and provenance of the payload-free aggregate. It does not prove that
an operator is semantically correct, that fixture fidelity is sufficient, or that a release is
approved. It also does not provide:

- child payload replay attachments or decryption authorization;
- transparency-log inclusion/consistency proofs or witness quorum;
- semantic branch/rule/fallback/compensation coverage;
- ANEKE workbook projection, owner approval, or publish-gate verdict;
- cross-region evidence replication or independent timestamp authority.

Key compromise and revocation semantics are now formalized by the separate pinned key-set protocol.
The next trust-chain work is transparency/inclusion proof and trusted pin distribution. Semantic
coverage and ANEKE projection should consume these stable attestation boundaries instead of inventing
a second signature format.
