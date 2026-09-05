# Resource Gateway 产品技术演进详细设计方案 v1.4.5:声明式业务编排产品前门

> 承接 v1.4.4(四实体纯函数运行时,P1–P7 已落地并有测试证据)。v1.4.5 建产品前门层:业务负责人自然语言表意 → Agent 生产四实体 → 业务语言审阅(不见 DSL)→ 两门 → 发布;特征供给契约优先交接;运营数据回流闭环。读者无需预先了解代码库,每个概念在首次出现处定义。

## 1 背景与目标
- **v1.4.4 已交付**:四实体(特征/场景/指令/解法)纯函数运行时、确定性特征信任 token、写效应交接与受控执行对账、发布治理与快照冻结、published-only 运行时、MCP 工具面(`rg.feature.*`/`rg.scenario.*`/`rg.instruction.*`/`rg.solution.*`)。
- **v1.4.4 差距(产品化)**:业务负责人仍需理解 DSL 与工具;审阅面呈现技术态,非业务语言;特征无契约优先交接(写指令有 `rg.engineering.handoff`,特征无对等交接);无熟练度自适应;`rg.solution.performance` 无真实数据回流。
- **v1.4.5 目标**:建产品前门。业务负责人经 AI 工作台自然语言表意 → Agent 产四实体草案 → 业务语言审阅看板(规则矩阵/处置清单/红绿板,无 DSL)→ 应然批准门 + 发布签署门 → 发布。特征供给与写指令同构交接。运营信号回流驱动政策迭代。
- **结构性收益**:治理对象从流程路径(组合爆炸)降为规则矩阵(线性);正确性从事故暴露前移到发布前证明;信任从假设变机制。

## 2 要解决的问题
- **H1 表意前门缺失**:业务负责人需懂工具/DSL 才能创作。
- **H2 审阅无业务语言层**:现有面呈现技术态(DSL/契约 JSON)。
- **H3 特征无契约优先交接**:设计态特征无 handoff,工程无待办入口。
- **H4 无熟练度自适应**:单一交互不匹配新手/专家。
- **H5 无运营数据回流**:`rg.solution.performance` 无真实处置信号来源。

根因:v1.4.4 建了运行时与治理,未建面向业务负责人的前门层。

## 3 价值空间(结构性收益)
| 维度 | v1.4.4 前(命令式画布现状) | v1.4.5 后(声明式 + 前门) | 结构性收益 |
|---|---|---|---|
| 治理对象 | 数千节点流程路径 | 规则矩阵 + 处置清单 | 治理复杂度 组合爆炸 → 线性 |
| 正确性 | 生产事故暴露 | 发布前 golden 逐条证明 | 质量 事后 → 事前 |
| 审计 | 追踪节点流转 | 规则命中路径 + 推理 + 签名事实 | 可审计 不可 → 可 |
| 变更 | 全图重验、排期发版 | 局部改 + 局部回归 | 周期 排期 → 当日 |
| 创作 | 懂工具/DSL | 自然语言表意,不见 DSL | 门槛 工程 → 业务 |
| 信任 | 靠人写对 | 事实签名 + 双人门 + 快照 | 信任 假设 → 机制 |

## 4 产品架构总纲
- **分层**:前门层(v1.4.5 新建)叠加于运行时层(v1.4.4 复用)。前门不改运行时契约。
- **工作流九阶段**:意图表达 → 特征供给 → 编译预检 → 分层测试 → 应然批准门 → 写效应交接 → 发布签署门 → 运行时处置 → 运营回流。
- **角色六类 + 用途门**:业务负责人(AUTHORING)、特征工程(**FEATURE_ENG,新增**)、通用工程(非 MCP)、一线客服 Agent(EXECUTION)、运营(READ)、业主签署人(GOVERNANCE)。
- **架构基石(承 1.4.4)**:纯函数解法 + Agent 编排采集 + 特征非图节点 + 内建算子 scenarioCall/instructionCall + 子场景有界递归。
- **前门原则**:业务不见 DSL(P2);审阅业务语言(P1);契约优先(P6);可测性即信任(P4);双人双门(P9);熟练度自适应(P10)。

## 5 决策依据
- **E1 NL→四实体归 Agent,平台给契约与编译**:平台提供 `rg.dsl.reference.get`(上下文 + 指纹)、`rg.feature/scenario/instruction.define`、`rg.solution.compose`、`rg.dsl.preview`(均 v1.4.4 已有)。不把自然语言理解塞进平台。相较"平台内建 NLU",此方案复用现有工具、保持平台无对话状态。
- **E2 审阅看板业务语言投影**:读现有 `ruleMatrix`(scenario.define 返回)、指令契约、`baseline` 红绿、`readiness` 门,投影为规则矩阵/处置清单/红绿板/特征卡/发布卡,不呈现 DSL。相较"直接展示契约 JSON",此方案对业务负责人可读。
- **E3 特征交接同构写指令**:新增 `rg.feature.handoff` + 特征设计态(evaluationRef 空,v1.4.4 已有 `feature.speccing()`)+ `FEATURE_ENG` 门;镜像 `EngineeringHandoffService`。相较"特征全由工程预建",此方案让业务声明、工程履行,契约优先。
- **E4 熟练度自适应**:引导模式(结构化槽位 + 缺口反问)↔ 专家模式(自由 NL + 差异)。Agent 侧编排,平台无状态。相较"单一交互",此方案匹配新手与专家。
- **E5 运营回流零泄漏**:处置信号(rulePath/instruction/result)聚合 → 命中/升级/处置分布;不含业务 payload。相较"原始处置留存",此方案守零泄漏。

## 6 工程实现设计

> 依据 v1.4.4 代码库:特征/指令契约 record 有 `speccing()` 设计态;`EngineeringHandoffService` 聚合设计态写指令;`AgentTddStateRepository` 通用资产 find/save/saveIfRevision/executeOnce;`IntegrationOperation` 用途门;`SolutionGovernanceService` 发布;`FeatureEvaluationBackend`/`ReconciliationAdapter` 为可插拔接口;看板 `agent-tdd.html`。

### 6.1 模块与包结构
```
前端(resource-gateway-examples/src/main/frontend/src):
  workbench/   # 表意工作台:双模、三步向导、专家单框、草案预览
  review/      # 审阅看板:规则矩阵/处置清单/验证板/特征卡/发布卡
  ops/         # 运营看板:命中分布/升级率/处置分布/红应然
后端增量(com.leanowtech.bloge.gateway.solution):
  feature/FeatureHandoffService.java          # 特征契约交接(同构写指令)
  board/BoardProjectionService.java           # 业务语言投影
  ops/OperationsInsightService.java           # 运营信号聚合
  demo/{RideResponsibilityBackend,CancelWithinFreeBackend}.java  # 场景求值后端
  demo/{RefundReconciliationAdapter,TicketReconciliationAdapter}.java  # 对账适配器
鉴权:IntegrationOperation 增 AGENT_TDD_FEATURE_ENG
MCP:ResourceGatewayAgentTddTools 增 rg.feature.handoff;rg.solution.performance 接真实回流
```

### 6.2 特征契约交接(`rg.feature.handoff`,同构写指令)
特征设计态:`FeatureContract.evaluationRef` 空 → `speccing()=true`(v1.4.4 已有;`rg.feature.evaluate` 对 speccing 特征回 `FEATURE_BINDING_REQUIRED`)。
```java
@Service
public final class FeatureHandoffService {
  public static final String FEATURE_HANDOFF = "SOLUTION_FEATURE_HANDOFF";
  private final AgentTddStateRepository states;
  private final SolutionEntityRegistry registry;
  private final ObjectMapper mapper;

  /** 聚合设计态特征 → 特征交接单;不授求值绑定权。 */
  public Map<String,Object> submit(String featureRef, IntegrationRequestContext identity) {
    if (identity == null || !IntegrationOperation.AGENT_TDD_PROPOSE.accepts(identity.purpose()))
      throw new AgentTddToolException("FORBIDDEN_PURPOSE", "Authoring purpose is required.");
    String scope = AgentTddMutationService.scopeKey(identity);
    FeatureContract feature = registry.requireFeature(scope, featureRef);   // 不存在→REFERENCE_UNRESOLVED
    if (!feature.speccing())
      throw new AgentTddToolException("GATE_REJECTED", "Feature already bound.");
    ObjectNode data = mapper.createObjectNode();
    data.put("ticketId", "feat-handoff:" + shortFingerprint(scope, featureRef));
    data.put("featureName", feature.featureRef());
    data.set("requiredOutput", feature.output());
    data.set("requiredInputs", feature.inputs());
    data.put("evaluationKind", feature.evaluationKind().name());
    data.put("businessSemantics", feature.fact());
    data.put("status", "OPEN");
    AgentTddStoredAsset stored = states.save(scope, FEATURE_HANDOFF, feature.featureRef(), data);
    return Map.of("ticketId", data.get("ticketId").asText(), "featureName", feature.featureRef(),
        "status", "OPEN", "revision", stored.revision());
  }
}
```
履行(特征工程,`AGENT_TDD_FEATURE_ENG` 门):提交 `evaluationRef` → 特征退设计态;平台跑特征单测(fixture → 断言 `output.type`)→ `status=VERIFIED` → `RegisteredFeature.state=READY`。交接状态机 `OPEN → IMPLEMENTED → VERIFIED`(镜像写指令 `OPEN → IMPLEMENTED → CLOSED`)。

### 6.3 表意工作台(Agent 编排 + 平台契约)
平台侧无新契约(复用 v1.4.4 工具)。Agent 侧编排协议(FE + Agent 实现):
```
IntentExpressionInput{ proficiencyMode, domainContext(rg.dsl.reference.get → 特征/指令/语法 + contextFingerprint), utterance | slotResponses }
双模状态机:
  GUIDED: STEP_BASIS → STEP_RULES → STEP_ACTIONS(每步反问触发见 §附录甲.C)
  EXPERT: utterance → 一次编译 + 差异
逐工具产四实体:
  特征 → rg.feature.define(缺求值 → 设计态 → 触发 rg.feature.handoff)
  场景 → rg.scenario.define(返回 ruleMatrix/tree)
  指令 → rg.instruction.define(写缺 governance → WRITE_GOVERNANCE_REQUIRED)
  解法 → rg.solution.compose(返回 inputContract/pureFunctionProjection/authoringReceiptFingerprint)
预检:rg.dsl.preview(带 contextFingerprint;漂移 → CONTEXT_DRIFT 重取)
→ FourEntityDraft(草案预览 = compose 的 ruleMatrix + inputContract)
```
熟练度推断:引导默认;单框输入完整度阈值(覆盖四实体缺口数 = 0)触发专家。显式开关覆盖。
FE:双栏(左输入/右草案预览:规则矩阵 + 处置清单 + 覆盖缺口),草案状态机 `DRAFTING→COMPILING→{COMPILED|COMPILE_ERROR}→READY_FOR_TEST`。业务界面不含 DSL。

### 6.4 审阅看板(业务语言投影)
```java
@Service
public final class BoardProjectionService {
  /** 聚合四实体 + 测试 + 就绪 → 业务语言看板视图(无 DSL)。 */
  public BoardView project(String scope, String solutionRef) {
    SolutionContract sol = registry.requireSolution(scope, solutionRef);
    ScenarioContract scn = registry.requireScenario(scope, sol.rootScenarioRef());
    RuleMatrixView matrix = ruleMatrix(scn);                       // 条件事实 × 规则 → 处置
    List<DispositionCard> dispositions = sol.instructions().stream()
        .map(ref -> dispositionCard(registry.requireInstruction(scope, ref))).toList();  // 效应/结果/对账
    RedGreenView redGreen = baselineView(scope, solutionRef);     // 分层红绿 + 业务待办
    List<FeatureCard> features = featureCards(scope, sol);        // 事实/类型/来源/状态/交接
    ReadinessView readiness = readinessView(scope, solutionRef);  // 四门
    return new BoardView(matrix, dispositions, redGreen, features, readiness);  // 无 DSL 字段
  }
}
```
面板字段级见 §附录甲.B。两门:门① = baseline 应然批准(复用 v1.4.4 maker-checker review,maker 业务 + checker 运营);门② = `rg.solution.publish` 签署(复用 `SolutionGovernanceService`,maker 业务 commit + checker 业主 publish)。payload-bearing 详情只经 HUMAN no-store endpoint(承 v1.4.4 看板策略)。

### 6.5 运营回流(数据飞轮)
```java
@Service
public final class OperationsInsightService {
  /** 聚合运行时处置信号 → 命中/升级/处置分布 + 政策缺口;零业务 payload。 */
  public OperationsInsight aggregate(String scope, String solutionRef, Window window) {
    List<Disposition> ds = signals.read(scope, solutionRef, window);  // rulePath/instructionRef/result(结构化)
    var hit = ds.stream().collect(groupingBy(d -> lastRule(d.rulePath()), counting()));
    double escalation = ratio(ds, d -> isEscalate(d.instructionRef()));
    var disp = ds.stream().collect(groupingBy(d -> resultKind(d.result()), counting()));
    List<String> redGolden = testing.redGolden(scope, solutionRef);
    List<PolicyGap> gaps = gaps(hit, escalation, redGolden);          // 高升级 ruleId + 红应然 → 建议修订
    return new OperationsInsight(share(hit), escalation, share(disp), redGolden, gaps);
  }
}
```
`rg.solution.performance` 从 `OperationsInsight` 投影。信号来源:`SolutionLiveInvocationService` 完成处置后落 `OperationsSignal`(rulePath/instructionRef/result;不含 suppliedFacts)。零泄漏:聚合分布,非原始处置。

### 6.6 场景求值后端 + 对账适配器(取消费纠纷,使剧本可跑)
```java
// 确定性特征求值后端(implements FeatureEvaluationBackend)
final class RideResponsibilityBackend implements FeatureEvaluationBackend {
  public JsonNode evaluate(FeatureContract f, JsonNode inputs, IntegrationRequestContext id) {
    return mapper.valueToTree(responsibilityClient.decide(inputs.path("orderId").asText()).party());
  }
}
final class CancelWithinFreeBackend implements FeatureEvaluationBackend { /* 计算图/规则 → boolean */ }

// 对账适配器(implements ReconciliationAdapter,承 v1.4.4 甲.C.3)
final class RefundReconciliationAdapter implements ReconciliationAdapter {
  public String adapterRef(){ return "recon:refund-v1"; }
  public String downstreamSystem(){ return "refund-service"; }
  public ObservedEffect observe(String orderId, JsonNode in) {
    var s = refundClient.getRefundState(orderId);
    return new ObservedEffect(orderId, Map.of("decision", s.status(), "amount", s.amount()));
  }
}
final class TicketReconciliationAdapter implements ReconciliationAdapter { /* ticket-service 回读 */ }
```
后端与适配器为示例域代码(demo 包),经 `FeatureEvaluationDispatcher` 与 `ReconciliationAdapterRegistry` 装配。

### 6.7 存储与鉴权
- 特征交接单 → 通用表 `agent_asset` kind=`FEATURE_HANDOFF`(复用仓储,无新表)。
- 用途门:`IntegrationOperation.AGENT_TDD_FEATURE_ENG(Set.of("AGENT_TDD_FEATURE_ENG"))`;履行特征求值绑定;非 Agent 创作/执行门。
- 运营信号:`agent_asset` kind=`OPERATIONS_SIGNAL`(或专表,若查询量大)。

### 6.8 MCP 接线
- 新增 `rg.feature.handoff`(PROPOSE 提交;FEATURE_ENG 履行 endpoint 非 Agent 目录)。
- `rg.solution.performance` 接 `OperationsInsightService`。
- `McpToolCatalog` 工具数 +1;`AGENT_TDD_FEATURE_ENG` 履行不入 Agent 目录。

### 6.9 测试策略
| 区 | 关键测试 |
|---|---|
| 特征交接 | 设计态特征 → handoff 聚合;已绑定 → GATE_REJECTED;跨 scope → REFERENCE_UNRESOLVED 不泄名 |
| 审阅投影 | BoardView 无 DSL 字段;规则矩阵/处置/红绿/就绪 对齐源 |
| 表意编排 | 双模状态机;缺口反问;contextFingerprint 漂移 → 重取 |
| 运营回流 | 聚合分布正确;OperationsSignal 无 suppliedFacts(零泄漏) |
| 场景后端 | party/withinFree 求值 + 令牌;refund/ticket 对账 match/mismatch |
| 端到端 | §8 剧本:表意 → 审阅 → 两门 → 发布 → 运行时,零外呼,无 skipped/mock |

## 7 MCP 工具面(v1.4.5 增量)
| 工具 | 影响 | in | out | 状态 |
|---|---|---|---|---|
| `rg.feature.handoff` | PROPOSE | {featureRef, idempotencyKey} | {ticketId, featureName, status, revision} | 新增 |
| 特征求值绑定(履行,不入 Agent 目录) | FEATURE_ENG | {featureRef, evaluationRef} | {featureName, state=READY, verified} | 新增 |
| `rg.solution.performance` | READ | {solutionRef} | {hitDistribution[], escalationRate, dispositionDistribution[], redGolden[], policyGaps[]} | 接真实回流 |
| 表意/审阅/运营 FE | — | — | — | 新增(FE,读现有工具) |

错误码增量:`FEATURE_BINDING_REQUIRED`(v1.4.4 已有)、`GATE_REJECTED`(已绑定特征交接)、`CONTEXT_DRIFT`(表意预检)。

## 8 端到端剧本(产品前门,取消费纠纷)
1. 业务负责人经工作台表意(引导:三步槽位;或专家:自由 NL)→ Agent 产四实体草案。
2. 缺求值的特征(party/withinFree)→ `rg.feature.handoff` → 特征工程建后端(RideResponsibilityBackend/CancelWithinFreeBackend)→ 退设计态。
3. 审阅看板核对规则矩阵 + 处置清单(无 DSL)。
4. 补桩分层测试(`rg.solution.baseline` side=RED,零外呼)→ 红绿板 → **门① 应然批准**(maker 业务 + checker 运营)。
5. 写指令(refund/escalate)→ `rg.engineering.handoff` → 通用工程补 bindingRef + 对账适配器 → 逻辑绿(side=GREEN)→ 受控写对账。
6. **门② 发布签署**(maker 业务 commit + checker 业主 publish)→ 发布快照。
7. 运行时:一线客服 Agent 采集(order-picker + evaluate + token)→ `rg.solution.invoke` → 处置(结果 + 推理)→ 运营信号回流。

## 9 工程实施计划(v1.4.5)
- **Q1 特征契约交接**:`rg.feature.handoff` + `AGENT_TDD_FEATURE_ENG` + `FeatureHandoffTicket` + 履行退设计态。验收:设计态特征可交接、可履行、退设计态;跨 scope 零泄漏。
- **Q2 审阅看板业务语言投影**:`BoardProjectionService` + FE 五面板 + 两门接线。验收:BoardView 无 DSL;两门可批准/签署。
- **Q3 表意工作台**:FE 双栏双模 + Agent 编排协议 + 熟练度推断。验收:业务负责人不见 DSL 产四实体草案;引导/专家双模。
- **Q4 运营回流**:`OperationsInsightService` + `OperationsSignal` 采集 + `rg.solution.performance` 接线 + FE。验收:命中/升级/处置分布真实;零泄漏。
- **Q5 场景后端 + 对账适配器**:party/withinFree 后端 + refund/ticket 适配器。验收:§8 剧本端到端可跑,零外呼。

## 10 落地后遗留
1. 交互特征标准化组件库(order-picker 契约 + 对话协议)。
2. 存量 SOP 图 → 四实体迁移器(双轨)。
3. 多解法编排(降级链)。
4. 熟练度推断精度(阈值 → 模型)。
5. 运营信号长期留存与专表性能。

## 11 实施追踪
本节只记录落入代码并有测试证明的能力；未完成项仍按计划标识，不把设计文本当实现证据。
| 阶段 | 当前状态 | 计划证据 |
|---|---|---|
| Q1 特征契约交接 | 已实现 | `FeatureHandoffService` + `rg.feature.handoff` + 非 MCP `FEATURE_ENG` 履行端点；OPEN→IMPLEMENTED→VERIFIED、输出类型、幂等与跨 scope 零泄漏测试 |
| Q2 审阅看板投影 | 后端已实现 | `BoardProjectionService` 五面板 + HUMAN/GOVERNANCE no-store HTTP；无 DSL/图实现引用与跨证据 join 测试。前端五面板和两门交互待实现 |
| Q3 表意工作台 | 前端已实现 | `/workbench/?create=business-solution` 双栏双模、三步引导、切换保留、上下文漂移失败关闭、熟练度提示和无 DSL 四实体预览；Agent-host 编译桥接的真实端到端证据并入 Q5 |
| Q4 运营回流 | 计划 | OperationsInsightService 聚合 + 信号零泄漏 |
| Q5 场景后端适配器 | 计划 | 求值后端 + 对账适配器 + 剧本端到端零外呼 |

## 附录 甲 · 工程细粒度展开

### A 特征交接单(字段级 + 状态机)
```jsonc
// agent_tdd_assets kind=SOLUTION_FEATURE_HANDOFF, ref=featureName
{ "ticketId":"feat-handoff:<fp>", "featureName":"responsibility.party",
  "requiredOutput":{ "type":{"enum":["passenger","driver","platform","none"]} },
  "requiredInputs":{ "orderId":"string" }, "evaluationKind":"API",
  "businessSemantics":"责任方", "status":"OPEN|IMPLEMENTED|VERIFIED",
  "evaluationRef":null, "acceptanceRef":"featureUnit:responsibility.party" }
```
状态机:`OPEN`(业务声明设计态)→ `IMPLEMENTED`(特征工程提交 evaluationRef)→ `VERIFIED`(特征单测通过 → 退设计态 READY)。
测试:设计态 → OPEN;已绑定 → GATE_REJECTED;履行非 FEATURE_ENG → FORBIDDEN_PURPOSE;单测失败 → 回 IMPLEMENTED。

### B 审阅看板投影(字段级)
`BoardView`(无 DSL):
| 面板 | 字段 |
|---|---|
| ruleMatrix | {conditions:[featureName], rules:[{ruleId, cells:{featureName:predicateText}, disposition}], otherwise:{disposition}} |
| dispositions | [{instructionName, effectText(读|写), resultFields:[{name,type}], reconciliation:{downstream,reconKey}}] |
| redGreen | {byLayer:{feature,scenario,solution}{pass,fail}, cases:[{caseId,givenFacts,expected,actual,verdict}], backlog:[{caseId,reason}]} |
| featureCards | [{featureName, fact, type, sourceText(API/计算图/模型/用户), state(设计态|就绪), handoffStatus}] |
| publishCard | {gates:{logicGreen,implementationBound,writeReconciled,ownerSignoff}, publishable} |
FE 组件:面板只读 + 门① 批准按钮(maker/checker)+ 门② 签署按钮;payload 详情经 HUMAN no-store。

### C 表意工作台双模(状态机 + 编排 + 交互契约)
引导三步状态机(承 v2 PRD §9.1):
| 步 | 收集 | 反问触发 | 反问 |
|---|---|---|---|
| STEP_BASIS | decisionBasis → 特征引用 | 规则条件引用未声明依据 | "判断依据还缺 X 事实,补充?" |
| STEP_RULES | decisionRules → 场景规则 | 无兜底 | "其余情况如何处置?" |
| STEP_ACTIONS | dispositionActions → 指令 + 对账 | 写动作无下游 | "写到哪个系统?怎么核对?" |
草案状态机:`DRAFTING→COMPILING→{COMPILED|COMPILE_ERROR}→READY_FOR_TEST`。
Agent 编排:每步 → `rg.*.define`;缺特征 → 设计态 + `rg.feature.handoff`;COMPILE_ERROR → 安全诊断(稳定码)→ 澄清。
交互契约:切专家=保留槽位;提交步=追问或进下一步;提交专家文本=编译+差异;进入测试=校验 READY_FOR_TEST。约束:业务不见 DSL;bindingRef 恒空。

### D 运营回流(聚合算法 + 零泄漏)
```
aggregate(scope, solutionRef, window):
  signals = read(OPERATIONS_SIGNAL where scope, solutionRef, window)   // {rulePath, instructionRef, result}
  hitDistribution = normalize(count by lastRule(rulePath))
  escalationRate  = count(isEscalate(instructionRef)) / total
  dispositionDistribution = normalize(count by resultKind(result))
  redGolden = testing.redGolden(scope, solutionRef)
  policyGaps = [ {ruleId, symptom:"high-escalation", suggestedRevision} for ruleId in hot(escalation) ∪ redGolden ]
零泄漏:OperationsSignal 落库时剥离 suppliedFacts;只留 rulePath/instructionRef/resultKind。
```

### E 场景求值后端 + 对账适配器(Java 示例)
```java
@Component class RideResponsibilityBackend implements FeatureEvaluationBackend {
  public JsonNode evaluate(FeatureContract f, JsonNode in, IntegrationRequestContext id){
    return mapper.valueToTree(client.decide(in.path("orderId").asText()).party()); } }   // → enum
@Component class CancelWithinFreeBackend implements FeatureEvaluationBackend {
  public JsonNode evaluate(FeatureContract f, JsonNode in, IntegrationRequestContext id){
    return BooleanNode.valueOf(policy.withinFree(in.path("orderId").asText())); } }         // → boolean
@Component class RefundReconciliationAdapter implements ReconciliationAdapter {
  public String adapterRef(){ return "recon:refund-v1"; }
  public String downstreamSystem(){ return "refund-service"; }
  public ObservedEffect observe(String orderId, JsonNode in){
    var s = refundClient.getRefundState(orderId);
    return new ObservedEffect(orderId, Map.of("decision", s.status(), "amount", s.amount())); } }
```
