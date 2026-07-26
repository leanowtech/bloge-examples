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
- signed `resourceGateway.readOnlyShadowComparison.v1/v2/v3`、strict Schema 与 typed diff；
  v1/v2 保持兼容读取，v2 冻结 exact normalization policy 与 source-resolution attestation，
  v3 当前产出进一步冻结 admission fingerprint、grant/policy/kill-switch authority proof 与确认时序；
- durable `ReadOnlyShadowJobRequest.v1/ReadOnlyShadowJob.v1`、full-scope sample ordinal
  reservation、数据库时钟 deadline、owner/epoch/expiry lease、bounded retry 和 worker；
- protected Shadow submit/read/request/comparison/lifecycle API、同事务 operation/lifecycle audit、
  可选 bounded regional scheduler，以及 API/worker/scheduler/serving 独立 readiness；
- governed Shadow data-plane composition kernel：grant/kill-switch/egress 双重观测、
  独立共享 execution guard、baseline/candidate 隔离 connector、source-resolution verifier、
  typed comparison engine 与逐外部边界 durable heartbeat；
- database-authoritative Shadow execution guard：authority-owned `guardScope`、stable
  guard-policy id/revision、跨副本 concurrency/fixed-window budget、token/epoch lease、
  circuit cool-down 与全局唯一 half-open probe；
- signed online Shadow authority protocol：sampling grant、kill switch 与 shared guard
  policy 使用独立签名域、短有效窗、exact current-head、完整 scope、append-only
  predecessor chain 与 payload-free attestation；
- full-scope database current-head repository、动态 key/revocation lookup、无正向缓存的
  sampling/kill-switch adapter，以及不依赖 server/Spring 的 Test Kit 独立 current-head verifier；
- root-threshold-signed Shadow authority key-set、完整 scope/kind/issuer binding、单调
  generation/revocation floor、不可逆 retained-key lifecycle、root-policy-before-append、
  database-current managed trust store；
- test/staging 受保护 authority publish/page API、冻结 high-water 的有界连续游标、显式媒体
  协议协商，以及不依赖 server/Spring 的 Test Kit key-set/page 独立验签与离线追赶；
- strict lifecycle event/page Schema，Test Kit 可独立重算 comparison、job/request/comparison
  闭包与完整 admission-to-head lifecycle；
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
- 企业 root-policy/control-plane connector、跨区域传播 SLO 与轮换认证、
  真实 baseline/candidate connector、source resolver/comparison policy adapter、drift 自动降级、
  outcome reconciliation 和工作台。

因此在 managed signer、Scenario authority 或 signed Shadow comparison authority 可用时，
`mirrorDomainFidelityProjectionReady=true` 只表示系统能从已签名演练证据生成一份显式带
abstention debt 的**部分 profile**。Shadow adapter ready 表示已提供的合法 comparison 可以
独立验真和投影，不表示 Resource Gateway 已具备生产流量复制与 shadow job。请求空间覆盖和业务
结果校准仍必须分别读取 typed adapter flag、source artifact 与 profile limitations。durable
job API/lifecycle/scheduler ready 也只表示控制面状态机可用；默认 governed data plane 已具备
database guard、签名 authority 协议、current-head repository 和在线 adapter，但动态 trust
policy provider、真实 connector、source verifier 与 comparison engine 仍为 fail-closed，因此保持
`ready=false`，不代表生产流量复制已启用。

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
`MISSING/NO_ELIGIBLE_EVIDENCE`。每个新作业必须生成
`ReadOnlyShadowComparison.v2`，同时冻结：

- exact inventory/unit/ScenarioCase/target capability；
- exact normalization/typed-diff policy 与 signed source-resolution attestation；
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

当前 `projectShadow` 是内部 Java 投影边界，不接受调用方直接上传 comparison。durable
queue/worker 已具备受保护的 job submit/read/request/comparison/lifecycle API、同事务 operation
audit、append-only lifecycle audit、数据库时钟 claim/retry/expire、owner/epoch/expiry fence 和
显式开启的 bounded scheduler。默认 `GovernedReadOnlyShadowDataPlane` 已固定权威、护栏、
connector、来源验真与 comparison 的安全顺序，但其深层 adapter 均默认不可用，所以 worker 与
end-to-end serving readiness 仍为 false；因此 control plane 可用绝不表示“Resource Gateway 已能
复制生产流量”。

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
| 提交 Shadow job | `MIRROR_SHADOW` | 完整企业 scope 与 command scope 精确一致 |
| 读取 Shadow job/request/comparison/lifecycle | `MIRROR_SHADOW` 或 `GOVERNANCE_EVIDENCE_INGESTION` | 完整企业 scope |

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

### 5.2 Shadow job 与生命周期 API

仅演示 control plane：

```bash
./scripts/start-visual-canvas-demo.sh --shadow-jobs
```

同时启用 bounded regional poller：

```bash
./scripts/start-visual-canvas-demo.sh --shadow-scheduler
```

安装 exact detached source connector、独立复核器和 payload-free equality policy：

```bash
./scripts/start-visual-canvas-demo.sh --shadow-detached-data-plane
```

该开关只安装 v2 detached 数据面纵切，不伪造 signer、企业 root-policy、sampling/kill-switch
current head 或 egress authority。上述 authority 未就绪时，worker 仍在调用 connector 前失败关闭。

`--shadow-scheduler` 会把 scheduler partition 与受信 demo identity 的 region/environment
对齐，并拒绝 `prod`、`production`、`live`。企业可以使用 `qa-sg`、`shadow-staging` 等自定义
非生产环境名；真正的隔离根是 `@Profile("!production & (test | staging)")` 和显式
`RG_MIRROR_RUNTIME_ENABLED=true`，环境字符串检查只是纵深防御。production 与
production+test 混合 profile 中，controller、service、repository、worker、scheduler 全部物理缺席。

在线来源提交 body 匹配
[`read-only-shadow-job-request-v1.schema.json`](schemas/resource-gateway-mirror/read-only-shadow-job-request-v1.schema.json)；
v1 不携带来源模式或来源绑定，固定解释为 `ONLINE_EXECUTION`。离线证据模式必须使用
[`read-only-shadow-job-request-v2.schema.json`](schemas/resource-gateway-mirror/read-only-shadow-job-request-v2.schema.json)，
显式设置 `sourceMode=DETACHED_EVIDENCE` 并引用一个 exact `SHADOW_SOURCE_BINDING`。两个版本都只包含
内容地址、授权坐标、sample ordinal 与 deadline：

```bash
curl -i -X POST http://localhost:8080/api/mirror/shadow-jobs \
  -H "Authorization: Bearer $SHADOW_TOKEN" \
  -H "X-Purpose: MIRROR_SHADOW" \
  -H "Content-Type: application/json" \
  --data @read-only-shadow-job.json
```

服务端先认证再解码 JSON，拒绝重复 key、未知/缺失字段、尾随 JSON、超限 byte/depth/node；
调用方传入的 `scope` 必须精确等于认证 identity。相同 `requestId + request fingerprint` 返回同一
job；相同 request id 内容漂移或相同 sampling-grant fingerprint + ordinal 被不同 request 占用时
返回 conflict。首次 admission、job row 与成功 operation audit 在一个事务中提交，audit 不可用会
整体回滚。

detached source pair 必须先通过受保护注册 API 形成：

```bash
curl -i -X POST http://localhost:8080/api/mirror/shadow/source-bindings \
  -H "Authorization: Bearer $SOURCE_ADMIN_TOKEN" \
  -H "X-Purpose: MIRROR_SHADOW_SOURCE_ADMIN" \
  -H "X-BLOGE-Shadow-Source-Binding-Protocol: read-only-shadow-source-binding-v1" \
  -H "Accept: application/vnd.bloge.read-only-shadow-source-binding.v1+json" \
  -H "Content-Type: application/json" \
  --data @read-only-shadow-source-binding-registration.json
```

注册命令不能提供 `bindingFingerprint`、`baselineObservationFingerprint` 或 `bindingSeal`。
服务端先按认证 scope 精确拉取 `candidateEvidenceRef`，独立关闭 bundle content address、
run、plan、target capability、request context 和完成时间，再计算 nested baseline 与 outer
binding 两层内容地址并签名。读取必须使用
`GET /api/mirror/shadow/source-bindings/{bindingId}/revisions/{revision}?fingerprint=...`
和相同协议协商头；不存在 latest fallback。该控制面让 detached connector 有可信输入，但不等于
connector 已装配或 data-plane ready。

读取独立验真闭包：

```bash
curl http://localhost:8080/api/mirror/shadow-jobs/$JOB_ID \
  -H "Authorization: Bearer $GOVERNANCE_TOKEN" \
  -H "X-Purpose: GOVERNANCE_EVIDENCE_INGESTION"

curl http://localhost:8080/api/mirror/shadow-jobs/$JOB_ID/request \
  -H "Authorization: Bearer $GOVERNANCE_TOKEN" \
  -H "X-Purpose: GOVERNANCE_EVIDENCE_INGESTION"

curl "http://localhost:8080/api/mirror/shadow-jobs/$JOB_ID/lifecycle?afterSequence=0&limit=100" \
  -H "Authorization: Bearer $GOVERNANCE_TOKEN" \
  -H "X-Purpose: GOVERNANCE_EVIDENCE_INGESTION"

curl "http://localhost:8080/api/mirror/shadow/source-resolutions/$ATTESTATION_ID/revisions/$REVISION?fingerprint=$FINGERPRINT" \
  -H "Authorization: Bearer $GOVERNANCE_TOKEN" \
  -H "X-Purpose: GOVERNANCE_EVIDENCE_INGESTION" \
  -H "X-BLOGE-Shadow-Source-Resolution-Protocol: read-only-shadow-source-resolution-attestation-v1" \
  -H "Accept: application/vnd.bloge.read-only-shadow-source-resolution-attestation.v1+json"
```

生命周期是 database-ordered append-only fact stream，转移闭集为 `ADMITTED`、`CLAIMED`、
`TAKEN_OVER`、`LEASE_RENEWED`、`RETRY_SCHEDULED`、`SUCCEEDED`、`FAILED`、`EXPIRED`。
每个事实只公开 scope、job/request/record content address、状态、attempt/epoch、owner
fingerprint、数据库时间、comparison fingerprint 与稳定 failure code；payload、credential、
exception message、stack trace 在模型和表结构中都不可表示。`afterSequence` 是 exclusive
cursor，`limit` 为 1..1000；调用方必须根据 `hasMore` 继续取页，不能把单页前缀宣称为完整证据。

默认数据面不可用时，scheduler 只得到 no-work，不 claim、不增加 attempt。要使 worker ready，
客户可以整体注入 operator-owned `ReadOnlyShadowDataPlane`，也可以保留默认治理型组合内核并逐个
提供下表 adapter：

| Adapter | 必须证明 | 默认 |
|---|---|---|
| `ReadOnlyShadowAuthorityPublicationSource` | exact full-scope current head；不向 runtime 暴露历史 predecessor | database-authoritative / ready |
| `ReadOnlyShadowAuthorityTrustStore` | 每次观测动态解析 exact issuer/key 和当前 ACTIVE/RETIRED/REVOKED 生命周期 | unavailable |
| `ReadOnlyShadowSamplingGrantAuthority` | exact execution scope/grant、authority-owned `guardScope`、exact current guard-policy ref、sample 上限、有效窗、共享 limits 与两份签名 authority attestation | signed current-head adapter；因 trust store unavailable 而 fail-closed |
| `ReadOnlyShadowKillSwitchAuthority` | exact current scope/switch generation、enabled 状态、短有效窗与签名 authority attestation | signed current-head adapter；因 trust store unavailable 而 fail-closed |
| `MirrorDeploymentIsolationRunTrustAuthority` | 执行前/后的同一 egress decision、keyset、status 与 agent snapshot | unavailable |
| `ReadOnlyShadowExecutionGuard` | 跨副本并发、窗口速率、熔断、唯一半开探针和 fenced lease | database-authoritative / ready |
| `ReadOnlyShadowBaselineConnector` / `ReadOnlyShadowCandidateConnector` | 同一 request context 的 payload-free source observation 与零写测量 | 默认 unavailable；`--shadow-detached-data-plane` 安装 exact detached pair；显式 online-baseline 配置和三个独立 trust bean 只安装 regional TEE baseline connector |
| `ReadOnlyShadowSourceResolutionVerifier` | 两侧 exact artifact 二次拉取、内容地址/签名/scope/target/authority closure，并签发 append-only proof | 默认 unavailable；`--shadow-detached-data-plane` 安装真实 detached verifier |
| `ReadOnlyShadowComparisonEngine` | exact comparison policy 下的规范化 typed diff | 内置 content-addressed `payload-free-equality-v1` / ready |

组合内核在 baseline、candidate、终态 authority 和 source resolution 前分别续 durable job lease，
并把 shared guard lease 限定到不晚于新的 job lease。任何依赖不可用、权威漂移、写凭据、写尝试、
来源 context 漂移或 policy 漂移都会以稳定 failure reason 失败关闭。`AccessGrant` 中 egress
证明的 artifact kind 是实际部署隔离协议的 `DEPLOYMENT_ISOLATION_ATTESTATION`。

### 5.3 Capability probe

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
| `mirrorReadOnlyShadowJobApi` | protected submit/read/request/comparison/lifecycle route 已装配 |
| `mirrorReadOnlyShadowSourceBindingApi` | detached source-pair register/exact-read route 与 v1/v2 request protocol 已装配 |
| `mirrorReadOnlyShadowSourceBindingReady` | source-binding signer 与 repository 当前可用；不代表 baseline/candidate connector ready |
| `mirrorReadOnlyShadowSourceResolutionApi` | exact signed source-resolution attestation read route 已装配 |
| `mirrorReadOnlyShadowDetachedDataPlaneReady` | exact detached connector、二次来源复核、proof signer、online authority 与 shared guard 整条链 ready；不会由单个 API 或 policy ready 推导 |
| `mirrorReadOnlyShadowLifecycleAudit` | 每个 committed job transition 同事务写入 append-only journal |
| `mirrorReadOnlyShadowWorkerReady` | managed signer 与受信 baseline/candidate data plane 当前可执行 |
| `mirrorReadOnlyShadowScheduling` | bounded regional poller 当前运行；不代表 worker ready |
| `mirrorReadOnlyShadowServingReady` | API + lifecycle + worker + scheduler 全部 ready |

调用方不得用 projection flag 推导 shadow/outcome flag；readable history、available key、
可生成部分 profile、真实行为对照和业务结果校准是不同生命周期事实。
`--shadow-detached-data-plane` 安装的 `DetachedReadOnlyShadowSourceResolutionVerifier` 不信任
两个 connector 的标签，而会再次精确拉取 source binding 与 candidate bundle、重验两者签名和
content address、重跑同一个 policy normalization，并逐项对比 scope、target、request context、
evidence class/completeness 与零写计数。验证通过后才签发
`resourceGateway.readOnlyShadowSourceResolutionAttestation.v1` 并 append-only 落库。proof 显式携带
稳定 `executionId`，区分历史 source completion 与本次 resolution time；comparison 中的 ref
只是入口，治理消费者仍必须读取并独立验真 proof。

detached source binding 也遵守这一边界。`ReadOnlyShadowSourceBindingVerifier` 在独立 Test Kit
中先重算 baseline/binding 双层 content address，再验证 exact v2 job reference、有效窗、binding
authority key/seal，并调用 `MirrorEvidenceVerifier` 关闭 candidate bundle 的 run/scope/plan/target/
request/completion 坐标。它证明“这对离线来源不可歧义且未被篡改”，不证明在线 baseline connector、
egress 或 comparison policy 已可运行。

online baseline 模式与 detached 模式互斥。Resource Gateway 只向 regional TEE sidecar 发送
`OnlineReadOnlyShadowBaselineCommand`：execution/request/scope、immutable artifact refs、
admission fingerprint、sampling/egress/kill-switch 坐标与 deadline。scenario request、生产
endpoint、workload credential 和 request/response payload 均没有可表达字段。sidecar 在
自己的 payload vault 与 workload identity 边界中完成只读调用，并返回签名
`OnlineReadOnlyShadowBaselineObservation`。该证据闭合 command/idempotency、read-only identity、
identity/transport attestation、opaque vault receipt、response Schema、hash-only source I/O、
normalized facts 和写能力实测。

HTTP authority 只接受 private-PKI + SPKI pin + mTLS + certificate identity binding，禁用
redirect，并严格协商
`application/vnd.bloge.online-read-only-shadow-baseline+json` /
`X-BLOGE-Online-Baseline-Protocol: 1.0`。`ready()` 每次读取最多 5 分钟有效的 capability，
同时要求 payload isolation、read-only identity、execution idempotency、vault receipt、
write-credential prohibition 和 exact artifact read 全部为真。调用 timeout 取本地配置和
durable deadline 的较早者；网络 unavailable 与 deterministic protocol rejection 使用不同
失败分类。

运行时配置前缀是
`gateway.testing.mirror.read-only-shadow.online-baseline`，至少设置 `enabled=true` 与
HTTPS `base-uri`；部署方还必须提供 role-separated
`OnlineReadOnlyShadowBaselineTransport`、
`HttpOnlineReadOnlyShadowBaselineAuthority.RequestHeadersProvider` 和
`OnlineReadOnlyShadowBaselineEvidenceAuthority`。缺任一角色时保持 fail closed。当前纵切没有
online candidate 和 online source-resolution verifier，因此 baseline 可连接不等于 worker/data-plane/
serving ready；公共 capability 也暂未单列 baseline readiness。

签名 authority 也遵守同一原则：database publication source ready 只表示 append-only current-head
读写和内容地址可用，不表示 issuer 已受信。`ReadOnlyShadowAuthorityTrustStore` 必须由独立 managed
key/revocation authority 提供，并满足企业传播时限后，sampling/kill-switch authority 才会 ready。

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

detached source proof 还必须单独验真：

```java
ReadOnlyShadowSourceResolutionAttestationVerifier.VerificationResult result =
        new ReadOnlyShadowSourceResolutionAttestationVerifier().verify(
                sourceResolutionJson,
                sourceResolutionAuthorityKey,
                resolutionContext);
```

`resolutionContext` 由 comparison 的 exact source-resolution ref、认证 scope、job request id、
durable `executionId`、v3 admission fingerprint、已读取的 exact source binding 及其独立
verification context 组成。验证器重新执行 source-binding/candidate evidence 验真，重算确定性
proof id、content address、`payload-free-equality-v1` policy ref 和 candidate
behavior/contract/effect/state facts，再检查 source/resolution/authority 时间序、零写事实、key policy
与签名。它按数值比较 artifact revision，不依赖 Jackson `IntNode`/`LongNode` 实现细节。

升级 JSON/JDK/crypto provider 时还必须执行固定跨产物 fixture：

```java
ReadOnlyShadowSourceResolutionCompatibilityFixture fixture =
        CapabilityMirrorProtocol
                .readOnlyShadowSourceResolutionCompatibilityFixture();
if (!fixture.verify().verified()) {
    throw new IllegalStateException("source-resolution wire drift");
}
```

fixture 由服务端真实生成 candidate evidence、source binding 与 source-resolution proof，并以
三把独立 Ed25519 key 签名；Test Kit 从同一 public-only 文件递归重算三层 content address、
deterministic id、policy facts、时间序和签名。它用于发现两边“各自自测全绿”的
canonicalization/domain/key-role 双重假绿，不代表当前 online authority 或数据使用授权已通过。

durable job export 使用第二个独立 verifier：

```java
ReadOnlyShadowJobVerifier verifier =
        new ReadOnlyShadowJobVerifier();

ReadOnlyShadowJobVerifier.VerificationResult result =
        verifier.verify(jobJson, requestJson, comparisonJson, verificationKey);
```

它重算 request fingerprint、确定性 job id、mutable record fingerprint、lifecycle、scope 和
deadline，并在 `SUCCEEDED` 时继续重验 comparison 签名、artifact ref、grant proof、policy
与 source-resolution closure。当前 v3 还重验 admission fingerprint、grant/policy/switch
publication attestation、guard scope、三组 material/attestation `id + revision` 闭包，以及
`admittedAt <= confirmedAt <= observedAt`。单行离线导出无法证明数据库中的 sample ordinal
唯一性或实时 lease owner；这两项仍由在线数据库事务证明。

三类在线 authority publication 使用另一个独立 current-head verifier：

```java
ReadOnlyShadowAuthorityPublicationVerifier verifier =
        new ReadOnlyShadowAuthorityPublicationVerifier();

ReadOnlyShadowAuthorityPublicationVerifier.VerificationResult result =
        verifier.verify(
                publicationJson,
                locallyPinnedCurrentHeadBinding,
                authorityVerificationKey,
                trustedNow);
```

本地 binding 必须包含 publication type、stream id、revision、完整 publication fingerprint、
完整企业 scope 与 issuer；本地 key delegation 也必须精确绑定同一 scope 和 publication type。
verifier 独立执行 strict Schema、协议域分离材料指纹、完整 publication 指纹、current-head
binding、短时窗、guard duration 上限、ACTIVE/RETIRED/REVOKED 生命周期、canonical padded
Base64 和 Ed25519 校验。`RETIRED` key 只接受 `retiredAt` 之前的签名。grant 引用的 guard policy
必须作为第二份 publication 用自己的 current-head binding 和 policy key 独立验证；运行期 grant
同时保留 grant 与 policy 两份 attestation ref，只验证或只传播 grant 内的一段 ref 不构成 policy
authority。

生命周期页使用独立 verifier：

```java
ReadOnlyShadowLifecycleVerifier verifier =
        new ReadOnlyShadowLifecycleVerifier();

ReadOnlyShadowLifecycleVerifier.VerificationResult result =
        verifier.verify(jobJson, lifecyclePageJson);

if (!result.verified() || !result.complete()) {
    throw new IllegalStateException(result.reasonCode());
}
```

它重算 current job record fingerprint，逐事件检查 strict Schema、exact scope/job/request closure、
database append order、合法状态转移和最终 job head。`VERIFIED_PAGE` 仅表示 bounded page 自洽；
只有包含 `ADMITTED` 且 `hasMore=false`、最后事件与 current job 精确闭合时才返回
`VERIFIED_COMPLETE`。

各类 `VerificationResult` 只包含 bounded id/fingerprint/cursor、闭集 outcome 与稳定 reason
code，不输出 Scenario fixture、请求、响应或原始诊断。

## 7. Schema

- [`domain-fidelity-inventory-v1.schema.json`](schemas/resource-gateway-mirror/domain-fidelity-inventory-v1.schema.json)
- [`domain-fidelity-inventory-registration-request-v1.schema.json`](schemas/resource-gateway-mirror/domain-fidelity-inventory-registration-request-v1.schema.json)
- [`domain-fidelity-profile-v1.schema.json`](schemas/resource-gateway-mirror/domain-fidelity-profile-v1.schema.json)
- [`read-only-shadow-comparison-v1.schema.json`](schemas/resource-gateway-mirror/read-only-shadow-comparison-v1.schema.json)
- [`read-only-shadow-comparison-v2.schema.json`](schemas/resource-gateway-mirror/read-only-shadow-comparison-v2.schema.json)
- [`read-only-shadow-comparison-v3.schema.json`](schemas/resource-gateway-mirror/read-only-shadow-comparison-v3.schema.json)
- [`read-only-shadow-job-request-v1.schema.json`](schemas/resource-gateway-mirror/read-only-shadow-job-request-v1.schema.json)
- [`read-only-shadow-job-request-v2.schema.json`](schemas/resource-gateway-mirror/read-only-shadow-job-request-v2.schema.json)
- [`read-only-shadow-source-binding-registration-request-v1.schema.json`](schemas/resource-gateway-mirror/read-only-shadow-source-binding-registration-request-v1.schema.json)
- [`read-only-shadow-source-binding-v1.schema.json`](schemas/resource-gateway-mirror/read-only-shadow-source-binding-v1.schema.json)
- [`read-only-shadow-source-resolution-attestation-v1.schema.json`](schemas/resource-gateway-mirror/read-only-shadow-source-resolution-attestation-v1.schema.json)
- [`online-read-only-shadow-baseline-command-v1.schema.json`](schemas/resource-gateway-mirror/online-read-only-shadow-baseline-command-v1.schema.json)
- [`online-read-only-shadow-baseline-observation-v1.schema.json`](schemas/resource-gateway-mirror/online-read-only-shadow-baseline-observation-v1.schema.json)
- [`online-read-only-shadow-baseline-capability-v1.schema.json`](schemas/resource-gateway-mirror/online-read-only-shadow-baseline-capability-v1.schema.json)
- [`read-only-shadow-job-v1.schema.json`](schemas/resource-gateway-mirror/read-only-shadow-job-v1.schema.json)
- [`read-only-shadow-job-lifecycle-event-v1.schema.json`](schemas/resource-gateway-mirror/read-only-shadow-job-lifecycle-event-v1.schema.json)
- [`read-only-shadow-job-lifecycle-page-v1.schema.json`](schemas/resource-gateway-mirror/read-only-shadow-job-lifecycle-page-v1.schema.json)

Test Kit 公共资源常量：

```java
CapabilityMirrorProtocol.DOMAIN_FIDELITY_INVENTORY_SCHEMA_RESOURCE
CapabilityMirrorProtocol.DOMAIN_FIDELITY_INVENTORY_REGISTRATION_SCHEMA_RESOURCE
CapabilityMirrorProtocol.DOMAIN_FIDELITY_PROFILE_SCHEMA_RESOURCE
CapabilityMirrorProtocol.READ_ONLY_SHADOW_COMPARISON_SCHEMA_RESOURCE
CapabilityMirrorProtocol.READ_ONLY_SHADOW_COMPARISON_V2_SCHEMA_RESOURCE
CapabilityMirrorProtocol.READ_ONLY_SHADOW_COMPARISON_V3_SCHEMA_RESOURCE
CapabilityMirrorProtocol.READ_ONLY_SHADOW_JOB_REQUEST_SCHEMA_RESOURCE
CapabilityMirrorProtocol.READ_ONLY_SHADOW_JOB_REQUEST_V2_SCHEMA_RESOURCE
CapabilityMirrorProtocol.READ_ONLY_SHADOW_SOURCE_BINDING_REGISTRATION_REQUEST_SCHEMA_RESOURCE
CapabilityMirrorProtocol.READ_ONLY_SHADOW_SOURCE_BINDING_SCHEMA_RESOURCE
CapabilityMirrorProtocol.READ_ONLY_SHADOW_SOURCE_RESOLUTION_ATTESTATION_SCHEMA_RESOURCE
CapabilityMirrorProtocol.READ_ONLY_SHADOW_JOB_SCHEMA_RESOURCE
CapabilityMirrorProtocol.READ_ONLY_SHADOW_JOB_LIFECYCLE_EVENT_SCHEMA_RESOURCE
CapabilityMirrorProtocol.READ_ONLY_SHADOW_JOB_LIFECYCLE_PAGE_SCHEMA_RESOURCE
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
| v3 authority proof 缺失、时间倒序，或 grant/policy/switch material 与 attestation 坐标错配 | 服务端与 Test Kit 都拒绝 |
| detached request 没有 exact source binding、模式/版本混用或引用指纹漂移 | strict decoder / source-binding verifier 拒绝 |
| source binding 的 nested baseline/outer address、candidate bundle、scope/plan/target/request/time 任一漂移 | 服务端与 Test Kit 都拒绝 |
| Shadow MATCH 与 fact fingerprint 不一致或 diff type 跨维度 | 服务端与 Test Kit 都拒绝 |
| Shadow comparison 仅覆盖部分 inventory | 保留完整分母，遗漏 unit 为 `MISSING` |
| Shadow API purpose/scope 不符 | 认证后返回 forbidden/not found，不触发 repository lookup 泄漏 |
| 相同 grant ordinal 被不同 request 占用 | admission conflict；不会重复采样 |
| lease 过期后旧 owner heartbeat/complete | owner + epoch + expiry + record fingerprint fence 拒绝 |
| lifecycle page 截断或 head 漂移 | 独立 verifier 返回 `VERIFIED_PAGE` 或 `INVALID`，不能形成完整证据 |
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
9. Shadow job mutation 与对应 lifecycle event 同事务；owner 原文、payload、credential 和异常详情
   不进入 job/lifecycle 表。
10. autonomous scheduler 默认关闭，只服务一个 exact region/environment partition；跨副本
    uniqueness、claim、retry、deadline 与 fencing 始终由数据库权威决定。
11. source binding 以完整 scope、binding id、revision 为 append-only 主键；读取重算两层内容地址、
    复验签名和重复索引，且只接受 exact content-address reference。

## 10. 下一实施纵切

repository、managed signer、受保护 inventory/read API、Scenario source adapter、signed
read-only Shadow comparison adapter、durable queue/worker、protected Shadow API、lifecycle audit、
bounded scheduler、独立 readiness、lifecycle verifier，以及 governed data-plane composition
kernel、database-authoritative execution guard、三类签名 online authority protocol、
append-only current-head repository、managed trust distribution、server adapter、v1/v2
job request、exact detached source-binding repository/API、真实 detached connector/policy/source
resolver、signed source-resolution proof API、独立 Test Kit verifier 与三 authority 跨产物固定签名
fixture 已完成。
下一步按来源信任依赖推进：

1. 接入企业 root-policy/control-plane connector，并认证 authority successor/revocation 的
   跨区域传播时限、outage 和 rolling rotation。
2. 为第一个获授权的真实在线 baseline connector 接入 payload-isolated read adapter；detached
   evidence path 已完成，不得把它误报为在线生产采样。
3. 把 signed typed diff 接入 drift budget，自动 stale/downgrade/revoke serving conclusion。
4. 实现 authoritative outcome observation 与 delayed/censored reconciliation。
5. 为 `ERROR_DISTRIBUTION` 和 `REQUEST_SPACE` 增加 cohort/sampling proof，而不是借用单次
   Scenario PASS。
6. 把 profile limitations/stale/debt 接入 ANEKE gate 与 Owner workbench。

在真实 data-plane connector 与 outcome 未完成前，不能把 queue、API、scheduler 或 adapter
readiness 描述为“已接入生产流量”或“业务结果已校准”。
