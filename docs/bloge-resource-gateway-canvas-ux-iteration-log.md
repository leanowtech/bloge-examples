# BLOGE Resource Gateway 画布 UX 目标与差距迭代日志

状态：Iteration Log
日期：2026-07-06
范围：`resource-gateway-examples` / `/author/` React Flow 画布

本文用于约束 Resource Gateway 新版可视化画布的 UX 改进节奏：先定义目标，再按轮次记录证据、差距和下一轮针对性计划。只有当某轮复核后目标差距小于或等于 5%，才认为本目标闭环完成。

## 1. UX 目标

目标不是让画布“能拖节点”，而是让业务编排者在 schema 约束下能稳定、直观地完成业务逻辑搭建：

1. 拖入算子后，用户能看懂这个算子是什么、收什么、吐什么、适合接到哪里。
2. 连线被 schema 约束，但不应该把用户甩给一句 `connection rejected by server`。如果存在唯一可用字段路径，画布应帮助自动选中；如果存在多个选择，画布应把选择面显式展示出来。
3. 不同算子族必须有可感知差异。`foreach` 要突出集合、item 上下文和结果列表；`decision table` 要突出条件输入、决策输出和规则语义；resource、transform、streaming 等也要有独立视觉与合同提示。
4. 画布的权威裁决仍来自服务端，前端只做引导、解释和交互承接。
5. 每轮都必须用自动化测试和浏览器检视验证，不只看代码是否编译。

## 2. 完成度量尺

当前用 100 分制做工程化复核，不把它包装成精确用户研究数据，而是作为迭代取舍的明确标尺。

| 维度 | 权重 | 目标状态 |
| --- | ---: | --- |
| Schema 连线体验 | 30 | 拖线、候选、字段路径、失败解释都能指向下一步动作 |
| 算子专有表达 | 25 | 关键算子族有独立视觉、合同提示和后续 inspector 空间 |
| 任务流可发现性 | 20 | palette、节点、连接 guide、notice 能共同解释当前可做什么 |
| 浏览器视觉证据 | 15 | 真实 catalog、桌面视口和移动视口均有可复核证据 |
| 回归与可维护性 | 10 | 关键逻辑有单测/组件测试，复杂度增长被限制在局部 |

目标达成线：95/100 或以上，即差距不超过 5%。

## 3. Iteration 0 基线判断

基线日期：2026-07-06
估计完成度：72/100
目标差距：28%

已具备的基础：

| 能力 | 状态 |
| --- | --- |
| React Flow 画布、palette、节点、schema handle | 已具备 |
| 服务端 `/api/visual/connections/check` 权威连线校验 | 已具备 |
| 服务端 `/api/visual/connections/candidates` 候选枚举 | 已具备 |
| Connect Next / 连接 guide 基础动作 | 已具备 |
| 真实 resource、Java operator、decision table 进入 catalog | 已具备 |

关键缺口：

| 缺口 | 用户感知 | 根因判断 |
| --- | --- | --- |
| 字段级候选路径丢失 | 点击 guide 或拖线后仍容易看到 `connection rejected by server` | `connectionGuideRows()` 只保留 target port，未把 `candidate.target.path` 带入最终 check |
| 拖线失败缺少智能兜底 | 用户明明看到两个节点可能相关，但落线失败 | port-level check 被拒后，前端没有用服务端候选做唯一字段路径重试 |
| 算子卡片过于同质 | `foreach`、`decision table`、resource 看起来像同一种普通方块 | 缺少 operator family 分类、视觉 token 和输入/输出合同摘要 |
| 失败解释仍过于机器化 | 用户不知道要改 schema、选字段，还是换目标节点 | 服务端解释未被前端重组为行动建议 |
| 多候选选择面不显式 | 多个字段兼容时缺少 field picker | 当前只有列表式 guide，还没有直接在拖线流程中承接消歧 |

## 4. Iteration 1：字段路径保真与算子族视觉提示

本轮目标：先消除最刺眼的“明明有候选但 check 仍被拒”的路径丢失问题，同时让 `foreach` 和 `decision table` 在画布上立刻有不同感知。

### 4.1 本轮改动

| 文件 | 改动 |
| --- | --- |
| `resource-gateway-examples/src/main/frontend/src/draftModel.ts` | `OperatorSummary` 增加 `visualKind`、`visualLabel`、`contractHint`、输入/输出合同标签；connection check request 支持 `sourcePath` / `targetPath`；connection guide row 保留 `targetPath` |
| `resource-gateway-examples/src/main/frontend/src/AuthorCanvas.tsx` | 连接逻辑集中到 `applyCheckedConnection()`；port-level 拖线失败后尝试用唯一 accepted 字段候选自动重试；edge 保存并展示字段路径；节点和 palette 显示算子族与合同提示 |
| `resource-gateway-examples/src/main/frontend/src/styles.css` | 为 decision table、foreach、resource、transform、streaming 增加差异化视觉样式 |
| `resource-gateway-examples/src/main/frontend/src/types.ts` | 补齐前端 `capabilities.streaming` 类型 |
| `resource-gateway-examples/src/main/frontend/src/draftModel.test.ts` | 覆盖字段路径序列化、guide row `targetPath`、foreach/decision-table 分类 |
| `resource-gateway-examples/src/main/frontend/src/AuthorCanvas.test.tsx` | 先用红测复现 guide 丢失 `target.path`，再覆盖修复后的 check payload 与算子族渲染 |

### 4.2 验证证据

自动化验证：

```bash
cd resource-gateway-examples/src/main/frontend
npm test -- --run src/draftModel.test.ts src/AuthorCanvas.test.tsx
npm run build
```

结果：

| 验证项 | 结果 |
| --- | --- |
| `draftModel.test.ts` | 60 tests passed |
| `AuthorCanvas.test.tsx` | 10 tests passed |
| Vite/TypeScript build | passed |

红测证据：修复前组件测试刻意让 candidate target 为 `profile.score`，但最终 `/connections/check` 只收到 `{ nodeId: "n2", port: "profile" }`，测试失败；修复后 check payload 保留 `{ path: "score" }`。

浏览器检视证据：

| 场景 | 观察 |
| --- | --- |
| 真实 `/author/` catalog | 看到 `__foreach__:enrichOrders`、`bloge:decisionTable`、`resource:loan-applicant-service.getProfile` 等真实算子 |
| 拖入 foreach 与 decision table | 节点分别带 `kind-foreach` 与 `kind-decision-table` class |
| foreach 节点 | 显示 `Foreach` 与 `item source -> result list` |
| decision table 节点 | 显示 `Decision table` 与 `conditions -> decision row` |
| palette | foreach 显示 `collection -> per-item results`，decision table 显示 `conditions -> matched decision`，transform 显示 `source fields -> mapped output` |
| resource -> decision table 候选 | 仍能看到真实服务端 blocked rows，暴露下一轮需要把 rejected reason 转为更明确行动建议 |

## 5. Iteration 1 后差距复核

本轮后估计完成度：88/100
剩余目标差距：12%

已缩小的差距：

| 改进点 | 结果 |
| --- | --- |
| 字段级路径不再在 guide/check 之间丢失 | 已修复并有红绿测试 |
| 唯一字段候选可自动重试 | 已实现；减少直接拖线后的无解释拒绝 |
| edge label 能显示字段路径 | 已实现；用户能看出连到 root 还是 nested field |
| `foreach` / `decision table` 视觉和合同提示 | 已实现第一层节点与 palette 表达 |

仍未达标的 12%：

| 剩余缺口 | 影响 | 下一轮针对动作 |
| --- | --- | --- |
| blocked reason 仍可能只是 `Connection rejected by server` | 用户不知道应选字段、改 schema 还是换算子 | 在 connection guide 和 notice 中汇总 schema mismatch、source/target type、推荐字段路径和下一步动作 |
| 多个兼容字段时缺少显式 field picker | 拖线失败后只提示有多个字段，操作仍不够直接 | 增加 field candidate picker，允许用户在目标端口下选择具体 `path` 后落线 |
| decision table 还只是卡片级差异 | 规则算子的核心信息没有在 inspector 中展开 | selected inspector 增加条件输入、决策输出、hit policy / rule matrix 占位与配置入口 |
| foreach 还只是卡片级差异 | 用户看不到 collection item 上下文与 per-item mapping | selected inspector 增加 collection source、item alias、per-item output preview |
| 真实浏览器证据还缺少移动矩阵 | 本轮 DOM 证据足够证明视觉 token，但还没有完整 desktop/mobile screenshot 矩阵 | 下一轮补 1440px 与 390px viewport 检查，确保新增 picker/inspector 无溢出 |

下一轮目标分数：至少 95/100，差距小于或等于 5%。

## 6. Iteration 2 计划

优先级按用户痛感排序：

1. 把 rejected connection 转成可执行建议：展示 source endpoint、target endpoint、schema type mismatch、是否存在 accepted nested field、推荐操作。
2. 为多候选字段增加 picker：在拖线失败 notice 和 connection guide 中都能选择具体 target path，最终仍调用服务端 `/connections/check`。
3. 增强 selected operator inspector：为 decision table 与 foreach 提供专有信息区，而不是只靠节点颜色。
4. 做浏览器矩阵验证：至少覆盖真实 catalog 下的 desktop 和 390px mobile，无横向溢出，关键文本不重叠。
5. 如果 `AuthorCanvas.tsx` 继续膨胀，拆出 connection orchestration helper 与 operator visual summary helper，保持后续迭代可控。

## 7. 当前结论

Iteration 1 已经把最核心的字段路径丢失问题和算子族同质化问题往前推进了一大步，但还不能宣布目标完成。剩余 12% 主要集中在“失败解释可行动化”和“复杂算子专有 inspector”上；下一轮应围绕这两个点继续收敛，而不是扩大到新的平台能力。
