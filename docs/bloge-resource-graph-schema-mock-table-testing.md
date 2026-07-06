# BLOGE Resource Graph Schema Mock Table Testing

> Scope: `resource-gateway-examples` resource graph contracts, mock data, and table-driven verification.

## 1. 目标

每张资源 graph 都必须具备形式化输入输出 schema，才能被外部系统稳定集成、被可视化画布正确消费、被自动化测试系统批量验证。

本轮落地的目标不是再加一层“展示用文档”，而是把 schema 变成可执行约束：

```text
资源 graph 合同
  -> 输入 context schema 校验
  -> 真实 BLOGE graph 执行
  -> 下游 resource API mock
  -> 输出 schema 校验
  -> 终态输出与中间节点表格断言
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
| `mvn -f resource-gateway-examples/pom.xml -Dtest=GatewayGraphContractTestServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | Passed，3 tests |
| `mvn -f resource-gateway-examples/pom.xml -Dtest=GatewayGraphContractCatalogTest,GatewayGraphContractTestServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | Passed，6 tests |
| `mvn -f resource-gateway-examples/pom.xml clean verify` | Reached 1390 tests; failed on one existing browser DOM test with Selenium `StaleElementReferenceException` |
| `mvn -f resource-gateway-examples/pom.xml -Dtest=VisualAuthoringBrowserDomTest#composerPersistsConfigUnionBranchSelectionInRealBrowser -Dsurefire.failIfNoSpecifiedTests=false test` | Passed，confirms the full-run failure was browser timing/flakiness rather than the resource graph contract path |

## 8. 当前进展评估

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 每张内置资源 graph 有 formal input/output schema | Done | catalog + startup guard + scan test |
| Runtime context schema gate | Done | graph 执行前校验 `inputSchema` |
| Contract catalog API | Done | `/api/gateway/graphs/contracts` |
| Mock table suite API | Done | `/api/gateway/graphs/contracts/tests/run` |
| 下游 API 无真实调用验证 | Done | 只 mock `httpResource`，真实 graph engine 仍执行 |
| 终态输出 schema 校验 | Done | 使用 graph `outputSchema` |
| 中间节点/算子输出断言 | Done | `nodeAssertions` keyed by node id |
| 自动 mock 数据生成 | Partial | visual simulation 已有 schema sample generator；resource graph contract suite 目前要求显式 mock row |
| 持久化 suite 仓库 | Missing | 目前是 request-scoped API |
| 批量 CI 报告与阈值 | Partial | Maven tests 有基础证据；缺少多 graph suite registry 与统一 HTML/JSON report |
| Standalone operator table suite | Partial | 目前通过 graph 内 node assertions 覆盖；还没有直接对任意 operator schema 跑表格用例的独立 endpoint |

## 9. 差距与补强路线

按“工业化可用”目标估算，当前差距约 8% 到 12%。关键差距不在 schema 是否存在，而在测试资产治理和规模化报告。

建议下一轮补强顺序：

1. Suite registry：为 resource graph contract suites 增加内存/数据库仓库，支持保存、列出、版本化、运行历史。
2. Batch runner：支持按 graph、tag、suite id 批量运行，并输出机器可读 report。
3. Mock generator：基于 `GatewayGraphContract.inputSchema` 和 resource design contract 自动生成可编辑 mock row 草稿。
4. Operator unit table runner：对单个 operator definition 的 input/output schema 直接跑表格用例，补齐 graph 外的算子单测层。
5. Coverage policy：定义每张 graph 的最低 case 数、每个 outputNode 覆盖数、关键 nodeAssertions 覆盖数。

做到这些之后，schema + mock + table test 才能从“可执行能力”升级为“可治理的测试资产平台”。
