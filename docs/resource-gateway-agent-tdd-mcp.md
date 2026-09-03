# 在 Codex 中使用 Resource Gateway Agent TDD MCP

本文是一份可直接照做的本地运营手册。目标是在 Codex Desktop、CLI 或 IDE 插件中，让 Agent 通过 MCP 完成能力发现、Tool 编排、业务用例提议、RED/GREEN 零外呼验证、人工 Oracle 审批、人工发布签署和不可变发布。

完整流程有两个人工停点。Agent 不能批准自己提出的业务 Oracle，也不能替人签署发布；这两步必须用独立的人工 reviewer 凭据在 Resource Gateway 看板中完成。服务端会校验 actor type、提议者与批准者分离，以及人实际打开的 proposal fingerprint。

## 1. 四条权限边界

| 边界 | 谁执行 | 能做什么 |
| --- | --- | --- |
| READ | Codex Agent | 查能力、契约、用例、证据和 readiness |
| AUTHORING | Codex Agent | 写草稿、Instruction、场景和依赖行为 |
| EXECUTION | Codex Agent | 做 RED、GREEN、baseline；真实外呼必须为 0 |
| GOVERNANCE | 人与 Agent 分权 | 人工 reviewer 批 Oracle、签署证据；Agent 只能在门禁通过后提交发布 |

这不是 Agent 直接调用生产 API 的入口。`rg.simulate` 和 `rg.tool.baseline` 即使使用已绑定的 API，依赖节点仍由用例 stub 替代；`realExternalCalls` 必须为 `0`。

## 2. 启动本地服务

前置条件：Java 25、Maven 3.9，以及本机 Maven 仓库中已安装的 BLOGE 依赖。在仓库根目录执行：

```bash
export RG_MCP_TOKEN='replace-with-a-local-token'
export RG_REVIEW_TOKEN='replace-with-a-different-reviewer-token'
export RG_INTEGRATION_DEMO_IDENTITY_ENABLED=true
export RG_INTEGRATION_DEMO_TOKEN="$RG_MCP_TOKEN"
export RG_INTEGRATION_DEMO_REVIEW_TOKEN="$RG_REVIEW_TOKEN"
export RG_INTEGRATION_ALLOWED_PURPOSES='AGENT_TDD_READ,AGENT_TDD_AUTHORING,AGENT_TDD_EXECUTION,AGENT_TDD_GOVERNANCE,CORRECTNESS_FIXTURE_MATERIAL_WRITE'

RESOURCE_GATEWAY_PORT=8081 ./scripts/start-examples.sh resource-gateway
./scripts/example-services.sh status resource-gateway
```

启动脚本会管理 PID、日志、端口和 readiness，并默认设置 `RG_INTEGRATION_ENVIRONMENT_ID=local`。这个值很重要：Agent TDD 执行在 `prod` 环境会失败关闭。需要覆盖时，启动前显式传入 `RG_INTEGRATION_ENVIRONMENT_ID=test` 或 `local`。

- MCP：`http://localhost:8081/mcp`
- 人工看板：`http://localhost:8081/agent-tdd.html`
- 日志：`target/example-logs/resource-gateway.log`

停止服务：

```bash
./scripts/stop-examples.sh resource-gateway
```

两个 token 必须不同。`RG_MCP_TOKEN` 映射到 `WORKLOAD` Agent；`RG_REVIEW_TOKEN` 映射到 `HUMAN` reviewer，且只允许 READ/GOVERNANCE。Demo identity 只能用于本机演示。生产环境必须关闭它并使用受信 JWT 或自定义身份解析器。外部 PostgreSQL 必须先应用 `db/postgresql/V20260903_020__agent_tdd_runtime.sql`；只有嵌入式 H2 会自动建 Agent TDD 表，外部库缺 migration 时启动失败关闭。

## 3. 把 MCP 配进 Codex

Codex Desktop、CLI 和 IDE 插件共用 MCP 配置。项目级配置放在仓库的 `.codex/config.toml`；个人级配置放在 `~/.codex/config.toml`。下例把同一个入口拆成四个最小权限 server，避免一个连接天然持有全部用途。

```toml
[mcp_servers.rg_read]
url = "http://localhost:8081/mcp"
bearer_token_env_var = "RG_MCP_TOKEN"
http_headers = { "X-Purpose" = "AGENT_TDD_READ" }
enabled_tools = [
  "rg.capability.list", "rg.library.get", "rg.library.list",
  "rg.contract.get", "rg.tool.getInstruction", "rg.scenario.listCases",
  "rg.verdict.get", "rg.evidence.get", "rg.dsl.preview",
  "rg.gate.check", "rg.readiness.get"
]
required = true
startup_timeout_sec = 10
tool_timeout_sec = 60

[mcp_servers.rg_author]
url = "http://localhost:8081/mcp"
bearer_token_env_var = "RG_MCP_TOKEN"
http_headers = { "X-Purpose" = "AGENT_TDD_AUTHORING" }
enabled_tools = [
  "rg.library.upsert", "rg.feature.compose", "rg.tool.compose",
  "rg.tool.setInstruction", "rg.scenario.upsertCases", "rg.oracle.propose",
  "rg.scenario.setDependencyBehavior", "rg.tool.publishSpec"
]
required = true
startup_timeout_sec = 10
tool_timeout_sec = 60

[mcp_servers.rg_execute]
url = "http://localhost:8081/mcp"
bearer_token_env_var = "RG_MCP_TOKEN"
http_headers = { "X-Purpose" = "AGENT_TDD_EXECUTION" }
enabled_tools = ["rg.simulate", "rg.feature.rehearse", "rg.tool.baseline"]
required = true
startup_timeout_sec = 10
tool_timeout_sec = 120

[mcp_servers.rg_govern]
url = "http://localhost:8081/mcp"
bearer_token_env_var = "RG_MCP_TOKEN"
http_headers = { "X-Purpose" = "AGENT_TDD_GOVERNANCE" }
enabled_tools = ["rg.fixture.promote", "rg.tool.publish"]
required = true
startup_timeout_sec = 10
tool_timeout_sec = 120
```

不要把 Bearer token 直接写进 TOML。确保启动 Codex 的进程能读取 `RG_MCP_TOKEN`，然后完全退出并重新打开 Codex。CLI 可从已经导出该变量的 Shell 启动；Desktop 应通过系统的安全环境注入方式把同名变量传给应用进程。

**绝对不要把 `RG_REVIEW_TOKEN` 传给 Codex、写进 `.codex/config.toml` 或粘进对话。** Codex 的 governance server 只暴露 `fixture.promote` 和门禁后的 `tool.publish`；Oracle 批准和 signoff 根本不是 MCP 工具，并且 `WORKLOAD` 身份直接请求 HTTP 审批也会被拒绝。

检查配置：

```bash
codex mcp list
codex mcp get rg_read
```

在 Codex 会话中输入 `/mcp`，应看到四个 server 均已连接。Resource Gateway 会与当前 Codex 协商 `2025-06-18` 生命周期；也兼容仓库定义的无状态 `2026-07-28` 请求和旧 `2025-11-25` initialize。Codex 会自动完成 `initialize` 和 `notifications/initialized`。

连接失败时先检查：

```bash
curl --fail http://localhost:8081/examples/gateway >/dev/null
test -n "${RG_MCP_TOKEN:-}" && echo 'RG_MCP_TOKEN is visible'
tail -80 target/example-logs/resource-gateway.log
```

## 4. 完整示例：编排余额查询 Tool

示例使用内置 `wallet-service.getBalance` API。闭环分三段提示词，中间由人完成两次评审。不要把三段合成“全自动发布”。

### 4.1 第一段：发现、编排、提出 GOLDEN

在新的 Codex 任务中粘贴：

```text
请使用 rg_read 和 rg_author MCP 完成下面工作，所有事实以 MCP 返回为准，不要猜测：

1. 调用 rg.capability.list，选择 runtimeState 可用于治理评审、effect=READ_EXTERNAL 的余额查询 API。
2. 调用 rg.contract.get 读取它的真实输入输出端口和 schema。
3. 创建 Tool `codex-wallet-ops-v1`。DSL 必须引用发现到的 bindingRef，不要直接写 httpResource：

graph codexWalletOps {
  input { userId: String }
  node wallet : "resource:wallet-service.getBalance" {
    input { params = { userId: ctx.userId } }
  }
  transform response {
    amount = wallet.output.payload.amount
    currency = wallet.output.payload.currency
  }
}

4. libraryRefs 显式传空数组。每个写操作使用独立、可读且本轮稳定的 idempotencyKey。
5. 设置完整 Instruction：name/title/description/whenToUse/inputs/outputs/errors 都不能缺失。
6. 创建 caseSet `codex-wallet-cases-v1`，提出 GOLDEN 行 `wallet-usd`：given.userId=u-100；wallet stub 返回 payload.amount=100、payload.currency=USD；expect 为 amount=100、currency=USD；oracleOwner=wallet-ops。
7. 调用 rg.scenario.listCases 确认该行等待人工 Oracle 审批，然后停止。不要执行 RED/GREEN，不要替人批准。

最后只汇报 toolRef、draft revision、caseSetRef、case revision、待办人工动作和稳定错误码，不展示业务 payload。
```

预期：Tool 草稿已保存；`honestVerdict` 只证明契约语法，业务正确性仍是 `NOT_PROVEN`；GOLDEN 尚不能进入 baseline。同一 idempotencyKey 携带不同内容会返回 `IDEMPOTENCY_CONFLICT`。

### 4.2 人工停点一：批准 Oracle

打开 `http://localhost:8081/agent-tdd.html`，输入 **`RG_REVIEW_TOKEN`**，找到 `codex-wallet-cases-v1 / wallet-usd`，点击“查看详情并批准”。浏览器会先用 HUMAN 身份读取精确 revision 的 `intent/given/stubs/expect/owner/proposedBy`，显示确认框；逐项核对后再批准。

看板列表仍是 `STRUCTURE_ONLY`；payload-bearing 详情只在人工治理端点按需读取，响应带 `no-store`，不会进入 MCP。批准同时绑定 `expectedRevision` 和详情的 `proposalFingerprint`；如果 Agent 在评审期间修改了用例，服务端会拒绝，必须刷新后重审。提议者与 reviewer 是同一 actor 时也会拒绝。

批准后，让 Agent 调用 `rg.scenario.listCases`，确认该行是 `ACTIVE`。

### 4.3 第二段：RED、GREEN 和发布前检查

继续在同一任务中粘贴：

```text
继续 `codex-wallet-ops-v1` 的 Agent TDD 流程：

1. 读取 `codex-wallet-cases-v1`，确认 `wallet-usd` 已 ACTIVE；否则停止。
2. 用 side=RED、cases.caseSetRef=codex-wallet-cases-v1 调用 rg.simulate。要求 verdict=RED_PASS 且 realExternalCalls=0。
3. RED 不满足时，只根据 diagnostics.code/target/line/column 修复草稿或用例，然后重跑；不要猜测隐藏 payload。
4. RED 通过后，用 side=GREEN、caseSetRef=codex-wallet-cases-v1、rounds=3 调用 rg.tool.baseline。要求 status=GO、businessFingerprintStable=true、realExternalCalls=0。
5. 调用 rg.verdict.get 和 rg.readiness.get。若还有非人工缺口，按 remainingLimitations 修复并重跑；若只剩 PUBLISH_SIGNOFF，停止等待人工签署。

最后只汇报 draftRevision、goldenSetId、evidenceFingerprint、baseline status、realExternalCalls 和下一项人工动作。不要调用 rg.tool.publish。
```

GREEN 表示“冻结的可执行绑定在批准用例和受控依赖下满足业务 Oracle”，不表示真实上游健康，也不产生真实外部请求。

### 4.4 人工停点二：签署发布证据

仍使用 **`RG_REVIEW_TOKEN`** 回到看板，找到 `codex-wallet-ops-v1` 的 `PUBLISH_SIGNOFF`。核对 `draftRevision`、`goldenSetId`、`evidenceFingerprint` 与 Agent 汇报完全一致。填写新的 `signoffRef`，例如 `wallet-ops-signoff-20260903-01`，再批准。

签署不可变；同一个 `signoffRef` 不能覆盖使用。草稿、ACTIVE 用例、Oracle、stub、binding 或目标实现任何一项变化，旧 GREEN 和旧签署都会失效。

### 4.5 第三段：最终发布

继续粘贴：

```text
请完成 `codex-wallet-ops-v1` 的受治理发布：

1. 调用 rg.readiness.get，确认 publishable=true，且当前 draftRevision、goldenSetId、evidenceFingerprint 与人工签署一致。
2. 若不一致或仍有 remainingLimitations，停止并列出稳定原因码。
3. 一致时调用 rg.tool.publish，signoffRef=`wallet-ops-signoff-20260903-01`，使用新的 idempotencyKey。
4. 再读取 readiness/verdict，汇报 publicationId、artifactKind、冻结 revision 和最终状态。

不得绕过门禁，不得调用真实业务 API，不得输出 token 或业务 payload。
```

预期 `artifactKind=EXECUTABLE`。发布物冻结通过门禁的 operator snapshot，不会在发布后静默跟随 catalog 漂移。

## 5. Agent 操作约束

### 5.1 DSL 与契约

- 先 `rg.capability.list`，再 `rg.contract.get`，最后写 DSL。
- API 节点使用发现到的 `bindingRef`，例如 `resource:wallet-service.getBalance`；不要绕过契约直接写 `httpResource`。
- `libraryRefs` 必须显式传入，空依赖也是 `[]`。
- BLOGE DSL 字段按换行分隔，不要仿 JSON 在字段末尾加逗号。
- Resource payload 使用 `node.output.payload.field`；命名端口会按目录解析。

### 5.2 用例与 Oracle

- `rg.simulate.cases` 必须二选一：只传 `caseSetRef`，或只传临时 `rows`。
- 只有持久 `caseSetRef` 中的 `ACTIVE` 行能推进 READY 并形成发布证据。
- 每个执行行必须有显式 `expect`。实现不能反过来修改 Oracle 以迎合结果。
- 执行后的并发用例修改会让 READY、evidence、verdict 和 line 一起回滚。

### 5.3 依赖行为

支持 `RETURN`、`ERROR`、`DELAY`、`TIMEOUT`、`REPLAY`、`OBSERVE`、`MUST_NOT_CALL`：

- `DELAY/TIMEOUT.afterMillis` 为 1–60000，使用逻辑时钟。
- `REPLAY` 要求精确 `bloge-replay:<id>@<revision>#sha256:<fingerprint>` 和冻结 `value`。
- `OBSERVE` 只允许本地确定性 delegate，并必须提供 `value`。
- `MUST_NOT_CALL` 节点一旦执行，整例失败。

决策表枚举支持 `== != < <= > >=`、数值范围、`in {...}` 和 `otherwise`。`per-rule` 需要 `oracleOwner`；不可解析谓词没有 `authorSamples` 时生成 `qualityState=BLOCKED`，不会猜样本。

### 5.4 证据和隐私

MCP diagnostics 只包含 `level/code/target/line/column`。底层异常文案、metadata、generated DSL、operator snapshot、fixture material 和业务响应体不会穿过 MCP 边界。不要把 token 或业务 payload 写进提示词、提交信息和日志。

## 6. 常见失败

| 现象或错误码 | 原因 | 处理 |
| --- | --- | --- |
| `/mcp` 未连接 | 服务未启动、token 对 Codex 不可见、配置未重载 | 查 status/log，完全重启 Codex，再看 `/mcp` |
| `/mcp` 已连接，但任务在调用工具前报告 Codex API/模型服务 404 | Codex 账户或模型后端不可用，不是 RG MCP 失败 | 先运行第 7 节协议探针；若返回 200，保留 RG 进程，更新或重新登录 Codex 后重试 |
| `UNAUTHENTICATED` / 401 | Bearer 缺失或身份解析失败 | 核对 client/server token；不要打印 token |
| `FORBIDDEN_PURPOSE` / 400 或 403 | purpose 缺失、非法或越权 | 使用四 server 配置并检查 allowed purposes |
| `GATE_REJECTED` / 503 | 身份/审计基础设施不可用，或生产 admission | 查日志；本地脚本应使用 environment `local` |
| `GOLDEN_REQUIRES_APPROVAL` | GOLDEN 未人工批准 | 看板批准精确 revision，再读 case set |
| `GATE_REJECTED` / 409（审批） | 使用了 Agent token、自批，或 proposal fingerprint 已变化 | 改用独立 reviewer token，重新打开详情并复核 |
| `SCHEMA_NONCONFORMANT` | binding、stub 或行为参数不匹配 | 重读 contract，按真实端口/schema 修复 |
| `LIBRARY_NOT_FOUND` | libraryRefs 或 runtime binding 不存在 | 显式依赖并重新 discovery |
| `SIM_REAL_CALL_DETECTED` | 非纯节点发生真实调用 | 立即停止；这是隔离缺陷 |
| `PUBLISH_GATE_NOT_MET` | GREEN、稳定性、签署或 fingerprint 过期 | 按 readiness.remainingLimitations 补证据 |
| JSON-RPC `-32602` | 参数不符合当前 inputSchema | 以最新 tools/list 修正 |
| JSON-RPC `-32603` | MCP 边界内未预期失败 | 查服务日志和 correlation ID |

鉴权发生在工具分派前：身份失败保留 401；purpose 缺失/非法/越权保留 400/403；身份提供方或审计不可用保留 503。响应只含稳定码和固定说明。

## 7. 协议探针

以下只用于诊断 server，不是日常 Agent 流程：

```bash
curl --fail-with-body http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${RG_MCP_TOKEN}" \
  -H 'X-Purpose: AGENT_TDD_READ' \
  -H 'MCP-Protocol-Version: 2025-06-18' \
  --data '{"jsonrpc":"2.0","id":"list-1","method":"tools/list","params":{}}'
```

成功响应同时提供 MCP `content` 和 `structuredContent`。服务端按公布的 `inputSchema` 校验参数、按 `outputSchema` 校验结果；不匹配时失败关闭且不回显被拒绝的数据。

## 8. 回归验证

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='AgentTddMcpOperationalWorkflowTest,DslImportServiceTest,GraphDraftDslGeneratorTest,ExampleServicesScriptTest' \
  test

mvn -f resource-gateway-examples/pom.xml clean verify
```

`AgentTddMcpOperationalWorkflowTest` 使用真实 Spring 服务、HTTP `/mcp`、Bearer/purpose 鉴权、`capability.list → contract.get` 动态 binding 发现、独立 WORKLOAD/HUMAN 凭据、人工详情与批准 HTTP、H2 持久化、零外呼 RED/GREEN、baseline 和发布服务贯穿余额查询。它不会访问真实上游，也不能替代生产身份提供方、真实 PostgreSQL 和发布责任人的验收证据。

## 9. 完成判据

1. Codex `/mcp` 显示四个最小权限 server 已连接。
2. API binding 来自 capability discovery 和 contract，而非 Agent 猜测。
3. GOLDEN Oracle 经不同 HUMAN actor 打开详情并批准；Codex 使用 WORKLOAD token，执行的是 ACTIVE 持久用例。
4. RED 通过；GREEN baseline 为 `GO` 且业务指纹稳定。
5. RED/GREEN 的 `realExternalCalls` 都是 `0`。
6. 人工签署精确绑定当前 revision、goldenSetId、evidenceFingerprint。
7. readiness 为 `publishable=true` 后才创建 `EXECUTABLE` 发布物。
8. 内容或 catalog 漂移会使旧证据/旧签署失效。

Codex 的 MCP 配置与管理方式以 [OpenAI Codex MCP 官方文档](https://learn.chatgpt.com/docs/extend/mcp) 为准；本文聚焦本仓库的工具、用途和治理顺序。
