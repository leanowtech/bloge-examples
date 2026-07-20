# Stage 4 Test-Secret External Non-Equivocation Verification

## 1. 结论

本增量关闭 test-secret 动态 serving inventory 的两个排序 head 只落在 Resource Gateway
本地数据库中的 split-view/backup-rollback 缺口：

1. deployment publication 与 independent witness 被合成为一个不可拆分的外部 sequence head；
2. atomic deployment/witness runtime-key root publication 形成第二条独立 sequence head；
3. 两条流都先提交外部 compare-and-append quorum，再推进本地 durable floor；
4. staging 强制 `3f+1 / 2f+1` 且 `f>=1`，最小部署是四个独立 failure domain、三个有效回执；
5. valid signed conflict 永远是致命冲突，不会被其他 `ACCEPTED` 回执掩盖。

这不是“多写一份日志”。外部 authority 是本地数据库之外的排序权威；只有在声明的
Byzantine fault bound、独立 failure domain 和外部持久性假设成立时，系统才可声明抵抗完整
Resource Gateway 数据库回滚与同代 split view。

## 2. 根因与不变量

前一增量已有 deployment/witness 双签名、严格 predecessor、数据库 floor 和 restart-free runtime
root rotation，但数据库管理员、备份恢复系统或被攻陷的本地存储权威仍可把所有副本一起恢复到一个
历史上一致且签名合法的旧 head。本地自洽不能证明“没有见过更新的 head”。

本增量冻结以下不变量：

- **External first**：外部 quorum 未成功前不得调用本地 floor。
- **Atomic publication/witness**：外部 publication head 必须同时提交两个 fingerprint；不能只推进其一。
- **Exact retry**：外部成功而本地失败时，重试必须产生逐字节相同的外部 head。
- **No protocol drift**：复用 `bloge.testSuiteStabilityExternalSequenceHead.v1` 的既有两类序列形状，
  不向闭集 v1 enum 增加 test-secret 专用值。
- **Domain separation**：publication 使用稳定 stream id
  `test-secret-authority-serving-inventory-publication`；root 使用固定长度
  `test-secret-root-<sha256(namespace || NUL || trustRootSetId)>`，既避免跨产品碰撞，也不缩窄
  合法 root-set id 长度。
- **Conflict dominance**：任一通过独立信任验证的 `CONFLICT` 都使整次操作失败，即使同时存在接受 quorum。
- **No sensitive projection**：health/capability 不输出 URI、stream id、scope、fingerprint、challenge、
  authority id、key id、签名或远端正文。

## 3. 协议映射

### 3.1 Publication/witness 流

`ExternallyAnchoredTestSecretAuthorityServingInventoryPublicationFloor` 对以下 canonical material
计算 SHA-256：

```text
schemaVersion = bloge.testSecretAuthorityServingInventoryExternalPublicationHead.v1
scopeId
sequence
publicationMaterialFingerprint
witnessMaterialFingerprint
```

successor 的 `previousHeadFingerprint` 使用相同 schema、`sequence-1` 和两个精确 predecessor
fingerprint 重算。这样 notary 无法接受“publication 已推进、witness 仍停留”的组合。

### 3.2 Runtime-root 流

`TestSecretAuthorityServingInventoryTrustRootPublication` 的 material fingerprint 已原子绑定
deployment/witness 两个 runtime key set、threshold、trust domain、policy、sequence 和 predecessor，
因此 root wrapper 直接把该 fingerprint 作为外部 head。

### 3.3 复用边界

HTTP media/header、fresh 256-bit challenge、request fingerprint、短时 Ed25519 receipt、
`3f+1 / 2f+1` quorum、独立 failure domain 和 authenticated-conflict 语义复用已经验证的 external
sequence v1 transport。Java 侧使用独立 `TestSecretAuthorityExternalSequenceAnchor` 端口，避免
Spring 将 suite-stability 的另一套 notary policy 误注入 test-secret 链。

## 4. Commit 与恢复语义

```text
verify signed candidate
        |
        v
external compare-and-append quorum
        |
        +-- unavailable / invalid quorum / signed conflict --> reject; local floor untouched
        |
        v
local database floor
        |
        +-- failure --> candidate remains unavailable; exact retry reuses external head
        |
        v
atomic local runtime publication
```

外部成功、本地失败会产生“external ahead”而不是“local unanchored”。同一 candidate 重试时，外部服务
必须幂等返回接受；不同 fingerprint 使用同一 sequence 则是 authenticated conflict。反向顺序不可接受，
因为它会短暂或永久产生未外部证明的本地 generation。

## 5. Spring 与部署策略

配置前缀：

```text
gateway.testing.test-secrets.authority.http.jwks.cohort
  .signed-inventory.remote.external-anchor.*
```

- `test`：默认 `enabled=false`、`required=false`、`minimum-faults=0`，便于本地演示；能力事实保持 false。
- `staging`：当 test-secret cohort 启用时，默认 `required=true`、`minimum-faults=1`。
- enabled/required、remote inventory 与 managed roots 的矛盾在容器启动时 fail closed。
- 配置必须恰好产生一个 test-secret external anchor；零个、多个、unavailable、非 external durable 或
  非 challenge-bound 均拒绝启动。
- `scripts/visual-canvas-demo.sh` 在构建和 Java 启动前检查必要 feature flags、HTTPS escape hatch、
  非空 endpoint、managed trust publication/bootstrap quorum、identifier、quorum 和 timing bounds。

## 6. Capability 与 Health

新增 capability truth：

- `testSecretAuthorityExternallyAnchoredInventoryPublicationFloor`
- `testSecretAuthorityByzantineQuorumInventoryPublicationFloor`
- `testSecretAuthorityExternallyAnchoredTrustRootFloor`
- `testSecretAuthorityByzantineQuorumTrustRootFloor`
- `testSecretAuthorityExternalNonEquivocationReady`
- `managedTestSecretExternalNotaryTrust`
- `restartFreeTestSecretExternalNotaryKeyRotation`
- `durableTestSecretExternalNotaryTrustFloor`
- `testSecretExternalNotaryTrustReady`

最后一个只有在 dynamic inventory ready、publication/root 两条流都 external anchored、两条流都满足
Byzantine quorum，且 authority descriptor 明确声明 composite non-equivocation 时才为 true。不会用
“配置了 endpoint”替代运行时可验证事实。

`TestSecretAuthorityExternalSequenceAnchorHealth` 仅输出 status、最近成功时间、成功/失败/冲突计数、
authority count、threshold、maximum faults 和独立 failure-domain count。读取 health 不发起远端 I/O。

## 7. 测试证据

直接和联合测试覆盖：

- publication/witness canonical composite 与 predecessor 连续性；
- root material direct mapping 与 test-secret stream namespace；
- external-first 调用顺序；
- external failure 不触碰任一本地 floor；
- external success/local failure 后 exact retry；
- unsafe anchor、non-durable local floor、hidden/duplicate provider fail-fast；
- Byzantine 与 non-Byzantine capability truth 不混淆；
- domain adapter 保持稳定 v1 head 不变；
- aggregate-only health 与 snapshot exception fail closed；
- staging 单 notary 降级拒绝与合法 four-notary/three-signature 配置；
- profile required-but-disabled 在 inventory/notary I/O 前失败；
- strict test-secret authority Schema 与 Tool Studio capability projection。

发布门禁：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
mvn -f resource-gateway-test-kit/pom.xml clean verify
bash -n scripts/visual-canvas-demo.sh
```

- Resource Gateway：3,188 tests，0 failures，0 errors，2 skipped，共 347 份 Surefire reports；
- Resource Gateway test-kit：230 tests，0 failures，0 errors，0 skipped，共 24 份 Surefire reports。

## 8. 明确未完成

- deployment/witness bootstrap-root 自身的轮换 ceremony、break-glass、KMS/HSM custody 与双人审批；
- bootstrap root 自身的 restart-free rotation ceremony 与独立 consistency witness；
- endpoint mTLS、certificate pinning、egress identity 与远端 workload identity；
- notary 服务的生产实现、跨区域部署、备份/恢复、WORM 介质、SLO、告警和审计导出；
- 非 H2 数据库、真实 backup rollback、网络分区、clock fault、soak、chaos 与多站点 DR 认证；
- 外部 quorum 只能根治已接入的 publication/root 两条流，不自动覆盖其他 test-control durable family。

因此当前可声明“test-secret 两条 mutable ordering stream 具备 external-first Byzantine
non-equivocation core”；不能声明 bootstrap trust、外部 notary 产品或整套控制面的生产认证已经完成。
Receipt notary public-key 的 managed trust publication 与 restart-free rotation 已由
[external notary trust rotation verification](resource-gateway-execution-data-control-plane-stage4-external-notary-trust-rotation-verification.md)
闭合。
