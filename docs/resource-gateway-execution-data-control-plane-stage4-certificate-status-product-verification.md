# Stage 4 Certificate Status Admission 产品验收

## 1. 结论

证书状态链已从可嵌入 kernel 推进为 test/staging 产品路径：外部 CA event、OCSP、CRL 先由企业
adapter 归一化为完整 signed status publication，Resource Gateway 再执行严格 source protocol、独立
M-of-N 验签、database-clock monotonic floor、dual-clock local admission 和逐请求 exact binding。

固定执行顺序为：

1. source 通过独立 private PKIX、SPKI pin、mTLS 与双端 workload identity 获取 strict v2 response；
2. 每个成功 response 必须携带签名 exact source head，并可携带 request cursor 的唯一直接后继；
3. trust store 分别校验 publication/source-head 的 trust domain、policy、M-of-N 签名和 key lifecycle；
4. source-head floor 以数据库时钟拒绝 rollback、fork、stale renewal、attestation-id reuse 与存储漂移；
5. publication floor 校验连续 sequence、predecessor、整行 fingerprint、唯一 publication id 和完整 target 清单；
6. monitor 以 sequence + publication fingerprint 计算 exact lag，再替换进程内 admission cache；
7. source-head 本地 lease 同时受数据库 observedAt、wall clock 与 monotonic deadline 约束，replay 不续命；
8. 每条 rotating transport 在真实 handler I/O 前校验 exact target、generation 与 settings fingerprint；
9. `REVOKED`、`UNKNOWN`、binding mismatch 或 hard expiry 一律不发送请求。

这条链证明“当前证书代次是否仍被外部状态权威允许”，不替代 rotation event 的变更授权，也不把
source transport 的 TLS 身份提升为 CA 状态签名权威。

## 2. Bootstrap 与信任边界

status source 使用单独的静态 deployment identity，不复用它所保护的 12 条 rotating transport。
这是刻意的 bootstrap 边界：若状态 publication 必须通过待判定证书本身拉取，吊销或状态源故障会形成
循环依赖，系统无法区分“证书不可用”与“无法获得证书是否可用的信息”。

因此 test/staging 启动同时要求：

- signed rotation 为 `enabled=true, required=true`；
- status 与 rotation 的 `deployment-scope-id` 完全相等；
- status trust domain、accepted policy 与 authority public keys 独立配置；
- source 使用 private trust store、server SPKI pin、mTLS 和 exact client/server workload identity；
- status source 与 event source、notary、inventory 等 control-plane source 不复用 client keystore 身份；
- profile、capability、health 和日志均不输出 path、credential ref、pin、certificate 或 provider 诊断。

静态 source identity 只是当前 bootstrap 方案，不是生产终态。生产仍需受治理的 source identity 热轮换、
紧急吊销、双身份 overlap 与恢复演练，且其治理域不能与 status signing authority 形成单点共因。

## 3. 启用方式

先配置 signed rotation，再配置 status admission。关键变量如下：

```bash
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_ENABLED=true
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_REQUIRED=true
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SCOPE_ID=rg-staging
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRUST_DOMAIN=enterprise-ca-status
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_ACCEPTED_POLICIES=sha256:<64-hex>
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SIGNATURE_THRESHOLD=2
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_AUTHORITY_KEYS_JSON='[{...public Ed25519 key...}]'
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_BASELINE_SEQUENCE=0
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_BASELINE_PUBLICATION_FINGERPRINT=sha256:<64-hex>
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_ENDPOINT_URI=https://ca.example.test/v1/certificate-status

export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRANSPORT_ENABLED=true
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRANSPORT_REQUIRED=true
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRANSPORT_TRUST_STORE_PATH=/deployment/status-trust.p12
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRANSPORT_TRUST_STORE_PASSWORD_REF=env:RG_STATUS_TRUST_PASSWORD
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRANSPORT_CLIENT_KEY_STORE_PATH=/deployment/status-client.p12
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRANSPORT_CLIENT_KEY_STORE_PASSWORD_REF=env:RG_STATUS_CLIENT_PASSWORD
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRANSPORT_SERVER_SPKI_PINS=sha256:<64-hex>
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRANSPORT_CERTIFICATE_IDENTITY_REQUIRED=true
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRANSPORT_EXPECTED_CLIENT_SUBJECT_DN='CN=resource-gateway-status,O=Example'
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRANSPORT_EXPECTED_CLIENT_URI_SAN=spiffe://example.test/resource-gateway/status
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRANSPORT_CLIENT_ISSUER_SPKI_PINS=sha256:<64-hex>
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRANSPORT_EXPECTED_SERVER_URI_SAN=spiffe://example.test/ca/status
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_TRANSPORT_SERVER_ISSUER_SPKI_PINS=sha256:<64-hex>

export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_STARTUP_GRACE_SECONDS=60
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_MAXIMUM_REFRESH_SUCCESS_AGE_SECONDS=120
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_MINIMUM_EXPIRY_HEADROOM_SECONDS=60
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_MINIMUM_REFRESH_SAMPLES=20
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_MAXIMUM_REFRESH_FAILURE_BASIS_POINTS=500
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_MINIMUM_ADMISSION_SAMPLES=100
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_MAXIMUM_ADMISSION_DENIAL_BASIS_POINTS=1000
export RG_TEST_CONTROL_PLANE_CERTIFICATE_STATUS_SLO_MAXIMUM_SOURCE_HEAD_LAG=0
```

密码值只存在于 `*_PASSWORD_REF` 指向的 secret 中。使用演示脚本时，staging 会在 Maven build 前
检查依赖、scope、HTTPS、baseline、public-only authority JSON、I/O/scheduler/batch/SLO bounds、文件可读性、
credential reference、SPKI、workload identity 与跨 source identity isolation：

```bash
scripts/start-visual-canvas-demo.sh --profile staging
scripts/stop-visual-canvas-demo.sh
```

## 4. 可用性与失败语义

远端 source availability 与本地 admission freshness 是两个不同事实。一次 publication 已完成验签并
提交 durable floor 后，短暂 source outage 不会立刻打断流量；只有 cached snapshot 同时未越过签名
`expiresAt`、本地 wall-clock deadline 和 monotonic deadline 时才可继续服务。进程重启不能凭 wall clock
回拨延长旧租约，必须重新从 durable floor 建立受限 admission。

| 条件 | Durable floor | Local admission | 请求行为 |
| --- | --- | --- | --- |
| source timeout/5xx，cache 仍 fresh | 不变 | 保留 exact snapshot | 允许 exact binding |
| source timeout/5xx，cache 已 hard-expired | 不变 | stale | 全部失败关闭 |
| publication gap/fork/rollback | 不推进 | 不替换 | 保留旧 lease 至硬过期 |
| source head 缺失/过期/回退/分叉 | head floor 不推进 | 保留旧 cache | readiness/SLO 失败关闭，旧 cache 仅服务至自身硬过期 |
| publication 漏 target 或整行被篡改 | 不推进 | 不替换 | 保留旧 lease 至硬过期 |
| target 为 `REVOKED` 或 `UNKNOWN` | 新完整 snapshot | 对该 binding 拒绝 | handler 前阻断 |
| generation/settings fingerprint 不匹配 | 不需要写 floor | exact lookup miss | handler 前阻断 |
| trust/floor/cache 不可用 | 不确定 | unavailable | 全部失败关闭 |

## 5. 运维判读

Actuator status health v2 固定为 19 个低基数字段，区分 `sourceAvailable`、`sourceHeadVerified`、
`sourceHeadSequence`、`sourceHeadLag`、`admissionFresh`、monitor 状态和 runtime 状态；descriptor 异常时
使用固定 reason code，不携带异常文本。Tool Studio capability 公开：

- `controlPlaneCertificateStatusIntegrated`
- `controlPlaneCertificateStatusAvailable`
- `controlPlaneCertificateStatusFresh`
- `controlPlaneCertificateRevocationAdmission`
- `controlPlaneCertificateStatusSloIntegrated`
- `controlPlaneCertificateStatusSloHealthy`
- `controlPlaneCertificateStatusExactSourceHead`
- `controlPlaneCertificateStatusFixedCardinalityTelemetry`

`Available=false, Fresh=true` 表示远端刚发生短暂故障、但已验证 cache 仍在硬租约内；它不是长期降级
许可。`Fresh=false` 必须解释为请求 admission 已关闭。`controlPlaneCertificateRotationProductionReady`
继续为 false。

独立 SLO assessment v2 不复用 readiness 的瞬时语义：它以 closed violation vocabulary 检查启动宽限、
当前 source outage、exact source-head availability、最近成功刷新年龄、hard-expiry headroom、成熟刷新
失败率、成熟 admission deny-rate 和 `sourceHeadLag > maximumSourceHeadLag`。`BATCH_LIMIT` 仍作为吞吐趋势
计数保留，但不再冒充外部 backlog 事实。因此 cache fresh 且 source 暂时不可用时，请求仍可按 exact binding 放行，但 SLO 同时进入
`SLO_VIOLATED/SOURCE_UNAVAILABLE` 供告警消费。所有 meter tag 只来自 closed enum；target、authority、
URI、fingerprint、credential、reason detail 和异常文本永不进入 metric identity。

## 6. 机器契约与测试证据

本路径保留 v1 历史协议，并发布 v2 configuration、source response、source-head、source-head floor、monitor、
health 与 SLO closed JSON Schema。配置 schema 与两个 Spring profile、Java record、Java 25
configuration metadata 做精确字段反射测试；未知字段、disabled residual、system trust downgrade、可选
identity、重复 policy、超长配置、私钥字段和与 I/O/publication lifetime 必然冲突的 SLO 窗口均在
source I/O 前失败。

以下 82 项 exact source-head、HTTP source、durable floor、monitor/SLO、health、Tool Studio capability
和 demo preflight 聚焦门禁已通过，
0 failures、0 errors、0 skips：

```bash
mvn -f resource-gateway-examples/pom.xml test \
  -Dtest='ControlPlaneCertificateStatusSourceHeadTest,ControlPlaneCertificateStatusSourceHeadProtocolSchemaTest,ControlPlaneCertificateStatusSourceResponseProtocolTest,DatabaseControlPlaneCertificateStatusSourceHeadFloorTest,HttpControlPlaneCertificateStatusSourceTest,ControlPlaneCertificateStatusMonitorTest,ControlPlaneCertificateStatusTelemetryTest,ControlPlaneCertificateStatusSloMonitorTest,ControlPlaneCertificateStatusHealthTest,ControlPlaneCertificateStatusRuntimeConfigurationTest,ControlPlaneCertificateStatusProductSchemaTest,ToolStudioControlPlaneCertificateRotationCapabilityTest,VisualCanvasDemoScriptTest'
```

上一轮全量回归基线为：Resource Gateway `clean verify` 执行 3834 tests，0 failures、0 errors、
2 个条件浏览器跳过，并生成 Spring Boot 可执行 JAR；独立 test-kit `clean verify` 执行 230 tests，
0 failures、0 errors、0 skips，且将 57 份 testing Schema 与 5 份 Tool Studio Schema 打入发布 JAR，
普通 JAR、shaded JAR 和 public JavaDoc 门禁全部通过。

本次构建仍有三类非阻断工程告警：BLOGE `0.8.9-RC2` 的已发布 POM 缺少一项传递依赖版本、Mockito
仍使用 JDK 动态 agent、Selenium 在本机 Chrome CDP 150 上回退到 CDP 149。另有故障路径测试会输出
预期的 fail-closed/optimistic-lock WARN。它们不改变本次绿色结论，但应在依赖发布与测试日志治理中
消除，避免真实故障被噪声淹没。

## 7. 未完成边界

本增量不声明 enterprise PKI production ready，剩余根问题包括：

- certified CA event/OCSP/CRL normalizer 的语义一致性、签名 custody 与互操作认证；
- status authority key 和 source client identity 的无重启轮换、紧急撤销与恢复；
- 多区域 source HA、anti-equivocation witness、跨域 gossip 与 publication retention/compaction；
- 外部 alert routing、跨窗口 burn-rate、error-budget policy 与 pager 演练；
- production database、backup/restore、DR、split-brain、clock anomaly 与 chaos 认证；
- HSM/KMS custody、maker/checker 变更流程和跨版本 N/N-1 compatibility matrix。

在这些门禁关闭前，status product 只能被视为严格的 test/staging 产品路径和生产集成参考实现。
