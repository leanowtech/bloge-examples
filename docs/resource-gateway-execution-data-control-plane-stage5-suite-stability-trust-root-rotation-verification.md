# Stage 5 Serving Inventory 运行密钥轮换内核验证

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
4. 向 `TestSuiteStabilityServingInventoryTrustRootFloor` 提交数据库权威代际。
5. 只有 floor 成功后才原子替换进程内不可变双 key-set snapshot。

数据库 floor 通过 `(scope_id, trust_root_set_id)` 独立锁行线性化并发后继，使用数据库时钟和
whole-record fingerprint。rollback、same-sequence fork、gap、broken predecessor、记录腐化和 store
outage 全部拒绝。相同代际和相同材料可幂等重放。

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

## 5. 自动验证

聚焦测试覆盖以下风险：

- 双 quorum、错误 root signature、trust-domain/key overlap、policy/binding drift。
- canonical key order、严格 JSON、合法阈值轮换与签名阈值撤销。
- HTTPS media/protocol/ETag/no-redirect、source outage、hard maximum age、scheduler close。
- unknown-key 同步刷新 cooldown、fork/gap/broken predecessor 和进程重建 rollback。
- 数据库 floor 幂等、作用域隔离、并发后继线性化、腐化与 store outage。
- 真实 Ed25519 端到端链：A 运行密钥库存 -> 双根发布 B -> B 运行密钥库存，无进程重启完成；随后
  witness runtime threshold 撤销会立即关闭库存 admission。

本提交的相关门禁执行 31 tests，0 failures、0 errors、0 skips；完整 Resource Gateway
`clean verify` 执行 2731 tests，0 failures、0 errors、2 个条件浏览器跳过，并成功重打包 Spring Boot
可执行 JAR。

## 6. 明确未完成

本增量只交付协议和内核，不把尚未完成的部署面包装成产品能力。后续仍需：

- Spring composition root、配置属性、staging fail-fast preflight 与启停脚本接线。
- 独立 trust-root health/capability、cohort descriptor 和 machine-readable JSON Schema。
- bootstrap root 轮换 ceremony、KMS/HSM/mTLS、根签名发布服务 HA。
- 数据库整库备份回退的外部不可回退锚、transparency/WORM/gossip 与跨域 split-view 检测。
- PostgreSQL 等非 H2 方言、backup/restore、灾备切换和大规模并发认证。
