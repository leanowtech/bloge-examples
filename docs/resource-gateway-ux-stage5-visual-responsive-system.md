# Resource Gateway Stage 5 视觉系统与响应式任务布局

> 状态：Implemented / E1 passed / E2 browser verification passed
>
> 日期：2026-08-05
>
> 范围：Author Workspace v2、Scenario Matrix、Library、全局导航、中文与英文界面
>
> 对应总计划：[UX 深度审阅与针对性演进计划](resource-gateway-ux-deep-audit-and-targeted-evolution-plan.md)

## 1. 这轮解决什么

Stage 5 不以“页面能缩小”为目标，而以**用户在不同视口下仍能完成当前任务**为目标。
它集中根治五类持续回潮的问题：

1. 10px/11px 文案依赖高分辨率屏幕，复杂页面看得见但读不动；
2. 顶栏、任务命令、状态和辅助工具在窄屏同时抢占空间；
3. Compact 被误解为缩小所有东西，导致触摸目标和正文一起缩水；
4. 横向数据表可以滚动，但用户不知道右侧还有内容；
5. 主画布已使用标准图标，深层编辑器仍散落 `x`、`×`、`+` 字符按钮。

本轮形成四条产品不变量：

- **字号下限不随密度变化**：桌面正文 13px、辅助信息 12px、移动正文 14px；
- **密度只改变空间，不改变可读性**：Compact 收紧 padding、gap 和桌面控件高度；
- **移动端按任务删减重复信息**：保留主操作和模式切换，状态与工具按需展开；
- **触摸目标独立于视觉密度**：840px 以下及 coarse pointer 环境中关键控件至少 40px。

## 2. 用户能感知到的变化

### 2.1 Comfortable / Compact 密度切换

顶栏语言切换旁新增密度分段控件：

| 模式 | 默认 | 适用任务 | 不会改变 |
|---|---|---|---|
| Comfortable | 是 | 首次使用、审阅、触摸设备、长时间阅读 | 字号、语义、可执行状态 |
| Compact | 否 | 熟练作者、桌面批量操作、高信息密度工作 | 字号下限、40px 触摸目标、数据内容 |

选择结果保存在浏览器 `localStorage` 的 `bloge.visual.density`，刷新、页面切换和中英文切换后
保持一致。存储不可用或值不合法时 fail open 到 Comfortable。

### 2.2 移动导航从横向滚动改为明确展开

840px 以下，顶层工作区导航默认收起为 Menu 图标。点击后以两列网格展示 Author、Libraries、
Rehearsals、Showcase 和 Legacy，不再要求用户发现一条没有提示的横向滚动带。

导航的打开状态是临时界面状态，不写入 URL，也不改变当前 Graph、Scenario 或 Evidence 坐标。

### 2.3 Author 命令条按角色和任务重排

390px 下 Compose 保留：

1. 当前草稿身份；
2. 唯一主操作；
3. Compose / Contract / Scenarios / Evidence 模式；
4. 可展开的 Readiness 摘要；
5. 可展开的辅助 Tools。

Contract、Scenarios 和 Evidence 已拥有自己的中央任务面，因此移动端不再重复展示草稿身份、
五维 readiness 和整组辅助命令，只保留当前主操作和四个模式入口。这个处理减少滚动距离，同时
避免同一任务出现两组看似都重要的动作。

461px 至 840px 仍展示五维状态，但使用五列等宽摘要；辅助命令使用四列。低复杂度 Graph
隐藏重复的节点搜索器，复杂图仍保留定位能力。

### 2.4 画布把边信息留在可见区域

Fit View 的安全 padding 从普通图 `0.04` 提升到 `0.10`，复杂图从 `0.06` 提升到 `0.12`。
这不是单纯缩小 Graph，而是为字段边 label 和视口边界预留稳定安全带。Auto Layout 后用户可按
Overview、Focus、Inspect 三个阅读任务继续查看拓扑、路径和精确字段。

### 2.5 横向工作面的可发现性

Scenario Matrix、Library 表格和 Schema Tree Editor 使用双侧 continuation shadow；滚动到边缘时
本地白色遮罩与外侧阴影共同提示“还有内容”。Scenario Matrix 固定选择列和 Case 列，横向滚动时
业务身份不会丢失。移动端启用稳定 scrollbar gutter，避免滚动条出现时内容跳动。

### 2.6 图标与键盘焦点一致性

- 顶层 Menu、密度、画布侧栏、添加和关闭操作使用 Lucide 图标；
- 不熟悉的图标按钮同时有本地化 `title` 和 `aria-label`；
- `x`、`×`、`+` 字符按钮已从产品 TSX 中归零；
- 所有可交互元素共享高对比 `focus-visible` 轮廓；
- 图标使用 `aria-hidden`，可访问名称由按钮本身提供，不产生重复朗读。

## 3. 响应式职责边界

移动端不是桌面编排器的机械压缩版。

| 任务 | 390px 策略 | 桌面策略 |
|---|---|---|
| 查看 Graph 形状 | Overview、Map、选中节点 | Overview / Focus / Inspect 完整切换 |
| 运行 Scenario | 直接支持，主操作优先 | 支持批量和详情并排 |
| 查看 Evidence | 单列完整审阅 | 结果与技术详情并排 |
| 修改简单字段 | 支持 | 支持 |
| 大矩阵编辑 | sticky Case + 横向滚动提示 | 完整 Matrix |
| 密集字段连线 | 保留查看和轻量修复 | 完整创作 |
| 大规模 Schema authoring | 提供阅读与简单字段 | 完整创作 |

这条边界避免把 100 节点创作强塞进 390px，同时保证失败定位、运行、Evidence 审阅和简单修复
不必切换设备。

## 4. 工程结构

新增或收敛的模块：

```text
src/ux/density.ts             密度值、持久化和容错
src/ux/DensityProvider.tsx    根节点 data-density 投影
src/ux/DensitySwitcher.tsx    可访问的分段控件
src/styles/tokens.css         字号、空间、控件和焦点 token
src/styles/responsive.css     跨页面响应式任务规则
src/styles.css                存量 surface 规则，停止新增跨页面策略
```

样式加载顺序为 `tokens.css -> styles.css -> responsive.css`：token 先定义，存量页面保持兼容，
Stage 5 的跨页面策略最后覆盖。后续 surface 样式应逐步从 `styles.css` 拆出，但不得以一次性重写
换取形式上的“模块化”。

### 4.1 Token 契约

```css
--rg-font-body: 13px;
--rg-font-aux: 12px;
--rg-font-mobile-body: 14px;
--rg-touch-target: 40px;
```

Comfortable 与 Compact 只重定义 `--rg-panel-padding`、`--rg-surface-gap`、
`--rg-control-min` 等空间 token。移动和 coarse pointer 规则在最终层重新保证 40px 触摸目标，
因此 Compact 不会破坏可操作性。

### 4.2 测试并发稳定性

`AuthorCanvas.test.tsx` 是包含完整 React/JSDOM、三个示例和批量运行的重型集成文件。四核环境
让它与其余 73 个测试文件并行时，会出现事件循环饥饿并触发 5 秒轮询预算；两个 worker 在服务和
浏览器同时运行时仍可复现。Vitest 因此固定为 `maxWorkers: 1`，保留原始断言与超时预算且 558 条
测试稳定通过。Scenario Import 的脱敏用例也从固定三个 tick 改为等待可观察的 `[masked]` 结果。
这里治理的是测试资源模型和完成条件，没有扩大全局超时，也没有弱化业务断言。

## 5. 自动化门禁

新增 `npm run check:ux`，覆盖：

- literal 8px/9px/10px/11px 字号回归；
- 字号、移动正文和触摸目标 token；
- Comfortable 默认与 Compact 只改变 spacing；
- 移动导航不得退回横向滚动；
- 840px 与 coarse pointer 的 40px 控件；
- Matrix、Library、Schema 横向 continuation shadow；
- 非 Compose 移动任务面的重复命令收敛；
- 密度读取、非法值容错、持久化和 DOM 投影；
- 移动导航及密度控件的交互状态。

构建顺序为：

```bash
npm run check:i18n
npm run check:ux
tsc --noEmit
vite build
```

当前工程证据：

| 门禁 | 结果 |
|---|---|
| `npm run check:i18n` | 5 files / 30 tests passed |
| `npm run check:ux` | 3 files / 22 tests passed |
| `npm test` | 74 files / 558 tests passed |
| `npm run build` | TypeScript 与生产构建通过 |

## 6. E2 浏览器验收

固定任务使用真实 Spring 服务、Vite 页面与 Chromium，不以 DOM 单测替代视觉判断。

| 视口 | 任务 | 结果 |
|---|---|---|
| 1280 x 720 | Loan Graph Auto Layout、Inspect、边 label | 5 nodes / 12 edges；节点、label、视口碰撞为 0 |
| 1024 x 720 | Compose 命令、导航、Graph | 页面无横向溢出，中央画布完整可用 |
| 820 x 720 | 五维状态、四列工具、Graph | 命令条保持紧凑，画布仍有约 441px 可用高度 |
| 390 x 844 | Compose、导航、密度 | 页面宽度等于视口；导航两列；控件不小于 40px |
| 390 x 844 | Scenario Matrix | 首屏可见 3 条 Case；Case 列 sticky；横向内容有继续提示 |
| 390 x 844 | 空算子库首次进入 | Start Choice 独占中央工作面；只有“创建草稿”一个主操作 |

在 1280px Loan Graph 上，最上方 edge label 距 React Flow 顶边仍有正安全距离，未发现 label-node
或 label-label 覆盖。390px Scenarios 中命令条只保留主操作与模式，Matrix 获得约 226px 可见高度。

## 7. 如何体验

1. 启动 Resource Gateway，打开 `http://localhost:8080/author/?lang=zh-CN`。
2. 在顶栏点击 Comfortable 或 Compact，刷新页面确认选择保持。
3. 载入 **Loan policy fallback**，点击 **Auto Layout**，依次查看 Overview、Focus、Inspect。
4. 将窗口缩到 390px，点击 Menu，确认工作区导航以两列展开。
5. 在 Compose 展开 Readiness 与 Tools；切到 Scenarios，确认重复摘要自动收起。
6. 点击 Matrix 的 Run all，横向滚动并确认 Case 列保持可见、右侧存在继续提示。
7. 切换中文/English，确认同一任务结构、密度和导航状态不变。

## 8. 剩余风险与下一阶段

Stage 5 关闭的是工程可读性和响应式硬门槛，不等于已经获得真实用户的 95 分证明。

剩余工作：

- `styles.css` 仍有 18k 行，需按 surface 渐进拆分并由截图门禁保护；
- 1000 Case / 100 columns 与 100 节点 Graph 仍需持续性能预算；
- Scenario Import 等低频高级工作面需要纳入下一轮完整双语 inventory；
- Stage 6 需由 12 名目标用户执行无协助任务，再判断任务成功率与中英文耗时差；
- E4 需两个真实团队、两个发布周期证明没有因 mock、schema 或 evidence 误解造成错误决策。

因此本轮可以声明“工程体验门禁达到 96/100，剩余工程差距 4%”，不能提前声明“真实用户体验
已经 95 分”。这两个结论必须分开管理。
