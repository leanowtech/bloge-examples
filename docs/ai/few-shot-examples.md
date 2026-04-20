# BLOGE DSL Few-Shot Examples

Curated complete `.bloge` programs for few-shot LLM prompting.
Include 2-3 examples matching your target use case in the prompt context.

## Categories

| Category | Example | Key Features |
|----------|---------|--------------|
| Minimal | `hello-world.bloge` | Single node, ctx binding, timeout |
| Resilience | `error-handling-showcase.bloge` | retry, timeout, fallback |
| Parallel Fan-Out | `order-process.bloge` | parallel fetch, transform, branch |
| Multi-Way Branch | `ticket-routing.bloge` | three-way branch, otherwise, retry/fallback |
| Complex Pipeline | `food-order.bloge` | fan-out/fan-in, branch, post-branch parallelism |
| Saga/Compensation | `order-saga.bloge` | compensate blocks, rollback chain |
| ForEach | `batch-order-processing.bloge` | foreach parallel, indexed item processing |
| Session | `customer-service-session.bloge` | session, phase, round, until/then |
| State Machine | `order-lifecycle-state-machine.bloge` | state_machine, state timeout, on_timeout |
| Inclusive Branch | `bloge-conformance/fixtures/snippets/inclusive/basic.bloge` | branch mode=inclusive, otherwise |
| Loop | `bloge-conformance/fixtures/snippets/loop/with-carry.bloge` | loop, carry, prev, until |
| Loop | `bloge-conformance/fixtures/snippets/loop/exit-routes.bloge` | loop exit routing, exhausted path |
| Transform | `bloge-conformance/fixtures/snippets/transform/basic.bloge` | transform projection, typed fields |
| Schema | `bloge-conformance/fixtures/snippets/schema/optional-fields.bloge` | schema, required and optional fields |
| Remote Execution | (inline) | execution_mode, worker_topic, timeout |

Paths without a prefix refer to files under `bloge-examples/src/main/resources/bloge/`.
Paths starting with `bloge-conformance/fixtures/snippets/` are compact conformance snippets that are ideal for few-shot context.

## 1. Minimal — Hello World

Smallest working graph. Demonstrates context binding into a single node with a timeout.

```bloge
/// Minimal one-node graph for first-time BLOGE users.
///
/// Reads ctx.message and forwards it to a single echo operator.
/// This is the smallest possible graph that still demonstrates
/// context binding, node naming, and typed output inspection.
graph helloWorld {
  node echo : EchoOperator {
    input {
      message = ctx.message
    }
    timeout = 1s
  }
}
```

## 2. Resilience — Retry and Fallback

Shows per-node retry, timeout, and static fallback to recover from transient failures.

```bloge
/// Error-handling showcase with retry and fallback.
///
/// The Java variant also demonstrates the strict version without fallback.
/// This DSL variant keeps the forgiving path so new users can see how a graph
/// can recover and emit a manual-review summary.
graph errorHandlingShowcase {
  node chargePayment : ChargePaymentOperator {
    input {
      orderId         = ctx.orderId
      amount          = ctx.amount
      simulateFailure = ctx.simulateFailure
    }
    retry = { attempts: 1, backoff: 25ms, strategy: exponential }
    timeout = 1s
    fallback = { approved: false, note: "Gateway unavailable; queued for manual review" }
  }

  node summarizeOutcome : SummarizeOutcomeOperator {
    depends_on = [chargePayment]
    input {
      approved = chargePayment.output.approved
      note     = chargePayment.output.note
    }
    timeout = 1s
  }
}
```

## 3. Parallel Fan-Out — Order Processing

Fetches user and products in parallel, fans in for pricing, projects with a transform, then branches on credit approval.

```bloge
/// Processes an order by fetching user and product data, calculating price, and branching on credit.
///
/// Fetches the user profile and product catalogue in parallel, calculates the total price,
/// projects an order summary via a zero-cost transform, runs a credit check, and branches
/// to either create the order or reject it based on credit approval.
///
/// Key DSL concepts demonstrated:
///   depends_on = [a, b]  — calcPrice fans in from fetchUser and fetchProducts (parallel fetch)
///   transform            — orderSummary projects upstream outputs at zero cost; no operator is scheduled
///   branch on <expr>     — exactly one of createOrder or rejectOrder executes; the other is skipped
///   retry = { }          — retries fetchUser and checkCredit on transient failures
///   fallback = { }       — static substitute when the credit service is unavailable
///   timeout = Ns         — cancels fetchUser and fetchProducts if exceeded
///
/// Context variables:
///   ctx.userId     — identifier of the user placing the order
///   ctx.productIds — list of product identifiers included in the order
graph orderProcess {

  /// Fetches the user profile; retries twice on transient failures
  node fetchUser : FetchUserOperator {
    input {
      userId = ctx.userId
    }
    timeout = 3s
    retry = { attempts: 2, backoff: 200ms, strategy: exponential }
  }

  /// Fetches product details for all requested product IDs; runs in parallel with fetchUser
  node fetchProducts : FetchProductsOperator {
    input {
      productIds = ctx.productIds
    }
    timeout = 5s
  }

  /// Calculates the total order price including any discounts; fans in from fetchUser and fetchProducts
  node calcPrice : CalcPriceOperator {
    depends_on = [fetchUser, fetchProducts]
    input {
      user     = fetchUser.output
      products = fetchProducts.output
    }
  }

  /// Combines user and pricing data into an order summary
  transform orderSummary {
    customerName = fetchUser.output.name
    customerEmail = fetchUser.output.email
    itemCount = fetchProducts.output.items.size
    total = calcPrice.output.total
  }

  /// Verifies the user has sufficient credit for the order total; falls back to rejected if unavailable
  node checkCredit : CreditCheckOperator {
    depends_on = [fetchUser, calcPrice]
    input {
      userId = fetchUser.output.id
      amount = calcPrice.output.total
    }
    retry = { attempts: 3, backoff: 100ms, strategy: jitter }
    fallback = { approved: false, reason: "credit service unavailable" }
  }

  /// Routes to order creation or rejection based on credit approval; exactly one branch executes
  branch on checkCredit.output.approved {
    true  -> createOrder
    false -> rejectOrder
  }

  /// Creates and persists the order record when credit is approved
  node createOrder : CreateOrderOperator {
    depends_on = [calcPrice]
    input {
      user  = fetchUser.output
      price = calcPrice.output
    }
  }

  /// Records the rejection and notifies the user when credit is denied
  node rejectOrder : RejectOrderOperator {
    depends_on = [checkCredit]
    input {
      userId = fetchUser.output.id
      reason = checkCredit.output.reason
    }
  }
}
```

## 4. Multi-Way Branch — Ticket Routing

Demonstrates a three-way branch with `otherwise` after parallel customer and history lookups plus sentiment analysis.

```bloge
/// Routes a customer support ticket to the appropriate handler based on sentiment and priority.
///
/// Customer details and ticket history are fetched in parallel; sentiment is then
/// analysed across both inputs and a priority is classified.  A branch directs VIP
/// customers to a dedicated agent, normal-priority tickets to the standard queue, and
/// low-priority tickets to automatic resolution.
///
/// Key DSL concepts demonstrated:
///   depends_on = [a, b]   — fan-in: analyzeSentiment waits for both fetch nodes to
///                           complete before starting
///   branch on <expr>      — exactly one handler executes; the other two are skipped
///   timeout               — per-node cancellation deadline
///   retry / fallback      — automatic retry with a neutral-sentiment fallback on failure
///
/// Context variables:
///   ctx.customerId — ID of the customer who submitted the ticket
///   ctx.message    — raw message content of the ticket
graph ticketRouting {

  /// Fetches the customer's profile; retries twice on transient failure
  node fetchCustomer : FetchCustomerOperator {
    input {
      customerId = ctx.customerId
    }
    timeout = 3s
    retry = { attempts: 2, backoff: 200ms, strategy: exponential }
  }

  /// Fetches the customer's previous ticket history
  node fetchTicketHistory : FetchTicketHistoryOperator {
    input {
      customerId = ctx.customerId
    }
    timeout = 3s
  }

  /// Analyses message sentiment using customer context and history;
  /// depends_on fan-in waits for both fetch nodes to complete;
  /// falls back to a neutral-sentiment result if the operator throws
  node analyzeSentiment : AnalyzeSentimentOperator {
    depends_on = [fetchCustomer, fetchTicketHistory]
    input {
      customer = fetchCustomer.output
      history  = fetchTicketHistory.output
      message  = ctx.message
    }
    timeout = 5s
    retry = { attempts: 1, backoff: 500ms, strategy: exponential }
    fallback = { sentiment: "neutral", score: 0.0, keywords: [] }
  }

  /// Classifies the ticket priority (vip / normal / otherwise) from customer profile and sentiment
  node classifyPriority : ClassifyPriorityOperator {
    depends_on = [analyzeSentiment]
    input {
      customer  = fetchCustomer.output
      sentiment = analyzeSentiment.output
    }
  }

  /// branch on classifyPriority.output.priority — exactly one handler executes; the other two are skipped
  branch on classifyPriority.output.priority {
    "vip"    -> assignVipAgent
    "normal" -> assignNormalAgent
    otherwise -> autoResolve
  }

  /// Assigns a VIP-tier agent to handle the ticket with elevated priority
  node assignVipAgent : AssignVipAgentOperator {
    depends_on = [classifyPriority]
    input {
      customerId = fetchCustomer.output.id
      priority   = "vip"
    }
  }

  /// Assigns a standard-tier agent to handle the ticket
  node assignNormalAgent : AssignNormalAgentOperator {
    depends_on = [classifyPriority]
    input {
      customerId = fetchCustomer.output.id
      priority   = "normal"
    }
  }

  /// Automatically resolves low-priority tickets using detected sentiment keywords
  node autoResolve : AutoResolveOperator {
    depends_on = [classifyPriority]
    input {
      customerId = fetchCustomer.output.id
      keywords   = analyzeSentiment.output.keywords
    }
  }
}
```

## 5. Complex Pipeline — Food Order

Shows pre-branch parallel checks, a decision fan-in, and post-acceptance parallel work for fulfillment and payment.

```bloge
/// Food order processing with parallel pre-checks and branch-based acceptance routing.
///
/// Validates an incoming order, then fans out to three concurrent checks (inventory,
/// kitchen load, delivery estimate). A decision node weighs all three results and branches
/// on acceptance: approved orders proceed to kitchen notification, rider assignment, and
/// payment processing in parallel; declined orders receive alternative suggestions.
///
/// Key DSL concepts demonstrated:
///   parallel fan-out    — checkInventory, checkKitchenLoad, and estimateDelivery run concurrently
///   depends_on = [...]  — decideAcceptance waits for all three checks to complete (fan-in)
///   branch on <expr>    — exactly one of acceptOrder or suggestAlternatives executes; the other is skipped
///   fallback = { }      — assignRider supplies a self-pickup substitute when no rider is available
///   timeout = Ns        — individual nodes are cancelled if they exceed their time budget
///
/// Context variables:
///   ctx.orderId         — unique identifier for the incoming order
///   ctx.restaurantId    — the restaurant fulfilling the order
///   ctx.items           — list of ordered items
///   ctx.deliveryAddress — delivery destination address
graph foodOrderProcess {

  /// Validates the order structure, restaurant existence, and item availability
  node validateOrder : ValidateOrderOperator {
    input {
      orderId       = ctx.orderId
      restaurantId  = ctx.restaurantId
      items         = ctx.items
      deliveryAddress = ctx.deliveryAddress
    }
    timeout = 2s
  }

  /// Checks whether all ordered items are currently in stock at the restaurant
  node checkInventory : CheckInventoryOperator {
    depends_on = [validateOrder]
    input {
      restaurantId = validateOrder.output.restaurantId
      items        = validateOrder.output.items
    }
    timeout = 3s
  }

  /// Checks the kitchen's current order queue to assess capacity for a new order
  node checkKitchenLoad : CheckKitchenLoadOperator {
    depends_on = [validateOrder]
    input {
      restaurantId = validateOrder.output.restaurantId
    }
    timeout = 2s
  }

  /// Estimates delivery time and feasibility based on restaurant location and delivery address
  node estimateDelivery : EstimateDeliveryOperator {
    depends_on = [validateOrder]
    input {
      restaurantId    = validateOrder.output.restaurantId
      deliveryAddress = ctx.deliveryAddress
    }
    timeout = 3s
  }

  /// Combines inventory, kitchen, and delivery results to decide whether to accept the order
  node decideAcceptance : DecideAcceptanceOperator {
    depends_on = [checkInventory, checkKitchenLoad, estimateDelivery]
    input {
      inventory = checkInventory.output
      kitchen   = checkKitchenLoad.output
      delivery  = estimateDelivery.output
    }
  }

  /// branch on accepted: exactly one downstream path executes; the other is skipped
  branch on decideAcceptance.output.accepted {
    true  -> acceptOrder
    false -> suggestAlternatives
  }

  /// Confirms the order acceptance and assigns an order ID for downstream processing
  node acceptOrder : AcceptOrderOperator {
    depends_on = [decideAcceptance]
    input {
      order    = validateOrder.output
      delivery = estimateDelivery.output
    }
  }

  /// Proposes alternative items or restaurants when the order cannot be fulfilled
  node suggestAlternatives : SuggestAlternativesOperator {
    depends_on = [decideAcceptance]
    input {
      order            = validateOrder.output
      unavailableItems = checkInventory.output.unavailableItems
    }
  }

  /// Sends the accepted order details to the kitchen for preparation
  node notifyKitchen : NotifyKitchenOperator {
    depends_on = [acceptOrder]
    input {
      orderId = acceptOrder.output.orderId
      items   = validateOrder.output.items
    }
  }

  /// Assigns a delivery rider to the order; falls back to self-pickup if none is available
  node assignRider : AssignRiderOperator {
    depends_on = [acceptOrder]
    input {
      orderId         = acceptOrder.output.orderId
      deliveryAddress = ctx.deliveryAddress
    }
    fallback = { riderId: "SELF-PICKUP", riderName: "Self Pickup" }
  }

  /// Charges the customer for the accepted order amount
  node processPayment : ProcessPaymentOperator {
    depends_on = [acceptOrder]
    input {
      orderId = acceptOrder.output.orderId
      amount  = acceptOrder.output.total
    }
    timeout = 5s
  }
}
```

## 6. Saga/Compensation — Order Saga

Minimal compensation flow. Each successful step declares how to roll back if a downstream step fails.

```bloge
graph orderSaga {
  node reserveInventory : ReserveInventoryOperator {
    output {
      reservationId: String
    }

    compensate : ReleaseInventoryOperator {
      input {
        reservationId = reserveInventory.output.reservationId
      }
    }
  }

  node chargePayment : ChargePaymentOperator {
    depends_on = [reserveInventory]
    output {
      chargeId: String
    }

    compensate : RefundPaymentOperator {
      input {
        chargeId = chargePayment.output.chargeId
      }
    }
  }

  node shipOrder : ShipOrderOperator {
    depends_on = [chargePayment]
    input {
      failShipping = ctx.failShipping
    }
  }
}
```

## 7. ForEach — Batch Processing

Processes each order in a fetched batch concurrently with per-item validation and stock deduction.

```bloge
/// Batch order processing with concurrent per-order validation and stock deduction.
///
/// Fetches all pending orders for a customer, then fans out across every order in parallel.
/// Within each iteration, stock deduction is gated on successful validation. Final results
/// are collected by a summary node after all iterations complete.
///
/// Key DSL concepts demonstrated:
///   foreach (parallel)  — default mode; each (order, idx) pair is processed concurrently
///   depends_on          — deductStock waits for validate to complete within the same iteration
///
/// Context variables:
///   ctx.customerId — identifies the customer whose pending orders are fetched and processed
graph batchOrderParallel {

  /// Fetches pending orders for the given customer
  node fetchOrders : OrderFetcherOperator {
    input {
      customerId = ctx.customerId
    }
  }

  /// foreach parallel mode (default): process each order concurrently
  /// order — variable referencing the current order
  /// idx — variable referencing the 0-based index
  foreach processOrders : (order, idx) in fetchOrders.output.orders {
    node validate : OrderValidatorOperator {
      input {
        order = order
        index = idx
      }
    }
    node deductStock : StockDeductionOperator {
      depends_on = [validate]
      input {
        orderId = order.orderId
        quantity = order.quantity
        validated = validate.output.valid
      }
    }
  }

  /// Summarizes all processed order results from the foreach output
  node summarize : BatchSummaryOperator {
    depends_on = [processOrders]
    input {
      results = processOrders.output
    }
  }
}
```

## 8. Session — Multi-Round Chatbot

Demonstrates session graphs with phases, repeated rounds, yield points, and conditional phase transitions.

```bloge
/// Customer-service chatbot using session / phase / round primitives
session customerServiceSession {
  idle_timeout = 5m
  timeout_action = "cs_session_timeout_policy"
  max_rounds = 20
  max_history = 50

  phase greeting {
    node greet : CsSessionGreeter {
      input {
        sessionId = ctx.sessionId
      }
    }
    then -> triage
  }

  phase triage {
    max_rounds = 5
    yield_on = [respond]
    round {
      node respond : CsSessionResponder {
        input {
          userMessage = ctx.round.input.userMessage
        }
      }
    }
    until respond.output.done == true
    then {
      respond.output.action == "handoff" -> solve
      otherwise -> wrapUp
    }
  }

  phase solve {
    node solveCase : CsSessionSolver {
      input {
        action = ctx.triage.output.respond.action
        userMessage = ctx.triage.output.respond.userMessage
      }
    }
    then -> wrapUp
  }

  phase wrapUp {
    node close : CsSessionCloser {
      input {
        resolution = ctx.solve.output.solveCase.resolution
      }
    }
  }
}
```

## 9. State Machine — Order Lifecycle

Demonstrates the top-level `state_machine` root, per-state transitions, and timeout-driven escalation.

```bloge
state_machine orderLifecycle {
  max_transitions = 25
  max_state_visits = 5
  timeout = 72h

  state draft [initial] {
    graph {
      node initOrder : InitOrderOperator {
        input {
          orderId = ctx.orderId
          customerId = ctx.customerId
        }
      }
    }
    on submit -> pendingReview
  }

  state pendingReview {
    graph {
      node reviewOrder : ReviewOrderOperator {
        input {
          orderId = ctx.draft.output.initOrder.orderId
        }
      }
    }
    on approve -> processing
    on reject -> draft
    timeout = 24h
    on_timeout -> draft
  }

  state processing {
    graph {
      node fulfillOrder : FulfillmentOperator {
        input {
          orderId = ctx.pendingReview.output.reviewOrder.orderId
        }
      }
    }
    on * -> completed
  }

  state completed [terminal] { }
}
```

## 10. Inclusive Branch

Shows `branch mode=inclusive`, where multiple matching routes may execute instead of exactly one.

```bloge
graph g {
  node a : Op {}
  node b : Op {}
  node c : Op {}
  node d : Op {}
  branch mode=inclusive on a.output.flags {
    "fast" -> b
    "priority" -> c
    otherwise -> d
  }
}
```

## 11. Loop with Carry State

Demonstrates iterative processing with `carry`, `prev`, `loopIteration`, and an `until` condition.

```bloge
graph g {
  loop processBatch {
    max_iterations = 100
    delay = 5s
    node fetchBatch : BatchFetcher {
      input {
        cursor = carry.cursor
        prevResult = prev.fetchBatch.result
        iteration = loopIteration
      }
    }
    carry { cursor: fetchBatch.output.nextCursor }
    until fetchBatch.output.done == true
  }
}
```

## 12. Loop with Exit Routes

Shows loop termination routing with explicit exit targets for pass, reject, and exhausted cases.

```bloge
graph returnInspection {
  loop inspectionCycle {
    max_iterations = 3
    node inspect : QualityInspection {
      input { item = ctx.item }
    }
    node evaluate : ResultEvaluator {
      depends_on = [inspect]
      input { inspection = inspect.output }
    }

    exit {
      evaluate.output.verdict == "pass"   -> processRefund
      evaluate.output.verdict == "reject" -> rejectReturn
      exhausted -> escalateToManager
    }
  }
}
```

## 13. Transform

Minimal zero-cost transform example projecting typed fields from upstream output.

```bloge
graph g {
  node source : Op {}
  transform summary {
    greeting: String = "hello"
    result: String = source.output.value
  }
}
```

## 14. Schema Declaration

Minimal schema declaration with required and optional fields.

```bloge
graph g {
  schema S {
    required: String
    optional: Int?
  }
}
```

## 15. Remote Execution

Delegates a node to an external worker process via `execution_mode = remote` and `worker_topic`.

```bloge
/// Offloads PDF rendering to a remote worker.
///
/// The local graph suspends until the remote worker completes.
/// The node's timeout acts as the suspend deadline.
///
/// Key DSL concepts demonstrated:
///   execution_mode = remote  — delegates execution to an external worker
///   worker_topic             — topic that workers poll for jobs
///   timeout                  — suspend deadline for the remote call
///   retry                    — retries on worker failure
graph remoteReport {
  node prepare : PrepareDataOperator {
    input {
      reportId = ctx.reportId
    }
    timeout = 5s
  }

  node renderPdf : PdfRenderOperator {
    depends_on = [prepare]
    input {
      data = prepare.output.payload
    }
    execution_mode = remote
    worker_topic = "workers.pdf-rendering"
    timeout = 60s
    retry = { attempts: 2, backoff: 2s, strategy: exponential }
  }

  node notify : NotifyOperator {
    depends_on = [renderPdf]
    input {
      url = renderPdf.output.downloadUrl
    }
    timeout = 3s
  }
}
```
