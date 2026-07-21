# Certificate Status Exact Source-Head 产品验收

## 1. 结论与根因

证书状态 monitor 过去只能从连续 `BATCH_LIMIT` 推测可能存在积压。这个信号无法区分“恰好处理完”、
“仍有 N 条待处理”和“上游 head 已回退”，因而不能进入精确 SLO、发布门禁或审计证据。

本增量已把根因闭合为一条 test/staging 产品链：外部状态 authority 对 source head 做 M-of-N Ed25519
签名；每次成功 source 响应都携带 exact signed head；Resource Gateway 以数据库时间持久化独立
anti-rollback floor，并把当前 publication identity 与 head 绑定后计算精确 lag。连续 `BATCH_LIMIT`
只保留为诊断计数，不再决定 `CATCH_UP_BACKLOG`。

## 2. 协议与传输

`bloge.controlPlaneCertificateStatusSourceHead.v1` 固定签名材料：

- `trustDomain`、`attestationId`、`deploymentScopeId`；
- `headSequence`、`headPublicationFingerprint`、`policyFingerprint`；
- `issuedAt`、`expiresAt` 和 1..32 个 distinct-authority signatures。

零 baseline head 是一等状态，其 fingerprint 必须来自部署固定 baseline。trust store 与数据库 floor
都校验 exact scope、accepted policy、最大 24 小时有效期、canonical fingerprint、签名时刻 key lifecycle
和 M-of-N quorum；未知 key 不贡献 quorum，拒绝结果不返回 attestation identity、cursor 或 fingerprint。

source transport 已升级到 `bloge.controlPlaneCertificateStatusSourceResponse.v2`：

- 仅接受 HTTP `200`；成功响应必须携带 source head，publication 可为空；
- `Content-Type` 必须精确为
  `application/vnd.bloge.control-plane-certificate-status-source-response.v2+json`；
- `X-BLOGE-Certificate-Status-Protocol` 必须精确为
  `certificate-status-source-response-v2`；
- 空 publication 只表示“请求 cursor 就是 exact head”，不能表示“本次没有返回数据”；
- 非空 publication 必须是请求 cursor 的唯一直接后继，且不高于 head；若正好位于 head，两个
  publication fingerprint 必须相同。

旧 raw publication/HTTP 204 路径不再是产品成功协议。v1 Schema 仅作为历史版本保留，不会被 v2
HTTP adapter 降级接受。

## 3. Durable floor 与一致性

`DatabaseControlPlaneCertificateStatusSourceHeadFloor` 使用与 publication floor 相同的 deployment lock，
但维护独立 head snapshot 和 attestation journal。它在事务内以数据库当前时间重新验签，并拒绝：

- sequence rollback、同 sequence 不同 publication fingerprint 和 baseline 冲突；
- attestation id 被不同材料复用、同 head 的非递增 `issuedAt` renewal 或 hard expiry；
- canonical row/journal fingerprint 篡改和跨重启重建不一致。

独立 floor 不读取 publication 表；产品 monitor 是两者的唯一协调入口，并在提交 head 前拒绝低于当前
applied publication 的 head，以及同 sequence publication identity 不一致。这样既避免 floor 之间形成
隐式双向依赖，也不允许产品路径绕过跨协议一致性。

精确 lag 不是无条件的 `headSequence - appliedSequence`。baseline/head 两个端点必须精确匹配
publication fingerprint；中间 sequence 则由 monitor 每轮验证 request cursor 的唯一直接后继，逐步收敛到
最终 signed head fingerprint。head 过期、端点 fingerprint 不一致或 cursor 超界时，`exactLagFrom(...)`
返回 `-1`，调用方必须把它解释为“精确证明不可用”，而不是零积压。单个 head attestation 不宣称直接
列举或证明所有中间 publication；authority equivocation 仍由后续 witness 能力治理。

## 4. Monitor、SLO 与运维语义

monitor 是两类 durable floor 的产品协调入口。每次 `UNCHANGED` 或 `PUBLICATION` 成功都必须先接受
exact head；缺失、过期、回退、分叉或与 publication 不一致时返回 `SOURCE_HEAD_REJECTED`，不推进
admission cache。达到单轮处理上限时：

- exact lag 为 `0`：状态是 `APPLIED`，说明恰好追平；
- exact lag 大于 `0`：状态是 `BATCH_LIMIT`，说明仍有精确积压；
- exact proof 不可用：状态是 `SOURCE_HEAD_REJECTED`，不能猜测 backlog。

monitor descriptor v2 公开 `sourceHeadVerified`、`sourceHeadSequence`、`sourceHeadLag` 和
`sourceHeadExpiresAt`；health v2 与 SLO assessment v2 只投影前三个固定基数事实。descriptor 会在
source-head wall-clock + monotonic lease 到期后即时把 proof 置为不可用，即使没有下一次 fetch。

SLO 新增 `SOURCE_HEAD_UNAVAILABLE`；只有 `sourceHeadVerified=true` 且
`sourceHeadLag > maximumSourceHeadLag` 时才产生 `CATCH_UP_BACKLOG`。配置变量为：

```bash
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_MAXIMUM_SOURCE_HEAD_LAG=0
```

允许范围是 `0..1000000`。Micrometer 导出 `source.head.verified`、`source.head.sequence`、
`source.head.lag`、`source.head.seconds.to.expiry`；Tool Studio capability 公开
`controlPlaneCertificateStatusExactSourceHead`。capability 每次即时评估 SLO，避免缓存 assessment 在
head 过期后继续报告健康。所有字段均不包含 endpoint、证书、authority key、credential 或 payload。

## 5. Schema 与测试证据

新增或升级的 closed Draft 2020-12 Schema 包括 source head v1、source response v2、source-head floor
snapshot v1、monitor descriptor v2、health v2、SLO assessment v2 和 configuration v2。v1 descriptor/
health/SLO/configuration Schema 保留用于协议历史和显式兼容测试。

以下 82 项协议、HTTP、durable floor、monitor、SLO、health、Tool Studio 与 demo preflight 门禁已通过，
0 failures、0 errors、0 skips：

```bash
mvn -f resource-gateway-examples/pom.xml test \
  -Dtest='ControlPlaneCertificateStatusSourceHeadTest,ControlPlaneCertificateStatusSourceHeadProtocolSchemaTest,ControlPlaneCertificateStatusSourceResponseProtocolTest,DatabaseControlPlaneCertificateStatusSourceHeadFloorTest,HttpControlPlaneCertificateStatusSourceTest,ControlPlaneCertificateStatusMonitorTest,ControlPlaneCertificateStatusTelemetryTest,ControlPlaneCertificateStatusSloMonitorTest,ControlPlaneCertificateStatusHealthTest,ControlPlaneCertificateStatusRuntimeConfigurationTest,ControlPlaneCertificateStatusProductSchemaTest,ToolStudioControlPlaneCertificateRotationCapabilityTest,VisualCanvasDemoScriptTest'
```

## 6. 未完成边界

exact source-head 已完成 test/staging 产品化，但不能据此声明 enterprise PKI production ready。剩余根问题：

- certified CA event/OCSP/CRL normalizer 的互操作、语义一致性和签名 custody；
- status authority/source workload identity 的无重启轮换、紧急撤销与恢复演练；
- 多区域 authority equivocation witness、跨域 gossip、retention/compaction 和审计归档；
- 外部 alert/burn-rate/pager 路由以及 SLO 预算治理；
- production database、backup/restore、split-brain、clock anomaly、HA/DR/chaos 认证；
- HSM/KMS custody、maker/checker 变更和 N/N-1 兼容矩阵。

在这些门禁关闭前，本实现是严格的 test/staging 产品路径和生产集成参考，不是生产认证声明。
