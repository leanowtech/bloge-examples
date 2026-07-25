# Resource Gateway 场景演练注册、编译、耐久批次与证据指南

本文说明 E7 场景演练的第一条端到端纵向链路：把业务处置规则、已有测试、
Fixture、MirrorPlan 和可选 Session checkpoint 绑定成不可变执行许可，
然后逐 case 调用既有 Mirror runtime，并将已验签 evidence 求值成不携带
业务 payload 的结果聚合。

## 1. 当前交付边界

| 能力 | 状态 | 能证明什么 |
|---|---|---|
| `CaseHandlingAssertion.v1` | 可用 | 处置结果以 typed selector/expectation 表达，不复制业务值 |
| `ScenarioCase.v1` | 可用 | 一个业务 case 精确引用既有 TestSuite case、Fixture、MirrorPlan、fault 和 checkpoint |
| `ScenarioPack.v1` | 可用 | 多个 case、断言、状态模型和写效果形成同一业务能力的不可变场景包 |
| 场景资产注册表 | 可用 | 全企业 scope、append-only、exact revision/fingerprint、读取时重验完整性 |
| `CompiledScenarioRehearsalPlan.v1` | 可用 | 编译时证明跨仓储依赖闭包一致，产出 payload-free 执行许可 |
| `ScenarioHandlingAssertionResult.v1` | 可用（求值内核） | 把一个 exact assertion 绑定到一个已验签 evidence bundle；只输出状态、错误码、指纹、计数、耗时、布尔值和局限 |
| `ScenarioHandlingAssertionEvaluator` | 可用（求值内核） | 对现有 evidence 已表达的 node/edge/capability/input/error/state/receipt/governance/budget 事实确定性求值 |
| `ScenarioRehearsalExecutionRequest.v1` | 可用 | 客户端只能提交 request id 和 exact compiled-plan ref，不能覆盖 context、fixture、fault、Session 或 policy |
| `ScenarioCaseRehearsalResult.v1` | 可用 | 每个 case 绑定 exact child run、signed evidence 和完整 assertion closure；无 evidence 时失败关闭 |
| `ScenarioRehearsalResult.v1` | 可用（耐久同步聚合） | 顺序聚合所有 case，服务端派生 PASS/FAIL/INDETERMINATE；case 前缀可恢复 |
| `ScenarioRehearsalEvidenceAttestation.v1` | 可用 | 在独立签名域中绑定 aggregate run、request、compiled plan、result fingerprint 和签名时间 |
| `ScenarioRehearsalEvidenceBundle.v1` | 可用（证据 API） | 对完整 payload-free result 做 Ed25519 签名、立即复验、append-only 保存和 exact runId 读取 |
| aggregate durable coordination | 可用 | 完整 scope 请求注册、数据库时钟 lease、单调 epoch、逐 case checkpoint、takeover 与原子终态 |
| 受保护 Scenario 操作审计 | 可用（终态） | run/evidence read 成功与失败均写入 payload-free audit；run 成功审计与 evidence/request 终态同事务 |
| aggregate lifecycle audit | 可用 | `CLAIMED/TAKEN_OVER/CHECKPOINTED/RELEASED/COMPLETED` 只含 scope、epoch、cursor、ref 与 fingerprint，并与状态转换同事务 |
| `ScenarioRehearsalRetentionEvent/State.v1` | 可用 | 完整 scope、不可变最短保留边界、多重独立 hold、签名事件链和删除证明 |
| Scenario retention API | 可用 | retention read、hold place/release、到期 purge 均使用独立 purpose 和 payload-free 操作审计 |
| `ScenarioRehearsalRetentionVerifier` | 可用 | ANEKE/CI 可离线重算最新事件指纹、验证 projection 闭包、密钥策略与 Ed25519 删除证明 |
| `ScenarioRehearsalWorkbookSeed.v1` | 可用 | 把 exact Pack/Plan、signed aggregate、初始 retention proof、逐 case/assertion 与保守 blocker 投影成确定性 payload-free ANEKE 输入 |
| `ScenarioRehearsalWorkbookVerifier` | 可用 | 独立重算计划/工作簿内容地址、两类签名、逐 case/assertion 闭包和 gate decision，不信任生产者的 `gateReady` |
| Scenario workbook API/Test Kit client | 可用 | 受保护 exact-run 读取；客户端自动拉取五类源资产并在返回前完成独立闭包验证 |
| `ScenarioRehearsalBatchRequest/Manifest.v1` | 可用 | 调用方只提交有序 exact plan ref；服务端解析并封存 batch id、child request/run id、case count 和总预算 |
| `ScenarioRehearsalBatchJob/ItemPage.v1` | 可用 | 暴露 payload-free 生命周期、计数、失败码和稳定 manifest-index 分页，不泄露 fixture、context 或 worker 身份 |
| durable batch repository | 可用 | `(region, environment)` 数据库权威容量、同请求精确重放、租户公平 claim、优先级老化、lease/epoch、重试、取消、deadline 和恢复 |
| batch worker turn | 可用 | 执行一个 manifest item，复用 aggregate 幂等，独立复验签名 evidence 与 workbook closure 后才完成 item |
| autonomous batch scheduling | 可用（显式非生产开关） | 单地域分区、固定 lane、有界 fixed-delay poll、启动配置校验、动态 readiness、停机 drain 和逐 case heartbeat/cancel/deadline |
| signed batch evidence/index | 可用 | 请求、冻结 manifest、终态 job、有序 item 与 child evidence/workbook ref 形成 Ed25519 签名闭包；Test Kit 在返回前独立重算 |
| durable batch evidence finalization | 可用 | `FINALIZING_EVIDENCE` outbox、独立 KMS lane、幂等签名、lease takeover、bounded retry/quarantine 与受控 remediation |
| finalization health/SLO | 可用 | 同一数据库时钟聚合驱动 full-scope API、部署 readiness 和固定基数指标；未知控制状态、策略漂移和存储故障 fail closed |
| batch operation/lifecycle audit | 可用 | submit/read/evidence/cancel 使用强制 payload-free operation audit；低频队列转换使用数据库赋时 append-only lifecycle audit，并与状态写同事务 |
| `ScenarioRehearsalBatchRetentionEvent/State.v1` | 可用 | 准入时冻结不可缩短的批次保留下限，提供多重独立 hold、签名事件链、精确删除计数和逻辑删除证明 |
| batch retention API/Test Kit client | 可用 | read/place/release/purge 使用独立 purpose；客户端在返回前重算投影闭包、事件指纹、密钥策略和 Ed25519 签名 |
| `ScenarioRehearsalBatchWorkbookSeed.v1` | 可用 | 把签名 batch v2、初始 retention proof、全部 child workbook commitment 与有界 correctness projection 归约为一个确定性整批 ANEKE 输入 |
| batch workbook root seal/verifier | 可用 | 服务端内部逐 child 复验后对 deterministic seed 做域隔离 Ed25519 seal；ANEKE/CI 无需 N+1 即可重算 batch/retention/root 三类签名、blocker 和 gate |
| batch workbook API/Test Kit client | 可用 | 受保护 exact-job 读取；一键拉取 seed、batch evidence 和三把公开 key 后失败关闭，case 级审计可按需打开 child commitment |
| 可作为生产发布门禁的 Scenario evidence | 未交付 | 本地 gate-consumable closure 已完成；尚无企业 retention policy authority、WORM/外部锚、消费者认证和环境级门禁 |

当前链路已经解决“执行前冻结什么、运行时从哪里取值、每个结果依据什么证据”
三个问题，并让同步完成的聚合成为可独立复验和重读的证据。aggregate 现以
数据库时钟 lease/epoch 串行化执行，把每个 payload-free case result 作为连续
checkpoint；进程退出后，同 request id 可由下一副本从首个未完成 case 接续。
终态证据与 retention registration 同事务提交，默认最短保留 30 天；到期后只有
`PAYLOAD_RETENTION_ADMIN` 且不存在任何 active hold 才能删除 aggregate evidence。
批次终态也把 evidence、retention registration、job/item 终态和 lifecycle audit
放在同一事务；批次保留下限在准入时按 `deadlineAt + terminalRetention` 冻结，
不会因提前完成而缩短。批次 purge 只删除 job、全部 item 和 batch evidence，
child Scenario evidence 及 operation/lifecycle audit 明确保留。
系统现在可从完整已验证闭包确定性导出 ANEKE workbook seed，且独立客户端会再次
验签、重算来源闭包与 gate decision。该本地闭包适合 test/staging 的回归、法律
保全、正确性工作簿导入和门禁联调；企业策略权威、WORM/外部锚与消费者环境认证
完成前，仍不能冒充 production publish-gate-ready 证据。

## 2. 为什么需要独立编译计划

`ScenarioPack` 是可复用的业务剧本，`MirrorPlan` 是可复用的镜像运行计划。
如果二者互相保存 exact fingerprint，会形成无法稳定求值的内容寻址环：

```text
ScenarioPack -> ScenarioCase -> MirrorPlan -> ScenarioPack
```

系统因此禁止 `MirrorPlan.scenarioPackRef` 反向引用，并由
`CompiledScenarioRehearsalPlan` 作为唯一 join artifact：

```text
ScenarioPack
  -> ScenarioCase
     -> TestSuite/TestCase
     -> FixtureBundle
     -> MirrorPlan
     -> optional signed Session checkpoint
     -> CaseHandlingAssertion
  -> CompiledScenarioRehearsalPlan
```

编译计划保存所有 exact ref、隔离策略、执行服务和断言闭包，但不保存
TestCase input、Fixture 返回值、Session payload 或真实凭据。相同依赖输入
必须生成相同 fingerprint；任何依赖漂移都必须重新编译。

## 3. 启动与停止

从仓库根目录启动 test profile 的 Mirror control plane：

```bash
RG_MIRROR_RUNTIME_ENABLED=true \
  ./scripts/start-visual-canvas-demo.sh --profile test
```

需要注册 stateful case 的 Session checkpoint 时启用独立加密数据面：

```bash
./scripts/start-visual-canvas-demo.sh --profile test --stateful
```

`--stateful` 已包含 Mirror runtime 开关。脚本会等待 capability probe
可读后才报告启动成功。停止服务：

```bash
./scripts/stop-visual-canvas-demo.sh
```

检查装配真值：

```bash
curl -sS http://localhost:8080/api/integration/capabilities
```

当前里程碑应满足：

| Feature flag | 期望值 |
|---|---:|
| `mirrorScenarioArtifactRegistry` | `true` |
| `mirrorScenarioRehearsalCompilation` | `true` |
| `mirrorScenarioRehearsalExecution` | `true` |
| `mirrorScenarioRehearsalEvidenceApi` | `true` |
| `mirrorScenarioRehearsalRetentionApi` | `true` |
| `mirrorScenarioRehearsalLegalHold` | `true` |
| `mirrorScenarioRehearsalDeletionProof` | `true` |
| `mirrorScenarioRehearsalWorkbookSeed` | `true` |
| `mirrorScenarioRehearsalBatchApi` | `true` |
| `mirrorScenarioRehearsalBatchCooperativeControl` | `true` |
| `mirrorScenarioRehearsalBatchEvidence` | `true` |
| `mirrorScenarioRehearsalBatchWorkbookSeed` | `true` |
| `mirrorScenarioRehearsalBatchEvidenceFinalizationApi` | `true` |
| `mirrorScenarioRehearsalBatchFinalizationRemediationApi` | `true` |
| `mirrorScenarioRehearsalBatchFinalizationHealthApi` | `true` |
| `mirrorScenarioRehearsalBatchFinalizationSloIntegrated` | 使用 `--scenario-batch` 时为 `true` |
| `mirrorScenarioRehearsalBatchFinalizationSloReady` | 当前聚合为 `HEALTHY`/`DEGRADED` 时为 `true` |
| `mirrorScenarioRehearsalBatchRetentionApi` | `true` |
| `mirrorScenarioRehearsalBatchLegalHold` | `true` |
| `mirrorScenarioRehearsalBatchDeletionProof` | `true` |
| `mirrorScenarioRehearsalBatchScheduling` | 默认 `false`；使用 `--scenario-batch` 且 scheduler 健康时为 `true` |
| `mirrorScenarioRehearsalEvidence` | `false` |

若前两个为 `false`，先检查 profile 是否为 `test`/`staging`、Mirror 开关、
数据库与依赖仓储是否完成装配。不要绕过 probe 直接把环境标记为可用。

## 4. 调用身份

所有场景路由都要求受验证的完整企业身份。资产注册、编译与执行使用
`MIRROR_REHEARSAL`；evidence 与 workbook seed 读取也接受最小权限的
`GOVERNANCE_EVIDENCE_INGESTION`。retention、legal hold 和 purge 使用端点表
列出的独立 purpose。普通演练请求使用：

```http
Authorization: Bearer bloge-aneke-demo-token
X-Purpose: MIRROR_REHEARSAL
Content-Type: application/json
```

服务端从身份派生 tenant、organization、project、environment 和 region。
请求内的 Scenario scope 必须与该完整身份一致；不同组织、项目、环境或
region 即使 id 相同，也不能互相读取 Scenario 资产。

TestSuite/Fixture registry 当前写入 `bloge.storedTestSuite.v2` 与
`bloge.storedFixtureBundle.v2`。两类 envelope、仓储主键和读取条件都包含
tenant、organization、project、environment 与 region；额外的
`binding_fingerprint` 将这五个维度与资产 id、revision、内容 fingerprint
绑定，防止只改数据库 scope 列后把有效内容搬到另一项目。

历史 `v1` envelope 和表只保留为显式迁移输入。场景 compiler、运行入口和
完整 scope repository API 均不会把 `v1` 自动提升为 `v2`，也不会回退到
tenant + environment 查询。迁移必须由目标 project 的授权主体从原始
TestSuite/Fixture 定义重新注册，得到新的 v2 envelope，再重新编译引用它们的
ScenarioPack。这样迁移是在重新声明所有权，而不是用 ScenarioCase 的自声明
补造来源资产归属。

## 5. 资产准备

一个可编译 case 至少需要下列已有资产：

1. exact target capability revision；
2. 已注册且通过自身完整性校验的 TestSuite revision，其中存在
   `testCaseId`；
3. TestCase 精确引用的 FixtureBundle revision；
4. 已编译 MirrorPlan；
5. case 声明的每一个 fault rule 都真实存在于 FixtureBundle；
6. 每一个 `CaseHandlingAssertion`；
7. stateful case 需要 live、同 scope、签名有效且与 plan/state closure
   一致的 Session checkpoint。

其中 TestSuite 与 FixtureBundle 必须是完整 scope 的 v2 存储 envelope。
混用 v1/v2、跨 organization/project/region 引用，或数据库 scope
`binding_fingerprint` 漂移都会失败关闭。

业务输入和预期业务输出继续保存在 TestSuite/Fixture 的既有受治理边界中。
Scenario 资产只能引用，不能复制这些内容。

## 6. 注册顺序

注册表采用 child-before-parent 的 append-only 规则：

```text
CaseHandlingAssertion
        |
optional signed checkpoint
        |
ScenarioCase
        |
ScenarioPack
```

端点如下：

| 方法与路径 | 权限操作 | 行为 |
|---|---|---|
| `POST /api/mirror/scenarios/assertions` | `MIRROR_SCENARIO_ARTIFACT_WRITE` | 写入一个 exact assertion revision |
| `POST /api/mirror/scenarios/checkpoints` | `MIRROR_SCENARIO_ARTIFACT_WRITE` | 写入一个 live signed checkpoint |
| `POST /api/mirror/scenarios/cases` | `MIRROR_SCENARIO_ARTIFACT_WRITE` | 验证 assertion/checkpoint closure 后写入 case |
| `POST /api/mirror/scenarios/packs` | `MIRROR_SCENARIO_ARTIFACT_WRITE` | 验证完整 case/assertion/state closure 后写入 pack |
| `GET /api/mirror/scenarios/packs/{packId}` | `MIRROR_SCENARIO_ARTIFACT_READ` | 按 revision + fingerprint 精确读取 |
| `POST /api/mirror/scenarios/runs` | `MIRROR_REHEARSAL_EXECUTE` | 同步执行 exact compiled plan 并返回内容寻址聚合 |
| `GET /api/mirror/scenarios/runs/{runId}/evidence` | `MIRROR_REHEARSAL_EVIDENCE_READ` | 按完整企业 scope 读取并重新验签一个 Scenario 聚合证据 |
| `GET /api/mirror/scenarios/runs/{runId}/workbook-seed` | `MIRROR_REHEARSAL_WORKBOOK_READ` | 从 exact Plan/Evidence/Retention closure 确定性投影 ANEKE seed |
| `GET /api/mirror/scenarios/runs/{runId}/retention` | `MIRROR_REHEARSAL_RETENTION_READ` | 重建并验证 retention projection 与最新签名事件 |
| `POST /api/mirror/scenarios/runs/{runId}/retention/holds` | `MIRROR_REHEARSAL_LEGAL_HOLD` | 以独立 command/hold id 放置一个法律保全 |
| `POST /api/mirror/scenarios/runs/{runId}/retention/hold-releases` | `MIRROR_REHEARSAL_LEGAL_HOLD` | 释放一个 exact hold，不影响其他 active hold |
| `POST /api/mirror/scenarios/runs/{runId}/retention/purge` | `MIRROR_REHEARSAL_RETENTION_ADMIN` | 数据库时钟确认到期且无 hold 后删除 aggregate 并返回签名证明 |
| `POST /api/mirror/rehearsal-jobs` | `MIRROR_REHEARSAL_BATCH_SUBMIT` | 解析所有 exact plan、冻结 manifest 并按服务端容量策略幂等入队 |
| `GET /api/mirror/rehearsal-jobs` | `MIRROR_REHEARSAL_BATCH_READ` | 按 exact scope 与不可变创建坐标 keyset 分页列出最新 payload-free job |
| `GET /api/mirror/rehearsal-jobs/{jobId}` | `MIRROR_REHEARSAL_BATCH_READ` | 读取并重验 payload-free job projection |
| `GET /api/mirror/rehearsal-jobs/{jobId}/items` | `MIRROR_REHEARSAL_BATCH_READ` | 使用 `startIndex` + `limit` 读取稳定 manifest-index 页 |
| `GET /api/mirror/rehearsal-jobs/{jobId}/evidence` | `MIRROR_REHEARSAL_BATCH_EVIDENCE_READ` | 读取并复验请求、manifest、终态 job、全部 item ref 的签名批次闭包 |
| `GET /api/mirror/rehearsal-jobs/{jobId}/workbook-seed` | `MIRROR_REHEARSAL_BATCH_WORKBOOK_READ` | 从已验签 batch/retention/child closure 投影并签发有界 ANEKE batch seed |
| `POST /api/mirror/rehearsal-jobs/{jobId}/cancellations` | `MIRROR_REHEARSAL_BATCH_CANCEL` | 记录幂等 cooperative cancellation intent |
| `GET /api/mirror/rehearsal-jobs/{jobId}/retention` | `MIRROR_REHEARSAL_RETENTION_READ` | 重建并验证 batch retention projection 与最新签名事件 |
| `POST /api/mirror/rehearsal-jobs/{jobId}/retention/holds` | `MIRROR_REHEARSAL_LEGAL_HOLD` | 放置一个独立 batch legal hold |
| `POST /api/mirror/rehearsal-jobs/{jobId}/retention/hold-releases` | `MIRROR_REHEARSAL_LEGAL_HOLD` | 只释放指定 hold，不影响其他 active hold |
| `POST /api/mirror/rehearsal-jobs/{jobId}/retention/purge` | `MIRROR_REHEARSAL_RETENTION_ADMIN` | 到期且无 hold 时删除 batch closure 并返回签名逻辑删除证明 |

同一 scope、kind、id、revision 的相同内容重试是幂等的；不同 fingerprint
是不可变修订冲突。资产注册和编译 API 不提供 `latest` 或覆盖更新；删除只能通过
独立权限、保留期和法律保全约束下的 retention purge 协议。

严格 decoder 在构造领域对象前拒绝：

- 重复 JSON key；
- 未知顶层或嵌套字段；
- 错误 `schemaVersion`；
- 超过 8 MiB 的请求；
- 深度超过 64 或节点数超过 100,000；
- 在 selector、metadata 等位置夹带 request/response/payload。

## 7. 编译

注册完 ScenarioPack 后提交 exact 坐标：

```bash
curl -sS -X POST \
  http://localhost:8080/api/mirror/scenarios/packs/refund-pack/compiled-plans \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL' \
  -H 'Content-Type: application/json' \
  --data '{
    "schemaVersion": "resourceGateway.scenarioRehearsalCompileRequest.v1",
    "revision": 1,
    "fingerprint": "sha256:<scenario-pack-fingerprint>"
  }'
```

编译器会重新从各自 authority 解析依赖，而不是相信客户端提交的拼装结果。
它逐项证明：

1. pack、case、assertion seal 有效且 lifecycle/approval 未过期；
2. pack 与所有 child 的完整企业 scope、target capability 一致；
3. TestSuite envelope 完整且包含 exact `testCaseId`；
4. TestCase 的 Fixture ref 与 case 完全一致；
5. Fixture target lineage、classification 和 lifecycle 合法；
6. MirrorPlan seal、scope、target、Fixture、state/write closure 与 policy
   完全一致；
7. 所有 `THROW`、`DELAY`、`TIMEOUT`、`DENY` rule 都被 case 显式列出，
   且 case 不引用不存在的 fault；
8. deterministic clock/random/identity/feature flags 等执行服务无漂移；
9. stateful checkpoint 的签名、store generation、plan、state model、
   write effect、logical clock 和有效期均一致；
10. generation-one policy 保持 sequential、case-isolated、no real call、
    no real credential、no network egress、`HASH_ONLY`。

成功响应中的 `planId` 为 `<packId>@compiled-v1`。读取时必须同时提供
exact revision 和 fingerprint：

```bash
curl -sS \
  'http://localhost:8080/api/mirror/scenarios/compiled-plans/refund-pack%40compiled-v1?revision=1&fingerprint=sha256%3A<compiled-plan-fingerprint>' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL'
```

## 8. 同步试跑

编译成功后只提交一个稳定 request id 和 exact plan ref：

```bash
curl -sS -X POST \
  http://localhost:8080/api/mirror/scenarios/runs \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL' \
  -H 'Content-Type: application/json' \
  --data '{
    "schemaVersion": "resourceGateway.scenarioRehearsalExecutionRequest.v1",
    "requestId": "refund-regression-20260724-001",
    "compiledPlanRef": {
      "kind": "COMPILED_REHEARSAL_PLAN",
      "id": "refund-pack@compiled-v1",
      "revision": 1,
      "fingerprint": "sha256:<compiled-plan-fingerprint>"
    }
  }'
```

请求不能携带 `context`、fixture 数据、fault、clock、random seed、Session
binding 或 timeout。服务端按 compiled plan 重新解析 exact ScenarioCase、
企业 scope v2 TestSuite/TestCase、Fixture、MirrorPlan、ACTIVE assertion
和可选 checkpoint，再执行：

```text
TestSuite case input -> graph context
exact case binding   -> MirrorExecutionRequest
child request id     -> <aggregate-request-id>:case:<zero-padded-index>
Mirror run           -> independently verified signed evidence
verified evidence    -> complete assertion result closure
case results         -> derived aggregate outcome and summary
aggregate result     -> independent Ed25519 attestation
verified bundle      -> append-only evidence store
```

`POST` 返回的 `payload` 不是裸 `ScenarioRehearsalResult`，而是
`resourceGateway.scenarioRehearsalEvidenceBundle.v1`。稳定聚合标识位于：

```text
payload.attestation.runId
```

随后可以在同一完整企业 scope 内重读证据：

```bash
curl -sS \
  http://localhost:8080/api/mirror/scenarios/runs/scenario-REPLACE_WITH_64_HEX/evidence \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL'
```

消费端必须验证 `bundleFingerprint`、完整 result content address 和独立
attestation，不能只相信 HTTP 200 或 `signatureStatus` 字段。服务端在签发后
立即复验，在写入和读取 append-only store 时再次复验；签名 authority 不可用、
材料漂移或库中内容被篡改都会失败关闭。

Java/CI 消费端可直接使用不依赖服务端类的 Test Kit：

```java
ScenarioRehearsalEvidenceVerifier.VerificationResult verified =
        new ScenarioRehearsalEvidenceVerifier().verify(bundleJson, publicKey);
if (!verified.verified()) {
    throw new IllegalStateException(verified.reasonCode());
}
```

该 verifier 不只验签，还会重算每个 assertion/case/result/bundle fingerprint，
重新派生 case/aggregate outcome 与 summary，并检查完整身份、时间和 evidence
closure；即使生产者用合法 key 给错误的 PASS 结论签名也会被拒绝。

### 8.1 保留、法律保全和删除证明

终态提交不允许先发布 evidence、稍后再“尽力登记”保留信息。
`scenario_rehearsal_evidence`、request `COMPLETED`、lifecycle/operation audit
和 revision 1 `RETENTION_REGISTERED` 位于同一本地事务。最短保留边界由服务端在
request registration 时计算为运行时刻后至少 30 天，客户端不能缩短或覆盖。

查看当前状态：

```bash
curl -sS \
  http://localhost:8080/api/mirror/scenarios/runs/scenario-REPLACE_WITH_64_HEX/retention \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: GOVERNANCE_EVIDENCE_INGESTION'
```

放置和释放 hold 必须使用 `LEGAL_HOLD` purpose。`commandId` 是操作幂等键，
`holdId` 是独立保全身份；同一 aggregate 可以同时持有多个 hold：

```bash
curl -sS -X POST \
  http://localhost:8080/api/mirror/scenarios/runs/scenario-REPLACE_WITH_64_HEX/retention/holds \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: LEGAL_HOLD' \
  -H 'Content-Type: application/json' \
  --data '{
    "schemaVersion": "resourceGateway.scenarioRehearsalLegalHoldCommand.v1",
    "commandId": "legal-case-2026-001-place",
    "holdId": "legal-case-2026-001",
    "reasonCode": "RG.MIRROR.REHEARSAL.LITIGATION"
  }'
```

释放时调用 `hold-releases`，使用新的 `commandId` 和同一个 `holdId`。已经释放
的 hold id 不允许复用；这避免一条旧审计链被解释成一次新的法律保全。

到期清除必须使用 `PAYLOAD_RETENTION_ADMIN` purpose：

```bash
curl -sS -X POST \
  http://localhost:8080/api/mirror/scenarios/runs/scenario-REPLACE_WITH_64_HEX/retention/purge \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: PAYLOAD_RETENTION_ADMIN' \
  -H 'Content-Type: application/json' \
  --data '{
    "schemaVersion": "resourceGateway.scenarioRehearsalPurgeCommand.v1",
    "commandId": "retention-purge-2026-001",
    "reasonCode": "RG.MIRROR.REHEARSAL.RETENTION_EXPIRED"
  }'
```

清除只删除 aggregate evidence 和 aggregate case progress。request tombstone、
lifecycle/operation audit、签名 retention event chain 和 deletion proof 保留；
child Mirror evidence 可能被其他聚合引用，因此明确记录为 `RETAINED`，不级联
删除。之后 evidence 路由返回 `410
RG.MIRROR.REHEARSAL.EVIDENCE_PURGED`，并只暴露删除证明 fingerprint 与时间。

ANEKE/CI 应验证响应 `payload`，而不是相信状态字符串：

```java
ScenarioRehearsalRetentionVerifier.VerificationResult verified =
        new ScenarioRehearsalRetentionVerifier()
                .verify(retentionStateJson, publicKey);
if (!verified.verifiedDeletionProof()) {
    throw new IllegalStateException(verified.reasonCode());
}
```

该 verifier 对严格 Schema、projection/latest-event 闭包、规范事件指纹、签名
时间、key id/lifecycle 和 Ed25519 seal 逐项失败关闭。当前 API 返回最新事件和
前序事件 fingerprint；完整事件链由服务端每次读取重建验证，跨系统全历史导出
与外部 transparency anchor 仍是后续部署能力。

### 8.2 导出并独立验证正确性工作簿

workbook seed 只从仍然可读的终态 aggregate 生成。服务端先重新验证 aggregate
bundle、exact compiled plan 和完整 retention event chain，再把 revision 1 的
`RETENTION_REGISTERED` 签名事件作为稳定保留证明嵌入 seed：

```bash
curl -sS \
  http://localhost:8080/api/mirror/scenarios/runs/scenario-REPLACE_WITH_64_HEX/workbook-seed \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: GOVERNANCE_EVIDENCE_INGESTION'
```

seed 包含完整 enterprise scope、Pack/Plan/Capability exact ref、aggregate/result
fingerprint、两类签名 key identity、业务 outcome/summary、按编译顺序排列的 case
与 assertion 结果，以及服务端保守派生的 blockers；不包含 TestCase input、
Fixture value、Session payload、节点业务值或凭据。相同来源闭包必须得到相同
`seedFingerprint`，legal hold 的后续变更不会改写 revision 1 的运行时保留承诺。
aggregate 被合法清除后，seed 不会脱离源证据继续发布。

当前固定 blocker 语义为：

| 条件 | blocker |
|---|---|
| aggregate outcome 为 `FAIL` | `REHEARSAL_FAILED` |
| aggregate outcome 为 `INDETERMINATE` | `REHEARSAL_INDETERMINATE` |
| case 没有完整 child evidence | `CASE_EVIDENCE_MISSING` |
| child evidence 不是 `CERTIFIABLE` | `CHILD_EVIDENCE_NOT_CERTIFIABLE` |
| blocker assertion 为 `FAIL` | `BLOCKER_ASSERTION_FAILED` |
| blocker assertion 为 `INDETERMINATE` | `BLOCKER_ASSERTION_INDETERMINATE` |

warning assertion 仍进入 summary 和 case projection，但不单独形成发布 blocker。
`gateReady=true` 当且仅当重新派生的 blocker 集合为空。它不是 ANEKE 的最终发布
决策，也不能被生产者或调用方覆盖。

Java/CI 推荐使用一键闭包读取；该调用会读取 seed、evidence、exact plan 和两把
公开验签密钥，再用独立 verifier 重算：

```java
JsonNode verifiedSeed =
        client.findScenarioRehearsalWorkbookSeed(runId);
```

离线场景可显式提供五份材料：

```java
ScenarioRehearsalWorkbookVerifier.VerificationResult result =
        new ScenarioRehearsalWorkbookVerifier().verify(
                workbookSeed, compiledPlan, aggregateBundle,
                aggregateEvidenceKey, retentionEventKey);
if (!result.verified()) {
    throw new IllegalStateException(result.reasonCode());
}
```

verifier 不复用服务端领域对象，会独立执行 strict Schema、计划 content address、
aggregate signature、retention signature、source identity、case/assertion closure、
blocker/gate 派生和 seed content address 校验。即使生产者用合法密钥重新封装了
一个错误 `gateReady` 或替换了 case，消费端也会拒绝。

case 结果遵循以下保守语义：

| 条件 | case outcome |
|---|---|
| run `PASSED`，所有 blocker assertion `PASS` | `PASS` |
| 确定性执行失败，或任一 blocker assertion `FAIL` | `FAIL` |
| evidence 不完整/取消，或任一 blocker assertion `INDETERMINATE` | `INDETERMINATE` |
| 无 child evidence 的非重试型拒绝 | `FAIL`，保留 payload-free diagnostic code |
| 总预算不足以再启动一个完整 case | `INDETERMINATE`，该 case 不调度 |

warning assertion 的失败和不确定会进入 summary，但不会改变 case outcome。
聚合优先级固定为 `FAIL > INDETERMINATE > PASS`，客户端不能提交或覆盖结果。

相同 aggregate request id 会生成相同 child request id。已有 Mirror 子运行使用
耐久请求租约和 evidence 存储，因此中断后重试可以复用已完成 child。stateful
case 直接把 checkpoint 的 session id + state fingerprint 作为原始 fence 交给
子协调器：completed retry 先命中耐久结果；只有新执行才检查当前 Session head。

当前版本的明确限制：

- aggregate 已有完整 scope request registration、数据库时钟 lease、单调 owner
  epoch 和连续 case progress；并发请求在 child orchestration 前返回 retryable
  `409`，lease 到期后下一副本从首个未完成 case 接管；
- case checkpoint、signed evidence 插入和 terminal request transition 均受当前
  owner/epoch/expiry 栅栏；最终 evidence 与 terminal transition 同事务提交；
- 聚合 evidence 已独立签名、耐久保存和可读取；run/evidence read 的 protected
  operation audit 已失败关闭，内部 claim/takeover/checkpoint/release/complete
  转换也有同事务 payload-free lifecycle audit；retention registration、multi-hold、
  purge 与签名 deletion proof 已闭合，deterministic workbook seed 和独立消费闭包
  也已完成；尚无企业 policy authority、WORM/transparency anchor 和消费环境认证；
- checkpoint 是同一加密数据面的 recovery fence，不是隔离 clone；全新重复执行
  需要新的隔离 Session/checkpoint；
- operator 级 scalar TestSuite input 暂不能直接作为 graph context；
- 单个 aggregate 只支持 sequential，最多 256 个 case；
- Resource Gateway 只输出 ANEKE seed；ANEKE workbook 持久化、owner approval、
  最终 publish gate 和 Author UX 尚未交付。

因此 capability probe 将 `mirrorScenarioRehearsalExecution` 与
`mirrorScenarioRehearsalEvidenceApi` 报告为 `true`，但
`mirrorScenarioRehearsalEvidence` 仍为 `false`。调用方不得把“证据协议/API
和本地 workbook closure 已可用”误读为“已通过生产发布认证”。

### 8.1 耐久批次控制面

批次请求只表达“对哪些已编译计划执行回归”，不能覆盖 fixture、context、
Session、priority、并发度、retry 或 timeout。一个请求最多 256 个 exact plan，
解析后的总 case 数最多 10,000：

```bash
curl -sS -X POST \
  http://localhost:8080/api/mirror/rehearsal-jobs \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL' \
  -H 'Content-Type: application/json' \
  --data '{
    "schemaVersion": "resourceGateway.scenarioRehearsalBatchRequest.v1",
    "requestId": "nightly-refund-regression-20260724",
    "entries": [
      {
        "entryId": "refund-happy-path",
        "compiledPlanRef": {
          "kind": "COMPILED_REHEARSAL_PLAN",
          "id": "refund-pack-plan",
          "revision": 1,
          "fingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        }
      },
      {
        "entryId": "refund-timeout-path",
        "compiledPlanRef": {
          "kind": "COMPILED_REHEARSAL_PLAN",
          "id": "refund-timeout-plan",
          "revision": 1,
          "fingerprint": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        }
      }
    ]
  }'
```

服务端先通过 Scenario authority 重新解析每个 plan，再封存不可变 manifest。
manifest 确定性派生 batch id、每个 child aggregate request/run id、case count
和计划预算；同 scope + request id + 相同内容重试返回同一个 job，内容漂移返回
immutable conflict。队列使用数据库时钟和数据库权威容量，不以 JVM 内存计数器
做 admission；claim 会在租户间轮转并对等待任务做优先级老化。

查询与取消：

```bash
curl -sS \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL' \
  'http://localhost:8080/api/mirror/rehearsal-jobs/<jobId>'

curl -sS \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL' \
  'http://localhost:8080/api/mirror/rehearsal-jobs/<jobId>/items?startIndex=0&limit=50'

curl -sS \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: GOVERNANCE_EVIDENCE_INGESTION' \
  'http://localhost:8080/api/mirror/rehearsal-jobs/<jobId>/evidence'

curl -sS \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: GOVERNANCE_EVIDENCE_INGESTION' \
  'http://localhost:8080/api/mirror/rehearsal-jobs/<jobId>/finalization'

curl -sS \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: GOVERNANCE_EVIDENCE_INGESTION' \
  'http://localhost:8080/api/mirror/rehearsal-jobs/finalization-health'

curl -sS -X POST \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL' \
  -H 'Content-Type: application/json' \
  --data '{
    "schemaVersion": "resourceGateway.scenarioRehearsalBatchCancellationRequest.v1",
    "commandId": "cancel-nightly-refund-20260724",
    "reasonCode": "OWNER_CANCELLED"
  }' \
  'http://localhost:8080/api/mirror/rehearsal-jobs/<jobId>/cancellations'
```

默认启动仍只装配队列、协议、API 和 worker turn，不启动后台线程。演示自治批次：

```bash
./scripts/start-visual-canvas-demo.sh --scenario-batch
```

脚本会派生稳定 DAG worker/finalizer instance id，把两个 scheduler 和 demo
identity 对齐到同一个 `sg/test`（staging profile 对应 staging），并在 capability
动态确认普通调度与 evidence finalization 调度都 ready 后才报告 ready。手动配置
使用：

```bash
export RG_MIRROR_RUNTIME_ENABLED=true
export RG_MIRROR_SCENARIO_BATCH_SCHEDULER_ENABLED=true
export RG_MIRROR_SCENARIO_BATCH_INSTANCE_ID=rg-sg-test-01
export RG_MIRROR_SCENARIO_BATCH_REGION=sg
export RG_MIRROR_SCENARIO_BATCH_ENVIRONMENT=test
export RG_MIRROR_SCENARIO_BATCH_MAXIMUM_POLLERS=4
export RG_MIRROR_SCENARIO_BATCH_FINALIZATION_SCHEDULER_ENABLED=true
export RG_MIRROR_SCENARIO_BATCH_FINALIZATION_INSTANCE_ID=rg-sg-test-01-finalizer
export RG_MIRROR_SCENARIO_BATCH_FINALIZATION_REGION=sg
export RG_MIRROR_SCENARIO_BATCH_FINALIZATION_ENVIRONMENT=test
export RG_MIRROR_SCENARIO_BATCH_FINALIZATION_MAXIMUM_POLLERS=1
export RG_MIRROR_SCENARIO_BATCH_FINALIZATION_SLO_OBSERVATION_INTERVAL_MILLIS=30000
export RG_MIRROR_SCENARIO_BATCH_FINALIZATION_SLO_MAXIMUM_ELIGIBLE_BACKLOG=100
export RG_MIRROR_SCENARIO_BATCH_FINALIZATION_SLO_MAXIMUM_OLDEST_ELIGIBLE_AGE_SECONDS=300
export RG_MIRROR_SCENARIO_BATCH_FINALIZATION_SLO_MAXIMUM_ACTIVE_SIGNING_AGE_SECONDS=90
export RG_MIRROR_SCENARIO_BATCH_FINALIZATION_SLO_MAXIMUM_QUARANTINED_BACKLOG=0
export RG_MIRROR_SCENARIO_BATCH_FINALIZATION_SLO_CRITICAL_QUARANTINED_BACKLOG=100
export RG_MIRROR_SCENARIO_BATCH_FINALIZATION_SLO_MAXIMUM_SIGNER_UNAVAILABLE_BACKLOG=10
export RG_MIRROR_SCENARIO_BATCH_FINALIZATION_SLO_MAXIMUM_CONTROL_UNAVAILABLE_BACKLOG=10
export RG_INTEGRATION_REGION=sg
export RG_INTEGRATION_ENVIRONMENT_ID=test
```

每个进程只轮询一个 exact `(region, environment)`；数据库协调键、policy
generation、容量、tenant fairness、reconcile、DAG claim 与 finalization claim
均使用同一完整分区，同名环境跨地域互不争抢容量。DAG lane 与 KMS lane 分属不同
线程池和并发上限；停止时先取消未来 poll，再分别等待当前 turn 有界 drain，最终
发布仍受数据库 lease/epoch fence 约束。

Scenario runtime 现在提供 server-owned、payload-free 的 execution-control hook，
在 case resolution 前、每个外部 case 前、case progress 耐久 checkpoint 后以及
aggregate commit 前回调。batch worker 在这些边界以数据库时钟核对 exact
owner/epoch/item fence，写入单调 heartbeat count 与 next-case cursor，并在同一事务
观察 cancellation/deadline。运行中取消最迟在当前受 case timeout 约束的外部调用
返回后收敛；已完成 case 先落耐久 progress，batch 当前 item 保守标为
`INDETERMINATE`，未开始项标为 `CANCELLED`。

heartbeat 不延长 lease。claim 已按 immutable compiled-plan timeout 加 commit reserve
一次性分配权限；允许 checkpoint 无限续期会破坏测试的有界性。当前仍不能物理终止
不合作的 operator，也尚无 PostgreSQL 多副本认证。调用方必须同时检查
`mirrorScenarioRehearsalBatchCooperativeControl` 与 scheduling capability，不能只看
API 是否存在。

终态发布采用 `request -> manifest -> terminal job + ordered items -> signed bundle`
闭包，但远程签名不再发生在 DAG 分区锁事务内。worker 先在短事务中冻结
content-addressed `FinalizationIntent`，把 job 更新为
`FINALIZING_EVIDENCE`；独立 finalizer 使用数据库 lease 在事务外重新验证每个 child
aggregate 的 scope、run id、evidence/workbook fingerprint，并生成
`ScenarioRehearsalBatchEvidenceIndex.v2` 与
`ScenarioRehearsalBatchEvidenceBundle.v2`。签名和 retention seal 都准备完成后，
finalizer 以 owner/epoch fence 在一个短事务中原子提交 terminal job、evidence、
retention、lifecycle audit 和 `FINALIZED` outbox。批次包只携带 child 内容地址，
不复制最多 256 份 aggregate bundle，因而保持 payload-free 和有界。

finalization 状态为 `PENDING -> SIGNING -> FINALIZED`，可恢复故障进入有界
`RETRY_WAIT`，永久 material/signature 故障或 20 次预算耗尽进入
`QUARANTINED`。首次 claim 冻结 `signingStartedAt` 和 stable
`signingRequestId`；陈旧 lease 接管继续复用它们，KMS 响应丢失后的 exact replay
不会因 key rotation 生成第二份合法 bundle。一个 quarantined intent 不会阻塞同
分区后续工作。Owner 修复不是放开旧 claim：调用方先读取 status，再以专用
`MIRROR_REHEARSAL_FINALIZATION_ADMIN` purpose 提交
`commandId + expectedAttemptCount + expectedUpdatedAt + reasonCode`。仓储只接受
仍处于该 exact `QUARANTINED` fence 的 generation，在一个事务内重建新的
content-addressed intent、terminal projection 与 signing request，递增旧 lease
epoch、把 attempt 归零、续期 `retainUntil` 至至少
`acceptedAt + terminalRetention`，并写入 immutable remediation receipt、lifecycle
fact 和 protected-operation success audit。旧页面、重复但不同内容的 command、
非隔离状态和审计失败均不会改变运行状态。

聚合健康不是扫描某几个“最近失败”任务，而是用同一个数据库时间快照对当前
分区或 exact enterprise scope 的控制行做闭合记账。它分别统计
`PENDING/SIGNING/RETRY_WAIT/QUARANTINED/FINALIZED`、未知状态、当前可 claim
数量、陈旧 signing lease、损坏控制记录、policy generation 漂移、四类稳定失败
和最大 attempt；同时计算最老未完成、可处理、隔离和活动签名年龄。未知状态不会
从分母中消失并制造假绿。

`ScenarioRehearsalBatchFinalizationHealth.v1` 只服务认证身份自己的完整
tenant/organization/project/environment/region，返回服务端阈值用于解释但不允许
调用方覆盖。部署 Actuator contributor 只观察本进程 scheduler 所属
`(region, environment)`；它可以跨该分区内多个租户聚合，但该结果不会通过租户 API
暴露。`HEALTHY` 和仅含 quarantine/活动签名 warning 的 `DEGRADED` 保持 readiness
`UP`；积压数或年龄超限、陈旧 lease、控制损坏、policy drift 或 KMS/control
故障压力为 `CRITICAL/OUT_OF_SERVICE`；数据库观察失败为 `UNAVAILABLE/DOWN`。
API、Actuator 和 Micrometer 共用同一 evaluator，避免三套阈值互相矛盾。指标标签
只使用闭集 state/failure/health，不含 region、scope、job、provider 或异常文本。

Test Kit 的 `findScenarioRehearsalBatchEvidence(jobId)` 接受 v1/v2，获取公开 key
后重新派生 request/manifest/job/index/bundle fingerprint、完整 scope batch id、
每个 child request/run id、总 case 数、item 顺序、终态 summary、签名时间 key
policy 和 Ed25519 signature。`findScenarioRehearsalBatchFinalization(jobId)` 在
strict Schema 校验和 job-id 绑定后返回 payload-free 控制状态；它不暴露 worker、
provider diagnostics 或业务数据。v1 仍按原域验证，不能以 v2 verifier 规则静默
改写历史证据。Test Kit 的
`remediateScenarioRehearsalBatchFinalization(jobId, request)` 会先校验严格命令
Schema，使用专用 admin purpose，并拒绝 job、command 或 reviewed attempt 被替换
的回执。
`findScenarioRehearsalBatchFinalizationHealth()` 使用 governance evidence purpose，
对 strict Schema、完整 scope、状态/violation 一致性和无 job-id/payload 结构做
独立校验。它不把 deployment partition health 冒充为调用方 scope health。

受保护的 batch submit/read/items/evidence/cancel 使用四个独立、固定基数
`MirrorOperationAuditEvent.Operation`。submit/cancel 的成功事实位于队列写事务
内部；成功审计失败会回滚 admission/cancellation/evidence，读取缺失则先提交
`NOT_FOUND` 拒绝审计再返回 404。内部队列另外维护 payload-free
`scenario_rehearsal_batch_lifecycle_audit`，只追加 `ADMITTED`、`CLAIMED`、
`ITEM_TERMINALIZED`、`ITEM_RETRY_SCHEDULED`、`CANCELLATION_REQUESTED`、
`FINALIZATION_QUEUED`、`FINALIZATION_REMEDIATED` 和 `TERMINALIZED`。它绑定完整
scope、job/request/manifest、item/attempt、
lease epoch、稳定 reason 和 evidence fingerprint；fixture、业务输入输出、凭据、
异常文本和栈不可表示。heartbeat 是高频运行信号，不进入 lifecycle audit。

批次 retention registration 与 signed batch evidence、terminal job/item 和
`TERMINALIZED` lifecycle event 共用一个事务。准入时数据库已经冻结
`retainUntil = deadlineAt + terminalRetention`，终态发布只复用该值，因此快速完成
不会缩短最短保留期，晚完成也不会暗中延长已承诺的删除边界。默认
`terminalRetention` 为 30 天，当前由本地一致性 policy generation 管理；企业 policy
authority 尚未交付。

读取与法律保全：

```bash
curl -sS \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: GOVERNANCE_EVIDENCE_INGESTION' \
  'http://localhost:8080/api/mirror/rehearsal-jobs/<jobId>/retention'

curl -sS -X POST \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: LEGAL_HOLD' \
  -H 'Content-Type: application/json' \
  --data '{
    "schemaVersion": "resourceGateway.scenarioRehearsalLegalHoldCommand.v1",
    "commandId": "batch-hold-command-20260725",
    "holdId": "litigation-case-2026-017",
    "reasonCode": "RG.MIRROR.REHEARSAL.LITIGATION"
  }' \
  'http://localhost:8080/api/mirror/rehearsal-jobs/<jobId>/retention/holds'
```

释放 hold 使用同一 command shape 和
`/retention/hold-releases`，但必须使用新的 `commandId`。同一 command 的 exact
重放是幂等的；语义漂移或复用已经释放的 `holdId` 会失败关闭。到期清除：

```bash
curl -sS -X POST \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: PAYLOAD_RETENTION_ADMIN' \
  -H 'Content-Type: application/json' \
  --data '{
    "schemaVersion": "resourceGateway.scenarioRehearsalPurgeCommand.v1",
    "commandId": "batch-purge-command-20260725",
    "reasonCode": "RG.MIRROR.REHEARSAL.BATCH_RETENTION_EXPIRED"
  }' \
  'http://localhost:8080/api/mirror/rehearsal-jobs/<jobId>/retention/purge'
```

purge 前会重读并复验 signed batch bundle，重算 terminal job 和全部 item
fingerprint，并要求它们与 evidence index 精确一致。成功事务只删除一条 batch job、
全部 batch item 和一条 batch evidence；child Scenario evidence、
operation/lifecycle audit 和 retention event chain 保留。最新 `PURGED` 事件记录
三类精确删除计数及两个 `RETAINED` disposition。它是数据库逻辑删除证明，不是
磁盘擦除、备份清除、外部 WORM 或跨地域删除证明。

Java/CI 可让 Test Kit 自动完成 key 获取和独立验证：

```java
JsonNode state =
        client.findScenarioRehearsalBatchRetention(jobId);
JsonNode deletionProof =
        client.purgeScenarioRehearsalBatch(jobId, purgeCommand);
```

`placeScenarioRehearsalBatchLegalHold` 和
`releaseScenarioRehearsalBatchLegalHold` 使用同一严格命令协议。四个入口都会在
请求离开进程前验证 Schema，并在返回前验证 batch identity、投影/事件闭包、签名
时间、key lifecycle 和 Ed25519 seal。

### 8.3 导出并独立验证批量正确性工作簿

batch workbook 只允许从仍可读取且重新验签通过的 terminal batch 生成。服务端
依次复验 batch v1/v2 evidence、初始 batch retention registration，以及签名 index
引用的每个 child workbook seed；任何缺失、额外、重复、跨 scope、run/plan/
evidence/workbook fingerprint 漂移都会失败关闭：

```bash
curl -sS \
  http://localhost:8080/api/mirror/rehearsal-jobs/scenario-batch-REPLACE_WITH_64_HEX/workbook-seed \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: GOVERNANCE_EVIDENCE_INGESTION'
```

响应最多包含 256 个按 manifest index 排列的 entry。每个 entry 保留 exact plan、
child request/run、attempt、terminal status、evidence/workbook content address，
以及 child 的 outcome/summary/gate/blocker 有界投影，不复制 child 的 case 明细、
Fixture、Session state 或业务 payload。根级 blocker 固定为：

| 条件 | blocker |
|---|---|
| batch 不是 `SUCCEEDED` | `BATCH_STATUS_<STATUS>` |
| 任一 item 为 `FAILED` | `BATCH_ITEM_FAILED` |
| 任一 item 为 `INDETERMINATE` | `BATCH_ITEM_INDETERMINATE` |
| 任一 item 为 `CANCELLED` | `BATCH_ITEM_CANCELLED` |
| 非取消 item 没有完整 child evidence/workbook | `CHILD_EVIDENCE_MISSING` |
| 任一 child workbook gate 被阻断 | `CHILD_WORKBOOK_BLOCKED` |

执行成功和治理可发布被刻意分开：全部 item `PASSED` 时 batch status 可以是
`SUCCEEDED`，但只要某个 child 只有 exploratory evidence，整批仍为
`gateReady=false + CHILD_WORKBOOK_BLOCKED`。

`seedFingerprint` 是排除 detached seal 的确定性内容地址；同一来源闭包在 key
rotation 前后仍保持相同。服务端使用独立签名域
`RESOURCE_GATEWAY_SCENARIO_REHEARSAL_BATCH_WORKBOOK_V1` 对 seed identity、job、
batch bundle 和 index 坐标签发 `workbookSeal`。因此普通 ANEKE/CI ingestion
不需要逐个读取 child：

```java
JsonNode verifiedBatchSeed =
        client.findScenarioRehearsalBatchWorkbookSeed(jobId);
```

该调用只读取 batch seed、signed batch evidence，以及 batch evidence、batch
retention、workbook seal 三把公开 key。独立 verifier 不链接 Spring 或服务端领域
对象，并重新执行 strict Schema、batch signature、retention signature、root seal、
request/manifest/job/item closure、summary、blocker/gate 和 seed content address
校验：

```java
ScenarioRehearsalBatchWorkbookVerifier.VerificationResult result =
        new ScenarioRehearsalBatchWorkbookVerifier().verify(
                batchSeed, signedBatchEvidence,
                batchEvidenceKey, batchRetentionKey, workbookSealKey);
if (!result.verified()) {
    throw new IllegalStateException(result.reasonCode());
}
```

当审计人员需要证明某个有界 child projection 也确实来自其完整 case-level seed，
再按需拉取 child seeds 并调用 `verifyWithChildren(...)`。这条深验路径拒绝缺失、
额外、重复或替换的 child；它不是每次 publish-gate ingestion 的前置 N+1。
ANEKE 仍拥有 workbook 持久化、owner approval 与最终发布裁决，Resource Gateway
的 `gateReady` 只代表这份已验签 Scenario 闭包没有本地 blocker。

### 8.4 Owner 工作台的批次发现协议

Owner 工作台不能要求业务人员先从日志或数据库复制 `jobId`。受保护
`GET /api/mirror/rehearsal-jobs` 使用
`ScenarioRehearsalBatchJobPage.v1` 返回当前认证完整 scope 内的最新批次：

```bash
curl -sS \
  'http://localhost:8080/api/mirror/rehearsal-jobs?limit=25' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL'
```

下一页必须原样提交上一页 `nextCursor` 的两个坐标：

```text
?limit=25
&beforeCreatedAt=2026-07-25T03:00:00Z
&beforeJobId=scenario-batch-<64-hex>
```

排序固定为 `createdAt DESC, jobId DESC`。`createdAt` 在准入后不可变，因此运行状态、
进度或终态更新不会把行移到另一页；同一数据库时间内用确定性 `jobId` 完成全序。
查询始终另外绑定认证得到的 tenant/organization/project/environment/region，cursor
只是位置坐标，不是授权令牌。缺一字段、时间非法、job id 非规范或超过 100 行都失败
关闭。服务端逐行重验 job record fingerprint，Test Kit 还会复验 strict Schema、
全页 scope、唯一性、降序和 cursor 与末行的一致性。

这个协议只解决工作台“找到批次并稳定翻页”的底座，不把 mutable job projection
冒充签名证据。运行中页面可用 job/item 显示进度；终态治理结论必须切换到已验签
batch workbook，选中某个异常 entry 后才按需打开 child workbook 的 case/assertion
明细，避免默认 N+1。

### 8.5 Owner 演练工作台

使用演示脚本启动批量执行与证据终态化 worker：

```bash
./scripts/start-visual-canvas-demo.sh --scenario-batch
```

打开 `http://localhost:8080/rehearsals/`。工作台与 `/author/`、`/showcase/`
使用同一 React/Vite 包，但拥有独立可寻址路由，不通过 iframe 拼接。页面由三层任务
信息组成：

| 区域 | Owner 看到什么 | 数据权威 |
|---|---|---|
| 左侧批次队列 | 当前认证 exact scope、最新批次、状态和完成数；`Load older batches` 使用服务端 keyset cursor | mutable、完整性保护的 batch job projection |
| 中间分诊区 | 批次进度、gate 状态、根 blocker，以及按 Execution/Evidence/Assertions/Governance/Warnings/Passed 分组的 entry | 运行中读 item page；终态读 root-sealed batch workbook |
| 右侧证据抽屉 | exact plan/run/content address、case 汇总、blocker、case 与 handling assertion 结果 | 选中终态 entry 后才读取 child workbook |

`Live projection` 明确表示批次仍可能变化，页面同时显示
`Mutable and not publish-gate evidence`。终态批次才显示 `Signed workbook`；
这表示服务端已经重验 batch evidence、retention closure 和 root seal，并不表示
ANEKE 已作出最终发布裁决。

分诊类别不是新的后端事实，也不覆盖签名结果，而是稳定的只读任务投影：

| 类别 | 归类依据 | Owner 首先处理什么 |
|---|---|---|
| Execution | item failed/cancelled 或有稳定 failure code | timeout、target/runtime、取消和重试耗尽 |
| Evidence | indeterminate、缺完整 evidence/workbook commitment 或仍为 mutable state | signer/store、证据缺失和未知结果 |
| Assertions | child blocker failure/indeterminate 大于零 | 失败的业务处置断言及其治理码 |
| Governance | child `gateReady=false` 或有非断言 blocker | evidence class、policy 和 closure |
| Warnings | warning assertion 失败或不确定 | 非阻断验证债务 |
| Passed | item passed 且没有 child blocker | 可抽查，不优先占用故障队列 |

点击 entry 后，地址栏会保存
`/rehearsals/?jobId=<jobId>&entry=<manifest-index>`。刷新或从 ANEKE deep link
进入时，客户端通过有界 keyset 扫描定位 exact job；如果 job 不属于当前认证 scope，
页面明确报告不可见，不尝试跨 scope 查询。终态 child workbook 采用 lazy read，
因此打开 256-item 批次不会产生 256 次 case-level 请求。

当前浏览器默认凭证只被仓库的 test/staging demo identity 接受，宿主或 VSCode
Webview 必须通过 `setOperatorTestHeadersProvider(...)` 注入短期身份。页面只请求
`GOVERNANCE_EVIDENCE_INGESTION` purpose，不保存 token，也没有取消、修复、legal
hold、purge 或 finalization-admin 按钮。Reviewed remediation 和零 DSL case 调整
仍是下一阶段，不能通过开放通用 JSON/DSL 编辑器绕过。

### 8.6 经评审修复协议

Owner 的业务修复与 finalization 管理员修复是两套不同协议。前者创建不可变的后继
批次；后者只重放 quarantined KMS/outbox intent，不改变业务计划，也不能暴露给
普通 Owner。

业务修复固定经过以下对象，不能跳过 preview 或用 submit 请求重新描述变更：

| 阶段 | Schema | 不变量 |
|---|---|---|
| 预览意图 | `scenario-rehearsal-remediation-preview-request-v1` | 绑定 exact predecessor workbook；选择 `RERUN_EXACT` 或 `REPLACE_COMPILED_PLANS`；使用闭集 reason code |
| 冻结计划 | `scenario-rehearsal-remediation-plan-v1` | content-addressed；冻结完整 successor request、predecessor blocker 和固定双角色审批策略 |
| 审批命令 | `scenario-rehearsal-remediation-approval-command-v1` | 比较 plan fingerprint 与 expected approval generation；批准和拒绝 reason 不能混用 |
| 审批事实 | `scenario-rehearsal-remediation-approval-v1` | append-only；server-owned actor/time/delegation；previous fingerprint 形成链 |
| 提交命令 | `scenario-rehearsal-remediation-submit-command-v1` | 同时比较 plan、approval generation 与 approval head；至少完成两级审批 |
| 提交回执 | `scenario-rehearsal-remediation-receipt-v1` | predecessor 与 successor job 必须不同；绑定 frozen request 与最终审批链 |

首版 `RERUN_EXACT` 只能用于可重试的运行/证据原因，不得携带 replacement。
`REPLACE_COMPILED_PLANS` 只替换指定 entry 的 exact existing compiled plan，
必须保留原 entry id、index 和批次顺序；没有列出的 entry 保持不变。计划不得删除
case、改写 DSL/JSON、降低断言严重度，或放松网络、凭证、运行时和 certification
约束。`governanceTicketRef` 必须是
`GOVERNANCE_REVIEW_TICKET` 类型的 exact resource reference。

固定审批策略为先 `OWNER`、后 `INDEPENDENT_REVIEWER`，并要求两个不同 actor。
客户端只能提交角色和决定；服务端从认证上下文写入 actor、delegation 和时间。
Schema 负责结构失败关闭，应用服务还必须在数据库事务内校验 exact predecessor、
replacement closure、角色顺序、actor separation、generation/head CAS 与幂等
successor enqueue。

当前交付边界是 Java protocol、六份 authoritative Schema、Test Kit validator 和
capability `supportedObjects` 版本目录。尚未提供 preview/approve/submit API、
repository、successor 调度或工作台按钮，因此没有对应 capability readiness flag。
这些服务落地前，`/rehearsals/` 仍是只读分诊面。

## 9. 失败语义

| 失败类别 | 处理原则 |
|---|---|
| 身份或 purpose 不合法 | 在解析请求体前拒绝 |
| exact dependency 不存在 | 失败关闭，不回退到 latest |
| fingerprint、scope、target 或 execution service 漂移 | 拒绝编译 |
| Fixture 存在隐式 fault | 拒绝编译，要求 case 显式声明 |
| checkpoint 签名无效、过期或状态闭包漂移 | 拒绝编译 |
| 同 revision 不同内容 | immutable conflict |
| artifact store 不可用 | `503`，不返回缓存猜测 |

错误响应统一使用 Resource Gateway `IntegrationProblem`，只携带稳定 code、
correlation id 和 payload-free details。认证失败在 decoder 前发生，避免未授权
请求通过解析差异探测协议内部结构。

## 10. 单断言求值

`ScenarioHandlingAssertionEvaluator` 不接收裸 `MirrorEvidenceBundle`。调用方必须
先通过持有验证 key authority 的 `MirrorEvidenceIntegrityService` 获取类型化能力令牌：

```java
MirrorEvidenceIntegrityService.VerifiedBundle verified =
        evidenceIntegrity.requireVerified(bundle);
ScenarioHandlingAssertionResult result =
        assertionEvaluator.evaluate(assertion, verified);
ScenarioHandlingAssertionResultIntegrity.verify(mapper, result);
```

`requireVerified` 会重算嵌套 resolution/state seal、evidence fingerprint、
bundle fingerprint 和 detached signature，并返回 canonical detached bundle。
验证 authority 不可用与材料/签名无效分别失败；不能用一个调用方布尔值冒充验签结果。
断言还必须是同一完整 enterprise scope 下的 ACTIVE revision，owner approval
不得晚于 evidence `startedAt`，未撤销且到期时间必须严格晚于 `completedAt`。
事后补批和执行窗口内到期都失败关闭。

当前求值覆盖：

| 断言维度 | 当前证据求值语义 |
|---|---|
| `NODE_STATUS` / `EDGE_STATUS` | exact selector 命中的所有状态都必须属于允许集合；没有 observation 为失败 |
| `CAPABILITY_OCCURRENCE` | 按 site + graph path + correlation + occurrence 去重；同一次调用的 retry attempt 不重复计数 |
| `INVOCATION_INPUT` | node input 与 resolution request 的所有可见指纹必须匹配 |
| `ERROR` | node、attempt、resolution 的稳定错误码精确匹配 |
| `STATE_TRANSITION` / `SIDE_EFFECT_RECEIPT` | 从 v4/v5 state evidence 读取 committed/replayed/rejected 状态与 receipt fingerprint |
| `GOVERNANCE_EXPECTATION` | 只有 `CERTIFIABLE` 且 run/isolation limitation 均为空才为 certifiable |
| `LATENCY_BUDGET` | whole run 或 selected node occurrence 的最大耗时 |
| `RETRY_BUDGET` | node attempts 超出第一次的次数；无 node trace 时按 resolution attempt 重建 |
| `RESOURCE_BUDGET` | whole run 或 selector 范围内的 node、edge、resolution 数量 |

当前 signed evidence 尚未携带 path-level graph output/schema、fallback 顺序、
compensation 和 final invariant 事实。因此对应断言固定返回
`INDETERMINATE + ASSERTION_EVIDENCE_FACT_UNAVAILABLE`，而不是从 whole-run
`PASSED` 推断业务正确。`EVIDENCE_INCOMPLETE` 同样固定返回
`INDETERMINATE`。这两类结果在后续 aggregate gate 中都必须阻断 blocker
断言，不能按 warning 静默吞掉。

结果协议为
`resourceGateway.scenarioHandlingAssertionResult.v1`，采用 strict Schema、
256 KiB canonical 上限和 content address。它绑定 exact `runId`、
evidence bundle fingerprint、plan fingerprint 和 assertion ref，不复制
request、response、实体或 fixture payload。

## 11. 数据库与重启语义

场景资产和编译计划存放在独立 append-only 表中：

- `mirror_scenario_artifacts`
- `compiled_scenario_rehearsal_plans`
- `scenario_rehearsal_run_requests`
- `scenario_rehearsal_case_progress`
- `scenario_rehearsal_lifecycle_audit`
- `scenario_rehearsal_evidence`
- `scenario_rehearsal_retention_states`
- `scenario_rehearsal_retention_events`
- `scenario_rehearsal_batch_locks`
- `scenario_rehearsal_batch_policies`
- `scenario_rehearsal_batch_cursors`
- `scenario_rehearsal_batch_jobs`
- `scenario_rehearsal_batch_items`
- `scenario_rehearsal_batch_evidence`
- `scenario_rehearsal_batch_lifecycle_audit`
- `scenario_rehearsal_batch_retention_states`
- `scenario_rehearsal_batch_retention_events`

主键覆盖完整 scope、artifact kind、id 和 revision。写入与读取都重算
canonical fingerprint，并核对数据库索引身份；checkpoint 还会重新验签。
表中没有业务 payload、原始 correlation key 或 mutable latest pointer。

每个 child Mirror run 的 request lease、terminal summary 和 signed evidence
沿用既有耐久仓储。aggregate request 表只保存 scope、request/plan/run
fingerprint、case cursor、lease owner/epoch/expiry、终态 evidence fingerprint
和时间边界。progress 表只追加已经 content-addressed 的
`ScenarioCaseRehearsalResult`，不保存 TestSuite input、Fixture value、node
input/output 或 replay payload。

`scenario_rehearsal_lifecycle_audit` 是独立 append-only 状态转换事实流。每条记录
由数据库分配 sequence/time，并绑定完整 scope、exact compiled plan、stable run、
owner epoch、case cursor 以及 case/evidence fingerprint；没有业务 payload 或异常
文本。生命周期审计 append 与对应 request/progress 转换共用事务，审计不可用时
claim/checkpoint/takeover/release/complete 失败关闭并回滚。

每个 case 完成后先在当前数据库时钟 lease 下原子追加 progress 并推进 cursor。
进程退出后，相同 request id 在 lease 到期或主动 release 后取得 `epoch + 1`，
读取并校验连续 checkpoint 前缀，只执行剩余 case。旧 worker 即使恢复运行，也
无法写入 checkpoint 或 terminal evidence。最终
`scenario_rehearsal_evidence` 插入与 request `COMPLETED` 转换位于同一事务；
lease 在提交前到期会整体回滚，不留下孤儿 evidence。

完成的 `ScenarioRehearsalResult` 被封入独立签名 bundle，按完整 scope + stable
runId append-only 保存；读取时重算 result/bundle fingerprint 并复验 Ed25519
signature。`mirrorScenarioRehearsalEvidence` 仍保持 `false`，原因已从“运行不可
恢复”收敛为缺少企业 policy authority、WORM/外部锚、消费者认证与环境级门禁。
workbook seed 从重新验证的 Plan/Evidence/Retention closure 即时投影，不另建可
漂移的事实仓储。最终 run 提交把 signed evidence、retention registration、
request `COMPLETED`、lifecycle
`COMPLETED` 和
`SCENARIO_REHEARSAL_CREATE` 成功审计放在同一本地事务；evidence read 也必须先
提交 `SCENARIO_REHEARSAL_EVIDENCE_READ` 审计才可发布结果。

批次 evidence 表按完整 scope + stable job id append-only 保存有界签名 bundle，
并冗余保存 request/manifest/job/index/bundle fingerprint 作为查询索引。读取时
不能信任这些列：repository 会重算完整签名闭包，再逐项核对索引列。batch
repository 只有在 evidence publisher 成功后才写 job 终态，因此 signer/store
不可用不会留下 terminal-without-evidence；排队态直接取消也复用相同发布路径。
每个重要队列转换同时追加 batch lifecycle audit，任一审计写失败都会回滚对应的
item/job/evidence。batch retention 使用独立 projection 和签名事件链，不借用
aggregate retention；注册失败会回滚整个批次终态提交。

retention state 是可重建 projection，append-only signed event chain 才是权威。
每次读取/修改会验签完整链、检查 revision/previous fingerprint、重放 multi-hold
状态并与 projection 和数据库索引比对。purge 在同一行锁事务内再次使用数据库
时钟检查 `retainUntil` 和全部 hold，核对 exact evidence fingerprint，删除
aggregate 两张数据表，写入 `PURGED` 事件并更新 projection。任一步骤或强制
operation audit 失败都会回滚。

batch purge 使用同样的数据库时钟、multi-hold、幂等命令和签名事件链原则，但在
删除前额外重算 batch job、全部 item 与 signed evidence index。它只删除三类 batch
closure 表，保留 child aggregate evidence 及 batch operation/lifecycle audit，并用
精确计数和 disposition 形成可离线验证的逻辑删除证明。

本地重启后可以按 exact fingerprint 读取同一资产。数据库备份、跨区域恢复、
WORM、外部 transparency anchor、企业级策略分发和跨地域删除认证仍属于部署
认证，不由本地签名链代替。

## 12. 开发验证

先运行场景纵向聚焦测试：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ScenarioRehearsalControllerTest,ScenarioRehearsalBatchControllerTest,ScenarioArtifactRequestDecoderTest,ScenarioArtifactRegistryServiceTest,ScenarioRehearsalIntegrationServiceTest,ScenarioRehearsalCompilerTest,ScenarioRehearsalRuntimeServiceTest,ScenarioRehearsalBatchProtocolTest,ScenarioRehearsalBatchManifestTest,ScenarioRehearsalBatchJobPageTest,ScenarioRehearsalBatchServiceTest,ScenarioRehearsalBatchWorkbookSeedTest,ScenarioRehearsalBatchWorkbookServiceTest,DatabaseScenarioRehearsalBatchRepositoryTest,DatabaseScenarioRehearsalBatchLifecycleAuditRepositoryTest,ScenarioRehearsalBatchEvidenceIntegrityServiceTest,ScenarioRehearsalBatchEvidencePublisherTest,DatabaseScenarioRehearsalBatchEvidenceRepositoryTest,DatabaseScenarioRehearsalBatchRetentionRepositoryTest,ScenarioRehearsalBatchRetentionServiceTest,ScenarioRehearsalBatchSchedulerTest,ScenarioRehearsalBatchSchedulerPropertiesTest,ScenarioRehearsalWorkbookSeedTest,ScenarioRehearsalResultProtocolTest,ScenarioRehearsalEvidenceIntegrityServiceTest,DatabaseScenarioRehearsalEvidenceRepositoryTest,DatabaseScenarioRehearsalRunRepositoryTest,ScenarioRehearsalCommitServiceTest,DatabaseScenarioRehearsalRetentionRepositoryTest,ScenarioRehearsalRetentionServiceTest,DatabaseScenarioArtifactRepositoryTest,DatabaseCompiledScenarioRehearsalPlanRepositoryTest,ScenarioPackProtocolTest,MirrorEvidenceIntegrityServiceTest,ScenarioHandlingAssertionEvaluatorTest,MirrorRuntimeConfigurationTest,ToolStudioIntegrationServiceTest,VisualCanvasDemoScriptTest \
  test
```

提交前运行完整门禁：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

2026-07-25 本轮门禁结果：Resource Gateway `5,179` 项测试零失败、零错误、
4 项条件跳过（其余真实 Chrome DOM/工作流和可执行 Boot JAR 均通过）；Test Kit `394` 项
零失败、零错误，完成 132 个 Mirror Schema 的引用闭包与 shaded JAR 打包，公共
JavaDoc 校验通过。演练工作台另以 `-Pfrontend` 在真实 Chrome 中通过桌面、中等宽度、
移动宽度三档响应式定向验证。本轮最终源码另通过服务端 finalization health/SLO
联合 `99/99` 项与 Test Kit Schema/client `54/54` 项聚焦验证。

关键实现与协议：

- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioRehearsalCompiler.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioArtifactRegistryService.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/DatabaseScenarioRehearsalRunRepository.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/DatabaseScenarioRehearsalLifecycleAuditRepository.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioRehearsalCommitService.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/ScenarioRehearsalController.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioHandlingAssertionEvaluator.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioHandlingAssertionResult.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioRehearsalRuntimeService.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioCaseRehearsalResult.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioRehearsalResult.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioRehearsalEvidenceAttestation.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioRehearsalEvidenceBundle.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioRehearsalEvidenceIntegrityService.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/DatabaseScenarioRehearsalEvidenceRepository.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioRehearsalRetentionService.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/DatabaseScenarioRehearsalRetentionRepository.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioRehearsalWorkbookSeed.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioRehearsalBatchCompiler.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioRehearsalBatchJobPage.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/DatabaseScenarioRehearsalBatchRepository.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioRehearsalBatchEvidenceIntegrityService.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioRehearsalBatchFinalizationWorker.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioRehearsalBatchFinalizationScheduler.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioRehearsalBatchFinalizationStatus.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioRehearsalBatchFinalizationHealth.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioRehearsalBatchFinalizationSloMonitor.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/DatabaseScenarioRehearsalBatchEvidenceRepository.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioRehearsalBatchLifecycleAuditEvent.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/DatabaseScenarioRehearsalBatchLifecycleAuditRepository.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioRehearsalBatchWorker.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/ScenarioRehearsalBatchController.java`
- `resource-gateway-test-kit/src/main/java/com/leanowtech/bloge/gateway/testkit/ScenarioRehearsalRetentionVerifier.java`
- `resource-gateway-test-kit/src/main/java/com/leanowtech/bloge/gateway/testkit/ScenarioRehearsalWorkbookVerifier.java`
- `resource-gateway-test-kit/src/main/java/com/leanowtech/bloge/gateway/testkit/ScenarioRehearsalBatchEvidenceVerifier.java`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-compile-request-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/compiled-scenario-rehearsal-plan-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-handling-assertion-result-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-execution-request-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-case-rehearsal-result-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-result-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-evidence-attestation-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-evidence-bundle-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-legal-hold-command-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-purge-command-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-retention-event-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-retention-state-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-workbook-seed-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-batch-request-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-batch-manifest-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-batch-job-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-batch-job-v2.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-batch-job-page-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-batch-finalization-status-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-batch-finalization-health-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-batch-finalization-remediation-request-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-batch-finalization-remediation-receipt-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-remediation-preview-request-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-remediation-plan-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-remediation-approval-command-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-remediation-approval-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-remediation-submit-command-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-remediation-receipt-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-batch-item-page-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-batch-cancellation-request-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-batch-evidence-index-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-batch-evidence-index-v2.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-batch-evidence-attestation-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-batch-evidence-attestation-v2.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-batch-evidence-bundle-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-batch-evidence-bundle-v2.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-batch-retention-event-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-batch-retention-state-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-batch-workbook-seed-v1.schema.json`

独立 consumer 继续使用 `resource-gateway-test-kit` 的
`ScenarioPackVerifier` 验证 ScenarioPack/Case/Assertion，并使用
`ScenarioRehearsalEvidenceVerifier` 对执行聚合做 nested content address、
derived outcome/summary、key policy 与 Ed25519 独立复验。compiled plan 的独立
content address 现在会作为 workbook source closure 的一部分复验；跨语言固定
compatibility fixture 仍待补齐，未完成前不应宣称异构消费者已通过认证。

生产化之前还必须闭合跨语言固定向量、外部透明度锚点、企业 retention policy、
数据库迁移/备份恢复、多副本并发与 ANEKE consumer certification。当前
capability flag 只声明本地协议/API 能力，不声明这些环境证明已经完成。
