# Resource Gateway UX 95 分实施状态

> 状态：In Progress
>
> 基准计划：[Resource Gateway 体验成熟度 95 分校准与修正计划](resource-gateway-ux-maturity-95-recalibration-plan.md)
>
> 当前实施轮次：Stage B / 唯一中央工作面
>
> 评分纪律：代码完成只能获得 E1；真实服务和浏览器验证后最多获得 E2。没有 E3 目标用户
> 证据时，不宣称体验成熟度达到 95 分。

## 1. 总览

| 阶段 | 目标分 | 状态 | 当前证据 |
|---|---:|---|---|
| 基线 | 74 | 已确认 | 2026-07-31 真实浏览器复评 |
| Stage A：可信度止血 | 80 | 已完成 | A01–A07、三条浏览器纵切与完整回归，E2 |
| Stage B：唯一中央工作面 | 85 | 进行中 | 源码差距审计已完成，E0 |
| Stage C：统一 Scenario | 90 | 未开始 | 无 |
| Stage D：复杂图可读 | 93 | 未开始 | 无 |
| Stage E：生命周期闭环 | 95 工程就绪 | 未开始 | 无 |
| Stage F：E3/E4 | 95–100 | 未开始 | 无 |

Stage A 的工程目标分 `80` 已达到 E2，但这不等于整体体验成熟度达到 95。当前最主要的
15 分差距来自中央工作面仍使用 mega modal、Scenario 创作语言不统一、复杂图感知可读性
不足，以及资产恢复与诊断修复闭环缺失。

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

## 4. 当前差距与 Stage B 开工点

Stage A 解决的是“结果是否可信”，并未解决“工作面是否直观”。源码审计确认当前顶层已有
`Compose / Contract / Scenarios / Evidence`，但后三个 mode 仍会打开
`ContractScenarioWorkspace` 全屏 dialog，并在弹层内再次提供
`Interface / Scenarios / Compatibility / Evidence`。这造成：

1. 顶层 mode 与弹层 tab 两套任务导航；
2. URL、modal open state 和 active tab 三个状态源；
3. 从 Compose 进入 Contract 后，拓扑上下文被遮住；
4. 1024、820、390 宽度下形成接近 nested dialog 的工作方式；
5. 关闭弹层会把用户送回 Compose，业务任务状态与视图生命周期耦合。

Stage B 将先完成以下纵切：

1. 让 `ContractScenarioWorkspace` 支持中央页面与 legacy dialog 两种 presentation；
2. 由 `authorMode` 唯一控制中央 `Contract / Scenarios / Evidence` surface；
3. 移除中央页面中的重复任务 tabs，只保留 Contract 内部的 Compatibility 次级入口；
4. 非 Compose mode 隐藏 Operator palette，保留轻量 `TopologyContextRail`；
5. URL 直接保存 mode、exact target、scenario 和 run，Back/Forward 可恢复；
6. 保留旧 `workspaceView` deep link 的确定性迁移；
7. 在 1280、1024、820、390 做无 nested dialog 的浏览器门禁。
