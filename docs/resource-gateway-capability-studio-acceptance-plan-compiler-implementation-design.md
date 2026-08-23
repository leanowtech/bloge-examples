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
| Plan/Catalog 输入均为 bounded bytes | public compile/verify API 接收 caller bounded planBytes/catalogBytes；Compiler/Verifier 内部独立加载 packaged profile 和 closed registry | 不解析网络/文件路径；bounded 字节由调用方提供 |
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

**Catalog 接受逻辑：** plan.catalogRef 是 Catalog raw fingerprint 的声明值；Compiler 独立计算传入 catalogBytes 的 catalogRawFingerprint，与 plan.catalogRef 精确比较（catalogRefVerified）。raw bytes 空白变化、对象键顺序变化、换行符变化会改变 catalogRawFingerprint：若 plan.catalogRef 不同步更新 → catalogRawFingerprintVerified=false → catalogRef mismatch → 拒绝；若 plan.catalogRef 同步更新为新值 → catalogRawFingerprintVerified=true → 编译通过，compiledPlanFingerprint 变化。catalogSemanticFingerprint 纯由 Catalog 内容语义决定，空白/键序变化不影响；若 contractEntry 字段内容漂移 → INVALID_CATALOG_SEMANTICS。

### 2.2 `AcceptancePlanSource`

只读字节输入，不执行 I/O：

```
byte[] planBytes          // bounded，来自调用方
String schemaVersion     // 必须 = "bloge.capability-studio.acceptance-plan.v1"
byte[] canonicalBytes     // 见 §2.5 仓库 canonicalization 规则
String planSourceSemanticFingerprint  // sha256:domain-separated(plan semantic canonical bytes)
```

禁止字段：`className`、`script`、`url`、`expression`、`serviceLoader`、`produces`、`requiredRoles`、`type`（旧字段）。Plan Source 只允许 root 字段和 primitive 实例字段：`id`、`typeId`、`revision`、`dependsOn[]`、`inputSlot`；`produces`/`requiredRoles` 等产出和角色由唯一 Registry + Catalog 派生，不由调用方声明。

### 2.3 Fingerprint 语义与 Domain-Separated 公式

**不使用 RFC 8785 JCS。不声称 EvidenceVerificationSupport 直接提供 domain separation。Protocol 自己实现长度前缀 SHA-256，可复用 EvidenceVerificationSupport 递归键排序思想。**

六个隔离 domain，每个使用统一公式结构（Domain 2 为例）：

```
Domain 2: catalog raw fingerprint — catalogBytes 的 exact bytes
catalogRawFingerprint =
  "sha256:" + SHA256(
    UTF8("RG-CS-CATALOG-RAW-v1")
    + I32BE(byteLength(catalogBytes))
    + catalogBytes
  )
```

- `UTF8(domain)`：直接 UTF-8 编码，无额外长度前缀
- `I32BE(length)`：payload 字节长度的 big-endian 32-bit unsigned 整数（4 字节）
- `payload`：紧跟其后的实际字节内容

所有 fingerprint 使用 `I32BE`（big-endian 32-bit unsigned length prefix）。

**其余五个 domain：**

- Domain 1: plan source semantic — canonical plan body（不含 planSourceSemanticFingerprint 自身）
- Domain 2.5: catalog semantic — canonical catalog body（不含 catalogSemanticFingerprint 自身）
- Domain 3: compiler profile raw — packaged profile 的 exact raw bytes（不含 compilerProfileRawFingerprint 自身）
- Domain 4: compiled plan — compiled canonical body 自指（不含 compiledPlanFingerprint 自身）
- Domain 5: primitive registry — 内建 fixed closed registry 的 exact canonical bytes

### 2.4 `CompiledAcceptancePlan`

Compiler 的唯一权威输出。字段列表与 §5.3 Compiled Plan Schema 完全一致，不得自行发明 schema 中不存在的字段。

**23 个 root required 字段（与 schema 一致）：**

`schemaVersion`、`planId`、`revision`、`planSourceSemanticFingerprint`、`catalogRawFingerprint`、`catalogSemanticFingerprint`、`compilerProfileRawFingerprint`、`primitiveRegistryFingerprint`、`stageExitContractCount`、`acStdCount`、`feltObligationCount`、`canonicalMatrixCellCount`、`suiteRunCount`、`matrixCellIds`、`suiteRunIds`、`exactContractIds`、`primitiveContracts`、`phaseBarriers`、`executionOrder`、`expectedEvidenceRoles`、`oracleBindings`、`terminalGate`、`compiledPlanFingerprint`

**Counter const（schema `const` 约束）：** `stageExitContractCount=27`、`acStdCount=9`、`feltObligationCount=14`、`canonicalMatrixCellCount=27`、`suiteRunCount=3`

**`stageExitContractCount`、`canonicalMatrixCellCount` 和 `matrixCellIds` 是三套独立字段。** Canonical 9 Case x 3 轮的计划必须同时给出 27 个稳定 `matrixCellId` 和 3 个稳定 suite run identity；它们不能从 `stageExitContractCount` 推导，也不能写入裸 `expectedCount=27` 后由消费者猜测语义。

### 2.5 Canonicalization（仓库既有规则 + duplicate detection）

Phase 1 canonicalization 规则（仓库级既定，不在本设计范围内修改）：

- JSON 对象键升序排序
- 数组内各元素独立递归 canonicalize 后拼接（不是按 JSON 数组整体排序）
- 移除 JSON 注释和空白（空格、制表、换行）

Phase 1 补充规则：

- **重复字段检测**：必须显式启用 `Jackson StreamReadFeature.STRICT_DUPLICATE_DETECTION`；Parser 在遇到重复键时直接抛出 `JsonParseException`（非 Schema 层面拒绝），因此 Schema validation 不会看到任何重复键

### 2.6 `PrimitiveDescriptor` 与 Phase 1 编译投影

**Registry 以 `typeId` 为 key**，非 primitiveId。每个 Descriptor 声明完整的 input/output/effect/phase/verifier/binding 信息。

**Phase 1 compiled primitive 只投影 6 个字段（与 compiled plan schema `compiledPrimitive` required 一致）：**

```
primitiveId   // 来自 plan source
typeId        // 查 Registry 的 key
revision      // plan source 中声明的 revision
effectClass  // 来自 Registry 投影
phase         // 来自 Registry 投影
dependsOn    // required 数组，可空
inputSlot    // optional
```

**typedVerifierId、typedVerifierRevision、retryPolicy、failureMapping、capabilityRequirements 均属 packaged profile（compilerProfileRawFingerprint 绑定）和 closed Registry，不写入 compiled plan wire。** Compiler 通过 `primitiveRegistryFingerprint`（Domain 5）绑定 Registry 内容，Verifier 独立重算验证。

Plan 不能指定 Java class，不能通过反射发现任意实现。

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

**Schema AC3 精确集合验证：** compiled schema 中的 `const` 字段（stageExitContractCount=27, acStdCount=9, feltObligationCount=14, canonicalMatrixCellCount=27, suiteRunCount=3）与 required arrays 同时验证，保证精确集合。

### 5.1 `bloge.capability-studio.acceptance-plan.v1`

文件名：`capability-studio-acceptance-plan-v1.schema.json`

**Wire 字段表（与真实 schema 完全一致）：**

| 字段 | 类型/约束 | 说明 |
|---|---|---|
| `schemaVersion` | `const` = `bloge.capability-studio.acceptance-plan.v1` | |
| `planId` | string, 1–128 字 | Plan 唯一标识 |
| `revision` | integer, ≥1 | Plan 修订版本号 |
| `compilerProfile` | `const` = `formal-evidence-v1` | Phase 1 仅此值 |
| `catalogId` | string, 1–128 字 | Catalog 标识（如 `builtin-contract-catalog-v1`） |
| `catalogRef` | string, 1–256 字 | Catalog raw fingerprint，格式 `sha256:[0-9a-f]{64}` |
| `primitives` | array[1–64], unique by id | items = planPrimitive |
| `primitives[].id` | string, 1–128 字 | primitive 唯一标识 |
| `primitives[].typeId` | string, 1–128 字 | Registry 中的 typeId |
| `primitives[].revision` | integer, ≥1 | |
| `primitives[].dependsOn` | string[], max 64, unique | 依赖的 primitive id 列表 |
| `primitives[].inputSlot` | enum（optional） | |

### 5.2 `bloge.capability-studio.contract-catalog.v1`

文件名：`capability-studio-contract-catalog-v1.schema.json`

Catalog root 字段：

| 字段 | 类型/约束 |
|---|---|
| `stageExitContracts` | array[27], unique by contractId |
| `acStandards` | array[9], unique by contractId |
| `feltObligations` | array[14], unique by contractId |
| `canonicalCases` | array[9], unique by canonicalCaseId |
| `suiteRuns` | array[3], unique by suiteRunId |
| `matrixCells` | array[27], unique by matrixCellId |

**evidenceRoles / ownerRoles / externalFactRequirements 在每个 contractEntry 内**，不是 catalog root 字段。

### 5.3 `bloge.capability-studio.compiled-plan.v1`

文件名：`capability-studio-compiled-acceptance-plan-v1.schema.json`

**Root required（23 个）：**

| 字段 | 类型/约束 |
|---|---|
| `schemaVersion` | `const` = `bloge.capability-studio.compiled-plan.v1` |
| `planId` | string |
| `revision` | integer, ≥1 |
| `planSourceSemanticFingerprint` | sha256 pattern | Domain 1 |
| `catalogRawFingerprint` | sha256 pattern | Domain 2 |
| `catalogSemanticFingerprint` | sha256 pattern | Domain 2.5 |
| `compilerProfileRawFingerprint` | sha256 pattern | Domain 3 |
| `primitiveRegistryFingerprint` | sha256 pattern | Domain 5 |
| `stageExitContractCount` | `const`=27 |
| `acStdCount` | `const`=9 |
| `feltObligationCount` | `const`=14 |
| `canonicalMatrixCellCount` | `const`=27 |
| `suiteRunCount` | `const`=3 |
| `matrixCellIds` | array[27], unique |
| `suiteRunIds` | array[3], unique |
| `exactContractIds` | array[50], unique | 27+9+14 |
| `primitiveContracts` | array[1–64] | items = compiledPrimitive |
| `phaseBarriers` | array[7] | items = phaseBarrier；barrierId 和完整 phases 数组均来自 profile barriers[] |
| `executionOrder` | array[1–64], unique |
| `expectedEvidenceRoles` | array[1–8] | items = evidenceRoleBinding |
| `oracleBindings` | array[50] | items = oracleBinding |
| `terminalGate` | `const` = `DEVELOPMENT_VERIFIED_ONLY` |
| `compiledPlanFingerprint` | sha256 pattern | Domain 4；wire 中非 null |

**compiledPrimitive（`primitiveContracts` items）：**

| 字段 | required | 类型/约束 |
|---|---|---|
| `primitiveId` | **是** | string, maxLength=128 |
| `typeId` | **是** | string, maxLength=128 |
| `revision` | **是** | integer, ≥1 |
| `effectClass` | **是** | closed enum: `PURE_VERIFY` \| `AUTHORITY_LEASE` \| `LOCAL_IMMUTABLE_WRITE` |
| `phase` | **是** | closed enum: `BOOTSTRAP_FACTS` … `EXTERNAL_ADJUDICATION`（以 schema 权威 enum 为准） |
| `dependsOn` | **是** | string[], required 数组，可空 |
| `inputSlot` | **否** | enum（optional） |

> 不存在：retryPolicy、failureMapping、typedVerifierId、typedVerifierRevision、capabilityRequirements（属 packaged profile/Registry）。

**phaseBarrier（`phaseBarriers` items）：**

`barrierId`（closed enum，以 schema 权威值为准）+ `phases` 数组（按 profile `barriers[].phases` 完整保留；unique、minItems:1、maxItems:10、ordered）。

**evidenceRoleBinding（`expectedEvidenceRoles` items）：**

`role`（closed enum）+ `contractIds[]`（required nonempty unique 升序数组，minLength:1，maxLength:50，max 50 items）。

**oracleBinding（`oracleBindings` items）：**

`contractId`（string）+ `oracleId`（string）；精确 50 项（当前 catalog 每 contract 一 oracle）。

权威文件：[capability-studio-compiled-acceptance-plan-v1.schema.json](docs/schemas/resource-gateway-capability-studio/capability-studio-compiled-acceptance-plan-v1.schema.json)

### 5.4 `bloge.capability-studio.compiled-plan-verification-result.v1`

文件名：`capability-studio-compiled-plan-verification-result-v1.schema.json`

**Flat closed union，三状态均平铺于 root，不嵌套 verifiedResult/invalidResult/unavailableResult。**

**Root required（所有状态均有）：** `schemaVersion`, `status`, `verificationFingerprint`

**9 个 boolean check 字段（所有状态均有，Schema type=boolean，VERIFIED 时 const=true）：**

| 字段 | VERIFIED | INVALID | UNAVAILABLE |
|---|---|---|---|
| `catalogRefVerified` | const=true | boolean | boolean |
| `catalogRawFingerprintVerified` | const=true | boolean | boolean |
| `catalogSemanticFingerprintVerified` | const=true | boolean | boolean |
| `planFingerprintVerified` | const=true | boolean | boolean |
| `phaseBarrierVerified` | const=true | boolean | boolean |
| `dependencyDagVerified` | const=true | boolean | boolean |
| `effectBarrierVerified` | const=true | boolean | boolean |
| `canonicalMatrixCellCountVerified` | const=true | boolean | boolean |
| `stageExitContractCountVerified` | const=true | boolean | boolean |

**VERIFIED：** `reasonCode=null`, `reasonField=null`；`expectedCompiledPlanFingerprint` / `recomputedCompiledPlanFingerprint` 非 null（由语义 verifier 保证相等）；9 个 boolean 全 const=true。

**INVALID：** `status=INVALID`；`reasonCode` ∈ `INVALID_*` 含 `INVALID_CATALOG_SEMANTICS`；`reasonField` 为 RFC6901 JSON pointer（string，非 null）；early failure 时 `expectedCompiledPlanFingerprint`/`recomputedCompiledPlanFingerprint` 可为 null。

**UNAVAILABLE：** `status=UNAVAILABLE`；`reasonCode` ∈ `UNAVAILABLE_SCHEMA_NOT_FOUND` | `UNAVAILABLE_CATALOG_NOT_FOUND` | `UNAVAILABLE_PACKAGED_SCHEMA` | `UNAVAILABLE_CATALOG_REF_MISMATCH`；**禁止出现 `reasonField`（Schema `not: {required:["reasonField"]}`）**；`planId`/`revision`/`expectedCompiledPlanFingerprint`/`recomputedCompiledPlanFingerprint` 可为 null。

**不出现 `verifiedResult` / `invalidResult` / `unavailableResult` 嵌套对象；不出现 PASS / ACCEPTED / COMPILED 状态。**

权威文件：[capability-studio-compiled-plan-verification-result-v1.schema.json](docs/schemas/resource-gateway-capability-studio/capability-studio-compiled-plan-verification-result-v1.schema.json)

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
  "primitiveDescriptors": [...],
  "barriers": [...]
}
```

Compiler 在编译时重算 `primitiveRegistryFingerprint`，与 `expectedPrimitiveRegistryFingerprint` 比较（精确匹配）。Verifier 在验证时同样重算，两端独立。

## 9. Catalog Fingerprint 与 Verifier Bindings

Compiler 验证 catalog fingerprint 两层：

1. `catalogRawFingerprint`：SHA256(Domain 2 前缀 + catalogBytes)，与 plan.catalogRef 精确比较（catalogRefVerified）
2. `catalogSemanticFingerprint`：SHA256(Domain 2.5 前缀 + canonicalCatalogBytes)，与 packaged profile 的 expectedCatalogSemanticFingerprint 精确比较

Catalog 字节由调用方作为 bounded input 提供，不从文件系统读取。

## 10. Primitive Registry 内容

见 §7 内建 Registry 表。

## 11. 测试矩阵

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
| `NEG-WIRE-012` | verification result 含 status=`COMPILED`（schema enum 不含此值） | Schema `status` enum 拒绝 |
| `NEG-WIRE-013` | verification result 含 reasonCode=`PASS`（INVALID reasonCode enum 不含此值） | Schema `reasonCode` enum 拒绝 |
| `NEG-WIRE-014` | plan 只含 stageExitContractCount=27，缺少 canonicalMatrixCellCount 字段 | Schema required 拒绝 |
| `NEG-WIRE-015` | verification result VERIFIED 时 reasonCode 非 null | Schema oneOf 拒绝 |
| `NEG-WIRE-016` | verification result VERIFIED 时 reasonField 非 null | Schema oneOf 拒绝 |
| `NEG-WIRE-017` | compiled plan 无 compiledPlanFingerprint | Schema required 拒绝 |
| `NEG-WIRE-018` | verification result UNAVAILABLE 时含 reasonField | Schema `not: {required:["reasonField"]}` 拒绝 |

### 11.2 Fingerprint

| 测试 ID | 描述 | 预期 |
|---|---|---|
| `NEG-FP-001` | catalogRawFingerprint 使用错误 domain 前缀 | 与 golden 不等 |
| `NEG-FP-002` | catalogRawFingerprint 直接 hash catalogBytes（无 domain prefix） | 与 golden 不等 |
| `NEG-FP-003` | compiledPlanFingerprint 自指时未排除自身 | 与 golden 不等 |
| `POS-FP-001` | catalogRawFingerprint = SHA256(UTF8(domain)+I32BE(len)+catalogBytes) | 与 golden exact match |
| `POS-FP-002` | Domain 2–5 各使用独立 domain 字符串 | 各 fingerprint 不冲突 |

### 11.3 Determinism

| 测试 ID | 输入 | 预期 |
|---|---|---|
| `POS-DET-001` | T1 同语义 plan，executionOrder 不同 | same compiledPlanFingerprint |
| `POS-DET-002` | T2-catalog-raw catalogBytes 变更 | 不同 catalogRawFingerprint | compiler |
| `POS-DET-003` | T3-catalog-raw catalogBytes 变更 | 不同 catalogRawFingerprint | compiler |
| `POS-DET-004` | T4-catalog-semantic catalog semantic 变更 | 不同 catalogSemanticFingerprint | compiler |
| `NEG-DET-005` | T5-dup-id duplicate primitiveId | INVALID_PLAN_STRUCTURE | compiler |
| `NEG-DET-006` | T6-cycle cyclic dependsOn | INVALID_TOPOLOGY_CYCLE | compiler |
| `NEG-DET-007` | T7-unknown-type typeId 未在 registry | INVALID_REGISTRY_TYPE_NOT_FOUND | compiler |
| `NEG-DET-008` | T8-rev-mismatch revision 不符 | INVALID_REGISTRY_REVISION_MISMATCH | compiler |
| `NEG-DET-009` | T9-size-plan planBytes >1MiB | reject | compiler |
| `NEG-DET-010` | T10-size-catalog catalogBytes >1MiB | reject | compiler |
| `POS-DET-011` | T11-order-topo plan primitives | executionOrder 拓扑排序 | compiler |
| `POS-DET-012` | T12-permutation source primitive order permuted | same executionOrder+fingerprint | compiler |
| `POS-DET-013` | T13-phases-complete profile barriers | 完整保留 barrierId/phases[] 保序 | compiler |
| `POS-DET-014` | T14-count-27 catalog.stageExitContracts | stageExitContractCount==27 | compiler |
| `POS-DET-015` | T15-count-9 catalog.acStandards | acStdCount==9 | compiler |
| `POS-DET-016` | T16-count-14 catalog.feltObligations | feltObligationCount==14 | compiler |
| `POS-DET-017` | T17-count-cases catalog.canonicalCases | canonicalCases==9 | compiler |
| `POS-DET-018` | T18-count-runs catalog.suiteRuns | suiteRuns==3 | compiler |
| `POS-DET-019` | T19-count-cells catalog.matrixCells | canonicalMatrixCellCount==27 | compiler |
| `POS-DET-020` | T20-keys-sorted IR body | object keys 升序 | compiler |
| `POS-DET-021` | T21-primitive-sorted primitiveContracts | primitiveId 升序；dependsOn 升序 | compiler |
| `POS-DET-022` | T22-barrier-order phaseBarriers | 保持 profile.barriers 声明顺序；phases 保序 | compiler |
| `POS-DET-023` | T23-ids-sorted exactContractIds | contractId 升序 | compiler |
| `POS-DET-024` | T24-role-sorted expectedEvidenceRoles | role 升序；contractIds 升序 | compiler |
| `POS-DET-025` | T25-oracle-sorted oracleBindings | contractId 升序 | compiler |
| `POS-DET-026` | T26-domain2 IR body | Domain2(compiledPlanFingerprint) | compiler |
| `POS-DET-027` | T27-reason-max512 Invalid reasonField | reasonField length ≤512 | verifier |
| `POS-DET-028` | T28-role-contractids catalog contracts | expectedEvidenceRoles.role -> contractIds[] | compiler |
| `POS-DET-029` | T29-oracle-contractid catalog contracts | oracleBindings.contractId -> oracleId | compiler |
| `POS-DET-030` | T30-no-guess-producer catalog 无 evidenceRoles | 禁止派生 producerPrimitiveId | compiler |
| `POS-DET-031` | T31-raw-fingerprint catalogBytes | catalogRawFingerprint == Domain2 | compiler |
| `NEG-DET-032` | T32-whitespace-ref catalog whitespace 变，plan.catalogRef 未更新 | INVALID_FINGERPRINT_MISMATCH，拒绝 | compiler |
| `POS-DET-033` | T33-ref-sync plan.catalogRef 同步更新 | semantic 不变；raw/plan/compiled fingerprint 变化 | compiler |
| `POS-DET-034` | T34-formal-0-27 IR output | 无 formalPassCount 字段 | compiler |
| `POS-DET-035` | T35-verified Valid IR | status=VERIFIED | verifier |
| `NEG-DET-036` | T36-invalid-pointer Invalid IR | status=INVALID, reasonField 非空 | verifier |
| `POS-DET-037` | T37-schema-valid IR output | JSON Schema validation pass | compiler |

### 11.4 Topology

| 测试 ID | 场景 | 预期 |
|---|---|---|
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
| `POS-DET-003` | golden fingerprint fixture 匹配 | Compiler 输出 CompilationResult（status=COMPILED）；compiledPlanFingerprint 与 golden fixture exact match |
| `POS-DET-004` | catalog raw bytes 仅空白变化（semantic 不变）；plan.catalogRef 仍引用原 fingerprint | catalogRawFingerprint 改变；catalogSemanticFingerprint 不变；**catalogRef mismatch → catalogRawFingerprintVerified=false → 拒绝**；若 plan.catalogRef 同步更新，则编译通过且 compiledPlanFingerprint 变化 |
| `POS-DET-005` | 相同 catalog bytes 两次传入 | catalogRawFingerprint 稳定且 compiledPlanFingerprint 稳定 |
| `POS-DET-006` | primitive executionOrder 排列不同，语义等价 | compiledPlanFingerprint 相等（semantic sort 稳定性） |
| `POS-DET-007` | catalog contractEntry 字段内容变化（semantic drift） | catalogSemanticFingerprintVerified=false → INVALID_CATALOG_SEMANTICS |

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
| POS-PACK-001 | ordinary main JAR (`bloge-resource-gateway-test-kit-1.0.0.jar`) 包含 4 个 Phase 1 Schema + 3 个 authority files（plan/catalog/profile） | Schema 可加载；authority files 可读 |
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
capability-studio-acceptance-plan-v1.schema.json
capability-studio-contract-catalog-v1.schema.json
capability-studio-compiled-acceptance-plan-v1.schema.json
capability-studio-compiled-plan-verification-result-v1.schema.json
```

**Authority 清单（Phase 1）：**

```
docs/acceptance/capability-studio/acceptance-engine-v1/
  rg-cs-felt-v1.acceptance.plan.json       // authority acceptance plan wire
  builtin-contract-catalog.json              // authority catalog wire
  builtin-compiler-profile-formal-v1.json   // authority compiler profile
```

**pom.xml resource 配置：**

```xml
<resource>
  <directory>docs/acceptance/capability-studio/acceptance-engine-v1</directory>
  <targetPath>acceptance-engine-v1</targetPath>
  <filtering>false</filtering>
  <includes>
    <include>rg-cs-felt-v1.acceptance.plan.json</include>
    <include>builtin-contract-catalog.json</include>
    <include>builtin-compiler-profile-formal-v1.json</include>
  </includes>
</resource>
```

此配置**不得**放入 a1 JAR 的 testResource；a1 JAR 继续严格只包含 `resource-gateway-capability-studio-a1/` 下的 schema。

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

## 13. Java Public API 草图

### 13.1 CapabilityStudioAcceptancePlanCompiler（public）

```java
package com.leanowtech.bloge.gateway.testkit.acceptance;

/**
 * Deep module: compiles bounded AcceptancePlanSource bytes into an immutable
 * CompiledAcceptancePlan.
 *
 * Phase 1 contract:
 * - INPUT:  bounded planBytes + catalogBytes（均由 public API caller 提供）
 *           （内部独立加载 packaged compiler profile 和 closed registry）
 * - OUTPUT: CompiledAcceptancePlan with status=COMPILED
 * - DOES NOT: execute primitives；除有界读取 packaged schema/catalog/profile resources 外不做外部 I/O，
 *             generate PASS/ACCEPTED, or modify formalPassCount (remains 0/27)
 *
 * Registry and Protocol are fixed internals; callers cannot inject trust-root replacements.
 */
public final class CapabilityStudioAcceptancePlanCompiler {

    /** Static factory: returns compiler with built-in internals. */
    public static CapabilityStudioAcceptancePlanCompiler withBuiltInInternals() {
        return new CapabilityStudioAcceptancePlanCompiler(
            CapabilityStudioAcceptancePrimitiveRegistry.builtIn(),
            CapabilityStudioAcceptancePlanProtocol.instance()
        );
    }

    // Package-private constructor; public callers use withBuiltInInternals()
    CapabilityStudioAcceptancePlanCompiler(
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
 * - INPUT:  bounded planSourceBytes + catalogBytes + compiledPlanBytes（均由 public API caller 提供）
 *           （内部独立加载 packaged compiler profile 和 closed registry）
 * - OUTPUT: VerificationResult with status=VERIFIED|INVALID|UNAVAILABLE
 * - DOES NOT: call Compiler, use Compiler verdict as trust root；除有界读取 packaged schema/catalog/profile/resources 外不做外部 I/O，
 *             generate PASS, or modify formalPassCount (remains 0/27)
 *
 * Registry and Protocol are fixed internals.
 * Architecture: class constant pool must not contain Compiler internal names.
 */
public final class CapabilityStudioCompiledPlanVerifier {

    public static CapabilityStudioCompiledPlanVerifier withBuiltInInternals() {
        return new CapabilityStudioCompiledPlanVerifier(
            CapabilityStudioAcceptancePrimitiveRegistry.builtIn(),
            CapabilityStudioAcceptancePlanProtocol.instance()
        );
    }

    CapabilityStudioCompiledPlanVerifier(
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
 * 8 primitive type descriptors (type definitions), 9 primitive instances (concrete uses) in Phase 1 plan.
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

## 14. 提交拆分

以下提交边界基于 Wire commit hash `5eef6e0d2` 和 authority packaging commit `fe3bb70f1`。后续 Compiler、Verifier、integration/docs 的具体提交 hash 尚未确定，以实际提交为准。

### 提交 1：Wire（schema；commit `5eef6e0d2`）

```
docs/schemas/resource-gateway-capability-studio/
  capability-studio-acceptance-plan-v1.schema.json
  capability-studio-contract-catalog-v1.schema.json
  capability-studio-compiled-acceptance-plan-v1.schema.json
  capability-studio-compiled-plan-verification-result-v1.schema.json
```

### 提交 2：Authority Packaging（fixtures、pom.xml；commit `fe3bb70f1`）

```
docs/acceptance/capability-studio/acceptance-engine-v1/
  rg-cs-felt-v1.acceptance.plan.json    // authority plan wire
  builtin-contract-catalog.json
  builtin-compiler-profile-formal-v1.json

resource-gateway-test-kit/pom.xml
  (acceptance-engine-v1 resource 配置；不得放入 a1 testResource)

docs/
  resource-gateway-capability-studio-acceptance-plan-compiler-implementation-design.md
```

> 此提交后可能出现纠偏性质的额外提交，不影响 authority wire 的核心不变性。

### 后续提交（分离，无限定 hash）

**提交 3（Compiler + Registry + Protocol）：**
```
resource-gateway-test-kit/src/main/java/com/leanowtech/bloge/gateway/testkit/acceptance/
  CapabilityStudioAcceptancePlanCompiler.java
  CapabilityStudioCompiledPlanVerifier.java
  CapabilityStudioAcceptancePrimitiveRegistry.java   // package-private
  CapabilityStudioAcceptancePlanProtocol.java      // package-private
```

**提交 4（Tests）：**
```
resource-gateway-test-kit/src/test/java/com/leanowtech/bloge/gateway/testkit/acceptance/
  CapabilityStudioAcceptancePlanCompilerTest.java
  CapabilityStudioCompiledPlanVerifierTest.java
  CapabilityStudioAcceptancePlanCompilerArchitecturalTest.java   // NEG-ARCH-001
  CapabilityStudioCatalogFixedDenominatorTest.java
  CapabilityStudioCanonicalizationDeterminismTest.java   // POS-DET-001..007
```

**提交 5（Integration + Docs）：**
```
resource-gateway-test-kit/pom.xml
  (验证 acceptance-engine-v1 resource 已正确配置到 ordinary 和 shaded main JAR，
   且 a1 testResource 不含此路径)
docs/
  (更新 README 相关章节)
```

## 15. 验收标准汇总

| # | 标准 | 验证方式 |
|---|---|---|
| AC1 | 四个 Schema 均为 Draft 2020-12、additionalProperties=false | JSON Schema validator |
| AC2 | Schema fingerprint 均为 `sha256:[0-9a-f]{64}` | Schema 内置 regex |
| AC3 | Catalog schema 含 required arrays：stageExitContracts=27, acStandards=9, feltObligations=14, canonicalCases=9, suiteRuns=3, matrixCells=27（由 minItems/maxItems + required 验证；非 const） | Schema minItems/maxItems + required 字段验证 |
| AC4 | catalogRawFingerprint = raw bytes hash（Domain 2）；catalogSemanticFingerprint = 领域语义 hash（Domain 2.5）；catalogRefVerified 由 plan.catalogRef 与 catalogRawFingerprint 精确比较决定 | POS-DET-004, POS-DET-007 |
| AC5 | 禁止字段 className/script/url/expression/serviceLoader 在 Schema 层面拒绝 | NEG-WIRE-001..005 |
| AC4b | catalog contractEntry 字段内容漂移 → catalogSemanticFingerprintVerified=false → INVALID_CATALOG_SEMANTICS | POS-DET-007 |
| AC4c | catalog raw 空白变化 + plan.catalogRef 同步更新 → 编译通过，catalogRawFingerprint 变，compiledPlanFingerprint 变；catalogSemanticFingerprint 不变 | POS-DET-004 |
| AC6 | 6 个 domain-separated fingerprint 公式各自独立 | POS-DET-002, POS-DET-004 |
| AC7 | PURE_VERIFY_GATE 正确：preflight 在 lease/write 前，且 lease 有 provider-conformance 传递依赖 | POS-BARRIER-001, NEG-BARRIER-001..002 |
| AC8 | NO_DELETE_AFTER_LEASE 通过 Registry capability 验证，不靠排序 | NEG-BARRIER-004 |
| AC9 | §3 ADR phase 顺序与 §8.1 依赖一致；旧 §9 顺序被 NEG-BARRIER-005 拒绝 | NEG-BARRIER-005 |
| AC10 | 两个 27 分母字段独立验证 | POS-27ISOLATE-001, NEG-27ISOLATE-001..003 |
| AC11 | Semantics equivalence → same compiledPlanFingerprint | POS-DET-001, POS-DET-006 |
| AC12 | 幂等性：相同输入两次编译相同 | POS-DET-002 |
| AC13 | catalog raw bytes 改变且 plan.catalogRef 同步更新 → catalogRawFingerprint 变，compiledPlanFingerprint 变；catalogRawFingerprintVerified 由 catalogRef 决定 | POS-DET-004 |
| AC14 | 篡改 plan 后 Verifier 独立检测 | NEG-TAMPER-001..004 |
| AC15 | Verifier 不调用 Compiler（constant pool 层面） | NEG-ARCH-001 |
| POS-PACK-001 | ordinary main JAR (`bloge-resource-gateway-test-kit-1.0.0.jar`) 包含 4 个 Phase 1 Schema + 3 个 authority files（plan/catalog/profile） | Schema 可加载；authority files 可读 |
| AC17 | a1 JAR 不包含 Phase 1 Schema | NEG-PACK-002 |
| AC18 | Javadoc doclint 全通过 | `mvn verify` javadoc goal |
| AC19 | Git diff-check 无警告 | `git diff --check` |
| AC20 | formalPassCount 在 Phase 1 始终为 0/27 | 代码审查 |
| AC21 | duplicate detection 使用 StreamReadFeature.STRICT_DUPLICATE_DETECTION | 代码审查 |
