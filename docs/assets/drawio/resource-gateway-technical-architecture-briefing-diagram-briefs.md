# Resource Gateway 技术架构汇报图形任务书

本文档固定关键图的决策问题、阅读路径和视觉语义。图中文字先在此处审定，再进入 Draw.io；`.drawio` 文件是图形结构和架构语义的唯一事实源。

## 1. 业务能力镜像目标架构

- **决策问题**：业务能力如何从创作入口进入治理、运行、测试证据和外部系统边界？
- **一句话结论**：ANEKE 治理面管理资产、正确性与发布裁决，Resource Gateway 承担创作和运行；测试与证据面保存受控投影和可验证事实，不持有原始业务载荷。
- **阅读顺序**：使用入口 → ANEKE 治理面 → Resource Gateway 创作与运行面 → 测试与证据面 → 客户外部系统与权威事实；最后查看证据回流与发布门禁。
- **节点清单**：Author Canvas、业务工作台、SDK / CLI / CI、SRE / 安全 / 审计、Contract & Asset Registry、Scenario / Assertion Governance、Workbook & Publish Gate、Capability Projection、MirrorPlan Compiler、Rehearsal Orchestrator、BLOGE DAG Runtime、Trace & Evidence Export、Observation Admission、Corpus Payload Vault、External Leaf Resolver、Session State World、Shadow & Outcome Connector、业务 API / 事件 / 日志、权威业务结果。
- **关系清单**：业务请求与资源调用、资产变更与治理控制、隔离执行与外部解析、Trace / Coverage / Evidence 回流、发布门禁反馈。
- **视觉语义**：使用 Draw.io `corporate` 预设；蓝色表示运行服务，紫色表示治理与安全，橙色表示网关控制，绿色表示数据与证据，灰色虚线表示外部边界。
- **交互需求**：HTML 默认以 1:1 比例阅读，支持适应宽度和独立横向滚动；图内保留图例。
- **验收条件**：读者能够区分治理面、运行面、测试证据面和外部事实边界，并识别主业务流与证据反馈方向。

## 2. DSL 资产生产架构

- **决策问题**：运营意图、精简 YAML、样例推断和存量能力如何收敛为唯一标准合同？
- **一句话结论**：多种人类友好的创作入口共享一个 Authoring Surface；纯编译器只执行确定性转换，Canonical Contract 是治理和运行时共同消费的唯一事实。
- **阅读顺序**：创作与发现入口 → 统一 Authoring Surface → Authoring Control Plane → Canonical Contract & Runtime Plane → 诊断回流。
- **节点清单**：图形化 Builder、精简 YAML、自动发现 / 推断、存量能力目录、Advanced Canonical Editor、Author Workspace、Library Workbench、Deterministic Authoring Compiler、Source Adapters、Authoring Catalogs、Fingerprint Cache、Canonical Library、Runtime Parity、Publish / Test Gate。
- **关系清单**：输入事实进入统一创作面，编译器生成标准合同，运行时一致性和发布门禁反馈创作状态；推断信息在确认之前不进入生产事实。
- **视觉语义**：使用 Draw.io `corporate` 预设；蓝色表示创作入口，橙色表示编译控制，绿色表示标准合同与运行时，紫色表示高级编辑和治理确认，红色表示阻断。
- **交互需求**：HTML 提供标签切换、1:1 阅读和适应宽度；图外文字只说明决策问题和结论。
- **验收条件**：读者能够指出创作便利层、确定性编译器和运行时事实的责任边界，并理解多入口不会形成多套合同。

## 3. 确定性编译与原子导入

- **决策问题**：推断信息如何在不越权的前提下进入可发布 DSL 资产？
- **一句话结论**：编译器只展开已声明或已确认的事实；低置信推断必须形成 Confirmation Record，通过治理门禁后才能原子导入。
- **阅读顺序**：Authoring Document / Inference Evidence / Confirmation Records → Safe Parse → Normalize AST → Resolve Types → Semantic Validation → Reference Resolution → Assemble Contract Library → Compute Fingerprint → Governance Gate → Atomic Import。
- **节点清单**：输入事实、Pure Authoring Compiler、标准合同输出、Governance Gate、Reject Receipt、Atomic Import、Source Pointer Projection、Diagnostic Mapping。
- **关系清单**：蓝色主线表示确定性编译，红色表示失败关闭，紫色表示诊断回映射和人工确认，绿色表示可导入标准合同。
- **视觉语义**：使用 Draw.io `corporate` 预设；阶段容器保持中性，颜色只区分输入、控制、结果和阻断语义。
- **交互需求**：HTML 允许按原始尺寸横向阅读，避免把编译阶段和诊断文字整体缩小。
- **验收条件**：读者能够识别哪些步骤是纯函数转换、哪些事实需要授权确认，以及失败发生后如何定位回原始创作字段。

## 4. 执行数据与测试控制面

- **决策问题**：Fixture、替身和控制计划如何进入测试运行，同时不污染 GraphContext 或触达生产依赖？
- **一句话结论**：测试控制面先生成不可变 EffectiveExecutionPlan；隔离运行面通过 ExecutionOptions 与 OperatorResolverChain 执行受控替换，生产依赖对测试目的硬拒绝。
- **阅读顺序**：Trusted Callers → Identity & Purpose Gate → Suite / Fixture Registry 与 Target Snapshot → Execution Control Compiler → Safety Preflight → EffectiveExecutionPlan → Test Runtime API → BLOGE Engine → Trace / Assertions → Signed TestRunEvidence。
- **节点清单**：Author Canvas、CI / CLI / VSCode、ANEKE Tool Studio、Identity & Purpose Gate、Suite / Fixture Registry、Target Snapshot、Execution Control Compiler、Safety Preflight、EffectiveExecutionPlan、Test Runtime API、ExecutionServices、BLOGE Engine、Test Double Runtime、Frozen Real Runtime Bindings、Sandbox Dependencies、Invocation Trace & Assertions、Signed TestRunEvidence、Production Dependencies。
- **关系清单**：调用方提交测试请求，控制面编译并签发计划，运行面执行真实或替代绑定，轨迹和断言形成签名证据；红色虚线表示生产依赖拒绝边界。
- **视觉语义**：使用 Draw.io `corporate` 预设；橙色表示控制与 API，蓝色表示运行服务，绿色表示已批准计划和证据，紫色表示身份和执行服务，红色表示拒绝边界。
- **交互需求**：默认 1:1 阅读，支持适应宽度；图内保留控制面、数据面和拒绝边界图例。
- **验收条件**：读者能够说明测试控制为何不进入业务数据、替身在哪一层生效，以及生产目的如何失败关闭。

## 5. 可测试性与正确性证据链

- **决策问题**：如何证明一项业务资产不仅能运行，而且局部、组合、环境和晋级语义都正确？
- **一句话结论**：不可变测试资产支撑局部正确性、组合正确性和环境回归；语义覆盖、签名 Evidence 与独立验证共同形成治理可消费的证明链。
- **阅读顺序**：Immutable Test Assets → Local Correctness → Composition Correctness → Environment and Regression → Correctness Proof and Governance；同时沿底部 Execution Data Control Plane 与 Orchestratable Semantic Coverage 横向阅读。
- **节点清单**：Formal Contracts、FixtureBundle、Frozen Target、Coverage & Promotion Policy、L0–L6 正确性层级、Effective Plan + Trace、Semantic Coverage、Signed TestRunEvidence、ANEKE Workbook / Publish Gate。
- **关系清单**：测试资产冻结目标与依赖，执行控制面记录真实解析结果，语义覆盖汇总路径和规则，签名 Evidence 绑定指纹，独立验证结果进入发布治理。
- **视觉语义**：使用 Draw.io `corporate` 预设；蓝色表示局部执行，橙色表示组合验证，紫色表示环境回归，绿色表示证据与治理结果，红色表示生产观察边界。
- **交互需求**：HTML 支持标签切换和两种阅读比例；图外结论不重复图中各证明层说明。
- **验收条件**：读者能够解释测试通过、覆盖满足、Evidence Verified 和 Promotion Eligible 之间的区别。

## 6. 意图驱动的运营工作流

- **决策问题**：业务资产 DSL 化后，运营人员与编码智能体如何分工，才能减少逐字段配置并提高变更正确性？
- **一句话结论**：运营人员负责意图、业务 Oracle、风险和批准，编码智能体复用软件工程工具链生成与修订资产，平台以确定性校验和 Evidence 约束结果。
- **阅读顺序**：表述业务意图 → 读取资产上下文 → 生成变更集 → 编译与影响分析 → 隔离验证 → 确认业务 Oracle → 批准晋级；失败按诊断类型返回编码智能体，高风险或语义不确定项返回授权人员。
- **节点清单**：业务意图、Catalog / Schema / Policy、DSL / Scenario / Fixture 变更集、Compile / Diff / Impact、Isolated Run / Evidence、确认 Oracle 与风险、批准晋级、诊断与修订。
- **关系清单**：蓝色实线表示主交付流，灰色虚线表示验证证据，红色虚线表示确定性失败回到诊断，琥珀色虚线表示语义不确定和高风险人工确认。
- **视觉语义**：按「业务 Owner / 运营」「编码智能体」「平台 / 质量」三条泳道组织；绿色节点表示已形成可验证证据或批准结果。
- **交互需求**：HTML 在窄屏提供横向滚动；图内包含主路径与两类反馈说明。
- **验收条件**：读者能够明确编码智能体不定义业务正确答案，且失败返回具体 DSL、Scenario、Fixture、Binding 或 Policy 资产。

## 7. 从业务意图到可发布资产的协作工作流

- **决策问题**：业务、编码智能体、平台工程和质量治理如何围绕同一组资产完成发布，而不产生线下第二套事实？
- **一句话结论**：四类角色围绕 Contract、Scenario、Fixture、Plan 与 Evidence 协作，所有失败都返回对应资产修改，晋级由业务批准与质量门禁共同决定。
- **阅读顺序**：定义结果 → 生成并创作资产 → 编译与构建测试资产 → 生成执行计划 → 隔离执行与验证 Evidence → 批准并发布。
- **节点清单**：业务 Owner / 运营、编码智能体、平台 / 集成工程、质量 / 治理；六个阶段为定义、创作、编译、计划、验证、发布。
- **关系清单**：每条泳道内部为阶段推进；跨泳道关系体现 Oracle 确认、测试资产供给、Evidence 验证和发布批准；底部反馈带说明失败定位规则。
- **视觉语义**：蓝色表示创作与执行，紫色表示编码智能体产物，琥珀色表示门禁，绿色表示 Evidence 和发布结果；反馈使用红色虚线。
- **交互需求**：HTML 提供横向滚动和图例，桌面端一屏可读，移动端可逐段查看。
- **验收条件**：读者能够从任一失败类型定位责任资产与下一步动作，并理解业务批准不能被单一测试通过替代。
