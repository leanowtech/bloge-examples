# Resource Gateway 与 ANEKE Package 集成指南

## 1. 本文用途

本文面向 Resource Gateway 实施人员、ANEKE Tool Studio 集成人员、治理平台开发者和 SRE。
内容覆盖 `ToolStudioResourceGatewayProtocol 1.1.0` 的 Package registry ingest、治理投影回传、
离线复验、部署、故障恢复和上线检查。

本能力只建立跨系统工程协议：

- Resource Gateway 是 Package Snapshot、Readiness、L0-L3 关系闭包和 Evidence Index 的事实权威；
- ANEKE 是 registry record、workbook、owner approval、gate decision 和发布状态的治理权威；
- Resource Gateway 缓存 ANEKE 签名投影，用于作者回显和陈旧检测，不复制 ANEKE registry 或发布门禁；
- 固定 fixture 只证明 wire compatibility，不能作为客户治理审批或生产认证证据。

## 2. 一次完整交互

```text
Resource Gateway                    ANEKE Tool Studio
       |                                   |
       |  GET registry-ingest-bundle       |
       |----------------------------------->|
       |  Snapshot + Readiness + L0-L3     |
       |  + Evidence + exact dependencies  |
       |                                   |
       |                    registry/workbook/gate
       |                                   |
       |  signed governance projection     |
       |<-----------------------------------|
       |  verify trust + current RG facts  |
       |  + expiry + monotonic generation  |
       |                                   |
       |  receipt / joined current view    |
       |----------------------------------->|
```

这不是同步 mutable draft。每次交互都绑定不可变 revision 与 SHA-256 内容地址。Package 重新编译、
Evidence 重投影或 ANEKE 投影过期后，旧投影变为 `STALE` 或 `EXPIRED`，不会被沿用为当前治理结论。

## 3. 协议对象

| 对象 | `schemaVersion` | Authority | 作用 |
|---|---|---|---|
| Package Registry Ingest Bundle | `toolStudio.resourceGateway.packageRegistryIngestBundle.v1` | Resource Gateway | 向 ANEKE 交付一个编译 revision 的完整不可变事实闭包 |
| Package Governance Projection | `toolStudio.domainCapabilityPackageGovernanceProjection.v1` | ANEKE | 回传 registry record、gate decision、状态、外部 generation 和签名 |
| Package Governance View | `toolStudio.domainCapabilityPackageGovernanceView.v1` | Resource Gateway 派生 | 联结当前 RG 事实与缓存投影，并输出 freshness |
| Projection Receipt | `toolStudio.packageGovernanceProjectionReceipt.v1` | Resource Gateway | 表示新 generation 已提交或同一投影已精确重放 |

严格 Schema 位于 `docs/schemas/tool-studio-resource-gateway/`。Bundle 与 Projection 各有一份
server-produced fixed fixture。Test Kit JAR 同时打包四份 Schema 和两份 fixture。

### 3.1 Registry Ingest Bundle 闭包

Bundle 不是若干松散 JSON 的压缩包。消费者必须同时验证：

1. `scope` 与 Snapshot、Readiness、Business Asset Link Closure、Evidence Index 完全一致。
2. `packageId` 与 `revision` 在四类事实中一致。
3. Snapshot 的 `readinessReportRef` 和 `businessAssetLinkClosureRef` 精确指向内嵌对象。
4. Evidence 的三个 source 分别精确指向 Snapshot、Readiness 和关系闭包。
5. `dependencyManifest` 与 Snapshot 中的 manifest 顺序和值完全一致。
6. 每个内嵌对象及整个 Bundle 的内容地址均可重新计算。
7. `exportedAt` 不早于 Snapshot 创建和 Evidence 投影时间。

Bundle 不携带 ANEKE registry 状态、审批结果或发布结论。

### 3.2 Governance Projection 信任闭包

Projection 必须同时满足：

- 完整企业 Scope 与当前请求身份一致；
- `packageSnapshotRef`、`registryIngestBundleRef` 和 `evidenceIndexRef` 指向当前 RG 事实；
- `externalGeneration` 从 `1` 开始，只允许逐一递增；
- 同一 generation 只允许同一 `projectionFingerprint` 精确重放；
- `projectionId` 与 `issuer` 在一个 Package stream 内不可更换；
- `UNDER_REVIEW` 不携带 `gateDecisionRef`，其余状态必须携带 exact gate decision；
- 有效期最长 7 天，接收和读取时均重新检查；
- `projectionSeal` 使用独立签名域，信任由部署方提供的 ANEKE trust adapter 决定。

## 4. 五分钟体验

### 4.1 启动和停止

从仓库根目录启动 test profile：

```bash
./scripts/start-visual-canvas-demo.sh --profile test --port 8080
```

查看状态和日志位置：

```bash
./scripts/visual-canvas-demo.sh status --port 8080
```

停止服务：

```bash
./scripts/stop-visual-canvas-demo.sh --port 8080
```

本地 demo 使用 `bloge-aneke-demo-token`。该 token 只用于本地 test profile。

### 4.2 检查协议与运行就绪状态

```bash
curl -fsS http://localhost:8080/api/integration/capabilities \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: GOVERNANCE_EVIDENCE_INGESTION' \
  | jq '.payload | {
      protocolVersion,
      objects: {
        bundle: .supportedObjects.packageRegistryIngestBundle,
        projection: .supportedObjects.domainCapabilityPackageGovernanceProjection,
        view: .supportedObjects.domainCapabilityPackageGovernanceView,
        receipt: .supportedObjects.packageGovernanceProjectionReceipt
      },
      features: {
        protocol: .features.businessMirrorPackageGovernanceProtocol,
        exportApi: .features.businessMirrorPackageRegistryIngestApi,
        projectionApi: .features.businessMirrorPackageGovernanceProjectionApi,
        ingestReady: .features.businessMirrorPackageGovernanceProjectionIngestReady
      }
    }'
```

解释：

- `protocol=true` 表示当前构建理解四类协议对象。
- `exportApi=true` 和 `projectionApi=true` 表示路由已物理装配。
- `ingestReady=false` 表示没有安装客户 ANEKE trust adapter。此时导出仍可使用，投影写入返回
  `RG.BUSINESS_MIRROR.GOVERNANCE_TRUST_UNAVAILABLE`。这是预期的失败关闭，不是 demo 故障。

### 4.3 离线查看和复验固定闭包

不安装 ANEKE，也可以检查固定协议向量：

```bash
jq '{bundleId, revision, packageId: .packageSnapshot.packageId,
     dependencies: (.dependencyManifest | length),
     bundleFingerprint}' \
  docs/schemas/tool-studio-resource-gateway/package-registry-ingest-bundle-v1.fixture.json

jq '{projectionId, externalGeneration, status, packageSnapshotRef,
     registryIngestBundleRef, evidenceIndexRef, issuer, expiresAt}' \
  docs/schemas/tool-studio-resource-gateway/domain-capability-package-governance-projection-v1.fixture.json

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=PackageGovernanceProtocolTest test
```

该测试使用独立生成的 fixture 公钥验证 Ed25519 签名，并覆盖地址篡改、闭包漂移、未知字段、
trust 拒绝和过期投影。它不链接 Resource Gateway 服务端或 Spring。

## 5. HTTP 接入

以下命令假设 Package 已成功编译，且 Package Evidence worker 已为同一 compilation revision 生成
Evidence Index。Package 创建和编译流程见
[Business Mirror Package Authoring 指南](resource-gateway-business-mirror-package-authoring-guide.md)，
Evidence 投影流程见
[Package Evidence 与 Fidelity 指南](resource-gateway-package-evidence-and-fidelity-guide.md)。

```bash
export RG_URL=http://localhost:8080
export RG_TOKEN=bloge-aneke-demo-token
export PACKAGE_ID=cancellation-package
export COMPILATION_REVISION=7
```

实际 Scope 由 trusted identity registry 从 credential 映射。请求头不能覆盖 Scope。

### 5.1 导出 Registry Ingest Bundle

```bash
curl -fsS \
  "$RG_URL/api/integration/domain-capability-packages/$PACKAGE_ID/revisions/$COMPILATION_REVISION/registry-ingest-bundle" \
  -H "Authorization: Bearer $RG_TOKEN" \
  -H 'X-Purpose: GOVERNANCE_EVIDENCE_INGESTION' \
  | tee /tmp/package-registry-ingest-bundle.json | jq
```

ANEKE 在写 registry 前，应使用 Test Kit 或等价的非 JVM verifier 重新计算全部内容地址和闭包。
不要只校验顶层 `bundleFingerprint`。

### 5.2 回传签名 Governance Projection

ANEKE 生成 Projection 时，必须绑定 5.1 返回的三个 exact refs。随后调用：

```bash
curl -fsS -X POST \
  "$RG_URL/api/integration/domain-capability-packages/$PACKAGE_ID/governance-projections" \
  -H "Authorization: Bearer $RG_TOKEN" \
  -H 'X-Purpose: GOVERNANCE_GATE_FEEDBACK' \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/aneke-package-governance-projection.json \
  | tee /tmp/package-governance-receipt.json | jq
```

POST 不使用调用方 `Idempotency-Key`。幂等坐标是完整 Scope、Package、`externalGeneration` 和
`projectionFingerprint`。网络超时后，应原样重放同一 Projection：

- 同一 generation 与同一 fingerprint 返回 `replayed=true`；
- 同一 generation 与不同 fingerprint 返回 generation fork 冲突；
- 更小 generation 返回 rollback 冲突；
- 跳过 generation 返回 gap 冲突。

### 5.3 读取当前治理视图

```bash
curl -fsS \
  "$RG_URL/api/integration/domain-capability-packages/$PACKAGE_ID/governance-projection" \
  -H "Authorization: Bearer $RG_TOKEN" \
  -H 'X-Purpose: GOVERNANCE_EVIDENCE_INGESTION' \
  | jq '{packageId, freshness, reasonCode,
         currentPackageSnapshotRef, currentEvidenceIndexRef,
         currentRegistryIngestBundleRef,
         governanceStatus: (.projection.status // null)}'
```

`freshness` 是 Resource Gateway 根据当前事实派生的结果：

| 值 | 含义 | 恢复动作 |
|---|---|---|
| `CURRENT` | 签名、窗口和三个 RG exact refs 均为当前值 | 可供作者回显；发布决定仍以 ANEKE 为准 |
| `MISSING` | 尚未接收投影 | ANEKE 读取当前 Bundle 后提交 generation `1` |
| `STALE` | Package、Evidence 或 Bundle 已变化，或 Evidence 已过期 | 重新导出 Bundle，ANEKE 重新治理并递增 generation |
| `EXPIRED` | Projection 不在有效期内 | ANEKE 以当前事实签发下一 generation |
| `UNVERIFIABLE` | trust 不可用、签名失败或持久化状态损坏 | 停止消费治理结论，修复 trust 或数据后重新读取 |

## 6. 部署 ANEKE Trust Adapter

仓库故意不提供“接受所有签名”的实现。部署方必须提供一个
`PackageGovernanceProjectionTrust` Bean，并在 `verify` 中完成：

1. 固定 ANEKE issuer、trust domain 和允许的 `keyId`。
2. 从独立受信渠道取得公钥或 key set，不信任 Projection 自带的公钥材料。
3. 检查 key 的 `notBefore`、`notAfter`、retire、revoke 和 compromise 事件。
4. 按 `projectionSeal.materialFingerprint` 验证 detached signature。
5. 将 Projection 的 `issuer`、Scope 和环境约束纳入授权决策。
6. trust source 不可用或状态不确定时返回不可用或拒绝，不使用最后一次未知状态继续放行。

安装 Bean 后，能力探针中的
`businessMirrorPackageGovernanceProjectionIngestReady` 才能变为 `true`。

`test/staging` 路由还要求：

```text
spring profile = test 或 staging
gateway.testing.mirror.enabled = true
```

`production` profile 不装配当前示例 Controller。生产化部署必须先完成客户认证和独立配置评审，
不得通过混合 profile 绕过该边界。

## 7. PostgreSQL 迁移与并发语义

部署前应用：

```text
resource-gateway-examples/src/main/resources/db/postgresql/
V20260815_004__package_governance_projection.sql
```

迁移创建：

- `business_mirror_package_governance_heads`：每个完整 Scope + Package 的当前 generation；
- `business_mirror_package_governance_projections`：append-only 投影历史和 exact refs；
- 按完整 Scope 与 `expires_at` 建立的过期扫描索引。

Repository 使用独立事务初始化 head，再用 `FOR UPDATE` 和 compare-and-set 推进 generation。
这是必要语义：PostgreSQL 唯一键冲突会终止当前事务，不能像 H2 一样在同一事务捕获冲突后继续。
原生 PostgreSQL 认证测试会启动两个独立 DataSource 和 transaction manager，证明两个冲突 successor
只能提交一个。

运行聚焦认证：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabasePackageGovernanceProjectionPostgresCertificationTest test
```

## 8. 稳定错误与恢复

| 错误码或 reason | 含义 | 恢复动作 |
|---|---|---|
| `RG.BUSINESS_MIRROR.REGISTRY_BUNDLE_NOT_FOUND` | exact compilation revision 不存在或不在当前 Scope | 核对身份 Scope、Package 和 revision |
| `RG.BUSINESS_MIRROR.REGISTRY_BUNDLE_EVIDENCE_UNAVAILABLE` | compilation 尚无 exact Evidence Index | 等待或恢复 Evidence projection worker，不要改用其他 revision |
| `RG.BUSINESS_MIRROR.GOVERNANCE_TRUST_UNAVAILABLE` | 未安装或无法访问 ANEKE trust | 修复 trust adapter；不要降级为跳过验签 |
| `RG.BUSINESS_MIRROR.GOVERNANCE_PROJECTION_INVALID` | 地址、签名材料、签名或结构无效 | 丢弃输入，从当前 Bundle 重新生成 Projection |
| `RG.GOVERNANCE.PROJECTION_STALE` | Projection 不再绑定当前 RG facts 或有效窗口 | 重新导出、治理、递增 generation |
| `...GENERATION_ROLLBACK` | 收到比 current 更旧的 generation | 读取当前 ANEKE/RG cursor，停止旧 worker |
| `...GENERATION_FORK` | 同一 generation 出现不同内容地址 | 隔离冲突 producer，保留两份输入供审计，不自动选胜者 |
| `...GENERATION_GAP` | generation 不连续 | 补交缺失 generation，或按治理恢复流程重建 stream |
| `...STREAM_IDENTITY_MISMATCH` | `projectionId` 或 `issuer` 试图接管既有 stream | 停止写入并检查 ANEKE tenant/issuer 路由 |

错误响应和日志不得包含 Projection、业务 Payload、credential 或签名私钥。

## 9. Test Kit 接入

```java
JsonNode bundle = objectMapper.readTree(bundleBytes);
JsonNode projection = objectMapper.readTree(projectionBytes);

var verifiedBundle = PackageGovernanceProtocol.verifyRegistryIngestBundle(bundle);
var verifiedProjection = PackageGovernanceProtocol.verifyGovernanceProjection(
        projection,
        bundle,
        (fingerprint, algorithm, keyId, signedAt, signature) ->
                anekeTrust.verify(fingerprint, algorithm, keyId, signedAt, signature),
        clock.instant());
```

Verifier 返回的记录只包含 Package、revision、fingerprint、状态和时间，不返回业务 Payload。
调用方仍需自己管理 ANEKE key lifecycle、issuer policy、revocation floor 和可信时间。

协议 `1.1.0` 是 additive upgrade。Test Kit 同时接受 `1.1.0` 与最低兼容版 `1.0.0` 的 integration
envelope；Stage 0 capability baseline 的 mixed-version 测试覆盖两者。旧消费者可以忽略新增对象，
但不能把未知治理状态解释为通过。

## 10. 上线检查

- [ ] Resource Gateway 与 ANEKE 对 Scope、issuer、key lifecycle 和 generation ownership 达成书面约定。
- [ ] 能力探针显示协议对象版本正确，API 和 ingest readiness 与实际装配一致。
- [ ] ANEKE 在 registry ingest 前独立复验完整 Bundle，而非只信任 HTTP 成功。
- [ ] trust adapter 使用独立分发的 key set，并覆盖 revoke、retire、过期和 source unavailable。
- [ ] stale、expired、rollback、fork、gap、stream takeover 和 exact replay 已演练。
- [ ] PostgreSQL migration、备份恢复和双副本竞争认证在目标版本完成。
- [ ] change event 消费者能处理重复事件，并使用 cursor 恢复，不以事件替代当前事实读取。
- [ ] 日志、指标和告警保持 payload-free，精确 Package 坐标只进入受保护审计。
- [ ] 固定 fixture 未被注册为客户 registry record、gate decision 或生产证据。
- [ ] Resource Gateway 没有新增 ANEKE registry、workbook、owner approval 或 publish authority。

