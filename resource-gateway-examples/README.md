# BLOGE Resource Gateway

**Turn external APIs into schema-checked building blocks for business workflows.**

Resource Gateway is a Spring Boot example that shows how BLOGE can turn messy
provider integrations into visible, reusable orchestration. Instead of writing a
new Java operator for every upstream endpoint, teams describe resources with
`ResourceDescriptor`, compose them in `.bloge` graphs or the visual canvas, and
let the same schema contract drive validation, simulation, runtime execution,
and reuse.

The interesting part is not "calling HTTP". The interesting part is making API
integration something the business flow can see, reason about, test, and change.

## What You Get

| Capability | Why it matters |
| --- | --- |
| Descriptor-first resources | Add most APIs by changing contracts, not cloning Java operators |
| Graph-level contracts | Every built-in resource graph exposes formal input/output JSON Schema for system integration |
| Schema-aware canvas | Drag, connect, validate, simulate, and publish under server-side schema checks |
| Runtime-backed demos | Local upstreams, real gateway execution, mock simulation, SSE examples, and reusable publications |
| Schema-gated table tests | Run 13 built-in cases across all seven resource graphs with F3 transport fixtures, bounded retry consumption, coverage gates, and fidelity evidence |
| Isolated testing control plane | Test/staging-only target discovery, immutable fixture registry, caller-driven DAG execution, sanitized evidence retention, batch runs, and production control-field guard |
| Governed run controls | Absolute deadline, monotonic remaining-budget propagation, fenced cancel, durable owner lease/epoch, cross-instance commands, and automatic signed evidence recovery after owner failure |
| Auditable external writes | Versioned write contracts, binding/activation conformance, execution-scoped journal, commit receipts, UNKNOWN_COMMIT DAG guard, and signed reconciliation evidence |
| Dynamic workload identity | Atomic JWKS/revocation refresh, zero-restart key rotation, bounded propagation SLO, group/clearance/delegation claims, and explicit 401/503 semantics |
| Managed evidence signing | Non-exportable KMS/HSM provider protocol, atomic public-key generations, locally verified signatures, rotation/revoke semantics, and machine-readable custody health |
| Consistent draft export | Frozen operator/library/binding/activation/test-suite refs, deterministic dependency fingerprints, and retryable 409 conflict on assembly-time drift |
| Governed replay payloads | Payload values detached from immutable evidence, classification ABAC, selective retention, legal hold, bounded expiry, and signed deletion proof |
| Workbook and gate evidence loop | Deterministic sanitized workbook seeds, exact suite/run evidence refs, versioned gate decision basis, stale detection, and transactional gate events |
| Operational controls | Cache, tenant rate limit, circuit breaker, run history, golden cases, and publication history |

## Start The Demo

From the repository root:

```bash
./scripts/start-visual-canvas-demo.sh --open
```

This packages the Spring Boot gateway with the React frontend and starts the
demo on `http://localhost:8080`. The dedicated demo script activates the `test`
profile by default so `/api/testing/**` is available; use `--profile production`
to demonstrate that the testing beans and endpoints are structurally absent.

| Open | Best first move |
| --- | --- |
| `http://localhost:8080/author/` | Build a schema-constrained graph on the visual canvas |
| `http://localhost:8080/showcase/` | Explore guided product scenarios and sample outputs |
| `http://localhost:8080/examples/gateway` | Use the legacy Custom Composer regression surface |
| `http://localhost:8080/api/integration/capabilities` | Verify protocol versions, endpoints, feature flags, identity provider, payload policy, and signer readiness |
| `http://localhost:8080/api/gateway/graphs/contracts` | Inspect resource graph input/output contracts |
| `GET http://localhost:8080/api/testing/targets/graphs/{graphName}` | Freeze the graph/resource target fingerprint before authoring fixtures (test/staging only) |
| `POST http://localhost:8080/api/testing/executions` | Run an isolated inline or governed fixture plan and retain sanitized evidence (test/staging only) |
| `POST http://localhost:8080/api/gateway/graphs/contracts/tests/draft` | Generate editable graph mock/table suites from graph and resource schemas |
| `POST http://localhost:8080/api/gateway/graphs/contracts/tests/run` | Run schema-gated mock/table contract suites |
| `POST http://localhost:8080/api/gateway/graphs/contracts/tests/suites/run-all` | Run every stored contract suite with coverage policy checks |
| `POST http://localhost:8080/api/visual/operators/tests/draft` | Generate editable operator mock/table suites from operator schemas |
| `POST http://localhost:8080/api/visual/operators/tests/suites/run-all` | Run every stored operator schema mock/table suite |

Stop it with:

```bash
./scripts/stop-visual-canvas-demo.sh
```

Useful variants:

```bash
./scripts/start-visual-canvas-demo.sh --port 18080
./scripts/start-visual-canvas-demo.sh --no-build
./scripts/start-visual-canvas-demo.sh --run-tests
./scripts/start-visual-canvas-demo.sh --profile staging
./scripts/start-visual-canvas-demo.sh --profile production
./scripts/visual-canvas-demo.sh status
./scripts/visual-canvas-demo.sh restart
```

The start command becomes ready only after the integration capability probe
succeeds. Process output is written to `target/example-logs/visual-canvas-demo.log`;
the PID and selected port are kept under `target/example-pids/`.

The testing API requires `Authorization: Bearer bloge-aneke-demo-token` and a
least-privilege `X-Purpose` (`TEST_EXECUTION`, `TEST_FIXTURE_READ`, or
`TEST_FIXTURE_WRITE`) in the local test profile. See
[Testing Control Plane API](../docs/resource-gateway-testing-control-plane-api.md)
for the complete target-discovery, fixture-registration, execution, evidence,
and production-isolation workflow. Java/JUnit consumers can use the independent
[Resource Gateway Test Kit](../resource-gateway-test-kit/README.md) instead of
hand-assembling HTTP requests and JUnit XML.

Batch-migrate existing `.bloge` files after the service is running:

```bash
./scripts/bloge-dsl-batch-import.sh report \
  --base-url http://localhost:8080 \
  --operator-library risk-policy \
  --dsl-dir resource-gateway-examples/src/main/resources/bloge/gateway \
  --out target/dsl-batch-report.json
```

Use `commit --commit-policy renderable|fully-projected|rewrite-allowed` with the
same inputs to save eligible projections as governed visual drafts. The script
does not write source `.bloge` files; source replacement still belongs behind
rewrite gate evidence and a reviewed VCS/source-writer flow.

## Notice The Product Loop

1. **Import or use a resource contract**: seeded resources such as
   `user-service.getProfile`, `order-service.listOrders`, and
   `credit-provider.primary` all run through `HttpResourceOperator`.
2. **Compose the business flow**: connect descriptors, transforms, decisions,
   subgraphs, and design-only operators under JSON Schema constraints.
3. **Prove and promote it**: validate, simulate with mock or real evidence,
   run real-DAG graph contract suites and operator schema-contract suites, publish reusable graph products, and
   protect them with golden cases.

The showcase covers dashboard aggregation, product enrichment, enriched orders,
credit fallback, loan policy, and SSE search. The full endpoint catalog lives in
[REFERENCE.md](REFERENCE.md).

## Architecture At A Glance

![BLOGE visual canvas architecture](../docs/assets/bloge-visual-canvas-architecture.svg)

| Layer | Responsibility |
| --- | --- |
| Serving | Spring MVC gateway APIs, visual APIs, admin APIs, browser entry points |
| Orchestration | BLOGE DSL graphs schedule dependencies, fan-out, joins, transforms, decisions, and streams |
| Provider | `HttpResourceOperator` resolves descriptors, maps parameters, calls upstreams, validates response protocol, and extracts payload |
| Visual product surface | Catalog import/export, schema checks, draft validation, simulation, publication, golden cases, and run history |
| Controls | Durable run-control state, owner lease/epoch fencing, pre-run evidence reservation, side-effect claim/reconciliation, crash recovery sweeper/outbox, response cache, tenant rate limiting, circuit breaking, and tenant context |

Managed runs reserve `100 ms` by default for terminal-state and evidence finalization. BLOGE propagates the resulting
work budget through `OperatorContext`, scheduler admission, resilience timeout/retry, common HTTP calls, Resource Gateway
resources, and remote-worker envelopes. Override the reserve with
`resource-gateway.run-control.finalization-reserve-ms`; size it from measured evidence-finalization latency rather than
treating it as an operator timeout.

External-write operators declare `bloge.sideEffectProtocol.v1` and call `OperatorContext.beginSideEffect(...)` before
crossing the provider boundary. Missing contracts are DESIGN-only; a managed WRITE that returns without a journal attempt
is rejected. Descriptor-backed POST/PUT/PATCH/DELETE calls additionally require
`resourceGateway.externalWriteContract.v1`, an idempotency key, an evidence-safe lookup reference, and a provider receipt.
The Author palette shows `managed write` or `write protocol required` before a node is used. Provider-specific status
adapters implement `SideEffectReconciler`; Resource Gateway keeps original evidence immutable and appends a separately
signed reconciliation record. See the [product guide](../docs/bloge-visual-canvas-product-and-system-guide.md),
[conformance chain](../docs/assets/resource-gateway-side-effect-conformance-chain.svg), and
[reconciliation lifecycle](../docs/assets/resource-gateway-side-effect-reconciliation-lifecycle.svg).

Enterprise integration credentials can be verified from a live JWKS and versioned revocation feed. Resource Gateway
publishes key and revocation changes as one immutable snapshot, throttles unknown-`kid` refreshes, exposes refresh health
and propagation SLO through `/api/integration/capabilities`, and records organization/delegation facts without storing raw
tokens or group names. Authority outages return retryable 503; deterministically invalid credentials return 401. See the
[dynamic trust lifecycle](../docs/assets/resource-gateway-dynamic-jwks-trust-lifecycle.svg) and
[identity setup guide](../docs/bloge-visual-canvas-product-and-system-guide.md#31-调用-integration-api-前先建立受信身份).

Production evidence signatures can use a private-network KMS/HSM sidecar instead of the demo H2 key store. Resource
Gateway sends only the canonical evidence fingerprint and expected key version, rejects private material in provider
responses, and verifies the returned Ed25519 signature locally before persistence. Key discovery retains `VERIFY_ONLY`
history while distinguishing `DISABLED` and `REVOKED`; malformed trust material fails immediately, and transport outages
can use cached public keys only until the authority-declared expiry. Enable it with
`RG_EVIDENCE_SIGNING_MANAGED_ENABLED=true` and `RG_EVIDENCE_SIGNING_MANAGED_BASE_URI=https://...`. See the
[custody lifecycle](../docs/assets/resource-gateway-managed-evidence-signing-custody.svg) and
[managed signing setup](../docs/bloge-visual-canvas-product-and-system-guide.md#32-为运行证据启用-kmshsm-托管签名).

Tool Studio draft export now reads one relevant-only dependency snapshot containing operator library revision,
runtime binding and activation state, contract-suite revision, schema fingerprints, and a normalized readiness result.
The service checks the draft and dependency fingerprint again after assembly. A concurrent relevant change returns
`409 RG.INTEGRATION.DRAFT_SNAPSHOT_CHANGED` instead of publishing a half-old, half-new bundle; unchanged revisions remain
byte-stable for idempotent consumers. Capability discovery exposes `graphDraftConsistentDependencySnapshot` and
`graphDraftStructuredDependencyRefs`. Scope-mismatched operators export only the draft-owned historical snapshot, never
the current restricted schema, owner, binding, activation, or suite. See the
[snapshot protocol](../docs/assets/resource-gateway-graph-draft-consistent-dependency-snapshot.svg),
[profile v2 schema](../docs/schemas/tool-studio-resource-gateway/graph-draft-dependency-profile-v2.schema.json), and
[product usage guide](../docs/bloge-visual-canvas-product-and-system-guide.md#33-导出可系统化导入的-graphdraft-一致依赖快照).

Replay payloads now have a lifecycle independent from immutable run evidence. New run records contain only shape facts,
a versioned policy descriptor, payload reference, and digest; sanitized values live in an expirable vault. Reads require
tenant/environment scope, purpose, classification clearance, and every policy-required group. `RESTRICTED` defaults to
no retention, expired or purged payloads return 410, legal hold freezes deletion, and each hold/release/purge transition
extends a signed hash chain. See the [lifecycle diagram](../docs/assets/resource-gateway-governed-payload-lifecycle.svg),
[payload replay v2 schema](../docs/schemas/tool-studio-resource-gateway/payload-replay-bundle-v2.schema.json), and
[usage guide](../docs/bloge-visual-canvas-product-and-system-guide.md#35-用-recorded-replay-重算正确性断言).

ANEKE workbook integration now exports an immutable `CorrectnessWorkbookBundle.v1` from one exact draft/dependency
snapshot. It carries exact operator-suite revisions, stable case/assertion IDs, sanitized table values, and verified run
evidence references. ANEKE remains the workbook and publish-gate authority, but a `PASSED` result must use
`GovernanceGateResult.v2` and include a verifiable decision basis for workbook source, dependency snapshot, suite/evidence
refs, policy version, and every required check. Stale or incomplete bases fail closed; accepted gate results and their
change events commit atomically. See the [evidence loop](../docs/assets/resource-gateway-workbook-gate-evidence-loop.svg),
[workbook schema](../docs/schemas/tool-studio-resource-gateway/correctness-workbook-bundle-v1.schema.json), and
[usage guide](../docs/bloge-visual-canvas-product-and-system-guide.md#351-把-contract-suite-和-run-evidence-交给-aneke-workbook).

## Extend It

To add an external API:

1. Register a `ResourceDescriptor` through bootstrap config or the admin API.
2. Define parameter mapping, response protocol, and payload schema.
3. Use it as a `resource:<resourceId>` visual operator or lower it to
   `httpResource`.
4. Compose it in a `.bloge` graph or on the visual canvas.

When you add a new built-in `.bloge` graph under `src/main/resources/bloge/gateway`,
also add a `GatewayGraphContract` entry. This is not optional: `GatewayGraphService`
fails startup when a loaded graph has no contract, and
`GatewayGraphContractCatalogTest` scans every gateway `.bloge` file to catch
schema drift in CI. Runtime execution validates each context against the graph
`inputSchema`, and public gateway endpoints resolve terminal output through the
contract `outputNodes` before validating it against `outputSchema`;
`/api/gateway/graphs/contracts` exposes both schemas; and
`/api/gateway/examples/scenarios` mirrors the same schemas into showcase
metadata so the browser can show each example's Graph Contract;
`/api/gateway/graphs/contracts/tests/draft` generates editable mock rows from
the graph input schema and resource response schemas before
`/api/gateway/graphs/contracts/tests/run` executes table-driven suites against
the real graph through a fresh, run-scoped test engine with downstream APIs
replaced by deterministic, schema-gated fixture rules. The adapter now delegates
to the shared Execution Data Control Plane kernel: selector preflight rejects
zero-match and ambiguous plans, external effects fail closed, required fixtures
must be consumed, and node/edge/assertion evidence is captured without sharing
application interceptors, caches, quotas, circuit breakers, or durable stores.
Stored suites are available under
`/api/gateway/graphs/contracts/tests/suites`; each suite can carry a coverage
policy so batch runs fail when they lack enough cases, schema validations,
mocked calls, assertions, or required output-node coverage.
The built-in catalog covers all seven example graphs with 14 cases, 28
controlled resource-call observations, and 37 business assertions. Resource
rows use explicit F3 transport fixtures, so request mapping, URL rendering,
descriptor response protocol, and payload extraction stay real. Retry cases
declare `minUses/maxUses`, making credit-provider, wallet, and notification
attempt counts part of the pass/fail result. The testing kernel now recursively
freezes synchronous nested graphs and uses BLOGE run-scoped operator resolution
to control foreach, loop, subgraph, and compensation sites. Node evidence is
addressed by structural invocation site, runtime correlation, site occurrence,
and containing-graph occurrence, with retries retained as attempt facts; edge
evidence carries the same graph coordinates. `enrichOrderList` now certifies a
two-item parallel foreach case with independently controlled shipping and
invoice calls. Streaming/suspendable nested execution remains fail closed for
certification.

For the detailed contract-test design, request format, verification evidence,
and remaining industrialization gaps, see
[Resource Graph Schema Mock Table Testing](../docs/bloge-resource-graph-schema-mock-table-testing.md).
The industrial direction is defined by the
[Execution Data Control Plane and testability evolution plan](../docs/resource-gateway-industrial-testability-evolution-plan.md).
Stage 1 of its
[Execution Data Control Plane v1 blueprint](../docs/resource-gateway-industrial-testability-evolution-plan-1.0.md)
is implemented: it separates schema-only operator checks from executable micro-graph
tests, supplies `REAL/RETURN/THROW/DENY/SPY`, supports F2 protocol-derived and F3
transport-level HTTP fixtures, and records fingerprinted evidence. All seven built-in
graphs now dogfood that adapter under `clean verify`; the Spring integration proof points
descriptors at an unreachable address and still requires every suite to pass, catching any
root resource call that escapes its fixture. The generic public
testing API, persistent test-run store, independent JUnit test kit,
production-profile endpoint isolation, deterministic `DELAY/TIMEOUT`, and
synchronous nested invocation control are now available as Stage 2 increments.
Public operator execution, occurrence-level nested evidence, streaming control,
and physical test-runtime deployment isolation remain in progress and are not
advertised as complete.

Create a provider-specific Java operator only when the provider behavior cannot
be expressed cleanly as a descriptor-backed resource.

To add a user-supplied visual operator library:

1. Open `/author/`.
2. Paste a `bloge.visualOperatorLibrary.v1` JSON or YAML document that follows
   the [operator library schema guide](../docs/bloge-visual-operator-library-schema.md)
   and [machine schema](../docs/schemas/bloge-visual-operator-library.schema.json).
3. Validate and import it.
4. Use `/api/visual/operators/tests/draft` to generate editable operator mock
   rows from each operator's input/config/output schemas, then save or batch-run
   schema-contract checks through `/api/visual/operators/tests/suites`. This
   current mode validates fixture and schema consistency; it does not execute a
   real operator runtime binding. Results expose `mode=SCHEMA_CONTRACT`, and the
   canvas labels the editor `Schema Contract Suite` to make that proof strength explicit.
5. Runtime-backed operators can already be exercised by the internal
   `OperatorMicroGraphRunner`; the public `Run Operator` API/UI adapter arrives in Stage 2.
   `httpResource` can earn `EXECUTABLE_UNIT` only with a transport-boundary fixture, so
   request mapping, URL rendering, response protocol, and payload extraction really execute.
6. Drag operators, wire schemas, simulate, and export.

The `/author/` built-in canvas examples also carry their own graph-level
input/output schemas, and exported drafts include the current `inputSchema` so
the design can be integrated instead of remaining a diagram-only artifact.

## Build And Verify

This is a standalone Maven project. BLOGE artifacts must already exist in the
local Maven repository.

```bash
mvn -f resource-gateway-examples/pom.xml \
    -Dtest=GatewayGraphContractTestServiceTest,ExecutionControlCompilerTest,TestRunServiceTest,ResourceFixtureRuntimeTest,OperatorMicroGraphRunnerTest,GraphArtifactFingerprintTest test
mvn -f resource-gateway-examples/pom.xml clean verify
mvn -f resource-gateway-test-kit/pom.xml clean verify
mvn -f resource-gateway-examples/pom.xml -Pfrontend package
mvn -f resource-gateway-examples/pom.xml spring-boot:run
```

If BLOGE core artifacts are missing, install them from the main BLOGE repo:

```bash
mvn -pl bloge-core,bloge-dsl,bloge-common-operators,bloge-spring,bloge-test \
    -am install -DskipTests -Dspotbugs.skip=true
```

Java 25 preview flags are already configured.

## Know The Boundary

- Runtime visual execution is request-response; streaming and durable operators
  are visible in catalog readiness but blocked from direct request-response runs.
- Remote worker, AI tool, event source, message handler, and webhook operators
  can be modeled; this example does not ship their production runtime plane.
- Multi-user collaboration and the complete enterprise IAM policy lifecycle remain outside this example. Dynamic
  JWKS/revocation, group/clearance/delegation claims, purpose authorization and tenant isolation are implemented; customer
  IdP certification, customer policy-engine conformance, group lifecycle/orphan ownership and emergency-access governance
  still require deployment-specific integration. The built-in payload policy is fail-closed and replaceable, not a
  substitute for the customer's authoritative classification registry.
- Managed evidence signing is implemented as a vendor-neutral KMS/HSM sidecar protocol and provider SPI. Production
  deployments still need a customer-specific provider identity/policy, authoritative key-use audit export, historical
  public-key retention, multi-region disaster recovery, and vendor conformance evidence; the default H2 signer remains
  demo-only.
- The visual core still lives inside `resource-gateway-examples`; it is shaped
  for future extraction, but is not yet a standalone artifact.

For full endpoint catalogs, implementation notes, tests, and historical detail,
use [REFERENCE.md](REFERENCE.md). For the visual canvas product guide, use
[docs/bloge-visual-canvas-product-and-system-guide.md](../docs/bloge-visual-canvas-product-and-system-guide.md).
