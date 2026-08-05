# Resource Gateway UX Stage 4：双语完整性与术语治理

> 状态：Completed / E2 verified  
> 完成日期：2026-08-05  
> 对应计划：UXA-S4-01 至 UXA-S4-06

## 1. 本阶段解决什么

此前中文界面采用 English-as-key：找不到中文时直接显示英文。这让顶层导航看似双语可用，深层的
Contract、Scenario、Evidence、算子弹层、Library 编辑器和第三方控件却能静默漏翻译。更严重的
问题是服务端异常、协议状态和业务资产名混在同一层，用户无法区分“产品没翻译”与“业务数据本来
就是英文”。

本阶段建立四层治理：

```text
稳定产品命令 / 状态 / blocker
  -> typed message id

迁移期深层界面
  -> strict legacy source inventory

服务端 diagnostic code
  -> localized title + explanation + remediation
  -> original code / message 留在 Technical details

业务资产 / 字段路径 / DSL / payload
  -> 保持原值，不翻译
```

## 2. 用户可感知变化

### 2.1 Author 任务链完整中文化

- Start Dialog、算子面板、画布导航、命令栏、右侧检查器、诊断抽屉均使用同一 locale；
- Decision Table 双击弹层中的 Rules、Config、Data、Contract、Advanced 和表格操作完整本地化；
- 节点卡的类型、契约方向、就绪度和副作用状态已本地化；
- 聚合边标签由 `6 fields / 4 targets` 显示为 `6 个字段 / 4 个目标`；
- ReactFlow 的 Zoom、Fit 和 interaction controls 跟随语言切换，aria-label 与 tooltip 同步。

节点名 `Policy decision`、字段路径 `inputs.score` 和 `operatorRef` 仍保持业务原文。这是有意边界，
不是漏翻译。

### 2.2 Contract 从协议码变成可读状态

有效契约面板以“已声明、推断、已绑定、已观测”解释来源。`EDGE / OPAQUE / UNBOUND /
DECISION_OUTPUT` 分别显示为“传入边 / 不透明 / 未绑定 / 决策输出”，原始协议值保留在技术追踪信息。

### 2.3 Diagnostic 以动作而不是异常为中心

已建立 code-based diagnostic catalog。已知诊断显示本地化标题、影响解释和修复动作；未知 code
显示安全兜底。原始 code、服务端 message 和技术坐标折叠在 Technical details 中，避免英文异常
抢占业务结论。

## 3. 工程实现

### 3.1 Typed message catalog

`src/i18n/messageCatalog.ts` 使用稳定 ID 管理 Author command、blocker 和 lifecycle status。目录在
编译期约束 message ID，并校验中英文占位符一致。

旧的 `t(source)` 在迁移期保留，避免一次性重写大组件。它不再是无门禁通道：深层表面的字面
文案、动态注册项、状态机文案和协议 token 都有自动化 inventory。

### 3.2 Protocol diagnostic catalog

`src/i18n/diagnosticCatalog.ts` 将稳定 diagnostic code 投影为：

```text
title
explanation
remediation
technicalDetail
cataloged
```

产品展示只依赖前三项；调试和支持人员仍能访问后两项。

### 3.3 Deep surface inventory

CI 扫描以下用户高频深层面：

- AuthorCanvas 与 Author Command / Diagnostics；
- Contract Scenario、Schema tree、Assertion、Dependency 和 Matrix；
- Operator / Function / Schema Library builders；
- discovery、sample inference、fixture save 和 effective contract；
- Rehearsal 与 Showcase。

扫描同时拒绝绕过 `t()` 的可见英文 JSX 和英文 accessibility attributes。动态来源另外覆盖：

- node editor registry 的主任务与 Tab；
- readiness state machine 的 headline、reason 和 action；
- operator summary / focus panel 的动态标签与 notice；
- effective-contract 的来源、置信度、绑定状态和 trace kind。

### 3.4 第三方控件适配

ReactFlow Controls 在父组件 effect 之后挂载，单次 `requestAnimationFrame` 会偶发错过按钮。本阶段
改为对画布根节点做 child-list MutationObserver，只在控件挂载时设置本地化 aria-label 和 title；
不监听 attribute，也不执行全局文本替换，因此没有循环更新或误改业务 DOM 的风险。

## 4. 验证证据

### 4.1 自动化

```text
npm run check:i18n
  5 test files / 28 tests green

npx tsc --noEmit
  green

npm test
  72 test files / 541 tests green

npm run build
  production build green / 286 modules transformed

mvn -f resource-gateway-examples/pom.xml clean verify
  5,898 tests / 0 failures / 0 errors / 13 skipped
```

专项测试证明：

- typed catalog 完整且占位符兼容；
- diagnostic 已知 code 与未知 code 都有安全中文表达；
- 深层表面不存在裸英文产品 JSX；
- node editor、readiness 与 effective-contract 动态 token 不会绕过检查；
- Author Command Bar 的中英文命令状态一致。

全量 Maven 验证同时覆盖 Resource Gateway 的 controller、operator、协议与集成行为；构建中仅保留
仓库既有的 BLOGE RC2 POM、Selenium CDP 版本提示和测试预期的 fail-closed 日志，不存在新增失败。

### 4.2 真实浏览器 E2

环境：真实 Resource Gateway 服务、Vite frontend、Chromium、`1280 x 720`。

固定任务：载入 Loan policy fallback，检查 5 nodes / 12 edges，双击 `Policy decision`，依次查看
Rules、Contract 和 Advanced，再切换英文。

观察结果：

| 检查项 | 结果 |
|---|---|
| ReactFlow controls | 中文为“放大 / 缩小 / 适配视图 / 切换交互模式”，英文恢复标准标签 |
| 节点卡 | 产品类型、契约方向、输入输出数量和 readiness 为中文 |
| Edge bundle | `fields / targets` 已本地化，字段路径保持原文 |
| Decision editor | 5 个 Tab 与规则操作完整中文化 |
| Effective Contract | 来源、类型、置信度、状态和 trace kind 均可读 |
| Locale parity | 中英文均保留 5 个 Node Editor Tab；语言切换不改变 Graph 或用户数据 |
| Inspector toggle | 收起 / 展开 aria-label 跟随语言 |

## 5. 使用方式

1. 启动服务并打开 `/author/`；
2. 在全局标题栏使用 `EN / 中文` 分段控件切换；
3. 演示固定中文链接可使用 `/author/?lang=zh-CN`；
4. 选择“载入示例 -> 贷款策略与降级”；
5. 双击任意算子检查规则、数据、契约和高级设置；
6. 打开“测试场景”和“证据”，观察同一状态在深层工作区中的一致表达。

语言偏好写入 localStorage 并同步到 URL，后续导航和刷新保持一致。

## 6. 退出判断与剩余风险

UXA-S4-01 至 UXA-S4-06 已达到 E1/E2 工程退出门槛。Stage 4 复评分为 `92 / 100`。

仍不能宣称真实用户体验达到 95 分，原因不是本地化功能缺失，而是：

- 10px / 11px 字号、按钮层级和触摸目标仍属 Stage 5；
- 390px 移动端角色布局和横向滚动提示尚未完成；
- E3 需要 12 名目标用户验证中文与英文任务成功率差异 `<= 10%`；
- 业务资产自由文本不会自动翻译，企业若需要多语言资产名，应在资产 schema 中提供 locale map。

术语和新增文案规则见
[Resource Gateway 双语术语与界面文案规范](resource-gateway-localization-glossary.md)。
