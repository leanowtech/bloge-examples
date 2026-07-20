# Stage 3 Suite-Run Storage Integrity Verification

## 1. Problem closed

A suite checkpoint was previously fingerprinted and signed from one Java object, while callers could
later serialize the original object. Nested metadata aliases, a forged `VERIFIED` label, repository
substitution, or drift between searchable columns and `record_json` could therefore make the durable
aggregate differ from the object that was signed or requested.

This increment establishes one invariant:

> The canonical aggregate that is signed, returned by the repository, indexed, queried, projected,
> reconciled, and exported must be the same v1-v5 protocol value and storage envelope.

## 2. Implemented boundary

`TestSuiteRunEvidence` through `TestSuiteRunEvidenceV5` recursively freeze metadata using the shared
JSON-value freezer. `TestSuiteRunEvidenceProtocolCodec.canonicalSnapshot(...)` then performs an
exact-generation JSON round trip. `TestSuiteRunAttestationService.seal(...)` fingerprints, signs, and
immediately verifies that snapshot and returns it in `SealResult.evidence()`.

`TestSuiteRunRecordIntegrity` canonicalizes the whole record and verifies:

- cryptographic attestation validity rather than trusting `signatureStatus=VERIFIED`;
- exact evidence/attestation generation and `CHECKPOINT` versus `TERMINAL` scope;
- suite run, client request, request fingerprint, suite reference, start/completion, and retention;
- signed `tenantId`, `organizationId`, `projectId`, `environmentId`, `actorId`, and `classification`;
- blank running versus exact terminal aggregate fingerprint semantics;
- exact create/update receipts from replaceable repository adapters;
- complete run, client-request, and suite-revision lookup keys;
- abandoned candidates that are running, retained, and lease-expired at the sweep instant.

`DatabaseTestSuiteRunRepository` applies the same verifier before writes and after reads. It compares
`record_json` with the independently stored suite-run id, tenant, environment, client request, suite
id/revision, status, evidence fingerprint, creation time, and expiry time. Execution, mutation, query,
idempotency, and reconciliation services verify adapter results before business or authorization
decisions. Integrity exceptions and security events have stable payload-free messages.

## 3. Compatibility policy

- Newly written `VERIFIED` aggregates must pass current cryptographic verification.
- A signer outage may persist only terminal `EVIDENCE_INCOMPLETE + BLOCKED` material explicitly marked
  `VERIFICATION_UNAVAILABLE`; it cannot become passing or promotion-eligible evidence.
- Historical unsigned v1 records remain decodable for an explicit migration flow but are not eligible
  for create/update through the current boundary.
- Numeric values in untyped metadata are JSON-canonical values; consumers must compare number meaning,
  not a producer-specific Java wrapper type.

## 4. Negative matrix

| Attack or fault | Expected result |
| --- | --- |
| Mutate a nested list or arbitrary bean after `seal` | returned evidence remains unchanged and verifies |
| Copy a valid attestation onto changed aggregate JSON | write/read fails with payload-free integrity error |
| Mark forged material `VERIFIED` | cryptographic verification fails |
| Substitute tenant/environment/run/client/suite lookup result | service rejects before clearance or projection |
| Drift an indexed column from `record_json` | JDBC read fails closed |
| Return a different but individually valid write receipt | exact receipt comparison fails |
| Return an unexpired or retention-expired reconciliation candidate | candidate is counted failed and not reconciled |
| Write unsigned current evidence | persistence boundary rejects it |

## 5. Reproducible verification

Focused gate:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestSuiteRunRecordIntegrityTest,TestSuiteRunAttestationServiceTest,\
TestRuntimePersistenceTest,TestSuiteExecutionServiceTest,\
TestMutationSuiteExecutionServiceTest,TestSuiteRunReconciliationServiceTest test
```

Result: 65 tests, 0 failures, 0 errors, 0 skips.

Full project gate:

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

Result: 3070 tests, 0 failures, 0 errors, 2 conditional browser skips. The Spring Boot executable JAR
was rebuilt successfully. Total gate time: 06:23.

## 6. Residual trust boundary

This increment detects partial row tampering, mutable aliases, forged manifests, and adapter
substitution. It does not prove that a database authority unable to be observed externally cannot
rewrite both the aggregate and all local indexes, roll back an entire backup, or replace signing-key
configuration. External WORM retention, independently pinned transparency/witness checkpoints,
database backup rollback detection, and operating-system/key-provider controls remain required.
