# Stage 4 Certificate Status Admission 产品验收

## 1. 结论

证书状态链已从可嵌入 kernel 推进为 test/staging 产品路径：外部 CA event、OCSP、CRL 先由企业
adapter 归一化为完整 signed status publication，Resource Gateway 再执行严格 source protocol、独立
M-of-N 验签、database-clock monotonic floor、dual-clock local admission 和逐请求 exact binding。

固定执行顺序为：

1. source 通过独立 private PKIX、SPKI pin、mTLS 与双端 workload identity 获取唯一后继 publication；
2. trust store 校验 trust domain、policy、M-of-N 签名、key lifecycle 与完整 publication commitment；
3. durable floor 校验连续 sequence、predecessor、整行 fingerprint、唯一 publication id 和完整 target 清单；
4. monitor 先提交 durable snapshot，再原子替换进程内 admission cache；
5. 每条 rotating transport 在真实 handler I/O 前校验 exact target、generation 与 settings fingerprint；
6. `REVOKED`、`UNKNOWN`、binding mismatch 或 hard expiry 一律不发送请求。

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
```

密码值只存在于 `*_PASSWORD_REF` 指向的 secret 中。使用演示脚本时，staging 会在 Maven build 前
检查依赖、scope、HTTPS、baseline、public-only authority JSON、I/O/scheduler/batch bounds、文件可读性、
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
| publication 漏 target 或整行被篡改 | 不推进 | 不替换 | 保留旧 lease 至硬过期 |
| target 为 `REVOKED` 或 `UNKNOWN` | 新完整 snapshot | 对该 binding 拒绝 | handler 前阻断 |
| generation/settings fingerprint 不匹配 | 不需要写 floor | exact lookup miss | handler 前阻断 |
| trust/floor/cache 不可用 | 不确定 | unavailable | 全部失败关闭 |

## 5. 运维判读

Actuator status health 固定为 16 个低基数字段，区分 `sourceAvailable`、`admissionFresh`、monitor 状态和
runtime 状态；descriptor 异常时使用固定 reason code，不携带异常文本。Tool Studio capability 公开：

- `controlPlaneCertificateStatusIntegrated`
- `controlPlaneCertificateStatusAvailable`
- `controlPlaneCertificateStatusFresh`
- `controlPlaneCertificateRevocationAdmission`

`Available=false, Fresh=true` 表示远端刚发生短暂故障、但已验证 cache 仍在硬租约内；它不是长期降级
许可。`Fresh=false` 必须解释为请求 admission 已关闭。`controlPlaneCertificateRotationProductionReady`
继续为 false。

## 6. 机器契约与测试证据

本路径发布六个 closed JSON Schema：configuration、source descriptor、trust-store descriptor、monitor
descriptor、admission descriptor 和 health。配置 schema 与两个 Spring profile、Java record、Java 25
configuration metadata 做精确字段反射测试；未知字段、disabled residual、system trust downgrade、可选
identity、重复 policy、超长配置与私钥字段均在 source I/O 前失败。

以下 68 项状态链、真实 TLS source、durable floor、逐请求 gate 和 live transport 联合门禁已通过，
0 failures、0 errors、0 skips：

```bash
mvn -f resource-gateway-examples/pom.xml test \
  -Dtest='*ControlPlaneCertificateStatus*,HttpControlPlaneCertificateStatusSourceTest,DatabaseControlPlaneCertificateStatusFloorTest,ControlPlaneCertificateRotationRuntimeTest,RotatingControlPlaneHttpTransportTest'
```

另有 56 项 profile/metadata/YAML/demo preflight 与 certificate rotation event delivery 联合产品测试
通过，证明两个独立 source 可以在同一 composition root 中保持协议、凭据和 client identity 边界。

最终全量回归基线同样通过：Resource Gateway `clean verify` 执行 3817 tests，0 failures、0 errors、
2 个条件浏览器跳过，并生成 Spring Boot 可执行 JAR；独立 test-kit `clean verify` 执行 230 tests，
0 failures、0 errors、0 skips，且将 56 份 testing Schema 与 5 份 Tool Studio Schema 打入发布 JAR，
普通 JAR、shaded JAR 和 public JavaDoc 门禁全部通过。

## 7. 未完成边界

本增量不声明 enterprise PKI production ready，剩余根问题包括：

- certified CA event/OCSP/CRL normalizer 的语义一致性、签名 custody 与互操作认证；
- status authority key 和 source client identity 的无重启轮换、紧急撤销与恢复；
- 多区域 source HA、anti-equivocation witness、publication retention/compaction 与 backlog contract；
- freshness/backlog/deny-rate 固定基数指标、外部 alert routing 与 SLO burn-rate；
- production database、backup/restore、DR、split-brain、clock anomaly 与 chaos 认证；
- HSM/KMS custody、maker/checker 变更流程和跨版本 N/N-1 compatibility matrix。

在这些门禁关闭前，status product 只能被视为严格的 test/staging 产品路径和生产集成参考实现。
