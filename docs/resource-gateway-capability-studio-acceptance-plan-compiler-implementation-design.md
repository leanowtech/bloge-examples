# Capability Studio Acceptance Plan Compiler — Phase 1 实现设计

**状态**: Frozen
**范围**: `resource-gateway-test-kit` 模块内的 Plan Compiler 与独立 Plan Verifier
**事实源**: 4 schemas、packaged profile、3 authority resources、compiled-plan-valid fixture、lossless IR design

---

## A. 状态与规范约束

### A.1 规范优先级

Compiler 只做 compile，不执行 primitive，不生成 PASS/ACCEPTED，不修改外部 formalPassCount。正式进度始终为 0/27，直到 Runner 消费经过 Verifier 验证一致的 IR。

### A.2 非目标（不实现）

- Runner、Scheduler、stateful 执行
- formalPassCount 写入
- PASS/ACCEPTED 判定生成
- EXTERNAL_PUBLICATION type 或 effect
- 任何网络 I/O、文件路径解析、反射

### A.3 外部正式进度

进度状态在 Phase 1 始终为 0/27。

---

## B. 输入与 Trust Boundary

### B.1 Caller 提供（bounded bytes）

| 参数 | 约束 |
|------|------|
| `planBytes` | AcceptancePlan 序列化字节，≤ 1MiB，defensive copy |
| `catalogBytes` | ContractCatalog 序列化字节，≤ 1MiB，defensive copy |

Caller 负责边界检查；Compiler 内部再次验证 size。

### B.2 内部资源（packaged / closed code）

以下由 Compiler/Verifier 各自独立从 packaged resources 加载，Caller 不提供：

- `profile`：`builtin-compiler-profile-formal-v1.json`，schemaVersion = `bloge.capability-studio.compiler-profile-formal.v1`，profileId = `formal-evidence-v1`
- `registry`：从 profile primitiveDescriptors 构建的 closed OperatorRegistry snapshot（不通过网络）
- 4 个 JSON Schema：acceptance-plan-v1、contract-catalog-v1、compiled-acceptance-plan-v1、compiled-plan-verification-result-v1

### B.3 Authority Resources 不是隐式输入

Authority plan (`rg-cs-felt-v1.acceptance.plan.json`) 和 authority catalog (`builtin-contract-catalog.json`) 是 golden/example fixture，不是隐式输入。Compiler 接受任意符合 schema 的 plan/catalog 对，只要 profile binding 正确。

---

## C. Wire 精确摘要

### C.1 AcceptancePlan Schema 关键约束

`schemaVersion` const = `bloge.capability-studio.acceptance-plan.v1`

Root discriminator: `schemaVersion` 字段。

`catalogRef` 字段：schema 仅要求 `type: string, minLength: 1, maxLength: 256`，但 semantic binding 规则为：

```
catalogRef = catalogId + "@" + computed catalogRawFingerprint
computed catalogRawFingerprint = SHA256(raw catalogBytes)
```

Compiler 必须验证 `plan.catalogId == catalog.catalogId`。

`primitives`：schema `minItems: 1, maxItems: 64`，每个 primitive 的 `dependsOn` schema `maxItems: 64`。

### C.2 ContractCatalog Schema 关键约束

`schemaVersion` const = `bloge.capability-studio.contract-catalog.v1`

固定基数（schema minItems/maxItems + required 验证）：

| 数组字段 | 值 |
|----------|-----|
| stageExitContracts | 27 |
| acStandards | 9 |
| feltObligations | 14 |
| canonicalCases | 9 |
| suiteRuns | 3 |
| matrixCells | 27 |

### C.3 CompiledAcceptancePlan Schema 关键约束

`schemaVersion` const = `bloge.capability-studio.compiled-plan.v1`（注意不是 `compiled-acceptance-plan.v1`）

Root discriminator: `schemaVersion`。

精确基数（schema const）：

| 字段 | 值 |
|------|-----|
| stageExitContractCount | 27 |
| acStdCount | 9 |
| feltObligationCount | 14 |
| canonicalMatrixCellCount | 27 |
| suiteRunCount | 3 |
| exactContractIds[] | 50（unique） |
| oracleBindings[] | 50（unique） |
| phaseBarriers[] | 7 |
| expectedEvidenceRoles[] | 1..8（schema minItems: 1, maxItems: 8，unique） |

---

## D. Domain2 表

所有 fingerprint 使用统一公式：

```
Domain2(domain: string, payload: bytes) = SHA256( UTF8(domain) || I32BE(payload.length) || payload )
```

无 length prefix on domain string。

| # | Domain String | Payload |
|---|---------------|---------|
| 1 | `RG-CS-PLAN-SOURCE-SEMANTIC-v1` | plan semantic canonical JSON bytes |
| 2 | `RG-CS-CATALOG-RAW-v1` | raw catalogBytes |
| 3 | `RG-CS-CATALOG-SEMANTIC-v1` | catalog 按规范化后（含 contractEntry 内 evidenceRoles/ownerRoles/externalFactRequirements 排序）的 semantic canonical bytes |
| 4 | `RG-CS-COMPILER-PROFILE-RAW-v1` | raw profile bytes |
| 5 | `RG-CS-PRIMITIVE-REGISTRY-v1` | registry descriptor canonical JSON array（typeId 升序，3 个 set-like 数组升序） |
| 6 | `RG-CS-COMPILED-PLAN-v1` | compiled IR body（compiledPlanFingerprint 字段 self-excluding）在内序列化后的 canonical bytes |

---

## E. Canonicalization

### E.1 Object Key Ordering

所有对象键按 Jackson UTF-16 code unit 升序排列（JSON Object 序列化特性）。

### E.2 Array 排序

- `set-like arrays`（含 uniqueItems 约束的数组）：元素升序排列
- `dependsOn`（semantic plan/compiled primitive）：升序
- `source primitive permutation` 不改变语义（Compiler 重新拓扑排序）
- `phaseBarriers` 和 `profile order` 和 `phases` 数组：**保序**（不重新排序）

### E.3 禁止 JSON Comments

不允许 `#` 或 `//` JSON comments。

---

## F. Compile Algorithm

### F.1 步骤顺序

```
1. bounded parse: planBytes/catalogBytes 各 ≤ 1MiB，defensive copy
2. strict duplicate KEY detection (Jackson StreamReadFeature.STRICT_DUPLICATE_DETECTION)
3. JSON Schema validation (planBytes, catalogBytes)
4. semantic duplicate ID detection across all six catalog arrays (exact 50 contracts)
   — separate step from schema validation
5. catalog id/ref/raw/semantic/profile/registry checks:
   a. plan.catalogId == catalog.catalogId
   b. catalogRef semantic binding: catalogId + "@" + computed catalogRawFingerprint
   c. catalog raw fingerprint == Domain2(RG-CS-CATALOG-RAW-v1, raw catalogBytes)
   d. catalog semantic fingerprint == Domain2(RG-CS-CATALOG-SEMANTIC-v1, semantic catalog canonical bytes)
   e. catalog semantic fingerprint == profile.expectedCatalogSemanticFingerprint
   f. primitiveRegistryFingerprint == Domain2(RG-CS-PRIMITIVE-REGISTRY-v1, registry canonical bytes)
   g. primitiveRegistryFingerprint == profile.expectedPrimitiveRegistryFingerprint
6. profile checks:
   a. profile.schemaVersion == expected schema version constant
   b. profile.profileId == "formal-evidence-v1"
   c. each plan primitive.typeId in registry (strict)
   d. each plan primitive.revision matches descriptor revision
   e. each plan primitive.effectClass matches allowedEffectClasses
   f. each plan primitive.phase matches descriptor phase
7. unknown dep detection: each dependsOn ID must reference an existing primitive.id
8. cycle detection: DAG 构建 + 检测
9. forward phase barrier: dependsOn phase index 必须 <= 被依赖 primitive phase index
10. stable topological sort: Kahn's algorithm，PQ 以 phase index 为 primary key、primitiveId 为 secondary key（zero-indegree 时）
11. primitiveContracts: 按 primitiveId 升序输出；每个 primitive 的 dependsOn 升序
12. exactContractIds: 50 个 contractId 升序
13. expectedEvidenceRoles: role 升序；每个 role 的 contractIds 升序
14. oracleBindings: contractId 升序
15. phaseBarriers: **保序**（按 profile.barriers 声明顺序，不重新排序）
16. IR body assembly: 按 §E 规范化
17. 派生 23-field CompiledAcceptancePlan IR（无 formalPassCount）
18. final schema validation: compiled IR against compiled-acceptance-plan-v1 schema
19. fingerprint:
    a. planSourceSemanticFingerprint = Domain2(RG-CS-PLAN-SOURCE-SEMANTIC-v1, plan semantic canonical bytes)
    b. catalogRawFingerprint = Domain2(RG-CS-CATALOG-RAW-v1, raw catalogBytes)
    c. catalogSemanticFingerprint = Domain2(RG-CS-CATALOG-SEMANTIC-v1, catalog semantic canonical bytes)
    d. compilerProfileRawFingerprint = Domain2(RG-CS-COMPILER-PROFILE-RAW-v1, raw profile bytes)
    e. primitiveRegistryFingerprint = Domain2(RG-CS-PRIMITIVE-REGISTRY-v1, registry canonical bytes)
    f. compiledPlanFingerprint = Domain2(RG-CS-COMPILED-PLAN-v1, self-excluding body canonical bytes)
```

### F.2 8 Descriptors / 9 Primitives

Profile `primitiveDescriptors` 数组含 8 个 descriptor。

Authority plan `primitives` 数组含 9 个 primitive 实例：`verify-fixed-material`（MATERIAL_SNAPSHOT）、`verify-authority-tree`（READ_ONLY_PREFLIGHT）、`verify-target-tree`（READ_ONLY_PREFLIGHT）、`provider-conformance`（PROVIDER_CONFORMANCE）、`execute-lease-evidence`（STATEFUL_EXECUTION）、`verify-durable-wrapper`（INDEPENDENT_VERIFICATION）、`verify-packaged-matrices`（INDEPENDENT_VERIFICATION）、`verify-postflight`（MATERIAL_POSTFLIGHT）、`commit-local-proof`（DURABLE_LOCAL_COMMIT）。

### F.3 Barrier Exact 7 / Evidence Roles Authority 6 / Schema 1..8

`phaseBarriers` 精确 7 个：`PURE_VERIFY_GATE`、`LEASE_GATE`、`NO_DELETE_AFTER_LEASE`、`DURABLE_COMMIT_GATE`、`STORE_RECEIPT_GATE`、`OWNER_SIGNOFF_GATE`、`NO_ACCEPTED_FROM_LOCAL`。

Authority catalog evidenceRoles 实际去重后为 6 个（authority roles = 6）。Compiled schema evidenceRoles 允许 1..8（minItems: 1, maxItems: 8）。

### F.4 Oracles Exact 50

oracleBindings 精确 50 项（每个 contract 一个 oracleId）。

---

## G. Barrier Semantics（当前 profile/schema 可静态证明的条件）

### G.1 静态拒绝条件

以下条件在 compile 阶段静态验证，不依赖 Runner runtime：

1. **dependency phase 逆序**：若 primitive A dependsOn B，则 `phase(A) index >= phase(B) index`，否则拒绝
2. **effectClass 不匹配**：primitive.effectClass 必须在 profile allowedEffectClasses 内
3. **profile barrier 集合漂移**：compiled phaseBarriers 集合必须等于 profile.barriers 集合（相同 7 个 barrierId）
4. **profile barrier 顺序漂移**：compiled phaseBarriers 顺序必须等于 profile.barriers 声明顺序

### G.2 Runner Runtime 效果闭包（不在本阶段）

以下属于 Runner runtime 职责，不在 compile 阶段验证：

- primitive 实际执行顺序
- EXTERNAL_PUBLICATION phase 的运行时效果闭包
- PURE_VERIFY_GATE / LEASE_GATE 的实际执行期隔离

---

## H. Public API

### H.1 Builder

```java
public final class CapabilityStudioAcceptancePlanCompiler {
    private CapabilityStudioAcceptancePlanCompiler(OperatorRegistry registry,
                                                   CapabilityStudioCompilerProtocol protocol) { ... }

    // 受保护构造器：仅接受 internal registry/protocol
    public static Builder withBuiltInInternals() { return new Builder(); }

    public static final class Builder {
        Builder() { /* 注入 internal registry + protocol */ }
        public CapabilityStudioAcceptancePlanCompiler build() { ... }
    }
}
```

### H.2 compile 方法

```java
public CompilationResult compile(byte[] planBytes, byte[] catalogBytes) throws CompilerException
```

- `planBytes`：defensive copy 后解析
- `catalogBytes`：defensive copy 后解析
- `CompilationResult`：immutable snapshot，不暴露可变 JsonNode
- `CompilerException`：`reasonCode`（RFC6901 pointer + human-readable） + RFC6901 pointer

### H.3 CompilationResult

```java
public final class CompilationResult {
    private final byte[] planBytes;          // defensive copy of input
    private final byte[] catalogBytes;        // defensive copy of input
    private final JsonNode compiledPlan;     // immutable snapshot (ObjectMapper.readValue copy)
    private final List<CompilerWarning> warnings;

    // 无 formalPassCount 字段
    // 返回 JSON bytes 时返回 defensive copy
}
```

### H.4 CompilerException

```java
public final class CompilerException extends Exception {
    CompilerException(String reasonCode, String rfc6901Pointer, String message) { ... }
    public String reasonCode() { ... }
    public String rfc6901Pointer() { ... }
}
```

reasonCode 枚举（部分）：`INVALID_PLAN_SCHEMA`、`INVALID_CATALOG_SCHEMA`、`CATALOG_ID_MISMATCH`、`CATALOG_REF_MISMATCH`、`CATALOG_SEMANTIC_FINGERPRINT_MISMATCH`、`INVALID_TOPOLOGY_CYCLE`、`UNKNOWN_DEPENDENCY`、`INVALID_REGISTRY_TYPE_NOT_FOUND`、`INVALID_REGISTRY_REVISION_MISMATCH`、`INVALID_EFFECT_CLASS`。

### H.5 Constructor Constraint

构造器仅接受 registry/protocol，不接受 Catalog。Catalog 来自 bounded wire bytes。

---

## I. Independent Verifier API（下一提交设计，不在 Phase 1 实现）

### I.1 隔离契约

Verifier 不调用 Compiler，在独立执行路径中重新计算所有 fingerprint 和拓扑。Verifier 构造函数不接受 Compiler 实例引用。

### I.2 API

```java
public final class CapabilityStudioCompiledPlanVerifier {
    public static Builder withBuiltInInternals() { return new Builder(); }

    public VerificationResult verify(CompiledAcceptancePlan compiled, byte[] catalogBytes) { ... }
}
```

注：Verifier 实现在 Phase 1 之后单独提交（见 §L）。

---

## J. Authority Goldens

### J.1 4 个已知 Authority Fingerprint

| Fingerprint | 值 | 说明 |
|-------------|-----|------|
| catalogRaw | `sha256:b14c3ee599a87e0c10a94f4e0237455bcae93ff2c9fcc8a6a82ab9145942990c` | authority catalog 字节的 Domain2 |
| catalogSemantic | `sha256:f20774c84f7f34cb0f95c9bb6f5061e048d94b2463da3ca20b560e338cdb7b4d` | profile.expectedCatalogSemanticFingerprint |
| profileRaw | `sha256:113f89e746a87d7e9fc662f2466d49e143797501d07edeb67a3b419773d10c46` | profile 字节的 Domain2 |
| registrySemantic | `sha256:15ea38ddeda10a7befb280efc4fdefb74503036cc06d55775da09665ae4c2686` | profile.expectedPrimitiveRegistryFingerprint |

catalogRaw 值从 authority plan (`rg-cs-felt-v1.acceptance.plan.json`) 的 `catalogRef` 解析：`builtin-contract-catalog-v1@sha256:b14c3...`。

catalogSemantic 和 registrySemantic 值从 packaged profile 的 `expectedCatalogSemanticFingerprint` 和 `expectedPrimitiveRegistryFingerprint` 字段提取。

profileRaw 由 Compiler 对 packaged profile 字节计算。

### J.2 待 Compiler 生成的 Fingerprint

以下由 Compiler 派生，authority 尚未固化：

- `planSourceSemanticFingerprint`：authority plan semantic canonical 的 Domain2（待 Compiler 生成）
- `compiledPlanFingerprint`：authority plan/catalog 编译后的 Domain2（待 Compiler 生成）

### J.3 Compiled-Plan-Valid Fixture 说明

`compiled-plan-valid.json` fixture（含 placeholder `000...`/`111...` fingerprint）**不是 golden**，仅作为 schema validation 测试向量。真实 compiled plan fingerprint 由 Compiler 对 authority plan/catalog 输入派生。

---

## K. 精确测试门

### K.1 Lossless IR Matrix（37 条，分组）

**分组 1：Schema 验证**

- G1-1: acceptance-plan-v1 schema 验证通过
- G1-2: contract-catalog-v1 schema 验证通过（27+9+14+9+3+27 cardinality）
- G1-3: compiled-acceptance-plan-v1 schema 验证通过（50+50+7+exact const）
- G1-4: compiled-plan-verification-result-v1 schema 验证通过

**分组 2：Canonicalization 确定性**

- G2-1: plan primitive 顺序 permutation → 相同 planSourceSemanticFingerprint
- G2-2: catalog 空白变化（whitespace）→ 不同 catalogRawFingerprint，catalogSemanticFingerprint 不变
- G2-3: catalog contractEntry 内容漂移 → 不同 catalogSemanticFingerprint
- G2-4: object key 顺序变化（Jackson UTF-16）→ 相同 semantic fingerprint
- G2-5: dependsOn 数组 permutation → 相同 compiled output（升序后）
- G2-6: compiled body object key 升序 → deterministic fingerprint
- G2-7: phaseBarriers 保序（不等于 phase 升序）

**分组 3：Catalog 结构完整性**

- G3-1: stageExitContracts 27（含 6 stages 各若干）
- G3-2: acStandards 9
- G3-3: feltObligations 14
- G3-4: canonicalCases 9
- G3-5: suiteRuns 3
- G3-6: matrixCells 27
- G3-7: exactContractIds 50（unique）
- G3-8: oracleBindings 50（unique）

**分组 4：Semantic ID 一致性**

- G4-1: 9 个 primitive 实例 + 8 个 descriptor typeId/typeId 匹配
- G4-2: duplicate primitive.id → INVALID
- G4-3: duplicate catalog contractId → INVALID
- G4-4: duplicate semantic IDs across all six catalog arrays → INVALID

**分组 5：Profile Binding**

- G5-1: plan primitive.typeId in registry
- G5-2: plan primitive.revision == descriptor.revision
- G5-3: plan primitive.effectClass ∈ allowedEffectClasses
- G5-4: plan primitive.phase == descriptor.phase
- G5-5: VERIFY_FIXED_MATERIAL_V1 phase == MATERIAL_SNAPSHOT
- G5-6: catalogSemanticFingerprint == profile.expectedCatalogSemanticFingerprint
- G5-7: primitiveRegistryFingerprint == profile.expectedPrimitiveRegistryFingerprint

**分组 6：CatalogRef 同步**

- G6-1: plan.catalogRef 与 catalogRawFingerprint 匹配（catalogRefVerified=true）
- G6-2: catalog whitespace 变，plan.catalogRef 未更新 → INVALID_FINGERPRINT_MISMATCH
- G6-3: catalog whitespace 变，plan.catalogRef 同步更新 → 编译通过（semantic 不变）

**分组 7：Topology**

- G7-1: DAG 无 cycle → 编译通过
- G7-2: cyclic dependsOn → INVALID_TOPOLOGY_CYCLE
- G7-3: unknown dependsOn ID → UNKNOWN_DEPENDENCY
- G7-4: forward phase 依赖逆序 → REJECT

**分组 8：Barrier 静态验证**

- G8-1: dependency phase 逆序 → REJECT
- G8-2: effectClass 不匹配 → REJECT
- G8-3: profile barrier 集合漂移 → REJECT
- G8-4: profile barrier 顺序漂移 → REJECT

**分组 9：其他**

- G9-1: planBytes > 1MiB → reject
- G9-2: catalogBytes > 1MiB → reject
- G9-3: duplicate JSON keys → reject（STRICT_DUPLICATE_DETECTION）
- G9-4: Verifier 不调用 Compiler（NEG-ARCH-001）
- G9-5: final schema 验证通过
- G9-6: inputSlot lossless（从 plan 到 compiled）
- G9-7: compiled IR 无 formalPassCount 字段

---

## L. 提交拆分

### L.1 提交 1（本文档）

```
docs/resource-gateway-capability-studio-acceptance-plan-compiler-implementation-design.md
docs/resource-gateway-capability-studio-compiled-acceptance-ir-design.md
```

### L.2 后续提交

- **提交 2**：4 schemas + 3 authority resources + pom.xml resource 配置
- **提交 3**：Compiler + Registry + Protocol（package-private）
- **提交 4**：Verifier（独立，不调用 Compiler）
- **提交 5**：Tests + JAR inventory 验证

### L.3 JAR 边界

- `bloge-resource-gateway-test-kit-*.jar`（ordinary/shaded main JAR）包含 4 schemas + 3 authority files
- `a1-protocol JAR` 不包含 Phase 1 schemas

---

## M. Definition of Done

### M.1 冻结条件

所有以下条件满足后本文档 Frozen：

1. 4 schemas 已提交且通过 JSON Schema Draft 2020-12 validator
2. 3 authority resources 已提交且 fingerprint 已固化
3. Compiler 实现通过全部 37 条 lossless IR test gates
4. Verifier 实现通过隔离性测试（不调用 Compiler）
5. `git diff --check` 无警告
6. Javadoc doclint 全通过

### M.2 开放边界

以下不在 Phase 1 范围内：

1. Verifier 实现（Phase 2）
2. Runner / Scheduler / stateful 执行（Phase 3+）
3. EXTERNAL_PUBLICATION phase runtime 效果闭包
4. formalPassCount 写入逻辑
5. catalogSemanticFingerprint / primitiveRegistryFingerprint 之外的 profile expected 值动态验证机制
