# Business Mirror Capability Proposal 作者指南

## 1. 能力边界

Capability Proposal 用于表达“业务已经把缺失能力定义到可验收程度，但真实实现尚不存在”。当前迭代提供可靠的作者事实链：

- `CapabilityProposalDraft` 的创建、保存、读取、历史和分页；
- 五段企业 Scope 隔离；
- optimistic revision、跨副本命令锁和重启后 exact replay；
- strict Schema、canonical fingerprint 和独立 Test Kit 验证；
- 固定 `SIMULATION_ONLY` binding，真实网络、外部凭据和 egress 必须为 `false`。

当前迭代**不提供** Proposal 模拟运行。能力探针中的 `businessMirrorProposalApi=true` 仅表示作者 API 可用；`businessMirrorProposalSimulation=false` 表示 `/simulate` 尚不可调用。保存成功也不表示能力已实现、通过治理或可进入生产。

## 2. 启动与检查

从仓库根目录启动：

```bash
./scripts/start-visual-canvas-demo.sh
```

检查能力边界：

```bash
curl -s http://localhost:8080/api/integration/capabilities \
  | jq '.payload.features | {
      businessMirrorProposalApi,
      businessMirrorProposalSimulation
    }'
```

预期结果：

```json
{
  "businessMirrorProposalApi": true,
  "businessMirrorProposalSimulation": false
}
```

## 3. 五分钟创建流程

固定兼容性样例包含一个完整、只读、无网络出口的取消费归因 Proposal。先从 receipt fixture 提取 revision `0` 的创建请求：

```bash
jq '.result.draft | .revision = 0' \
  docs/schemas/resource-gateway-business-mirror/cancellation-attribution-proposal-save-receipt-v1.fixture.json \
  > /tmp/cancellation-attribution-proposal.json
```

创建：

```bash
curl -sS -X POST http://localhost:8080/api/business-mirror/proposals \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: BUSINESS_MIRROR_AUTHORING' \
  -H 'Idempotency-Key: demo:proposal:create:v1' \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/cancellation-attribution-proposal.json \
  | tee /tmp/cancellation-attribution-proposal-receipt.json \
  | jq '{proposalId: .result.draft.proposalId,
         revision: .result.draft.revision,
         binding: .result.draft.simulationRuntimeBinding,
         fingerprint: .result.draftFingerprint}'
```

重复执行完全相同的命令时，响应头 `Idempotent-Replayed` 为 `true`，body 与第一次提交完全一致。相同 key 携带不同材料会返回 `409 RG.BUSINESS_MIRROR.IDEMPOTENCY_CONFLICT`。

## 4. 编辑与历史

基于服务端返回的 revision `1` 修改业务价值假设：

```bash
jq '.result.draft
    | .businessIntent.expectedValue =
      "Rehearse cancellation attribution independently before implementation"' \
  /tmp/cancellation-attribution-proposal-receipt.json \
  > /tmp/cancellation-attribution-proposal-v2.json

curl -sS -X PUT \
  'http://localhost:8080/api/business-mirror/proposals/trip-cancellation-attribution-query?expectedRevision=1' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: BUSINESS_MIRROR_AUTHORING' \
  -H 'Idempotency-Key: demo:proposal:save:v2' \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/cancellation-attribution-proposal-v2.json \
  | jq '{revision: .result.draft.revision,
         expectedValue: .result.draft.businessIntent.expectedValue}'
```

读取当前值、历史和有界目录：

```bash
curl -sS http://localhost:8080/api/business-mirror/proposals/trip-cancellation-attribution-query \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: BUSINESS_MIRROR_AUTHORING' | jq

curl -sS http://localhost:8080/api/business-mirror/proposals/trip-cancellation-attribution-query/revisions \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: BUSINESS_MIRROR_AUTHORING' \
  | jq '[.[] | {revision: .draft.revision, fingerprint: .draftFingerprint}]'

curl -sS 'http://localhost:8080/api/business-mirror/proposals?limit=25' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: BUSINESS_MIRROR_AUTHORING' \
  | jq '{items: [.items[].draft.proposalId], nextCursor}'
```

## 5. 必须保持的隔离属性

`simulationRuntimeBinding` 不是普通 RuntimeBinding，三个开关不可修改：

```json
{
  "kind": "SIMULATION_ONLY",
  "realExternalCallsAllowed": false,
  "externalCredentialsAllowed": false,
  "networkEgressAllowed": false
}
```

Java 领域对象和 JSON Schema 都会拒绝任何 `true` 值。Proposal draft 只保存 Fixture、acceptance suite 和 resolver policy 的 exact ref，不保存真实凭据或业务 payload。未匹配 Fixture 的失败关闭语义将在 Proposal simulation 工作包中进入运行根；在该工作包完成前，能力探针不会广告模拟可用。

## 6. 独立消费者验证

Test Kit 不依赖 Resource Gateway server 或 Spring Boot：

```java
JsonNode receipt = new ObjectMapper().readTree(Files.readString(Path.of(
        "docs/schemas/resource-gateway-business-mirror/" +
        "cancellation-attribution-proposal-save-receipt-v1.fixture.json")));

BusinessMirrorProtocol.requireProposalSaveReceipt(receipt);
BusinessMirrorProtocol.requireStoredProposalDraft(receipt.path("result"));
```

Verifier 会复算 draft fingerprint，并检查 strict shape、Scope/provenance、时间顺序、分页排序和 cursor 绑定。异常只返回稳定 reason code，不回显 Proposal 内容。

## 7. 常见错误

| HTTP | Code | 处理 |
|---:|---|---|
| `400` | `RG.BUSINESS_MIRROR.IDEMPOTENCY_KEY_INVALID` | 使用 1-160 位 URL-safe key |
| `403` | `RG.BUSINESS_MIRROR.PROPOSAL_SCOPE_MISMATCH` | 让 body Scope 与受信身份 Scope 完全一致 |
| `409` | `RG.BUSINESS_MIRROR.PROPOSAL_REVISION_CONFLICT` | 重新读取当前 revision，再显式合并或重试 |
| `409` | `RG.BUSINESS_MIRROR.IDEMPOTENCY_CONFLICT` | 材料变化时使用新 key；不要覆盖旧命令身份 |
| `404` | `RG.BUSINESS_MIRROR.PROPOSAL_NOT_FOUND` | 检查当前受信 Scope 和 proposal id |
| `400` | `RG.BUSINESS_MIRROR.PROPOSAL_RAW_SECRET_FORBIDDEN` | 只保存 Secret ref，不保存明文凭据 |

## 8. 生产迁移与停止

生产部署先应用：

```text
db/postgresql/V20260814_003__business_mirror_proposal_authoring.sql
```

DDL 创建 current、append-only history、command lock 和 exact receipt 四类表。`Scope + proposalId` 与 `Scope + idempotencyKey` 均进入数据库主键，不依赖查询后的应用层过滤。

停止演示服务：

```bash
./scripts/stop-visual-canvas-demo.sh
```
