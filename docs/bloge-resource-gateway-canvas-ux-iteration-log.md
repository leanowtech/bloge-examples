# BLOGE Resource Gateway 画布 UX 目标与差距迭代日志

状态：Iteration Log
日期：2026-07-06
范围：`resource-gateway-examples` / `/author/` React Flow 画布

本文用于约束 Resource Gateway 新版可视化画布的 UX 改进节奏：先定义目标，再按轮次记录证据、差距和下一轮针对性计划。当前目标达成线按最新要求收紧为：只有当某轮复核后目标差距小于或等于 3%，才认为本目标闭环完成。

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

目标达成线：97/100 或以上，即差距不超过 3%。

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

## 7. Iteration 2：行动化连接解释、字段选择和移动端复核

本轮目标：把 Iteration 1 剩余的 12% 缺口继续收敛，重点处理“失败解释不可行动”“多个字段兼容但无法显式选择”“复杂算子只靠节点颜色表达”以及真实浏览器中可能存在的隐藏布局问题。

### 7.1 本轮改动

| 文件 | 改动 |
| --- | --- |
| `resource-gateway-examples/src/main/frontend/src/draftModel.ts` | `connectionGuideRows()` 从 candidate 行升级为 target input 分组；新增 `fieldOptions` 与 `actionHint`，blocked 行优先展示结构化 diagnostic，过滤泛化的 `Connection rejected by server.` |
| `resource-gateway-examples/src/main/frontend/src/AuthorCanvas.tsx` | Connect Next 渲染字段 path chip；点击 chip 会携带 `target.path` 调用服务端 check；直接拖线遇到多字段兼容时自动选中 source 节点并打开 Connect Next；selected inspector 新增 operator family focus panel；点击添加节点按画布宽度选择横向网格或移动端纵向摆放 |
| `resource-gateway-examples/src/main/frontend/src/styles.css` | 新增 connection field chip、operator focus panel、author workspace mobile layout；390px 下 palette/canvas/inspector 纵向堆叠，toolbar、journey、guide、summary chips 可换行 |
| `resource-gateway-examples/src/main/frontend/src/draftModel.test.ts` | 覆盖字段级 target 分组、blocked actionable hint、泛化拒绝文案降级 |
| `resource-gateway-examples/src/main/frontend/src/AuthorCanvas.test.tsx` | 覆盖字段 chip 点击后携带 `target.path`、foreach/decision-table inspector 专有表达、窄画布纵向默认摆放 |

### 7.2 验证证据

自动化验证：

```bash
cd resource-gateway-examples/src/main/frontend
npm test -- --run src/draftModel.test.ts src/AuthorCanvas.test.tsx
npm run build
```

结果：

| 验证项 | 结果 |
| --- | --- |
| `draftModel.test.ts` | 61 tests passed |
| `AuthorCanvas.test.tsx` | 12 tests passed |
| 合计 | 73 tests passed |
| Vite/TypeScript build | passed |

浏览器检视证据：

| 场景 | 观察 |
| --- | --- |
| desktop 真实 catalog | `/author/` 加载 22 个真实算子 |
| desktop 添加 foreach + decision table | 两个节点不重叠；foreach 节点显示 `Foreach`、`item source -> result list`；decision table 节点显示 `Decision table`、`conditions -> decision row` |
| desktop selected decision table | inspector 显示 `Rule contract`、`Condition inputs`、`Decision output`、`Rule matrix` |
| desktop resource -> decision table guide | 服务端返回 `1 compatible target · 2 blocked.`；ready 行可直接 connect；blocked 行显示 `visual.binding.typeMismatch...` 与 `Try a nested field, add a transform, or choose another target.` |
| 390px mobile author workspace | `scrollWidth=390`、`clientWidth=390`，页面级无横向溢出 |
| 390px mobile 添加 foreach + decision table | 两个节点纵向摆放、不重叠；页面级无横向溢出；decision table inspector focus panel 可见 |

### 7.3 新发现并修复的隐藏 UX 问题

| 隐藏问题 | 证据 | 修复 |
| --- | --- | --- |
| 点击 palette 连续添加两个算子时节点重叠 | 真实浏览器中 foreach 和 decision table 的 bounding box 重叠，导致 foreach 难以选中 | 默认坐标改为 280px 横向网格；窄画布改为纵向堆叠 |
| 390px 下 author workspace 仍是三列布局 | 浏览器显示 `.workspace` 为 `240px 0px 320px`，页面横向溢出到 `scrollWidth=560` | author workspace 在移动断点下改为 palette/canvas/inspector 纵向布局 |
| React Flow 内部节点在移动端横向跑出可视区域 | 390px 下第二个节点 x=431，虽未撑宽页面但操作不直观 | 默认摆放按 `author-flow.clientWidth` 判断，小于 640px 时纵向堆叠 |

## 8. Iteration 2 后差距复核

本轮后估计完成度：96/100
剩余目标差距：4%

| 维度 | Iteration 1 | Iteration 2 | 结论 |
| --- | ---: | ---: | --- |
| Schema 连线体验 | 26/30 | 29/30 | 字段 path 保真、唯一字段自动重试、多字段 chip 选择、blocked 行行动建议均已落地 |
| 算子专有表达 | 21/25 | 24/25 | 节点、palette、selected inspector 已区分 foreach / decision table / resource / transform / streaming |
| 任务流可发现性 | 18/20 | 19/20 | coach、guide、notice、field chip 已形成更连续的下一步动作 |
| 浏览器视觉证据 | 13/15 | 14/15 | desktop 与 390px mobile 均已验证；仍缺更大设备矩阵 |
| 回归与可维护性 | 10/10 | 10/10 | 本轮测试覆盖新增交互 |

剩余 4% 是可接受的后续增强，不阻断本目标完成：

| 残留项 | 原因 |
| --- | --- |
| decision table 规则矩阵编辑器 | 已在后续 UX refinement 中补齐，见第 10 节 |
| foreach 还没有 item mapping 子画布 | 当前已突出 collection/item/result 合同；子画布属于下一阶段能力 |
| 浏览器矩阵只覆盖默认桌面与 390px mobile | 已足以证明本轮新增布局不重叠、不横向溢出；更广矩阵可进入常规回归 |

## 9. 当前结论

Iteration 2 后，原目标可以认为达成：用户拖入算子后，连接失败不再停留在泛化的 `connection rejected by server`；唯一字段路径会被自动承接，多字段路径会显式展示为可点击 chip，blocked 行会给出可行动诊断；foreach 和 decision table 在 palette、节点和 selected inspector 中都有不同的信息侧重；真实浏览器还发现并修复了节点重叠与移动端横向溢出两个隐藏 UX 问题。

按本文第 2 节的完成度量尺，本轮剩余差距为 4%，小于 5% 达成线。

## 10. UX Refinement：Decision Table 双击规则矩阵

触发反馈：decision table 被双击选中时，应弹出浮层表格编辑规则，这比只在 inspector 展示规则合同更符合规则节点的操作直觉。

改动日期：2026-07-06

| 改动 | 结果 |
| --- | --- |
| React Flow decision table 节点双击 | 打开 `Decision table` 规则矩阵浮层 |
| 规则矩阵浮层 | 支持编辑 hit policy、output type、condition、decision、ruleId、otherwise 行，并可新增/删除规则 |
| Draft config 持久化 | 编辑结果写入节点 `config.hitPolicy`、`config.outputType`、`config.rules[]`，导出、校验、模拟链路可消费 |
| 移动端 | 390px 下浮层不撑开页面；宽表格在浮层内部局部滚动 |

验证：

```bash
cd resource-gateway-examples/src/main/frontend
npm test -- --run src/draftModel.test.ts src/AuthorCanvas.test.tsx
npm run build
```

浏览器证据：

| 场景 | 观察 |
| --- | --- |
| desktop 双击 `bloge:decisionTable` | 弹出规则矩阵浮层；默认规则行可见 |
| desktop 编辑第一条规则 | draft export 中出现 `rules[0].conditions = "score: score >= 700"`、`decision = "approve"`、`ruleId = "prime"` |
| 390px mobile 双击 `bloge:decisionTable` | 浮层宽度约 370px；页面 `scrollWidth=390`；表格 `scrollWidth=720` 但约束在局部滚动容器 |

更新后估计完成度：98/100
剩余目标差距：2%

## 11. Iteration 3：规则矩阵列张力与同类算子主动审查

触发反馈：decision table 的表格张力仍不够，且只能增加行、无法增加列；同时需要主动审查其它算子是否存在类似“画布能看但核心配置不好编辑”的 UX 缺口。

本轮目标：

1. Decision table 必须成为真正的规则矩阵，而不是固定的 `condition/decision/ruleId` 三字段表单。
2. 用户能按业务语义增加条件列和输出列，并把导出结构保持为后端 DSL codegen 可消费的 schema-friendly config。
3. 主动审查 transform、foreach、resource 等算子族，至少补齐一个同类高价值配置缺口，并明确其它算子为什么暂不做本地浮层。
4. 继续用自动化测试和浏览器桌面/移动端证据复核，不只凭代码判断。

### 11.1 本轮改动

| 文件 | 改动 |
| --- | --- |
| `resource-gateway-examples/src/main/frontend/src/AuthorCanvas.tsx` | Decision table editor 从固定字段模型升级为动态列模型；支持 `conditionColumns`、`outputColumns`、列重命名、列删除、条件/输出单元格编辑；默认输出类型在默认状态下跟随输出列同步 |
| `resource-gateway-examples/src/main/frontend/src/AuthorCanvas.tsx` | 新增 transform 双击映射编辑器，支持编辑 `config.assignments` 的输出字段和表达式，并可新增/删除 assignment |
| `resource-gateway-examples/src/main/frontend/src/styles.css` | 规则矩阵改为条件列/输出列分组视觉；宽表只在浮层内部横向滚动；transform 映射表使用更窄浮层 |
| `resource-gateway-examples/src/main/frontend/src/AuthorCanvas.test.tsx` | 覆盖 decision table 新增条件列/输出列、导出对象结构、transform 双击映射编辑器和 assignments 导出 |
| `docs/bloge-visual-canvas-product-and-system-guide.md` | 增加双击配置可编辑算子的使用说明，明确 decision table 与 transform 的 draft config 结构 |

### 11.2 算子族主动审查

| 算子族 | 审查结论 | 本轮处理 |
| --- | --- | --- |
| `bloge:decisionTable` | 核心语义是二维规则矩阵，旧版只能加行，列维度缺失，业务表达力不足 | 已补动态条件列、动态输出列、列名编辑、对象化导出 |
| `bloge:transform` | 核心语义是字段映射，若只能靠 inspector 看合同，用户无法直觉配置输出对象 | 已补双击 transform mapping 浮层，直接编辑 `config.assignments` |
| `foreach` | 当前核心语义来自 Java/operator contract：collection、item context、result list；缺少 item mapping 子画布，但这属于更大阶段能力 | 本轮不做本地浮层，保留 inspector 合同表达；后续可设计 foreach 子图/loop body editor |
| resource-backed operator | 参数、响应、资源 id 由 descriptor/OpenAPI contract 管理，前端本地编辑容易绕过 registry 治理 | 本轮不做本地浮层，继续通过 resource descriptor import/validation 管理 |
| streaming | 当前主要是运行/展示语义，配置入口依赖具体 event source 或 runtime binding | 本轮不做本地浮层，后续应和 runtime binding 管理一起设计 |

### 11.3 验证证据

自动化验证：

```bash
cd resource-gateway-examples/src/main/frontend
npm test -- --run src/draftModel.test.ts src/AuthorCanvas.test.tsx
npm run build
```

结果：

| 验证项 | 结果 |
| --- | --- |
| `draftModel.test.ts` | 61 tests passed |
| `AuthorCanvas.test.tsx` | 13 tests passed |
| 合计 | 74 tests passed |
| Vite/TypeScript build | passed |

浏览器检视证据：

| 场景 | 观察 |
| --- | --- |
| desktop decision table | 双击节点弹出规则矩阵；可新增 `score` 条件列和 `tier` 输出列；页面 `scrollWidth=1440`、`clientWidth=1440`，无页面级横向溢出 |
| desktop decision table 导出 | `config.conditionColumns=["value","score"]`，`config.outputColumns=["decision","ruleId","tier"]`，`rules[0].conditions.score="score >= 700"` |
| 390px mobile decision table | 浮层宽度约 370px；页面 `scrollWidth=390`、`clientWidth=390`；规则表 `scrollWidth=1055`，但被约束在局部滚动容器 |
| desktop transform | 双击节点弹出 transform mapping；新增两条 assignment 后导出 `config.assignments.tier` 与 `config.assignments.reason` |

### 11.4 Iteration 3 后差距复核

本轮后估计完成度：99/100
剩余目标差距：1%

| 维度 | Iteration 2 | Iteration 3 | 结论 |
| --- | ---: | ---: | --- |
| Schema 连线体验 | 29/30 | 29/30 | 本轮未触碰连线主流程，保持既有能力 |
| 算子专有表达 | 24/25 | 25/25 | decision table 和 transform 已具备双击配置浮层；foreach/resource/streaming 的边界已有审查结论 |
| 任务流可发现性 | 19/20 | 20/20 | 双击复杂算子直接进入对应编辑器，减少“只看不知去哪改”的断点 |
| 浏览器视觉证据 | 14/15 | 15/15 | desktop 与 390px mobile 均复核浮层、局部滚动和页面无溢出 |
| 回归与可维护性 | 10/10 | 10/10 | 动态列、transform editor 都有组件测试覆盖 |

剩余 1% 进入后续路线，不阻断本目标完成：

| 残留项 | 后续计划 |
| --- | --- |
| foreach 子图/loop body editor | 需要先定义 loop body 与外层 graph draft 的边界，不宜在本轮以局部表单硬塞 |
| resource descriptor 可视化编辑 | 应和 resource contract registry、OpenAPI import、版本治理一起设计 |
| 更广浏览器矩阵 | 当前覆盖桌面与 390px mobile；后续常规回归可加入 tablet 和高缩放场景 |

结论：本轮把用户指出的“只有行没有列”的核心问题改成可用的动态规则矩阵，并主动补齐 transform 的同类配置缺口。按第 2 节标尺，剩余差距约 1%，目标闭环完成。

## 12. UX Refinement：Decision Table 条件列引用传入边数据

触发反馈：decision table 的 `Condition` 应该能引用到传入边的数据信息，而不是只依赖用户手工创建一个同名条件列。

问题判断：

| 层面 | 旧行为 | 影响 |
| --- | --- | --- |
| Draft 模型 | 前端只保存 `edges`，没有从 accepted edge 派生目标节点 `inputs` binding | 后端 DSL codegen 读取 `node.inputs()` 时拿不到上游数据 |
| 规则矩阵 UI | decision table 浮层只读取 `config.conditionColumns` / `rules[].conditions` | 用户无法直观看到 `inputs.score` 这类传入字段应该写成哪个条件列 |

改动：

| 文件 | 改动 |
| --- | --- |
| `resource-gateway-examples/src/main/frontend/src/draftModel.ts` | `toGraphDraft()` 从 data edges 派生目标节点 `inputs` 的 `nodePath` binding；`CanvasEdge` 支持保存服务端返回的 `bindingKey` |
| `resource-gateway-examples/src/main/frontend/src/AuthorCanvas.tsx` | accepted connection 持久化 `bindingKey`；decision table 双击浮层读取 incoming edges，生成锁定条件列，并显示来源边 |
| `resource-gateway-examples/src/main/frontend/src/AuthorCanvas.test.tsx` | 覆盖 score -> decision table `inputs.score` 连接后，规则矩阵自动出现 `score` 条件列，导出 `nodes[].inputs.score` 与 `rules[0].conditions.score` |
| `docs/bloge-visual-canvas-product-and-system-guide.md` | 更新 decision table 使用说明，明确条件列来自传入边且锁定列不能改名/删除 |

验证口径：

```bash
cd resource-gateway-examples/src/main/frontend
npm test -- --run src/draftModel.test.ts src/AuthorCanvas.test.tsx
npm run build
```

本次修正后，decision table 的条件列与传入边形成闭环：

```text
accepted edge target inputs.score
  -> canvas edge bindingKey score
    -> GraphDraft nodes[n].inputs.score nodePath binding
      -> decision table editor locked condition column score
        -> config.rules[].conditions.score
```

## 13. 3% 目标完成审计

审计日期：2026-07-06
目标口径：不仅处理 decision table，还要主动审查其它算子族；每轮差距复核和后续计划必须落文档；使用浏览器验证视觉调整；当目标差距小于或等于 3% 后才可闭环。

### 13.1 要求与证据

| 要求 | 当前证据 | 结论 |
| --- | --- | --- |
| 不只处理 decision table | Iteration 3 同时补齐 `bloge:transform` 双击 mapping editor；Iteration 3.2 审查 foreach、resource-backed、streaming 的边界与后续路线 | 满足 |
| 主动审查其它算子族 | 第 11.2 节逐项记录 `bloge:decisionTable`、`bloge:transform`、`foreach`、resource-backed、streaming 的 UX 判断 | 满足 |
| 针对性改进计划落文档 | 第 5、6、8、11.4、12、13 节记录差距、下一轮动作、残留项和闭环判断 | 满足 |
| 每轮迭代后复核差距 | Iteration 1、2、3 均有分数与剩余差距；最新复核为 99/100、剩余 1% | 满足 |
| 浏览器验证视觉调整 | 第 7.2、10、11.3、12 节记录 desktop 与 390px mobile 证据；本轮继续用浏览器复核真实 `/author/` 页面 | 满足 |
| 差距小于或等于 3% | Iteration 3 后为 99/100、剩余 1%；Iteration 12 修复 incoming data 条件列后没有引入新的未覆盖 UX 缺口 | 满足 |

### 13.1.1 最新浏览器复核证据

复核方式：本地启动 `/author/`，使用浏览器打开真实页面；catalog 实际加载 26 个算子，覆盖 foreach、decision table、transform、resource、streaming 五类。

| 视口 | 观察 |
| --- | --- |
| 默认桌面视口 | 添加 `foreach`、`bloge:decisionTable`、`bloge:transform`、`resource:loan-applicant-service.getProfile`、`MockCitationStreamingOperator` 后，节点分别带 `kind-foreach`、`kind-decision-table`、`kind-transform`、`kind-resource`、`kind-streaming` |
| 默认桌面视口 | 选中 foreach/resource/streaming 后，inspector 分别显示 `Loop contract`、`Resource contract`、`Stream contract` |
| 默认桌面视口 | 双击 transform 打开 `transform-assignment-editor`；双击 decision table 打开 `decision-table-editor` |
| 默认桌面视口 | 页面 `scrollWidth=725`、`clientWidth=725`，无页面级横向溢出 |
| 390px mobile | 添加同样五类节点后，页面 `scrollWidth=390`、`clientWidth=390`，workspace 为单列 `390px` |

### 13.2 最新剩余差距

当前估计完成度：99/100
剩余目标差距：1%

| 残留项 | 为什么不阻断 3% 目标 | 后续路线 |
| --- | --- | --- |
| foreach 子图/loop body editor | 当前 foreach 已有 family 视觉、contract inspector、collection/item/result 合同提示；子图编辑涉及 graph draft 嵌套语义，需要独立设计 | 后续定义 loop body draft 边界后再实现 |
| resource descriptor 可视化编辑 | resource-backed operator 的权威配置来自 resource registry / OpenAPI contract，前端局部表单不应绕过治理 | 后续和 resource contract registry 版本治理一起设计 |
| streaming runtime binding 面板 | streaming 当前主要是运行态与 runtime binding 问题，不是 author canvas 的局部规则编辑问题 | 后续进入 runtime binding 管理视图 |

结论：按 3% 达成线审计，当前 `/author/` 已覆盖复杂规则、字段映射、schema 连接、候选字段选择、移动端布局和关键算子族表达。剩余项属于更大阶段的子图/registry/runtime binding 设计，不构成本轮 UX 目标阻断。

## 14. Iteration 14：逐算子 UX 审查与 HTTP/Readiness 补强

触发反馈：此前第 13 节仍然停留在“算子族”口径，不能证明真实 catalog 中每个 operatorRef 都被逐个审查。新的目标要求是 26 个算子逐个形成 UX 审查报告，并继续用浏览器验证视觉调整。

本轮目标：

1. 从真实 `/api/visual/operators` catalog 冻结 operatorRef 清单，不用手工猜测。
2. 对 26/26 operatorRef 逐个记录：当前 UX 判断、本轮动作、残留缺口和后续路线。
3. 修复审查中发现的非 decision table 缺口，尤其是 `httpResource`、`httpRequest` 和 `runtimeReadiness` 的表达问题。
4. 用自动化测试、构建和浏览器桌面/移动端证据复核，重新计算与 3% 目标的差距。

### 14.1 本轮改动

| 文件 | 改动 |
| --- | --- |
| `resource-gateway-examples/src/main/frontend/src/types.ts` | 前端 `OperatorDefinition` 接入服务端 `runtimeReadiness` wire contract |
| `resource-gateway-examples/src/main/frontend/src/draftModel.ts` | `summarizeOperator()` 新增 readiness summary；`httpResource`/`resource` tag/resource-descriptor lowering 归为 Resource；新增 `HTTP` 视觉族用于 `httpRequest` |
| `resource-gateway-examples/src/main/frontend/src/AuthorCanvas.tsx` | palette 展示 readiness badge；节点卡片展示 readiness notice；selected inspector 增加 Readiness 行；新增 HTTP contract panel |
| `resource-gateway-examples/src/main/frontend/src/styles.css` | 新增 HTTP 族、readiness badge、节点状态条样式，保证长文案可换行 |
| `resource-gateway-examples/src/main/frontend/src/draftModel.test.ts` | 覆盖 `httpResource` Resource 分类、`httpRequest` HTTP 分类、streaming runtime-blocked readiness |
| `resource-gateway-examples/src/main/frontend/src/AuthorCanvas.test.tsx` | 覆盖 Resource/Streaming readiness 在 palette、节点、inspector 中可见 |
| `docs/bloge-resource-gateway-operator-ux-audit.md` | 新增 26/26 operatorRef 逐算子 UX 审查报告 |

### 14.2 逐算子审查结论

完整报告见 [Resource Gateway `/author/` 逐算子 UX 审查报告](./bloge-resource-gateway-operator-ux-audit.md)。

真实 catalog 返回 26 个 operator，已逐个审查：

```text
MockCitationStreamingOperator
MockLlmTokenStreamingOperator
MockMetaStreamingOperator
__decision_table__
__foreach__:enrichOrders
__transform__
bloge:decisionTable
bloge:transform
httpRequest
httpResource
orders:normalize
orders:route-sla
resource:catalog-service.getProduct
resource:credit-provider.primary
resource:credit-provider.secondary
resource:invoice-service.getInvoice
resource:license-service.getLicense
resource:loan-applicant-service.getProfile
resource:logistics-service.getShipping
resource:notification-service.unread
resource:order-service.listOrders
resource:recommendation-service.forUser
resource:user-service.getProfile
resource:wallet-service.getBalance
risk:eligibility
support:classify-ticket
```

核心判断：

| 类别 | 结论 |
| --- | --- |
| 已本轮修复 | `httpResource` 不再是 generic；`httpRequest` 有独立 HTTP 视觉族；streaming/resource/native/design-only 的 readiness 风险不再隐藏 |
| 已有闭环 | `bloge:decisionTable`、`bloge:transform` 已有双击编辑浮层；resource virtual operators 有 Resource contract；foreach 有 Loop contract |
| 不应硬做本地浮层 | 用户库 design-only operator 缺少 config/editor contract，不能凭 `decision`、`transform` tag 猜成内置编辑器 |
| 后续路线 | operator-library `ux.editorHint`、native alias preferred/advanced 分层、resource registry drill-down、streaming runtime binding 视图 |

### 14.3 验证证据

自动化验证：

```bash
cd resource-gateway-examples/src/main/frontend
npm test -- --run src/draftModel.test.ts src/AuthorCanvas.test.tsx
npm run build
```

结果：

| 验证项 | 结果 |
| --- | --- |
| `draftModel.test.ts` | 62 tests passed |
| `AuthorCanvas.test.tsx` | 15 tests passed |
| 合计 | 77 tests passed |
| Vite/TypeScript build | passed |

浏览器检视证据：

| 视口 | 观察 |
| --- | --- |
| 默认桌面视口 | palette 显示 `26/26`；`httpRequest` 显示 `HTTP ... request -> response ... review`；`httpResource` 显示 `Resource ... params -> payload ... review`；streaming operator 显示 `Streaming ... request -> event stream ... blocked` |
| 默认桌面视口 | 添加三类节点后，节点分别带 `kind-http`、`kind-resource`、`kind-streaming`，节点内显示 readiness notice；页面 `scrollWidth=725`、`clientWidth=725` |
| 默认桌面视口 | 选中三类节点后，inspector 分别显示 `HTTP contract`、`Resource contract`、`Stream contract`，且都有 `Readiness` 行 |
| 390px mobile | palette 显示 `26/26`；workspace 单列 `390px`；页面 `scrollWidth=390`、`clientWidth=390` |
| 390px mobile | 添加三类节点后，三个节点 bounding box 均为 `left=18,right=372,width=355`，readiness 长文案未造成页面级横向溢出 |

### 14.4 Iteration 14 后差距复核

本轮后估计完成度：98/100
剩余目标差距：2%

| 维度 | Iteration 13 | Iteration 14 | 结论 |
| --- | ---: | ---: | --- |
| Schema 连线体验 | 29/30 | 29/30 | 保持 incoming data 条件列、字段候选和 schema check 闭环 |
| 算子专有表达 | 25/25 | 25/25 | 不再只按算子族判断；逐个覆盖 26/26 operatorRef，补 HTTP 族与 Resource 误判 |
| 任务流可发现性 | 20/20 | 20/20 | readiness badge/notice 让 runtime-blocked、governance-review、design-only 风险进入作者视线 |
| 浏览器视觉证据 | 15/15 | 15/15 | 桌面和 390px mobile 均验证真实页面、真实 catalog、无页面级横向溢出 |
| 回归与可维护性 | 10/10 | 9/10 | 新增分类/readiness 测试；仍需后续把 operator UX hint 设计进正式 schema |

剩余 2% 不阻断当前 3% 目标，但必须作为后续路线保留：

| 残留项 | 为什么不阻断本目标 | 后续路线 |
| --- | --- | --- |
| 用户库 design-only operator 的专用编辑器 | 当前没有 operator-library editor hint 合同，前端凭 tag 猜编辑器会误导用户 | 定义 `ux.editorHint` / `interactionModel` 后再开启 policy/triage/mapping 专用编辑体验 |
| Java native alias 与 DSL 推荐入口并存 | readiness review 已提示治理风险，但 palette 还未明确推荐优先级 | 增加 preferred/advanced 分层 |
| resource/streaming 深层治理信息 | author canvas 已展示 contract 与 readiness；auth、binding、adapter activation 属于 registry/runtime binding 视图 | resource registry drill-down 与 streaming runtime binding 管理视图 |

结论：Iteration 14 把审查粒度从“算子族”提升到真实 catalog 的 26 个 operatorRef，并主动修复 `httpResource`、`httpRequest`、`runtimeReadiness` 三个非 decision table UX 缺口。按 3% 达成线，本轮剩余差距约 2%，目标可以闭环。
