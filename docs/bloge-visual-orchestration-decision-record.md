# BLOGE 可视化编排系统关键决策记录

状态：Draft v0.1
日期：2026-06-29
关联设计：

- [BLOGE 可视化编排设计包索引](./bloge-visual-orchestration-design-package.md)
- [通用 BLOGE 可视化编排系统设计方案](./bloge-visual-orchestration-system-design.md)
- [BLOGE 可视化编排协议草案 v1](./bloge-visual-orchestration-protocol-v1.md)
- [BLOGE 可视化编排 Phase 1 实现蓝图](./bloge-visual-orchestration-phase1-implementation-blueprint.md)

## 1. 决策上下文

目标是以 `resource-gateway-examples` 为基版，设计一套通用 BLOGE 可视化编排画布。画布必须能接收用户提供的算子库定义，理解 operator input/output schema，在 schema 约束下支持拖拽、连接、配置、校验、编译、运行和观测。

当前已有事实：

- resource-gateway 已有 `ResourceDescriptor`、`HttpResourceOperator`、`ResourceRegistry`、descriptor CRUD、动态 DSL compile/run composer。
- graph-engine 已有 `visualLayout.v1`、operator inventory、version metadata、diagnostics、diagram API 和版本控制面。
- 当前 gateway composer 的 operator palette、节点属性和 DSL 生成仍写死在前端 JavaScript 中，不能承载用户提供的任意算子库。

核心约束：

- 不能让视觉布局成为第二套业务语义来源。
- 不能让缺 schema 的 `Object payload` 假装类型安全。
- 不能为了拖拽体验绕开 BLOGE 编译器和 runtime 约束。
- MVP 必须能从现有 resource-gateway 平滑演进，不能一开始就要求重写 graph-engine。

## 2. 决策总览

| 编号 | 决策 | 推荐 |
| --- | --- | --- |
| ADR-001 | 画布语义模型 | 采用 `GraphDraft`，再生成 BLOGE DSL |
| ADR-002 | 视觉布局职责 | `visualLayout` 只做 presentation-only |
| ADR-003 | 算子目录模型 | 建立 `OperatorDefinition`，不直接暴露 Java 反射结果 |
| ADR-004 | ResourceDescriptor 设计时 schema | 使用独立 `ResourceDesignContract`，先不合并进 runtime descriptor |
| ADR-005 | Resource 虚拟算子 | `ResourceDescriptor + ResourceDesignContract` 投影为 `VirtualOperator` |
| ADR-006 | 校验权威性 | 前端即时校验，服务端权威校验 |
| ADR-007 | 缺 payload schema 策略 | 可执行、不可假装类型安全连接 |
| ADR-008 | graph-engine 关系 | gateway 先做 MVP，长期对齐/抽取到 graph-engine 控制面 |
| ADR-009 | DSL 反向导入 | 不进 MVP，先保证 GraphDraft -> DSL 单向稳定 |
| ADR-010 | AI 辅助 | 不进核心 MVP，等确定性合同稳定后再接入 |

## 3. ADR-001：采用 GraphDraft 作为画布语义模型

### 决策

画布编辑的核心模型是结构化 `GraphDraft`，不是前端拼接 BLOGE DSL 字符串，也不是直接编辑 `visualLayout`。

### 备选方案

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| 前端直接拼 BLOGE DSL | 最快复用现有 composer；预览直观 | 难以做增量校验、字段级诊断、结构化 patch、并发编辑、schema-aware 连接 | 拒绝 |
| 直接编辑 `visualLayout` | 与画布坐标天然一致 | layout 无法表达 schema、binding、lowering、retry、fallback、decision table 语义 | 拒绝 |
| GraphDraft -> BLOGE DSL | 支持结构化编辑、校验、定位、lowering、版本化 | 需要新增模型、DTO、codegen | 接受 |
| 直接构建 BLOGE Graph 对象 | 避免 DSL 生成 | 人类不可读，失去 DSL 生态、diff、文档和 AI 生成路径 | 暂不采用 |

### 理由

GraphDraft 能表达画布所需的中间语义：

- 节点来自哪个 `operatorRef` 和 fingerprint。
- 输入字段如何绑定到 ctx、常量、表达式或上游节点。
- 边是数据依赖、控制依赖、条件分支、stream 还是 fallback。
- 错误能定位到 node、edge、fieldPath。
- 虚拟算子能通过 lowering 生成真实 BLOGE DSL。

### 接受的代价

- 需要维护 GraphDraft schema。
- 需要写 GraphDraft -> DSL generator。
- 需要做 path mapping，例如虚拟 resource 的 `output.score` 到 DSL 的 `output.payload.score`。

### 失效条件

如果 BLOGE 编译器未来直接支持稳定、可序列化、可 diff 的 Graph AST，并能保留字段级 source mapping，可以重新评估是否让画布直接编辑 Graph AST。

## 4. ADR-002：visualLayout 只做 presentation-only

### 决策

继续沿用 graph-engine 的原则：`visualLayout` 只保存节点位置、尺寸、分组、标签、视口和轻量视觉注解，不承载业务语义。

### 备选方案

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| layout 承载完整语义 | 前端简单，一个 JSON 搞定 | 第二真相；与 DSL/Graph 编译结果冲突；运行行为不可审计 | 拒绝 |
| layout + DSL 双写 | 可以兼顾视觉和可执行 | 双写一致性极难维护 | 拒绝 |
| GraphDraft 语义 + visualLayout 表现 | 边界清楚；可复用现有 layout v1 | 需要维护两个相关对象 | 接受 |

### 理由

复杂编排系统最怕“看起来能跑，但不知道哪份定义是真的”。`visualLayout` 如果混入业务语义，后续会出现：

- layout 有节点但 DSL 没节点。
- DSL 有重试但 layout 没表达。
- 用户移动节点时误改业务逻辑。
- 版本 diff 不知道该比较 layout 还是 DSL。

### 接受的代价

保存 draft 时要同时维护 `GraphDraft.nodes/edges` 与 `visualLayout.nodes/edges` 的引用一致性。

### 失效条件

没有。这个边界应作为长期硬约束。

## 5. ADR-003：建立 OperatorDefinition，而不是直接暴露 Java 反射结果

### 决策

画布消费归一化 `OperatorDefinition`。Java `OperatorRegistry` 反射结果只是 `OperatorDefinition` 的一种来源。

### 备选方案

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| 直接使用 `OperatorInventoryEntry` | 快速复用 graph-engine API | 只适合 inventory，不含 lowering、policy、authoring hints、port 规则 | 拒绝作为最终模型 |
| 直接反射 Java input/output record | 实现快 | 不能表达虚拟算子、HTTP descriptor、subgraph、remote worker、AI tool | 拒绝 |
| 用户自己上传任意 JSON，前端解释 | 灵活 | 运行时不可控，安全和校验困难 | 拒绝 |
| 归一化 `OperatorDefinition` | 能统一 Java、resource、subgraph、worker、AI tool | 协议更重 | 接受 |

### 理由

可视化编排需要的不是“有哪些 Java 类”，而是“哪些能力可被安全地放进画布”。这至少包括：

- input/output/config schema。
- source.kind 与 runtime binding。
- side effect、idempotency、streaming、durable、secret requirements。
- tenant/namespace/environment policies。
- authoring hints。
- lowering rules。
- usage examples 和 fingerprint。

### 接受的代价

需要写 catalog projector：

- Java operator -> OperatorDefinition。
- ResourceDescriptor + ResourceDesignContract -> OperatorDefinition。
- User catalog JSON/YAML -> OperatorDefinition。

### 失效条件

如果系统永远只面向 Java 开发者，且只允许 Java Operator 进入画布，可以简化。但这会直接违背“用户提供算子库定义”的目标。

## 6. ADR-004：ResourceDesignContract 与 ResourceDescriptor 分离

### 决策

新增独立 `ResourceDesignContract` 存储 request schema、payload schema、error schema、examples、field hints。短中期不把这些字段合并进现有 `ResourceDescriptor`。

### 备选方案

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| 直接修改 `ResourceDescriptor` | 一个对象包含全部资源信息 | 破坏现有构造器和 JSON；runtime 与 authoring 变化频率耦合 | 暂不采用 |
| 独立 `ResourceDesignContract` | 不破坏 runtime；设计时信息可独立演进 | 需要 join/projector；可能出现缺合同状态 | 接受 |
| 从运行样本自动推断 schema | 用户成本低 | 推断不可靠，容易把偶然样本当契约 | 只能作为辅助 |
| 只用 OpenAPI 导入 | schema 完整 | 许多内部接口没有 OpenAPI 或质量很差 | 作为补充 |

### 理由

`ResourceDescriptor` 关注“怎么调用”。`ResourceDesignContract` 关注“怎么被理解、连接和治理”。两者变化频率不同：

- URL、method、timeout、auth、response protocol 是 runtime contract。
- payload schema、field label、examples、sensitive hint、UI control 是 authoring contract。

强行合并会让 UI 层字段调整污染 runtime descriptor，也会让现有 resource-gateway 迁移成本变高。

### 接受的代价

- 需要保证 `resourceId` 一致性。
- 无 design contract 的 resource 要降级处理。
- 管理 API 需要多一个端点。

### 失效条件

如果后续实践证明 runtime descriptor 和 design contract 总是同步更新，且兼容性迁移成本可控，可以在 v2 合并。但当前不应这么做。

## 7. ADR-005：ResourceDescriptor 投影成 VirtualOperator

### 决策

每个具备设计合同的 `ResourceDescriptor` 可以投影成 `operatorRef = resource:<resourceId>` 的虚拟算子。画布展示虚拟算子，运行时 lowering 到 `httpResource`。

### 备选方案

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| 画布只暴露 `httpResource` | 实现最简单 | 用户每次都要手填 resourceId/params；没有业务语义 | 拒绝 |
| 每个 API 写一个 Java Operator | 类型最强 | provider-specific 类爆炸，违背 resource-gateway 抽象 | 拒绝 |
| ResourceDescriptor 投影虚拟算子 | 保留通用 runtime，同时给画布业务语义 | 需要 lowering/path remap | 接受 |

### 理由

用户在画布上需要看到“Get Applicant Profile”，而不是“HTTP Resource”。但运行时不应该因此写一个 `GetApplicantProfileOperator`。虚拟算子正好分离用户心智和运行实现。

### 接受的代价

必须维护 path remap：

- 画布：`fetchApplicant.output.score`
- DSL：`fetchApplicant.output.payload.score`

### 失效条件

如果某个 API 具备复杂协议、长连接、本地事务或强定制性能要求，可能需要专用 Java Operator。但这应是例外，不是默认。

## 8. ADR-006：服务端校验为权威

### 决策

前端可做即时校验和交互限制，但 `validate`、`compile`、`publish`、`run` 必须由服务端裁决。

### 备选方案

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| 前端完全校验 | 响应快 | 不可信；无 runtime registry、权限、secret、compiler 上下文 | 拒绝 |
| 服务端完全校验，前端无约束 | 实现集中 | 体验差，拖线后才报错 | 拒绝 |
| 前端辅助 + 服务端权威 | 体验和安全平衡 | 需要双层规则和错误同步 | 接受 |

### 理由

服务端掌握：

- 当前 tenant/namespace/environment。
- runtime operator registry。
- resource registry。
- secret binding。
- policy gate。
- BLOGE compiler。
- graph-engine metadata。

前端不可能可靠地模拟这些条件。

### 接受的代价

需要低延迟增量 validate API，并保证前端 diagnostics 能和服务端一致。

### 失效条件

没有。只要有权限、外部调用和发布概念，服务端裁决就是硬约束。

## 9. ADR-007：缺 payload schema 不能假装类型安全

### 决策

没有 `payloadSchema` 的 resource 可以出现在 palette，也可以被手动执行，但其输出视为 `opaque`。默认不能参与类型安全连接，除非用户显式插入 opaque transform 或策略允许 warning 发布。

### 备选方案

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| 缺 schema 也允许任意连接 | 用户阻力小 | schema 约束名存实亡，错误推迟到运行时 | 拒绝 |
| 缺 schema 完全不显示 | 强约束 | 迁移阻力大，现有资源不可用 | 拒绝 |
| 显示但降级为 opaque | 诚实表达能力边界 | 用户需要补 schema 或显式 transform | 接受 |

### 理由

通用画布的核心价值是“在 schema 约束下自由编排”。如果 `Object payload` 可以随便连到任何字段，系统就退化为图形化脚本编辑器。

### 接受的代价

短期会暴露大量现有 descriptor 缺 schema，需要提供：

- 样本辅助推断。
- OpenAPI 导入。
- 手工 schema editor。
- 运行时 drift 检测。

### 失效条件

如果目标用户只是开发者，且愿意接受运行时失败，策略可以放松。但这会降低系统面对复杂业务编排的可信度。

## 10. ADR-008：gateway 先做 MVP，长期对齐 graph-engine 控制面

### 决策

Phase 1 以 `resource-gateway-examples` 做最小可用切片；长期将 catalog、draft、validation、layout、version、runtime observation 对齐或抽取到 graph-engine。

### 备选方案

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| 直接在 graph-engine 全量实现 | 架构正统 | 首次成本高，离 resource gateway 场景较远 | 暂不采用 |
| 只在 gateway 做独立平台 | 上手快 | 会与 graph-engine 控制面重复和分裂 | 拒绝长期化 |
| gateway MVP + graph-engine 对齐 | 路径现实，可复用现有场景 | 需要明确哪些是临时代码 | 接受 |

### 理由

resource-gateway 有最好的第一批业务样本：API 聚合、resource dispatch、decision table、branch、foreach、fallback、streaming。它适合证明通用画布价值。但版本治理、operator inventory、instance diagram、node states 等长期能力已经在 graph-engine 里有基础，不能重造一套。

### 接受的代价

MVP 包名和 API 要标明 experimental / visual，避免被误认为最终平台边界。

### 失效条件

如果一开始就必须服务生产级多租户部署，应直接从 graph-engine 控制面实现，而不是 gateway 示例内实现。

## 11. ADR-009：DSL 反向导入不进 MVP

### 决策

MVP 只保证 `GraphDraft -> BLOGE DSL` 单向稳定生成。DSL 反向导入延后。

### 备选方案

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| MVP 支持无损 DSL import | 对已有 DSL 友好 | 工作量大；复杂语法会拖慢核心闭环 | 拒绝进 MVP |
| 永远不支持 DSL import | 简单 | 割裂已有 BLOGE 用户和文件资产 | 拒绝 |
| 分阶段支持有限导入 | 平衡现实和长期兼容 | v1 有能力边界 | 接受 |

### 路线

1. MVP：GraphDraft -> DSL。
2. Phase 2：导入普通 node、transform、decision table、direct edge。
3. Phase 3：导入 branch、foreach、stream、wait、subgraph。无法编辑的结构显示为 read-only block。

### 失效条件

如果首批用户已有大量 `.bloge` 文件并要求从画布接管编辑，则 DSL import 优先级需要提前。

## 12. ADR-010：AI 辅助不进核心 MVP

### 决策

AI 可以作为后续 authoring assistant，但不进入核心 MVP 的确定性闭环。

### 备选方案

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| AI 优先生成画布 | 演示效果强 | 没有确定性合同时会制造不可验证草稿 | 拒绝 |
| 完全不考虑 AI | 简单 | 浪费 BLOGE AI 模块已有基础 | 拒绝长期化 |
| 先确定性合同，后 AI 辅助 | 稳 | AI 上线较晚 | 接受 |

### 理由

AI 生成必须被 `OperatorCatalog`、`GraphDraft`、schema validator、policy gate 和 compiler 约束。否则它只是把错误从用户手里转移到模型输出里。

### 失效条件

如果目标是纯 demo，可以提前展示 AI 生成，但必须明确是候选草稿，不可发布。

## 13. 当前确认与待确认

### 已确认为推荐方向

1. 采用 GraphDraft。
2. visualLayout 只做展示。
3. OperatorDefinition 作为画布算子合同。
4. ResourceDesignContract 与 ResourceDescriptor 分离。
5. ResourceDescriptor 投影为 VirtualOperator。
6. 服务端校验为权威。
7. 缺 payload schema 降级为 opaque。
8. gateway 先做 MVP，长期对齐 graph-engine。

### 仍需用户确认

1. 第一批目标用户是开发者、解决方案架构师、业务运营，还是混合角色？
2. Phase 1 是否必须保存草稿，还是允许纯临时 composer？
3. 用户提供算子库第一优先来源是否确认：`ResourceDescriptor + ResourceDesignContract`，然后是 JSON/YAML catalog？
4. 是否需要把 OpenAPI 导入纳入 Phase 1，作为补齐 payload schema 的主要入口？
5. `opaque` 输出是否默认阻断发布，还是允许租户策略放宽为 warning？

## 14. 实现影响

如果这些决策成立，Phase 1 最小实现应按
[BLOGE 可视化编排 Phase 1 实现蓝图](./bloge-visual-orchestration-phase1-implementation-blueprint.md)
拆分。核心对象包括：

- `ResourceDesignContract` record 和存储。
- `ResourceVirtualOperatorProjector`。
- `OperatorDefinition` DTO。
- `GraphDraft` DTO。
- `VisualDiagnostic` DTO。
- `GraphDraftValidator`。
- `GraphDraftDslGenerator`。
- `/api/visual/operators`。
- `/api/visual/drafts/*`。
- catalog-driven palette/editor。
- 现有 composer 从前端拼 DSL 迁移到服务端生成 DSL。

## 15. 决策回看触发器

以下事件发生时必须重审本文：

1. 首批用户明确要求大量既有 DSL 反向导入。
2. `ResourceDesignContract` 与 `ResourceDescriptor` 长期 1:1 同步，分离造成明显维护成本。
3. graph-engine 已经提供完整 authoring plane，gateway 内 visual 包出现重复建设。
4. `opaque` 策略导致用户无法迁移关键资源。
5. AI authoring 成为首要入口，而不是辅助入口。
6. 业务流程从 request-response API 编排扩展到长运行人工任务和 durable waits。
