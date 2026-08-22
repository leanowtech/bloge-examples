# Evidence Receipt and Ledger 可执行规范

> 状态：`DRAFT` | 适用范围：Capability Studio Gate A1 执行器与 Evidence Ledger 服务

---

## 0. 规范性引用

本文档规范性引用 [00-normative-conventions.md](00-normative-conventions.md)，统一使用以下原语：

- **lowerhex**: 十六进制小写字符串编码
- **ENC/JCS/SHA256**: 按 00 定义
- **TypedFP**: 按 00 §2.3 构造，所有 TypedFP 指纹均遵循该规范，不得使用模糊拼接或省略 ENC

---

## 1. Evidence Catalog 严格条目

```
EvidenceCatalogEntry {
  evidenceId:          EvidenceId
  schemaRef:           SchemaRef            // JSON Schema Draft 2020-12 URI
  producerOwner:       PrincipalId           // 证据产生主体
  semanticVerifier:    SemanticVerifierSpec  // §1.1
  artifactRelation:    ArtifactRelation      // RELATES_TO | PROVES | DISPROVES | EXTENDS
  relatedEvidenceRefs: List<UTF-8 String>   // required IDs exact closure，无 orphan/rename
                                              // 绑定: policy.maxEvidenceRefs ∈ ℤ, 1 ≤ value ≤ 10000
                                              // 超出时运行时拒绝: E_EVIDENCE_REF_LIMIT
}
```

### 1.1 Semantic Verifier 规范

```
SemanticVerifierSpec {
  verifierId:    VerifierId
  inputs:       List<UTF-8 String>        // 声明式输入，只读 catalog 冻结值
  policy:       "READ_CATALOG_ONLY"
  clockUsage:   false                      // 禁止 wall/monotonic clock
  randomUsage:  false                      // 禁止 CSPRNG/PRNG（仅约束 verifier 自身运行时）
  networkUsage: false                      // 禁止 HTTP/gRPC/file
  outcomeSource: "REDUCTOR_DERIVED"        // outcome 由 Reducer 派生，运行器不自报
  version:      SemanticVersion             // 冻结版本
}
```

**约束**：Verifier 不得读取未在 `inputs` 声明的 catalog 条目；不得使用 clock、random、network。
`randomUsage == false` 的约束范围**仅及于 verifier 进程自身**，不代表 ledger 或 reducer 侧的随机数使用。

---

## 2. 四态模型

```
SOURCE ──compile──▶ COMPILED ──observe──▶ OBSERVED ──accept/reject──▶ ACCEPTED / REJECTED
```

| 状态 | 含义 | 可写操作 |
|---|---|---|
| `SOURCE` | 原始 artifact | 产生者写入 |
| `COMPILED` | 结构完整性通过 | 追加签名/manifest |
| `OBSERVED` | Observation Receipt，无 decision | 追加 ObservationReceipt |
| `ACCEPTED` | Reducer 返回 ACCEPTED 终端决策，Acceptance Receipt 签发，entry 冻结；REJECTED/ERROR 是独立的终端决策，非 ACCEPTED 状态 | **无写操作** |

转移**单向**，不可逆。

---

## 3. 三核心对象

### 3.1 Observation Receipt（物理事实凭证）

**Payload**（不含自含指纹）:
```
ObservationReceiptPayload {
  artifactRef:    ArtifactRef
  observedAt:    Instant           // 仅可观测性，不参与 Verifier 判定
  observerId:    PrincipalId
  status:        "OBSERVED"        // 无 decision 字段
}
```

**Envelope**（持有自含指纹）:
```
ObservationReceipt {
  observationReceiptFP: WireDigest<TypedFP>  // TypedFP('rg.gatea.observation-receipt.v1', ObservationReceiptPayload)
  payload:             ObservationReceiptPayload
}
```

### 3.2 Reducer（纯函数）

```
Reducer { reducerId: ReducerId; version: SemanticVersion; pure: true; inputs: DecisionInput }
```

**约束**：幂等；禁止文件句柄、网络连接或随机状态。

### 3.3 Acceptance Receipt（调用方持有）

**invocationKeyFP 声明**: `TypedFP('rg.gatea.invocation-key.v1', { ledgerId, invocationKey })`

```
AcceptanceReceipt {
  invocationKey:       InvocationKey
  invocationKeyFP:     WireDigest<TypedFP>  // TypedFP('rg.gatea.invocation-key.v1', { ledgerId, invocationKey })
  decisionInputFP:     WireDigest<TypedFP>  // TypedFP('rg.gatea.decision-input.v1', DecisionInput)
  reducerOutputFP:    WireDigest<TypedFP>  // TypedFP('rg.gatea.reducer-output.v1', ReducerOutput)
  resultFP:           WireDigest<TypedFP>  // TypedFP('rg.gatea.result.v1', Result)
  ledgerEntryFP:      WireDigest<TypedFP>  // TypedFP('rg.gatea.ledger-entry.v1', LedgerEntryPayload)
  status:              ReceiptStatus       // ACCEPTED | REJECTED | ERROR（终端决策）
  issuedAt:            Instant
  issuerId:            PrincipalId         // caller-owned
}
```

> **invocationKeyFP 跨账本隔离**：payload 为 `{ ledgerId, invocationKey }` 二元组，同一原始 invocationKey 在不同 ledgerId 下产生不同 FP；DB 唯一性约束为 `(ledgerId, invocationKey)`。

---

## 4. FP 域标签对照表

按 00 §2.3 构造；payload 结构不含自身 FP 字段。

| FP 名称 | registered domain | payload 结构 |
|---|---|---|
| `observationReceiptFP` | `"rg.gatea.observation-receipt.v1"` | ObservationReceiptPayload |
| `decisionInputFP` | `"rg.gatea.decision-input.v1"` | DecisionInput |
| `reducerOutputFP` | `"rg.gatea.reducer-output.v1"` | ReducerOutput |
| `resultFP` | `"rg.gatea.result.v1"` | Result（见 §4.1） |
| `ledgerEntryFP` | `"rg.gatea.ledger-entry.v1"` | LedgerEntryPayload |
| `invocationKeyFP` | `"rg.gatea.invocation-key.v1"` | `{ ledgerId, invocationKey }` |
| `revocationRecordFP` | `"rg.gatea.revocation-record.v1"` | RevocationRecordPayload |
| `revocationPayloadFP` | `"rg.gatea.revocation-payload.v1"` | RevocationRecordPayload（不含 raSignature、authorityId） |
| `raListFP` | `"rg.gatea.ra-list.v1"` | RaListSnapshot |
| `headFP` | `"rg.gatea.ledger-head.v1"` | LedgerHead snapshot |
| `genesisFP` | `"rg.gatea.genesis.v1"` | `{ domain, packageFP, ledgerId }` |

### 4.1 Result 结构（输入给 resultFP）

```
Result {
  decisionInputFP:           WireDigest<TypedFP>  // TypedFP('rg.gatea.decision-input.v1', DecisionInput)，恰好出现一次
  terminalState:              ReceiptStatus     // closed enum: ACCEPTED | REJECTED | ERROR；由 target acceptance-receipt-v1 schema 强制约束
  normalizedDerivedReason:    UTF-8 String      // 确定性规范化推理理由
  sortedEvidenceRefs:         List<UTF-8 String> // UTF-8 字典序
                                // 绑定: policy.maxEvidenceRefs ∈ ℤ, 1 ≤ value ≤ 10000
                                // 超出时运行时拒绝: E_EVIDENCE_REF_LIMIT
  artifactClosureFP:          WireDigest<TypedFP> // TypedFP('rg.gatea.artifact-closure.v1', artifactClosurePayload)
  reducerVersion:             SemanticVersion
}
```

### 4.2 ContentDigest（非 TypedFP 摘要）

以下字段使用 ContentDigest 而非 TypedFP，仅记录原始字节内容完整性哈希，不携带类型语义：

| 字段 | 说明 |
|------|------|
| `stdoutHash` | stdout 原始字节 SHA-256（ContentDigest） |
| `stderrHash` | stderr 原始字节 SHA-256（ContentDigest） |
| `jarFP` | raw JAR bytes SHA-256（ContentDigest） |
| `frozenInputHash` | decisionInput 中 frozen 输入的原始字节确定性哈希（ContentDigest） |

ContentDigest 统一使用 WireDigest<ContentDigest> 格式（`sha256:` + 64 字符 lowerhex）。**注意**：`artifactClosureFP` = TypedFP(`rg.gatea.artifact-closure.v1`, artifactClosurePayload)，携带 artifact 级联结构语义。

---

## 5. Ledger 访问控制

### 5.1 读取权限模型

Ledger 读取操作（receipt 查询、entry 读取、effectiveStatus 查询、evidence 查询、revocation 查询）均受以下三维权限控制：

| 维度 | 说明 |
|------|------|
| `callerTenantId` | 调用方租户标识 |
| `visibilityPolicyRef` | 可见性策略引用（URN） |
| `role projection` | 调用方持有的 role 列表，按 visibilityPolicy 裁剪后生效 |

**默认策略**：**DENY**——未明确授权的读取请求一律拒绝。

### 5.2 权限检查流程

```
1. 提取 callerTenantId + role projection（从调用方凭证）
2. 加载 visibilityPolicyRef 指向的策略文档
3. 按策略对请求资源类型（receipt/entry/effectiveStatus/evidence/revocation）执行 role 过滤
4. 若无匹配 role -> 返回错误，不得泄露存在性差异
```

### 5.3 错误码

| 错误码 | 含义 | 触发条件 |
|---|---|---|
| `ROLE_NOT_AUTHORIZED` | 调用方无权读取该资源 | callerTenantId + role projection + visibilityPolicyRef 组合不满足读取条件 |

### 5.4 侧信道防护

**禁止泄露 exists 侧信道**：读取操作返回「资源不存在」与「无权访问」必须使用相同响应格式和延迟分布；不得通过响应时间、错误消息差异或状态码区别暗示资源存在性。

---

## 6. Revocation 撤销机制

### 6.1 RevocationRecord 结构

**Payload**（不含自含指纹、RA 签名及 RA 授权绑定）:
```
RevocationRecordPayload {
  ledgerId:              LedgerId
  ledgerEntryFP:         WireDigest<TypedFP>  // 要撤销的 entry ledgerEntryFP
  reasonCode:           UTF-8 String         // 撤销原因码
  timestamp:            Instant
  previousRevocationHeadFP: WireDigest<TypedFP> // 前一个 revocationRecordFP
}
```

**Envelope**（持有自含指纹、RA 签名及签发时授权快照）:
```
RevocationRecord {
  revocationRecordFP:  WireDigest<TypedFP>    // TypedFP('rg.gatea.revocation-record.v1', RevocationRecordPayload)
  payload:            RevocationRecordPayload
  revocationPayloadFP: WireDigest<TypedFP>    // TypedFP('rg.gatea.revocation-payload.v1', RevocationRecordPayload)
  packageFP:          WireDigest<TypedFP>    // TypedFP('rg.gatea.package.v1', manifestEnvelope)
  raListFP:            WireDigest<TypedFP>    // TypedFP('rg.gatea.ra-list.v1', RaListSnapshot)
  raSignature:         Binary                // RA 对 revocationPayloadFP WireDigest 字节的签名
  authorityId:         PrincipalId            // 签发 RA 身份
}
```

**链完整性保证**：`previousRevocationHeadFP` 字段形成单向链；`revocationRecordFP` 的 payload 不含 `revocationRecordFP` 自身；`raSignature` 覆盖 `revocationPayloadFP` 字节及签名算法/KeyId 包络；任何篡改均可通过 FP 链回溯和签名验证检测。

### 6.2 撤销错误码

| 错误码 | 含义 | 触发条件 |
|---|---|---|
| `RA_NOT_AUTHORIZED` | RA 不在授权列表 | authorityId 未在 RA List 中 |
| `RA_SIGNATURE_INVALID` | RA 签名校验失败 | raSignature 无法用 RA 公钥验证 |
| `REVOCATION_DUPLICATE` | 重复撤销同一 entry | 同一 ledgerEntryFP 的 RevocationRecord 已存在 |
| `REVOCATION_UNKNOWN_ENTRY` | 撤销引用的 ledgerEntryFP 不存在 | ledgerEntryFP 未命中 Ledger |

---

## 7. RA（Revocation Authority）授权列表

### 7.1 RA 授权来源唯一性原则

**RA 授权的权威来源单元是 Source Package 本身**（由 `packageFP` 承诺）。Ledger 无独立的可变 RA 列表状态。

具体约束：

- Ledger genesis 固定 ledger 的 `packageFP`（由 genesisFP payload = `{ domain, packageFP, ledgerId }` 绑定）
- 账本中每一条 ledger entry 的 `packageFP` **必须等于** genesis 的 `packageFP`（entry 间 packageFP 一致性）
- RevocationRecord 的 `packageFP` **等于**被撤销目标 entry 的 `packageFP`（或 genesis）
- `raListFP` 从**该 exact package** 解析得出（package 中嵌入的 RaListSnapshot FP）
- 对 RA 授权的 membership 检查和签名验证均相对于该 exact `raListFP`（签发时快照，不溯及既往）

**结论**：不存在 Ledger 内的独立可变 RA 列表。新的 RA snapshot 必须通过新的 Source Package + 新的 ledger genesis 引入；不得在账本内部原地修改 RA 授权。

### 7.2 RaListSnapshot 结构

```
RaListSnapshot {
  version:               SemanticVersion
  registeredAuthorities:  List<RegisteredAuthority>
  snapshotTimestamp:      Instant
  // 不含 packageFP：RaListSnapshot 是完整 authority 源单元，由 packageFP 承诺，不反向引用 packageFP
}
```

### 7.3 授权检查不变式

```
A-RA1: RevocationRecord.authorityId 必须在签发时绑定的 raListSnapshot.registeredAuthorities 中存在
A-RA2: RevocationRecord.raSignature 通过对应 RegisteredAuthority.publicKey 验证 revocationPayloadFP
A-RA3: RevocationRecord.raListFP 匹配签发时的 RaListSnapshot FP（保证 RA 权限快照不可篡改）
A-RA4: RaListSnapshot 整体由 packageFP 承诺（不包含 packageFP 字段，无循环依赖）
```

---

## 8. LedgerEntry 与 AtomicAppend

### 8.1 LedgerEntry 结构

**Payload**（不含自含指纹）:
```
LedgerEntryPayload {
  ledgerId:           LedgerId
  invocationKey:      InvocationKey
  decisionInputFP:    WireDigest<TypedFP>   // TypedFP('rg.gatea.decision-input.v1', DecisionInput)
  reducerOutputFP:    WireDigest<TypedFP>   // TypedFP('rg.gatea.reducer-output.v1', ReducerOutput)
  resultFP:           WireDigest<TypedFP>   // TypedFP('rg.gatea.result.v1', Result)
  status:             ReceiptStatus
  effectiveStatus:   EffectiveStatus        // §8.3
  nonce:             Bytes
  retryOf:            WireDigest<TypedFP>?  // 指向被重试的旧 entry 的 invocationKeyFP
  predecessorHeadFP: WireDigest<TypedFP>   // 前一个 ledgerEntryFP（genesis 为 null）
  packageFP:         WireDigest<TypedFP>    // TypedFP('rg.gatea.package.v1', manifestEnvelope)
}
```

**Envelope**（持有自含指纹）:
```
LedgerEntry {
  ledgerEntryFP: WireDigest<TypedFP>  // TypedFP('rg.gatea.ledger-entry.v1', LedgerEntryPayload)
  payload:       LedgerEntryPayload
}
```

### 8.2 FrozenInputs 结构（重试范围校验用）

```
FrozenInputs {
  decisionInputFP:   WireDigest<TypedFP>  // TypedFP('rg.gatea.decision-input.v1', DecisionInput)
  packageFP:         WireDigest<TypedFP>  // TypedFP('rg.gatea.package.v1', manifestEnvelope)
  sliceFP:           WireDigest<TypedFP>?  // TypedFP('rg.gatea.slice.v1', sliceDescriptor)（若使用 slice）
  frozenInputHash:   WireDigest<ContentDigest> // decisionInput 中 frozen 输入的确定性内容哈希
}
```

### 8.3 effectiveStatus 状态枚举

```
EffectiveStatus =
  | ACCEPTED
  | REJECTED
  | ERROR
  | ACCEPTED/REVOKED
  | REJECTED/REVOKED
  | ERROR/REVOKED
```

### 8.4 转移表

| 转移 | 触发 | 副作用 |
|---|---|---|
| compile | 结构校验通过 | 签名/manifest 追加 |
| observe | ObservationReceipt 签发 | ObservationReceipt 持久化 |
| accept | DecisionInputFP + ReducerOutputFP 就绪 | AcceptanceReceipt 签发，Ledger AtomicAppend |
| reject | Reducer REJECTED | Ledger AtomicAppend，状态 REJECTED |
| error | Reducer 异常 | 新 nonce + retryOf 新 entry 追加，旧 ERROR entry 保留 |
| revoke | RA 追加 RevocationRecord | effectiveStatus 派生成 REVOKED 变体 |

### 8.5 invocationKey 唯一性作用域

**唯一性约束**：`invocationKey` 在 `(ledgerId, invocationKey)` 二元组上唯一（DB 索引）。

- 同一 ledgerId 内不允许重复 invocationKey
- 不同 ledgerId 可使用相同 invocationKey（作用域隔离）
- 违反唯一性约束时返回 `DUPLICATE_INVOCATION`

### 8.6 ERROR 重试校验

ERROR 重试请求必须验证以下条件：

| 校验项 | 错误码 | 说明 |
|--------|--------|------|
| 原 entry 未被 revocation | `RETRY_SOURCE_REVOKED` | 被重试的 ledgerEntryFP 对应 effectiveStatus 不含 REVOKED |
| ledgerId 相同 | `RETRY_SCOPE_MISMATCH` | 重试请求 ledgerId 必须等于原 entry ledgerId |
| packageFP 相同 | `RETRY_SCOPE_MISMATCH` | 重试请求 packageFP 必须等于原 entry packageFP |
| sliceFP 相同（如适用） | `RETRY_SCOPE_MISMATCH` | 重试请求 sliceFP 必须等于原 entry sliceFP（若原 entry 有 slice） |
| frozenInputs 相同 | `RETRY_SCOPE_MISMATCH` | decisionInput 中 frozen 输入的确定性哈希必须一致 |

**校验顺序**：先校验 revocation，再校验 scope 一致性。

---

## 9. 错误码

| 错误码 | 含义 | 触发条件 |
|---|---|---|
| `HEAD_MISMATCH` | CAS 校验失败 | expectedHeadFP / expectedRevision 不等于当前 head |
| `DUPLICATE_INVOCATION` | invocationKey 重复 | (ledgerId, invocationKey) 唯一索引冲突 |
| `INTERNAL_ERROR` | 事务异常 | DB 写入失败（不由用户导致） |
| `INVALID_FP` | TypedFP 格式校验失败 | lowerhex 长度/字符不符合规范 |
| `NONCE_TOO_SHORT` | nonce 长度不足 | nonce.bits < 128 或 nonce.value.length < 16 |
| `HASH_TRUNCATED` | 哈希输出被截断 | SHA256 输出不足 256 bits |
| `UNREACHABLE_STATE` | 状态转移违反有向图 | 状态机校验失败 |
| `E_EVIDENCE_REF_LIMIT` | evidence 引用数量超限 | relatedEvidenceRefs 或 sortedEvidenceRefs 超出 policy.maxEvidenceRefs（1..10000） |
| `REVOCATION_HEAD_MISMATCH` | Revocation log CAS 失败 | expectedRevocationHeadFP / expectedRevocationRevision 不匹配 |
| `RA_NOT_AUTHORIZED` | RA 不在授权列表 | authorityId 未在签发时绑定的 raListSnapshot 中 |
| `RA_SIGNATURE_INVALID` | RA 签名校验失败 | raSignature 无法用 RA 公钥验证 |
| `REVOCATION_DUPLICATE` | 重复撤销同一 entry | 同一 ledgerEntryFP 的 RevocationRecord 已存在 |
| `REVOCATION_UNKNOWN_ENTRY` | 撤销引用的 ledgerEntryFP 不存在 | ledgerEntryFP 未命中 Ledger |
| `ROLE_NOT_AUTHORIZED` | 调用方无权读取资源 | callerTenantId + visibilityPolicyRef + role projection 不满足读取条件 |
| `RETRY_SOURCE_REVOKED` | 被重试的 entry 已撤销 | retryOf 引用的原 entry effectiveStatus 含 REVOKED |
| `RETRY_SCOPE_MISMATCH` | 重试 scope 参数不一致 | ledgerId / packageFP / sliceFP / frozenInputs 与原 entry 不一致 |
| `E_TERMINAL_STATE_MISMATCH` | Result.terminalState 与 ReducerOutput.terminalStatus 不一致 | Result.terminalState != ReducerOutput.terminalStatus，或 Result.terminalState != AcceptanceReceipt.status |

---

**跨文档同步**：验收断言与 01 §2.4/§2.5、03 §9 的断言列表保持一致；错误码仅在本章 §9 定义。

## 10. 正向与攻击验收矩阵

| ID | 场景 | 预期结果 |
|---|---|---|
| T-001 | 合法路径（有效 DecisionInput + 幂等 Reducer） | ACCEPTED，AtomicAppend 成功 |
| T-002 | Reducer REJECTED | REJECTED，AtomicAppend 成功 |
| T-003 | Reducer 异常 | 新 nonce + retryOf 重试，旧 ERROR entry 保留，无覆盖 |
| T-004 | 并发竞争（同 expectedHead） | 一个成功，其余 HEAD_MISMATCH，零写 |
| T-005 | 重放 invocationKey（同 ledgerId） | DUPLICATE_INVOCATION，零写 |
| T-006 | Verifier 读取未声明 evidence | READ_CATALOG_VIOLATION，rejected |
| T-007 | RA 签发 RevocationRecord | 原 entry 不变，查询派生 REVOKED |
| T-008 | RA 不在签发时绑定的 raListSnapshot 中 | RA_NOT_AUTHORIZED，拒绝追加 |
| T-009 | RA 签名无效 | RA_SIGNATURE_INVALID，拒绝追加 |
| T-010 | 重复撤销同一 entry | REVOCATION_DUPLICATE，拒绝追加 |
| T-011 | ERROR 重试原 entry 已 REVOKED | RETRY_SOURCE_REVOKED，拒绝 |
| T-012 | ERROR 重试 ledgerId 不一致 | RETRY_SCOPE_MISMATCH，拒绝 |
| T-013 | 读取资源 caller 无权访问 | ROLE_NOT_AUTHORIZED，不泄露 exists 侧信道 |
| T-014 | 有效 RA 追加 RevocationRecord（valid RA 签名 + authority 在 raListSnapshot 中） | AtomicAppend 成功，原 entry 派生 effectiveStatus REVOKED，零覆盖 |
| T-015 | RA 签名无效 | RA_SIGNATURE_INVALID，拒绝追加，零写 |
| T-016 | RA authority 不在签发时快照的 raListSnapshot 中 | RA_NOT_AUTHORIZED，拒绝追加，零写 |
| T-017 | genesis RevocationRecord：previousRevocationHeadFP 为空/null，绑定有效 ledgerEntryFP | AtomicAppend 成功，genesis 派生 effectiveStatus REVOKED |
| T-018 | RevocationRecord 签发：原 LedgerEntry 不变，查询派生 effectiveStatus REVOKED | 原 entry 内容不变，零覆盖，仅 effectiveStatus 派生为 REVOKED |
| T-019 | LedgerEntry effectiveStatus 全量派生：ACCEPTED→REVOKED；REJECTED→REVOKED；ERROR→REVOKED | 各场景下原 entry 状态保留，effectiveStatus 均派生为 REVOKED |
| T-020 | ERROR retry：相同 ledgerId+packageFP+sliceFP+frozenInputs，new nonce + retryOf | AtomicAppend 成功，旧 ERROR entry 保留，无覆盖 |
| T-021 | ERROR retry source entry 已 REVOKED | RETRY_SOURCE_REVOKED，拒绝追加，零写 |
| T-022 | ERROR retry ledgerId 与原 entry 不一致 | RETRY_SCOPE_MISMATCH，拒绝追加，零写 |
| T-023 | relatedEvidenceRefs 数量等于 policy.maxEvidenceRefs（边界值） | AtomicAppend 成功 |
| T-024 | relatedEvidenceRefs 数量等于 policy.maxEvidenceRefs+1 | E_EVIDENCE_REF_LIMIT，拒绝追加，零写 |
| T-025 | previousRevocationHeadFP 指向 prior revocationRecordFP（正确域标签）→AtomicAppend 成功；previousRevocationHeadFP 指向错误域标签的 FP →INVALID_FP，拒绝追加，零写 |
| ATK-001 | 重放旧 expectedHeadFP | HEAD_MISMATCH，零写 |
| ATK-002 | 重复消费 invocationKey（同 ledgerId） | DUPLICATE_INVOCATION，零写 |
| ATK-003 | nonce < 128 位 | NONCE_TOO_SHORT，拒绝 |
| ATK-004 | SHA256 输出截断 | HASH_TRUNCATED，拒绝 |
| ATK-005 | Verifier 访问 network/clock/random | READ_CATALOG_VIOLATION，拒绝 |
| ATK-006 | 直接修改 ACCEPTED entry | entry 不可变，查询派生 REVOKED |
| ATK-007 | read-then-write TOCTOU | 事务原子性保证，零写 |
| ATK-008 | 伪造 RA 签名追加 RevocationRecord | RA_SIGNATURE_INVALID，拒绝 |
| ATK-009 | revocation 引用未知 ledgerEntryFP | REVOCATION_UNKNOWN_ENTRY，拒绝 |
| ATK-010 | 构造包含 revoked entry 的新 invocationKey | 原 entry REVOKED 不影响新 entry（范围隔离） |
| ATK-011 | 通过响应时间差异推断 exists 侧信道 | 相同延迟分布，无侧信道泄露 |
| ATK-012 | 跨 ledgerId 重放 invocationKey（相同 raw invocationKey） | 允许；因 invocationKeyFP payload 含 ledgerId，跨账本产生不同 invocationKeyFP；DB 唯一性约束 (ledgerId, invocationKey) 不冲突 |
| ATK-013 | Result.terminalState 与 ReducerOutput.terminalStatus 不一致 | E_TERMINAL_STATE_MISMATCH，拒绝写入 Ledger Entry |
| ATK-014 | previousRevocationHeadFP 指向非 revocation-record.v1 域的 FP（如 ledger-head.v1） | INVALID_FP，拒绝追加 RevocationRecord |

---

## 11. 机器可检查断言

```
A1:  relatedEvidenceRefs 形成 exact closure，无 orphan
A2:  semanticVerifier.networkUsage == false
A3:  semanticVerifier.clockUsage == false
A4:  semanticVerifier.randomUsage == false   // 仅约束 verifier 进程自身
A5:  resultFP 中 decisionInputFP 出现次数 == 1
A6:  nonce.bits >= 128 且 nonce.value.length >= 16
A7:  SHA256 输出长度 == 256 bits（不截断）
A8:  Ledger AtomicAppend 事务原子（commit 或 rollback，无部分写）
A9:  RevocationRecord 不修改原 entry 状态，append-only
A10: invocationKey 在 (ledgerId, invocationKey) 二元组上唯一（DB 约束）
A11: 所有 TypedFP 构造遵循 00 §2.3；02 不再重印构造公式
A12: AtomicAppend 中，head 比较与更新在同一 serializable DB 事务内完成，commit 或 rollback（无 TOCTOU）
A13: RevocationAppendTransaction 有独立 expectedRevocationHeadFP/expectedRevocationRevision CAS
A14: RevocationRecord 的 raSignature 通过 RA 公钥对 revocationPayloadFP 验证
A15: RevocationRecord 的 authorityId 在签发时绑定的 raListSnapshot.registeredAuthorities 中
A16: effectiveStatus 取值在 {ACCEPTED, REJECTED, ERROR,
                              ACCEPTED/REVOKED, REJECTED/REVOKED, ERROR/REVOKED} 之内
A17: ERROR 重试 entry 的 retryOf 字段指向被重试的旧 entry 的 invocationKeyFP
A18: genesis 起源时 predecessorHead == null，genesisFP payload = {domain, packageFP, ledgerId}
A19: RevocationRecord 不含 referencedRevisions 字段；链完整性由 previousRevocationHeadFP 单向链保证
A20: RaListSnapshot 以 raListFP 形式进入 Source Package 并由 packageFP 承诺
A21: RevocationRecord 绑定签发时的 raListFP（由对应 packageFP 解析得出）
A22: ERROR 重试前校验原 entry effectiveStatus 不含 REVOKED
A23: ERROR 重试校验 (ledgerId, packageFP, sliceFP, frozenInputs) 与原 entry 一致
A24: 读取操作返回 ROLE_NOT_AUTHORIZED 时不泄露资源是否存在（侧信道防护）
A25: relatedEvidenceRefs 和 sortedEvidenceRefs 列表长度不超过 policy.maxEvidenceRefs（1..10000）；
     超出时运行时拒绝 E_EVIDENCE_REF_LIMIT
A26: Result.terminalState == AcceptanceReceipt.status
A27: Result.terminalState == ReducerOutput.terminalStatus
A28: previousRevocationHeadFP == RevocationRecord.revocationRecordFP 或 genesis/null sentinel（首个 revocation 的起点）
```

---

## 12. Adapter Mode 与 Release Acceptance 约束

### 12.1 Adapter Mode 枚举

```
AdapterMode = EXTERNAL_CAS_RELEASE | NON_RELEASE_ROLLBACKABLE_ADAPTER
```

| Mode | 含义 | Release Acceptance 能力 |
|------|------|------------------------|
| `EXTERNAL_CAS_RELEASE` | 外部 CAS 发布模式，可产生 release evidence | **可产生 ACCEPTED release evidence** |
| `NON_RELEASE_ROLLBACKABLE_ADAPTER` | 非发布回滚适配器，仅处理 NON_RELEASE 工件 | **禁止产生任何终端决策（ACCEPTED、REJECTED、ERROR）的 release evidence** |

### 12.2 Release Acceptance 强制规则

**规则 R1**：`EXTERNAL_CAS_RELEASE` adapter 才能 emit `ACCEPTED` 状态。

- 仅 `adapterMode == EXTERNAL_CAS_RELEASE` 时，执行器才可将 Reducer 返回的 `ACCEPTED` 终端决策写入 Ledger Entry。
- `NON_RELEASE_ROLLBACKABLE_ADAPTER` 执行器**只能** emit `NON_RELEASE` observation，不得 emit 任何终端决策（ACCEPTED、REJECTED、ERROR）的 release evidence。
- `EXTERNAL_CAS_RELEASE` 可 emit 所有终端决策（ACCEPTED、REJECTED、ERROR）。

**规则 R2**：`NON_RELEASE_ROLLBACKABLE_ADAPTER` 不得绕过 release evidence 约束。

- adapter 必须在其配置或 manifest 中声明 `adapterMode`。
- 执行器在 emit evidence 前必须校验 adapterMode 与目标 evidence 类型的兼容性。
- 不兼容组合触发错误码 `E_ADAPTER_MODE_INCOMPATIBLE`，拒绝写入 Ledger。

**规则 R3**：Domain unknown 由 target schema enum 拒绝。

- Target `acceptance-receipt-v1` schema 的 `status` 字段使用 closed enum `["ACCEPTED", "REJECTED", "ERROR"]`。
- 运行时遇到未在 enum 中列出的 status 值，JSON Schema 验证器拒绝该 payload，错误码 `E_DOMAIN_UNKNOWN`。
- `NON_RELEASE` 不是 target acceptance receipt 的合法 status 值。

### 12.3 Assertion 与 Test Cases

**Assertion A29**：`adapterMode == EXTERNAL_CAS_RELEASE` 才能写入 `effectiveStatus ∈ {ACCEPTED, REJECTED, ERROR}` ledger entry。

**Assertion A30**：`adapterMode == NON_RELEASE_ROLLBACKABLE_ADAPTER` 写入的 ledger entry 的 `effectiveStatus` 不得为 ACCEPTED、REJECTED 或 ERROR（即不得写入任何终端决策的 release ledger entry）。

**Assertion A31**：所有 adapter 配置必须在 manifest 中声明 `adapterMode`，缺失时默认拒绝。

| Test ID | 场景 | 预期结果 |
|---------|------|----------|
| T-026 | EXTERNAL_CAS_RELEASE adapter 提交 ACCEPTED decision | Ledger entry 写入 ACCEPTED，成功 |
| T-027 | NON_RELEASE_ROLLBACKABLE_ADAPTER 提交 ACCEPTED decision | 拒绝，E_ADAPTER_MODE_INCOMPATIBLE，零写 |
| T-028 | NON_RELEASE_ROLLBACKABLE_ADAPTER 提交 REJECTED decision | 拒绝，E_ADAPTER_MODE_INCOMPATIBLE，零写 |
| T-029 | 缺失 adapterMode 声明的 adapter 尝试写入 | 拒绝，E_ADAPTER_MODE_INCOMPATIBLE，零写 |
| T-030 | Adapter 写入 status 不在 enum 中的 entry | JSON Schema 验证失败，E_DOMAIN_UNKNOWN，零写 |
| T-031 | NON_RELEASE_ROLLBACKABLE_ADAPTER 提交 ERROR decision | 拒绝，E_ADAPTER_MODE_INCOMPATIBLE，零写 |
| T-032 | Result.terminalState 与 AcceptanceReceipt.status 不一致 | 拒绝，E_TERMINAL_STATE_MISMATCH，零写 |

### 12.4 错误码扩展

| 错误码 | 含义 | 触发场景 |
|--------|------|----------|
| `E_ADAPTER_MODE_INCOMPATIBLE` | adapterMode 与目标 evidence 类型不兼容 | NON_RELEASE adapter 尝试写入任何终端决策（ACCEPTED、REJECTED、ERROR）的 release evidence |
| `E_DOMAIN_UNKNOWN` | status 值不在 target schema closed enum 中 | 运行时遇到未登记的 domain/schemaId/status 值 |
| `E_TERMINAL_STATE_MISMATCH` | Result.terminalState 与 ReducerOutput/AcceptanceReceipt 不一致 | Result.terminalState != ReducerOutput.terminalStatus 或 Result.terminalState != AcceptanceReceipt.status |

### 12.5 与 Legacy Oracle 的兼容性边界

- LEGACY_GATE_A_WIRE_V1 的 adapter wrapper objects 可能包含 `adapterMode` 的 legacy 字段名称。
- Legacy wrapper 仅在 Oracle 内部被接受；target 生产路径**仅接受** `EXTERNAL_CAS_RELEASE` 或 `NON_RELEASE_ROLLBACKABLE_ADAPTER` 二元 enum。
- 迁移映射（见 04 §2.2）必须将 legacy adapterMode 值显式映射到 target enum 值。
