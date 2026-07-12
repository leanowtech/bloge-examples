# Resource Gateway x ANEKE 实施验证与差距台账

> 本文只记录可由当前代码、自动化测试、运行结果或可检查制品证明的事实。设计文档中的意图、尚未运行的测试和仅存在的 DTO 均不计为完成。

| 属性 | 内容 |
|---|---|
| 设计基线 | `docs/resource-gateway-aneke-tool-studio-integration-evolution-plan.md` |
| 当前实现基线 | Round 10（execution-budget 跨层传播、deadline admission、HTTP/remote budget） |
| 评估日期 | 2026-07-12 |
| 目标 | 加权实施差距 `<3%`，且不存在 P0 阻断项 |
| 最近全量验证 | `mvn -f resource-gateway-examples/pom.xml clean verify`：1524 tests，0 failures，0 errors，2 skipped，`BUILD SUCCESS`（04:57） |

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

## 2.4 Round 4 重新审计

本轮把身份信任根从客户端 header 移到服务端 credential resolver。Controller 在任何资源查询前验证 Bearer credential；resolver 只产出服务端 claims；operation、requested purpose 与 identity purpose allowlist 三者必须同时成立；旧身份 header 只作为一致性 hint，冲突立即拒绝。service/repository 的 tenant/environment predicate 保留为第二道防线，所有认证决定进入不含 credential 的 append-only audit。

| 维度 | 权重 | 当前完成率 | 得分 | 本轮可证明增量 | 仍未证明 |
|---|---:|---:|---:|---|---|
| 协议、版本与身份边界 | 15 | 88% | 13.20 | Bearer trust boundary；可替换 resolver SPI；server-owned tenant/org/project/environment/actor；operation × purpose 双 allowlist；hint mismatch 403；401 Bearer challenge；capability 揭示 provider/demo mode；scope negative tests | 企业 OIDC/mTLS adapter 实联调、多 identity/group/clearance、delegation grant、rotation/revocation propagation、自动 N/N-1 matrix |
| GraphDraft 依赖快照 | 10 | 60% | 6.00 | 无语义变化 | runtime binding refs、suite refs 写入 bundle、完整 readiness profile |
| Run evidence 可信链 | 20 | 78% | 15.60 | evidence 读取现在先通过受信 identity 和 operation policy | 引擎内部 retry/fallback/cancel、KMS/HSM、retention lifecycle |
| Payload replay | 15 | 80% | 12.00 | replay command 只能由允许 `PAYLOAD_REPLAY` 的受信 identity 发起，错误 purpose 在资源查询前拒绝 | shadow/live 审批与隔离、unknown commit、跨实例 exactly-once |
| Timeout/partial failure 语义 | 10 | 45% | 4.50 | 无语义变化 | deadline/cancel/unknown commit、retry budget、fallback/skip 因果 |
| Workbook、gate feedback 与 Deep Link | 10 | 70% | 7.00 | gate result write/read 分别绑定独立 operation-purpose policy | workbook ref 双向映射、coverage policy、owner/migration gate 完整闭环 |
| Change event、cursor、webhook 与对账 | 10 | 80% | 8.00 | change feed/reconciliation/library/suite 统一只能使用 `CHANGE_SYNC` purpose，cursor scope 来自受信 claims | signed webhook、DLQ、投递重试、retention/compaction、乱序 fault harness |
| 工业运行控制 | 10 | 32% | 3.20 | credential-free access audit 持久化/重启；allow/deny reason；demo/production provider capability；关闭 provider 后 fail closed | 企业 IAM/KMS、审计导出与 retention、SLO/metrics、quota、HA DB、DR 和容量演练 |
| **合计** | **100** |  | **69.50** |  | **加权差距 30.50%** |

Round 4 结论：客户端无法再通过伪造 `X-Tenant-Id` 建立 Integration 身份，`SEC-01` 的 header 自报病根已关闭；差距从 `32.95%` 降至 `30.50%`。但仓库默认 resolver 是明确标记 `demoMode=true` 的单 workload server registry，不能代替客户 IAM。真实 OIDC/mTLS、凭证轮换/撤销、组织委托和策略分发仍作为 `IAM-01` P0 保留。

## 2.5 Round 5 重新审计

本轮补上可执行的生产签名身份路径，而不再只留一个“企业自行实现 resolver”的接口。`SignedJwtIntegrationIdentityResolver` 使用 JDK crypto 严格验证短时 `RS256/EdDSA` JWT；`IntegrationJwtTrustStore` 管理多 `kid`、key 生命周期与 key/`jti` 撤销；审计记录增加不可用于重放的 `credentialId(kid)` 与 `tokenId(jti)`。静态 Bearer 继续存在，但 capability 明确标记 demo mode。

| 维度 | 权重 | 当前完成率 | 得分 | 本轮可证明增量 | 仍未证明 |
|---|---:|---:|---:|---|---|
| 协议、版本与身份边界 | 15 | 94% | 14.10 | signed JWT provider；RS256/EdDSA；issuer/audience/time/purpose/scope 校验；多 key 轮换；key/jti 撤销；kid/jti audit correlation；weak-key/alg confusion/duplicate JSON negative tests；capability 诚实揭示 provider 状态 | 动态 JWKS/KMS 或 mTLS 实联调、撤销传播 SLO、多 identity/group/clearance、正式 delegation grant、自动 N/N-1 matrix |
| GraphDraft 依赖快照 | 10 | 60% | 6.00 | 无语义变化 | runtime binding refs、suite refs 写入 bundle、完整 readiness profile |
| Run evidence 可信链 | 20 | 78% | 15.60 | evidence 访问可由短时 signed workload identity 授权，审计可定位验证 key/token id | 引擎内部 retry/fallback/cancel、KMS/HSM evidence key、retention lifecycle |
| Payload replay | 15 | 80% | 12.00 | replay 的 `PAYLOAD_REPLAY` purpose 可由签名 claims 最小授权 | shadow/live 审批与隔离、unknown commit、跨实例 exactly-once |
| Timeout/partial failure 语义 | 10 | 45% | 4.50 | 无语义变化 | deadline/cancel/unknown commit、retry budget、fallback/skip 因果 |
| Workbook、gate feedback 与 Deep Link | 10 | 70% | 7.00 | gate feedback 写权限可绑定 signed workload 的独立 purpose | workbook ref 双向映射、coverage policy、owner/migration gate 完整闭环 |
| Change event、cursor、webhook 与对账 | 10 | 80% | 8.00 | change sync token 的 audience/scope/purpose/TTL 可验证，泄漏或撤销后可按 jti 阻断 | signed webhook、DLQ、投递重试、retention/compaction、乱序 fault harness |
| 工业运行控制 | 10 | 36% | 3.60 | key 生命周期配置、轮换窗口、key/token revoke、kid/jti 持久审计、严格 crypto attack tests、图与操作文档同步 | 动态撤销传播、SIEM/retention/legal hold、SLO/metrics、quota、HA DB、DR、容量和真实 IdP 演练 |
| **合计** | **100** |  | **70.80** |  | **加权差距 29.20%** |

Round 5 结论：差距从 `30.50%` 降至 `29.20%`。代码已经具备不依赖静态 secret 的 signed workload 身份基线，且轮换、撤销和审计链有正反向测试；但 `IAM-01` 不能仅凭本地生成 key 的测试关闭，必须取得客户 IAM/JWKS/KMS 或 mTLS 的动态传播和组织策略联调证据。下一轮应转向当前更低成熟度且直接影响 evidence 解释正确性的 timeout/partial failure 事实链。

## 2.6 Round 6 重新审计

本轮根治的是“治理证据根据错误文本和相邻节点状态猜执行语义”。Gateway 现在直接消费 BLOGE
`ExecutionListener/ResilienceListener`，把策略上限、真实 retry/timeout/fallback 事件、拓扑推导的 skip/cancel
因果、关键输出聚合和副作用不确定性写入签名 run material。任何无法唯一关联的 resilience event 会形成
可见 evidence gap，而不是串到另一个并发 run。

| 维度 | 权重 | 当前完成率 | 得分 | 本轮可证明增量 | 仍未证明 |
|---|---:|---:|---:|---|---|
| 协议、版本与身份边界 | 15 | 95% | 14.25 | `runEvidenceBundle.v3` additive 协议；v1/v2/v3 同时声明；新 capability 对 deadline/cancel/commit confirmation 诚实返回 false | 动态 IAM/mTLS 联调、自动 N/N-1 consumer matrix、正式 delegation/clearance |
| GraphDraft 依赖快照 | 10 | 60% | 6.00 | run node snapshot 增加 operator effect/idempotency，供 unknown-commit 解释 | runtime binding/suite refs 完整写入导出、跨表快照一致性、完整 readiness profile |
| Run evidence 可信链 | 20 | 86% | 17.20 | `VisualGraphRunRecord.v5` 持久化并签名 execution facts；manifest 增加 fact coverage；edge propagation 不再冒充 target status；旧记录缺 facts 自动 quarantine | KMS/HSM、retention/legal hold、payload classification、commit receipt 和外部 verifier consumer contract |
| Payload replay | 15 | 80% | 12.00 | replay facts 明确 `MOCKED/NOT_INVOKED`，不混入真实 resilience event | shadow/live 审批与隔离、跨实例 exactly-once、选择性 retention |
| Timeout/partial failure 语义 | 10 | 78% | 7.80 | engine-observed retry scheduled/exhausted、timeout、fallback；configured/observed attempts；skip/cancel cause；关键输出驱动 PARTIAL；unknown commit；并发关联歧义 fail closed | 图级 deadline、剩余预算传播、用户 cancel/fencing、commit reconciliation、side-effect-aware retry budget |
| Workbook、gate feedback 与 Deep Link | 10 | 70% | 7.00 | v3 原因码、来源、因果和 unknown commit 可直接进入 workbook/gate 规则 | 双向 workbook refs、coverage policy、owner/migration gate 完整闭环 |
| Change event、cursor、webhook 与对账 | 10 | 80% | 8.00 | 无语义变化 | signed webhook、DLQ/重试、retention/compaction、乱序 fault harness |
| 工业运行控制 | 10 | 39% | 3.90 | 17 个聚焦语义/contract tests、52 个运行/integration/repository/controller 回归、并发歧义 negative test、corporate Draw.io 0 warning | SLO/metrics、quota、HA DB、DR、deadline/cancel fault injection、容量与真实 IdP/KMS 演练 |
| **合计** | **100** |  | **76.15** |  | **加权差距 23.85%** |

Round 6 结论：差距从 `29.20%` 降至 `23.85%`。`statusMap + error text` 的证据解释病根已经关闭，
timeout、fallback、retry exhaustion、skip、cancel、edge propagation 和 graph partial 均有结构化测试。但 Stage 2
仍不能退出：Resource Gateway 还没有 run-level deadline/cancel/fencing，也不能确认超时外部写的 commit 结果；
这两项必须以运行协议和 operator receipt 解决，不能继续靠 DTO 字段补洞。

## 2.7 Round 7 重新审计

本轮先关闭“HTTP 返回或 future 异常就等于运行已停止”的危险假设。Resource Gateway 现在拥有 versioned
`VisualRunIntent`、绝对 deadline、fenced cancel command、monotonic revision 和显式 control endpoint；运行时同时
跟踪 scheduler owner 与 operator in-flight threads。忽略中断的业务算子不会被误报为取消成功，而会形成
`TERMINATION_UNCONFIRMED` 和可被 ANEKE 消费的 quarantine gap。

| 维度 | 权重 | 当前完成率 | 得分 | 本轮可证明增量 | 仍未证明 |
|---|---:|---:|---:|---|---|
| 协议、版本与身份边界 | 15 | 96% | 14.40 | `VisualRunIntent.v1`、`VisualRunControl.v1`、evidence v4；capability 显式拆分 deadline/cancel/confirmation/hard kill/durable control；fence lookup/cancel | visual control endpoint 尚未接企业 IAM scope；自动 N/N-1 consumer matrix；动态 IAM/mTLS 联调 |
| GraphDraft 依赖快照 | 10 | 60% | 6.00 | 无语义变化 | runtime binding/suite refs、跨表一致快照、完整 readiness profile |
| Run evidence 可信链 | 20 | 90% | 18.00 | `VisualGraphRunRecord.v6` 将 control fact 纳入 fingerprint/seal；evidence v4 导出 request/execution/status/revision/deadline/termination/side-effect risk；未确认终止 fail closed | KMS/HSM、retention/legal hold、commit receipt、外部 verifier matrix |
| Payload replay | 15 | 80% | 12.00 | replay run 明确使用 unmanaged control fact，不冒充真实 cancel/deadline | shadow/live 审批隔离、跨实例 exactly-once、选择性 retention |
| Timeout/partial failure 语义 | 10 | 92% | 9.20 | 图级绝对 deadline；用户 cancel；fence/revision；owner+operator 双条件终止确认；grace 未确认；真实 UI 控制入口 | 剩余预算向节点/HTTP 下游传播、disconnect policy、durable lease/fencing owner、commit reconciliation |
| Workbook、gate feedback 与 Deep Link | 10 | 70% | 7.00 | evidence v4 的 control reason 与 quarantine gap 可直接作为 workbook/gate 条件 | 双向 workbook refs、coverage policy、owner/migration gate 完整闭环 |
| Change event、cursor、webhook 与对账 | 10 | 80% | 8.00 | 终态 run 仍沿既有事务 outbox 发布 | signed webhook、DLQ/重试、retention/compaction、乱序 fault harness |
| 工业运行控制 | 10 | 45% | 4.50 | deadline/cancel 并发测试；错误 fence/过期 revision；不合作算子 fault test；HTTP 映射；画布 deadline/stop UX；corporate lifecycle 图 | durable control repository、leader lease、restart takeover、SLO/metrics、quota、HA/DR/容量演练 |
| **合计** | **100** |  | **79.10** |  | **加权差距 20.90%** |

Round 7 结论：差距从 `23.85%` 降至 `20.90%`。单进程 authoring runtime 已具备诚实的 deadline/cancel
语义，`RUN-01` 不再是“完全没有控制协议”；但它仍是 P0，因为 active control state 不能跨重启接管、deadline
预算尚未传到 runtime binding，而且 visual control endpoint 尚未进入受信 workload identity 作用域。更关键的
`RUN-02` 也未关闭：即使线程终止被确认，已发出的远端写仍可能是 `UNKNOWN_COMMIT`。

## 2.8 Round 8 重新审计

本轮把控制状态从 JVM map 提升为数据库权威状态机。`dynamic_run_controls` 以唯一 requestId 建 claim，原始
fencing token 只以 SHA-256 digest 保存；owner id/epoch、revision 和 lease 约束所有 owner mutation。另一实例可以
提交 cancel，当前 owner 在固定轮询/续租点观察后中断本地线程。owner 消失不会让状态静默丢失：lease 过期后，
首次读取在行锁内写入 `OWNER_LEASE_EXPIRED + TERMINATION_UNCONFIRMED + ABANDONED`，并阻止旧 owner 覆盖。

| 维度 | 权重 | 当前完成率 | 得分 | 本轮可证明增量 | 仍未证明 |
|---|---:|---:|---:|---|---|
| 协议、版本与身份边界 | 15 | 96.5% | 14.475 | capability 拆分 durable control、跨实例 cancel、lease/epoch、expired quarantine 与 restart resumption；原始 fence 不落库 | visual control endpoint 企业 IAM scope；自动 N/N-1 consumer matrix；动态 IAM/mTLS 联调 |
| GraphDraft 依赖快照 | 10 | 60% | 6.00 | 无语义变化 | runtime binding/suite refs、跨表一致快照、完整 readiness profile |
| Run evidence 可信链 | 20 | 90% | 18.00 | owner lease 过期形成稳定、可查询的 fail-closed control fact，不再因重启丢失 | abandoned run 自动形成 signed evidence/outbox；KMS/HSM；retention/legal hold；commit receipt |
| Payload replay | 15 | 80% | 12.00 | 无语义变化 | shadow/live 审批隔离、跨实例 exactly-once、选择性 retention |
| Timeout/partial failure 语义 | 10 | 95% | 9.50 | durable claim；跨实例 fenced cancel；并发单赢家；owner lease/epoch；租约过期 abandonment；cancel-before-start 不被晚 start 覆盖 | remaining-budget 向 node/HTTP 传播；disconnect policy；unknown commit reconciliation |
| Workbook、gate feedback 与 Deep Link | 10 | 70% | 7.00 | `OWNER_LEASE_EXPIRED` 可成为明确 gate 阻断原因 | 双向 workbook refs、coverage policy、owner/migration gate 完整闭环 |
| Change event、cursor、webhook 与对账 | 10 | 80% | 8.00 | 无语义变化 | abandoned control 事件/outbox、signed webhook、DLQ/重试、retention/compaction、乱序 fault harness |
| 工业运行控制 | 10 | 52% | 5.20 | DB row lock；跨 repository restart；双实例 cancel；并发 command race；old owner/epoch fencing；raw fence absence；Spring wiring | 外部 HA DB 多进程实测、lease metrics/SLO、quota、DR/容量、clock-skew policy、自动 recovery sweeper |
| **合计** | **100** |  | **80.175** |  | **加权差距 19.825%** |

Round 8 结论：差距从 `20.90%` 降至 `19.825%`。`RUN-01` 中“active control state 只能留在单进程”的病根
已经关闭，但不能据此声称运行可无损续跑。线程栈和外部事务不能持久化，当前正确恢复是 quarantine，而不是接管执行。
剩余 P0 收敛为两条：deadline remaining-budget 还未贯穿 BLOGE/runtime binding；外部写仍缺 commit receipt 与
reconciliation。与此同时，abandoned control 还没有自动生成 run evidence/outbox，属于下一轮必须补齐的证据链缺口。

## 2.9 Round 9 重新审计

Round 8 只证明“owner failure fact 不丢”，没有证明“治理证据一定出现”。Round 9 在执行前提交脱敏
`VisualRunRecoveryReservation`，确定性绑定 requestId→runId、draft/scope/context fingerprint；正常完成和自动
sweeper 对同一 reservation 加行锁。owner abandonment、terminal control 后 evidence 提交中断、control row 未创建
三种断点都会形成带 recovery provenance 的签名记录，并与 reservation 终态和 integration outbox 同事务提交。

| 维度 | 权重 | 当前完成率 | 得分 | 本轮可证明增量 | 仍未证明 |
|---|---:|---:|---:|---|---|
| 协议、版本与身份边界 | 15 | 97% | 14.55 | `VisualGraphRunRecord.v7`、`runEvidenceBundle.v5` 和正式 JSON Schema；capability 保留 v1-v4 并声明 reservation/recovery/outbox | visual control endpoint 企业 IAM scope；自动 N/N-1 consumer matrix；动态 IAM/mTLS 联调 |
| GraphDraft 依赖快照 | 10 | 60% | 6.00 | recovery reservation 固化 stripped draft 与 scope，但未改变 export 依赖完整度 | runtime binding/suite refs、跨表一致快照、完整 readiness profile |
| Run evidence 可信链 | 20 | 94% | 18.80 | owner/process failure 自动形成签名、可验、fail-closed evidence；reservation fingerprint/control revision/attempt 纳入 seal；正常与恢复 exactly-once | KMS/HSM、retention/legal hold、commit receipt、外部 verifier matrix |
| Payload replay | 15 | 80% | 12.00 | 恢复 evidence 明确缺失 payload，不允许伪装成 replay-ready | shadow/live 审批隔离、跨实例 exactly-once、选择性 retention |
| Timeout/partial failure 语义 | 10 | 95% | 9.50 | abandonment/terminal gap/control missing 均有明确 recovery reason 和 quarantine | remaining-budget 向 node/HTTP 传播；disconnect policy；unknown commit reconciliation |
| Workbook、gate feedback 与 Deep Link | 10 | 72% | 7.20 | `RUN_ABANDONED` 与 recovery mode 可直接成为 workbook/gate 阻断条件 | 双向 workbook refs、coverage policy、owner/migration gate 完整闭环 |
| Change event、cursor、webhook 与对账 | 10 | 85% | 8.50 | `RUN_ABANDONED/RUN_EVIDENCE_RECOVERED` 与 run record 同事务进入既有 cursor/reconciliation 流 | signed webhook、DLQ/退避、retention/compaction、乱序 fault harness |
| 工业运行控制 | 10 | 62% | 6.20 | bounded scheduled sweep、normal/recovery 行锁单赢家、并发 reservation、跨服务重启、outbox 失败全回滚后重试、Spring wiring | 外部 HA DB 多进程实测、recovery metrics/SLO、退避/DLQ、quota、DR/容量、clock-skew、residency |
| **合计** | **100** |  | **82.75** |  | **加权差距 17.25%** |

Round 9 结论：差距从 `19.825%` 降至 `17.25%`，`EVD-02` 关闭。这里关闭的是“崩溃事实无法进入治理链”，
不是“崩溃执行可以续跑”或“外部写结果已知”。自动恢复记录保持签名可验证，但只要精确 payload、终止确认或
commit receipt 缺失，manifest 仍为 `QUARANTINED`。剩余 P0 是 IAM 动态生命周期、KMS custody、remaining-budget
传播和副作用 commit/reconciliation；工业化还缺 scheduler telemetry、backoff/DLQ、retention/residency 和外部 HA DB 演练。

## 2.10 Round 10 重新审计

Round 9 的 graph deadline 仍停留在 control plane：operator 只能被线程中断，无法把“这次 run 还剩多少业务时间”
继续传给 retry、HTTP 或 remote worker。Round 10 在 BLOGE core 建立共享 `ExecutionBudget`，把绝对 deadline、
evidence finalization reserve 和只减不增的 remaining budget 变成执行期合同；Resource Gateway 在 owner 启动前完成
绑定，并通过 capability 对集成方显式声明传播范围。

| 维度 | 权重 | 当前完成率 | 得分 | 本轮可证明增量 | 仍未证明 |
|---|---:|---:|---:|---|---|
| 协议、版本与身份边界 | 15 | 98% | 14.70 | capability 新增 OperatorContext、admission、retry、HTTP、remote-worker 五个独立 feature flag | visual control endpoint 企业 IAM scope；自动 N/N-1 consumer matrix；动态 IAM/mTLS 联调 |
| GraphDraft 依赖快照 | 10 | 60% | 6.00 | 无语义变化 | runtime binding/suite refs、跨表一致快照、完整 readiness profile |
| Run evidence 可信链 | 20 | 94% | 18.80 | deadline admission 会形成结构化 `DEADLINE_EXHAUSTED` node fact，但 reserve 尚未成为独立 evidence 字段 | KMS/HSM、retention/legal hold、commit receipt、外部 verifier matrix |
| Payload replay | 15 | 80% | 12.00 | 无语义变化 | shadow/live 审批隔离、跨实例 exactly-once、选择性 retention |
| Timeout/partial failure 语义 | 10 | 99% | 9.90 | deadline→GraphContext→OperatorContext；admission fail closed；timeout/retry/suspend cap；HTTP header；remote worker budget；clock rollback 不放宽 | detach policy；任意自定义 binding 的协作合规；unknown commit reconciliation |
| Workbook、gate feedback 与 Deep Link | 10 | 72% | 7.20 | `DEADLINE_EXHAUSTED + ENGINE_ADMISSION` 可直接成为 workbook/gate 原因 | 双向 workbook refs、coverage policy、owner/migration gate 完整闭环 |
| Change event、cursor、webhook 与对账 | 10 | 85% | 8.50 | 无语义变化 | signed webhook、DLQ/退避、retention/compaction、乱序 fault harness |
| 工业运行控制 | 10 | 68% | 6.80 | finalization reserve；预算不可扩张；wall-clock 回拨不增加预算；跨 scheduler/retry/HTTP/remote 边界测试 | 外部 HA DB 多进程实测、metrics/SLO、quota、DR/容量、residency、detach fault injection |
| **合计** | **100** |  | **83.90** |  | **加权差距 16.10%** |

Round 10 结论：差距从 `17.25%` 降至 `16.10%`。`RUN-01` 的核心“remaining budget 不出 control plane”病根已
关闭，但该编号仍保留为较窄的 P0：客户端断开没有显式 detach 语义，且私有 runtime binding 可以忽略
`OperatorContext` 后发起不可取消 I/O。`RUN-02`、`IAM-01`、`OPS-01` 没有因本轮工作获得虚假完成度。

## 3. 已通过的证明

### 3.1 协议与隔离

- `/api/integration/capabilities` 返回稳定 envelope 和真实 feature flags。
- draft export 显式携带 `operatorRef -> operatorLibraryId`、operator/schema fingerprint。
- run evidence/replay 在 tenant 或 environment 不匹配时统一返回 404，避免授权范围探测。
- 缺失身份上下文返回稳定 `IntegrationProblem`，包含 code、status、retryable 和 correlationId。

### 3.2 Evidence 与 payload

- `VisualGraphRunRecord.v7` 持久化 draft/operator fingerprint、sanitized context/output/node results、edge snapshot、精确 node attempts、结构化 node execution facts、run-control termination fact 和 recovery provenance。
- sanitizer 对 secret/token/authorization/cookie/PII 类键及 Bearer/Basic/labeled credential 内容执行有界递归脱敏并记录 manifest。
- evidence manifest 校验每个 invoked node 的 input、成功节点 output、node/edge status 和持久 seal；缺口或验签失败进入 `QUARANTINED`。
- `DatabaseVisualEvidenceSigner` 持久化 Ed25519 key history；旧 run 在 repository 重建后仍可验证，consumer 可通过公开 verification key 离线验签。
- GET replay 返回 `RECORDED + SANITIZED` payload；POST recorded replay 生成独立签名 run/evidence，明确 `externalInvocationCount=0` 和 `sideEffectPolicy=DENY`。

### 3.3 Governance 与 Deep Link

- gate result 绑定 `draftId + revision + draftFingerprint`，相同 id/内容幂等，不同内容冲突。
- Author read model 明确返回 `CURRENT/STALE/EXPIRED/MISSING`，草稿修订后旧结果立即变成 `STALE`。
- `/author/?draftId=&nodeId=&operatorRef=&runId=&gateIssueId=` 可恢复存量草稿、自动布局并定位上下文。
- 门禁问题可以从 `targetPath/deepLink` 聚焦节点，失效目标显示 warning。
- 真实浏览器发现并修复 React Strict Mode 首次加载被取消后无法重试的问题。

### 3.4 回归范围

```text
mvn -f resource-gateway-examples/pom.xml clean verify
Tests run: 1471, Failures: 0, Errors: 0, Skipped: 2
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

### 3.6 受信身份与访问审计

- Spring HTTP 入口使用 `IntegrationRequestAuthenticator`；缺失/无效 credential 返回 401 和标准 Bearer challenge，不能靠补齐 `X-Tenant-Id` 等 header 绕过。
- `StaticBearerIntegrationIdentityResolver` 对 credential 做 SHA-256 后的常量时间比较，过期、停用或不匹配均不返回 identity；原始 token 不写数据库、不写 problem、不写 audit。
- tenant、organization、project、environment、region、actor 和 delegatedBy 全部来自 resolver；客户端同名 header 可省略，存在且冲突时返回 403，但 problem 不回显受信值。
- identity purpose allowlist 与 endpoint operation allowlist 都必须通过；`CHANGE_SYNC` 不能发起 replay，evidence ingestion 不能写 gate result。
- `DatabaseIntegrationAccessAuditRepository` 追加 ALLOWED/DENIED 决定及 reason code，repository 重建后仍可读取；当前仍缺审计导出、保留、legal hold 和 SIEM 投递。
- capability 显式返回 provider type、claims source、available 和 demo mode；关闭 demo 且没有替代 resolver 时受保护接口 fail closed。
- 图源和 SVG 均通过 Draw.io 结构检查：`0 errors / 0 warnings / 0 crossings / 0 overlaps`，并完成 PNG 视觉检查。

本轮聚焦证明：

```text
29 focused Java tests passed
real Spring HTTP 401 / 200 / 403,
server-owned claims, purpose escalation denial, claim-hint mismatch,
expired/disabled/unavailable identity, credential-free audit restart,
audit-store failure -> retryable 503 fail closed,
controller/service capability contract
```

真实 jar HTTP 证明（端口 `18082`，验证后已停止）：

```text
GET capabilities -> provider=STATIC_BEARER_REGISTRY, claimsSource=SERVER_REGISTRY,
                    trustedWorkloadIdentity=true, demoIdentityMode=true
self-asserted identity headers without credential -> 401 + WWW-Authenticate: Bearer
verified demo credential + CHANGE_SYNC -> 200, context tenant-a/prod
verified credential + conflicting X-Tenant-Id=tenant-b -> 403 IDENTITY_CLAIM_MISMATCH
verified credential + CHANGE_SYNC on run evidence -> 403 PURPOSE_FORBIDDEN
invalid credential -> 401 AUTHENTICATION_FAILED + Bearer challenge
```

### 3.7 Signed workload JWT、轮换与撤销

- `SignedJwtIntegrationIdentityResolver` 只接受 `RS256` 和 `EdDSA`；RSA key 少于 2048 bit、`alg=none`、header algorithm 与 key 类型不一致、未知 `kid`、签名篡改和重复 JSON 字段均被拒绝。
- `iss`、`aud`、`sub`、`jti`、`iat`、`exp` 与完整 tenant/org/project/environment/region/actor/purpose claims 是强制合同；`nbf` 可选，默认等于 `iat`。默认 clock skew 30 秒、最大 lifetime 900 秒，Bearer 总长度上限 4096。
- `ConfiguredIntegrationJwtTrustStore` 可以同时装载最多 32 把 public key。旧、新 `kid` 重叠时两类 token 均可验证；key 可按 enabled/notBefore/expiresAt/revoked 控制，token 可按 `jti` 撤销。
- `IntegrationJwtTrustStore` 是动态 SPI，resolver 每次请求都查询 key 和 token revoke 状态；配置型实现只在启动时载入，企业可替换为 JWKS/KMS adapter，而无需改 controller/service。
- allow/deny audit 保存 `kid/jti`，数据库在已有 Round 4 表上用 `ADD COLUMN IF NOT EXISTS` 平滑升级；原始 JWT、签名和 public/private key 内容不写审计。
- capability 新增 `signedWorkloadJwt`、`credentialRotation`、`credentialRevocation`，provider properties 返回 accepted algorithms、trusted/active/revoked key count、revoked token count、TTL 与 skew，不暴露 key 内容。

本轮聚焦证明：

```text
22 Java tests passed
RS256 + EdDSA happy paths, two-key overlap rotation,
tampered signature, alg confusion/none, wrong issuer/audience/kid,
expired/future/overlong token, weak RSA, duplicate JSON,
key/jti revocation, invalid delegation/scope/purpose,
credential-free audit migration/restart and demo compatibility
```

全量验证：

```text
mvn -f resource-gateway-examples/pom.xml clean verify
Tests run: 1480, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS (04:47)
```

验证过程没有隐藏失败。第一次全量运行暴露 `VisualAuthoringBrowserDomTest` 在 React union branch 重渲染后继续使用旧 `WebElement`，产生 stale reference；调用点改为已有的 `WebDriverWait + By` 重定位 helper 后，目标浏览器用例连续 3 次通过。第二次全量运行暴露新签名测试只修改 Base64URL 尾字符，可能只改变未使用 bit、解码后签名字节不变；测试改为解码后翻转真实签名字节。第三次全量运行 1479 项全部通过。最终安全审计又补充 mixed audience/header/time-window 负向测试，并发现、修复 audience 首个匹配后提前返回的问题；最终第四次全量 1480 项全部通过。这些修复没有放宽产品断言。

真实 jar HTTP 证明（端口 `18083`，OpenSSL 临时 RSA-2048 key，验证后 key 已删除且服务已优雅停止）：

```text
GET capabilities -> 200, provider=SIGNED_JWT, claimsSource=VERIFIED_TOKEN,
                    demoMode=false, activeKeyCount=1, revokedTokenCount=1
valid RS256 JWT + CHANGE_SYNC -> 200, reconciliation scope tenant-a/prod
same trusted key + wrong audience -> 401 AUTHENTICATION_FAILED
trusted key + revoked jti -> 401 AUTHENTICATION_FAILED
trusted claims + byte-level tampered signature -> 401 AUTHENTICATION_FAILED
```

### 3.8 运行失败事实链

- `NodeExecutionCaptureInterceptor` 同时实现 `OperatorInterceptor + ExecutionListener`，用 capture id 绑定同一 run
  的 exact input/output attempt 与引擎 resilience event。
- retry callback 的终态失败不再被算成额外执行：`configuredMaxAttempts=3`、实际调用 3 次时，
  `observedAttempts=3`，事件为两次 `RETRY_SCHEDULED`、一次 `RETRY_EXHAUSTED`。
- fallback 成功节点同时保留 `status=FALLBACK`、`reasonCode=FALLBACK_SUCCEEDED` 和
  `retry.exhausted=true`，不再用成功结果掩盖前置失败。
- timeout 由 `onNodeTimeout` 事件确认，不检查 exception class/message 子串；配置预算和 observed flag 分开保存。
- `SKIPPED/CANCELLED` 明确标注 `TOPOLOGY_DERIVATION`；取消节点无需伪造 input，edge 在未传播时不生成 payload ref。
- 图级 `PARTIAL` 只在关键输出已到达且存在独立降级时产生；关键输出超时为 `TIMEOUT`。
- 同名图并发且无 execution context 的 resilience callback 出现多个候选时，两个 run 都标记
  `ENGINE_STATUS_WITH_EVENT_GAP`，manifest 的 fact coverage 不通过。
- operator effect/idempotency 被固化到 run node snapshot；缺少 commit receipt 的外部副作用输出
  `UNKNOWN_COMMIT`，不会被误写为 `NOT_COMMITTED`。

本轮聚焦证明：

```text
17 Java tests passed
engine retry/fallback/timeout events, terminal retry counting,
critical-output partial aggregation, cancellation cause chain,
edge propagation, concurrent correlation ambiguity, signed fact coverage,
serialized DTO vs JSON Schema drift

52 Java tests passed
dynamic runner, visual run service, integration service/controller,
run repository restart, history controller and v5 JSON persistence

Draw.io validation: 0 errors, 0 warnings, 0 crossings/overlaps
PNG visual inspection passed; SVG references editable embedded source
```

全量与真实进程验证：

```text
mvn -f resource-gateway-examples/pom.xml clean verify
Tests run: 1486, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS (05:21)

real jar :18084
capability -> runEvidence v1/v2/v3; structuredExecutionFacts=true;
              graphDeadline=false; userRunCancellation=false;
              sideEffectCommitConfirmation=false
import decision-table DSL -> scoped transient run SUCCESS
run response -> nodeExecutionFacts.decision = ENGINE_STATUS / SUCCESS / NONE
evidence -> v3, criticalOutputReached=true, facts=1/1,
            READY + complete=true + signature VERIFIED
AJV draft-2020-12 validation of the real evidence payload -> valid
graceful shutdown completed
```

边界说明：BLOGE 当前 timeout/fallback listener 旧签名没有 `OperatorContext`。Gateway 已用 invocation scope 和
唯一 active candidate 关联，并在歧义时 fail closed；根治方案仍应在 BLOGE 演进中把 executionId/context 加入
所有 resilience event。图级 absolute deadline、用户 cancel/fencing 和双条件终止确认已实现；Round 10 又补齐
budget 对 scheduler、OperatorContext、HTTP、remote worker 和 retry/timeout 的传播，但 disconnect policy、hard kill
和 operator commit receipt 仍未实现。Round 8 已把 control state、跨实例 cancel
和 owner lease/epoch 持久化，但恢复策略是 abandonment + quarantine，不把它夸大为崩溃后自动续跑。

### 3.9 Run control 与真实页面证明

本轮新增证明不是只验证 DTO。聚焦测试覆盖 deadline、用户取消、错误 fence、过期 revision、不合作算子和 HTTP
映射；扩大回归覆盖 dynamic runner、visual service/controller、repository/evidence 和静态页面合同；最后以真实 jar、
真实浏览器和正式 JSON Schema 三条路径交叉验证。

```text
24 focused Java tests passed
114 expanded run-control/integration/static-UI regression tests passed

mvn -f resource-gateway-examples/pom.xml clean verify
Tests run: 1494, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS (04:51)

real jar :18085 + real browser
Custom Composer managed run -> SUCCEEDED
terminationConfirmed=true; sideEffectsMayBeInFlight=false
run record -> VisualGraphRunRecord.v6
evidence -> toolStudio.resourceGateway.runEvidenceBundle.v4
AJV draft-2020-12 validation of real evidence payload -> valid
capability -> graphDeadline/userRunCancellation/runTerminationConfirmation=true
              hardRunTermination/durableRunControl/sideEffectCommitConfirmation=false
desktop 1280x720 + mobile 390x844 visual inspection passed
mobile scrollWidth=clientWidth=390; browser console errors=0

Draw.io validation: 0 errors, 0 warnings, 0 crossings/overlaps
SVG visual inspection passed; editable corporate source retained
```

真实 evidence 的 `execution.runControl` 包含 request/engine execution id、revision、绝对 deadline、终态时间和双条件
终止结论。真实 integration 请求必须使用受信 Bearer 与 `GOVERNANCE_EVIDENCE_INGESTION` purpose；作用域不匹配的
首次请求按设计得到 404，调整 authoring scope 到受信 identity 的 `tenant-a/prod` 后才可读取，这同时证明运行证据
没有越过租户/环境边界。

### 3.10 Durable control 故障证明

Round 8 用共享 H2 数据源创建两个独立 repository/service 实例，验证数据库而非 JVM map 才是状态权威源。
测试覆盖 repository 重建、跨实例 cancel、并发 cancel race、cancel-before-start、owner/epoch 越权、lease expiry、
abandonment retention 和 fence secret hygiene。

```text
45 focused tests passed
  9 database run-control state-machine tests
  18 dynamic composer executions, including 10 repeated cross-instance cancellations
  13 integration capability/evidence tests
  5 Spring application wiring tests

repository restart -> RUNNING state + owner epoch + revision retained
instance B cancel -> instance A observes durable command -> local threads interrupted -> CANCELLED
two concurrent cancel commands -> exactly one accepted transition
cancel-before-start -> late owner start cannot overwrite CANCEL_REQUESTED
lease expired -> OWNER_LEASE_EXPIRED + TERMINATION_UNCONFIRMED + ABANDONED
old owner/epoch -> cannot mutate abandonment or another owner's row
raw fencing token -> absent from dynamic_run_controls
purge -> never deletes unconfirmed abandonment evidence

mvn -f resource-gateway-examples/pom.xml clean verify
Tests run: 1513, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS (05:45)

Draw.io validation: 0 errors, 0 warnings, 0 crossings/overlaps
SVG visual inspection passed; durable authority and JVM-local handles are visually separated
```

全量回归先后暴露两个只有在调度压力下容易命中的窗口：owner 已退出而 operator `finally` 尚未 drain 时，
`completion.isDone()` 和 `completion.get()` 两条返回路径都可能提前返回 transient
`TERMINATION_UNCONFIRMED`。修复后，两条路径都会在 cancellation grace 内重新读取 durable control；只有 operator
仍未退出时才在 grace 后返回隔离结论。该竞态被保留为 10 次重复的跨实例测试，而不是通过放宽预期隐藏。
全量回归还暴露了浏览器测试在 React 重渲染后持有不可交互旧元素的问题；测试 helper 现在重新定位活动元素并
重试 stale/not-interactable 状态，目标大图 connectability 用例与最终全量均通过。

这里的 `durableRunControl=true` 精确表示：状态、命令和 owner failure 结论可跨 repository/实例生存，并可通过共享
数据库协调；它不表示 Java continuation 可迁移，也不表示崩溃后的业务执行可自动 resume。对应 capability 将
`restartRunResumption=false` 单独暴露。

### 3.11 Evidence recovery 原子性与故障证明

Round 9 的证明对象是 request thread 死亡后的证据连续性，而不是 DTO shape。reservation 在执行前保存去 fixture
的 draft、tenant/environment、脱敏 context、确定性 runId 和 material fingerprint；sweeper 只对 durable control
已经 abandonment、terminal evidence gap 或超过 grace 仍缺 control 的 reservation 动作。

```text
11 focused recovery fault tests passed
41 recovery/boundary/authoring focused tests passed
1 targeted real-Chrome revision-conflict flow passed
1524 full tests passed; 0 failures, 0 errors, 2 skipped

owner lease expiry -> OWNER_ABANDONED recovery -> signed QUARANTINED evidence
terminal control + no evidence after grace -> TERMINAL_EVIDENCE_GAP
reservation + no control after grace -> CONTROL_MISSING
normal completion vs recovery -> reservation row lock -> exactly one run record
two concurrent sweepers -> exactly one record + one outbox event
two concurrent equivalent reservations -> same deterministic runId + one row
same requestId + different material -> rejected
validation blocked before control claim -> reservation consumed, no later synthetic duplicate
service/repository restart -> pending reservation recovered once
outbox append failure -> run record + reservation transition roll back; later retry succeeds
context secret -> redacted before reservation persistence

run record -> VisualGraphRunRecord.v7
evidence -> toolStudio.resourceGateway.runEvidenceBundle.v5
recovery event -> RUN_ABANDONED / RUN_EVIDENCE_RECOVERED
JSON Schema v5 -> valid JSON document + serialized-field contract test
Draw.io -> 0 errors, 0 warnings, 0 crossings, 0 overlaps
```

签名只证明恢复记录自创建后未被篡改，不证明丢失的 node payload 或远端 commit outcome。恢复 factory 对 draft
节点统一输出 `DURABLE_CONTROL_RECOVERY` 来源和保守的 `PARTIAL/UNKNOWN_COMMIT`，manifest 因 capture 或
termination gap 保持 `QUARANTINED`。ANEKE 可以可靠地消费“发生过不可确认运行”这一事实，但不能把它用于通过发布门禁。

第一次 Round 9 全量回归暴露了两类不能以“偶发”处理的问题。其一，recovery service 直接导入 gateway
`DynamicRunControlRepository/View`，违反 D18 visual package 边界；修复后 visual 只依赖
`VisualRunControlRecoveryPort`，候选查询和模型转换由 `DynamicRunControlRecoveryAdapter` 承担，边界测试通过。
其二，operator library 导入在首个异步校验前仍显示上一次成功状态，真实 Chrome 会把旧状态误判为本次完成；现在
点击后同步显示 `Validating operator library...`，消除用户和自动化共同可见的竞态，原失败用例及全量浏览器回归均通过。

### 3.12 Execution budget 的跨层证明

本轮验证覆盖的不是一个孤立 `Duration` 字段，而是从 Resource Gateway intent 到 BLOGE runtime binding 的完整收窄链：

```text
RunIntent.deadlineAt
  -> root GraphContext.ExecutionBudget(deadline, finalizationReserve)
  -> scoped / nested GraphContext (shared ceiling; cannot widen)
  -> scheduler admission + resilience timeout/retry/suspend
  -> OperatorContext.deadlineAt / remainingBudget / capTimeout
  -> HttpResourceOperator / common HttpRequestOperator / RemoteWorkerEnvelope

BLOGE reactor:
  core 1909 passed
  crypto functions 18 passed
  DSL 1555 passed, 1 skipped
  common operators 108 passed
  total 3590 passed, 0 failures, 0 errors, 1 skipped

Resource Gateway focused + Spring wiring:
  56 passed, 0 failures, 0 errors, 0 skipped

Resource Gateway full verify (real Chrome included):
  1527 passed, 0 failures, 0 errors, 2 skipped
  BUILD SUCCESS (04:56)

Corporate Draw.io:
  0 errors, 0 warnings, 0 crossings, 0 overlaps
  SVG visual inspection passed
```

故障断言包括：reserve 被业务预算扣除；时钟向后校正不能增加预算；预算耗尽时 operator invocation 为零；
retry backoff 放不进剩余窗口时不再 retry；20 秒 HTTP 配置在 5 秒 deadline/1 秒 reserve 下收紧为 4 秒；common
HTTP 真实 server 收到 deadline/budget headers；remote envelope 可序列化预算并按 worker clock/skew 判定过期。
Resource Gateway 还证明 admission skip 被采集为 `CANCELLED + DEADLINE_EXHAUSTED + ENGINE_ADMISSION`，而不是
退化为 `UNKNOWN`。

## 4. 当前 P0 阻断项

| ID | 阻断项 | 病根 | 根治验收 |
|---|---|---|---|
| `IAM-01` | 企业身份生命周期尚未端到端证明 | signed JWT、轮换和撤销代码已具备，但配置 trust store 只在启动时装载，尚无客户 IAM 动态策略/撤销传播证据 | 动态 JWKS/KMS 或 mTLS adapter + 多 identity/group/clearance + delegation grant + propagation SLO + policy/audit 联调演练 |
| `OPS-01` | 本地签名 key 不满足企业 custody | private key 由本地 H2 demo provider 保存 | KMS/HSM-backed `VisualEvidenceSigner`、rotation/disable/revoke、审计和灾备演练 |
| `RUN-01` | disconnect/detach 与任意 binding 的预算合规尚未封口 | Round 10 已完成 OperatorContext、scheduler admission、retry/timeout/suspend、Resource Gateway/common HTTP 和 remote worker 的 remaining-budget 传播，并防止 wall-clock 回拨放宽预算；但客户端断开语义未版本化，私有 binding 仍可忽略合同 | versioned `detachPolicy` + disconnect race/fault tests + runtime binding conformance suite + non-cooperative I/O quarantine |
| `RUN-02` | 外部副作用 timeout 后只能标记 unknown commit | operator 没有 commit receipt/reconciliation hook，盲重试可能重复写 | effect/idempotency admission + transaction/idempotency receipt + reconcile/compensate hook + downstream-write suspension + gate policy |

`EVD-02` 已在 Round 9 关闭；`EVT-01` 已在 Round 3 对当前资产模型关闭。webhook、recovery retry
backoff/DLQ、retention 和投递指标仍为 Stage 4 的 P1/工业化差距，但不再构成“崩溃事实无法进入治理链”或
“事件丢失后无法对账”的 P0 病根。

## 5. 迭代记录

| Round | 目标 | 结果 | 加权差距 | 证据 |
|---|---|---|---:|---|
| 0 | 建立可审计基线 | Stage 1 主体、Stage 2 雏形 | 72.0% | commit `876afa9f`、1433 tests |
| 1 | 可信 evidence + gate/Deep Link 闭环 | evidence 可隔离/签名/离线验，作者可从 ANEKE 问题直达节点 | 53.25% | commit `1fd6b889` + frontend working tree、1439 Java tests、51 frontend tests、desktop/mobile browser |
| 2 | recorded replay command | 新 replay run/evidence、parent lineage、四类 case、path/schema/error/governance 断言、零外部调用、幂等 | 43.20% | 1443 Java tests、132 frontend tests、H2 restart、signed replay evidence、desktop/mobile browser |
| 3 | transactional outbox + cursor + reconciliation | 资产/事件原子提交、稳定分页、作用域签名 cursor、过期重建、不可变 revision ref、持续反熵 | 32.95% | 1461 Java tests、132 frontend tests、真实 jar HTTP 多租户闭环、Draw.io 0 errors/warnings |
| 4 | trusted workload identity + purpose policy | 服务端 claims、operation/purpose 双 allowlist、hint conflict、401/403、credential-free audit、provider capability | 30.50% | 1471 Java tests、29 个聚焦 tests、真实 jar HTTP 401/200/403、identity Draw.io 0 errors/warnings |
| 5 | signed workload JWT + rotation/revocation | RS256/EdDSA、严格 claims/time/algorithm 校验、多 kid 轮换、key/jti 撤销、kid/jti audit、signed provider capability | 29.20% | 1480 Java tests、22 个集成聚焦/18 个最终安全聚焦 tests、真实 jar HTTP 200/401、identity Draw.io 0 errors/warnings |
| 6 | engine-observed failure semantics | retry/timeout/fallback 真事件、skip/cancel 因果、关键输出 PARTIAL、edge propagation、unknown commit、fact coverage quarantine | 23.85% | 1486 全量 tests、17 个聚焦/52 个运行边界 tests、真实 jar v3 + AJV schema、Draw.io 0 errors/warnings |
| 7 | graph deadline、fenced cancel 与终止确认 | versioned intent/control、绝对 deadline、fence/revision、owner+operator 双条件确认、不合作算子 quarantine、UI deadline/stop、evidence v4 | 20.90% | 1494 全量 tests、24 个聚焦/114 个扩展回归 tests、真实浏览器 desktop/mobile、真实 jar v4 + AJV schema、Draw.io 0 errors/warnings |
| 8 | durable run control 与 owner failure 语义 | DB 权威状态、跨实例 cancel、lease/epoch fencing、并发单赢家、cancel-before-start、过期 abandonment、raw fence 不落库 | 19.825% | 45 个聚焦 tests、1513 个全量 tests、跨 repository restart/双实例/race tests、真实浏览器回归、Draw.io 0 errors/warnings |
| 9 | owner failure 后 evidence continuity | pre-run 脱敏 lineage reservation、normal/recovery 单赢家、三类 synthetic recovery、签名 evidence v5、事务 outbox、失败回滚重试、visual-owned recovery port | 17.25% | 11 个 recovery fault tests、41 个聚焦 tests、1 个目标真实 Chrome 流程、1524 个全量 tests、v5 schema、restart/concurrency/outbox rollback、Draw.io 0 errors/warnings |
| 10 | deadline remaining-budget 跨层传播 | `ExecutionBudget`、finalization reserve、不可扩张/防时钟回拨、scheduler admission、retry/timeout/suspend cap、HTTP headers、remote-worker envelope、结构化 deadline-exhausted fact | 16.10% | BLOGE reactor 3590 tests；Resource Gateway 56 个聚焦/Spring tests、1527 个全量 tests（含真实 Chrome）；Draw.io 0 errors/warnings/crossings/overlaps、SVG 视觉检查通过 |

后续每轮必须更新本表、代码证据、失败测试和剩余阻断项。只有全部 P0 阻断关闭、全量验证与真实浏览器验证通过，并且按同一权重计算的差距 `<3%`，才允许把目标标记完成。
