# Resource Gateway 可信 Capability Observation 准入指南

## 1. 目标与当前交付边界

客服业务镜像的上限取决于对客户真实业务行为的拟合保真度。要提升保真度，系统必须持续吸收真实能力调用的结构、
结果和状态迁移事实；但这些事实只有同时具备来源可信、用途合法、数据已治理、范围精确和可审计性时，才能进入后续
corpus、resolver、trajectory 和 outcome calibration。

本增量交付 Stage 2 的第一个工业级纵切：

- 冻结签名、payload-free 的 `resourceGateway.capabilityObservation.v1`；
- 冻结本地终态准入决定 `resourceGateway.capabilityObservationAdmission.v1`；
- 冻结原子回执 `resourceGateway.capabilityObservationReceipt.v1`；
- 提供受保护的 `POST /api/mirror/observations`；
- 提供 full-scope、append-only、读写重验真的 H2 repository；
- 提供 operator-owned policy SPI 和外部 payload reference verification SPI；
- 提供独立于服务端和 Spring 的 test-kit verifier 与固定签名 fixture；
- 将 API 是否装配与外部权威是否就绪分开报告。

本增量不包含 payload vault、脱敏引擎、corpus revision、resolver、schema induction、retention 删除执行器或
outcome calibration。默认 policy provider 和 payload-reference verifier 均不可用，因此开箱启动时 API 可以存在，
但 readiness 必须为 `false`。

## 2. 不可破坏的不变量

### 2.1 Resource Gateway 永不接收业务 payload

Observation envelope 只能携带已脱敏 payload 的 exact reference、sanitization proof reference、JSON Schema
reference、大小、分类、驻留地域和保留期。以下信息不得进入请求、数据库、审计、指标或错误：

- 原始或脱敏后的 request/response bytes；
- 客户号、工单号、订单号等原始业务键；
- provider error message、stack trace 或任意自由文本；
- secret、credential、cookie、token 或 HTTP header；
- 调用方上传的 trust root、准入策略或权限覆盖参数。

`CapabilityObservationPayloadReferenceVerifier` 只能返回低基数的 `VERIFIED`、`REJECTED` 或 `UNAVAILABLE`，
不得把 payload 返回 Resource Gateway。

### 2.2 信任来源属于 operator，不属于请求方

请求方只声明签名 observation 自身。以下事实必须由
`CapabilityObservationAdmissionPolicyProvider` 以一个原子 policy generation 提供：

- 完整 enterprise scope 与 exact capability revision；
- exact data-use grant 和 policy reference；
- producer issuer、key id、公钥、生命周期和有效窗；
- 允许的数据分类、vault region 和 permitted use；
- observation age、future skew、payload size 和剩余 retention 约束。

请求体、query parameter 和普通 header 都不能选择 trust root 或放宽策略。默认 provider 返回 unavailable，
避免无配置时出现 TOFU。

### 2.3 “治理拒绝”和“无法判断”必须分开

系统只允许三种外部可观察结果：

| 结果 | HTTP | 是否持久化 | 含义 |
|---|---:|---|---|
| `ADMITTED` | 200 | 是 | 所有准入控制通过，可在 `usableUntil` 前被后续 corpus 消费 |
| `QUARANTINED` | 200 | 是 | 已做出确定的治理拒绝，只可调查，不得进入 serving corpus |
| unavailable/problem | 4xx/5xx | 否 | 身份、请求或基础设施导致无法形成可信决定 |

policy 不存在、capability 不合格、签名无效、grant/窗口/payload policy 不合格、外部 proof 被明确拒绝，都形成
durable `QUARANTINED`。policy store、capability store、payload authority、observation store 或 mandatory audit
不可用时返回 `503`，不得伪造一个 quarantine 决定。

## 3. 协议模型

### 3.1 Signed observation

`CapabilityObservationEnvelope` 的 material 包含：

| 字段 | 约束与用途 |
|---|---|
| `observationId` | 完整 scope 内稳定的幂等身份 |
| `scope` | tenant、organization、project、environment、region 缺一不可 |
| `capabilityRef` | exact `CAPABILITY` id/revision/fingerprint |
| `occurredAt` | 可信 producer 记录的发生时间 |
| `trace` | payload-free traceId/spanId/sequence |
| `request` | 已脱敏 payload、proof、schema 的 exact references |
| `response` | 成功调用的已脱敏 payload references |
| `error` | 失败调用的 closed errorClass/errorCode/retryable/details fingerprint |
| `latencyMillis` | 0 到 24 小时的有界耗时 |
| `stateCorrelation` | entity type 和 tenant-scoped business/state fingerprints |
| `outcomeCorrelationRef` | 可选 exact `OUTCOME_CORRELATION` |
| `dataUseGrant` | exact grant、专用 purpose、允许用途和有效窗 |

`response` 与 `error` 必须且只能出现一个。`allowedUses` 使用 canonical enum 顺序，当前支持：

- `CORPUS_CURATION`
- `EXACT_REPLAY`
- `OUTCOME_CALIBRATION`
- `TRAJECTORY_MODELING`

seal 固定使用 Ed25519，对 domain-separated material fingerprint 签名；签名必须在 observation 发生后 15 分钟内
签发。完整 envelope 另有 canonical observation fingerprint，任何字段漂移都会破坏内容地址。

### 3.2 Terminal admission

`CapabilityObservationAdmission` 固定：

- exact observation、capability、grant、policy 和 authority-key references；
- 本地可信 `decidedAt`；
- `ADMITTED` 或 `QUARANTINED`；
- closed reason；
- admitted observation 的 exclusive `usableUntil`。

`usableUntil` 取 data-use grant、request retention 和 response retention 的最早值。quarantine 的
`usableUntil == decidedAt`，从协议上阻止误入 serving corpus。

### 3.3 Atomic receipt

`CapabilityObservationReceipt` 同时返回 exact producer envelope 和 terminal local admission。调用方无需把两个
endpoint 的结果自行拼接，也不会读取到“observation 已写、decision 尚未写”的中间状态。

权威 strict Schema 和固定 public-only fixture 位于：

- `docs/schemas/resource-gateway-mirror/capability-observation-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/capability-observation-admission-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/capability-observation-receipt-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/capability-observation-stage2-v1.fixture.json`

## 4. 准入顺序

服务端按以下固定顺序执行，前序失败不得触发后续跨系统查询：

1. 认证 workload identity，并要求 purpose 为 `MIRROR_CORPUS_INGESTION`。
2. 严格解码 JSON：拒绝重复 key、unknown field、trailing token、超过 1 MiB、32 层或 10,000 nodes。
3. 验证 test/staging 环境和完整 enterprise scope。
4. 在任何 policy、capability 或 payload lookup 前比较 signed scope 与 authenticated scope。
5. 按 full scope + `observationId` 查询 exact retry。
6. exact retry 直接返回原始 receipt，不重新咨询可能已变化的 policy 或 external authority。
7. 解析 operator-owned atomic admission policy。
8. 读取并重验 exact CapabilitySnapshot；只接受 `ACTIVE` 或 `DEPRECATED`。
9. 校验 grant、用途、age/future skew、classification、region、size 和 remaining retention。
10. 重新计算 material/envelope fingerprint，并验证 producer key lifecycle、issuer 和 Ed25519 signature。
11. 委托外部 authority 验证 payload、sanitization proof、schema、scope、grant 和 retention references。
12. 封印本地 admission，原子追加 observation + decision + mandatory success audit。

同一 full scope 下复用 `observationId` 且 observation fingerprint 不同，返回 `409`。不同 organization、project、
environment 或 region 可以安全地使用相同 id，不会共享记录。

## 5. 服务端接线

### 5.1 装配条件

Observation API 与整个 Mirror runtime 使用同一双栅栏：

```text
active profile in {test, staging}
AND gateway.testing.mirror.enabled=true
AND production profile absent
```

只要 active profiles 包含 `production`，controller、service、repository 和 adapter 都物理不存在。

### 5.2 必须提供的企业适配器

客户部署应在自己的配置中提供以下 beans；Resource Gateway 的默认 beans 使用
`@ConditionalOnMissingBean`，会自动让位：

```java
@Bean
CapabilityObservationAdmissionPolicyProvider observationAdmissionPolicies(
        GovernedPolicyClient client) {
    return new CustomerObservationAdmissionPolicyProvider(client);
}

@Bean
CapabilityObservationPayloadReferenceVerifier observationPayloadReferences(
        PayloadVaultAuthority authority) {
    return new CustomerPayloadReferenceVerifier(authority);
}
```

policy provider 的一次 `resolve` 必须读取一个原子 generation，不能分别读取 key、grant 和地域策略后在内存中拼装。
payload verifier 必须按 signed full scope 进行 metadata-only 验证，并保证：

- payload ref 是 immutable content address；
- payload 在首次持久化前已经脱敏；
- proof、schema、grant、region、classification 和 retention 与 ref exact binding；
- 删除、过期或 legal policy 禁止使用时返回确定的 `REJECTED`；
- timeout、读 quorum 不足或 authority 状态不明时返回 `UNAVAILABLE`。

### 5.3 Capability probe

读取 `GET /api/integration/capabilities`：

| Feature | 含义 |
|---|---|
| `mirrorObservationProtocol=true` | 服务版本认识 observation/admission/receipt v1 |
| `mirrorObservationAdmissionApi=true` | 非生产 route 和 application service 已装配 |
| `mirrorObservationAdmissionReady=true` | policy provider 与 payload-reference authority 当前都可用 |

`Api=true`、`Ready=false` 是默认且健康的 fail-closed 状态，不应被部署平台误报成“功能已完全就绪”。

## 6. 调用方式

先启动 test/staging Mirror runtime：

```bash
RG_MIRROR_RUNTIME_ENABLED=true \
  ./scripts/start-visual-canvas-demo.sh --profile test
```

检查能力：

```bash
curl -sS http://localhost:8080/api/integration/capabilities
```

提交由可信 producer 生成并签名的 observation：

```bash
curl -sS http://localhost:8080/api/mirror/observations \
  -H 'Authorization: Bearer REPLACE_WITH_PRODUCER_TOKEN' \
  -H 'X-Purpose: MIRROR_CORPUS_INGESTION' \
  -H 'Content-Type: application/json' \
  --data-binary @signed-capability-observation.json
```

固定 fixture 的时钟和 key 只用于跨版本 compatibility 验证，不是 demo 私钥，也不能直接作为当前时间的在线准入请求。
仓库不包含 observation producer 私钥。

停止服务：

```bash
./scripts/stop-visual-canvas-demo.sh
```

## 7. 稳定错误与重试语义

| Code | HTTP | 重试 | 含义 |
|---|---:|---|---|
| `RG.MIRROR.OBSERVATION_REQUEST_MALFORMED` | 400 | 修正后 | JSON 不闭合、歧义或超过应用边界 |
| `RG.MIRROR.OBSERVATION_INVALID` | 400 | 修正后 | typed protocol 或 canonical state 无效 |
| `RG.MIRROR.OBSERVATION_SCOPE_MISMATCH` | 403 | 否 | signed scope 与认证 scope 不同 |
| `RG.MIRROR.OBSERVATION_PURPOSE_REQUIRED` | 403 | 换身份 | 缺少专用 ingest purpose |
| `RG.MIRROR.OBSERVATION_SCOPE_INCOMPLETE` | 400 | 修正身份 | enterprise scope 不完整 |
| `RG.MIRROR.OBSERVATION_ENVIRONMENT_FORBIDDEN` | 403 | 否 | 试图在非 test/staging 环境准入 |
| `RG.MIRROR.OBSERVATION_ID_CONFLICT` | 409 | 换 id/查漂移 | 同 id 已绑定不同 immutable content |
| `RG.MIRROR.OBSERVATION_POLICY_UNAVAILABLE` | 503 | 是 | policy provider 当前不能做可信决定 |
| `RG.MIRROR.OBSERVATION_POLICY_INVALID` | 503 | 运维修复 | provider 返回自相矛盾的 policy |
| `RG.MIRROR.OBSERVATION_PAYLOAD_AUTHORITY_UNAVAILABLE` | 503 | 是 | vault/proof authority 状态未知 |
| `RG.MIRROR.OBSERVATION_CAPABILITY_STORE_UNAVAILABLE` | 503 | 是 | capability store 不可用 |
| `RG.MIRROR.OBSERVATION_STORE_UNAVAILABLE` | 503 | 是 | observation store 不可用或读时验真失败 |
| `RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE` | 503 | 是 | mandatory audit 失败，业务写已回滚 |

确定性治理拒绝不使用 4xx/5xx，而在 200 receipt 中返回 `QUARANTINED` 和 closed admission reason。调用方必须读取
`payload.admission.state`，不能只看 HTTP status。

## 8. 独立消费与 test-kit

`resource-gateway-test-kit` 不依赖 server 或 Spring。先用固定 fixture 证明本地版本与 canonicalization/signature
语义兼容：

```java
CapabilityObservationCompatibilityFixture fixture =
        CapabilityMirrorProtocol.capabilityObservationCompatibilityFixture();

CapabilityObservationVerifier.VerificationResult result =
        new CapabilityObservationVerifier().verify(
                fixture.observation(),
                fixture.verificationKey(),
                fixture.expectedScope(),
                fixture.verificationTime());

if (!result.verified()) {
    throw new IllegalStateException(result.reasonCode());
}
```

验证真实 observation 时，public key、expected scope 和 verification time 必须来自消费者自己的可信配置。verifier
证明 strict Schema、canonical use ordering、purpose/window、scope、fingerprint、key lifecycle 和 Ed25519 signature；
它明确不证明 payload ref 存在或已脱敏。后者只能由 tenant-scoped payload-vault authority 证明。

## 9. 运维与故障演练

上线前至少完成：

1. **Policy outage**：probe readiness 变 false；新 ingest 返回 503；exact retry 仍返回已提交 receipt。
2. **Payload authority timeout**：不得形成 quarantine；恢复后同一请求可重试。
3. **Invalid signature**：形成 durable `INTEGRITY_REJECTED` quarantine，且不调用 payload authority。
4. **Cross-scope attack**：在任何 repository/provider lookup 前返回 403。
5. **Audit outage**：observation、admission 和 success audit 一起回滚。
6. **Store corruption**：索引列与 canonical JSON 不一致时停止读取并返回 503。
7. **Duplicate producer delivery**：exact retry 不重新执行 mutable policy 和 external proof。
8. **Ingress pressure**：在 proxy/container 配置 raw body、connection、rate 和 timeout 限制。

应用 decoder 的 1 MiB 限制发生在 Spring 已缓冲 body 之后，不能替代 ingress 的 pre-materialization 限流。

当前 repository 没有删除 API。客户进入真实 corpus 前，必须另行完成 payload vault 的 retention、legal hold、
data-subject deletion、客户终止删除、WORM audit 和 deletion proof。只删除 payload 不等于删除 observation metadata；
两者的法规语义、保留期和证明责任必须由数据治理 owner 冻结。

## 10. 下一阶段开工顺序

1. 将 admitted observation 投影成 immutable corpus candidate/revision，并保持 exact lineage。
2. 建立 quarantine review、release/reject、poisoning 和 burst detection 工作流。
3. 实现 exact replay resolver，先覆盖 fingerprint 精确匹配和强制 abstention。
4. 再实现 trajectory/cluster resolver，不允许低置信度静默回退成“猜测成功”。
5. 建立 schema candidate induction、holdout validation 和 owner review。
6. 接入 outcome correlation，形成按 capability revision 和业务域分层的 fidelity 向量。
7. 通过 retention/deletion proof、跨租户攻击、provider outage、并发和多副本 certification 后，才允许扩大语料规模。

这一顺序先把“哪些事实可信且可用”根治，再构建“如何用这些事实拟合业务”，避免在来源和数据权利尚不确定时生成
看似丰富、实际上不可审计的模拟能力。
