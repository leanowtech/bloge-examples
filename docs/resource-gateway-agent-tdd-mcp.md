# Resource Gateway Agent TDD MCP 使用说明

本文说明 Resource Gateway 1.4 的 Agent TDD 接口、身份用途、红绿验证、人工评审和发布门禁。适用于本地验证、MCP 客户端接入和故障排查。

## 1. 启动服务

前置条件：Java 25、Maven 3.9，以及本机 Maven 仓库中已安装的 BLOGE 依赖。

```bash
export RG_INTEGRATION_DEMO_IDENTITY_ENABLED=true
export RG_INTEGRATION_DEMO_TOKEN='replace-with-a-local-token'
export RG_INTEGRATION_ALLOWED_PURPOSES='AGENT_TDD_READ,AGENT_TDD_AUTHORING,AGENT_TDD_EXECUTION,AGENT_TDD_GOVERNANCE,CORRECTNESS_FIXTURE_MATERIAL_WRITE'
RESOURCE_GATEWAY_PORT=8081 ./scripts/start-examples.sh resource-gateway
./scripts/example-services.sh status resource-gateway
```

`scripts/start-examples.sh` 管理 PID、日志、端口和 readiness。Agent TDD overlay 表由应用在同一数据源中幂等创建；PostgreSQL 外部环境仍须由发布方按数据库变更流程审查和部署 schema。不要在生产环境启用 demo identity。

停止服务：

```bash
./scripts/stop-examples.sh resource-gateway
```

## 2. MCP 传输与认证

MCP 入口为 `POST /mcp`。现代无状态请求使用协议版本 `2026-07-28`，并在每次请求中发送以下字段：

| Header | 要求 | 说明 |
| --- | --- | --- |
| `Authorization` | 必填 | `Bearer <credential>`；身份、租户和环境由服务端解析 |
| `X-Purpose` | 必填 | 必须与工具影响级别匹配 |
| `MCP-Protocol-Version` | 建议 | `2026-07-28` |
| `Mcp-Method` | 使用现代路由时必填 | 与 JSON-RPC `method` 完全一致 |
| `Mcp-Name` | 调用工具时必填 | 与 `params.name` 完全一致 |
| `X-Correlation-Id` | 可选 | 调用方提供的有界关联 ID；缺省时由服务端生成 |

用途映射：

| 工具影响 | `X-Purpose` |
| --- | --- |
| READ | `AGENT_TDD_READ` |
| DRAFT_WRITE、PROPOSE | `AGENT_TDD_AUTHORING` |
| EXECUTE | `AGENT_TDD_EXECUTION` |
| GOVERNED_WRITE、Web 人工批准 | `AGENT_TDD_GOVERNANCE` |

发现工具：

```bash
curl --fail-with-body http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer replace-with-a-local-token' \
  -H 'X-Purpose: AGENT_TDD_READ' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: tools/list' \
  --data '{"jsonrpc":"2.0","id":"list-1","method":"tools/list","params":{}}'
```

响应同时提供 MCP `content` 和机器可读的 `structuredContent`。应用错误使用统一的 `ok:false` 信封，不包含上游响应体或 fixture 载荷。

## 3. 五阶段工具

1. 使用 `rg.capability.list`、`rg.library.get/list`、`rg.contract.get`、`rg.tool.getInstruction`、`rg.scenario.listCases`、`rg.verdict.get` 和 `rg.evidence.get` 查询状态。
2. 使用 `rg.library.upsert`、`rg.feature.compose`、`rg.tool.compose`、`rg.tool.setInstruction` 和场景工具修改草稿。写操作必须带 `idempotencyKey`；同一 key 携带不同请求时返回 `IDEMPOTENCY_CONFLICT`。
3. 使用 `rg.dsl.preview` 和 `rg.gate.check` 编译。`libraryRefs` 必须显式提供，编译器不会从全局目录猜测依赖。
4. 使用 `rg.simulate`、`rg.feature.rehearse` 和 `rg.tool.baseline` 验证。RED 在模拟边界真实执行纯逻辑节点，并用 stand-in 替换 operator 调用；所有 binding 就绪后，GREEN 在同一零外呼边界验证可执行图与纯业务逻辑，依赖节点使用已批准用例行为。两侧 `realExternalCalls` 都必须为 `0`。
5. 使用 `rg.fixture.promote`、`rg.tool.publishSpec`、`rg.readiness.get` 和 `rg.tool.publish` 完成治理与发布。

GOLDEN 行由 Agent 提议。业务负责人批准后，行状态才从 `DRAFT` 变为 `ACTIVE`，并成为 Tool 示例和 baseline 输入。Tool 契约变化时，既有 `ACTIVE` 行自动变为 `STALE`，旧的绿色证据不能继续通过发布门禁。

依赖桩行为统一编译到现有测试执行内核，不会回退到真实依赖：`RETURN` 返回固定值；`ERROR` 注入稳定错误；`DELAY` 和 `TIMEOUT` 使用 `afterMillis`（1–60000）及逻辑时钟；`REPLAY` 要求精确的 `bloge-replay:<id>@<revision>#sha256:<fingerprint>` 和本轮冻结的 `value`；`OBSERVE` 只观察本地确定性 delegate，必须提供 `value`；`MUST_NOT_CALL` 在节点被调用时失败。REPLAY 的内联值只用于零外呼模拟，证据保持非认证状态。

决策表枚举支持 `== != < <= > >=`、数值范围、`in {...}` 和 `otherwise`。`per-rule` 需要 `oracleOwner`，每条规则生成一个 GOLDEN 代表行，期望取自规则结论并自动进入人工批准提议；其余邻域值生成 BOUNDARY 行。不可解析谓词从 `authorSamples.<inputName>` 取确定性样本；没有样本时生成 `qualityState=BLOCKED` 的行。`combinatorial` 按字段和值排序后计算笛卡尔积，超过 `maxCases` 失败关闭。

`rg.simulate` 和 `rg.feature.rehearse` 可在 `cases.caseSetRef` 中引用已保存的用例集，也可在 `cases.rows` 中传入临时行；服务端只执行引用用例集中的 `ACTIVE` 行，且每个执行行必须包含显式 `expect` Oracle。`rg.tool.baseline` 使用顶层 `caseSetRef`，忽略调用方附带的内联行，只运行该持久化用例集中的 `ACTIVE` 行。每次不同的执行结果都生成内容寻址的 `evidenceRef`，因此同一红绿线的失败与修复证据会分别保留；完全相同的结果仍保持确定性引用。

## 4. bindingRef 与 goldenSetId

外部算子没有 `runtime.bindingRef` 时，Tool 状态为 `SPECCING`。此状态允许红侧模拟和 `rg.tool.publishSpec`，禁止绿侧发布。

`runtime.bindingRef` 必须解析到当前服务端 operator catalog。Resource Gateway 校验绑定目标的输入、输出端口数量和 JSON Schema；不匹配时返回 `SCHEMA_NONCONFORMANT`。绑定成功后，图快照使用目标的可执行 lowering，但继续保留库契约身份。

`goldenSetId` 由 Tool 引用、Tool/算子契约指纹和排序后的 ACTIVE case ID 计算。实现绑定不参与契约指纹，因此红侧和绿侧保持同一身份；I/O 契约或用例集合变化时生成新身份。

GREEN 用同一批 ACTIVE 用例和同一 `goldenSetId`，但只有在全部 `bindingRef` 已解析后才能运行。EXECUTE 权限不会调用这些真实 binding；外部依赖继续由用例中的受控行为替换，因此 `realExternalCalls=0` 是硬约束。GREEN 证明“绑定齐全的可执行图在受控依赖下符合业务 Oracle”，不证明真实集成健康或生产环境治理；后两项仍在诚实结论中标为未证明。

`rg.fixture.promote` 必须显式指定 `outputPort`。返回的 `sourceKind` 由服务端捕获证据派生：存在与当前草稿、节点、算子和输出一致的有效模拟捕获时为 `SCENARIO`，否则为 `SAMPLE`；客户端不能自行声明该来源。

## 5. 人工评审看板

打开 `http://localhost:8081/agent-tdd.html`。页面只读取 `STRUCTURE_ONLY` 投影，以输入输出字段、节点步骤、连线计数和场景生命周期表呈现 DSL 与用例结构；不显示 `given`、`expect`、fixture 或运行输出值。访问凭据只保存在当前页面的 JavaScript 内存中。

看板提供以下受治理操作：

- 批准 GOLDEN Oracle：`POST /api/agent-tdd/reviews/oracles/{caseSetRef}/{caseId}/approve`。
- 批准规格发布提议：`POST /api/agent-tdd/reviews/specs/{toolRef}/approve`。
- 签署可执行 Tool：`POST /api/agent-tdd/reviews/tools/{toolRef}/signoffs/{signoffRef}/approve`，请求体必须携带 `{draftRevision, goldenSetId, evidenceFingerprint}`。

Oracle 和规格批准请求必须携带人工实际查看的 `expectedRevision`。revision 已变化时，原子 revision fence 拒绝批准；重新读取后再评审。签署记录精确绑定 Tool 草稿 revision、goldenSetId 和 GREEN evidenceFingerprint，任一内容变化都会使旧签署失效。

所有 MCP 写工具通过状态库的 `executeOnce` 边界执行：服务端先原子占用 `{scope, operation, idempotencyKey}`，业务写与成功响应在同一事务内完成，再开放精确重放。相同键与相同请求只执行一次；不同请求材料或进行中的冲突失败关闭。内存实现也以同一临界区覆盖业务动作与响应记录。

## 6. 发布门禁

`rg.tool.publish` 只在以下条件全部满足时创建既有 `VisualGraphPublication` 不可变发布物：

- 所有库算子均有可解析、schema 相容的 `runtime.bindingRef`。
- 最新 baseline 为 `GO`，并与当前 ACTIVE 用例计算出的 `goldenSetId` 相同。
- 最新 baseline 明确来自 `GREEN` 侧；`RED` baseline 即使稳定通过也不能解锁发布。
- baseline 的 `draftRevision`、`evidenceFingerprint` 必须与当前草稿、ACTIVE 用例内容、Oracle、stub 和 binding 指纹一致。
- `signoffRef` 必须精确批准同一份 baseline；旧版本签署不能复用。
- 既有 Visual Graph validator、lowering 和 BLOGE compiler 允许发布可执行制品。

任一条件不满足时，调用返回 `PUBLISH_GATE_NOT_MET` 或更具体的稳定错误码，不创建发布物。

Feature/Tool 草稿按租户和环境闭合。若其他作用域已经占用同一个草稿引用，compose 返回 `DRAFT_NOT_FOUND`，不会读取或覆盖该草稿。

## 7. 验证与排查

定向测试：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='com.leanowtech.bloge.gateway.agenttdd.*Test,GraphNodeFixturePromotionServiceTest' test
```

最终验证：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

排查顺序：

1. 检查响应的稳定错误码和 `X-Correlation-Id`。
2. 检查 Bearer credential 是否由当前 identity provider 接受。
3. 检查 `X-Purpose` 是否与工具影响级别匹配，并包含在 `RG_INTEGRATION_ALLOWED_PURPOSES` 中。
4. 对 `LIBRARY_NOT_FOUND`，检查显式 `libraryRefs` 和 `runtime.bindingRef` 是否存在于当前 catalog。
5. 对 `GOLDEN_REQUIRES_APPROVAL`，在看板批准目标 revision 后重新读取 case set。
6. 对 `PUBLISH_GATE_NOT_MET`，调用 `rg.readiness.get`，按 `remainingLimitations` 逐项处理。
7. 对依赖行为的 `SCHEMA_NONCONFORMANT`，检查 `afterMillis`、精确 `replayRef`、REPLAY/OBSERVE 的冻结 `value`。

排查期间不要记录 Bearer credential、fixture material 或业务响应体。
