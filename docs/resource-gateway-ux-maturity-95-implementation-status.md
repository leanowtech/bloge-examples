# Resource Gateway UX 95 分实施状态

> 状态：In Progress
>
> 基准计划：[Resource Gateway 体验成熟度 95 分校准与修正计划](resource-gateway-ux-maturity-95-recalibration-plan.md)
>
> 当前实施轮次：Stage A / 可信度止血
>
> 评分纪律：代码完成只能获得 E1；真实服务和浏览器验证后最多获得 E2。没有 E3 目标用户
> 证据时，不宣称体验成熟度达到 95 分。

## 1. 总览

| 阶段 | 目标分 | 状态 | 当前证据 |
|---|---:|---|---|
| 基线 | 74 | 已确认 | 2026-07-31 真实浏览器复评 |
| Stage A：可信度止血 | 80 | 进行中 | A01–A04 第一条可信纵切已实现，E1 |
| Stage B：唯一中央工作面 | 85 | 未开始 | 无 |
| Stage C：统一 Scenario | 90 | 未开始 | 无 |
| Stage D：复杂图可读 | 93 | 未开始 | 无 |
| Stage E：生命周期闭环 | 95 工程就绪 | 未开始 | 无 |
| Stage F：E3/E4 | 95–100 | 未开始 | 无 |

当前不能重新评分为 80。Stage A 的示例清理、readiness 文案和真实浏览器 Graph /
Operator / Function 三条纵切尚未完成。

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

## 3. Stage A 剩余差距

| ID | 剩余工作 | 完成证据 |
|---|---|---|
| UX95R-A05 | 三个 Graph 与三个 Library 示例清除非教学 warning | packaged 服务中的示例预览均为 0 非教学 warning |
| UX95R-A06 | Library preview 也按 `code + target + rootCause` 聚合 | 组件测试与真实预览 occurrence count |
| UX95R-A07 | `DESIGN_READY` 与 runtime binding 投影为一句人类结论 | 0/5 bound 时明确说明可设计、不可执行及下一步 |
| 浏览器门禁 | Graph、Operator、Function 三条纵切 | 真实浏览器交互、console、截图和请求对账 |
| 完整回归 | frontend + Maven | `npm test`、`npm run build`、`mvn clean verify` 全绿 |

## 4. 下一轮实施

下一轮先完成 A05–A07：

1. 把示例中的 `any` 端口替换为具名业务类型，消除重复 opaque-schema warning；
2. Library Contract Preview 聚合重复诊断并显示发生次数；
3. 建立统一 `ReadinessPresentation`，把 design readiness、runtime binding 和 production
   readiness 翻译成一个结论和一个下一步；
4. 增加三个内置 Library 示例的 server compiler golden test；
5. 启动 packaged 服务，用真实浏览器完成 Graph、Operator、Function 纵切；
6. 根据 E2 证据复评 Stage A 分数，再决定是否进入 Stage B。
