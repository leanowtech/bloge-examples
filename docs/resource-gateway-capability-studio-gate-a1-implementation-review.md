# Capability Studio Gate A1 — Implementation Review Ledger

> 状态：`STEP0_INDEX_ATTESTED_REVIEW_PASS`。第一轮独立审查的 6 项 P0、2 项 P1 已修复；最终两轮独立复核均为 P0 = 0、P1 = 0，且 index attestation 已通过。提交后的 HEAD/CI attestation 尚未形成，本文不声明生产 Gate A1 PASS。

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

- `A1_DESIGN_PASS`：**通过 2026-08-23**；设计前置门已解除
- `Step0`：实现候选已产出；工作区验证、capture-check 与完整 Maven verify 已通过
- `Step0 index attestation`：**通过 2026-08-23**；`--index-check` 输出 `STEP0_INDEX_PASS`，并封闭枚举 103 个 Legacy 与 13 个 Target authority schema
- `Step0 fresh review`：**通过 2026-08-23**；两名独立 reviewer 均报告 P0 = 0、P1 = 0
- Implementation review（A1_IMPLEMENTATION_PASS）：**尚未发生**；Step5 通过前不记录
- Gate admission：Step0 accepted 前禁止推进 Step1；`DRAFT_UNPINNED` 继续 fail-closed

---

## 5. 第一轮独立审查与修复状态

第一轮审查结论为 **6 项 P0、2 项 P1**。这些 finding 证明旧验证结果不足以形成 Step0 acceptance；后续修复必须由 fresh review 重新验证，不能由原作者自证关闭。

| 级别 | Finding | 根因 | 修复候选 / 复审重点 |
|---|---|---|---|
| P0 | 普通与 shaded JAR 携带 103 个 Legacy schema，却被当作 A1 release artifact 审计 | 兼容产品与 A1 协议产品未分坐标 | 保留普通/CLI 兼容行为；新增独立 `a1-protocol` classifier，封闭校验其仅含 13 个 target schema 与允许的 Maven metadata |
| P0 | HermeticObservation 复用了 `observation-receipt` FP domain | Receipt envelope 与事实 artifact 的身份边界混淆 | 删除 HermeticObservation 的 `observationReceiptFP`；只由 generic `ObservationReceipt.artifactRef` 引用 |
| P0 | Step0 JCS 输入处理允许超出 ECMAScript 安全整数的值 | Python 任意精度整数与 RFC 8785/ECMAScript number 语义不一致 | RFC 8785 保持规范；Authority Numeric Input Profile 拒绝 float 与超界整数，accepted 文档的规范字节必须与 RFC 一致 |
| P0 | 工作区 `STEP0_PASS` 被误读为 committed authority | 验证未绑定 Git index 或 commit attestation | 新增 `--index-check`；文档区分 workspace consistency 与 `STEP0_INDEX_PASS`，并要求外部提交 attestation |
| P0 | SourceUnit schema 缺失 `relationHandle` | prose 与 schema 未形成同一 authority | 将 32 位小写十六进制 `relationHandle` 设为必填并增加正负例 |
| P0 | `OBSERVER_GENERATED` 可被普通 Evidence 使用 | outcome authority 未与 ObserverFailure identity 双向闭合 | schema 按 evidenceId/schemaRef/verifier/inputs/relation 建立双向条件，其他 Evidence 只能 `REDUCTOR_DERIVED` |
| P1 | migration mapping 被描述成语义转换，却只有 5 字段 schema pair | authority 对应关系与字段级迁移语义混为一谈 | 将其明确为 authority pair mapping；不声称字段来源、默认值或信息损失转换能力 |
| P1 | archive verifier 的 `validFrom/validUntil` 自测未验证任何 authority schema | 无归属 helper 被当成协议负例 | 删除伪时序 oracle；改为验证已落入 schema 的 `expectedOutcome/errorCode` 和 ObserverGenerated 双向条件 |

**最终复核记录**：

1. `./scripts/oracle/verify-step0.sh --index-check` 输出 `STEP0_INDEX_PASS`；额外暂存 authority 条目也会被完整 root 枚举并拒绝。
2. `a1-protocol` classifier 精确包含 13 个 Target schema 与允许的 Maven metadata；普通/CLI JAR 精确保留 103 个 index-bound Legacy schema，且不携带 Target A1 schema。
3. 两名独立 reviewer 基于最终 index 复核，均报告 P0 = 0、P1 = 0。
4. 提交后仍需以 commit SHA 形成 HEAD/CI attestation；在此之前不得宣称生产 Gate A1 PASS 或推进 Step1。

---

## 6. 相关链接

- [00-normative-conventions.md](capability-studio-gate-a1-design/00-normative-conventions.md)
- [01-source-package-and-compiler.md](capability-studio-gate-a1-design/01-source-package-and-compiler.md)
- [02-evidence-receipt-and-ledger.md](capability-studio-gate-a1-design/02-evidence-receipt-and-ledger.md)
- [03-hermetic-runtime-and-role-closure.md](capability-studio-gate-a1-design/03-hermetic-runtime-and-role-closure.md)
- [04-migration-oracle-and-acceptance.md](capability-studio-gate-a1-design/04-migration-oracle-and-acceptance.md)
- [资源网关示例](../resource-gateway-examples/README.md)
