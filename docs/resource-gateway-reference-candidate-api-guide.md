# Resource Gateway 引用候选 API 指南

引用候选 API 为正确性工作台、业务镜像和 VS Code 客户端提供统一的资产发现入口。调用方按业务名称、ID、标签和类型搜索；服务端返回 metadata-only 候选。用户选中候选后，调用方仍需通过 resolve API 重新解析权威坐标。

本 API 不返回 Schema 全文、Fixture material、Evidence payload 或 Secret 内容，也不替代资产运行授权。

## 1. 能力检查

先读取：

```http
GET /api/integration/capabilities
```

相关 feature：

| Feature | 含义 |
|---|---|
| `referenceCandidateApi` | 已提供统一候选搜索和 exact resolve |
| `correctnessTargetCatalogApi` | 已提供正确性 Target 与 Definition 两级目录 |
| `guidedWorkspaceLauncher` | UI 已启用引导式工作区入口 |

部署未广告目录能力时，界面应显示「当前部署未提供资产目录」，并保留高级精确坐标模式。客户端不得通过请求 404 推断能力。

## 2. 候选搜索

```http
GET /api/visual/reference-candidates
Authorization: Bearer <workload-token>
X-Purpose: CORRECTNESS_READ
```

查询参数：

| 参数 | 必填 | 默认值 | 限制 | 说明 |
|---|---:|---:|---:|---|
| `kind` | 否 | 空 | 稳定 kind | 例如 `GRAPH`、`OPERATOR`、`FUNCTION` |
| `query` | 否 | 空 | 最长 200 字符 | 匹配 ID、名称、说明和标签 |
| `cursor` | 否 | 空 | 最长 4096 字符 | 只用于同一查询的下一页 |
| `limit` | 否 | `20` | `1..100` | 单页最大候选数 |
| `lifecycle` | 否 | 空 | 协议枚举 | `DRAFT`、`ACTIVE`、`DEPRECATED`、`SUPERSEDED` |
| `compatibleWith` | 否 | 空 | 协议枚举 | `COMPATIBLE`、`REVIEW`、`INCOMPATIBLE`、`UNKNOWN` |

响应中的 `queryFingerprint` 和 `catalogGeneration` 共同定义分页快照。目录发生变化时，旧 cursor 返回 `RG.REFERENCE.CURSOR_STALE`；客户端应从第一页重试，但不得清除用户已经选中的 exact ref。

候选的关键字段：

```json
{
  "schemaVersion": "bloge.referenceCandidate.v1",
  "kind": "GRAPH",
  "id": "loan-decision-with-fallback",
  "displayName": "Loan decision correctness",
  "revision": 7,
  "fingerprint": "sha256:...",
  "authority": "resource-gateway://correctness-targets",
  "scope": {
    "tenantId": "tenant-a",
    "organizationId": "knowledge-governance",
    "projectId": "tool-studio",
    "environmentId": "test",
    "region": "local"
  },
  "lifecycle": "ACTIVE",
  "owner": {
    "stableId": "credit-service-design",
    "displayName": "Credit Service Design"
  },
  "compatibility": "COMPATIBLE",
  "disabledReasonCode": ""
}
```

`owner.stableId` 是可持久化身份。`owner.displayName` 只用于展示。

## 3. 正确性 Target 与 Definition

正确性工作台使用两级选择：

1. 搜索 exact Target；
2. 根据 exact Target 查询绑定的 Correctness Definition。

```http
GET /api/visual/correctness-targets?targetKind=GRAPH&query=loan
GET /api/visual/correctness-targets/GRAPH/loan-decision-with-fallback/definitions?targetFingerprint=sha256%3A...
```

Definition endpoint 最多返回两个 current head。语义如下：

| 结果数 | UI 行为 |
|---:|---|
| `0` | 显示「尚未定义业务正确性」，提供创建或高级精确模式 |
| `1` | 自动选择，并明确显示自动绑定结果 |
| `2` | 要求用户选择；不得根据更新时间静默绑定 |

正确性运行时未安装 Definition repository 时，Target/Definition 目录返回 `RG.REFERENCE.CATALOG_UNAVAILABLE`，不会伪造空结果。

## 4. Exact resolve

搜索结果只用于发现。保存绑定、打开工作区或执行跨工作区动作前，调用：

```http
POST /api/visual/reference-candidates:resolve
Authorization: Bearer <workload-token>
X-Purpose: BUSINESS_MIRROR_AUTHORING
Content-Type: application/json

{
  "schemaVersion": "bloge.referenceResolveCommand.v1",
  "kind": "GRAPH",
  "id": "loan-decision-with-fallback",
  "revision": 7,
  "fingerprint": "sha256:...",
  "intendedUse": "EDIT_TOPOLOGY"
}
```

Scope 不由请求正文提交。Controller 从已验证 workload identity 重建 `tenantId / organizationId / projectId / environmentId / region`，避免调用方扩大查询边界。

resolve 状态：

| 状态 | 含义 | 调用方处理 |
|---|---|---|
| `RESOLVED` | exact 坐标仍有效 | 可进入下一步 |
| `DRIFTED` | authority 中存在新坐标 | 展示返回的当前候选；禁止静默升级 |
| `NOT_FOUND` | 当前授权 Scope 中不存在 | 标记选择失效并重新搜索 |
| `FORBIDDEN` | 当前 purpose 无权绑定 | 显示权限申请或切换身份入口 |

`DRIFTED` 响应携带当前权威候选，便于比较 revision 和 fingerprint。`NOT_FOUND` 与 `FORBIDDEN` 不携带候选，避免泄漏未授权资产。

## 5. Provider SPI

默认 `ResourceGatewayReferenceCandidateProvider` 投影以下核心权威源：

- `GraphDraftRepository`；
- `VisualOperatorCatalog`；
- `VisualOperatorCatalog.builtInFunctions()`。

企业部署优先实现一个或多个 `ReferenceCandidateContributor` Bean，把 ANEKE、组织 Owner 目录、Taxonomy
目录或业务资产注册表的 metadata 投影进统一目录。Provider 先加入核心候选，再按 `contributorId` 稳定排序聚合；
exact coordinate 冲突时核心候选优先。Contributor 不复制外部系统的业务内容，也不成为新的权威库。

只有需要完全改变 Provider 行为的部署才整体提供自定义 `ReferenceCandidateProvider` Bean；默认配置仍使用
`@ConditionalOnMissingBean` 允许该替换。

Contributor 与自定义 Provider 都必须满足：

- 查询前已完成 Scope 与 purpose 认证；
- 返回内容严格限制为 metadata；
- `snapshot.generation` 在目录变化时改变；
- 结果顺序经统一服务确定性排序；
- resolve 重新读取 authority，不复用搜索缓存作最终授权。

内置贷款决策演示 Contributor 只在 `test` 或 `staging` profile 且
`gateway.testing.correctness.demo.enabled=true` 时装配。它覆盖 Business Mirror 所需的 13 类元数据候选，
不包含 Schema 正文、Fixture、Evidence payload、凭据或 Secret；生产 profile 永不装配。

## 6. 错误与恢复

| 错误码 | HTTP | 是否可重试 | 恢复方式 |
|---|---:|---:|---|
| `RG.REFERENCE.REQUEST_INVALID` | `400` | 否 | 修正 query、facet、limit 或协议版本 |
| `RG.REFERENCE.CURSOR_STALE` | `409` | 是 | 从第一页重试同一查询 |
| `RG.REFERENCE.QUERY_FINGERPRINT_MISMATCH` | `409` | 是 | 丢弃旧 cursor，使用新查询第一页 |
| `RG.REFERENCE.CATALOG_UNAVAILABLE` | `503` | 是 | 保留输入并重试；允许进入高级精确模式 |
| `RG.INTEGRATION.PURPOSE_FORBIDDEN` | `403` | 否 | 使用被 operation 接受的 purpose 或申请权限 |

错误响应和候选响应均使用 `Cache-Control: no-store`。审计记录保存 purpose、operation、调用方和结果，不保存 query 原文或业务 payload。
