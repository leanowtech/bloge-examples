# bloge-examples

BLOGE example projects covering beginner quickstarts, production-style orchestration patterns, long-running flows, integration recipes, and antipattern references.

Different scenarios ship different assets:
- **Java Fluent API version** (`*Example.java`) — strongly-typed record I/O, graph built with `Graph.builder()` (including grouped graph-level contracts/settings where examples need them)
- **External DSL version** (`*DslExample.java` + `.bloge`) — declarative DSL, `Map<String, Object>` operators
- **BPMN → DSL version** (`bpmn/*.bpmn` + `bpmn/BpmnToDslExample.java`) — included where a visual workflow source adds value
- **Integration assets** (`application.yml`, dashboards, Maven profiles) — included for Spring, observability, and plugin-oriented examples

> **Engine choice for durable examples.** Examples that use durable stores
> (`checkpoint`, `signal`, `work-item`) are being migrated to
> `DurableGraphEngine` as the primary entry point. The shared
> `LongRunningRuntimeExampleSupport` helper now constructs a `DurableGraphEngine`
> internally and returns that facade from `runtime.engine()`, while still exposing
> `runtime.coreEngine()` for places that need the compatibility `GraphEngine` view.
> Non-durable examples remain on `GraphEngine` and are unaffected.

---

## Example List

### Beginner quickstarts

These examples are optimized for first-time BLOGE readers who want to understand the execution model before jumping into the larger domain suites.

| Example | Files | What it shows |
|---|---|---|
| Hello World | `beginner/HelloWorldExample.java` \| `beginner/HelloWorldDslExample.java` \| `hello-world.bloge` | A single-node graph, typed inputs/outputs, and the smallest possible DSL-backed execution path |
| Two Node Chain | `beginner/TwoNodeChainExample.java` \| `beginner/TwoNodeChainDslExample.java` \| `two-node-chain.bloge` | The simplest dependency edge: one node produces data and the next node consumes it |
| Error Handling Showcase | `beginner/ErrorHandlingShowcaseExample.java` \| `beginner/ErrorHandlingShowcaseDslExample.java` \| `error-handling-showcase.bloge` | An intentionally failing node, explicit `GraphResult.errors()` inspection, and a focused fallback demonstration |
| Error Boundary Replacement | `beginner/ErrorBoundaryReplacementExample.java` \| `beginner/ErrorBoundaryReplacementDslExample.java` \| `error-boundary-replacement.bloge` | Validates the BPMN Error Boundary replacement pattern (plan §3.2): a `fallback` marker value `{failed: true}` flows downstream and a `branch on` routes to manual-review or normal-success paths. Includes a strict (no-fallback) variant that surfaces `GraphResult.errors()` |
| Expression Features | `beginner/ExpressionFeaturesDslExample.java` \| `expression-features.bloge` | DSL indexing (`items[0]`, `items[-1]`, safe `?[...]`), map lookup, string interpolation, and `when` expressions |

### Integration recipes

These examples show how BLOGE fits into framework and tooling workflows instead of only standalone graph execution.

| Example | Files | What it shows |
|---|---|---|
| Spring Boot starter | `integration/spring/*.java` \| `bloge/integration/spring/spring-ticket-triage.bloge` \| `integration/spring-boot-example/application.yml` | `@BlogeOperator` bean discovery, classpath DSL loading, a demo REST controller, and `/actuator/bloge` diagnostics inside a Spring Boot app. Example operators demonstrate LLM metadata annotation (`promptHint`, `usageExample`, `constraintsDescription`) |
| Modular checkout imports | `ecommerce/ModularCheckoutDslExample.java` \| `bloge/modular/checkout.bloge` \| `bloge/modular/payment-flow.bloge` \| `bloge/modular/inventory-check.bloge` | DSL `import` declarations, classpath import resolution, and invoking imported graphs directly by alias |
| Observability wiring | `integration/observability/ObservabilityExample.java` \| `integration/observability/application.yml` \| `integration/observability/grafana-dashboard.json` | Direct `MetricsExecutionListener` wiring plus Prometheus/OTLP/Grafana assets that mirror the same signals in a deployed app |
| Maven plugin profile | `integration/maven/MavenPluginExample.java` \| `bloge/integration/plugin/metadata-catalog.bloge` \| `plugin-example.blogerc.json` | The `bloge-plugin-example` Maven profile for `export-metadata` (now outputs version 1.1.0 with LLM metadata fields), `validate` (compile-level operator resolution), and `lint`, scoped to a clean DSL catalog |

### Cross-extension composition

These examples show the new composition path between the `session` and `state_machine` extensions.

| Example | Files | What it shows |
|---|---|---|
| Session phase with nested state machine | `ecommerce/OrderSessionWithStateMachineExample.java` \| `ecommerce/OrderSessionWithStateMachineDslExample.java` \| `bloge/ecommerce/order-session-with-state-machine.bloge` | A `session` phase that delegates to a nested `state_machine`, forwards `SessionHandle.signal(...)` payloads into local and global transitions, and resumes the outer workflow once the nested machine reaches a terminal state |
| State machine state with nested session | `approval/ReviewStateMachineWithSessionExample.java` \| `approval/ReviewStateMachineWithSessionDslExample.java` \| `bloge/approval/review-state-machine-with-session.bloge` | A `state_machine` state that runs a nested `session` to completion and uses the nested session outputs to drive guarded auto-transitions |

### Evolution plan validation examples

These examples validate the BPMN replacement patterns described in the [evolution plan](../docs/implements-plan/bloge-evolution-to-ai-native-graph-engine-plan.md) §3.2, §4.2, and the validation matrix §8.

| Example | Files | What it shows |
|---|---|---|
| Error Boundary Replacement (§3.2) | `beginner/ErrorBoundaryReplacementExample.java` \| `beginner/ErrorBoundaryReplacementDslExample.java` \| `error-boundary-replacement.bloge` | Proves that `fallback` marker values flow downstream and `branch on` routes correctly — no Error Boundary primitive needed. Three variants: strict failure path, fallback→manual-review, fallback→normal-success |
| Interruptible Scope Replacement (§4.2) | `ecommerce/OrderCancellationSessionExample.java` \| `ecommerce/OrderCancellationSessionDslExample.java` \| `bloge/ecommerce/order-cancellation-session.bloge` | Proves that session signals route at phase boundaries rather than interrupting mid-node. A "cancel" signal redirects to a `cancelled` phase where refund and notification nodes execute to completion |
| Timeout Escalation (§4.2) | `ecommerce/TimeoutEscalationStateMachineExample.java` \| `ecommerce/TimeoutEscalationStateMachineDslExample.java` \| `bloge/ecommerce/timeout-escalation-state-machine.bloge` | Proves that per-state `timeout` + `on_timeout` transitions replace BPMN Timer Boundary Events. The `pendingApproval` state auto-transitions to `escalated` when its timeout expires |

### Decision table examples

These examples demonstrate BLOGE 0.8.3-RC3 decision tables for auditable business rule matrices.

| Example | Files | What it shows |
|---|---|---|
| Credit Tier Decision | `finance/CreditTierDecisionExample.java` \| `finance/CreditTierDecisionDslExample.java` \| `credit-tier-decision.bloge` | `hit=first`, chained credit-score ranges, and an `otherwise` fallback for underwriting tiers |
| Loan Terms Decision | `finance/LoanTermsDecisionExample.java` \| `finance/LoanTermsDecisionDslExample.java` \| `loan-terms-decision.bloge` | `hit=unique`, multi-input rule conditions, structured outputs, and stable ambiguous-match violations |
| Insurance Premium Decision | `insurance/InsurancePremiumDecisionExample.java` \| `insurance/InsurancePremiumDecisionDslExample.java` \| `insurance-premium-decision.bloge` | A two-input premium-pricing matrix with structured premium/tier outputs |
| Applicable Discounts Decision | `ecommerce/ApplicableDiscountsDecisionExample.java` \| `ecommerce/ApplicableDiscountsDecisionDslExample.java` \| `bloge/ecommerce/applicable-discounts-decision.bloge` | `hit=collect`, empty collection results without `otherwise`, and multi-discount accumulation |
| Customer Tier Decision | `customerservice/CustomerTierDecisionExample.java` \| `customerservice/CustomerTierDecisionDslExample.java` \| `customer-tier-decision.bloge` | Static and dynamic `in` membership checks plus stable invalid-collection violation handling |

### Schema evolution examples

These examples demonstrate BLOGE 0.8.3-RC3 publish-time schema compatibility checks.

| Example | Files | What it shows |
|---|---|---|
| Customer Profile Schema Evolution | `schema/CustomerProfileSchemaEvolutionExample.java` | `VersionedSchema`, optional-field additions, deprecated-field warnings, reserved-name violations, and a small release-gate decision model |

### 1. Basic DAG (Parallel + Branching + Fault Tolerance)

The following 8 examples demonstrate the core capabilities of DAGs: parallel calls, `depends_on` aggregation, `branch` conditional branching, `transform` field projection, and `timeout` / `retry` / `fallback` fault tolerance.

| # | Domain | Example | Core capabilities |
|---|------|------|----------|
| 1.1 | E-commerce | `ecommerce/OrderProcessing` | Parallel fetch → aggregation → branch(createOrder/rejectOrder) |
| 1.2 | Infrastructure | `bff/BffAggregation` | 5-way parallelism + independent timeout/fallback per branch |
| 1.3 | Catering | `catering/FoodOrder` | Parallel inventory + kitchen + delivery checks → branch(accept/suggest) |
| 1.4 | Customer service | `customerservice/TicketRouting` | Parallel fetch → sentiment → branch(vip/normal/auto) |
| 1.5 | Finance | `finance/LoanApproval` | 4-way parallel risk checks → transform riskSummary → branch(approved/rejected/manual) |
| 1.6 | Healthcare | `healthcare/OnlineTriage` | AI pre-diagnosis → branch(emergency/specialist/general) |
| 1.7 | Insurance | `insurance/ClaimProcessing` | 3-way parallelism + assessRisk → transform claimContext → branch(approved/rejected/investigate) |
| 1.8 | Logistics | `logistics/ShipmentPlanning` | 3-way parallel (warehouse/carrier/route) → calculateCost → branch(express/standard/consolidated) |

#### 1.1 E-commerce Order Processing

Demonstrates complete DAG orchestration capabilities: parallel calls, dependency aggregation, conditional branching, and fault-tolerant degradation.

**Files**: `ecommerce/OrderProcessingExample.java` | `OrderProcessingDslExample.java` | `order-process.bloge`

```
fetchUser ──────┬──→ calcPrice ──→ checkCredit ──┬──→ createOrder
                │         ↑                       │
fetchProducts ──┘─────────┘                       └──→ rejectOrder
```

- `fetchUser` + `fetchProducts` run in parallel, with `calcPrice` aggregating both
- `checkCredit` is configured with retry (3 jittered attempts) + fallback (degrades when the credit service is unavailable)
- `transform orderSummary`: zero-cost field projection with no operator scheduling
- `branch on checkCredit.output.approved` conditional branch

#### 1.1b E-commerce Order Saga (Compensation)

Demonstrates declarative compensation / saga execution. Each side-effecting node declares a rollback operator via `compensate`. The optional graph-level `saga { ... }` block configures compensation ordering (`mode`), failure policy (`on_failure`), and the default compensation retry budget (`max_compensation_retries`). Individual compensation blocks may also override retry with `retry = { ... }`.

- `OrderSagaExample.java` — Java fluent API saga with `NodeBuilder.compensate(...)` on each payment step
- `OrderSagaDslExample.java` — DSL-driven version of the same saga
- `order-saga.bloge` — the `.bloge` resource used by the DSL example

The Java fluent version also shows the grouped `Graph.GraphExecutionSettings` API for graph-level saga policy.

Key features demonstrated:
- `compensate : <Operator> { input { ... } }` — per-node compensation bindings
- `saga { mode = backward, on_failure = compensate }` — graph-level saga policy
- `retry = { ... }` inside compensation blocks — per-step retry override
- `GraphResult.compensationResults()` — inspecting compensation outcomes

#### 1.2 Multi-source Aggregation BFF

Demonstrates a BFF (Backend For Frontend) scenario that maximizes parallelism.

**Files**: `bff/BffAggregationExample.java` | `bff/BffAggregationDslExample.java` | `bff-dashboard.bloge`

```
fetchProfile ────────┐
fetchOrders ─────────┤
fetchRecommendations ┼──→ aggregate
fetchNotifications ──┤
fetchLoyalty ────────┘
```

| Node | Timeout | Retry | Fallback |
|------|------|------|------|
| fetchProfile | 2s | 1 retry | None (must succeed) |
| fetchOrders | 3s | — | Empty list |
| fetchRecommendations | 2s | — | Empty list |
| fetchNotifications | 2s | 2 retries EXPONENTIAL | Empty notifications |
| fetchLoyalty | 1s | — | Default value |

#### 1.3 Catering Delivery Order Processing

**Files**: `catering/FoodOrderExample.java` | `FoodOrderDslExample.java` | `food-order.bloge`

```
validateOrder → [checkInventory ∥ checkKitchenLoad ∥ estimateDelivery] → decideAcceptance
                                                                           ├──→ acceptOrder → [notifyKitchen ∥ assignRider ∥ processPayment]
                                                                           └──→ suggestAlternatives
```

- 3-way parallel pre-checks (inventory, kitchen load, and delivery-time estimation)
- `assignRider` is configured with fallback (pickup as the fallback option)

#### 1.4 Intelligent Ticket Routing

**Files**: `customerservice/TicketRoutingExample.java` | `TicketRoutingDslExample.java` | `ticket-routing.bloge`

```
[fetchCustomer ∥ fetchTicketHistory] → analyzeSentiment → classifyPriority
                                                               ├──→ assignVipAgent
                                                               ├──→ assignNormalAgent
                                                               └──→ autoResolve
```

- `analyzeSentiment` is configured with retry (1 retry) + fallback (neutral sentiment)
- 3-way branch routing

#### 1.5 Finance Loan Approval

**Files**: `finance/LoanApprovalExample.java` | `LoanApprovalDslExample.java` | `loan-approval.bloge`

```
fetchApplication → [checkCredit ∥ detectFraud ∥ verifyIncome ∥ checkBlacklist]
                            └──────────────────────────────────┘
                                          ↓
                                    aggregateRisk
                                          ↓
                                  transform riskSummary
                                          ↓
                                    makeDecision ──→ approveLoan / rejectLoan / manualReview
```

- 4-way parallel risk checks, each configured with retry/fallback
- `transform riskSummary`: aggregates 5 risk signals for audit logging
- 3-way branch decision

#### 1.6 Healthcare Online Triage

**Files**: `healthcare/OnlineTriageExample.java` | `OnlineTriageDslExample.java` | `online-triage.bloge`

```
[fetchPatientRecord ∥ fetchMedicalHistory] → analyzeSymptoms → aiPreDiagnosis → triageDecision
                                                                                    ├──→ routeEmergency
                                                                                    ├──→ routeSpecialist
                                                                                    └──→ routeGeneral
```

- `aiPreDiagnosis` is configured with retry (2 times EXPONENTIAL) + timeout 10s
- `branch on triageDecision.output.triageLevel` three-way routing

#### 1.7 Insurance Claim Processing

**Files**: `insurance/ClaimProcessingExample.java` | `ClaimProcessingDslExample.java` | `claim-processing.bloge`

```
fetchClaim → [validatePolicy ∥ reviewDocuments ∥ checkClaimHistory] → assessRisk
                                                                            ↓
                                                               transform claimContext
                                                                            ↓
                                                                    claimDecision
                                                                    ├──→ approveClaim → schedulePayout
                                                                    ├──→ rejectClaim
                                                                    └──→ investigateClaim
```

- `transform claimContext`: projects policyType, coverageLimit, docsComplete, priorClaims, and riskLevel
- `reviewDocuments` is configured with retry (2 times EXPONENTIAL)

#### 1.8 Shipment Planning

**Files**: `logistics/ShipmentPlanningExample.java` | `ShipmentPlanningDslExample.java` | `shipment-planning.bloge`

```
fetchOrder → [lookupWarehouse ∥ selectCarrier ∥ optimizeRoute] → calculateCost → decideShipMode
                                                                                    ├──→ dispatchExpress
                                                                                    ├──→ dispatchStandard
                                                                                    └──→ dispatchConsolidated
```

- `selectCarrier` is configured with retry + fallback (standard courier as fallback)
- 3-way branch shipping mode

---

### 2. Sub-Graph Multi-Domain Complex Examples

The following 5 examples demonstrate the core capabilities of sub-graphs: nested execution, parallel sub-graphs (via DAG-level fan-out or the dedicated `parallel` block), branching into sub-graphs, and sub-graph output aggregation.

Each example includes a **Java API version** (strongly-typed record I/O + `SubGraphOperator`), a **DSL version** (`DslCompiler.registerSubGraph` + `subgraph("name")` syntax), and a `.bloge` file.

#### 2.1 E-commerce — End-to-End Order Pipeline ⭐ Sub-Graph

**Files**: `ecommerce/OrderFullPipelineExample.java` | `OrderFullPipelineDslExample.java` | `order-full-pipeline.bloge`

```
validateOrder → [payment-processing ∥ inventory-fulfillment] → confirmOrder → notifyCustomer
```

- Sub-graph A `payment-processing` (4 nodes): fraudDetection → paymentGateway → paymentConfirmation → receiptGeneration
- Sub-graph B `inventory-fulfillment` (3 nodes): inventoryCheck → warehouseAllocation → shipmentCreation
- **Demonstrates:** parallel sub-graph execution, sub-graph outputs aggregated into the parent graph, and retry/timeout inside sub-graphs

#### 2.1b E-commerce — Modular Checkout Imports ⭐ DSL Import

**Files**: `ecommerce/ModularCheckoutDslExample.java` | `modular/checkout.bloge` | `modular/payment-flow.bloge` | `modular/inventory-check.bloge`

```
checkout.bloge imports payment-flow.bloge + inventory-check.bloge
loadCart → payment(paymentFlow) + inventory(inventoryCheck) → assembleCheckout
```

- `import "./payment-flow" as paymentFlow` resolves a sibling classpath DSL resource
- `node payment : paymentFlow` invokes the imported graph directly by alias
- **Demonstrates:** reusable DSL-only graph modules without pre-registering Java-built sub-graphs

#### 2.2 Finance — End-to-End Loan Approval ⭐ Sub-Graph

**Files**: `finance/LoanApprovalSubGraphExample.java` | `LoanApprovalSubGraphDslExample.java` | `loan-approval-subgraph.bloge`

```
receiveApplication → [credit-assessment ∥ compliance-check] → underwritingDecision → branch(approved/rejected)
```

- Sub-graph A `credit-assessment` (4 nodes): creditQuery → incomeVerification → debtRatioCalc → riskScoring
- Sub-graph B `compliance-check` (4 nodes): amlScreening → kycVerification → sanctionListCheck → complianceDetermination
- **Demonstrates:** parallel sub-graphs + branch decisions that depend on sub-graph outputs, with fallback on internal sub-graph nodes

#### 2.3 Customer service — Intelligent Ticket Handling ⭐ Sub-Graph

**Files**: `customerservice/SmartTicketHandlingExample.java` | `SmartTicketHandlingDslExample.java` | `smart-ticket-handling.bloge`

```
receiveTicket → classifyIntent → sentiment-analysis → determinePriority → branch(high → escalation-workflow | otherwise → generateReply)
```

- Sub-graph A `sentiment-analysis` (4 nodes): textPreprocessing → nlpClassification → sentimentScoring → priorityAssignment
- Sub-graph B `escalation-workflow` (4 nodes): supervisorNotification → slaCheck → escalationRouting → customerCallbackSchedule
- **Demonstrates:** sequential sub-graphs, entering a sub-graph after a branch, and using sub-graph outputs to drive subsequent branches

#### 2.4 Logistics — International Shipment Flow ⭐ Sub-Graph

**Files**: `logistics/InternationalShipmentExample.java` | `InternationalShipmentDslExample.java` | `international-shipment.bloge`

```
receiveRequest → validateAddress → [customs-clearance ∥ route-optimization] → bookingConfirmation → trackingSetup → sendNotification
```

- Sub-graph A `customs-clearance` (5 nodes + branch): documentPreparation → hsCodeClassification → dutyCalculation → customsDeclaration → branch(approved/rejected)
- Sub-graph B `route-optimization` (4 nodes): carrierQuery → rateComparison → transitTimeEstimation → optimalRouteSelection
- **Demonstrates:** one parallel sub-graph with 5 nodes to show complex nesting, plus branching inside a sub-graph

#### 2.5 Catering — End-to-End Restaurant Order ⭐ Sub-Graph

**Files**: `catering/RestaurantOrderPipelineExample.java` | `RestaurantOrderPipelineDslExample.java` | `restaurant-order-pipeline.bloge`

```
receiveOrder → paymentVerification → kitchen-dispatch → qualityCheck → branch(dineIn/delivery → delivery-coordination) → orderComplete
```

- Sub-graph A `kitchen-dispatch` (5 nodes): dishValidation → ingredientCheck → stationAssignment → cookingTimeEstimate → queueForPickup
- Sub-graph B `delivery-coordination` (4 nodes): riderMatching → routeCalculation → etaEstimation → realtimeTrackingSetup
- **Demonstrates:** sequential sub-graphs + entering a sub-graph after a branch, with retry inside the sub-graph (rider matching may fail and require retry)


### 3. Iteration Examples (foreach & loop)

The following 6 examples demonstrate the iterative constructs `foreach` (parallel/sequential traversal) and `loop` (polling/pagination/retry/composite).

Each example includes a **Java API version** (strongly-typed record I/O + `ForEachOperator`/`LoopOperator`), a **DSL version** (`Map<String, Object>` operators + `GraphLoader.load()` syntax), and a `.bloge` file.

All examples use the shared `common/LoggingListener.java` as the execution listener.

#### 3.1 Parallel Batch Order Processing — ForEach (Parallel)

**Files**: `iteration/BatchOrderParallelExample.java` | `BatchOrderParallelDslExample.java` | `batch-order-parallel.bloge`

```
fetchOrders → foreach processOrders (parallel) { validate → deductStock } → summarize
```

- `foreach processOrders`: traverses the order list in parallel, with each item passing through the `validate → deductStock` sub-graph
- **Demonstrates:** implicit variables `item`, `item.field`, and `itemIndex`, parallel foreach, `max_concurrency`, and downstream nodes consuming foreach output

#### 3.1b Batch Order Processing with Item Failure Tolerance — ForEach (Parallel)

**Files**: `iteration/BatchOrderWithFailureExample.java` | `BatchOrderWithFailureDslExample.java` | `batch-order-with-failure.bloge`

```
loadOrders + loadConfig → foreach processOrders { process } → summarize
```

- `batch_size = max(loadConfig.output.batchSize, 1)` evaluates batch sizing from graph data
- `on_item_failure = continue` records a per-item `__error__` placeholder while later items continue

#### 3.2 Sequential Transfer Processing — ForEach (Sequential)

**Files**: `iteration/SequentialTransferExample.java` | `SequentialTransferDslExample.java` | `sequential-transfer.bloge`

```
loadAccounts → foreach processTransfers sequential { validateTransfer → executeTransfer → recordTransaction } → generateReport
```

- `foreach processTransfers sequential`: traverses the transfer list sequentially to guarantee execution order
- **Demonstrates:** the `sequential` keyword, a 3-node sub-graph chain, and sequential foreach output aggregation

#### 3.3 Status Polling — Loop (Polling)

**Files**: `iteration/StatusPollingExample.java` | `StatusPollingDslExample.java` | `status-polling.bloge`

```
submitJob → loop pollStatus (max: 20, delay: 2s) { checkStatus } until status == "READY" → fetchResult
```

- `loop pollStatus`: polls asynchronous task status every 2 seconds, up to 20 times
- **Demonstrates:** the implicit variable `loopIteration`, the `until` termination condition, and passing loop output downstream

#### 3.4 Cursor Pagination — Loop (Pagination + carry)

**Files**: `iteration/CursorPaginationExample.java` | `CursorPaginationDslExample.java` | `cursor-pagination.bloge`

```
initPagination → loop fetchAllPages (max: 100, delay: 500ms) { fetchPage → transformPage } carry { cursor, totalRecords } until !hasMore → finalizeData
```

- `loop fetchAllPages`: uses `carry` to pass the cursor and accumulated record count between iterations
- **Demonstrates:** multi-field `carry { cursor, totalRecords }`, `carry.cursor` / `carry.totalRecords` references, and multi-node sub-graphs

#### 3.5 Exponential Backoff Retry — Loop (Retry + carry)

**Files**: `iteration/RetryWithBackoffExample.java` | `RetryWithBackoffDslExample.java` | `retry-with-backoff.bloge`

```
prepareRequest → loop retryCall (max: 5) { computeBackoff → callService } carry { lastError } until success == true → processResponse
```

- `loop retryCall`: a loop-based retry mechanism that supports custom backoff logic and state tracking
- **Demonstrates:** `loopIteration` driving exponential backoff calculation, `carry.lastError` tracking the last error, and no delay (controlled by the operator itself)

#### 3.6 Logistics Batch Dispatch — Composite (ForEach + Loop)

**Files**: `iteration/LogisticsBatchDispatchExample.java` | `LogisticsBatchDispatchDslExample.java` | `logistics-batch-dispatch.bloge`

```
fetchParcels → foreach assignRoutes { planRoute → dispatchParcel } → loop pollAllDispatched { checkAllStatus } until allDelivered → dispatchReport
```

- Combines `foreach` (parallel parcel routing) + `loop` (polling delivery status) in the same graph, showing foreach→loop data flow
- **Demonstrates:** foreach output flowing into the loop via `depends_on`, and using both iterative constructs within a single graph

---

### 4. Streaming Examples (stream node / stream foreach / stream loop)

The following 3 DSL examples demonstrate the constructs `stream node` (streaming operators), `stream foreach` (streaming traversal), and `stream loop` (streaming polling). Each stream edge (`StreamEdge`) forwards items immediately when an operator emits them, without waiting for the whole batch to finish; `buffer = N` controls `NodeChannel` backpressure.

These are pure DSL files (no standalone Java example classes); the Java versions in the Voice Agent examples also fully demonstrate streaming operators.

#### 4.1 LLM Token Streaming Pipeline

**Files**: `llm-streaming.bloge`

```
buildPrompt → stream llmCall → stream tokenFilter(buf=32) → assembleResponse
```

- `stream llmCall`: emits LLM API tokens one by one and forwards them downstream in real time through `StreamEdge`
- `stream tokenFilter`: buffer size 32, filters control tokens while retaining semantic tokens
- `assembleResponse`: a regular node where `DirectEdge` consumes the complete token list
- **Demonstrates:** `stream node`, `.stream input` (`StreamEdge`), `.output input` (`DirectEdge`), and `buffer = N`

#### 4.2 Streaming Batch Order Processing

**Files**: `streaming-batch.bloge`

```
loadOrders → stream foreach processOrder { validateItem → calculateDiscount } → generateReport
```

- `stream foreach processOrder`: processes each order in parallel and streams each item downstream immediately after completion
- `generateReport`: `DirectEdge` consumes the complete result list
- **Demonstrates:** `stream foreach`, `buffer = N`, and the distinction between stream edges and direct edges

#### 4.3 Streaming Status Monitoring

**Files**: `streaming-status-monitor.bloge`

```
initMonitor → stream loop checkStatus(max=100, delay=2s, buf=8) { pollStatus } until status=="ready" → sendNotification
```

- `stream loop checkStatus`: each iteration result is emitted through a stream edge in real time without waiting for the whole loop to complete
- `until pollStatus.output.status == "ready"` triggers an early exit
- **Demonstrates:** `stream loop`, the implicit variable `loopIteration`, and early exit with `until`

---

### 5. Voice Agent AI Voice Orchestration

The following 8 examples demonstrate AI voice orchestration scenarios that combine capabilities such as `stream node` (real-time audio processing), `SubGraphOperator` (business logic reuse), `LoopOperator` (feedback loops), and `branch` (intent routing).

Each example includes both a **Java Fluent API version** and a **DSL version** (the `.bloge` file).

| # | Domain | Example | Core capabilities |
|---|------|------|----------|
| 5.1 | General | `voice/VoicePipeline` | stream source → stream transform → regular node: minimal streaming voice pipeline |
| 5.2 | General | `voice/MultimodalInteraction` | parallel stream video + stream audio → stream merge → save recording |
| 5.3 | Customer service | `customerservice/VoiceContactCenter` | stream STT + sub-graph complaint escalation + 4-way branch routing |
| 5.4 | Finance | `finance/VoiceBanking` | voiceprintAuth(retry) → stream STT → intentDetection → branch(transfer/balance/agent) |
| 5.5 | Healthcare | `healthcare/VoiceTelemedicine` | stream STT → sub-graph medical-intake → 3-way branch(emergency/specialist/GP) |
| 5.6 | Logistics | `logistics/VoiceLogisticsDispatch` | stream STT → parseRequest → branch(routeQuery/anomalyReport) → stream TTS |
| 5.7 | Education | `education/VoiceTutoring` | stream STT → pronunciationScoring → loop feedbackLoop(carry, until ≥ 0.8) → report |
| 5.8 | Emergency | `emergency/VoiceEmergencyDispatch` | parallel stream STT + location + stream realtimeTranslation → classifyEmergency → branch dispatch |

#### 5.1 Basic Voice Processing Pipeline

**Files**: `voice/VoicePipelineExample.java` | `VoicePipelineDslExample.java` | `voice-pipeline.bloge`

```
stream audioCapture → stream speechToText → textAnalysis
```

- Demonstrates the minimal runnable streaming voice pipeline: source stream → transform stream → regular node
- **Demonstrates:** `StreamingOperator`, `NodeChannel`, and stream nodes materialized as `List<T>` for downstream consumption

#### 5.2 Multimodal Interaction Recording

**Files**: `voice/MultimodalInteractionExample.java` | `MultimodalInteractionDslExample.java` | `multimodal-interaction.bloge`

```
stream videoStream ─┐
                    ├→ stream mergeStreams → saveRecording
stream audioStream ─┘
```

- Parallel audio + video stream merging, demonstrating aggregation from multiple stream sources
- **Demonstrates:** parallel multi-stream sources + stream merge node + final `DirectEdge` materialization

#### 5.3 Intelligent Voice Contact Center

**Files**: `customerservice/VoiceContactCenterExample.java` | `VoiceContactCenterDslExample.java` | `voice-contact-center.bloge`

```
stream audioCapture → stream speechToText → intentClassification
                                                 ├── routeToBilling
                                                 ├── routeToTechSupport
                                                 ├── sentimentMonitoring (sub-graph: complaint-escalation)
                                                 └── routeToGeneral
                                                        └─→ callSummary → saveCallRecord
Sub-graph "complaint-escalation": sentimentAnalysis → escalationDecision → supervisorNotification
```

- Streaming audio transcription + intent classification + 4-way branch routing, with the complaint branch entering an escalation sub-graph
- **Demonstrates:** stream + sub-graph + multi-way branch

#### 5.4 Voice Banking

**Files**: `finance/VoiceBankingExample.java` | `VoiceBankingDslExample.java` | `voice-banking.bloge`

```
stream audioCapture → voiceprintAuth(timeout=10s, retry=2/1s/FIXED)
                    → stream speechToText → intentDetection
                         → txnContext → branch(transfer/balance/agent)
intentDetection → complianceRecording(fallback)
```

- Voiceprint authentication (retry + timeout) → streaming transcription → intent routing
- **Demonstrates:** stream + regular-node retry/timeout + branch + fallback

#### 5.5 Voice Telemedicine

**Files**: `healthcare/VoiceTelemedicineExample.java` | `VoiceTelemedicineDslExample.java` | `voice-telemedicine.bloge`

```
stream audioCapture → stream speechToText → verifyPatientIdentity
                    → medicalIntake (sub-graph: symptomExtraction → terminologyNormalization → urgencyAssessment)
                         ├── routeEmergencyDoctor
                         ├── routeSpecialist
                         └── routeGeneralPractitioner
                    → startConsultationRecording (always)
```

- Streaming transcription + sub-graph medical summary + 3-way branch triage
- **Demonstrates:** stream + sub-graph medical-intake + multi-way branch

#### 5.6 Voice Logistics Dispatch

**Files**: `logistics/VoiceLogisticsDispatchExample.java` | `VoiceLogisticsDispatchDslExample.java` | `voice-logistics-dispatch.bloge`

```
stream audioCapture → stream speechToText → parseDriverRequest
                    → branch(routeQuery/anomalyReport → logAnomaly → notifyDispatcher/acknowledgement)
                    → stream textToSpeech → sendVoiceResponse
```

- Driver voice request → intent routing → TTS voice response
- **Demonstrates:** stream input + stream output (TTS) + branch

#### 5.7 Voice Pronunciation Coaching

**Files**: `education/VoiceTutoringExample.java` | `VoiceTutoringDslExample.java` | `voice-tutoring.bloge`

```
stream audioCapture → stream speechToText → pronunciationScoring
                    → loop feedbackLoop(max=3, until suggestedScore >= 0.8)
                         └─ generateFeedback → textToSpeech
                    → generateReport
```

- Streaming transcription → pronunciation scoring → feedback loop (loop + carry, until the score passes the threshold) → report
- **Demonstrates:** stream + `LoopOperator` carry state + early exit with `until`

#### 5.8 Voice Emergency Dispatch

**Files**: `emergency/VoiceEmergencyDispatchExample.java` | `VoiceEmergencyDispatchDslExample.java` | `voice-emergency-dispatch.bloge`

```
stream audioCapture ─┬─ stream speechToText
                     │       ├─ stream realtimeTranslation(buf=16)
                     │       └─────────────────────────────────────┐
                     └─ detectCallerLocation                        │
                                                              classifyEmergency
                                                              branch(fire/medical/otherwise)
                                                              → dispatchFireDept / dispatchAmbulance / dispatchPolice
classifyEmergency + realtimeTranslation → logDispatchRecord
```

- Parallel audio transcription + real-time translation + location detection → emergency type classification → dispatch units
- **Demonstrates:** multi-stream parallelism + stream buffer + branch + aggregation

---

### 6. Antipattern Reference (Antipatterns)

Demonstrates common DAG orchestration antipatterns and their correct forms, helping developers identify and avoid common mistakes.

| Example | Files | What to look for |
|---|---|---|
| Excessive node splitting | `antipatterns/AntipatternExamples.java` \| `antipatterns/excessive-nodes.bloge` | Replace pure field-mapping nodes with a `transform` block |
| God graph | `antipatterns/GodGraphExample.java` \| `antipatterns/god-graph.bloge` | Split a giant workflow into focused sub-graphs and consume terminal outputs only |
| Over-broad fallback | `antipatterns/OverBroadFallbackExample.java` \| `antipatterns/over-broad-fallback.bloge` | Limit fallbacks to retryable infrastructure failures instead of swallowing every exception |
| Missing timeout | `antipatterns/MissingTimeoutExample.java` \| `antipatterns/missing-timeout.bloge` | Put explicit timeouts around slow nodes so one blocked call does not stall the graph |
| Circular dependency | `antipatterns/CircularDependencyExample.java` \| `antipatterns/circular-dependency.bloge` | Keep the graph acyclic and break feedback loops into later stages or signals |

#### 6.1 Excessive Node Splitting → Use transform

**Files**: `antipatterns/excessive-nodes.bloge`

```
❌ fetchCustomer → extractName → formatAddress → combineLabel → sendNotification
✅ fetchCustomer → transform customerLabel { fullName = ...; address = ...; label = ... } → sendNotification
```

- Three operator nodes used only for field mapping (`extractName` / `formatAddress` / `combineLabel`) introduce unnecessary scheduling overhead
- **Correct approach:** replace them with a single `transform` block at zero scheduling cost

#### 6.2 God Graph → Split into sub-graphs

**Files**: `antipatterns/GodGraphExample.java` | `antipatterns/god-graph.bloge`

- Shows a deliberately over-coupled case-management workflow next to a composed variant that breaks the flow into reusable sub-graphs
- Documents the `SubGraphOperator` rule that only terminal node outputs are exposed back to the parent graph

#### 6.3 Over-broad fallback → Target retryable failures

**Files**: `antipatterns/OverBroadFallbackExample.java` | `antipatterns/over-broad-fallback.bloge`

- Contrasts a fallback that hides validation bugs with a safer variant that only handles infrastructure-style retries

#### 6.4 Missing timeout → Bound slow calls

**Files**: `antipatterns/MissingTimeoutExample.java` | `antipatterns/missing-timeout.bloge`

- Demonstrates how an otherwise harmless slow operator can monopolize the graph until a timeout and fallback boundary are added

#### 6.5 Circular dependency → Keep graphs acyclic

**Files**: `antipatterns/CircularDependencyExample.java` | `antipatterns/circular-dependency.bloge`

- Provides a compact cycle example that should be rejected during graph construction and a neighboring fixed shape that stays acyclic

---

### 7. Long-running Flows (Long-Running: wait / await / signal)

The long-running examples demonstrate the complete lifecycle of **suspend → signal → resume**:

| Example | Domain | Suspend mechanism | Key features |
|------|------|----------|----------|
| `TicketApprovalLongRunningExample` | Customer service | Timer auto-close | `InMemoryTimerService`; 2-second timeout |
| `TicketApprovalLongRunningDslExample` | Customer service (DSL) | Same as above | Loads the graph from an inline DSL string |
| `PaymentWaitExample` | E-commerce | Event correlation (single event) | `publishEvent` / `EventCorrelation`; OR mode |
| `PaymentWaitDslExample` | E-commerce (DSL) | Same as above | Loaded via `GraphLoader` |
| `LoanDisbursementWaitExample` | Finance | Dual-event AND wait | Resumes only when both events arrive: `document.signed` + `payment.cleared` |
| `LoanDisbursementWaitDslExample` | Finance (DSL) | Same as above | |
| `CargoReadinessWaitExample` | Logistics | OR mode with multiple trucks | Triggered as soon as the first truck arrives |
| `CargoReadinessWaitDslExample` | Logistics (DSL) | Same as above | |
| `PatientConsentWaitExample` | Healthcare | Signature deadline | 2-second timer simulates a 48 h deadline; two execution scenarios |
| `PatientConsentWaitDslExample` | Healthcare (DSL) | Same as above | |
| `ScheduledWaitExample` | Reporting | Cron + deadline timers | Sequential scheduled waits: business-window cron gate followed by archival deadline |
| `ScheduledWaitDslExample` | Reporting (DSL) | Same as above | Loads the shared `scheduled-wait.bloge` resource |
| `RemoteWorkerExecutionDslExample` | Reporting (DSL) | Remote work item | `execution_mode = remote`, `worker_topic`, durable `RemoteWorkerEnvelope`, poll/claim worker handoff |

**Long-running lifecycle**:

```
1. engine.executeWithOperators(graph, ctx, ops)
   -> node returns `OperatorResult.suspend(...)` -> `execution.isSuspended() = true`

2. external signal / timer / event arrives

3. checkpointStore.saveNodeCheckpoint(new NodeCheckpoint(execId, graphName, nodeId, json, now))
   -> serialize the signal payload to JSON and persist the suspended node as a completed checkpoint

4. engine.resume(graph, execId, ctx)
   -> load the checkpoint, skip completed nodes, and continue executing downstream nodes
```

The helper behind these examples now owns a `DurableGraphEngine`, so `runtime.engine()`
returns the durable facade directly. Existing example code continues to call familiar
methods such as `executeWithOperators()`, `publishEvent()`, and `resume()` on that facade,
while new durable-specific scenarios can also use facade-only operations such as work-item
dispatch without rebuilding the helper setup.

#### 7.1 Remote Worker Execution — Durable Work Item Dispatch

**Files**: `durable/RemoteWorkerExecutionDslExample.java` | `remote/remote-report-rendering.bloge`

```
prepareReport → renderPdf (execution_mode = remote, worker_topic = "workers.reporting.pdf")
```

- Compiles the remote node through `RemoteWorkerOperatorFactories.durable(...)`
- Enqueues a JSON-friendly `RemoteWorkerEnvelope` into a `WorkItemStore` and suspends at `renderPdf`
- Demonstrates worker-side poll/claim and the output payload shape a worker would complete with

### 8. Lambda Collection Operations (Lambda Collection Ops)

| Example | Description |
|------|------|
| `OrderEnrichmentLambdaDslExample` | Uses `.map` / `.filter` / `.reduce` / `.sortBy` / `.associate` inside an `input { }` block to perform inline list transformations without additional operator nodes |

Corresponding DSL resource file: `order-enrichment-lambda.bloge`

---

### 9. Intelligent Bots / Chatbot

Multi-turn chatbot examples covering two implementation patterns:

- **Plan A (single-turn graph + external loop)**: each user input executes the graph once, while the external loop manages conversation history.
- **Plan B (long-lived graph + suspend/resume)**: the entire conversation lifecycle executes once, and the `awaitUserInput` node suspends while waiting for `engine.signal()` to inject the user's message.

**Note:** Do not add `dependsOn(branchSource)` to branch target nodes — `ConditionalEdge` already establishes the scheduling dependency. Adding `DirectEdge` at the same time causes non-selected SKIPPED nodes to be incorrectly added to the ready queue and executed.

| Example | Mode | Description |
|------|------|------|
| `CustomerServiceChatbotExample` | Plan A · Java API | `parseInput → classifyIntent → branch(query_order / complaint / faq / fallback)` |
| `CustomerServiceChatbotDslExample` | Plan A · DSL | Same as above, DSL version using the `CsChat*` operator prefix |
| `EcommerceChatbotExample` | Plan A · Java API | E-commerce shopping guide: `branch(search_product / compare / recommend / fallback)` |
| `EcommerceChatbotDslExample` | Plan A · DSL | Same as above, DSL version using the `EcChat*` operator prefix |
| `ItHelpdeskChatbotExample` | Plan A · Java API | IT helpdesk: `branch(password_reset / permission_request / incident_report / faq)` |
| `ItHelpdeskChatbotDslExample` | Plan A · DSL | Same as above, DSL version using the `ItChat*` operator prefix |
| `CustomerServiceChatbotLongRunningExample` | Plan B · Java API | `greet → awaitUserInput[SUSPEND] → parseInput → classifyIntent → branch` |
| `CustomerServiceChatbotLongRunningDslExample` | Plan B · DSL | Same as above, DSL version using the `CsLr*` operator prefix |
| `AiToolCallingExample` | Tool-calling · Java API | `think → executeTool → respond` — manual two-turn tool loop built on `LlmProvider.ToolCall` and `LlmMessage.tool(...)` |
| `AiToolCallingDslExample` | Tool-calling · DSL | Same as above, DSL version backed by `ai-tool-calling.bloge` and `AiTool*` operators |
| `AgentExample` | Agent loop · Java API | One embedded `AgentLoopOperator` node built with `AgentBuilder`; exposes `searchKnowledgeBase`, `createTicket`, and `escalateToHuman` as declarative tools, with `maxToolConcurrency(2)` limiting per-turn tool fan-out |
| `AgentDslExample` | Agent loop · DSL | Same customer-support agent defined in `agent-customer-support.bloge`, including `max_tool_concurrency = 2`, and compiled through `AgentDslCompiler` |
| `StreamingAnalysisAgentExample` | Streaming agent · Java API | One `StreamingAgentLoopOperator` node built with `AgentBuilder`, `memory(TokenBudget)`, and a mock streaming LLM that emits token/tool lifecycle chunks |
| `StreamingAnalysisAgentDslExample` | Streaming agent · DSL | Same data-analysis agent defined with `stream agent` in `streaming-analysis-agent.bloge` |
| `DynamicSubGraphExample` | Graph factory · Java API | `generateDynamicDsl → executePlan[dynamicSubGraph]` — upstream node emits DSL, `dynamicSubGraph` sandbox-validates, compiles, and executes it at runtime |

Corresponding DSL resource files:
- `customer-service-chatbot.bloge`
- `ecommerce-chatbot.bloge`
- `it-helpdesk-chatbot.bloge`
- `customer-service-chatbot-long-running.bloge`
- `ai-tool-calling.bloge`
- `agent-customer-support.bloge`
- `streaming-analysis-agent.bloge`
- `dynamic-agent.bloge`

**Plan B lifecycle**:

```
engine.executeWithOperators(graph, ctx, ops)    // runs on virtual threads
  ↓ awaitUserInput returns OperatorResult.suspend(...)  // graph suspends
onNodeSuspended(NodeSuspendedEvent) fires       // listener sees event.nodeId() == "awaitUserInput"

engine.signal(execId, "awaitUserInput", payload) // inject user message
  ↓ the signal payload becomes the output of awaitUserInput
parseInput → classifyIntent → branch → solver   // execution resumes

resultFuture.get()                              // obtain the final result
```

New BPMN example resource: `src/main/resources/bpmn/order-process.bpmn` (can be translated and executed via `BpmnToDslExample`).

### 10. State Machine (Order Lifecycle)

Event-driven state machine modelling an order lifecycle:
draft → pendingReview → processing → completed, with per-state timeouts and embedded DAGs.

| Example | Mode | Description |
|---------|------|-------------|
| `OrderLifecycleStateMachineExample` | Java API | Order lifecycle: draft → pendingReview → processing → completed with state timeouts and embedded DAGs |
| `OrderLifecycleStateMachineDslExample` | DSL | Same as above, compiled from `order-lifecycle-state-machine.bloge` |

DSL resource: `src/main/resources/bloge/order-lifecycle-state-machine.bloge`

### 11. State Machine (Order Fulfillment — Global Transitions, Checkpoint, Listener)

Complete state machine example demonstrating all Phase 0–5 features:
pending → confirmed → shipped → delivered, with global CANCEL transition,
per-state timeouts, checkpoint/resume, and audit listener.

| Example | Mode | Description |
|---------|------|-------------|
| `OrderStateMachineExample` | Java API | Order fulfillment with `globalTransition("CANCEL", "cancelled")`, per-state timeouts, `StateMachineEvent`-based audit logging, `createCheckpoint()` / `resumeFromCheckpoint()` round-trip, and `StateMachineCheckpoint.toBuilder()` for checkpoint mutation |
| `OrderStateMachineDslExample` | DSL | Same workflow compiled from `order-state-machine.bloge` — demonstrates `global_transitions { }` DSL syntax |

DSL resource: `src/main/resources/bloge/statemachine/order-state-machine.bloge`

### 12. Approval Workflow (Guard Expressions, Global Transitions, State Timeouts)

Demonstrates the unique combination of `when` guard expressions on transitions together
with `global_transitions` and per-state `timeout`/`on_timeout` escalation:

| State | Timeout | Description |
|-------|---------|-------------|
| `pending [initial]` | 24h → `auto_assigned` | Waiting for reviewer assignment |
| `auto_assigned` | 72h → `escalated` | System auto-assigned a reviewer |
| `under_review` | 72h → `escalated` | Reviewer is actively processing |
| `escalated` | 48h → `rejected` | Manager review after reviewer timeout |

Global transitions (`WITHDRAW`, `CANCEL`) apply from any non-terminal state.
`APPROVE` transitions carry a guard: `when ctx.approverLevel >= ctx.requiredLevel`.

DSL resource: `src/main/resources/bloge/statemachine/approval-workflow.bloge`

```
src/main/resources/bloge/
├── Basic DAG ─────────────────────────────────────────────────────────────────
├── order-process.bloge              — E-commerce order processing
├── bff-dashboard.bloge              — BFF multi-source aggregation
├── food-order.bloge                 — Catering delivery order processing
├── ticket-routing.bloge             — Customer service ticket routing
├── loan-approval.bloge              — Finance loan approval
├── online-triage.bloge              — Healthcare online triage
├── claim-processing.bloge           — Insurance claim processing
├── shipment-planning.bloge          — Logistics shipment planning
│
├── Sub-Graph ────────────────────────────────────────────────────────────────
├── order-full-pipeline.bloge        — E-commerce end-to-end order pipeline ⭐
├── loan-approval-subgraph.bloge     — Finance full loan approval flow ⭐
├── smart-ticket-handling.bloge      — Customer service smart ticket handling ⭐
├── international-shipment.bloge     — Logistics international shipment ⭐
├── restaurant-order-pipeline.bloge  — Catering restaurant order pipeline ⭐
│
├── Iteration (foreach & loop)────────────────────────────────────────────────────
├── batch-order-parallel.bloge       — foreach parallel batch orders with `max_concurrency`
├── batch-order-processing.bloge     — batch order processing (early reference)
├── sequential-transfer.bloge        — foreach sequential transfers
├── status-polling.bloge             — loop status polling
├── cursor-pagination.bloge          — loop cursor pagination
├── retry-with-backoff.bloge         — loop exponential backoff retry
├── logistics-batch-dispatch.bloge   — composite foreach + loop
│
├── Streaming (stream node / stream foreach / stream loop)──────────────────
├── llm-streaming.bloge              — LLM token streaming pipeline
├── streaming-batch.bloge            — streaming batch orders (stream foreach)
├── streaming-status-monitor.bloge   — streaming status monitoring (stream loop)
│
├── Voice Agent AI orchestration ───────────────────────────────────────────────
├── voice-pipeline.bloge             — basic voice processing pipeline
├── multimodal-interaction.bloge     — multimodal audio/video interaction
├── voice-contact-center.bloge       — intelligent voice contact center
├── voice-banking.bloge              — voice banking
├── voice-telemedicine.bloge         — voice telemedicine
├── voice-logistics-dispatch.bloge   — voice logistics dispatch
├── voice-tutoring.bloge             — voice pronunciation tutoring
├── voice-emergency-dispatch.bloge   — voice emergency dispatch
│
├── Long-running (wait / await)──────────────────────────────────────
├── ticket-approval-wait.bloge       — customer service ticket approval wait (timer)
├── payment-wait.bloge               — e-commerce payment confirmation wait (event)
├── loan-disbursement-wait.bloge     — finance loan disbursement wait (AND-mode)
├── cargo-readiness-wait.bloge       — logistics cargo readiness wait (OR-mode)
├── patient-consent-wait.bloge       — healthcare patient consent deadline (deadline)
├── scheduled-wait.bloge             — reporting cron + deadline scheduled waits
│
├── Lambda collection operations ───────────────────────────────────────────────────────────
├── order-enrichment-lambda.bloge    — inline .map/.filter/.reduce/.sortBy example
│
├── Intelligent bots / chatbots──────────────────────────────────────────────────────
├── customer-service-chatbot.bloge           — customer service chatbot (Plan A single-turn graph)
├── ecommerce-chatbot.bloge                  — e-commerce shopping guide chatbot (Plan A single-turn graph)
├── it-helpdesk-chatbot.bloge                — IT helpdesk chatbot (Plan A single-turn graph)
├── customer-service-chatbot-long-running.bloge — customer service long-running chatbot (Plan B suspend/resume)
├── ai-tool-calling.bloge                    — manual tool-calling loop with think → executeTool → respond
├── agent-customer-support.bloge             — first-class `agent` DSL with declarative tool bindings, memory strategy, and `max_tool_concurrency`
├── dynamic-agent.bloge                      — graph-factory pattern: generateDynamicDsl → dynamicSubGraph (F1)
│
├── State Machine ───────────────────────────────────────────────────────────────
├── order-lifecycle-state-machine.bloge  — order lifecycle state machine (draft → completed)
├── statemachine/order-state-machine.bloge — order fulfillment with global_transitions + timeouts
├── statemachine/approval-workflow.bloge  — approval workflow with guard expressions + escalation timeouts
│
└── Antipattern references──────────────────────────────────────────────
    └── antipatterns/
        ├── excessive-nodes.bloge       — excessive node splitting antipattern
        ├── god-graph.bloge             — oversized graph that should become sub-graphs
        ├── over-broad-fallback.bloge   — fallback that swallows too much
        ├── missing-timeout.bloge       — slow node without a timeout guard
        └── circular-dependency.bloge   — cyclic dependency example
```

## Running examples

use `run.sh` and `run-repl.sh`
