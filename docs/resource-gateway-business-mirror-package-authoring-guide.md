# Resource Gateway Business Mirror Package Authoring 使用与运维指南

> 适用版本：`bloge.domainCapabilityPackageDraft.v1`
>
> 实现状态：BM-002 durable authoring vertical slice 已可用
>
> 目标读者：Package 作者、集成开发者、平台实施人员和运维人员

## 1. 能力边界

本接口用于创建、修改、查询和编译 `DomainCapabilityPackageDraft`。作者态保存与编译事实使用不同事务协议；Proposal 模拟、ANEKE 发布门禁和生产运行仍不在本轮范围内。

当前已提供：

- 经过认证的 create、save、current read、revision read、history 和 list API；
- `tenantId + organizationId + projectId + environmentId + region` 完整 Scope 隔离；
- optimistic revision 和稳定的 `409` 冲突语义；
- 必填 `Idempotency-Key`、跨进程数据库锁和重启后 exact receipt replay；
- current projection 与不可变 revision history 同事务提交；
- draft fingerprint、receipt fingerprint 和数据库列/JSON 一致性校验；
- H2 本地运行和 PostgreSQL 14 部署 DDL 认证；
- 独立 Test Kit 对保存回执、列表页和固定样例的离线 JSON Schema 校验。
- exact source revision 的幂等编译 API，以及 Readiness、Link Closure、可选 Snapshot 的原子 append-only 保存；
- 不同幂等键并发编译同一 Package 时的跨副本 revision 串行分配；
- compile receipt 的严格 Schema、canonical fact 复验和 exact revision read。

当前尚未提供：

- Business Mirror Workspace 可视化编辑界面；
- Proposal simulation、实现交付和 Package 级测试运行；
- ANEKE registry、publish gate 或治理状态写入。

能力探针中 `businessMirrorPackageApi=true` 表示 durable authoring API 已装配，`businessMirrorPackageCompilerApi=true` 表示编译事务和 API 已装配。默认部署的 `businessMirrorPackageCompilerAuthorityReady=true` 表示组合 Authority 已安装，并能解析内置 `GRAPH_DRAFT` 与 `CONTRACT`；它不表示任意 Package 的 Scenario、Fidelity、Outcome 等全部依赖已经接通。unsupported kind 仍会失败关闭。

## 2. 启动演示服务

### 2.1 配置固定样例的认证 Scope

取消费固定样例属于以下 Scope：

```text
tenantId       = ride-hailing
organizationId = customer-service
projectId      = cancellation
environmentId  = test
region         = sg
```

从仓库根目录执行：

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

预期结果：服务监听 `http://localhost:8080`。以下请求使用演示凭据 `bloge-aneke-demo-token`。生产环境必须关闭 demo identity，并配置签名 JWT 或自定义可信身份解析器。

### 2.2 确认能力探针

```bash
curl -fsS http://localhost:8080/api/integration/capabilities \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: BUSINESS_MIRROR_AUTHORING' \
  | jq '.payload |
        {businessMirrorPackageApi: .features.businessMirrorPackageApi,
         businessMirrorPackageCompilerApi: .features.businessMirrorPackageCompilerApi,
         businessMirrorPackageCompilerAuthorityReady: .features.businessMirrorPackageCompilerAuthorityReady,
         supportedObjects: .supportedObjects}'
```

判断条件：`businessMirrorPackageApi` 为 `true`，且 `supportedObjects` 包含：

- `storedDomainCapabilityPackageDraft`
- `domainCapabilityPackageSaveReceipt`
- `domainCapabilityPackagePage`
- `packageCompilationReceipt`
- `businessAssetLinkClosure`

## 3. 完成一次作者态闭环

### 3.1 生成 create 请求

仓库中的业务样例已经是 revision `1` 的固定协议 fixture。create API 要求请求 revision 为 `0`，服务端成功保存后生成 revision `1`：

```bash
jq '.revision = 0' \
  docs/schemas/resource-gateway-business-mirror/cancellation-fee-package-stage1-v1.fixture.json \
  > /tmp/cancellation-fee-package-create.json
```

不要修改请求中的 Scope。Scope 必须与可信身份中的五段 Scope 完全一致。

### 3.2 创建 Package

```bash
curl -i -sS -X POST \
  http://localhost:8080/api/business-mirror/packages \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: BUSINESS_MIRROR_AUTHORING' \
  -H 'Idempotency-Key: demo:cancellation-fee:create:v1' \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/cancellation-fee-package-create.json
```

预期结果：

- HTTP 状态为 `201`；
- `Idempotent-Replayed: false`；
- `ETag` 等于保存后 draft fingerprint；
- 响应 `result.draft.revision` 为 `1`；
- 响应是完整 `resourceGateway.domainCapabilityPackageSaveReceipt.v1`，不是临时确认消息。

### 3.3 模拟响应丢失后的重试

原样再次执行第 3.2 节命令。请求体、身份、Scope、actor 和 `Idempotency-Key` 必须保持不变。

预期结果：

- HTTP 状态仍为 `201`；
- `Idempotent-Replayed: true`；
- 响应 JSON 与首次响应逐字段相同，包括 `completedAt`；
- 数据库中不会增加第二个 Package revision。

同一个 `Idempotency-Key` 携带不同命令内容时，服务返回 `RG.BUSINESS_MIRROR.IDEMPOTENCY_CONFLICT`。不要通过自动更换 key 掩盖未知提交结果；先使用原 key 重放并取得权威回执。

### 3.4 查询 current、history 和列表

```bash
AUTH=(-H 'Authorization: Bearer bloge-aneke-demo-token' \
      -H 'X-Purpose: BUSINESS_MIRROR_AUTHORING')

curl -fsS "${AUTH[@]}" \
  http://localhost:8080/api/business-mirror/packages/cancellation-fee-resolution | jq

curl -fsS "${AUTH[@]}" \
  http://localhost:8080/api/business-mirror/packages/cancellation-fee-resolution/revisions | jq

curl -fsS "${AUTH[@]}" \
  'http://localhost:8080/api/business-mirror/packages?limit=25' | jq
```

列表采用同一 Scope 内的 `packageId` keyset cursor。`limit` 允许 `1-200`。响应中的非空 `nextCursor` 应作为下一页的 `after` 参数，不要自行构造 offset。

### 3.5 保存下一 revision

先从 current read 取出 draft，再修改业务内容。请求体 revision 和 `expectedRevision` 必须同时等于当前 revision：

```bash
curl -fsS "${AUTH[@]}" \
  http://localhost:8080/api/business-mirror/packages/cancellation-fee-resolution \
  | jq '.draft | .assumptions += ["Second authoring revision"]' \
  > /tmp/cancellation-fee-package-save.json

curl -i -sS -X PUT \
  'http://localhost:8080/api/business-mirror/packages/cancellation-fee-resolution?expectedRevision=1' \
  "${AUTH[@]}" \
  -H 'Idempotency-Key: demo:cancellation-fee:save:v2' \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/cancellation-fee-package-save.json
```

预期结果：响应 `result.draft.revision` 为 `2`，history 同时保留 revision `1` 和 `2`。

如果其他作者已先保存，接口返回 retryable `RG.BUSINESS_MIRROR.PACKAGE_REVISION_CONFLICT`，并在 `details.currentRevision` 中给出当前 revision。客户端必须重新读取、合并并使用新 key 提交新命令，不得覆盖服务器 current revision。

### 3.6 编译 exact revision

```bash
curl -i -sS -X POST \
  'http://localhost:8080/api/business-mirror/packages/cancellation-fee-resolution/compile?sourceRevision=1' \
  "${AUTH[@]}" \
  -H 'Idempotency-Key: demo:cancellation-fee:compile:r1'
```

内置演示部署没有客户 Registry Authority，因此预期结果是：

- HTTP `201`、`Idempotent-Replayed: false`；
- `Compilation-Status: BLOCKED`；
- `compilationRevision: 1`；
- `readiness.findings` 精确列出无法解析或尚未接入的业务依赖；
- `businessAssetLinkClosure` 被持久化；
- `snapshot` 为 `null`，不会伪造可发布 Package。

原样重试会返回 `Idempotent-Replayed: true` 和逐字段相同的 receipt。随后可读取 exact 事实：

```bash
curl -fsS "${AUTH[@]}" \
  http://localhost:8080/api/business-mirror/packages/cancellation-fee-resolution/compilations/1 \
  | jq '{compilationRevision, authorityGeneration, status: .readiness.status,
         findings: [.readiness.findings[] | {code, fieldPath}]}'
```

默认组合 Authority 已能物化七个内置 Graph 与 Contract，因此探针返回 `businessMirrorPackageCompilerAuthorityReady=true`。只有某个 Package 的全部 exact refs 都有唯一 Authority Owner、都被成功物化并证明对应 assurance 时，该次编译才可能生成非空 `snapshot`；探针就绪不能替代 Package 级 Readiness。

## 4. API 参考

| 方法与路径 | 用途 | 关键约束 |
|---|---|---|
| `POST /api/business-mirror/packages` | 创建 Package | body revision 必须为 `0`；`Idempotency-Key` 必填 |
| `PUT /api/business-mirror/packages/{packageId}?expectedRevision={n}` | 保存下一 revision | path、body id 和 expected revision 必须一致 |
| `GET /api/business-mirror/packages/{packageId}` | 读取 current | 只读取认证 Scope |
| `GET /api/business-mirror/packages/{packageId}/revisions` | 读取全部 history | revision 按降序返回 |
| `GET /api/business-mirror/packages/{packageId}/revisions/{revision}` | 读取 exact revision | revision 必须大于 `0` |
| `GET /api/business-mirror/packages?after={packageId}&limit={1-200}` | 列出 current projections | keyset pagination；默认 limit 为 `50` |
| `POST /api/business-mirror/packages/{packageId}/compile?sourceRevision={n}` | 编译 exact authoring revision | `Idempotency-Key` 必填；结果原子追加 |
| `GET /api/business-mirror/packages/{packageId}/compilations/{revision}` | 读取 exact compile receipt | 返回 Readiness、Closure 和可选 Snapshot |

所有端点都要求：

- `Authorization: Bearer <credential>`；
- `X-Purpose: BUSINESS_MIRROR_AUTHORING`；
- 写请求还要求 `Idempotency-Key`，长度为 `1-160`，字符集为 `[A-Za-z0-9._:/-]`，首字符必须是字母或数字。

### 4.1 稳定错误与恢复动作

| 错误码 | HTTP | retryable | 含义与恢复动作 |
|---|---:|---:|---|
| `RG.BUSINESS_MIRROR.IDEMPOTENCY_KEY_INVALID` | `400` | 否 | key 缺失或格式错误；修正后提交 |
| `RG.BUSINESS_MIRROR.PACKAGE_IDENTITY_INVALID` | `400` | 否 | path、body 或 revision 不一致；重新读取 current 后构造请求 |
| `RG.BUSINESS_MIRROR.PACKAGE_OPERATION_INVALID` | `400` | 否 | create/save revision 语义错误；create 用 `0`，save 用正数 |
| `RG.BUSINESS_MIRROR.PACKAGE_RAW_SECRET_FORBIDDEN` | `400` | 否 | draft 含原始 Secret；改为受治理的 Secret 引用 |
| `RG.BUSINESS_MIRROR.PACKAGE_SCOPE_MISMATCH` | `403` | 否 | body Scope 与可信身份不一致；不要用请求头伪造 Scope |
| `RG.BUSINESS_MIRROR.PACKAGE_NOT_FOUND` | `404` | 否 | 当前认证 Scope 中不存在目标 Package/revision |
| `RG.BUSINESS_MIRROR.IDEMPOTENCY_CONFLICT` | `409` | 否 | 同 key 已绑定不同命令；停止自动重试并调查调用方 key 管理 |
| `RG.BUSINESS_MIRROR.PACKAGE_REVISION_CONFLICT` | `409` | 是 | current 已变化；重新读取、合并并使用新 key 保存 |
| `RG.BUSINESS_MIRROR.COMPILATION_IDEMPOTENCY_KEY_INVALID` | `400` | 否 | compile key 缺失或格式错误；修正后提交 |
| `RG.BUSINESS_MIRROR.COMPILATION_IDEMPOTENCY_CONFLICT` | `409` | 否 | 同 key 绑定了不同 source revision 或 actor；停止自动换 key |
| `RG.PACKAGE.DEPENDENCY_DRIFT` | `409` | 是 | Authority 在编译窗口内变化；重新冻结依赖后重试 |
| `RG.BUSINESS_MIRROR.COMPILATION_NOT_FOUND` | `404` | 否 | 当前 Scope 不存在该 compilation revision |

## 5. 协议校验

跨语言权威 Schema 位于：

```text
docs/schemas/resource-gateway-business-mirror/
```

BM-002 新增的可消费根对象为：

- `stored-domain-capability-package-draft-v1.schema.json`
- `domain-capability-package-save-receipt-v1.schema.json`
- `domain-capability-package-page-v1.schema.json`

Test Kit 消费者可离线校验，不需要启动 Resource Gateway：

```java
JsonNode receipt = objectMapper.readTree(receiptJson);
BusinessMirrorProtocol.requirePackageSaveReceipt(receipt);

JsonNode page = objectMapper.readTree(pageJson);
BusinessMirrorProtocol.requirePackagePage(page);

JsonNode compilation = objectMapper.readTree(compilationReceiptJson);
BusinessMirrorProtocol.requirePackageCompilationReceipt(compilation);
```

独立校验器会复算 stored draft 的 canonical fingerprint，并拒绝未知字段、内容篡改、时间倒序、跨 Scope page、非递增 package id、重复项和游离 cursor。保存命令的 `requestFingerprint` 由服务端绑定完整命令材料；只持有回执而没有原始命令的消费者只能校验格式，不能独立复算该字段。固定回执样例为 `cancellation-fee-package-save-receipt-v1.fixture.json`。

## 6. PostgreSQL 部署

在生产或共享测试环境启用 API 前，使用企业迁移工具执行：

```text
resource-gateway-examples/src/main/resources/db/postgresql/
V20260814_001__business_mirror_package_authoring.sql
V20260814_002__business_mirror_package_compilation.sql
```

第二个迁移增加 Package revision allocator/lock、compile command lock/receipt、compilation index，以及 Readiness、Link Closure、Snapshot 三类 append-only fact 表。完整 Scope 是每张表主键的一部分。

运行时 repository 的自动建表只用于示例和本地启动，不替代企业 migration gate。生产变更流程至少要保存迁移执行证据、备份/恢复证据和回滚决策。BM-013 才会补齐 PostgreSQL HA、网络分区、滚动升级和备份恢复认证包；当前 PostgreSQL 证据只覆盖真实 DDL、`fsync=on`、`synchronous_commit=on` 和两独立连接并发。

## 7. 停止服务

```bash
./scripts/stop-visual-canvas-demo.sh
```

脚本停止由 demo launcher 管理的进程。默认本地 H2 数据不构成持久生产存储；需要验证重启重放时，应配置持久数据库并保留相同的 Scope、身份和 receipt 表。

## 8. 验证命令

聚焦验证：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseDomainCapabilityPackageAuthoringTest,\
DatabaseDomainCapabilityPackagePostgresCertificationTest,\
DatabasePackageCompilationTest,PackageCompilationControllerTest,\
DomainCapabilityPackageControllerTest,\
BusinessMirrorPackageSpringWiringTest,\
BusinessMirrorCapabilityTest test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=BusinessMirrorProtocolTest test
```

完整门禁：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

测试覆盖 exact restart replay、同 key 漂移、完整 Scope 隔离、optimistic conflict、事务回滚、H2 双实例并发、原生 PostgreSQL 双实例并发、compile revision 分配、READY/BLOCKED fact 持久化、receipt 防篡改、认证 HTTP 和独立 Schema 消费。
