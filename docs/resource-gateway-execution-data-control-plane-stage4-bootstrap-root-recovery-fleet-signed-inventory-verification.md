# Stage 4 bootstrap-root recovery fleet signed inventory verification

## 1. 本步边界

本步在既有 process-local recovery fleet inventory 与 durable fixed-partition coordinator 之间增加
deployment-owned 签名信任入口，新增：

- `bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation.v1`；
- canonical material、material fingerprint 与 distinct-authority Ed25519 M-of-N signature；
- deployment scope、artifact、`fleetId`、partition count、generation 与完整 lane descriptor 集绑定；
- strict JSON public-key/attestation 入口；
- 签名通过后按 lane key 从本地 reviewed catalog 精确解析 service/resolver；
- 每次 snapshot/cycle/lane/cursor commit 的 hard-expiry 与 exact-generation fence；
- aggregate-only signed inventory health 和诚实的 descriptor properties；
- strict public JSON Schema 与 test/staging Spring composition preflight。

本步只闭合 static signed configuration。它不宣称已具备 HTTPS/ETag 自动刷新、签名撤销 publication、
witness、durable generation floor、跨副本 refresh 收敛、capability/HTTP 或 production 接线。

## 2. 根因

原 `ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory` 已经能阻断同代 descriptor/runtime 换绑，
但 lane 集合和 generation 仍由每个 Resource Gateway 副本本地提供。这留下五个系统性缺口：

1. 被入侵副本可以删掉高风险 lane 后用更小的本地集合自证健康；
2. 不同副本可使用相同 generation 但不同 lane 集合，数据库 coordinator 只能看到后到达的 fingerprint
   冲突，不能证明哪一份是部署权威；
3. inventory 与 `fleetId`/partition count 分离配置，滚动发布时可把正确清单接到错误分区拓扑；
4. 只签 lane key 不能证明本地 service/resolver/runtime closure 就是治理审核的实现；
5. 没有 hard expiry 时，旧清单可在控制面失联后无限期继续授权扫描。

因此签名材料必须同时覆盖“谁、在哪个部署、用哪个制品、运行哪些 lane、使用哪套固定拓扑、在什么
时间窗口内有效”，而不是只给一份 lane id 列表加签名。

## 3. 签名材料

`ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation.Material` 绑定：

- 独立 inventory trust domain 与唯一 `inventoryId`；
- 严格单调 `generation`；
- tenant/environment 级 `deploymentScopeId`；
- durable `fleetId` 与不可滚动修改的 `partitionCount`；
- exact application image/artifact `sha256:`；
- 0..256 个按 `(scopeId, rootSetId)` 排序且唯一的完整 `LaneDescriptor`；
- external policy `sha256:`；
- whole-second `issuedAt/notBefore/expiresAt`。

空 descriptor 集是合法的治理 drain，不等于 authority unavailable。非 canonical 顺序、重复 key、null、
未知字段、duplicate JSON key、trailing token、私钥字段、超过 32 个签名或重复 authority 均在进入密码学
验证前拒绝。material fingerprint 由 canonical protocol mapper 重算；测试钉住 golden fingerprint，避免
无意序列化漂移改写跨系统协议身份。

机器合同为
[`external-sequence-anchor-bootstrap-root-recovery-fleet-inventory-v1.schema.json`](schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-inventory-v1.schema.json)。
Schema 对 attestation、material、lane descriptor、ceremony binding 和 authority signature 全部使用
`additionalProperties: false`，冻结 256-lane、64-partition、32-signature 上限；Java 构造器继续负责
canonical sort、Byzantine threshold、Duration 关系和 whole-second 等跨字段语义。

## 4. M-of-N 验签与时间

`ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority` 只接收 public Ed25519 keys。
每个 signature 必须来自不同 authority，key lifecycle、enabled/revoked、signed-at、material issuance 和
expiry 全部参与验证。有效签名不足 threshold、未知或 revoked key、错误签名、过早/未来签名均 fail
closed。

构造时要求清单已进入有效窗口，最大 inventory lifetime 为 30 天，允许的签名/发行 wall-clock skew 为
5 分钟。构造成功不是永久授权：`observation()` 与 `snapshot()` 每次使用当前 `Clock` 重检
`notBefore <= now < expiresAt`，所以到期无需重启即可关闭准入。

## 5. Runtime 反向绑定

签名清单只携带 public descriptor，不能反序列化 service 或 resolver。验签成功后，authority 仅以 signed
`LaneKey` 调用 caller-owned、non-blocking `LaneResolver`。resolver 返回的 `Lane` 必须满足：

1. `Lane` 自身的 expected binding 与 ceremony service 完全相等；
2. `Lane.descriptor()` 与 signed descriptor 全字段相等；
3. signed runtime-binding fingerprint 与本地 reviewed closure fingerprint 相等。

缺 lane、错 service、错 resolver closure、同 key fingerprint 漂移或 resolver failure 均使 authority 构造
失败。由此，签名控制面可以授权本地已审核实现，却不能通过 JSON 注入运行对象、endpoint 或凭据。

## 6. Worker 与拓扑 fence

durable worker 构造时若 inventory 实现
`ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority`，必须把本地 `fleetId` 与 partition count
和 signed `VerifiedBinding` 精确比较。错 fleet 或错 partition count 在读取 inventory、获取数据库 lease
和调用 lane 前失败。

每个 cycle 固定 snapshot generation，并在以下位置读取 in-memory observation：

- snapshot 接受后；
- 每个 local/durable lane 前后；
- durable acquisition 后启动 heartbeat 前；
- 停止 heartbeat 后、提交 durable cursor 前；
- 空 inventory、all-partitions-busy 或正常 cycle 返回前。

任何 hard expiry 或 generation change 都终止 cycle。durable cycle 会 abandon 最新 lease 且不推进 cursor；
lane ceremony journal 仍是业务 attempt/write 的唯一 authority。单个 lane 已经进入 provider 调用后无法由
inventory fence 强行中断；provider deadline、ceremony write fence 与进程隔离仍负责该边界。

## 7. 健康与信息最小化

`ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth` 只读取 immutable observation。有效的空
drain 和普通有效清单均为 UP；`NOT_YET_VALID`、`EXPIRED` 或 observation failure 为 DOWN。details 仅含：

- bounded status/source type；
- aggregate generation/lane count/signature count；
- `runtimeExpiryFence/fleetTopologyBound/exactRuntimeBinding`；
- 尚未具备的 `automaticRefresh/signedRevocation/durableGenerationFloor=false`。

health 不输出 deployment scope、fleet id、lane key、expiry timestamp、policy/material fingerprint、authority
id、public/private key、endpoint、payload、exception 或 message。

## 8. 嵌入方式

部署方先建立 reviewed local catalog，再从严格 JSON 配置构造 authority：

```java
var inventoryAuthority =
        ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.fromJson(
                objectMapper,
                "recovery-fleet-inventory.example",
                acceptedPolicyFingerprints,
                signatureThreshold,
                publicAuthorityKeysJson,
                signedInventoryAttestationJson,
                new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                        .VerifiedBinding(
                                "tenant-a/prod",
                                "bootstrap-root-recovery-v1",
                                deployedArtifactFingerprint,
                                8),
                reviewedLaneCatalog::get);

try (var worker = new ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker(
        inventoryAuthority,
        authenticatedWorkerId,
        new ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.Policy(30, 16),
        durableCoordinator,
        "bootstrap-root-recovery-v1",
        8)) {
    var inventoryHealth =
            new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth(
                    inventoryAuthority).health();
    var cycle = worker.runCycle();
}
```

`reviewedLaneCatalog::get` 必须是预构造内存 map；不得把远端 discovery、数据库查询、secret resolution 或
provider probe 隐藏在 resolver 中。所有副本必须部署相同 attestation、accepted policy、trust keys、artifact
fingerprint 和 topology。

在 test/staging Spring 应用中，把该 authority 以
`ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority` bean 注册，然后启用：

```bash
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ENABLED=true
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ID=bootstrap-root-recovery-v1
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_WORKER_ID="${HOSTNAME}"
export RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_PARTITIONS=8
```

`ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfiguration` 在创建数据库 coordinator 前读取一次
snapshot/observation/binding，精确校验 available、generation、lane count、fleet id 和 partition count；
成功后装配 durable coordinator、worker、scheduler、fleet health 和独立 inventory health。它默认关闭、
与单 root-set recovery 互斥，并在任何 `production` profile 下物理缺席。详细生命周期见
[runtime composition verification](resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-runtime-composition-verification.md)。

## 9. 验证

新增 authority 10 项、inventory health 4 项、Schema 3 项和 worker 4 项测试，共 21 项，覆盖：

- canonical order、duplicate/null descriptor 与 golden fingerprint；
- distinct-authority quorum、bad signature、revoked key、signature time 与 insufficient threshold；
- scope/fleet/artifact/partition/policy substitution；
- missing/drifted runtime lane；
- valid empty drain、hard expiry、future/expired/overlong material；
- strict JSON unknown/private/duplicate/trailing rejection；
- strict Schema/serialized DTO 字段一致性与 private material 排除；
- durable topology mismatch、local expiry-before-lane、binding drift 与 in-flight generation change abandon；
- UP/DOWN/unavailable health 与 diagnostics 脱敏。

聚焦命令：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthorityTest,ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealthTest,ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryProtocolSchemaTest,ExternalSequenceAnchorBootstrapRootRecoveryFleetWorkerTest \
  test
```

该命令执行 37 tests，0 failures、0 errors、0 skips。十一类 fleet 联合门禁执行 86 tests，0 failures、
0 errors、0 skips；再与既有 single-lane configuration 联合执行 95 tests，结果同样全绿。新增
attestation、authority、configured authority、inventory health、修改后的 worker 及 runtime configuration
六个公共类型通过 `javadoc --release 25 -Werror -Xdoclint:all`，0 warnings、0 errors。完整 Resource
Gateway `clean verify` 执行 3430 tests，0 failures、0 errors、2 skips；Browser DOM 34 项中 32 项及
browser workflow 1 项真实执行，并成功重打包 Spring Boot 可执行 JAR。

## 10. 仍未宣称

- strict HTTPS/ETag bounded refresh 与 last-known-good atomic swap；
- signed `ACTIVE/REVOKED` publication chain、independent witness 和 split-view detection；
- 数据库权威 generation/publication/witness floor、回滚/fork 拒绝与备份回滚外部锚；
- 全副本 inventory generation convergence gate 与 refresh SLO；
- enterprise IAM/PDP 对 lane membership、worker 与 resolver closure 的授权；
- capability truth 与 test/staging dynamic authority 自动装配已由后续子步闭合；production profile、运维
  配置 metadata 和外部告警/SLO 仍未完成；
- 在线 partition-count migration、rebalance、priority、weighted fairness；
- PostgreSQL/MySQL、multi-region HA、DR/chaos/soak 和 production SLO 认证。

后续 publication/floor kernel 已复用本步 attestation contract 冻结 witnessed `ACTIVE/REVOKED` 双链和数据库
durable floor，bounded HTTPS/ETag dynamic authority 也已真正消费该协议。static green tests 仍只证明本步
签名入口和运行时 fencing；动态失联、撤销与原子刷新验证见
[dynamic inventory verification](resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-dynamic-inventory-verification.md)，
底层 floor 见
[publication floor kernel verification](resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-publication-floor-kernel-verification.md)。
