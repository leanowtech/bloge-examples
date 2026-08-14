# Resource Gateway Business Mirror PackageCompiler 设计与接入说明

> 状态：BM-003 编译内核已实现；编译结果持久化、HTTP API 和生产 Authority Adapter 尚未完成。
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
| `PackageDependencyObservation` | 记录 source ref、materialized ref、Scope、状态和已证明 assurance | 携带 Fixture 或业务 Payload |
| `FrozenPackageDependencies` | 携带 generation、closure/plan/policy/evidence refs 和业务资产 links | 充当新的资产 source-of-truth |
| `BusinessAssetLinkClosure` | 固化单 Scope、无悬空引用、无环的 L0-L3 关系事实 | 替代 Graph edge 或 ANEKE impact registry |
| `PackageReadinessReport` | 输出稳定、payload-free、可深链的 Finding | 接受自由文本错误或人工改写 status |
| `DomainCapabilityPackageSnapshot` | 固化一次 exact compile 的 immutable 跨系统事实 | 表达 ANEKE 发布、认证或客户批准状态 |

`PackageCompilationAuthority` 是端口，不是数据库表。后续 Adapter 可以接 Capability Closure、Graph publication、Contract、Scenario、Fidelity 和 Outcome 各自的权威仓储，但不能把这些事实复制成一个新的万能 Registry。

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
```

三类入口不再只做 JSON Schema 校验，还会复算 fingerprint，并验证关系闭包、status 派生、Scope、manifest 顺序和 mutable artifact 禁令。失败只返回稳定错误码，不回显业务内容。

## 9. 当前可用范围

已可用：

- 纯 Java `PackageCompiler`；
- Authority freeze/fence port；
- 确定性 Finding、Readiness、Link Closure 和 Snapshot 生成；
- 服务端 canonical sealing 与 Test Kit 独立复验；
- 依赖缺失、跨 Scope、隔离缺失、篡改、循环和 TOCTOU 失败关闭。

尚不可用：

- `POST .../{packageId}/compile` HTTP API；
- Snapshot、Readiness、Closure 和 compile receipt 的原子持久化；
- 面向现有 Graph/Scenario/Outcome 仓储的生产 Authority Adapter；
- 大型 Package async job、容量门禁和取消；
- capability probe 中的 Package Compiler readiness。

因此当前部署不能宣称 Package 编译服务已经可供产品调用。下一子迭代完成持久化和认证 HTTP vertical slice 后，才评估对应 feature flag。

## 10. 验证命令

聚焦验证：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=PackageCompilerTest,DomainCapabilityPackageProtocolTest test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=BusinessMirrorProtocolTest test
```

覆盖内容包括：完整编译、100 组输入乱序、缺失与指纹漂移、跨 Scope、Scenario/Outcome/Proposal/Effect assurance、mutable material、额外 Authority 观测、关系缺失/悬空/循环、source tamper 和结果发布前 TOCTOU fencing。

完整门禁结果见 [Business Mirror 实施状态](resource-gateway-business-mirror-implementation-status.md)。当前 Resource Gateway `5950` 个测试以及 Test Kit `541` 个测试均无失败；前者包含原生 PostgreSQL 和真实浏览器 E2E，后者包含打包、shade 与 Javadoc 门禁。

## 11. 下一步

1. 建立 `snapshot/readiness/link-closure/compile-receipt` append-only 表和 PostgreSQL migration。
2. 使用 `Scope + packageId + sourceDraftRevision + idempotencyKey` 串行化 compile command。
3. 在同一事务内保存 exact compile receipt 和全部 immutable facts；响应丢失后返回原结果。
4. 将 `PackageDependencyDriftException` 映射为 `RG.PACKAGE.DEPENDENCY_DRIFT` `409`。
5. 增加认证 compile/read API，并通过 Spring HTTP、双实例并发和 PostgreSQL 原生测试。
6. 实现 Legacy Graph Authority Adapter，逐个包装七个内置 Graph，并保持缺失业务语义 `BLOCKED`。
