# BLOGE DSL Syntax Reference

Concise machine-readable reference for AI/LLM code generation.
Derived from [`bloge-dsl-specification.md`](../bloge-dsl-specification.md) v1.0.0.

## Resource Gateway Agent TDD 支持范围

本文是仓库内的完整语法资料，不是远程 Agent 的运行时权威输入。Resource Gateway 1.4.2 只向 Agent TDD 开放 `graph` 根类型。Codex 必须先调用 `rg.dsl.reference.get`，以返回的 `languageVersion`、`compilerProfile`、topics、可见 operator/function contracts、certified examples 和 `authoringContextFingerprint` 为准。不同 tenant、project、environment 或 `libraryRefs` 的参考不能混用。

业务人员不需要阅读或编写 DSL。Codex 根据业务意图生成候选后，应按以下顺序在后台处理：

1. 用同一 `authoringContextFingerprint` 调用 `rg.dsl.preview`。
2. 只根据 payload-free `authoringDiagnostics` 修正；最多三轮。同一阻断 `diagnosticFingerprint` 连续出现两次时停止。
3. preview 接受后，对同一 source 调用 `rg.gate.check`。
4. compose 时提交同一 source、context fingerprint 和 receipt fingerprint。服务端会重新编译并拒绝 source/context/receipt 不一致的请求。

`session` 和 `state_machine` 仍属于 BLOGE 语言，但不在当前 Agent TDD authoring profile 中。不要把本文件整篇复制进提示词，也不要用它覆盖 MCP 返回的当前作用域参考。

---

## File Structure

A `.bloge` file contains exactly one top-level definition: a `graph`, `session`, or `state_machine`.

```ebnf
Program = GraphDef | SessionDef | StateMachineDef
```

---

## Graph

```ebnf
GraphDef = DocComment? "graph" IDENT "{" Member* "}"
Member   = NodeDef | BranchDef | TransformDef | SchemaDef | ForEachDef
         | LoopDef | WaitDef | AwaitDef | ScriptDef | CommentNode
```

```bloge
/// Doc comment for the graph
graph orderProcess {
  // members here
}
```

---

## Node

```ebnf
NodeDef  = DocComment? "node" IDENT ":" IDENT "{" NodeBody "}"
NodeBody = ( InputBlock | OutputDecl | DependsOn
           | TimeoutField | RetryField | FallbackField | ScopeField
           | ExecutionModeField | WorkerTopicField )*
```

```bloge
/// Fetches user profile
node fetchUser : FetchUserOperator {
  input {
    userId = ctx.userId
  }
  depends_on = [otherNode]
  timeout = 3s
  retry = { attempts: 2, backoff: 200ms, strategy: exponential }
  fallback = { name: "unknown", email: "" }
  output {
    name: String
    email: String
  }
}
```

### Input Block

```bloge
input {
  field = ctx.value
  other = someNode.output.result
}
```

### Output Declaration

Inline schema or named reference:

```bloge
output { name: String, email: String? }
output : MySchema
```

### Depends On

```bloge
depends_on = [nodeA, nodeB]
```

### Compensation

```bloge
node reserve : ReserveOp {
  output { reservationId: String }
  compensate : ReleaseOp {
    input { reservationId = reserve.output.reservationId }
  }
}
```

### Remote Execution

Delegate a node to an external worker process. The compiler wraps the node in a
durable `RemoteWorkerOperator`; local operator-registry validation is skipped.
Cannot be combined with subgraph operators.

```bloge
node generateReport : ReportGenerator {
  input { params = ctx.reportParams }
  execution_mode = remote
  worker_topic = "workers.reporting"
  timeout = 30s
  retry = { attempts: 2, backoff: 1s, strategy: exponential }
}
```

| Field | Type | Description |
|-------|------|-------------|
| `execution_mode` | Identifier | Currently only `remote` is supported |
| `worker_topic` | String | Topic that workers poll for jobs; required when `execution_mode = remote` |

Constraints:
- `worker_topic` requires `execution_mode = remote`
- `execution_mode = remote` requires `worker_topic`
- Subgraph nodes cannot use remote execution

---

## Branch

```ebnf
BranchDef = DocComment? "branch" ("mode" "=" ("inclusive"|"exclusive"))? "on" Expr "{" BranchCase* "}"
BranchCase = (Expression | "otherwise") "->" IDENT
```

Exclusive (default — first match wins):

```bloge
branch on checkCredit.output.approved {
  true  -> createOrder
  false -> rejectOrder
}
```

Multi-value with otherwise:

```bloge
branch on classify.output.priority {
  "vip"    -> assignVip
  "normal" -> assignNormal
  otherwise -> autoResolve
}
```

Inclusive (all matching branches activate):

```bloge
branch mode=inclusive on a.output.flags {
  "fast"     -> b
  "priority" -> c
  otherwise  -> d
}
```

---

## Transform

Zero-cost projection node; no operator is scheduled.

```ebnf
TransformDef = DocComment? "transform" IDENT "{" LetBinding* TransformField* "}"
LetBinding   = "let" IDENT "=" Expression
TransformField = DocComment? IDENT (":" IDENT "?"?)? "=" Expression
```

```bloge
transform orderSummary {
  let discount = calcPrice.output.discount
  customerName: String = fetchUser.output.name
  total: Number = calcPrice.output.total - discount
}
```

---

## Schema

```ebnf
SchemaDef = "schema" IDENT "{" FieldDecl* "}"
FieldDecl = IDENT ":" ( IDENT "?"? | "{" FieldDecl* "}" )
```

```bloge
schema OrderResult {
  orderId: String
  total: Number
  note: String?
  address: {
    street: String
    city: String
  }
}
```

Types: `String`, `Int`, `Number`, `Boolean`, or named schemas. `?` marks optional.

---

## ForEach

```ebnf
ForEachDef = DocComment? "foreach" IDENT ":" ("(" IDENT ("," IDENT)? ")" | IDENT)
             "in" Expr "sequential"? "{" Member* "}"
```

Parallel (default):

```bloge
foreach processOrders : (order, idx) in fetchOrders.output.orders {
  node validate : Validator { input { order = order, index = idx } }
  node deduct : Deductor { depends_on = [validate] input { id = order.orderId } }
}
```

Sequential:

```bloge
foreach items : item in ctx.items sequential { ... }
```

---

## Loop

```ebnf
LoopDef = DocComment? "loop" IDENT "{" (MaxIter | Delay | DependsOn | Member | CarryBlock | UntilClause | ExitBlock)* "}"
```

With `until` condition:

```bloge
loop processBatch {
  max_iterations = 100
  delay = 5s
  node fetch : Fetcher { input { cursor = carry.cursor } }
  carry { cursor: fetch.output.nextCursor }
  until fetch.output.done == true
}
```

With exit routes:

```bloge
loop inspect {
  max_iterations = 3
  node check : Inspector { input { item = ctx.item } }
  exit {
    check.output.verdict == "pass"   -> approved
    check.output.verdict == "reject" -> rejected
    exhausted -> escalate
  }
}
```

Loop-specific paths: `carry.field`, `prev.nodeId.field`, `loopIteration`.

---

## Wait (Timer Suspension)

```ebnf
WaitDef = DocComment? "wait" IDENT "=" (DURATION | "deadline" "(" STRING ")" | "cron" "(" STRING ")") "after" IDENT ("{" WaitBody "}")?
```

```bloge
wait paymentDeadline = 24h after createOrder {
  signal_key = createOrder.output.orderId
  on_timeout { status = "expired" }
  on_fire    { status = "paid" }
}
```

---

## Await (External Event Correlation)

```ebnf
AwaitDef = DocComment? "await" IDENT "{" (ModeDecl | DependsOn | EventMatcher | TimeoutField | OnTimeout)* "}"
EventMatcher = "event" STRING ("where" IDENT "=" Expr)? ("{" "optional" "=" "true" "}")?
```

```bloge
await approvalResult {
  depends_on = [submitRequest]
  mode = any
  event "approval.completed" where correlationId = submitRequest.output.requestId
  event "approval.rejected"  where correlationId = submitRequest.output.requestId
  timeout = 48h
  on_timeout { status = "expired" }
}
```

---

## Script

Sandboxed Groovy execution:

```bloge
script classify {
  lang = "groovy"
  timeout = 5s
  input { score = ctx.score }
  output_schema { level: String }
  code = """
    if (score > 80) return [level: "high"]
    return [level: "low"]
  """
}
```

---

## Session (Multi-Round)

```ebnf
SessionDef = DocComment? "session" IDENT "{" SessionProp* PhaseDef* "}"
PhaseDef   = DocComment? "phase" IDENT "{" PhaseBody "}"
PhaseBody  = (PhaseProperty | "round" "{" Member* "}" | Member)*
ThenProp   = "then" "->" IDENT | "then" "{" (Expr "->" IDENT)* ("otherwise" "->" IDENT)? "}"
```

```bloge
session customerService {
  idle_timeout = 5m
  max_rounds = 20

  phase greeting {
    node greet : Greeter { input { id = ctx.sessionId } }
    then -> triage
  }

  phase triage {
    max_rounds = 5
    yield_on = [respond]
    round {
      node respond : Responder { input { msg = ctx.round.input.userMessage } }
    }
    until respond.output.done == true
    then {
      respond.output.action == "handoff" -> solve
      otherwise -> wrapUp
    }
  }

  phase wrapUp {
    node close : Closer { input { resolution = ctx.solve.output.result } }
  }
}
```

---

## State Machine (Event-Driven)

```ebnf
StateMachineDef = DocComment? "state_machine" IDENT "{" StateMachineProp* StateDef* "}"
StateDef        = DocComment? "state" IDENT StateModifier? "{" StateBody "}"
StateModifier   = "[initial]" | "[terminal]"
StateBody       = ( AnonymousGraph | SessionDef | Transition | StateTimeout | OnTimeout )*
Transition      = "on" (IDENT | "*") ("when" Expr)? "->" IDENT
StateTimeout    = "timeout" "=" DURATION
OnTimeout       = "on_timeout" "->" IDENT
```

```bloge
/// Order lifecycle state machine
state_machine orderLifecycle {
  max_transitions = 25
  max_state_visits = 5
  timeout = 72h

  state draft [initial] {
    graph {
      node initOrder : InitOrderOperator {
        input {
          orderId = ctx.orderId
        }
      }
    }
    on submit -> pendingReview
  }

  state pendingReview {
    on approve -> completed
    on reject -> draft
    timeout = 24h
    on_timeout -> draft
  }

  state completed [terminal] { }
}
```

Notes:

- `state_machine` is a first-class top-level root, not a nested-only feature.
- A `state` body may contain an anonymous `graph { ... }` or a nested `session { ... }`.
- `on * -> target` declares an automatic transition after the state's graph completes.

---

## Expressions

### Path Expressions

| Pattern | Resolves To | Example |
|---------|------------|---------|
| `ctx.field` | Graph context input | `ctx.userId` |
| `node.output.field` | Node output (explicit) | `fetchUser.output.name` |
| `node.field` | Node output (implicit) | `fetchUser.name` |
| `transform.field` | Transform field | `summary.total` |
| `item` / `item.field` | ForEach item | `order.orderId` |
| `idx` | ForEach index | `idx` |
| `carry.field` | Loop carry state | `carry.cursor` |
| `prev.node.field` | Previous loop iteration | `prev.fetch.result` |
| `loopIteration` | Current loop iteration number | `loopIteration` |
| `node.stream.field` | Streaming output | `source.stream.chunk` |

Safe navigation: `ctx.user?.name` (null-propagating).

### Operators

| Category | Operators |
|----------|-----------|
| Arithmetic | `+` `-` `*` `/` `%` |
| Comparison | `==` `!=` `>` `<` `>=` `<=` |
| Logical | `&&` `\|\|` `!` |
| Null handling | `??` (coalesce), `?.` (safe nav) |
| Ternary | `cond ? then : else` |

### String Interpolation

```bloge
message = "Hello ${ctx.user.name}! You have ${summary.total} items"
literal = "\${ctx.user.name}"
```

- Works in normal double-quoted strings.
- Each `${expr}` placeholder accepts the regular BLOGE expression grammar.
- Use `\${` to emit a literal `${` in the resulting string.
- Placeholder values are concatenated as strings; `null` contributes empty text.
- Triple-quoted `"""..."""` strings keep raw script-body behavior and are not interpolated.

### Lambdas

```bloge
items.filter(x -> x.active == true)
items.map((item, idx) -> item.name + " #" + idx)
list.reduce(0, (acc, x) -> acc + x.amount)
```

### When Expression

```bloge
result = when status {
  "active"  -> "green"
  "pending" -> "yellow"
  otherwise -> "gray"
}
```

### Object & Array Literals

```bloge
fallback = { approved: false, reason: "unavailable" }
tags = ["urgent", "vip"]
```

---

## Built-in Functions

| Category | Functions |
|----------|-----------|
| String | `length(s)` `substring(s,start,end?)` `toUpperCase(s)` `toLowerCase(s)` `trim(s)` `replace(s,old,new)` `startsWith(s,prefix)` `endsWith(s,suffix)` `contains(s,sub)` `split(s,delim)` `join(list,delim)` `matches(s,regex)` |
| Math | `abs(n)` `min(a,b)` `max(a,b)` `round(n)` `floor(n)` `ceil(n)` |
| Collection | `size(c)` `isEmpty(c)` `first(c)` `last(c)` `flatten(c)` `distinct(c)` `sorted(c)` `reversed(c)` `take(c,n)` `skip(c,n)` `zip(a,b)` `groupBy(c,fn)` `toMap(c,keyFn,valFn)` |
| Collection methods | `.filter(fn)` `.map(fn)` `.reduce(init,fn)` `.flatMap(fn)` `.any(fn)` `.all(fn)` `.none(fn)` `.find(fn)` `.count(fn)` `.sumBy(fn)` `.minBy(fn)` `.maxBy(fn)` `.sortBy(fn)` |
| Null | `coalesce(a,b,...)` `ifNull(val,default)` |
| Type | `toString(v)` `toNumber(v)` `toBoolean(v)` `typeOf(v)` |
| Date/Time | `now()` `formatDate(d,pattern)` `parseDate(s,pattern)` `plusDays(d,n)` `plusHours(d,n)` `between(d1,d2)` |
| JSON | `toJson(v)` `fromJson(s)` |
| Crypto | `sha256(s)` `hmacSha256(s,key)` `base64Encode(s)` `base64Decode(s)` |
| Other | `uuid()` `secret(name)` |

**Impure** (`now()`, `uuid()`, `secret()`): forbidden inside `transform` blocks.

---

## Duration Literals

```
100ms   3s   5m   1h   7d
```

Suffix: `ms` = milliseconds, `s` = seconds, `m` = minutes, `h` = hours, `d` = days.

---

## Comments

```bloge
/// Single-line doc comment (preserved in AST, attached to next construct)
/** Multi-line doc comment (also preserved) */
// Regular line comment (discarded)
/* Block comment (discarded, supports nesting) */
```

---

## Resilience Configuration

### Timeout

```bloge
timeout = 3s
```

### Retry

```bloge
retry = { attempts: 3, backoff: 200ms, strategy: exponential }
retry = { attempts: 5, backoff: 100ms, strategy: jitter }
retry = { attempts: 2, backoff: 1s, strategy: fixed }
```

Strategies: `fixed`, `exponential`, `jitter`.

### Fallback

Static value returned when the operator fails after all retries:

```bloge
fallback = { approved: false, reason: "service unavailable" }
fallback = { score: 0 }
```

---

## Scope Mode

Controls visibility of parent node outputs inside `foreach`, `loop`, and `subgraph`:

```bloge
scope = parent    // (default for foreach/loop) can reference outer nodes
scope = isolated  // (default for subgraph) only sees explicit input
```
