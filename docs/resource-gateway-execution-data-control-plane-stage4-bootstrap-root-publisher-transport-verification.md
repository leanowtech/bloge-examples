# Stage 4：Bootstrap-root Publisher 固定 mTLS 传输验证

## 1. 结论

本增量关闭 bootstrap-root complete-chain 写侧仍依赖 JVM system trust、没有客户端身份、不能抵抗
企业 TLS 代理或错误 CA 替换的断点。`HttpExternalSequenceAnchorBootstrapRootPublisher` 现在可由统一
`ControlPlaneHttpTransport` 注入冻结后的传输策略；test/staging Spring 产品路径使用同一严格配置对象，
staging 在创建 publication journal 和协议 adapter 前强制：

1. PKIX 链验证必须成功；
2. JDK hostname verification 必须成功；
3. 已验证服务端证书链必须命中 1..16 个精确 SHA-256 SPKI pin；
4. 必须提交 deployment-owned PKCS#12 client certificate；
5. keystore 密码只能以 opaque secret reference 出现，并在 TLS context 构建后擦除；
6. `allow-insecure-loopback`、system-trust downgrade、部分 trust-store 配置和多个 secret resolver 均
   fail closed。

这不是“HTTPS 已开启”的重复包装。签名 response 负责应用层 publisher 身份和 request binding；固定
mTLS 负责连接到正确的网络端点并证明调用方工作负载身份。两层必须同时成立。

## 2. 根因与修复

### 2.1 原断点

旧 adapter 虽然执行严格 HTTPS、no redirect、Ed25519 signed response、conditional predecessor 和
content-addressed idempotency，但默认 `HttpClient` 使用 JVM system trust，且不携带 client certificate。
因此仍存在三类问题：

| 问题 | 原因 | 后果 |
| --- | --- | --- |
| 企业代理或错误公有 CA 可终止 TLS | 没有服务端 key pin | 流量可被导向持有另一张合法证书的端点 |
| publisher 无法认证 RG workload | 没有 mTLS client identity | 网络入口只能依赖 IP/token 等弱绑定 |
| 配置宣称安全但运行姿态不可见 | publisher descriptor 只有应用协议事实 | readiness 无法区分 system trust 与 pinned mTLS |

应用层 signed response 会阻止伪 endpoint 伪造成功，但无法阻止流量暴露、请求阻断、错误路由或
匿名调用抵达 publisher。安全模型不能把“最终签名失败”当作传输认证。

### 2.2 统一传输边界

新增 `ControlPlaneHttpTransport` 作为 testing control plane 的通用传输端口，拥有：

- bounded no-redirect `HttpClient` 创建；
- server/client authentication 的 payload-free descriptor；
- opaque credential resolver；
- “一个 transport instance 只代表一个冻结 client identity + server trust policy”的所有权约束。

`RecoveryFleetPublicationTransport` 保留为兼容子类型，既有 inventory 和 managed-root source 无需
协议迁移。publisher 依赖通用端口，不再依赖 recovery-fleet 的业务命名。

### 2.3 Spring preflight

`ExternalSequenceAnchorBootstrapRootPublicationRuntimeConfiguration` 新增
`ValidatedPublicationTransport`。publication journal 与 publisher 都依赖该 token，所以以下验证先于
journal/outbox 和协议 adapter：

- staging 必须同时设置 `transport.enabled=true` 与 `transport.required=true`；
- staging endpoint 必须是 strict HTTPS，禁止 loopback escape hatch；
- client keystore 必须是绝对、可读、含 private key 的 PKCS#12；
- private trust store 若配置，路径和 password ref 必须成对出现；
- pin 必须是 canonical `sha256:<64 lowercase hex>`；
- 只能存在一个 credential resolver；无自定义 resolver 时，demo adapter 只接受
  `env:VARIABLE_NAME`，不缓存 secret。
- publisher 不得与 recovery-fleet inventory 或 managed-root source 复用相同的 client-keystore path
  和 credential reference；该检查发生在 secret resolution 和 `database.jdbc()` 之前。

test profile 保留 system-trust adapter，用于本地 HTTP loopback 协议测试和迁移验证；它不能在 staging
被误认为合格配置。

## 3. 配置

启用 staging publication 时，至少增加：

```bash
export RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_ENABLED=true
export RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_ENDPOINT=https://root-publisher.example/publications
export RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT_ENABLED=true
export RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT_REQUIRED=true
export RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT_TRUST_STORE_PATH=/run/rg/publisher-trust.p12
export RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT_TRUST_STORE_PASSWORD_REF=env:RG_PUBLISHER_TRUST_PASSWORD
export RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT_CLIENT_KEY_STORE_PATH=/run/rg/publisher-client.p12
export RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT_CLIENT_KEY_STORE_PASSWORD_REF=env:RG_PUBLISHER_CLIENT_PASSWORD
export RG_TEST_BOOTSTRAP_ROOT_PUBLICATION_TRANSPORT_SERVER_SPKI_PINS=sha256:<64-lowercase-hex>
```

私有 trust store 可省略，此时 PKIX 使用 JVM roots；SPKI pin 与 client identity 仍不可省略。生产交付
建议使用私有 enterprise roots，并让 publisher client identity 与 inventory source、managed-root source
分属独立 workload identity。Spring preflight 与 demo 启动脚本都会在这些组件同时启用时拒绝复用相同
client-keystore path 和 credential reference；正式部署还应由 PKI issuer/SAN policy 约束不同用途证书，
不能只依赖配置引用。

## 4. 可观测性

`ExternalSequenceAnchorBootstrapRootPublicationHealth` 新增四个固定基数字段：

- `transportSystemTrustStore`
- `transportPrivateTrustStore`
- `transportServerSpkiPinned`
- `transportMutualTls`

它们不包含 endpoint、文件路径、secret ref、SPKI pin、证书 subject、issuer、serial 或 key id。health
只陈述本地冻结配置，不制造远端 probe；真实 publication 仍必须通过 TLS 与完整 signed-response 协议。
`ExternalSequenceAnchorBootstrapRootPublicationService.Snapshot` 因增加 transport 子投影升级为 v2；旧 v1
Java 构造面保留并显式映射为 system-trust compatibility truth，避免静默伪造 pinned mTLS。

## 5. 验证矩阵

| 场景 | 期望 |
| --- | --- |
| 私有 CA + 正确 hostname + 正确 pin + 正确 client cert | HTTPS handler 观察到预期 client principal |
| PKIX 成功但 SPKI pin 错误 | TLS handshake 在 HTTP handler 前失败，publisher 返回 `UNAVAILABLE` |
| system trust/匿名 client 访问私有 mTLS publisher | handler 不接收请求，publisher 返回 `UNAVAILABLE` |
| staging 启用 publisher 但 transport disabled/optional | Spring context 在 publication runtime 接线前失败 |
| publisher 与 inventory/managed-root 复用 client identity 配置 | secret resolution 和数据库建表前失败 |
| staging 嵌套配置 + opaque resolver + PKCS#12 material | Spring publisher descriptor 与 Actuator 均报告 pinned mTLS |
| test profile 未配置 transport | 保留 system-trust compatibility，health 如实报告未 pin/未 mTLS |
| script staging transport downgrade | build 与 service startup 前退出，不回显 secret |

主要测试：

- `HttpExternalSequenceAnchorBootstrapRootPublisherTest`
- `ExternalSequenceAnchorBootstrapRootPublicationRuntimeConfigurationTest`
- `ExternalSequenceAnchorBootstrapRootPublicationHealthTest`
- `RecoveryFleetPublicationTransportTest`
- `VisualCanvasDemoScriptTest`

真实 TLS 测试动态生成独立 CA、server/client certificates 与 PKCS#12 stores，不使用伪造
`HttpClient` 来替代 handshake 证据。

本增量 publisher、publication runtime/health、journal/supervisor 与启动脚本联合聚焦门禁执行
66 tests，0 failures、0 errors、0 skips；五个改动公共类型通过
`javadoc --release 25 -Werror -Xdoclint:all`，0 warnings、0 errors。

冻结源码后的独占 Resource Gateway `clean verify` 执行 3589 tests，0 failures、0 errors、2 个条件
浏览器跳过，并成功重打包 Spring Boot 可执行 JAR。JAR 已核验同时包含通用
`ControlPlaneHttpTransport` 与 `HttpExternalSequenceAnchorBootstrapRootPublisher` 产品类。

## 6. 明确不宣称

本增量只关闭 bootstrap-root publisher 写侧传输认证，不代表整个信任链达到 production readiness：

1. managed notary trust publication 与 bootstrap-root bundle 的读侧尚未接入固定 mTLS；
2. publisher signed-response key 仍是静态配置，尚未实现 witnessed hot rotation；
3. client certificate 尚未绑定强制 issuer/SAN/EKU/workload policy，当前由 PKIX、pin 和 publisher 端
   mTLS policy 共同承担；
4. private key 文件仍由 JVM keystore 加载，不等于 HSM/KMS non-exportable custody；
5. 单 publisher 仍可能 equivocate，尚无 publisher quorum、transparency gossip 或 WORM 证明；
6. production profile 仍物理关闭，目标数据库、HA、DR、chaos、容量和外部 SLO 尚未认证。

下一子步应复用同一个 `ControlPlaneHttpTransport`，分别给 external notary、managed notary trust
publication 和 bootstrap-root complete-chain bundle 注入独立 transport policy，并禁止两个读侧与
publisher 复用 workload identity。完成后才能把“notary/trust/bootstrap-root transport pinning”整体
标记为关闭。
