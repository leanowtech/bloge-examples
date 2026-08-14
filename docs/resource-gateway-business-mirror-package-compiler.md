# Resource Gateway Business Mirror PackageCompiler 设计与接入说明

> 状态：BM-003 编译内核、原子持久化、认证 HTTP API 与首批组合 Authority Adapter 已实现。
>
> 最近更新：2026-08-14

本文面向 Resource Gateway 开发者、客户系统适配器开发者和 ANEKE 集成人员，说明 `PackageCompiler` 已实现的协议、失败语义、接入边界和验证方式。Package Draft 的创建、保存和查询见 [Package Authoring 操作指南](resource-gateway-business-mirror-package-authoring-guide.md)。

## 1. 本轮解决的问题

Package Draft 中的 `revision + fingerprint` 只表达作者希望引用哪个事实，不能证明以下条件成立：

- exact revision 真实存在，内容与 fingerprint 一致；
- 引用属于当前企业 Scope；
- Graph Draft 和 Capability Proposal 已被物化为不可变事实；
- Scenario denominator 非空，OutcomeDefinition 可解析；
- Proposal 只能使用有界 Fixture，且真实外部调用被禁止；
- 高风险 Effect 具备状态、补偿或隔离防线；
- L0-L3 业务资产关系无悬空引用、跨 Scope 引用或循环；
- 编译开始后，依赖 head 和 Authority generation 没有漂移。

`PackageCompiler` 将这些条件统一收敛为一次确定性、失败关闭的编译。编译成功时产出 immutable Package Snapshot；编译被阻断时仍产出可验证的 Readiness Report 和安全的 Business Asset Link Closure，但不产出 Snapshot。

## 2. 不可破坏的不变量

1. Draft 中的 exact ref 必须经过 Authority Adapter 解析，不因格式合法而自动可信。
2. 每个 Draft 引用必须存在且仅存在一条 `PackageDependencyObservation`；额外观测同样失败关闭。
3. `GRAPH_DRAFT` 和 `CAPABILITY_PROPOSAL` 不能进入 Snapshot manifest，必须物化为 immutable ref。
4. 所有依赖必须与 Package 使用同一个五段企业 Scope。V1 不接受隐式跨 Scope 授权。
5. Readiness status 只能由 Finding severity 派生，作者不能提交绿色状态。
6. `BLOCKED` 编译不能发布 Snapshot；`READY` 或 `REVIEW_REQUIRED` 才能形成 immutable Snapshot。
7. Authority 在结果返回前必须执行第二次 fencing check。依赖漂移抛出 `PackageDependencyDriftException`，调用方应重新编译。
8. 相同 Draft、Authority 冻结事实、result revision 和 `compiledAt` 必须生成相同 fingerprint。

## 3. 模块边界

| 类型 | 责任 | 不负责 |
|---|---|---|
| `PackageCompiler` | 校验、Finding 派生、manifest 组装、closure/snapshot sealing、TOCTOU 调度 | 查询具体 Registry、写数据库、处理 HTTP |
| `PackageCompilationAuthority` | 冻结同一代依赖事实，并在结果发布前确认未漂移 | 决定 Package readiness、替编译器忽略缺失项 |
| `CompositePackageCompilationAuthority` | 按 source kind 指派唯一 Adapter、合并冻结事实、生成 policy/generation、二次解析围栏 | 用 Adapter 优先级掩盖歧义、把 unsupported kind 判为已解析 |
| `PackageDependencyAuthorityAdapter` | 从既有 source-of-truth 解析一种或多种明确归属的依赖类型 | 充当新的万能 Registry、读取客户端伪造 observation |
| `BuiltInGraphAssetAuthority` | 复用既有 DSL 投影、Graph Contract、Operator/Resource 目录和测试套件生成 exact refs | 维护第二份 Graph 拓扑、把测试套件冒充 ScenarioPack |
| `PackageDependencyObservation` | 记录 source ref、materialized ref、Scope、状态和已证明 assurance | 携带 Fixture 或业务 Payload |
| `FrozenPackageDependencies` | 携带 generation、closure/plan/policy/evidence refs 和业务资产 links | 充当新的资产 source-of-truth |
| `BusinessAssetLinkClosure` | 固化单 Scope、无悬空引用、无环的 L0-L3 关系事实 | 替代 Graph edge 或 ANEKE impact registry |
| `PackageReadinessReport` | 输出稳定、payload-free、可深链的 Finding | 接受自由文本错误或人工改写 status |
| `DomainCapabilityPackageSnapshot` | 固化一次 exact compile 的 immutable 跨系统事实 | 表达 ANEKE 发布、认证或客户批准状态 |
| `PackageCompilationCoordinator` | 幂等 command、Package revision 串行分配、原子 fact/receipt 发布 | 接受调用方提交的 Authority observation |
| `PackageCompilationFactRepository` | append-only Readiness、Closure、Snapshot 与 exact revision read | 修改已经发布的事实 |
| `PackageCompilationReceipt` | 绑定 source、结果 revision、Authority generation 和三类结果事实 | 暴露依赖 payload 或可变 Registry head |

`PackageCompilationAuthority` 是端口，不是数据库表。后续 Adapter 可以接 Capability Closure、Graph publication、Contract、Scenario、Fidelity 和 Outcome 各自的权威仓储，但不能把这些事实复制成一个新的万能 Registry。

当前组合规则是硬约束：同一个 source kind 只能有一个 Adapter Owner；发现双 Owner 时应用启动失败。未安装 Adapter 的类型生成 `MISSING` observation，不会按顺序尝试多个 Registry。组合 Authority 的 generation 同时绑定 Adapter 集、Scope、source/head observation、物化 closure/plan、业务关系、evidence 和 code-owned compilation policy；`assertUnchanged` 会用同一批 source refs 再解析一次并比较完整 generation。

首批 `BuiltInGraphPackageDependencyAdapter` 只拥有：

| Source kind | 权威来源 | 物化结果 |
|---|---|---|
| `GRAPH_DRAFT` | classpath BLOGE DSL、`GatewayGraphContractCatalog`、`VisualOperatorCatalog`、`ResourceRegistry` | immutable root `CAPABILITY` 与 `CAPABILITY_CLOSURE` ref |
| `CONTRACT` | `GatewayGraphContractCatalog` | content-addressed `CONTRACT` ref |

内置 Contract Test Suite 只作为 exact evidence ref 进入冻结窗口。它不是 ScenarioPack，也不会消除 `SCENARIO_INVENTORY_MISSING` 或 `SCENARIO_PACK_MISSING`。

## 4. 编译流程

```text
verify stored Draft fingerprint
  -> Authority.freeze(exact stored revision)
  -> verify frozen Scope
  -> derive Draft obligation findings
  -> resolve every declared dependency observation
  -> verify kind-specific assurances
  -> compile and seal BusinessAssetLinkClosure
  -> assemble immutable dependency manifest
  -> seal PackageReadinessReport
  -> when not BLOCKED, seal DomainCapabilityPackageSnapshot
  -> Authority.assertUnchanged(frozen generation and heads)
  -> return exact compilation result
```

`compiledAt` 和 Snapshot revision 由上层幂等 command/persistence 边界分配。编译器不读取系统时间，也不自行分配 revision，因此离线复验和响应丢失重试不会产生新 fingerprint。

部署事务边界为：

```text
authenticate + exact source read
  -> lock (Scope, Idempotency-Key)
  -> replay exact prior receipt or reject key drift
  -> lock (Scope, packageId) revision allocator
  -> reserve compilation revision
  -> compile under Authority freeze/fence
  -> append Readiness + Link Closure + optional Snapshot
  -> when Snapshot exists, append impact projection command + Snapshot-compiled change event
  -> append exact compile receipt
  -> commit once
```

反向影响索引不在权威编译事务内计算。编译事务只追加 projection outbox command；数据库租约 worker 在提交后读取 immutable compilation facts，构建可重建索引，并将 impact rows、current head、`BUSINESS_ASSET_IMPACT_CHANGED` 和 outbox completion 原子提交。投影失败不会撤销 Package Snapshot，查询通过 `CURRENT/STALE` 明确暴露追平状态。详见 [Business Asset Impact 指南](resource-gateway-business-asset-impact-index-guide.md)。

## 5. 依赖状态与 Assurance

### 5.1 Resolution status

| 状态 | 含义 | 编译结果 |
|---|---|---|
| `RESOLVED` | Authority 找到并物化了 exact source ref | 继续检查 Scope 与 assurance |
| `MISSING` | exact revision 不存在 | `BLOCKED` |
| `FINGERPRINT_MISMATCH` | revision 存在，但内容地址不一致 | `BLOCKED` |
| `SCOPE_VIOLATION` | 资产不属于 Package Scope | `BLOCKED` |
| `INVALID` | 资产无法通过其权威协议校验 | `BLOCKED` |

### 5.2 Kind-specific assurance

| Assurance | 必须用于 | 缺失 Finding |
|---|---|---|
| `SCHEMA_VALID` | 所有已解析依赖 | `DEPENDENCY_SCHEMA_INVALID` |
| `NON_EMPTY_DENOMINATOR` | `SCENARIO_INVENTORY` | `SCENARIO_DENOMINATOR_EMPTY` |
| `OUTCOME_PARSABLE` | `OUTCOME_DEFINITION` | `OUTCOME_DEFINITION_UNPARSABLE` |
| `SIMULATION_BOUNDED` | `CAPABILITY_PROPOSAL` | `PROPOSAL_RESOLVER_UNBOUNDED` |
| `REAL_EXTERNAL_CALLS_FORBIDDEN` | `CAPABILITY_PROPOSAL` | `PROPOSAL_REAL_CALL_GUARD_MISSING` |
| `STATE_EFFECT_PROTECTED` | 高风险 Package 的 Effect | `HIGH_RISK_EFFECT_UNPROTECTED` |

Assurance 是 Adapter 对其权威对象完成校验后的结果，不允许从 Draft 中直接复制。生产 Adapter 必须保留可追溯的 policy generation 和 exact materialized ref。

## 6. Readiness 与发布语义

| 状态 | 派生条件 | Snapshot |
|---|---|---|
| `READY` | 没有 `ERROR` 或 `WARNING` | 生成 |
| `REVIEW_REQUIRED` | 没有 `ERROR`，至少一个 `WARNING` | 生成，但治理系统可继续阻断发布 |
| `BLOCKED` | 至少一个 `ERROR` | 不生成 |

Finding 只包含稳定 `findingId`、`code`、`severity`、`category`、JSON Pointer、可选 artifact ref 和 `messageId`。业务值、下游异常和 Payload 不进入 Finding。

当前 Draft 业务必填项、缺少 Scenario、Fidelity、Outcome、Solution、Carrier 或 Channel 都属于 `ERROR`。Legacy Graph 包装器必须显式展示这些缺口，不能因为 Graph 本身可运行就生成绿色 Package。

## 7. Business Asset Link Closure

新增协议 `resourceGateway.businessAssetLinkClosure.v1`，Schema 位于：

- `docs/schemas/resource-gateway-business-mirror/business-asset-link-closure-v1.schema.json`

Closure 包含 exact `assets`、语义 `links`、Package/Scope 坐标、revision、创建时间和 canonical fingerprint。服务端和独立 Test Kit 都验证：

- 资产只属于一个 Scope；
- link 两端必须存在于 `assets`；
- 相同端点、relation 和 condition 的业务 link 只能出现一次，不能用 risk 或 Owner 差异制造歧义；
- link 图不能包含有向环；
- fingerprint 必须与 canonical content 一致。

Graph edge 继续表达执行数据流；Business Asset Link 只表达业务组合、交付、暴露、验证和校准关系。

## 8. Test Kit 离线验证

无需启动 Resource Gateway：

```java
BusinessMirrorProtocol.requireBusinessAssetLinkClosure(linkClosureJson);
BusinessMirrorProtocol.requirePackageReadinessReport(readinessJson);
BusinessMirrorProtocol.requirePackageSnapshot(snapshotJson);
BusinessMirrorProtocol.requirePackageCompilationReceipt(compilationReceiptJson);
```

这些入口不再只做 JSON Schema 校验，还会复算 fingerprint，并验证关系闭包、status 派生、Scope、manifest 顺序、mutable artifact 禁令，以及 receipt 中 source/revision/time/fact reference 的一致性。失败只返回稳定错误码，不回显业务内容。

## 9. 当前可用范围

已可用：

- 纯 Java `PackageCompiler`；
- Authority freeze/fence port；
- 确定性 Finding、Readiness、Link Closure 和 Snapshot 生成；
- 服务端 canonical sealing 与 Test Kit 独立复验；
- 依赖缺失、跨 Scope、隔离缺失、篡改、循环和 TOCTOU 失败关闭。
- 认证 `POST .../{packageId}/compile` 与 exact compilation read API；
- H2/PostgreSQL append-only facts、Package revision allocator 和 durable receipt；
- 响应丢失 exact replay、不同 key 并发 revision 串行化和数据库列/JSON 防漂移；
- 动态 capability readiness：API 与 Authority 就绪状态分别暴露。
- `CompositePackageCompilationAuthority` 的唯一 kind ownership、unsupported-kind fail closed 和完整 generation re-resolution；
- 七个内置 Graph 的 DSL/Contract/Operator/Resource/Test Suite 权威解析；
- `GRAPH_DRAFT` → immutable root `CAPABILITY`/`CAPABILITY_CLOSURE` 与 `CONTRACT` exact materialization；
- 七个内置 Graph 的 fail-closed Legacy Package preview、gap inventory 和 durable idempotent import。
- L0-L3 transitive impact、事务 projection outbox、跨副本租约 worker、freshness、重建和 exact Deep Link。

尚不可用：

- 面向持久化 Visual Graph publication、Scenario、Fidelity、Outcome 和 L0-L3 关系仓储的 Authority Adapter；
- 大型 Package async job、容量门禁和取消；
- 客户环境对 Authority Adapter 的 HA、升级、灾备和大规模容量认证。

默认部署现在返回 `businessMirrorPackageCompilerApi=true` 与 `businessMirrorPackageCompilerAuthorityReady=true`，表示组合 Authority 已安装并能对其拥有的 source kind 执行 exact resolution 与二次围栏。这个布尔值不表示任意 Package 的全部依赖都存在，也不表示客户生产环境已认证；unsupported kind 仍会形成 `MISSING` 并阻断 Snapshot。

### 9.1 API 快速体验

```bash
curl -i -sS -X POST \
  'http://localhost:8080/api/business-mirror/packages/cancellation-fee-resolution/compile?sourceRevision=1' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: BUSINESS_MIRROR_AUTHORING' \
  -H 'Idempotency-Key: demo:cancellation-fee:compile:r1'

curl -fsS \
  http://localhost:8080/api/business-mirror/packages/cancellation-fee-resolution/compilations/1 \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: BUSINESS_MIRROR_AUTHORING' | jq
```

先按 [Package Authoring 指南](resource-gateway-business-mirror-package-authoring-guide.md) 创建 revision `1`。演示环境返回 `BLOCKED` 是正确行为，不是接口故障。

## 10. 验证命令

聚焦验证：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=PackageCompilerTest,CompositePackageCompilationAuthorityTest,\
ResourceGatewayApplicationTest,DomainCapabilityPackageProtocolTest test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=BusinessMirrorProtocolTest test
```

覆盖内容包括：完整编译、100 组输入乱序、缺失与指纹漂移、跨 Scope、Scenario/Outcome/Proposal/Effect assurance、mutable material、额外 Authority 观测、关系缺失/悬空/循环、source tamper、唯一 kind ownership、unsupported kind、七个内置 Graph exact materialization 和结果发布前 TOCTOU fencing。

完整门禁结果见 [Business Mirror 实施状态](resource-gateway-business-mirror-implementation-status.md)。当前 Resource Gateway `5967` 个测试以及 Test Kit `544` 个测试均无失败；前者包含原生 PostgreSQL、真实浏览器 E2E 与可执行 JAR 打包，后者包含 Schema packaging、shade 与 Javadoc 门禁。

## 11. 下一步

BM-004 Legacy Graph projector 已完成。其协议、三条 API、离线验真和逐包操作见 [存量 Graph 渐进迁移指南](resource-gateway-business-mirror-legacy-migration-guide.md)。

后续顺序：

1. 在 Business Mirror Workspace 中呈现 Package、gap 和 exact source lineage；
2. 将已发现的 Contract Test Suite 显式迁移为 owner-governed Scenario denominator/ScenarioPack，而不是直接改名；
3. 继续接入持久化 Visual Graph、Scenario、Fidelity、Outcome 和业务资产关系 Adapter，并增加客户环境认证；
4. 增加大型 Package async capacity/cancel；当前同步 API 只用于有界编译。
