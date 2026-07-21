# Stage 4 bootstrap-root recovery fleet runtime composition verification

## 1. 结论与边界

本增量把 durable recovery fleet 从 Java embedding kernel 提升为可部署的 test/staging Spring
composition root。它装配：

- caller-owned local `ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory`，或 strict properties
  自动装配的 witnessed dynamic authority；
- database-clock `DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator`；
- bounded `ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker`；
- fixed-delay `ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler`；
- aggregate fleet health；
- 当 inventory 是 signed authority 时，额外装配 aggregate inventory health；
- dynamic 模式默认装配 durable database publication/witness floor。

它仍不是 production fleet service，也不负责签名生成、lane discovery、IAM、secret、capability 或 HTTP
endpoint。dynamic composition 只绑定 public trust、exact deployment/fleet binding 和 bounded transport
policy；lane resolver 仍是 caller-reviewed 唯一本地 catalog。这个 ownership 边界不会把 signer private key
或 provider credential 引入 Resource Gateway。

## 2. 根因

上一子步已经证明固定分区、数据库 lease、durable cursor 和 heartbeat，但调用方仍需手写 bean 顺序。该
状态有五个工程风险：

1. 多副本可误用 process-local worker，重启后公平游标归零；
2. scheduler 可能早于 inventory/topology 校验启动，留下表或后台线程等半初始化状态；
3. 单 root-set scheduler 与 fleet scheduler 可同时扫描同一恢复域；
4. Spring shutdown 若先关 worker 或 lane service，会在 admitted cycle 中制造悬空依赖；
5. profile/配置字段若宽松绑定，production 或拼错的安全配置可能静默启用错误行为。

因此根治点不是再包一层 convenience factory，而是冻结 profile、preflight、bean dependency、ownership、
mutual exclusion 和失败顺序。

## 3. 启用方式

调用方可以提供唯一 inventory bean。普通实现必须返回 immutable、bounded、non-blocking local snapshot；
更强模式可提供
`ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority`，由 composition preflight 额外校验其
observation 与 signed topology。也可以注册唯一 `LaneResolver`，并通过
`bootstrap-root-recovery-fleet-dynamic-inventory` properties 自动构造 dynamic authority。构造阶段必须先
成功取得一个可用的 `ACTIVE` publication，随后才启动后台 fixed-delay refresh。

最小配置：

```bash
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ENABLED=true
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ID=bootstrap-root-recovery-v1
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_WORKER_ID="${HOSTNAME}"
```

完整策略：

| Environment variable | Default | Invariant |
| --- | ---: | --- |
| `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ENABLED` | `false` | 必须显式为 true |
| `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ID` | empty | enabled 时必填；partition topology identity |
| `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_WORKER_ID` | empty | enabled 时必填；每副本稳定且可认证 |
| `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_PARTITIONS` | `8` | `1..64`；原 fleet id 下不可修改 |
| `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_LEASE_SECONDS` | `30` | 至少 3 秒并满足 coordinator policy |
| `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_MAX_LANES` | `16` | 单 cycle 有界 lane budget |
| `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_INITIAL_DELAY_MS` | `5000` | 非负 bounded duration |
| `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_POLL_INTERVAL_MS` | `5000` | 至少 100 ms |
| `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_MAX_CYCLE_MS` | `600000` | scheduler overdue budget |
| `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DRAIN_TIMEOUT_MS` | `5000` | bounded shutdown wait |

配置源位于 `application-test.yml` 与 `application-staging.yml`。对应
`FleetProperties` 使用 `ignoreUnknownFields=false`；拼错字段和试图注入 signer private key 的未知字段都
直接阻断启动。dynamic inventory 的完整环境变量表与示例见
[dynamic inventory verification](resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-dynamic-inventory-verification.md)。

## 4. Profile 与互斥

配置类使用 `!production & (test | staging)`，并要求
`gateway.testing.external-sequence-anchor.bootstrap-root-recovery-fleet.enabled=true`。因此：

- 只有 `test` 或 `staging` 可出现 fleet beans；
- 只要 active profiles 中包含 `production`，即使同时包含 `test` 也物理缺席；
- 默认配置不创建 coordinator table、worker、thread 或 health；
- fleet mode 与
  `gateway.testing.external-sequence-anchor.bootstrap-root-recovery.enabled=true` 启动前互斥。
- staging fleet 强制 `dynamic-inventory.required=true`，不能用环境变量降级到 static fallback；
- `allow-insecure-loopback=true` 只允许 test，staging 拒绝 localhost HTTP 逃生口。

互斥的病根是两套 scheduler 没有共享外层扫描 fence；允许同时运行不会增加安全性，只会增加重复 poll 和
运行解释歧义。

## 5. Stateful preflight

preflight 拆成两个有依赖顺序的 token。`ValidatedFleetConfiguration` 不读取 inventory、不建表、不访问网络，
先完成：

1. 单 lane/fleet 互斥检查；
2. staging required policy；
3. fleet identity、partition 和 strict property validation；
4. dynamic 模式的 public key、独立 trust domain、resolver 唯一性、binding、URI 与 duration validation。

随后才允许默认 floor DDL 和 dynamic remote bootstrap。最终 `ValidatedFleetRuntime` 再完成：

1. inventory snapshot 非阻塞读取与 DTO validation；
2. `fleetId + generation + inventory fingerprint + partitionCount` manifest 计算；
3. signed authority 校验：available、snapshot/observation generation、lane count、fleet id 和
   partition count 必须完全一致。
4. required 模式还要求 dynamic source type、coherent descriptor，以及 automatic refresh、signed
   revocation、durable generation floor、witnessed publication 四个能力真值。

任何错误都折叠为固定消息
`Bootstrap-root recovery fleet runtime configuration is invalid`。inventory/provider diagnostics 不进入启动
错误；测试同时证明失败时尚未创建任何 `RG_EXTERNAL_SEQUENCE_ANCHOR_%` 表。

## 6. Bean ownership 与关闭顺序

composition root 拥有默认 coordinator、worker、scheduler 和两个 health indicator。启用内置 dynamic
模式时，它还拥有 authority refresh scheduler 和默认 database floor；inventory、lane service、authority
resolver、`TestRuntimeDatabase`、`ObjectMapper` 仍由调用方拥有。

Spring dependency graph 使 scheduler 依赖 worker，worker 依赖 coordinator 和 preflight；销毁顺序因而是：

```text
scheduler.close -> worker.close -> dynamic authority.close -> caller closes resolver/database
```

`scheduler.close()` 停止新 poll 并等待 admitted scheduler cycle；`worker.close()` 再关闭新 worker admission
并等待已进入 cycle；dynamic authority 最后停止 refresh。composition 不关闭 caller-owned
service/resolver/database，避免跨 ownership 重复释放。

## 7. 可替换点与 fail-closed 约束

调用方可提供自定义 `ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator`，但 worker 构造时要求
`durable() == true`；不能用 in-memory mock 冒充跨副本协调。也可替换 worker/scheduler/health bean，但
替换方必须自己保持相同 ownership、profile 和关闭语义。

普通 inventory 只代表“调用方已授权的本地事实”。signed authority 才代表 external attestation，并自动
获得独立 inventory health。static 与 dynamic 能力由 authority descriptor 逐项投影；health 同时读取
observation 与 descriptor，若刷新恰好跨越两次读取导致 generation/status 不一致，则本次采样 fail closed，
而不是拼接两个时代的健康事实。

## 8. 验证矩阵

runtime configuration 的 11 项既有测试，加上 dynamic composition 的真实签名 HTTP/Spring 测试，覆盖：

- default-disabled、production-only 和 production+test 物理隔离；
- test/staging 完整组装、手工 run、aggregate health 与 close 后 quiescence；
- 共享 H2 数据库下完整 Spring context 重建后 durable cursor 从下一 lane 继续；
- 缺 inventory、single-lane 冲突、non-durable custom coordinator；
- invalid partition/schedule、unknown private-like property；
- inventory exception 脱敏且 stateful table 尚未创建；
- signed authority health 自动装配与 topology/generation preflight 失败。
- 默认 database floor、staging required、test fallback、insecure-loopback profile fence；
- malformed/unknown/half configuration、缺失/重复 resolver、non-durable floor 和重复 inventory 均在
  网络或 recovery state 前 fail closed。

聚焦命令：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfigurationTest \
  test
```

`ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfigurationTest` 11 项与
`ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfigurationTest` 10 项联合执行 21 tests，
0 failures、0 errors、0 skips；后者显式覆盖两种 configuration 注册顺序。包含后续 capability protocol 的
完整 recovery-fleet 18 类门禁执行 142 tests，0 failures、0 errors、
0 skips；相关公共类型通过 `javadoc --release 25 -Werror -Xdoclint:all`，0 warnings、0 errors。完整
Resource Gateway `clean verify` 执行 3493 tests，0 failures、0 errors、2 条环境条件跳过，并成功重打包
Spring Boot 可执行 JAR。

## 9. 未完成的生产门禁

- dynamic authority 的原子双信任根内核已闭合，在线 HTTPS refresh/unknown-key/consumer 接线未闭合；
- capability/schema discovery 已由后续子步闭合；运维配置 metadata、外部告警/SLO 与跨副本
  convergence readiness 仍未完成；
- enterprise IAM 对 worker/inventory/lane membership 的授权；
- production profile、PostgreSQL/MySQL 方言、连接池/锁超时与 rolling-upgrade certification；
- multi-region HA、backup/restore rollback、DR、chaos、soak 与外部 SLO；
- online partition rebalance、priority、weighted fairness 和 fleet-wide rollout jitter。

当前 H2 context-rebuild 测试证明 Spring ownership 与 cursor durability 的仓内闭环，不证明目标数据库或
生产故障域已经认证。
