# Resource Gateway 客户业务能力镜像实施状态

> 状态：持续更新
>
> 蓝图：[客户业务能力镜像蓝图差距评估与技术演进方案](resource-gateway-customer-business-mirror-blueprint-gap-and-technical-evolution-plan.md)
>
> 当前迭代：BM-008A 实现绑定已完成；正在实施 BM-008B 同套件 Conformance
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

## 12. Iteration 5：BM-005 Business Mirror Workspace

### 12.1 已交付

| 交付 | 结果 |
|---|---|
| 默认业务入口 | `/` 重定向到 `/business-mirror/`；Portfolio 以七个真实 Legacy Graph preview 呈现待镜像资产，旧 Author v2 与 Legacy 页面继续可进入 |
| 六步任务工作区 | Package 页面按问题、边界、能力、场景、演练、校准与提交组织任务，不把协议字段平铺成表单 |
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

降幅保持克制，因为绑定尚未执行实现，也没有复用原 acceptance suite、Case 配对、结构化 Diff 或 `CONFORMANT` Snapshot。BM-008B 将只把原模拟中 Proposal target 的 invocation sites 反转到 exact implementation binding，所有其他外部依赖继续 Fixture-only；共用规则、真实 fallback、绑定过期和调用未触达必须失败关闭。报告必须 payload-free、签名、durable、可重放，并明确“同套件 assertion 一致”不等于未声明业务语义也完全一致。
