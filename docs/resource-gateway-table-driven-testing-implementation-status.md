# Resource Gateway 表格驱动测试实施状态

> 对应方案：[表格驱动测试产品基准、补强设计与实施计划](resource-gateway-table-driven-testing-product-design.md)
>
> 更新日期：2026-08-04
>
> 当前阶段：Stage 3 complete，Stage 4 next
>
> 当前实现匹配度：`89 / 100`，相对目标差距约 `11%`

## 1. 完成定义

只有同时满足以下条件，阶段才记为完成：

1. 方案中的用户能力或协议不变量已进入代码；
2. 正向、负向、边界和回归测试能够证明行为；
3. 相关文档已同步；
4. frontend 全量 test/build 与 Resource Gateway `clean verify` 全绿；
5. 用户可见阶段经过真实浏览器和多视口检查；
6. 已形成独立提交；
7. 完成一次相对最终目标的差距复评。

`Stage complete` 不等于整份方案完成。只有总差距小于 `5%`，且最终逐项审计没有缺失证据，
才允许结束本次演进目标。

## 2. Stage 0：基线与协议冻结

### 2.1 已实现

| ID | 实现 | 证据 |
|---|---|---|
| TDT-001 | 固定 5、50、500 case 三档确定性语料 | `tableDrivenTestingBaseline.ts` |
| TDT-002 | 每行固定 20 Given 字段、8 controlled dependencies、12 assertions | corpus test 对每一行逐项断言 |
| TDT-003 | 覆盖五种 case intent | GOLDEN、NEGATIVE、BOUNDARY、REGRESSION、PROPERTY |
| TDT-004 | 冻结 execution/assertion/freshness/proof 四轴词汇 | `tableDrivenTestStatus.ts` |
| TDT-005 | 禁止裸 `Passed` 投影 | 7 组 verdict 组合测试 |
| TDT-006 | Operator legacy row 保留 canonical input、fixture、oracle 和 case type | adapter compatibility test |
| TDT-007 | Operator 身份由 exact target 保留，fixture selector 保持 `nodeId` 单坐标 | adapter test + compiler full regression |

三档语料不是演示数据。它们是后续 Matrix projection、性能、虚拟化、浏览器可读性和协议兼容
测试共用的不可弱化压力面。变更行数或每行维度必须同步更新本文件，并说明为何没有降低验收强度。

### 2.2 状态词汇

新增的状态模型明确分离：

- Execution：`NOT_RUN / QUEUED / RUNNING / SUCCESS / ERROR / TIMEOUT / SKIPPED /
  CANCELLED / BUDGET_STOPPED`；
- Assertions：`NONE / PASSED / FAILED / INCONCLUSIVE`；
- Freshness：`CURRENT / STALE / SUPERSEDED`；
- Proof strength：`SCHEMA / MOCK / SANDBOX / RUNTIME / CERTIFIABLE`。

`presentTableCaseVerdict()` 的优先级是 freshness -> execution -> assertions -> proof。它不会把
`runtime success + no assertions`、`schema valid`、`mock behavior matched` 和
`certifiable behavior matched` 合并成一个绿色 Passed。

### 2.3 测试证据

已通过的聚焦测试：

```text
tableDrivenTestingBaseline.test.ts  11 passed
scenarioAuthoring.test.ts            3 passed
scenarioCompiler.test.ts            16 passed
scenarioEditorModel.test.ts          6 passed
                                      ---------
                                      36 passed
```

同时通过完整工程门禁：

```text
frontend full suite       51 files / 427 tests passed
frontend production build TypeScript + Vite passed
Resource Gateway verify    5,847 tests, 0 failures, 0 errors, 10 skipped
```

`AuthorCanvas` 的异步视图测试改为基于明确超时截止时间等待 lazy surface，而不是依赖固定轮询
次数；这只消除了全量并发测试中的调度抖动，没有放宽任何产品断言。

500-case corpus 包含：

```text
500 Scenarios
10,000 Given fields
4,000 controlled dependencies
6,000 assertions
```

它在当前测试进程中可确定性生成和比较；正式性能结论仍必须由 Stage 1 的 Matrix projection 与
真实浏览器数据取得，不能用纯对象生成时间代替 UI 性能。

## 3. Stage 1：Matrix + Case 双视图

### 3.1 已实现

| ID | 实现 | 代码与验证证据 |
|---|---|---|
| TDT-101 | `ScenarioTableProjection` 从 canonical Scenario、Contract dependency 和行级 evidence 生成稳定 `CASE / GIVEN / DEPENDENCY / THEN / PROOF` 列 | `scenarioTableModel.ts`；5/50/500 deterministic projection tests |
| TDT-102 | Matrix 提供 sticky selection/name、搜索、类型/判定筛选、排序、列选择与 50 行渐进窗口 | `ScenarioMatrixSurface.tsx`；500-case component test |
| TDT-103 | Matrix name、case type、tags、Given scalar 修改只通过 canonical command 写回 `ScenarioDraftSet`；Case 与 Advanced JSON 读取同一对象 | canonical round-trip tests |
| TDT-104 | 每行独立显示 execution、assertions、freshness、proof、duration 与首个失败；不把 schema/mock/runtime 混成裸 Passed | verdict projection tests + real Chrome row assertions |
| TDT-105 | `Run all / selected / failed` 解析为 canonical order 的 exact case-id closure；filter/sort 不改变 selection；failed 只来自上一次确切运行集合 | selection model tests + 两行真实 simulate browser path |
| TDT-106 | Operator 与 built-in Function 旧测试行经 adapter 进入同一 Matrix；Operator 保留 schema proof，Function 保留 bound/unbound runtime 诚实性 | `assetScenarioTableAdapter.ts` + `AssetTestTable` tests |
| TDT-107 | 行可聚焦并用 Enter 打开 Case，控件具备明确 accessible name；1280/1024/820/390 无页面横向溢出，宽表只在自身滚动 | component accessibility assertions + packaged Chrome test |

多 case 默认进入 Matrix；单 case 默认进入 Case。批量运行通过显式 `ScenarioRunIntent.MATRIX`
保持在表格，不再被单 case 的 Evidence 导航副作用带走。Matrix 自己已经提供逐行失败定位，因此
批量运行期间会抑制全局 Diagnostics 自动抢焦点；离开 Scenarios 后恢复原策略。

### 3.2 不变量

1. Matrix 是 projection，不拥有第二份测试值；`ScenarioDraftSet` 仍是唯一 authoring truth。
2. selection 保存 exact case ids，不保存“当前可见第几行”。隐藏、排序和渐进加载不会偷换运行集合。
3. `Run failed` 只使用上一轮 exact closure 中的 failed rows，当前筛选出的红色行不构成执行授权。
4. 每个 case 的执行和断言证据独立保存；后完成的 case 不覆盖先完成的结果。
5. 批量运行按 canonical order 顺序执行真实 compile -> simulate -> compare，不把 UI selection 直接
   当成后端 evidence。
6. 500-case 页面首屏只物化 50 行；用户显式请求下一窗口，避免一次创建全部 DOM。

### 3.3 测试与视觉证据

Stage 1 聚焦测试覆盖 projection、round trip、selection、batch run、Operator/Function adapter：

```text
scenarioTableModel.test.ts                 7 passed
ScenarioMatrixSurface.test.tsx             3 passed
ContractScenarioWorkspace.test.tsx        24 passed
AssetTestTable.test.tsx                    4 passed
                                           ---------
                                           38 passed
```

`VisualAuthoringBrowserDomTest#scenarioMatrixSelectsRunsAndRemainsUsableAcrossViewportsInRealBrowser`
在打包后的 Spring Boot + Chrome 中完成：加载 `loan-policy-fallback`、选择两行、运行 exact
closure、确认两行四轴结果、确认仍处于 Scenarios、确认 Diagnostics 收起，再检查
`1280 x 800`、`1024 x 768`、`820 x 900`、`390 x 844`。手机档位要求至少 150px 可见表格、
局部横向滚动、批量按钮完全位于视口内且高度至少 34px。截图可通过以下命令按需生成到
`target/visual-qa`，不会进入源码或 evidence：

```bash
mvn -Dtest=VisualAuthoringBrowserDomTest#scenarioMatrixSelectsRunsAndRemainsUsableAcrossViewportsInRealBrowser \
  -DresourceGateway.visualQaOutputDir=target/visual-qa test
```

Stage 1 completion gate on 2026-08-04:

```text
Frontend Vitest suite            54 files / 441 tests passed
Frontend production build       passed
Packaged Chrome visual flow      1 test / 4 viewports passed
Resource Gateway clean verify    5848 tests, 0 failures, 0 errors, 11 skipped
```

The skipped tests are existing environment-gated cases. The full Maven gate included the packaged
`VisualAuthoringBrowserDomTest` suite and completed with `BUILD SUCCESS` in 9 minutes 28 seconds.

## 4. Stage 2：CSV / JSON 导入与 schema mapping

### 4.1 已实现

| ID | 实现 | 代码与验证证据 |
|---|---|---|
| TDT-201 | Papa Parse 与 Commons CSV 双端独立解析；JSON 使用标准 parser；byte/row/column/cell/depth/item 六类预算 fail closed | `scenarioImportModel.ts`、`ScenarioImportMaterializationService.java`；quoted CSV、BOM、嵌入换行与预算测试 |
| TDT-202 | Preview 显示行列规模、类型、missing/null/empty 统计、敏感字段掩码和 formula-prefix warning | `ScenarioImportWorkbench.tsx`；敏感值不进入 warning/receipt 断言 |
| TDT-203 | 五步 Source/Preview/Map/Review/Receipt 工作台；exact path/name 自动映射、normalized mapping 人工确认、field picker 与有限 converter | component test + packaged Chrome 端到端路径 |
| TDT-204 | `VALUE / EMPTY / NULL / MISSING / DEFAULT` 独立语义；`MISSING` 真正删除 template 值，空 NUMBER 不再被误转为 0 | 前后端 value-semantics 回归与 duplicate identity 测试 |
| TDT-205 | plan 绑定 source/mapping/Contract/target/selection 指纹；Java 与浏览器共享导入边界的递归 canonical JSON 黄金向量 | `ScenarioImportFingerprintTest` + cross-runtime source fingerprint golden |
| TDT-206 | 服务端重新解析 raw source、强类型校验 target、验证当前 Contract、scope/clearance、raw secret 与 finite converter；JDBC 幂等保存 canonical Scenario + receipt | service/repository/controller 正负向测试；同 plan 重试返回同一结果 |
| TDT-207 | 以加密散列业务身份比较 added/changed/removed/unchanged；receipt 仅保留 `identityFingerprint`，不落业务主键原文 | diff test + 严格 receipt schema 的 payload-free 字段测试 |

未保存的 Graph 或 stale Contract 不再允许打开导入向导。Matrix 的 **Import cases** 会保持禁用并
通过 `title` 明确提示先 **Save Graph** 或 rebase，避免用户在最后一步才遇到 target/Contract 拒绝。

### 4.2 协议与安全不变量

1. 浏览器 preview 不构成授权；服务端必须用 Commons CSV/Jackson 重新解析同一 source snapshot。
2. 同一 plan 的 source、mapping、Contract、target、selection 任一变化都会生成新 fingerprint。
3. `JsonNode` 数值节点类型不能作为跨语言相等语义；target 先解析为强类型 `ContractDraft.Target`。
4. receipt、数据库、Problem response 和 warning 不保存 raw source、cell value 或业务 identity。
5. source 仅存在于一次受限 request；runtime 永远只消费已物化 canonical Scenario。
6. 低置信度映射未经显式确认不得进入 Review；converter 与 value semantics 是有限枚举。
7. protocol availability 与 API readiness 分开广告。schema 在所有 profile 可协商，物化 API 仅在
   test/staging testing control plane 可用。

四份 wire-contract authority 已冻结在：

- `docs/schemas/bloge-scenario-materialization-plan-v1.schema.json`
- `docs/schemas/bloge-scenario-materialization-receipt-v1.schema.json`
- `docs/schemas/bloge-scenario-import-materialization-request-v1.schema.json`
- `docs/schemas/bloge-scenario-import-materialization-result-v1.schema.json`

capability probe 对应暴露 `scenarioImportMaterializationProtocol`、
`scenarioImportMaterializationApi`、四类 supported object 和条件化 endpoint。

### 4.3 测试与视觉证据

```text
Frontend focused suite          5 files / 94 tests passed
Java import/protocol suite      7 classes / 16 tests passed
Frontend production build      TypeScript + Vite passed
Packaged Chrome import flow     1 test / 2 viewports passed
Frontend full suite             56 files / 465 tests passed
Resource Gateway verify         5865 tests, 0 failures, 0 errors, 12 skipped
```

`VisualAuthoringBrowserDomTest#scenarioImportMaterializesSampleThroughTheServerAndReturnsToMatrixAcrossViewports`
在真实 Spring Boot + Chrome 中完成：加载复杂 Graph、确认未保存时导入被解释性禁用、保存 Graph、
重基线、加载 5 行动态 JSON、preview、自动 mapping、review、调用 Java materializer、查看 0 rejected
receipt、在 1280 与 390 视口检查无页面横向溢出，最后返回 Matrix 并确认新增行。

第一次全量门禁曾发现：若为导入协议直接扩大共享 `VisualBundleFingerprint` 的递归排序语义，
会改变既有 Run Evidence 与 verification key-set 的稳定摘要。实现已将递归 canonicalization 收回
`ScenarioImportFingerprint` 私有边界，并恢复共享指纹器的兼容语义；`ToolStudioIntegrationServiceTest`
与 `EvidenceVerificationKeySetTest` 的既有黄金向量随后连同全量门禁一起通过。这条回归证明新增协议
不能借“规范化”改变存量证据坐标，后续 protocol evolution 也必须遵守相同边界。

Stage 2 最终门禁于 2026-08-04 完成，完整 `mvn verify` 用时 9 分 27 秒。12 项 skipped 均为既有
环境门控用例，不包含 Scenario import 的服务、协议或浏览器路径。

## 5. Stage 3：服务端权威批量运行与行级 Evidence

### 5.1 已实现

| ID | 实现 | 代码与验证证据 |
|---|---|---|
| TDT-301 | 服务端从 exact Scenario set 与 retained complete baseline 解析 `ALL / SELECTED / FAILED / CHANGED / AFFECTED` canonical closure | `TableSuiteRunService` selection tests；空 closure fail closed |
| TDT-302 | admission 同时校验 enterprise scope、Graph/Contract/Scenario 指纹闭包、test/staging profile、`SIMULATED + SIDE_EFFECT_FREE`、单 case hard timeout、failure/case/concurrency budget | service negative tests + controller narrow purpose |
| TDT-303 | JDBC 保存 payload-free batch、row、bounded event suffix；`GET` full view 与 revision delta 支持刷新恢复，event gap 显式 `resetRequired` | repository/protocol/service tests |
| TDT-304 | retry 只追加 failed row 的物理 attempt，首个失败不被覆盖；失败后成功投影 `flaky`；cancel 与 failure budget 为明确终态 | append-only retry/cancel/budget tests |
| TDT-305 | Matrix 从服务端 batch 投影 Execution/Assertions/Proof/Duration/Attempts/Baseline；业务值只在受限瞬时 command context，durable row 只留 expected/actual fingerprint 和首错摘要 | payload absence serialization tests |
| TDT-306 | complete baseline 驱动 failed/changed/affected；浏览器只持久化 case fingerprints、失败 ID 和 Contract 坐标并在点击前显示精确计数 | differential model/component/browser tests |
| TDT-307 | 只有完整且结论确定的 full closure 能成为 baseline；partial、cancelled、budget-stopped 均不能满足 promotion 或差分基线 | frontend/server baseline eligibility tests |

公开协议由以下严格 Schema 固定：

- `docs/schemas/bloge-visual-graph-draft-v1.schema.json`
- `docs/schemas/bloge-table-suite-run-command-v1.schema.json`
- `docs/schemas/bloge-table-suite-run-batch-v1.schema.json`
- `docs/schemas/bloge-table-suite-run-delta-v1.schema.json`

test/staging control plane 暴露：

```text
POST /api/visual/table-suite-runs
GET  /api/visual/table-suite-runs/{batchId}
GET  /api/visual/table-suite-runs/{batchId}/events?afterRevision=N
POST /api/visual/table-suite-runs/{batchId}/cancel
POST /api/visual/table-suite-runs/{batchId}/retry-failed
```

所有请求使用 accepted purpose `TEST_EXECUTION`；operation-to-purpose 映射仍由服务端
`IntegrationOperation` 裁决，前端不再把 Java operation 名误当作 `X-Purpose`。

### 5.2 运行与证据不变量

1. POST 只接受同一 scope 下 exact Graph、Contract、Scenario revision；selection 在 admission 时
   冻结，后续筛选、排序或编辑不改变已接收 closure。
2. 服务端持久层不保存 Given、fixture、expected 或 actual 值。payload-bearing command 仅存在于
   256 个有界、30 分钟过期的进程内 retry context；过期后 retry 明确返回 `CONTEXT_EXPIRED`。
3. 单 case 在 virtual thread 上由 hard timeout 中断；整批另有 max failure budget，并受进程级 8 batch
   fair semaphore 限流。用户看到 `TIMEOUT / RUNTIME_ERROR / ASSERTION_FAILED / CANCELLED /
   BUDGET_STOPPED`，不会只看到一个 Failed。
4. JSON 断言递归按 JSON 语义比较。对象字段顺序无关，数组保持顺序，`Integer / Long / Decimal`
   按数值比较，numeric tolerance 对数值叶子生效。
5. delta revision 落后于 retained event window 时返回 `resetRequired=true`，客户端必须 full GET，
   不能在缺事件时假装状态连续。
6. complete baseline 必须 full-suite 且每行存在结论确定的 attempt；取消或 failure-budget 未执行的行
   不得成为差分基线。
7. full closure 全部成功才 `Promotion Eligible`；任何 partial selection 即使 100% 通过也显示
   `Partial only`。

### 5.3 真实浏览器验收发现与根治

真实 Spring Boot + 浏览器链路依次发现并关闭：

1. delta/cancel 曾发送 `TEST_SUITE_STABILITY_JOB_READ/CANCEL` 作为 purpose，服务端按设计拒绝。
   根因是 operation 与 purpose 两层概念混用；现统一发送 `TEST_EXECUTION` 并由服务端 operation
   细分授权。
2. 单例 Expected/Actual 完全相同而 batch assertion failed。根因是嵌套数字的 Jackson node
   类型差异；现使用递归 JSON 数值语义比较，并增加跨 `Integer/Long/Decimal` 回归。
3. complete baseline 后仍可点击空的 Run changed 并看到 400。现 Matrix 显示
   `Run failed (N) / Run changed (N) / Run affected (N)`，0 时解释性禁用；服务端仍保留空 closure
   拒绝作为最终防线。

最终手工浏览器路径完成：保存复杂 Loan Graph -> compatibility review/rebase -> Run all 2 cases ->
2/2 SUCCESS + Assertions PASSED + Promotion Eligible -> 修改一格 Given -> changed/affected 立即变 1 ->
Run changed 仅运行 1 case并显示 Partial only。1280 桌面与 390 窄屏均无页面级横向溢出。

详见 [Stage 3 验证记录](resource-gateway-table-driven-testing-stage3-verification.md)。

### 5.4 Completion gate

```text
Frontend full suite              58 files / 470 tests passed
Frontend production build        passed
Resource Gateway clean verify    5880 tests, 0 failures, 0 errors, 12 skipped
```

最终 `clean verify` 于 2026-08-04 完成，用时 10 分 09 秒。12 项 skipped 均为既有环境门控用例，
不包含 table-suite run 的 service、repository、controller、protocol、capability 或浏览器验收路径。

## 6. 当前差距复评

### 6.1 评分

| 能力域 | 权重 | 当前得分 | 已有证据 | 主要缺口 |
|---|---:|---:|---|---|
| canonical model 与兼容性 | 15 | 15 | Scenario 单一真相、strict import schemas、跨运行时 canonical fingerprint 与 provenance receipt 已落地 | 无阻断缺口 |
| Matrix + Case 产品体验 | 20 | 18 | 稳定列、双视图、筛选排序、sticky、渐进窗口、键盘打开 | 跨行 fill/paste 与 saved view 未落地 |
| 数据导入与物化 | 15 | 15 | 双端 bounded parser、preview、mapping、五值语义、exact plan、幂等物化、payload-free receipt 与 diff 全部落地 | Excel/JDBC connector 有意延后，不能绕过物化 |
| 精确批量运行 | 15 | 15 | 服务端 exact closure、preflight、durable batch/delta、hard timeout、budget、cancel、retry、恢复已落地 | 无阻断缺口 |
| Evidence 与 verdict | 15 | 14 | 四轴 projection、append-only attempt、flaky、baseline、payload-free first failure、promotion semantics 已落地 | Stage 5 仍需治理导出与长期趋势 |
| Coverage-guided generation | 10 | 3 | case type、coverage policy、PROPERTY/mutation 基础存在 | Coverage Lens 与候选生成缺失 |
| 企业规模与协作 | 10 | 8 | 500-case progressive projection、profile 隔离、clearance、durable receipt 与 capability negotiation | bulk conflict、saved views、server-side query 缺失 |
| **合计** | **100** | **89** |  | **差距约 11%** |

### 6.2 根因判断

Stage 3 已把执行权从浏览器循环迁到服务端，并把精确选择、恢复、取消、重试和 baseline 变成协议。
当前最大差距转移到“测试是否足够”而非“测试能否可靠运行”：

1. 作者仍需凭经验判断 Contract 边界、DAG 分支、fallback、dependency behavior 和 assertion 是否覆盖；
2. 系统还不能解释“下一条候选为何值得增加”和它贡献了哪一格 coverage；
3. 生成候选尚无 deterministic seed、预算、constraint 和 provenance 闭包；
4. 企业协作层的 saved/team view、bulk conflict、review/approval、retention 与治理导出仍未接入 Matrix。

因此下一轮进入 Stage 4 Coverage Lens 与 deterministic candidate generation。它必须消费当前
canonical Contract/DAG/Scenario，不得创建第二份测试真相，也不得用生成数量冒充覆盖质量。

## 7. 下一轮实施计划

Stage 4 按以下顺序纵切：

1. 冻结六维 coverage projection：case intent、Contract field/boundary、DAG path、dependency behavior、
   assertion target、evidence freshness/proof；
2. 先做只读 Coverage Lens，所有 denominator 和 contribution 可解释、可定位回 Matrix/Case/Canvas；
3. 实现 deterministic schema-boundary/negative candidate generator，固定版本、seed、order 与 work budget；
4. 候选必须显示 provenance、coverage delta、expected-behavior readiness 和阻断原因；
5. Accept 才生成 canonical Scenario，Reject/Ignore 不偷偷改业务 revision；
6. pairwise 只作为独立 SPI，在 constraint、稳定顺序和预算通过 ADR/测试后接入；
7. 500-case 浏览器与服务端压力回归证明 Lens 不破坏渐进渲染和 batch 可操作性。

Stage 4 退出后重新评分；若 coverage 是一个不透明百分数、候选无 expected behavior、相同 seed
顺序不稳定，或生成器绕过 canonical Scenario command，则不得记为完成。
