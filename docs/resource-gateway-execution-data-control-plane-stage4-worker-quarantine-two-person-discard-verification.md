# Stage 4 Worker Quarantine Two-Person Discard Verification

## Problem Closed

Worker quarantine `DISCARD` removes a correctness safeguard and makes an exact poisoned checkpoint
eligible for later worker acquisition. A secret claim fence proves current ownership, but it does not
prove independent review. Giving one privileged actor both ownership and deletion authority leaves
credential compromise, operator error, and rushed remediation on the same failure path.

This increment makes every newly initiated discard a database-authoritative maker/checker operation.
The maker owns a secret claim. A distinct verified checker approves only the payload-free claim
closure and never receives its token. The maker must then prove the original live claim and atomically
consume the independent approval. Direct new `DISCARD` commands are rejected.

## Security Invariants

1. Scope and mutating owner come only from verified identity.
2. Maker requires the deployment operator group; checker requires a separate approver group.
3. Maker and checker actor IDs must differ, even if a credential has both groups.
4. The checker never receives or submits `claimToken`.
5. Approval binds exact scope, run, checkpoint fingerprint, claim owner, version, expiry, reason,
   approver, and a database-clock deadline.
6. Approval expiry is at most 900 seconds and never later than the maker claim expiry.
7. One approval can be consumed only once and only by its original live maker claim.
8. Approval consumption, quarantine deletion, receipt, two-person history, and maker audit commit in
   one local transaction.
9. Approval and history rows carry canonical whole-record fingerprints and fail closed on tampering.
10. No response, history, audit fact, health detail, metric, or problem object exposes claim token or
    business payload.

## Public Protocol

| Method | Path | Required role | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/testing/durable-state/worker-quarantines/discard-approvals` | independent approver group | approve one exact live maker claim |
| `POST` | `/api/testing/durable-state/worker-quarantines/approved-discards` | maintenance operator group | atomically consume approval and discard |
| `GET` | `/api/testing/durable-state/worker-quarantines/approved-discards/history` | maintenance operator group | read bounded token-free maker/checker evidence |

All endpoints additionally require verified identity, `TEST_RUNTIME_MAINTENANCE`, minimum configured
clearance, and `test` or `staging`. Production profile assembly excludes the complete testing runtime.

The frozen Schema objects are:

- `bloge.durableWorkerQuarantineDiscardApprovalRequest.v1`;
- `bloge.durableWorkerQuarantineDiscardApprovalResponse.v1`;
- `bloge.durableWorkerQuarantineApprovedDiscardRequest.v1`;
- `bloge.durableWorkerQuarantineApprovedDiscardResponse.v1`;
- `bloge.durableWorkerQuarantineApprovedDiscardHistoryResponse.v1`.

Requests reject unknown fields. Approval response, approved-discard response, and history structurally
exclude `claimToken`. Capability discovery advertises all five objects and all three endpoints only
when the isolated testing runtime is enabled.

## State Machine

```text
AVAILABLE
   |
   | maker claim
   v
CLAIMED(owner, token, version, claimUntil)
   |
   | checker observes owner/version/claimUntil, no token
   v
APPROVED(approvalId, approver, reason, approvalUntil)
   |
   | same maker proves token + exact fence + exact reason
   v
CONSUMED + quarantine deleted + immutable two-person history
```

Claim expiry makes the quarantine available for takeover and invalidates its bound approval. Approval
expiry does not alter the quarantine or maker claim; it only prevents discard. `RELEASE` preserves
worker suppression. A new checker approval is required after claim takeover or any fence drift.

The legacy `/resolutions` endpoint remains wire compatible for `RELEASE` and exact replay of a
historically committed command. It rejects every newly initiated direct `DISCARD` with stable code
`RG.TEST.WORKER_QUARANTINE_DISCARD_APPROVAL_REQUIRED`.

## Persistence And Linearization

| Table | Authority |
| --- | --- |
| `rg_test_durable_worker_quarantine_discard_approvals` | immutable checker intent plus one-way `APPROVED -> CONSUMED` state |
| `rg_test_durable_worker_quarantine_discards` | caller-stable maker intent and token-free result replay |
| `rg_test_durable_worker_quarantine_discard_history` | independent retained maker/checker evidence |

Approval and discard use the established lock order: exact checkpoint authority, exact quarantine,
maintenance control, then approval when applicable. Both operations repeat idempotency lookup after
the checkpoint lock. This closes the lost-response race in which a waiter could otherwise miss the
command committed by the lock winner.

Approval uses database time to cap `approvalUntil` to the earlier of requested duration and
`claimUntil`. Discard locks the approval and verifies state, key, owner, version, claim expiry,
approver separation, reason, and approval expiry before mutation. Two different concurrent discard
request IDs cannot both consume one approval: one commits and the other observes the deleted
quarantine/fence failure. An exact retry of the winner replays its immutable receipt.

## Stable Rejections

| Condition | Stable code or disposition |
| --- | --- |
| direct new legacy discard | `RG.TEST.WORKER_QUARANTINE_DISCARD_APPROVAL_REQUIRED` |
| checker lacks separate group | `RG.TEST.WORKER_QUARANTINE_APPROVER_ROLE_REQUIRED` |
| checker actor equals maker actor | `RG.TEST.WORKER_QUARANTINE_SELF_APPROVAL_FORBIDDEN` |
| checker observed stale/expired claim | `RG.TEST.WORKER_QUARANTINE_APPROVAL_FENCE_REJECTED` |
| approval absent, expired, consumed, or mismatched | `RG.TEST.WORKER_QUARANTINE_DISCARD_APPROVAL_REJECTED` |
| maker token/version/expiry no longer exact | `RG.TEST.WORKER_QUARANTINE_FENCE_REJECTED` |
| request ID reused with changed intent | `RG.TEST.WORKER_QUARANTINE_IDEMPOTENCY_CONFLICT` |
| checkpoint closure changed | `RG.TEST.WORKER_QUARANTINE_STALE_CHECKPOINT` |

Malformed version, UUID, request ID, duration, key, or reason is `400`. Authority conflict is `409`.
Store or integrity failure remains fail closed and is not translated into an acceptable outcome.

## Audit And Evidence

Checker approval audit contains approval ID, claim version, reason, intent fingerprint, and approval
fingerprint. Maker audit contains both actor identities, approval and receipt fingerprints, reason,
and non-secret command identity. The retained history also carries quarantine reason, threshold,
failure count, first/quarantined timestamps, both actors, approval identity/fingerprint, action time,
receipt fingerprint, and record fingerprint.

Audit mutation is transaction-bound. A checker-audit failure leaves no approval. A maker-audit
failure leaves approval `APPROVED`, keeps the quarantine, and writes no discard command or history.
This permits an exact safe retry after the audit store recovers.

## SLO And Operations

The database-clock SLO snapshot now reports:

- live unconsumed discard approvals;
- expired unconsumed discard approvals;
- retained approved-discard history count.

The default expired-approval threshold is zero. Exceeding it produces stable violation
`WORKER_CANDIDATE_QUARANTINE_DISCARD_APPROVAL_EXPIRED`. Fixed-cardinality gauges are:

```text
resource.gateway.test.runtime.worker.candidate.quarantines.discard.approvals.live
resource.gateway.test.runtime.worker.candidate.quarantines.discard.approvals.expired
resource.gateway.test.runtime.worker.candidate.quarantines.discards.approved.history
```

| Property | Environment variable | Default |
| --- | --- | --- |
| `gateway.testing.durable.worker-quarantines.required-approver-group` | `RG_TEST_WORKER_QUARANTINE_REQUIRED_APPROVER_GROUP` | `resource-gateway-test-runtime-quarantine-approvers` |
| `gateway.testing.runtime-slo.worker-quarantine-max-expired-discard-approvals` | `RG_TEST_RUNTIME_SLO_WORKER_QUARANTINE_MAX_EXPIRED_DISCARD_APPROVALS` | `0` |

## Counterexample Proofs

| Counterexample | Required result |
| --- | --- |
| actor belongs to operator and approver groups and approves its own claim | self approval rejected; no approval row |
| checker submits maker token or future field | strict request rejected |
| claim changes after checker observation | approval fence rejected under checkpoint lock |
| approval reason and discard reason differ | discard rejected; approval remains unconsumed |
| approval expires while maker claim remains live | discard rejected; quarantine remains |
| two distinct discard IDs race on one approval | exactly one discard/history/audit commits |
| exact discard response is lost | same request replays immutable token-free receipt |
| approval row is changed out of band | discard fails closed before mutation |
| history row is changed out of band | history read fails closed |
| checker or maker audit append fails | complete authority transaction rolls back |
| metrics and health are scraped | only aggregate counts are exposed |

## Verification Gate

Focused verification combines persistence, service, HTTP, profile isolation, Schema, capability,
application wiring, SLO health, and fixed-cardinality telemetry:

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseDurableWorkerQuarantineControlPlaneTest,DurableWorkerQuarantineServiceTest,DurableWorkerQuarantineControllerTest,DatabaseTestRuntimeSloControlPlaneTest,TestRuntimeSloMonitorTest,TestRuntimeSloTelemetryTest,TestingControlProtocolSchemaTest,TestabilityCapabilitiesTest,TestRuntimeApplicationIntegrationTest,TestRuntimeProfileIsolationTest test
```

Release requires Resource Gateway `clean verify` plus independent test-kit `clean verify`, because
the protocol Schema is packaged as a client wire authority and public JavaDoc is a compatibility gate.

The focused gate executes 48 tests with 0 failures, 0 errors, and 0 skips. Its database control-plane
class contributes 18 tests. Resource Gateway `clean verify` executes 2,201 tests with 0 failures,
0 errors, and 34 existing conditional browser skips, then packages the executable Spring Boot JAR.
Independent test-kit `clean verify` executes 63 tests with 0 failures, 0 errors, and 0 skips, and
passes packaged-Schema, shaded CLI, and public JavaDoc verification.

## Honest Boundary

This is an in-process two-person database protocol, not a complete enterprise approval workflow.
Actor separation is only as trustworthy as the configured identity provider and group lifecycle. It
does not yet bind an external ticket, approval policy revision, device/session assurance, time-bound
privileged access grant, or governance callback. Same-database fingerprints detect accidental or
unsophisticated mutation but are not external WORM evidence.

Claim-command replay tokens now use a rotation-aware AES-256-GCM envelope; valid legacy plaintext
rows are migrated and old-key envelopes are rewrapped at startup. The active control fence remains
in the isolated database for its short lease. Approval/command/history retention is now bounded by a
database-leased lifecycle with payload-free request tombstones, but there is no archive, legal hold,
backup-erasure proof, or external retention-policy ledger. Alert routing, webhook notification,
break-glass workflow, bulk approval limits, external witness anchoring, non-H2 dialect certification,
and production-scale contention tests remain future hardening work.

Key setup, two-phase rotation, migration failure semantics, and the narrower remaining boundary are
specified in the [claim-token protection verification](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-claim-token-protection-verification.md).
Deletion clocks, tombstone idempotency semantics, and lease/fence proofs are specified in the
[bounded retention verification](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-retention-verification.md).
