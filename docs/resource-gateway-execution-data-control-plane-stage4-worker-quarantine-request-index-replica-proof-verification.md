# Stage 4 Worker Quarantine Request-Index Replica-Proof Verification

## Purpose

Per-replica capability flags made rollout mode observable but did not make the observation fresh,
challenge-bound, artifact-bound, or tamper evident. This increment adds a test/staging-only endpoint
that signs one process's exact request-index transition facts. It narrows the trust gap between the
Resource Gateway runtime and an external deployment gate without pretending that the application
can discover its own complete fleet.

## Signed Material

`POST /api/testing/durable-state/worker-quarantines/request-index/replica-proofs` accepts
`bloge.workerQuarantineRequestIndexReplicaProofRequest.v1` with a random `32..128` character
challenge and one immediate target: `DUAL_READ_KEYED_WRITE` or `KEYED_ONLY`.

The returned `bloge.workerQuarantineRequestIndexReplicaProof.v1` envelope covers canonical
`bloge.workerQuarantineRequestIndexReplicaProofMaterial.v1` with an Ed25519 evidence seal. Material
contains only these deployment and rollout facts:

| Fact | Root of trust and purpose |
| --- | --- |
| challenge | deployment gate; prevents reuse across evaluations |
| deployment scope fingerprint | verified tenant/organization/project/environment/region identity |
| instance id | deployment inventory; binds one stable serving slot |
| startup id | process-generated UUID; prevents counting one cached process as another start |
| artifact fingerprint | deployment-supplied immutable image/application SHA-256 |
| protocol/current/target mode | runtime binary and exact immediate transition |
| live inventory | repeatable-read DB-clock generation counts and latest expiries |
| transition flag/blockers | closed local policy verdict |
| expiry | DB observation time plus a bounded `5..300` second TTL |

The material fingerprint and seal fingerprint must be identical. Seal time must not predate DB time
by more than five minutes and must be strictly before the exclusive proof expiry. Signing or audit failure returns a
payload-free `503`; no unsigned fallback proof escapes.

## DB Inventory

The inventory query runs in a read-only `REPEATABLE_READ` transaction and uses database time as its
live-row boundary. It groups only record version and non-secret key id. The authority rejects an
unknown record version, malformed generation, unavailable live HMAC key, inconsistent aggregate,
or more than 16 live keyed generations. It exports no request id, request fingerprint, tenant,
scope, credential, claim token, or business payload.

## Authorization And Configuration

The endpoint exists only in `test` and `staging`. The verified identity must use exact purpose
`TEST_RUNTIME_MAINTENANCE`, belong to the configured worker-quarantine operator group, satisfy its
clearance, and include project plus region. Rejection and issuance are security-audited without
payloads.

`staging` requires `RG_RESOURCE_GATEWAY_INSTANCE_ID` and a canonical lowercase
`RG_RESOURCE_GATEWAY_ARTIFACT_FINGERPRINT=sha256:<64 hex>`. The launcher rejects missing or malformed
values before startup. `RG_TEST_WORKER_QUARANTINE_REQUEST_INDEX_PROOF_TTL_SECONDS` defaults to 120
and is fail-closed outside `5..300` whole seconds. Release gates must use a managed signer and an
independently pinned verification-key policy; the local database signer and test defaults are only
for demonstration.

## Transition Matrix

| Current | Requested target | Inventory condition | Signed result |
| --- | --- | --- | --- |
| `LEGACY_READ_WRITE` | `DUAL_READ_KEYED_WRITE` | no live keyed rows | allowed |
| `LEGACY_READ_WRITE` | `DUAL_READ_KEYED_WRITE` | live keyed rows | `LIVE_KEYED_ROWS_PRESENT` |
| `DUAL_READ_KEYED_WRITE` | `KEYED_ONLY` | no live legacy rows | allowed |
| `DUAL_READ_KEYED_WRITE` | `KEYED_ONLY` | live legacy rows | `LIVE_LEGACY_ROWS_PRESENT` |
| any non-predecessor | either target | any | `CURRENT_MODE_NOT_PREDECESSOR` plus applicable inventory blocker |
| any | `LEGACY_READ_WRITE` or unknown | any | request rejected with `400` |

## Offline Exact-Inventory Gate

The independent `resource-gateway-test-kit` now supplies three public protocol types:

- `WorkerQuarantineRequestIndexReplicaProof` strictly parses the packaged authoritative schema and
  retains exact signed JSON material for canonical fingerprint verification;
- `WorkerQuarantineRequestIndexFleetPolicy` carries the deployment authority's challenge, scope,
  target, artifact, protocol, exact serving-instance set, external key-set pin, and maximum cohort
  observation spread;
- `WorkerQuarantineRequestIndexFleetGateVerifier` verifies the complete cohort offline without any
  Resource Gateway server implementation dependency.

The fleet gate first requires exact set equality: missing, unexpected, duplicate instance ids and
duplicate process-start UUIDs all fail before any partial success can be reported. It then verifies
one bounded cohort window, every immutable policy binding, the immediate predecessor mode, target-
compatible inventory, DB-clock freshness, `5..300` second TTL, exclusive expiry, canonical material
fingerprint, the externally pinned complete evidence key set, current active-key policy, and every
Ed25519 signature. Its result contains only a bounded reason, counts, and optional deployment
instance/key ids; no business payload is accepted or emitted.

`ResourceGatewayTestClient.requestWorkerQuarantineRequestIndexReplicaProof` uses exact purpose
`TEST_RUNTIME_MAINTENANCE`, validates the request and response with the packaged schema, and binds the
response to the requested challenge and target. The caller must construct one client per directly
routable instance endpoint. Using one load balancer repeatedly cannot establish exact inventory.

## Failure Matrix

| Counterexample | Required result |
| --- | --- |
| unknown request field, short challenge, or unsupported target | strict `400`; no proof |
| production profile | controller and proof service absent |
| wrong purpose, group, clearance, environment, or incomplete scope | `401/403`; audited rejection |
| unavailable live keyed generation | payload-free `503`; no misleading inventory |
| audit append or signing fails | payload-free `503`; no unsigned proof |
| transition invariant fails | valid signed proof with closed blocker, never a false transport outage |
| proof replayed for another rollout | challenge mismatch at the external gate |
| cached proof survives restart | startup-id mismatch or stale expiry at the external gate |
| one replica is omitted, repeated, or replaced by an unknown instance | test-kit exact-set gate rejects the cohort |
| two instance ids return one process-start UUID | test-kit rejects duplicate process identity |
| proofs span more than the policy cohort window | test-kit rejects a non-coherent fleet observation |
| scope, artifact, protocol, target, or predecessor mode drifts | test-kit rejects the exact proof |
| material fingerprint, key-set pin, key policy, or signature is invalid | offline gate fails closed |

## Verification Gate

The focused gate covers strict transport parsing, authorization, signer and audit failure, signed
allowed and blocked proofs, material fingerprint/signature verification, capability publication,
profile isolation, Spring application assembly, and DB inventory invariants:

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml \
  -Dtest=WorkerQuarantineRequestIndexRolloutServiceTest,WorkerQuarantineRequestIndexRolloutControllerTest,DatabaseDurableWorkerQuarantineControlPlaneTest,TestingControlProtocolSchemaTest,TestabilityCapabilitiesTest,TestRuntimeProfileIsolationTest,TestRuntimeApplicationIntegrationTest test
```

The release gate remains Resource Gateway `clean verify`, independent test-kit `clean verify`,
`bash -n`, staging launcher negative checks, and executable-JAR inspection.

The test-kit focused gate is:

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=WorkerQuarantineRequestIndexFleetGateVerifierTest,ResourceGatewayTestClientTest test
```

Verified on 2026-07-17: the test-kit focused gate passed 31 tests; the server rollout-service gate
passed 6 tests; the catalog startup regression passed 2 tests. Independent test-kit `clean verify`
passed 74 tests with no failures, errors, or skips, including authoritative-schema, shaded-CLI, and
public-JavaDoc gates. Resource Gateway `clean verify` passed 2257 tests with no failures or errors,
2 conditional skips, and a packaged executable JAR.

## Honest Boundary

A proof establishes what one reachable, new-binary process signed about one DB snapshot. It does not
establish that the caller reached every serving process. Resource Gateway has no independent view of
unregistered, partitioned, shadow, stale, or N-1 instances. The deployment platform remains the
authority for exact serving inventory, direct instance routing, immutable artifact identity, and
verification-key trust.

The offline verifier closes cohort aggregation only for the inventory it is given. It cannot prove
that the deployment inventory itself is complete, that direct routing did not silently traverse a
load balancer, or that an unmanaged/partitioned process is absent. Artifact fingerprint is still a
deployment assertion, not a self-measured image digest. The deployment platform must bind instance
ids to direct endpoints and immutable image digests, distribute the key-set pin independently, and
run one gate per identity-derived region scope. Multi-region simultaneity, old-binary conformance,
artifact transparency, and rollback-drill certification remain higher-level release controls.
