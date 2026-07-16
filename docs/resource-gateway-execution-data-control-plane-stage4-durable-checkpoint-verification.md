# Stage 4 Durable Test Checkpoint Verification

## Scope

This increment establishes the trusted persistence substrate required before Resource Gateway can
offer cold-start durable test execution. It now wires BLOGE's `ExecutionCheckpointStore` through a
staged, transaction-participating adapter and provides a profile-gated, fail-closed durable test
session. It does not expose a resume endpoint and does not yet wire BLOGE execution-status, wait,
timer, work-item, or streaming stores into that substrate.

The increment defines two versioned objects:

- `bloge.fixtureConsumptionStateSnapshot.v1` freezes cumulative rule use plus hashed invocation-site
  and containing-graph occurrence cursors.
- `bloge.durableTestExecutionCheckpoint.v1` binds the complete effective plan, exact fixture
  revision, replay dependencies already frozen in that plan, side-effect policy, identity-authority
  snapshot, fixture state, execution-service state, BLOGE engine-state closure, caller scope, and
  owner/lease/revision fence under one canonical fingerprint.

Both definitions are included in
[testing-control-plane-v1.schema.json](schemas/resource-gateway-testing/testing-control-plane-v1.schema.json).
They are supported internal durable objects, not public request payloads in this increment.

## Runtime Fixture Ledger

`InvocationRecorder.captureFixtureState()` now freezes rule-use, invocation-site occurrence, and
containing-graph occurrence counters behind a fair write lock. Normal `consume` and `bind` calls hold
the corresponding read lock, so a checkpoint cannot observe the site counter after allocation but
the graph counter before allocation. A bound invocation is pending until its first attempt begins,
and every executing attempt is tracked explicitly; capture at either point fails closed. Fixture
`maxUses` validation and reservation use one CAS operation, so parallel calls cannot over-consume a
bounded rule.

Cursor maps use SHA-256 identities from the first allocation. The hashed material is domain-separated
by `bloge.fixtureCursorIdentity.v1`, distinguishes `SITE` from `GRAPH`, and base64url-encodes the
structural coordinate and correlation value before hashing. Persisted state therefore contains no raw
graph path, invocation-site id, or runtime correlation value. These hashes provide stable pseudonymous
addressing, not confidentiality for low-entropy source values. Restore recomputes the snapshot content
fingerprint and accepts only an otherwise empty recorder; locked reads cannot observe a partial restore.

## Persistence Contract

`DurableTestExecutionCheckpointRepository` accepts only a `BoundEngineStateMutation`, which declares
the exact engine execution id and formal `EngineState` represented by its writes. The repository
rejects any identity, checkpoint-ref, boundary, sequence, version, or closure-fingerprint mismatch
before opening a transaction. The callback
receives the transaction-bound `JdbcTemplate` for the isolated test-runtime datasource. Creation and
advancement therefore have one commit decision:

1. validate every nested content identity;
2. verify tenant/environment scope and immutable dependency closure;
3. verify owner id, lease epoch, current revision, lifecycle transition, and monotonic cursors;
4. apply the BLOGE engine-state mutation through the same datasource;
5. compare-and-set the checkpoint row by owner, epoch, revision, and previous fingerprint;
6. commit both writes, or roll back both writes when any step fails.

The callback must not perform network I/O or write through another datasource. Such effects cannot
join this local transaction and are deliberately outside the contract.

## Transaction-Participating BLOGE Adapter

`StagedBlogeExecutionCheckpointStore` implements BLOGE's public `ExecutionCheckpointStore` over the
isolated test-runtime datasource. An execution must open the only local stage for its trusted engine
execution id before BLOGE can write. Node, loop, and sequential foreach checkpoint upserts, payload
rewrites, and deletes remain in a process-local read-your-writes overlay. Writes without a stage,
cross-execution batches, late writes after prepare, use of another datasource, and use outside an
active Spring transaction all fail closed.

`Stage.prepare(...)` sorts and freezes the exact delete/upsert set and computes a canonical
`bloge.testCheckpointMutation.v1` fingerprint. The prepared mutation is idempotent so the same
content can be retried after a transient transaction rollback, but it becomes unusable when its
owning stage closes. Global maintenance pagination sees committed rows only. Two service instances
may stage the same execution independently; the control repository's owner/epoch/revision CAS is
the distributed winner election, and the losing instance's concrete BLOGE rows roll back.

`IndependentDurableTestEngineFactory.openSession(...)` owns the stage and a short-lived
`DurableGraphEngine`. It always selects `CheckpointFailurePolicy.FAIL_FAST`, installs no production
interceptors, listeners, context carriers, or extension listeners, and accepts a complete frozen
`ExecutionOptions`. The session replaces only the first root id allocation with the caller-assigned
engine execution id; the run-scoped operator resolver and all subsequent deterministic providers
remain unchanged. This keeps fixture control and execution identity outside business
`GraphContext`, permits one root execution, and requires prepare/commit before session close.

## BLOGE Fail-Closed Prerequisite

BLOGE source commit `bcbb19694` adds the public `CheckpointFailurePolicy` to both
`DurableManager.Builder` and `DurableGraphEngine.Builder`. `FAIL_FAST` converts node-output, loop,
and sequential for-each checkpoint read/write/codec failures into `DurabilityException`; the loop
operator path preserves that exception instead of logging and continuing. `BEST_EFFORT` remains the
compatibility default.

Resource Gateway now consumes this prerequisite in its test-profile durable resources: the
transaction-participating execution-checkpoint adapter and independent durable factory both fail
closed. This still covers only BLOGE execution checkpoints. Crash recovery and the remaining durable
stores must be completed before cold-start resume can be enabled.

## Safety Invariants

1. Durable checkpoints are accepted only in `test` or `staging`; production scope fails before
   persistence.
2. The embedded `EffectiveExecutionPlan.v3` retains full exact replay references. The fixture id,
   positive revision, and fingerprint must agree with the plan's fixture fingerprint; `latest` does
   not exist.
3. The execution-service snapshot must bind the same plan fingerprint. Resumable states reject a
   provider snapshot that reports restore gaps.
4. v1 accepts only `DENY_REAL`/`REPLAY_ONLY` side-effect policy and a `FAIL_CLOSED` identity
   authority. Sandbox REAL effects or fixture identity require a future protocol after their
   authorities and conformance proofs exist.
5. Runtime correlation values are not persisted. Site and graph occurrence maps accept only
   canonical SHA-256 keys; provider snapshots already follow the same rule for RANDOM/UUID scopes.
   Hashing is pseudonymization, so storage access control remains mandatory.
6. Rule use, dynamic occurrence, logical time, deterministic sequence cursor, provider usage,
   engine state version, and engine boundary sequence cannot move backwards.
7. Tenant, organization, project, environment, actor, run id, engine execution id, dependency
   closure, and creation time are immutable after revision zero.
8. Owner transfer is not inferred from an update. Until an explicit claim protocol is added, the
   same owner and lease epoch must advance exactly one revision.
9. Terminal and `CONTROL_PLAN_UNAVAILABLE` checkpoints cannot advance.
10. Lifecycle timestamps are canonicalized to microsecond precision before hashing so H2 and
    common enterprise SQL stores cannot create false JSON/index drift by rounding Java nanoseconds.
11. Database reads recompute nested and aggregate fingerprints and compare every authorization,
   dependency, fence, and state index with the JSON value. Index/JSON drift is treated as corrupt
   trusted state.
12. Concurrent writers may both enter their engine-state callbacks, but only one CAS can commit;
    the losing callback is rolled back in the same transaction.
13. A prepared BLOGE mutation is bound to both the exact engine execution id and the complete formal
    engine-state value. A caller cannot attach execution A's rows to execution B or reuse a closure
    fingerprint under another boundary/ref/version.
14. Closing the stage invalidates its prepared mutation. A rollback may retry the same frozen
    content while the stage remains open; no mutation can escape session cleanup.

## Automated Evidence

`DurableTestExecutionCheckpointTest` proves complete closure sealing, replay-reference retention,
production rejection, hashed cursor enforcement, nested fixture tamper rejection, provider-state
tamper rejection, and plan/provider identity closure.

`DatabaseDurableTestExecutionCheckpointRepositoryTest` proves:

- atomic create and advance with a participating engine-state table;
- create rollback after an injected engine-store failure;
- stale owner rejection before engine mutation;
- two-writer CAS with exactly one committed engine mutation;
- tenant/environment-scoped lookup;
- idempotent duplicate create and cross-tenant global engine-id collision rejection;
- fail-closed indexed-column/JSON drift detection;
- cursor rewind rejection before engine mutation.

`TestingControlProtocolSchemaTest` pins both Java protocol versions to the authoritative machine
schema. Spring application tests exercise profile-gated composition through the existing
`TestRuntimeConfiguration`.

`InvocationRecorderCheckpointTest` proves monotonic rule/site/graph continuation after restore,
payload-free hashed cursor storage, tamper rejection without partial mutation, rejection of restore
into an active recorder, fail-closed non-quiescent capture, non-torn concurrent allocation and
consumption, bounded `maxUses` under contention, and finite test deadlines.

`StagedBlogeExecutionCheckpointStoreTest` proves stage-only writes, read-your-writes and discard,
formal engine-state and engine-id binding, same-datasource transaction enforcement, rollback and
idempotent retry, closed-stage invalidation, caller-assigned execution identity, inherited operator
fixture resolution, durable-engine checkpoint persistence, and concrete two-instance CAS loser
rollback. `IndependentDurableTestEngineFactoryTest` proves that bypassing the session cannot silently
run without a composite stage. `TestRuntimeProfileIsolationTest` proves that the holder exists only
under the isolated test profile and is vetoed whenever production is active.

Reproduce the focused gate with:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DurableTestExecutionCheckpointTest,DatabaseDurableTestExecutionCheckpointRepositoryTest,StagedBlogeExecutionCheckpointStoreTest,IndependentDurableTestEngineFactoryTest,InvocationRecorderCheckpointTest,TestingControlProtocolSchemaTest,TestRuntimeProfileIsolationTest test
```

The repository-wide `clean verify` gate completed with 1910 tests, zero failures, zero errors, two
conditional skips, real-browser regression coverage, and successful Spring Boot JAR packaging.

## Honest Remaining Gaps

- BLOGE execution-status, wait, timer, work-item, and stream-offset stores do not yet execute through
  `EngineStateMutation`. Node/loop/sequential-foreach execution checkpoints now participate, but a
  suspended execution still spans stores that cannot share the composite commit.
- The durable session is a profile-gated internal resource; the public testing execution service has
  not yet selected it or exposed suspend/resume/owner-claim operations.
- The fixture ledger snapshot intentionally excludes pre-checkpoint invocation and attempt evidence;
  a resumed terminal evidence bundle cannot yet reconstruct those historical trace facts.
- Resume does not yet re-authorize the exact fixture revision, replay retention state, identity,
  side-effect policy, or caller permissions. Missing or revoked dependencies therefore cannot yet be
  surfaced through a public `CONTROL_PLAN_UNAVAILABLE` lifecycle.
- There is no lease claim/owner transfer API, public suspend/resume endpoint, crash-driven process
  recovery loop, or signed checkpoint attestation.
- The repository uses a local database transaction. Cross-database BLOGE stores require either
  migration onto this datasource or an outbox/recovery protocol; pretending a distributed
  transaction exists is explicitly rejected.
- Stream/event fixture state, typed identity/feature-flag/secret authorities, deterministic parallel
  scheduling, and physical test-runtime deployment remain Stage 4/5 work.
