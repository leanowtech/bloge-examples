# Resource Gateway 能力设计工作台产品与技术演进方案

> 文档状态：Proposed for Review；Stage 0 开发证据持续收口
>
> 日期：2026-08-18
> 适用范围：Resource Gateway 浏览器端、VS Code 轻量宿主、Author Canvas、Correctness Studio、Business Mirror、Testing Control Plane  
> 核心目标：让业务人员可以连续完成「定义业务接口 → 准备可复现数据 → 编排业务特征 → 交付契约化工具 → 隔离试跑并沉淀证据」

相关文档：

- [正确性定义与测试数据配置 UX 演进方案](resource-gateway-correctness-authoring-ux-audit-and-evolution-plan.md)
- [Contract & Scenario Authoring 演进计划](resource-gateway-contract-scenario-authoring-evolution-plan.md)
- [引导式正确性与业务镜像改进方案](resource-gateway-guided-correctness-and-business-mirror-ux-technical-evolution-plan.md)
- [客户业务能力镜像蓝图差距与技术演进方案](resource-gateway-customer-business-mirror-blueprint-gap-and-technical-evolution-plan.md)
- [Resource Gateway 产品手册](resource-gateway-product-manual.md)
- [Resource Gateway 业务能力镜像与保真演练方案](resource-gateway-mock.md)

## 0. 结论先行

本轮反馈指出的不是一个局部交互问题，而是当前产品模型仍然以技术模块为中心。用户需要先理解 Graph、Operator、Schema、Fixture、Mock、Scenario、Correctness、Business Mirror 和 Run Evidence，再自行拼出一条工作流。底层能力很多，产品却没有形成稳定的业务心智。

建议将 Resource Gateway 的默认生产体验收敛为 **能力设计工作台（Capability Studio）**。工作台只向普通作者暴露三类一等业务资产：

1. **业务接口**：说明可以查询或执行什么业务动作，接收什么，返回什么，失败时如何表现。
2. **业务特征**：把多个接口和已有特征加工为可复用的业务判断依据，DAG 是其主要实现视图。
3. **业务工具**：面向 Agent、SOP、Workflow 或业务应用交付的完整能力，拥有独立输入、输出、错误和副作用契约，并可复用前两类资产。

三类资产共用一个 **场景数据资产中心**。中心不只是保存 JSON，而是管理：

- 某个业务条件下应使用什么输入；
- 每个外部依赖在该条件下应如何表现；
- 正确结果是什么，依据是什么；
- 数据从哪里来、适用于哪个契约版本、是否脱敏、是否过期；
- 数据覆盖了哪些业务分支，发现过哪些缺陷；
- 哪个精确版本已经被评审、运行和证明。

产品语言需要同步改变。默认界面不再要求业务人员「配置 Fixture 和 Mock Rule」，而是让其完成：

```text
定义业务边界
  -> 准备场景数据
  -> 设置依赖表现
  -> 在隔离环境试跑
  -> 检查业务结果和覆盖
  -> 保存为可复用验证资产
```

`FixtureBundle`、selector、fingerprint、revision 和 raw schema 继续作为高级模式、API 与治理协议存在，但不再支配主操作流。

### 0.1 五项核心决策

1. **不新建第二套执行引擎。** 新增面向业务作者的资产投影、编译器和任务式 UI，继续复用 GraphDraft、ScenarioDraftSetV2、FixtureBundle、TestSuite、BLOGE Runtime 和 Evidence。
2. **接口、特征和工具都必须具备独立契约。** DAG 是实现，不是契约；节点能运行不代表能力可以被稳定集成。
3. **场景数据是一级业务资产。** 数据集、场景行、依赖表现、Oracle、来源、质量和生命周期不能继续散落在表单与 JSON 字段中。
4. **隔离运行由显式 Binding Plan 控制。** 每个依赖在一次运行中使用真实服务、替身数据、回放或拒绝访问，必须可见、可审计、可锁定；生产环境默认物理排除调用方注入替身的能力。
5. **第一条纵向切片使用「取消费争议处理工具」。** 它与现有 Business Mirror 样例一致，又能同时展示多接口、特征 DAG、工具契约、故障降级和测试数据积累。

### 0.2 评审时应优先确认

- 是否接受「业务接口 / 业务特征 / 业务工具 / 场景数据」作为默认产品词汇。
- 是否接受能力设计工作台成为统一创作入口，现有 Author、Correctness Studio 和 Business Mirror 作为上下文视图保留。
- 是否接受场景数据与依赖表现分离建模，避免把输入样本、Mock 规则和预期结果压在同一个 Fixture 对象中。
- 是否接受测试与生产运行面物理隔离，生产 API 不暴露任意 Fixture/Mock override。
- 是否接受以取消费争议为首个完整黄金案例，而不是继续堆积互不相干的技术样例。

### 0.3 交付方法修正：先建立可执行验收基线

过去多轮修补效率偏低，不是团队缺少方案，而是「目标产品体验」没有先被固化为可操作、可运行、可自动检查的参考成品。页面开发开始后，反馈只能以局部缺陷进入，导致组件持续变多，整体任务链仍然漂移。

本方案增加一项最高优先级约束：**Stage 1 正式实施前，必须先形成并签署 `Capability Studio Acceptance Baseline v1`。** 验收基线至少包含：

1. 可点击的高保真原型，而不只是线框图和文字说明；
2. 逐屏黄金路径验收合同，写清动作、反馈、数据和完成标准；
3. 版本化黄金演示数据包，所有引用和业务预期闭合；
4. Dataset 编译、DAG 数据透镜和生产隔离三项技术 Spike 证据；
5. Playwright、协议测试和视觉检查的自动化骨架；
6. 业务、产品、UX、架构、安全与 QA 的 Go/No-Go 记录。

验收基线不是静态 PRD。它必须能被人工走查，也能被自动化测试消费。任何实现变更若改变黄金路径、页面反馈或数据语义，必须先修改并重新批准验收基线，不能先补代码、再追认预期。

### 0.4 验收不是“功能清单完成”

本方案中的每一项验收必须同时回答六个问题：**谁在什么前置条件下执行什么动作、看到什么结果、系统保持什么不变量、用什么证据复验**。只有页面、接口或测试代码已经存在，不构成验收通过。

每条 Acceptance Contract 必须具备以下字段：

| 字段 | 约束 |
|---|---|
| Requirement ID | 使用稳定的 `GP-*`、`SPIKE-*`、`SEC-*`、`NFR-*` 或 `S*-AC-*`，不得以 PR、组件名代替 |
| Preconditions | 固定候选构建、Baseline、Demo Pack、Scope、语言、视口和运行模式 |
| User outcome | 业务角色可以观察并解释的结果，不以 HTTP 200 或按钮可点击代替 |
| System invariant | exact ref、契约闭包、确定性、零真实调用、权限和数据安全等不可破坏条件 |
| Evidence | 可重放测试、协议结果、运行证据、视觉基线、可用性记录和内容 fingerprint |
| Owner and decision | 责任角色、判定时间、`PASS`/`FAIL` 及限制；签署不能由构建脚本代填 |

验收状态只允许按以下语义使用：

- `NOT_RUN`：尚未执行，不能推断结果。
- `FAIL`：已执行但至少一个不变量或期望不成立。
- `PARTIAL`：已有开发证据，但完整前置、矩阵、人工签署或端到端链路尚未闭合；**不得用于阶段退出**。
- `PASS`：所有自动和人工证据闭合，且没有未关闭的 P0/P1 问题。
- `BLOCKED`：存在明确外部阻断，必须记录 Owner、解除条件和复验入口；不能用来隐藏失败。

不设置“默认通过”或口头豁免。测试跳过、证据过期、引用 fingerprint 不匹配、签署缺失和环境不符合前置条件时，一律不能生成 `PASS`。

#### 0.4.1 唯一通过公式

一个 Acceptance Contract 只有在以下条件**同时成立**时才能判定为 `PASS`：

```text
PASS = 前置条件全部满足
    AND 用户结果逐项符合 Oracle
    AND 系统不变量全部保持
    AND 约定测试矩阵 100% 执行且无跳过
    AND 机器证据可解析、可复算且 fingerprint 一致
    AND 指定 Owner 全部签署
    AND 未关闭 P0/P1 = 0
```

其中任一业务 Oracle、安全或隔离不变量不成立，结果直接为 `FAIL`，不能用其他成功项抵消；因环境、权限或证据设施不可用而无法执行时为 `BLOCKED`，不能记为“有条件通过”；只完成组件测试、开发截图或部分矩阵时为 `PARTIAL`，不能用于阶段退出。

#### 0.4.2 验收对象与失败边界

| 验收轴 | 最低通过阈值 | 直接失败条件 | 证据 |
|---|---|---|---|
| 业务正确性 | Canonical 9 Case × 3 轮全部符合逐 Case Oracle；同 Case 三轮业务结果 fingerprint 一致 | 任一 Case 错判、漏跑、重复 `runId`、结果漂移或 Oracle 被跳过 | Run Evidence、Oracle 明细、semantic fingerprint |
| 引用闭包 | Contract、Graph、Dataset、Case、Behavior、Binding 全部使用 exact ref，独立复算 fingerprint 一致 | 运行中读取 mutable head、跨 Scope 引用、混用版本或引用缺失 | closure manifest、Test Kit verifier |
| 隔离运行 | 进程内 connector counter 与部署级 egress 观测均为 0；所有依赖行为可追溯到 Binding Plan | 任一未声明真实调用、未解析依赖继续运行、生产面可注入任意替身 | network deny、counter、装配测试、审计 |
| 故障语义 | timeout、retry、fallback、skip、partial 在 UI、Trace、Oracle 中含义一致 | 把超时当空数据、fallback 后丢失原始失败尝试、最终状态与业务结果矛盾 | attempt/final trace、Data Lens、错误注入测试 |
| 操作体验 | 5/6 代表性用户在 15 分钟内独立完成 `GP-01` 至 `GP-10`；默认路径技术 ID 与 Raw JSON 输入均为 0 | 任一 P0/P1；主任务必须依赖主持人口头补步骤；关键状态无恢复动作 | 原始可用性记录、浏览器录像、任务计时 |
| 视觉与可访问性 | 中英文 × 1440/1024/390 关键视口完成；键盘主路径可达；axe serious/critical 为 0 | 控件、节点、边标签或错误反馈被遮挡；焦点丢失；仅靠颜色表达状态 | Playwright、DOM 几何、截图、人工签署、axe |
| 安全与治理 | Payload 权限、脱敏、审计、保留、撤销与 Scope 负向用例全部通过 | 明文泄漏、越权访问、撤销后仍可新运行、日志或 Evidence 泄漏 Payload | RBAC/ABAC、攻击 fixture、日志扫描、审计 |
| 企业兼容性 | 冻结协议矩阵中的 N/N-1/N+1 全部产生确定的兼容或拒绝结果 | 静默丢字段、错误降级、无法判断版本兼容性 | consumer contract、capability probe、兼容矩阵 |

容量、延迟、并发、保留和恢复目标必须在 Stage 0 以客户规模与环境基线冻结为数值化 SLO；未冻结前相关 NFR 只能是 `NOT_RUN`，不得用“性能基本可接受”替代。

#### 0.4.3 证据有效期与重新验收触发器

以下任一变化都会使受影响 Acceptance Result 立即失效并回到 `NOT_RUN`：候选 build、业务 Oracle、Contract/Schema、Graph/DSL、Dataset/Case/Behavior、Binding、权限策略、生产装配、网络策略、协议主次版本或目标运行环境发生变化。仅文案和视觉改动也必须重跑受影响的 UX、可访问性和视觉合同；不得沿用旧截图签署新版本。

每次重新验收只重跑追踪矩阵证明受影响的合同，但影响分析本身必须可审计。若无法证明影响边界，则重跑该 Stage 全部合同。

#### 0.4.4 三级验收结论，禁止跨级宣称

为避免“自动化全绿”被误解为“业务已可发布”，所有进度、页面和汇报必须使用以下三级结论：

| 级别 | 允许证明的事实 | 不允许的宣称 | 晋级条件 |
|---|---|---|---|
| `DEVELOPMENT_VERIFIED` | 固定开发数据、test/staging 装配与自动化矩阵符合开发合同 | 不得宣称发布候选、客户业务正确或 Stage 退出 | 严格 Schema、跨字段 verifier、成功/失败关闭、真实浏览器或运行测试全部通过 |
| `CANDIDATE_VERIFIED` | 不可变候选构建在目标验收环境中经过完整机器矩阵 | 不得代替业务、安全、UX 和发布责任人签署 | 满足 `AC-PRE-01` 至 `AC-PRE-05`；exact closure、环境指纹、部署级 egress 和不可变 Evidence 闭合 |
| `ACCEPTED` | 指定 Stage 的产品、业务、工程、安全和体验验收已完整成立 | 不得扩大到未被当前 Baseline 覆盖的客户、Scope 或运行环境 | 本 Stage 全部 `S*-AC-*` 为 `PASS`，P0/P1 为 0，指定 Owner 全部签署，Manifest 状态为 `ACCEPTED` |

页面必须同时呈现“已证明什么”和“仍不能证明什么”。例如受治理 9 × 3 开发验证可以显示“27 项检查全部通过”，但必须并列显示“发布仍不可验收”及未闭合的具体理由。

#### 0.4.5 单条合同的可执行记录

每条 `GP-*`、`SPIKE-*`、`SEC-*`、`NFR-*` 和 `S*-AC-*` 都必须生成一条可机器读取的 Acceptance Result，至少固定以下内容：

```text
contractId + contractRevision
candidateRef + candidateFingerprint
baselineRef + demoPackRef + exactClosureFingerprint
environmentFingerprint + profile + Scope + actor
testMatrixExpected + testMatrixExecuted + skippedCount
observations + invariantResults + unresolvedIssues
evidenceRefs + evidenceFingerprints
decision + decidedBy + decidedAt
```

可执行矩阵必须精确相等，不接受“至少跑一部分”。`testMatrixExecuted != testMatrixExpected`、`skippedCount > 0`、Evidence 无法解析、指纹无法复算或 Owner 缺席时，不得产生 `PASS`。测试用例数是某次候选的观测值，不是永久产品指标；永久合同是冻结矩阵中的所有义务均被执行且无跳过。

#### 0.4.6 共用可执行验收标准

以下标准是所有 `S*-AC-*` 的强制基线。Stage 合同可以增加约束，但不能降低或省略这些约束。每项标准必须在 Acceptance Result 中记录 `PASS`、`FAIL`、`BLOCKED` 或 `NOT_RUN`，不得只写说明文字。

| 标准 ID | 必须成立的判据 | 机器或人工证据 | 直接失败条件 |
|---|---|---|---|
| `AC-STD-01 CANDIDATE_IDENTITY` | 被测对象绑定部署侧冻结的 `buildRef`、revision、Git commit、`CLEAN` source tree 和实际 JAR/镜像 SHA-256；请求方不能覆盖 | Candidate attestation、制品摘要、启动配置快照、负向注入测试 | 部分配置、工作区脏、摘要不匹配、请求可替换候选身份 |
| `AC-STD-02 EXECUTION_INTENT` | 每次运行把候选、Suite exact ref、publication、compilation 和 source map 绑定为同一 canonical intent fingerprint | `candidateIntentFingerprint`、Test Kit 独立重算、篡改测试 | 任一坐标漂移、指纹不能重算、运行证据未回显同一 intent |
| `AC-STD-03 MATRIX_COMPLETENESS` | 冻结矩阵 100% 执行，`skippedCount=0`；批量运行的 Case、轮次和 Run ID 集合精确相等 | expected/executed matrix、唯一性检查、批量运行收据 | 漏跑、跳过、重复 Run ID、额外 Case 或轮次不足 |
| `AC-STD-04 BUSINESS_CORRECTNESS` | 每个 Case 的业务 Oracle 通过；同 Case 多轮 semantic fingerprint 一致；高风险分支有专项证明 | Oracle 明细、semantic fingerprint、timeout/duplicate/forbidden-write 证明 | 任一 Oracle 失败、结果漂移、用技术成功替代业务正确 |
| `AC-STD-05 EXACT_CLOSURE` | Contract、Graph、Dataset、Case、Behavior、Binding、Fixture 和 Suite 全部使用 exact ref，内容指纹可独立复算 | closure manifest、Registry 写后回读、Test Kit verifier | mutable head、跨 Scope、缺失引用、内容与指纹不一致 |
| `AC-STD-06 ISOLATION` | 进程内 connector counter、部署级真实外呼数和被网络策略拒绝的外呼尝试数均为 0；未解析依赖在调度前失败 | counter、network deny、egress 日志、生产装配否定测试 | 任一真实外呼、任一被拒绝的外呼尝试、fallback-to-real、生产入口可注入替身 |
| `AC-STD-07 FAILURE_SEMANTICS` | timeout、retry、fallback、skip、partial 和 cancel 在 UI、Trace 与 Oracle 中语义一致；失败关闭不生成伪证据 | attempt/final Trace、失败投影 Schema、负向用例 | 超时被当成空数据、原始失败丢失、失败响应携带已验证结论 |
| `AC-STD-08 UX_ACCESSIBILITY` | 约定语言、视口、权限、页面状态和输入方式矩阵全部完成；主路径无技术 ID/Raw JSON；无 P0/P1 | 真实浏览器、DOM 几何、axe、人工读屏、可用性原始记录 | 遮挡、焦点丢失、仅靠颜色、主持人必须补步骤、无恢复动作 |
| `AC-STD-09 EVIDENCE_SIGNOFF` | Evidence 可解析、可回放、未过期且 fingerprint 一致；指定 Owner 在证据生成后签署 | 不可变 Evidence URI、Verifier 结果、签署主体与时间 | 证据缺失/过期/不可复算、签署早于证据、Owner 缺席 |

判定顺序固定为：先判断直接失败不变量，再判断前置阻断，再判断矩阵完整性，最后判断签署。命中任一直接失败条件即为 `FAIL`；前置设施不可用且尚未执行为 `BLOCKED`；尚未开始验收运行时为 `NOT_RUN`；九项全部通过后，单条正式合同才允许为 `PASS`。

`PARTIAL` 只用于开发进度台账和 v1 兼容投影，不属于正式 Stage 退出状态。只有开发证据或部分矩阵时，不得生成“半通过”的 Stage 退出结果：尚未开始正式运行时保持 `NOT_RUN`，前置条件不成立时为 `BLOCKED`，已经观测到不变量失败时为 `FAIL`。`DEVELOPMENT_VERIFIED` 同样只能作为开发证据级别，不能绕过 `AC-STD-01`、`AC-STD-06`、`AC-STD-08` 或 `AC-STD-09`。

#### 0.4.7 Stage 退出结果的机器合同

正式 Stage 退出结果使用 `Stage Acceptance Result v2`。该合同只接收 `STAGE_EXIT`，状态仅允许 `PASS`、`FAIL`、`BLOCKED` 和 `NOT_RUN`，并要求 `AC-STD-01` 至 `AC-STD-09` 恰好各出现一次。`NOT_RUN` 的执行开始与证据完成时间必须为 `null`，不得用相同时间或占位时间伪造“尚未执行”。`BLOCKED` 必须明确区分运行前阻断和运行中阻断：前者的两个执行时间均为 `null` 并携带 `RUN_NOT_STARTED`，后者的两个时间均存在且不得携带该诊断。`PASS` 必须绑定 `CLEAN` 候选、完整执行窗口、覆盖该窗口且未过期的目标环境证明、零真实外呼且零被拒外呼尝试的部署级 egress 观测，以及正确性、Runtime、QA 三类 Owner 签署。

根状态与九项检查不能互相矛盾。候选不干净或缺少环境证明时，`AC-STD-01` 不得为 `PASS`；缺少合格 egress 观测或观测到真实/被拒外呼尝试时，`AC-STD-06` 不得为 `PASS`；三类 Owner 未完整批准时，`AC-STD-09` 不得为 `PASS`。其余检查仍可以保留已经成立的 `PASS`，但每个不可成立的检查必须使用 `FAIL`、`BLOCKED` 或 `NOT_RUN` 如实记录，不能只在根级 `diagnostics` 中补一句说明。

签署前的 `evidenceClosureFingerprint` 必须由以下内容确定性复算：结果身份与 revision、结果类型和状态、合同身份与 revision、完整候选执行绑定、完整环境证明投影或 `null`、完整 egress 投影或 `null`、九项验收检查，以及签署前 Evidence 目录。`decidedAt`、诊断、签署对象和闭包字段自身不参与计算；Owner 签署引用在闭包生成后产生，并绑定同一个闭包指纹。该顺序用于避免“签名指纹又参与被签名内容”的循环依赖。

Test Kit 的默认 v2 verifier 只负责严格 Schema、状态机、时间窗口、引用闭包、指纹复算和签署坐标绑定，不能自行证明外部 Evidence 真实存在，也不能验证签名公钥、签发机构权限或 Owner 身份。正式 `ACCEPTED` 还必须由受信 Evidence Resolver、受信 Key Set/Issuer Policy 和 Owner Authority 完成外部真实性校验。只有默认 verifier 通过时，结论仍是“Stage 退出结果在语义上自洽”，不得据此把当前 Manifest 从 `NO_GO` 改为 `ACCEPTED`。

#### 0.4.8 线协议到真实界面的闭环验收

后端对象测试、JSON Schema、独立 Test Kit 和前端静态 fixture 测试分别通过，仍不能证明产品可用。每个进入黄金路径的读模型必须增加一条 **Wire-to-UI Acceptance**：使用实际 Spring 服务生成的原始响应字节，由生产前端通过真实认证头和 purpose 读取，再由真实浏览器完成解析、渲染、交互、几何与可访问性验证。禁止用手工复制 JSON、前端本地常量或测试专用 adapter 代替这条链路。

| 闭环步骤 | 明确通过标准 | 直接失败条件 | 证据 |
|---|---|---|---|
| 服务端出线 | 实际 Controller 响应通过公开 Schema；内容 fingerprint、稳定排序和跨字段不变量可由 Test Kit 从原始 bytes 独立复算 | 只验证 Java 对象、序列化后字段漂移、Schema 与实现使用不同字符集或基数规则 | HTTP 集成响应、Schema 结果、Test Kit 结果 |
| 前端入线 | 生产 API client 携带与部署一致的 credential/purpose；严格 parser 直接接受真实响应，并拒绝未知字段、非法关系和跨 Scope 引用 | 漏认证头、前端 fixture 与真实响应不同、把多 Authority 误判为跨 Scope、合法边 ID 无法解析 | API client 测试、真实网络请求、parser 正负用例 |
| 用户结果 | 页面显示合同规定的业务事实；主操作改变精确选择与影响闭包，刷新或恢复不产生近似本地结论 | 页面只“加载成功”但关键数量/阻断/边界不符；选择后路径不变；API 失败后用常量补图 | DOM 业务断言、交互后节点/边集合、错误态断言 |
| 视觉与无障碍 | 冻结视口无页面横向溢出、无遮挡；axe serious/critical 为 0；状态主次不以降低文字可读性实现 | 用整体 opacity 弱化导致文字对比度失败；移动端信息被裁切；仅靠颜色表达选中或阻断 | DOM 几何、axe、真实 Chrome 截图、人工视觉复核 |
| 证据落盘 | 测试命令、候选指纹、响应/截图路径和精确观察值进入追踪矩阵；失败结果保留并关联修复 | 只留口头结论或成功截图；无法知道截图对应哪个候选和响应 | Acceptance Result、Evidence ref/fingerprint、失败记录 |

Wire-to-UI 通过只允许把该纵向切片标为 `DEVELOPMENT_VERIFIED`。目标环境、部署级隔离、人工可用性和 Owner 签署未闭合时，仍不得生成 `CANDIDATE_VERIFIED` 或 `ACCEPTED`。

## 1. 从技术诉求到产品任务

### 1.1 用户真正要完成的事

| 技术描述 | 用户真正的问题 | 默认产品语言 | 产品动作 |
|---|---|---|---|
| 定义 input/output schema | 这个能力能接收什么，保证返回什么 | 定义业务边界 | 从样例生成、表单编辑、导入 OpenAPI、进入高级 Schema 模式 |
| 配置 fixture | 在一个确定场景里，各项已知事实是什么 | 准备场景数据 | 新建场景、选择数据集、导入样例、派生边界数据 |
| 配置 mock 桩 | 外部服务在本次演练中应如何表现 | 设置依赖表现 | 选择正常返回、业务错误、超时、序列返回、状态变化或禁止访问 |
| 脱离真实接口运行 | 我能否在不等待其他团队环境的情况下验证流程 | 隔离试跑 | 运行前显示真实/替身清单，缺少替身时失败关闭 |
| 多 API DAG 编排 | 多个业务事实如何被加工为稳定特征 | 编排特征加工链 | 拖入接口和特征，连线映射字段，选择场景查看边上数据 |
| 工具契约化 | 上层 Agent 或 Workflow 如何稳定调用该工具 | 定义工具承诺 | 定义输入、输出、错误、副作用、SLA 和兼容策略 |
| 维护 fixture/mock 数据集 | 如何把业务理解沉淀为可复用、可治理的资产 | 管理场景数据资产 | 版本、来源、质量、覆盖、影响、审批、退役和复用 |

### 1.2 当前成熟度偏低的病根

#### 病根 A：资产按实现模块分散

接口定义在算子库，Graph 在 Author Canvas，场景在 Correctness Studio，Fixture 在测试数据面板，业务包在 Business Mirror，运行结果又进入 Rehearsals 或 Evidence。每个页面本身可能合理，但用户没有一个稳定对象可以从头做到尾。

**根治方式**：建立统一 `CapabilityAsset` 聚合和能力工作区。用户始终围绕当前接口、特征或工具工作，契约、编排、场景数据、运行和证据成为该资产的任务视图。

#### 病根 B：底层协议对象直接成为界面对象

`FixtureBundle`、`NodeFixture`、selector、revision、fingerprint 和 ID 都是必要的工程概念，但不是普通作者的业务任务。直接暴露会增加认知成本，也容易形成「填完 JSON 就完成正确性定义」的错误心智。

**根治方式**：默认视图使用业务语义，技术坐标通过详情渐进披露。所有可选引用使用主动筛选器，不要求手工输入 ID。

#### 病根 C：契约、实现和验证没有形成连续状态机

当前用户可能先搭图、再补 Schema、临时填 Fixture、运行一次后离开。系统没有强制回答：这次运行绑定了哪个契约、使用了哪些数据版本、是否覆盖了必须验证的分支、结果为何正确。

**根治方式**：每类能力共用以下 Readiness 轴：

```text
业务定义 -> 契约 -> 依赖闭包 -> 场景数据 -> 业务预期 -> 隔离运行 -> 当前证据
```

这些轴不能合并为一个模糊百分比。每个阻断项必须提供原因、影响、主动作和完成标准。

#### 病根 D：数据只是字段值，没有成为资产

同一份订单样本可能被复制到 API Fixture、Graph Scenario、Operator Test Case 和 Business Mirror 包中。复制后没有统一来源、版本、适用契约和影响分析。数据越积累，重复与漂移越严重。

**根治方式**：以 `ScenarioDataset` 为聚合根，场景行引用精确 `DataCaseRevision` 和 `DependencyBehaviorProfileRevision`；不同能力只引用，不复制。

#### 病根 E：画布只展示结构，不能帮助理解数据

多 API DAG 的价值不只是看节点和线，而是回答：某个场景下数据从哪里来，经过什么映射，为什么得到这个特征。若边上没有字段与样例值，画布仍然需要用户在多个面板之间推理。

**根治方式**：为 DAG 增加「数据透镜」。选择场景后，节点显示输入/输出摘要，边显示字段映射和样例值，失败时直接在首个漂移位置显示预期/实际差异。

## 2. 目标产品定位与边界

### 2.1 产品定位

能力设计工作台是 Resource Gateway 面向业务作者的统一生产面：

> 用契约定义能力边界，用 DAG 表达加工和组合，用场景数据控制依赖表现，用隔离运行证明业务正确性，再把精确资产和证据交给治理系统。

它不是另一个 API 管理平台，也不是另一个测试管理平台。其差异化价值是把「资源接入、能力编排、业务模拟和正确性证据」放在同一个可执行资产生命周期中。

### 2.2 目标用户

| 角色 | 主要任务 | 默认视图 |
|---|---|---|
| 业务能力设计者 | 定义接口含义、业务特征、工具结果和场景分母 | 业务表单、DAG、场景矩阵 |
| 正确性 Owner | 审核业务预期、覆盖分母、数据质量和证据 | Readiness、Coverage、Oracle、Evidence |
| 接口 Owner | 维护真实绑定、错误语义、兼容性和 SLA | Contract、Binding、Impact |
| 研发与测试 | 实现算子、诊断差异、批量验证、接入 CI | 高级 Schema、DSL、Run Trace、Test Kit |
| 治理与平台团队 | 消费精确资产、证据、Owner、风险与状态 | Export Bundle、Protocol、Deep Link |

### 2.3 明确不做

- 不复制 ANEKE 的 registry、发布门禁、TEE 治理和组织审批权。
- 不建设通用主数据治理或生产数据湖。
- 不允许自动生成的数据直接成为已认证业务事实。
- 不把一次运行成功等同于业务正确、覆盖充分或允许发布。
- 不允许生产调用方任意指定 Fixture、Mock 或 fallback-to-real。
- 不用一个巨型 `Capability` JSON 取代现有领域模型；统一的是投影协议和体验，不是所有内部存储。

### 2.4 当前能力与目标差距

| 能力域 | 当前基础 | 主要差距 | 改造性质 |
|---|---|---|---|
| 业务接口 | Operator Schema、Resource Descriptor、`HttpResourceOperator`、算子级测试 | 以技术算子为中心，缺少业务语义、样例优先契约和连续试跑动线 | 产品投影与 UX 重组为主 |
| 业务特征 | GraphDraft、DSL、built-in function、`BusinessAssetRef.Kind.FEATURE` | Feature 不是独立工作区，字段语义、血缘和数据透镜不完整 | 新增一等资产与画布能力 |
| 业务工具 | Graph Contract、Capability Proposal、DAG 运行 | 图能运行，但工具对调用方的服务承诺、禁止结果和整包验收不足 | 契约投影与引用闭包 |
| 场景数据 | ScenarioDraftSetV2、FixtureAssetDescriptor、FixtureBundle、Fixture Studio | 缺少跨能力 Dataset 聚合、业务目录、质量、影响和复用治理 | 本方案最大新增领域 |
| 隔离运行 | 数据流控制反转、MirrorPlan、Testing Runtime、批量运行 | 入口分散，运行前真实/替身闭包与运行后五轴结论不连续 | 应用编排与体验整合 |
| 正确性证据 | Oracle、Assertion、RunTrace、Evidence、fingerprint | 业务作者难以从数据和运行直接理解「证明了什么」 | 任务投影与 Deep Link |
| 黄金演示 | 已有取消费 Business Mirror fixture 和多个技术样例 | 样例分散在不同工作区，缺少一条无需 ID、无需 JSON、离线可跑的完整路径 | 版本化 Demo Pack 与 CI |

总体判断是：**执行与协议底座较强，统一资产模型和业务创作体验明显落后。** 因此不应按「从零建设新平台」估算，也不能把工作缩减为页面换皮。真正的关键路径是 Scenario Dataset、Capability Compiler 和统一工作区三项，它们分别解决资产积累、语义闭合和用户动线问题。

## 3. 统一产品模型

![能力设计工作台目标产品模型](assets/resource-gateway-capability-studio-product-model.svg)

图源：[resource-gateway-capability-studio-product-model.drawio](assets/drawio/resource-gateway-capability-studio-product-model.drawio)

### 3.1 三类一等能力资产

| 资产 | 业务定义 | 典型实现 | 可引用对象 | 必须具备的验证 |
|---|---|---|---|---|
| `API_CAPABILITY` | 查询或改变业务事实的最小外部能力 | `HttpResourceOperator`、自定义 Operator、协议 Adapter | 无或 built-in function | 契约样例、错误与超时、请求匹配、敏感字段、真实绑定连通性 |
| `FEATURE_CAPABILITY` | 由一个或多个事实加工得到的稳定业务特征 | GraphDraft / BLOGE DSL | API、Feature、built-in function | 字段血缘、分支覆盖、边界值、缺失值、依赖故障和输出语义 |
| `TOOL_CAPABILITY` | 可由 Agent、SOP、Workflow 或应用稳定调用的业务动作 | GraphDraft / BLOGE DSL / 组合 Operator | API、Feature、Tool、规则 | 工具契约、业务 Oracle、副作用、全链隔离场景、兼容性和证据 |

三类资产共享通用头信息：

- 稳定 ID、显示名称、业务说明和 Owner；
- 完整企业 Scope；
- 风险、数据分类、标签和适用范围；
- Draft、Snapshot 和外部 Governance Projection；
- 输入、输出、错误、副作用和兼容策略；
- exact dependency refs、scenario dataset refs 和 evidence refs；
- revision、fingerprint、provenance 和 lifecycle。

三类资产的具体内容保持分离。API 拥有传输和真实绑定信息；Feature 拥有字段语义与 DAG；Tool 拥有面向上层调用方的服务承诺。不要把所有字段塞入一个可空对象。

### 3.2 契约、实现和验证必须分离

每个能力资产由三部分组成：

```text
Capability Contract
  对外承诺：输入、输出、错误、副作用、兼容性

Capability Implementation
  如何实现：Operator、GraphDraft、DSL、built-in function、runtime binding

Capability Correctness
  如何证明：Coverage、Scenario Dataset、Oracle、Assertion、Run Evidence
```

重要不变量：

1. 契约可以先于实现存在，以支持业务人员提出并验证能力提案。
2. 实现可以替换，但不能静默改变已发布契约。
3. 验证资产必须绑定精确契约与实现快照；发生漂移后证据自动过期。
4. Feature 和 Tool 都使用 DAG 实现时，仍各自拥有独立对外契约，不能继承整张图的偶然内部字段。

### 3.3 场景数据不是 Mock 的附件

建议形成以下层次：

```text
ScenarioDataset
├── Dataset metadata
│   ├── business domain / owner / classification
│   ├── applicable contract refs
│   ├── coverage tags / source / retention
│   └── lifecycle / quality profile
├── DataCaseRevision[]
│   ├── business condition
│   ├── graph or tool input
│   ├── expected business outcome
│   └── exact dependency behavior refs
├── DependencyBehaviorProfileRevision[]
│   ├── invocation-site selector
│   ├── request matcher
│   ├── response / error / delay / state transition
│   └── consumption and unmatched policy
└── Oracle / Assertion / Evidence refs
```

产品概念必须明确区分：

| 概念 | 解决的问题 | 是否包含业务 Payload | 是否可独立复用 |
|---|---|---:|---:|
| 场景数据集 | 哪些业务条件需要长期维护和验证 | 可引用 | 是 |
| 场景数据行 | 某个确定业务条件下输入与预期是什么 | 是 | 是 |
| 依赖表现方案 | 某个依赖在场景中如何响应、失败或变化 | 可能引用 | 是 |
| Fixture material | 受保护的具体请求、响应或状态材料 | 是 | 通过精确引用复用 |
| Binding Plan | 本次运行每个依赖到底走真实还是替身 | 否，仅引用 | 运行级不可变 |
| Run Evidence | 这次运行证明了什么 | 默认不保存明文 | 是，受策略约束 |

## 4. 目标信息架构

### 4.1 一级导航

建议默认一级导航收敛为：

| 入口 | 回答的问题 | 现有能力承接 |
|---|---|---|
| 能力资产 | 已有哪些接口、特征和工具，状态如何 | Business Mirror Portfolio + Operator Library + Graph Catalog |
| 能力设计 | 当前能力的边界、实现、数据和证据是否闭合 | Author v2 + Contract/Scenario Workspace |
| 场景数据 | 可复现业务事实和依赖表现是否充分、可信、可复用 | Correctness Fixture Studio + Testing Control Plane |
| 隔离试跑 | 哪些场景通过、为什么失败、是否访问真实服务 | Rehearsals + Table Run + Run Trace |
| 交付证据 | 哪个精确版本被证明，限制和治理反馈是什么 | Evidence + Integration Export |

原有页面保留为 Legacy 或专家深链，不再要求新用户自行选择正确入口。

### 4.2 能力工作区的固定任务带

打开任何接口、特征或工具后，顶部显示同一条紧凑任务带：

```text
1 定义目标
2 定义边界
3 组装实现
4 准备场景
5 隔离试跑
6 检查证据
```

用户可跳转到任一步，但每一步都显示：

- 本步要回答的业务问题；
- 已有输入和缺失输入；
- 一个主要动作；
- 完成标准；
- 上下游影响；
- 技术详情入口。

步骤状态由服务端 `ReadinessProjection` 计算，不能由前端根据字段数量猜测。

### 4.3 工作区页面结构

桌面端采用稳定三栏布局：

```text
左侧 220-260px：步骤、资产结构、依赖导航
中间自适应：当前主任务，契约表单、DAG、矩阵或运行差异
右侧 300-360px：就绪度、影响、来源、质量、主要动作
```

交互约束：

- 普通编辑不打开阻塞式多层 Modal；复杂对象使用可保持上下文的 Side Sheet。
- 所有引用字段使用主动筛选组合框，支持名称、Owner、标签和说明搜索。
- ID、revision、fingerprint 和 authority 默认只读，收进「技术详情」。
- 保存后重新读取权威 head 与 readiness；前端不得自行假设阻断已经消失。
- 未保存切换、版本冲突、契约漂移和数据过期都有明确恢复动作。

## 5. 业务接口设计体验

### 5.1 首次创建不从空白 Schema 开始

创建业务接口时提供四种入口，推荐顺序如下：

1. **粘贴一组请求与响应样例**：系统推导字段结构、类型、必填候选和敏感字段候选。
2. **导入 OpenAPI 或 cURL**：生成接口边界和运行绑定草稿。
3. **从已有真实接口生成草稿**：仅在授权环境中读取元数据，不自动抓取 Payload。
4. **从空白开始**：面向熟悉 Schema 的专家。

系统推导结果必须标记为「待确认」，不能把样例中偶然出现的字段直接宣布为稳定契约。

### 5.2 契约设计器

默认按业务语义分组：

- **调用时需要提供**：业务字段名、说明、是否必填、样例、敏感级别。
- **成功时会返回**：字段、含义、可空性、枚举和单位。
- **未成功时会发生**：业务错误、技术错误、是否可重试、调用方建议动作。
- **可能产生的影响**：只读、幂等写、非幂等写、状态变化和补偿要求。
- **服务承诺**：Owner、SLA、限流、数据新鲜度和兼容策略。

每个字段同时保存稳定 `fieldId`。展示名和技术名称可以不同，字段映射不以中文文案作为机器身份。

### 5.3 准备依赖表现

创建契约后，主要动作不是「发布 Fixture」，而是「准备第一个可运行场景」。编辑器以业务句式呈现：

```text
当 请求中的 orderId 等于 O-2026-001
并且 调用次数为第 1 次
则 返回「订单已完成」样例
耗时 80 ms
此后 保持相同结果
```

内置表现模板至少包含：

- 返回正常业务数据；
- 返回业务错误；
- 返回协议错误；
- 延迟后返回；
- 超时；
- 按调用次序返回不同结果；
- 读取和修改会话状态；
- 只观察请求，不替换响应；
- 本场景中不得调用。

高级模式继续暴露 selector、transport boundary、exhaustion policy、unmatched policy 和 raw payload。

### 5.4 接口隔离试跑

运行前显示一张可读预检表：

| 检查项 | 示例结论 |
|---|---|
| 运行环境 | 隔离演练，不允许真实网络访问 |
| 请求 | 已通过输入契约 |
| 依赖表现 | 命中「已完成订单」场景数据 v3 |
| 敏感数据 | 2 个字段已脱敏，Payload 不进入日志 |
| 预期结果 | 状态为 `COMPLETED`，不得出现支付凭证 |

运行后默认显示请求匹配、实际响应、预期差异和证据等级，不跳离当前编辑上下文。

## 6. 业务特征加工与 DAG 体验

### 6.1 特征是一等资产，不是隐藏在工具里的临时变量

业务特征用于把多个原始事实加工成可复用判断。例如「取消费争议上下文」可以由订单状态、取消责任、城市计价规则和历史补偿共同得到。它必须拥有：

- 业务名称、定义、Owner 和适用范围；
- 输入契约与输出特征契约；
- 字段口径、单位、空值和时效语义；
- DAG/DSL 实现；
- 依赖接口与特征的精确引用；
- 场景分母、数据集、Oracle 和证据。

### 6.2 DAG 画布的默认语义

画布节点按业务角色区分，而不是只按代码类型区分：

| 节点 | 视觉与交互 | 主要信息 |
|---|---|---|
| 业务输入 | 固定在左侧，使用输入图标 | 字段、类型、来源 |
| 业务接口 | 蓝色边框，显示真实/替身状态 | 接口名称、契约版本、当前场景表现 |
| 已有特征 | 紫色边框，可展开血缘 | 输出特征、版本、Owner |
| 转换与 built-in function | 中性节点，紧凑展示 | 映射、聚合、计算、条件 |
| 决策规则 | 可双击表格编辑 | 条件列、输出列、命中策略 |
| 特征输出 | 固定在右侧 | 字段含义、质量与时效 |

连线表示数据依赖，边标签显示字段映射摘要。Schema 不兼容、字段未映射、敏感数据越界和循环依赖直接标在相应边或节点上。

### 6.3 数据透镜

画布顶部固定一个场景选择器。选择「支付超时」后：

- 每个接口节点显示当前使用的依赖表现；
- 每个节点显示输入与输出的字段摘要；
- 每条边可展开查看样例值、来源和脱敏状态；
- 未执行节点显示 `SKIPPED` 原因；
- fallback、retry、timeout 和 partial 状态可视化；
- 点击失败边，右侧直接显示预期与实际差异。

数据透镜只读取受授权的 Fixture material。没有查看 Payload 权限的用户仍可看到字段结构、指纹、状态和差异类型。

### 6.4 编排辅助

- 从输出字段拖到下游输入字段完成映射。
- 名称与类型相容时提供映射建议，但必须由用户确认跨语义映射。
- 新建接口或特征后返回画布并自动绑定 exact ref。
- Auto Layout 为节点、边标签和数据摘要预留空间；开启数据透镜后可切换高密度与诊断布局。
- DSL 导入后自动识别接口、built-in function 和依赖拓扑；缺少 Schema 时以推演结果渲染，并明确置信度和未知边界。

## 7. 业务工具契约化体验

### 7.1 工具对上层调用方的承诺

工具不是「一张能跑的图」。工具工作区首先要求定义：

- 谁会调用，解决什么业务问题；
- 输入字段、输出字段和错误语义；
- 哪些结果明确禁止；
- 是否读写业务状态，是否幂等，如何补偿；
- 使用了哪些 API、Feature、规则和子工具；
- 在何种数据分母上被验证；
- 当前证据是否匹配最新契约和实现。

### 7.2 工具内部实现

工具可引用前置接口与特征。编辑时不复制这些资产，只保存 exact refs。依赖升级时系统生成影响报告：

- 契约是否兼容；
- 哪些字段映射失效；
- 哪些场景数据过期；
- 哪些 Oracle 与断言需要复核；
- 哪些证据不再代表当前版本。

### 7.3 工具级隔离运行

工具运行前由系统编译不可变 `BindingPlan`：

```text
订单查询 API          -> 场景数据「已完成订单」v3
取消责任 API          -> 场景数据「司机责任」v2
城市计价规则 API      -> 场景数据「上海规则 2026.08」v1
历史补偿 API          -> 场景数据「无历史补偿」v4
争议上下文 Feature    -> 执行当前 Graph snapshot
补偿建议规则          -> 执行当前 Decision Table snapshot
所有其他外部调用      -> DENY
```

运行结果必须区分：

- 执行是否完成；
- 业务断言是否通过；
- 冻结分母是否被覆盖；
- 证据是否完整且当前；
- 是否满足交付或发布门禁。

不允许使用一个绿色「通过」掩盖这些差异。

## 8. 场景数据资产中心

这是本方案最重要的产品能力。业务接口和 DAG 可以被模仿，长期积累的高保真场景分母、数据、Oracle、缺陷历史和校准结果更难复制。

### 8.1 首屏只回答六个问题

1. 当前业务域有多少可用场景数据集。
2. 哪些关键分支没有可复现数据。
3. 哪些数据因契约或政策变化已经过期。
4. 哪些数据来源不明、未脱敏或未评审。
5. 哪些数据被多个能力复用，变更影响范围多大。
6. 下一步最值得补齐的缺口是什么。

### 8.2 数据集目录

左侧目录支持按以下维度筛选：

- 业务域、问题分类和客户群；
- 关联接口、特征、工具；
- `GOLDEN`、`NEGATIVE`、`BOUNDARY`、`REGRESSION`、`PROPERTY`；
- 数据来源和可信等级；
- Owner、生命周期、新鲜度和敏感级别；
- 已覆盖义务、未覆盖义务和历史缺陷。

搜索结果显示业务名称和摘要。精确 ID 与指纹只在技术详情中展示。

### 8.3 场景矩阵

中间主区域使用可虚拟化表格，默认列组为：

```text
业务条件 | 能力输入 | 依赖表现 | 正确结果 | 覆盖与证据 | 来源与质量
```

交互要求：

- 单击编辑简单标量，双击或 Enter 打开结构化 Side Sheet。
- 结构字段由 Schema 驱动；Raw JSON 只在高级模式出现。
- 表格列由契约自动生成，契约变化后显示迁移预览。
- 可批量派生空值、最小值、最大值、枚举外值、错误、超时和重复调用变体。
- 可运行所选、失败、受影响、已变化和全部场景。
- 批量命令前显示精确行数、目标版本和数据集版本。
- 保存个人列视图不改变 canonical dataset。

### 8.4 数据进入方式

| 来源 | 产品动作 | 默认可信状态 | 必要处理 |
|---|---|---|---|
| 手工设计 | 从业务场景创建 | `DRAFT` | Schema 校验、Owner、业务预期 |
| Schema 生成 | 生成最小合法或边界数据 | `DRAFT` | 标记为合成数据，不冒充真实代表性 |
| 样例导入 | CSV/JSON/OpenAPI example | `PROPOSED` | 字段映射、来源说明、重复检查 |
| 受控 Trace 提议 | 从授权运行生成脱敏候选 | `PROPOSED` | 脱敏复核、数据权利、用途和保留期 |
| 生产事故回流 | 从事故或客诉创建回归样本 | `PROPOSED` | 事故依据、业务 Oracle、敏感信息处理 |
| Replay 派生 | 从受治理回放材料引用 | `PROPOSED` | 精确来源、授权、过期和撤销传播 |
| 其他团队共享 | 引用已认证数据集 | 保持来源状态 | Scope、用途与版本兼容检查 |

自动生成、导入和捕获都只能创建提案，不能绕过 Owner 审核成为 `ACTIVE`。

### 8.5 依赖表现编辑器

编辑器采用「条件 → 行为 → 持续方式」三段：

```text
条件
  调用哪个业务接口
  请求的哪些字段满足什么条件
  是第几次调用，处于哪个会话状态

行为
  返回哪个数据版本，或抛出什么错误
  延迟多久，是否超时，是否修改状态

持续方式
  只使用一次、按序消费、重复最后结果、未命中即失败
```

系统在保存前检查：

- 同一优先级是否存在歧义匹配；
- 是否有未覆盖的外部调用点；
- 是否存在静默 fallback-to-real；
- 返回数据是否符合当前输出契约；
- 故障行为是否与接口错误契约相容；
- 状态迁移是否有明确初始状态和终态。

### 8.6 生命周期

```text
DRAFT
  -> PROPOSED
  -> REVIEWED
  -> ACTIVE
  -> STALE | QUARANTINED | DEPRECATED
  -> REVOKED | EXPIRED
```

规则：

- `ACTIVE` revision 不可原地修改，只能派生新 revision。
- 契约、政策、数据来源或脱敏规则变化会生成 stale proposal，不直接篡改历史状态。
- 被证据引用的旧 revision 按保留策略继续可验证，不能物理覆盖。
- `QUARANTINED` 表示存在安全、权利或质量疑问，所有新运行默认拒绝引用。
- 撤销向所有 Binding Plan、Scenario、Tool readiness 和证据视图传播。

### 8.7 质量画像

质量不是单一分数。至少显示以下独立维度：

| 维度 | 说明 | 典型信号 |
|---|---|---|
| 契约有效性 | 数据是否满足目标 Schema | 字段、类型、枚举、格式 |
| 代表性 | 是否代表声明的业务人群和条件 | 来源窗口、采样偏差、业务审核 |
| 可复现性 | 是否完全受控 | REAL 依赖、逻辑时间、随机种子、状态初值 |
| 可解释性 | 是否知道为什么这样输入和预期 | business intent、Oracle、basis、Owner |
| 新鲜度 | 是否仍适用于当前规则与契约 | contract/policy drift、expiry |
| 唯一性 | 是否与已有样本高度重复 | 结构与语义近似候选 |
| 覆盖增量 | 是否关闭新的业务缺口 | obligation、DAG branch、error path |
| 缺陷价值 | 是否发现或防止过真实问题 | regression history、escaped defect |

产品可以给出建议优先级，但不能把多维质量压缩为一个容易误读的综合分数。

### 8.8 数据血缘与影响

每份 Data Case 必须可以回答：

- 原始来源是什么，经过哪些脱敏和加工；
- 当前 Material 在哪里存储，谁有权读取；
- 适用于哪些契约、接口、特征和工具版本；
- 被哪些场景、测试套件和证据引用；
- 变更会影响多少资产和哪条黄金路径；
- 哪个 Owner 在何时基于什么依据批准。

### 8.9 企业级安全

- 元数据与 Payload 分库存储；普通目录和遥测保持 payload-free。
- Payload Vault 使用租户/组织/项目/环境/区域完整 Scope。
- 明文查看、导入、导出、派生、运行和删除使用独立权限。
- 敏感字段按路径脱敏，保留 profile version 和人工复核状态。
- Reporter、异常、日志、指标和前端 URL 禁止包含业务 Payload。
- 保留与撤销由策略驱动；法律保留与数据主体删除需要单独工作流。
- 数据下载默认加水印和审计；跨 Scope 只能发布受控共享版本，不能直接引用内部 Material。

## 9. 黄金演示案例

![取消费争议处理黄金演示路径](assets/resource-gateway-capability-studio-golden-path.svg)

图源：[resource-gateway-capability-studio-golden-path.drawio](assets/drawio/resource-gateway-capability-studio-golden-path.drawio)

### 9.1 案例名称

**取消费争议处理工具**：客服接到乘客对取消费的申诉后，查询订单、取消责任、城市计价规则和历史补偿，加工争议上下文，判断是否异常，并输出处理建议和解释。

### 9.2 样例资产包

#### 业务接口

| 接口 | 输入 | 输出 | 演示表现 |
|---|---|---|---|
| 查询行程订单 | `orderId` | 状态、城市、时间、司机与乘客信息摘要 | 已完成、已取消、订单不存在 |
| 查询取消责任 | `orderId` | 责任方、原因、证据摘要 | 司机责任、乘客责任、无法判定 |
| 查询城市计价规则 | `cityCode`、`occurredAt` | 取消费规则、上限、版本 | 正常规则、规则缺失、旧版本 |
| 查询历史补偿 | `passengerId`、时间窗口 | 补偿次数、金额、最近原因 | 无历史、已达上限、接口超时 |

#### 业务特征

`cancellation_fee_dispute_context`：

```text
输入：orderId、passengerId、complaintReason、channel
输出：
  responsibility
  chargedAmount
  policyAmount
  amountDelta
  compensationEligibility
  riskFlags[]
  evidenceRefs[]
```

DAG 同时调用订单、责任、规则和补偿接口，进行字段标准化、金额计算、政策匹配和异常标记。

#### 业务工具

`assess_cancellation_fee_appeal`：

```text
输入：orderId、passengerId、complaintReason、channel
输出：decision、reasonCode、explanation、recommendedAction、refundableAmount、evidenceRefs
禁止结果：信息不足时不得自动退款；费用正常时不得错误补偿
副作用：第一阶段只读，不直接执行退款
```

### 9.3 必备场景分母

| 类型 | 场景 | 主要证明 |
|---|---|---|
| `GOLDEN` | 司机责任且实际扣费高于规则 | 正确识别异常并建议补偿 |
| `NEGATIVE` | 乘客责任且金额符合规则 | 不得错误补偿 |
| `BOUNDARY` | 建议补偿达到城市上限 | 金额不得超过政策上限 |
| `BOUNDARY` | 规则生效时间前后一分钟 | 使用正确规则版本 |
| `FAULT` | 历史补偿接口超时 | 走人工复核，不静默认为无历史 |
| `FAULT` | 城市规则缺失 | 明确阻断自动决策 |
| `PARTIAL` | 责任无法判定 | 输出信息不足和下一步建议 |
| `REGRESSION` | 重复扣费事故样本 | 防止已知问题再次出现 |
| `SECURITY` | 依赖响应额外包含支付凭证字段 | 敏感字段不得传播到特征和工具输出 |

### 9.4 十五分钟演示动线

1. 打开「能力资产」，选择「取消费争议处理」完整样例。
2. 进入「业务接口」，打开订单查询契约；用业务表单查看输入、成功结果和错误，不展示原始 JSON。
3. 点击「准备场景数据」，查看 `GOLDEN`、`NEGATIVE`、`BOUNDARY`、`FAULT` 和 `REGRESSION` 行。
4. 打开「历史补偿接口超时」，把依赖表现从正常返回切换为超时，查看系统给出的业务句式预览。
5. 进入「争议上下文特征」DAG，选择同一场景，查看四个接口节点和边上的样例数据。
6. 运行特征，展示支付超时如何导致人工复核特征，而不是默认无历史补偿。
7. 进入工具契约，查看输入、输出、禁止结果、副作用和依赖清单。
8. 先运行 Canonical Baseline 并得到 9/9 通过；再进入 Tutorial Branch 制造一个受控失败，定位差异并一键恢复 Baseline。
9. 打开数据质量，展示来源、Owner、新鲜度、覆盖和影响关系。
10. 打开证据，展示精确能力、数据集、Binding Plan 和运行 fingerprint，以及返回 DAG 的 Deep Link。

演示过程不输入任何技术 ID，不连接真实业务 API，也不要求用户编写 JSON。

### 9.5 黄金样例交付标准

- 一键加载后所有引用闭合，不出现 404、空白页或需要手工补 ID。
- Canonical Baseline 的 9 个场景默认全部通过，并且可以重复得到相同语义结果。
- 教学型失败使用 Baseline 派生出的临时 Tutorial Branch；它不修改已认证数据，退出教学后可以一键恢复 Baseline。
- 浏览器与 VS Code 模式使用同一资产包和同一场景语义。
- 每个步骤有一句话任务说明、一个主要动作和可验证完成标准。
- 断网条件下仍可完成接口、特征和工具的完整隔离试跑。
- 演示完成后生成可导出的精确证据包，而不是只有前端成功提示。

### 9.6 逐屏黄金路径验收合同

以下合同是产品、研发、QA、演示手册和 Playwright 的共同权威。实现可以调整内部组件，但不能在未经重新评审的情况下改变这些外部行为。

| ID | 用户动作 | 必须看到的反馈 | 通过标准 | 自动化证据 |
|---|---|---|---|---|
| `GP-01` | 从默认首页打开「取消费争议处理」 | 显示 4 个接口、1 个特征、1 个工具和 9 个场景的资产摘要 | 无手工 ID、无 404、无缺失引用、首个主要动作明确 | 路由、目录 API、引用闭包断言 |
| `GP-02` | 打开订单查询业务接口 | 以业务表单展示输入、成功结果、错误和副作用 | 默认不出现 Raw JSON；字段说明、必填和敏感状态完整 | DOM、可访问名称、Schema round-trip |
| `GP-03` | 进入场景数据中心 | 显示五类场景、来源、Owner、适用契约和质量状态 | 不需要理解 FixtureBundle；所有场景均有业务名称和正确结果 | Dataset projection 与表格断言 |
| `GP-04` | 在 Tutorial Branch 将历史补偿接口设为超时 | 显示「当什么条件、依赖如何表现、持续多久」的业务句式 | 保存成功；Canonical Baseline 未被修改；运行预检显示 0 个未解析依赖 | Revision、branch isolation、preflight |
| `GP-05` | 打开争议上下文特征 DAG 并选择超时场景 | 四个接口、转换、规则和特征输出完整展示，边上可查看样例值 | 无节点、边标签和数据摘要遮挡；可解释每个输出字段来源 | Canvas DOM、像素检查、lineage assertion |
| `GP-06` | 运行当前特征场景 | 历史补偿依赖的原始尝试显示 `TIMEOUT`，BLOGE fallback 被明确标识；Feature 最终状态为 `PASSED`，输出 `action=MANUAL_REVIEW`、`informationGap=COMPENSATION_HISTORY_TIMEOUT` | 不能把超时投影为空数据；聚合与决策节点按降级输入继续执行；真实外部调用数为 0；Trace 同时保留失败尝试和恢复后结果 | RunTrace attempt/final 断言、Feature Oracle、Data Lens、mock 标记、network deny |
| `GP-07` | 打开工具契约 | 显示输入、输出、错误、禁止结果、副作用、精确依赖和“9 场景 × 3 轮”验证目标 | 默认不要求技术 ID 或 Raw JSON；技术坐标默认折叠；主操作唯一且可被键盘触发 | Contract projection、引用闭包、DOM/可访问名称断言 |
| `GP-08` | 在工具页运行全部 9 个 Canonical Case | 同屏显示 9/9 场景、9/9 业务 Oracle、3/3 轮次、27/27 业务断言、0 进程内真实调用；展示 3 个 suite run、9 × 3 Case 矩阵、同 Case 三轮稳定的业务结果指纹和 timeout/duplicate/forbidden-write 专项证明，并并列“开发验证通过”与“发布仍不可验收”；部署已绑定候选时显示候选制品与执行意图 | 严格覆盖固定 9 Case × 3 轮；3 个 suite `runId` 和 27 个 child `runId` 全部唯一；三轮 publication/provenance/source-map、candidate intent 与逐 Case semantic fingerprint 稳定；child evidence 必须经既有授权 API 回读且完整签名可独立验证；Canonical `RETURN` 必须作为 descriptor-backed transport response 经过同一 `ResourceRegistry`、`HttpResourceOperator` 和输出映射链，当前样例为 `verificationLevel=DEVELOPMENT_VERIFIED / evidenceClass=CERTIFIABLE`；未绑定候选时显示四项限制，绑定后只保留目标环境认证、部署级 egress 和 Owner 签署三项限制；始终保持 `releaseGateStatus=NO_GO` | 受治理 Batch API、v3 严格 Schema、独立 Test Kit verifier、候选/意图篡改与未解析 Resource 负向测试、Spring 集成、真实 Chrome、axe、桌面/移动截图 |
| `GP-09` | 从场景数据中心进入「质量与影响」，选择一条 Case 查看其来源、正确性判定和受影响资产 | 首屏必须同时说明「五项闭包覆盖是否齐全」和「当前为何仍不可准入」；逐 Case 展示 Owner、Source、Oracle、适用 Contract、运行依赖、复用数与影响路径；脱敏和新鲜度使用独立状态，不得混成一个“数据安全”标签 | 投影必须严格闭合 Dataset、9 个 Case、Source、Oracle、Contract、runtime dependency 和 Target exact refs；节点、边、Case 与汇总数量可独立复算且无孤立 Case。`READY` 只允许在 Active Case 大于 0、五项覆盖均为 100%、新鲜度为 `CURRENT`、无 blocker、无孤立 Case 时出现；当前黄金样例必须如实显示 9 个 `DRAFT`、0 个 `ACTIVE`、新鲜度 `UNVERIFIED`、准入 `BLOCKED`。`PAYLOAD_NOT_EXPORTED` 只证明当前投影没有导出 Payload，不得宣称源数据已经完成语义脱敏 | 严格 Quality/Impact v1 Schema、独立 Test Kit 内容指纹与图闭包复算、服务端确定性/篡改/授权测试、前端选择与恢复测试、真实 Chrome 中英文/桌面/移动/键盘/axe/溢出证据 |
| `GP-10` | 在 Tool 的 9 × 3 Case 矩阵选择一轮运行，打开证据，再进入失败或降级节点 | 首屏明确说明“读取原运行，没有重新执行”；展示原 `runId`、Case、焦点节点，以及 Tool、Contract、Dataset、runtime target、Binding Plan、Fixture、Behavior、依赖、source map、provenance 和结构级 Data Lens 的完整 exact closure；Feature 页只绘制焦点所属 graph path，完整 Data Lens 不丢失外层 Tool 节点 | GET 只能读取已持久化 child run，调用次数为 0；同一 Run/Case 连续读取 response bytes 与 projection fingerprint 完全相同；URL 必须绑定 `task/runId/scenarioId/nodeId`，刷新、前进、后退和从 Feature 返回 Tool 后仍指向同一 Run/Case/Node；结构视图不得包含 Payload；未知 Run/Case、合同漂移、引用缺失、指纹篡改、无权限和审计故障必须失败关闭；当前 Canonical timeout 子图恰好为 6 节点，完整 Data Lens 恰好为 7 节点，`fallbackToReal=false` | 严格 v1 Schema、独立 Test Kit 对真实 wire bytes 复算、服务端零重跑/确定性/篡改/授权测试、前端 URL 与错误恢复测试、真实 Spring + Chrome 刷新/返回测试、1440/1280/390 DOM/视觉/溢出证据 |

步骤失败时不能只显示错误码。每个失败状态必须同时展示：发生了什么、当前影响、主要恢复动作、是否保留未保存内容。

#### 9.6.1 黄金路径固定执行矩阵

`GP-01` 至 `GP-10` 的正式候选验收不是一次主持人演示，而是以下矩阵的笛卡尔积。任一单元未执行或被跳过，对应合同最高只能是 `PARTIAL`。

| 维度 | 固定值 | 通过标准 |
|---|---|---|
| 语言 | `zh-CN`、`en` | 产品文案完整切换；业务权威数据未本地化时必须明示边界 |
| 视口 | 1440 × 900/1100、1024 × 768、390 × 844 | 页面无横向溢出；控件、DAG、边、表格和状态不遮挡 |
| 输入 | 鼠标、仅键盘 | 主路径可达，焦点可见，不依赖隐藏 ID 或 Raw JSON |
| 权限 | 结构可见、受控 Payload 可见、禁止 | 三态语义一致；无权时 payload 不进入 DOM、URL、日志和 Evidence |
| 页面状态 | loading、empty、ready、dirty、saving/running、conflict、error、stale、forbidden | 每态都有可理解的原因、影响和恢复动作；错误不丢可恢复输入 |
| 业务数据 | 固定 4 API、1 Feature、1 Tool、9 Case | 引用闭包精确相等；数量、Owner、Oracle、Source 和适用契约不得漂移 |
| 批量运行 | 9 Case × 3 轮 | 3 个 suite Run、27 个 child Run 唯一；无跳过；业务 Oracle 和隔离不变量全部成立 |
| 可访问性 | 自动 axe + 人工读屏 | serious/critical 为 0；主路径语义、错误和双结论可被正确读出 |

#### 9.6.2 Data Lens 身份与 Payload 授权验收合同

「结构」与「受控数据」是用户申请的视图，不是用户可以自我授予的权限。服务端必须先用可验证的 workload credential 解析出 Scope、purpose 和 clearance，再决定是否执行演练以及返回哪种投影。`permission`、`X-Clearance` 或其他客户端字段只能表达请求，不得成为授权事实。

| 场景 | 请求条件 | 必须结果 | 直接失败条件 | 必须证据 |
|---|---|---|---|---|
| 结构查看 | 有效 Bearer、`X-Purpose=CAPABILITY_STUDIO_REHEARSAL`、受信身份 clearance 至少为 `PUBLIC`、请求 `STRUCTURE_ONLY` | HTTP 200；保留 topology、状态和 fingerprint；节点输入输出、边值和差异明文为 `null`；响应回显 `STRUCTURE_ONLY` | 明文进入响应、DOM、URL、错误、日志或 Evidence | Controller 正向测试、严格 Schema/Test Kit、真实浏览器 DOM 与泄漏扫描、允许审计记录 |
| 受控数据查看 | 有效 Bearer、专用 purpose、受信 clearance 至少为 `CONFIDENTIAL`、请求 `PAYLOAD_VISIBLE` | HTTP 200；只返回当次隔离演练允许的受控值；响应中的值与 fingerprint 可独立互验 | 低于阈值的身份获得任何 Payload；返回超出当次 Run 和 Scope 的数据 | clearance 边界测试、响应/fingerprint 互验、数据分类与 Scope 断言、允许审计记录 |
| 未认证 | 无 Bearer、格式错误、token 无效/过期 | HTTP 401 与稳定问题码；提供可操作恢复说明；不执行 Feature DAG | 先执行再拒绝、返回部分 Trace/Payload、静默降级为有权视图 | 未认证 Controller 测试、Run/connector counter 不增长、拒绝审计、UI 恢复态 |
| purpose 或 clearance 不足 | 身份有效，但 purpose 不允许；或请求 Payload 时 clearance 低于 `CONFIDENTIAL` | HTTP 403 与稳定问题码；明确说明本次数据未展示及如何申请授权；不执行 Feature DAG | 用查询参数提权、默认回退为 Payload、错误体泄露值或敏感身份声明 | 低权限/purpose 负向测试、零运行副作用断言、拒绝审计、中英文 UI 恢复态 |
| 伪造身份声明 | 有效 token，但 `X-Clearance`、Tenant、Project 或 Environment 等客户端提示与受信身份不一致 | HTTP 403，稳定问题码指向 identity claim mismatch；不执行 Feature DAG | 客户端提示覆盖受信身份；通过伪造 `X-Clearance=CONFIDENTIAL` 获得 Payload | 全部声明篡改测试、拒绝审计、Payload/Trace 泄漏扫描 |
| 非演示环境 | production profile 或 demo 开关关闭 | 端点不装配，不存在「仅靠权限即可激活」的隐藏路径 | production 中返回任何演示 Trace 或 Payload | production 装配否定测试、路由扫描、部署配置快照 |

上表的 401/403 不能只验证 HTTP 状态。还必须证明认证/授权发生在 Graph 执行之前，拒绝记录写入失败时整个请求失败关闭，且错误投影、前端恢复态和服务日志都不携带 Payload。

#### 9.6.3 场景数据质量与影响验收合同

GP-09 解决的不是「有没有质量百分比」，而是三个更严格的问题：这批数据能否被准入、为什么；任一 Case 变化会影响什么；页面对数据保护状态的表述是否可被证据支持。覆盖率、准入状态、数据新鲜度和 Payload 边界必须分开判定。

| 验收面 | 机器可观测标准 | 直接失败条件 | 必须证据 |
|---|---|---|---|
| 引用闭包 | Dataset、Case、Source、Oracle、Contract、runtime dependency、Target 全部使用 exact ref；同一身份只出现一次；Case、节点和边稳定排序；每条边的端点存在 | 用名称拼接关系、缺少 fingerprint/Scope/Authority、重复节点、悬空边、汇总数量与图不一致 | 严格 Schema、Test Kit exact-ref/排序/去重/基数复算、服务端篡改测试 |
| Case 完备性 | 每条 Case 必须有 Owner、Source、Oracle、至少一个适用 Contract，以及完整的 runtime-control dependency；`impactedAssetCount` 必须等于该 Case 的唯一 Contract、runtime dependency 与 Target 并集 | 任一 Case 孤立、责任人或正确性判定缺失、影响数量由前端猜测 | 服务端投影测试、Test Kit 闭包复算、UI Case 选择断言 |
| 准入状态 | `READY` 必须同时满足：`activeCaseCount > 0`、五项覆盖均为 100%、新鲜度为 `CURRENT`、`orphanCaseCount=0` 且 blocker 为空；`BLOCKED` 至少有一个稳定 blocker 和业务可读说明 | 只有覆盖率 100% 就显示 READY；0 个 Active Case 仍显示可发布；阻断原因只给错误码 | 状态机负向测试、矛盾组合 mutation test、首屏 DOM 断言 |
| 当前黄金样例 | 固定显示 9 个 `DRAFT`、0 个 `ACTIVE`、0 个孤立 Case；五项覆盖均为 100%；新鲜度为 `UNVERIFIED`；blocker 至少包含 `NO_ACTIVE_CASES` 与 `FRESHNESS_EVIDENCE_MISSING`；最终准入为 `BLOCKED` | 为演示好看而把 Draft 改成 Active、伪造复核时间或隐藏 blocker | 固定数据断言、截图、协议快照和 Test Kit verifier |
| 新鲜度 | Source 没有可信复核时间时只能是 `UNVERIFIED`；未来引入 Authority 后才可依据受信复核时间和策略窗口计算 `CURRENT/STALE` | 用当前系统时间或页面加载时间冒充复核时间；无时间证据仍显示 CURRENT | Authority/时间来源断言、边界时钟测试、过期和时钟漂移测试 |
| Payload 与脱敏 | 普通质量投影的 `payloadExposure` 固定为 `NONE`，递归扫描不得出现 request、response、body、fixture、mock、replay、secret 等业务值字段；页面必须明确「未导出 Payload」不等于「源数据已语义脱敏」 | 用 `PAYLOAD_NOT_EXPORTED` 宣称已完成脱敏；值进入响应、DOM、URL、日志或 Evidence | Schema/Test Kit 泄漏扫描、Controller 负向测试、浏览器 DOM/URL 扫描 |
| 影响交互 | 选择 Case 后只高亮其 Source、Oracle、适用 Contract、runtime dependency 与 Target；业务名称优先，技术坐标默认折叠；桌面展示可扫读关系图，移动端降级为稳定关系列表 | 选择后影响路径不变、节点被遮挡、主视图暴露原始 ID、移动端横向溢出 | 前端交互测试、真实浏览器 DOM/像素/键盘/读屏检查 |
| 失败与恢复 | loading、协议错误、越权、空结果、stale 和服务错误均说明发生了什么、影响和恢复动作；失败不能回退到前端本地推断的“近似影响图” | API 失败后仍展示旧结论却不标 stale；静默用 Demo 常量补图 | 错误态测试、断网/篡改/刷新浏览器测试 |

当前黄金样例通过的是「投影真实性」验收，不是「数据已准入」验收。它应明确证明系统不会把 100% 元数据覆盖误报为业务数据已经可发布；只有后续 Dataset Authority 提供可信复核时间、生命周期变更和 Owner 审批后，才允许生成新的 `READY` 证据。

## 10. 技术架构

![能力设计工作台技术架构](assets/resource-gateway-capability-studio-technical-architecture.svg)

图源：[resource-gateway-capability-studio-technical-architecture.drawio](assets/drawio/resource-gateway-capability-studio-technical-architecture.drawio)

### 10.1 架构原则

1. UI 面向业务任务，领域服务保持精确协议。
2. 新增投影与编译层，不复制运行时语义。
3. Draft 可变，Snapshot 和已激活数据 revision 不可变。
4. 元数据可发现，Payload 默认不可见。
5. 每次运行先编译 exact closure，再执行；运行中不回查可变 head。
6. 真实与替身是运行绑定选择，不是资产本体属性。
7. 所有自动推演都携带置信度、来源和待确认状态。

### 10.2 建议新增模块

| 模块 | 职责 | 不承担的职责 |
|---|---|---|
| `capability-authoring` | 三类能力 Draft/Snapshot、引用和生命周期 | 不执行 Graph |
| `capability-projection` | 面向 UI 的任务、Readiness、Picker 和影响投影 | 不成为 wire authority |
| `scenario-data` | Dataset、Data Case、Behavior Profile、血缘、质量和影响 | 不保存未受保护的明文日志 |
| `capability-compiler` | 契约传播、Graph/DSL lowering、依赖闭包、Binding Plan | 不在运行中动态猜依赖 |
| `isolated-run` | 预检、批量运行、控制反转、证据组装 | 不接管生产调用入口 |
| `capability-demo-pack` | 版本化黄金样例、完整场景和验收脚本 | 不作为客户生产事实 |

### 10.3 领域对象

建议新增协议对象：

```text
ApiCapabilityDraft / ApiCapabilitySnapshot
FeatureCapabilityDraft / FeatureCapabilitySnapshot
ToolCapabilityDraft / ToolCapabilitySnapshot
CapabilityContract
CapabilityImplementationRef
CapabilityDependencyLink
CapabilityReadinessProjection

ScenarioDatasetDraft / ScenarioDatasetSnapshot
DataCaseDraft / DataCaseRevision
DependencyBehaviorProfileDraft / DependencyBehaviorProfileRevision
FixtureMaterialRef
ScenarioDataQualityProjection
ScenarioDataImpactReport

ExecutionBindingProfile
CompiledBindingPlan
CapabilityRunRequest
CapabilityRunEvidence
```

通用对象只承载稳定头信息。各能力 Kind 使用独立内容结构：

```text
CapabilityAssetHeader
  schemaVersion / capabilityId / revision / scope
  display / owner / risk / classification
  lifecycle / provenance

ApiCapabilityContent
  contract / transportSemantics / runtimeBindingRefs

FeatureCapabilityContent
  contract / graphRef / fieldSemantics / dependencyRefs

ToolCapabilityContent
  contract / graphRef / dependencyRefs / serviceSemantics
```

### 10.4 与现有对象的映射

| 新产品对象 | 现有权威基础 | 演进方式 |
|---|---|---|
| API Capability | OperatorDefinition、Operator schema、Resource Descriptor、runtime binding | 新增业务投影与 Snapshot，不复制 Operator 实现 |
| Feature Capability | `BusinessAssetRef.Kind.FEATURE`、GraphDraft、DSL | 增加 Feature Draft/Snapshot 与字段语义 |
| Tool Capability | GraphDraft、GatewayGraphContract、Capability Proposal | 增加面向上层调用方的 Tool Contract 投影 |
| Scenario Dataset | Correctness Definition、ScenarioDraftSetV2、FixtureAssetDescriptor | 新增聚合与引用关系，复用现有资产 |
| Behavior Profile | Scenario dependency behaviors、FixtureRule | Authoring 模型编译为 FixtureRule |
| Binding Plan | FixtureBundle + MirrorPlan + EffectiveExecutionPlan | 固化每个 invocation site 的数据来源与运行策略 |
| Run Evidence | TestRunEvidence、RunTrace、Mirror Evidence | 增加 Capability/Dataset/Binding exact closure |
| Business Package | DomainCapabilityPackageDraft | 引用三类 Capability Snapshot，不复制内容 |

### 10.5 编译流程

```text
Capability Draft
  + exact Contract
  + exact Graph/Operator/DSL implementation
  + exact Scenario Dataset revision
  + environment execution policy
        |
        v
Capability Compiler
  1. 校验契约与实现兼容
  2. 冻结递归依赖与 invocation inventory
  3. 传播 Schema 并校验字段映射
  4. 将 Behavior Profile 编译为 FixtureBundle rules
  5. 计算每个调用点的 Binding
  6. 拒绝未解析、歧义或越权的依赖
        |
        v
CompiledBindingPlan + Scenario/TestSuite
        |
        v
BLOGE Testing Runtime
        |
        v
CapabilityRunEvidence
```

编译器必须是确定性的。相同输入产生相同 fingerprint；任何契约、依赖、数据或策略变化都会改变计划 fingerprint。

### 10.6 建议 API 表面

以下路径用于冻结职责，不要求一次迭代全部开放：

```text
GET    /api/capability-assets
POST   /api/capability-assets/{kind}
GET    /api/capability-assets/{kind}/{id}
PUT    /api/capability-assets/{kind}/{id}
POST   /api/capability-assets/{kind}/{id}/snapshots
GET    /api/capability-assets/{kind}/{id}/readiness
GET    /api/capability-assets/{kind}/{id}/impact

GET    /api/scenario-datasets
POST   /api/scenario-datasets
GET    /api/scenario-datasets/{datasetId}
PUT    /api/scenario-datasets/{datasetId}
POST   /api/scenario-datasets/{datasetId}/revisions
POST   /api/scenario-datasets/{datasetId}/cases:derive
GET    /api/scenario-datasets/{datasetId}/quality
GET    /api/scenario-datasets/{datasetId}/impact

POST   /api/capability-runs:preflight
POST   /api/capability-runs
GET    /api/capability-runs/{runId}
GET    /api/capability-runs/{runId}/evidence
POST   /api/capability-runs/{runId}:replay
```

要求：

- 列表端点只返回 metadata，不返回业务 Payload。
- 所有精确读取支持 `revision` 与 fingerprint 条件。
- 保存使用 optimistic revision 或 ETag，冲突不得 last-write-wins。
- 错误返回稳定 code、目标字段、影响和恢复动作；界面不得只显示 `Request failed: 400`。
- Capability Probe 公布协议版本、端点、feature flags、最大批次与当前 readiness。

### 10.7 存储边界

建议分为四类存储：

1. **Authoring metadata store**：Draft、关系、Readiness、质量和索引。
2. **Immutable snapshot store**：内容寻址的能力、数据集、规则和证据快照。
3. **Protected payload vault**：Fixture material、Replay material 和受控差异，独立加密与授权。
4. **Event/outbox store**：资产变化、运行完成、数据过期和证据撤销事件。

仓储不变量：

- 完整 Scope 进入复合键和查询条件。
- 相同 ID/revision 不允许不同 fingerprint。
- Payload 与 metadata 不在同一普通查询投影中返回。
- 创建 Snapshot 与更新 head 必须在一致性边界内完成。
- Event 使用 transactional outbox，避免保存成功但影响索引未更新。

## 11. 运行控制与生产隔离

### 11.1 三种用户可见运行方式

| 产品名称 | 工程模式 | 用途 | 约束 |
|---|---|---|---|
| 完全隔离 | `ISOLATED` | 本地设计、演示、批量回归 | 所有外部调用必须有替身或被拒绝 |
| 受控联调 | `CONTROLLED_INTEGRATION` | 验证部分真实接口 | 真实依赖白名单、只读优先、明确审批 |
| 生产绑定验证 | `PRODUCTION_CONFORMANCE` | 验证真实实现符合已批准契约 | 不接受调用方 Fixture override，使用独立凭证与证据策略 |

### 11.2 防止生产误用

- Production profile 不装配 Fixture/Mock injection controller 和 runtime bean。
- 普通业务运行 DTO 拒绝 `fixture`、`mock`、`replay`、`bindingOverride` 等字段族。
- 替身运行使用独立 endpoint、身份、数据库、网络策略和审计类别。
- `FALLBACK_TO_REAL` 默认禁止；受控联调中也必须逐依赖批准并进入证据。
- UI 顶部持续显示当前环境和真实调用数量；颜色不能作为唯一风险提示。
- Side-effect 能力在隔离环境使用 copy-on-write state 或明确拒绝，不调用真实写接口。
- 每次运行证据记录 Binding Plan fingerprint、Mock 标记、真实调用、错误、耗时和限制。

## 12. 非功能要求

### 12.1 可用性

- 黄金样例离线可运行，默认无外部依赖。
- 保存失败保留本地草稿和服务端错误详情，可重试且不产生重复 revision。
- 批量运行支持取消、失败重试、断点恢复和幂等 request ID。
- Capability Probe 区分「协议支持」「当前服务装配」「依赖可用」和「环境已认证」。

### 12.2 性能与规模

- 资产与数据集目录使用服务端搜索、cursor 分页和可取消请求。
- 场景矩阵在大数据集下使用行列虚拟化；分页不是唯一规模手段。
- DAG 在复杂图下提供 minimap、fit-to-content、语义缩放和按分组折叠。
- Payload 不进入列表、指标或普通投影，减少泄漏和查询放大。
- 批量运行以 execution lease 和有界并发保护 Runtime 与下游服务。

具体容量、延迟和保留指标应在 Stage 0 基于客户规模冻结，本方案不预设未经验证的数值承诺。

### 12.3 可访问性与国际化

- 中文与英文使用同一 message key，不以英文拼接生成中文句子。
- 组合框、矩阵、画布、Side Sheet 和运行状态支持完整键盘操作。
- 状态同时使用文本、图标和颜色；错误位置可被屏幕阅读器定位。
- 长字段名、中文业务名和技术 ID 不应改变固定工具栏、节点和表格布局。

### 12.4 实施前必须交付的验收包

正式功能开发前必须提交以下七项制品。缺少任一项时，Stage 0 状态保持 `NO_GO`：

| 制品 | 最低内容 | 验收责任 |
|---|---|---|
| Product Acceptance Charter | 一页说明目标用户、唯一黄金任务、范围、非目标、成功与失败定义 | 产品负责人 + 业务 Owner |
| 高保真交互原型 | 覆盖接口、数据、特征 DAG、工具、运行和证据，以及空白、错误、冲突和无权限状态 | UX + 产品负责人 |
| Screen State Inventory | 每个关键页面的 loading、empty、ready、dirty、saving、conflict、error、stale 和 forbidden 状态 | UX + 前端 + QA |
| Golden Demo Pack | 4 API、1 Feature、1 Tool、9 Case、Canonical Baseline、Tutorial Branch 和期望证据 | 业务 Owner + 正确性 Owner |
| Domain & Protocol ADR | 三类能力、Scenario Dataset、Behavior、Binding Plan、Authority 和生产隔离不变量 | 架构 + Runtime + 安全 |
| Acceptance Test Skeleton | `GP-01` 至 `GP-10` 的 Playwright 用例、协议测试、Run assertion 和截图基线占位 | QA + 前后端 |
| Sign-off Record | 每个门禁的状态、证据链接、Owner、批准时间、限制和未决项 | 交付负责人 |

高保真原型必须使用黄金数据包中的真实业务名称和字段。Lorem ipsum、`foo/bar`、空表和静态截图不能作为交互验收依据。

### 12.5 三项必须先通过的技术 Spike

#### Spike A：Scenario Dataset 确定性编译

**要证明**：业务化 Dataset/Behavior 模型可以无损编译为现有 FixtureBundle/TestSuite，不需要第二套测试 Runtime。

通过标准：

- 相同 Dataset、Contract 和 policy 连续编译 3 次得到相同 semantic fingerprint；
- 9 个黄金 Case 均可编译并运行；
- RETURN、ERROR、TIMEOUT、顺序消费和 `MUST_NOT_CALL` 至少各有一个有效样例；
- 歧义 matcher、缺失外部调用点和 fallback-to-real 在调度前失败；
- source map 可以从 FixtureRule 回到 Dataset、Case 和界面字段。

#### Spike B：DAG 数据透镜

**要证明**：节点输入输出、边值、状态和首个差异可以从 BLOGE 真实执行信息投影得到，不复制第二套数据流模型。

通过标准：

- 黄金 Feature DAG 的节点和边都能映射到稳定运行坐标；
- 有 Payload 权限时显示脱敏样例值，无权限时只显示结构、状态和 fingerprint；
- timeout、retry、skip、fallback 和 partial 至少各有投影验证；
- Auto Layout 后 1440px 与 1024px 视口无节点、边标签和数据摘要遮挡；
- 点击差异可以定位到首个偏离字段、边或节点。

#### Spike C：生产运行面物理隔离

**要证明**：生产环境不是通过隐藏 UI 防止替身注入，而是从装配和协议表面移除该能力。

通过标准：

- `production` 与 `production,test` profile 下 Fixture/Mock injection route 和 bean 均不存在；
- 普通运行 DTO 嵌套注入 fixture、replay、binding override 字段时失败关闭并审计；
- 完全隔离运行的外部网络调用数为 0；
- 受控联调只有 Binding Plan 白名单依赖可以访问真实服务；
- 安全测试证明跨 Scope 的 Dataset、Material 和 Evidence 不可读取或绑定。

技术 Spike 不是一次性 Demo。验证代码、fixture 和结论必须进入仓库，并成为后续回归门禁。

### 12.6 目标用户可用性门禁

建议 Stage 0 使用 6 名代表性参与者：3 名业务能力设计者、1 名接口 Owner、1 名正确性/测试 Owner、1 名实施或产品人员。测试使用高保真原型和 Canonical Demo Pack，不由主持人逐步指导。

`GO` 的最低标准：

- 至少 5/6 参与者可以独立完成 `GP-01` 至 `GP-10`；
- 完成者的中位用时不超过 15 分钟；
- 手工输入技术 ID 和编辑 Raw JSON 的次数均为 0；
- 所有完成者都能准确说明本次运行哪些依赖使用替身、是否访问真实接口、证据证明了什么；
- 不存在未关闭的 P0/P1 可用性问题；
- 同一个问题被 2 名及以上参与者遇到时，必须形成设计修订或有依据的拒绝记录。

若样本组织方式或目标用户构成发生变化，应重新批准门槛，不能在测试后为了通过而降低标准。

本方案中的可用性严重级别定义为：

- **P0**：用户无法完成黄金任务、产生错误业务结果、可能访问错误环境，或造成数据与安全风险。
- **P1**：关键任务只能依赖主持人解释、技术 ID、Raw JSON、临时脚本或跨页面猜测完成，或者失败后没有明确恢复路径。
- **P2**：不阻断任务，但显著增加操作、理解或检查成本。
- **P3**：视觉与文案瑕疵，不改变任务结果和用户判断。

### 12.7 Go/No-Go 开工门禁

| 门禁 | `GO` 证据 | 签署角色 | `NO_GO` 条件 |
|---|---|---|---|
| 产品范围 | Acceptance Charter 和唯一黄金路径已冻结 | 产品负责人、业务 Owner | 仍有两个以上同优先级主路径 |
| 交互体验 | 高保真原型、Screen State Inventory、用户测试通过 | UX、产品负责人 | 关键动作或失败恢复仍靠口头解释 |
| 业务正确性 | 9 个 Case、Oracle、数据来源和 Canonical Baseline 闭合 | 业务 Owner、正确性 Owner | 存在无 Oracle、无 Owner 或无来源的 Active Case |
| 领域与协议 | ADR、Schema、生命周期和迁移边界通过评审 | 架构、后端、Test Kit Owner | 仍需第二套 Runtime 或有 Authority 歧义 |
| 技术可行性 | Spike A/B/C 全部通过并有仓库证据 | Runtime、画布、安全 | 任一 Spike 只能通过 Mock 演示而非真实内核 |
| 自动化验收 | `GP-01` 至 `GP-10` 测试骨架可运行并绑定需求 ID | QA、前后端 | 验收仍只依赖人工截图和演示说明 |
| 交付准备 | Stage 1 backlog 只包含纵向切片并有 Owner | 交付负责人 | 工作包按前端/后端横向拆开且没有可运行增量 |

任一门禁为 `NO_GO` 时不得开始大规模页面重构。可以继续完成原型、Spike、数据包和测试骨架，但不能把探索代码包装成正式进度。

### 12.8 变更控制

Stage 1 开工后，新反馈必须先归类：

| 类型 | 判断 | 处理方式 |
|---|---|---|
| 验收缺陷 | 实现不符合已批准的 `GP-*` 行为 | 当前纵向切片内修复，并补回归测试 |
| 体验阻断 | 用户无法完成已批准任务，但合同未覆盖该状态 | 先修订 Acceptance Baseline，再实现 |
| 范围扩展 | 新角色、新资产、新主路径或新治理责任 | 进入后续 backlog，不以局部补丁插入当前切片 |
| 技术债务 | 不影响当前外部行为，但增加后续风险 | 记录触发条件和偿还阶段，不伪装成用户需求 |

每次评审只接受带 `GP-*`、领域不变量或非功能门禁引用的变更。没有验收依据的「这里再加一个按钮」不进入当前迭代。

## 13. 分阶段实施计划

### Stage 0：建立 Acceptance Baseline v1

**目标**：先形成可点击、可运行、可自动检查、经目标用户验证的参考交付，再开始正式实现。

交付：

- 冻结接口、特征、工具、场景数据、依赖表现和 Binding Plan 的术语表。
- 完成现有对象到新产品对象的映射与 ADR。
- 冻结 `CapabilityAssetHeader`、三类 kind-specific Draft/Snapshot 和 exact ref 规则。
- 完成 Product Acceptance Charter、高保真原型和 Screen State Inventory。
- 冻结 Golden Demo Pack 的资产清单、9 个场景、Oracle、Canonical Baseline 和 Tutorial Branch。
- 通过 Dataset 编译、DAG 数据透镜和生产隔离三项技术 Spike。
- 建立 `GP-01` 至 `GP-10` 的 Playwright、协议和运行验收骨架。
- 完成 6 名代表性用户的可用性测试并关闭 P0/P1 问题。
- 生成签署完整的 `Capability Studio Acceptance Baseline v1`。

退出门禁：

- 评审者可以不用 FixtureBundle 术语解释完整用户任务。
- 不存在 Feature、Tool 和 Graph Contract 相互覆盖的职责歧义。
- 生产隔离不变量得到安全与运行团队批准。
- `GP-01` 至 `GP-10` 均有页面原型、数据、预期和自动化测试 ID。
- Canonical Baseline 离线运行 3 次均为 9/9 通过，真实外部调用数为 0。
- 目标用户可用性门禁达到第 12.6 节标准。
- 第 12.7 节所有门禁均为 `GO`，不存在口头豁免。

**Stage 1 硬前置**：只有 Acceptance Baseline v1 完整签署后才能开工。任何「边开发边明确主要操作流」的提议均按 `NO_GO` 处理。

### Stage 1：业务接口与场景数据纵向切片

**目标**：让一个接口可以从样例定义契约、准备表现、隔离试跑并保存数据资产。

交付：

- API Capability Draft/Snapshot 与目录。
- 样例优先契约设计器和高级 Schema 模式。
- Scenario Dataset、Data Case、Behavior Profile 最小闭环。
- 依赖表现业务句式编辑器。
- 完全隔离预检、单场景运行与证据。
- 订单查询与城市规则两个完整演示接口。

退出门禁：

- 新用户不输入 ID、不写 JSON 即可完成首次隔离运行。
- 契约变更能标记受影响数据为 stale。
- 未命中行为默认失败，不访问真实服务。
- 所有演示数据具备来源、Owner、分类和版本。

### Stage 2：业务特征 DAG 与数据透镜

**目标**：把多个接口加工为可复用特征，并让用户在画布上看懂数据如何流动。

交付：

- Feature Capability Draft/Snapshot。
- API/Feature Picker、字段拖拽映射和 Schema 传播。
- 场景选择器、节点输入输出摘要、边值预览和首个差异定位。
- DSL 导入后的自动布局、推演边界和置信度提示。
- Feature 级 Scenario、Oracle、Assertion 和 Evidence。
- 取消费争议上下文完整 DAG。

退出门禁：

- 用户可以只看画布解释特征来源和失败路径。
- 画布无节点、边标签和数据摘要遮挡。
- 所有外部调用点在运行前有明确 Binding。
- Graph/Contract/DataSet 漂移会使证据失效。

### Stage 3：工具契约化与整包隔离验收

**目标**：形成可由上层系统稳定调用、可独立验证的 Tool Capability。

交付：

- Tool Capability Draft/Snapshot 和服务语义。
- API、Feature、Tool 引用闭包与影响分析。
- 工具契约、禁止结果、副作用和兼容策略编辑。
- 工具级 Binding Plan、批量运行、五轴结论和 Deep Link。
- 取消费争议处理工具 9 个场景。
- DomainCapabilityPackage 对 Capability Snapshot 的精确引用。

退出门禁：

- 工具不连接真实 API 也能完成全部默认场景。
- 工具导出明确包含输入/输出契约、依赖、数据集、运行绑定和证据引用。
- 工具依赖升级有可读影响报告，不发生静默漂移。

### Stage 4：数据资产工业化

**目标**：让测试数据积累可长期治理，而不是演示数据越积越乱。

交付：

- Payload Vault、独立权限、脱敏、保留、撤销和访问审计。
- 数据质量多维画像、重复候选和覆盖增量。
- 从事故、客诉、受控 Trace 和 Replay 生成提案。
- 数据评审、共享、隔离、过期和退役工作流。
- Dataset impact graph、owner task 和变更事件。
- 大规模矩阵、批量运行、分区与配额保护。

退出门禁：

- 任意 Active Data Case 都可追溯来源、适用契约、Owner 和审批。
- 撤销能阻断新运行并传播到引用资产。
- 普通日志、错误、列表和导出不泄露明文 Payload。

### Stage 5：企业集成与持续治理

**目标**：让 ANEKE 和其他系统稳定消费能力、数据质量与证据，而不复制画布。

交付：

- 版本化 export bundle、capability probe、change event 和 polling cursor。
- `capabilityId`、`datasetId`、`caseId`、`nodeId`、`runId` 级 Deep Link。
- 治理反馈回显、Owner action 和发布门禁阻断定位。
- Test Kit 离线 verifier、固定兼容性样例和跨版本测试矩阵。
- 受控联调与生产实现 conformance 路径。

退出门禁：

- 两个系统可独立升级并通过协议协商确定兼容性。
- Evidence 可被独立复验并准确回到画布、场景和数据版本。
- 本地 fixed fixture 不会被误报为客户现场认证。

### 13.1 分阶段 Acceptance Contract

下表是阶段退出的最低可执行合同。各 Stage 的原交付清单继续说明建设范围，本表负责回答“什么证据出现后才算交付完成”。任一必填项为 `NOT_RUN`、`FAIL`、`PARTIAL` 或 `BLOCKED`，阶段状态均不是 `PASS`。

所有 `S*-AC-*` 在执行前必须满足以下统一前置条件。验收运行必须在 Manifest 中引用这些值，不能依赖测试人员口头说明：

| ID | 前置条件 | 不满足时的处理 |
|---|---|---|
| `AC-PRE-01` | 候选构建具有不可变 build ref、revision、artifact fingerprint 和源码提交；工作区没有未记录补丁 | `NOT_RUN`；不能用开发工作区截图替代候选证据 |
| `AC-PRE-02` | Acceptance Baseline、Golden Demo Pack、Contract、Dataset、Graph 和 Binding 使用 exact ref；所有 fingerprint 已由独立 verifier 复算 | `BLOCKED`；先修复引用闭包或重新生成候选 |
| `AC-PRE-03` | 记录 profile、Scope、region、feature flags、运行身份、逻辑时钟、网络策略和外部依赖可达性；环境与目标门禁一致 | `NOT_RUN` 或 `BLOCKED`；环境不符时不得复用结果 |
| `AC-PRE-04` | Evidence store、egress counter、审计、浏览器版本、Test Kit/verifier 版本和签署入口可用；证据 URI 可解析并校验 fingerprint | `BLOCKED`；不能以控制台文本或人工截图代替缺失的机器证据 |
| `AC-PRE-05` | 角色、语言、视口、Case、异常状态和重复次数矩阵已冻结；执行期间不手工改数据库、不粘贴隐藏 ID、不切换未记录配置 | `FAIL`；污染运行作废，修复后从干净环境重跑 |

每条 Acceptance Contract 继承 `AC-PRE-01` 至 `AC-PRE-05`，并在本条结果中补充自己的用户动作、业务结果和系统不变量。只有前置条件与本条证据都闭合，Owner 才能签署 `PASS`。

每次验收必须生成一条不可变 Acceptance Result。Result 至少包含：`contractId`、候选 build/ref/fingerprint、Baseline 与 Demo Pack exact ref、环境 fingerprint、执行身份、开始与结束时间、实际执行矩阵、自动化命令、观察值、Evidence URI 与 fingerprint、未关闭问题、状态、Owner 和签署。缺失任一必填字段时，状态只能是 `NOT_RUN` 或 `BLOCKED`。后续代码、配置、Schema、Dataset、Graph、Binding、Case、Oracle 或环境策略发生变化时，旧 Result 仍可用于审计，但不能继续证明新候选通过。

| Result 状态 | 唯一含义 |
|---|---|
| `NOT_RUN` | 尚未在满足 `AC-PRE-*` 的候选与环境上执行，或只有开发测试、截图、口头演示 |
| `BLOCKED` | 前置条件、依赖、权限或证据基础设施不满足，验收不能产生有效结论 |
| `FAIL` | 已有效执行，至少一个必需业务结果、系统不变量或阈值不满足 |
| `PARTIAL` | 仅用于开发进度台账；部分子矩阵有证据，但不能作为阶段退出结果 |
| `PASS` | 全量矩阵有效执行，所有必需结果与不变量满足，证据可解析并由指定 Owner 完成签署 |

禁止以失败比例平均、后续补解释、人工忽略异常、重跑后只保留成功结果或降低矩阵覆盖来得到 `PASS`。重跑必须生成新 Result，并保留失败 Result 与修复关联。

#### Stage 0：Acceptance Baseline

| ID | 可观察结果与系统不变量 | 必须证据 | 判定与 Owner |
|---|---|---|---|
| `S0-AC-01` | 评审者从默认入口完成 `GP-01` 至 `GP-10` 原型走查；全程不输入技术 ID、不编辑 Raw JSON、不依赖主持人口头补步骤 | 固定候选构建、逐屏状态清单、中文/英文 1440/1024/390 浏览器记录 | 产品、UX、QA 全部签署后 `PASS` |
| `S0-AC-02` | Golden Demo Pack 固定为 4 API、1 Feature、1 Tool、9 Case；Case 均有 Owner、Source、Oracle、适用契约和精确引用闭包 | Demo Pack Schema 校验、Test Kit verifier、内容 fingerprint、篡改与跨 Scope 负向用例 | 业务 Owner、正确性 Owner `PASS` |
| `S0-AC-03` | Spike A 无损下沉到既有测试 Runtime；Spike B 来自真实 BLOGE Trace；Spike C 从生产装配和协议移除注入能力 | 三份 Spike 报告与仓库测试；确定性、source map、Data Lens、生产 profile、network deny 和 counter 证据 | 架构、Runtime、画布、安全分别签署，三项均 `PASS` |
| `S0-AC-04` | 满足 `AC-STD-01` 至 `AC-STD-07`：不可变候选与执行意图绑定；固定 9 个 Canonical Case 各执行 3 次，共 27 个唯一 `runId`；每个 Case 的业务 Oracle 均通过，同一 Case 的业务结果 fingerprint 三次一致；Graph/Contract/Dataset/Binding exact ref 全程不变；duplicate Case 三次结果幂等；forbidden-write Case 无写调用；timeout Case 的依赖尝试为 `TIMEOUT`，fallback 后 Feature/Tool 最终 `PASSED` 并输出 `MANUAL_REVIEW` 与 `COMPENSATION_HISTORY_TIMEOUT`；进程内与部署级真实外部调用观测均为 0 | 候选制品与执行意图指纹、27 份 `CERTIFIABLE` Run Evidence、逐 Case Oracle 明细、业务结果与依赖 fingerprint、调用点/写入点 Trace、timeout attempt/final/fallback Trace、进程内 counter、network deny/egress 观测、运行环境 fingerprint；Test Kit 批量 verifier 对证据闭包独立复算 | 任一候选/意图漂移、Case 任一次缺失、Oracle 未通过、引用漂移、重复 `runId`、fingerprint 不一致、禁写不成立、timeout 原始尝试丢失、fallback 未执行、最终结果不是人工复核、counter 非 0、Evidence 非 `CERTIFIABLE` 或 network deny 未观测即 `FAIL`；正确性 Owner、Runtime、QA 全部签署后 `PASS` |
| `S0-AC-05` | 至少 5/6 代表性用户在 15 分钟内独立完成黄金路径；无 P0/P1；能说明替身、真实调用和证据含义 | 原始任务记录、完成时间、错误点、严重级别、修订与复验记录 | 业务 Owner、产品、UX `PASS` |
| `S0-AC-06` | Baseline、Demo Pack、ADR、追踪矩阵与 Manifest 互相使用 exact ref；任何内容变化都会使旧签署失效 | `APPROVED` Baseline、`ACCEPTED` Manifest、七类真实签署和独立 fingerprint 复算 | 交付负责人核对后 `PASS` |

Stage 0 的合同定义与实现进度必须分开记录。截至 2026-08-18，当前结论仍为 `NO_GO`：

| ID | 当前状态 | 已有可复验证据 | 阻止 `PASS` 的缺口 |
|---|---|---|---|
| `S0-AC-01` | `PARTIAL` | GP-01 至 GP-10 均已有真实 Chrome 开发切片；GP-01 至 GP-06 覆盖中英文、1440/1024/390、Dataset、教程分支、Feature Trace、键盘路径和 axe；GP-07/08 覆盖中文 1440/390、真实 POST 运行、9 × 3 矩阵、双结论、无页面溢出和 axe serious/critical 为 0；GP-09 以真实认证请求闭合 9/0/0 准入事实、五项 100%、两个阻断、37 节点/81 边、Case 选择后的 9 节点/8 边路径，并覆盖中文 1440/390、无溢出和 axe；GP-10 覆盖原 child run 精确读取、完整引用闭包、Feature 节点焦点以及刷新和返回上下文保持 | GP-07 至 GP-10 的英文、1024 和完整异常恢复浏览器矩阵未闭合；GP-09 仍缺仅键盘与人工读屏；产品/UX/QA 签署未闭合 |
| `S0-AC-02` | `PARTIAL` | 4 API、1 Feature、1 Tool、9 Case 的 Golden Demo Pack、严格加载和 Test Kit 基础验证已存在；每个 Case 已投影完整四 API `RUNTIME_CONTROL`，幂等与禁止写入作为独立 `BUSINESS_EXPECTATION` 保留，编译 source map 不再把业务预期误降为 Tool fixture；前后端与独立 Test Kit 均按该语义验证运行闭包 | Dataset 仍为只读投影；受保护 fixture material 仍由开发服务组装；部分子引用是 Stage 0 坐标摘要；业务与正确性 Owner 未签署 |
| `S0-AC-03` | `PARTIAL` | Spike A 已实现确定性编译、强类型 exact-ref provenance、内容寻址、通过既有 Registry 发布后逐项回读复算，并仅以确认的 exact suite 进入既有 `TestSuiteExecutionService`；完整闭包以可逆字典清单写入 aggregate evidence，Canonical suite metadata 为 11,863 bytes；真实 Spring test profile 已从同一受治理闭包连续运行 3 个 suite、27 个唯一 child run，全部 `PASSED` 且进程内真实外部调用为 0。四个 Canonical API descriptor 已幂等进入应用级 `ResourceRegistry`，同名异构 descriptor 会使 Demo 装配失败而不会覆盖已有资产；Canonical `RETURN` 以 transport-level 原始响应经过真实 `HttpResourceOperator` 的状态、协议和输出映射链，合格 child evidence 为 `CERTIFIABLE`；未解析 descriptor 在调度前以稳定错误失败，output-level 替身仍只能产生 `EXPLORATORY` 证据。严格 Schema 与独立 Test Kit verifier 已验证成功、失败关闭和篡改负向样例。同一 Canonical Feature DAG 已包装为 Tool binding 并通过既有 BLOGE nested graph 路径执行；Spike B 已用真实 BLOGE Trace 驱动 6 节点、5 边 Data Lens，并用服务端可信身份、专用 purpose 和 clearance 取代查询参数授权；Spike C 已证明 production profile/property 不装配受治理基线端点，且进程内 connector counter 为 0。部署启动器已能用实际 JAR SHA-256、Git commit 与 `CLEAN` source tree 生成不可由请求覆盖的候选绑定 | 上述仍是 `DEVELOPMENT_TEST_OWNED`；候选绑定机制已实现，但尚未形成目标验收环境的正式 Candidate attestation。Spike B 仍缺字段级 source map、客户级数据分类/ABAC/Scope Authority 和可信 Graph/semantic fingerprint 来源；Spike C 仍缺部署级 network deny/egress 观测和安全签署 |
| `S0-AC-04` | `PARTIAL` | `POST /api/capability-studio/governed-baseline` 已把页面 9 × 3 切换到同一受治理编译、应用级 `ResourceRegistry`、真实 `HttpResourceOperator`、Registry 回读与 exact-suite 执行链路；真实 Spring 运行产生 3 个唯一 suite `runId`、27 个唯一 child `runId`，九个 Case 在每轮恰好出现一次并全部 `PASSED`。服务端通过既有授权 API 回读每条完整签名 child evidence，逐项核对 target/fixture/run/integrity，导出 payload-free evidence fingerprint、semantic result fingerprint、业务断言与 Fixture 控制计数；同 Case 三轮 semantic fingerprint 一致，9/9 Oracle 与 27/27 业务断言通过，timeout fallback、duplicate 幂等和 forbidden-write 无写入均形成专项证明；publication receipt、suite exact ref、compilation/source-map/provenance fingerprint 三轮一致，进程内真实外部调用为 0。Canonical `RETURN` 使用 descriptor-backed transport fixture，child evidence 为 `CERTIFIABLE`；未解析 Resource 在调度前失败，output-level 替身保持 `EXPLORATORY`。v3 严格 Schema 新增 `verificationLevel`、部署候选和 canonical execution intent；独立 Test Kit 会重算 intent 并拒绝候选篡改，失败态固定为 `NOT_VERIFIED` 且不携带运行证据 | 协议继续强制 `DEVELOPMENT_TEST_OWNED / NO_GO`。干净制品由演示启动器运行时可关闭候选未绑定限制，但正式 `PASS` 仍缺目标环境 Candidate attestation、部署级 network deny/egress 观测及正确性/Runtime/QA Owner 签署；缺少任一项都不能用 `CERTIFIABLE` 证据替代发布验收 |
| `S0-AC-05` | `NOT_RUN` | 已有可执行任务界面和黄金演示数据 | 尚未组织 6 名代表性用户测试，也没有 P0/P1 关闭和复验记录 |
| `S0-AC-06` | `NOT_RUN` | Baseline、ADR、Screen Inventory、追踪矩阵和 `NO_GO` Manifest 已版本化 | ADR 仍为 `Proposed`；Baseline 未批准；Manifest 未接受；七类签署均为空 |

局部开发证据只允许把对应合同从 `NOT_RUN` 更新为 `PARTIAL`。只有表中全部缺口关闭并由指定 Owner 签署，Stage 0 才能退出；启动脚本、组件测试或截图均不能单独生成 `PASS`。

##### 当前受治理候选开发验收子结果

以下结果只证明当前版本的受治理控制面、独立 verifier 和浏览器纵向切片达到 `DEVELOPMENT_VERIFIED`。它不是 `S0-AC-03` 或 `S0-AC-04` 的正式 Acceptance Result，也不能替代目标环境 Candidate attestation。各子合同中的测试数量保留该切片完成时的历史观测，最新候选的回归总量以表中最后一条候选记录为准；数量本身不是永久门槛，永久门槛是冻结矩阵精确执行、无跳过且全部义务成立。

| 子合同 | 可执行标准 | 当前观测 | 结论 |
|---|---|---|---|
| `S0-DEV-GOV-01` | 同一 Dataset、Tool Contract、runtime target 与 source map 重复编译时，FixtureBundle/TestSuite 内容地址和 provenance fingerprint 不漂移 | 确定性、内容寻址和旧 Scenario 兼容性测试通过 | `PASS`，仅限开发证据 |
| `S0-DEV-GOV-02` | 完整 exact-ref 闭包不得因传输预算被截断；持久化往返后必须可严格还原；suite metadata 不超过 16,384 bytes | 可逆字典清单严格重建 kind、id、revision、fingerprint、Scope 和 Authority；Canonical suite metadata 为 11,863 bytes；未知字段、非法索引和非整数 revision 失败关闭 | `PASS`，仅限开发证据 |
| `S0-DEV-GOV-03` | 发布必须注册 9 个 Fixture 和 1 个 Suite；不得信任写返回；每个资产必须独立回读并复算 fingerprint | 真实 Registry 集成通过，回读内容与编译计划一致 | `PASS`，仅限开发证据 |
| `S0-DEV-GOV-04` | 只能执行回读确认的 exact suite；必须产生 9 个互异 child `runId` 和 9 个互异 Fixture ref；全部 Case 终态为 `PASSED`；进程内真实外部调用为 0 | 真实 Spring test profile 一轮执行满足全部条件 | `PASS`，仅限开发证据 |
| `S0-DEV-GOV-05` | 同一 client request 重试必须返回同一收据；新 request 必须复用同一发布资产并生成新的 suite `runId` | 幂等与新请求测试通过 | `PASS`，仅限开发证据 |
| `S0-DEV-GOV-06` | provenance 缺失、字段不全、指纹篡改、调用方覆盖、闭包漂移或协议体积越界时，不得生成可认证结果 | 编译、执行和候选边界负向测试通过 | `PASS`，仅限开发证据 |
| `S0-DEV-GOV-07` | 受治理基线必须只在 test/staging 装配；成功时严格返回 3 suite/27 child/9 Case/9 Oracle/27 业务断言/0 真实调用；任一不变量失败时必须为 `NOT_VERIFIED`，不得伪造 evidence class、发布、Run 或指纹 | 公开严格 v3 Schema、production profile/property 否定测试、服务失败关闭测试通过 | `PASS`，仅限开发证据 |
| `S0-DEV-GOV-08` | 独立 Test Kit 必须验证 Schema、固定 Case 集合/顺序、三轮矩阵、唯一 Run、指纹稳定、业务 Oracle/断言闭包、零真实调用、动态限制和失败关闭无假证据 | `CapabilityStudioGovernedBaselineVerifierTest` 覆盖 v1/v2/v3、成功、失败、候选/意图篡改、业务结果指纹/断言/专项证明/evidence class 篡改；Test Kit `clean verify` 的 798 个测试通过 | `PASS`，仅限开发证据 |
| `S0-DEV-GOV-09` | 用户必须能从 Tool 页触发真实 POST，在桌面和移动端看到 9 × 3 结果与“开发通过/发布不可验收”双结论；五列表格必须完整展示业务场景、Oracle 和三轮断言，页面与结果表均无横向溢出，且无 serious/critical 可访问性问题 | 真实服务与浏览器在 1280 × 720、390 × 844 复验；桌面结果表 `scrollWidth=clientWidth=553`，移动页面/结果表溢出均为 0、轮次计数保持单行；既有 1440 × 1100 与 390 × 844 自动化 Chrome DOM/axe 矩阵通过 | `PASS`，仅限开发证据 |
| `S0-DEV-GOV-10` | 聚合 Suite `PASSED` 不得替代 child 证据；每条 child 必须从授权存储回读完整签名 Evidence，并闭合 run、target、Fixture、证据等级、semantic fingerprint、断言和 Fixture 控制计数；timeout/duplicate/forbidden-write 必须由结构化事实独立判定 | 真实 Spring 9 × 3 与候选边界负向测试通过；Canonical `RETURN` 经 descriptor-backed transport fixture 和真实 Resource Operator 链执行，v3 投影不含 Payload，页面显示 9/9 Oracle、27/27 业务断言、稳定结果指纹、三项专项证明和 `CERTIFIABLE` 证据等级 | `PASS`，仅限开发证据 |
| `S0-DEV-GOV-11` | 候选身份只能由部署启动器一次性注入；必须绑定实际 JAR SHA-256、Git commit 和 `CLEAN` source tree；同一绑定必须进入每轮 execution intent，Test Kit 可独立重算 | `CapabilityStudioDeploymentCandidateAuthorityTest`、候选服务漂移负向测试、v3 verifier 候选篡改测试和演示脚本静态检查通过；未配置时如实保留候选未绑定限制 | `PASS`，仅限机制与开发证据 |
| `S0-DEV-GOV-12` | `CERTIFIABLE` 只能来自可认证运行路径：Resource descriptor 必须存在，transport fixture 必须具备可映射的状态和原始响应，且调用点不得回退为 output-level 替身或真实依赖 | 四个 Canonical descriptor 注册到应用级 Registry；正向集成测试闭合 27 份 `CERTIFIABLE` child evidence；未解析 Resource 在调度前返回稳定错误；output-level 替身保持 `EXPLORATORY`，真实行为不能产生可认证成功证据 | `PASS`，仅限开发机制与 Canonical 样例 |
| `S0-DEV-GOV-13` | 正式 Stage 结果必须精确覆盖 `AC-STD-01..09`，禁止 `PARTIAL` 和伪造执行窗口；结果身份、状态、候选执行、完整环境/egress 投影、检查和签署前 Evidence 必须形成可复算闭包；候选、环境、egress、签署与 `AC-STD-01/06/09` 不得矛盾 | v2 严格 Schema 与 `CapabilityStudioStageAcceptanceResultV2Verifier` 已随 Test Kit JAR 打包；82 条聚焦测试覆盖成功、关闭态、运行前/中阻断、时间、投影、闭包、签署坐标和篡改；Test Kit 798 条全量测试通过 | `PASS`，仅限语义协议机制；外部 Evidence Resolver、公钥验签、Issuer/Owner Authority 和正式签署仍未闭合 |
| `S0-DEV-GOV-14` | Data Lens 的视图参数不得成为授权事实；服务端必须先验证 credential 与专用 purpose，再以受信 clearance 裁决 Payload；401/403 和安全审计不可用必须在 Feature Graph 执行前失败关闭；伪造 `X-Clearance` 不得提权 | Controller 覆盖缺少/无效 credential、缺少 purpose、PUBLIC 结构查看、PUBLIC Payload 拒绝、伪造 clearance、CONFIDENTIAL Payload、审计不可用；两个零交互测试证明拒绝和审计故障发生在 rehearsal service 之前；前端覆盖 host credential、固定 purpose、中英文恢复文案和拒绝后回到实际结构视图；启动脚本使用认证探针。2026-08-18 真实浏览器再以 `CONFIDENTIAL` 与 `PUBLIC` 两个独立 test 实例复验中文桌面和 `390×844`：允许态闭合 6 节点、5 条边、0 真实调用及同一 Trace Payload，拒绝态回到结构投影、保留 6/5 拓扑且 DOM 无 Payload | `PASS`，仅限 test/staging 演示切片；英文拒绝态、客户级 ABAC、数据分类、跨 Scope Payload Authority 和正式安全签署仍未闭合 |
| `S0-DEV-GOV-15` | 精确证据必须读取原持久化 child run，禁止隐式重跑；同一 Run/Case 重读必须字节级稳定。投影必须闭合 Tool、所有适用 Contract、Dataset、Case、runtime target、Binding Plan、Fixture、Behavior、依赖、source map、provenance、断言和同一 Run/Data Lens；完整结构证据与当前 graph-path DAG 必须分别验收。`STRUCTURE_ONLY` 不得携带 Payload；嵌套 edge/path 坐标必须无损；只有显式 `ALLOW_REAL`/`FALLBACK_TO_REAL` 才可令 `fallbackToReal=true`。URL 必须保持 `task/runId/scenarioId/nodeId`，刷新、返回和重复点击不得创建新 Run | 服务端单元/集成测试覆盖原 Run、零执行、字节/指纹确定性、7 节点完整 Data Lens、6 节点当前子图、timeout 焦点、Contract union、嵌套 edge/path、错误 Case、未知 Run、合同/Fixture/plan/evidence 漂移、授权与篡改失败关闭；前端 66 条聚焦测试覆盖严格解析、同格单 GET、错误恢复、URL 刷新/返回和子图过滤；独立 Test Kit 用真实响应 bytes 通过 Schema、exact closure、焦点、Payload 边界及三类 fingerprint 复算；真实 Spring + Chrome 从 Tool timeout cell 进入证据、Feature、刷新并返回，原 Run/Case/Node 不变；中文 1440/1280/390 页面无横向溢出，Feature DAG 无 `subject`，完整 Data Lens 保留 `subject`；Test Kit 798 条和 Resource Gateway 6687 条全量测试均为 0 失败、0 错误 | `PASS`，仅限 test/staging 开发证据；目标环境 Candidate attestation、部署级 egress、客户级 ABAC/Scope Authority、英文/1024/异常恢复、人工读屏与 QA/Integration Owner 签署仍未闭合，正式 Stage 0 保持 `NO_GO` |
| `S0-DEV-GOV-16` | GP-09 必须从同一 Dataset 产生严格、确定、payload-free 的质量与影响投影；根级 `targetRef` 必须独立锚定唯一被验证的 Feature/Tool，图不能自声明目标后自证闭包。固定黄金事实为 9 `DRAFT`、0 `ACTIVE`、0 `STALE`、五项覆盖 100%、新鲜度 `UNVERIFIED`、准入 `BLOCKED`，且 blocker 精确为 `FRESHNESS_EVIDENCE_MISSING`、`NO_ACTIVE_CASES`。图必须为 37 节点/81 边、无孤立 Case；每 Case 闭合 Source、Oracle、1 Contract、4 runtime dependency 和同一 Target，影响资产计数为 6；`PAYLOAD_NOT_EXPORTED` 不得被解释为源数据已语义脱敏 | 公开严格 v1 Schema；服务端确定性、基数、排序、授权和配置装配测试；独立 Test Kit 从真实 wire bytes 重算 projection fingerprint、根级 Target exact-ref/Scope/关系/准入/汇总与 Payload 边界；前端严格 parser、认证 purpose、选择和错误恢复测试；真实 Spring + Chrome 在中文 1440/390 断言 9 Case、37/81 图、所选 Case 9 节点/8 边、五项 100%、两个业务阻断、无页面横向溢出和 axe serious/critical 为 0；启动脚本必须以根级 `targetRef=TOOL/tool-cancellation-fee-dispute-handling` 复验同一协议真相，旧 ID、缺失认证或任一基数漂移都不得报告 ready。当前候选 Test Kit 819 条、Resource Gateway 6694 条全量测试均为 0 失败、0 错误；另有 23 条环境条件测试按既有规则跳过，不计入正式 Stage 退出矩阵。带完整前端制品的 Capability Studio 浏览器类另行执行 10 条、0 跳过并全部通过；测试使用 CDP 精确设置并断言 `innerWidth/innerHeight`，截图像素与 1440/1024/390 合同一致 | `PASS`，仅限 test/staging 开发证据；当前 Dataset 仍是只读 Golden Authority，缺可信 freshness Authority、Active 生命周期变更、客户级数据分类/语义脱敏证明、英文/1024/仅键盘/异常矩阵、人工读屏与 Data Owner 签署，正式准入继续为 `BLOCKED`，Stage 0 继续为 `NO_GO` |

复验必须同时覆盖服务端、前端协议、独立 Test Kit 和真实浏览器，不得只运行成功路径。当前候选使用以下命令：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ScenarioGovernedCompilerTest,ScenarioGovernedProvenanceMetadataCodecTest,CapabilityStudioGovernedCompilationServiceTest,CapabilityStudioGovernedAssetPublisherTest,CapabilityStudioGovernedCandidateServiceTest,CapabilityStudioGovernedCandidateIntegrationTest,CapabilityStudioGovernedBaselineServiceTest,CapabilityStudioDeploymentCandidateAuthorityTest,CapabilityStudioDemoConfigurationTest,CapabilityStudioDemoControllerTest,TestSuiteExecutionServiceTest \
  test

mvn -f resource-gateway-test-kit/pom.xml clean verify

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=CapabilityStudioStageAcceptanceResultV2VerifierTest test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=CapabilityStudioScenarioQualityImpactVerifierTest test

mvn -f resource-gateway-examples/pom.xml \
  -Dtest=CapabilityStudioBrowserAcceptanceTest#gp07AndGp08RunTheGovernedToolBaselineAndKeepReleaseClosedAcrossViewports \
  test

mvn -f resource-gateway-examples/pom.xml \
  -Dtest=CapabilityStudioBrowserAcceptanceTest#gp09ExposesFalsifiableQualityAdmissionAndCaseImpactAcrossViewports \
  test

cd resource-gateway-examples/src/main/frontend
npm test -- src/capability-studio/CapabilityStudio.test.tsx \
  src/capability-studio/api.test.ts \
  src/capability-studio/domain.test.ts
npm run build
```

上述命令是开发复验入口，不是正式签署命令。当前已闭合每 Case 每轮 semantic fingerprint、业务断言和 timeout/duplicate/forbidden-write 开发 Oracle，Canonical child Evidence 已达到 `CERTIFIABLE`，并完成部署侧候选绑定机制；但正式验收运行尚未在目标环境生成 Candidate attestation。正式退出仍需补齐目标环境 fingerprint、部署级 network deny/egress 观测和指定 Owner 签署。任一项缺失，Stage 0 仍为 `NO_GO`。

#### Stage 1：业务接口与场景数据

| ID | 可观察结果与系统不变量 | 必须证据 | 判定与 Owner |
|---|---|---|---|
| `S1-AC-01` | 新用户可对订单查询、城市规则两个接口完成“从样例定义边界 -> 确认契约 -> 准备场景 -> 设置依赖表现 -> 隔离试跑 -> 查看证据” | 两条端到端浏览器用例、任务时长与零 ID/零 Raw JSON 断言 | 产品、接口 Owner、QA `PASS` |
| `S1-AC-02` | 表单、样例、JSON Schema/OpenAPI 导入和导出保持语义 round-trip；非法、缺失、敏感字段和 breaking change 均有业务化反馈 | 协议 round-trip、兼容性、unknown field、敏感字段、错误恢复测试 | 接口 Owner、架构 `PASS` |
| `S1-AC-03` | Dataset、Case、Behavior 使用持久化 Authority、immutable revision、CAS 和 exact refs；契约变化会传播 stale，不静默继续使用 | Repository、并发冲突、重启恢复、stale propagation、跨 Scope 与篡改测试 | 正确性 Owner、数据安全 `PASS` |
| `S1-AC-04` | 未解析依赖在调度前失败；完全隔离试跑不会访问真实服务；失败可保留编辑内容并给出恢复动作 | Preflight、单场景 Evidence、network deny/counter、错误状态浏览器证据 | Runtime、安全、QA `PASS` |
| `S1-AC-05` | 中文/英文及 1440/1024/390 关键视口可完成主任务；键盘路径和 axe serious/critical 均无阻断 | 真实浏览器、键盘、完整 axe、视觉截图和溢出检查 | UX、前端、QA `PASS` |

#### Stage 2：业务特征 DAG 与 Data Lens

| ID | 可观察结果与系统不变量 | 必须证据 | 判定与 Owner |
|---|---|---|---|
| `S2-AC-01` | 用户通过 Picker 和字段映射组装取消费争议 Feature，不手填引用；Graph Contract、节点端口和边映射闭合 | Graph/Contract/Binding 编译测试、引用闭包和 Schema 传播证据 | Feature Owner、画布、架构 `PASS` |
| `S2-AC-02` | 选择超时场景后，画布可解释每个字段的来源、节点输入输出、边值、状态和首个差异；无 Payload 权限时只显示结构与 fingerprint；视图参数与伪造身份头无法提权，认证/授权在 Graph 执行前完成 | BLOGE RunTrace 投影、权限双态、lineage 与首差异断言；Data Lens 身份权限矩阵、声明篡改、零运行副作用、审计失败关闭与泄漏扫描 | 画布、Runtime、数据安全 `PASS` |
| `S2-AC-03` | 1440 和 1024 视口 Auto Layout 后节点、端口、边标签和数据摘要均无遮挡；缩略图可判断整体拓扑 | DOM 几何断言、canvas pixel 检查、真实浏览器截图和人工视觉签署 | UX、画布、QA `PASS` |
| `S2-AC-04` | timeout、retry、skip、fallback、partial 的 UI、Trace 和 Oracle 语义一致；证据绑定 exact Graph/Contract/Dataset/Binding | 五类运行证据、漂移失效和 Deep Link 回图测试 | Feature Owner、正确性 Owner `PASS` |

#### Stage 3：工具契约化与整包验证

| ID | 可观察结果与系统不变量 | 必须证据 | 判定与 Owner |
|---|---|---|---|
| `S3-AC-01` | Tool 明确展示输入、输出、错误、禁止结果、副作用、SLA 和全部 API/Feature 依赖；默认界面不要求理解底层 Graph ID | Tool Contract Schema、投影、可访问 DOM 和引用闭包测试 | Tool Owner、产品、架构 `PASS` |
| `S3-AC-02` | 不连接真实 API 运行完整工具，9 Case 连续 3 次全部通过，且 ERROR、TIMEOUT、顺序消费和 `MUST_NOT_CALL` 语义保真 | Batch Run、semantic fingerprint、source map、断言、network counter | 正确性 Owner、Runtime、QA `PASS` |
| `S3-AC-03` | Tool export bundle 包含 exact 契约、依赖、Dataset、Binding Plan 和 Evidence refs；下游可独立复验，不能把本地 fixture 误报为现场认证 | Test Kit 离线 verifier、tamper/mixed-version/权限负向测试 | 集成 Owner、安全 `PASS` |
| `S3-AC-04` | 依赖升级会产生可读影响和阻断，Evidence Deep Link 可返回 exact Graph、节点、Case 和 revision | breaking migration、stale gate、刷新保持和 Deep Link 浏览器测试 | Tool Owner、治理 Owner `PASS` |

#### Stage 4：数据资产工业化

| ID | 可观察结果与系统不变量 | 必须证据 | 判定与 Owner |
|---|---|---|---|
| `S4-AC-01` | 任一 Active Case 都能追溯 Source、Owner、Oracle、审批、适用契约、复用方和保留策略；缺项不能激活 | 全量质量查询、随机抽样复验、激活门禁与 orphan 扫描 | 数据 Owner、正确性 Owner `PASS` |
| `S4-AC-02` | Payload 的查看、导出、派生和运行权限独立；普通投影、日志、错误、指标和 Evidence 不泄露明文 | RBAC/ABAC、脱敏、审计、导出、日志扫描与攻击 fixture | 数据安全、合规 `PASS` |
| `S4-AC-03` | 撤销、过期或 Scope 变化会阻断新运行并传播影响；历史证据保留状态与原因，不被静默改写 | revoke/retention/stale 事件、impact graph、race 与恢复测试 | 数据 Owner、平台 `PASS` |
| `S4-AC-04` | 在 Stage 0 冻结的目标规模下，批量运行、索引、Vault 和事件满足批准的 SLO，超载时有配额、背压和降级 | 容量模型、压力/浸泡/故障注入结果、告警与 Runbook 演练 | SRE、平台、架构 `PASS` |

#### Stage 5：企业集成与持续治理

| ID | 可观察结果与系统不变量 | 必须证据 | 判定与 Owner |
|---|---|---|---|
| `S5-AC-01` | Resource Gateway 与 ANEKE 可独立升级并通过 capability probe 协商版本；字段增加、弃用和不兼容均有确定结果 | N/N-1/N+1 兼容矩阵、固定 fixture、consumer contract 与降级测试 | 两端协议 Owner `PASS` |
| `S5-AC-02` | Run Evidence 可导出、脱敏、按 runId 回放并由 Test Kit 独立验证；失败状态、retry/fallback 和 payload 权限语义一致 | Evidence bundle、payload replay、tamper、retention 和权限测试 | 正确性、治理、安全 `PASS` |
| `S5-AC-03` | draft/operator/run/contract 的 Deep Link、治理反馈和 Owner action 均回到正确上下文；刷新和跨版本不丢失坐标 | 中文/英文真实浏览器、权限、过期链接和迁移测试 | 产品、治理 Owner、QA `PASS` |
| `S5-AC-04` | change event/webhook 可重试、去重、补拉和审计；消费者停机或乱序不会造成静默漏治理 | outbox、cursor、幂等、乱序、重放、灾难恢复和观测证据 | 平台、集成、SRE `PASS` |

### 13.2 固定验收执行矩阵

除单条 `S*-AC-*` 的专项证据外，各阶段必须完整执行下列矩阵。矩阵单元的分母必须出现在 Acceptance Result 中；跳过、重试后只保留成功结果或缩小分母均视为 `FAIL`。

| Stage | 固定执行矩阵 | 通过阈值 | 立即失败条件 |
|---|---|---|---|
| Stage 0 | `GP-01..10 × zh-CN/en-US × 1440/1024/390`；Canonical `9 Case × 3 轮`；6 名代表性用户；3 项技术 Spike | 自动矩阵 100%；至少 5/6 用户在 15 分钟内独立完成；P0/P1 为 0；三项 Spike 全部签署 | 黄金路径依赖手填 ID/Raw JSON/主持人补步骤；任一 Case 错判；真实调用非 0；协议或证据不可复算 |
| Stage 1 | 订单查询、城市规则 `2 API × 样例/表单/OpenAPI/Schema 4 种入口 × 新建/兼容修改/breaking 修改/非法输入 4 类变更`；正常、业务错误、超时、禁止访问 4 类依赖表现 | 32 个创作组合和 8 个隔离运行组合全部通过；round-trip 语义差异为 0 | breaking change 未阻断；保存丢数据；未解析依赖继续运行；敏感字段泄漏 |
| Stage 2 | 取消费争议 `1 Feature × timeout/retry/fallback/skip/partial 5 类故障 × 有/无 Payload 权限 2 类身份 × 1440/1024 2 个视口` | 20 个 Data Lens 组合全部通过；节点、端口、边标签和数据摘要遮挡数为 0 | Trace 与 Oracle 状态矛盾；字段来源不可解释；无权限身份看到 Payload；Auto Layout 遮挡关键数据 |
| Stage 3 | `9 Canonical Case × 3 轮` Tool 整包运行；exact export、篡改、混版、越权 4 类离线验证；依赖兼容与 breaking 升级各 1 组 | 27 次运行与 exact export 全通过；3 类负向包全部被拒绝；breaking 升级稳定阻断 | 任一依赖未进入闭包；本地替身被标为现场认证；禁止写入发生；结果 fingerprint 漂移 |
| Stage 4 | 全量 Active Case 质量扫描；至少 30 个或全量（取较小者）随机复验；Payload 查看/运行/导出/派生 4 权限 × 允许/拒绝 2 结果；撤销、过期、Scope 变化 3 类生命周期事件 | Active Case 完整率 100%；越权成功数 0；三类失效传播均阻断新运行且保留历史证据 | 无 Owner/Source/Oracle 的 Case 可激活；明文泄漏；撤销后仍可新运行；历史 Evidence 被静默改写 |
| Stage 5 | 协议 N/N-1/N+1 × Resource Gateway 先升级/ANEKE 先升级 2 个方向；webhook 重复、乱序、延迟、消费者停机 4 类故障；有效/过期/无权/已迁移 Deep Link 4 类状态 | 版本协商和降级结果 100% 确定；事件最终无遗漏且无重复副作用；Deep Link 均返回正确上下文或明确恢复动作 | 静默丢字段；事件永久漏治理；重放产生重复副作用；Deep Link 跳到错误资产或泄漏越权信息 |

Stage 0 冻结的性能与恢复 SLO 必须从 Stage 1 起进入每次发布候选。至少包含：单场景运行延迟、9 Case 批量运行延迟、并发运行数、Dataset/Case 规模、Evidence 保留、RPO、RTO、限流和背压阈值。任一指标未测、未达标或没有超载降级证据时，对应阶段不能 `PASS`。

### 13.3 缺陷严重级别与退出规则

| 级别 | 定义 | 示例 | 阶段处理 |
|---|---|---|---|
| `P0` | 业务结果错误、数据或权限泄漏、访问真实生产依赖、证据伪造或不可逆数据破坏 | 超时被当成“无历史补偿”并自动赔付；普通用户看到 Payload；隔离运行发出真实写请求 | 立即停止验收，候选 `FAIL`，修复并重跑受影响 Stage 全矩阵 |
| `P1` | 黄金主任务无法独立完成、无可执行恢复路径、核心状态或引用闭包不可信 | 必须手填隐藏 ID；保存失败且内容丢失；Trace 无法返回对应节点 | 候选 `FAIL`，不得带病退出 Stage |
| `P2` | 有明确绕行且不改变业务正确性、安全和证据可信度的体验或非关键功能缺陷 | 次要筛选条件未保留，但主任务可恢复 | 记录 Owner 与期限；产品、UX、QA 明确接受后可有限制 `PASS` |
| `P3` | 不影响任务、可访问性和理解的轻微一致性问题 | 非关键间距或辅助文案瑕疵 | 进入 backlog，不影响合同判定 |

同一根因导致的多个表象按最高级别处理，不能拆成多个低级缺陷稀释严重性。任何标记为 `P2/P3` 的问题如果影响黄金路径完成率、业务 Oracle、安全不变量或机器证据，必须升级为 `P0/P1`。

### 13.4 所有阶段共用的验收纪律

- 每个 Stage 只能以可运行的纵向增量结束，不能用「前端完成」「接口完成」或「代码已合并」作为阶段完成结论。
- 每个 Stage 都要升级 Acceptance Baseline、Golden Demo Pack、追踪矩阵和 Manifest Schema；四者版本必须互相引用。
- 阶段演示必须从干净环境使用标准启动脚本开始，不允许手工改数据库、临时粘贴 ID 或切换未记录的配置。
- 阶段退出必须生成 `ACCEPTED` Manifest，并由该阶段对应业务、产品、架构、安全或 QA Owner 签署。
- 同一时间最多推进一个未被用户验收的主纵向切片；发现方向性问题时先回到原型和 Baseline，不继续横向铺开页面。
- 对阶段范围的任何扩展必须说明它关闭了哪个 `GP-*`、领域不变量或 NFR；无法关联时进入后续 backlog。

### 13.5 单条合同的执行与签署顺序

1. **冻结候选**：记录源码提交、制品、配置、Baseline、Demo Pack 与环境 fingerprint。
2. **独立预检**：由 Test Kit 复算 exact closure；验证身份、网络策略、证据设施和执行矩阵分母。
3. **自动执行**：运行协议、组件、端到端、隔离、安全、视觉与可访问性检查；保留全部尝试，不删除失败后重跑记录。
4. **业务复核**：业务或能力 Owner 逐 Case 核对输入事实、依赖表现、业务结果和禁止结果，而不是只看绿色总数。
5. **专业签署**：UX、安全、Runtime、架构和 QA 只签署各自拥有的证据，不允许单一角色代签。
6. **生成结果**：Acceptance Result 记录分母、失败、豁免、证据 URI 和签署；只有唯一通过公式成立时生成 `PASS`。
7. **发布后校验**：在目标部署执行 capability probe、smoke、network deny 和 Deep Link 复验；部署差异使预发布结果失效时立即回滚。

验收结果必须同时服务三类读者：业务 Owner 能看懂场景和结果，工程团队能定位代码与协议，审计方能独立复算引用和证据。任何只满足其中一类的报告都不是完整交付物。

## 14. 工程工作包

| 工作包 | 主要产物 | 依赖 | 建议 Owner |
|---|---|---|---|
| WP-01 统一领域与 ADR | 术语、对象、生命周期、映射 | 无 | 架构 + 产品 |
| WP-02 Capability Registry | 三类 Draft/Snapshot、Repository、API | WP-01 | 后端 |
| WP-03 Capability Studio Shell | Portfolio、任务带、Readiness、Picker | WP-01/02 | 前端 + UX |
| WP-04 Contract Designer | 样例推导、表单、Schema round-trip | WP-02 | 前后端 |
| WP-05 Scenario Data Service | Dataset、Case、Behavior、质量、影响 | WP-01 | 后端 + 数据安全 |
| WP-06 Scenario Data UX | 目录、矩阵、Side Sheet、派生与 diff | WP-05 | 前端 + UX |
| WP-07 Capability Compiler | closure、Schema 传播、lowering、Binding Plan | WP-02/05 | 引擎 |
| WP-08 Data Lens | 边值、映射、差异、权限投影 | WP-07 | 画布 + 引擎 |
| WP-09 Isolated Run | preflight、批量、证据、replay | WP-05/07 | 测试平台 |
| WP-10 Golden Demo Pack | 资产、9 个场景、证据、脚本、手册 | WP-04/06/08/09 | 产品 + QA |
| WP-11 Enterprise Hardening | Vault、RBAC、retention、events、SLO | 核心纵切稳定 | 平台 + 安全 |
| WP-12 Integration Protocol | export、probe、deep link、Test Kit | WP-02/05/09 | 集成团队 |

推荐并行关系：WP-02 与 WP-05 在共同 ADR 后并行；WP-03 可先使用 fixture projection 开发；WP-07 是 Feature、Tool 和 Data Lens 的共同关键路径；WP-10 从 Stage 0 开始维护，不能留到最后补样例。

## 15. 测试与验收

### 15.1 领域与协议测试

- 三类 Capability kind 的必填、不变量和非法组合。
- Draft 到 Snapshot 的确定性 fingerprint。
- Dataset、Case、Behavior、Material 的精确引用闭包。
- unknown field、跨 Scope、重复 ID、revision 冲突和 tamper 拒绝。
- 新旧协议 mixed-version、固定 fixture 和 Test Kit 独立复验。

### 15.2 编译与运行测试

- Contract 到 Operator/Graph port 的 Schema 传播。
- invocation site 完整、歧义、未命中和多次消费。
- RETURN、ERROR、DELAY、TIMEOUT、REPLAY、OBSERVE、DENY 和状态序列。
- logical clock、random seed、retry、fallback、partial 与 cancel。
- 编译后不回查 mutable head。
- 生产 profile 物理不存在 Fixture injection 路由与 bean。

### 15.3 数据安全测试

- Payload 不进入日志、异常、URL、Metric、metadata API 和普通 Evidence。
- 跨租户、组织、项目、环境、区域读取与引用失败关闭。
- 脱敏规则漂移、撤销、过期和保留执行。
- 下载、导出、派生、运行和明文查看权限彼此独立。

### 15.4 真实浏览器 Golden Path

至少覆盖：

- 中文和英文；
- 1440px、1024px 和 390px 关键视口；
- 键盘完成契约、Picker、场景编辑和运行；
- 无服务目录、无权限、保存冲突、接口 404/400、版本漂移和运行失败；
- 取消费争议完整 10 步演示；
- DAG Auto Layout 后节点、边标签、数据摘要不遮挡；
- 页面刷新、Deep Link 和跨工作区返回后上下文保持。

截图不能替代 DOM、协议和运行断言，但每个发布候选必须进行真实浏览器视觉验收。

### 15.5 需求到证据的追踪矩阵

每个需求必须在同一张追踪矩阵中闭合，不能只在 PR 描述中声称完成：

| Requirement ID | 原型页面 | Domain/API | Demo Data | Automated Test | Visual Baseline | Sign-off |
|---|---|---|---|---|---|---|
| `GP-01` 至 `GP-10` | 必填 | 必填或明确 N/A | 必填 | 必填 | 涉及界面时必填 | 产品 + QA |
| `SPIKE-A` 至 `SPIKE-C` | 可选 | 必填 | 必填 | 必填 | Data Lens 必填 | 架构 + 专项 Owner |
| `SEC-*` | 非主要证据 | 必填 | 攻击 fixture | 必填 | N/A | 安全 |
| `NFR-*` | 涉及 UX 时必填 | 必填 | 容量 fixture | 必填 | 响应式要求必填 | 架构 + QA |

测试失败时报告必须返回 Requirement ID、exact asset refs、环境和恢复入口。只显示组件测试名，不足以支持跨团队分诊。

### 15.6 Golden Path Acceptance Manifest

每个发布候选生成机器可读的 `CapabilityStudioGoldenPathAcceptanceManifest`，至少包含：

```text
manifestVersion
candidateBuildRef
acceptanceBaselineRef
goldenDemoPackRef
contractAndDatasetFingerprints
gpResults[GP-01..GP-10]
scenarioResults[9]
realExternalCallCount
browserAndViewportResults
accessibilityResults
protocolAndSecurityResults
knownLimitations
signOffs
generatedAt
```

Manifest 只保存精确引用、状态、计数和 fingerprint，不复制业务 Payload。缺少任何 `GP-*`、9 个场景未全部通过、真实调用数不为 0、存在未签署门禁或引用不闭合时，Manifest 状态必须为 `REJECTED`。

### 15.7 发布候选硬门禁

以下任一条件成立时不得进入演示发布或下一阶段：

- Canonical Demo Pack 不是 9/9 通过；
- `GP-01` 至 `GP-10` 任一失败、跳过或无证据；
- 需要手工输入技术 ID、编辑 Raw JSON 或临时修改数据库才能完成演示；
- 出现 404、无恢复动作的 400、空白页或未解释的 capability 缺失；
- 完全隔离运行发生真实网络访问；
- DAG 在关键视口存在节点、边标签或数据摘要遮挡；
- Evidence 无法回到 exact Graph、节点、场景和数据 revision；
- 高保真原型与实现的主要任务顺序不一致且未重新批准；
- 存在未关闭的 P0/P1 产品、正确性、安全或数据权利问题。

## 16. 产品指标

### 16.1 首次成功

- 从进入完整样例到首次隔离运行成功的时间。
- 完成过程中手工输入技术 ID 和 Raw JSON 的次数，目标为零。
- 首次运行前未理解真实/替身依赖的用户比例。
- 因缺少演示数据、404、400 或引用漂移中断的比例。

### 16.2 数据资产积累

- Active Data Case 中有来源、Owner、Oracle 和适用契约的比例。
- 新增 Case 实际关闭的 Coverage Obligation 数量。
- 数据复用次数与复制创建次数。
- stale 数据发现到修复或退役的周期。
- 由事故、客诉和 Outcome 偏差回流的 Regression Case 数量。
- 数据发现缺陷、阻止回归和减少外部环境等待的事实记录。

### 16.3 反指标

- Fixture 数量增长不能作为成功指标。
- 模拟通过率不能独立作为质量指标。
- 单一综合成熟度分数不能作为发布门禁。
- 自动生成场景数量不能替代业务 Owner 认可的分母覆盖。
- 本地固定样例通过不能被表述为客户真实接口已认证。

## 17. 主要风险与根治措施

| 风险 | 表面修补 | 病根 | 根治措施 |
|---|---|---|---|
| 又增加一个新工作台造成更多入口 | 增加导航说明 | 没有统一对象与路由权威 | 能力资产成为聚合根，旧页转为上下文视图和 Legacy |
| Dataset 变成新的 JSON 仓库 | 增加标签 | 缺少生命周期、质量和影响 | exact refs、来源、Owner、Oracle、覆盖、审批和撤销成为必备元数据 |
| Mock 规则过于复杂 | 再包一层表单 | 请求匹配、状态与传输语义混杂 | 简单模板 + 业务句式 + 高级模式；编译前做歧义和闭包检查 |
| 自动 Schema 推导产生错误契约 | 提高模型置信度 | 样例不是契约权威 | 推导只生成 Proposal，要求 Owner 确认和兼容性基线 |
| DAG 看起来丰富但仍难理解 | 增加节点颜色 | 数据与结构分离 | 场景驱动的数据透镜、字段映射、边值和首差异定位 |
| Fixture 误入生产 | UI 增加红色提示 | 运行面和 DTO 共享 | 物理装配隔离、独立 endpoint/identity/network policy、字段族拒绝 |
| 数据复用造成大面积隐式漂移 | 复制一份再改 | 引用没有版本与影响 | immutable revision、exact ref、impact report、stale propagation |
| 黄金样例再次变成空壳 | 增加更多样例名称 | 没有样例验收协议和 Owner | 一个完整纵切先于多个浅样例；资产、数据、运行、证据都进 CI |
| 业务人员仍然无法判断何为正确 | 增加断言模板 | Oracle 和技术断言未分层 | 先写业务预期和依据，再生成可执行断言 |
| 大型组织中数据权责不清 | 增加审批按钮 | 来源权、规格权、实现权、发布权混用 | 独立 Owner、Authority、Scope、review 和 governance projection |

## 18. Definition of Done

### 开工 Definition of Ready

- `Capability Studio Acceptance Baseline v1` 已签署并内容寻址。
- Product Acceptance Charter、高保真原型、Screen State Inventory 和 Golden Demo Pack 全部完成。
- Spike A、B、C 通过，验证代码和报告进入仓库门禁。
- `GP-01` 至 `GP-10` 自动化骨架可执行，需求追踪矩阵无空项。
- 6 名目标参与者的可用性测试达到第 12.6 节阈值。
- 第 12.7 节所有门禁为 `GO`，不存在口头或临时豁免。

### 产品

- 至少 5/6 目标用户在无步骤指导下，于 15 分钟内完成 `GP-01` 至 `GP-10`。
- 默认路径手工输入技术 ID 和编辑 Raw JSON 的次数均为 0。
- 场景数据有独立入口、可发现目录、矩阵、质量、影响和生命周期。
- DAG 通过数据透镜解释数据来源、映射、分支和失败位置。
- Canonical Baseline 为 4 API、1 Feature、1 Tool 和 9/9 通过场景；Tutorial Branch 可制造并恢复受控失败。
- 不存在未关闭的 P0/P1 可用性问题。

### 工程

- 三类能力 Draft/Snapshot、Dataset/Case/Behavior 和 Binding Plan 协议稳定版本化。
- 新模型可确定性编译到现有 Graph、FixtureBundle、TestSuite 和 Runtime。
- Snapshot、运行和 Evidence 使用 exact refs 与 fingerprint 闭合。
- optimistic concurrency、event outbox、stale propagation 和 rollback 路径具备测试。
- 相同 Baseline 连续运行 3 次产生相同语义结果，9 个场景全部通过，真实外部调用数为 0。
- `GP-01` 至 `GP-10`、协议、运行、安全和视觉检查全部进入持续集成。

### 安全

- 生产运行面物理排除调用方替身注入。
- Payload 与 metadata 分离，所有访问、派生、导出和运行可审计。
- Scope、分类、脱敏、保留和撤销规则失败关闭。
- UI、日志、异常、指标和证据默认不泄露业务 Payload。
- `production` 与 `production,test` profile 的路由、Bean、DTO 注入和网络隔离测试全部通过。

### 体验验收

- 真实浏览器完成中文/英文、桌面/移动和键盘路径。
- 1440px、1024px 和 390px 视口无节点、边标签、表格文本、数据摘要和状态控件遮挡。
- 404、400、无权限、冲突、过期和运行失败均说明原因、影响和可执行恢复动作。
- 保存、运行、跨页面和 Deep Link 不丢失当前能力、场景与数据上下文。
- `CapabilityStudioGoldenPathAcceptanceManifest` 为 `ACCEPTED`，且所有签署和证据引用闭合。

## 19. 待评审决策

| 决策 | 推荐选项 | 影响 |
|---|---|---|
| 顶层产品名称 | 「能力设计」；技术文档使用 Capability Studio | 避免「镜像」「编排」覆盖不了完整任务 |
| 业务接口资产来源 | Operator/Resource 作为实现，ApiCapability 作为业务投影 | 保留现有 Operator Library 兼容性 |
| Feature 与 Tool 实现 | 共同复用 GraphDraft/DSL，分别拥有独立契约 | 避免复制画布和引擎 |
| 场景数据聚合根 | 独立 ScenarioDataset，引用 Fixture material 和 Behavior Profile | 支持跨能力复用与治理 |
| 默认运行模式 | 完全隔离，未解析外部依赖失败关闭 | 避免误访问真实接口 |
| Schema 创作入口 | 样例优先，OpenAPI/Schema 为导入和高级模式 | 降低门槛且保留精确性 |
| 首个黄金场景 | 取消费争议处理工具 | 复用现有样例并覆盖完整能力链 |
| Business Mirror 关系 | 引用 Capability Snapshot 组装 L0-L3 能力包 | 不让 Package 复制底层资产内容 |

## 20. 方案设计自审结论

本方案按九个维度进行自审，综合成熟度为 **97/100**。该分数只衡量方案是否完整、可解释和可实施，**不是代码完成度、产品验收通过率或发布就绪度**：

| 维度 | 权重 | 得分 | 扣分原因 |
|---|---:|---:|---|
| 产品对象完整性 | 13 | 12 | 资产跨组织共享的产品细节仍需客户目录验证 |
| 操作动线连续性 | 13 | 12 | 需通过目标业务作者研究验证六步任务带 |
| 场景数据竞争力 | 15 | 15 | 数据集、表现、质量、血缘、覆盖与生命周期已形成闭环 |
| 技术领域边界 | 14 | 13 | Payload Vault 部署 Authority 尚待冻结 |
| 现有能力复用 | 10 | 10 | 明确复用 Graph、Correctness、Testing Runtime 和 Evidence |
| 运行与生产隔离 | 10 | 10 | 物理装配、DTO、身份、网络和证据边界完整 |
| 实施可拆分性 | 10 | 10 | 阶段、工作包、依赖和退出门禁可直接拆解 |
| 黄金案例完整性 | 8 | 8 | API、Feature、Tool、9 个场景与演示路径闭合 |
| 指标与可证伪性 | 7 | 7 | GP 合同、用户阈值、Manifest 和硬门禁已明确；容量参数在 Stage 0 冻结 |
| **合计** | **100** | **97** | **达到评审线，仍有四项 Stage 0 决策** |

仍需在 Stage 0 关闭的缺口：

1. 与业务、接口、数据安全和 ANEKE Owner 共同确认四类资产的 Authority 边界。
2. 基于目标客户规模冻结 Scenario Dataset 容量、Payload 保留和批量运行 SLO。
3. 确认 Payload Vault 是 Resource Gateway 自建适配层还是接入企业既有受控存储。
4. 按第 12.6 节组织 6 名代表性参与者完成黄金路径可用性测试，验证产品词汇与任务顺序。

这些缺口不会改变总体架构方向，但会影响 Stage 1 的协议细节、部署形态和容量门禁。在上述决策关闭前，不建议直接开始大规模页面重构；可以先以取消费争议纵向切片验证统一资产模型和场景数据中心。

### 20.1 实现差距采用阶段退出合同硬计数

实现进度不使用主观百分比，也不把 `PARTIAL`、开发截图或组件测试折算成半个 `PASS`。权威分母是第 13.1 节的 27 个阶段退出合同：Stage 0 为 6 个，Stage 1 为 5 个，Stage 2 至 Stage 5 各 4 个。

```text
formalImplementationGap = (27 - formalPassCount) / 27 × 100%
```

截至 2026-08-18，正式 `formalPassCount=0`，因此正式实现与验收差距仍为 **100%**。这不表示开发工作为零：`S0-DEV-GOV-01..16` 和 GP-01..10 的开发切片已经形成大量 `DEVELOPMENT_VERIFIED` 证据；它只表示没有任何一条 Stage 退出合同同时闭合候选、目标环境、完整矩阵、业务 Oracle、部署级隔离、人工复核和 Owner 签署。

“差距小于 3%”在这个分母下不存在模糊空间：缺少 1 个合同即为 `1/27 = 3.70%`，所以停止条件只能是 **27/27 个正式合同全部 `PASS`**。任何开发子合同全绿、设计评分 97/100 或单个 Stage 的演示成功，都不能降低正式差距分子。

每轮迭代按以下顺序更新差距：

1. 先更新对应 `S*-AC-*` 的实际状态和不可变 Evidence，不修改分母；
2. 只有完整执行固定矩阵、关闭 P0/P1、完成目标环境证明和指定 Owner 签署后，才把该合同计入 `formalPassCount`；
3. 若只能关闭开发缺口，则更新 `S0-DEV-GOV-*`、追踪矩阵和剩余缺口，但正式差距保持不变；
4. 下一轮优先选择能移除最多共同根因的纵向切片，而不是选择最容易把页面标绿的局部任务。

当前自动化可继续关闭的首要根因依次为：固定 60 单元浏览器矩阵缺少机器结果、Scenario Dataset/freshness 缺少持久化 Authority 与 Active 生命周期、Feature 缺字段级 source map 和客户级 ABAC/Scope Authority、部署级 network deny/egress 尚无目标环境证据。六人可用性、业务判断和 Owner 签署属于必须由真实责任人完成的外部验收，系统只能提供可信入口和证据，不能代签。
