# 通用 BLOGE 可视化编排系统设计方案

状态：Draft v0.2
日期：2026-06-29
基线：`resource-gateway-examples`，参考 `graph-engine-examples` 的版本、布局、算子清单与诊断能力

当前实现状态：[BLOGE 可视化编排实现状态审计](./bloge-visual-orchestration-implementation-status.md)

设计包索引：[BLOGE 可视化编排设计包索引](./bloge-visual-orchestration-design-package.md)

配套协议：[BLOGE 可视化编排协议草案 v1](./bloge-visual-orchestration-protocol-v1.md)

关键决策：[BLOGE 可视化编排系统关键决策记录](./bloge-visual-orchestration-decision-record.md)

Phase 1 实现蓝图：[BLOGE 可视化编排 Phase 1 实现蓝图](./bloge-visual-orchestration-phase1-implementation-blueprint.md)

## 1. 核心判断

这套系统不能被设计成一个简单的“拖拽画布”。拖拽只是入口，真正要建设的是一套 **BLOGE 可视化编排控制面**：

1. 用户可以注册或导入算子库定义，包括算子输入、输出、配置、能力、约束、权限和运行时绑定。
2. 画布根据这些定义生成可拖拽、可连接、可配置的节点。
3. 节点之间的连接、字段映射、表达式、分支、循环、重试、降级、流式输出等行为必须被 schema 和编译器约束。
4. 画布编辑结果必须能编译为 BLOGE 可执行图，或者生成等价的 BLOGE DSL。
5. 运行、调试、发布、观测、版本演进和治理必须形成闭环，否则复杂业务编排会在短期内腐化成不可维护的低代码黑箱。

当前 `resource-gateway-examples` 适合作为基版，不是因为它已经有完整画布，而是因为它已经证明了一个重要抽象：**运行时行为可以由描述符驱动，而不是由每个业务 API 手写 Java Operator**。这正是通用可视化编排系统要放大的能力。

## 2. 已有基础与证据

### 2.1 resource-gateway 已有能力

事实：

- `resource-gateway-examples` 已经落地 visual authoring slice：`OperatorDefinition`、`OperatorLibrary`、Java OperatorRegistry 投影、`ResourceDesignContract`、`GraphDraft`、连接预检、DSL 生成、运行和 immutable publication。
- `ResourceDescriptor` 已经描述外部 HTTP 资源的 URL、方法、Header、认证、超时、参数映射、响应协议和 payload 提取路径。
- `HttpResourceOperator` 是通用资源算子，运行时根据 `resourceId` 解析 descriptor，再完成参数求值、请求构建、响应校验和 payload 提取。
- `DatabaseResourceRegistry` 已经有 descriptor 持久化、热路径缓存和表达式预编译。
- `/admin/resources` 已经提供资源描述符 CRUD。
- `/api/visual/operators`、`/admin/visual-operator-libraries`、`/api/visual/drafts`、`/api/visual/connections/check`、`/api/visual/publications` 已形成服务端权威 authoring API。
- 静态页面 `Custom Composer` 已从 catalog API 加载动态 palette，并支持用户算子库、resource 虚拟算子、带 projection readiness 的 OpenAPI operation discovery 到 resource contract 预览、schema-aware 连接、visualLayout contract validation、visualLayout group band 渲染、Server Check 诊断按节点 label/id 聚合/过滤/聚焦/轮转、队列位置/过滤明细/隐藏节点提示，并在当前修复节点落在摘要折叠范围外时仍保留该节点预览，F8/Shift+F8 修复队列快捷导航和 Esc 清除过滤、selected-node diagnostics 归因、connectability blocked preview / reason 标签、已配置节点复制和 Cmd/Ctrl+D 快捷入口、Delete/Backspace 删除选中节点并复用 impact cleanup 路径、节点影响面 detach、草稿、发布和运行。

推断：

- resource-gateway 的正确演进方向不是增加更多 provider-specific operators，而是将 `ResourceDescriptor` 泛化为“算子描述符 / 连接器描述符 / 虚拟算子定义”。
- 现有 composer 已经不只是 MVP 原型；它是当前通用画布的可运行 Phase 1 示例。下一阶段应补前端回归、Java operator 深化、导入辅助和长期 graph-engine 对齐。

### 2.2 graph-engine 已有能力

事实：

- `graph-engine-examples` 已经有 `bloge.visualLayout.v1` 模型，用于保存节点位置、分组、标签和注解。
- `visualLayout` 被明确定位为 presentation-only，BLOGE DSL 和编译元数据仍是节点、边、分支、schema、重试和运行语义的来源。
- resource-gateway 的 Phase 1 已开始把 layout group 固化为表现层合同：服务端校验 group id、kind/label 类型、nodeIds、节点 `group` 归属和跨 group membership，浏览器只把它渲染成节点背后的阶段/泳道 band。
- Graph Engine Server 已有 definitions、versions、deployments、instances、node states、diagram、operator inventory 等控制面概念。
- `/api/v1/operators` 已有算子清单能力，包含 metadata、inputSchema、outputSchema 和 usage。
- AI 模块已有 `OperatorCatalogBuilder`，能从 `OperatorRegistry` 构建 prompt-facing operator catalog。

推断：

- 通用画布不应在 resource-gateway 中重造所有版本治理能力。resource-gateway 适合作为“可视化编排最小产品切片”，graph-engine 的版本、诊断、布局和 operator inventory 应该逐步被复用或抽取。
- `visualLayout.v1` 可以继续作为视觉布局合同，但不能承载业务语义；服务端只校验 schemaVersion、rootId、节点 operatorRef、节点/边/分组引用、分组 id/membership、节点/边覆盖、edge kind / port-path metadata、几何值和 viewport 等表现层一致性。

## 3. 设计目标

### 3.1 产品目标

让用户在浏览器画布上完成以下动作：

1. 导入或注册算子库。
2. 浏览算子能力、schema、示例、限制和运行成本。
3. 拖拽算子到画布。
4. 在 schema 约束下连接算子输出到下游输入。
5. 使用字段选择、表达式、transform、decision table、branch、foreach、fallback 等机制表达业务逻辑。
6. 实时获得类型错误、缺失字段、不可达节点、循环依赖、权限不满足、运行时绑定缺失等诊断。
7. 编译、测试、模拟运行、发布、回滚、观测。

### 3.2 架构目标

1. **契约优先**：算子和图都必须有机器可读 contract。
2. **单一语义真相**：视觉布局不能变成第二套业务定义。
3. **前端辅助、后端裁决**：浏览器可以做即时校验，但发布前必须由服务端编译器和验证器裁决。
4. **描述符驱动运行时**：HTTP/API 类能力优先由 descriptor 驱动，不为每个上游服务写一个 Java Operator。
5. **可演进**：支持 operator schema 版本、graph version、兼容性检查、使用影响分析。
6. **可治理**：支持租户、命名空间、环境、权限、密钥、审计、配额和 egress 控制。
7. **可观测**：每个节点运行状态、输入输出摘要、错误、重试、降级和耗时都能回到画布上。

## 4. 范围边界

### 4.1 In Scope

- 通用算子目录和算子 schema 合同。
- resource descriptor 到虚拟算子的映射。
- 可视化 graph draft 模型。
- schema-aware 连接、字段映射、表达式校验。
- Graph IR 到 BLOGE DSL 的生成。
- DSL 编译诊断到画布节点/字段的映射。
- 草稿、版本、发布和运行记录的生命周期。
- request-response 模式下的快速执行与调试。
- 后续兼容 durable/runtime instance 的状态叠加。

### 4.2 Out of Scope for MVP

- 多人实时协同编辑。
- 完整 BPMN 替代品。
- 任意代码执行型自定义算子沙箱。
- 拖拽生成所有 BLOGE 高级语法的完整覆盖。
- 跨组织 marketplace。
- 生产级 IAM 管理后台。

这些不是永远不做，而是不应放进第一阶段，否则会把核心合同拖垮。

## 5. 第一类实体

| 实体 | 说明 | 是否已部分存在 |
| --- | --- | --- |
| Operator Provider | 算子来源，如 Java registry、用户上传 catalog、resource descriptor、远程连接器包 | 部分存在 |
| Operator Definition | 一个可放入画布的算子定义，包括 schema、配置、能力、约束、视觉信息 | 已实现 |
| Virtual Operator | 由 descriptor 或模板派生的算子，例如 `loan-applicant-service.getProfile` | 已实现 |
| Port | 算子输入/输出端口，包含方向、schema、可连接规则 | 已实现 |
| Schema Descriptor | BLOGE 内部 schema 表达，可导入/导出 JSON Schema | 部分存在 |
| Graph Draft | 画布编辑中的图定义，未必可发布 | 已实现 |
| Graph Version | 已编译、可发布、可回滚的图版本 | graph-engine 已存在 |
| Visual Layout | 节点位置、尺寸、分组、视口、视觉注解 | 已存在 |
| Binding | 下游输入如何从上游输出、上下文或常量中取得值 | 已实现 |
| Validation Report | 草稿、节点、边、表达式、schema 和运行时绑定的诊断集合 | 已实现 |
| Execution Plan | 编译后可执行计划，包括节点拓扑、依赖、运行策略 | BLOGE 内部存在 |
| Run / Instance | 一次执行或长运行实例 | 部分存在 |
| Trace / Node State | 节点级运行状态、耗时、输入输出摘要、错误 | 部分存在 |
| Governance Policy | 租户、环境、权限、密钥、配额、egress、审计规则 | 部分存在 |

## 6. 总体架构

```mermaid
flowchart LR
  UI["Canvas UI"]
  API["Authoring API"]
  Catalog["Operator Catalog Service"]
  Draft["Graph Draft Service"]
  Validator["Schema + Policy Validator"]
  Compiler["BLOGE Compiler / DSL Generator"]
  Runtime["Graph Runtime Gateway"]
  Registry["Descriptor / Connector Registry"]
  Version["Version + Layout Store"]
  Observe["Trace / Event / Metrics"]

  UI <--> API
  API --> Catalog
  API --> Draft
  API --> Validator
  API --> Compiler
  API --> Runtime
  Catalog <--> Registry
  Draft <--> Version
  Compiler --> Version
  Runtime --> Observe
  Observe --> UI
```

### 6.1 五个平面

1. **Catalog Plane**：管理可用算子、虚拟算子、schema、示例、权限和运行时绑定。
2. **Authoring Plane**：管理画布草稿、节点、边、字段映射、布局和编辑诊断。
3. **Validation / Compilation Plane**：做 schema 校验、策略校验、BLOGE DSL 生成、编译、lint 和 dry-run。
4. **Runtime Plane**：执行 request-response 图、启动 durable 实例、处理流式输出、回传节点状态。
5. **Governance / Observability Plane**：管理版本、发布、审计、配额、密钥、运行事件和问题回放。

## 7. 核心设计决策

本章列出当前推荐决策。完整备选方案比较、拒绝理由、失效条件和回看触发器见
[BLOGE 可视化编排系统关键决策记录](./bloge-visual-orchestration-decision-record.md)。

### D1. 画布不直接编辑 `visualLayout`

决策：`visualLayout` 只保存视觉信息。画布编辑产生的是语义化 `GraphDraft`，再生成 BLOGE DSL 或编译 artifact。

理由：

- 布局没有能力表达 schema、输入绑定、重试、fallback、decision table、foreach 等语义。
- 如果把布局当图定义，会出现 DSL、编译图、布局三份真相。
- 现有 graph-engine 已经明确 `visualLayout` 是 presentation-only，应沿用这个边界。

代价：

- 需要维护 GraphDraft / Graph IR 模型，而不是只存一份布局 JSON。当前 `resource-gateway-examples` 已经实现 `GraphDraft`，后续代价转为协议演进、兼容性和与 graph-engine 控制面的长期对齐。

### D2. 算子目录是一级资产，不是 Java 反射结果

决策：Java `OperatorRegistry` 反射出来的 schema 只是 catalog source 之一。最终暴露给画布的是归一化 `OperatorDefinition`。

理由：

- 反射 schema 只能描述输入输出形状，不能描述权限、成本、side effect、幂等性、连接规则、密钥需求、UI 控件、运行环境、版本兼容性。
- 用户提供的算子库可能不是 Java class，而是 HTTP resource、远程 worker、AI tool、SQL query、SaaS connector 或 subgraph。

代价：

- catalog schema 需要精心设计，不能只复用当前 `OperatorInventoryEntry`。

### D3. ResourceDescriptor 应升级为虚拟算子来源

决策：`ResourceDescriptor` 不只在运行时被 `httpResource` 解析，也要在设计时投影成可拖拽的 `Virtual Operator`。

例子：

- 底层执行算子仍是 `httpResource`。
- 画布上显示的是 `loan-applicant-service.getProfile`。
- 节点配置默认写入：

```bloge
node fetchApplicant : httpResource {
  input {
    resourceId = "loan-applicant-service.getProfile"
    params = { applicantId: ctx.applicantId }
  }
}
```

理由：

- 业务用户关心“获取申请人画像”，而不是“调用通用 HTTP 资源算子”。
- descriptor 已经包含参数映射和响应协议，可以派生出更具体的输入输出 schema。

代价：

- `ResourceDescriptor` 需要增加或关联设计时 schema，例如 `requestSchema`、`payloadSchema`、`errorSchema`、`fieldHints`。

### D4. 客户端即时校验，服务端权威校验

决策：前端根据 catalog 做交互约束，但 `validate/compile/publish/run` 必须走服务端。

理由：

- 浏览器不能被信任。
- 编译器、operator registry、租户权限、密钥策略和 runtime binding 都在服务端。
- 复杂图校验依赖全局上下文，不适合只在前端完成。

代价：

- 需要高频低延迟的 validate API，否则体验会差。

### D5. MVP 以 resource-gateway 为基版，但不能把平台锁死在 gateway

决策：第一阶段在 `resource-gateway-examples` 中实现通用画布切片，优先支持 descriptor-backed HTTP resource、decision table、transform、branch、foreach 的有限闭环。第二阶段将 catalog、draft、validation、layout contract 下沉或对齐到 graph-engine。

理由：

- resource-gateway 已经有业务感强、容易演示的场景。
- graph-engine 的版本和控制面更适合作为长期平台基础，但第一步直接上全平台会过重。

代价：

- 需要明确哪些类是示例内实现，哪些未来要抽取为共享模块。

## 8. Operator Catalog 设计

本章给出架构层面的 catalog 形态。字段级合同、校验规则、API 请求响应和迁移路径见
[BLOGE 可视化编排协议草案 v1](./bloge-visual-orchestration-protocol-v1.md)。

### 8.1 OperatorDefinition v1

```json
{
  "schemaVersion": "bloge.operatorCatalog.v1",
  "operatorRef": "httpResource",
  "operatorVersion": ">=0.8.9 <1.0.0",
  "display": {
    "name": "HTTP Resource",
    "description": "Execute a descriptor-backed HTTP resource",
    "category": "Resource",
    "icon": "globe",
    "color": "#2563eb"
  },
  "owner": "bloge-platform",
  "tags": ["http", "resource", "gateway"],
  "source": {
    "kind": "java-operator",
    "registryName": "httpResource"
  },
  "inputSchema": {
    "kind": "object",
    "required": ["resourceId", "params"],
    "properties": {
      "resourceId": { "kind": "string" },
      "params": { "kind": "object", "additionalProperties": true }
    }
  },
  "outputSchema": {
    "kind": "object",
    "properties": {
      "resourceId": { "kind": "string" },
      "statusCode": { "kind": "integer" },
      "payload": { "kind": "object", "additionalProperties": true },
      "success": { "kind": "boolean" }
    }
  },
  "configSchema": {
    "kind": "object",
    "properties": {
      "timeout": { "kind": "duration", "default": "PT3S" },
      "retry": { "kind": "retryPolicy" }
    }
  },
  "capabilities": {
    "sideEffect": "EXTERNAL_CALL",
    "idempotency": "UNKNOWN",
    "streaming": false,
    "durable": false,
    "requiresSecrets": true,
    "supportsDryRun": false
  },
  "policy": {
    "tenants": ["*"],
    "namespaces": ["local", "default"],
    "environments": ["dev", "staging"]
  },
  "authoring": {
    "defaultNodeId": "callResource",
    "usageExample": "node callResource : httpResource { input { resourceId = \"...\" params = {...} } }",
    "fieldHints": {
      "input.resourceId": { "control": "resource-picker" },
      "input.params": { "control": "mapping-editor" }
    }
  }
}
```

### 8.2 VirtualOperatorDefinition

虚拟算子由 descriptor、subgraph、远程 worker 或 connector package 派生。对用户来说它是独立算子，对运行时来说它可以退化为已有通用算子的配置。

```json
{
  "schemaVersion": "bloge.operatorCatalog.v1",
  "operatorRef": "resource:loan-applicant-service.getProfile",
  "display": {
    "name": "Get Applicant Profile",
    "category": "Loan / Applicant"
  },
  "source": {
    "kind": "resource-descriptor",
    "resourceId": "loan-applicant-service.getProfile",
    "runtimeOperatorRef": "httpResource"
  },
  "inputSchema": {
    "kind": "object",
    "required": ["applicantId"],
    "properties": {
      "applicantId": { "kind": "string" }
    }
  },
  "outputSchema": {
    "kind": "object",
    "properties": {
      "score": { "kind": "integer" },
      "segment": { "kind": "string" },
      "income": { "kind": "decimal" }
    }
  },
  "lowering": {
    "operatorRef": "httpResource",
    "inputTemplate": {
      "resourceId": "loan-applicant-service.getProfile",
      "params": {
        "applicantId": "{{input.applicantId}}"
      }
    },
    "outputPath": "payload"
  }
}
```

关键点：

- `operatorRef` 可以是用户可见的虚拟引用。
- `lowering` 描述如何降级成 BLOGE 实际运行节点。
- 画布连接时使用虚拟算子的精确 input/output schema。
- DSL 生成时使用 `lowering` 产出真实 BLOGE 节点。

### 8.3 算子来源

| 来源 | 示例 | 优先级 | 说明 |
| --- | --- | --- | --- |
| Java OperatorRegistry | `httpResource`, `transform`, streaming operators | 必须支持 | 从运行时注册表反射基础能力 |
| ResourceDescriptor | `loan-applicant-service.getProfile` | MVP 核心 | 生成更业务化的虚拟算子 |
| Subgraph | `customerRiskAssessment` | 第二阶段 | 将已发布图包装成复用算子 |
| Remote Worker | `fraudModel.score` | 第二阶段 | 远程任务执行，适合 durable |
| AI Tool | `llm.extractFields` | 第二阶段 | 强治理，需 structured output schema |
| User Catalog JSON/YAML | 外部导入 | 第二阶段 | 支持非 Java 算子声明 |

## 9. Graph Draft / Graph IR 设计

本章解释为什么需要 GraphDraft。可实现的 `bloge.graphDraft.v1` 顶层结构、节点、边、binding、状态和 lowering 规则见
[BLOGE 可视化编排协议草案 v1](./bloge-visual-orchestration-protocol-v1.md)。

### 9.1 为什么需要 GraphDraft

前端不能只拼 DSL 字符串。原因很简单：字符串不适合作为实时编辑模型。

画布需要知道：

- 节点 id、位置、选中状态、折叠状态。
- 节点使用的 operator catalog version。
- 输入字段来自哪个输出字段、常量、ctx 变量或表达式。
- 哪些连接是数据依赖，哪些是控制依赖。
- 哪些错误定位到哪个节点、哪个字段、哪条边。
- 哪些节点是虚拟算子，需要 lowering。

这些都应该由结构化 GraphDraft 表达。

### 9.2 GraphDraft v1

```json
{
  "schemaVersion": "bloge.graphDraft.v1",
  "draftId": "draft-loan-policy",
  "graphName": "customLoanPolicy",
  "tenantId": "demo-tenant",
  "namespace": "local",
  "nodes": [
    {
      "id": "fetchApplicant",
      "operatorRef": "resource:loan-applicant-service.getProfile",
      "operatorVersion": "1.0.0",
      "config": {
        "timeout": "PT3S",
        "retry": { "attempts": 1, "backoff": "PT0.2S" }
      },
      "inputs": {
        "applicantId": {
          "kind": "expression",
          "expr": "ctx.applicantId"
        }
      },
      "visual": {
        "x": 80,
        "y": 220,
        "width": 184,
        "height": 76
      }
    },
    {
      "id": "loanPolicy",
      "operatorRef": "bloge:decisionTable",
      "inputs": {
        "score": {
          "kind": "path",
          "sourceNode": "fetchApplicant",
          "path": "output.score"
        },
        "amount": {
          "kind": "expression",
          "expr": "ctx.requestedAmount"
        }
      },
      "config": {
        "hitPolicy": "unique",
        "outputSchema": {
          "kind": "object",
          "properties": {
            "decision": { "kind": "string" },
            "rate": { "kind": "decimal" },
            "ruleId": { "kind": "string" }
          }
        }
      }
    }
  ],
  "edges": [
    {
      "id": "fetchApplicant->loanPolicy:score",
      "kind": "data",
      "source": { "nodeId": "fetchApplicant", "path": "output.score" },
      "target": { "nodeId": "loanPolicy", "path": "input.score" }
    }
  ],
  "outputNode": "assembleLoanDecision"
}
```

### 9.3 Binding 类型

| Binding Kind | 用途 | 示例 |
| --- | --- | --- |
| `constant` | 固定值 | `"approved"` |
| `contextPath` | 从 `ctx` 读取 | `ctx.userId` |
| `nodePath` | 从上游节点指定输出端口读取 | `fetchProfile.output.payload.name` |
| `expression` | BLOGE 表达式 | `ctx.amount * 1.2` |
| `objectTemplate` | 结构化对象模板 | `{ id: ctx.id, score: risk.output.score }` |
| `transformRef` | 显式 transform 节点 | `normalizeApplicant.output` |
| `secretRef` | 密钥引用 | `secret("crm.apiKey")` |

MVP 可以先支持 `constant`、`contextPath`、`nodePath`、`expression`、`objectTemplate`。
当多个 input port 暴露同名字段时，binding key 必须允许端口限定命名，
例如 `customer.id` 和 `order.id`；真实校验位置由 `targetPort` 和
`targetPath` 决定，而不是由 map key 猜测。`targetPath` 必须支持嵌套
object path，例如 `applicant.score`；画布字段枚举和服务端校验都要按完整
schema path 工作，否则复杂业务 payload 会被迫扁平化。
同一节点内解析后的 `targetPort + targetPath` 必须具备单一所有者；
`objectTemplate` 字段也要展开参与检查，且 `applicant` 与 `applicant.score`
这样的 root/path 前缀重叠必须阻断。否则画布看到的是两个来源，DSL 运行时
却只能得到最后一次写入或不确定覆盖。
required 判断也必须沿展开后的字段证明：普通整对象 binding 可以凭 source
schema 证明 `applicant.score` 存在，但 `objectTemplate` 不能只绑定
`applicant` 父对象就满足嵌套 required，必须递归提供对应叶子字段。

当前 `resource-gateway-examples` 已把 `contextPath` 纳入 schema gate：前端会从 Context JSON 推导草稿 `inputSchema` 并把兼容的 `ctx.*` 字段放入 source picker，服务端会在严格 input schema 下阻断未知 `ctx` path 和 source/target 类型不兼容。

## 10. Schema 约束与连接规则

字段级 schema envelope、类型兼容、缺 schema 降级和诊断 code 已在
[BLOGE 可视化编排协议草案 v1](./bloge-visual-orchestration-protocol-v1.md) 中展开。本章保留关键原则。

### 10.1 连接基本规则

当用户把 A 的输出连到 B 的输入时，系统必须检查：

1. A 节点是否存在且可达。
2. A 的输出端口和输出 path 是否存在。
3. B 的输入端口和输入字段是否存在。
4. A 输出字段类型是否可赋值给 B 输入字段类型。
5. B 输入字段是否 required。
6. 如果类型不兼容，是否存在可插入 transform 或 adapter。
7. 该连接是否违反 side-effect、streaming、durable、tenant、environment 或 permission 约束。

### 10.2 类型兼容规则

| Source | Target | 结果 | 说明 |
| --- | --- | --- | --- |
| `integer` | `integer` | 允许 | 精确匹配 |
| `integer` | `decimal` | 允许 | 安全拓宽 |
| `decimal` | `integer` | 警告/需 transform | 可能丢精度 |
| `string` | `enum` | 禁止/需 transform | 没有来源值域证明，不能隐式接入枚举输入 |
| `object` | `object` | 结构化检查 | required 字段必须满足 |
| `array<T>` | `array<U>` | 检查 item 兼容 | foreach 场景重要 |
| `oneOf`/`anyOf` | 任意 | 保守检查，可显式消歧 | source union 必须所有分支可赋值；target `anyOf` 至少一个分支可接；target `oneOf` 默认必须唯一分支可接；binding 可用 `targetUnionBranch` 指定 root target branch |
| `object` | `string` | 禁止或需表达式 | 不能隐式转 JSON |
| `unknown` | 任意 | 警告 | 可继续草稿，不可无条件发布 |

resource-gateway 示例当前已在 binding 和 edge 校验中递归检查 object required
字段、`array.items` 兼容性和 enum 值域集合：target object 的 required 字段必须
能从 source schema 中证明为 required 且类型兼容，source enum values 必须是
target enum values 的子集，普通 `string` 不能直接接入 enum input。正式 draft
还会校验 data edge 与语义依赖一致，包含 `nodePath` binding 和 config expression
引用；`nodePath` binding 必须有可见 data edge，防止画布连线和 DSL 输入语义分叉。
服务端还会在 `nodePath` binding、表达式引用、data edge source endpoint、带路径的 graph
output selection，以及 whole-output graph selection 暴露输出端口时检查非默认 output port
name 是否能作为 BLOGE DSL path segment，
用于兜住历史 catalog 或外部 projector 绕过导入校验后的发布前安全边界。
未知 binding kind 会在 `GraphDraftValidator` 被阻断，不能落到 codegen 的 literal fallback
绕过 target schema gate。
浏览器侧的 connection hint 和 source picker 也要复用这些关键规则，至少覆盖
object required 字段证明、array item 递归兼容、enum 值域子集、`oneOf`/`anyOf`
union 的保守兼容提示，以及普通 `string` 不能隐式接入 enum，减少画布交互与服务端裁决之间的断层。拖线落点和
inspector source picker 写入 binding 前都必须调用服务端 connection preview
gate，本地 hint 不能成为最终授权。
当前服务端 schema gate 和浏览器本地 hint 已支持 `oneOf` / `anyOf` 作为受限 visual union：
运行时 value validation 中 `oneOf` 必须唯一命中，`anyOf` 至少命中一个；
schema-to-schema 连接兼容采用保守策略，避免把无法证明唯一性的 union
直接放入下游输入。浏览器 contract panel 和 operator library profile 会展示
union 分支摘要，帮助用户在拖线前看见端口约束；input inspector 已支持对 root
target union 选择 `targetUnionBranch`，draft 保存、connection preview、binding
validation 和 data edge validation 会共同按选中 branch 消歧。分支内字段仍不能被
混成稳定 handle；后续如果要支持嵌套 union branch map，需要把 branch path、
字段枚举和 lowering 语义一起收敛，不能只做前端展开。
同一节点内多个 binding 写入同一解析后输入目标，或 root/path 前缀重叠，
会以 `visual.input.duplicateTarget` 阻断；不同 input port 上同名字段仍然合法，
例如 `customer.id` 和 `order.id`。
无 design contract 的旧 resource 仍按 opaque schema 降级；一旦注册
`ResourceDesignContract`，request/response schema 就和用户导入 operator
library 一样必须通过服务端结构校验，缺失 `items` 的 array、未知
`required` 字段、缺失 enum values 和 examples 中的原始 secret 都会被
admin upsert gate 阻断。

### 10.3 required 字段规则

对每个节点：

- 所有 required input 必须有 binding。
- binding 不能引用不可达节点。
- data edge 必须对应一个实际语义依赖；`nodePath` binding 必须对应画布上的 data edge。
- binding 如果引用 optional 输出字段，需要下游声明 fallback、default 或 nullable。
- 如果输入 schema 有 `oneOf` / union，服务端已能做保存/运行/连接层面的保守校验；root target 可通过 `targetUnionBranch` 显式选择分支，嵌套 union 仍需要后续 branch path 模型。

### 10.4 表达式校验

表达式必须做三层校验：

1. **语法校验**：BLOGE parser 能解析。
2. **引用校验**：引用的 `ctx` 字段和节点输出 path 存在或被标记为 dynamic。
3. **类型推断**：表达式结果能赋给目标 input schema。

当前 `BlgeExpressionEvaluator.precompile()` 已经能做注册时预编译，但还不够。通用画布需要表达式的引用定位和类型推断，否则错误只能在运行时暴露。

## 11. DSL 生成与编译链路

### 11.1 编译流程

```mermaid
sequenceDiagram
  participant UI as Canvas UI
  participant API as Authoring API
  participant VAL as Validator
  participant GEN as DSL Generator
  participant COMP as GraphLoader / Compiler
  participant RT as GraphEngine

  UI->>API: save draft patch
  API->>VAL: validate draft incrementally
  VAL-->>UI: node/edge/field diagnostics
  UI->>API: compile draft
  API->>GEN: lower virtual operators + generate BLOGE DSL
  GEN->>COMP: parse/lint/compile
  COMP-->>API: compiled graph + diagnostics
  API-->>UI: DSL preview + diagnostics + layout
  UI->>API: run with context
  API->>RT: execute compiled graph
  RT-->>UI: output + node states + trace
```

### 11.2 Lowering 示例

画布节点：

```json
{
  "id": "fetchApplicant",
  "operatorRef": "resource:loan-applicant-service.getProfile",
  "inputs": {
    "applicantId": { "kind": "expression", "expr": "ctx.applicantId" }
  }
}
```

生成 DSL：

```bloge
node fetchApplicant : httpResource {
  input {
    resourceId = "loan-applicant-service.getProfile"
    params = { applicantId: ctx.applicantId }
  }
  timeout = 3s
  retry = { attempts: 1, backoff: 200ms }
}
```

### 11.3 DSL 反向导入

长期应该支持 DSL import，但不能要求 v1 完全无损。

阶段策略：

1. MVP：GraphDraft -> DSL 单向生成，DSL 预览可编辑但编辑后以 compiler layout 回显。
2. Phase 2：支持 DSL -> Draft 的有限导入，覆盖普通 node、transform、decision table、direct edge。
3. Phase 3：支持复杂结构导入，如 foreach、branch、stream、wait、subgraph。无法导入的语义保留为 read-only block。

## 12. API 设计草案

本章是架构级 API 切分。请求/响应示例、并发规则、诊断结构见
[BLOGE 可视化编排协议草案 v1](./bloge-visual-orchestration-protocol-v1.md)。

### 12.1 Operator Catalog API

| Method | Path | 说明 |
| --- | --- | --- |
| `GET` | `/api/visual/operators` | 查询可用算子，支持 pattern、tag、tenant、namespace、environment、source/lowering/capability/runtime-readiness facets |
| `GET` | `/api/visual/operators/{operatorRef}` | 获取单个算子定义 |
| `GET` | `/api/visual/operators/{operatorRef}/usage` | 当前已实现：查询某个 operatorRef 被哪些 stored draft / immutable publication 节点使用，并返回 saved/frozen fingerprint 与当前 catalog fingerprint 的状态 |
| `POST` | `/api/visual/operator-catalogs/import` | 导入用户提供的 catalog JSON/YAML |
| `POST` | `/api/visual/operator-catalogs/validate` | 校验 catalog 定义 |
| `GET` | `/api/visual/resource-operators` | 将 resource descriptors 投影为虚拟算子 |

resource-gateway 示例当前以 `/admin/visual-operator-libraries` 暴露用户库管理：
`POST /admin/visual-operator-libraries/validate` 返回 diagnostics、impact 和服务端派生
`bloge.visualOperatorLibraryProfile.v1`，但不落库；
`POST/PUT /admin/visual-operator-libraries` 在写入前执行同一校验，阻断空库、
重复 `operatorRef`、跨已导入库冲突的 `operatorRef`、重复端口、
覆盖内置算子的 `operatorRef`、占用 `resource:` 命名空间的用户算子、
不支持 lowering mode、非法 schema kind、缺失 `items` 的 array、native
lowering 缺可执行 BLOGE operatorRef、design-only lowering 误声明 executable
operatorRef、executable lowering mode 下非默认 output port 或 schema path
字段无法渲染为 BLOGE DSL path segment、transform lowering 缺 assignments、
assignment target 不在 output schema 中、template 引用不存在 input path、
以及 `required` 引用不存在字段等硬错误。`OperatorDefinition.policy`
当前已支持 `tenants`、`namespaces`、`environments`，`/api/visual/operators`
可按 scope 过滤，`GraphDraftValidator` 在 validate/compile/run/publish 前
返回 `visual.operator.policyDenied` 阻断越权 operator 使用。`OperatorDefinition`
当前还包含服务端派生的 `runtimeReadiness`，按 source/lowering/capability/diagnostics
给出 `RUNTIME_EXECUTABLE`、`DESIGN_ONLY`、`RUNTIME_BLOCKED`、`GOVERNANCE_REVIEW`
或 `CATALOG_REPAIR_REQUIRED`，并作为 `/api/visual/operators` 的 `runtimeReadiness`
filter 与 `facets.runtimeReadinessStates` 聚合维度返回，避免浏览器用前端启发式伪造控制面判断。
同一口径也进入 operator-library validate/import 的 `profile`：服务端按规范化后的 operator、
validation diagnostics 和 runtime inventory warning 计算 operator count、schema field count、
DSL-unsafe/dynamic schema 计数、facets、catalog-repair、runtime-blocked、governance-review 与
design-only 摘要；浏览器只有在用户继续编辑 JSON 或服务端 profile 缺失时才回退到本地即时预览。
当前还支持
`lowering.mode=design` 的 schema-only authoring：这类用户算子可以进入 catalog、
拖拽、连线、保存、导出、被 schema validator 校验，并可通过浏览器发布模式或 API
`artifactKind=DESIGN` 发布成非执行型设计制品；compile/run/default executable publish 会返回
`visual.codegen.designOnlyOperator`，直到后续绑定 native / transform / branch 等可执行 lowering。
draft validate response 现在返回服务端派生的 `bloge.visualGraphReadiness.v1`：
`valid` 只表达 draft contract 是否有 blocking error，`readiness` 单独表达
`runtime-executable`、`design-only`、`runtime-blocked`、`governance-review` 或
`draft-repair-required` 图级状态、可发布 artifact kind 和 node-level readiness 摘要。
这保证 schema-only 图可以是 `valid=true`，同时明确 `executable=false` 且只能冻结为
`DESIGN` artifact，而不是被误解成系统运行失败。
publication result 也会在成功或拒绝响应中携带同一份 validation/readiness；
浏览器因此可以把 Server Check 的 readiness 反向施加到发布控件上：只允许
`DESIGN` 的图会自动切到设计制品发布，`draft-repair-required` 这类没有
publishable artifact kind 的图则禁用发布入口。
选中已发布 artifact 时，浏览器展示 publication 冻结的 readiness 和非执行节点清单；
这是审阅历史设计制品的依据，不依赖当前 catalog 重新推断。
同一 admin API 已具备 registry-aware impact preflight：删除、禁用或替换仍被 stored draft 引用的
operatorRef 会被阻断，same-ref fingerprint drift 会作为 warning 暴露；对于 immutable publication，
当前 executable artifact 运行不依赖最新 catalog，因为 publication 持有 frozen DSL 和 operator snapshots；
DESIGN artifact 可审阅但不可运行，浏览器会禁用 run/golden 动作，也不会投影成 `publication:*` 子图算子。validate 会返回
publication 级 warning，提示 replay、recertification 或 republish 前需要重新审计。直接删除 library 时，
如果已有 published artifact 引用了该库内 operatorRef，服务端要求 `force=true` 才允许删除。
导入阶段还会 warning-gate capability 和治理风险：streaming/durable 算子可以被显式确认后进入 catalog，
但当前 request-response runtime 会阻断使用它们的 draft；secret-backed execution 和
`NON_IDEMPOTENT` 外部副作用也必须经 `ackWarnings=true` 确认后才会写入。
发布阶段同样 warning-gate production promotion：stored draft validate 如果只返回
warning-level diagnostics，`/api/visual/drafts/{draftId}/publish` 会先返回 `409`，
要求作者审阅并携带 `ackWarnings=true` 后才写入 immutable publication。
validate、warning-gated import/replace 和 delete conflict 响应会返回
`bloge.visualOperatorLibraryImpact.v1`，按 error/warning、affected draft、
affected draft node target、publication、operatorRef 和 diagnostic code 给出机器可消费的影响摘要。
浏览器导入面板优先消费该合同，再展示 diagnostics 明细，让作者先判断
替换影响面；affected draft 是可操作入口，可以直接加载对应草稿并聚焦受影响节点，
进入 schema/fingerprint/binding 审阅，再决定是否二次确认 `ackWarnings=true`
或启用 `force=true`。
`/api/visual/operators/{operatorRef}/usage` 已把这种影响分析拆成可查询的
`bloge.visualOperatorUsage.v1` 合同：draft usage 返回 saved fingerprint
状态，publication usage 返回 frozen fingerprint 状态，并在 frozen snapshot
与当前 catalog definition 可比较时给出 changed-surface 摘要。
如果用户审计后决定接受 drift，草稿层通过专用 rebase API 刷新节点
fingerprint snapshot；普通保存和 PATCH 仍保留既有 snapshot，避免无意中把
旧算子语义升级成新算子语义。

### 12.2 Draft API

| Method | Path | 说明 |
| --- | --- | --- |
| `POST` | `/api/visual/drafts` | 创建图草稿 |
| `GET` | `/api/visual/drafts/{draftId}` | 获取草稿 |
| `GET` | `/api/visual/drafts/{draftId}/export` | 当前已实现：导出 `bloge.visualGraphDraftExport.v1` 包，包含 draft snapshot、operator snapshots 和 export-time diagnostics |
| `POST` | `/api/visual/drafts/import` | 当前已实现：以新 identity 导入 export bundle，刷新当前 catalog fingerprints，存储前校验 bundle/draft contract，并返回 `bloge.visualGraphDraftImportResult.v1` 目标环境 diagnostics |
| `PATCH` | `/api/visual/drafts/{draftId}` | 保存节点、边、layout、binding patch |
| `POST` | `/api/visual/drafts/{draftId}/operator-fingerprints/rebase` | 当前已实现：显式刷新选中节点或全部节点的 service-managed operator fingerprint snapshot，使用 `expectedRevision` 防并发覆盖，并对未知节点/当前 catalog 缺失算子返回结构化 diagnostics |
| `POST` | `/api/visual/drafts/{draftId}/validate` | 增量或全量校验；当前实现的 transient `/api/visual/drafts/validate` 返回 `valid`、`diagnostics` 和 `bloge.visualGraphReadiness.v1` 图级 runtime/design readiness |
| `POST` | `/api/visual/drafts/{draftId}/compile` | 生成 DSL 并编译 |
| `POST` | `/api/visual/drafts/{draftId}/run` | 使用测试 context 运行 |
| `POST` | `/api/visual/drafts/{draftId}/publish` | 发布为 graph version；浏览器和 API 默认 `artifactKind=EXECUTABLE`，也支持 `artifactKind=DESIGN` 冻结非执行型设计制品；warning-level diagnostics 需 `ackWarnings=true` 后才写入；响应在成功和拒绝时都保留 validation/readiness，供客户端按 artifact kind 纠偏 |

### 12.3 Runtime / Trace API

| Method | Path | 说明 |
| --- | --- | --- |
| `GET` | `/api/visual/runs` | 当前已实现：按 source/draft/publication/graph/outcome/limit 查询运行历史 |
| `GET` | `/api/visual/runs/stats` | 当前已实现：按同一过滤窗口聚合成功率、blocked/error 和 p50/p95/max latency |
| `GET` | `/api/visual/runs/{runId}` | 当前已实现：获取 shape-only 运行记录 |
| `GET` | `/api/visual/runs/{runId}/nodes` | 后续方向：获取节点状态 |
| `GET` | `/api/visual/runs/{runId}/events` | 后续方向：SSE 运行事件 |
| `GET` | `/api/visual/runs/{runId}/trace` | 当前已实现：获取节点 status、operator metadata、选中输出标记、result shape summary、按节点归属的 diagnostics、errors 和 DSL 的 shape-only replay trace |

### 12.4 Golden Case API

| Method | Path | 说明 |
| --- | --- | --- |
| `GET` | `/api/visual/golden-cases?publicationId=...` | 当前已实现：查询绑定某个 immutable publication 的 golden regression cases |
| `GET` | `/api/visual/golden-cases/{caseId}` | 当前已实现：读取单个 golden case |
| `POST` | `/api/visual/golden-cases` | 当前已实现：为已发布版本保存样例 context、outputNode、期望输出、可选 output assertions、numeric tolerance assertions 和 output schema assertions |
| `POST` | `/api/visual/golden-cases/{caseId}/run` | 当前已实现：用 frozen publication DSL 执行 golden case，写入 run history，并返回 exact-output、value/tolerance assertion 或 schema assertion diagnostics |
| `POST` | `/api/visual/golden-cases/publications/{publicationId}/run` | 当前已实现：执行某个 publication 绑定的全部 golden cases，汇总 total/passed/failed 并为每个 case 写入 run history |
| `POST` | `/api/visual/golden-cases/publications/{publicationId}/certify` | 当前已实现：执行 suite，持久化该 publication 的最新 golden certification，并记录当次 case-set fingerprint |
| `GET` | `/api/visual/golden-cases/publications/{publicationId}/certification` | 当前已实现：读取该 publication 最近一次 golden certification |
| `GET` | `/api/visual/golden-cases/publications/{publicationId}/certification/status` | 当前已实现：读取 promotion-readiness 状态，区分 `CERTIFIED`、`STALE`、`FAILED`、`MISSING_CASES`、`UNCERTIFIED` 并返回结构化 diagnostics |

### 12.5 与现有 API 的关系

- `/admin/resources` 保留，用于管理底层 ResourceDescriptor。
- 新增 `/api/visual/resource-operators` 将 descriptor 转成画布可用虚拟算子。
- `/api/gateway/examples/compose/run` 可演进为 `/api/visual/drafts/{id}/run`，但示例端点可以保留兼容。
- graph-engine 的 `/api/v1/operators` 可作为长期 operator inventory 后端，但需要扩展 authoring metadata。

## 13. 前端画布设计

### 13.1 画布主区

画布必须支持：

- 拖拽添加节点。
- 节点移动、选择、删除、复制。
- schema-aware 连线。
- 嵌套 object schema 展开为可连接字段 path。
- 连线时高亮可连接字段。
- 不兼容连接给出错误原因和可选修复动作。
- 运行后叠加节点状态：`NOT_STARTED`、`RUNNING`、`COMPLETED`、`FAILED`、`SKIPPED`、`CANCELLED`、`WAITING`。
- 对分支、foreach、parallel、stream 使用 group 或泳道表达。

### 13.2 左侧 Operator Palette

Palette 不应写死三类节点。它从 catalog 获取：

- 分类。
- 搜索和标签过滤。
- 算子描述。
- 输入输出 schema 摘要。
- 权限/环境可用性。
- 是否可 dry-run。
- 是否有 side effect。

### 13.3 右侧 Inspector

根据选中对象显示不同编辑器：

| 对象 | Inspector 内容 |
| --- | --- |
| 节点 | operator 信息、validation/trace diagnostics 局部归因、input binding、config、timeout/retry/fallback、schema、connectability ready/wired/blocked 状态、blocked preview 和 blocked reason、复制当前配置、快捷复制/删除入口、当前画布上下游影响面、逐条/批量 detach 引用、跨 draft/publication 的 operator usage index 与 fingerprint drift、节点 saved-vs-current fingerprint snapshot 和显式 rebase 动作，并在画布节点上回显 usage 风险 badge |
| 边 | source path、target path、类型兼容结果、transform 建议 |
| decision table | input columns、output schema、hit policy、规则矩阵、冲突检测 |
| transform | output object builder、表达式编辑、类型预览 |
| graph | input schema、output node、环境、发布策略 |
| run | 节点状态、输入输出摘要、错误、耗时、重试 |

### 13.4 底部 Diagnostics / DSL / Run Console

建议底部三 tab：

1. Diagnostics：按 severity、node、field 分组，并能从聚合摘要或 F8/Shift+F8 快捷键过滤/聚焦/轮转到受影响节点，显示当前队列位置、过滤后的 issue 数和未展开节点数量；当前过滤节点即使排在折叠范围外也保持可见，Esc 可清除当前过滤。
2. DSL Preview：展示生成的 BLOGE DSL，支持复制和高级编辑。
3. Run Console：输入 context，执行，查看输出和节点 trace。

## 14. 后端模块建议

在 resource-gateway 内先新增以下包，保持示例独立：

```text
com.leanowtech.bloge.gateway.visual.catalog
com.leanowtech.bloge.gateway.visual.draft
com.leanowtech.bloge.gateway.visual.validation
com.leanowtech.bloge.gateway.visual.codegen
com.leanowtech.bloge.gateway.visual.runtime
```

### 14.1 catalog

职责：

- 从 `OperatorRegistry` 读取 Java operators。
- 从 `ResourceRegistry` 读取 descriptors 并生成 virtual operators。
- 合并用户导入 catalog。
- 过滤 tenant / namespace / environment 可用性。

关键接口：

```java
interface VisualOperatorCatalog {
    List<OperatorDefinition> search(OperatorCatalogQuery query);
    OperatorDefinition require(String operatorRef);
}
```

### 14.2 draft

职责：

- 保存 GraphDraft。
- 保存 visual layout。
- 做 patch 合并和版本号递增。
- 提供 optimistic locking。

MVP 可用内存或 H2，正式方向应使用 graph-engine version store 或独立表。

### 14.3 validation

职责：

- 校验节点 operator 是否存在。
- 校验 input binding。
- 校验 schema 兼容。
- 校验表达式语法。
- 校验权限、环境、side effect、密钥。
- 产出定位到 node/edge/field 的 diagnostics。

### 14.4 codegen

职责：

- 将虚拟算子 lowering 为真实 BLOGE 节点。
- 生成 BLOGE DSL。
- 对 native executable `operatorRef` 做 DSL-safe 渲染：`IDENT(.IDENT)*` 裸写，
  其他命名空间安全 token 生成字符串形式，避免用户库 import 成功但 DSL
  preview/compile 失败。
- 对 native 输入做对象树 lowering：`targetPort + targetPath` 先合成为
  BLOGE 对象字面量，避免复杂 schema 绑定被输出成非法 dotted input field。
- 浏览器 DSL preview 必须和服务端 codegen 使用同一类 native rendering 规则，
  不能让画布展示一份不可编译的“近似 DSL”。
- 保持稳定排序，减少 diff 噪音。
- 将 compiler diagnostics 映射回 draft 节点。
- `/compile` 与 `/publish` 不能停在 codegen 成功，必须把生成 DSL 交给 BLOGE
  compiler；只有 compiler 无 blocking error 时才允许返回 compiled/generated
  success 或创建 immutable publication。

### 14.5 runtime

职责：

- 使用 `GraphLoader.loadWithDiagnostics()` 编译 DSL。
- 使用 `GraphEngine.execute()` 执行 request-response 图。
- 返回 selected output、raw node results、statusMap、elapsed、errors。
- 后续接入 durable runtime 和 SSE events。

## 15. 生命周期

```mermaid
stateDiagram-v2
  [*] --> Draft
  Draft --> Validating
  Validating --> Draft: diagnostics
  Validating --> Compiled: no blocking errors
  Compiled --> TestRun
  TestRun --> Draft: edit
  TestRun --> Review
  Review --> Published
  Published --> Deployed
  Deployed --> Running
  Running --> Observed
  Observed --> Draft: clone new version
  Published --> Deprecated
  Deprecated --> [*]
```

### 15.1 Draft

允许保存不完整图，但要持续显示 diagnostics。

### 15.2 Compiled

必须满足：

- 无 blocking schema errors。
- BLOGE DSL 编译成功。
- 所有 operatorRef 可解析。
- 所有 required inputs 有绑定。

### 15.3 Review

适合加入：

- schema compatibility report。
- operator usage impact。
- side-effect review。
- secret/egress review。
- test case coverage。

### 15.4 Published

发布后 artifact 应不可变：

- canonical DSL。
- graph hash。
- operator fingerprints。
- input/output schema。
- visual layout。
- validation report。

### 15.5 Running / Observed

运行后必须把事实回流到图：

- 哪些节点执行。
- 哪些节点被跳过。
- 哪些分支命中。
- 哪些规则匹配。
- 哪些资源调用失败、重试、降级。
- 输出 schema 是否与声明漂移。

## 16. 复杂业务编排能力覆盖

| 场景 | 画布表达 | BLOGE 能力 | MVP 优先级 |
| --- | --- | --- | --- |
| API 聚合 | 多个 resource 节点并行，transform 汇总 | DAG + transform | P0 |
| 决策矩阵 | decision table 节点 | `decision_table` | P0 |
| 条件分支 | branch 节点或 conditional edge | conditional edges | P1 |
| 列表逐项处理 | foreach group | foreach / iteration | P1 |
| 多提供方降级 | fallback edge / retry config | retry + fallback | P1 |
| 流式输出 | stream node/lane | streaming operators | P2 |
| 人工审批 | user task | durable / task | P2 |
| 长等待 | wait/timer/signal | durable waits | P2 |
| 子图复用 | subgraph operator | published graph as operator | P2 |
| AI 调用 | LLM/tool operator | AI operators | P2 |

判断：P0/P1 足以吃下大量 API 编排、风控、订单、客服、资源聚合、政策决策场景。P2 决定它是否能成为更通用的业务流程平台。

## 17. 治理与安全

### 17.1 权限模型

至少需要四类权限：

| 权限 | 说明 |
| --- | --- |
| `operator.view` | 浏览算子 |
| `graph.draft.edit` | 编辑草稿 |
| `graph.run.test` | 测试运行 |
| `graph.publish` | 发布版本 |
| `resource.execute` | 执行资源 |
| `secret.bind` | 绑定密钥 |
| `policy.override` | 覆盖治理策略 |

### 17.2 租户与命名空间

GraphDraft、OperatorDefinition、ResourceDescriptor、Run 都必须携带：

- tenantId
- namespace
- environment
- owner
- createdBy / updatedBy

当前 `resource-gateway-examples` 已把 draft revision 审计元数据落成代码：
`GraphDraft.revisionMetadata` 记录 `createdAt/createdBy/updatedAt/updatedBy`、
`changeSource`、`changeSummary` 和 `changedPaths`。`PATCH /api/visual/drafts/{draftId}`
可以携带 actor/source/summary，repository 在写入当前 draft 与 revision history 时固化
这些字段；未接入真实身份系统前默认 actor 为 `visual-canvas`。这不是完整审批流，
但已经把协作、回滚、事故追踪所需的最小审计锚点放进协议和持久化快照。

### 17.3 密钥处理

画布不得展示真实 secret。只能展示：

- secret ref。
- secret scope。
- 是否已绑定。
- 是否对当前环境可用。

运行时由 gateway 注入 secret，GraphDraft 中不得持有明文 token。

### 17.4 外部调用治理

对 `EXTERNAL_CALL` 类算子：

- egress allowlist。
- rate limit。
- circuit breaker。
- timeout 上限。
- payload size 上限。
- cache policy。
- audit log。

resource-gateway 已有 rate limiting、cache、circuit breaker，可以作为治理样板。

## 18. 可观测性与反熵机制

这类系统最容易腐化的地方不是画布，而是隐藏的例外越来越多：

- 某些算子输出 schema 实际漂移。
- 某些资源 descriptor 改了但下游图没感知。
- 用户用表达式绕过 schema。
- 图越画越大，没人知道哪些节点还被使用。
- 失败只能看日志，画布无法解释。

必须内建反熵机制：

1. **Schema Drift Detection**：运行时采样输出，与声明 schema 比较。
2. **Operator Usage Index**：每个 operator 被哪些 graph version 使用。
3. **Descriptor Impact Analysis**：更新 descriptor 前提示影响的图。
4. **Golden Test Cases**：当前已支持每个发布版本绑定样例输入、期望输出、基础 value/path assertions、`PATH_APPROX_EQUALS` numeric tolerance assertions 和 `OUTPUT_MATCHES_SCHEMA` schema assertions，并用 frozen publication DSL 执行 single-case 与 suite-level 回归；浏览器 Publications 面板已支持从最近一次 publication run 输出保存 exact-output case，或用多断言队列保存 value/path assertion、numeric tolerance assertion 与 output-schema assertion 组合；latest certification 作为独立控制面状态保存，不反写 immutable publication；promotion-readiness status 通过 case-set fingerprint 识别 stale certification，并以 `promotionReady` 与 diagnostics 支撑推广准入。
5. **Runtime Trace Replay**：当前已支持从 run history 打开 shape-only trace，按节点查看 status、operator metadata、结果形状、选中输出、诊断归属和 DSL，并在当前画布匹配节点上显示 replay badge；当历史 trace 节点已不在当前画布中时，浏览器会报告 replay coverage 和 missing nodes，避免误判为完整回放。后续可补更细的节点耗时、事件流和时间轴重放。
6. **Dead Node Detection**：提示不可达节点、永不命中分支、未使用输出。
7. **Policy Audit**：记录谁发布、谁覆盖权限、谁修改 descriptor。
8. **Compatibility Gate**：operator schema 或 graph output schema 不兼容时阻断发布。

## 19. 与现有 resource-gateway 的演进关系

### 19.1 保留

- `HttpResourceOperator` 作为通用 HTTP runtime operator。
- `ResourceDescriptor` 作为外部 API 的底层描述符。
- `ResponseProtocol` 作为成功/失败解释抽象。
- `/admin/resources` 作为资源管理 API。
- 现有 `.bloge` 示例作为 seed graph。
- 现有 `Custom Composer` 作为体验原型。

### 19.2 改造状态

| 原始问题 | 当前状态 | 后续方向 |
| --- | --- | --- |
| `OPERATOR_TYPES` 写死在 JS，无法接收用户算子库 | 已缓解：browser composer 从 `/api/visual/operators` 加载 native、user-library、resource-backed operators，并动态注册 palette spec | 新能力仍必须先进入 `OperatorDefinition`，不能只补前端 |
| builder 节点字段写死贷款场景，不能通用 | 已缓解：inspector 根据 input/config schema 渲染字段、source picker 和 config controls | 继续补复杂嵌套 config 的专业化控件和浏览器回归测试 |
| DSL 字符串由 JS 拼接，难以校验和扩展 | 已缓解：GraphDraft -> DSL 在服务端生成，compile/run/publish 走服务端 validator/generator/compiler | 保留 DSL preview，但不让手写 DSL 成为画布语义源 |
| `ResourceDescriptor` 无精确 payload schema，画布无法类型安全连接 | 已缓解：独立 `ResourceDesignContract` 补足 request/payload schema 并投影 resource virtual operator；已提供 OpenAPI operation discovery 和 operation 到 contract 草案的 preview endpoint | 继续深化 descriptor 生成建议、schema diff 和更广义接口导入 |
| `/compose/run` 只注册 `httpResource`，无法执行通用算子 | 部分缓解：visual draft run 使用 GraphDraft DSL generator 和现有 dynamic runner；user-library transform/branch/native lowering 已可参与；无实现的 schema-only operator 可用 `lowering.mode=design` 进入画布，并可用 `artifactKind=DESIGN` 冻结设计制品；compile/run/default executable publish 阶段明确阻断 | 补 remote worker binding 和更完整 runtime operator availability |
| diagnostics 只展示 compiler 信息，缺少 schema/权限/策略错误 | 已缓解：`VisualDiagnostic` 覆盖 schema、policy、connection、fingerprint、compile/run diagnostics；run history 已保存 shape-only trace 并提供 `/api/visual/runs/{runId}/trace` | 继续补节点耗时、事件流和画布高亮重放 |

当前 `resource-gateway-examples` 已经把 `configSchema` 的第一层落成代码：用户导入 operator library 时会校验 config schema 本身，canvas inspector 会根据简单 config 字段渲染编辑控件，并允许把 config 字段从 literal 切换为 source-backed expression。服务端 `GraphDraftValidator` 会在 validate/compile/run 前阻断缺必填 config、类型不匹配、enum 不匹配和禁止额外字段场景。结构化 config expression（例如 `{ kind: "expression", expr: "ctx.threshold" }`）不会被当成普通 object 误杀；如果它是纯 `ctx.*` 或 `node.output.*` 引用，服务端会把引用 schema 与目标 `configSchema` 做兼容性校验，DSL preview/codegen 也会把该结构降回普通 BLOGE DSL 表达式。native operator 的业务 `configSchema` 会 lower 成 BLOGE input block 的 `config` 对象，所以浏览器会在业务 config 存在时隐藏会 lower 到根 `config` 的普通 input target，服务端也会在 draft 校验和连接预检中阻断同一节点把普通 input schema 路径 lower 到根 `config` 的冲突。它还区分正式校验与连接预检：正式 draft 阻断 data edge / semantic dependency 不一致，连接预检则允许尚未写入 binding 的 preview edge 先通过 schema 与 DAG gate。config 中的表达式引用已经参与引用存在性校验、DAG cycle gate 和拓扑排序。复杂表达式类型推断、复杂嵌套 config 的专业化控件仍属于后续增强，但服务端契约已经先行兜底。

当前 `resource-gateway-examples` 也已经把第一层 operator policy gate 落成代码：
用户库可以声明 `policy.tenants`、`policy.namespaces`、`policy.environments`；
浏览器使用当前 draft scope 拉取 catalog；服务端在 draft validate/compile/run/publish
前再次阻断不匹配的 operator。它也已经把 streaming/durable runtime capability
纳入 `OperatorDefinition` fingerprint，并在当前 request-response visual runtime 中阻断
`streaming=true` 或 `durable=true` 的 operator；对 `NON_IDEMPOTENT` 外部副作用算子会给出
production promotion 前需要 review/audit control 的 warning。用户算子库导入阶段也会对
streaming/durable、secret-backed execution 和 non-idempotent external effect 发出
warning-gated diagnostics，避免高风险 operator 未经审阅进入 catalog。完整 RBAC、secret 权限和真正的
durable runtime authoring/instance 管理仍属于后续治理层增强。

### 19.3 新增

- `VisualOperatorCatalogController`
- `GraphDraftController`
- `GraphDraftValidator`
- `GraphDraftDslGenerator`
- `ResourceVirtualOperatorProjector`
- `SchemaCompatibilityChecker`
- `VisualRunController`

## 20. 路线图

Phase 1 的工程拆分、包结构、API、测试和 Definition of Done 见
[BLOGE 可视化编排 Phase 1 实现蓝图](./bloge-visual-orchestration-phase1-implementation-blueprint.md)。

### Phase 0：合同固化

目标：先把语义边界钉死。

交付：

- `bloge.operatorCatalog.v1` 草案。
- `bloge.graphDraft.v1` 草案。
- `bloge.visualLayout.v1` 在 gateway 和 graph-engine 中对齐，且 group/id/membership 只作为表现层合同存在。
- ResourceDescriptor 增加设计时 schema 的方案。
- 诊断模型统一：severity、message、nodeId、edgeId、fieldPath、sourceRange、suggestion。

验收：

- 不改前端也能通过 API 看到虚拟算子 catalog。
- 一个 ResourceDescriptor 可以投影成 VirtualOperatorDefinition。

### Phase 1：Resource Gateway 通用画布 MVP

目标：用 resource-gateway 跑通通用 authoring 闭环。

交付：

- Palette 从 catalog 加载。
- 节点表单从 schema 动态生成。
- 支持 resource、decision table、transform 三类节点。
- 支持字段 path 连接和表达式 binding。
- 服务端生成 DSL。
- 服务端编译运行。
- 节点状态和输出回显到画布。

验收：

- 用户可以不改 JS 代码，新增一个 ResourceDescriptor 后，它出现在画布 palette 中。
- 用户可以把该资源输出连接到 decision table 或 transform。
- 错误连接会被阻止或给出明确诊断。

### Phase 2：发布治理与版本化

目标：从 demo composer 变成可管理资产。

交付：

- GraphDraft 持久化。
- GraphVersion 发布。
- visual layout 随版本保存。
- schema compatibility diff。
- operator/descriptor impact analysis。
- golden test cases 基础版、output assertion modes、numeric tolerance assertion、output schema assertion、publication suite run、latest certification 和 stale-aware promotion gate 已落地。

验收：

- 已发布版本不可变。
- 修改 descriptor 时能列出受影响图。
- 发布前能跑测试输入并阻断不兼容 schema。

### Phase 3：复杂编排覆盖

目标：覆盖更大的业务流程空间。

交付：

- branch / foreach / fallback / streaming 可视化。
- subgraph as operator。
- durable instance 状态叠加。
- human task / wait / signal 只读或半编辑支持。
- 运行 trace replay。

验收：

- 现有 gateway 七个示例都可以从画布表达。
- 用户可以理解一次运行为什么走了某条分支、命中某条规则、触发某个 fallback。

### Phase 4：智能辅助

目标：让 AI 辅助编排，但不替代确定性校验。

交付：

- 根据自然语言生成 GraphDraft。
- 根据 diagnostics 自动修复建议。
- 根据 schema 推荐字段映射。
- 根据运行错误推荐 fallback / retry / timeout 调整。

底线：

- AI 只能生成候选草稿。
- 发布仍由确定性 compiler、schema validator、policy gate 裁决。

## 21. 风险与应对

| 风险 | 严重度 | 说明 | 应对 |
| --- | --- | --- | --- |
| 画布和 DSL 双重真相 | 高 | 最终图不可解释 | GraphDraft 生成 DSL，visualLayout 只做表现 |
| schema 太弱 | 高 | 拖线自由但运行失败 | 强制 input/output/config schema；示例项目已服务端阻断非法 config |
| 表达式绕过类型系统 | 高 | 用户用字符串表达式破坏约束 | 表达式引用和结果类型推断 |
| ResourceDescriptor 无 payload schema | 高 | 虚拟算子输出不可连接 | descriptor 增加 payload schema 或采样推断后人工确认 |
| 前端写死领域 | 中 | 无法通用 | Palette 和 editor 全部 catalog-driven |
| 校验太慢 | 中 | 拖拽体验差 | 客户端快速校验 + 服务端增量校验 |
| 权限后补 | 高 | 外部调用和密钥风险 | 第一层 operator scope policy 与 request-response runtime capability gate 已服务端阻断；后续补 RBAC、secret 权限和 review gate |
| 过早做全功能低代码 | 高 | 平台失控 | 先做 gateway MVP，限制节点类型 |

## 22. 待确认问题

这些问题会影响下一版方案的收敛：

1. 目标用户到底是开发者、解决方案架构师、业务运营，还是三者都要覆盖？不同用户决定 UI 的 DSL 暴露程度。
2. 第一版是否要求保存草稿和发布版本，还是只需要浏览器内临时 composer？
3. 用户提供的“算子库定义”优先是哪种形态：JSON/YAML catalog、Java Operator、HTTP/OpenAPI、还是 BLOGE subgraph？
4. 是否要把 OpenAPI 导入作为 ResourceDescriptor 生成器？如果是，schema 来源会更完整。
5. Phase 1 是否必须支持 DSL 反向导入？如果要求无损导入，工作量会明显上升。
6. 是否要求接入 graph-engine 的版本/部署/实例模型，还是先在 resource-gateway 内做轻量版？
7. 是否允许用户在画布中编辑任意 BLOGE 表达式？如果允许，必须同步做表达式类型推断。

## 23. 建议的下一步

当前主设计和协议草案已经给出一条可实现路线。下一步不应该继续泛泛讨论“画布长什么样”，而应该收敛三个实现决策：

1. **确认 Phase 1 的 canonical authoring model**：采用 `GraphDraft`，不再让前端拼 DSL 字符串。
2. **确认算子库输入形态优先级**：先支持 `ResourceDescriptor + ResourceDesignContract -> VirtualOperator`，再支持用户导入 `operatorCatalog.json/yaml`。
3. **确认 schema 强度**：没有 `payloadSchema` 的 resource 默认不能参与类型安全连接，除非用户显式插入 opaque transform 或策略允许 warning 发布。

如果这三个方向成立，下一步可以按
[BLOGE 可视化编排 Phase 1 实现蓝图](./bloge-visual-orchestration-phase1-implementation-blueprint.md)
进入代码切片：Java record/DTO、controller、validator、projector、DSL generator 和前端 catalog-driven editor。

如果这三个方向被推翻，应先回看
[BLOGE 可视化编排系统关键决策记录](./bloge-visual-orchestration-decision-record.md)
中对应 ADR 的失效条件，再修改协议草案。
