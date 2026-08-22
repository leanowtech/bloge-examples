# 04-migration-oracle-and-acceptance.md

## Capability Studio Gate A1 — Migration Oracle and Acceptance

> **当前状态：A1_DESIGN_PASS（2026-08-23）。Step0 Baseline + Source Authority 现已允许执行；禁止在 Step0 完成前提交 Step1+ 代码。Python 3.14.6 已确认安装，trust pyc 可通过 `python3` 直接执行。**

---

## 1. 当前事实（As-Is Snapshot）

### 1.1 [current] compile-protocol-authority.py (损坏)

- **文件大小：998 字节**，仅含入口碎片，无 git 历史。
- 无可信构建轨迹，不可作为 oracle 源。
- 不恢复该文件的功能等价体；Step0 Baseline + Source Authority 将精确字节复制到内容寻址的 NON_RELEASE 隔离工件（digest 命名），仅在新 facade 就绪后才替换入口可执行文件。
- 当前精确路径：`docs/acceptance/capability-studio/gate-a-wire-v1/protocol-compiler/compile-protocol-authority.py`
- 保留法医原始文件直至验证完成。

### 1.2 [current] pyc 编译产物

- **精确路径**：`docs/acceptance/capability-studio/gate-a-wire-v1/trust-build/__pycache__/validate-fixtures.cpython-314.pyc`
- **文件大小**：247,211 字节
- **SHA-256**：`d6dab90a53ea9b353c1e6ebabca7660eb34324d57bf8526d9e0f7539901de280`
- 该 pyc 在 Python 3.14 以下版本不可运行；当前本机 Python 版本为 3.14.6，trust pyc 可通过 `python3` 直接执行验证。
- **禁止写入**：受控归档路径（包括 `quarantine/`、`compiled/`、`__pycache__/` 下所有 NON_RELEASE 文件）**禁止写入任何未发布内容**；写入操作违反 NON_RELEASE 封存约束。

- **pyc 保留策略**：该 pyc 文件在以下条件**全部满足之前**必须保留，不得删除：
  1. [planned] 所有替代 logical modules 的 Python 实现均通过 CI 测试；
  2. [planned] compatibility corpus（全量 projections/fixtures）逐字节全等校验通过；
  3. attack-corpus 所有 expected rejection 条目校验通过。
  满足以上条件后，pyc 从生产依赖和 release artifact 中移除，pyc 本身转为受控归档基线（NON_RELEASE 受控归档），**不立即物理删除**。

### 1.3 [current] 旧编译产物

- 存在历史 `projections/` 和 `fixtures/` 目录，内容为旧 compiler 输出的序列化结果。
- **不恢复旧 compiler 实现**；projections 作为签封 fixture source，fixtures 作为 expected output baseline。
- 旧 FP 域标签、旧 compiled outputs **仅服务于 Oracle 兼容性验证**，不构成生产依赖路径或 release artifact 的一部分。
- pyc 最终产物 **不得进入任何 release artifact**。

### 1.4 [current] Compiled Golden 实际目录

- 已签封的 compiled golden 输出存放于：`docs/acceptance/capability-studio/gate-a-wire-v1/protocol-compiler/compiled/`
- 该目录下文件由 CompilerGoldenOracle 签封，逐字节全等校验，不得重新生成。

---

## 2. Compatibility Oracle Bundle

### 2.1 两类 Oracle

| Oracle 类型 | 职责 | 签封状态 |
|---|---|---|
| **CompilerGoldenOracle** | sealed compiled projections + contentDigest 的静态 golden reference；逐条目比对精确字节；**不驱动 production verdict（accept/reject 判决）** | 永久签封，不可重算，不可执行 |
| **TrustBehaviorOracle** | 精确 Python 3.14 runtime 可复算 expectedOutcome/error/contentDigest；其他环境仅验证已签封字节 | 条件签封：Python 3.14 环境可复算；其他环境降级为只读验证已签封字节 |

### 2.2 约束

- **NON_RELEASE**：两类 oracle 均不进入任何 release 分发物；enforcement via signed manifest/hash verification + build/package denylist + CODEOWNERS/review policy + CI no-reference/no-release scans。
- **不承接生产**：oracle 仅用于迁移期 CI/CD 验证，**不参与任何生产路径决策**；oracle 执行路径为完全离线。
- **CompilerGoldenOracle 判决规则**：逐条目比对 `contentDigest`（精确原始 fixture/output 字节的 WireDigest<ContentDigest>）。完全匹配视为 PASS；任一条目失配视为 FAIL。
- **TrustBehaviorOracle 判决规则**：精确 Python 3.14 runtime 可自行重算 expectedOutcome/expectedErrorCode/contentDigest；其他 runtime 仅验证 detachedSignature 签封的字节，不得重算。
- **Oracle 不驱动生产判决**：Ledger 或 CI 中的 production verdict 由 CI gate + signed review record 决定，不由 oracle 结果决定。
- **迁移映射**：[planned] 显式迁移映射工件：
  ```
  oldDomain/oldDigest -> newDomain/newWireDigest
  ```
  不得使用 in-place 重新解释（no in-place reinterpretation）。
- **dependencyAuthority**：当前为 `DRAFT_UNPINNED`；release gate 预期 **fail-closed**（任意 DRAFT_UNPINNED 依赖触发 CI gate 拒绝，不得自动放行）。
- 所有回退策略：production path 为 fail-closed（oracle 验证失败即拒绝上线）；oracle 路径仅离线执行。

### 2.3 Runtime Legacy Ingestion（强制约束）

**Target A1 生产服务在运行时绝不接受 LEGACY_GATE_A_WIRE_V1。**

- **Legacy 输入范围**：LEGACY_GATE_A_WIRE_V1 仅作为以下两类离线工具的输入：
  1. 离线 migrator：验证 legacy schema，产生显式迁移映射记录 + target artifact（独立验证后才进入 target pipeline）。
  2. 离线 oracle：产生签封的 expectedOutputs fixture，供 CI 对比使用。
- **禁止 fallback**：无任何运行时 fallback 路径将 legacy request 直接转发至 target pipeline。
- **禁止直接 Ledger 写入**：legacy request 不得绕过 migrator 直接创建 A1 Ledger 入口。
- **`NON_RELEASE_ROLLBACKABLE_ADAPTER`**：该 adapter 是 target 开发环境的测试 adapter，**不是 legacy 兼容性 adapter**。其用途为在实现阶段可回滚到最近通过的 step，不得作为生产 legacy 兼容路径。
- 离线 migrator 产出格式见 §14.1 Migration Output 格式；target artifact 独立通过 target schema 验证后才进入 target pipeline。

---

## 3. Machine-Readable Oracle Manifest

### 3.1 [planned] Schema 顶层字段

[planned] Schema：

```json5
{
  "schemaVersion": "1.0",
  "oracleId": "string",
  "oracleType": "CompilerGoldenOracle | TrustBehaviorOracle",
  "releaseStatus": "NON_RELEASE",
  "fixtureCorpusRoot": "string (repo-relative path, no leading /, no ../)",
  "ciRoot": "string (repo-relative path, no leading /, no ../)",
  "instance": {
    "corpusRootDigest": "WireDigest<ContentDigest> (corpus tree root, JCS canonical)",
    "parentCommitSha": "string (parent commit SHA)",
    "generatedAt": "string (ISO-8601)",
    "snapshotTimestamp": "string (ISO-8601)"
  },
  "launcher": {
    "command": "string",
    "args": ["string"],
    "env": {"string": "string"},
    "corpusRoot": "string (repo-relative path, no leading /, no ../)"
  },
  "runtimeIdentity": {
    "interpreterVersion": "string",
    "sha256": "string",
    "platform": "string"
  },
  "expectedOutputs": [
    {
      "fixturePath": "string (repo-relative, no leading /, no ../)",
      "contentDigest": "WireDigest<ContentDigest> (exact raw fixture/output bytes)",
      "expectedOutcome": "PASS | REJECT",  // closed enum
      "expectedErrorCode": "string | null (error code when expectedOutcome is REJECT; null otherwise)"
    }
  ],
  "detachedSignature": {
    "algorithm": "Ed25519",  // const: Ed25519
    "keyId": "string",
    "keyId": "string",
    "signatureBase64": "string (Ed25519 signature over JCS(manifest payload excluding detachedSignature))"
  }
}
```

> **注意**：`commitSha` 在同一 commit 内不可知。[planned] Step0 manifest 记录 `corpusRootDigest` + `parentCommitSha` + `generatedAt`；Step0 commit SHA 由 CI/签名审查流程在提交后外部记录于 signed review/CI attestation 中。

### 3.2 Oracle Manifest 覆盖范围

[planned] manifest 记录 oracle 定义、launcher、expected outputs（含 contentDigest/expectedOutcome/expectedErrorCode）、runtime identity、CI root、detachedSignature。

### 3.3 [planned] CI 集成

| [planned] CI 环境变量 | 说明 |
|---|---|
| `ORACLE_MANIFEST_ROOT` | oracle manifest 根目录 |
| `ORACLE_CI_ROOT` | CI 相关 artifact 根目录 |
| `LEDGER_URL` | Ledger service endpoint（尚待实现） |
| `CI_GATE_TIMEOUT_MS` | CI gate 超时（毫秒） |

### 3.4 Attack Corpus 在 Ledger 存在前的处理

**背景**：Evidence Ledger 尚未实现时，Step1/Compiler 输出的 attack corpus 无法写入 Ledger。本节定义在 Ledger 就绪前的临时报告机制，确保 corpus 验证的完整性和可追溯性。

#### 3.4.1 CI AttackRunReport Schema

Step1/Compiler 执行 attack corpus 验证后，输出以下格式的 signed CI AttackRunReport（**不写入 Evidence Ledger**）：

**形式化定义**：

- **`AttackRunReportPayload`**：以下 JSON 结构（不含 `reportId` 和 `detachedSignature` 字段），JCS 序列化后得到规范字节流。
- **`reportId`**：`WireDigest<ContentDigest>(JCS(AttackRunReportPayload))`。
- **`detachedSignature`**：`algorithm` 固定为 `Ed25519`（常量）；`signatureBase64` 覆盖 `JCS({ "reportId": <reportId>, "payload": <AttackRunReportPayload> })`，即 envelope 中除 `detachedSignature` 本身之外的完整 JCS 序列化。

```json5
{
  "schemaVersion": "1.0",
  "reportId": "WireDigest<ContentDigest>(JCS(AttackRunReportPayload))",
  "corpusRootDigest": "WireDigest<ContentDigest> (corpus tree root, JCS canonical)",
  "exactCaseIds": ["string (case identifier, sorted-asc, unique)"],
  "runnerContentDigest": "WireDigest<ContentDigest> (exact runner binary/tool bytes)",
  "perCase": [
    {
      "caseId": "string (must exist in attack-case-v1.schema.json signed corpus; must equal exactCaseIds[i])",
      "expectedOutcome": "PASS | REJECT",  // closed enum; copied from attack-case-v1.schema.json case entry"
      "expectedErrorCode": "string | null",
      "actualOutcome": "PASS | REJECT | ERROR | TIMEOUT",  // closed enum; runner observation"
      "actualErrorCode": "string | null"
    }
  ],
  "startedAt": "string (ISO-8601, observability-only)",
  "finishedAt": "string (ISO-8601, observability-only)",
  "overallStatus": "PASS | FAIL",  // closed enum
  "detachedSignature": {
    "algorithm": "Ed25519",  // const: Ed25519
    "keyId": "string",
    "signatureBase64": "string (signature over JCS({ "reportId": <reportId>, "payload": <AttackRunReportPayload> }))"
  }
}
```

**关键约束**：

- `startedAt`/`finishedAt` 为观测专用字段，**不参与 corpus 确定性身份计算**。
- `corpusRootDigest` + `exactCaseIds` 共同定义确定性 corpus 身份。
- `overallStatus` 反映 perCase 全部 PASS（无 unexpected accept/wrong rejection code/crash/missing case）则为 PASS；任一异常均导致 FAIL。

**Report Invariant**：

1. `exactCaseIds` 必须是升序排列的去重列表，恰好等于被测 corpus 的完整 case 集合（case-set drift 检测见 §3.4.2）。
2. `perCase` 中 `caseId` 的集合和顺序必须与 `exactCaseIds` 完全一致，不允许重复。
3. `perCase` 中每条记录的 `expectedOutcome`/`expectedErrorCode` 必须与 `attack-case-v1.schema.json` 签封 corpus 中对应 `caseId` 的值逐字节相等；任一不等触发 FAIL 并立即阻断。

#### 3.4.2 即时阻断规则

攻击 corpus 验证中的以下任一情况**立即阻断当前 Step**：

| 阻断触发条件 | 含义 |
|---|---|
| unexpected accept | 应拒绝但实际通过 |
| wrong rejection code | 拒绝代码与 expectedErrorCode 不匹配 |
| crash | runner 或被测实现崩溃 |
| missing case | expected case 未出现在 actual 结果中 |
| case-set drift | 实际 case 集合与 expected 集合不一致（以 `exactCaseIds` 为准） |

**Ledger 写入**：在 Receipt/Ledger 步骤前，禁止向 Evidence Ledger 写入任何 attack corpus 验证结果。Report 仅作为 Step 退出条件和 CI gate 凭证。

**后续 Ledger 导入（可选）**：Ledger 就绪后，可将 CI AttackRunReport 作为历史证据导入，仅追加 report 引用（`reportId` + `corpusRootDigest`），原始 Report 仍保留于 CI artifact 存储。

#### 3.4.3 Report 存储位置

- **Primary**：CI artifact 存储（由 CI 平台管理，如 GitHub Actions artifacts、S3 bucket 等）。
- **Repo reference**：[planned] 在 Step1/Compiler artifact 目录下记录 report reference 文件（不含 Report 本身）。
- **Ledger reference**（Ledger 就绪后）：[planned] 在 Evidence Ledger 中追加 `attackRunReportRef` 字段，指向原始 Report。

---

## 4. 执行阶段（Execution Phases）[planned]

完整流水线包含 1 个前置 Gate 和 7 个 implementation steps：

**强制顺序**：Gate（A1_DESIGN_PASS） → Step0（Baseline+Source） → Step1（Compiler） → Step2（Evidence） → Step3（Receipt/Ledger） → Step4（Runtime） → Step5（Fresh Review） → Step6（Oracle Excision）

| 阶段 | 名称 | 描述 | 状态 |
|---|---|---|---|
| Gate | **Design Freeze / A1_DESIGN_PASS** | 2026-08-23 已通过；不是 implementation step | [current] **已通过** |
| Step0 | **Baseline + Source Authority** | quarantine legacy fragments/pyc + 创建 13 个 target schemas + migration mapping | [planned] |
| Step1 | **Compiler** | 新 compiler 替代 | [planned] |
| Step2 | **Evidence** | Sealed evidence package 生成 | [planned] |
| Step3 | **Receipt/Ledger** | Ledger 或 artifact registry 记录 | [planned] |
| Step4 | **Runtime** | Runtime 验证 | [planned] |
| Step5 | **Fresh Review / A1_IMPLEMENTATION_PASS** | 重新评审 + A1_IMPLEMENTATION_PASS | [planned] |
| Step6 | **Oracle Excision** | Oracle 隔离（NON_RELEASE 封存；Step5 pass 后方可执行） | [planned] |

> **设计顺序约束**：A1_DESIGN_PASS（2026-08-23）已通过，前置条件已满足，Step0 Baseline + Source Authority 现已允许执行，历史约束已解除。

### 4.1 各步骤评审要求

| Step | P0/P1 | Reviewer | 其他 |
|---|---|---|---|
| Step0 | 任何 P0 | [planned] 两名独立 reviewer | Gate 已通过 |
| Step1 | 任何 P0 | [planned] 两名独立 reviewer | Step0 ACCEPTED 记录 |
| Step2 | 任何 P0 | [planned] 两名独立 reviewer | Step1 ACCEPTED 记录 |
| Step3 | 任何 P0 | [planned] 两名独立 reviewer | Step2 ACCEPTED 记录 |
| Step4 | 任何 P0 | [planned] 两名独立 reviewer | Step3 ACCEPTED 记录 |
| Step5 | 任何 P0 | [planned] 两名独立 reviewer | Step4 ACCEPTED 记录 |
| Step6 | 任何 P0 | [planned] 两名独立 reviewer | Step5 ACCEPTED 记录 |

---

## 5. [planned] Sealed Evidence Package 结构

| 文件 | 描述 |
|---|---|
| `oracle-manifest.schema.json` | Oracle manifest schema |
| `compiler-golden-outputs/` | Compiler golden outputs |
| `trust-behavior-evidence/` | Trust behavior oracle outputs |
| `migration-mapping.json` | [planned] 迁移映射：oldDomain/oldDigest -> newDomain/newWireDigest |

---

## 6. Baseline Preservation [planned]

### 6.1 Step0 Baseline + Source Authority 步骤

1. [completed] 确认 A1_DESIGN_PASS 已通过。
2. [planned] 精确字节复制 `compile-protocol-authority.py`（998 字节）到 quarantine artifact。
3. [planned] 生成 Step0 manifest（含 corpusRootDigest + parentCommitSha + generatedAt，不含 commitSha）。
4. [planned] 创建 `docs/schemas/resource-gateway-capability-studio-a1/` 路径并写入 13 个 target schema 文件（按 01 §10.2 列表）。
5. [planned] 对 13 个 target schema 文件执行 JSON Schema Draft 2020-12 验证，确保所有字段类型、enum closedness、WireDigest pattern 符合 00 规范。
6. [planned] 编写 `docs/schemas/migration-mapping-v1.json`，记录 LEGACY_GATE_A_WIRE_V1 → PLANNED TARGET A1 的每个 schema 的 oldDigest → newWireDigest 映射（格式见 04 §14.1）。
7. [planned] 执行 Step0 unified verification：对 quarantine artifact 完整性、schema validation、NON_RELEASE 封存路径执行 CI-level 检查。
8. [planned] 记录 Step0 commit SHA 于外部 signed review/CI attestation。
## 7. Compatibility Corpus [planned]

### 7.1 字节全等要求

- `projections/` 和 `fixtures/` 下所有文件作为 compatibility corpus。
- 新 compiler 输出与 corpus 中对应文件 **逐字节全等** 校验（binary identical）。
- [planned] 任何导致 diff 的修改必须经 A1_IMPLEMENTATION_PASS 重新评审。

### 7.2 Attack Corpus

- 独立 `attack-corpus/` 目录存放已知边界用例。
- Attack corpus 可比 compatibility corpus 更严格，且 **expected rejection**（预期拒绝）结果签封。
- **Attack corpus 任一 failure 必须阻止当前 step**，产生 signed CI AttackRunReport（不写入 Evidence Ledger）。Ledger 就绪后，可追加 report 引用（`reportId` + `corpusRootDigest`），原始 Report 保留于 CI artifact 存储。

---

## 8. Commit 策略

- 每 Step 独立 commit，commit message 包含 Step 编号和通过条件引用。
- 全量验证：`mvn -f resource-gateway-test-kit/pom.xml clean verify`（Java 部分） + [planned] Python unified CI（尚待实现）。
- **统一 CI**：GitHub Actions / 等价 CI 平台执行 Maven 联合 pipeline。Python unified CI **planned，尚待实现**，明确标注为非当前可用状态。
- 禁止 commit 在已知损坏状态下推进；Step0 通过前不得提交任何 Step1+ 代码。

### 8.1 Commit 顺序约束 [planned]

- [planned] 所有后续 Step 需在前序 Step ledger 状态为 `ACCEPTED` 后方可推进（Step1+ 受前序 ACCEPTED 约束）。

---

## 9. 入口 / 出口 / 回退准则 [planned]

### 9.1 入口条件

| Step | 入口前提 |
|---|---|
| Gate | A1_DESIGN_PASS（2026-08-23）已通过；仓库可写 |
| Step0 | Gate 已通过 |
| Step1 | [planned] Step0 evidence 已在 artifact registry 记录 |
| Step2 | [planned] Step1 evidence 已在 artifact registry 记录 |
| Step3 | [planned] Step2 evidence 已在 artifact registry 记录 |
| Step4 | [planned] Step3 evidence 已在 artifact registry 记录 |
| Step5 | [planned] Step4 evidence 已在 artifact registry 记录 |
| Step6 | [planned] Step5 evidence 已在 artifact registry 记录 |

### 9.2 出口条件

- Step N 出口（Steps 0-5）：CI gate 通过 + 两名独立 reviewer 签字 + signed review record 写入 artifact registry。
- Step N 出口（Step6 Oracle Excision）：ledger 写入 ACCEPTED + 两名独立 reviewer 签字 + CI 验证通过。
- 任意 Step 出口失败：Ledger 就绪后写入 REJECTED（此前仅 artifact record），block 后续 Steps。

### 9.3 回退策略

- 生产路径：[planned] fail-closed；oracle 失败即拒绝上线。
- 开发路径：[planned] revert 到最近 ACCEPTED Step commit，Ledger 就绪后记录 rollback reason（此前仅 CI artifact 记录）。
- **不得在 oracle 验证失败状态下推进任何 Step。**

---

## 10. 测试命令占位符 [planned]

以下命令为 planned command（尚未实现）；对应 Step 实现落地时必须验证可执行性（`bash -n` / `python -m py_compile`），禁止冒充已可运行。

```bash
# [planned] Step0 baseline verification — requires implementation
./scripts/oracle/verify-baseline.sh

# Step1+ Maven verify
mvn -f resource-gateway-test-kit/pom.xml clean verify

# [planned] Step2+ Python unified CI — Python planned unified gate 明确尚待实现
python -m pytest tests/integration/ --oracle-manifest=oracle/manifest.schema.json

# [planned] Step6 Oracle Excision verification (pyc excision) — requires implementation
./scripts/oracle/verify-oracle-excision.sh

# [planned] Ledger 状态查询 — Ledger 实现后才可用；此前使用 CI gate
curl -s "${LEDGER_URL}/api/v1/status?oracleId=${ORACLE_ID}" | jq .state
```

> 所有 planned command 在对应 Step 实现前必须通过语法检查（bash -n / python -m py_compile）；禁止在实际不可执行时声称可运行。

---

## 11. Findings 严重性分级

P0 / P1 是 finding severity 分级（不指代 Reviewer 角色）[planned]。

| Finding 级别 | 定义 | 合入条件 |
|---|---|---|
| **P0** | 阻断性：安全漏洞、字节不等、schema 违背 | [planned] 两名独立 reviewer 均签字，P0 findings = 0 |
| **P1** | 高优先级：性能退化、边界 case 缺失、API 不兼容 | 两名独立 reviewer 均签字，P0 findings = 0，P1 findings = 0 |

**Reviewer 要求**：每项 finding 评审必须由两名独立 reviewer 共同签字确认关闭；reviewer 不得与 commit author 为同一人。

---

## 12. 已知当前损坏状态

- [current] `compile-protocol-authority.py`（998b）无历史，不可恢复；精确路径已知。
- [current] `trust source`（5,683b stub）无实际编译逻辑。
- 旧 compiler 未实现，projections/fixtures 为残留物。
- **以上状态已知损坏；production 为 fail-closed，从不使用 oracle。**
- **不得在 A1_DESIGN_PASS 通过前执行任何 Step0 代码/文件重定位。**

---

## 13. 本文档元数据

- **版本**：0.1-draft
- **状态**：A1_DESIGN_PASS（2026-08-23）；Step0 现已允许执行
- **Scope**：仅定义框架，不编码任何功能
- **Next Action**：执行 Step0 Baseline + Source Authority（设计已通过）

---

## 14. Migration Output 与 Design Acceptance 澄清

### 14.1 迁移映射记录格式（强制性）

所有从 LEGACY_GATE_A_WIRE_V1 到 PLANNED TARGET A1 的转换**必须**产生以下格式的显式映射记录：

```json5
{
  "legacySchemaId": "string",
  "legacyRawDigest": "WireDigest",
  "targetSchemaId": "string",
  "targetWireDigest": "WireDigest",
  "transformationVersion": "SemVer"
}
```

**强制约束**：
- `legacyRawDigest`：legacy payload 经 JCS 序列化后的 WireDigest。
- `targetWireDigest`：target payload 经 JCS 序列化后的 WireDigest。
- **禁止 in-place reinterpretation**：不得将 legacy wrapper object 的字段直接映射为 target type，而不产生映射记录。
- 映射记录写入独立工件（如 `docs/schemas/migration-map-v1.json`），不修改 source artifact。
- `transformationVersion` 记录迁移逻辑版本，用于审计和回滚。

### 14.2 A1_DESIGN_PASS Gate 与 Step0 关系澄清

| 条件 | 含义 |
|------|------|
| A1_DESIGN_PASS **前** | 仅允许文档编辑；禁止代码/文件重定位；禁止向 `docs/schemas/resource-gateway-capability-studio-a1/` 写入任何 schema artifact |
| A1_DESIGN_PASS **通过后** | 允许 Step0 实现；target schema artifacts 成为 Step0/Source 退出条件 |

**Target schema artifacts 作为 Step0 退出条件，非前置条件**：

- Step0/Source 的退出条件**包含**创建 `docs/schemas/resource-gateway-capability-studio-a1/` 下的 target schema 文件。
- A1_DESIGN_PASS 通过前，这些 schema 文件**尚未创建**；设计文档本身（00、01、02、04）构成 A1_DESIGN_PASS 的评审对象。
- 设计通过后，Step0 实现者按设计文档创建 target schema artifacts；schema 创建本身是 Step0 的一部分，不是设计冻结的前置要求。

### 14.3 当前/planned 状态真值表（Step0 产出：共 13 个 target schema）

| 项目 | 当前状态 | Planned |
|------|----------|---------|
| `docs/schemas/resource-gateway-capability-studio-a1/` | **不存在**（路径待建） | Step0 产出 |
| `normative-primitives-v1.schema.json` | **不存在** | Step0 产出 |
| `source-unit-v1.schema.json` | **不存在** | Step0 产出 |
| `source-package-v1.schema.json` | **不存在** | Step0 产出 |
| `compiler-manifest-v1.schema.json` | **不存在** | Step0 产出 |
| `oracle-manifest-v1.schema.json` | **不存在** | Step0 产出 |
| `attack-case-v1.schema.json` | **不存在** | Step0 产出 |
| `evidence-catalog-entry-v1.schema.json` | **不存在** | Step0 产出 |
| `observation-receipt-v1.schema.json` | **不存在** | Step0 产出 |
| `acceptance-receipt-v1.schema.json` | **不存在** | Step0 产出 |
| `ledger-entry-v1.schema.json` | **不存在** | Step0 产出 |
| `revocation-record-v1.schema.json` | **不存在** | Step0 产出 |
| `observer-failure-v1.schema.json` | **不存在** | Step0 产出 |
| `hermetic-observation-v1.schema.json` | **不存在** | Step0 产出 |
| LEGACY schema (`docs/schemas/resource-gateway-capability-studio/`) | **已存在**，Oracle 签封 | 兼容性保留 |
| `capability-studio-gate-a-protocol-compilation-manifest-v1.schema.json` | **已存在** | 明确为 Legacy CompilerGoldenOracle artifact |

### 14.4 Oracle manifest 与 Target schema 关系澄清

**CompilerGoldenOracle Manifest（LEGACY）**：

- `capability-studio-gate-a-protocol-compilation-manifest-v1.schema.json` 是 **CompilerGoldenOracle** 的 sealed artifact。
- 该 schema 定义了 legacy `protocol-compilation-manifest` 的 shape，供 Oracle 内部增量 diff 使用。
- **Target `compilerManifest` 结构** 由 01 §10.2 的 `compiler-manifest-v1.schema.json` 定义（Step0 计划产出）。
- 两者**不可混淆**：Oracle manifest 是兼容性签封，不是 target compiler manifest 的前身。

**TrustBehaviorOracle Test Artifact**：

- `canonicalization-oracle` 相关 schema（如 JCS 测试向量）是 **TrustBehaviorOracle** 的独立 test artifact。
- 明确**不属于** TrustBehaviorOracle manifest 的 schema 事实源。
- TrustBehaviorOracle manifest schema 见 04 §3.1 planned `oracle-manifest-v1.schema.json`。

### 14.5 Step0 退出条件（澄清版）

Step0 Baseline + Source Authority 的退出条件：

1. [completed] A1_DESIGN_PASS 已通过（2026-08-23）。
2. [planned] 原始 `compile-protocol-authority.py` 和 `trust source` 已精确字节复制到 NON_RELEASE 隔离工件（digest 命名）。
3. [planned] pyc 文件在受控归档路径（NON_RELEASE）保留，直至条件满足（见 04 §1.2）。
4. [planned] `docs/schemas/resource-gateway-capability-studio-a1/` 下 13 个 target schema artifacts 创建（按 01 §10.2 列表）+ migration mapping。
5. [planned] 迁移映射记录工件已创建（格式见 04 §14.1）。

**Schema artifacts 是 Step0 退出条件的一部分，不是 A1_DESIGN_PASS Gate 的前置条件。**
