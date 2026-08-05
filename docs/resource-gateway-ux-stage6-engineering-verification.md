# Resource Gateway Stage 6 工程验收与体验证明记录

> 日期：2026-08-05
>
> 证据等级：E2（真实服务 + 真实浏览器 + 自动化门禁）
>
> 结论：工程体验门禁 `96 / 100`，已验收核心任务的 P0/P1 为 0；E3/E4 仍待外部证据

## 1. 这份记录证明什么

这不是新一轮主观打分，而是 [UX 深度审阅与针对性演进计划](resource-gateway-ux-deep-audit-and-targeted-evolution-plan.md)
Stage 0 至 Stage 5 的工程收口记录。它回答三个问题：

1. 固定核心任务是否能在真实服务上完成；
2. 桌面、平板和移动视口是否出现遮挡、溢出、不可点击或信息丢失；
3. 双语、状态、Evidence 和响应式规则是否有可自动复核的回归门禁。

它不能证明新用户在无协助情况下的任务成功率，也不能证明复杂组织连续两个发布周期的
正确性。前者必须由 E3 用户研究证明，后者必须由 E4 生产观测证明。

## 2. 最后一轮发现与修复

| 现象 | 根因 | 修复 | 防回归 |
|---|---|---|---|
| 中文演练队列显示 `PARTIAL` | 组件直接渲染 wire enum | 队列状态统一通过 `t(...)` 做产品语言投影 | 组件级中文测试 |
| 条目结果显示 `FAILED` / `PASSED` | 证据状态和产品文案没有分层 | 保留原始状态用于样式和协议，视图仅显示本地化标签 | 九个生命周期状态字典测试 |
| 诊断、重试和下一步操作中英混排 | 字符串在运行时拼接，静态 inventory 无法看见 | 增加动态产品文案 presenter，分离产品文案、协议码和业务资产 | 真实中文组件与浏览器抽屉验收 |
| 响应式“不溢出”不代表“能完成任务” | 过去验收只看页面宽度 | 验收改为命令、画布、Matrix、sticky 列和触摸面联合几何断言 | `check:ux` + 固定浏览器矩阵 |

这次修复不改变协议数据。`PARTIAL` 等原始值仍是 API、日志、导出和诊断的稳定机器语义，
只在人机交互层转换为当前语言的可读标签。

## 3. 真实浏览器验收矩阵

验收使用真实 Spring `test` profile、Vite 前端和 Chromium，不用 JSDOM 尺寸代替视觉结论。

| 视口 | 任务 | 可证伪结果 | 结论 |
|---|---|---|---|
| 1280 x 720 | Loan Graph 自动布局与边信息 | 5 nodes / 12 edges；4 个 edge label；node-label、label-label、越界均为 0 | 通过 |
| 1024 x 720 | Compose 命令、导航和画布 | 页面无横向溢出，命令和画布同时可见 | 通过 |
| 820 x 720 | 五维状态、四列工具和 Graph | 命令条未遮挡画布，工具最小高度 40px | 通过 |
| 390 x 844 | Compose、导航和密度切换 | 页面宽度等于视口，导航两列，按钮不小于 40px | 通过 |
| 390 x 844 | Scenario Matrix | 首屏可见 3 条 Case，Case 列 sticky，水平继续提示存在 | 通过 |
| 390 x 844 | 空算子库首次进入 | Start Choice 独占中央工作面，只有一个 primary action | 通过 |
| 390 x 844 | 中文 Rehearsals | 状态、动态诊断、负责人角色、重试时间线和下一步操作均为中文；无横向溢出，无小于 40px 的按钮 | 通过 |

图形布局的“通过”同时要求边标签可读、节点不遮挡边信息且最上与最下标签保持正安全距离，
不是只判断 React Flow 是否渲染。

## 4. 自动化门禁

| 门禁 | 验证范围 | 结果 |
|---|---|---|
| `npm test` | Author、Contract、Scenario、Evidence、Library、Rehearsal、i18n、响应式和模型 | 75 files / 564 tests passed |
| `npm run check:i18n` | 词条完整性、动态状态、核心任务表面 | 5 files / 30 tests passed |
| `npm run check:ux` | 字号、触摸面、密度、导航、Matrix 与响应式规则 | 3 files / 22 tests passed |
| `npm run build` | TypeScript、双语和 UX 门禁、Vite 生产构建 | 通过 |
| `mvn -f resource-gateway-examples/pom.xml clean verify` | API、执行、持久化、测试控制面和浏览器集成 | 5,898 tests，0 failures，0 errors，13 skipped |

Stage 6 新增的组件级用例不只检查字典：它使用中文 locale 渲染真实 Rehearsal Workbench，
输入 `PARTIAL` / `FAILED` / `PASSED` 协议状态和动态断言统计，断言用户看到本地化状态、诊断与
“执行条目”无障碍分组名，同时保留 `DEPENDENCY_TIMEOUT` 等协议码。

### 4.1 已知非阻断边界

| 边界 | 当前影响 | 演进门禁 |
|---|---|---|
| 生产构建主入口约 710.40 kB | Vite 警告，弱网首次交互仍需实测 | E3 记录路由级 LCP/INP；弱网超预算时按 surface 拆分 chunk，初始入口目标 < 500 kB |
| live-only 凭证治理补救面板未在当前演示 profile 做 E2 双语走查 | 示例模式不展示该面板；现有 5 条组件测试不等于真实服务证据 | 中文全量 GA 前使用开启 remediation API 的部署走完 freeze、approve、submit、compare |
| 示例的 Graph、plan 和业务描述仍可为英文 | 这些是可导入、可审计的业务资产，不是产品 UI | 不做运行时翻译；未来通过中文 sample pack 提供本地化内容 |

这三项不否定当前核心任务的 E2 结论，但都不允许被“已达 96 分”遮蔽。第二项如果被纳入中文
GA 的任务范围，立即升为 P1 发布门禁。

## 5. E3 执行方案

E3 不在开发者已知路径的前提下进行。每类角色招募 3 人，共 12 人：Graph 作者、测试工程师、
治理 reviewer 和平台集成工程师。研究版本必须锁定 commit、locale、视口、数据集和后端 profile。

| 数据 | 采集方式 | 失败判定 |
|---|---|---|
| 任务成功率 | 最终产物与证据校验，不以用户自报代替 | 产物不完整、证据不当前或不可用于目标 gate |
| 完成时间 | 从任务卡暴露到产物验证通过 | 超过主计划门槛或放弃 |
| 协助量 | 持人按预定等级记录每次提示 | 出现路径性提示即不再计入“无协助”成功 |
| 错误决策 | 记录 mock、stale、schema 和 Evidence 判断 | 将探索证据当作发布证据等安全性错误 |
| 中英差异 | 同角色分层随机，使用等价任务 | 成功率差 > 10% 或耗时差 > 15% |

主持人不解释 Contract、Fixture、Evidence 或 stale。所有观察先记录为事实，再按根因归类为
语言、信息架构、反馈、可操作性、概念模型或系统缺陷。P0/P1 修复后必须重跑受影响任务，
不用平均分稀释关键失败。

## 6. E4 组织证据

E4 要求两个真实团队、连续两个发布周期。每个周期至少保留：

- 已发布 Graph、Contract、Suite、Fixture 和 fingerprint 关联；
- Run Trace、Evidence class、mock 标记、gate 决策和人工 override；
- stale / schema / assertion 失败的处理时间和最终决策；
- 误发布、错误放行、错误拒绝和紧急回滚事件；
- locale、角色、团队、图复杂度和测试规模等分层维度。

任一周期出现因误解 mock、schema 或 Evidence 而导致的错误发布决策，成熟度声明立即失效，
必须回流根因分析与新的 E2/E3 验收。

## 7. 如何复核

1. 在仓库根目录用 `./scripts/start-visual-canvas-demo.sh` 启动演示服务。
2. 打开 `http://localhost:8080/author/?lang=zh-CN`，载入 **Loan policy fallback** 并执行自动布局。
3. 分别以 1280、1024、820 和 390px 宽度走查 Compose、Scenarios、Libraries 和 Rehearsals。
4. 在中文 Rehearsals 中确认队列和条目显示“部分完成”、“已失败”和“已通过”。
5. 运行本文第 4 节的四组工程门禁。
6. 完成后使用 `./scripts/stop-visual-canvas-demo.sh` 停止服务。

## 8. 最终判断

当前可以对外声明：**Resource Gateway 已验收核心任务的已知 P0/P1 UX 硬伤已清零，E2 工程体验门禁达到
96/100，具备进入目标用户研究和真实组织试运行的条件。**

当前不能声明：“真实用户体验已经 95 分”或“已证明复杂企业组织长期可用”。这两个结论只能在
E3/E4 数据达到方案门槛后获得。
