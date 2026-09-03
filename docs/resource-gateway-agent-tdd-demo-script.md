# Resource Gateway Agent TDD 演示导演脚本

本文用于完成一次 12～15 分钟的现场演示。演示者在 Codex 中通过 MCP 发现业务能力、编排 Tool、提出 GOLDEN、完成 RED/GREEN、查看平台实景验证，并在两个人工停点后发布不可变产物。

完整配置、字段说明和故障排查见 [在 Codex 中使用 Resource Gateway Agent TDD MCP](resource-gateway-agent-tdd-mcp.md)。本文只保留现场需要执行和讲解的内容。

## 1. 演示目标

演示结束时，观众应看到以下事实：

1. Codex 先从 Resource Gateway 发现 API 和契约，不猜测 binding 或 Schema。
2. Agent 可以编排 Tool 和提出业务用例，但不能批准自己的 Oracle。
3. RED/GREEN 逻辑验证的 `realExternalCalls` 为 `0`。
4. 逻辑 GREEN 后，平台自动执行受控的只读沙箱实景验证。
5. 人工签署绑定当前 revision、GOLDEN、逻辑证据和实现证据。
6. 所有门禁通过后，系统才创建 `EXECUTABLE` 发布物。

演示不是“Agent 自动操作生产系统”。真实外部写不在本流程中；实景验证只允许已登记、白名单内的只读沙箱资源。

## 2. 角色与屏幕

| 角色 | 使用界面 | 凭据 | 职责 |
| --- | --- | --- | --- |
| 演示者 / Agent 操作者 | Codex | WORKLOAD Agent token | 发现、编排、提议、RED/GREEN、读取证据、提交发布 |
| 业务评审人 | 浏览器看板 | HUMAN reviewer token | 查看业务详情、批准 Oracle、签署发布证据 |
| Resource Gateway | 终端 A | 启动时接收两类 token | 鉴权、持久化、执行门禁、实景验证和发布 |

推荐同时展示三个区域：

- 左侧：Codex 任务。
- 右上：`http://localhost:8081/agent-tdd.html`。
- 右下：终端 A，显示服务状态和日志；不要展示 token。

Reviewer token 只能由业务评审人在浏览器中输入。不要把 reviewer token 传给 Codex、写入配置文件、粘贴进对话或投屏展示。

## 3. 演示前准备

### 3.1 启动 Resource Gateway

在仓库根目录打开专用的终端 A。不要从终端 A 启动 Codex。

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

预期结果：

```text
Resource Gateway: running
```

启动脚本默认只监听 `127.0.0.1`。现场演示不要改成 `0.0.0.0`。

### 3.2 检查 Codex MCP

Codex 的项目级 MCP 配置使用仓库 `.codex/config.toml`。配置内容和 Agent token 注入步骤见完整操作手册第 3 节。

在只持有 Agent token 的终端 B 中检查：

```bash
codex mcp list
codex mcp get rg_read
```

在 Codex 中输入：

```text
/mcp
```

预期结果：`rg_read`、`rg_author`、`rg_execute`、`rg_govern` 均已连接。若未连接，不要开始正式演示。

### 3.3 打开人工看板

在普通浏览器中打开：

```text
http://localhost:8081/agent-tdd.html
```

此时先不要输入 reviewer token。保留页面，等待第一个人工停点。

## 4. 现场导演脚本

### 第 0 幕：说明边界（约 1 分钟）

演示者讲解：

> 这次演示不是让 Agent 直接调用生产 API。Agent 负责发现、编排、提出业务用例和执行零外呼验证。业务 Oracle 与发布签署分别由人确认。逻辑 GREEN 后，只有平台内部身份能运行只读沙箱实景验证。

屏幕展示：Codex 的 `/mcp` 连接列表，以及看板空闲页面。

成功信号：四个最小权限 MCP server 均连接。

### 第 1 幕：发现、编排和提出 GOLDEN（约 3 分钟）

在新的 Codex 任务中粘贴以下提示词：

```text
请使用 rg_read 和 rg_author MCP 完成一次可演示的 Agent TDD 编排。所有 API、binding、端口和 Schema 都必须来自 MCP 返回，不要猜测。

演示对象：
- toolRef: demo-profile-ops-v1
- caseSetRef: demo-profile-cases-v1

执行步骤：
1. 调用 rg.capability.list，找到 effect=READ_EXTERNAL 的 `user-service.getProfile`。
2. 调用 rg.contract.get，读取它的真实 bindingRef、输入端口、输出端口和 Schema。
3. 创建 Tool `demo-profile-ops-v1`，libraryRefs 显式传空数组。DSL 使用发现到的 bindingRef：

graph demoProfileOps {
  input { userId: String }
  node profile : "resource:user-service.getProfile" {
    input { params = { userId: ctx.userId } }
  }
  transform response {
    name = profile.output.payload.name
    tier = profile.output.payload.tier
  }
}

4. 设置完整 Instruction，包含 name/title/description/whenToUse/inputs/outputs/errors。
5. 创建 `demo-profile-cases-v1`，提出一条 GOLDEN：
   - caseId: profile-premium
   - given.userId: u-100
   - profile stub payload: userId=u-100, name=Alice, tier=premium
   - expect: name=Alice, tier=premium
   - intent: 已批准的用户资料查询应返回业务方确认的姓名和等级
   - oracleOwner: profile-ops
6. 每个写操作使用新的、可读的 idempotencyKey。
7. 调用 rg.scenario.listCases，确认用例等待人工审批，然后停止。

不要执行 RED/GREEN，不要批准 Oracle，不要调用发布。最后只汇报 toolRef、draft revision、caseSetRef、case revision、当前 lifecycle 和下一项人工动作。
```

演示者讲解：

> Agent 不是从提示词中获得接口结构。它先查询 Resource Gateway 的能力目录和契约，再把发现到的 binding 写入图。此时业务用例只是提案，还不能形成发布证据。

成功信号：

- Codex 显示 `demo-profile-ops-v1` 和 `demo-profile-cases-v1`。
- `profile-premium` 仍等待人工 Oracle 审批。
- Codex 停止执行，没有调用 RED、GREEN 或发布。

### 第 2 幕：人工批准 Oracle（约 2 分钟）

业务评审人执行：

1. 在看板输入 reviewer token。
2. 选择“加载”。
3. 找到 `demo-profile-cases-v1 / profile-premium`。
4. 选择“查看详情并批准”。
5. 核对 `intent`、`given`、`stubs`、`expect`、`oracleOwner` 和 `proposedBy`。
6. 确认浏览器提示。

演示者讲解：

> 看板先按精确 revision 读取业务详情。批准动作同时绑定 proposal fingerprint。如果 Agent 在评审期间修改用例，批准会失败，评审人必须重新打开并核对。

成功信号：看板中的待审 Oracle 数量减少；Codex 再调用 `rg.scenario.listCases` 后看到 `lifecycle=ACTIVE`。

不要让 Agent 代替人工调用审批 HTTP 接口。WORKLOAD 身份即使知道地址也会被拒绝。

### 第 3 幕：RED、GREEN 和平台实景验证（约 3 分钟）

继续在同一 Codex 任务中粘贴：

```text
继续 `demo-profile-ops-v1` 的 Agent TDD 流程：

1. 调用 rg.scenario.listCases，确认 `profile-premium` 为 ACTIVE；否则停止。
2. 调用 rg.simulate：side=RED，cases.caseSetRef=demo-profile-cases-v1，libraryRefs=[]。
3. 要求 RED verdict=RED_PASS，且 realExternalCalls=0；不满足时只依据结构化 diagnostics 修复。
4. RED 通过后调用 rg.tool.baseline：toolRef=demo-profile-ops-v1，caseSetRef=demo-profile-cases-v1，side=GREEN，rounds=3，libraryRefs=[]。
5. 要求 status=GO、businessFingerprintStable=true、realExternalCalls=0。
6. 检查 baseline.attestation，要求 status=ATTESTED、environment 为 local/test/sandbox、所有 case 的 oracleHeld=true、所有依赖的 allDependenciesCalled=true。
7. 调用 rg.verdict.get 和 rg.readiness.get。
8. 如果只剩 OWNER_SIGNOFF_ABSENT，停止等待人工签署。不要调用 rg.tool.publish。

最后只汇报：RED verdict、逻辑 realExternalCalls、baseline status、draftRevision、goldenSetId、evidenceFingerprint、attestation status/environment/realExternalCalls、implementationFingerprint 和下一项人工动作。不要展示业务 payload。
```

演示者按以下顺序指出结果：

1. `RED_PASS` 证明测试能够识别未实现状态。
2. GREEN baseline 为 `GO`，并且业务指纹在多轮执行中稳定。
3. RED/GREEN 的 `realExternalCalls=0`，说明 Agent 验证没有外呼。
4. `attestation.status=ATTESTED` 是平台随后生成的独立实景证据。
5. `readiness.publishable=false` 是预期状态，因为还缺人工签署。

如果 attestation 为 `FAILED` 或 `RECOVERY_REQUIRED`，不要修改结果或自动重试。跳到第 6 节的失败演示话术。

### 第 4 幕：人工签署发布证据（约 2 分钟）

业务评审人在看板执行：

1. 找到 `demo-profile-ops-v1`。
2. 核对实景验证状态为 `ATTESTED`。
3. 打开 `PUBLISH_SIGNOFF`。
4. 将看板中的 `draftRevision`、`goldenSetId`、`evidenceFingerprint`、`implementationFingerprint` 与 Codex 汇报逐项比对。
5. 输入 `signoffRef=demo-profile-signoff-v1`。
6. 批准签署。

演示者讲解：

> 这不是对 Tool 名称做一次永久授权。签署绑定当前草稿、当前 GOLDEN、逻辑证据和实景实现证据。任何一项发生漂移，旧签署都会失效。

成功信号：Codex 调用 `rg.readiness.get` 后看到 `publishable=true`。

### 第 5 幕：受治理发布（约 2 分钟）

继续在同一 Codex 任务中粘贴：

```text
请完成 `demo-profile-ops-v1` 的受治理发布：

1. 调用 rg.readiness.get，确认 publishable=true。
2. 核对当前 draftRevision、goldenSetId、evidenceFingerprint、attestation.implementationFingerprint 与人工签署一致。
3. 任一项不一致或 remainingLimitations 非空时停止，并报告稳定原因码。
4. 全部一致时调用 rg.tool.publish，signoffRef=demo-profile-signoff-v1，并使用新的 idempotencyKey。
5. 再读取 readiness 和 verdict。

只汇报 publicationId、artifactKind、冻结 revision 和最终状态。不得绕过门禁，不得输出 token 或业务 payload。
```

演示者讲解：

> 发布物冻结的是通过门禁的 operator snapshot。后续 catalog 或 descriptor 变化不会让已发布 Tool 静默改用新实现。

成功信号：

- `artifactKind=EXECUTABLE`。
- 返回 publication ID 和冻结 revision。
- 没有未处理的 `remainingLimitations`。

### 第 6 幕：结束和停服（约 1 分钟）

演示者总结：

> Agent 完成了发现、编排、测试和发布提交，但业务真相与发布责任仍由人确认。逻辑测试没有真实外呼；只读实景验证由平台身份在治理边界内完成。

在终端 A 执行：

```bash
./scripts/stop-examples.sh resource-gateway
./scripts/example-services.sh status resource-gateway
```

预期结果：

```text
Resource Gateway: stopped
```

## 5. 演示成功判据

| 检查点 | 必须看到的结果 |
| --- | --- |
| MCP 连接 | 四个最小权限 server 已连接 |
| API 发现 | binding 和 Schema 来自 `rg.capability.list` / `rg.contract.get` |
| Oracle | 不同 HUMAN actor 批准，case lifecycle 为 `ACTIVE` |
| RED | `RED_PASS`，`realExternalCalls=0` |
| GREEN | `status=GO`，`businessFingerprintStable=true`，`realExternalCalls=0` |
| 实景验证 | `ATTESTED`，环境为 local/test/sandbox |
| 发布前 | 人工签署前 `publishable=false`，签署后为 `true` |
| 发布 | `artifactKind=EXECUTABLE` |
| 清理 | `Resource Gateway: stopped` |

缺少任意一项时，不要把演示描述为完整闭环。

## 6. 失败时的演示话术与恢复

### MCP 未连接

执行：

```bash
./scripts/example-services.sh status resource-gateway
curl --fail http://localhost:8081/examples/gateway >/dev/null
codex mcp list
tail -80 target/example-logs/resource-gateway.log
```

演示话术：

> 当前失败发生在 Codex 与 MCP 的连接层，尚未进入业务编排。先保留证据并恢复连接，不跳过协议门禁。

恢复：确认 Agent token 对 Codex 可见，然后完全重启 Codex。不要把 reviewer token 注入 Codex。

### Oracle 尚未批准

现象：`GOLDEN_REQUIRES_APPROVAL`。

演示话术：

> 这是预期的失败关闭。Agent 提出的业务期望不能自动成为真相。

恢复：业务评审人打开精确详情并批准。不要改成临时 rows 绕过持久用例。

### 实景验证失败

现象：`ATTESTATION_EXECUTION_FAILED`、`ATTESTATION_ORACLE_MISMATCH` 或 `ATTESTATION_RECOVERY_REQUIRED`。

演示话术：

> 逻辑 GREEN 与真实沙箱结果不一致，或者平台无法确认上一次请求是否已完成。发布门禁保持关闭。系统不会让 Agent 自动重试并制造重复外呼。

恢复：由人工检查沙箱、descriptor 和 Oracle。只有 HUMAN reviewer 可以从看板确认重跑。演示现场不修改 Oracle 迎合当前实现。

### 发布被拒绝

现象：`PUBLISH_GATE_NOT_MET`。

执行：让 Codex 调用 `rg.readiness.get`，读取 `remainingLimitations`。

演示话术：

> 发布请求没有绕过缺失证据。系统返回的是稳定门禁原因，而不是把半完成草稿发布出去。

恢复：只补齐 readiness 指出的当前证据。内容、用例或 binding 已变化时，重新执行 GREEN、实景验证和人工签署。

## 7. 彩排建议

正式演示前至少完成一次同机彩排：

1. 使用与现场相同的 Java、Maven、Codex、Chrome 和 Chromedriver。
2. 走完两个真实人工停点，不用 HTTP 请求代替浏览器审批。
3. 确认 `user-service.getProfile` 出现在能力目录中。
4. 确认 RED/GREEN `realExternalCalls=0`，attestation 为 `ATTESTED`。
5. 完成发布后执行停服命令，并确认端口释放。
6. 清理终端滚屏中的 token，演示时只保留必要状态和结构化证据。

演示现场优先展示系统真实返回。不要预先制作“成功结果”截图替代实时门禁，也不要在失败时手工修改响应。
