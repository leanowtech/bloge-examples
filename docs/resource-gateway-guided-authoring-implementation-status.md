# Resource Gateway 引导式创作实施状态

> 对照方案：[引导式正确性与业务镜像产品技术改进方案](resource-gateway-guided-correctness-and-business-mirror-ux-technical-evolution-plan.md)
>
> 本文只记录已经有代码与自动化证据支持的能力。`PLANNED` 不代表已实现。

## 当前状态

| 切片 | 状态 | 已有实现证据 | 尚缺 |
|---|---|---|---|
| 方案与 Draw.io 基线 | `DONE` | 目标任务流、Remediation 闭环、Stage 0-4 计划已版本化 | 用真实用户数据校准指标 |
| P0：Business Mirror -> Author exact link | `IMPLEMENTED` | 认证的 `POST /api/visual/authoring-links:resolve` 校验 exact source、Scope 和 return coordinate；前端只消费结构化 descriptor；失败留在原页并可重试；不存在 Showcase 回退 | 企业目录中的 durable draft authority 扩展 |
| P0：Author legacy Graph source validation | `IMPLEMENTED` | Author 反查 legacy projection并比较 id/revision/fingerprint；源图以只读态打开，结构编辑被锁定；显式“创建工作副本”后 URL 切换 durable `draftId` 并保留 lineage | 企业持久库验证 fork receipt 的审计签名 |
| P0：Remediation 命令闭环 | `IMPLEMENTED` | 21 类已知 gap 有稳定 descriptor；同页/跨页均切换、滚动、聚焦、高亮、打开 Picker、写入 URL 并播报 outcome；跨工作区动作真实导航并携带受控返回坐标；保存后重新读取权威 readiness，阻断消失才返回 `RESOLVED`；未知 gap 显式失败 | 正式部署中的跨系统回传与用户取消样本校准 |
| Stage 0：payload-free guided telemetry | `IMPLEMENTED` | 共享 sink-backed telemetry、严格事件/字段/枚举校验、短 scope hash、时延/结果 bucket；Launcher 搜索与候选打开成功/失败、Business Mirror remediation started/completed 已接入；默认 no-op 不发网络 | 宿主注入正式 sink、服务端聚合与漏斗看板 |
| Stage 4：anti-entropy 协议检查 | `IMPLEMENTED` | 独立测试把 `/api/integration/capabilities` 的四个 guided feature 与真实 controller mapping 对照；运行时未装配时不广告 correctness target/workspace 路由；21 类 gap descriptor 的三组 message id 均有中英文 catalog；Author link 强制 `/author/` Compose 且禁止 Showcase | 将同一检查接入 CI 发布门禁，并扩展到企业 Provider manifest |
| P0：任务指引骨架 | `IMPLEMENTED` | 七步均显示业务问题、Why、输入状态、权威完成状态、Next Best Action 和底部决策；`OPEN_PICKER` 自动展开并聚焦目标控件；中英文与响应式布局完整 | 用真实业务人员任务数据继续压缩首步理解时间 |
| P1：候选资产与 Picker | `IMPLEMENTED` | metadata-only Candidate/Page/Resolve 协议、可组合 Contributor SPI、Graph/Operator/Function 默认 Provider、Correctness Target/Definition 两级 Launcher、Business Mirror 13 类字段、认证 Scope、稳定错误、Capability Probe、共享 Combobox和 5000 候选 P95 门禁已实现 | 真实企业目录与跨区域网络压测 |
| P1：七步可操作化 | `IMPLEMENTED` | 七步任务合同、精确阻断定位、Domain/Taxonomy/Owner/Contract/State/Effect/L1-L3/Scenario/Fidelity/Outcome/Approval 主动筛选与整包保存已接通；保存后由权威 Package readiness 重算结果 | 外部资产“新建提案并回跳”、ANEKE gate feedback |
| P1：正确性三阶段与 Next Action | `IMPLEMENTED` | 顶部任务带明确“先看结论/定义正确性/运行并留证”的问题、状态、缺口、完成标准和主动作；`Required attention` 展示原因、影响、完成标准并跳到 Coverage/Oracle/Runs 等精确专业页；任务带可折叠且偏好仅保存在本地 UI 状态 | 由子编辑器统一上报 dirty 状态后的安全“切换资产”侧栏 |

## Exact Author Link 行为

业务镜像「打开精确编排图」先调用认证的解析协议：

```http
POST /api/visual/authoring-links:resolve
X-Purpose: BUSINESS_MIRROR_AUTHORING
```

请求携带 `sourceGraphRef`、`EDIT_TOPOLOGY` intent 和 allowlisted return coordinate。服务端从认证身份重建 Scope，
重新读取 `BuiltInGraphAssetAuthority` 并逐项比较 id、revision、fingerprint。成功后返回结构化 descriptor，客户端
才投影以下受控坐标：

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
`sessionTenantId` 两个允许透传的入口参数，`returnRoute` 不是任意 URL。解析期间链接不可点击；`403/404/409/503`
会留在当前 Package 并给出可重试错误，不能回退到客户端猜测坐标。

Author 收到坐标后：

1. 读取 `/api/business-mirror/legacy-graphs/{graphName}`；
2. 比较 projection 的 source id、revision、fingerprint；
3. 读取该 Graph 的官方 scenario 与 diagram；
4. 校验 diagram root 和所有 edge endpoint；
5. 投影为 `SOURCE_PREVIEW` GraphDraft 并进入 Compose，只允许查看、选择、缩放和导航；拖动、连线、拖入算子和双击编辑被锁定；
6. 用户点击「创建工作副本」后保存 durable Graph revision，URL 删除 source 参数并切换到 `draftId + revision`；
7. 任一身份或拓扑不一致时返回稳定错误，不猜测 latest。

## 正确性三阶段行为

正确性 Workspace 保留 Overview/Coverage/Cases/Fixtures/Oracle/Runs 六个专业视图，同时增加三阶段任务带：

1. **先看结论**：解释五轴 Verdict 与首要阻断；
2. **定义正确性**：闭合冻结分母、Canonical Case、有效 Fixture 和可执行 Assertion；
3. **运行并留证**：通过 Preflight，运行 exact revision 并保留 current Evidence。

任务带展示服务端投影派生的当前状态、还缺什么、完成标准和唯一主动作。Overview 的「需要处理」现在是动作卡：
每项包含原因、业务影响和完成标准，并按 command 跳到 Coverage、Oracle 或 Runs；刷新类动作直接重新读取 Workspace，
未知 command 安全回到 Overview。阶段浏览只产生 payload-free `GUIDED_STEP_VIEWED`，不会记录 target ID、fingerprint、
Fixture 或业务输入输出。熟悉流程后可折叠任务带，偏好写入浏览器本地 UI 状态，不进入 Workspace、URL、fingerprint
或治理证据。

## 阻断处理行为

「处理首个阻断」和右侧 gap 项不再只是切换 Sheet。当前实现根据 `RemediationDescriptor` 执行以下动作：

1. 用稳定 gap code 解析目标 Sheet、字段路径、语义锚点和所需 capability；
2. 同 Sheet 也会滚动到精确控件并移动键盘焦点，避免视觉 no-op；
3. 跨 Sheet 先切换步骤，再聚焦并高亮对应字段、要求或行动入口；
4. URL 保留 `gapCode` 和 `remediationAnchor`，便于诊断与回归；
5. `aria-live` 播报定位结果；部署无法处理未知 gap 时显式返回失败且不修改数据。

Outcome 由动作的真实后置条件决定：

- 本地定位、聚焦或打开 Picker 后，阻断仍存在时返回 `STILL_BLOCKED`；
- 保存 Package 后重新读取权威 readiness，目标阻断确实消失时才返回 `RESOLVED`；
- `OPEN_AUTHOR`、`OPEN_REHEARSAL`、`OPEN_CORRECTNESS`、`OPEN_GOVERNANCE` 会执行真实导航并返回
  `NAVIGATED`，目标链接携带 allowlist 约束的 `returnRoute`、`returnPackageId`、`returnTask` 和
  `returnAnchor`；
- 未知 gap、缺少 capability 或目标不可达返回 `FAILED`，不修改业务数据；
- `CANCELLED` 已在遥测协议中保留，只有未来出现需要用户确认且被明确取消的动作时才产生。

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

业务镜像的协议身份字段不再要求手填 ID。七个 Sheet 共用受治理引用控件，覆盖 13 类候选：

```text
BUSINESS_DOMAIN / PROBLEM_TAXONOMY / OWNER / PACKAGE_CONTRACT
STATE_MODEL / EFFECT_MODEL / SOLUTION / SERVICE_CARRIER / CHANNEL
SCENARIO_INVENTORY / SCENARIO_PACK / FIDELITY_INVENTORY / OUTCOME_DEFINITION
```

用户按业务名称、负责人、ID 或 Scope 搜索；候选首屏展示 Owner、Scope 和 lifecycle。选择只形成临时意图，
前端随后调用 `reference-candidates:resolve`，只有 `RESOLVED` 才把稳定 ID 或 exact ref 写入 Package Draft。
`DRIFTED/NOT_FOUND/FORBIDDEN` 均失败关闭。任意 Sheet 的绑定变化都会触发“能力包更改尚未保存”，统一通过
optimistic revision 与幂等键保存整个 Package，而不是只比较第一页的 `businessDefinition`。

`SERVICE_CARRIER` 和 `CHANNEL` 是 Picker 查询族，不是 Package kind。权威候选分别返回
`SOP / AGENT / WORKFLOW` 和 `CHANNEL_APPLICATION`；Web、服务端与 VS Code 离线适配器都只把这些具体类型
写入 `BusinessAssetRef`。这条边界避免选择成功后在保存阶段才暴露 kind/layer 不兼容。

候选 `ReferenceScope` 还可能包含 `fullySpecified` 等服务端派生属性。Web 绑定器现在只白名单复制五个企业
Scope 坐标，查询投影中的附加字段不会进入 Package 写模型。真实浏览器回归已验证选择 L3 渠道后保存成功：
Package 从 r2 升至 r3，`CHANNEL_BINDING_MISSING` 消失，阻断数减少 1；该缺陷此前会在服务端严格反序列化时
返回 `400`。

默认 demo 在 `test/staging + gateway.testing.correctness.demo.enabled=true` 时贡献贷款决策业务目录；生产 profile
以及未显式开启 demo 的部署均不会装配。企业可增加零到多个 `ReferenceCandidateContributor`，核心目录优先，
外部 contributor 按稳定 ID 排序并以 exact coordinate 去重。

## 自动化证据

聚焦测试覆盖：

- Web/VS Code exact link 和 query allowlist；
- Link Resolver 的 purpose、Scope、exact ref、drift、not-found、禁止 return URL 与 capability anti-entropy；
- 非法 revision/fingerprint 拒绝；
- Business Mirror 页面不再生成 Showcase URL；
- Author seed 解析、source drift、dangling topology；
- 真实 AuthorCanvas 组件加载 Business Mirror Graph 并关闭首次对话框；
- Author 只读源图、显式工作副本创建以及 URL 从 source coordinate 切换到 durable `draftId`；
- 21 个已知 gap descriptor 完整覆盖、未知 gap 安全降级；
- 当前 Sheet 阻断仍会聚焦/高亮，跨 Sheet 阻断会定位到精确要求；
- Business Mirror 候选经过 exact resolve 后才写入，漂移时草稿保持不变；
- 非第一页的 Scenario Pack 绑定也会触发整包 dirty 状态并进入保存请求；
- demo Contributor 仅在显式 test/staging 配置中出现，且提供 13 类 metadata-only 候选；
- 5000 候选、100 条有界页的服务端查询 P95 门禁；
- VS Code 离线目录的 13 类搜索、分页、query fingerprint、exact resolve、漂移和 metadata-only 约束；
- `/api/integration/capabilities` 的 `referenceCandidateApi`、`correctnessTargetCatalogApi`、
  `guidedWorkspaceLauncher`、`businessMirrorGuidedRemediation` 与真实 controller mapping 的 anti-entropy 对照；
- 所有已知 Business Mirror blocker 的 descriptor/message catalog 双语覆盖，以及 Author Compose 深链不回退
  `/showcase/`；
- 原有 Business Mirror、Author workspace location 回归；
- 正确性三阶段状态、可执行 Next Action、指引折叠偏好、中文文案和 payload-free 阶段遥测；
- Picker 保存后重新读取权威 readiness，分别覆盖 `RESOLVED` 与 `STILL_BLOCKED`；
- Author、Rehearsal、Correctness、Governance 跨工作区动作产生 `NAVIGATED`，且只携带受控返回坐标。
- `visual` 内核不依赖网关实现：Spring Controller、Resource Gateway 目录 Provider 和 Business Mirror Link Resolver
  均位于 `visualadapter`；`VisualRuntimeBoundaryTest` 持续阻止适配器依赖回流到通用画布内核。

最新真实浏览器证据：

- 正确性 Launcher 通过业务名称发现 Graph，唯一 Definition 自动绑定，全程无需手填 fingerprint；
- `loanDecisionPolicy` 精确链接进入 `/author/` Compose，并加载 3 个节点、2 条边，未出现 Showcase 回退；
- Business Mirror 同 Sheet 的 Owner 阻断自动聚焦并打开 Picker；选择后保存，Package 从 r1 升到 r2，
  `ACCOUNTABLE_OWNER_MISSING` 被权威 readiness 判为已解决，阻断数从 18 降到 17；
- 正确性三阶段指引可折叠，刷新后仍保留个人偏好；首要动作能进入 Coverage，而不是停留在总览；
- 默认桌面视口与 390×844 均无根页面横向溢出，移动端 `document.scrollWidth=390`；
- 全部 Golden Path 结束后浏览器控制台 `warning/error` 均为 0；
- 截图见 [正确性入口](assets/resource-gateway-guided-correctness-launcher-zh.png)、
  [正确性总览](assets/resource-gateway-guided-correctness-overview-zh.png)、
  [业务镜像 Picker](assets/resource-gateway-guided-business-mirror-picker-zh.png) 和
  [精确 DAG](assets/resource-gateway-guided-author-exact-graph-zh.png)，移动端任务壳见
  [390×844 业务镜像](assets/resource-gateway-guided-business-mirror-mobile-zh.png)。

截至 2026-08-17 的自动化基线：

- Web 前端 126 个测试文件、913 个测试全部通过；
- VS Code 扩展 20 个测试全部通过；
- `npm run build` 通过 i18n、UX、host、TypeScript、Vite 与 bundle budget 共 102 项构建门禁；
- Maven 聚焦回归已覆盖 Link Resolver、Candidate API、Capability anti-entropy、隔离函数 worker 和真实数据库
  observation journal；
- `mvn -f resource-gateway-examples/pom.xml clean verify` 最终通过：共执行 6423 项测试，失败 0、错误 0、
  按环境条件跳过 13 项，并成功生成可运行 JAR。

## 最终差距评估

按本方案 Definition of Done 对“仓库内可控制的产品、协议、测试、文档与浏览器行为”逐项核验，当前实现完成度为
**97.6%**，剩余差距 **2.4%**，已低于 3% 收口阈值。剩余项不是继续在 demo 中补假 authority 可以解决：

| 剩余占比 | 外部条件 | 根因与后续验收 |
|---:|---|---|
| 0.8% | 外部资产“创建提案并回跳自动绑定” | 需要企业 Contract/Scenario/Owner authority 提供草稿、审批、回执与权限协议；应以 Provider 合同测试和真实目录联调验收 |
| 0.7% | 正确性工作区内安全切换资产 | Cases/Fixtures/Oracle 子编辑器尚需统一上报 dirty state，才能提供保存、放弃、取消三分支；不得由父页面猜测 |
| 0.5% | ANEKE gate feedback 与企业 Provider | 需要真实 gate、Owner/Taxonomy/Asset catalog 和 workload identity；仓库已经提供 capability、SPI 与失败关闭边界 |
| 0.4% | 真实辅助技术、跨区域弱网与首次用户研究 | 需要 VoiceOver/NVDA、客户网络和 5 至 8 名业务用户；仓库自动化已覆盖键盘语义、5000 候选有界查询和移动端布局 |

因此，仓库内的核心路径已经可以交付演示与进入部署联调；上述 2.4% 必须作为客户环境验收计划继续管理，不能用
本地静态数据宣称已经完成。

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

# Stage 4 anti-entropy
mvn -q -Dtest=GuidedProtocolAntiEntropyTest,CorrectnessAuthoringCapabilityTest test
npm test -- --run src/business-mirror/businessMirrorAntiEntropy.test.ts
```

全量 `npm test`、`npm run build`、Maven `clean verify` 和真实浏览器 Golden Path 在每轮集成完成后执行；单个切片不能据此宣称整体目标完成。
