# Stage 4 Durable Test Checkpoint Verification

## Scope

This increment establishes the trusted persistence substrate required before Resource Gateway can
offer cold-start durable test execution. It does not expose a resume endpoint and does not yet wire
BLOGE suspend, timer, work-item, or streaming stores into that substrate.

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

## Persistence Contract

`DurableTestExecutionCheckpointRepository` accepts an `EngineStateMutation` callback. The callback
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

Reproduce the focused gate with:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DurableTestExecutionCheckpointTest,DatabaseDurableTestExecutionCheckpointRepositoryTest,TestingControlProtocolSchemaTest test
```

The repository-wide `clean verify` gate completed with 1889 tests, zero failures, zero errors, two
conditional skips, real-browser regression coverage, and successful Spring Boot JAR packaging.

## Honest Remaining Gaps

- BLOGE checkpoint, wait, execution-status, timer, work-item, and stream-offset stores do not yet
  execute through `EngineStateMutation`. This increment proves the atomic boundary but has not moved
  those engine facts into it.
- `InvocationRecorder` does not yet export and restore the new fixture-consumption snapshot.
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
