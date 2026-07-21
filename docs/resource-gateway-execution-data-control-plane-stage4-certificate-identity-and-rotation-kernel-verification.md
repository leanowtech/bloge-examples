# Stage 4 Control-plane 证书身份与轮换内核验收

## 1. 结论

本增量关闭两个容易被混为一谈的根因，但只交付可复用内核，不提升产品接线声明：

1. mTLS 握手成功不等于连接的是预期 workload。新增策略在 PKIX、hostname 和 server SPKI pin
   之外，约束 client Subject、双端 URI SAN、双端 EKU/KeyUsage 和独立 issuer SPKI pin。
2. 把新证书写到原路径不等于安全热轮换。新增 rotation kernel 先在旧代之外加载并验真完整候选，
   再按连续 generation 和受限 activation time 原子切换；任何请求只使用完整旧代或完整新代。

九条 external-anchor 读侧、publisher 写侧、typed properties、staging preflight、health/capability
仍未接入该新策略。本增量不能被解释为企业 PKI 或生产证书轮换已经开放。

## 2. 根因模型

仅有 `PKIX + hostname + leaf SPKI pin + mTLS` 仍存在四类盲区：

| 盲区 | 失败结果 | 根治手段 |
| --- | --- | --- |
| 同一 CA 签出错误 workload | TLS 成功但越权访问错误服务 | 精确 Subject/URI SAN + role EKU |
| client keystore 含多个 key entry | JDK alias 选择可能漂移 | 启动期要求唯一 private-key identity |
| trust store 含多个企业 root | PKIX 可能落到非预期 issuer | 先按 issuer SPKI pin 缩窄 trust anchor 再做 PKIX |
| 原地覆盖 keystore | trust/key/pin 非原子、请求阻塞、失败后难回退 | generation 化 immutable transport + 两阶段候选发布 |

## 3. 身份不变量

`ControlPlaneCertificateIdentityPolicy` 只接受两种形态：全空兼容策略，或字段完整的绑定策略。绑定
策略强制：

- client keystore 恰好一个 key entry，证书链至少两层，并以命中独立 issuer pin 的 CA
  作为 `TrustAnchor` 在激活时刻完成 PKIX path validation；
- client leaf 在激活时刻有效，包含 `clientAuth` EKU 和 digital-signature KeyUsage；
- client Subject DN 与配置精确匹配，client 只能携带一个且必须匹配的 workload URI SAN；
- server PKIX trust anchor 先按 server issuer pin 收窄；
- server leaf 包含 `serverAuth` EKU、digital-signature KeyUsage 和精确 server URI SAN；
- policy、异常和 descriptor 不投影 keystore 密码、私钥、路径或实际证书值。

兼容策略保留原有 TLS 行为，且 `certificateIdentityBound=false`，防止旧调用方在未配置身份约束时
获得虚假的安全能力声明。

## 4. 轮换不变量

`RotatingControlPlaneHttpTransport` 的状态只有一个 active generation 和至多一个
pending successor：

1. 初始 generation 必须为正数，且初始证书在最小重叠窗口后仍有效。
2. successor 必须是 `current + 1`；rollback、跳代、第二 pending 和超前窗口越界先于 secret
   resolution 失败。
3. 候选 keystore、trust store、pins 和 identity policy 在状态锁外完整加载；慢 secret manager 或磁盘
   I/O 不阻塞旧代请求。
4. 加载后以第二次锁内比较确认 active 未变化、pending 仍为空且激活时刻未越过，才发布候选。
5. successor 在激活时刻必须有效，且新旧证书在激活后继续满足最小重叠窗口。
6. 稳定 `HttpClient` proxy 每个请求只选择一次 immutable generation；已开始的旧代请求不混入新代
   SSLContext，新请求在激活后获得新代 client。
7. active 已过期且没有可激活 successor 时，在网络 I/O 前 fail closed。

轮换入口当前是 Java API。调用方必须先认证签名 inventory 或 secret-manager event；内核不把未经认证
的文件变更当作轮换授权。

## 5. 代码证据

- `ControlPlaneCertificateIdentityPolicy`：证书 profile、Subject/SAN、issuer 与激活时刻校验。
- `PinnedMutualTlsRecoveryFleetPublicationTransport`：受 issuer policy 约束的 trust manager、绑定身份
  descriptor 和可供轮换判定的 client certificate lifetime。
- `RotatingControlPlaneHttpTransport`：两阶段 stage、连续 generation、重叠窗口、
  request-level 原子选择和过期 fail-closed。
- `RecoveryFleetPublicationTlsFixture`：真实 CA、server/client 证书、双向 TLS server 和 client identity
  轮换材料。

## 6. 测试证据

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ControlPlaneCertificateIdentityPolicyTest,\
RecoveryFleetPublicationTransportTest,\
RotatingControlPlaneHttpTransportTest test
```

验收结果：21 tests，0 failures、0 errors、0 skips。覆盖：

- 真实 mTLS 下精确 client/server workload identity 成功；
- client Subject、URI SAN、额外 workload URI、issuer、`clientAuth` 缺失在 transport 创建期失败；
- server URI SAN 与 issuer 错误在 HTTP handler 前失败；
- credential 字符在成功和失败路径擦除，descriptor 不泄漏路径/ref/pin；
- 连续 generation 在同一稳定 client 上按时切换 client principal；
- rollback、超前激活、第二 pending 在 secret resolution 前失败；
- 非法候选不扰动 active generation；
- 候选 credential 加载被阻塞时旧代真实 TLS 请求仍成功；
- active identity 过期后请求在 handler 前 fail closed。

另执行复用 transport 的 79 项联合协议回归，0 failures、0 errors、0 skips。

最终全量门禁：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

验收结果：3613 tests，0 failures、0 errors、2 skips；并成功生成 37 MB 的
`bloge-examples-resource-gateway-1.0.0.jar` Spring Boot 可执行产物。

## 7. 下一门禁

下一增量必须完成 typed certificate identity/rotation properties、严格 Schema、staging fail-fast、脚本
preflight、固定基数 health/capability 和九条读侧/写侧产品接线。之后才可关闭“证书身份绑定已产品化”。
仍不在本内核声明内的是企业 CA 签发/吊销事件认证、OCSP/CRL 策略、HSM 私钥 custody、跨副本一致
轮换、secret manager lease、生产 HA/DR/chaos 和目标数据库认证。
