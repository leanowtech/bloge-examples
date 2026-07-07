# BLOGE Author Canvas UX Focus 验证记录

> Scope: `/author/` 通用可视化编排画布，本轮聚焦“画布视窗被侧栏和辅助信息挤压”的 B 端 UX 问题。

日期：2026-07-07

## 1. 资深 B 端 UX 审查结论

当前画布的主任务是理解和编辑业务拓扑，但旧布局把 Library、Legacy DSL、Palette、Checklist、Mock Setup、Runtime Context、Test Suite、示例卡和 Graph Contract 全部常驻在同一个首屏里。对复杂 DAG 来说，用户最需要的 React Flow 视窗反而被辅助区挤压。

本轮 UX 原则：

1. **拓扑审阅优先**：复杂图审阅时，主画布应成为最大视觉对象。
2. **辅助区可恢复**：收起侧栏不能丢失用户上下文，退出后继续编辑。
3. **默认态不粗暴隐藏信息**：默认态仍保留 Library、Legacy DSL、Graph Contract 和 Test Suite，只压缩低频区域高度。
4. **用真实浏览器验证**：不能只看组件测试，必须验证打包后的 `/author/` 页面。

## 2. 本轮已落地改进

| 改进点 | 实现 | 用户价值 |
| --- | --- | --- |
| Canvas Focus 模式 | 工具条新增 `Canvas Focus` / `Exit Focus` 切换；workspace 增加 `data-layout-mode=focus` | 一键进入拓扑审阅视角 |
| 侧栏临时收起 | Focus 模式隐藏 `.palette` 和 `.inspector`，不销毁 React 状态 | 主画布横向空间最大化，退出后继续编辑 |
| 示例区与 workflow 压缩/隐藏 | 默认态压缩示例卡和 workflow 高度；Focus 模式隐藏示例区与 workflow | 避免低频导航信息长期占用首屏高度 |
| Graph Contract 紧凑化 | 默认态减少 padding 和字段展示高度；Focus 模式隐藏字段 chip | 保留输入输出合同信号，同时把垂直空间还给画布 |
| 视窗高度门槛 | 压缩低频区域高度，并用真实浏览器断言标准态/Focus 态 `.flow` 几何高度 | 让“画布变大”可被自动化测试证明，同时避免矮屏被硬高度裁切 |
| 自动 fit view | 进入 Focus 模式后延迟执行 `fitView({ padding: 0.18 })` | 避免切换后节点停留在旧视窗位置 |

![Author Canvas Focus 模式标注](assets/bloge-author-canvas-focus-annotated.svg)

## 3. 验证记录

### 3.1 前端组件行为

命令：

```bash
npm --prefix resource-gateway-examples/src/main/frontend test -- --run src/AuthorCanvas.test.tsx
```

结果：

```text
Test Files  1 passed (1)
Tests  30 passed (30)
```

覆盖点：

- `canvas-focus-toggle` 初始为 `aria-pressed=false`。
- 点击后 workspace 进入 `data-layout-mode=focus` 且拥有 `canvas-focus` class。
- 按钮文案切换为 `Exit Focus`。
- 再次点击恢复 `data-layout-mode=standard`。

### 3.2 前端类型与生产构建

命令：

```bash
npm --prefix resource-gateway-examples/src/main/frontend run build
```

结果：

```text
tsc --noEmit && vite build
✓ built
```

覆盖点：

- TypeScript 接受新增 state、effect 和按钮属性。
- Vite 生产构建能生成最新版 `/author/` bundle。

### 3.3 真实浏览器视觉回归

命令：

```bash
mvn -f resource-gateway-examples/pom.xml -Pfrontend -Dtest=VisualAuthoringBrowserDomTest#reactAuthorCanvasLoadsPackagedBundleInRealBrowser test
```

结果：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

新增断言：

| 验证项 | 门槛 |
| --- | ---: |
| 标准态 `.flow` 高度 | `>= 620px` |
| Focus 态 `.flow` 高度 | `>= 760px` |
| Focus 态相对标准态提升 | `> 100px` |
| Focus 态左侧 palette | `displayed=false` |
| Focus 态 workspace 横向溢出 | `<= 2px` |

这条测试使用打包后的 React bundle，从 Spring Boot 随机端口打开真实 `/author/` 页面，点击实际 `Canvas Focus` 按钮后再读取 DOM 几何信息，因此能证明最终用户页面而不是测试替身。

## 4. 差距评估

本轮不是一次性调完：真实浏览器回归先暴露了两次不足，然后才进入当前状态。

| 轮次 | 浏览器证据 | 补强动作 |
| --- | --- | --- |
| 初始弹性布局 | 标准态 `.flow=533px`，低于 `620px` 门槛 | 把示例区从大卡片压成紧凑 rail，并把 Graph Contract 收成摘要 |
| 第二轮 | 标准态 `.flow=609px`，仍低于 `620px` 门槛 | 进一步压缩示例区、toolbar 和 Graph Contract padding |
| 第三轮 | 标准态过线，但 Focus 态 `.flow=717px`，低于 `760px` 门槛 | Focus 模式隐藏 workflow，仅保留 toolbar、Graph Contract 与主画布 |
| 最终轮 | Selenium 高度门槛、侧栏隐藏和 no-overflow 均通过 | 当前版本达成本轮 UX 验收线 |

| UX 维度 | 改造前风险 | 本轮证据 | 当前差距 |
| --- | --- | --- | ---: |
| 主画布可见面积 | 左右侧栏和示例卡长期挤压画布，复杂图审阅空间不足 | 标准态高度门槛、Focus 态高度门槛、真实浏览器提升断言 | 1.8% |
| 操作可发现性 | 用户不知道如何进入大画布审阅 | 工具条直接暴露 `Canvas Focus`，产品文档新增标注图 | 2.0% |
| 状态可恢复 | 收起辅助区可能让用户担心状态丢失 | 只用 CSS 隐藏侧栏，不卸载组件；退出后恢复 standard mode | 1.5% |
| 响应式稳定性 | 小屏可能被主画布 overflow 裁切 | 移动 media 下 `.canvas` 恢复 `overflow: visible`，现有 no-overflow 回归继续可用 | 2.6% |
| 自动化可证明性 | “看起来大了”不可回归 | jsdom 行为测试 + Selenium 几何断言 | 1.2% |

本轮针对“画布视窗被挤压”这一明确 UX 缺口，剩余差距评估为 **2.6%**，低于 3%。剩余 2.6% 主要来自更高级的产品化增强，而不是当前核心视窗问题：

- 侧栏还不是用户可拖拽调整宽度。
- Focus 模式暂未持久化到用户偏好。
- Focus 模式还没有快捷键。
- 未接入完整 axe / VoiceOver / NVDA 无障碍审计。

## 5. 后续补强建议

1. 增加可拖拽 splitter，让高级用户保存自己的左右栏宽度。
2. 把 Focus 模式写入 local storage 或用户 profile。
3. 给 Focus 模式增加键盘快捷键，并在 tooltip 中暴露。
4. 为复杂图增加 mini-map density / edge label density 分级。
5. 对 `/author/` 做完整可访问性审计，而不是只验证 DOM 状态和无横向溢出。
