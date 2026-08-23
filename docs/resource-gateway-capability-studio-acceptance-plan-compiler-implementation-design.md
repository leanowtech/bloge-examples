# Capability Studio Acceptance Plan Compiler — Phase 1 实现设计

> 状态：`DESIGNED`（Phase 2 前置文档）
>
> 适用范围：`resource-gateway-test-kit` 模块内的 Plan Compiler 与独立 Plan Verifier
>
> 设计冻结：本文与 `docs/resource-gateway-capability-studio-convergent-acceptance-engine-technical-design.md` §6/8/9/16 Phase 1 严格一致，不发明架构
>
> **相位归属**：Phase 1 = §16 GATE-B COMPILE_ONLY。Gate A（typed replay + independent verify + gate admission）是独立信任闭环，作为 Phase 0 前置；Gate A 与 Phase 1 属于不同纵切，不在本设计范围内。



## 1. 设计决策冻结

### 1.1 明确拒绝：直接硬编码 Runner

**不实现** `FormalV2RunnerImpl` 或任何等效上帝类直接选择 primitive 顺序或缩小分母。

原因：Runner 必须执行 Compiler 输出的 `CompiledAcceptancePlan`，不能自行决定执行顺序或跳过义务。直接硬编码绕过了 Plan 作为单一事实来源的约束，破坏可验证性。

Phase 1 的唯一目标是：**证明 27 个 Stage-exit 合同 + 9 个 AC-STD 合同 + 14 个 FELT obligation + 9 Canonical Case × 3 suite run 能被确定性编译且结果指纹稳定**。

Phase 1 后才能引入 Phase 3 Scheduler 和 stateful Runner。

### 1.2 架构决策

| 决策 | 结论 | 理由 |
|---|---|---|
| Compiler 与 Verifier 分离 | 两独立类，不共享 verdict | Verifier 必须独立重算，不能把 Compiler 输出当信任根 |
| Registry/Profile/Protocol 为 package-private 内部固定 | Compiler/Verifier 构造器不接受 Registry/Profile 注入 | 防止调用方替换信任根 |
| Wire Schema 先行 | 4 个 Draft 2020-12 Schema 先于 Java 实现 | Schema 冻结后实现才可判定正确性 |
| Plan/Catalog 输入均为 bounded bytes | Compiler.compile(planBytes, catalogBytes)；Verifier.verify(planBytes, catalogBytes, compiledPlanBytes)；Compiler/Verifier 内部独立加载 packaged profile 和 closed registry | 不解析网络/文件路径；bounded 字节由调用方提供 |
| Catalog 为 bounded 独立 wire | Catalog 有独立 Schema、raw bytes 与 domain-separated fingerprint；Compiler 必须验证传入 bytes 的 Schema 合规性、catalogRef 与 fingerprint 精确匹配 | 不能只用代码 Map 把 catalog wire 变摆设 |
| formalPassCount 在 Phase 1 始终为 0/27 | Compiler/Verifier 不生成 PASS | PASS 只能由 typed verifier ProofRecord 推导，不在编译阶段产生 |

## 2. 一等领域对象（Phase 1 范围）

### 2.1 `AcceptanceContractCatalog`

Phase 1 使用 bounded wire Catalog 字节作为独立输入，由 packaged JSON 实例提供便捷读取出口。

**固定分母（不得混淆）：**

| 字段 | 值 | 说明 |
|---|---|---|
| `stageExitContractCount` | `27` | S0:6, S1:5, S2:4, S3:4, S4:4, S5:4 |
| `acStdCount` | `9` | `AC-STD-01` 至 `AC-STD-09` |
| `feltObligationCount` | `14` | `FELT-01` 至 `FELT-14` |
| `canonicalMatrixCellCount` | `27` | 9 Canonical Case × 3 轮 suite run |
| `suiteRunCount` | `3` | 三轮独立 suite run |

**Schema key:** `bloge.capability-studio.contract-catalog.v1`

Catalog 实例路径：`docs/acceptance/capability-studio/acceptance-engine-v1/builtin-contract-catalog.json`

**Catalog 接受逻辑：** plan.catalogRef 绑定 plan 提交时的 actual catalog raw fingerprint；Compiler 验证 catalogSemanticFingerprint 等于 packaged profile 声明的 expectedCatalogSemanticFingerprint（semantic equality check）。raw bytes 空白变化、对象键顺序变化、换行符变化会改变 catalogRawFingerprint（被 catalogRef 检测到）但不改变 semantic fingerprint（可接受）。若 contractEntry 字段内容变化导致 catalogSemanticFingerprint 漂移 → INVALID_CATALOG_SEMANTICS。

### 2.2 `AcceptancePlanSource`

只读字节输入，不执行 I/O：

```
byte[] planBytes          // bounded，来自调用方
String schemaVersion     // 必须 = "bloge.capability-studio.acceptance-plan.v1"
byte[] canonicalBytes     // 见 §2.5 仓库 canonicalization 规则
String planSourceSemanticFingerprint  // sha256:domain-separated(plan semantic canonical bytes)
```

禁止字段：`className`、`script`、`url`、`expression`、`serviceLoader`、`produces`、`requiredRoles`。Plan Source 只允许 `id`/`type`/`revision`/`dependsOn[]`/`inputSlot`；`produces`/`requiredRoles` 等产出和角色由唯一 Registry + Catalog 派生不由调用方声明。

### 2.3 Fingerprint 语义与 Domain-Separated 公式

**不使用 RFC 8785 JCS。不声称 EvidenceVerificationSupport 直接提供 domain separation。Protocol 自己实现长度前缀 SHA-256，可复用 EvidenceVerificationSupport 递归键排序思想。**

六个隔离 domain，公式如下：

所有 fingerprint 使用 big-endian 32-bit unsigned length prefix（I32BE，4 字节无符号整数）。I32BE 前缀用于防止字段拼接时的字节边界歧义，不依赖其防碰撞属性。

```
// Domain 1: plan source — 对领域语义规范化后的 canonical bytes 做 hash
planSourceSemanticFingerprint =
  "sha256:" + SHA256(
    UTF8("RG-CS-PLAN-SOURCE-SEMANTIC-v1")
    + I32BE(byteLength(canonicalPlanSourceBytes))
    + canonicalPlanSourceBytes
  )
// 注意：primitive/dependsOn 等集合排列变化可通过语义规范化保持稳定

// Domain 2: catalog raw — 对调用方 exact catalogBytes 原始字节做 hash
// 不 canonicalize；空白变化、对象键顺序变化、换行符变化必须改变 raw fingerprint
catalogRawFingerprint =
  "sha256:" + SHA256(
    UTF8("RG-CS-CATALOG-RAW-v1")
    + I32BE(byteLength(catalogBytes))
    + catalogBytes
  )

// Domain 2.5: catalog semantic — 对 catalog 领域语义规范化后的 canonical bytes 做 hash
// 与 catalogRawFingerprint 不同：空白变化、对象键顺序变化、换行符变化不改变 catalogSemanticFingerprint
// 只有 contractEntry 字段内容变化才改变
catalogSemanticFingerprint =
  "sha256:" + SHA256(
    UTF8("RG-CS-CATALOG-SEMANTIC-v1")
    + I32BE(byteLength(canonicalCatalogBytes))
    + canonicalCatalogBytes
  )
// canonicalCatalogBytes = canonicalize(完整 Catalog wire，catalog wire 本身不含 catalogSemanticFingerprint 字段)

// Domain 3: compiler profile — 绑定 packaged exact raw bytes
compilerProfileRawFingerprint =
  "sha256:" + SHA256(
    UTF8("RG-CS-COMPILER-PROFILE-RAW-v1")
    + I32BE(byteLength(compilerProfileBytes))
    + compilerProfileBytes
  )

// Domain 4: compiled plan — compiled canonical body 自指（不含 compiledPlanFingerprint 自身）
compiledPlanFingerprint =
  "sha256:" + SHA256(
    UTF8("RG-CS-COMPILED-PLAN-v1")
    + I32BE(byteLength(canonicalCompiledBodyBytes))
    + canonicalCompiledBodyBytes
  )
// 其中 canonicalCompiledBodyBytes = canonicalize(CompiledAcceptancePlan with compiledPlanFingerprint=null)
// catalog canonicalization: 集合型字段（contractEntry 按 contractId 排序）做领域排序；所有数组内字段递归键排序

// Domain 6: primitive registry — 内建 fixed closed registry 的 exact canonical bytes
// Phase 1：仅绑定 verifierId/revision；verifier artifact/profile fingerprint 留 Phase 2 Proof Registry，不在 Phase 1 声称已闭合
// preimage = 8 个 Descriptor 按 typeId 升序排列后各自 canonicalize，再拼接
primitiveRegistryFingerprint =
  "sha256:" + SHA256(
    UTF8("RG-CS-PRIMITIVE-REGISTRY-v1")
    + I32BE(byteLength(canonicalRegistryBytes))
    + canonicalRegistryBytes
  )
// canonicalRegistryBytes = 按 typeId 升序拼接 eachDescriptorCanonicalBytes；每个 Descriptor canonicalize 时移除 outputEvidenceKinds 中的 nullable field 差异

// verificationFingerprint（Verifier 输出）同样自指：
verificationFingerprint =
  "sha256:" + SHA256(
    UTF8("RG-CS-COMPILED-PLAN-VERIFICATION-v1")
    + I32BE(byteLength(canonicalResultBytes))
    + canonicalResultBytes
  )
// 其中 canonicalResultBytes = canonicalize(VerificationResult with verificationFingerprint=null)

所有 fingerprint 使用 `I32BE`（big-endian 32-bit unsigned length prefix）。

### 2.4 `CompiledAcceptancePlan`

Compiler 的唯一权威输出，至少包含：

```text
planId
planRevision
planFingerprint
compilerFingerprint
catalogFingerprint
stageExitContractCount = 27
expandedObligationIds
canonicalMatrixCellCount
matrixCellIds
suiteRunIds
stableExecutionOrder
primitiveContracts
expectedEvidenceRoles
oracleBindings
factRequirements
resumeGraph
terminalGate
primitiveRegistryFingerprint   // Domain 6；Compiler 重算并写入 wire
```

`stageExitContractCount`、`canonicalMatrixCellCount` 和 `matrixCellIds` 是三套独立字段。Canonical 9 Case x 3 轮的计划必须同时给出 27 个稳定 `matrixCellId` 和 3 个稳定 suite run identity；它们不能从 `stageExitContractCount` 推导，也不能写入裸 `expectedCount=27` 后由消费者猜测语义。

### 2.5 Canonicalization（仓库既有规则 + duplicate detection）

Phase 1 canonicalization 规则（仓库级既定，不在本设计范围内修改）：

- JSON 对象键升序排序
- 数组内各元素独立递归 canonicalize 后拼接（不是按 JSON 数组整体排序）
- 移除 JSON 注释和空白（空格、制表、换行）

Phase 1 补充规则：

- **重复字段检测**：必须显式启用 `Jackson StreamReadFeature.STRICT_DUPLICATE_DETECTION`；Parser 在遇到重复键时直接抛出 `JsonParseException`（非 Schema 层面拒绝），因此 Schema validation 不会看到任何重复键

### 2.6 `PrimitiveDescriptor`

Primitive 是引擎唯一可执行动作。每个 primitive 必须声明：

```text
primitiveId + revision
effectClass
inputKinds
outputEvidenceKinds
typedVerifierId + revision
retryPolicy
failureMapping
capabilityRequirements
```

`primitiveId` 来自 Test Kit 内建 closed registry。Plan 不能指定 Java class，也不能通过反射发现任意实现。

`PrimitiveDescriptor` 是 Compiler 与 Proof Verifier 唯一共享的声明来源。Compiler 读取其中的 input/output/effect/phase 定义做静态检查，Verifier 读取其中的 verifier artifact/profile 绑定做 proof replay；两边不得各自维护一份 role、Oracle 或 failure mapping 表。业务 Oracle 的实现仍只存在于既有 typed verifier/Runtime，Descriptor 只保存 exact ref，不能内嵌表达式。

**Phase 1 绑定范围：** `typedVerifierId` 和 `revision` 字段在 Phase 1 写入 wire；`verifierArtifactFingerprint` 和 `verifierPolicyFingerprint` 留 Phase 2 Proof Registry 实现，不在本设计范围内声称已闭合。

## 3. Corr. / ADR — Phase 顺序矛盾冻结

### §8.1（内建 plan 依赖）vs §9（phase skeleton）矛盾

**问题：** §8.1 内建 plan 的依赖链是 `verify → postflight → commit-local-proof`（即 INDEPENDENT_VERIFICATION → MATERIAL_POSTFLIGHT → DURABLE_LOCAL_COMMIT），但 §9 phase skeleton 原始顺序把 DURABLE_LOCAL_COMMIT 放在 INDEPENDENT_VERIFICATION 之前。两者不能同时成立。

**冻结结论：** Phase 1 compiler profile 采用以下 phase 顺序（与 §8.1 依赖一致）：

```
BOOTSTRAP_FACTS
  -> MATERIAL_SNAPSHOT           // VERIFY_FIXED_MATERIAL_V1
  -> READ_ONLY_PREFLIGHT         // VERIFY_FORMAL_INPUT_TREE_V1 (两实例)
  -> PROVIDER_CONFORMANCE        // RUN_PROVIDER_CONFORMANCE_V2
  -> STATEFUL_EXECUTION          // EXECUTE_LEASE_EVIDENCE_V1
  -> INDEPENDENT_VERIFICATION    // VERIFY_DURABLE_WRAPPER_V1, VERIFY_PACKAGED_FELT_MATRICES_V1
  -> MATERIAL_POSTFLIGHT         // VERIFY_MATERIAL_POSTFLIGHT_V1；post-lease PURE_VERIFY，合法
  -> DURABLE_LOCAL_COMMIT       // COMMIT_LOCAL_PROOF_BUNDLE_V1（内建 plan 最终 primitive，Phase 1 只编译不执行）
  -> EXTERNAL_PUBLICATION        // Phase 4
  -> EXTERNAL_ADJUDICATION       // Phase 5
```

**需要同步修正：主 technical design §9 需在代码提交前同步更新为上述顺序。** 主 technical design §9 phase skeleton 需在代码提交前更新为上述顺序，以保持文档与实现的长期一致。

## 4. Phase Barrier 与 Effect Barrier

### 4.1 Phase Barrier（稳定拓扑排序）

**稳定执行顺序：** 在 phase 内按 phase 分组，组内用 Kahn priority queue 拓扑排序——优先队列只纳入 in-degree=0 的节点，同 in-degree 节点按 primitive ID 升序 tie-break。这样同 phase 内有依赖关系的节点（如 verify-authority-tree → provider-conformance）也能保证正确顺序。**依赖顺序不作为语义**，即 `A→B` 与 `B→A` 在依赖满足且同 phase 前提下，若无直接依赖，则执行顺序变化不影响 `compiledPlanFingerprint`。

**语义集合 canonical sort：** `executionOrder` 中 primitive 列表按 phase 分组、组内按 primitive ID 升序排列。相同语义的输入（primitive 集合相同但排列不同）必须得到相同的 `compiledPlanFingerprint`。

### 4.2 Effect Barrier（technical design §9 + 本 ADR 修正版）

以下 barrier 在编译期强制检查：

| Barrier ID | 条件 | 拒绝理由 |
|---|---|---|
| `PURE_VERIFY_GATE` | preflight 只读 phases（MATERIAL_SNAPSHOT / READ_ONLY_PREFLIGHT / PROVIDER_CONFORMANCE）的全部 PURE_VERIFY primitive 必须在任何 AUTHORITY_LEASE 或 LOCAL_IMMUTABLE_WRITE 之前；且 AUTHORITY_LEASE 必须通过传递依赖路径包含 PROVIDER_CONFORMANCE；**MATERIAL_POSTFLIGHT（即 VERIFY_MATERIAL_POSTFLIGHT_V1）是 post-lease PURE_VERIFY，合法且必须在 DURABLE_LOCAL_COMMIT 之前执行** | `INVALID_BARRIER_BYPASS` |
| `LEASE_GATE` | AUTHORITY_LEASE 前必须有 PROVIDER_CONFORMANCE 的传递依赖路径 | `INVALID_BARRIER_BYPASS` |
| `NO_DELETE_AFTER_LEASE` | Registry effect closure 中 `AUTHORITY_LEASE` 的 `capabilityRequirements` 声明 `preservesExistingEvidence=true`；Compiler 验证：任何 LOCAL_IMMUTABLE_WRITE 或 EXTERNAL_PUBLISH primitive 的 `capabilityRequirements` 中不得出现删除/覆盖既有 evidence 的声明 | `INVALID_BARRIER_BYPASS` |
| `DURABLE_COMMIT_GATE` | DURABLE_LOCAL_COMMIT 必须在任何 EXTERNAL_PUBLISH 之前 | `INVALID_BARRIER_BYPASS` |
| `STORE_RECEIPT_GATE` | Store receipt phase > DURABLE_LOCAL_COMMIT phase（Phase 4） | `INVALID_BARRIER_BYPASS` |
| `OWNER_SIGNOFF_GATE` | Owner signoff phase > R1 phase（Phase 5） | `INVALID_BARRIER_BYPASS` |
| `NO_ACCEPTED_FROM_LOCAL` | 本地 primitive 不得产生 ACCEPTED | `INVALID_BARRIER_BYPASS` |

**注意：`NO_DELETE_AFTER_LEASE` 不靠排序证明，而是通过 Registry effect closure 和 capability 声明静态验证。** Registry 中 `AUTHORITY_LEASE` 和 `LOCAL_IMMUTABLE_WRITE` 的 `capabilityRequirements` 声明它们是 preserve-only 还是允许 delete/overwrite。Compiler 拒绝任何 capability 与 barrier 条件矛盾的 plan。

## 5. 四个 Wire Schema（Draft 2020-12）

所有 Schema 必须满足：
- `additionalProperties: false`
- `schemaVersion` 使用 `const` string 作为 discriminator
- 所有 fingerprint 使用 `sha256:[0-9a-f]{64}`
- 所有 enum 为 closed vocabulary
- 有界数组/对象字段带 `minItems`/`maxItems`

Schema 文件路径（`docs/schemas/resource-gateway-capability-studio/`，已在 pom.xml 复制到 ordinary 和 shaded main JAR；**不在** a1 JAR）：

**Schema AC3 精确集合验证：** schema 中的 `"const"` 字段（27/9/14/27/3）与 required arrays 同时验证，保证精确集合。Catalog `const` 拒绝不等于指定值的 `schemaVersion`。

### 5.1 `bloge.capability-studio.acceptance-plan.v1`

文件名：`capability-studio-acceptance-plan-v1.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://leanowtech.com/schemas/bloge.capability-studio.acceptance-plan.v1",
  "title": "Capability Studio Acceptance Plan v1",
  "type": "object",
  "additionalProperties": false,
  "required": [
    "schemaVersion", "planId", "revision", "compilerProfile",
    "catalogRef", "obligationSet", "primitives", "terminalGate"
  ],
  "properties": {
    "schemaVersion": { "const": "bloge.capability-studio.acceptance-plan.v1" },
    "planId": { "type": "string", "maxLength": 128 },
    "revision": { "type": "integer", "minimum": 1 },
    "compilerProfile": { "type": "string", "maxLength": 128 },
    "catalogRef": { "type": "string", "maxLength": 256 },
    "obligationSet": { "type": "string", "maxLength": 128 },
    "primitives": {
      "type": "array",
      "minItems": 1,
      "maxItems": 64,
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["id", "type", "dependsOn"],
        "properties": {
          "id": { "type": "string", "maxLength": 128 },
          "type": { "type": "string", "maxLength": 128 },
          "dependsOn": {
            "type": "array",
            "items": { "type": "string", "maxLength": 128 }
          },
          "inputSlot": { "type": "string", "maxLength": 64 }
        }
      }
    },
    "terminalGate": {
      "enum": ["DEVELOPMENT_VERIFIED_ONLY"]
    }
  }
}
```

### 5.2 `bloge.capability-studio.contract-catalog.v1`

文件名：`capability-studio-contract-catalog-v1.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://leanowtech.com/schemas/bloge.capability-studio.contract-catalog.v1",
  "title": "Capability Studio Contract Catalog v1",
  "type": "object",
  "additionalProperties": false,
  "required": [
    "schemaVersion", "catalogId", "catalogRevision",
    "stageExitContracts", "acStandards", "feltObligations",
    "canonicalCases", "suiteRuns",
    "catalogRawFingerprint", "catalogSemanticFingerprint"
  ],
  "properties": {
    "schemaVersion": { "const": "bloge.capability-studio.contract-catalog.v1" },
    "catalogId": { "type": "string" },
    "catalogRevision": { "type": "integer" },
    "stageExitContracts": {
      "type": "array",
      "minItems": 27, "maxItems": 27,
      "items": { "$ref": "#/$defs/contractEntry" }
    },
    "acStandards": {
      "type": "array",
      "minItems": 9, "maxItems": 9,
      "items": { "$ref": "#/$defs/contractEntry" }
    },
    "feltObligations": {
      "type": "array",
      "minItems": 14, "maxItems": 14,
      "items": { "$ref": "#/$defs/contractEntry" }
    },
    "canonicalCases": {
      "type": "array",
      "minItems": 27, "maxItems": 27,
      "items": { "$ref": "#/$defs/canonicalCaseEntry" }
    },
    "suiteRuns": {
      "type": "array",
      "minItems": 3, "maxItems": 3,
      "items": { "$ref": "#/$defs/suiteRunEntry" }
    },
    "catalogRawFingerprint": { "$ref": "#/$defs/sha256" },
    "catalogSemanticFingerprint": { "$ref": "#/$defs/sha256" }
  },
  "$defs": {
    "sha256": {
      "type": "string",
      "pattern": "^sha256:[0-9a-f]{64}$"
    },
    "contractEntry": {
      "type": "object",
      "additionalProperties": false,
      "required": ["contractId", "category"],
      "properties": {
        "contractId": { "type": "string" },
        "category": { "type": "string" }
      }
    },
    "canonicalCaseEntry": {
      "type": "object",
      "additionalProperties": false,
      "required": ["matrixCellId", "canonicalCaseId"],
      "properties": {
        "matrixCellId": { "type": "string" },
        "canonicalCaseId": { "type": "string" }
      }
    },
    "suiteRunEntry": {
      "type": "object",
      "additionalProperties": false,
      "required": ["suiteRunId", "suiteRunNumber"],
      "properties": {
        "suiteRunId": { "type": "string" },
        "suiteRunNumber": { "type": "integer" }
      }
    }
  }
}
```

Schema `const` 字段（27/9/14/27/3）与 required arrays 同时验证，保证精确集合。

### 5.3 `bloge.capability-studio.compiled-plan.v1`

文件名：`capability-studio-compiled-acceptance-plan-v1.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://leanowtech.com/schemas/bloge.capability-studio.compiled-plan.v1",
  "title": "Capability Studio Compiled Acceptance Plan v1",
  "type": "object",
  "additionalProperties": false,
  "required": [
    "schemaVersion", "planId", "revision",
    "planSourceSemanticFingerprint", "catalogRawFingerprint", "catalogSemanticFingerprint",
    "compilerProfileRawFingerprint", "primitiveRegistryFingerprint",
    "stageExitContractCount", "acStdCount", "feltObligationCount",
    "canonicalMatrixCellCount", "suiteRunCount",
    "matrixCellIds", "suiteRunIds", "exactContractIds",
    "primitiveContracts", "phaseBarriers", "executionOrder",
    "expectedEvidenceRoles", "oracleBindings",
    "terminalGate", "compiledPlanFingerprint"
  ],
  "properties": {
    "schemaVersion": { "const": "bloge.capability-studio.compiled-plan.v1" },
    "planId": { "type": "string", "maxLength": 128 },
    "revision": { "type": "integer", "minimum": 1 },
    "planSourceSemanticFingerprint": { "$ref": "#/$defs/sha256" },
    "catalogRawFingerprint": { "$ref": "#/$defs/sha256" },
    "catalogSemanticFingerprint": { "$ref": "#/$defs/sha256" },
    "compilerProfileRawFingerprint": { "$ref": "#/$defs/sha256" },
    "primitiveRegistryFingerprint": { "$ref": "#/$defs/sha256" },
    "stageExitContractCount": { "type": "integer", "const": 27 },
    "acStdCount": { "type": "integer", "const": 9 },
    "feltObligationCount": { "type": "integer", "const": 14 },
    "canonicalMatrixCellCount": { "type": "integer", "const": 27 },
    "suiteRunCount": { "type": "integer", "const": 3 },
    "matrixCellIds": {
      "type": "array",
      "minItems": 27,
      "maxItems": 27,
      "uniqueItems": true,
      "items": { "type": "string", "maxLength": 128 }
    },
    "suiteRunIds": {
      "type": "array",
      "minItems": 3,
      "maxItems": 3,
      "uniqueItems": true,
      "items": { "type": "string", "maxLength": 128 }
    },
    "exactContractIds": {
      "type": "array",
      "minItems": 50,
      "maxItems": 50,
      "uniqueItems": true,
      "items": { "type": "string", "maxLength": 128 }
    },
    "primitiveContracts": {
      "type": "array",
      "minItems": 1,
      "maxItems": 64,
      "items": { "$ref": "#/$defs/compiledPrimitive" }
    },
    "phaseBarriers": {
      "type": "array",
      "maxItems": 32,
      "items": { "$ref": "#/$defs/phaseBarrier" }
    },
    "executionOrder": {
      "type": "array",
      "minItems": 1,
      "maxItems": 64,
      "items": { "type": "string", "maxLength": 128 }
    },
    "expectedEvidenceRoles": {
      "type": "array",
      "maxItems": 64,
      "items": { "$ref": "#/$defs/evidenceRoleBinding" }
    },
    "oracleBindings": {
      "type": "array",
      "maxItems": 64,
      "items": { "$ref": "#/$defs/oracleBinding" }
    },
    "terminalGate": { "enum": ["DEVELOPMENT_VERIFIED_ONLY"] },
    "compiledPlanFingerprint": { "$ref": "#/$defs/sha256" }
  },
  "$defs": {
    "sha256": {
      "type": "string",
      "pattern": "^sha256:[0-9a-f]{64}$"
    },
    "compiledPrimitive": {
      "type": "object",
      "additionalProperties": false,
      "required": ["primitiveId", "type", "effectClass", "phase"],
      "properties": {
        "primitiveId": { "type": "string" },
        "type": { "type": "string" },
        "effectClass": { "type": "string" },
        "phase": { "type": "string" }
      }
    },
    "phaseBarrier": {
      "type": "object",
      "additionalProperties": false,
      "required": ["barrierId", "phase"],
      "properties": {
        "barrierId": { "type": "string" },
        "phase": { "type": "string" }
      }
    },
    "evidenceRoleBinding": {
      "type": "object",
      "additionalProperties": false,
      "required": ["role", "producerPrimitiveId"],
      "properties": {
        "role": { "type": "string" },
        "producerPrimitiveId": { "type": "string" }
      }
    },
    "oracleBinding": {
      "type": "object",
      "additionalProperties": false,
      "required": ["oracleId", "primitiveId"],
      "properties": {
        "oracleId": { "type": "string" },
        "primitiveId": { "type": "string" }
      }
    }
  }
}
```

### 5.4 `bloge.capability-studio.compiled-plan-verification-result.v1`

文件名：`capability-studio-compiled-plan-verification-result-v1.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://leanowtech.com/schemas/bloge.capability-studio.compiled-plan-verification-result.v1",
  "title": "Capability Studio Compiled Plan Verification Result v1",
  "type": "object",
  "additionalProperties": false,
  "required": ["schemaVersion", "planId", "revision", "status", "verificationFingerprint"],
  "properties": {
    "schemaVersion": { "const": "bloge.capability-studio.compiled-plan-verification-result.v1" },
    "planId": { "type": "string" },
    "revision": { "type": "integer" },
    "status": { "$ref": "#/$defs/Status" },
    "expectedFingerprint": { "$ref": "#/$defs/sha256" },
    "recomputedFingerprint": { "$ref": "#/$defs/sha256" },
    "verificationFingerprint": { "$ref": "#/$defs/sha256" },
    "reasonCode": { "type": ["string", "null"] },
    "reasonField": { "type": ["string", "null"] }
  },
  "oneOf": [
    {
      "required": ["status", "verifiedResult"],
      "properties": {
        "status": { "const": "VERIFIED" },
        "verifiedResult": {
          "type": "object",
          "additionalProperties": false,
          "required": ["expectedFingerprint", "recomputedFingerprint", "verificationFingerprint"],
          "properties": {
            "expectedFingerprint": { "$ref": "#/$defs/sha256" },
            "recomputedFingerprint": { "$ref": "#/$defs/sha256" },
            "verificationFingerprint": { "$ref": "#/$defs/sha256" }
          }
        }
      }
    },
    {
      "required": ["status", "invalidResult"],
      "properties": {
        "status": { "const": "INVALID" },
        "invalidResult": {
          "type": "object",
          "additionalProperties": false,
          "required": ["reasonCode"],
          "properties": {
            "reasonCode": { "type": "string" },
            "reasonField": { "type": ["string", "null"] },
            "expectedFingerprint": { "type": ["string", "null"] },
            "recomputedFingerprint": { "type": ["string", "null"] }
          }
        }
      }
    },
    {
      "required": ["status", "unavailableResult"],
      "properties": {
        "status": { "const": "UNAVAILABLE" },
        "unavailableResult": {
          "type": "object",
          "additionalProperties": false,
          "required": ["reasonCode"],
          "properties": {
            "reasonCode": { "type": "string" },
            "reasonField": { "const": null }
          }
        }
      }
    }
  ],
  "$defs": {
    "sha256": {
      "type": "string",
      "pattern": "^sha256:[0-9a-f]{64}$"
    },
    "Status": { "type": "string", "enum": ["VERIFIED", "INVALID", "UNAVAILABLE"] }
  }
}
```

Schema oneOf 联动 status discriminant：
- `VERIFIED` → `verifiedResult` 分支（所有 boolean 字段 required；expected==recomputed 由语义 verifier 保证相等）
- `INVALID` → `invalidResult` 分支（reasonCode 含 INVALID_* 和 INVALID_CATALOG_SEMANTICS；early failure 时 expected/recomputed 可为 null）
- `UNAVAILABLE` → `unavailableResult` 分支（planId/revision 可 null，reasonField 固定 null）

## 6. Golden Vectors

内建 plan 和 catalog 的 golden fingerprint vectors（Schema 冻结后由 Compiler 自己计算并固化）：

| 向量 | 值 |
|---|---|
| 内建 catalog raw fingerprint | `builtin-contract-catalog.json` 的 exact SHA256 |
| 内建 catalog semantic fingerprint | canonical catalog bytes 的 SHA256 |
| 内建 compiler profile fingerprint | `builtin-compiler-profile-formal-v1.json` 的 exact SHA256 |
| 内建 primitive registry fingerprint | 按 typeId 排序的 8 个 Descriptor canonical bytes 的 SHA256 |
| 内建 compiled plan fingerprint（golden） | 首次编译输出的 exact canonical SHA256 |
| 内建 compiled plan verification result | `status=VERIFIED` + verificationFingerprint |

Golden vectors 写入 `docs/acceptance/capability-studio/acceptance-engine-v1/` 目录，文件名对应各向量。

## 7. Phase 1 内建 Plan 与 Primitive Registry

Phase 1 使用内建（hardcoded）Plan 和 Registry，不接受外部传入。

内建 primitive registry 固定 8 个 Descriptor（按 §2.6）：

| typeId | effectClass | Phase |
|---|---|---|
| `VERIFY_FIXED_MATERIAL_V1` | PURE_VERIFY | BOOTSTRAP_FACTS / MATERIAL_SNAPSHOT |
| `VERIFY_FORMAL_INPUT_TREE_V1` | PURE_VERIFY | READ_ONLY_PREFLIGHT |
| `RUN_PROVIDER_CONFORMANCE_V2` | PURE_VERIFY | PROVIDER_CONFORMANCE |
| `EXECUTE_LEASE_EVIDENCE_V1` | AUTHORITY_LEASE | STATEFUL_EXECUTION |
| `VERIFY_DURABLE_WRAPPER_V1` | PURE_VERIFY | INDEPENDENT_VERIFICATION |
| `VERIFY_PACKAGED_FELT_MATRICES_V1` | PURE_VERIFY | INDEPENDENT_VERIFICATION |
| `VERIFY_MATERIAL_POSTFLIGHT_V1` | PURE_VERIFY | MATERIAL_POSTFLIGHT |
| `COMMIT_LOCAL_PROOF_BUNDLE_V1` | LOCAL_IMMUTABLE_WRITE | DURABLE_LOCAL_COMMIT |

Phase 1 内建 plan 精确 9 个 primitive instance（按 §3 ADR 冻结的 phase 顺序）：

1. verify-fixed-material（VERIFY_FIXED_MATERIAL_V1）
2. verify-authority-tree（VERIFY_FORMAL_INPUT_TREE_V1，dependsOn=verify-fixed-material，inputSlot=AUTHORITY）
3. verify-target-tree（VERIFY_FORMAL_INPUT_TREE_V1，dependsOn=verify-fixed-material，inputSlot=TARGET）
4. provider-conformance（RUN_PROVIDER_CONFORMANCE_V2，dependsOn=verify-authority-tree, verify-target-tree）
5. execute-lease-evidence（EXECUTE_LEASE_EVIDENCE_V1，dependsOn=provider-conformance）
6. verify-durable-wrapper（VERIFY_DURABLE_WRAPPER_V1，dependsOn=execute-lease-evidence）
7. verify-packaged-matrices（VERIFY_PACKAGED_FELT_MATRICES_V1，dependsOn=verify-durable-wrapper）
8. verify-postflight（VERIFY_MATERIAL_POSTFLIGHT_V1，dependsOn=verify-packaged-matrices）
9. commit-local-proof（COMMIT_LOCAL_PROOF_BUNDLE_V1，dependsOn=verify-postflight）

## 8. Compiler Profile 与 Verification Profile

内建 compiler profile（`builtin-compiler-profile-formal-v1.json`）包含：

```json
{
  "schemaVersion": "bloge.capability-studio.compiler-profile-formal.v1",
  "profileId": "formal-evidence-v1",
  "expectedCatalogSemanticFingerprint": "sha256:...",
  "expectedPrimitiveRegistryFingerprint": "sha256:...",
  "phaseOrder": [...],
  "allowedEffectClasses": [...],
  "barriers": [...]
}
```

Compiler 在编译时重算 `primitiveRegistryFingerprint`，与 `expectedPrimitiveRegistryFingerprint` 比较（精确匹配）。Verifier 在验证时同样重算，两端独立。

## 9. Catalog Fingerprint 与 Verifier Bindings

Compiler 验证 catalog fingerprint 两层：

1. `catalogRawFingerprint`：直接 SHA256(调用方传入 catalogBytes)
2. `catalogSemanticFingerprint`：SHA256(canonicalCatalogBytes)，与 `expectedCatalogSemanticFingerprint` 精确比较

Catalog 字节由调用方作为 bounded input 提供，不从文件系统读取。

## 10. Primitive Registry 内容

见 §7 内建 Registry 表。

---


### 11.1 Wire Schema 负例

| 测试 ID | 输入 | 预期 |
|---|---|---|
| `NEG-WIRE-001` | plan.json 含 `className` | Schema additionalProperties 拒绝 |
| `NEG-WIRE-002` | plan.json 含 `script` | Schema additionalProperties 拒绝 |
| `NEG-WIRE-003` | plan.json 含 `url` | Schema additionalProperties 拒绝 |
| `NEG-WIRE-004` | plan.json 含 `expression` | Schema additionalProperties 拒绝 |
| `NEG-WIRE-005` | plan.json 含 `serviceLoader` | Schema additionalProperties 拒绝 |
| `NEG-WIRE-006` | plan.json 含未声明字段 | Schema additionalProperties 拒绝 |
| `NEG-WIRE-007` | plan.json primitives 某元素含重复 id | Schema uniqueItems 拒绝 |
| `NEG-WIRE-008` | compiled plan 的 stageExitContractCount=26 | Schema const 拒绝 |
| `NEG-WIRE-009` | compiled plan 的 canonicalMatrixCellCount=26 | Schema const 拒绝 |
| `NEG-WIRE-010` | compiled plan 的 matrixCellIds size=26 | Schema minItems 拒绝 |
| `NEG-WIRE-011` | compiled plan 的 suiteRunIds size=2 | Schema minItems 拒绝 |
| `NEG-WIRE-012` | verification result 含 status=COMPILED | Schema oneOf 拒绝 |
| `NEG-WIRE-013` | verification result 含 reasonCode=PASS | Schema oneOf 拒绝 |
| `NEG-WIRE-014` | plan 只含 stageExitContractCount=27，缺少 canonicalMatrixCellCount 字段 | Schema required 拒绝 |
| `NEG-WIRE-015` | verification result VERIFIED 时 reasonCode 非 null | Schema oneOf 拒绝 |
| `NEG-WIRE-016` | verification result INVALID 时 reasonField=null | Schema oneOf 拒绝 |
| `NEG-WIRE-017` | verification result UNAVAILABLE 时含 reasonField | Schema oneOf 拒绝 |
| `NEG-WIRE-018` | plan.json schemaVersion = `bloge.capability-studio.contract-catalog.v1`（catalog 专用的 key） | Schema const `bloge.capability-studio.acceptance-plan.v1` 拒绝 |
| `NEG-WIRE-019` | catalog.json schemaVersion 值正确但 `const` discriminator 为 `wrong-value` | Schema const 拒绝（验证 discriminator 必须 exact match，不接受"值类型正确但不等"） |

### 11.1.5 Positive Compilation（内建 plan）

| 测试 ID | 场景 | 预期 |
|---|---|---|
| `POS-BUILTIN-001` | 内建 plan（9 个 primitive）用内建 catalog 编译 | COMPILED；compiledPlanFingerprint 与 golden fixture exact match |

### 11.2 集合精确性

| 测试 ID | 输入 | 预期 |
|---|---|---|
| `POS-COLLECT-001` | exact 50 contract IDs（S0-5:27 + AC-STD:9 + FELT:14） | COMPILED |
| `POS-COLLECT-002` | matrixCellIds 精确 27 个不同 ID | COMPILED |
| `POS-COLLECT-003` | suiteRunIds 精确 3 个不同 ID | COMPILED |
| `NEG-COLLECT-001` | 49 个 contract ID（缺一个 FELT） | INVALID_COLLECTION_SIZE |
| `NEG-COLLECT-002` | matrixCellIds 含重复 | Schema uniqueItems 拒绝 |
| `NEG-COLLECT-003` | stageExitContractCount=27 但 FELT 含 13 个 | INVALID_COLLECTION_SIZE |
| `NEG-COLLECT-004` | plan 只含 stageExitContractCount=27，缺少 canonicalMatrixCellCount | Schema required 拒绝 |
| `NEG-COLLECT-005` | catalog.json 缺少 acStandards 字段 | Schema required 拒绝 |

### 11.3 两个 27 分母隔离

| 测试 ID | 描述 | 预期 |
|---|---|---|
| `POS-27ISOLATE-001` | stageExitContractCount=27 + canonicalMatrixCellCount=27 + 各 required array size 正确 | COMPILED |
| `NEG-27ISOLATE-001` | plan 缺少两个 required 字段 | Schema required 拒绝 |
| `NEG-27ISOLATE-002` | compiled plan 中 stageExitContractCount 字段缺失 | Schema required 拒绝 |
| `NEG-27ISOLATE-003` | compiled plan 中 canonicalMatrixCellCount 字段缺失 | Schema required 拒绝 |

### 11.4 拓扑与 Dependency

| 测试 ID | 输入 | 预期 |
|---|---|---|
| `POS-TOPO-001` | 无循环、依赖完整，9 个 instance 按 §3 顺序 | COMPILED |
| `NEG-TOPO-CYCLE-001` | A→B→C→A | INVALID_TOPOLOGY_CYCLE |
| `NEG-TOPO-CYCLE-002` | 自环 A→A | INVALID_TOPOLOGY_CYCLE |
| `NEG-TOPO-UNKNOWN-001` | B 依赖不存在的 C | INVALID_TOPOLOGY_UNKNOWN_NODE |
| `NEG-TOPO-UNKNOWN-002` | plan 引用 registry 中不存在的 primitive type | INVALID_REGISTRY_TYPE_NOT_FOUND |
| `NEG-TOPO-REVISION-001` | plan 中 primitive revision 与 registry 不符 | INVALID_REGISTRY_REVISION_MISMATCH |
| `NEG-TOPO-DUP-001` | 两个 primitive 有相同 id | Schema uniqueItems + 语义双重拒绝 |

### 11.5 Barrier

| 测试 ID | 场景 | 预期 |
|---|---|---|
| `POS-BARRIER-001` | preflight phases 全 PURE_VERIFY 在前，postflight/durable-commit 在后 | COMPILED |
| `NEG-BARRIER-001` | LOCAL_IMMUTABLE_WRITE 出现在 preflight phases 之前 | INVALID_BARRIER_BYPASS |
| `NEG-BARRIER-002` | AUTHORITY_LEASE 无 PROVIDER_CONFORMANCE 传递依赖 | INVALID_BARRIER_BYPASS |
| `NEG-BARRIER-003` | EXTERNAL_PUBLISH 出现在 DURABLE_LOCAL_COMMIT 之前 | INVALID_BARRIER_BYPASS |
| `NEG-BARRIER-004` | primitive 声明 capability 为 destructive | INVALID_BARRIER_BYPASS |
| `NEG-BARRIER-005` | 按旧 §9 phase 顺序（commit-local-proof 在 INDEPENDENT_VERIFICATION 之前） | INVALID_BARRIER_BYPASS（证明矛盾已修复，防止回归） |

### 11.6 语义排序确定性

| 测试 ID | 描述 | 预期 |
|---|---|---|
| `POS-DET-001` | 两语义等价 plan（primitive 集合相同但 executionOrder 不同） | 两 compiledPlanFingerprint 相等 |
| `POS-DET-002` | 相同 plan 两次编译 | 两 compiledPlanFingerprint 相等（幂等性） |
| `NEG-DET-001` | 依赖顺序不同（语义等价） | compiledPlanFingerprint 必须相等 |
| `POS-DET-003` | golden fingerprint fixture 匹配 | COMPILED + golden fingerprint exact match |
| `POS-DET-004` | catalog raw bytes 仅空白变化（semantic 不变） | catalogRawFingerprint 改变，catalogSemanticFingerprint 不变，compiledPlanFingerprint 不变 |
| `POS-DET-005` | 相同 catalog bytes 两次传入 | catalogRawFingerprint 稳定且 compiledPlanFingerprint 稳定 |
| `POS-DET-006` | primitive executionOrder 排列不同，语义等价 | compiledPlanFingerprint 相等（semantic sort 稳定性） |
| `POS-DET-007` | catalog contractEntry 字段内容变化（semantic drift） | INVALID_CATALOG_SEMANTICS |

### 11.7 篡改检测

| 测试 ID | 场景 | 预期 |
|---|---|---|
| `NEG-TAMPER-001` | 修改 primitive id 后验证 | INVALID_FINGERPRINT_MISMATCH |
| `NEG-TAMPER-002` | 修改 stageExitContractCount=28 | INVALID_STAGE_EXIT_CONTRACT_COUNT |
| `NEG-TAMPER-003` | catalogRawFingerprint 与 catalogBytes 不匹配 | INVALID_FINGERPRINT_MISMATCH |
| `NEG-TAMPER-004` | expected vs recomputed compiledPlanFingerprint 不等 | INVALID_FINGERPRINT_MISMATCH |

### 11.8 Verifier 不调用 Compiler 的架构测试

| 测试 ID | 描述 | 预期 |
|---|---|---|
| `NEG-ARCH-001` | Verifier class constant pool 含 Compiler internal name | 架构测试失败 |
| `NEG-ARCH-002` | 验证 Catalog 时使用 Compiler 缓存 | INVALID：Verifier 必须独立重算 catalogRawFingerprint 和 catalogSemanticFingerprint |
| `NEG-ARCH-003` | 内建 registry 中 descriptor 内容变化（typeId 不变，verifierId/revision 变化） | compiledPlanFingerprint 变化（golden fingerprint 不匹配）；验证 Compiler 和 Verifier 都重算 primitiveRegistryFingerprint 并拒绝原 golden |

**NEG-ARCH-001 实现：** 扫描 verifier JAR compiled class constant pool，检测是否出现 `CapabilityStudioAcceptancePlanCompiler` 或其任何 `internal` 包名中的 internal name（含 `/`）。不依赖源码 import 检查。

### 11.9 Packaging 与 Schema

| 测试 ID | 描述 | 预期 |
|---|---|---|
| `POS-PACK-001` | ordinary main JAR (`bloge-resource-gateway-test-kit-1.0.0.jar`) 包含 4 个 Phase 1 Schema | Schema 可加载 |
| `POS-PACK-002` | shaded main JAR 包含 4 个 Phase 1 Schema | Schema 可加载 |
| `NEG-PACK-001` | shaded JAR 移除 Phase 1 Schema 后验证 | UNAVAILABLE_PACKAGED_SCHEMA |
| `NEG-PACK-002` | a1 protocol JAR 不包含 Phase 1 Schema | a1 JAR resource path 不含 Phase 1 schema |
| `POS-JAVADOC-001` | 所有 public API 有 Javadoc，含 @param/@return/@throws | javadoc doclint 通过 |
| `NEG-JAVADOC-001` | public API 缺少 Javadoc | javadoc doclint 失败 |

---

## 12. 精确文件清单

**4 个顶层类：**

```
acceptance/
  CapabilityStudioAcceptancePlanCompiler.java          // public; 嵌套 records/exceptions
  CapabilityStudioCompiledPlanVerifier.java              // public; 独立验证
  CapabilityStudioAcceptancePrimitiveRegistry.java        // package-private
  CapabilityStudioAcceptancePlanProtocol.java           // package-private; schema + domain-separated codec
```

**Built-in Catalog/Profile 实例（`docs/acceptance/capability-studio/acceptance-engine-v1/`）：**

```
builtin-contract-catalog.json              // 内建 27+9+14+9+3 精确集合
builtin-compiler-profile-formal-v1.json   // formal-evidence-v1 profile descriptor
```

**Schema 文件路径（`docs/schemas/resource-gateway-capability-studio/`）：**

```
bloge.capability-studio.acceptance-plan.v1.schema.json
bloge.capability-studio.contract-catalog.v1.schema.json
bloge.capability-studio.compiled-plan.v1.schema.json
bloge.capability-studio.compiled-plan-verification-result-v1.schema.json
```

**Fixture 路径（`resource-gateway-test-kit/src/test/resources/`）：**

```
acceptance/
  plans/
    rg-cs-felt-v1-valid.json
    rg-cs-felt-v1-cycle.json
    rg-cs-felt-v1-duplicate-id.json
    rg-cs-felt-v1-missing-producer.json
    rg-cs-felt-v1-barrier-bypass.json
    rg-cs-felt-v1-semantic-permute.json
    rg-cs-felt-v1-old-phase-order.json     // NEG-BARRIER-005：旧 §9 顺序，固定被拒绝
  catalogs/
    builtin-catalog.json
    builtin-catalog-canonical-bytes.txt    // catalog canonical bytes hex
  compiled/
    rg-cs-felt-v1-compiled-golden.json
    rg-cs-felt-v1-fingerprint-golden.txt
  verification/
    valid-verification-result.json
    invalid-fingerprint-mismatch.json
    unavailable-schema.json
```

**pom.xml 资源配置：** Schema 文件使用现有 `schemas/resource-gateway-capability-studio/` 资源路径（已配置为 ordinary 和 shaded main JAR 的 resource）。Catalog/Profile 实例需新增资源路径配置：

```xml
<resource>
  <directory>docs/acceptance/capability-studio/acceptance-engine-v1</directory>
  <targetPath>acceptance-engine-v1</targetPath>
  <filtering>false</filtering>
  <includes>
    <include>builtin-contract-catalog.json</include>
    <include>builtin-compiler-profile-formal-v1.json</include>
  </includes>
</resource>
```

此配置**不得**放入 a1 JAR 的 testResource；a1 JAR 继续严格只包含 `resource-gateway-capability-studio-a1/` 下的 schema。

---

## 13. Java Public API 草图

### 13.1 CapabilityStudioAcceptancePlanCompiler（public）

```java
package com.leanowtech.bloge.gateway.testkit.acceptance;

/**
 * Deep module: compiles bounded AcceptancePlanSource bytes into an immutable
 * CompiledAcceptancePlan.
 *
 * Phase 1 contract:
 * - INPUT:  bounded planBytes + catalogBytes
 *           (内部独立加载 packaged compiler profile 和 closed registry)
 * - OUTPUT: CompiledAcceptancePlan with status=COMPILED
 * - DOES NOT: execute primitives；除有界读取 packaged schema/catalog/profile resources 外不做外部 I/O，
 *             generate PASS/ACCEPTED, or modify formalPassCount (remains 0/27)
 *
 * Registry and Protocol are fixed internals; callers cannot inject trust-root replacements.
 */
public final class CapabilityStudioAcceptancePlanCompiler {

    /** Static factory: returns compiler with built-in internals. */
    public static CapabilityStudioAcceptancePlanCompiler withBuiltInInternals() {
        // 内部加载 packaged catalog/profile/registry；public compile() 不接收这些字节
        return new CapabilityStudioAcceptancePlanCompiler(
            CapabilityStudioAcceptancePlanProtocol.instance().parseBuiltInCatalog(),
            CapabilityStudioAcceptancePrimitiveRegistry.builtIn(),
            CapabilityStudioAcceptancePlanProtocol.instance()
        );
    }

    // Package-private constructor; public callers use withBuiltInInternals()
    CapabilityStudioAcceptancePlanCompiler(
        CapabilityStudioAcceptancePlanProtocol.Catalog catalog,
        CapabilityStudioAcceptancePrimitiveRegistry registry,
        CapabilityStudioAcceptancePlanProtocol protocol
    ) {}

    /**
     * @param planBytes           bounded plan source, max 1 MB
     * @param catalogBytes        bounded catalog wire bytes, max 1 MB
     * @return CompilationResult with status=COMPILED
     * @throws CompilerException with exact reasonCode on failure
     */
    public CompilationResult compile(
        byte[] planBytes,
        byte[] catalogBytes
    ) throws CompilerException {}
}
```

### 13.2 CapabilityStudioCompiledPlanVerifier（public）

```java
package com.leanowtech.bloge.gateway.testkit.acceptance;

/**
 * Independent verifier: parses and validates a CompiledAcceptancePlan
 * without calling the Compiler as a shortcut.
 *
 * Phase 1 contract:
 * - INPUT:  bounded planSourceBytes + catalogBytes + compiledPlanBytes
 * - OUTPUT: VerificationResult with status=VERIFIED|INVALID|UNAVAILABLE
 * - DOES NOT: call Compiler, use Compiler verdict as trust root；除有界读取 packaged schema/catalog/profile/resources 外不做外部 I/O，
 *             generate PASS, or modify formalPassCount (remains 0/27)
 *
 * Registry and Protocol are fixed internals.
 * Architecture: class constant pool must not contain Compiler internal names.
 */
public final class CapabilityStudioCompiledPlanVerifier {

    public static CapabilityStudioCompiledPlanVerifier withBuiltInInternals() {
        // 内部加载 packaged catalog/profile/registry；public verify() 不接收这些字节
        return new CapabilityStudioCompiledPlanVerifier(
            CapabilityStudioAcceptancePlanProtocol.instance().parseBuiltInCatalog(),
            CapabilityStudioAcceptancePrimitiveRegistry.builtIn(),
            CapabilityStudioAcceptancePlanProtocol.instance()
        );
    }

    CapabilityStudioCompiledPlanVerifier(
        CapabilityStudioAcceptancePlanProtocol.Catalog catalog,
        CapabilityStudioAcceptancePrimitiveRegistry registry,
        CapabilityStudioAcceptancePlanProtocol protocol
    ) {}

    public VerificationResult verify(
        byte[] planSourceBytes,
        byte[] catalogBytes,
        byte[] compiledPlanBytes
    ) throws VerificationException {}
}
```

### 13.3 CapabilityStudioAcceptancePrimitiveRegistry（package-private）

```java
package com.leanowtech.bloge.gateway.testkit.acceptance;

/**
 * Built-in immutable primitive descriptor registry.
 * 8 primitive types, 9 instances in Phase 1 plan.
 * Package-private: only accessed by Compiler and Verifier via their static factories.
 */
final class CapabilityStudioAcceptancePrimitiveRegistry {

    static CapabilityStudioAcceptancePrimitiveRegistry builtIn() {}

    PrimitiveDescriptor get(String typeId) {}
    Set<String> typeIds() {}
    Map<String, PrimitiveDescriptor> descriptors() {}
    String descriptorSetFingerprint() {}
}
```

### 13.4 CompilerException / CompilationResult（Compiler 嵌套类型）

```java
public sealed class CompilerException extends Exception
    permits CompilerException.WireException,
            CompilerException.SchemaException,
            CompilerException.TopologyException,
            CompilerException.BarrierException,
            CompilerException.FingerprintException {
    public final class WireException extends CompilerException {}
    public final class SchemaException extends CompilerException {}
    public final class TopologyException extends CompilerException {}
    public final class BarrierException extends CompilerException {}
    public final class FingerprintException extends CompilerException {}
    CompilerException(String message, Throwable cause) { super(message, cause); }
    public String reasonCode() { return switch(this) {
        case WireException e -> "INVALID_WIRE";
        case SchemaException e -> "INVALID_SCHEMA";
        case TopologyException e -> "INVALID_TOPOLOGY";
        case BarrierException e -> "INVALID_BARRIER_BYPASS";
        case FingerprintException e -> "INVALID_FINGERPRINT_MISMATCH";
    }; }
}

public record CompilationResult(
    CompiledAcceptancePlan plan,
    String catalogRawFingerprint,
    String catalogSemanticFingerprint,
    String compilerProfileRawFingerprint,
    String compiledPlanFingerprint
) {}
```

### 13.5 VerificationException / VerificationResult（Verifier 嵌套类型）

```java
public sealed class VerificationException extends Exception
    permits VerificationException.SchemaUnavailable,
            VerificationException.RegistryUnavailable {
    public final class SchemaUnavailable extends VerificationException {}
    public final class RegistryUnavailable extends VerificationException {}
    VerificationException(String message, Throwable cause) { super(message, cause); }
}

// VerificationResult: matches Schema oneOf discriminant branches
// Self-preimage: computed with verificationFingerprint=null, expected/recomputed may be null on early failure
public record VerificationResult(
    String planId,
    int revision,
    Status status,
    String reasonCode,
    String reasonField,
    boolean catalogRefVerified,
    boolean catalogRawFingerprintVerified,
    boolean catalogSemanticFingerprintVerified,
    boolean planFingerprintVerified,
    boolean phaseBarrierVerified,
    boolean dependencyDagVerified,
    boolean effectBarrierVerified,
    boolean canonicalMatrixCellCountVerified,
    boolean stageExitContractCountVerified,
    String expectedCompiledPlanFingerprint,
    String recomputedCompiledPlanFingerprint,
    String verificationFingerprint
) {
    // VERIFIED: status=VERIFIED; reasonCode=null, reasonField=null
    //           expected == recomputed (非 null，由语义 verifier 保证相等)
    //           verificationFingerprint 非 null
    // INVALID:  status=INVALID; reasonCode 非 null
    //           early failure 时 expected/recomputed 可为 null
    // UNAVAILABLE: status=UNAVAILABLE; reasonCode 非 null
    //              expected/recomputed 可为 null
    //           early failure 时 expected/recomputed 可为 null
    //              verificationFingerprint 非 null（自指 verificationResult body）
    public enum Status { VERIFIED, INVALID, UNAVAILABLE }
}
```

---

## 14. 提交拆分（3 个隔离提交）

### 提交 1：Wire + Fixtures（schema、fixtures、golden vectors）

```
docs/schemas/resource-gateway-capability-studio/
  capability-studio-acceptance-plan-v1.schema.json
  capability-studio-contract-catalog-v1.schema.json
  capability-studio-compiled-acceptance-plan-v1.schema.json
  capability-studio-compiled-plan-verification-result-v1.schema.json

docs/acceptance/capability-studio/acceptance-engine-v1/
  builtin-contract-catalog.json
  builtin-compiler-profile-formal-v1.json

resource-gateway-test-kit/src/test/resources/acceptance/
  (所有 fixture 文件)

resource-gateway-test-kit/pom.xml
  (添加 acceptance-engine-v1 resource 配置，但不得放入 a1 testResource)

docs/
  resource-gateway-capability-studio-acceptance-plan-compiler-implementation-design.md
```

### 提交 2：Compiler + Tests（catalog、registry、compiler、tests）

```
resource-gateway-test-kit/src/main/java/com/leanowtech/bloge/gateway/testkit/acceptance/
  CapabilityStudioAcceptancePlanCompiler.java
  CapabilityStudioCompiledPlanVerifier.java
  CapabilityStudioAcceptancePrimitiveRegistry.java   // package-private
  CapabilityStudioAcceptancePlanProtocol.java      // package-private

resource-gateway-test-kit/src/test/java/com/leanowtech/bloge/gateway/testkit/acceptance/
  CapabilityStudioAcceptancePlanCompilerTest.java
  CapabilityStudioCompiledPlanVerifierTest.java
  CapabilityStudioAcceptancePlanCompilerArchitecturalTest.java   // NEG-ARCH-001
  CapabilityStudioCatalogFixedDenominatorTest.java
  CapabilityStudioCanonicalizationDeterminismTest.java   // POS-DET-001..006
```

### 提交 3：Integration + Docs

```
resource-gateway-test-kit/pom.xml
  (验证 acceptance-engine-v1 resource 已正确配置到 ordinary 和 shaded main JAR，
   且 a1 testResource 不含此路径)

docs/
  (更新 README 相关章节)
```

验收标准：
- `mvn -f resource-gateway-test-kit/pom.xml clean verify` 全绿
- §11 测试矩阵全部通过
- NEG-BARRIER-005 验证旧 §9 顺序被拒绝（防止矛盾回归）
- javadoc doclint 通过
- `git diff --check` 无警告

---

## 15. 验收标准汇总

| # | 标准 | 验证方式 |
|---|---|---|
| AC1 | 四个 Schema 均为 Draft 2020-12、additionalProperties=false | JSON Schema validator |
| AC2 | Schema fingerprint 均为 `sha256:[0-9a-f]{64}` | Schema 内置 regex |
| AC3 | Catalog Schema 含 required arrays（27/9/14/9/3） | Schema required 字段验证 |
| AC4 | catalogRawFingerprint = raw bytes hash（Domain 2）；catalogSemanticFingerprint = 领域语义 hash（Domain 2.5） | POS-DET-004, POS-DET-007 |
| AC5 | 禁止字段 className/script/url/expression/serviceLoader 在 Schema 层面拒绝 | NEG-WIRE-001..005 |
| AC4b | catalog semantic drift（contractEntry 字段内容变化）被拒绝 | POS-DET-007 |
| AC4c | catalog raw 空白变化被接受（catalogSemanticFingerprint 不变，catalogRawFingerprint 变） | POS-DET-004 |
| AC6 | 6 个 domain-separated fingerprint 公式各自独立 | POS-DET-002, POS-DET-004 |
| AC7 | PURE_VERIFY_GATE 正确：preflight 在 lease/write 前，且 lease 有 provider-conformance 传递依赖 | POS-BARRIER-001, NEG-BARRIER-001..002 |
| AC8 | NO_DELETE_AFTER_LEASE 通过 Registry capability 验证，不靠排序 | NEG-BARRIER-004 |
| AC9 | §3 ADR phase 顺序与 §8.1 依赖一致；旧 §9 顺序被 NEG-BARRIER-005 拒绝 | NEG-BARRIER-005 |
| AC10 | 两个 27 分母字段独立验证 | POS-27ISOLATE-001, NEG-27ISOLATE-001..003 |
| AC11 | Semantics equivalence → same compiledPlanFingerprint | POS-DET-001, POS-DET-006 |
| AC12 | 幂等性：相同输入两次编译相同 | POS-DET-002 |
| AC13 | catalog raw bytes 改变 → catalogRawFingerprint 和 compiledPlanFingerprint 均变 | POS-DET-004 |
| AC14 | 篡改 plan 后 Verifier 独立检测 | NEG-TAMPER-001..004 |
| AC15 | Verifier 不调用 Compiler（constant pool 层面） | NEG-ARCH-001 |
| AC16 | main ordinary JAR 包含 4 个 Phase 1 Schema | POS-PACK-001 |
| AC17 | a1 JAR 不包含 Phase 1 Schema | NEG-PACK-002 |
| AC18 | Javadoc doclint 全通过 | `mvn verify` javadoc goal |
| AC19 | Git diff-check 无警告 | `git diff --check` |
| AC20 | formalPassCount 在 Phase 1 始终为 0/27 | 代码审查 |
| AC21 | duplicate detection 使用 StreamReadFeature.STRICT_DUPLICATE_DETECTION | 代码审查 |
