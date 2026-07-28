# Resource Gateway Author 任务式交互 UX 实现状态

> 对应方案：[Resource Gateway Author 任务式交互 UX 改善计划](resource-gateway-author-task-oriented-ux-improvement-plan.md)
>
> 状态：Implementation in Progress
>
> 最近更新：2026-07-28
>
> 完成门槛：相对目标差距严格小于 8%

## 评估方法

每轮必须同时提供：

1. 已实现代码和协议证据；
2. 有意义的单元、组件和真实浏览器测试；
3. 生产构建结果；
4. 与目标方案逐项比较后的加权差距；
5. 下一轮只针对剩余差距的实施计划；
6. 独立 Git commit。

评分不复用旧画布日志的“算子表达覆盖率”量尺。新量尺覆盖完整 Author 任务：

| 维度 | 权重 | 完成定义 |
|---|---:|---|
| 任务式 Shell 与信息架构 | 20 | Start、Compose、Contract、Test、Review 和单一主操作 |
| Start/Import 入口 | 10 | 示例、DSL、Library、Blank 统一且不常驻 |
| 上下文与节点专属编辑 | 15 | 单一 Inspector、类型化默认编辑器、稳定 Apply/Cancel |
| Schema-driven Input/Test | 15 | 核心流程无需 Raw JSON，Graph Input/Run Input 分离 |
| 可信状态与错误恢复 | 15 | Execution/Assertions/Contract/Governance 四维状态 |
| Canvas 与复杂图可读性 | 15 | 语义缩放、Overview、edge label、overlay-aware layout |
| 工程边界、测试和灰度 | 10 | 模块边界、固定 fixture、浏览器门禁、可回滚发布 |
| 合计 | 100 | 差距 `< 8%` 才允许完成 |

## Round 0：Stage 0 基线、灰度边界与快修

### 本轮目标

1. 建立不会随实现方便而漂移的 0/5/25/100 节点基线；
2. 建立 Author Workspace v2 的 URL 灰度和 v1 回滚坐标；
3. 修复 Operator Detail 标题四个元素挤入三列造成的动作换行；
4. 用真实 Chrome 做几何断言，而不是只检查 DOM 是否存在；
5. 冻结新的整体 UX 评分口径。

### 已实现

| 能力 | 实现 |
|---|---|
| Workspace version | `authorWorkspaceVersion.ts` 纯函数解析 `?authorWorkspace=v2`；未知值 fail closed 到 v1 |
| 数据隔离 | version 只进入 Workspace UI prop/data attribute，不写入 GraphDraft 或 Scenario |
| 固定 stress corpus | `uxBaselineFixtures.ts` 冻结 0/5/25/100 nodes 和 0/12/50/250 edges |
| 压力特征 | fixture 包含长节点名称、平行路径、长 source path 和 condition edge |
| 浮层快修 | `.operator-detail-heading` 从三列改为四列 |
| 浏览器门禁 | Selenium 检查 v2 opt-in、未知版本回退，以及四个 heading child 同行且互不覆盖 |
| Java 文档 | 新浏览器测试说明测试意图、历史缺陷和为什么必须做几何断言 |

### 测试演进

真实浏览器测试不是一次写对：

1. 第一版依赖 Loan Policy 示例生成 `n4`，随机端口 catalog 下示例没有形成节点；
2. 第二版使用测试 DOM 专有的 `node-wrapper:n1`，真实 React Flow 中不存在；
3. 最终版本只依赖真实 catalog 的首个 operator，并使用生产 DOM 的 `canvas-node:n1`；
4. 最终几何门禁通过。

保留这一过程的原因是：测试必须证明目标行为，不能依赖不属于该行为的示例可用性或 mock DOM 结构。

### 验证证据

前端聚焦测试：

```text
authorWorkspaceVersion.test.ts    3 passed
uxBaselineFixtures.test.ts        6 passed
App.test.tsx                      5 passed
AuthorCanvas.test.tsx            37 passed
total                            51 passed
```

完整前端回归：

```text
18 test files passed
240 tests passed
```

生产前端构建：

```text
tsc --noEmit passed
vite build passed
```

真实浏览器：

```text
VisualAuthoringBrowserDomTest
  #authorWorkspaceVersionAndOperatorDialogHeadingRemainRollbackSafeInRealBrowser
1 passed
```

### 本轮差距评估

| 维度 | 已实现 | 权重 | 判断 |
|---|---:|---:|---|
| 任务式 Shell 与信息架构 | 2 | 20 | 只有灰度坐标，v2 Shell 尚未出现 |
| Start/Import 入口 | 0 | 10 | 仍为三个常驻区域 |
| 上下文与节点专属编辑 | 5 | 15 | 既有专属编辑能力存在，但默认顺序未重构 |
| Schema-driven Input/Test | 6 | 15 | Contract 工作台已有基础，旧 Test Suite 仍大量 Raw JSON |
| 可信状态与错误恢复 | 2 | 15 | 仍有单一 `All passed` 和分散 Result |
| Canvas 与复杂图可读性 | 7 | 15 | 已有 Focus/Map/Auto Layout，但标签和 overlay 问题仍在 |
| 工程边界、测试和灰度 | 7 | 10 | fixture、灰度和浏览器几何门禁已建立 |
| 合计 | **29** | **100** | **剩余差距 71%** |

Stage 0 完成，但总体目标远未完成，不能停。

### Round 1 针对性计划

下一轮只攻最大病根：任务式 Shell。

1. v2 下增加 `Compose / Contract / Test / Review` mode state；
2. 建立 Command Bar，并将 Draft identity、mode、状态摘要和单一主操作收敛到一处；
3. 将 Library、Legacy DSL、Examples 迁入统一 Start/Import Dialog；
4. v2 Palette 首屏只保留搜索、筛选和 operator list；
5. v1 路径保持原样，GraphDraft 导出做等价测试；
6. 增加空白首屏 persistent controls、主操作数量和 Canvas 占比的真实浏览器门禁；
7. 完成第一条纵切：Load example → Compose → Run sample → Review。

Round 1 退出时重新评分；不得因为导航已经出现就宣称 Stage 1 完成。

## Round 1a：任务式 Shell 纵切

### 本轮目标

本轮不试图一次完成 Stage 1 的所有工程项，而是先证明最重要的端到端纵切：

```text
Start → Load complete example → Compose → Run scenario → Review
```

纵切必须复用现有 GraphDraft、DSL import、Operator Library、Contract Workspace 和 Test
Suite，不允许为了新导航复制第二套业务状态。

### 已实现

| 能力 | 实现 |
|---|---|
| Start/Import | v2 首次进入显示统一对话框，集中 Example、DSL、Library 和 Blank 四种开始方式 |
| Command Bar | 顶部集中 Draft identity、四个 mode、四维 truth status、次要操作和唯一主操作 |
| Mode state | `Compose / Contract / Test / Review` 有稳定单选状态；Contract/Test 复用既有工作台 |
| 单一主操作 | 纯函数 `resolveAuthorPrimaryAction` 根据空图、输入错误、未运行、失败和成功派生下一步 |
| Palette 收敛 | v2 左栏默认只保留搜索、facet 和 operator list；Library/DSL 表单只在 Import 流程出现 |
| Inspector 收敛 | v2 右栏只显示当前 Graph 或 selected node；Review 时显示四维结果摘要 |
| 旧能力兼容 | v1 JSX 和协议保持原样；v2 只是 task projection，不改变 GraphDraft wire model |
| 示例体验 | 三个复杂示例在 Start 中同时展示拓扑规模和 Graph input/output Contract 摘要 |
| 示例布局 | 示例加载时自动执行既有布局，避免模板坐标在窄画布中造成 node-node overlap |
| 响应式布局 | 1280 桌面采用 200 / canvas / 240 三列；840 以下切换为轻量纵向布局 |

### 测试与真实浏览器证据

新增纯状态测试覆盖 5 个主操作分支；AuthorCanvas 组件测试新增：

1. v2 首屏只有一个主操作；
2. Start 中能够发现完整示例和 Graph Contract 摘要；
3. DSL/Library 选择会进入原有验证表单；
4. Loan Policy 示例可以由唯一主操作运行两条 Scenario，并自动进入 Review。

完整前端验证：

```text
19 test files passed
248 tests passed
tsc --noEmit passed
vite production build passed
```

真实 packaged Chrome 纵切：

```text
VisualAuthoringBrowserDomTest
  #taskOrientedAuthorShellLoadsRunsAndReviewsWithoutCompetingChromeInRealBrowser
1 passed
```

浏览器在 `1280 × 720` 实测：

| 指标 | 结果 |
|---|---:|
| Canvas 占 post-command-bar workspace | 65.625% |
| 可见主操作 | 1 |
| Command Bar persistent commands | 9 |
| 横向溢出 | 0 px |
| 加载示例 node-node overlap | 0 |
| Start → Run → Review | 通过真实服务完成 |

真实浏览器还发现 Auto Layout 后有 4 处 node/edge-label 相交，且 Fit 缩放下降到约
49%。该问题明确保留为 Stage 4 阻断项，不能因为 Shell 已经成立而关闭。

### 本轮差距评估

| 维度 | 已实现 | 权重 | 判断 |
|---|---:|---:|---|
| 任务式 Shell 与信息架构 | 14 | 20 | 主流程成立；缺 Drawer、panel collapse/resize、URL mode |
| Start/Import 入口 | 8 | 10 | 四入口统一并复用原表单；缺完整三路径浏览器任务门禁 |
| 上下文与节点专属编辑 | 8 | 15 | selection-scoped Inspector 成立；节点默认编辑优先级未改 |
| Schema-driven Input/Test | 6 | 15 | 沿用既有 Contract/Test；默认流程仍有 Raw JSON |
| 可信状态与错误恢复 | 7 | 15 | 四维状态可见；尚未统一服务端 diagnostics 与恢复动作 |
| Canvas 与复杂图可读性 | 7 | 15 | 画布面积达标且 node 不重叠；edge label 与低缩放未解决 |
| 工程边界、测试和灰度 | 9 | 10 | Shell 已拆模块，纯函数/组件/真实 Chrome/生产构建齐全 |
| 合计 | **59** | **100** | **剩余差距 41%** |

Round 1a 是可演示纵切，不是 Stage 1 全量完成，更不是总体完成。

### Round 1b 针对性计划

1. 增加可折叠 Palette/Inspector，并把宽度偏好限制在 UI local state；
2. 增加底部 Diagnostics Drawer，统一承载 graph/run/contract/governance 问题；
3. 将 `mode` 和 `selected node` 写入 URL，并验证刷新、deep link 和 v1 回滚；
4. 为 Example、DSL、Library 三条开始路径补齐 packaged Chrome 任务门禁；
5. 增加 v1/v2 GraphDraft serialization 等价断言；
6. Round 1b 退出后再判断 Stage 1 是否真正满足全部门禁。
