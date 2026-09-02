# Resource Gateway 调用方驱动 Fixture 模拟方案 v2

状态：Proposed

日期：2026-09-02

适用范围：API Resource、Reusable Flow、Operator、Built-in Function 的 Fixture 配置与模拟执行

关联基线：

- `rg-api-fixture-reusable-flow-authoring-proposal-v1.md`
- `rg-evolution-design-1.3.0.md`

## 1. 文档目的

本文定义调用方在模拟请求中选择 Fixture 条件、Fixture Case 和 Mock 行为的统一协议。

本文替代基线方案中 `SimulationRequest v1` 将输入来源与 Fixture 控制绑定为一个联合类型的设计。
本文不直接修改已经发布的 v1 wire contract；v2 在实现和验收完成前保持 Proposed。

本文首先解决以下使用场景：

1. 调用一个 API Resource 时，调用方提交本次业务输入，并指定一个精确 Fixture Case。
2. 调用一个 API Resource 时，服务端根据真实调用输入匹配已保存的 Fixture 条件。
3. 调用一个由多个 API Resource 构成的 DAG 时，为不同节点选择不同 Fixture Case。
4. 将一组节点 Fixture 绑定保存为工具或方案的可复用模拟场景。
5. 为 Operator 和 Built-in Function 配置 Fixture，并在独立调用或 DAG 调用点使用。
6. 在模拟证据中准确区分真实执行、Fixture 返回、错误、超时、回放和未执行。

## 2. 当前问题

### 2.1 输入与 Fixture 控制被错误绑定

当前 `SimulationRequest v1` 只有两种互斥来源：

- `FIXTURE_CASE`：Subject、Input 和 Controls 全部来自一个已保存 Case。
- `AD_HOC`：调用方提交 Subject 和 Input，但不能引用已保存 Controls。

调用方因此无法表达以下请求：

> 使用本次传入的客户数据运行工具，同时让 `profile` 节点命中 VIP Fixture，
> 让 `credit` 节点模拟 Provider Timeout，其余节点禁止真实外部访问。

### 2.2 叶子 Fixture 的输入没有匹配语义

当前父 Flow 使用 `APPLY_CASE` 时，编译器读取叶子 Case 的输出 Material，但明确忽略叶子 Case 的输入。
这使叶子 Fixture 更像一个人工指定的输出盒子，而不是带适用条件的 Mock 桩。

### 2.3 Fixture Subject 不完整

当前 Fixture Subject 仅覆盖：

- `API_RESOURCE`
- `FLOW_DRAFT`
- `FLOW_VERSION`

Operator 和 Built-in Function 仍使用独立测试协议，无法进入统一 Fixture Set、Fixture Plan 和
Simulation Run 证据链。

### 2.4 Built-in Function 缺少调用点身份

Built-in Function 通常嵌在节点表达式中，不是独立 DAG 节点。同一节点可能多次调用同一个函数。
仅使用函数名作为 Fixture Target 会同时影响多个调用，无法准确模拟。

## 3. 设计结论

### 3.1 模拟命令拆成三个独立轴

`SimulationCommand v2` 固定由三个独立部分组成：

| 轴 | 含义 | 是否允许携带受保护 Material |
| --- | --- | --- |
| `subject` | 本次模拟的精确对象 | 否 |
| `input` | 本次调用输入的来源 | 仅业务输入；不得携带 Fixture 输出或凭证 |
| `fixturePlan` | 本次调用点如何选择已保存 Fixture | 否；只允许引用精确 Fixture revision |

`executionPolicy` 继续独立控制外部访问。Fixture 中的 `REAL` 不代表授权。

### 3.2 条件属于 Fixture Case，不属于模拟请求

Fixture 条件必须保存在受版本控制的 Fixture Case 中。模拟请求只能：

- 引用精确 `caseId`；
- 引用稳定 `conditionId`；
- 请求服务端使用真实调用输入进行自动匹配。

模拟请求不得上传任意条件表达式或任意 Mock 输出。否则服务端无法证明本次模拟使用了哪一个
受治理的 Fixture 版本，也无法稳定回放。

### 3.3 每次 Invocation 的解析结果必须固化为精确 Case

即使调用方使用 `conditionId` 或自动匹配，每个 Invocation evidence 也必须记录最终解析出的：

- `fixtureSetId`
- `revision`
- `fingerprint`
- `caseId`
- `target`
- `behavior`
- `fidelity`
- `matchedBy`

条件名称只是选择入口，不是最终证据。

### 3.4 与 1.3.0 的关系

本方案不是另建一套 Fixture 系统，而是把 1.3.0 已定义的创作与治理链扩展成调用方可复用的
执行协议：

```text
simulate sample
  -> pin
  -> 保存为 PRIVATE Fixture Case revision
  -> 可选晋级为 governed Fixture Asset
  -> 由 Simulation Command 精确选择或按条件匹配
  -> 编译为 Resolved Fixture Plan
  -> 按 Invocation 执行并生成证据
  -> 幂等累计治理资产 usage
```

1.3.0 负责回答“Fixture 如何从一次模拟结果沉淀为可治理资产、如何在工具主线中被看见”；
本文负责回答“调用方如何在一次 API、工具或 DAG 模拟中，安全且可回放地选择这些资产”。

以下边界保持不变：

- `ToolCoordinate` 只用于界面主线和路由，不进入 GraphDraft、Scenario、Fixture 或 Simulation wire contract。
- `sample` 和尚未保存的 `pinned` 状态是编辑态数据，不能成为外部调用方引用。
- 现有 `GraphDraft.nodeFixtures` 是运行适配层输入，不升级为 v2 公共协议。
- v2 只引用已保存的 Fixture Set revision；受保护输出继续由服务端 Material authority 解析。

### 3.5 融合后的双链模型

系统保留两条职责清晰、通过精确 revision 相接的链：

| 链 | 入口 | 终点 | 权威对象 |
| --- | --- | --- | --- |
| Fixture 创作与治理链 | 模拟结果 sample | PRIVATE Case 或 governed Asset | Fixture Set revision、Fixture Asset revision |
| Fixture 选择与执行链 | Simulation Command | Simulation Run evidence | Resolved Fixture Plan、Invocation Evidence |

创作链不能直接注入运行时状态；执行链不能上传任意 Mock 输出。两条链只通过精确 Fixture revision、
fingerprint 和受保护 Material reference 连接。

## 4. 领域术语

| 术语 | 定义 |
| --- | --- |
| Fixture Set | 绑定一个精确 Subject 的版本化 Fixture Case 集合 |
| Fixture Case | 一组可复用的 Driver Input、适用条件、运行 Controls 和可选 Expectation |
| Driver Input | 直接运行该 Fixture Case 时使用的 Subject 输入 |
| Fixture Condition | 用于判断某个运行时调用输入是否适用该 Case 的受限谓词 |
| Fixture Plan | 本次模拟中 Target 到 Fixture Selection 的完整映射 |
| Fixture Binding | Fixture Plan 中一个 Target 与一个 Selection 的绑定 |
| Target | Subject、DAG Node 或 Built-in Function Call Site |
| Selection | 精确 Case、命名条件或自动匹配策略 |
| Resolved Fixture Plan | 服务端将 Subject、Target、精确 Fixture revision、选择规则和策略闭包后形成的不可变运行计划 |
| Resolved Fixture Selection | 某个 Invocation 使用实际输入解析出的精确 Fixture Case 和 Behavior |
| Fixture Provenance | Fixture 从 `sample`、`pinned` 到 `governed` 的来源与治理状态 |
| Fixture Asset | 服务端治理、脱敏、审阅和激活后的受保护 Fixture Material |
| Call Site | 编译产物中某个可拦截调用位置的静态稳定身份 |
| Invocation Key | Runtime 为一次具体运行、尝试和调用序号生成的动态身份 |
| Unmatched Policy | 没有 Fixture Binding 的调用点应阻断还是尝试真实执行 |

## 5. 核心不变量

1. 一个 Simulation Command 只能指定一个精确 Subject。
2. `subject`、Fixture Subject 和 Target 解析结果必须处于同一可信 Scope。
3. 每个 Target 最多有一个 Fixture Binding。
4. 精确 Fixture 引用必须包含 revision 和 fingerprint。
5. `MATCH_CONDITION` 必须命中指定条件且该条件必须满足真实调用输入。
6. `AUTO_MATCH` 必须恰好命中一个 Case；零命中和多命中都阻断。
7. 默认 `unmatched=BLOCK`。
8. `unmatched=REAL` 不授予外部访问权限。
9. 外部写操作在模拟中始终禁止。
10. 调用方不能在 Simulation Command 中提交 Fixture 输出、凭证、Fixture Asset 内容或 Replay 内容。
11. 父 Target 被整体替代时，任何后代 Target Binding 都是冲突。
12. Built-in Function Fixture 必须定位到精确 Call Site，不能只按函数名全局替换。
13. 运行证据必须记录精确解析结果，不能只记录条件名称。
14. Fixture 条件和 Material 的变更必须产生新的 Fixture Set revision。
15. `ToolCoordinate`、画布坐标、组件 test id 和临时 AST 下标不得进入 v2 wire identity。
16. 调用方只能绑定静态 Target 或 Call Site，不能提交或猜测 Runtime `InvocationKey`。
17. 重试、循环、嵌套 Flow 和同一 Call Site 的多次调用必须产生不同 Invocation Key，并逐次解析、逐次记证据。
18. `sample` 或未保存的 `pinned` Fixture 不得被 Simulation Command 引用。
19. schema 或 runtime fingerprint 漂移的 Fixture 必须标记 `STALE`，不得参加条件匹配或自动匹配。
20. governed Fixture usage 按已提交的 `(runId, invocationKey, assetRef)` 幂等累计一次。

## 6. `SimulationCommand v2`

### 6.1 顶层结构

```ts
interface SimulationCommandV2 {
  schemaVersion: 'bloge.simulationCommand.v2';
  subject: ExactFixtureSubjectRefV2;
  input: SimulationInputSource;
  fixturePlan: FixturePlan;
  executionPolicy?: SimulationExecutionPolicy;
}
```

### 6.2 输入来源

```ts
type SimulationInputSource =
  | {
      kind: 'INLINE';
      value: unknown;
    }
  | {
      kind: 'CASE_INPUT';
      fixtureSet: ExactFixtureSetRef;
      caseId: string;
    };
```

`CASE_INPUT` 只读取 Case 的 `driverInput`，不隐式启用该 Case 的 Controls。调用方需要同时使用
Case 的 Controls 时，必须在 `fixturePlan` 中显式引用。

### 6.3 Fixture Plan

```ts
type FixturePlan =
  | { kind: 'NONE' }
  | {
      kind: 'CASE_CONTROLS';
      fixtureSet: ExactFixtureSetRef;
      caseId: string;
      unmatched: 'BLOCK' | 'REAL';
    }
  | {
      kind: 'BINDINGS';
      unmatched: 'BLOCK' | 'REAL';
      bindings: FixtureBinding[];
    };
```

语义如下：

| kind | 用途 |
| --- | --- |
| `NONE` | 不使用 Fixture；所有执行仍受 Execution Policy 限制 |
| `CASE_CONTROLS` | 将一个已保存 Case 的全部 Controls 作为可复用模拟方案 |
| `BINDINGS` | 调用方为本次运行逐个指定 Target 和 Fixture Selection |

### 6.4 精确 Fixture Set 引用

```ts
interface ExactFixtureSetRef {
  fixtureSetId: string;
  revision: number;
  fingerprint: string;
}
```

不提供“自动使用最新 revision”的运行协议。界面可以先查询 Head，再把精确坐标写入命令。

## 7. Fixture Binding

```ts
interface FixtureBinding {
  target: FixtureTarget;
  selection: FixtureSelection;
}
```

### 7.1 Target

```ts
type FixtureTarget =
  | { kind: 'SUBJECT' }
  | {
      kind: 'NODE_PATH';
      nodePath: string[];
    }
  | {
      kind: 'CALL_SITE';
      nodePath: string[];
      callSiteId: string;
    };
```

`nodePath` 使用从顶层 Subject 开始的层级节点路径。例如：

```json
{
  "kind": "NODE_PATH",
  "nodePath": ["risk-tool", "credit-provider"]
}
```

该路径表示顶层 Flow 的 `risk-tool` 节点展开后，其内部 `credit-provider` 节点。

### 7.2 Selection

```ts
type FixtureSelection =
  | {
      kind: 'EXACT_CASE';
      fixtureSet: ExactFixtureSetRef;
      caseId: string;
    }
  | {
      kind: 'MATCH_CONDITION';
      fixtureSet: ExactFixtureSetRef;
      conditionId: string;
    }
  | {
      kind: 'AUTO_MATCH';
      fixtureSet: ExactFixtureSetRef;
    };
```

选择语义：

| kind | 匹配方式 | 失败条件 |
| --- | --- | --- |
| `EXACT_CASE` | 强制选择一个精确 Case | Case 不存在、Subject 不匹配或 Material 不可用 |
| `MATCH_CONDITION` | 按 `conditionId` 找到唯一 Case，并使用真实调用输入验证条件 | 条件不存在、重复或不满足 |
| `AUTO_MATCH` | 评估该 Fixture Set 中全部 `when` | 零命中或多命中 |

`EXACT_CASE` 用于确定性回归和故障注入。它不要求 Case 的 `when` 满足本次输入，但仍执行输入和输出
Contract 校验。Simulation Run 必须把 `matchedBy` 记录为 `EXACT_CASE`，避免把强制注入误报为条件命中。

## 8. `FixtureSetCommand v2`

### 8.1 Case 结构

```ts
interface FixtureCaseV2 {
  caseId: string;
  name: string;
  driverInput?: unknown;
  when?: FixtureCondition;
  controls: FixtureControl[];
  expect?: { output: unknown };
}
```

`driverInput` 和 `when` 是不同概念：

- `driverInput` 用于直接运行 Case。
- `when` 用于该 Case 被其他运行引用时匹配实际调用输入。

### 8.2 Fixture Condition

```ts
interface FixtureCondition {
  conditionId: string;
  all: FixturePredicate[];
}

type FixturePredicate =
  | { path: string; operator: 'EQ'; value: unknown }
  | { path: string; operator: 'IN'; values: unknown[] }
  | { path: string; operator: 'PRESENT' }
  | { path: string; operator: 'ABSENT' }
  | {
      path: string;
      operator: 'NUMBER_RANGE';
      minimum?: number;
      maximum?: number;
    };
```

v2 首版不支持：

- 正则表达式；
- 任意脚本；
- SpEL、JavaScript 或 DSL 表达式；
- 网络查询；
- 根据 Secret、Credential 或受保护 Material 进行匹配；
- 自定义 Predicate 插件。

### 8.3 条件求值规则

1. `path` 使用受限 JSONPath，只支持 `$` 和对象属性路径，如 `$.customer.level`。
2. 数组过滤、递归下降、函数调用和通配符均不支持。
3. 条件只读取当前 Target 的实际输入。
4. `all` 为空视为非法配置，不等价于 Always Match。
5. 一个 Fixture Set 内 `conditionId` 必须唯一。
6. 条件求值不得读取其他节点输出、环境变量或身份信息。
7. 条件编译结果必须进入 Fixture fingerprint。

### 8.4 示例

```json
{
  "caseId": "vip-customer",
  "name": "VIP customer",
  "driverInput": {
    "customerId": "customer-1001",
    "customerLevel": "VIP",
    "region": "SG"
  },
  "when": {
    "conditionId": "vip-customer",
    "all": [
      {
        "path": "$.customerLevel",
        "operator": "EQ",
        "value": "VIP"
      },
      {
        "path": "$.region",
        "operator": "IN",
        "values": ["SG", "HK"]
      }
    ]
  },
  "controls": [
    {
      "target": { "kind": "SUBJECT" },
      "behavior": {
        "kind": "RETURN",
        "material": {
          "kind": "FIXTURE_ASSET",
          "fixtureAssetId": "profile-vip-output",
          "revision": 2,
          "schemaFingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        }
      },
      "fidelity": "OUTPUT_LEVEL"
    }
  ]
}
```

## 9. Subject 扩展

### 9.1 统一 Subject

```ts
type ExactFixtureSubjectRefV2 =
  | ApiResourceSubject
  | FlowDraftSubject
  | FlowVersionSubject
  | OperatorSubject
  | BuiltInFunctionSubject;
```

新增 Subject：

```ts
interface OperatorSubject {
  kind: 'OPERATOR_VERSION';
  libraryId: string;
  libraryRevision: number;
  operatorRef: string;
  contractFingerprint: string;
}

interface BuiltInFunctionSubject {
  kind: 'BUILTIN_FUNCTION_VERSION';
  catalogId: string;
  catalogRevision: number;
  functionName: string;
  signatureFingerprint: string;
  runtimeFingerprint: string;
}
```

### 9.2 Operator 规则

- Operator 可作为独立 Subject 运行 Fixture Case。
- Operator 作为 DAG 节点时使用 `NODE_PATH` 定位。
- Operator Fixture 首版只支持 `OUTPUT_LEVEL`。
- 只有 Operator Runtime 明确支持协议或传输模拟时，才能开放更高 Fidelity。

### 9.3 Built-in Function 规则

- 纯函数默认真实执行，不需要 Fixture 才能运行。
- 调用方显式配置时，可使用 `RETURN`、`ERROR` 或 `TIMEOUT`。
- Built-in Function 作为独立 Subject 时，`driverInput` 表示参数对象或规范化参数数组。
- Built-in Function 在 DAG 中必须通过 `CALL_SITE` 定位。
- 同名但签名不同的函数不能共享 Fixture Set。
- Runtime fingerprint 变化后，旧 Fixture Set 状态应转为 `STALE`。

## 10. 稳定 Call Site

### 10.1 必要性

以下表达式包含两个同名调用：

```text
lookup(customerId) + lookup(referrerId)
```

按函数名 Mock 会同时替换两个调用，不能表达一个成功、一个超时。

### 10.2 Call Site 身份要求

编译器必须为每次函数调用生成并持久化 `callSiteId`。该身份满足：

1. 在一个精确 Flow 或 Operator revision 内唯一。
2. 代码格式、画布布局和无语义元数据变化不改变身份。
3. 调用表达式或参数绑定发生语义变化时改变身份。
4. 不使用源码行号、数组位置或临时 AST 下标作为 wire identity。
5. 编译产物可以列出 Call Site、Callable Subject 和输入/输出 Contract。

Call Site authority 是 Built-in Function Fixture 进入统一 Simulation 前的实现前置条件。

### 10.3 静态 Call Site 与动态 Invocation Key

1.3.0 中节点和函数调用的可见身份只解决“代码里的哪个位置”。实际运行还必须区分“这个位置的
第几次调用”。因此 v2 严格分离：

```ts
interface InvocationIdentity {
  invocationKey: string;       // 服务端生成的 opaque identity
  runId: string;
  target: FixtureTarget;       // 静态 Target / Call Site
  attempt: number;             // 重试或恢复尝试
  ordinal: number;             // 同一静态调用点在本次尝试中的调用序号
  parentInvocationKey?: string;
}
```

- Fixture Plan 只绑定静态 `target`。
- Runtime 在每次即将调用前创建 `InvocationKey`，再用该次实际输入解析条件。
- `foreach`、重试、递归或嵌套 Flow 不共享 Invocation Key。
- Resume 必须恢复同一个 Resolved Fixture Plan fingerprint 和已提交的 Invocation evidence；不得重新自动匹配后覆盖历史结果。
- 调用方提交 `invocationKey` 一律按非法字段拒绝。

## 11. 三类调用示例

### 11.1 API Resource：按条件模拟

```json
{
  "schemaVersion": "bloge.simulationCommand.v2",
  "subject": {
    "kind": "API_RESOURCE",
    "resourceId": "customer.get-profile",
    "revision": 3,
    "fingerprint": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  },
  "input": {
    "kind": "INLINE",
    "value": {
      "customerId": "customer-1001",
      "customerLevel": "VIP",
      "region": "SG"
    }
  },
  "fixturePlan": {
    "kind": "BINDINGS",
    "unmatched": "BLOCK",
    "bindings": [
      {
        "target": { "kind": "SUBJECT" },
        "selection": {
          "kind": "MATCH_CONDITION",
          "fixtureSet": {
            "fixtureSetId": "customer-profile-fixtures",
            "revision": 3,
            "fingerprint": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
          },
          "conditionId": "vip-customer"
        }
      }
    ]
  },
  "executionPolicy": {
    "externalReads": { "kind": "DENY" },
    "externalWrites": { "kind": "DENY" }
  }
}
```

### 11.2 多 API DAG：逐节点选择 Fixture

```json
{
  "schemaVersion": "bloge.simulationCommand.v2",
  "subject": {
    "kind": "FLOW_VERSION",
    "publicationId": "customer-risk-tool",
    "revision": 4,
    "fingerprint": "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
  },
  "input": {
    "kind": "INLINE",
    "value": { "customerId": "customer-1001" }
  },
  "fixturePlan": {
    "kind": "BINDINGS",
    "unmatched": "BLOCK",
    "bindings": [
      {
        "target": {
          "kind": "NODE_PATH",
          "nodePath": ["profile"]
        },
        "selection": {
          "kind": "AUTO_MATCH",
          "fixtureSet": {
            "fixtureSetId": "profile-fixtures",
            "revision": 7,
            "fingerprint": "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
          }
        }
      },
      {
        "target": {
          "kind": "NODE_PATH",
          "nodePath": ["credit"]
        },
        "selection": {
          "kind": "EXACT_CASE",
          "fixtureSet": {
            "fixtureSetId": "credit-fixtures",
            "revision": 5,
            "fingerprint": "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
          },
          "caseId": "provider-timeout"
        }
      }
    ]
  }
}
```

### 11.3 Built-in Function：精确 Call Site

```json
{
  "target": {
    "kind": "CALL_SITE",
    "nodePath": ["pricing"],
    "callSiteId": "discount-lookup-1"
  },
  "selection": {
    "kind": "EXACT_CASE",
    "fixtureSet": {
      "fixtureSetId": "discount-function-fixtures",
      "revision": 2,
      "fingerprint": "sha256:1111111111111111111111111111111111111111111111111111111111111111"
    },
    "caseId": "holiday-discount"
  }
}
```

## 12. Reusable Flow 与嵌套工具语义

### 12.1 整体替代

当 `NODE_PATH` 指向一个 Reusable Flow 节点，并选择该 Flow Subject 的 Whole-flow Return Case 时：

- 该节点整体标记为 `MOCKED`；
- 子 Flow 不展开；
- 子 Flow 内部节点和 Call Site 不执行；
- Simulation Run 记录 Whole-flow Fixture Case。

### 12.2 展开执行

当父 Flow 节点没有整体替代，并且 Unmatched Policy 与 Execution Policy 允许真实本地执行时：

- 子 Flow 按精确发布版本展开；
- 后代 Target 使用层级 `nodePath` 定位；
- API Resource 的真实访问仍需显式 `ALLOW_EXACT`；
- Operator 和纯 Built-in Function 可以真实本地执行。

### 12.3 重叠冲突

以下绑定组合非法：

- 绑定 `nodePath=["risk-tool"]`，同时绑定 `nodePath=["risk-tool", "credit"]`；
- 绑定一个节点整体，同时绑定该节点内的 Call Site；
- 同一 Target 出现两个 Selection；
- 一个 Fixture Plan 同时通过 `CASE_CONTROLS` 和 `BINDINGS` 提供控制。

### 12.4 Decision Scenario 边界

1.3.0 已冻结的 Decision Scenario 语义继续保留：

- 决策表枚举产生的 Scenario 默认 `dependencies=[]`，表示业务样例和期望，不自动自我 Mock。
- Given/Then 与 Fixture `when` 不互相转换：前者是业务断言，后者是调用时选择 Mock 的适用条件。
- “Use expected output as Return fixture” 是用户显式创作动作，必须保存为新的 PRIVATE Fixture Case revision。
- OPERATOR 契约用 canonical `operatorRef`，GRAPH 契约用 `nodeId`；编译器再投影为 v2 静态 Target。
- expected output 必须深拷贝，不能携带 Credential、Fixture Asset Material 或 Replay 内容。
- 保存完成前只是编辑态 override，不能被其他 Simulation Command 引用。

Scenario Compiler 的职责是把显式保存的 dependency 编译成 Fixture Binding；它不能把 Decision Table
predicate 自动改写成 Fixture Condition，也不能为了让断言通过而自动生成 Return Fixture。

## 13. `FixturePlanCompiler` 深模块

### 13.1 Interface

```java
ResolvedFixturePlan compile(
        AuthoringScope scope,
        ExactSubject subject,
        JsonNode input,
        FixturePlan fixturePlan,
        ExecutionPolicy executionPolicy);
```

调用方和 Transport 只依赖这个 Interface。以下复杂度全部隐藏在 Implementation 中：

- 精确 Subject 加载；
- Flow 展开与 Target 解析；
- Call Site authority 校验；
- Fixture revision/fingerprint 校验；
- Condition 编译与求值；
- Case 唯一性和 Subject 闭包；
- Target 重叠检测；
- Unmatched Policy；
- Egress Policy；
- Material 解析权限；
- Kernel control 投影；
- 运行证据投影。

前端、Graph Author、Scenario Workspace 和 Simulation Controller 不得各自实现一份 Fixture 合并逻辑。

### 13.2 编译流程

```mermaid
flowchart LR
    A[Validate command] --> B[Load exact subject]
    B --> C[Resolve input]
    C --> D[Build invocation topology]
    D --> E[Resolve targets]
    E --> F[Load exact fixture revisions]
    F --> G[Evaluate selections and conditions]
    G --> H[Check overlaps and unmatched policy]
    H --> I[Enforce egress and material authority]
    I --> J[Produce immutable Resolved Fixture Plan]
    J --> K[Execute kernel]
    K --> L[Persist Simulation Run evidence]
```

### 13.3 条件求值时点

Flow 节点的条件不能在请求入口统一求值，因为后续节点输入可能来自前序节点输出。

条件求值发生在每个 Target 即将调用之前：

1. DAG Mapping 生成实际节点输入。
2. Fixture Plan Compiler 读取该 Target 的 Selection。
3. `MATCH_CONDITION` 或 `AUTO_MATCH` 使用实际节点输入求值。
4. 解析成精确 Case 和 Behavior。
5. 执行 Fixture 行为或真实调用。

### 13.4 每次 Invocation 的解析闭包

`ResolvedFixturePlan` 冻结“允许哪些静态 Target 使用哪些精确 Fixture Set revision”；对于条件选择，
最终 `caseId` 仍在每个 Invocation 输入产生后解析。每次解析必须：

1. 使用当前 Invocation 的实际输入和静态 Target。
2. 只读取计划中已钉住的 Fixture Set revision。
3. 校验 Fixture、Target Contract 和 runtime fingerprint 未漂移。
4. 将选中的 Case、Behavior、Fidelity 和匹配原因写入该 Invocation evidence。
5. 在调用前完成 fail-closed 校验；零命中、多命中、STALE 或不支持的 Fidelity 都不进入 Kernel。

该闭包避免循环第二次调用复用第一次命中结果，也避免重试时静默切换到新的 Fixture Head。

## 14. 行为与 Fidelity

| Behavior | API Resource | Flow | Operator | Built-in Function |
| --- | --- | --- | --- | --- |
| `REAL` | 需外部访问授权 | 本地展开 | 本地执行 | 本地执行 |
| `RETURN` | 支持 | 支持整体返回 | 支持 | 支持 |
| `ERROR` | 支持 | 支持整体错误 | 支持 | 支持 |
| `TIMEOUT` | 支持 | 支持整体超时 | 支持 | 支持 |
| `REPLAY` | 支持 | 支持受控回放 | 依 Runtime 能力 | 仅确定性受控回放 |
| `APPLY_CASE` | 作为持久 Fixture Plan 引用 | 作为节点复用 | 可引用 Operator Case | 可引用 Function Case |

Fidelity：

- API Resource 可支持 `OUTPUT_LEVEL`、`PROTOCOL_DERIVED`、`TRANSPORT_LEVEL`。
- Flow、Operator 和 Built-in Function 首版只支持 `OUTPUT_LEVEL`。
- 更高 Fidelity 必须由对应 Runtime 显式声明能力，不能由调用方自行选择后假装支持。

## 15. Simulation Run v2 证据

### 15.1 顶层增加字段

```ts
interface SimulationRunV2 {
  schemaVersion: 'bloge.simulationRun.v2';
  runId: string;
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'BLOCKED';
  subject: ExactFixtureSubjectRefV2;
  requestFingerprint: string;
  resolvedFixturePlanFingerprint: string;
  output?: unknown;
  invocations: InvocationEvidence[];
  verdicts: SimulationVerdicts;
  diagnostics: SimulationDiagnostic[];
}
```

### 15.2 Invocation Evidence

```ts
interface InvocationEvidence {
  invocationKey: string;
  parentInvocationKey?: string;
  target: FixtureTarget;
  subject: ExactFixtureSubjectRefV2;
  status: 'COMPLETED' | 'FAILED' | 'BLOCKED' | 'SKIPPED';
  execution: 'REAL' | 'MOCKED';
  matchedBy: 'NONE' | 'EXACT_CASE' | 'CONDITION' | 'AUTO_MATCH';
  fixtureCase?: {
    fixtureSetId: string;
    revision: number;
    fingerprint: string;
    caseId: string;
  };
  behavior?: 'RETURN' | 'ERROR' | 'TIMEOUT' | 'REPLAY';
  fidelity?: 'OUTPUT_LEVEL' | 'PROTOCOL_DERIVED' | 'TRANSPORT_LEVEL';
  provenance?: 'PINNED_PRIVATE' | 'GOVERNED_ASSET' | 'REPLAY';
  fixtureAssetRef?: {
    fixtureAssetId: string;
    revision: number;
    schemaFingerprint: string;
  };
  inputFingerprint: string;
  outputFingerprint?: string;
  egress: EgressEvidence;
}
```

证据中不得包含：

- Credential；
- Secret Reference；
- Request/Response Header；
- 未脱敏业务 payload；
- Fixture Asset 内容；
- Replay 内容；
- Error Behavior 的敏感 message。

### 15.3 四维结论

沿用 1.3.0 的诚实结论模型，不用一个泛化 `PASSED` 掩盖不同证明边界：

```ts
interface SimulationVerdicts {
  execution: 'PASSED' | 'FAILED' | 'BLOCKED';
  assertions: 'PASSED' | 'FAILED' | 'NOT_CHECKED';
  contract: 'VALID' | 'INVALID' | 'NOT_CHECKED';
  governance: 'PASSED' | 'FAILED' | 'NOT_CHECKED';
  aggregate: 'READY' | 'NOT_READY';
}
```

- Fixture 条件命中只证明选择规则成立，不证明业务断言通过。
- Fixture 返回成功只证明 Execution 按计划完成，不证明真实 Provider 可用。
- 只有四维全部满足对应门槛时，`aggregate` 才能为 `READY`。
- 未执行断言、契约或治理检查时必须显示 `NOT_CHECKED`，不能折叠成 Passed。

## 16. HTTP 协议

继续使用：

```http
POST /api/authoring/simulations
Content-Type: application/json
Idempotency-Key: <caller-generated-key>
X-Purpose: AUTHORING_SIMULATION_RUN
```

成功响应必须包含：

```http
Cache-Control: no-store
Pragma: no-cache
```

相同 Scope、Actor、Idempotency Key 和请求 fingerprint 返回同一个不可变 Simulation Run。
同一个 Idempotency Key 对应不同请求返回冲突。

### 16.1 错误语义

| HTTP | Code | 含义 |
| --- | --- | --- |
| 400 | `SIMULATION_COMMAND_INVALID` | schema、Target、Binding 或字段格式非法 |
| 401 | `SIMULATION_AUTHENTICATION_REQUIRED` | 缺少可信身份 |
| 403 | `SIMULATION_FORBIDDEN` | Actor 无模拟或受保护 Material 权限 |
| 404 | `SIMULATION_REFERENCE_NOT_FOUND` | Subject、Fixture Set、Case 或 Call Site 不可见 |
| 409 | `SIMULATION_IDEMPOTENCY_CONFLICT` | 同一幂等键对应不同命令 |
| 422 | `FIXTURE_SUBJECT_MISMATCH` | Fixture Subject 与 Target Subject 不一致 |
| 422 | `FIXTURE_CONDITION_NOT_SATISFIED` | 指定条件不满足实际输入 |
| 422 | `FIXTURE_AUTO_MATCH_EMPTY` | 自动匹配没有命中 |
| 422 | `FIXTURE_TARGET_OVERLAP` | 父 Target 与后代 Target 或重复 Target 冲突 |
| 424 | `FIXTURE_MATERIAL_UNAVAILABLE` | 受保护 Material 或 Replay authority 不可用 |
| 503 | `SIMULATION_BUSY` | 相同幂等命令仍在执行 |

错误响应只返回稳定 code、安全 detail、correlation id 和恢复建议，不返回 payload 或 Material 信息。

## 17. 安全与治理

### 17.1 Scope 与权限

- tenant、project、environment、actor 和 purpose 全部来自服务端可信身份。
- Fixture Set、Fixture Asset、Subject 和 Simulation Run 均按同一 Scope 查询。
- 无权访问与不存在统一映射为 404，避免枚举跨 Scope 对象。
- `TEAM_AVAILABLE` 或更高治理级别 Material 需要独立 Material Read Purpose。

### 17.2 外部访问

- 默认 `DENY` 外部读写。
- API Resource 的 `REAL` 仅表示不应用 Fixture，不表示允许网络访问。
- 外部 Read 需要 `ALLOW_EXACT`、只读 Resource 契约和服务端授权共同成立。
- 外部 Write 始终拒绝。
- `FIXTURE_ONLY_WRITE` Resource 只能使用 Fixture，不能真实调用。

### 17.3 条件安全

- 条件只读取当前 Target 的运行输入。
- 条件值进入 Fixture fingerprint，但不得写入日志和错误。
- 条件不允许访问身份、Secret、系统时间、随机数或网络。
- Condition Compiler 必须限制路径深度、Predicate 数量和集合大小。

## 18. 持久化影响

### 18.1 Fixture Set

Fixture Set revision JSON 增加 `driverInput` 和 `when`。现有 JSON authority 可以存储新结构，但 readiness、
fingerprint 和 schema validation 必须识别 v2。

v1 迁移规则：

- `input` 映射为 `driverInput`；
- `when` 缺失；
- v1 Case 只能使用 `EXACT_CASE`；
- v1 Case 不参与 `MATCH_CONDITION` 和 `AUTO_MATCH`。

迁移不推断条件，避免把示例输入误当成业务匹配规则。

### 18.2 Simulation Run

现有 `rg_authoring_simulation_runs.run_json` 可以保存 v2 Run，但应增加可查询列：

- `subject_kind`
- `subject_id`
- `subject_revision`
- `resolved_plan_fingerprint`

索引至少支持：

- Scope + Subject + started_at；
- Scope + resolved_plan_fingerprint + started_at；
- Scope + status + lease_until recovery。

新增 migration 必须向前追加，不修改 V001–V018。

### 18.3 Operator 与 Function authority

实现前必须具备：

- 可按精确 library/catalog revision 读取 Operator/Function Contract；
- 可校验 contract/signature/runtime fingerprint；
- 可列出编译后的稳定 Call Site；
- authority 变化后可将关联 Fixture 标为 `STALE`。

### 18.4 1.3.0 Fixture provenance 与治理资产桥接

| 编辑态/治理态 | v2 中的含义 | 是否可被外部 Simulation Command 引用 |
| --- | --- | --- |
| `sample` | 一次 Simulation Run 的临时输出 | 否 |
| `pinned` 未保存 | 当前编辑会话中的待保存 Fixture | 否 |
| `pinned` 已保存 | PRIVATE Fixture Case revision，Material 为服务端保存的 INLINE 内容 | 是，按精确 Fixture Set revision 引用 |
| `governed` | Fixture Case 的 Material 指向精确 ACTIVE Fixture Asset revision | 是，需 Material Read 权限 |
| `STALE` | Subject schema 或 runtime fingerprint 已漂移 | 否，直到重捕获或产生兼容的新 revision |

Pin 动作必须返回可见保存回执，至少包含 `fixtureSetId`、`revision`、`fingerprint` 和 `caseId`。
Promote 不得原地改写旧 Case；它创建 Fixture Asset revision，并创建新的 Fixture Set revision，将 Material
引用切换到该 Asset。旧 revision 保持不可变，已有 Simulation Run 仍可审计。

治理资产的 `usageCount` 只在 Simulation Run 与 Invocation evidence 成功提交后增加，并以
`(runId, invocationKey, fixtureAssetId, assetRevision)` 唯一约束防止重试重复累计。

## 19. 前端交互

### 19.1 模拟面板

模拟面板拆成四区：

1. **Input**：Inline 输入或从 Case 取 Driver Input。
2. **Fixture Plan**：None、Saved Case Controls 或 Per-target Bindings。
3. **Execution Policy**：默认隐藏高级配置，明确显示真实外部访问状态。
4. **Resolved Evidence**：展示实际命中的精确 Fixture Case 和每个调用点执行方式。

### 19.2 Target Binding

Flow 页面按拓扑展示：

- 节点名称和精确 Subject；
- 当前 Selection；
- 可用 Fixture Set；
- 可选 Case/Condition；
- 匹配预览；
- 当前 Unmatched Policy；
- 是否会发生真实外部访问。

Built-in Function 使用节点下的 Call Site 子列表，不混入普通 DAG Node 列表。

### 19.3 防误操作

- `AUTO_MATCH` 在保存或运行前显示零命中、多命中诊断。
- `unmatched=REAL` 必须显示真实执行警告。
- 对外部 API 的真实 Read 必须二次确认并显示允许清单。
- 不提供在模拟面板直接粘贴 Mock 输出的快捷入口。
- 需要新 Mock 输出时，引导到 Fixture Object 创建新 revision。

### 19.4 融入 1.3.0 工具主线

v2 不新增平行的 Fixture Studio。它进入 1.3.0 已定义的对象主线：

- **Feed**：捕获 sample、Pin、保存 PRIVATE Case、Promote、选择 Case/Condition、配置 Fixture Plan。
- **Prove**：展示 Resolved Plan、逐 Invocation evidence、四维结论、真实外部访问决定和治理状态。
- API Resource、Reusable Flow、Operator 和 Built-in Function 使用同一 Fixture Plan 交互模型。
- Flow 页面按 DAG 展开节点；Function 只作为所属节点下的 Call Site 展示。

`ToolCoordinate` 可以继续驱动 Thread Rail、Breadcrumb 和 UI 定位，但只能以 props 或 `data-tool-*`
存在。Fixture Plan 保存时必须转换为 Subject/Target 的正式 authority，不能把路由 query 或画布坐标写入协议。

## 20. 可观测性

建议指标：

- `rg_simulation_runs_total{subject_kind,status}`
- `rg_fixture_resolution_total{selection_kind,outcome}`
- `rg_fixture_condition_ambiguous_total{subject_kind}`
- `rg_fixture_condition_empty_total{subject_kind}`
- `rg_simulation_invocations_total{subject_kind,execution,behavior}`
- `rg_simulation_external_reads_total{decision,outcome}`
- `rg_fixture_stale_reference_total{subject_kind}`

日志只记录：

- run id；
- Scope 的安全标识；
- Subject 坐标；
- Fixture/Case 坐标；
- Target；
- 稳定错误码；
- correlation id。

日志不得记录业务输入、Fixture 输出、条件值、Secret 或 Header。

## 21. 兼容与演进

### 21.1 v1 保留策略

- v1 endpoint payload 在迁移期继续接受。
- `FIXTURE_CASE` 转换为：`CASE_INPUT + CASE_CONTROLS`。
- v1 `AD_HOC` 转换为：`INLINE + fixturePlan=NONE`。
- v1 运行证据仍按 v1 schema 返回，或由客户端显式请求 v2 media type。
- v1 不获得条件匹配、Operator Subject 或 Function Call Site 新能力。

### 21.2 不兼容边界

以下行为不做隐式兼容：

- 不把 v1 `input` 自动推断为 `when`；
- 不把 Fixture Head 自动解析为精确 revision；
- 不把函数名自动扩展为全部 Call Site；
- 不把缺失 Binding 自动解释为 `REAL`；
- 不把多 Case 命中按顺序选择第一条。

### 21.3 加法式发布与回滚

沿用 1.3.0 的对象主线 overlay 策略：

- v2 协议、Fixture Plan 编辑器和 Invocation evidence 由独立 feature switch 门控。
- switch 关闭时不挂载新组件，v1 请求、证据和页面行为保持不变。
- 先在 API Resource 对象页启用，再扩展 Flow、Operator、Built-in Function。
- 不在 `AuthorCanvas` 中复制 Fixture 编译逻辑；所有入口调用同一个 `FixturePlanCompiler`。
- 回滚只关闭 v2 入口，不删除已保存 Fixture revision 或 Simulation Run evidence。

## 22. 实施阶段

### 阶段 S0：融合 1.3.0 资产链

- 冻结 `sample -> pinned -> PRIVATE Case -> governed Asset` 的状态与保存回执。
- 明确 Pin、Promote 和 governed Material 的 revision 不可变语义。
- 复用现有三种 Resource Fidelity、schema stale 检测和 usage 计数能力。
- 冻结静态 Call Site 与动态 Invocation Key 的职责边界。
- 保持 feature switch 关闭时 v1 UI 和协议不变。

验收：编辑态临时输出不能被外部引用；保存后的 Case 可精确引用；Promote 不改写历史 revision。

### 阶段 S1：Schema 与纯编译模型

- 冻结 `SimulationCommand v2` JSON Schema。
- 冻结 `FixtureSetCommand v2` 的 `driverInput` 和 `when`。
- 实现受限 Condition Compiler。
- 实现 Target、Selection、重叠与唯一匹配验证。
- 不接 Runtime，不修改 UI。

验收：所有 schema 正反例、条件边界和 Plan 编译测试通过。

### 阶段 S2：API Resource

- 支持 API Resource `SUBJECT`。
- 支持 `EXACT_CASE`、`MATCH_CONDITION` 和 `AUTO_MATCH`。
- 支持 `RETURN`、`ERROR`、`TIMEOUT`、`REPLAY`。
- 持久化 Simulation Run v2 证据。

验收：调用方可提交业务输入并选择/匹配 Fixture，默认零网络访问。

### 阶段 S3：Flow 与多 API DAG

- 支持 `NODE_PATH`。
- 支持 `CASE_CONTROLS` 作为可复用工具模拟方案。
- 支持局部 Fixture 和 Unmatched Policy。
- 支持嵌套 Flow 的整体替代与展开执行。
- 每次循环、重试和嵌套调用生成独立 Invocation Key 并逐次匹配。

验收：同一个工具运行中，不同 API 节点可选择不同 Case，并产生逐节点证据。

### 阶段 S4：Operator

- 增加 `OPERATOR_VERSION` Subject authority。
- Operator Fixture Set authoring、查询和 Simulation。
- Flow 中 Operator Node Binding。

验收：Operator 可独立模拟，也可在工具 DAG 中被精确替代。

### 阶段 S5：Built-in Function

- 增加 `BUILTIN_FUNCTION_VERSION` Subject authority。
- 编译并持久化稳定 Call Site。
- 支持 Function Fixture 和 `CALL_SITE` Binding。

验收：同一节点的两个同名函数调用可以使用不同 Fixture，互不影响。

### 阶段 S6：前端与操作手册

- Input 与 Fixture Plan 独立编辑。
- 节点/Call Site Fixture 选择器。
- Condition 编辑器和匹配预览。
- Resolved Evidence 展示。
- 接入 1.3.0 的 Feed/Prove 对象主线与四维结论。
- 接入 sample、Pin、Promote、governed picker 和 stale 提示，不新增平行 Fixture Studio。
- 更新 API Resource、Flow、Operator、Function 操作手册和截图。

### 阶段 S7：Decision Scenario 桥接

- 保持决策表枚举 Scenario 的 `dependencies=[]`。
- 通过可见“Use expected output as Return fixture”动作创建 PRIVATE Case revision。
- Scenario Compiler 将显式 dependency 投影为 v2 Fixture Binding。
- 四维结果分别展示 Execution、Assertions、Contract 和 Governance。

验收：没有自动自 Mock；保存的 Return Fixture 可复用；一次 Fixture 成功不会被泛化成工具 Ready。

## 23. 测试矩阵

### 23.1 Schema 与领域模型

- 三种 Fixture Plan 联合类型互斥。
- Target 联合类型互斥。
- Selection 联合类型互斥。
- Predicate 路径、数量、深度、值大小边界。
- JSON defensive copy 与日志脱敏。

### 23.2 Fixture Plan Compiler

- 精确 Case 正常解析。
- conditionId 唯一且满足。
- conditionId 不满足。
- AUTO_MATCH 零命中。
- AUTO_MATCH 多命中。
- Fixture Subject 与 Target Subject 不一致。
- 重复 Target。
- 父 Target 与后代 Target 重叠。
- v1 Case 不参与条件匹配。
- protected Material 无权限时 fail-closed。
- STALE Fixture 不参与条件匹配或自动匹配。
- retry、foreach 和嵌套调用按不同 Invocation Key 独立解析。
- Resume 只能恢复同一 Resolved Plan fingerprint，不重新匹配 Fixture Head。

### 23.3 API Resource

- Inline 输入 + Subject Fixture。
- CASE_INPUT + CASE_CONTROLS。
- `unmatched=BLOCK` 无网络访问。
- `unmatched=REAL` 但 policy deny 时阻断。
- `ALLOW_EXACT` 仅允许精确只读 Resource。
- `FIXTURE_ONLY_WRITE` 永不真实调用。

### 23.4 Flow

- 全节点 Fixture。
- 部分节点 Fixture + 其余本地执行。
- 嵌套 Flow 整体替代时内部节点不执行。
- 嵌套 Flow 展开后的层级路径解析。
- 前序 Fixture 输出进入后序节点条件匹配。
- DAG 拓扑与 output contract 验证。
- 同一 Call Site 多次 Invocation 可以因实际输入命中不同 Case，证据互不覆盖。

### 23.5 Operator 与 Function

- Operator 独立 Subject Fixture。
- Operator DAG Node Fixture。
- Function 独立 Subject Fixture。
- 同名不同签名不串用。
- 同节点两个同名 Call Site 分别绑定。
- Call Site authority 漂移后 Fixture `STALE`。
- 调用方提交 Invocation Key 被 schema 拒绝。

### 23.6 创作、治理与 Scenario 桥接

- sample 只能 Pin，不能直接进入 Simulation Command。
- Pin 保存回执产生精确 PRIVATE Fixture Case revision。
- Promote 创建新的 Fixture Asset 和 Fixture Set revision，不改写旧 revision。
- governed Fixture 每个已提交 Invocation 只累计一次 usage。
- schema/runtime fingerprint 漂移后 picker 显示 STALE，运行 fail-closed。
- Decision Scenario 枚举保持 `dependencies=[]`。
- 显式 expected Return Fixture 保存后才能被 Scenario Compiler 使用。
- Given/Then predicate 不会被自动转换成 Fixture Condition。
- 四维 verdict 独立计算，缺失维度保持 `NOT_CHECKED`。

### 23.7 HTTP 与安全

- 401/403 在读取 Fixture Material 前拒绝。
- 跨 Scope 引用统一 404。
- 幂等 Replay 返回同一 Resolved Plan 和 Run。
- 同一幂等键不同 Plan 返回 409。
- 错误、日志和证据不泄漏输入、输出、条件值或 Material。

### 23.8 UI 与兼容

- feature switch 关闭时不挂载 v2 组件，v1 交互和请求保持不变。
- Feed 中完成 capture、Pin、Promote、选择和 Plan 保存。
- Prove 中展示逐 Invocation evidence 与四维 verdict。
- 1280px 真实浏览器完成 API 导入、DAG 编排、Fixture 沉淀、跨工具复用和模拟。
- happy path 不要求粘贴手写 JSON，也不出现新的全屏 Fixture Studio。

## 24. 验收标准

方案完成必须同时满足：

1. 调用方可以提交本次输入并选择精确 Fixture Case。
2. 调用方可以通过稳定 conditionId 请求条件匹配。
3. 服务端可以使用真实节点输入进行唯一自动匹配。
4. 一个 DAG 中不同节点可以使用不同 Fixture。
5. 整个工具的 Fixture Plan 可以作为 Case Controls 保存和复用。
6. API Resource、Flow、Operator 和 Built-in Function 使用同一 Fixture 领域模型。
7. Built-in Function 使用稳定 Call Site，不发生按函数名全局误替换。
8. 默认情况下没有真实网络访问。
9. 每个 Mock 调用都能追溯到精确 Fixture Set revision 和 Case。
10. 运行结果明确区分强制 Case、条件命中、自动匹配和真实执行。
11. 零命中、多命中、Target 重叠和 Subject 不匹配全部 fail-closed。
12. 受保护 Material、Credential 和业务 payload 不进入日志或安全错误。
13. sample、Pin、Promote、PRIVATE Case 和 governed Asset 形成一条可见且可审计的资产链。
14. 每个运行时调用都有独立 Invocation Key；重试、循环和嵌套调用的证据不互相覆盖。
15. governed Fixture usage 按已提交 Invocation 幂等累计，失败和重放不重复计数。
16. Decision Scenario 只有在用户显式保存 Return Fixture 后才产生 Mock control。
17. Execution、Assertions、Contract 和 Governance 分维展示，只有全部满足门槛才显示 Ready。
18. v2 关闭时，1.3.0 之前的 v1 协议与界面保持原样。

## 25. 待确认决策

以下决策需要在进入 S1 实现前确认：

1. 是否接受将 `input` 与 `fixturePlan` 作为永久独立的两个轴。
2. 是否接受运行请求只能引用已保存 Fixture，不允许直接上传 Mock 输出。
3. 是否保留 `AUTO_MATCH`，还是 v2 首版只提供 `EXACT_CASE` 和 `MATCH_CONDITION`。
4. 是否允许 `EXACT_CASE` 忽略 `when`，用于故障注入；本文建议允许并在证据中明确标记。
5. `unmatched=REAL` 是否在普通界面出现；本文建议仅在高级入口出现。
6. Built-in Function Call Site authority 是否作为 S5 前置单独冻结。
7. Pin 是否直接保存 PRIVATE Fixture Case；本文建议一次可见动作完成 Pin 与保存，并返回精确回执。
8. STALE Fixture 是否允许特权用户强制运行；本文建议 v2 首版一律 fail-closed，通过新 revision 重捕获。

在以上决策确认前，不应修改现有 v1 wire schema 或直接实现 Controller 字段。

## 26. 与 `rg-evolution-design-1.3.0.md` 的交叉比对结论

### 26.1 直接吸收

| 1.3.0 来源 | 1.3.0 设计 | 本方案的融合结果 |
| --- | --- | --- |
| §七、§8.5 | Define → Wire → Publish → Feed → Decide → Prove 工具主线 | Fixture 创作与选择进入 Feed，Resolved evidence 与四维结论进入 Prove |
| §8.3 Phase C | `sample -> pinned -> governed` provenance | 扩展为 `sample -> pinned -> PRIVATE Case revision -> governed Asset revision` |
| §二、§8.3 | 三种 API Resource Fidelity | 原样保留；其他 Subject 只有 Runtime 显式声明后才能提升 Fidelity |
| §8.3、§十 | schema fingerprint 漂移检测 | 提升为 Compiler 和 Runtime 的 `STALE` fail-closed 门禁 |
| §二、§8.3 | governed Asset 生命周期与 `usageCount` | 由每个已提交 Invocation 的精确 Asset 引用幂等累计 |
| §8.4 Phase D | 显式“Use expected output as Return fixture” | 保持显式创作；保存为 Case 后由 Scenario Compiler 生成 Binding |
| §二、§六、§8.5 | Execution/Assertions/Contract/Governance 四维结论 | 固化进 `SimulationRunV2.verdicts`，禁止泛化 Passed |
| §五、§8.1 | 对象主线 overlay 与 feature switch | 作为 v2 UI 发布和回滚策略，不新建平行 Studio |
| §8.5 | 真实浏览器、不手写 JSON 的验收 | 纳入 S6/S7 和 UI 兼容测试 |

### 26.2 v2 新增

以下能力在 1.3.0 中只有交互或局部运行形态，本文将其提升为统一协议：

- 将本次业务输入与 Fixture 控制彻底分离。
- 调用方按精确 Case、稳定 conditionId 或唯一自动匹配选择 Fixture。
- 为多 API DAG、嵌套 Flow、Operator 和 Built-in Function 统一 Target/Selection。
- 将静态 Call Site 与动态 Invocation Key 分离，覆盖循环、重试和恢复。
- 生成不可变 Resolved Fixture Plan 和逐 Invocation 审计证据。
- 明确 `unmatched` 与真实外部访问授权互不替代。

### 26.3 明确不融合

以下内容看似相近，但不得进入 v2 公共协议：

- 不把 `ToolCoordinate`、画布位置或 UI test id 当作 Fixture Target。
- 不让外部调用方直接引用临时 sample、未保存 pinned 状态或 `GraphDraft.nodeFixtures`。
- 不把 Decision Table predicate 自动复制成 Fixture Condition。
- 不把 expected assertion 自动变成自 Mock，从而制造自证通过。
- 不按函数名全局替换 Built-in Function；必须使用稳定 Call Site。
- 不用一个 `PASSED` 同时代表运行成功、断言成功、契约有效和治理合规。

### 26.4 最终融合判断

两份设计没有方向冲突。1.3.0 是对象化创作、治理和可见体验的基线；v2 是调用方选择、编译、
执行与证据协议的深化。正确的实现方式是共享同一 Fixture Set、Fixture Asset、Fidelity、staleness 和
Scenario 资产，不再新增第二份 Mock 存储、第二套条件编译器或第二个 Fixture 工作台。

## 27. 实施状态

截至 2026-09-02，S1 已实现并通过聚焦回归：`SimulationCommand v2`、`FixtureSetCommand v2`、五类 exact
Subject、受限 Condition、API Resource `SUBJECT` Plan 编译、Target overlap 与 payload-safe fingerprint 均已落地。
冻结 Schema 的 minimal/complete/invalid goldens 与既有 v1 Fixture/Simulation 兼容测试共 **66/66 green**。

S2a 已冻结并实现 `bloge.simulationRun.v2`：每个动态调用保存 server-generated Invocation Key、父调用、精确
Target/Subject、REAL/MOCKED、命中来源、Fixture Case/Asset 坐标、行为、Fidelity、Provenance、输入/输出
fingerprint 与 egress evidence。四维 verdict 继续独立，只有四维全部通过才允许 `READY`。内存与 JDBC authority
均已实现；JDBC 复用 V013，v1/v2 共用 scope + Idempotency-Key 坐标，并对损坏或未知 evidence fail closed。
runtime config 在同一 DataSource/transaction manager 上同时装配 v1/v2 store。该切片 schema/store/config 聚焦门
为 **29/29 green**；包含既有 v1 module/store/readiness/controller 的兼容门为 **46/46 green**。

S2b 已接入 API Resource runtime：精确 Case、conditionId 与唯一自动匹配均使用本次业务输入；`RETURN` 支持
PRIVATE inline 与 exact governed Asset，`ERROR/TIMEOUT` 不等待、不泄漏配置 message，`REPLAY` 只读取 exact
recording authority。输入与成功输出均按 exact Resource contract 验证；未选择 Fixture、Material authority 缺失或
未配置真实读时只形成 `BLOCKED` evidence，不会回落到网络。governed usage 只从已提交的 COMPLETED Invocation
通过 `runId + invocationKey + asset` 幂等投影。S1/S2 runtime/store/schema 与 v1 兼容门为 **62/62 green**。

S2c 复用同一 authenticated/idempotent POST/GET 路径，按 exact `schemaVersion` 分派 v1/v2；v2 可使用专用
`AUTHORING_SIMULATION_RUN` purpose，仍只信任已验证 identity。严格反序列化拒绝 caller-supplied Invocation Key
和未知字段，Fixture subject/condition/auto-match/overlap/material/stale 等失败使用 payload-free exact problem
code。应用配置显式组装 compiler 与 v2 module，缺失可选 material/replay/usage provider 时继续 fail closed。
controller/configuration/v2 module 聚焦门为 **20/20 green**。

S3 已增加独立 Flow compiler/runtime：exact draft/version 会递归展开为受界层级 topology；`NODE_PATH` 缺失、
循环、祖先/后代 overlap 均在执行前拒绝。显式 Binding 在 DAG mapping 生成真实节点输入后才解析 condition/auto
match；`CASE_CONTROLS` 编译为固定工具方案。每个动态节点生成不同 Invocation Key，嵌套 Flow container 作为父
Invocation；整体 Fixture 会跳过后代，未整体替代时本地展开。未匹配外部 API 仍零网络阻断。S3 与 v1 parent
Flow 兼容聚焦门为 **48/48 green**。

S4 已接入统一 component authority。Operator 通过 immutable library revision、operator ref 与 contract fingerprint
解析，Built-in Function 通过 catalog revision、signature fingerprint 与 runtime fingerprint 解析；漂移在 run claim
前拒绝。两类独立 Subject 复用同一 behavior、schema validation、Invocation evidence 与 idempotency runtime。
Reusable Flow wire 也新增 exact Operator composable ref，Flow compiler 从同一 authority 获取节点契约，运行时可用
`NODE_PATH` Fixture 替代 Operator 节点而不执行真实组件。component authority、独立执行、Operator DAG、配置与
schema 聚焦门为 **48/48 green**。

当前总体覆盖约 **94%**，剩余约 **6%**。S0–S4 的 schema、API Resource、Flow/DAG、Operator 独立与 DAG 节点、
Built-in Function 独立 Subject、Invocation evidence 与 HTTP 主链已闭合。S5 已增加 compiler-owned 稳定 Call
Site 与逐次动态拦截 seam：同节点同名调用可分别绑定 Fixture，同一静态 Call Site 多次调用会按实际输入重新选
Case，并产生不同 Invocation Key。尚未闭合的 S5 边界是把 Call Site authority 持久化到 Operator/Flow 发布产物、
接入 production Operator runtime adapter，以及 Operator/Function Fixture Set 的独立持久化与检索。S6/S7 的 UI、
Pin/Promote、Scenario bridge 和真实浏览器验收也仍待完成。当前 Flow 模型没有循环/重试节点，但动态解析 seam 已
保证每次实际调用重新选择且生成独立 Invocation Key；未来 loop/retry runtime 必须复用该 seam。
