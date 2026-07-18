# Stage 5 Suite-Stability Cancellation Audit Verification

## 1. Scope

This increment closes the semantic-audit gap in the asynchronous suite-stability cancellation
path. It makes the first accepted cancellation command, its database-observed state result, and one
payload-free security event a single local transaction. Exact command replay returns the retained
job without another semantic event.

It does not claim hard process interruption, external WORM storage, legal hold, backup erasure, or a
product-supplied current-IAM adapter. Authentication access audit remains a separate record of every
HTTP attempt; this increment records the exactly-once business meaning of the first accepted
cancellation command.

## 2. Protocol boundary

`TestSuiteStabilityJobCancellationCommand` carries the exact tenant/environment/job, caller-stable
command id, actor-bound command fingerprint, and credential-free current actor snapshot. It cannot
carry a bearer token, request headers, suite request, fixture, graph context, or execution output.

`TestSuiteStabilityJobCancellationReceipt` is created only after the repository locks the
environment and reads database time. Its closed outcome vocabulary is:

| Previous state | Resulting state | Audit outcome |
| --- | --- | --- |
| `QUEUED` | `CANCELLED` | `CANCELLED_BEFORE_START` |
| `RUNNING` | `CANCEL_REQUESTED` | `CANCELLATION_REQUESTED` |
| `COMMITTING` | `COMMITTING` | `TOO_LATE_TO_CANCEL` |
| any terminal state | same terminal state | `ALREADY_TERMINAL` |
| queued/running parent already signed | `SUCCEEDED` | `PARENT_ALREADY_COMPLETED` |

Contradictory state/outcome combinations are rejected by the receipt constructor before audit
material can be committed.

## 3. Atomicity and replay

The repository contract accepts a function from the database result receipt to
`TestRuntimeTransactionMutation`. For a fresh command it performs this sequence under one
environment-serialized transaction:

1. lock and integrity-check the exact scoped job;
2. reject a retained command id/fingerprint mismatch;
3. resolve parent-first cancellation or the explicit too-late/terminal no-op;
4. retain the first command id/fingerprint even when state is unchanged;
5. compare-and-update the whole-row integrity fingerprint;
6. derive and execute the transaction-bound semantic event mutation;
7. commit both writes, or roll both back.

Returning no audit mutation or throwing during the mutation aborts the transaction. A retained exact
command exits before the audit factory is invoked. `COMMITTING` publication and later terminal
transitions preserve the cancellation identity, so crash takeover cannot reopen the command slot or
duplicate its event.

Parent stop uses the pre-existing parent-first safety protocol. Its separate durable stop may remain
after an outer transaction failure; that conservative stop is replayable and prevents resumable
parent work from escaping cancellation. It is not presented as a distributed two-phase commit.

## 4. Audit data minimization

The append-only `SUITE_STABILITY_JOB_CANCELLATION` event contains:

- schema version, job id, command id and canonical command fingerprint;
- organization/project and actor type/id;
- delegation actor/grant, purpose and clearance;
- group count and content fingerprint, never group names;
- database-time previous/resulting status and closed cancellation outcome;
- a semantic fingerprint over the complete top-level scope/actor/time/reason and payload-free fact map.

It excludes credentials, headers, suite/fixture/context data, execution metadata, node/edge values,
source run ids, lease owner/epoch/expiry, policy generation and row seal. The public job view still
excludes the retained cancellation id/fingerprint and the security event.

The security-event repository recomputes this complete fingerprint before append and after every
read. A modified tenant, actor, database time, event outcome, reason code, status, or other fact is
rejected as an integrity failure rather than returned to an audit consumer.

Capability discovery exposes `asyncSuiteStabilityJobCancellationSemanticAudit` independently from
route existence and worker-backed submission readiness, allowing N/N-1 consumers to require this
stronger cancellation contract explicitly.

## 5. Failure matrix

| Condition | Result |
| --- | --- |
| exact command replay | original job; no second semantic event |
| same command id with changed fingerprint | `CANCELLATION_CONFLICT`; no mutation/event |
| first command at `COMMITTING` | status unchanged, command retained, one `TOO_LATE` event |
| first command after terminal | status unchanged, command retained, one terminal-no-op event |
| signed parent already won | queue converges to `SUCCEEDED`, command retained, one parent-winner event |
| audit factory absent/returns null/fails | transaction rollback; original queue row remains |
| parent authority unavailable | transaction rollback; no semantic event |
| cross-scope caller | hidden before repository cancellation; no semantic event |

## 6. Verification

The focused gate is:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestSuiteStabilityJobCancellationReceiptTest,\
TestSuiteStabilityJobServiceTest,DatabaseTestSuiteStabilityJobRepositoryTest test
```

It executes 57 tests with zero failures, errors, or skips. The cases cover command/receipt shape,
all five outcomes, payload minimization, actor/correlation behavior, queued/running cancellation,
parent winner, `COMMITTING`, terminal no-op, exact replay, conflicting replay, publication after a
too-late command, transaction rollback when the audit sink fails, and fail-closed read after stored
actor/fact tampering. The capability regression raises the combined focused gate to 60 tests.

The project-wide `clean verify` executes 2628 tests with zero failures, zero errors, and two existing
conditional skips. The configured real-browser regression completes and the executable Spring Boot
JAR is repackaged successfully. Compilation, configuration/profile isolation, protocol Schema, and
packaging gates all pass.

## 7. Residual risk

The semantic event is queryable through the existing test security-event repository, but this
increment does not add an external governance export endpoint, WORM anchor, legal-hold policy, or
backup-erasure proof. The worker still requires a real externally supplied
`TestSuiteStabilityJobAuthorizer`; the product-supplied current-IAM adapter is the next safety
increment. Cooperative cancellation still cannot kill a non-cooperative operator process.
