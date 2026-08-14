# Resource Gateway 客户业务能力镜像实施状态

> 状态：持续更新
>
> 蓝图：[客户业务能力镜像蓝图差距评估与技术演进方案](resource-gateway-customer-business-mirror-blueprint-gap-and-technical-evolution-plan.md)
>
> 当前迭代：BM-013 Runtime Certification Harness 仓库内工程实现已完成；下一步进入 BM-014 ANEKE protocol 1.1 持续集成
>
> 最近更新：2026-08-15

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

## 12. Iteration 5：BM-005 Business Mirror Workspace

### 12.1 已交付

| 交付 | 结果 |
|---|---|
| 默认业务入口 | `/` 重定向到 `/business-mirror/`；Portfolio 以七个真实 Legacy Graph preview 呈现待镜像资产，旧 Author v2 与 Legacy 页面继续可进入 |
| 七步任务工作区 | Package 页面按问题、边界、能力、场景、演练、证据、校准与提交组织任务，不把协议字段平铺成表单 |
| 引导式作者流程 | 支持 preview 导入、业务字段编辑、durable save 和 compile；无需直接编辑 JSON |
| 可行动就绪信息 | 固定显示第一个阻断项和完整 gap inventory；保存成功不伪装成 READY |
| L0-L3 能力地图 | 从 exact refs 投影 L0 资源指令、L1 服务设计、L2 服务载体和 L3 业务应用；未建模层级保持缺失，不用拓扑猜测 |
| 企业级基础体验 | 英文/中文切换、键盘可达、响应式任务轨与内容布局；`390`、`820`、`1280` 宽度完成真实浏览器检查 |
| 服务端可分发页面 | Vite 产物随 Spring Boot JAR 打包至独立 `business-mirror` 静态目录，canonical route、资源加载和能力探针可验真 |
| VS Code 离线闭环 | 无 sidecar 时可浏览固定目录、preview、导入、编辑、保存与编译；幂等键绑定 canonical material fingerprint |
| 演示与操作材料 | 启停脚本默认打开 Business Mirror；新增操作手册和四张中文界面截图 |

### 12.2 架构与信任边界

1. Workspace 是 Package、Legacy projection 和 compile receipt 的任务投影，不是新的事实存储；浏览器不自行派生 revision、fingerprint 或 readiness。
2. 页面复用既有认证、Scope、purpose、optimistic revision 和 idempotency 约束；作者请求显式携带 `X-Purpose: BUSINESS_MIRROR_AUTHORING`。
3. 发现到的 Contract Test Suite 仍然只是技术证据，不能被界面自动提升为 owner-governed Scenario 或 Scenario denominator。
4. 页面不把保存成功解释为可发布，也不伪造 Outcome、Fidelity、Governance 或 ANEKE gate 结论；所有未满足项继续显示为 blocker/gap。
5. VS Code 离线包仅存在于当前扩展会话，用于轻量作者体验；它不声称具有生产持久化、真实网络模拟、Secret、治理或发布能力。
6. 相同 idempotency key 仅可重放完全相同的 canonical material；材料变化必须使用新 key，否则失败关闭。

### 12.3 开发红灯与根治

| 红灯 | 病根 | 根治与回归保护 |
|---|---|---|
| 真实页面请求返回 `400` | API client 未携带服务端安全策略要求的 authoring purpose；纯组件测试没有穿透该边界 | Business Mirror client 统一附加 purpose header；API 单测断言 header；打包 JAR 后执行真实浏览器流程 |
| `/` 页面空白且静态资源 `404` | server-side forward 保留根 URL，Vite 的相对 `./assets` 从错误基址解析 | 根路径和无尾斜杠入口使用 canonical redirect；controller 测试固定重定向/forward 契约；浏览器验证产物资源 |
| 首次保存可能出现幂等冲突 | 保存可早于异步 fingerprint projection 完成，重试键却已基于旧材料生成 | continuity hook 以 material fingerprint epoch 原子管理 retry identity；新增“保存发生在异步投影前”的 exact retry 测试 |

这些问题共同证明：Business Mirror 不能只以 React DOM 测试作为验收。后续工作包继续同时执行 domain/protocol、打包部署和真实浏览器门禁。

### 12.4 自动化与视觉验证

| 范围 | 结果 | 证明内容 |
|---|---|---|
| Frontend | `764/764` tests 通过 | Portfolio、六步工作区、API purpose、i18n、键盘、路由、bundle budget 和 continuity |
| VS Code extension | `17/17` tests 通过，`npm run verify` 通过 | 离线路由、固定任务、幂等与冲突、WebView 打包和 chunk 检查 |
| Resource Gateway | `5968` tests，`0` failures，`0` errors，`13` conditional skips | Controller、能力探针、真实浏览器 E2E、Spring Boot 可执行 JAR |
| Resource Gateway Test Kit | `544` tests，全部通过 | Business Mirror schema、fixture、独立协议和打包门禁无回归 |
| 手工真实浏览器 | `1280x720`、`820x900`、`390x844` | 七个 Portfolio 项、导入、编辑、保存、编译、首要 blocker、L0-L3、中文/英文、无 document 横向溢出 |

`1440` 宽度有 CSS 与自动化 viewport contract 覆盖，但本轮可用浏览器的最大实际截图宽度为 `1280`，不能将其写成已完成的人工视觉认证。截图和操作路径见 [Business Mirror Workspace 使用手册](resource-gateway-business-mirror-workspace-guide.md)。

### 12.5 架构漂移审计

1. 没有新增平行业务真相源；Legacy Graph endpoint、Package repository、Compiler 和 Readiness 派生规则保持权威。
2. Workspace 没有把 suite evidence 自动转换为 Scenario，也没有越权定义客户 Outcome。
3. `READY`、Publish、Governance 和 Production certification 没有因 UI 可操作而提前宣称完成。
4. 在线和离线实现共享 fail-closed gap 模型；离线只提供固定样例和会话内作者态，不模拟生产 Authority。
5. Author v2 与 Legacy 保留兼容入口，Business Mirror 通过新路由渐进成为默认任务入口，没有破坏既有 API 或 Graph name。

### 12.6 差距复评

BM-005 关闭了“业务人员只能面对协议、JSON 或纯画布”“存量 Graph 无统一迁移入口”“缺口不可行动”和“轻量 VS Code 场景必须启动 sidecar”的体验断层。现在用户可以从真实 Portfolio 进入 Package，沿六项任务补全业务语义，并持续看到 exact lineage 与第一个阻断项。

风险加权差距由约 `17%` 降至约 `14%`。降幅保持克制，因为高权重能力仍未完成：CapabilityProposal 尚不能在严格隔离域内形成 Fixture 驱动的模拟与业务验收闭环；Visual Graph、Scenario、Fidelity 和 Outcome 仍缺少持久化 Authority；生产 KMS、HA/DR、升级、容量和多组织运营尚未认证；客户业务验收也不是仓库内测试可以替代的证据。

下一迭代进入 BM-006：先实现 CapabilityProposal durable authoring，再由 BM-007 接入 Fixture、验收套件和确定性模拟运行。任何未匹配调用、真实网络、Secret 或生产副作用都必须失败关闭，模拟成功也不能自动晋级为 IMPLEMENTED 或 CONFORMANT。

## 13. Iteration 6：BM-006 CapabilityProposal durable authoring

### 13.1 已交付

| 交付 | 结果 |
|---|---|
| Proposal command/query port | 新增 `CapabilityProposalAuthoringService` 与 repository interfaces；HTTP、事务编排和 JDBC 存储保持分层 |
| Current 与 immutable history | `DatabaseCapabilityProposalDraftRepository` 以五段企业 Scope 和 `proposalId` 定位 current/history；保存后重算 canonical fingerprint |
| 乐观并发控制 | create 仅接受 revision `0`；save 必须同时匹配 path id、body id 和 `expectedRevision`；冲突不覆盖 current |
| Durable exact replay | `(Scope, Idempotency-Key)` 数据库锁、canonical command fingerprint 和持久回执共同保证响应丢失、重启及跨副本重试返回原结果 |
| 认证 Authoring API | `/api/business-mirror/proposals` 提供 create、save、list、current、history 和 exact revision；read/write 使用独立 `IntegrationOperation` |
| PostgreSQL 部署协议 | `V20260814_003__business_mirror_proposal_authoring.sql` 建立 current、history、command lock 和 receipt 表及约束 |
| 严格跨语言协议 | 新增 stored draft、save receipt、page 三份 JSON Schema 和固定取消费 Proposal receipt；Test Kit 可离线校验、复算 fingerprint 与检查排序/Scope |
| 能力发现 | `businessMirrorProposalApi=true`，并显式保持 `businessMirrorProposalSimulation=false`；capability endpoint 只广告本轮真实存在的六个 API |
| 使用与运维文档 | 新增 Proposal authoring 指南，覆盖启动、能力探针、创建、保存、查询、exact replay、隔离、错误恢复、DDL 和停止服务 |

### 13.2 事务、身份与隔离语义

一次写请求的原子边界为：

```text
authenticate complete enterprise Scope
  -> validate proposal identity, revision and SIMULATION_ONLY binding
  -> reject raw Secret material
  -> acquire (Scope, Idempotency-Key) database row lock
  -> replay exact receipt or reject key/material drift
  -> compare current revision
  -> write current + immutable history
  -> write exact receipt
  -> commit once
```

以下边界必须分开理解：

1. `CapabilityProposalDraft` 已经只允许 `SIMULATION_ONLY` binding，并把 real external call、external credential 和 network egress 固定为 `false`。
2. 本轮没有 Proposal 执行 API，因而没有任何路径可触达真实网络、凭据或生产副作用。这是「尚未开放执行面」的安全事实，不是 BM-007 运行时隔离已经认证。
3. 作者可以声明 Fixture 和 acceptance suite 的 exact refs，但本轮不会把引用存在解释为已解析、已运行或已通过。
4. 保存成功只表示作者态持久化成功；不会创建 `CapabilityProposalSnapshot`，也不会把 evidence state 提升为 `SIMULATED`、`IMPLEMENTED` 或更高状态。
5. Scope 只来自已验证的 `IntegrationRequestContext`。请求体 Scope 不一致时返回 `403`，列表、历史和幂等回执均不能跨 Scope 读取。

### 13.3 开发红灯与根治

| 红灯 | 病根 | 根治与回归保护 |
|---|---|---|
| Spring 上下文无法代理 Proposal repository | `@Transactional` repository 被声明为 `final`，CGLIB 无法创建事务代理 | 移除实现类的 `final` 限制；Spring 完整装配测试验证 AOP、Controller、认证和数据库真实链路 |
| Test Kit `clean verify` 在测试全绿后仍失败 | 新增 public protocol API 缺少完整 Javadoc，发布门禁正确阻止不完整客户端 | 补齐所有 `@param`、`@return` 和 `@throws`；完整 Javadoc/JAR/shade 门禁保持开启 |
| 首次 Resource Gateway 全量回归在最后失败 | capability endpoint 的 exact golden 清单未登记六个新 Proposal API，5977 个测试中 1 个严格协议断言发现漂移 | 更新权威清单，保留 `containsExactlyInAnyOrder` 严格断言；定向重跑 capability、Proposal、Spring 和 PostgreSQL 共 `59/59` 通过 |

这些红灯都来自强门禁，修复方式是补齐被证明存在的工程事实，而不是降低断言或关闭发布检查。

### 13.4 自动化验证

| 范围 | 结果 | 证明内容 |
|---|---|---|
| Proposal domain/authoring focused suite | `23/23` 通过 | binding 失败关闭、readiness、H2 transaction、rollback、history/page、Scope、revision、exact replay、双实例并发、Controller、Spring 与 PostgreSQL |
| Endpoint regression focused suite | `59/59` 通过 | capability exact endpoint 清单、Proposal 协议、认证 HTTP、Spring 装配和原生 PostgreSQL |
| Test Kit focused suite | `20/20` 通过 | 三份新增 Schema、固定 receipt、fingerprint/Scope/page/tamper/duplicate 校验 |
| Resource Gateway Test Kit | `546` tests，`0` failures，`0` errors，`0` skipped | `clean verify`、Schema packaging、shade、Javadoc 与 JAR 全绿 |

Resource Gateway 完整 `clean verify` 在修正 endpoint golden 清单后通过：`5977` 项测试，`0` 失败、`0` 错误、`13` 跳过，并完成真实 Chromium 工作流、原生 PostgreSQL 认证和可执行 Spring Boot JAR 打包。全量门禁结果与定向测试结论一致。

### 13.5 架构漂移审计

1. Proposal repository 只存作者态和保存回执，没有成为 Contract、Fixture、Suite、Package、Graph 或治理状态的新 Authority。
2. `CapabilityProposalSnapshot` 仍是服务端派生、内容寻址的不可变事实；作者 API 不能直接写 snapshot 或 evidence state。
3. Proposal API 使用现有 Integration authentication、Scope、problem contract、canonical fingerprint 和 PostgreSQL 事务模式，没有另建旁路身份或存储协议。
4. `businessMirrorProposalApi` 与 `businessMirrorProposalSimulation` 分开广告，避免客户端把「可编辑」误判成「可运行」。
5. fixed fixture、server output 和独立 Test Kit 使用同一 wire contract，但 Test Kit 不依赖 Resource Gateway server 或 Spring Boot artifacts。

### 13.6 差距复评

BM-006 关闭了 Proposal 只能以领域 record 或固定 JSON 存在、无法被企业 Scope 隔离地创建和演进、并发编辑会静默覆盖、重试可能重复写入、外部消费者无法独立验真的根问题。它为 BM-007 提供了稳定的 authoring source、exact refs 和部署协议。

风险加权差距由约 `14%` 降至约 `12%`。降幅保持克制：Proposal 仍不能解析 Fixture/Suite Authority、不能生成 temporary snapshot/MirrorPlan、不能试跑 acceptance suite，也没有分层 simulation evidence；Visual Graph、Scenario、Fidelity 与 Outcome 的完整持久 Authority，以及生产 KMS、HA/DR、容量、多区域和组织运营认证仍未完成。

下一迭代进入 BM-007：从一个 exact Proposal revision 冻结 temporary snapshot，解析并 pin Fixture/acceptance suite，复用既有 `MirrorPlanCompiler`、Fixture runtime 和 Test evidence 内核执行模拟。未匹配调用必须 `ABSTAINED/FIXTURE_NOT_FOUND`，物理禁止真实网络、Secret 和 External Write；证据必须标注 `SIMULATED`、fixture 来源、匹配规则、调用次数、限制与不确定性，并且不能被实现或发布门禁当成 conformance evidence。

## 14. Iteration 7：BM-007 Proposal simulation

### 14.1 已交付

| 交付 | 结果 |
|---|---|
| 纯 temporary snapshot compiler | `CapabilityProposalSimulationCompiler` 将候选 Contract 仅覆盖到一个 exact external Capability，递归重封受影响祖先和 root runtime Graph fingerprint；原 DSL、base closure 和各 Authority 均不被修改 |
| 完整运行前置复验 | Proposal revision/fingerprint/expiry/readiness、Package snapshot/manifest/closure、built-in Graph、target capability、TestSuite、Fixture 和 Region 全部 exact-match；不接受 `latest` |
| 物理隔离准入 | V1 只接受只读、无状态、无 Secret、当前 Region 合法的 Contract/closure；Fixture 的 `REAL`、`SPY`、`STREAM`、`FALLBACK_TO_REAL`、`ALLOW_REAL` 全部失败关闭 |
| 复用 Mirror runtime | 每个 acceptance Case 生成确定性 `MirrorPlan` 和 `MirrorRun`，复用现有 Graph materialization、Fixture resolver、测试断言、未匹配失败和 payload-free evidence 内核 |
| 分层模拟证据 | Case 层记录 Suite/Fixture/Plan/Evidence exact refs、状态、resolver source、rule refs 和 Proposal 调用次数；Aggregate 层绑定 Proposal/Package/Graph/base/simulated closure、限制和不确定性 |
| 严格证据状态 | 运行结束只派生 `CapabilityProposalSnapshot.evidenceState=SIMULATED`；`implementationBindingRef` 保持 `null`，成功也不会产生 IMPLEMENTED/CONFORMANT/CALIBRATED 事实 |
| Durable exact replay | PostgreSQL/H2 repository 使用完整五段 Scope、Proposal revision、simulation id、command fingerprint、数据库时钟、30 分钟 lease、Case 间续租和 epoch fencing；完成结果重启后原样返回 |
| 认证运行 API | 新增 simulate 与 exact evidence read；分别使用 `MIRROR_REHEARSAL` 和 `GOVERNANCE_EVIDENCE_INGESTION` operation；Controller 只在 test/staging + mirror enabled 时装配 |
| 动态能力发现 | 三类协议对象始终可协商；`businessMirrorProposalSimulation` 与两个 endpoints 只有应用服务真实装配时才广告 |
| 跨语言协议与固定样例 | request/evidence/stored result 三份 strict Draft 2020-12 Schema；固定服务端结果；Test Kit 可离线复算 evidence/Snapshot fingerprint、覆盖、排序、时间和 identity closure |
| 部署与使用文档 | 新增 Proposal simulation 指南，更新作者指南、Resource Gateway/Test Kit README；新增 `V20260814_004` PostgreSQL migration |

### 14.2 运行与证据不变量

```text
authenticate complete Scope + MIRROR_REHEARSAL
  -> durable claim(Scope, proposalId, revision, simulationId, requestFingerprint)
  -> resolve exact Proposal + Package + Graph + base closure
  -> verify exact direct Suites + Fixtures and isolation policy
  -> overlay one temporary SIMULATION_ONLY capability and reseal ancestors
  -> for each Case: renew lease -> create exact MirrorPlan -> execute MirrorRun
                    -> read signed payload-free MirrorEvidenceBundle -> renew lease
  -> verify structural suite coverage and Proposal call count
  -> seal aggregate -> sign and immediately verify
  -> derive SIMULATED ProposalSnapshot
  -> renew + epoch-fenced durable complete
```

关键边界：

1. Proposal target 是本次 Graph context 中的 exact external Capability，不会覆盖全局 Capability registry。
2. Temporary Capability 使用 `SIMULATION_ONLY` runtime binding；其内容地址进入 simulated closure 与每个 MirrorPlan。
3. Case input 只进入瞬时 Mirror execution context；Aggregate 不存 input、output、node value 或 Fixture return payload。
4. `PASSED` 要求全部 Case 的 Mirror status 为 `PASSED` 且 Proposal temporary capability 至少被调用一次。套件全部通过但没有触达候选能力时 Aggregate 为 `FAILED`。
5. 同一个 Proposal exact revision 只有一个权威模拟结果。改变 Package、Graph、target 或 suite/fixture material 时必须先保存新 Proposal revision。
6. Aggregate attestation 的结构与 material binding 可离线复验；签名来源真实性仍由部署 evidence trust/key set 验证，Test Kit 不伪造部署信任。

### 14.3 并发、恢复和数据库语义

1. 主键是完整 Scope + Proposal id + Proposal revision；simulation id 在完整 Scope 内唯一，杜绝跨组织碰撞。
2. `INSERT ... ON CONFLICT DO NOTHING` 避免 PostgreSQL 唯一约束异常把当前事务标成 aborted；竞争副本随后锁定同一行并返回 `IN_PROGRESS` 或 exact completed result。
3. lease 使用数据库时钟，不依赖副本系统时钟；每个 Case 前后及最终提交前续租。
4. epoch、owner 和当前 lease expiry 共同参与 renew/release/complete 条件，旧 worker 不能覆盖接管者结果。
5. 失败释放只保存有界 reason code，不保存异常、业务 payload 或凭据。
6. runtime fallback DDL 与正式 migration 都使用 H2/PostgreSQL 兼容的 `TEXT`；原生 PostgreSQL 测试同时执行 migration 和 repository `init()`。

### 14.4 开发红灯与根治

| 红灯 | 病根 | 根治与回归保护 |
|---|---|---|
| Full Spring context 首次无法启动 | 新测试遗漏 durable quarantine 的 claim/request key 测试配置，触发既有安全边界 fail closed | 使用与完整 runtime integration test 相同的两组 key-ring、write mode 和 rollout identity；保留完整 Spring 装配测试 |
| unsafe Contract 测试预期错误 | 测试假定 readiness blocker 总先于运行准入，但该构造的 draft blocker 为空，真正拒绝来自运行时 read-only/secret policy | 断言稳定的安全拒绝原因，不伪造不存在的 blocker |
| PostgreSQL fallback DDL 隐患 | repository fallback 表使用 H2 `CLOB`，且捕获 duplicate key 后继续查询会使 PostgreSQL 事务处于 aborted 状态 | 改为双数据库 `TEXT`，以 `ON CONFLICT DO NOTHING` 完成无异常仲裁；加入原生 PostgreSQL 双副本并发认证 |
| 大闭包收集潜在二次复杂度 | changed snapshot 以旧 ref 索引，收集新 ref 时逐项扫描 rewritten values | 构建 materialized ref index，closure collect 变为 O(V+E) |
| 长批次可能丢失提交权 | 初版只在运行开始领取 30 分钟 lease，多 Case 批次可能超过 lease | 增加数据库时钟续租，Case 前后和 final commit 前检查；续租失败立即拒绝提交 |

### 14.5 自动化验证

| 范围 | 结果 | 证明内容 |
|---|---|---|
| Simulation focused server suite | `53/53` 通过 | overlay/reseal、unsafe/target rejection、service composition、payload omission、exact replay、lease renew/fence、Controller operation、Spring profile wiring、capability probe regression，以及生产行为与 real fallback 双重 fail-closed |
| Native PostgreSQL certification | `1/1` 通过 | migration + fallback DDL、两个独立 DataSource/transaction manager 并发 claim、唯一行和续租 |
| Test Kit Business Mirror suite | `23/23` 通过 | 三份新 Schema、服务端固定结果、evidence/Snapshot fingerprint、identity closure、tamper rejection、严格 request kinds |
| Resource Gateway | `5993` tests，`0` failures，`0` errors，`13` skipped | 完整 `clean verify`、原生 PostgreSQL、真实 Chromium E2E 与可执行 Spring Boot JAR 全绿 |
| Resource Gateway Test Kit | `549` tests，`0` failures，`0` errors，`0` skipped | 完整 `clean verify`、Schema packaging、shade、Javadoc 与 JAR 全绿 |

服务端与 Test Kit 的完整发布门禁均通过；跳过项是既有环境条件测试，不包含本迭代新增测试。

### 14.6 架构漂移审计

1. Proposal simulation 只编排现有 Proposal、Package compilation fact、Graph Authority、Fixture/TestSuite registry 和 Mirror runtime，没有成为这些对象的新 Authority。
2. Overlay compiler 是纯函数，不更新 DSL、Graph registry、Capability registry 或 compiled Package。
3. 每个 Case 通过现有 `MirrorPlanIntegrationService` 和 `MirrorRunIntegrationService` 执行，没有复制 resolver、测试运行或 evidence signing 内核。
4. HTTP 只负责认证和传输；事务、lease、运行编排、纯编译和 protocol records 保持独立。
5. ANEKE 的 registry、publish gate、owner approval 和 TEE 治理未被引入 Resource Gateway；Aggregate 只输出可供治理消费的模拟事实。

### 14.7 差距复评

BM-007 关闭了 Proposal 只能编辑但不能试跑、候选 Contract 无法进入真实 Graph context、Suite/Fixture exact refs 未解析、模拟结果无持久身份和外部消费者无法辨别 SIMULATED/IMPLEMENTED 的主要缺口。

风险加权差距由约 `12%` 降至约 `10%`。剩余差距主要集中在：实现绑定与同源 Conformance diff（BM-008）、L0-L3 reverse impact（BM-009）、Package evidence/Fidelity 聚合（BM-010）、生产 Outcome/Regional Data Plane/HA-DR 认证（BM-011/012/013）、ANEKE 持续集成（BM-014）和真实取消费域试点（BM-015）。此外，V1 仍只支持 built-in Graph、direct TestSuite 和只读无状态候选能力，默认 demo 不伪造客户 Authority 数据。

下一迭代进入 BM-008：建立 Proposal implementation binding、SDK/runtime port 和同源 acceptance suite conformance report，保证“模拟通过”与“真实实现通过”是两个可比较但不可混淆的证据阶段。

## 15. Iteration 8A：BM-008 implementation binding

### 15.1 已交付

| 交付 | 结果 |
|---|---|
| Runtime-owned port | `CapabilityImplementationRuntimePort` 将部署方 Descriptor/调用适配器与 Proposal、HTTP 和 repository 解耦；默认实现物理失败关闭 |
| Exact binding command | 请求固定 Proposal draft fingerprint、PASSED simulation evidence、target Capability、runtime port generation 和 implementation generation，不接受 `latest` |
| Server-attested binding | 服务端重新读取 Proposal/Simulation/Contract/Runtime authority，派生 Scope、Region、Owner、安全属性、有效期和 content address，再签发 detached attestation |
| Durable immutable repository | 完整五段 Scope + binding id；相同材料 exact replay，不同材料冲突；JSON 与索引列、fingerprint 和签名读取时复验 |
| 认证 API | test/staging 下提供 bind/read；固定用途白名单，调用方不能以自报 purpose 绕过应用服务授权 |
| PostgreSQL 协议 | `V20260814_005` 提供部署 DDL；原生 PostgreSQL 两独立连接并发写只产生一条绑定事实 |
| 独立协议 | 三份 strict Schema、payload-free fixed fixture、Test Kit canonical fingerprint/region/time/attestation closure verifier |
| 诚实能力探针 | 区分对象协议、binding API 和 customer runtime adapter readiness；默认演示不会假装安装客户实现 |
| 接入文档 | [实现绑定与交付接入指南](resource-gateway-business-mirror-implementation-binding-guide.md) 说明 Adapter、命令、验证、迁移和失败语义 |

### 15.2 不变量

1. 绑定不能由调用方单方面声明：Proposal、Simulation 和 runtime Descriptor 都由服务端 Authority 重新读取。
2. 只有 `COMPLETED + PASSED` 的 exact Simulation 才能进入绑定；target 必须与模拟证据完全一致。
3. V1 只允许当前 Region 内、只读、无状态、未过期的实现；Descriptor 或 Contract 漂移失败关闭。
4. Binding repository 不保存实现输入、输出、凭据或业务 payload，只保存身份、内容地址和证明材料。
5. `binding created` 不等于 `CONFORMANT`。本迭代不晋级 Proposal Snapshot，也不产生实现试跑通过事实。

### 15.3 验证与开发红灯

聚焦门禁覆盖领域安全、服务 exact closure、runtime/signer unavailable、purpose 白名单、H2 repository、Controller、capability probe、strict Schema 和跨 JVM fixture。原生 PostgreSQL 门禁额外执行 V005 并让两个独立 repository 竞争同一绑定。

| 范围 | 结果 | 证明内容 |
|---|---:|---|
| BM-008A 服务端聚焦门禁 | `20/20` 通过 | exact binding、runtime drift、过期、用途白名单、H2/PostgreSQL、Controller、Spring 与 capability probe |
| Resource Gateway Test Kit | `550` tests，全部通过 | strict Schema、固定 fixture、canonical fingerprint、Scope/Region/time 和 attestation closure；JAR、shade 与 Javadoc 门禁通过 |
| Resource Gateway | `6006` tests，`0` failures，`0` errors，`13` skipped | 完整 `clean verify`、原生 PostgreSQL、真实 Chromium E2E 和可执行 Spring Boot JAR 全绿 |

实现过程中主动发现并根治四处边界问题：初版读取服务把 `identity.purpose()` 同时当作期望用途和实际用途，形成直接服务调用时的自证授权风险；现已改为固定三项白名单并添加反例。Spring 完整装配门禁发现 repository 被声明为 `final`，导致 `@Transactional` 无法代理；已恢复可代理类型并保留上下文测试。数据库复核发现查询只取 JSON，无法发现索引列被篡改；现已交叉核对 Proposal、revision、request fingerprint 和时间列。原生 PostgreSQL 认证不复用 H2-only demo signer DDL，只对 V005 repository 使用已签名的 payload-free test seal。固定 fixture 的 fingerprint 使用与服务端一致的排序 canonical JSON 独立生成，并由 Test Kit 从 JAR 资源重新计算，而不是复制服务端 verifier。

### 15.4 架构漂移审计

1. Runtime port 归客户部署实现，Resource Gateway 只拥有 binding 和后续 Conformance 编排，不接管业务实现 Registry。
2. Binding 精确引用现有 Proposal/Simulation/Capability/Contract 事实，没有复制或改写它们。
3. 默认 unavailable adapter 与动态 readiness 分离，协议存在不会被误报为客户实现可运行。
4. Test Kit 继续不依赖 Spring 或服务端 artifact；部署 signer trust 仍由独立 key-set 验证承担。

### 15.5 差距复评

BM-008A 关闭了“实现只有一个 URL/名称、无法绑定评审代次”“模拟证据与实现代次可错配”“客户 runtime 未安装却被误报 ready”和“绑定结果不能跨语言复验”的问题。风险加权差距由约 `10%` 降至约 `9.5%`。

该差距在随后完成的 BM-008B 中关闭：实现运行复用原 acceptance suite、Case、Fixture 和 baseline evidence，只把 Proposal target invocation sites 反转到 exact implementation binding，并形成 payload-free、签名、durable、可重放的结构化报告。具体实现与剩余边界见下一节。

## 16. Iteration 8B：BM-008 same-suite implementation Conformance

### 16.1 已交付

| 交付 | 结果 |
|---|---|
| Target-only plan derivation | 从 accepted `CompiledMirrorPlan` 派生 Conformance plan；只将 Proposal target sites 改为 `REAL`，其余 fixture/replay/corpus/clock/random 保持冻结 |
| 隔离拒绝规则 | 无 target、共享规则、operator ref 跨职责复用、embedded target、非 target REAL/SPY/fallback、控制未解析或 runtime coordinate 不唯一均失败关闭 |
| Runtime exact adapter | `ConformanceOperatorRegistry` 每次调用前重新核验 Descriptor、binding 与 expiry，只记录调用次数和 site，不留存 payload |
| 跨运行行为指纹 | 新增 payload-free observable behavior projection；保留节点/边坐标、输入输出指纹、状态、错误与 attempt，归一化预期的 fixture/real 执行机制差异 |
| Same-suite execution | 逐一读取原 Simulation 的 exact Suite/Case/Fixture/MirrorPlan/baseline evidence，使用原输入和断言执行真实实现 |
| 可解释报告 | 每个 Case 同时携带 baseline/implementation 完整语义指纹、行为指纹、调用次数、site、断言结果摘要和稳定 mismatch reason |
| 单调 Proposal 状态 | 全部 Case `MATCH` 才生成 `CONFORMANT` Snapshot；失败报告仍签名持久化，但 Snapshot 保持 `IMPLEMENTED` |
| Durable lease authority | 五段 Scope + binding revision 主键、conformance id 唯一、数据库时钟、lease epoch fencing、exact replay；完成重试不重复调用实现 |
| 认证 API 与能力探针 | 提供 conform/read endpoint；对象协议、API 装配与客户 runtime readiness 分开声明 |
| 独立协议闭环 | 四份 strict Schema、完整 stage-1 fixture 和 Test Kit verifier；重算 evidence/report/snapshot 三层指纹及状态、覆盖、attestation closure |
| PostgreSQL 与接入文档 | `V20260814_006` 通过双副本并发认证；[Conformance 指南](resource-gateway-business-mirror-implementation-conformance-guide.md)覆盖操作、Diff、恢复与边界 |

### 16.2 关键不变量

1. Conformance 不能重新选择 `latest` Suite、Fixture、Plan、baseline evidence 或 implementation generation。
2. Proposal target 是唯一允许触达 runtime port 的职责边界；非 target 外部依赖不能借 Conformance 逃逸到真实系统。
3. 完整测试语义指纹不直接判等，因为 purpose、plan、fixture consumption 和 fidelity 必然不同；跨运行行为投影必须显式版本化且 payload-free。
4. `MATCH` 同时要求 baseline/implementation 通过、行为指纹一致、target 调用次数/site 一致和实现断言全绿。
5. exact retry 返回已持久化结果，不再次调用客户实现；旧 lease 不能完成新 epoch。
6. `CONFORMANT` 只证明声明范围内的同套件一致性，不能越级成为 `CALIBRATED` 或生产 Outcome 证据。

### 16.3 实施中发现并根治的问题

初版直接比较共享测试内核的 `semanticResultFingerprint`。该指纹有意包含执行 purpose、plan fingerprint、fixture consumption 和 fidelity，因此 baseline 的 `MIRROR_REHEARSAL/FIXTURE` 与实现的 `CAPABILITY_CONFORMANCE/REAL` 即使业务输出完全相同也一定不等。问题根因不是样例，而是把“执行身份”误当成“跨机制业务行为”。现已保留双方原语义指纹用于审计，另引入版本化 behavior projection 负责跨机制判等，并以等值/值漂移反例锁定。

第二个问题是只替换 `OperatorRegistry` 不能使真实实现生效：测试运行时执行的是 plan 中冻结的 invocation inventory。现由 compiler 同时替换 target site 的 frozen operator，防止计划指纹、预检对象与实际 delegate 不一致。

第三个问题由全量架构守卫发现：Conformance compiler 初版直接持有 testing runtime 内部的 `GovernedExecutionServices`，功能虽然正确，却让业务镜像层获得了状态化 provider 句柄。根因是缺少“冻结服务投影 → 绑定 plan → 组装 control”的公开规划边界。现新增一次性 `ExecutionControlPreparation`，调用方只能读取 payload-free binding，并只能消费一次完成 exact plan 绑定；架构守卫保持原规则，不增加例外名单。

Test Kit 首次 `verify` 还在 552 个行为测试全绿后被 Javadoc 门禁拒绝。新增离线校验 API 当时只有摘要，缺少参数、返回值和失败语义。现已补齐可由 IDE 直接展示的公共契约，Javadoc 门禁继续启用。

### 16.4 验证与架构漂移审计

聚焦门禁覆盖 behavior normalization、target-only plan、共享规则拒绝、一次性执行服务边界、服务 exact closure、H2 lease/fencing、Controller、capability probe、Spring Bean、strict Schema、tamper、固定 fixture 和原生 PostgreSQL 双副本竞争。

| 工程 | 命令 | 结果 |
|---|---|---|
| Resource Gateway | `mvn -f resource-gateway-examples/pom.xml clean verify` | `6018` tests，`0` failures，`0` errors，`13` skipped；原生 PostgreSQL、真实浏览器 E2E、架构守卫和 Spring Boot 可执行 JAR 打包通过 |
| Resource Gateway Test Kit | `mvn -f resource-gateway-test-kit/pom.xml clean verify` | `552` tests，`0` failures，`0` errors，`0` skipped；strict Schema packaging、shade、Javadoc 和 JAR 打包通过 |

`13` 个 skipped 均为仓库既有的环境条件跳过，不是本轮失败降级。

架构未引入平行执行引擎：Conformance 复用 `MirrorPlanIntegrationService`、`ExecutionControlCompiler`、`TestRunService`、Fixture/Suite repository、Mirror evidence 与现有 signer。`materializeForConformance` 是固定 purpose、仅 test/staging 的内部权限桥，不对 HTTP 暴露 sealed Graph 或 payload。客户实现继续归 runtime-owned port；Resource Gateway 只拥有编排、证据与状态推进。

### 16.5 差距复评

BM-008 完整关闭了“模拟通过后没有精确实现身份”“真实实现无法复用原验收分母”“fixture 与实现职责串线”“结果不可解释、不可重放、不可离线复验”的仓库内工程差距。风险加权差距由约 `9.5%` 降至约 `8.5%`。

降幅仍受生产边界约束：V1 仅覆盖只读、无状态候选实现；尚无 L0-L3 reverse impact（BM-009）、Package Evidence/Fidelity 聚合（BM-010）、生产 Outcome/Regional Data Plane/HA-DR 认证（BM-011/012/013）、ANEKE 持续集成（BM-014）和真实取消费域试点（BM-015）。下一迭代进入 BM-009。

## 17. Iteration 9：BM-009 L0-L3 reverse impact

### 17.1 已交付

| 交付 | 结果 |
|---|---|
| 确定性传递内核 | `BusinessAssetImpactProjection` 从已封存 `BusinessAssetLinkClosure` 计算每个 exact source 到全部 downstream target 的 depth、path count、highest risk 和稳定代表路径；拒绝超过 `4096` 个 target 的单源爆炸 |
| 权威/派生解耦 | Package 编译事务只追加 immutable facts、projection outbox command 和 `DOMAIN_CAPABILITY_PACKAGE_SNAPSHOT_COMPILED`；索引计算失败不撤销权威 Snapshot |
| Durable projection outbox | 完整五段 Scope + Package/revision 主键；数据库时钟、owner、epoch、expiry、attempt、指数退避和第 `8` 次 quarantine；只保存稳定失败码 |
| 跨副本 worker | 每次短事务领取一个租约；impact rows、current head、`BUSINESS_ASSET_IMPACT_CHANGED` 和 job completion 在第二个事务原子提交；重放不重复事件 |
| 追加索引与 current head | 历史 projection 不删除；query 只 join 每个 Package current head；相同 revision/fingerprint exact replay，旧 revision 与同坐标事实漂移失败关闭 |
| Freshness 与重建 | query 对比最新 immutable Package Snapshot，返回 `CURRENT/STALE`、有界 stale Package inventory 和 `projectedThrough`；维护 API 从 immutable facts 有界重建 |
| 认证 API | 作者侧和 Integration 侧 impact read；独立 maintenance rebuild；selector 支持 logical kind/id + optional authority，多 revision 返回 exact matches |
| 严格报告协议 | report/rebuild 两份 Draft 2020-12 Schema；服务端对象拒绝超页、重复 coordinate、断裂路径、错误 risk summary、Scope 漂移和 cursor/count 矛盾 |
| 独立消费者 | Test Kit 复算 canonical fingerprint，并验证 exact Snapshot/Closure/source/path/Deep Link、排序、freshness 与 rebuild 不变量 |
| 完整业务样例 | `trip-api-impact-stage1-v1.fixture.json` 从 L0 Resource 经 Operator、L1 Solution、L2 Workflow 到 L3 Channel，包含四个下游 path 和可回画布的准确坐标 |
| Deep Link UX | `/business-mirror/` 解析 Package compilation 与 asset kind/id/revision/authority，直接打开 Capability Map；只对完整 exact coordinate 高亮，不把缺 revision 的 Legacy ref 当作命中 |
| 部署与运维 | `V20260814_007`、能力探针、默认/test/staging maintenance purpose、worker 配置、查询/重建/排障/Test Kit/启停操作指南 |

### 17.2 一致性和失败语义

```text
Package compilation transaction
  -> append Readiness / Link Closure / optional Snapshot
  -> enqueue exact impact projection coordinate
  -> append SNAPSHOT_COMPILED event
  -> append exact compile receipt
  -> commit once

projection worker transaction
  -> load exact immutable compilation receipt
  -> verify Snapshot fingerprint and Scope
  -> compile deterministic transitive impact
  -> append projection rows + advance current head
  -> append IMPACT_CHANGED event
  -> epoch-fenced job completion
  -> commit once
```

该结构修正了实现中途识别出的一处架构漂移风险：初版让反向索引在 Package 编译事务中同步计算，虽然强一致，却会让可重建投影的数据库、容量或代码故障回滚权威 Snapshot。最终实现保留事务 outbox admission 强保证，把投影计算和权威写入拆开，并通过显式 Freshness 让 eventual consistency 对消费者可见。

以下不变量已进入代码和测试：

1. outbox command 与 Snapshot 在同一编译事务提交，不能出现已提交 Snapshot 却完全没有投影意图。
2. projection failure、worker crash 或响应丢失不会修改或删除 Snapshot；lease 到期后新 epoch 可接管。
3. 旧 owner 不能完成新 epoch；projection 已提交但 job completion 重试时，projection exact replay 且不重复 `IMPACT_CHANGED`。
4. 相同 Package compilation revision 不能绑定不同 Snapshot 或 Closure fingerprint。
5. report 若 `STALE` inventory、Scope、selector、path、risk、Deep Link 或 fingerprint 不闭合，服务端和 Test Kit 均失败关闭。
6. `STALE` 结果可用于排障，但 ANEKE publish gate、破坏性变更批准和完整回归分母选择不得消费为 current fact。

### 17.3 实施中发现并根治的问题

| 红灯或审计发现 | 病根 | 根治与回归保护 |
|---|---|---|
| 同步 projection 会拖垮 Package compile | 把派生视图误放进权威写事务 | 改为 transaction outbox + leased worker；Freshness/rebuild 保持索引可重建 |
| 默认演示 rebuild 返回 403 | endpoint 和 capability 已存在，但默认 purpose allowlist 未登记 maintenance | default/test/staging 明确加入 `BUSINESS_MIRROR_MAINTENANCE`；指南按真实启停脚本操作 |
| 前端行为测试通过但发布构建失败 | TypeScript `BusinessMirrorAssetRef` 漏了 wire protocol 已要求的 `authority`，映射返回类型也落后 | 修正领域类型和 capability ref projection；保留 `tsc --noEmit` 发布门禁 |
| Deep Link 可能高亮同 id 的旧逻辑 ref | UI 只比较 kind/id，忽略 revision/authority | exact 四元坐标匹配；不完整 Legacy ref 与精确目标并列显示，只高亮后者 |
| PostgreSQL 竞争测试出现 NPE | 数据库正确返回一份 lease + 一份空结果，测试却用拒绝 null 的 `List.of` 收集 | 使用允许空占位的测试集合，继续严格断言只有一个 replica 获得租约 |
| worker 控制库不可用时会继续空转当前 batch | drain 只把 `NO_WORK` 当停止信号 | `CONTROL_UNAVAILABLE` 立即结束当前批次，避免一次调度放大数据库故障 |
| 完整 capability 端点全集断言落后 | 新 API 已进入 capability payload，但固定全集仍停在 BM-008 | 把 impact read/integration read/rebuild 三个端点纳入 exact-set 门禁，防止服务声明与消费者预期漂移 |
| 正向 observation 集成夹具在整库压力下偶发 quarantine | 夹具把端到端命令计时与供应方最大响应都压到 `100 ms`，把数据库准备时间误算成供应方超时 | 正向路径改为同一租约内的 `800 ms` confirmation window 与 `400 ms` provider budget；专用 `100 ms` timeout 测试继续固定严格失败语义 |
| 260 节点真实浏览器用例在整库尾段被通用时限中断 | 该用例串行覆盖服务端分页、键盘窗口、四维筛选、清空、移动视口，复杂度显著高于普通 DOM 用例 | 仅该方法使用 `150 s` 上限；其余真实浏览器用例继续执行类级 `90 s` 门禁，避免整体放宽掩盖性能回退 |
| Test Kit 语义测试全绿但发布失败 | 新公共 verifier record 与 rebuild helper 缺少完整 Javadoc 参数契约 | 补齐 13 个公共 API 文档项并保留 `failOnWarnings`，保证独立消费者的 DX 与二进制产物同时达标 |

### 17.4 自动化验证

| 范围 | 结果 | 证明内容 |
|---|---:|---|
| 服务端 impact 聚焦门禁 | `37/37` 通过 | 传递、上限、不变量、H2 current/stale/replay/tamper、outbox lease/retry/quarantine、service/event、worker、Controller、Spring HTTP、maintenance rebuild 和 capability probe |
| 原生 PostgreSQL 14 | `1/1` 通过 | V002 + V007、`fsync=on`、`synchronous_commit=on`、两个独立 DataSource 竞争唯一 lease、projection/head/job completion 与查询 |
| Test Kit Business Mirror | `30/30` 通过 | 两份 strict Schema、完整 L0-L3 fixture、fingerprint、path/Deep Link/freshness/tamper/rebuild 语义 |
| Frontend production build | 通过 | i18n `34`、UX `40`、host `13`、TypeScript、Vite `1869` modules 和 route chunk budget 全绿；Business Mirror startup closure `171.58 KiB` |
| 实机 Deep Link 视觉检查 | 通过 | 完整演示 JAR 在真实浏览器直接打开 exact impact URL；桌面唯一高亮 `trip-api / RESOURCE r3 / customer-registry / compilation r7`，390px 文档宽度等于视口、控制台零错误、页面无横向滚动 |
| Resource Gateway 完整发布门禁 | `6039` 项，`0` failure，`0` error，`13` skipped | 干净编译、全部 H2/原生 PostgreSQL/事务/认证/协议/真实浏览器回归和 Spring Boot JAR 打包 |
| Test Kit 完整发布门禁 | `556/556` 通过 | `134` 个主源码、`89` 个测试源码、`38` 份 Business Mirror 资源、shaded JAR 和零警告公共 API Javadoc |

两项完整结果均来自 `clean verify`，聚焦结果只用于快速定位，不能替代发布门禁。Test Kit 的首次完整运行因新增公共 API Javadoc 缺项被门禁拒绝；补齐文档后从干净目录重跑通过，没有关闭或降低该门禁。

### 17.5 架构漂移审计

1. 反向索引只从 immutable `BusinessAssetLinkClosure` 构建，不解析 DSL、不读取 mutable Registry、不成为 L0-L3 新 Authority。
2. Graph edge 继续表达执行依赖，Business Asset Link 继续表达业务关系；没有把两类边混成一个万能 DAG。
3. report 只返回 exact refs、路径结构、risk summary 和 Deep Link，不复制 Package、Fixture、input/output 或业务 payload。
4. Resource Gateway 负责 impact projection 和 authoring context；ANEKE 继续拥有 Registry、publish gate、breaking migration 和治理批准。
5. Deep Link 只提供定位，不绕过企业 Scope 或授权检查。
6. H2 runtime DDL 只服务示例启动；正式 PostgreSQL migration 与原生认证独立存在，本地通过不冒充 HA/DR。

### 17.6 差距复评

BM-009 关闭了“L0-L3 关系只有正向 Closure、无法从变更反查下游业务资产”“索引落后不可见”“影响结论没有 exact Snapshot/Closure 证据”“治理告警不能回到画布上下文”和“独立消费者无法验证报告”的仓库内工程差距。

风险加权差距由约 `8.5%` 降至约 `7.5%`。降幅保持克制：尚缺 Package Evidence/Fidelity 聚合与 drift task（BM-010）、生产 Outcome Connector（BM-011）、Regional Data Plane（BM-012）、PostgreSQL HA/kill/partition/upgrade/backup certification（BM-013）、ANEKE protocol 1.1 持续集成（BM-014）和真实取消费域试点（BM-015）。当前 PostgreSQL 证据只证明单进程原生数据库的双连接竞争，不证明客户生产拓扑。

下一迭代进入 BM-010：建立 Package Evidence Index 与多维 Portfolio Fidelity projection。每个结论必须回到 exact source；freshness、confidence、abstention 和 denominator 分开显示，禁止用单一总分掩盖未知范围。

## 18. Iteration 10：BM-010 Package Evidence/Fidelity

### 18.1 已交付

| 交付 | 结果 |
|---|---|
| 五层 Evidence Index | Operator、Graph、Scenario、Carrier 和 Outcome 五层分别保留结论、exact source、freshness 和限制，不允许低层通过替代高层业务证明 |
| 七维 Fidelity | Contract、Branch、State、Fault、Temporal、Outcome 和 Drift 独立报告 required/pass、coverage、Wilson interval、abstention 与 source lineage；协议和 UI 均禁止综合分数 |
| 可重建持久投影 | 从 immutable Package compilation fact 与现有 Domain Fidelity authority 生成；事务 outbox、数据库时钟 lease、epoch fencing、退避、quarantine、append-only revision 和 current head 完整闭合 |
| Domain Portfolio | 按 domain 分页聚合当前 Package 摘要与活跃债务，不复制业务 payload；Portfolio、Evidence Index 和 Owner Task 三者做 exact Package/revision/fingerprint 闭合 |
| Owner Task journal | drift/debt 派生 `OPEN` 任务，支持 optimistic acknowledge 和携带 exact resolution evidence 的 resolve；任务状态变化不修改 Evidence 事实 |
| 认证 API 与动态探针 | Authoring 与 Integration 同义路由、固定 purpose、完整 Scope；协议版本始终可发现，运行 API 仅在受保护服务真实装配时为 `true` |
| 生产隔离 | Package Evidence 服务、worker 和 Controller 只在 `test/staging + gateway.testing.mirror.enabled=true` 物理装配；production 与混合 profile 即使误设开关也失败关闭 |
| 跨语言协议 | 三份 strict Schema、两份 server-produced fixed fixture、Test Kit 离线 verifier 和公开 Java API Javadoc；拒绝篡改、综合分数、错误 revision closure 和不一致 Portfolio |
| 七步产品体验 | Business Mirror 新增“验证证据”任务，展示五层证明、七维保真度、活跃任务、刷新和接手；404 时可打开明确隔离、只读且不写库的参考证据 |
| 运维与接入文档 | [Package Evidence/Fidelity 指南](resource-gateway-package-evidence-and-fidelity-guide.md)覆盖启停、能力探针、HTTP、任务生命周期、PostgreSQL、Test Kit、安全与故障排查 |

### 18.2 实施中发现并根治的问题

| 红灯或审计发现 | 病根 | 根治与回归保护 |
|---|---|---|
| Portfolio 曾把健康 Profile 与不相关任务组合 | 聚合只按 domain 汇总，没有核对 Package/revision/fingerprint 语义闭包 | Portfolio 构造和 Test Kit 同时复验 exact closure；增加真实债务样例固定 OPEN task |
| 固定 fixture 比较把 JSON 整数节点实现差异当成协议漂移 | `IntNode` 与 `LongNode` 的 Java 类型差异被误当成 wire value 差异 | fixture 兼容门禁改为递归 JSON 数值语义比较，同时继续严格比较字段、数组顺序和字符串 |
| 移动端七维表格与任务导航不易定位 | 宽表依赖页面滚动，deep-linked 第六步超出首屏任务轨道 | 宽表改为局部横向滚动；任务轨道在布局稳定后自动把当前步骤居中，不产生页面级横向溢出 |
| capability probe 在默认关闭时被测试错误要求为 ready | 把“协议存在”与“运行服务已装配”混为一谈，且服务最初缺少 production profile 物理隔离 | 默认上下文断言 API false；完整 test Mirror 上下文断言 true；production/mixed profile 路由与 bean 均为空 |
| Test Kit 行为全绿但发布被 Javadoc 拒绝 | 新 verifier 的三个公开 record 缺字段语义，独立消费者无法仅凭 IDE 理解 revision/fingerprint | 补齐全部 `@param`，保留 `failOnWarnings`；重新执行完整 `clean verify`，不降低门禁 |

### 18.3 自动化与实机验证

| 范围 | 结果 | 证明内容 |
|---|---:|---|
| BM-010 服务端聚焦门禁 | `39/39` 通过 | projector/service/repository/worker/Controller、H2/PostgreSQL、动态 capability、Spring 装配与 production 物理隔离 |
| Test Kit 完整发布门禁 | `558/558` 通过 | strict Schema、server fixture、tamper/closure/禁止综合分数、shade、JAR 和零警告 Javadoc |
| Frontend 聚焦与发布构建 | 通过 | Workspace `6`、locale `13`、i18n `34`、UX `40`、host `13`、TypeScript、Vite `1870` modules 和 route budget 全绿 |
| 真实浏览器 | 通过 | 真实 Spring Boot API + Vite 在 1280/820/390px 验证五层、七维、任务接手与只读参考样例；移动端文档宽度等于视口、局部宽表可滚动、当前任务可见、控制台零错误 |
| Resource Gateway 完整发布门禁 | `6061` 项，`0` failure，`0` error，`13` skipped | 干净编译、整库行为/数据库/浏览器/架构门禁和可执行 JAR 打包通过 |

### 18.4 架构漂移审计

1. Evidence Index 只消费 immutable successful compilation facts 与现有 Fidelity authority，不读取 mutable draft 推导“已证明”结论。
2. 五层证明与七维 Fidelity 分开保存；任何层级、维度、UI 或 Test Kit 都不生成综合成熟度分数。
3. Evidence 是治理输入，不是 Resource Gateway 自建 publish gate；ANEKE 继续拥有 Registry、Workbook、审批和最终发布裁决。
4. Owner Task 是可审计工作流投影；acknowledge/resolve 不能修改 Evidence、Fidelity、Scenario denominator 或 Outcome authority。
5. `supportedObjects` 表示消费者理解协议，runtime feature 表示端点真实可调用；二者不再互相冒充。
6. 前端参考 fixture 明确标记为非当前 Package，只读加载，不写数据库、不创建任务、不触发发布门禁。
7. H2 用于本地演示，原生 PostgreSQL 双连接只证明 repository 语义和租约竞争，不宣称 HA、灾备或客户规模成熟度。

### 18.5 差距复评

BM-010 关闭了“Package 缺少跨 Operator/Graph/Scenario/Carrier/Outcome 的统一证据坐标”“Fidelity 只有技术页、业务 Owner 无法经营债务”“drift 没有可确认、可解决、可审计工作项”“协议存在与运行 API 可用性混淆”和“独立消费者无法复验证据聚合”的仓库内工程差距。

风险加权差距由约 `7.5%` 降至约 `6.5%`。剩余部分几乎全部位于不能由本地功能演示冒充的工业化边界：生产 Outcome Connector 的持续事实供给（BM-011）、真实 Regional Data Plane 与密钥/证书/出口隔离（BM-012）、HA/故障/升级/备份恢复认证包（BM-013）、ANEKE protocol 1.1 与 stale governance projection 闭环（BM-014），以及由客户 Owner 冻结分母并运行完整观察窗的取消费申诉试点（BM-015）。

下一迭代进入 BM-011。实现策略是复用现有 `AuthoritativeOutcomeObservation`、durable inbox、selected population 和 continuous assessment 内核，只新增生产 Connector 的 source contract、durable checkpoint、backfill/revocation 命令、quarantine 与可移植认证证据，不建立第二套 Outcome Authority。

## 19. Iteration 11：BM-011 production Outcome Source

### 19.1 已交付

| 交付 | 结果 |
|---|---|
| Source port 与安全描述 | `AuthoritativeOutcomeSource` 冻结部署所有的 Live baseline、exact position、bounded fetch status 和全真 mTLS/private trust/SPKI/certificate identity descriptor；仓库不提供默认客户实现 |
| 双 Authority 分离 | `AuthoritativeOutcomeSourceAuthorityVerifier` 校验 page/command；既有 `AuthoritativeOutcomeAuthorityVerifier` 继续校验每条 Outcome，Connector 签名不能冒充业务结果正确性 |
| durable checkpoint | 完整企业 Scope、Live/Backfill 独立 cursor chain、数据库时间 owner/epoch lease、staged page、heartbeat、退避、quarantine、terminal state 和 irreversible generation fence |
| 崩溃一致性 | worker 采用 stage/apply/commit；整页先持久化，每条 Observation 通过既有 integrity/inbox exact append，全部 durable 后才推进 cursor；任意中断由下一 owner 重放同一 staged page |
| Backfill 与 revoke 控制面 | authenticate-before-decode、固定 purpose、外部 Authority、命令 expiry、Scope 隔离、idempotent exact replay、payload-free audit 和稳定错误码 |
| 启动与调度 | Live baseline 启动时幂等注册且拒绝倒退；Scheduler 仅显式开启，限制 poller、间隔和 drain；`production`/混合 profile 与保留生产环境名物理失败关闭 |
| 动态能力探针 | protocol 始终可发现；Control API、durable checkpoint、Source、Authority、worker、Scheduler 和 continuous readiness 分别报告，端点只在运行面真实装配时广告 |
| PostgreSQL 迁移与原生认证 | V20260815_002 建立 command/generation/checkpoint/staged-page/lease 索引和约束；真实 PostgreSQL 两连接竞态证明唯一 claim、重启读取 staged page、commit 和 generation revoke |
| 跨语言协议 | Source Page、Control Command、Checkpoint 三份 strict Schema，三份 server-produced fixed fixture，Test Kit 独立 verifier、内容地址/闭合/tamper 门禁和完整公共 API Javadoc |
| 接入与运维文档 | [权威 Outcome Source 指南](resource-gateway-production-outcome-source-guide.md)覆盖责任边界、Adapter、Authority、启动、探针、Backfill、revoke、事故处置、Test Kit 和客户上线清单 |

### 19.2 实施中发现并根治的问题

| 红灯或审计发现 | 病根 | 根治与回归保护 |
|---|---|---|
| 直接在抓取后逐条写 Observation 会形成 cursor 与 inbox 的不确定窗口 | 外部分页 cursor 与内部多条事实提交不是一个事务域，at-least-once 不能单靠内存重试闭合 | 先 durable stage 完整 page，再 exact replay/append inbox，最后 fenced commit cursor；重启测试固定覆盖 stage 后崩溃 |
| Live 与修复数据若共享 cursor，会让迟到修复改写主时间线 | 把「持续事实流」和「有业务授权的历史修复」误建模成同一生命周期 | Live 固定 `streamId=live`；Backfill 使用独立 command、时间窗、stream 和 baseline，不能推进 Live cursor |
| Source transport 签名容易被误用为 Outcome 正确性证明 | 「数据确由连接器发出」与「业务结果真实有效」属于不同 Authority | 保留 page/command Authority 与 Observation Authority 双校验；worker 复用既有 Outcome integrity，不新建旁路准入 |
| 服务具有协议类时 capability 容易被误报 ready | 静态版本支持与客户 Adapter、Authority、Scheduler 的当前可用性混在一个布尔值 | 七个独立 runtime facts 加 `continuousReady` 合取；运行路由只在 Control service 装配后广告 |
| Source page 不能直接复用最终 Observation Schema | Source page 内的 Observation 尚未经过 Resource Gateway Authority 签名，最终 Schema 会错误要求 RG seal | Source Page Schema 显式定义 addressed-but-unsigned candidate；worker 准入时才由既有 integrity 签名；禁止放宽最终 Observation Schema |
| 固定 fixture 比较再次遇到 JSON `IntNode/LongNode` 差异 | Java 节点实现差异不等于 JSON wire value 差异 | 服务端 fixture 门禁使用递归数值语义比较，仍严格比较字段闭包、数组顺序、字符串和 fingerprint |
| 审计若晚于 purpose/env 校验，拒绝尝试不可见 | 只围绕成功业务分支启动审计，前置授权失败没有控制面证据 | 完整身份建立后立即创建 operation observation，再执行 purpose、Scope 和环境拒绝；成功与失败均 payload-free 收口 |

### 19.3 自动化验证

| 范围 | 结果 | 证明内容 |
|---|---:|---|
| Source kernel 聚焦门禁 | `72/72` 通过 | page/command 协议、repository、worker、control service、decoder/controller、bootstrap、scheduler、capability、profile/routes 与 H2 crash replay |
| PostgreSQL / Schema / Spring 最终聚焦 | `5/5` 通过 | 双连接唯一 claim、staged page 重启恢复、commit/revoke、三类服务端 fixture、生产装配隔离和连续 readiness |
| Test Kit 完整发布门禁 | `562/562` 通过 | `202` 份 Mirror 资源打包、Schema closure、page/command/checkpoint verifier、tamper、shaded JAR 和零警告公共 API Javadoc |
| Resource Gateway 完整发布门禁 | `6100` 项，`0` failure，`0` error，`13` skipped | 干净编译、整库行为/数据库/真实浏览器/架构门禁、三类 Source 协议与可执行 JAR 重打包通过 |

### 19.4 架构漂移审计

1. Source worker 只向既有 `AuthoritativeOutcomeInboxRepository` 准入 Observation，没有建立平行 Outcome repository、Fidelity projector 或业务真相源。
2. Connector Adapter 与 Source Authority 由客户部署拥有；Resource Gateway 只拥有 checkpoint、运行编排、协议和证据，不接管客户数据 Registry 或密钥治理。
3. Live baseline、Backfill command、Source page、checkpoint 和 Observation 使用 exact ref/content address 连接，没有让 raw cursor 或 Payload 穿过控制面。
4. `production` profile 不装配 Mirror Source；所谓「生产 Outcome」指事实来自客户生产 Authority，并在隔离 staging Mirror 域被只读摄取，不表示 RG 进入生产交易链路。
5. Protocol support 与 runtime readiness 分离；本地 fixed fixture 和测试 Adapter 不会让客户 Connector 被宣称为 ready。
6. 当前 PostgreSQL 认证证明单 PostgreSQL 进程的两个连接边界，不证明多节点 HA、网络分区、备份恢复或跨区域能力。

### 19.5 差距复评

BM-011 关闭了仓库内「生产事实没有持续 Source port」「cursor 与 inbox 崩溃窗口不闭合」「Live 与 Backfill 串线」「generation 无不可逆撤销」「Source 与 Outcome Authority 混淆」「外部消费者不能独立复验」六类工程缺口。

风险加权差距由约 `6.5%` 降至约 `5.5%`。这不是客户生产 Connector 已认证的声明：实际 Adapter、Source Authority、Observation Authority、数据授权、业务 watermark SLO 和目标源断流/迟到/冲突演练只能在客户环境完成。仓库剩余高权重差距为 Regional Data Plane 与私有 PKI/KMS/egress 隔离（BM-012）、HA/kill/partition/upgrade/backup certification（BM-013）、ANEKE protocol 1.1 与 stale governance projection（BM-014），以及真实取消费域 Owner 冻结分母和完整观察窗试点（BM-015）。

下一迭代进入 BM-012。目标不是再造一个业务运行平台，而是把 Vault、Secret、State、Resolver、KMS、mTLS 和 egress policy 组合为可验证的 regional deployment contract，并让缺失、陈旧、rotation 中断和 write escape 失败关闭。

## 20. Iteration 12：BM-012 Regional Data Plane certification

### 20.1 已交付

| 交付 | 结果 |
|---|---|
| 七组件部署契约 | `EVIDENCE_KMS`、`PAYLOAD_VAULT`、`SECRET_AUTHORITY`、`SESSION_STATE_STORE`、`FIXTURE_RESOLVER`、`MUTUAL_TLS` 和 `EGRESS_ISOLATION` 必须在同一 Scope、region、environment、deployment 下完整闭合；缺一项即拒绝认证 |
| 短期外部认证 | `RegionalDataPlaneCertification` 由部署方 Authority 出具，最长存活 15 分钟；绑定 exact contract、七项组件观测、KMS/CA rotation、零 write attempt/escape 与外部 Ed25519 seal |
| 原子 material source | `RegionalDataPlaneCertificationMaterialSource` 是客户 Adapter port，一次读取同一观测窗口内的 contract、certification 和 key set，避免跨次读取产生 TOCTOU；仓库不提供冒充客户基础设施的默认实现 |
| 运行期三次复核 | `RegionalDataPlaneCertifiedRunTrustAuthority` 组合既有隔离 Authority，在 admission、execution confirmation 和 commit permit 三个时点重新读取并验证短期认证；运行过程中吊销、过期或 rotation 漂移均失败关闭 |
| v2 隔离闭包 | `MirrorDeploymentIsolationAttestationBundle` v2 强制携带 exact regional certification ref；v1 canonical fingerprint 保持不变，v2 在内容地址中纳入 certification ref，避免旧消费者被静默破坏 |
| 重启与撤销安全 | 数据库持久化 bundle schema version 与 regional certification ref；重建、revoke 和 restart 不丢 v2 坐标，同一 attestation revision 绑定不同 certification ref 被判定为 `REVISION_FORK` |
| Rotation 语义 | KMS 与 CA 分别记录 generation 激活时间、实际 overlap、上一代撤销、全副本收敛、旧 session 排空和无重启；服务端与 Test Kit 同时验证最大 age、最小 overlap 和 serving generation |
| 动态能力探针 | 协议对象与 v1/v2 支持始终可发现；`mirrorRegionalDataPlaneCertificationReady` 独立探测 regional authority，不再复用旧 isolation readiness；配置 `gateway.testing.mirror.regional-data-plane.required=true` 时缺客户 Adapter 启动失败 |
| 跨语言协议 | Deployment Contract、Certification、v2 Isolation Bundle 三份 strict Schema、三份 server-produced fixed fixture，以及不依赖服务端/Spring 的 Test Kit verifier 已完成 |
| 接入与运维文档 | [Regional Data Plane 认证指南](resource-gateway-regional-data-plane-certification-guide.md)覆盖责任边界、Adapter、启动、探针、三次复核、轮转、事故处置、Test Kit 与上线清单 |

### 20.2 实施中发现并根治的问题

| 红灯或审计发现 | 病根 | 根治与回归保护 |
|---|---|---|
| 契约声明 rotation age/overlap，但认证只保存布尔通过 | 把策略期望当成了已观测事实，无法证明证书和密钥是否真的在窗口内轮转 | rotation observation 增加 generation 激活时间与实际 overlap；服务端和 Test Kit 计算 age、比较 minimum overlap，并核对撤销、收敛和旧 session 排空 |
| v2 certification ref 在数据库重建或 revoke 后丢失 | 只把兼容字段放在 Java record，持久层仍按 v1 形状重建 | additive DDL 持久化 schema version 与 exact ref；所有读写、撤销和重启路径纳入回归，same revision/different ref 明确拒绝为 fork |
| Regional readiness 一度直接映射旧 isolation readiness | “网络/Secret 隔离已证明”不等于“七组件短期认证当前有效” | capability snapshot 独立调用 regional authority；未装配、过期、吊销或 Scope 不闭合时仅该 feature false，协议支持仍保持可发现 |
| 子组件观测可晚于聚合认证观测时间 | 时间模型只校验 freshness，没有校验父子观测的因果顺序 | 所有 component/rotation observation 必须不晚于 certification `observedAt`，并完整覆盖 run window；构造器、服务端 verifier 与 Test Kit 三层固定 |
| 固定 fixture 容易被误读成客户现场认证 | server-produced fixture 只验证 wire compatibility，没有连接真实私有基础设施 | fixture、能力文案和指南统一标记为协议样例；客户 Adapter、Authority、KMS/Vault/PKI/egress 现场证据仍是上线硬门禁 |

### 20.3 自动化验证

| 范围 | 结果 | 证明内容 |
|---|---:|---|
| BM-012 服务端聚焦门禁 | `79/79` 通过 | Contract/Certification/v2 bundle integrity、外部签名、Scope、freshness、rotation、zero-write、三阶段复核、持久化、fork、动态 capability、Schema 和 Spring 装配 |
| Test Kit 完整发布门禁 | `568/568` 通过 | 三份 strict Schema、三份 server-produced fixture、独立 verifier、tamper/expiry/rotation/ref closure、shaded JAR 和零警告公共 API Javadoc |
| Resource Gateway 完整发布门禁 | `6118` 项，`0` failure，`0` error，`13` skipped | 干净编译、整库行为、数据库、真实浏览器、协议和可执行 JAR 重打包通过 |

### 20.4 架构漂移审计

1. Regional certification 只证明部署基础设施的当前可用与隔离事实，不成为业务 Outcome、Fixture、Contract 或 ANEKE 治理 Authority。
2. KMS、Vault、PKI、State、Resolver 和 egress enforcement 仍由客户部署拥有；Resource Gateway 只定义 Adapter、认证闭包和运行期失败关闭，不建立第二套基础设施控制面。
3. 运行时复用既有 `MirrorRunTrustAuthority`，只做组合校验，没有建立第二套 DAG runtime、session lifecycle 或 commit protocol。
4. capability 明确区分“消费者支持协议”和“当前部署认证 ready”，固定 fixture 不会把运行面误报为可用。
5. 所有持久化与 capability 数据保持 payload-free；认证只携带内容地址、状态、时间、generation 和计数，不泄露 secret、certificate、fixture 或业务请求响应。
6. v1 bundle 保持既有 fingerprint 与读取语义；v2 是显式升级，旧消费者可继续读取 v1，不会把未知字段静默解释为已认证。
7. 本地 H2/PostgreSQL 回归证明协议、持久化和失败关闭，不宣称客户多区域基础设施、真实轮转或网络出口控制已经达标。

### 20.5 差距复评

BM-012 关闭了仓库内“区域运行面只有散落配置、没有形式化部署闭包”“运行开始后认证可被撤销却继续提交”“密钥/证书轮转只有声明没有实测坐标”“v2 认证引用无法跨重启保持”“能力探针把旧隔离状态冒充区域认证”和“外部消费者无法复验”的工程差距。

风险加权差距由约 `5.5%` 降至约 `4.5%`。该数字不包含客户基础设施已经通过认证的承诺：真实 KMS/Vault/PKI/State/Resolver/egress Adapter、私有 Authority、rotation 观察窗和 write-escape 探测必须由部署方完成。仓库剩余差距集中在三处：可移植的 HA/kill/partition/upgrade/backup 运行时认证包（BM-013）、ANEKE protocol 1.1 与 freshness-aware gate projection（BM-014），以及由客户业务 Owner 冻结分母并完成观察窗的取消费申诉试点（BM-015）。

下一迭代进入 BM-013。目标是把故障注入从不可审计的运维脚本升级为显式 manifest、受保护环境 Adapter、逐场景证据和严格离线 verifier；默认只生成计划，任何破坏性动作都必须由客户沙箱授权，禁止在生产环境执行。

## 21. Iteration 13：BM-013 Runtime Certification Harness

### 21.1 已交付

| 交付 | 结果 |
|---|---|
| 固定认证分母 | `RuntimeCertificationManifest` 精确冻结 Scope、region、deployment、环境指纹、Resource Gateway/BLOGE/数据库/JVM 四类 build 和 12 个不可删除的故障场景；部署只能增加 invariant，不能缩减困难场景 |
| Plan-first Harness | `plan()` 只生成完整计划并检查 Adapter 描述，永不调用 Adapter；`PRODUCTION` 在 Manifest、Authorization 和执行三层被拒绝 |
| 单次外部授权 | Authorization 最长 30 分钟，绑定 exact Manifest、环境、deployment、完整场景集合、nonce 和审批 refs；授权 Authority 与 Report signer 分离 |
| 客户 Adapter port | `RuntimeCertificationEnvironmentAdapter` 由客户部署实现；必须独立验签、持久防重放、校验 epoch 和提供 deadline/kill switch，仓库没有可误用的默认实现 |
| Durable Journal | 数据库时钟、authorization/nonce 唯一消费、事务行锁、epoch-fenced lease、逐场景有序前缀、完整签名 Report 和 exact replay；无进程内生产 fallback |
| 恢复 SLO 与失败语义 | `faultAppliedAt / faultRemovedAt / recoveryObservedAt` 形成可计算时间线；Harness 与离线 verifier 同时验证执行期限和恢复 SLO；失败后停止继续注入，但用 `ABORTED` 补齐固定分母 |
| 区域信任闭包 | 执行前和完整运行窗口后复验 BM-012 Regional Certification 与 Isolation Decision；Report 固定 exact certification/decision/attestation refs |
| 自包含回放证据 | Replay Bundle 内嵌 Manifest、Authorization、Report、Regional Contract/Certification 和 v2 Isolation Decision；Bundle 不新增 Authority，消费者仍逐件验址、验签并推导交叉引用 |
| 跨语言协议 | 四份 strict Schema、四份 server-produced fixed fixture，以及不依赖服务端/Spring 的 `RuntimeCertificationVerifier` 已完成；未知字段、Payload、缩减分母、地址或签名漂移均失败关闭 |
| 动态能力探针 | protocol support 与 plan/journal/execution readiness 分离；execution 还合取当前 Regional Data Plane readiness，默认演示部署只广告协议，不冒充可执行 |
| 接入与运维资产 | [运行时认证指南](resource-gateway-runtime-certification-guide.md)覆盖责任边界、12 场景、Adapter、迁移、探针、CI/nightly、事故与现场准入；`scripts/verify-runtime-certification.sh` 提供无故障协议与 PostgreSQL 门禁 |

### 21.2 实施中发现并根治的问题

| 红灯或审计发现 | 病根 | 根治与回归保护 |
|---|---|---|
| 单靠 Harness 消费授权，危险 Adapter 仍可能被绕过或重放 | 把编排器的信任域误当成基础设施动作的信任域 | Adapter contract 强制独立验签和持久消费授权/nonce/run/scenario/epoch；Harness Journal 再做全局单次消费，形成双层防线 |
| 进程崩溃后从头执行会重复注入已经完成的故障 | 执行进度只存在内存，授权单次语义与场景前缀没有同一持久权威 | Journal 逐场景先提交终态再进入下一项；重启从 exact prefix 恢复；completed authorization 精确返回同一签名 Report |
| 调用方时间可伪造租约有效性 | 把跨副本所有权建立在应用时钟上 | 数据库实现忽略 caller time，使用 DB clock 和事务行锁；旧 epoch、过期 lease、并发副本和 restart 纳入 H2/PostgreSQL 回归 |
| “故障已恢复”曾只有布尔断言，无法验收恢复 SLO | 状态结果缺乏因果时间线 | 场景结果增加 apply/remove/recovery 三时刻；Harness、服务端 integrity 和 Test Kit 计算窗口并拒绝慢恢复 |
| 只导出 Report 会让独立消费者依赖外部查找 BM-012 证据 | Report 引用闭合，但发布包不是自包含 | 新增内容寻址 Replay Bundle，内嵌完整区域与隔离信任链；Test Kit 从内嵌对象独立推导三个 Report ref，而非相信生产者 |
| JSON `IntNode` 与 `LongNode` 让语义相同引用误判 | 用 Jackson 节点实现类型代替 wire-level 数值语义 | Test Kit 对 ArtifactRef 显式比较 kind/id/long revision/fingerprint，fixture 回归覆盖小整数 revision |
| 能力对象存在容易被运维误读为故障注入已启用 | 静态协议支持与客户 Adapter、Journal、双 signer、Regional trust 当前可用性混在一个布尔值 | 四个独立能力事实；execution readiness 是全部动态条件的合取，任一探针异常只返回 false |

### 21.3 自动化验证

| 范围 | 结果 | 证明内容 |
|---|---:|---|
| Harness / Integrity / H2 Journal / Schema / capability | `26/26` 通过 | 计划不执行、生产拒绝、签名与 identity closure、12 场景、首错停止、完整分母、恢复 SLO、write escape、DB clock、双副本、restart、exact replay、nonce fork、stale epoch、状态篡改和能力边界 |
| 原生 PostgreSQL | `1/1` 通过 | `V20260815_003`、两个独立 DataSource/transaction manager 并发 claim、单次授权消费、逐场景 append、完整 Report 和 exact replay |
| Schema 与能力探针 | 聚焦门禁通过 | 四类 strict Schema/fixture 精确一致、Payload/credential 字段拒绝、协议支持与动态 readiness 分离 |
| 独立 Test Kit | `7/7` Runtime Certification 用例通过 | 四对象内容地址、两类签名材料、固定分母、恢复 SLO、zero-write、区域/隔离闭包、未知字段和 tamper 失败关闭 |

最终整库 `clean verify` 数字在 BM-015 收口时统一更新，避免用聚焦测试冒充完整发布门禁。

### 21.4 架构漂移审计

1. Harness 只编排认证动作与证据，不部署或控制客户 PostgreSQL、KMS、Vault、PKI、Service Mesh 和故障平台。
2. Adapter、执行授权和现场 proof 由客户拥有；Resource Gateway 不取得云管理员权限，也不新增生产故障 API。
3. BM-012 Regional Certification 保持基础设施当前事实 Authority；BM-013 Report 只证明一次完整故障窗口，不改写区域 Contract 或 Isolation Decision。
4. Report/Bundle payload-free；原始日志和业务数据留在客户证据库，跨系统只传内容地址、状态、时间、计数与签名。
5. 没有综合分数，也不允许省略失败/未执行场景；`CERTIFIED` 只能由固定分母全部通过推导。
6. 默认演示 profile 不装配危险 Adapter；本地 fixed fixture 和 PostgreSQL 测试不能产生客户现场认证声明。

### 21.5 差距复评

BM-013 关闭了仓库内“故障场景分母可任意缩减”“危险动作没有单次外部授权”“崩溃后重复注入”“跨副本旧 Owner
仍可写”“恢复只有布尔声明”“运行报告缺区域信任闭包”“消费者不能离线复验完整认证包”和“能力探针冒充执行就绪”
八类工程缺口。

风险加权差距由约 `4.5%` 降至约 `3.5%`。降幅保持克制：仓库提供的是认证协议与 Harness，不是客户现场认证。
真实 PostgreSQL HA、网络分区、云/机房故障、滚动升级、备份恢复、KMS/Vault/PKI 轮转、write escape、容量和
长时间 soak 必须在客户批准的隔离环境形成证据。仓库剩余差距集中在 ANEKE protocol 1.1 的 registry ingest、
freshness-aware gate projection 与 mixed-version 兼容（BM-014），以及取消费申诉业务 Owner 冻结分母并完成
完整观察窗的首个试点验收包（BM-015）。

下一迭代进入 BM-014。目标不是把 ANEKE 治理复制到 Resource Gateway，而是让 Package Snapshot、Evidence、
Runtime Certification 与外部 gate result 通过 additive 1.1 协议形成可独立升级、可检测陈旧、可审计重放的持续集成闭环。

## 22. Iteration 14：BM-014 ANEKE Package Integration

### 22.1 已交付

| 交付 | 结果 |
|---|---|
| Additive protocol 1.1 | `ToolStudioResourceGatewayProtocol` 升级到 `1.1.0`，最低兼容消费者保持 `1.0.0`；integration envelope 和 Stage 0 baseline 同时覆盖两版 |
| Registry Ingest Bundle | 一个对象完整携带 Package Snapshot、Readiness、Business Asset Link Closure、Package Evidence Index 和 exact dependency manifest；Bundle 与四类内嵌事实均内容寻址 |
| ANEKE Governance Projection | ANEKE 回传 registry record、gate decision、状态、source cursor、有效窗口和域分离签名；Resource Gateway 不生成或解释 ANEKE publish result |
| Freshness-aware View | 将当前 Package/Evidence/Bundle 与缓存投影联结，严格派生 `CURRENT/MISSING/STALE/EXPIRED/UNVERIFIABLE`，不把缺 trust 或旧投影降级成通过 |
| 单调外部日志 | 完整 Scope + Package 一条 append-only stream；`externalGeneration` 从 1 连续递增，exact replay 可恢复，rollback/fork/gap 和 projectionId/issuer takeover 被拒绝 |
| 认证 Integration API | exact revision Bundle export、Projection ingest 和 current joined view 三条路由；每条路由使用独立 purpose policy 和 trusted identity Scope |
| 动态能力探针 | 协议对象始终可发现；API 物理装配与 ANEKE trust 当前 ready 分开广告，未安装客户 trust 时写入失败关闭 |
| 事务事件 | 新 generation 提交后写入 `DOMAIN_CAPABILITY_PACKAGE_GOVERNANCE_CHANGED` outbox；exact replay 不重复产生治理变更事件 |
| 数据库迁移 | `V20260815_004` 创建 Scope-keyed head、append-only projection history、exact ref 列和 expiry index；H2 与 PostgreSQL 共用 repository 语义 |
| 独立消费者 | Test Kit 打包四份 strict Schema、两份 server-produced fixture，并独立复验 Bundle 闭包、内容地址、Projection 签名材料、有效期、exact refs 和 caller-owned trust |
| 接入文档 | [ANEKE Package 集成指南](resource-gateway-aneke-package-integration-guide.md)覆盖 Authority、启动、探针、HTTP、trust adapter、generation、PostgreSQL、错误恢复和上线清单 |

### 22.2 实施中发现并根治的问题

| 红灯或审计发现 | 病根 | 根治与回归保护 |
|---|---|---|
| 服务端已是 `1.1.0`，Test Kit mirror envelope 仍只接受 `1.0.0` | Producer 版本升级只改了服务端常量，没有同步独立消费者兼容基线 | Test Kit 显式维护 current/minimum 两版；baseline 把 `1.1.0` 放在首选、保留 `1.0.0`，新增滚动升级混部用例 |
| PostgreSQL 双副本在 head 唯一键冲突后无法继续 `FOR UPDATE` | H2 允许捕获冲突后继续，PostgreSQL 会把整个事务标记为 aborted | head 初始化进入独立 `REQUIRES_NEW` 事务，冲突在事务外消化，再进入 generation 提交事务；原生 PostgreSQL 双 DataSource 认证固定该语义 |
| 独立初始化事务可能被 gap 请求抢占 stream identity | 把“创建 head”提前后，没有先限制 bootstrap generation | 只有 generation `1` 可创建新 head；被拒绝的 generation gap 不留下 stream，随后合法 issuer/projectionId 可正常 bootstrap |
| 只校验 Projection 顶层签名会漏掉 Bundle 内部漂移 | 外部治理签名不能替代 RG 编译事实和 Evidence 的各自内容地址 | 服务端接收前重建当前 Bundle；Test Kit 逐一复验 Snapshot、Readiness、L0-L3 Closure、Evidence、manifest 和三类跨对象 ref |
| fixed fixture 容易被当成已接通 ANEKE | 协议兼容证据与客户治理 Authority 没有在体验路径中分开 | 文档把本地协议体验、离线复验和安装客户 trust 的完整闭环分成三条路径；默认 `ingestReady=false` 明确解释为预期失败关闭 |

### 22.3 自动化验证

| 范围 | 结果 | 证明内容 |
|---|---:|---|
| 服务端协议、service、controller、H2 repository、capability | `17/17` 通过 | 内容地址、签名/trust、exact replay、Scope、stale/expired、outbox、API purpose、generation 和动态探针 |
| 原生 PostgreSQL | `1/1` 通过 | `V20260815_004`、两个独立 DataSource/transaction manager 竞争同一 successor，只允许一个提交 |
| 独立 Test Kit 与 mixed-version | `9/9` 通过 | 四类 Schema、两份 fixture、Ed25519、Bundle closure、Projection binding、tamper、unknown field、expiry、trust reject 和 `1.1.0/1.0.0` 协商 |

最终整库 `clean verify` 数字在 BM-015 收口时统一更新，避免用聚焦测试冒充发布门禁。

### 22.4 架构漂移审计

1. Resource Gateway 只导出 immutable Package/Evidence facts 和缓存外部投影，不新增 ANEKE registry、workbook、owner approval、TEE 或 publish gate。
2. ANEKE Projection 必须由部署方 trust adapter 验证；仓库没有 accept-all、fixture-key 或 production fallback。
3. Projection 不能修改 Draft、Snapshot、Evidence 或 compilation head；RG facts 变化只会让投影变 stale。
4. Bundle 和 event 均 payload-free；业务 Payload、credential、私钥和原始审计材料不进入跨系统协议。
5. Outbox 只通知“外部治理投影发生变化”，不把异步事件当作当前状态 Authority；消费者必须使用 cursor 恢复并重读 current view。
6. H2/本地 Ed25519 fixture 只证明实现和 wire compatibility；真实 ANEKE trust、registry、gate 和组织审批仍需客户环境认证。

### 22.5 差距复评

BM-014 关闭了仓库内“Package 导入 ANEKE 时依赖松散 Map”“多库/多 revision 依赖无法精确闭合”“外部 gate
结果没有签名、有效期与 generation”“陈旧治理结果继续显示为当前”“两边版本无法独立滚动升级”“并发副本可形成
generation fork”和“消费者必须信任服务端自证”七类工程差距。

风险加权差距由约 `3.5%` 降至约 `2.8%`。该数字仍不代表客户 ANEKE 已接通：真实 issuer/key lifecycle、
registry/workbook/gate、跨系统 cursor、组织审批、故障演练和长期运行必须在客户环境形成证据。仓库内最后一个工作包是
BM-015：形成取消费申诉试点验收 Manifest，把第 16.5 节十项门禁、Owner 冻结分母、exact evidence refs、观察窗和
“未获客户验收”状态形式化，避免用演示数据冒充业务验收。
