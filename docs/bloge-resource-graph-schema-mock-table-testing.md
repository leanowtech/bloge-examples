# BLOGE Resource Graph And Operator Schema Mock Table Testing

> Scope: `resource-gateway-examples` resource graph contracts, operator schemas, mock data, and table-driven verification.

## 1. 目标

每张资源 graph 和其中使用的每个可视化算子都必须具备形式化输入输出 schema，才能被外部系统稳定集成、被可视化画布正确消费、被自动化测试系统批量验证。

本轮落地的目标不是再加一层“展示用文档”，而是把 schema 变成可执行约束：

```text
资源 graph 合同
  -> 输入 context schema 校验
  -> resource mock row 草稿生成
  -> 真实 BLOGE graph 执行
  -> 下游 resource API mock
  -> 输出 schema 校验
  -> 终态输出与中间节点表格断言
  -> operator input/config/mock output schema table test
  -> 结构化测试报告
```

## 2. 资源 Graph 合同

资源 graph 合同由 `GatewayGraphContract` 表达，当前版本为：

```text
bloge.gatewayGraphContract.v1
```

核心字段：

| 字段 | 含义 |
| --- | --- |
| `graphName` | `.bloge` 文件中声明的 graph 名称 |
| `inputSchema` | graph 执行前的 `GraphContext` JSON Schema |
| `outputSchema` | 对外集成可依赖的终态输出 JSON Schema |
| `outputNodes` | 可作为公共输出的终端节点 id 列表 |

内置合同集中在：

```text
resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/gateway/GatewayGraphContractCatalog.java
```

合同 API：

```text
GET /api/gateway/graphs/contracts
GET /api/gateway/graphs/contracts/{graphName}
```

## 3. 覆盖面守卫

形式化 schema 不是靠约定维护，而是有两层守卫。

运行时守卫：

- `GatewayGraphService` 初始化时检查所有已加载 graph。
- 如果某张 graph 没有 `GatewayGraphContract`，应用启动失败。
- 每次执行 graph 前，`GatewayGraphService` 使用 `inputSchema` 校验 `GraphContext`。
- 公开 gateway endpoint 成功返回前，会按合同 `outputNodes` 选择终态输出并用 `outputSchema` 校验。
- Java record 等运行时对象会按 JSON 可见形态进行 schema 校验，避免 Java 内部类型和 HTTP 集成契约混淆。

测试守卫：

- `GatewayGraphContractCatalogTest` 扫描 `src/main/resources/bloge/gateway/*.bloge`。
- 测试要求 catalog 中的 graph 名称与所有 `.bloge` 声明完全一致。
- 每个 `inputSchema` 和 `outputSchema` 都必须通过 `VisualSchemaValidator.validateEnvelope`。
- 每个合同必须声明至少一个 `outputNode`。

这意味着新增资源 graph 的最低准入条件是：

```text
新增 .bloge graph
  -> 新增 GatewayGraphContract
  -> 声明 inputSchema / outputSchema / outputNodes
  -> catalog test 通过
  -> gateway 可启动
```

## 4. Schema-Gated Mock Table Test

新增测试入口：

```text
POST /api/gateway/graphs/contracts/tests/draft
POST /api/gateway/graphs/contracts/tests/run
```

请求版本：

```text
bloge.gatewayGraphContractTestSuiteRequest.v1
```

响应版本：

```text
bloge.gatewayGraphContractTestSuiteResult.v1
```

服务实现：

```text
GatewayGraphContractTestController
GatewayGraphContractTestService
GatewayGraphContractTestSuiteRepository
InMemoryGatewayGraphContractTestSuiteRepository
```

测试行模型：

| 字段 | 含义 |
| --- | --- |
| `name` | 表格测试行名称 |
| `context` | graph 输入上下文，必须满足 graph `inputSchema` |
| `resourceMocks` | 下游 `httpResource` mock 响应行 |
| `outputNode` | 可选，指定本 case 要检查的终态输出节点 |
| `assertions` | 对终态输出执行的断言 |
| `nodeAssertions` | 对中间节点输出执行的断言，key 是 node id |

`resourceMocks` 只替换 descriptor-backed `httpResource` 调用。Graph topology、DSL 编译产物、decision table、branch、transform、fallback 等仍然通过真实 BLOGE engine 执行。

每个 `resourceMocks` 行同时是一条可审计 fixture 规则：

| 字段 | 含义 |
| --- | --- |
| `resourceId` | 要匹配的 descriptor-backed resource id |
| `expectedParams` | 可选的精确参数匹配条件；不匹配时 fail closed |
| `fixtureMode` | `OUTPUT_LEVEL`、`PROTOCOL_DERIVED` 或 `TRANSPORT_LEVEL`，决定替换边界与证据等级 |
| `payload` / `success` | 仅供 `OUTPUT_LEVEL` 使用的已解释算子输出 |
| `statusCode` / `rawBody` / `responseHeaders` | F2/F3 使用的原始上游 HTTP 响应；仍由生产协议解析链处理 |
| `required` | 兼容字段；为 `true` 时缺省 `minUses=1`，否则缺省 `minUses=0` |
| `minUses` | 最少消费次数，用于证明 retry/loop 的预期调用基数 |
| `maxUses` | 最大消费次数；`0` 表示不设上限，历史行缺省为 `1` |

保真语义不能靠 suite 名称或存储位置推断：

| `fixtureMode` | 实际替换点 | 最高保真度 | 证据用途 |
| --- | --- | --- | --- |
| `OUTPUT_LEVEL` | 直接提供 `HttpResourceOutput` | F1 | 兼容历史 table row，只能生成 `EXPLORATORY` 证据 |
| `PROTOCOL_DERIVED` | 提供 raw response，由真实 `ResponseProtocol` 与 payload path 解释 | F2 | 证明响应协议和业务编排 |
| `TRANSPORT_LEVEL` | 替换 HTTP transport，保留真实参数映射、URL 渲染、协议解释 | F3 | stored suite 且其余认证条件满足时可生成 `CERTIFIABLE` 证据 |

`minUses/maxUses` 是确定性控制，不是覆盖率提示。实际消费低于最小值会得到 `FIXTURE_UNUSED`，超过最大值会得到
`FIXTURE_EXHAUSTED`；两者都使 case 失败。retry/fallback 用例必须显式写出调用次数，不能以无限复用 fixture 掩盖错误的重试策略。

### 4.0 画布内 Test Suite 入口

后端 schema-gated suite 是治理级能力；新版 `/author/` 现在还提供画布内 **Test Suite**，作为作者构造表格用例的低门槛入口。右侧 inspector 只保留轻量摘要和入口按钮，完整表格在浮层里编辑，避免大表格挤占节点配置空间。

画布内 Test Suite 的一行包含：

| 字段 | 含义 |
| --- | --- |
| `context` | 本行传入 transient simulate 的 runtime context |
| `fixtureOverrides` | 本行覆盖节点级 Mock Setup 的 `nodeFixtures` |
| `expectedOutput` | 本行断言的 terminal output |

它复用 `POST /api/visual/graphs/simulate`，不直接落库，也不替代本章后面的 stored suite / batch runner。推荐使用路径是：

```text
/author/ Test Suite 快速调试多路径
  -> 后端 graph/operator schema suite 固化为治理资产
    -> publication golden case 做发布级认证
```

这样用户不用一开始就手写完整 suite JSON，也不会让浏览器承担持久化测试平台的职责。

## 4.1 Suite Registry、Batch Runner 与 Coverage Policy

一次性 `POST /run` 适合调试，但工业化测试需要把 suite 作为可治理资产保存和批量执行。本轮新增：

```text
GET  /api/gateway/graphs/contracts/tests/suites
GET  /api/gateway/graphs/contracts/tests/suites/{suiteId}
PUT  /api/gateway/graphs/contracts/tests/suites/{suiteId}
POST /api/gateway/graphs/contracts/tests/suites/{suiteId}/run
POST /api/gateway/graphs/contracts/tests/suites/run-all
```

Stored suite 版本：

```text
bloge.gatewayGraphContractTestSuite.v1
```

Stored suite 字段：

| 字段 | 含义 |
| --- | --- |
| `suiteId` | 稳定 suite id，可被 CI 或演示脚本引用 |
| `displayName` | 人类可读名称 |
| `description` | 说明 |
| `tags` | 批量选择、报告聚合的标签 |
| `request` | 原始 table suite request |
| `coveragePolicy` | 最低验证证据阈值 |

Coverage policy 字段：

| 字段 | 含义 |
| --- | --- |
| `minCases` | 至少需要多少个 table case |
| `minInputSchemaValidated` | 至少多少个 case 需要通过 input schema |
| `minContractOutputSchemaValidated` | 至少多少个 case 需要通过 output schema |
| `minMockedResourceCalls` | 至少观察到多少次下游 resource mock 调用 |
| `minAssertionCount` | 至少配置并评估多少个终态/节点断言 |
| `requiredOutputNodes` | 至少要被 passing case 覆盖的 output node |

内置 in-memory catalog 不再只有一条 smoke path，而是让仓库自身持续执行全部七张资源图：

| suiteId | graph | case | 关键路径 | 证据等级 |
| --- | --- | ---: | --- | --- |
| `ai-enriched-search-streams` | `aiEnrichedSearch` | 1 | metadata、token stream、citation 汇总 | Certifiable |
| `credit-score-provider-routing` | `creditScore` | 2 | primary 成功；primary 两次失败后 secondary fallback | Certifiable |
| `enrich-order-list-occurrence-control` | `enrichOrderList` | 2 | 空集合边界；两条订单并行 shipping/invoice enrichment | Certifiable |
| `loan-decision-policy-smoke` | `loanDecisionPolicy` | 2 | decision table R1 approval 与 R4 decline | Certifiable |
| `product-detail-all-branches` | `productDetail` | 3 | physical、digital、generic 三个 branch | Certifiable |
| `resource-dispatch-descriptor-protocols` | `resourceDispatch` | 2 | BodyCode 与 query-mapped descriptor 协议 | Certifiable |
| `user-dashboard-happy-and-degraded` | `userDashboard` | 2 | 并行聚合 happy path；bounded retry + fallback 降级 | Certifiable |

合计 7 suites、14 cases、28 个被观察到的 root/nested 资源调用和 37 个业务断言。所有 resource fixture 均为显式
`TRANSPORT_LEVEL`；Spring dogfooding 还会把 descriptor endpoint 指向不可连接地址，任何 fixture 逃逸都会使测试确定失败。同步 foreach/loop/subgraph/compensation 的 node/edge evidence 已按 structural site、runtime correlation 和 graph occurrence 展开；streaming/suspendable 仍不可认证。
详细证明见 [Stage 2 dogfooding verification](resource-gateway-execution-data-control-plane-stage2-dogfooding-verification.md)。

## 4.1 Resource Graph Mock Draft

`/api/gateway/graphs/contracts/tests/draft` 用正式 graph contract 和 resource design contract 生成一份可编辑 table suite 草稿，降低从零手写 JSON 的门槛。

请求版本：

```text
bloge.gatewayGraphContractTestDraftRequest.v1
```

响应版本：

```text
bloge.gatewayGraphContractTestDraft.v1
```

生成链路：

```text
GatewayGraphContract.inputSchema
  -> JsonSchemaSampleGenerator 生成 context sample
  -> contextOverrides 覆盖关键业务字段
  -> 编译后 httpResource node inputAssembler 求值
  -> resourceId / expectedParams 预填
  -> ResourceDesignContract.responseSchema 生成 mock payload
  -> resourcePayloadOverrides 覆盖关键业务 payload
  -> 默认 OUTPUT_MATCHES_SCHEMA 断言 graph outputSchema
```

这让用户可以先拿到能看懂、能编辑的 mock table row，再按业务路径补充 payload、断言和 coverage policy。对于 foreach 内部节点、依赖上游 runtime output 的 resource 调用，系统不会假装已经完全知道运行时值，而是返回 `gateway.graphContractTest.mockDraftInputUnresolved` warning，提示用户在草稿上补齐对应 mock row。

## 4.2 Operator Schema Mock Table Test

资源 graph 里的复杂业务往往会沉淀为可复用算子。只验证 graph 不够，算子本身也需要能用 schema、mock 数据和表格用例独立验证。

新增 operator 级测试入口：

```text
POST /api/visual/operators/tests/draft
POST /api/visual/operators/tests/run
GET  /api/visual/operators/tests/suites
GET  /api/visual/operators/tests/suites/{suiteId}
PUT  /api/visual/operators/tests/suites/{suiteId}
POST /api/visual/operators/tests/suites/{suiteId}/run
POST /api/visual/operators/tests/suites/run-all
```

请求版本：

```text
bloge.visualOperatorContractTestSuiteRequest.v1
```

响应版本：

```text
bloge.visualOperatorContractTestSuiteResult.v1
bloge.visualOperatorContractTestBatchResult.v1
```

核心模型：

| 字段 | 含义 |
| --- | --- |
| `operatorRef` | visual operator catalog 中的稳定算子引用 |
| `inputs` | mock 输入，按 input port name 分组 |
| `config` | mock 配置，必须满足算子 `configSchema` |
| `mockedOutputs` | mock 输出，按 output port name 分组 |
| `outputAssertions` | 对每个 output port 执行的表格断言 |

`/draft` 会读取 `OperatorDefinition`，用 `JsonSchemaSampleGenerator` 为 input/config/output schema 生成可编辑 mock row 草稿，并自动为生成的 output port 加上 `OUTPUT_MATCHES_SCHEMA` 断言。这样用户不需要从空 JSON 开始手写 mock。

`/run` 不依赖真实下游 API，也不要求 design-only、remote worker、AI tool、webhook 等算子已经有生产 runtime binding。它以 operator schema 为契约验证 table row：

```text
operator definition
  -> input port schema validation
  -> config schema validation
  -> mocked output schema validation
  -> output port assertions
  -> suite/batch coverage evidence
```

必须准确理解这一层的证明强度：当前 `/api/visual/operators/tests/run` **不会调用真实 operator runtime binding**，
它验证的是 input/config/mocked output/断言之间的 schema-contract 自洽性。因此当前模式应归类为
`SCHEMA_CONTRACT`，不能把 passing result 解释为“operator 实现已经执行并正确”。真实执行指定 binding、控制内部依赖、
注入故障和输出签名测试证据的演进设计见
[Resource Gateway 工业级可测试性与执行数据控制反转演进方案](resource-gateway-industrial-testability-evolution-plan.md)。

该证明强度不是文档约定：suite run API 的响应会显式返回 `mode: "SCHEMA_CONTRACT"`。任何消费方都应按
`mode` 判断证据含义，不能根据 `passed=true` 推断 operator runtime binding 已被执行。

这补齐了 graph 外的 standalone operator 表格测试层。Stage 1 已经在服务端内核增加
`OperatorMicroGraphRunner`：它把冻结的 runtime binding 编译成单节点 graph，复用统一 planner、double、assertion 和 evidence
执行真实算子；不满足 Composability Contract 的算子明确返回 `OPAQUE_RUNTIME`。`/author/` 双击节点后的
`Executable Operator Suite` 已迁移到这条公共 adapter：先调用
`GET /api/testing/targets/operators/{operatorRef}` 冻结并审查目标，再调用
`POST /api/testing/targets/operators/{operatorRef}/executions` 运行真实微图。native operator 使用 `SPY` 保留真实主体执行，
resource operator 只在 `TRANSPORT` 边界注入可编辑原始响应。画布的 `Run*` 使用 inline fixture，因此 passing 结果仍明确为
`EXPLORATORY`；`Govern*` 会把单行内容注册为内容寻址的不可变 revision、校验 registry 身份，再按 stored ref 执行。stored
provenance 只是 `CERTIFIABLE` 的必要条件，最终等级仍取决于 target composability、strict schema 和 fixture fidelity；当前单行
governed fixture 也还不是一等 immutable `TestSuite` registry。

### 4.4 Execution Data Control Plane Stage 1

既有 graph suite 已收编为统一内核的第一个 adapter。一次 case 的真实路径是：

```text
GatewayGraphContractTestService
  -> FixtureBundle + frozen target fingerprint
  -> SafetyPreflight + SelectorResolver
  -> EffectiveExecutionPlan
  -> fresh run-scoped GraphEngine
  -> executeWithOperators(test doubles)
  -> node/edge trace + fixture consumption + assertion
  -> TestRunEvidence
```

关键运行语义：

| 规则 | 当前行为 |
| --- | --- |
| 外部效应未配置 fixture | 合成 `IMPLICIT_DENY`，结果为 `FIXTURE_UNMATCHED`，绝不静默真调 |
| selector 零命中或同优先级歧义 | 运行前返回 `CONTROL_PLAN_REJECTED`，不会启动 graph |
| required fixture 未消费 | 终态为 `FIXTURE_UNUSED` |
| `REAL/RETURN/THROW/DENY/SPY` | 五种时间无关行为已激活；时间、stream、replay 字段存在但 v1 显式拒绝 |
| resource payload RETURN | `OUTPUT_LEVEL`，只可生成 `EXPLORATORY` 证据 |
| raw body + status RETURN | 走真实 `ResponseProtocol`/payloadPath，记为 `PROTOCOL_DERIVED` |
| `boundary=TRANSPORT` | 用 `StubHttpRequestOperator` 替换传输层，参数映射、URL 渲染和协议解析真实执行 |
| SPY | 真实执行；evidence 记录节点控制模式和脱敏 side-effect journal 快照 |
| bounded regex | 最长 256 字符、候选值最长 4096 字符；group/alternation/look-around/backreference 在 preflight 拒绝 |

旧 `GatewayGraphResourceMock` 为兼容已有 API 仍解释为 OUTPUT_LEVEL fixture，不能因存储为 suite 就自动升级为认证证据。
调用方必须显式提供 raw protocol response 或 transport fixture，才可能达到 F2/F3。这样避免兼容迁移偷偷改变旧 case
的 `success/payload` 语义。

## 5. 断言能力

当前断言模式：

| Mode | 用途 |
| --- | --- |
| `OUTPUT_EQUALS` | 整个输出必须与 `expectedValue` JSON 等价 |
| `OUTPUT_MATCHES_SCHEMA` | 输出必须满足 `expectedValue` 中给出的 JSON Schema 或 `SchemaEnvelope` |
| `PATH_EQUALS` | JSON Pointer 指向的值必须等于 `expectedValue` |
| `PATH_EXISTS` | JSON Pointer 必须存在 |
| `PATH_ABSENT` | JSON Pointer 必须不存在 |

终态断言示例：

```json
{
  "mode": "PATH_EQUALS",
  "path": "/policy/ruleId",
  "expectedValue": "R1"
}
```

中间节点断言示例：

```json
{
  "loanPolicy": [
    {
      "mode": "PATH_EQUALS",
      "path": "/decision",
      "expectedValue": "approved"
    }
  ]
}
```

这让 decision table、transform、resource node 等 graph 内部算子输出也能进入表格化验证，而不是只能检查最终 HTTP 响应。

## 6. 请求示例

```json
{
  "graphName": "loanDecisionPolicy",
  "cases": [
    {
      "name": "prime applicant",
      "context": {
        "applicantId": "prime",
        "requestedAmount": 450000.0
      },
      "resourceMocks": [
        {
          "resourceId": "loan-applicant-service.getProfile",
          "expectedParams": {
            "applicantId": "prime"
          },
          "statusCode": 200,
          "rawBody": "{\"code\":0,\"message\":\"OK\",\"data\":{\"applicantId\":\"prime\",\"score\":780,\"segment\":\"private-bank\"}}",
          "responseHeaders": {
            "Content-Type": "application/json"
          },
          "fixtureMode": "TRANSPORT_LEVEL",
          "required": true,
          "minUses": 1,
          "maxUses": 1
        }
      ],
      "outputNode": "assembleLoanDecision",
      "assertions": [
        {
          "mode": "PATH_EQUALS",
          "path": "/policy/ruleId",
          "expectedValue": "R1"
        }
      ],
      "nodeAssertions": {
        "loanPolicy": [
          {
            "mode": "PATH_EQUALS",
            "path": "/decision",
            "expectedValue": "approved"
          }
        ]
      }
    }
  ]
}
```

响应中重点看：

| 字段 | 含义 |
| --- | --- |
| `passed` | suite 是否整体通过 |
| `coverage.inputSchemaValidated` | context 通过 input schema 的 case 数 |
| `coverage.contractOutputSchemaValidated` | 输出通过 graph output schema 的 case 数 |
| `coverage.mockedResourceCalls` | 实际 mock resource 调用次数 |
| `coverage.assertionCount` | 已评估断言数 |
| `policyResult.passed` | coverage policy 是否满足 |
| `policyResult.diagnostics` | 覆盖阈值不足时的诊断 |
| `results[].mockedResourceInvocations` | 每次 mock resource 调用的 resourceId、params、是否匹配 mock |
| `results[].statusMap` | graph 节点执行状态 |
| `results[].diagnostics` | schema、mock、执行、断言诊断 |

## 7. 验证证据

Focused verification:

```bash
mvn -f resource-gateway-examples/pom.xml \
    -Dtest=GatewayGraphContractTestServiceTest,ResourceGatewayApplicationTest,VisualSchemaValidatorTest,ExecutionControlCompilerTest,TestRunServiceTest,ResourceFixtureRuntimeTest,OperatorMicroGraphRunnerTest,GraphArtifactFingerprintTest test
```

当前覆盖：

| 测试 | 验证点 |
| --- | --- |
| `tableSuiteRunsResourceGraphWithMockedDownstreamResources` | 两行 loan decision table case；mock 下游 applicant API；校验 graph output schema；校验 terminal output；校验 `loanPolicy` 中间节点输出；统计 4 个断言 |
| `tableSuiteFailsFastWhenContextViolatesGraphInputSchema` | 缺失 `requestedAmount` 时，graph 不执行并返回 input schema 诊断 |
| `contractTestApiRunsTableSuites` | REST API 可运行 suite 并返回 coverage 与 mock invocation |
| `draftGeneratesEditableGraphMockSuiteFromFormalSchemas` | graph contract draft 生成 context、resource mock payload、output schema 断言，并可直接运行通过 |
| `contractTestApiDraftsGraphMockSuites` | REST API 可返回 formal graph contract 与可编辑 mock suite 草稿 |
| `ExecutionControlCompilerTest` | selector 冻结、零命中/歧义/保留坐标/危险正则拒绝、外部效应隐式 DENY |
| `TestRunServiceTest` | 五行为、消费检查、断言容差、隔离引擎、拒绝证据、SPY side-effect intent |
| `ResourceFixtureRuntimeTest` | 相同 raw body 在不同协议下派生不同结果；F3 捕获真实渲染 URL；204 空 body |
| `OperatorMicroGraphRunnerTest` | 纯算子 EXECUTABLE_UNIT、opaque fail closed、httpResource TRANSPORT 认证 |
| `GraphArtifactFingerprintTest` | 指纹稳定性、DSL 源和边完成语义变化检测 |
| `builtInSuiteCatalogCoversEveryGraphAndUsesExplicitTransportFixtures` | 七图/14-case catalog 完整性、全部 F3 fixture、retry 精确消费次数、非空 foreach suite |
| `everyBuiltInGraphSuiteRunsThroughRealWiringWithoutUncontrolledResourceCalls` | 真实 Spring wiring 批量执行七图；不可达 endpoint 下 28 次 root/nested resource 调用全部由 transport fixture 接管；37 个断言通过 |
| `validatesTypedRecordOutputsThroughTheJsonObjectContractModel` | typed Java record 与 JSON map 使用同一 schema 可见形态，避免 graph gate 和 kernel assertion 结论分裂 |

Graph contract coverage:

```bash
mvn -f resource-gateway-examples/pom.xml \
    -Dtest=GatewayGraphContractCatalogTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
```

该测试确认每张内置 gateway graph 都有形式化 input/output schema。

Final verification for this module should still use:

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

本轮执行记录：

| 命令 | 结果 |
| --- | --- |
| `mvn -f resource-gateway-examples/pom.xml -Dtest=GatewayGraphContractTestServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | Passed，8 tests |
| `mvn -f resource-gateway-examples/pom.xml -Dtest=GatewayGraphContractCatalogTest,GatewayGraphContractTestServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | Passed，12 tests |
| `mvn -f resource-gateway-examples/pom.xml -Dtest=GatewayIntegrationTest,ResourceExecuteIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` | Passed，14 tests；验证公开 gateway endpoint 返回前执行 output schema gate |
| `mvn -f resource-gateway-examples/pom.xml -Dtest=GatewayGraphContractCatalogTest,GatewayGraphContractTestServiceTest,VisualOperatorContractTestServiceTest,ResourceGatewayApplicationTest -Dsurefire.failIfNoSpecifiedTests=false test` | Passed，21 tests；覆盖 graph/operator schema drafts, stored suites, Spring wiring, graph-level I/O schema guard |
| `mvn -f resource-gateway-examples/pom.xml -Dtest=VisualOperatorContractTestServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | Passed，5 tests；覆盖 operator schema mock table run、draft、stored suite、run-all、非法 schema assertion 诊断 |
| Stage 1 kernel focused command（见上） | Passed，37 tests；0 failures/errors/skips |
| `mvn -f resource-gateway-examples/pom.xml clean verify`（Stage 1） | Passed，1653 tests；0 failures，0 errors，34 conditional skips；JAR 打包成功 |
| `mvn -f resource-gateway-examples/pom.xml clean verify` | Reached 1390 tests; failed on one existing browser DOM test with Selenium `StaleElementReferenceException` |
| `mvn -f resource-gateway-examples/pom.xml -Dtest=VisualAuthoringBrowserDomTest#composerPersistsConfigUnionBranchSelectionInRealBrowser -Dsurefire.failIfNoSpecifiedTests=false test` | Passed，confirms the full-run failure was browser timing/flakiness rather than the resource graph contract path |
| `mvn -f resource-gateway-examples/pom.xml clean verify`（Stage 2 dogfooding） | Passed，1691 tests；0 failures，0 errors，33 conditional skips；真实 Chrome 回归与 JAR 打包成功 |

## 8. 当前进展评估

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 每张内置资源 graph 有 formal input/output schema | Done | catalog + startup guard + scan test |
| Runtime context schema gate | Done | graph 执行前校验 `inputSchema` |
| Public endpoint output schema gate | Done | graph 成功后按 `outputNodes` 选择终态输出，并用 `outputSchema` 校验后再返回 |
| Contract catalog API | Done | `/api/gateway/graphs/contracts` |
| Mock table suite API | Done | `/api/gateway/graphs/contracts/tests/run` |
| 下游 API 无真实调用验证 | Done for root resource nodes | 真实 graph engine 执行；Spring dogfooding 将 descriptor 指向不可达 endpoint，fixture 逃逸会确定失败 |
| 终态输出 schema 校验 | Done | runtime endpoint 与 contract test 都使用 graph `outputSchema` |
| 中间节点/算子输出断言 | Done | `nodeAssertions` keyed by node id |
| Resource graph mock draft | Done | `/api/gateway/graphs/contracts/tests/draft` 基于 graph input schema 与 resource response schema 生成可编辑 row |
| Operator schema mock draft | Done | `/api/visual/operators/tests/draft` 基于 operator schema 生成可编辑 table row |
| 自动 mock 数据生成 | Done for draft | graph/operator 均可生成可编辑 mock row；复杂 runtime-dependent graph mock 仍以 warning 提示人工补齐 |
| Suite registry | Done | in-memory repository + list/get/put/run API + 七图/14-case 内置 catalog |
| Batch runner | Done | `POST /api/gateway/graphs/contracts/tests/suites/run-all` 聚合 suite、case、coverage |
| Coverage policy | Done | suite 级阈值可要求 case、schema validation、mock call、assertion、output node 覆盖 |
| F2/F3 resource fixture | Done for root resource nodes | 显式 protocol/transport 边界、响应头与 bounded consumption；nested invocation 仍待寻址 |
| 仓库级 no-egress dogfooding | Done for root resource nodes | 七图/13 case 全绿；foreach body 如实降级为 exploratory |
| 批量 CI 报告 | Partial | 已有机器可读 batch result；还缺独立 HTML/历史趋势报告 |
| Standalone operator table suite | Done | `/api/visual/operators/tests/run` + stored suite + run-all |
| Canvas executable operator suite | Done | 双击节点后按行/批量执行公共 operator target + micro-graph API；native SPY、resource TRANSPORT、opaque fail-closed |
| 画布内 Test Suite authoring 入口 | Done | `/author/` 支持多行 context、fixture override、expected output，并通过浮层表格逐行运行 transient simulate |

## 9. 差距与补强路线

按本文件“schema + mock + table authoring”这一窄范围目标估算，当前差距约 2% 到 2.5%。核心链路已打通：资源 graph 有 formal input/output schema，graph/operator 都能生成可编辑 mock row 并运行表格验证；画布内既能基于当前 draft 批量验证 graph mock 路径，也能在 Operator Detail 中真实试跑可组合的单个 runtime binding。

这个数字**不代表 Resource Gateway 整体工业级可测试性的完成度**。真实 operator binding 执行、统一 fixture/control
protocol、故障与非确定性控制、durable resume、语义覆盖率、签名测试证据和 production hard isolation 仍是实质缺口；
它们不是“增强型治理”，而是从 mock authoring 走向业务正确性保障的下一条主链路。

建议下一轮补强顺序：

1. Operator execution adapter：对已具备 request-response runtime binding 的算子支持 mock + real execution 双模式表格验证。
2. Complex graph mock expansion：对 foreach / upstream-output-dependent resource call 提供半自动 payload scenario template。
3. CI/report adapter：把 graph/operator batch result 落成稳定 JSON/HTML 报告，并保留运行历史趋势。

做到这些之后，schema + mock + table test 才能从“可执行能力”升级为“可治理的测试资产平台”。
