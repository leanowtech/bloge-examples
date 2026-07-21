# Stage 4 bootstrap-root recovery fleet capability verification

## 1. 结论

本子步把 recovery-fleet 从“Spring 中已经运行，但集成方只能猜测是否可用”提升为既有
`GET /api/integration/capabilities` 上的版本化、机器可读、fail-closed 能力事实。新增
`bloge.externalSequenceAnchorBootstrapRootRecoveryFleetCapability.v1`，同时提供：

- `testability.recoveryFleet` 结构化状态；
- `features.bootstrapRootRecoveryFleet*` 九项向后兼容的布尔投影；
- `supportedObjects.externalSequenceAnchorBootstrapRootRecoveryFleetCapability` Schema 发现。

这不是新的运行或管理入口，也不允许 capability probe 触发远端 bootstrap、数据库、lane resolver 或
payload 读取。它只读取启动时冻结的唯一 bean 组合及其进程内 immutable snapshot，因此没有扩大认证面和
数据面。production profile 仍不装配 recovery fleet；能力对象只能如实回显 `DISABLED`，不能绕过发布门禁。

## 2. 根因

仅有 worker、scheduler 和 health bean 不能形成稳定的跨系统协议：

1. 集成方只能根据 bean、日志或配置推断就绪，容易把“端点存在”误判为“当前可执行”；
2. 多个 inventory、authority、worker 或 scheduler 候选若按 Spring 顺序选取，会把装配错误伪装成健康；
3. capability 请求若临时解析 lazy provider，可能在只读探针里触发网络 bootstrap 或产生新运行对象；
4. authority refresh 与探针并发时，单次读取可能混合两个 generation；
5. 直接导出 fleet/lane/URI/key/fingerprint 会把低基数健康面变成身份和信任材料泄漏面；
6. 单个 `ready` 布尔值不能区分未配置、半配置、库存失效、调度停滞、周期失败或 lane 隔离。

根治方式不是继续增加布尔开关，而是冻结一份独立 Schema、封闭状态机和无副作用投影协议。

## 3. 协议形状

权威 Schema：
[external-sequence-anchor-bootstrap-root-recovery-fleet-capability-v1.schema.json](schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-capability-v1.schema.json)。

| 字段组 | 字段 | 语义 |
| --- | --- | --- |
| 协议 | `schemaVersion` | 精确为 capability v1 |
| 总状态 | `configured`、`ready`、`status` | 是否存在组合、是否允许新 recovery poll、封闭原因 |
| inventory | `externallyAttested`、`inventoryAvailable`、`sourceType`、`inventoryGeneration`、`laneCount` | 当前已验签 aggregate truth，不含 identity |
| 治理强度 | `dynamicInventory`、`automaticRefresh`、`signedRevocation`、`witnessedPublications` | 是否具备动态刷新、撤销和独立 witness |
| 顺序防回退 | `durablePublicationFloor`、`externallyAnchoredPublicationFloor`、`byzantineQuorumAnchoredPublicationFloor` | 逐层加强且不可倒置的 publication floor |
| 运行状态 | `schedulerActive`、`schedulerOverdue` | 当前调度活跃与停滞事实 |
| 聚合计数 | `pollCount`、`pollFailureCount`、`cycleCount`、`cycleFailureCount` | 非负、失败数不超过总数的低基数累计值 |

Schema 使用 `additionalProperties=false`，限制 source type、lane 上界、非负计数和状态枚举，并形式化以下
关系：`DISABLED <=> configured=false`、`READY <=> ready=true`、dynamic source 与
`dynamicInventory` 等价、external anchor 必须建立在 durable floor 上、Byzantine quorum anchor 必须建立
在 external anchor 上。

## 4. 封闭状态机

| 状态 | 含义 | `ready` |
| --- | --- | --- |
| `DISABLED` | 本地没有任何 recovery-fleet bean | false |
| `INCOMPLETE_COMPOSITION` | inventory、worker 或 scheduler 缺失 | false |
| `AMBIGUOUS_COMPOSITION` | 任一 seam 有多个候选，或 inventory/authority 不是同一实例 | false |
| `UNATTESTED_INVENTORY` | 有本地 inventory，但没有 externally attested authority | false |
| `INVENTORY_UNAVAILABLE` | inventory 过期、撤销、过旧或 refresh 失败 | false |
| `RUNTIME_CLOSED` | worker 或 scheduler 已关闭 | false |
| `SCHEDULER_STALLED` | scheduler 超过有界 progress budget | false |
| `SCHEDULER_FAILED` | 最近 poll 失败 | false |
| `CYCLE_FAILED` | 最近 worker cycle 违反 fleet-wide invariant | false |
| `LANE_FAILURES` | 最近完整 cycle 隔离了至少一个 lane failure | false |
| `INCONSISTENT` | authority 两次 observation 跨代或与 descriptor 冲突 | false |
| `UNAVAILABLE` | 本地 snapshot 读取或校验异常 | false |
| `READY` | inventory、worker、scheduler 当前共同允许新 poll | true |

优先级刻意先判断 inventory，再判断生命周期、停滞、poll、cycle 和 lane failure。这样 inventory 已撤销时不会
被较次要的 runtime 故障掩盖。任何未知异常只收敛为固定 `UNAVAILABLE`，异常消息不会进入 wire payload。

## 5. Bean 发现与读取一致性

`ToolStudioIntegrationService` 在 Spring 注入阶段分别冻结 inventory、authority、worker、scheduler 的有序
候选列表。每次 HTTP capability probe 只对这些已存在实例读取本地 snapshot，不再次访问
`ObjectProvider`，也不创建 lazy authority。

组合规则是精确的：

1. 四类候选全部为零才是 `DISABLED`；
2. 任一类型大于一个立即 `AMBIGUOUS_COMPOSITION`，且不读取任何 runtime snapshot；
3. inventory、worker、scheduler 不是各一个时为 `INCOMPLETE_COMPOSITION`；
4. 缺 authority 但运行图完整时为 `UNATTESTED_INVENTORY`；
5. inventory 与 authority 必须是同一个 Spring singleton，禁止拼接两个各自看似合法的事实源；
6. 投影以 authority observation 前后夹读 descriptor、worker 和 scheduler；generation/status/source/lane
   任一撕裂都返回 identity-free `INCONSISTENT`。

这里冻结的是候选集合，不是健康结果。每次 probe 都重新读取本地 snapshot，因此 refresh、关闭、停滞和
故障会及时反映，不会把启动时的 `READY` 永久缓存。

## 6. 兼容与安全边界

capability 顶层仍使用 `toolStudio.resourceGateway.capabilities.v1`；本次是对既有开放发现文档的 additive
producer extension。旧 Java 构造器保留并默认注入 `DISABLED` recovery fleet，旧客户端可继续忽略未知
`supportedObjects`、`features` 和 `testability` 字段。需要精确消费 recovery fleet 的新客户端必须先检查
独立对象 Schema 版本，不能仅凭布尔 feature 推断字段语义。

结构化对象刻意不包含 fleet id、lane id、partition、publication URI、ETag、policy fingerprint、key id、
public key、signature、trust fingerprint、异常消息或业务 payload。公开的 source type、generation、lane
count 和累计计数均为有界 aggregate information。该探针不替代授权、health endpoint、告警或审计证据。

## 7. 集成方使用方式

集成方应按以下顺序判定：

1. 在 `supportedObjects` 中确认 capability Schema 版本；
2. 读取 `testability.recoveryFleet.status` 解释不可用原因；
3. 只有 `ready=true && status=READY` 才允许发起新的 recovery 工作；
4. 按需求检查 dynamic、revocation、witness 和 publication-floor 强度，不能把 `READY` 等同于所有治理能力
   均已达到 production 等级；
5. 对 `INCONSISTENT`、`UNAVAILABLE` 和所有 runtime failure 采用 fail-closed，并通过受控运维面排障；
6. `features` 仅用于旧客户端快速降级，结构化对象才是状态解释权威。

## 8. 验证

新增三层验证：

- capability kernel：正常投影、全部 runtime 故障优先级、inventory 不可用、跨 generation 撕裂、异常脱敏、
  关闭工厂和非法关系；
- strict Schema：字段闭包、枚举/source/lane parity、disabled/ready/dynamic/floor implication 和敏感词禁入；
- integration/Spring/HTTP：disabled 发现、动态 `READY` 双视图、每次重算、重复候选零读取、半配置与未证明
  区分、异常脱敏、同一 authority 双接口 singleton 解析，以及真实 `/api/integration/capabilities` 回显。

聚焦门禁：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ExternalSequenceAnchorBootstrapRootRecoveryFleetCapabilityTest,ExternalSequenceAnchorBootstrapRootRecoveryFleetCapabilityProtocolSchemaTest,ToolStudioRecoveryFleetCapabilityTest,ToolStudioIntegrationServiceTest,TestabilityCapabilitiesTest,TestRuntimeApplicationIntegrationTest \
  test
```

该组合执行 49 tests，0 failures、0 errors、0 skips；完整 recovery-fleet 18 类联合门禁执行 142 tests，
0 failures、0 errors、0 skips。新增公共 capability 类型通过
`javadoc --release 25 -Werror -Xdoclint:all`，0 warnings、0 errors。完整 Resource Gateway
`clean verify` 执行 3493 tests，0 failures、0 errors、2 条环境条件跳过，并成功重打包 Spring Boot
可执行 JAR。

## 9. 仍未解除的生产门禁

本子步只关闭 capability truth，不把下列事项伪装成已完成：

1. 运维配置 metadata、外部 metrics/alert routing、SLO 与跨副本 convergence readiness；
2. deployment/witness 原子双 trust-root publication、验证器与 durable key floor 内核已闭合，但 strict
   HTTPS/ETag refresh、unknown-key refresh、dynamic authority 与 capability 接线仍待完成；
3. publication floor 的外部 Byzantine anchor、publisher/witness HA 与 split-view/equivocation 检测；
4. mTLS、certificate pinning、DNS/proxy policy 和 response-key hot rotation；
5. enterprise IAM/PDP、HSM/KMS custody 和 production profile composition；
6. PostgreSQL/MySQL、rolling upgrade、backup/restore rollback、multi-region DR、chaos/soak 和容量认证；
7. online partition rebalance、受治理 fleet identity migration 与跨进程 supervisor。
