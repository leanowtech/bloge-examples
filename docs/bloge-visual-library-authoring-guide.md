# BLOGE 渐进式算子与 Function 库创作指南

> 合同：`bloge.visualLibraryAuthoring.v1`
>
> 当前能力：Stage 0 无状态权威预览
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

`preview` 是只读操作，不会写入 registry。Stage 0 尚未提供 draft autosave、ETag 或 stale-preview commit。

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
- `sampleInference=false`
- `draftLifecycle=false`

集成方也可以读取 `/api/integration/capabilities`，确认协议对象和三个已实现 endpoint。

## 9. 从预览进入现有 Registry

当前安全路径是先审阅 preview，再把 `canonicalLibrary` 交给既有 canonical validate/import API。不要把本地预览当作提交凭证。

```bash
curl --silent --fail-with-body \
  -H 'Content-Type: application/yaml' \
  --data-binary @docs/examples/customer-service-library-authoring.yaml \
  http://localhost:8080/admin/visual-operator-library-authoring/preview \
  | jq '.canonicalLibrary' \
  | curl --fail-with-body \
      -H 'Content-Type: application/json' \
      --data-binary @- \
      http://localhost:8080/admin/visual-operator-libraries/validate
```

生产化的原子 `preview -> commit` 仍需 draft revision、ETag、catalog fingerprint、warning acknowledgement 和 target registry revision fencing；这些能力不会由上述命令冒充。

## 10. 安全与配额

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

## 11. 相关资料

- [完整技术方案](resource-gateway-progressive-operator-function-library-authoring-technical-design.md)
- [实现状态与差距](resource-gateway-progressive-library-authoring-implementation-status.md)
- [Canonical 算子库 Schema](bloge-visual-operator-library-schema.md)
- [BLOGE Framework Schema Export 需求](bloge-framework-operator-function-schema-export-requirement.md)
