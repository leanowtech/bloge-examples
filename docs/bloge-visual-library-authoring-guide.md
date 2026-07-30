# BLOGE 渐进式算子与 Function 库创作指南

> 合同：`bloge.visualLibraryAuthoring.v1`
>
> 当前能力：Stage 0 权威编译 + Stage 1 持久化 draft/ETag/preview-fenced commit
>
> 机器 Schema：[bloge-visual-library-authoring-v1.schema.json](schemas/bloge-visual-library-authoring-v1.schema.json)

## 1. 什么时候使用

当你只想描述业务能力、输入输出、函数签名和测试资产引用时，优先使用本合同。Resource Gateway 会确定性编译它，并复用既有 `bloge.visualOperatorLibrary.v1` validator。

```text
精简 YAML
  -> 安全解析
  -> 类型与函数签名编译
  -> canonical library
  -> validator / callable conflict / target diff
  -> readiness + source map
```

它不会替代 canonical 合同。需要填写完整 policy、lowering 或高级 JSON Schema 时，继续使用 [Canonical 算子库合同](bloge-visual-operator-library-schema.md)。

## 2. 快速体验

启动服务：

```bash
./scripts/start-visual-canvas-demo.sh
```

预览完整 operator + function 示例：

```bash
curl --fail-with-body \
  -H 'Content-Type: application/yaml' \
  --data-binary @docs/examples/customer-service-library-authoring.yaml \
  http://localhost:8080/admin/visual-operator-library-authoring/preview
```

预览 function-only 示例：

```bash
curl --fail-with-body \
  -H 'Content-Type: application/yaml' \
  --data-binary @docs/examples/function-only-library-authoring.yaml \
  http://localhost:8080/admin/visual-operator-library-authoring/preview
```

成功响应重点看：

| 字段 | 含义 |
| --- | --- |
| `previewAuthority` | 服务端返回 `SERVER_AUTHORITATIVE` |
| `canonicalLibrary` | 生成的 canonical `bloge.visualOperatorLibrary.v1` |
| `sourceMap` | canonical 路径到可编辑 YAML 路径的映射 |
| `diagnostics` | 编译、canonical 校验和目标 catalog 冲突 |
| `confirmationRequests` | 缺 owner、secret posture 等待确认事实 |
| `readiness` | 是否可导入、Schema 是否明确、当前成熟度 |
| `diff` | 与同 `library.id` 当前 registry revision 的差异 |
| `catalogFingerprint` | 本次冲突判断所依据的目标 catalog 快照 |

顶层 `preview` 是只读操作，不会写入 registry。需要可恢复编辑、自动保存和受保护提交时，
使用第 9 节的 draft lifecycle。

## 3. 最小文档

Operator-only：

```yaml
schemaVersion: bloge.visualLibraryAuthoring.v1
library:
  id: support-operators
  owner: support-team
operators:
  support:echo:
    archetype: pure
    input:
      value: string
    output:
      value: string
```

Function-only：

```yaml
schemaVersion: bloge.visualLibraryAuthoring.v1
library:
  id: support-functions
  owner: support-team
functions:
  support.normalize:
    signature: "(text: string) -> string"
```

`operators` 与 `functions` 至少一项非空。Function-only library 是正式能力，不需要伪造 operator。

## 4. 类型写法

高频类型可以直接写成字符串：

```yaml
input:
  id: string
  tags: "string[]"
  matrix: "integer[][]"
  optionalPort?: Customer
```

支持：

```text
any unknown string number integer boolean object json date datetime
NamedType
T[]
T?
```

字段名尾部 `?` 与类型尾部 `?` 含义不同：

| 写法 | 含义 |
| --- | --- |
| `nickname?: string` | 字段或端口可以不存在 |
| `nickname: string?` | 必须存在，但值可以是 `null` |
| `nickname?: string?` | 可以不存在，存在时也可以是 `null` |

命名对象与约束：

```yaml
types:
  Score:
    type: integer
    minimum: 0
    maximum: 100
  Applicant:
    fields:
      id: string
      score?: Score
      status:
        enum: [pending, approved, rejected]
```

Quick 合同禁止外部 `$ref`、条件 Schema 和任意自定义关键字。复杂 Schema 请升级到 Canonical Advanced。

## 5. Operator Archetype

| Archetype | 默认 effect | 关键确认 |
| --- | --- | --- |
| `pure` | `PURE` | input/output |
| `decision` | `PURE` | rules/input/output |
| `resource-read` | `READ_EXTERNAL` | runtime、secret posture |
| `external-write` | `WRITE_EXTERNAL` | idempotency、side-effect protocol、secret、runtime |
| `remote-worker` | `EXTERNAL` | effect、binding、durable、timeout |
| `ai-tool` | `EXTERNAL` | binding、data policy、timeout |
| `event-source` | `EXTERNAL` | event binding、delivery |
| `message-handler` | `EXTERNAL` | delivery、retry、dead letter |
| `webhook` | `EXTERNAL` | auth、request verification |

只有 `pure` 和 `decision` 默认 `requiresSecrets=false`。外部能力缺少 secret posture 会返回阻断诊断，不会静默当作安全。

## 6. Function 签名

```yaml
functions:
  support.pickFirst:
    signatures:
      - "(value: any, fallback?: any) -> any"
      - "(values: any[]) -> any"
```

规则：

- 参数写作 `name: Type`；
- 可选参数写作 `name?: Type`；
- 可变参数写作 `...values: Type`，并且只能位于最后；
- 每个函数最多 20 个签名，每个签名最多 64 个参数；
- 不支持默认值表达式，也不会执行签名文本。

即时校验：

```bash
curl --fail-with-body \
  -H 'Content-Type: application/json' \
  -d '{"signature":"(value: string, fallback?: string) -> string"}' \
  http://localhost:8080/admin/visual-operator-library-authoring/signature/parse
```

表达式按 function `name` 解析，`namespace` 只表示来源。与默认函数或其他 library 同名但合同不同会返回 `RG.AUTHORING.FUNCTION_CALLABLE_CONFLICT`。

## 7. 测试引用

Operator 和 function 都可以引用独立测试资产：

```yaml
tests:
  - ref: fixtures/classify-enterprise-ticket
```

引用进入 authoring source，但 fixture payload 不进入公开 operator catalog。Stage 0 只保留引用；测试资产解析、生成与 runner 在后续测试闭环阶段接入。

## 8. 读取能力边界

```bash
curl --fail-with-body \
  http://localhost:8080/admin/visual-operator-library-authoring/catalogs
```

该响应给出 grammar、archetype、配额、catalog fingerprint 和精确 feature flags。当前应看到：

- `functionOnlyLibrary=true`
- `statelessPreview=true`
- `crossLibraryTypeImports=false`
- `sampleInference=true`
- `draftLifecycle=true`
- `etagConcurrency=true`
- `previewFencedCommit=true`

集成方也可以读取 `/api/integration/capabilities`，确认协议对象、stateless endpoint 和
draft lifecycle endpoint。

## 9. 持久化 Draft 与受保护提交

Workbench 使用 `If-Match` 保存每个可编辑 revision。创建时显式提交 revision `0`：

```bash
curl --fail-with-body -i -X PUT \
  -H 'Content-Type: application/json' \
  -H 'If-Match: "0"' \
  -d '{
    "sourceMode": "QUICK",
    "actor": "demo-author",
    "document": {
      "schemaVersion": "bloge.visualLibraryAuthoring.v1",
      "library": {"id": "support-quick", "owner": "support-team"},
      "operators": {
        "support:echo": {
          "archetype": "pure",
          "input": {"value": "string"},
          "output": {"value": "string"}
        }
      }
    }
  }' \
  http://localhost:8080/admin/visual-operator-library-authoring/drafts/support-quick
```

响应 `ETag: "1"` 与 body `revision=1` 表示服务端已保存第一版。后续保存必须发送最后观察到的
ETag；两个标签同时编辑时，旧标签收到 `412 RG.AUTHORING.DRAFT_REVISION_STALE`，不会覆盖新版本。

对 revision `1` 获取权威预览：

```bash
curl --fail-with-body \
  -H 'If-Match: "1"' \
  -X POST \
  http://localhost:8080/admin/visual-operator-library-authoring/drafts/support-quick/preview \
  > /tmp/support-quick-preview.json
```

提交到 Design Catalog 时，必须回传这次预览的四个指纹与目标 revision：

```bash
jq '{
  authoringFingerprint,
  compileFingerprint,
  catalogFingerprint,
  canonicalFingerprint,
  targetRevision: .diff.baseRevision,
  actor: "demo-author",
  reason: "Reviewed in Library Workbench"
}' /tmp/support-quick-preview.json \
| curl --fail-with-body \
    -H 'Content-Type: application/json' \
    -H 'If-Match: "1"' \
    --data-binary @- \
    http://localhost:8080/admin/visual-operator-library-authoring/drafts/support-quick/commit
```

服务端会重新读取 exact draft、重新编译并重新读取 catalog。任一 revision 或 fingerprint
变化都会返回 `409/412`，不会把 stale preview 导入 registry。commit 只产生 design
catalog revision；它不等于 runtime binding 或 production publish。

## 10. 从多个样本推断字段

样本推断绑定一个已保存 draft 的精确 revision 和 operator input/output 位置。它只产生
`OBSERVED` facts、保守 candidate 与待确认问题，不修改 draft，也不会把观察结果自动升级
为 declared contract。

```bash
curl --fail-with-body \
  -H 'Content-Type: application/json' \
  -H 'If-Match: "1"' \
  -d '{
    "schemaVersion": "bloge.visualSampleInferenceRequest.v1",
    "target": {
      "assetKind": "OPERATOR",
      "assetRef": "support:echo",
      "portDirection": "INPUT",
      "portName": "value"
    },
    "samples": [
      {"ticketId": "T-1001", "score": 1, "status": "open"},
      {"ticketId": "T-1002", "score": 1.5, "status": "closed"}
    ],
    "options": {
      "suggestEnums": true,
      "suggestFormats": true,
      "persistPayload": false
    },
    "idempotencyKey": "support-echo-input-1"
  }' \
  http://localhost:8080/admin/visual-operator-library-authoring/drafts/support-quick/infer/samples
```

响应中的 `candidate` 保持 object open；日期格式、enum、required/nullable、敏感字段和类型
冲突进入 `confirmationRequests`。`observations` 解释样本数、出现次数、null 数、distinct 数、
类型拓宽原因和敏感性。原始 `samples` 不进入响应、draft、public catalog 或日志，且
`payloadPersisted` 固定为 `false`。需要长期保存的测试数据应另行进入受治理 fixture。

调用限制为 2 MiB、100 个样本、总计 20,000 个 JSON node、32 层深度、每对象/数组
2,000 项。过期 `If-Match` 返回 `412`，未知 operator 返回 `404`，请求
`persistPayload=true` 返回 `422`。机器合同见：

- [Sample inference request schema](schemas/bloge-visual-sample-inference-request-v1.schema.json)
- [Sample inference result schema](schemas/bloge-visual-sample-inference-result-v1.schema.json)

当前 Workbench 还没有把 confirmation queue 做成可操作界面；本阶段先提供服务端协议与
推断内核，客户端不得把 candidate 静默写回 declared schema。

## 11. 安全与配额

解析入口执行：

- 5 MiB 原始正文限制；
- JSON/YAML 重复键拒绝；
- YAML 自定义 tag、递归 alias 拒绝；
- alias 最多 20；
- parser 深度 64、类型深度 32；
- 每库最多 1000 types、1000 operators、2000 functions；
- 每对象最多 2000 fields；
- 签名和紧凑类型长度、参数数量有界。

超限返回 `RG.AUTHORING.DOCUMENT_LIMIT_EXCEEDED`；语法或结构问题返回 `RG.AUTHORING.PARSE_FAILED`。错误响应不会回显完整输入 payload。

## 12. 相关资料

- [完整技术方案](resource-gateway-progressive-operator-function-library-authoring-technical-design.md)
- [实现状态与差距](resource-gateway-progressive-library-authoring-implementation-status.md)
- [Canonical 算子库 Schema](bloge-visual-operator-library-schema.md)
- [BLOGE Framework Schema Export 需求](bloge-framework-operator-function-schema-export-requirement.md)
