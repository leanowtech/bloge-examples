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
- Scenario workbook source adapter：重验 aggregate/retention 双签名、workbook/assertion
  内容地址、signed aggregate 对账与 exact inventory case closure；
- Scenario assertion 到 `BEHAVIOR/CONTRACT/EFFECT/STATE_TRANSITION` 的保守映射；
- signed `resourceGateway.readOnlyShadowComparison.v1`、strict Schema、typed diff 与
  Test Kit 独立验真；
- Shadow source adapter：重验 comparison 内容地址/签名、采样授权、kill switch、egress、
  零写证明、同请求双边来源和 exact inventory unit closure；
- Shadow diff 到 `BEHAVIOR/CONTRACT/EFFECT/STATE_TRANSITION` 的保守映射，允许部分
  comparison 集合并把未覆盖 unit 保留为 missing debt；
- 可组合、动态、fail-closed 的 typed source readiness；
- 分开报告 route、signing、各 source adapter 和 partial-profile projection readiness 的
  capability probe。

尚未实现：

- authoritative outcome 到 `Measurement` 的独立来源适配器；
- request-space sampling proof 与 error-distribution cohort adapter；
- durable shadow job、流量采样/限流/熔断、drift 自动降级、outcome reconciliation 和工作台。

因此在 managed signer、Scenario authority 或 signed Shadow comparison authority 可用时，
`mirrorDomainFidelityProjectionReady=true` 只表示系统能从已签名演练证据生成一份显式带
abstention debt 的**部分 profile**。Shadow adapter ready 表示已提供的合法 comparison 可以
独立验真和投影，不表示 Resource Gateway 已具备生产流量复制与 shadow job。请求空间覆盖和业务
结果校准仍必须分别读取 typed adapter flag、source artifact 与 profile limitations。

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

已签名 Scenario run 不需要由调用方手工组装 `Measurement`。内部 projector 使用：

```java
DomainFidelityProfile profile =
        domainFidelityService.projectScenario(
                inventoryRef,
                List.of(scenarioRunIdA, scenarioRunIdB),
                scenarioRehearsalDomainFidelitySource,
                projectorIdentity);
```

`runIds` 的 case 并集必须与 inventory unit 一一对应，不能缺失、重复或夹带其他 case。
适配器会用 `GOVERNANCE_EVIDENCE_INGESTION` 子目的读取 durable aggregate/workbook，再独立重验：

1. aggregate 内容地址和 Ed25519 签名；
2. workbook 内容地址及其与 signed aggregate 的逐 case 对账；
3. retention registration 内容地址和 Ed25519 签名；
4. 每条 assertion result 内容地址；
5. full scope、exact ScenarioCase、case type 和 target capability。

Scenario fixture 来源固定标为 `SYNTHESIZED`。它只映射可证明的四类维度：

| Workbook observation | Fidelity dimension |
|---|---|
| `GRAPH_OUTPUT_SCHEMA` | `CONTRACT` |
| graph value、node/edge status、occurrence、input、error、fallback、governance、latency/retry/resource budget | `BEHAVIOR` |
| `SIDE_EFFECT_RECEIPT`、`COMPENSATION` | `EFFECT` |
| `STATE_TRANSITION`、`FINAL_STATE_INVARIANT` | `STATE_TRANSITION` |

单次确定性演练不会被包装成业务结果、请求分布或故障分布证据，所以
`OUTCOME`、`REQUEST_SPACE`、`ERROR_DISTRIBUTION` 继续 abstain。exploratory 或
`EVIDENCE_INCOMPLETE` child 也会让相应 unit 全维 abstain。

已由受信 Shadow 数据面生成的 signed comparison 同样不需要手工组装 `Measurement`：

```java
DomainFidelityProfile profile =
        domainFidelityService.projectShadow(
                inventoryRef,
                signedComparisons,
                readOnlyShadowDomainFidelitySource,
                projectorIdentity);
```

`signedComparisons` 可以只覆盖部分 inventory unit；遗漏 unit 不会被删除，而会形成
`MISSING/NO_ELIGIBLE_EVIDENCE`。每个 `ReadOnlyShadowComparison.v1` 必须同时冻结：

- exact inventory/unit/ScenarioCase/target capability；
- exact sampling grant、外部 egress attestation 和 enabled kill-switch generation；
- `READ_ONLY` 或 `SAFE_SANDBOX` access mode，且 `writeCredentialExposed=false`、
  `writeAttemptCount=0`；
- 同一 `requestContextFingerprint` 的 baseline/candidate source pair；
- baseline signed observation 与 candidate Mirror evidence bundle 的 exact ref；
- 每个维度的 baseline/candidate normalized fact fingerprint；
- domain-separated content address 和 Ed25519 comparison seal。

typed outcome 不能由 producer 随意填写：

| Outcome | 必须满足 |
|---|---|
| `MATCH` | 双边 fact fingerprint 非空且相等，`diffTypes=[]` |
| `MISMATCH` | 双边 fingerprint 非空且不同，至少一个维度兼容的 diff type |
| `INDETERMINATE` | 至少一边 fingerprint 缺失，且唯一 diff type 为 `EVIDENCE_GAP` |

`OUTPUT_SCHEMA/UNKNOWN_FIELD` 只能进入 `CONTRACT`；
`OUTPUT_VALUE/TERMINAL_STATUS/ERROR_CODE/BRANCH/RETRY/FALLBACK` 只能进入
`BEHAVIOR`；`EFFECT` 与 `STATE` 分别进入 `EFFECT`、`STATE_TRANSITION`。
单请求 comparison 不得证明 `OUTCOME`、`REQUEST_SPACE` 或 `ERROR_DISTRIBUTION`。两边
任一 exploratory、不完整或任一维度 indeterminate 时，投影内核会把来源降为 abstention。

当前 `projectShadow` 是内部 Java 边界，没有 HTTP ingestion route。它证明的是“可信
comparison 可消费”，不是“Resource Gateway 已能复制生产流量”；durable job、采样执行器、
速率预算、熔断和自动 drift downgrade 属于下一纵切。

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
| `mirrorDomainFidelityProjectionReady` | route、signer 和至少一个 verified source adapter ready，可生成显式部分 profile |
| `mirrorDomainFidelityScenarioAdapterReady` | Scenario aggregate/workbook/retention 验真链当前可用 |
| `mirrorDomainFidelityShadowAdapterReady` | signed read-only comparison 可独立验真和投影；不代表生产 shadow job 已装配 |
| `mirrorDomainFidelityOutcomeAdapterReady` | 当前为 `false`；不能宣称业务结果已校准 |

调用方不得用 projection flag 推导 shadow/outcome flag；readable history、available key、
可生成部分 profile、真实行为对照和业务结果校准是不同生命周期事实。
当前 Shadow source adapter 重验 comparison 根签名及其 exact artifact refs，但不会主动拉取并
重验底层 baseline/candidate artifact；这项 source-resolution closure 属于下一轮 durable
data-plane connector。

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

Shadow comparison 使用独立 verifier：

```java
ReadOnlyShadowComparisonVerifier verifier =
        new ReadOnlyShadowComparisonVerifier();

ReadOnlyShadowComparisonVerifier.VerificationResult result =
        verifier.verify(comparisonJson, verificationKey);
```

它会独立拒绝重新签名后的请求错配、sample ordinal 越权、写凭据或写尝试、伪造
`MATCH/MISMATCH/INDETERMINATE`、跨维度 diff type、内容地址、签名、key lifecycle 和
签名时间漂移。

`VerificationResult` 只包含 domain id、fingerprint、assessment、闭集 limitations、key id 和稳定
reason code，不输出 Scenario fixture、请求、响应或原始诊断。

## 7. Schema

- [`domain-fidelity-inventory-v1.schema.json`](schemas/resource-gateway-mirror/domain-fidelity-inventory-v1.schema.json)
- [`domain-fidelity-inventory-registration-request-v1.schema.json`](schemas/resource-gateway-mirror/domain-fidelity-inventory-registration-request-v1.schema.json)
- [`domain-fidelity-profile-v1.schema.json`](schemas/resource-gateway-mirror/domain-fidelity-profile-v1.schema.json)
- [`read-only-shadow-comparison-v1.schema.json`](schemas/resource-gateway-mirror/read-only-shadow-comparison-v1.schema.json)

Test Kit 公共资源常量：

```java
CapabilityMirrorProtocol.DOMAIN_FIDELITY_INVENTORY_SCHEMA_RESOURCE
CapabilityMirrorProtocol.DOMAIN_FIDELITY_INVENTORY_REGISTRATION_SCHEMA_RESOURCE
CapabilityMirrorProtocol.DOMAIN_FIDELITY_PROFILE_SCHEMA_RESOURCE
CapabilityMirrorProtocol.READ_ONLY_SHADOW_COMPARISON_SCHEMA_RESOURCE
```

Schema 使用 `additionalProperties: false`。profile 不允许业务 payload、自由文本诊断或综合
`score`；未知来源类型也不能以“向前兼容”为理由进入 v1 信任闭包。新来源必须先定义独立 verifier，
再通过新协议版本或经过兼容审查的闭集扩展进入。

## 8. 失败关闭矩阵

| 情况 | 结果 |
|---|---|
| Inventory 无 Owner 审批、过期、撤销或 scope 不一致 | 拒绝投影 |
| Unit 不在 inventory 或 Scenario ref 漂移 | 拒绝投影 |
| Scenario workbook case 并集缺失、重复或夹带额外 case | 拒绝整次来源投影 |
| Workbook 与 signed aggregate、retention 或 assertion 指纹不一致 | 拒绝整次来源投影 |
| Shadow comparison 的请求双边、scope、inventory、unit 或 capability 漂移 | 拒绝整次来源投影 |
| Shadow grant 越界、kill switch/egress ref 缺失、存在写凭据或写尝试 | Schema/构造阶段拒绝 |
| Shadow MATCH 与 fact fingerprint 不一致或 diff type 跨维度 | 服务端与 Test Kit 都拒绝 |
| Shadow comparison 仅覆盖部分 inventory | 保留完整分母，遗漏 unit 为 `MISSING` |
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

repository、managed signer、受保护 inventory/read API、Scenario source adapter、signed
read-only Shadow comparison adapter 和 capability 分层已完成。下一步按来源信任依赖推进：

1. 实现 durable shadow job、受控流量复制、速率/并发预算、熔断、kill switch 和来源证据拉取。
2. 把 signed typed diff 接入 drift budget，自动 stale/downgrade/revoke serving conclusion。
3. 实现 authoritative outcome observation 与 delayed/censored reconciliation。
4. 为 `ERROR_DISTRIBUTION` 和 `REQUEST_SPACE` 增加 cohort/sampling proof，而不是借用单次
   Scenario PASS。
5. 把 profile limitations/stale/debt 接入 ANEKE gate 与 Owner workbench。

在 durable shadow job/outcome 未完成前，不能把 adapter readiness 描述为“已接入生产流量”或
“业务结果已校准”。
