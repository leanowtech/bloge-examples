# Stage 5 Serving Inventory 运行密钥轮换与生产接线验证

## 1. 本增量解决什么

动态 serving inventory 已能在运行期刷新和撤销，但其 deployment publication key 与 witness key
仍由 Resource Gateway 启动配置固定。仅轮换业务运行密钥也需要重新发布并重启所有副本，既扩大变更
窗口，也容易让不同副本停留在不同信任代际。

本增量建立一条更窄、可证明的信任链：部署 bootstrap-root quorum 与独立 witness bootstrap-root
quorum 共同签署一个原子的双运行密钥集。Resource Gateway 可以在不重启进程的前提下刷新该密钥集，
再用同一代 deployment/witness keys 验证 serving inventory、publication 和 witness checkpoint。

它没有把根信任变成循环自证。两组 bootstrap public keys 仍是部署时注入的最终 trust anchor；本增量
轮换的是日常运行签名密钥，不宣称 bootstrap root 自身可以无锚热轮换。

## 2. 版本化协议

| 对象 | 版本 | 关键约束 |
| --- | --- | --- |
| 双根发布信封 | `bloge.testSuiteStabilityServingInventoryTrustRootPublication.v1` | material fingerprint、deployment-root signatures、witness-root signatures |
| 原子双密钥材料 | `bloge.testSuiteStabilityServingInventoryTrustRootMaterial.v1` | stable root-set id、sequence、predecessor、scope/protocol、四个 trust domain、双阈值、双 key set、policy、短时间窗 |
| 持久化单调代际 | `bloge.testSuiteStabilityServingInventoryTrustRootGeneration.v1` | scope、root-set id、sequence、current/previous material fingerprint |

两组运行密钥在同一个 canonical material 中发布，禁止把 deployment keys 和 witness keys 拆成两个
无法证明同时性的 JWKS。材料必须满足：

1. deployment root、witness root、deployment runtime、witness runtime 四个 trust domain 两两独立。
2. deployment 与 witness 的 authority id 和公钥材料不重叠。
3. 每组 key 按 authority/key 稳定排序；重复、非 Ed25519 X.509 key、非法生命周期均拒绝。
4. 两组 bootstrap root 分别达到 M-of-N，任一侧缺签都不能发布运行密钥。
5. sequence 只能从 1 建立，并逐一绑定前代 material fingerprint。
6. policy、scope、protocol、root-set id 与本地 expected binding 精确相等。

## 3. 刷新和提交顺序

`DynamicTestSuiteStabilityServingInventoryTrustRootAuthority` 只接受 HTTPS（测试可显式放行 loopback
HTTP）、精确 vendor media type 和 protocol header。它禁止 redirect，限制文档为 512 KiB，使用有界
timeout 与 ETag/304，并对 duplicate、unknown、trailing JSON fail closed。

修改响应按以下顺序处理：

1. 严格解析并验证 canonical material fingerprint。
2. 验证本地 binding、有效期、生命周期和双 bootstrap-root quorum。
3. 验证进程内 sequence/predecessor successor。
4. staging 先向外部 compare-and-append quorum 提交 exact root head。
5. 外部成功后向数据库 `TestSuiteStabilityServingInventoryTrustRootFloor` 提交权威代际。
6. 只有两层 floor 都成功后才原子替换进程内不可变双 key-set snapshot。

数据库 floor 通过 `(scope_id, trust_root_set_id)` 独立锁行线性化并发后继，使用数据库时钟和
whole-record fingerprint。rollback、same-sequence fork、gap、broken predecessor、记录腐化和 store
outage 全部拒绝。相同代际和相同材料可幂等重放。external-first 顺序使“外部成功、数据库失败”可安全
重试，并结构性禁止“数据库成功、外部失败”的未锚定代际。

## 4. 与 serving inventory 的联动

`DynamicTestSuiteStabilityServingInventoryAuthority` 新增 managed trust-root 路径：

- inventory/publication 引用未知运行 key 时，只允许在 cooldown 内触发一次同步根刷新。
- 根刷新成功后，deployment publication、嵌套 inventory 和独立 witness 都用同一原子根代际验证。
- inventory 本地状态记录实际完成验签的私有根 generation；根后台先行轮换时，两条通道未收敛即
  返回 `TRUST_ROOT_GENERATION_UNVERIFIED`，不能继续使用旧验签结果。
- inventory endpoint 返回 `304` 但根 generation 已改变时，仍必须用新根重新验证当前文档。
- 根源刷新失败、过期、关闭或签名阈值被撤销时，已验证的旧 inventory 也立即不可用。
- cohort 私有 source generation 同时绑定 publication、witness 与 root material fingerprints，避免不同
  副本各自健康却处于不同运行密钥代际。

公开 descriptor 只增加 `managedTrustRootRefresh` 和 `atomicDualTrustRootPublication` 两个布尔事实，不
泄露 endpoint、ETag、authority id、key id、公钥或材料 fingerprint。

## 5. 生产接线与部署不变量

第二阶段把内核接入 `TestRuntimeConfiguration`、`application-test.yml`、
`application-staging.yml` 和演示启停脚本：

1. staging 的 dynamic serving inventory 必须启用 managed trust roots；test profile 仍可显式使用
   static fallback。
2. managed mode 必须恰有一个动态 root authority 和一个数据库 floor，且必须同时启用 remote
   inventory；缺失、多实例或 partial mode 均启动失败。
3. legacy deployment/witness runtime trust domain、threshold 和 key JSON 不得与 managed mode 混用。
   composition root 在网络 bootstrap 前检查该约束，避免错误配置先产生外部副作用。
4. root publication 采用严格 Draft 2020-12 JSON Schema；重复、未知和 trailing 属性仍由应用层严格
   parser 拒绝，跨字段 domain/key 独立性、阈值和时间顺序由语义验证器拒绝。
5. cohort descriptor 升至 v4，并原子声明 `managedInventoryTrustRoots` 与
   `atomicDualInventoryTrustRootPublication`；任何副本缺少原子能力都不能收敛。
6. capability 分别公开 `restartFreeSuiteStabilityServingInventoryKeyRotation` 与
   `atomicDualQuorumSuiteStabilityServingInventoryTrustRoots`。Actuator root health 只输出状态、时序、
   计数、阈值和 key 数量，不输出 URI、ETag、root-set id、fingerprint、authority/key id 或密钥材料。
7. staging preflight 在启动 JVM 前检查 HTTPS、标识符、policy fingerprints、独立根域、阈值、JSON
   array 形状、刷新/timeout/hard-age 关系和 legacy mixed mode。
8. staging 同时强制 external anchor `f>=1`，publication 与 trust-root 两条可变顺序流必须都先经过
   `3f+1 / 2f+1` challenge-bound signed notary quorum；完整设计与证明见
   [外部非等价锚验证](resource-gateway-execution-data-control-plane-stage5-suite-stability-external-non-equivocation-verification.md)。

## 6. 自动验证

聚焦测试覆盖以下风险：

- 双 quorum、错误 root signature、trust-domain/key overlap、policy/binding drift。
- canonical key order、严格 JSON、合法阈值轮换与签名阈值撤销。
- HTTPS media/protocol/ETag/no-redirect、source outage、hard maximum age、scheduler close。
- unknown-key 同步刷新 cooldown、fork/gap/broken predecessor 和进程重建 rollback。
- 数据库 floor 幂等、作用域隔离、并发后继线性化、腐化与 store outage。
- 真实 Ed25519 端到端链：A 运行密钥库存 -> 双根发布 B -> B 运行密钥库存，无进程重启完成；随后
  witness runtime threshold 撤销会立即关闭库存 admission。
- Spring managed/static mode 隔离、required/disabled 矛盾、网络 bootstrap 前 mixed-mode 拒绝、
  profile bean isolation、aggregate-only health、cohort v3 和 capability truth table。
- Java wire properties 与 strict trust-root publication Schema 一致，整秒时间、sequence-1 predecessor
  和 private/operational material 禁止项均有反例。

第一阶段内核提交的相关门禁执行 31 tests，0 failures、0 errors、0 skips；完整 Resource Gateway
`clean verify` 执行 2731 tests，0 failures、0 errors、2 个条件浏览器跳过，并成功重打包 Spring Boot
可执行 JAR。

第二阶段生产接线相关聚焦门禁执行 73 tests，0 failures、0 errors、0 skips；完整 Resource Gateway
`clean verify` 执行 2736 tests，0 failures、0 errors、2 个条件浏览器跳过，并成功重打包 Spring Boot
可执行 JAR。`zsh -n scripts/visual-canvas-demo.sh` 与 `git diff --check` 同时通过。

## 7. 外部依赖与明确未完成

Resource Gateway 侧的数据库备份回退检测客户端、wire contract、法定人数、双 floor external-first
接线、staging 门禁和 aggregate-only capability/health 已由后续
[外部非等价锚增量](resource-gateway-execution-data-control-plane-stage5-suite-stability-external-non-equivocation-verification.md)
闭合。但本仓库不把外部基础设施包装成已完成能力，生产投用仍需：

- bootstrap root 轮换 ceremony、KMS/HSM/mTLS、根签名发布服务 HA。
- 独立 notary 服务部署、WORM/tamper evidence、backup/restore、容量与跨地域 DR 认证。
- 若监管要求公开可验证历史，再增加 transparency inclusion/consistency proof、gossip 与跨域
  split-view 检测；当前 federated quorum 不等价于公开 transparency log。
- PostgreSQL 等非 H2 方言、backup/restore、灾备切换和大规模并发认证。
