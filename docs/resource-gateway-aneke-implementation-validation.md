# Resource Gateway x ANEKE 实施验证与差距台账

> 本文只记录可由当前代码、自动化测试、运行结果或可检查制品证明的事实。设计文档中的意图、尚未运行的测试和仅存在的 DTO 均不计为完成。

| 属性 | 内容 |
|---|---|
| 设计基线 | `docs/resource-gateway-aneke-tool-studio-integration-evolution-plan.md` |
| 当前实现基线 | Round 3（transactional outbox、opaque cursor、reconciliation） |
| 评估日期 | 2026-07-12 |
| 目标 | 加权实施差距 `<3%`，且不存在 P0 阻断项 |
| 最近全量验证 | `mvn -f resource-gateway-examples/pom.xml clean verify`：1461 tests，0 failures，0 errors，2 skipped |

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

## 2.1 Round 1 重新审计

本轮关闭了“evidence 只有形状、没有可信来源”和“治理结论无法回到画布”的主要缺口，但 replay、事件同步和企业身份仍是大块空白。

| 维度 | 权重 | 当前完成率 | 得分 | 本轮可证明增量 | 仍未证明 |
|---|---:|---:|---:|---|---|
| 协议、版本与身份边界 | 15 | 65% | 9.75 | gate feedback purpose 校验、幂等冲突、capability 对象/端点同步 | 受信 IAM claims、N/N-1 consumer contract、service-account/人类委托链 |
| GraphDraft 依赖快照 | 10 | 55% | 5.50 | 无新增 | runtime binding refs、contract suite refs、事务一致性和完整 readiness profile |
| Run evidence 可信链 | 20 | 75% | 15.00 | 精确 node invocation input/output/error、脱敏、完整性缺口、READY/QUARANTINED、持久 Ed25519 seal、重启后验签、离线公钥验证 | 引擎内部 retry attempt/fallback/cancel 的完整事实、KMS/HSM key custody、evidence retention lifecycle |
| Payload replay | 15 | 30% | 4.50 | recorded replay 已返回真实脱敏 node input/output | 尚未生成 replay run、parent lineage、断言重算、shadow/live policy 和副作用审批 |
| Timeout/partial failure 语义 | 10 | 45% | 4.50 | node timeout 推导、graph PARTIAL、edge timeout 传播、attempt error type | deadline/cancel/unknown commit、引擎内部 retry budget、fallback/skip 因果 |
| Workbook、gate feedback 与 Deep Link | 10 | 55% | 5.50 | immutable gate result、fingerprint freshness、idempotency、draft/node/operator/run/gate issue Deep Link、作者页阻断回显 | workbook case/assertion vNext、owner/workbook/migration gate 的完整消费闭环 |
| Change event、cursor、webhook 与对账 | 10 | 0% | 0.00 | 无 | transactional outbox、cursor、签名 webhook、DLQ、reconciliation |
| 工业运行控制 | 10 | 20% | 2.00 | H2 restart 签名验证、真实浏览器 desktop/mobile 验证、Strict Mode 回归 | IAM、KMS、SLO/metrics、quota、retention/residency、DR、fault/performance/security harness |
| **合计** | **100** |  | **46.75** |  | **加权差距 53.25%** |

Round 1 结论：差距从 `72.0%` 降到 `53.25%`。数值仍远高于 `<3%`，并且 `RPL-01`、`SEC-01`、事件丢失后不可对账三项仍是 P0 阻断，因此不得声称工业级完成。

## 2.2 Round 2 重新审计

本轮把“读取历史 payload”与“执行 recorded replay”拆成 GET/POST 两种语义，并让 replay 成为新的签名 run/evidence，而不是临时响应。

| 维度 | 权重 | 当前完成率 | 得分 | 本轮可证明增量 | 仍未证明 |
|---|---:|---:|---:|---|---|
| 协议、版本与身份边界 | 15 | 70% | 10.50 | replay request/result 独立版本；run evidence v1/v2 同时声明；requestId 幂等与冲突语义 | 受信 IAM claims、自动 consumer compatibility matrix、跨实例幂等锁 |
| GraphDraft 依赖快照 | 10 | 55% | 5.50 | 无新增 | runtime binding refs、contract suite refs、事务一致性和完整 readiness profile |
| Run evidence 可信链 | 20 | 78% | 15.60 | replay lineage、断言结果、side-effect policy 被纳入 run material fingerprint 和新 seal | 引擎内部 retry/fallback/cancel、KMS/HSM、retention lifecycle |
| Payload replay | 15 | 80% | 12.00 | POST command 生成 deterministic replay run；parent lineage；output/node/path/schema/error/governance assertions；默认且唯一 `DENY`；external call=0；成功/失败均持久化 | shadow/live 审批与隔离、unknown commit、跨实例 exactly-once、选择性 payload retention |
| Timeout/partial failure 语义 | 10 | 45% | 4.50 | replay edge 明确 `MOCKED` | deadline/cancel/unknown commit、引擎内部 retry budget、fallback/skip 因果 |
| Workbook、gate feedback 与 Deep Link | 10 | 65% | 6.50 | replay caseType 对齐 golden/negative/boundary/regression；断言覆盖 path/schema/error/governance expectation | contract suite vNext 持久资产、ANEKE workbook refs、coverage policy 双向映射 |
| Change event、cursor、webhook 与对账 | 10 | 0% | 0.00 | 无 | transactional outbox、cursor、签名 webhook、DLQ、reconciliation |
| 工业运行控制 | 10 | 22% | 2.20 | replay DB restart、签名与失败证据回归 | IAM、KMS、SLO/metrics、quota、retention/residency、DR、fault/performance/security harness |
| **合计** | **100** |  | **56.80** |  | **加权差距 43.20%** |

Round 2 结论：`RPL-01` 的 recorded replay 根治验收已满足，差距降至 `43.20%`。下一最高收益且仍属 P0 的病根是 `EVT-01`：没有 outbox/cursor/reconciliation 时，ANEKE 无法证明持续同步没有静默丢资产。

## 2.3 Round 3 重新审计

本轮没有先做 webhook，而是先根治“权威数据已提交但集成事件丢失”和“消费者离线后只能猜测全量状态”两个更底层的问题。资产变更与 outbox 在同一数据库事务中提交；ANEKE 先用一致性快照建立投影，再以受签名、作用域绑定的 opaque cursor 持续拉取。事件丢失、重复消费或 cursor 过期时，都能回到 reconciliation，而不是依赖人工改库。

| 维度 | 权重 | 当前完成率 | 得分 | 本轮可证明增量 | 仍未证明 |
|---|---:|---:|---:|---|---|
| 协议、版本与身份边界 | 15 | 75% | 11.25 | integration event/cursor/reconciliation schema version；cursor 签名、租户/环境绑定、过期 410；capability 与端点同步 | 受信 IAM claims、自动 N/N-1 consumer matrix、服务账号委托链、cursor confidentiality |
| GraphDraft 依赖快照 | 10 | 60% | 6.00 | reconciliation 直接从数据库读取 scoped draft/contract，并返回不可变 revision/fingerprint/payload ref | runtime binding refs、suite refs 写入 GraphDraft bundle、完整 readiness profile |
| Run evidence 可信链 | 20 | 78% | 15.60 | RUN_COMPLETED 与 run/evidence 同事务产生稳定引用，reconciliation 可发现当前 run | 引擎内部 retry/fallback/cancel、KMS/HSM、retention lifecycle |
| Payload replay | 15 | 80% | 12.00 | 无语义变化 | shadow/live 审批与隔离、unknown commit、跨实例 exactly-once |
| Timeout/partial failure 语义 | 10 | 45% | 4.50 | 无语义变化 | deadline/cancel/unknown commit、retry budget、fallback/skip 因果 |
| Workbook、gate feedback 与 Deep Link | 10 | 70% | 7.00 | operator contract suite 改为持久、带 revision 的资产；suite 变更进入 outbox；精确 revision 可按 payload ref 读取 | workbook ref 双向映射、coverage policy、owner/migration gate 完整闭环 |
| Change event、cursor、webhook 与对账 | 10 | 80% | 8.00 | draft/operator/run/suite 同事务 outbox；全局 stream sequence；bounded high-water page；稳定重读；作用域隔离；cursor 防篡改/过期；DB-authoritative reconciliation | signed webhook、DLQ、投递重试、outbox retention/compaction、乱序 webhook fault harness |
| 工业运行控制 | 10 | 27% | 2.70 | outbox/cursor signer 重启可用；篡改/跨租户/回滚/分页测试；corporate 架构图自动检查 | IAM、KMS、SLO/metrics、quota、retention/residency、外部 HA DB、DR 和容量演练 |
| **合计** | **100** |  | **67.05** |  | **加权差距 32.95%** |

Round 3 结论：在当前支持的资产模型内，`EVT-01` 的“事件不可静默丢失且可对账”根治验收已满足，差距从 `43.20%` 降至 `32.95%`。但这只是可靠 pull feed，不等于 webhook 产品化完成；`SEC-01` 与 `OPS-01` 仍是 P0，且企业部署仍缺少外部数据库、指标、保留和灾备证明。

## 3. 已通过的证明

### 3.1 协议与隔离

- `/api/integration/capabilities` 返回稳定 envelope 和真实 feature flags。
- draft export 显式携带 `operatorRef -> operatorLibraryId`、operator/schema fingerprint。
- run evidence/replay 在 tenant 或 environment 不匹配时统一返回 404，避免授权范围探测。
- 缺失身份上下文返回稳定 `IntegrationProblem`，包含 code、status、retryable 和 correlationId。

### 3.2 Evidence 与 payload

- `VisualGraphRunRecord.v3` 持久化 draft/operator fingerprint、sanitized context/output/node results、edge snapshot 和精确 node attempts。
- sanitizer 对 secret/token/authorization/cookie/PII 类键及 Bearer/Basic/labeled credential 内容执行有界递归脱敏并记录 manifest。
- evidence manifest 校验每个 invoked node 的 input、成功节点 output、node/edge status 和持久 seal；缺口或验签失败进入 `QUARANTINED`。
- `DatabaseVisualEvidenceSigner` 持久化 Ed25519 key history；旧 run 在 repository 重建后仍可验证，consumer 可通过公开 verification key 离线验签。
- replay 仍只返回 `RECORDED + SANITIZED`，明确 `rawAvailable=false`、`externalSideEffectsAllowed=false`；本轮只补真实 node input，没有把它误报成 replay execution。

### 3.3 Governance 与 Deep Link

- gate result 绑定 `draftId + revision + draftFingerprint`，相同 id/内容幂等，不同内容冲突。
- Author read model 明确返回 `CURRENT/STALE/EXPIRED/MISSING`，草稿修订后旧结果立即变成 `STALE`。
- `/author/?draftId=&nodeId=&operatorRef=&runId=&gateIssueId=` 可恢复存量草稿、自动布局并定位上下文。
- 门禁问题可以从 `targetPath/deepLink` 聚焦节点，失效目标显示 warning。
- 真实浏览器发现并修复 React Strict Mode 首次加载被取消后无法重试的问题。

### 3.4 回归范围

```text
mvn -f resource-gateway-examples/pom.xml clean verify
Tests run: 1461, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
```

其中包括 34 个 Selenium 浏览器画布场景，以及 integration controller/service、run repository/history 的针对性测试。

Round 1 前端与手工浏览器证据：

```text
npm test -- --run src/api.test.ts src/AuthorCanvas.test.tsx
51 passed

npm run build
TypeScript + Vite production build passed

Browser: 1280x720 desktop + 390x844 mobile
desktop gate CURRENT/BLOCKED rendered; approvalPolicy selected;
click owner-approval -> buildResponse selected; mobile bodyScrollWidth == viewportWidth.
```

### 3.5 持续同步与反熵

- `GraphDraft`、operator library、run 和 operator contract suite 的数据库写入与 integration outbox append 处于同一事务；注入 outbox 失败时资产和事件共同回滚。
- outbox 是 append-only 全局序列；租户投影只读取自身 tenant/environment，加上显式定义为全局的 operator library 和 contract suite。
- 分页在首次请求时冻结 high-water mark；同一 cursor 重读得到同一页，窗口期间新增事件延后到 checkpoint 后读取，不产生页漂移。
- cursor 使用持久 Ed25519 signer，绑定 tenant/environment/purpose 和有效期；篡改、错作用域返回 400，过期返回 410 并指向 reconciliation 恢复路径。
- reconciliation 在 repeatable-read 事务中先冻结 outbox high-water，再从数据库权威表生成当前资产清单、计数和 rolling fingerprint；缓存不参与对账事实。
- operator library 与 contract suite 的 event payload ref 可按 revision 精确读取，避免 mutable latest 破坏历史事件解释。
- `visual` 包仅依赖 neutral `VisualChangeEventPublisher` SPI，不反向依赖 integration 实现；边界测试已覆盖该约束。

本轮聚焦证明：

```text
58 Java tests passed
rollback atomicity, cursor tamper/scope/expiry/restart,
bounded paging/repeat reads/late events, reconciliation isolation,
immutable payload refs, controller contract, visual package boundary

132 frontend tests passed
TypeScript + Vite production build passed

Draw.io validation: 0 errors, 0 warnings, 0 crossings/overlaps
```

真实 jar HTTP 证明（端口 `18081`，验证后已停止）：

```text
initial reconciliation assets = 0
create tenant-a draft -> GRAPH_DRAFT_CREATED, streamSequence=1, aggregate.sequence=1
event payloadRef -> immutable draft revision 1 export
next reconciliation -> GRAPH_DRAFT + GRAPH_CONTRACT + rolling sha256 fingerprint
tenant-b reconciliation contains tenant-a draft = false
```

边界说明：当前 cursor 内容经过签名并禁止客户端解释，但不是加密 token。Base64 解码后可看到 scope 和全局序列位置，因此仍存在跨租户活动量元数据侧信道；进入严格多租户部署前应改为加密 token 或服务端随机 cursor handle。

## 4. 当前 P0 阻断项

| ID | 阻断项 | 病根 | 根治验收 |
|---|---|---|---|
| `SEC-01` | header 身份可自报 | 示例尚无企业 IAM adapter 和 service-layer authorization policy | 受信 identity adapter + ABAC/purpose policy + repository predicate + audit negative tests |
| `OPS-01` | 本地签名 key 不满足企业 custody | private key 由本地 H2 demo provider 保存 | KMS/HSM-backed `VisualEvidenceSigner`、rotation/disable/revoke、审计和灾备演练 |

`EVT-01` 已在 Round 3 对当前资产模型关闭。webhook、DLQ、retention 和投递指标仍为 Stage 4 的 P1/工业化差距，但不再构成“事件丢失后无法对账”的 P0 病根。

## 5. 迭代记录

| Round | 目标 | 结果 | 加权差距 | 证据 |
|---|---|---|---:|---|
| 0 | 建立可审计基线 | Stage 1 主体、Stage 2 雏形 | 72.0% | commit `876afa9f`、1433 tests |
| 1 | 可信 evidence + gate/Deep Link 闭环 | evidence 可隔离/签名/离线验，作者可从 ANEKE 问题直达节点 | 53.25% | commit `1fd6b889` + frontend working tree、1439 Java tests、51 frontend tests、desktop/mobile browser |
| 2 | recorded replay command | 新 replay run/evidence、parent lineage、四类 case、path/schema/error/governance 断言、零外部调用、幂等 | 43.20% | 1443 Java tests、132 frontend tests、H2 restart、signed replay evidence、desktop/mobile browser |
| 3 | transactional outbox + cursor + reconciliation | 资产/事件原子提交、稳定分页、作用域签名 cursor、过期重建、不可变 revision ref、持续反熵 | 32.95% | 1461 Java tests、132 frontend tests、真实 jar HTTP 多租户闭环、Draw.io 0 errors/warnings |

后续每轮必须更新本表、代码证据、失败测试和剩余阻断项。只有全部 P0 阻断关闭、全量验证与真实浏览器验证通过，并且按同一权重计算的差距 `<3%`，才允许把目标标记完成。
