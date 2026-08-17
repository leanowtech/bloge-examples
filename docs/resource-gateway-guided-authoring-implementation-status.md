# Resource Gateway 引导式创作实施状态

> 对照方案：[引导式正确性与业务镜像产品技术改进方案](resource-gateway-guided-correctness-and-business-mirror-ux-technical-evolution-plan.md)
>
> 本文只记录已经有代码与自动化证据支持的能力。`PLANNED` 不代表已实现。

## 当前状态

| 切片 | 状态 | 已有实现证据 | 尚缺 |
|---|---|---|---|
| 方案与 Draw.io 基线 | `DONE` | 目标任务流、Remediation 闭环、Stage 0-4 计划已版本化 | 用真实用户数据校准指标 |
| P0：Business Mirror -> Author exact link | `IMPLEMENTED` | `businessMirrorAuthorHref` 生成 allowlisted exact source/return coordinate；Business Mirror 不再引用 Showcase | 服务端 Link Resolver 与 durable fork receipt |
| P0：Author legacy Graph source validation | `IMPLEMENTED` | Author 反查 legacy projection，比较 id/revision/fingerprint，加载官方 scenario/diagram；漂移失败关闭 | 只读态视觉提示和显式“创建工作副本”命令 |
| P0：Remediation 命令闭环 | `IMPLEMENTED` | 21 类已知 gap 有稳定 descriptor；同页/跨页均切换、滚动、聚焦、高亮、写入 URL 并播报 outcome；未知 gap 显式失败 | Picker 自动打开、跨工作区 `NAVIGATED`、权威重算后的 `RESOLVED` |
| P0：任务指引骨架 | `IMPLEMENTED` | 七步均显示业务问题、Why、输入状态、权威完成状态、Next Best Action 和底部决策；中英文与响应式布局完整 | Picker 接入后把 `OPEN_PICKER` 动作从定位升级为自动展开 |
| P1：候选资产与 Picker | `PARTIAL` | metadata-only Candidate/Page/Resolve 协议、Graph/Operator/Function 默认 Provider、Correctness Target/Definition 两级目录、认证 Scope、稳定错误、Capability Probe 和共享 Combobox 已实现 | Correctness Launcher 与业务镜像字段接入、5000 候选压测 |
| P1：七步可操作化 | `PLANNED` | 目标控件与完成标准已在方案冻结 | Contract/Owner/Scenario/Fidelity 等目录适配与创建回跳 |

## Exact Author Link 行为

业务镜像「打开精确编排图」当前生成以下受控坐标：

```text
/author/?authorWorkspace=v2&authorMode=compose
  &sourceKind=BUSINESS_MIRROR_LEGACY_GRAPH
  &sourceGraphName={graphName}
  &sourceId={sourceRef.id}
  &sourceRevision={sourceRef.revision}
  &sourceFingerprint={sourceRef.fingerprint}
  &returnRoute=business-mirror
  &returnPackageId={packageId}
  &returnTask=capabilities
  &returnAnchor=graph:{sourceRef.id}
```

Web 使用 `/author/`；VS Code Webview 使用 `workspaceRoute=author`，其余 exact identity 相同。只保留 `lang` 和
`sessionTenantId` 两个允许透传的入口参数，`returnRoute` 不是任意 URL。

Author 收到坐标后：

1. 读取 `/api/business-mirror/legacy-graphs/{graphName}`；
2. 比较 projection 的 source id、revision、fingerprint；
3. 读取该 Graph 的官方 scenario 与 diagram；
4. 校验 diagram root 和所有 edge endpoint；
5. 投影为 `SOURCE_PREVIEW` GraphDraft 并进入 Compose；
6. 任一身份或拓扑不一致时返回稳定错误，不猜测 latest。

## 阻断处理行为

「处理首个阻断」和右侧 gap 项不再只是切换 Sheet。当前实现根据 `RemediationDescriptor` 执行以下动作：

1. 用稳定 gap code 解析目标 Sheet、字段路径、语义锚点和所需 capability；
2. 同 Sheet 也会滚动到精确控件并移动键盘焦点，避免视觉 no-op；
3. 跨 Sheet 先切换步骤，再聚焦并高亮对应字段、要求或行动入口；
4. URL 保留 `gapCode` 和 `remediationAnchor`，便于诊断与回归；
5. `aria-live` 播报定位结果；部署无法处理未知 gap 时显式返回失败且不修改数据。

当前 outcome 为 `STILL_BLOCKED` 或 `FAILED`。选择器接入后再增加 `CANCELLED`，跨工作区动作增加
`NAVIGATED`，保存并读取权威 readiness 后才允许显示 `RESOLVED`。

## 七步任务壳行为

每个 Sheet 现在共享 `GuidedTaskShell`，不再直接把领域对象和协议字段堆给首次用户：

- 顶部先说明“本步回答什么问题”和“为什么重要”，并标明当前步骤、权威状态和来源预览状态；
- 输入清单分别显示“已绑定”“当前快照未绑定”“需在本步检查”和“构成权威阻断”，不再用“没有 gap”
  猜测输入已经存在；
- 下一最佳动作显示待补输入、业务影响和精确控件入口，协议 code/field path 收进技术详情；
- 底部根据权威 gap 决定“继续补齐本步”或“进入下一步”，仍允许专家从左侧直接跳步；
- Step Contract、具体 Sheet 和 Remediation Descriptor 分层，业务表单没有复制导航与完成规则。

## 引用候选协议

候选目录已形成独立的发现与解析边界：

1. `GET /api/visual/reference-candidates` 统一搜索 Graph、Operator 和 built-in function；
2. `GET /api/visual/correctness-targets` 从 Correctness Definition head 投影可验证 Target；
3. `GET /api/visual/correctness-targets/{kind}/{id}/definitions` 返回零个、唯一或歧义 Definition；
4. `POST /api/visual/reference-candidates:resolve` 在绑定前重新读取 authority；
5. 搜索 Scope 只从认证上下文生成，query 最长 200 字符，page limit 最大 100；
6. cursor 绑定 query fingerprint 与 catalog generation，目录变化后失败关闭；
7. `DRIFTED` 返回当前权威候选供比较，`NOT_FOUND/FORBIDDEN` 不泄漏候选元数据。

前端共享 `AsyncReferenceCombobox` 已与 Java `bloge.referenceCandidate.v1` 对齐，覆盖防抖、取消、分页、
去重、键盘、禁用候选、空结果、目录不可用和重试。使用方式与错误恢复见
[引用候选 API 指南](resource-gateway-reference-candidate-api-guide.md)。

正确性工作台入口已从原始坐标表单升级为 `CorrectnessWorkspaceLauncher`：用户先按 Graph、Operator 或
built-in function 筛选业务目标，再由系统主动查询绑定的 Correctness Definition。唯一 Definition 自动选择，
多个 Definition 必须显式选择，零结果会阻断无效打开；`targetId + fingerprint + definitionId` 只在选中后作为
精确坐标提交。旧 deep link 不变，原始坐标表单收进「高级精确坐标」，目录能力未声明时也能明确降级。
Capability Probe 仅在 correctness Workspace 与 Target Catalog 真实装配时声明
`guidedWorkspaceLauncher=true`。

## 自动化证据

聚焦测试覆盖：

- Web/VS Code exact link 和 query allowlist；
- 非法 revision/fingerprint 拒绝；
- Business Mirror 页面不再生成 Showcase URL；
- Author seed 解析、source drift、dangling topology；
- 真实 AuthorCanvas 组件加载 Business Mirror Graph 并关闭首次对话框；
- 21 个已知 gap descriptor 完整覆盖、未知 gap 安全降级；
- 当前 Sheet 阻断仍会聚焦/高亮，跨 Sheet 阻断会定位到精确要求；
- 原有 Business Mirror、Author workspace location 回归。

验证命令：

```bash
cd resource-gateway-examples/src/main/frontend
npm test -- --run \
  src/shared/workspace-routing/businessMirrorAuthorLink.test.ts \
  src/author/source/businessMirrorGraphSeed.test.ts \
  src/author/shell/authorWorkspaceLocation.test.ts \
  src/business-mirror/guidance.test.ts \
  src/business-mirror/BusinessMirrorWorkspace.test.tsx
npx vitest run src/AuthorCanvas.test.tsx -t "opens an exact Business Mirror source"
npx tsc --noEmit
```

全量 `npm test`、`npm run build`、Maven `clean verify` 和真实浏览器 Golden Path 在每轮集成完成后执行；单个切片不能据此宣称整体目标完成。
