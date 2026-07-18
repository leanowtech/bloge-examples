# Stage 5 Suite-Stability Authority Trust Cohort Verification

## Decision

Dynamic JWKS refresh solves restart-free key rotation for one Resource Gateway process. It does not
prove that every process able to accept or claim a stability job is authorizing against the same
complete trust generation. During rotation, rollout, network partition, or scheduler failure, two
individually healthy replicas can therefore make different current-authority decisions.

The optional authority-trust cohort control closes that gap for an **exact deployment-configured
serving set**. A stability job may be freshly submitted or durably claimed only when all configured
slots have one live process start, equivalent immutable deployment facts, healthy local dynamic
trust, and one complete JWKS-generation fingerprint. Existing post-claim reauthorization runs the
same gate again before business execution.

This control is fail-closed and test/staging-only. It does not turn Resource Gateway into a service
discovery or deployment-governance system.

> Follow-up: staging now binds this cohort to an externally signed exact inventory and durable
> revision floor. This document remains the authority for cohort convergence and local configured
> test mode; fleet-completeness controls are verified in
> [Stage 5 signed serving-inventory verification](resource-gateway-execution-data-control-plane-stage5-suite-stability-serving-inventory-verification.md).

## Root Cause

Single-process readiness has four blind spots:

1. Local JWKS health cannot observe a peer that missed a refresh.
2. A replica count does not prove exact membership; a wrong replica can replace a missing one.
3. An instance id alone cannot reveal two live process starts claiming one serving slot.
4. Two rolling deployment generations can each look internally healthy without a shared authority
   deciding which generation owns the fleet scope.

The remedy therefore needs an exact policy, process-start leases, complete trust-generation
identity, and one database-authoritative active cohort. A best-effort in-memory registry would only
move the ambiguity.

## Exact Policy

`TestSuiteStabilityAuthorityCohortPolicy` freezes these deployment-owned facts:

| Fact | Invariant |
| --- | --- |
| scope id | stable fleet scope shared by successive deployment generations |
| cohort id | immutable identity of one deployment generation |
| instance id | stable serving slot represented by this process |
| startup id | unique UUID generated for this process start |
| artifact fingerprint | canonical SHA-256 of the exact image or JAR |
| expected instance ids | exact 1..256 set, including the local slot |
| authority id | exact signed PDP identity |
| protocol version | exact Resource Gateway integration protocol generation |
| heartbeat interval | whole seconds, 1..300 |
| lease duration | whole seconds, 3..900 and at least three heartbeats |
| record retention | whole seconds, 1 hour..30 days and at least the lease |

The shared policy fingerprint includes scope, cohort, artifact, sorted exact inventory, authority,
protocol, and all timing values. It excludes only local instance id and startup id. Peers cannot
converge when one deployment parameter drifts.

## Private Trust Generation

`DynamicJwksTestSuiteStabilityAuthorityTrustStore` computes an irreversible SHA-256 fingerprint
over the complete accepted public trust snapshot: authority id, sorted key identities, full public
key material, algorithms, uses, and lifecycle windows. Atomic refresh publishes that fingerprint
only after every JWKS document and key check succeeds. A `304 Not Modified` preserves it; rotation,
removal, revocation, or lifecycle change produces a different generation.

This fingerprint is private control-plane data. It is not returned by capabilities, health,
authorizer descriptors, logs, or public APIs. It is not a signature or witness proof.

## Database Authority

The isolated test-runtime database owns three additive tables:

| Table | Purpose |
| --- | --- |
| `rg_test_suite_stability_authority_cohort_scope_locks` | serializes active-generation decisions per stable scope |
| `rg_test_suite_stability_authority_active_cohorts` | leases the one cohort generation allowed to converge |
| `rg_test_suite_stability_authority_cohort_members` | stores one row per scope, cohort, instance, and process start |

Scope is part of the member primary key and every read/delete path, so two organizations or fleet
scopes may reuse a cohort name without sharing membership. All liveness decisions use database
time. A member row carries observed, lease-expiry, and
purge-after times plus a whole-record fingerprint. Reads require exact lease and retention
relationships and reject malformed or fingerprint-divergent rows. Expired rows are ignored for
readiness and removed in a globally bounded batch after retention.

The first heartbeat under a scope claims its active cohort. The current cohort can renew its lease.
A different generation may publish member rows during rollout but remains blocked until the old
active lease expires; the next heartbeat then atomically claims the scope. Concurrent first
heartbeats serialize on the scope lock, so exactly one generation wins. A corrupt active-cohort row
cannot be silently overwritten or self-healed.

An unhealthy old process that still heartbeats intentionally keeps its cohort active. Correct
rollout requires stopping or fencing the old deployment before handover; this favors authorization
consistency over rollout availability.

## Convergence State

A snapshot is `CONVERGED` only when all conditions hold at one repeatable-read database boundary:

1. this cohort owns an unexpired active-scope lease;
2. every configured instance has exactly one unexpired process-start row;
3. no unconfigured instance or duplicate process start is live;
4. the reading process sees its exact local startup row;
5. policy, artifact, protocol, authority, and provider type match exactly;
6. every member reports healthy dynamic trust with an active key and successful refresh time;
7. all live members report one trust-generation fingerprint;
8. every row passes shape, time-relation, and whole-record integrity checks.

Primary and secondary blockers use a bounded stable vocabulary:

`COHORT_NOT_ACTIVE`, `COHORT_AUTHORITY_CORRUPT`, `INVENTORY_OVERFLOW`,
`INVENTORY_CORRUPT`, `MEMBER_MISSING`, `UNEXPECTED_MEMBER`, `DUPLICATE_INSTANCE`,
`LOCAL_PROCESS_NOT_REGISTERED`, `POLICY_DIVERGED`, `ARTIFACT_DIVERGED`,
`PROTOCOL_DIVERGED`, `AUTHORITY_DIVERGED`, `MEMBER_UNHEALTHY`, and
`SNAPSHOT_DIVERGED`.

## Runtime Gates

The monitor performs one immediate heartbeat and then uses one jittered daemon lane. Descriptor
reads never write membership and never call the remote JWKS endpoint. If current local trust differs
from the last successfully published observation, readiness immediately becomes
`LOCAL_OBSERVATION_UNPUBLISHED` until the next successful heartbeat. Database failure, scheduler
failure, monitor close, or local trust failure all close the gate.

The local comparison covers availability, refresh state, complete snapshot fingerprint, active-key
count, and policy. It deliberately excludes the last successful refresh timestamp: a successful
`304 Not Modified` refresh advances freshness without changing authorization semantics and must not
create periodic false closures. The next heartbeat still publishes the newer operational time.

The gate is checked at three boundaries:

| Boundary | Behavior when unavailable |
| --- | --- |
| fresh async submit | current authorizer descriptor closes submission before reservation |
| worker poll | returns `AUTHORITY_UNAVAILABLE` before durable queue claim |
| acquired job | existing `reauthorize` checks the cohort again before suite execution |

Worker startup checks local trust readiness rather than requiring immediate cohort convergence.
This avoids a bootstrap deadlock in which no replica can start before its peers have heartbeated.
Current runtime readiness is therefore dynamic and must not be cached from Spring startup.

The gate is point-in-time and lease-bounded. It is not a transaction lock held for the complete
suite execution; post-claim reauthorization narrows, but does not eliminate, that time-of-check
window.

## Configuration

Enable the HTTP authority and dynamic JWKS source first, then opt into the cohort. The following
local expected-set form is retained for the `test` profile:

```bash
export RG_TEST_STABILITY_JOB_AUTHORITY_HTTP_ENABLED=true
export RG_TEST_STABILITY_JOB_AUTHORITY_JWKS_ENABLED=true
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_ENABLED=true
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_SCOPE_ID=rg-stability-authority
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_ID=release-2026-07-19-01
export RG_RESOURCE_GATEWAY_INSTANCE_ID=rg-stability-01
export RG_RESOURCE_GATEWAY_ARTIFACT_FINGERPRINT=sha256:<64-lowercase-hex>
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_EXPECTED_INSTANCE_IDS=rg-stability-01,rg-stability-02
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_HEARTBEAT_SECONDS=10
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_LEASE_SECONDS=30
export RG_TEST_STABILITY_JOB_AUTHORITY_COHORT_RETENTION_SECONDS=86400
```

Each replica receives the same scope, cohort, artifact, expected set, authority, protocol, and timing
values; only `RG_RESOURCE_GATEWAY_INSTANCE_ID` differs. Staging additionally requires the signed
inventory variables documented in the follow-up verification; the local list then becomes only an
optional equality assertion.

## Capability And Health Truth

`exactSuiteStabilityAuthorityTrustCohort` means an exact configured, database-clock cohort gate is
assembled. `convergedSuiteStabilityAuthorityTrustCohort` means its current aggregate counts and one
snapshot generation are converged and the current authorizer is available. The second flag may
change without restart.

The authorizer descriptor and Actuator health expose only configuration/readiness booleans, stable
status, aggregate expected/live/healthy/distinct counts, and lease duration. They do not expose
scope, cohort, instance, startup, artifact, policy, endpoint, key id, or snapshot fingerprints.

## Verification

The focused gate executes 90 tests with zero failures, errors, or skips. It covers:

- exact two-replica convergence, cross-scope same-name isolation, and local process identity;
- missing, unexpected, duplicate, unhealthy, policy, artifact, protocol, authority, and generation
  divergence;
- database-clock expiry, retention relation, member corruption, and bounded cross-cohort purge;
- single active generation, lease handover, concurrent first-heartbeat election, and corrupt active
  authority rejection;
- local publication drift, database failure, close/withdraw, and payload-free health;
- atomic JWKS rotation, `304` generation stability, and private fingerprint behavior;
- zero-request HTTP authorization on cohort failure;
- pre-claim worker refusal and post-claim current-authority reauthorization;
- Spring/profile isolation, exact configuration validation, strict private Schema, and capability
  derivation.

The complete Resource Gateway `clean verify` executes 2674 tests with zero failures, zero errors,
and two conditional skips, then successfully repackages the executable Spring Boot JAR.

## Deliberate Limits

This increment by itself must not be described as full fleet attestation:

1. Local configured mode still trusts a Resource Gateway setting. Staging closes that specific
   self-shrink path with the follow-up deployment-signed serving-inventory protocol.
2. The JWKS fingerprint proves local byte-equivalent accepted state, not issuer non-equivocation.
   Signed JWKS metadata, an independent witness, and cross-region gossip remain future controls.
3. JWKS endpoint HA, KMS/HSM custody, mTLS or certificate pinning, external alerts, chaos/DR
   certification, and cross-database recovery remain unimplemented.
4. Hard process cancellation, physical test-runtime/network isolation, and a complete distributed
   attempt supervisor remain Stage 5 exit criteria.
5. Scope-lock rows are durable stable-scope authorities; operators must govern scope cardinality.
   Member history alone is retention-bounded.

The follow-up increment now consumes deployment-signed serving inventory and binds its revision,
material, policy, and expiry into the cohort. Its remaining gaps are restart-free refresh/revocation,
platform non-equivocation witnesses, signer custody certification, and non-H2/DR conformance.
