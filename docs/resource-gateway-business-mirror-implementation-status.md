# Resource Gateway 客户业务能力镜像实施状态

> 状态：持续更新
>
> 蓝图：[客户业务能力镜像蓝图差距评估与技术演进方案](resource-gateway-customer-business-mirror-blueprint-gap-and-technical-evolution-plan.md)
>
> 当前迭代：BM-001 协议内核
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

## 3. 能力探针解释

当前探针应包含：

```json
{
  "supportedObjects": {
    "domainCapabilityPackageDraft": ["bloge.domainCapabilityPackageDraft.v1"],
    "domainCapabilityPackageSnapshot": ["resourceGateway.domainCapabilityPackageSnapshot.v1"],
    "capabilityProposalDraft": ["bloge.capabilityProposalDraft.v1"],
    "capabilityProposalSnapshot": ["resourceGateway.capabilityProposalSnapshot.v1"]
  },
  "features": {
    "businessMirrorProtocol": true,
    "businessMirrorPackageApi": false,
    "businessMirrorProposalSimulation": false
  }
}
```

`businessMirrorProtocol=true` 只表示协议、Schema 和独立校验器可用。当前还不能通过 HTTP 创建、保存、编译或模拟 Package/Proposal。

## 4. 架构偏差审计

| 蓝图决策 | 当前实现 | 结论 |
|---|---|---|
| 业务主模型进入独立 `businessmirror` 深模块 | 新对象位于 `com.leanowtech.bloge.gateway.businessmirror.domain` | 符合 |
| `integration.mirror` 保持执行与证据事实边界 | 新模块只复用 Capability、Contract、Effect、ArtifactRef 和 Provenance | 符合 |
| Graph 是执行投影，不是业务根 | Package 使用 `graphRefs`，没有向 `GraphDraft` 塞业务字段 | 符合 |
| Proposal 不是正式 Operator 上的 `mock=true` | Proposal 有独立身份、价值假设、隔离 binding 和证据生命周期 | 符合 |
| RG 不接管 ANEKE Registry/Gate | Snapshot 不包含 ANEKE 权威状态 | 符合 |
| Test Kit 不依赖服务端和 Spring | 新公共入口只依赖 Jackson 与打包 Schema | 符合 |
| 新对象进入 additive `1.1.x` 集成协议 | 对象已进入能力探针，但当前 protocolVersion 仍为 `1.0.0` | 有意延后到 BM-014；先补多版本协商与旧消费者认证，避免伪兼容 |

未发现需要推翻蓝图边界的架构偏差。

## 5. 差距复评

本轮关闭的是「业务主对象不存在、Proposal 没有独立身份、L0-L3 无类型协议、跨语言协议不可消费」四个根问题的协议层部分。仍未关闭其持久化、编译、运行、产品和组织闭环。

| 口径 | 开工前 | Iteration 1 复评 | 剩余主要缺口 |
|---|---:|---:|---|
| 技术内核完成度 | `90-92` | `91-93` | PackageCompiler、Proposal simulation、真实基础设施认证 |
| 产品蓝图闭环度 | `67` | `72` | repository/API、Business Mirror Workspace、实现交付、Impact/Evidence |
| 复杂企业生产成熟度 | `54` | `54` | 本轮没有新增 HA/DR、生产 Connector、KMS/WORM 或组织运行证据 |

按 15 个工作包的风险加权口径，当前距离完整蓝图仍约 `28%`。该数字是仓库内部工程复评，不是客户验收结论，也远未达到 `<3%` 的收敛门槛。

## 6. 下一迭代：BM-002

下一步建立 Package 的 durable authoring vertical slice，禁止直接跳到 UI：

1. 定义 Package command/query port、authority policy 和稳定错误码。
2. 增加 PostgreSQL migration、完整 Scope 主键、optimistic revision 与 canonical draft fingerprint。
3. 实现 create/save/read/list API，使用 idempotency key 保存 exact receipt。
4. 覆盖并发保存、响应丢失重试、同 key 不同 payload、跨 Scope、重启 exact replay 和数据库回滚。
5. 能力探针仅在运行装配和认证完成后把 `businessMirrorPackageApi` 改为 `true`。

BM-002 完成后重新执行差距复评，再进入 PackageCompiler 与 Legacy Graph 包装器。
