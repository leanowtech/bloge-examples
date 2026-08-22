# Capability Studio Gate A1 — Source Package and Compiler

**版本**: 1.0.0-DRAFT
**范围**: Source Package 结构、Merkle 算法、独立版本管理、Compiler 纯函数契约、Role Visibility、Package-Scoped RelationHandle、Candidate/Provider 依赖约束
**跨文档引用**: Gate A2（运行时分发）、Gate A3（Ledger 绑定）仅在架构层面被引用；本文不涉及 Ledger 操作语义和运行期降级策略。

---

## 0. 规范性引用

本文档规范性引用 [00-normative-conventions.md](00-normative-conventions.md)。该文档定义了所有跨模块共享的密码学原语：

所有密码学原语（ENC、JCS、SHA256、lowerhex、ContentDigest、TypedFP、raw32）由 [00-normative-conventions.md](00-normative-conventions.md) 统一维护；本文档仅在此声明 Merkle 专用原语。

**Merkle 是 00 的 special primitive**：MerkleLeaf、MerkleNode 由 00 §3 统一定义；treeRoot / packageFP 进入 wire 时以 WireDigest（`sha256:` + lowerhex）表示；`raw32` 仅用于树内部节点哈希构造，边界处必须转换为 WireDigest，不得出现在 wire 协议中。

**algorithmVersion 注册表**：Merkle 算法版本标识符（如 `"1.0"`）在 00 规范 Primitive 注册表中登记；版本演进须在 00 中声明兼容性矩阵。

---

## 1. Protocol Source Package

### 1.1 定义

Protocol Source Package（**Source Package**）是可验证构建单元：

| 组件 | 说明 |
|------|------|
| `manifest` | 包级元数据，含 algorithmVersion、orderedDescriptors、treeRoot |
| `schema/` | 所有 schema 源文件（`.schema.json`） |
| `role/` | 所有 role 定义文件（`.role.json`） |
| `rule/` | 所有 rule 源文件（`.rule.json`） |
| `evidence/` | 所有 evidence 源文件（`.evidence.json`） |
| `authority/` | 所有 authority 源文件（`.authority.json`） |
| `visibility-policy/` | 所有 visibility-policy 源文件（`.visibility-policy.json`） |

**packageFP 是唯一事实源。**
`packageFP = TypedFP('rg.gatea.package.v1', manifestEnvelope)`，其中 TypedFP 实现为 `SHA256(ENC(domain) || ENC(JCS(payload)))`（见 00 §2.3），packageFP 跨边界以 WireDigest 形式出现。
`manifestEnvelope = { "algorithmVersion": "1.0", "orderedDescriptors": [...], "treeRoot": "sha256:lowerhex" }`，`orderedDescriptors` 为 descriptor 列表按 UTF-8 字节序升序排列后的结果。
RA List Snapshot 作为 authority 源单元包含在 Source Package 中，packageFP 承诺其完整性。
任何 source unit 变更均生成新 packageFP；不得绕过 packageFP 验证。

### 1.2 Source Unit 结构

```json5
{
  "apiVersion": "studio.a1/v1",
  "unitType": "schema|role|rule|evidence|authority|visibility-policy",
  "unitId": "urn:example:schema:auth-token:1.0",
  "roleVisibility": ["admin", "provider"],   // 必填，进入 packageFP
  "content": { /* 实际业务内容 */ }
}
```

**unitType 说明**：
- `schema`：数据模型定义
- `role`：角色权限定义
- `rule`：业务规则定义
- `evidence`：证据模式定义
- `authority`：RA List Snapshot（完整 Registered Authority 列表快照），属于 authority 源单元
- `visibility-policy`：可见性策略定义，属于 visibility-policy 源单元

### 1.3 输入与输出

- **输入**：至少一个 source unit + manifest 元数据
- **输出**：Source Package（manifest.json + source unit + manifestEnvelope + packageFP）

---

## 2. Merkle 算法规范

### 2.1 Merkle 专用原语声明

本文档声明以下 Merkle 树专用原语（由 00 统一引用）：

| 原语 | 值 | 说明 |
|------|-----|------|
| `LEAF_DOMAIN` | `"rg.gatea.merkle-leaf.v1"` | leaf 哈希域标签 |
| `NODE_DOMAIN` | `"rg.gatea.merkle-node.v1"` | node 哈希域标签 |
| `PACKAGE_DOMAIN` | `"rg.gatea.package.v1"` | packageFP 域标签 |

**wire digest 编码约定**：Merkle leaf/node 是专用 digest32 构造原语（见 00 §3），不是 TypedFP。树根 treeRoot 进入 manifestEnvelope 时以 WireDigest（`sha256:` + 64 字符 lowerhex）形式出现；packageFP 以 WireDigest 形式出现。内部树操作使用 raw32（32 字节原始二进制），raw32 不得出现在 wire 协议、API 响应或持久化记录中。

### 2.2 编码规则（来自 00）

| 规则 | 实现要求 |
|------|----------|
| ENC | 见 00 §1.2 |
| JSON 序列化 | JCS（RFC 8785），见 00 §1.1 |
| 路径规范化 | NFC；禁止绝对路径、`.`/`..`、大小写碰撞（统一小写比较） |
| 排序 | descriptor 按 UTF-8 字节序升序 |
| 奇数叶 | 复制最后叶作为右兄弟 |

### 2.3 树结构

```
leaf  = SHA256(ENC(LEAF_DOMAIN) || ENC(unitType) || ENC(normalizedLogicalPath) || ENC(JCS(wholeSourceUnit)))
node  = SHA256(ENC(NODE_DOMAIN) || left_raw32 || right_raw32)   // raw32：32字节原始二进制
root  = treeRoot = leaf   // 单叶时：root = leaf
root  = treeRoot = node   // 多叶时：root = 顶层 node
```

- **leaf/node 域严格分离**：`LEAF_DOMAIN`（`"rg.gatea.merkle-leaf.v1"`）与 `NODE_DOMAIN`（`"rg.gatea.merkle-node.v1"`）作为 ENC 域前缀明确区分；leaf 不参与 node 哈希，node 不作为 leaf。Merkle leaf/node 不属于 TypedFP，是专用 digest32 构造原语。
- **非空约束**：package 至少含一 source unit；允许某类目录为空（无 .gitkeep 占位要求）。
- **manifestEnvelope**：`algorithmVersion`（"1.0"）、`orderedDescriptors`、`treeRoot`。

### 2.4 正向验收

- [ ] 相同 source unit 集合在不同机器上 packageFP 字节级一致
- [ ] 添加/删除任意 source unit 后 packageFP 变化
- [ ] 奇数叶时最后叶复制为右兄弟
- [ ] descriptor 重排序被 canonical 排序归一后 packageFP 不变
- [ ] treeRoot 以 WireDigest 编码，raw32 仅用于树内部构造，不出现在 wire 协议中

**跨文档同步**：正向验收断言与 02 §11、03 §9 的断言列表保持一致；错误码（E_CONTENT_FINGERPRINT_MISMATCH 等）仅在 01 中定义，不与其他文档重复。

### 2.5 攻击验收

- [ ] `../schema` 或 `./schema` 被路径规范化拒绝
- [ ] `Schema/` vs `schema/` 统一小写后比较
- [ ] 空目录可存在，无 .gitkeep 占位要求
- [ ] 篡改 source unit 内容被 leaf hash 不一致检测拒绝

---

## 3. 独立版本管理

| 命名空间 | 示例 | 说明 |
|----------|------|------|
| `sourcePackage` | `1.2.3` | Source Package 自身语义化版本 |
| `compiler` | `2.1.0` | 编译此 package 的编译器版本 |
| `IR` | `ir/v3` | 中间表示格式版本 |

---

## 4. Compiler Manifest

### 4.1 定义

Compiler Manifest（**compilerManifest**）是 Compiler 纯函数的输出，记录构建时元数据，绑定 compiler 输出到 packageFP、compilerVersion、optionsFP、按 (type, path) 排序的产物 descriptor {type, path, rawFP} exact-set。

### 4.2 compilerManifest payload

```json
{
  "packageFP": "sha256:...",
  "compilerVersion": "2.1.0",
  "optionsFP": "sha256:...",
  "products": [
    { "type": "ir", "path": "...", "rawFP": "sha256:..." },
    { "type": "projection", "path": "...", "rawFP": "sha256:..." }
  ]
}
```

产物按 (type, path) 升序排序；不嵌入 whole outputs。

### 4.3 错误码

| 代码 | 含义 |
|------|------|
| `E_CONTENT_FINGERPRINT_MISMATCH` | source unit 内容与 leaf hash 不一致 |
| `E_NON_DETERMINISTIC_VERSION` | SemVer range 解析非确定性 |
| `E_VERSION_INCOMPATIBLE` | 构建期版本与目标环境不兼容 |
| `E_ROLE_VISIBILITY_MISSING` | 缺少 roleVisibility 必填字段 |
| `E_EMPTY_SOURCE_UNIT` | source unit 内容为空 |
| `E_PATH_NORMALIZATION_FAILED` | 路径含禁止字符 |
| `E_MANIFEST_SIGNATURE_INVALID` | manifestEnvelope 与 packageFP 不匹配 |

---

## 5. Role Visibility 与 Package Fingerprint

`roleVisibility` 为**必填字段**，进入 packageFP 计算（第 1.1 节已定义 packageFP 公式）。

**投影精确性**：仅包含 roleVisibility 包含该 role 的 source unit；未授权 role 请求时返回空列表，不得暴露存在性差异。caller 使用前必须以完整 packageFP 验证 manifestEnvelope 的 treeRoot 与声明值是否一致。

---

## 6. Package-Scoped RelationHandle

### 6.1 Authoring 阶段

```
CSPRNG128() -> relationHandle   // 128 bit 密码学安全随机数
绑定: relationHandle -> (unitId, roleVisibility, packageFP)
写入: source unit 含 relationHandle 字段，进入 packageFP
```

- 每 **package version** 重生成 relationHandle（不跨版本复用）。
- relationHandle source unit 只被 packageFP 承诺；不写循环绑定 packageFP。
- 不得从数据内容派生（防逆向）。
- **攻击防御**：relationHandle 受 packageFP 绑定 + Authoring CSPRNG 保护；packageFP 任何修改使 relationHandle 绑定失效，CSPRNG 保证不可预测性。
- Compiler 作为纯函数仅将 relationHandle 确定性复制到**授权 projection** 输出中。

---

## 7. Candidate 与 Provider 依赖约束

| 主体 | 允许 | 禁止 |
|------|------|------|
| Candidate | caller-frozen SPI API artifact（artifactFP 已知且不可变） | Provider implementation |
| Provider | frozen SPI（Caller 提供版本固定接口定义） | Candidate implementation |

**实际 class bytes / ABI / JAR FP 交叉绑定**：
- JAR FP = SHA256(raw JAR bytes)。
- class exact-set 另行列出于 closureManifest。
- ABI 由 actual public signatures / descriptor diff 校验，不由 SemVer 保证；不兼容时报 `E_ABI_INCOMPATIBLE`。
- JAR FP / class bytes / ABI 三者交叉验证，任一变更生成新 closureManifest FP。

| 代码 | 含义 |
|------|------|
| `E_DEPENDENCY_ON_PROVIDER_IMPL` | Candidate 依赖了 Provider implementation |
| `E_ABI_INCOMPATIBLE` | Provider 使用 Caller 未暴露的 SPI 成员 |
| `E_UNFROZEN_SPI_ARTIFACT` | 依赖的 SPI artifact 标记为非 frozen |
| `E_CLASS_BYTES_FP_MISMATCH` | 运行期 class bytes 与 closureManifest 记录不符 |

---

## 8. 正向验收清单

| # | 检查项 |
|---|--------|
| P1 | source unit 集合在两台机器上产生相同 packageFP |
| P2 | role projection 仅含授权 source unit |
| P3 | 未授权 role 获取不到 source unit 存在性信号 |
| P4 | relationHandle 在每个 package version 重新生成 |
| P5 | Compiler 纯函数：相同输入产生相同输出 |
| P6 | manifestEnvelope 与 packageFP 字节级一致 |
| P7 | SemVer range 相对于 caller-pinned catalog/lock snapshot 解析并记录 exact selection |
| P8 | Candidate 不依赖 Provider implementation |
| P9 | JAR FP / class bytes / ABI 三者交叉绑定验证通过 |
| P10 | 手改 source unit 内容触发 E_CONTENT_FINGERPRINT_MISMATCH |
| P11 | treeRoot 使用 WireDigest 编码，raw32 不出现在 wire 协议中 |
| P12 | algorithmVersion 在 00 注册表中登记 |

---

## 9. 攻击验收清单

| # | 攻击向量 | 防御 |
|---|----------|------|
| A1 | 路径遍历（`../schema`） | NFC + 路径规范化拒绝 |
| A2 | 大小写碰撞（`Schema/`） | 统一小写比较 |
| A3 | 空目录存在 | 允许空目录，无 .gitkeep 占位要求 |
| A4 | 篡改 source unit 内容 | leaf hash 不一致检测 |
| A5 | 运行时降级版本 | 构建期 exact-version 锁定（相对 caller-pinned catalog/lock snapshot） |
| A6 | 未授权 role 获取 fingerprint | 投影精确性隔离 |
| A7 | Provider implementation 泄露给 Candidate | Candidate 依赖约束检查 |
| A8 | 非确定性 SemVer 解析 | E_NON_DETERMINISTIC_VERSION |
| A9 | manifestEnvelope 伪造 | packageFP 绑定验证 |
| A10 | 手改 relationHandle | packageFP 绑定 + Authoring CSPRNG 不可预测性 |
| A11 | raw32 摘要泄露到 wire 协议 | WireDigest 编码规范强制 |

---

## 10. Target Schema Authority：PLANNED_A1_SCHEMA

### 10.1 Schema 权威路径

**Target schema 权威路径**：`docs/schemas/resource-gateway-capability-studio-a1/`

此路径是 Gate A1 生产路径的 schema 事实源，与 LEGACY_GATE_A_WIRE_V1（`docs/schemas/resource-gateway-capability-studio/`）严格隔离。

**禁止**：不得在 `docs/schemas/resource-gateway-capability-studio-a1/` 下放置任何 legacy wrapper object 或 legacy 算法实现。所有文件必须使用 bare WireDigest 和本文档 §1-§9 定义的规范。

### 10.2 必需初始 Target Schemas（Step0/Source 计划产出）

以下 schema 文件在 Step0/Source 阶段必须创建于 `docs/schemas/resource-gateway-capability-studio-a1/` 路径下：

| Schema ID | 文件名 | 说明 | 当前状态 |
|-----------|--------|------|----------|
| `normative-primitives-v1` | `normative-primitives-v1.schema.json` | WireDigest 模式（排除全零）、closed domain enum、genesis-zero sentinel 类型 | **待创建** |
| `source-unit-v1` | `source-unit-v1.schema.json` | Source Unit 结构（含 unitType、unitId、roleVisibility、content） | **待创建** |
| `source-package-v1` | `source-package-v1.schema.json` | Source Package manifest 结构（algorithmVersion、orderedDescriptors、treeRoot、packageFP） | **待创建** |
| `compiler-manifest-v1` | `compiler-manifest-v1.schema.json` | Compiler Manifest（packageFP、compilerVersion、optionsFP、products[]） | **待创建** |
| `oracle-manifest-v1` | `oracle-manifest-v1.schema.json` | Oracle Manifest（oracleId、oracleType、releaseStatus、fixtureCorpusRoot） | **待创建** |
| `attack-case-v1` | `attack-case-v1.schema.json` | 已知攻击向量测试用例结构 | **待创建** |
| `evidence-catalog-entry-v1` | `evidence-catalog-entry-v1.schema.json` | Evidence Catalog 条目（evidenceId、schemaRef、semanticVerifier、policy.READ_CATALOG_ONLY） | **待创建** |
| `observation-receipt-v1` | `observation-receipt-v1.schema.json` | Observation Receipt Payload 结构 | **待创建** |
| `acceptance-receipt-v1` | `acceptance-receipt-v1.schema.json` | Acceptance Receipt（invocationKeyFP、decisionInputFP、reducerOutputFP、resultFP、ledgerEntryFP、status） | **待创建** |
| `ledger-entry-v1` | `ledger-entry-v1.schema.json` | Ledger Entry（ledgerId、packageFP、sliceFP、frozenInputs、effectiveStatus） | **待创建** |
| `revocation-record-v1` | `revocation-record-v1.schema.json` | Revocation Record（raSignature、revocationPayloadFP、authorityId） | **待创建** |
| `hermetic-observation-v1` | `hermetic-observation-v1.schema.json` | Hermetic Observation（clockUsage=false、randomUsage=false、networkUsage=false） | **待创建** |
| `observer-failure-v1` | `observer-failure-v1.schema.json` | ObserverFailureReceipt payload/envelope（producerOwner、failureReason、observedAt、visibility），由 03 §9.1/§9.2 定义；schema identity = `urn:studio:schema:observer-failure:v1`；与 `hermetic-observation-v1`（通用 hermetic run observations）严格区分 | **待创建** |


**当前状态**：以上 schema 文件均**不存在**于 `docs/schemas/resource-gateway-capability-studio-a1/` 路径下。它们是 Step0/Source 的计划产出。

**实现约束**：任何消费 target schema 产出的实现必须在对应 schema 创建并通过验证后，方可将产出纳入生产路径。不得消费 prose 推导的 ad-hoc JSON。

### 10.3 normative-primitives-v1 强制约束

Target `normative-primitives-v1` schema 必须满足以下约束：

1. **WireDigest 模式**：`pattern: "^sha256:[0-9a-f]{64}$"`，默认排除全零值。
2. **Closed domain enum**：所有枚举类型使用 `enum` 关键字，closed 模式拒绝未列出的值。
3. **Genesis-zero sentinel**：`genesis-zero` 作为独立类型或枚举值，与有效 WireDigest 明确区分，不得隐式映射为全零摘要。

### 10.4 evidence-catalog-entry-v1 强制约束

Target `evidence-catalog-entry-v1` schema 必须满足以下约束：

1. `policy` 字段必须显式声明 `READ_CATALOG_ONLY`（无 clock/random/network 访问）。
2. `semanticVerifier.clockUsage == false`、`randomUsage == false`、`networkUsage == false`。
3. **Cross-field 时间排序**：由语义验证器逻辑处理（见 02 §1.1），JSON Schema 无法直接比较两个字段值；必须提供 mandatory negative test 验证跨字段约束违反时正确拒绝。

### 10.5 与 LEGACY_GATE_A_WIRE_V1 的关系

| 维度 | LEGACY_GATE_A_WIRE_V1 | PLANNED TARGET A1 |
|------|----------------------|-------------------|
| 路径 | `docs/schemas/resource-gateway-capability-studio/` | `docs/schemas/resource-gateway-capability-studio-a1/` |
| Schema 状态 | 已存在（Oracle 签封） | **待创建** |
| Digest 格式 | 可能包含 wrapper object | bare WireDigest 字符串 |
| 算法 | 可能不含 ENC | ENC + JCS（RFC 8785） |
| 生产路径 | 禁止消费 | **唯一合法 schema 源** |
| Oracle 消费 | Oracle 内部接受 | 不接受 legacy wrapper |

迁移路径见 04 §2.2。
