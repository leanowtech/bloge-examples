# Business Mirror Capability Proposal 模拟运行指南

## 1. 这项能力解决什么问题

Proposal simulation 用于验证“业务人员定义的候选能力是否足以让一张既有 Graph 在隔离环境中完成业务验收”，但不要求候选能力已有生产实现。

一次运行冻结并关联以下 exact artifacts：

```text
CapabilityProposalDraft revision
  + compiled DomainCapabilityPackageSnapshot
  + built-in GraphDraft and base CapabilityClosure
  + target external CapabilitySnapshot
  + TestSuite revisions
  + FixtureBundle revisions
    -> temporary SIMULATION_ONLY CapabilitySnapshot
    -> resealed simulation CapabilityClosure
    -> one MirrorPlan and MirrorRun per acceptance case
    -> signed, payload-free ProposalSimulationEvidence
    -> evidenceState = SIMULATED ProposalSnapshot
```

`SIMULATED` 只表示候选 Contract 与 Fixture 在本次精确上下文中跑过。它不表示能力已经实现，不表示生产行为一致，也不能作为 `IMPLEMENTED`、`CONFORMANT`、`CALIBRATED` 或发布门禁通过的证据。

## 2. 启动与能力探针

从仓库根目录启动测试态服务：

```bash
./scripts/start-visual-canvas-demo.sh
```

确认运行面真实装配：

```bash
curl -s http://localhost:8080/api/integration/capabilities \
  | jq '{enabled: .payload.features.businessMirrorProposalSimulation,
         objects: {
           request: .payload.supportedObjects.capabilityProposalSimulationRequest,
           evidence: .payload.supportedObjects.capabilityProposalSimulationEvidence,
           stored: .payload.supportedObjects.storedCapabilityProposalSimulation
         },
         endpoints: [.payload.endpoints[]
           | select(.path | contains("/simulations"))]}'
```

只有 `test` 或 `staging` profile、`gateway.testing.mirror.enabled=true`、Mirror runtime、Fixture/TestSuite repositories、Package compiler Authority 和 evidence signer 均装配时，探针才返回 `businessMirrorProposalSimulation=true` 并广告两个端点。生产 profile 物理不装配 Controller。

## 3. 运行前置条件

运行命令不接受 `latest` 或模糊名称。调用方必须先准备：

| Artifact | 必须满足的条件 |
|---|---|
| Proposal | exact revision 与 draft fingerprint；未过期；无 readiness blocker |
| Package | exact compiled snapshot；dependency manifest 包含 Graph；closure fingerprint 与 Graph Authority 一致 |
| Graph | `GRAPH_DRAFT` id 使用 `built-in:{graphName}`；运行时 Graph fingerprint 与 TestSuite/Fixture 一致 |
| Target | base closure 中一个 `EXTERNAL` `CAPABILITY` exact ref；Scope 与 Proposal 完全一致 |
| TestSuite | Proposal 直接声明的 `TEST_SUITE` exact refs；V1 不展开 `SCENARIO_PACK` |
| Fixture | 每个 Case 的 exact Fixture 必须在 Proposal fixture pack 内；目标 Graph fingerprint 一致 |
| Contract | 只读、无状态、无 Secret，且允许当前 Region |

Fixture 中出现 `REAL`、`SPY`、`STREAM`、`FALLBACK_TO_REAL` 或 `ALLOW_REAL` 时，服务返回 `RG.PROPOSAL.REAL_EXECUTION_FORBIDDEN`。未匹配调用由既有 Mirror resolver 失败关闭，不会回退到真实依赖。

## 4. 发起一次模拟

先从 Proposal exact revision 读取 `draftFingerprint`。`packageRef`、`graphRef` 和 `targetCapabilityRef` 必须来自同一轮评审的 Package/Graph/Capability closure：

```json
{
  "schemaVersion": "resourceGateway.capabilityProposalSimulationRequest.v1",
  "expectedProposalDraftFingerprint": "sha256:<64-hex>",
  "packageRef": {
    "kind": "DOMAIN_CAPABILITY_PACKAGE",
    "id": "refund-package",
    "revision": 1,
    "fingerprint": "sha256:<64-hex>"
  },
  "graphRef": {
    "kind": "GRAPH_DRAFT",
    "id": "built-in:refundGraph",
    "revision": 1,
    "fingerprint": "sha256:<64-hex>"
  },
  "targetCapabilityRef": {
    "kind": "CAPABILITY",
    "id": "operator:refundLookup",
    "revision": 1,
    "fingerprint": "sha256:<64-hex>"
  }
}
```

调用：

```bash
curl -sS -X POST \
  'http://localhost:8080/api/business-mirror/proposals/refund-proposal/revisions/1/simulations' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: MIRROR_REHEARSAL' \
  -H 'Idempotency-Key: refund-proposal:r1:acceptance:v1' \
  -H 'Content-Type: application/json' \
  --data-binary @proposal-simulation-request.json \
  | tee /tmp/proposal-simulation-result.json \
  | jq '{simulationId: .evidence.simulationId,
         status: .evidence.status,
         evidenceState: .proposalSnapshot.evidenceState,
         cases: [.evidence.cases[] | {
           caseId, runStatus, resolverSources, matchedRuleRefs,
           proposalCallCount, mirrorEvidenceBundleRef
         }],
         limitations: .evidence.limitations,
         uncertainties: .evidence.uncertainties}'
```

同一 Proposal revision 只接受一个确定性模拟命令。相同 `Idempotency-Key` 与相同材料在响应丢失或服务重启后返回原结果；同一 revision 或 key 绑定不同材料时返回 `409`，不会覆盖历史证据。

## 5. 读取与核验结果

读取已完成结果：

```bash
curl -sS \
  'http://localhost:8080/api/business-mirror/proposals/refund-proposal/revisions/1/simulations/refund-proposal:r1:acceptance:v1' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: GOVERNANCE_EVIDENCE_INGESTION' \
  | jq
```

结果分三层：

| 层级 | 内容 | 用途 |
|---|---|---|
| Case | TestSuite、Fixture、MirrorPlan、MirrorEvidenceBundle exact refs，run status、resolver source、rule refs、候选能力调用次数 | 定位哪条业务验收路径为何通过或失败 |
| Aggregate | Proposal/Package/Graph/base closure/simulated closure、全部 Case、限制和不确定性 | 审计本次 Proposal 模拟的完整依赖闭包 |
| Proposal Snapshot | `evidenceState=SIMULATED` 与 aggregate evidence ref | 形成不可变业务能力演进事实，但不越级成为实现证据 |

Aggregate 不存储 Case input、Graph output、node input/output 或 Fixture return payload。业务内容仍由原 Test/Mirror evidence retention policy 管理；Aggregate 只保留可关联的内容指纹和 artifact refs。

## 6. 独立 Test Kit 验证

仓库提供服务端模型生成的固定结果：

```text
docs/schemas/resource-gateway-business-mirror/
  refund-proposal-simulation-stage1-v1.fixture.json
```

离线验证：

```java
JsonNode stored = new ObjectMapper().readTree(Files.readString(Path.of(
        "docs/schemas/resource-gateway-business-mirror/" +
        "refund-proposal-simulation-stage1-v1.fixture.json")));

BusinessMirrorProtocol.requireProposalSimulationEvidence(stored.path("evidence"));
BusinessMirrorProtocol.requireStoredProposalSimulation(stored);
```

Test Kit 会复算 aggregate 与 Proposal Snapshot 指纹，检查 Case/suite 排序、套件覆盖、候选能力调用次数、时间、attestation material binding 和 `SIMULATED + implementationBindingRef=null`。部署签名的真实性仍由部署 evidence trust/key-set verifier 负责，不能仅因结构校验通过就信任签名来源。

## 7. 并发、恢复与运维

数据库以完整企业 Scope、Proposal id 和 revision 作为运行主键，以 Scope 与 simulation id 建唯一约束。运行实例通过数据库时钟、30 分钟 lease、epoch fencing 和 Case 间续租协调：

- 活跃运行返回 `409 RG.BUSINESS_MIRROR.PROPOSAL_SIMULATION_IN_PROGRESS` 与 `retryAfterSeconds`；
- worker 失败时释放 lease，后继副本以新 epoch 恢复；
- 旧 worker 的 complete/renew 被 fencing token 拒绝；
- 证据签名失败、Proposal/Package/Graph/Fixture/Suite 漂移或到期时失败关闭；
- 成功结果持久化，重启后 exact replay 不重复执行业务 Case。

生产部署前应用：

```text
db/postgresql/V20260814_004__business_mirror_proposal_simulation.sql
```

停止演示服务：

```bash
./scripts/stop-visual-canvas-demo.sh
```

## 8. V1 明确限制

1. 只支持内置 Graph Authority 与直接 `TEST_SUITE` refs，不展开 Scenario Pack。
2. 只支持只读、无状态、无 Secret 的候选 Contract 和闭包；有状态及写能力留给后续隔离认证。
3. 一个 exact Proposal revision 只产生一个权威模拟结果；需要修改目标或套件时先保存新 Proposal revision。
4. Aggregate 只证明隔离模拟表现，不证明生产实现、生产连接、真实 Outcome 或组织发布审批。
5. 默认演示服务提供协议样例与离线验证材料，但不会伪造客户自有 Package、Graph closure、Fixture 和 TestSuite Authority；在线试跑必须先导入并编译一组彼此 exact-matched 的业务资产。
