# BLOGE VSCode 插件轻量化可视化编排方案

状态：Draft v0.1
日期：2026-07-10

目标读者：BLOGE visual canvas、resource-gateway、Studio/LSP、业务研发团队、平台工程团队

## 1. 核心判断

当前 `/author/` 画布已经证明了 BLOGE 可视化编排、DSL 导入、schema 增强、mock simulation、test suite 和 rewrite gate 的产品价值。但它的运行形态仍然偏重：用户需要先启动 Resource Gateway Spring Boot 服务，再打开浏览器页面。

如果目标用户是已经在 IDE 里维护 `.bloge`、Java operator、schema、测试样例的开发者，那么更轻的入口应该是 VSCode 插件：

```text
打开业务仓库
  -> 点击 .bloge 文件旁边的 Visualize
    -> 不启动服务端，先本地渲染 topology
      -> 自动发现 workspace 内 schema
        -> 本地 mock simulate / table test
          -> 可选调用 JVM 或远程服务做权威校验
```

这不是把 Spring Boot 搬进 VSCode，也不是把完整服务端治理删掉。正确边界是：

| 形态 | 第一职责 | 是否必须启动服务端 |
| --- | --- | --- |
| VSCode 插件 | IDE 内理解、编辑、迁移、mock 验证、文件协作 | 否 |
| Resource Gateway / Studio 服务端 | 权威 schema gate、发布治理、真实运行、审计、租户权限 | 是，作为可选增强 |

插件版要兑现的是“低门槛理解和修改”，服务端版要兑现的是“权威治理和生产发布”。两者共享同一套 `GraphDraft`、operator library、diagnostic 和 simulation contract。

## 2. 产品定位

VSCode 插件不是另一个演示页面，而是 BLOGE 开发者的本地 authoring companion。

它应该覆盖 5 个高频场景：

| 场景 | 用户问题 | 插件能力 |
| --- | --- | --- |
| 看懂存量 DSL | 这份 `.bloge` 到底有哪些节点、依赖和输出 | 右键 `.bloge` -> Open Visual Canvas，本地 topology-first 渲染 |
| schema 渐进增强 | 我还没有完整 operator schema，能不能先看图 | 无 schema 仍渲染 topology-only；发现 schema 后增强端口、连线、补全 |
| 本地试跑 | 我不想启动服务，只想验证表格样例和 mock 逻辑 | 本地 mock simulation、节点 fixture、graph test suite |
| 文件协作 | 编辑后的 draft 怎么回到源码或资产文件 | 导出 `GraphDraft`、生成 patch 候选、打开 diff，不自动覆盖 |
| 权威校验 | 准备发布前还要相信服务端结论 | 一键切到 JVM assisted 或 remote authoritative mode |

## 3. 用户体验

### 3.1 打开方式

插件提供这些入口：

| VSCode 命令 | 行为 |
| --- | --- |
| `BLOGE: Open Visual Canvas` | 打开当前 workspace 的 authoring webview |
| `BLOGE: Visualize Current DSL` | 读取当前 `.bloge` 文件，直接生成 topology canvas |
| `BLOGE: Import Operator Library` | 导入 `bloge.visualOperatorLibrary.v1` JSON/YAML |
| `BLOGE: Scan Workspace Schemas` | 扫描 workspace 中的 visual library / capability catalog / generated schema |
| `BLOGE: Run Graph Test Suite` | 对当前 graph draft 运行本地 mock table tests |
| `BLOGE: Export GraphDraft` | 导出 `bloge.visualGraphDraft.v1` 到文件或剪贴板 |
| `BLOGE: Check Rewrite With BLOGE Runtime` | 可选调用 JVM/远程服务做 round-trip / rewrite gate |

右键菜单：

```text
.bloge file
  BLOGE: Visualize
  BLOGE: Visualize Topology Only
  BLOGE: Check Rewrite
  BLOGE: Export Visual Draft
```

编辑器布局：

```text
VSCode Explorer / Source Editor
  |
  +-- BLOGE Visual Canvas Webview
        |- left: workspace catalog / DSL source map
        |- center: React Flow canvas
        |- right: Graph Contract / Context / Test Suite / Diagnostics
```

当用户点击画布节点或 source map 行时，插件在源 `.bloge` 文件中定位对应行列。用户从源码编辑后，webview 可提示 “DSL changed, refresh topology”。

### 3.2 默认心智模型

插件版默认给用户 3 个清晰状态：

| 状态 | 含义 | UI 提示 |
| --- | --- | --- |
| Topology Only | 只从 DSL 提取节点、边、ctx 引用、输出依赖和函数调用 | 灰色端口、warning diagnostics、允许看图和注释 |
| Schema Enhanced | 已找到 operator/function schema，可做端口展示、连接候选和样例生成 | 蓝色端口、schema summary、mock sample |
| Runtime Checked | 已通过 JVM 或远程权威校验 | 绿色 gate、round-trip / rewrite / compile evidence |

这三个状态非常重要：它让“没有 schema 也能看图”和“发布前仍需权威校验”同时成立。

## 4. 总体架构

```text
VSCode Extension Host
  |- workspace scanner
  |- local schema index
  |- topology-only DSL scanner
  |- local draft validator
  |- local mock simulation engine
  |- optional JVM assisted runner
  |- optional remote service client
  |
  | postMessage transport
  v
Webview React Canvas
  |- reused AuthorCanvas UI
  |- React Flow DAG editor
  |- Graph Contract / Context / Test Suite panels
  |- BlogeApiTransport adapter
```

关键设计点：

1. Webview 继续复用现有 React Flow 画布和 TypeScript wire contracts。
2. Webview 不直接访问本地文件系统，也不启动端口服务。
3. Webview 通过 `BlogeApiTransport` 把原本的 API 请求转成 VSCode `postMessage`。
4. Extension Host 收到请求后，按当前 runtime mode 选择本地实现、JVM 一次性进程或远程服务。
5. 所有返回仍保持现有 `OperatorCatalogResponse`、`DslVisualProjection`、`VisualValidationResult`、`SimulationResponse` 等合同。

本轮代码已经先落下第一块地基：`resource-gateway-examples/src/main/frontend/src/api.ts` 增加了可替换的 `BlogeApiTransport`。浏览器 demo 默认仍走 `fetch`，VSCode Webview 后续可以安装 postMessage-backed transport。

### 4.1 Library Authoring 共享内核

Resource Gateway 现在还提供：

- `docs/schemas/bloge-visual-library-authoring-v1.schema.json`
- `bloge.compactType.v1` 与 `bloge.functionSignature.v1`
- 20 组 `src/test/resources/visual-authoring/golden-vectors.yaml`
- 服务端权威 `/admin/visual-operator-library-authoring/preview`
- `/signature/parse` 与 `/catalogs`

VSCode local compiler 必须消费同一机器 Schema、grammar version 和 golden vectors。它只能返回 `LOCAL_PREVIEW`；准备进入 registry 时，插件调用远端 preview 并展示 local/remote diff，不能把本地结果改名成权威结果。当前服务端 capability 明确声明 `statelessPreview=true`、`sampleInference=true`、`draftLifecycle=true`、`etagConcurrency=true` 和 `previewFencedCommit=true`。插件可以调用远端 multi-sample inference，但仍须把返回值标记为 `OBSERVED`，展示 confirmation queue 后才允许写入 declared contract。

## 5. 运行模式

### 5.1 Offline Topology Mode

默认模式，不依赖 Java、不依赖 Maven、不依赖服务端。

能力：

- 从 `.bloge` 文本中提取 graph 名称、graph input/output 声明、node/operatorRef、上游输出引用、ctx 引用、显式 edge、transform/decision table/function 调用。
- 对未知 operator 生成 synthetic operator definition。
- 输出 `DslVisualProjection`，其中 `draft.visualLayout.import.projectionMode=topology-only`。
- 缺失 operator/function schema 只出 warning，不阻止渲染。
- 支持 source map 到行列的 best-effort 定位。

限制：

- 不保证覆盖全部 DSL 语法。
- 不做 semantic round-trip。
- 不允许自动覆盖源 `.bloge`。

这层的产品价值是“先看懂”，不是“证明可发布”。

### 5.2 Workspace Schema Mode

插件扫描 workspace 内可识别的 schema 文件：

```text
**/bloge-visual-operator-library*.json
**/bloge-visual-operator-library*.yaml
**/bloge-capability-catalog*.json
**/target/bloge-capability-catalog.json
**/.bloge/operator-library.json
```

处理规则：

| 输入 | 行为 |
| --- | --- |
| `bloge.visualOperatorLibrary.v1` | 直接进入 local schema index |
| `bloge.capabilityCatalog.v1` | 本地 adapter 投影成 visual library；无法投影字段标记 opaque |
| JSON/YAML 解析失败 | 生成 workspace diagnostic |
| 同名 operatorRef 冲突 | 按 workspace profile 优先级或提示用户选择 |

增强后可以提供：

- palette / search / tag filter
- port schema summary
- ctx 变量样例生成
- connection candidates
- table test fixture sample
- missing schema diagnostic 降级

### 5.3 Local Mock Simulation Mode

本地模拟不是执行真实 operator，而是复用现有画布 mock simulation 的安全理念：

| 节点类型 | 本地行为 |
| --- | --- |
| 有 fixture 的节点 | 返回 fixture output，并可断言 expected input |
| schema-backed 普通 operator | 根据 output schema 生成 deterministic sample |
| transform / decision table | 第一阶段可 mock；第二阶段实现 TypeScript 子集执行 |
| unknown operator | 返回 opaque sample，并提示补 schema |
| remote/http/native operator | 永不真实调用，默认 mock |

本地 simulation 结果仍返回 `SimulationResponse`：

```json
{
  "validated": true,
  "compiled": false,
  "success": true,
  "graphName": "loanApproval",
  "outputNode": "finalResponse",
  "output": {},
  "results": {},
  "statusMap": {},
  "mockedNodeIds": [],
  "realNodeIds": [],
  "terminalOutputConforms": true,
  "diagnostics": [],
  "errors": [],
  "generatedDsl": ""
}
```

`compiled=false` 是有意设计：它告诉用户这是 IDE 本地 mock 证据，不是 BLOGE runtime compile/run 证据。

### 5.4 JVM Assisted Mode

当用户安装了 JDK 并且 workspace 能解析 BLOGE 依赖时，插件可以启动一次性 JVM 子进程，而不是启动 Spring Boot 服务。

适合放到 JVM assisted mode 的能力：

- 官方 `DslCompiler.parseAst()` 精确解析。
- GraphDraft -> DSL codegen。
- semantic round-trip / rewrite gate。
- BLOGE compile diagnostics。
- framework capability catalog adapter 如果 TypeScript adapter 无法覆盖。

这层仍然不打开端口，不常驻服务。它像 `javac` 或测试命令一样按需执行，并把 JSON 结果返回给 extension host。

### 5.5 Remote Authoritative Mode

当团队需要生产级结论时，插件可连接已有 Resource Gateway / Studio 服务端：

```json
{
  "bloge.visual.remoteBaseUrl": "http://localhost:8080",
  "bloge.visual.runtimeMode": "remote"
}
```

远程模式负责：

- 服务端权威 validation。
- schema/policy/lowering gate。
- round-trip / rewrite gate。
- governed draft commit。
- publication/export。
- runtime binding readiness。

插件应该明确显示 “Remote Checked”，避免用户把本地 mock 结果误认为发布证据。

## 6. API 路由映射

VSCode 插件不必重写画布 UI。它只需要在 extension host 里实现与现有 API 等价的本地路由。

| 现有 API | VSCode 本地实现 |
| --- | --- |
| `GET /api/visual/operators` | 从 local schema index 返回 operator/function catalog |
| `POST /admin/visual-operator-libraries/validate-text` | 本地 JSON/YAML 解析 + visual library schema 轻校验 |
| `POST /admin/visual-operator-libraries/import-text` | 写入 extension workspace state 或 `.bloge/visual-libraries` |
| `POST /admin/visual-operator-libraries/from-capability-catalog-text` | capability catalog -> visual library adapter |
| `POST /api/visual/dsl-imports/preview` | topology scanner；JVM mode 下切换为官方 AST projector |
| `POST /api/visual/dsl-imports/rewrite-gate` | offline 返回 block；JVM/remote 才允许 rewrite evidence |
| `POST /api/visual/drafts/validate` | 本地结构校验 + schema 轻校验 |
| `POST /api/visual/connections/check` | 有 schema 时做兼容性判断；无 schema 时 accepted with warning |
| `POST /api/visual/connections/candidates` | 从当前 draft + schema index 推导候选 |
| `POST /api/visual/graphs/simulate` | 本地 mock simulation；remote mode 可转发 |
| `POST /api/visual/dsl-imports/commit` | 插件内保存 draft 文件；remote mode 才 governed commit |

Webview transport envelope 可以采用轻量 JSON-RPC 风格：

```json
{
  "id": "req-42",
  "method": "POST /api/visual/dsl-imports/preview",
  "body": {
    "sourceId": "loan-approval.bloge",
    "dsl": "graph loanApproval { ... }",
    "inlineLibraries": []
  }
}
```

响应保持 HTTP-like 结构，便于复用当前 `api.ts`：

```json
{
  "id": "req-42",
  "status": 200,
  "body": {
    "schemaVersion": "bloge.dslVisualProjection.v1",
    "sourceId": "loan-approval.bloge",
    "draft": {},
    "diagnostics": []
  }
}
```

## 7. 数据与文件模型

建议 workspace 里支持一个轻量配置文件：

```json
{
  "schemaVersion": "bloge.vscodeWorkspaceProfile.v1",
  "runtimeMode": "offline",
  "schemaSearchGlobs": [
    "**/bloge-visual-operator-library*.json",
    "**/target/bloge-capability-catalog.json"
  ],
  "draftDirectory": ".bloge/visual-drafts",
  "testSuiteDirectory": ".bloge/test-suites",
  "remoteBaseUrl": ""
}
```

默认文件布局：

```text
.bloge/
  visual-libraries/
    risk-policy.json
  visual-drafts/
    loan-approval.graphdraft.json
  test-suites/
    loan-approval.tests.json
  vscode-profile.json
src/main/resources/bloge/
  loan-approval.bloge
```

原则：

1. 插件自动生成的资产必须可版本化。
2. 不自动覆盖 `.bloge` 源文件。
3. 源码回写必须通过 preview diff / rewrite gate / 用户确认。
4. 本地 state 只保存 UI 偏好，不保存核心业务语义。

## 8. 安全边界

| 风险 | 约束 |
| --- | --- |
| 执行任意业务代码 | 插件默认不执行真实 operator |
| SSRF / 内网请求 | local mock mode 不发起 HTTP/resource 调用 |
| 源文件误覆盖 | rewrite 只能打开 diff 或生成 patch，不能静默写源文件 |
| Webview XSS | CSP 禁止 inline script，资源 URI 使用 VSCode webview API |
| 不可信 workspace | VSCode workspace trust 未开启时只允许只读 topology preview |
| schema 误导 | topology-only / schema-enhanced / runtime-checked 三态必须显式展示 |

## 9. 代码演进方案

### Phase 0：抽出 Webview runtime 接口

已开始落地：

- `api.ts` 增加 `BlogeApiTransport`、`setBlogeApiTransport()`、`resetBlogeApiTransport()`。
- 默认浏览器 demo 继续用 fetch。
- 测试覆盖 custom transport 能接管 `/api/visual/operators`。

下一步：

- 将 AuthorCanvas 的启动参数抽成 `AuthorCanvasRuntimeProfile`。
- 给 VSCode webview 提供 `createVsCodeApiTransport(acquireVsCodeApi())`。
- 避免 Webview 代码直接读取 `window.location` 或假设 HTTP base URL。

### Phase 1：最小可用 VSCode 插件

目标：不启动服务端，能打开 `.bloge` 并看图。

建议目录：

```text
tooling/bloge-vscode-authoring/
  package.json
  tsconfig.json
  src/
    extension.ts
    webviewPanel.ts
    transportRouter.ts
    runtime/
      workspaceSchemaIndex.ts
      topologyDslScanner.ts
      localDraftValidator.ts
      localMockSimulator.ts
  webview/
    dist/  # 复用 resource-gateway frontend build 产物
```

验收标准：

- `BLOGE: Visualize Current DSL` 可打开 webview。
- 无 schema 时可生成 topology-only draft。
- 导入 visual library 后 palette/port summary 能增强。
- Graph Contract、Context、Test Suite 能在本地工作。
- 本地 mock simulation 返回 `compiled=false` 的 `SimulationResponse`。

### Phase 2：JVM assisted 精确能力

目标：保留轻量入口，同时拿到官方 DSL 语义。

能力：

- 调用官方 BLOGE parser/compiler。
- 返回精确 source map。
- 支持 GraphDraft -> DSL codegen。
- 支持 semantic round-trip。
- 支持 rewrite gate。

实现方式：

```text
VSCode extension host
  -> spawn java process
    -> bloge-visual-cli parse/project/check-rewrite
      -> JSON stdout
```

这需要主 BLOGE 或 examples repo 提供一个稳定 CLI，不建议让插件手拼 Maven classpath。

### Phase 3：远程权威治理

目标：IDE 内直接接入团队的 Studio/resource-gateway。

能力：

- 远程 catalog。
- governed draft commit。
- publication bundle。
- runtime binding readiness。
- audit evidence。
- 团队共享 draft/history。

## 10. 与现有服务端方案的关系

| 能力 | VSCode Offline | VSCode JVM Assisted | Resource Gateway / Studio |
| --- | --- | --- | --- |
| 打开 DSL 看拓扑 | 强 | 强 | 强 |
| 无 schema topology-only | 强 | 强 | 强 |
| operator schema 增强 | 中 | 强 | 强 |
| 连接候选 | 中 | 强 | 强 |
| 本地 mock simulation | 强 | 强 | 强 |
| 真实 BLOGE compile | 无 | 强 | 强 |
| rewrite gate | 无 | 强 | 强 |
| governed draft repository | 弱，本地文件 | 弱，本地文件 | 强 |
| 发布治理 | 无 | 无 | 强 |
| 运行时绑定和审计 | 无 | 无 | 强 |

因此推荐定位是：

```text
VSCode 插件 = 入口更轻的 authoring surface
Resource Gateway / Studio = 更强的 authoritative control plane
```

## 11. 产品取舍

### 11.1 为什么不要求合法算子 schema

因为可视化的第一价值是理解拓扑，而不是立刻证明每个端口 100% 类型正确。没有 schema 时，插件仍可从 DSL 信息推断：

- 使用了哪些 operatorRef。
- 哪些节点依赖 ctx。
- 哪些节点读取上游输出。
- 哪些 transform/decision table/function 参与了输出。
- graph input/output 大致是什么。
- 整体依赖结构是什么。

schema 的价值是增强，不是入场券。

### 11.2 为什么不默认启动 JVM

因为用户想要轻量入口。默认启动 JVM 会把插件体验又拖回“先准备环境”。但官方 parser/compiler 又是生产级准确性的关键，所以 JVM assisted 应该是显式增强：

```text
Need exact rewrite evidence? Click "Check With BLOGE Runtime".
Need just understand topology? Stay offline.
```

### 11.3 为什么不在插件里真实执行 operator

IDE 插件不应该拥有业务系统运行时的权限、网络、密钥和隔离能力。真实执行属于服务端或受控 runner。插件只做 mock、fixture、schema sample、纯表达式子集。

## 12. 当前代码差距

| 差距 | 当前状态 | 推荐补齐 |
| --- | --- | --- |
| API transport 可替换 | 已补 `BlogeApiTransport` 与版本化 VS Code request/response bridge | 后续只扩充离线路由，不复制 UI |
| Webview 打包为插件资产 | 已落地 `vscode-extension/scripts/prepare-webview.mjs` | 发布前增加 VSIX 签名与供应链清单 |
| Reference extension host | 已落地唯一面板、CSP、deep link、受限 fetch 与命令 | 补 Windows/Linux/Remote 宿主矩阵 |
| 加密 recovery | 已落地 AES-256-GCM、SecretStorage key、scope/AAD 与篡改拒绝 | 用真实用户执行 X/kill/timeout 故障矩阵 |
| 离线示例 catalog | 已内置三套示例所需 operator 与 built-in function | 从工作区 schema index 动态合并，不覆盖用户定义 |
| 本地 topology scanner | 未落地 | TypeScript best-effort scanner |
| 本地 schema index | 未落地 | workspace glob + JSON/YAML parser |
| 本地 mock simulator | 未落地 | 从现有 simulation contract 反推 TS mock engine |
| JVM assisted CLI | 未落地 | 主 BLOGE 提供 `bloge-visual-cli` |
| VSCode source map 跳转 | 未落地 | extension host 使用 `vscode.window.showTextDocument` |
| 文件 diff / patch | 未落地 | 使用 VSCode diff editor，不静默覆盖 |

## 13. 推荐下一步

第一条垂直切片已经完成：当前 React 画布以 production 资源进入真实 VS Code WebView，postMessage transport、
离线 catalog、加密恢复、唯一面板和可选远端代理均可运行；干净 profile 重启可恢复 5 节点 / 12 边，
不需要 Spring Boot。实现与截图见
[VS Code 宿主集成与实机体验审阅](resource-gateway-ux-round3-s5-vscode-host-integration.md)。

下一条垂直切片应集中关闭“离线可视化存量 DSL”而不是继续搭宿主骨架：

1. 用 workspace glob 建立 JSON/YAML operator 与 built-in function schema index；
2. 实现 TypeScript best-effort DSL scanner 和 `POST /api/visual/dsl-imports/preview` 离线路由；
3. 实现受限表达式/fixture mock simulator 和 `POST /api/visual/graphs/simulate` 离线路由；
4. 通过 VS Code `showTextDocument` 打开 source map，使用 diff editor 展示 DSL patch；
5. 用存量业务 `.bloge` 文件验证：不开 Spring Boot、不起端口、能看图、能 mock run、能回到源码；
6. 对需要真实 operator、secret、网络或治理证据的路径继续 fail closed，并引导配置受控远端 runtime。

这条切片才会把“示例离线可用”提升为“存量业务离线可迁移”，同时不把生产执行权限塞进 IDE。
