# 详细技术方案：用业务主线故事审视系统设计，做针对性调整与适配

> 本文面向从未参与讨论、也不熟悉本代码库的实施团队。每个概念首次出现处解释，不做需要背景才懂的引用。方法是：先立一条**业务主线故事**当审视镜，再**逐幕**对照"业务这步需要什么 / 系统现在怎么支持 / 差距 / 针对性调整"，最后汇成一份工程实施计划。

## 1. 背景与目标

### 1.1 系统是什么
本系统是一个**给 AI Agent 造"工具"的平台**。"AI Agent"指由大模型驱动、需调用外部能力完成任务的程序（如客服助手）；"工具"指 Agent 可调用的一个业务能力（如"判定一笔订单的取消费该不该减免"）。工具分三层：**接口**（对一个外部 HTTP 服务的声明）、**特征**（把若干接口聚合成业务上下文的小编排）、**工具**（在特征上加决策逻辑、产出业务处置）。

### 1.2 已经建成的能力（一句话逐条）
- **契约优先/设计态**：能只声明一个能力的输入输出契约而不写实现；有"实现指向"字段就是已实现，没有就是"设计态"。
- **零外呼模拟**：能在不触达任何真实系统下把整张图跑起来（依赖用预设"桩"替代，纯逻辑真跑）。
- **验证用例（golden）**：业务方以表格写"这种事实下业务应得什么结论"，须**人工在看板逐条批准**才生效，起草的 Agent 不能替人批准。
- **红 / 逻辑绿**：红=对"只有契约"跑零外呼模拟；逻辑绿=对"绑定齐全"仍跑零外呼模拟；两者都不触真实系统。
- **看板**：只读，按工具展示就绪状态、红/绿、待办、用例覆盖，以及待人工批准的队列。
- **发布**：达标 + 人工签署后冻结成不可变工具。
- **操作面**：Agent 通过一套标准工具协议调用上述能力；每次调用被鉴权到具名"用途"，Agent 的凭据被限制在固定几个用途内。

### 1.3 一个观察，以及本方案的目标
系统**按技术阶段和工具**组织（查询/创作/执行/治理各一组工具）。但业务人脑子里不是工具清单，而是一条**旅程**："我有条业务规则，怎么从想法走到上线的可信能力"。**本方案的目标：把这条业务旅程立成审视镜，逐幕检查现有系统设计对每一步的支持是否到位，并做针对性调整与适配，让业务人能自助走完旅程**——这才真正落实"运营环节的人机协同"。

## 2. 业务主线故事（审视镜本身）
**小李**，客服政策负责人，不写代码。她要把"网约车取消费该不该减免"做成客服 AI 能调用的可信工具。她全程用自然语言跟本地编码 Agent 对话，只在关键处上看板亲自把关。五幕：
1. **提案库契约**——说清这件事涉及哪些业务事实、可能得出哪些结论（先立"世界观"，还不接任何真实系统）。
2. **接资源 + 钉样例**——指定每个事实从哪个真实数据源来，并给几组"典型长相"的样例数据。
3. **口述逻辑 → Agent 落成编排**——描述业务规则，Agent 自动把自然语言翻成可执行编排（DAG + 决策表）。
4. **定义 golden 验证集**——给"这种情况就该这么判"的标准用例并逐条批准；系统立刻零外呼跑一遍，红的就是业务待办。
5. **发布**——达标且"真能对接真实系统"后，亲自签署上线。

## 3. 要解决的问题：逐幕审视（业务需要什么 vs 现状差距）

| 幕 | 业务这步需要 | 系统现状怎么支持 | 差距/错配 |
|---|---|---|---|
| 1 提案库契约 | 用业务语言声明事实与结论；看得懂"平台给了我哪些现成积木" | 有"提交契约"能力（可只声明、不实现）；有"列能力/读契约"的查询 | 契约以技术 YAML 形态存在；**没有业务可读的"平台积木 + 我声明的世界观"视图**；"提案"在系统里其实是直接落草稿、无独立评审位 |
| 2 接资源+钉样例 | "这事实从这个源来"；"这是我的几组样例" | 可把契约能力指向已注册的真实资源；可把某次运行捕获的输出**晋级**为可复用样例 | **"给样例"不是一等动作**（样例得先作为用例桩跑一遍再晋级，绕）；**资源必须预先注册**，业务流程里若源还没建，走不通 |
| 3 口述逻辑→编排 | 用自然语言描述规则；**在看板核对 Agent 有没有理解对**（她不读代码） | Agent 把自然语言写成编排文本并提交；有"编译预览/门禁"给 Agent 反馈；看板展示图结构 | **看板给的是"算子引用/节点/连线"的技术结构视图，不是业务可读的"规则矩阵/决策表"视图** → 业务人无法自助核对逻辑对不对 |
| 4 golden 验证集 | 给标准用例、批准应然、看"还差什么" | 有"写用例/自动枚举边界/人工逐条批准"；看板有红/绿板、业务待办、用例覆盖计数 | 基本到位；**覆盖只有计数、看不出"哪些事实组合还没被覆盖"** |
| 5 发布 | 既证明"逻辑对"，又证明"真的能对接真实系统"，再签署 | 有逻辑绿 + 就绪门（绑定齐全/逻辑绿/签署）+ 人工签署 | **逻辑绿从不触真实系统 → 没有"真的能对接"这一证明**；就绪清单里"真实集成未验证"只是个占位、无法销账 |
| 跨幕 | 一条"从提案到发布"的旅程入口 | 操作面按技术阶段/工具组织；看板按工具卡片 | **没有面向业务人的"旅程"主入口**，上手门槛是技术性的 |

## 4. 价值空间
把上述差距补齐后：业务人能**自助**走完"提案→接源→编排→验证→发布"，在每一步都能**看懂、能把关**；系统从"工程师的工具箱"变成"业务人可操作的运营主线"，真正兑现"人机协同 + 软件工程质量方法用于业务运营"的价值，且**不牺牲既有的零外呼安全与人工治理**。

## 5. 解法设计：针对性调整与适配（逐幕）

> 每项适配都对齐到某一幕的差距，自包含说明"是什么、怎么落到现有系统"。

### A0（跨幕）· 把主面重组为"业务旅程"
把业务人的第一入口从"工具/阶段"改为**一条五幕旅程导航**：提案契约 → 接源钉样例 → 口述编排 → 定 golden → 发布，每幕直达该幕所需的少数动作与看板视图；技术性的工具卡片退居"专家视图"。这是让业务人"进得来、走得完"的骨架，其余适配挂在对应幕上。

### A1（第1幕）· 业务可读的"积木 + 世界观"视图，并明确契约的评审位
- **适配**：在看板上给一个业务可读视图——左侧"**平台给你的现成积木**"（如"调外部服务""决策表""数据变换"，用业务语言描述其用途），右侧"**你已声明的世界观**"（业务类型、数据来源，标注"仅契约/已接入"）。让业务人不看 YAML 也能理解"这件事由哪些事实和积木构成"。
- **契约评审位（需确认的决策）**：契约当前是直接落草稿、无独立人工评审。经业务镜审视，契约是"世界观草稿"，其正确性会在第4幕（golden）和第5幕（发布）被下游验证，因此**不必**为契约单设人工门；"提案"在业务语义上=先起草、后被 golden 检验。建议保持无独立门，仅在看板把它明确标为"草稿世界观、待用例检验"。

### A2（第2幕）· 让"给样例"成为一等动作，并桥接"资源尚未注册"
- **适配一**：新增一条一等路径"**这是我对某数据源的几组样例 → 直接成为可复用验证数据**"，业务人（经 Agent）直接提交样例即可，无需先构造用例跑一遍再晋级。系统仍按现有治理规则派生样例的作用域/schema/来源（防伪造）。
- **适配二**：当业务人要接的真实数据源**尚未在平台注册**时，给一条**桥接**——引导补一个"外部接口定义"（复用平台已有的外部接口登记能力），再回到主线绑定。避免业务流程在此断裂。

### A3（第3幕）· 业务可读的编排投影 + 收紧自然语言→编排的反馈闭环
- **核心适配**：把看板对已编排工具的展示，从"算子引用/节点/连线"的技术结构视图，换成**业务可读投影**——特别是把决策逻辑呈现为一张**规则矩阵/决策表**（条件列+结论列+规则行+兜底），把流程呈现为几句白话（"先取四项事实→汇成上下文→按这张表判"）。让业务人**自助核对 Agent 有没有把她的规则理解对**，这是第3幕能否由业务人把关的关键。
- **配套适配**：给 Agent 更充分的"落对编排"上下文与反馈——把可用积木的契约、示例、语法要点作为稳定上下文供 Agent 取用，并让"编译预览"的诊断以稳定错误码 + 源码定位回流，形成"自然语言→编排→预览→修正"的收敛闭环。

### A4（第4幕）· 事实组合覆盖可见性
- **适配**：在红/绿板旁增加"**覆盖视图**"——基于决策表的条件列，展示"哪些事实组合已有 golden 覆盖、哪些是盲区"，而不仅是用例计数。让业务人看得出"我的规则是否被验证周全"。

### A5（第5幕）· 实景验证（ATTEST）：把红→绿闭合到真实系统
这是最大的一处适配，对应第5幕"真的能对接才敢上线"的缺口。自包含说明：
- **问题**：逻辑绿始终用桩替代依赖、从不触真实系统，因此真实上游可达性、真实响应形状、真实鉴权/错误等一整类问题在发布前不可见。
- **适配**：在逻辑绿之外新增一道"**实景验证**"——对**同一批已批准 golden 用例**，把依赖换成**真实调用**、在**受控沙箱环境**跑一次，核对真实结论仍满足业务应然。
- **安全（关键约束）**：真实外呼是系统里唯一会触达外部的通道，绝不能落到 Agent 手里。因此实景验证用一个**Agent 结构上拿不到的独立"用途"**触发，且**由平台自动**在逻辑绿通过后于沙箱发起（业务人只需在看板看结果）；生产环境**失败关闭**；出口加**目标主机白名单**（同时清掉此前"真实出站无 host 治理"的 SSRF 隐患）；默认**只读**，写副作用须走沙箱替身 + 对账。
- **证据与门禁**：实景验证产出**只含结构化观测**（各依赖是否被真实调用、次数、每条用例应然是否成立、环境标识），**不落真实业务载荷**；证据**绑定"工具+契约+用例集"的稳定身份与当前实现指纹**，任何漂移即失效。发布门改为**逻辑绿 + 实景验证 + 负责人签署三者齐备且指纹一致**；就绪清单里原本无法销账的"真实集成未验证"占位，现在可被这份证据销账。

## 6. 决策因素与依据

**决策一 · 用业务旅程重组主面，而非继续按技术阶段组织（A0）。** 候选：维持技术阶段面 / 叠加业务旅程主面。选后者：业务人按旅程思考，技术面让他们进不来；叠加旅程主面、技术面退居专家视图，既降低业务人门槛，又不丢工程能力。代价是多一层导航，收益远大于成本。

**决策二 · 第3幕给业务可读的规则矩阵投影，而非教业务人读编排文本（A3）。** 候选：培训业务人看编排 DSL / 给业务可读投影。选后者：业务人不读代码是前提约束；把决策逻辑投影成规则矩阵，业务人用"看表核对"的既有习惯即可把关，成本低、可靠。教业务人读 DSL 既不现实也不可扩展。

**决策三 · "给样例"做成一等动作（A2）。** 候选：维持"先跑用例再晋级样例"的间接路径 / 直接提交样例。选后者：业务人的心智是"这是我的标本"，间接路径引入不必要的步骤与认知负担；直接提交、系统按治理规则派生元数据，既顺手又不失治理。

**决策四 · 实景验证用独立用途 + 平台自动触发，Agent 拿不到（A5）。** 候选 A：给 Agent 的执行能力加"真实模式"开关；候选 B：独立用途、平台自动。选 B：A 把"是否真实外呼"的决定权交给大模型，违背"Agent 永不触发不可控副作用"这条最强安全底线；B 用结构隔离保证底线，且前提确定（逻辑绿刚过）时自动触发最省业务人心力。手动触发易漏、增加负担，仅保留为异常重跑入口。

**决策五 · 实景证据绑定实现指纹（A5）。** 否则会"给旧实现盖章、发布新实现"。绑定后任何漂移使证据自动失效，与人工签署失效同机制，闭环无缝。

**决策六 · 实景验证默认沙箱 + 主机白名单 + 只读（A5）。** 真实外呼若直连生产会造成真实副作用与数据风险；沙箱 + 白名单把"真实"限制在安全边界内，只读把副作用挡在门外，写操作显式走沙箱 + 对账。

两处基底摸准了：**看板是一张独立静态页 `agent-tdd.html`（原生 JS，`fetch('/api/agent-tdd/board')` 后 `renderTool`/`renderReview`）**，前端适配就是扩这张页 + 扩后端 `AgentTddBoardService` 的投影；**决策表存在节点 `config` 里**（`hitPolicy`、`conditionColumns[]`、`outputColumns[]`、`rules[]{conditions,outputs}`、`otherwise`），A3 规则矩阵直接投影这些字段。据此把「7. 工程实施计划」按 A5 深度全面展开（A5 已在上一条给出完整规格，这里补 A0–A4 并给跨适配顺序）。

---

## 7. 工程实施计划

### A0 · 业务旅程主面重组（前端为主）

**目标**：把看板从"两栏技术面板"改造成"五幕旅程主线"，每个工具显示它走到旅程第几幕、下一步业务动作是什么。

**数据模型**（在 `toolCard` 新增一个 `journey` 字段）：
```jsonc
"journey": {
  "stage": "CONTRACT|RESOURCES|ORCHESTRATION|GOLDEN|PUBLISH",
  "stageIndex": 2,                 // 0..4
  "nextAction": "APPROVE_GOLDEN",  // 供看板提示的下一步业务动作枚举
  "blocking": ["OWNER_SIGNOFF_ABSENT"] // 复用 readiness.remainingLimitations
}
```

**后端改动**（`AgentTddBoardService`）：新增纯函数 `journeyStage(card)`，仅依据卡上已有字段派生，不引新查询：
```java
private static Map<String,Object> journey(Map<String,Object> card){
  boolean speccing = "SPECCING".equals(card.get("state"));
  Map<?,?> cov = (Map<?,?>) card.getOrDefault("caseCoverage", Map.of());
  long active=num(cov,"active"), pending=num(cov,"pendingApproval");
  boolean green = gate(card,"greenBaseline");
  boolean publishable = Boolean.TRUE.equals(card.get("publishable"));
  String stage; String next;
  if (publishable)            { stage="PUBLISH";       next="SIGNOFF_OR_PUBLISH"; }
  else if (green)             { stage="PUBLISH";       next="AWAIT_ATTEST_OR_SIGNOFF"; }
  else if (active>0||pending>0){ stage="GOLDEN";        next= pending>0?"APPROVE_GOLDEN":"RUN_RED_GREEN"; }
  else if (!speccing)         { stage="ORCHESTRATION"; next="ADD_GOLDEN"; }
  else                        { stage="RESOURCES";     next="BIND_OR_FIXTURE"; }
  int idx=List.of("CONTRACT","RESOURCES","ORCHESTRATION","GOLDEN","PUBLISH").indexOf(stage);
  return Map.of("stage",stage,"stageIndex",idx,"nextAction",next,
                "blocking", card.getOrDefault("remainingLimitations", List.of()));
}
```
在 `toolCard(...)` 末尾 `card.put("journey", journey(card));`。（"CONTRACT"幕对应"还没有工具草稿、只有库契约"，属库总览视图 A1，不在工具卡里出现，故工具卡最早落 RESOURCES。）

**前端改动**（`agent-tdd.html`）：
- 顶部 `summary` 下新增一条**五幕进度条图例**（静态 5 段：提案契约 / 接源钉样例 / 编排 / golden / 发布）。
- `renderTool()` 内在 `tool-head` 下插入一行进度指示：`五段小圆点，点亮至 journey.stageIndex，并显示 nextAction 中文文案`（用一个 `NEXT_ACTION_LABELS` 映射把枚举转中文，如 `APPROVE_GOLDEN→"待你批准业务应然"`）。
- `tools` 面板标题由"实现与发布门禁"改为"业务主线 · 各工具进行到哪一幕"。

**测试**：
- 后端单元：构造 `speccing=true` 无用例 → `stage=RESOURCES`；`!speccing` 无用例 → `ORCHESTRATION`；有 pending → `GOLDEN/APPROVE_GOLDEN`；green 未签署 → `PUBLISH/AWAIT_ATTEST_OR_SIGNOFF`；publishable → `PUBLISH/SIGNOFF_OR_PUBLISH`。
- 前端（现有 `agent-tdd.html` 若有 DOM 测试则加，否则手工）：进度条点亮段数=stageIndex+1。

**顺序**：纯派生 + 静态页改动，无外部依赖，可最先落。

---

### A1 · 积木 / 世界观视图（前端为主 + 只读后端投影）

**目标**：让业务人不看 YAML 也看懂"平台给了我哪些现成积木"和"我已声明的世界观"。

**数据模型**（新端点 `GET /api/agent-tdd/library-overview`，`AGENT_TDD_READ`）：
```jsonc
{
  "buildingBlocks": [   // 平台基础积木 + 已导入库算子，业务语言
    { "ref":"httpResource", "kind":"BASE", "title":"调用一个外部服务", "effect":"READ_EXTERNAL" },
    { "ref":"bloge:decisionTable", "kind":"BASE", "title":"按规则表判定" },
    { "ref":"ride:order-lookup", "kind":"LIBRARY", "title":"订单查询", "bound":false }
  ],
  "worldModel": {       // 业务人声明的世界观
    "types":[ {"name":"Order","fields":["orderId","cancelledBy","cancelWindowSeconds","feeCharged","city"]} ],
    "operations":[ {"ref":"ride:order-lookup","title":"订单查询","inputs":["orderId"],"outputs":["order"],"bound":false} ]
  }
}
```

**后端改动**：新增 `AgentTddLibraryOverviewService`（或在 `AgentTddBoardService` 加方法），数据源=注册表 `OperatorLibraryRegistry`（已导入的库：类型/算子/是否已绑定实现）+ 可视化算子目录 `VisualOperatorCatalog`（基础积木 `httpResource`/`bloge:decisionTable`/`bloge:transform` 及其效应）。`bound = 该算子是否存在实现指向`（复用既有 `designOnly(operator)` 判定取反）。新增控制器方法在 `AgentTddBoardController` 下，鉴权 `AGENT_TDD_READ`，`no-store`。

**前端改动**（`agent-tdd.html`）：新增一个可折叠 section「第1幕 · 你的世界观与可用积木」，两列渲染：左「平台积木」（title + 效应徽标），右「你声明的世界观」（类型字段 + 操作，`bound` 用"已接入/仅契约"徽标）。新增一次 `request('/api/agent-tdd/library-overview', {headers:headers('AGENT_TDD_READ')})`。

**测试**：后端单元——注册一个含 1 设计态算子 + 1 已绑定算子的库 → overview 的 `worldModel.operations` 两条、`bound` 分别 false/true；`buildingBlocks` 含 3 个 BASE。前端——两列渲染且 bound 徽标正确。

**顺序**：只读投影 + 静态页，独立，可与 A0 并行。

---

### A2 · "给样例"一等路径 + 资源桥接（前后端）

**目标**：业务人直接"给几组样例"即成可复用验证数据；引用的真实资源若未注册，给补建桥接、不断裂。

#### A2-1 给样例一等路径
现状：把样例变 fixture 只能走 `rg.fixture.promote`——它要 `{draftId,nodeId,outputPort}`，即从某次运行**捕获**的输出晋级（服务端派生作用域/schema/来源，客户端不能注入坐标）。对"这是我的样例"是间接的。

**新增 MCP 工具 `rg.fixture.provide`（影响 `GOVERNED_WRITE`）**：直接对某个"库算子/资源"提交一个样例值，服务端**校验其符合该算子声明的输出 schema**后，按现有治理规则派生元数据存为 fixture（`sourceKind=SAMPLE`）。
```jsonc
// in
{ "operatorRef":"ride:order-lookup", "outputPort":"order",
  "sampleValue": { /* 业务 JSON，由 operator 输出契约约束 */ },
  "category":"", "retentionDays":0, "redactPaths":["/order/feeCharged"], "idempotencyKey":"" }
// out.data
{ "fixtureId":"", "scope":"(服务端派生)", "schemaRef":"(服务端派生)", "sourceKind":"SAMPLE", "lineageRef":"" }
// errors: SCHEMA_NONCONFORMANT(样例不符输出契约), LIBRARY_NOT_FOUND, IDEMPOTENCY_CONFLICT
```

**后端改动**：
- `McpToolCatalog`：新增该工具定义（阶段五，`GOVERNED_WRITE`，input schema 如上）。
- `ResourceGatewayAgentTddTools.invoke`：新增 `case "rg.fixture.provide" -> executionSuccess(workflow().provideFixture(...))`。
- `AgentTddWorkflowService.provideFixture(...)`：`idempotent` 包裹；解析 `operatorRef` 的输出 schema（从库/目录）；用现有 fixture 治理组件校验 `sampleValue` 符合该 schema（复用 `GraphNodeFixturePromotionService` 里已有的"输出符合算子 schema"校验逻辑，抽出一个不依赖 draft/node 的 `validateAndDeriveSample(operatorRef, outputPort, value, request)` 入口）；派生 `scope/schemaRef/sourceKind=SAMPLE/lineage` 后落库。**不接受客户端注入作用域**（同现有纪律）。

#### A2-2 资源桥接
现状：绑定要指向**已注册**的资源（描述符）；未注册则走不通（老的外部接口表单是逃生舱）。

**改动**：
- `AgentTddMutationService.compose(...)`：当 `resolveRuntimeBindings` 发现引用了未注册的 `resourceId` 时，抛稳定码 **`RESOURCE_NOT_REGISTERED`**（附缺失的 resourceId 列表，无载荷）。
- **新增 MCP 工具 `rg.resource.declare`（`DRAFT_WRITE`）**：薄封装既有外部接口登记（写"资源描述符"+"设计契约"两处，复用现有 admin 能力），让主线内即可补建资源再回来绑定。
```jsonc
// in: { "resourceId":"ride-order-service.lookup", "method":"GET", "urlTemplate":"…",
//       "payloadSchema":{…}, "idempotencyKey":"" }
// out.data: { "resourceId":"", "registered":true }
// errors: SCHEMA_NONCONFORMANT, EGRESS_NOT_ALLOWED(host 不在白名单,复用 A5 §6), IDEMPOTENCY_CONFLICT
```
（注：登记本身仍是"两写"，承接遗留 #9 的对账；host 白名单校验复用 A5 §6。）

**前端改动**：`agent-tdd.html` 第2幕 section 显示"样例（fixture）"列表（只读，来自 board 若已含，或新增只读端点）；资源桥接主要是 Agent 侧提示，无需看板改动。

**测试**：
- `rg.fixture.provide`：样例符合输出契约 → 存 `sourceKind=SAMPLE`；不符 → `SCHEMA_NONCONFORMANT`；同 key 异内容 → `IDEMPOTENCY_CONFLICT`。
- 资源桥接：compose 引用未注册 resource → `RESOURCE_NOT_REGISTERED`；`rg.resource.declare` 后再 compose → 通过。

**顺序**：依赖既有 fixture 治理组件（抽 `validateAndDeriveSample`）与 A5 §6 的 host 白名单（`rg.resource.declare` 复用）。

---

### A3 · 业务可读规则矩阵投影（前端为主 + 后端投影）

**目标**：把工具展示从"算子引用/节点/连线"的技术视图，换成业务人能自助核对的**规则矩阵 + 白话流程**。

**数据模型**（在 `toolCard` 新增 `ruleMatrices` 与 `flowSummary`）：
```jsonc
"ruleMatrices": [
  { "nodeId":"disputePolicy", "hitPolicy":"unique",
    "conditionColumns":[{"id":"party","label":"责任方"},{"id":"withinFree","label":"免责时长内"},{"id":"abuse","label":"恶意申诉"}],
    "outputColumns":[{"id":"decision","label":"处置"},{"id":"reviewLane","label":"通道"}],
    "rules":[ {"conditions":{"abuse":"= confirmed"}, "outputs":{"decision":"ESCALATE_HUMAN","reviewLane":"human"}}, … ],
    "otherwise":{"decision":"ESCALATE_HUMAN","reviewLane":"human"} }
],
"flowSummary":"取『订单/责任/政策/历史』四项事实 → 汇成纠纷上下文 → 按规则表判 → 产出处置方案"
```

**后端改动**（`AgentTddBoardService.toolCard`）：
- 新增 `ruleMatrix(node)`：对 `node.operatorRef().equals("bloge:decisionTable")` 的节点，从 `node.config()` 读 `hitPolicy`、`conditionColumns`、`outputColumns`、`rules`（每条含 `conditions` map 与 `outputs` map）、`otherwise`（这些 key 与现有前端 `AuthorCanvas.tsx`/`effectiveContractProjection.ts` 所读一致），投影成上面的结构；谓词/输出值转为**展示字符串**（如 `abuse == "confirmed"`→`"= confirmed"`），不含业务载荷。
- 新增 `flowSummary(draft)`：按依赖拓扑排序节点，用算子效应把节点归类为"取数/汇总/判定/产出"，拼一句白话（模板化，非自由文本）。
- `card.put("ruleMatrices", …); card.put("flowSummary", …);`

**前端改动**（`agent-tdd.html`）：`renderTool()` 的第三列由现在的"步骤 · N 节点（operatorRef 列表）"改为：
- 若有 `ruleMatrices`，渲染一张 HTML 表格：表头=条件列 label + 输出列 label；每行=一条规则（条件单元格显示谓词串、输出单元格显示值）；末行=`otherwise`。
- 表格上方一行显示 `flowSummary`。
- 保留 operatorRef 技术视图为"展开查看技术结构"的折叠项（工程师逃生舱）。

**测试**：
- 后端单元：构造含 `config.{hitPolicy,conditionColumns,outputColumns,rules,otherwise}` 的决策表节点 → `ruleMatrices[0]` 列/行/otherwise 正确；无决策表节点 → `ruleMatrices` 为空、`flowSummary` 仍生成。
- 前端：给定 `ruleMatrices` → 渲染出对应行列的表格；改一条规则输出 → 表格对应单元格变化。

**顺序**：依赖 board 数据；纯投影 + 静态页，独立于 A5。

---

### A4 · 事实组合覆盖视图（后端 + 前端）

**目标**：让业务人看得出"哪些事实组合已被 golden 覆盖、哪些是盲区"，而不仅是用例计数。

**数据模型**（在 `toolCard` 新增 `factCoverage`）：
```jsonc
"factCoverage": {
  "dimensions":[ {"column":"party","values":["passenger","driver","platform","none"]},
                 {"column":"withinFree","values":["true","false"]},
                 {"column":"abuse","values":["none","suspected","confirmed"]} ],
  "coveredCount": 5, "totalCount": 24,
  "blindSpots":[ {"party":"platform","withinFree":"true","abuse":"none"}, … ]  // 截断上限如 20 条
}
```

**后端改动**（`AgentTddBoardService`，复用枚举器的代表值逻辑）：
- 事实空间 `dimensions`：对该工具决策表的每个条件列，用**与既有确定性枚举器同一套**代表值推导（枚举/阈值邻域/成员集）得出该列取值集合。抽 `AgentTddDecisionScenarioEnumerator` 里的"谓词→代表值"为可复用方法供此处调用（避免两套逻辑）。
- 已覆盖组合：把该工具 `CASE_SET` 中 `lifecycle=ACTIVE` 的 golden 行的 `given` 投影到这些条件列，得已覆盖组合集合。
- `blindSpots = 笛卡尔积(dimensions) − 已覆盖`，按确定性顺序截断到上限（如 20），并给 `coveredCount/totalCount`。

**前端改动**（`agent-tdd.html`）：`renderTool()` 在红/绿行旁新增一行"覆盖 · 已覆盖 X / 共 Y"，并在折叠区列出前若干条 `blindSpots`（每条一行事实组合），零盲区时显示"事实组合已覆盖周全"。

**测试**：后端单元——2 列（各 2、3 取值）→ `totalCount=6`；给 2 条 golden → `coveredCount=2`、`blindSpots` 列出其余 4；确定性顺序稳定。前端——渲染覆盖行 + 盲区列表。

**顺序**：依赖抽出的枚举器代表值方法（与 A3 都读决策表 config，可同批做）。

---

### A5 · 实景验证（ATTEST）
见前述**完整工程规格**（§枚举 `AGENT_TDD_ATTEST` / `ATTESTATION` 复用现有资产表无新迁移 / `AgentTddAttestationService` 算法 / `VisualGraphRunService.run` 真实执行与 `nodeAttempts` 观测 / `EgressHostPolicy` host 白名单 / 自动触发运行者 / `readiness`·`publish` 门禁 diff / 测试用例 / 两处待确认）。此处不重复。

---

### 跨适配顺序与依赖（一张图）
```
P0  A0 旅程主面(纯派生+静态页)         ── 独立，最先
P0  A1 世界观视图(只读投影+静态页)     ── 独立，可并行
P1  抽公共:枚举器"谓词→代表值"方法      ── A3/A4 共用
P1  A3 规则矩阵投影(读决策表 config)   ── 依赖 P1
P1  A4 覆盖视图(读决策表 config)       ── 依赖 P1
P2  A5 §6 host 白名单                  ── 独立安全项(清 SSRF)
P2  抽公共:fixture "校验+派生样例"入口  ── A2-1 用
P2  A2-1 rg.fixture.provide           ── 依赖上一步
P2  A2-2 rg.resource.declare + 桥接    ── 依赖 A5 §6 白名单
P3  A5 证据/服务/门禁/自动触发          ── 依赖 A5 §6
P4  端到端旅程用例贯穿                  ── 依赖全部
```
**验收总纲**：一条端到端"旅程用例"串起——世界观视图可见（A1）→ 提供样例成 fixture（A2-1）→ 编排后规则矩阵可读（A3）→ 覆盖盲区可见（A4）→ golden 批准 → 逻辑绿 → 自动实景验证 ATTESTED（A5）→ 签署发布；且看板每步显示正确的旅程幕次（A0）。

---

## 8. 落地后遗留 / 新暴露问题及预计解法
1. **业务可读投影的表达力边界**：规则矩阵能覆盖决策表类逻辑，但复杂编排（循环、并发、子图）如何业务可读仍难——预计解法：对复杂结构给"白话摘要 + 可展开技术视图"的分层呈现。
2. **真实写副作用的实景验证**：本方案默认只读；写副作用的实景验证需专门治理——预计解法：为写副作用设计沙箱替身 + 对账证据，作为实景验证的子能力分期落地。
3. **沙箱与生产差异**：实景验证证明"沙箱能对接"，与生产仍有一致性差距——预计解法：发布后金丝雀/周期性重跑实景验证，证据标注环境、不冒充生产。
4. **资源桥接的权责**：让业务流程能补建外部接口，涉及谁有权登记外部依赖——预计解法：把外部接口登记纳入角色与审批，业务侧只发起、由授权角色确认。
5. **"用例质量就绪"与"工具可发布"两个词易混**：一个用例"跑通即达标"的质量标记，与工具"可发布"的就绪是两回事——预计解法：在旅程主面与看板明确区分二者。
