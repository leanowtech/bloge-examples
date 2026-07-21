# Certificate Status Exact Source-Head Protocol Verification

## 1. 根因与边界

证书状态 monitor 的 `BATCH_LIMIT` 只能证明本地单轮处理达到上限，不能证明外部规范化发布日志还剩多少条，
也不能区分“恰好处理完”“持续积压”和“外部 head 已回退”。因此它只能形成 possible-backlog signal，
不能成为精确 backlog SLO 或发布门禁证据。

本子步冻结独立的 `bloge.controlPlaneCertificateStatusSourceHead.v1`。它让外部状态 authority 对
`deploymentScopeId + policyFingerprint + headSequence + headPublicationFingerprint + validity window`
进行 M-of-N Ed25519 签名，不修改已经发布的 immutable status publication，也不把 endpoint、证书、
credential 或 authority key material 带入运行证据。

## 2. 已实现协议

- `ControlPlaneCertificateStatusSourceHead`：closed envelope、canonical material fingerprint、1..32 个
  distinct-authority signatures 和数据库可持久化时间精度；
- `ControlPlaneCertificateStatusTrustStore.verifySourceHead`：复用独立 status authority trust domain，
  校验 exact scope、accepted policy、hard expiry、最大 24 小时有效期、签名时刻、canonical fingerprint
  和 M-of-N quorum；
- `SourceHeadVerification`：拒绝结果不返回 attestation identity、head cursor 或 fingerprint；
- `control-plane-certificate-status-source-head-v1.schema.json`：Draft 2020-12 closed Schema，精确反射
  envelope/material/signature，禁止 private key、certificate、secret、endpoint、OCSP/CRL payload 和异常字段。

零 baseline head 也是一等合法状态，其 fingerprint 必须来自部署固定 baseline。未知 key 不贡献 quorum，
重复 authority、坏签名、错误 scope/policy、future/expired/超长 attestation 和 fingerprint tamper 均失败关闭。

## 3. 测试证据

协议、Schema 与既有 publication 回归共 14 tests，0 failures、0 errors、0 skips：

```bash
mvn -f resource-gateway-examples/pom.xml test \
  -Dtest=ControlPlaneCertificateStatusSourceHeadTest,ControlPlaneCertificateStatusSourceHeadProtocolSchemaTest,ControlPlaneCertificateStatusPublicationTest,ControlPlaneCertificateStatusProtocolSchemaTest
```

## 4. 尚未声明完成

本子步只冻结可验证协议，不声明精确 backlog 已产品化。后续必须继续闭合：

- source-head 独立 HTTPS/mTLS/pinning transport 与严格媒体类型；
- database-clock durable floor、attestation-id journal、rollback/fork/reuse/tamper detection；
- publication floor 与 source-head floor 的同 scope 一致性和跨重启恢复；
- monitor 的 exact lag、hard-expiry 与 source-head availability 观测；
- SLO/Actuator/Micrometer/Tool Studio、profile、Schema metadata 和 demo preflight；
- 多 authority equivocation witness、retention/compaction 和生产数据库/HA/DR/chaos 认证。

在这些闭合前，现有 `CATCH_UP_BACKLOG` 仍只能解释为本地 possible-backlog signal。
