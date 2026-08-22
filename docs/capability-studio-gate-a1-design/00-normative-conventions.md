# Capability Studio Gate A1 — 规范性约定

**版本**: 1.0.0-DRAFT
**适用范围**: 所有 Gate A1 协议构件、摘要算法、编码规则
**跨文档引用**: 本文档是 Gate A1 全系列文档的唯一事实源；不得在其他文档中重新定义本文已明确定义的原语。

---

## 1. 编码基础

### 1.1 JCS — Canonical JSON Serialization

所有结构化负载的序列化必须严格遵循 **RFC 8785**（JCS: JSON Canonicalization Scheme），即 ECMAScript 序列化规范定义的全部规则。

**实现要求**：
- UTF-8 编码作为唯一字符集；禁止 BOM（`\uFEFF`）。
- 对象成员名称按 RFC 8785 §3.1 递归排序：成员名以 UTF-16 代码单元升序排列，不保留源文件顺序。
- 数字按 RFC 8785 引用的 ECMAScript `NumberToString` 规则序列化；整数可无小数点，算法要求时允许指数形式，不保留非规范尾随零。
- 字符串中的转义序列按 JCS 规范严格展开；`\u0000` 替代控制字符。
- `null` 是明确值，与缺失键不同；不得将 `null` 等价于缺失键处理。

> **实现约束**：任何将 JCS 输出与手动拼接字符串混合的行为均视为违反本规范。

### 1.1.1 Authority Numeric Input Profile

RFC 8785 仍是 Gate A1 结构化负载的规范序列化规则。Step0 authority 验证器采用更窄的输入剖面，以避免不同语言在 JSON number 解析阶段产生不可见的精度分歧：

- 仅接受 IEEE 754 可安全精确表示的整数，即 `[-9007199254740991, 9007199254740991]`。
- 拒绝浮点数，以及超出上述范围的整数；拒绝发生在 JCS 序列化之前。
- 对于被该剖面接受的文档，authority 产生的规范字节必须与 RFC 8785 实现产生的字节完全一致。
- 该输入剖面不是对 RFC 8785 的替代算法，也不得被解释为允许非 JCS 输出。

### 1.2 ENC — Length-Prefixed Binary Encoding

`ENC(bytes)` 是所有 Merkle 构造和 TypedFP 输入的底层编码函数：

```
ENC(B: Bytes) = uint64_be(length(B)) || B
```

- `uint64_be`：大端无符号 64 位整数，表示后续原始字节序列的精确字节数。
- `||`：字节级联操作。
- `B` 可以为空字节序列；空字节编码为 8 字节零长度前缀 `0x0000000000000000`。

**示例**：
```
ENC("hello") = 0x0000000000000005 || "hello"
ENC("")      = 0x0000000000000000
```

---

## 2. 摘要原语

### 2.0 摘要层级约定

- **digest32**：原始 32 字节 SHA-256 输出，内部类型，不得出现在任何序列化字段中。
- **WireDigest**：ASCII 字符串 `sha256:` + 64 字符 lowerhex，跨边界的唯一合法格式。
- `ContentDigest`、`TypedFP` 内部返回 digest32；进入 JCS 或网络字段前必须转换为 WireDigest。

### 2.1 WireDigest — 网络传输格式

内部摘要使用 **digest32**（原始 32 字节 SHA-256 结果），仅限模块内部使用，不得直接写入文件或网络。

跨组件边界传输时必须表示为 **WireDigest** 格式：

```
WireDigest(digest32) = "sha256:" || lowerhex(digest32)
```

- `lowerhex`：32 字节的 64 个十六进制小写字符（`[0-9a-f]{64}`）。
- 前缀 `sha256:` 是 WireDigest 的标识符，不得省略。
- WireDigest 是纯 ASCII 字符串，不含任何二进制字节。

> **所有摘要字段进入 JCS 序列化时必须为 WireDigest 字符串，不得传入原始 SHA256 字节。**
> **JCS 负载内任何摘要值字段必须为 WireDigest 字符串类型。**

### 2.2 ContentDigest — 原始内容摘要

`ContentDigest` 用于对原始字节流进行无格式摘要，适用于：标准输出、标准错误、可执行 JAR 文件、树结构源文件。

```
ContentDigest(raw) → digest32   // 内部返回原始 32 字节
ContentDigest(raw) → WireDigest // 跨边界时必须包装
```

**约束**：
- 不使用 TypedFP；不包含任何元数据。
- 不经过 ENC 编码；直接对原始字节序列计算 SHA256。
- `stdout`、`stderr`、`jar`、`treeFile` 的 Digest 均采用此原语。

### 2.3 TypedFP — 语义指纹

`TypedFP` 是包含域标签的语义指纹，用于在同一内容不同语义场景下产生独立指纹：

```
TypedFP(domain: String, payload: JSON) =
    SHA256( ENC(ASCII(domain)) || ENC(JCS(payload)) )
    → lowerhex(结果)   // 完整 256 位，不截断
```

**约束**：
- `domain`：ASCII 字符串（如 `"rg.gatea.package.v1"`），大小写敏感。
- `payload`：必须是 JCS 规范化后的 JSON 结构（JSON Object、Array、String 等均可）。
- 输出为完整 256 位（64 字符 lowerhex），不得截断为 128 位或 160 位。
- TypedFP 内部计算返回 digest32（原始 32 字节 SHA-256）；跨组件边界时必须包装为 WireDigest 字符串，不得直接输出 bare lowerhex。

**错误示例（禁止）**：
```
TypedFP("foo", {...})[0..31]      // 禁止截断
TypedFP("Foo", {...})              // 禁止大小写混淆
hex(SHA256(JCS({...})))            // 禁止省略 ENC
```

---

## 3. Merkle 树构造原语

Merkle 树内部节点和叶节点是两种明确区分的专用原语。

### 3.1 MerkleLeaf — 源单元叶

```
MerkleLeaf(domain, type, path, unit) =
    SHA256( ENC(ASCII(domain)) ||
            ENC(ASCII(type))   ||
            ENC(ASCII(path))   ||
            ENC(JCS(unit)) )
    → lowerhex
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `domain` | ASCII String | 协议域标签 |
| `type` | ASCII String | Source unit 类型：`schema` \| `role` \| `rule` \| `evidence` |
| `path` | ASCII String | 源文件相对路径（UTF-8 字节序规范化） |
| `unit` | JSON | 源单元完整 JSON 结构（JCS 规范化） |

### 3.2 MerkleNode — 内部节点

```
MerkleNode(domain, left, right) =
    SHA256( ENC(ASCII(domain)) || left_32_raw || right_32_raw )
    → lowerhex
```

**关键约束**：
- `left_32_raw` 和 `right_32_raw` 是 SHA256 输出直接截取的 32 字节原始二进制。
- 仅在 Merkle 树内部构造时使用原始 32 字节（即 digest32 / raw32）；raw32 仅在树构造过程中流转，不得出现在任何外部接口、文件或网络字段中；输出到 Wire 前必须转换为 WireDigest。
- 不得将 MerkleLeaf 的 WireDigest（64 字符字符串）直接用于 MerkleNode 构造。

### 3.3 树输出规则

Merkle 树根节点（treeRoot）在 Package manifest 中必须以 WireDigest 表示：

```
manifest.treeRoot = WireDigest(MerkleNode(...))
```

---

## 4. 域标签注册表

### 4.1 域标签注册表（v1）

域标签注册表在每个协议版本中是**封闭但可扩展**的集合：
- 已列出的标签为正式标签，实现必须支持。
- 未知域标签（即未在正式列表中出现的标签）必须在解析时以 `E_DOMAIN_UNKNOWN` 拒绝，不得静默接受或回退。
- 新增域标签必须通过规范修订显式添加，不得由个别实现自行引入。

| 域标签 | 版本 | 使用场景 | 规范引用 |
|--------|------|----------|----------|
| `rg.gatea.package.v1` | 1.0 | Source Package manifest TypedFP | §1.1 / 01 §1 |
| `rg.gatea.merkle-node.v1` | 1.0 | Merkle 内部节点摘要 | 01 §2 |
| `rg.gatea.merkle-leaf.v1` | 1.0 | Merkle 叶子节点摘要 | 01 §2 |
| `rg.gatea.artifact-closure.v1` | 1.0 | Artifact Closure TypedFP | 02 §4 |
| `rg.gatea.slice.v1` | 1.0 | Slice Descriptor TypedFP | 02 §4 |
| `rg.gatea.compiler-manifest.v1` | 1.0 | Compiler Manifest TypedFP | 01 §4 |
| `rg.gatea.observation-receipt.v1` | 1.0 | Observation Receipt TypedFP | 02 §4 |
| `rg.gatea.decision-input.v1` | 1.0 | Decision Input TypedFP | 02 §4 |
| `rg.gatea.reducer-output.v1` | 1.0 | Reducer Output TypedFP | 02 §4 |
| `rg.gatea.result.v1` | 1.0 | Result TypedFP | 02 §4 |
| `rg.gatea.ledger-entry.v1` | 1.0 | Ledger Entry TypedFP | 02 §5 |
| `rg.gatea.invocation-key.v1` | 1.0 | Invocation Key TypedFP | 02 §5 |
| `rg.gatea.revocation-payload.v1` | 1.0 | Revocation Payload TypedFP | 02 §6 |
| `rg.gatea.revocation-record.v1` | 1.0 | Revocation Record TypedFP | 02 §6 |
| `rg.gatea.ra-list.v1` | 1.0 | RA List Snapshot TypedFP | 02 §3 / 01 §4 |
| `rg.gatea.ledger-head.v1` | 1.0 | Ledger Head TypedFP | 02 §5 |
| `rg.gatea.genesis.v1` | 1.0 | Genesis Record TypedFP | 02 §5 |
| `rg.gatea.observer-failure.v1` | 1.0 | Observer Failure TypedFP | 03 §9 |
| `rg.gatea.artifact.v1` | 1.0 | Artifact ContentDigest（原始字节） | Gate A1 §3 |

### 4.2 旧实现域标签（仅 Oracle 兼容）

以下域标签存在于历史实现中，**仅保留用于 Oracle 模式兼容**，不得用于新设计：

| 旧域标签 | 旧含义 | 兼容声明 |
|----------|--------|----------|
| `impl.legacy.fingerprint` | SHA256(hex(payload)) | 仅 Oracle 对比时使用；新代码不得生成 |
| `impl.legacy.rawsha256` | raw SHA256 bytes as hex | 仅历史证据回溯；不得作为 WireDigest |

> **重要**：旧实现域标签**不得静默重解释**为 v1 正式标签。两者算法不同（ENC 存在性、JCS 使用）导致指纹结果不可等价。若需要迁移，必须通过显式转换函数处理。

### 4.3 算法版本与升级

- `algorithmVersion` 字段在 manifest 中声明，当前为 `"1.0"`。
- 未来引入 v1.1 时，`algorithmVersion` 变更为 `"1.1"`，域标签注册表同步扩展。
- 不兼容变更（如 ENC 替换为变长编码）必须触发 major 版本升级（`"2.0"`），并发布迁移路径。

---

## 5. 禁止行为

以下行为在任何 Gate A1 组件中均属禁止：

1. **混用摘要类型**：不得将 TypedFP 与 ContentDigest 互换使用；每种场景必须使用指定原语。
2. **截断指纹**：TypedFP 必须输出完整 256 位；不得使用 `FP[0..15]`、`fingerprint.substring(0, 32)` 等操作。
3. **自含指纹**：payload 不得包含正在被计算的 FP 自身（payload must not contain the FP being computed）；嵌套摘要仅在显式声明类型和用途时允许（如 `ContentDigest(TypedFP(...))` 作为中间步骤须在规范中明确指定）。
4. **原始字节入 JCS**：任何摘要字段进入 JSON 结构前必须转换为 WireDigest 字符串。
5. **跨域重解释**：旧实现域标签不得在不声明的情况下被解释为新 v1 标签。

---

## 6. 错误码规范

| 错误码 | 含义 | 触发场景 |
|--------|------|----------|
| `E_JCS_INVALID` | JCS 序列化失败 | JSON 结构不符合 RFC 8785 |
| `E_ENC_MALFORMED` | ENC 编码格式错误 | uint64_be 解析失败或长度不匹配 |
| `E_DIGEST_MISMATCH` | 摘要不匹配 | 验签时实际 digest32 与声明 WireDigest 不一致 |
| `E_DOMAIN_UNKNOWN` | 域标签未注册 | 遇到未在 §4.1 列出的正式域标签 |
| `E_FP_TRUNCATED` | 指纹截断检测 | TypedFP 长度不等于 64 字符 |
| `E_WIRE_MISSING_PREFIX` | WireDigest 缺少 `sha256:` 前缀 | 字段值不以指定前缀开头 |
| `E_MERKLE_RAW_IN_WIRE` | Merkle 内部使用原始字节到 Wire | 树节点以原始字节暴露到外部 |

---

## 7. 测试向量要求

所有实现必须通过以下验证：

1. **UTF-8 一致性**：`JCS({"key":"中文"})` 输出字节与 `UTF8("...")` 字节序列完全一致。
2. **ENC 往返**：对任意字节序列 `B`，`DEC(ENC(B)) == B` 必须成立。
3. **WireDigest 格式**：`WireDigest` 必须通过正则 `^sha256:[0-9a-f]{64}$`。
4. **TypedFP 确定性**：相同 (domain, payload) 在任何实现中产生相同 TypedFP。
5. **Merkle 一致性**：相同源单元集合产生的 Merkle 树根必须相同。
6. **禁止回环**：TypedFP 输出经过 ENC 后不得再次进入 TypedFP 或 MerkleLeaf。

---

## 8. Diff 检查规则

- 所有 `.schema.json`、`.role.json`、`.rule.json`、`.evidence.json` 文件变更必须触发 packageFP 重新计算。
- Diff 工具必须报告 UTF-8 字节级差异，不允许字符归一化掩盖实际编码变更。
- 对比测试向量文件（`*.tv.json`）时，WireDigest 字段采用精确字符串匹配，不允许语义等价判断。

---

## 9. Schema Authority 命名空间隔离

### 9.1 两层 Schema Authority

| Authority | 路径 | 作用域 | 状态 |
|-----------|------|--------|------|
| **LEGACY_GATE_A_WIRE_V1** | `docs/schemas/resource-gateway-capability-studio/` | Oracle 内部 byte-compatible 签封 | 兼容性保留，禁止扩展 |
| **TARGET A1 CANDIDATE** | `docs/schemas/resource-gateway-capability-studio-a1/` | 生产路径 schema 候选事实源 | 13 个 candidate schema 已创建，待 Step0 index attestation 与 fresh review |

### 9.2 LEGACY_GATE_A_WIRE_V1 约束

- `docs/schemas/resource-gateway-capability-studio/` 下所有 `.schema.json` 文件属于 **LEGACY_GATE_A_WIRE_V1**，仅服务于 Oracle 内部兼容性验证。
- protocol-compiler 的编译产物（`compiled/`、`projections/`、`fixtures/`）属于 **LEGACY_GATE_A_WIRE_V1**，不进入任何 release artifact。
- Legacy wrapper objects（如 fingerprint 包装对象、protocol-compilation-manifest 兼容结构）**仅在 Oracle 内部被接受**，不得被生产路径静默消费。
- `capability-studio-gate-a-protocol-compilation-manifest-v1.schema.json` 明确为 **CompilerGoldenOracle 签封 artifact**，其 shape 属于 legacy 兼容性结构，不构成 target A1 compilerManifest 规范。
- JCS test 或acles（如 `canonicalization-oracle` 相关 schema）是独立的 **TrustBehaviorOracle test artifact**，不是 TrustBehaviorOracle manifest 的 schema 事实源。
- **禁止行为**：生产路径不得将 legacy wrapper object 直接 reinterpret 为 target type，必须通过 §9.3 定义的显式迁移映射。
- 普通 test-kit JAR 与 `cli` shaded JAR 继续携带 Legacy schema，以维持现有兼容行为；它们是 Legacy 兼容产品，不是 A1 release product。
- Target A1 schema 通过独立的 `a1-protocol` classifier 分发。该发布物只承载协议定义，不承载 Oracle 实例、可执行文件、fixture、expected output 或签名材料。
- `oracle-manifest-v1.schema.json` 仅定义 NON_RELEASE Oracle manifest 的合法结构。分发该 schema definition 不等于分发 Oracle 实例；Oracle 实例及其可执行和数据材料仍严格禁止进入 release artifact。

### 9.3 迁移映射记录格式

从 LEGACY 到 TARGET 的转换必须产生以下显式映射记录，不得进行 in-place 重新解释：

```json5
{
  "legacySchemaId": "string (LEGACY schema identifier)",
  "legacyRawDigest": "string (WireDigest of legacy serialized form)",
  "targetSchemaId": "string (TARGET A1 candidate schema identifier)",
  "targetWireDigest": "string (WireDigest of target wire form)",
  "transformationVersion": "string (SemVer, e.g. '1.0.0')"
}
```

- `legacyRawDigest`：legacy payload 经 JCS 序列化后的 WireDigest。
- `targetWireDigest`：target payload 经 JCS 序列化后的 WireDigest。
- `transformationVersion`：迁移逻辑版本，用于审计和回滚。

### 9.4 WireDigest 与 Legacy Wrapper 边界

- **Target schemas**（TARGET A1 candidate）：所有摘要字段使用 **bare WireDigest** 字符串（`sha256:` + 64 字符 lowerhex）。
- **Legacy wrapper objects**（LEGACY_GATE_A_WIRE_V1）：可能包含指纹包装对象（如 `{ "algorithm": "SHA256", "value": "..." }`），仅 Oracle 内部接受。
- 生产路径的 JSON Schema 验证必须拒绝 legacy wrapper 格式；target enum 和 pattern 约束确保 bare digest 格式。
- **Domain unknown rejection**：target schema enum 明确列出允许的 schemaId；运行时遇到未登记 schemaId 必须拒绝（错误码：`E_DOMAIN_UNKNOWN`）。

### 9.5 规范性引用约束

本文档（00）定义的密码学原语（ENC、JCS、SHA256、lowerhex、ContentDigest、TypedFP、WireDigest）适用于 **两层 Authority**：

- LEGACY Authority 内部 Oracle 验证使用 legacy 算法（可能不含 ENC、或使用 raw SHA256 hex）。
- TARGET Authority 所有实现**必须**遵循本文档 §1-§8 定义的规范，包括 ENC 前缀和 JCS 序列化。
- 不得将 legacy 算法行为推广为规范要求；本文档是 TARGET Authority 的唯一事实源。
