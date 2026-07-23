# Resource Gateway Capability Corpus 治理、轨迹/分簇发布与运行服务指南

> 本增量把可信 observation 变成可审查、可冻结、可发布的 payload-free 治理事实。
> 第四纵切允许 owner 从 reviewed publication 中显式发布 recorded retry trajectory；当前第五纵切进一步
> 允许 fixture 精确绑定这些 trajectory，在 plan generation 创建时完成在线复验、短时 payload 物化，
> 并通过 BLOGE 原生重试循环按 attempt 解析为 `RECORDED_TRAJECTORY`。第六纵切新增 externally validated、
> owner-reviewed recorded-cluster publication；第七纵切用 strict fixture binding、在线 authority/content-address
> 复验和 identity-safe projection 接入 `RECORDED_CLUSTER` runtime。它仍不是生产 payload vault、session state
> 或有状态世界。

## 1. 能力边界

本阶段解决八个问题：

1. 对终态 `QUARANTINED` observation 记录一次不可变人工审查，但不篡改原准入事实。
2. 把一组 exact `ADMITTED` observation 冻结成不可变 corpus candidate revision，并计算确定性的元数据风险。
3. 由授权 owner 对当前 eligible candidate 做第二次来源复验后，追加一个独立 serving publication。
4. 由不可变 fixture 绑定 exact capability/publication；计划创建和重建时重新验证当前 head、policy、grant、
   retention、region、classification、tombstone 和 payload content address，再冻结进单次运行 generation。
5. 由 owner 显式选择同一已发布 corpus 中的 consecutive attempt sources，服务端按当前 retry policy、
   trace/order、grant 和 source authority 复验后，发布独立、payload-free、append-only trajectory fact。
6. 由同一 fixture 同时绑定 exact corpus publication 与 reviewed trajectory publication；每次 materialize
   重验 current head、retry policy、source authority 和 payload，再将 attempt sequence 冻结进单次 generation，
   由真实 BLOGE retry loop 驱动，不另造一套重试引擎。
7. 接收外部数据面生成的 payload-free cluster validation，在 owner policy 下重新验证 exact corpus、support、
   identity safety、holdout/Wilson confidence、grant、retention 和 lineage，再追加独立 recorded-cluster
   publication；发布过程不读取 payload。
8. 由同一 fixture 绑定 exact cluster publication；每次 generation 创建时重验 current head、policy、
   validation、member grant/lifecycle/horizon 和 payload content address，只按 exact match paths 命中，并把
   当前请求身份结构化投影到代表响应。

以下能力仍不在本阶段：

- 不在数据库、HTTP、plan、evidence 或普通日志中保存、读取或返回 request/response payload。
- 不内建生产 payload vault、proof service、retention service 或 tombstone authority；只提供部署实现的 SPI。
- 不实现 session-state 或 stateful runtime resolver；`RECORDED_TRAJECTORY` 只表达显式发布的单节点重试序列，
  `RECORDED_CLUSTER` 不推断未声明状态。
- 不把 candidate revision 直接当作 serving 数据。
- 不证明当前策略、组织授权或外部 payload 在离线验证后仍然有效。
- 不完成 poisoning、drift、bias、outcome calibration 和删除证明。
- 不把 retryable 单点错误或时间相邻 observation 当作精确重试行为；只有 owner 显式发布的 trajectory 才可进入后续 resolver。

## 2. 不可破坏的不变量

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

它不复制 payload，也不单独赋予运行权限。只有 `FixtureBundle.metadata.mirrorTrajectories` 同时精确绑定
capability、corpus publication 与 trajectory publication，且在线重验全部通过后，运行时才会创建
`RECORDED_TRAJECTORY` source。未进入显式 trajectory 的 retryable 单点 error 继续失败关闭。

运行时 attempt 从一开始计数。非终态 retryable error 只在 `RECORDED_TRAJECTORY` source 中降低为 BLOGE
`RetryableException`；owner fixture、standalone exact error 和 trajectory 终态 error 都保持 non-retryable。
trajectory 最大 attempt 数还必须不超过冻结节点的 `retryAttempts + 1`，否则执行计划编译以
`CONTROL_PLAN_TRAJECTORY_RETRY_INCOMPATIBLE` 拒绝，不能运行到中途才耗尽。

### 2.5 物化不是长期 payload 所有权

`CapabilityCorpusPayloadAuthority` 是区域 vault 的短时读取边界，不是 Resource Gateway 的 payload repository。
Resource Gateway 只把 authority 返回的响应 JSON 冻结在当前 `CompiledMirrorPlan` generation 内：

- 每次 plan 创建和 materialize 都重新检查当前 publication head 与当前 policy；
- 每个 source 都重新检查 grant、retention、classification、region、tombstone 与 proof authority；
- 响应 bytes 必须与声明 size 和 `SANITIZED_PAYLOAD` content address 一致，并且是单根 JSON 文档；
- 整个 generation 最多持有 256 MiB，一个 payload 最多 16 MiB，与运行证据协议共用同一限制；
- payload 不进入 public `MirrorPlan`、database、evidence、metrics、audit 或 `toString()`；
- 运行期间不再回读 mutable vault，避免同一 generation 内出现撕裂行为。

### 2.6 分簇验证不等于跨实体复制

`CapabilityCorpusClusterValidation` 必须由外部 data-plane authority 产生并保持 payload-free。它绑定 exact
current corpus publication/revision、2..1000 个有序 source、代表 source、request match JSON Pointer、identity
mode/projection、distinct identity count、独立 holdout 和可重算的 95% Wilson precision interval。

Resource Gateway 的 publication service 仍会独立证明：

- validation content address、scope、capability、corpus 和 authority currentness 完全一致；
- 每个 member/representative 都属于 exact corpus，响应 Schema 相同，且 grant 同时允许
  `EXACT_REPLAY` 与 `CLUSTER_MODELING`；
- owner policy 的 minimum support/identity/holdout、maximum false-positive basis points、
  minimum confidence lower bound、publisher 和 path policy 全部满足；
- `IDENTITY_FREE_RESPONSE` 没有 projection；`REQUEST_PROJECTION` 的 request/response paths 合法、有序，
  response paths 全局不重复且互不为父子路径，identity coverage 明确 complete；
- publication horizon 不越过 corpus、validation、source retention 或 owner policy 的任一上界。

不能证明身份字段完整覆盖时必须拒绝发布。运行时还会先验证当前 request source 和代表 response destination
全部存在，再清空并从当前 request 回填所有声明路径；任一缺口 `ABSTAINED`，同一请求命中多个 cluster 则
`MIRROR_CLUSTER_AMBIGUOUS` 失败关闭。绝不允许把实体 A 的完整响应直接返回给实体 B，也不允许用字符串替换
伪装结构化身份 projection。

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
                       /                   |                    \
          exact fixture binding   explicit attempts +     external holdout +
                       |           current retry policy    identity validation
                       |            + owner review                |
                       |                 v                         v
                       |       trajectory publication      cluster publication
                       |                 |                         |
                       |       fixture trajectory binding  fixture cluster binding
                       \                 |                         /
                online head/policy/validation/source/payload revalidation
                                      |
       frozen RECORDED_EXACT + RECORDED_TRAJECTORY + RECORDED_CLUSTER generation
                                  |
                        BLOGE native retry loop
```

`ObservationReview`、`CorpusRevision`、`CorpusPublication`、`CorpusTrajectoryPublication`、
`CapabilityCorpusClusterPublication` 是不同的事实线。review 以 observation 为唯一终态坐标；candidate、
publication、trajectory publication 与 cluster publication 各自拥有独立、连续、乐观栅栏保护的 revision
lineage。三类 serving source 各自保持独立索引和 provenance，不因进入同一 generation 而合并事实线。

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
| Cluster validation | `capability-corpus-cluster-validation-v1.schema.json` | external support、match paths、identity projection、holdout 与 Wilson confidence proof |
| Cluster command | `capability-corpus-cluster-publish-request-v1.schema.json` | exact corpus/policy/validation、owner ticket 与 cluster lineage fence |
| Cluster fact | `capability-corpus-cluster-publication-v1.schema.json` | payload-free support、identity safety、confidence、reviewer 与 serving horizon |
| Fixture serving binding | `fixture-mirror-corpus-bindings-v1.schema.json` | immutable capability 到 exact latest publication 的选择 |
| Fixture trajectory binding | `fixture-mirror-trajectory-bindings-v1.schema.json` | 同一 fixture 在已选 corpus 下选择 reviewed trajectory |
| Fixture cluster binding | `fixture-mirror-cluster-bindings-v1.schema.json` | 同一 fixture 在已选 corpus 下选择 reviewed cluster |

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

Trajectory binding 的固定样本是
`docs/schemas/resource-gateway-mirror/fixture-mirror-trajectory-bindings-v1.fixture.json`。服务端还会把每个
trajectory 与同一 fixture 的 `mirrorCorpus` 选择做 exact equality 对账；独立 verifier 只验证 strict Schema、
canonical capability/trajectory order 和 trajectory coordinate 唯一性，不冒充在线治理权威。

Cluster publication 的固定样本是
`docs/schemas/resource-gateway-mirror/capability-corpus-cluster-stage2-v1.fixture.json`。服务端和独立
`CapabilityCorpusClusterVerifier` 会重算 corpus/validation/command/publication content address、member/
representative membership、共同 response Schema、identity path topology、holdout 计数和 Wilson 区间。成功结果
仍明确保留 current policy、validation authority、grant/retention、source/payload authority 等在线限制。

Cluster binding 的固定样本是
`docs/schemas/resource-gateway-mirror/fixture-mirror-cluster-bindings-v1.fixture.json`。服务端 parser 与
`FixtureMirrorClusterBindingsVerifier` 验证 strict Schema、canonical capability/cluster order、cluster coordinate
唯一性，并与同一 fixture 的 `mirrorCorpus` capability/publication 做 exact equality 对账。

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

### 4.2 Trajectory 绑定示例

该对象只能放在同一 `FixtureBundle.metadata.mirrorTrajectories`。每一项必须重复写出
`capabilityRef`、`corpusPublicationRef` 和 `trajectoryPublicationRef`；前两者必须与
`metadata.mirrorCorpus` 中的一项完全相等。这样 trajectory 不能悄悄切换到另一版 capability 或 corpus：

```json
{
  "schemaVersion": "resourceGateway.fixtureMirrorTrajectoryBindings.v1",
  "trajectories": [
    {
      "capabilityRef": {
        "kind": "CAPABILITY",
        "id": "operator:customer.lookup",
        "revision": 3,
        "fingerprint": "sha256:1111111111111111111111111111111111111111111111111111111111111111"
      },
      "corpusPublicationRef": {
        "kind": "CAPABILITY_CORPUS_PUBLICATION",
        "id": "customer-lookup-corpus",
        "revision": 7,
        "fingerprint": "sha256:2222222222222222222222222222222222222222222222222222222222222222"
      },
      "trajectoryPublicationRef": {
        "kind": "CAPABILITY_CORPUS_TRAJECTORY_PUBLICATION",
        "id": "customer-lookup-timeout-retry",
        "revision": 10,
        "fingerprint": "sha256:4444444444444444444444444444444444444444444444444444444444444444"
      }
    }
  ]
}
```

同一 capability 可绑定多条 trajectory，但每个 canonical request fingerprint 最终只能得到一条轨迹。
绑定必须按 capability 和 trajectory 坐标严格升序，历史 trajectory head、fork、重复 source 或不同
trajectory 复用同一 source 都会失败关闭。

### 4.3 Cluster 绑定示例

该对象只能放在同一 `FixtureBundle.metadata.mirrorClusters`。每一项必须重复 exact
`capabilityRef`、`corpusPublicationRef` 和 `clusterPublicationRef`，前两者必须与 `mirrorCorpus` 中的一项完全
相等：

```json
{
  "schemaVersion": "resourceGateway.fixtureMirrorClusterBindings.v1",
  "clusters": [
    {
      "capabilityRef": {
        "kind": "CAPABILITY",
        "id": "operator:customer.lookup",
        "revision": 3,
        "fingerprint": "sha256:1111111111111111111111111111111111111111111111111111111111111111"
      },
      "corpusPublicationRef": {
        "kind": "CAPABILITY_CORPUS_PUBLICATION",
        "id": "customer-lookup-corpus",
        "revision": 7,
        "fingerprint": "sha256:2222222222222222222222222222222222222222222222222222222222222222"
      },
      "clusterPublicationRef": {
        "kind": "CAPABILITY_CORPUS_CLUSTER_PUBLICATION",
        "id": "customer-lookup-retail-cluster",
        "revision": 2,
        "fingerprint": "sha256:3333333333333333333333333333333333333333333333333333333333333333"
      }
    }
  ]
}
```

绑定必须按 capability 和 cluster 坐标严格升序。旧 cluster head、重复/fork coordinate、跨 corpus 绑定在
payload authority 被调用前失败关闭；同一请求运行时命中多个当前 cluster 也不会按列表顺序任选一个。

## 5. 受保护 API

五条治理路由只在以下条件同时满足时装配：

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
| `POST /api/mirror/corpus-clusters` | 从 exact current publication 发布一版 externally validated、owner-reviewed recorded cluster |

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

### 5.4 Cluster publication 示例

调用方只提交 reference-only owner command，不提交 validation 正文或 payload：

```json
{
  "schemaVersion": "resourceGateway.capabilityCorpusClusterPublishRequest.v1",
  "clusterId": "support-refund-customer-cluster",
  "revision": 1,
  "expectedPredecessorRef": null,
  "capabilityRef": {
    "kind": "CAPABILITY",
    "id": "graph:support-refund",
    "revision": 7,
    "fingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  },
  "corpusPublicationRef": {
    "kind": "CAPABILITY_CORPUS_PUBLICATION",
    "id": "support-cluster-corpus",
    "revision": 1,
    "fingerprint": "sha256:4344469a83b4d6a6b6b7eb07b657ac0ad3e249032ec2426fe6a1a4310ebb0b47"
  },
  "clusterPolicyRef": {
    "kind": "CORPUS_CLUSTER_POLICY",
    "id": "support-cluster-policy",
    "revision": 3,
    "fingerprint": "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
  },
  "validationRef": {
    "kind": "CAPABILITY_CORPUS_CLUSTER_VALIDATION",
    "id": "support-refund-customer-cluster-validation",
    "revision": 1,
    "fingerprint": "sha256:bdaf93f506d15d1dbe9c6a4750a775a45c2028453cc3d3d8ca37cb5dccdc8fc0"
  },
  "reviewTicketRef": {
    "kind": "GOVERNANCE_REVIEW_TICKET",
    "id": "GOV-2030-CLUSTER-001",
    "revision": 1,
    "fingerprint": "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
  },
  "reasonCode": "OWNER_APPROVED_RECORDED_CLUSTER"
}
```

服务端用 `validationRef` 从 `CapabilityCorpusClusterValidationAuthority` 读取 exact current proof，并重算
fingerprint。调用方不能通过请求体自报 members、confidence、identity projection 或 usable horizon。

## 6. Operator-owned 接线

默认组合根会安装五个 unavailable provider，防止演示配置被误当成信任：

- `CapabilityCorpusGovernancePolicyProvider`
- `CapabilityCorpusSourceVerifier`
- `CapabilityCorpusPayloadAuthority`
- `CapabilityCorpusClusterPolicyProvider`
- `CapabilityCorpusClusterValidationAuthority`

企业环境需要提供自己的 Spring bean。`@ConditionalOnMissingBean` 会让部署实现替换默认 placeholder。
cluster publication 只依赖治理/验证 authority，不读取 payload；cluster runtime 还依赖 payload authority，
把 exact member request 和一个 representative response 短时物化到当前 generation。

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
fixture exact + trajectory + cluster bindings
 -> exact publication lookup + latest-head equality
 -> exact corpus revision/capability/scope equality
 -> current governance/publication policy equality
 -> ELIGIBLE risk state + full plan horizon
 -> exact observation/admission/source lineage
 -> trajectory latest-head + current retry-policy equality
 -> attempt membership/request/trace/span/sequence/terminal checks
 -> cluster latest-head + current cluster-policy/validation equality
 -> member support/match values/distinct identity/projection checks
 -> current grant/classification/region/retention checks
 -> source lifecycle/tombstone authority
 -> request/response payload authority + size/content-address/single-JSON checks
 -> conflict-free exact, trajectory, and cluster indexes
 -> trajectory length <= frozen node retryAttempts + 1
 -> immutable run-generation snapshot
```

固定 resolver 顺序由 execution-control fingerprint 封印：

```text
OWNER_SPECIFIED
 -> RECORDED_EXACT
 -> RECORDED_TRAJECTORY
 -> RECORDED_CLUSTER
 -> GOVERNED_REPLAY
 -> ABSTAINED
```

owner rule 因此可以显式覆盖 corpus；standalone exact 优先于 trajectory，trajectory 优先于 cluster，避免泛化
结果覆盖更高保真事实。trajectory 使用真实 one-based
`attempt` 逐项返回；超过已发布序列以 `MIRROR_TRAJECTORY_ATTEMPT_EXHAUSTED` 失败关闭，绝不循环最后一项或落到真实
external call。cluster 只比较 validation 冻结的 exact JSON Pointer 值；缺失、类型不同或值不同会 abstain。
`REQUEST_PROJECTION` 只从当前 request 回填 owner-approved response identity paths。最终仍然失配就
`ABSTAINED`。相同 exact request fingerprint 的多条 source 只有 outcome
完全一致才会折叠，并在 limitation 中记录 source count；response 或 normalized error 冲突会以
`RG.MIRROR.CORPUS_EXACT_CONFLICT` 拒绝整个 generation。

成功响应以 `HASH_ONLY` 输出进入 mirror evidence，同时附 exact publication、revision、policy、review、
observation、admission、payload/proof/schema、authority key 和 grant provenance。非重试 normalized error 保留稳定
error code/type；trajectory resolution 额外携带 trajectory publication、retry policy、owner review ticket 和每个
attempt source refs，confidence 为 `RECORDED_TRAJECTORY_V1`。cluster resolution 携带 cluster/corpus/validation/
policy/member 完整 refs、外部验证的 Wilson confidence 和 limitations，但不携带代表 payload。retryable 单点 error 返回
`RG.MIRROR.CORPUS_RETRYABLE_ERROR_UNSUPPORTED`，因为正确拟合需要完整 attempt/最终结果 trajectory，不能把一个
观测点重复播放成虚假业务行为。

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
- candidate、publication、trajectory publication 和 cluster publication 使用四套独立 predecessor fence。
- review、candidate、publication、trajectory publication、cluster publication 的正文写入与 mandatory success
  audit 位于同一数据库事务。
- audit 失败返回 `503 RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE`，本次治理写入回滚。
- 失败 audit 使用独立事务保存；业务 payload 不进入 audit。

H2 表只保存完整 scope、索引元数据和 canonical payload-free JSON：

- `mirror_capability_observation_reviews`
- `mirror_capability_corpus_revisions`
- `mirror_capability_corpus_publications`
- `mirror_capability_corpus_trajectories`
- `mirror_capability_corpus_clusters`

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
| `mirrorCorpusGovernanceApi` | review/candidate/publication 三条基础 test/staging route 已装配 |
| `mirrorCorpusGovernanceReady` | policy provider 与 source verifier 当前都可用 |
| `mirrorCorpusTrajectoryPublicationProtocol` | trajectory command/fact、fingerprint 和 strict Schema 已支持 |
| `mirrorCorpusTrajectoryPublicationApi` | `POST /api/mirror/corpus-trajectories` 已装配 |
| `mirrorCorpusTrajectoryPublicationReady` | corpus policy、retry policy、source authority 当前都可用 |
| `mirrorCorpusClusterPublicationProtocol` | cluster validation/command/publication、identity safety、holdout/Wilson fingerprint 与 strict Schema 已支持 |
| `mirrorCorpusClusterPublicationApi` | `POST /api/mirror/corpus-clusters` 已装配 |
| `mirrorCorpusClusterPublicationReady` | corpus policy、cluster policy、external validation authority 与 source authority 当前都可用 |
| `mirrorCorpusResolverReady` | policy、source lifecycle、payload authority 当前都可用 |
| `mirrorCorpusTrajectoryResolverProtocol` | fixture trajectory binding 与 `RECORDED_TRAJECTORY` 运行协议已支持 |
| `mirrorCorpusTrajectoryResolverReady` | trajectory serving 的 policy、source lifecycle、payload 与 retry-policy authorities 当前都可用；独立于 publication route |
| `mirrorCorpusClusterResolverProtocol` | fixture cluster binding、exact match 和 identity-safe `RECORDED_CLUSTER` 运行协议已支持 |
| `mirrorCorpusClusterResolverReady` | cluster serving 的 corpus/cluster policy、validation、source lifecycle 与 payload authorities 当前都可用；独立于 publication route |

`supportedObjects.fixtureMirrorCorpusBindings`、`supportedObjects.fixtureMirrorTrajectoryBindings` 与
`supportedObjects.fixtureMirrorClusterBindings` 应分别包含
`resourceGateway.fixtureMirrorCorpusBindings.v1` 和
`resourceGateway.fixtureMirrorTrajectoryBindings.v1`、`resourceGateway.fixtureMirrorClusterBindings.v1`。默认
demo 中相关 protocol/API 为 `true`，动态 readiness 通常为 `false`。客户端不能用 `Api=true` 推导 `Ready=true`，也不能用
`GovernanceReady=true` 推导 resolver 已可物化 payload。反向也不成立：只读执行部署可以令
`mirrorCorpusTrajectoryPublicationApi=false`，同时在 serving authorities 完整时令
`mirrorCorpusTrajectoryResolverReady=true`。

`supportedObjects.capabilityCorpusClusterValidation`、
`supportedObjects.capabilityCorpusClusterPublishRequest` 和
`supportedObjects.capabilityCorpusClusterPublication` 必须分别为对应 v1 wire version。

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
`CapabilityRetryPolicyProvider`；trajectory runtime serving 也依赖同一个当前权威。默认实现不可用，不能信任
请求或历史 publication 自报的 `retryPolicyRef`。
cluster publication 还必须安装 operator-owned `CapabilityCorpusClusterPolicyProvider` 和外部
`CapabilityCorpusClusterValidationAuthority`。默认实现同样不可用；demo 启动成功只证明 route assembly，
不证明 data-plane validation 或 cluster publication ready。
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
| cluster policy/validation | `RG.MIRROR.CORPUS_CLUSTER_POLICY_UNAVAILABLE`、`RG.MIRROR.CORPUS_CLUSTER_VALIDATION_UNAVAILABLE`、`RG.MIRROR.CORPUS_CLUSTER_VALIDATION_INTEGRITY_INVALID`、`RG.MIRROR.CORPUS_CLUSTER_CONFIDENCE_REJECTED` | 恢复 current authority，或重新验证/发布；禁止降低阈值、忽略 identity gap 或信任调用方自报 proof |
| cluster lineage/source | `RG.MIRROR.CORPUS_CLUSTER_HEAD_CONFLICT`、`RG.MIRROR.CORPUS_CLUSTER_SOURCE_REJECTED`、`RG.MIRROR.CORPUS_CLUSTER_USE_NOT_AUTHORIZED` | 读取 current heads，修复 source/grant 后生成新 validation/command；不得修改旧 publication |
| serving binding | `RG.MIRROR.CORPUS_BINDING_INVALID`、`RG.MIRROR.CORPUS_PUBLICATION_STALE`、`RG.MIRROR.CORPUS_TRAJECTORY_STALE`、`RG.MIRROR.CORPUS_CLUSTER_STALE`、`RG.MIRROR.CORPUS_POLICY_DRIFT` | 修复 fixture 或重新审阅/发布，不回退历史 head |
| trajectory runtime | `RG.MIRROR.CORPUS_TRAJECTORY_RETRY_POLICY_DRIFT`、`CONTROL_PLAN_TRAJECTORY_RETRY_INCOMPATIBLE`、`MIRROR_TRAJECTORY_ATTEMPT_EXHAUSTED` | 重新发布/绑定轨迹，或显式调整 DAG retry capacity；禁止截断序列 |
| cluster runtime | `RG.MIRROR.CORPUS_CLUSTER_AUTHORITY_UNAVAILABLE`、`RG.MIRROR.CORPUS_CLUSTER_POLICY_DRIFT`、`MIRROR_CLUSTER_AMBIGUOUS` | 恢复 current authority 或重新验证/发布；多 cluster 命中必须收紧 match paths，禁止按顺序任选 |
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

cluster validation/command/publication 可用固定夹具独立验证：

```java
CapabilityCorpusClusterCompatibilityFixture cluster =
        CapabilityMirrorProtocol.capabilityCorpusClusterCompatibilityFixture();
CapabilityCorpusClusterVerifier.VerificationResult verified =
        new CapabilityCorpusClusterVerifier().verify(cluster);
if (!verified.verified()) {
    throw new IllegalStateException(verified.reasonCode());
}
```

该 verifier 关闭五份 strict artifact Schema，重算所有 content address、corpus/command/validation lineage、
member/representative membership、response Schema、identity paths、holdout 和 Wilson confidence。它不会联网
证明 current cluster policy、validation authority、grant/retention、source lifecycle 或 payload authority，
也不声称当前部署的 runtime resolver 已 ready。

fixture trajectory binding 也可独立验证：

```java
FixtureMirrorTrajectoryBindingsVerifier.VerificationResult binding =
        new FixtureMirrorTrajectoryBindingsVerifier().verify(bindingJson);
if (!binding.verified()) {
    throw new IllegalStateException(binding.reasonCode());
}
```

该 verifier 证明 strict Schema、canonical capability/trajectory order 和 exact trajectory coordinate 唯一性。
它不知道同一 fixture 的 `mirrorCorpus` 内容，因此不能证明二者匹配；也不能证明 current head、retry policy、
grant、retention、tombstone、payload/source authority 或节点 retry capacity。在线 materialization 和 plan compiler
必须重新证明这些事实。

fixture cluster binding 可连同同一 fixture 的 corpus binding 独立交叉验证：

```java
FixtureMirrorClusterBindingsVerifier.VerificationResult binding =
        new FixtureMirrorClusterBindingsVerifier().verify(
                clusterBindingJson, corpusBindingJson);
if (!binding.verified()) {
    throw new IllegalStateException(binding.reasonCode());
}
```

该 verifier 证明 strict Schema、canonical capability/cluster order、exact cluster coordinate 唯一性和 exact
corpus selection equality；current heads、policy、validation revocation、member grants/lifecycle、payload content
address 与 identity value 仍由在线 materialization 证明。

## 14. 上线前剩余门禁

进入生产流量或 certification 前至少补齐：

1. 生产级 payload/proof/grant/retention/tombstone authority 与 outage SLO。
2. candidate/publish 外部调用的 prepare/verify/commit 分段，缩短数据库事务持有时间，并用 generation token 防止
   verification 后漂移。
3. poison、burst、producer concentration、bias，以及按业务分层而非只按全局随机抽样的持续 holdout validation。
4. owner review queue、双人审批或高风险 separation of duties。
5. 多副本 resolver generation cache、撤销/过期传播、内存清零和 stale-while-deny 语义。
6. retention deletion proof、legal hold 与 corpus lineage 重建。
7. stateful resolver 的 confidence、abstention、session isolation 和 outcome calibration。
8. 非 Java fixed fixture、数字 canonicalization 和客户环境 certification。
9. payload authority 与 trajectory resolver 的负载、熔断、区域故障、vault generation 撕裂、重试风暴和
   256 MiB generation budget 压测。

在这些门禁完成前，本增量应被描述为“治理事实、显式 retry trajectory/recorded-cluster 管线与
test/staging exact/trajectory/cluster serving kernel 已闭合”，不能描述为
“保真语料服务已生产就绪”。
