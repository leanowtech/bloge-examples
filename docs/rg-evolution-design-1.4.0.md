
# Resource Gateway 演进详细技术方案（终稿 · 语法校订版）：面向 Agent 的业务资产 TDD 运行时

> 本文面向从未参与本设计、也不熟悉 Resource Gateway 代码库的一线实施团队。每个系统概念、领域词、内部机制在首次出现处解释。所有 BLOGE DSL 片段均已对照代码库真实样例（`loan-decision-policy.bloge`、`user-dashboard.bloge`）与语法参考（`bloge-dsl-syntax-reference.md`）校订。第 5 章用"取消费纠纷处理"贯穿全程；附录 A–F 给出可直接对接的线级契约、数据模型、状态机与算法。

---

## 1. 背景与目标

### 1.1 Resource Gateway 是什么
Resource Gateway（下称 RG）是一个**给 AI Agent 造"工具"的平台**。"AI Agent"指像客服助手这类由大模型驱动、需要调用外部能力完成任务的程序；"工具"指 Agent 可调用的一个业务能力（查订单、判定取消费是否减免、处理退款纠纷）。

RG 用**三层能力模型**组织工具，自下而上：
- **接口（API）**：对一个外部 HTTP 服务的声明。包含 URL 模板、方法、参数映射、成功判定、从响应取哪段业务数据（payload），以及**业务契约**（业务输入字段、成功返回字段、可预期错误、副作用、负责人、SLA、数据敏感度）。
- **特征（Feature）**：一张小编排图（有向无环图 DAG），把若干接口聚合成"业务信号/上下文"。例如"取消纠纷上下文"并行调用订单查询、责任判定、城市定价政策、历史补偿四个接口，汇成一个上下文对象。
- **工具（Tool）**：Agent 真正调用的业务能力，建在特征之上，内部常含决策逻辑（如一张决策表），产出**业务处置（方案/指令）**。

工具的造法：可视化画布（拖算子、连线成 DAG）+ 与画布等价的**文本 DSL**（后端把可视化草稿降级/lower 成可执行的 BLOGE `.bloge` 文本）+ 模拟运行、测试、治理发布。

### 1.2 一项已存在但被埋没的能力
理解本方案的前提是三项能力：

**(a) 契约优先 / 设计态算子。** 在 RG 里可以**只声明**一个算子或接口的契约（输入类型、输出类型）而**不写实现**。声明用一份**库文档**（schema 标识 `bloge.visualLibraryAuthoring.v1`），一份库同时声明**类型 types / 算子 operators / 函数 functions**。算子带 `runtime.bindingRef`（指向运行时实现/资源）就是"已实现"；不带就是**设计态（design-only）**——只有契约、无实现。`bindingRef` 的有无，就是"设计与实现之间的解耦缝"。关键事实：**设计态算子可以被编排和模拟，但不能被降级为可执行 `.bloge`**（后端代码生成器会对设计态算子给出明确诊断并拒绝降级）——这一点后文会转化为"红/绿"的技术边界。

**(b) 模拟运行（simulate）。** RG 能在**完全不触达任何真实外部系统**下把整张图跑起来：对每个依赖节点用"桩（stub）"替身产出输出。桩来源有二——按算子**声明输出类型合成**一个形状合规值，或用作者**钉定的固定示例值（fixture）**。纯逻辑节点（数据变换 `transform`、决策表 `decision_table`、分支 `branch`）**真实执行**（无副作用、确定性），其余（接口、设计态算子、原生算子、子图）全被桩替换。因此**即使算子只有契约没有实现，整张图也能在可视化草稿层被完整跑通并验证接得通、跑得动**。

**(c) 一个已跑通、但当前只服务演示数据的"业务测试驱动"闭环。** RG 内部有一处已把下面闭环实现出来（当前由固定演示数据包驱动，未对一般业务开放创作）：
- **特征演练**：用受控 fixture 跑特征，保证真实外部调用为 0，按权限展示节点/数据边轨迹。
- **业务 Oracle**：一个**独立于运行时之外**的业务正确性判定器，逐用例检查业务不变量（如"乘客无责就不应产生乘客取消费"），且**从不注入输出、从不把运行时技术性 PASS 当业务成功**。
- **治理基线**：一批用例连续多轮跑，核对业务结论跨轮稳定、真实外呼为 0，任何不变量被破坏即"失败关闭（fail closed）"；结果只标"开发已验证"，**绝不自动通过发布门禁**。

(a)(b)(c) 合起来：**RG 事实上已能"在实现存在之前，用可执行的例子把业务行为规定死并持续验证"**——即软件工程的测试驱动开发（TDD）/例证驱动规格，只是对象从"代码"变成"客服业务规则"。这个潜在产品被"编排+测试+治理"的叙事盖住了。

### 1.3 目标
把 RG 从"造工具+测试的平台"演进为**面向 Agent 的业务 TDD 运行时**：让客服这类业务的常态运营，像软件工程那样用契约优先、例证驱动、持续回归的纪律经营，并**由 AI Agent 作主要操作者**创作维护这些资产。四个可检验目标：① 规则/动作/契约/用例都成为 **Agent 可读写的文本资产**，人机协同；② 业务"应该怎么做"以**可执行 golden 用例集**沉淀为长期资产，变更持续回归；③ 契约可先于实现，业务定"应然"、工程"达标"，可并行；④ 任何"通过"都**分维度诚实**说明证明/未证明了什么。

---

## 2. 要解决的问题

**2.1 业务运营侧。** 规则散落在人脑与零散配置；变更靠临时点验；改一条打断另一条的回归常到生产才暴露；"这个业务在这种情况应该怎么做"没有可执行、不腐烂的记录；新规则依赖部落知识。**根因是业务缺一套"先规定应然、再实现、持续回归"的规格纪律。**

**2.2 系统/产品侧。** 能力足够但被当"编排+测试+治理"用：① 核心资产创作方式是 web 表单点选填值——低带宽、不可 diff、不可版本化、大模型难稳定操作；② 1.2(c) 的闭环封在演示里，不能被一般业务泛化创作，也无统一对外操作面；③ 工具目前只有"输入输出 schema + 身份"，**没有面向 Agent 的语义契约**（叫什么、何时调用、参数何意、示例）——而这正是让大模型能"理解并选择"工具的东西。

**2.3 衍生问题（不一并处理会成新债）。** ① **资产单元错位**：把"长期核心资产"语义挂在 node fixture（一个依赖桩固定值）上并设 1–30 天保留期到期删除，但业务真正想沉淀的是"精心设计的 golden 用例集"，fixture 只是用例里一格；② **"应然"与"现状"混淆**：若期望输出直接取自"当前实现输出"，测试只能锁现状防漂移，不能表达"业务本应如此"；③ **设计与实现耦合**：若编译/检查只对"运行时已注册算子"解析，就做不到"只有契约也能编译检查模拟"，契约优先落不了地。

---

## 3. 价值空间

| 工作流 | 现状成本 | 目标成本 | 价值 |
|---|---|---|---|
| 改一条业务规则 | 临时点验，回归在生产暴露 | 变更即对 golden 集重跑，回归前置拦截 | 回归逃逸率↓ |
| 沉淀业务知识 | 部落知识、散配置、会腐烂 | 可执行 golden 集（活文档+复利资产） | 知识资产化、可回归 |
| 新工具/规则建设 | 工程先建、运营后补坑 | 契约优先，业务定应然工程达标 | 交付并行、返工↓ |
| 日常创作/修改 | web 表单点选填值 | Agent 经 MCP 操作文本资产（DSL+表格） | 人机协同、效率数量级↑ |
| "完成"的定义 | 主观 | 红色 GOLDEN 集=客观待办 | 可度量 DoD |

关键判断：**能力大部分已存在（含 1.2(c) 完整闭环），差的是"串联+语义重构+一个 Agent 操作面"**——边际成本低、边际价值高的演进。

---

## 4. 解法设计

### 4.1 总体形态：Agent 优先，三载体，RG 退居后端
**Agent 优先。** 创作/修改默认入口不再是 web 表单，而是**本地编码 Agent**（如 Claude Code、Codex：能在工作区读写文件、执行命令的智能体）。因为若核心资产是文本，大模型对文本的读写/重构/解释/diff 远强于逐格填表，也让"人说意图→Agent 起草→人审"成为自然工作方式。**web 图形界面相应退为供人审阅的只读看板**，表单仅作非 Agent 用户的逃生舱。

**RG 后端退为"编译器 + 测试运行器 + 治理"**：把文本资产编译成可执行、跑模拟、产诚实结论、执行合入门禁、管资产生命周期，并以一个 **Agent 可调用的工具协议**对外暴露。

**三载体，各用最合适形式**：**逻辑用 DSL**（契约用库文档 YAML，工具用 BLOGE `.bloge` 图，可 diff/可复用，大模型强项）；**例证用表格**（golden 用例一行一个，业务方读表易、Agent 读写表易、最大复用已有场景矩阵/枚举/CSV 导入）；**Agent 操作面用 MCP**（Model Context Protocol，把一组工具暴露给编码 Agent 的标准协议，轻且能直接包住 RG 现有 HTTP 接口）。

### 4.2 契约优先与"设计/实现解耦"
契约的权威格式是**库文档**（`bloge.visualLibraryAuthoring.v1`）。一份库同时声明类型、算子、函数，示例见第 5 章。核心机关一句话：**`operator.runtime.bindingRef` 缺席 = 设计态（纯契约），存在 = 已实现**。业务/设计方可先把一整套算子/函数/类型契约声明出来（bindingRef 全缺席），就能编排、能模拟、能写验证用例；工程方后续逐个补 `bindingRef`，同一套用例从"红"走到"绿"。

**编译/检查/模拟必须"库契约感知"、零 runtime 依赖。** 这是要补的关键点：Agent 写 DSL 时，编译（preview）、检查（gate.check）、模拟（simulate）三个动作都**按调用显式传入所引用的库契约 id（`libraryRefs`）**并**对这些库契约解析**，而非对"运行时已注册算子"解析。这样一份只有契约、无实现的库也能被完整编译/检查/模拟。设计上不引入会话态绑定——每次调用显式带库 id，行为可预测、可测、可回归（决策依据见 D7）。

**设计态（speccing）判定与技术边界**：一个工具只要引用的算子里存在任一 `bindingRef` 缺席，就处于**设计态**。技术上，设计态算子**不能被降级为可执行 `.bloge`**（后端代码生成器对 `lowering.mode=design` 的算子给出诊断 `visual.codegen.designOnlyOperator` 并拒绝降级）。因此设计态工具只能在**可视化草稿层被模拟**（红侧），不能真跑、不能发布；等引用算子全部补齐 `bindingRef` 后，图才能被降级成可执行 `.bloge`、转"绿"、可发布。精确状态机见附录 C。

### 4.3 核心资产：golden 场景（不是 fixture）
一个"依赖桩固定值（node fixture）"只是零件；真正值得长期沉淀的核心资产是 **golden 场景**：

> golden 场景 = { 业务事实输入 given + 每个依赖的桩行为 dependency stubs + 期望业务结论 expected oracle + 意图 intent }

它编码"**这个业务在这种事实下应该得到什么**"。golden 场景以**表格**承载，一行一个用例。完整列 schema 见附录 B。

**区分 GOLDEN 与 REGRESSION**（决定了这套是"业务 TDD"还是"锁现状"）：**GOLDEN** 的期望是**业务独立认定的"应然"**，由人（业务专家）判定、与实现无关，**可以对着实现为"红"**（业务希望这样但实现还没做到——这批红就是**业务待办**）；**REGRESSION** 的期望是**当前实现的实际输出**，用来**锚现状、防漂移**，它的红意味着"行为被意外改变"。不分开就只能锁现状、无法驱动应然（D9）。

**生命周期由意图/类别驱动，而非统一保留期**：GOLDEN/REGRESSION 走"长期资产"生命周期，临时点验用例走"短期可弃"，解决"把核心资产按 30 天统一删除"的错误（D11）。

### 4.4 红→绿闭环、测试金字塔、业务待办
一套 golden 用例集有稳定身份 `goldenSetId`，绑定"哪个工具 + 哪份契约"。**红**：对**契约态（可视化草稿 + 设计态算子）**跑模拟，依赖用契约合成桩，通过=规格自洽、接得通，对 GOLDEN 还校验人写期望与契约形状一致，红**不证明真实行为**；**绿**：同一套对**绑定齐全的可执行投影**跑零外呼模拟，依赖仍由批准用例控制，通过=实现绑定与纯图逻辑达标。关键是**同一 goldenSetId、同一批用例**从契约态走到实现态是一条身份连续、可见的线；契约或用例集合变化才重开 golden 线，当前目标实现指纹变化则保留该线但使旧证据与签署失效。精确模型（goldenSetId 计算、漂移重开）见附录 C。

**测试金字塔分层**（每层映射 RG 已有能力）：**单元**（单算子/函数/决策表隔离）、**契约**（工具 I/O 签名 + 对依赖的调用符合依赖契约）、**集成**（多个已发布工具组合——一个已发布工具可作算子拖进更大图）、**冒烟**（端到端快速跑通）。红→绿因此按层聚合。

**红色 GOLDEN 用例集 = 业务待办（Definition of Done）**：任何时刻"业务希望但实现还没达到"的行为，就是那批红色 GOLDEN，天然是可执行、可度量的业务待办清单。

### 4.5 Agent 操作面：MCP 工具目录
RG 以**内置在 RG 后端的 Java 模块**提供 MCP 服务（复用 RG 已有用途鉴权与集成身份，D5）。会话固定租户/环境/工作负载身份，每个写操作带幂等键。工具按**运营工作流五阶段**组织，每工具标**影响级别**（五级）：`READ`（不改状态）/`DRAFT_WRITE`（改草稿，须过合入前置门+诚实结论）/`PROPOSE`（生成提案，须人在看板显式批准才生效）/`EXECUTE`（跑模拟/演练/基线，0 真实外呼、产证据）/`GOVERNED_WRITE`（写治理资产，需用途鉴权+幂等+常需签署）。工具清单（附精简 I/O）：

**阶段一·查询（全 READ）**：`capability.list`（盘点 API/Feature/Tool）、`library.get/list`（读库契约、看谁设计态谁已实现）、`contract.get`（读业务契约）、`tool.getInstruction`（读工具 Agent 面契约+可产方案枚举）、`scenario.listCases`（读 golden 表）、`verdict.get`（读分层红→绿+四维诚实结论+红色 GOLDEN 待办+治理基线剩余限制）、`evidence.get`（读运行数据透镜，按仅结构/受控数据分权）。

**阶段二·调整**：`library.upsert`（DRAFT_WRITE，就地定义/改**算子库+函数库契约**的 YAML，bindingRef 可缺席）、`feature.compose`/`tool.compose`（DRAFT_WRITE，编排特征/工具的图草稿）、`tool.setInstruction`（DRAFT_WRITE，写工具 Agent 面契约，示例取自 golden）、`scenario.upsertCases`（写/枚举用例；REGRESSION/临时=DRAFT_WRITE，**GOLDEN=PROPOSE**）、`oracle.propose`（PROPOSE，提议业务应然）、`scenario.setDependencyBehavior`（DRAFT_WRITE，配依赖桩行为）。

**阶段三·编译/检查（全 READ）**：`dsl.preview`（带 libraryRefs，对契约解析，产诊断+投影+源码位置映射）、`gate.check`（合入前置门）。

**阶段四·模拟/验证（全 EXECUTE，0 真实外呼、产证据）**：`feature.rehearse`（特征模拟演练）、`tool.baseline`（治理基线：多用例×多轮+业务 Oracle+业务指纹稳定+分层红→绿）、`simulate`（快速跑一/一批用例，出单次红→绿+输出符合性）。

**阶段五·发布/治理**：`fixture.promote`（GOVERNED_WRITE，把某节点捕获输出晋级为可复用可治理 fixture，服务端派生作用域/schema/来源，客户端不能注入）、`tool.publishSpec`（PROPOSE，发规格版供评审）、`tool.publish`（GOVERNED_WRITE，发不可变可执行工具，要求红→绿已绿+人工签署）、`readiness.get`（READ，读发布门禁+剩余限制）。

每个工具的**完整 in/out JSON schema、共享信封、错误码目录**见附录 A。

### 4.6 web 看板（只读）
web 不再承担创作，退为审阅看板：分层红→绿板、红色 GOLDEN=业务待办、golden 覆盖、设计态进度、契约/表/证据的结构化投影（把 DSL 与表投影成非程序员可读视图）、以及 GOLDEN 应然提案的批准入口。

### 4.7 信任边界与安全
READ 全开放；DRAFT_WRITE 一律过合入前置门+四维诚实结论；**PROPOSE（GOLDEN 应然、publishSpec）永不自动生效**，落"待批准"由业务专家在看板显式批准；EXECUTE 保证 0 真实外呼；GOVERNED_WRITE 需用途鉴权+幂等+常带人工/四眼签署。**Agent 之所以能大胆改而不失控**：有人拥有的 GOLDEN 应然做锚——Agent 改任何契约/工具/桩都要对这批人写的 GOLDEN 重跑，改坏立刻红。载荷不泄漏：错误信息不回灌外部响应体；数据透镜按仅结构/受控数据分权。

### 4.8 复用地图
**直接复用**：三层能力模型、可视化草稿↔`.bloge` 降级、设计态算子、模拟运行、四维诚实结论、决策表→场景枚举、场景矩阵表格、fixture 治理生命周期、工具发布与"发布物即算子"组合、业务 Oracle 与治理基线（演示态）、合入前置门、库契约格式、已建好的"节点 fixture 服务端派生晋级"接口。**需新建（少量）**：① 内置 RG 的 MCP 服务模块（库契约感知、五阶段工具）；② 设计态判定（落在 bindingRef）；③ 红→绿身份连续线（goldenSetId+分层待办）；④ golden 表接意图驱动生命周期；⑤ 工具 Agent 面契约（示例取自 golden）；⑥ web 只读看板。存量前端代码的具体处置见第 8 章。

---

## 5. 贯穿案例：取消费纠纷处理（worked example）

背景领域：网约车订单取消后，乘客/司机对"取消费"是否合理产生纠纷，客服需给出处置（全额减免 WAIVE_FULL / 维持原判 UPHOLD / 升级人工 ESCALATE_HUMAN）。下面把整套机制从"只有契约"走到"发布"。所有 DSL 均为校订后的真实 BLOGE 语法。

### 5.1 第一步：声明库契约（全部设计态，无实现）
Agent 调 `rg.library.upsert`，提交这份 YAML（只声明类型 + 4 个设计态 `resource-read` 算子；决策逻辑不在库里，在图里）：

```yaml
schemaVersion: bloge.visualLibraryAuthoring.v1
library: { id: ride-cancellation, name: Ride Cancellation Dispute, version: 0.1.0, owner: cx-ops }
defaults: { operatorVersion: 0.1.0, namespace: ride }

types:                                    # 命名类型（字段基元用小写 string/number；集合用 enum；避免未验证的 integer/boolean）
  Order:
    fields:
      orderId: string
      cancelledBy:         { enum: [passenger, driver, platform, system] }
      cancelWindowSeconds: { type: number, minimum: 0 }   # 下单到取消经过的秒数
      feeCharged:          { type: number, minimum: 0 }
      city: string
  Responsibility:
    fields:
      party:      { enum: [passenger, driver, platform, none] }
      confidence: { type: number, minimum: 0, maximum: 1 }
  CityPolicy:
    fields: { freeCancelSeconds: { type: number }, maxFee: { type: number } }   # freeCancelSeconds = 免责时长阈值
  History:
    fields:
      priorWaivedCount: { type: number, minimum: 0 }
      abuseSignal:      { enum: [none, suspected, confirmed] }

operators:                                # 全部无 runtime.bindingRef → 设计态（design-only）
  ride:order-lookup:          { name: Order Lookup,          archetype: resource-read, requiresSecrets: false, input: { orderId: string }, output: { order: Order } }
  ride:responsibility-decide: { name: Responsibility Decide, archetype: resource-read, requiresSecrets: false, input: { order: Order },    output: { responsibility: Responsibility } }
  ride:city-pricing-policy:   { name: City Pricing Policy,   archetype: resource-read, requiresSecrets: false, input: { city: string },    output: { policy: CityPolicy } }
  ride:compensation-history:  { name: Compensation History,  archetype: resource-read, requiresSecrets: false, input: { orderId: string }, output: { history: History } }
```

返回：编译诊断（无错）+ 投影出的 4 个设计态算子。此时它们**只有契约、没有实现**。

### 5.2 第二步：编排工具（图）；设计态下只能在草稿层模拟
Agent 调 `rg.feature.compose`/`rg.tool.compose` 把 4 个接口算子聚合成"取消纠纷上下文"、再接一张决策表产出处置。**在设计态，这张图停留在可视化草稿层用设计态算子编排，只能被模拟（红侧）——设计态算子不能降级为可执行 `.bloge`。** 一旦 4 个算子补上 `runtime.bindingRef`，它就降级为下面这张可执行 `.bloge` 图（绿侧的执行形态，语法照 `loan-decision-policy.bloge` / `user-dashboard.bloge`）：

```bloge
/// 取消费纠纷处理 — 资源支撑的决策表图。ctx.orderId = 争议订单
graph cancellationFeeDisputeHandling {

  node fetchOrder : httpResource {
    input { resourceId = "ride-order-service.lookup", params = { orderId: ctx.orderId } }
    timeout = 3s
    retry = { attempts: 1, backoff: 200ms }
  }
  node decideResponsibility : httpResource {
    input { resourceId = "ride-responsibility-service.decide", params = { orderId: ctx.orderId } }
    timeout = 3s
  }
  node cityPolicy : httpResource {
    input { resourceId = "ride-pricing-service.cityPolicy", params = { city: fetchOrder.output.payload.city } }
    timeout = 2s
  }
  node history : httpResource {
    input { resourceId = "ride-compensation-service.history", params = { orderId: ctx.orderId } }
    timeout = 2s
    fallback = { resourceId: "ride-compensation-service.history", statusCode: 0, payload: { priorWaivedCount: 0, abuseSignal: "none" }, rawBody: "", duration: duration("PT0S"), success: false }
  }

  /// 特征层：可复用的"纠纷上下文"投影（布尔事实先在 transform 算好，再喂决策表——保持决策表输入为简单路径）
  transform disputeFacts {
    party      = decideResponsibility.output.payload.party
    withinFree = fetchOrder.output.payload.cancelWindowSeconds <= cityPolicy.output.payload.freeCancelSeconds
    abuse      = history.output.payload.abuseSignal
  }

  /// 业务规则矩阵；hit=unique 要求规则互斥
  decision_table disputePolicy(
    party      = disputeFacts.party,
    withinFree = disputeFacts.withinFree,
    abuse      = disputeFacts.abuse
  ) hit=unique -> { decision: String, reviewLane: String, ruleId: String } {
    rule (abuse: abuse == "confirmed")                                                               -> { decision: "ESCALATE_HUMAN", reviewLane: "human",         ruleId: "R5" }
    rule (abuse: abuse != "confirmed", party: party == "none",      withinFree: withinFree == true)  -> { decision: "WAIVE_FULL",     reviewLane: "auto",          ruleId: "R1" }
    rule (abuse: abuse != "confirmed", party: party == "passenger", withinFree: withinFree == true)  -> { decision: "WAIVE_FULL",     reviewLane: "auto",          ruleId: "R2" }
    rule (abuse: abuse != "confirmed", party: party == "passenger", withinFree: withinFree == false) -> { decision: "UPHOLD",         reviewLane: "auto",          ruleId: "R3" }
    rule (abuse: abuse != "confirmed", party: party == "driver")                                     -> { decision: "WAIVE_FULL",     reviewLane: "driver-ledger", ruleId: "R4" }
    otherwise                                                                                        -> { decision: "ESCALATE_HUMAN", reviewLane: "human",         ruleId: "R0" }
  }

  /// 处置方案（R4 的 plan 出口）：决策被当作数据消费，汇成图输出
  transform assembleDisputePlan {
    orderId    = ctx.orderId
    decision   = disputePolicy.output.decision
    reviewLane = disputePolicy.output.reviewLane
    ruleId     = disputePolicy.output.ruleId
  }
}
```

（`disputeFacts` 这段 fetch+投影就是"特征"——独立发布后可作为子工具被更大的图复用。）

### 5.3 第三步：决策表的规则视图与 R4 出口
上面 `decision_table disputePolicy` 的规则用表格看即：

| # | party | withinFree（取消秒数 ≤ 免责时长） | abuse | → decision |
|---|---|---|---|---|
| R5 | * | * | confirmed | ESCALATE_HUMAN |
| R1 | none | true | ≠confirmed | WAIVE_FULL |
| R2 | passenger | true | ≠confirmed | WAIVE_FULL |
| R3 | passenger | false | ≠confirmed | UPHOLD |
| R4 | driver | * | ≠confirmed | WAIVE_FULL（司机侧记账 lane） |

R4"决策表出口"的三种形态（详见附录 E，均为真实 DSL 构造）：**方案 plan**（本例）= 决策表输出对象经 `transform` 汇成图输出；**指令/子场景 dispatch**（后段）= 用 `branch on disputePolicy.output.decision { … -> 子工具节点 }` 路由。

### 5.4 第四步：写 golden 表 → 红侧跑 → 得到业务待办
Agent 调 `rg.scenario.upsertCases` 写 golden 表（列 schema 见附录 B；stub 列按图里的节点 id 命名）。GOLDEN 期望是业务应然、走 PROPOSE 由人批准。边界行由**决策表枚举算法**（附录 D）在阈值 `freeCancelSeconds=120` 邻域 `{119,120,121}` 自动生成：

| caseId | category | given.orderId | stub.fetchOrder.payload | stub.decideResponsibility.payload | stub.cityPolicy.payload | stub.history.payload | expect.decision |
|---|---|---|---|---|---|---|---|
| g1 | GOLDEN | O1 | {cancelledBy:passenger, cancelWindowSeconds:90, city:SH, feeCharged:8} | {party:none} | {freeCancelSeconds:120} | {abuseSignal:none} | WAIVE_FULL |
| g2 | GOLDEN | O2 | {cancelledBy:passenger, cancelWindowSeconds:200, city:SH, feeCharged:8} | {party:passenger} | {freeCancelSeconds:120} | {abuseSignal:none} | UPHOLD |
| g3 | GOLDEN | O3 | {cancelledBy:driver, cancelWindowSeconds:60, city:SH, feeCharged:8} | {party:driver} | {freeCancelSeconds:120} | {abuseSignal:none} | WAIVE_FULL |
| n1 | NEGATIVE | O4 | {cancelledBy:passenger, cancelWindowSeconds:60, city:SH, feeCharged:8} | {party:passenger} | {freeCancelSeconds:120} | {abuseSignal:confirmed} | ESCALATE_HUMAN |
| b1/b2/b3 | BOUNDARY | O5 | {cancelWindowSeconds:119/120/121, …passenger} | {party:passenger} | {freeCancelSeconds:120} | {abuseSignal:none} | WAIVE_FULL/WAIVE_FULL/UPHOLD |

Agent 调 `rg.simulate`（带 `libraryRefs=[ride-cancellation]`）跑红侧：`decision_table`/`transform` **真实执行**（纯逻辑），4 个 `resource-read` 节点**被契约合成桩/fixture 替换**，0 真实外呼。假设作者**初版决策表漏了 R4（司机责任）**：则 **g3 = RED_FAIL**——这条红色 GOLDEN 就是一条**业务待办**："司机责任应全额减免，但当前规则没覆盖"。Agent 补上 R4 → 重跑 → g3 RED_PASS。红侧全绿 = 规格自洽、工具接得通（但尚未证明真实行为）。

### 5.5 第五步：补实现（加 bindingRef）→ 图降级为可执行 → 绿侧跑
工程方为 4 个 `resource-read` 算子补 `runtime.bindingRef`（分别指向真实资源 `ride-order-service.lookup` 等），再次 `rg.library.upsert`。设计态解除，图不再含 design-only 算子，**可降级为 §5.2 那张可执行 `.bloge`**。工具状态从 **SPECCING** 转 **IMPLEMENTING**。Agent 调 `rg.tool.baseline` 跑绿侧：同一 `goldenSetId`、同一批用例、binding 已全部解析，但 EXECUTE 仍以用例行为替换外部依赖，保持 0 真实外呼；因此绿侧证明实现图与纯业务逻辑达标，不把真实集成健康冒充为已证明。全部 GREEN_PASS 且跨轮业务指纹稳定 → 状态转 **IMPLEMENTED**。红→绿是**同一条身份连续线**（附录 C）。

### 5.6 第六步：发布
Agent 调 `rg.readiness.get` 看发布门：绿全过 ✓、但"负责人签署"未满足 → 人在看板签署 → Agent 调 `rg.tool.publish`（GOVERNED_WRITE，幂等）→ 发布为不可变可执行工具，可被 Agent 调用，也可作算子拖进更大图（集成层）。

**本案例演示了全部关键机制**：契约先于实现、决策表出 plan、golden 表（含枚举边界）、红色 GOLDEN=业务待办、红→绿同 id 身份线、设计态→可执行降级→发布、0 真实外呼的模拟。

---

## 6. 决策因素与依据

> 每条给出：问题、候选、选定与理由、放弃其它的原因、对长期可维护性的考量。

**D1 战略路径——叠加对象主线并演进为业务 TDD 运行时（非重建、非单点打磨）。** 问题是体验/信息架构而非能力缺失。候选：单点打磨/推倒重建/Studio 合并/叠加主线并演进。选最后者：单点打磨不收敛（每加能力就重造割裂）；重建丢弃大量已测引擎（模拟/契约/fixture/Oracle/基线），回归风险高、复用低；Studio 合并没除根（仍按系统对象组织）。叠加+开关复用全部已测能力、可回退、最快兑现价值，避免"大爆炸"改动把系统带进不可维护。

**D2 产品重心——Agent 优先、web 退看板。** 若核心资产是文本，大模型对文本操作远强于表单。候选：web 优先/双创作面并重/Agent 优先。选 Agent 优先：web 表单低带宽、不可 diff/版本化、对大模型不友好；双创作面并重要长期维护两套创作逻辑。大模型能力持续增强，Agent 优先顺势，web 收敛为看板显著缩小长期维护面积。

**D3 资产载体——逻辑用 DSL、例证用表格。** 逻辑（契约/规则/结构）是大模型强项、可 diff/复用；把用例硬塞进 DSL 是过度设计——用例天然是表格（决策表、example 表的成熟形态），表对业务方更可读、对 Agent 好读写、最大复用已有场景矩阵/枚举/CSV 导入；全用表单则回到 Agent 不友好老路。二者各用最合适载体、演进不互相绑架。

**D4 Agent 操作面——MCP，非自建 CLI、非让 Agent 直接拼 HTTP。** MCP 是把工具暴露给编码 Agent 的原生标准协议，轻且能直接包住现有 HTTP；自建 CLI 要长期维护额外产物；直接拼 HTTP 把鉴权/幂等/错误语义甩给调用方、易错。不维护第二套 CLI，跟随 MCP 生态。

**D5 MCP 宿主——RG 内 Java 模块，非独立进程。** RG 是 Java/Spring，已有成熟用途鉴权与集成身份。内置模块可直接复用鉴权、同仓演进，避免鉴权/协议两处双写。代价是同生命周期部署，可接受。

**D6 契约单位——库文档，非逐个契约。** 一份库把类型/算子/函数内聚，且算子 `bindingRef` 有无就是"设计/实现解耦缝"；Agent 编辑一个 YAML 最顺手。逐个 upsert 会打散关系、解耦缝无处安放。把解耦缝落在已存在字段上，机制稳定、不发明新概念。

**D7 库契约绑定——按调用显式传库引用，非会话态绑定。** 每次调用显式带库 id，解析上下文明确、无隐藏状态；会话级绑定引入隐式状态，增加复杂度、难测难回归。无隐式状态的接口更可预测、可单测、易维护。

**D8 核心资产单元——golden 场景，非 node fixture。** fixture 只是"某依赖在某情况返回什么"（零件）；golden 场景（输入+桩+期望+意图）才编码"业务应该怎么做"。把"核心资产/长期保留"语义挂在场景上，粒度才对，避免像现在把核心资产按 30 天误删。

**D9 用例语义——显式分 GOLDEN 与 REGRESSION。** TDD 价值在"表达应然并驱动实现"；不分、把当前输出当期望就只能锁现状。GOLDEN=人定应然（可对实现为红=待办），REGRESSION=锚现状防漂移。防止"回归用例冒充规格"腐蚀业务真相。

**D10 红→绿——身份连续一条线，非模拟与认证两个割裂动作。** 本质是"同一套规格，先证自洽（红，无实现），再证实现达标（绿）"。同一 goldenSetId 连续线才能追踪进度与漂移，避免"绿了但其实是另一套用例"的错觉。

**D11 生命周期——由意图/类别驱动，非全局统一保留期。** GOLDEN/REGRESSION 是长期核心资产、临时点验是短期可弃，生命周期本就不同；用意图/类别驱动，核心资产不被统一保留期误删。

**D12 信任边界——GOLDEN 应然人拥有、Agent 只提议。** Agent 能大胆改，正因有人拥有的 GOLDEN 做锚+诚实结论+治理；若让 Agent 自行落地应然，等于让它悄改业务真相。故 GOLDEN/发布落 PROPOSE 由人批准。这条边界让"提效"与"业务正确性不失控"长期共存。

**D13 建设策略——复用非重建。** 业务 TDD 闭环在 RG 内部（演示态）已跑通，三层模型/模拟/Oracle/基线/契约格式都在；复用并把演示态泛化为一般能力，是最低回归、最快兑现的路径。

---

## 7. 工程实施计划

> 实现状态（2026-09-03）：W1–W5 及贯穿门禁已在 `resource-gateway-examples` 中完成。MCP 使用现代无状态请求头并保留 legacy initialize；严格输入/输出 Schema 与真实响应同步；Agent overlay 与原子幂等响应使用同一数据源持久化；Web 看板为 `STRUCTURE_ONLY` 投影；Appendix-D 枚举和七种依赖行为已接入共享测试内核。第 5 章案例已有服务级端到端测试，覆盖“契约→编排→错误 GOLDEN 产生业务待办→R4 Oracle 修复/人工批准→RED→绑定→GREEN/READY→版本与目标实现绑定签署→目录漂移失效→重新基线→不可变发布”。新 goldenSetId 重开独立矩阵，旧线仍可按身份查询。实际运行和运维步骤见 [`resource-gateway-agent-tdd-mcp.md`](resource-gateway-agent-tdd-mcp.md)。本文后续章节保留设计决策和可追溯依据，不替代运行证据。

一次性造通完整循环（查询→调整→编译→模拟→发布），分周推进，每步复用已有能力、只补 4.8 新件。

- **W1·MCP 骨架 + 阶段一查询（READ）。** 建 RG 内 Java MCP 模块、接已有用途鉴权；实现 8 个只读工具（包住已有只读投影）。验收：Agent 能盘点现状、读契约/库/golden/待办；全 READ、零风险。
- **W2·阶段三编译 + 阶段四模拟（库契约感知）。** 实现 `dsl.preview/gate.check`（带 libraryRefs、对契约解析）、`feature.rehearse/tool.baseline/simulate`（带 libraryRefs）。验收：一份只有契约（bindingRef 全缺席）的库能被编译/检查/模拟，真实外呼为 0，红→绿按层出结论。
- **W3·阶段二调整（DRAFT_WRITE + GOLDEN PROPOSE）。** 实现 `library.upsert/feature.compose/tool.compose/tool.setInstruction/scenario.upsertCases/oracle.propose/setDependencyBehavior`；GOLDEN 落"待批准"。验收：Agent 能改库契约/工具/用例，GOLDEN 走人工批准，改动过合入前置门+四维结论。
- **W4·阶段五发布/治理 + 红→绿身份线 + 生命周期。** 实现 `fixture.promote/publishSpec/publish/readiness.get`；实现 goldenSetId 身份线+分层业务待办；golden 表接意图驱动生命周期。验收：端到端跑通第 5 章案例（声明契约→编排→写 golden→红→补实现→图降级→绿→发布）；核心 golden 不被统一保留期误删。
- **W5·web 只读看板。** 分层红→绿板、红色 GOLDEN=业务待办、覆盖、GOLDEN 批准入口、DSL/表结构化投影。
- **贯穿门禁**：READ 全开；DRAFT_WRITE 过合入前置门+四维；PROPOSE 人批准；EXECUTE 保证 0 真实外呼；GOVERNED_WRITE 需用途鉴权+幂等+签署；每层有组件测试与真实服务/浏览器测试。

**需冻结的关键数据结构**（详见附录）：库契约 `bloge.visualLibraryAuthoring.v1`（types/operators(含 archetype、bindingRef)/functions，见附录及第 5 章）；决策表出口消费方式（plan 经 `transform` 成图输出 / dispatch 经 `branch` 路由，附录 E）；golden 用例列 schema（附录 B）；`goldenSetId` 计算与状态机（附录 C）；枚举算法（附录 D）；工具 Agent 面契约 ToolAgentContract（附录 F）；MCP 信封与错误码（附录 A）。

---

## 8. 存量前端（1.3.0）处置：KEEP / REUSE / EXTEND / DEMOTE，无 DELETE

| 存量件（路径） | 现在做什么 | 处置 | 理由 |
|---|---|---|---|
| `…/spine/authorSpine.ts`（`resolveSpine`/`parseToolCoordinate`/`toolCoordinateHref`） | 对象主线导航状态；`ToolCoordinate` 仅 UI 态、从不进协议 | **KEEP** 并升为只读看板默认导航 | 看板仍需按对象导航；无协议影响 |
| `…/external-api/*`（`externalApiFormToDescriptor`/`toDesignContract`/`inferSchema`）+ `externalApiTransport.ts` | web 表单定义外部 API、写既有 admin 端点，`inferSchema` 从样例推 schema（有界、确定性、防环） | **DEMOTE 为逃生舱** | canonical 改为 `rg.library.upsert`；表单保留给非 Agent 用户走同一端点；`inferSchema` 逻辑复用；不删 |
| `GraphNodeFixturePromotionController/Service` | 节点捕获输出晋级为治理 fixture，服务端派生坐标、作用域闭合先于读 | **KEEP**，增加显式多输出端口选择并由 `rg.fixture.promote` 包装 | 保留既有单输出语义，同时消除多输出歧义 |
| `…/decision-scenario/decisionScenario.ts`（有界谓词文法、`representativeValues` 阈值邻域、确定性枚举、opaque→需 authorSamples） | 决策表→场景枚举 | **KEEP 并升为 canonical 枚举引擎**，接 `rg.scenario.upsertCases` 枚举模式 | 正是附录 D 的算法，已存在 |
| `…/tool/toolModel.ts` `ToolSignature`（仅 I/O + 身份，无 Agent 面描述） | 工具签名 | **EXTEND 为 `ToolAgentContract`**（加 description/whenToUse/examples-from-golden） | 补"工具缺 Agent 面契约"缺口（附录 F） |
| 7 个平行顶层 Studio（创作面） | 各系统对象创作/治理面 | **收敛入只读看板**，创作面降为逃生舱，spine 默认导航 | Agent 优先下 web 不承担创作 |

结论：存量以 KEEP/REUSE/EXTEND 为主，少量 DEMOTE，**无 DELETE**——迁移风险低。

---

## 9. R1–R5 需求可追溯映射

| 需求 | 内容 | 落到设计/工具 | 案例步骤 |
|---|---|---|---|
| **R1** | 可用地定义一个外部 API（接口） | 库算子 `archetype: resource-read` 契约 + `rg.library.upsert`；存量表单 `inferSchema` 复用 | 5.1 声明 4 个接口算子 |
| **R2** | 多个 API 聚合成工具、带 I/O 契约 | `rg.feature.compose`/`rg.tool.compose`；三层模型 API→Feature→Tool | 5.2 组图（fetch+transform+decision_table） |
| **R3** | API+工具可用 fixture 模拟；fixture 成累积资产 | `rg.simulate`/`rg.feature.rehearse`（0 真实外呼）+ `rg.fixture.promote`（治理生命周期，替代"按天删"） | 5.4 红侧跑；`stub.*` 桩/fixture |
| **R4** | 决策表枚举 facts→场景；出口为方案/子场景/指令 | 枚举算法（附录 D）+ 出口 plan（`decision_table`→`transform`）/ dispatch（`branch` 路由，附录 E） | 5.3 决策表出 plan；5.4 边界枚举 |
| **R5** | 决策表可 fixture 模拟 | `rg.simulate` 中 `decision_table` 真跑、依赖用桩/fixture；`setDependencyBehavior` 配桩行为 | 5.4 决策表真跑+接口被桩 |

---

## 10. 落地后遗留 / 新暴露问题及预计解法

1. **Oracle 授权是组织问题**：GOLDEN 应然需业务专家判断，权责要落到角色与流程——解：为"业务作者/审批人"设计明确批准流与权限。
2. **副作用侧的"绿"需治理**：纯逻辑 TDD 干净，但客服有真副作用（退款、开工单），模拟里被桩掉，真实实现的绿要接住真副作用——解：绿侧真副作用接入受管写入/对账/治理证据（也是 dispatch 出口落地的前置）。
3. **组织角色反转**：要求业务方拥有并持续策划 golden 规格、工程方达标——解：产品与流程为业务作者角色配套。
4. **golden 集规模化治理**：覆盖盲区、重复、随契约变更过时——解：覆盖度分析、用例指纹去重、随 contractFingerprint 漂移自动标 STALE 并一键幂等重枚举（附录 C/D）。
5. **web 看板须真好用于审阅**：DSL/表投影成非程序员可读视图是前提——解：看板作专项，重点做红→绿板、业务待办、覆盖。
6. **MCP 面安全**：防越权、超量、载荷泄漏——解：用途鉴权+配额/速率+审计+数据透镜分权（附录 A 错误码含 `FORBIDDEN_PURPOSE` 等）。
7. **演示态泛化为一般能力**：当前 Oracle/基线只服务演示数据包——解：分阶段用真实库契约与 golden 集替换演示源、保持接口稳定。
8. **外部 API 出站无 host 治理（存量审查发现）**：现仅校验参数表达式、无 host 白名单/SSRF 防护——解：资源执行边界加 host 白名单+egress 策略（附录 A `EGRESS_NOT_ALLOWED`）；simulate 永不外呼，红侧不受影响。
9. **两写非原子（存量审查发现）**：定义外部 API 会写"资源描述符"和"设计契约"两处、非原子——解：幂等 upsert+对账，或事务包裹。
10. **多输出端口算子无法晋级 fixture（存量审查发现）**：晋级要求单一无歧义输出端口——解：`fixture.promote` 增显式端口选择参数（附录 A）。

---

## 附录 A · MCP 线级契约与错误码

**共享信封**：会话初始化固定 `{tenant, env, workload}` 身份（带外建立，不逐调用传）；写工具入参含 `idempotencyKey: string`。统一响应：
```json
{ "ok": true,  "data": {}, "diagnostics": [ {"severity":"error|warn|info","code":"","message":"","sourceSpan":{}} ] }
{ "ok": false, "error": { "code":"", "message":"(无外部响应体/业务载荷)", "retryable": false, "details": {} } }
```
**稳定错误码目录**：`UNAUTHENTICATED` / `FORBIDDEN_PURPOSE` / `DRAFT_NOT_FOUND` / `LIBRARY_NOT_FOUND` / `COMPILE_ERROR` / `GATE_REJECTED` / `SPECCING_NOT_EXECUTABLE`（设计态不可真跑/发布）/ `GOLDEN_REQUIRES_APPROVAL` / `IDEMPOTENCY_CONFLICT` / `SCHEMA_NONCONFORMANT` / `AMBIGUOUS_OUTPUT_PORT` / `RETENTION_POLICY_VIOLATION` / `EGRESS_NOT_ALLOWED` / `PUBLISH_GATE_NOT_MET` / `SIM_REAL_CALL_DETECTED`（模拟中检测到真实外呼，失败关闭）/ `COMBINATORIAL_CAP_EXCEEDED`。

**代表性工具完整 in/out**（其余同信封，I/O 见 4.5）：

`rg.library.upsert`（DRAFT_WRITE）
```json
// in
{ "libraryYaml": "string(bloge.visualLibraryAuthoring.v1)", "idempotencyKey": "string" }
// out.data
{ "libraryId":"", "version":"",
  "operators":[ {"id":"ride:order-lookup","archetype":"resource-read","speccing":true,"bindingRef":null} ],
  "functions":[], "types":[""] }
// errors: COMPILE_ERROR, LIBRARY_NOT_FOUND, IDEMPOTENCY_CONFLICT, GATE_REJECTED
```
`rg.simulate`（EXECUTE）
```json
// in
{ "toolRef":"", "libraryRefs":["ride-cancellation"], "cases":{"caseSetRef":""},
  "adhocFixtures":[ {"nodeId":"fetchOrder","value":{"payload":{}}} ] }
// out.data
{ "goldenSetId":"", "side":"RED",
  "byLayer": {"unit":{"pass":0,"fail":0},"contract":{},"integration":{},"smoke":{}},
  "cases":[ {"caseId":"g3","layer":"contract","verdict":"RED_PASS|RED_FAIL|GREEN_PASS|GREEN_FAIL|GREEN_BLOCKED",
             "oracle":{"invariant":"","held":true},"schemaConformant":true} ],
  "realExternalCalls": 0 }
// errors: SPECCING_NOT_EXECUTABLE(请求绿侧但仍有设计态), SCHEMA_NONCONFORMANT, SIM_REAL_CALL_DETECTED
```
`rg.verdict.get`（READ）
```json
// out.data
{ "toolRef":"", "state":"SPECCING|IMPLEMENTING|IMPLEMENTED", "goldenSetId":"",
  "byLayer":{"unit":{"red":{},"green":{}},"contract":{},"integration":{},"smoke":{}},
  "businessBacklog":[ {"caseId":"g3","reason":"driver responsibility not covered","owner":"cx-ops"} ],
  "honestVerdict":{"dimensions":[ {"name":"business-correctness","status":"","proves":"","doesNotProve":""} ]},
  "baseline":{"status":"NO_GO|GO","remainingLimitations":["RUNTIME_ENV_NOT_ATTESTED","LIVE_INTEGRATION_NOT_ATTESTED","OWNER_SIGNOFF_ABSENT"]} }
```
`rg.fixture.promote`（GOVERNED_WRITE）
```json
// in
{ "draftId":"", "nodeId":"fetchOrder", "outputPort":"payload",
  "fixtureId":"", "category":"", "retentionDays":0, "redactPaths":["/payload/feeCharged"], "idempotencyKey":"" }
// out.data
{ "fixtureId":"", "scope":"(服务端派生)", "schemaRef":{}, "sourceKind":"SCENARIO|SAMPLE", "lineageRef":{} }
// errors: AMBIGUOUS_OUTPUT_PORT, SCHEMA_NONCONFORMANT, RETENTION_POLICY_VIOLATION, DRAFT_NOT_FOUND
```
`rg.scenario.upsertCases`（DRAFT_WRITE；GOLDEN 行触发 PROPOSE）
```json
// in
{ "caseSetRef":"", "rows":[ {"caseId":"","category":"GOLDEN","given":{},"stubs":{},"expect":{}} ],
  "enumerateFrom":{"decisionTableRef":"disputePolicy","mode":"per-rule|combinatorial","maxCases":500,
    "oracleOwner":"cx-policy-owner","authorSamples":{"opaqueInput":["sample-a","sample-b"]}}, "idempotencyKey":"" }
// out.data
{ "caseSetRef":"", "rows":[], "proposed":[{"caseId":"","awaiting":"human-approval"}], "enumeratedCount":0 }
// errors: COMBINATORIAL_CAP_EXCEEDED, GOLDEN_REQUIRES_APPROVAL, SCHEMA_NONCONFORMANT
```

---

## 附录 B · golden 表列 schema（与既有场景矩阵映射）

一行=一个 golden 用例。列：`caseId`；`category: {GOLDEN,REGRESSION,NEGATIVE,BOUNDARY,FAULT,SECURITY}`（映射既有 `ScenarioCaseCategory`）；`lifecycle: {DRAFT,ACTIVE,STALE,RETIRED}`；`qualityState: {DESIGNED_NOT_RUN,READY,STALE,BLOCKED}`；`given.<field>`（业务输入，类型由工具输入契约约束）；`stub.<nodeId>`（依赖桩，三选一：内联 JSON 值 / `fx:<fixtureId>` / 行为指令 `{behavior: RETURN|ERROR|DELAY|TIMEOUT|REPLAY|OBSERVE|MUST_NOT_CALL,…}`）；`expect.<path>`（GOLDEN=业务作者写、REGRESSION=从运行捕获）；`oracleOwner`（GOLDEN 必填）；`sourceRunRef`（REGRESSION 必填）。映射：`given.*`→既有业务事实列，`stub.*`→既有依赖行为单元格，`expect.*`→既有断言单元格，可直接复用既有场景矩阵/CSV 导入。

---

## 附录 C · 状态机与 goldenSetId

**goldenSetId 计算**：
```
contractFingerprint = stableHash( 工具 I/O 契约 + 所有被引算子契约(archetype/input/output) )   // 不含 bindingRef 指向的实现
goldenSetId         = stableHash( toolRef + contractFingerprint + sortedSet(caseIds) )
```
实现（bindingRef 目标）变化**不改** goldenSetId → 红→绿保持同一线，但旧 GREEN 证据与人工签署失效；契约变或用例集增删 → 新 goldenSetId → 线重开（旧线按 `{toolRef,goldenSetId}` 归档可查，矩阵不继承）。证据指纹另含草稿 revision、Oracle/stub 语义和每个 bindingRef 当前解析出的目标实现指纹；通过的 ACTIVE 行推进为 `qualityState=READY`，该运营状态不参与证据语义指纹。

**工具实现就绪状态机**：
```
SPECCING     : ∃ 被引算子 bindingRef 缺席（图不可降级为可执行 .bloge）
               允许 preview/check/simulate(红)/rehearse/baseline(红)/publishSpec；禁 真跑/publish/绿
   │ (所有被引算子补齐 bindingRef → 图可降级)
   ▼
IMPLEMENTING : bindingRef 全齐、绿侧未全过；允许 绿侧 simulate/baseline
   │ (所需层绿侧全过)
   ▼
IMPLEMENTED  : 绿侧全过 → 允许 publish
```
**golden 用例状态机**：`lifecycle: DRAFT→ACTIVE→STALE→RETIRED`；`qualityState: DESIGNED_NOT_RUN→READY→STALE→BLOCKED`。GOLDEN 由 Agent 写入 → `PROPOSED` → 人批准 → `ACTIVE`。contractFingerprint 漂移时相关用例 `ACTIVE→STALE`、goldenSetId 重开。
**单用例分层判定**：`RED_PASS/RED_FAIL`（契约态）、`GREEN_PASS/GREEN_FAIL/GREEN_BLOCKED`（实现态；BLOCKED=仍有设计态依赖）。工具聚合=矩阵 [layer × {red,green}]。

---

## 附录 D · 决策表 → 场景枚举算法

输入：一张 `decision_table` 的规则集（每条 `rule (inputName: 谓词, …) -> {输出}`）。
1. **谓词解析**（有界文法）：比较 `== != < <= > >=`、范围 `a <= x < b`、成员 `in {…}`、缺省 `otherwise`、不可解析 `opaque`。
2. **代表值推导**：`enum`→每成员；数值阈值 `t`→邻域 `{t-ε,t,t+ε}`；范围 `[a,b]`→`{a-ε,a,mid,b,b+ε}`；`opaque`→**需作者提供 `authorSamples`，否则用例标 `BLOCKED`**。
3. **枚举模式**：`per-rule`（默认，规模≈规则数+边界邻居，线性）；`combinatorial`（笛卡尔，受 `maxCases` 封顶，超则失败关闭 `COMBINATORIAL_CAP_EXCEEDED`）。
4. **确定性**：枚举是 `(表,代表值策略,模式,封顶)` 的纯函数——同输入→同用例集+同顺序，支持漂移后**幂等重枚举**。
5. **落表**：边界邻域行→`BOUNDARY`；规则代表行→`GOLDEN`（期望取自表结论，但 `PROPOSED` 待人批准）。

（第 5.4 的 `b1..b3` 即 `withinFree` 阈值邻域 `{119,120,121}` 的 per-rule 边界枚举；此逻辑复用存量 `decisionScenario.ts`。）

---

## 附录 E · 决策表出口：plan 与 dispatch（R4，均为真实 DSL 构造）

- **plan（方案，本期）**：`decision_table` 的输出对象经 `transform` 汇成图输出——即第 5.2 的 `assembleDisputePlan`。golden 的 `expect.*` 直接对 plan 字段（`decision` 等）断言。
- **dispatch（指令/子场景，后段）**：用 `branch`（已验证语法）按决策路由到调用已发布子工具的节点：
  ```bloge
  branch on disputePolicy.output.decision {
    "ESCALATE_HUMAN" -> escalateNode
    otherwise        -> autoResolveNode
  }
  ```
  约束：路由目标节点调用**不可变的已发布工具**（其算子 token 于实施时确认）；simulate 中这些子工具节点被 mock，保 0 真实外呼、可在目标实现存在前先红测路由逻辑；golden 对 dispatch 断言落在 `{targetRef, boundInputs}`（路由正确性），与目标自身 golden 分开。
- **分期理由**：plan 无副作用、可立即纳入纯逻辑 TDD；dispatch 牵涉真副作用治理（见第 10 章第 2 条），契约先行、执行后段。

---

## 附录 F · 工具 Agent 面契约数据模型（ToolAgentContract）

```
ToolAgentContract {
  toolRef, name, title,
  description,                 // 面向 Agent：这个工具做什么
  whenToUse,                   // 何时该选它
  inputs:  [ { name, type, meaning, required, constraints } ],
  outputs: { kind: plan|object|scalar, plan?: { decisions:[…], actionKinds:[…] } },
  errors:  [ { code, meaning } ],
  examples:[ { fromGoldenCaseId, input, output } ]     // 从 ACTIVE GOLDEN 投影，单一真源
}
```
**"示例取自 golden"机制**：`examples` 不单独撰写，而是从该工具的 **ACTIVE GOLDEN 用例投影**（`given`→input，期望 oracle→output）。改示例=改 golden，杜绝文档漂移。这把存量 `ToolSignature`（仅 I/O+身份）扩展为带 Agent 面语义的契约。
