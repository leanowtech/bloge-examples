# 在 Codex 中使用 Resource Gateway Agent TDD MCP

本文是一份可直接照做的本地运营手册。目标是在 Codex Desktop、CLI 或 IDE 插件中，让 Agent 通过 MCP 完成世界观与积木发现、资源登记、样例提供、Tool 编排、业务用例提议、RED/GREEN 零外呼验证、平台实景验证、人工 Oracle 审批、人工发布签署和不可变发布。

完整流程有两个人工停点。Agent 不能批准自己提出的业务 Oracle，也不能替人签署发布；这两步必须用独立的人工 reviewer 凭据在 Resource Gateway 看板中完成。服务端会校验 actor type、提议者与批准者分离，以及人实际打开的 proposal fingerprint。

## 1. 四条权限边界

| 边界 | 谁执行 | 能做什么 |
| --- | --- | --- |
| READ | Codex Agent | 查能力、契约、用例、证据和 readiness |
| AUTHORING | Codex Agent | 写草稿、Instruction、场景和依赖行为 |
| EXECUTION | Codex Agent | 做 RED、GREEN、baseline；真实外呼必须为 0 |
| GOVERNANCE | 人与 Agent 分权 | 人工 reviewer 批 Oracle、签署证据；Agent 只能在门禁通过后提交发布 |

这四条是 Codex 可见的权限边界。平台另有一个不进入 MCP 工具目录、也不发给 Codex 的内部用途 `AGENT_TDD_ATTEST`：逻辑 GREEN 持久化成功后，平台才用它自动运行只读沙箱实景验证。

这不是 Agent 直接调用生产 API 的入口。`rg.simulate` 和 `rg.tool.baseline` 的逻辑执行即使使用已绑定 API，也会以用例 stub 替代依赖；其 `realExternalCalls` 必须为 `0`。baseline 响应中的 `attestation.realExternalCalls` 属于随后由平台触发的独立实景运行，不能由 Agent 选择输入、资源或执行模式。

## 2. 启动本地服务

前置条件：Java 25、Maven 3.9，以及本机 Maven 仓库中已安装的 BLOGE 依赖。先在专门运行 Resource Gateway 的 **终端 A** 中执行；不要从这个终端启动 Codex：

```bash
printf 'Local Agent token: '
IFS= read -rs RG_AGENT_DEMO_TOKEN
printf '\nLocal reviewer token: '
IFS= read -rs RG_REVIEW_DEMO_TOKEN
printf '\n'

RG_INTEGRATION_DEMO_IDENTITY_ENABLED=true \
RG_INTEGRATION_DEMO_TOKEN="${RG_AGENT_DEMO_TOKEN}" \
RG_INTEGRATION_DEMO_REVIEW_TOKEN="${RG_REVIEW_DEMO_TOKEN}" \
RG_INTEGRATION_ALLOWED_PURPOSES='AGENT_TDD_READ,AGENT_TDD_AUTHORING,AGENT_TDD_EXECUTION,AGENT_TDD_GOVERNANCE' \
RG_AGENT_TDD_ATTEST_ALLOWED_HOSTS='localhost,127.0.0.1' \
RG_CORRECTNESS_AUTHORING_ENABLED=true \
RG_CORRECTNESS_FIXTURE_MATERIAL_ENABLED=true \
RESOURCE_GATEWAY_PORT=8081 \
./scripts/start-examples.sh resource-gateway

unset RG_AGENT_DEMO_TOKEN RG_REVIEW_DEMO_TOKEN

./scripts/example-services.sh status resource-gateway
```

启动脚本会管理 PID、日志、端口和 readiness，默认绑定 `127.0.0.1`，并设置 `RG_INTEGRATION_ENVIRONMENT_ID=local`。这个值很重要：Agent TDD 执行在 `prod` 环境会失败关闭。需要覆盖时，启动前显式传入 `RG_INTEGRATION_ENVIRONMENT_ID=test` 或 `local`。只有在已配置正式身份提供方、网络访问控制和 TLS 后，才可将 `RESOURCE_GATEWAY_ADDRESS` 设置为 `0.0.0.0`；启动脚本仍通过 loopback 做 readiness。直接运行 Spring Boot 时才使用 `SERVER_ADDRESS`。

上例显式开启 Correctness 元数据与 Fixture material，因为 `rg.fixture.provide` 需要把样例加密后存入受治理的 Fixture 仓库。首次启动时，脚本用 `openssl` 生成一个本机 AES-256 key，权限设为 `0600`，保存到 `target/example-secrets/resource-gateway-fixture-material.key`；后续启动复用它，并在每次读取前重新收紧为 `0600`。`target/example-secrets` 目录或 key 路径若是符号链接，或者不是预期的普通目录/文件，启动会失败关闭。脚本不会打印该 key，文件也位于 Git 忽略的 `target/` 下。若已由密钥管理系统注入 `RG_CORRECTNESS_FIXTURE_MATERIAL_ACTIVE_KEY_ID` 和 `RG_CORRECTNESS_FIXTURE_MATERIAL_KEY_RING`，脚本不会生成本地 key。生产环境不得使用这个本地演示 key。

`RG_AGENT_TDD_ATTEST_ALLOWED_HOSTS` 是精确主机名白名单，不接受通配符、URL 或域名后缀。资源声明和后续实景验证只允许 HTTP/HTTPS，且拒绝 user-info、可变主机模板和未配置主机。空值表示全部拒绝。

- MCP：`http://localhost:8081/mcp`
- 人工看板：`http://localhost:8081/agent-tdd.html`
- 日志：`target/example-logs/resource-gateway.log`

看板顶部的「第 1 幕 · 你的世界观与可用积木」并列显示平台基础积木和当前作用域内已导入的业务操作。业务操作标记为「草稿世界观 · 待用例检验」或「已接入」；业务类型只从已声明的输出 Schema 派生。该视图不读取 Fixture 或真实响应，HTTP 响应使用 `no-store`。

每个 Tool 卡片优先显示固定模板生成的业务流程摘要和决策规则表。规则表从决策节点的 `hitPolicy`、条件列、输出列、规则行与兜底行投影，不要求业务评审人阅读 DSL；算子引用和连线保留在「展开查看技术结构」中。卡片的覆盖区使用与场景枚举相同的谓词代表值算法，显示 ACTIVE GOLDEN 已覆盖组合、事实空间总数和最多 20 条盲区。

不透明谓词没有业务方代表值时，看板把对应列标为“代表值待补充”，`coverageComplete=false`，不会显示“覆盖周全”或用 `0 / 0` 冒充完整事实空间。业务方批准为 ACTIVE GOLDEN 的输入值会成为该列的 author sample，并立即进入同一套事实空间与盲区计算。没有决策表时则明确显示“当前无决策表事实空间”。Tool 卡片还显示实景验证状态、环境和真实调用总数。逻辑 GREEN 后平台自动验证；失败时只有 HUMAN/USER reviewer 能在看板确认“将访问已批准的只读沙箱资源”后重跑。该 HTTP 恢复入口不是 MCP 工具，WORKLOAD 身份即使知道地址也会被拒绝。

停止服务：

```bash
./scripts/stop-examples.sh resource-gateway
```

两个 token 必须不同。`RG_INTEGRATION_DEMO_TOKEN` 映射到 `WORKLOAD` Agent；`RG_INTEGRATION_DEMO_REVIEW_TOKEN` 映射到 `HUMAN` reviewer，且只允许 READ/GOVERNANCE。上面的 inline 环境只传给启动脚本，不会把 reviewer token 导出到父 Shell。Demo identity 只能用于本机演示。生产环境必须关闭它并使用受信 JWT 或自定义身份解析器。

Codex 只需要上表四个 `AGENT_TDD_*` 用途。Fixture material 的底层 `CORRECTNESS_FIXTURE_MATERIAL_WRITE` 由服务端在验证 `AGENT_TDD_GOVERNANCE` 后派生，不能加入 Codex token 的用途列表。外部 PostgreSQL 应由正式迁移工具依次应用仓库中所需的版本化 migration，至少包括 `V20260815_005` 至 `V20260816_010` 的 Correctness 表，以及 `V20260903_020__agent_tdd_runtime.sql`；应用不会在外部数据源上自行执行 DDL。嵌入式 H2 的本地启动器会校验并执行完整 authoring migration 集，checksum 漂移或缺表时失败关闭。

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
  "rg.library.upsert", "rg.resource.declare", "rg.feature.compose", "rg.tool.compose",
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
enabled_tools = ["rg.fixture.promote", "rg.fixture.provide", "rg.tool.publish"]
required = true
startup_timeout_sec = 10
tool_timeout_sec = 120
```

不要把 Bearer token 直接写进 TOML。在独立的 **终端 B** 中只注入 Agent token，再从这里启动 Codex CLI：

```bash
printf 'Local Agent token: '
IFS= read -rs RG_MCP_TOKEN
printf '\n'
export RG_MCP_TOKEN
unset RG_REVIEW_TOKEN RG_INTEGRATION_DEMO_REVIEW_TOKEN
codex
```

Codex Desktop 应通过系统的安全环境注入方式只获得同名 `RG_MCP_TOKEN`，然后完全退出并重新打开。不要从终端 A 启动 Desktop、CLI 或 IDE；也不要把 reviewer token 存进能被 Codex 进程继承的全局 Shell 配置。

**绝对不要把 reviewer token 传给 Codex、写进 `.codex/config.toml`、Shell 历史或粘进对话。** 人工 reviewer 只在浏览器看板的密码框中输入 reviewer token；该值只保存在当前页面内存。Codex 的 governance server 只暴露 Fixture 治理和门禁后的 `tool.publish`；Oracle 批准和 signoff 根本不是 MCP 工具，并且 `WORKLOAD` 身份直接请求 HTTP 审批也会被拒绝。

检查配置：

```bash
codex mcp list
codex mcp get rg_read
```

在 Codex 会话中输入 `/mcp`，应看到四个 server 均已连接。Resource Gateway 会与当前 Codex 协商 `2025-06-18` 生命周期；也兼容仓库定义的无状态 `2026-07-28` 请求和旧 `2025-11-25` initialize。Codex 会自动完成 `initialize` 和 `notifications/initialized`。

业务方直接给出典型数据时，使用 `rg.fixture.provide`，传入 `operatorRef`、精确 `outputPort`、`sampleValue`、分类、保留天数和幂等键。服务端先按该输出端口的 Schema 校验样本，再派生 Fixture ID、作用域、Schema 引用和 SAMPLE 来源。响应不返回 `sampleValue`。同一幂等键携带不同样本时，请求返回 `IDEMPOTENCY_CONFLICT`。

编排引用尚未登记的资源时，`rg.tool.compose` 返回 `RESOURCE_NOT_REGISTERED`，并在 payload-free 的 `error.details.missingResourceIds` 一次列出全部缺失资源 ID。先逐个调用 `rg.resource.declare`，提交 `resourceId`、`method`、`urlTemplate`、响应 `payloadSchema` 和幂等键，再重新编排。该工具只接受 `GET`、`HEAD` 和 `OPTIONS`，并同时写入资源描述符与可视化设计契约；目标主机必须命中 `RG_AGENT_TDD_ATTEST_ALLOWED_HOSTS` 的精确白名单。登记成功后，使用 `resource:<resourceId>` 作为库算子的 `runtime.bindingRef`。当前桥接不登记写操作；需要写外部系统时停止流程，先建立沙箱替身和对账方案。

连接失败时先检查：

```bash
curl --fail http://localhost:8081/examples/gateway >/dev/null
test -n "${RG_MCP_TOKEN:-}" && echo 'RG_MCP_TOKEN is visible'
tail -80 target/example-logs/resource-gateway.log
```

## 4. 完整示例：编排用户资料查询 Tool

示例使用内置 `user-service.getProfile` API。它与本地 demo upstream 的真实响应结构一致，因此可直接完成逻辑验证和实景验证。闭环分三段提示词，中间由人完成两次评审。不要把三段合成“全自动发布”。

### 4.1 第一段：发现、编排、提出 GOLDEN

在新的 Codex 任务中粘贴：

```text
请使用 rg_read 和 rg_author MCP 完成下面工作，所有事实以 MCP 返回为准，不要猜测：

1. 调用 rg.capability.list，选择 runtimeState 可用于治理评审、effect=READ_EXTERNAL 的用户资料查询 API `user-service.getProfile`。
2. 调用 rg.contract.get 读取它的真实输入输出端口和 schema。
3. 创建 Tool `codex-profile-ops-v1`。DSL 必须引用发现到的 bindingRef，不要直接写 httpResource：

graph codexProfileOps {
  input { userId: String }
  node profile : "resource:user-service.getProfile" {
    input { params = { userId: ctx.userId } }
  }
  transform response {
    name = profile.output.payload.name
    tier = profile.output.payload.tier
  }
}

4. libraryRefs 显式传空数组。每个写操作使用独立、可读且本轮稳定的 idempotencyKey。
5. 设置完整 Instruction：name/title/description/whenToUse/inputs/outputs/errors 都不能缺失。
6. 创建 caseSet `codex-profile-cases-v1`，提出 GOLDEN 行 `profile-premium`：given.userId=u-100；profile stub 返回 payload.userId=u-100、payload.name=Alice、payload.tier=premium；expect 为 name=Alice、tier=premium；oracleOwner=profile-ops。
7. 调用 rg.scenario.listCases 确认该行等待人工 Oracle 审批，然后停止。不要执行 RED/GREEN，不要替人批准。

最后只汇报 toolRef、draft revision、caseSetRef、case revision、待办人工动作和稳定错误码，不展示业务 payload。
```

预期：Tool 草稿已保存；`honestVerdict` 只证明契约语法，业务正确性仍是 `NOT_PROVEN`；GOLDEN 尚不能进入 baseline。同一 idempotencyKey 携带不同内容会返回 `IDEMPOTENCY_CONFLICT`。

### 4.2 人工停点一：批准 Oracle

打开 `http://localhost:8081/agent-tdd.html`，输入只由人工保管的 **reviewer token**，找到 `codex-profile-cases-v1 / profile-premium`，点击“查看详情并批准”。浏览器会先用 HUMAN 身份读取精确 revision 的 `intent/given/stubs/expect/owner/proposedBy`，显示确认框；逐项核对后再批准。

看板列表仍是 `STRUCTURE_ONLY`；payload-bearing 详情只在人工治理端点按需读取，响应带 `no-store`，不会进入 MCP。批准同时绑定 `expectedRevision` 和详情的 `proposalFingerprint`；如果 Agent 在评审期间修改了用例，服务端会拒绝，必须刷新后重审。提议者与 reviewer 是同一 actor 时也会拒绝。

批准后，让 Agent 调用 `rg.scenario.listCases`，确认该行是 `ACTIVE`。

### 4.3 第二段：RED、GREEN 和发布前检查

继续在同一任务中粘贴：

```text
继续 `codex-profile-ops-v1` 的 Agent TDD 流程：

1. 读取 `codex-profile-cases-v1`，确认 `profile-premium` 已 ACTIVE；否则停止。
2. 用 side=RED、cases.caseSetRef=codex-profile-cases-v1 调用 rg.simulate。要求 verdict=RED_PASS 且 realExternalCalls=0。
3. RED 不满足时，只根据 diagnostics.code/target/line/column 修复草稿或用例，然后重跑；不要猜测隐藏 payload。
4. RED 通过后，用 side=GREEN、caseSetRef=codex-profile-cases-v1、rounds=3 调用 rg.tool.baseline。要求 status=GO、businessFingerprintStable=true、realExternalCalls=0；平台会紧接着自动实景验证。
5. 检查 baseline.attestation：必须为 status=ATTESTED，environment 必须是 local/test/sandbox，所有 cases 的 oracleHeld/allDependenciesCalled 为 true。只汇报结构化计数和指纹，不索取或展示真实响应。
6. 调用 rg.verdict.get 和 rg.readiness.get。要求 gates.runtimeAttestation=true；若还有非人工缺口，按 remainingLimitations 修复逻辑 GREEN。实景验证为 FAILED 时停止，并请人工 reviewer 在看板确认后重跑；Codex 不调用恢复端点。若只剩 OWNER_SIGNOFF_ABSENT，停止等待人工签署。

最后只汇报 draftRevision、goldenSetId、evidenceFingerprint、baseline status、逻辑 realExternalCalls、attestation status/environment/realExternalCalls 和下一项人工动作。不要调用 rg.tool.publish。
```

GREEN 只表示“冻结的可执行绑定在批准用例和受控依赖下满足业务 Oracle”，自身不产生真实外部请求。`ATTESTED` 是另一份证据：平台把同一批 ACTIVE GOLDEN 的依赖换成当前冻结的真实只读 descriptor，在精确 host 白名单内执行，并再次核对 Oracle。生产 HTTP client 不跟随 3xx 重定向；重定向响应交给图逻辑处理，不会把已批准请求或认证头转发到第二个未批准主机。证据绑定 tool、draft revision、goldenSetId、case-set revision、契约、实现和 GREEN evidence fingerprint；任一项漂移都会使它失效。

实景证据只保存每个依赖进入 HTTP transport 的次数、每条用例是否成功/满足 Oracle、环境和稳定指纹。普通节点 attempt 不算真实调用；只有 descriptor 已通过执行期复核、渲染完成且请求交给 HTTP transport 时，才记录 `HTTP_TRANSPORT_DISPATCHED`。它不保存 URL、请求、响应、given、expect、异常消息或认证材料。相同 GREEN 的自动请求会先在独立数据库事务中提交持久 reservation，再离开事务执行真实读取，完成后用另一事务写入精确回放结果。并发重复请求不会再次外呼；首个请求完成后可精确回放。进程若在外呼期间退出，未完成 reservation 会保留，readiness 和看板投影 `RECOVERY_REQUIRED`，原因码为 `ATTESTATION_RECOVERY_REQUIRED`；系统绝不自动猜测重试。人工核对沙箱状态并在看板确认后，服务端先提交新的 attempt revision，再开始新的受控读取。即使上一次人工恢复进程退出，下一次人工确认也会获得新的 reservation key。

### 4.4 人工停点二：签署发布证据

仍使用人工保管的 **reviewer token** 回到看板，先确认实景验证为 `ATTESTED`，再找到 `codex-profile-ops-v1` 的 `PUBLISH_SIGNOFF`。核对 `draftRevision`、`goldenSetId`、`evidenceFingerprint`、`implementationFingerprint` 与 Agent 汇报完全一致。填写新的 `signoffRef`，例如 `profile-ops-signoff-20260904-01`，再批准。实景验证缺失、失败或过期时，看板不会提供发布签署待办。

签署不可变；同一个 `signoffRef` 不能覆盖使用。草稿、ACTIVE 用例、Oracle、stub、binding、目标实现或 resource descriptor 任何一项变化，旧 GREEN、实景证明或旧签署会在相应门禁失效。重新实景验证得到新的 `implementationFingerprint` 后，必须重新人工签署。

### 4.5 第三段：最终发布

继续粘贴：

```text
请完成 `codex-profile-ops-v1` 的受治理发布：

1. 调用 rg.readiness.get，确认 publishable=true，且当前 draftRevision、goldenSetId、evidenceFingerprint、attestation.implementationFingerprint 与人工签署一致。
2. 若不一致或仍有 remainingLimitations，停止并列出稳定原因码。
3. 一致时调用 rg.tool.publish，signoffRef=`profile-ops-signoff-20260904-01`，使用新的 idempotencyKey。
4. 再读取 readiness/verdict，汇报 publicationId、artifactKind、冻结 revision 和最终状态。

不得绕过门禁，不得自行调用真实业务 API 或实景恢复 HTTP 端点，不得输出 token 或业务 payload。
```

预期 `artifactKind=EXECUTABLE`。发布物冻结通过门禁的 operator snapshot，不会在发布后静默跟随 catalog 漂移。

## 5. Agent 操作约束

### 5.1 DSL 与契约

- 先 `rg.capability.list`，再 `rg.contract.get`，最后写 DSL。
- API 节点使用发现到的 `bindingRef`，例如 `resource:user-service.getProfile`；不要绕过契约直接写 `httpResource`。
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

### 5.5 实景验证边界

- 只接受 `local`、`test`、`sandbox` 环境；`prod` 失败关闭。
- 只执行 descriptor-backed 的 `READ_EXTERNAL`。HTTP `GET`、`HEAD`、`OPTIONS` 可进入验证；外部写一律返回 `WRITE_EFFECT_NOT_ALLOWED`。
- host 必须精确命中 `RG_AGENT_TDD_ATTEST_ALLOWED_HOSTS`。不支持通配符、后缀匹配、URL user-info、非 HTTP(S) 或 host 模板。
- catalog operator snapshot、resource descriptor 和 GREEN 身份分别冻结；HTTP 算子在发送前复核执行期 descriptor，注册表并发替换会让本次运行失败，不能把旧批准用于新地址。
- `realExternalCalls` 只统计通过全部发送前检查后进入 HTTP transport 的 `HTTP_TRANSPORT_DISPATCHED` 事件。节点开始、重试或 fallback 本身不算真实调用。
- 自动验证不在 MCP `tools/list` 中。异常重跑只能由 HUMAN/USER 在看板确认，服务端重新读取当前 GREEN，调用方不能提交 rows、binding 或 URL。

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
| `ATTESTATION_ENVIRONMENT_NOT_ALLOWED` | 环境不是 local/test/sandbox | 切换到受控沙箱；不能放宽到 prod |
| `WRITE_EFFECT_NOT_ALLOWED` / `EGRESS_NOT_ALLOWED` | 依赖是写操作、未登记或 host 未精确放行 | 建只读沙箱资源或修正精确白名单；不要让 Agent 绕过 |
| `ATTESTATION_EXECUTION_FAILED` / `ATTESTATION_ORACLE_MISMATCH` | 真实依赖执行失败，或真实结论违背已批准 Oracle | 人工查沙箱与契约；确认后从看板重跑 |
| `ATTESTATION_RECOVERY_REQUIRED` | 已存在未完成的持久实景 reservation，可能在进程退出前已经发出请求 | 禁止 Agent 自动重试；人工核对沙箱后从看板确认新 attempt |
| `ATTESTATION_STALE` | GREEN、draft、case set、binding 或 descriptor 在执行期间变化 | 重新运行逻辑 GREEN，生成当前证据 |
| `PUBLISH_GATE_NOT_MET` | GREEN、实景验证、签署或 fingerprint 缺失/过期 | 按 readiness.remainingLimitations 补证据 |
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
  -Dtest='AgentTddMcpOperationalWorkflowTest,AgentTddAttestationServiceTest,VisualOperatorFixtureSchemaSourceTest,LocalAuthoringSchemaBootstrapConfigurationTest,DatabaseAgentTddStateRepositoryPostgresCertificationTest,DslImportServiceTest,GraphDraftDslGeneratorTest,ExampleServicesScriptTest' \
  test

mvn -f resource-gateway-examples/pom.xml clean verify
```

`AgentTddMcpOperationalWorkflowTest` 使用真实 Spring 服务、HTTP `/mcp`、Bearer/purpose 鉴权、`capability.list → contract.get` 动态 binding 发现、独立 WORKLOAD/HUMAN 凭据、人工详情与批准 HTTP、H2 持久化、零外呼 RED/GREEN、平台自动实景读取、Oracle 复核、真实 Chrome 看板失败重跑和发布服务，贯穿用户资料查询。浏览器步骤在本机同时具备 Chrome 和兼容 Chromedriver 时执行；正式验收必须确认该测试 `skipped=0`，否则只能算后端覆盖。真实读取只访问同一测试进程内的 demo upstream，不访问外部业务系统。`AgentTddAttestationServiceTest` 覆盖平台身份、prod、写操作、host 白名单、transport dispatch 计数、进程丢失后的新人工 attempt 和 exact replay；`GatewayHttpClientRedirectPolicyTest` 证明生产 transport 不会跟随到第二主机；`HttpResourceOperatorTest` 证明 descriptor 在白名单校验后发生替换时不会发送请求，也不会产生 transport dispatch。`DatabaseAgentTddStateRepositoryTest` 证明未完成的外呼 reservation 跨 repository restart 仍失败关闭；`DatabaseAgentTddStateRepositoryPostgresCertificationTest` 会启动原生 PostgreSQL，验证 migration、并发 reservation、事务健康和 exact replay。这些测试不能替代生产身份提供方、生产数据库部署和发布责任人的验收证据。

## 9. 完成判据

1. Codex `/mcp` 显示四个最小权限 server 已连接。
2. API binding 来自 capability discovery 和 contract，而非 Agent 猜测。
3. GOLDEN Oracle 经不同 HUMAN actor 打开详情并批准；Codex 使用 WORKLOAD token，执行的是 ACTIVE 持久用例。
4. RED 通过；GREEN baseline 为 `GO` 且业务指纹稳定。
5. RED/GREEN 的 `realExternalCalls` 都是 `0`。
6. 平台自动实景验证为 `ATTESTED`；证据只含结构化调用计数、Oracle 布尔值、环境和指纹，不含业务载荷。
7. 人工签署精确绑定当前 revision、goldenSetId、evidenceFingerprint、implementationFingerprint。
8. readiness 的 `greenBaseline/runtimeAttestation/ownerSignoff` 全为 true 后，才创建 `EXECUTABLE` 发布物。
9. 内容、case set、catalog binding、实现或 descriptor 漂移会使旧实景证据/旧签署失效，或在发送前失败关闭。

Codex 的 MCP 配置与管理方式以 [OpenAI Codex MCP 官方文档](https://learn.chatgpt.com/docs/extend/mcp) 为准；本文聚焦本仓库的工具、用途和治理顺序。
