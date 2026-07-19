# Resource Gateway Testing Control Plane API

> Status: Stage 2 public control plane, protocol `bloge.testing.v1`
>
> Runtime profiles: `test`, `staging` only
>
> Production invariant: ordinary run APIs reject fixture/control fields before DTO deserialization

Machine-readable schema bundle:
[testing-control-plane-v1.schema.json](schemas/resource-gateway-testing/testing-control-plane-v1.schema.json).
It defines every public payload: graph/operator target descriptors, fixture and test-suite
registration/stored revisions, built-in graph-catalog materialization, graph/operator and
immutable-suite execution requests, common and aggregate responses, effective plan, and evidence.

## 1. What This API Is

The testing control plane lets a verified caller freeze a graph or operator binding, inject
deterministic operator/resource fixtures, execute the real DAG or a one-node micro graph on an
isolated short-lived BLOGE engine, and retain sanitized evidence. It is an engineering protocol,
not a `testMode` switch on production execution.

The trust transition is explicit:

1. `IntegrationRequestAuthenticator` verifies the bearer workload and `X-Purpose`.
2. `TestExecutionApiService` accepts only identities whose trusted environment is `test` or `staging`.
3. The endpoint mints `GRAPH_CONTRACT_TEST` or `OPERATOR_UNIT_TEST`; request content cannot mint an
   authorized purpose.
4. The graph, operator bindings, and a conservative snapshot of all resource descriptors are frozen.
5. `ExecutionControlCompiler` resolves every selector and rejects zero-match, ambiguity, stale target,
   unsafe external REAL/SPY, and fallback-to-real plans before graph execution.
6. The independent runtime atomically acquires its own test-capacity permit, then a new engine instance
   executes without production cache, quota, circuit breaker, durable state, listener, or context-carrier
   instances.
7. Evidence is bounded and redacted before it is written to the independent test-runtime database.

## 2. Start And Stop

The visual demo starts with the `test` profile by default, which assembles `/api/testing/**` and uses
a separate H2/Hikari pool for fixtures, immutable test suites, child test runs, recoverable suite-run
checkpoints, and test security events:

```bash
./scripts/start-visual-canvas-demo.sh --open
./scripts/visual-canvas-demo.sh status
./scripts/stop-visual-canvas-demo.sh
```

Choose a profile explicitly when needed:

```bash
./scripts/start-visual-canvas-demo.sh --profile test
./scripts/start-visual-canvas-demo.sh --profile production
```

`staging` has no committed claim-token or request-index key. Inject two independent 32-byte roots
from the deployment secret manager before startup; the launcher rejects a missing configuration
immediately:

```bash
export RG_TEST_WORKER_QUARANTINE_TOKEN_ACTIVE_KEY_ID='staging-2026-07'
export RG_TEST_WORKER_QUARANTINE_TOKEN_KEY_RING='staging-2026-07=<base64-encoded-32-byte-key>'
export RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_ACTIVE_KEY_ID='request-index-2026-07'
export RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_RING='request-index-2026-07=<different-base64-encoded-32-byte-key>'
export RG_TEST_WORKER_QUARANTINE_REQUEST_INDEX_WRITE_MODE='DUAL_READ_KEYED_WRITE'
export RG_RESOURCE_GATEWAY_INSTANCE_ID='rg-staging-0'
export RG_RESOURCE_GATEWAY_ARTIFACT_FINGERPRINT='sha256:<64-lowercase-hex-digest>'
export RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_TRUST_DOMAIN='enterprise-change-governance'
export RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_POLICY_FINGERPRINTS='sha256:<64-lowercase-hex-policy-digest>'
export RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_SIGNATURE_THRESHOLD='2'
export RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_AUTHORITY_KEYS_JSON='[{"authorityId":"change-board-a","keyId":"ed25519-a-2026-07","publicKeyBase64":"<X.509-Ed25519-public-key-a>","notBefore":"2026-07-01T00:00:00Z","expiresAt":"2027-07-01T00:00:00Z","enabled":true,"revoked":false},{"authorityId":"risk-board-b","keyId":"ed25519-b-2026-07","publicKeyBase64":"<X.509-Ed25519-public-key-b>","notBefore":"2026-07-01T00:00:00Z","expiresAt":"2027-07-01T00:00:00Z","enabled":true,"revoked":false}]'
./scripts/start-visual-canvas-demo.sh --profile staging
```

Do not use the placeholders literally, reuse the local `test` keys, or share one root between the two
rings. The change-authorization JSON contains public verification keys only; signing private keys
remain in the independent governance authority. Its threshold cannot exceed the number of distinct
configured authorities. The launcher checks presence and basic shape, then application startup
performs strict key, policy, threshold, and validity validation. Rotation runbooks are in the
[claim-token protection verification](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-claim-token-protection-verification.md)
and [request-index protection verification](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-request-index-protection-verification.md).
For the first N/N-1 rollout, start N in `LEGACY_READ_WRITE`, prove every serving instance is N,
then move to `DUAL_READ_KEYED_WRITE`; enter `KEYED_ONLY` only after the live v1 inventory is zero.
See the [request-index rolling-upgrade verification](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-request-index-upgrade-verification.md).
The signed discard-authorization protocol, canonical scope/subject preimages, exact replay behavior,
and static-key rotation boundary are specified in the
[change-authorization trust verification](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-change-authorization-trust-verification.md).

`production` intentionally has no `TestExecutionController`, fixture/suite repository,
child/suite-run repository, or testability capability marker. The capability probe reports
`testability.executionEndpointEnabled=false`; all three request-index mode feature flags are false
in that profile.

Direct Maven startup:

```bash
mvn -f resource-gateway-examples/pom.xml spring-boot:run \
  -Dspring-boot.run.profiles=test
```

Independent-store settings:

| Property | Environment variable | Default |
| --- | --- | --- |
| `gateway.testing.store.jdbc-url` | `RG_TEST_STORE_JDBC_URL` | `jdbc:h2:file:./data/resource-gateway-test-runtime;AUTO_SERVER=TRUE` |
| `gateway.testing.store.username` | `RG_TEST_STORE_USERNAME` | `sa` |
| `gateway.testing.store.password` | `RG_TEST_STORE_PASSWORD` | empty |
| `gateway.testing.store.maximum-pool-size` | `RG_TEST_STORE_MAXIMUM_POOL_SIZE` | `4` |
| `gateway.testing.store.retention-days` | `RG_TEST_STORE_RETENTION_DAYS` | `30` |
| `gateway.testing.admission.policy-generation` | `RG_TEST_ADMISSION_POLICY_GENERATION` | `1` |
| `gateway.testing.admission.tenant-max-active` | `RG_TEST_ADMISSION_TENANT_MAX_ACTIVE` | `16` |
| `gateway.testing.admission.suite-max-active` | `RG_TEST_ADMISSION_SUITE_MAX_ACTIVE` | `2` |
| `gateway.testing.admission.operator-max-active` | `RG_TEST_ADMISSION_OPERATOR_MAX_ACTIVE` | `8` |
| `gateway.testing.admission.dependency-max-active` | `RG_TEST_ADMISSION_DEPENDENCY_MAX_ACTIVE` | `4` |
| `gateway.testing.admission.lease-duration-seconds` | `RG_TEST_ADMISSION_LEASE_SECONDS` | `30` |
| `gateway.testing.admission.heartbeat-interval-seconds` | `RG_TEST_ADMISSION_HEARTBEAT_SECONDS` | `5` |
| `gateway.testing.admission.instance-id` | `RG_TEST_ADMISSION_INSTANCE_ID` | generated process identity |
| `gateway.testing.admission.cleanup-interval-ms` | `RG_TEST_ADMISSION_CLEANUP_INTERVAL_MS` | `60000` |
| `gateway.testing.admission.cleanup-batch-size` | `RG_TEST_ADMISSION_CLEANUP_BATCH_SIZE` | `1000` |
| `gateway.testing.stability-runs.instance-id` | `RG_TEST_STABILITY_INSTANCE_ID` | generated process identity |
| `gateway.testing.stability-runs.lease-duration-seconds` | `RG_TEST_STABILITY_LEASE_SECONDS` | `30` |
| `gateway.testing.stability-runs.heartbeat-interval-seconds` | `RG_TEST_STABILITY_HEARTBEAT_SECONDS` | `5` |
| `gateway.testing.stability-runs.lease-cleanup-interval-ms` | `RG_TEST_STABILITY_LEASE_CLEANUP_INTERVAL_MS` | `15000` |
| `gateway.testing.stability-runs.lease-cleanup-batch-size` | `RG_TEST_STABILITY_LEASE_CLEANUP_BATCH_SIZE` | `1000` |
| `gateway.testing.stability-jobs.api.retry-after-seconds` | `RG_TEST_STABILITY_JOB_API_RETRY_AFTER_SECONDS` | `5` |
| `gateway.testing.stability-jobs.authority.http.enabled` | `RG_TEST_STABILITY_JOB_AUTHORITY_HTTP_ENABLED` | `false` |
| `gateway.testing.stability-jobs.authority.http.base-uri` | `RG_TEST_STABILITY_JOB_AUTHORITY_HTTP_BASE_URI` | empty; required when enabled |
| `gateway.testing.stability-jobs.authority.http.expected-authority-id` | `RG_TEST_STABILITY_JOB_AUTHORITY_ID` | empty; required when enabled |
| `gateway.testing.stability-jobs.authority.http.request-timeout-ms` | `RG_TEST_STABILITY_JOB_AUTHORITY_TIMEOUT_MS` | `3000` |
| `gateway.testing.stability-jobs.authority.http.maximum-decision-lifetime-seconds` | `RG_TEST_STABILITY_JOB_AUTHORITY_MAX_LIFETIME_SECONDS` | `60` |
| `gateway.testing.stability-jobs.authority.http.clock-skew-seconds` | `RG_TEST_STABILITY_JOB_AUTHORITY_CLOCK_SKEW_SECONDS` | `5` |
| `gateway.testing.stability-jobs.authority.http.minimum-remaining-validity-ms` | `RG_TEST_STABILITY_JOB_AUTHORITY_MIN_REMAINING_MS` | `100` |
| `gateway.testing.stability-jobs.authority.http.allow-insecure-loopback` | `RG_TEST_STABILITY_JOB_AUTHORITY_ALLOW_INSECURE_LOOPBACK` | `false`; local tests only |
| `gateway.testing.stability-jobs.authority.http.authority-keys-json` | `RG_TEST_STABILITY_JOB_AUTHORITY_KEYS_JSON` | `[]`; required public Ed25519 keys when enabled |
| `gateway.testing.stability-jobs.authority.http.jwks.enabled` | `RG_TEST_STABILITY_JOB_AUTHORITY_JWKS_ENABLED` | `false`; selects built-in dynamic trust |
| `gateway.testing.stability-jobs.authority.http.jwks.uri` | `RG_TEST_STABILITY_JOB_AUTHORITY_JWKS_URI` | empty; HTTPS required in dynamic mode |
| `gateway.testing.stability-jobs.authority.http.jwks.refresh-interval-seconds` | `RG_TEST_STABILITY_JOB_AUTHORITY_JWKS_REFRESH_SECONDS` | `30` |
| `gateway.testing.stability-jobs.authority.http.jwks.unknown-key-refresh-interval-seconds` | `RG_TEST_STABILITY_JOB_AUTHORITY_JWKS_UNKNOWN_KEY_REFRESH_SECONDS` | `5` |
| `gateway.testing.stability-jobs.authority.http.jwks.request-timeout-ms` | `RG_TEST_STABILITY_JOB_AUTHORITY_JWKS_TIMEOUT_MS` | `3000` |
| `gateway.testing.stability-jobs.authority.http.jwks.maximum-snapshot-age-seconds` | `RG_TEST_STABILITY_JOB_AUTHORITY_JWKS_MAXIMUM_AGE_SECONDS` | `60`; at least refresh + timeout |
| `gateway.testing.stability-jobs.authority.http.jwks.allow-insecure-loopback` | `RG_TEST_STABILITY_JOB_AUTHORITY_JWKS_ALLOW_INSECURE_LOOPBACK` | `false`; local tests only |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.enabled` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_ENABLED` | `false`; exact cross-replica gate |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.scope-id` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_SCOPE_ID` | empty; stable across deployment generations |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.cohort-id` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_ID` | empty; immutable deployment generation |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.instance-id` | `RG_RESOURCE_GATEWAY_INSTANCE_ID` | exact serving slot; required in staging |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.artifact-fingerprint` | `RG_RESOURCE_GATEWAY_ARTIFACT_FINGERPRINT` | canonical `sha256:<lowercase-hex>` |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.expected-instance-ids` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_EXPECTED_INSTANCE_IDS` | local exact set in test mode; optional equality assertion in signed mode |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.heartbeat-interval-seconds` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_HEARTBEAT_SECONDS` | `10`; 1..300 |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.lease-duration-seconds` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_LEASE_SECONDS` | `30`; 3..900 and at least three heartbeats |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.record-retention-seconds` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_RETENTION_SECONDS` | `86400`; 3600..2592000 and at least the lease |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.enabled` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_SIGNED_INVENTORY_ENABLED` | `false`; required when a staging cohort is enabled |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.required` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_SIGNED_INVENTORY_REQUIRED` | `false` in test, `true` in staging |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.trust-domain` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_TRUST_DOMAIN` | exact independent deployment trust domain |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.accepted-policy-fingerprints` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_POLICY_FINGERPRINTS` | comma-separated 1..32 canonical SHA-256 values |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.signature-threshold` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_SIGNATURE_THRESHOLD` | distinct authority M-of-N threshold, 1..32 |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.authority-keys-json` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_AUTHORITY_KEYS_JSON` | public Ed25519 keys only; strict JSON array |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.inventory-json` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_SIGNED_INVENTORY_JSON` | strict static envelope v1; test fallback only and forbidden with remote mode |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.enabled` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_ENABLED` | `false`; required for a staging cohort |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.required` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_REQUIRED` | `false` in test, `true` in staging |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.uri` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_URI` | strict publication v1 endpoint; HTTPS required |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.refresh-interval-seconds` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_REFRESH_SECONDS` | `30`; 1..3600 |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.request-timeout-ms` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_TIMEOUT_MS` | `3000`; 100..30000 |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.maximum-snapshot-age-seconds` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_MAXIMUM_AGE_SECONDS` | `60`; 2..86400 and at least refresh plus timeout |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.allow-insecure-loopback` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_REMOTE_ALLOW_INSECURE_LOOPBACK` | `false`; local tests only |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.witness-domain` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_WITNESS_DOMAIN` | exact trust domain independent from deployment inventory trust |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.witness-signature-threshold` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_WITNESS_SIGNATURE_THRESHOLD` | distinct witness M-of-N threshold, 1..32 |
| `gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.witness-authority-keys-json` | `RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_INVENTORY_WITNESS_AUTHORITY_KEYS_JSON` | strict public Ed25519 witness keys; must not overlap deployment authorities |
| `gateway.testing.replay-payloads.maximum-retention-days` | `RG_TEST_REPLAY_MAX_RETENTION_DAYS` | `30` |
| `gateway.testing.replay-payloads.sweep-interval-ms` | `RG_TEST_REPLAY_SWEEP_INTERVAL_MS` | `60000` |
| `gateway.testing.replay-payloads.sweep-batch-size` | `RG_TEST_REPLAY_SWEEP_BATCH_SIZE` | `100` |
| `gateway.testing.durable.projection-findings.required-group` | `RG_TEST_PROJECTION_FINDING_REQUIRED_GROUP` | `resource-gateway-test-runtime-operators` |
| `gateway.testing.durable.projection-findings.required-clearance` | `RG_TEST_PROJECTION_FINDING_REQUIRED_CLEARANCE` | `RESTRICTED` |
| `gateway.testing.durable.projection-slo.observation-interval-ms` | `RG_TEST_PROJECTION_SLO_OBSERVATION_INTERVAL_MS` | `30000` |
| `gateway.testing.durable.projection-slo.startup-grace-seconds` | `RG_TEST_PROJECTION_SLO_STARTUP_GRACE_SECONDS` | `180` |
| `gateway.testing.durable.projection-slo.max-reconciliation-staleness-seconds` | `RG_TEST_PROJECTION_SLO_MAX_RECONCILIATION_STALENESS_SECONDS` | `180` |
| `gateway.testing.durable.projection-slo.max-retention-staleness-seconds` | `RG_TEST_PROJECTION_SLO_MAX_RETENTION_STALENESS_SECONDS` | `10800` |
| `gateway.testing.durable.projection-slo.max-unresolved-findings` | `RG_TEST_PROJECTION_SLO_MAX_UNRESOLVED_FINDINGS` | `0` |
| `gateway.testing.durable.projection-slo.max-unresolved-age-seconds` | `RG_TEST_PROJECTION_SLO_MAX_UNRESOLVED_AGE_SECONDS` | `3600` |
| `gateway.testing.durable.projection-slo.max-overdue-resolved-findings` | `RG_TEST_PROJECTION_SLO_MAX_OVERDUE_RESOLVED_FINDINGS` | `0` |
| `gateway.testing.durable.projection-slo.max-overdue-archive-records` | `RG_TEST_PROJECTION_SLO_MAX_OVERDUE_ARCHIVE_RECORDS` | `0` |
| `gateway.testing.durable.worker-acquisitions.candidate-limit` | `RG_TEST_DURABLE_WORKER_CANDIDATE_LIMIT` | `32` |
| `gateway.testing.durable.worker-acquisitions.initial-backoff-seconds` | `RG_TEST_DURABLE_WORKER_INITIAL_BACKOFF_SECONDS` | `5` |
| `gateway.testing.durable.worker-acquisitions.maximum-backoff-seconds` | `RG_TEST_DURABLE_WORKER_MAXIMUM_BACKOFF_SECONDS` | `300` |
| `gateway.testing.durable.worker-acquisitions.quarantine-threshold` | `RG_TEST_DURABLE_WORKER_QUARANTINE_THRESHOLD` | `32` |
| `gateway.testing.durable.recovery-sequences.retention-instance-id` | `RG_TEST_DURABLE_RECOVERY_SEQUENCE_RETENTION_INSTANCE_ID` | generated process identity in `test`; required in `staging` |
| `gateway.testing.durable.recovery-sequences.retention-lease-duration-seconds` | `RG_TEST_DURABLE_RECOVERY_SEQUENCE_RETENTION_LEASE_SECONDS` | `120` |
| `gateway.testing.durable.recovery-sequences.command-retention-days` | `RG_TEST_DURABLE_RECOVERY_SEQUENCE_COMMAND_RETENTION_DAYS` | `30` |
| `gateway.testing.durable.recovery-sequences.tombstone-retention-days` | `RG_TEST_DURABLE_RECOVERY_SEQUENCE_TOMBSTONE_RETENTION_DAYS` | `365` |
| `gateway.testing.durable.recovery-sequences.retention-page-size` | `RG_TEST_DURABLE_RECOVERY_SEQUENCE_RETENTION_PAGE_SIZE` | `100` |
| `gateway.testing.durable.recovery-sequences.retention-interval-ms` | `RG_TEST_DURABLE_RECOVERY_SEQUENCE_RETENTION_INTERVAL_MS` | `3600000` |
| `gateway.testing.durable.recovery-sequences.slo.observation-interval-ms` | `RG_TEST_DURABLE_RECOVERY_SEQUENCE_SLO_OBSERVATION_INTERVAL_MS` | `30000` |
| `gateway.testing.durable.recovery-sequences.slo.startup-grace-seconds` | `RG_TEST_DURABLE_RECOVERY_SEQUENCE_SLO_STARTUP_GRACE_SECONDS` | `180` |
| `gateway.testing.durable.recovery-sequences.slo.max-retention-staleness-seconds` | `RG_TEST_DURABLE_RECOVERY_SEQUENCE_SLO_MAX_RETENTION_STALENESS_SECONDS` | `10800` |
| `gateway.testing.durable.recovery-sequences.slo.max-overdue-sequences` | `RG_TEST_DURABLE_RECOVERY_SEQUENCE_SLO_MAX_OVERDUE_SEQUENCES` | `0` |
| `gateway.testing.durable.recovery-sequences.slo.max-oldest-overdue-sequence-age-seconds` | `RG_TEST_DURABLE_RECOVERY_SEQUENCE_SLO_MAX_OLDEST_OVERDUE_SEQUENCE_AGE_SECONDS` | `3600` |
| `gateway.testing.durable.recovery-sequences.slo.max-expired-tombstones` | `RG_TEST_DURABLE_RECOVERY_SEQUENCE_SLO_MAX_EXPIRED_TOMBSTONES` | `0` |
| `gateway.testing.durable.recovery-sequences.slo.max-oldest-expired-tombstone-age-seconds` | `RG_TEST_DURABLE_RECOVERY_SEQUENCE_SLO_MAX_OLDEST_EXPIRED_TOMBSTONE_AGE_SECONDS` | `3600` |
| `gateway.testing.durable.recovery-sequences.request-key-protection.active-key-id` | `RG_TEST_DURABLE_RECOVERY_SEQUENCE_REQUEST_KEY_ACTIVE_ID` | local key in `test`; required in `staging` |
| `gateway.testing.durable.recovery-sequences.request-key-protection.key-ring` | `RG_TEST_DURABLE_RECOVERY_SEQUENCE_REQUEST_KEY_RING` | local key in `test`; required in `staging` |
| `gateway.testing.durable.worker-quarantines.required-group` | `RG_TEST_WORKER_QUARANTINE_REQUIRED_GROUP` | `resource-gateway-test-runtime-operators` |
| `gateway.testing.durable.worker-quarantines.required-approver-group` | `RG_TEST_WORKER_QUARANTINE_REQUIRED_APPROVER_GROUP` | `resource-gateway-test-runtime-quarantine-approvers` |
| `gateway.testing.durable.worker-quarantines.required-clearance` | `RG_TEST_WORKER_QUARANTINE_REQUIRED_CLEARANCE` | `RESTRICTED` |
| `gateway.testing.durable.worker-quarantines.retention-instance-id` | `RG_TEST_WORKER_QUARANTINE_RETENTION_INSTANCE_ID` | generated process identity |
| `gateway.testing.durable.worker-quarantines.retention-lease-duration-seconds` | `RG_TEST_WORKER_QUARANTINE_RETENTION_LEASE_SECONDS` | `120` |
| `gateway.testing.durable.worker-quarantines.command-retention-days` | `RG_TEST_WORKER_QUARANTINE_COMMAND_RETENTION_DAYS` | `30` |
| `gateway.testing.durable.worker-quarantines.history-retention-days` | `RG_TEST_WORKER_QUARANTINE_HISTORY_RETENTION_DAYS` | `365` |
| `gateway.testing.durable.worker-quarantines.tombstone-retention-days` | `RG_TEST_WORKER_QUARANTINE_TOMBSTONE_RETENTION_DAYS` | `365` |
| `gateway.testing.durable.worker-quarantines.retention-page-size` | `RG_TEST_WORKER_QUARANTINE_RETENTION_PAGE_SIZE` | `100` |
| `gateway.testing.durable.worker-quarantines.retention-interval-ms` | `RG_TEST_WORKER_QUARANTINE_RETENTION_INTERVAL_MS` | `3600000` |
| `gateway.testing.durable.worker-quarantines.claim-token-protection.active-key-id` | `RG_TEST_WORKER_QUARANTINE_TOKEN_ACTIVE_KEY_ID` | local key in `test`; required in `staging` |
| `gateway.testing.durable.worker-quarantines.claim-token-protection.key-ring` | `RG_TEST_WORKER_QUARANTINE_TOKEN_KEY_RING` | local key in `test`; required in `staging` |
| `gateway.testing.durable.worker-quarantines.request-key-protection.active-key-id` | `RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_ACTIVE_KEY_ID` | local key in `test`; required in `staging` |
| `gateway.testing.durable.worker-quarantines.request-key-protection.key-ring` | `RG_TEST_WORKER_QUARANTINE_REQUEST_KEY_RING` | local key in `test`; required in `staging` |
| `gateway.testing.durable.worker-quarantines.request-key-protection.write-mode` | `RG_TEST_WORKER_QUARANTINE_REQUEST_INDEX_WRITE_MODE` | `DUAL_READ_KEYED_WRITE` in local `test`; required in `staging` |
| `gateway.testing.durable.worker-quarantines.request-index-rollout.instance-id` | `RG_RESOURCE_GATEWAY_INSTANCE_ID` | local id in `test`; deployment-inventory id required in `staging` |
| `gateway.testing.durable.worker-quarantines.request-index-rollout.artifact-fingerprint` | `RG_RESOURCE_GATEWAY_ARTIFACT_FINGERPRINT` | demo fingerprint in `test`; immutable artifact/image SHA-256 required in `staging` |
| `gateway.testing.durable.worker-quarantines.request-index-rollout.proof-ttl-seconds` | `RG_TEST_WORKER_QUARANTINE_REQUEST_INDEX_PROOF_TTL_SECONDS` | `120`; allowed range `5..300` |
| `gateway.testing.runtime-slo.observation-interval-ms` | `RG_TEST_RUNTIME_SLO_OBSERVATION_INTERVAL_MS` | `30000` |
| `gateway.testing.runtime-slo.outcome-lookback-seconds` | `RG_TEST_RUNTIME_SLO_OUTCOME_LOOKBACK_SECONDS` | `900` |
| `gateway.testing.runtime-slo.execution-minimum-samples` | `RG_TEST_RUNTIME_SLO_EXECUTION_MINIMUM_SAMPLES` | `20` |
| `gateway.testing.runtime-slo.execution-max-incomplete-basis-points` | `RG_TEST_RUNTIME_SLO_EXECUTION_MAX_INCOMPLETE_BASIS_POINTS` | `0` |
| `gateway.testing.runtime-slo.suite-minimum-samples` | `RG_TEST_RUNTIME_SLO_SUITE_MINIMUM_SAMPLES` | `5` |
| `gateway.testing.runtime-slo.suite-max-incomplete-basis-points` | `RG_TEST_RUNTIME_SLO_SUITE_MAX_INCOMPLETE_BASIS_POINTS` | `0` |
| `gateway.testing.runtime-slo.suite-max-depth` | `RG_TEST_RUNTIME_SLO_SUITE_MAX_DEPTH` | `100` |
| `gateway.testing.runtime-slo.suite-max-expired-leases` | `RG_TEST_RUNTIME_SLO_SUITE_MAX_EXPIRED_LEASES` | `0` |
| `gateway.testing.runtime-slo.suite-max-oldest-age-seconds` | `RG_TEST_RUNTIME_SLO_SUITE_MAX_OLDEST_AGE_SECONDS` | `120` |
| `gateway.testing.runtime-slo.creation-max-depth` | `RG_TEST_RUNTIME_SLO_CREATION_MAX_DEPTH` | `100` |
| `gateway.testing.runtime-slo.creation-max-expired-leases` | `RG_TEST_RUNTIME_SLO_CREATION_MAX_EXPIRED_LEASES` | `0` |
| `gateway.testing.runtime-slo.creation-max-oldest-age-seconds` | `RG_TEST_RUNTIME_SLO_CREATION_MAX_OLDEST_AGE_SECONDS` | `180` |
| `gateway.testing.runtime-slo.durable-max-depth` | `RG_TEST_RUNTIME_SLO_DURABLE_MAX_DEPTH` | `1000` |
| `gateway.testing.runtime-slo.durable-max-expired-leases` | `RG_TEST_RUNTIME_SLO_DURABLE_MAX_EXPIRED_LEASES` | `0` |
| `gateway.testing.runtime-slo.durable-max-oldest-age-seconds` | `RG_TEST_RUNTIME_SLO_DURABLE_MAX_OLDEST_AGE_SECONDS` | `180` |
| `gateway.testing.runtime-slo.work-max-depth` | `RG_TEST_RUNTIME_SLO_WORK_MAX_DEPTH` | `10000` |
| `gateway.testing.runtime-slo.work-max-expired-claims` | `RG_TEST_RUNTIME_SLO_WORK_MAX_EXPIRED_CLAIMS` | `0` |
| `gateway.testing.runtime-slo.work-max-oldest-age-seconds` | `RG_TEST_RUNTIME_SLO_WORK_MAX_OLDEST_AGE_SECONDS` | `300` |
| `gateway.testing.runtime-slo.worker-backoff-max-active` | `RG_TEST_RUNTIME_SLO_WORKER_BACKOFF_MAX_ACTIVE` | `1000` |
| `gateway.testing.runtime-slo.worker-backoff-max-retry-due` | `RG_TEST_RUNTIME_SLO_WORKER_BACKOFF_MAX_RETRY_DUE` | `100` |
| `gateway.testing.runtime-slo.worker-backoff-max-consecutive-failures` | `RG_TEST_RUNTIME_SLO_WORKER_BACKOFF_MAX_CONSECUTIVE_FAILURES` | `16` |
| `gateway.testing.runtime-slo.worker-backoff-max-oldest-age-seconds` | `RG_TEST_RUNTIME_SLO_WORKER_BACKOFF_MAX_OLDEST_AGE_SECONDS` | `3600` |
| `gateway.testing.runtime-slo.worker-quarantine-max-records` | `RG_TEST_RUNTIME_SLO_WORKER_QUARANTINE_MAX_RECORDS` | `100` |
| `gateway.testing.runtime-slo.worker-quarantine-max-oldest-age-seconds` | `RG_TEST_RUNTIME_SLO_WORKER_QUARANTINE_MAX_OLDEST_AGE_SECONDS` | `86400` |
| `gateway.testing.runtime-slo.worker-quarantine-max-expired-claims` | `RG_TEST_RUNTIME_SLO_WORKER_QUARANTINE_MAX_EXPIRED_CLAIMS` | `0` |
| `gateway.testing.runtime-slo.worker-quarantine-max-expired-discard-approvals` | `RG_TEST_RUNTIME_SLO_WORKER_QUARANTINE_MAX_EXPIRED_DISCARD_APPROVALS` | `0` |
| `gateway.testing.runtime-slo.max-expired-execution-records` | `RG_TEST_RUNTIME_SLO_MAX_EXPIRED_EXECUTION_RECORDS` | `0` |
| `gateway.testing.runtime-slo.max-expired-suite-records` | `RG_TEST_RUNTIME_SLO_MAX_EXPIRED_SUITE_RECORDS` | `0` |
| `gateway.testing.runtime-slo.max-terminal-durable-executions` | `RG_TEST_RUNTIME_SLO_MAX_TERMINAL_DURABLE_EXECUTIONS` | `10000` |
| `gateway.testing.runtime-slo.max-terminal-work-items` | `RG_TEST_RUNTIME_SLO_MAX_TERMINAL_WORK_ITEMS` | `100000` |

## 3. Authentication

Testing endpoints require a verified bearer and the least-privilege purpose for the operation:

```text
Authorization: Bearer <verified workload credential>
X-Purpose: TEST_EXECUTION | TEST_FIXTURE_READ | TEST_FIXTURE_WRITE | TEST_REPLAY | TEST_SUITE_READ | TEST_SUITE_WRITE | TEST_RUNTIME_MAINTENANCE
```

The local test-profile defaults are:

| Operation | Required `X-Purpose` |
| --- | --- |
| target discovery | any testing purpose, including suite read/write |
| execute, batch, child-run query, suite execute/query without REPLAY | `TEST_EXECUTION` |
| execute or suite execute when any fixture uses REPLAY | `TEST_REPLAY` |
| fixture revision query | `TEST_FIXTURE_READ` |
| immutable fixture registration | `TEST_FIXTURE_WRITE` |
| governed replay payload capture/query | `TEST_REPLAY` |
| test-suite revision query | `TEST_SUITE_READ` |
| immutable test-suite registration | `TEST_SUITE_WRITE` |
| built-in graph catalog materialization | `TEST_SUITE_WRITE` |
| global durable projection finding read/claim/resolve | `TEST_RUNTIME_MAINTENANCE` plus configured global group and clearance |
| scoped durable worker quarantine list/claim/release/approved-discard/history | `TEST_RUNTIME_MAINTENANCE` plus configured operator group and clearance |
| issue a signed request-index replica rollout proof | `TEST_RUNTIME_MAINTENANCE` plus configured operator group and clearance; complete project and region identity required |
| approve a scoped durable worker quarantine discard | `TEST_RUNTIME_MAINTENANCE` plus the distinct configured approver group and clearance |

The local demo bearer is `bloge-aneke-demo-token` and is granted all listed testing purposes.
Production credentials should keep fixture authors, suite authors, readers, and runners separate.
`RG_INTEGRATION_ENVIRONMENT_ID` and `RG_INTEGRATION_ALLOWED_PURPOSES` override profile defaults;
deployment manifests must set both explicitly so a staging runner cannot inherit production identity claims.

`X-Tenant-Id`, `X-Environment-Id`, and actor headers are optional claim hints only. They never create
identity. If supplied, they must match the verified credential. Fixture and run lookups always apply
the verified tenant and environment scope, returning 404 rather than revealing cross-scope existence.

## 4. End-To-End Flow

### 4.1 Discover and freeze the target

```bash
curl -sS http://localhost:8080/api/testing/targets/graphs/loanDecisionPolicy \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION'
```

The response contains:

- graph id and current composite SHA-256 fingerprint;
- graph-level input/output schema contract;
- resource descriptor fingerprints;
- `CONSERVATIVE_ALL_REGISTERED` dependency policy.
- certification eligibility and explicit gaps; a graph without recoverable definition source is
  always restricted to `EXPLORATORY` evidence.

The conservative policy exists because BLOGE expressions may compute `resourceId` at runtime. A
descriptor change may invalidate more fixture bundles than strictly necessary, but the system never
certifies against an incomplete dependency set.

### 4.1.1 Discover and freeze an operator binding

```bash
curl -sS http://localhost:8080/api/testing/targets/operators/httpResource \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION'
```

`bloge.testOperatorTargetDescriptor.v2` returns the operator target fingerprint, implementation
closure fingerprint, runtime-binding-state fingerprint, schema fingerprint, composability manifest
fingerprint, input/output schemas, execution model, side-effect/idempotency declarations, resource
dependencies, and explicit testability facts. v2 is intentional: v1 did not carry the required
composability facts and is retained by the Java test kit only as a historical version constant.

| `testabilityClass` | Meaning |
| --- | --- |
| `EXECUTABLE_UNIT` | Synchronous read-only binding has a valid self-contained composability manifest |
| `CONDITIONAL_TRANSPORT` | `HttpResourceOperator` is executable only with strict transport fixtures |
| `OPAQUE_RUNTIME` | Effects are not exposed through a controllable composability port |
| `UNSUPPORTED_EXECUTION_MODEL` | Streaming/suspendable execution is discoverable but blocked in v1 |

Discovery never executes the operator. `certificationEligible=true` additionally requires
fingerprintable implementation bytes, formalized runtime state, valid behavioral declarations, and a
bounded `OperatorComposabilityManifest`. A stateless binding satisfies only the runtime-state
condition; it no longer receives certification merely because it has no instance fields. A configured
binding must implement `OperatorRuntimeBindingSnapshotProvider`; the returned bounded credential-free
map is fingerprinted but never returned or persisted. A non-resource binding must also implement
`OperatorComposabilityManifestProvider` and bind a self-contained dependency declaration to a
conformance suite reference and SHA-256 artifact fingerprint. Missing manifests, declared execution
services (`TIME`, `RANDOM`, `UUID`, `IDENTITY`, `FEATURE_FLAG`), generic dependency ports, mutable
global state, or malformed conformance facts fail certification closed in v1 runtime semantics.
`HttpResourceOperator` has a built-in contract that fingerprints its protocol-processing class
closure and the conservative descriptor snapshot.

### 4.1.2 Generate validator-proven boundary inputs

After discovery, request a boundary plan for the same current graph or operator target:

```bash
curl -sS http://localhost:8080/api/testing/targets/graphs/loanDecisionPolicy/boundary-cases \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION'

curl -sS http://localhost:8080/api/testing/targets/operators/httpResource/boundary-cases \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION'
```

Both endpoints use the target-read authorization boundary, resolve the current exact target
fingerprint, and return `bloge.testBoundaryCasePlan.v1`. The plan contains the projected input-schema
fingerprint, a content fingerprint, deterministic generation policy, complete candidate inputs, the
expected schema-admission outcome, validator diagnostic codes, and explicit coverage gaps. Operator
planning retains BLOGE-to-visual-schema projection warnings instead of silently treating a lossy
projection as complete.

| `status` | Contract |
| --- | --- |
| `GENERATED` | At least one case exists and the planner found no unsupported constraint or generation bound in the traversed supported subset |
| `PARTIAL` | Useful proven cases exist, but `gaps` names unsupported constraints, projection loss, an unprovable candidate, or a safety limit |
| `UNAVAILABLE` | The schema is opaque/invalid or no baseline could be independently proven valid; `cases` is empty |

Generation is deterministic and bounded to 64 published cases, schema depth 8, and generated
string/collection size 32. Every accepted candidate must produce no validator error. Every rejected
candidate must produce the diagnostic family expected for its transformation; an incidental failure
does not count. v1 expands required/unknown properties, type mismatches, numeric and exclusive
bounds, string/array lengths, enum, and const. Constraints such as pattern, format, multipleOf,
uniqueItems, combinators, conditionals, dependent schemas, and property-count rules are disclosed as
`CONSTRAINT_NOT_BOUNDARY_EXPANDED`; nullable type arrays are also disclosed because v1 traverses only
the generated baseline branch. Empty, `any`, and `opaque` input domains are `UNAVAILABLE` rather than
being represented by one misleading baseline.

This response is an authoring plan, not a persisted `TestSuite`, an executed run, correctness
evidence, exhaustive property coverage, or a mutation score. Review `gaps`, select or refine cases,
and materialize an exact selected subset as described below. Schemas must not embed credentials or business
secrets in defaults/examples; target readers can already inspect those schema literals, and generated
baseline inputs may reproduce them.

### 4.1.3 Generate reproducible property trials

Property planning explores more than the fixed boundary transforms while retaining an exact replay
coordinate. The caller must choose the seed; trial and shrink limits are explicit and bounded:

```bash
curl -sS 'http://localhost:8080/api/testing/targets/graphs/loanDecisionPolicy/property-cases?seed=918273645&trials=8&maxShrinkSteps=3' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION'

curl -sS 'http://localhost:8080/api/testing/targets/operators/httpResource/property-cases?seed=918273645&trials=8&maxShrinkSteps=3' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION'
```

Both endpoints return `bloge.testPropertyCasePlan.v1` for the current exact target and projected
input-schema fingerprint. Repeating the same request against the same target and schema reproduces
the same ordered roots, shrink paths, and `planFingerprint`. Changing the seed, schema, target, policy,
candidate, or disclosed gap changes the content address. Operator plans preserve BLOGE schema
projection warnings as first-class gaps.

Every published root and shrink candidate is independently accepted by `VisualSchemaValidator`.
Shrink paths are precomputed, linear, and strictly decrease a deterministic complexity score; they are
candidate minimization coordinates for later execution, not proof that a business failure was minimized.
v1 permits at most 16 unique roots, five shrink candidates per root, 96 total cases, 32 attempts per
unique root, schema depth 8, and string/collection size 32. A low-cardinality domain returns `PARTIAL`
with `UNIQUE_TRIAL_LIMIT_REACHED` rather than duplicating values to satisfy a requested count.

`quantification=BOUNDED_SAMPLED` and `exhaustive=false` are mandatory protocol facts. `GENERATED`
means every requested unique root was produced without a disclosed planning gap; it does not mean the
input domain is exhausted. `PARTIAL` retains useful proven trials and names every known projection,
constraint, or resource-limit gap. `UNAVAILABLE` publishes no trial and must explain why. The capability
`seededPropertyCasePlanning=true` advertises this authoring step. A reviewed plan can now be frozen as
V4 through the next section and executed through the isolated testing runtime. Planning alone is
still not correctness evidence; only a generation-matched terminal property evidence bundle can be
consumed by CI or a governance gate.

### 4.1.4 Plan bounded pure-DSL mutants

Mutation planning starts from the recoverable BLOGE DSL AST already attached to the exact graph:

```bash
curl -sS 'http://localhost:8080/api/testing/targets/graphs/loanDecisionPolicy/mutation-cases?maxMutants=64' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION'
```

The response is `bloge.testMutationCasePlan.v1`. Before publishing any mutant, the service decodes
the tagged AST through a restricted class allowlist, independently recompiles the baseline, and
requires both its graph-artifact fingerprint and its complete dependency-bound target fingerprint to
match the current graph. Each candidate is then independently recompiled through the runtime operator
registry. A non-compiling or duplicate candidate is omitted and disclosed as a stable gap.

v1 can toggle branch mode, redirect branch targets, negate a decision predicate, swap FIRST-hit
decision rules, relax a decision hit policy, swap adjacent transform bindings, remove a fallback, or
decrement a positive retry count. It never rewrites `operatorRef`, operator implementation, external
request, fixture, payload, or operator input binding. Imported graphs, extension nodes and nested
foreach/loop/parallel scopes are not expanded in this generation and therefore produce explicit gaps.

| `status` | Contract |
| --- | --- |
| `GENERATED` | At least one independently compiling mutant exists and no supported site or declared bound was skipped |
| `PARTIAL` | Useful mutants exist, but a limit, unsupported scope, duplicate, or compiler rejection is disclosed in `gaps` |
| `UNAVAILABLE` | No safely reproducible mutant exists; `mutants` is empty and `gaps` explains the source or verification failure |

The plan is bounded to 1 through 128 mutants. It contains AST coordinates and source/artifact/target
fingerprints, but deliberately omits executable mutated source and business literals. Every mutant's
`equivalenceClassification` is `UNKNOWN`; v1 performs no equivalent-mutant detection. This endpoint
itself does not execute mutants, materialize a suite, calculate a score, or emit evidence. Capability
clients must observe `pureDslMutationPlanning`, `mutationSuiteMaterialization`,
`pureDslMutationExecution`, and `mutationScoreEvidence` as independent facts. When the isolated test
runtime is assembled, all four are currently true; the plan alone is still only an authoring asset.

### 4.1.5 Materialize a reviewed property plan

Register an assertion-bearing fixture for the same exact target, then submit the plan's seed, policy,
three review fingerprints, and exact fixture reference. Materialization requires
`TEST_SUITE_WRITE` and always freezes the complete root-plus-shrink closure; there is no favorable-case
selection field.

```http
POST /api/testing/targets/graphs/loanDecisionPolicy/property-suites
Authorization: Bearer bloge-aneke-demo-token
X-Purpose: TEST_SUITE_WRITE
Content-Type: application/json

{
  "schemaVersion": "bloge.testPropertySuiteMaterializationRequest.v1",
  "suiteId": "loan-decision-properties",
  "classification": "INTERNAL",
  "expectedTargetFingerprint": "sha256:<target>",
  "expectedInputSchemaFingerprint": "sha256:<input-schema>",
  "expectedPlanFingerprint": "sha256:<property-plan>",
  "seed": 918273645,
  "trials": 8,
  "maxShrinkSteps": 3,
  "fixtureRef": {
    "fixtureBundleId": "loan-property-assertions",
    "revision": 4,
    "fingerprint": "sha256:<fixture>"
  },
  "acceptGenerationGaps": false
}
```

Use `POST /api/testing/targets/operators/{operatorRef}/property-suites` for an operator target. The
service regenerates the plan with the submitted coordinates. Target, projected schema, or plan drift
returns `409 RG.TEST.PROPERTY_PLAN_FINGERPRINT_CONFLICT`. A partial plan requires explicit gap
acceptance; an unavailable plan is rejected. Fixture fingerprint or target substitution is a
conflict, and an assertion-free fixture is invalid.

Success returns `bloge.testPropertySuiteMaterialization.v1`: materialization fingerprint, exact
target/schema/plan, copied generation policy, ordered root IDs, the complete case ID closure, fixture
ref, and content-derived V4 suite ref. `bloge.testSuite.v4` retains bounded-sampled/non-exhaustive
quantification, generator policy, accepted gaps, every input and fingerprint, and root/shrink lineage
as canonical fields. Inputs are recursively immutable. Every case has type `PROPERTY`, uses the same
fixture revision, and participates in full-case coverage and future fail-closed promotion policy.

Raw `PUT /api/testing/suites/{suiteId}` registration rejects V4; only the materializer can provide the
same-request regenerated plan proof. Conversely, `PROPERTY` cannot be used in V1-V3. Capability
discovery reports `propertySuiteMaterialization=true`; it reports `propertySuiteExecution=true` only
when the isolated suite-execution endpoint is enabled.

The standalone test-kit exposes graph/operator plan and materialization methods and validates all
four messages against its packaged schema. Implementation and negative proof details are recorded in
[Stage 5 immutable property suite materialization verification](resource-gateway-execution-data-control-plane-stage5-property-suite-materialization-verification.md).

### 4.1.6 Execute and verify a bounded property suite

Execute the exact V4 suite reference returned by materialization through the ordinary suite endpoint:

```http
POST /api/testing/suites/loan-decision-properties/executions
Authorization: Bearer bloge-aneke-demo-token
X-Purpose: TEST_EXECUTION
Content-Type: application/json

{
  "schemaVersion": "bloge.testSuiteExecutionRequest.v1",
  "suiteRef": {
    "suiteId": "loan-decision-properties",
    "revision": 4,
    "fingerprint": "sha256:<materialized-suite>"
  },
  "clientRequestId": "property-ci-1842",
  "strategy": "COLLECT_ALL",
  "metadata": {"pipeline": "release-candidate", "buildId": "1842"}
}
```

The service executes each frozen root and shrink candidate with the suite's one exact fixture. It
does not regenerate inputs at run time. `COLLECT_ALL` evaluates the complete closure. `FAIL_FAST`
finishes the current root's precomputed shrink path after the first counterexample, then skips later
roots; this avoids publishing a root failure without its reviewed minimization evidence.

The response/evidence/attestation/bundle generation is V5/V4/V4/V4. Typed property results distinguish
`SATISFIED`, `COUNTEREXAMPLE`, `EXECUTION_FAILED`, and `EVIDENCE_INCOMPLETE` at case and aggregate
levels. A counterexample reference contains only case id, input fingerprint, complexity, and
minimality facts. `minimalityScope=PRECOMPUTED_SHRINK_PATH` and `globallyMinimal=false` are mandatory:
the finite reviewed path is reproducible evidence, not a global proof over the input domain.

The aggregate remains bounded and non-exhaustive even when every sampled case passes. Exact
idempotency replay returns the existing checkpoint or terminal evidence. Lease loss, signing failure,
terminal persistence failure, and abandoned checkpoints fail closed. Reconciliation preserves every
completed root/shrink result, marks only pending cases incomplete, signs a terminal V4 closure, and
never re-invokes the business target. Export the portable bundle and verify it against an independently
pinned key set before using the result as a publish-gate input. Full implementation and failure proofs
are recorded in
[Stage 5 property execution verification](resource-gateway-execution-data-control-plane-stage5-property-execution-verification.md).

### 4.1.7 Materialize reviewed boundary cases

After reviewing one exact plan, submit the target, input-schema, and plan fingerprints together with
an explicit case selection. Materialization requires `TEST_SUITE_WRITE`; target-read or execution-only
credentials cannot publish generated candidates as governed assets.

```http
POST /api/testing/targets/graphs/loanDecisionPolicy/boundary-suites
Authorization: Bearer bloge-aneke-demo-token
X-Purpose: TEST_SUITE_WRITE
Content-Type: application/json

{
  "schemaVersion": "bloge.testBoundarySuiteMaterializationRequest.v1",
  "suiteId": "loan-decision-schema-boundaries",
  "classification": "INTERNAL",
  "expectedTargetFingerprint": "sha256:<target>",
  "expectedInputSchemaFingerprint": "sha256:<input-schema>",
  "expectedPlanFingerprint": "sha256:<plan>",
  "selectedCaseIds": ["baseline", "required-customerId-missing"],
  "acceptCoverageGaps": false
}
```

Use `POST /api/testing/targets/operators/{operatorRef}/boundary-suites` with the same body for an
operator target.

The service regenerates the current plan before any write. A changed target, projected schema, or
plan returns `409 RG.TEST.BOUNDARY_PLAN_FINGERPRINT_CONFLICT`; the caller must fetch and review the
new plan. Unknown, duplicate, empty, oversized, or more than 64 case IDs fail closed. `PARTIAL`
requires `acceptCoverageGaps=true`; `UNAVAILABLE` cannot be materialized.

Success returns `bloge.testBoundarySuiteMaterialization.v1` with a content fingerprint, source-plan
status, selected IDs in source-plan order, one exact inert fixture ref, and one exact
`bloge.testSuite.v3` ref. Content-derived revisions make an exact retry idempotent. The fixture has no
rules, assertions, clock, or random seed; v3 binds every case ID to `ACCEPTED` or `SCHEMA_REJECTED`
plus its stable validator codes. Its execution/semantic coverage is deliberately empty and its
promotion policy requires zero certifiable cases. If fixture storage succeeds but suite storage
fails, retry is safe: the only residue is an immutable, unreferenced inert fixture.

The capability probe reports both `schemaBoundarySuiteMaterialization=true` and
`schemaAdmissionSuiteExecution=true` when the isolated testing runtime is assembled. Execute the
exact v3 suite as described below. Admission evidence is useful for reviewed schema-regression
gates, but it remains permanently ineligible for business promotion.

### 4.1.8 Execute and verify a schema-admission suite

Use the exact suite reference returned by materialization. The request and idempotency semantics are
the same as other immutable suites:

```http
POST /api/testing/suites/loan-decision-schema-boundaries/executions
Authorization: Bearer bloge-aneke-demo-token
X-Purpose: TEST_EXECUTION
Content-Type: application/json

{
  "schemaVersion": "bloge.testSuiteExecutionRequest.v1",
  "suiteRef": {
    "suiteId": "loan-decision-schema-boundaries",
    "revision": 3,
    "fingerprint": "sha256:<materialized-suite>"
  },
  "clientRequestId": "schema-admission-ci-1042",
  "strategy": "COLLECT_ALL",
  "metadata": {"source": "contract-regression"}
}
```

The runner atomically resolves the current target, projected input schema, and regenerated boundary
plan, then validates every stored case with the same shared validator used by graph/operator
admission. It never invokes the graph or operator. A terminal response uses this same-generation
closure:

| Object | Version | Required interpretation |
| --- | --- | --- |
| response | `bloge.testSuiteExecutionResponse.v4` | schema-admission response, not business execution |
| evidence | `bloge.testSuiteRunEvidence.v3` | exact plan/schema/generator provenance plus typed observations |
| attestation | `bloge.testSuiteRunAttestation.v3` | signed terminal/checkpoint aggregate with empty `childEvidenceRefs` |
| portable bundle | `bloge.testSuiteEvidenceBundle.v3` | payload-free evidence export for offline verification |

`admissionResults[*].status=MATCHED` proves both case provenance and exact expected validator
outcome/codes. `admissionCoverage.status=SATISFIED` requires every selected case to match. Structural
`coverage` is always `NOT_EVALUATED`; `promotion` is always `BLOCKED` with
`SCHEMA_ADMISSION_ONLY` and `BUSINESS_EXECUTION_NOT_PERFORMED`; every compatibility case has blank
`runId`, null child evidence fields, and zero assertion counters. These are protocol invariants, not
display conventions.

Export terminal evidence with:

```bash
curl -sS http://localhost:8080/api/testing/suite-executions/<suiteRunId>/evidence-bundle \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION'
```

The standalone test-kit exposes `evaluationMode()`, `admissionResults()`,
`requireAdmissionCoverage()`, `admissionPassed()`, and
`TestSuiteRunAssertions.assertAdmissionPassed(...)`. `passed()` and `assertPassed(...)` remain
business-execution predicates and deliberately return/fail false for admission-only evidence. For a
JUnit XML schema-regression gate, call `writeSuite(..., false)`; requiring promotion eligibility is
expected to fail because admission evidence can never authorize publication by itself.

Exact request replay returns the same checkpoint or terminal result. Changed suite intent returns an
idempotency conflict. Target/schema/plan drift fails before evaluation, signer unavailability fails
closed without publishing unsigned v3 evidence, and abandoned checkpoints reconcile to signed
`EVIDENCE_INCOMPLETE` while preserving completed observations and the empty business-child closure.

### 4.1.9 Materialize an exact mutation matrix

After reviewing a mutation plan, bind it to one exact executable graph oracle. The service accepts
only the complete regenerated plan closure; callers cannot upload mutated source, select favorable
mutants, or substitute a mutable/latest oracle.

```http
POST /api/testing/targets/graphs/loanDecisionPolicy/mutation-suites
Authorization: Bearer bloge-aneke-demo-token
X-Purpose: TEST_SUITE_WRITE
Content-Type: application/json

{
  "schemaVersion": "bloge.testMutationSuiteMaterializationRequest.v1",
  "suiteId": "loan-decision-mutations",
  "classification": "INTERNAL",
  "expectedTargetFingerprint": "sha256:<target>",
  "expectedSourceFingerprint": "sha256:<recoverable-source>",
  "expectedGraphArtifactFingerprint": "sha256:<baseline-artifact>",
  "expectedPlanFingerprint": "sha256:<reviewed-plan>",
  "maxMutants": 16,
  "oracleSuiteRef": {
    "suiteId": "loan-decision-regression",
    "revision": 7,
    "fingerprint": "sha256:<oracle-suite>"
  },
  "acceptPlanningGaps": false,
  "scorePolicy": {
    "minimumScoreBasisPoints": 8000,
    "maximumInconclusiveMutants": 0,
    "requireNoSurvivors": false,
    "excludeEquivalentMutants": false
  }
}
```

Materialization is deliberately narrower than authoring planning: generation one freezes at most 16
mutants, at most 16 oracle cases, and at most 256 mutant-case executions. The oracle may be an
executable V1, V2, or V4 graph suite for the same exact target. Every oracle fixture fingerprint and
target binding is reread, and every case must contain at least one governed business assertion. V3
schema-admission suites, V5 mutation suites, target drift, assertion-free fixtures, and oversized
matrices fail before registration.

The service regenerates the plan using `maxMutants` and requires exact target, source, artifact, and
plan fingerprints. `PARTIAL` requires `acceptPlanningGaps=true`; `UNAVAILABLE` cannot be materialized.
Success returns `bloge.testMutationSuiteMaterialization.v1` plus a content-derived
`bloge.testSuite.v5` ref. V5 freezes the complete mutant and oracle closure, score policy, and
generation-one `equivalenceClassification=UNKNOWN`; `excludeEquivalentMutants` must remain false.

### 4.1.10 Execute and verify a mutation suite

V5 uses a dedicated endpoint so an ordinary suite runner cannot accidentally flatten mutation
semantics into structural coverage:

```http
POST /api/testing/suites/loan-decision-mutations/mutation-executions
Authorization: Bearer bloge-aneke-demo-token
X-Purpose: TEST_EXECUTION
Content-Type: application/json

{
  "schemaVersion": "bloge.testMutationSuiteExecutionRequest.v1",
  "suiteRef": {
    "suiteId": "loan-decision-mutations",
    "revision": 5,
    "fingerprint": "sha256:<materialized-suite>"
  },
  "clientRequestId": "mutation-ci-1842",
  "strategy": "STOP_AFTER_KILL",
  "metadata": {"pipeline": "release-candidate", "buildId": "1842"}
}
```

The runner first executes the complete oracle against the unmodified exact graph. A failed or
incomplete baseline prevents all mutant scheduling. It then regenerates each reviewed mutant through
the same planner and runs it in the isolated test engine with the baseline-bound case inputs and
governed fixtures. `COLLECT_ALL` runs every case. `STOP_AFTER_KILL` stops only the current mutant's
remaining cases after a signed assertion failure; every later mutant is still visited, so scheduling
cannot selectively inflate the score.

Only a child with `evidenceStatus=ASSERTION_FAILED` and a failed governed assertion produces
`ASSERTION_KILLED`. A fully passing case contributes to survival. Timeout, fixture, control, runtime,
target, persistence, and evidence failures are inconclusive, never kills. A partially scheduled
mutant without a valid kill stays unclassified. The generation-one denominator is exactly
`killed + survived`; an unclassified mutant forces score zero, and no equivalent mutant is excluded.

| Object | Version | Required interpretation |
| --- | --- | --- |
| response | `bloge.testSuiteExecutionResponse.v6` | exact V5 mutation run, not an ordinary suite response |
| evidence | `bloge.testSuiteRunEvidence.v5` | baseline, complete mutant matrix, classification, and score |
| attestation | `bloge.testSuiteRunAttestation.v5` | signed ordered baseline and mutant child closure |
| portable bundle | `bloge.testSuiteEvidenceBundle.v5` | payload-free terminal export for offline verification |

Child attestation labels are structural coordinates: `baseline/<caseId>` for the unmodified graph and
`<mutantId>/<caseId>` for mutant execution. Their run id, evidence fingerprint, fixture identity, and
mutant target fingerprint must match the aggregate result exactly. Exact idempotency replay returns
the existing checkpoint or terminal result without a second child execution. If a lease is abandoned,
reconciliation preserves completed children, marks pending baseline work incomplete and pending
mutant work `NOT_SCHEDULED` with `ABANDONED_RUN_RECONCILED`, recomputes the score, signs terminal V5
evidence, and never reruns a potentially side-effecting child.

The standalone test-kit exposes `materializeGraphMutationSuite(...)`,
`executeMutationSuite(...)`, `requireMutationScore()`, `mutantResults()`, and
`TestSuiteRunAssertions.assertMutationSatisfied(...)`. Its offline verifier independently re-derives
classification, kill provenance, counts, denominator, score, policy verdict, and prefixed child
closure before accepting a V5 bundle. The shaded CLI selects this endpoint with `--mode MUTATION`;
`--strategy` accepts only `COLLECT_ALL` or `STOP_AFTER_KILL` in that mode.

This is a bounded generation-one mutation score, not semantic equivalent-mutant proof, flaky-run
analysis, statistical confidence, cross-process scheduling, or deployment-level physical isolation.
Implementation and failure evidence are recorded in
[Stage 5 mutation execution verification](resource-gateway-execution-data-control-plane-stage5-mutation-execution-verification.md).

### 4.2 Register an immutable governed fixture

Use the discovered target fingerprint in both `target.fingerprint` and
`fixtureBundle.targetFingerprint`:

```http
PUT /api/testing/fixture-bundles/loan-prime-v1
Authorization: Bearer bloge-aneke-demo-token
X-Purpose: TEST_FIXTURE_WRITE
Content-Type: application/json
```

```json
{
  "schemaVersion": "bloge.fixtureBundleRegistrationRequest.v1",
  "target": {
    "kind": "GRAPH",
    "id": "loanDecisionPolicy",
    "fingerprint": "sha256:<from-target-descriptor>"
  },
  "fixtureBundle": {
    "schemaVersion": "bloge.fixtureBundle.v1",
    "fixtureBundleId": "loan-prime-v1",
    "revision": 1,
    "targetFingerprint": "sha256:<from-target-descriptor>",
    "classification": "INTERNAL",
    "logicalClock": null,
    "randomSeed": null,
    "rules": [
      {
        "schemaVersion": "bloge.fixtureRule.v1",
        "ruleId": "applicant-profile",
        "selector": {
          "graphPath": "/root",
          "nodeId": "fetchApplicant",
          "operatorRef": "",
          "resourceRef": "loan-applicant-service.getProfile",
          "functionRef": "",
          "capabilities": [],
          "tags": [],
          "invocationKind": "RESOURCE",
          "attempts": [],
          "occurrences": [],
          "correlationKey": "",
          "match": {
            "canonicalInput": null,
            "pathEquals": {},
            "pathsExist": [],
            "pathsAbsent": [],
            "schema": {},
            "correlationKey": "",
            "boundedRegex": {}
          }
        },
        "behavior": {
          "kind": "RETURN",
          "boundary": "TRANSPORT",
          "value": null,
          "rawBody": "{\"code\":0,\"data\":{\"applicantId\":\"prime\",\"score\":780,\"segment\":\"private-bank\"}}",
          "statusCode": 200,
          "headers": {"Content-Type": "application/json"},
          "errorCode": "",
          "errorType": "",
          "errorMessage": "",
          "after": null,
          "sequence": [],
          "replayRef": ""
        },
        "consumption": {
          "required": true,
          "minUses": 1,
          "maxUses": 1,
          "onExhausted": "FAIL",
          "onUnmatched": "FAIL"
        },
        "schemaCheck": {"mode": "STRICT", "waiverReason": ""}
      }
    ],
    "assertions": [
      {
        "scope": "OUTPUT_PATH",
        "nodeId": "assembleLoanDecision",
        "path": "/policy/ruleId",
        "operator": "EQUALS",
        "expected": "R1",
        "numericTolerance": null
      }
    ],
    "metadata": {"owner": "risk-quality", "caseType": "golden"}
  }
}
```

The `(tenant, environment, fixtureBundleId, revision)` key is immutable. Repeating byte-equivalent
content is idempotent; different content returns `RG.TEST.FIXTURE_REVISION_CONFLICT`.

### 4.2.1 Control logical time, delay, and timeout

`DELAY` and `TIMEOUT` are active only when the fixture bundle declares a `logicalClock` origin.
Each run receives its own monotonic clock. A logical sleep advances that clock atomically and returns
without wall-clock waiting; the clock and its state are never shared between test runs.

```json
{
  "logicalClock": "2026-07-15T09:00:00Z",
  "rules": [
    {
      "schemaVersion": "bloge.fixtureRule.v1",
      "ruleId": "bureau-timeout",
      "selector": {
        "graphPath": "/root",
        "nodeId": "fetchCreditScore",
        "operatorRef": "",
        "resourceRef": "",
        "functionRef": "",
        "capabilities": [],
        "tags": [],
        "invocationKind": "PRIMARY",
        "attempts": [],
        "occurrences": [],
        "correlationKey": "",
        "match": {
          "canonicalInput": null,
          "pathEquals": {},
          "pathsExist": [],
          "pathsAbsent": [],
          "schema": {},
          "correlationKey": "",
          "boundedRegex": {}
        }
      },
      "behavior": {
        "kind": "TIMEOUT",
        "boundary": "NODE",
        "value": null,
        "rawBody": "",
        "statusCode": null,
        "headers": {},
        "errorCode": "CREDIT_BUREAU_TIMEOUT",
        "errorType": "TIMEOUT",
        "errorMessage": "credit bureau did not answer",
        "after": "PT3S",
        "sequence": [],
        "replayRef": ""
      },
      "consumption": {
        "required": true,
        "minUses": 2,
        "maxUses": 2,
        "onExhausted": "FAIL",
        "onUnmatched": "FAIL"
      },
      "schemaCheck": {"mode": "STRICT", "waiverReason": ""}
    }
  ]
}
```

Time-control rules obey these fail-closed constraints:

- `after` is required, positive, and no greater than 365 days;
- only `boundary=NODE` is supported;
- `TIMEOUT` cannot also carry a return or protocol payload;
- `DELAY` advances time and then returns its schema-gated `value`;
- `TIMEOUT` advances time and throws BLOGE's `OperatorTimeoutException`, so the graph's real retry
  and fallback policies remain in charge;
- a timeout without recovery produces top-level `TIMED_OUT`, node status `TIMEOUT`, and the declared
  stable `errorCode`;
- evidence metadata records `logicalTime.mode`, `origin`, `current`, and `elapsedMs`; audit
  `startedAt/completedAt` remain real timestamps.

This mode verifies time-dependent business behavior and the graph's reaction to timeout. It does
not prove wall-clock watchdog accuracy, interruption of blocked operator code, or deterministic
completion order between concurrent branches. Those remain engine/sandbox conformance concerns.

### 4.2.1.1 Select retry attempts and graph re-entry occurrences

`attempts` and `occurrences` are active runtime selector coordinates. Both are one-based, bounded
to 100 values in the range `1..100000`, unique, and strictly increasing on the wire. An empty array
matches every coordinate. Values inside one array are OR alternatives; non-empty attempt and
occurrence arrays are combined with AND.

The following pair drives the real BLOGE retry policy deterministically: the first delegate call
times out, while the second returns a fixture value.

```java
FixtureBundleBuilder retryFixture = FixtureBundleBuilder
        .graph(target.graphId(), target.fingerprint())
        .id("credit-provider-retry")
        .logicalClock(Instant.parse("2026-07-15T09:00:00Z"))
        .rule("attempt-1-timeout")
            .node("fetchCreditScore")
            .attempts(1)
            .timeout(Duration.ofSeconds(3), "FIRST_ATTEMPT_TIMEOUT", "retry")
            .add()
        .rule("attempt-2-recovery")
            .node("fetchCreditScore")
            .attempts(2)
            .returnValue(Map.of("score", 780))
            .add();
```

`occurrences(n)` selects the nth binding of one `invocationSiteId` and runtime correlation key.
This is useful when a nested graph is re-entered by a parent retry or loop. A retry appends a new
`AttemptTrace` inside the current occurrence; it never increments occurrence. Parallel foreach
items normally have different correlation keys, so each item owns an independent occurrence series.

Selector candidates are frozen in descending specificity. Attempt/occurrence-constrained rules
therefore override an explicit general fallback for the same site. Same-precedence rules may share
a site only when preflight can prove them disjoint by coordinate, correlation, resource, canonical
input, or conflicting path equality. Overlapping peers return `CONTROL_PLAN_AMBIGUOUS` before any
operator runs. A coordinate gap does not silently call the real operator: normal `onUnmatched`
policy applies, which defaults to `FIXTURE_UNMATCHED`.

### 4.2a Capture a governed replay payload

Replay data is never accepted as caller-supplied return JSON. Capture one exact successful node
attempt from the signed, detached visual run payload vault:

```http
PUT /api/testing/replay-payloads/orders-approved
Authorization: Bearer <verified workload credential>
X-Purpose: TEST_REPLAY
Content-Type: application/json

{
  "schemaVersion": "bloge.replayPayloadCaptureRequest.v1",
  "revision": 1,
  "source": {
    "runId": "run-2026-07-16-001",
    "nodeId": "fetchOrder",
    "attempt": 1,
    "runEvidenceFingerprint": "sha256:<64 lowercase hex>",
    "payloadFingerprint": "sha256:<64 lowercase hex>"
  },
  "classification": "CONFIDENTIAL",
  "expiresAt": "2026-07-23T00:00:00Z"
}
```

Capture succeeds only when source run and payload scope match the verified identity, both
fingerprints match, the run and payload lifecycle signatures verify, the selected attempt is
`SUCCESS`, clearance/group policy passes, classification is not downgraded, neither sanitizer
truncated data, and destination expiry is within both source retention and server policy.

The response contains an exact reference such as:

```text
bloge-replay:orders-approved@1#sha256:<64 lowercase hex>
```

Query it with `GET /api/testing/replay-payloads/orders-approved?revision=1` and
`X-Purpose: TEST_REPLAY`. Retention sweeps physically remove the value but preserve an `EXPIRED`
payload-free tombstone. The captured object contains only the selected sanitized output; historical
request data, credentials, and side-effect outcomes are not copied or replayed.

### 4.2b Execute a governed replay fixture

Put the exact reference returned by capture into a node-boundary fixture rule. The caller may not
also provide `value`, protocol response, fault, delay, or sequence fields, and both unmatched and
exhausted policies must remain `FAIL`:

```json
{
  "schemaVersion": "bloge.fixtureRule.v1",
  "ruleId": "replay-approved-order",
  "selector": {"graphPath": "/root", "nodeId": "fetchOrder"},
  "behavior": {
    "kind": "REPLAY",
    "boundary": "NODE",
    "replayRef": "bloge-replay:orders-approved@1#sha256:<64 lowercase hex>"
  },
  "consumption": {
    "required": true,
    "minUses": 1,
    "maxUses": 1,
    "onExhausted": "FAIL",
    "onUnmatched": "FAIL"
  },
  "schemaCheck": {"mode": "STRICT", "waiverReason": ""}
}
```

Registering a fixture containing `REPLAY`, executing a graph/operator with it, or executing a suite
that depends on it requires `X-Purpose: TEST_REPLAY`. Ordinary fixtures continue to use their
existing purposes. Resolution happens before planning: every exact ref is rechecked for scope,
lifecycle, clearance, classification, immutable fingerprint, and descriptor/value integrity; then
canonical JSON is frozen into a run-scoped internal object. The planner and runtime receive no
repository handle, and a missing, expired, purged, changed, oversized, or unauthorized dependency
fails before any graph node is scheduled.

`bloge.effectiveExecutionPlan.v3` retains the v2 payload-free replay identity and source lineage in
`replayDependencies`; the payload itself is never embedded in the plan or plan fingerprint
material. V3 also freezes all run-scoped ambient-authority bindings in
`executionServiceBindings`: service kind, effective provider mode, availability, determinism,
configuration fingerprint, declared consumers, and certification gaps. The binding never exports
the raw clock or seed; evidence may separately expose governed logical timestamps as execution
facts. Runtime materializes a fresh replay value per invocation, validates it with BLOGE's declared
operator output schema, returns it without invoking the real operator, and emits node/attempt
status `MOCKED`, fidelity `REPLAYED`, and control mode `REPLAY`. There is no fallback-to-real path.

When a fixture supplies `logicalClock`, BLOGE scheduler time, operator `timeSource()` and
environment-dependent time functions share one advancing zero-wall-clock provider. `randomSeed`
drives domain-separated SHA-256 sequences for RANDOM and UUID. Calls with the same stable scope
and occurrence sequence reproduce across runs; different scopes do not consume a global shared
cursor. Missing TIME/RANDOM/UUID controls are allowed for exploratory runs but downgrade evidence
when a declared or observed semantic consumer uses them. IDENTITY, FEATURE_FLAG and SECRET have
no fixture authority in this increment and always fail closed. Evidence metadata records only
payload-free usage counts, function call sites and hashed provider scopes.

One run may resolve at most 1,000 distinct replay refs and 16 MiB of canonical frozen payloads.
A replay captured from a non-executable draft or otherwise non-certifiable source makes the whole
run `EXPLORATORY`; it cannot be upgraded by storing the fixture. A signed immutable executable
publication source may remain certification-eligible when every other certification gate passes.

Capability probe reports both `governedTestReplayPayloadCapture=true` and
`testReplayBehavior=true`. It advertises effective-plan v1/v2 for readers and v3 as the current
producer contract.

### 4.2c Execution-service state checkpoint protocol

Capability discovery advertises `executionServiceStateSnapshot` version
`bloge.executionServiceStateSnapshot.v1`. The content-addressed object carries:

- exact `planFingerprint` and `bindingSetFingerprint`;
- current governed logical time;
- next RANDOM/UUID occurrence per hashed scope;
- cumulative provider/function usage;
- derived `restorable` and bounded `restoreGaps`;
- `snapshotFingerprint` over all preceding fields.

It never carries a random seed, raw provider scope, fixture payload, identity/flag/secret value, or
runtime repository handle. Capture excludes concurrent provider mutation. Restore first performs
normal preflight and independently recompiles the effective plan, then recomputes fingerprint,
binding, restore policy, and deterministic cursor/usage closure. Any mismatch maps to
`CONTROL_PLAN_UNAVAILABLE`; there is no fallback to latest configuration, system providers, or REAL
execution.

This state object remains an internal planner/runtime protocol. The public surface exposes narrow,
authenticated graph and operator durable-run creators plus a payload-free checkpoint query and the owner-claim,
recovery-heartbeat, and one-signal terminal-recovery controls described below. It does not expose a
runtime-state dispatcher or general multi-boundary BLOGE resume endpoint. It now exposes a bounded,
non-blocking worker pull that acquires only a payload-free recovery fence; BLOGE state remains
server-side. The
profile-gated staged BLOGE stores persist it inside
`bloge.durableTestExecutionCheckpoint.v2`, which also binds the immutable plan, exact fixture,
fixture-consumption cursors, execution/wait/work-item closure, side-effect policy, authority snapshot,
owner fence, and exact `{kind,id,fingerprint}` graph/operator locator. The locator fingerprint must
equal the plan target fingerprint and its kind must agree with the server-authorized execution
purpose. Database reads independently compare its projected columns with the sealed JSON.

Legacy `bloge.durableTestExecutionCheckpoint.v1` remains canonically readable but has no target
locator and cannot enter a future public recovery path until an independently verified migration
supplies one. Neither checkpoint fingerprint is an authenticity proof; cross-process adapters must
accept these objects only from the trusted fenced store or a signed checkpoint attestation. Public
resume still requires authenticated scope binding and live reauthorization of target, fixture,
replay, authority, and side-effect policy before BLOGE state restoration.

### 4.2d Create one durable graph execution

The profile-gated creator accepts only authenticated `TEST_EXECUTION` or `TEST_REPLAY` callers and
only creates an exact `GRAPH_CONTRACT_TEST` from an immutable stored fixture revision:

```http
POST /api/testing/durable-executions
Authorization: Bearer <workload-token>
X-Purpose: TEST_EXECUTION
Content-Type: application/json
```

```json
{
  "schemaVersion": "bloge.durableTestExecutionCreateRequest.v1",
  "clientRequestId": "create-approval-flow-20260717-01",
  "target": {
    "kind": "GRAPH",
    "id": "approvalFlow",
    "fingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  },
  "executionPurpose": "GRAPH_CONTRACT_TEST",
  "context": {
    "requestId": "REQ-20260717-42",
    "amount": 25000,
    "region": "SG"
  },
  "fixtureBundleRef": {
    "fixtureBundleId": "approval-fixture",
    "revision": 3,
    "fingerprint": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  }
}
```

The graph fingerprint and fixture fingerprint are mandatory; `latest`, inline fixtures, operator
targets, caller-selected owner/lease values, control-plan fields, and control keys hidden inside
`context` are rejected. The service re-authorizes the exact graph, fixture, replay closure,
workload-identity authority, side-effect policy, deterministic providers, and compiled plan before it
reserves any execution authority. Business context is bounded to 1 MiB and is used only by the staged
engine invocation; it is never copied into the creation command, response, or semantic audit.

Creation v1 succeeds only when a fresh BLOGE execution becomes durably quiescent at exactly one live
`WAIT_SIGNAL`. The service then commits the revision-zero `SUSPENDED` checkpoint, complete staged
execution/checkpoint/wait/work-item aggregate, immutable command result, and security audit in one
local transaction. Terminal completion, pause, timer/task/stream waits, non-restorable provider state,
or multiple live suspensions are persisted as an immutable payload-free rejection and return `409`;
the service never guesses which boundary should be recoverable.

The success envelope is `bloge.durableTestExecutionCreateResponse.v1`. Its nested `execution` is the
same payload-free `bloge.durableTestExecutionView.v1` returned by the query below, initially with
`status=SUSPENDED`, `revision=0`, `recoverable=true`, and the server-minted `runId` and
`engineExecutionId`:

```json
{
  "schemaVersion": "bloge.durableTestExecutionCreateResponse.v1",
  "execution": {
    "schemaVersion": "bloge.durableTestExecutionView.v1",
    "runId": "4e6ea66d-1c39-4ca8-b670-11a846dfab30",
    "engineExecutionId": "engine-43478e5c-18a8-4780-ad5b-329fab8303db",
    "status": "SUSPENDED",
    "fence": {"ownerId": "durable-create-a", "leaseEpoch": 1, "revision": 0},
    "leaseExpiresAt": "2026-07-17T12:02:00Z",
    "target": {
      "kind": "GRAPH",
      "id": "approvalFlow",
      "fingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    },
    "fixture": {
      "fixtureBundleId": "approval-fixture",
      "revision": 3,
      "fingerprint": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    },
    "authorizedPurpose": "GRAPH_CONTRACT_TEST",
    "sideEffectPolicy": "DENY_REAL",
    "planFingerprint": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
    "executionServiceStateFingerprint": "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
    "fixtureConsumptionStateFingerprint": "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
    "engineBoundary": {
      "checkpointRef": "initial-4e6ea66d-1c39-4ca8-b670-11a846dfab30",
      "nodeId": "approval-wait",
      "boundaryType": "SUSPEND",
      "boundarySequence": 1,
      "stateVersion": 2,
      "closureFingerprint": "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
    },
    "checkpointFingerprint": "sha256:1111111111111111111111111111111111111111111111111111111111111111",
    "createdAt": "2026-07-17T12:00:00Z",
    "updatedAt": "2026-07-17T12:00:00Z",
    "recoverable": true,
    "migrationRequired": false
  },
  "idempotentReplay": false
}
```

`clientRequestId` is scoped by tenant and environment and fingerprints the complete authenticated
intent. A retry after a committed success or deterministic rejection replays that immutable outcome
before mutable dependencies are read again. A concurrent retry while the database-time preparation
lease is live returns payload-free `409 RG.TEST.DURABLE_CREATE_IN_PROGRESS` with `runId` and
`leaseExpiresAt`. After expiry, another instance may fence the abandoned preparer, retain the same
run/engine identities, and retry only when the caller resubmits the same request.

The service automatically heartbeats each acquired preparation reservation while the isolated graph
run is in progress. Renewal is an internal server protocol, not a caller endpoint: it requires the
exact live `PENDING` owner, epoch, and record fingerprint; reads database time; preserves scope,
authorization, run, engine, owner, and epoch; and rotates only update time, lease deadline, and the
successor record fingerprint. Commit or deterministic rejection first freezes renewal and waits for
any in-flight heartbeat, then uses only that latest successor. A failed heartbeat or service shutdown
makes ownership uncertain, discards staged BLOGE state, and returns payload-free
`409 RG.TEST.DURABLE_CREATE_LEASE_LOST` with `runId`. The server-owned lease defaults to 120 seconds
and accepts whole seconds from 3 through 3600. The heartbeat interval defaults to `0`, meaning derive
one third of the lease, and an explicit value must be a whole second no greater than one third of the
lease. This does not forcibly cancel an uncooperative in-process operator; hard wall-clock deadlines
still require a killable worker process or container plus lease fencing.

### 4.2e Create one durable operator execution

Durable operator creation has its own immutable request contract instead of widening the graph v1
schema in place. Discover the exact synchronous binding and publish an immutable fixture revision for
that operator fingerprint first, then submit:

```http
POST /api/testing/durable-executions/operators/{operatorRef}
Authorization: Bearer <workload-token>
X-Purpose: TEST_EXECUTION
Content-Type: application/json
```

```json
{
  "schemaVersion": "bloge.durableOperatorTestExecutionCreateRequest.v1",
  "clientRequestId": "create-credit-score-20260717-01",
  "target": {
    "kind": "OPERATOR",
    "id": "creditScore",
    "fingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  },
  "executionPurpose": "OPERATOR_UNIT_TEST",
  "input": {
    "customerId": "C-42",
    "annualIncome": 180000
  },
  "fixtureBundleRef": {
    "fixtureBundleId": "credit-score-fixture",
    "revision": 4,
    "fingerprint": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  }
}
```

The path and body target must match exactly. The target fingerprint, stored fixture revision and
fingerprint, and `OPERATOR_UNIT_TEST` purpose are mandatory. Inline/latest fixtures, caller-owned
context, owner/lease fields, and test-control keys hidden in the formal input are rejected. Input is
bounded to 1 MiB. After exact binding verification, the server converts it with the frozen operator
metadata and stores the converted value under the server-owned `operatorInput` context key. It then
creates the canonical `durable-operator-test:{operatorRef}` BLOGE graph: the source is the read-only,
idempotent `durable-operator-start` gate and the exact `subject` binding runs only after that gate.
Callers cannot inject either internal node or the context key.

Committed and rejected outcomes use the same tenant/environment-scoped durable command namespace as
graph creation. A retry is therefore replayed before the mutable operator registry or fixture store is
read, while reuse of the same `clientRequestId` for a different graph/operator intent returns the
existing idempotency conflict. Fresh work enters the same four-dimensional admission gate,
database-time preparation lease, staged execution/checkpoint/wait/work-item aggregate, revision-zero
checkpoint commit, and transaction-bound audit path. The response remains
`bloge.durableTestExecutionCreateResponse.v1`, with `target.kind=OPERATOR` and
`authorizedPurpose=OPERATOR_UNIT_TEST`; it never contains raw or converted input.

Fresh creation always reaches exactly one server-owned signal suspension at
`durable-operator-start`; `subject` has not been invoked when revision zero commits. A later terminal
recovery must signal that exact gate. The gate ignores signal data, then the subject consumes the
already-persisted formal input and executes with the same frozen binding, fixture cursor, provider
state, authority, side-effect policy, admission permit, and lease fence. The isolated runtime proves
that a cold recovery invokes the subject exactly once. As with graph recovery, ambiguous response
replay returns the committed terminal result without reapplying the signal or operator mutation.

The internal gate is included in the compiled invocation inventory and therefore consumes a
conservative operator admission slot alongside `subject`; caller fixtures must not target it. This
capability does not add multi-boundary orchestration, hard cancellation, or complete
pre-checkpoint trace evidence.

### 4.2f Query one durable execution

The profile-gated read endpoint accepts only authenticated `TEST_EXECUTION` or `TEST_REPLAY`
purposes:

```http
GET /api/testing/durable-executions/{runId}
Authorization: Bearer <workload-token>
X-Purpose: TEST_EXECUTION
```

It resolves the checkpoint by verified tenant/environment, then independently requires the caller's
organization and project to match. Missing and cross-scope values both return the same `404`. The
repository verifies the sealed checkpoint, every nested fingerprint, and indexed projections before
the service constructs `bloge.durableTestExecutionView.v1`; corruption or store failure returns a
payload-free `503`.

```json
{
  "schemaVersion": "bloge.durableTestExecutionView.v1",
  "runId": "run-42",
  "engineExecutionId": "engine-42",
  "status": "SUSPENDED",
  "fence": {"ownerId": "worker-a", "leaseEpoch": 1, "revision": 7},
  "leaseExpiresAt": "2026-07-17T12:02:00Z",
  "target": {
    "kind": "GRAPH",
    "id": "approvalFlow",
    "fingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  },
  "fixture": {
    "fixtureBundleId": "approval-fixture",
    "revision": 3,
    "fingerprint": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  },
  "authorizedPurpose": "GRAPH_CONTRACT_TEST",
  "sideEffectPolicy": "DENY_REAL",
  "planFingerprint": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
  "executionServiceStateFingerprint": "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
  "fixtureConsumptionStateFingerprint": "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
  "engineBoundary": {
    "checkpointRef": "checkpoint-7",
    "nodeId": "approval-wait",
    "boundaryType": "SUSPEND",
    "boundarySequence": 2,
    "stateVersion": 7,
    "closureFingerprint": "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
  },
  "checkpointFingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "createdAt": "2026-07-17T12:00:00Z",
  "updatedAt": "2026-07-17T12:01:00Z",
  "recoverable": true,
  "migrationRequired": false
}
```

The response omits context, fixture values, replay content, provider cursors, authority values,
credentials, hidden dispatches, and engine checkpoint bodies. A legacy v1 checkpoint is readable for
operations but has no `target`, sets `migrationRequired=true`, and is never reported recoverable. The
query is an observation, not a lease reservation or bearer capability; callers must still submit the
entire returned fence to owner claim, which rechecks live state and current authorization.

### 4.2g Pull one durable worker assignment

Workers that do not know a run id use the profile-gated, authenticated non-blocking pull endpoint:

```http
POST /api/testing/durable-executions/worker-acquisitions
Authorization: Bearer <workload-token>
X-Purpose: TEST_EXECUTION
Content-Type: application/json

{
  "schemaVersion": "bloge.durableTestWorkerAcquisitionRequest.v1",
  "clientRequestId": "poll-worker-a-000042"
}
```

The verified principal exclusively determines tenant, organization, project, and environment.
Caller-owned run ids, queue filters, owner ids, lease durations, priorities, and candidate limits are
unknown fields and fail closed. The repository uses its database clock and an indexed SQL query to
select at most `RG_TEST_DURABLE_WORKER_CANDIDATE_LIMIT` expired `ACTIVE`, `SUSPENDED`, or `RESUMING`
v2 candidates from a persisted cyclic `(leaseExpiresAt, updatedAt, runId)` keyset position; default
`32`, valid `1..1000`. Cursor plus tail/head reads use one database-clock `REPEATABLE_READ` snapshot.
Each candidate is integrity-verified and freshly re-authorized before exact fence CAS. Authorization
denials (`403`), exact conflicts (`409`), and legacy/target-less checkpoints are deterministic
ineligibility reasons. A winning scan records a database-timed negative scheduling cache for the
exact checkpoint fingerprint. Active records skip re-authorization while the cyclic scan still
advances; a due repeat doubles the delay from
`RG_TEST_DURABLE_WORKER_INITIAL_BACKOFF_SECONDS` to the bounded
`RG_TEST_DURABLE_WORKER_MAXIMUM_BACKOFF_SECONDS` cap. Dependency-store or authority outages and all
other infrastructure failures commit neither a result, cursor progress, nor a deferral.

When a due observation reaches `RG_TEST_DURABLE_WORKER_QUARANTINE_THRESHOLD` consecutive failures
for the same reason and exact checkpoint fingerprint, the winning cursor transaction inserts a
whole-record-fingerprinted quarantine and removes the temporary deferral. Passage of time never
makes that closure worker-eligible again. Quarantined candidates remain visible to cyclic progress
but skip both dependency authorization and worker claim. The repository rechecks quarantine inside
the claim transaction, so a stale or defective service-layer selection cannot bypass it. A fenced
checkpoint transition clears scheduling state for the old fingerprint. The active quarantine is an
internal dead-letter state; list/claim/release and immutable remediation receipts are not part of
this protocol version. The explicit run-targeted owner-claim command remains a separate,
authenticated recovery path rather than an implicit worker retry.

The first successful transaction performs lease CAS, issues the hidden authorization-bound dispatch,
stores the immutable acquisition result, and appends the semantic audit atomically. If the bounded
window has no claimable candidate, the same transaction stores `NO_WORK` with database observation
time and audit. The transaction also compare-and-advances the cursor through the last candidate
actually examined. A stale concurrent token is a no-op and cannot regress a newer cursor. Cursor
lookup uses a derived scope key and verifies all scope/position projections against a whole-record
fingerprint, so projection drift cannot silently reset the scan. Both outcomes are immutable under
the scoped `clientRequestId`; a retry after a lost response receives the original result before a
new scan. A stale concurrent cursor token cannot create or amplify a deferral. Checkpoint replacement,
successful claim, and ordinary checkpoint update clear the old fingerprint's record. A later
observation must use a new key.

```json
{
  "schemaVersion": "bloge.durableTestWorkerAcquisitionResponse.v1",
  "outcome": "ACQUIRED",
  "observedAt": "2026-07-17T12:00:00Z",
  "assignment": {
    "runId": "run-42",
    "status": "RESUMING",
    "ownerId": "server-issued-owner",
    "leaseEpoch": 2,
    "revision": 11,
    "leaseExpiresAt": "2026-07-17T12:02:00Z",
    "checkpointFingerprint": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    "target": {
      "kind": "GRAPH",
      "id": "credit-score",
      "fingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
  },
  "idempotentReplay": false
}
```

`NO_WORK` has `assignment: null`. Neither shape contains dispatch, authorization, fixture/replay
payload, provider cursor, engine checkpoint, context, or credential. This endpoint acquires recovery
ownership only. It does not hold an admission permit while idle, execute BLOGE remotely, long-poll,
guarantee bounded waiting under unbounded churn, provide tenant weighting/priority/aging, quarantine
unrecoverable candidates permanently, provide dead-letter/manual remediation, cancel work, or
supervise worker liveness. The cyclic cursor prevents a stable poison prefix from causing permanent
starvation and deterministic candidate backoff reduces repeated authority load; neither is a general
scheduler. Persistence, SLO, and counterexample semantics are specified in
[Stage 4 worker candidate backoff verification](resource-gateway-execution-data-control-plane-stage4-worker-candidate-backoff-verification.md).

### 4.2h Claim, renew, and terminally recover an exact durable fence

The three public recovery-control commands exist only under `test` or `staging` and require
`TEST_EXECUTION` or `TEST_REPLAY` purpose:

```http
POST /api/testing/durable-executions/{runId}/owner-claims
POST /api/testing/durable-executions/{runId}/heartbeats
POST /api/testing/durable-executions/{runId}/recovery-steps
POST /api/testing/durable-executions/{runId}/recovery-sequences
POST /api/testing/durable-executions/{runId}/terminal-recoveries
Authorization: Bearer <workload-token>
X-Purpose: TEST_EXECUTION
Content-Type: application/json
```

Owner claim accepts an expired v2 checkpoint fence, re-authorizes its exact target, fixture, replay,
identity authority, side-effect policy, deterministic provider state, and effective plan, then moves
it to `RESUMING`. The server chooses the owner and lease. Its payload-free response is the only public
input needed for the first heartbeat.

```json
{
  "schemaVersion": "bloge.durableTestRecoveryHeartbeatRequest.v1",
  "clientRequestId": "heartbeat-run-42-1",
  "expectedFence": {
    "ownerId": "server-issued-owner",
    "leaseEpoch": 2,
    "revision": 11
  },
  "expectedCheckpointFingerprint": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
}
```

The heartbeat adapter uses the exact fence to retrieve the unique committed internal dispatch. It
does not accept a dispatch, authorization receipt, owner, expiry, or lease duration from the caller.
The current authenticated principal must exactly match the principal authorized by owner claim:
tenant, organization, project, environment, region, actor, delegation, purpose, clearance, and sorted
groups are covered. Correlation id is deliberately excluded so a response-loss retry remains possible.

The database clock verifies that the resolved dispatch still owns the exact live `RESUMING` fence.
The first commit advances one revision, extends the server-owned lease, issues a hidden successor
dispatch, stores the immutable heartbeat record, and appends the `ALLOWED` semantic audit in the same
local transaction. The response exposes only the successor fence:

```json
{
  "schemaVersion": "bloge.durableTestRecoveryHeartbeatResponse.v1",
  "runId": "run-42",
  "status": "RESUMING",
  "ownerId": "server-issued-owner",
  "leaseEpoch": 2,
  "revision": 12,
  "leaseExpiresAt": "2026-07-17T12:02:00Z",
  "checkpointFingerprint": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
  "idempotentReplay": false
}
```

Use the successor fence for the next renewal and a new key for that new intent. Retry an ambiguous
response with the original key and intent to receive the exact committed successor with
`idempotentReplay=true`. Stale/expired/unissued fences and same-key intent drift return stable,
payload-free failures; cross-scope lookup is hidden as not found; store or audit outage returns
unavailable. `RG_TEST_DURABLE_HEARTBEAT_LEASE_SECONDS` owns the renewal duration (default `120`, whole
seconds in `3..3600` for the assembled synchronous terminal worker). The process-local worker derives
an interval of one third of that lease; `RG_TEST_DURABLE_RECOVERY_HEARTBEAT_INTERVAL_SECONDS` may
override it with a whole-second value from `1` through one third of the lease. This protocol keeps an
already claimed fence alive. It does not discover work, execute or cancel BLOGE, publish terminal
evidence, or make cold-start durable resume complete.

### 4.2h.1 Advance one suspended-or-terminal recovery step

Graphs with more than one signal boundary use the latest exact owner-claim or heartbeat fence:

```json
{
  "schemaVersion": "bloge.durableTestRecoveryStepRequest.v1",
  "clientRequestId": "step-run-42-1",
  "expectedFence": {
    "ownerId": "server-issued-owner",
    "leaseEpoch": 2,
    "revision": 12
  },
  "expectedCheckpointFingerprint": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
  "signal": {
    "nodeId": "approval-wait",
    "data": {"approved": true}
  }
}
```

The request is strict at every nesting level. Outcome, engine/fixture/provider state, lease expiry,
dispatch, authorization, evidence, receipt, and omitted `signal.data` are rejected before service
entry. Immutable replay is resolved before dispatch lookup, authorization, admission, heartbeat, or
engine execution. A first attempt requires the same principal and freshly reconstructed dependency
closure as the issued dispatch, acquires the same database-authoritative runtime permit, then runs
one signal under automatic lease renewal.

The repository atomically commits the staged four-store BLOGE mutation, cumulative fixture/provider
state, next checkpoint, immutable command result, semantic audit, and optional terminal receipt. A
new suspension releases ownership with database authority time; the old dispatch cannot cross that
boundary. The payload-free suspended response is:

```json
{
  "schemaVersion": "bloge.durableTestRecoveryStepResponse.v1",
  "runId": "run-42",
  "outcome": "SUSPENDED",
  "status": "SUSPENDED",
  "ownerId": "server-issued-owner",
  "leaseEpoch": 2,
  "revision": 13,
  "observedAt": "2026-07-17T12:01:18Z",
  "checkpointFingerprint": "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
  "boundary": {
    "nodeId": "second-approval-wait",
    "boundaryType": "SUSPEND",
    "boundarySequence": 8,
    "stateVersion": 13
  },
  "terminal": null,
  "idempotentReplay": false
}
```

For `COMPLETED`, `FAILED`, `FAILED_RECOVERY`, `CANCELLED`, or `TERMINATED`, `status` is `TERMINAL`
and `terminal` contains completion time, receipt fingerprint, `EVIDENCE_INCOMPLETE`, and explicit
gap codes. Signal data and internal state never appear. To continue after `SUSPENDED`, a worker must
acquire and freshly authorize the new checkpoint, then use a new key for the next signal. This is a
one-step durable primitive, not a queued signal broker, remote supervisor, hard cancellation system,
or complete historical evidence service.

### 4.2h.2 Advance a bounded recovery sequence

When a test fixture already knows several ordered signal values, the sequence endpoint removes the
manual claim/step choreography while preserving the same per-boundary authorization and atomicity:

```json
{
  "schemaVersion": "bloge.durableTestRecoverySequenceRequest.v1",
  "clientRequestId": "sequence-run-42-1",
  "expectedFence": {
    "ownerId": "server-issued-owner",
    "leaseEpoch": 2,
    "revision": 12
  },
  "expectedCheckpointFingerprint": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
  "signals": [
    {"nodeId": "risk-approval", "data": {"approved": true}},
    {"nodeId": "finance-approval", "data": {"approved": true}}
  ]
}
```

The request accepts 1 through 16 signals, no more than 256 KiB each and 1 MiB in total. Every
nesting level rejects unknown fields. Before executing signal zero, the server atomically stores a
payload-free reservation containing the authenticated scope, run, signal count, complete request
fingerprint, database time, record fingerprint, and companion semantic audit. It never stores the
signal program itself. Reusing the outer key with a changed initial fence, node, signal value,
ordering, signal count, run, or principal fails before any child command executes, including when
only a late, not-yet-consumed signal changed.

The orchestrator derives stable child keys from tenant, environment, and the outer key. It invokes
the ordinary atomic recovery-step service for each signal. After every `SUSPENDED` child result and
before the next signal, it invokes the ordinary owner-claim service against the exact released
checkpoint. That claim freshly reconstructs authorization and issues a new hidden dispatch; an old
dispatch never crosses a suspension. If the HTTP response is lost after any prefix committed, the
unchanged outer retry replays the reservation, child steps, and claims from index zero, then
continues at the first uncommitted child without applying a signal twice.

```json
{
  "schemaVersion": "bloge.durableTestRecoverySequenceResponse.v1",
  "runId": "run-42",
  "outcome": "COMPLETED",
  "status": "TERMINAL",
  "stopReason": "TERMINAL",
  "providedSignalCount": 2,
  "consumedSignalCount": 2,
  "steps": [
    {
      "schemaVersion": "bloge.durableTestRecoveryStepResponse.v1",
      "runId": "run-42",
      "outcome": "SUSPENDED",
      "status": "SUSPENDED",
      "ownerId": "server-issued-owner",
      "leaseEpoch": 2,
      "revision": 13,
      "observedAt": "2026-07-17T12:01:18Z",
      "checkpointFingerprint": "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
      "boundary": {
        "nodeId": "finance-approval",
        "boundaryType": "SUSPEND",
        "boundarySequence": 8,
        "stateVersion": 13
      },
      "terminal": null,
      "idempotentReplay": false
    },
    {
      "schemaVersion": "bloge.durableTestRecoveryStepResponse.v1",
      "runId": "run-42",
      "outcome": "COMPLETED",
      "status": "TERMINAL",
      "ownerId": "server-issued-owner",
      "leaseEpoch": 3,
      "revision": 15,
      "observedAt": "2026-07-17T12:01:31Z",
      "checkpointFingerprint": "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
      "boundary": {
        "nodeId": "complete",
        "boundaryType": "NODE_BOUNDARY",
        "boundarySequence": 9,
        "stateVersion": 14
      },
      "terminal": {
        "executionOutcome": "COMPLETED",
        "completedAt": "2026-07-17T12:01:31Z",
        "receiptFingerprint": "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        "evidenceStatus": "EVIDENCE_INCOMPLETE",
        "evidenceGapCodes": [
          "PRE_CHECKPOINT_TRACE_UNAVAILABLE",
          "RECOVERY_SIGNAL_PAYLOAD_OMITTED"
        ]
      },
      "idempotentReplay": false
    }
  ],
  "idempotentReplay": false
}
```

The response contains one ordered `steps` entry per consumed signal. `stopReason=TERMINAL` may consume fewer than
the provided count when the graph terminates early. `stopReason=SIGNALS_EXHAUSTED` means every
provided signal committed but the graph reached another suspension. No signal, dispatch,
authorization, fixture/provider state, engine body, or lease expiry appears in the response.

#### Recovery-sequence replay and retention lifecycle

The exact-response replay window is finite and absolute. By default, the outer sequence reservation
and every server-derived step, intermediate owner claim, and automatic recovery heartbeat can be
replayed for 30 days from first reservation. Each request accepted before that deadline advances a
separate whole-record-fingerprinted `activityUntil/revision` fence for one command window. The fence
prevents retention from deleting a sequence while that accepted request can still write a child;
it does not extend the absolute replay deadline. A scheduled page is elected by a database-clock
lease. In one local transaction it:

1. selects at most `retention-page-size` expired outer reservations in stable database order;
2. integrity-verifies the outer reservation and every derived child row before deletion;
3. inserts a tenant/environment-bound, domain-separated HMAC request tombstone without retaining
   the plaintext `clientRequestId`;
4. deletes the exact verified child and outer records by whole-record fingerprint;
5. independently verifies and purges at most one page of expired tombstones; and
6. advances aggregate counters and releases the fenced lease.

The retention query locks selected outer rows and requires both the absolute deadline and activity
fence to have elapsed. Replay uses the same row lock: whichever transaction wins forces the other
to observe either a new activity fence or the tombstone. Schema upgrade rows without a v1 activity
fingerprint are initialized and deferred for one grace window before they become deletion eligible.
Any corrupt, missing-key, changed-fingerprint, or stale-lease condition rolls back the entire page.
The page has independent bounds for outer records and expired tombstones; automatic-heartbeat
fanout is additionally capped at 4,096 per sequence and fails closed above that bound. The initial
owner claim supplied before sequence orchestration is not sequence-derived and is deliberately
outside this deletion set.

At the absolute deadline, unchanged intent returns stable
`409 RG.TEST.DURABLE_RECOVERY_SEQUENCE_REPLAY_WINDOW_EXPIRED`. Changed intent under the same
request identity remains an idempotency conflict, including while physical erasure is waiting for an
in-flight fence or the next scheduled page. After tombstone expiry, that identity may be reused.
This is a deliberate finite replay contract: callers that require longer exact replay must configure
a longer command window before execution, up to 3,650 days, or retain their own external evidence.
The same window must exceed the maximum supported synchronous sequence wall time. Minimums are one
hour for command detail and one day for tombstones; page size is `1..1000`.

New tombstones use only the active request-index key generation; lookup tries the bounded active-first
key ring. Because plaintext request ids are erased, an old tombstone cannot be re-keyed. Operators
must add the new key to every live replica, verify fleet rollout, switch the active generation on
every replica, retain old verification keys through their last tombstone expiry, and only then
remove them. There is no built-in cohort proof for this key ring, so deployment orchestration must
not permit a replica with an incomplete ring to serve during the active-key switch. Repository
startup refuses an unavailable referenced generation. Key material, tenant, run, request, payload,
and error text are absent from retention logs and metrics. Capability discovery advertises
`durableRecoverySequenceRetention` only when the test-runtime control plane is assembled.

The same profile owns a dedicated fail-closed Actuator health indicator. It observes a single
repeatable-read snapshot whose authority time comes from the database, not the application host.
The sequence backlog count includes only rows whose absolute replay-retention deadline and
activity fence have both elapsed. Its oldest age starts at the true eligibility instant
`max(createdAt + commandRetention, activityUntil)`, so a newly eligible 30-day record does not
appear 30 days stale. Tombstone age starts at persisted expiry. The state mapping is:

| State | Actuator status | Meaning |
|---|---|---|
| `HEALTHY` | `UP` | last committed page is fresh and both backlog policies pass |
| `INITIALIZING` | `UNKNOWN` | no page has committed, but startup grace has not elapsed |
| `SLO_VIOLATED` | `OUT_OF_SERVICE` | one or more freshness, count, or age policies fail |
| `STORE_UNAVAILABLE` | `DOWN` | no trustworthy aggregate database snapshot could be read |

Stable violation codes are `RETENTION_NEVER_SUCCEEDED`, `RETENTION_STALE`,
`SEQUENCE_RETENTION_BACKLOG_EXCEEDED`, `SEQUENCE_RETENTION_BACKLOG_STALE`,
`TOMBSTONE_PURGE_BACKLOG_EXCEEDED`, `TOMBSTONE_PURGE_BACKLOG_STALE`, and
`RETENTION_STORE_UNAVAILABLE`. Health and metrics expose only counts, durations, state, and these
closed codes; identities, payloads, key material, and exception text are excluded. Telemetry
failure never changes a successful health assessment, while store observation failure always
fails closed. Capability discovery separately advertises
`durableRecoverySequenceRetentionSloHealth`.

This endpoint is synchronous and bounded. It does not durably queue future signals, wait for a
signal that was not supplied, run BLOGE in another process, enforce a wall-clock process kill,
schedule fairly across tenants, supervise a remote worker, or preserve complete pre-checkpoint
trace evidence. Same-database deletion does not prove backup erasure, legal-hold compliance, or
external WORM retention. Those remain separate dispatcher, supervisor, hard-cancellation, evidence,
and governance requirements.

### 4.2h.3 Execute one terminal cold recovery

The terminal-recovery request consumes the latest exact fence returned by owner claim or heartbeat:

```json
{
  "schemaVersion": "bloge.durableTestTerminalRecoveryRequest.v1",
  "clientRequestId": "terminal-run-42-1",
  "expectedFence": {
    "ownerId": "server-issued-owner",
    "leaseEpoch": 2,
    "revision": 12
  },
  "expectedCheckpointFingerprint": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
  "signal": {
    "nodeId": "approval-wait",
    "data": {"approved": true}
  }
}
```

The strict contract requires an explicit `signal.data`; use JSON `null` when the BLOGE signal has no
value. Canonical signal JSON is limited to `256 KiB`. The service fingerprints it for idempotency but
never writes the raw value to its audit, checkpoint, terminal receipt, or response. Unknown top-level,
fence, or signal fields fail closed, preventing a caller from supplying terminal outcome, engine
state, fixture/provider state, evidence status, dispatch, authorization, or lease policy.

Response-loss replay is checked before dispatch lookup, reauthorization, and engine execution. On a
first attempt, the service resolves the unique issued dispatch from the trusted store, requires exact
tenant/organization/project/environment/fence/checkpoint agreement and principal continuity, loads
the live `RESUMING` checkpoint, then freshly rebuilds the graph or operator micro-graph, fixture,
replay closure, authority, side-effect policy, deterministic providers, and effective plan. The new
authorization receipt must equal the receipt inside the dispatch.

The isolated recovery session restores cumulative fixture cursors and provider state, applies the
signal synchronously, and accepts only a BLOGE terminal lifecycle. A second suspension returns
`RG.TEST.DURABLE_RECOVERY_NOT_TERMINAL` and closes the stage without changing committed state. For a
first attempt, a process-local lease guard synchronously renews the issued dispatch before opening
the runtime, then periodically rotates exact successors while BLOGE executes. It validates every
successor against the immutable authorization, target, fixture, provider, engine, owner, and epoch
closure. Before terminal commit it stops and joins renewal, then gives the newest successor dispatch
to the repository CAS. A conflict, store failure, malformed successor, or coordinator shutdown at
either boundary closes the staged runtime and returns retryable, payload-free
`RG.TEST.DURABLE_RECOVERY_LEASE_LOST`; no terminal mutation is attempted. For a terminal boundary,
the exact staged BLOGE mutation, final fixture/provider closure, terminal control
checkpoint, immutable idempotency result, transaction-bound semantic audit, and
`bloge.durableTestRecoveryTerminalReceipt.v1` commit atomically under database-time lease fencing.

```json
{
  "schemaVersion": "bloge.durableTestTerminalRecoveryResponse.v1",
  "runId": "run-42",
  "status": "TERMINAL",
  "executionOutcome": "COMPLETED",
  "ownerId": "server-issued-owner",
  "leaseEpoch": 2,
  "revision": 13,
  "completedAt": "2026-07-17T12:01:18Z",
  "terminalCheckpointFingerprint": "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
  "terminalReceiptFingerprint": "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
  "evidenceStatus": "EVIDENCE_INCOMPLETE",
  "evidenceGapCodes": [
    "PRE_CHECKPOINT_TRACE_UNAVAILABLE",
    "RECOVERY_SIGNAL_PAYLOAD_OMITTED"
  ],
  "idempotentReplay": false
}
```

Retry an ambiguous result with the same key, fence, node, signal, and principal. The original terminal
checkpoint and receipt return with `idempotentReplay=true`; the engine mutation is not executed again.
This endpoint is deliberately terminal-only and synchronous. It does not poll a queue, supervise a
separate worker process, accept multiple signals, enforce a hard process deadline, assemble complete
signed historical evidence, or turn Resource Gateway into a general durable worker runtime.

### 4.2i Operate the durable projection finding queue

The anti-entropy finding table cannot reliably prove tenant ownership, so these endpoints are not
ordinary tenant APIs. They require all of the following: `test` or `staging`, the exact
`TEST_RUNTIME_MAINTENANCE` purpose, the deployment-owned global operator group, and the configured
minimum clearance. A tenant-scoped identity without that explicit global role is rejected even if it
can run tests. For the local demo, opt in deliberately:

```bash
RG_INTEGRATION_GROUPS=resource-gateway-test-runtime-operators \
RG_INTEGRATION_CLEARANCE=RESTRICTED \
./scripts/start-visual-canvas-demo.sh --profile test
```

List payload-free actionable findings:

```bash
curl -sS 'http://localhost:8080/api/testing/durable-state/projection-findings?actionableOnly=true&limit=100' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_RUNTIME_MAINTENANCE'
```

Claim one exact finding. The request deliberately has no owner field; the service uses the verified
`actorId`. Save the returned `claimToken`, `version`, and `claimUntil` as one opaque fence:

```http
POST /api/testing/durable-state/projection-findings/claims
Authorization: Bearer <global-operator-token>
X-Purpose: TEST_RUNTIME_MAINTENANCE
Content-Type: application/json

{
  "schemaVersion": "bloge.durableStateProjectionFindingClaimRequest.v1",
  "clientRequestId": "claim-execution-a-1",
  "key": {"entityType": "EXECUTION", "rowId": "execution-a"},
  "claimDurationSeconds": 120
}
```

After repairing or quarantining the affected row, resolve with that exact fence:

```http
POST /api/testing/durable-state/projection-findings/resolutions
Authorization: Bearer <global-operator-token>
X-Purpose: TEST_RUNTIME_MAINTENANCE
Content-Type: application/json

{
  "schemaVersion": "bloge.durableStateProjectionFindingResolutionRequest.v1",
  "clientRequestId": "resolve-execution-a-1",
  "key": {"entityType": "EXECUTION", "rowId": "execution-a"},
  "claimToken": "<server-issued-token>",
  "claimVersion": 4,
  "claimUntil": "2026-07-17T12:02:00Z",
  "resolution": "QUARANTINED"
}
```

Exact retries return the original receipt with `idempotentReplay=true`; request-ID fact drift,
another live owner, and stale/forged/expired fences return stable 409 problems. Only a successful
claim response contains the token. Finding pages, resolution receipts, semantic action events,
integration access audits, logs, and problem responses omit it. On the first state transition, the
fenced finding update and append-only action event use the same local transaction; audit failure
rolls the state change back. Rejected and replay attempts receive separate append-only events.
Application code has no update/delete API for these events; external WORM anchoring remains a later
hardening item, so this is an application-level immutable audit rather than a storage certification.

Resolved rows do not remain in the owner queue forever. A separate profile-gated, database-leased
retention loop uses the database clock and two bounded pages per tick:

1. After `resolved-retention-days` (default `30`), it copies the resolved lifecycle to the internal
   projection-finding archive and deletes the exact source row in the same transaction.
2. After `archive-retention-days` from `archivedAt` (default `365`), it purges at most one archive
   page. Source archival and archive purge each use `retention-page-size` (default `100`, maximum
   `1000`), and the loop defaults to a one-hour fixed delay.

The archive carries only entity type/internal row ID, discrepancy kind, column names, repairability,
last outcome, counters, lifecycle timestamps, resolution, source revision, and a canonical record
fingerprint binding the archive identity and database-clock archive time. It never carries claim
owner/token, caller request IDs/fingerprints, resolution owner,
authority JSON, business values, or credentials. Reads recompute the fingerprint and fail closed on
drift. The fingerprint is not keyed and the archive remains in the same database, so this is bounded
operational history rather than tamper-evident external evidence. Once active retention elapses, the
ordinary finding endpoint no longer returns that lifecycle; no public archive endpoint is exposed in
v1.

Environment variables for the packaged test/staging profiles are:

| Variable | Default | Constraint |
|---|---:|---:|
| `RG_TEST_PROJECTION_FINDING_RESOLVED_RETENTION_DAYS` | `30` | `1..3650` days |
| `RG_TEST_PROJECTION_FINDING_ARCHIVE_RETENTION_DAYS` | `365` | `1..3650` days |
| `RG_TEST_PROJECTION_FINDING_RETENTION_PAGE_SIZE` | `100` | normalized to `1..1000` per phase |
| `RG_TEST_PROJECTION_FINDING_RETENTION_INTERVAL_MS` | `3600000` | positive scheduler fixed delay |

The loop reuses `gateway.testing.durable.projection-reconciliation.instance-id` and
`gateway.testing.durable.projection-reconciliation.lease-duration-seconds`, but has its own durable lease row; retention work
cannot advance or hold the anti-entropy keyset cursor.

The same profile installs `durableStateProjectionSloMonitor` as an Actuator health component. Its
source is one database transaction and one database timestamp, not replica wall clocks or logs. The
stable violation codes are:

- `RECONCILIATION_NEVER_SUCCEEDED` / `RECONCILIATION_STALE`;
- `RETENTION_NEVER_SUCCEEDED` / `RETENTION_STALE`;
- `UNRESOLVED_FINDING_LIMIT_EXCEEDED` / `UNRESOLVED_FINDING_AGE_EXCEEDED`;
- `RESOLVED_RETENTION_BACKLOG_EXCEEDED` / `ARCHIVE_PURGE_BACKLOG_EXCEEDED`;
- `PROJECTION_STORE_UNAVAILABLE`.

Safety/backlog violations override startup initialization. Store failures return `DOWN` with only
the stable code; exception messages are discarded. Micrometer records attempt counters and timers,
finding-state gauges, active/archive backlog, last-success age, and numeric health under
`resource.gateway.test.projection.*`. The only tag keys are the closed vocabularies `result`,
`state`, `tier`, and `loop`; tenant, row, operator, token, error, and payload labels are forbidden.
Only Actuator health is web-exposed by default. A deployment must explicitly secure and configure a
registry/exporter before exporting metrics, and should keep detailed health output authorized.

### 4.2j Operate exact-checkpoint worker quarantines

Permanent worker quarantine is tenant/project scoped, unlike the global projection-finding queue.
The service derives tenant, organization, project, environment, and the mutating owner from verified
identity. Access still requires `test` or `staging`, exact purpose `TEST_RUNTIME_MAINTENANCE`, and
minimum clearance. List, claim, release, discard, and history use the configured operator group;
discard approval uses a separate deployment-owned approver group. The checker repeats a payload-free
observed `claimOwner`, but the database treats it as an untrusted fence value and revalidates it.

List active payload-free records or token-free action history:

```bash
curl -sS 'http://localhost:8080/api/testing/durable-state/worker-quarantines?actionableOnly=true&limit=100' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_RUNTIME_MAINTENANCE'

curl -sS 'http://localhost:8080/api/testing/durable-state/worker-quarantines/history?limit=100' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_RUNTIME_MAINTENANCE'

curl -sS 'http://localhost:8080/api/testing/durable-state/worker-quarantines/approved-discards/history?limit=100' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_RUNTIME_MAINTENANCE'
```

`actionableOnly=true` returns `AVAILABLE` records and records whose previous claim has expired by
the database clock. Claim one exact `runId` plus checkpoint fingerprint with a caller-stable request
ID. The request has no owner or scope:

```http
POST /api/testing/durable-state/worker-quarantines/claims
Authorization: Bearer <maintenance-operator-token>
X-Purpose: TEST_RUNTIME_MAINTENANCE
Content-Type: application/json

{
  "schemaVersion": "bloge.durableWorkerQuarantineClaimRequest.v1",
  "clientRequestId": "claim-quarantine-run-a-1",
  "key": {
    "runId": "run-a",
    "checkpointFingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  },
  "claimDurationSeconds": 120
}
```

The successful response is the only public object containing `claimToken`. Treat `claimToken`,
`version`, and `claimUntil` as one opaque fence. `RELEASE` still uses the legacy-compatible
resolution endpoint with the exact fence and a closed, non-payload reason code. A newly submitted
direct `DISCARD` on that endpoint is rejected with
`RG.TEST.WORKER_QUARANTINE_DISCARD_APPROVAL_REQUIRED`; only an exact replay of a historically
committed legacy command remains replayable.

The exact-replay copy of `claimToken` is stored as an AES-256-GCM envelope bound to the command
identity. The live control stores only a key ID and domain-separated HMAC-SHA-256 verifier bound to
scope/run/checkpoint/owner/version/expiry; resolve and approved discard verify it in constant time.
`staging` refuses to start without an explicit active key and key ring. Rotation requires the
documented two-phase rollout; startup rewraps commands before re-keying active controls and refuses
missing/ambiguous recovery commands or unknown old keys. Upgrade and rotation details are in the
[claim-token protection verification](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-claim-token-protection-verification.md).

```http
POST /api/testing/durable-state/worker-quarantines/resolutions
Authorization: Bearer <maintenance-operator-token>
X-Purpose: TEST_RUNTIME_MAINTENANCE
Content-Type: application/json

{
  "schemaVersion": "bloge.durableWorkerQuarantineResolutionRequest.v1",
  "clientRequestId": "release-quarantine-run-a-1",
  "key": {
    "runId": "run-a",
    "checkpointFingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  },
  "claimToken": "<server-issued-token>",
  "claimVersion": 1,
  "claimUntil": "2026-07-17T12:02:00Z",
  "action": "RELEASE",
  "reasonCode": "DEPENDENCY_POLICY_FIXED"
}
```

To discard, a distinct checker first approves the exact live claim. Before the HTTP call, construct
the published `bloge.workerQuarantineChangeAuthorizationScope.v1` preimage from the same verified
tenant, organization, project, and environment identity used by Resource Gateway. Construct
`bloge.workerQuarantineChangeAuthorizationSubject.v1` from the exact key, observed claim owner,
version, deadline, and reason. Canonically fingerprint both objects, place those fingerprints in the
authorization material, and have the independent governance authorities sign that material's exact
canonical fingerprint with Ed25519.

```json
{
  "schemaVersion": "bloge.workerQuarantineChangeAuthorizationScope.v1",
  "tenantId": "tenant-a",
  "organizationId": "org-a",
  "projectId": "project-a",
  "environmentId": "staging"
}
```

```json
{
  "schemaVersion": "bloge.workerQuarantineChangeAuthorizationSubject.v1",
  "key": {
    "runId": "run-a",
    "checkpointFingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  },
  "claimOwner": "maintenance-operator-a",
  "claimVersion": 1,
  "claimUntil": "2026-07-17T12:02:00Z",
  "reasonCode": "AUTHORIZED_RETRY"
}
```

The checker request deliberately has no `claimToken`; its local lifetime is bounded to `1..900`
seconds and never exceeds `claimUntil` or the external authorization expiry. The fingerprints and
signature below are shape-only placeholders and must be replaced with the exact canonical values:

```http
POST /api/testing/durable-state/worker-quarantines/discard-approvals
Authorization: Bearer <independent-checker-token>
X-Purpose: TEST_RUNTIME_MAINTENANCE
Content-Type: application/json

{
  "schemaVersion": "bloge.durableWorkerQuarantineDiscardApprovalRequest.v2",
  "clientRequestId": "approve-discard-run-a-1",
  "key": {
    "runId": "run-a",
    "checkpointFingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  },
  "claimOwner": "maintenance-operator-a",
  "claimVersion": 1,
  "claimUntil": "2026-07-17T12:02:00Z",
  "reasonCode": "AUTHORIZED_RETRY",
  "approvalDurationSeconds": 60,
  "changeAuthorization": {
    "schemaVersion": "bloge.workerQuarantineChangeAuthorization.v1",
    "material": {
      "schemaVersion": "bloge.workerQuarantineChangeAuthorizationMaterial.v1",
      "trustDomain": "enterprise-change-governance",
      "authorizationId": "CHG-2026-0001842",
      "action": "WORKER_QUARANTINE_DISCARD",
      "scopeFingerprint": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
      "subjectFingerprint": "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
      "policyFingerprint": "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
      "issuedAt": "2026-07-17T11:55:00Z",
      "notBefore": "2026-07-17T11:56:00Z",
      "expiresAt": "2026-07-17T12:02:00Z"
    },
    "materialFingerprint": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    "signatures": [{
      "authorityId": "change-board-a",
      "keyId": "ed25519-a-2026-07",
      "algorithm": "Ed25519",
      "signedAt": "2026-07-17T11:56:00Z",
      "signature": "<base64-ed25519-signature-over-material-fingerprint>"
    }]
  }
}
```

Resource Gateway derives both bindings again from verified identity and request facts, verifies the
configured quorum, and reserves the authorization under database time. The v2 response contains a
token-free `approvalId`, `approvalFingerprint`, and
`bloge.durableWorkerQuarantineChangeAuthorizationReference.v1`; it never echoes authority
signatures or public keys. The original maker then consumes that exact approval while still proving
the secret claim fence:

```http
POST /api/testing/durable-state/worker-quarantines/approved-discards
Authorization: Bearer <maintenance-operator-token>
X-Purpose: TEST_RUNTIME_MAINTENANCE
Content-Type: application/json

{
  "schemaVersion": "bloge.durableWorkerQuarantineApprovedDiscardRequest.v1",
  "clientRequestId": "discard-quarantine-run-a-1",
  "key": {
    "runId": "run-a",
    "checkpointFingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  },
  "claimToken": "<server-issued-token>",
  "claimVersion": 1,
  "claimUntil": "2026-07-17T12:02:00Z",
  "approvalId": "<checker-issued-approval-id>",
  "reasonCode": "AUTHORIZED_RETRY"
}
```

The actions have intentionally different worker and governance semantics:

| Action | Maintenance state | Worker effect | Evidence |
| --- | --- | --- | --- |
| `RELEASE` | returns to `AVAILABLE` with a higher version | checkpoint remains quarantined and cannot be acquired | immutable token-free receipt and history row |
| approved `DISCARD` | approval is atomically consumed and active quarantine is deleted | exact checkpoint becomes eligible for a later worker scan | immutable token-free maker/checker receipt and dedicated retained history row |

Every claim, approval, and mutation first locks and revalidates the full checkpoint authority, then
the exact quarantine/control row. Approved discard additionally locks and verifies the approval.
Maker and checker identities must differ; claim, local approval, and external authorization must
bind the same scope, key, owner, version, expiry, and reason; all must be live by database time for a
new command. A changed checkpoint, stale/forged/
expired fence, self approval, consumed approval, or reused request ID with changed intent returns a
stable `409`; malformed requests return `400`. Exact retries return the immutable original result.
Approval replay checks the committed database intent before live trust, so a lost response remains
replayable after external expiry or temporary trust unavailability without authorizing new work.

Exact replay is intentionally time bounded. After the command/approval deadline plus
`command-retention-days`, the leased retention loop atomically replaces the detailed command with a
payload-free request tombstone. An exact retry then returns
`409 RG.TEST.WORKER_QUARANTINE_REPLAY_WINDOW_EXPIRED`; changed intent under that ID remains an
idempotency conflict. A v2 tombstone stores a non-secret key generation and a domain-separated
`v1.<base64url HMAC-SHA-256>` over operation, authenticated scope, and request ID rather than the raw
`clientRequestId`. New writes use the active key; exact lookup checks a bounded active/old/legacy
candidate set and CAS re-keys an old-key or legacy hit. Startup fails if any unexpired v2 tombstone
references an unavailable key, while expired rows remain purgeable without that retired key. Only
after `tombstone-retention-days` may the request identity be reused.
Token-free action history is physically deleted after `history-retention-days`.

Each tick has a database-clock owner/token/epoch lease and processes at most the configured page in
each command, history, and tombstone category. Source verification, tombstone insertion, exact
deletion, history purge, counter advance, and lease release commit as one transaction; a stale fence,
corrupt row, or claim-envelope authentication failure rolls the page back. See the
[worker-quarantine retention verification](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-retention-verification.md)
for lifecycle clocks, metrics, counterexamples, and honest erasure boundaries.

Approval creation has a checker-bound audit transaction. Approved discard then consumes the approval,
deletes the quarantine, writes its immutable command receipt and dedicated maker/checker history, and
commits semantic audit in one local transaction. Audit failure rolls everything back. Responses,
history, metrics, health, logs, and audit facts exclude claim tokens and business payloads. During
the detailed replay window the claim-command copy is AES-GCM encrypted; bounded retention later
authenticates it before deletion and leaves only a payload-free request tombstone. The live control
contains only a keyed verifier, not a second bearer token. Request tombstone indexes use a separate
key ring so their longer retention does not retain the claim-token root. External workflow
ticket lifecycle callbacks, dynamic revocation refresh, device/session assurance, WORM anchoring,
legal hold, backup erasure, and webhook notification remain hardening work.

The exact request-index format, online rotation order, legacy migration limit, and failure matrix are
defined in the
[request-index protection verification](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-request-index-protection-verification.md).

### 4.2k Prove one replica's request-index rollout state

Before moving from legacy writes to keyed writes, or from dual read to keyed-only, the deployment
gate can challenge each exact serving process:

```http
POST /api/testing/durable-state/worker-quarantines/request-index/replica-proofs
Authorization: Bearer <maintenance-operator-token>
X-Purpose: TEST_RUNTIME_MAINTENANCE
Content-Type: application/json

{
  "schemaVersion": "bloge.workerQuarantineRequestIndexReplicaProofRequest.v1",
  "challenge": "release_2026_07_17_gate_01_abcdef",
  "targetMode": "DUAL_READ_KEYED_WRITE"
}
```

The returned `bloge.workerQuarantineRequestIndexReplicaProof.v1` envelope signs a canonical material
fingerprint with Ed25519. Its material binds the caller challenge, identity-derived deployment
scope, deployment-supplied `instanceId`, process-start `startupId`, immutable artifact fingerprint,
protocol version, current and target modes, a DB-clock live-generation inventory, closed blockers,
and a short expiry. A valid signature can deliberately carry `transitionAllowed=false`; this proves
which local invariant blocked the transition rather than turning a policy failure into an
unexplained endpoint outage.

One proof is never a fleet gate. The deployment platform remains authoritative for the exact
serving-instance inventory and artifact digest. It must address each instance directly, reject
missing, duplicate, unexpected, stale, cross-scope, or mixed-artifact proofs, and verify every seal
against independently trusted keys. A load-balanced sample cannot prove that an unregistered,
partitioned, or previous-binary process is absent. The local database signer is demonstration-only;
release gates require a managed signer and externally pinned verification key policy. See the
[replica-proof verification](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-request-index-replica-proof-verification.md).

The standalone test-kit provides `requestWorkerQuarantineRequestIndexReplicaProof` and
`WorkerQuarantineRequestIndexFleetGateVerifier`. Build an independently trusted map of
`instanceId -> direct URI`, call the proof endpoint once through each exact URI with one challenge,
and pass the resulting cohort to a `WorkerQuarantineRequestIndexFleetPolicy` containing that exact
instance set, expected scope/artifact/protocol/target, a key-set pin obtained outside this response,
and the permitted cohort observation spread. The verifier rejects a missing, duplicate, unexpected,
stale, mixed, blocked, badly fingerprinted, or badly signed proof. It does not discover fleet
membership or convert repeated load-balanced calls into fleet evidence.

### 4.2.1.1 Global test-runtime SLO and capacity observation

`testRuntimeSloMonitor` is a separate Actuator health component for the whole isolated testing
runtime. It reads the child-run, suite-run, durable-creation, durable-checkpoint, BLOGE execution,
and BLOGE work-item projections in one read-only `REPEATABLE_READ` transaction. The observation time
comes from the database; no replica wall clock participates in lease expiry or queue-age decisions.
The outcome window defaults to 15 minutes and cannot exceed 365 days. Dedicated lifecycle/time
indexes keep the periodic aggregate read off payload columns; no `record_json`, `checkpoint_json`,
`payload_json`, fixture, replay, context, identity value, or credential is deserialized.

The health model distinguishes correctness outcomes from runtime correctness:

- `ASSERTION_FAILED`, `EXECUTION_FAILED`, `FIXTURE_UNMATCHED`, negative cases, and other expected
  product-under-test outcomes are counted by status but do **not** make the service unhealthy;
- child `EVIDENCE_INCOMPLETE` / `CONTROL_PLAN_UNAVAILABLE` and suite `PARTIAL` /
  `EVIDENCE_INCOMPLETE` contribute to the incomplete-evidence ratio;
- a ratio is enforced only after its configured minimum sample count, preventing one startup sample
  from creating a false platform outage;
- suite `RUNNING`, durable-creation `PENDING`, resumable durable checkpoints, and dispatchable or
  expired-claim work each have independent depth, expired-ownership, and oldest-age policies;
- suspended durable executions count toward capacity, but an expired suspension lease is not an
  ownership failure until the execution is `ACTIVE` or `RESUMING`;
- deterministic worker-candidate deferrals have independent active-count, retry-due, repeated-failure,
  and oldest-active-age policies; aggregation uses only the closed failure-reason vocabulary;
- permanent worker-candidate quarantines have independent backlog, oldest-age, expired maintenance
  claim, and expired unconsumed discard-approval policies; aggregates include closed reason/state
  counts, live/expired approvals, and retained legacy/two-person history sizes without identities;
- expired child/suite records and terminal durable/work-item rows have explicit cleanup-backlog
  limits. Observation does not silently delete them.

Stable violation codes are:

```text
EXECUTION_EVIDENCE_INCOMPLETE_RATE_EXCEEDED
SUITE_EVIDENCE_INCOMPLETE_RATE_EXCEEDED
SUITE_RUN_CAPACITY_EXCEEDED
SUITE_RUN_LEASE_BACKLOG
SUITE_RUN_STALE
DURABLE_CREATION_CAPACITY_EXCEEDED
DURABLE_CREATION_LEASE_BACKLOG
DURABLE_CREATION_STALE
DURABLE_EXECUTION_CAPACITY_EXCEEDED
DURABLE_EXECUTION_LEASE_BACKLOG
DURABLE_EXECUTION_STALE
WORK_ITEM_CAPACITY_EXCEEDED
WORK_ITEM_CLAIM_BACKLOG
WORK_ITEM_DISPATCH_STALE
WORKER_CANDIDATE_BACKOFF_CAPACITY_EXCEEDED
WORKER_CANDIDATE_RETRY_DUE_BACKLOG
WORKER_CANDIDATE_REPEATED_FAILURES
WORKER_CANDIDATE_BACKOFF_STALE
WORKER_CANDIDATE_QUARANTINE_BACKLOG
WORKER_CANDIDATE_QUARANTINE_STALE
WORKER_CANDIDATE_QUARANTINE_CLAIM_EXPIRED
WORKER_CANDIDATE_QUARANTINE_DISCARD_APPROVAL_EXPIRED
EXECUTION_RETENTION_BACKLOG_EXCEEDED
SUITE_RETENTION_BACKLOG_EXCEEDED
DURABLE_TERMINAL_RETENTION_BACKLOG_EXCEEDED
WORK_ITEM_TERMINAL_RETENTION_BACKLOG_EXCEEDED
TEST_RUNTIME_STORE_UNAVAILABLE
```

Satisfying policy reports `UP`; any stable violation reports `OUT_OF_SERVICE`; an unreadable store
reports `DOWN`. Health details include only aggregate counts, basis points, queue ages, and the codes
above. Store exception messages are discarded. Micrometer gauges are rooted at
`resource.gateway.test.runtime.*`:

| Metric family | Closed tag vocabulary | Meaning |
|---|---|---|
| `execution.outcomes` | `status` | recent child outcomes |
| `suite.outcomes` | `status` | recent terminal suite outcomes |
| `durable.executions` | `status` | durable control checkpoint states |
| `engine.executions` | `status` | BLOGE execution states |
| `work.items` | `status` | BLOGE work-item states |
| `queue.depth`, `lease.expired`, `queue.oldest.age` | `queue` | suite, creation, durable, and work pressure |
| `worker.candidate.deferrals`, `worker.candidate.deferrals.active` | `reason` | retained and active deterministic backoffs |
| `worker.candidate.deferrals.retry_due`, `.maximum_failures`, `.oldest_age` | none | due backlog and worst active-record pressure |
| `worker.candidate.quarantines` | `reason` | active exact-checkpoint quarantine backlog |
| `worker.candidate.quarantines.maximum_failures`, `.oldest_age` | none | worst unresolved quarantine pressure |
| `worker.candidate.quarantines.maintenance` | `state` | effective `AVAILABLE`/`CLAIMED` counts |
| `worker.candidate.quarantines.claims.expired`, `.history` | none | expired ownership and retained resolution history |
| `worker.candidate.quarantines.discard.approvals.live`, `.expired` | none | unconsumed checker approval lifecycle |
| `worker.candidate.quarantines.discards.approved.history` | none | retained two-person discard evidence count |
| `worker.candidate.quarantines.retention.attempts` | `result` | completed, lease-busy, or failed retention ticks |
| `worker.candidate.quarantines.retention.duration` | none | bounded retention attempt duration |
| `worker.candidate.quarantines.retention.tombstoned.total`, `.tombstones.purged.total`, `.history.purged.total` | none | cumulative lifecycle transitions |
| `worker.candidate.quarantines.retention.tombstones.records`, `.last.success.epoch` | none | current request reservations and last committed page |
| `durable.recovery.sequences.retention.attempts` | `result` | closed `completed`, `lease_busy`, or `failed` retention outcome |
| `durable.recovery.sequences.retention.duration` | none | bounded retention attempt duration |
| `durable.recovery.sequences.retention.sequences.tombstoned.total`, `.steps.purged.total`, `.claims.purged.total`, `.heartbeats.purged.total`, `.tombstones.purged.total` | none | cumulative verified lifecycle transitions |
| `durable.recovery.sequences.retention.sequences.records`, `.tombstones.records`, `.last.success.epoch` | none | aggregate current rows and last committed page |
| `durable.recovery.sequences.retention.sequences.overdue`, `.tombstones.expired` | none | policy-ready sequence and tombstone backlog counts |
| `durable.recovery.sequences.retention.last.success.age`, `.sequences.overdue.oldest.age`, `.tombstones.expired.oldest.age` | none | database-clock lifecycle ages in seconds; `-1` means unavailable |
| `durable.recovery.sequences.retention.health` | none | `1` healthy, `0` startup grace, `-1` SLO violated, `-2` store unavailable |
| `evidence.incomplete.basis_points` | `scope` | execution/suite incomplete ratio |
| `storage.records`, `storage.backlog` | `kind` | retained and cleanup-pressure rows |
| `health` | none | `1` healthy, `-1` violated, `-2` store unavailable |

Tenant, suite, run, operator, owner, item, token, error, and payload labels are forbidden. Capability
discovery advertises `testRuntimeSloHealth` and `boundedCardinalityTestRuntimeMetrics` only when the
profile-owned testing runtime exists.

The SLO component is an observation and readiness gate, not the capacity authority. The same isolated
profile separately installs the admission controller below. Neither component supplies a queued
scheduler, priority/fairness policy, two-person dead-letter approval, hard worker cancellation,
runtime-state delivery, adaptive scaling, or external alert delivery.

### 4.2.1.2 Database-authoritative runtime admission

Every engine-starting command is admitted against one independent test-runtime database transaction:

| Command path | Permit lifetime | Subject closure |
|---|---|---|
| Direct graph/operator | compiled plan to sanitized evidence | tenant + recursively reachable operators + frozen dependencies |
| Batch | one direct permit per sequential child | each child's exact compiled closure |
| Immutable suite | one parent permit for the complete serial run | tenant + suite id + target operator/dependency closure |
| Durable create | fresh engine preparation to committed initial boundary | authorized target/control-plan closure |
| Durable recovery step | recovered engine execution to next suspended or terminal boundary | re-authorized target/control-plan closure |
| Durable terminal recovery | recovered engine execution to committed terminal boundary | re-authorized target/control-plan closure |

Suite children deliberately do not reacquire capacity: the parent already owns every subject they can
use. Query, claim, and heartbeat commands do not start an engine and consume no permit. Idempotent suite
or durable result replay is resolved before admission and therefore consumes no capacity.

The authority hashes every subject with tenant and environment scope, locks a bounded request stripe and
all subject hashes in stable order, applies one versioned policy generation, counts only database-clock
live leases, and inserts every claim or none. A heartbeat renews exact token/owner/epoch ownership. Lost
ownership blocks terminal publication; exact release or bounded oldest-first expiry cleanup returns
capacity. No fixture, business context, credential, raw operator/dependency name, or lease token is
stored. Metrics use only closed `result` and `scope` tags under
`resource.gateway.test.admission.decisions`. Capability discovery advertises
`databaseAuthoritativeTestRuntimeAdmission` and
`boundedCardinalityTestRuntimeAdmissionMetrics` only when the profile-owned runtime exists.

Failure semantics are stable and payload-free:

| Condition | HTTP/result | Caller action |
|---|---|---|
| Any quota dimension is full | `429 RG.TEST.ADMISSION_QUOTA_EXCEEDED` + bounded `Retry-After` | retry with jitter after the advertised delay |
| Same stable suite/durable intent is live | `429 RG.TEST.ADMISSION_IN_PROGRESS` | query/replay the existing command, or retry later |
| Stable key is rebound to another intent | `409 RG.TEST.ADMISSION_IDEMPOTENCY_CONFLICT` | use the original intent or a new caller key |
| Store unavailable, policy generation drift, or coordinator shutdown | `503` | stop dispatch and repair deployment/store state |
| Heartbeat loses exact ownership | `503 RG.TEST.ADMISSION_LEASE_LOST` | treat the terminal response as unpublished and reconcile by command id |

Limits must be positive and at most `1,000,000`. Lease duration is an integral `2..3600` seconds and
heartbeat is an integral shorter duration; cleanup batch is `1..10000`. Invalid values fail application
startup. Changing any limit requires incrementing `policy-generation`; drain active tenant permits before
the rollout so old and new replicas do not intentionally fail closed on mixed generations. Give each
replica a stable, unique `instance-id` in managed deployments. The current SQL protocol is H2-certified;
another database requires dialect/concurrency certification before use.

### 4.2.2 Register an immutable test suite

A suite is a reviewed execution manifest, not an inline list of mutable fixtures. Every case carries
an exact fixture id, revision, and full fingerprint; the suite itself freezes the target fingerprint,
case intent, coverage policy, promotion policy, classification, and provenance:

```http
PUT /api/testing/suites/loan-decision-regression
Authorization: Bearer bloge-aneke-demo-token
X-Purpose: TEST_SUITE_WRITE
Content-Type: application/json
```

```json
{
  "schemaVersion": "bloge.testSuiteRegistrationRequest.v1",
  "testSuite": {
    "schemaVersion": "bloge.testSuite.v1",
    "suiteId": "loan-decision-regression",
    "revision": 1,
    "target": {
      "kind": "GRAPH",
      "id": "loanDecisionPolicy",
      "fingerprint": "sha256:<from-target-descriptor>"
    },
    "classification": "INTERNAL",
    "cases": [
      {
        "caseId": "prime-r1",
        "caseType": "GOLDEN",
        "input": {"applicantId": "prime", "requestedAmount": 450000},
        "fixtureBundleRef": {
          "fixtureBundleId": "loan-prime-v1",
          "revision": 1,
          "fingerprint": "sha256:<returned-by-fixture-registration>"
        },
        "tags": ["ci", "release-gate"],
        "metadata": {"requirementId": "RISK-1024"}
      }
    ],
    "coveragePolicy": {
      "minimumCases": 1,
      "requiredCaseTypes": ["GOLDEN"],
      "requiredInvocationSiteIds": ["/root/assembleLoanDecision#PRIMARY"],
      "requiredEdgeTransfers": [],
      "minimumAssertionsPerCase": 1,
      "requireAllFixtureRulesConsumed": true
    },
    "promotionPolicy": {
      "requireAllCasesPassed": true,
      "minimumCertifiableCases": 1,
      "requireTargetCertificationEligible": true
    },
    "metadata": {"owner": "risk-quality"}
  }
}
```

Registration is dependency-closed and fail closed:

- the current target must exactly match the suite target fingerprint;
- every case must resolve an existing fixture in the same verified tenant and environment;
- blank or stale fixture fingerprints are rejected; there is no implicit `latest` lookup;
- suite classification must be at least as restrictive as every fixture classification;
- graph case input must be a JSON object, case ids must be unique, and cases are bounded to 100;
- required case types, minimum case count, and minimum assertion density must already be satisfiable;
- `requireTargetCertificationEligible=true` rejects a target revision with certification gaps;
- `(tenant, environment, suiteId, revision)` is immutable and idempotent for equivalent content.

Coverage uses `invocationSiteId` and explicit source/destination site pairs rather than local
`nodeId` or `edgeId`. The structural coordinate includes graph path and invocation kind, so the same
node name in a root graph, foreach body, and compensation graph cannot collapse into one false hit.

To require orchestration behavior rather than only structural presence, register a new immutable
revision as `bloge.testSuite.v2` and add `semanticCoveragePolicy`. The registration envelope remains
`bloge.testSuiteRegistrationRequest.v1` because it already dispatches the nested suite by version:

```json
{
  "schemaVersion": "bloge.testSuite.v2",
  "semanticCoveragePolicy": {
    "requirements": [
      {"requirementId": "prime-branch", "kind": "BRANCH_TRANSFERRED",
       "fromInvocationSiteId": "/root/decision#PRIMARY",
       "toInvocationSiteId": "/root/approve#PRIMARY"},
      {"requirementId": "manual-skipped", "kind": "BRANCH_SKIPPED",
       "fromInvocationSiteId": "/root/decision#PRIMARY",
       "toInvocationSiteId": "/root/manual#PRIMARY"},
      {"requirementId": "rule-prime", "kind": "DECISION_RULE",
       "invocationSiteId": "/root/decision#PRIMARY",
       "outputJsonPointer": "/rule", "expectedScalar": "PRIME"},
      {"requirementId": "credit-retry", "kind": "RETRY",
       "invocationSiteId": "/root/credit#PRIMARY", "minimumAttempts": 2},
      {"requirementId": "bureau-fallback", "kind": "FALLBACK",
       "invocationSiteId": "/root/bureau#PRIMARY", "errorCode": ""},
      {"requirementId": "bureau-timeout", "kind": "TIMEOUT",
       "invocationSiteId": "/root/bureau#PRIMARY", "errorCode": "UPSTREAM_TIMEOUT"},
      {"requirementId": "reserve-compensation", "kind": "COMPENSATION",
       "invocationSiteId": "/root/releaseReservation#COMPENSATION", "errorCode": ""}
    ]
  }
}
```

This fragment replaces only the suite `schemaVersion` and adds the shown policy; target, cases,
structural coverage, promotion policy, and metadata remain required. Requirement ids are unique and
canonicalized. Decision expectations must be scalar JSON values. Compensation sites must end in
`#COMPENSATION`; only timeout requirements may carry a non-empty machine error code.

Query an exact revision with a separate reader purpose:

```bash
curl -sS 'http://localhost:8080/api/testing/suites/loan-decision-regression?revision=1' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_SUITE_READ'
```

### 4.2.3 Execute an exact suite revision

Suite execution accepts neither inline cases nor `latest`. The request binds the exact suite content
and carries a tenant/environment-scoped idempotency key:

```http
POST /api/testing/suites/loan-decision-regression/executions
Authorization: Bearer bloge-aneke-demo-token
X-Purpose: TEST_EXECUTION
Content-Type: application/json
```

Use `X-Purpose: TEST_REPLAY` instead when any case fixture contains a `REPLAY` rule. The suite
service preserves that verified purpose for every child execution, so replay authorization is
checked again while resolving each exact payload reference.

```json
{
  "schemaVersion": "bloge.testSuiteExecutionRequest.v1",
  "suiteRef": {
    "suiteId": "loan-decision-regression",
    "revision": 1,
    "fingerprint": "sha256:<returned-by-suite-registration>"
  },
  "clientRequestId": "risk-ci-1842-loan-regression",
  "strategy": "COLLECT_ALL",
  "metadata": {"pipeline": "release-candidate", "buildId": "1842"}
}
```

`COLLECT_ALL` schedules every bounded case. `FAIL_FAST` stops scheduling new cases after the first
non-pass result; it does not cancel the case already running and therefore cannot pretend to undo an
external side effect. The runner:

1. verifies the exact suite fingerprint and current target fingerprint before any case runs;
2. atomically writes the first `RUNNING` checkpoint with a process-owner lease, renews that lease
   while a child is running, and advances a database checkpoint fence after every child run;
3. executes graph and operator cases through the existing authorized adapters with `FULL` internal
   evidence and only the suite's exact stored fixture reference;
4. validates every child target, fixture, run id, and evidence identity before aggregation;
5. derives invocation-site, edge-transfer, case-type, assertion-density, and required-fixture
   consumption coverage from child evidence rather than author metadata;
6. stores a generation-matched terminal evidence record: V1 structural, V2 semantic, V3 schema
   admission, or V4 bounded property execution.

The owner lease is not a user lock. It is a short-lived runtime-instance claim that prevents a
slow child from being mistaken for a dead process. Every heartbeat, checkpoint, and terminal write
is scoped by tenant/environment/run id and the same owner id. The shared test-runtime database is
the time authority, so application-node clock skew cannot expire a live owner. Heartbeat and checkpoint writes
advance `checkpoint_version`; the abandoned-run sweeper can therefore terminalize a row only when
status, expired lease, owner, and scanned version still match.

The response links independently persisted child runs without copying their payloads. This
abridged view omits required fields that remain authoritative in the machine schema:

```json
{
  "schemaVersion": "bloge.testSuiteExecutionResponse.v2",
  "suiteRunId": "<server-run-id>",
  "evidenceFingerprint": "sha256:<aggregate-evidence>",
  "evidence": {
    "schemaVersion": "bloge.testSuiteRunEvidence.v1",
    "status": "PASSED",
    "caseResults": [
      {
        "caseId": "prime-r1",
        "status": "PASSED",
        "runId": "<child-test-run-id>",
        "evidenceStatus": "PASSED",
        "evidenceClass": "CERTIFIABLE"
      }
    ],
    "coverage": {
      "status": "SATISFIED",
      "missingInvocationSiteIds": [],
      "missingEdgeTransfers": [],
      "assertionDensityViolations": [],
      "fixtureConsumptionViolations": []
    },
    "promotion": {
      "status": "ELIGIBLE",
      "reasons": [],
      "coverageSatisfied": true,
      "allCasesCompleted": true
    }
  },
  "attestation": {
    "schemaVersion": "bloge.testSuiteRunAttestation.v1",
    "signatureStatus": "VERIFIED",
    "scope": "TERMINAL",
    "suiteRunId": "<server-run-id>",
    "requestFingerprint": "sha256:<normalized-suite-request>",
    "aggregateEvidenceFingerprint": "sha256:<aggregate-evidence>",
    "childEvidenceRefs": [
      {
        "caseId": "prime-r1",
        "runId": "<child-test-run-id>",
        "evidenceFingerprint": "sha256:<complete-child-evidence>"
      }
    ],
    "keyId": "<verification-key-id>",
    "algorithm": "Ed25519",
    "signature": "<base64-detached-signature>",
    "independentlyVerifiable": true
  }
}
```

The full wire shape is authoritative in the machine schema. Repeating the same
`clientRequestId` with the same normalized request returns the existing checkpoint or terminal run
without executing another case. Reusing it with different intent returns
`RG.TEST.SUITE_RUN_IDEMPOTENCY_CONFLICT`.

Query the latest durable checkpoint or terminal evidence:

```bash
curl -sS http://localhost:8080/api/testing/suite-executions/<suiteRunId> \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION'
```

Aggregate status is `RUNNING`, `PASSED`, `COMPLETED_WITH_FAILURES`, `PARTIAL`, or
`EVIDENCE_INCOMPLETE`. Coverage failure prevents `PASSED` even when every child assertion passes.
`promotion.status=ELIGIBLE` means only that the server-owned suite policy is satisfied; it is not a
certification, owner approval, ANEKE gate decision, or publication. New servers return v2 with a
signed `CHECKPOINT` or `TERMINAL` attestation. The v1 response remains a read-only migration shape
for historical unsigned records and must not be upgraded to trusted evidence by inference.

A semantic v2 suite returns `bloge.testSuiteExecutionResponse.v3`,
`bloge.testSuiteRunEvidence.v2`, and `bloge.testSuiteRunAttestation.v2`. Its required
`semanticCoverage` contains the signed `required`, server-derived `observed`, complete-evidence
`missingRequirementIds`, and trust/sanitization `unavailable` facts. `UNSATISFIED` means trusted
complete evidence did not contain a required fact; `INCOMPLETE` means Resource Gateway could not
prove the fact. Both block promotion. Only `CERTIFIABLE` child evidence can satisfy a requirement.

A schema-admission v3 suite returns `bloge.testSuiteExecutionResponse.v4`,
`bloge.testSuiteRunEvidence.v3`, and `bloge.testSuiteRunAttestation.v3`. Its success predicate is
`admissionCoverage=SATISFIED`, not structural coverage. It carries no business child closure and is
always promotion-blocked even when every validator expectation matches. Consumers must branch on
the evidence generation/evaluation mode; treating v4 as a more permissive business-suite response is
a protocol error.

A bounded property v4 suite returns `bloge.testSuiteExecutionResponse.v5`,
`bloge.testSuiteRunEvidence.v4`, and `bloge.testSuiteRunAttestation.v4`; its portable export is
`bloge.testSuiteEvidenceBundle.v4`. The evidence binds the plan and input-schema fingerprints,
generation policy, non-exhaustive quantification, ordered root/shrink lineage, every child evidence
reference, property coverage, and payload-free minimum observed counterexamples. Consumers must use
`evaluationMode=PROPERTY_EXECUTION` and the typed property verdict. A normal structural `PASSED`
predicate or an empty counterexample list cannot be used as a substitute for property coverage.

A mutation v5 suite returns `bloge.testSuiteExecutionResponse.v6`,
`bloge.testSuiteRunEvidence.v5`, and `bloge.testSuiteRunAttestation.v5`; its portable export is
`bloge.testSuiteEvidenceBundle.v5`. Consumers must branch on
`evaluationMode=PURE_DSL_MUTATION`, independently re-derive baseline status, mutant classification,
kill provenance, denominator, score, and policy reasons, and require the exact prefixed child closure.
An ordinary structural `PASSED`, a producer-supplied score, or a detached list of killed mutant ids is
not sufficient evidence.

The Canvas executable operator suite and standalone test-kit both consume this exact protocol; they
do not reconstruct an aggregate result from mutable row responses.

#### Abandoned `RUNNING` reconciliation

The test and staging profiles run a bounded anti-entropy sweep. When a process crashes, its
heartbeats stop. After the lease expires, the sweeper converts the latest durable checkpoint to
`EVIDENCE_INCOMPLETE` with diagnostic `ABANDONED_RUN_RECONCILED`:

- for structural/semantic v1-v2 evidence, completed child case results and child `runId`
  references are preserved exactly, pending cases become case-level `EVIDENCE_INCOMPLETE`, and
  business coverage becomes `INCOMPLETE`;
- for schema-admission v3 evidence, the child closure remains empty, completed typed validator
  observations are preserved, pending common/admission results become `EVIDENCE_INCOMPLETE`, and
  exact target, plan, input-schema, generator, and verification-mode fingerprints remain bound;
- for property v4 evidence, completed root/shrink child results and their signed references remain
  unchanged, only pending property cases become `EVIDENCE_INCOMPLETE`, property coverage becomes
  `INCOMPLETE`, and no input is regenerated or executed during reconciliation;
- for mutation v5 evidence, completed baseline and mutant children remain unchanged, pending baseline
  cases become `EVIDENCE_INCOMPLETE`, pending mutant cases become `NOT_SCHEDULED` with
  `ABANDONED_RUN_RECONCILED`, classification and score are recomputed, and no mutant is regenerated or
  executed during reconciliation;
- v3 structural DAG coverage remains `NOT_EVALUATED`; its admission coverage becomes
  `INCOMPLETE`, and every evidence generation sets promotion to `BLOCKED`;
- reconciliation metadata records only owner fingerprint/version/timestamps, never raw owner,
  fixture, or node payloads;
- a status/version/owner/expiry compare-and-set prevents an old scan from overwriting a concurrent
  heartbeat, checkpoint, or terminal result;
- a failed candidate does not stop the batch, and the next scheduled sweep retries unresolved rows.

This is **terminalization, not resume**. The sweeper never reruns a business child or a schema
validator case. A business case may have produced an unconfirmed external side effect; a schema
case must not be silently evaluated after the reviewed run lost ownership. A caller may query the
existing `suiteRunId`, inspect the fail-closed evidence, and decide whether a new idempotent suite
execution is appropriate.

| Environment variable | Default | Meaning |
|---|---:|---|
| `RG_TEST_SUITE_RUNNER_INSTANCE_ID` | generated per process | Stable owner for this process lifetime |
| `RG_TEST_SUITE_LEASE_SECONDS` | `30` | Active owner lease; bounded to 5-3600 seconds |
| `RG_TEST_SUITE_HEARTBEAT_SECONDS` | `5` | Renewal interval; normalized below the lease duration |
| `RG_TEST_SUITE_RECONCILIATION_INTERVAL_MS` | `15000` | Fixed delay between anti-entropy sweeps |
| `RG_TEST_SUITE_RECONCILIATION_BATCH_SIZE` | `100` | Oldest-first sweep bound; maximum 1000 |
| `RG_TEST_STABILITY_INSTANCE_ID` | generated per process | Prefix for fresh per-invocation parent owners |
| `RG_TEST_STABILITY_LEASE_SECONDS` | `30` | Parent owner lease; whole 5-3600 seconds |
| `RG_TEST_STABILITY_HEARTBEAT_SECONDS` | `5` | Whole-second renewal interval; at most one-third of lease |
| `RG_TEST_STABILITY_LEASE_CLEANUP_INTERVAL_MS` | `15000` | Fixed delay between expired-orphan sweeps |
| `RG_TEST_STABILITY_LEASE_CLEANUP_BATCH_SIZE` | `1000` | Oldest-first deletion bound; maximum 10000 |

### 4.2.4 Execute and verify bounded suite stability

Stability analysis is a separate signed protocol over repeated exact suite executions. It accepts
executable V1, V2, and V4 suites; schema-admission V3 has no business child closure and mutation V5
has its own matrix semantics, so both are rejected. The request fixes the immutable suite identity,
caller-owned parent idempotency key, and exact attempt count:

```http
POST /api/testing/suites/loan-decision-regression/stability-executions
Authorization: Bearer bloge-aneke-demo-token
X-Purpose: TEST_EXECUTION
Content-Type: application/json
```

```json
{
  "schemaVersion": "bloge.testSuiteStabilityExecutionRequest.v1",
  "suiteRef": {
    "suiteId": "loan-decision-regression",
    "revision": 1,
    "fingerprint": "sha256:<returned-by-suite-registration>"
  },
  "clientRequestId": "risk-ci-1842-stability",
  "attempts": 5,
  "metadata": {"pipeline": "nightly", "buildId": "1842"}
}
```

Deterministic request v1 accepts 3 through 20 attempts. To precommit the current exact probability
claim, use request v3:

```json
{
  "schemaVersion": "bloge.testSuiteStabilityExecutionRequest.v3",
  "suiteRef": {
    "suiteId": "loan-decision-regression",
    "revision": 1,
    "fingerprint": "sha256:<returned-by-suite-registration>"
  },
  "clientRequestId": "risk-ci-1842-statistical-stability",
  "attempts": 30,
  "statisticalPolicy": {
    "model": "BASELINE_CONDITIONAL_EXACT_BINOMIAL",
    "claimScope": "SUITE_ATTEMPT_ANY_CASE",
    "stoppingRule": "PRECOMMITTED_FIXED_HORIZON",
    "censoringPolicy": "FAIL_CLOSED",
    "confidenceLevelBps": 9500,
    "maximumInstabilityRateBps": 1000
  },
  "metadata": {"pipeline": "nightly", "buildId": "1842"}
}
```

Statistical request v3 accepts 3 through 1000 attempts, requires an exact sufficient horizon, and
caps `attempts * suiteCaseCount` at 10,000. The example's 95% confidence/10% instability ceiling
requires 30 executions: the first verified vector establishes the baseline and the remaining 29 are
comparison trials. Historical request v2 with `ZERO_INSTABILITY_EXACT_BINOMIAL` remains accepted for
verification compatibility and returns historical v3 evidence; model/version cross-pairs are
rejected. Every attempt uses `COLLECT_ALL`; the service derives a
stable child idempotency key for each attempt and delegates to the ordinary durable suite runner.

To permit optional-stopping-safe early completion, use request v4 and precommit a strictly smaller
alternative rate in addition to confidence, ceiling, and maximum horizon:

```json
{
  "schemaVersion": "bloge.testSuiteStabilityExecutionRequest.v4",
  "suiteRef": {
    "suiteId": "loan-decision-regression",
    "revision": 1,
    "fingerprint": "sha256:<returned-by-suite-registration>"
  },
  "clientRequestId": "risk-ci-1842-anytime-stability",
  "attempts": 100,
  "statisticalPolicy": {
    "model": "BASELINE_CONDITIONAL_ANYTIME_VALID_E_PROCESS",
    "claimScope": "SUITE_ATTEMPT_ANY_CASE",
    "stoppingRule": "ANYTIME_VALID_E_PROCESS",
    "censoringPolicy": "FAIL_CLOSED",
    "confidenceLevelBps": 9500,
    "maximumInstabilityRateBps": 1000,
    "alternativeInstabilityRateBps": 500
  },
  "metadata": {"pipeline": "nightly", "buildId": "1842"}
}
```

For v4, `attempts` is a maximum rather than an exact execution count. At the example coordinates a
clean path first crosses after 56 comparisons, so terminal v5 evidence contains 57 executions. The
only legal stops are the first boundary crossing, the first censored attempt, or maximum horizon.
Repeating the parent request therefore reuses the same retained terminal analysis, while reusing its
`clientRequestId` with different suite, attempt count, or metadata returns
`RG.TEST.STABILITY_IDEMPOTENCY_CONFLICT`. Use `TEST_REPLAY` when the exact suite contains a governed
replay fixture.

Before attempt one, a separate database-authoritative parent progress record and lease serialize the scoped request
across replicas without consuming another child suite quota permit. A concurrent same-intent call
returns `429 RG.TEST.STABILITY_EXECUTION_IN_PROGRESS` and `retryAfterSeconds` before any child is
scheduled. Database-clock expiry permits only an epoch-incrementing takeover. The owner renews
before every new attempt. After a source suite run and child closure are verified, the server
atomically appends their payload-free source reference and renews the exact lease before scheduling
the next attempt. An expired-owner takeover receives this contiguous prefix, refetches and verifies
every source/child closure, and executes only the remaining attempts. Terminal insert, complete
journal validation, progress deletion, and exact lease deletion commit atomically. A stale owner
returns `503 RG.TEST.STABILITY_EXECUTION_LEASE_LOST` and cannot persist an `INCONCLUSIVE`
substitute. Bounded cleanup removes expired orphan leases, while derived child idempotency keys close
the narrower source-terminal-before-parent-checkpoint crash window. See
[Stage 5 suite-stability execution-lease verification](resource-gateway-execution-data-control-plane-stage5-suite-stability-execution-lease-verification.md)
and [durable parent-progress verification](resource-gateway-execution-data-control-plane-stage5-suite-stability-durable-progress-verification.md)
for the state, shutdown, crash-window, and failure matrices. The synchronous endpoint remains a
resumable single-owner call. The durable non-blocking job protocol below uses the same execution
semantics behind database-authoritative admission; neither path claims distributed attempt-level
scheduling.

Request v1 returns complete terminal response/evidence/attestation v2. Historical request v2 returns
matching v3 objects. Current request v3 returns response/evidence/attestation v4 containing the
signed baseline-conditional exact-rate assessment. Request v4 returns v5 objects containing the
actual ordered prefix, precommitted alternative, first crossing, e-value-derived confidence floor,
and stop reason. Retained v1 responses remain queryable for
audit. For each exact case and attempt, v2+ evidence binds the source
suite promotion status/reasons plus child run/evidence, fixture, effective-plan, evidence-class, and
semantic-result fingerprints without copying payload values. Classification uses the complete
outcome identity `evidenceStatus + semanticResultFingerprint`:

| Case result | Required proof |
| --- | --- |
| `STABLE_PASS` | All requested observations are verified, pass, and have one outcome identity |
| `CONSISTENT_FAILURE` | All observations are verified, fail, and have one outcome identity |
| `FLAKY` | At least two verified observations have different outcome identities |
| `INCONCLUSIVE` | Missing/invalid evidence, source reuse, child reuse, or effective-plan drift prevents a conclusion |

The aggregate status is `STABLE`, `FLAKY`, `CONSISTENT_FAILURE`, or `INCONCLUSIVE`. `STABLE` is
necessary but not sufficient for promotion: every verified source suite must also be promotion
`ELIGIBLE`. If one source is `BLOCKED`, the aggregate remains `STABLE` but promotion becomes
`BLOCKED` with `SOURCE_SUITE_PROMOTION_BLOCKED`. `FLAKY` produces a quarantine recommendation; it does not change suite
state, suppress a failure, or authorize publication. `INCONCLUSIVE` remains fail closed.

V4 treats the first verified ordered suite-attempt vector as the observed baseline and only the
remaining `verifiedAttempts - 1` vectors as Bernoulli comparisons. It signs that comparison count,
the number of vectors differing from baseline, a conservative confidence floor, and the
upward-rounded one-sided exact Clopper-Pearson upper instability-rate bound. A complete sample is
`SATISFIED` when that bound is no greater than the configured ceiling and `REJECTED` otherwise; any
censoring yields `INCONCLUSIVE`, confidence zero, and no upper bound. Thus a complete 60-execution,
one-event sample at 95% confidence has 59 comparisons and a 7.79% upper bound, but its deterministic
aggregate is still `FLAKY`, promotion-blocked, and quarantine-required. This is a conditional rate
bound under signed exchangeability/stationarity and observed-baseline assumptions, not a correctness
proof or guarantee that future runs cannot vary.

V5 independently reconstructs the likelihood-ratio e-process at every signed prefix. It accepts a
partial closure only at the first confidence boundary or first censor; otherwise all requested
attempts must be present with `MAXIMUM_HORIZON_REACHED`. A producer cannot select the alternative
after observing data, report a later favorable crossing, or treat censoring as a no-event sample.
Anytime-valid controls optional stopping under the signed conditional model assumptions; it does not
prove stationarity, baseline representativeness, common-cause independence, or business correctness.

The v2-v5 attestation signs the canonical parent request fingerprint, evidence fingerprint, and
exact ordered source-suite closure including source promotion status and reasons. A source suite run
or child run reused across attempts, an omitted
source, an invalid source/child signature, or plan drift can never produce `STABLE`. Retention uses
`gateway.testing.store.retention-days` (default 30, bounded to 1..3650) and is capped from the earliest
source start; analysis creation fails when the source retention window is already exhausted.

Query the retained result in the same tenant/environment scope:

```bash
curl -sS http://localhost:8080/api/testing/stability-executions/<stabilityRunId> \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION'
```

Poll the payload-free parent lifecycle with the same authority:

```bash
curl -sS http://localhost:8080/api/testing/stability-executions/<stabilityRunId>/progress \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION'
```

Progress v1/v2 reports `RUNNING` when a database-clock-live owner exists,
`RECOVERABLE` when retained progress has no live owner, and `COMPLETED` when signed terminal evidence
exists. V2 additionally carries the terminal stop reason and permits an actual completed prefix below
the planned maximum only for first crossing or censoring. It returns exact suite identity and
planned/completed counts, but omits owner, epoch, source
run ids, journal entries, fixture/context values, and payloads. This operational projection is not
signed release evidence. Java consumers call
`ResourceGatewayTestClient.findSuiteStabilityProgress(stabilityRunId)`; the test-kit validates both
the authoritative Schema and semantic count/time relationships.

The independent test-kit re-derives case/aggregate classification, source promotion closure,
promotion and quarantine verdicts, request/evidence/source-closure fingerprints, source suite
evidence, child evidence, and the detached Ed25519 signature. For v3-v5 it also reconstructs the
exact horizon, attempt vectors, censor/event counts, achieved confidence, assumptions, assessment,
and promotion flag; v4 additionally recomputes the post-baseline comparison count and exact upper
rate bound, while v5 scans every prefix for the first e-value boundary. A valid v1 signature can be verified
for audit, but v1 fails every release gate because source promotion closure is unavailable. Its CI
`STABILITY` mode additionally requires an externally supplied
atomic-key-set fingerprint pin; accepting a key set only because the producer returned it is not a
trust decision. CLI options `--confidence-bps` and `--max-instability-rate-bps` select request
v3/response v4; adding `--alternative-instability-rate-bps` explicitly selects request v4/response
v5. The CLI never chooses an alternative implicitly. For fixed horizon, when `--attempts` is omitted,
the exact minimum horizon is used. See
[Stage 5 suite-stability verification](resource-gateway-execution-data-control-plane-stage5-suite-stability-verification.md)
for the implementation boundary and negative proofs.

#### Submit, inspect, and cancel a durable stability job

The asynchronous protocol is a separate application boundary over the same exact execution request.
It never returns the stored principal, execution metadata, source attempt ids, lease owner/epoch,
cancellation fingerprint, policy generation, or row-integrity seal. Submit is authenticated with the
dedicated `TEST_SUITE_STABILITY_JOB_SUBMIT` operation and returns `202 Accepted` plus a canonical
`Location` header:

```http
POST /api/testing/suites/loan-decision-regression/stability-jobs
Authorization: Bearer bloge-aneke-demo-token
X-Purpose: TEST_EXECUTION
Content-Type: application/json
```

```json
{
  "schemaVersion": "bloge.testSuiteStabilityJobSubmitRequest.v1",
  "execution": {
    "schemaVersion": "bloge.testSuiteStabilityExecutionRequest.v1",
    "suiteRef": {
      "suiteId": "loan-decision-regression",
      "revision": 1,
      "fingerprint": "sha256:<returned-by-suite-registration>"
    },
    "clientRequestId": "risk-ci-1842-stability-job",
    "attempts": 10,
    "metadata": {"pipeline": "nightly", "buildId": "1842"}
  },
  "priority": "NORMAL",
  "deadlineAt": "2026-07-19T00:00:00Z"
}
```

`deadlineAt` is an integral-second UTC timestamp. Database time accepts it only when it is in the
future and inside `maximum-deadline-horizon-days`. `clientRequestId`, tenant, and environment derive
the stable `stability-job-<sha256>` identity. Exact replay is resolved before rereading the mutable
suite registry, so a retained accepted command remains observable during authority outages or after
registry evolution. Changing execution, priority, or deadline under the same request identity returns
`409 RG.TEST.STABILITY_JOB_IDEMPOTENCY_CONFLICT`.

Poll with the returned location:

```bash
curl -sS http://localhost:8080/api/testing/stability-jobs/<jobId> \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION'
```

The closed status vocabulary is `QUEUED`, `RUNNING`, `CANCEL_REQUESTED`, `COMMITTING`, `SUCCEEDED`,
`FAILED`, `CANCELLED`, `EXPIRED`, and `QUARANTINED`. Only `SUCCEEDED` carries `stabilityRunId` and
`evidenceFingerprint`; only the five terminal states report `terminal=true`. Query first scopes by
verified tenant/environment and then verifies organization/project. A mismatch is the same
`404 RG.TEST.STABILITY_JOB_NOT_FOUND` as absence, preventing cross-scope probing.

Cancel with a separate caller-stable command identity:

```bash
curl -sS -X POST \
  http://localhost:8080/api/testing/stability-jobs/<jobId>/cancellations \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION' \
  -H 'Content-Type: application/json' \
  -d '{"schemaVersion":"bloge.testSuiteStabilityJobCancelRequest.v1",\
       "clientRequestId":"cancel-risk-ci-1842"}'
```

Queued work becomes `CANCELLED`; running work becomes `CANCEL_REQUESTED` and stops at the next
cooperative checkpoint. `COMMITTING` has already crossed the final cancellation/deadline
linearization point and is returned unchanged. Cancellation replay is bound to the authenticated
actor/delegation command but not transient correlation ids.

The first accepted command is also an exactly-once semantic audit boundary. Queue state and the
`SUITE_STABILITY_JOB_CANCELLATION` event commit in the same test-runtime database transaction;
failure or absence of the transaction-bound audit mutation rolls back the queue change. The event
records only schema version, job/command fingerprints, organization/project, actor/delegation,
purpose, clearance, group count/fingerprint, database-time previous/resulting status and the closed
outcome. It never records bearer credentials, group names, execution metadata, suite request,
fixture, graph context, payload, node result, or lease fence. A first command received in
`COMMITTING` or any terminal state is retained and audited as `TOO_LATE_TO_CANCEL` or
`ALREADY_TERMINAL`; exact replay returns the retained job without another semantic event, while a
different command id/fingerprint conflicts. See the
[cancellation audit verification](resource-gateway-execution-data-control-plane-stage5-suite-stability-cancellation-audit-verification.md).

Fresh submit is available only when
`gateway.testing.stability-jobs.worker.enabled=true` and startup has found exactly one ready
current-IAM `TestSuiteStabilityJobAuthorizer`. The product HTTP adapter is opt-in through
`gateway.testing.stability-jobs.authority.http.enabled=true`. It calls the versioned private endpoint
`<base-uri>/v1/stability-job-authorizations` over HTTPS and verifies short-lived Ed25519 decisions
against the configured authority id and public-key ring. The request carries only action, exact job/
suite/request fingerprints, deadline, classification and a credential-free principal projection;
it omits correlation id, bearer credential, execution metadata, fixture/context/payload and node
results. A fresh 256-bit challenge, request id, principal fingerprint and request fingerprint are
echoed by and signed into every response. Network/protocol/time/key/signature ambiguity is
`UNAVAILABLE`; an HTTP `403` is not treated as revocation. Only a valid signed `REVOKED` decision can
permanently fail the job for current policy.

The startup check proves that exactly one provider and at least one currently active trust key are
present; it is not a perpetual readiness lease. Resource Gateway reevaluates the provider's local,
key-free descriptor for every fresh submission and every capability response. Key expiry/revocation,
provider ambiguity, descriptor failure, or trust refresh outage therefore closes fresh admission
and changes `asyncSuiteStabilityJobSubmission` to `false` without making a remote PDP call. An exact
retained submit replay is resolved first and remains available, preserving idempotency during IAM
rotation or outage. Claimed workers still perform the signed remote decision immediately before
execution; local readiness never substitutes for that decision.

Static trust is configured with a bounded JSON array such as:

```json
[
  {
    "keyId": "iam-key-2026-07",
    "algorithm": "Ed25519",
    "publicKeyBase64": "<X.509-encoded Ed25519 public key>",
    "notBefore": "2026-07-01T00:00:00Z",
    "expiresAt": "2026-10-01T00:00:00Z",
    "enabled": true,
    "revoked": false
  }
]
```

Add and deploy a new public key before the authority starts signing with it. Keep the previous key
enabled until no live decision can remain, then remove it in a later fleet rollout. Mark a
compromised key `revoked=true` immediately; decisions signed by it fail closed. The configured key
must remain active at issue time, verification time and through the decision expiry.

For restart-free rotation, enable the built-in dynamic source with
`gateway.testing.stability-jobs.authority.http.jwks.enabled=true` and configure its HTTPS URI. It
accepts only a bounded public Ed25519 JWKS such as:

```json
{
  "keys": [
    {
      "kid": "iam-key-2026-08",
      "kty": "OKP",
      "crv": "Ed25519",
      "alg": "EdDSA",
      "use": "sig",
      "key_ops": ["verify"],
      "x": "<base64url 32-byte public coordinate>",
      "enabled": true,
      "revoked": false
    }
  ]
}
```

Bootstrap requires one active key. Thereafter one jittered background lane uses ETag conditional
GET, publishes only fully parsed snapshots and performs a cooldown-bound refresh when a signed
response presents an unknown `kid`. Any fetch, protocol or parsing failure makes the complete trust
snapshot unavailable; there is no stale-acceptance mode. A hard local maximum age also closes a
silent refresh lane. Capability and Actuator health read only the local snapshot and never issue a
remote request. A valid refresh can recover without restart.

Staging serving-inventory deployments use a separate managed runtime-key source instead of static
deployment/witness runtime keys. The versioned publication atomically carries both runtime key sets,
their independent trust domains and thresholds, and a sequence/predecessor chain. It is accepted
only after independent deployment bootstrap-root and witness bootstrap-root M-of-N verification,
strict local binding, lifetime checks, and a durable database sequence-floor commit. Inventory
unknown-key handling may trigger one cooldown-bounded synchronous root refresh; a changed root
generation forces inventory revalidation even after `304`. Root source outage, hard-age expiry,
rollback, fork, gap, partial quorum, threshold revocation, or root/inventory generation divergence
closes admission. Managed and legacy static runtime-key modes cannot be mixed. Capability, cohort,
and Actuator projections contain only aggregate counts, status, and protocol booleans; source URI,
ETag, root-set id, authority/key ids, public keys, signatures, and fingerprints stay private.

Staging also externalizes the ordering of both the inventory publication/witness chain and the
managed trust-root chain. The private
`bloge.testSuiteStabilityExternalSequenceCheckpointRequest.v1` submits the exact stream head with a
fresh 256-bit challenge and at most 60-second whole-second window to every configured notary in
parallel. Each notary returns a signed
`bloge.testSuiteStabilityExternalSequenceCheckpointReceipt.v1` with `ACCEPTED` or `CONFLICT`, the
exact request fingerprint, candidate head, observed head, authority/failure-domain identity, and
short expiry. Resource Gateway requires `3f+1` independently configured authorities and at least
`2f+1` accepted receipts; staging fixes the minimum at `f=1`. One authenticated, meaningful conflict
fails closed even if another threshold accepts. This intentionally prefers safety over availability:
a compromised configured notary can deny progress, but cannot make a conflicting local generation
acceptable while the `<=f` assumption holds.

The ordering is external-first: the notary quorum compare-and-append completes before the local
database floor transaction. External success plus local failure is idempotently retryable; local
success without external acceptance is impossible. Receipt signature, challenge replay, request/
receipt deadline containment, media/protocol header, duplicate/unknown/trailing JSON, redirect,
endpoint/failure-domain uniqueness, quorum math, and HTTPS are all enforced. JSON Schema defines
the wire shape; Java validation owns cross-field equality, time ordering, threshold, signature, and
meaningful-conflict semantics. Health and capability reads never contact a notary and expose no
endpoint, authority, key, stream, challenge, or fingerprint identity.

The built-in adapters rely on the JVM TLS context, so mTLS identity belongs in deployment TLS
material, not in JSON properties. Both `allow-insecure-loopback` settings must remain disabled
outside local tests. A deployment-owned KMS/certificate implementation may still replace
`TestSuiteStabilityAuthorityTrustStore` while preserving the verification contract.

When the worker or authority is disabled, query/cancel remain operational and submit returns
`503 RG.TEST.STABILITY_JOB_SUBMISSION_UNAVAILABLE` with `Retry-After`. Queue and tenant capacity
return `429`; policy drift returns retryable `503`; expired retained detail returns `410`. Discover
the distinction through `testability.suiteStabilityJobSubmissionEnabled` and the
`asyncSuiteStabilityJobProtocol`, `asyncSuiteStabilityJobSubmission`,
`suiteStabilityCurrentAuthorityRevalidation`, `signedChallengeBoundSuiteStabilityAuthority`,
`dynamicSuiteStabilityAuthorityTrust`, `suiteStabilityAuthorityTrustRefreshSlo`,
`exactSuiteStabilityAuthorityTrustCohort`,
`convergedSuiteStabilityAuthorityTrustCohort`,
`externallyAttestedSuiteStabilityServingInventory`,
`dynamicSuiteStabilityServingInventory`,
`witnessedSuiteStabilityServingInventoryPublications`,
`durableSuiteStabilityServingInventoryPublicationFloor`,
`restartFreeSuiteStabilityServingInventoryKeyRotation`,
`atomicDualQuorumSuiteStabilityServingInventoryTrustRoots`,
`externallyAnchoredSuiteStabilityServingInventoryOrdering`,
`byzantineQuorumSuiteStabilityServingInventoryNonEquivocation`,
`asyncSuiteStabilityJobQuery`, `asyncSuiteStabilityJobCancellation`, and
`asyncSuiteStabilityJobCancellationSemanticAudit` feature flags. The strict
request/response definitions live in
[`testing-control-plane-v1.schema.json`](schemas/resource-gateway-testing/testing-control-plane-v1.schema.json).
The private worker-to-PDP contract is separately versioned in
[`suite-stability-authority-v1.schema.json`](schemas/resource-gateway-testing/suite-stability-authority-v1.schema.json);
it is not a caller-facing testing endpoint.
The dynamic serving-inventory publication is separately versioned in
[`suite-stability-serving-inventory-publication-v1.schema.json`](schemas/resource-gateway-testing/suite-stability-serving-inventory-publication-v1.schema.json).
The atomic dual-root runtime-key publication is defined by
[`suite-stability-serving-inventory-trust-root-publication-v1.schema.json`](schemas/resource-gateway-testing/suite-stability-serving-inventory-trust-root-publication-v1.schema.json).
The external compare-and-append request/receipt contract is defined by
[`suite-stability-external-sequence-checkpoint-v1.schema.json`](schemas/resource-gateway-testing/suite-stability-external-sequence-checkpoint-v1.schema.json).
Implementation evidence and deliberately unclaimed guarantees are recorded in
[Stage 5 current-authority verification](resource-gateway-execution-data-control-plane-stage5-suite-stability-current-authority-verification.md).
Dynamic refresh invariants, health semantics and deliberately unclaimed fleet guarantees are in
[Stage 5 dynamic authority trust verification](resource-gateway-execution-data-control-plane-stage5-suite-stability-dynamic-authority-trust-verification.md).
Signed membership, revocation, witness, and cross-replica generation invariants are recorded in
[Stage 5 dynamic serving-inventory verification](resource-gateway-execution-data-control-plane-stage5-suite-stability-dynamic-serving-inventory-verification.md).
Managed runtime-key rotation and local durable ordering are recorded in
[Stage 5 serving-inventory trust-root rotation verification](resource-gateway-execution-data-control-plane-stage5-suite-stability-trust-root-rotation-verification.md).
External non-equivocation threat assumptions, commit ordering, failure semantics, and deployment
responsibilities are recorded in
[Stage 5 serving-inventory external non-equivocation verification](resource-gateway-execution-data-control-plane-stage5-suite-stability-external-non-equivocation-verification.md).

The standalone Java test-kit exposes the same protocol without depending on Resource Gateway
server classes:

```java
TestSuiteStabilityJobRequest asyncRequest = TestSuiteStabilityJobRequest.fixedHorizon(
        "loan-decision-regression",
        1,
        suiteFingerprint,
        "risk-ci-1842-stability-job",
        10,
        Map.of("pipeline", "nightly", "buildId", "1842"),
        TestSuiteStabilityJobRequest.Priority.NORMAL,
        Instant.now().plus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.SECONDS));

TestSuiteStabilityJobSubmission admitted = client.submitSuiteStabilityJob(
        asyncRequest, TestSuiteStabilityJobRetryPolicy.conservative());
TestSuiteStabilityJob terminal = client.awaitSuiteStabilityJob(
        admitted.job().jobId(), TestSuiteStabilityJobPollingPolicy.conservative());

if (terminal.status() == TestSuiteStabilityJob.Status.SUCCEEDED) {
    TestSuiteStabilityEvidenceVerifier.VerificationResult verification =
            client.verifySuiteStability(terminal.stabilityRunId(), trustedKeySetPin);
    TestSuiteStabilityAssertions.assertReleaseEligible(
            client.findSuiteStability(terminal.stabilityRunId()), verification);
}
```

`TestSuiteStabilityJobRequest.statistical(...)` emits execution request v2 and verifies the
precommitted exact-binomial horizon locally. Submit requires exact `202 + Location`, validates the
packaged Schema, recalculates the nested execution fingerprint, and binds suite/request/priority/
deadline to the response. `findSuiteStabilityJob` and `cancelSuiteStabilityJob` enforce the exact job
identity and payload-free view. Cancellation also has an idempotent bounded-retry overload.

`TestSuiteStabilityJobRetryPolicy` bounds HTTP attempts, each delay, and total monotonic time. It
retries only server-declared retryable `429`/`503`; a present but invalid or over-bound
`Retry-After` stops retry, so the client never sends earlier than the server directive.
`TestSuiteStabilityJobPollingPolicy` separately bounds query count, elapsed time, normal interval,
and accepted server delay. Await returns all terminal outcomes without translating a failed,
cancelled, expired, or quarantined job into success. The operational job view remains unsigned;
release decisions must fetch and verify the successful terminal stability evidence.

See the [test-kit guide](../resource-gateway-test-kit/README.md) and
[Stage 5 asynchronous test-kit verification](resource-gateway-execution-data-control-plane-stage5-suite-stability-test-kit-verification.md).

### 4.2.5 Materialize the built-in graph catalog

The seven legacy resource-graph suites already execute through the common kernel, but their source
table assets predate `bloge.testSuite.v1`. Materialize all 14 cases into the caller's tenant and
environment scope with one idempotent operation:

```bash
curl -sS -X PUT \
  http://localhost:8080/api/testing/catalogs/gateway-graph-contract-v1 \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_SUITE_WRITE'
```

The `bloge.testSuiteCatalogMaterialization.v1` response contains a `catalogFingerprint` and, for
each source suite, its graph, case count, exact destination suite reference, and one exact fixture
reference per case. It contains no test payload or registry timestamp. Callers can compare repeated
responses directly and pass `suites[].suiteRef` to the suite execution endpoint or CI CLI.

Materialization preserves the four case intents, F3 transport fixtures, bounded retry consumption,
numeric assertion tolerance, graph/output schema assertions, and legacy required-output-node
coverage. Required node ids are resolved through the planner's frozen invocation inventory, so an
`httpResource` output is correctly required as `#RESOURCE`, not guessed as `#PRIMARY`.

Destination ids remain stable. Revisions are derived from canonical source content plus the exact
graph dependency fingerprint. A graph, descriptor, case, assertion, intent, or policy change creates
a new immutable revision while old evidence remains reproducible. The operation commits fixture
revisions before their referring suite. If a later item fails, a retry converges on the same content;
the only possible residue is an unreferenced immutable revision, never a partially mutated suite.
The registry's unique keys and equivalent-content checks also make concurrent retries fail closed.

### 4.3 Execute with a stored fixture

```http
POST /api/testing/executions
Authorization: Bearer bloge-aneke-demo-token
X-Purpose: TEST_EXECUTION
Content-Type: application/json
```

```json
{
  "schemaVersion": "bloge.testExecutionRequest.v1",
  "target": {
    "kind": "GRAPH",
    "id": "loanDecisionPolicy",
    "fingerprint": "sha256:<from-target-descriptor>"
  },
  "executionPurpose": "GRAPH_CONTRACT_TEST",
  "context": {"applicantId": "prime", "requestedAmount": 450000},
  "fixtureBundle": null,
  "fixtureBundleRef": {
    "fixtureBundleId": "loan-prime-v1",
    "revision": 1,
    "fingerprint": "sha256:<returned-by-registration>"
  },
  "verbosity": "FULL",
  "metadata": {"suiteId": "loan-decision-regression", "caseId": "prime-r1"}
}
```

Exactly one of `fixtureBundle` and `fixtureBundleRef` is required. Inline bundles are fingerprinted
immediately but always produce `EXPLORATORY` evidence. Stored bundles can produce `CERTIFIABLE`
evidence only when there is no schema waiver and each mocked resource site is protocol-derived or
transport-level rather than an output-level self-report.

### 4.3.1 Execute a frozen operator binding

Register the fixture through the same fixture endpoint with `target.kind=OPERATOR`, then submit:

```http
POST /api/testing/targets/operators/customer.normalize/executions
Authorization: Bearer bloge-aneke-demo-token
X-Purpose: TEST_EXECUTION
Content-Type: application/json
```

```json
{
  "schemaVersion": "bloge.testOperatorExecutionRequest.v1",
  "target": {
    "kind": "OPERATOR",
    "id": "customer.normalize",
    "fingerprint": "sha256:<from-operator-target-descriptor>"
  },
  "executionPurpose": "OPERATOR_UNIT_TEST",
  "input": {"name": "Ada"},
  "fixtureBundle": null,
  "fixtureBundleRef": {
    "fixtureBundleId": "normalize-contract",
    "revision": 1,
    "fingerprint": "sha256:<returned-by-registration>"
  },
  "verbosity": "FULL",
  "metadata": {"suiteRef": "customer-normalization", "caseRef": "uppercase"}
}
```

The service converts JSON input to the registry-declared Java input type, runs the exact binding as
node `subject`, and returns signed `bloge.testExecutionResponse.v2`. The test-kit still accepts
historical unsigned v1 responses, but the server does not issue v1 for new executions. Stored provenance alone is never enough
for certification: an opaque binding, unformalized configured state, schema waiver, or output-level
resource replacement forces `EXPLORATORY`. `HttpResourceOperator` earns `CERTIFIABLE` only when its
selected resource interactions use strict `boundary=TRANSPORT` protocol responses.

### 4.3.2 Run from Author Canvas

In `/author/`, double-click a node and open `Executable Operator Suite`. `Run Case` and
`Run Exploratory` perform the rapid inline path:

1. Resolve `lowering.operatorRef` (or the visual `operatorRef`) and discover the frozen target.
2. Reject `OPAQUE_RUNTIME` and unsupported targets before execution.
3. For a native binding, run real code with a strict node-level `SPY` rule.
4. For a resource visual operator, lower the visual input to `{resourceId, params}`, run
   `httpResource`, and inject the editable `Transport response` only at `TRANSPORT` boundary.
5. Compare native whole output or resource `/payload` against `Expected output`, then show the real
   run id, evidence class, diagnostics, and actual subject output in the table row.

The canvas sends `X-Purpose: TEST_EXECUTION` and obtains authorization headers from a replaceable
host provider. The standalone demo provider uses the test-profile demo identity; a VSCode or embedded
host must inject its own short-lived credential. Testing endpoints exist only in test/staging profiles
and are absent in production.

`Publish Case + Run` and `Publish Suite + Run` perform the provenance-bearing path. Each row declares
one governance intent: `GOLDEN`, `NEGATIVE`, `BOUNDARY`, or `REGRESSION`. The client then:

1. discovers one exact `bloge.testOperatorTargetDescriptor.v2` target under `TEST_SUITE_WRITE` and
   requires the runtime id plus a full SHA-256 implementation-closure fingerprint;
2. canonicalizes every lowered input, transport fixture, expected output, and row identity, derives
   a bounded content-addressed fixture id, and registers immutable revision 1 under
   `TEST_FIXTURE_WRITE`;
3. builds one content-addressed `bloge.testSuite.v1` revision whose cases retain exact fixture refs
   and case intent, with coverage requiring every represented case type and promotion requiring all
   cases to pass with certifiable evidence;
4. registers the suite under `TEST_SUITE_WRITE`, verifies the full returned immutable suite value
   including classification, target, ordered cases, inputs, intents, fixture refs, metadata,
   coverage policy, promotion policy, and authoritative fingerprint;
5. executes that exact suite revision under `TEST_EXECUTION` with `COLLECT_ALL` and a deterministic
   caller idempotency key;
6. accepts aggregate evidence only when the suite ref, target, request id, case ids, case intents,
   fixture refs, and suite-run identity match the submitted intent, and when child run presence,
   evidence class, assertion counters, coverage, promotion, and aggregate status are internally
   consistent.

`Publish Case + Run` uses the same protocol with a legitimate one-case suite; it is not a shortcut
around suite governance. `Publish Suite + Run` publishes all valid rows as one revision and renders
payload-free child run links plus aggregate execution, coverage, and promotion status. Repeating
unchanged content reuses the same immutable assets and idempotent suite run; changing any relevant
target, case, fixture, or intent creates a different content address rather than mutating history.
The table is read-only while either path is running. Starting an exploratory run clears any previous
governed publication banner before execution so stale evidence is never presented as current.

`Run*` remains the fast inline `EXPLORATORY` loop. Published provenance is necessary but does not
promise `CERTIFIABLE`: target composability, strict schema checks, and fixture fidelity still govern
the server-authoritative child evidence. Likewise `ELIGIBLE` is only the suite policy verdict, not a
certificate, approval, or publication decision. `Apply Fixture` only writes a row back to the visual
draft's ordinary node fixture and never registers a control-plane asset.

The focused protocol, UI, negative-test, and real-browser evidence is recorded in
[Stage 2 Canvas suite publication verification](resource-gateway-execution-data-control-plane-stage2-canvas-suite-publication-verification.md).

### 4.3.3 Signed child-run evidence

Every new graph or operator execution sanitizes the complete `bloge.testRunEvidence.v2`, computes its
semantic-result and complete-evidence SHA-256 fingerprints, and signs a domain-separated canonical
envelope containing the latter fingerprint and the signing time with the shared Resource Gateway
evidence signer. It immediately
verifies the detached signature before persistence. The current response is:

```json
{
  "schemaVersion": "bloge.testExecutionResponse.v2",
  "runId": "<run-id>",
  "target": {"kind": "GRAPH", "id": "loanDecisionPolicy", "fingerprint": "sha256:<target>"},
  "fixtureBundleRef": {"source": "STORED", "fixtureBundleId": "loan-prime-v1",
    "revision": 1, "fingerprint": "sha256:<fixture>"},
  "plan": {"schemaVersion": "bloge.effectiveExecutionPlan.v3", "planFingerprint": "sha256:<plan>"},
  "integrity": {
    "schemaVersion": "bloge.testEvidenceIntegrity.v1",
    "evidenceFingerprint": "sha256:<complete-sanitized-evidence>",
    "signatureStatus": "VERIFIED",
    "keyId": "<verification-key-id>",
    "algorithm": "Ed25519",
    "signedAt": "2026-07-16T00:00:00Z",
    "signature": "<base64-detached-signature>",
    "projection": "FULL",
    "projectionFingerprint": "sha256:<evidence-in-this-response>",
    "independentlyVerifiable": true
  },
  "evidence": {
    "schemaVersion": "bloge.testRunEvidence.v2",
    "runId": "<run-id>",
    "semanticResultFingerprint": "sha256:<stable-business-result>"
  }
}
```

`semanticResultFingerprint` answers whether two runs produced the same deterministic business
result. Its versioned material includes status, execution purpose, target/fixture/plan identity,
stable node/edge coordinates and values, attempts, fixture use, assertions, sorted diagnostics,
semantic execution-service use, logical time, and stable side-effect intents. It excludes run id,
evidence class, timestamps, durations, signatures, response projection, broad governance metadata,
parallel completion order, engine-only provider calls, and volatile side-effect ids. It is recomputed
after redaction, so persisted fingerprints cannot distinguish two values hidden as `[REDACTED]`.

The complete `integrity.evidenceFingerprint` answers whether this exact persisted evidence record,
including run identity and timing, is unchanged. Integrity sealing and reads reject current evidence
whose semantic fingerprint is stale. Explicit evidence v1 remains a dual-read historical shape with
no semantic fingerprint; current response v2 requires evidence v2. `STANDARD` and `SUMMARY` carry the
semantic fingerprint as signed full-evidence lineage but omit values required to recompute it.

The fingerprint canonicalization sorts object properties and map entries, serializes time values as
ISO-8601 text, then hashes the exact UTF-8 JSON bytes. The Ed25519 signature covers a second canonical
SHA-256 value derived from `{schemaVersion, evidenceFingerprint, signedAt}`. This domain separation
prevents a signature from another Resource Gateway evidence protocol from being transplanted and
binds the signing time. Consumers must not hash arbitrary pretty-printed response JSON.

Projection semantics are deliberate:

| verbosity | `integrity.projection` | independently verifiable from this response |
|---|---|---|
| `FULL` | `FULL` | yes, when the two fingerprints are equal and signature status is `VERIFIED` |
| `STANDARD` | `STANDARD` | no; the full-evidence seal is lineage and `projectionFingerprint` identifies the redacted projection |
| `SUMMARY` | `SUMMARY` | no; node and edge traces are omitted |

The service always verifies complete persisted evidence before answering a query, then applies the
requested projection. A changed fingerprint, malformed signature, unsigned historical record, or
inconsistent FULL manifest returns `409 RG.TEST.EVIDENCE_INTEGRITY_INVALID` and emits a security
event. A temporarily unavailable verification authority returns
`503 RG.TEST.EVIDENCE_VERIFICATION_UNAVAILABLE`. If signing a newly completed run fails, the run is
fail-closed to `EVIDENCE_INCOMPLETE + EXPLORATORY` with
`signatureStatus=VERIFICATION_UNAVAILABLE`; it cannot be promoted as certifiable evidence.

Immutable-suite aggregation requests FULL child evidence and verifies every child signature before
trusting its evidence class or counters. An unsigned or altered child produces
`RG.TEST.SUITE_CHILD_EVIDENCE_INTEGRITY_INVALID`, an aggregate `EVIDENCE_INCOMPLETE`, and blocked
promotion. Every initial and subsequent `RUNNING` checkpoint is signed with `scope=CHECKPOINT`
before persistence. A terminal write uses `scope=TERMINAL` and binds the aggregate fingerprint plus
the ordered `{caseId, runId, evidenceFingerprint}` child closure. A reconciliation worker first
verifies the abandoned checkpoint, preserves its closed child references, constructs a fail-closed
terminal aggregate, and signs that terminal result; altered or unsigned checkpoints are rejected
instead of being recovered as trusted progress.

The verification key named by `integrity.keyId` remains available through
`GET /api/integration/evidence-keys/{keyId}` for compatibility and diagnosis. Release consumers use
`GET /api/integration/evidence-keys`, which returns one atomic
`toolStudio.resourceGateway.evidenceVerificationKeySet.v1` snapshot containing validity bounds,
current states, ordered lifecycle events, canonical `snapshotFingerprint`, and an active-key
attestation. The fingerprint must be pinned through an independent governance channel; a snapshot
cannot establish trust merely by signing itself with an embedded key. Managed provider v1 is
explicitly `CURRENT_STATE_ONLY`; provider v2 can claim `COMPLETE` only after lifecycle validation.

### 4.3.4 Export and independently verify terminal suite evidence

Export one payload-free portable bundle after a suite reaches a terminal state:

```bash
curl -sS http://localhost:8080/api/testing/suite-executions/<suiteRunId>/evidence-bundle \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION'
```

The response is `bloge.testSuiteEvidenceBundle.v1` for structural evidence or
`bloge.testSuiteEvidenceBundle.v2` for semantic evidence. It contains `payloadPolicy=OMITTED`, the exact
v1/v2 aggregate, its generation-matched terminal attestation, and a canonical `bundleFingerprint` over
`{payloadPolicy, attestation, evidence}`. Child input/output payloads remain in governed storage;
the bundle carries only signed child evidence references. A `RUNNING` checkpoint, unavailable
signer, unsigned historical record, altered aggregate, or non-terminal attestation cannot be
exported and fails closed through the testing problem protocol.

The suite attestation signs the SHA-256 fingerprint of this canonical material, not the pretty
printed bundle JSON:

```json
{
  "schemaVersion": "bloge.testSuiteRunAttestation.v1",
  "scope": "TERMINAL",
  "suiteRunId": "<suite-run-id>",
  "suiteRef": {"suiteId": "<id>", "revision": 1, "fingerprint": "sha256:<suite>"},
  "requestFingerprint": "sha256:<normalized-suite-request>",
  "aggregateEvidenceFingerprint": "sha256:<aggregate-evidence>",
  "childEvidenceRefs": [
    {"caseId": "<case>", "runId": "<child-run>",
      "evidenceFingerprint": "sha256:<complete-child-evidence>"}
  ],
  "signedAt": "2026-07-16T00:00:00Z"
}
```

Object keys are recursively sorted, arrays retain protocol order, times are ISO-8601 text, and the
canonical UTF-8 bytes are SHA-256 fingerprinted. The Ed25519 signature is over the resulting
`sha256:<64-lowercase-hex>` text. Consumers must preserve child order: sorting or substituting child
references invalidates the signature.

The standalone test-kit implements release-grade verification without depending on server code:

```java
TestSuiteEvidenceBundle bundle = client.findSuiteEvidenceBundle(suiteRunId);
EvidenceVerificationKeySet keySet = client.findEvidenceVerificationKeySet();
String trustedPin = System.getenv("RESOURCE_GATEWAY_EVIDENCE_KEY_SET_PIN");
TestSuiteEvidenceVerifier.VerificationResult result =
        new TestSuiteEvidenceVerifier().verify(bundle, keySet, trustedPin);

if (!result.verified()) {
    throw new IllegalStateException(result.reasonCode());
}

// Convenience form: fetch bundle + atomic key set, then apply the same external pin.
TestSuiteEvidenceVerifier.VerificationResult verified =
        client.verifySuiteEvidence(suiteRunId, trustedPin);
```

The verifier recomputes aggregate, bundle, and signature-material fingerprints; verifies ordered
case/run closure and Ed25519; validates the external pin, snapshot freshness, attestation, key/event
coherence, and exact key membership; then evaluates `ACTIVATED`, `RETIRED`, `DISABLED`, prospective
`REVOKED`, and retroactive `COMPROMISE_DECLARED` at the evidence signing time. Its outcomes are
`VERIFIED`, `INVALID`, `KEY_UNAVAILABLE`, or `POLICY_REJECTED`, with payload-free reason codes suitable
for CI logs. It never trusts the producer's `VERIFIED` field by itself. The single-key overload remains
a compatibility path and is insufficient for a release gate.

These bundles are portable integrity and provenance facts, not complete certification packages.
It deliberately does not contain replay payload attachments, transparency-log inclusion proof,
ANEKE workbook projection, publish-gate decision, or owner approval. Key lifecycle is a separate
signed and pinned protocol rather than copied into every bundle.

The protocol invariants, negative matrix, and reproducible gates are recorded in
[Stage 3 suite attestation verification](resource-gateway-execution-data-control-plane-stage3-suite-attestation-verification.md)
and [Stage 3 key lifecycle verification](resource-gateway-execution-data-control-plane-stage3-key-lifecycle-verification.md).
Semantic generation rules and negative compatibility evidence are recorded in
[Stage 3 semantic coverage verification](resource-gateway-execution-data-control-plane-stage3-semantic-coverage-verification.md).

### 4.3.5 Project one exact semantic suite into an ANEKE workbook seed

The draft-oriented `CorrectnessWorkbookBundle.v1` remains frozen for historical visual contract
tables. Typed testing-control-plane suites use a separate exact-revision projection:

```bash
curl -sS \
  http://localhost:8080/api/integration/test-suites/<suiteId>/revisions/<revision>/semantic-correctness-workbook \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: WORKBOOK_SYNC'
```

The payload is `toolStudio.resourceGateway.semanticCorrectnessWorkbookBundle.v1`. The endpoint is
available only when the isolated test runtime is present (`test` or `staging`) and requires one exact
`bloge.testSuite.v2` revision. It never infers a suite from a draft or graph display name. Historical
`bloge.testSuite.v1` is rejected with a stable conflict instead of being interpreted as an empty
semantic policy.

The projection carries payload-free case/fixture identities, structural and typed semantic policies,
the newest retained terminal v2 aggregates, signed semantic and promotion verdicts, and v2 terminal
attestation references. Case input, fixture values, suite metadata values, child input/output, and
free-text diagnostics are omitted. Omitted metadata is committed by fingerprint. Each evidence row
links to its URL-encoded portable bundle endpoint; consumers must fetch and independently verify that
bundle against an out-of-band pinned key set before using it in a release decision.

The manifest distinguishes states that governance must not collapse:

| `projectionStatus` | Meaning | Gate treatment |
|---|---|---|
| `READY` | At least one verified `PASSED + SATISFIED + ELIGIBLE` aggregate exists | seed may enter ANEKE validation |
| `NO_TERMINAL_EVIDENCE` | no retained terminal candidate exists | block or require execution |
| `VERIFICATION_UNAVAILABLE` | at least one candidate could not be checked because the authority was unavailable | retry; never treat as missing or passing |
| `NO_ELIGIBLE_EVIDENCE` | verified evidence exists but none satisfies semantic and promotion policy | block |

At most 100 newest verified terminal rows are projected. The manifest binds candidate count,
unavailable count, and `evidenceTruncated`; consumers must not mistake this bounded seed for a complete
history API. An invalid/unsigned record, suite/evidence/attestation generation mismatch, target/scope
drift, or fingerprint mismatch fails the whole request closed. This projection is a governance seed,
not an ANEKE publish decision and not a replacement for portable-bundle verification.

The machine contract is
[semantic-correctness-workbook-bundle-v1.schema.json](schemas/tool-studio-resource-gateway/semantic-correctness-workbook-bundle-v1.schema.json).
The independent Java consumer uses `findSemanticCorrectnessWorkbook(...)` and
`SemanticCorrectnessWorkbook.requireGateReady()`; the verification and negative matrix are recorded in
[Stage 3 ANEKE semantic workbook verification](resource-gateway-execution-data-control-plane-stage3-aneke-semantic-workbook-verification.md).

### 4.3.6 Bind semantic workbooks into a governance gate decision

ANEKE submits `toolStudio.resourceGateway.gateResult.v3` to
`POST /api/integration/gate-results` with purpose `GOVERNANCE_GATE_FEEDBACK`. v3 retains the v2
draft workbook/snapshot/policy basis and adds `decisionBasis.semanticWorkbooks`. Each entry contains
the exact suite and target, source bundle fingerprint and status, bounded manifest counts/truncation,
and the complete ordered list of projected `suiteRunId + evidenceFingerprint` values.

Resource Gateway resolves exact runs rather than latest history, verifies terminal semantic aggregate
and attestation generations, rebuilds the source bundle, and compares its fingerprint. A later run
therefore does not invalidate the decision. Missing retained evidence, target/suite drift, or invalid
signatures fail closed; verification-store or authority outage yields 503 on write and
`UNVERIFIABLE` freshness on an already accepted decision.

For `PASSED`, all semantic workbooks must be gate-ready, at least one must target `GRAPH`, policy must
require `SEMANTIC_CORRECTNESS`, and that check's refs must equal all source bundle fingerprints. The
graph target must equal the composite target obtained by lowering and compiling the exact GraphDraft;
an operator target must occur in the draft and match its current runtime closure. v2 remains supported
for structural workbook compatibility but cannot carry semantic refs.

The machine contract is
[governance-gate-result-v3.schema.json](schemas/tool-studio-resource-gateway/governance-gate-result-v3.schema.json).
The independent client validates both request and acknowledgement with
`submitGovernanceGateResult(...)`. Full negative and compatibility evidence is recorded in
[Stage 3 semantic gate basis verification](resource-gateway-execution-data-control-plane-stage3-semantic-gate-basis-verification.md).

### 4.4 Query a run or run a batch

```bash
curl -sS 'http://localhost:8080/api/testing/executions/<runId>?verbosity=SUMMARY' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: TEST_EXECUTION'
```

`POST /api/testing/executions/batch` accepts 1-100 independent requests. Stage 2 runs them
sequentially; one item cannot share mutable plan or fixture-consumption state with another.

### 4.5 Java, JUnit 5, and CI test kit

Java consumers do not need to assemble wire payloads or CI reports manually. The independent
`bloge-resource-gateway-test-kit` module provides graph/operator target, fixture/suite revision,
child/suite-run projections, strict `FixtureBundleBuilder` and `TestSuiteBuilder` builders, dynamic
attempt/occurrence selector methods, a bounded
JDK HTTP client, JUnit 5 assertions, and JUnit XML:

```bash
mvn -f resource-gateway-test-kit/pom.xml clean install
```

The client exposes immutable suite register/find/execute/query operations, terminal evidence-bundle
export, exact-key and atomic key-set lookup, pinned lifecycle-aware Ed25519 verification, and a typed
`materializeBuiltInGraphContractCatalog()` operation. It also executes and queries bounded suite
stability, re-derives the complete stability/source closure, and verifies its detached signature
against a caller-owned atomic-key-set fingerprint pin. It also exposes challenge-bound request-index
replica-proof collection and `WorkerQuarantineRequestIndexFleetGateVerifier`: callers provide the
exact deployment instance set, expected scope/artifact/protocol/target, cohort window, complete key
set, and independently distributed key-set pin; the offline gate rejects partial or mixed fleets.
Execution requires an
exact revision, full SHA-256 fingerprint, and explicit `clientRequestId`; malformed identities are
rejected before any network call. The exact packaged Draft 2020-12 schema validates complete suite
registration and execution values at runtime, and every returned suite/run identity is rebound to the
originating request before it can reach assertions or reporters. It accepts suite execution response
v1 as an explicitly unsigned migration shape, structural v2, and semantic v3. `TestRun.integrity()`
validates and exposes the child v2 signature/projection manifest, while
`TestRun.semanticResultFingerprint()` and `TestRunAssertions.assertSameSemanticResult` support
payload-free deterministic regression comparisons. `TestSuiteRun.attestation()`
exposes the aggregate checkpoint/terminal signature, while `findSuiteEvidenceBundle`,
`findEvidenceVerificationKeySet`, and the pinned `verifySuiteEvidence` overload provide the
release-grade consumer-verification path. The exact-key overload remains available for migration.
`TestSuiteBuilder` keeps structural suites on v1 and automatically emits v2 after
`requireBranchTransferred`, `requireBranchSkipped`, `requireDecisionRule`, `requireRetry`,
`requireFallback`, `requireTimeout`, or `requireCompensation` is called. `TestSuiteRun` exposes
payload-free case links, structural coverage, and promotion eligibility. Semantic-aware consumers
call `requireSemanticCoverage()`; historical v1 fails with `SEMANTIC_COVERAGE_UNAVAILABLE` instead
of appearing empty and satisfied. `TestSuiteRunAssertions` separates
execution, case, coverage, and eligibility assertions.

`clean package` also emits an executable `*-cli.jar`. It reads the bearer token only from
`RESOURCE_GATEWAY_TOKEN`, requires the exact suite reference and caller-owned idempotency key, writes
payload-free JUnit XML, and returns `0` only when the selected `STANDARD`, `MUTATION`, or `STABILITY`
typed gate passes, `1` for a governed terminal quality/trust failure, and `2` when no trustworthy
verdict can be produced. `STABILITY` additionally requires 3..20 attempts and an externally supplied
key-set fingerprint pin. An explicit
`--allow-non-eligible` relaxes only eligibility; it never relaxes case or coverage correctness. A
valid but non-terminal `RUNNING` checkpoint also exits `2`, because no governed gate verdict exists.

The client requests a fresh bearer credential for every operation, supplies the correct
`X-Purpose`, rejects protocol-version drift and oversized bodies, and omits payload/problem details
from exceptions and reports. Its typed `TestRun` projection retains node/site/correlation/occurrence,
retry-attempt, and edge endpoint facts without carrying payload values; legacy v1 responses remain
readable as zero-coordinate summaries. Unknown CLI argument values are never echoed, and test-kit
`clean verify` fails on public JavaDoc warnings. See the
[test-kit guide](../resource-gateway-test-kit/README.md) for a complete discover, register, execute,
assert, report, and exact-inventory rollout-gate example.

### 4.6 Run the built-in graph dogfooding catalog

The compatibility graph-suite adapter delegates to the same execution-control kernel. Its stored
catalog covers all seven built-in graphs with 14 cases. The old endpoints remain available for
authoring compatibility:

```bash
curl -sS http://localhost:8080/api/gateway/graphs/contracts/tests/suites
curl -sS -X POST http://localhost:8080/api/gateway/graphs/contracts/tests/suites/run-all
```

All resource rows are explicit F3 transport fixtures. `minUses/maxUses` declares retry cardinality;
old rows that omit fidelity/cardinality fields remain one-use `OUTPUT_LEVEL` fixtures and therefore
remain exploratory. `enrichOrderList` includes a certifiable two-item parallel foreach case whose
nested shipping and invoice calls are independently controlled and occurrence-addressed. The
detailed matrix and unreachable-endpoint proof are in
[Stage 2 dogfooding verification](resource-gateway-execution-data-control-plane-stage2-dogfooding-verification.md).

For governed API/CLI/CI use, call the materialization endpoint from section 4.2.5 and execute the
returned exact suite references through `/api/testing/suites/{suiteId}/executions`. The end-to-end
proof that all seven materialized suites return `PASSED + SATISFIED + ELIGIBLE` against unreachable
real endpoints is recorded in
[Stage 2 catalog materialization verification](resource-gateway-execution-data-control-plane-stage2-catalog-materialization-verification.md).

## 5. Verbosity And Persistence

| Verbosity | HTTP response | Persisted record |
| --- | --- | --- |
| `SUMMARY` | status, fingerprints, consumption, assertions, diagnostics; no node/edge trace | full sanitized evidence |
| `STANDARD` | node/edge status and fidelity; payload values omitted | full sanitized evidence |
| `FULL` | sanitized node input/output and edge values | full sanitized evidence |

Sensitive keys, bearer/basic credentials, labeled secrets, oversized collections, deep objects, and
long strings are redacted or truncated before `rg_test_run_records` is written. Raw in-memory
`GraphResult` is never persisted by this API.

### 5.1 Occurrence and retry coordinates

`NodeTrace` is one logical node occurrence, not one row per retry. Consumers must use the complete
coordinate instead of joining on local `nodeId`:

| Field | Meaning |
| --- | --- |
| `invocationSiteId` | stable structural primary/compensation site id |
| `graphPath` | path of the graph owning the node, such as `/root/enrichOrders/foreach` |
| `correlationKey` | runtime foreach/loop or business correlation coordinate |
| `occurrence` | one-based binding count for this invocation site |
| `graphOccurrence` | one-based execution of the containing graph; joins sibling nodes and edges even when a branch skips a site |
| `attempts[]` | ordered actual delegate calls inside the occurrence; `attempt` is one-based |

`occurrence=0`, `graphOccurrence=0`, or `attempt=0` is reserved for a legacy producer that cannot
provide that coordinate. Current synchronous execution emits positive coordinates. A retry does not
increase `occurrence`; it appends another `AttemptTrace`, preserving the distinction between
"the second foreach item" and "the second retry of one item".

Fixture selectors use the same coordinates as evidence. `attempts=[1,2]` matches either delegate
attempt inside an occurrence; `occurrences=[2]` matches only the second binding for that site and
correlation key. When both are present, both constraints must hold. This identity reuse is what makes
the control plan auditable against the resulting trace.

`EdgeTrace` carries `graphPath`, `correlationKey`, `graphOccurrence`,
`fromInvocationSiteId`, and `toInvocationSiteId`. Its status is:

- `TRANSFERRED`: source completed successfully or was mocked and the target was actually invoked;
- `SKIPPED`: a conditional edge was not selected after a successful source;
- `NOT_TRANSFERRED`: source did not produce a transferable value or the target was not invoked for a
  non-conditional path.

A target that later fails still has an incoming `TRANSFERRED` edge: transfer evidence describes data
movement, while node evidence describes processing outcome. `STANDARD` responses retain all
coordinates and attempt status/fidelity but omit node, attempt, and edge payload values. `FULL`
returns sanitized values.

## 6. Status Model

The public evidence status is exactly one of:

| Status | Meaning |
| --- | --- |
| `PASSED` | execution, assertions, and fixture consumption passed |
| `ASSERTION_FAILED` | graph completed but a business assertion failed |
| `EXECUTION_FAILED` | graph or controlled operator failed unexpectedly |
| `CONTROL_PLAN_REJECTED` | selector, fingerprint, behavior, or safety preflight rejected the plan |
| `FIXTURE_UNMATCHED` | an external invocation had no approved matching fixture |
| `FIXTURE_UNUSED` | a required fixture rule was not consumed |
| `CONTROL_PLAN_UNAVAILABLE` | checkpointed plan/provider state cannot be validated or restored exactly |
| `EVIDENCE_INCOMPLETE` | execution ended but sanitized evidence could not be durably committed |
| `CANCELLED` | controlled cancellation |
| `TIMED_OUT` | an injected or run-level timeout was not recovered |

`MOCKED` is a node observation, not a top-level terminal status.

## 7. Production Boundary

The production run routes below are guarded before Jackson DTO deserialization:

- `/api/gateway/resources/execute`
- `/api/gateway/examples/compose/run`
- `/api/visual/drafts/run`
- `/api/visual/drafts/{draftId}/run`
- `/api/visual/publications/{publicationId}/run`

Nested `controlPlan`, `requestedControls`, `fixtureBundle`, `fixtureBundleRef`, `executionPurpose`,
`testMode`, mock, or behavior-override fields return
`RG.PRODUCTION.CONTROL_FIELD_FORBIDDEN`. The rejection must first commit a credential-free
`PRODUCTION_RUN_CONTROL_GUARD` audit record; audit failure returns 503 and remains fail closed.

## 8. Current Stage 2-4 Boundaries

Implemented now:

- public graph target discovery/execution/batch/query and operator target discovery/micro-graph
  execution APIs, sharing one fixture registry, evidence model, and run store;
- profile, identity, purpose, tenant/environment, and classification gates;
- exact governed replay-payload capture with signed source lineage, server-side sanitization,
  independent retention, and payload-free expiry tombstones;
- exact governed `REPLAY` execution with pre-plan closure resolution, immutable integrity and
  lifecycle rechecks, run-scoped payload freezing, payload-free plan v2 lineage, BLOGE output-schema
  gating, zero real-operator calls, `REPLAYED` evidence, and certification downgrade propagation;
- independent datasource, tables, retention, evidence sanitization, and security events;
- immutable plan plus graph/operator/resource dependency fingerprints;
- profile-sensitive capability probe and production control-field guard.
- profile-gated authenticated durable graph and operator creation, payload-free execution query, v2 owner claim,
  authenticated heartbeat, one-signal suspended-or-terminal recovery steps, and compatible
  terminal-only recovery,
  with exact hidden-dispatch lookup, principal and reauthorization continuity, database-time fencing,
  immutable pre-execution replay, isolated cold execution, and atomic BLOGE/checkpoint/receipt/audit
  commit; terminal v1 receipts remain explicitly incomplete and promotion-blocking.
- standalone Maven test-kit with HTTP client, fail-closed fixture/suite builders, child/suite-run
  projections, JUnit 5 assertions, fail-closed CI CLI, payload-free JUnit XML, executable shaded
  artifact, typed built-in catalog materialization, and packaged canonical JSON Schema.
- complete seven-graph/14-case built-in dogfooding catalog, F3 legacy-suite migration into exact
  fixture/TestSuite revisions, four case intents, numeric tolerance, bounded retry consumption, and
  a Spring proof that root and synchronous nested resource calls do not escape fixtures.
- run-scoped advancing logical clock plus bounded `DELAY` and `TIMEOUT`; timeout injection uses the
  real BLOGE retry/fallback chain and emits normalized logical-time evidence.
- one-based attempt/occurrence selectors with canonical bounds, specificity ordering, proven-disjoint
  peers, explicit lower-precedence fallback, runtime unmatched fail-closed behavior, and the
  `dynamicAttemptOccurrenceSelectors` capability marker.
- recursively frozen synchronous subgraph/foreach/loop/compensation sites, with run-scoped fixture
  propagation and fail-closed cycle/depth/site limits.
- occurrence-addressable synchronous node/attempt/edge evidence, including runtime correlation and
  containing-graph occurrence coordinates; non-empty parallel foreach certification is enabled.
- operator implementation closure, schema, runtime-state, and resource dependency fingerprints;
  stateless and explicitly snapshot-providing configured bindings can certify, while opaque state
  fails closed.
- Author Canvas `Executable Operator Suite` target discovery, native `SPY`, resource
  `TRANSPORT` lowering, real exploratory run/evidence display, four case intents, content-addressed
  fixture and first-class suite publication, exact-revision aggregate execution, coverage/promotion
  display, response-intent validation, and opaque-target fail-closed behavior.
- first-class immutable `bloge.testSuite.v1` structural and `bloge.testSuite.v2` semantic protocols,
  dependency-closed dual-read registry, independent read/write purposes,
  target/fixture/classification drift checks, JDBC persistence, and capability discovery.
- idempotent immutable-suite runner for graph and operator targets, durable per-case checkpoints,
  fail-fast/collect-all scheduling, child evidence identity checks, aggregate structural coverage,
  promotion eligibility verdict, suite-run query, and capability discovery.
- process-owner suite-run leases, long-child heartbeats, database checkpoint fencing, and bounded
  fail-closed reconciliation of abandoned `RUNNING` checkpoints.
- detached Ed25519 signatures over complete sanitized graph/operator child-run evidence, immediate
  producer verification, verification on read, projection lineage, and a signed-child requirement
  before suite aggregation can promote evidence.
- signed suite `CHECKPOINT` and `TERMINAL` attestations, persistence/read/reconciliation verification,
  ordered child evidence closure, payload-free terminal bundle export, verification-key lookup, and
  independent Ed25519 verification in the standalone test-kit.
- typed branch transfer/skip, decision-rule, retry, fallback, timeout, and compensation requirements;
  certifiable-evidence-only aggregation; distinct missing/unavailable verdicts; generation-matched
  aggregate attestation/bundle export; and fail-closed test-kit semantic projection.

Still intentionally outside this increment:

- streaming/suspendable controls and evidence, including an explicit stream offset/checkpoint
  recovery protocol;
- signed certification decisions, transparency-log proof, trusted pin distribution, ANEKE
  N/N-1 release-matrix conformance, semantic equivalent-mutant proof, flaky/quarantine rerun analysis,
  and statistical mutation confidence; semantic workbook fingerprints now enter
  `GovernanceGateResult.v3` through a reconstructable exact-evidence basis, but the ANEKE publish
  decision itself remains outside Resource Gateway;
- automatic case resume after an abandoned run, independent cross-failure-domain recovery queues,
  queued priority/fairness scheduling, runtime-state delivery to remote workers, adaptive
  quota/autoscaling, external alert routing, and suite-history list/trend APIs;
  database-authoritative immediate admission now enforces tenant/suite/operator/dependency capacity,
  while payload-free worker acquisition transfers only control ownership, not executable state;
- dispatcher/polling, cross-process recovery supervision and multi-boundary recovery orchestration,
  hard worker cancellation, typed identity/flag/secret
  authorities, explicit
  streaming offset/checkpoint recovery, and deterministic concurrent scheduling;
- a physically separate test-runtime deployment and network policy;
- certification of streaming foreach/loop graphs until their invocation and edge evidence is
  occurrence-addressable and built-in suites exercise it.

Those items remain visible in the two industrial testability evolution plans and must not be inferred
as complete from `executionEndpointEnabled=true`.
