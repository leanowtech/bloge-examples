# API Resource / Fixture / Reusable Flow 实施台账

> 目标方案：`rg-api-fixture-reusable-flow-authoring-proposal-v1.md`。
>
> 状态：Paused for architecture review。当前只冻结待评审契约，不继续运行时或 UI 实现。
>
> 完成条件：所有目标切片有实现与对应范围的运行证据，最终差距严格小于 3%。

## 1. 评估方法

本台账按用户可观察能力加权，不按代码行数、提交数或既有内部组件数量计分。旧接口能够执行相似动作，
但仍要求前端理解 Descriptor、Contract、GraphDraft 或治理生命周期时，不视为新方案已完成。

| 目标能力 | 权重 |
| --- | ---: |
| 七类 Wire Schema family 与安全/CAS/Scope 协议 | 10% |
| API Resource 后端权威、复合保存与投影闭包 | 18% |
| API Resource 对象页、Default Fixture 与首次模拟 | 12% |
| Reusable Flow 后端、单一 Mapping、发布版本与 Catalog | 18% |
| Tool / Solution 对象页与 DAG 创作 | 10% |
| FixtureSet、Whole-flow Fixture、`APPLY_CASE` 与共享治理 | 12% |
| 稳定 SimulationModule、Trace、Egress 与四维 Verdict | 8% |
| 兼容迁移、入口切换和旧数据修复 | 5% |
| 文档、全量门禁、真实浏览器与可操作性验收 | 7% |
| **合计** | **100%** |

每轮只在证据与目标范围相同且最新实现已复验时增加完成度。聚焦测试不能证明全量门禁，静态 Schema 不能
证明 Controller、持久化或真实用户任务。

## 2. Iteration 1 — Schema 冻结

日期：2026-08-30。

### 已完成的契约原型

- `74f2bb937`：提交目标方案与可编辑架构图。
- `eaf745010`：提交 Authoring JSON Schema families、golden positive/negative examples 与协议测试。
- `3c1824962`：关闭 `SECRET_REF` URI 和敏感 Header 大小写绕过。
- 补充按 Exact Subject 查询 metadata-only `FixtureSetSummary` 的协议边界；禁止把 Input、Material、Replay
  Payload 或 Credential 放入列表响应。

评审稿在原型之后又冻结了 Publish/Share Wire Contract、Example 到 Case 的回执映射、
Fixture 状态可运行性、共享 Header Policy 和可审计 Egress Evidence。因此下述绿色测试只证明
已提交原型的自一致性，不证明最新评审稿已完全转成 JSON Schema。

### 最新验证

```text
mvn -f resource-gateway-examples/pom.xml -Dtest=AuthoringProtocolSchemaTest test
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

协调者在 `3c1824962` 后独立重跑，时间为 2026-08-30 07:44 +08。Maven 仍报告既有
`bloge-durable` / `bloge-test` POM warning；它没有使本次测试失败，也不是新方案完成证据。

### 差距评估

| 目标能力 | 已证明 |
| --- | ---: |
| Wire Schema family | 6 / 10（原型已验证，待评审后对齐新契约） |
| 文档与当前聚焦门禁 | 3 / 7 |
| 其余运行与 UI 能力 | 0 / 83 |
| **当前完成度** | **9%** |
| **当前差距** | **91%** |

不能把现有 Descriptor、GraphDraft、Fixture Catalog 或 Simulation Kernel 直接计为新方案完成；它们目前只是
待新深模块复用的 Adapter 候选。

### 评审通过后的候选计划

1. 以 `ApiResourceModule` 和 `AuthoringFacade` 为公共 seam，先完成一个 API Resource 的复合保存行为测试。
2. 复用现有 `WritableResourceRegistry`、`ResourceDesignContractRegistry` 和
   `ResourceVirtualOperatorProjector`，但只允许 Facade 写入三份投影。
3. 保存成功必须返回精确 Resource、Default Fixture 与全部 `READY` Projection Receipt；任一失败不得暴露
   新 revision。
4. Controller 实现标准 `If-None-Match` / 强 `If-Match`、Idempotency-Key、Problem Detail 与同 Scope 404。
5. 聚焦服务/Controller 测试 GREEN 后独立提交，再实现前端对象页；不同时修改 `AuthorCanvas`。

上述内容只用于评估实施可行性；在 R1-R5 和 Schema 对照完成评审前不启动。

## 3. 未关闭风险

- JSON Schema 测试使用仓库内轻量语义校验器；运行时 DAG 环、Schema 路径兼容、Fixture Target 与
  `APPLY_CASE` 约束仍必须由 Java 模块测试证明。
- V1 staging/commit 必须适配当前存储现实；若无法保证不可见 staging，不得用异步投影冒充成功 Receipt。
- API Resource 页面重载必须通过 Exact Subject Fixture summary 查询恢复，不能保存 material 到 Resource View
  或 Local Storage。
- 评审稿新增的 Publish/Share、Header Policy、Fixture 状态矩阵和 Egress Evidence 尚未对齐到可执行
  JSON Schema；评审通过前不继续实现。
- 共享工作树中的 `GraphNodeFixtureControls.tsx` 修改不属于本目标提交，必须继续隔离。
