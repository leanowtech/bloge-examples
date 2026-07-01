# BLOGE 可视化编排实现状态审计

状态：Current Implementation Snapshot
日期：2026-07-01
代码基线：`resource-gateway-examples`

本文不是新的愿景文档，而是把当前代码事实、已完成闭环、仍缺口和下一步工程重点对齐起来。它用于防止设计文档、README 和实际实现发生语义漂移。

## 1. 当前结论

Phase 1 的核心闭环已经从“设计草案”进入“可运行示例项目”：

```text
Operator Library / Resource Design Contract
        -> Visual Operator Catalog
        -> GraphDraft
        -> Schema-aware Connection / Validation
        -> BLOGE DSL Generation
        -> Compile / Run
        -> Immutable Publication
```

现在最重要的判断不是“有没有画布雏形”，而是：这个示例已经具备通用画布的控制面骨架，但仍不是完整低代码平台。它可以严肃演示用户算子库、Java OperatorRegistry 投影、资源虚拟算子、OpenAPI 辅助生成 resource design contract、schema 约束连线、草稿、发布和运行；还没有覆盖 durable 实例、远程 worker/subgraph marketplace、生产 IAM 后台和多人协作。

## 2. 已落地能力

| 能力域 | 当前状态 | 代码证据 |
| --- | --- | --- |
| 用户算子库导入 | 已落地。支持 `bloge.visualOperatorLibrary.v1`、生命周期、policy、schema/lowering/DSL 可寻址性校验、`oneOf`/`anyOf` visual union schema、H2 持久化、跨库 `operatorRef` 归属保护，以及面向 stored draft 和 immutable publication 的 replacement/delete impact preflight。 | `visual/catalog/OperatorLibrary.java`、`OperatorLibraryValidator.java`、`OperatorLibraryAdminController.java`、`DatabaseOperatorLibraryRegistry.java` |
| 视觉算子定义 | 已落地。`OperatorDefinition` 统一表达 operatorRef、fingerprint、display、source、ports、configSchema、capabilities、policy、lowering 和 diagnostics。 | `visual/catalog/OperatorDefinition.java` |
| Resource 虚拟算子 | 已落地。`ResourceDescriptor + ResourceDesignContract` 投影为 `resource:<resourceId>`，运行时 lowering 到 `httpResource`。 | `visual/catalog/ResourceVirtualOperatorProjector.java`、`DefaultVisualOperatorCatalog.java` |
| Java OperatorRegistry 投影 | 已落地基础版。Spring `OperatorRegistry` 中的 Java `Operator` / `StreamingOperator` / `SuspendableOperator` 可投影为 visual operator，复用 BLOGE schema metadata、注解描述、capabilities 和 native lowering；catalog 会区分普通、streaming、suspendable Java source kind，运行时把 DSL map 输入适配到 Java DTO/record，并支持 record 输出路径选择。 | `visual/catalog/JavaOperatorInventoryProjector.java`、`config/GatewayConfiguration.java`、`example/InputCoercingOperatorRegistry.java`、`example/DynamicGatewayComposerService.java` |
| Resource 设计合同 | 已落地。支持 H2 registry、bootstrap、admin API、schema/secret/lifecycle 校验、disable/delete impact guard、fingerprint drift warnings。 | `visual/resource/*` |
| OpenAPI resource contract 导入辅助 | 已落地。`POST /admin/resource-design-contracts/from-openapi` 可从 OpenAPI operation 生成 `ResourceDesignContract` 草案和可审阅的 `ResourceDescriptor` 建议，支持 parsed `openApi` 或 raw JSON/YAML `openApiText` 输入、operationId 或 path+method 定位、参数/requestBody/2xx JSON response schema 投影、local component `$ref` 到 `$defs` 改写、server/path 到 `urlTemplate`、path/query/header/cookie/body 到 runtime parameter mapping，可保留 `application/*+json` 等 JSON-compatible vendor media type 到 descriptor `Accept` / `Content-Type` 并给出 review warning；`application/x-www-form-urlencoded` requestBody 会投影为 `ctx.params.body`，并由 `HttpResourceOperator` 在运行时把 Map body 编码成标准表单请求体；`multipart/form-data`、binary 等仍会生成 warning 并省略 body mapping，避免伪造不可运行 descriptor；可把 HTTP bearer/basic 和 header apiKey security scheme 投影成带占位凭据的 `authStrategy` 建议；OAuth2、OpenID Connect 和 mutualTLS 会生成明确的 descriptor-suggestion warning，而不是伪造不可运行的 runtime auth；重新导入已有 resource 时返回 request/response schema、合同 metadata 和 runtime descriptor diff warnings；不会自动落库。 | `OpenApiResourceDesignContractImporter.java`、`ResourceDesignContractAdminController.java` |
| Operator Catalog API | 已落地。支持 native、Java registry、用户导入、resource-backed virtual operators；支持 search、tags、resourceOnly、includeDeprecated、tenant/namespace/environment policy filtering。 | `VisualOperatorCatalogController.java`、`DefaultVisualOperatorCatalog.java` |
| GraphDraft | 已落地。支持 `bloge.visualGraphDraft.v1`、nodes、edges、inputSchema、visualLayout、output、operatorFingerprints、revisionMetadata。 | `visual/draft/GraphDraft.java` |
| 草稿持久化、迁移与并发控制 | 已落地。H2 和 in-memory repository，revision history，`saveIfRevision`，controller 层 stale update/run/delete/publish guard，field-level JSON Patch；`bloge.visualGraphDraftExport.v1` 支持导出 draft snapshot、operator snapshots 和 export-time diagnostics，并以新 identity 导入且刷新当前 operator fingerprints；`bloge.visualGraphDraftImportResult.v1` 在导入后返回目标环境兼容性 diagnostics。 | `GraphDraftRepository.java`、`DatabaseGraphDraftRepository.java`、`GraphDraftExportBundle.java`、`GraphDraftImportResult.java`、`VisualGraphDraftController.java`、`GraphDraftPatchService.java` |
| Schema-aware validation | 已落地。支持输入/输出/config schema、`oneOf`/`anyOf` union schema、contextPath/nodePath/expression/objectTemplate、data/dependency/route edges、DAG、output selection、DSL-safe path/port segments、policy、fingerprint drift、runtime context validation；union 连接采用保守策略，source union 必须所有分支可赋值，target `anyOf` 至少一个分支可接，target `oneOf` 必须唯一分支可接；显式 `targetUnionBranch` / `targetUnionBranches` 可把 binding 和 data edge 校验收敛到用户选择的 target branch；config authoring 可通过 `visualLayout.nodes[].annotations.configUnionBranches` 选择 config schema 分支，并让 literal config、objectTemplate 和 config expression 引用按选中分支校验。 | `visual/validation/*` |
| 连接预检 | 已落地。服务端模拟 preview edge/binding/config expression，返回与当前拖拽连接相关的 schema、policy、DAG 和 DSL-safe path/port segment diagnostics；connection check request 可携带 `targetUnionBranch`，让尚未写入 draft 的拖拽预检也能按显式 union branch 消歧。 | `visual/connection/*` |
| DSL 生成 | 已落地。支持 resource virtual operator、native user operator、transform lowering、branch lowering、dependency edges、named ports、root-object bindings、structured config expression。 | `visual/codegen/GraphDraftDslGenerator.java` |
| Compile / Run | 已落地。运行前做 fingerprint、draft validation、runtime context validation，再生成 DSL 并调用现有 dynamic runner；Java operator 运行会在 gateway 边界做 typed input coercion。 | `visual/runtime/VisualGraphRunService.java`、`example/InputCoercingOperatorRegistry.java` |
| Run history / node trace 摘要 | 已落地。transient draft、stored draft 和 publication run 会生成 `runId`，并持久化来源、revision、statusMap、diagnostics、errors、elapsedMs、runtime per-node elapsedMs、DSL、context/output/results shape-only 摘要和 draft node snapshots；API 已支持 source/draft/publication/graph/outcome/limit 查询、成功率/blocked/error/latency p50/p95 stats 聚合、节点级 run count/status 分布/diagnostic attribution/selected-output count/runtime per-node latency 聚合，并保留 observed whole-run latency 供 legacy 记录和慢运行关联分析使用，以及 `/api/visual/runs/{runId}/trace` shape-only replay trace，按节点暴露 operator metadata、result summary、timingKnown/elapsedMs 和 diagnostics 归属；浏览器 Run History 面板可查看最近运行、SLO 摘要和节点热区，并打开单条运行记录和节点 trace，同时在当前画布匹配节点上显示 replay badge，并报告历史 trace 与当前画布的节点覆盖率/缺失节点。 | `visual/runtime/VisualGraphRunRecord.java`、`VisualGraphRunTrace.java`、`VisualGraphRunStats.java`、`VisualGraphRunNodeStats.java`、`DatabaseVisualGraphRunRepository.java`、`VisualGraphRunHistoryController.java`、`static/examples/gateway/app.js` |
| Immutable Publication | 已落地。发布物冻结 draft、operator snapshots、fingerprints、layout、DSL、validation report、generation report，并支持 H2 持久化和 publication run。 | `visual/publication/*` |
| Golden regression cases | 已落地基础版。golden case 绑定不可变 publication，保存样例 context、outputNode、期望输出和可选 assertions；执行时复用 frozen publication DSL，写入 run history，并返回 exact-output、`OUTPUT_EQUALS/PATH_EQUALS/PATH_APPROX_EQUALS/PATH_EXISTS/PATH_ABSENT` value/path assertion 或 `OUTPUT_MATCHES_SCHEMA` schema assertion diagnostics。`PATH_APPROX_EQUALS` 支持绝对容差和相对容差，用于评分、金额、概率和聚合指标等非严格精确输出。API 和浏览器均支持单 case run、publication 级 suite run 汇总、latest certification，以及基于 case-set fingerprint 的 `CERTIFIED/STALE/FAILED/MISSING_CASES/UNCERTIFIED` promotion-readiness 状态。 | `visual/golden/*`、`VisualGraphRunRecord.java`、`static/examples/gateway/app.js` |
| Browser Composer | 已落地。静态页面已从 catalog 拉取 operator palette，支持算子库导入、草稿保存/导出/导入、修订、发布、schema-aware picker/connection、`oneOf`/`anyOf` union schema 本地类型提示、分支摘要可视化、root/nested target union branch 选择、config union branch 选择与 layout annotations 持久化、值匹配/连线兼容 advisory、DSL preview/run、run history SLO 摘要，以及 publication golden case exact-output、多条 value/path assertion、numeric tolerance assertion 和 output-schema assertion authoring、保存、单跑、suite run、certify/status。 | `src/main/resources/static/examples/gateway/app.js`、`styles.css`、`index.html` |

## 3. 协议命名现状

早期设计文档使用了较抽象的草案名：

| 早期草案名 | 当前实现名 | 说明 |
| --- | --- | --- |
| `bloge.operatorCatalog.v1` | `bloge.visualOperator.v1` / `bloge.visualOperatorLibrary.v1` / `bloge.visualOperatorCatalog.v1` | 当前实现把单个算子、用户算子库、catalog response 拆成三个显式合同。 |
| `bloge.graphDraft.v1` | `bloge.visualGraphDraft.v1` | 当前实现明确这是 visual authoring draft，而不是 BLOGE runtime graph AST。 |
| `bloge.resourceDesignContract.v1` | `ResourceDesignContract` record，目前 schemaVersion 由实现归一化 | 当前 API 以 resourceId 为主键管理设计时合同。 |
| `bloge.visualDiagnostic.v1` | `VisualDiagnostic` record | 当前诊断以 `level/code/message/target/line/column` 为核心字段。 |
| 未单列 | `bloge.visualGraphPublication.v1` | 当前实现新增不可变发布物协议。 |

结论：后续协议文档应以当前实现名为准，再讨论是否在平台化抽取时统一命名。不要把早期 `operatorCatalog` / `graphDraft` 草案名当成当前 API 的 wire contract。

## 4. 已完成的 Phase 1 验收

| 验收项 | 当前结论 | 证据 |
| --- | --- | --- |
| 新增带 schema 的 resource 不改前端即可进入 palette | 已满足 | catalog API + browser `loadVisualOperatorCatalog()` 动态注册 `OPERATOR_TYPES` |
| 用户可上传算子库定义 | 已满足 | `/admin/visual-operator-libraries` |
| 算子 input/output/config schema 进入画布 | 已满足 | `OperatorDefinition.Ports`、`configSchema`、browser schema field rendering |
| 连线受 schema 约束 | 已满足 | `GraphDraftValidator` + `/api/visual/connections/check` |
| 草稿可保存、修订、恢复 | 已满足 | draft repository revisions + browser Drafts panel |
| 发布物不可变 | 已满足 | `VisualGraphPublication` + insert-only repository |
| 运行不依赖当前 catalog | 对 published artifact 已满足 | publication run 使用 frozen DSL |
| operator schema drift 可被阻断或预警 | 已满足 | draft fingerprint validation；library/resource replacement drift warning；publication frozen-DSL impact warning |
| 服务端为权威校验 | 已满足 | validate/compile/run/publish 均经过服务端 validator/generator/compiler |

## 5. 仍未完成的生产级能力

| 缺口 | 影响 | 建议阶段 |
| --- | --- | --- |
| Durable / long-running graph authoring | 当前示例以 request-response 为主，不能覆盖长事务、人工审批、事件等待等编排。 | 平台化阶段 |
| AsyncAPI / 更广义接口导入 | OpenAPI JSON/YAML 到 resource contract 与基础 descriptor suggestion 的第一层已落地；OAuth2/OpenID/mTLS 已有明确 warning 诊断，但 token acquisition、OIDC discovery、client certificate 传输配置和完整 descriptor 自动化仍未覆盖；事件流、webhook、消息队列也仍未覆盖。 | 下一阶段 |
| Java OperatorRegistry 深化 | 已支持基础投影、typed DTO/record 运行，以及普通/streaming/suspendable Java source kind 可见性；高级泛型展示、streaming UX、suspend/resume authoring 和更丰富注解语义仍需深化。 | 下一阶段 |
| Subgraph / remote worker / AI tool source kinds | 设计已预留，当前示例主要覆盖 resource、native、user-library transform/branch/native lowering。 | 后续阶段 |
| 生产 IAM / RBAC / 权限后台 | 当前 policy 是 tenant/namespace/environment availability gate，不是完整权限系统。 | 平台化阶段 |
| 多人实时协作 | 当前通过 revision guard 防止覆盖，但没有 presence、merge、operation log 或 CRDT/OT。 | 后续阶段 |
| 前端自动化回归 | 已新增 browser-facing HTTP smoke、`app.js` schema/helper probe 和 Selenium/Chrome DOM smoke，覆盖静态入口、catalog、连接预检、draft save/export/import/run/publish、draft revision preview/restore/delete、publication run、publication golden case assertion/save/run/suite run/certify/status、run history query/filter/stats UI、Java operator palette、OpenAPI JSON/YAML contract/descriptor preview 与 save UI wiring、用户算子库导入、palette-to-canvas 拖拽、schema-aware connection 拖拽、schema-incompatible connection rejection、`oneOf`/`anyOf` union schema 浏览器本地 hint、端口分支摘要、root/nested target branch selection 和 config union branch selection 序列化/本地消歧、route/dependency/config 复杂连接拖拽、无输入端口用户算子的 UI schema 投影、graph output 选择、页面 warning diagnostics 渲染、server validation failure diagnostics 渲染和 publication run。更多失败场景和更多浏览器矩阵仍需补齐。 | 下一阶段 |

## 6. 下一步优先级

1. **真实浏览器交互回归**：在现有 browser-facing HTTP + Selenium DOM smoke 基础上，补更多失败场景和更多浏览器矩阵验证。
2. **Java operator inventory 深化**：补 streaming/suspendable 画布交互、复杂泛型展示、注解扩展和 Java operator drift/兼容性策略。
3. **OpenAPI 导入深化**：继续补 multipart/form-data、binary/streaming 等非 JSON media type 的可运行编码策略，OAuth2/OpenID/mTLS 的可配置运行时接入策略和更完整的 descriptor 自动化；仍必须走现有 `ResourceDesignContractValidator`，不能让未验证 schema 进入 catalog。
4. **Run history / Golden 深化**：当前已经保存 shape-only run record，并补了查询过滤、浏览器最近运行列表、基础 SLO stats、节点级 runtime per-node latency/health 聚合、observed whole-run latency legacy/correlation 字段、带节点诊断归属和 per-node timing 的 shape-only run trace、画布 badge 和覆盖率提示、publication 级 golden case、suite run、latest certification、stale-aware promotion-readiness status、基础 golden assertions、output-schema assertions 和 numeric tolerance assertions；下一步可补事件流回放和更完整的 regression trend。
5. **文档协议收敛**：把早期草案字段名逐步改成当前实现的 wire contract，并保留平台化抽象命名作为未来 ADR，而不是混在当前协议里。

## 7. 反熵控制

为了让这个示例继续朝通用画布演进，而不是退化成一次性 demo，后续改动必须守住这些规则：

1. 新的可拖拽能力必须先进入 `OperatorDefinition`，不能只在前端 `app.js` 里硬编码。
2. 新的连接或绑定语义必须进入 `GraphDraftValidator` 和 `/api/visual/connections/check`，不能只靠浏览器判断。
3. 新的 executable draft 必须保留 operator fingerprint snapshot，并在 compile/run/publish 前验证。
4. 新的 resource authoring 信息必须通过 `ResourceDesignContract` 或等价合同进入 catalog projection，不能污染 runtime `ResourceDescriptor` 的调用职责。
5. 新的发布/运行能力必须返回结构化 `VisualDiagnostic`，不能只返回字符串错误。
6. README 可以描述用户体验，但协议字段和不变量必须以 `docs/bloge-visual-orchestration-implementation-status.md` 和代码为准同步更新。
