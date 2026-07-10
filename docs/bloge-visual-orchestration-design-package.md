# BLOGE 可视化编排设计包索引

状态：Draft v0.2
日期：2026-07-07

这份索引用于把通用 BLOGE 可视化编排系统的设计资产串起来。它不替代详细文档，只负责说明阅读路径、目标覆盖、当前结论和待确认问题。

## 1. 设计资产

| 文档 | 作用 | 建议读者 |
| --- | --- | --- |
| [通用 BLOGE 可视化编排系统设计方案](./bloge-visual-orchestration-system-design.md) | 主方案：系统目标、边界、架构、核心实体、路线图、治理和复杂业务覆盖 | 架构负责人、技术负责人、产品技术决策者 |
| [BLOGE 可视化编排协议草案 v1](./bloge-visual-orchestration-protocol-v1.md) | 协议合同：OperatorCatalog、GraphDraft、VisualDiagnostic、ResourceDesignContract、API、校验和存储 | 后端工程师、前端工程师、平台工程师 |
| [BLOGE 可视化编排系统关键决策记录](./bloge-visual-orchestration-decision-record.md) | 决策理由：备选方案、拒绝理由、接受代价、失效条件和回看触发器 | 架构评审者、CTO/Tech Lead |
| [BLOGE 可视化编排 Phase 1 实现蓝图](./bloge-visual-orchestration-phase1-implementation-blueprint.md) | 落地计划：包结构、DTO、controller、validator、DSL generator、前端迁移、测试和 DoD | 实施团队 |
| [BLOGE 可视化编排实现状态审计](./bloge-visual-orchestration-implementation-status.md) | 当前代码事实：已落地能力、wire contract 命名、Phase 1 验收、剩余生产级缺口和下一步 | 所有人，尤其是继续实现前的工程师 |
| [BLOGE 可视化编排工业化评估报告](./bloge-visual-orchestration-industrialization-assessment.md) | 多维度成熟度评分、当前工业级差距、本轮迭代复盘和下一轮优先级 | 架构负责人、技术负责人、继续迭代的工程师 |
| [存量 BLOGE DSL 业务迁移到可视化编排设计方案](./bloge-legacy-dsl-visual-migration-design.md) | 迁移专线：消费任意合法 operator/function schema，导入手写 `.bloge` DSL，投影为 GraphDraft，并做 source map、诊断和 round-trip；业务代码导出 capability catalog 只是推荐来源之一 | 已接入 BLOGE 的业务团队、迁移平台团队、画布/Studio/LSP 实施团队 |
| [BLOGE VSCode 插件轻量化可视化编排方案](./bloge-vscode-extension-lightweight-authoring-plan.md) | IDE 轻量入口：不启动服务端即可打开 `.bloge` 看 topology、扫描本地 schema、做本地 mock simulation，并可选切换 JVM/远程权威校验 | VSCode 插件实施团队、BLOGE Studio/LSP 团队、业务研发团队 |
| [Legacy DSL Source Map UI 验证记录](./bloge-visual-canvas-legacy-dsl-source-map-verification.md) | 本轮浏览器验证证据：合法 inline visual library + `.bloge` 渲染、source map 行定位、导出 draft 保留源码映射和剩余差距 | 继续实现迁移专线的工程师、产品验收人员 |
| [Author Canvas UX Focus 验证记录](./bloge-visual-canvas-ux-focus-verification.md) | 本轮 B 端 UX 验证证据：Canvas Focus 模式、默认辅助区压缩、真实浏览器画布高度门槛和剩余差距评估 | 产品负责人、UX 设计师、前端工程师、验收人员 |

## 2. 推荐阅读路径

### 2.1 快速判断方向是否成立

1. 先读工业化评估报告，确认当前离完整生产级平台还有多大差距，以及最近一轮迭代修补了什么。
2. 读实现状态审计，确认当前代码已经做到哪里，以及早期协议名和当前实现名的差异。
3. 读主方案第 1-7 章，理解为什么这不是普通拖拽画布，而是可视化编排控制面。
4. 读决策记录 ADR-001 到 ADR-008，确认核心取舍是否能接受。
5. 读主方案第 20-23 章，确认路线图和下一步。

### 2.2 准备进入实现

1. 读实现状态审计第 2-6 章，把下一步任务绑定到当前代码事实，而不是早期草案假设。
2. 读协议草案第 2-9 章，理解四个核心协议和校验模型；遇到 wire contract 命名冲突时，以实现状态审计和代码为准。
3. 读协议草案第 10-14 章，理解 API、校验流水线、存储和最小闭环。
4. 读 Phase 1 实现蓝图全篇，按 slice 拆任务。

### 2.3 做架构评审

1. 读主方案全篇。
2. 对照决策记录检查每个关键选择是否有替代方案和失效条件。
3. 对照本索引第 3 章检查原始目标覆盖。
4. 对照 Phase 1 实现蓝图第 12 章检查 Definition of Done。

## 3. 原始目标覆盖矩阵

| 原始目标要求 | 覆盖结论 | 主要证据 |
| --- | --- | --- |
| 以 `resource-gateway` 为基版 | 已覆盖 | 主方案第 2、7、19、20 章；Phase 1 蓝图第 1、3、6、10 章 |
| 定义通用 BLOGE 可视化编排画布 | 已覆盖 | 主方案第 1、3、5、6、13 章；协议草案第 7、8、9 章 |
| 接收用户提供的算子库定义 | 设计和实现均已覆盖 | 主方案第 8、12 章；协议草案第 5、10 章；决策记录 ADR-003；实现状态审计第 2 章 |
| 支持 operator schema、input/output schema | 设计和实现均已覆盖 | 协议草案第 4、5、6、7 章；主方案第 8、10 章；实现状态审计第 2 章 |
| 支持 schema 约束下拖拽和连线 | 设计和实现均已覆盖 | 主方案第 10、13 章；协议草案第 7、9、11 章；Phase 1 蓝图第 6、7、8 章；实现状态审计第 2、4 章 |
| 支持自由编排业务逻辑 | 已覆盖核心闭环，复杂能力分阶段 | 主方案第 11、13、16、20 章；Phase 1 蓝图第 9 章；实现状态审计第 5 章 |
| 能吃下复杂业务编排场景 | 已覆盖架构方向，生产级能力仍分阶段补齐 | 主方案第 16、18、20 章；决策记录 ADR-008、ADR-009、ADR-010；实现状态审计第 5、6 章 |
| 支持存量手写 DSL 业务升级到可视化交付 | 迁移专线设计已补齐，resource-gateway 后端 DSL preview/commit/rewrite-gate/batch-report/batch-commit、命令行/CI batch import CLI、`/author/` Legacy DSL 面板、topology-only 无 schema 可视化、source map 行定位、stored draft 保存、preview 内置 round-trip 状态和 source replacement preflight 已落地；真正覆盖原 DSL 的 source writer / VCS 集成仍未闭环 | 存量 DSL 迁移设计方案第 4-15 章；`scripts/bloge-dsl-batch-import.sh`；`POST /api/visual/dsl-imports/preview`、`POST /api/visual/dsl-imports/commit`、`POST /api/visual/dsl-imports/rewrite-gate`、`POST /api/visual/dsl-imports/batch-report`、`POST /api/visual/dsl-imports/batch-commit`；画布先从 DSL AST 推演 topology-only draft，schema-backed catalog 只是后续精确校验、补全、执行和 rewrite gate 的增强层，框架 `bloge.capabilityCatalog.v1` / `export-schema` 是推荐 schema acquisition 来源之一 |
| 降低可视化编排与模拟验证的启动成本 | 新增 VSCode 插件轻量化方案，并在前端 API 层补 `BlogeApiTransport` 插拔点；后续可在 webview 内复用同一 Author Canvas，通过 extension host 本地满足 operator catalog、DSL preview 和 mock simulation 合同 | VSCode 插件轻量化方案第 4-13 章；`resource-gateway-examples/src/main/frontend/src/api.ts` |
| 形成详尽设计方案 | 已覆盖，并新增实现状态审计与存量迁移专线防漂移 | 设计/状态文档合计覆盖架构、协议、决策、实现蓝图、当前代码事实和存量 DSL 迁移路径 |

## 4. 当前核心结论

1. **画布不是第一性原理，合同才是。**
   真正的一等模型是 `OperatorDefinition`、`GraphDraft`、`VisualDiagnostic` 和 `ResourceDesignContract`。

2. **Resource Gateway 的核心价值是 descriptor-driven runtime。**
   通用画布应把 `ResourceDescriptor` 投影成虚拟算子，而不是为每个 API 写 Java Operator。

3. **GraphDraft 是画布语义源。**
   前端拼 DSL 和 layout 承载语义都不适合长期演进。

4. **visualLayout 必须保持 presentation-only。**
   它保存位置、分组、尺寸和视口，不保存业务逻辑。

5. **缺 `payloadSchema` 的 resource 不能假装类型安全。**
   可以执行，但输出必须降级为 `opaque`，不能随意参与 schema-aware 连线。

6. **服务端校验是权威。**
   前端校验只改善体验，发布和运行前必须经过服务端 schema、policy、lowering 和 BLOGE compile。

7. **Phase 1 核心闭环已经在 resource-gateway 内落地。**
   Java operator inventory 基础投影也已进入 catalog；下一阶段的 P0 不是继续证明画布能画图，
   而是在已落地 pre-bind validate、proposal persistence、bind/supersede/unbind lifecycle、adapter activation
   registry、runtime rollout observation registry、executable lowering integration registry、catalog response projection 和 executable promotion
   projection、只读 executable readiness recompute preview、native governed apply mutation、
   external-runtime-bound governed apply mutation、apply revision-write failed result、post-apply evidence refresh/rebind mutation、governed
   unbind/deactivate mutation、bind 写入失败诊断、字段级 implementation contract-diff gate、semantic JSON
   Schema-compatible drift classification、runtime implementation SemVer/reimplementation submit gate、implementation rolloutPlan validate gate
   和四类 runtime evidence submit stable-id 精确 replay
   幂等返回、submit 写入失败诊断、overview runtime evidence aggregate 与 runtime-evidence action queue、operator-library create/import-text/import-bundle/replace/restore/delete registry 写入失败诊断、ResourceDesignContract upsert/delete registry 写入失败诊断、draft create/full-save/import 写入失败诊断、publication publish/import 持久化失败诊断，以及 golden save/delete/run-history/certification 写入失败诊断之后，补其他尚未覆盖的跨 repository 事务/partial-failure 硬化、更广义 idempotency、
   更深跨系统 rollout/canary/rollback 编排、指标消费闭环和在已补 `allOf` 结构校验、runtime value matching 与保守交集推理之后继续深化 JSON Schema 兼容性门禁，让
   handoff contract snapshot 能被外部 runtime team 变成可绑定、可激活、可对账 executor bridge、
   可回滚、可持续重新派生 executable readiness 的实现事实。

8. **VSCode 插件应该成为低门槛入口，而不是第二套平台。**
   插件默认不启动服务端，只做本地 topology、schema index、mock simulation 和文件协作；需要权威 compile、rewrite gate、governed commit、发布治理时，再显式切换到 JVM assisted 或远程服务端模式。

## 5. 当前待确认决策

这些问题不是空白，而是需要产品/架构取舍：

| 决策 | 当前推荐 | 影响 |
| --- | --- | --- |
| 第一批目标用户 | 开发者 + 解决方案架构师优先，业务运营后置 | 决定 DSL 暴露程度和表单抽象深度 |
| Phase 1 是否保存草稿 | 已决：保存 H2-backed GraphDraft、revision history 和 revision metadata | 不保存草稿会削弱诊断、版本和 UI 状态一致性 |
| 用户算子库第一来源 | `ResourceDescriptor + ResourceDesignContract`，然后 JSON/YAML catalog | 最贴近 resource-gateway 基版 |
| OpenAPI 导入是否进 Phase 1 | 已作为 resource contract schema 辅助工具进入，不作为核心闭环前置条件 | 降低补 schema 成本，同时避免 OpenAPI 质量问题拖慢主线 |
| `opaque` 输出是否允许发布 | 默认阻断类型安全发布，租户策略可放宽为 warning | 决定系统是否真正坚持 schema 约束 |
| DSL 反向导入是否进 MVP | 不进 Phase 1 MVP；作为 Phase 2 存量迁移专线推进 | 先保证 GraphDraft -> DSL 稳定，再用合法 operator/function catalog + `DslCompiler.parseAst()` 做 loss-aware DSL import；`bloge.capabilityCatalog.v1` 是推荐来源但不是画布硬依赖 |
| AI authoring 是否进 MVP | 不进核心 MVP | 先建立确定性合同和校验链 |

## 6. Phase 1 最小验收

截至 2026-06-30，下面的最小闭环已由 `resource-gateway-examples` 代码实现。后续是否达到“完整生产级平台”应看实现状态审计第 5 章列出的剩余能力，而不是重复判断 Phase 1 是否存在。

Phase 1 不以 UI 好看为验收，而以真实闭环为验收：

1. 可以为现有 resource 注册 `ResourceDesignContract`。
2. `/api/visual/operators` 能返回由 resource 投影出的 virtual operator。
3. 前端 palette 不写死 operator 类型。
4. 用户能创建 draft，绑定 `ctx` 输入，连接 resource output 到 transform 或 decision table。
5. 服务端能校验 required input、unknown operator、schema incompatibility 和 opaque output。
6. 服务端能生成 BLOGE DSL。
7. 服务端能运行生成的 DSL，并返回 diagnostics、statusMap、output。
8. 没有 `payloadSchema` 的 resource 不能作为强类型输出连接。
9. 现有 `/admin/resources`、gateway 示例 endpoints 和 composer legacy path 不被破坏。
10. `mvn -f resource-gateway-examples/pom.xml clean verify` 通过。

## 7. 推荐下一步

如果继续代码实现，优先按实现状态审计第 6 章推进：

1. Runtime binding implementation 与 readiness 派生闭环：在当前 implementation validate、字段级 contract-diff gate、semantic JSON Schema-compatible drift classification、runtime implementation SemVer/reimplementation submit gate、implementation rolloutPlan validate gate、proposal persistence、bind/supersede/unbind API、bind 写入失败诊断、adapter activation registry、runtime rollout observation registry、executable lowering integration registry、四类 runtime evidence submit stable-id 精确 replay 幂等返回、catalog response projection、executable promotion projection、readiness recompute preview、native governed apply mutation、external-runtime-bound governed apply mutation、apply revision-write failed result、post-apply evidence refresh/rebind mutation 和 governed unbind/deactivate mutation 之后，补跨 repository 事务/partial-failure 硬化、更广义 idempotency、更深跨系统 rollout/canary/rollback 编排、指标消费闭环和更完整 JSON Schema 兼容性推理门禁。
2. 前端回归验证。
3. Java operator inventory 深化。
4. 存量业务迁移专线：补 opaque diagnostics 修复向导、Studio batch import dashboard、coverage dashboard 和源码 writer / VCS 集成；schema-neutral preview/commit/rewrite-gate/batch-report/batch-commit、CLI/CI batch import 入口、source map 行定位、`bloge.capabilityCatalog.v1` 到 visual library adapter、preview 内置 round-trip 状态已经落地。
5. VSCode 插件轻量入口：基于 `BlogeApiTransport` 接 webview postMessage，先完成无服务端 topology preview、本地 schema index、本地 mock simulation 三个路由。
6. OpenAPI/resource contract/AsyncAPI 导入深化。
7. Run history 查询、重放和 SLO 统计深化。
8. 协议文档命名与当前 wire contract 收敛。

不要把新的可拖拽能力只写进前端；任何新能力都必须先有 `OperatorDefinition`、schema gate、GraphDraft lowering 和服务端 diagnostics。
