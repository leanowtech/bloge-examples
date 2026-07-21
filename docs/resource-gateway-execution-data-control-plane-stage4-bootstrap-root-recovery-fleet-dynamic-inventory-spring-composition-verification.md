# Stage 4 bootstrap-root recovery fleet dynamic inventory Spring composition verification

## 1. 结论

本子步关闭 dynamic recovery-fleet inventory 只能由宿主手工构造的交付缺口。在 `test`/`staging`
profile 下，Resource Gateway 可以从 public-only strict properties 自动装配：

- witnessed HTTPS dynamic inventory authority；
- caller-owned reviewed lane resolver；
- 默认 database publication/witness floor，或唯一 custom durable floor；
- 既有 database coordinator、worker、scheduler、fleet health 与 inventory health。

这不是把治理签名职责搬进 Resource Gateway。配置面不接受 signer private key、provider credential 或远端
lane implementation；它只消费公开验证材料和 reviewed local runtime catalog。`production` profile 仍物理
排除整套 fleet composition。

## 2. 根因

手工 bean 组装的问题不是代码多几行，而是关键安全顺序由每个 embedding application 自己重写：

1. 网络 bootstrap 可能早于 trust、topology 和 profile 校验；
2. database floor 可能在无效配置下先建表，留下半初始化状态；
3. staging 可能误用 static fallback，失去 refresh、signed revocation 和 witness；
4. 多个 resolver、inventory 或 floor 候选可能被 bean 顺序静默选择；
5. authority refresh thread 的关闭 ownership 容易遗漏。

因此本实现冻结 composition protocol，而不是提供一个宽松 convenience factory。

## 3. Bean 图与启动顺序

```text
FleetProperties + DynamicInventoryProperties + active profiles
  -> ValidatedFleetConfiguration                 (local/stateless)
       -> ValidatedDynamicInventoryConfiguration (local/stateless)
            -> durable publication floor         (stateful DDL)
            -> dynamic authority                  (bounded remote bootstrap)
                 -> ValidatedFleetRuntime         (exact runtime reverse binding)
                      -> coordinator -> worker -> scheduler
                      -> fleet health + inventory health
```

第一阶段校验 single-lane/fleet 互斥、staging requirement、fleet identity 和 partition。第二阶段严格解析
public key JSON，验证 deployment/witness trust domain 与 authority/public-key material 相互独立，固定
M-of-N threshold、policy、deployment/artifact/fleet binding、唯一 resolver、URI 和 duration。只有两个 token
都创建成功后，默认 floor 才能建表，authority 才能访问远端。

最终 runtime preflight 不信任“bean 创建成功”这一事实。它重新读取 snapshot、observation 和 descriptor，
要求 generation、lane count、fleet topology 完全一致；required 模式还要求 source type 精确为 dynamic，
并且 `automaticRefresh`、`signedRevocation`、`durableGenerationFloor`、`witnessedPublications` 全为 true。

## 4. 配置合同

fleet 配置继续使用
`gateway.testing.external-sequence-anchor.bootstrap-root-recovery-fleet`。dynamic 配置使用独立 sibling prefix：

`gateway.testing.external-sequence-anchor.bootstrap-root-recovery-fleet-dynamic-inventory`

| Environment variable suffix | Required when enabled | Rule |
| --- | --- | --- |
| `ENABLED` | yes | test/staging 显式开启内置 dynamic authority |
| `REQUIRED` | staging 强制 | 禁止 static/non-witnessed fallback |
| `DEPLOYMENT_SCOPE_ID` | yes | exact tenant/environment deployment scope |
| `ARTIFACT_FINGERPRINT` | yes | `sha256:` + 64 lowercase hex |
| `TRUST_DOMAIN` | yes | deployment inventory/publication trust domain |
| `ACCEPTED_POLICY_FINGERPRINTS` | yes | comma-separated accepted policy fingerprints |
| `SIGNATURE_THRESHOLD` | yes | deployment distinct-authority M-of-N threshold |
| `AUTHORITY_KEYS_JSON` | yes | strict public Ed25519 key array |
| `PUBLICATION_URI` | yes | HTTPS；test 可显式允许 localhost HTTP |
| `REFRESH_INTERVAL_SECONDS` | default 30 | 1 秒..1 小时 |
| `REQUEST_TIMEOUT_MS` | default 3000 | 100 毫秒..30 秒 |
| `MAXIMUM_SNAPSHOT_AGE_SECONDS` | default 60 | 至少 interval + timeout，且最多 24 小时 |
| `ALLOW_INSECURE_LOOPBACK` | default false | 仅 test profile 有效；staging 永远拒绝 |
| `WITNESS_DOMAIN` | yes | 必须与 deployment trust domain 不同 |
| `WITNESS_SIGNATURE_THRESHOLD` | yes | witness distinct-authority M-of-N threshold |
| `WITNESS_AUTHORITY_KEYS_JSON` | yes | 与 deployment authority/public key 不重叠 |

完整环境变量名以前缀
`RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_` 拼接表中 suffix。properties 使用
`ignoreUnknownFields=false`，JSON parser 同时拒绝 duplicate、unknown 和 trailing token。disabled 状态仍
携带 source 配置会被视为 half-configuration；唯一例外是单独的 `required=true`，它允许宿主提供满足同等
descriptor contract 的 custom certified dynamic authority。

## 5. Profile 与降级策略

| Active profile / mode | Result |
| --- | --- |
| no `test`/`staging` | configuration absent |
| contains `production` | configuration absent，即使同时有 `test` |
| test + dynamic disabled | 允许 caller-owned local/static inventory，用于 isolated tests |
| test + dynamic enabled | 自动装配 witnessed authority |
| staging + fleet enabled | 必须 `required=true`，且最终 inventory 必须满足 dynamic capability contract |
| staging + insecure loopback | 启动前拒绝，不访问网络、不建 recovery state |

staging requirement 在代码中再次强制，不能仅靠 `application-staging.yml` 默认值。其目的不是防止运维修改
配置文件，而是防止一次错误的环境变量覆盖悄悄把治理能力降级。

## 6. Ownership 与替换点

宿主必须提供唯一 `LaneResolver`。它把 signed lane key 解析到 reviewed local runtime descriptor，但不得
发网络请求，也不得从 publication 动态加载可执行代码。默认 floor 使用共享 `TestRuntimeDatabase`；宿主可
替换为唯一 custom floor，但 `durable()` 必须为 true。

Spring 拥有内置 dynamic authority，并通过 `destroyMethod="close"` 停止其 refresh scheduler。scheduler、
worker 和 authority 的依赖关系保证关闭顺序先停止新 recovery admission，再停止远端 refresh；caller-owned
resolver/database 最后由宿主关闭。配置不会关闭宿主提供的对象。

## 7. Fail-closed 矩阵

| Failure | Latest permitted side effect | Result |
| --- | --- | --- |
| unknown/private-like property | property binding | startup rejected |
| disabled half-configuration | record construction | startup rejected |
| staging required=false | stateless fleet preflight | startup rejected |
| malformed key/trust/binding/URI/duration | stateless dynamic preflight | no floor DDL, no HTTP |
| zero or multiple resolver | stateless dynamic preflight | no floor DDL, no HTTP |
| zero or multiple inventory candidate | dependency resolution | no fallback, no HTTP |
| custom non-durable floor | authority local constructor guard | no HTTP |
| remote/protocol/signature/floor failure | bounded bootstrap/refresh | authority unavailable; runtime not admitted |
| production profile present | configuration discovery | no bean, no DDL, no HTTP |

所有配置失败使用固定低基数消息，不把 key、URI、policy、payload 或异常链细节投影到健康接口。

## 8. 验证

`ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfigurationTest` 使用真实 Spring context、
JDK `HttpServer`、Ed25519 deployment/witness signatures 和 H2 database，验证成功组合与上述失败矩阵。
它和 `ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfigurationTest` 联合证明旧 caller-owned 模式
保持兼容，新增模式不会放宽生产隔离或 durable coordinator gate；两种 configuration 注册顺序均只产生
一个 inventory health bean。

聚焦门禁：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfigurationTest,ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfigurationTest \
  test
```

相关 configuration、authority、health 和 floor 类型同时进入 strict JavaDoc 门禁；全量
`resource-gateway-examples clean verify` 仍是提交条件。实测 Spring composition 21 tests、包含后续
capability protocol 的完整 recovery-fleet 142 tests 均为 0 failures、0 errors、0 skips；strict JavaDoc 为
0 warnings、0 errors；全量 `clean verify` 执行 3493 tests，0 failures、0 errors、2 条环境条件跳过，并完成
可执行 JAR 重打包。

## 9. 未完成门禁

本子步只关闭 test/staging 产品接线，不解除以下生产门禁：

1. deployment/witness 原子双 trust-root publication、验证器、durable key floor、strict HTTPS/ETag
   refresh、unknown-key refresh、dynamic authority 与 aggregate health 已闭合；managed-root Spring/
   capability 接线、配置 Schema 和 staging downgrade fence 仍待完成；
2. capability truth 已由后续子步闭合；配置 metadata、外部告警/SLO 和跨副本 convergence readiness；
3. mTLS、certificate pinning、DNS/proxy policy 与 response-key rotation；
4. external Byzantine anchor、publisher/witness HA、equivocation detection 和跨区 transparency；
5. PostgreSQL/MySQL、rolling upgrade、backup/restore、DR、chaos/soak 与容量认证；
6. online partition rebalance 和受治理 fleet identity migration；
7. enterprise IAM/PDP、HSM/KMS custody 与 production profile composition。
