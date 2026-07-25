# Resource Gateway 域级保真度协议与投影内核

本文说明 `DomainFidelityInventory.v1` 与 `DomainFidelityProfile.v1` 的语义、使用方式、
独立验真边界和当前实施状态。它解决的是“模拟覆盖了多少已定义业务空间、证据是否足够新、
哪些维度确实被测量、哪些只能拒答”，不是给业务域生成一个好看的综合分。

## 1. 当前状态

截至 2026-07-26，已实现：

- owner-approved、content-addressed 的
  `resourceGateway.domainFidelityInventory.v1`；
- payload-free、无总分的
  `resourceGateway.domainFidelityProfile.v1`；
- 保留完整分母的 fail-closed Java 投影内核；
- `PASS`、`FAIL`、`ABSTAINED`、`STALE`、`MISSING` 五态测量语义；
- 分维度 coverage、abstention、Wilson 95% 置信区间和最低样本门槛；
- freshness、source composition、abstention debt、exact source lineage；
- 服务端模型自校验，以及不依赖 Spring/服务端类的 Test Kit 离线验证器；
- strict Draft 2020-12 JSON Schema 和恶意重签故障矩阵；
- full-enterprise-scope、append-only 的 inventory/profile H2 repository；
- inventory revision predecessor CAS、profile evidence-cut 唯一性与读取时索引/JSON
  交叉验真；
- 使用现有 managed evidence signer 的 domain-separated Ed25519 profile seal；
- auth-before-decode 的 inventory register/read 和 signed profile read API；
- owner/projector 职责隔离、同事务成功审计与审计失败回滚；
- 分开报告 route、signing、source adapter 和 projection readiness 的 capability probe。

尚未实现，因此不能宣称 profile projection ready：

- Scenario workbook、read-only shadow、authoritative outcome 到
  `Measurement` 的独立来源适配器；
- drift 自动降级、shadow job、outcome reconciliation 和工作台。

因此 inventory/profile 受保护 API 与 managed signing 可以报告 ready，但
`mirrorDomainFidelityProjectionReady` 必须保持 `false`。历史 profile 可被读取和独立验真，
不等于系统现在能从真实业务证据生成新 profile。

## 2. 为什么需要两个对象

### 2.1 DomainFidelityInventory

Inventory 是业务 Owner 批准的稳定分母。每个 `CoverageUnit` 固定：

- 稳定 `unitId`；
- exact `ScenarioCase` revision/fingerprint；
- exact target capability；
- case 类型；
- 必须测量的维度集合。

Inventory 必须：

- 来源是 `OWNER`，有明确审批人、审批时间和复审失效时间；
- lifecycle 为 `ACTIVE`，且没有 revocation；
- scope、tenant 和 provenance 一致；
- unit、Scenario ref、dimension 均唯一且规范排序；
- 每个 unit 至少要求 `BEHAVIOR` 与 `CONTRACT`；
- `FAULT` 必须额外要求 `ERROR_DISTRIBUTION`；
- `STATE_TRANSITION` 必须额外要求 `STATE_TRANSITION`；
- 用 canonical SHA-256 形成不可变内容地址。

新增成功样本不能偷偷缩小分母。业务空间变化必须发布新的 inventory revision，使历史 profile
仍能解释“当时依据哪个业务覆盖定义”。

### 2.2 DomainFidelityProfile

Profile 是在固定 `measuredAt` 对 inventory 全量投影得到的多维证据向量。它包含：

- exact inventory/taxonomy ref；
- 固定最低样本数与 freshness policy；
- 每个 inventory unit 的来源、观察时间、失效时间和逐维结果；
- 完整 unit/dimension denominator；
- 分维度 coverage、abstention、PASS/FAIL 和 Wilson 95% 区间；
- abstention debt 及闭集原因；
- recorded/synthesized/owner-declared/authoritative/unknown 来源构成；
- `COMPLETE`、`PARTIAL`、`INSUFFICIENT_EVIDENCE` 或 `STALE`；
- 解释限制和 producer seal。

协议故意没有 `score`。不同维度不满足可加性：

- contract 通过不能抵消 outcome 未校准；
- behavior 通过不能抵消 state transition 缺失；
- synthesized 样本不能伪装成 recorded；
- 低样本的 100% 通过率不能伪装成高置信度；
- missing/stale/abstained 不能通过改变分母消失。

## 3. 七个独立维度

| 维度 | 回答的问题 | 典型权威来源 |
|---|---|---|
| `BEHAVIOR` | 业务分支与处置行为是否一致 | Scenario assertion / shadow diff |
| `CONTRACT` | 输入、输出、错误契约是否一致 | contract suite / workbook |
| `EFFECT` | 声明副作用和实际虚拟 effect 是否一致 | effect assertion / state evidence |
| `ERROR_DISTRIBUTION` | 故障类型、重试和 rare path 分布是否一致 | fault rehearsal / shadow cohort |
| `OUTCOME` | 模拟处置与独立业务结果是否一致 | authoritative outcome connector |
| `REQUEST_SPACE` | 被测请求空间是否覆盖 Owner 定义的业务分布 | approved corpus / sampling proof |
| `STATE_TRANSITION` | 状态前后关系、幂等和写结果是否一致 | Session transition workbook |

`OUTCOME` 和 `REQUEST_SPACE` 没有专门证据时必须分别产生
`OUTCOME_AUTHORITY_UNAVAILABLE` 与 `REQUEST_SPACE_EVIDENCE_UNAVAILABLE`，不能借用
`BEHAVIOR` 的成功结果。

## 4. 投影规则

输入是已经通过各自信任边界验证的 payload-free `Measurement`。v1 只接受：

- `SCENARIO_REHEARSAL_WORKBOOK_SEED`；
- `SCENARIO_REHEARSAL_BATCH_WORKBOOK_SEED`；
- `FIDELITY_SHADOW_COMPARISON`；
- `AUTHORITATIVE_OUTCOME_OBSERVATION`。

投影按以下顺序执行：

1. 复验 inventory 内容地址、Owner provenance、scope、lifecycle 和有效窗口。
2. 拒绝 inventory 外 unit、错误 Scenario ref、未来 observation 和重复 unit/source。
3. 每个 unit 选择 `observedAt` 最新的来源；时间相同以 source fingerprint 确定性裁决。
4. 没有来源时为每个 required dimension 生成 `MISSING/NO_ELIGIBLE_EVIDENCE`。
5. 强制 `expiresAt = observedAt + freshnessWindow`。
6. 到期来源的全部维度变为 `STALE/EVIDENCE_STALE`。
7. 非 certifiable 或不完整来源全部转成 `ABSTAINED`，不能保留 PASS。
8. 来源没有回答 required dimension 时生成维度特定的 `ABSTAINED`。
9. 在完整 inventory 分母上重算 metrics、debt、source composition 和 limitations。
10. 生成 profile 内容地址；应用服务使用 managed signer 附加并立即复验
    domain-separated Ed25519 seal。

### 4.1 Sufficiency

每个维度独立判断：

| 状态 | 条件 |
|---|---|
| `NO_ASSESSED_EVIDENCE` | `PASS + FAIL = 0` |
| `BELOW_MINIMUM_SAMPLE` | assessed 大于 0，但低于 policy 最低样本数 |
| `PARTIAL_COVERAGE` | 样本数足够，但仍有 abstained/stale/missing |
| `MEASURED` | 最低样本满足，且 required unit 全部是 PASS/FAIL |

### 4.2 Wilson 95%

置信区间只使用 `PASS + FAIL`，不把 abstention 当成功或失败，也不从分母删除：

```text
p = passed / assessed
z = 1.959963984540054
denominator = 1 + z^2 / assessed
center = p + z^2 / (2 * assessed)
spread = z * sqrt(p * (1-p) / assessed + z^2 / (4 * assessed^2))
lower = max(0, (center - spread) / denominator)
upper = min(1, (center + spread) / denominator)
```

`assessed = 0` 时 confidence 必须为 `null`，并明确输出
`NO_ASSESSED_EVIDENCE`。

## 5. Java 内核用法

先构造并封印 Owner inventory：

```java
DomainFidelityInventory inventory =
        new DomainFidelityInventory(
                DomainFidelityInventory.SCHEMA_VERSION,
                "customer-service-v1",
                1,
                "",
                scope,
                "customer-service",
                taxonomyRef,
                coverageUnits,
                ownerProvenance,
                CapabilitySnapshot.Lifecycle.ACTIVE,
                effectiveAt,
                expiresAt)
                .seal(objectMapper);
```

只有来源证据已经独立验证后，适配器才能产生 `Measurement`：

```java
var measurement =
        new DomainFidelityProfileProjector.Measurement(
                "refund-golden",
                scenarioCaseRef,
                workbookSeedRef,
                observedAt,
                DomainFidelityProfile.SourceMode.RECORDED,
                true,
                true,
                List.of(
                        new DomainFidelityProfile.DimensionResult(
                                DomainFidelityProfile.Dimension.BEHAVIOR,
                                DomainFidelityProfile.MeasurementOutcome.PASS,
                                DomainFidelityProfile.MeasurementReason.ASSERTIONS_PASSED),
                        new DomainFidelityProfile.DimensionResult(
                                DomainFidelityProfile.Dimension.CONTRACT,
                                DomainFidelityProfile.MeasurementOutcome.PASS,
                                DomainFidelityProfile.MeasurementReason.ASSERTIONS_PASSED)));
```

在固定 evidence cut 投影：

```java
DomainFidelityProfile unsignedProfile =
        DomainFidelityProfileProjector.project(
                objectMapper,
                inventory,
                List.of(measurement),
                new DomainFidelityProfile.ProjectionPolicy(
                        30,
                        Duration.ofDays(30),
                        true,
                        DomainFidelityProfile.CONFIDENCE_METHOD),
                measuredAt);

unsignedProfile.verify(objectMapper);
```

`project` 返回 unsigned profile 是刻意的边界。`DomainFidelityService.projectVerified`
会要求 `SERVICE/WORKLOAD` principal、`MIRROR_FIDELITY_PROJECTION` purpose 和
`RESOURCE_GATEWAY_FIDELITY_PROJECTOR` group，确认引用的是当前 inventory head，再使用受治理的
evidence signer 签名、立即自验并持久化。该方法没有 HTTP 路由；普通业务调用方不能提交
`certifiable=true` 自证来源可信，业务代码也不得安装隐式开发私钥。

### 5.1 受保护 API 用法

路由只在显式开启 `gateway.testing.mirror.enabled=true` 的 `test` 或 `staging` profile
装配，`production` profile 中物理不存在。除 capability probe 外均要求 Bearer credential；
scope、actor、owner、approval time、provenance、lifecycle 和 fingerprint 全部来自服务端。

| 操作 | Purpose | 额外身份约束 |
|---|---|---|
| 注册 inventory revision | `MIRROR_FIDELITY_GOVERNANCE` | `USER/HUMAN`，属于 `RESOURCE_GATEWAY_FIDELITY_OWNER` |
| 读取 inventory | `MIRROR_FIDELITY_GOVERNANCE` 或 `GOVERNANCE_EVIDENCE_INGESTION` | 完整企业 scope |
| 读取 signed profile | `MIRROR_FIDELITY_GOVERNANCE` 或 `GOVERNANCE_EVIDENCE_INGESTION` | 完整企业 scope |
| 投影新 profile | `MIRROR_FIDELITY_PROJECTION` | 仅内部 source adapter；无 HTTP endpoint |

注册第一个 revision：

```bash
curl -X POST http://localhost:8080/api/mirror/domain-fidelity/inventories \
  -H "Authorization: Bearer $HUMAN_OWNER_TOKEN" \
  -H "X-Purpose: MIRROR_FIDELITY_GOVERNANCE" \
  -H "Content-Type: application/json" \
  --data @domain-fidelity-inventory-registration.json
```

请求文件必须匹配
[`domain-fidelity-inventory-registration-request-v1.schema.json`](schemas/resource-gateway-mirror/domain-fidelity-inventory-registration-request-v1.schema.json)。
revision 1 的 `expectedPredecessorFingerprint` 必须为空；后续 revision 必须填当前 head 的
exact fingerprint。服务端拒绝未知/重复/缺失字段、尾随 JSON、过深结构、超限 body、过期窗口、
revision gap、错误 predecessor 和同坐标不同内容。

读取当前 denominator 和最新 profile：

```bash
curl http://localhost:8080/api/mirror/domain-fidelity/inventories/refund-support/latest \
  -H "Authorization: Bearer $GOVERNANCE_TOKEN" \
  -H "X-Purpose: GOVERNANCE_EVIDENCE_INGESTION"

curl http://localhost:8080/api/mirror/domain-fidelity/domains/refund-domain/profiles/latest \
  -H "Authorization: Bearer $GOVERNANCE_TOKEN" \
  -H "X-Purpose: GOVERNANCE_EVIDENCE_INGESTION"
```

还可按 revision 或 content address 精确读取：

- `GET /api/mirror/domain-fidelity/inventories/{inventoryId}/revisions/{revision}`
- `GET /api/mirror/domain-fidelity/profiles/{profileFingerprint}`

默认 demo credential 是 `WORKLOAD`，不能冒充 human owner 注册 inventory。演示写入需要企业
OIDC/mTLS adapter 或显式配置的测试身份 resolver 提供 human principal、owner group 和治理 purpose；
仅修改 `X-Actor-Type`、`X-Groups` 或 scope header 不会改变受信 claims。

### 5.2 Capability probe

`GET /api/integration/capabilities` 暴露以下独立事实：

| Feature | 当前含义 |
|---|---|
| `mirrorDomainFidelityInventoryApi` | inventory register/read route 已装配 |
| `mirrorDomainFidelityProfileReadApi` | signed profile read route 已装配 |
| `mirrorDomainFidelitySigningReady` | managed signer 当前可签名和验签 |
| `mirrorDomainFidelityProjectionReady` | route、signer 和全部 verified source adapter 同时 ready |
| `mirrorDomainFidelityScenarioAdapterReady` | 当前固定为 `false` |
| `mirrorDomainFidelityShadowAdapterReady` | 当前固定为 `false` |
| `mirrorDomainFidelityOutcomeAdapterReady` | 当前固定为 `false` |

调用方不得用前三项推导第四项；readable history、available key 和可生成新 profile 是三个不同
生命周期事实。

## 6. 治理侧离线验真

Test Kit 不链接 Resource Gateway server 或 Spring：

```java
DomainFidelityProfileVerifier verifier =
        new DomainFidelityProfileVerifier();

DomainFidelityProfileVerifier.VerificationResult result =
        verifier.verify(profileJson, inventoryJson, verificationKey);

if (!result.verified()) {
    throw new IllegalStateException(result.reasonCode());
}
```

验证器会独立完成：

- 两份 strict Schema 及 `$ref` 闭包校验；
- inventory 和 profile 内容地址重算；
- exact inventory/profile scope、domain、taxonomy、unit 和 Scenario closure；
- freshness policy 与逐 unit expiry 重算；
- denominator、metrics、Wilson 95%、debt、source composition、assessment 和
  limitations 重算；
- domain-separated attestation material 重算；
- key id、Ed25519、key state、签名时间和签名验证。

合法签名只证明某个 key 签过内容，不证明内容语义正确。因此 Test Kit 会拒绝“修改 Wilson 下界、
延长 freshness、缩小分母后重新计算指纹并用合法 key 重签”的 profile。

`VerificationResult` 只包含 domain id、fingerprint、assessment、闭集 limitations、key id 和稳定
reason code，不输出 Scenario fixture、请求、响应或原始诊断。

## 7. Schema

- [`domain-fidelity-inventory-v1.schema.json`](schemas/resource-gateway-mirror/domain-fidelity-inventory-v1.schema.json)
- [`domain-fidelity-inventory-registration-request-v1.schema.json`](schemas/resource-gateway-mirror/domain-fidelity-inventory-registration-request-v1.schema.json)
- [`domain-fidelity-profile-v1.schema.json`](schemas/resource-gateway-mirror/domain-fidelity-profile-v1.schema.json)

Test Kit 公共资源常量：

```java
CapabilityMirrorProtocol.DOMAIN_FIDELITY_INVENTORY_SCHEMA_RESOURCE
CapabilityMirrorProtocol.DOMAIN_FIDELITY_INVENTORY_REGISTRATION_SCHEMA_RESOURCE
CapabilityMirrorProtocol.DOMAIN_FIDELITY_PROFILE_SCHEMA_RESOURCE
```

Schema 使用 `additionalProperties: false`。profile 不允许业务 payload、自由文本诊断或综合
`score`；未知来源类型也不能以“向前兼容”为理由进入 v1 信任闭包。新来源必须先定义独立 verifier，
再通过新协议版本或经过兼容审查的闭集扩展进入。

## 8. 失败关闭矩阵

| 情况 | 结果 |
|---|---|
| Inventory 无 Owner 审批、过期、撤销或 scope 不一致 | 拒绝投影 |
| Unit 不在 inventory 或 Scenario ref 漂移 | 拒绝投影 |
| 没有来源证据 | 保留 unit，逐维 `MISSING` |
| 证据过期 | 全维 `STALE`，profile 为 `STALE` |
| 证据非 certifiable 或不完整 | 逐维 `ABSTAINED` |
| Outcome/Request Space 没有专门来源 | 独立 abstention debt |
| assessed 低于最低样本 | `BELOW_MINIMUM_SAMPLE`，不能 `MEASURED` |
| producer 缩小 denominator | 服务端与 Test Kit 都拒绝 |
| producer 延长 `expiresAt` 并重签 | 服务端与 Test Kit 都拒绝 |
| producer 伪造 Wilson 区间并重签 | 服务端与 Test Kit 都拒绝 |
| verification key 不可用 | `KEY_UNAVAILABLE`，不能降级接受 |
| key id/算法/生命周期不符 | `INVALID` 或 `POLICY_REJECTED` |

## 9. 当前持久化与事务不变量

1. inventory/profile 表的主键和查询都包含 tenant、organization、project、environment、region。
2. inventory 是 immutable revision stream；revision 1 无 predecessor，后续使用 current head fingerprint
   做 compare-and-set。
3. profile 在 `(scope, domain, inventory fingerprint, measuredAt)` 上只能有一个权威结果。
4. profile 必须绑定数据库中 exact inventory revision/fingerprint，不能凭孤立 JSON 插入。
5. inventory 读取重算内容地址并核对所有重复索引；profile 读取还会重算派生统计并验签。
6. 数据库只存 canonical protocol JSON 与 payload-free 索引，不新增 request/response/fixture/secret 列。
7. 业务写入与 success audit 同事务；success audit 不可用时返回
   `RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE` 并回滚业务行。
8. 跨 scope 查询返回 not found；损坏索引、不可用 verification key 或签名异常均失败关闭。

## 10. 下一实施纵切

repository、managed signer、受保护 inventory/read API 和 capability 分层已完成。下一步按来源信任
依赖推进：

1. 实现 Scenario workbook source adapter：先用 Test Kit 独立验真 seed、child evidence 和签名，
   再映射 payload-free `Measurement`。
2. 将 source adapter readiness 做成可组合 provider，而不是配置布尔值。
3. 实现 read-only shadow comparison、typed diff 和 sampling/egress policy。
4. 实现 authoritative outcome observation 与 delayed/censored reconciliation。
5. 把 drift 自动降级、profile limitations/stale/debt 接入 ANEKE gate 与 Owner workbench。

在第 1 项完成前，`mirrorDomainFidelityProjectionReady` 必须保持 `false`；在 shadow/outcome
未完成前，也不能把 profile 描述为“真实行为已对照”或“业务结果已校准”。
