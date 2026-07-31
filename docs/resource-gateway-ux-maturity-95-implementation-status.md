# Resource Gateway UX 95 分实施状态

> 状态：In Progress
>
> 基准计划：[Resource Gateway 体验成熟度 95 分校准与修正计划](resource-gateway-ux-maturity-95-recalibration-plan.md)
>
> 当前实施轮次：Stage E / 资产生命周期与修复闭环
>
> 评分纪律：代码完成只能获得 E1；真实服务和浏览器验证后最多获得 E2。没有 E3 目标用户
> 证据时，不宣称体验成熟度达到 95 分。

## 1. 总览

| 阶段 | 目标分 | 状态 | 当前证据 |
|---|---:|---|---|
| 基线 | 74 | 已确认 | 2026-07-31 真实浏览器复评 |
| Stage A：可信度止血 | 80 | 已完成 | A01–A07、三条浏览器纵切与完整回归，E2 |
| Stage B：唯一中央工作面 | 85 | 已完成 | B01–B08、真实浏览器断点矩阵，E2 |
| Stage C：统一 Scenario | 90 | 已完成 | C01–C08、392 条前端回归与三条浏览器纵切，E2 |
| Stage D：复杂图可读 | 93 | 已完成 | D01–D07、1024/1280/820 真实浏览器矩阵，E2 |
| Stage E：生命周期闭环 | 95 工程就绪 | 进行中 | durable asset / remediation 差距审计，E0 |
| Stage F：E3/E4 | 95–100 | 未开始 | 无 |

Stage D 的工程目标分 `93` 已达到 E2，但这不等于整体体验成熟度达到 95。当前剩余
2 个工程分主要来自 durable asset 恢复与跨 Evidence / Rehearsal / Governance 的修复闭环；
E3 目标用户证据仍是正式宣称 95 分的必要条件。

## 2. Round A1：Canonical Scenario Run

### 2.1 已实现

| 计划项 | 实现 |
|---|---|
| UX95R-A01 | `ScenarioEditorSnapshot` 深拷贝并冻结点击 Run 瞬间的 Scenario、Contract schema 和节点 schema |
| UX95R-A02 | 编译结果绑定 editor、compiled plan、request source、evidence source 和 request material fingerprint |
| UX95R-A03 | RETURN / DELAY 输出按节点 output schema 检查必填值；空值在网络调用前阻断并聚焦到字段 |
| UX95R-A04 | Operator schema-only 结果显示 `Schema valid`，Function 真实执行显示 `Runtime passed` |
| UX95R-A06（部分） | Evidence 相同根因诊断聚合，并保留 occurrence count |

同时修复了一条隐藏的 canonical-state 破坏路径：Author 顶部快捷运行不再把全局 Context
二次合并并覆盖 Scenario `Given`。Graph 和 Operator 的工作台运行现在都只接受
`ScenarioEditorSnapshot`。

### 2.2 运行不变量

```text
visible graphical values
  -> frozen ScenarioEditorSnapshot
  -> transient compilation plan
  -> SimulationRequest
  -> retained local evidence coordinate
```

以下条件任一不满足时不得调用 simulate：

1. Scenario target 或 Contract fingerprint 已过期；
2. 必填 Return 值为空；
3. 行为无法无损降级到 transient NodeFixture；
4. 编译未生成 fingerprint closure proof；
5. 请求 material fingerprint 与编译记录不一致。

### 2.3 用户可见行为

1. 在 Scenarios 中修改 Return 字段；
2. 点击 `Run & Compare`；
3. 实际请求严格使用当前可见值；
4. 清空必填 Return 字段时，页面显示 `Required Return value ... is empty.`；
5. 焦点自动回到该字段，不会发起请求；
6. Operator contract test 的成功结论为 `Schema valid`，不再暗示运行时业务行为通过。

### 2.4 测试证据

聚焦回归：

```bash
cd resource-gateway-examples/src/main/frontend
npx vitest run \
  src/contract-scenario/scenarioEditorModel.test.ts \
  src/contract-scenario/scenarioCompiler.test.ts \
  src/contract-scenario/evidenceModel.test.ts \
  src/contract-scenario/ContractScenarioWorkspace.test.tsx \
  src/library-authoring/AssetTestTable.test.tsx
```

结果：`5` 个测试文件、`49` 条测试全部通过。

完整前端回归：`38` 个测试文件、`362` 条测试全部通过。完整回归还验证了三个内置 Graph
模板的 Scenario 投影；Scenario 节点 schema 现在保留单一命名端口外层，与
`NodeFixture.output` 的真实 request shape 一致。

生产构建：

```bash
cd resource-gateway-examples/src/main/frontend
npm run build
```

结果：TypeScript 检查和 Vite production build 通过。

新增或强化的关键测试：

- 编辑快照不会被后续 mutable draft 修改；
- editor / plan / request / evidence source fingerprint 完全一致；
- request material fingerprint 可独立重算；
- 空必填 Return 阻断 runtime；
- UI 将焦点移回准确 schema path；
- 重复诊断合并并显示 occurrence count；
- Schema-only 测试不显示裸 `Passed`。

## 3. Round A2：Readiness 与示例可信度

### 3.1 已实现

| 计划项 | 实现 |
|---|---|
| UX95R-A05 | 三个 Library 示例显式标记 `Design-only`，使用具名业务类型；Order 示例补齐外部写协议。三个示例均为 0 error group / 0 warning group |
| UX95R-A06 | Library diagnostics 按 `code + path + root cause` 聚合，并显示 occurrence count |
| UX95R-A07 | `ReadinessPresentation` 把 design、schema、runtime binding 投影为一个保守结论、一个下一步和机器状态 |

Readiness 不再把 `DESIGN_READY` 误读为“可以运行”。典型投影：

```text
Design valid; runtime unbound
Contract structure is valid, but no runtime implementation is bound.
Next: Bind a runtime implementation before execution or promotion.
```

三个内置 Library 示例的额外约束：

- Operator input/output 不得使用 `any` 或 `unknown`；
- built-in function signature 不得使用 `any` 或 `unknown`；
- 所有具名类型都必须在当前 Library 内可解析；
- 有外部副作用的示例必须声明幂等性或完整 side-effect protocol；
- 示例来源在命令区和选择器中都明确显示为 `Design-only`。

三个内置 Graph 模板继续由完整前端回归验证 Scenario 投影和 request shape。Loan 示例另由
真实浏览器完成端到端试跑。

### 3.2 真实浏览器 E2 证据

使用打包后的 Spring Boot 服务在真实浏览器完成以下纵切：

| 目标 | 操作 | 可见证据 |
|---|---|---|
| Graph | 加载 `Loan policy fallback`，打开 Scenario，运行 `Run & Compare` | Execution `PASSED`、Assertions `PASSED`、`1/1` assertion、3 mocked / 2 real nodes，输出与可见 Return 值一致 |
| Operator | 打开 `support:classify-ticket` 的 test table 并运行 | `Schema valid`，显示当前 fingerprint 证据，不暗示 runtime 通过 |
| Function | 打开 `customer support -> trim` 的 test table 并运行 | `Runtime passed`，返回 `"sample"`，显示当前 fingerprint 证据 |
| Library | 依次加载 Customer、Order、Risk 三个模板 | 均显示 `Design valid; runtime unbound`、0 error group、0 warning group、`No diagnostics` |

Graph 浏览器纵切使用的可见 mock 数据为：

- primary score `728`、provider `primary`、band `A`；
- secondary score `701`、provider `secondary`、band `B`。

结果中的 terminal output 与这些可见值和 Then 断言完全一致。唯一提示
`DRAFT_EPHEMERAL` 被明确描述为可审计性边界，不被当作业务失败。

浏览器视觉复核还发现并修复了 Readiness 标题与结论共用容器 class 导致的文本拼接问题。
修复后标题、结论、机器状态、三类 readiness、Next action 和 diagnostics 分层显示。

### 3.3 完整验证

前端完整回归：

```bash
cd resource-gateway-examples/src/main/frontend
npm test
```

结果：`40` 个测试文件、`368` 条测试全部通过。

生产构建：

```bash
cd resource-gateway-examples/src/main/frontend
npm run build
```

结果：TypeScript 检查与 Vite production build 通过。

项目完整回归：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

结果：`5841` 条测试、`0` 失败、`0` 错误、`10` 跳过，`BUILD SUCCESS`。完整验证包含
Spring Boot 集成测试和仓库自带的真实浏览器工作流，耗时 `14:44`。

### 3.4 Stage A 退出审计

| 退出门槛 | 结果 | 证据等级 |
|---|---|---|
| 修改可见 mock 值后 request 与结果同步 | 通过 | E2 |
| 空必填 Return 在网络前阻断并聚焦 | 通过 | E1 自动化 |
| 无 UI 空值、evidence 隐藏业务值路径 | 通过 | E1 canonical invariant |
| 不显示无证据范围的裸 `Passed` | 通过 | E2 |
| 3 Graph + 3 Library 示例无非教学 warning | 通过 | Graph E1 / Library E2 |
| 同根因诊断分组并显示次数 | 通过 | E1 + Library E2 |
| Graph、Operator、Function 浏览器纵切 | 通过 | E2 |

因此 Stage A 目标分 `80` 在 E2 层成立。由于尚未进行目标用户 E3 研究，不能把这一分数外推
为正式发布体验评分。

## 4. Stage B：唯一中央工作面

### 4.1 已实现

| 计划项 | 实现 |
|---|---|
| UX95R-B01 | `AuthorSurfaceRouter` 让 Compose / Contract / Scenarios / Evidence 各自只挂载一个中央工作面 |
| UX95R-B02 | Contract 正式工作面集中展示 Interface、semantics、Compatibility 和完整 lineage |
| UX95R-B03 | Scenario 正式工作面直接承载 case list、Given / Dependencies / Then 和 Run |
| UX95R-B04 | Evidence 正式工作面直接展示 Verdict、可信维度、finding、assertion 和 trace |
| UX95R-B05 | `TopologyContextRail` 保留 graph、节点、直接上下游、Scenario 与 Run 坐标 |
| UX95R-B06 | 命令条按 mode 收敛；Compose 才显示 Import / Auto layout，每个 mode 只有一个主操作 |
| UX95R-B07 | URL、History 与 legacy `operatorRef` 迁移统一；缺失 Operator 确定性回退 Graph |
| UX95R-B08 | v2 主路径不再挂载 Contract/Scenario/Evidence dialog；旧 presentation 只作 legacy adapter |

中央工作面中的动作也按任务收敛：

- Contract：Save Graph、Export / Import Workspace；
- Scenarios：Load / Save Scenario、Export / Import Workspace；
- Evidence：Publish、Export / Import Workspace；
- exploratory 状态下才补充 Save Graph，不在所有工作面重复堆放动作。

Contract 新增正式 Lineage 区，明确 target kind / id / revision、target fingerprint、
Contract fingerprint、source 与 confidence。作者在离开 Compose 后仍可从右侧拓扑轨看到
准确节点和直接上下游；紧凑视口下该轨变成可开关抽屉，关闭不会改动 URL 或业务状态。

### 4.2 URL 与恢复不变量

```text
authorMode + target + nodeId + workspaceView + scenarioId + runId
  -> one canonical authoring coordinate
  -> pushState for user navigation
  -> popstate restores exact mode and target
```

旧 `operatorRef=x` 会迁移为 `target=operator:x`，下一次 URL 写入会删除旧参数。URL 指向
不存在的 Operator 时，工作区显示 fallback 提示并回到 Graph target，不会静默落到另一个
Operator。

### 4.3 E2 浏览器证据

真实浏览器人工纵切覆盖 1280px：

- Contract、Scenarios、Evidence 均在中央区域显示，页面 `role=dialog` 数为 0；
- Graph target 与 `bloge:transform` Operator target 均保持准确；
- Scenario `Run & Compare` 成功进入 Evidence；
- Back / Forward 在 Scenario 与 Evidence 间恢复同一 `n5`、Scenario 和 run；
- Diagnostics 不再拦截 Scenario 底部 Run。

仓库 Selenium 门禁覆盖 1024、820、390：

- 1024：Contract 只保留 `Contract details / Compatibility` 次级 tabs，Lineage 可见；
- 820：Topology 抽屉可开关且 URL 不变，Run 按钮与 Diagnostics 几何不相交；
- 390：Evidence 为中央 `region`，不存在 nested dialog、横向溢出或 header/action 重叠；
- 移动端 Author 使用全高、可收缩应用壳，旧版通用 `.workspace { flex: none }` 不再使
  工作区越出视口。

### 4.4 自动化证据

聚焦前端回归覆盖 `AuthorSurfaceRouter`、`AuthorCommandBar`、`TopologyContextRail`、
URL migration、Contract Scenario workspace 和完整 AuthorCanvas 历史恢复，共 `98` 条测试。

真实浏览器回归：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=VisualAuthoringBrowserDomTest#taskWorkspacePreviewsCollisionFreeLayoutAndUsesCompactDrawersInRealBrowser \
  test
```

结果：`1` 条完整断点纵切通过。失败诊断会输出 viewport、workspace、canvas、surface、
scroll body、footer 和 Diagnostics 几何，便于以后直接定位响应式回潮。

### 4.5 Stage B 退出审计

| 退出门槛 | 结果 | 证据等级 |
|---|---|---|
| Contract / Scenarios / Evidence 不出现 modal | 通过 | E2 |
| 页面不存在两套同名任务 tabs | 通过 | E1 + E2 |
| Back / Forward 恢复 mode、target、scenario、run | 通过 | E1 + E2 |
| Compose 到 Contract 保持 exact selected node | 通过 | E1 + E2 |
| 关闭 secondary drawer 不改变业务状态 | 通过 | E1 + E2 |
| 1024、820、390 无 nested dialog | 通过 | E2 |
| Legacy deep link 有确定性迁移与 fallback | 通过 | E1 |

因此 Stage B 目标分 `85` 在 E2 层成立。

## 5. Stage C：统一 Scenario 与测试创作

### 5.1 已实现

| 计划项 | 实现 |
|---|---|
| UX95R-C01 | `SchemaValueEditor` 作为 Graph、Operator、Function 共享值编辑入口；visual-first，Advanced JSON 显式折叠 |
| UX95R-C02 | Operator / Function 横向 Raw JSON 表替换为左侧 case list + 右侧 selected case editor |
| UX95R-C03 | `controllableDependencies` 只投影 fixture 控制的调用；Real 节点继续留在执行计划和证据 |
| UX95R-C04 | Return / Error / Delay / Timeout / Replay / Observe / Deny 保留完整预设，新增依赖默认 Return |
| UX95R-C05 | Graph Then 继续支持 output path、schema、node status、edge transfer 和 invocation assertion |
| UX95R-C06 | Advanced JSON 保留最后一个合法 canonical value，错误 JSON 不污染运行请求 |
| UX95R-C07 | Fixture Save 在测试 dialog 内改为 complementary side sheet，加入脱敏后 payload preview |
| UX95R-C08 | 现有 Operator / Function suite 仍按原 wire protocol 读取和运行，具名表单通过无损 adapter 回写 |

Function 参数不再要求用户记住数组位置。`functionSignatureSchema` 把
`(text: string, fallback?: string) -> string` 投影为具名字段，运行前再按声明顺序恢复
wire `args[]`。Operator 的 input、config、mocked output 同理由 Library compact type 与
具名类型投影成 JSON Schema。

### 5.2 渐进披露与运行边界

```text
visible Schema form
  -> canonical case value
  -> existing versioned test-suite wire adapter
  -> exact-draft run
  -> signed evidence coordinate
```

- 5 节点图只有 3 个 fixture 时，Scenario 只显示 3 张 controlled dependency 卡；
- 完整依赖默认折叠为 identity + behavior，缺 selector、Replay ref、Return output、
  duration、error code 或 waiver reason 时自动展开；
- `+ Dependency` 创建可立即编辑的 Return override，而不是制造一张无意义的 Real 卡；
- 未出现在 Scenario 中的节点照常真实执行，其 node/edge trace 不从 Evidence 消失；
- Fixture sheet 不创建第二个 modal，也不接管父测试工作区的业务坐标；
- preview 自动遮盖 password、secret、token、credential、API key 等敏感键，并应用用户
  输入的 JSON Pointer 路径。

### 5.3 自动化与 E2 证据

完整前端回归：

```bash
cd resource-gateway-examples/src/main/frontend
npm test
```

结果：`46` 个测试文件、`392` 条测试全部通过。生产构建的 TypeScript 与 Vite build
通过。新增 golden 覆盖 object、array、enum、nullable、union、named function args、
5 节点受控依赖投影、完整/缺失依赖展开策略、side sheet 语义和脱敏 preview。

真实 Spring Boot 服务与浏览器纵切：

| 目标 | 可见结果 |
|---|---|
| Operator | `support:classify-ticket` 显示一列 case list；Given 和 Then 是嵌套具名字段，无默认 Raw JSON |
| Function | `support.normalizeText` 的 `text` 参数按签名显示；Then 明确区分 Equals、Return schema、Error |
| Fixture | 父级仍只有一个 dialog；右侧 sheet 显示 classification、retention、redaction 和 preview |
| Graph | Loan Prime 场景显示 `3 controlled dependencies`，三张完整 Return 卡默认折叠 |

### 5.4 Stage C 退出审计

| 退出门槛 | 结果 | 证据等级 |
|---|---|---|
| 默认测试路径不出现 Raw JSON | 通过 | E1 + E2 |
| 从 Schema 创建 meaningful case | 通过 | E1 + E2 |
| Graph / Operator / Function 使用 Given / Dependencies / Then | 通过 | E2 |
| Dependency 卡不超过 controlled dependency | 通过 | E1 + E2 |
| 完整 Dependency 默认折叠、缺失项展开 | 通过 | E1 + E2 |
| Fixture 不出现 dialog in dialog | 通过 | E1 + E2 |
| 旧 suite 无损读取，现有 wire 兼容 | 通过 | E1 |
| object / array / enum / nullable / union parity | 通过 | E1 golden |

因此 Stage C 目标分 `90` 在 E2 层成立。

## 6. Stage D：复杂图感知可读性

### 6.1 已实现

| 计划项 | 实现 |
|---|---|
| UX95R-D01 | `CanvasSemanticMode` 明确定义 Overview / Focus / Inspect；三者分别投影拓扑、完整闭包和完整字段语义 |
| UX95R-D02 | `adaptiveCanvasChromePolicy` 按任务、图规模、zoom、selected node 和用户 panel preference 决定侧栏；明确打开动作覆盖旧偏好 |
| UX95R-D03 | `CanvasPerceptualQualityReport` 同时报告几何状态、等效标题字号、label/field 数、密度、占用率和可执行原因 |
| UX95R-D04 | data edge 按 source 聚合为语义 bundle，保留 exact field count、目标数、完整 title 和原 edge id 集合 |
| UX95R-D05 | 线性复杂度拓扑 lane 为 25/100 节点提供 Inputs / Stage / Outputs 结构导航 |
| UX95R-D06 | Focus 只显示 selected node 完整上下游闭包，标签预算为 12；Fit 使用同一闭包边界 |
| UX95R-D07 | Selenium 在 1024 / 1280 / 820 断点读取真实像素，断言节点、标签、视口和等效字号 |

布局不再靠持续扩大节点间距。标签规划器遍历无碰撞轨道，并综合轨道距离、图边界溢出和
上下安全带拥挤度评分；Inspect 可把竞争的长标签分配到上下安全带，Focus 又使用实际闭包
作为视口边界。Fit All 最大只恢复到 `100%`，不会为了填满宽屏而意外放大并吃掉标签余量。

### 6.2 用户可见行为

1. **Overview**：只看节点、连线和 stage 形状，字段 label 为 0；
2. **Focus**：点选节点后显示完整上游/下游闭包，旁路降噪；
3. **Inspect**：恢复完整字段语义；Loan 的 12 条字段依赖显示为 4 个 bundle；
4. **Readability**：导航条显示 Pass / Review、等效字号和当前 label 密度；
5. **Adaptive panels**：compact 视口默认回收左右栏，用户明确点开时不会被旧偏好立即关回；
6. **25/100 节点**：stage overview 按拓扑 lane 导航，不要求在一个视口辨认每条字段。

### 6.3 E2 浏览器证据

以 `Loan policy fallback` 的 5 节点、12 字段依赖图为固定样本：

| 视口 / 模式 | zoom | 等效标题 | label / field | 实际像素结果 |
|---|---:|---:|---:|---|
| 1024 Inspect | 89% | 13.4px | 4 / 12 | node/label、label/label、node/label 越界全部 0 |
| 1024 Focus(n2) | 86% | 12.9px | 3 / 10 | 完整闭包可读，全部碰撞和越界为 0 |
| 1280 Inspect | >= 89% | >= 13.4px | 4 / 12 | 不低于 1024，全部碰撞和越界为 0 |
| 820 Overview | 受视口约束 | 明确 Review | 0 / 0 | 双侧栏收起，节点在界内；点节点后可进入 Inspect |

820 不伪装成完整 Inspect 可读：当完整字段视图低于可读性地板时，报告明确显示 Review，
用户先用 Overview 判断形状，再选择节点进入 Inspect。

纯函数测试还验证：

- 100 节点含回边的 lane 推导在 `50ms` 预算内完成；
- label bundle 不丢字段依赖和 exact title；
- 上下安全带在竞争长标签时确定性分流；
- small graph 低于 80% 或等效标题低于 12px 时不得报告 Pass；
- 用户 `open / closed / auto` panel preference 不进入 GraphDraft。

真实 Chrome 门禁：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='VisualAuthoringBrowserDomTest#taskWorkspacePreviewsCollisionFreeLayoutAndUsesCompactDrawersInRealBrowser' \
  test
```

结果：通过。浏览器旅程同时覆盖 Auto Layout preview 稳定态、panel 互斥、Topology
launcher 显式操作优先级、Scenario Run 与 390px Evidence。

### 6.4 Stage D 退出审计

| 退出门槛 | 结果 | 证据等级 |
|---|---|---|
| 5 节点图在 1024 / 1280 默认 zoom >= 80% | 通过 | E2 |
| Overview 无字段 label | 通过 | E1 + E2 |
| Focus 只显示闭包且 0 collision | 通过 | E1 + E2 |
| Inspect 恢复完整字段语义 | 通过 | E1 + E2 |
| 25/100 节点有 lane overview | 通过 | E1；90 秒定位等待 E3 |
| 100 节点投影 2 秒内可交互 | 通过 | E1 性能预算 |
| 1280 不比 1024 可读性更低 | 通过 | E2 |
| quality report 同时覆盖几何与感知 | 通过 | E1 + E2 |

因此 Stage D 目标分 `93` 在 E2 层成立。25 节点 90 秒定位仍需由 E3 用户研究证明。

## 7. 当前差距与 Stage E 开工点

画布阅读问题已从 P0/P1 缺陷转为受控的 compact Review。剩余工程差距集中在“找到资产”和
“知道下一步怎么修”：

1. Libraries 仍缺 Recent / Mine / Needs confirmation / Runtime drift 等任务队列；
2. durable draft 缺少 exact revision 的统一 resume 入口；
3. Contract、Scenario、Evidence、Drift、Rehearsal 与 ANEKE gate 还没有统一
   `RemediationAction` 投影；
4. Evidence 首屏仍需进一步把业务 verdict、Expected / Actual / Diff 和 owner 放在
   fingerprint 之前；
5. Rehearsal 需要 attempt timeline、准确目标 deep link 和权限诚实的修复动作；
6. Stage E 完成后仍只能宣称“95 分工程就绪”，正式 95 分等待 E3/E4。
