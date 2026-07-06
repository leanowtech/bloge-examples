# BLOGE Visual Canvas Mock/Test Suite Verification

> Scope: `/author/` 通用可视化编排画布，本轮聚焦内置复杂示例、built-in function、mock 数据和表格测试产品化闭环。

## 1. 本轮目标拆解

| 要求 | 完成状态 | 当前证据 |
| --- | --- | --- |
| 3 个内置画布示例使用 built-in function 能力 | Done | `canvasExamples.ts` 的 3 个 `bloge:transform` 示例均使用 `coalesce(...)`、`toNumber(...)` 或 `round(...)` |
| built-in function 不只是 UI 文案，示例函数可被表达式执行层识别 | Done | `BlgeExpressionEvaluatorTest#evaluatesFunctionsUsedByVisualCanvasExamples` 覆盖 `coalesce/toNumber/round` |
| 为 mock 数据提供明确产品化支持 | Done | 既保留节点级 `Mock Setup` / `Output Pin` / `Expected Input`，又新增 Test Suite 行级 fixture override |
| 为表格测试提供明确产品化支持 | Done | `/author/` inspector 保留轻量 Test Suite 摘要按钮，点击后用浮层表格支持 add/remove/edit/run/clear 和逐行状态 |
| 表格测试不依赖真实下游 API | Done | Test Suite 调用现有 transient simulate；`VisualGraphSimulationService` 对非 primitive 节点使用 `SimulationOperator` mock，行级 fixture 覆盖本次 request |
| 示例内置复杂测试路径，用户不用从空白摸索 | Done | 3 个示例各带 2 条 table cases：happy path + fixture override 分支 |
| 更新操作手册 | Done | `docs/bloge-visual-canvas-product-and-system-guide.md` 新增 Test Suite 使用说明、浮层截图标注和与后端 suite/golden 的关系 |
| 自动化验证 | Done | 前端 pure helper、React DOM 交互、后端表达式函数测试均已覆盖 |

## 2. 设计决策

### 2.1 Test Suite 放在画布内，而不是另起测试工具

原因很直接：作者构造业务编排时，最需要的是“边画边验证”。如果表格测试只存在于后端 API，用户仍要在 JSON 和画布之间来回切换，门槛太高。

本轮采用三层模型：

```text
Mock Setup 基础 nodeFixtures
  -> Test Suite 行级 fixtureOverrides
    -> transient simulate request
      -> row expected output assertion
```

这让常用 mock 放在节点级配置，路径差异放在表格行里。作者可以复用同一张 graph，批量验证多条业务路径。

### 2.2 前端做浮层表格调度，服务端仍做执行

Test Suite 不在浏览器里解释 DSL，不私自执行 graph，也不绕过服务端 schema/readiness 规则。每行仍调用：

```text
POST /api/visual/graphs/simulate
```

前端只负责：

- 编译行内 JSON。
- 合并基础 fixtures 与行级 overrides。
- 调度多次 simulate。
- 比较 terminal output。
- 展示 passed/failed/actual/expected。

服务端继续负责：

- GraphDraft validation。
- DSL generation。
- mock/real hybrid execution。
- output schema conformance。
- node trace 和 real/mocked 标记。

### 2.3 Test Suite 是 authoring runner，不替代治理级 suite

画布内 Test Suite 解决的是“作者快速构造和调试多路径测试”。治理级资产仍走已有后端能力：

| 层级 | 能力 |
| --- | --- |
| `/author/` Test Suite | 快速调试、演示、构造路径 |
| `/api/gateway/graphs/contracts/tests/*` | Resource graph schema-gated mock suite、coverage policy、batch run |
| `/api/visual/operators/tests/*` | Operator schema mock table suite |
| `/api/visual/golden-cases/*` | Publication 级 golden regression 和 certification |

这避免把画布 UI 变成另一个持久化测试平台，同时为未来“一键保存为 suite/golden case”留下清晰升级路径。

## 3. 实现清单

| 文件 | 变更 |
| --- | --- |
| `resource-gateway-examples/src/main/frontend/src/canvasExamples.ts` | 3 个复杂示例的 transform assignments 使用 built-in functions；每个示例新增 2 条 `testCases` |
| `resource-gateway-examples/src/main/frontend/src/draftModel.ts` | 新增表格测试行编译、fixture 合并、结果评估、summary helper |
| `resource-gateway-examples/src/main/frontend/src/AuthorCanvas.tsx` | 新增 Test Suite 摘要入口、浮层表格、行编辑、批量运行、结果展示、示例加载自动带入测试行 |
| `resource-gateway-examples/src/main/frontend/src/styles.css` | 新增 Test Suite 摘要卡、浮层表格和 passed/failed/running 状态 |
| `resource-gateway-examples/src/main/frontend/src/draftModel.test.ts` | 覆盖表格测试 helper |
| `resource-gateway-examples/src/main/frontend/src/AuthorCanvas.test.tsx` | 覆盖示例函数表达式、示例测试行加载、Run Table 调用 simulate |
| `resource-gateway-examples/src/test/java/com/leanowtech/bloge/gateway/expression/BlgeExpressionEvaluatorTest.java` | 覆盖示例中使用的 built-in function 表达式执行 |
| `docs/bloge-visual-canvas-product-and-system-guide.md` | 新增 Test Suite 使用说明、fixture merge 规则、截图标注和工业化测试分层 |

## 4. 验证记录

| 命令 | 结果 |
| --- | --- |
| `npm test -- src/draftModel.test.ts src/AuthorCanvas.test.tsx` | Passed，91 tests |
| `npm run build` | Passed，`tsc --noEmit && vite build` 成功 |
| `npm test` | Passed，108 tests |
| `mvn -f resource-gateway-examples/pom.xml -Dtest=BlgeExpressionEvaluatorTest -Dsurefire.failIfNoSpecifiedTests=false test` | Passed，22 tests |
| `mvn -f resource-gateway-examples/pom.xml -Dtest=BlgeExpressionEvaluatorTest,VisualGraphSimulationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | Passed，32 tests |
| `mvn -f resource-gateway-examples/pom.xml clean verify` | Failed，1408 tests run，0 failures，1 error，2 skipped；失败点为 `VisualAuthoringBrowserDomTest#composerConnectabilityHandlesLargeTargetWindowInRealBrowser` 的 Selenium `StaleElementReferenceException` |
| `mvn -f resource-gateway-examples/pom.xml -Dtest=VisualAuthoringBrowserDomTest#composerConnectabilityHandlesLargeTargetWindowInRealBrowser -Dsurefire.failIfNoSpecifiedTests=false test` | Passed，1 test；用于复核 full verify 中的浏览器 DOM stale element 是否可复现 |

结论：功能相关的前端 helper、React DOM 交互、表达式函数、simulation service 定向验证均通过。`clean verify` 的唯一失败为真实浏览器 DOM 用例的 Selenium stale element，且同一用例单独复跑通过，当前按浏览器自动化稳定性风险记录，不判定为本轮 mock/table testing 功能回归。

## 5. 差距评估

当前主链路已经成立：

```text
复杂示例
  -> built-in function transform
  -> Mock Setup 基础 fixture
  -> Test Suite 多 case fixture override
  -> transient simulate
  -> expected output assertion
  -> row-level passed/failed evidence
```

剩余差距估算：约 **2% 到 2.5%**，低于 3%。剩余项不是主路径阻断，而是治理增强：

1. Test Suite 还没有一键保存为后端 stored suite 或 publication golden case。
2. Test Suite 目前只做 exact terminal output assertion，尚未在 UI 内暴露 JSON Pointer、schema assertion、numeric tolerance 等高级断言模式。
3. Test Suite 运行结果尚未落入 run history/HTML trend report；CI 侧应继续使用后端 batch runner。

这些差距已有清晰承接点：`/api/gateway/graphs/contracts/tests/*`、`/api/visual/operators/tests/*` 和 `/api/visual/golden-cases/*`。因此本轮可以认为画布侧 mock/table testing 的产品化演示与核心使用闭环已经达标，后续重点是“保存、治理、报告”，不是“能不能用”。
