# Resource Gateway 调用方驱动 Fixture 模拟方案 v2

状态：Proposed

日期：2026-09-02

适用范围：API Resource、Reusable Flow、Operator、Built-in Function 的 Fixture 配置与模拟执行

关联基线：`rg-api-fixture-reusable-flow-authoring-proposal-v1.md`

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

### 3.3 解析结果必须固化为精确 Case

即使调用方使用 `conditionId` 或自动匹配，Simulation Run 也必须记录最终解析出的：

- `fixtureSetId`
- `revision`
- `fingerprint`
- `caseId`
- `target`
- `behavior`
- `fidelity`
- `matchedBy`

条件名称只是选择入口，不是最终证据。

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
| Resolved Fixture Plan | 服务端将全部 Selection 解析为精确 Case 后形成的不可变运行计划 |
| Call Site | 编译产物中某一次 Built-in Function 调用的稳定身份 |
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
| 422 | `FIXTURE_AUTO_MATCH_AMBIGUOUS` | 自动匹配命中多个 Case |
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

## 22. 实施阶段

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
- 更新 API Resource、Flow、Operator、Function 操作手册和截图。

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

### 23.5 Operator 与 Function

- Operator 独立 Subject Fixture。
- Operator DAG Node Fixture。
- Function 独立 Subject Fixture。
- 同名不同签名不串用。
- 同节点两个同名 Call Site 分别绑定。
- Call Site authority 漂移后 Fixture `STALE`。

### 23.6 HTTP 与安全

- 401/403 在读取 Fixture Material 前拒绝。
- 跨 Scope 引用统一 404。
- 幂等 Replay 返回同一 Resolved Plan 和 Run。
- 同一幂等键不同 Plan 返回 409。
- 错误、日志和证据不泄漏输入、输出、条件值或 Material。

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

## 25. 待确认决策

以下决策需要在进入 S1 实现前确认：

1. 是否接受将 `input` 与 `fixturePlan` 作为永久独立的两个轴。
2. 是否接受运行请求只能引用已保存 Fixture，不允许直接上传 Mock 输出。
3. 是否保留 `AUTO_MATCH`，还是 v2 首版只提供 `EXACT_CASE` 和 `MATCH_CONDITION`。
4. 是否允许 `EXACT_CASE` 忽略 `when`，用于故障注入；本文建议允许并在证据中明确标记。
5. `unmatched=REAL` 是否在普通界面出现；本文建议仅在高级入口出现。
6. Built-in Function Call Site authority 是否作为 S5 前置单独冻结。

在以上决策确认前，不应修改现有 v1 wire schema 或直接实现 Controller 字段。
