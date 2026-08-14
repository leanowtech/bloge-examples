# Resource Gateway 存量 Graph 渐进迁移指南

> 适用协议：`resourceGateway.legacyGraphPackageProjection.v1`
>
> 实现状态：BM-004 仓库内工程实现已完成
>
> 目标读者：业务能力 Package 作者、平台实施人员、ANEKE 集成人员和审计工具开发者

本文说明如何把 Resource Gateway 已有 Graph 渐进包装为 `DomainCapabilityPackageDraft`。迁移接口复用原 Graph、Graph Contract、Operator、Resource 和 Contract Test Suite 的权威事实，不改写 Graph 拓扑，也不把技术资产误判为已经治理完备的业务能力。

## 1. 能力边界

迁移器负责四件事：

1. 读取一个存量 Graph 的 exact source facts；
2. 生成不写数据库的 Package preview 和完整 gap inventory；
3. 通过既有 durable authoring 事务幂等创建 Package revision `1`；
4. 让服务端和 Test Kit 独立复算 projection fingerprint 与迁移语义。

迁移器不负责：

- 从拓扑猜测客户问题、业务目标、Owner、风险和 Outcome；
- 把 Contract Test Suite 直接改名为 Scenario inventory 或 ScenarioPack；
- 代表业务 Owner 批准推断结果；
- 绕过 Package compiler、ANEKE publish gate 或生产认证；
- 修改原 Graph、Contract、Operator 或 Resource。

因此，内置 Graph 可执行，不等于导入后的 Package 可发布。当前七个内置 Graph 的 preview 都应为 `BLOCKED`；这是迁移完整性的证明，不是接口故障。

## 2. 投影模型

| 存量事实 | 投影字段 | 处理规则 |
|---|---|---|
| Graph DSL 与 formal Graph Contract | `sourceGraphRef` | 使用 `GRAPH_DRAFT` exact ref；不复制拓扑 |
| Graph Contract | `sourceContractRef`、`packageContractRef` | 保留技术契约，但要求 Package Owner 再确认 |
| Graph capability root | `projectedCapabilityRef` | 使用 immutable `CAPABILITY` ref，不写入作者态 `capabilityRefs` |
| 完整外部依赖闭包 | `capabilityClosureRef` | 使用 content-addressed `CAPABILITY_CLOSURE` ref |
| Contract Test Suite | `discoveredTestSuiteRefs` | 仅作为迁移证据，不冒充 Scenario 治理资产 |
| 无法证明的业务字段 | `packageDraft` 空值与 `gaps` | fail closed，不填默认业务语义 |
| 来源与限制 | `provenance` | 固定为 `INFERRED`，无 Owner approval |

导入 Package 使用稳定 id `legacy:{graphName}`，作者态 revision 固定为 `0`；成功创建后由 durable repository 分配 revision `1`。重复 preview 不写入任何状态。

## 3. Gap 不是提示词

每个 gap 都包含稳定 `code`、`origin`、`category`、`severity`、JSON Pointer、解释、恢复动作和 payload-free evidence refs。

`PACKAGE_READINESS` gap 必须与 `packageDraft.readinessBlockers()` 精确相等。迁移器额外增加以下 policy gap：

| Gap | 级别 | 必须完成的工作 |
|---|---|---|
| `GRAPH_CONTRACT_OWNER_CONFIRMATION_MISSING` | `BLOCKING` | 由 Package Owner 确认或替换技术 Graph Contract |
| `MIRROR_PLAN_MISSING` | `BLOCKING` | 在业务边界确认后编译并审阅 exact MirrorPlan |
| `LEGACY_PROJECTION_OWNER_APPROVAL_MISSING` | `BLOCKING` | 独立审阅全部推断绑定并记录 Owner approval |
| `DISCOVERED_TEST_SUITE_REQUIRES_SCENARIO_GOVERNANCE` | `WARNING` | 将发现的测试分类、去重、版本化并纳入 Scenario denominator |

系统不提供“忽略全部 gap 并标绿”的入口。业务字段、Scenario、Fidelity、Outcome、L1-L3 绑定及高风险 State/Effect 缺失时，Package compiler 必须继续输出 `BLOCKED`。

## 4. 启动与能力确认

从仓库根目录设置固定演示 Scope：

```bash
export RG_INTEGRATION_TENANT_ID=ride-hailing
export RG_INTEGRATION_ORGANIZATION_ID=customer-service
export RG_INTEGRATION_PROJECT_ID=cancellation
export RG_INTEGRATION_ENVIRONMENT_ID=test
export RG_INTEGRATION_REGION=sg
export RG_INTEGRATION_ACTOR_ID=business-mirror-demo-author
export RG_INTEGRATION_ALLOWED_PURPOSES=BUSINESS_MIRROR_AUTHORING

./scripts/start-visual-canvas-demo.sh
```

定义后续命令共用的认证头：

```bash
AUTH=(-H 'Authorization: Bearer bloge-aneke-demo-token' \
      -H 'X-Purpose: BUSINESS_MIRROR_AUTHORING')
```

检查能力：

```bash
curl -fsS "${AUTH[@]}" \
  http://localhost:8080/api/integration/capabilities \
  | jq '.payload | {
      api: .features.businessMirrorLegacyMigrationApi,
      authorityReady: .features.businessMirrorLegacyMigrationAuthorityReady,
      projection: .supportedObjects.legacyGraphPackageProjection,
      catalog: .supportedObjects.legacyGraphPackageProjectionCatalog
    }'
```

本地演示的 `api` 和 `authorityReady` 都应为 `true`。前者只表示路由与协议已安装；后者表示至少一个 Graph 可被 installed authority 精确读取。两者都不表示任意 Package 已 READY。

## 5. 完成一次迁移

### 5.1 查看完整候选目录

```bash
curl -fsS "${AUTH[@]}" \
  http://localhost:8080/api/business-mirror/legacy-graphs \
  | tee /tmp/legacy-graph-catalog.json \
  | jq '[.items[] | {
      graphName,
      packageId: .packageDraft.packageId,
      status,
      gaps: (.gaps | length),
      tests: (.discoveredTestSuiteRefs | length)
    }]'
```

预期目录按 `graphName` 严格递增，包含七个内置 Graph，且使用同一个认证 Scope。目录有界返回，不提供跨 Scope 聚合。

### 5.2 预览一个 Graph

```bash
curl -fsS "${AUTH[@]}" \
  http://localhost:8080/api/business-mirror/legacy-graphs/loanDecisionPolicy \
  | tee /tmp/loan-decision-legacy-projection.json \
  | jq '{
      graphName,
      status,
      sourceGraphRef,
      sourceContractRef,
      projectedCapabilityRef,
      discoveredTestSuiteRefs,
      gaps: [.gaps[] | {code, category, severity, draftPath, requiredAction}],
      projectionFingerprint
    }'
```

审阅时至少确认：

- `sourceGraphRef.id` 为 `built-in:loanDecisionPolicy`；
- `packageDraft.packageId` 为 `legacy:loanDecisionPolicy`；
- `packageDraft.provenance.sourceType` 为 `INFERRED`；
- `approvedBy` 为空；
- `businessDefinition` 未被技术拓扑自动补成业务事实；
- Contract Test Suite 只出现在 `discoveredTestSuiteRefs` 和 provenance，不出现在 `scenarioPackRefs`。

### 5.3 导入 Package revision 1

```bash
curl -i -sS -X POST "${AUTH[@]}" \
  -H 'Idempotency-Key: demo:legacy:loanDecisionPolicy:import:v1' \
  http://localhost:8080/api/business-mirror/legacy-graphs/loanDecisionPolicy/packages
```

预期结果：

- HTTP `201`；
- `Idempotent-Replayed: false`；
- `Legacy-Projection-Fingerprint` 与 preview 完全一致；
- `Location: /api/business-mirror/packages/legacy:loanDecisionPolicy`；
- 响应 `result.draft.revision` 为 `1`；
- 保存回执与普通 Package create 使用同一严格协议。

原样重试同一命令。响应应为 `Idempotent-Replayed: true`，并逐字段重放首次回执。不得在响应未知时自动更换 key，否则会把一次未知提交变成第二个业务命令。

### 5.4 读取并编译

```bash
curl -fsS "${AUTH[@]}" \
  http://localhost:8080/api/business-mirror/packages/legacy:loanDecisionPolicy \
  | jq '{revision: .draft.revision, lifecycle: .draft.lifecycle,
         blockers: .draft.businessDefinition, provenance: .draft.provenance}'

curl -fsS -X POST "${AUTH[@]}" \
  -H 'Idempotency-Key: demo:legacy:loanDecisionPolicy:compile:r1' \
  'http://localhost:8080/api/business-mirror/packages/legacy:loanDecisionPolicy/compile?sourceRevision=1' \
  | jq '{compilationRevision, status: .readiness.status,
         findings: [.readiness.findings[] | {code, fieldPath}], snapshot}'
```

预期编译状态仍为 `BLOCKED`，`snapshot` 为 `null`。Graph 与 Contract 的 exact resolution 可以成功，但它不能消除业务定义、Scenario、L1-L3、Fidelity、Outcome、State 和 Effect 的阻断项。

下一步应读取 current Package，逐项补齐经 Owner 审阅的 exact refs，再按 [Package Authoring 指南](resource-gateway-business-mirror-package-authoring-guide.md) 保存新 revision。不要修改存量 Graph 来承载这些业务字段。

## 6. API 与错误恢复

| 方法与路径 | 用途 | 权限 |
|---|---|---|
| `GET /api/business-mirror/legacy-graphs` | 查看同 Scope 的完整迁移目录 | Package read |
| `GET /api/business-mirror/legacy-graphs/{graphName}` | 生成无副作用 preview | Package read |
| `POST /api/business-mirror/legacy-graphs/{graphName}/packages` | 幂等创建 revision `1` | Package write |

| 错误码 | HTTP | 恢复动作 |
|---|---:|---|
| `RG.BUSINESS_MIRROR.LEGACY_GRAPH_NOT_FOUND` | `404` | 从 catalog 取得精确、区分大小写的 graph name |
| `RG.BUSINESS_MIRROR.LEGACY_PROJECTION_UNAVAILABLE` | `503` | 检查 source authority、Resource/Operator/Contract 完整性和 capability probe |
| `RG.BUSINESS_MIRROR.IDEMPOTENCY_KEY_INVALID` | `400` | 修正 key 格式后重新提交 |
| `RG.BUSINESS_MIRROR.IDEMPOTENCY_CONFLICT` | `409` | 停止换 key；检查同 key 是否被不同 actor、Scope 或命令复用 |
| `RG.BUSINESS_MIRROR.PACKAGE_REVISION_CONFLICT` | `409` | 目标 Package 已存在或更新；读取 current 并转入普通 authoring 流程 |

所有错误遵循统一 `IntegrationProblem`，不回显业务 Payload。未知 Graph 与 authority 不可用使用不同状态，便于调用方区分输入错误和部署故障。

## 7. 离线验证

Test Kit JAR 打包两个 strict Schema 和固定 loan-decision fixture。消费者无需启动 Resource Gateway：

```java
JsonNode projection = objectMapper.readTree(projectionJson);
BusinessMirrorLegacyMigrationVerifier.VerifiedProjection verified =
        BusinessMirrorLegacyMigrationVerifier.verifyProjection(projection);

JsonNode catalog = objectMapper.readTree(catalogJson);
BusinessMirrorLegacyMigrationVerifier.VerifiedCatalog verifiedCatalog =
        BusinessMirrorLegacyMigrationVerifier.verifyCatalog(catalog);
```

也可以只依赖稳定公共门面：

```java
BusinessMirrorProtocol.requireLegacyGraphPackageProjection(projection);
BusinessMirrorProtocol.requireLegacyGraphPackageProjectionCatalog(catalog);
```

验证器会独立检查 Schema、Scope、Graph/Contract/Capability/Closure 绑定、provenance source closure、Package readiness gap 等价性、迁移 policy gap、排序、状态派生和 canonical fingerprint。失败只返回稳定 reason code，不包含 Package 内容。

固定兼容性向量位于：

```text
docs/schemas/resource-gateway-business-mirror/
loan-decision-legacy-graph-projection-v1.fixture.json
```

该 fixture 还约束 Resource Descriptor 中混合类型 success set 的跨 JVM 稳定编码。响应协议会在值对象边界规范化 Set 顺序，避免相同 source facts 在不同进程生成不同 capability fingerprint。

## 8. 生产边界

当前实现完成了仓库内存量迁移协议和七个内置 Graph 的 vertical slice，尚未认证以下能力：

- 客户持久化 Visual Graph publication 的迁移 Authority；
- 海量 Graph 的异步扫描、分页、限流、取消和失败恢复；
- 跨版本迁移 dry-run、批次审批和回滚编排；
- 客户 Registry、KMS、HA/DR、备份恢复和组织四权分离；
- Scenario/Fidelity/Outcome 与 L0-L3 业务资产的自动关联建议。

在这些能力完成前，迁移应按 Package 小批次执行：preview、离线验真、Owner 审阅、幂等导入、补齐业务事实、编译、验证。禁止把 catalog 批量导入成功率当作业务能力迁移完成率。

## 9. 停止与验证

停止演示服务：

```bash
./scripts/stop-visual-canvas-demo.sh
```

聚焦门禁：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=CapabilityProjectionServiceTest,BusinessMirrorCapabilityTest,\
ResourceGatewayApplicationTest,BusinessMirrorPackageSpringWiringTest,\
ToolStudioIntegrationServiceTest test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=BusinessMirrorProtocolTest test
```

完整门禁：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

