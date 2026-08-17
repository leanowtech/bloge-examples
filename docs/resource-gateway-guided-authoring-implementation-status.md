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
| P0：Remediation 命令闭环 | `IN_PROGRESS` | 领域合同与 UI Router 正在实施 | 同页聚焦、跨页定位、outcome 与浏览器回归 |
| P0：任务指引骨架 | `IN_PROGRESS` | 七步 Step Contract 正在实施 | 页面投影、Next Best Action、i18n |
| P1：候选资产与 Picker | `IN_PROGRESS` | Provider SPI、候选协议和共享 Combobox 正在实施 | Controller/Capability、Correctness Launcher 和各字段接入 |
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

## 自动化证据

聚焦测试覆盖：

- Web/VS Code exact link 和 query allowlist；
- 非法 revision/fingerprint 拒绝；
- Business Mirror 页面不再生成 Showcase URL；
- Author seed 解析、source drift、dangling topology；
- 真实 AuthorCanvas 组件加载 Business Mirror Graph 并关闭首次对话框；
- 原有 Business Mirror、Author workspace location 回归。

验证命令：

```bash
cd resource-gateway-examples/src/main/frontend
npm test -- --run \
  src/shared/workspace-routing/businessMirrorAuthorLink.test.ts \
  src/author/source/businessMirrorGraphSeed.test.ts \
  src/author/shell/authorWorkspaceLocation.test.ts \
  src/business-mirror/BusinessMirrorWorkspace.test.tsx
npx vitest run src/AuthorCanvas.test.tsx -t "opens an exact Business Mirror source"
npx tsc --noEmit
```

全量 `npm test`、`npm run build`、Maven `clean verify` 和真实浏览器 Golden Path 在每轮集成完成后执行；单个切片不能据此宣称整体目标完成。

