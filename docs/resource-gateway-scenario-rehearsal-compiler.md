# Resource Gateway 场景演练注册、编译、同步试跑与证据指南

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
| `ScenarioRehearsalResult.v1` | 可用（同步聚合） | 顺序聚合所有 case，服务端派生 PASS/FAIL/INDETERMINATE 和计数 |
| `ScenarioRehearsalEvidenceAttestation.v1` | 可用 | 在独立签名域中绑定 aggregate run、request、compiled plan、result fingerprint 和签名时间 |
| `ScenarioRehearsalEvidenceBundle.v1` | 可用（证据 API） | 对完整 payload-free result 做 Ed25519 签名、立即复验、append-only 保存和 exact runId 读取 |
| 可作为发布门禁的 Scenario evidence | 未交付 | 尚无 aggregate lease/recovery、操作审计、保留策略和 ANEKE workbook seed |

当前链路已经解决“执行前冻结什么、运行时从哪里取值、每个结果依据什么证据”
三个问题，并让同步完成的聚合成为可独立复验和重读的证据。下一条链路负责
聚合级租约、耐久进度、崩溃恢复、操作审计、保留策略和 ANEKE workbook seed；
在此之前，证据适合 test/staging 的交互式试跑、回归和排错，但不能冒充
publish-gate-ready 证据。

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
| `mirrorScenarioRehearsalEvidence` | `false` |

若前两个为 `false`，先检查 profile 是否为 `test`/`staging`、Mirror 开关、
数据库与依赖仓储是否完成装配。不要绕过 probe 直接把环境标记为可用。

## 4. 调用身份

所有场景路由都要求受验证身份和 `MIRROR_REHEARSAL` purpose。本地演示请求
使用：

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

同一 scope、kind、id、revision 的相同内容重试是幂等的；不同 fingerprint
是不可变修订冲突。API 不提供 `latest`、覆盖更新或删除语义。

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

- aggregate 尚无耐久 request lease、进度表、owner epoch 和崩溃恢复；
- 相同 request id 会派生稳定 runId；并发计算最终由 append-only 唯一性阻止覆盖，
  但不能抑制重复的 child orchestration，因此还不是完整 exactly-once 协调；
- 聚合 evidence 已独立签名、耐久保存和可读取，但尚无 operation audit、
  retention/legal-hold、WORM/transparency anchor 和 workbook seed；
- checkpoint 是同一加密数据面的 recovery fence，不是隔离 clone；全新重复执行
  需要新的隔离 Session/checkpoint；
- operator 级 scalar TestSuite input 暂不能直接作为 graph context；
- 只支持 sequential，最多 256 个 case；没有分布式 batch scheduler；
- ANEKE workbook、批量 job 和 owner UX 尚未交付。

因此 capability probe 将 `mirrorScenarioRehearsalExecution` 与
`mirrorScenarioRehearsalEvidenceApi` 报告为 `true`，但
`mirrorScenarioRehearsalEvidence` 仍为 `false`。调用方不得把“证据协议/API
已可用”误读为“已可用于发布认证”。

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
- `scenario_rehearsal_evidence`

主键覆盖完整 scope、artifact kind、id 和 revision。写入与读取都重算
canonical fingerprint，并核对数据库索引身份；checkpoint 还会重新验签。
表中没有业务 payload、原始 correlation key 或 mutable latest pointer。

每个 child Mirror run 的 request lease、terminal summary 和 signed evidence
沿用既有耐久仓储。完成的 `ScenarioRehearsalResult` 被封入独立签名 bundle，
按完整 scope + stable runId append-only 保存；读取时重算 result/bundle
fingerprint 并复验 Ed25519 signature。表只保存 `HASH_ONLY` 证据 JSON 及其索引
身份，不增设 payload、context、fixture、entity 或诊断文本列。

进程在 aggregate evidence 提交前退出时，客户端使用同一 request id 重试，
可复用已提交 child，但会重新解析计划、重新求值并重新封印 aggregate。当前没有
aggregate lease/checkpoint，所以崩溃前尚未提交的聚合进度不会恢复；这正是
`mirrorScenarioRehearsalEvidence` 仍保持 `false` 的主要原因。

本地重启后可以按 exact fingerprint 读取同一资产。数据库备份、跨区域恢复、
WORM、外部 transparency anchor 和法律保留仍属于部署认证，不由本地哈希代替。

## 12. 开发验证

先运行场景纵向聚焦测试：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ScenarioRehearsalControllerTest,ScenarioArtifactRequestDecoderTest,ScenarioArtifactRegistryServiceTest,ScenarioRehearsalIntegrationServiceTest,ScenarioRehearsalCompilerTest,ScenarioRehearsalRuntimeServiceTest,ScenarioRehearsalResultProtocolTest,ScenarioRehearsalEvidenceIntegrityServiceTest,DatabaseScenarioRehearsalEvidenceRepositoryTest,DatabaseScenarioArtifactRepositoryTest,DatabaseCompiledScenarioRehearsalPlanRepositoryTest,ScenarioPackProtocolTest,MirrorEvidenceIntegrityServiceTest,ScenarioHandlingAssertionEvaluatorTest,MirrorRuntimeConfigurationTest,ToolStudioIntegrationServiceTest \
  test
```

提交前运行完整门禁：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

2026-07-24 本轮门禁结果：Resource Gateway `5,021` 项测试零失败、零错误、
3 项条件跳过（含真实 Chrome DOM/工作流）；Test Kit `353` 项零失败、零错误，
完成 101 个 Mirror Schema 的引用闭包、shaded JAR 和零警告公共 JavaDoc。

关键实现与协议：

- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioRehearsalCompiler.java`
- `resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/integration/mirror/ScenarioArtifactRegistryService.java`
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
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-compile-request-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/compiled-scenario-rehearsal-plan-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-handling-assertion-result-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-execution-request-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-case-rehearsal-result-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-result-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-evidence-attestation-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/scenario-rehearsal-evidence-bundle-v1.schema.json`

独立 consumer 继续使用 `resource-gateway-test-kit` 的
`ScenarioPackVerifier` 验证 ScenarioPack/Case/Assertion，并使用
`ScenarioRehearsalEvidenceVerifier` 对执行聚合做 nested content address、
derived outcome/summary、key policy 与 Ed25519 独立复验。compiled plan 的独立
compatibility fixture/verifier 仍待补齐；未完成前不应把编译对象本身直接作为
发布门禁证据。

生产化之前还必须闭合 TestSuite/Fixture 的完整企业 scope、跨语言 compiled
plan verifier、外部透明度锚点、数据库迁移/备份恢复和多副本并发认证。当前
capability flag 只声明非生产编译能力，不声明这些环境证明已经完成。
