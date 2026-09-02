# Resource Gateway curl 演示剧本：客户留存优惠 Tool

本文提供一条可直接执行的 HTTP 操作链，验证以下能力：

1. 定义三个外部 API Resource；
2. 从 API example 原子生成每个 Resource 的私有 Default Fixture；
3. 由调用方指定精确 Fixture revision 和 Case，模拟单个 API；
4. 将三个 API Resource 编排成可发布的客户留存优惠 Tool；
5. 对 DAG 中每个节点指定不同 Fixture Case，执行完整数据映射；
6. 为发布后的 Tool 保存 whole-Tool Fixture，并把整个 Tool 作为一个 Mock Subject 执行。

可执行脚本是 [`scripts/curl-caller-directed-fixture-demo.sh`](../scripts/curl-caller-directed-fixture-demo.sh)。
本文解释脚本的业务意义、协议边界和结果判读。脚本内保留完整请求 JSON，审阅或排障时以脚本为准。

## 1. 业务场景

一家订阅制企业希望为高流失风险客户生成续费优惠。Tool 使用三个只读 API：

| 顺序 | API Resource | 输入 | 输出 | 业务作用 |
| --- | --- | --- | --- | --- |
| 1 | Customer Profile | `customerId` | 客群、流失风险 | 判断是否需要挽留 |
| 2 | Account Summary | `customerId` | 月消费额、币种 | 判断客户价值 |
| 3 | Retention Offer | 前两步输出 | 优惠码、折扣、文案 | 生成最终续费优惠 |

DAG 的前两个节点都读取 Tool 输入中的 `customerId`；第三个节点接收前两个节点的完整输出。演示结果是为客户
`C-1001` 生成 `SAVE20` 优惠。

演示中的 Base URL 使用保留域名 `*.example.test`。所有模拟命令都设置
`externalReads=DENY`、`externalWrites=DENY`，并由 `RETURN` Fixture 提供输出，因此不会发起真实网络请求。

## 2. 前置条件

需要 Java 25、Maven、`curl` 和 `jq`。

仓库启动器已默认启用 API Resource 与 Reusable Flow authoring。演示身份还必须获准使用
`API_RESOURCE_AUTHORING` purpose：

```bash
RG_INTEGRATION_ALLOWED_PURPOSES=API_RESOURCE_AUTHORING \
  scripts/example-services.sh start resource-gateway
```

默认地址为 `http://localhost:8081`，默认演示 Bearer Token 为
`bloge-aneke-demo-token`。先检查两个能力是否可用：

```bash
curl --fail-with-body --silent --show-error \
  http://localhost:8081/api/authoring/availability | jq .
```

预期：

```json
{
  "schemaVersion": "bloge.authoringAvailability.v1",
  "apiResource": true,
  "reusableFlow": true
}
```

## 3. 一键执行完整剧本

每次使用新的 `RG_DEMO_ID`。创建接口采用 `If-None-Match: *`，重复使用同一 ID 而改变请求内容会按设计返回
CAS 或幂等冲突。

```bash
RG_DEMO_ID=retention-review-01 \
RG_KEEP_DEMO_FILES=true \
  scripts/curl-caller-directed-fixture-demo.sh
```

可选参数：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `RG_BASE_URL` | `http://localhost:8081` | Resource Gateway 地址 |
| `RG_TOKEN` | `bloge-aneke-demo-token` | 演示 Bearer Token |
| `RG_PURPOSE` | `API_RESOURCE_AUTHORING` | 受信操作 purpose |
| `RG_DEMO_ID` | 当前时间生成 | 本轮对象名前缀，建议显式填写 |
| `RG_KEEP_DEMO_FILES` | `false` | 为 `true` 时保留所有请求、响应和响应头 |

成功时脚本最后输出三个不可变 Run ID 和一个已发布 Tool coordinate：

```text
Demo completed successfully.
  API Resource simulation run: sim-...
  DAG simulation run:          sim-...
  Whole-Tool simulation run:   sim-...
  Published Tool:              publication-...@1
  No real external request was authorized or attempted.
```

## 4. 分步协议

### 4.1 定义 API Resource，并从 example 创建 Default Fixture

三个 Resource 都通过以下端点创建：

```http
PUT /api/authoring/resources/{resourceId}
Authorization: Bearer ...
X-Purpose: API_RESOURCE_AUTHORING
If-None-Match: *
Idempotency-Key: ...
Content-Type: application/json
```

Customer Profile 的核心命令如下。脚本还定义 Account Summary 和 Retention Offer 两个同结构命令。

```json
{
  "schemaVersion": "bloge.apiResourceSaveCommand.v1",
  "connection": {
    "mode": "CREATE",
    "command": {
      "schemaVersion": "bloge.apiConnectionCommand.v1",
      "displayName": "Customer CRM API",
      "baseUrl": "https://crm.example.test",
      "auth": { "kind": "NONE" },
      "defaults": {
        "timeoutMs": 3000,
        "headers": { "Accept": "application/json" }
      }
    }
  },
  "resource": {
    "displayName": "Get customer retention profile",
    "operation": {
      "method": "GET",
      "path": "/customers/{customerId}/retention-profile",
      "bindings": [
        {
          "from": "$.customerId",
          "to": { "location": "PATH", "name": "customerId" }
        }
      ]
    },
    "contract": {
      "input": {
        "format": "json-schema",
        "version": "2020-12",
        "schema": {
          "type": "object",
          "properties": { "customerId": { "type": "string" } },
          "required": ["customerId"],
          "additionalProperties": false
        }
      },
      "output": {
        "format": "json-schema",
        "version": "2020-12",
        "schema": {
          "type": "object",
          "properties": {
            "customerId": { "type": "string" },
            "segment": { "type": "string" },
            "churnRisk": { "type": "string" }
          },
          "required": ["customerId", "segment", "churnRisk"],
          "additionalProperties": false
        }
      }
    },
    "response": { "success": { "kind": "HTTP_STATUS", "codes": [200] } },
    "effect": { "kind": "READ_ONLY" },
    "examples": [
      {
        "name": "vip-risk",
        "input": { "customerId": "C-1001" },
        "output": {
          "customerId": "C-1001",
          "segment": "VIP",
          "churnRisk": "HIGH"
        }
      }
    ]
  },
  "defaultFixture": {
    "kind": "FROM_EXAMPLES",
    "displayName": "VIP churn-risk customer",
    "exampleNames": ["vip-risk"]
  }
}
```

`FROM_EXAMPLES` 不是客户端上传一份脱离 Resource 的 Mock。后端在同一保存协议内验证 example、提交精确
Resource revision，并生成与该 revision 绑定的私有 Fixture Set。响应中的以下字段是后续调用的权威坐标：

```jq
.resource.resourceId
.resource.revision
.resource.fingerprint
.defaultFixture.fixtureSetId
.defaultFixture.revision
.defaultFixture.fingerprint
.defaultFixture.cases[0].caseId
```

不要根据名称猜测 revision 或 fingerprint，也不要从 mutable head 推导历史命令。

### 4.2 调用方指定 API Fixture Case 并模拟

单资源运行使用 `bloge.simulationCommand.v2`。`subject`、Fixture Set 与 Case 均来自上一步 receipt：

```json
{
  "schemaVersion": "bloge.simulationCommand.v2",
  "subject": {
    "kind": "API_RESOURCE",
    "resourceId": "retention-review-01.customer-profile",
    "revision": 1,
    "fingerprint": "sha256:..."
  },
  "input": {
    "kind": "INLINE",
    "value": { "customerId": "C-1001" }
  },
  "fixturePlan": {
    "kind": "CASE_CONTROLS",
    "fixtureSet": {
      "fixtureSetId": "retention-review-01.customer-profile:r1",
      "revision": 1,
      "fingerprint": "sha256:..."
    },
    "caseId": "vip-risk",
    "unmatched": "BLOCK"
  },
  "executionPolicy": {
    "externalReads": { "kind": "DENY" },
    "externalWrites": { "kind": "DENY" }
  }
}
```

调用方式：

```bash
curl --fail-with-body --silent --show-error \
  --request POST http://localhost:8081/api/authoring/simulations \
  --header 'Authorization: Bearer bloge-aneke-demo-token' \
  --header 'X-Purpose: API_RESOURCE_AUTHORING' \
  --header 'Content-Type: application/json' \
  --header 'Idempotency-Key: retention-review-01:simulate:profile' \
  --data-binary @profile-simulation-command.json | jq .
```

必须看到：

- `status=SUCCEEDED`；
- `invocations[0].execution=MOCKED`；
- `matchedBy=CASE_CONTROLS`；
- `fixtureCase` 指向上一步的精确 Fixture revision；
- `egress.decision=FIXTURE` 且 `attempted=false`；
- `contract=VALID`。

Default Fixture 没有独立 `expect`，所以 `assertions=NOT_CHECKED` 是准确结果，不应改写成 `PASSED`。

### 4.3 把三个 API Resource 编排为契约化 Tool

脚本向 `PUT /api/authoring/flows/{flowId}` 提交 `bloge.reusableFlowSaveCommand.v1`。Graph 有三个节点：

```text
Tool input.customerId
       ├──────────────> profile ───┐
       └──────────────> account ───┼──> offer ──> Tool output
                                   └─────────────
```

每个节点的 `use` 都携带对应 API Resource 的精确 `resourceId/revision/fingerprint`。映射为：

- `profile.input.customerId <- $.customerId`；
- `account.input.customerId <- $.customerId`；
- `offer.input.profile <- $.profile`；
- `offer.input.account <- $.account`；
- Tool output 取 `$.offer`。

保存 receipt 给出不可变 Draft coordinate。随后调用：

```http
POST /api/authoring/flows/{flowId}:publish
```

发布响应中的 `version.publicationId/revision/fingerprint` 是 Tool 的可复用契约坐标。

### 4.4 对 DAG 中三个 API 节点分别指定 Fixture

DAG 模拟仍使用 v2 命令，但 `fixturePlan.kind=BINDINGS`。每个 binding 的 Target 是稳定 `NODE_PATH`，Selection
是一个精确 Fixture Case：

```json
{
  "fixturePlan": {
    "kind": "BINDINGS",
    "unmatched": "BLOCK",
    "bindings": [
      {
        "target": { "kind": "NODE_PATH", "nodePath": ["profile"] },
        "selection": {
          "kind": "EXACT_CASE",
          "fixtureSet": {
            "fixtureSetId": "retention-review-01.customer-profile:r1",
            "revision": 1,
            "fingerprint": "sha256:..."
          },
          "caseId": "vip-risk"
        }
      },
      {
        "target": { "kind": "NODE_PATH", "nodePath": ["account"] },
        "selection": { "kind": "EXACT_CASE", "fixtureSet": {}, "caseId": "high-value" }
      },
      {
        "target": { "kind": "NODE_PATH", "nodePath": ["offer"] },
        "selection": { "kind": "EXACT_CASE", "fixtureSet": {}, "caseId": "save20" }
      }
    ]
  }
}
```

上例省略了后两个 `fixtureSet` 的实际坐标；可执行脚本会从各 Resource receipt 精确填充，不能提交空对象。

预期证据：三个 Invocation 都是 `COMPLETED + MOCKED + EXACT_CASE`，三个 egress 都是
`FIXTURE / attempted=false`，最终 output 为：

```json
{
  "customerId": "C-1001",
  "offerCode": "SAVE20",
  "discountPercent": 20,
  "message": "20% off the next renewal"
}
```

这里仍然执行了真实 DAG 映射，只替代三个外部节点。若 mapping、输入或 output contract 不闭合，运行会失败；
Fixture 命中不会绕过 Tool contract。

### 4.5 为整个 Tool 定义 Fixture

发布 Tool 后，通过 Fixture Set 端点保存 Subject 级 `RETURN`：

```http
PUT /api/authoring/fixture-sets/{fixtureSetId}
If-None-Match: *
Idempotency-Key: ...
```

Fixture Subject 必须是精确 `FLOW_VERSION`，Case 控制必须是 `SUBJECT + RETURN`。本例 Case 同时保存
`input`、`output` 和 `expect.output`，所以整工具模拟能独立给出 assertion 结论。

### 4.6 直接用 whole-Tool Fixture 获得结果

whole-Tool 替代执行使用同一 `/api/authoring/simulations` 入口的冻结
`bloge.simulationRequest.v1 / FIXTURE_CASE` 协议：

```json
{
  "schemaVersion": "bloge.simulationRequest.v1",
  "source": {
    "kind": "FIXTURE_CASE",
    "fixtureSetId": "retention-review-01.tool-fixtures",
    "revision": 1,
    "caseId": "save20-tool"
  },
  "executionPolicy": {
    "externalReads": { "kind": "DENY" },
    "externalWrites": { "kind": "DENY" }
  }
}
```

这与 v2 DAG node bindings 是两个明确层次：

- v2 `BINDINGS`：执行 Tool 的 DAG 与 mapping，只 Mock 指定节点；
- v1 `FIXTURE_CASE`：把整个 Tool Subject 替换成一个已保存 Case，内部节点不执行。

预期 evidence 只有一个 `nodeId=subject`，并且为 `MOCKED + FIXTURE + attempted=false`；
`contract=PASSED`、`assertions=PASSED`，output 精确等于 Fixture output。

## 5. 读取不可变运行证据

脚本输出任一 Run ID 后，可重新读取：

```bash
RUN_ID=sim-...
curl --fail-with-body --silent --show-error \
  "http://localhost:8081/api/authoring/simulations/${RUN_ID}" \
  --header 'Authorization: Bearer bloge-aneke-demo-token' \
  --header 'X-Purpose: API_RESOURCE_AUTHORING' | jq .
```

读取仍受 trusted scope 约束。跨 tenant/project/environment 的相同 Run ID 不可见。

## 6. 结果边界

| 证据 | 能证明 | 不能证明 |
| --- | --- | --- |
| `MOCKED` | 本次 invocation 由 Fixture 控制 | 真实上游可用 |
| `attempted=false` | 本次未尝试网络出站 | Connection 已通过网络检查 |
| `contract=VALID/PASSED` | output 符合冻结 schema | 业务断言已检查 |
| `assertions=PASSED` | 保存的 expect 与 output 相等 | Fixture 已通过组织治理 |
| `governance=NOT_CHECKED` | 本次未执行治理门禁 | 可发布、可生产使用 |
| `aggregate=NOT_READY` | 仍有未通过或未检查维度 | 模拟失败 |

私有 Fixture 的成功运行不能替代生产 Connection Check、真实 egress 授权、Secret Provider、脱敏审查或发布审批。

## 7. 常见失败

| HTTP / code | 常见原因 | 处理方式 |
| --- | --- | --- |
| `401/403` | Token 无效或 purpose 未授权 | 启动时允许 `API_RESOURCE_AUTHORING`，检查 Token |
| `404` | 精确 subject、revision、Case 或 endpoint 不存在 | 使用上一响应中的坐标，不要手写历史 revision |
| `409` | 同一 Idempotency-Key 对应不同请求 | 原请求重放；变更内容时使用新 key |
| `412` | 更新使用了旧 ETag | 读取当前对象，用最新强 ETag 重试 |
| `422` | schema、binding、Fixture subject 或 output 不闭合 | 修正命令；不要通过删除 fingerprint 绕过 |
| `424 simulation.unsupported` | 用 v2 whole-Flow `CASE_CONTROLS` 走错协议层 | whole-Tool 使用 v1 `FIXTURE_CASE`；节点覆盖使用 v2 `BINDINGS` |

## 8. 当前验证证据

2026-09-02 使用 `RG_DEMO_ID=review-144400` 对本脚本执行了完整真实 HTTP 验证：

- 三个 API Resource 均保存成功，并各自产生一个私有 Default Fixture；
- 单 API v2 模拟为 `SUCCEEDED`，一个 `MOCKED` Invocation，`attempted=false`；
- 三 API DAG v2 模拟为 `SUCCEEDED`，三个 `MOCKED` Invocation，最终返回 `SAVE20`；
- whole-Tool v1 模拟为 `SUCCEEDED`，仅一个 `subject` Mock，`assertions=PASSED`；
- 脚本全部内置断言通过并以退出码 `0` 结束。

这是一条本地 H2、演示身份、无真实 egress 的验收证据，不替代 PostgreSQL、外部 Secret Provider 或目标
API 的生产认证。

## 9. 审阅清单

- [ ] 三个 Resource receipt 都包含 `projections.*=READY` 和 `defaultFixture`。
- [ ] 后续命令只消费 receipt 返回的 revision/fingerprint。
- [ ] 单 API 运行命中 `CASE_CONTROLS`，没有 egress。
- [ ] Tool 发布后使用 `FLOW_VERSION`，不依赖 mutable Draft head。
- [ ] 三个 DAG 节点各自命中精确 `NODE_PATH + EXACT_CASE`。
- [ ] DAG output 来自真实 mapping，三个外部节点均为 Mock。
- [ ] whole-Tool Fixture 绑定精确发布版本，内部节点不执行。
- [ ] whole-Tool `expect` 产生独立 `assertions=PASSED`。
- [ ] 没有把 `NOT_CHECKED` 或 `NOT_READY` 误报为治理通过。
