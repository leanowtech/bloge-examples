# Resource Gateway Capability Corpus 治理与发布指南

> 本增量把可信 observation 变成可审查、可冻结、可发布的 payload-free 治理事实。
> 它没有实现 resolver，也不会让候选或发布结果自动进入运行时服务。

## 1. 能力边界

本阶段解决三个问题：

1. 对终态 `QUARANTINED` observation 记录一次不可变人工审查，但不篡改原准入事实。
2. 把一组 exact `ADMITTED` observation 冻结成不可变 corpus candidate revision，并计算确定性的元数据风险。
3. 由授权 owner 对当前 eligible candidate 做第二次来源复验后，追加一个独立 serving publication。

以下能力仍不在本阶段：

- 不保存、读取或返回 request/response payload。
- 不实现 payload vault、proof service、retention service 或 tombstone authority。
- 不实现 exact、trajectory、cluster resolver。
- 不把 candidate revision 直接当作 serving 数据。
- 不证明当前策略、组织授权或外部 payload 在离线验证后仍然有效。
- 不完成 poisoning、drift、bias、outcome calibration 和删除证明。

## 2. 三个不可破坏的不变量

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

### 2.3 发布不等于运行时已接线

`CapabilityCorpusPublication` 是独立 append-only lineage。它绑定当前 candidate、当前 publication
policy、owner review ticket、认证 reviewer 和 source horizon。未来 resolver 只能消费最新且重新验证通过的
publication，不能回退读取 observation store 或 candidate table。

当前 capability probe 固定报告 `mirrorCorpusResolverReady=false`。本增量发布的 corpus 不会改变现有 mirror
运行结果。

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
                                         |
                              future resolver, not implemented
```

`ObservationReview`、`CorpusRevision`、`CorpusPublication` 是三条不同的事实线。review 以 observation
为唯一终态坐标；candidate 与 publication 各自拥有独立、连续、乐观栅栏保护的 revision lineage。

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

默认组合根会安装两个 unavailable provider，防止演示配置被误当成信任：

- `CapabilityCorpusGovernancePolicyProvider`
- `CapabilityCorpusSourceVerifier`

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

## 7. 风险与发布门禁

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

## 8. 幂等、并发与事务

- exact retry 先读取已提交事实，再访问 mutable policy/source provider。
- 同坐标、同 command fingerprint 返回原事实。
- 同坐标、不同 command fingerprint 返回 `409`。
- candidate 和 publication 使用两套独立 predecessor fence。
- review、candidate、publication 的正文写入与 mandatory success audit 位于同一数据库事务。
- audit 失败返回 `503 RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE`，本次治理写入回滚。
- 失败 audit 使用独立事务保存；业务 payload 不进入 audit。

H2 表只保存完整 scope、索引元数据和 canonical payload-free JSON：

- `mirror_capability_observation_reviews`
- `mirror_capability_corpus_revisions`
- `mirror_capability_corpus_publications`

每次读取同时复验 JSON fingerprint 与冗余索引列，索引漂移不能改变治理结果。

## 9. Capability Probe

调用：

```bash
curl -sS http://localhost:8080/api/integration/capabilities
```

分别判断：

| Flag | 含义 |
|---|---|
| `mirrorCorpusGovernanceProtocol` | 六类 v1 wire object 与 strict Schema 已支持 |
| `mirrorCorpusGovernanceApi` | 三条 test/staging route 已装配 |
| `mirrorCorpusGovernanceReady` | policy provider 与 source verifier 当前都可用 |
| `mirrorCorpusResolverReady` | runtime resolver 已接入 verified publication |

默认 demo 中前三项通常为 `true/true/false`，resolver 为 `false`。客户端不能用 `Api=true` 推导
`Ready=true`，也不能用 `GovernanceReady=true` 推导 resolver 已服务。

## 10. 启动、停止与演示

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
beans；不要把 always-verified adapter 放进共享或生产配置。

## 11. 稳定错误与处置

| 类型 | 代表错误 | 处置 |
|---|---|---|
| 输入关闭 | `RG.MIRROR.CORPUS_REQUEST_MALFORMED` | 修正字段、版本、大小、深度、顺序 |
| scope/purpose | `CORPUS_GOVERNANCE_PURPOSE_REQUIRED`、`CORPUS_SCOPE_INCOMPLETE` | 修复 workload identity，不改请求冒充 scope |
| review 冲突 | `OBSERVATION_NOT_QUARANTINED`、`OBSERVATION_REVIEW_CONFLICT` | 查原 admission/review；新事实使用新 observation |
| source 确定失效 | `CORPUS_SOURCE_INELIGIBLE`、`CORPUS_SOURCE_REJECTED` | 修复/重录 source，不重试同一内容 |
| provider 不确定 | `CORPUS_POLICY_UNAVAILABLE`、`CORPUS_SOURCE_AUTHORITY_UNAVAILABLE` | 恢复 authority 后 exact retry |
| lineage 冲突 | `CORPUS_REVISION_HEAD_CONFLICT`、`CORPUS_PUBLICATION_HEAD_CONFLICT` | 读取 current head，重新生成下一 command |
| 发布门禁 | `CORPUS_CANDIDATE_INELIGIBLE`、`CORPUS_POLICY_DRIFTED` | 新建 candidate，不修改旧 revision |
| 存储/审计 | `CORPUS_STORE_UNAVAILABLE`、`OPERATION_AUDIT_UNAVAILABLE` | 按 outage runbook 恢复；确认事务未留下半成品 |

错误详情不返回 provider message、SQL、payload 或 stack trace。

## 12. 独立验证

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

## 13. 上线前剩余门禁

进入 resolver 实现前至少补齐：

1. 生产级 payload/proof/grant/retention/tombstone authority 与 outage SLO。
2. candidate/publish 外部调用的 prepare/verify/commit 分段，缩短数据库事务持有时间，并用 generation token 防止
   verification 后漂移。
3. poison、burst、producer concentration、bias 和 holdout validation。
4. owner review queue、双人审批或高风险 separation of duties。
5. publication current-head 读取、resolver cache、撤销/过期传播和 stale-while-deny 语义。
6. retention deletion proof、legal hold 与 corpus lineage 重建。
7. exact/trajectory/cluster resolver 的 confidence、abstention、provenance 与 signed evidence。
8. 非 Java fixed fixture、数字 canonicalization 和客户环境 certification。

在这些门禁完成前，本增量应被描述为“治理事实管线已闭合”，不能描述为“保真语料服务已生产就绪”。
