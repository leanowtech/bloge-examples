# Resource Gateway Package Evidence 与 Fidelity 使用说明

> 适用实现：`resourceGateway.packageEvidenceIndex.v1`、`resourceGateway.evidenceOwnerTask.v1`、`resourceGateway.domainEvidencePortfolio.v1`
>
> 对应工作包：RG-BM-010
> 边界：Resource Gateway 生产、展示和导出证据；ANEKE 仍负责 Registry、正确性工作簿、发布门禁和治理裁决。

## 1. 这项能力解决什么问题

一个 Package 能编译，不等于它已经证明了客户业务保真度。工程上至少要分别回答：

1. L0 Resource、Operator、Graph 和 Contract 是否有精确来源。
2. L1 Scenario、Solution 是否已成为受治理的服务设计资产。
3. L2 SOP、Workflow、Agent 等服务载体是否有证据。
4. L3 业务应用或渠道是否有证据。
5. Fidelity 分母、七个维度、来源构成、弃权债务和独立 Outcome 是否仍然有效。

Package Evidence 投影把这些问题固化为五层不可互相替代的结论。系统禁止输出
`overallScore`、`totalScore`、`maturityScore` 或 `overallPass`。原因是单一分数会掩盖分母不足、
置信区间过宽、全部依赖合成数据或关键维度弃权等本质差异。

## 2. 端到端工作流

```text
Package draft
  -> PackageCompiler 冻结依赖并生成成功 Snapshot
  -> 在同一事务写入 immutable compilation facts 与 evidence outbox
  -> DB-time lease worker 领取一个精确 compilation coordinate
  -> 读取 Package Snapshot、Readiness、BusinessAssetLinkClosure
  -> 解析 Package 绑定的 FidelityInventory 与该业务域最新签名 FidelityProfile
  -> 生成五层 PackageEvidenceIndex
  -> 原子更新 current head 并协调 Owner Task
  -> 生成 DomainEvidencePortfolio 与 payload-free Change Event
  -> UI、ANEKE 或 Test Kit 按 exact fingerprint 消费
```

只有带非空 `DomainCapabilityPackageSnapshot` 的成功编译才进入 evidence outbox。`BLOCKED`
编译仍有 durable Readiness receipt，但不会伪造 Evidence Index。页面出现“尚未生成证据投影”时，
应先补齐 Readiness 输入，而不是重复刷新一个从未存在的索引。

## 3. 三类协议对象

| 对象 | 作用 | 关键不变量 |
|---|---|---|
| `PackageEvidenceIndex` | 一个 Package compilation 的完整证据切面 | append-only；独立 projection revision；五层顺序固定；每个结论都有 exact subject 与 lineage；Fidelity 保留七维向量；无总分 |
| `EvidenceOwnerTask` | 把漂移或证据债务分配给明确 Owner | optimistic version；`OPEN -> ACKNOWLEDGED -> RESOLVED`；关闭必须提供 exact resolution evidence ref；旧任务可 `SUPERSEDED` |
| `DomainEvidencePortfolio` | 有界聚合一个业务域当前 Package heads | Package 按 id 稳定排序；每层 state/proof 计数闭合；只包含活跃任务；cursor 不猜测总量 |

严格 Schema 与固定样例：

- [Package Evidence Index Schema](schemas/resource-gateway-business-mirror/package-evidence-index-v1.schema.json)
- [Evidence Owner Task Schema](schemas/resource-gateway-business-mirror/evidence-owner-task-v1.schema.json)
- [Domain Evidence Portfolio Schema](schemas/resource-gateway-business-mirror/domain-evidence-portfolio-v1.schema.json)
- [完整五层、七维固定样例](schemas/resource-gateway-business-mirror/package-evidence-index-stage1-v1.fixture.json)
- [缺失 Fidelity Profile 与活跃任务固定样例](schemas/resource-gateway-business-mirror/domain-evidence-portfolio-stage1-v1.fixture.json)

## 4. 五层结论如何解释

| 层 | 典型对象 | 不能由什么替代 |
|---|---|---|
| `L0_RESOURCE` | Contract、Graph、Operator、Resource、State/Effect、执行证据 | L0 测试通过不能证明服务设计正确 |
| `L1_SERVICE_DESIGN` | Scenario、Solution、业务特征与问题解决设计 | 技术 Test Suite 数量不能冒充 Owner 批准的 Scenario 分母 |
| `L2_SERVICE_CARRIER` | SOP、Workflow、Agent、知识服务载体 | Graph 存在不能证明实际服务载体已绑定 |
| `L3_APPLICATION` | 文本机器人、语音机器人和渠道应用 | Carrier 存在不能证明渠道交付已完成 |
| `CALIBRATION` | FidelityInventory、签名 FidelityProfile、独立 Outcome | 自报执行结果不能冒充独立 Outcome |

每个 `EvidenceConclusion` 同时保留：`evidenceKind`、`proofStrength`、`state`、精确
`subject`、`sourceLineage`、有效期和 limitation code。Portfolio 只做计数投影，不改变结论强度。

## 5. Fidelity 七维模型

当前 Profile 必须按固定顺序提供七个维度：

1. `BEHAVIOR`
2. `CONTRACT`
3. `EFFECT`
4. `ERROR_DISTRIBUTION`
5. `OUTCOME`
6. `REQUEST_SPACE`
7. `STATE_TRANSITION`

每个维度独立展示 `requiredUnits`、fresh/passed/failed/abstained/stale/missing 数量、覆盖率、
弃权率、Wilson 95% 置信区间和 sufficiency。Profile 缺失时，`dimensions` 必须为空且
`state=MISSING`；系统不能用零分或默认通过填补未知事实。

Profile 还保留：

- `abstentionDebt`：有多少应验证义务被明确弃权以及原因。
- `sourceComposition`：recorded、synthesized、owner-declared、authoritative、unknown 的构成。
- `assessment` 与 `limitations`：解释证据是否足够，不代表 ANEKE 发布决定。
- `sourceLineage`：Inventory、Profile、Package 和 Outcome 的精确内容地址。

## 6. 启动并在界面体验

从仓库根目录启动带 React 前端的演示服务：

```bash
./scripts/visual-canvas-demo.sh start --open
./scripts/visual-canvas-demo.sh status
```

打开 `http://localhost:8080/business-mirror/?lang=zh-CN`：

![Business Mirror 五层证据与七维 Fidelity 参考视图](assets/resource-gateway-business-mirror-evidence-zh.png)

1. 在 Portfolio 中打开任一能力包。
2. 选择左侧第 6 步“检查证据”。
3. 若该能力包已有成功编译投影，可查看五层结论、七维 Fidelity 和责任任务。
4. 若尚未生成投影，选择“打开参考证据”。该只读样例来自服务端生成、Test Kit 验真的固定 fixture；页面会明确提示它不是当前能力包证据。
5. 返回当前能力包后，导入 Package、补齐 Readiness 输入并执行“检查就绪度”。异步 worker 完成后选择“重新加载证据”。
6. 对 `OPEN` 任务选择“确认接手”；真正关闭仍需通过 API 提交 exact resolution evidence ref。

参考样例只用于理解协议，不写数据库、不触发发布门禁、不改变当前 Package。停止服务：

```bash
./scripts/visual-canvas-demo.sh stop
```

## 7. 能力探针

部署和客户端必须先读取能力探针，不得根据 classpath 或配置文本猜测 API 可用性：

```bash
curl -s http://localhost:8080/api/integration/capabilities \
  | jq '.payload | {
      objects: {
        packageEvidenceIndex: .supportedObjects.packageEvidenceIndex,
        evidenceOwnerTask: .supportedObjects.evidenceOwnerTask,
        domainEvidencePortfolio: .supportedObjects.domainEvidencePortfolio
      },
      evidence: {
        protocol: .features.businessMirrorPackageEvidenceProtocol,
        api: .features.businessMirrorPackageEvidenceApi,
        portfolio: .features.businessMirrorDomainEvidencePortfolioApi,
        tasks: .features.businessMirrorEvidenceOwnerTaskApi
      }
    }'
```

对象版本表示消费者可以理解协议；三个 API feature 只有在受保护的 `PackageEvidenceService`
真实装配后才为 `true`。feature 为 `false` 时，相应端点也不会出现在 capability endpoint 列表中。
服务和路由仅在 `test` 或 `staging` profile 且 `gateway.testing.mirror.enabled=true` 时物理装配；
`production` profile 即使误设开关也保持 fail-closed。

## 8. HTTP 操作

本地演示身份：

```bash
export RG_URL=http://localhost:8080
export RG_TOKEN=bloge-aneke-demo-token
export PACKAGE_ID=cancellation-package
export DOMAIN_ID=ride-cancellation
export RG_HEADERS=(
  -H "Authorization: Bearer $RG_TOKEN"
)
```

实际 Scope 以部署的 trusted identity registry 为准。调用方不能通过头字段扩权；服务端会把
credential 映射成可信 Scope。若要让本地固定身份匹配自己的数据，应在启动前设置
`RG_INTEGRATION_TENANT_ID`、`RG_INTEGRATION_ORGANIZATION_ID`、`RG_INTEGRATION_PROJECT_ID`、
`RG_INTEGRATION_ENVIRONMENT_ID` 和 `RG_INTEGRATION_REGION`。

### 8.1 读取当前 Package Evidence Index

```bash
curl -s "${RG_HEADERS[@]}" \
  -H 'X-Purpose: GOVERNANCE_EVIDENCE_INGESTION' \
  "$RG_URL/api/integration/domain-capability-packages/$PACKAGE_ID/evidence-index" \
  | jq '{packageId, compilationRevision, projectionRevision, indexFingerprint, fidelity: .fidelity.state}'
```

Authoring UI 使用 `/api/business-mirror/...` 同义读取路由和
`X-Purpose: BUSINESS_MIRROR_AUTHORING`。

### 8.2 读取业务域 Portfolio

```bash
curl -s "${RG_HEADERS[@]}" \
  -H 'X-Purpose: GOVERNANCE_EVIDENCE_INGESTION' \
  "$RG_URL/api/integration/domain-portfolios/$DOMAIN_ID?limit=100" \
  | jq '{domainId, packages: [.packages[] | {packageId, freshness, ownerTasks: (.ownerTasks | length)}], nextCursor}'
```

`nextCursor` 非空时，把它作为下一页 `afterPackageId`；不要用 offset，也不要把本页大小当总量。

### 8.3 从最新来源重新投影

```bash
curl -s -X POST "${RG_HEADERS[@]}" \
  -H 'X-Purpose: BUSINESS_MIRROR_MAINTENANCE' \
  "$RG_URL/api/business-mirror/domain-capability-packages/$PACKAGE_ID/evidence-index/refresh"
```

refresh 只能重投影已存在的成功 compilation，不是“绕过 Readiness 创建首个索引”的命令。

### 8.4 查询和确认 Owner Task

```bash
curl -s "${RG_HEADERS[@]}" \
  -H 'X-Purpose: BUSINESS_MIRROR_AUTHORING' \
  "$RG_URL/api/business-mirror/evidence-owner-tasks?domainId=$DOMAIN_ID&status=OPEN&limit=100"

curl -s -X POST "${RG_HEADERS[@]}" \
  -H 'X-Purpose: BUSINESS_MIRROR_AUTHORING' \
  "$RG_URL/api/business-mirror/evidence-owner-tasks/TASK_ID/acknowledge?expectedVersion=1"
```

### 8.5 使用 exact evidence 关闭任务

```bash
curl -s -X POST "${RG_HEADERS[@]}" \
  -H 'X-Purpose: BUSINESS_MIRROR_AUTHORING' \
  -H 'Content-Type: application/json' \
  "$RG_URL/api/business-mirror/evidence-owner-tasks/TASK_ID/resolve?expectedVersion=2" \
  -d '{
    "resolutionEvidenceRef": {
      "kind": "DOMAIN_FIDELITY_PROFILE",
      "id": "ride-cancellation",
      "revision": 3,
      "fingerprint": "sha256:REPLACE_WITH_EXACT_64_HEX"
    }
  }'
```

仅填写说明文本不能关闭任务。若新投影仍检测到同一债务，repository 会保持或重建活跃任务；
任务状态不是修改证据事实的后门。

## 9. 异步一致性与恢复

| 现象 | 根因 | 正确处理 |
|---|---|---|
| 编译成功后短暂 `404` | evidence outbox 尚未被 worker 消费 | 等待默认 `2s` 轮询周期后重读；不要立即重复编译 |
| 编译结果 `BLOCKED` 且一直 `404` | 没有成功 Snapshot，因此未准入 outbox | 按 Readiness finding 补齐权威依赖后重新编译 |
| Portfolio 仍显示旧 projection | Package Index 与 Portfolio 读取时刻不同 | 比较 compilation/projection revision；按 cursor 重读，不在客户端拼接新旧页 |
| task 更新返回 `409` | 其他作者已改变 task version | 重读任务，以最新 version 决定确认或关闭，不覆盖他人操作 |
| refresh 返回 `404` | 从未存在 current index，或 Scope 不匹配 | 检查 trusted identity Scope 和成功编译事实；refresh 不能创建首个成功事实 |
| outbox 多次失败 | Fidelity ref 不可解析、签名 Profile 不可信或 immutable fact 损坏 | 查看服务日志中的稳定 failure code；修复 Authority 后重投影；达到 8 次的 poison job 会隔离，不可静默跳过 |
| 多副本重复领取 | 不应发生；DB-time lease 与 epoch fence 只允许一个 winner | 立即检查 PostgreSQL 隔离与 migration；不要用本地内存锁替代数据库 fence |

worker 默认参数：lease `2 min`、最大尝试 `8`、每轮最多 `50`、调度间隔
`${gateway.business-mirror.evidence.worker-fixed-delay-ms:2000}`。改变参数前要用目标 PostgreSQL
拓扑做容量、kill 和时钟偏差认证；本地 H2 结果不能替代生产 HA 结论。

## 10. PostgreSQL 部署

正式 DDL 位于：

`resource-gateway-examples/src/main/resources/db/postgresql/V20260815_001__business_mirror_package_evidence.sql`

迁移包含：

- Package 级数据库锁与独立 projection sequence。
- append-only evidence indexes 与 current heads。
- durable outbox、状态、attempt、DB-time lease owner/epoch/expiry。
- Owner Task current row 与 append-only task journal。
- Domain current-head、ready-job 和 task 查询索引。

上线顺序：先迁移所有数据库，再发布能写新表的实例，最后开放 capability feature。回滚应用版本时
保留表和 append-only 数据；不得回滚或重用 projection revision。备份恢复后必须校验 head 指向的
index 存在、fingerprint 可复验、outbox lease 已过期或由新实例接管、task journal 版本连续。

## 11. 独立 Test Kit 验证

`resource-gateway-test-kit` 不依赖 Spring Boot 或 Resource Gateway 服务端。消费者可以在 CI、
ANEKE adapter 或客户资产仓库中离线拒绝格式正确但语义被篡改的证据。

```java
ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

JsonNode index = mapper.readTree(responseBody);
BusinessMirrorProtocol.requirePackageEvidenceIndex(index);

JsonNode task = mapper.readTree(taskBody);
BusinessMirrorProtocol.requireEvidenceOwnerTask(task);

JsonNode portfolio = mapper.readTree(portfolioBody);
BusinessMirrorProtocol.requireDomainEvidencePortfolio(portfolio);
```

校验不仅运行 JSON Schema，还会检查：canonical fingerprint、五层顺序、结论与 lineage 闭合、
七维顺序和 metric 维度一致性、时间顺序、signal 排序、task Deep Link、Portfolio 计数算术、
Scope/task closure、cursor，以及任何禁止的 aggregate score 字段。

验证仓库内固定样例：

```bash
mvn -f resource-gateway-test-kit/pom.xml -Dtest=BusinessMirrorProtocolTest test
```

## 12. 权限、隐私和审计边界

1. Integration read 允许 `GOVERNANCE_EVIDENCE_INGESTION`、`CHANGE_SYNC` 或业务镜像 authoring 用途。
2. refresh 只允许 `BUSINESS_MIRROR_MAINTENANCE`。
3. task mutation 只允许 `BUSINESS_MIRROR_AUTHORING`。
4. Index、Portfolio、task 与 Change Event 不包含客户 request/response payload；只包含 ref、统计、状态、时间和 fingerprint。
5. evidence deep link 只恢复定位上下文，不绕过认证和 Scope。
6. `ACKNOWLEDGED` 表示责任人接手，不等于证据充分；`RESOLVED` 必须指向可独立复验的 exact artifact。
7. Resource Gateway 的 Fidelity state 不是 ANEKE publish gate，任何客户端都不得把 `CURRENT` 直接映射为“允许发布”。

## 13. 发布前门禁

```bash
# 服务端协议、持久化、Controller、Spring 与 PostgreSQL 聚焦回归
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='PackageEvidence*Test,BusinessMirrorCapabilityTest,ResourceGatewayApplicationTest' test

# 前端 Evidence、i18n、类型和生产构建
cd resource-gateway-examples/src/main/frontend
npx vitest run src/business-mirror/BusinessMirrorWorkspace.test.tsx \
  src/i18n/messageCatalog.test.ts src/i18n/localeSurfaceInventory.test.ts
npx tsc --noEmit
npm run build

# 独立消费者完整门禁
cd ../../../..
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

最终发布仍应执行两个 Maven 项目的 `clean verify`。原生 PostgreSQL 聚焦测试证明当前 migration
和双连接 lease 竞争，不证明多可用区故障切换、备份恢复和滚动升级；这些属于 RG-BM-013。
