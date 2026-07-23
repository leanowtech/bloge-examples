# Resource Gateway Capability Corpus 治理、轨迹发布与精确服务指南

> 本增量把可信 observation 变成可审查、可冻结、可发布的 payload-free 治理事实。
> 当前第四纵切进一步允许 owner 从 reviewed publication 中显式发布 recorded retry trajectory；
> 第三纵切允许 fixture 精确选择 reviewed publication，并在 plan generation
> 创建时经外部 authority 物化为进程内 `RECORDED_EXACT` 结果。它仍不是生产 payload vault，
> trajectory 运行时 resolver 尚未接线，也不把相邻 observation 猜成重试或有状态世界。

## 1. 能力边界

本阶段解决五个问题：

1. 对终态 `QUARANTINED` observation 记录一次不可变人工审查，但不篡改原准入事实。
2. 把一组 exact `ADMITTED` observation 冻结成不可变 corpus candidate revision，并计算确定性的元数据风险。
3. 由授权 owner 对当前 eligible candidate 做第二次来源复验后，追加一个独立 serving publication。
4. 由不可变 fixture 绑定 exact capability/publication；计划创建和重建时重新验证当前 head、policy、grant、
   retention、region、classification、tombstone 和 payload content address，再冻结进单次运行 generation。
5. 由 owner 显式选择同一已发布 corpus 中的 consecutive attempt sources，服务端按当前 retry policy、
   trace/order、grant 和 source authority 复验后，发布独立、payload-free、append-only trajectory fact。

以下能力仍不在本阶段：

- 不在数据库、HTTP、plan、evidence 或普通日志中保存、读取或返回 request/response payload。
- 不内建生产 payload vault、proof service、retention service 或 tombstone authority；只提供部署实现的 SPI。
- 不实现 `RECORDED_TRAJECTORY`、cluster、stateful runtime resolver；本增量只完成 trajectory governance publication。
- 不把 candidate revision 直接当作 serving 数据。
- 不证明当前策略、组织授权或外部 payload 在离线验证后仍然有效。
- 不完成 poisoning、drift、bias、outcome calibration 和删除证明。
- 不把 retryable 单点错误或时间相邻 observation 当作精确重试行为；只有 owner 显式发布的 trajectory 才可进入后续 resolver。

## 2. 五个不可破坏的不变量

### 2.1 审查不是改判

`CapabilityObservationAdmission` 是不可变的历史准入事实。审查只能追加
`CapabilityObservationReview`：

- `CONFIRMED_QUARANTINE`
- `PRODUCER_REMEDIATION_REQUIRED`
- `POLICY_REMEDIATION_REQUIRED`
- `SECURITY_INVESTIGATION_REQUIRED`
- `FALSE_POSITIVE_REINGEST_REQUIRED`

即使误判，也必须修复 producer、policy 或 proof 后，以新的 `observationId` 重新准入。旧记录不能从
`QUARANTINED` 原地变成 `ADMITTED`。

### 2.2 候选不是服务

`CapabilityCorpusRevision` 冻结：

- 完整企业 scope；
- exact capability、governance policy 和 predecessor；
- exact observation/admission；
- request/response payload、proof、schema 的内容地址；
- producer authority key、trace fingerprint、发生时间和可用期限；
- policy-independent 风险统计与 policy-derived eligibility。

它不包含 payload，也不表示 owner 已批准。即使 `eligibility=ELIGIBLE`，仍不能被 resolver 直接读取。

### 2.3 发布不等于自动服务

`CapabilityCorpusPublication` 是独立 append-only lineage。它绑定当前 candidate、当前 publication
policy、owner review ticket、认证 reviewer 和 source horizon。resolver 只能消费 fixture 显式绑定、当前最新且
在线重新验证通过的 publication，不能回退读取 observation store、candidate table 或任意历史 publication。

publication 自身不会改变任何 mirror 结果。只有 immutable fixture metadata 中出现合法 binding，且当前
`CapabilityCorpusGovernancePolicyProvider`、`CapabilityCorpusSourceVerifier` 和
`CapabilityCorpusPayloadAuthority` 同时可用，计划才可编译出 `RECORDED_EXACT`。

### 2.4 轨迹必须显式发布，不能靠时间邻近猜测

`CapabilityCorpusTrajectoryPublication` 拥有独立 lineage，并同时绑定 exact current
`CapabilityCorpusPublication`、其 corpus revision、current publication policy、current retry policy、canonical
request fingerprint 与 2 到 32 个 observation/admission attempt source。服务端重新证明：

- 所有 attempt 都属于 exact published revision，且 grant 同时授权 `EXACT_REPLAY` 与 `TRAJECTORY_MODELING`；
- attempt 从 1 连续编号，observation/span 唯一，trace 相同，sequence 和发生时间递增；
- 除最后一次外都必须是 owner retry policy 允许的 retryable normalized error；
- 最后一次必须是 response 或 non-retryable terminal error；
- corpus policy、retry policy、source lifecycle authority 任一不可用都不发布伪事实。

它不复制 payload，也不证明运行时已能消费。当前 `RECORDED_EXACT` 仍会拒绝 retryable 单点 error，直到
trajectory fixture binding、generation materialization 和 resolver 完成。

### 2.5 物化不是长期 payload 所有权

`CapabilityCorpusPayloadAuthority` 是区域 vault 的短时读取边界，不是 Resource Gateway 的 payload repository。
Resource Gateway 只把 authority 返回的响应 JSON 冻结在当前 `CompiledMirrorPlan` generation 内：

- 每次 plan 创建和 materialize 都重新检查当前 publication head 与当前 policy；
- 每个 source 都重新检查 grant、retention、classification、region、tombstone 与 proof authority；
- 响应 bytes 必须与声明 size 和 `SANITIZED_PAYLOAD` content address 一致，并且是单根 JSON 文档；
- 整个 generation 最多持有 256 MiB，一个 payload 最多 16 MiB，与运行证据协议共用同一限制；
- payload 不进入 public `MirrorPlan`、database、evidence、metrics、audit 或 `toString()`；
- 运行期间不再回读 mutable vault，避免同一 generation 内出现撕裂行为。

## 3. 生命周期

```text
signed observation
  -> ADMITTED ------------------------------+
  -> QUARANTINED -> immutable review         |
                                              v
                                  corpus candidate revision
                                   -> BLOCKED: retain evidence
                                   -> ELIGIBLE
                                         |
                           owner authorization + policy recheck
                           + every source authority recheck
                                         v
                                  serving publication
                                    /            \
        explicit attempts + current retry       fixture exact binding
             policy + owner review                       |
                       |                      online head/policy/source/payload
             trajectory publication                  revalidation
                       |                                 |
            runtime resolver: pending       frozen RECORDED_EXACT generation
```

`ObservationReview`、`CorpusRevision`、`CorpusPublication`、`CorpusTrajectoryPublication` 是四条不同的事实线。
review 以 observation 为唯一终态坐标；candidate、publication 与 trajectory publication 各自拥有独立、连续、
乐观栅栏保护的 revision lineage。

## 4. 协议对象

权威 Schema 位于 `docs/schemas/resource-gateway-mirror/`：

| 对象 | Schema | 语义 |
|---|---|---|
| Review command | `capability-observation-review-request-v1.schema.json` | exact quarantine、ticket、closed disposition |
| Review fact | `capability-observation-review-v1.schema.json` | scope、认证 reviewer、command/artifact fingerprint |
| Candidate command | `capability-corpus-candidate-request-v1.schema.json` | corpus lineage fence 与有序 source coordinates |
| Candidate revision | `capability-corpus-revision-v1.schema.json` | payload-free sources、risk、policy、horizon |
| Publish command | `capability-corpus-publish-request-v1.schema.json` | publication fence、exact candidate、owner ticket |
| Publication fact | `capability-corpus-publication-v1.schema.json` | serving lineage、policy、reviewer、horizon |
| Trajectory command | `capability-corpus-trajectory-publish-request-v1.schema.json` | exact publication/retry policy、2..32 explicit attempt sources、lineage fence |
| Trajectory fact | `capability-corpus-trajectory-publication-v1.schema.json` | canonical request、corpus/retry policy、reviewer、attempts、serving horizon |
| Fixture serving binding | `fixture-mirror-corpus-bindings-v1.schema.json` | immutable capability 到 exact latest publication 的选择 |

所有对象都：

- 使用独立 `schemaVersion`；
- `additionalProperties=false`；
- 限制字段长度、JSON 深度、节点数和 source 数量；
- 使用 canonical SHA-256 内容寻址；
- 把首版的 predecessor `null` 作为必填字段，而不是允许字段消失；
- 不包含 raw payload、业务键、credential、secret、provider message 或 stack trace。

共享固定样本是
`docs/schemas/resource-gateway-mirror/capability-corpus-stage2-v1.fixture.json`。服务端生产者与独立
test-kit 都会验证同一份 review、candidate 和 publication 生命周期。

Serving binding 的固定 payload-free 样本是
`docs/schemas/resource-gateway-mirror/fixture-mirror-corpus-bindings-v1.fixture.json`。服务端 parser 和独立
`FixtureMirrorCorpusBindingsVerifier` 都验证 strict Schema、canonical capability order、capability/publication
唯一性和 artifact kind。离线 verifier 故意不声称 publication 仍是 live head，也不声称 policy、grant、tombstone
或 vault 当前可用。

### 4.1 Fixture 绑定示例

该对象只能放在 `FixtureBundle.metadata.mirrorCorpus`，不能由 plan/run 请求临时注入：

```json
{
  "schemaVersion": "resourceGateway.fixtureMirrorCorpusBindings.v1",
  "publications": [
    {
      "capabilityRef": {
        "kind": "CAPABILITY",
        "id": "operator:customer.lookup",
        "revision": 3,
        "fingerprint": "sha256:1111111111111111111111111111111111111111111111111111111111111111"
      },
      "publicationRef": {
        "kind": "CAPABILITY_CORPUS_PUBLICATION",
        "id": "customer-lookup-corpus",
        "revision": 7,
        "fingerprint": "sha256:2222222222222222222222222222222222222222222222222222222222222222"
      }
    }
  ]
}
```

`publications` 必须按 capability `(id, revision, fingerprint)` 严格升序；capability 与 publication 都不能重复。
每个 capability 必须恰好存在于当前 graph closure 的 external invocation binding 中，未使用 binding、内部节点
binding、跨 scope publication、旧 publication head 或 fingerprint 不一致都会在调度前失败关闭。

## 5. 受保护 API

三条路由只在以下条件同时满足时装配：

```text
active profile in {test, staging}
AND production profile is absent
AND gateway.testing.mirror.enabled=true
```

所有请求需要认证 workload identity、完整
`tenant/organization/project/environment/region` scope，以及专用 purpose
`MIRROR_CORPUS_GOVERNANCE`。

| API | 作用 |
|---|---|
| `POST /api/mirror/observations/{observationId}/reviews` | 追加一次 terminal quarantine review |
| `POST /api/mirror/corpus-candidates` | 冻结一版 non-serving corpus candidate |
| `POST /api/mirror/corpus-publications` | 发布一版 owner-reviewed serving fact |
| `POST /api/mirror/corpus-trajectories` | 从 exact current publication 发布一版 owner-reviewed retry trajectory |

### 5.1 Review 示例

```json
{
  "schemaVersion": "resourceGateway.capabilityObservationReviewRequest.v1",
  "observationRef": {
    "kind": "CAPABILITY_OBSERVATION",
    "id": "support-refund-quarantine-0001",
    "revision": 1,
    "fingerprint": "sha256:1111111111111111111111111111111111111111111111111111111111111111"
  },
  "admissionRef": {
    "kind": "CAPABILITY_OBSERVATION_ADMISSION",
    "id": "support-refund-quarantine-0001:admission",
    "revision": 1,
    "fingerprint": "sha256:2222222222222222222222222222222222222222222222222222222222222222"
  },
  "disposition": "PRODUCER_REMEDIATION_REQUIRED",
  "reviewTicketRef": {
    "kind": "GOVERNANCE_REVIEW_TICKET",
    "id": "SEC-2030-0007",
    "revision": 1,
    "fingerprint": "sha256:3333333333333333333333333333333333333333333333333333333333333333"
  },
  "reasonCode": "PRODUCER_SIGNATURE_REMEDIATION"
}
```

### 5.2 Candidate 示例

```json
{
  "schemaVersion": "resourceGateway.capabilityCorpusCandidateRequest.v1",
  "corpusId": "support-refund-corpus",
  "revision": 1,
  "expectedPredecessorRef": null,
  "capabilityRef": {
    "kind": "CAPABILITY",
    "id": "graph:support-refund",
    "revision": 7,
    "fingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  },
  "sources": [
    {
      "observationRef": {
        "kind": "CAPABILITY_OBSERVATION",
        "id": "support-refund-observation-0001",
        "revision": 1,
        "fingerprint": "sha256:1f3b78c8cc7112b6cf4f218b2709339c643b9cac4381ff1e3c4115c2083709b0"
      },
      "admissionRef": {
        "kind": "CAPABILITY_OBSERVATION_ADMISSION",
        "id": "support-refund-observation-0001:admission",
        "revision": 1,
        "fingerprint": "sha256:6666666666666666666666666666666666666666666666666666666666666666"
      }
    }
  ]
}
```

source 必须按 `observationRef.id` 严格升序、无重复，数量为 `1..1000`。revision 1 的 predecessor 必须明确为
`null`；后续 revision 必须引用当前 exact head。

### 5.3 Publication 示例

```json
{
  "schemaVersion": "resourceGateway.capabilityCorpusPublishRequest.v1",
  "corpusId": "support-refund-corpus",
  "publicationRevision": 1,
  "expectedPublicationRef": null,
  "corpusRevisionRef": {
    "kind": "CAPABILITY_CORPUS_REVISION",
    "id": "support-refund-corpus",
    "revision": 1,
    "fingerprint": "sha256:5823cbe123386aa74fec0946c31a99aac51860b08275891b5ec2a2c8c96703de"
  },
  "reviewTicketRef": {
    "kind": "GOVERNANCE_REVIEW_TICKET",
    "id": "GOV-2030-0101",
    "revision": 4,
    "fingerprint": "sha256:9999999999999999999999999999999999999999999999999999999999999999"
  },
  "reasonCode": "OWNER_APPROVED"
}
```

publish 只接受当前 candidate head。candidate 创建后若又追加了新 revision，旧 candidate 即使 eligible 也不能再
发布。

## 6. Operator-owned 接线

默认组合根会安装三个 unavailable provider，防止演示配置被误当成信任：

- `CapabilityCorpusGovernancePolicyProvider`
- `CapabilityCorpusSourceVerifier`
- `CapabilityCorpusPayloadAuthority`

企业环境需要提供自己的 Spring bean。`@ConditionalOnMissingBean` 会让部署实现替换默认 placeholder。

### 6.1 Governance policy provider

provider 必须原子返回一个 generation，至少绑定：

- exact full scope 与 capability revision；
- candidate governance policy ref；
- serving publication policy ref；
- quarantine reviewer groups；
- publisher groups；
- minimum/maximum samples；
- maximum duplicate basis points；
- minimum distinct producer keys；
- minimum remaining serving horizon。

不得从请求体读取 group、阈值、policy ref 或 reviewer identity。provider 不能把不同刷新时刻的配置片段拼成一个
“撕裂”的 policy generation。

### 6.2 Source verifier

source verifier 每次 candidate 创建和 publication 发布都必须重新检查：

- exact sanitized payload reference；
- exact sanitization proof；
- exact JSON Schema；
- data-use grant 与用途；
- tenant、classification、region；
- retention horizon；
- tombstone、legal hold 与删除状态；
- observation/admission 所绑定的 exact fingerprint。

接口只返回 `VERIFIED`、`REJECTED` 或 `UNAVAILABLE`，不得把 payload 返回 Resource Gateway。确定的失效返回
`REJECTED`；authority 超时、撕裂读或无法确认必须返回 `UNAVAILABLE`。

### 6.3 Payload authority

payload authority 只接收 exact full scope、capability/publication/observation/payload/proof/schema/grant refs、
server-minted `MIRROR_REHEARSAL` purpose、classification、vault region、声明 size 与完整 plan horizon。部署实现必须：

- 在同一可信读中验证 tenant、region、grant、proof、retention 与 tombstone generation；
- 只返回已脱敏响应的 canonical JSON，不返回 request payload；
- 对删除、撤销、grant 失效和 policy denial 返回 `REJECTED`；
- 对超时、部分读、generation 不一致和无法证明返回 `UNAVAILABLE`；
- 不缓存超过调用生命周期，不记录 bytes、业务键、provider message 或 secret；
- 提供独立的并发、吞吐、超时、熔断和审计 SLO，不能借 Resource Gateway 数据库承担 vault 职责。

当前仓库只提供 fail-closed SPI 与测试 authority，不提供可投产的 vault adapter。

## 7. 运行期解析语义

plan 创建和每次 durable run materialize 都执行同一条在线门禁：

```text
fixture exact binding
 -> exact publication lookup + latest-head equality
 -> exact corpus revision/capability/scope equality
 -> current governance/publication policy equality
 -> ELIGIBLE risk state + full plan horizon
 -> exact observation/admission/source lineage
 -> current grant/classification/region/retention checks
 -> source lifecycle/tombstone authority
 -> response payload authority + size/content-address/single-JSON checks
 -> conflict-free request-fingerprint index
 -> immutable run-generation snapshot
```

固定 resolver 顺序由 execution-control fingerprint 封印：

```text
OWNER_SPECIFIED
 -> RECORDED_EXACT
 -> GOVERNED_REPLAY
 -> ABSTAINED
```

owner rule 因此可以显式覆盖 corpus；corpus 未命中当前 canonical request fingerprint 时继续进入 governed replay，
最终仍然失配就 `ABSTAINED`，绝不落到真实 external call。相同 request fingerprint 的多条 source 只有 outcome
完全一致才会折叠，并在 limitation 中记录 source count；response 或 normalized error 冲突会以
`RG.MIRROR.CORPUS_EXACT_CONFLICT` 拒绝整个 generation。

成功响应以 `HASH_ONLY` 输出进入 mirror evidence，同时附 exact publication、revision、policy、review、
observation、admission、payload/proof/schema、authority key 和 grant provenance。非重试 normalized error 保留稳定
error code/type；retryable 单点 error 返回 `RG.MIRROR.CORPUS_RETRYABLE_ERROR_UNSUPPORTED`，因为正确拟合需要完整
attempt/退避/最终结果 trajectory，不能把一个观测点重复播放成虚假业务行为。

## 8. 风险与发布门禁

candidate 会确定性计算：

- sample count；
- unique/duplicate request count；
- maximum request multiplicity；
- distinct producer-key count；
- duplicate basis points；
- earliest source horizon。

门禁失败会持久化 `BLOCKED` candidate 及 closed reasons，便于审计和修复，但 publish 必须拒绝。这样既不会丢掉
失败证据，也不会让“记录下来”被误解为“可以服务”。

publish 还要求：

1. candidate 是当前 revision head；
2. candidate `ELIGIBLE`；
3. 当前 governance policy ref 与 candidate 完全相同；
4. 认证 actor 命中 operator-owned publisher group；
5. 当前 publication predecessor 精确匹配；
6. 每个 source 再次通过外部 authority；
7. serving horizon 尚未到期。

## 9. 幂等、并发与事务

- exact retry 先读取已提交事实，再访问 mutable policy/source provider。
- 同坐标、同 command fingerprint 返回原事实。
- 同坐标、不同 command fingerprint 返回 `409`。
- candidate、publication 和 trajectory publication 使用三套独立 predecessor fence。
- review、candidate、publication、trajectory publication 的正文写入与 mandatory success audit 位于同一数据库事务。
- audit 失败返回 `503 RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE`，本次治理写入回滚。
- 失败 audit 使用独立事务保存；业务 payload 不进入 audit。

H2 表只保存完整 scope、索引元数据和 canonical payload-free JSON：

- `mirror_capability_observation_reviews`
- `mirror_capability_corpus_revisions`
- `mirror_capability_corpus_publications`
- `mirror_capability_corpus_trajectories`

每次读取同时复验 JSON fingerprint 与冗余索引列，索引漂移不能改变治理结果。

## 10. Capability Probe

调用：

```bash
curl -sS http://localhost:8080/api/integration/capabilities
```

分别判断：

| Flag | 含义 |
|---|---|
| `mirrorCorpusGovernanceProtocol` | review/candidate/publication v1 wire object 与 strict Schema 已支持 |
| `mirrorCorpusExactResolverProtocol` | fixture binding 与 `RECORDED_EXACT` 运行协议已支持 |
| `mirrorCorpusGovernanceApi` | 三条 test/staging route 已装配 |
| `mirrorCorpusGovernanceReady` | policy provider 与 source verifier 当前都可用 |
| `mirrorCorpusTrajectoryPublicationProtocol` | trajectory command/fact、fingerprint 和 strict Schema 已支持 |
| `mirrorCorpusTrajectoryPublicationApi` | `POST /api/mirror/corpus-trajectories` 已装配 |
| `mirrorCorpusTrajectoryPublicationReady` | corpus policy、retry policy、source authority 当前都可用 |
| `mirrorCorpusResolverReady` | policy、source lifecycle、payload authority 当前都可用 |

`supportedObjects.fixtureMirrorCorpusBindings` 应包含
`resourceGateway.fixtureMirrorCorpusBindings.v1`。默认 demo 中三个 protocol 为 `true`，相关 API 为 `true`，三个
readiness 通常为 `false`。客户端不能用 `Api=true` 推导 `Ready=true`，也不能用
`GovernanceReady=true` 推导 resolver 已可物化 payload。

## 11. 启动、停止与演示

从仓库根目录启动：

```bash
RG_MIRROR_RUNTIME_ENABLED=true \
  ./scripts/start-visual-canvas-demo.sh --profile test
```

查看状态和 capability probe：

```bash
./scripts/status-visual-canvas-demo.sh
curl -sS http://localhost:8080/api/integration/capabilities
```

停止：

```bash
./scripts/stop-visual-canvas-demo.sh
```

默认外部 providers 是 fail-closed placeholder，因此 probe 会显示 governance API 已安装但未 ready，实际治理请求会
返回稳定 `503`。要演示成功 candidate/publication，必须在演示应用中安装测试专用 policy 与 source authority
beans；要演示 `RECORDED_EXACT` 还必须安装测试专用 payload authority。不要把 always-verified 或内存 payload
adapter 放进共享或生产配置。trajectory publication 还必须安装 operator-owned
`CapabilityRetryPolicyProvider`；默认实现不可用，不能信任请求自报的 `retryPolicyRef`。
策略中的错误类与错误码是单调收紧的两个维度：空集合表示该维度不参与约束；两者都配置时必须同时命中，
不能用一个维度的命中掩盖另一个维度的不匹配。

## 12. 稳定错误与处置

| 类型 | 代表错误 | 处置 |
|---|---|---|
| 输入关闭 | `RG.MIRROR.CORPUS_REQUEST_MALFORMED` | 修正字段、版本、大小、深度、顺序 |
| scope/purpose | `CORPUS_GOVERNANCE_PURPOSE_REQUIRED`、`CORPUS_SCOPE_INCOMPLETE` | 修复 workload identity，不改请求冒充 scope |
| review 冲突 | `OBSERVATION_NOT_QUARANTINED`、`OBSERVATION_REVIEW_CONFLICT` | 查原 admission/review；新事实使用新 observation |
| source 确定失效 | `CORPUS_SOURCE_INELIGIBLE`、`CORPUS_SOURCE_REJECTED` | 修复/重录 source，不重试同一内容 |
| provider 不确定 | `CORPUS_POLICY_UNAVAILABLE`、`CORPUS_SOURCE_AUTHORITY_UNAVAILABLE` | 恢复 authority 后 exact retry |
| lineage 冲突 | `CORPUS_REVISION_HEAD_CONFLICT`、`CORPUS_PUBLICATION_HEAD_CONFLICT` | 读取 current head，重新生成下一 command |
| 发布门禁 | `CORPUS_CANDIDATE_INELIGIBLE`、`CORPUS_POLICY_DRIFTED` | 新建 candidate，不修改旧 revision |
| trajectory 策略/结构 | `CORPUS_TRAJECTORY_RETRY_INVALID`、`CORPUS_TRAJECTORY_ORDER_INVALID`、`RETRY_POLICY_UNAVAILABLE` | 修复显式 attempt 选择或恢复 owner retry-policy authority；禁止猜测/降级 |
| serving binding | `RG.MIRROR.CORPUS_BINDING_INVALID`、`RG.MIRROR.CORPUS_PUBLICATION_STALE`、`RG.MIRROR.CORPUS_POLICY_DRIFT` | 修复 fixture 或重新审阅/发布，不回退历史 head |
| payload serving | `RG.MIRROR.CORPUS_PAYLOAD_UNUSABLE`、`RG.MIRROR.CORPUS_PAYLOAD_INTEGRITY_INVALID` | 查 tombstone/proof/content address；不得降级到未校验 bytes |
| outcome 冲突 | `RG.MIRROR.CORPUS_EXACT_CONFLICT`、`RG.MIRROR.CORPUS_RETRYABLE_ERROR_UNSUPPORTED` | 修复污染 source；retryable 行为进入 trajectory pipeline |
| 存储/审计 | `CORPUS_STORE_UNAVAILABLE`、`OPERATION_AUDIT_UNAVAILABLE` | 按 outage runbook 恢复；确认事务未留下半成品 |

错误详情不返回 provider message、SQL、payload 或 stack trace。

## 13. 独立验证

`resource-gateway-test-kit` 提供：

```java
CapabilityCorpusCompatibilityFixture fixture =
        CapabilityMirrorProtocol.capabilityCorpusCompatibilityFixture();
CapabilityCorpusVerifier.VerificationResult result =
        new CapabilityCorpusVerifier().verify(fixture);
if (!result.verified()) {
    throw new IllegalStateException(result.reasonCode());
}
```

独立 verifier 会复验 strict Schema、六个 canonical fingerprint、full scope、command-to-fact binding、source
顺序、lineage、risk statistics 和时间窗。它不会证明 live payload/proof、当前策略、授权 group 或 current serving
head；这些必须由在线 authority 和服务端门禁确认。

fixture serving binding 可独立做结构验证：

```java
FixtureMirrorCorpusBindingsVerifier.VerificationResult binding =
        new FixtureMirrorCorpusBindingsVerifier().verify(bindingJson);
if (!binding.verified()) {
    throw new IllegalStateException(binding.outcome().name());
}
```

该 verifier 只证明 strict Schema、canonical order、唯一 coordinate 与 artifact kind，不证明 live head、policy、
grant、retention、tombstone、payload bytes 或 runtime readiness。

trajectory command/fact 可独立验证：

```java
CapabilityCorpusTrajectoryVerifier.VerificationResult trajectory =
        new CapabilityCorpusTrajectoryVerifier().verify(
                command, publication, corpusPublication, corpusRevision, verificationTime);
if (!trajectory.verified()) {
    throw new IllegalStateException(trajectory.reasonCode());
}
```

该 verifier 重算四个 content address、检查 command-to-fact、corpus publication/revision、attempt membership、
连续编号、共同 request fingerprint 与 horizon。成功结果仍显式携带四项 online limitations；retry policy、
normalized outcome、trace ordering、grant、retention、tombstone 和 payload authority 必须在线复验。

## 14. 上线前剩余门禁

进入生产流量或 certification 前至少补齐：

1. 生产级 payload/proof/grant/retention/tombstone authority 与 outage SLO。
2. candidate/publish 外部调用的 prepare/verify/commit 分段，缩短数据库事务持有时间，并用 generation token 防止
   verification 后漂移。
3. poison、burst、producer concentration、bias 和 holdout validation。
4. owner review queue、双人审批或高风险 separation of duties。
5. 多副本 resolver generation cache、撤销/过期传播、内存清零和 stale-while-deny 语义。
6. retention deletion proof、legal hold 与 corpus lineage 重建。
7. trajectory fixture binding、generation materialization、`RECORDED_TRAJECTORY` resolver，以及 stateful/cluster
   resolver 的 confidence、abstention 和 outcome calibration。
8. 非 Java fixed fixture、数字 canonicalization 和客户环境 certification。
9. payload authority 的负载、熔断、区域故障、vault generation 撕裂与 256 MiB generation budget 压测。

在这些门禁完成前，本增量应被描述为“治理事实/显式 retry trajectory publication 管线与 test/staging exact
serving kernel 已闭合”，不能描述为
“保真语料服务已生产就绪”。
