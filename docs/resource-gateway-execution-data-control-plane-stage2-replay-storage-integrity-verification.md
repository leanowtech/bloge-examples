# Stage 2 Governed Replay Vault Storage Integrity Verification

## Scope

This increment closes the local storage trust gap between governed replay capture and F4 execution.
The earlier replay boundary revalidated available descriptor/value fingerprints in the service, but
the repository could still retain caller aliases, accept a self-reported fingerprint, return another
valid record, or let indexed columns drift from `descriptor_json`. After value expiry there was no
independent commitment left to detect descriptor, scope, provenance, or tombstone-state mutation.

The implemented boundary covers:

- `StoredReplayPayload` JSON-container ownership;
- available-value fingerprint creation and verification;
- create receipt identity/content equality;
- tenant/environment/payload-id/revision lookup binding;
- JDBC descriptor/index/envelope projection binding;
- payload-free lifecycle commitments for `AVAILABLE`, `EXPIRED`, and `PURGED`;
- read-time and scheduled expiry compare-and-set transitions;
- legacy-row upgrade before normal repository reads;
- payload-free service diagnostics and security audit for alternate repositories.

## Canonical Integrity Model

`ReplayPayloadIntegrity` is the single fingerprint and canonicalization authority. It performs an
exact `StoredReplayPayload` JSON round trip so arbitrary Java beans become independently owned JSON,
then enforces exact protocol generation, closed lifecycle state, complete source lineage, bounded
classification/redaction/gap facts, retention ordering, and state/value consistency.

Two different commitments have intentionally different jobs:

| Commitment | Material | Survives value deletion | Purpose |
| --- | --- | --- | --- |
| `ReplayPayloadDescriptor.fingerprint` | descriptor with blank self-fingerprint plus canonical value | no recomputation after deletion | proves the available replay value |
| database `record_fingerprint` | scope, descriptor, state, payload-available bit, stored time and actor; value omitted | yes | proves immutable envelope and tombstone lifecycle |

An available row must verify both commitments. A tombstone must contain no value and must verify the
second commitment. The second commitment cannot substitute for the first: changing an available
value while keeping its descriptor still fails value verification.

## Repository And Lifecycle Boundary

`DatabaseReplayPayloadRepository` now writes only a verified canonical snapshot and binds these
indexed columns back to it on every read:

1. tenant id;
2. environment id;
3. replay payload id;
4. revision;
5. descriptor fingerprint;
6. classification;
7. state;
8. expiry.

The row's `record_fingerprint` additionally binds storage provenance and lifecycle state. Read-time
expiry and the bounded retention sweep first verify the complete available row, derive the exact
payload-free successor, then atomically set `EXPIRED`, erase `payload_json`, and replace the
commitment only when the old commitment and database deadline still match. A concurrent or tampered
row cannot be converted into an apparently valid tombstone.

The service repeats create-receipt and full lookup-key verification. This is deliberate: a custom
`ReplayPayloadRepository` implementation cannot bypass the JDBC guarantees by returning a valid
record from another scope or revision. Such substitution emits `REPLAY_PAYLOAD_INTEGRITY_INVALID`
and returns `409 RG.TEST.REPLAY_INTEGRITY_INVALID`; payload values and source secrets are omitted.

## Upgrade Semantics

Startup adds `record_fingerprint` when upgrading the legacy table and migrates blank rows in bounded
1,000-row pages before the repository serves reads. Available legacy rows are accepted only after
their descriptor/value fingerprint recomputes exactly. Historical tombstones no longer contain the
value needed for that proof, so migration verifies their canonical structure and indexed projection,
then treats that exact descriptor/envelope as the upgrade baseline.

This is an honest compatibility boundary, not retroactive authentication. A database authority that
changed a tombstone before this binary first ran cannot be detected from value-free legacy material.
External signing, WORM retention, witnessed database rollback detection, and backup erasure remain
separate Stage 3/5 controls.

## Failure Semantics

| Condition | Result |
| --- | --- |
| forged available value fingerprint | reject before insert |
| mutable caller container or bean | detach before write/receipt |
| valid create receipt with different identity/content | integrity rejection plus security event |
| cross-tenant/environment/id/revision lookup replacement | integrity rejection plus security event |
| descriptor JSON or indexed-column drift | repository integrity failure before clearance/value access |
| changed record commitment | repository integrity failure |
| state/value contradiction | repository integrity failure |
| corrupt available legacy row | startup migration fails closed |
| canonical legacy tombstone | establish explicit value-free upgrade baseline |

## Verification

Focused replay matrix:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestRuntimeProfileIsolationTest,ReplayPayloadRefTest,ReplayPayloadIntegrityTest,\
DatabaseReplayPayloadRepositoryTest,TestReplayPayloadServiceTest,TestExecutionControllerTest,\
TestExecutionApiServiceTest,TestSuiteExecutionServiceTest,ExecutionControlCompilerTest,\
TestRunServiceTest,TestingControlProtocolSchemaTest,TestingDomainProtocolTest,\
TestabilityCapabilitiesTest,TestRuntimeApplicationIntegrationTest test
```

Result: 176 tests, 0 failures, 0 errors, 0 skipped. The matrix includes eight repository attacks,
four direct integrity-contract tests, malicious third-party repository substitution, natural
database-clock expiry, legacy available/tombstone migration, controller/schema/capability parity,
compiler/runtime execution, suite replay, profile isolation, and real Spring assembly.

Full project gate:

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

Result: 3,080 tests, 0 failures, 0 errors, 2 conditional skips. The configured real-browser
authoring tests completed and Maven successfully repackaged the Spring Boot executable JAR. Total
gate time was 6 minutes 40 seconds.

## Remaining Trust Boundary

The local commitments detect partial mutation, adapter substitution, and TOCTOU. They do not protect
against an authority that can rewrite the complete row and recompute every hash, roll the database
and backup back together, or compromise the application process. Independent signed/WORM anchoring,
witnessed rollback/fork detection, and backup lifecycle proof remain required for that threat model.
