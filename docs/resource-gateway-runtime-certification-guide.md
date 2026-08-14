# Resource Gateway 运行时认证接入与运维指南

> 适用协议：`resourceGateway.runtimeCertificationManifest.v1`、
> `resourceGateway.runtimeCertificationExecutionAuthorization.v1`、
> `resourceGateway.runtimeCertificationReport.v1`、
> `resourceGateway.runtimeCertificationReplayBundle.v1`
>
> 对应工作包：RG-BM-013
>
> 安全边界：故障注入只能在 `SANDBOX` 或隔离的 `PRE_PRODUCTION` 环境执行。
> `PRODUCTION` 在协议、授权和 Harness 三层被拒绝。仓库没有默认环境 Adapter，也没有“一键注入故障”端点。

## 1. 这项能力解决什么问题

普通集成测试能证明某条成功路径在某一时刻通过，却不能回答以下生产化问题：

1. PostgreSQL 主节点在事务不同阶段失效时，是否出现部分可见、重复副作用或已提交状态丢失？
2. 网络分区和租约接管时，是否同时出现两个有效 Owner，旧 epoch 是否被真正隔离？
3. 滚动升级、备份恢复、KMS 和 mTLS CA 轮转后，状态与信任代际是否连续？
4. Vault 失联时系统是否失败关闭，是否偷偷回退到缓存 Secret？
5. 隔离环境是否真的拦住了外部业务写入？
6. 一次“测试通过”能否被 CI、ANEKE 或审计方离线复验，而不依赖运行者口头说明？

Runtime Certification Harness 把这些问题固化为不可缩减的 12 场景分母，并把一次执行变成可寻址、
可签名、可恢复、可离线复验的证据链。它不是另一套基础设施控制面，也不替客户操作 Kubernetes、
PostgreSQL、KMS、Vault 或 Service Mesh；实际动作由客户拥有的 Adapter 完成。

## 2. 责任边界

| 责任方 | 拥有的事实与动作 | 明确不拥有 |
|---|---|---|
| 客户 Security / SRE / DBA | 认证环境、故障窗口、审批、Adapter、基础设施动作、恢复观测、私有信任根 | Resource Gateway 协议语义和 ANEKE 发布裁决 |
| Resource Gateway | Manifest、计划、授权验真、执行编排、逐场景日志、Report、Replay Bundle、能力探针 | 客户云账号、生产凭据、故障工具和业务 Payload |
| 区域数据平面 Authority | KMS、Vault、Secret、State、Resolver、mTLS、egress 的当前认证闭包 | HA/恢复场景的执行结论 |
| 独立授权 Authority | 对一次完整场景分母、环境和有效窗签发单次授权 | 兼任 Report signer 或自行放宽场景 |
| 独立 Report signer | 对完整运行结果签名 | 伪造未观测场景或替代客户基础设施证据 |
| Resource Gateway Test Kit | 离线 Schema、内容地址、签名材料、场景分母、SLO 和交叉引用复验 | 替调用方选择可信公钥或把 fixture 当客户认证 |
| ANEKE | Registry、Workbook、治理投影、发布门禁和证据保留 | 重新执行故障或接管运行环境 |

核心约束是职责分离：授权签名方与报告签名方必须独立；Adapter 还必须在动作边界再次验证授权并持久抑制重放。

## 3. 固定认证分母

Manifest 必须且只能包含以下 12 个场景，每个场景恰好一次。协议内建的 invariant 不能被部署方删除，
部署方只能增加更严格的 invariant，并为每个场景设置不超过 7200 秒的执行期限和不大于执行期限的恢复 SLO。

| 场景 | 必须证明的核心 invariant |
|---|---|
| `POSTGRES_PRIMARY_KILL_BEFORE_STAGE` | 无部分可见、精确重放、无已提交状态丢失 |
| `POSTGRES_PRIMARY_KILL_AFTER_STAGE` | 无部分可见、精确重放、无已提交状态丢失 |
| `POSTGRES_PRIMARY_KILL_AFTER_APPLY` | 无部分可见、精确重放、无已提交状态丢失 |
| `POSTGRES_PRIMARY_KILL_AFTER_COMMIT` | 已提交状态可见、精确重放、无重复效果 |
| `NETWORK_PARTITION` | 单一有效 Owner、旧 epoch 隔离、最终恢复 |
| `LEASE_TAKEOVER` | 单一有效 Owner、旧 epoch 隔离、最终恢复 |
| `ROLLING_UPGRADE` | 混合版本兼容、无状态丢失、最终恢复 |
| `BACKUP_RESTORE` | 恢复连续、无状态丢失、fence 单调 |
| `KMS_KEY_ROTATION` | 签名连续、旧代际拒绝、无重启 |
| `MTLS_CA_ROTATION` | 信任连续、旧代际拒绝、无重启 |
| `VAULT_UNAVAILABLE` | 失败关闭、无 Secret 回退、最终恢复 |
| `WRITE_ESCAPE_PROBE` | 外部业务写入为零、失败关闭 |

报告没有综合分数。只有全部场景 `PASSED`、全部 invariant `PASSED`、恢复 SLO 满足且 write escape 为零时，
结论才是 `CERTIFIED`。任一真实断言失败得到 `FAILED`；未执行、Adapter 异常、超时或前序失败导致的中止得到
`BLOCKED`。后续场景仍以 `ABORTED` 进入固定分母，不能通过缩小分母制造高通过率。

## 4. 四个协议对象

### 4.1 Manifest

`RuntimeCertificationManifest` 冻结完整企业 Scope、region、deployment identity、环境指纹、Resource Gateway、
BLOGE Engine、数据库和 JVM 的精确 build fingerprint，以及固定的 12 场景与 SLO。Manifest 最长有效 31 天，
执行时环境必须与其精确一致。

### 4.2 Execution Authorization

`RuntimeCertificationExecutionAuthorization` 是最长 30 分钟、单次使用的外部签名批准。它绑定 exact Manifest、
Scope、环境类别、环境指纹、deployment、完整场景集合、nonce 和审批证据引用，并固定：

- `destructiveActionsAllowed=true`；
- `productionExecutionDenied=true`；
- `singleUse=true`；
- 环境只能是 `SANDBOX` 或 `PRE_PRODUCTION`。

授权不是通用变更单。改变任何环境、版本、场景或 Manifest 都必须重新审批和签发。

### 4.3 Report

`RuntimeCertificationReport` 保存完整 12 场景的状态、时间线、invariant 观察、恢复时刻、命令/观察 fingerprint、
proof refs、write attempt/escape 计数和独立签名。报告只允许内容地址和有界状态，不携带请求、响应、Secret、
凭据、日志正文或业务 Payload。

每个 `PASSED` 场景必须同时给出 `faultAppliedAt`、`faultRemovedAt` 和 `recoveryObservedAt`。Harness 与 Test Kit
都计算 `faultRemovedAt → recoveryObservedAt`，不能用 Adapter 自报的布尔值替代恢复 SLO。

### 4.4 Replay Bundle

`RuntimeCertificationReplayBundle` 是自包含、payload-free 的导出包，内含：

1. Manifest、Authorization 和 Report；
2. BM-012 Regional Deployment Contract 与短期 Certification；
3. 绑定该区域认证的 v2 Isolation Decision。

Bundle 本身只有内容地址，不新增权威。消费者仍必须分别重算每个组成对象、验证独立签名，并核对 Report 中的
regional certification、isolation decision 和 attestation 引用可从内嵌对象精确推导。这样 CI、ANEKE 和审计方
无需依赖运行服务仍能重放验证整条信任闭包。

## 5. 执行模型

```text
冻结 Manifest
  → plan() 只检查，不调用 Adapter
  → 外部审批系统签发单次 Authorization
  → Harness 验证授权、Adapter 描述和当前 Regional Certification
  → 数据库原子消费 authorization fingerprint + nonce
  → 按固定顺序执行场景；每个终态先写 Journal，再进入下一场景
  → 每个 Adapter 调用前后 heartbeat，旧 epoch 失去写权限
  → 完整窗口再次验证 Regional Certification
  → 独立 signer 签发完整 Report
  → Journal 原子落终态；精确重试返回同一 Report
  → 导出 Replay Bundle
  → Test Kit 离线复验后进入 ANEKE / CI / WORM 保留
```

`plan()` 永不执行 Adapter。执行期间若任何场景不通过，Harness 停止继续注入故障，但仍为剩余场景生成
`ABORTED` 结果。进程崩溃后，新副本只能在数据库租约过期后以更高 epoch 从已持久化前缀继续；旧副本不能追加。

## 6. 客户 Environment Adapter

客户需要实现 `RuntimeCertificationEnvironmentAdapter`，仓库不提供默认实现。Adapter 的
`descriptor()` 必须准确声明 provider、版本、environment/deployment fingerprint、支持的完整场景和安全控制；
`execute(ScenarioExecution)` 负责映射真实基础设施动作与观测。

Adapter 必须满足：

1. 在自身信任域独立验证 Authorization 的签名、Manifest、Scope、deployment、环境、场景、nonce 和有效窗。
2. 在自身持久存储中以 authorization fingerprint、nonce、runId、scenario、journal epoch 建立唯一消费记录。
3. 相同坐标只返回同一已保存结果；任何 fork、旧 epoch 或 nonce 复用都失败关闭。
4. 动作前证明环境没有生产流量、生产身份、生产 Secret 或生产业务出口；环境漂移立即阻断。
5. 每个动作都有硬 deadline、kill switch、自动清理和有界恢复路径；Harness 超时不能被理解为 Adapter 已停止动作。
6. Report 只返回 fingerprint、状态、时间和计数。原始日志、数据库行、证书和 Payload 留在客户证据库。
7. `WRITE_ESCAPE_PROBE` 只能向专用 deny sink 发出探测，禁止把真实业务端点当测试目标。
8. Adapter 与 Harness 的权限独立；Resource Gateway 进程不直接持有云平台管理员凭据。

伪代码仅说明装配边界：

```java
RuntimeCertificationHarness harness = new RuntimeCertificationHarness(
        integrity,
        independentAuthorizationVerifier,
        independentReportSigner,
        regionalDataPlaneAuthority,
        databaseJournal,
        Clock.systemUTC(),
        stableReplicaId);

RuntimeCertificationHarness.Plan plan = harness.plan(manifest, customerAdapter);
// plan.status() == READY 之后仍需外部审批；plan 不授予执行权。
RuntimeCertificationReport report = harness.execute(commandWithSignedAuthorization);
```

## 7. Durable Journal 与数据库迁移

生产化 staging 必须使用 `DatabaseRuntimeCertificationExecutionJournal`。它使用数据库时钟、事务行锁、唯一
authorization fingerprint、唯一 nonce、状态内容地址和 epoch-fenced lease；不存在进程内 fallback。

外部数据库变更流程应先审批并应用：

```text
resource-gateway-examples/src/main/resources/db/postgresql/
V20260815_003__runtime_certification_journal.sql
```

迁移新增 authorization lock 表、run journal 表和 `(status, lease_expires_at, run_id)` 索引。升级顺序：

1. 备份并验证恢复点；应用 additive DDL；检查约束和索引。
2. 先部署只开放 plan surface 的版本，确认能力探针和数据库读写。
3. 装配 Journal、两个独立 signer/verifier 和客户 Adapter，但保持执行入口受审批系统隔离。
4. 在 disposable sandbox 运行一次完整认证并离线验证 Replay Bundle。
5. 通过变更审批后再启用隔离 pre-production 周期任务。

禁止删除正在运行的 journal 行。保留或清理必须按 Report/Bundle 的法务保留状态执行；authorization nonce
的防重放记录不能早于相应证据保留期删除。

## 8. 能力探针

```bash
curl -s http://localhost:8080/api/integration/capabilities | jq '.payload | {
  objects: {
    manifest: .supportedObjects.runtimeCertificationManifest,
    authorization: .supportedObjects.runtimeCertificationExecutionAuthorization,
    report: .supportedObjects.runtimeCertificationReport,
    replayBundle: .supportedObjects.runtimeCertificationReplayBundle
  },
  runtime: {
    protocol: .features.mirrorRuntimeCertificationProtocol,
    plan: .features.mirrorRuntimeCertificationPlanReady,
    journal: .features.mirrorRuntimeCertificationDurableJournalReady,
    execute: .features.mirrorRuntimeCertificationExecutionReady,
    regional: .features.mirrorRegionalDataPlaneCertificationReady
  }
}'
```

解释规则：

- `protocol=true` 只表示软件理解四种对象。
- `plan=true` 表示明确装配 plan surface 且 Adapter 当前可探测。
- `journal=true` 表示 destructive execution surface 已装配且 durable journal 当前可用。
- `execute=true` 还要求 Adapter、Journal、授权 Authority、Report signer 和 Regional Data Plane 同时 ready。
- 探针异常一律投影为 `false`，不输出 provider message、凭据或 Payload。

默认演示服务只广告协议，三个运行 readiness 保持 `false`。这是刻意的安全行为，不是功能故障。

## 9. 验证与启动/停止

先运行不会触发故障的协议与离线消费者门禁：

```bash
./scripts/verify-runtime-certification.sh protocol
./scripts/verify-runtime-certification.sh postgres  # 需要仓库的 embedded PostgreSQL 运行条件
./scripts/verify-runtime-certification.sh all
```

脚本只运行自动化测试，不安装 Adapter，不调用故障 API。普通产品演示仍使用：

```bash
./scripts/start-visual-canvas-demo.sh --open
./scripts/stop-visual-canvas-demo.sh
```

该演示脚本不会伪造客户 KMS、Vault、PKI、HA 集群或授权 Authority，因此不能体验真实故障注入。客户认证环境
应由部署仓库提供单独的 Spring composition、Adapter 和基础设施流水线，不应把危险能力加入通用演示 profile。

## 10. Test Kit 离线验证

`resource-gateway-test-kit` 的 `RuntimeCertificationVerifier` 不依赖 Spring 或服务端类。推荐直接验证自包含包：

```java
RuntimeCertificationVerifier.VerifiedReplayBundleCoordinates verified =
        new RuntimeCertificationVerifier().requireReplayBundle(
                replayBundleJson,
                (seal, regionalCertification) -> regionalTrust.verify(seal, regionalCertification),
                (seal, authorization) -> approvalTrust.verify(seal, authorization),
                (seal, report) -> evidenceTrust.verify(seal, report));
```

三个 callback 必须使用调用方固定的信任根、key generation、revocation 和 policy，禁止实现为恒真。验证器会：

- 按 strict Schema 拒绝未知字段和 Payload；
- 重算四个运行认证对象与三类区域/隔离对象的内容地址；
- 重建 Authorization 与 Report 的 domain-separated signing material；
- 核对固定场景分母、状态、时间线、恢复 SLO、zero-write 和完整交叉引用；
- 验证 Regional Certification 覆盖整个 Report 时间窗；
- 只返回稳定坐标，不返回业务数据。

权威 Schema 与 server-produced fixture 位于 `docs/schemas/resource-gateway-mirror/`。fixture 用于跨实现兼容，
不是客户环境认证证据。

## 11. CI、夜间与客户环境流程

| 阶段 | 频率 | 环境 | 退出条件 |
|---|---|---|---|
| 协议门禁 | 每次提交 | 无故障、本地 JVM/H2 | Schema、内容地址、Harness、Journal、Test Kit 全绿 |
| PostgreSQL 门禁 | 每次发布候选 | disposable PostgreSQL | 双连接只消费一次授权、重启恢复、exact replay、stale epoch 拒绝 |
| 夜间认证 | 每晚或每周 | 隔离 sandbox | 12/12 场景完成、Replay Bundle 离线复验、证据归档 |
| 升级认证 | 每次 RG/BLOGE/JVM/DB 版本变化 | production-shaped pre-production | exact build coordinates、rolling upgrade、backup/restore 与恢复 SLO 全绿 |
| 客户现场认证 | 上线前及基础设施重大变更后 | 客户批准的隔离环境 | 客户 Authority、Adapter、区域闭包、完整观察窗和审批记录齐全 |

任何 build fingerprint、环境 fingerprint、deployment、区域 Contract/Certification 或场景策略改变，旧 Report 都
只能作为历史证据，不能继续代表新环境。失败或阻断结果必须保留完整分母，生成事故任务；不得只重跑失败场景后
拼接成新的 `CERTIFIED` 报告。

## 12. 事故与恢复

| 现象 | 判定 | 处理 |
|---|---|---|
| `JOURNAL_UNAVAILABLE` / `LEASE_LOST` | 执行所有权不可信 | 停止新动作；确认 Adapter kill switch；数据库恢复后以更高 epoch 继续 |
| Adapter 超时或异常 | `BLOCKED` | Adapter 独立停止/清理；剩余场景记 `ABORTED`；不得猜测通过 |
| invariant 失败 | `FAILED` | 保存证据；回滚环境；建立根因和整改项；冻结该 build 的发布 |
| Regional Certification 过期或漂移 | `BLOCKED` | 不签发新报告；重新认证区域数据面后重新申请授权 |
| write attempt 或 escape 非零 | `FAILED` 且安全事件 | 立即隔离环境、吊销凭据、排查目标；不得自动重试 |
| 同 nonce、授权或 runId 出现不同身份 | fork/冲突 | 拒绝执行；审计授权系统与数据库完整性 |
| Report/Bundle 验证失败 | 证据不可采信 | 不进入 ANEKE 门禁；从原始可信证据重新导出，不就地修改 JSON |

## 13. 上线清单

- [ ] Manifest 冻结完整 Scope、deployment、四类 build 和 12 场景，Owner 已批准 SLO。
- [ ] 环境被证明无生产流量、生产身份、生产 Secret 和真实业务出口。
- [ ] Authorization signer 与 Report signer 权限、密钥和审计日志独立。
- [ ] Adapter 独立验签、持久防重放、epoch fencing、deadline、kill switch 和清理流程已演练。
- [ ] `V20260815_003` 已审批，数据库时钟、事务隔离、唯一约束、备份与恢复已验证。
- [ ] BM-012 Regional Certification 与 v2 Isolation Decision 覆盖完整执行窗口。
- [ ] capability probe 区分 protocol、plan、journal、execution 和 regional readiness。
- [ ] Replay Bundle 经独立 Test Kit 与调用方固定信任根验证。
- [ ] Evidence 保留、WORM/Anchor、法务保留、删除证明和事故响应责任已冻结。
- [ ] CI、nightly、升级和客户现场认证的触发条件与失败门禁已接入发布流程。

## 14. 能力边界

仓库已经实现可移植协议、plan-first Harness、外部授权、客户 Adapter port、durable journal、恢复 SLO、
区域信任闭包、签名报告、自包含回放包、动态探针和独立 Test Kit。仓库测试证明这些工程语义成立。

以下事实仍只能由客户环境给出：真实 PostgreSQL HA/网络分区行为、云或机房故障动作、KMS/Vault/PKI
实际轮转、业务出口物理隔离、跨区域灾备、容量与长时间 soak、客户审批制度和证据保留制度。没有这些现场证据，
系统只能声明“具备认证能力”，不能声明“客户生产环境已认证”。
