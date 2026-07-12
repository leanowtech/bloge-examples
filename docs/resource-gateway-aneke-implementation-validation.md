# Resource Gateway x ANEKE 实施验证与差距台账

> 本文只记录可由当前代码、自动化测试、运行结果或可检查制品证明的事实。设计文档中的意图、尚未运行的测试和仅存在的 DTO 均不计为完成。

| 属性 | 内容 |
|---|---|
| 设计基线 | `docs/resource-gateway-aneke-tool-studio-integration-evolution-plan.md` |
| 当前实现基线 | `876afa9f` (`Add Tool Studio integration evidence protocol`) |
| 评估日期 | 2026-07-12 |
| 目标 | 加权实施差距 `<3%`，且不存在 P0 阻断项 |
| 最近全量验证 | `mvn -f resource-gateway-examples/pom.xml clean verify`：1433 tests，0 failures，0 errors，2 skipped |

## 1. 评分方法

总权重为 100。每项只按以下证据等级计分：

| 等级 | 计分 | 判定规则 |
|---|---:|---|
| `PROVEN` | 100% | 有生产代码、正反向自动化测试，并验证跨租户/失败/兼容等适用的不变量 |
| `PARTIAL` | 25%-75% | 主路径存在，但缺失败语义、安全控制、持久性、兼容性或端到端证据 |
| `SHAPE_ONLY` | 10% | 只有 DTO、静态 capability 或文档，没有真实生命周期 |
| `MISSING` | 0% | 当前代码没有可消费能力 |

加权差距计算为：`100 - sum(维度权重 * 维度完成率)`。此外，下列任一项存在时，即使数值差距低于 3% 也不得通过：跨租户可读、evidence 不完整却标记可采纳、replay 可产生未批准外部副作用、协议 breaking change 无兼容检测、事件丢失后无法对账。

## 2. Round 0 基线审计

| 维度 | 权重 | 当前完成率 | 得分 | 当前证据 | 关键缺口 |
|---|---:|---:|---:|---|---|
| 协议、版本与身份边界 | 15 | 60% | 9.0 | versioned envelope、capability、problem、tenant/environment scope negative tests | 真实 IAM claims、purpose/actor 授权、N/N-1 schema contract、幂等与审计 |
| GraphDraft 依赖快照 | 10 | 55% | 5.5 | draft/operator/schema fingerprint、libraryId、确定性导出 | runtime binding refs、contract suite refs、readiness/SLA/owner、并发快照事务 |
| Run evidence 可信链 | 20 | 35% | 7.0 | run/draft/operator fingerprint、node output、edge projection、sanitized payload、completeness hash | 精确 node input、retry/fallback 原因、assertion、独立 evidence 状态、quarantine、持久签名/验签 |
| Payload replay | 15 | 20% | 3.0 | 按 runId 返回 sanitized recorded payload，默认声明无外部副作用 | 当前不是 replay 执行；无新 runId/lineage、node input、断言重算、shadow/live policy、授权审批 |
| Timeout/partial failure 语义 | 10 | 25% | 2.5 | integration 状态枚举和基础聚合 | engine 事实未完整捕获；无 deadline、cancel、unknown commit、retry budget、fallback/skip 因果 |
| Workbook、gate feedback 与 Deep Link | 10 | 0% | 0.0 | 无 | suite vNext、governance expectation、gate freshness、draft/node/operator/run deep link |
| Change event、cursor、webhook 与对账 | 10 | 0% | 0.0 | 无 | transactional outbox、opaque cursor、签名投递、DLQ、reconciliation |
| 工业运行控制 | 10 | 10% | 1.0 | H2 持久化、全量回归、部分浏览器测试 | SLO/metrics、quota、retention/residency、KMS、DR、fault/performance/security harness、runbook |
| **合计** | **100** |  | **28.0** |  | **加权差距 72.0%** |

结论：Stage 1 的只读协议主体已形成，Stage 2 只有 evidence 投影雏形。当前 capability 对未实现特性明确返回 `false`，这保持了协议诚实性，但不能被解释为工业级完成。

## 3. 已通过的证明

### 3.1 协议与隔离

- `/api/integration/capabilities` 返回稳定 envelope 和真实 feature flags。
- draft export 显式携带 `operatorRef -> operatorLibraryId`、operator/schema fingerprint。
- run evidence/replay 在 tenant 或 environment 不匹配时统一返回 404，避免授权范围探测。
- 缺失身份上下文返回稳定 `IntegrationProblem`，包含 code、status、retryable 和 correlationId。

### 3.2 Evidence 与 payload

- `VisualGraphRunRecord.v2` 持久化 draft/operator fingerprint、sanitized context/output/node results 和 edge snapshot。
- sanitizer 对 secret/token/authorization/cookie/PII 类键执行有界递归脱敏并记录 redaction manifest。
- evidence manifest 可报告 node/edge capture 数量、缺口和内容 hash，但当前未签名。
- replay 只返回 `RECORDED + SANITIZED`，明确 `rawAvailable=false`、`externalSideEffectsAllowed=false`。

### 3.3 回归范围

```text
mvn -f resource-gateway-examples/pom.xml clean verify
Tests run: 1433, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
```

其中包括 34 个 Selenium 浏览器画布场景，以及 integration controller/service、run repository/history 的针对性测试。

## 4. 当前 P0 阻断项

| ID | 阻断项 | 病根 | 根治验收 |
|---|---|---|---|
| `EVD-01` | node input 不可回放 | 运行适配器只保留最终 `NodeResults`，没有 operator invocation capture | 每个实际执行 attempt 的 sanitized input/output、attempt、error 都与 executionId/nodeId 绑定 |
| `EVD-02` | manifest 可哈希但不可证明来源 | evidence projection 没有持久 key identity 和签名服务 | 重启后旧 evidence 仍可验；篡改任意受保护字段均失败；keyId/version 可追溯 |
| `EVD-03` | run success 与 evidence acceptance 混在一起 | evidence 是读取时临时投影，没有 `READY/QUARANTINED` 生命周期 | capture 缺失、sanitizer/signature 失败进入 quarantine，publish consumer 无法误采纳 |
| `RPL-01` | replay 只是 payload fetch | 没有 replay command、lineage 和 assertion evaluator | recorded replay 生成新 run/evidence，parentRunId 不可变，默认零外部调用并可重算断言 |
| `SEC-01` | header 身份可自报 | 示例尚无企业 IAM adapter 和 service-layer authorization policy | 受信 identity adapter + ABAC/purpose policy + repository predicate + audit negative tests |

## 5. 迭代记录

| Round | 目标 | 结果 | 加权差距 | 证据 |
|---|---|---|---:|---|
| 0 | 建立可审计基线 | Stage 1 主体、Stage 2 雏形 | 72.0% | commit `876afa9f`、1433 tests |

后续每轮必须更新本表、代码证据、失败测试和剩余阻断项。只有全部 P0 阻断关闭、全量验证与真实浏览器验证通过，并且按同一权重计算的差距 `<3%`，才允许把目标标记完成。
