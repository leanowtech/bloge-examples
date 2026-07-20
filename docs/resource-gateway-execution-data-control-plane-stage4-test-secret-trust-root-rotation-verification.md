# Stage 4 Test-Secret Serving-Inventory 运行密钥轮换验证

## 1. 根因与能力边界

动态 test-secret serving inventory 已能无重启刷新、撤销和恢复，但 inventory publication 的
deployment key 与 witness key 仍来自进程启动配置。一次日常运行密钥轮换需要同步修改所有副本并重启，
既扩大变更窗口，也会产生副本间信任代际漂移。

本增量引入两层信任：

1. 低频、离线治理的 deployment bootstrap roots 与独立 witness bootstrap roots 仍由部署配置注入，
   是最终 trust anchor。
2. 两组 bootstrap quorum 共同签署一个原子的 deployment/witness runtime-key publication。Resource
   Gateway 严格刷新该 publication，再用同一代 runtime keys 验证 inventory、publication 和 witness。

因此闭合的是日常运行验签 key 的无重启轮换，不宣称 bootstrap roots 自身能无锚热轮换，也不宣称
本地数据库 floor 能对抗已完全控制数据库与备份系统的攻击者。

## 2. 版本化协议

| 对象 | 版本 | 约束 |
| --- | --- | --- |
| 原子双 key-set 信封 | `bloge.testSecretAuthorityServingInventoryTrustRootPublication.v1` | canonical material fingerprint、deployment-root signatures、witness-root signatures |
| 双 key-set material | `bloge.testSecretAuthorityServingInventoryTrustRootMaterial.v1` | stable root-set、sequence/predecessor、scope/protocol、四 trust domain、双 threshold、双 key set、policy、短时间窗 |
| 持久化代际 | `bloge.testSecretAuthorityServingInventoryTrustRootGeneration.v1` | scope、root-set、sequence、current/previous material fingerprint |
| 已验证聚合快照 | `bloge.testSecretAuthorityServingInventoryTrustRootSnapshot.v1` | 状态、代次、有效期、阈值和 active authority 数量 |
| 动态刷新快照 | `bloge.testSecretAuthorityServingInventoryDynamicTrustRootSnapshot.v1` | 刷新状态、计数、hard age、floor 与 aggregate readiness |

权威 wire Schema 是
[`test-secret-authority-serving-inventory-trust-root-publication-v1.schema.json`](schemas/resource-gateway-testing/test-secret-authority-serving-inventory-trust-root-publication-v1.schema.json)，
聚合快照定义同时收录在
[`test-secret-authority-v1.schema.json`](schemas/resource-gateway-testing/test-secret-authority-v1.schema.json)。
两份 Schema 与协议常量由独立 test-kit JAR 一起打包。

material 必须同时满足以下不变量：

1. deployment root、witness root、deployment runtime、witness runtime 四个 trust domain 两两独立。
2. deployment 与 witness runtime authority id、公钥材料不重叠。
3. 两组 key 均 canonical 排序且 identity 唯一，只接受 X.509 Ed25519 public key 与合法生命周期。
4. 两组 bootstrap roots 分别达到独立 M-of-N；任一侧缺签都不能发布任一半 runtime key-set。
5. sequence 从 1 开始逐一推进，后继精确绑定前代 material fingerprint。
6. scope、protocol、root-set、root domains、policy 和有效期与本地 expectation 精确一致。
7. publication 不允许 private key、secret、token、credential、业务 payload 或操作端点进入协议。

## 3. 刷新与提交协议

`DynamicTestSecretAuthorityServingInventoryTrustRootAuthority` 只接受 HTTPS；仅显式本机测试可放行
loopback HTTP。远端必须返回精确 vendor media type：

```text
application/vnd.bloge.test-secret-authority-serving-inventory-trust-roots.v1+json
```

以及精确协议头：

```text
X-BLOGE-Test-Secret-Serving-Inventory-Trust-Root-Protocol:
  bloge.testSecretAuthorityServingInventoryTrustRootPublication.v1
```

客户端禁止 redirect，限制正文为 512 KiB，使用有界 timeout、ETag/304、抖动后台刷新与全局
unknown-key refresh cooldown。一个修改响应按以下顺序提交：

1. 严格解析，拒绝 duplicate、unknown、trailing 和降级协议。
2. 重算 material fingerprint，验证 binding、policy、时间窗和双 bootstrap-root quorum。
3. 检查当前进程内 sequence/predecessor，拒绝 rollback、fork、gap 和 broken predecessor。
4. 数据库 floor 在 `(test-secret/<scope>, root-set)` 上原子接受完整 generation。
5. 只有 floor 成功后，才一次替换进程内不可变双 key-set snapshot。

相同 sequence 与 fingerprint 可幂等重放。floor 使用 whole-record fingerprint 检测持久化腐化，并与
suite-stability root stream 通过 namespace 隔离。远端错误、签名歧义、source hard age、floor 故障或
threshold 撤销都会立即关闭 test-secret resolution；合法后继可无重启恢复。

## 4. Inventory 联动与并发原子性

`DynamicTestSecretAuthorityServingInventoryAuthority` 的 managed 路径不再持有静态 runtime keys：

- inventory 引用未知 deployment/witness key 时，最多触发一次 cooldown 约束的同步 root refresh。
- key maps、trust domains、thresholds 与 root generation 来自同一个不可变已验证 key-set；禁止先读
  key、后读 generation 的 TOCTOU 混代。
- root generation 一旦变化，已验证 inventory 立即变为
  `TRUST_ROOT_GENERATION_UNVERIFIED`，HTTP authority 在网络调用前就 fail closed。
- inventory 返回新正文时，必须使用当前 root generation 完整重验 nested inventory、publication、
  witness 和 durable inventory floor 后才恢复。
- inventory 返回 `304` 时也不能复用旧验证结论；缓存正文必须用当前 root key-set 重验，成功后才把
  新 root generation 与旧 inventory head 原子绑定。
- root publication 撤销任一 runtime threshold 后，inventory 不再可用，即使旧 inventory 的签名和
  signed expiry 尚未过期。

managed 模式禁止同时配置 legacy deployment/witness runtime trust domain、threshold 或 key JSON，
从结构上消除“部分 key 来自动态根、另一部分来自静态配置”的不一致状态。

## 5. Spring、健康与跨系统能力

`test` 可选择 managed roots；`staging` 在 dynamic inventory 模式下要求 managed roots。启动需要：

- 一份 deployment bootstrap-root public-key set 与 M-of-N threshold。
- 一份独立 witness bootstrap-root public-key set 与 M-of-N threshold。
- root-set id、两个 root trust domain、accepted policy fingerprints 与 HTTPS publication URI。
- 一个可用的 test-runtime database；root floor 与 inventory publication/witness floor 分开持久化。

`TestSecretAuthorityServingInventoryTrustRootHealth` 只投影状态、sequence、刷新计数、时序、阈值、
active authority 数和 floor 类别；URI、ETag、root-set、policy/generation fingerprint、authority/key id、
公钥和签名均不输出。

Tool Studio capability 将“协议存在”和“当前 ready”分开：

- `testSecretAuthorityManagedServingInventoryTrustRoots`
- `testSecretAuthorityAtomicDualServingInventoryTrustRoots`
- `testSecretAuthorityDurableTrustRootFloor`
- `testSecretAuthorityExternallyAnchoredTrustRootFloor`
- `testSecretAuthorityManagedTrustRootsReady`

当前实现使用数据库 durable floor，因此前三项在 managed 模式为 true；尚未接入外部顺序锚，
`testSecretAuthorityExternallyAnchoredTrustRootFloor` 诚实保持 false。组合 ready 还要求 dynamic inventory
自身 `VERIFIED`、signed revocation、独立 witness、durable inventory floor 与 exact cohort 收敛。

## 6. 自动验证证据

核心与攻击面测试覆盖：

- 独立双 quorum、错误签名、binding/policy/time/key-domain overlap 和严格 JSON。
- HTTPS media/protocol/ETag/no-redirect、hard source age、refresh failure/recovery 与 scheduler close。
- unknown-key 触发轮换、rollback/fork/gap/broken predecessor、signed threshold revocation。
- root A + inventory A 到 root B + inventory B 的无重启轮换，以及 root revoke 即时关闭。
- root generation 变化后 inventory 立即关闭，并在 `304` 缓存正文重验后恢复。
- database floor 幂等、scope/root-set 隔离、并发后继线性化、腐化和 store outage。
- 真实 Spring 上下文装配 remote roots、两层 database floor、双 health 与 HTTP descriptor。
- capability truth table、严格 Schema、test-kit 常量和 JAR 内 Schema 资源。

聚焦门禁：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DynamicTestSecretAuthorityServingInventoryTrustRootAuthorityTest,\
DatabaseTestSecretAuthorityServingInventoryTrustRootFloorTest,\
TestRuntimeProfileIsolationTest,ToolStudioIntegrationServiceTest,\
TestSecretAuthorityProtocolSchemaTest,\
TestSecretAuthorityServingInventoryProtocolSchemaTest test
```

发布门禁：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

- Resource Gateway：3,171 tests，0 failures，0 errors，2 skipped，共 344 份 Surefire reports；
  真实浏览器回归与 Spring Boot 可执行 JAR 打包成功。
- Resource Gateway test-kit：230 tests，0 failures，0 errors，0 skipped，共 24 份
  Surefire reports；权威 Schema、普通/shaded JAR 与 public JavaDoc 打包成功。

## 7. 明确未完成

- bootstrap-root 自身的轮换 ceremony、KMS/HSM custody、双人审批和离线恢复 runbook。
- test-secret root/inventory stream 的外部 compare-and-append、Byzantine quorum、跨区域 witness gossip
  与 split-view 检测；当前本地 database floor 不构成 external non-equivocation。
- mTLS workload identity、证书 pinning 或签名 endpoint discovery。
- PostgreSQL 等非 H2 方言、数据库备份回退、跨区域 DR、根发布服务 HA 与网络分区 chaos 认证。
- 对外部告警、SLO burn-rate、轮换演练、失陷 key 应急响应和审计工单的产品化闭环。
- 中性的通用 signed-sequence/dual-root kernel；当前 persistence 复用成熟 stability substrate，但 wire、
  scope、Schema 与 capability 已完全隔离。

这些缺口不会被当前 capability 冒充为已完成。下一步应优先把 test-secret 两条可变顺序流接到独立
external-first non-equivocation anchor，并补齐 production-grade PKI/DR 认证。
