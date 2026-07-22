# Resource Gateway Stage 4 Physical Attempt Provider Inventory And Capability Verification

## 1. 增量目标与病根

前十七个 physical-attempt 增量已经形成 durable start、observation reconciliation、terminal projection 和
自主 worker，但 provider/deployment 到 runtime adapter 的绑定仍由部署代码中的任意 resolver 隐式决定，
Tool Studio 也没有一个能够证明整条链路真实可用的 capability。病根不是少一个健康检查，而是缺少三个
可验证事实：当前允许哪些 provider deployment、每个 deployment 精确对应哪个 runtime artifact 与
observation key、所有副本是否在同一 inventory generation 上运行。

如果继续允许函数式或 `Map` resolver，单副本可以遗漏 deployment、混入额外 adapter，或在 inventory
过期后继续使用旧对象；如果 capability 只读取局部 health，系统还会在静态配置、撤销不可见或 cohort
分裂时误报 ready。本增量因此先冻结协议和失败关闭边界，不把静态签名基线包装成动态生产能力。

## 2. 签名 Inventory 协议

`TestSuiteStabilityPhysicalAttemptProviderInventory` 定义 canonical、完整、M-of-N Ed25519 签名的 inventory。
每个 binding 同时冻结：

- provider id、deployment id 与 isolation modes；
- runtime adapter id、runtime artifact fingerprint 与 observation key fingerprint；
- descriptor/observation latency budget、retention 和 policy fingerprint；
- trust domain、scope、cohort、protocol version、generation、签发时间与硬过期时间。

canonical material 对 binding 排序，拒绝重复或无界集合。签名覆盖完整 material，而不是只覆盖
provider id。`ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority` 在开放解析前验证
scope/policy、可信 key、签名阈值、时效和 runtime adapter 精确全覆盖，因而以下情况统一失败关闭：

| 破坏方式 | 结果 |
| --- | --- |
| 缺少或额外 runtime adapter | authority 构造失败 |
| inventory 包含未知 deployment | authority 构造失败 |
| command 跨 provider/deployment binding | provider I/O 前拒绝 |
| descriptor 与签名 binding 任一字段漂移 | provider I/O 前拒绝 |
| inventory hard expiry | 新解析和已解析 wrapper 均关闭 |
| observe 前 inventory generation 改变 | provider I/O 前拒绝 |

descriptor/capability 读取不会调用 provider。已解析 authority 在 `descriptor()` 与 `observe()` 前重新读取
inventory observation，避免把一次构造期校验错误地当成永久租约。

## 3. Cohort 与运行时装配

`TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate` 冻结 exact expected replica set、逐副本
generation/fingerprint observation 和整体收敛判定。它是协议 seam，不虚构数据库实现；当前实现提交没有
把 replica-local set 当作生产 cohort authority。

`TestSuiteStabilityPhysicalAttemptObservationReconciliationRuntimeConfiguration` 不再接受任意
`AuthorityResolver` 或 map-based resolver，只接受唯一
`TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority`。因此 runtime 解析、descriptor 校验和 observation
调用共享同一签名 generation fence，缺 authority 或存在歧义时 Spring composition 启动失败。

## 4. Fail-Closed Capability

`TestSuiteStabilityPhysicalAttemptRuntimeCapability` 在两次 inventory observation 之间读取 cohort、observation
reconciliation health 和 terminal projection health。前后 observation 不一致时不会拼接出一个不存在的
混合快照。状态是闭集协议：

| 状态族 | 含义 |
| --- | --- |
| `DISABLED`、`INCOMPLETE`、`AMBIGUOUS` | composition 未启用、依赖缺失或候选不唯一 |
| `INVENTORY_UNAVAILABLE`、`INVENTORY_INCONSISTENT` | inventory 无法读取或前后 observation 不一致 |
| `DYNAMIC_INVENTORY_REQUIRED` | 只有静态签名 inventory，不能证明自动刷新 |
| `AUTOMATIC_REFRESH_REQUIRED`、`SIGNED_REVOCATION_REQUIRED` | 动态 authority 缺刷新或签名撤销能力 |
| `WITNESS_REQUIRED`、`DURABLE_FLOOR_REQUIRED` | 缺独立 witness 或防回滚 durable floor |
| `COHORT_UNAVAILABLE`、`COHORT_NOT_CONVERGED` | exact replica cohort 不可证或未收敛 |
| `RUNTIME_UNAVAILABLE` | reconciliation 或 terminal projection health 不 ready |
| `READY` | 上述动态信任、cohort 与两条 runtime health 全部成立 |
| `UNAVAILABLE` | 读取或协议校验出现不可安全分类的失败 |

静态 configured authority 诚实返回 `DYNAMIC_INVENTORY_REQUIRED`。代码中存在 `READY` 判定协议，不代表
本增量已经提供可进入该状态的动态 HTTPS adapter。

Tool Studio `IntegrationCapabilities.Testability` 新增 typed `physicalAttemptRuntime`、12 个布尔事实和相应
object versions。`ToolStudioIntegrationService` 先冻结候选列表，再拒绝缺失或歧义 composition；capability
读取只投影本地受验证事实，不触发 provider resolution 或业务 I/O。旧构造器保留，旧调用方会得到明确的
非 ready 默认值，不会因升级而获得虚假的能力声明。

## 5. Schema 与制品

新增四份 strict JSON Schema：

- `physical-attempt-provider-inventory-v1.schema.json`；
- `physical-attempt-provider-inventory-descriptor-v1.schema.json`；
- `physical-attempt-provider-inventory-cohort-observation-v1.schema.json`；
- `physical-attempt-runtime-capability-v1.schema.json`。

schema 使用 closed object、bounded collection、identifier/fingerprint grammar 和显式 version const。
test-kit packaging test 验证资源路径，普通 JAR 与 CLI shaded JAR 均实际包含四份文件。

## 6. 已执行验证

实现提交 `05903ddc` 包含 21 个文件、2672 行新增和 30 行删除。新增 29 项测试，覆盖 canonical/signature、
阈值、scope/policy/freshness、adapter 精确集合、generation fence、descriptor drift、hard expiry、cohort、
capability 状态、Tool Studio 投影和 test-kit 资源打包。相邻 physical observation/terminal/queue 聚合门禁为：

```text
Tests run: 281, Failures: 0, Errors: 0, Skipped: 0
```

五个新增公共类型执行 `javadoc --release 25 -Werror -Xdoclint:all`，结果为 0 warnings、0 errors。四份
JSON Schema 通过语法校验，`git diff --check` 通过。

从实现提交创建 `/tmp/bloge-examples-verify-05903ddc` immutable snapshot，并从 clean 状态执行：

```text
mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 4218, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
Total time: 07:48 min
```

470 份 Surefire XML 独立汇总为 `tests=4218 failures=0 errors=0 skipped=2`。34 项 Browser DOM 测试中
32 项真实执行、2 项按环境条件跳过。39,846,655 bytes Spring Boot 可执行 JAR 包含 12 个 provider
inventory 匹配 class entry 和 2 个 runtime capability 匹配 class entry。

同一 snapshot 执行 test-kit 完整门禁：

```text
mvn -f resource-gateway-test-kit/pom.xml clean verify

Tests run: 231, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 10.881 s
```

25 份 Surefire XML 独立汇总一致。759,412 bytes 普通 JAR 与 3,799,438 bytes CLI shaded JAR 均包含四份
新增 schema，完整 test-kit JavaDoc verify 同时通过。两次构建结束后检查 snapshot 路径关联的 Maven、Java、
Chrome 与 ChromeDriver 进程，残留为零。

## 7. 质量评估与剩余病根

本增量局部质量评估为 **93/100**。签名完整性、adapter 精确集合、代际 fencing、descriptor 一致性、
capability truth、向后兼容和制品可消费性已经闭合。扣分不是因为静态实现“还差一个开关”，而是生产动态
信任链尚不存在：当前没有动态 HTTPS ACTIVE/REVOKED publication、独立 witness、durable anti-rollback
floor 和数据库 exact cohort，故静态 authority 必须保持 non-ready。

相对两份工业级可测试性计划的完整目标，当前估计仍有 **约 21% 的实质差距**，未进入允许完成的正负
8% 区间。剩余问题按病根排序为：

1. 实现动态 HTTPS 签名 inventory publication、ACTIVE/REVOKED lifecycle、自动刷新、独立 witness 与
   durable generation floor，形成可证明的防回滚和撤销传播链。
2. 用数据库 authoritative expected set 与逐副本 lease/heartbeat 建立 exact cohort，验证滚动升级、分区、
   scale-in/out、迟到副本和 generation split 的收敛语义。
3. 完成 N/N-1 source backfill、不可变 attempt history、跨 provider family fact ledger 及升级/回滚门禁。
4. 完成 start/cancellation/observation/projection retention、tombstone、legal hold、WORM、external anchor 与
   evidence bundle 生命周期。
5. 接入真实 process/container provider，验证 secret/HSM/KMS custody、网络与进程隔离、hard cancellation、
   生产数据库 dialect、HA/partition/chaos、容量和 DR。

下一增量应优先把第 1、2 项作为一个信任闭环实现，而不是先写一个总是 `READY` 的测试 adapter；只有动态
authority 与 authoritative cohort 同时可证，当前 capability 协议才有资格在真实 staging 中进入 `READY`。
