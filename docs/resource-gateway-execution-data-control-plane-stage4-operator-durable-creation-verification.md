# Execution Data Control Plane Stage 4 Operator Durable Creation Verification

## 1. Verification Claim

Resource Gateway can now create an authenticated, idempotent durable test for one exact operator
binding and commit its revision-zero suspension before invoking the business operator. The same
durable query, owner claim, heartbeat, and terminal-recovery protocol can later cold-start the
canonical operator graph and execute the subject exactly once from the frozen formal input.

This is a narrow `test`/`staging` authoring-runtime capability. It is not a worker queue, general
multi-boundary durable engine, hard-cancellation boundary, or complete historical evidence claim.

## 2. Root Cause And Design Correction

A synchronous one-node operator graph normally reaches `COMPLETED`, while durable creation accepts
only one live `WAIT_SIGNAL`. Merely allowing `target.kind=OPERATOR` through the existing graph creator
would therefore expose an endpoint that deterministically rejects ordinary operators. Treating that
as operator durable creation would be protocol theater rather than a usable capability.

The root problem is the absence of a stable checkpoint boundary before the business binding. The
fix is a server-owned start gate, not a caller-defined wait and not a special parallel durable engine:

```text
durable-operator-start (read-only, idempotent, WAIT_SIGNAL)
        |
        v
subject (exact frozen operator binding, reads persisted operatorInput)
```

Fresh creation can now commit while the gate is suspended and `subject` has zero invocations.
Terminal recovery signals the exact gate, which ignores signal data, and only then executes
`subject`. This preserves one kernel for graph and operator durability.

## 3. Frozen Wire Contract

The graph request remains `bloge.durableTestExecutionCreateRequest.v1` with `kind=GRAPH`. Operator
creation uses a separate additive contract:

- endpoint: `POST /api/testing/durable-executions/operators/{operatorRef}`;
- request: `bloge.durableOperatorTestExecutionCreateRequest.v1`;
- exact path/body target: `{kind=OPERATOR,id,fingerprint}`;
- purpose: `OPERATOR_UNIT_TEST`;
- formal `input`: arbitrary JSON, at most 1 MiB after serialization;
- fixture: exact stored `{fixtureBundleId,revision,fingerprint}`;
- response: existing payload-free `bloge.durableTestExecutionCreateResponse.v1`.

Unknown fields, inline/latest fixtures, caller context, owner/lease/control fields, path drift,
fingerprint drift, purpose drift, and hidden test-control keys fail closed. The authenticated request
fingerprint covers the complete request and principal. A committed retry is resolved before mutable
operator-registry or fixture-store reads.

## 4. Runtime And Atomicity Invariants

| Invariant | Enforcement |
| --- | --- |
| Exact binding | Authorizer resolves the path operator, requires exact descriptor fingerprint, and rebuilds the same canonical durable graph during recovery. |
| Typed input | `OperatorInputCoercer` converts JSON with frozen operator metadata before any execution authority is reserved. |
| No pre-checkpoint business call | The canonical source is `durable-operator-start`; `subject` depends on it and cannot run during fresh creation. |
| Input isolation | Only server code writes `operatorInput`; it is absent from command records, responses, audits, and terminal receipts. |
| Signal separation | Recovery signal data releases the gate but never replaces or mutates `operatorInput`. |
| Same execution kernel | Operator creation uses the existing creation reservation, admission, lease coordinator, `RunSession`, staged stores, and repository commit. |
| Atomic revision zero | Execution, checkpoint, wait, work-item mutation, immutable command result, and semantic audit commit or roll back together. |
| Exact cold recovery | Recovery reconstructs the same two-node graph, restores provider/fixture cursors, and signals the persisted gate. |
| At-most-once committed recovery | Exact fence CAS and immutable command replay prevent a response-loss retry from reapplying the signal or operator mutation. |
| Capacity isolation | The exact operator and internal start gate appear in the compiled invocation inventory and acquire conservative admission capacity before engine start. |

The gate declares `READ_ONLY` and `IDEMPOTENT`. Its stable node id is
`durable-operator-start`, which satisfies BLOGE checkpoint identifier rules. A fixture selector that
tries to control this internal suspendable node fails closed instead of changing gate behavior.

## 5. Authorization And Failure Semantics

The controller is absent in production and requires the existing authenticated integration
operation `TEST_DURABLE_EXECUTION_CREATE` with `TEST_EXECUTION` or `TEST_REPLAY` workload purpose.
Before reservation, authorization freezes:

- tenant, organization, project, environment, region, actor, delegation, groups, and clearance;
- exact operator descriptor, implementation, schema, runtime-state, and composability closure;
- exact fixture and replay dependencies;
- identity/side-effect authority and deterministic provider state;
- effective control plan and invocation inventory.

Invalid formal input returns `400 RG.TEST.DURABLE_OPERATOR_INPUT_INVALID`. Target, fixture,
authorization, plan, provider, admission, preparation-lease, unsupported-boundary, store, and audit
failures retain the existing stable durable-control-plane error taxonomy. No error includes business
input, fixture values, provider state, credentials, or raw recovery signal data.

## 6. Capability And Schema Evidence

`/api/integration/capabilities` publishes all three facts together when testing is enabled:

- object `durableOperatorTestExecutionCreateRequest` with the exact v1 schema version;
- feature `durableOperatorTestExecutionCreation=true`;
- endpoint `/api/testing/durable-executions/operators/{operatorRef}`.

The authority schema at
`docs/schemas/resource-gateway-testing/testing-control-plane-v1.schema.json` defines the strict
operator request independently of the graph request. Tests pin required fields, closed-object
behavior, target kind, purpose, and SHA-256 fingerprint constraints.

## 7. Automated Evidence

| Proof | Test evidence |
| --- | --- |
| Public authentication and exact endpoint | `DurableTestExecutionCreationControllerTest` |
| Request validation, common admission/lease/atomic commit, and replay-before-registry | `DurableTestExecutionCreationServiceTest` |
| Exact binding, typed input, canonical graph, path drift rejection | `DurableTestRecoveryAuthorizerTest` |
| Gate commits before business invocation | `IndependentDurableTestEngineFactoryTest` |
| Cold signal restores input and invokes subject exactly once | `IndependentDurableTestRecoverySessionTest` |
| Strict JSON Schema contract | `TestingControlProtocolSchemaTest` |
| Capability disabled/enabled truthfulness | `TestabilityCapabilitiesTest` |

Focused command:

```bash
/opt/apache-maven-3.9.16/bin/mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DurableTestExecutionCreationServiceTest,DurableTestRecoveryAuthorizerTest,\
DurableTestExecutionCreationControllerTest,TestingControlProtocolSchemaTest,\
TestabilityCapabilitiesTest,IndependentDurableTestEngineFactoryTest,\
IndependentDurableTestRecoverySessionTest test
```

Result on 2026-07-17: **45 tests, 0 failures, 0 errors, 0 skips**. The compatibility case
proves that an existing one-node OPERATOR checkpoint is reconstructed with the legacy micro graph,
while checkpoints whose persisted boundary is `durable-operator-start` use the new canonical graph.

Full verification on 2026-07-17:

- Resource Gateway `clean verify`: **2131 tests, 0 failures, 0 errors, 2 conditional browser skips**;
  Spring Boot JAR packaged successfully.
- Independent test-kit `clean verify`: **62 tests, 0 failures, 0 errors, 0 skips**; ordinary and
  shaded CLI JARs package the updated authority schema and the public JavaDoc gate passes.

## 8. Explicit Remaining Gaps

- No worker poll, remote dispatch acquisition, queue scheduler, fairness, or priority policy.
- No cross-process worker supervision or killable wall-clock execution boundary.
- No automatic multi-suspension continuation or general-purpose durable operator workflow.
- No stream offset/checkpoint recovery protocol.
- No complete pre-checkpoint node/edge/attempt trace; terminal receipt remains
  `EVIDENCE_INCOMPLETE` and promotion-blocking.
- No identity, feature-flag, or test-secret fixture authority completion.
- The internal gate consumes a conservative operator admission slot; capacity policy may later gain
  an explicit trusted-infrastructure subject class, but must not be inferred from caller metadata.

These gaps are not hidden by the new capability flag. The increment proves exact operator initial
creation and one-signal terminal recovery only.
