# Capability Studio Gate A1 — Implementation Review Ledger

> 状态：`A1_DESIGN_PASS`（2026-08-23）；Step0 未开始；无 implementation review 发生

---

## 1. 规范性文档索引

| 编号 | 文件 | 作用域 |
|---|---|---|
| DOC-00 | [capability-studio-gate-a1-design/00-normative-conventions.md](capability-studio-gate-a1-design/00-normative-conventions.md) | ENC/JCS/WireDigest/TypedFP/Merkle/错误码 |
| DOC-01 | [capability-studio-gate-a1-design/01-source-package-and-compiler.md](capability-studio-gate-a1-design/01-source-package-and-compiler.md) | SourcePackage/Compiler/role visibility/13 target schemas |
| DOC-02 | [capability-studio-gate-a1-design/02-evidence-receipt-and-ledger.md](capability-studio-gate-a1-design/02-evidence-receipt-and-ledger.md) | Evidence Catalog/ObservationReceipt/LedgerEntry/AcceptanceReceipt/T-001..T-032/A-RA1..A-RA4/A1..A31 |
| DOC-03 | [capability-studio-gate-a1-design/03-hermetic-runtime-and-role-closure.md](capability-studio-gate-a1-design/03-hermetic-runtime-and-role-closure.md) | HermeticRuntime/Observer/Role Closure/A1_Hermetic_001..020 |
| DOC-04 | [capability-studio-gate-a1-design/04-migration-oracle-and-acceptance.md](capability-studio-gate-a1-design/04-migration-oracle-and-acceptance.md) | Oracle Bundle/Legacy Ingestion/Migration Mapping/Phase Sequence |

---

## 2. 机器完整性统计

命名空间严格分离；同一编号在不同文档中指代完全不同内容。

| 区间 | 来源 | 主题 |
|------|------|------|
| T-001..T-032 | 02 §12.3 (lines 400-528) | Ledger/Evidence/Adapter Mode 全32个行为测试 |
| A-RA1..A-RA4 | 02 §6 (lines 275-278) | Revocation Authority 四项断言约束 |
| A1..A31 | 02 §11 (lines 445-518) | 机器可检查断言二十八项（A1..A28）+ Adapter Mode 三项（A29..A31）|
| A1_Hermetic_001..020 | 03 §7 (lines 145-154) | Hermetic runtime 二十项攻击向量 |
| AC-01..AC-10 | 本表 §3 | Step0..Step6 退出条件 |

---

## 3. 验收标准

| 编号 | 条件 | 来源 |
|------|------|------|
| AC-01 | Step0 exit: digest-named copies confirmed on disk; audit log entry written | 04 §6.1 |
| AC-02 | Step0 exit: `quarantine/fragments/{digest}/…` artifact paths verified | 04 §6.2 |
| AC-03 | Step0 exit: 13 schema files in `docs/schemas/resource-gateway-capability-studio-a1/` schema-valid (Draft 2020-12) | 01 §10.2 |
| AC-04 | Step0 exit: migration map record created（格式：04 §14.1） | 04 §14.1 |
| AC-05 | 所有 RA Assertion A-RA1..A-RA4 成立 | 02 §6 |
| AC-06 | 所有 Test Case T-001..T-032 通过 | 02 §12.3 |
| AC-07 | 所有 Ledger Assertion A1..A31 通过；所有 Hermetic Attack A1_Hermetic_001..020 有对应测试 | 02 §11 + §12.3 / 03 §7 |
| AC-08 | Step1: 新 compiler compatibility projection byte-match LEGACY golden | 04 §2.2 |
| AC-09 | Step1: TARGET-only artifact 按 compiler manifest independently validated | 04 §2.2 |
| AC-10 | Step6: Oracle excision preserves replay path and audit trail; no production path modification | §4 |

---

## 4. 状态声明

- `A1_DESIGN_PASS`：**通过 2026-08-23**；Step0 现已允许执行
- `Step0`：未开始；damaged compiler / pending conformance / DRAFT_UNPINNED 为 fail-closed blocker，但不阻止 Step0
- Implementation review（A1_IMPLEMENTATION_PASS）：**尚未发生**；Step5 通过前不记录
- Gate admission：Step0 解除 blocking 后按序执行 Step1..Step6

---

## 5. 相关链接

- [00-normative-conventions.md](capability-studio-gate-a1-design/00-normative-conventions.md)
- [01-source-package-and-compiler.md](capability-studio-gate-a1-design/01-source-package-and-compiler.md)
- [02-evidence-receipt-and-ledger.md](capability-studio-gate-a1-design/02-evidence-receipt-and-ledger.md)
- [03-hermetic-runtime-and-role-closure.md](capability-studio-gate-a1-design/03-hermetic-runtime-and-role-closure.md)
- [04-migration-oracle-and-acceptance.md](capability-studio-gate-a1-design/04-migration-oracle-and-acceptance.md)
- [资源网关示例](../resource-gateway-examples/README.md)
