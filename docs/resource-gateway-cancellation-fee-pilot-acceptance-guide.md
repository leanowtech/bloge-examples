# 取消费申诉 Business Mirror 试点验收指南

本文说明如何把取消费申诉试点的 Package、场景分母、模拟、实现一致性、运行证据、
ANEKE 治理、影响分析、Outcome 和目标环境认证组装为一个可复验的验收包。

仓库提供的是验收协议、参考 Manifest 和离线 verifier，不是客户验收结论。参考文件
`business-mirror-pilot-acceptance-manifest-v1.fixture.json` 的状态固定为：

```text
status = PREPARING
customerAcceptance.status = NOT_REQUESTED
passedGateCount = 0
evidenceAvailableGateCount = 3
blockedGateCount = 7
```

任何本地测试、固定 fixture 或 Resource Gateway 自身进程都不能把它改写成真实的
`CUSTOMER_ACCEPTED`。

## 1. 产物边界

| 产物 | Authority | Resource Gateway 的职责 |
|---|---|---|
| Package Snapshot、Readiness、L0-L3 Closure | Resource Gateway | 编译并输出 immutable facts |
| Owner-frozen Scenario denominator | 客户业务 Owner | RG 记录 exact ref、覆盖计数和未知范围，不代签 |
| Proposal Simulation、Conformance、Mirror Evidence | Resource Gateway 与客户 runtime | 运行、固化内容地址并输出 evidence |
| ANEKE Gate Decision | ANEKE | RG 只引用并回显外部签名治理结果 |
| Outcome population 与 Fidelity | 客户 Outcome Authority | RG 校准并在不可用、迟到、冲突时失败关闭 |
| Regional/Runtime Certification | 客户平台团队 | RG 提供协议与 Harness，不控制客户基础设施 |
| Customer Acceptance Decision | 客户验收 Owner | RG 只接受 exact decision ref，不从十个布尔值自动造审批 |

Manifest 是证据索引，不是上述 Authority 的替代品。消费者仍需解析 exact ref，并按各自
协议验证签名、有效期、Scope、generation 和 freshness。

## 2. Manifest 结构

`BusinessMirrorPilotAcceptanceManifest` 包含以下不可省略的部分：

| 字段 | 约束 |
|---|---|
| `scope` | 完整 `tenantId/organizationId/projectId/environmentId/region` |
| `packageSnapshotRef` | 必须是 exact `DOMAIN_CAPABILITY_PACKAGE` ref |
| `scenarioDenominator` | 单独内容寻址，携带 Owner freeze attestation、场景族、高风险义务和未知范围 |
| `acceptanceGates` | 固定十项、固定顺序、无 `WAIVED` 状态 |
| `observationWindow` | `PLANNED/ACTIVE/COMPLETED/INVALIDATED`，完成时必须引用权威 Outcome population |
| `customerAcceptance` | `ACCEPTED/REJECTED` 必须携带客户决策人、时间和 exact decision ref |
| `status` | 由 Gate、观察窗和客户决策严格推导，调用方不能自由填写 |
| `manifestFingerprint` | 整个 Manifest 的 canonical SHA-256 内容地址 |

`scenarioDenominator` 自己拥有 `denominatorFingerprint`。因此，修改场景族、覆盖计数或未知
范围时，必须先发布新 denominator revision，再重新生成 Manifest；不能只重算外层指纹。

## 3. 取消费场景分母

参考 Manifest 显式列出蓝图中的 12 个场景族：

1. 司机未到达，取消费应免除。
2. 司机已到达且乘客超时，取消费成立。
3. 时间阈值前后一分钟边界。
4. 规则版本、城市和车型差异。
5. 订单状态延迟或冲突。
6. 重复申诉、重复退款和幂等重放。
7. 上游超时、部分失败、fallback 和人工升级。
8. 高风险账户或证据不足时 abstain。
9. Proposal Fixture 未匹配时失败关闭。
10. 模拟与实现的错误码、金额、状态或副作用差异。
11. 文本机器人多轮补信息与会话恢复。
12. Outcome 迟到、冲突或删失时不误绿。

参考数值 `18` 个高风险义务、`14` 个已覆盖义务和 `2` 个未知范围只用于演示协议。
客户 Owner 必须根据真实业务重新冻结分母。只要 Gate
`HIGH_RISK_BRANCH_OBLIGATIONS` 被标记为 `PASSED`，协议就强制
`coveredHighRiskObligationCount == highRiskObligationCount`；未知范围仍保留并可见。

## 4. 十项退出门禁

`EVIDENCE_AVAILABLE` 只表示材料已附上，不能解释为通过。`PASSED` 必须有 `assessedAt`，
并至少包含下表要求的证据种类。Authority 也是每个 Gate 的协议常量，调用方不能把客户
Owner、客户平台或 ANEKE 的 Gate 改成由 RG 自行评估。

| # | Gate | Authority | `PASSED` 最小证据种类 | 参考状态 |
|---:|---|---|---|---|
| 1 | `PACKAGE_DEFINITION_COMPLETE` | RG | Package、Readiness、Scenario denominator | `EVIDENCE_AVAILABLE` |
| 2 | `HIGH_RISK_BRANCH_OBLIGATIONS` | 业务 Owner | Scenario denominator、Business Acceptance Suite | `EVIDENCE_AVAILABLE` |
| 3 | `ISOLATED_PROPOSAL_REHEARSAL` | RG | Proposal Simulation、Isolation Attestation | `BLOCKED` |
| 4 | `SAME_SUITE_IMPLEMENTATION_CONFORMANCE` | RG | Implementation Conformance、同一 Acceptance Suite | `BLOCKED` |
| 5 | `ZERO_EXTERNAL_BUSINESS_WRITES` | 客户平台 | Mirror Evidence Bundle、Runtime Certification | `BLOCKED` |
| 6 | `EVIDENCE_TRACEABILITY` | RG | Package Evidence Index | `EVIDENCE_AVAILABLE` |
| 7 | `ANEKE_GOVERNANCE_ROUND_TRIP` | ANEKE | Registry Ingest Bundle、ANEKE Gate Decision | `BLOCKED` |
| 8 | `CHANGE_IMPACT_ANALYSIS` | RG | Business Asset Impact Report | `BLOCKED` |
| 9 | `OUTCOME_FIDELITY_FAIL_CLOSED` | 业务 Owner | Fidelity Profile、权威 Outcome population | `BLOCKED` |
| 10 | `TARGET_ENVIRONMENT_CERTIFICATION` | 客户平台 | Regional Certification、Runtime Certification | `BLOCKED` |

协议还执行三项跨 Gate 闭包检查：

1. Gate 1 必须引用 Manifest 的 exact Package 和 embedded denominator。
2. Gate 2 必须引用同一个 denominator，不能换成较小分母。
3. Gate 9 必须引用观察窗实际使用的同一 Outcome population。

## 5. 状态推导

| 条件 | 合法 `status` |
|---|---|
| 任一 Gate 未通过，或观察窗未完成 | `PREPARING` |
| 十个 Gate 全部通过、观察窗完成，客户尚未决定或正在复核 | `READY_FOR_CUSTOMER_VALIDATION` |
| 上述条件成立，且客户返回 `ACCEPTED` exact decision ref | `CUSTOMER_ACCEPTED` |
| 客户返回 `REJECTED` exact decision ref 和原因 | `CUSTOMER_REJECTED` |

不存在手工指定 `READY`、跳过 Gate 或本地自动批准的路径。客户接受时间不得早于观察窗完成
时间；实际观察开始、Gate 评估、观察窗结束和客户决策时间也不得晚于 `assembledAt`。
直接引用 denominator 的 Gate 1/2 评估，以及客户最终决策，均不得早于 Owner 冻结分母的
`frozenAt`。`FAILED` Gate 必须有评估证据、评估时间和原因码，不能只留下不可审计的红灯。

## 6. 快速检查参考 Manifest

该路径不需要启动 Resource Gateway：

```bash
jq '{
  status,
  customerAcceptance: .customerAcceptance.status,
  denominator: {
    families: .scenarioDenominator.declaredFamilyCount,
    highRisk: .scenarioDenominator.highRiskObligationCount,
    covered: .scenarioDenominator.coveredHighRiskObligationCount,
    unknown: .scenarioDenominator.unknownRangeCount
  },
  gates: [.acceptanceGates[] | {gateId, state, reasonCodes}]
}' docs/schemas/resource-gateway-business-mirror/business-mirror-pilot-acceptance-manifest-v1.fixture.json
```

运行服务端模型与独立消费者的聚焦测试：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=BusinessMirrorPilotAcceptanceManifestTest test

mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=BusinessMirrorPilotAcceptanceProtocolTest test
```

## 7. Test Kit 离线复验

Test Kit JAR 内包含 strict Schema 和参考 fixture。验证不访问 Resource Gateway，也不会把
业务 Payload 写入日志：

```java
JsonNode manifest = objectMapper.readTree(manifestBytes);

BusinessMirrorPilotAcceptanceProtocol.VerifiedPilotAcceptance verified =
        BusinessMirrorPilotAcceptanceProtocol.verify(manifest);

if (!"READY_FOR_CUSTOMER_VALIDATION".equals(verified.overallStatus())) {
    throw new IllegalStateException(
            "Pilot is not ready; blocked gates=" + verified.blockedGateCount());
}
```

返回值只包含 Manifest/denominator 内容地址、Package ID、计数、状态和时间，不包含 fixture、
请求、响应、凭证或客户决策正文。

## 8. 从参考包走到客户验收

### 8.1 建立同一 Scope 的证据链

参考仓库中的 Package、Simulation、Conformance、Impact 和 Outcome fixtures 来自不同阶段，
部分 ID 或 Scope 不一致。不能把它们机械拼接后标绿。实施时按以下顺序重新生产：

1. 在目标试点 Scope 编译取消费 Package，取得 Snapshot、Readiness 和 Closure。
2. 由业务 Owner 冻结 Scenario family、风险义务和未知范围，发布 freeze attestation。
3. 使用该 denominator 的同一 Business Acceptance Suite 运行 Proposal Simulation。
4. 绑定客户实现后复用同一 Suite 运行 Conformance，保存结构化 diff。
5. 在无真实退款权限、无生产凭证和禁止业务写出口的 Mirror 环境运行全流程。
6. 从 exact Package 生成 Evidence Index，并完成一次 L0 变更影响分析。
7. 将 Registry Ingest Bundle 交给 ANEKE，回收受信 Gate Decision。
8. 在客户目标环境完成 Regional 与 Runtime Certification。
9. 启动只读 Outcome 观察窗，处理迟到、冲突、删失和断流。
10. 全部 Gate 由对应 Authority 评估后，才提交客户验收。

### 8.2 每次变更都重建，而不是修改历史

以下任一变化都应发布新的 denominator 或 Manifest revision：

- 业务规则、城市、车型或风险分层变化；
- Package、Graph、Operator、Suite、Fixture 或实现 binding 变化；
- ANEKE gate generation、Outcome population 或目标环境 certification 变化；
- 观察窗失效、补数、冲突处理或客户复核结论变化。

旧 revision 保留用于审计和 replay。不要原地替换 fingerprint 指向的内容。

## 9. 常见失败

| 错误后缀 | 原因 | 恢复动作 |
|---|---|---|
| `PILOT_ACCEPTANCE_SCHEMA_INVALID` | 缺字段、未知字段、Gate 数量错误或类型错误 | 对 strict Schema 修正输入 |
| `PILOT_DENOMINATOR_FINGERPRINT_MISMATCH` | 分母内容被修改，内层地址未更新 | 发布新 denominator revision 并重新组装 Manifest |
| `PILOT_ACCEPTANCE_FINGERPRINT_MISMATCH` | Manifest 内容与外层地址不一致 | 从确定性 producer 重新生成 |
| `PILOT_GATE_DENOMINATOR_INVALID` | 十项 Gate 缺失、重复或乱序 | 使用协议固定 Gate 列表，不做裁剪 |
| `PILOT_GATE_PASS_EVIDENCE_INCOMPLETE` | Gate 声称通过但缺少最低证据种类 | 补 exact evidence refs，重新评估 |
| `PILOT_CORE_REFERENCE_CLOSURE_INVALID` | Gate 1/2 与 Package 或 denominator 不一致 | 使用同一 revision 和 fingerprint 重建闭包 |
| `PILOT_HIGH_RISK_COVERAGE_INCOMPLETE` | 高风险义务仍有未覆盖项却声称通过 | 补场景或保持 `BLOCKED/FAILED` |
| `PILOT_OUTCOME_REFERENCE_CLOSURE_INVALID` | Fidelity Gate 与观察窗使用不同 Outcome population | 统一到同一权威 population ref |
| `PILOT_CUSTOMER_ACCEPTANCE_UNPROVEN` | 本地直接写入 `ACCEPTED`，但 Gate/观察窗未完成 | 删除伪造状态，完成外部验收流程 |
| `PILOT_ACCEPTANCE_TIME_INVALID` | 评估、观察或决策时间晚于 Manifest 组装时间 | 修复时序并发布新 revision |

## 10. 上线前检查

- [ ] 所有证据属于同一完整 Scope。
- [ ] Package 与 denominator 都是 exact revision + fingerprint。
- [ ] 12 个场景族由客户 Owner 复核，义务数和未知范围不是参考数值。
- [ ] 十项 Gate 无缺失、无 `WAIVED`、无本地代签。
- [ ] Proposal 无真实退款权限、生产凭证和业务写出口。
- [ ] Simulation 与实现 Conformance 使用同一 Acceptance Suite。
- [ ] Mirror External Write 计数为 `0`，且有 Runtime Certification 支撑。
- [ ] ANEKE Gate Decision 的 issuer、key lifecycle、generation 和有效期已验证。
- [ ] Outcome observation 已完成，迟到、冲突和删失没有误绿。
- [ ] PostgreSQL、KMS、网络、证书、备份恢复和数据权利已在目标环境认证。
- [ ] Customer Acceptance Decision 来自客户 Authority，并晚于观察窗完成时间。
- [ ] Test Kit 在独立进程中复验成功。

## 11. 实现位置

| 内容 | 路径 |
|---|---|
| 服务端领域模型 | `businessmirror/pilot/BusinessMirrorPilotAcceptanceManifest.java` |
| 内容寻址与复验 | `businessmirror/pilot/BusinessMirrorPilotAcceptanceManifestIntegrity.java` |
| strict Schema | `docs/schemas/resource-gateway-business-mirror/business-mirror-pilot-acceptance-manifest-v1.schema.json` |
| 参考 Manifest | `docs/schemas/resource-gateway-business-mirror/business-mirror-pilot-acceptance-manifest-v1.fixture.json` |
| 独立 verifier | `resource-gateway-test-kit/.../BusinessMirrorPilotAcceptanceProtocol.java` |

相关前置流程见 [Business Mirror Workspace 指南](resource-gateway-business-mirror-workspace-guide.md)、
[Proposal Simulation 指南](resource-gateway-business-mirror-proposal-simulation-guide.md)、
[Implementation Conformance 指南](resource-gateway-business-mirror-implementation-conformance-guide.md)、
[Package Evidence 指南](resource-gateway-package-evidence-and-fidelity-guide.md)、
[ANEKE Package 接入指南](resource-gateway-aneke-package-integration-guide.md)和
[Runtime Certification 指南](resource-gateway-runtime-certification-guide.md)。
