# Stage 4 Worker Quarantine Request-Index Rolling Upgrade Verification

## Purpose

The keyed request index introduced a deliberate binary incompatibility: the new Resource Gateway
can read legacy SHA-256 tombstones, while the previous binary cannot derive or find a v2 HMAC
tombstone. Enabling keyed writes while even one previous binary can still serve maintenance traffic
can therefore resurrect a retained request identity on that old replica.

This increment turns that hidden deployment assumption into a closed, observable three-stage
protocol. It controls new writes, validates every live tombstone generation before readiness, and
publishes the exact per-replica mode through the integration capability probe.

## Closed State Machine

`WorkerQuarantineRequestIndexMode` accepts exactly these values:

| Mode | New writes | Readiness invariant | Exact-access behavior |
| --- | --- | --- | --- |
| `LEGACY_READ_WRITE` | v1 unkeyed SHA-256 | zero live v2 rows | reads v1; can read a v2 row created after readiness so a controlled new-binary configuration rollout does not fail, but never migrates v1 |
| `DUAL_READ_KEYED_WRITE` | v2 keyed HMAC | every live v2 key is present; v1 is allowed | reads active/old-key v2 and v1; exact v1 or old-key hits are CAS re-keyed to the active v2 generation |
| `KEYED_ONLY` | v2 keyed HMAC | zero live v1 rows and every live v2 key is present | reads v2; an exact v1 row appearing after readiness fails closed instead of being silently accepted |

Unknown, blank, or aliased modes are rejected during application assembly. `LEGACY_READ_WRITE`
also rejects readiness when a live v2 row already exists, so an operator cannot claim previous-
binary rollback safety after keyed writes have begun. `KEYED_ONLY` rejects readiness while any live
legacy reservation remains.

The default local `test` mode is `DUAL_READ_KEYED_WRITE`. `staging` has no mode default and cannot
start through the packaged launcher without an explicit canonical value.

## N/N-1 Rollout Runbook

1. Deploy binary N to every new replica with `LEGACY_READ_WRITE`. Binary N and N-1 both continue
   writing and finding v1 tombstones.
2. Address every deployment-platform instance directly and request a challenge-bound signed
   replica proof targeting `DUAL_READ_KEYED_WRITE`. Require the expected instance, process-start,
   artifact, scope, protocol, legacy mode, empty blocker set, and live inventory. An N-1 process
   cannot publish this proof and must not remain in the platform inventory.
3. Only after the deployment authority proves that every serving instance is binary N, roll the
   fleet to `DUAL_READ_KEYED_WRITE`. From the first v2 write onward, rollback to N-1 is prohibited.
   Binary-N replicas still running the legacy mode can read v2 rows created during this configuration
   rollout, but an old application binary cannot.
4. Keep `DUAL_READ_KEYED_WRITE` until the live inventory has zero `record_version=1` rows. Legacy
   rows contain no raw request ID, so they can migrate only on an exact retry or expire at their
   configured tombstone deadline. Long-lived, untouched rows may intentionally keep the fleet in
   dual mode for the full retention window.
5. Roll the fleet to `KEYED_ONLY`. Every restarting replica independently refuses readiness if a
   legacy row remains. Require the exact keyed-only capability value on every serving instance.

Use this payload-free inventory before steps 3 and 5:

```sql
SELECT record_version, request_key_id, COUNT(*) AS live_rows, MAX(expires_at) AS last_expiry
FROM rg_test_durable_worker_quarantine_request_tombstones
WHERE expires_at > CURRENT_TIMESTAMP
GROUP BY record_version, request_key_id
ORDER BY record_version, request_key_id;
```

Do not shorten tombstone retention merely to reach `KEYED_ONLY`: doing so shortens the destructive
request replay fence. `DUAL_READ_KEYED_WRITE` is a supported steady state while legacy rows age out.

## Capability Contract

The existing `capabilities.v1.testability` shape is intentionally unchanged so strict old adapters
remain compatible. When the isolated testing runtime is absent, all mode flags are false. When
present, the probe reports exactly one true value among:

- `durableWorkerQuarantineRequestIndexLegacyReadWrite`;
- `durableWorkerQuarantineRequestIndexDualReadKeyedWrite`;
- `durableWorkerQuarantineRequestIndexKeyedOnly`.

`stagedDurableWorkerQuarantineRequestIndexUpgrade=true` means this binary implements the staged
protocol. `keyedDurableWorkerQuarantineRequestIndex=true` means it understands the keyed format; it
does not by itself mean that this replica currently writes keyed rows. Deployment gates must inspect
the exact mode and every serving instance, not one load-balanced response.

## Configuration

| Property | Environment variable | Profile behavior |
| --- | --- | --- |
| `gateway.testing.durable.worker-quarantines.request-key-protection.write-mode` | `RG_TEST_WORKER_QUARANTINE_REQUEST_INDEX_WRITE_MODE` | defaults to `DUAL_READ_KEYED_WRITE` in local `test`; required and closed-valued in `staging` |
| `gateway.testing.durable.worker-quarantines.request-key-protection.active-key-id` | `RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_ACTIVE_KEY_ID` | local demonstration key in `test`; required in `staging` |
| `gateway.testing.durable.worker-quarantines.request-key-protection.key-ring` | `RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_RING` | local demonstration ring in `test`; required in `staging` |
| `gateway.testing.durable.worker-quarantines.request-index-rollout.instance-id` | `RG_RESOURCE_GATEWAY_INSTANCE_ID` | deployment inventory identity; required in `staging` |
| `gateway.testing.durable.worker-quarantines.request-index-rollout.artifact-fingerprint` | `RG_RESOURCE_GATEWAY_ARTIFACT_FINGERPRINT` | immutable artifact/image SHA-256; required in `staging` |
| `gateway.testing.durable.worker-quarantines.request-index-rollout.proof-ttl-seconds` | `RG_TEST_WORKER_QUARANTINE_REQUEST_INDEX_PROOF_TTL_SECONDS` | `120`, bounded to `5..300` seconds |

The write mode is not a secret. It belongs in deployment policy, while both request-index roots
belong in the secret manager. A direct Spring Boot launch and the packaged launcher enforce the same
closed Java vocabulary; the launcher additionally rejects a missing or non-canonical staging value
before building or starting the service.

## Counterexample Matrix

| Counterexample | Required result |
| --- | --- |
| N is deployed beside N-1 in legacy mode | both binaries continue to create old-readable v1 indexes |
| A live v2 row exists before a legacy-mode replica starts | readiness fails; previous-binary compatibility cannot be claimed |
| A v2 row appears after an already-ready binary-N legacy replica | the N replica can classify the exact retry without rewriting v1 rows |
| A live v1 row exists before keyed-only startup | readiness fails with a mode-specific error |
| A v1 row appears after keyed-only readiness | exact access fails closed rather than accepting or migrating it |
| Dual mode reads an exact v1 row | retry classification is preserved and the locked row is CAS migrated to active v2 |
| Dual mode completes migration | keyed-only readiness succeeds without deleting a live replay fence |
| A mode is blank or outside the closed vocabulary | application assembly fails without echoing the untrusted value |
| Production profile is active | testing runtime is absent and all three mode flags are false |

## Verification Gate

The focused gate covers the closed vocabulary, mode matrix, old lookup formula, the complete
legacy-to-dual-to-keyed transition, both readiness vetoes, capability exclusivity, Spring profile
binding, application assembly, and existing request-index rotation/retention behavior:

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml \
  -Dtest=WorkerQuarantineRequestIndexModeTest,WorkerQuarantineRequestKeyProtectorTest,DatabaseDurableWorkerQuarantineControlPlaneTest,TestabilityCapabilitiesTest,TestRuntimeProfileIsolationTest,TestRuntimeApplicationIntegrationTest,BuiltInTestSuiteCatalogMaterializationIntegrationTest test
```

The release gate remains Resource Gateway `clean verify`, independent test-kit `clean verify`, shell
syntax validation, launcher missing/invalid-mode checks, and packaged JAR inspection.

On 2026-07-17, the focused gate executed 59 tests with 0 failures, 0 errors, and 0 skips. Resource
Gateway `clean verify` executed 2,246 tests with 0 failures, 0 errors, and 2 existing conditional
skips, then built the executable JAR. Independent test-kit `clean verify` executed 63 tests with 0
failures, 0 errors, and 0 skips, including public Javadoc and shaded-JAR verification. `bash -n`,
the staging launcher's missing/invalid-mode checks, and packaged-JAR inspection also passed; the JAR
contains the mode enum plus both `application-test.yml` and `application-staging.yml`.

## Signed Per-Replica Proof

`POST /api/testing/durable-state/worker-quarantines/request-index/replica-proofs` issues one
short-lived Ed25519 proof for an immediate transition to `DUAL_READ_KEYED_WRITE` or `KEYED_ONLY`.
The signed material binds the deployment challenge, identity-derived scope fingerprint, stable
instance id, process-start UUID, immutable artifact fingerprint, Resource Gateway protocol version,
current and target modes, DB-clock live generation inventory, closed blockers, and expiry. The
endpoint requires `test` or `staging`, exact purpose `TEST_RUNTIME_MAINTENANCE`, the configured
operator group and clearance, and complete project/region identity.

The proof remains signed when a transition is blocked. `CURRENT_MODE_NOT_PREDECESSOR`,
`LIVE_KEYED_ROWS_PRESENT`, and `LIVE_LEGACY_ROWS_PRESENT` are closed policy facts, not transport
errors. Inventory contains counts, expiries, and non-secret key-generation ids only; it excludes
request ids, tenants, scopes, and payloads. Full protocol and counterexamples are in the
[replica-proof verification](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-request-index-replica-proof-verification.md).

The independent test-kit now consumes those proofs with a caller-owned exact instance set, expected
scope/artifact/protocol/target, maximum cohort spread, and externally pinned complete key set. It
rejects missing, duplicate, unexpected, stale, mixed-mode, blocked, malformed, or badly signed
cohorts. This closes deterministic aggregation; it does not manufacture serving inventory.

## Honest Boundary

The mode is enforced and signed by each new binary, but the proof is not a database-coordinated
fleet barrier or service-discovery inventory. Resource Gateway cannot prove that an unmanaged,
unregistered, partitioned, or stale N-1 process no longer exists. The deployment platform must
enumerate every serving instance and gate step 3; one load-balanced proof is insufficient.

Likewise, a misconfigured N replica can start in dual mode while N-1 still serves. The protocol makes
that configuration observable and gives the orchestrator a safe sequence; it cannot force an old
binary that predates the protocol to honor a new database flag. The configured artifact fingerprint
is also a deployment assertion rather than a self-measured image digest, so the gate must bind it to
its independently trusted artifact inventory. Serving-inventory completeness, direct-routing proof,
multi-region propagation proof, non-H2 dialect qualification, and rollback-drill certification
remain later industrialization work.
