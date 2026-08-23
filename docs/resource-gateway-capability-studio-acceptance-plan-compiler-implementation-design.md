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
- `registry`：从 profile primitiveDescriptors 构建的 closed primitive registry snapshot（不通过网络）
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
computed catalogRawFingerprint = Domain2("RG-CS-CATALOG-RAW-v1", exact catalogBytes)
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
| 7 | `RG-CS-COMPILED-PLAN-VERIFICATION-v1` | verification result self-excluding canonical bytes |

---

## E. Canonicalization

### E.1 Object Key Ordering

所有对象键按 Jackson UTF-16 code unit 升序排列（JSON Object 序列化特性）。

### E.2 Array 排序精确规范

以下为各数组类型的排序规则，不存在统一规则：

**plan primitives：**
- `primitives[]` 本身： Compiler 拓扑排序，不按原始顺序输出
- 每个 primitive 的 `dependsOn[]`：升序（primitive id）

**catalog contract 数组（按 contractId 升序；每个 entry 的三个角色数组升序）：**
- `stageExitContracts[].contractId`
- `acStandards[].contractId`
- `feltObligations[].contractId`

**catalog 角色数组（各自内部升序）：**
- `evidenceRoles[]`：升序
- `ownerRoles[]`：升序
- `externalFactRequirements[]`：升序

**catalog ID 聚合数组（升序）：**
- `canonicalCases[].canonicalCaseId`
- `suiteRuns[].suiteRunId`
- `matrixCells[].matrixCellId`

**registry descriptor 数组（按 typeId 升序；三个 set-like 数组各自升序）：**
- `primitiveDescriptors[].typeId`
- `primitiveDescriptors[].allowedEffectClasses[]`
- `primitiveDescriptors[].phaseOrder[]`

**compiled plan 数组：**
- `primitiveContracts[].primitiveId`：升序
- `primitiveContracts[].dependsOn[]`：升序
- `exactContractIds[]`：升序
- `expectedEvidenceRoles[].role`：升序；每个 role 的 `contractIds[]` 升序
- `oracleBindings[].contractId`：升序

**phaseBarriers / profile / phases 数组：保序**（不重新排序，与 lossless IR §7.6 一致）

### E.3 禁止 JSON Comments

不允许 `#` 或 `//` JSON comments。

---

## F. Compile Algorithm

### F.1 步骤顺序

```
1. bounded parse: planBytes/catalogBytes 各 ≤ 1MiB，defensive copy
2. strict duplicate KEY detection (Jackson StreamReadFeature.STRICT_DUPLICATE_DETECTION)
3. JSON Schema validation (planBytes, catalogBytes)
4. semantic duplicate ID detection:
   — contractId 在 stageExitContracts / acStandards / feltObligations 三个数组的合并集合内全局唯一且 exact 50
   — canonicalCaseId 在 canonicalCases 数组内唯一
   — suiteRunId 在 suiteRuns 数组内唯一
   — matrixCellId 在 matrixCells 数组内唯一
   — primitive.id 全局唯一
   — separate step from schema validation
5. catalog id/ref/raw/semantic/profile/registry checks:
   a. plan.catalogId == catalog.catalogId
   b. catalogRef semantic binding: catalogId + "@" + catalogRawFingerprint
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
   e. descriptor.effectClass ∈ allowedEffectClasses
   f. descriptor.phase ∈ phaseOrder
7. unknown dep detection: each dependsOn ID must reference an existing primitive.id
8. cycle detection: DAG 构建 + 检测
9. dependency direction: A dependsOn B ⇒ phaseIndex(B) ≤ phaseIndex(A)
10. stable topological sort: Kahn's algorithm，PQ 以 phase index 为 primary key、primitiveId 为 secondary key（zero-degree 时）
11. primitiveContracts: 按 primitiveId 升序输出；每个 primitive 的 dependsOn 升序
12. exactContractIds: 50 个 contractId 升序
13. expectedEvidenceRoles: role 升序；每个 role 的 contractIds 升序
14. oracleBindings: contractId 升序
15. phaseBarriers: **保序**（按 profile.barriers 声明顺序，不重新排序）
16. IR body assembly: 按 §E 规范化，生成除 compiledPlanFingerprint 外的 22 个字段
17. 计算 compiledPlanFingerprint = Domain2(RG-CS-COMPILED-PLAN-v1, self-excluding body canonical bytes)
18. 写入 compiledPlanFingerprint 为第 23 字段，组装完整 23-field body
19. final compiled schema validation: 完整 IR body（含 compiledPlanFingerprint） against compiled-acceptance-plan-v1 schema
```

### F.2 8 Descriptors / 9 Primitives

Profile `primitiveDescriptors` 数组含 8 个 descriptor。

Authority plan `primitives` 数组含 9 个 primitive 实例：`verify-fixed-material`（MATERIAL_SNAPSHOT）、`verify-authority-tree`（READ_ONLY_PREFLIGHT）、`verify-target-tree`（READ_ONLY_PREFLIGHT）、`provider-conformance`（PROVIDER_CONFORMANCE）、`execute-lease-evidence`（STATEFUL_EXECUTION）、`verify-durable-wrapper`（INDEPENDENT_VERIFICATION）、`verify-packaged-matrices`（INDEPENDENT_VERIFICATION）、`verify-postflight`（MATERIAL_POSTFLIGHT）、`commit-local-proof`（DURABLE_LOCAL_COMMIT）。

### F.3 Barrier Exact 7 / Evidence Roles Authority 6 / Schema 1..8

`phaseBarriers` 精确 7 个：`PURE_VERIFY_GATE`、`LEASE_GATE`、`NO_DELETE_AFTER_LEASE`、`DURABLE_COMMIT_GATE`、`STORE_RECEIPT_GATE`、`OWNER_SIGNOFF_GATE`、`NO_ACCEPTED_FROM_LOCAL`。

Authority catalog evidenceRoles 实际去重后为 6 个（authority roles = 6）。Compiled schema evidenceRoles 允许 1..8（minItems: 1, maxItems: 8）。

### F.4 ID Uniqueness 精确约束

- `contractId` 在 stageExitContracts / acStandards / feltObligations 三个数组的合并集合内全局唯一且 exact 50
- `canonicalCaseId` 在 canonicalCases 数组内唯一
- `suiteRunId` 在 suiteRuns 数组内唯一
- `matrixCellId` 在 matrixCells 数组内唯一
- `primitive.id` 全局唯一
- oracleBindings `contractId` 按 contract 聚合（每个 contractId 对应一个 oracleId）
- expectedEvidenceRoles `contractIds` 按 role 聚合（每个 role 对应若干 contractIds）

---

## G. Barrier Semantics（Compiler 静态可证条件）

### G.1 静态可证条件

以下条件在 compile 阶段静态验证，不依赖 Runner runtime：

1. **unknown dependency**：dependsOn ID 引用不存在的 primitive.id → `INVALID_TOPOLOGY_UNKNOWN_NODE`
2. **cyclic dependency**：DAG cycle → `INVALID_TOPOLOGY_CYCLE`
3. **phase reverse dependency**：A dependsOn B 但 phaseIndex(B) > phaseIndex(A) → `INVALID_TOPOLOGY_CYCLE`
4. **descriptor effectClass**：descriptor.effectClass ∉ allowedEffectClasses → `INVALID_REGISTRY_REVISION_MISMATCH`
5. **descriptor phase**：descriptor.phase ∉ phaseOrder → `INVALID_REGISTRY_TYPE_NOT_FOUND`
6. **profile vocabulary**：
   - barrier 集合漂移（compiled phaseBarriers 集合 ≠ profile.barriers 集合）→ `INVALID_BARRIER_BYPASS`
   - barrier 顺序漂移（compiled phaseBarriers 顺序 ≠ profile.barriers 声明顺序）→ `INVALID_COLLECTION_SIZE`
   - Protocol 验证 compiled phaseBarriers 精确为 7 个 closed barrierId、唯一、顺序与 profile.barriers 一致、各 barrier.phase 在 phaseOrder 内
   - Compiler 无损投影 plan primitives 到 compiled phaseBarriers

### G.2 Compiler 不验证的条件（由 Verifier/Runtime 负责）

以下不在 compile 阶段验证：

- tampered compiled 输出漂移（由 Verifier 在独立路径拒绝；Compiler 无 compiled 输入）
- primitive 实际执行顺序
- EXTERNAL_PUBLICATION phase 的 runtime 效果闭包
- formalPassCount 写入逻辑

---

## H. Public API

### H.1 Factory

```java
public final class CapabilityStudioAcceptancePlanCompiler {
    private CapabilityStudioAcceptancePlanCompiler(CapabilityStudioAcceptancePrimitiveRegistry registry,
                                                   CapabilityStudioAcceptancePlanProtocol protocol) { ... }

    // 直接返回 Compiler 实例，无需 Builder
    public static CapabilityStudioAcceptancePlanCompiler withBuiltInInternals() {
        return new CapabilityStudioAcceptancePlanCompiler(
            CapabilityStudioAcceptancePrimitiveRegistry.builtIn(),
            CapabilityStudioAcceptancePlanProtocol.instance()
        );
    }

    // package-private constructor：仅供 internal factory
    // 不接受 Catalog 参数；Catalog 来自 bounded wire bytes
}
```

### H.2 compile 方法

```java
public CompilationResult compile(byte[] planBytes, byte[] catalogBytes) throws CompilerException
```

- `planBytes`：defensive copy 后解析
- `catalogBytes`：defensive copy 后解析
- `CompilationResult`：immutable，不暴露可变 JsonNode
- `CompilerException`：`reasonCode` + `reasonField`（RFC6901 pointer）

### H.3 CompilationResult（immutable record）

```java
public final class CompilationResult {
    private final byte[] compiledPlanBytes;           // NOT immutable; clone on save and on access
    private final String compiledPlanFingerprint;     // String is immutable
    // NO warnings field

    public CompilationResult(byte[] compiledPlanBytes, String compiledPlanFingerprint) {
        this.compiledPlanBytes = compiledPlanBytes.clone(); // constructor clone 保存
        this.compiledPlanFingerprint = compiledPlanFingerprint;
    }

    public byte[] compiledPlanBytes() {
        return compiledPlanBytes.clone();             // accessor clone
    }

    public String compiledPlanFingerprint() { return compiledPlanFingerprint; }
    // NO warnings() accessor
}
```

**设计原则**：不保存输入 bytes，不暴露 mutable JsonNode。
- `byte[]` 不是 immutable；constructor clone 保存，accessor clone 返回
- `String` 字段天然 immutable，直接返回


### H.4 CompilerException

```java
public final class CompilerException extends Exception {
    CompilerException(String reasonCode, String rfc6901Pointer, String message) { ... }
    public String reasonCode() { ... }
    public String reasonField() { ... }   // RFC6901 JSON Pointer
}
```

reasonCode 枚举仅使用 compiled-plan-verification-result-v1 schema 已闭合的 INVALID_* reason codes（可用其中子集）：

- `INVALID_SCHEMA`
- `INVALID_PLAN_STRUCTURE`
- `INVALID_TOPOLOGY_CYCLE`
- `INVALID_TOPOLOGY_UNKNOWN_NODE`
- `INVALID_REGISTRY_TYPE_NOT_FOUND`
- `INVALID_REGISTRY_REVISION_MISMATCH`
- `INVALID_BARRIER_BYPASS`
- `INVALID_COLLECTION_SIZE`
- `INVALID_CATALOG_SEMANTICS`
- `INVALID_FINGERPRINT_MISMATCH`
- `INVALID_STAGE_EXIT_CONTRACT_COUNT`
- `INVALID_TAMPERED_PLAN`

不得使用 schema 未闭合的 invented reason codes。

### H.5 Constructor Constraint

构造器仅接受 registry/protocol，不接受 Catalog。Catalog 来自 bounded wire bytes。

---

## I. Independent Verifier API

### I.1 隔离契约

Verifier 不调用 Compiler，在独立执行路径中重新计算所有 fingerprint 和拓扑。Verifier 构造函数不接受 Compiler 实例引用。

### I.2 API

```java
public final class CapabilityStudioCompiledPlanVerifier {
    // 直接返回 Verifier 实例，无需 Builder
    public static CapabilityStudioCompiledPlanVerifier withBuiltInInternals() {
        return new CapabilityStudioCompiledPlanVerifier(...);
    }

    public VerificationResult verify(byte[] planBytes,
                                    byte[] catalogBytes,
                                    byte[] compiledPlanBytes) { ... }
}
```

**输入**：三者各 bounded defensive copy；Verifier 内部独立加载 schema/profile/registry（不从 Caller 继承任何资源）。

**Verifier 不调用 Compiler**：Architectural constraint，违反则 CompilationResult/CompilerException 语义循环依赖。

注：Verifier 实现在独立提交（见 §L），但属于本交付范围。

---

## J. Authority Goldens

### J.1 4 个已固化 Authority Fingerprint（Material Fixed）

| Fingerprint | 值 | 说明 |
|-------------|-----|------|
| catalogRaw | `sha256:b14c3ee599a87e0c10a94f4e0237455bcae93ff2c9fcc8a6a82ab9145942990c` | authority catalog 字节的 Domain2 |
| catalogSemantic | `sha256:f20774c84f7f34cb0f95c9bb6f5061e048d94b2463da3ca20b560e338cdb7b4d` | profile.expectedCatalogSemanticFingerprint |
| profileRaw | `sha256:1bf4cc98feeb3c12ed413d50240f0799385efdb755139ba0375200096a672337` | profile 字节的 Domain2 |
| registrySemantic | `sha256:15ea38ddeda10a7befb280efc4fdefb74503036cc06d55775da09665ae4c2686` | profile.expectedPrimitiveRegistryFingerprint |

catalogRaw 值从 authority plan (`rg-cs-felt-v1.acceptance.plan.json`) 的 `catalogRef` 解析：`builtin-contract-catalog-v1@sha256:b14c3...`。

catalogSemantic 和 registrySemantic 值从 packaged profile 的 `expectedCatalogSemanticFingerprint` 和 `expectedPrimitiveRegistryFingerprint` 字段提取。

**注意**：现有 `compiled-plan-valid.json` fixture 中的 `catalogRawFingerprint = sha256:07b8...` 来自 test fixture catalog（用于 schema validation 测试向量），不是 authority catalog，不是 fingerprint authority。

### J.2 待 Compiler 生成的 Fingerprint

以下由 Compiler 派生，authority 尚未固化：

- `planSourceSemanticFingerprint`：authority plan semantic canonical 的 Domain2（待 Compiler 生成）
- `compiledPlanFingerprint`：authority plan/catalog 编译后的 Domain2（待 Compiler 生成）
- schema fingerprint 不声称固定
- 3 个 authority fingerprints（catalogRaw/catalogSemantic/registrySemantic）已固化；profileRaw 已固化；共 4 个 material fingerprint 固定

### J.3 Compiled-Plan-Valid Fixture 说明

`compiled-plan-valid.json` fixture（含 placeholder `000...`/`111...` fingerprint）**不是 golden**，仅作为 schema validation 测试向量。真实 compiled plan fingerprint 由 Compiler 对 authority plan/catalog 输入派生。

---

## K. 精确测试门

### K.1 Lossless IR Matrix（37 条规范必须全部实现，以下为覆盖分组与附加边界）

覆盖范围引用 lossless IR §12。

**分组 1：Schema 验证（4 条）**

- G1-1: acceptance-plan-v1 schema 验证通过
- G1-2: contract-catalog-v1 schema 验证通过（27+9+14+9+3+27 cardinality）
- G1-3: compiled-acceptance-plan-v1 schema 验证通过（50+50+7+exact const）
- G1-4: compiled-plan-verification-result-v1 schema 验证通过

**分组 2：Canonicalization 确定性（7 条）**

- G2-1: plan primitive 顺序 permutation → 相同 planSourceSemanticFingerprint
- G2-2: catalog 空白变化（whitespace）→ 不同 catalogRawFingerprint，catalogSemanticFingerprint 不变
- G2-3: catalog contractEntry 内容漂移 → 不同 catalogSemanticFingerprint
- G2-4: object key 顺序变化（Jackson UTF-16）→ 相同 semantic fingerprint
- G2-5: dependsOn 数组 permutation → 相同 compiled output（升序后）
- G2-6: compiled body object key 升序 → deterministic fingerprint
- G2-7: phaseBarriers 保序（不等于 phase 升序）

**分组 3：Catalog 结构完整性（8 条）**

- G3-1: stageExitContracts 27（含 6 stages 各若干）
- G3-2: acStandards 9
- G3-3: feltObligations 14
- G3-4: canonicalCases 9
- G3-5: suiteRuns 3
- G3-6: matrixCells 27
- G3-7: exactContractIds 50（unique）
- G3-8: oracleBindings 50（unique）

**分组 4：Semantic ID 一致性（4 条）**

- G4-1: 9 个 primitive 实例 + 8 个 descriptor typeId/typeId 匹配
- G4-2: duplicate primitive.id → INVALID
- G4-3: duplicate catalog contractId → INVALID
- G4-4: contractId 在 3 个 contract 数组的合并集合内全局唯一且 exact 50；canonicalCaseId/suiteRunId/matrixCellId 各自在自身数组唯一；primitive.id 全局唯一；role 聚合和 oracle 按 contract

**分组 5：Profile Binding（7 条）**

- G5-1: plan primitive.typeId in registry
- G5-2: plan primitive.revision == descriptor.revision
- G5-3: descriptor.effectClass ∈ allowedEffectClasses
- G5-4: descriptor.phase ∈ phaseOrder
- G5-5: VERIFY_FIXED_MATERIAL_V1 descriptor.phase == MATERIAL_SNAPSHOT
- G5-6: catalogSemanticFingerprint == profile.expectedCatalogSemanticFingerprint
- G5-7: primitiveRegistryFingerprint == profile.expectedPrimitiveRegistryFingerprint

**分组 6：CatalogRef 同步（3 条）**

- G6-1: plan.catalogRef 与 catalogRawFingerprint 匹配（catalogRefVerified=true）
- G6-2: catalog whitespace 变，plan.catalogRef 未更新 → INVALID_FINGERPRINT_MISMATCH
- G6-3: catalog whitespace 变，plan.catalogRef 同步更新 → 编译通过（semantic 不变）

**分组 7：Topology（4 条）**

- G7-1: DAG 无 cycle → 编译通过
- G7-2: cyclic dependsOn → INVALID_TOPOLOGY_CYCLE
- G7-3: unknown dependsOn ID → INVALID_TOPOLOGY_UNKNOWN_NODE
- G7-4: A dependsOn B 但 phaseIndex(B) > phaseIndex(A) → INVALID_TOPOLOGY_CYCLE

### K.2 附加边界测试（超出 37 条）

以下为 lossless IR §12 覆盖的附加边界：

- G10-1: null inputs → reject（planBytes == null / catalogBytes == null）
- G10-2: defensive-copy mutation → input array 被 Caller 修改不影响编译结果
- G10-3: catalog semantic 变更即使 catalogRef 同步更新也拒绝（profile expected catalogSemanticFingerprint pin 未更新）
- G10-4: authority primitives=9/barriers=7/roles=6/oracles=50；stageExit=27 精确验证（stageExitContractCount=27/acStdCount=9/feltObligationCount=14/exactContractIds=50/oracleBindings=50/phaseBarriers=7）
- G10-5: Verifier 不调用 Compiler（NEG-ARCH-001）
- G10-6: final schema 验证通过
- G10-7: inputSlot lossless（从 plan 到 compiled）
- G10-8: compiled IR 无 formalPassCount 字段

---

## L. 提交拆分

### L.1 已完成（提交 1）

```
docs/resource-gateway-capability-studio-acceptance-plan-compiler-implementation-design.md
docs/resource-gateway-capability-studio-compiled-acceptance-ir-design.md
```

wire 序列化、packaged profile、3 个 authority resources、compiled-plan-valid fixture 已在前序 commit 完成。

### L.2 后续提交

- **提交 2**：Compiler + Registry + Protocol（package-private）
- **提交 3**：Verifier（独立，不调用 Compiler）
- **提交 4**：Tests + JAR inventory 验证 + full 37+8 条门验证
- **提交 5**：docs/（如有最终调整）

### L.3 JAR 边界

- `bloge-resource-gateway-test-kit-*.jar`（ordinary/shaded main JAR）包含 4 schemas + 3 authority files
- `a1-protocol JAR` 不包含 Phase 1 schemas

---

## M. Definition of Done

### M.1 状态声明

本文档为 Frozen Implementation Contract（FROZEN_IMPLEMENTATION_CONTRACT）。Frozen 文档不依赖代码实现已完成；其约束在实现阶段必须满足。

### M.2 Design Gate（已满足）

以下 design gate 条件已满足：

1. ✅ 4 schemas 已提交且 fingerprint 已固化（schema fingerprint 非 plan/catalog semantic）
2. ✅ 3 authority resources 已提交；4 个 material fingerprint（catalogRaw/catalogSemantic/profileRaw/registrySemantic）已固化；schema fingerprint 和 plan semantic/compiled fingerprint 待 Compiler 生成
3. ✅ wire 序列化、profile、fixtures 已完成
4. ✅ compile-only、无 PASS/ACCEPTED 字段、IR 无 formalPassCount、外部正式进度 0/27 已明确

### M.3 Implementation Gate（待满足）

以下 implementation gate 条件在对应代码提交后方可关闭：

1. ⬜ Compiler 实现通过全部 37 条 lossless IR test gates
2. ⬜ Verifier 实现通过隔离性测试（不调用 Compiler）
3. ⬜ 全部 8 条附加边界测试通过
4. ⬜ `git diff --check` 无警告
5. ⬜ Javadoc doclint 全通过

### M.4 开放边界（Phase 2+）

以下不在 Phase 1 范围内：

1. Runner / Scheduler / stateful 执行（Phase 3+）
2. EXTERNAL_PUBLICATION phase runtime 效果闭包
3. formalPassCount 写入逻辑
4. catalogSemanticFingerprint / primitiveRegistryFingerprint 之外的 profile expected 值动态验证机制
