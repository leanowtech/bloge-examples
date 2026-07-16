# Stage 4 Durable Test Checkpoint Verification

## Scope

This increment establishes the trusted persistence substrate and internal synchronous recovery
primitive required before Resource Gateway can offer cold-start durable test execution. It wires BLOGE's `ExecutionCheckpointStore`,
`ExecutionStore`, `WaitStore`, and `WorkItemStore` through staged, transaction-participating adapters
and composes all four behind one profile-gated, fail-closed durable test session. Signal, timer, task,
extension, and retry waits plus queued, claimed, retried, failed, and dead-lettered work are therefore
part of the same commit decision as lifecycle and node checkpoints. A public, authenticated
payload-free query now exposes an integrity-verified lifecycle/fence/dependency/boundary view under
tenant/environment/organization/project non-disclosure scope; it does not reserve a lease or expose
hidden execution material. A public, authenticated
owner-claim endpoint now performs the database-clock compare-and-set ownership handoff after exact
dependency re-authorization; it deliberately does not itself resume BLOGE or expose a worker
lifecycle. A public authenticated heartbeat resolves the hidden issued dispatch from the exact
predecessor fence, preserves the original authorization principal, compares it with the live
`RESUMING` fence, and atomically rotates the lease and successor dispatch. A public terminal-only
adapter now resolves one still-live issued dispatch, reproduces its complete executable authorization,
applies one bounded signal through the internal `RecoverySession`, and accepts only a BLOGE terminal
boundary. It atomically commits the server-derived BLOGE mutation, terminal checkpoint, immutable
command result, semantic audit, and a promotion-blocking evidence-gap receipt. The endpoint is one
synchronous orchestration step, not a dispatcher or general multi-boundary worker lifecycle.
Streaming recovery has
no separate BLOGE store SPI and requires an explicit checkpoint/offset protocol rather than a
fictitious store adapter.

The increment defines the following versioned objects:

- `bloge.fixtureConsumptionStateSnapshot.v1` freezes cumulative rule use plus hashed invocation-site
  and containing-graph occurrence cursors.
- `bloge.durableTestExecutionCheckpoint.v1` is the readable legacy closure. It binds the complete
  effective plan, exact fixture
  revision, replay dependencies already frozen in that plan, side-effect policy, identity-authority
  snapshot, fixture state, execution-service state, BLOGE engine-state closure, caller scope, and
  owner/lease/revision fence under one canonical fingerprint, but it does not contain a graph/operator
  locator and therefore cannot independently re-resolve its target for public recovery.
- `bloge.durableTestExecutionCheckpoint.v2` is the current write protocol. It adds an exact
  `target = {kind,id,fingerprint}` locator, requires the locator fingerprint to equal the effective
  plan target fingerprint, and binds `GRAPH` to `GRAPH_CONTRACT_TEST` and `OPERATOR` to
  `OPERATOR_UNIT_TEST`.
- `bloge.durableTestExecutionView.v1` is the public payload-free query projection. It exposes only
  lifecycle, fence, exact target/fixture references, content fingerprints, and the engine-boundary
  identity. Legacy v1 checkpoints omit target and are explicitly migration-required and
  non-recoverable.
- `bloge.durableTestRecoveryTerminalReceipt.v1` is an internal, payload-free terminal control
  receipt. Until the durable closure contains complete pre-checkpoint node, edge, and attempt trace,
  it is fixed to `EVIDENCE_INCOMPLETE`, requires at least one bounded gap code, and cannot satisfy a
  promotion gate.
- `bloge.durableTestRecoveryHeartbeatRequest.v1` and
  `bloge.durableTestRecoveryHeartbeatResponse.v1` are public payload-free recovery-control contracts.
  They carry only a caller idempotency key and exact predecessor/successor fences; the internal
  dispatch, authorization receipt, lease policy, engine closure, and business payload remain hidden.
- `bloge.durableTestTerminalRecoveryRequest.v1` carries one exact issued fence, stable key, and
  bounded signal. `bloge.durableTestTerminalRecoveryResponse.v1` is payload-free and exposes only the
  server-derived terminal control result, receipt identity, and mandatory evidence gaps.

These definitions are included in
[testing-control-plane-v1.schema.json](schemas/resource-gateway-testing/testing-control-plane-v1.schema.json).
The same schema also defines the public, payload-free `bloge.durableTestOwnerClaimRequest.v1` and
`bloge.durableTestOwnerClaimResponse.v1` wire contracts. The full checkpoint values remain internal.

## Initial Stable Boundary

`RunSession.execute(...)` uses BLOGE's synchronous execution-to-durable-boundary API, so a fresh run
returns after terminal completion or after all admitted work is durably quiescent. A separate
`prepareInitialSuspension(...)` policy admits only the creation-v1 shape that Resource Gateway can
currently recover without guessing: the persisted execution must be `SUSPENDED`, and the wait store
must contain exactly one live `WAIT_SIGNAL`. The session then captures the quiescent fixture cursor
and prepares the execution/checkpoint/wait/work-item aggregate with boundary sequence one. The
returned `PreparedRun` cross-checks node, boundary type, state version, and sequence against the
formal engine-state closure.

Terminal completion, pause, timer, work item, stream offset, or multiple live waits fail before a
repository callback can commit staged state. Closing the session drops the complete overlay. In
particular, BLOGE may reach concurrent signal suspensions, but Resource Gateway does not yet define
their ordering, fan-in recovery, or evidence semantics; creation v1 therefore rejects them instead of
choosing an arbitrary node. This runtime policy is now implemented and tested. Public request
idempotency, ownership reservation, dependency freezing, and the creation endpoint remain separate
work and are not implied by this primitive.

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

The database redundantly projects v2 target kind, id, and fingerprint into nullable index columns.
Reads compare all three columns with the sealed JSON. Nullable columns preserve v1 rows exactly;
repository initialization adds them idempotently to an existing v1 table. A v1 JSON round trip omits
the absent target rather than inventing a null property, so its historical canonical fingerprint does
not drift. A v1 outer checkpoint rejects a v2 locator, while every newly written v2 checkpoint
requires one.

The callback must not perform network I/O or write through another datasource. Such effects cannot
join this local transaction and are deliberately outside the contract.

Initial creation now has a separate payload-free command reservation in
`rg_test_durable_creation_commands`. Its scoped key is `(tenant, environment, clientRequestId)` and
its content identity covers the authenticated request fingerprint, exact authorization-closure
fingerprint, complete organization/project/actor scope, server-minted run and engine identities,
database-time owner/epoch/lease fence, state, rejection code, and original checkpoint result identity.
The row never stores graph context, fixture/replay values, provider seeds/cursors, credentials, or a
BLOGE checkpoint body. The request fingerprint commits to caller input without making that input a
control-plane payload.

The first contender inserts `PENDING`; a same-intent contender sees the same assigned identities but
does not acquire execution authority. A live lease cannot be stolen. After database-time expiry, a
new owner increments the epoch while retaining run and engine identities. Same-key different request,
scope, or authorization closure fails as an idempotency conflict. A winning owner may atomically
commit the sealed revision-zero `SUSPENDED` control checkpoint, staged four-store BLOGE aggregate,
immutable initial checkpoint snapshot, and companion audit. Any failure rolls all four decisions
back to `PENDING`. A deterministic unsupported-boundary result can instead transition to immutable,
payload-free `REJECTED`; ambiguous retries replay the original terminal command result. Record and
checkpoint fingerprints are reverified on every resolution. This is the database protocol needed by
the public creator; transport authentication and dependency authorization remain the next adapter.

Expired owner recovery is a distinct control-only transition. `claimExpiredLease(...)` accepts the
exact tenant/environment/run, old owner/epoch/revision fence, previous checkpoint fingerprint, new
process owner, and a whole-second lease from one second through one hour. It reads time from the
database inside the transaction; callers cannot move time forward to steal an active lease. Only an
exact, expired `ACTIVE`, `SUSPENDED`, or `RESUMING` closure can move to `RESUMING`. Success increments
both lease epoch and control revision, reseals the checkpoint, and leaves plan, fixture, provider,
cursor, and engine-state closure unchanged. The SQL CAS repeats scope, old fence, previous fingerprint,
expiry, and resumable-status checks. A cross-scope or stale claim returns the same `STALE_FENCE`
category, while an exact caller may distinguish `LEASE_ACTIVE` and `NOT_RESUMABLE`.

`claimExpiredLeaseIdempotently(...)` closes the next storage-level ambiguity window. A trusted
orchestrator supplies a tenant/environment-scoped `clientRequestId` and a canonical fingerprint of
the complete authorized command. The command reservation, lease CAS, and immutable result snapshot
commit in the same local transaction. Retrying the same intent returns that original snapshot even
if the live checkpoint later advances; changing the run, old fence, previous fingerprint, claimant,
lease, or request fingerprint under the same key fails as `IDEMPOTENCY_CONFLICT`. Stored results are
re-verified against their aggregate fingerprint before replay. A separate
`bloge.durableResumeCommandRecord.v2` fingerprint covers scope, caller key, request fingerprint,
complete fence/claim intent, authorization/checkpoint/dispatch fingerprints, and database creation time, so indexed intent drift
is reported as storage corruption rather than misclassified as caller conflict. This is an
internal persistence protocol. Its public adapter adds transport authentication, authorization,
security audit, exact dependency re-authorization, and server-owned claimant policy.

## Public Durable Execution Query

`GET /api/testing/durable-executions/{runId}` is structurally available only in `test` and `staging`
and authenticates through `TEST_DURABLE_EXECUTION_READ` for `TEST_EXECUTION` or `TEST_REPLAY`.
Malformed run identities fail before repository access. The repository scopes its read by verified
tenant/environment and verifies the sealed JSON, nested content identities, and every indexed
projection; the application service then requires exact organization/project scope. Missing and
cross-scope executions share one not-found result, while store failure or integrity drift produces a
payload-free unavailable result.

`bloge.durableTestExecutionView.v1` returns status, current owner/epoch/revision and lease deadline,
exact graph/operator and immutable fixture references, plan/provider/fixture-ledger fingerprints, a
payload-free BLOGE boundary, aggregate checkpoint fingerprint, timestamps, and derived
`recoverable`/`migrationRequired` facts. It omits context, fixture and replay values, provider
cursors, identity authority, credentials, dispatches, and BLOGE checkpoint bodies. A v1 checkpoint
remains observable for migration and incident handling, but has no target and is never marked
recoverable. The response is an observation that can become stale immediately; only owner claim can
recheck a complete fence, re-authorize dependencies, and issue a recovery dispatch.

## Public Owner Claim

`POST /api/testing/durable-executions/{runId}/owner-claims` is structurally available only in
`test` and `staging`. It accepts `TEST_EXECUTION` or `TEST_REPLAY`, requires an exact observed
owner/epoch/revision fence and checkpoint fingerprint, and rejects unknown fields so callers cannot
smuggle a claimant owner or lease duration into the command. The deployment owns those values through
`RG_TEST_DURABLE_OWNER_INSTANCE_ID` and `RG_TEST_DURABLE_OWNER_LEASE_SECONDS`.

The service first establishes the complete tenant/organization/project/environment scope without
disclosing cross-project existence. For a new command it then requires a current v2 target and
re-authorizes the exact graph or operator fingerprint, immutable fixture revision and recomputed
content fingerprint, governed replay payload closure, workload-identity authority policy, caller
clearance and purpose, side-effect mode, deterministic provider snapshot, and recompiled effective
plan. Missing, revoked, drifted, stale, or unavailable authorities fail closed; no dependency falls
back to `latest` or REAL behavior.

The v2 canonical command fingerprint binds caller intent to verified scope and region, actor,
delegation, purpose, clearance, and sorted groups. It excludes correlation ID, the server-selected owner,
and lease so the same caller
intent remains replayable after process restart. A fresh claim and its `ALLOWED` semantic security
event share the repository transaction; if audit cannot commit, the lease cannot move. A response-loss
retry reads the immutable command result before consulting mutable dependencies and independently
audits that replay. A concurrent loser looks up and returns the winner's exact result. The response
contains no fixture, replay payload, engine state, credential, or authority value.

Authorization now returns the exact graph or canonical operator micro-graph, frozen
`CompiledExecutionControl`, and a payload-free `bloge.durableTestRecoveryAuthorization.v1` receipt.
The receipt binds the source checkpoint and authenticated principal to exact target, plan, fixture,
replay, provider-state, authority, purpose, and side-effect fingerprints. In the claim transaction,
the repository issues `bloge.durableTestRecoveryDispatch.v1`, binding that receipt to result scope,
engine execution, owner/epoch/revision/expiry, and claimed checkpoint. Command replay verifies both
JSON values, every nested fingerprint, indexed projections, and the source-to-result chain. Exact
internal lookup returns this historical dispatch only for the same scope, run, fence, and checkpoint.

The dispatch is not a bearer token and does not make the claim a general cold-start worker. The
public heartbeat can resolve and rotate it only from the exact predecessor fence under the original
principal. The terminal endpoint can consume it only after reconstructing the same executable
authorization and proving the live fence. No worker polls a queue, schedules heartbeats, coordinates
multiple suspensions, or assembles complete historical signed evidence.

## Public Authenticated Recovery Heartbeat

`POST /api/testing/durable-executions/{runId}/heartbeats` is structurally available only in `test`
and `staging`. It authenticates through the dedicated `TEST_DURABLE_RECOVERY_CONTROL` operation and
accepts `TEST_EXECUTION` or `TEST_REPLAY`. The strict request contains a caller-stable key, exact
owner/epoch/revision fence, and exact checkpoint fingerprint. Unknown fields fail closed, so the
caller cannot supply the dispatch, authorization, owner choice, expiry, or lease duration.

The adapter resolves the unique historical dispatch from the trusted store and verifies tenant,
organization, project, environment, run, fence, and checkpoint agreement before mutation. It then
recomputes the canonical principal fingerprint used by owner claim. Region, actor, delegation,
purpose, clearance, and sorted groups must remain identical; correlation id is excluded so a lost
response can be retried. Any principal drift is forbidden and cross-scope existence remains hidden.
The server owns the whole-second `1..3600` renewal through
`RG_TEST_DURABLE_HEARTBEAT_LEASE_SECONDS`, defaulting to `120`.

The service derives the immutable intent fingerprint from the hidden dispatch, current principal,
run, caller key, and server lease. Its `ALLOWED` semantic security event joins the heartbeat's local
transaction; an audit failure cannot advance the fence. An ambiguous retry with the same key and
intent returns the exact committed successor and emits a separate replay audit. The response carries
only run/status, successor owner/epoch/revision, database-authority expiry, successor checkpoint
fingerprint, and `idempotentReplay`.

The public adapter delegates the actual state transition to the following internal persistence
protocol; it does not schedule renewals, run BLOGE, cancel a worker, or produce terminal evidence.

`heartbeatRecoveryLeaseIdempotently(...)` closes the live-fence and ambiguous-response window for a
trusted recovery worker. `RecoveryHeartbeatCommand` carries a caller-stable key, server-derived
request fingerprint, exact source dispatch, and a whole-second lease extension from one second
through one hour. Before consulting the live row, the repository proves that the source dispatch is
the verified result of a committed owner claim or predecessor heartbeat. A merely self-consistent
content fingerprint is insufficient: fingerprints prove integrity, not system issuance or caller
identity.

The database clock then decides whether the exact dispatch still controls a non-expired
`RESUMING` checkpoint. Scope, engine execution, owner, lease epoch, revision, previous expiry, and
checkpoint fingerprint are repeated in the SQL compare-and-set. Success advances exactly one control
revision and extends the lease while preserving plan, fixture, provider, cursor, and BLOGE engine
closure byte-for-byte. The same transaction issues a successor
`bloge.durableTestRecoveryDispatch.v1` and writes a content-addressed
`bloge.durableRecoveryHeartbeatRecord.v1` covering source and result fences, authorization,
idempotency intent, duration, and database time. A transaction-bound payload-free audit/evidence
mutation may join the same commit decision.

The original key replays the exact successor after a lost response. A different key using the old
dispatch sees `STALE_FENCE`; an expired owner sees `LEASE_EXPIRED`; a valid but unissued dispatch sees
`UNRECOGNIZED_DISPATCH`. Same-key intent drift, duplicate replicas, JSON/index/fingerprint corruption,
and companion-mutation failure all fail closed. This remains the repository authority beneath the
public adapter; it does not itself authenticate callers, schedule heartbeats, execute BLOGE, cancel a
process, or produce terminal evidence.

## Public Terminal Recovery and Atomic Commit

`POST /api/testing/durable-executions/{runId}/terminal-recoveries` is structurally absent outside
`test` and `staging` and authenticates through `TEST_DURABLE_RECOVERY_CONTROL`. Its strict request
contains only a stable key, exact owner/epoch/revision/checkpoint fence, signal node, and explicit
JSON signal value. Canonical signal data is limited to 256 KiB. Unknown fields and caller-owned
outcome, engine state, fixture/provider state, dispatch, authorization, or evidence controls fail at
the HTTP boundary.

The server fingerprints the signal but never places its raw value in audit, response, checkpoint, or
receipt. It queries an immutable terminal result before dispatch lookup or execution, requires the
original principal, loads the exact live `RESUMING` checkpoint, and reconstructs graph/operator,
fixture, replay, authority, side-effect, provider, and effective-plan dependencies. The newly issued
authorization value must equal the dispatch receipt. Fresh execution and cold recovery share
`CompiledTestRuntimeOptions`, preventing fixture/operator lowering drift.

One isolated session restores fixture and provider cursors and applies one synchronous signal. A new
suspension closes the stage and returns a stable conflict; only `COMPLETED`, `FAILED`,
`FAILED_RECOVERY`, `CANCELLED`, or `TERMINATED` may continue. The prepared stage remains open until
the repository consumes its mutation or the service closes it.

`terminalizeRecoveryIdempotently(...)` closes the local commit and ambiguous-response windows at a
recovery terminal boundary. `RecoveryTerminalCommand` binds a caller-stable scoped key, a
server-derived intent fingerprint, the exact source dispatch, normalized outcome, final fixture and
deterministic-provider snapshots, final BLOGE `EngineState`, and one or more stable evidence-gap
codes. The engine mutation independently declares the same execution id and exact state; disagreement
is rejected before a transaction starts.

Inside one test-runtime transaction, the repository proves that the source dispatch came from a
committed owner claim or heartbeat, reads database time, and verifies that the complete dispatch
still controls the live, unexpired `RESUMING` checkpoint. It validates monotonic fixture, provider,
engine, and revision movement, applies the BLOGE mutation, and repeats scope, execution, owner,
epoch, revision, expiry, status, and source-checkpoint fingerprint in the terminal SQL CAS. It then
writes the sealed terminal checkpoint, `bloge.durableTestRecoveryTerminalReceipt.v1`, and
content-addressed `bloge.durableRecoveryTerminalCommandRecord.v1`; an optional local audit/evidence
mutation joins the same commit decision.

The original key replays the exact checkpoint and receipt without running the engine mutation again.
Same-key intent drift, stale or expired fences, self-consistent but unissued dispatches, replica races,
stored checkpoint/receipt/index corruption, and companion failure all fail closed or roll back the
whole first attempt. The receipt carries no fixture, provider seed, request/response, credential, or
engine checkpoint payload. It deliberately proves only the atomic terminal control fact: because the
current checkpoint omits complete trace history before its boundary, v1 always records
`EVIDENCE_INCOMPLETE` with explicit gaps and is promotion-blocking. A future historical-trace closure
and signed evidence assembler must close that gap; changing this receipt's label would be dishonest.

## Transaction-Participating BLOGE Aggregate

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

`StagedBlogeExecutionStore` implements the complete public `ExecutionStore` lifecycle and lease
surface. Opening a stage cold-loads the committed `ExecutionInstance` and supplies the run's logical
`TimeSource` to BLOGE's proven in-memory transition semantics. Create, status, signal-idempotency,
claim/renew/release, recovery-attempt, and delete mutations remain local; exact-id reads observe the
overlay while recovery and operations scans see committed rows only. Preparing produces a
`bloge.testExecutionMutation.v1` content identity. The committed row retains tenant/namespace,
business/graph/shard lookup columns plus the complete immutable JSON value; cold reads reconstruct
and tenant-filter the formal BLOGE record.

`StagedBlogeWaitStore` implements the complete public `WaitStore` state machine. Opening a stage
cold-loads every committed wait for that execution into BLOGE's proven in-memory version-transition
implementation and uses the run's logical `TimeSource`. Exact-id and execution reads observe the
overlay, but global type/key correlation and timer recovery scans expose committed rows only. This
prevents another dispatcher from acting on a signal or timer whose control checkpoint may still
roll back. `resolve` and `timeout` retain BLOGE optimistic-version semantics and persist full
`ExecutionWait` JSON under `bloge.testWaitMutation.v1`; indexed projections support recovery and
dispatch. A wait identity must exactly match its execution lifecycle identity, and a committed
`waitId` cannot move to another execution. Both checks are repeated structurally by execution-bound
updates and primary-key collision at commit.

`StagedBlogeWorkItemStore` implements BLOGE's complete public `WorkItemStore` v5 state machine. It
cold-loads each execution's committed items into BLOGE's proven in-memory claim, lease-renewal,
retry, failure, dead-letter, restore, discard, and cancel implementation, driven by the run's logical
`TimeSource`. Full immutable `WorkItem` JSON is authoritative; dispatch, claim, and execution columns
are scheduling projections. Execution-scoped reads see the local overlay, while global ready and
expired-claim scans deliberately read committed rows only. An asynchronous thread may enqueue only
while BLOGE's graph-execution scope is bound and the trusted execution stage is active; an unscoped
reader still cannot see that speculative item.
Claim and terminal transitions require the caller thread to re-enter the execution stage. Batches
reject duplicate ids and cross-execution membership before mutation. Item identity must retain the
execution tenant, namespace, graph, route, lineage, and source; BLOGE's worker-topic shard is the sole
supported dispatch override. A committed `itemId` cannot migrate to another execution.

`StagedBlogeDurableStateStore` is the only aggregate handed to the engine factory. It opens and
closes all four component stages, combines their fingerprints under
`bloge.testDurableStateMutation.v3`, and applies all prepared mutations through the repository's
single transaction callback. A control checkpoint therefore cannot commit with a missing execution
row, node checkpoint, signal, timer, or work item. Version 3 adds
`bloge.testWorkItemMutation.v1` without changing the historical v1 execution/checkpoint or v2 wait
aggregate material in place. On
concurrent revision advance, concrete row updates serialize competing candidates and the control CAS
chooses the winner; the losing lifecycle, wait, and work-item updates roll back together.
Initial control creation inserts the unique control identity before applying engine state, so two
first writers cannot publish an orphan execution row.

`IndependentDurableTestEngineFactory.openSession(...)` owns the stage and a short-lived
`DurableGraphEngine`. It always selects `CheckpointFailurePolicy.FAIL_FAST`, installs no production
interceptors, listeners, context carriers, or extension listeners, and accepts a complete frozen
`ExecutionOptions`. The session replaces only the first root id allocation with the caller-assigned
engine execution id; the run-scoped operator resolver and all subsequent deterministic providers
remain unchanged. The engine receives the aggregate's execution, checkpoint, wait, and work-item
SPIs, but no
raw store is published as a Spring bean. This keeps fixture control and execution identity outside business
`GraphContext`, permits one root execution, and requires prepare/commit before session close.

`IndependentDurableTestEngineFactory.openRecoverySession(...)` is the corresponding one-use recovery
entry point. It accepts only a current v2 checkpoint whose lifecycle is `RESUMING`, whose exact target
exists, and whose execution-service state is restorable. It restores the cumulative fixture cursor
into an empty recorder, opens all four staged stores for the trusted engine execution id, and requires
the committed BLOGE lifecycle to be `SUSPENDED`.

`RecoverySession.signalAndAwait(...)` first verifies that exactly one waiting signal exists for the
requested node, then invokes BLOGE's synchronous `DurableGraphEngine.resumeSuspended(...)`. Control
returns only after the engine reaches a terminal lifecycle or one unambiguous new signal suspension.
The session checks the recovered execution identity and monotonic engine version before exposing a
`RecoveryBoundary`. `prepare(...)` increments the control boundary sequence and freezes the actual
engine version, cumulative fixture cursor, and complete v3 aggregate mutation for the repository's
owner/epoch/revision CAS. Closing without prepare, losing the CAS, or a later transaction failure
discards the deleted wait and every downstream write together.

The in-process API intentionally has no hard wall-clock timeout parameter. A detached future can make
the caller time out without stopping an operator that ignores interruption, which would let execution
continue after its transaction owner discards the stage. Enforceable deadlines require a killable
worker process or container plus lease fencing, orphan reconciliation, and idempotent side-effect
protocols. Until that worker boundary exists, the implementation makes no cancellation claim.

## BLOGE Fail-Closed Prerequisite

BLOGE source commit `bcbb19694` adds the public `CheckpointFailurePolicy` to both
`DurableManager.Builder` and `DurableGraphEngine.Builder`. `FAIL_FAST` converts node-output, loop,
and sequential for-each checkpoint read/write/codec failures into `DurabilityException`; the loop
operator path preserves that exception instead of logging and continuing. `BEST_EFFORT` remains the
compatibility default.

BLOGE source commit `cb758c1af` adds synchronous `resumeSuspended(...)` overloads to `GraphEngine`
and `DurableGraphEngine`. The runtime restores the persisted suspension, exact `ExecutionOptions`,
caller context, and artifact binding on the caller thread and returns its `GraphResult`. Existing
asynchronous `signal(...)` behavior remains compatible and shares the same cold-signal preparation
and execution kernel. The focused BLOGE gate passed all 15 suspend/signal tests and 17 durable-facade
tests; the module gate passed 1950 Core unit tests, 17 Core integration tests, and 159 Runtime SPI
unit tests with Checkstyle, suppression audit, SpotBugs, source, Javadoc, and install packaging.

Resource Gateway now consumes this prerequisite in its test-profile durable resources: the
transaction-participating execution/checkpoint/wait/work-item aggregate and independent durable
factory both fail closed. Public owner claim now establishes a re-authorized fence and the internal
session can advance one real cold signal. Owner claim atomically issues a payload-free,
authorization-bound dispatch and supports exact scoped historical lookup. Worker acquisition with a
live-fence comparison now has a public authenticated, payload-free heartbeat adapter over the
internal successor protocol. Worker polling/running, terminal evidence, enforceable worker cancellation, and
streaming recovery still must be completed before cold-start resume can be enabled as a product
surface.

## Safety Invariants

1. Durable checkpoints are accepted only in `test` or `staging`; production scope fails before
   persistence.
2. The embedded `EffectiveExecutionPlan.v3` retains full exact replay references. The fixture id,
   positive revision, and fingerprint must agree with the plan's fixture fingerprint; `latest` does
   not exist.
3. Current v2 checkpoints require a `GRAPH` or `OPERATOR` stable id whose fingerprint equals the
   plan target fingerprint and whose kind agrees with the server-authorized execution purpose. Legacy
   v1 checkpoints remain readable but are not eligible for future public target reauthorization.
4. The execution-service snapshot must bind the same plan fingerprint. Resumable states reject a
   provider snapshot that reports restore gaps.
5. Both versions accept only `DENY_REAL`/`REPLAY_ONLY` side-effect policy and a `FAIL_CLOSED` identity
   authority. Sandbox REAL effects or fixture identity require a future protocol after their
   authorities and conformance proofs exist.
6. Runtime correlation values are not persisted. Site and graph occurrence maps accept only
   canonical SHA-256 keys; provider snapshots already follow the same rule for RANDOM/UUID scopes.
   Hashing is pseudonymization, so storage access control remains mandatory.
7. Rule use, dynamic occurrence, logical time, deterministic sequence cursor, provider usage,
   engine state version, and engine boundary sequence cannot move backwards.
8. Tenant, organization, project, environment, actor, run id, engine execution id, dependency
   closure, and creation time are immutable after revision zero.
9. Normal engine-state advance cannot transfer ownership. An expired-lease claim is the sole owner
   handoff: database time must prove expiry, old owner/epoch/revision/fingerprint must all match, and
   success increments epoch and revision while preserving the complete recovery closure.
10. Terminal and `CONTROL_PLAN_UNAVAILABLE` checkpoints cannot advance.
11. Lifecycle timestamps are canonicalized to microsecond precision before hashing so H2 and
    common enterprise SQL stores cannot create false JSON/index drift by rounding Java nanoseconds.
12. Database reads recompute nested and aggregate fingerprints and compare every authorization,
   dependency, fence, and state index with the JSON value. Index/JSON drift is treated as corrupt
   trusted state.
13. Concurrent writers may both enter their engine-state callbacks, but only one CAS can commit;
    the losing callback is rolled back in the same transaction.
14. A prepared BLOGE mutation is bound to both the exact engine execution id and the complete formal
    engine-state value. A caller cannot attach execution A's rows to execution B or reuse a closure
    fingerprint under another boundary/ref/version.
15. Closing the stage invalidates its prepared mutation. A rollback may retry the same frozen
    content while the stage remains open; no mutation can escape session cleanup.
16. Execution lifecycle, node checkpoints, waits, and work items are component fingerprints inside
    one v3 aggregate fingerprint. A lifecycle-only, wait-only, or work-item-only change therefore
    changes the formal engine closure even when the other component rows do not change.
17. The control row and execution row are created in one transaction. Every later revision starts
    from that paired state; a control checkpoint without its execution lifecycle is corrupt input,
    not a supported recovery state.
18. Global wait correlation and timer scans return committed rows only. Execution-local reads remain
    read-your-writes so the current engine can complete suspend/signal logic before prepare.
19. A wait carries the exact lifecycle identity of its execution. A globally unique committed
    `waitId` cannot be reassigned to another execution or tenant by an upsert.
20. Global work-item ready and expired-claim scans expose committed rows only. BLOGE-scoped async
    threads may enqueue into their trusted active execution, but unscoped exact-id reads cannot see
    the overlay; claim and terminal transitions require an execution stage on the caller thread.
21. Work-item batches validate the complete set before mutation. A duplicate id, cross-execution
    member, lifecycle identity drift, or attempted committed `itemId` migration rejects the batch.
22. Work-item claim tokens, versions, retry classifications, terminal timestamps, dead-letter
    restoration, cancellation, and logical-clock behavior come from BLOGE's reference state machine,
    not a second Resource Gateway transition implementation.
23. Lease claims accept only whole-second durations from one second through one hour. Fence-counter
    overflow fails as `INVALID_TRANSITION`; it can never wrap into a valid owner epoch or revision.
24. A scoped durable resume command reserves its idempotency key and claims the lease in one
    transaction. An ambiguous retry returns the immutable original result; same-key different intent,
    cross-scope lookup, result corruption, and malformed keys fail closed.
25. A public owner-claim command cannot select its new owner or lease. Its authorized intent binds
    verified scope, actor, delegation, purpose, clearance, and groups; the fresh lease CAS and
    semantic `ALLOWED` audit share one local transaction.
26. Idempotent response replay reads its immutable committed result before re-evaluating mutable
    dependencies, but records a new replay audit. This preserves command history without silently
    authorizing a new recovery action under stale policy.
27. An internal recovery session accepts only a current v2 `RESUMING` checkpoint with an exact target
    and restorable provider state, and only when the paired BLOGE execution is committed as
    `SUSPENDED`.
28. Cold signal recovery must execute synchronously inside the stage owner. Closing a session cannot
    leave a detached engine thread mutating discarded state.
29. The requested node must have exactly one waiting signal. The next accepted boundary is terminal
    or exactly one new signal suspension, and the BLOGE execution version must advance monotonically.
30. The next control boundary sequence, actual engine version, cumulative fixture cursor, and four
    store mutations are one prepared recovery value. Publishing any subset is forbidden.
31. A heartbeat source dispatch must resolve to exactly one verified owner-claim or predecessor
    heartbeat result. Canonical content identity without a committed issuer record is rejected.
32. Heartbeat CAS repeats the complete live dispatch fence and requires database time to remain
    before its lease deadline. An expired worker cannot revive itself after takeover becomes legal.
33. Heartbeat changes only revision, update time, lease deadline, checkpoint fingerprint, and
    successor dispatch. Dependency, fixture, provider, cursor, engine, owner, and epoch closure is
    immutable.
34. The checkpoint CAS, immutable heartbeat result, successor dispatch, and companion audit/evidence
    mutation have one commit decision. The source key replays that result; another key cannot reuse
    the consumed dispatch.
35. A terminal source dispatch must be both provably issued and still control the exact live,
    unexpired `RESUMING` fence at database time.
36. The final engine mutation, monotonic fixture/provider state, terminal checkpoint, immutable
    command result, receipt, and companion mutation have one local commit decision.
37. Idempotent terminal replay never executes the engine mutation again; same-key outcome, state, or
    evidence-gap drift is an `IDEMPOTENCY_CONFLICT`.
38. Terminal receipt v1 always has non-empty explicit gaps and `EVIDENCE_INCOMPLETE`; without complete
    historical trace, no code path may mark it certifiable or promotion-eligible.

## Automated Evidence

`DurableTestExecutionCheckpointTest` proves complete closure sealing, replay-reference retention,
production rejection, hashed cursor enforcement, nested fixture tamper rejection, provider-state
tamper rejection, plan/provider identity closure, mandatory v2 target locator and purpose binding,
and canonical v1 JSON round-trip compatibility without an invented target field.

`DatabaseDurableTestExecutionCheckpointRepositoryTest` proves:

- atomic create and advance with a participating engine-state table;
- create rollback after an injected engine-store failure;
- stale owner rejection before engine mutation;
- two-writer CAS with exactly one committed engine mutation;
- tenant/environment-scoped lookup;
- idempotent duplicate create and cross-tenant global engine-id collision rejection;
- fail-closed indexed-column/JSON drift detection, including target locator drift;
- upgraded-table storage and exact readback of legacy v1 rows with nullable target projections;
- cursor rewind rejection before engine mutation;
- database-clock expired-lease claim with exact recovery-closure preservation and resealing;
- active-lease, terminal-state, stale-fingerprint, cross-tenant, duration, and counter-overflow
  rejection; and
- two repository instances racing one expired lease with exactly one new owner and immediate
  fencing of the former owner;
- database-clock heartbeat renewal with one-revision successor dispatch and exact historical lookup;
- same-key replay and two-replica convergence on one committed successor;
- stale, expired, self-consistent-but-unissued dispatch, malformed command, and same-key intent-drift
  rejection;
- heartbeat checkpoint/dispatch/record tamper detection and atomic companion-audit rollback;
- atomic terminal engine/checkpoint/receipt commit and exact immutable replay without a second engine
  mutation;
- stale, expired, and valid-but-unissued terminal dispatch rejection before engine mutation;
- terminal command intent drift, mandatory evidence gaps, stored-receipt tamper detection, companion
  rollback, and two-replica convergence on one committed terminal result;
- durable command replay after an ambiguous response, same-key intent conflict, and strict command
  identity validation; and
- two repository instances issuing the same command with one original result and one exact replay,
  plus cross-scope non-disclosure, indexed-intent drift detection, and stored-result tamper rejection.

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
run without a composite stage and that a real suspend/signal execution creates and removes a staged
signal wait. `TestRuntimeProfileIsolationTest` proves that the holder exists only under the isolated
test profile, that the checkpoint repository is absent whenever production is active, that raw
execution/checkpoint/wait/work-item SPIs are not beans, and that testing is vetoed whenever production
is active.

`StagedBlogeDurableStateStoreTest` proves real-engine lifecycle/checkpoint atomic commit; atomic wait
and work-item commit and rollback; cold reconstruction of `ExecutionInstance`, `ExecutionWait`, and
`WorkItem`; BLOGE optimistic-version semantics for lifecycle, wait, claim, retry, failed, and
dead-letter transitions; committed-only timer, correlation, ready-work, and expired-claim scans;
aggregate fingerprint sensitivity to lifecycle-only, wait-only, and work-item-only changes; atomic
batch validation, tenant isolation, async enqueue visibility, identity-drift and cross-execution id
rejection; and two-instance control-CAS rollback of the losing execution, wait, and work-item status.

`IndependentDurableTestRecoverySessionTest` creates and commits a real BLOGE suspension, opens a v2
`RESUMING` recovery session, restores through the synchronous cold-signal API, and proves that the
terminal lifecycle, deleted wait, fixture cursor, actual engine version, and control checkpoint
advance publish atomically. Its rollback case completes the same synchronous recovery but closes
without prepare, proving the committed suspension and waiting signal remain unchanged. Four negative
cases reject control state that has not entered `RESUMING`, engine-version drift, a non-suspend
boundary, and a signal node that differs from the claimed boundary.

`DurableTestRecoveryAuthorityTest` proves that volatile refresh telemetry cannot invalidate a
checkpoint while issuer/audience and authorization policy drift does, and that unavailable or stale
identity authority fails closed. `DurableTestRecoveryAuthorizerTest` rebuilds real graph and operator
targets and proves exact target, fixture, clearance, authority, provider-state, replay closure, and
effective-plan binding. `DurableTestOwnerClaimServiceTest` proves non-disclosing scope checks, v1
migration rejection, replay-before-reauthorization idempotency, cross-instance winner convergence,
stable conflict mapping, and atomic rollback when semantic audit cannot commit.

`DurableTestOwnerClaimControllerTest`, `TestRuntimeProfileIsolationTest`,
`TestingControlProtocolSchemaTest`, and `TestabilityCapabilitiesTest` prove workload authentication,
purpose restriction, unknown-field rejection, payload-free HTTP projection, production bean absence,
Draft 2020-12 wire contracts, and truthful capability advertisement.

`DurableTestRecoveryPrincipalTest`, `DurableTestRecoveryHeartbeatServiceTest`, and
`DurableTestRecoveryHeartbeatControllerTest` prove canonical principal stability across correlation
retry and group ordering, authority-drift rejection, exact hidden-dispatch resolution, server-owned
lease policy, atomic semantic audit, immutable replay, stable conflict mapping, nested unknown-field
rejection, payload-free HTTP projection, and dedicated-operation authentication. The database suite
also executes the public service against the real repository and transaction-bound audit store.

Reproduce the focused gate with:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DurableTestExecutionCheckpointTest,DatabaseDurableTestExecutionCheckpointRepositoryTest,StagedBlogeExecutionCheckpointStoreTest,StagedBlogeDurableStateStoreTest,IndependentDurableTestEngineFactoryTest,IndependentDurableTestRecoverySessionTest,InvocationRecorderCheckpointTest,DurableTestRecoveryAuthorityTest,DurableTestRecoveryAuthorizerTest,DurableTestExecutionQueryServiceTest,DurableTestExecutionQueryControllerTest,DurableTestOwnerClaimServiceTest,DurableTestOwnerClaimControllerTest,DurableTestRecoveryPrincipalTest,DurableTestRecoveryHeartbeatServiceTest,DurableTestRecoveryHeartbeatControllerTest,DurableTestTerminalRecoveryRuntimeTest,DurableTestTerminalRecoveryServiceTest,DurableTestTerminalRecoveryControllerTest,TestingControlProtocolSchemaTest,TestabilityCapabilitiesTest,TestRuntimeProfileIsolationTest test
```

The combined focused gate completed with 152 tests, zero failures, zero errors, and zero skips. The
recovery-session slice contributes six database-level tests; authorization-bound dispatch adds two
database-level claim/replay, exact-lookup, and tamper cases plus two regional-authority cases. The
public query slice adds six service cases, including a real-database projection-tamper case, and two
controller cases covering dedicated authentication, non-disclosure scope, legacy migration facts,
payload omission, invalid identifiers, and stable unavailable mapping.
live-fence heartbeat slice adds eight database cases for renewal/replay, successor lookup, stale and
expired fences, unissued dispatches, command validation, two-replica convergence, rollback, and
tamper rejection.
The public heartbeat slice adds one database end-to-end case, one canonical-principal case, eight
service cases, and two controller cases covering authentication, strict wire parsing, principal
continuity, lease-policy validation, replay, atomic audit, and stable payload-free failures.
The terminal-commit slice adds eight database cases for atomic commit/replay, companion rollback,
intent drift, mandatory gaps, receipt tampering, stale/expired/unissued dispatches, and two-replica
convergence.
The public terminal slice adds two staged-runtime lifecycle cases, five application-service cases,
and two controller cases covering server-derived state, reauthorization continuity, pre-execution
replay, audit outage, non-terminal rollback, signal bounds, strict parsing, and payload-free output.
The repository-wide `clean verify` gate completed with 2023 tests, zero failures, zero errors, two
conditional skips, real-browser regression coverage, and successful Spring Boot JAR packaging.
Scoped public `javadoc -Xdoclint:all -Werror` for the recovery production types is also a required
pre-commit gate.
The optional project-wide Javadoc report still fails on 16 pre-existing HTML and
parameter diagnostics in unrelated packages; that baseline is not represented as fixed here.

## Honest Remaining Gaps

- BLOGE execution lifecycle/lease, node/loop/sequential-foreach checkpoints, signal/timer/task/retry
  waits, and work-item state now execute through one aggregate `EngineStateMutation`. The internal
  recovery session can advance one real cold signal, while public owner claim, authenticated
  heartbeat, and terminal recovery can establish, renew, and terminally consume the control fence.
  Public run query now exposes integrity-verified operational facts. Run creation, worker
  polling/dispatch, multi-suspension coordination, and automatic
  heartbeat scheduling do not exist, so this must not be mistaken for a complete remote-worker
  product lifecycle.
- The durable and recovery sessions remain internal resources. Public terminal recovery consumes one
  exact handoff synchronously, but no dispatcher consumes queued work and no general suspend/resume
  or repeated-signal endpoint exists.
- Legacy v1 checkpoint rows remain readable but have no graph/operator locator. They must be
  terminalized or migrated through an independently verified target mapping before any future public
  recovery service may consider them; guessing a target from fingerprint or plan diagnostics is
  forbidden. Current v2 rows close this locator gap, and the public owner claim authorizes them
  before mutation.
- The fixture ledger snapshot intentionally excludes pre-checkpoint invocation and attempt evidence.
  The new terminal receipt exposes that fact as mandatory `EVIDENCE_INCOMPLETE` gaps, so a resumed
  terminal evidence bundle still cannot reconstruct or certify those historical trace facts.
- Owner claim now re-authorizes the v2 target locator/fingerprint, exact fixture revision, replay
  closure, current identity authority, side-effect policy, deterministic provider snapshot, effective
  plan, and caller scope/purpose/clearance, then persists their content-addressed receipt in the exact
  dispatch. A cold worker must reconstruct and reproduce that receipt and still hold the live fence;
  the dispatch is not a transferable authorization token.
- The public adapters durably bind normalized caller intent and authenticated authority to one claim,
  each exact heartbeat successor, and one terminal signal. Heartbeat storage rejects
  unissued/stale/expired dispatches and rotates a successor atomically; terminal execution rechecks
  authorization and prevents duplicate engine mutation or partial local commit. Worker discovery,
  polling/dispatch, heartbeat scheduling, general suspend/resume, crash reconciliation, multi-boundary
  orchestration, complete signed evidence assembly,
  signed checkpoint attestation, and an enforceable
  process-level deadline remain orchestration work rather than properties inferred from storage CAS
  or internal synchronous recovery success.
- The repository uses a local database transaction. Cross-database BLOGE stores require either
  migration onto this datasource or an outbox/recovery protocol; pretending a distributed
  transaction exists is explicitly rejected.
- Stream/event fixture state, an explicit streaming offset/checkpoint protocol, typed
  identity/feature-flag/secret authorities, deterministic parallel scheduling, and physical
  test-runtime deployment remain Stage 4/5 work.
- Ready/expired work scans currently deserialize the authoritative rows before filtering. Indexed
  SQL pushdown plus projection-integrity monitoring is a throughput gate before high-volume worker
  deployment; correctness currently takes precedence over claiming unmeasured dispatch scale.
