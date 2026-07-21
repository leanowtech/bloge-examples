# Stage 4 Certificate Rotation Event Watcher 产品验收

## 1. 结论

证书轮换事件分发已从 page/cursor 内核推进为可运行的 test/staging 产品路径：严格 HTTPS source、
stable serving-slot durable cursor、bounded watcher、Spring 生命周期、Actuator health、Tool Studio
capability、profile YAML、Spring metadata 与 demo preflight 已接线。

产品执行顺序固定为：

1. convergence serving admission 成立后，按 durable committed head 请求唯一后继页面；
2. 在任何 event apply 前，将 exact page fingerprint durable stage；
3. 页面内每个 event 逐一进入现有 M-of-N 签名、binding、material 与 generation floor；
4. 只有全部 event 返回 `APPLIED/REPLAYED` 才 commit 页面；
5. 任一失败保留 staged page，重启只能从 source 获得 exact replay 后修复。

source 的 mTLS workload identity 只授权“交付页面”，不授权“轮换证书”；page fingerprint 证明
page-chain 完整性，也不替代 event 的外部签名授权。

## 2. 产品装配边界

`ControlPlaneCertificateRotationEventRuntimeConfiguration` 仅存在于 `test` 或 `staging` 且
`production` 未激活的进程。事件 source 启用时，启动必须同时证明：

- signed certificate rotation 为 `enabled=true, required=true`；
- all-replica convergence 为 `enabled=true, required=true`；
- cursor 使用 convergence `instance-id` 稳定 serving slot，而不是每次变化的 `startup-id`；
- source transport 使用 private PKCS#12 trust、server SPKI pin、mTLS 与双端 certificate identity；
- baseline sequence/fingerprint、poll/page/body/deadline/skew/lifetime 全部在冻结边界内；
- staging 禁止 HTTP loopback escape。

Spring 关闭顺序由 bean 生命周期保证：watcher 先停止 scheduler，再由测试运行时关闭数据库。
watcher 单线程、固定 delay、每轮最多 32 页，不创建无界队列。

## 3. 启用方式

先配置并启用 signed rotation 与 convergence，再提供事件 source 配置。核心变量如下：

```bash
export RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_ENABLED=true
export RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_REQUIRED=true
export RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_ENDPOINT_URI=https://ca.example.test/v1/rotation-events
export RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_BASELINE_SEQUENCE=0
export RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_BASELINE_PAGE_FINGERPRINT=sha256:<64-hex>

export RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_TRANSPORT_ENABLED=true
export RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_TRANSPORT_REQUIRED=true
export RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_TRANSPORT_TRUST_STORE_PATH=/deployment/ca-event-trust.p12
export RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_TRANSPORT_TRUST_STORE_PASSWORD_REF=env:RG_CA_EVENT_TRUST_PASSWORD
export RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_TRANSPORT_CLIENT_KEY_STORE_PATH=/deployment/rg-ca-event-client.p12
export RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_TRANSPORT_CLIENT_KEY_STORE_PASSWORD_REF=env:RG_CA_EVENT_CLIENT_PASSWORD
export RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_TRANSPORT_SERVER_SPKI_PINS=sha256:<64-hex>
export RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_TRANSPORT_CERTIFICATE_IDENTITY_REQUIRED=true
export RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_TRANSPORT_EXPECTED_CLIENT_SUBJECT_DN='CN=resource-gateway-ca-events,O=Example'
export RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_TRANSPORT_EXPECTED_CLIENT_URI_SAN=spiffe://example.test/resource-gateway/ca-events
export RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_TRANSPORT_CLIENT_ISSUER_SPKI_PINS=sha256:<64-hex>
export RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_TRANSPORT_EXPECTED_SERVER_URI_SAN=spiffe://example.test/ca/rotation-events
export RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_TRANSPORT_SERVER_ISSUER_SPKI_PINS=sha256:<64-hex>
```

密码值只放在 `*_PASSWORD_REF` 指向的环境变量或部署 secret resolver 中，不能写入 profile、catalog、
日志或 capability。使用现有脚本启动时，staging 会在 Maven build 前验证依赖、HTTPS、baseline、边界、
文件可读性、credential ref、SPKI 与 workload identity：

```bash
scripts/start-visual-canvas-demo.sh --profile staging
scripts/stop-visual-canvas-demo.sh
```

## 4. 运维判读

Actuator health 只从 cached watcher descriptor 读取，不触发 source 或数据库 I/O。`IDLE`、`APPLIED`
和 `RUNTIME_FENCED` 为健康状态；后者表示本副本被 convergence 有意暂停且未访问远端。`NEW`、source/
protocol/cursor/apply 故障和 `CLOSED` 均为 DOWN。

Tool Studio capability 独立公开以下事实，不把它们折叠成一个宽松布尔值：

- `controlPlaneCertificateRotationEventDeliveryIntegrated`
- `controlPlaneCertificateRotationEventDeliveryReady`
- `controlPlaneCertificateRotationEventDeliveryDurableCursor`
- `controlPlaneCertificateRotationEventDeliveryAuthenticatedSource`
- `controlPlaneCertificateRotationEventDeliverySourceMutualTls`
- `controlPlaneCertificateRotationEventDeliverySourceCertificateIdentityBound`

descriptor 异常时仅保留 `Integrated=true`，其余事实 fail closed；URI、query、exception text 不会进入
capability。`controlPlaneCertificateRotationProductionReady` 继续为 false。

## 5. 失败语义

| 故障 | 可见状态 | Durable cursor | 网络行为 |
| --- | --- | --- | --- |
| serving admission fenced | `RUNTIME_FENCED` | 不变 | 零 source I/O |
| timeout、408/425/429/5xx | `SOURCE_UNAVAILABLE` | 不 stage/commit | 下轮重试 |
| media/header/JSON/page-chain 错误 | `PROTOCOL_REJECTED` | 不 stage/commit | 必须修复 source |
| cursor corruption/outage | `CURSOR_UNAVAILABLE` | fail closed | 不 apply |
| gap/fork/competing page | `CURSOR_CONFLICT` | committed 不变 | 人工处置 |
| event authorization/material/generation 失败 | `APPLY_BLOCKED` | page 保持 staged | exact replay 修复 |
| 全部 event accepted | `APPLIED` | exact page commit | 可继续取后继 |

## 6. 测试证据

以下 76 项联合门禁在隔离工作树执行，0 failures、0 errors、0 skips：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ControlPlaneCertificateRotationEventPageTest,\
DatabaseControlPlaneCertificateRotationEventCursorTest,\
ControlPlaneCertificateRotationEventCursorProtocolSchemaTest,\
HttpControlPlaneCertificateRotationEventSourceTest,\
ControlPlaneCertificateRotationEventWatcherTest,\
ControlPlaneCertificateRotationEventWatcherProtocolSchemaTest,\
ControlPlaneCertificateRotationEventSourcePropertiesTest,\
ControlPlaneCertificateRotationEventWatcherHealthTest,\
ControlPlaneCertificateRotationEventRuntimeConfigurationTest,\
ControlPlaneCertificateRotationEventProductSchemaTest,\
ToolStudioControlPlaneCertificateRotationCapabilityTest,\
VisualCanvasDemoScriptTest test
```

覆盖 page/cursor tamper 与并发、真实 loopback HTTP 协议、真实 PKCS#12 product transport、partial
apply/replay repair、Spring profile/依赖降级、health 闭集、Schema/metadata/YAML 精确字段、capability
故障脱敏，以及 staging build 前预检。

最终全量回归在提交 `d37fc515` 的独立源码与 `target` 快照中通过：Resource Gateway
`clean verify` 执行 3817 tests，0 failures、0 errors、2 个条件浏览器跳过，并生成 Spring Boot
可执行 JAR；独立 test-kit `clean verify` 执行 230 tests，0 failures、0 errors、0 skips，且通过
权威 Schema 打包、普通/shaded JAR 与 public JavaDoc 门禁。

## 7. 未完成边界

本子步不声明企业 PKI production ready，后续仍需：

- CA source retention/compaction、分页 backlog 协议、限流预算与跨区域 HA；
- event source 自身 client certificate 的无重启轮换与紧急吊销；
- freshness/backlog SLO、Micrometer 固定基数指标和外部 alert routing；
- production database、backup/restore、DR、split-brain 与 chaos 认证；
- HSM/KMS custody、CA publisher anti-equivocation 和受治理 source failover；
- 生产 rollout、回滚与跨版本兼容矩阵。
