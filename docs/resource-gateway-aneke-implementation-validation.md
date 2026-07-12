# Resource Gateway x ANEKE 实施验证与差距台账

> 本文只记录可由当前代码、自动化测试、运行结果或可检查制品证明的事实。设计文档中的意图、尚未运行的测试和仅存在的 DTO 均不计为完成。

| 属性 | 内容 |
|---|---|
| 设计基线 | `docs/resource-gateway-aneke-tool-studio-integration-evolution-plan.md` |
| 当前实现基线 | Round 17 完整验证（workbook/gate evidence loop + cross-instance replay） |
| 评估日期 | 2026-07-13 |
| 目标 | 仓库内加权实施差距 `<3%`；客户环境部署门禁单独列账且不得被数值掩盖 |
| 最近全量验证 | `mvn -f resource-gateway-examples/pom.xml -Pfrontend clean verify`：1620 tests，0 failures，0 errors，0 skipped，`BUILD SUCCESS`（05:21，含 34 个真实 Chrome 场景）；frontend production build 通过，`npm audit` 0 vulnerabilities |

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

## 2.11 Round 11 重新审计

Round 10 能判定执行超时，却不能回答外部系统究竟是否已经提交。Round 11 把副作用从一段普通 operator 代码提升为
执行期协议：外部写入前登记 `PREPARED`，结束时只能显式收敛到 `COMMITTED`、`NOT_COMMITTED` 或
`UNKNOWN_COMMIT`；不确定提交会阻止 retry、fallback 和下游传播。重启后的状态确认由带 lease/fence 的持久化对账命令
完成，结果作为独立签名补充证据追加，绝不改写原始 run evidence。

| 维度 | 权重 | 当前完成率 | 得分 | 本轮可证明增量 | 仍未证明 |
|---|---:|---:|---:|---|---|
| 协议、版本与身份边界 | 15 | 98% | 14.70 | run evidence v6；request/record/summary 三份严格 schema；独立 read/execute purpose；capability 揭示 adapter 与全局确认状态 | 自动 N/N-1 consumer matrix；动态 IAM/mTLS 联调；旧 consumer 契约流水线 |
| GraphDraft 依赖快照 | 10 | 60% | 6.00 | 无语义变化 | runtime binding/suite refs、跨表一致快照、完整 readiness profile |
| Run evidence 可信链 | 20 | 98% | 19.60 | attempt journal、脱敏 lookup ref、commit receipt/proof、不可变 attempt fingerprint；对账记录独立签名并绑定 base evidence | KMS/HSM、retention/legal hold、外部 verifier matrix；全算子覆盖率 |
| Payload replay | 15 | 80% | 12.00 | unknown commit 不再进入 recorded replay 的可采纳路径 | shadow/live 审批隔离、跨实例 exactly-once、选择性 retention |
| Timeout/partial failure 语义 | 10 | 100% | 10.00 | PREPARED/UNKNOWN/PARTIAL；未知提交不重试、不 fallback、不传播；legacy 无 attempt 的 unknown 仍隔离 | 所有私有 binding 的协议采纳仍需 conformance 证明 |
| Workbook、gate feedback 与 Deep Link | 10 | 73% | 7.30 | verified reconciliation summary 可供 gate 区分原始 quarantine 与补充证据后的 READY | 双向 workbook refs、coverage policy、owner/migration gate 完整闭环 |
| Change event、cursor、webhook 与对账 | 10 | 88% | 8.80 | 对账结果、resolved head 与 `SIDE_EFFECT_RECONCILED` outbox 同事务；request 幂等、claim lease/fence、跨实例冲突语义 | signed webhook、DLQ/退避、retention/compaction、乱序 fault harness |
| 工业运行控制 | 10 | 74% | 7.40 | DB 重启、双实例 claim、lease takeover、旧 owner fencing、outbox 回滚、持久化篡改检测、provider timeout/error fail closed | 外部 HA DB 实测、provider adapter 灾难演练、metrics/SLO、quota、DR/容量、residency |
| **合计** | **100** |  | **85.80** |  | **加权差距 14.20%** |

Round 11 结论：差距从 `16.10%` 降至 `14.20%`。`RUN-02` 的引擎与协议病根已关闭，但不能据此宣称所有业务写
都可对账：默认 adapter registry 为空，通用 HTTP 与客户私有 operator 尚未被强制纳入 journal。capability 因而诚实地
保持 `sideEffectCommitConfirmation=false`，并把 adapter 可用性单独暴露。下一步应以 binding conformance、重点
provider adapter 和故障演练关闭“协议存在但业务绕开协议”的组织性缺口。

## 2.12 Round 12 重新审计

Round 11 的 journal 是一条可用协议，但只要 operator/library/runtime binding/底层 HTTP 任一层可以绕开，它就仍是
“推荐实践”，不是工程不变量。Round 12 将副作用一致性前移到 authoring contract，并在 binding、activation、operator
invocation 和 HTTP send 四个边界逐层 fail closed；同时把同一状态投影到 `/author/`，让作者在拖入节点前就能看见。

| 维度 | 权重 | 当前完成率 | 得分 | 本轮可证明增量 | 仍未证明 |
|---|---:|---:|---:|---|---|
| 协议、版本与身份边界 | 15 | 99% | 14.85 | `bloge.sideEffectProtocol.v1`、`resourceGateway.externalWriteContract.v1`、capability object/feature flags；合同进入 operator fingerprint | 自动 N/N-1 consumer matrix；动态 IAM/mTLS 联调；旧 consumer 契约流水线 |
| GraphDraft 依赖快照 | 10 | 64% | 6.40 | managed protocol 变化会改变 operator fingerprint；draft readiness 可导出 side-effect conformance requirement | suite/runtime binding refs 的一致性快照；完整 readiness profile；跨表 snapshot transaction |
| Run evidence 可信链 | 20 | 99% | 19.80 | 明确 WRITE 在调用前验合同、返回后验 journal adoption；descriptor/common HTTP mutation 不能绕开 PREPARED/receipt/UNKNOWN 事实 | KMS/HSM、retention/legal hold、外部 verifier matrix；错误 effect 分类和非 HTTP 私有写覆盖 |
| Payload replay | 15 | 80% | 12.00 | 无语义变化；既有 recorded replay 仍为 DENY side-effect | shadow/live 审批隔离、跨实例 exactly-once、选择性 retention |
| Timeout/partial failure 语义 | 10 | 100% | 10.00 | 缺 receipt 成为 UNKNOWN；unmanaged HTTP 在网络前失败；managed-but-no-journal 在返回后失败 | 错误分类为 MIXED/READ_ONLY 的私有写；disconnect/detach 与非协作 I/O |
| Workbook、gate feedback 与 Deep Link | 10 | 75% | 7.50 | binding 需要 conformance/fault evidence，activation 需要 reconciler health；Author 显示 managed/required/blocked 原因 | 双向 workbook refs、coverage policy、owner/migration gate 完整闭环 |
| Change event、cursor、webhook 与对账 | 10 | 88% | 8.80 | 无语义变化 | signed webhook、DLQ/退避、retention/compaction、乱序 fault harness |
| 工业运行控制 | 10 | 81% | 8.10 | 通用 HTTP mutation、手写 DSL 低层 HTTP、Java WRITE pre/post guard；binding/activation capability/evidence gate | provider adapter 灾难演练、effect/egress 自动发现、外部 HA DB、metrics/SLO、quota、DR/容量、residency |
| **合计** | **100** |  | **87.45** |  | **加权差距 12.55%** |

Round 12 结论：差距从 `14.20%` 降至 `12.55%`。通用 HTTP 和明确声明 WRITE 的 operator 已不能通过正常执行路径
绕过 journal，因此 `RUN-02` 从“主干可绕过”收窄为“错误 effect 分类、私有非 HTTP 写边界、provider adapter 覆盖和
企业故障演练”。全局 `sideEffectCommitConfirmation` 继续为 false 是正确结果，不应为了分数提前翻转。

## 2.13 Round 13 重新审计

Round 5 已能验证 signed JWT，但信任材料仍在启动时装载，而且 `Optional.empty()` 把坏 token、unknown `kid` 和 IdP
故障混为一谈。Round 13 把身份信任提升为运行期状态机：JWKS 与 revocation feed 原子刷新，unknown `kid` single-flight，
撤销传播 SLO 可探测，authority outage 与 credential invalidity 有稳定不同语义；同时把 group、clearance 和
issuer-attested delegation grant 纳入身份上下文与无凭证审计。

| 维度 | 权重 | 当前完成率 | 得分 | 本轮可证明增量 | 仍未证明 |
|---|---:|---:|---:|---|---|
| 协议、版本与身份边界 | 15 | 99.5% | 14.925 | 动态 JWKS/revocation 协议；`VERIFIED/INVALID/PROVIDER_UNAVAILABLE`；group/clearance/delegation claims；401/503 稳定分流 | 自动 N/N-1 consumer matrix；客户真实 IdP/mTLS 认证；组织策略版本合同 |
| GraphDraft 依赖快照 | 10 | 64% | 6.40 | 无语义变化 | suite/runtime binding refs 的一致性快照；完整 readiness profile；跨表 snapshot transaction |
| Run evidence 可信链 | 20 | 99% | 19.80 | evidence consumer 入口身份可动态轮换/撤销，authority outage 不再污染 invalid-credential 审计 | KMS/HSM、retention/legal hold、外部 verifier matrix；错误 effect 分类和非 HTTP 私有写覆盖 |
| Payload replay | 15 | 80% | 12.00 | replay purpose identity 可携带组织 group/clearance 和正式 delegation grant | shadow/live 审批隔离、跨实例 exactly-once、选择性 retention、resource classification policy |
| Timeout/partial failure 语义 | 10 | 100% | 10.00 | 身份权威 timeout/5xx 进入 retryable 503；畸形/过期文档 hard fail closed | disconnect/detach 与非协作 I/O |
| Workbook、gate feedback 与 Deep Link | 10 | 76% | 7.60 | capability 可供 ANEKE 显示 identity trust state、revocation SLO 和 outage policy | group/clearance 对 workbook/resource classification 的策略执行；双向 workbook refs；owner/migration gate |
| Change event、cursor、webhook 与对账 | 10 | 88% | 8.80 | 无语义变化 | signed webhook、DLQ/退避、retention/compaction、乱序 fault harness |
| 工业运行控制 | 10 | 86% | 8.60 | HTTPS/no-redirect、ETag、256 KB 流式上限、原子快照、并发节流、strict/bounded-stale、authority expiry、真实双服务 outage/rotation/revocation 演练 | 客户 IdP/多地域 JWKS、refresh metrics/alert、group lifecycle/orphan owner、break-glass、外部 HA DB、quota/DR/residency |
| **合计** | **100** |  | **88.125** |  | **加权差距 11.875%** |

Round 13 结论：差距从 `12.55%` 降至 `11.875%`。`IAM-01` 的代码病根已从“静态信任库且故障不可区分”收窄为
“客户组织策略与部署认证尚未证明”。不能仅凭本地模拟 IdP 宣称企业 IAM 完成；下一轮最高权重独立病根转向
`OPS-01` 的 evidence private-key custody，随后再处理 `RUN-01/RUN-02` 的私有实现不可见性。

## 2.14 Round 14 重新审计

Round 13 之后 evidence seal 仍默认由 H2 中的明文 PKCS#8 private key 生成，而且 capability 只有
`evidenceSignature=true/false`，无法区分 demo key、企业 KMS、provider outage 或 custody 失真。Round 14 把这条边界
提升为 managed-signing protocol：Resource Gateway 只持有原子 public-key generation，向 provider 发送 canonical
fingerprint 和 expected keyId，并在持久化前本地反验 provider 返回签名。

| 维度 | 权重 | 当前完成率 | 得分 | 本轮可证明增量 | 仍未证明 |
|---|---:|---:|---:|---|---|
| 协议、版本与身份边界 | 15 | 99.5% | 14.925 | capability 新增 `evidenceSignerDescriptor.v1`；key/sign request/response 三份 provider schema；key lookup 503/404 分流 | 自动 N/N-1 consumer matrix；客户真实 IdP/mTLS/KMS 认证；组织策略版本合同 |
| GraphDraft 依赖快照 | 10 | 64% | 6.40 | 无语义变化 | suite/runtime binding refs 的一致性快照；完整 readiness profile；跨表 snapshot transaction |
| Run evidence 可信链 | 20 | 99.5% | 19.90 | non-exportable managed signer；exactly-one-active；public history；remote signature local verification；disable/revoke；rotation race | retention/legal hold、外部 verifier matrix、可信时间戳；错误 effect 分类和非 HTTP 私有写覆盖；客户 KMS conformance |
| Payload replay | 15 | 80% | 12.00 | replay evidence 可使用同一 managed signer，但 replay 语义无新增 | shadow/live 审批隔离、跨实例 exactly-once、选择性 retention、resource classification policy |
| Timeout/partial failure 语义 | 10 | 100% | 10.00 | signing transport outage、malformed trust、bad signature、snapshot expiry 有不同状态；无本地私钥 fallback | disconnect/detach 与非协作 I/O |
| Workbook、gate feedback 与 Deep Link | 10 | 76% | 7.60 | ANEKE 可由 capability 区分 local demo 与 managed custody，并读取 signer health/key generation | signer readiness 尚未进入 workbook/publish gate policy；双向 workbook refs；owner/migration gate |
| Change event、cursor、webhook 与对账 | 10 | 88% | 8.80 | cursor/reconciliation signer 可复用 managed provider；签名失败不伪造本地 seal | signed webhook、DLQ/退避、retention/compaction、乱序 fault harness |
| 工业运行控制 | 10 | 90% | 9.00 | HTTPS/no-redirect、128 KB 流式上限、duplicate/private material reject、single refresh、bounded public cache、真实双服务 rotation/revoke/outage、H2 private-key table absent | 客户 KMS/HSM identity/policy、provider key-use audit export、持久 public-key cache/retention、多地域 DR、metrics/SLO、外部 HA DB、quota/residency |
| **合计** | **100** |  | **88.625** |  | **加权差距 11.375%** |

Round 14 结论：差距从 `11.875%` 降至 `11.375%`。`OPS-01` 的代码病根已从“生产签名必然把 private key 放进
Resource Gateway 数据库”收窄为“客户 provider 与部署控制尚未认证”。本轮真实 HTTP authority 证明协议生命周期，
但不是任何云厂商或客户 HSM 的合规证明。按剩余加权差距，下一轮应优先处理 `GraphDraft` 跨资产一致性快照和完整
dependency readiness profile，它单项仍贡献 `3.6%` 差距。

## 2.15 Round 15 重新审计

Round 15 没有把“按顺序查五张表”包装成快照，而是引入 relevant-only optimistic two-phase observation。第一次读取只冻结
draft 实际引用的 operator/library/binding/activation/suite，生成 canonical dependency fingerprint；组包后重读同一 draft
和同一相关资产集合。revision/fingerprint 任一漂移都丢弃整个候选包并返回可重试
`409 RG.INTEGRATION.DRAFT_SNAPSHOT_CHANGED`。无关资产变化和 repository iteration order 不影响 fingerprint。

审计同时发现并修复了更严重的最小披露问题：旧检查点通过全局 `catalog.find` 取得当前 operator 后会把完整 snapshot
放进 bundle，即使该 operator 对当前 tenant/namespace/environment 已 scope mismatch。正式实现拆分 current/scoped/export
三个视图：current 只以 digest 参与内部漂移检测；export 只允许 scoped 当前定义或该 draft 自有历史 snapshot；scope
mismatch/catalog missing 都不读取或导出当前 library、binding、activation、suite 详情。

| 维度 | 权重 | 当前完成率 | 得分 | 本轮可证明增量 | 仍未证明 |
|---|---:|---:|---:|---|---|
| 协议、版本与身份边界 | 15 | 99.5% | 14.925 | capability 声明 profile v1/v2 与 snapshot v1；新增两份 machine schema；409 retryable 语义与代码一致 | 自动 N/N-1 consumer matrix；显式响应版本协商；客户真实身份/策略认证 |
| GraphDraft 依赖快照 | 10 | 94% | 9.40 | library revision/version/owner/status/fingerprint、binding/activation revision/env/health、suite revision/case/fingerprint、readiness 状态；相关资产双读防漂移；scope-safe disclosure；确定性导出 | ANEKE 真实 consumer matrix；外部 HA DB 多实例 fault harness；SLA 权威来源；长期 snapshot retention/GC |
| Run evidence 可信链 | 20 | 99.5% | 19.90 | 无语义变化 | retention/legal hold、外部 verifier matrix、可信时间戳、客户 KMS conformance |
| Payload replay | 15 | 80% | 12.00 | 无语义变化 | shadow/live 审批隔离、跨实例 exactly-once、选择性 retention、resource classification policy |
| Timeout/partial failure 语义 | 10 | 100% | 10.00 | snapshot drift 由 typed retryable conflict 表达，不返回 complete-looking partial bundle | disconnect/detach 与非协作 I/O |
| Workbook、gate feedback 与 Deep Link | 10 | 76% | 7.60 | ANEKE 可消费 owner/readiness/suite revision，但 gate/workbook 尚未自动使用 | 双向 workbook refs；readiness/SLA/migration/owner gate policy |
| Change event、cursor、webhook 与对账 | 10 | 88% | 8.80 | dependency fingerprint 可作为同步去重和 impact-analysis 输入 | signed webhook、DLQ/退避、retention/compaction、乱序 fault harness |
| 工业运行控制 | 10 | 90% | 9.00 | relevant-only 读取、无关变更不误冲突、scope mismatch 最小披露、并发 suite revision 漂移 fault test | 外部 HA DB、客户 IAM/KMS、quota/residency、正式 metrics/SLO/DR |
| **合计** | **100** |  | **91.625** |  | **加权差距 8.375%** |

Round 15 结论：GraphDraft 单项差距从 `3.6%` 收敛到 `0.6%`，总差距经完整回归重评后从 `11.375%` 降至 `8.375%`。这里的
`94%` 不是“代码看起来齐了”，而是保留了四项未被当前仓库证明的企业证据：真实 ANEKE 兼容矩阵、多实例 HA DB
故障注入、SLA 权威来源、snapshot retention/GC。下一轮应在剩余大项中优先补 `Payload replay` 的选择性保留与
classification policy，或补 workbook 对 readiness/suite revision 的真正门禁消费；两者都不能只增加 DTO。

## 2.16 Round 16 重新审计

Round 16 首先否决了“在已签名 run JSON 上增加 `expiresAt`”的伪方案。旧模型把 payload 值直接放在不可变
`VisualGraphRunRecord` 中：不删除就违反 retention，改写删除又会破坏 evidence seal。正式实现将生命周期拆开：
v9 run evidence 只保留无值 shape、版本化 policy descriptor、payload ref 与 digest；实际脱敏值进入独立 vault。
run、payload、首个签名 lifecycle event 与 outbox 同事务提交，purge 只删除 blob 并追加签名 hash-chain receipt。

读取链实行四层 fail-closed：run tenant/environment scope、operation purpose、classification clearance + all required
groups、当前 lifecycle state。到期由 bounded scheduler 清理，读取路径也同步执行 expiry；因此 scheduler backlog
不会形成绕过窗口。legal hold 冻结 purge，release 后若 deadline 已过立即清理。数据库 row lock + revision CAS
保证两个实例的 hold/purge 竞态只有一个状态赢家；生命周期变化同时进入现有 cursor outbox。

| 维度 | 权重 | 当前完成率 | 得分 | 本轮可证明增量 | 仍未证明 |
|---|---:|---:|---:|---|---|
| 协议、版本与身份边界 | 15 | 99.5% | 14.925 | payload replay v2、retention view/command/sweep、run evidence v7 machine schema；capability 动态暴露 policy descriptor 与端点 | 真实 ANEKE N/N-1 consumer matrix；显式 response negotiation；客户 policy bundle 认证 |
| GraphDraft 依赖快照 | 10 | 94% | 9.40 | 无语义变化 | ANEKE consumer matrix、外部 HA DB、SLA 权威源、snapshot GC |
| Run evidence 可信链 | 20 | 99.5% | 19.90 | payload digest/descriptor 进入 immutable seal；purge 后原 evidence 仍验签；lifecycle transition 独立签名并链式绑定 | 可信时间戳、外部 verifier matrix、客户 KMS conformance、evidence 自身 legal-hold/retention 总策略 |
| Payload replay | 15 | 95% | 14.25 | detached vault、选择性 retention、RESTRICTED no-retain、classification ABAC、legal hold、到期 410、签名删除证明、真实 HTTP/H2 生命周期 | shadow/live 审批隔离；replay command 跨实例重复请求的统一幂等响应；residency/subject-request adapter |
| Timeout/partial failure 语义 | 10 | 100% | 10.00 | payload 分离保留 availability marker，不把完整 evidence 误判为缺失 | disconnect/detach 与非协作 I/O |
| Workbook、gate feedback 与 Deep Link | 10 | 76% | 7.60 | retention/classification 可进入 workbook evidence，但尚未自动消费 | 双向 workbook refs；readiness/SLA/migration/owner/retention gate policy |
| Change event、cursor、webhook 与对账 | 10 | 90% | 9.00 | capture/hold/release/purge 与 lifecycle state 同事务进入 cursor outbox | signed webhook、DLQ/退避、outbox retention/compaction、payload reconciliation snapshot、乱序 fault harness |
| 工业运行控制 | 10 | 92% | 9.20 | payload DB 分表、事务回滚、restart、tamper、双实例 hold/purge race、bounded scheduler、lazy expiry、真实 Spring/HTTP | 外部 HA DB、deletion backlog metrics/SLO、quota/bytes-days、residency、subject request、客户 policy conformance |
| **合计** | **100** |  | **94.275** |  | **加权差距 5.725%** |

Round 16 将 replay 单项差距从 `3.0%` 收敛到 `0.75%`，事件与工业运行控制再收敛 `0.4%`，总差距从
`8.375%` 降至 `5.725%`。不能把本轮评分抬到 100：recorded replay 的安全生命周期已成立，但 shadow/live
仍有意关闭，客户 policy/residency/subject-request 与外部 HA DB 删除 SLO 没有仓库内证据。下一轮最高收益病根是
workbook/gate 的真实消费闭环，它单项仍贡献 `2.4%` 差距；随后是 webhook/DLQ 与部署级工业证据。

## 2.17 Round 17 重新审计

Round 17 关闭了“gate result 只绑定 draft，因此 PASSED 无法证明依据”的病根。新增
`CorrectnessWorkbookBundle.v1` 从 exact draft/dependency snapshot 投影精确 suite revision、稳定 case/assertion ID、
脱敏测试表和已验签 run evidence refs；`GovernanceGateResult.v2` 的 decision basis 固化 workbook/source bundle、
snapshot、suite、evidence、policy 和 required checks。Resource Gateway 不接管 ANEKE 决策，但会拒绝无法映射到当前
不可变事实的 PASSED。suite/readiness 漂移会让已存 gate 自动 stale，跨 scope evidence 保持 404 最小披露。

gate result 写入现在与 `GOVERNANCE_GATE_RESULT_RECEIVED` 同事务；数据库唯一键为多实例幂等裁决点，相同内容重放
返回同一事实，异内容冲突，outbox 失败整体回滚。recorded replay 同样收紧：确定性 replay runId 的唯一键输家会在事务
回滚后回读 winner，核对 parent/request fingerprint 并返回同一响应；requestId 查询按 tenant/environment 隔离。

| 维度 | 权重 | 当前完成率 | 得分 | 本轮可证明增量 | 仍未证明 |
|---|---:|---:|---:|---|---|
| 协议、版本与身份边界 | 15 | 99.8% | 14.970 | workbook v1、gate v2 machine schema；capability 同时声明 gate v1/v2、workbook flags/endpoint；v1 PASSED fail closed | 真实 ANEKE N/N-1 consumer matrix；显式 Accept/response negotiation |
| GraphDraft 依赖快照 | 10 | 94% | 9.40 | workbook 和 gate basis 都复用一致 snapshot，并在组包/提交前两阶段复验 | ANEKE consumer matrix、外部 HA DB、SLA 权威源、snapshot GC |
| Run evidence 可信链 | 20 | 99.5% | 19.90 | workbook evidence ref 逐项核对 run scope、draft snapshot、material fingerprint 与签名 | 可信时间戳、外部 verifier matrix、客户 KMS conformance |
| Payload replay | 15 | 98% | 14.70 | 两个 DB repository/service 实例并发同 requestId 返回同一 replay；异内容 409；tenant 隔离；detached lifecycle request 持久幂等 | shadow/live 审批隔离；residency/subject-request adapter |
| Timeout/partial failure 语义 | 10 | 100% | 10.00 | required gate checks 可直接消费 readiness/failure evidence，不重解释 engine 状态 | disconnect/detach 与非协作 I/O |
| Workbook、gate feedback 与 Deep Link | 10 | 98% | 9.80 | deterministic/sanitized seed、exact suite/evidence refs、mappingStatus、policy required checks、basis freshness、Author stale、v1兼容 | 真实 ANEKE consumer conformance；图级 suite/golden case 统一投影 |
| Change event、cursor、webhook 与对账 | 10 | 94% | 9.40 | gate result 与 scoped event 同事务；跨实例幂等不重复发 event；outbox failure rollback | signed webhook、DLQ/退避、outbox retention/compaction、乱序 fault harness |
| 工业运行控制 | 10 | 92% | 9.20 | workbook source/suite drift、伪造/跨租户 evidence、DB restart/并发、gate outbox rollback 均有负向测试 | 外部 HA DB、quota/residency、客户 IAM/KMS/policy conformance、正式 metrics/SLO/DR |
| **合计** | **100** |  | **97.370** |  | **加权差距 2.630%** |

Round 17 将总差距从 `5.725%` 收敛到 `2.630%`，首次满足目标的数值阈值。该分数只表示仓库内实现与可执行
证据差距，不表示客户生产环境已经通过认证：真实 ANEKE、IdP、KMS/HSM、HA DB、residency 和 DR 仍必须走部署
conformance。shadow/live replay 继续通过 capability 明确关闭，因而不是一个隐藏的未受控副作用路径。

## 3. 已通过的证明

### 3.1 协议与隔离

- `/api/integration/capabilities` 返回稳定 envelope 和真实 feature flags。
- draft export 显式携带 `operatorRef -> operatorLibraryId`、operator/schema fingerprint、library revision、
  binding/activation revision 与状态、suite revision/case count 和 readiness；组包期漂移返回 retryable 409。
- scope mismatch 时只保留 draft 自有 operator snapshot，不导出当前受限 schema、library owner、binding、activation 或 suite。
- run evidence/replay 在 tenant 或 environment 不匹配时统一返回 404，避免授权范围探测。
- 缺失身份上下文返回稳定 `IntegrationProblem`，包含 code、status、retryable 和 correlationId。

### 3.2 Evidence 与 payload

- `VisualGraphRunRecord.v9` 持久化 draft/operator fingerprint、无值 payload availability shape、edge snapshot、结构化 node execution facts、run-control/recovery fact 和 payload policy descriptor/digest；sanitized context/output/node I/O 在独立 vault 中。
- sanitizer 对 secret/token/authorization/cookie/PII 类键及 Bearer/Basic/labeled credential 内容执行有界递归脱敏并记录 manifest。
- evidence manifest 校验每个 invoked node 的 input、成功节点 output、node/edge status 和持久 seal；缺口或验签失败进入 `QUARANTINED`。
- `DatabaseVisualEvidenceSigner` 持久化 Ed25519 key history；旧 run 在 repository 重建后仍可验证，consumer 可通过公开 verification key 离线验签。
- GET replay 在 scope/purpose/classification/lifecycle 四层校验后返回 `RECORDED + SANITIZED` payload；POST recorded replay 生成独立签名 run/evidence，明确 `externalInvocationCount=0` 和 `sideEffectPolicy=DENY`。

### 3.3 Governance 与 Deep Link

- gate result 绑定 `draftId + revision + draftFingerprint`，相同 id/内容幂等，不同内容冲突。
- Author read model 明确返回 `CURRENT/STALE/EXPIRED/MISSING`，草稿修订后旧结果立即变成 `STALE`。
- `/author/?draftId=&nodeId=&operatorRef=&runId=&gateIssueId=` 可恢复存量草稿、自动布局并定位上下文。
- 门禁问题可以从 `targetPath/deepLink` 聚焦节点，失效目标显示 warning。
- 真实浏览器发现并修复 React Strict Mode 首次加载被取消后无法重试的问题。

### 3.4 回归范围

```text
mvn -f resource-gateway-examples/pom.xml -Pfrontend clean verify
Tests run: 1620, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS (05:21)
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

### 3.13 外部副作用提交与对账证明

Round 11 同时验证执行路径与治理路径。执行路径证明 operator 必须先登记 attempt，原始 idempotency key 不进入
snapshot，credential-bearing lookup ref 会被丢弃；`UNKNOWN_COMMIT` 即使配置三次 retry 和 fallback，也只执行一次
外部写，并阻止下游节点。治理路径证明 provider 只按不透明 lookup ref 查询状态，不能重放原始写请求。

```text
BLOGE SideEffectJournal focused tests: 4 passed
Resource Gateway focused tests: 49 passed
  dynamic execution and DAG guard: 24
  evidence/capability/schema contracts: 13
  reconciliation service: 5
  persistent repository: 5
  controller purpose policy: 2

Resource Gateway full verify (real Chrome included):
  1543 passed, 0 failures, 0 errors, 2 skipped
  BUILD SUCCESS (04:56)

same requestId -> same signed record; provider invoked once
different requestId for resolved attempt -> conflict, no provider call
two repository instances -> one active lease; stale owner fenced
repository restart -> signed record and resolved head retained
outbox append failure -> record/head/event roll back atomically
tampered persisted record -> rejected before governance consumption
legacy node-level UNKNOWN_COMMIT without attempt -> remains OUTSTANDING/QUARANTINED
```

对账成功不会修改 base evidence 的 `QUARANTINED` 状态，而是生成绑定 `runId + evidenceId + manifestHash +
attemptFingerprint` 的签名 refinement record；只有所有可寻址 uncertainty 都被可信记录覆盖，effective summary 才能为
`READY`。`PARTIAL_COMMIT` 或旧版无法寻址的 unknown 会保留 synthetic outstanding marker，防止“没有结构化 attempt”
被误解释为“不需要对账”。

### 3.14 外部写一致性准入与防绕过证明

Round 12 的验证不是只测 schema parser，而是证明每一层的失败发生在正确边界：

```text
BLOGE core protocol focused tests: 6 passed
BLOGE common HTTP focused tests: 13 passed
  unmanaged POST -> rejected before client.send; test server received 0 requests

Resource Gateway focused regression: 330 passed
  unmanaged Java WRITE -> rejected before business invocation (invocations = 0)
  managed-but-no-journal WRITE -> rejected after return (invocations = 1)
  unmanaged descriptor POST -> rejected before HTTP server
  managed descriptor POST -> idempotency header + committed provider receipt
  success without receipt -> UNKNOWN_COMMIT
  binding missing capability/fault evidence -> rejected
  activation missing reconciler-health -> rejected
  resource descriptor contract round-trip and visual projection -> preserved

React authoring focused regression: 128 passed
  palette: managed write / write protocol required
  node: write ok / write blocked
  inspector: Side-effect protocol cause
  warning acknowledgement: explicit checkbox + non-empty audit reason before DESIGN import

Frontend full regression: 136 passed
  TypeScript + Vite 6.4.3 production build passed on Maven-managed Node 22.11
  npm audit: 0 vulnerabilities (from 3 moderate + 1 high + 1 critical before upgrade)

Resource Gateway full regression:
  mvn -f resource-gateway-examples/pom.xml -Pfrontend clean verify
  1553 passed, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS (05:33)
  34 real Chrome DOM scenarios passed
  390px AsyncAPI palette: two-column responsive cards, no horizontal overflow

Draw.io validation:
  0 errors, 0 warnings, 0 crossings, 0 overlaps
```

结构证据：[Resource Gateway 外部写一致性准入链](assets/resource-gateway-side-effect-conformance-chain.svg)，
图源为 [resource-gateway-side-effect-conformance-chain.drawio](assets/drawio/resource-gateway-side-effect-conformance-chain.drawio)。
真实浏览器还验证了 `/author/` 的 warning acknowledgement 流程：存在 warning 时 Import 在勾选确认并填写非空审计
原因前保持禁用；导入后 palette、node 和 inspector 分别展示合同状态、运行准入状态和阻断病因。页面证据见
[标注截图](assets/bloge-author-side-effect-protocol-annotated.svg)。

### 3.15 动态身份信任、组织 claims 与故障证明

Round 13 同时使用确定性状态机测试和两个真实 HTTP 服务证明身份信任，不把 JSON parser 单测冒充 IdP 生命周期：

```text
Dynamic trust + resolver/auth/audit focused: 30 passed
Integration package regression: 90 passed

unknown kid x24 concurrent requests -> one single-flight refresh; all accept rotated key
JWKS + revocation update -> one atomic snapshot; partial invalid document never publishes
token/key revocation -> visible within refresh interval + request timeout
transport/5xx + FAIL_CLOSED -> 503 retryable, audit reason IDENTITY_PROVIDER_UNAVAILABLE
transport/5xx + BOUNDED_STALE -> accepted only before configured stale deadline
malformed / private / weak / oversized / authority-expired document -> hard EXPIRED, never stale
RSA and Ed25519 public JWK -> verified; private material rejected

real local IAM HTTP authority + real Spring Boot Resource Gateway
tenant-a/org-a key-a -> 200; group/clearance/delegation grant audit persisted
publish overlapping key-a/key-b -> tenant-b/org-b key-b succeeds without restart
publish revoked jti -> 401 after configured propagation interval
ETag unchanged JWKS -> 304 observed while revocation feed advances
authority 503 -> Resource Gateway 503 retryable, not credential-invalid 401
oversized JWKS -> REMOTE_DOCUMENT_INVALID + provider unavailable

Resource Gateway full regression:
  mvn -f resource-gateway-examples/pom.xml -Pfrontend clean verify
  1567 passed, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS (05:20)
  34 real Chrome DOM scenarios passed

Draw.io validation: 0 errors, 0 warnings, 0 crossings, 0 overlaps
SVG/PNG visual inspection passed
```

结构证据：[Resource Gateway 动态 JWKS 信任生命周期](assets/resource-gateway-dynamic-jwks-trust-lifecycle.svg)，
图源为 [resource-gateway-dynamic-jwks-trust-lifecycle.drawio](assets/drawio/resource-gateway-dynamic-jwks-trust-lifecycle.drawio)。
当前 refresh counters/last failure 通过 capability 可读，但还没有正式 Micrometer 指标、SLO 告警和客户 IdP 认证报告，
因此这轮不关闭整个 `IAM-01`。浏览器回归还报告 Chrome 150 与当前 Selenium CDP 149 的近邻版本兼容提示；用例均通过，
但生产 CI 镜像仍应固定浏览器/驱动组合并消除此工具链漂移。

### 3.16 Managed evidence signing custody 与故障证明

Round 14 用确定性 provider、恶意 HTTP response 和真实 Spring Boot + HTTP signing authority 三层证明 custody：

```text
Managed signer state-machine tests: 11 passed
HTTP provider boundary tests: 4 passed
Real Spring managed-authority application test: 1 passed
Tool Studio integration service regression: 14 passed
Machine-schema drift test: 1 passed
Integration + visual/runtime package regression: 147 passed

fingerprint + expected keyId -> remote Ed25519 sign -> local public-key verification -> persisted seal
private key fields at key ingress -> PRIVATE_KEY_MATERIAL_REJECTED
duplicate JSON / oversized response / redirect -> non-retryable protocol failure
transport outage before snapshot expiry -> DEGRADED for historical local verification only
transport outage during sign -> no local fallback; signing fails
malformed key snapshot / invalid returned signature -> immediate UNAVAILABLE
snapshot expiry -> key resolution PROVIDER_UNAVAILABLE; public API maps to retryable 503, not 404
key-1 ACTIVE -> key-1 VERIFY_ONLY + key-2 ACTIVE -> old/new evidence both verify
key-1 DISABLED / REVOKED -> distinct KEY_DISABLED / KEY_REVOKED results
rotation between discovery and sign -> one refresh/retry; repeated drift -> ROTATION_UNSTABLE
24 concurrent due-refresh callers -> one provider key refresh
24 concurrent unknown-key lookups -> one throttled provider refresh

real local signing authority + real Spring Boot Resource Gateway
managed signer bean selected; H2 visual_evidence_signing_keys table absent
capability -> MANAGED_KMS_HSM + privateKeyExportable=false
evidence key API -> public material only
zero-restart rotate/revoke + sign 503 recovery -> passed

Resource Gateway full regression:
  mvn -f resource-gateway-examples/pom.xml -Pfrontend clean verify
  1585 passed, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS (05:19)
  34 real Chrome DOM scenarios passed
  TypeScript + Vite production build passed; npm audit 0 vulnerabilities

Draw.io validation: 0 errors, 0 warnings, 0 crossings, 0 overlaps
SVG/PNG visual inspection passed
```

结构证据：[Resource Gateway 托管 evidence 签名保管链](assets/resource-gateway-managed-evidence-signing-custody.svg)，
图源为 [resource-gateway-managed-evidence-signing-custody.drawio](assets/drawio/resource-gateway-managed-evidence-signing-custody.drawio)。
当前 capability counters 是进程级运行真相，provider audit 属于企业 signing authority 的权威记录；尚未实现 provider
audit ingestion、持久 public-key cache、Micrometer/SLO 告警或客户 KMS/HSM conformance kit，因此不能关闭部署级
`OPS-01`。

### 3.17 Governed replay payload 生命周期与删除证明

本轮自动化不是只检查 DTO 字段，而是直接检查数据库、签名、HTTP 错误和并发状态：

```text
run create -> payload value only in visual_run_payload_blobs
           -> visual_graph_run_records.run_json has no customerId/output value
           -> v9 descriptor contains payloadRef + sha256 digest + policy id/version/classification

PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED -> configurable retention
RESTRICTED + 0 days -> NOT_RETAINED, no blob
insufficient clearance -> 403 RG.INTEGRATION.PAYLOAD_CLEARANCE_REQUIRED
missing required group -> 403 RG.INTEGRATION.PAYLOAD_GROUP_REQUIRED (group names not echoed)
expired / PURGED / NOT_RETAINED -> 410 RG.INTEGRATION.PAYLOAD_NOT_AVAILABLE

CAPTURED -> HOLD_PLACED -> HOLD_RELEASED -> PURGED
each event signed; each event binds previousEventFingerprint
active hold -> manual/automatic purge conflict
release after retention deadline -> immediate PURGED
two DB repository instances race hold vs purge -> one revision winner, one consistent blob state
payload JSON tamper -> CORRUPT; replay fails closed
outbox failure -> run + payload state + blob roll back together
purge -> blob deleted; immutable run evidence signature still VERIFIED

real Spring Boot + H2 + HTTP
GET replay -> CONFIDENTIAL payload returned to matching identity
POST hold -> LEGAL_HOLD
POST purge while held -> 409
release + purge -> PURGED; blob count 0
GET replay after purge -> 410
GET evidence after purge -> retention.state=PURGED + signatureStatus=VERIFIED
GET events -> PAYLOAD_CAPTURED/HOLD_PLACED/HOLD_RELEASED/PURGED visible through cursor
```

机器合同包括 `payload-replay-bundle-v2`、`payload-retention-view-v1`、`payload-lifecycle-command-v1`、
`payload-retention-sweep-result-v1` 和 `run-evidence-bundle-v7`。结构证据：
[Resource Gateway 受治理 payload 生命周期](assets/resource-gateway-governed-payload-lifecycle.svg)，图源为
[resource-gateway-governed-payload-lifecycle.drawio](assets/drawio/resource-gateway-governed-payload-lifecycle.drawio)。

### 3.18 Workbook seed、gate decision basis 与跨实例 replay

Round 17 的证明直接覆盖 authority boundary、代际漂移、伪造引用和原子写入：

```text
exact draft + dependency snapshot + suite@revision
  -> deterministic CorrectnessWorkbookBundle.v1
  -> stable case/assertion ids
  -> explicit case-kind tag or REGRESSION+DEFAULTED
  -> test input/config/mock/expected sanitized before export
  -> matching signed runs exposed as runId + evidence material fingerprint
  -> bundle fingerprint repeatable

PASSED GovernanceGateResult.v2
  -> target tenant/namespace/environment + draft fingerprint verified
  -> workbook source bundle fingerprint verified
  -> dependency snapshot and exact suite refs verified
  -> every run evidence ref scope/fingerprint/signature verified
  -> policy.requiredChecks must all be PASSED
  -> stale source/suite/evidence = 409
  -> missing check = 409 INCOMPLETE
  -> cross-tenant run = scope-safe 404
  -> accepted result + GOVERNANCE_GATE_RESULT_RECEIVED commit atomically
  -> suite revision changes -> Author freshness STALE

two gate repositories on one DB
  -> same gate id/content = one row + one event
  -> same id/different content = conflict
  -> outbox failure = gate row rollback

two replay service/repository instances on one DB
  -> same tenant/parent/request/content = same deterministic replay run
  -> one replay row + one detached payload state
  -> same request/different content = RG.INTEGRATION.REPLAY_REQUEST_ID_CONFLICT
  -> same requestId in another tenant = independent replay, no cross-tenant collision
```

机器合同为 `correctness-workbook-bundle-v1` 与 `governance-gate-result-v2`；序列化字段集合与 capability
objects/features/endpoints 均有精确测试。结构证据：
[Resource Gateway 与 ANEKE workbook/gate 证据闭环](assets/resource-gateway-workbook-gate-evidence-loop.svg)，图源为
[resource-gateway-workbook-gate-evidence-loop.drawio](assets/drawio/resource-gateway-workbook-gate-evidence-loop.drawio)。

## 4. 部署验收门禁

以下项目不再是仓库内协议主链缺失，但仍是客户生产晋级的强制门禁。没有客户环境证据时，不得把
`2.630%` 的实现差距解释为“已通过生产认证”。

| ID | 部署门禁 | 病根 | 根治验收 |
|---|---|---|---|
| `IAM-01` | 客户组织身份策略与部署认证尚未端到端证明 | Round 13 已关闭动态 JWKS/revocation 与组织 claims；Round 16 已让 payload classification 对 clearance/groups 执行 fail-closed 策略。残余是客户真实 IdP/mTLS、多地域 authority、权威 classification registry/policy bundle、group 生命周期/orphan owner、break-glass 和正式告警/runbook | 客户 IdP + classification-policy conformance kit + signed policy bundle/version + group/owner lifecycle + emergency grant + multi-region outage/rollback + propagation SLO alert/report |
| `OPS-01` | 客户 KMS/HSM custody 与灾备认证尚未完成 | Round 14 已关闭 private key 必须进入 H2、远端签名不反验、轮换状态不明确和 capability 不透明的代码病根；残余是客户 provider identity/policy、authoritative audit、历史 public-key retention、跨地域 KMS 和正式 SLO | 客户 KMS/HSM conformance kit + mTLS/workload policy + provider audit export + persistent public-key retention + region outage/restore/disable/revoke drill + latency/error SLO |
| `RUN-01` | disconnect/detach 与任意 binding 的预算合规尚未封口 | Round 10 已完成 OperatorContext、scheduler admission、retry/timeout/suspend、Resource Gateway/common HTTP 和 remote worker 的 remaining-budget 传播，并防止 wall-clock 回拨放宽预算；但客户端断开语义未版本化，私有 binding 仍可忽略合同 | versioned `detachPolicy` + disconnect race/fault tests + runtime binding conformance suite + non-cooperative I/O quarantine |
| `RUN-02` | 副作用协议尚未覆盖企业 operator/binding 存量 | Round 12 已关闭正常目录下 `WRITE_EXTERNAL`、Java `SideEffectType.WRITE`、descriptor HTTP mutation 和低层 unsafe `httpRequest` 绕过；残余病根是实现可错报 effect、数据库/消息/私有 SDK 写边界不可见、默认 provider adapter registry 为空 | 制品 effect/egress 扫描 + runtime egress/DB/message telemetry + owner attestation 对账 + provider conformance kit + outage/restart/重复请求演练 + compensate policy + 覆盖率门禁 |

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
| 11 | 外部副作用提交确认与持久化对账 | journal、receipt/proof、unknown DAG guard、v6 evidence、lease/fence reconcile、签名 refinement、原子 outbox、effective gate summary | 14.20% | BLOGE 4 个聚焦 tests；Resource Gateway 49 个聚焦、1543 个全量 tests（含真实 Chrome）；DB restart/双实例/fencing/rollback/tamper/legacy unknown；corporate Draw.io 0 errors/warnings/crossings/overlaps |
| 12 | 外部写合同、binding/activation conformance 与防绕过 | operator/resource 正式合同、fingerprint/readiness、Java WRITE pre/post admission、descriptor/common HTTP fail closed、Author 状态可见；Vite/Vitest 漏洞归零；移动端 Palette 无横向溢出 | 12.55% | BLOGE core 6 个、common HTTP 13 个；Resource Gateway 330 个聚焦、1553 个全量 tests；React 128 个聚焦、136 个全量 tests；34 个真实 Chrome 场景；npm audit 0 vulnerabilities；corporate Draw.io 0 errors/warnings/crossings/overlaps |
| 13 | 动态企业身份信任与组织 claims | JWKS/revocation 原子刷新、single-flight、传播 SLO、strict/bounded-stale、401/503、group/clearance/delegation grant、credential-free audit | 11.875% | 30 个身份聚焦 tests、90 个 integration package tests、真实 IAM HTTP + Spring Boot 轮换/撤销/outage/oversize 演练；1567 个全量 tests、34 个真实 Chrome 场景；corporate Draw.io 0 errors/warnings/crossings/overlaps |
| 14 | KMS/HSM managed evidence signing custody | provider SPI、versioned key/sign schemas、non-exportable custody、原子公钥代际、本地反验、rotation/disable/revoke、503/404 分流 | 11.375% | 31 个聚焦 tests、147 个 integration/runtime package tests、4 份 machine schema、真实 signing HTTP + Spring Boot 轮换/撤销/outage；1585 个全量 tests、34 个真实 Chrome 场景、npm audit 0 vulnerabilities；corporate Draw.io 0 errors/warnings/crossings/overlaps |
| 15 | scope-safe GraphDraft consistent dependency snapshot | profile v2 + snapshot v1 固化 library/binding/activation/suite refs 与 readiness；相关资产两阶段重读防混代际；scope mismatch 最小披露；稳定 retryable 409；确定性相关资产指纹 | 8.375% | 23 个 snapshot/protocol/service 聚焦 tests、101 个 integration package tests（含真实 Spring/H2 依赖组装）、2 份 machine schema；1595 个全量 tests、34 个真实 Chrome 场景、npm audit 0 vulnerabilities；多库、多租户、missing/stale/blocked readiness、相关/无关并发漂移矩阵全部通过；corporate Draw.io 0 errors/warnings/crossings/overlaps |
| 16 | governed detached replay payload lifecycle | payload 与签名 run record 分离；classification/retention、clearance/groups ABAC、expiry sweep、legal hold/release/purge、签名 hash-chain lifecycle event、事务 outbox、跨实例 CAS | 5.725% | 15 个 payload governance 聚焦 tests、真实 Spring/H2/HTTP 生命周期、5 份新增/升级 machine schema；最终合并全量验证由 Round 17 覆盖；corporate Draw.io 0 errors/warnings/crossings/overlaps |
| 17 | correctness workbook/gate evidence loop + cross-instance replay | exact suite/evidence workbook seed、稳定 case/assertion ID、gate v2 decision basis、PASSED fail-closed、gate/outbox 原子提交、数据库唯一键幂等、跨实例 deterministic replay winner | 2.630% | 10 个 workbook/gate/replay 聚焦 tests、117 个 integration package tests、65 个 runtime package tests、1620 个全量 tests、34 个真实 Chrome 场景、npm audit 0 vulnerabilities；2 份 machine schema；corporate Draw.io 0 errors/warnings/crossings/overlaps |

仓库内实施目标现已满足 `<3%`，但这不等于客户生产认证。第 4 节的 IAM、KMS/HSM、disconnect/binding 与存量副作用
覆盖仍是环境晋级门禁；必须取得客户 conformance、故障演练和 SLO 证据后，才能声明对应部署通过。
