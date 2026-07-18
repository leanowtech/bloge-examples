# Stage 5 suite-stability worker wiring verification

## 1. Increment boundary

This increment wires the verified durable queue and bounded worker lifecycle into the
`test`/`staging` Spring composition root. It does not expose job submission, cancellation, or query
over HTTP, and it does not provide a permissive current-authority implementation.

The composition root now always creates, inside the isolated test-runtime profile:

1. `RepositoryTestSuiteStabilityJobParentAuthority` for signed parent proof and parent-first stop;
2. `DatabaseTestSuiteStabilityJobRepository` for durable queue authority;
3. one startup-validated `TestSuiteStabilityQueuePolicy` shared by all local worker components.

The heartbeat coordinator, single-poll worker, and fixed-delay scheduler exist only when
`gateway.testing.stability-jobs.worker.enabled=true`.

## 2. Current-authority gate

A submission-time principal is historical evidence, not a renewable credential. Resource Gateway
therefore supplies no default `TestSuiteStabilityJobAuthorizer`. An enabled worker requires exactly
one deployment-provided bean that can evaluate the durable actor/delegation and exact job intent
against current IAM, tenant, environment, and policy state.

Startup fails for both zero and multiple providers. A Spring `@Primary` cannot silently choose among
competing authorities because the composition root counts the complete provider set. This prevents
an accidental local allow-all implementation or ambiguous IAM migration from starting execution.

## 3. Startup invariants

| Configuration defect | Startup result | Reason |
| --- | --- | --- |
| worker omitted or explicitly disabled | queue available; no worker threads | safe migration and API groundwork |
| worker enabled, no authorizer | fail | historical principal cannot grant current authority |
| worker enabled, multiple authorizers | fail | authority selection must not depend on bean ordering |
| heartbeat greater than one-third queue lease | fail | a single delayed renewal would consume the safety margin |
| enabled environment outside `test`/`staging` | fail | production execution is structurally excluded |
| fewer pollers than enabled environments | fail | every queue needs a live polling lane |
| invalid queue capacity, retry, deadline, or retention | fail | replicas must converge on one finite policy |

The scheduler is a Spring disposable bean. Context failure and normal shutdown close the scheduler,
heartbeat coordinator, and isolated datasource. Shutdown interruption remains best effort; durable
queue and parent fences are the publication authority.

## 4. Configuration surface

`application-test.yml` and `application-staging.yml` publish the complete environment-variable
mapping under `gateway.testing.stability-jobs`:

- `queue.*` owns generation, global/tenant capacity, lease, aging, retry, deadline, and retention;
- `worker.enabled` defaults to `false`;
- `worker.instance-id`, environments, local execution slots, polling lanes, heartbeat, poll timing,
  and drain timeout own only process-local execution behavior.

Changing a retained queue policy requires a strictly newer generation. The repository, rather than
Spring configuration, rejects cross-replica policy drift while non-terminal jobs remain.

## 5. Verification

Focused command:

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestRuntimeProfileIsolationTest,DatabaseTestSuiteStabilityJobRepositoryTest,\
RepositoryTestSuiteStabilityJobParentAuthorityTest,\
DatabaseTestSuiteStabilityRunRepositoryTest,TestSuiteStabilityExecutionServiceTest,\
TestSuiteStabilityJobExecutionCoordinatorTest,TestSuiteStabilityJobWorkerTest,\
TestSuiteStabilityJobSchedulerTest test
```

The 84 focused tests pass with zero failures, errors, or skips. The configuration tests create real
Spring contexts and isolated H2 datasources. They prove production-profile absence, default-off
thread lifecycle, successful assembly with one authority, fail-fast zero/multiple authority,
heartbeat/lease rejection, environment starvation rejection, invalid policy rejection while the
worker is disabled, and resource cleanup after failed refresh.

## 6. Remaining gap

This increment closes product wiring, not product exposure. Before the asynchronous path can be
declared available, Resource Gateway still needs bounded terminal retention and poison-row handling,
authenticated HTTP submit/query/cancel, strict Schema, capability truth, and independent test-kit
support. Aggregate-only worker telemetry and database-clock backlog/readiness are now closed by the
[queue observability increment](resource-gateway-execution-data-control-plane-stage5-suite-stability-queue-observability-verification.md).
A real
deployment IAM adapter must also be supplied and conformance-tested; enabling the environment flag
alone intentionally fails.
