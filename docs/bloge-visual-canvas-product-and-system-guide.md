# BLOGE 通用可视化编排画布产品与系统说明

> Scope: `resource-gateway-examples` 新版通用编排画布 · Primary UI: `/author/` · Companion UI: `/showcase/`

> 截图/标注图说明：本文后续页面截图来自本地演示服务的真实 `/author/` 与 `/showcase/` 页面；布局说明图用于标注本轮新增交互。蓝色框表示重点区域，橙色编号对应正文中的操作说明。

## 1. 产品定位

BLOGE 通用可视化编排画布是一套面向复杂业务编排的 topology-first、schema-backed 工作台。它以 resource gateway 的资源编排能力为基版，但不再把画布绑定到固定几个内置算子，而是允许用户直接粘贴已有 `.bloge` DSL 先可视化整体拓扑，也可以导入自己的算子库定义，把 topology-only draft 渐进增强成可校验、可模拟、可发布和可安全改写的 schema-backed 业务流程。

一句话：用户可以直接粘贴既有 DSL 先看完整业务拓扑，也可以导入结构合法的算子/函数 schema 从空白画布编排；schema 会把 topology-only draft 渐进增强为可校验、可模拟、可发布和可安全改写的 `GraphDraft`。

它解决的问题不是“把图画出来”，而是把以下闭环产品化：

```text
导入/采用算子库
  -> 拖拽算子
    -> schema 约束连线
      -> 服务端校验
        -> mock/real 混合模拟
          -> 导出草稿或发布物
```

这次改进的重点，是把旧版 Custom Composer 中“能用但不够直观”的能力，收束成更清晰的 React Flow authoring workspace：有可搜索 palette、 typed handles、自动布局、下一步行动提示、连接候选高亮、节点级 fixture、模拟 trace 和明确的 real/mocked 标记。

## 2. 面向谁

**业务编排者**：把风控、营销、订单、资源聚合、AI 工具链等业务逻辑按 DAG 编排出来，先验证逻辑，再交给工程实现。

**平台工程师**：维护算子库、资源描述、schema 合同、执行绑定和发布治理，把业务编排从手写代码拆成可审阅的合同资产。

**解决方案/售前/演示人员**：用 `/showcase/` 讲清楚 resource gateway 的典型场景，用 `/author/` 展示“用户自带算子库也能编排”。

## 3. 系统入口

| 入口 | 用途 | 推荐人群 |
| --- | --- | --- |
| `/author/` | 新版通用可视化编排画布，支持导入算子库、Legacy DSL preview、拖拽、连线、校验、模拟、导出 | 主要使用入口 |
| `/showcase/` | React 版 resource gateway 场景目录，按后端场景顺序展示案例、图、请求执行和 SSE 流 | 演示与验证 |
| `/examples/gateway` | 旧版 Custom Composer/Showcase，保留兼容和功能回归价值 | 兼容入口 |

打开 `/author/` 后先按下面这张图定位页面：

![Author 工作台总览标注](assets/bloge-author-overview-annotated.svg)

图中 7 个区域分别承担不同任务：

1. **算子库导入**：粘贴 JSON/YAML，标准 `bloge.visualOperatorLibrary.v1` 先 Validate，再 Import；如果粘贴的是 `bloge.capabilityCatalog.v1`，先点击 `Adapt Catalog` 生成标准 visual library 草稿。导入成功后算子会出现在下方 palette；如果当前粘贴内容是 JSON 形式的合法 visual operator library，也可以作为本次 Legacy DSL preview 的 inline schema 使用。
2. **Legacy DSL**：粘贴既有 `.bloge` DSL，点击 Render DSL，服务端会先按 DSL AST 推演拓扑并投影成可编辑画布 draft。这个入口不要求先准备完整 operator/function schema；schema 只是后续把 topology-only draft 增强为 schema-backed draft 的精确层。
3. **内置复杂示例**：直接加载可编辑的复杂业务 graph，适合第一次理解 fan-out、decision table、transform、fixture 的组合方式。
4. **编排动作条**：执行 Simulate、Auto Layout、Canvas Focus、Validate、Export Draft，并查看节点数、边数、输出节点和 fixture 数。
5. **Graph Contract**：显示当前 graph 的 input/output schema 摘要，告诉系统集成方这张图需要什么上下文、会产出什么结果。
6. **Runtime Context**：以图形化变量表维护本次模拟的 context；高级用户也可以展开 Advanced JSON。
7. **Mock Setup / Test Suite**：右侧 inspector 保持轻量，只展示节点级 mock fixture 和 Test Suite 摘要；点击 `Test Suite` 后用浮层表格组织多行 context、fixture overrides 和 expected output。

当业务图已经有多层依赖或边标签较多时，优先点击工具条里的 **Canvas Focus**。它会临时收起左侧 Library/Legacy DSL/Palette、右侧 Checklist/Runtime/Test Suite inspector、顶部 workflow 和示例卡，只保留 toolbar、Graph Contract 和主画布。这个模式适合做拓扑审阅、Auto Layout 后验收、拖线调试和演示复杂图。

![Author Canvas Focus 模式标注](assets/bloge-author-canvas-focus-annotated.svg)

1. **Focus toggle**：`Canvas Focus` / `Exit Focus` 是同一个按钮，切换后画布会重新 fit view，避免节点仍停在旧视窗位置。
2. **辅助栏临时收起**：左侧算子库和右侧 inspector 不销毁状态，只在 Focus 模式下隐藏；退出后继续从原上下文编辑。
3. **示例区压缩/隐藏**：默认态示例卡更紧凑，Focus 模式完全隐藏示例区，把垂直空间还给 React Flow。
4. **主画布高度目标**：桌面真实浏览器回归要求标准态 author flow 至少 620px 高，Focus 态至少 760px 且比标准态多 100px 以上。

### 3.1 调用 Integration API 前先建立受信身份

`/api/integration/capabilities` 和 evidence 验签公钥是公开探针；其余 `/api/integration/*` 接口必须先验证 workload credential。`X-Tenant-Id`、`X-Organization-Id`、`X-Environment-Id` 和 `X-Actor-Id` 不再构成身份，只是迁移期一致性 hint：缺失不影响服务端 claims，填入值与受信 claims 冲突则返回 `403 RG.INTEGRATION.IDENTITY_CLAIM_MISMATCH`。

![Resource Gateway Integration 受信身份与纵深授权](assets/resource-gateway-integration-trusted-identity.svg)

图源文件：[`assets/drawio/resource-gateway-integration-trusted-identity.drawio`](assets/drawio/resource-gateway-integration-trusted-identity.drawio)

本地演示默认启用一个**仅供 demo** 的 server-side identity registry：

```text
Bearer token: bloge-aneke-demo-token
tenant:       tenant-a
organization: knowledge-governance
project:      tool-studio
environment:  prod
actor:        aneke-sync
```

因此最小同步请求是：

```bash
curl -sS 'http://localhost:8080/api/integration/reconciliation' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: CHANGE_SYNC'
```

建议保留 matching hints，便于在代理迁移或配置漂移时 fail fast：

```bash
curl -sS 'http://localhost:8080/api/integration/reconciliation' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-Organization-Id: knowledge-governance' \
  -H 'X-Project-Id: tool-studio' \
  -H 'X-Environment-Id: prod' \
  -H 'X-Actor-Id: aneke-sync' \
  -H 'X-Purpose: CHANGE_SYNC' \
  -H 'X-Correlation-Id: sync-0001'
```

服务端会执行两层 purpose allowlist：credential identity 必须允许该 purpose，目标 endpoint 也必须接受该 purpose。

| operation | 允许的 purpose |
| --- | --- |
| draft export | `GOVERNANCE_EVIDENCE_INGESTION`、`CHANGE_SYNC` |
| run evidence | `GOVERNANCE_EVIDENCE_INGESTION` |
| recorded payload read | `GOVERNANCE_EVIDENCE_INGESTION`、`PAYLOAD_REPLAY` |
| recorded assertion replay | `PAYLOAD_REPLAY` |
| gate result write | `GOVERNANCE_GATE_FEEDBACK` |
| gate result read | `GOVERNANCE_EVIDENCE_INGESTION`、`GOVERNANCE_GATE_FEEDBACK` |
| events / reconciliation / library / suite | `CHANGE_SYNC` |

每次允许或拒绝都会写入 `integration_access_audit`，记录 identity、tenant/environment、operation、purpose、outcome 和 reason code。signed JWT 还会记录验证它的 `kid` 和不可重放的 `jti`，但不保存 Bearer token 或签名内容。认证在资源查询之前完成；service 和 repository 随后仍按 tenant/environment 做二次 scope predicate，避免只靠入口层。

企业部署必须关闭公开 demo credential。当前系统已经内置短时 signed JWT 模式，不需要另写 resolver 才能建立生产接入基线：

```bash
export RG_INTEGRATION_DEMO_IDENTITY_ENABLED=false
export RG_INTEGRATION_JWT_ENABLED=true
export RG_INTEGRATION_JWT_ISSUER='https://iam.example.com/'
export RG_INTEGRATION_JWT_AUDIENCE='resource-gateway'
export RG_INTEGRATION_JWT_MAXIMUM_LIFETIME_SECONDS=900
export RG_INTEGRATION_JWT_TRUSTED_KEYS_JSON='[
  {
    "keyId": "aneke-sync-2026-07",
    "algorithm": "RS256",
    "publicKeyBase64": "<base64-of-X509-DER-public-key>",
    "notBefore": "2026-07-12T00:00:00Z",
    "expiresAt": "2026-10-12T00:00:00Z",
    "enabled": true
  }
]'
```

`publicKeyBase64` 是 X.509 SubjectPublicKeyInfo DER 的普通 Base64，只能放公钥；private key 必须留在企业 IAM/KMS。支持 `RS256` 和 `EdDSA`，RSA key 至少 2048 bit。要无停机轮换，在 JSON 数组中先同时放入旧、新两个不同 `kid` 的 public key，等待所有调用方切换后，再用 `RG_INTEGRATION_JWT_REVOKED_KEY_IDS=old-kid` 撤销旧 key。紧急撤销单枚 token 使用 `RG_INTEGRATION_JWT_REVOKED_TOKEN_IDS=jti-1,jti-2`。当前环境变量信任库在启动时装载；需要不重启传播 JWKS/KMS 轮换和撤销时，提供动态 `IntegrationJwtTrustStore` Bean。

ANEKE 发送的 JWT header 至少包含：

```json
{
  "alg": "RS256",
  "kid": "aneke-sync-2026-07",
  "typ": "JWT"
}
```

JWT payload 合同如下；`aud` 可以是字符串或数组，`purposes` 必须是非空数组：

```json
{
  "iss": "https://iam.example.com/",
  "aud": ["resource-gateway"],
  "sub": "aneke-sync-workload",
  "jti": "01J2TOKEN7YQ4M1",
  "iat": 1783843200,
  "nbf": 1783843200,
  "exp": 1783843500,
  "tenant_id": "tenant-a",
  "organization_id": "knowledge-governance",
  "project_id": "tool-studio",
  "environment_id": "prod",
  "region": "ap-southeast-1",
  "actor_type": "WORKLOAD",
  "actor_id": "aneke-sync",
  "delegated_by": "",
  "purposes": ["CHANGE_SYNC", "GOVERNANCE_EVIDENCE_INGESTION"]
}
```

服务端会拒绝 `alg=none`、算法/key 类型不一致、未知/停用/撤销 `kid`、撤销 `jti`、错误 issuer/audience、未来生效、过期、超过最大 TTL、重复 JSON 字段、非法 purpose 和自委托链。整个 Bearer credential 上限为 4096 字符，失败统一返回 401，不向调用方暴露具体验签分支。

启动后先检查公开 capability：生产环境应看到 `providerType=SIGNED_JWT`、`claimsSource=VERIFIED_TOKEN`、`demoMode=false`，并检查 `properties.activeKeyCount`、`keyRotationSupported`、`keyRevocationSupported`。若 provider 没有 active key，`available=false`，受保护接口 fail closed。不要把 `RG_INTEGRATION_DEMO_TOKEN` 的默认值带到共享或生产环境；OIDC/mTLS、组织 group/clearance 或更复杂 delegation policy 仍可通过替换 `IntegrationIdentityResolver` 接入。

### 3.2 从 ANEKE 治理问题直达画布

ANEKE Tool Studio 可以把治理问题链接回 `/author/`，画布会读取服务端保存的草稿，自动布局并定位到具体节点或算子。作者不需要先打开 Author 首页再手工搜索草稿。

![ANEKE 治理反馈与 Deep Link 标注](assets/bloge-governance-deep-link-authoring-annotated.svg)

1. **Deep Link 定位确认**：页面显示实际打开的 `draftId@revision` 和聚焦节点；目标在当前修订中不存在时会明确警告，不会悄悄选中错误节点。
2. **ANEKE 门禁反馈**：门禁带显示 `BLOCKED/PASSED` 结论和 `CURRENT/STALE/EXPIRED/MISSING` 新鲜度。`STALE` 或 `EXPIRED` 只作为历史反馈展示，不能冒充当前修订的发布结论。
3. **问题目标节点自动聚焦**：点击门禁问题会读取其 `targetPath` 或 `deepLink`，选中对应节点；截图中 `approvalPolicy` 因 correctness workbook 覆盖不足被直接定位。
4. **全图缩略图与视口**：Deep Link 载入后同样执行 Auto Layout 和 Fit All；复杂图仍可从 Map 判断整体形状和当前视口。

支持的查询参数：

| 参数 | 作用 | 解析规则 |
| --- | --- | --- |
| `draftId` | 打开存量草稿 | 读取 `GET /api/visual/drafts/{draftId}` |
| `nodeId` | 聚焦具体节点 | 优先级最高；必须存在于该 draft revision |
| `operatorRef` | 聚焦某类算子 | 选择当前图中第一个匹配 `operatorRef` 的节点 |
| `runId` | 从一次运行回到作者上下文 | 先读取 `GET /api/visual/runs/{runId}`，再由运行记录恢复 `draftId`、revision 和 output node |
| `gateIssueId` | 聚焦一条 ANEKE 门禁问题 | 从 gate result 中找到 issue，再由 `targetPath/deepLink` 解析节点 |

典型链接：

```text
/author/?draftId=<draftId>&nodeId=approvalPolicy
/author/?draftId=<draftId>&operatorRef=knowledge%3Aretrieve
/author/?runId=<runId>
/author/?draftId=<draftId>&gateIssueId=missing-workbook
```

使用 `runId` 打开时，画布还会显示运行结果、source kind、draft revision、耗时和第一条错误。若运行来自 transient draft 且没有可恢复的 `draftId`，页面会保留运行上下文并明确提示无法恢复存量草稿。

治理反馈由 ANEKE 通过 `POST /api/integration/gate-results` 写入，并绑定不可变的 `draftId + revision + draftFingerprint`。同一个 `gateResultId` 重复提交相同内容是幂等成功，内容不同则返回冲突；画布只通过只读接口 `GET /api/visual/governance-gates/drafts/{draftId}` 消费结果。

### 3.3 用 recorded replay 重算正确性断言

`GET /api/integration/runs/{runId}/replay` 只读取已脱敏的 recorded payload；`POST` 同一路径才是 replay command。command 不会重新运行 DSL，也不会调用任何 operator 或外部资源，而是基于父运行保存的 context、node input/output、terminal output 和 evidence 状态重算断言，并生成新的 replay run/evidence。

请求必须使用 `X-Purpose: PAYLOAD_REPLAY`，并显式声明副作用策略 `DENY`：

```json
{
  "schemaVersion": "toolStudio.resourceGateway.replayExecutionRequest.v1",
  "requestId": "workbook-case-2026-07-12-001",
  "mode": "RECORDED_ASSERTIONS",
  "caseType": "REGRESSION",
  "externalSideEffectPolicy": "DENY",
  "assertions": [
    {
      "assertionId": "terminal-decision",
      "scope": "OUTPUT",
      "mode": "PATH_EQUALS",
      "path": "/decision",
      "expectedValue": "APPROVE"
    },
    {
      "assertionId": "eligibility-node",
      "scope": "NODE",
      "nodeId": "eligibility",
      "mode": "PATH_EXISTS",
      "path": "/eligible"
    },
    {
      "assertionId": "evidence-ready",
      "scope": "RUN",
      "mode": "GOVERNANCE_EXPECTATION",
      "expectedValue": "EVIDENCE_READY"
    }
  ]
}
```

支持的 `caseType` 是 `GOLDEN`、`NEGATIVE`、`BOUNDARY`、`REGRESSION`。断言支持：

| mode | scope | 含义 |
| --- | --- | --- |
| `EQUALS` | OUTPUT/NODE | 完整 JSON 结构相等 |
| `PATH_EQUALS` | OUTPUT/NODE | JSON Pointer 路径值相等 |
| `PATH_EXISTS` / `PATH_ABSENT` | OUTPUT/NODE | 路径存在性 |
| `MATCHES_SCHEMA` | OUTPUT/NODE | 满足 JSON Schema 或 `SchemaEnvelope` |
| `ERROR_CONTAINS` | RUN | 父运行错误包含指定 code/text |
| `GOVERNANCE_EXPECTATION` | RUN | `EVIDENCE_READY`、`SIGNATURE_VERIFIED`、`NO_MOCKS` 或 `NO_ERRORS` |

成功受理后系统生成确定性的 replay runId，并返回 `parentRunId`、request fingerprint、断言结果、`externalInvocationCount=0`、evidence 状态和 evidence endpoint。相同 `requestId + parentRunId + request content` 重试返回同一 replay run；同一 `requestId` 指向不同内容返回 `409`。期望值不会原样写入 assertion evidence，只保存 fingerprint，降低测试数据泄露风险。

当前 replay command 只支持 `RECORDED_ASSERTIONS + DENY`。`shadow/live` 重放仍未开放，因为它们需要独立的审批、隔离环境、幂等能力证明和 unknown-commit 处理，不能复用这个安全接口悄悄开启。

### 3.4 读取 timeout、fallback 与 partial failure 证据

画布点击 **Run** 后，系统不再只保存一个最终 `SUCCESS/FAILED`。BLOGE 的 node lifecycle 与
`ResilienceListener` 会在同一个 run capture 中记录 retry、timeout 和 fallback 事件；Resource Gateway
再结合不可变拓扑解释 skip/cancel 的因果关系，最后写入 `VisualGraphRunRecord.v7` 并导出
`runEvidenceBundle.v5`。v7/v5 在既有 control fact 之外增加 recovery provenance，使正常完成记录和系统恢复记录
可以被机器明确区分。

![Resource Gateway 运行失败事实链](assets/resource-gateway-run-failure-semantics.svg)

图源：[resource-gateway-run-failure-semantics.drawio](assets/drawio/resource-gateway-run-failure-semantics.drawio)。

作者和 ANEKE 都按以下步骤查看：

1. 在画布运行图，记下响应中的 `runId`；也可通过 `/author/?runId=<runId>` 回到对应运行上下文。
2. 用具备 `GOVERNANCE_EVIDENCE_INGESTION` purpose 的 workload token 调用
   `GET /api/integration/runs/{runId}/evidence`。
3. 先检查 `manifest.evidenceStatus` 和 `signatureStatus`。只有 `READY + VERIFIED` 才表示当前 bundle
   结构完整且签名可信；`QUARANTINED` 必须先处理 `manifest.gaps`，不能进入发布门禁的通过证据集。
4. 再检查 `execution.criticalOutputReached`。失败 run 中，只有关键输出已经产生且同时存在其他失败时，
   图级状态才是 `PARTIAL`；关键输出自身超时则是 `TIMEOUT`，不会因为某个上游成功而误判为 `PARTIAL`。
5. 对每个节点读取 `reasonCode`、`observationSource`、`retry`、`timeout`、`fallback`、
   `causedByNodeIds` 和 `sideEffectOutcome`；对每条边读取 `propagated`，不要只看颜色或最终状态。

典型节点语义如下：

| 场景 | `status` | `reasonCode` | 事实来源 |
|---|---|---|---|
| 正常完成 | `SUCCESS` | `NONE` | `ENGINE_STATUS` |
| 重试耗尽后 fallback 返回 | `FALLBACK` | `FALLBACK_SUCCEEDED` | `ENGINE_RESILIENCE_EVENT`；retry 仍标记 `exhausted=true` |
| 节点实际超时 | `TIMEOUT` | `NODE_TIMEOUT` | BLOGE timeout event，不解析错误文本 |
| 条件分支未命中 | `SKIPPED` | `BRANCH_NOT_TAKEN` | `TOPOLOGY_DERIVATION` |
| 上游失败导致未执行 | `CANCELLED` | `UPSTREAM_FAILED` | `TOPOLOGY_DERIVATION`，`causedByNodeIds` 指向上游 |
| 同名图并发导致旧 listener 无法唯一关联 | 保留引擎最终状态 | `RESILIENCE_EVENT_CORRELATION_AMBIGUOUS` | `ENGINE_STATUS_WITH_EVENT_GAP`，evidence 隔离 |

边的 `status` 表示**传播事实**，不是目标节点的执行结果。例如 A 成功把值传给 B，随后 B 超时：
A→B 的边仍为 `SUCCESS + propagated=true + VALUE_PROPAGATED`，B 才是 `TIMEOUT`。若 A 超时导致 B
未执行，边为 `CANCELLED + propagated=false + UPSTREAM_FAILURE_PROPAGATED`，且没有虚构的 edge payload ref。

`sideEffectOutcome=UNKNOWN_COMMIT` 不是失败，而是诚实的不确定性：当前 BLOGE operator 合同没有提供外部
事务 commit receipt 时，Resource Gateway 不能从“抛异常/超时”反推出远端是否已经提交。ANEKE 应结合
operator 的 effect/idempotency、业务补偿能力和门禁策略决定是否接受；不要把 `UNKNOWN_COMMIT` 当作
`NOT_COMMITTED`。

能力探针会明确返回：

| capability | 当前值 | 含义 |
|---|---:|---|
| `structuredExecutionFacts` | `true` | node/edge/graph 标准事实已实现 |
| `graphDeadline` | `true` | 画布 run 支持绝对 deadline，并为业务执行预留 evidence/terminal-state 收尾时间 |
| `operatorContextDeadlineBudget` | `true` | 每个 BLOGE operator 可读取同一绝对 deadline 和只减不增的 remaining budget |
| `deadlineAdmissionControl` | `true` | 剩余业务预算耗尽时，scheduler 在算子执行前 fail closed，并记录 `DEADLINE_EXHAUSTED` |
| `retryBudgetEnforcement` | `true` | timeout 和 retry backoff 都会被剩余预算截短；预算不足时不会启动下一次 retry |
| `httpRemainingBudget` | `true` | Resource Gateway 与 BLOGE common HTTP operator 使用较短的有效 timeout，并向下游传递 deadline/budget header |
| `remoteWorkerDeadlineBudget` | `true` | remote worker envelope 携带 deadline、捕获时剩余预算、reserve 与 capturedAt，worker 可按时钟偏差 fail closed |
| `userRunCancellation` | `true` | 支持带 fencing token 的协作式 cancel command |
| `runTerminationConfirmation` | `true` | owner 已退出且 operator in-flight 归零后才确认终止 |
| `hardRunTermination` | `false` | Java 进程不能安全强杀忽略中断的任意业务代码 |
| `durableRunControl` | `true` | control state、fence digest、owner epoch、revision 和 lease 持久化；跨实例 lookup/cancel 可用 |
| `crossInstanceRunCancellation` | `true` | 非 owner 实例可用共享数据库提交 fenced cancel，owner 在续租循环中观察并中断本地线程 |
| `runOwnerLease` / `runOwnerEpochFencing` | `true` | owner 只能在有效 lease 和匹配 epoch 下推进状态，旧 owner 不能覆盖恢复结论 |
| `expiredOwnerQuarantine` | `true` | owner 租约过期后持久化为 `OWNER_LEASE_EXPIRED + TERMINATION_UNCONFIRMED` |
| `restartRunResumption` | `false` | 进程崩溃后不会盲目重跑未知副作用；当前恢复策略是 abandonment + quarantine，而不是自动续跑 |
| `runControlEvidence` | `true` | control fact 已进入 run record、签名和 evidence v5 |
| `runEvidenceRecoveryReservation` | `true` | managed run 执行前先持久化脱敏 lineage reservation，绑定确定性 runId、draft/scope/input fingerprint |
| `abandonedRunEvidenceRecovery` | `true` | owner abandonment、terminal evidence gap 和过期 missing-control reservation 会自动形成签名但 fail-closed 的 run evidence |
| `recoveryTransactionalOutbox` | `true` | 恢复 run record、reservation 终态与 `RUN_ABANDONED/RUN_EVIDENCE_RECOVERED` 事件同事务提交 |
| `sideEffectCommitConfirmation` | `false` | 尚无 operator commit receipt/unknown-commit 消解协议 |

旧 `runEvidenceBundle.v1/v2/v3/v4` 仍在 capability 的兼容列表中；新 consumer 应优先协商 v5。旧 run 若没有
每个节点的结构化 execution fact，会得到
`Structured execution semantics were not captured for every node.` gap 并被隔离，而不是由服务端静默猜测。
ANEKE 可直接使用 [run-evidence-bundle-v5.schema.json](schemas/tool-studio-resource-gateway/run-evidence-bundle-v5.schema.json)
做 producer/consumer contract 校验，不需要解析 Resource Gateway 内部 Java DTO。

#### 在画布设置 deadline 和取消运行

Custom Composer 的 **Run** 区现在直接提供 `Deadline` 数值框。点击 **Run** 时，页面自动生成
`requestId + fencingToken`，把相对毫秒数转换为绝对 `deadlineAt`；运行期间 Run 按钮会锁定，右侧出现停止按钮。
用户点击停止即可发出 fenced cancellation，不需要手写控制 JSON。

![Custom Composer 的 Deadline 与 Run 控件](assets/resource-gateway-run-control-ui.jpg)

上图聚焦右侧运行区：`Deadline` 接受 `100-300000 ms`，蓝色按钮启动受控运行；运行进入 active 状态后，
同一行会出现方形停止按钮。窄屏布局会自动把命令区收束为单列，输入框、按钮和规则表不会产生横向滚动：

![移动端 Deadline 与 Run 控件](assets/resource-gateway-run-control-ui-mobile.jpg)

![Resource Gateway run control 生命周期](assets/resource-gateway-run-control-lifecycle.svg)

图源：[resource-gateway-run-control-lifecycle.drawio](assets/drawio/resource-gateway-run-control-lifecycle.drawio)。

#### Deadline 如何进入算子和下游调用

页面上的 `Deadline` 是整次 run 的绝对边界，不是每个节点可独占的 timeout。Gateway 在启动 BLOGE 前创建共享
`ExecutionBudget`，并从绝对 deadline 中扣除 `finalizationReserve`；默认 reserve 为 `100 ms`。所有 scoped
`GraphContext`、嵌套图和 `OperatorContext` 共享同一预算上限，后续代码只能收紧，不能重新绑定一个更晚的
deadline。即使操作系统时钟向后校正，已经观察到的 remaining budget 也不会增大。

自定义 operator 可直接使用：

```java
Instant deadline = ctx.deadlineAt().orElse(null);
Duration remaining = ctx.remainingBudget().orElse(null);
Duration effectiveTimeout = ctx.capTimeout(configuredTimeout, "customer profile lookup");
```

调用链遵循以下规则：

1. scheduler 在 node admission 前检查预算；为零时不调用 operator，node fact 为
   `CANCELLED + DEADLINE_EXHAUSTED + ENGINE_ADMISSION`。
2. resilience timeout、suspend timeout 和 retry backoff 都不得超过剩余预算；下一次退避已经放不下时立即停止重试。
3. `HttpResourceOperator` 和 BLOGE `HttpRequestOperator` 取“配置 timeout 与剩余预算的较小值”。common HTTP
   operator 还发送 `X-Bloge-Deadline` 与 `X-Bloge-Remaining-Budget-Ms`，让下游继续收紧自己的子调用。
4. `RemoteWorkerEnvelope.Budget` 携带 `deadlineAt`、`remainingBudgetMillis`、`finalizationReserveMillis` 和
   `capturedAt`。worker 必须结合允许的 clock skew 判断 envelope 是否已过期，不能用传输前的剩余值重新放宽预算。

`remainingBudget` 是“还允许投入业务工作的时间”，已经扣除了 evidence 签名、终态持久化和 outbox 提交的
reserve。生产环境应根据 p99 evidence finalization 延迟配置该值；reserve 过小会在 deadline 边缘丢失治理证据，
过大则会过早拒绝业务节点：

```properties
resource-gateway.run-control.finalization-reserve-ms=100
```

当前协议仍不承诺强杀任意忽略中断的 Java 代码，也没有定义客户端断开后的 `detachPolicy`。自定义 operator 若绕过
`ctx.capTimeout(...)` 发起不可取消的私有 I/O，外层 run-control 只能中断线程并在 grace 后诚实地进入
`TERMINATION_UNCONFIRMED`；这类 binding 在生产准入时仍应被合规测试阻断。

执行完成后，在 Output 中展开 `payload.runControl`：

| status | 如何理解 | evidence 处理 |
|---|---|---|
| `SUCCEEDED` / `FAILED` | 调度线程和所有算子线程均已退出 | 继续按 node/edge facts 判断 |
| `CANCELLED` | 用户取消，协作终止已经确认 | 可消费，但仍检查外部写的 `sideEffectOutcome` |
| `TIMED_OUT` | 绝对 graph deadline 已到且协作终止已确认 | 可消费为 timeout 事实 |
| `CANCEL_REQUESTED` / `TIMING_OUT` | 停止信号已受理，仍在等待线程退出 | 非终态，不应发布 |
| `TERMINATION_UNCONFIRMED` | grace 已过或 owner 退出时仍有 operator in-flight | `manifest=QUARANTINED`，门禁必须阻断 |

`terminationConfirmed` 不是“HTTP 请求已经返回”的同义词。系统同时跟踪 scheduler owner 和实际进入
operator interceptor 的虚拟线程；只有 owner 退出且 in-flight 计数为零，才会清除
`sideEffectsMayBeInFlight`。若业务算子吞掉 `InterruptedException`，页面会及时返回
`TERMINATION_UNCONFIRMED`，而不是伪造 `CANCELLED`。

#### 多实例与重启时如何解释

Spring 产品服务使用 `dynamic_run_controls` 作为权威状态表，JVM 内存只保存当前 owner 的线程句柄。表中保存
`requestId`、fence 的 SHA-256 digest、owner id/epoch、monotonic revision、绝对 deadline、lease、状态和终止事实；
不会保存原始 fencing token。所有变更在数据库行锁内完成，因此两个实例同时取消时只有一个命令成为状态迁移赢家。

典型过程如下：

1. 画布 API 在进入 BLOGE 前先写 `visual_run_recovery_reservations`：保存去除 fixture 后的 draft、租户/环境、
   已脱敏输入、material fingerprint 和由 requestId 确定的 runId；不保存原始 fencing token。
2. 实例 A 以唯一 `requestId` 创建 durable claim，并获得 owner epoch。
3. A 每次轮询 deadline/cancel 时续租；本地线程句柄不写数据库。
4. 实例 B 可以处理同一 requestId 的 GET/cancel。B 只提交 fenced 状态迁移，不尝试操作 A 的线程。
5. A 观察到 `CANCEL_REQUESTED` 后中断 owner 和 operator，最终提交 `CANCELLED` 或
   `TERMINATION_UNCONFIRMED`。
6. A 若崩溃，lease 到期后的首次读取会原子地写入 `OWNER_LEASE_EXPIRED`、
   `recoveryDisposition=ABANDONED` 和 `sideEffectsMayBeInFlight=true`；旧 owner/epoch 后续不能覆盖它。
7. bounded sweeper 发现 abandonment 后锁定 reservation。正常完成路径与 recovery 路径只能有一个赢家；赢家
   创建 `VisualGraphRunRecord.v7`、签名 evidence v5、提交 reservation 终态并原子追加 integration outbox。

在 evidence v5 中先看顶层 `recovery`：

| `recovery.mode` | 含义 | 治理处理 |
|---|---|---|
| `NONE` | 请求线程完成了正常 run-record 提交 | 按 manifest、node/edge facts 和 assertion 继续判断 |
| `OWNER_ABANDONED` | owner lease 过期，执行终止和外部副作用无法确认 | `QUARANTINED`；消费 `RUN_ABANDONED`，发布门禁必须阻断 |
| `TERMINAL_EVIDENCE_GAP` | control 已终态，但进程在 evidence 事务前死亡 | 保留图级终态，精确 payload/facts 缺口仍隔离；消费 `RUN_EVIDENCE_RECOVERED` |
| `CONTROL_MISSING` | reservation 已提交，但 grace 后仍没有 control row | 视为 admission/进程异常，自动收敛 pending 状态并隔离 |

`reservationFingerprint`、`controlRevision`、`attempt` 和 `recoveredAt` 都进入 evidence 签名材料。恢复 evidence
可以被审计和对账，但不会因为“签名有效”就变成 `READY`：缺失精确 node payload、终止未确认或
`UNKNOWN_COMMIT` 仍会出现在 `manifest.gaps`。

恢复扫描默认每 5 秒运行一次；terminal control 额外等待 5 秒，让正常 evidence 事务优先完成；reservation 在
30 秒后仍没有 control 才进入 `CONTROL_MISSING`。演示环境可通过以下配置调整，但生产值应由 SLO、最大正常
提交时延和数据库容量测试决定：

```properties
resource-gateway.run-control.finalization-reserve-ms=100
resource-gateway.run-recovery.fixed-delay-ms=5000
resource-gateway.run-recovery.initial-delay-ms=5000
resource-gateway.run-recovery.evidence-commit-grace-ms=5000
resource-gateway.run-recovery.missing-control-grace-ms=30000
```

这里刻意不做自动重跑。进程死亡时，系统无法仅凭线程消失判断远端写是否提交；盲目从头执行可能造成重复扣款、
重复发布或重复通知。业务要恢复执行，必须先通过未来的 commit receipt/reconciliation 协议消解 unknown commit，
或由上层创建新的 requestId 走明确审批后的补偿/重试流程。

自动化客户端也可以调用：

```http
GET /api/visual/run-controls/{requestId}
X-Run-Fencing-Token: <fencingToken>

POST /api/visual/run-controls/{requestId}/cancel
Content-Type: application/json

{
  "fencingToken": "<fencingToken>",
  "expectedRevision": 0,
  "reason": "AUTHOR_CANCELLED_FROM_CANVAS"
}
```

`expectedRevision>0` 启用乐观并发检查；过期 revision 返回 `409`，错误 fence 返回 `403`。当前控制表只在
单个 Resource Gateway 进程内保留一小时终态，因此不能把它当作跨重启 workflow store；跨实例接管仍需
BLOGE durable execution lease 与持久 fencing owner。

### 3.5 让 ANEKE 持续同步且可对账

Resource Gateway 现在不要求 ANEKE 依赖 webhook 才能持续治理。draft、operator library、run 和 operator contract test suite 的权威写入会在**同一个数据库事务**中追加 integration event；任一 Outbox 写入失败时，资产写入也会回滚，不会产生“资产已经存在、治理侧永远不知道”的静默裂缝。

![Resource Gateway 与 ANEKE 持续同步闭环](assets/resource-gateway-aneke-continuous-sync.svg)

图源文件：[`assets/drawio/resource-gateway-aneke-continuous-sync.drawio`](assets/drawio/resource-gateway-aneke-continuous-sync.drawio)

ANEKE 侧按以下顺序接入：

1. 使用 `X-Purpose: CHANGE_SYNC` 调用 `GET /api/integration/reconciliation`，取得当前租户和环境的权威资产快照、各类数量、rolling fingerprint 和 `checkpointCursor`。
2. 用快照重建或校正 ANEKE projection。`GRAPH_DRAFT`、`GRAPH_CONTRACT`、`RUN` 和 gate result 按 tenant/environment 隔离；当前 operator library 和 contract suite 是共享资产，会以 global scope 出现在每个租户的同步视图中。
3. 保存快照里的 `checkpointCursor`，随后调用 `GET /api/integration/events?cursor=...&limit=100`。
4. 按 `eventId` 去重，并按 `aggregate.kind + aggregate.id + aggregate.sequence` 防止旧 revision 覆盖新 revision。事件只携带 fingerprint 和稳定 `payloadRef`；draft、library、suite 可按事件 revision 读取不可变快照，run 按 runId 读取 evidence。
5. 将本页 projection 更新和 `nextCursor` 放在 ANEKE 自己的同一事务中提交。`hasMore=true` 时继续拉取；`false` 时保存 `checkpointCursor` 并进入下一轮 polling。
6. Polling 中断、请求超时或通知丢失时，从最后已提交 cursor 继续。重复读取同一个 cursor 会得到同一个固定 high-water 窗口，不会把处理中途的新事件混进本页。
7. cursor 被篡改、跨租户/环境复用时返回 `400 RG.INTEGRATION.CURSOR_INVALID`；cursor 超过当前 7 天有效期返回 `410 RG.INTEGRATION.CURSOR_EXPIRED`，此时重新调用 reconciliation，不要猜测数据库 offset。

首次拉取示例：

```bash
curl -sS 'http://localhost:8080/api/integration/events?limit=100' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-Organization-Id: knowledge-governance' \
  -H 'X-Project-Id: tool-studio' \
  -H 'X-Environment-Id: prod' \
  -H 'X-Actor-Id: aneke-sync' \
  -H 'X-Purpose: CHANGE_SYNC' \
  -H 'X-Correlation-Id: sync-0001'
```

反熵快照示例：

```bash
curl -sS 'http://localhost:8080/api/integration/reconciliation' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-Organization-Id: knowledge-governance' \
  -H 'X-Project-Id: tool-studio' \
  -H 'X-Environment-Id: prod' \
  -H 'X-Actor-Id: aneke-sync' \
  -H 'X-Purpose: CHANGE_SYNC'
```

这里不承诺网络级 exactly-once。系统采用“生产端 transactional outbox + ANEKE 幂等消费 + opaque cursor + reconciliation”的可恢复最终一致性模型。Webhook 仍未开放；后续即使增加 webhook，它也只负责低延迟提醒，不能替代 cursor 和对账快照。

### 3.6 演示脚本启动方式

推荐演示时直接使用仓库根目录下的专用脚本。它默认执行 `resource-gateway-examples` 的 `-Pfrontend package`，把 React UI 打进 Spring Boot 静态资源，然后在 `8080` 启动服务。为缩短演示准备时间，脚本默认给 Maven 打包加 `-DskipTests`；需要把测试也跑进去时使用 `--run-tests`。

```bash
./scripts/start-visual-canvas-demo.sh
```

启动成功后脚本会打印：

```text
Author canvas:   http://localhost:8080/author/
Showcase:        http://localhost:8080/showcase/
Legacy composer: http://localhost:8080/examples/gateway
```

查看状态和日志位置：

```bash
./scripts/visual-canvas-demo.sh status
```

停止演示服务：

```bash
./scripts/stop-visual-canvas-demo.sh
```

常用参数：

| 参数 | 用途 |
| --- | --- |
| `--open` | 启动后自动打开 `/author/` |
| `--port 18080` | 改用指定端口 |
| `--no-build` | 跳过打包，复用已有 jar |
| `--api-only` | 不启用 `-Pfrontend`，只打包后端 API |
| `--run-tests` | 打包时不跳过 Maven 测试 |
| `-- --gateway.base-url=http://localhost:9091` | `--` 后面的参数透传给 Spring Boot 应用 |

脚本使用 `target/example-pids/visual-canvas-demo.pid` 记录进程，使用 `target/example-logs/visual-canvas-demo.log` 记录日志；停止时会校验 PID/端口上的进程确实像 Resource Gateway demo，避免误停其它服务。

### 3.7 手动启动方式

如果只运行后端 API 或旧版静态资源：

```bash
mvn -f resource-gateway-examples/pom.xml spring-boot:run
```

如果要使用新版 `/author/` 和 `/showcase/` 的打包版 React UI，先启用 frontend profile：

```bash
mvn -f resource-gateway-examples/pom.xml -Pfrontend package
java --enable-preview -jar resource-gateway-examples/target/bloge-examples-resource-gateway-1.0.0.jar
```

然后访问：

```text
http://localhost:8080/author/
http://localhost:8080/showcase/
http://localhost:8080/examples/gateway
```

说明：默认 Maven 构建不会打包 React UI，目的是让 Java 验证保持快速、离线。`-Pfrontend` 会安装本地 Node、执行 `npm ci` 和 `npm run build`，再把同一份 Vite 产物复制到 `static/author` 与 `static/showcase`。

本地前端调试也可以进入 `resource-gateway-examples/src/main/frontend` 执行：

```bash
npm ci
npm run dev
```

当前 Vite dev proxy 只代理 `/api` 到 Spring Boot。算子库导入使用 `/admin/visual-operator-libraries/*`，所以完整体验建议优先使用 Maven 打包后的 `/author/`。

### 3.8 VSCode 插件轻量化方向

如果用户只是想在业务仓库里看懂 `.bloge` 拓扑、导入本地算子 schema、配置 mock 数据并跑表格测试，每次都启动 Resource Gateway 服务端会显得偏重。后续推荐把 `/author/` 的核心能力下沉成 VSCode 插件入口：

```text
打开业务仓库
  -> Visualize 当前 .bloge
    -> 本地 topology-only 渲染
      -> 扫描 workspace schema 后渐进增强
        -> 本地 mock simulation / test suite
          -> 可选切换 JVM 或远程服务端做权威校验
```

这条路线不会替代现有服务端。插件负责低门槛 authoring 和本地理解；Resource Gateway / Studio 服务端继续负责权威 validation、rewrite gate、governed commit、发布治理和真实运行。

当前代码已经先补了一个插件化地基：前端 `api.ts` 支持 `BlogeApiTransport`，浏览器 demo 默认仍走 `fetch`，VSCode Webview 未来可以把同一批 API 请求通过 `postMessage` 交给 extension host 本地处理。详细方案见 [BLOGE VSCode 插件轻量化可视化编排方案](./bloge-vscode-extension-lightweight-authoring-plan.md)。

## 4. 核心概念

| 概念 | 含义 |
| --- | --- |
| Operator Library | 用户或系统提供的算子库，合同版本为 `bloge.visualOperatorLibrary.v1`；字段定义见 [BLOGE 可视化算子库 Schema 定义](./bloge-visual-operator-library-schema.md) |
| Operator | 单个可编排算子，至少有 `operatorRef`，通常包含展示信息、输入/输出端口、schema、lowering |
| Built-in Function | 算子库或系统默认目录提供的 BLOGE 表达式函数，用于 transform/branch 等表达式输入框的函数名补全和签名提示 |
| Port Schema | 输入/输出端口的 JSON Schema envelope，画布用它判断可连接性 |
| GraphDraft | 画布中的业务流程草稿，合同版本为 `bloge.visualGraphDraft.v1` |
| Connection Candidate | 服务端根据当前 draft 和 schema 枚举出的可连接目标 |
| Validate | 对当前 draft 做结构、schema、readiness、action readiness 校验 |
| Node Fixture | 节点级模拟样本，可 pin mock 输出，也可断言该节点收到的 expected input |
| Test Suite | 画布内的表格测试浮层；每行是一组 runtime context、节点 fixture override 和 expected terminal output |
| Simulate | 混合模拟运行。安全且已实现的内置算子可 real-run；design-only 或高风险算子会 mock-run |
| Export | 导出当前 draft、publication bundle 或内置 operator library bundle |

关键原则：浏览器负责交互体验，规则由服务端兜底。客户端可以做提示和高亮，但连接是否有效、草稿是否可运行、模拟是否可信，都以服务端结果为准。

### 4.1 Graph 级 input/output schema 在哪里看

Resource Gateway 内置 graph 的正式合同定义在：

- 代码：`resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/gateway/GatewayGraphContractCatalog.java`
- API：`GET /api/gateway/graphs/contracts`
- 示例场景 API：`GET /api/gateway/examples/scenarios`，每个 scenario 会携带自己的 `inputSchema` 和 `outputSchema`

新版 `/author/` 和旧版 `/examples/gateway` 都把 graph 合同作为一等信息看待：

- `/author/`：画布工具栏下方有 **Graph Contract** 条，显示当前 draft 的 Input/Output 摘要。3 个内置复杂示例各自携带 `inputSchema` 和 `outputSchema`；加载示例时会同步设置当前 graph contract，并用 input schema 生成一份 runtime context 样本。从 Legacy DSL 导入时，graph 级 `input { ... }` 会进入 `draft.inputSchema`，graph 级 `output { ... }` 会进入一等 `draft.outputSchema`。`visualLayout.graphContract.outputSchema` 仍会保留一份兼容副本，供旧导出、UI 摘要和历史 draft 回读使用。
- `/examples/gateway`：右侧 Inspector 顶部有 **Graph Contract** 区块，会显示当前 showcase/composer 的 Input/Output 摘要。

Graph Contract 会同时显示：

- **Input / ctx**：这张 graph 执行前要求的上下文字段。
- **Output / public result**：这张 graph 对系统集成暴露的终态输出字段。

对于 Resource Gateway showcase 示例，Graph Contract 来自 `GatewayGraphContractCatalog`，所以 `User Dashboard`、`Loan Decision Policy`、`Product Detail` 等示例各自有独立的 input/output schema。对于 `/author/` 的 3 个可编辑复杂示例，Graph Contract 定义在 `resource-gateway-examples/src/main/frontend/src/canvasExamples.ts`，并会随 draft 一起导出 `inputSchema`、一等 `outputSchema` 和兼容用 `visualLayout.graphContract.outputSchema`。对于 Legacy DSL，Graph Contract 来自 `.bloge` 文件里的 `input` / `output` 声明。对于 `Custom Composer`，Input 来自当前画布的 `Graph Input Schema`，Output 来自当前 `Graph Output` 选中的输出节点和 path；修改 schema 或切换输出节点后，Graph Contract 摘要会同步刷新。

加载 `Loan policy fallback` 后，Graph Contract 与画布状态会像下图这样联动：

![Author 加载 Loan policy fallback 后的 Graph Contract 标注](assets/bloge-author-loan-example-annotated.svg)

1. **示例元数据**：每个内置示例直接显示节点数、边数、Input 字段数、Output 字段数；点击 Load 会把完整 draft 加载进画布。
2. **运行/导出工具栏**：加载后节点、边、输出节点和 fixture 数会同步刷新，确认当前不是空草稿。
3. **输入输出 schema**：这里就是 graph 级 input/output schema 的可视化入口；例子中输入需要 `applicantId`，输出暴露 `decision`、`tier`、`primaryScore` 等公共结果字段。
4. **节点 mock/real 状态**：右侧 Mock Setup 告诉你哪些节点有 fixture、哪些节点可真实执行、哪些还只是 server sample。
5. **可编辑 DAG**：图不是静态展示，节点、边、output node、fixture 和配置都仍然可以继续编辑。

## 5. `/author/` 怎么用

### 5.1 第一步：准备算子库

最小可用算子库可以是 schema-only 的 design operator。它还没有运行时实现，也能进入画布参与设计、连接校验和模拟。算子库也可以声明 `builtInFunctions`，用于补充 transform/branch 表达式里的业务函数；导入后这些函数会和系统默认函数一起出现在表达式编辑器中。
完整字段合同、lowering 约束和机器校验 schema 见 [BLOGE 可视化算子库 Schema 定义](./bloge-visual-operator-library-schema.md) 与 [bloge-visual-operator-library.schema.json](./schemas/bloge-visual-operator-library.schema.json)。

```yaml
schemaVersion: bloge.visualOperatorLibrary.v1
libraryId: risk-policy
displayName: Risk Policy
version: 1.0.0
operators:
  - operatorRef: risk:eligibility
    display:
      name: Eligibility
      description: Decides whether an applicant is eligible.
      tags: [risk, policy]
    lowering:
      mode: design
    ports:
      inputs:
        - name: inputs
          required: true
          description: Applicant facts.
          schema:
            format: json-schema
            version: "2020-12"
            schema:
              type: object
              properties:
                score:
                  type: integer
                amount:
                  type: number
              required: [score, amount]
      outputs:
        - name: output
          description: Eligibility decision.
          schema:
            format: json-schema
            version: "2020-12"
            schema:
              type: object
              properties:
                eligible:
                  type: boolean
                reason:
                  type: string
              required: [eligible]
```

`lowering.mode: design` 表示它是设计期算子：可以拖拽、连接、保存、导出、模拟，但不能作为真实 request-response 运行时直接执行。未来接入 Java/native/remote worker/AI tool 等执行绑定后，readiness 会变化。

### 5.2 第二步：导入或采用算子

在 `/author/` 左侧的 operator library intake 中粘贴 JSON/YAML：

1. 点击 Validate Library，先让服务端解析和校验合同。
2. 校验通过后点击 Import Library。
3. 画布会刷新 `/api/visual/operators`，新算子进入 palette。
4. palette 可以按 library 分组，也可以通过 source、tag、runtime/design facet 过滤。
5. 使用 Cmd/Ctrl-K 可以快速聚焦搜索框并按关键字过滤。

校验不只是 JSON/YAML 语法检查。服务端还会做 namespace、operatorRef、端口、JSON Schema、lowering、远程 `$ref`、高风险 runtime capability 等检查。warning 需要显式确认时，服务端会在 validation/import response 中返回 readiness 和 diagnostics。

### 5.2.1 直接从内置复杂示例开始

新版 `/author/` 在画布上方内置了复杂编排示例入口。它不是只展示图片或说明文字，而是把一张可编辑的 `GraphDraft` 直接加载到当前画布，包括节点、连线、字段绑定、规则表/转换配置、输出节点和 mock fixtures。

当前内置示例：

| 示例 | 覆盖模式 | 典型学习点 |
| --- | --- | --- |
| Loan policy fallback | 风控 fan-out、双 provider、decision table、response transform | 多资源并行取数、字段级条件绑定、规则表输出进入最终响应 |
| Order fulfillment lane | 订单列表、foreach enrich、shipping quote、SLA decision | 列表 enrichment、资源参数从上游字段派生、履约 lane 规则 |
| Personalized dashboard | 用户画像 fan-out 到钱包、推荐、通知，再聚合成 dashboard | 多资源聚合、最终响应映射、mock resource + real transform 的混合模拟 |

这 3 个示例现在不再只是“看结构”。每个示例都内置了两类资产：

- **Built-in function transform**：最终 `bloge:transform` 使用 `coalesce(...)`、`toNumber(...)`、`round(...)` 这类 BLOGE 表达式函数，展示如何把空值兜底、类型转换和数值规整写进可视化映射。
- **Test Suite cases**：每个示例提供 2 行表格测试。第一行通常是 happy path；第二行通过 fixture override 改变下游 mock 数据，覆盖 decline、standard lane、fallback default 等分支。

如果示例依赖的 operatorRef 不在当前 catalog 中，Load 按钮会禁用并提示缺失数量。此时先导入对应算子库，或确认 resource descriptor / built-in operator catalog 是否已经启动完成。示例加载后会替换当前画布；需要保留当前草稿时，先使用 Export Draft 导出。

### 5.2.2 存量手写 DSL 业务升级路径

还有一类常见业务不是从空白画布开始：业务系统已经集成 BLOGE engine 和 DSL，已经实现了自定义算子与 built-in function，并通过手写 `.bloge` 文件完成业务逻辑。这类团队的升级目标不是“重新拖一遍图”，而是把存量代码库和 DSL 迁移成可视化交付资产。

对通用画布来说，schema 怎么生成不是核心问题，甚至 schema 是否一开始就齐全也不应该成为看图门槛。画布的第一层只关心 DSL 能否被 parser 接收，并从 AST 推演出拓扑、依赖、输入绑定、输出引用和函数调用；第二层才用 operator/function schema 增强精确校验、补全、rewrite gate 和执行能力。存量 BLOGE 业务最推荐的迁移路径如下：

```text
业务代码库
  -> 画布粘贴 .bloge DSL
    -> Render DSL
      -> 先生成 topology-only GraphDraft + source map + diagnostics
        -> 可选导入或 inline 提供 bloge.visualOperatorLibrary.v1
          -> 增强为 schema-backed draft / 精确校验 / rewrite gate
            -> 给出是否允许自动覆盖源 DSL 的 gate 结论
```

这里有两个输入：

| 输入 | 来源 | 作用 |
| --- | --- | --- |
| `bloge-capability-catalog.json` | 业务项目执行 `bloge-maven-plugin:export-schema` 生成，schemaVersion 为 `bloge.capabilityCatalog.v1` | 描述业务 operator、input/output schema、config schema、表达式函数签名和导出诊断 |
| `.bloge` DSL 源码 | 业务项目已有 `src/main/resources/bloge/*.bloge` | 描述真实业务编排逻辑，由官方 DSL parser/compiler 解析后投影为 `GraphDraft` |

`bloge-capability-catalog.json` 不是唯一入口。如果团队已经有手写的 `bloge.visualOperatorLibrary.v1`、平台接口下发的 catalog、OpenAPI/AsyncAPI/resource descriptor 投影后的 visual library，或者其他工具生成的合法 schema，画布都按同一套 validator 接收，并用它增强对应 DSL。更进一步，**schema acquisition 是增强层，不是入场门槛**：没有 operator/function schema 时，服务端仍会从 DSL AST 提取 graph、node、transform、decision table、input binding、node output reference、route/data/dependency edge、built-in function 调用和 graph input/output，先渲染 `topology-only` draft；补齐 schema 后再进入 schema-backed 连线校验、表达式补全、rewrite gate 和执行/发布。

当前状态要分开看：

- 已落地：`/author/` 支持导入 `bloge.visualOperatorLibrary.v1` 和编辑 `GraphDraft`。
- 已落地：后端提供 schema-neutral DSL preview API：`POST /api/visual/dsl-imports/preview`。它接受 `.bloge` 源码、当前已导入的 visual library id，或本次 preview 临时传入的 `inlineLibraries`，然后返回 `GraphDraft + sourceMap + diagnostics + coverage + roundTrip`。没有 operator/function schema 也会返回 topology-only draft；缺失项以 warning diagnostics 和 `draft.visualLayout.import.projectionMode=topology-only` 标识。
- 已落地：后端提供 schema-neutral DSL commit API：`POST /api/visual/dsl-imports/commit`。它接受与 preview 相同的 request，服务端重新投影 DSL，而不是信任浏览器临时 draft，然后保存为 governed `GraphDraft` revision，并返回 validation / dependency report。
- 已落地：浏览器 `/author/` 左侧提供 **Legacy DSL** 面板。用户粘贴 DSL 后点击 `Render DSL`，画布会直接渲染 preview draft，并同步节点、边、Graph Contract、Runtime Context 变量表、Test Suite 初始行和 Export Draft。
- 已落地：用户点击 `Commit Draft` 后，画布会调用 commit API 保存正式 draft；成功后提示 `Stored draft <draftId> @<revision>`，当前 Export Draft 会带上 stored draft identity 和 source map。
- 已落地：如果 Library 面板当前内容是 JSON 形式的合法 `bloge.visualOperatorLibrary.v1`，Render DSL 会把它作为 `inlineLibraries` 随 preview 一起提交；如果已经 Import 入库，则会通过当前 catalog 的 `operatorLibraryIds` 参与解析。
- 已落地：如果 Library 面板当前内容是 `bloge.capabilityCatalog.v1`，点击 `Adapt Catalog` 会调用 `POST /admin/visual-operator-libraries/from-capability-catalog-text`，把 framework export 预览投影为标准 `bloge.visualOperatorLibrary.v1` 草稿并回填到输入框。后续仍然点击 `Validate` / `Import`，因此画布渲染边界没有绑定到 capability catalog。
- 已落地：Legacy DSL 面板会展示 source map 行列表。点击 `node` / `binding` / `edge` 行可以选中对应画布节点，导出的 draft 也会在 `visualLayout.import.sourceMap` 保留源码行列映射。
- 已落地：Legacy DSL preview 会返回 `roundTrip` 状态。服务端会把源 DSL 投影成 draft，再用 `GraphDraftDslGenerator` 生成 DSL、重新解析并再次投影，比较两份 canonical visual semantics。状态会在页面显示为 `SUPPORTED`、`DRIFT`、`PARTIAL` 或 `NOT_ASSESSED`。topology-only draft 通常会停在 `PARTIAL` 或 warning 状态：可用于理解拓扑和迁移审阅，但不能当作自动源码替换或可执行发布证据。
- 已落地：`POST /api/visual/dsl-imports/rewrite-gate` 和 `/author/` 的 `Check Rewrite` 按钮。它复用同一个 schema-neutral request，返回 `ALLOW_REWRITE`、`BLOCK_SEMANTIC_DRIFT`、`BLOCK_INCOMPLETE_EVIDENCE` 等判定和 generated DSL；这个 gate 只做预检，不保存 draft，也不会改写源码文件。
- 已落地：后端提供仓库级批量迁移报告 API：`POST /api/visual/dsl-imports/batch-report`。它复用同一套 schema-neutral catalog/inlineLibraries 输入，但接受多份 `sources[]`，逐份返回 renderable / fullyProjected / needsRepair / rewrite decision，并聚合 coverage、round-trip status、diagnostic level 和 rewrite decision 计数，适合 CI 或迁移前评估。
- 已落地：后端提供批量迁移保存 API：`POST /api/visual/dsl-imports/batch-commit`。它接受与 batch-report 相同的 `sources[]` 和 schema-neutral catalog view，并按 `commitPolicy=renderable|fully-projected|rewrite-allowed` 把合格 source 服务端重投影后保存为 governed draft revision；它不写 `.bloge` 源文件，也不创建 VCS PR。
- 已落地：仓库根目录提供 `scripts/bloge-dsl-batch-import.sh`，迁移负责人可以从命令行或 CI 直接调用 batch-report / batch-commit。脚本支持 `--dsl-dir` 扫描 `.bloge` 文件、`--operator-library` 引用已导入算子库、`--inline-library-json` 临时传入标准 visual library、`--fail-on` 设置 CI gate，以及 `--dry-run` 生成请求体。
- 已落地：React authoring API client 已有 `batchReportDslImports()` / `batchCommitDslImports()` 类型化封装，后续 Studio dashboard 可以直接复用同一 wire contract。
- 未落地：真正写回业务代码库或覆盖原 `.bloge` 文件的 source writer / VCS 集成。当前系统只告诉调用方“是否可安全自动替换”，不直接动用户源码。

设计方案见 [存量 BLOGE DSL 业务迁移到可视化编排设计方案](./bloge-legacy-dsl-visual-migration-design.md)。

从 framework capability catalog 生成 visual library 草稿后，Library 面板会像下图这样展示：

![Capability catalog adapter 标注](assets/bloge-author-capability-adapter-annotated.svg)

1. **Generated visual library draft**：`Adapt Catalog` 成功后，输入框会从 `bloge.capabilityCatalog.v1` 自动回填成标准 `bloge.visualOperatorLibrary.v1`，后续所有画布能力都基于这个标准合同。
2. **Capability catalog example**：内置示例展示了业务代码导出的 framework catalog 形态，适合演示存量业务从代码 schema 进入画布。
3. **Adapt Catalog**：只做 schema acquisition preview，不写入 registry；要正式加入 palette 仍需继续 `Validate` / `Import`。
4. **Adapter result notice**：显示 projected operator/function 数和 coverage；如果有端口 schema 无法投影，会显示 opaque schema fallback 数。

Legacy DSL 面板会像下图这样展示；没有 schema 时先看 topology-only，补齐 schema 后再看 schema-backed：

![Legacy DSL source map 标注](assets/bloge-author-legacy-dsl-source-map-annotated.svg)

1. **Existing .bloge DSL**：这里粘贴或加载存量 `.bloge` 文件。示例中 DSL 声明了 graph 级 `input` / `output`，这些 schema 会进入 Graph Contract。
2. **Render topology first**：点击 `Render DSL` 后，服务端先按 DSL AST 渲染拓扑。没有 operator/function schema 时仍会显示节点、边、输入绑定和 source map，并标记为 `topology-only projection`；导入 schema 后再增强为更精确的 schema-backed draft。`Check Rewrite` 会基于同一份 DSL + schema view 判断 generated DSL 是否可安全替换源文件；`Commit Draft` 会用同一 request 在服务端重新投影并保存为正式 draft revision。
3. **Round trip 状态**：覆盖率数字下方会出现 Round trip 面板。`SUPPORTED` 表示生成 DSL 再解析后仍得到同一份 canonical visual semantics；`DRIFT` 表示生成 DSL 可解析但语义指纹不同；`PARTIAL` 表示生成、解析或投影证据不足，需要先修复诊断。
4. **Source map refs**：source map 会列出节点、输入绑定和数据边对应的 DSL 行列与源码片段，便于迁移审阅。
5. **Click row to select node**：点击 source map 行会选中对应画布节点；如果缺 operator/function schema，系统仍尽量渲染图，并在同一区域显示 diagnostics。

页面上的当前操作方式：

1. 在左侧 **Legacy DSL** 面板确认 `Source`，粘贴 `.bloge` 文件内容，或使用内置 `Eligibility DSL` 示例。
2. 点击 `Render DSL`。服务端解析 DSL，并先按 DSL AST 投影成可读的 `GraphDraft` 拓扑。
3. 如果还没有 operator/function schema，画布会提示 `topology-only projection`。这不是失败：节点、边、输入绑定、函数调用文本和 source map 已可用于理解整体业务逻辑。
4. 需要更精确校验时，再到左侧 **Library** 面板导入或粘贴一份合法 `bloge.visualOperatorLibrary.v1`。如果只是想临时 preview，可以粘贴 JSON 形式 library，不必先 Import。
5. 如果当前输入是业务项目导出的 `bloge.capabilityCatalog.v1`，点击 **Adapt Catalog**。系统会生成标准 `bloge.visualOperatorLibrary.v1` JSON 草稿；检查 notice 中的 operator/function/opaque schema 数后，再点击 `Validate` / `Import`。
6. 画布渲染节点和边；Graph Contract 同步显示 DSL `input` / `output`；Runtime Context 会根据 input schema 生成变量行。
7. 若出现 missing operator/function，画布仍会尽量渲染结构，并在 Legacy DSL 面板显示 warning diagnostics；补齐 schema 后再次 Render。
8. 查看 Round trip 面板：`SUPPORTED` 可作为后续回写的低风险证据；`DRIFT` / `PARTIAL` 说明当前更适合先作为可视化迁移 draft 审阅，不应直接覆盖原 DSL。
9. 点击 `Check Rewrite`。如果返回 `ALLOW_REWRITE`，说明 generated DSL 与源 projection 具有相同 canonical visual semantics，可交给外部源码回写工具继续处理；如果返回 drift/partial/import diagnostic block，不要自动覆盖原 `.bloge`。
10. 在 Source map 中点击行定位节点，确认 DSL 片段和画布元素对应关系。
11. 如果确认迁移结果可作为资产继续协作，点击 `Commit Draft`，把 DSL 投影保存为 stored draft/revision；如果只是临时审阅，可以跳过保存。
12. 继续使用 Auto Layout、Validate、Simulate、Operator Test Suite、全图 Test Suite 和 Export Draft。

对应 API 的最小调用方式：

```http
POST /api/visual/dsl-imports/preview
Content-Type: application/json
```

```json
{
  "sourceId": "loan-approval.bloge",
  "dsl": "graph loanApproval { node eligibility : \"risk:eligibility\" { input { score = ctx.score } } }",
  "catalogIds": ["risk-policy"],
  "inlineLibraries": [],
  "mode": "preview",
  "layout": {}
}
```

保存为正式 draft 时使用同一请求体，只需改调用路径和 `mode`：

```http
POST /api/visual/dsl-imports/commit
Content-Type: application/json
```

```json
{
  "sourceId": "loan-approval.bloge",
  "dsl": "graph loanApproval { node eligibility : \"risk:eligibility\" { input { score = ctx.score } } }",
  "catalogIds": ["risk-policy"],
  "inlineLibraries": [],
  "mode": "commit"
}
```

commit 会返回 `bloge.visualGraphDraftImportResult.v1`，其中 `draft.draftId` / `draft.revision` 是 repository 分配的正式身份，`draft.visualLayout.import.sourceMap` 会保留源码映射。parse failure 或 unsupported root 会拒绝保存；missing operator/function 会保存为可修复迁移 draft，并通过 validation/dependency diagnostics 暴露。

检查 generated DSL 是否可作为源码替换候选时，使用同一请求体，只需改调用路径和 `mode`：

```http
POST /api/visual/dsl-imports/rewrite-gate
Content-Type: application/json
```

```json
{
  "sourceId": "loan-approval.bloge",
  "dsl": "graph loanApproval { node eligibility : \"risk:eligibility\" { input { score = ctx.score } } }",
  "catalogIds": ["risk-policy"],
  "inlineLibraries": [],
  "mode": "rewrite-gate"
}
```

rewrite gate 返回 `bloge.dslRewriteGate.v1`。`allowed=true` / `decision=ALLOW_REWRITE` 表示 generated DSL 可交给外部工具进入源码替换流程；其他 decision 会携带 round-trip 和 diagnostics 说明阻断原因。这个接口不会持久化 draft，也不会直接修改 `.bloge` 文件。

如果要评估一个业务仓库里的多份 DSL，不要循环调用 UI。使用批量报告接口：

```http
POST /api/visual/dsl-imports/batch-report
Content-Type: application/json
```

```json
{
  "catalogIds": ["risk-policy"],
  "inlineLibraries": [],
  "mode": "batch-report",
  "includeDrafts": false,
  "sources": [
    {
      "sourceId": "loan-approval.bloge",
      "dsl": "graph loanApproval { ... }"
    },
    {
      "sourceId": "fraud-review.bloge",
      "dsl": "graph fraudReview { ... }"
    }
  ]
}
```

返回 `bloge.dslImportBatchReport.v1`。重点看：

| 字段 | 含义 |
| --- | --- |
| `summary.renderableSourceCount` | 能成功 parse/project 成 graph draft 的 DSL 数量 |
| `summary.fullyProjectedSourceCount` | 无 import error、无 missing operator/function、无 unsupported syntax 的 DSL 数量 |
| `summary.repairableSourceCount` | 已渲染但需要补 schema 或处理 loss-aware diagnostic 的 DSL 数量 |
| `summary.blockedSourceCount` | parse failure 或 unsupported root，当前不能进入可视化 draft 的 DSL 数量 |
| `summary.rewriteAllowedSourceCount` | 可进入 source replacement 流程的 DSL 数量 |
| `items[].rewriteDecision` | 每个文件的 `ALLOW_REWRITE` / `BLOCK_*` 机器可读结论 |
| `items[].coverage` | 每个文件的 member/node/edge/missing/unsupported 覆盖率 |

如果要把一批可接受的 DSL 直接沉淀成 governed draft，不要让迁移脚本循环调用单文件 commit。使用批量保存接口：

```http
POST /api/visual/dsl-imports/batch-commit
Content-Type: application/json
```

```json
{
  "catalogIds": ["risk-policy"],
  "inlineLibraries": [],
  "mode": "batch-commit",
  "commitPolicy": "renderable",
  "sources": [
    {
      "sourceId": "loan-approval.bloge",
      "dsl": "graph loanApproval { ... }"
    },
    {
      "sourceId": "fraud-review.bloge",
      "dsl": "graph fraudReview { ... }"
    }
  ]
}
```

返回 `bloge.dslImportBatchCommitResult.v1`。重点看：

| 字段 | 含义 |
| --- | --- |
| `summary.committedSourceCount` | 已保存为正式 draft revision 的 DSL 数量 |
| `summary.skippedSourceCount` | 因 parse/root/policy gate 被跳过的 DSL 数量 |
| `summary.failedSourceCount` | 满足保存策略但 repository 写入失败的 DSL 数量 |
| `summary.reportSummary` | 同一批 source 的 render/repair/rewrite readiness 汇总 |
| `items[].commitDecision` | `COMMITTED_*`、`SKIP_*` 或 `FAILED_PERSISTENCE` |
| `items[].importResult` | 单个 source 的 `bloge.visualGraphDraftImportResult.v1`，包含 draft identity、validation 和 dependency report |

`commitPolicy` 的选择：

| 策略 | 何时使用 |
| --- | --- |
| `renderable` | 默认策略。只要 DSL 能渲染成 graph draft 就保存；missing operator/function 会成为可修复迁移 draft |
| `fully-projected` | 只保存无 missing/unsupported/import error 的 DSL，适合迁移验收基线更严格的团队 |
| `rewrite-allowed` | 只保存 rewrite gate 通过的 DSL，适合后续马上接 source replacement / VCS PR 的低风险批次 |

命令行/CI 推荐直接使用批量迁移脚本。先启动服务并导入所需算子库，再运行：

```bash
./scripts/start-visual-canvas-demo.sh --port 18080

./scripts/bloge-dsl-batch-import.sh report \
  --base-url http://localhost:18080 \
  --operator-library risk-policy \
  --dsl-dir resource-gateway-examples/src/main/resources/bloge/gateway \
  --out target/dsl-batch-report.json \
  --fail-on blocked
```

如果迁移策略要求只有语义往返通过的 DSL 才能进入 governed draft，可以改用：

```bash
./scripts/bloge-dsl-batch-import.sh commit \
  --base-url http://localhost:18080 \
  --operator-library risk-policy \
  --dsl-dir resource-gateway-examples/src/main/resources/bloge/gateway \
  --commit-policy rewrite-allowed \
  --out target/dsl-batch-commit.json \
  --fail-on skipped-or-failed
```

脚本不会生成或绑定 schema。它只把已经合法的 schema view 与 `.bloge` 文件打包成同一份
batch API 请求：`--operator-library` 指向已导入的 visual library；
`--inline-library-json` 只接受标准 `bloge.visualOperatorLibrary.v1` JSON 对象；
如果上游手里是 `bloge.capabilityCatalog.v1`，仍需先通过 Library 面板或
`/admin/visual-operator-libraries/from-capability-catalog-text` 适配成 visual library。
调试时可以加 `--dry-run`，先确认最终请求体：

```bash
./scripts/bloge-dsl-batch-import.sh report --dry-run \
  --operator-library risk-policy \
  --dsl resource-gateway-examples/src/main/resources/bloge/gateway/loan-decision-policy.bloge
```

返回重点字段：

| 字段 | 含义 |
| --- | --- |
| `draft` | 可被画布渲染的 `bloge.visualGraphDraft.v1`；普通 node、transform、decision_table 会被投影成可编辑节点 |
| `draft.inputSchema` | DSL graph 级 `input { ... }` schema |
| `draft.outputSchema` | DSL graph 级 `output { ... }` schema；这是 graph 对外集成的正式输出合同 |
| `draft.visualLayout.graphContract.outputSchema` | 输出 schema 的 UI/历史兼容副本；旧 draft 只有这个字段时，前后端会自动回填到一等 `outputSchema` |
| `draft.visualLayout.import.projectionMode` | `schema-backed` 表示 operator/function schema 已绑定；`topology-only` 表示系统只从 DSL AST 推演拓扑、绑定和表达式文本 |
| `draft.visualLayout.import.operatorRefs/functionNames` | 本次 DSL 中扫描到的 operatorRef 与 built-in function 调用名 |
| `draft.visualLayout.import.missingOperatorRefs/missingFunctionNames` | 当前 effective catalog 中缺失的 operator/function；缺失时仍可看拓扑，但不能当作精确 schema 或自动回写证据 |
| `sourceMap.nodes/edges/bindings` | visual 元素到 DSL 行列的映射 |
| `coverage` | member、node、edge、missing operator/function、unsupported syntax 数量 |
| `roundTrip` | 本次 preview 的语义往返证据，包含 `status`、`message`、`generatedDsl`、`sourceFingerprint`、`generatedFingerprint` 和 generation/reparse diagnostics |
| `diagnostics` | parse、missing operator、missing function、unsupported syntax、schema ref 等迁移诊断 |
| `DslRewriteGateResult.allowed/decision/generatedDsl` | `rewrite-gate` 的源码替换预检结论、机器可读阻断原因和本次评估的 generated DSL |

`roundTrip.sourceFingerprint` / `generatedFingerprint` 比较的是 canonical visual semantics：
graph name、`draft.inputSchema`、一等 `draft.outputSchema`、节点输入/config、边和 graph output
selection。坐标、source map、fixtures、描述文本和 `visualLayout.graphContract.outputSchema`
兼容副本不参与语义等价判断。

注意：如果 DSL operatorRef 含有冒号，BLOGE DSL 里要用字符串形式，例如 `node eligibility : "risk:eligibility"`；否则冒号会被 DSL 语法当成节点 id 与 operatorRef 的分隔符。

更完整的迁移专线落地后，还应补齐：

1. 对 missing operator、missing function、opaque schema 或 unsupported syntax 给出更细的修复向导和 source snippet。
2. 需要真正覆盖原 `.bloge` 文件时，把 `rewrite-gate` / `batch-commit` 的 allow/commit 结论接到外部 source writer、VCS PR 或人工 reviewed 流程；不等价或证据不足时不能自动覆盖原文件。
3. 把 resource-gateway 示例里的 capability adapter 沉淀成 BLOGE framework / Studio 可复用的 adapter SPI。

这条路径的关键产品承诺是 **loss-aware import**：能结构化投影的 DSL 会变成可编辑画布节点；暂不支持的复杂语义会保留成带 source snippet 的 opaque 节点或诊断项，不会静默丢失。

### 5.3 第三步：把算子放到画布上

在 palette 中可以点击算子添加，也可以拖拽到 canvas。每个节点卡片会显示：

- 业务展示名和 `operatorRef`。
- 输入/输出端口数量。
- design-only/runtime-blocked/ready 等状态。
- typed handles：输出端口在一侧，输入端口在另一侧。

当画布变乱时，点击 Auto Layout。新版画布会用确定性布局把 DAG 拉开，让节点和边更容易读：同一依赖层内按上游/下游重心排序，列间距会为边标签预留空间，行间距会按节点卡片高度和必要空白展开。它的目标不是把所有节点压到最小面积，而是在保持信息密度的同时避免算子挤在一起、边上的 `source.path -> target.path` 被遮挡。

如果 Auto Layout 后仍需要更大视野审阅拓扑，点击工具条里的 `Canvas Focus`。Focus 模式会收起左右辅助栏、顶部 workflow 和示例卡，保留 toolbar、Graph Contract 与主画布；退出时点击 `Exit Focus`。这个模式适合检查跨层依赖、边标签、复杂 decision table 上下游，以及给业务方演示图结构。

### 5.3.1 配置起始节点输入

起始节点通常没有上游边，但它仍然需要业务入参，例如 `userId`、`orderId`、`applicant.score` 或请求上下文里的租户信息。新版 `/author/` 在右侧 inspector 中提供图形化的 `Runtime Context -> Context Variables`：

![Author Context Variables 绑定标注](assets/bloge-author-context-binding-annotated.svg)

对着图操作：

1. **Graph 输入字段**：先看 Graph Contract 的 Input 区，确认这张图需要哪些 ctx 字段。示例中 graph input 需要 `applicantId`。
2. **Context 变量表**：在 `Runtime Context -> Context Variables` 点击 Add Variable，新增一行变量，Path 填 `applicantId` 或 `applicant.score` 这类上下文路径。
3. **Bind 到节点输入**：选中需要配置输入的节点，再点击变量行上的 Bind；也可以把 `ctx.applicantId` chip 直接拖到右侧 inspector 或双击浮层里的 `Node Inputs` 区域。
4. **Preview JSON**：Sample 值会即时汇总成最终模拟 context，图中生成的是 `{ "applicantId": "prime" }`。
5. **起始节点/字段来源**：画布中的起始节点可以从 ctx 字段获得输入，不必为了“没有上游边”再造一个假节点。

画布会自动创建 `contextPath` 输入绑定，并把 Target port 默认设为算子的第一个输入端口、Target path 默认设为上下文路径最后一段。如果需要常量或复杂目标字段，仍可在 `Node Inputs` 中手动调整 Source、Target port 和 Target path。对起始节点，推荐直接双击节点打开 Operator Detail，在同一个浮层里完成关键属性、输入绑定、输出样例和 schema 对照。

例如，一个风控起始节点要从运行上下文读取 `applicant.score`，导出的 draft 会包含：

```json
{
  "inputs": {
    "score": {
      "kind": "contextPath",
      "path": "applicant.score",
      "targetPort": "inputs",
      "targetPath": "score"
    }
  }
}
```

模拟时，`Context Variables` 会生成本次 run 的 JSON context，例如：

```json
{
  "applicant": {
    "score": 720
  }
}
```

`Runtime Context` 会进入 `POST /api/visual/graphs/simulate` 的 `context` 字段；它不会写进导出的 `GraphDraft`。导出的 draft 只保存 `contextPath` / `constant` 等输入绑定语义，方便后续在真实网关运行时由外部请求上下文提供变量。

`Advanced JSON` 仍然保留给专家模式。没有配置 Context Variables 时，模拟会使用 `Advanced JSON` 中的对象；一旦配置了变量，模拟优先使用变量表生成的 context。

### 5.4 第四步：连线

从一个节点的输出 handle 拖到另一个节点的输入 handle。拖拽过程中，画布会调用：

```text
POST /api/visual/connections/candidates
```

服务端返回哪些目标 ready、哪些 blocked、哪些 already wired。真正落线时再调用：

```text
POST /api/visual/connections/check
```

只有服务端 accepted 的连接会写入 draft。这样可以避免浏览器本地规则和后端 validator 分叉。

常见 blocked 原因：

- 输出 schema 不能赋给目标输入 schema。
- 目标 required input 已经被别的边占用。
- path/port 名称不是 DSL-safe。
- draft 里存在阻断级诊断，导致连线后图仍不可用。

### 5.4.1 双击查看算子详情与专属编辑

新版 `/author/` 的双击行为统一了：**每个画布节点都可以双击打开 Operator Detail 浮层**。浮层不只是“查看详情”，也承担节点级编辑入口：能直接改关键属性、配置 input binding、维护 output/expected input 样例、管理该节点独立的 Operator Test Suite，再根据算子族展开专属编辑器。

![Author Operator Detail 浮层标注](assets/bloge-author-operator-detail-annotated.svg)

1. **任意节点双击**：resource、http resource、foreach、decision table、transform、用户导入的 design operator 都走同一个详情浮层入口。
2. **关键属性可编辑**：所有节点都能改显示 label；resource/http 节点还提供 Resource ID、Method、URL/route、Timeout 等常用运行属性输入框，写回节点 `config`。
3. **图形化输入绑定**：浮层内置 `Node Inputs`，可以 Add Binding、选择 `ctx` 或 `constant`、配置 Target port/path，也能接收 Runtime Context 变量 chip 拖拽。
4. **Input/Output 样例**：Output sample 和 Expected input 可以在浮层内直接维护，写入 `GraphDraft.nodeFixtures`，用于 mock simulate 和表格测试。
5. **Operator Test Suite**：每个节点都有自己的表格测试数据，按行维护 Input case 和 Output sample；点击 Apply Fixture 会把该行套用为当前节点的 Expected input / Output sample，用来验证该算子的合同和 mock 行为。
6. **Schema 摘要优先**：每个端口先显示 schema 类型、字段数和字段表；Raw schema 仍可展开查看，避免用户一上来就读大段 JSON。
7. **专属交互区**：decision table 和 transform 会在同一浮层内展开可编辑区域；foreach 会展开循环向导；generic/design operator 保留高级 config JSON 入口。

| 算子族 | 双击后的浮层能力 | 写入 draft 的配置 |
| --- | --- | --- |
| `bloge:decisionTable` | 详情 + 规则矩阵 + 节点级 Operator Test Suite。可编辑 hit policy、output type、条件列、输出列、规则行和 otherwise fallback | `config.hitPolicy`、`config.outputType`、`config.conditionColumns`、`config.outputColumns`、`config.rules[]`、`nodeFixtures[nodeId]` |
| `bloge:transform` | 详情 + 字段映射表 + 节点级 Operator Test Suite。可编辑输出字段名和 BLOGE 表达式，可新增/删除 assignment，并在 Expression 下方使用函数 chip、函数名补全和签名提示 | `config.assignments`、`nodeFixtures[nodeId]` |
| `__foreach__:*` | 详情 + Loop guide + 节点级 Operator Test Suite。按 `Bind collection -> Run per item -> Collect result list` 展示循环语义，帮助用户理解 array 输入、item context 和 list 输出 | 通常由 operator contract / runtime 定义；测试行可套用为 `nodeFixtures[nodeId]` |
| resource / http operator | 详情 + 可编辑 Resource ID、Method、URL/route、Timeout、Node Inputs、Input/Output samples、节点级 Operator Test Suite、schema 摘要和高级 config JSON | `config.resourceId/method/url/timeoutMs`、`inputs.*`、`nodeFixtures[nodeId]` |
| generic / design operator | 详情 + label、Node Inputs、Input/Output samples、节点级 Operator Test Suite、schema 摘要和高级 config JSON | `label`、`inputs.*`、`config`、`nodeFixtures[nodeId]` |

Operator Test Suite 和右侧全图 Test Suite 的边界不同：

| 测试入口 | 粒度 | 主要数据 | 用途 |
| --- | --- | --- | --- |
| Operator Detail 内的 Operator Test Suite | 单个节点/算子 | Input case、Output sample | 沉淀该算子的局部验证样例，并一键套用为节点 fixture |
| 右侧 inspector 的 Test Suite | 整张 graph | Runtime context、fixture overrides、Expected graph output | 批量验证端到端编排路径和最终业务结果 |

因此，当你只想确认某个 http resource、transform 或 decision table 节点“收到什么输入、应该吐出什么样例”时，优先在双击浮层里维护 Operator Test Suite；当你要验证整张图的 happy path、fallback path 或分支组合时，再进入右侧全图 Test Suite。

Decision table 双击后的页面重点如下：

![Author Decision Table 浮层编辑器标注](assets/bloge-author-decision-table-editor-annotated.svg)

1. **双击浮层编辑器**：双击 `bloge:decisionTable` 节点后打开，不需要在右侧 inspector 里找隐藏 JSON。
2. **来自传入边的条件列**：`score`、`income`、`employmentYears` 这类列来自上游边绑定，会以锁定列展示，避免规则表和边上的数据合同脱节。
3. **输出列**：规则命中后产出的结构化字段，例如 `decision`、`tier`、`reason`。
4. **规则行/otherwise**：每一行是一条匹配规则；otherwise 行作为 fallback，条件单元格禁用，只保留输出编辑。
5. **Done 保存到节点**：点击 Done 后，表格配置写回当前节点的 `config`，画布节点上的 input/output 数量也会同步刷新。

Decision table 的规则矩阵支持“加行”和“加列”：

1. 先把上游节点输出连到 decision table 的输入字段，例如连到 `inputs.score`。
2. 双击 decision table 后，规则矩阵会把传入边暴露为锁定条件列，例如 `score`。锁定列可以填写规则表达式，但不能改名或删除，因为列名就是后端 DSL 使用的 input key。
3. 点击 Add Condition Column 增加手工条件列，例如 `segment`、`amount`。
4. 点击 Add Output Column 增加输出列，例如 `tier`、`reason`。
5. 在规则行中填写每个条件表达式，例如 `score >= 700`。
6. 在输出列中填写匹配后的结构化结果，例如 `decision=approve`、`tier=platinum`。
7. 勾选 Otherwise 的行会作为 fallback，条件列会禁用，只保留输出编辑。

导出的 draft 会保持 schema-friendly 结构，而不是把整张表压成一段不可解析字符串：

```json
{
  "inputs": {
    "score": {
      "kind": "nodePath",
      "nodeId": "riskScore",
      "sourcePort": "decision",
      "path": "score",
      "targetPort": "inputs",
      "targetPath": "score"
    }
  },
  "config": {
    "hitPolicy": "unique",
    "outputType": "{ decision: String, ruleId: String, tier: String }",
    "conditionColumns": ["score"],
    "outputColumns": ["decision", "ruleId", "tier"],
    "rules": [
      {
        "conditions": {
          "score": "score >= 700"
        },
        "output": {
          "decision": "approve",
          "ruleId": "prime",
          "tier": "platinum"
        }
      },
      {
        "otherwise": true,
        "output": {
          "decision": "fallback",
          "ruleId": "otherwise",
          "tier": ""
        }
      }
    ]
  }
}
```

Transform 映射表则会导出为：

```json
{
  "config": {
    "assignments": {
      "tier": "inputs.score >= 700 ? \"prime\" : \"standard\"",
      "reason": "coalesce(inputs.reason, \"score policy\")"
    }
  }
}
```

Transform 浮层中的 Expression 输入框来自 `GET /api/visual/operators` 下发的 `builtInFunctions`：

1. 双击 `bloge:transform` 节点，打开 `Transform mapping` 浮层。
2. 在某一行 assignment 的 Expression 输入框下方，点击 `coalesce`、`jsonPath`、`round` 等函数 chip，系统会把调用片段插入当前表达式。
3. 当表达式为空时，签名提示会显示常用函数；当输入里出现 `coalesce(` 这类函数调用时，提示区会聚焦对应 signature。
4. 点击 Done 后，函数调用文本会作为普通 BLOGE 表达式写入 `config.assignments`，后续 validate、simulate、export 都读取同一份配置。

当前系统默认函数包括：

| 函数 | 典型用途 |
| --- | --- |
| `coalesce(value, fallback)` | 空值兜底，例如主评分缺失时使用备用评分 |
| `defaultIfBlank(text, fallback)` | 文本为空或 blank 时兜底 |
| `toNumber(value)` / `toString(value)` | 标量类型转换 |
| `jsonPath(object, path, fallback?)` | 从 object 中按路径读取字段 |
| `contains(collection, candidate)` | 判断字符串或集合是否包含某值 |
| `round(value, scale?)` | 数值四舍五入 |
| `formatDate(value, pattern)` | 日期/时间格式化 |

Foreach 的浮层不是另一个隐藏 JSON 编辑器，而是一个循环解释器：

1. **Bind collection**：告诉用户应该把上游 array 接到哪个 input port。
2. **Run per item**：说明每个 item 在运行期会成为单次处理的 item context。
3. **Collect result list**：说明 foreach 的输出仍是 array，供下游 transform、decision 或 resource 节点继续消费。

这解决了过去的问题：用户看到 `__foreach__:enrichOrders` 时不知道“循环在哪里发生”。现在双击节点即可看到集合输入、单项上下文、结果列表三段语义。

### 5.5 第五步：选择输出节点

选中节点后，在 inspector 中使用 Set Output。GraphDraft 的 `output.nodeId` 决定 validate、simulate、export 时哪个节点代表整张图的业务结果。

如果不选输出节点，系统无法判断哪些节点是有效业务链路、哪些只是旁路草稿，因此 Validate 会给出缺失 output 的诊断。

### 5.6 第六步：配置 fixture

对 design-only 或尚未实现的算子，模拟时需要 mock output。新版画布同时在 selected-node inspector 的 Simulation 区域和双击 Operator Detail 浮层里提供 fixture 编辑：

- Output fixture：指定该节点模拟时产出的值。
- Expected input：断言该节点模拟时应该收到的输入。
- Use Sample：根据输出 JSON Schema 生成一个确定性样本。

样本生成顺序是：

```text
用户 fixture
  -> schema examples/default/const/enum[0]
    -> 确定性 canonical sample
```

fixture 会写入 `GraphDraft.nodeFixtures`，属于 authoring/test evidence，不改变 DSL、fingerprint 或生产执行语义。

### 5.6.1 用 Test Suite 做批量 mock 回归

单次 Simulate 适合调试一条路径，但复杂业务编排不能只靠一条样例证明正确。新版 `/author/` 在右侧 inspector 中保留 **Test Suite** 摘要按钮，点击后打开浮层表格，把大规模系统化验证前移到画布内，同时避免右侧栏被大表格挤爆。

![Author Test Suite 浮层表格标注](assets/bloge-author-test-suite-dialog-annotated.svg)

1. **右栏轻量入口**：inspector 只显示 case 数、最新运行状态和 `Test Suite` 按钮。
2. **浮层表格**：点击按钮后打开完整表格，可批量编辑、运行、清理测试结果。
3. **行级验证数据**：每一行都包含 Context、Fixture Overrides、Expected Output；运行失败时展示 Actual Output。

每一行 Test Suite 都包含：

| 字段 | 作用 |
| --- | --- |
| Case name | 业务路径名称，例如 `Prime approval path` 或 `Fallback defaults` |
| Context | 本行传给 `POST /api/visual/graphs/simulate` 的 runtime context |
| Fixture Overrides | 本行覆盖节点级 Mock Setup 的 fixture，格式是 `{ "nodeId": { "output": ..., "expectedInput": ... } }` |
| Expected Output | 本行断言的 graph terminal output；留空时只要求 simulate 成功 |

资源节点的 fixture 要模拟完整资源输出，而不是只写 payload 本体。例如资源边和 transform 表达式通常读取 `n1.output.payload.score`，因此覆盖行应写成 `{ "n1": { "output": { "payload": { "score": 650 } } } }`。普通 primitive、transform 或 decision 节点则按节点真实输出结构填写。

使用方式：

1. 加载任意内置复杂示例，例如 `Loan policy fallback`。
2. 在右侧 `Test Suite` 摘要卡查看系统预置的 2 行 case。
3. 点击 `Test Suite` 打开浮层表格。
4. 需要新增路径时点击 `Add Case`，填写新的 context、fixture overrides 和 expected output。
5. 点击 `Run Table`。画布会逐行调用 transient simulate endpoint，并把每行状态标成 `pending/running/passed/failed`。
6. 如果某行失败，结果区会显示实际 output 和 expected output，便于判断是 mock 数据、decision table 规则、transform 函数还是预期断言错了。
7. 批量运行后，最后一行的 run trace 会同步到画布节点 badge 和 Result 面板，因此仍可沿 DAG 排查 real/mocked 节点。

Fixture 合并顺序是：

```text
Mock Setup 基础 nodeFixtures
  -> Test Suite 当前行 fixtureOverrides
    -> 本行 simulate request
```

这使作者可以把“共用的下游 mock 数据”放在节点 Simulation 区，把“某条业务路径特殊的 mock 变化”放在表格行里。工业化测试的关键就在这里：大部分复杂场景不需要真实下游 API，也能稳定跑大量路径验证，避免测试环境被外部系统状态、限流、网络和脏数据拖垮。

Test Suite 是画布内的 authoring-side transient runner。需要把测试资产治理起来时，使用后端已经落地的 schema-gated suite/golden 能力：

| 层级 | 入口 | 用途 |
| --- | --- | --- |
| 画布内调试 | `/author/` Test Suite | 作者快速构造路径、调试 mock、验证 transform/decision/foreach 编排逻辑 |
| Resource graph suite | `/api/gateway/graphs/contracts/tests/*` | 对正式 resource graph 按 input/output schema、resource mock 和 coverage policy 批量验证 |
| Operator suite | `/api/visual/operators/tests/*` | 对单个 operator 的 input/config/output schema 和 mock output 断言做表格验证 |
| Published golden | `/api/visual/golden-cases/*` | 对不可变 publication 做发布级回归和认证 |

更完整的后端表格测试模型见 [Resource Graph Schema Mock Table Testing](./bloge-resource-graph-schema-mock-table-testing.md)。

### 5.7 第七步：Validate

点击 Validate 后，前端调用：

```text
POST /api/visual/drafts/validate
```

结果中最重要的是三类信息：

- `valid`：合同和图结构是否通过。
- `readiness`：当前图整体是 executable、design-only、runtime-blocked 还是 catalog-repair required。
- `actionReadiness`：compile/run/publish design/publish executable 当前能不能做。

理解方式：

| 状态 | 说明 |
| --- | --- |
| Ready/valid | 图结构和 schema 约束通过，可以继续模拟或发布路径 |
| Design-only | schema 正确，但包含未绑定 runtime 的 design operator，只能作为设计资产或通过 simulate 验证 |
| Runtime-blocked | 存在 remote worker、AI tool、event source、message handler、webhook、streaming/durable 等当前 request-response runtime 不支持的边界 |
| Catalog repair required | 算子库或 operator projection 本身存在阻断问题，需要先修 catalog |

### 5.8 第八步：Simulate

点击 Simulate 后，前端调用：

```text
POST /api/visual/graphs/simulate
```

模拟不是生产运行。它的目标是验证编排逻辑、schema 形状、mock 输出传播、节点 trace 和终端输出是否符合预期。

系统采用 hybrid strategy：

- 安全、确定性、已实现的内置 DSL primitive 可以真实执行。
- 用户导入的 design-only operator、未绑定 runtime 的 operator、高风险副作用 operator 会用 `SimulationOperator` mock。
- 每个节点 trace 都会标记 `REAL` 或 `MOCKED`。
- 输出节点会额外标记 `OUTPUT`。

这能避免两个极端：一边是“所有东西都 mock 导致 transform/branch 逻辑没验证”，另一边是“设计期模拟误触真实外部副作用”。

模拟完成后，页面重点看这几个位置：

![Author 模拟结果标注](assets/bloge-author-simulation-result-annotated.svg)

1. **Simulate 成功**：顶部状态卡和工具栏会从 `not run` 变成 `success`。
2. **Run/Trust 检查**：Checklist 会显示 Run 是否成功，以及当前结果里有多少 real-run / mocked 节点。
3. **Mocked/Real 节点状态**：Mock Setup 区会按节点列出 `MOCKED` 或 `REAL`，方便判断哪些结果来自 fixture，哪些来自真实 transform/decision 执行。
4. **Graph ready 卡片**：画布左下角给出下一步行动提示；成功时会提示 graph ready，但仍标明 mocked 节点是否存在。
5. **节点徽标同步**：画布节点上的 badge 会同步显示 real/mock 状态，便于沿着 DAG 追踪模拟路径。

### 5.9 第九步：Export

当前 `/author/` 支持本地导出 draft JSON，包含：

```json
{
  "schemaVersion": "bloge.visualGraphDraft.v1",
  "graphName": "customGraph",
  "nodes": [],
  "edges": [],
  "nodeFixtures": {},
  "output": {
    "nodeId": "selectedNode"
  }
}
```

更完整的服务端资产流还包括：

| 资产 | API |
| --- | --- |
| 内置算子库导出 | `GET /api/visual/builtin-library/export` |
| 指定用户算子库导出 | `GET /admin/visual-operator-libraries/{libraryId}/export` |
| 用户算子库 bundle 导入 | `POST /admin/visual-operator-libraries/import-bundle` |
| Draft export/import | `GET /api/visual/drafts/{draftId}/export`, `POST /api/visual/drafts/import` |
| Publication export/import | `GET /api/visual/publications/{publicationId}/export`, `POST /api/visual/publications/import-bundle` |
| Golden case | `/api/visual/golden-cases/*` |

## 6. `/showcase/` 怎么用

`/showcase/` 是面向 resource gateway 示例的 React 场景目录，不是通用 authoring 工作台。它用于证明后端 resource gateway 场景、图、请求和 SSE 行为仍然可用。

页面重点如下：

![Showcase Loan Decision Policy 标注](assets/bloge-showcase-loan-policy-annotated.svg)

1. **场景目录**：左侧按后端返回顺序列出 resource gateway 示例，适合演示时快速切换 `User Dashboard`、`Loan Decision Policy`、`Product Detail` 等场景。
2. **场景说明/标签**：顶部展示业务模式、标签和解释文案，用于讲清这个 graph 证明了什么能力。
3. **后端 graph 图**：中间 Diagram 是后端示例 graph 的可视化，不是可编辑 canvas；它用于解释运行路径和节点关系。
4. **节点 Inspector**：点击图中的节点后，右侧显示 node kind、operator、payload、resourceId 等后端合同信息。
5. **运行输入**：下方 Sample Input/Run 区用于选择 preset、编辑请求参数、执行真实 gateway endpoint，并查看 expectation matched/missing。

使用方式：

1. 打开 `/showcase/`。
2. 从左侧场景列表选择一个示例。
3. 查看场景说明、运行参数、示意图和节点摘要。
4. 编辑 sample input。
5. 对普通请求点击 Run，系统会调用对应 public gateway endpoint。
6. 对 streaming 场景使用 SSE lane，必要时点击 Stop。
7. 查看 preset expectation matched/missing 反馈，判断演示输出是否符合预期。

它消费的核心 API：

```text
GET /api/gateway/examples/scenarios
GET /api/gateway/examples/scenarios/{graphName}
GET /api/gateway/examples/scenarios/{graphName}/diagram
```

## 7. 系统架构说明

![BLOGE 通用可视化编排系统架构](assets/bloge-visual-canvas-architecture.svg)

图源文件：[`assets/drawio/bloge-visual-canvas-architecture.drawio`](assets/drawio/bloge-visual-canvas-architecture.drawio)

### 7.1 前端职责

`resource-gateway-examples/src/main/frontend` 提供同一套 Vite/React bundle，并在 Spring Boot 打包时复制到 `/author/` 和 `/showcase/` 两个静态入口。

前端负责：

- React Flow 渲染和拖拽体验。
- Palette 搜索、分组、filter 和 Cmd/Ctrl-K。
- Node inspector、fixture 编辑、output 选择。
- Test Suite 浮层表格、fixture override 合并和逐行 transient simulate 调度。
- 调用服务端候选连接、连线确认、validate、simulate。
- 展示 readiness、diagnostics、trace、real/mocked badge。
- 解析 draft/node/operator/run/gate issue Deep Link，并回显 ANEKE gate freshness 与阻断原因。
- 导出本地 draft JSON。

前端不负责：

- 私自判定连接一定有效。
- 私自决定 draft 是否可运行。
- 私自相信用户导入的 runtime readiness。
- 在浏览器里直接执行 graph 或替代服务端模拟语义。
- 真实执行 design-only 或高风险 operator。

### 7.2 后端职责

后端 `visual/*` 包是核心：

| 后端模块 | 职责 |
| --- | --- |
| `visual/catalog` | operator catalog、算子库导入/导出、builtin library projection、profile、impact、revision |
| `visual/connection` | 服务端连接候选和连接预检 |
| `visual/importer` | schema-neutral `.bloge` DSL preview/commit/rewrite-gate/batch-report/batch-commit import，投影并保存 `GraphDraft`，输出 source map、coverage、round-trip、source replacement gate diagnostics、仓库级迁移 readiness report 和批量 governed draft 保存结果 |
| `visual/validation` | GraphDraft 合同、schema、runtime/design readiness、action readiness |
| `visual/simulation` | mock/real 混合模拟、fixture、trace、sample generator |
| `visual/publication` | publication 冻结、导入导出、依赖报告 |
| `visual/golden` | golden case 保存、运行、认证 |
| `visual/runtime` | `VisualGraphRunRecord.v7`、精确 node invocation attempt、结构化 node/run-control/recovery fact、pre-run 脱敏 lineage reservation、自动 evidence recovery、持久 evidence seal 和 trace/replay 数据 |
| `visual/resource` | OpenAPI/resource contract 投影到 visual resource/operator surface |
| `integration` | Tool Studio versioned envelope、capability probe、draft dependency export、evidence/replay、验签公钥、governance gate feedback、transactional outbox、签名 cursor 和 reconciliation snapshot |

resource gateway 自身继续保留：

- `HttpResourceOperator`：通用 HTTP resource 集成点。
- `ResourceDescriptor`：资源声明和参数映射。
- gateway example controllers：对外演示接口。
- DSL graphs：业务示例编排。

### 7.3 数据不变量

系统里有几个必须坚持的不变量：

1. `GraphDraft.visualLayout` 和 `nodeFixtures` 都是 authoring/test evidence，不定义生产业务语义。
2. `GraphDraft.output` 是图级结果选择，不能让前端隐式猜。
3. 连接写入前必须经过服务端 preflight。
4. `lowering.mode=design` 的 operator 可以设计和模拟，但不能冒充 executable operator。
5. 模拟 trace 必须明确标记 real/mocked，不能让 mock 输出看起来像真实生产结果。
6. 用户导入的 operator runtime readiness 不能被直接信任，服务端会重新派生。
7. 远程 `$ref`、不安全 schema、秘密字段、外部副作用和高风险 runtime capability 必须被 warning-gate 或 blocking-gate。
8. evidence 缺节点输入/输出或签名不可验证时必须进入 `QUARANTINED`，不能被 publish gate 当作可采纳证据。
9. governance gate result 必须绑定不可变 draft fingerprint；画布必须区分 `CURRENT`、`STALE`、`EXPIRED` 与 `MISSING`。
10. draft/operator/run/contract suite 的权威写入与 change event 必须同事务提交；Outbox 失败时资产也必须回滚。
11. event cursor 必须绑定 tenant/environment、签名并过期；客户端不能把数据库 offset 当协议。
12. Polling 或未来 webhook 都不是权威源；消费端漂移最终必须由 reconciliation snapshot 发现并收敛。
13. managed run 的 normal completion 与 recovery sweeper 必须锁定同一 reservation；run record、reservation
    终态和 integration event 必须原子提交，失败时三者一起回滚。

## 8. 典型业务流程

### 8.1 先设计、后实现

适合业务还没完全落地，但 schema 已经比较清楚的场景。

1. 平台或业务团队定义 `bloge.visualOperatorLibrary.v1`。
2. operator 使用 `lowering.mode=design`。
3. 在 `/author/` 导入 library。
4. 拖拽形成业务 DAG。
5. 设置 output node。
6. 为关键节点补 fixture。
7. Validate + Simulate。
8. 导出 draft，作为后续 runtime binding/工程实现输入。

这种模式的价值是：业务流程和数据合同可以先稳定下来，不必等所有 Java/operator/runtime 实现完。

### 8.2 资源网关场景演示

适合讲 resource gateway 能力。

1. 打开 `/showcase/`。
2. 选择 dashboard、product、order、credit、streaming 等场景。
3. 查看图和节点。
4. 调整 sample input。
5. 运行请求或 SSE stream。
6. 用 expectation 反馈说明结果。

### 8.3 把内置 registry 变成可移植 library

适合做环境迁移或示例复制。

1. 调用 `GET /api/visual/builtin-library/export`。
2. 拿到 portable bundle。
3. 在另一个环境通过 import bundle 导入。
4. 刷新 catalog，在画布中使用这些 operator。

### 8.4 从 OpenAPI/AsyncAPI 投影设计面

已有后端支持从协议文档生成 visual contract：

- OpenAPI resource contract：`POST /admin/resource-design-contracts/from-openapi`
- AsyncAPI operator library：`POST /admin/visual-operator-libraries/from-asyncapi`
- AsyncAPI operation discovery：`POST /admin/visual-operator-libraries/from-asyncapi/operations`

这些入口不会绕过 validator。未解析本地 `$ref`、远程 `$ref`、selector 未命中、blocked operation 都会被服务端诊断拦截。

## 9. 主要 API 速查

除 `GET /api/integration/capabilities` 和 evidence verification key 外，integration API 均要求 `Authorization: Bearer ...` 和与 operation 匹配的 `X-Purpose`。租户、组织、环境和 actor 以 resolver 返回的服务端 claims 为准。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/api/visual/operators` | 加载 operator catalog |
| `GET` | `/api/visual/operators/{operatorRef}` | 查看单个 operator detail |
| `POST` | `/api/visual/operators/fit-candidates` | 根据当前输出找可添加的候选 operator |
| `POST` | `/admin/visual-operator-libraries/validate-text` | 校验粘贴的算子库 JSON/YAML |
| `POST` | `/admin/visual-operator-libraries/import-text` | 导入粘贴的算子库 JSON/YAML |
| `POST` | `/admin/visual-operator-libraries/from-capability-catalog-text` | 将 `bloge.capabilityCatalog.v1` JSON/YAML 预览适配为标准 visual operator library 草稿，不自动存储 |
| `GET` | `/admin/visual-operator-libraries/{libraryId}/export` | 导出指定用户算子库 |
| `POST` | `/admin/visual-operator-libraries/import-bundle` | 导入算子库 bundle |
| `GET` | `/api/visual/builtin-library/export` | 导出内置 operator registry 为 portable library |
| `POST` | `/api/visual/dsl-imports/preview` | 以 schema-neutral 方式把 `.bloge` DSL + 当前 catalog/inline libraries 投影为 visual `GraphDraft` preview |
| `POST` | `/api/visual/dsl-imports/rewrite-gate` | 以同一 schema-neutral request 判断 generated DSL 是否可安全替换源 DSL；只返回 gate 结论，不持久化、不写源码 |
| `POST` | `/api/visual/dsl-imports/batch-report` | 以同一 schema-neutral catalog view 批量评估多份 DSL 的 render/repair/rewrite readiness 和覆盖率 |
| `POST` | `/api/visual/dsl-imports/batch-commit` | 按 `renderable` / `fully-projected` / `rewrite-allowed` 策略批量保存可接受 DSL projection 为 governed draft revision；不写源码 |
| `POST` | `/api/visual/dsl-imports/commit` | 以同一 schema-neutral request 重新投影 DSL，并保存为 governed stored draft revision |
| `POST` | `/api/visual/connections/candidates` | 枚举连接候选 |
| `POST` | `/api/visual/connections/check` | 预检单条连接 |
| `POST` | `/api/visual/drafts/validate` | 校验 transient draft |
| `GET` | `/api/visual/drafts/{draftId}` | 读取存量 draft，供 Author Deep Link 恢复画布 |
| `POST` | `/api/visual/graphs/simulate` | 模拟 transient draft |
| `GET` | `/api/visual/runs/{runId}` | 读取运行记录并恢复 draft/run Deep Link 上下文 |
| `GET` | `/api/visual/governance-gates/drafts/{draftId}` | Author 只读获取最新 ANEKE gate result 及快照新鲜度 |
| `GET` | `/api/integration/capabilities` | 查询 Tool Studio 协议版本、对象版本、端点和 feature flags |
| `GET` | `/api/integration/drafts/{draftId}/export` | 导出带依赖 fingerprint 的治理集成 bundle |
| `GET` | `/api/integration/runs/{runId}/evidence` | 导出带节点/边事实、完整性状态和持久签名的 evidence bundle |
| `GET` | `/api/integration/runs/{runId}/replay` | 读取经脱敏的 recorded replay payload；当前不触发外部副作用 |
| `POST` | `/api/integration/runs/{runId}/replay` | 以 `DENY` 副作用策略重算 recorded payload 断言，生成带 parent lineage 的新 replay run/evidence |
| `GET` | `/api/integration/evidence-keys/{keyId}` | 获取 evidence seal 验签公钥 |
| `POST` | `/api/integration/gate-results` | ANEKE 回写绑定 draft fingerprint 的治理门禁结果 |
| `GET` | `/api/integration/events` | 按签名 opaque cursor 拉取固定 high-water 事件窗口；仅允许 `CHANGE_SYNC` purpose |
| `GET` | `/api/integration/reconciliation` | 在一致数据库快照上返回租户/环境权威资产清单、计数、rolling fingerprint 和 checkpoint cursor |
| `GET` | `/api/integration/operator-libraries/{libraryId}` | 按可选 revision 获取事件引用的 operator library 不可变快照 |
| `GET` | `/api/integration/operator-test-suites/{suiteId}` | 按可选 revision 获取事件引用的 contract test suite 不可变快照 |
| `GET` | `/api/gateway/examples/scenarios` | showcase 场景列表 |
| `GET` | `/api/gateway/examples/scenarios/{graphName}/diagram` | showcase 场景图 |

## 10. 常见问题

**打开 `/author/` 是 404。**

大概率没有执行 `-Pfrontend package`，React 产物没有复制到 Spring Boot static resources。使用打包命令后再用 jar 启动。

**palette 为空。**

先确认 `/api/visual/operators` 是否有返回。若只有用户自定义算子，先在 library intake 导入算子库。若使用 deprecated library，默认 palette 会隐藏，需通过相应 catalog 参数或存量 draft 解析路径查看。

**算子库 Validate 失败。**

看第一条 blocking diagnostic。常见原因是 `schemaVersion` 不对、`libraryId` 缺失、`operatorRef` 冲突、port schema 格式不符合、远程 `$ref` 被拒绝、local `$ref` 无法解析、lowering mode 和字段不安全。

**拖线时目标是 blocked。**

看连接候选或 check response 的 message。多数是 schema 不兼容、目标 input 已被占用、path 不安全或目标 operator 当前不可用于该 scope。

**Validate 通过但 Run 不允许。**

这通常是 design-only 或 runtime-blocked。它说明 schema 编排成立，但当前 request-response runtime 没有真实执行绑定。此时使用 Simulate 验证逻辑，或补 runtime binding 后再发布 executable artifact。

**Simulate 结果都是 MOCKED。**

如果图里都是用户导入的 design-only operator，这是预期行为。mock 结果来自 fixture 或 schema sample。要看到 REAL，需要图中包含 allowlist 内、安全、确定性且已实现的内置 operator。

**Simulate 报 fixture JSON 无效。**

修正 selected-node Simulation 区域里的 Output fixture 或 Expected input。无效 fixture 不会发送到服务端，避免生成误导性的模拟证据。

**用 `npm run dev` 时导入算子库失败。**

当前 Vite dev proxy 只覆盖 `/api`，而导入算子库走 `/admin`。完整体验使用 Maven 打包后的 `/author/`，或在本地调试时补充 `/admin` proxy。

**ANEKE 链接打开后显示 target 不存在。**

先确认链接里的 `draftId` 指向哪个 revision。节点重命名、删除或 operator replacement 后，旧 `nodeId`、`operatorRef` 或 gate issue `targetPath` 可能已经失效；画布会继续打开草稿，但以 warning 显示未命中的目标。若 gate freshness 是 `STALE/EXPIRED`，应在 ANEKE 对当前 draft fingerprint 重新执行门禁，而不是修改链接绕过检查。

**ANEKE event cursor 返回 400 或 410。**

`400 RG.INTEGRATION.CURSOR_INVALID` 表示 token 被修改、格式损坏，或被拿到另一个 tenant/environment 使用；不要重试或尝试解析内部位置。`410 RG.INTEGRATION.CURSOR_EXPIRED` 表示离线时间超过 cursor 有效期，调用 `/api/integration/reconciliation` 重建 projection，并从返回的 `checkpointCursor` 恢复增量同步。

**Integration API 返回 401 或 403。**

`401 AUTHENTICATION_REQUIRED/AUTHENTICATION_FAILED` 表示没有 Bearer credential，或 credential 不在受信 resolver 中、已停用/过期。`403 PURPOSE_FORBIDDEN` 表示 identity 或 endpoint 不允许该 `X-Purpose`；`403 IDENTITY_CLAIM_MISMATCH` 表示请求仍携带了与服务端 claims 冲突的旧身份 header。先查看 `/api/integration/capabilities` 的 `identityProvider`，再检查本地 `RG_INTEGRATION_DEMO_TOKEN` 或企业 OIDC/mTLS adapter 配置，不要通过修改 `X-Tenant-Id` 绕过。

**事件处理成功，但 ANEKE 重启后又收到同一事件。**

这是消费端没有把 projection 更新和 cursor checkpoint 放在同一事务提交造成的。Resource Gateway 保证同一 cursor 的窗口稳定，但不替 ANEKE 管理消费事务。ANEKE 必须同时保存 `eventId` 去重记录、aggregate sequence 和 `nextCursor`；重复事件应成为幂等 no-op。

## 11. 验证与回归命令

前端核心回归：

```bash
cd resource-gateway-examples/src/main/frontend
npm test
```

resource gateway 后端完整验证：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

带 React 打包和浏览器 smoke 的关键验证：

```bash
mvn -f resource-gateway-examples/pom.xml -Pfrontend \
  -Dtest=VisualAuthoringBrowserDomTest#reactAuthorCanvasLoadsPackagedBundleInRealBrowser,VisualAuthoringBrowserDomTest#reactShowcaseLoadsPackagedScenarioParityInRealBrowser \
  verify
```

## 12. 当前边界与后续方向

当前系统已经覆盖通用画布核心闭环，但它仍是 `resource-gateway-examples` 内的 example-grade 实现，不等于完整控制面产品。

当前不覆盖：

- 多人实时协作。
- 生产级 IAM/RBAC；当前 integration headers 仍是示例身份上下文，不能视作受信 IAM claims。
- 持久化远程 worker runtime。
- 完整 AI tool/event/message/webhook 执行平面。
- 把 visual core 物理拆成独立 Maven artifact。
- KMS/HSM 托管的 evidence key；当前持久 Ed25519 provider 用于本地 H2 演示，企业部署必须替换 `VisualEvidenceSigner` SPI。
- webhook subscription、签名投递、重试与 DLQ；当前已具备 polling cursor 和 reconciliation，后续 webhook 只能作为低延迟提示层。
- 外部数据库集群、Outbox retention job 和灾备恢复编排；当前 H2 实现用于证明事务、游标和对账协议，不等于企业 HA 存储。
- `shadow/live` replay；当前已具备生成新 run lineage 的 `RECORDED_ASSERTIONS + DENY` 无副作用 replay command。

后续可以继续推进：

- 把 `visual/*` 抽出更干净的可复用 core + adapter SPI。
- 给 `/author/` 增加 stored draft 打开/保存/发布完整工作流。
- 把 runtime binding handoff 做成更直接的控制面。
- 增强复杂 schema 的表单化 fixture 编辑。
- 对大型 operator library 做更强的分页、分面和团队治理体验。

## 13. 一句话使用心法

先让 schema 成为真实边界，再让画布成为业务推理空间。不要急着追求所有算子都真实可执行；先用 design operator、fixture 和 simulate 把业务逻辑走通，再把稳定下来的图逐步绑定到 runtime。
