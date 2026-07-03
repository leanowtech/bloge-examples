# BLOGE 可视化编排设计包索引

状态：Draft v0.1
日期：2026-06-29

这份索引用于把通用 BLOGE 可视化编排系统的设计资产串起来。它不替代详细文档，只负责说明阅读路径、目标覆盖、当前结论和待确认问题。

## 1. 设计资产

| 文档 | 作用 | 建议读者 |
| --- | --- | --- |
| [通用 BLOGE 可视化编排系统设计方案](./bloge-visual-orchestration-system-design.md) | 主方案：系统目标、边界、架构、核心实体、路线图、治理和复杂业务覆盖 | 架构负责人、技术负责人、产品技术决策者 |
| [BLOGE 可视化编排协议草案 v1](./bloge-visual-orchestration-protocol-v1.md) | 协议合同：OperatorCatalog、GraphDraft、VisualDiagnostic、ResourceDesignContract、API、校验和存储 | 后端工程师、前端工程师、平台工程师 |
| [BLOGE 可视化编排系统关键决策记录](./bloge-visual-orchestration-decision-record.md) | 决策理由：备选方案、拒绝理由、接受代价、失效条件和回看触发器 | 架构评审者、CTO/Tech Lead |
| [BLOGE 可视化编排 Phase 1 实现蓝图](./bloge-visual-orchestration-phase1-implementation-blueprint.md) | 落地计划：包结构、DTO、controller、validator、DSL generator、前端迁移、测试和 DoD | 实施团队 |
| [BLOGE 可视化编排实现状态审计](./bloge-visual-orchestration-implementation-status.md) | 当前代码事实：已落地能力、wire contract 命名、Phase 1 验收、剩余生产级缺口和下一步 | 所有人，尤其是继续实现前的工程师 |

## 2. 推荐阅读路径

### 2.1 快速判断方向是否成立

1. 先读实现状态审计，确认当前代码已经做到哪里，以及早期协议名和当前实现名的差异。
2. 读主方案第 1-7 章，理解为什么这不是普通拖拽画布，而是可视化编排控制面。
3. 读决策记录 ADR-001 到 ADR-008，确认核心取舍是否能接受。
4. 读主方案第 20-23 章，确认路线图和下一步。

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
| 形成详尽设计方案 | 已覆盖，并新增实现状态审计防漂移 | 五份设计/状态文档合计覆盖架构、协议、决策、实现蓝图和当前代码事实 |

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
   而是在已落地 pre-bind validate、proposal persistence、bind/supersede lifecycle、adapter activation
   registry、executable lowering integration registry、catalog response projection 和 executable promotion
   projection，以及只读 executable readiness recompute preview 之后补 governed trusted library revision mutation，
   让 handoff contract snapshot 能被外部 runtime team 变成可绑定、可激活、可对账 executor bridge、
   可回滚、可重新派生 executable readiness 的实现事实。

## 5. 当前待确认决策

这些问题不是空白，而是需要产品/架构取舍：

| 决策 | 当前推荐 | 影响 |
| --- | --- | --- |
| 第一批目标用户 | 开发者 + 解决方案架构师优先，业务运营后置 | 决定 DSL 暴露程度和表单抽象深度 |
| Phase 1 是否保存草稿 | 已决：保存 H2-backed GraphDraft、revision history 和 revision metadata | 不保存草稿会削弱诊断、版本和 UI 状态一致性 |
| 用户算子库第一来源 | `ResourceDescriptor + ResourceDesignContract`，然后 JSON/YAML catalog | 最贴近 resource-gateway 基版 |
| OpenAPI 导入是否进 Phase 1 | 已作为 resource contract schema 辅助工具进入，不作为核心闭环前置条件 | 降低补 schema 成本，同时避免 OpenAPI 质量问题拖慢主线 |
| `opaque` 输出是否允许发布 | 默认阻断类型安全发布，租户策略可放宽为 warning | 决定系统是否真正坚持 schema 约束 |
| DSL 反向导入是否进 MVP | 不进 MVP | 先保证 GraphDraft -> DSL 稳定 |
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

1. Runtime binding implementation 与 readiness 派生闭环：在当前 implementation validate、proposal persistence、bind/supersede API、adapter activation registry、executable lowering integration registry、catalog response projection、executable promotion projection 和 readiness recompute preview 之后补 governed trusted library revision mutation。
2. 前端回归验证。
3. Java operator inventory 深化。
4. OpenAPI/resource contract/AsyncAPI 导入深化。
5. Run history 查询、重放和 SLO 统计深化。
6. 协议文档命名与当前 wire contract 收敛。

不要把新的可拖拽能力只写进前端；任何新能力都必须先有 `OperatorDefinition`、schema gate、GraphDraft lowering 和服务端 diagnostics。
