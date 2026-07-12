# BLOGE 可视化算子库 Schema 定义

状态：Draft v1
适用合同：`bloge.visualOperatorLibrary.v1` / `bloge.visualOperator.v1`
机器 schema：[bloge-visual-operator-library.schema.json](./schemas/bloge-visual-operator-library.schema.json)

## 1. 定位

算子库是通用 BLOGE 可视化编排画布的外部能力入口。平台或业务团队把自己的业务动作、远程 worker、AI tool、事件入口、webhook 或 schema-only 设计节点描述成 `OperatorLibrary`，也可以把通用数据转换能力描述成 `builtInFunctions`。画布再把它们投影成可搜索、可拖拽、可连线、可校验、可导出、可治理的 `OperatorDefinition`，以及 transform/branch 表达式可补全、可提示签名的函数目录。

当前实现的 Java source of truth 是：

| 模型 | 位置 | 责任 |
| --- | --- | --- |
| `OperatorLibrary` | `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/visual/catalog/OperatorLibrary.java` | 用户导入的算子库根对象 |
| `OperatorDefinition` | `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/visual/catalog/OperatorDefinition.java` | 单个可编排算子定义 |
| `BuiltInFunctionCatalog` | `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/visual/catalog/BuiltInFunctionCatalog.java` | BLOGE 表达式内置函数目录 |
| `SchemaEnvelope` | `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/visual/model/SchemaEnvelope.java` | 端口和配置 schema 包装 |
| `OperatorLibraryValidator` | `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/visual/catalog/OperatorLibraryValidator.java` | 导入前权威语义校验 |
| `VisualSchemaValidator` | `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/visual/validation/VisualSchemaValidator.java` | JSON Schema 2020-12 子集校验 |

本文件定义用户手写 JSON/YAML 算子库的合同。内置 Java operator、resource descriptor 投影和 publication operator 也会进入 catalog，但它们是服务端派生 surface，不应该被用户导入合同伪造。

通用画布不关心这个合同是怎么生成的。业务团队可以手写 `bloge.visualOperatorLibrary.v1`，也可以从平台 catalog、OpenAPI/AsyncAPI/resource descriptor 或其他工具投影得到它；只要最终结构通过同一套 validator，画布就可以用它渲染 DSL、约束连线和提供表达式函数提示。对于已经接入 BLOGE 的存量业务，`bloge.capabilityCatalog.v1` 是推荐的上游 source contract，但它不是画布消费合同；resource-gateway 提供 `POST /admin/visual-operator-libraries/from-capability-catalog-text` 作为 preview adapter，把 framework export 转成标准 visual library 草稿，再继续走本页定义的 Validate / Import 流程。

当前 capability adapter 的默认策略是保守的：从 `bloge.capabilityCatalog.v1` 投影出的 operator 使用 `source.kind=user-library` 与 `lowering.mode=design`，并把原始 `implementation.kind`、`className`、`inputType`、`outputType` 等 provenance 放入 `lowering.parameters.capabilityCatalog`。这表示它已经可以被画布审阅、编排和生成 DSL，但不会假装 resource-gateway 示例环境已经拥有业务系统里的 Java runtime。需要 EXECUTABLE 发布时，再由业务侧 runtime 或平台 binding 补齐 native/resource/subgraph lowering。

## 2. 合同分层

用户算子库分成四层：

| 层级 | 字段 | 用途 |
| --- | --- | --- |
| Library | `schemaVersion`、`libraryId`、`version`、`status`、`builtInFunctions`、`operators` | 标识一个可审计、可版本化、可替换的算子与函数包 |
| Expression Function | `name`、`namespace`、`signatures`、`parameters`、`returns`、`examples` | 定义 transform/branch/config 表达式里可调用的数据转换函数 |
| Operator | `operatorRef`、`display`、`source`、`ports`、`configSchema`、`capabilities`、`policy`、`lowering` | 定义一个能被画布使用的业务节点 |
| Schema | `ports.inputs[].schema`、`ports.outputs[].schema`、`configSchema` | 约束连线、配置、模拟输入输出和 drift 审阅 |
| Governance | `capabilities`、`policy`、`lowering`、server-derived readiness | 判断是否可执行、是否需要 ack/force/evidence/runtime binding |

机器 schema 负责结构预检，服务端 validator 负责最终语义裁决。不要只依赖 JSON Schema 判断能否导入。

## 3. Library 根对象

```yaml
schemaVersion: bloge.visualOperatorLibrary.v1
libraryId: risk-policy
displayName: Risk Policy
version: 1.0.0
owner: risk-platform
status: ACTIVE
builtInFunctions:
  - name: coalesce
    namespace: risk
    description: Return the first non-null value.
    signatures:
      - label: coalesce(value, fallback)
        parameters:
          - name: value
            type: any
          - name: fallback
            type: any
        returns:
          type: any
operators: []
```

| 字段 | 必填 | 规则 |
| --- | --- | --- |
| `schemaVersion` | 是 | 固定为 `bloge.visualOperatorLibrary.v1` |
| `libraryId` | 是 | namespace-safe token：`risk-policy`、`risk:policy`、`risk.policy` 均可 |
| `displayName` | 否 | 展示名；省略时服务端可回退到 `libraryId` |
| `version` | 建议 | SemVer：`MAJOR.MINOR.PATCH`，可带 prerelease/build；省略时服务端默认 `1.0.0` |
| `owner` | 否 | 发布团队、系统或负责人 |
| `status` | 否 | `ACTIVE`、`DEPRECATED`、`DISABLED`；省略默认 `ACTIVE` |
| `builtInFunctions` | 否 | 该 library 贡献给表达式编辑器的函数定义；服务端会和系统默认函数合并 |
| `operators` | 是 | 至少 1 个 `OperatorDefinition` |

`libraryId` 和 `operatorRef` 使用同一类 namespace-safe token：

```text
^[A-Za-z_][A-Za-z0-9_]*(?:(?::|\.|-)[A-Za-z_][A-Za-z0-9_]*)*$
```

### 3.1 builtInFunctions 表达式函数

`builtInFunctions` 用来把 BLOGE 表达式里的通用函数正式文档化。它不是普通节点，不会出现在左侧算子 palette；它会通过 `GET /api/visual/operators` 的 `builtInFunctions` 字段下发给画布，并用于 `bloge:transform` 等表达式编辑器的函数名补全和签名提示。

```yaml
builtInFunctions:
  - name: jsonPath
    namespace: risk
    displayName: JSON Path
    description: Reads a value from an object by JSONPath-like path.
    category: object
    signatures:
      - label: jsonPath(object, path, fallback?)
        description: Returns fallback when the path is absent.
        parameters:
          - name: object
            type: object
          - name: path
            type: string
          - name: fallback
            type: any
            optional: true
        returns:
          type: any
    examples:
      - jsonPath(inputs.profile, "$.address.city", "unknown")
```

| 字段 | 必填 | 规则 |
| --- | --- | --- |
| `name` | 是 | 函数调用名，可为 `coalesce` 或 `string.trim`，使用 Java/DSL identifier 与点号组合 |
| `namespace` | 否 | 函数来源命名空间；用于去重、展示和治理，不参与表达式调用文本 |
| `displayName` | 否 | UI 展示名；省略时回退到 `name` |
| `description` | 否 | 签名提示中的短说明 |
| `category` | 否 | UI 分组提示，例如 `null-handling`、`string`、`conversion`、`object` |
| `signatures` | 是 | 至少 1 个 overload；每个 signature 需要 `label` |
| `examples` | 否 | 可直接复制的表达式片段 |

`signatures[].parameters[]` 支持：

| 字段 | 说明 |
| --- | --- |
| `name` | 参数名；必须是单个 identifier，不能重复 |
| `type` | 轻量类型提示；省略默认 `any`。支持 `any`、`string`、`number`、`integer`、`boolean`、`object`、`array`、`json`、`date`、`datetime`、`unknown` |
| `schema` | 可选 JSON Schema envelope；用于比 `type` 更细的工具提示或未来静态校验 |
| `optional` | 参数可省略，例如 `fallback?` |
| `variadic` | 可变参数；必须是最后一个参数 |
| `description` | 参数说明 |

`signatures[].returns` 使用同样的 `type` 和可选 `schema`，省略时默认 `any`。服务端 validator 会拒绝重复函数名、非法函数名、缺失 signature、非法参数名、unsupported type、非末尾 variadic 参数，以及无法通过 `VisualSchemaValidator` 的参数/返回 schema。

系统默认提供的函数目录由 `BuiltInFunctionCatalog.defaults()` 管理，当前包括 `coalesce`、`defaultIfBlank`、`toNumber`、`toString`、`jsonPath`、`contains`、`round`、`formatDate`。用户 library 可以追加业务函数；同一 `namespace:name` 出现多次时，catalog 保留先出现的定义，避免编辑器出现重复候选。

## 4. OperatorDefinition

```yaml
operatorRef: risk:eligibility
operatorVersion: 1.0.0
display:
  name: Eligibility
  description: Decides whether an applicant is eligible.
  tags: [risk, policy]
source:
  kind: user-library
ports:
  inputs: []
  outputs: []
configSchema:
  format: json-schema
  version: "2020-12"
  schema:
    type: object
    additionalProperties: false
capabilities:
  effect: PURE
  idempotency: DETERMINISTIC
  streaming: false
  durable: false
  requiresSecrets: false
policy:
  tenants: []
  namespaces: []
  environments: []
lowering:
  mode: design
```

| 字段 | 必填 | 规则 |
| --- | --- | --- |
| `schemaVersion` | 否 | 固定为 `bloge.visualOperator.v1`；省略时服务端默认该值 |
| `operatorRef` | 是 | 画布、draft、publication、catalog usage 的稳定引用；同一 library 内不可重复 |
| `operatorVersion` | 建议 | SemVer；省略默认 `1.0.0` |
| `fingerprint` | 否 | 服务端派生；用户提交会被重新计算，不要依赖输入值 |
| `display` | 否 | 展示信息，当前支持 `name`、`description`、`tags` |
| `source` | 否 | 能力来源；用户导入默认 `user-library` |
| `ports` | 是 | 输入/输出端口定义；除 `branch` 外至少 1 个输出端口 |
| `configSchema` | 否 | 节点配置 schema；省略为开放 object |
| `capabilities` | 否 | 副作用、幂等性、streaming/durable/secret 等治理信号 |
| `policy` | 否 | tenant/namespace/environment 可见性策略 |
| `policies` | 否 | `policy` 的历史别名；新文档统一使用 `policy` |
| `lowering` | 否 | 该算子如何降级为 BLOGE DSL 或外部 runtime binding |
| `diagnostics` | 否 | 服务端管理；导入时必须省略或为空 |
| `runtimeReadiness` | 否 | 服务端派生；用户输入不会成为可信 readiness |

保留的 `operatorRef` 不能由用户算子库声明：

```text
httpResource
bloge:decisionTable
bloge:transform
visualPublication
resource:*
publication:*
```

这些引用属于内置执行器、resource descriptor 投影或 publication runtime surface。

## 5. Display

```yaml
display:
  name: Fraud Score
  description: Calculate fraud risk score.
  tags: [risk, fraud, score]
```

当前 wire model 只支持 `name`、`description`、`tags`。不要在用户导入合同里写 `category`、`icon`、`color`、`owner.contact` 等早期草案字段；如果要做 UI 分组，先用 `tags`、`source.kind`、`source.libraryId` 和 catalog facets。

## 6. Source

```yaml
source:
  kind: remote-worker
```

用户导入允许的 `source.kind`：

| kind | 说明 |
| --- | --- |
| `user-library` | 普通用户算子，可配合 `native`、`transform`、`branch`、`design` lowering |
| `remote-worker` | 外部 worker 运行边界 |
| `ai-tool` | 外部 AI tool 运行边界 |
| `event-source` | 事件源入口 |
| `message-handler` | 消息通道 handler |
| `webhook` | Webhook 入口 |

系统管理的 source kind 不能在用户导入合同里声明：

```text
resource-descriptor
visual-publication
java-operator
java-streaming-operator
java-suspendable-operator
```

`source.libraryId` 是服务端 catalog owner 投影，不参与 operator fingerprint，用户合同不需要填写。

## 7. Ports

端口是画布连线的核心合同。每个端口都有名称、schema、是否 required 和描述。

```yaml
ports:
  inputs:
    - name: facts
      required: true
      description: Applicant facts.
      schema:
        format: json-schema
        version: "2020-12"
        schema:
          type: object
          properties:
            applicantId:
              type: string
            score:
              type: integer
          required: [applicantId, score]
          additionalProperties: false
  outputs:
    - name: output
      description: Eligibility decision.
      schema:
        format: json-schema
        version: "2020-12"
        schema:
          type: object
          properties:
            eligible:
              type: boolean
            reason:
              type: string
          required: [eligible]
          additionalProperties: false
```

端口规则：

| 规则 | 说明 |
| --- | --- |
| 名称 | 单个 Java/DSL identifier：`^[A-Za-z_][A-Za-z0-9_]*$` |
| 输入端口 | `required: true` 表示 draft 发布前必须由绑定或配置满足 |
| 输出端口 | 通常命名为 `output`；多输出算子可使用多个输出端口 |
| 重名 | 同一方向内端口名不能重复，由服务端校验 |
| branch | `lowering.mode: branch` 必须至少 1 个输入，且不能声明输出端口 |
| transform | `lowering.mode: transform` 必须恰好 1 个输出端口，且名称为 `output` |

## 8. SchemaEnvelope

当前用户算子库只接受 JSON Schema envelope：

```yaml
schema:
  format: json-schema
  version: "2020-12"
  schema:
    type: object
    properties:
      id:
        type: string
    required: [id]
```

| 字段 | 规则 |
| --- | --- |
| `format` | 固定 `json-schema` |
| `version` | 固定 `2020-12` |
| `schema` | JSON object；不接受 boolean schema |

服务端支持的常用 schema 能力：

| 类别 | 支持项 |
| --- | --- |
| 基础类型 | `object`、`array`、`string`、`integer`、`number`、`decimal`、`boolean`、`duration`、`datetime`、`enum`、`any`、`opaque`、`null` |
| object | `properties`、`required`、`additionalProperties`、`unevaluatedProperties`、`patternProperties`、`propertyNames`、`dependentRequired`、`dependentSchemas`、`minProperties`、`maxProperties` |
| array | `items`、`prefixItems`、`contains`、`minContains`、`maxContains`、`unevaluatedItems`、`minItems`、`maxItems`、`uniqueItems` |
| scalar | `minimum`、`maximum`、`exclusiveMinimum`、`exclusiveMaximum`、`multipleOf`、`minLength`、`maxLength`、`pattern`、`format` |
| finite domain | `enum`、`const`、BLOGE legacy `kind: enum` + `values` |
| composition | `oneOf`、`anyOf`、safe `allOf` flattening、`if/then/else`、`not` |
| string format | `date`、`date-time`、`duration`、`email`、`uri`、`uuid` |

本地 JSON Pointer `$ref` 会在 `SchemaEnvelope` 构造时安全展开，常见路径包括：

```text
#/$defs/*
#/definitions/*
#/components/schemas/*
```

远程 `$ref`、`$dynamicRef`、未解析本地引用、循环引用或无法安全展开的引用会变成 blocking diagnostic。不要把无法解析的 schema 降级成 `opaque` 来绕过连线 gate。

## 9. Capabilities

```yaml
capabilities:
  effect: WRITE_EXTERNAL
  idempotency: IDEMPOTENT
  streaming: false
  durable: false
  requiresSecrets: true
  sideEffectProtocol:
    schemaVersion: bloge.sideEffectProtocol.v1
    mode: JOURNALED
    commitReceiptRequired: true
    reconciliationRequired: true
    reconcilerRef: orders.status
    idempotencyKeySource: input.idempotencyKey
    reconciliationLookupSource: input.reconciliationLookupRef
    commitReceiptSource: response.headers.X-Commit-Receipt
```

| 字段 | 允许值 | 影响 |
| --- | --- | --- |
| `effect` | `PURE`、`EXTERNAL`、`READ_EXTERNAL`、`WRITE_EXTERNAL` | 非纯算子会触发治理审阅语义 |
| `idempotency` | `DETERMINISTIC`、`IDEMPOTENT`、`NON_IDEMPOTENT`、`UNKNOWN` | 非幂等外部副作用通常需要 ack/audit/retry 策略 |
| `streaming` | boolean | 当前 request-response runtime 会给出 warning/readiness 阻断 |
| `durable` | boolean | 当前 request-response runtime 会给出 warning/readiness 阻断 |
| `requiresSecrets` | boolean | 触发 secretRef 和访问控制审阅 warning |
| `sideEffectProtocol` | object | `WRITE_EXTERNAL` 的执行准入合同；缺失时只能进入 DESIGN 编排，不能 run 或发布 EXECUTABLE |

这些字段不是 UI 装饰，它们会进入 import readiness、action readiness、runtime binding handoff 和 publication gate。

### 9.1 外部写协议

`WRITE_EXTERNAL` 算子必须使用 `bloge.sideEffectProtocol.v1`。`JOURNALED` 只有在以下字段全部成立时才被视为
managed write：

| 字段 | 约束 | 含义 |
| --- | --- | --- |
| `mode` | `JOURNALED` | operator 必须在外部写之前登记 `PREPARED` |
| `commitReceiptRequired` | `true` | 成功退出必须附 provider commit receipt |
| `reconciliationRequired` | `true` | `UNKNOWN_COMMIT` 必须能走只读 status lookup |
| `reconcilerRef` | 非空 | Resource Gateway 中注册的 provider-owned reconciler |
| `idempotencyKeySource` | 非空 | 原始幂等键来源；只允许 hash 进入 evidence |
| `reconciliationLookupSource` | 非空 | 请求前生成、无 credential 的不透明查询引用来源 |
| `commitReceiptSource` | 非空 | provider 响应中生成 receipt 的来源 |

兼容迁移不会把旧 `WRITE_EXTERNAL` schema 伪装成安全算子：省略该字段时服务端派生 `mode=UNDECLARED`，
catalog/画布显示 `Write protocol required`，readiness 为 `RUNTIME_BLOCKED`，可保存、导出和发布 DESIGN，但 compile、
run、EXECUTABLE publication 均被阻断。声明 `JOURNALED` 却缺任一字段是 blocking schema diagnostic，不能通过
operator-library import。

Runtime implementation 还必须提交 `SIDE_EFFECT_JOURNAL_V1`、`COMMIT_RECEIPT_V1`、
`RECONCILIATION_LOOKUP_V1` 三项 capability，以及 `side-effect-conformance`、`unknown-commit-fault` 测试证据；
adapter activation 还需当前 `reconciler-health` evidence。schema 声明、binding 证据与真实执行 journal 三层任何一层
不一致都 fail closed。

## 10. Policy

```yaml
policy:
  tenants: [acme]
  namespaces: [risk]
  environments: [dev, staging]
```

空数组或 `*` 表示不限制。不要把 `*` 和具体值混在同一个 scope 中：

```yaml
# bad
policy:
  environments: ["*", "prod"]
```

`policy` 影响 catalog 可见性、draft restore、stored draft dependency review 和发布时 scope-mismatch diagnostics。

## 11. Lowering

`lowering` 定义算子如何进入 BLOGE 执行语义或外部 runtime binding。

| mode | source.kind | 可本地执行 | 关键约束 |
| --- | --- | --- | --- |
| `native` | `user-library` | 取决于 executable operator 是否已注册 | `lowering.operatorRef` 必填，且不能指向系统保留执行器 |
| `transform` | `user-library` | 是，生成 transform lowering | 恰好一个 `output` 输出端口；`parameters.assignments` 非空 |
| `branch` | `user-library` | 是，生成 branch routing | 至少一个输入端口；不能有输出端口；`parameters.expression` 必填 |
| `design` | `user-library` | 否 | `lowering.operatorRef` 必须为空；作为 schema contract 可拖拽/连线/模拟 |
| `remote-worker` | `remote-worker` | 当前本地 runtime 不直接执行 | `parameters.workerTopic` 必填 |
| `ai-tool` | `ai-tool` | 当前本地 runtime 不直接执行 | `parameters.toolRef` 必填 |
| `event-source` | `event-source` | 当前本地 runtime 不直接执行 | `parameters.eventType` 必填 |
| `message-handler` | `message-handler` | 当前本地 runtime 不直接执行 | `parameters.channel` 必填 |
| `webhook` | `webhook` | 当前本地 runtime 不直接执行 | `parameters.method`、`parameters.path` 必填 |

用户不能声明这些 server-managed lowering 参数：

```text
runtimeBindingApplyKind
runtimeBindingId
adapterActivationId
executableLoweringIntegrationId
integrationRevision
```

这些字段只能由 runtime-binding apply 流程在 evidence review 后写入可信 catalog surface。

### 11.1 native

```yaml
lowering:
  mode: native
  operatorRef: riskEligibilityOperator
```

`native` 用于把用户可见的 `operatorRef` 降级到一个已注册或待绑定的 BLOGE executable operator。导入阶段会拒绝保留执行器，例如 `httpResource`、`bloge:transform`、`resource:*`。如果目标 executable 当前未解析，导入 readiness 会把它作为 runtime binding / executable resolution 风险返回。

### 11.2 transform

```yaml
lowering:
  mode: transform
  parameters:
    assignments:
      score: "{{facts.score}}"
      band: "manual-review"
```

规则：

| 项 | 约束 |
| --- | --- |
| 输出 | 必须只有一个输出端口，名称为 `output` |
| target | assignment key 必须是输出 schema 中存在的 dotted path |
| expression | 可以是 `{{input.path}}` / `{{port.path}}` 模板，也可以是静态 literal |
| 函数调用 | 可使用 catalog 下发的 `builtInFunctions`，例如 `coalesce(inputs.primaryScore, 0)`；画布会提供函数名补全和签名提示 |
| required | 输出 schema 的 required path 必须被 assignment 覆盖 |
| 类型 | 可证明不兼容的 assignment 会被拒绝 |

### 11.3 branch

```yaml
lowering:
  mode: branch
  parameters:
    expression: "{{facts.segment}} == 'vip'"
```

`branch` 用于控制路由，不产生数据输出。expression 必须引用至少一个已声明输入路径，且引用目标必须是 scalar，不能是 object/array。

### 11.4 design

```yaml
lowering:
  mode: design
```

`design` 是 schema-only operator。它能进入 palette、画布、schema-aware connection、draft save/export/import 和模拟，但不能作为当前 request-response runtime 的真实执行节点。适合早期业务流程建模、第三方协议包未绑定 runtime 前的合同审阅、或者把复杂业务步骤先结构化下来。

### 11.5 外部 runtime boundary

```yaml
source:
  kind: remote-worker
lowering:
  mode: remote-worker
  parameters:
    workerTopic: workers.risk.score
```

`remote-worker`、`ai-tool`、`event-source`、`message-handler`、`webhook` 都是外部运行边界。它们可以被导入、审阅、进入画布和生成 runtime binding handoff，但当前本地 request-response runtime 不会直接执行它们。

## 12. 服务端派生字段

这些字段不属于用户可信输入：

| 字段 | 行为 |
| --- | --- |
| `fingerprint` | 服务端按 `operatorRef`、`operatorVersion`、`source`、`ports`、`configSchema`、`capabilities`、`policy`、`lowering` 重新计算 |
| `runtimeReadiness` | 服务端根据 source/lowering/capability/diagnostics 派生 |
| `diagnostics` | 导入时必须为空；导入、validate、catalog projection 阶段由服务端产生 |
| `source.libraryId` | catalog owner 投影，不参与 fingerprint |
| server-managed `lowering.parameters.*` | runtime binding apply 后写入，不接受用户导入 |

`fingerprint` 是 draft drift、publication immutability、library replacement impact 的关键依据。用户合同里最好完全省略它。

## 13. 最小 design-only 示例

```yaml
schemaVersion: bloge.visualOperatorLibrary.v1
libraryId: risk-policy
displayName: Risk Policy
version: 1.0.0
operators:
  - operatorRef: risk:eligibility
    display:
      name: Eligibility
      description: Decides whether an applicant is eligible.
      tags: [risk, policy]
    lowering:
      mode: design
    ports:
      inputs:
        - name: facts
          required: true
          description: Applicant facts.
          schema:
            format: json-schema
            version: "2020-12"
            schema:
              type: object
              properties:
                applicantId:
                  type: string
                score:
                  type: integer
                amount:
                  type: number
              required: [applicantId, score, amount]
              additionalProperties: false
      outputs:
        - name: output
          description: Eligibility decision.
          schema:
            format: json-schema
            version: "2020-12"
            schema:
              type: object
              properties:
                eligible:
                  type: boolean
                reason:
                  type: string
              required: [eligible]
              additionalProperties: false
```

## 14. remote-worker 示例

```yaml
schemaVersion: bloge.visualOperatorLibrary.v1
libraryId: risk-workers
displayName: Risk Workers
version: 1.0.0
operators:
  - operatorRef: risk:fraudScore
    operatorVersion: 1.0.0
    display:
      name: Fraud Score
      tags: [risk, fraud, worker]
    source:
      kind: remote-worker
    capabilities:
      effect: READ_EXTERNAL
      idempotency: IDEMPOTENT
      requiresSecrets: true
    lowering:
      mode: remote-worker
      parameters:
        workerTopic: workers.risk.fraud-score
    ports:
      inputs:
        - name: facts
          required: true
          schema:
            format: json-schema
            version: "2020-12"
            schema:
              type: object
              properties:
                applicantId:
                  type: string
                deviceId:
                  type: string
              required: [applicantId]
      outputs:
        - name: output
          schema:
            format: json-schema
            version: "2020-12"
            schema:
              type: object
              properties:
                score:
                  type: integer
                  minimum: 0
                  maximum: 1000
                modelVersion:
                  type: string
              required: [score]
```

这个 library 可以导入并进入画布，但 readiness 会说明当前执行委托给外部 worker，本地 request-response runtime 不直接执行。

## 15. transform 示例

```yaml
schemaVersion: bloge.visualOperatorLibrary.v1
libraryId: risk-transforms
displayName: Risk Transforms
version: 1.0.0
operators:
  - operatorRef: risk:scoreProjection
    display:
      name: Score Projection
      tags: [risk, transform]
    lowering:
      mode: transform
      parameters:
        assignments:
          score: "{{facts.score}}"
          band: "manual-review"
    ports:
      inputs:
        - name: facts
          required: true
          schema:
            format: json-schema
            version: "2020-12"
            schema:
              type: object
              properties:
                score:
                  type: integer
              required: [score]
      outputs:
        - name: output
          schema:
            format: json-schema
            version: "2020-12"
            schema:
              type: object
              properties:
                score:
                  type: integer
                band:
                  type: string
              required: [score, band]
              additionalProperties: false
```

## 16. 校验与导入流程

推荐入口：

| 场景 | API |
| --- | --- |
| 粘贴 JSON/YAML 文本预检 | `POST /admin/visual-operator-libraries/validate-text` |
| 粘贴 JSON/YAML 文本导入 | `POST /admin/visual-operator-libraries/import-text` |
| JSON body 预检 | `POST /admin/visual-operator-libraries/validate` |
| portable bundle 预检 | `POST /admin/visual-operator-libraries/validate-bundle` |
| portable bundle 导入 | `POST /admin/visual-operator-libraries/import-bundle` |
| 导出当前 library | `GET /admin/visual-operator-libraries/{libraryId}/export` |

validate/import 返回的不只是 `valid=true/false`，还包括：

| 返回信息 | 用途 |
| --- | --- |
| `diagnostics` | blocking error、warning、target JSON pointer |
| `profile` | operator 数量、端口、能力、readiness 分布 |
| `impact` | replacement 对 stored draft/publication/operator usage 的影响 |
| `importReadiness` | 是否需要 ack warnings、force、governance evidence、runtime binding handoff |
| `targetDiff` / `schemaChanges[]` | bundle 或 replacement 场景下的字段级 schema drift 审阅 |

## 17. 常见失败

| 现象 | 常见原因 | 修复方向 |
| --- | --- | --- |
| `visual.library.schemaVersion.unsupported` | 根对象版本不是 `bloge.visualOperatorLibrary.v1` | 修改 `schemaVersion` |
| `visual.library.id.invalid` | `libraryId` 含 `/`、空格等非法字符 | 使用 `risk-policy`、`risk:policy` 或 `risk.policy` |
| `visual.operator.ref.reserved` | 用户声明了 `httpResource`、`resource:*` 等系统引用 | 改成业务命名空间，如 `risk:eligibility` |
| `visual.operator.output.required` | 非 branch 算子没有输出端口 | 增加 `ports.outputs` 或改为 `lowering.mode: branch` |
| `visual.schema.formatUnsupported` | schema envelope 不是 `json-schema` | 使用 `format: json-schema` |
| `visual.schema.refRemoteUnsupported` | schema 中有远程 `$ref` | 在 library 内展开或改成本地 `$defs` |
| `visual.operator.lowering.assignments.required` | transform 没有 assignments | 增加 `lowering.parameters.assignments` |
| `visual.operator.lowering.assignmentTarget.unknown` | assignment target 不在 output schema 中 | 修正 target path 或补 output schema |
| `visual.operator.lowering.branchExpression.referenceRequired` | branch expression 未引用输入 | 使用 `{{facts.segment}}` 这类模板 |
| `visual.operator.source.loweringModeMismatch` | `source.kind` 和外部 boundary lowering 不一致 | 让二者同名，例如都为 `remote-worker` |
| `visual.operator.policy.scopeWildcardMixed` | `*` 和具体 scope 混用 | 只保留 `*` 或只保留具体值 |
| `visual.operator.diagnostics.managed` | 用户导入了非空 diagnostics | 删除 `diagnostics` |
| `visual.function.name.invalid` | `builtInFunctions[].name` 不是合法函数调用名 | 使用 `coalesce`、`string.trim` 这类 identifier/点号形式 |
| `visual.function.signature.required` | 函数没有任何 signature | 至少声明 1 个 `signatures[]` |
| `visual.function.type.unsupported` | 参数或返回类型不在支持集合中 | 使用 `any/string/number/integer/boolean/object/array/json/date/datetime/unknown` |
| `visual.function.parameter.variadicPosition` | 可变参数不是最后一个参数 | 把 `variadic: true` 的参数移动到列表末尾 |

## 18. 文档兼容说明

早期设计文档中出现过 `bloge.operatorCatalog.v1`、顶层 `inputSchema/outputSchema`、`display.category/icon/color` 等草案字段。当前实现已收敛为：

```text
bloge.visualOperatorLibrary.v1
  -> builtInFunctions[]
  -> operators[]
    -> bloge.visualOperator.v1
      -> ports.inputs[] / ports.outputs[]
      -> configSchema
      -> capabilities / policy / lowering
```

新接入和新示例应以本文和机器 schema 为准。旧草案仍可作为架构思路阅读，但不要作为 wire contract 使用。
