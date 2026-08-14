# Resource Gateway 客户业务能力镜像实施状态

> 状态：持续更新
>
> 蓝图：[客户业务能力镜像蓝图差距评估与技术演进方案](resource-gateway-customer-business-mirror-blueprint-gap-and-technical-evolution-plan.md)
>
> 当前迭代：BM-004 Legacy migration 已完成；下一迭代 BM-005 Business Mirror Workspace
>
> 最近更新：2026-08-14

## 1. 本文用途

本文记录蓝图工作包的实际交付、验证证据、剩余差距和架构偏差。蓝图第 1-4 节是开工前基线，不随代码进展反复改写；本文是当前事实来源。

判断遵循三条规则：

1. Java 类型存在，不等于跨系统协议完成。
2. Schema 和能力探针存在，不等于 repository、API、UI 或运行能力可用。
3. 本地测试通过，不等于复杂企业生产环境已经认证。

## 2. Iteration 1：BM-001 协议内核

### 2.1 已交付

| 交付 | 代码或协议 | 已固化的关键约束 |
|---|---|---|
| L0-L3 业务资产引用 | `BusinessAssetRef` | 精确 revision/fingerprint、authority、完整企业 Scope、layer/kind 合法组合 |
| 业务资产关系 | `BusinessAssetLink` | 业务关系独立于 Graph edge；拒绝自环、跨 Scope 和 provenance tenant 漂移 |
| Package 作者态 | `DomainCapabilityPackageDraft` | 客户问题、Owner、风险、Contract、Capability/Graph、Scenario、Solution、Carrier、Channel、Fidelity 和 Outcome exact refs |
| Package 编译事实 | `DomainCapabilityPackageSnapshot` | source draft fingerprint、immutable dependency manifest、compiler/policy generation、内容寻址 |
| Package 就绪报告 | `PackageReadinessReport` | 状态由 Finding 推导，作者不能把 WARNING/ERROR 强行标成 READY |
| Proposal 作者态 | `CapabilityProposalDraft` | 价值假设、候选 Contract、Fixture、业务验收套件、到期时间和 `SIMULATION_ONLY` binding |
| Proposal 证据事实 | `CapabilityProposalSnapshot` | `NOT_RUN → SIMULATED → IMPLEMENTED → CONFORMANT → CALIBRATED` 由 exact evidence 单调推进 |
| 严格跨语言协议 | `docs/schemas/resource-gateway-business-mirror` | 六个根 Schema、公共类型闭包、`additionalProperties: false`、类型/长度/数量/版本门禁 |
| 独立消费者 | `BusinessMirrorProtocol` | Test Kit JAR 内离线加载完整 Schema 闭包；错误只返回稳定 reason code，不泄露 payload |
| 固定业务样例 | 两份 cancellation-fee fixtures | 完整取消费业务包、未实施能力的 simulation-only Proposal |
| 能力发现 | `/api/integration/capabilities` | 六类对象和 `businessMirrorProtocol=true`；未实现 API/Simulation 继续返回 `false` |

### 2.2 关键不变量

| 风险 | 根治约束 | 失败方式 |
|---|---|---|
| Graph 再次成为万能业务根 | Package 只以 exact ref 组合 Graph，业务定义与 L0-L3 关系独立建模 | Package 协议中不复制 Graph 内容 |
| 作者伪造治理或正确性状态 | 作者态与不可变 Snapshot 分离；Readiness 和 Evidence 状态派生 | 状态与 Finding/Evidence 不一致时拒绝构造和 Schema 校验 |
| 候选能力误连生产 | Proposal binding 只有 `SIMULATION_ONLY`；真实调用、外部凭据和网络出口固定为 `false` | Java 与 JSON Schema 双重失败关闭 |
| 多组织引用串域 | Business Asset Link 和 Package asset refs 使用完整 Scope | V1 直接拒绝跨 Scope；未来如需跨域必须新增可验证 delegation proof |
| revision 存在但内容漂移 | 所有不可变事实和依赖引用同时携带 revision 与 canonical SHA-256 | 指纹复算不一致时拒绝 |
| ANEKE 与 Resource Gateway 双主 | Snapshot 不承载 ANEKE publish/gate 状态 | ANEKE 状态只能通过后续 Governance Projection 回显 |

### 2.3 自动化验证

| 范围 | 用例数 | 结果 | 证明内容 |
|---|---:|---|---|
| 服务端领域协议 | 18 | 通过 | 生命周期晋级、高风险门禁、Scope、模拟隔离、证据单调性、内容指纹防篡改 |
| 能力探针 | 1 | 通过 | 对象版本可发现，未实现能力不误报为可用 |
| 独立 Test Kit | 8 | 通过 | 六个根 Schema、两份固定 fixture、未知字段、层级漂移、状态矛盾和网络越权拒绝 |

已执行窄测试：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=BusinessMirrorCapabilityTest,BusinessAssetProtocolTest,DomainCapabilityPackageProtocolTest,CapabilityProposalProtocolTest test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=BusinessMirrorProtocolTest test
```

完整项目门禁：

| 项目 | 命令 | 结果 |
|---|---|---|
| Resource Gateway | `mvn -f resource-gateway-examples/pom.xml clean verify` | `5926` tests，`0` failures，`0` errors，`13` skipped；包含真实浏览器 E2E；`BUILD SUCCESS` |
| Resource Gateway Test Kit | `mvn -f resource-gateway-test-kit/pom.xml clean verify` | `534` tests，`0` failures，`0` errors，`0` skipped；JAR、shade 与 Javadoc 门禁通过；`BUILD SUCCESS` |

BM-001 的仓库内工程门禁已完成。四方 Owner 对协议治理规则的组织签署不属于本地代码可以替代的证据，仍保留为 BM-001 的外部验收项。

## 3. Iteration 2：BM-002 Package durable authoring

### 3.1 已交付

| 交付 | 实现 | 已固化的关键约束 |
|---|---|---|
| Package command/query port | `DomainCapabilityPackageAuthoringService`、repository interfaces | HTTP transport、事务编排和 JDBC 存储分层；认证 Scope 只能由 `IntegrationRequestContext` 提供 |
| Current 与 immutable history | `DatabaseDomainCapabilityPackageDraftRepository` | 五段 Scope + `packageId` 主键；current 和 history 同事务；每次保存重算 canonical fingerprint |
| Optimistic save | `saveIfRevision`、`expectedRevision` | create 只接受 revision `0`；save 要求 path/body/expected revision 一致；冲突不覆盖 current |
| Durable idempotency | lock/receipt repository、`DomainCapabilityPackageSaveCoordinator` | key 绑定 canonical command fingerprint；数据库串行化跨线程/跨副本；重启后返回 exact 原回执 |
| 认证 Authoring API | `/api/business-mirror/packages` | create/save/current/exact revision/history/keyset list；独立 read/write operation；统一 problem contract |
| PostgreSQL migration | `V20260814_001__business_mirror_package_authoring.sql` | 四张表、完整 Scope 主键、fingerprint check、history/receipt 索引 |
| 严格 authoring envelope | 三份 JSON Schema、固定 save receipt fixture | stored draft、save receipt、page 均 `additionalProperties: false`；Test Kit 可离线校验 |
| 能力发现 | `/api/integration/capabilities` | 新增三类 authoring 对象；运行装配与 PostgreSQL 认证通过后才声明 `businessMirrorPackageApi=true` |
| 使用与运维指南 | `resource-gateway-business-mirror-package-authoring-guide.md` | 固定 Scope 演示、create/save/read/exact replay、错误恢复、迁移和停止服务步骤 |

### 3.2 事务与并发语义

一次写请求的原子边界为：

```text
authenticate
  -> validate command and trusted Scope
  -> acquire (Scope, Idempotency-Key) database row lock
  -> find exact prior receipt or reject key drift
  -> compare current revision
  -> write current + immutable history
  -> write exact receipt
  -> commit once
```

PostgreSQL 锁行使用 `INSERT ... ON CONFLICT DO NOTHING` 后 `SELECT ... FOR UPDATE`。实现没有通过捕获 unique violation 建锁，因为 PostgreSQL 的唯一键异常会中止当前事务。H2 使用等价的 `MERGE` 初始化锁行。该差异被封装在 receipt repository，并由两套数据库测试覆盖。

保存回执同时存储结构化 JSON、request fingerprint 和 completion timestamp。读取时重新校验三者以及回执内部 Scope；数据库列和 JSON 发生漂移时失败关闭。时间戳统一截断到微秒，避免 JDBC/PostgreSQL 精度差异破坏 exact replay。

### 3.3 自动化验证

| 测试层 | 用例数 | 证明内容 |
|---|---:|---|
| H2 repository/service | 10 | 响应丢失 + 重启重放、key 漂移、history/page、Scope 隔离、Scope mismatch、缺 key、receipt tamper、revision conflict、事务回滚、双实例并发 |
| 原生 PostgreSQL 14 | 1 | 部署 DDL、`fsync=on`、`synchronous_commit=on`、两独立连接争抢同 key、单 revision/receipt |
| Controller | 2 | HTTP status/header、认证 context 传递和 problem contract |
| Spring HTTP/transaction wiring | 2 | AOP transactional proxy；真实认证、Jackson、Controller、DB、exact replay 和 list 完整链路 |
| 能力探针 | 1 | 三类对象和 package API readiness 可发现 |
| 独立 Test Kit | 12 | 九类根协议、三类 durable envelope、固定 fixtures、canonical fingerprint 复算、page Scope/order/cursor、未知字段和篡改拒绝 |

聚焦门禁共 `28` 个用例，全部通过：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DatabaseDomainCapabilityPackageAuthoringTest,\
DatabaseDomainCapabilityPackagePostgresCertificationTest,\
DomainCapabilityPackageControllerTest,\
BusinessMirrorPackageSpringWiringTest,\
BusinessMirrorCapabilityTest test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=BusinessMirrorProtocolTest test
```

完整项目门禁：

| 项目 | 命令 | 结果 |
|---|---|---|
| Resource Gateway | `mvn -f resource-gateway-examples/pom.xml clean verify` | `5941` tests，`0` failures，`0` errors，`13` skipped；包含原生 PostgreSQL 和真实浏览器 E2E；`BUILD SUCCESS` |
| Resource Gateway Test Kit | `mvn -f resource-gateway-test-kit/pom.xml clean verify` | `538` tests，`0` failures，`0` errors，`0` skipped；JAR、shade 与 Javadoc 门禁通过；`BUILD SUCCESS` |

聚焦测试中曾发现一处测试断言把 `result.draft.revision` 写成 `result.revision`；实现响应符合既定 Schema，修正断言后重跑全绿。该失败没有通过改变协议来掩盖。

完整构建仍报告两类既有工具链风险：BLOGE 发布 POM 中 `bloge-durable`、`bloge-test` 未给 `bloge-execution-control` 声明依赖版本；本机 Chrome `151` 高于 Selenium 精确支持的 CDP `149`。两者未造成当前门禁失败，但应分别进入上游发布元数据和浏览器工具链升级队列。

### 3.4 当前认证边界

BM-002 的 PostgreSQL 认证证明了 migration 可执行、durable commit 开启和两副本幂等串行化。它尚未证明：

- PostgreSQL HA failover 与长事务锁恢复；
- 网络分区、连接池耗尽和数据库磁盘满；
- 蓝绿升级和跨版本读写；
- PITR、备份恢复、RPO/RTO；
- 客户 KMS/Vault、mTLS 和企业迁移平台。

这些不是本轮遗漏后可以标成「已完成」的细节，而是 BM-012/BM-013 的独立生产认证责任。

## 4. 能力探针解释

当前探针应包含：

```json
{
  "supportedObjects": {
    "domainCapabilityPackageDraft": ["bloge.domainCapabilityPackageDraft.v1"],
    "domainCapabilityPackageSnapshot": ["resourceGateway.domainCapabilityPackageSnapshot.v1"],
    "capabilityProposalDraft": ["bloge.capabilityProposalDraft.v1"],
    "capabilityProposalSnapshot": ["resourceGateway.capabilityProposalSnapshot.v1"],
    "storedDomainCapabilityPackageDraft": ["resourceGateway.storedDomainCapabilityPackageDraft.v1"],
    "domainCapabilityPackageSaveReceipt": ["resourceGateway.domainCapabilityPackageSaveReceipt.v1"],
    "domainCapabilityPackagePage": ["resourceGateway.domainCapabilityPackagePage.v1"],
    "businessAssetLinkClosure": ["resourceGateway.businessAssetLinkClosure.v1"],
    "packageCompilationReceipt": ["resourceGateway.packageCompilationReceipt.v1"],
    "legacyGraphPackageProjection": ["resourceGateway.legacyGraphPackageProjection.v1"],
    "legacyGraphPackageProjectionCatalog": ["resourceGateway.legacyGraphPackageProjectionCatalog.v1"]
  },
  "features": {
    "businessMirrorProtocol": true,
    "businessMirrorPackageApi": true,
    "businessMirrorPackageCompilerApi": true,
    "businessMirrorPackageCompilerAuthorityReady": true,
    "businessMirrorLegacyMigrationApi": true,
    "businessMirrorLegacyMigrationAuthorityReady": true,
    "businessMirrorProposalSimulation": false
  }
}
```

`businessMirrorProtocol=true` 表示领域协议、Schema 和独立校验器可用。`businessMirrorPackageApi=true` 表示 Package 作者态持久化 API 已装配；`businessMirrorPackageCompilerApi=true` 表示原子编译事务可调用。Authority readiness 现在为 `true`，表示默认部署已安装组合 Authority，并能围栏其明确拥有的 `GRAPH_DRAFT` 与 `CONTRACT` source kind；不支持的 Scenario、Fidelity、Outcome 等类型仍生成 `MISSING`。它不表示某个 Package 已 READY，也不表示 Proposal 模拟、Business Mirror Workspace 或生产环境认证已经完成。

`businessMirrorLegacyMigrationApi=true` 表示 catalog、preview 和 import 路由已安装；`businessMirrorLegacyMigrationAuthorityReady=true` 表示运行时至少能精确投影一个存量 Graph。静态 capability factory 保持 readiness 为 `false`，只有 Spring 装配真实 projector 后才动态提升，防止协议支持被误报为部署就绪。

## 5. 架构偏差审计

| 蓝图决策 | 当前实现 | 结论 |
|---|---|---|
| 业务主模型进入独立 `businessmirror` 深模块 | 新对象位于 `com.leanowtech.bloge.gateway.businessmirror.domain` | 符合 |
| `integration.mirror` 保持执行与证据事实边界 | 新模块只复用 Capability、Contract、Effect、ArtifactRef 和 Provenance | 符合 |
| Graph 是执行投影，不是业务根 | Package 使用 `graphRefs`，没有向 `GraphDraft` 塞业务字段 | 符合 |
| Proposal 不是正式 Operator 上的 `mock=true` | Proposal 有独立身份、价值假设、隔离 binding 和证据生命周期 | 符合 |
| RG 不接管 ANEKE Registry/Gate | Snapshot 不包含 ANEKE 权威状态 | 符合 |
| Test Kit 不依赖服务端和 Spring | 新公共入口只依赖 Jackson 与打包 Schema | 符合 |
| 写命令由完整企业 Scope 隔离 | current/history/lock/receipt 主键均包含五段 Scope | 符合 |
| 重试返回原始事实，不重复执行 | exact receipt 与 canonical command fingerprint 持久化在同一事务 | 符合 |
| 示例自动建表不冒充生产迁移 | 独立 PostgreSQL DDL 和原生数据库认证存在，文档明确运行时建表边界 | 符合 |
| 新对象进入 additive `1.1.x` 集成协议 | 对象已进入能力探针，但当前 protocolVersion 仍为 `1.0.0` | 有意延后到 BM-014；先补多版本协商与旧消费者认证，避免伪兼容 |
| Legacy 迁移不改写 Graph | projector 只生成 exact refs、Package draft 和 gap；原 Graph/Contract 不变 | 符合 |
| 技术测试不冒充业务 Scenario | Contract Test Suite 只进入 discovered evidence 和 provenance | 符合 |

未发现需要推翻蓝图边界的架构偏差。

## 6. 差距复评

Iteration 1 关闭了业务主对象的协议断层。Iteration 2 关闭了 Package 作者态没有 durable source-of-truth、写入不可重放、并发保存会漂移、跨系统无法校验保存结果的问题。业务能力包现在可以被可靠地写入和读取，但仍不能编译成不可变 Snapshot，也没有产品工作区和运行闭环。

| 口径 | 开工前 | Iteration 1 | Iteration 2 | 剩余主要缺口 |
|---|---:|---:|---:|---|
| 技术内核完成度 | `90-92` | `91-93` | `92-94` | PackageCompiler、Proposal simulation、HA/DR/upgrade certification |
| 产品蓝图闭环度 | `67` | `72` | `76` | Workspace、编译、模拟、实现交付、Impact/Evidence |
| 复杂企业生产成熟度 | `54` | `54` | `56` | PostgreSQL 单机认证之外，仍缺 HA/DR、客户 Connector、KMS/WORM 和组织运行证据 |

按 15 个工作包的风险加权口径，当前距离完整蓝图仍约 `24%`。下降的 `4` 个百分点来自可运行、可重放、可跨语言消费的 Package authoring vertical slice，而不是单纯增加 Java 类型。产品 UI、编译/模拟运行、客户真实事实源和生产基础设施仍是高权重缺口，因此没有把单个 repository 迭代放大成虚假成熟度。

该数字是仓库内部工程复评，不是客户验收结论，仍明显高于 `<3%` 的收敛门槛。

## 7. Iteration 2 复评时确定的后续路径

下一步先让已可靠保存的 Package 能产生可解释、可复现的编译事实，再包装存量 Graph：

1. 建立 `PackageCompiler` port，输入 exact stored draft 与 authority-frozen dependency resolver。
2. 产出 immutable `DomainCapabilityPackageSnapshot` 和由 Finding 派生的 `PackageReadinessReport`。
3. 对 Contract、Capability、Graph、Scenario、L0-L3 link、Fidelity 与 Outcome closure 做完整性、Scope、fingerprint 和生命周期校验。
4. 使用两次编译相同指纹、依赖 TOCTOU 冲突、乱序/property inputs 和 tamper cases 证明确定性。
5. 实现 Legacy Graph projector；七个内置 Graph 都能生成 Package draft 和明确 gap inventory，缺失业务语义不得误报 READY。

BM-003 与 BM-004 分别提交。每次提交后重新运行完整门禁、架构偏差审计和差距复评。

## 8. Iteration 3A：BM-003 deterministic compiler kernel

### 8.1 已交付

| 交付 | 结果 |
|---|---|
| Authority freeze/fence port | Registry 解析与编译决策分离；结果发布前强制第二次 TOCTOU 检查 |
| Dependency observation | exact source、immutable materialized ref、Scope、resolution status 和 kind-specific assurance 可审计 |
| Fail-closed compiler | Draft obligation、Contract/Scenario/Outcome、Proposal isolation、高风险 Effect 和 dependency manifest 统一派生 Finding |
| Business Asset Link Closure | 新增单 Scope、无 dangling ref、无环、content-addressed 的 L0-L3 closure 协议与严格 Schema |
| Deterministic facts | 相同输入、revision 和 `compiledAt` 生成相同 Readiness、Closure 和 Snapshot fingerprint |
| Independent verification | Test Kit 对 Closure、Readiness 和 Snapshot 复算 fingerprint 并验证关键语义，不依赖服务端或 Spring |
| 接入说明 | `resource-gateway-business-mirror-package-compiler.md` 记录不变量、适配器责任、Finding 和未完成边界 |

### 8.2 聚焦验证

| 项目 | 命令 | 结果 |
|---|---|---|
| Resource Gateway | `mvn -f resource-gateway-examples/pom.xml -Dtest=PackageCompilerTest,DomainCapabilityPackageProtocolTest test` | `16` tests，全部通过；其中编译器 `9` 个测试含 100 组乱序输入 |
| Resource Gateway Test Kit | `mvn -f resource-gateway-test-kit/pom.xml -Dtest=BusinessMirrorProtocolTest test` | `15` tests，全部通过 |

完整项目门禁：

| 项目 | 命令 | 结果 |
|---|---|---|
| Resource Gateway | `mvn -f resource-gateway-examples/pom.xml clean verify` | `5950` tests，`0` failures，`0` errors，`13` skipped；包含原生 PostgreSQL 和真实浏览器 E2E；`BUILD SUCCESS` |
| Resource Gateway Test Kit | `mvn -f resource-gateway-test-kit/pom.xml clean verify` | `541` tests，`0` failures，`0` errors，`0` skipped；JAR、shade 与 Javadoc 门禁通过；`BUILD SUCCESS` |

首次 Test Kit 完整门禁中，`540` 个测试全部通过，但 Javadoc 因内部 verifier 暴露了未文档化公共类型而失败。实现随即收窄为 package-private，仅保留 `BusinessMirrorProtocol` 作为公共入口并补全其契约说明；最终结果以修复后重跑为准。

提交前语义对齐审查又发现，服务端会拒绝端点、关系和条件相同而仅风险或 Owner 不同的重复业务关系，Test Kit 最初只依赖 JSON Schema 的结构性去重。独立 verifier 已补充同坐标去重并新增回归用例，避免跨语言消费者接受服务端拒绝的 Closure。

### 8.3 诚实的完成边界

Iteration 3A 只关闭编译决策与跨语言复验内核；Iteration 3B 已进一步关闭以下两项。仍缺：

- 连接现有 Graph、Scenario、Fidelity、Outcome 权威仓储的 Adapter；
- 七个内置 Graph 的 Legacy Package 投影；
- async capacity、取消、HA/DR 与真实客户 Authority 认证。

能力探针声明 compiler API 可用，但在默认 fallback Authority 下明确声明 Authority 未就绪。

### 8.4 差距复评

编译器已经能确定性地区分「可发布 immutable fact」与「有明确阻断原因的作者态」，关闭了协议对象存在但没有生成规则、exact ref 被乐观信任、L0-L3 link 无法独立验真的根问题。由于产品和部署仍不能调用该内核，风险加权差距只从约 `24%` 降至约 `22%`，不会按代码行数虚增成熟度。

下一子迭代继续 BM-003 的真实 Authority Adapter，并与 BM-004 Legacy Graph Adapter 合并验证。

## 9. Iteration 3B：BM-003 durable compilation vertical slice

### 9.1 已交付

| 交付 | 结果 |
|---|---|
| 原子编译事务 | exact source read、命令锁、Package revision 锁、Authority freeze/fence、三类 fact 与 receipt 单事务提交 |
| Append-only facts | Readiness、Business Asset Link Closure、可选 Snapshot 独立分表；exact compilation revision 可回读并复验 |
| Durable idempotency | command fingerprint 与 exact receipt 持久化；响应丢失、重启和同 key 漂移语义与 authoring 一致 |
| 跨副本 revision allocator | 不同 idempotency key 并发编译同一 Package 时，使用独立 Package coordinate lock 分配单调 revision |
| 认证 HTTP | `POST .../{packageId}/compile` 与 `GET .../compilations/{revision}`；Scope、purpose 和稳定 problem contract 生效 |
| 失败关闭默认 Authority | `UnavailablePackageCompilationAuthority` 将未接依赖形成 `BLOCKED` Finding，不接受客户端伪造 observation |
| 动态能力探针 | `businessMirrorPackageCompilerApi=true`；运行时根据安装的 Authority Bean 派生 `businessMirrorPackageCompilerAuthorityReady` |
| 跨语言 receipt | 严格 `resourceGateway.packageCompilationReceipt.v1` Schema；Test Kit 复验内嵌 fingerprints 与 source/revision/time/ref 对齐 |
| PostgreSQL migration | `V20260814_002__business_mirror_package_compilation.sql` 创建 allocator、locks、receipts 和 append-only fact 表 |
| 模块职责 | `compilation` 只保留领域编译规则和 ports；事务编排、JDBC Adapter、HTTP 分别位于 `application`、`persistence`、`transport` |

### 9.2 自动化验证

| 测试层 | 用例数 | 证明内容 |
|---|---:|---|
| 编译/持久化 H2 | 6 | restart exact replay、key drift、双副本不同 key revision、事务回滚、fact column tamper、READY Snapshot rehydrate |
| 原生 PostgreSQL 14 | 2（累计） | 两份 deployment DDL、`fsync/synchronous_commit`、save key 串行化、Package revision 跨连接串行化 |
| Controller/Spring HTTP | 4 | compile/read 分权认证、headers、BLOCKED receipt、exact replay、exact GET、事务代理 |
| Capability | 2 | 新对象/API 可发现；安装真实 Authority 后 runtime readiness 动态变为 true |
| Compiler kernel | 9 | 完整/阻断编译、100 组乱序、TOCTOU、Scope、tamper、cycle |
| Test Kit | 16 | 15 类 Schema 资源、compile receipt 内嵌事实复验、篡改与关系闭包拒绝 |

聚焦门禁全部通过：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=BusinessMirrorCapabilityTest,PackageCompilationControllerTest,\
BusinessMirrorPackageSpringWiringTest,DatabasePackageCompilationTest,\
PackageCompilerTest,DatabaseDomainCapabilityPackagePostgresCertificationTest test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=BusinessMirrorProtocolTest test
```

开发中有两次有价值的红灯：第一版 H2 `MERGE ... KEY` 会把既有 `next_revision` 重置为 `1`；仅改为 `WHEN NOT MATCHED` 后，两个首编译事务仍可同时判断 head 缺失。最终引入独立 `(Scope, packageId)` coordinate lock，先串行化首行创建再锁 head。修复后 H2 和 PostgreSQL 双连接测试均稳定得到 revision `1/2`。

首次完整门禁还发现一项能力探针契约测试仍把历史 endpoint 集合写死，新增八个 Package endpoint 被判为 unexpected。根治不是放宽集合断言，而是更新固定 endpoint 清单，并在同一测试中绑定 `businessMirrorPackageApi`、`businessMirrorPackageCompilerApi`、Authority readiness 以及两个新对象版本。端点、feature 与对象 Schema 今后必须同步演进。

完整项目门禁：

| 项目 | 命令 | 结果 |
|---|---|---|
| Resource Gateway | `mvn -f resource-gateway-examples/pom.xml clean verify` | `5964` tests，`0` failures，`0` errors，`13` skipped；原生 PostgreSQL、真实浏览器 E2E 与可执行 JAR 打包通过；`BUILD SUCCESS` |
| Resource Gateway Test Kit | `mvn -f resource-gateway-test-kit/pom.xml clean verify` | `542` tests，`0` failures，`0` errors，`0` skipped；Schema packaging、shade 与 Javadoc 门禁通过；`BUILD SUCCESS` |

### 9.3 差距复评

Package 编译已经从纯 Java 内核升级为可认证调用、可重放、可跨副本串行、可离线复验的部署纵向切片，BM-003 的主要剩余项收敛为真实 Authority Adapter 与大型编译容量控制。风险加权差距由约 `22%` 降至约 `20%`。

下降幅度仍受两个高权重事实约束：默认部署无法把七个内置 Graph 及其 Contract/Scenario/Fidelity/Outcome 解析成 READY Package；业务人员也没有 Workspace 完成 Package/Proposal 操作。因此下一轮不能继续堆 repository，而应完成 Legacy projector + composite Authority 的真实数据闭环。

## 10. Iteration 3C：BM-003 composite Authority

### 10.1 已交付

| 交付 | 结果 |
|---|---|
| 唯一 source-kind ownership | `CompositePackageCompilationAuthority` 在启动时拒绝同一 kind 的双 Adapter Owner，不允许靠隐式优先级消除歧义 |
| Unsupported-kind fail closed | 未安装 Adapter 的依赖形成精确 `MISSING` observation，不尝试第二 Registry，不从客户端读取 observation |
| 完整 generation fencing | generation 绑定 Adapter 集、Scope、source/head observation、closure/plan、业务关系、evidence 与 code-owned policy；发布前按同一 source refs 完整重解析 |
| 内置 Graph 权威适配 | `BuiltInGraphAssetAuthority` 复用 classpath DSL、Graph Contract、Operator/Resource 目录和既有 capability closure 投影，不维护第二份拓扑 |
| 内置 Contract 权威适配 | 七个 `GatewayGraphContract` 形成 content-addressed `CONTRACT` exact refs，并证明 `SCHEMA_VALID` |
| 测试资产诚实复用 | 每个内置 Contract Test Suite 进入 exact evidence refs，但不冒充 ScenarioPack，不消除 Scenario readiness blocker |
| 动态能力探针 | 默认 Spring 部署的 `businessMirrorPackageCompilerAuthorityReady` 变为 `true`；静态协议工厂仍保留未装配状态用于独立消费者测试 |

### 10.2 关键不变量

1. `GRAPH_DRAFT` source ref 只物化为 immutable root `CAPABILITY`；Draft 本身不进入 Package manifest。
2. 单 Graph Package 获得一个 exact `CAPABILITY_CLOSURE` ref；多 Graph root 在尚无正式 Package aggregate closure 前返回空 closure 并失败关闭，不擅自选第一个。
3. Resource descriptor、Operator snapshot、DSL、Contract 或 Test Suite 变化都会改变物化 ref 或 Authority generation；二次解析可检测编译窗口内漂移。
4. `businessMirrorPackageCompilerAuthorityReady=true` 只证明 Adapter infrastructure 已安装，Package 级是否 READY 仍由 exact observation 与 Finding 决定。
5. 内置测试套件含可执行测试数据，但它与 owner-governed Scenario denominator/ScenarioPack 语义不同；Legacy 迁移必须显式转换和确认。

### 10.3 自动化验证

| 层级 | 用例 | 证明内容 |
|---|---:|---|
| Composite unit | 3 | unsupported kind、唯一 kind owner、完整 generation drift、双 Graph root fail closed |
| 真实 Spring wiring | 10（测试类累计） | 七个 DSL Graph、Contract、Operator/Resource、Test Suite exact resolution；动态 capability readiness |

已执行：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=CompositePackageCompilationAuthorityTest test

mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ResourceGatewayApplicationTest test
```

两组聚焦门禁分别为 `3/3` 与 `10/10` 全绿。

完整项目门禁已执行：`5964` tests，`0` failures，`0` errors，`13` skipped；原生 PostgreSQL、真实浏览器 E2E 与可执行 JAR 打包均通过，`BUILD SUCCESS`。测试期间仅出现仓库既有的 BLOGE 发布 POM 元数据与 Selenium CDP 版本兼容提示，未形成测试失败或能力降级。

### 10.4 差距复评

默认部署不再只有「会输出缺失」的 fallback Authority，而是能从现有系统的真实 source-of-truth 解析七个内置 Graph、Contract、外部能力闭包和测试证据。BM-003 的根问题由「没有真实 Adapter」收敛为「Adapter 类型覆盖与生产容量认证尚不完整」。

这一迭代没有提供业务工作区、Scenario 治理转换或客户 Outcome，因此风险加权差距只从约 `20%` 降至约 `19%`。下一提交实施 BM-004：把七个内置 Graph 包装成 `LEGACY_IMPORTED` Package preview/draft，输出正式 gap inventory，并复用 durable authoring API 渐进导入；所有不可推断业务字段必须保持阻断。

## 11. Iteration 4：BM-004 Legacy Graph migration

### 11.1 已交付

| 交付 | 结果 |
|---|---|
| 无副作用迁移目录 | `GET /api/business-mirror/legacy-graphs` 在认证 Scope 内按 graph name 返回七个完整 preview |
| 单 Graph preview | exact Graph/Contract/Capability/Closure/Test Suite refs、空业务定义、`INFERRED` provenance 和完整 gap inventory |
| Durable 渐进导入 | `POST .../{graphName}/packages` 复用 Package create 的 Scope、事务、optimistic revision 和 durable exact replay |
| Fail-closed 迁移状态 | `PACKAGE_READINESS` gap 与 Draft blocker 精确等价；Owner、Contract 和 MirrorPlan 迁移 policy gap 不可隐藏 |
| 严格跨语言协议 | projection/catalog 两个 Draft 2020-12 Schema，全部 `additionalProperties: false` |
| 独立语义验真 | Test Kit 复算 projection fingerprint，并验证 source closure、Scope、排序、状态和 gap 完整性 |
| 固定兼容性向量 | server-produced loan-decision projection fixture 约束跨 JVM exact refs 和 projection fingerprint |
| 动态能力发现 | API support 与 runtime authority readiness 分离；三条 endpoint 和两个 object version 可发现 |
| 操作与恢复手册 | `resource-gateway-business-mirror-legacy-migration-guide.md` 覆盖启动、preview、导入、重放、编译、错误和停止 |

### 11.2 迁移信任边界

1. Package id 固定为 `legacy:{graphName}`，preview revision 固定为 `0`，成功导入后由 repository 分配 revision `1`。
2. Graph Contract 是技术 source evidence，不自动成为业务 Owner 已批准的 Package Contract。
3. Contract Test Suite 是可执行证据，不是 owner-governed Scenario denominator 或 ScenarioPack。
4. 无法从拓扑证明的 Problem、Goal、Owner、Risk、Solution、Carrier、Channel、Fidelity、Outcome、State 和 Effect 保持空值或保守值，并形成阻断 gap。
5. preview、catalog 和 import 使用可信身份的五段 Scope；调用方不能在 URL 或 body 中切换租户。
6. projection 的 provenance 必须闭合 Graph、Contract、Capability、Closure 和全部 discovered suites；遗漏任一 source ref 都会被服务端或 Test Kit 拒绝。
7. 导入成功只表示作者态已可靠落库，不表示迁移完成、编译 READY、ANEKE 可发布或客户环境已认证。

### 11.3 开发红灯与根治

固定 fixture 在独立 JVM 中首次发现同一 Resource Descriptor 生成不同 capability fingerprint。病根不是 SHA-256 或 ObjectMapper，而是 `ResponseProtocol.BodyCode.successValues` 使用 `Set.copyOf`，其跨 JVM 迭代顺序不稳定；canonical serializer 会按该顺序编码数组。

实现没有放宽 fixture 等值断言。`BodyCode` 和 `StatusCodes` 现在在值对象构造边界转为稳定有序、不可变 Set，并新增不同插入顺序生成相同 source/snapshot fingerprint 的回归测试。修复后独立 JVM 的 Spring HTTP 测试与固定 fixture 逐字段一致。这个缺陷说明 content-addressed protocol 必须规范化所有无序集合，不能只排序 Map key。

### 11.4 自动化验证

聚焦门禁：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=CapabilityProjectionServiceTest,BusinessMirrorCapabilityTest,\
ResourceGatewayApplicationTest,BusinessMirrorPackageSpringWiringTest,\
ToolStudioIntegrationServiceTest test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=BusinessMirrorProtocolTest test
```

验证覆盖七个 Graph、catalog 排序、固定 preview、未知 Graph `404`、import exact replay、导入后编译、静态/动态 capability readiness、Set 顺序确定性、Schema packaging、gap 隐藏和 fingerprint 篡改拒绝。

聚焦门禁中，Test Kit 为 `18/18` 全绿，Spring migration HTTP 为 `3/3` 全绿，Capability projection 为 `13/13` 全绿。

完整项目门禁：

| 项目 | 命令 | 结果 |
|---|---|---|
| Resource Gateway | `mvn -f resource-gateway-examples/pom.xml clean verify` | `5967` tests，`0` failures，`0` errors，`13` skipped；原生 PostgreSQL、真实浏览器 E2E、Spring Boot 可执行 JAR 打包通过；`BUILD SUCCESS` |
| Resource Gateway Test Kit | `mvn -f resource-gateway-test-kit/pom.xml clean verify` | `544` tests，`0` failures，`0` errors，`0` skipped；Schema packaging、shade、Javadoc 和 JAR 打包通过；`BUILD SUCCESS` |

完整回归新增覆盖 migration controller wiring、跨 JVM 固定 fixture、endpoint capability 清单和 Set 规范化。`13` 个 skipped 均为仓库既有的环境条件跳过，不是本轮失败降级。

### 11.5 差距复评

BM-004 关闭了存量 Graph 只能继续手工维护、无法进入 Package 主模型、迁移过程会误填业务语义以及导入结果无法独立验真的根问题。七个内置 Graph 现在都能逐包 preview 和导入，并且缺失项不会误绿。

风险加权差距由约 `19%` 降至约 `17%`。仍占主要权重的缺口是：没有业务人员可用的 Business Mirror Workspace；Proposal 尚不能形成隔离模拟闭环；持久化 Visual Graph、Scenario、Fidelity 和 Outcome Authority 未接通；客户生产环境尚无 KMS、HA/DR、升级与容量认证。下一迭代进入 BM-005，以现有 Package、projection、gap、compile receipt 和 exact lineage 构建任务导向工作区，而不是在旧 Canvas 上继续堆字段。
