# Business Mirror 实现绑定与交付接入指南

## 1. 这项能力解决什么问题

实现绑定回答的不是“这段实现跑过了吗”，而是一个更早、也更基础的问题：

> 哪一个客户自有运行端口的哪一个不可变实现代次，被授权用于实现哪一个已完成隔离模拟的 Proposal？

Resource Gateway 将以下材料冻结为一个 server-attested、content-addressed binding：

```text
exact Proposal revision + draft fingerprint
  + exact PASSED ProposalSimulationEvidence
  + exact target Capability
  + candidate Contract fingerprint
  + runtime port generation
  + implementation version + fingerprint
  + owner + region + safety attestation + expiry
    -> CapabilityImplementationBinding
    -> detached evidence attestation
    -> durable exact replay
```

绑定成功只形成 `IMPLEMENTED` 阶段的前置事实，不等于实现通过验收。只有 BM-008 后续的同套件 Conformance 才能形成 `CONFORMANT` 证据。

## 2. 三个必须分开的 readiness

检查能力探针：

```bash
curl -s http://localhost:8080/api/integration/capabilities \
  | jq '.payload | {
      objects: {
        request: .supportedObjects.capabilityImplementationBindingRequest,
        binding: .supportedObjects.capabilityImplementationBinding,
        stored: .supportedObjects.storedCapabilityImplementationBinding
      },
      features: {
        bindingApi: .features.businessMirrorImplementationBindingApi,
        runtimeReady: .features.businessMirrorImplementationRuntimeReady
      }
    }'
```

| 信号 | 含义 | 不代表什么 |
|---|---|---|
| 三类 `supportedObjects` 存在 | 当前版本能协商和校验绑定协议 | API 已装配 |
| `businessMirrorImplementationBindingApi=true` | 绑定 repository、签名器和认证 API 已装配 | 客户实现端口可调用 |
| `businessMirrorImplementationRuntimeReady=true` | 部署安装了客户自有 runtime adapter | 任意 Proposal 已绑定或已通过 Conformance |

默认演示部署使用 fail-closed `CapabilityImplementationRuntimePort.unavailable()`，因此最后一项为 `false`。演示不会伪造客户真实实现。无需运行服务即可用 Test Kit 校验固定绑定样例：

```text
docs/schemas/resource-gateway-business-mirror/
  refund-implementation-binding-stage1-v1.fixture.json
```

## 3. 客户实现适配器

部署方实现并注册一个 `CapabilityImplementationRuntimePort` Bean：

```java
public final class RefundImplementationPort
        implements CapabilityImplementationRuntimePort {
    @Override
    public Optional<Descriptor> describe(Scope scope, String runtimePortRef) {
        // Descriptor 必须来自部署或制品权威，不能从绑定请求原样回显。
        return authority.findExact(scope, runtimePortRef);
    }

    @Override
    public Object invoke(CapabilityImplementationBinding binding, Invocation invocation)
            throws Exception {
        // 输入/输出只在隔离运行内存中流动，不由该 Port 或 Binding repository 留存。
        return isolatedRuntime.invoke(binding.implementationFingerprint(), invocation.input());
    }
}
```

Runtime owner 必须独立维护：

1. `runtimePortFingerprint`：端口配置、路由和隔离策略的不可变代次。
2. `implementationFingerprint`：制品、镜像或可执行代码的内容地址。
3. `candidateContractFingerprint`：实现声明所对应的 Proposal Contract。
4. `allowedRegions`、`readOnly`、`stateless`、`attestedAt` 和 `expiresAt`。

V1 只允许当前 Scope region 内、只读、无状态的实现。Descriptor 漂移、过期、未安装、Contract 不一致或 signer 不可用均失败关闭。Runtime adapter 不接收 Secret 值；凭据解析、网络策略和进程隔离属于部署数据面，不能编码进 binding payload。

## 4. 创建不可变绑定

前置条件：Proposal exact revision 无 readiness blocker，且它的权威模拟状态为 `COMPLETED + PASSED`。请求必须重复提交同一轮评审看到的所有 exact fingerprints：

```json
{
  "schemaVersion": "resourceGateway.capabilityImplementationBindingRequest.v1",
  "expectedProposalDraftFingerprint": "sha256:<proposal-draft-64-hex>",
  "simulationEvidenceRef": {
    "kind": "PROPOSAL_SIMULATION_EVIDENCE",
    "id": "simulation-1",
    "revision": 1,
    "fingerprint": "sha256:<simulation-evidence-64-hex>"
  },
  "targetCapabilityRef": {
    "kind": "CAPABILITY",
    "id": "refund-lookup",
    "revision": 1,
    "fingerprint": "sha256:<target-capability-64-hex>"
  },
  "runtimePortRef": "runtime:refund:v1",
  "expectedRuntimePortFingerprint": "sha256:<runtime-port-64-hex>",
  "expectedImplementationVersion": "1.0.0",
  "expectedImplementationFingerprint": "sha256:<implementation-64-hex>"
}
```

```bash
curl -sS -X POST \
  'http://localhost:8080/api/business-mirror/proposals/refund-proposal/revisions/1/implementation-bindings' \
  -H 'Authorization: Bearer <workload-token>' \
  -H 'X-Purpose: CAPABILITY_IMPLEMENTATION' \
  -H 'Idempotency-Key: refund-implementation-binding-1' \
  -H 'Content-Type: application/json' \
  --data-binary @implementation-binding-request.json \
  | tee /tmp/refund-implementation-binding.json \
  | jq '{binding: .binding | {
      bindingId, fingerprint, proposalDraftRef, simulationEvidenceRef,
      targetCapabilityRef, runtimePortRef, implementationVersion,
      implementationFingerprint, runtimeOwner, allowedRegions, expiresAt
    }, attestation}'
```

服务端会重新读取 Proposal 和 simulation evidence，重新计算 candidate Contract fingerprint，再从 runtime authority 读取 Descriptor。任何一项与请求不一致都返回冲突，调用方不能用请求覆盖服务端事实。

同一完整 Scope 下，`bindingId` 是幂等命令身份。完全相同的命令返回原绑定；相同 id 携带不同材料失败，不会创建第二个“看起来相同”的实现事实。

## 5. 读取和独立验证

```bash
curl -sS \
  'http://localhost:8080/api/business-mirror/implementation-bindings/refund-implementation-binding-1' \
  -H 'Authorization: Bearer <workload-token>' \
  -H 'X-Purpose: CAPABILITY_IMPLEMENTATION' \
  | jq
```

读取服务会复算 binding fingerprint，并通过部署 signer 验证 detached attestation。允许用途固定为 `CAPABILITY_IMPLEMENTATION`、`CAPABILITY_CONFORMANCE` 或 `GOVERNANCE_EVIDENCE_INGESTION`；调用方不能通过自报任意 purpose 绕过应用服务授权。

独立 Test Kit 验证：

```java
JsonNode stored = objectMapper.readTree(input);
BusinessMirrorProtocol.requireStoredImplementationBinding(stored);
```

离线 verifier 检查 strict Schema、canonical fingerprint、region、只读/无状态、时间顺序和 attestation material closure。它不会把示例签名当成可信签名；部署方仍必须使用受信 key-set verifier 验证 signer 身份、撤销和签名时点。

## 6. 持久化、升级与故障语义

生产部署应用：

```text
db/postgresql/V20260814_005__business_mirror_implementation_binding.sql
```

表主键和查询条件包含五段企业 Scope。Binding 为 append-only revision `1` 事实；重新实现必须创建新 binding id 和新的 implementation fingerprint，不能原地修改。数据库 JSON 与索引列不一致、签名不合法、实现或 Proposal 过期时读取失败关闭。

常见失败：

| Code | 含义 | 处理 |
|---|---|---|
| `RG.BUSINESS_MIRROR.IMPLEMENTATION_SIMULATION_REQUIRED` | exact Proposal 尚无已完成模拟 | 先完成 BM-007 simulation |
| `RG.BUSINESS_MIRROR.IMPLEMENTATION_SIMULATION_STALE` | 模拟失败或 target/ref 漂移 | 使用 reviewed exact evidence，必要时创建新 Proposal revision |
| `RG.BUSINESS_MIRROR.IMPLEMENTATION_RUNTIME_UNAVAILABLE` | 客户 runtime adapter 未安装或端口不存在 | 安装 adapter，并检查 capability probe |
| `RG.BUSINESS_MIRROR.IMPLEMENTATION_RUNTIME_DRIFT` | 端口、实现、Contract、安全或 region 信息不一致 | 重新读取 runtime authority，禁止放宽请求绕过 |
| `RG.BUSINESS_MIRROR.IMPLEMENTATION_BINDING_SIGNER_UNAVAILABLE` | 无法形成可验证服务端事实 | 恢复 KMS/HSM signer 后重试 exact command |
| `RG.BUSINESS_MIRROR.IMPLEMENTATION_BINDING_FORBIDDEN` | 环境或 purpose 不被允许 | 仅在授权 test/staging workload 中调用 |

## 7. 当前边界与下一阶段

本阶段已经证明“实现身份与模拟基线精确绑定”，尚未证明：

- 实现真的被调用；
- 实现使用与模拟相同的 acceptance suite；
- 每个 Case 的 assertion、错误、边和调用次数一致；
- 实现与依赖 Fixture 的职责没有串线；
- 绑定在执行期间没有过期或漂移。

BM-008 后续 Conformance 会复用原 Simulation 的 Suite 与 Fixture，只将 Proposal target invocation site 受控反转到该 binding；其他依赖继续 Fixture-only。Conformance report 会将模拟 Case 与实现 Case 一一配对，形成 payload-free、签名、可持久回放的独立证据，并且只有该证据可以推动 `CONFORMANT`。
