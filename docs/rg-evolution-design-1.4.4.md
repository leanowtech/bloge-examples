# Resource Gateway 产品技术演进详细设计方案:面向客服解法运营的四实体 Agent 运行时(纯函数架构)

> 读者无需预先了解本代码库。每个概念在首次出现处给出定义。

## 1. 背景与目标

**RG 定义**:一个给 AI Agent 造业务能力的平台。业务人(客服政策负责人一类)不写代码,经本地编码 Agent(Codex 一类)对话表达意图。Agent 用声明式文本(BLOGE DSL)把意图落成可执行业务资产。平台承担编译、模拟验证、治理发布。

**现有能力**:契约优先(声明契约、暂缓实现的形态,名为"设计态")、零外呼模拟(不触真实系统的逻辑跑通,依赖用桩替代)、golden 验证用例(业务写应然、人工批准、持续回归)、DSL 创作支持(取参考、预编译、安全诊断)、业务可读看板。

**产品化差距**:业务分层对象缺失;写效应处置(退款、处罚、工单)欠缺;契约到工程的交接缺失;工具的组织维度是技术动作,不是业务剧本;端到端剧本缺失。

**目标**:RG 演进为"解法即纯函数、Agent 编排采集"的客服解法运营运行时。解法、场景、指令是纯函数(输入是特征值,输出是处置);特征是声明式采集契约,不是图节点;采集与交互的复杂度归于调用方 Agent,RG 的图不碰交互、不挂起。人经 Agent 工作台表达意图,Agent 代劳创作与测试(人定边界),web 退化为审阅看板。

## 2. 要解决的问题
- G1:解法一等对象缺失。
- G2:写效应处置欠缺。
- G3:契约到工程的交接缺失。
- G4:工具的组织维度是技术动作。
- G5:端到端剧本缺失。

根因:业务分层实体模型缺失、Agent 操作面缺失、剧本编排缺失。

## 3. 价值空间

| 维度 | 现状 | 目标 |
|---|---|---|
| 业务组织 | 一张图混合取数、决策、动作 | 特征(采)、场景(判)、指令(做)、解法(合)四层分离 |
| 架构复杂度 | 交互入图需要 session/await | 解法是纯函数,RG 不碰交互与异步,复杂度归 Agent |
| 运营闭环 | 缺失 | 解法:问题分布 → 表现 → 差距 → 调优 |
| 写效应 | 欠缺 | 设计态写契约 → 模拟 → 交工程 → 受管执行 + 对账 |
| 创作方式 | web 表单(复杂业务成本高) | Agent 代劳,web 退审阅 |
| 事实可信 | 无 | 确定性特征经 RG 求值并签 token,阻断 Agent 编造 |

## 4. 四实体模型(纯函数架构)

### 4.1 架构总纲:纯函数 + Agent 编排采集
解法在 Agent 视角是一个契约化的 function call。Agent 知道调它需要哪些参数(特征值),按特征契约找到求值路径、委派给对应 runtime 求值、凑齐参数、调用纯函数解法。据此:
- **解法与场景是纯函数图**:输入是特征值,输出是处置或出口;不取特征、不交互、不挂起。
- **特征是声明式采集契约**(非图节点):它是解法输入参数,以及"如何拿到值"的契约。
- **采集归 Agent**:确定性特征调 RG(`rg.feature.evaluate`,返回可信值 + token);交互特征由 Agent 承担(问用户、拉前端组件)。RG 不碰交互与异步。
- **内建算子**:`scenarioCall`、`instructionCall` 保留(纯决策、派发,消费传入的特征值);`featureCall` 删除。
- **副产品**:v1.4.3 的"仅支持 graph 顶层"从限制转为优势——解法是纯 graph,不需要 session/await。

### 4.2 特征 Feature(声明式采集契约)
契约文档 `bloge.solutionAuthoring.v1` 的 `features` 段。字段:

| 字段 | 含义 |
|---|---|
| `output.type` | 原子类型化事实的类型 |
| `evaluationKind` | `API` / `DAG` / `MODEL` / `INSTRUCTION_RESULT`(确定性,同步)；`USER_CONVERSATION` / `USER_COMPONENT`(交互) |
| `determinism` | `DETERMINISTIC` / `NON_DETERMINISTIC`(MODEL,版本锚定)/ `INTERACTIVE`(USER_*) |
| `inputs` | 求值输入(orderId 一类) |
| `evaluationRef` | 确定性类的实现引用(resourceId / 图名 / 模型版本 / 指令 + 路径) |
| `componentRef` / `promptRef` | 交互类的前端组件 / 对话提示引用 |

```yaml
features:
  responsibility.party: { output:{type:{enum:[passenger,driver,platform,none]}}, evaluationKind:API,  determinism:DETERMINISTIC, inputs:{orderId:string}, evaluationRef:"ride-responsibility-service.decide#$.party" }
  cancel.withinFree:     { output:{type:boolean}, evaluationKind:DAG, determinism:DETERMINISTIC, inputs:{orderId:string}, evaluationRef:graph:feat_cancel_within_free }
  passenger.sentiment:   { output:{type:{enum:[calm,upset,angry]}}, evaluationKind:MODEL, determinism:NON_DETERMINISTIC, inputs:{transcript:string}, evaluationRef:"model:sentiment-v3" }
  dispute.orderSelected: { output:{type:string}, evaluationKind:USER_COMPONENT, determinism:INTERACTIVE, componentRef:"order-picker-v1" }
```
求值分派与信任:`rg.feature.evaluate(featureRef, inputs)` 服务确定性类,返回 `{ value, evaluationToken }`(RG 对 `featureRef + inputs + value + 时间戳` 签短期 token);交互类不经 RG,由 Agent 采集。解法调用时,确定性特征值携带有效 evaluationToken(RG 校验签名、新鲜度、与 featureRef/inputs 的绑定),否则拒绝——阻断 Agent 编造事实。交互特征值直供,标记 `source=USER`。

### 4.3 场景 Scenario(纯决策)
`scenarios` 段:输入是一组特征引用;`decision` 是决策表(`hitPolicy:unique` 加 `otherwise` 必填);每行 `outlet.kind ∈ {SUB_SCENARIO | INSTRUCTION | TERMINAL}`,带 `ref` 与 `bind`(目标输入 ← 特征)。场景是可组合决策树:出口是更具体的子场景。DSL 实现是 `decision_table` 图(纯):
```bloge
graph scn_cancel_dispute {
  transform facts { party = ctx.party  withinFree = ctx.withinFree  abuse = ctx.abuse }
  decision_table outlet( party = facts.party, withinFree = facts.withinFree, abuse = facts.abuse )
    hit=unique -> { outletKind: String, ref: String, ruleId: String } {
    rule (abuse: abuse == "confirmed")                                                           -> { outletKind:"INSTRUCTION",  ref:"ins:escalate-human-ticket", ruleId:"R5" }
    rule (abuse: abuse != "confirmed", party: party == "none",      withinFree: withinFree==true) -> { outletKind:"INSTRUCTION",  ref:"ins:refund-waive-full",     ruleId:"R1" }
    rule (abuse: abuse != "confirmed", party: party == "passenger", withinFree: withinFree==false)-> { outletKind:"INSTRUCTION",  ref:"ins:uphold",                ruleId:"R3" }
    rule (abuse: abuse != "confirmed", party: party == "driver")                                 -> { outletKind:"SUB_SCENARIO", ref:"scn:driver-liability",      ruleId:"R4" }
    otherwise                                                                                    -> { outletKind:"INSTRUCTION",  ref:"ins:escalate-human-ticket", ruleId:"R0" }
  }
  out = { outlet: outlet.output }
}
```
子场景递归封装在内建 `scenarioCall`(边界:编译期无环检查、深度上限;`otherwise` 保证全覆盖;每条路径有限步到达 INSTRUCTION 或 TERMINAL)。解法图调一次 `scenarioCall(root)`,保持 DAG。子场景是一等对象,支持独立测试与复用。

### 4.4 指令 Instruction(纯动作 + 推理)
`instructions` 段:输入是声明的输入特征;输出是 `{ result: 结构化处置, reasoning: 必填(结果的成因) }`;`effect ∈ {READ | WRITE}`;写指令 `bindingRef` 缺席等于设计态,声明 `writeGovernance{ downstreamSystem, reconciliationKey, reconciliationAdapterRef }`。工具是指令的可执行发布态。
```yaml
instructions:
  refund-waive-full:
    inputs: { orderId: string, feeCharged: number }
    output: { result: {type:{fields:{decision:{enum:[WAIVED]}, waiveAmount:number}}}, reasoning: required }
    effect: WRITE
    writeGovernance: { downstreamSystem: "refund-service", reconciliationKey: "orderId", reconciliationAdapterRef: "recon:refund-v1" }
  uphold: { inputs:{orderId:string}, output:{result:{type:{fields:{decision:{enum:[UPHELD]}}}}, reasoning:required}, effect: READ }
```

### 4.5 解法 Solution(纯函数图)
`solutions` 段:`problem` 加 `inputs`(特征值参数)加 `scenarioTree.root` 加 `instructions` 加 `golden`。可执行形态是纯函数图(输入是特征值 → `scenarioCall` → `instructionCall` → 处置):
```bloge
graph sol_cancel_dispute {
  input { party: String  withinFree: Boolean  abuse: String  orderId: String  feeCharged: Number }
  node decide : scenarioCall { input { scenarioRef = "scn:cancel-dispute", party = ctx.party, withinFree = ctx.withinFree, abuse = ctx.abuse } }
  branch on decide.output.outlet.outletKind { "INSTRUCTION" -> dispatch  otherwise -> escalate }
  node dispatch : instructionCall { input { instructionRef = decide.output.outlet.ref, orderId = ctx.orderId, feeCharged = ctx.feeCharged } }
}
```
发布后是一个契约化 function-call 工具,输入契约是特征值(确定性者携带 token)。

### 4.6 Agent 编排采集流程(运行时,图之外)
```
1. rg.solution.getContract(sol:cancel-dispute) → 需要 {party, withinFree, abuse, orderId, feeCharged} 与每项的 evaluationKind
2. 采集:
   - dispute.orderSelected (USER_COMPONENT) → Agent 拉 order-picker → 用户选 O1
   - party / withinFree / abuse / feeCharged (API/DAG,依赖 orderId) → rg.feature.evaluate({featureRef, inputs:{orderId:O1}}) → {value, evaluationToken}
3. rg.solution.invoke(sol:cancel-dispute, { 值..., token... }) → RG 校验确定性特征 token → 跑纯函数 → {result, reasoning}(写指令经受管执行)
```

### 4.7 确定性特征信任
确定性特征值来源是 `rg.feature.evaluate`;RG 对结果签短期 token(绑定 featureRef、inputs、value、时间戳);`rg.solution.invoke` 校验 token 有效后采信,否则拒绝。交互特征值来源是用户,标记 `source=USER`。此机制阻断 Agent 编造确定性事实。

### 4.8 写效应完整路径
```
设计态写指令(effect=WRITE,无 bindingRef,声明对账适配器)
 → 零外呼模拟(写桩掉,无真实副作用) → golden 验证
 → rg.engineering.handoff(工程交接单:输入特征 / 输出契约含推理 / 下游 / 对账键 / 验收 golden)
 → 工程实现补 bindingRef → 逻辑绿(绑定齐全、纯逻辑达标,零外呼)
 → 受控写执行 + 对账(独立用途,Agent 拿不到;每下游一个对账适配器,回读下游 → 映射 observed → 与 expected 比对)
 → 人工签署发布
```

### 4.9 测试金字塔(值钉定,与 kind 无关)
| 层 | 对象 | 手段 |
|---|---|---|
| 单元 | 特征求值器、指令 | fixture,断言单个特征值 / 指令输出 |
| 契约 | 场景 | 给定特征值 → 断言命中出口(子场景 / 指令 + 绑定) |
| 集成 | 解法(纯函数) | 给定特征值 → 断言处置(结果 + 推理),依赖桩掉、零外呼 |
| 冒烟 | 解法 | 快速跑通 |
| golden | 解法 | 业务应然,人工批准,持续回归 |

golden 里的特征值钉为 `given`——解法作为纯函数对值测试,交互与模型特征照此测试。

### 4.10 与现有机制的关系
- **复用**:图引擎、零外呼模拟、golden 治理、发布与发布物、DSL 创作支持、决策表算子、v1.4.3 的预编译与安全诊断。
- **重构**:把"一张 tool 图混合取数 + 决策 + 动作"拆成——特征(声明式采集契约,不入图)、场景图(决策表)、指令(算子)、解法(纯函数图);MCP 与看板按四层重框。
- **删除**:featureCall。
- **新增**:`rg.feature.evaluate` 与信任 token、场景与解法一等面、写效应与工程交接与对账适配器、指令推理输出。

## 5. 决策依据
- **D1 纯函数解法 + Agent 编排采集**:交互与异步复杂度移出 RG 图,归于交互本体 Agent;解法是纯函数,易测、零外呼、不需要 session/await。相较"图内取特征 + 挂起",此方案简化实现、提升模型纯度。
- **D2 特征声明式、按 kind 分派**:特征声明"是什么",runtime 按 kind 找匹配求值器;支持 API/DAG/MODEL/交互的异构求值路径,不污染图。
- **D3 确定性特征 RG 求值 + 签 token**:阻断 Agent 编造事实;交互事实来源是用户,允许直供。相较"全信 Agent",此方案提升可信度;相较"解法内自取",此方案不引入图内取数。
- **D4 场景出口 {子场景 | 指令 | 终结} + scenarioCall 有界递归**:分层决策树,子场景一等可复用;相较静态展平,此方案避免组合爆炸、保留模块化。
- **D5 指令输出 = 结果 + 推理**:客服处置可解释、可审。
- **D6 写效应 design-only → 交接 → 受控执行 + 对账适配器**:Agent 不触发真实写(安全底线);每下游对账口径不同,适配器可插拔。
- **D7 特征/场景/指令/解法四层分离**:契合软工分层测试,各层独立创作、测试、治理。
- **D8 允许较大重构换模型纯度**(用户定):接受 tool 图拆分的重构成本。

## 6. 工程实现设计

> 依据现有代码库模式:契约解析仿 `AuthoringDocumentDecoder`;注册表仿 `OperatorLibraryRegistry`;内建算子在 `DefaultVisualOperatorCatalog` 注册加 `DefaultOperatorRegistry.registerRaw` 实现;图是 `GraphDraft`,编译是 `DslCompiler`,模拟是 `VisualGraphSimulationService`,真跑是 `VisualGraphRunService`;规范哈希与签名是 `VisualBundleFingerprint`;存储是通用表 `agent_tdd_assets` 加 `AgentTddStateRepository`;鉴权是 `IntegrationOperation` 用途加 `McpToolImpact`。

### 6.1 模块与包结构
新增包 `com.leanowtech.bloge.gateway.solution`(与 `agenttdd` 平级,复用其基础设施):
```
solution/
  SolutionAuthoringDecoder.java        # bloge.solutionAuthoring.v1 解析 → SolutionDocument
  SolutionDocument.java (record)       # features/scenarios/instructions/solutions
  contract/{Feature,Scenario,Instruction,SolutionContract}.java (record)
  registry/{Feature,Scenario,Instruction,Solution}Registry.java   # canonical upsert/revision/fingerprint
  lowering/{FeatureLowering,ScenarioLowering,InstructionLowering,SolutionLowering}.java  # 实体 → GraphDraft
  op/{ScenarioCallOperator,InstructionCallOperator}.java          # 内建纯算子
  scenario/{ScenarioTreeValidator,ScenarioTreeEvaluator}.java     # 静态检查 + 求值
  feature/{FeatureEvaluationService,FeatureValueToken,FeatureTokenSigner,FeatureTokenVerifier,FeatureTokenKeyProvider}.java
  write/{EngineeringHandoffService,WriteExecutionRunner,ReconciliationAdapter,ReconciliationAdapterRegistry,WriteReconciliation}.java
  mcp/SolutionAgentTools.java          # MCP 分派(接 ResourceGatewayAgentTddTools 或独立)
```

### 6.2 实体契约:文档、解析、注册
- 文档 schema:`bloge.solutionAuthoring.v1`(4 段,字段见 §4.2–4.5)。
- 解析:`SolutionAuthoringDecoder.decode(byte[]) → DecodeResult<SolutionDocument>`,拒未知字段,失败返回结构化诊断(供 `rg.*.define` 回 `COMPILE_ERROR` 加稳定码,不透传自由文本)。
- 注册(canonical,仿 `OperatorLibraryRegistry.upsert` 的 revision/metadata/fingerprint):
```java
interface FeatureRegistry {
  Feature upsert(Feature f, RevisionMetadata meta);   // 返回带 revision 与 contractFingerprint
  Optional<Feature> find(String featureRef);
  List<Feature> all();
}
// Scenario/Instruction/Solution Registry 同构
```
- 契约指纹:`entity.contractFingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper, canonical(entityContract), MAX)`;不含实现(bindingRef 目标),用于 golden 身份线与漂移判定(复用现有 goldenSetId 机制)。

### 6.3 实体 → GraphDraft lowering

| 实体 | lowering 目标 | 要点 |
|---|---|---|
| Feature(API) | 单 `httpResource` 图 加 `transform out{value=payload.<path>}` | 设计态(无 evaluationRef 绑定)报 `designOnly`,限模拟 |
| Feature(DAG) | 子图(作者提供) | 输出单 `value` |
| Feature(MODEL) | 模型算子节点 加 transform | 版本锚定进指纹 |
| Feature(INSTRUCTION_RESULT) | `instructionCall` 加取值 transform | — |
| Feature(USER_*) | 不 lowering(RG 不求值,Agent 承担);契约存在 | — |
| Scenario | `decision_table` 图(从 rules 生成) | `ScenarioLowering` 把契约 rules 转 decision_table DSL |
| Instruction(READ) | 纯或资源算子图 | 输出 {result, reasoning} |
| Instruction(WRITE 设计态) | design-only operator | 无 bindingRef |
| Solution | 纯函数图:`input{特征值}` → `scenarioCall` → `branch` → `instructionCall` | `SolutionLowering` 生成 GraphDraft |

`SolutionLowering.lower(SolutionContract) → GraphDraft`:input schema 是解法输入特征值;节点是 decide(scenarioCall)、dispatch(instructionCall)、branch。生成后经现有 `DslImportService` 与 `DslCompiler` 校验。

### 6.4 内建算子 scenarioCall / instructionCall
注册:`DefaultVisualOperatorCatalog` 增两个内建 operatorRef——`bloge:scenarioCall`(effect=PURE)、`bloge:instructionCall`(effect 依指令);`lowering.mode=native`。Java 实现经 `DefaultOperatorRegistry.registerRaw` 注入 `DynamicGatewayComposerVisualDslRunner`(仿测试的 `registerRaw("controlledRead", …)`)。
```java
// 纯算子:输入 {scenarioRef, <feature values>};输出 {outletKind, ref, bind}
final class ScenarioCallOperator implements Operator<Map<String,Object>, Map<String,Object>> {
  public Map<String,Object> execute(Map<String,Object> in, OperatorContext ctx){
    return evaluator.evaluate(text(in,"scenarioRef"), featureValues(in), 0);  // §6.6 有界递归
  }
  public SideEffectType sideEffectType(){ return READ_ONLY; }
}
```
```java
// 输入 {instructionRef, <feature values>};输出 {result, reasoning}
final class InstructionCallOperator implements Operator<...> {
  public Map<String,Object> execute(Map<String,Object> in, OperatorContext ctx){
    Instruction ins = instructions.require(text(in,"instructionRef"));
    if (ins.effect()==WRITE && ctx.mode()==SIMULATE) return stub(ins);          // 模拟态桩掉,零副作用
    if (ins.effect()==WRITE) return writeChannel.execute(ins, in, ctx);          // 真实 = 受管写通道(§6.7)
    return readExecutor.execute(ins, in, ctx);                                   // READ
  }
}
```
模拟器接线:`VisualGraphSimulationService` 的 `PRIMITIVE_OPERATOR_REFS` 增 `bloge:scenarioCall`(纯,真跑);`bloge:instructionCall` READ 真跑、WRITE 桩掉。此接线支撑解法零外呼测试。

### 6.5 特征求值 + 信任 token
```java
final class FeatureEvaluationService {
  EvalResult evaluate(String featureRef, JsonNode inputs, IntegrationRequestContext id){
    Feature f = features.require(featureRef);
    if (f.determinism()==INTERACTIVE) throw new AgentTddToolException("USE_NATIVE_INTERACTION", …);
    Object value = switch (f.evaluationKind()){
      case API -> resourceExecutor.read(f.evaluationRef(), inputs);
      case DAG -> runner.run(featureGraph(f), map(inputs), null).output();
      case MODEL -> modelProvider.infer(f.evaluationRef(), inputs);              // 版本锚定
      case INSTRUCTION_RESULT -> instructionCall(f, inputs);
    };
    String token = signer.sign(featureRef, inputs, value, id.scope());
    return new EvalResult(value, token);
  }
}
```
Token 数据模型与签名:
```jsonc
FeatureValueToken = base64(payload) + "." + base64( hmacSha256(key, payload) )
payload = { "featureRef":"", "inputsFp": sha256(canonical inputs), "valueFp": sha256(canonical value),
            "scope": tenant/project/env, "issuedAt": epochSec, "ttl": 300, "nonce":"" }
```
`FeatureTokenSigner` 与 `Verifier` 用 `FeatureTokenKeyProvider`(轮换密钥,仿现有 fixture-material key:本机演示自生成,生产由 KMS 注入)。`verify(token, featureRef, inputs, value)` 校验 HMAC 一致、TTL 未过、featureRef/inputsFp/valueFp 绑定一致;否则 `FEATURE_TOKEN_INVALID`。`rg.solution.invoke` 对每个确定性输入特征执行 verify;交互特征值标 `source=USER`,免 token。

### 6.6 场景树:静态检查 + 求值算法
`ScenarioTreeValidator.validate(rootScenarioRef)`(编译期,失败关闭):
```
1) 收集 SUB_SCENARIO 出口 → 构造场景引用有向图 → 拓扑排序;有环 → SCENARIO_TREE_CYCLE
2) 计算最大深度;超 maxDepth(默认 8) → SCENARIO_TREE_TOO_DEEP
3) 每个 scenario:hit=unique 且 otherwise 存在;缺 → SCENARIO_OTHERWISE_REQUIRED
4) 每个 outlet.ref 在授权上下文可解析;否 → SCENARIO_OUTLET_UNRESOLVED(按不存在处理)
5) outlet.bind 覆盖目标必需输入特征;缺 → SCENARIO_BIND_INCOMPLETE
```
`ScenarioTreeEvaluator.evaluate(scnRef, featureValues, depth)`(纯、确定):
```
if depth > maxDepth: fail
outlet = scenario(scnRef).decisionTable.matchUnique(featureValues)   // 有 otherwise → 必命中
switch outlet.kind:
  INSTRUCTION  -> return {outletKind:"INSTRUCTION", ref, bind: resolve(outlet.bind, featureValues)}
  TERMINAL     -> return {outletKind:"TERMINAL", terminalKind}
  SUB_SCENARIO -> return evaluate(outlet.ref, rebind(outlet.bind, featureValues), depth+1)
```
数据结构:`ScenarioNode(decisionTable, List<Outlet>)`;`Outlet(kind, ref, Map bind, terminalKind)`。决策表匹配复用现有 `bloge:decisionTable` 语义(hit=unique)。

### 6.7 写治理:交接单 + 受控执行 + 对账适配器
交接单 `EngineeringHandoff`(存 `agent_tdd_assets` kind=`HANDOFF`,ref=solutionRef;字段见 §4.8);状态机 `OPEN → IMPLEMENTED → CLOSED`。`EngineeringHandoffService.submit(solutionRef, identity)` 聚合解法内所有设计态(无 bindingRef)写指令,生成交接单(附输入特征、输出契约含推理、下游、对账键、验收 golden)。影响级 `PROPOSE`。

受控写执行(独立用途,Agent 拿不到):新增 `IntegrationOperation.AGENT_TDD_WRITE_EXEC(Set.of("AGENT_TDD_WRITE_EXEC"))`,不在 Agent 四用途集(测试断言目录不含)。
```java
final class WriteExecutionRunner {   // 受控/沙箱环境;prod 失败关闭
  WriteReconciliation execute(Solution sol, IntegrationRequestContext platformId){
    requireWriteExecAuthority(platformId);                 // 仅 AGENT_TDD_WRITE_EXEC
    requireSandboxEnv(platformId.environmentId());
    for (writeInstruction i : sol.writeInstructions()){
      var expected = i.contract().expectedEffect(caseInputs);        // 契约应发生什么
      i.execute(realBinding, caseInputs);                            // 真实写
      var observed = adapters.require(i.reconciliationAdapterRef())  // 对账适配器
                       .observe(i.reconciliationKey(), caseInputs);  // 回读下游
      record(expected, observed, expected.matches(observed));
    }
    return reconciliation;   // 绑定 goldenSetId 与 evidenceFingerprint,漂移即失效
  }
}
```
对账适配器(每下游一个,注册表):
```java
interface ReconciliationAdapter {
  String downstreamSystem();
  ObservedEffect observe(String reconciliationKey, JsonNode caseInputs);  // 回读下游状态 → 结构化
}
final class ReconciliationAdapterRegistry { ReconciliationAdapter require(String adapterRef); }
```
发布门:`rg.solution.readiness` 增 `writeReconciled`(存在与当前 goldenSetId 与 evidenceFingerprint 匹配、status=RECONCILED 的 `WriteReconciliation`)。对账证据载荷零泄漏(留结构化对齐结果)。

### 6.8 存储与鉴权
- 契约 → 各实体 registry(canonical)。覆盖资产(golden / handoff / reconciliation / verdict)→ 通用表 `agent_tdd_assets`(kind 区分),复用 `AgentTddStateRepository` 的 find/save/saveIfRevision/executeOnce/lockRevision;无需新表。
- 用途:`AGENT_TDD_WRITE_EXEC`(受控写)加现有 AGENT_TDD_READ/AUTHORING/EXECUTION/GOVERNANCE。`rg.feature.evaluate` 用 `AGENT_TDD_EXECUTE`(零真实副作用,读求值)。
- DB migration:复用通用表;对账查询性能需要专表时,建 `write_reconciliation`(与 agent_tdd_assets 同风格 DDL)。

### 6.9 MCP 接线
- `McpToolCatalog`:增各工具 in/out schema(见 §7);`rg.solution.invoke` 归运行时面(独立 server)。
- `ResourceGatewayAgentTddTools.invoke`:增 `case "rg.feature.define"/"rg.feature.evaluate"/"rg.scenario.define"/…` 分派到 `SolutionAgentTools`。
- `McpToolImpact` 映射;测试断言:工具数、`AGENT_TDD_ATTEST` 与 `AGENT_TDD_WRITE_EXEC` 不入目录。

### 6.10 测试策略(按区)
| 区 | 关键测试 |
|---|---|
| 契约解析 | 拒未知字段;设计态标记;指纹稳定 |
| lowering | 各 kind 特征/场景/指令/解法生成合法 GraphDraft;设计态不可 lower |
| scenarioCall/instructionCall | 纯求值确定;WRITE 模拟态零副作用;真实态走受控通道 |
| 特征 token | 有效 token 通过;篡改/过期/错绑 → FEATURE_TOKEN_INVALID;交互类 → USE_NATIVE_INTERACTION |
| 场景树 | 环/超深/缺 otherwise/未解析/绑定缺 各触发对应码;求值确定 |
| 写治理 | 设计态写模拟零副作用;交接单聚合正确;受控执行对账 match/mismatch;发布门含 writeReconciled;Agent 无 WRITE_EXEC 用途遭拒 |
| 端到端 | §8 剧本作最终验收(真实 Codex,无 skipped/mock) |

## 7. MCP 工具面(契约化 function-call 视角)

| 工具 | 影响 | in(要点) | out(要点) | 状态 |
|---|---|---|---|---|
| `rg.dsl.reference.get` | READ | libraryRefs/topics | 语法 + 四实体契约 + 内置函数 + 示例 + 上下文指纹 | 复用(扩四实体) |
| `rg.feature.define` | DRAFT_WRITE | featureYaml | featureId/kind/determinism/speccing | 新增 |
| `rg.feature.evaluate` | EXECUTE(确定性)/拒(交互) | featureRef+inputs | `{value, evaluationToken}` 或 `USE_NATIVE_INTERACTION` | 新增 |
| `rg.scenario.define` | DRAFT_WRITE | scenarioYaml+libraryRefs | ruleMatrix/tree(acyclic,maxDepth)/诊断 | 新增 |
| `rg.instruction.define` | DRAFT_WRITE | instructionYaml | effect/reasoningRequired/writeGovernance/speccing | 新增 |
| `rg.solution.compose` | DRAFT_WRITE | 解法契约+authoringContextFingerprint | 纯函数投影/输入特征契约/speccing | 新增 |
| `rg.solution.getContract` | READ | solutionRef | 输入特征 + kind + 输出(结果+推理)契约 | 新增 |
| `rg.scenario.test` | EXECUTE | scenarioRef+cases{特征值,期望出口} | byCase{hitRuleId,outlet,pass}/realExternalCalls:0 | 新增 |
| `rg.solution.baseline` | EXECUTE | solutionRef+caseSetRef+side | goldenSetId/byLayer/businessBacklog/realExternalCalls:0 | 新增(模拟,写桩掉) |
| `rg.solution.performance` | READ | solutionRef | 命中分布/处置结果/升级率/红色 golden | 新增 |
| `rg.solution.commit` | DRAFT_WRITE→PROPOSE | solutionRef+authoringReceiptFingerprint | 提交回执 | 新增 |
| `rg.engineering.handoff` | PROPOSE | solutionRef | handoffId + 设计态写指令清单 | 新增 |
| `rg.solution.readiness` | READ | solutionRef | gates{logicGreen,writeReconciled,ownerSignoff}/remaining | 新增 |
| `rg.solution.publish` | GOVERNED_WRITE | solutionRef+signoffRef | publicationId | 新增 |
| `rg.solution.invoke` | 运行时,受管 | solutionRef+特征值+token | {result,reasoning}(写受管执行) | 新增(运行时) |
| 写执行 + 对账(不入目录) | 独立用途 | 已发布写指令 | 对账证据 | 新增,Agent 拿不到 |

共享信封与错误码复用现有,加 `SCENARIO_TREE_CYCLE`/`SCENARIO_TREE_TOO_DEEP`/`SCENARIO_OTHERWISE_REQUIRED`/`SCENARIO_OUTLET_UNRESOLVED`/`SCENARIO_BIND_INCOMPLETE`、`FEATURE_TOKEN_INVALID`、`USE_NATIVE_INTERACTION`、`WRITE_EXECUTION_NOT_RECONCILED`。

## 8. 十步客服演示剧本(细化手册,取消费纠纷)

> 主角小李(不写代码)在 Codex 表意;Agent 经 MCP 代劳;人工把关两处。步 1–9 是创作与测试解法(测试期特征值用 fixture 钉定);步 10 发布后,运行时由客服 Agent 编排采集加调纯函数解法。形态仿 `resource-gateway-agent-tdd-mcp.md`。

### 8.0 环境与 MCP 配置
```bash
# 终端 A(RG 服务)——区分 Agent token 与 reviewer token
RG_INTEGRATION_ALLOWED_PURPOSES='AGENT_TDD_READ,AGENT_TDD_AUTHORING,AGENT_TDD_EXECUTION,AGENT_TDD_GOVERNANCE,CORRECTNESS_FIXTURE_MATERIAL_WRITE' \
RG_AGENT_TDD_ATTEST_ALLOWED_HOSTS='localhost,127.0.0.1' \
./scripts/start-examples.sh resource-gateway     # AGENT_TDD_ATTEST 与 WRITE_EXEC 不发给 Agent
```
`.codex/config.toml` 四最小权限 server(read/author/execute/govern),reviewer token 输入位置是人工看板。看板:`http://localhost:8081/agent-tdd.html`。

### 8.1 十步(每步:提示词 → 期望调用 → 期望信号 → 产出/停点)
- **步1 分析定位** · 提示词:"用 rg_read 看解法 `sol:cancel-dispute` 表现:升级率、红色 golden、命中分布。" → `rg.solution.performance`。信号:escalationRate 高的 ruleId 加 redGolden。
- **步2 导入契约库** · "取消费域可用的特征/场景/指令/内置函数与 graph 语法,给全并回上下文指纹。" → `rg.dsl.reference.get{libraryRefs:["ride-cancel"]}`。信号:四实体契约加 `authoringContextFingerprint`。
- **步3 定义特征** · "定义 responsibility.party(API)、cancel.withinFree(DAG)、history.abuseSignal(API)、dispute.orderSelected(USER_COMPONENT,order-picker-v1),每个原子、类型化。" → `rg.feature.define`×4。产出示例:
```yaml
responsibility.party: { output:{type:{enum:[passenger,driver,platform,none]}}, evaluationKind:API, determinism:DETERMINISTIC, inputs:{orderId:string}, evaluationRef:"ride-responsibility-service.decide#$.party" }
```
  信号:`kind/determinism/speccing`。
- **步4 建场景决策表** · "规则:确认恶意 → 升级人工;无责且免责内 → 全额减免;乘客超时 → 维持;司机责任 → 子场景 driver-liability。hit=unique,含 otherwise。" → `rg.scenario.define`。信号:`ruleMatrix` 加 `tree{acyclic:true,maxDepth:2}`。人工:看板核对规则矩阵。
- **步5 定义处置指令** · "refund-waive-full(WRITE,退款,输出带推理,对账 refund-service/orderId)、escalate-human-ticket(WRITE,工单)、uphold(READ)。系统缺的先定设计态写契约。" → `rg.instruction.define`×3。信号:`speccing:true` 加 `reasoningRequired:true`。
- **步6 组合解法** · "组成 `sol:cancel-dispute`,输入是特征值,内部 scenarioCall → instructionCall。" → `rg.solution.compose`。信号:`纯函数投影` 加 `inputContract{party,withinFree,abuse,orderId,feeCharged}` 加 `speccing:true`。
- **步7 写 DSL 加预编译** · Agent 据意图写各实体 bloge DSL,逐个 `rg.dsl.preview`(带上下文指纹)。信号:分阶段全 PASS;错误经安全诊断三轮内收敛。产出:通过的 solution 与 scenario DSL(见 §4.3/4.5)。
- **步8 补桩加分层测试** · "给标本:party=none/withinFree=true/abuse=none → 期望全额减免;边界 withinFree 翻转;恶意 → 升级。做特征单测、场景契约测、解法集成测,要业务应然。" → `rg.fixture.provide` → `rg.scenario.test` → `rg.solution.baseline{side:RED,caseSetRef}`。信号:红绿板加 `businessBacklog` 加 `realExternalCalls:0`。**人工停点①**:看板逐条批准 GOLDEN 应然。
- **步9 提交加工程交接加人审** · "验证过了,提交解法;退款/工单写契约交工程。" → `rg.solution.commit`(带 receipt 指纹)加 `rg.engineering.handoff`。信号:提交回执加 `handoffId` 加设计态写指令清单。人工:web 审核解法结构加交接单。
- **步10 发布** · 工程补 bindingRef → 逻辑绿(`rg.solution.baseline{side:GREEN}`)→ 受控写执行加对账(独立通道,Agent 无)→ `rg.solution.readiness`(gates{logicGreen,writeReconciled,ownerSignoff} 全绿)→ 人工签署 → `rg.solution.publish`。信号:`publishable:true` → `publicationId`。**人工停点②**:核对逻辑绿加写对账加签署。

### 8.2 运行时(发布后,客服 Agent)
`rg.solution.getContract` → 采集(`dispute.orderSelected` 拉 order-picker,用户选 O1;其余调 `rg.feature.evaluate({featureRef,inputs:{orderId:O1}})` 得 `{value,evaluationToken}`)→ `rg.solution.invoke(sol:cancel-dispute,{值...,token...})` → RG 校验 token → 跑纯函数 → 写受管执行 → 处置 `{result,reasoning}`。此形态对应 point 9/10:人经 AI 工作台表意,Agent 代劳,web 审阅。

### 8.3 最终验收门槛
① 全新 Codex(无仓库读、无 skill、只连 MCP)按业务提示词跑完步 1–9;② 特征 token 篡改遭拒;③ 场景树静态检查覆盖环/超深/缺 otherwise;④ 写指令模拟零副作用、受控执行有对账;⑤ 无 skipped/mock;⑥ `mvn -f `pom.xml` clean verify` 全绿。

## 9. 工程实施计划
- **P1 实体契约加创作面**:`bloge.solutionAuthoring.v1`(features/scenarios/instructions/solutions)加 `rg.feature.define`/`rg.scenario.define`/`rg.instruction.define`/`rg.solution.compose` 加预编译。验收:四实体声明(含设计态)、预编译验证。
- **P2 纯函数编译加场景树**:解法编译成纯函数图(输入是特征值);`scenarioCall` 有界递归加编译期无环/深度/全覆盖检查;`instructionCall` 派发。验收:一个解法零外呼集成测跑通。
- **P3 特征求值加信任 token**:`rg.feature.evaluate`(确定性求值加签 token);`rg.solution.invoke` 校验 token;交互类回 `USE_NATIVE_INTERACTION`。验收:确定性特征无有效 token 遭拒;交互类不经 RG。
- **P4 测试金字塔加 golden**:特征单测、场景契约测、解法集成测加 golden 人批。验收:红绿板加业务待办。
- **P5 写效应加工程交接加对账适配器**:设计态写指令 → 交接单 → 受控执行加每下游对账适配器。验收:写指令模拟零副作用;交接单可提交;受控执行有对账证据;发布门含写对账。
- **P6 MCP 重框加看板加运营表现**:工具按四实体加剧本重框;web 退审阅看板;`rg.solution.performance`。验收:§8 剧本跑通。

## 10. 落地后遗留
1. **交互特征求值器**(USER_*):现由 Agent 承担;平台侧标准化组件/对话协议(统一 order-picker 契约一类)作后续,不阻塞主线。
2. **MODEL 特征确定性**:非确定,版本锚定采样进 golden;模型版本漂移使相关证据失效。
3. **写对账下游适配**:每下游写系统对账口径不同,适配器逐个落地。
4. **信任 token 密钥与时效治理**:签名密钥轮换、时效窗口归运维。
5. **存量 tool 图迁移**:拆成特征/场景/指令/纯函数解法,提供迁移器加兼容期双轨。
6. **运营表现数据回流**:接入命中分布/处置结果,支撑解法调优闭环。

## 11. 实施追踪

本节只记录已经落入代码并由测试证明的能力，不把后续阶段的设计当成完成事实。

| 阶段 | 当前状态 | 已有证据 | 尚未完成 |
|---|---|---|---|
| P1 四实体契约与创作面 | 已完成 | 严格有界解码 `bloge.solutionAuthoring.v1` 与四种单实体片段；Feature/Scenario/Instruction/Solution 按 tenant/project/environment 独立版本化；四个 MCP 工具具备严格输入输出 Schema、精确幂等回放；Solution 组合前解析直接引用并返回纯函数投影；聚焦单元和 MCP 协议测试全绿 | 跨层场景树检查与 GraphDraft lowering 归 P2 |
| P2 纯函数编译与场景树 | 已完成 | `SolutionLowering` 将解法固定降级为 `scenarioCall → instructionCall` 两算子 GraphDraft，并经生产 `DslImportService` 预编译；全树 DFS 校验引用、无环、深度上限与指令绑定；纯求值器支持嵌套场景、`otherwise` 和唯一命中；WRITE 指令在 SIMULATE 中结构化桩化，零外呼集成测证明 `realExternalCalls=0`；`solution-authoring` 参考包已含四实体约束和完整片段示例 | 特征采集、签名 token 与运行时调用归 P3 |
| P3 特征求值与信任 token | 待实施 | — | 求值分派、短期签名 token、交互特征拒绝与 `solution.invoke` 校验 |
| P4 测试金字塔与 GOLDEN | 待实施 | — | Feature/Scenario/Solution 分层执行、业务待办与人工批准线 |
| P5 写交接与对账 | 待实施 | — | 工程交接、独立 WRITE_EXEC、对账证据和发布门 |
| P6 看板与真实认证 | 待实施 | — | 四实体业务看板、表现视图、十步 HTTP/Codex/浏览器无跳过认证 |

## 附录，更细粒度的实施方案参考

---

# 甲 · 工程细粒度展开

## A. 特征信任 token(完整)

### A.1 数据模型
```jsonc
// 线上形态:三段 base64url,点分隔
FeatureValueToken := b64url(headerJson) "." b64url(payloadJson) "." b64url(hmac)
headerJson  = { "alg":"HS256", "kid":"<当前签名密钥 id>" }
payloadJson = {
  "featureRef": "responsibility.party",
  "inputsFp":   "sha256:<canonical(inputs) 的十六进制>",
  "valueFp":    "sha256:<canonical(value) 的十六进制>",
  "scope":      "<tenant>/<project>/<env>",
  "iat":        1757000000,          // 签发秒
  "ttl":        300,                 // 有效秒
  "nonce":      "<128bit 随机>"
}
hmac = HMAC_SHA256(key[kid], b64url(headerJson) "." b64url(payloadJson))
```

### A.2 规范化与指纹
`inputsFp`/`valueFp` 用现有 `VisualBundleFingerprint.fromCanonicalValue(mapper, node, MAX)`(键序稳定、语义等价即同指纹)。规范化对象再 `sha256`。

### A.3 签名与密钥轮换
```java
final class FeatureTokenSigner {
  FeatureTokenKeyProvider keys;            // 提供 active 签名密钥 + 历史 verify 密钥
  String sign(String featureRef, JsonNode inputs, Object value, String scope){
    var key = keys.active();               // {kid, secret}
    var payload = payload(featureRef, fp(inputs), fp(value), scope, now(), 300, nonce());
    return b64(header(key.kid())) + "." + b64(payload) + "." + b64(hmac(key.secret(), header+payload));
  }
}
interface FeatureTokenKeyProvider {
  SigningKey active();                      // 签名用当前密钥
  Optional<byte[]> verifySecret(String kid);// 校验用(含正在退役的旧 kid)
}
```
密钥来源:本机演示自生成 `0600` 文件(仿 fixture-material key);生产由 KMS 注入 `RG_FEATURE_TOKEN_KEYRING`。轮换:新增 active kid,旧 kid 进 verify-only 窗口(> maxTtl 后剔除)。

### A.4 校验步骤(接入 `rg.solution.invoke`)
```java
final class FeatureTokenVerifier {
  void verify(String token, String featureRef, JsonNode inputs, Object value, String scope){
    var [h,p,sig] = split(token) else fail("FEATURE_TOKEN_INVALID","malformed");
    var secret = keys.verifySecret(h.kid) else fail("FEATURE_TOKEN_INVALID","unknown kid");
    if (!constantTimeEq(sig, hmac(secret, h+"."+p))) fail("FEATURE_TOKEN_INVALID","signature");
    if (now() > p.iat + p.ttl + SKEW) fail("FEATURE_TOKEN_INVALID","expired");   // SKEW=30s
    if (!p.featureRef.equals(featureRef)) fail("FEATURE_TOKEN_INVALID","featureRef");
    if (!p.inputsFp.equals(fp(inputs)))   fail("FEATURE_TOKEN_INVALID","inputs");
    if (!p.valueFp.equals(fp(value)))     fail("FEATURE_TOKEN_INVALID","value");
    if (!p.scope.equals(scope))           fail("FEATURE_TOKEN_INVALID","scope");
  }
}
```
`rg.solution.invoke` 对每个 `DETERMINISTIC`/`NON_DETERMINISTIC` 输入特征执行 `verify`;`INTERACTIVE` 特征值免 token,标 `source=USER`;缺 token 的确定性特征 → `FEATURE_TOKEN_INVALID`。

### A.5 重放与时钟
短 TTL(300s)加 `nonce`;`SKEW=30s` 容忍时钟偏移。invoke 侧对同 `(nonce)` 的重复采信设幂等键(复用现有 `executeOnce`),阻断同 token 重放放大写副作用。

### A.6 错误码
`FEATURE_TOKEN_INVALID`(签名/过期/绑定不符/未知 kid/畸形,详情不回显具体原因,留稳定码)。

### A.7 测试
| 用例 | 期望 |
|---|---|
| 有效 token | invoke 采信 |
| 改 value / inputs / featureRef / scope | FEATURE_TOKEN_INVALID |
| 过期(iat+ttl+skew 之外) | FEATURE_TOKEN_INVALID |
| 未知/已剔除 kid | FEATURE_TOKEN_INVALID |
| 轮换窗口内旧 kid | 采信 |
| 交互特征带 token | 忽略 token,采 source=USER |
| 确定性特征缺 token | FEATURE_TOKEN_INVALID |

## B. 内建算子 scenarioCall / instructionCall(完整)

### B.1 目录注册条目(`DefaultVisualOperatorCatalog`)
```jsonc
{ "operatorRef":"bloge:scenarioCall", "archetype":"native", "effect":"PURE",
  "lowering": { "mode":"native" },
  "input":  { "scenarioRef":"string", "*":"any(特征值)" },
  "output": { "outlet": { "outletKind":"string", "ref":"string", "bind":"object" } } }
{ "operatorRef":"bloge:instructionCall", "archetype":"native", "effect":"CONDITIONAL",
  "lowering": { "mode":"native" },
  "input":  { "instructionRef":"string", "*":"any(特征值)" },
  "output": { "result":"object", "reasoning":"string|object" } }
```

### B.2 执行接线
```java
// 注入 DynamicGatewayComposerVisualDslRunner 的 registry(仿测试 registerRaw)
operatorRegistry.registerRaw("bloge:scenarioCall",  new ScenarioCallOperator(scenarios, evaluator));
operatorRegistry.registerRaw("bloge:instructionCall", new InstructionCallOperator(instructions, readExec, writeChannel));
```
```java
final class ScenarioCallOperator implements Operator<Map<String,Object>,Map<String,Object>> {
  public Map<String,Object> execute(Map<String,Object> in, OperatorContext ctx){
    Object outlet = evaluator.evaluate(str(in,"scenarioRef"), featureValues(in), 0);  // §甲无(见主文档 6.6)
    return Map.of("outlet", outlet);
  }
  public SideEffectType sideEffectType(){ return READ_ONLY; }
}
final class InstructionCallOperator implements Operator<Map<String,Object>,Map<String,Object>> {
  public Map<String,Object> execute(Map<String,Object> in, OperatorContext ctx){
    Instruction ins = instructions.require(str(in,"instructionRef"));
    if (ins.effect()==WRITE && ctx.mode()==SIMULATE) return stubFromOutputSchema(ins);  // 桩,零副作用
    if (ins.effect()==WRITE) return writeChannel.execute(ins, in, ctx);                  // 受管写(§C)
    return readExec.execute(ins, in, ctx);                                               // READ
  }
  public SideEffectType sideEffectType(){ return context.mode()==SIMULATE ? READ_ONLY : downstream; }
}
```

### B.3 模拟器接线
`VisualGraphSimulationService.PRIMITIVE_OPERATOR_REFS` 增 `bloge:scenarioCall`(纯,真跑);`bloge:instructionCall` READ 真跑、WRITE 桩掉。解法 `rg.solution.baseline` 的 `realExternalCalls` 保持 0。

### B.4 WRITE 桩合成
`stubFromOutputSchema(ins)`:按指令 `output.result` schema 合成形状合规值,`reasoning` 填占位("simulated"),不触下游。

### B.5 测试
| 用例 | 期望 |
|---|---|
| scenarioCall 纯求值 | 同输入同出口,确定 |
| instructionCall READ | 返回 {result, reasoning} |
| instructionCall WRITE 模拟 | 桩,realExternalCalls=0 |
| instructionCall WRITE 真实 | 走 writeChannel;非 WRITE_EXEC 用途遭拒 |

## C. 写治理(完整)

### C.1 EngineeringHandoff 数据模型(存 `agent_tdd_assets` kind=HANDOFF)
```jsonc
{ "handoffId":"", "solutionRef":"sol:cancel-dispute", "status":"OPEN|IMPLEMENTED|CLOSED",
  "submittedBy":"", "createdAt":"",
  "items":[ { "instructionId":"ins:refund-waive-full",
              "inputs":{"orderId":"string","feeCharged":"number"},
              "output":{"result":{"decision":"enum[WAIVED]","waiveAmount":"number"},"reasoning":"required"},
              "effect":"WRITE", "downstreamSystem":"refund-service",
              "reconciliationKey":"orderId", "reconciliationAdapterRef":"recon:refund-v1",
              "businessIntent":"无责或乘客免责时长内 → 全额减免退款",
              "acceptanceGolden":"caseSet:cancel-dispute", "state":"DESIGN_ONLY|IMPLEMENTED" } ] }
```
`EngineeringHandoffService.submit(solutionRef, identity)`:聚合解法内 `effect=WRITE && bindingRef 缺席` 的指令 → 生成 items → 存 OPEN。影响级 PROPOSE。

### C.2 WriteExecutionRunner(复用 ATTEST 的崩溃安全预留)
```java
final class WriteExecutionRunner {
  WriteReconciliation execute(String solutionRef, IntegrationRequestContext platformId){
    requirePurpose(platformId, AGENT_TDD_WRITE_EXEC);            // Agent 拿不到
    requireSandbox(platformId.environmentId());                 // prod 失败关闭
    Solution sol = solutions.require(solutionRef);
    String fp = requestFingerprint(solutionRef, currentEvidenceFingerprint(sol), platformId.environmentId());
    var r = states.reserveExternalExecution(scope, "WRITE_EXEC", fp, fp);        // 复用现有预留
    if (r.status()==COMPLETED)   return convert(r.response());                   // 幂等回放
    if (r.status()==IN_PROGRESS) return recoveryRequired(solutionRef);           // 进程丢失标记
    List<CaseRecon> cases = new ArrayList<>();
    for (JsonNode caseRow : activeGolden(sol)) {
      for (WriteInstruction i : sol.writeInstructions()) {
        var expected = i.expectedEffect(caseRow);                                // 契约应发生
        i.execute(i.binding(), caseInputs(caseRow));                             // 真实写(沙箱)
        var observed = adapters.require(i.reconciliationAdapterRef())
                         .observe(i.reconciliationKey(caseRow), caseInputs(caseRow));
        cases.add(new CaseRecon(caseId(caseRow), i.instructionRef(), expected, observed, expected.matches(observed)));
      }
    }
    var recon = reconciliation(solutionRef, sol, platformId.environmentId(), cases);
    return convert(states.completeExternalExecution(scope, "WRITE_EXEC", fp, fp, tree(recon)));
  }
}
```

### C.3 ReconciliationAdapter 接口 + 示例
```java
interface ReconciliationAdapter {
  String adapterRef();                         // "recon:refund-v1"
  String downstreamSystem();                   // "refund-service"
  ObservedEffect observe(String reconKeyValue, JsonNode caseInputs);   // 回读下游 → 结构化
}
record ObservedEffect(String reconciliationKey, Map<String,Object> effect) {}
final class ReconciliationAdapterRegistry { ReconciliationAdapter require(String adapterRef); }

// 示例:退款下游
final class RefundReconciliationAdapter implements ReconciliationAdapter {
  public ObservedEffect observe(String orderId, JsonNode in){
    var s = refundClient.getRefundState(orderId);       // 回读退款状态
    return new ObservedEffect(orderId, Map.of("decision", s.status(), "amount", s.amount()));
  }
}
```
`expected.matches(observed)`:按对账键对齐,比对结构化 effect 字段(decision、amount)。

### C.4 WriteReconciliation 资产(载荷零泄漏)
```jsonc
{ "reconciliationId":"", "solutionRef":"", "goldenSetId":"", "evidenceFingerprint":"", "environmentId":"sandbox",
  "status":"RECONCILED|MISMATCH", "attestedBy":"system:write-exec-runner",
  "cases":[ { "caseId":"g1", "instructionRef":"ins:refund-waive-full",
              "expected":{"decision":"WAIVED","amount":8}, "observed":{"decision":"WAIVED","amount":8}, "match":true } ] }
```

### C.5 发布门接入
`rg.solution.readiness`:`writeReconciled = 存在 status=RECONCILED 且 goldenSetId+evidenceFingerprint 与当前一致的 WriteReconciliation`;缺 → `remainingLimitations += WRITE_EXECUTION_NOT_RECONCILED`。`rg.solution.publish` 门:`logicGreen && writeReconciled && ownerSignoff`。

### C.6 测试
| 用例 | 期望 |
|---|---|
| Agent(无 WRITE_EXEC 用途)调受控执行 | FORBIDDEN_PURPOSE |
| prod 环境 | 失败关闭 |
| expected==observed | RECONCILED |
| expected≠observed | MISMATCH,不销账 |
| 指纹漂移后旧对账 | isCurrent=false,readiness 回落 |
| 交接单聚合 | 只含设计态写指令 |

## D. 实体 → GraphDraft lowering(完整算法)

### D.1 FeatureLowering
```
lower(feature):
  switch feature.evaluationKind:
    API:  node fetch: httpResource{ resourceId=evaluationRef.resourceId, params=inputs }
          transform out{ value = fetch.output.payload.<evaluationRef.path> }
    DAG:  引用作者提供子图,约束其输出含单 value
    MODEL: node infer: modelOperator{ model=evaluationRef(版本), inputs }; transform out{ value = infer.output.value }
    INSTRUCTION_RESULT: node call: instructionCall{ instructionRef, inputs }; transform out{ value = call.output.result.<path> }
    USER_*: 不 lower;标 designOnly-interactive
  若 evaluationRef 绑定缺席 → 标 designOnly(限模拟)
```

### D.2 ScenarioLowering(rules → decision_table codegen)
```
lower(scenario):
  graph scn_<id> {
    transform facts { <每个 input feature>: ctx.<name> }
    decision_table outlet( <每个 condition feature>: facts.<name> ) hit=unique
      -> { outletKind:String, ref:String, ruleId:String, bind:Object } {
      for rule in scenario.rules:
        rule ( <condition feature>: <predicate> ) -> { outletKind, ref, ruleId, bind }
      otherwise -> { outletKind, ref, ... }   // 必存在
    }
    out = { outlet: outlet.output }
  }
```
`predicate` 从契约 `when` 生成(`== / != / <= / 范围`),与现有 decision_table 语法一致。

### D.3 InstructionLowering
```
lower(instruction):
  READ:  纯/资源算子图,输出 {result, reasoning}
  WRITE 且 bindingRef 存在:绑定真实写实现
  WRITE 且 bindingRef 缺席:design-only operator(限模拟,进交接单)
```

### D.4 SolutionLowering(固定纯函数图)
```
lower(solution):
  input schema = { <每个 solution.inputs 特征>: <特征 output.type> }
  graph sol_<id> {
    input { <特征值参数> }
    node decide : scenarioCall { input { scenarioRef=root, <把输入特征值透传给 scenarioCall> } }
    branch on decide.output.outlet.outletKind { "INSTRUCTION" -> dispatch  otherwise -> escalate }
    node dispatch : instructionCall { input { instructionRef=decide.output.outlet.ref, <bind 后的输入特征> } }
  }
  经 DslImportService + DslCompiler 校验;确定性输入特征在 invoke 侧要求 token(见 §A)
```

---

# 乙 · 可跑 Codex 手册(取消费纠纷)

## 乙.0 前置
Java 25、Maven 3.9、BLOGE 依赖已装。两个 token:Agent token(WORKLOAD)、reviewer token(HUMAN)。reviewer token 输入位置是浏览器看板密码框。

## 乙.1 启动 RG(终端 A)
```bash
printf 'Agent token: ';  IFS= read -rs RG_AGENT;  printf '\nReviewer token: '; IFS= read -rs RG_REVIEW; printf '\n'
RG_INTEGRATION_DEMO_IDENTITY_ENABLED=true \
RG_INTEGRATION_DEMO_TOKEN="$RG_AGENT" \
RG_INTEGRATION_DEMO_REVIEW_TOKEN="$RG_REVIEW" \
RG_INTEGRATION_ALLOWED_PURPOSES='AGENT_TDD_READ,AGENT_TDD_AUTHORING,AGENT_TDD_EXECUTION,AGENT_TDD_GOVERNANCE,CORRECTNESS_FIXTURE_MATERIAL_WRITE' \
RG_AGENT_TDD_ATTEST_ALLOWED_HOSTS='localhost,127.0.0.1' \
RESOURCE_GATEWAY_PORT=8081 ./scripts/start-examples.sh resource-gateway
unset RG_AGENT RG_REVIEW
# MCP: http://localhost:8081/mcp  ·  看板: http://localhost:8081/agent-tdd.html
# AGENT_TDD_ATTEST 与 AGENT_TDD_WRITE_EXEC 不进 allowed-purposes,Agent 拿不到
```

## 乙.2 Codex MCP 配置(`.codex/config.toml`)
```toml
[mcp_servers.rg_read]
url="http://localhost:8081/mcp"; bearer_token_env_var="RG_MCP_TOKEN"; http_headers={ "X-Purpose"="AGENT_TDD_READ" }
enabled_tools=["rg.solution.performance","rg.dsl.reference.get","rg.solution.getContract","rg.scenario.listCases","rg.solution.readiness"]
[mcp_servers.rg_author]
url="http://localhost:8081/mcp"; bearer_token_env_var="RG_MCP_TOKEN"; http_headers={ "X-Purpose"="AGENT_TDD_AUTHORING" }
enabled_tools=["rg.feature.define","rg.scenario.define","rg.instruction.define","rg.solution.compose","rg.dsl.preview","rg.solution.commit","rg.engineering.handoff"]
[mcp_servers.rg_execute]
url="http://localhost:8081/mcp"; bearer_token_env_var="RG_MCP_TOKEN"; http_headers={ "X-Purpose"="AGENT_TDD_EXECUTION" }
enabled_tools=["rg.fixture.provide","rg.scenario.test","rg.solution.baseline"]
[mcp_servers.rg_govern]
url="http://localhost:8081/mcp"; bearer_token_env_var="RG_MCP_TOKEN"; http_headers={ "X-Purpose"="AGENT_TDD_GOVERNANCE" }
enabled_tools=["rg.solution.publish"]
```
终端 B 只注入 Agent token 后启动 Codex:`export RG_MCP_TOKEN=<agent>; codex`。

## 乙.3 十步(完整提示词 + MCP 调用 args + 期望响应 JSON)

**步1 分析定位**
提示词:
```
用 rg_read,读解法 sol:cancel-dispute 的表现:命中分布(按 ruleId)、升级人工率、红色 golden。只汇报稳定字段,不展示业务 payload。
```
调用 `rg.solution.performance` args:`{"solutionRef":"sol:cancel-dispute"}`。期望响应:
```json
{ "ok": true, "data": {
  "solutionRef":"sol:cancel-dispute",
  "hitDistribution":[ {"ruleId":"R3","share":0.41}, {"ruleId":"R5","share":0.22} ],
  "escalationRate":0.22, "redGolden":["g-driver-liability-1"] }, "diagnostics":[] }
```

**步2 导入契约库**
提示词:
```
用 rg_read,取取消费域 ride-cancel 的 DSL 参考:支持的 root、特征/场景/指令契约、内置函数、认证示例,并回上下文指纹。graph-only。
```
`rg.dsl.reference.get` args:`{"libraryRefs":["ride-cancel"],"includeExamples":true}`。期望响应(节选):
```json
{ "ok": true, "data": {
  "supportedRootKinds":["graph"], "authoringContextFingerprint":"sha256:ctx-1",
  "features":[ {"featureRef":"responsibility.party","output":{"type":{"enum":["passenger","driver","platform","none"]}},"evaluationKind":"API","determinism":"DETERMINISTIC"} ],
  "operators":[ {"operatorRef":"bloge:scenarioCall","effect":"PURE"}, {"operatorRef":"bloge:instructionCall"} ],
  "examples":[ {"exampleId":"solution-decision-minimal","assertions":["COMPILES"]} ] }, "diagnostics":[] }
```

**步3 定义特征**
提示词:
```
用 rg_author,定义四个原子特征,每个带 evaluationKind/determinism:
- responsibility.party: API, 输入 orderId, 输出 enum[passenger,driver,platform,none]
- cancel.withinFree: DAG, 输入 orderId, 输出 boolean
- history.abuseSignal: API, 输入 orderId, 输出 enum[none,suspected,confirmed]
- dispute.orderSelected: USER_COMPONENT(order-picker-v1), 输出 string
每个用独立 idempotencyKey。
```
`rg.feature.define` args(responsibility.party):
```json
{ "featureYaml":"responsibility.party: { output:{type:{enum:[passenger,driver,platform,none]}}, evaluationKind:API, determinism:DETERMINISTIC, inputs:{orderId:string}, evaluationRef:\"ride-responsibility-service.decide#$.party\" }", "idempotencyKey":"feat-party-1" }
```
期望响应:`{ "ok":true, "data":{"featureId":"responsibility.party","evaluationKind":"API","determinism":"DETERMINISTIC","speccing":false}, "diagnostics":[] }`。`dispute.orderSelected` 返回 `"determinism":"INTERACTIVE"`。

**步4 建场景决策表**
提示词:
```
用 rg_author,建场景 scn:cancel-dispute,输入特征 [responsibility.party, cancel.withinFree, history.abuseSignal],hit=unique,含 otherwise:
- abuse==confirmed → 指令 escalate-human-ticket
- party==none 且 withinFree==true 且 abuse!=confirmed → 指令 refund-waive-full
- party==passenger 且 withinFree==false 且 abuse!=confirmed → 指令 uphold
- party==driver 且 abuse!=confirmed → 子场景 driver-liability
- otherwise → 指令 escalate-human-ticket
```
`rg.scenario.define` 期望响应:
```json
{ "ok":true, "data":{ "scenarioId":"scn:cancel-dispute",
  "ruleMatrix":{"conditions":["party","withinFree","abuse"],"rules":[...],"otherwise":{...}},
  "tree":{"acyclic":true,"maxDepth":2,"referencedScenarios":["scn:driver-liability"],"referencedInstructions":["ins:refund-waive-full","ins:uphold","ins:escalate-human-ticket"]},
  "diagnostics":[] }, "diagnostics":[] }
```
人工:看板核对规则矩阵。

**步5 定义处置指令**
提示词:
```
用 rg_author,定义指令:
- refund-waive-full: WRITE, 输入 orderId+feeCharged, 输出 result{decision:WAIVED,waiveAmount} + reasoning(必填), 对账 refund-service/orderId/recon:refund-v1
- escalate-human-ticket: WRITE, 输出 result{ticketId} + reasoning, 对账 ticket-service/orderId/recon:ticket-v1
- uphold: READ, 输出 result{decision:UPHELD} + reasoning
系统缺的 WRITE 先定设计态。
```
`rg.instruction.define`(refund) 期望响应:`{ "ok":true, "data":{"instructionId":"ins:refund-waive-full","effect":"WRITE","reasoningRequired":true,"speccing":true}, "diagnostics":[] }`。

**步6 组合解法**
提示词:
```
用 rg_author,组合解法 sol:cancel-dispute:输入=特征值 [party,withinFree,abuse,orderId,feeCharged],scenarioTree.root=scn:cancel-dispute,instructions=[refund-waive-full,uphold,escalate-human-ticket],golden=caseSet:cancel-dispute。回上下文指纹。
```
`rg.solution.compose` 期望响应:
```json
{ "ok":true, "data":{ "solutionRef":"sol:cancel-dispute","revision":1,
  "inputContract":{"party":"enum","withinFree":"boolean","abuse":"enum","orderId":"string","feeCharged":"number"},
  "scenarioTreeValid":true, "speccing":true,
  "authoringContextFingerprint":"sha256:ctx-1" }, "diagnostics":[] }
```

**步7 写 DSL 加预编译**
提示词:
```
用 rg_author,为 scn:cancel-dispute 与 sol:cancel-dispute 生成 bloge DSL(graph),逐个 rg.dsl.preview,带 authoringContextFingerprint=sha256:ctx-1。错误按 diagnostics 的 code/span 改,最多三轮。不展示源码给业务人。
```
`rg.dsl.preview` 期望响应(通过):
```json
{ "ok":true, "data":{ "authoringContext":{"fingerprint":"sha256:ctx-1","status":"CURRENT"},
  "stages":[{"phase":"CONTEXT","status":"PASS"},{"phase":"PARSE","status":"PASS"},{"phase":"RESOLVE","status":"PASS"},{"phase":"TYPE_CHECK","status":"PASS"},{"phase":"SEMANTIC_COMPILE","status":"PASS"},{"phase":"LINT","status":"PASS"},{"phase":"PROJECT","status":"PASS"},{"phase":"ROUND_TRIP","status":"PASS"}],
  "technicalAcceptance":"ACCEPTED","nextAction":"CONTINUE_TO_REWRITE_GATE","authoringReceiptFingerprint":"sha256:rcpt-1" }, "diagnostics":[] }
```

**步8 补桩加分层测试**
提示词:
```
用 rg_execute:
1. rg.fixture.provide 给标本:g1{party:none,withinFree:true,abuse:none,orderId:O1,feeCharged:8,期望 decision=WAIVED};边界 g2{withinFree:false→UPHELD};g3{abuse:confirmed→ESCALATE}
2. rg.scenario.test 对 scn:cancel-dispute 断言命中出口
3. rg.solution.baseline side=RED caseSetRef=caseSet:cancel-dispute
要求 realExternalCalls=0。红项停下汇报。
```
`rg.solution.baseline` 期望响应:
```json
{ "ok":true, "data":{ "goldenSetId":"sha256:gs-1","side":"RED",
  "byLayer":{"integration":{"pass":2,"fail":1}},
  "cases":[{"caseId":"g1","verdict":"RED_PASS"},{"caseId":"g3","verdict":"RED_FAIL"}],
  "businessBacklog":[{"caseId":"g3","reason":"RED_FAIL","owner":"cx-ops"}],
  "realExternalCalls":0 }, "diagnostics":[] }
```
人工停点①:看板 `agent-tdd.html` 用 reviewer token 逐条批准 GOLDEN 应然。

**步9 提交加工程交接**
提示词:
```
用 rg_author:rg.solution.commit(带 authoringReceiptFingerprint=sha256:rcpt-1);rg.engineering.handoff。汇报 handoffId 与设计态写指令清单。
```
`rg.engineering.handoff` 期望响应:
```json
{ "ok":true, "data":{ "handoffId":"ho-1","status":"OPEN",
  "items":[ {"instructionId":"ins:refund-waive-full","effect":"WRITE","downstreamSystem":"refund-service","reconciliationKey":"orderId","state":"DESIGN_ONLY"},
            {"instructionId":"ins:escalate-human-ticket","effect":"WRITE","downstreamSystem":"ticket-service","state":"DESIGN_ONLY"} ] }, "diagnostics":[] }
```
人工:web 审核解法结构与交接单。

**步10 发布(工程实现后)**
工程补 bindingRef → `rg.solution.baseline side=GREEN` → 平台受控写执行加对账(独立通道)→ `rg.solution.readiness`。`readiness` 期望响应:
```json
{ "ok":true, "data":{ "solutionRef":"sol:cancel-dispute","state":"IMPLEMENTED","publishable":true,
  "gates":{"logicGreen":true,"writeReconciled":true,"ownerSignoff":true},"remainingLimitations":[] }, "diagnostics":[] }
```
人工停点②:看板核对逻辑绿加写对账加签署 → `rg.solution.publish{solutionRef,signoffRef}` → `{"ok":true,"data":{"publicationId":"pub-1"}}`。

## 乙.4 运行时(发布后,客服 Agent)
```
rg.solution.getContract(sol:cancel-dispute) → 需要 {party,withinFree,abuse,orderId,feeCharged}
dispute.orderSelected(USER_COMPONENT) → 客服 Agent 拉 order-picker,用户选 O1
rg.feature.evaluate({featureRef:"responsibility.party",inputs:{orderId:"O1"}}) → {"value":"none","evaluationToken":"eyJ...","source":"RG"}
rg.solution.invoke(sol:cancel-dispute,{party:"none",...,orderId:"O1",feeCharged:8, tokens:{...}}) → RG 校验 token → {"result":{"decision":"WAIVED","waiveAmount":8},"reasoning":"无责且免责时长内,规则 R1"}
```

## 乙.5 最终验收门槛
① 全新 Codex(无仓库读、无 skill、只连 MCP)按步 1–9 提示词跑通;② 篡改特征值 token → FEATURE_TOKEN_INVALID;③ 场景树静态检查触发环/超深/缺 otherwise 码;④ WRITE 指令模拟 realExternalCalls=0,受控执行有对账证据;⑤ 无 skipped/mock;⑥ `mvn -f `pom.xml` clean verify` 全绿。
