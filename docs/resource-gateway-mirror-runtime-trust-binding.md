# Resource Gateway Mirror 运行期信任绑定

本文说明 Resource Gateway 如何把部署隔离证明绑定到一次完整的 Mirror 运行，使“业务逻辑在模拟环境中跑通”升级为“该运行始终处在已验证隔离边界内，并能被独立审计”。

适用读者：Resource Gateway 开发者、Tool Studio/ANEKE 集成方、平台工程团队、安全团队和 SRE。

## 1. 能力边界

Resource Gateway 现在支持两类运行：

| 类型 | 计划策略 | 运行要求 | Evidence |
|---|---|---|---|
| 探索模拟 | `certificationRequired=false` | 独立测试引擎、外部叶子全部 fixture 化、受治理 signer | v2 `EXPLORATORY`，显式包含 `DEPLOYMENT_EGRESS_NOT_ATTESTED` limitation |
| 认证模拟 | `certificationRequired=true` | 探索模拟的全部约束，加 deployment agent 双观察和事务期 commit permit | v2 `CERTIFIABLE`，无 deployment limitation，携带完整 run-trust binding |

该能力证明的是：一次运行在准入、执行完成和证据提交三个时点都属于同一个有效部署隔离决策。它不证明 fixture 表达了正确业务语义，也不替代 ANEKE 的 correctness workbook、发布门禁、owner 审批或客户环境认证。

## 2. 为什么绑定稳定决策，而不是锁死 cache generation

deployment agent 会定期重新获取并验证同一份 authority、attestation 和 status。即使远端事实没有变化，本地 snapshot 的 `cacheGeneration` 也会增加。如果把运行准入 generation 与提交 generation 强制相等，一次正常刷新就会让所有长运行失败。

当前协议把两个概念分开：

- **稳定决策**：`decisionRef` 指向原子的 `DEPLOYMENT_ISOLATION_ATTESTATION_BUNDLE`，并同时固定 `authorityKeySetRef`、`attestationRef` 和 `statusRef`。
- **本地观察**：`admittedSnapshotRef` 与 `committedSnapshotRef` 记录两个 agent cache generation。

允许提交的唯一代次变化是：snapshot id 相同、generation 单调不下降、稳定决策完全相同。attestation successor、status revocation、authority generation 变化、cache rollback、过期或 scope 变化都必须拒绝。

## 3. 端到端状态机

一次认证运行按以下顺序发生：

```text
certification-required MirrorPlan
  -> agent read permit: require ACTIVE, exact scope, now < validUntil
  -> Admission: stable decision + admitted snapshot + admittedAt
  -> request fingerprint includes stable decision fingerprint
  -> durable Registration stores stable decision
  -> durable Lease/TrustAttempt stores admitted snapshot and time fence
  -> isolated BLOGE execution, no production credential or real external fallback
  -> agent read permit: re-read current ACTIVE snapshot
  -> confirm same stable decision and complete signed execution-window coverage
  -> Binding: admitted snapshot + committed snapshot + admittedAt + confirmedAt
  -> project and sign MirrorRunEvidence v2
  -> acquire commit permit against current agent decision
  -> insert evidence + fence request completion + success audit in one transaction
  -> transaction afterCompletion releases commit permit
```

commit permit 持有 agent 的读锁。刷新、撤销和 successor publication 需要写锁，因此本机观察到的信任变化只能线性化在整笔 evidence 事务之前或之后，不能落在 evidence insert 与 request completion 之间。

## 4. 核心不变量

### 4.1 准入

1. 只有 `MirrorPlan.policy.certificationRequired=true` 才允许请求携带 `Admission`。
2. certification-required 计划没有可用 agent trust 时，在 durable claim 之前返回 `503`。
3. Admission scope 必须等于认证身份派生出的 tenant/organization/project/environment/region。
4. agent snapshot 必须是 `ACTIVE`，且当前时间早于独占的 `validUntil`。
5. authority、attestation、status 和本地 snapshot 都只以 canonical artifact ref 进入运行边界，不携带业务 payload。

### 4.2 幂等与恢复

1. `requestId` 的不可变含义包括 plan、有效 context fingerprint、完整 scope、purpose 和稳定 trust decision。
2. 相同 `requestId`、相同稳定 decision、更新的本地 snapshot 可以在租约过期后接管。
3. 相同 `requestId` 观察到不同 decision 必须返回 `RG.MIRROR.RUN_IDEMPOTENCY_CONFLICT`；调用方应使用新 requestId。
4. `TrustAttempt` 属于 lease epoch。接管时原子替换为新 admitted snapshot，但不改变 Registration 中的稳定 decision。
5. 已完成重试只读取已验证 evidence，并再次核对 durable decision、attempt 和 evidence binding；不重新执行图。

### 4.3 执行与确认

1. `confirmedAt >= completedAt >= startedAt`，且 `admittedAt <= startedAt`。
2. authority 和 attestation 的签名有效窗、status effective time 必须覆盖完整执行窗口。
3. committed snapshot id 必须等于 admitted snapshot id，generation 不得回退。
4. deployment isolation reference 必须等于 binding 中的 attestation reference。
5. 终态确认失败时，即使业务节点已经运行，也不得交付或持久化 evidence。

### 4.4 原子提交

1. evidence、request terminal state 和成功审计必须使用同一个本地事务管理器。
2. lease owner、epoch、原始 expiry 和数据库时间共同 fence 提交。
3. commit 前再次校验 durable Registration、TrustAttempt 与 evidence Binding。
4. commit permit 必须保持到 Spring transaction `afterCompletion`；回滚同样释放。
5. 任何 lease loss、trust change、audit failure 或 evidence integrity failure 都回滚 evidence insert。

## 5. 协议与版本

| 对象 | 当前版本 | 兼容策略 |
|---|---|---|
| Mirror run trust binding | `resourceGateway.mirrorDeploymentIsolationRunTrust.v1` | v2 evidence 中使用；strict unknown-field rejection |
| Mirror run evidence | `resourceGateway.mirrorRunEvidence.v2` | producer 默认 v2；reader 兼容 v1/v2 |
| Evidence attestation | `resourceGateway.mirrorEvidenceAttestation.v2` | v1/v2 使用不同签名 domain，禁止混代际 bundle |
| Evidence bundle | `resourceGateway.mirrorEvidenceBundle.v2` | v1/v2 均可离线验证；bundle/evidence/attestation 必须同代际 |

权威 Schema：

- `docs/schemas/resource-gateway-mirror/mirror-deployment-isolation-run-trust-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/mirror-run-evidence-v2.schema.json`
- `docs/schemas/resource-gateway-mirror/mirror-evidence-attestation-v2.schema.json`
- `docs/schemas/resource-gateway-mirror/mirror-evidence-bundle-v2.schema.json`

v1 没有 run-trust binding。历史 v1 `EXPLORATORY` 和 v1 `CERTIFIABLE` 都继续可读、可验签；系统不会把 v1 evidence 原地升级为 v2，也不会把 v1 attestation 放进 v2 bundle。

## 6. Run-trust Binding 字段

| 字段 | 含义 |
|---|---|
| `decisionRef` | authority、attestation 和 status 的原子稳定决策 |
| `authorityKeySetRef` | 精确的可信 authority publication |
| `attestationRef` | 精确的外部签名部署隔离证明 |
| `statusRef` | 精确的本地 ACTIVE status publication |
| `admittedSnapshotRef` | 执行前 agent 观察 |
| `committedSnapshotRef` | 执行完成后的 agent 观察 |
| `admittedAt` | 受信任准入时刻 |
| `confirmedAt` | 受信任终态确认时刻 |

协议只含引用、代次、fingerprint 和时间，不含证书、私钥、网络凭证、fixture 或业务输入输出。

## 7. 数据库存储

`mirror_run_requests` 新增以下 payload-free 列：

- `trust_required`；
- `trust_bundle_id/revision/fingerprint`；
- `admitted_snapshot_id/cache_generation/fingerprint`；
- `trust_admitted_at`；
- `trust_valid_until`。

初始化使用 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 兼容旧表。旧记录默认 `trust_required=false`，因此保持探索语义。表中仍没有 JSON、CLOB、BLOB、context、fixture、input、output 或 replay payload 列。

生产迁移前必须在目标数据库方言执行真实 DDL、锁等待、索引、备份恢复和滚动升级验证；当前仓库自动化以 H2 为参考实现，不能代替客户 HA 数据库认证。

## 8. Capability Probe

查询：

```http
GET /api/integration/capabilities
```

重点字段：

| Feature | 含义 |
|---|---|
| `mirrorServing` | 探索运行端点、仓储与 signer 当前可用 |
| `mirrorIsolationRunTrustBindingProtocol` | 当前二进制理解 run-trust binding 协议 |
| `mirrorIsolationRunTrustReady` | deployment agent 当前能提供正向准入 |
| `mirrorCertifiableEvidenceServingReady` | signer 与 deployment trust 同时可用，可接认证计划 |

`supportedObjects` 同时公布 evidence/bundle/attestation v1 与 v2，以及 run-trust v1。接入方必须按对象版本选择 Schema，不能只根据 HTTP endpoint 存在推断认证能力。

## 9. 使用步骤

### 9.1 平台准备

1. 仅在 `test` 或 `staging` profile 设置 `RG_MIRROR_RUNTIME_ENABLED=true`。
2. 按 deployment-agent 手册配置 private PKI、mTLS、SPKI pin、bootstrap roots、exact bootstrap floor 和 durable atomic cache。
3. 配置受治理 evidence signer；本地临时 signer 不能作为企业发布证据。
4. 启动后执行一次 `agent.refreshNow()`，确认 capability 中 run trust ready。

### 9.2 创建认证计划

向 `POST /api/mirror/plans` 提交经过评审的 artifact refs，并设置：

```json
{
  "certificationRequired": true
}
```

服务端会把该策略写入 sealed `MirrorPlan`。调用方不能在 execute request 临时把探索计划升级为认证计划。

### 9.3 执行与读取证据

调用 `POST /api/mirror/executions` 时只提交 requestId、planId、expectedPlanFingerprint 和业务 context。Admission 由服务端从 deployment agent 获取，调用方不能伪造。

成功后通过：

```http
GET /api/mirror/runs/{runId}/evidence
```

检查 `evidenceClass=CERTIFIABLE`、顶层与 isolation limitations 为空、`deploymentEgressEnforced=true`，并用 `resource-gateway-test-kit` 的 `MirrorEvidenceVerifier` 离线验证 Schema、closure、fingerprint、key policy、签名和 run-trust 时间/代次关系。

## 10. 错误与重试

| 错误码 | HTTP | 可重试 | 处理 |
|---|---:|---|---|
| `RG.MIRROR.DEPLOYMENT_TRUST_UNAVAILABLE` | 503 | 是 | 修复 agent bootstrap、分发、过期或撤销状态后，用相同 requestId 重试同一 decision |
| `RG.MIRROR.DEPLOYMENT_TRUST_REQUIRED` | 503 | 是 | 检查计划策略、服务 composition 和 admission 传递 |
| `RG.MIRROR.DEPLOYMENT_TRUST_CHANGED` | 503 | 是 | 当前 attempt 已释放；若 decision 已变化，使用新 requestId |
| `RG.MIRROR.RUN_IDEMPOTENCY_CONFLICT` | 409 | 否 | requestId 已绑定不同 plan/context/trust decision，生成新 requestId |
| `RG.MIRROR.RUN_REQUEST_IN_PROGRESS` | 409 | 是 | 按 `retryAfterSeconds` 重试 |
| `RG.MIRROR.RUN_LEASE_LOST` | 409 | 是 | 当前 worker 不再有提交权，重新 claim |
| `RG.MIRROR.RUN_EVIDENCE_INCONSISTENT` | 503 | 是 | 隔离 evidence store 并排查 durable state/evidence 漂移 |

错误、日志、指标和异常不得包含业务 payload、证书、私钥、cache 路径或完整 artifact fingerprint。

## 11. 运行与告警

建议至少告警：

- `mirrorServing=true` 但 `mirrorIsolationRunTrustReady=false` 持续超过一个 refresh SLO；
- `ACTIVE_REFRESH_DEGRADED` 距 `validUntil` 小于两次正常刷新窗口；
- `RG.MIRROR.DEPLOYMENT_TRUST_CHANGED` 突增；
- cache generation 回退、snapshot integrity failure 或 status discontinuity；
- transaction rollback、lease takeover 和 evidence inconsistency；
- NTP/时钟源异常。

指标保持固定基数，不以 tenant、deployment、attestation id、requestId 或 fingerprint 作 label。

## 12. 已验证场景

自动化覆盖：

- 同一稳定决策下 agent 常规刷新，generation 1 到 2 的长运行继续认证；
- attestation successor、revocation、cache rollback、scope/time window 不一致时 fail closed；
- commit permit 与 refresh 写锁真实并发，permit 释放前 refresh 不能穿过提交；
- trust decision/attempt 的数据库持久化、重启恢复、租约接管与 requestId 冲突；
- commit 前 durable admission/binding 对账、事务完成后释放 permit；
- v2 certifiable 正向运行与 terminal confirmation 失败拒绝交付；
- capability 探索 readiness 与认证 readiness 分离；
- 独立 test-kit 对 v1/v2 Schema、fingerprint、签名 domain 和 v2 run-trust 语义复验；
- 冻结 v1 cryptographic fixture 与历史 v1 certifiable evidence 兼容。

本增量的提交门禁结果：

| 门禁 | 结果 |
|---|---|
| `mvn -f resource-gateway-examples/pom.xml clean verify` | 4621 tests，0 failures，0 errors，3 skipped；包含 35 个真实浏览器用例 |
| `mvn -f resource-gateway-test-kit/pom.xml clean verify` | 271 tests，全部通过；29 份 Mirror Schema 进入发布 JAR；公开 API JavaDoc 校验通过 |
| `git diff --check` | 通过 |

Resource Gateway 全项目额外执行 `javadoc:javadoc -Werror` 仍会被既有基线阻断：当前有 16 个旧 HTML/标签错误和 100 个旧 record `@param` 警告。本增量新增公开类型不在诊断列表中；该全局文档债务应单独治理，不能把正式 `clean verify` 的成功误报为全项目严格 JavaDoc 已通过。

## 13. 仍需环境级认证

以下能力没有被本增量伪称为完成：

1. 多副本之间的撤销传播仍受各 agent refresh/maximumSnapshotAge 约束；本地锁只提供单进程线性化。
2. 客户 KMS/HSM custody、真实 IdP/mTLS、private PKI 运维和 break-glass 流程必须单独认证。
3. 客户数据库方言、HA/DR、跨地域复制、在线 DDL 与长事务容量必须压测和演练。
4. wall-clock 依赖需要受治理 NTP/时钟源；仓库测试不能替代时钟回拨和 leap-event 演练。
5. v2 尚缺非 Java 客户端的固定签名 compatibility fixture；Java test-kit 已能独立生成和验证 v2。
6. fixture 业务保真度、stateful world、outcome calibration 和 owner 认可仍由后续 Mirror 演进与 ANEKE 门禁负责。

生产晋级必须把这些项目作为部署证据，而不是因为 capability 显示 run trust ready 就自动放行。
