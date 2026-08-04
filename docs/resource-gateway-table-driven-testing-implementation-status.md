# Resource Gateway 表格驱动测试实施状态

> 对应方案：[表格驱动测试产品基准、补强设计与实施计划](resource-gateway-table-driven-testing-product-design.md)
>
> 更新日期：2026-08-04
>
> 当前阶段：Stage 1 complete，Stage 2 planned
>
> 当前实现匹配度：`64 / 100`，相对目标差距约 `36%`

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

## 4. 当前差距复评

### 4.1 评分

| 能力域 | 权重 | 当前得分 | 已有证据 | 主要缺口 |
|---|---:|---:|---|---|
| canonical model 与兼容性 | 15 | 14 | Scenario 单一真相、三向读取和 legacy adapter 已由 round trip 证明 | 导入 provenance v2 未落地 |
| Matrix + Case 产品体验 | 20 | 18 | 稳定列、双视图、筛选排序、sticky、渐进窗口、键盘打开 | 跨行 fill/paste 与 saved view 未落地 |
| 数据导入与物化 | 15 | 1 | Workspace JSON import 存在 | CSV/JSON preview、mapping、value semantics、receipt 缺失 |
| 精确批量运行 | 15 | 10 | all/selected/previous-failed exact closure 与 canonical order 已落地 | changed/affected、并发预算、取消与服务端 batch 缺失 |
| Evidence 与 verdict | 15 | 11 | 四轴行级 projection、attempt、duration、first failure 已落地 | baseline compare、可导出 batch receipt 缺失 |
| Coverage-guided generation | 10 | 3 | case type、coverage policy、PROPERTY/mutation 基础存在 | Coverage Lens 与候选生成缺失 |
| 企业规模与协作 | 10 | 7 | 500-case progressive projection、隔离、RBAC、retention、durable runner 基础强 | bulk conflict、saved views、server-side query 缺失 |
| **合计** | **100** | **64** |  | **差距约 36%** |

### 4.2 根因判断

Stage 1 已根治“一次只能编辑和理解一个 case”的首要问题。当前最大差距转移到数据进入系统
之前的产品边界：

1. CSV/JSON 仍不能安全预览、映射和表达 null/missing/default；
2. 外部数据还没有 materialization receipt，运行无法证明 source/mapping/Contract closure；
3. 大批执行仍是前端顺序调度，尚无服务端 batch budget、cancel、retry 与 durable progress；
4. coverage policy 尚未告诉作者“下一条最值得补什么 case”；
5. 500-case 能浏览但没有 optimistic bulk edit、saved view 与 server-side query。

因此下一轮必须做 Stage 2 的受限导入和物化。若直接接入 Excel/JDBC 或让运行期读取可变文件，
会绕开已建立的 canonical Scenario、exact selection 和 fingerprint evidence。

## 5. 下一轮实施计划

Stage 2 按以下顺序纵切：

1. 先定义 bounded CSV/JSON parser、预算和结构化错误；
2. 建立 preview model，只暴露受限 sample、推断类型、敏感路径和 warning；
3. 建立 schema mapping workbench，支持同名 Map All、field picker、confidence 和 converter；
4. 将 `VALUE / NULL / MISSING / EMPTY / DEFAULT` 做成显式值语义，而非字符串约定；
5. 生成绑定 source、mapping、Contract、target fingerprint 的 materialization plan；
6. 幂等物化 canonical Scenario，并返回不含业务 payload 的 receipt；
7. 对更新数据源提供 added/changed/removed diff，未经确认不覆盖现有 Scenario。

Stage 2 退出后重新评分；如果导入只把文件内容塞进前端状态、没有 bounds、显式 mapping、值语义
和 fingerprint receipt，则不得记为完成。
