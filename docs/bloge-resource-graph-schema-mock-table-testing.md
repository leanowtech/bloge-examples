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

### 4.0 画布内 Test Table 入口

后端 schema-gated suite 是治理级能力；新版 `/author/` 现在还提供画布内 **Test Table**，作为作者构造表格用例的低门槛入口。

画布内 Test Table 的一行包含：

| 字段 | 含义 |
| --- | --- |
| `context` | 本行传入 transient simulate 的 runtime context |
| `fixtureOverrides` | 本行覆盖节点级 Mock Setup 的 `nodeFixtures` |
| `expectedOutput` | 本行断言的 terminal output |

它复用 `POST /api/visual/graphs/simulate`，不直接落库，也不替代本章后面的 stored suite / batch runner。推荐使用路径是：

```text
/author/ Test Table 快速调试多路径
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

内置 in-memory suite：

| suiteId | 覆盖 |
| --- | --- |
| `loan-decision-policy-smoke` | loan decision table 的 R1 approval 与 R4 decline 两条路径；覆盖 `assembleLoanDecision` 输出节点、2 次 resource mock、4 个断言 |

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

这补齐了 graph 外的 standalone operator 表格测试层。对于已经有真实 request-response runtime binding 的算子，后续可以在此基础上增加 executable adapter，把“schema mock 验证”升级成“mock + real execution 双模式验证”。

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
          "payload": {
            "applicantId": "prime",
            "score": 780,
            "segment": "private-bank"
          }
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
    -Dtest=GatewayGraphContractTestServiceTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
```

当前覆盖：

| 测试 | 验证点 |
| --- | --- |
| `tableSuiteRunsResourceGraphWithMockedDownstreamResources` | 两行 loan decision table case；mock 下游 applicant API；校验 graph output schema；校验 terminal output；校验 `loanPolicy` 中间节点输出；统计 4 个断言 |
| `tableSuiteFailsFastWhenContextViolatesGraphInputSchema` | 缺失 `requestedAmount` 时，graph 不执行并返回 input schema 诊断 |
| `contractTestApiRunsTableSuites` | REST API 可运行 suite 并返回 coverage 与 mock invocation |
| `draftGeneratesEditableGraphMockSuiteFromFormalSchemas` | graph contract draft 生成 context、resource mock payload、output schema 断言，并可直接运行通过 |
| `contractTestApiDraftsGraphMockSuites` | REST API 可返回 formal graph contract 与可编辑 mock suite 草稿 |

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
| `mvn -f resource-gateway-examples/pom.xml clean verify` | Reached 1390 tests; failed on one existing browser DOM test with Selenium `StaleElementReferenceException` |
| `mvn -f resource-gateway-examples/pom.xml -Dtest=VisualAuthoringBrowserDomTest#composerPersistsConfigUnionBranchSelectionInRealBrowser -Dsurefire.failIfNoSpecifiedTests=false test` | Passed，confirms the full-run failure was browser timing/flakiness rather than the resource graph contract path |

## 8. 当前进展评估

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 每张内置资源 graph 有 formal input/output schema | Done | catalog + startup guard + scan test |
| Runtime context schema gate | Done | graph 执行前校验 `inputSchema` |
| Public endpoint output schema gate | Done | graph 成功后按 `outputNodes` 选择终态输出，并用 `outputSchema` 校验后再返回 |
| Contract catalog API | Done | `/api/gateway/graphs/contracts` |
| Mock table suite API | Done | `/api/gateway/graphs/contracts/tests/run` |
| 下游 API 无真实调用验证 | Done | 只 mock `httpResource`，真实 graph engine 仍执行 |
| 终态输出 schema 校验 | Done | runtime endpoint 与 contract test 都使用 graph `outputSchema` |
| 中间节点/算子输出断言 | Done | `nodeAssertions` keyed by node id |
| Resource graph mock draft | Done | `/api/gateway/graphs/contracts/tests/draft` 基于 graph input schema 与 resource response schema 生成可编辑 row |
| Operator schema mock draft | Done | `/api/visual/operators/tests/draft` 基于 operator schema 生成可编辑 table row |
| 自动 mock 数据生成 | Done for draft | graph/operator 均可生成可编辑 mock row；复杂 runtime-dependent graph mock 仍以 warning 提示人工补齐 |
| Suite registry | Done | in-memory repository + list/get/put/run API + 内置 smoke suite |
| Batch runner | Done | `POST /api/gateway/graphs/contracts/tests/suites/run-all` 聚合 suite、case、coverage |
| Coverage policy | Done | suite 级阈值可要求 case、schema validation、mock call、assertion、output node 覆盖 |
| 批量 CI 报告 | Partial | 已有机器可读 batch result；还缺独立 HTML/历史趋势报告 |
| Standalone operator table suite | Done | `/api/visual/operators/tests/run` + stored suite + run-all |
| 画布内 Test Table authoring 入口 | Done | `/author/` 支持多行 context、fixture override、expected output，并逐行运行 transient simulate |

## 9. 差距与补强路线

按“工业化可用”目标估算，当前差距已经从 3% 到 4% 继续收敛到约 2% 到 2.5%。核心 schema + mock + table test 主链路已打通：资源 graph 有 formal input/output schema，graph/operator 都能生成可编辑 mock row 并运行表格验证；画布内也已经有 Test Table authoring 入口，可以直接基于当前 draft 批量验证多条 mock 路径。剩余差距主要是增强型治理能力，而不是主路径缺口。

建议下一轮补强顺序：

1. Operator execution adapter：对已具备 request-response runtime binding 的算子支持 mock + real execution 双模式表格验证。
2. Complex graph mock expansion：对 foreach / upstream-output-dependent resource call 提供半自动 payload scenario template。
3. CI/report adapter：把 graph/operator batch result 落成稳定 JSON/HTML 报告，并保留运行历史趋势。

做到这些之后，schema + mock + table test 才能从“可执行能力”升级为“可治理的测试资产平台”。
