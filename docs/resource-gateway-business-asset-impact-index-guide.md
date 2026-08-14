# Resource Gateway Business Asset Impact 索引与 Deep Link 指南

> 状态：RG-BM-009 仓库内工程实现
>
> 最近更新：2026-08-14

本文面向业务能力 Owner、Resource Gateway 运维人员、ANEKE 集成人员和 Test Kit 消费者，说明如何从一个 L0-L3 业务资产精确定位受影响的 Package、Solution、SOP、Workflow、Agent 和 Channel，并在反向索引落后时安全恢复。

## 1. 解决的问题

Graph edge 只表达执行时的数据和控制依赖，不能可靠回答以下业务问题：

- `trip-api` 变更会影响哪些客服解决方案？
- 哪些 Workflow 或渠道仍绑定旧 Operator revision？
- 一个逻辑资产在多个 Authority 或 revision 中出现时，哪个精确事实产生了影响？
- 影响结论来自哪一版 Package Snapshot 和 Business Asset Link Closure？
- 从 ANEKE 的治理告警如何直接回到画布中的准确资产？

RG-BM-009 使用编译时已封存的 `BusinessAssetLinkClosure` 生成可重建反向索引。Snapshot 与 Closure 仍是权威事实，索引只负责查询，不获得业务资产写权限。

## 2. 端到端流程

```text
PackageCompiler
  -> append immutable Readiness / Link Closure / Package Snapshot
  -> append projection command to transactional outbox
  -> append DOMAIN_CAPABILITY_PACKAGE_SNAPSHOT_COMPILED
  -> commit authoritative facts once

leased projection worker
  -> claim oldest available command with database-clock lease
  -> load exact immutable compilation receipt
  -> verify Snapshot fingerprint
  -> compile deterministic transitive impact paths
  -> append immutable projection rows and advance Package head
  -> append BUSINESS_ASSET_IMPACT_CHANGED
  -> complete outbox command in the same transaction

authenticated query
  -> select logical kind/id plus optional authority
  -> join only each Package's current projection head
  -> compare head with latest immutable Package Snapshot
  -> return CURRENT or explicit STALE report with exact refs and Deep Links
```

投影失败不会回滚已经发布的 Package Snapshot。Worker 使用数据库时钟、owner、epoch 和 expiry 围栏旧副本；失败按指数退避重试，默认第 `8` 次失败后进入 `QUARANTINED`。重试只保存稳定失败码，不保存业务 payload、下游异常或凭据。

## 3. 能力发现

启动演示服务：

```bash
bash scripts/start-visual-canvas-demo.sh
```

检查能力探针：

```bash
curl -fsS http://localhost:8080/api/integration/capabilities \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: BUSINESS_MIRROR_AUTHORING' \
  | jq '.payload | {
      protocolVersion,
      impactReport: .supportedObjects.businessAssetImpactReport,
      impactRebuild: .supportedObjects.businessAssetImpactRebuildReport,
      impactQuery: .features.businessMirrorAssetImpactApi,
      impactRebuildApi: .features.businessMirrorAssetImpactRebuild,
      impactFreshness: .features.businessMirrorAssetImpactFreshness
    }'
```

必须同时检查对象版本、feature flag 和 endpoint。Schema 可协商不等于当前部署的 worker、数据库迁移或认证用途已就绪。

## 4. 查询影响

### 4.1 API

Resource Gateway 作者侧与集成侧共享相同报告协议：

```text
GET /api/business-mirror/business-assets/{kind}/{id}/impact
GET /api/integration/business-assets/{kind}/{id}/impact
```

参数：

| 参数 | 必填 | 语义 |
|---|---|---|
| `kind` | 是 | `RESOURCE`、`OPERATOR`、`BUILT_IN_FUNCTION`、`FEATURE`、`SCENARIO`、`SOLUTION`、`SOP`、`AGENT`、`WORKFLOW` 或 `CHANNEL_APPLICATION` |
| `id` | 是 | 逻辑资产 ID，不允许 `latest` 或模糊匹配 |
| `authority` | 否 | 限定资产权威源；空值会返回所有精确 authority/revision 匹配 |
| `afterPackageId` | 否 | Keyset 分页游标 |
| `limit` | 否 | `1-200`，默认 `100` |

示例：

```bash
curl -fsS \
  'http://localhost:8080/api/integration/business-assets/RESOURCE/trip-api/impact?authority=customer-registry&limit=25' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: CHANGE_SYNC' \
  | tee /tmp/trip-api-impact.json | jq
```

### 4.2 如何读报告

`resourceGateway.businessAssetImpactReport.v1` 包含：

| 字段 | 解释 |
|---|---|
| `scope` | 认证身份派生的五段企业 Scope，请求不能覆盖 |
| `selector` | 本次逻辑 kind/id/authority 查询条件 |
| `status` | `CURRENT` 或 `STALE`，不允许缺失 freshness 语义 |
| `stalePackageIds` | 最新 Snapshot 尚未被当前索引 head 覆盖的 Package，最多 `200` 个 |
| `items` | 每个受影响 Package 的当前投影，按 packageId 稳定排序 |
| `packageSnapshotRef` | 结论对应的 exact Package Snapshot |
| `businessAssetLinkClosureRef` | 影响路径对应的 exact Link Closure |
| `matches` | 同一逻辑资产的精确 authority/revision/fingerprint 匹配 |
| `paths` | 到每个下游业务资产的深度、路径数、最高风险和确定性代表路径 |
| `deepLink` | 返回 Business Mirror Workspace 的准确 Package 或资产坐标 |
| `projectedThrough` | 当前 Scope 最近一次成功投影的数据库时间 |
| `fingerprint` | 整份规范化报告的 SHA-256 内容地址 |

`pathCount` 是同一 source 到 target 的全部有向路径数；`representativePath` 是按稳定坐标选择的一条可解释路径，不代表唯一依赖路径。消费者做保守影响分析时应使用 `pathCount` 和 `highestRisk`，不能只看代表路径。

报告有以下协议上限：每页 `200` 个 Package，每个 Package `64` 个精确 source match，每个 source `4096` 个受影响 target，代表路径深度不超过 `64`，规范化报告不超过 `8 MiB`。超过上限时编译或封装失败关闭，不返回静默截断的“完整结果”。

## 5. Freshness 与重建

`CURRENT` 表示当前 Scope 下所有最新 immutable Package Snapshot 都已被 impact head 覆盖。`STALE` 表示报告中的旧结果仍可用于排障，但治理发布、回归分母选择和破坏性变更批准必须失败关闭。

维护用途为 `BUSINESS_MIRROR_MAINTENANCE`：

```bash
curl -fsS -X POST \
  'http://localhost:8080/api/business-mirror/impact-index/rebuild?limit=100' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: BUSINESS_MIRROR_MAINTENANCE' | jq
```

若 `nextCursor` 非空，使用原值继续：

```bash
curl -fsS -X POST \
  'http://localhost:8080/api/business-mirror/impact-index/rebuild?limit=100&afterPackageId=LAST_PACKAGE_ID' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: BUSINESS_MIRROR_MAINTENANCE' | jq
```

重建只读取 immutable compilation facts，不重新解析 mutable Registry，不分配新 compilation revision，也不修改 Snapshot。`projectedCount + replayedCount` 必须等于 `packageIds` 数量；非空 cursor 必须等于本页最后一个 packageId。

## 6. Deep Link

报告中的资产链接形如：

```text
/business-mirror/?packageId=refund-package
  &compilationRevision=7
  &task=capabilities
  &assetKind=RESOURCE
  &assetId=trip-api
  &assetRevision=3
  &assetAuthority=customer-registry
```

浏览器打开后会直接进入 Package 的 L0-L3 Capability Map，显示 compilation revision、kind、revision 和 authority，并只对完整 `kind + id + revision + authority` 坐标高亮。旧 catalog 若只有逻辑 ID，界面会额外插入精确目标，而不会把不完整引用伪装成命中。

Deep Link 只负责定位，不赋予读取权限。API 与 Workspace 仍使用当前认证 Scope；其他租户是否存在同名资产不会被泄露。

## 7. Test Kit 离线验真

无需启动 Resource Gateway：

```java
JsonNode impact = objectMapper.readTree(impactJson);
BusinessMirrorProtocol.requireBusinessAssetImpactReport(impact);

JsonNode rebuild = objectMapper.readTree(rebuildJson);
BusinessMirrorProtocol.requireBusinessAssetImpactRebuildReport(rebuild);
```

Test Kit 会执行 strict JSON Schema 和独立语义校验，包括：

- 复算报告 fingerprint；
- Scope、selector 和 exact source match 闭合；
- Package Snapshot / Link Closure exact ref；
- Package、source、target 稳定顺序与唯一性；
- 代表路径首尾、连续性、深度和最高风险；
- `CURRENT/STALE` 与 stale inventory 一致性；
- Deep Link 中 package、compilation 和资产坐标一致性；
- 重建计数、排序和 cursor 一致性。

完整 L0 到 L3 固定样例位于 `trip-api-impact-stage1-v1.fixture.json`，JAR 资源常量为 `BusinessMirrorProtocol.BUSINESS_ASSET_IMPACT_FIXTURE_RESOURCE`。

## 8. PostgreSQL 部署与运维

生产迁移：

```text
db/postgresql/V20260814_007__business_mirror_asset_impact.sql
```

它创建：

| 表 | 责任 |
|---|---|
| `business_mirror_asset_impact_outbox` | 事务投影命令、租约、attempt、退避和 quarantine |
| `business_mirror_asset_impact_locks` | 按 Scope + Package 串行化 head 与 outbox admission |
| `business_mirror_asset_impact_heads` | 每个 Package 当前投影 revision/fingerprint |
| `business_mirror_asset_impact_projections` | 追加保存每个 source exact ref 的完整有界路径投影 |

示例运行时会 `CREATE TABLE IF NOT EXISTS` 便于本地启动；企业部署必须由正式迁移平台先应用 V007，并保留 DDL 审批、备份和回滚证据。

Worker 默认每 `2000 ms` 运行一次，每轮最多处理 `50` 个命令。可通过下列配置调整轮询频率：

```properties
gateway.business-mirror.impact.worker-fixed-delay-ms=2000
```

当前 v1 固定使用 `2` 分钟 lease、`8` 次 attempt 和最大 `5` 分钟指数退避。修改这些安全参数需要新增版本化运维策略与容量认证，不能只按现场感觉放大。

### 8.1 排障顺序

1. 能力探针确认 impact API 和对象版本存在。
2. 查询报告查看 `status`、`stalePackageIds` 和 `projectedThrough`。
3. 检查 outbox 的 `PENDING/PROJECTING/QUARANTINED` 数量和最老 `available_at`。
4. 对 stale Package 运行有界 rebuild，而不是删除 projection 表。
5. 若进入 quarantine，先修复 immutable fact、DDL、容量或代码问题；v1 没有自动“跳过并标绿”。
6. 修复后通过受控数据库运维把准确 job 恢复为 `PENDING`，保留变更单和前后行证据；后续 BM-013 将提供正式 quarantine remediation API 与认证包。

不允许直接改写 `heads` 或旧 projection JSON。索引可整体重建，篡改当前 head 会让 freshness 与业务影响结论失去证据闭包。

## 9. 失败语义与恢复

| 情况 | 行为 | 恢复 |
|---|---|---|
| selector 非法或 page 越界 | `400` + 稳定 problem code | 修正 kind/id/authority/limit |
| Scope 不完整或 purpose 不允许 | `401/403`，不泄露其他 Scope | 修复 workload identity 和用途授权 |
| outbox 同 revision 不同 Snapshot fingerprint | projection drift，事务回滚 | 审计 immutable coordinate 冲突，不覆盖旧事实 |
| worker 失败 | 指数退避；不回滚 Snapshot | 修复根因，等待重试或受控解除 quarantine |
| 旧 worker 完成新 epoch | completion 返回失败并回滚 | 新 lease owner 继续处理 |
| 索引落后 | 报告 `STALE` + stale ids | 有界 rebuild；治理消费失败关闭 |
| 存储 projection fingerprint 漂移 | 查询失败关闭 | 从 immutable facts 重建，调查数据库篡改或损坏 |
| 报告超过协议上限 | 不生成部分报告 | 按业务域拆 Package 或升级有版本的分页协议 |

## 10. 验证命令

聚焦验证：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='BusinessAssetImpact*Test,DatabaseBusinessAssetImpact*Test,\
BusinessMirrorPackageSpringWiringTest,BusinessMirrorCapabilityTest' test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=BusinessMirrorProtocolTest test

npm --prefix resource-gateway-examples/src/main/frontend run build
```

发布前完整门禁：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

停止演示服务：

```bash
bash scripts/stop-visual-canvas-demo.sh
```

## 11. 当前边界

本轮证明的是仓库内 impact vertical slice：确定性传递、持久反向索引、事务 outbox、跨副本租约、认证查询/重建、Deep Link、严格协议、独立消费者和原生 PostgreSQL 认证。

它不证明 PostgreSQL HA failover、PITR、跨区域复制、客户 KMS/WORM、超大业务域容量、真实 ANEKE 事件消费或业务 Owner 对影响语义的验收。这些证据分别属于 BM-012、BM-013、BM-014 和 BM-015，不能由本地测试替代。
