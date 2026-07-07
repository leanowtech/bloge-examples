# 存量 BLOGE DSL 业务迁移到可视化编排设计方案

状态：Partially Implemented / Phase 2.2 迁移专线；resource-gateway 已落地 schema-neutral DSL preview 后端与 `/author/` Legacy DSL 面板 MVP
目标读者：BLOGE framework、resource-gateway visual canvas、Studio/LSP、业务平台迁移团队
适用场景：业务系统已经集成 BLOGE 引擎和 DSL，已经自定义实现一批 Java 算子/表达式函数，并通过手写 `.bloge` DSL 承载业务逻辑，现在希望升级到可视化编排交付模式。

## 1. 核心判断

这不是“给画布加一个导入按钮”的问题，而是一条存量业务迁移流水线：

```text
任意合法 operator/function schema
  -> visual operator/function catalog
    -> 导入手写 .bloge DSL
      -> 解析 AST / 编译诊断 / source map
        -> 投影为 VisualGraphDraft
          -> 画布可视化渲染、修复、验证、模拟、回写 DSL
```

因此需要同时解决三件事：

1. 画布能消费结构合法的业务算子库和 business built-in function schema，不关心 schema 是怎么生成的。
2. 画布能导入这些 schema，并把它们变成 palette、连线校验、表达式补全和测试能力。
3. 画布能导入手写 DSL，把 DSL 内容可视化渲染成 GraphDraft，并用诊断明确哪些内容已完整投影、哪些内容需要人工修复。

产品承诺不能是“完美反编译”。正确承诺应该是：**loss-aware import，支持常用 DSL 无损投影；复杂或未知语义保留为可审阅 opaque 节点/片段，绝不静默丢逻辑。**

### 1.1 画布边界

通用画布不应该关心 schema provenance。下面这些来源在画布看来都应等价：

| Schema 来源 | 是否应可渲染 DSL | 画布关心什么 |
| --- | --- | --- |
| `bloge-maven-plugin:export-schema` 从业务代码生成 | 是 | 合同结构合法、operator/function ref 可解析 |
| 用户手写 `bloge.visualOperatorLibrary.v1` | 是 | 字段、端口、schema、lowering、函数签名合法 |
| 平台接口下发 catalog | 是 | catalog 版本、租户/命名空间、签名和权限合法 |
| OpenAPI / AsyncAPI / resource descriptor 投影 | 是 | 投影后的 visual operator library 合法 |
| 其他工具生成 JSON/YAML schema | 是 | 是否能通过同一套 validator |

所以迁移系统要分清两层：

```text
Schema acquisition: 负责生成、收集、治理、对账 schema
Canvas rendering: 只消费合法 schema + DSL，并投影成可视化 GraphDraft
```

`bloge.capabilityCatalog.v1` 是存量 BLOGE 业务最推荐的 schema acquisition 路径，但它不是通用画布渲染 DSL 的唯一入口，更不能成为硬依赖。

## 2. 代码事实

主 BLOGE 仓库已经具备迁移链路的一部分基础能力：

| 事实 | 位置 | 设计含义 |
| --- | --- | --- |
| DSL runtime path 是 `.bloge` 或 Java graph -> parser/compiler -> `Graph` -> `GraphEngine` | `bloge-dsl`、`bloge-core` | DSL import 必须复用官方 parser/compiler，不应写正则解析器 |
| DSL 是手写 Java lexer/parser/compiler | `bloge-dsl/src/main/java/.../lexer`、`parser`、`compiler` | Visual projection 要跟 AST 结构绑定，并跟 `bloge-lang` / Studio 同步 |
| `DslCompiler` 已提供 `parseAst(String)` 和 `parse(String)` | `bloge-dsl/.../DslCompiler.java` | 可以形成 DSL -> AST -> visual draft 的服务端导入管线 |
| `DslCompiler` 可返回 expression function descriptors | `DslCompiler#functionDescriptors()` | 画布函数目录不应再靠手工维护 |
| `AstNode.GraphDef` 已携带 graph-level `inputSchema` / `outputSchema` | `bloge-dsl/.../AstNode.java` | DSL 导入应直接投影 graph input/output contract |
| Maven 插件已有 `export-schema` goal | `bloge-maven-plugin/.../ExportCapabilityCatalogMojo.java` | 存量业务可在 CI 生成 `bloge.capabilityCatalog.v1` |
| 框架级 catalog schemaVersion 已是 `bloge.capabilityCatalog.v1` | `bloge-core/.../BlogeCapabilityCatalog.java` | `bloge.visualOperatorLibrary.v1` 应成为画布消费合同，而不是框架源合同 |
| `export-schema` 会合并 operator metadata 与 expression function registry | `BlogeCapabilityExporter` | 业务算子与 built-in function 可以统一进入迁移入口 |
| resource-gateway 画布当前核心草稿是 `GraphDraft` | `resource-gateway-examples/.../visual/draft/GraphDraft.java` | DSL import 应输出现有 `bloge.visualGraphDraft.v1`，不要新增并行模型 |
| 画布当前已有 GraphDraft -> DSL codegen | `GraphDraftDslGenerator` | 迁移后要做 semantic round-trip，而不是仅渲染一次 |
| resource-gateway 已有 DSL preview import 入口 | `resource-gateway-examples/.../visual/importer/DslImportService.java`、`DslImportController.java`、`src/main/frontend/src/AuthorCanvas.tsx` | 第一版已支持普通 node、transform、decision_table、graph input/output schema、source map、missing operator/function diagnostics，并已接入 `/author/` Legacy DSL 面板；source map UI、commit 和 round-trip 仍待接入 |

结论：迁移方案应把现有 `bloge.capabilityCatalog.v1`、`DslCompiler.parseAst()`、`GraphDraft` 和 `GraphDraftDslGenerator` 串起来，而不是另造一个 Studio-only 或 canvas-only 格式。

## 3. 目标与非目标

### 3.1 目标

1. 通用画布可消费任意来源、结构合法的 operator/function schema。
2. 业务系统可选择通过 Maven/CI 从存量代码导出 operator/function capability catalog，并适配成现有 visual catalog。
3. 通用画布可导入 `.bloge` DSL，服务端解析后返回可渲染 `GraphDraft`。
4. 导入结果带 source map，点击画布节点能定位回 DSL 行列；点击 DSL 片段能定位画布节点。
5. 对 unknown operator、missing function、unsupported syntax、lossy projection 给出显式 diagnostics。
6. 支持 DSL -> VisualDraft -> Generated DSL -> AST semantic equivalence 验证。
7. 未被支持的 DSL 内容必须保留为 opaque import block 或 source snippet，不能丢失。
8. 导入后的 graph 继续享受现有画布能力：schema 连线校验、context 绑定、decision table、test suite、simulate、export、publish。

### 3.2 非目标

| 非目标 | 原因 |
| --- | --- |
| 第一版就做到所有 DSL 语法 100% 可视化编辑 | 成本极高，且会拖慢常见业务迁移闭环 |
| 以字符串相等作为回写判断 | 格式化、注释、字段顺序会制造伪差异；应以 AST/语义等价为准 |
| 把 visual schema 变成框架 source of truth | 框架源合同应是 `bloge.capabilityCatalog.v1` |
| 在画布端执行任意业务代码来完成扫描 | 安全边界错误；扫描应在业务 CI 或受控服务端完成 |
| 用前端 TypeScript 独立解析 DSL | 会跟 Java compiler 漂移；前端只消费服务端 import result |

## 4. 目标用户路径

### 4.1 存量业务团队

业务系统已有如下资产：

```text
src/main/java/.../RiskScoreOperator.java
src/main/java/.../CustomerProfileOperator.java
src/main/resources/bloge/loan-approval.bloge
src/main/resources/bloge/order-fulfillment.bloge
```

升级路径：

1. 准备一份能被画布 validator 接受的 operator/function schema。它可以来自 `bloge-maven-plugin:export-schema`，也可以来自平台接口、手写 visual library 或其他 schema 生成器。
2. 如果团队已经标准接入 BLOGE，推荐在业务项目接入 `bloge-maven-plugin:export-schema`，由 CI 生成 `target/bloge-capability-catalog.json`。
3. 使用 `bloge:validate` 或等价校验能力对 `.bloge` 文件做 operator/function/schema 编译级校验。
4. 在通用画布打开 Legacy DSL Import。
5. 导入或 inline 提供合法 `bloge.visualOperatorLibrary.v1`；`bloge.capabilityCatalog.v1` 需要先经过 adapter，adapter 仍是后续切片。
6. 导入一个或多个 `.bloge` 文件。
7. 查看 import preview：节点、边、graph input/output schema、函数引用、unsupported diagnostics。
8. 对 unresolved 节点或函数执行修复：选择 catalog operator、补 schema、标记 opaque、确认 function alias。
9. Commit 为 `GraphDraft`。
10. 在画布上继续编辑、模拟、跑 test suite，并在需要时生成 DSL 回写。

### 4.2 平台团队

平台团队维护的是迁移流水线和治理：

- catalog baseline：每个业务系统发布一份 capability catalog。
- schema drift gate：通过 `bloge:compare-schema` 阻止破坏性 operator/function 变更。
- import coverage report：统计每批 DSL 的 fully-projected、opaque、unsupported 比例。
- migration backlog：把 unsupported syntax、unknown operator、missing schema 变成可跟踪修复项。
- visual contract baseline：保存导入后的 GraphDraft 和 semantic round-trip 结果。

## 5. 总体架构

```text
Schema source
  |
  | manual / CI export / platform API / OpenAPI / AsyncAPI / resource descriptor
  v
operator/function schema
  |
  | normalize + validate
  v
bloge.visualOperatorLibrary.v1
  |
  +----------------------+
                         |
.bloge source            |
  |                      |
  | DslCompiler.parseAst |
  v                      v
DslImportSession -> DslVisualProjector -> GraphDraft + sourceMap + diagnostics
                                      |
                                      v
                               /author visual canvas
                                      |
                        validate / simulate / test / export
                                      |
                                      v
                          GraphDraftDslGenerator
                                      |
                                      v
                         generated .bloge + semantic round-trip report
```

### 5.1 三层责任边界

| 层 | 责任 | 不做什么 |
| --- | --- | --- |
| Capability Extraction | 从业务代码导出 operator/function catalog | 不解析业务 DSL，不启动任意业务运行流 |
| DSL Reverse Projection | 解析 DSL、绑定 catalog、生成 GraphDraft/sourceMap/diagnostics | 不直接执行真实 operator |
| Visual Authoring | 渲染、修复、编辑、验证、模拟、测试和回写 | 不成为 operator/function source of truth |

## 6. 数据合同

### 6.1 `bloge.capabilityCatalog.v1`

这是框架级源合同，来自主 BLOGE `export-schema`。它是存量 BLOGE 业务推荐使用的 schema acquisition 格式，不是画布唯一能接受的 schema 来源：

```json
{
  "schemaVersion": "bloge.capabilityCatalog.v1",
  "catalogId": "risk-service",
  "displayName": "Risk Service",
  "blogeVersion": "1.0.0",
  "operators": [],
  "functions": [],
  "diagnostics": [],
  "statistics": {
    "operatorCount": 12,
    "functionCount": 7,
    "opaqueSchemaCount": 1,
    "diagnosticCount": 2
  }
}
```

如果采用 capability catalog 路径，不建议业务团队手写该文件；它应该由业务代码库的构建流程生成，并进入版本管理或制品库。若业务团队已经能提供结构合法的 `bloge.visualOperatorLibrary.v1`，画布应直接消费，不需要强迫其先转换成 capability catalog。

### 6.2 `bloge.visualOperatorLibrary.v1`

这是通用画布消费合同。无论上游 schema 来自哪里，进入画布渲染前都应被规整成 visual library 或等价 catalog view。`CapabilityCatalogVisualAdapter` 只是从 framework catalog 生成 visual library 的一种 adapter：

| Capability Catalog | Visual Operator Library |
| --- | --- |
| `catalogId` | `libraryId` |
| `operators[].operatorRef` | `operators[].operatorRef` |
| `operators[].display` | `operators[].display` |
| `operators[].ports` | `operators[].ports` |
| `operators[].configSchema` | `operators[].configSchema` |
| `operators[].capabilities` | `operators[].capabilities` |
| `functions[]` | `builtInFunctions[]` |
| `diagnostics[]` | import diagnostics |

适配规则必须保留 provenance：

```json
{
  "source": {
    "kind": "bloge-capability-catalog",
    "libraryId": "risk-service"
  },
  "lowering": {
    "mode": "native",
    "operatorRef": "risk:score"
  }
}
```

### 6.3 `DslImportSession`

导入不是一次简单转换，而是一个可审阅会话：

```json
{
  "schemaVersion": "bloge.dslImportSession.v1",
  "sessionId": "import-20260707-risk",
  "catalogIds": ["risk-service"],
  "sources": [
    {
      "sourceId": "loan-approval.bloge",
      "path": "src/main/resources/bloge/loan-approval.bloge",
      "contentHash": "sha256:..."
    }
  ],
  "status": "NEEDS_REPAIR",
  "coverage": {
    "nodeCount": 18,
    "projectedNodeCount": 15,
    "opaqueNodeCount": 2,
    "unsupportedSyntaxCount": 1
  },
  "diagnostics": []
}
```

### 6.4 `DslVisualProjection`

这是 preview API 的主响应：

```json
{
  "schemaVersion": "bloge.dslVisualProjection.v1",
  "sourceId": "loan-approval.bloge",
  "draft": {
    "schemaVersion": "bloge.visualGraphDraft.v1",
    "graphName": "loanApproval",
    "inputSchema": {},
    "nodes": [],
    "edges": [],
    "output": { "nodeId": "finalResponse", "path": "" }
  },
  "sourceMap": {
    "nodes": {},
    "edges": {},
    "bindings": {}
  },
  "roundTrip": {
    "supported": true,
    "equivalence": "NOT_RUN"
  },
  "diagnostics": []
}
```

### 6.5 `DslSourceMap`

Source map 是迁移可用性的关键，不是锦上添花：

```json
{
  "nodes": {
    "riskScore": {
      "sourceId": "loan-approval.bloge",
      "startLine": 18,
      "startColumn": 3,
      "endLine": 31,
      "endColumn": 4,
      "dslKind": "node"
    }
  },
  "bindings": {
    "riskScore.inputs.applicantId": {
      "sourceId": "loan-approval.bloge",
      "startLine": 22,
      "startColumn": 7,
      "endLine": 22,
      "endColumn": 36,
      "expression": "ctx.applicantId"
    }
  }
}
```

第一版如果 parser 暂时没有 endLine/endColumn，也至少要提供 start line/column 和 raw snippet。没有 source map，迁移审阅会变成猜谜。

## 7. DSL 投影规则

### 7.1 GraphDef

| DSL AST | Visual Draft |
| --- | --- |
| `GraphDef.name` | `draft.graphName` |
| `GraphDef.inputSchema` | `draft.inputSchema` |
| `GraphDef.outputSchema` | `draft.outputSchema` 的后续扩展字段；当前可先放入 `visualLayout.graphContract.outputSchema` |
| `GraphDef.streamingOutputNodeId` | output selection + streaming diagnostic |
| imports | import session dependencies |
| comments/description | node/display/revision metadata |

当前 `GraphDraft` 只有 `inputSchema`，而用户已经要求每张 graph 都有形式化 input/output schema。因此迁移专线应推动 `GraphDraft` 增加一等 `outputSchema` 字段；在字段落地前，导入器可以临时把 output schema 放在 `visualLayout.graphContract.outputSchema`，但这只能作为兼容过渡。

### 7.2 普通 node

`AstNode.NodeDef` 映射为 `GraphDraft.DraftNode`：

| NodeDef | DraftNode |
| --- | --- |
| `id` | `id` |
| `operatorRef` | `operatorRef` |
| `description` | `label` / display metadata |
| `input` | `inputs` |
| `timeout/retry/fallback/compensation` | `config` 或治理配置区 |
| `inputSchema/outputSchema` | node-local schema override |
| `dependsOn` | dependency edges |

节点处理策略：

1. operatorRef 能在 imported visual catalog 找到：生成 typed node。
2. operatorRef 找不到，但 DSL 可编译为 late-bound：生成 `bloge:opaqueDslNode`，保留原始 snippet。
3. operatorRef 找不到且编译失败：生成 diagnostic，允许用户选择 catalog operator 修复。
4. operator schema 不完整：允许渲染，但连线校验降级 warning/opaque。

### 7.3 输入绑定

常见输入表达式应投影为图形化 binding：

| DSL 表达式 | Binding |
| --- | --- |
| `ctx.userId` | `contextPath("userId")` |
| `ctx.order.items[0].sku` | context path + path expression metadata |
| `riskScore.output.score` | `nodePath("riskScore", "score")` |
| string/number/boolean/object literal | `constant(value)` |
| `coalesce(profile.name, "unknown")` | `expression("coalesce(...)")` |
| object literal with mixed fields | `objectTemplate(fields)` |

关键点：从 DSL 导入的表达式不要全部扔进 raw expression。能结构化的要结构化，这样用户才能在画布上拖拽修复；不能结构化的才保留 expression binding。

### 7.4 数据边

数据边来自两类信息：

1. 显式 `dependsOn` / branch / control-flow 声明。
2. 输入表达式中引用的 node output path。

投影规则：

```text
nodeB.input.user = nodeA.output.user
  -> data edge nodeA.output.user -> nodeB.inputs.user
  -> nodeB.inputs["user"] = nodePath(nodeA, "output", "user", "inputs", "user")
```

如果一个节点只通过 `dependsOn` 控制顺序、没有数据引用，则生成 `dependency` edge，并在画布上用区别于 data edge 的样式展示。

### 7.5 Branch / decision

第一版应支持两种投影：

| DSL 形态 | Visual 形态 |
| --- | --- |
| 简单 branch cases | route edges + condition label |
| 表格式规则或可规整条件集合 | `bloge:decisionTable` |

复杂表达式不要强行变 decision table。导入器应该给出 recommendation：

- `PROJECTED_AS_DECISION_TABLE`：已规整成表格。
- `PROJECTED_AS_BRANCH_EDGES`：保持 route edges。
- `OPAQUE_CONDITION`：表达式太复杂，只保留条件文本。

### 7.6 Transform

Transform 是迁移成可视化编辑的高价值区域：

| Transform field | Visual binding |
| --- | --- |
| 直接字段复制 | nodePath/contextPath |
| 常量默认值 | constant |
| 函数调用 | expression + function reference |
| 嵌套对象 | objectTemplate |

如果函数出现在 capability catalog 的 `functions[]` 中，画布显示函数 chip、签名提示和示例。如果缺失，诊断为 `function-missing`，但保留原始表达式。

### 7.7 ForEach / parallel / loop / await / session extension

复杂控制流分层处理：

| 语义 | Phase 2 MVP | 后续 |
| --- | --- | --- |
| foreach | group/container node + body subgraph preview | 可编辑子画布 |
| parallel | group/container node + dependency lanes | lane 级执行策略编辑 |
| loop | opaque control node + source snippet | loop 表单编辑 |
| await/event | opaque event node + diagnostics | event source schema 化 |
| session/phase extension | extension container | session 专属可视化 |

原则：复杂结构先可视化呈现和保持 round-trip，再逐步开放细粒度编辑。

## 8. 诊断模型

导入诊断必须面向“迁移修复”，不能只有 compiler error 文本。

| Code | Level | 含义 | 推荐动作 |
| --- | --- | --- | --- |
| `dsl.import.operatorResolved` | INFO | operatorRef 已绑定 catalog | 无需处理 |
| `dsl.import.operatorMissing` | ERROR | DSL 引用了 catalog 中不存在的 operator | 导入正确 catalog 或手动映射 |
| `dsl.import.functionMissing` | WARNING/ERROR | 表达式函数缺少 schema | 补 function descriptor 或标记 external |
| `dsl.import.schemaOpaque` | WARNING | port schema 只能投影为 opaque | 补 SchemaAware / 显式 schema |
| `dsl.import.unsupportedSyntax` | WARNING | 语法能解析但暂不可视化编辑 | 保留 opaque snippet，后续补 projector |
| `dsl.import.lossyProjection` | WARNING | 可渲染但回写可能改变结构 | 运行 round-trip |
| `dsl.import.roundTripFailed` | ERROR | 生成 DSL 与原 DSL 语义不等价 | 阻止自动回写 |
| `dsl.import.sourceMapMissing` | WARNING | 缺少精确源码定位 | 降级为 graph-level 诊断 |

诊断目标必须指向 source 和 visual 两端：

```json
{
  "level": "WARNING",
  "code": "dsl.import.functionMissing",
  "message": "Function riskScoreBand is used by node finalDecision but is not declared in the current catalog view.",
  "source": {
    "sourceId": "loan-approval.bloge",
    "line": 42,
    "column": 15
  },
  "visualTarget": {
    "nodeId": "finalDecision",
    "binding": "tier"
  },
  "recommendedAction": "Add a function schema or import a function catalog that declares riskScoreBand."
}
```

## 9. API 设计

### 9.0 Schema-neutral 导入原则

DSL 可视化渲染 API 不应该要求 schema 必须来自 `bloge.capabilityCatalog.v1`。它只需要一个可解析的 operator/function catalog view：

```text
inline visual library
  or existing libraryId
  or capability catalog + adapter
  or platform catalog reference
    -> resolved operator/function catalog
      -> DSL visual projection
```

因此 API 设计上要避免把 capability catalog 写死在 DSL import request 里。Capability catalog 导入是独立入口，DSL preview import 接受的是“当前可用 catalog ids 或 inline schema”。

### 9.1 Capability catalog 导入

```http
POST /admin/visual-operator-libraries/from-capability-catalog/validate
POST /admin/visual-operator-libraries/from-capability-catalog/import
```

输入：

```json
{
  "catalog": { "schemaVersion": "bloge.capabilityCatalog.v1" },
  "libraryIdOverride": "risk-service",
  "importMode": "ADAPT_TO_VISUAL_LIBRARY",
  "includeImplementationClass": false
}
```

输出沿用现有 `OperatorLibraryValidationResult` / import readiness，并附加 adapter diagnostics。

### 9.2 DSL preview import

```http
POST /api/visual/dsl-imports/preview
```

输入：

```json
{
  "sourceId": "loan-approval.bloge",
  "dsl": "graph loanApproval { ... }",
  "catalogIds": ["risk-service"],
  "inlineLibraries": [],
  "mode": "LOSS_AWARE",
  "layout": {
    "strategy": "DAGRE_SPACIOUS"
  }
}
```

输出：

```json
{
  "schemaVersion": "bloge.dslVisualProjection.v1",
  "sourceId": "loan-approval.bloge",
  "draft": {
    "schemaVersion": "bloge.visualGraphDraft.v1",
    "inputSchema": {},
    "visualLayout": {
      "graphContract": {
        "outputSchema": {}
      },
      "import": {
        "schemaNeutral": true
      }
    }
  },
  "sourceMap": {
    "nodes": {},
    "edges": {},
    "bindings": {}
  },
  "diagnostics": [],
  "coverage": {
    "memberCount": 0,
    "projectedNodeCount": 0,
    "edgeCount": 0,
    "unsupportedSyntaxCount": 0,
    "missingOperatorCount": 0,
    "missingFunctionCount": 0
  },
  "roundTrip": {
    "supported": false,
    "status": "NOT_ASSESSED"
  }
}
```

resource-gateway 当前实现口径：

- `catalogIds` 可用别名 `operatorLibraryIds`；inline visual libraries 只参与本次 preview，不写入 registry。
- operator/function schema 来源不限，但 inline visual library 会走同一套 `OperatorLibraryValidator`。
- DSL 使用官方 `DslCompiler.parseAst()`，并加载可发现的 DSL extension providers。
- 普通 `node` 缺 operator schema 时仍生成占位 draft node，并返回 `visual.dslImport.operatorMissing`。
- `transform` 投影为 `bloge:transform` 节点，字段表达式写入 `config.assignments`。
- `decision_table` 投影为 `bloge:decisionTable` 节点，入参表达式写入 `config.inputs`，入边数据会成为 decision table condition 可引用的局部参数。
- DSL graph `input { ... }` 写入 `draft.inputSchema`；DSL graph `output { ... }` 暂写入 `draft.visualLayout.graphContract.outputSchema`。
- `foreach`、`loop`、`parallel`、`wait`、`await`、`script`、extension 等复杂语法第一版以 warning diagnostic 暴露，不静默丢弃。

### 9.3 DSL import commit

```http
POST /api/visual/dsl-imports/commit
```

输入包含 previewId 或完整 projection。commit 后写入现有 draft repository：

```json
{
  "previewId": "preview-123",
  "draftName": "loanApproval-visual",
  "commitMessage": "Import legacy DSL loan-approval.bloge",
  "allowOpaqueNodes": true
}
```

### 9.4 Round-trip 验证

```http
POST /api/visual/dsl-imports/round-trip
```

校验流程：

```text
original DSL -> parseAst -> canonical AST A
draft -> GraphDraftDslGenerator -> generated DSL -> parseAst -> canonical AST B
A ~ B
```

返回：

```json
{
  "equivalence": "SEMANTIC_EQUIVALENT",
  "generatedDsl": "graph loanApproval { ... }",
  "diff": [],
  "diagnostics": []
}
```

等价级别：

| Level | 含义 |
| --- | --- |
| `TEXT_IDENTICAL` | 文本一致，少见 |
| `AST_EQUIVALENT` | AST 结构等价 |
| `SEMANTIC_EQUIVALENT` | 执行语义等价，可接受 |
| `LOSSY_BUT_REVIEWED` | 有损但人工确认 |
| `NOT_EQUIVALENT` | 不允许自动回写 |

## 10. 前端体验

### 10.1 Legacy DSL Import 入口

`/author/` 增加一个 Import Existing DSL 面板：

```text
Step 1 Capability catalog
  - paste/upload bloge-capability-catalog.json
  - validate
  - import/adapt to visual library

Step 2 DSL source
  - paste/upload .bloge
  - choose catalog
  - preview

Step 3 Review
  - visual graph preview
  - diagnostics list
  - source map side panel
  - unresolved mapping wizard

Step 4 Commit
  - save as GraphDraft
  - run validate/simulate/test
```

### 10.2 画布渲染要求

导入后的图必须立即可读：

- 使用自动布局，但给 edge label 和 route condition 留足空间。
- 对 imported node 加轻量来源标记，不要干扰主要业务信息。
- Opaque node 使用明确视觉状态，提醒用户它不是已完全结构化的节点。
- source map 面板展示原 DSL snippet，可点击定位。
- diagnostics 按严重程度、node、source line 分组。

### 10.3 修复向导

常见修复操作：

| 问题 | UI 操作 |
| --- | --- |
| operator missing | 从 catalog 搜索并映射 operatorRef |
| function missing | 选择已有 function、添加 function schema、标记 external |
| schema opaque | 选择 schema 文件、从样例推断、手写 JSON Schema |
| unsupported branch | 保留 route edge 或转换为 decision table |
| unknown output node | 选择 terminal output 节点和 output path |

修复动作要落到 import session，不能只改前端状态。否则刷新后审阅上下文会丢。

## 11. BLOGE 主仓库演进点

### 11.1 DSL frontend

需要补强：

1. AST 节点 endLine/endColumn/sourceSpan。
2. 注释和 doc comment 更稳定地绑定到 AST 节点。
3. 表达式 AST -> binding projection visitor。
4. canonical AST normalizer，用于 semantic round-trip。
5. unsupported syntax classification，给 visual importer 消费。

### 11.2 Capability catalog

已有 `bloge.capabilityCatalog.v1`，后续重点是：

1. 函数 descriptor 覆盖率：旧式 `ExpressionFunction` fallback 要逐步替换为完整 descriptor。
2. operator config schema：把运行配置、timeout/retry/fallback 等可视化编辑合同标准化。
3. source provenance：明确 annotation、SchemaAware、runtime registry、manual override 的来源。
4. visual adapter：提供 official `CapabilityCatalog -> VisualOperatorLibrary` 投影器，避免各业务重复写。
5. security profile：把 requiresSecrets、sideEffectType、determinism、runtime profile 暴露得更完整。

### 11.3 Maven 插件

扩展 `bloge-maven-plugin`：

```bash
mvn bloge:export-schema
mvn bloge:validate
mvn bloge:visual-import-preview
```

`visual-import-preview` 可在 CI 里生成迁移报告：

```text
target/bloge-visual-import-report/
  capability-catalog.json
  visual-operator-library.json
  loan-approval.visual-draft.json
  loan-approval.import-diagnostics.json
  loan-approval.roundtrip.json
```

### 11.4 Studio / bloge-lang

Studio 已有 visual/code 同步基础，但通用画布应优先以 Java server import 为权威。长期可以让 `bloge-lang` 暴露同构 projector，用于前端离线 preview；但 CI、发布、迁移验收仍以 Java compiler 为准。

## 12. resource-gateway visual canvas 演进点

### 12.1 后端新增包

建议在 `resource-gateway-examples` 的 visual 包下增加：

```text
visual/importer/
  CapabilityCatalogVisualAdapter.java
  DslImportService.java
  DslVisualProjector.java
  DslSourceMap.java
  DslImportDiagnostic.java
  DslImportSession.java
  DslRoundTripVerifier.java
  OpaqueDslNodeProjector.java
```

### 12.2 新增 operator

为 loss-aware import 增加内置设计节点：

| operatorRef | 用途 |
| --- | --- |
| `bloge:opaqueDslNode` | 暂不能结构化投影的普通 DSL node |
| `bloge:opaqueControlBlock` | loop/await/session 等复杂控制块 |
| `bloge:importedSubgraph` | import/subgraph 引用 |
| `bloge:unsupportedDslSnippet` | 保留无法安全投影的 source snippet |

这些节点必须是 design-only，不能伪装成可执行节点。模拟时可用 fixture，发布 executable 时必须要求修复或明确绑定。

### 12.3 GraphDraft 扩展

建议把下面字段升为一等合同：

```json
{
  "outputSchema": {},
  "sourceProvenance": {
    "kind": "bloge-dsl-import",
    "sourceId": "loan-approval.bloge",
    "contentHash": "sha256:..."
  },
  "sourceMap": {},
  "roundTrip": {}
}
```

其中 `outputSchema` 优先级最高，因为用户已经明确要求每张资源 graph 都有形式化 input/output schema。导入 DSL 时如果原 DSL 有 output schema，必须直接投影；如果没有，导入器可以基于 output node schema 推断，但要标记 `inferred`。

## 13. 兼容与安全

### 13.1 版本兼容

| 资产 | 兼容策略 |
| --- | --- |
| capability catalog | 使用 `schemaVersion` + `compare-schema` 做 breaking gate |
| visual library | 保持 `bloge.visualOperatorLibrary.v1` 兼容导入 |
| GraphDraft | 新字段必须向后兼容；旧 draft 不含 sourceMap 仍可打开 |
| DSL | semantic round-trip，不要求文本相同 |

### 13.2 安全边界

1. 画布服务端不能扫描任意用户上传 jar 并执行类初始化。
2. 构建期 catalog 导出只做 annotation/generic/schema introspection，不实例化 operator。
3. Runtime capability endpoint 只在业务系统自己受控环境开放。
4. DSL import 限制文件大小、节点数、表达式深度和 import 递归深度。
5. Opaque snippet 进入页面前要做转义，避免 XSS。
6. source map 和 diagnostics 不应泄露 secret 默认值。

## 14. 分阶段路线图

### Phase 2.1：Schema intake 与 catalog adapter

交付：

- Schema-neutral visual library validate/import 能力，保证手写、平台下发、工具生成的合法 schema 都可进入画布。
- `bloge.capabilityCatalog.v1` validate/import API。
- Capability -> visual library adapter。
- functions -> `builtInFunctions` 映射。
- adapter diagnostics。

验收：

- 任意结构合法的 visual library 能进入 `/author/` palette。
- 用真实业务导出的 capability catalog 能通过 adapter 进入 `/author/` palette。
- transform expression editor 能看到业务函数。
- catalog 中 opaque schema 被明确标记。

### Phase 2.2：DSL import preview MVP

状态：resource-gateway 后端 MVP 与 `/author/` 浏览器 preview 面板已落地。

已交付：

- DSL -> AST -> GraphDraft projector。
- 普通 node、transform、decision_table、contextPath、nodePath、constant、expression、dependency/data/route edge 支持。
- graph input schema 投影；graph output schema 通过 `visualLayout.graphContract.outputSchema` 过渡承载。
- source map 基础支持。
- diagnostics list。
- `/author/` Legacy DSL 面板消费 preview response，并把返回 draft 加载为当前可编辑 canvas。
- 导入后同步 graphName、Graph Contract、Runtime Context 变量表、Test Suite 初始行、operator snapshots/fingerprints 和 Export Draft。

已验证：

```bash
mvn -f resource-gateway-examples/pom.xml -Dtest=DslImportServiceTest,DslImportControllerTest test
```

验收剩余：

- opaque snippet / unsupported syntax 的 UI 展示与定位。
- 大 DSL 文件、扩展语法、批量导入和负路径 coverage report。

### Phase 2.3：Round-trip 与修复闭环

交付：

- GraphDraft -> DSL -> AST equivalence verifier。
- import session commit。
- unresolved mapping wizard。
- output schema 一等字段。

验收：

- 未修改 imported graph 可回写为语义等价 DSL。
- 有 unresolved/opaque 内容时不能误标为 executable ready。

### Phase 2.4：复杂 DSL 覆盖

交付：

- foreach/parallel group projection。
- branch -> decision table recommendation。
- loop/await/session extension opaque container。
- subgraph/import dependency 展示。

验收：

- 真实存量业务 DSL 覆盖率达到可迁移阈值，例如 80%+ 节点 fully projected，剩余以 opaque 保留。

### Phase 2.5：工业化迁移

交付：

- CI import report。
- batch import。
- coverage dashboard。
- schema drift + round-trip gate。

验收：

- 一个业务仓库可批量导出 catalog、导入全部 DSL、生成迁移报告、产出可审阅 GraphDraft。

## 15. 验收标准

1. 给定任意来源、结构合法的 operator/function schema，通用画布可导入并渲染对应 DSL。
2. 对于已集成 BLOGE 的业务项目，执行 `mvn bloge:export-schema` 能生成包含业务 operator 和 expression function 的 catalog，并可作为推荐输入进入画布。
3. 上传一份业务 `.bloge` 后，画布能渲染节点、边、输入绑定、graph input schema 和 output selection。
4. 每个导入节点可追溯到 DSL source line。
5. missing operator/function/unsupported syntax 被诊断并保留，不会静默丢失。
6. 支持语义 round-trip 校验，未通过时不允许自动覆盖原 DSL。
7. 导入后的 draft 可继续使用现有 validate、simulate、operator test suite 和 graph test suite。
8. Opaque/design-only 节点不能绕过 executable readiness。

## 16. 关键风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 业务算子 schema 不完整 | 画布无法强类型连线 | 推动 `SchemaAware` / 显式 schema / CI 诊断 |
| DSL 表达式复杂 | 难以图形化编辑 | expression binding + function chip + source snippet |
| 注释/格式无法完全保留 | 回写 diff 噪声 | semantic round-trip + source map，而非文本相等 |
| 自定义 extension 太多 | visual projector 覆盖慢 | extension opaque container + extension-specific projector SPI |
| catalog 与 DSL 不匹配 | 导入失败或大量 unresolved | import session 支持 catalog 多版本选择与 mapping wizard |
| 动态 operator registration | 构建期 catalog 看不全 | runtime capability endpoint 或手工补充 catalog |

## 17. 架构决策

### ADR：采用框架 capability catalog 作为存量业务的推荐迁移入口

接受：对于“已经集成 BLOGE 引擎和 Maven/CI 的存量业务”，优先推荐：

```text
bloge.capabilityCatalog.v1 -> visual adapter -> bloge.visualOperatorLibrary.v1
```

拒绝：把 capability catalog 规定为通用画布渲染 DSL 的唯一入口。

```text
DSL import API 必须先上传 bloge.capabilityCatalog.v1
```

理由：存量业务真实 source of truth 通常在代码和 BLOGE registry 中，所以 capability catalog 是治理质量最高的路径；但通用画布的渲染边界只应依赖合法 catalog contract。用户手写 visual library、平台 catalog、OpenAPI/AsyncAPI/resource descriptor 投影，只要能通过同一套 validator，就应该能渲染对应 DSL。

### ADR：画布不关心 schema 生成方式

接受：

```text
schema provenance outside canvas rendering boundary
```

画布只关心：

1. operator/function schema 结构是否合法。
2. DSL 能否被官方 parser/compiler 接受。
3. DSL 中的 operatorRef / function name 能否在当前 catalog view 中解析。
4. 无法解析或无法结构化投影的内容是否能被 loss-aware 保留并诊断。

拒绝：

```text
canvas rendering depends on Maven export / business code scan / specific schema generator
```

理由：通用画布的第一性能力是 schema-constrained visual rendering，不是 schema 生产。把 schema 生产方式耦合进画布，会让手写 catalog、平台下发 catalog、OpenAPI/AsyncAPI 投影和未来其他工具链都变成二等入口。

### ADR：DSL import 使用 loss-aware projection

接受：常见语义结构化投影，未知语义保留 opaque/source snippet/diagnostic。
拒绝：一开始追求全量无损可编辑。

理由：业务迁移第一价值是“可看见、可审阅、可逐步修复”，不是一次性把所有 DSL 变成完美表单。

### ADR：Round-trip 以语义等价为准

接受：AST/canonical semantic equivalence。
拒绝：文本相等。

理由：可视化编辑器天然会格式化和规整 DSL，文本相等会阻止合理演进。

## 18. 下一步最小实现切片

优先切片应该是：

1. 在 resource-gateway visual 层实现 `CapabilityCatalogVisualAdapter`，支持把 `bloge.capabilityCatalog.v1` 适配为 visual library，但不把它作为唯一入口。
2. 给 imported draft 增加 source provenance 和 source map 展示。
3. 实现 DSL import commit，把 preview draft 保存为 stored `GraphDraft`，并保留 source map / import provenance。
4. 增加 round-trip API，阻止不等价回写。

这个切片闭环之后，存量业务团队就能从“手写 DSL 黑盒”进入“可视化审阅 + schema 约束 + 测试验证”的交付路径。
