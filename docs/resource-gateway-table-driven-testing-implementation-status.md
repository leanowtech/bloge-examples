# Resource Gateway 表格驱动测试实施状态

> 对应方案：[表格驱动测试产品基准、补强设计与实施计划](resource-gateway-table-driven-testing-product-design.md)
>
> 更新日期：2026-08-04
>
> 当前阶段：Stage 0 complete，Stage 1 planned
>
> 当前实现匹配度：`39 / 100`，相对目标差距约 `61%`

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

## 3. 当前差距复评

### 3.1 评分

| 能力域 | 权重 | 当前得分 | 已有证据 | 主要缺口 |
|---|---:|---:|---|---|
| canonical model 与兼容性 | 15 | 13 | Scenario/Fixture/Suite 基础强，Stage 0 adapter 补强 | 导入 provenance v2 未落地 |
| Matrix + Case 产品体验 | 20 | 5 | Case View 已存在 | Matrix、selection、列投影、批量编辑缺失 |
| 数据导入与物化 | 15 | 1 | Workspace JSON import 存在 | CSV/JSON preview、mapping、value semantics、receipt 缺失 |
| 精确批量运行 | 15 | 3 | 单 case、部分 Run all 基础存在 | selected/failed/changed/affected 与 exact closure 缺失 |
| Evidence 与 verdict | 15 | 8 | Evidence/fingerprint/gate 基础强，四轴词汇已冻结 | 行级 projection、attempt、baseline compare 缺失 |
| Coverage-guided generation | 10 | 3 | case type、coverage policy、PROPERTY/mutation 基础存在 | Coverage Lens 与候选生成缺失 |
| 企业规模与协作 | 10 | 6 | 隔离、RBAC、retention、durable runner 基础强 | 500+ Matrix、bulk conflict、saved views 缺失 |
| **合计** | **100** | **39** |  | **差距约 61%** |

### 3.2 根因判断

当前最大的差距不在后端执行能力，而在 authoring projection 和批量坐标：

1. 已有 canonical Scenario，但用户一次只能深编辑一个 case；
2. 已有 run/evidence，但前端没有 exact multi-case selection；
3. 已有 coverage policy，但创作时看不到下一条 case 的覆盖贡献；
4. 已有企业控制面，但大部分能力没有投影到表格工作流。

因此下一轮必须先做 Stage 1。若跳到 CSV importer 或 pairwise generator，会让新数据继续落入
缺少批量扫描和精确选择的旧界面，放大而不是消除体验债务。

## 4. 下一轮实施计划

Stage 1 按以下顺序纵切：

1. 从 canonical Scenario + Contract + row evidence 生成纯 `ScenarioTableProjection`；
2. 实现 Matrix / Case 模式切换和稳定列分组；
3. 实现 selection model，确保 filter/sort 不改变 selected case ids；
4. 接入 Run selected / Run failed，提交 exact ordered closure；
5. 将四轴 verdict 投影到每行；
6. 复用 Operator / Function legacy adapter；
7. 使用 5/50/500 corpus 做 unit、component 和真实浏览器门禁。

Stage 1 退出后重新评分；如果 Matrix 只“看起来像表格”但没有 canonical round trip、exact
selection 和失败定位证据，则不得记为完成。
