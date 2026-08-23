# CompiledAcceptancePlan v1 静态验收中间表示设计

**版本**: 1.0.0-Proposed
**状态**: 待审
**适用范围**: Phase1 Compiler 输出契约、Verifier 独立验证契约
**Schema**: `schemas/resource-gateway-capability-studio/capability-studio-compiled-acceptance-plan-v1.schema.json`
**验证结果**: `schemas/resource-gateway-capability-studio/capability-studio-compiled-plan-verification-result-v1.schema.json`

---

## 1. IR 性质

CompiledAcceptancePlan v1 是 compile-only deterministic lossless static acceptance IR：

- 不代表执行结果
- 不产生 PASS/ACCEPTED 判定
- 本模块不修改外部 formalPassCount，正式进度仍 0/27
- Runner 消费经过独立 Verifier 验证一致的 IR

---

## 2. 输入契约

### 2.1 Caller 提供

| 输入 | 约束 |
|------|------|
| `planBytes` | Caller bounded 的 AcceptancePlan 序列化字节；不含外部引用 |
| `catalogBytes` | Caller bounded 的 ContractCatalog 序列化字节；不含网络查找 |

**大小约束**：各 ≤ 1MiB

### 2.2 Compiler/Verifier 内部加载

以下资源由 Compiler/Verifier 各自从 packaged resources / closed code 加载，Caller 不提供：

- `profile`：schemaVersion/profileId/expectedCatalogSemanticFingerprint/expectedPrimitiveRegistryFingerprint/phaseOrder/allowedEffectClasses/primitiveDescriptors/barriers
- `registry`：OperatorRegistry descriptor 快照
- `schemas`：JSON Schema 集合

---

## 3. Catalog 结构约束

catalog root 字段（来自 `schemas/resource-gateway-capability-studio/capability-studio-contract-catalog-v1.schema.json`）：

- `stageExitContracts[27]`：stageExit contract entries
- `acStandards[9]`：ac standard entries
- `feltObligations[14]`：felt obligation entries
- `canonicalCases[9]`：canonical case entries
- `suiteRuns[3]`：suite run entries
- `matrixCells[27]`：matrix cell entries

`evidenceRoles`/`ownerRoles`/`externalFactRequirements` 在每个 contractEntry 内，不是 catalog root 字段。

---

## 4. Profile 结构约束

profile 真实字段（由 Compiler/Verifier 内省）：

- `schemaVersion`：profile schema 版本标识
- `profileId`：profile 唯一标识
- `expectedCatalogSemanticFingerprint`：catalog semantic 期望值
- `expectedPrimitiveRegistryFingerprint`：registry fingerprint 期望值
- `phaseOrder[]`：phase 声明顺序
- `allowedEffectClasses[]`：允许的 effect class 集合
- `primitiveDescriptors[]`：primitive 定义
- `barriers[]`：barrier 定义，含 barrierId 和 phases[]（不是 phase）

---

## 5. IR 结构

### 5.1 compiledPrimitive

```
primitiveId: string (required)
typeId: string (required)
revision: integer (required, >=1)
effectClass: enum (required)
phase: enum (required)
dependsOn: string[] (required, can be empty)
inputSlot: enum (optional)
```

- `primitiveId`：派生自 plan primitive.id
- `typeId`：派生自 plan primitive.typeId，与 profile descriptor 交叉验证
- `revision`：integer，派生自 plan primitive.revision
- `effectClass`：派生自 profile descriptor
- `phase`：派生自 profile descriptor
- `dependsOn`：required 数组，可空
- `inputSlot`：optional

### 5.2 phaseBarrier

```
barrierId: enum (required)
phases: string[] (required, unique, minItems: 1, maxItems: 10, ordered)
```

- `phases` 是 required 数组，unique、minItems: 1、maxItems: 10
- 完整保留 profile 声明的 phases 数组及顺序

### 5.3 expectedEvidenceRoles item

```
role: enum (required)
contractIds: string[] (required, nonempty unique, minLength: 1, maxLength: 50, sorted by contractId)
```

- 按 role 聚合 catalog 中的 contractId
- contractIds 必须 nonempty、unique、max 50、升序

### 5.4 oracleBindings item

```
contractId: string (required, max 50 items)
oracleId: string (required)
```

- catalog contractEntry 含 oracleId
- contractId 派生自 catalog
- 精确 50 项（当前 catalog 每 contract 一 oracle）

### 5.5 根对象指纹字段

| 字段 | 指纹域 |
|------|--------|
| planSourceSemanticFingerprint | RG-CS-PLAN-SOURCE-SEMANTIC-v1 |
| catalogRawFingerprint | RG-CS-CATALOG-RAW-v1 |
| catalogSemanticFingerprint | RG-CS-CATALOG-SEMANTIC-v1 |
| compilerProfileRawFingerprint | RG-CS-COMPILER-PROFILE-RAW-v1 |
| primitiveRegistryFingerprint | RG-CS-PRIMITIVE-REGISTRY-v1 |
| compiledPlanFingerprint | RG-CS-COMPILED-PLAN-v1 (self-excluding) |

---

## 6. 派生算法

### 6.1 算法顺序

```
1. bounded parse (reject >1MiB)
2. strict duplicate KEY detection in parser
3. JSON Schema validation (planBytes/catalogBytes)
4. duplicate ID detection as semantic validation (separate step)
5. ID extraction: contractId/typeId/revision/barrierId/phase/canonicalCaseId/suiteRunId/matrixCellId
6. profile/registry binding: typeId/revision/effectClass/phase 交叉验证
7. topology analysis: DAG 构建 + 循环检测
8. stable topological sort: 派生 executionOrder (见 §6.2)
9. catalog semantic binding: contractId -> role/contractIds/oracleId
10. canonical IR assembly: 按 §7 规范化
11. Domain2 fingerprints: 所有指纹字段
12. schema validation: IR 经 JSON Schema 验证后交付
```

### 6.2 executionOrder 稳定排序规则

executionOrder 按以下规则派生：

1. 按 profile.phaseOrder 分组
2. 在每组内，按 phase index 排序
3. 在可入队节点中，按 primitive ID 升序

**禁止**：按 source executionOrder 原始顺序排列（否则 permutation 不确定）

### 6.3 错误码

对齐 `schemas/resource-gateway-capability-studio/capability-studio-compiled-plan-verification-result-v1.schema.json` 的 INVALID_* reasonCode：

| reasonCode | 条件 |
|------------|------|
| INVALID_SCHEMA | JSON Schema 验证失败 |
| INVALID_PLAN_STRUCTURE | plan 结构异常或 duplicate ID |
| INVALID_TOPOLOGY_CYCLE | primitive 依赖图含循环 |
| INVALID_TOPOLOGY_UNKNOWN_NODE | dependsOn 引用了不存在的 primitiveId |
| INVALID_REGISTRY_TYPE_NOT_FOUND | typeId 未在 registry 中登记 |
| INVALID_REGISTRY_REVISION_MISMATCH | plan revision 与 registry descriptor 不符 |
| INVALID_BARRIER_BYPASS | barrier 定义冲突 |
| INVALID_COLLECTION_SIZE | catalog 数组长度不符合常量约束 |
| INVALID_CATALOG_SEMANTICS | catalog semantics 无法解析 |
| INVALID_FINGERPRINT_MISMATCH | fingerprint 验证失败 |
| INVALID_STAGE_EXIT_CONTRACT_COUNT | stageExitContractCount != 27 |
| INVALID_TAMPERED_PLAN | plan 被篡改 |

### 6.4 错误结构

```json
{
  "status": "INVALID",
  "reasonCode": "INVALID_XXX",
  "reasonField": "string (RFC 6901 JSON pointer, max 512 chars)"
}
```

---

## 7. 规范化规则

### 7.1 Domain2 指纹

```
Domain2(domain: String, payload: Bytes) =
    SHA256( UTF8(domain) || UINT32_BE(payload.length) || payload )
```

- `UINT32_BE`：大端无符号 32 位整数，max 2^32-1
- 1MiB 上限保证 Java int 安全
- domain 不 length-prefix

### 7.2 Fingerprint 域

| 域 | 算法 | 输入 |
|---|------|------|
| RG-CS-PLAN-SOURCE-SEMANTIC-v1 | Domain2 | plan bytes 按 canonical JSON |
| RG-CS-CATALOG-RAW-v1 | Domain2 | catalogBytes exact bytes |
| RG-CS-CATALOG-SEMANTIC-v1 | Domain2 | catalog 按 §7.3 规范化 |
| RG-CS-COMPILER-PROFILE-RAW-v1 | Domain2 | profile bytes exact |
| RG-CS-PRIMITIVE-REGISTRY-v1 | Domain2 | registry 按 canonical array 规范化 |
| RG-CS-COMPILED-PLAN-v1 | Domain2 | IR body (不含 compiledPlanFingerprint) |

### 7.3 Catalog Semantic Canonicalization

catalogSemanticFingerprint 计算规范：

- `stageExitContracts/acStandards/feltObligations`：按 contractId 升序
- `canonicalCases`：按 canonicalCaseId 升序
- `suiteRuns`：按 suiteRunId 升序
- `matrixCells`：按 matrixCellId 升序
- **每个 contractEntry 内**：`evidenceRoles[]`、`ownerRoles[]`、`externalFactRequirements[]` 升序
- 数组序列化为 canonical JSON（含 `[]` 和逗号分隔）

### 7.4 Registry Canonicalization

registryFingerprint 计算规范：

- descriptor 按 typeId 升序
- 每个 descriptor 内的 `inputKinds[]`、`outputEvidenceKinds[]`、`capabilityRequirements[]` 升序
- 数组序列化为 canonical JSON

### 7.5 Object Keys 排序

所有 JSON object 的 keys 按 **Jackson 内部排序规则**（UTF-16 代码单元升序）排列。

### 7.6 Compiled IR 数组排序规则

| 数组字段 | 排序规则 |
|----------|----------|
| primitiveContracts | by primitiveId；同 primitiveId 则 dependsOn 升序 |
| phaseBarriers | 保持 profile.barriers 声明顺序；phases 保序 |
| executionOrder | 保序 |
| expectedEvidenceRoles | by role；contractIds 升序 |
| oracleBindings | by contractId |

### 7.7 Semantic Normalization

- **不 trim/lowercase 任何字符串**
- 枚举值 exact 保留
- ID exact 保留原始值
- 仅按明确规则排序，不改变内容

---

## 8. Independent Verifier

### 8.1 设计原则

Verifier 独立实现，不调用 Compiler：

1. 接收 CompiledAcceptancePlan IR 和原始 planBytes/catalogBytes
2. 独立重做所有派生计算
3. 逐字段比较派生结果与 IR 声明值
4. 输出 VERIFIED/INVALID/UNAVAILABLE

### 8.2 验证项目

| 验证项 | 方法 |
|--------|------|
| compiledPlanFingerprint | Domain2(IR body) == IR.compiledPlanFingerprint |
| primitiveContracts 完整性 | 独立派生每个 primitive |
| phaseBarriers 完整性 | 独立从 profile 提取 barriers/phases 并验证保序 |
| executionOrder | 独立执行 phase 分组 + primitive ID 排序并验证 |
| catalog count 常量 | stageExitContractCount==27, acStdCount==9, feltObligationCount==14, canonicalCases==9, suiteRuns==3, matrixCells==27 |
| expectedEvidenceRoles | 独立聚合 contractEntry.evidenceRoles -> role/contractIds[] |
| oracleBindings | 独立从 contractEntry 派生 contractId/oracleId |
| forbidden producer guess | 不派生 catalog 无法证明的 producerPrimitiveId |
| raw whitespace/ref mismatch | catalogRawFingerprint == Domain2(catalogBytes) |
| formal 0/27 | IR 不声明 formalPassCount 字段 |

### 8.3 VERIFIED 语义

VERIFIED 仅表示：Verifier 独立重算的派生结果与 IR 中声明值字节级一致。

---

## 9. Catalog/Registry 候选哈希

### 9.1 Catalog Semantic

```
sha256:f20774c84f7f34cb0f95c9bb6f5061e048d94b2463da3ca20b560e338cdb7b4d
```

来源：catalog 按 §7.3 规范化后（含 contractEntry 内 evidenceRoles/ownerRoles/externalFactRequirements 排序）的 Domain 2.5（catalog semantic）

### 9.2 Registry

```
sha256:15ea38ddeda10a7befb280efc4fdefb74503036cc06d55775da09665ae4c2686
```

来源：registry descriptor canonical JSON array（typeId 升序，3 个 set-like 数组升序）的 Domain 5（primitive registry）

### 9.3 CatalogRef 同步规则

catalog 有两种变化的语义场景：
1. catalog 仅 whitespace/key-order 变化 → semantic 不变、raw 变化
2. catalog 内容变化 → semantic 变化、raw 变化

| 场景 | catalogSemanticFingerprint | catalogRawFingerprint | planSourceSemanticFingerprint | compiledPlanFingerprint |
|------|--------------------------|---------------------|----------------------------|-------------------------|
| catalog 仅 whitespace/key-order 变化，plan.catalogRef 未同步 | 不变 | 变化 | 不变 | —（catalogRefVerified=false，编译被拒绝，无 compiled plan 输出） |
| catalog 仅 whitespace/key-order 变化，plan.catalogRef 同步更新 | 不变 | 变化 | 变化（plan.catalogRef 已改） | 变化 |
| catalog 内容变化，plan.catalogRef 未同步 | 变化 | 变化 | 不变 | —（catalogRefVerified=false，编译被拒绝，无 compiled plan 输出） |
| catalog 内容变化，plan.catalogRef 同步更新 | 变化 | 变化 | 变化（plan.catalogRef 已改） | 变化 |

### 9.4 Profile Expected 值更新

当 catalog semantic 或 registry 变更时：

- profile 的 `expectedCatalogSemanticFingerprint` 必须更新
- profile 的 `expectedPrimitiveRegistryFingerprint` 必须更新
- profile exact raw bytes 必然变化
- catalog raw 内容不变
- 新值需在双 oracle 复算后更新 golden 向量

---

## 10. ExecutionBindingPlan 边界

ExecutionBindingPlan 是未来运行时绑定边界概念：

- 负责 typeId/verifierId/provider artifact 的运行时绑定
- 具体接口定义和 fingerprint 方案待 Runner 实现阶段确定
- v1 IR 不包含 producerPrimitiveId/runtime binding

---

## 11. Migration 影响

### 11.1 Schema 修订

现有 `schemas/resource-gateway-capability-studio/capability-studio-compiled-acceptance-plan-v1.schema.json` 需修订以下 5 项：

| # | 项目 | 当前状态 | 需修改为 |
|---|------|----------|----------|
| 1 | compiledPrimitive.dependsOn | 未在 required 中 | 加入 required |
| 2 | compiledPrimitive 增加 inputSlot | 无此字段 | 增加 optional 字段 |
| 3 | phaseBarrier | barrierId + phase (single) | barrierId + phases[] (array, unique, min1, max10) |
| 4 | expectedEvidenceRoles | role + producerPrimitiveId | role + contractIds[] (min1, max50, sorted) |
| 5 | oracleBindings | oracleId + primitiveId | contractId + oracleId (max 50) |

### 11.2 受影响构件

| 构件 | 影响 |
|------|------|
| compiled-acceptance-plan-v1.schema.json | 上述 5 项修订 |
| compiled fixtures | profile expected 值更新后全部受影响 |
| wire format tests | 相应 expected values 需更新 |
| profile golden 向量 | expectedCatalogSemanticFingerprint 和 expectedPrimitiveRegistryFingerprint 更新 |

### 11.3 不变构件

| 构件 | 理由 |
|------|------|
| plan/catalog authority 内容 | IR 仅为派生表示，plan/catalog 原文不变 |

### 11.4 版本条件

仅因 schema 尚未外部发布才原位修订 v1。若已有消费者，则必须升级至 v2，实施前由 release owner 确认。

---

## 12. 验收矩阵

| # | 测试ID | 输入 | 预期 | 层级 |
|---|--------|------|------|------|
| 1 | T1-deterministic | 相同 planBytes+catalogBytes | 相同 compiledPlanFingerprint | compiler |
| 2 | T2-plan-fingerprint | planBytes 变更 | 不同 planSourceSemanticFingerprint | compiler |
| 3 | T3-catalog-raw | catalogBytes 变更 | 不同 catalogRawFingerprint | compiler |
| 4 | T4-catalog-semantic | catalog semantic 变更 | 不同 catalogSemanticFingerprint | compiler |
| 5 | T5-dup-id | duplicate primitiveId | INVALID_PLAN_STRUCTURE | compiler |
| 6 | T6-cycle | cyclic dependsOn | INVALID_TOPOLOGY_CYCLE | compiler |
| 7 | T7-unknown-type | typeId 未在 registry | INVALID_REGISTRY_TYPE_NOT_FOUND | compiler |
| 8 | T8-rev-mismatch | revision 不符 | INVALID_REGISTRY_REVISION_MISMATCH | compiler |
| 9 | T9-size-plan | planBytes >1MiB | reject | compiler |
| 10 | T10-size-catalog | catalogBytes >1MiB | reject | compiler |
| 11 | T11-order-topo | plan primitives | executionOrder 拓扑排序 | compiler |
| 12 | T12-permutation | source primitive order permuted | same executionOrder+fingerprint | compiler |
| 13 | T13-phases-complete | profile barriers | 完整保留 barrierId/phases[] 保序 | compiler |
| 14 | T14-count-27 | catalog.stageExitContracts | stageExitContractCount==27 | compiler |
| 15 | T15-count-9 | catalog.acStandards | acStdCount==9 | compiler |
| 16 | T16-count-14 | catalog.feltObligations | feltObligationCount==14 | compiler |
| 17 | T17-count-cases | catalog.canonicalCases | canonicalCases==9 | compiler |
| 18 | T18-count-runs | catalog.suiteRuns | suiteRuns==3 | compiler |
| 19 | T19-count-cells | catalog.matrixCells | canonicalMatrixCellCount==27 | compiler |
| 20 | T20-keys-sorted | IR body | object keys 升序 | compiler |
| 21 | T21-primitive-sorted | primitiveContracts | primitiveId 升序；dependsOn 升序 | compiler |
| 22 | T22-barrier-order | phaseBarriers | 保持 profile.barriers 声明顺序；phases 保序 | compiler |
| 23 | T23-ids-sorted | exactContractIds | contractId 升序 | compiler |
| 24 | T24-role-sorted | expectedEvidenceRoles | role 升序；contractIds 升序 | compiler |
| 25 | T25-oracle-sorted | oracleBindings | contractId 升序 | compiler |
| 26 | T26-domain2 | IR body | Domain2(compiledPlanFingerprint) | compiler |
| 27 | T27-reason-max512 | Invalid reasonField | reasonField length ≤512 | verifier |
| 28 | T28-role-contractids | catalog contracts | expectedEvidenceRoles.role -> contractIds[] | compiler |
| 29 | T29-oracle-contractid | catalog contracts | oracleBindings.contractId -> oracleId | compiler |
| 30 | T30-no-guess-producer | catalog 无 evidenceRoles | 禁止派生 producerPrimitiveId | compiler |
| 31 | T31-raw-fingerprint | catalogBytes | catalogRawFingerprint == Domain2 | compiler |
| 32 | T32-whitespace-ref | catalog whitespace 变，plan.catalogRef 未更新 | INVALID_FINGERPRINT_MISMATCH，拒绝 | compiler |
| 33 | T33-ref-sync | plan.catalogRef 同步更新 | semantic 不变；raw/plan/compiled fingerprint 变化 | compiler |
| 34 | T34-formal-0-27 | IR output | 无 formalPassCount 字段 | compiler |
| 35 | T35-verified | Valid IR | status=VERIFIED | verifier |
| 36 | T36-invalid-pointer | Invalid IR | status=INVALID, reasonField 非空 | verifier |
| 37 | T37-schema-valid | IR output | JSON Schema validation pass | compiler |

---

## 13. 自评分（实施前 Gate）

| 维度 | 评分 | 依据 |
|------|------|------|
| 确定性 | 96 | Domain2/phase 分组 + primitive ID 排序算法定义完整 |
| 无损性 | 97 | 5 项结构完整保留；dependsOn/phases[]/contractIds[] |
| 可验证性 | 94 | Verifier 覆盖全部项目；reasonCode 对齐 schema |
| 简洁性 | 96 | 不包含 producerPrimitiveId/runtime binding；字段最小化 |
| 可演进性 | 94 | ExecutionBindingPlan 边界清晰；版本策略明确 |

**Gate 要求**：所有维度需 ≥ 95 方可进入 Frozen 状态。

- [ ] **Gate-1**：双 oracle 复算验证 catalog/registry 候选哈希一致性
- [ ] **Gate-2**：CompiledAcceptancePlan schema 5 项修订完成
- [ ] **Gate-3**：至少 3 个 golden test vector 通过 deterministic 验证
- [ ] **Gate-4**：Independent Verifier 实现完成并通过隔离性测试

---

## 14. ADR

### ADR-A: IR 是 compile-only

**决策**：compile-only deterministic lossless static IR。

**理由**：Runner 实现晚于 Compiler；确定性派生要求 IR 不含运行时副作用；Verifier 独立重算要求 IR 不包含 Compiler 执行状态。

### ADR-B: expectedEvidenceRoles 结构

**决策**：expectedEvidenceRoles = role + contractIds[]（按 contractId 排序）。

**tradeoff**：producerPrimitiveId 由 ExecutionBindingPlan 提供，IR 不猜测。

### ADR-C: oracleBindings 结构

**决策**：oracleBindings = contractId + oracleId。

**限制**：仅包含 catalog 可派生的绑定。

### ADR-D: Catalog/Registry 候选哈希

**状态**：待双 oracle 复算验证后固化。

---

## 15. 开放问题

| 问题 | 状态 | 依赖 |
|------|------|------|
| 双 oracle 复算验证 | 待执行 | Gate-1 |
| ExecutionBindingPlan 接口定义 | 待 Runner 阶段 | Gate-4 之后 |
| profile expected 值更新 golden | 待 Gate-1 完成 | Gate-1 |
| Verifier 实现路径 | 待确定 | Gate-3 |
