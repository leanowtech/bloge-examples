# BLOGE 可视化编排实现状态审计

状态：Current Implementation Snapshot
日期：2026-06-30
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

现在最重要的判断不是“有没有画布雏形”，而是：这个示例已经具备通用画布的控制面骨架，但仍不是完整低代码平台。它可以严肃演示用户算子库、资源虚拟算子、schema 约束连线、草稿、发布和运行；还没有覆盖 durable 实例、完整 run history、OpenAPI 导入、远程 worker/subgraph marketplace、生产 IAM 后台和多人协作。

## 2. 已落地能力

| 能力域 | 当前状态 | 代码证据 |
| --- | --- | --- |
| 用户算子库导入 | 已落地。支持 `bloge.visualOperatorLibrary.v1`、生命周期、policy、schema/lowering 校验、H2 持久化、跨库 `operatorRef` 归属保护和 replacement impact preflight。 | `visual/catalog/OperatorLibrary.java`、`OperatorLibraryValidator.java`、`OperatorLibraryAdminController.java`、`DatabaseOperatorLibraryRegistry.java` |
| 视觉算子定义 | 已落地。`OperatorDefinition` 统一表达 operatorRef、fingerprint、display、source、ports、configSchema、capabilities、policy、lowering 和 diagnostics。 | `visual/catalog/OperatorDefinition.java` |
| Resource 虚拟算子 | 已落地。`ResourceDescriptor + ResourceDesignContract` 投影为 `resource:<resourceId>`，运行时 lowering 到 `httpResource`。 | `visual/catalog/ResourceVirtualOperatorProjector.java`、`DefaultVisualOperatorCatalog.java` |
| Resource 设计合同 | 已落地。支持 H2 registry、bootstrap、admin API、schema/secret/lifecycle 校验、disable/delete impact guard、fingerprint drift warnings。 | `visual/resource/*` |
| Operator Catalog API | 已落地。支持 native、用户导入、resource-backed virtual operators；支持 search、tags、resourceOnly、includeDeprecated、tenant/namespace/environment policy filtering。 | `VisualOperatorCatalogController.java`、`DefaultVisualOperatorCatalog.java` |
| GraphDraft | 已落地。支持 `bloge.visualGraphDraft.v1`、nodes、edges、inputSchema、visualLayout、output、operatorFingerprints、revisionMetadata。 | `visual/draft/GraphDraft.java` |
| 草稿持久化与并发控制 | 已落地。H2 和 in-memory repository，revision history，`saveIfRevision`，controller 层 stale update/run/delete/publish guard，field-level JSON Patch。 | `GraphDraftRepository.java`、`DatabaseGraphDraftRepository.java`、`VisualGraphDraftController.java`、`GraphDraftPatchService.java` |
| Schema-aware validation | 已落地。支持输入/输出/config schema、contextPath/nodePath/expression/objectTemplate、data/dependency/route edges、DAG、output selection、policy、fingerprint drift、runtime context validation。 | `visual/validation/*` |
| 连接预检 | 已落地。服务端模拟 preview edge/binding/config expression，返回与当前拖拽连接相关的结构化 diagnostics。 | `visual/connection/*` |
| DSL 生成 | 已落地。支持 resource virtual operator、native user operator、transform lowering、branch lowering、dependency edges、named ports、root-object bindings、structured config expression。 | `visual/codegen/GraphDraftDslGenerator.java` |
| Compile / Run | 已落地。运行前做 fingerprint、draft validation、runtime context validation，再生成 DSL 并调用现有 dynamic runner。 | `visual/runtime/VisualGraphRunService.java` |
| Immutable Publication | 已落地。发布物冻结 draft、operator snapshots、fingerprints、layout、DSL、validation report、generation report，并支持 H2 持久化和 publication run。 | `visual/publication/*` |
| Browser Composer | 已落地。静态页面已从 catalog 拉取 operator palette，支持算子库导入、草稿、修订、发布、schema-aware picker/connection、DSL preview/run。 | `src/main/resources/static/examples/gateway/app.js`、`styles.css`、`index.html` |

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
| operator schema drift 可被阻断或预警 | 已满足 | draft fingerprint validation；library/resource replacement drift warning |
| 服务端为权威校验 | 已满足 | validate/compile/run/publish 均经过服务端 validator/generator/compiler |

## 5. 仍未完成的生产级能力

| 缺口 | 影响 | 建议阶段 |
| --- | --- | --- |
| Run history / node trace 持久化 | 现在运行结果能返回给 UI，但不能作为长期审计、回放、SLO 和问题定位记录。 | 下一阶段 |
| Durable / long-running graph authoring | 当前示例以 request-response 为主，不能覆盖长事务、人工审批、事件等待等编排。 | 平台化阶段 |
| OpenAPI / AsyncAPI 导入 | 用户仍需手写 operator library 或 resource design contract，导入成本偏高。 | 下一阶段 |
| Java OperatorRegistry 自动投影 | 当前 native 内建算子有限，尚未把任意 Java operator inventory 完整归一化为 visual operator。 | 下一阶段 |
| Subgraph / remote worker / AI tool source kinds | 设计已预留，当前示例主要覆盖 resource、native、user-library transform/branch/native lowering。 | 后续阶段 |
| 生产 IAM / RBAC / 权限后台 | 当前 policy 是 tenant/namespace/environment availability gate，不是完整权限系统。 | 平台化阶段 |
| 多人实时协作 | 当前通过 revision guard 防止覆盖，但没有 presence、merge、operation log 或 CRDT/OT。 | 后续阶段 |
| 前端自动化回归 | 后端测试覆盖很强，浏览器交互仍主要依赖手动验证。 | 下一阶段 |

## 6. 下一步优先级

1. **Run history 和 node trace 存储**：把一次 visual run 的 DSL、context 摘要、node status、diagnostics、elapsedMs、publication/draft source 固化，形成可审计闭环。
2. **OpenAPI/resource contract 导入辅助**：降低用户补 schema 成本，但必须走现有 `ResourceDesignContractValidator`，不能让未验证 schema 进入 catalog。
3. **Java operator inventory projector**：让现有 BLOGE Java operators 也能进入同一 visual catalog，而不是只依赖手写内建定义。
4. **前端回归验证**：给 catalog load、drag/drop connection preflight、draft save/publish/run 建立浏览器级 smoke test。
5. **文档协议收敛**：把早期草案字段名逐步改成当前实现的 wire contract，并保留平台化抽象命名作为未来 ADR，而不是混在当前协议里。

## 7. 反熵控制

为了让这个示例继续朝通用画布演进，而不是退化成一次性 demo，后续改动必须守住这些规则：

1. 新的可拖拽能力必须先进入 `OperatorDefinition`，不能只在前端 `app.js` 里硬编码。
2. 新的连接或绑定语义必须进入 `GraphDraftValidator` 和 `/api/visual/connections/check`，不能只靠浏览器判断。
3. 新的 executable draft 必须保留 operator fingerprint snapshot，并在 compile/run/publish 前验证。
4. 新的 resource authoring 信息必须通过 `ResourceDesignContract` 或等价合同进入 catalog projection，不能污染 runtime `ResourceDescriptor` 的调用职责。
5. 新的发布/运行能力必须返回结构化 `VisualDiagnostic`，不能只返回字符串错误。
6. README 可以描述用户体验，但协议字段和不变量必须以 `docs/bloge-visual-orchestration-implementation-status.md` 和代码为准同步更新。
