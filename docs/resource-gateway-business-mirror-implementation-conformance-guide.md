# Business Mirror 实现一致性验证与接入指南

## 1. 这项能力证明什么

实现一致性验证回答一个受限但可审计的问题：

> 同一份 Proposal 业务验收套件，在保持 Graph、输入、Fixture、非目标依赖与断言不变的条件下，将候选能力从模拟值替换为某个精确实现后，是否仍呈现相同的可观察业务行为？

完整链路如下：

```text
PASSED ProposalSimulationEvidence
  + exact CapabilityImplementationBinding
  + original TestSuite / Case / Fixture / MirrorPlan / baseline evidence
    -> 仅将 Proposal target invocation sites 切换为 REAL
    -> 其余外部依赖继续使用 frozen Fixture / replay
    -> 运行相同输入与断言
    -> 比较状态、调用次数、调用点和可观察行为指纹
    -> signed CapabilityImplementationConformanceReport
    -> IMPLEMENTED 或 CONFORMANT ProposalSnapshot
```

`PASSED` 证明已声明的断言和可观察节点/边行为一致，不证明未声明语义、生产结果保真度、写副作用或长期稳定性。生产 Outcome 校准属于后续阶段。

## 2. 前置条件与能力探针

执行前必须具备：

1. exact Proposal revision 仍未过期且无 readiness blocker；
2. Proposal 存在 `COMPLETED + PASSED` 的 exact simulation evidence；
3. acceptance Suite、Case、Fixture、MirrorPlan 和 baseline evidence 仍可按 revision/fingerprint 读取；
4. implementation binding 与 Proposal、simulation、target、Contract 和 runtime generation 完全一致；
5. 部署已安装 `CapabilityImplementationRuntimePort`，且 Descriptor 未漂移、未过期；
6. 环境为授权的 test/staging，purpose 为 `CAPABILITY_CONFORMANCE`。

检查部署能力：

```bash
curl -s http://localhost:8080/api/integration/capabilities \
  | jq '.payload | {
      objects: {
        request: .supportedObjects.capabilityImplementationConformanceRequest,
        evidence: .supportedObjects.capabilityImplementationTestEvidence,
        report: .supportedObjects.capabilityImplementationConformanceReport,
        stored: .supportedObjects.storedCapabilityImplementationConformance
      },
      features: {
        api: .features.businessMirrorImplementationConformanceApi,
        runtimeReady: .features.businessMirrorImplementationRuntimeReady
      }
    }'
```

| 信号 | 含义 |
|---|---|
| 四类 `supportedObjects` 存在 | 当前版本可协商和离线校验 v1 协议 |
| `businessMirrorImplementationConformanceApi=true` | repository、应用服务、认证 Controller 与 signer 已装配 |
| `businessMirrorImplementationRuntimeReady=true` | 部署安装了可用的客户实现 adapter |

默认演示环境的 runtime 为 fail-closed unavailable，因此可以查看协议和离线样例，不能伪造“真实实现已通过”。需要在线试跑时，应先按[实现绑定与交付接入指南](resource-gateway-business-mirror-implementation-binding-guide.md)安装客户 adapter。

## 3. 目标隔离如何实现

`CapabilityImplementationConformancePlanCompiler` 从已接受的 `CompiledMirrorPlan` 派生新计划，不重新解析可变 `latest`：

1. 根据 simulation 中的 temporary Capability 找到全部 target invocation sites；
2. 只将绑定这些 target sites 的规则改为 `REAL`；
3. 将 frozen invocation inventory 中的目标算子替换为 `ConformanceOperatorRegistry`；
4. 保留其他节点的 fixture、replay、corpus、clock、random 和执行服务；
5. 重新生成 content-addressed conformance plan fingerprint。

以下情况在执行前失败关闭：

- target 没有 invocation site 或没有唯一控制规则；
- 一条规则同时控制 target 与非 target；
- target operator ref 被非 target site 复用；
- target 是 embedded operator，无法由 runtime port 接管；
- 非 target 存在 `REAL`、`SPY`、真实 fallback 或未解析控制；
- runtime coordinate、binding、Descriptor、Contract、Scope、Region 或有效期漂移。

该边界防止“为了测试新实现，顺便调用了真实上游”以及 fixture 与实现职责串线。

## 4. 发起一致性验证

请求只提交 exact 引用，不携带业务 payload：

```json
{
  "schemaVersion": "resourceGateway.capabilityImplementationConformanceRequest.v1",
  "implementationBindingRef": {
    "kind": "PROPOSAL_IMPLEMENTATION_BINDING",
    "id": "refund-implementation-binding-1",
    "revision": 1,
    "fingerprint": "sha256:<binding-64-hex>"
  },
  "simulationEvidenceRef": {
    "kind": "PROPOSAL_SIMULATION_EVIDENCE",
    "id": "simulation-1",
    "revision": 1,
    "fingerprint": "sha256:<simulation-64-hex>"
  },
  "expectedProposalDraftFingerprint": "sha256:<proposal-draft-64-hex>"
}
```

```bash
curl -sS -X POST \
  'http://localhost:8080/api/business-mirror/proposals/refund-proposal/revisions/1/implementation-conformances' \
  -H 'Authorization: Bearer <workload-token>' \
  -H 'X-Purpose: CAPABILITY_CONFORMANCE' \
  -H 'Idempotency-Key: refund-conformance-1' \
  -H 'Content-Type: application/json' \
  --data-binary @implementation-conformance-request.json \
  | tee /tmp/refund-implementation-conformance.json \
  | jq '{status: .report.status, cases: [.report.cases[] | {
      caseId, comparison, baselineTargetCallCount,
      implementationTargetCallCount, targetInvocationSiteIds, mismatchReasons
    }], proposalState: .proposalSnapshot.evidenceState}'
```

同一 Scope、binding revision 和完全相同命令会返回持久化的 exact result，不再次调用客户实现。相同 `Idempotency-Key` 或 binding revision 携带不同材料会冲突。

## 5. 如何解读报告

每个 `CaseComparison` 同时保留两类指纹：

| 字段 | 用途 |
|---|---|
| `baselineSemanticResultFingerprint` | 原 Mirror 测试内核的完整语义身份，包含原 purpose、plan、fixture consumption 与 fidelity |
| `implementationEvidence.semanticResultFingerprint` | 实现运行的完整测试内核语义身份 |
| `baselineBehaviorFingerprint` | 从 baseline payload-free node/edge trace 提取的跨运行可比较行为 |
| `implementationBehaviorFingerprint` | 从真实实现 node/edge trace提取的同构行为 |

完整语义指纹不能直接判等，因为 `MIRROR_REHEARSAL/FIXTURE` 与 `CAPABILITY_CONFORMANCE/REAL` 的执行方式必然不同。行为投影保留节点/边坐标、输入输出指纹、状态、错误和 attempt，排除 plan、purpose、耗时和 fidelity，并将预期的 `MOCKED` 状态归一为 `SUCCESS`。业务值改变仍会改变行为指纹。

Case 只有同时满足以下条件才为 `MATCH`：

1. baseline 与 implementation 都通过；
2. 行为指纹相同；
3. target 调用次数相同且大于零；
4. target invocation site 集合相同；
5. implementation 的全部声明断言通过。

全部 Case 为 `MATCH` 时 report 为 `PASSED`，Proposal snapshot 进入 `CONFORMANT`。任一 Case mismatch 时 report 为 `FAILED`，snapshot 保持 `IMPLEMENTED`，但失败证据仍会持久化和签名，供排障与治理消费。

## 6. 读取、离线验证与固定样例

按 binding exact revision 读取：

```bash
curl -sS \
  'http://localhost:8080/api/business-mirror/implementation-bindings/refund-implementation-binding-1/revisions/1/conformance' \
  -H 'Authorization: Bearer <workload-token>' \
  -H 'X-Purpose: GOVERNANCE_EVIDENCE_INGESTION' \
  | jq
```

无需服务器即可验证完整固定样例：

```java
JsonNode stored = objectMapper.readTree(input);
BusinessMirrorProtocol.requireStoredImplementationConformance(stored);
```

样例位置：

```text
docs/schemas/resource-gateway-business-mirror/
  refund-implementation-conformance-stage1-v1.fixture.json
```

TestKit 会重算 implementation evidence、report 和 Proposal snapshot 三层 content address，并验证 case 顺序/唯一性、Suite 覆盖、状态派生、调用次数、行为指纹、report 引用和 detached attestation material closure。示例签名只用于协议兼容性，不构成部署信任；生产消费者仍需验证 key-set、撤销和签名时点。

## 7. 持久化、并发与恢复

生产 PostgreSQL 应用：

```text
db/postgresql/V20260814_006__business_mirror_implementation_conformance.sql
```

Repository 使用五段 Scope、binding id/revision 作为主键，conformance id 为 Scope 内唯一键。数据库时钟、lease owner、单调 epoch 与 expiry 控制跨副本执行权；旧 lease 不能 renew、complete 或 release 新 epoch。完成结果为不可变 JSON，并在读取时重新验证所有指纹和签名。

| 情况 | 结果 | 恢复方式 |
|---|---|---|
| 相同命令正在执行 | retryable conflict，返回 retry 时间 | 使用相同命令退避重试 |
| worker 失联且 lease 过期 | 新 worker 以更高 epoch 接管 | 保持相同 idempotency key |
| runtime 或 binding 漂移 | `RUNTIME_DRIFT` / `BINDING_STALE` | 创建新 binding，禁止覆盖旧证据 |
| Suite、Fixture、baseline evidence 漂移 | `*_STALE` / `BASELINE_INVALID` | 恢复 exact artifact 或创建新 Proposal revision |
| 计划无法隔离 target | `PLAN_REJECTED` | 拆分共享规则/operator ref，消除真实 fallback |
| 行为或断言不一致 | report `FAILED` | 根据 `mismatchReasons` 修复实现或显式更新 Contract/Suite |
| signer 不可用 | 不提交报告 | 恢复 KMS/HSM signer 后重试 exact command |

## 8. 安全与当前边界

1. HTTP 入口仅在非 production 的 test/staging profile 且 Mirror testing enabled 时存在。
2. 应用服务固定校验 purpose；调用方不能通过自报用途获得生产执行权。
3. Binding、repository、report 和 TestKit 不保存 request/response payload、Secret 或凭据。
4. Runtime adapter 每次调用前重新读取 exact Descriptor；过期或漂移立即失败。
5. V1 只支持只读、无状态候选实现，不支持写副作用 Conformance。
6. `CONFORMANT` 不是 `CALIBRATED`。生产 Shadow、Outcome 和长期 Fidelity 仍需独立证据。

验证命令：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
mvn -f resource-gateway-test-kit/pom.xml clean verify
```
