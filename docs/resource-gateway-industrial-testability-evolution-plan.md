# Resource Gateway 工业级可测试性与执行数据控制反转演进方案

> 核心判断：Resource Gateway 下一阶段最值得投入的不是继续增加画布控件，而是把现有
> schema、DSL、operator、DAG、fixture、run trace 和 evidence 串成一条可重复、可隔离、
> 可审计的正确性证明链。建议将这套能力正式命名为 **Execution Data Control Plane**，
> 中文名为“执行数据控制面”。

| 文档属性 | 内容 |
|---|---|
| 状态 | Accepted / In implementation；Stage 0/1 已落地，Stage 2 主路径持续收口，Stage 3 证据链已闭环，Stage 4 持续加固，Stage 5 已闭合 bounded pure-DSL mutation planning/materialization/execution/evidence 主链 |
| 目标读者 | Resource Gateway、BLOGE Runtime、operator 开发团队、QA、平台安全、SRE、ANEKE Tool Studio |
| 设计目标 | 让调用方在测试运行中确定性控制 DAG 的外部数据、故障和非确定性来源，并产出可验证的测试证据 |
| 非目标 | 不把 Resource Gateway 变成通用代码覆盖率平台；不允许普通生产请求携带测试替换指令；不替代 operator 代码仓库中的白盒单元测试 |
| 第一原则 | 测试控制必须是运行期带外控制，不进入业务 `GraphContext`，不改变 DSL 业务语义，不能被生产请求伪造 |

### 实施快照（2026-07-18）

| 范围 | 状态 | 代码/证据 |
| --- | --- | --- |
| Stage 0 语义冻结 | Done | `SCHEMA_CONTRACT` 诚实命名；五个版本化 testing domain；隔离与 opaque runtime ADR；capability protocol |
| Stage 1 unified kernel | Done | selector/preflight/effective plan、独立 engine、五行为、consumption/assertion/evidence、F2/F3、micro graph、旧 graph suite adapter；1653 tests 全绿 |
| Stage 2 public control plane | In progress | graph/operator target discovery、operator target v2 composability manifest、graph execution/batch/query、operator micro-graph execution、canvas executable operator suite（四类 case intent、内容寻址 fixture/一等 suite 发布、精确 revision 执行与 aggregate coverage/promotion 回显）、fixture/TestSuite registry、幂等 immutable TestSuite runner、独立 child/suite-run store、聚合结构 coverage 与 promotion eligibility、process-owner lease/heartbeat/checkpoint fence、v1/v2/v3 保代的 abandoned RUNNING fail-closed reconciliation、脱敏、10 态 child evidence、profile/identity/production protocol guard、独立 Java/JUnit/CI test-kit suite adapter、七图/14-case F3 dogfooding及其 governed catalog materialization、numeric tolerance、run-scoped logical clock + DELAY/TIMEOUT、受治理 F4 replay payload 精确捕获/脱敏/retention/tombstone、exact-ref REPLAY 执行、payload-free plan v2 谱系与认证降级，以及同步 nested/foreach/loop/compensation 控制传播、动态 attempt/occurrence selector 与 occurrence/attempt/node/edge evidence 已落地；streaming/suspendable control/evidence 与物理 network isolation 待完成 |
| Stage 3 | In progress | graph/operator `TestRunEvidence`、suite checkpoint/terminal attestation、ordered child closure、payload-free portable bundle、suite/evidence/attestation 独立 v2 typed semantic coverage 已完成；signed atomic key-set、managed v1/v2 lifecycle、签名时刻 lifecycle policy、外部 M-of-N trust publication、bounded append-only consistency page、durable consumer checkpoint、rollback/fork/split-view/revoked-pin resurrection detection 与 test-kit independent verifier 已完成；exact-suite ANEKE semantic workbook seed、`GovernanceGateResult.v3` 可重建 basis、编译级 GraphDraft target 绑定和独立 schema consumer 已完成；真实 ANEKE N/N-1 conformance、独立 witness gossip/跨域一致性证明待完成 |
| Stage 4 | In progress | BLOGE run-scoped services、checkpoint/resume primitives 与 RG deterministic provider、组合 checkpoint、同库事务、数据库时钟 fence、幂等命令和 staged 四 store aggregate 已落地；公开 authenticated durable GRAPH/OPERATOR create、payload-free query、owner claim、heartbeat、one-signal suspended-or-terminal recovery step、有界同步 multi-suspension recovery sequence、兼容 terminal-only recovery 和进程内 lease coordinator 已闭合；recovery sequence 外层及派生 step/claim/automatic-heartbeat 已具备数据库租约化有界 retention、独立 HMAC tombstone、密钥轮换启动自检、固定基数 telemetry 和数据库时钟 backlog SLO/readiness；公开 non-blocking worker pull 已在认证 tenant/org/project/environment 内有界扫描，逐候选重授权，并把 exact lease CAS、hidden dispatch、`ACQUIRED/NO_WORK` 幂等结果和审计原子提交，再以 scope 级持久化循环 keyset 游标避免稳定毒化前缀饥饿，对 exact checkpoint 的确定性失败做数据库时钟指数退避，并在连续失败阈值后转为永久 worker quarantine；隔离 list/claim/release、数据库权威 maker/checker approved discard、token-free receipt/history、审批 SLO observation、claim-command replay token AES-GCM envelope/旧行迁移/轮换重包、active-control HMAC fence/旧行迁移/轮换重键、命令/审批/历史的数据库租约化有界保留、独立 keyed-HMAC request-index tombstone/在线轮换/旧行惰性迁移、N/N-1 三阶段 write/readiness/capability、challenge-bound 逐副本签名 proof、独立 test-kit exact-inventory fleet gate、外部 Ed25519 M-of-N quarantine change authorization 的 HTTP v2/Schema/config/readiness/capability/数据库唯一消费与四维即时 admission 已落地。其他 durable command family 的统一有界 lifecycle、跨平台 serving-inventory 完整性证明、外部工单全生命周期与动态撤销刷新、法律保留/备份擦除、外部 WORM、runtime-state dispatch、排队/公平/优先级调度、异步/无界多 suspension 调度、跨进程 worker supervision、强制 worker 取消、完整历史 trace evidence、stream offset/checkpoint、identity/flag/secret fixture authority、streaming 恢复与确定性并发待完成 |
| Stage 5 | In progress（isolated pure-DSL mutation execution/evidence 已闭环） | 已落地 graph/operator schema boundary planning/admission、seeded bounded property plan/materialization/execution/evidence、recoverable AST mutation planning/exact regeneration、immutable V5 mutation suite、baseline-first 隔离执行、V5 signed evidence/abandoned reconciliation、HTTP/capability、独立 test-kit/CLI 与真实 Spring 闭环；equivalent-mutant detection、flaky analysis、统计置信策略、跨进程调度和物理隔离尚未落地 |

第三十九增量从 Stage 5 先切出一个可诚实交付的 schema-boundary tracer bullet。新的
`bloge.testBoundaryCasePlan.v1` 对当前 exact GRAPH/OPERATOR target 生成完整输入候选；baseline 由样例
生成器合成，但每个候选必须再由公共 `VisualSchemaValidator` 独立证明。拒绝候选还必须命中该变换对应
的稳定诊断族，不能拿偶然的其他校验错误冒充覆盖。planner 支持 required/unknown property、type、
numeric/exclusive bounds、string/array length、enum/const，并把 BLOGE schema 投影损失、未展开约束、
候选不可证明和 case/depth/collection 截断全部变成稳定 gap。两个 profile-isolated GET endpoint、
capability feature/object/endpoint 和 testing-control-plane JSON Schema 已同步；56 项聚焦协议测试全绿。
这个增量只生成内容寻址的 authoring plan，不持久化 suite、不执行 DAG、不签发 evidence，也不宣称
穷举 property 空间。该增量交付时的下一步是先建立 plan -> immutable boundary suite 的人工确认/内容寻址转换，再做
可复现 property seed/shrink 与仅限纯 DSL 的 mutation runner，避免把“生成了输入”包装成 mutation score。
完整 Resource Gateway `clean verify` 执行 2340 tests，0 failures、0 errors、34 个条件跳过并完成
可执行 JAR；独立 test-kit `clean verify` 执行 77 tests，0 failures、0 errors、0 skips，并通过权威
Schema 打包、普通/shaded JAR 与 public Javadoc 门禁。

第四十增量（历史快照，当前执行限制已被第四十一增量取代）完成 plan 到 immutable
schema-admission suite 的人工确认转换。调用方必须提交 exact
target/input-schema/plan 三指纹和显式 case ID 集合；服务端重新生成当前 plan 后才写入，`PARTIAL`
必须明确接受 gap，`UNAVAILABLE` 不可发布。转换生成无 rule/assertion/clock/random 的惰性 fixture，
以及把每个 case 与 `ACCEPTED/SCHEMA_REJECTED + validationCodes` 一一闭合的
`bloge.testSuite.v3`；fixture 与 suite revision 都由内容派生，可安全重试，第二次写失败最多留下不可执行、
未引用的 immutable fixture。v3 的 coverage/promotion 不允许声明 invocation、edge、assertion、semantic
或 certifiable case，公共 Schema、capability、graph/operator POST endpoint 和真实 Spring HTTP 回读已闭合。
本增量没有把旧 suite runner 改成“把预期拒绝当失败”：capability 明示
`schemaAdmissionSuiteExecution=false`，runner 在任何执行/admission/evidence 写入前返回稳定 409。下一步
必须定义并实现签名 schema-admission evidence，再开放执行。实现边界与反例见
[Stage 5 boundary-suite materialization verification](resource-gateway-execution-data-control-plane-stage5-boundary-suite-materialization-verification.md)。
本增量完整 Resource Gateway `clean verify` 执行 2348 tests，0 failures、0 errors、34 个条件跳过，
并完成 Spring Boot 可执行 JAR；独立 test-kit `clean verify` 执行 77 tests，0 failures、0 errors、
0 skips，并通过权威 Schema 打包、普通/shaded JAR 与 public Javadoc 门禁。

第四十一增量关闭第四十增量刻意保留的“可物化但不可执行”边界。`bloge.testSuiteRunEvidence.v3`
把执行目的固定为 `SCHEMA_ADMISSION_SUITE_EXECUTION`，绑定 exact suite/target/input-schema/boundary-plan/
generator/validator mode，并用一一对应的 typed admission result 与独立 admission coverage 表达结果。
runner 在同一原子快照中解析当前 target、schema 与重建 plan，经数据库权威 admission、owner lease、
签名 checkpoint 后逐 case 调用公共 validator；永不进入 graph/operator business runner，也不生成 child
run。结构 coverage 永远 `NOT_EVALUATED`，promotion 永远以 `SCHEMA_ADMISSION_ONLY` 和
`BUSINESS_EXECUTION_NOT_PERFORMED` 阻断。响应、证据、attestation、bundle 分别升级为 v4/v3/v3/v3，
空 child closure 作为受签名隔离事实；幂等重放、fail-fast、容量拒绝、签名失败、terminal persistence
失败、lease 丢失和 abandoned checkpoint reconciliation 均有 fail-closed 反例。权威 JSON Schema、
capability、真实 Spring HTTP materialize/execute/read/export、独立 test-kit typed projection/JUnit gate/
Ed25519 offline verifier 已同步，跨代组合直接拒绝。实现和反例见
[Stage 5 schema-admission execution verification](resource-gateway-execution-data-control-plane-stage5-schema-admission-execution-verification.md)。
该证据只证明 reviewed schema admission，不证明业务行为、结构/语义覆盖或发布资格；下一步进入
可复现 property seed/shrink 与纯 DSL mutation score 的可证伪实现。
本增量完整 Resource Gateway `clean verify` 执行 2364 tests，0 failures、0 errors、2 个条件跳过，
并完成 Spring Boot 可执行 JAR；独立 test-kit `clean verify` 执行 84 tests，0 failures、0 errors、
0 skips，并通过权威 Schema 打包、普通/shaded JAR 与严格 public Javadoc 门禁。

第四十二增量（历史快照，执行限制已被第四十四增量取代）先关闭 property testing 的可复现
authoring coordinate，不越级声称执行证据。
`bloge.testPropertyCasePlan.v1` 把 exact target/input-schema、调用方 seed、requested trials、shrink/
case/depth/collection/attempt 上限、完整 root/shrink 输入、已知 gap 和 validator proof mode 一并内容寻址。
所有 root 与严格递减的线性 shrink candidate 均须经公共 `VisualSchemaValidator` 复核；低基数域不能用
重复样本填数，未展开 constraint、BLOGE 投影损失和资源截断均使状态降为 `PARTIAL` 或
`UNAVAILABLE`。协议强制 `BOUNDED_SAMPLED + exhaustive=false`，因此 `GENERATED` 只表示请求的有限
样本在声明边界内生成完成，不表示输入空间穷举。graph/operator GET、profile/identity boundary、严格
JSON Schema、capability object/feature/endpoint、controller 与真实 Spring HTTP 同 seed 精确重放均同步。
`propertySuiteExecution=false` 继续 fail closed；下一增量必须把 plan closure 物化为绑定真实 assertion
fixture 的 immutable suite，并定义同代签名 evidence/attestation/bundle 后才可开放 runner。验证见
[Stage 5 property-plan verification](resource-gateway-execution-data-control-plane-stage5-property-plan-verification.md)。
本增量 28 项聚焦测试与 Resource Gateway 全量 2372 tests 全绿，后者 0 failures、0 errors、2 个条件
跳过并完成可执行 JAR；独立 test-kit 84 tests 全绿，并通过 Schema 打包、普通/shaded JAR 与 public
Javadoc 门禁。

第四十三增量（历史快照，执行限制已被第四十四增量取代）关闭 property plan 到 immutable suite 的
治理转换，不把 authoring asset 冒充 correctness evidence。`bloge.testPropertySuiteMaterializationRequest.v1`
绑定 suite identity、classification、exact
target/input-schema/plan 三指纹、完整 generation coordinate 和一个既存 assertion-bearing fixture；服务端
在同一认证请求内重建 plan 后，必须原序冻结所有 root 与预计算 shrink candidate，不提供 case selection。
`bloge.testSuite.v4` 把 bounded-sampled/non-exhaustive 量词、完整 policy、accepted gaps、输入指纹与严格
递减 lineage 变成 canonical 内容，所有 case 只能是 `PROPERTY`。通用 registration 不接受 V4，V1-V3
也不能借用 `PROPERTY`；只有持有 regenerated plan proof 的 package-owned materializer 可以注册。
graph/operator POST、严格 JSON Schema、capability、真实 Spring HTTP 和独立 test-kit plan/materialization
client 已同步。`propertySuiteMaterialization=true` 只表示可冻结 reviewed asset；`propertySuiteExecution=false`
仍是硬边界，runner 在持久化 run、runtime admission 和业务调用前返回
`RG.TEST.PROPERTY_EVIDENCE_UNAVAILABLE`。必须等 property result、shrink evaluation、coverage、checkpoint、
terminal attestation、portable bundle 与 verifier 同代后才能开放执行。验证见
[Stage 5 immutable property suite materialization verification](resource-gateway-execution-data-control-plane-stage5-property-suite-materialization-verification.md)。
本增量完整 Resource Gateway `clean verify` 执行 2382 tests，0 failures、0 errors、2 个条件跳过，
并完成 Spring Boot 可执行 JAR；独立 test-kit `clean verify` 执行 85 tests，0 failures、0 errors、
0 skips，并通过权威 Schema 打包、普通/shaded JAR 与严格 public JavaDoc 门禁。

第四十四增量关闭第四十三增量刻意保留的 property execution/evidence 缺口。
`bloge.testSuiteRunEvidence.v4` 不把 property suite 降级成普通 case 列表：它绑定 exact property plan、
input schema、generation policy、`BOUNDED_SAMPLED + exhaustive=false`、root/shrink lineage、逐 case child
evidence 和 typed property coverage。runner 只执行 V4 已冻结输入，不在运行期重新生成样本；
`COLLECT_ALL` 跑完整闭包，`FAIL_FAST` 在首个反例后仍完成当前 root 的预计算 shrink path，再停止后续 root。
反例只声明 `minimalityScope=PRECOMPUTED_SHRINK_PATH` 且强制 `globallyMinimal=false`，从协议层阻止把
有限路径最小观察冒充全域最小证明。响应、证据、attestation、portable bundle 分别升级为 V5/V4/V4/V4，
数据库 generation guard、签名 material、ordered child closure、test-kit Schema 和 Ed25519 offline verifier
同步闭合；capability 仅在隔离 suite execution endpoint 可用时发布 `propertySuiteExecution=true`。
幂等重放返回既有 checkpoint/terminal，lease 丢失、签名或 terminal persistence 失败均 fail closed；
abandoned reconciliation 只保留已完成事实、把 pending property case 标成 `EVIDENCE_INCOMPLETE`，绝不
重建或重跑业务输入。实现边界和反例见
[Stage 5 property execution verification](resource-gateway-execution-data-control-plane-stage5-property-execution-verification.md)。
这仍是有界样本正确性证据，不是全输入域证明；该增量交付时的下一步集中在 mutation score、flaky/统计置信、
跨进程并行调度和物理 test-runtime 隔离。
本增量完整 Resource Gateway `clean verify` 执行 2389 tests，0 failures、0 errors、2 个既有条件跳过，
并通过 34 项真实浏览器回归和 Spring Boot 可执行 JAR 打包；独立 test-kit `clean verify` 执行
92 tests，0 failures、0 errors、0 skips，并通过权威 Schema、普通/shaded JAR、V4 语义重算/离线验签与严格
public JavaDoc 门禁。

第四十五增量先交付 mutation 的可重放 authoring coordinate，不把“生成 mutant”越级包装成 score。
`bloge.testMutationCasePlan.v1` 从 exact graph 自带的 recoverable `bloge-dsl.ast.v1` source 出发，经受限
class allowlist 解码后，先独立复编译 baseline 并同时核对 graph artifact 与 dependency-bound target
fingerprint；随后对 branch、decision table、transform、fallback 和 retry 生成有界纯 DSL 改写，每个候选
再次通过 runtime operator registry 独立复编译，非编译、重复、截断和未展开 scope 全部进入稳定 gap。
v1 不改 `operatorRef`、operator implementation、外部请求、fixture、payload 或 operator input binding；响应
也不携带可执行 source 和业务字面量。严格 Schema 将 1..128 上限、三态完整性、九类 mutation、三个内容
指纹以及 `equivalenceClassification=UNKNOWN` 固化；capability 分离发布
`pureDslMutationPlanning=true`、`pureDslMutationExecution=false`、`mutationScoreEvidence=false`；这些是该
历史 authoring 增量交付时的 capability，独立
test-kit 提供 schema-validated client。验证见
[Stage 5 mutation-plan verification](resource-gateway-execution-data-control-plane-stage5-mutation-plan-verification.md)。
该增量当时还没有 suite materialization、mutant execution、survived/killed/inconclusive 判定、等价
mutant 检测、score denominator、签名 evidence 或发布门禁语义；这些缺口由后续增量沿 exact
baseline/plan/mutant fingerprint closure 逐项关闭，不能把 authoring plan 当作 correctness evidence。
本增量完整 Resource Gateway `clean verify` 执行 2398 tests，0 failures、0 errors、2 个既有条件跳过，
并通过真实浏览器回归与 Spring Boot 可执行 JAR 打包；独立 test-kit `clean verify` 执行 96 tests，
0 failures、0 errors、0 skips，并通过权威 Schema 打包、普通/uber JAR 与严格 public JavaDoc 门禁。

第四十六增量（历史协议快照，执行限制已被第四十七增量取代）先关闭 mutation suite 与 evidence protocol 的真实性边界。immutable `bloge.testSuite.v5`
绑定 exact reviewed plan、baseline/source/artifact/target fingerprint、完整 oracle suite/fixture closure，
并把同步代际限制在 16 mutant × 16 case、最多 256 work unit；每个 executable mutant 只能由服务端通过
同一 planner 精确重生成，普通 suite runner 对 V5 fail closed。`bloge.testSuiteRunEvidence.v5` 与纯
evaluator 把 baseline-first 前提、mutant-case 状态、killed/survived/inconclusive/unclassified 分类和
score denominator 固化为可重算协议：只有签名 child 的 `ASSERTION_FAILED` 能 kill；运行、fixture、
control、target 和 evidence failure 不得伪装成 kill；无有效 kill 且存在未调度 case 时保持未分类；
generation one 不排除 equivalent mutant；分母仅含 killed + survived，未分类时 score 固定为 0。
attestation v5、response v6、portable bundle v5、codec、持久化代际和 strict Schema 已同步，但该历史
协议增量交付时 capability 继续关闭 mutation execution/score evidence。验证见
[Stage 5 mutation evidence protocol verification](resource-gateway-execution-data-control-plane-stage5-mutation-evidence-protocol-verification.md)。
下一增量必须实现独立 runner、baseline-first 调度、exact-mutant child execution、租约/崩溃恢复、HTTP、
test-kit 和真实 Spring 端到端闭包；在此之前不得签发可消费的 mutation score evidence。

第四十七增量关闭上述 mutation execution/evidence 缺口。独立
`TestMutationSuiteExecutionService` 只接受 exact V5 suite，baseline 必须先用原始 target 和完整 oracle
fixture closure 通过，之后每个 mutant 才由已审阅 plan 在服务端精确重生成并进入独立 test engine。
`COLLECT_ALL` 与 `STOP_AFTER_KILL` 都遍历全部 mutant，后者只在当前 mutant 出现签名 assertion kill 后
停止其剩余 case，不能通过选择性截断抬高 score。timeout、fixture、control、runtime、target 和 evidence
failure 均保持 inconclusive。runner 复用数据库权威幂等身份、owner lease/heartbeat、逐 child checkpoint、
V5 terminal attestation 和 portable bundle；abandoned reconciliation 只保留 terminal facts、把 pending
工作降为 incomplete/not-scheduled、重算 score 并重签，绝不重跑可能已有副作用的 child。

公开 V5 materialization 与 mutation execution HTTP、严格 Schema、capability、真实 Spring 全链路和独立
test-kit 已闭合。test-kit 独立重算 baseline、mutant classification、kill provenance、denominator 与 policy，
并核对 `baseline/<caseId>`、`<mutantId>/<caseId>` 的签名 child closure；CLI 通过显式
`--mode MUTATION` 提供 payload-free CI/JUnit gate。验证见
[Stage 5 mutation execution verification](resource-gateway-execution-data-control-plane-stage5-mutation-execution-verification.md)。
generation one 仍不提供 semantic equivalent-mutant proof、flaky/quarantine 重跑分析、统计置信、跨进程
并行调度或部署级硬隔离，这些不能从一个数值 score 反推。
本增量完整 Resource Gateway `clean verify` 执行 2436 tests，0 failures、0 errors、2 个既有条件跳过，
其中浏览器回归共 35 tests，并完成 Spring Boot 可执行 JAR；独立 test-kit `clean verify` 执行
111 tests，0 failures、0 errors、0 skips，并通过权威 Schema、普通/shaded JAR、V5 语义重算、
payload-free mutation JUnit/CLI 与严格 public JavaDoc 门禁。

第三十五增量已把多 signal 图的恢复原语从“engine 能识别、应用层拒绝”推进为数据库权威的
单步状态机。`RecoveryStepCommand` 只允许 live issued dispatch 消费一个 signal 并到达唯一新
`SUSPENDED` 或五类 `TERMINAL` 边界；四类 BLOGE store mutation、fixture/provider cursor、下一控制
checkpoint、幂等 command、可选 terminal receipt 与 companion audit 同事务提交。再次挂起时以
`leaseExpiresAt = updatedAt = databaseNow` 显式释放所有权，后续 worker 必须重新扫描、授权和 claim，
旧 dispatch 不能跨边界延续。公开 `recovery-steps` HTTP、严格 Schema、独立 operation、capability、
profile isolation 与应用级重放已接线；85 项 repository/runtime 与 15 项公开协议测试全绿。当前闭合
逐 signal 推进，不宣称自动多 suspension 编排；验证见
[Stage 4 recovery-step verification](resource-gateway-execution-data-control-plane-stage4-recovery-step-verification.md)。
本增量完整 Resource Gateway `clean verify` 执行 2285 tests，0 failures、0 errors、2 个既有条件
浏览器跳过并完成 Spring Boot JAR 打包；独立 test-kit `clean verify` 执行 75 tests，0 failures、
0 errors、0 skips，并通过普通/shaded JAR、权威 Schema 打包与 public Javadoc 门禁。

第三十六增量把调用方手工重复“step -> 读新 fence -> claim -> step”的编排收敛为有界同步
recovery sequence。`bloge.durableTestRecoverySequenceRequest.v1` 在第一条 signal 执行前，以同库事务
保留 tenant/environment scoped 外层 key、完整 authenticated intent 指纹、run、signal count、数据库
时间、whole-record fingerprint 与 semantic audit；记录不含 signal 原值。1..16 条 signal、单条
256 KiB、总计 1 MiB 在任何 child mutation 前统一校验。服务端从外层 key 派生稳定 child key，逐项复用
现有 atomic recovery step；每次 `SUSPENDED` 后必须对 exact released checkpoint 复用 owner claim，重新
授权并签发新 hidden dispatch。响应丢失后，同一完整 intent 从 index zero 重放已提交 prefix，并在首个
未提交 child 继续；改变晚位 signal、顺序、初始 fence、run 或 principal 会在 child 执行前冲突。
`bloge.durableTestRecoverySequenceResponse.v1` 只返回有序 payload-free steps、provided/consumed count、
最终 outcome/status 与 `TERMINAL`/`SIGNALS_EXHAUSTED`。该增量关闭同步有限 signal fixture 的自动多
suspension 编排，不声称 durable signal inbox、异步 dispatcher、公平队列、跨进程 supervisor 或 hard
cancellation；验证见
[Stage 4 recovery-sequence verification](resource-gateway-execution-data-control-plane-stage4-recovery-sequence-verification.md)。
恢复控制面回归执行 146 tests，0 failures、0 errors、0 skips；完整 Resource Gateway
`clean verify` 执行 2298 tests，0 failures、0 errors、28 个既有条件跳过，并通过真实浏览器流程与
Spring Boot 可执行 JAR 打包。独立 test-kit `clean verify` 执行 77 tests，0 failures、0 errors、
0 skips，并通过普通/shaded JAR、权威 Schema 与 public Javadoc 门禁。

第三十七增量把 recovery sequence 的精确重放从“永久保存”升级为显式有限窗口。统一的版本化
key authority 负责 outer namespace、step、intermediate claim 与 automatic heartbeat 派生，执行与
retention 不再各自拼接字符串。absolute replay deadline 与独立完整性 activity fence 以同一 outer
row lock 消除在途 replay/维护删除竞态。数据库租约只允许一个副本处理一页：先验证 outer 与全部派生 child，
再写入 scope 绑定、domain-separated keyed-HMAC tombstone，随后以 exact fingerprint 删除 child/outer、
清理独立一页过期 tombstone、更新 aggregate counter 并释放 lease，全部处于同一个本地事务。坏 child、
stale fence、坏 tombstone、坏 retention authority 均整页回滚。默认 30 天 detail + 365 天 tombstone；
absolute replay deadline 后的同 intent 重试返回
`RG.TEST.DURABLE_RECOVERY_SEQUENCE_REPLAY_WINDOW_EXPIRED`，异 intent 继续冲突，墓碑到期才允许复用。
由于 plaintext request id 不进入 tombstone，HMAC 轮换只能全副本 append new key、确认 fleet 后切 active、
等待旧墓碑到期、再移除 old key；启动时发现 referenced generation 缺失会 fail closed，内建 cohort proof
仍是后续缺口。staging 配置、capability、聚合日志
及固定基数 telemetry 均已接线，122 项聚焦反例全绿；完整 Resource Gateway `clean verify` 执行
2322 tests，0 failures、0 errors、2 个既有条件跳过，并通过真实浏览器流程和可执行 JAR 打包。验证见
[Stage 4 recovery-sequence verification](resource-gateway-execution-data-control-plane-stage4-recovery-sequence-verification.md)。
该增量不覆盖 sequence 之外的通用 durable command retention、法律保留、backup erasure、外部 WORM
或非 H2 容量/方言认证。

第三十八增量把“retention 已存在”推进为“retention 失效可被 readiness 可靠阻断”。repository 在
repeatable-read 事务内用同一数据库时钟返回 last success、当前 detail/tombstone 总量、已满足 replay
deadline 与 activity fence 的 sequence backlog、已过期 tombstone backlog 及最老时间。sequence 年龄从
真实 eligibility `max(createdAt + commandRetention, activityUntil)` 起算，避免把 30 天合法 replay window
误报成 30 天积压；观测所用 command window 必须与 repository policy 精确相等，配置漂移直接失败。
独立 SLO monitor 将首次启动、最近成功新鲜度、两类 backlog 数量/年龄收敛为 `HEALTHY`、
`INITIALIZING`、`SLO_VIOLATED`、`STORE_UNAVAILABLE`，分别映射 Actuator
`UP/UNKNOWN/OUT_OF_SERVICE/DOWN`。数据库不可用 fail closed，telemetry 不可用不污染已得到的健康结论；
health/metrics 仅暴露稳定 code、聚合计数和数据库时钟 age，不含 tenant/run/request/payload/key/error。
test/staging profile、七项阈值配置、capability 与固定基数 gauges 已接线，113 项 SLO/repository/profile
聚焦测试全绿；完整 Resource Gateway `clean verify` 执行 2329 tests，0 failures、0 errors、2 个既有
条件跳过，并通过真实浏览器流程和可执行 JAR；独立 test-kit 执行 77 tests 全绿，并通过普通/shaded
JAR 与 public Javadoc。该增量解决的是“维护任务静默死亡”病根，不替代外部告警路由、多副本跨库
见证、容量认证、法律保留或 backup erasure。验证见
[Stage 4 recovery-sequence verification](resource-gateway-execution-data-control-plane-stage4-recovery-sequence-verification.md)。

Stage 4 最新增量把 fresh initial boundary 收敛为唯一持久化 signal wait，并在该静止点同时
冻结 fixture cursor 与四 store closure；终态、pause、timer/work-item/stream 及多 suspension 在
repository commit 前 fail closed。数据库 creation command reservation 已补齐 scoped idempotency、
数据库时钟 lease、过期 fencing 接管、不可变 rejection/result replay，以及 initial checkpoint、
四 store mutation 与 audit 的原子提交；公开 authenticated create adapter 已进一步绑定 exact
GRAPH/fixture、principal 与完整 dependency authorization，并只发布首个唯一 signal suspension。
进程内 coordinator 以数据库时钟和 exact owner/epoch/record fingerprint CAS 自动续租；进入
commit/reject 前先冻结心跳并使用最新 successor，续租失败或关停时丢弃 staged 状态。
它仍是窄创建协议，不是 dispatcher 或完整 durable worker 产品。

第二十一增量补齐 operator-target durable creation，但没有把图协议 v1 偷换为联合类型。
`bloge.durableOperatorTestExecutionCreateRequest.v1` 只接受 path/body 完全一致的 exact OPERATOR
target、`OPERATOR_UNIT_TEST`、形式化 input 与 exact stored fixture；服务端先按冻结 metadata 做输入
coercion，再把值放入隔离 `operatorInput` context。canonical durable micro-graph 由只读、幂等的
`durable-operator-start` source 和下游 exact `subject` 组成：fresh create 必须先在 start gate 形成唯一
signal suspension，因而 revision-zero checkpoint 原子提交前绝不会调用业务算子；cold terminal recovery
只负责放行该 gate，signal data 不替代业务 input，subject 随后按同一 binding/fixture/provider/authority
闭包执行。该入口复用 graph create 的幂等命令、四维 admission、数据库时钟 preparation lease、四
store staged aggregate、原子 audit/query/claim/heartbeat/terminal recovery；独立 runtime 测试证明冷
恢复后 subject 恰好调用一次。内部 gate 也计入 operator admission inventory，这是偏保守的容量语义。

第二十二增量提供公开的 payload-free worker pull acquisition。请求只含版本和 caller-stable key；
认证 principal 决定 tenant/org/project/environment，部署策略决定 owner、lease 与 1..1,000 的候选窗。
数据库时钟 oldest-expiry-first SQL、sealed checkpoint 回验、逐候选 exact dependency reauthorization
之后，repository 在一个事务中完成 source fence CAS、hidden dispatch、`ACQUIRED` 结果和 semantic
audit；无可领取项则原子提交 database-timed `NO_WORK` 与审计。幂等物理键包含 org/project，因此同租户
多项目不会互相占 key；`NO_WORK` 也不可变，新观察必须使用新 key。store/authority 故障整体 503，不能
伪装为空队列。响应不含 dispatch、fixture、context、provider cursor 或 engine state。该协议闭合的是
remote control ownership，不是 runtime-state offload、long poll、公平 scheduler 或跨进程 supervisor。

第二十三增量关闭 bounded oldest-first 窗口的毒化前缀饥饿。每个 tenant/org/project/environment
scope 只维护一个持久化循环 keyset 游标，顺序固定为
`(leaseExpiresAt, updatedAt, runId)`；游标读取、尾段查询与按需回卷头段共享数据库时钟
`REPEATABLE_READ` 快照。每个候选携带内部 compare-and-advance token，游标仅随最终
`ACQUIRED/NO_WORK`、audit 及可选 lease CAS/hidden dispatch 同事务推进到最后实际检查项；authority
基础设施故障不推进。独立内容寻址 scope key 使投影漂移无法隐藏 cursor row，whole-record fingerprint
拒绝篡改；陈旧并发 token 只能 no-op，不能倒退新游标。有限稳定队列中的完整不可授权前缀因此不再
永久遮蔽后续工作；该增量交付时尚无 tenant weighting、priority/aging、公平队列或候选退避。
游标、repository、service、controller、profile、protocol 与 capability 联合聚焦门禁执行 80 tests，
0 failures、0 errors、0 skips。本增量完整 Resource Gateway `clean verify` 执行 2152 tests，
0 failures、0 errors、2 个既有浏览器条件跳过并完成 Spring Boot JAR 打包；独立 test-kit
`clean verify` 执行 63 tests，0 failures、0 errors、0 skips，并通过 public Javadoc 与 shaded JAR
校验。

第二十四增量治理循环扫描中的确定性失败热点。只有 legacy/target-less checkpoint、exact
authorization `403` 和 `409` 三类闭集原因可以为 exact checkpoint fingerprint 建立临时负调度缓存；
authority/store `5xx` 继续整体 fail closed，不提交结果、游标或退避。首次失败按数据库时钟写入初始
延迟，同原因到期重试按 2 倍增长至部署上限；活动退避跳过 authority 调用但仍推进循环扫描。只有赢得
cursor compare-and-advance 的 token 能写入或放大计数，陈旧并发 token 无权改变退避；checkpoint
fingerprint 变化、成功 claim 或普通 checkpoint update 立即清理旧记录。scope projection、reason、
计数和时间都由 whole-record fingerprint 回验，退避、cursor、可选 lease/dispatch、幂等结果和 audit
同事务成败。全局 SLO 仅按 closed reason 聚合总量/活动量，并观测 retry-due、最大连续失败和最老活动
年龄，不输出 scope/run/checkpoint。该增量是有界临时抑制，不是永久 quarantine、dead-letter 或人工
处置工作流。repository/service/SLO/profile/capability 联合聚焦门禁执行 91 tests，0 failures、0 errors、
0 skips；完整 Resource Gateway `clean verify` 执行 2162 tests，0 failures、0 errors、2 个既有浏览器
条件跳过并完成可执行 JAR 打包；独立 test-kit `clean verify` 执行 63 tests，0 failures、0 errors、
0 skips，并通过 public JavaDoc、schema 打包与 shaded CLI 校验。

第二十五增量把达到连续同原因阈值的 exact checkpoint 从临时 deferral 原子转换为独立 active
quarantine。只有 cursor CAS 胜者能创建隔离；记录绑定完整 scope projection、checkpoint fingerprint、
closed reason、阈值/计数、数据库时间和 whole-record fingerprint，候选页按有界批次投影。隔离候选
仍推进循环扫描，但不再授权或被 worker claim；repository 在 lease CAS 前二次校验，显式 fenced
checkpoint transition 则清理旧 fingerprint。全局 SLO 仅输出 closed reason 数量、最大失败数与最老
年龄。它关闭了永久毒化 closure 的自动回流，不等于 maintenance list/claim/release/discard、不可变
resolution receipt 或历史 dead-letter evidence 已完成。
本增量聚焦门禁执行 100 tests 全绿；完整 Resource Gateway `clean verify` 执行 2171 tests，0 failures、
0 errors，34 个既有浏览器条件跳过并完成可执行 JAR；独立 test-kit `clean verify` 执行 63 tests 全绿，
并通过 public JavaDoc、schema 与 shaded CLI 校验。

第二十六增量把 active dead-letter 接入专用治理协议。认证 identity 唯一决定 scope/owner，
`test/staging` 下还必须满足 exact maintenance purpose、operator group 和 clearance；公开面只返回
payload-free quarantine/history。claim 与 `RELEASE`/`DISCARD` 用 server token、version、owner、
caller-observed expiry 和 database clock 精确 fencing，并按 checkpoint authority -> quarantine/control
顺序锁定。命令支持 caller-stable exact replay、拒绝同键异意图；首次状态、receipt、token-free audit 与
immutable history 同事务。`RELEASE` 只释放维护 ownership，`DISCARD` 才移除 exact worker suppression。
SLO 已增加维护 state、expired claim、history aggregate 与稳定过期 claim code。该能力仍无四眼审批、
claim-command token 加密/有界保留、外部 WORM、webhook 和 alert routing。
本增量聚焦 37 tests（含 checkpoint authority 锁后的并发 exact retry）、Resource Gateway 全量
2190 tests 和独立 test-kit 63 tests 全绿；全量中的
34 个 skip 均为既有条件浏览器用例，可执行 JAR、权威 schema、shaded CLI 与 public JavaDoc 门禁通过。

第二十七增量把 `DISCARD` 从单人高权限命令收敛为数据库权威的 maker/checker 协议。maker 先持有
server token/version/expiry claim；独立 checker 只看 payload-free owner/version/expiry fence，审批寿命
最多 900 秒且不超过 claim，协议结构上不向 checker 暴露 token。maker 必须以原 claim、同一 reason 和
approvalId 原子消费审批；checkpoint authority、quarantine/control、approval 按固定顺序加锁，审批消费、
隔离删除、幂等 receipt、双人 history 与 audit 同事务。maker/checker actor 必须不同并分别满足 operator/
approver deployment group。新 legacy direct `DISCARD` 稳定拒绝，历史精确 replay 兼容。审批、命令与
历史有 whole-record fingerprint；并发不同命令只允许一个消费，篡改、过期、自审批、reason 漂移及 audit
失败全部 fail closed。Schema/capability/profile/JavaDoc/SLO/手册同步，健康新增 expired approval code，
metrics 只输出 live/expired approval 和双人历史计数。该能力是进程内数据库职责分离，不是外部工单、
JIT 特权、设备会话保证或 WORM 审批链；token 加密/保留、审批 retention、workflow binding 和非 H2
认证仍待完成。
本增量聚焦 48 tests（数据库 authority 18 tests）、Resource Gateway 全量 2201 tests 和 test-kit
63 tests 全绿；全量 34 个 skip 为既有条件浏览器用例，可执行 JAR、权威 Schema、shaded CLI 与
public JavaDoc 门禁通过。

第二十八增量保护 claim 精确重放所需的 secret fence。新命令只持久化 AES-256-GCM envelope，随机
nonce、认证 tag 和稳定 AAD 绑定 scope/request/run/checkpoint/version/expiry，v2 whole-record
fingerprint 不再含明文 token。启动按 1000 条稳定分页锁迁移合法 v1 明文并清空旧列，同时把非
active-key envelope 认证后重包；未知 key、tag/AAD/fingerprint 漂移、短 key、重复 key 或缺 active
key 都 fail closed。轮换采用两阶段：全副本先配置新 decrypt key，再切 active key并完成启动重包，
最后才移除旧 key。`staging` 强制外部注入 key ring，`test` 本地默认不得复用；capability 新增窄语义
`encryptedDurableWorkerQuarantineClaimReplay`。这关闭的是命令重放副本的明文暴露，不是 KMS/HSM
托管、active control fence hashing、命令/审批/历史 retention、外部 WORM 或 workflow binding。
本增量联合聚焦门禁执行 56 tests 全绿，其中数据库 authority 22 tests、token protector 4 tests；
Resource Gateway `clean verify` 执行 2209 tests，0 failures、0 errors、2 个条件跳过并完成可执行 JAR；
独立 test-kit `clean verify` 执行 63 tests 全绿，并通过权威 Schema 打包、shaded CLI 与 public JavaDoc。

第二十九增量把 worker-quarantine command、approval、history 和 request identity 接入数据库权威的
三窗口保留协议。四类详细 replay 行到期时，系统先验证 whole-record fingerprint；claim 行还必须成功
认证 AES-GCM envelope，随后在同一事务写入不含原始 request ID/token 的 request-key tombstone 并精确
删除来源。两类 history 与 tombstone 使用独立 deadline 物理删除。singleton database-clock
owner/token/epoch lease 提供跨副本单 owner 与接管 fence，每 tick 七类各最多一页；任一来源/墓碑篡改、
过期旧 fence、删除计数漂移或存储错误都使整页回滚。详细 replay 已删除时，精确重试返回稳定
replay-window-expired `409`，异意图继续冲突，tombstone 到期后才允许 ID 复用。固定基数 metrics 只
暴露 closed result 和 aggregate counters，post-commit telemetry 故障不会篡改数据事务结论。该实现
关闭无界表增长和 request resurrection，不等于 archive、法律保留、backup erasure 或外部 WORM。
本增量 retention 聚焦门禁执行 51 tests 全绿，其中数据库 authority 31 tests；Resource Gateway
`clean verify` 执行 2223 tests，0 failures、0 errors、34 个既有条件浏览器跳过并完成可执行 JAR；
独立 test-kit `clean verify` 执行 63 tests 全绿，并通过权威 Schema、shaded CLI 与 public JavaDoc。

第三十增量关闭 active control 中第二份明文 bearer token。新 v2 control 清空兼容列，只保存 key ID
和 `v1.<base64url HMAC-SHA-256>`；MAC 子键从现有 AES root key 以固定 key context 派生，消息再以
独立 context 和长度前缀绑定 scope/run/checkpoint/owner/state/version/expiry/token，resolve 与 approved
discard 使用常量时间比较。启动先完成 claim-command AES 重包，再按带索引的 1000 条稳定页迁移
control：仍存活的 v1 `CLAIMED` 与旧 key MAC 必须找到唯一 scope/run/checkpoint/owner/version/expiry
命令，解密出的 token 必须与旧明文或旧 MAC 一致，随后以 record fingerprint CAS 清明文并写
active-key MAC；`AVAILABLE` 可直接升级，已过期物理 `CLAIMED` 用数据库时钟规范化为同版本
`AVAILABLE`，避免 retention 合法删除 replay command 后阻断未来轮换。live 命令缺失/歧义、未知旧
key、待迁移行的 MAC/整行篡改或跨记录不一致全部阻止 readiness 并回滚当前页。capability 以窄语义
`hashedDurableWorkerQuarantineActiveFence` 发布。该能力消除数据库
只读者直接取得 live bearer 的路径，但 root key 与数据库同时泄露仍可从 replay envelope 恢复 token；
它不是 KMS/HSM custody、进程隔离、外部 WORM 或 backup erasure。
本增量联合聚焦门禁执行 72 tests 全绿，其中数据库 authority 35 tests、token protector 6 tests；
Resource Gateway `clean verify` 执行 2229 tests，0 failures、0 errors、34 个既有条件浏览器跳过并完成
可执行 JAR；独立 test-kit `clean verify` 执行 63 tests 全绿，并通过权威 Schema、shaded CLI 与
public JavaDoc。

第三十一增量关闭 request tombstone 中低熵 `clientRequestId` 的离线枚举面。新 v2 行只保存独立
request-index key ID、`v1.<base64url HMAC-SHA-256>` 和 `recordVersion=2`；专用 32-byte root 先以固定
KDF context 派生，再以独立 message context 和长度前缀绑定 operation、认证 scope 与 request ID，
不复用 claim-token root。key ring 固定上限 16 代，新写只用 active key，精确查询有界计算
active/old/legacy 候选并做常量时间验证；命中 old key 或 legacy SHA 行时，在行锁内按整行 fingerprint
CAS 重键。启动检查所有未过期 v2 行的 key generation，缺 key 即 fail readiness；已过期行不再参与
幂等查找，可在 retired key 缺失时按整行完整性有界清理。因为 legacy 行刻意不含原始 request ID，
不能离线批量迁移，只能在精确访问时升级或等待到期，这是明确兼容边界。capability 新增
`keyedDurableWorkerQuarantineRequestIndex`。该能力抵御 database-only dictionary attack，但不覆盖
process/root compromise、database+key 联合失陷、KMS/HSM custody、备份擦除或多区域轮换认证。
key generation 可在线轮换，但旧 binary 无法读取新 v2 行；首次 legacy-to-v2 升级必须暂停 maintenance
写入/retention、排空在途命令并完成全副本升级后再恢复。真正零停机 N/N-1 rollout 仍需显式
`LEGACY_READ_WRITE -> DUAL_READ_KEYED_WRITE -> KEYED_ONLY` 状态协议与跨版本 conformance。
本增量联合聚焦门禁执行 81 tests 全绿，其中数据库 authority 40 tests、request-index protector
4 tests；Resource Gateway `clean verify` 执行 2238 tests，0 failures、0 errors、34 个既有条件浏览器
跳过并完成可执行 JAR；独立 test-kit `clean verify` 执行 63 tests 全绿，并通过权威 Schema、shaded
CLI 与 public JavaDoc。

第三十二增量把 first keyed-write cutover 从人工假设收敛为 staged application-binary 协议。
`LEGACY_READ_WRITE` 在 N/N-1 共存期继续产出 N-1 可查的 v1，并以“启动时零 live v2”阻止错误回滚
承诺；`DUAL_READ_KEYED_WRITE` 只写 keyed v2、同时有界读取 v1/v2 并在 exact access 时 CAS 迁移；
`KEYED_ONLY` 要求启动时 live v1 为零，运行中发现回流 v1 也 fail closed。三态闭集由配置解析、
database readiness、write path 和 lookup path 共同执行。capability 保持 v1 `testability` 对象形状
不变，新增 staged-protocol marker 和三个互斥 mode flag，production 全 false；staging launcher
要求显式 canonical mode。部署顺序必须是：N 先以 legacy
与 N-1 共存，平台逐实例证明所有 serving binary 均为 N，再进入 dual；等待 v1 随 exact retry 迁移
或自然过期后才进入 keyed-only。该协议允许零 maintenance-write 停机升级，但 keyed-write 开始后
禁止回滚 N-1。单副本 readiness 不等于 fleet attestation；未注册旧进程、跨区域配置传播、真实旧
制品 conformance 与签名 deployment inventory 仍由企业发布系统证明。本增量联合聚焦门禁执行
59 tests 全绿，其中数据库 authority 45 tests、request-index protector 4 tests、mode parser 2 tests；
Resource Gateway `clean verify` 执行 2246 tests，0 failures、0 errors、2 个既有条件跳过并完成可执行
JAR；独立 test-kit `clean verify` 执行 63 tests 全绿，并通过权威 Schema、shaded CLI 与 public
JavaDoc。脚本语法、staging 缺失/非法 mode 拒绝以及 JAR 内容检查亦通过。

第三十三增量把“逐副本 mode 可观察”升级为“逐副本事实可签名、完整 cohort 可离线拒伪”。
test/staging-only proof endpoint 以同一 DB 时钟快照签入 challenge、identity-derived deployment
scope、deployment-supplied instance/artifact、process-start UUID、协议/当前/目标 mode、live legacy/keyed
generation inventory、闭集 blocker 与短 TTL；proof 即使 blocked 也保持有效签名，签名或审计失败则不
降级为 unsigned 响应。独立 test-kit 不依赖服务端实现类：严格解析打包 Schema，保留 exact wire
material 重算 canonical fingerprint，以调用方独立提供的 exact serving instance set 做集合等值校验，
拒绝 missing/unexpected/duplicate instance、duplicate startup、过宽 cohort、过期/未来 proof、scope/
artifact/protocol/mode 漂移、inventory 矛盾、blocker、坏 key-set pin、非 active key 与坏 Ed25519
signature。这个增量关闭的是“已给定精确清单的确定性聚合”，不是应用自发现 fleet；未注册、分区、
shadow 或 N-1 进程是否仍 serving，direct URI 是否绕过负载均衡，artifact digest 是否真实，仍必须由
部署平台的独立 inventory/registry 证明。验证见
[Stage 4 request-index replica-proof verification](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-request-index-replica-proof-verification.md)。
本增量聚焦验证执行 test-kit 31 tests、服务端 rollout service 6 tests、catalog 启动回归 2 tests，
全部通过；独立 test-kit `clean verify` 执行 74 tests，0 failures、0 errors、0 skips，并通过权威
Schema、shaded CLI 与 public JavaDoc；Resource Gateway `clean verify` 执行 2257 tests，0 failures、
0 errors、2 个既有条件跳过并完成可执行 JAR。

第三十四增量已关闭 Resource Gateway 内“应用内 checker 被误当成企业变更审批”的授权根缺口。新增独立
`WorkerQuarantineChangeAuthorizationTrustStore`，以外部配置的 Ed25519 公钥、精确 policy fingerprint
和 M-of-N distinct-authority threshold 验证短期 `WORKER_QUARANTINE_DISCARD` 授权；签名材料只包含
opaque authorization id、identity-derived scope fingerprint、精确 mutation subject fingerprint 与时间窗，
不包含 ticket 文本、scope/actor 原值、claim token、credential 或业务 payload。模型和 verifier 已以
8 个真实签名测试覆盖 quorum、binding/policy/time/material/signature/key lifecycle/config 反例。随后
新增独立授权 authority table，以数据库时钟复核窗口、authorization
id/material fingerprint 双重唯一预留，并在销毁事务中连同 checker approval 原子消费，同时将外部引用
写入 v2 approval/receipt/command/history 指纹与证据。第三阶段把签名信封、公开 canonical scope/subject
前像和 verifier 接入 checker HTTP API，严格 v2 Schema 强制信封，staging 四项 trust 配置缺一即拒绝
启动，capability 区分 endpoint 存在与外部 trust ready；精确已提交请求先由数据库 intent fingerprint
重放，避免授权过期或 trust 暂时不可用破坏 lost-response 幂等。旧 v1 行保持原指纹可读但不能再驱动
新销毁。尚未闭合的是外部工单全生命周期、动态密钥/撤销刷新、设备会话保证、break-glass、外部 WORM
和治理回调，不再是 Resource Gateway 的签名决策执行路径。持久化控制面 51 tests 已覆盖重复占用、
窗口、审计回滚、旧审批拒绝和证据保留。该增量联合聚焦门禁执行 84 tests，最终持久化/服务
回归执行 65 tests；Resource Gateway `clean verify` 执行 2273 tests，0 failures、0 errors、2 个既有
条件跳过并完成可执行 JAR；独立 test-kit `clean verify` 执行 74 tests，0 failures、0 errors、0 skips，
并通过权威 Schema、shaded CLI 与 public JavaDoc。
验证见
[Stage 4 worker-quarantine change-authorization trust verification](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-change-authorization-trust-verification.md)。

同步 terminal recovery 的进程内 coordinator 现在先用原 dispatch 完成一次认证 heartbeat，确保
BLOGE 不会在临近过期 fence 下启动；运行中只沿服务端签发的 successor 链周期续租，并逐次验证
authorization、target、fixture、provider、engine、owner/epoch 闭包不漂移。终态提交前先冻结并等待
在途 heartbeat，再把最新 successor 交给 repository CAS。续租冲突、存储异常、畸形 successor 或
服务关停均关闭 staged runtime、拒绝终态写入并返回 payload-free lease-loss。这个增量解决同步
执行窗口的 ownership 活性，不等于 worker 队列、跨进程监督、multi-boundary orchestration 或 hard
cancellation。

worker 扫描持久化面已将 ready work、过期 work-item claim 与过期 execution lease 的
tenant/namespace、状态、可选 shard、时间、排序和有界 limit 下推到 SQL，并对每个候选的调度投影
与权威 JSON 做 fail-closed 回验。默认页 100、硬上限 10,000；全局和 tenant-scoped 复合索引均已
建立。公开 worker acquisition 对 control checkpoint 使用更窄的默认 32、硬上限 1,000 窗口，并复用
数据库时钟、SQL 前置过滤和 authority 回验；持久化循环 keyset 游标保证稳定毒化前缀后的候选最终
可达，且 scope/position 篡改与并发回退 fail closed。热路径仍无法发现 checkpoint 自身被错误投影隐藏
的候选，该问题由下述独立反熵循环处理。测试执行的即时四维配额已由独立 admission authority 执行；
确定性候选临时退避、exact-checkpoint 自动 quarantine、专用人工处置、token-free history 与全局压力
观测和 maker/checker approved discard 已实现；claim-command replay token 加密、旧行迁移和轮换重包
以及 active-control HMAC fence、旧行迁移和轮换重键已实现；command/approval/history 有界 retention
与独立 HMAC request-index tombstone、在线轮换、旧行惰性迁移、live-key readiness 及 N/N-1
三阶段 write/readiness/capability 协议已实现；外部审批绑定、
法律保留/备份擦除证明、外部 WORM、runtime-state dispatch、排队/
公平/优先级 backpressure 与跨进程 worker supervisor 仍未提供。

隐藏候选型投影漂移现已由独立 system-level keyset 反熵循环覆盖。它不使用待审计的调度谓词，而按
execution/work-item 主键各自有界轮转；默认每表 100 行、60 秒一轮、`REPAIR_DERIVED`。JSON 仍是
authority，修复以 row identity + tenant/namespace + work-item execution ownership + 原始 payload
做 CAS；只有上述安全边界一致的派生列可自动重建，主键/归属/scope 漂移和不可读 authority 只报告。
单行坏数据不会阻断后续页；repair、finding lifecycle 与双 cursor checkpoint 在同一事务提交，存储
失败整体回滚。双游标和 database-clock owner/token/epoch sweep lease 已持久化，多副本只有一个 page
owner；payload-free finding owner queue 以 server token/version/owner/expiry 做 claim/resolve fencing，
一致性复查可自动关闭历史 finding。认证 operations adapter 现只在 `test`/`staging` 装配，并同时要求
`TEST_RUNTIME_MAINTENANCE`、deployment-owned global operator group 与最低 clearance；owner 只能来自受信
actor。claim/resolve 以 caller-stable key + canonical intent receipt 实现精确重放，同键异意图拒绝；首次
状态迁移与 token-free semantic action event 在同一 test-runtime 事务提交，审计失败即回滚。读取、拒绝
和 replay 也追加事件，claim token 只出现在成功 claim response。日志仍只有聚合计数。resolved finding
现由第二条 database-leased retention loop 处理：active/archive 两级保留期和每阶段页大小均有界；
token-free archive insert、exact source delete、archive purge 与 counter checkpoint 同事务，跨副本单
owner，失败整体回滚；archive read 复算 canonical whole-record fingerprint。第十八增量已用同一数据库
事务和数据库时钟形成 payload-free operational snapshot，以稳定 violation code 提供 Actuator health，
并以固定 `result/state/tier/loop` 标签提供 reconciliation/retention attempt、duration、finding state、
backlog、last-success age 与 health 指标。第十九增量进一步用数据库时钟和一个只读
`REPEATABLE_READ` 事务聚合全局 child/suite evidence completeness、suite/creation/durable/work queue、
expired ownership、oldest age 与 retention/terminal backlog；Actuator 只返回稳定 violation code 和
聚合值，Micrometer 只使用固定 `status/queue/scope/kind` 标签，业务断言与被测系统失败不触发平台
失活。authority table 已增加 lifecycle/time 运维索引，观察窗硬上限 365 天。第二十增量进一步在
同一独立数据库中提供 tenant/suite/operator/dependency 四维全有或全无 admission、database-clock
renewable lease、exact fencing、固定 4096 请求锁条带、bounded expiry cleanup 和关闭释放。所有
engine-starting path 均在 control-plan/authorization 后获取 permit；suite 父运行一次保留完整闭包，child
不重复获取。仍缺排队/公平/优先级 scheduler、runtime-state remote worker dispatch/supervision、hard
cancellation、外部 alert routing、外部 WORM/tamper-evident audit/archive anchoring、非 H2 方言与
生产负载认证。

实现边界、错误语义、配置和 96 项聚焦证明见
[Stage 4 runtime admission verification](resource-gateway-execution-data-control-plane-stage4-runtime-admission-verification.md)。
Operator durable create 的启动门、原子性、冷恢复和反例证明见
[Stage 4 operator durable creation verification](resource-gateway-execution-data-control-plane-stage4-operator-durable-creation-verification.md)。
Worker pull 的 scope、数据库时钟、幂等空结果、原子 claim/dispatch/audit 和反例证明见
[Stage 4 worker acquisition verification](resource-gateway-execution-data-control-plane-stage4-worker-acquisition-verification.md)。
循环游标的有限队列活性、回卷、并发不回退、投影防篡改与事务回滚证明见
[Stage 4 worker scan cursor verification](resource-gateway-execution-data-control-plane-stage4-worker-scan-cursor-verification.md)。
确定性候选退避的失败闭集、数据库时钟、并发不放大、SLO 与诚实边界见
[Stage 4 worker candidate backoff verification](resource-gateway-execution-data-control-plane-stage4-worker-candidate-backoff-verification.md)。
永久隔离维护与双人销毁的协议、线性化、证据和诚实边界见
[Stage 4 worker quarantine maintenance verification](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-maintenance-verification.md) 与
[Stage 4 worker quarantine two-person discard verification](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-two-person-discard-verification.md)。
三窗口保留、request-key tombstone、跨副本 lease、事务回滚与 telemetry 故障域证明见
[Stage 4 worker quarantine retention verification](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-retention-verification.md)。
低熵 request ID 的 keyed HMAC 索引、独立 key custody、在线轮换、readiness 与 legacy 迁移边界见
[Stage 4 worker quarantine request-index protection verification](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-request-index-protection-verification.md)。
N/N-1 三阶段迁移、challenge-bound 逐副本签名与独立 exact-inventory cohort gate 见
[Stage 4 request-index rolling-upgrade verification](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-request-index-upgrade-verification.md) 与
[Stage 4 request-index replica-proof verification](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-request-index-replica-proof-verification.md)。

实现细节、行为兼容决策和可复现测试见
[v1 实施蓝图](resource-gateway-industrial-testability-evolution-plan-1.0.md) 与
[Stage 1 verification](resource-gateway-execution-data-control-plane-stage1-verification.md) 与
[Testing Control Plane API](resource-gateway-testing-control-plane-api.md) 与
[Stage 2 test-kit verification](resource-gateway-execution-data-control-plane-stage2-test-kit-verification.md) 与
[Stage 2 operator adapter verification](resource-gateway-execution-data-control-plane-stage2-operator-adapter-verification.md) 与
[Stage 2 suite registry verification](resource-gateway-execution-data-control-plane-stage2-suite-registry-verification.md) 与
[Stage 2 suite runner verification](resource-gateway-execution-data-control-plane-stage2-suite-runner-verification.md) 与
[Stage 2 suite consumer adapters verification](resource-gateway-execution-data-control-plane-stage2-suite-consumer-adapters-verification.md) 与
[Stage 2 Canvas suite publication verification](resource-gateway-execution-data-control-plane-stage2-canvas-suite-publication-verification.md) 与
[Stage 2 dogfooding verification](resource-gateway-execution-data-control-plane-stage2-dogfooding-verification.md) 与
[Stage 2 catalog materialization verification](resource-gateway-execution-data-control-plane-stage2-catalog-materialization-verification.md) 与
[Stage 2 logical-time verification](resource-gateway-execution-data-control-plane-stage2-logical-time-verification.md) 与
[Stage 2 suite-run reconciliation verification](resource-gateway-execution-data-control-plane-stage2-suite-run-reconciliation-verification.md) 与
[Stage 3 signed test evidence verification](resource-gateway-execution-data-control-plane-stage3-signed-test-evidence-verification.md) 与
[Stage 3 suite attestation verification](resource-gateway-execution-data-control-plane-stage3-suite-attestation-verification.md) 与
[Stage 3 key lifecycle verification](resource-gateway-execution-data-control-plane-stage3-key-lifecycle-verification.md) 与
[Stage 3 evidence trust transparency verification](resource-gateway-execution-data-control-plane-stage3-evidence-trust-transparency-verification.md) 与
[Stage 3 semantic coverage verification](resource-gateway-execution-data-control-plane-stage3-semantic-coverage-verification.md) 与
[Stage 3 semantic gate basis verification](resource-gateway-execution-data-control-plane-stage3-semantic-gate-basis-verification.md)。北极星中的目标态能力未出现在上述
Done 行时，均不得从文档推断为产品已开放。

Stage 4 的 run-scoped provider、语义结果身份、脱敏后重算和 v1/v2 兼容证明见
[Stage 4 execution services verification](resource-gateway-execution-data-control-plane-stage4-execution-services-verification.md)。
组合检查点、同库事务参与、CAS 围栏和故障回滚证明见
[Stage 4 durable checkpoint verification](resource-gateway-execution-data-control-plane-stage4-durable-checkpoint-verification.md)。

恢复控制面进一步补齐了公开 owner claim：tenant/environment-scoped `clientRequestId`、规范化授权
意图指纹、精确旧 fence、server-owned claimant/lease、lease CAS、不可变结果快照与 `ALLOWED` 语义
安全事件共享一个本地事务。授权器不再只返回布尔结论，而是返回 exact graph/micro-graph、冻结
`CompiledExecutionControl` 与 payload-free `bloge.durableTestRecoveryAuthorization.v1`；receipt 绑定 source
checkpoint、认证 principal（含 region）、target/plan/fixture/replay/provider/authority 指纹、purpose 与 side-effect policy。
repository 在同一 claim 事务签发 `bloge.durableTestRecoveryDispatch.v1`，把 receipt 与结果 scope、
engine execution、owner/epoch/revision/expiry/checkpoint fence 绑定；升级后的
`bloge.durableResumeCommandRecord.v2` 同时覆盖 authorization、checkpoint 与 dispatch 指纹。响应丢失后的
同意图重试优先返回原始 checkpoint + dispatch，不因当前依赖漂移改变已经提交的结果；
同键异意图、跨 scope 探测、双实例并发、索引投影漂移与结果 JSON/指纹篡改均 fail closed。
公开 adapter 只在 `test`/`staging` 存在，按认证身份计算指纹，并重新授权 exact
target/fixture/replay/identity/side-effect/provider/plan 闭包。它现在建立 `RESUMING` fence 和不可转移的
worker dispatch receipt。公开 `bloge.durableTestRecoveryHeartbeatRequest.v1` 只携带 exact predecessor
fence、checkpoint fingerprint 和 caller-stable key，由服务端从可信存储隐式解析 dispatch；它要求 tenant/
org/project/environment/region/actor/delegation/purpose/clearance/groups 与 owner claim 的 principal 完全一致，
只排除 correlation id 以允许模糊响应重试。`RecoveryHeartbeatCommand` 只接受已提交 claim/前序 heartbeat
签发的 dispatch，以数据库时钟检查 live `RESUMING` fence，在同一事务把 revision 加一、延长 server-owned
lease、签发 successor dispatch、固化可幂等回放的 `bloge.durableRecoveryHeartbeatRecord.v1` 并提交
`ALLOWED` semantic audit；旧 dispatch、过期 lease、未签发 dispatch、principal 漂移、同键异意图、存储
篡改和伴随审计失败均 fail closed。heartbeat adapter 只续租，不执行引擎。公开
`bloge.durableTestTerminalRecoveryRequest.v1` 只接受 exact fence、caller-stable key、signal node 与最大
256 KiB 的显式 JSON data。服务先查询不可变终态 replay，再解析已签发 dispatch、校验原 principal、
加载 exact live checkpoint，并重建 graph/micro-graph、fixture/replay/provider/authority/plan；新旧
authorization receipt 必须相等。共享 compiled runtime 在隔离 session 中执行一个 signal，若再次
suspension 则丢弃 stage 并返回 409。只有 server-derived terminal outcome、fixture/provider/engine
closure 才能进入 `RecoveryTerminalCommand`，并与 BLOGE mutation、`TERMINAL` checkpoint、
`bloge.durableTestRecoveryTerminalReceipt.v1` 和伴随审计写入同一事务；相同 key 在执行前回放原结果，
不重复 signal 或 engine mutation。signal 原值不进入审计、response 或 receipt。由于断点前完整
node/edge/attempt trace 尚未持久化，v1 receipt 强制为 `EVIDENCE_INCOMPLETE`，并披露
`PRE_CHECKPOINT_TRACE_UNAVAILABLE` 与 `RECOVERY_SIGNAL_PAYLOAD_OMITTED`，只证明原子终态并阻断
promotion。公开 durable GRAPH/OPERATOR create 和 durable run query 已分别提供 exact
dependency/principal authorization、原子首 suspension 创建，以及完整性验证、跨 scope 隐匿且
payload-free 的 v1 view；operator path 以服务端 start gate 保证 checkpoint 先于业务 binding 执行。
现已提供 payload-free non-blocking worker acquisition、一次只消费一个 signal 的公开 recovery step，
以及预先保留完整 intent、逐边界重新 claim 的 1..16 signal 同步 recovery sequence；尚无 durable
signal inbox、runtime-state dispatcher、异步/无界多 suspension 调度、跨进程 recovery supervision
与完整 resume 编排。

动态 attempt/occurrence selector 的一基坐标、优先级、失败边界和真实 retry/nested re-entry
证明见 [Stage 2 dynamic selector verification](resource-gateway-execution-data-control-plane-stage2-dynamic-selector-verification.md)。
Semantic coverage 不修改已签名 v1 canonical shape，而通过 suite/evidence v2 双读演进，见
[ADR-003](adr/ADR-003-semantic-coverage-protocol-versioning.md)。
Exact semantic suite 到 ANEKE payload-free workbook seed 的投影、失败边界和 consumer 证明见
[Stage 3 ANEKE semantic workbook verification](resource-gateway-execution-data-control-plane-stage3-aneke-semantic-workbook-verification.md)。
Semantic workbook 到 ANEKE gate decision 的 exact evidence 重建、GraphDraft 编译 target 绑定与 v2 兼容证明见
[Stage 3 semantic gate basis verification](resource-gateway-execution-data-control-plane-stage3-semantic-gate-basis-verification.md)。

第二十增量后端全量验收：Resource Gateway `clean verify` 共 2121 tests、0 failures、0 errors、
34 个既有条件跳过，Spring Boot JAR 打包成功；runtime admission 相关 96 项聚焦验证全部通过。
第二十一增量后端全量验收：Resource Gateway `clean verify` 共 2131 tests、0 failures、0 errors、
2 个条件性浏览器跳过，Spring Boot JAR 打包成功；operator durable creation、cold recovery、
legacy reconstruction、schema/capability/controller 相关 45 项聚焦验证全部通过。独立 test-kit
`clean verify` 共 62 tests、0 failures、0 errors、0 skips，权威 testing-control-plane schema 已打入
普通与 shaded CLI JAR，JavaDoc 门禁通过。
第二十二增量后端全量验收：Resource Gateway `clean verify` 共 2146 tests、0 failures、0 errors、
2 个条件性浏览器跳过，Spring Boot JAR 打包成功；worker candidate scan、原子 acquisition、
幂等 `ACQUIRED/NO_WORK`、认证 service/controller、profile/schema/capability 相关 74 项聚焦验证
全部通过。独立 test-kit `clean verify` 共 63 tests、0 failures、0 errors、0 skips，普通与 shaded
CLI JAR、权威 schema 及 JavaDoc 门禁全部通过。
当前严格验收基线：Resource Gateway `-Pfrontend clean verify` 共 2103 tests、0 failures、0 errors、
0 skips，真实浏览器回归与 Spring Boot JAR 打包成功；Canvas suite 聚焦 68 tests、
前端全量 150 tests，并在桌面与 390 x 844 真实浏览器中完成两行一等 suite 发布；Canvas 对完整
stored suite value、child evidence、coverage、promotion 与 aggregate 一致性 fail closed；immutable TestSuite
runner/attestation/protocol 增量聚焦 49 tests；key lifecycle 增量聚焦 41 tests；动态 selector/capability/schema 增量聚焦 51 tests；typed semantic coverage/codec/registry/persistence/schema/capability 增量聚焦 52 tests；suite-run lease/reconciliation/profile 聚焦 22 tests；built-in catalog materialization 增量聚焦 34 tests；suite consumer adapter 聚焦 21 tests、独立 test-kit
`clean verify` 62 tests，均为 0 failures、0 errors，library/CLI JAR 均打包成功；semantic gate/projector/target/schema
增量聚焦 23 tests，integration package 138 tests 全绿；完整 suite/catalog/semantic workbook/gate v3 wire value 按打包的
Draft 2020-12 schema 校验并回绑 request identity，`RUNNING` 在无 polling CLI 中退出 2，
未知参数值与 validator 细节不进入日志，public JavaDoc 零告警且由 `verify` 门禁强制；Stage 4
durable checkpoint/aggregate/public payload-free query/owner-claim/internal recovery/authorization-bound dispatch/authenticated live-fence heartbeat/terminal commit/automatic terminal heartbeat/worker SQL scan/projection anti-entropy 聚焦 190 tests 全绿；其中 worker scan 的 3 个数据库测试证明 SQL 前置过滤、稳定排序、有界分页与候选投影漂移拒绝，7 个 scanner/scheduler 测试覆盖安全自愈、审计模式、安全域拒修、坏行隔离、双游标推进、authority CAS 竞态和调度器容错，新增 5 个 durable control-plane 测试覆盖跨副本持久游标、单 owner/过期接管/旧 fence 拒绝、repair+finding+cursor 原子回滚、finding claim/resolve fencing 与一致性复查关闭；authenticated finding operations/audit 的 persistence/service/controller/profile/capability/schema/application 组合聚焦 24 tests；finding retention/archive 的 database lease、两级有界生命周期、原子 rollback、whole-record fingerprint、profile/capability 组合聚焦 18 tests；projection SLO snapshot/health/telemetry/scheduler/profile/capability 聚焦 24 tests；global test-runtime SLO snapshot/health/telemetry/profile/capability 聚焦 11 tests，并连同实际 repository/schema 回归扩展为 72 tests；authenticated durable GRAPH creation 的 runtime/repository/authorizer/service/controller/schema/capability 组合聚焦 71 tests；creation preparation heartbeat 的 repository/coordinator/service/capability 组合聚焦 65 tests；本轮 automatic terminal-recovery heartbeat 的 coordinator/heartbeat-service/terminal-service/capability/Spring wiring 组合聚焦 33 tests，均全绿。

## 1. 结论先行

用户提出的方向是对的，而且可能成为 Resource Gateway 从“能编排”进入“敢交付”的决定性能力。DSL 化使图、节点、绑定和 schema 都变成可寻址对象，BLOGE 又已经具备 node 级 operator 替换、逻辑时间、mock operator、执行监听器和 snapshot testing 的基础，因此现在确实到了把测试能力产品化的时候。

但必须修正一个过于乐观的推论：

> 单个 operator 可测试，是 DAG 可测试的必要条件，不是充分条件。

即使每个 operator 都有完美单测，DAG 仍可能因以下问题产生业务错误：

- input binding 指向了错误的 context path；
- edge 连错、条件表达式错误或 decision table 规则顺序错误；
- 并行分支在 join 时丢失、覆盖或错误聚合数据；
- retry、timeout、fallback、skip、cancel 和 compensation 的组合行为错误；
- built-in function、时间、随机数、UUID、身份或 feature flag 使运行不可重复；
- operator 声称无副作用，实际却隐藏访问数据库、网络或全局状态；
- mock 没有命中、命中过宽或未被消费，测试却仍显示通过；
- 测试图、operator runtime binding、fixture 和最终证据不是同一个不可变版本。

因此目标不能只定义为“operator 可以填 mock output”，而应是：

```text
可寻址的执行依赖
  + 可版本化的 FixtureBundle
  + 运行前确定的 EffectiveExecutionPlan
  + 引擎级替换与故障注入
  + 业务语义覆盖率
  + 不可抵赖的 TestRunEvidence
  + 生产环境硬隔离
= 工业级可测试 Tool Authoring Runtime
```

## 2. 当前系统事实审计

### 2.1 已有基础

| 能力 | 当前代码事实 | 可复用价值 |
|---|---|---:|
| Operator schema table suite | `VisualOperatorContractTestService` 已支持 input/config/mock output schema 校验和 path 断言 | 高，适合作为 schema contract 层 |
| Resource graph contract suite | `GatewayGraphContractTestService` 运行真实 BLOGE graph，仅替换 descriptor-backed `httpResource` | 很高，已经接近 graph contract test |
| Visual simulation fixture | `VisualGraphSimulationService` 支持 persisted/request-scoped node fixture、mock output 和 expected input | 很高，是执行控制面的原型 |
| Golden regression | `VisualGraphGoldenCase` 绑定不可变 publication，并运行真实发布物 | 高，适合作为 promotion regression 层 |
| Run trace/evidence/replay | 已有 node attempt、status、payload governance、recorded replay 和签名 evidence | 很高，可承载测试证据 |
| BLOGE node replacement | `GraphEngine.executeWithOperators(...)` 可按 node id 注入 operator map | 高，但当前只是底层逃生口，不是稳定协议 |
| BLOGE test kit | `bloge-test` 已有 `MockOperator`、`GraphTestRunner`、`TestGraphEngine`、逻辑时间和 snapshot | 高，方法论和实现均可复用 |
| Formal graph contract | graph 级 input/output schema、operator fingerprint、dependency snapshot 已存在 | 高，可以冻结测试目标 |

### 2.2 当前最严重的认知缺口

#### 2.2.1 Operator Test Suite 目前没有真正执行 operator

现有 `VisualOperatorContractTestService#runCase` 校验的是：

```text
input fixture 是否满足 input schema
config fixture 是否满足 config schema
mockedOutputs 是否满足 output schema
assertion 是否能在 mockedOutputs 上通过
```

这证明了测试数据自洽，却没有证明 operator 实现正确。严格说，它当前是
**Operator Schema Fixture Validation**，不是 executable operator unit test。继续沿用“operator test 已跑通”的表述会制造错误安全感。

> 2026-07-15 落地校正：本节描述仍适用于 `/api/visual/operators/tests/*` 的持久化 schema suite；它继续诚实返回
> `SCHEMA_CONTRACT`。React Author Canvas 的 `Executable Operator Suite` 已改走公共 operator target discovery 与
> micro-graph execution，不再调用该 schema-only runner。`Run Case / Run Exploratory` 使用 inline fixture；
> `Publish Case + Run / Publish Suite + Run` 则为每行注册内容寻址 fixture，并把一行或多行发布为一等 immutable
> `bloge.testSuite.v1` 后执行精确 revision。两条入口并存，证据等级不得混用。

正确演进不是删掉它，而是把模式显式拆开：

| 模式 | 是否执行真实 operator | 证明什么 |
|---|---:|---|
| `SCHEMA_CONTRACT` | 否 | fixture 与 operator contract 自洽 |
| `EXECUTABLE_UNIT` | 是 | 指定 runtime binding 在受控输入下行为正确 |
| `ADAPTER_CONTRACT` | 是，连接 sandbox/test container | operator 与真实协议适配正确 |
| `DIFFERENTIAL` | 新旧版本都执行 | 升级前后行为差异符合预期 |

#### 2.2.2 Visual Simulation 是安全 mock run，但不是完整控制反转

当前 simulation 的策略是：transform、branch、decision table 等纯 DSL primitive 真实执行，其余 operator-invoking node 默认被 mock。这个默认对作者预览很安全，但存在四个限制：

1. 替换对象主要按 node id，缺少 operator/resource/function/call occurrence 等统一 selector。
2. fixture 主要是固定返回和 expected input，缺少 throw、delay、timeout、stream、retry attempt、调用次数等行为。
3. 没有运行前生成“最终到底替换了谁”的有效执行计划，无法发现零命中、歧义命中和过宽命中。
4. simulate evidence、stored suite、golden case 和 replay 仍是相邻模型，没有共享同一个 fixture/control protocol。

#### 2.2.3 `executeWithOperators(Map)` 不足以成为工业协议

BLOGE 当前 API 为测试提供了重要基础，但它有明显边界：

- `Map<String, ?>` 没有版本、目的、作用域、fingerprint 和审计语义；
- 主节点按 node id 覆盖，compensation 主要按 operatorRef 解析，寻址语义不统一；
- streaming operator 的解析顺序与普通 operator 不完全一致；
- nested graph、foreach body、durable resume 是否继承同一替换计划没有一等合同；
- operator map 无法描述第几次调用、哪次 retry、何种输入匹配和故障序列；
- built-in function 在 DSL 编译时被捕获为函数闭包，运行期 operator map 无法控制；
- durable store 尚未持久化测试控制计划与 provider-state；当前 compiler 已能对独立重建的
  plan/config 精确校验并恢复 `ExecutionServiceStateSnapshot`，但重启链路尚未接线。

结论是：保留 `executeWithOperators` 作为兼容 API，但新增一等 `ExecutionControlPlan`，不能继续扩大裸 `Map` 的职责。

## 3. 目标架构

![Resource Gateway 执行数据控制面目标架构](assets/resource-gateway-execution-data-control-plane.svg)

图源：[resource-gateway-execution-data-control-plane.drawio](assets/drawio/resource-gateway-execution-data-control-plane.drawio)。

目标架构把一次测试运行拆成两个阶段：

1. **Plan phase**：认证调用方，冻结 graph/operator/runtime/fixture 版本，解析 selector，做生产隔离和 side-effect policy 检查，生成不可变 `EffectiveExecutionPlan`。
2. **Execute phase**：BLOGE Engine 只消费已批准计划，按 invocation site 解析真实实现或 test double，并把每次命中、输入、输出、故障、调用次数和断言写入 trace。

真正重要的不是接口名称，而是以下边界：

- DSL 和 `GraphContext` 只承载业务数据；
- `ExecutionControlPlan` 是引擎带外控制；
- fixture 不能在 node 内部被读取、修改或传播；
- production run data plane 不能接受 test control plan；
- evidence 必须同时记录计划指纹和实际命中结果。

## 4. 领域模型与统一语言

| 对象 | 定义 | 是否不可变 |
|---|---|---:|
| `TestSuite` | 一组围绕同一测试目标组织的 case、coverage policy 和 promotion policy | revision 不可变 |
| `TestCase` | 一次确定性运行的输入、控制计划引用和断言集合 | revision 不可变 |
| `FixtureBundle` | 可复用的输入、依赖替身、故障脚本、逻辑时间和数据分类声明 | 是 |
| `ExecutionControlSpec` | 用户意图：哪些 invocation site 使用 REAL/STUB/MOCK/FAULT/REPLAY/SPY | 是 |
| `EffectiveExecutionPlan` | 服务端结合目标 artifact、环境 policy 和 runtime inventory 后解析出的最终计划 | 是 |
| `InvocationSite` | 一次可控制调用的稳定身份，不等同于简单 node id | 由 artifact 决定 |
| `TestDouble` | 返回、抛错、延迟、流式响应、录制或回放的受控实现 | 由 fixture 决定 |
| `TestRun` | 执行一个 case 后形成的运行实例 | 是 |
| `TestRunEvidence` | 对 target、fixture、plan、trace、assertion 和 coverage 的签名事实 | 是 |
| `TestCertification` | 多个 suite/evidence 满足某个 promotion policy 的结果 | 是 |

### 4.1 InvocationSite 不能只用 node id

稳定寻址至少需要：

```text
artifactFingerprint
graphPath                 # root/subgraph/foreach-body/compensation
nodeId
operatorRef
runtimeBindingFingerprint
invocationKind            # PRIMARY/COMPENSATION/FUNCTION/RESOURCE/SUBGRAPH
attempt                    # retry attempt，可选
correlationKey             # foreach item/business key，可选
occurrence                 # 同一 site 第几次调用，仅在顺序确定时使用
```

`graphPath + nodeId` 是主身份。`operatorRef`、resourceRef 和 tag selector 只适合批量声明策略，不足以单独形成审计身份。

## 5. 不可破坏的设计不变量

1. **测试控制永远不从 `GraphContext` 读取。** 业务 DSL 无法看到或修改 fixture。
2. **生产身份不能由请求字段声明。** `ExecutionPurpose` 必须由 endpoint、workload identity 和服务端 policy 联合铸造。
3. **Production purpose 下有效计划必须为空。** 任何 override、fault、logical clock 或 replay 行为都在执行前拒绝。
4. **零命中和歧义命中默认失败。** fixture 不是“尽力而为”的提示。
5. **required fixture 必须被消费。** 未消费 mock 表明路径没有按预期发生。
6. **每个真实调用和替换调用都必须可区分。** trace 不允许把 MOCKED 伪装成 SUCCESS。
7. **测试目标必须冻结。** artifact、operator、schema、runtime binding、fixture 和 plan 都有 fingerprint。
8. **控制计划必须在执行前完全解析。** 运行中不能临时读取可变 fixture 配置。
9. **durable resume 必须恢复同一计划。** 找不到原计划时进入 `CONTROL_PLAN_UNAVAILABLE`，不能回退为 REAL。
10. **外部写默认拒绝。** 只有 sandbox binding、显式 side-effect policy 和专用身份同时满足时才允许。
11. **测试 evidence 与生产 evidence 分类不同。** 测试通过可以成为 correctness evidence，但不能冒充生产真实执行证据。
12. **断言失败与执行失败分开建模。** 两者都失败，但原因、责任和重试策略不同。
13. **并发测试默认只断言偏序和结果，不断言线程完成全序。** 除非 graph contract 明确要求全序。
14. **敏感 fixture 不进入普通日志。** 分类、脱敏、保留和 legal hold 沿用 payload governance。
15. **任何 bypass 都产生审计事件。** break-glass 只能扩大诊断可见性，不能在生产启用 fixture。

## 6. 测试分层：从 operator 到 DAG 正确性

![Resource Gateway 分层测试与正确性证明链](assets/resource-gateway-testability-evidence-chain.svg)

图源：[resource-gateway-testability-evidence-chain.drawio](assets/drawio/resource-gateway-testability-evidence-chain.drawio)。

### 6.1 L0 Schema Contract Test

目标：验证 operator、resource 和 graph contract 本身合法，fixture 满足 schema。

这是现有 operator suite 已经擅长的层。它执行快、可以覆盖 design-only operator，但不能声称验证了实现行为。

### 6.2 L1 Operator Executable Unit Test

目标：执行一个真实 operator runtime binding，其外部依赖全部被替换或接入 sandbox。

推荐不是直接调用 `operator.execute`，而是编译并运行一个单节点 micro graph：

```text
formal operator input
  -> production input coercion
  -> production interceptor chain
  -> exact runtime binding
  -> output/schema/assertion/side-effect observation
```

这样测试能覆盖 Resource Gateway 真正会走的类型转换、deadline、interceptor、side-effect journal 和 evidence 路径。

注意：如果一个 operator 内部偷偷 new HTTP client、直接读系统时间或访问全局数据库，DAG 层无法从 node 边界控制它。operator 必须满足 **Composability Contract**：

- 所有外部依赖通过可声明 port、resource binding 或可注入 provider 暴露；
- 时间使用 `OperatorContext.timeSource()`；
- 随机数、UUID、identity、feature flag 使用 execution-scoped provider；
- side effect type、idempotency、dependency manifest 和 secret policy 如实声明；
- 不使用未声明的全局可变状态。

不满足该合同的 operator 仍可运行，但认证等级必须标为 `OPAQUE_RUNTIME`，不能宣称可重复单测。

### 6.3 L2 Subgraph Component Test

目标：让被测子图真实运行，把边界外节点替换成 doubles。

它验证 input binding、边、transform、decision table、foreach 和局部错误策略，比单 operator 更接近业务逻辑，又比整图测试更容易定位失败。

### 6.4 L3 Graph Contract Test

目标：真实执行完整 DAG 的编排语义，仅虚拟化外部系统边界。

这应成为 Resource Gateway 的主力测试层。现有 `GatewayGraphContractTestService` 已证明这条路径可行，但下一版要从“只 mock `httpResource`”扩展为统一 InvocationSite 控制。

### 6.5 L4 Adapter/Sandbox Integration Test

目标：operator 和 resource adapter 连接真实协议兼容的 sandbox、test container 或 ephemeral environment。

这一层验证序列化、认证、HTTP 状态、数据库 schema、消息协议等边界，不追求大量 case。它不能与 mock test 混在同一个未标识模式里。

### 6.6 L5 Replay/Differential Regression

目标：使用历史脱敏 payload 或 golden fixture 运行新版本，比较：

- 最终输出；
- node/edge path；
- schema；
- error class；
- side-effect intent；
- 新旧版本差异预算。

默认 `DENY_EXTERNAL_WRITES`，只允许生成 side-effect intent，不提交真实外部写。

### 6.7 L6 Production Observation

目标：验证真实运行事实、SLO 和漂移，不使用 fixture。它不是测试控制面的运行模式，只向正确性工作簿提供补充证据。

## 7. 执行数据控制反转模型

### 7.1 为什么叫 Control Plane，而不是 Fixture Map

`Map<nodeId, output>` 只能表达静态 stub。工业场景还需要表达：

- 第一次返回 503，第二次成功，以验证 retry；
- 某个 foreach item 超时，其余 item 成功；
- 只对 resource 参数满足某个 canonical match 的调用返回 fixture；
- 真实执行 operator，但记录调用输入和 side-effect intent；
- 从历史 run replay 响应，并证明 payload 已脱敏；
- built-in function 在固定 seed 和 logical clock 下执行；
- stream 按时间序列发出 N 个事件后失败；
- compensation 被调用且使用正确 idempotency key。

这些是执行策略，不是静态数据。

### 7.2 控制行为

建议第一版支持以下行为，避免一次性引入可执行脚本：

| Behavior | 语义 | 典型用途 |
|---|---|---|
| `REAL` | 使用冻结的真实 runtime binding | 被测主体 |
| `RETURN` | 返回 schema-gated fixture | stub 下游数据 |
| `THROW` | 抛出标准化 error code/type | negative path |
| `DELAY` | 推进或等待逻辑时间后执行后续行为 | timeout/retry |
| `TIMEOUT` | 产生标准 timeout，不真实睡眠 | deadline/fallback |
| `STREAM` | 按逻辑时间发出 item/error/complete 序列 | event/stream graph |
| `REPLAY` | 从受治理 payload vault 读取历史响应 | regression |
| `SPY` | 真实执行并记录输入、输出和 side-effect intent | adapter observation |
| `DENY` | 若该 invocation site 被触发则失败 | 证明某路径绝不发生 |

第一版不支持上传 JavaScript、Groovy、SpEL 或任意代码。动态行为使用声明式 match、sequence 和 fault spec，避免 RCE、不可重复和治理失控。

### 7.3 Selector

```yaml
selector:
  graphPath: /root
  nodeId: fetchPolicy
  operatorRef: resource:policy-service.getPolicy
  invocationKind: PRIMARY
  match:
    paths:
      /customerId: C-1001
  attempts: [1, 2]
```

支持层级：

1. exact invocation site + attempt + correlation key；
2. graphPath + nodeId；
3. operatorRef/resourceRef/functionRef；
4. capability/tag；
5. suite default policy。

优先级高的 selector 胜出；同级多个 selector 同时命中则 preflight 失败。禁止使用“最后声明覆盖前面”掩盖歧义。

### 7.4 Match 语义

只支持可规范化、可审计的匹配：

- whole input canonical JSON equals；
- JSON Pointer path equals/exists/absent；
- schema match；
- correlation key equals；
- invocation attempt/occurrence equals。

正则必须受长度、复杂度和 timeout 限制。表达式不允许调用外部函数或读取未授权 payload path。

### 7.5 Consumption policy

每条 fixture rule 必须声明：

```yaml
consumption:
  required: true
  minUses: 1
  maxUses: 1
  onExhausted: FAIL
  onUnmatched: FAIL
```

测试结束后至少检查：

- required rule 是否被使用；
- 是否存在没有 fixture 的外部调用；
- rule 是否超额使用；
- DENY site 是否被调用；
- 调用输入、次数和顺序约束是否满足。

未消费 mock 必须是失败或显式 warning，不能悄悄通过。

### 7.6 并发和 sequence

全局“第 1 次/第 2 次调用”在并行 DAG 中不稳定。规则是：

- 首选业务 correlation key 或 foreach item key；
- attempt 序列绑定到同一 invocation lineage；
- 只有 graph 证明顺序执行时才允许 occurrence sequence；
- 并发分支默认断言偏序，例如 `A complete before C start`，不比较 A/B 完成先后；
- test runner 应支持 deterministic scheduler 作为专项模式，但不能把它当成生产调度语义。

## 8. 协议草案

### 8.1 TestExecutionRequest

```json
{
  "schemaVersion": "bloge.testExecutionRequest.v1",
  "target": {
    "kind": "GRAPH_PUBLICATION",
    "id": "pub-order-risk-2026-07-15",
    "fingerprint": "sha256:..."
  },
  "executionPurpose": "GRAPH_CONTRACT_TEST",
  "caseRef": {
    "suiteId": "suite-order-risk",
    "suiteRevision": 12,
    "caseId": "negative-timeout-fallback"
  },
  "context": {
    "orderId": "O-1001"
  },
  "fixtureBundleRef": {
    "fixtureBundleId": "fixture-order-risk",
    "revision": 9,
    "fingerprint": "sha256:..."
  },
  "requestedControls": {
    "logicalClock": "2026-07-15T09:00:00Z",
    "randomSeed": 314159,
    "externalWritePolicy": "DENY"
  }
}
```

`executionPurpose` 只是调用方意图，服务端必须结合受信身份生成不可伪造的
`AuthorizedExecutionPurpose`。请求写 `PRODUCTION` 或 `TEST` 都不能越过 endpoint policy。

### 8.2 FixtureBundle

```yaml
schemaVersion: bloge.fixtureBundle.v1
fixtureBundleId: fixture-order-risk
revision: 9
targetFingerprint: sha256:...
classification: INTERNAL
logicalTime:
  startAt: 2026-07-15T09:00:00Z
randomSeed: 314159
rules:
  - ruleId: policy-first-timeout
    selector:
      graphPath: /root
      nodeId: fetchPolicy
      invocationKind: PRIMARY
      attempt: 1
    behavior:
      kind: TIMEOUT
      after: PT2S
      errorCode: RG.TEST.UPSTREAM_TIMEOUT
    consumption:
      required: true
      minUses: 1
      maxUses: 1
  - ruleId: policy-retry-success
    selector:
      graphPath: /root
      nodeId: fetchPolicy
      invocationKind: PRIMARY
      attempt: 2
    behavior:
      kind: RETURN
      value:
        decision: REVIEW
        riskScore: 71
    consumption:
      required: true
      minUses: 1
      maxUses: 1
assertions:
  - scope: NODE_STATUS
    nodeId: fetchPolicy
    operator: EQUALS
    expected: SUCCESS
  - scope: NODE_ATTEMPT_COUNT
    nodeId: fetchPolicy
    operator: EQUALS
    expected: 2
  - scope: OUTPUT_PATH
    path: /decision
    operator: EQUALS
    expected: REVIEW
```

### 8.3 EffectiveExecutionPlan

它不是用户编辑对象，而是 preflight 结果：

```json
{
  "schemaVersion": "bloge.effectiveExecutionPlan.v3",
  "planId": "plan-...",
  "planFingerprint": "sha256:...",
  "authorizedPurpose": "GRAPH_CONTRACT_TEST",
  "targetFingerprint": "sha256:...",
  "fixtureBundleFingerprint": "sha256:...",
  "resolvedSites": [
    {
      "invocationSiteId": "/root/fetchPolicy#PRIMARY",
      "resolution": "TEST_DOUBLE",
      "behavior": "REPLAY",
      "boundary": "NODE",
      "ruleRefs": ["policy-approved-replay"],
      "fidelity": "REPLAYED"
    }
  ],
  "replayDependencies": [
    {
      "replayRef": "bloge-replay:policy-approved@4#sha256:<64 lowercase hex>",
      "replayPayloadId": "policy-approved",
      "revision": 4,
      "fingerprint": "sha256:<64 lowercase hex>",
      "classification": "CONFIDENTIAL",
      "sourceRunId": "run-...",
      "sourceNodeId": "fetchPolicy",
      "sourceAttempt": 1,
      "sourceRunFingerprint": "sha256:<64 lowercase hex>",
      "sourcePayloadFingerprint": "sha256:<64 lowercase hex>",
      "expiresAt": "2026-08-15T09:00:00Z",
      "certificationEligible": true,
      "certificationGaps": []
    }
  ],
  "defaultPolicies": {
    "externalEffects": "DENY",
    "selectorZeroMatch": "FAIL",
    "selectorAmbiguity": "FAIL",
    "productionControl": "REJECT"
  },
  "diagnostics": []
}
```

运行前 UI/CLI 必须能查看这个计划。用户需要知道“哪些节点真实、哪些被替换、哪些被禁止”，而不是运行后猜测。

## 9. BLOGE 引擎技术改造

### 9.1 新增 ExecutionOptions，而不是继续扩张 Map

建议新增兼容式 API：

```java
GraphResult execute(Graph graph, GraphContext context, ExecutionOptions options);

record ExecutionOptions(
        ExecutionPurpose purpose,
        ExecutionControlPlan controlPlan,
        ExecutionServices services,
        List<ExecutionListener> listeners) {}
```

旧 `execute(...)` 委托到 production defaults；旧 `executeWithOperators(...)` 保留兼容，但标记为 low-level/test compatibility API，不再承载新协议。

### 9.2 统一 OperatorResolverChain

主节点、streaming、suspendable、compensation、nested graph 必须共享。当前同步主节点、compensation、内置 nested graph 与内部 cold-start signal 已通过 `ExecutionOptions.operatorResolver` 共享同一链；streaming/suspendable 证据和公开 durable worker 仍是未完成项：

```text
InvocationSite
  -> ExecutionControlResolver
  -> EmbeddedOperatorResolver
  -> RuntimeBindingResolver
  -> RegistryResolver
  -> unresolved failure
```

resolver 返回的不只是实现，还包括：

```text
resolution kind
implementation fingerprint
fixture rule refs
side-effect policy
schema contract
trace decorators
```

这样可以根治当前主节点、streaming 和 compensation 各自维护解析规则的漂移。

### 9.3 ExecutionServices

把会导致非确定性的运行依赖显式化：

```java
record ExecutionServices(
        TimeSource timeSource,
        RandomSource randomSource,
        IdGenerator idGenerator,
        IdentityProvider identityProvider,
        FeatureFlagProvider featureFlags,
        SecretProvider secretProvider,
        ExpressionFunctionResolver functionResolver) {}
```

不是所有 provider 都允许被 fixture 替换：secret 只允许指向测试 secret ref，不允许把明文 secret 写进 bundle；identity 由测试身份 authority 产生；production purpose 强制使用 production services。

### 9.4 Built-in function 的改造边界

目前 `DslCompiler` 在编译 function call 时捕获 `ExpressionFunction` 实例，运行期直接调用 `fn.apply(args)`。这意味着 operator 控制面无法观察或替换 function。

建议分两类：

1. **纯、确定性 built-in**：继续内联或直接调用，不需要 mock，但记录 function fingerprint；例如字符串、集合和数学函数。
2. **环境依赖 function**：编译为 `FunctionCallSite`，运行期通过 `ExpressionFunctionResolver` 解析；例如 clock、random、UUID、identity、feature flag、外部 lookup。

自定义 function 必须声明：

```text
purity
determinism
sideEffectType
dependencyRefs
input/output schema
implementationFingerprint
testControlPolicy
```

声称 `pure=true` 却访问网络或系统时间属于 contract violation，应在 conformance test 中阻断 runtime readiness。

### 9.5 Durable execution

持久化执行至少保存：

- plan id/fingerprint；
- fixture bundle id/revision/fingerprint；
- authorized purpose；
- logical clock/seed state；
- 每条有状态 rule 的 consumption cursor；
- replay payload refs；
- side-effect policy；
- test identity snapshot。

resume 时只从不可变 store 恢复，禁止重新读取“latest fixture”。fixture 已删除、过期或权限变化时进入可解释终态，不得自动执行 REAL。

## 10. Resource Gateway 产品与服务改造

### 10.1 统一 Test Runtime Service

新增内部模块边界：

```text
testing/domain
  TestSuite, TestCase, FixtureBundle, Assertion, CoveragePolicy

testing/planning
  ExecutionControlCompiler, SelectorResolver, SafetyPreflight

testing/runtime
  TestRunService, TestDoubleFactory, InvocationRecorder

testing/evidence
  TestRunEvidenceAssembler, TestCertificationService

testing/api
  operator tests, graph tests, replay tests, batch/CI endpoints
```

现有 operator suite、gateway graph suite、visual simulation 和 golden case 逐步改为 adapter，共享 domain/planning/runtime/evidence，不再各自发展 fixture 语义。

### 10.2 Operator runtime binding 必须可定位

`EXECUTABLE_UNIT` 必须绑定：

- operatorRef + operatorVersion；
- operator fingerprint；
- runtime binding id + implementation fingerprint；
- input/output/config schema fingerprint；
- runtime environment class；
- dependency manifest；
- side-effect policy。

只给 operatorRef 不够。测试 latest 实现、发布旧实现、证据却只写 operatorRef，会产生无法追责的版本错配。

### 10.3 UI 体验

Operator Detail 的 Test Suite 应拆成清晰模式：

| UI 模式 | 默认行为 |
|---|---|
| Schema Check | 不执行 runtime，适合 design-only operator |
| Run Operator | 真实执行指定 binding，依赖默认 DENY |
| Mock Dependencies | 图形化选择依赖，配置 RETURN/THROW/TIMEOUT |
| Compare Versions | 左右选择 binding，展示 path diff |

Graph Test Suite 增加：

- 画布节点状态覆盖层：REAL、MOCK、REPLAY、DENY、UNRESOLVED；
- fixture rule 从 palette 拖到 node/resource/function；
- preflight plan 预览；
- 未命中、歧义、未消费 fixture 的显著错误；
- logical clock 和 seed 控件；
- path/branch/retry/fallback coverage overlay；
- 失败后直接定位 node、attempt、assertion 和 fixture rule；
- `Save as governed suite`、`Promote to golden`、`Replay against version`。

复杂用户仍可编辑 YAML/JSON，但图形化编辑器是默认入口，raw editor 是高级模式。

## 11. 断言与业务语义覆盖率

### 11.1 断言维度

| Scope | 示例 |
|---|---|
| Graph output | exact、path、schema、approx、set contains |
| Node input/output | path、schema、absence、sanitized snapshot |
| Node status | SUCCESS、FAILED、TIMEOUT、SKIPPED、PARTIAL、MOCKED |
| Invocation | call count、attempt count、input match、fixture consumption |
| Edge/branch | edge traversed/not traversed、decision rule id |
| Resilience | retry exhausted、fallback used、deadline propagated |
| Side effect | denied、intent emitted、journaled、compensation invoked |
| Error | stable error code/type/path，不断言脆弱 message 全文 |
| Governance | no restricted payload leak、required owner/policy marker |
| Performance | 逻辑 deadline；真实性能只在 benchmark/sandbox 层断言 |

### 11.2 Coverage 不能只数 case

工业级 coverage policy 应包含：

```text
node coverage
edge coverage
branch outcome coverage
decision rule coverage
output node coverage
schema boundary coverage
negative error class coverage
retry/fallback/timeout coverage
compensation coverage
external dependency virtualization coverage
required fixture consumption
assertion density
```

代码行覆盖率仍由 operator 代码仓库负责。Resource Gateway 输出的是 orchestration semantic coverage。

### 11.3 Property、boundary 和 mutation testing

阶段性引入：

- 从 JSON Schema 生成 boundary values，而不只是一个 happy-path sample；
- 支持 property assertion，例如 score 始终在 `[0, 100]`；
- 对 decision condition、edge、binding path 做有限 mutation，确认 suite 能杀死错误；
- mutation 只对纯 DSL 语义运行，不能自动修改外部写 operator。

mutation score 比“有 20 个 case”更能证明 suite 是否真的有防回归能力。generation-one bounded score
现已按 P2 落地；semantic equivalence、flaky 与统计置信继续留在后续质量阶段。

## 12. 生产隔离与安全模型

这是整个方案最不能妥协的部分。只做一个 `testMode=true` 参数是不可接受的。

### 12.1 推荐的三层隔离

#### 第一层：协议隔离

- 普通 `/run` 和 publication execution API 不接收 fixture/control 字段；
- 测试只走 `/api/testing/executions` 或内部 gRPC test runtime；
- production purpose 对非空 control plan fail closed；
- test token 必须包含 tenant、environment、purpose、target、TTL 和 nonce。

#### 第二层：进程与部署隔离

目标态使用独立 `resource-gateway-test-runtime` deployment：

- production binary/profile 不注册 `TestDoubleFactory` 和 test endpoints；
- test runtime 使用独立 service account、database/schema、queue namespace 和 secret scope；
- network policy 默认拒绝生产 upstream；
- fixture payload store 与生产 evidence store逻辑隔离；
- 测试运行有独立配额和 autoscaling，不能挤占生产 run data plane。

早期可在同一模块化单体中落地，但必须 feature-disabled by default，并有明确退出时间，不能把过渡形态永久化。

#### 第三层：数据与副作用隔离

- external write 默认 `DENY`；
- `REAL` write 只允许指向标记为 `SANDBOX` 的 runtime binding；
- egress allowlist 校验目标 endpoint/environment；
- 使用 test tenant/test credentials 和幂等 key namespace；
- side-effect journal 仍然启用，测试不是绕过审计的理由；
- replay 一律禁止提交历史 side effect，只可比较 intent。

### 12.2 权限

| 权限 | 说明 |
|---|---|
| `test.suite.author` | 编辑 case/assertion，不自动获得敏感 fixture 读取权 |
| `test.fixture.read` | 按 classification/path 读取脱敏 fixture |
| `test.fixture.write` | 创建 fixture，需 DLP 和 schema 校验 |
| `test.run.mock` | 运行无外部写的 mock/component test |
| `test.run.sandbox` | 调用 sandbox binding |
| `test.replay` | 使用受治理 replay payload |
| `test.certify` | 签发 certification，需职责分离 |
| `test.policy.admin` | 配置 isolation/egress/side-effect policy |

suite 作者不能自己批准 production promotion。复杂企业组织中必须支持 owner 变更、离职移交、跨域共享和 break-glass 审计。

## 13. TestRunEvidence

一份可用于 ANEKE workbook/publish gate 的测试证据至少包含：

```text
runId / parentRunId
authorized execution purpose
tenant / namespace / environment / actor / workload identity
target kind/id/version/fingerprint
operator/runtime/schema/dependency fingerprints
suite/case/fixture bundle refs and fingerprints
effective execution plan fingerprint
logical clock / random seed / scheduler mode
real/mocked/replayed/denied invocation sites
rule match and consumption report
node/edge/attempt trace
assertion results
semantic coverage
side-effect intents/outcomes
payload policy/redaction refs
startedAt/completedAt/duration
evidence class and signature
```

标准状态建议：

| 状态 | 含义 |
|---|---|
| `PASSED` | 执行、断言、coverage 和 fixture consumption 全部通过 |
| `ASSERTION_FAILED` | graph 正常结束，但业务断言失败 |
| `EXECUTION_FAILED` | 被测运行发生非预期失败 |
| `CONTROL_PLAN_REJECTED` | selector、policy、版本或隔离检查失败，未执行 |
| `FIXTURE_UNMATCHED` | 发生未覆盖外部调用 |
| `FIXTURE_UNUSED` | required fixture 未被消费 |
| `CONTROL_PLAN_UNAVAILABLE` | durable resume 无法恢复原计划 |
| `EVIDENCE_INCOMPLETE` | 运行结束但证据无法完整固化，不能认证 |
| `CANCELLED` | 受控取消 |
| `TIMED_OUT` | test run deadline 到期 |

`MOCKED` 是 invocation/node observation，不应替代顶层 test run 结果。

## 14. 企业级失败模式与根治手段

| 问题 | 表面修补 | 病根 | 根治手段 |
|---|---|---|---|
| mock 没命中却调用真实系统 | 增加 warning | 默认 fallback to real | unmatched 默认 fail；外部边界默认 DENY |
| node id 重构导致 fixture 失效 | 运行时报错 | fixture 未绑定 artifact fingerprint | plan phase 冻结 target；提供 selector rebase/diff |
| operator 同名多版本误测 | 只记录 operatorRef | runtime binding 未入测试合同 | 固化 implementation fingerprint |
| 并发调用消费错 sequence | 给 list 加锁 | 依赖非确定调用顺序 | correlation key/attempt lineage 定位 |
| resume 后改用真实 operator | 重启后重新解析 latest | control plan 未持久化 | provider snapshot 已绑定 plan/binding fingerprint 并 fail closed；继续把 plan、provider state 与 consumption cursor 原子接入 durable store |
| built-in function 无法 mock | 在 ctx 放测试值 | function 在编译时闭包捕获 | FunctionCallSite + ExecutionServices |
| 测试通过但生产写错数据 | 加更多 golden case | hidden side effect 未声明 | Composability Contract + side-effect conformance |
| 测试数据泄密 | 日志打码 | fixture 生命周期无治理 | 分类、ABAC、脱敏、retention、legal hold |
| 测试 endpoint 被生产误用 | 加 header 开关 | purpose 由请求自报 | 独立 endpoint/deployment + server-minted purpose |
| suite 数量很多但防不住回归 | 统计 case count | coverage 只看数量 | semantic coverage + generation-one mutation score + 后续 flaky/equivalence 分析 |
| 失败难定位 | 返回最终 output diff | 无 invocation/fixture lineage | node/attempt/rule/assertion 统一 trace |
| 大批量回归拖垮网关 | 调高线程池 | 测试与生产共享资源池 | 独立 test runtime、配额、队列和优先级 |
| fixture 随业务变化腐化 | 人工定期清理 | 没有 schema/dependency drift index | fixture impact analysis + stale state + rebase workflow |
| 真实 sandbox 不稳定导致 flaky | 重跑三次 | 测试层级混淆 | mock contract 与 sandbox contract 分开统计 |
| snapshot 每次都变 | 忽略字段越来越多 | 非确定性未建模 | logical time/seed/canonicalization/semantic diff |

## 15. 工程化易用性

### 15.1 一份 suite，多种运行入口

同一不可变 suite 应可从以下入口运行：

- 画布浮层；
- operator detail；
- REST/gRPC；
- CLI；
- Maven/Gradle test adapter；
- VSCode command；
- CI batch；
- ANEKE workbook trigger。

所有入口最终调用同一个 Test Runtime Service，避免浏览器、CLI 和 CI 各自解释 fixture。

### 15.2 输出格式

除 JSON evidence 外，提供：

- JUnit XML：进入现有 CI test report；
- SARIF：把 schema/binding/assertion 问题定位回 DSL source map；
- concise CLI summary：适合 PR；
- HTML/Author trace：适合交互诊断；
- evidence ref：适合 ANEKE gate，不在 CI 日志散落敏感 payload。

### 15.3 测试数据生成

schema sample generator 要从“生成一个合法值”升级为 test data matrix：

```text
minimum / maximum / just below / just above
empty / null / missing required
enum alternatives
oneOf/anyOf variants
array min/max/contains
string length/pattern/format
dependentRequired/dependentSchemas
business equivalence classes supplied by author
```

自动生成 case 只能是草稿。业务等价类、风险路径和正确结果仍需领域 owner 确认。

## 16. 存储、生命周期与反熵

### 16.1 生命周期

```text
Suite: DRAFT -> REVIEWED -> ACTIVE -> DEPRECATED -> RETIRED
Fixture: DRAFT -> VALIDATED -> ACTIVE -> STALE -> REBASED/RETIRED
Certification: PENDING -> PASSED/FAILED -> STALE/REVOKED
```

以下变化自动把 suite/certification 标为 stale：

- graph/operator/schema/runtime binding fingerprint 变化；
- selector 对应 invocation site 消失或变成多个；
- side-effect classification 升级；
- fixture schema 不兼容；
- coverage policy 或 gate policy 版本变化；
- replay payload 到期、purged 或 legal-hold 状态变化；
- evidence signer 被 revoke。

### 16.2 大 payload

FixtureBundle 元数据放关系库，大 payload 放 governed object/payload store：

- content-addressed digest 去重；
- 分块和压缩；
- 按 tenant/region 加密；
- path-level redaction；
- retention/hold/purge；
- evidence 只引用 digest 和 policy，不复制原文。

### 16.3 反熵任务

- 周期扫描 stale selector 和 schema drift；
- 检测永不消费、永不运行、长期 flaky suite；
- 对比 suite coverage 与生产路径观测，发现未测试真实路径；
- 检测 operator 声明与实际 side-effect/egress observation 不一致；
- 输出 owner queue，不自动篡改 assertion 或 golden value。

## 17. 容量与性能

测试控制面会引入额外 trace 和匹配成本，应提前定义边界：

| 维度 | 初始建议 |
|---|---|
| 单 graph nodes | 沿用 graph complexity policy，不在 test API 绕过 |
| fixture rules | 每 case 默认 500，上限由租户配额控制 |
| selector match | 运行前索引；运行中目标 O(1) 或 O(log n)，禁止每调用全表扫描 |
| payload | 小值内联，大值使用 content ref |
| batch concurrency | tenant、suite、operator 和 dependency 四级配额 |
| evidence | 流式采集、终态组装；失败时 quarantine incomplete evidence |
| retention | test evidence 通常短于 production evidence，gate 引用可延长 |

批量 suite 要支持 fail-fast 和 collect-all 两种策略，但必须区分“停止调度新 case”和“中断正在执行的有副作用 case”。

## 18. 观测与 SRE

关键指标：

```text
test_run_total{purpose,result}
test_run_duration
control_plan_rejected_total{reason}
fixture_match_total{behavior}
fixture_unmatched_total{site}
fixture_unused_total
real_external_call_total{environment}
test_side_effect_denied_total
suite_flaky_rate
suite_stale_total{reason}
evidence_incomplete_total
batch_queue_depth / tenant_throttled_total
```

最高优先级告警：

1. test purpose 触达 production endpoint、credential 或 data source；
2. production run 出现非空 control plan；
3. replay/test run 产生未批准 external write；
4. resume 时 control plan 丢失并尝试 fallback；
5. evidence 标记与实际 REAL/MOCK resolution 不一致。

这些属于安全事件，不是普通测试失败。

## 19. 演进计划

### Stage 0：语义冻结与诚实命名，1-2 周

交付：

- 把现有 operator suite 明确标为 `SCHEMA_CONTRACT`；
- 定义 `ExecutionPurpose`、`InvocationSite`、`FixtureBundle`、`EffectiveExecutionPlan` 和 `TestRunEvidence` v1；
- ADR 冻结生产隔离策略和 unmatched default；
- capability probe 暴露 testability protocol 版本和 enabled environments；
- 建立当前 suite/evidence 的迁移映射。

验收：系统不再把 mocked output schema check 描述为真实 operator 执行。

### Stage 1：Executable Operator Test，2-4 周

**实现状态**：内核与 Java micro-graph runner 已完成；独立 test-kit 的
JUnit 5 assertions、JUnit XML 与 CI exit code 已在 Stage 2 首个增量落地；公共同步 operator
target discovery、micro-graph execution、immutable fixture、test-kit adapter 和 Author Canvas
`Executable Operator Suite` 已落地；画布可将一行或多行 case 发布为内容寻址 fixture 与一等 TestSuite，
再执行并展示聚合 coverage/promotion evidence。当前
`/api/visual/operators/tests/run` 继续严格标识为
`SCHEMA_CONTRACT`；画布通过另一组隔离 API 执行真实 binding，不能借 UI 迁移反向改写旧 API 的证明语义。

交付：

- runtime binding 精确定位；
- single-node micro graph runner；
- `REAL/RETURN/THROW/DENY/SPY`；
- input/output/error/invocation/schema assertion；
- test evidence 基础版；
- UI 的 Schema Check / Run Operator 双模式；
- JUnit XML 和 CI exit code。

验收：至少覆盖内置纯 operator、`HttpResourceOperator` sandbox、失败 operator 和 side-effect DENY 四类 conformance case。

### Stage 2：Execution Data Control Plane，3-5 周

**实现状态**：进行中。Stage 1 已完成 selector preflight、不可变 effective plan、主节点 replacement、consumption policy 与既有
gateway graph suite adapter；Stage 2 已补 graph target discovery、公共执行/批量/查询、immutable fixture registry、canvas 内容寻址 fixture 与一等 suite 发布、独立持久化、证据脱敏、profile/identity guard、production run control-field guard、独立 test-kit，并完成七图/14-case built-in dogfooding：28 个 root/nested 资源调用观测使用 F3，retry 以 bounded consumption 计数，Spring wiring 在不可达 endpoint 下证明没有 HTTP 调用逃逸。run-scoped logical clock、DELAY/TIMEOUT、同步 nested/foreach/loop/compensation 的结构寻址、控制传播、动态 attempt/occurrence selector 与 occurrence/attempt/node/edge evidence、公共同步 operator adapter、canvas operator runner，以及一等 immutable `bloge.testSuite.v1` 的依赖闭包 registry/API、精确幂等 runner、逐 case checkpoint、结构覆盖与服务端 promotion eligibility 已落地。动态 selector 复用 evidence 的一基坐标，按 specificity 冻结候选，只有可证明互斥的同级规则可共存；未覆盖坐标仍 fail closed。suite runner 进一步以 process-owner lease 和长 case 心跳证明活性，heartbeat/checkpoint 共用版本 fence；bounded sweeper 在 owner 过期后用 CAS 把旧 `RUNNING` 终态化为 promotion-blocked `EVIDENCE_INCOMPLETE`，保留 child ref 且不自动重跑可能有副作用的 case。该恢复协议已扩展到 schema-admission v3：空 child closure 不变，已完成 validator observation 不变，仅 pending common/admission result 降为 incomplete，结构 coverage 不被伪造，plan/schema/generator 坐标与 v3 attestation 代际保持一致；trust authority 不可用时拒绝派生终态。Canvas 同时支持四类 case intent、完整 stored suite value 回绑、child/coverage/promotion/aggregate 逻辑一致性校验与聚合回显，异步运行期间冻结编辑并主动清除过期 publication；Java/JUnit/CI suite adapter 已提供 builder、强类型 projection/assertion、payload-free JUnit XML 和 fail-closed CLI。旧七图 catalog 已通过稳定 source id、canonical-content revision 和 exact ref 映射幂等物化为 7 份 governed suite 与 14 份 fixture，numeric tolerance 也已进入唯一兼容 mapper 和统一 assertion kernel。受治理 replay vault、exact-ref preflight closure、运行期冻结、plan v2 payload-free lineage、BLOGE schema gate、`REPLAYED` evidence 与认证降级已经闭环。streaming/suspendable control/evidence、跨故障域 recovery queue/告警 SLO 和物理 test-runtime/network isolation 仍是本阶段硬验收，不能因同步 suite 主路径通过就宣称 Stage 2 完成。

交付：

- BLOGE `ExecutionOptions` 和统一 `OperatorResolverChain`；
- selector preflight 与不可变 effective plan；
- `TIMEOUT/DELAY/REPLAY`、attempt/occurrence/correlation selector 与 consumption policy（已完成）；
- nested/subgraph/foreach/compensation 统一寻址；
- graph component 和 graph contract runner；
- 现有 visual simulation、gateway graph suite 迁移到统一 runtime。

验收：任何外部调用未匹配 fixture 时 fail closed；plan 与实际 resolution 可逐项对账。

### Stage 3：正确性证据与语义覆盖，2-4 周

**实现状态**：进行中。第一增量已对完整、脱敏后的 graph/operator child
`TestRunEvidence` 计算 canonical fingerprint，复用 Resource Gateway 的本地 Ed25519 或 managed
KMS/HSM signer 生成 detached signature，并在持久化前自验、查询时复验。`FULL` 响应可独立验签，
`STANDARD/SUMMARY` 只保留签名谱系及各自 projection fingerprint；suite runner 在聚合前必须验证
每个 FULL child，否则固化 `EVIDENCE_INCOMPLETE` 并阻断 promotion。第二增量为初始及后续
`RUNNING` checkpoint 签 `CHECKPOINT`，为终态 aggregate 与有序 child evidence closure 签
`TERMINAL`，并在 persistence read、idempotent read 与 abandoned-run reconciliation 前复验。
服务端可导出 `payloadPolicy=OMITTED` 的 `bloge.testSuiteEvidenceBundle.v1`。第三增量新增
`toolStudio.resourceGateway.evidenceVerificationKeySet.v1`：signer 原子暴露 key generation，managed
provider v2 携带有效期、`COMPLETE/CURRENT_STATE_ONLY` 和有序 lifecycle events，v1 兼容输入强制
降级为 current-state-only；Gateway 对 canonical snapshot fingerprint 签名并本地反验。独立 test-kit
必须使用带外 pin，重算 key-set/aggregate/bundle/signature material，验证 key/event 当前状态一致性，
并按 evidence `signedAt` 判断 activation、retirement、disable、prospective revocation 和 retroactive
compromise。第四增量将 exact `bloge.testSuite.v2`、verified terminal v2 aggregate 和 v2 attestation
投影为 payload-free `SemanticCorrectnessWorkbookBundle.v1`，并由独立 test-kit 执行 Tool Studio schema
校验与 fail-closed 状态投影。第五增量以 `GovernanceGateResult.v3` 固化完整有序 evidence closure、manifest
计数/截断事实和 semantic bundle fingerprint；服务端按 exact run 重建原 bundle，因此新 run 到达不会误判旧
decision stale。graph suite 必须与 exact GraphDraft 重新 lowering/compile 后的 composite target fingerprint 相等，
operator suite 必须真实出现在 draft 且当前 runtime target 未漂移；`PASSED` 还必须至少含一个 gate-ready graph
suite，并由 `SEMANTIC_CORRECTNESS` check 精确引用全部 bundle fingerprint。该链仍不能描述为完整
certification package，因为它不含 replay payload attachment、独立 witness gossip/跨域一致性证明、
真实 ANEKE cross-version conformance 或 ANEKE 最终 publish decision。

交付：

- 完成 branch/rule/retry/fallback/compensation coverage，并把已落地的 suite-level
  invocation-site/edge-transfer/assertion-density/fixture-consumption 覆盖升级为可签名语义度量；
- fixture consumption report；
- signed `TestRunEvidence`（child-run、aggregate checkpoint/terminal attestation、portable bundle
  与 consumer verifier、signed key lifecycle、外部 M-of-N trust publication、bounded consistency
  page、durable checkpoint 和 rollback/fork/split-view/revoked-pin resurrection detection 已完成）；
- ANEKE semantic workbook seed projection 与 `GovernanceGateResult.v3` 可重建 gate basis（已完成）；真实 ANEKE
  N/N-1 consumer conformance 待完成；
- stale/impact analysis；
- Save as Suite / Promote to Golden。

验收：publish gate 能回答“哪个不可变版本，被哪组 fixture，以何种 REAL/MOCK 组合，覆盖了哪些业务路径”。

### Stage 4：确定性依赖与 durable test，3-6 周

**实现状态**：进行中。BLOGE 已提供 run-scoped `ExecutionServices`、typed service kind、
`FunctionCallSite` 和环境依赖 built-in 的运行期 resolver；Resource Gateway planner 在调度前冻结
同一服务对象及 `bloge.effectiveExecutionPlan.v3` 的 payload-free binding，并将它同时传给 BLOGE
scheduler、operator context 和 DSL function。`logicalClock` 控制 TIME，`randomSeed` 以域隔离
SHA-256 序列控制 RANDOM/UUID；调用事实进入 evidence metadata，缺少必要控制会把 run 降为
EXPLORATORY。IDENTITY/FEATURE_FLAG/SECRET 当前没有 fixture authority，调用即 fail closed。
生产包路径不得引用 governed provider，由架构测试持续证明。第二增量新增
`bloge.testRunEvidence.v2.semanticResultFingerprint`：它对 target + fixture + plan 下的稳定业务结果做
domain-separated canonical hash，排除 runId、墙钟、耗时、签名、治理 provenance、并行完成顺序和
引擎内部 UUID 调用；保留稳定 node/edge 坐标、值、状态、attempt、fixture/assertion、语义 provider 使用
和副作用意图。脱敏后重新计算，签名前和读取验签时均校验一致性；同一 request 每次执行使用全新
`GraphContext`，防止 provider、budget、node output 与 side-effect journal 串运行。test-kit 同时提供
`assertSameSemanticResult`。验证见
[Stage 4 execution services verification](resource-gateway-execution-data-control-plane-stage4-execution-services-verification.md)。

第三增量新增 `bloge.executionServiceStateSnapshot.v1`：在排斥并发 provider mutation 的原子边界
冻结逻辑时间、按哈希 scope 的 RANDOM/UUID cursor 与累计 usage，并绑定精确 plan/binding-set
fingerprint。恢复端重新编译计划、重算资格与 cursor/usage 闭合；篡改、配置漂移和不可恢复的
system random/UUID 语义使用统一 fail closed 为 `CONTROL_PLAN_UNAVAILABLE`。快照不携带 seed、原始
scope、fixture payload 或 authority value。

第四增量新增 `bloge.fixtureConsumptionStateSnapshot.v1` 与
`bloge.durableTestExecutionCheckpoint.v1`；后续协议根修演进出当前 v2：在 v1 的完整 effective plan
（因此保留 exact replay refs）、fixture
精确 revision、side-effect policy、identity authority 摘要、fixture rule/动态 occurrence cursor、
provider-state、BLOGE engine-state closure、tenant/environment/actor scope 与 owner/lease epoch/revision
基础上，v2 强制加入 exact graph/operator kind、stable id 与 target fingerprint，并把 kind 与授权目的、
fingerprint 与 plan 双向绑定。受信数据库把 locator 三字段冗余投影并与 sealed JSON 回绑；历史 v1
保持无 target 字段的 canonical 读取但不得进入未来公开恢复。仓库允许 BLOGE state mutation 使用同一个 test-runtime datasource
参与本地事务，随后按 owner + epoch + revision + 前序 fingerprint 做 CAS；回调失败、陈旧 fence、
并发 CAS 输家都会把控制行和 engine state 一起回滚。所有 cursor、逻辑时间、usage、engine version
只能单调前进，索引列与 JSON 任一漂移均按腐坏状态 fail closed。验证见
[Stage 4 durable checkpoint verification](resource-gateway-execution-data-control-plane-stage4-durable-checkpoint-verification.md)。

第五增量已让 `InvocationRecorder` 通过公平读写边界捕获/恢复 fixture rule use、site occurrence
和 containing-graph occurrence cursor；只有不存在待执行 binding 和执行中 attempt 的静止调用边界
才能捕获，否则 fail closed。`maxUses` 检查与消费已合并为 CAS 原子操作，避免并发超领。
cursor identity 从运行开始即使用版本化 SHA-256 key，快照不保存 graph path、site id 或原始
correlation value；该哈希仅用于去原值和稳定寻址，不是低熵值的保密边界。恢复会重算内容指纹，
并拒绝向已产生任何运行事实的 recorder 合并状态。该能力已证明快照无擕裂且 resume 后从
前序游标继续，但不包含断点前的 invocation/attempt evidence。

第六增量先在 BLOGE durable facade 关闭“存储故障被降级为空状态”的框架缺口：源码提交
`bcbb19694` 新增公共 `CheckpointFailurePolicy`，并由 `DurableManager.Builder`、
`DurableGraphEngine.Builder` 贯通。`FAIL_FAST` 对 node output、loop snapshot、sequential foreach
progress 的读、写、序列化与解码故障统一抛出 `DurabilityException`，loop operator 不再吞掉该
严格异常；`BEST_EFFORT` 继续作为兼容默认。该策略只定义失败传播，不提供跨 store 事务，RG
仍必须在 test runtime 显式选择 `FAIL_FAST` 并让具体 BLOGE store 参与下述本地事务边界。

第七增量在 Resource Gateway test profile 中新增 staged `ExecutionCheckpointStore`、
`ExecutionStore` 和独立 durable session：调用方在业务 context 外指定 engine execution id，session 继承冻结后的完整
`ExecutionOptions`（包括 operator fixture resolver 与全部 provider），并强制 `FAIL_FAST`。BLOGE 的
node/loop/sequential-foreach checkpoint 先进入 execution-scoped read-your-writes overlay，`prepare`
后按稳定顺序生成 `bloge.testCheckpointMutation.v1` closure；mutation 同时绑定 engine id 和完整
`EngineState`，只能在同 datasource 的活动事务中执行，可在事务回滚后幂等重试，stage 关闭后失效。
`ExecutionStore` 从已提交 JSON 冷读完整 `ExecutionInstance`，并复用 BLOGE 已验证的 optimistic version、
lease、signal idempotency 与 recovery-attempt 语义；生命周期变更先进入同一 run stage。
`bloge.testExecutionMutation.v1` 与 checkpoint component fingerprint 最终由
`bloge.testDurableStateMutation.v1` 聚合，repository 在一个事务回调中应用两者。两个具体 aggregate
实例争抢同一 fence 的测试证明只有 control CAS 胜者的 execution 状态可提交，输家整体回滚。

第八增量把 BLOGE 完整 `WaitStore` 加入同一 execution stage。signal、timer、task、extension timeout
和 retry-backoff wait 复用 BLOGE 已验证的 optimistic version 状态机，完整 `ExecutionWait` JSON 为
持久化权威值；execution-local 查询看到 overlay，timer/correlation 全局调度查询只看到已提交行，
避免 dispatcher 消费尚可能回滚的 wait。wait identity 必须与 lifecycle identity 完全一致，已提交
`waitId` 不可跨 execution/tenant 迁移。`bloge.testWaitMutation.v1` 与前两类 component fingerprint
由新的 `bloge.testDurableStateMutation.v2` 聚合，保留 v1 的历史语义而不原地改变指纹材料。真实
suspend/signal、timer terminal transition、冷读、事务后段失败、跨实例 control CAS 输家、身份漂移
与 wait-id 抢占均已有反例测试。

第九增量把 BLOGE 完整 `WorkItemStore` v5 状态机加入同一 execution stage。create/batch、claim/
renew、done/retry/failed、dead-letter/restore/discard、cancel 全部复用 BLOGE 已验证的 reference
transition，不在 RG 重写状态机。完整 `WorkItem` JSON 为权威值，execution-local 查询具备
read-your-writes，global ready/expired-claim scan 只读 committed rows；只有绑定 BLOGE graph-execution
scope 的异步引擎线程可按受信 execution id 入队，无 scope/stage 的读者看不到 speculative item，claim 与终态迁移必须由调用线程
重新进入 stage。批量写入先完整校验 duplicate/cross-execution/identity/id ownership，已提交 itemId
不可迁移。除 BLOGE 用作 worker topic 的 dispatch shard 外，item identity 必须绑定 lifecycle。
ready work、过期 work-item claim 与过期 execution lease 的 tenant/namespace、type/status、可选 shard、
到期时间、稳定排序和有界 limit 已下推到 SQL，并有全局与 tenant-scoped 复合索引；只有候选行才解码
权威 JSON，且返回前逐字段回验查询所依赖的调度投影。默认页为 100，硬上限 10,000，防止错误配置
形成无界 poll。独立 system-level keyset 反熵循环不依赖这些调度谓词，因而能发现隐藏候选；默认
`REPAIR_DERIVED` 以 row identity、tenant/namespace、work-item execution ownership 和 authority
快照做 CAS，仅重建安全边界一致的派生列。主键/归属/scope 漂移及不可读 JSON 不自动修复；单行
失败隔离。双游标和 owner/token/epoch lease 已持久化；每页 safe repair、finding lifecycle 与 cursor
checkpoint 同事务成败。payload-free finding owner queue 支持服务端 token、version、owner 和数据库
时钟租约的 claim/resolve fencing，一致性复查也会关闭历史 finding。第十六增量补齐 profile-gated
authenticated operations adapter：专用 maintenance purpose、global group 与 clearance 三重授权防止普通
tenant operator 获得 system queue 权限；server-derived actor、请求幂等 receipt、exact claim deadline/token/
version fence 与稳定 409 语义已协议化。首次 claim/resolve 和 token-free action audit 同事务，拒绝与 replay
独立追加；capability probe、权威 JSON Schema、运行配置和操作手册同步。第十七增量新增独立 retention
lease/state 与 token-free archive：active 及 archive retention 都按 database clock 和稳定顺序有界处理，
archive insert、exact source delete、purge 与累计 counter 同事务；archive read 复算 whole-record fingerprint。
应用层审计与同库 archive 均无 update/delete API，
第十八增量再以 database-clock operational snapshot、稳定 SLO violation code、Actuator health 和低基数
Micrometer 指标关闭 projection loop 的基础运维观测缺口；store exception、row/token/payload 均不进入
health detail 或 label。第十九增量把同一原则扩展到全局 execution/suite/capacity observation：一个只读
可重复读事务聚合 evidence completeness、四类控制队列、expired ownership、oldest age 与 storage
backlog，固定标签不含 scope identity，业务失败不污染平台 SLO。第二十增量已补齐即时四维 admission、
数据库时钟租约、精确释放/过期回收与低基数决策指标。第二十二增量已补齐公开、有界、幂等且
payload-free 的 worker pull acquisition。第二十三增量进一步以 scope 级持久化循环 keyset 游标保证
稳定毒化前缀后的候选最终可达，并拒绝 scope/position 篡改与并发倒退。第二十四增量再以 exact
checkpoint fingerprint、数据库时钟、指数封顶与 cursor CAS 胜者约束实现确定性候选临时退避，并把
closed reason 总量、retry-due、最大连续失败与最老活动年龄纳入全局低基数 SLO。第二十五增量再用
独立 active quarantine、阈值转换、批量投影和 claim 前二次校验停止永久毒化 closure 回流。第二十六
增量再以 identity-derived scope/owner、专用 maintenance purpose/group/clearance、database-clock
token/version/expiry fence、caller-stable idempotency、checkpoint-first lock order 和事务绑定 audit 实现
payload-free list/claim/release/discard；`RELEASE` 保留隔离，`DISCARD` 只删除 exact quarantine，token-free
receipt/history 独立保留并复算 whole-record fingerprint。SLO 同步增加 closed maintenance state、expired
claim、history aggregate 与稳定过期 claim code；第二十七增量已增加 maker/checker approved discard、
独立 approver group、单次审批原子消费、双人历史和 expired approval SLO；第二十八增量再用
AES-GCM envelope、启动迁移和两阶段轮换保护 claim 精确重放副本；第二十九增量再以三窗口 retention、
request-key tombstone、跨副本 lease/fence 和固定基数 telemetry 关闭无界维护记录与 request resurrection；
第三十增量以 domain-separated HMAC active fence、命令交叉验证和轮换重键清除 control 明文 bearer；
第三十一增量再以独立 HMAC request-index key ring、live-key readiness、bounded dual-read 和惰性 CAS
重键关闭低熵 request ID 的 database-only 离线枚举面；第三十二增量再以 legacy/dual/keyed-only
三阶段模式、readiness veto 与逐副本 capability 关闭 N/N-1 写格式切换协议；第三十三增量以
challenge-bound signed replica proof、外部 key-set pin 和独立 test-kit exact-set verifier 关闭给定
serving inventory 的 cohort 聚合伪证据；第三十四增量以独立 Ed25519 M-of-N trust、canonical
scope/subject binding、HTTP v2 强制、数据库唯一预留/消费、strict Schema、staging fail-fast 和
key-free capability/evidence 关闭 Resource Gateway 的外部签名决策执行路径。仍缺跨平台
serving-inventory 完整性证明、真实旧制品 conformance、外部工单全生命周期与动态撤销刷新、
法律保留/备份擦除证明、外部
WORM/tamper-evident anchoring、排队/公平/优先级
backpressure、alert routing、非 H2 方言与生产负载认证、runtime-state dispatch、hard cancellation
或生产级跨进程 supervisor。
`bloge.testWorkItemMutation.v1` 由 `bloge.testDurableStateMutation.v3` 纳入聚合，不修改 v1/v2
历史 fingerprint。冷读、回滚、retry/dead-letter、tenant、异步可见性、过期 claim、跨实例 CAS
输家和 work-item-only fingerprint 均已有反例测试。

第十增量补上 control plane 内部过期租约接管。`claimExpiredLease` 以 exact tenant/environment/run、
旧 owner/epoch/revision、旧 checkpoint fingerprint、新 process owner 和 1 秒至 1 小时整秒租约为
输入；接管时刻由数据库在事务内提供，调用方不能伪造未来时间抢占活动租约。只有 exact 且已过期的
`ACTIVE/SUSPENDED/RESUMING` 可进入 `RESUMING`；成功后 epoch/revision 各加一，重新封印 control
checkpoint，但 plan、fixture、provider、cursor 与 BLOGE engine closure 必须逐值不变。SQL CAS 再次
约束 scope、旧 fence、旧 fingerprint、过期时间和可恢复状态；跨 scope 与 stale claim 统一返回
`STALE_FENCE`，精确调用方才可区分 `LEASE_ACTIVE`/`NOT_RESUMABLE`。双 repository 实例竞态只允许
一个新 owner，旧 owner 随即无法 advance；计数器溢出、production scope 和非整秒/越界租约均在
持久状态变化前失败。

第十一增量把 repository protocol 收到公开但严格收窄的 owner-claim control plane。
`bloge.durableTestOwnerClaimRequest.v1` 只允许 caller 提供 stable idempotency key、旧 fence 与旧
checkpoint fingerprint，拒绝 caller 指定新 owner 或 lease；两者由部署配置拥有。endpoint 只在
`test`/`staging` profile 注册，接受 `TEST_EXECUTION`/`TEST_REPLAY`，先做完整 tenant/org/project/
environment non-disclosure scope，再对新命令精确重解 graph/operator target、immutable fixture、
governed replay closure、当前 identity authority、clearance、side-effect policy、provider state 和重编译
plan。任一 authority 缺失、撤销、漂移或不可用均 fail closed，绝不回退 latest/REAL。

授权意图指纹绑定认证后的 actor、delegation、purpose、clearance 与有序 groups；fresh claim 与
`ALLOWED` semantic audit 共享 test-runtime 本地事务，audit 失败不会移动 lease。响应丢失重试先按
不可变命令结果返回，再独立写 replay audit；双实例并发输家读取赢家的精确结果。public response
只有结果 fence、expiry、checkpoint/target fingerprint 和 replay 标志，不泄漏 fixture/replay payload/
engine state/authority。legacy v1 没有 target locator，明确返回 migration-required。

第十二增量补上真实但仍内部化的 cold-signal recovery primitive。BLOGE 提交 `cb758c1af` 新增同步
`GraphEngine`/`DurableGraphEngine.resumeSuspended(...)`：它只接受无 active execution 的持久化
suspension，在调用线程内恢复 context、run-scoped resolver/provider 与 caller context，执行到 terminal
或下一 suspension 后返回 `GraphResult`，因此事务 owner 不需要轮询一个无法取消的 detached thread。
原有异步 `signal(...)` 兼容行为保留并复用同一准备/执行内核。

RG `IndependentDurableTestEngineFactory.openRecoverySession(...)` 只接受完整性已验证、exact target
存在、provider snapshot 可恢复且 lifecycle 为 `RESUMING` 的 v2 checkpoint；恢复 fixture cursor 后，
它冷加载 staged 四 store aggregate，并要求 BLOGE committed lifecycle 为 `SUSPENDED`、目标 signal wait
唯一存在。`signalAndAwait(...)` 同步运行到 terminal 或唯一新 suspension，校验 engine execution id 与
单调 version；`prepare(...)` 冻结下一 boundary sequence、实际 engine version、累计 fixture cursor 与
完整 aggregate mutation，再由 repository fence CAS 原子提交。数据库级用例证明 terminal advance
提交后 wait 被删除，也证明同步执行完成但未 prepare 时关闭 stage 会完整保留旧 suspension。

该 API 没有伪造 in-process hard timeout。若 operator 忽略 interrupt，单纯 future timeout 只能让调用方
先返回，不能阻止后台线程继续改变 staged state。工业级 wall-clock deadline 必须落在可终止 worker
进程/容器边界，并结合 lease expiry、fencing token、幂等副作用协议和 orphan reconciliation；这仍属于
公开 worker lifecycle 增量。

第十三增量把 owner-claim 的授权结论绑定成可持久化 worker handoff。authorizer 返回 exact 可执行 graph、
`CompiledExecutionControl` 与内容寻址 authorization receipt；receipt 不含 credential、group 原值、fixture/
replay payload 或 provider seed。repository 在 lease CAS、幂等 command result 与 audit 的同一事务中签发
dispatch，并把 source checkpoint authorization、结果 owner/epoch/revision/expiry 与 claimed checkpoint
完整串联。worker 内部可按 exact scope/run/fence/checkpoint lookup dispatch，但该查询只返回历史事实，
调用方仍必须在执行前核对 live checkpoint。旧 v1 command row 不会被猜测补造 dispatch；它明确 fail
closed，待旧 lease 过期后以新 command 重新授权接管。

第十四增量关闭 worker heartbeat 的 live-fence 与模糊响应窗口，并把它接到窄化的公开认证协议。
`bloge.durableTestRecoveryHeartbeatRequest.v1` 只允许 caller 提供 stable key、exact predecessor
owner/epoch/revision 与 checkpoint fingerprint；拒绝 caller-owned dispatch、authorization、owner、expiry
或 lease。adapter 从已提交历史记录解析 hidden source dispatch，并要求当前认证 principal 与 owner claim
receipt 完全一致；region、actor、delegation、purpose、clearance 与有序 groups 都进入 canonical fingerprint，
仅 correlation id 被排除。server 通过 `RG_TEST_DURABLE_HEARTBEAT_LEASE_SECONDS` 拥有 1..3600 秒续期。

`RecoveryHeartbeatCommand` 携带 caller-stable key、server-derived request fingerprint、exact source dispatch 和续期；repository
先从已提交 owner-claim 或 predecessor heartbeat 证明 dispatch 的签发谱系，再以数据库时钟要求该
dispatch 与 live `RESUMING` checkpoint 的 scope/execution/owner/epoch/revision/expiry/fingerprint 逐值一致且
尚未过期。成功只推进 control revision、`updatedAt` 和 lease deadline，plan/fixture/provider/cursor/engine
closure 保持逐值不变，并在同一事务写入 successor dispatch 与内容寻址
`bloge.durableRecoveryHeartbeatRecord.v1`。丢响应重试返回原 successor；换 key 复用旧 dispatch、离线构造
但未签发的自洽 dispatch、过期 owner、并发 CAS 输家、结果 JSON/索引篡改及 companion audit 失败均
fail closed。dispatch 指纹因此同时具备完整性和持久化来源证明，但仍不是身份凭据或 bearer token。
首次 heartbeat 与 `ALLOWED` semantic audit 原子提交，audit 失败不移动 fence；丢响应重试返回原
successor 并追加 replay audit。public response 仅投影 successor fence、数据库 expiry、checkpoint
fingerprint 与 replay 标志，不暴露 dispatch 或执行闭包。该 endpoint 只保持 ownership 活性，不负责
poll、运行、取消或 terminal evidence。

第十五增量关闭恢复完成时“引擎已变、控制行未变”以及响应丢失导致重复 mutation 的窗口。
`RecoveryTerminalCommand` 绑定 caller-stable key、认证意图指纹、exact source dispatch、归一化执行结果、
最终 fixture/provider/engine state 与非空 evidence gap code。repository 先证明 dispatch 的签发来源，再按
数据库时钟检查完整 live fence；成功时只执行一次与最终 `EngineState` 精确回绑的 BLOGE mutation，并把
terminal checkpoint、payload-free `bloge.durableTestRecoveryTerminalReceipt.v1`、不可变
`bloge.durableRecoveryTerminalCommandRecord.v1` 和可选 companion evidence/audit 写入同一事务。CAS 输家、
过期或未签发 dispatch、同键异意图、回执篡改和事务后段失败全部回滚或 fail closed；模糊重试不再执行
engine mutation，只返回原 checkpoint + receipt。当前 checkpoint 没有断点前完整 trace，因此 receipt v1
固定声明 `EVIDENCE_INCOMPLETE` 并显式列出 gap，不能进入 promotion，也不能被称为 signed terminal evidence。

第十六增量把内部 recovery session 与 terminal commit 收到一个公开但严格窄化的同步协议。
`bloge.durableTestTerminalRecoveryRequest.v1` 不接受 caller-owned outcome、dispatch、authorization、
engine state、fixture/provider cursor 或 evidence label，只接受 exact fence、stable key 和一个有界 signal。
服务按 canonical signal fingerprint 做运行前幂等查询，重验原 principal，重新授权完整 executable
closure，并要求 receipt 与已签发 dispatch 完全一致。`CompiledTestRuntimeOptions` 成为 fresh run 与 cold
recovery 的共享 operator/resource fixture lowering；原 signal 只进入内存。到达 terminal 后，开放的 staged
session 保持到 repository 原子消费 mutation 为止；再次 suspension、audit outage、stale/expired fence、
principal/authorization drift 或 transaction failure 均不留下 speculative BLOGE 状态。response 只投影
terminal fence、outcome、checkpoint/receipt fingerprint 和固定 evidence gaps，丢响应重试不再运行引擎。

第十七增量补上公开 durable checkpoint 查询，但严格把它限定为 observation，而不是 authority。
`GET /api/testing/durable-executions/{runId}` 只在 `test`/`staging` 装配，使用独立
`TEST_DURABLE_EXECUTION_READ` operation，并接受 `TEST_EXECUTION`/`TEST_REPLAY`。repository 先校验
sealed JSON、嵌套指纹和全部索引投影，service 再按 tenant/environment/org/project 做 non-disclosure
scope；不存在与跨组织/项目统一为 404，畸形 run id 在读库前拒绝，存储故障或任何投影漂移统一为
payload-free 503。`bloge.durableTestExecutionView.v1` 只投影 status、owner/epoch/revision/expiry、exact
target/fixture ref、plan/provider/fixture-ledger 指纹、payload-free engine boundary 与 aggregate
checkpoint fingerprint，不返回 context、fixture/replay value、provider cursor、authority、credential、
dispatch 或 BLOGE checkpoint body。v1 legacy row 可作为运维事实查询，但无 target 且固定
`migrationRequired=true`、`recoverable=false`。该 view 不是 lease reservation、bearer token 或实时存活
证明；owner claim 仍须用完整 fence 重新检查 live state 和 authorization。

`POST /api/testing/durable-executions` 只在 `test`/`staging` 装配，以
`TEST_DURABLE_EXECUTION_CREATE` 对 `TEST_EXECUTION`/`TEST_REPLAY` workload 鉴权。v1 request 只允许
caller-stable key、exact GRAPH fingerprint、`GRAPH_CONTRACT_TEST`、不超过 1 MiB 且无 control key
的业务 context 和 exact immutable fixture ref；inline/latest、operator target、caller-owned
run/engine/owner/lease 全部 fail closed。authorizer 在 reservation 前冻结 graph/input contract、
fixture/replay、authority/clearance、side-effect、provider 与 plan closure，authenticated intent
fingerprint 绑定完整 principal。

首次调用获得数据库时钟 preparation fence，在隔离 stage 到达唯一 live `WAIT_SIGNAL` 后，同事务
提交 revision-zero checkpoint、四 store mutation、immutable result 与 semantic audit。成功和确定性
rejection 都在 dependency reread 前 replay；live contender 得到 payload-free runId/expiry，expired
contender保留 run/engine identity 并递增 epoch 后接管。command/response/audit 均不携带 context、
fixture/replay value、provider seed/cursor、credential 或 checkpoint body。进程内 coordinator 以
exact `PENDING + owner + epoch + record fingerprint` 和数据库时钟 CAS 自动续租，只轮转 update time、
expiry 与 successor fingerprint；commit/reject 先等待在途心跳并冻结最新 successor。续租冲突、
存储异常或服务关停统一使 ownership 不确定，staged 状态被丢弃并返回 payload-free
`RG.TEST.DURABLE_CREATE_LEASE_LOST`。creation lease 为 3..3600 秒，心跳不大于三分之一 lease。
当前仍没有不可协作 operator 的进程内强制取消；fencing 只能阻止陈旧执行提交，真实 hard deadline
仍需可终止的进程/容器 worker。

第十八增量把 authenticated heartbeat 从独立手工协议接入同步 terminal recovery 的执行窗口。
`DurableTestRecoveryLeaseCoordinator` 在 runtime 访问前同步调用已解析 dispatch 的续租 seam，随后
按配置间隔只消费服务端签发的 successor；每次结果都必须保持 scope、principal authorization、
target、fixture、provider、engine、owner 和 epoch 不变，revision、updatedAt 与 expiry 严格前进。
`freeze()` 与 shutdown 线性化：它先停止并等待在途 heartbeat，再向 terminal command 交付最新
successor。初始续租失败不会启动 runtime；runtime 已 prepare 后发生失败则 try-with-resources 关闭
stage，且 repository terminal CAS 不会被调用。Spring 默认以 lease 三分之一调度，可通过
`RG_TEST_DURABLE_RECOVERY_HEARTBEAT_INTERVAL_SECONDS` 配置；同步 worker 装配要求 heartbeat lease
为 3..3600 秒，显式间隔为 1 秒至 lease 三分之一。能力探针公开
`automaticDurableRecoveryHeartbeat=true`，但它只陈述本进程同步执行，不陈述 dispatcher 或远程
worker 存活。

Stage 4 仍无 stream offset/checkpoint 恢复协议；runtime-state dispatch、
跨进程 worker supervision、异步/无界多 suspension 编排、强制 worker 取消、完整历史 terminal evidence、dispatcher 消费与通用 cold-start 编排、stream/event fixture、确定性并发调度、
identity/feature-flag/test-secret authority 与断点前历史 evidence 恢复尚未完成。因此当前已公开的是
“exact GRAPH/OPERATOR initial create + payload-free checkpoint query + bounded worker pull + 依赖重授权后的 ownership fence + authenticated lease renewal + 自动续租的 one-signal suspended-or-terminal recovery step + 1..16 signal 同步 recovery sequence + 兼容 terminal-only recovery”；它仍不等于
完整 cold-start durable worker 产品，Stage 4 继续保持进行中。

交付：

- `ExecutionServices`；
- logical clock、random seed、id generator；
- environment-dependent built-in `FunctionCallSite`；
- durable checkpoint/resume 的 plan/cursor 恢复；
- stream/event fixture；
- crash/recovery/fencing tests。

验收：同一 target + fixture + plan 在支持确定性声明的图上产生相同 `semanticResultFingerprint`；每次 evidence bundle 仍因 runId、时间和签名事件具有独立 fingerprint。并发节点不要求虚假的完成全序一致。

### Stage 5：企业规模化与生产硬隔离，按客户环境触发

交付：

- 独立 test runtime deployment；
- network/identity/secret/data store 隔离；
- tenant quota、batch scheduler、regional fixture store；
- sandbox binding registry；
- property/boundary/mutation test；
- flaky analysis、生产路径对比和反熵任务。

验收：完成渗透测试、故障注入、DR、容量、跨租户隔离和“test control 不可能进入 production data plane”的架构证明。

## 20. 优先级与工程拆分

| 优先级 | Epic | 为什么现在做 |
|---|---|---|
| P0 | 诚实区分 schema test 与 executable test | 消除错误正确性声明 |
| P0 | ExecutionPurpose + 生产隔离不变量 | 防止能力落地即成为生产后门 |
| P0 | InvocationSite + EffectiveExecutionPlan | 所有复杂 fixture、trace、resume 的共同地基 |
| P0 | Executable operator micro graph runner | 让 operator test 首次验证真实实现 |
| P0 | unmatched/unused/ambiguous fail closed | 杀死 mock 测试最常见的假阳性 |
| P1 | 统一 operator/graph/simulation fixture protocol | 消除三套测试模型漂移 |
| P1 | fault/retry/fallback/stream controls | 覆盖工业运行的主要错误路径 |
| P1 | semantic coverage + signed evidence | 让测试结果可进入 gate |
| P1 | function/time/random execution services | 提升重复性并覆盖 built-in function |
| P2 | durable test resume | 长运行和事件驱动图需要 |
| P2 | separate test deployment | 企业生产隔离目标态 |
| P2 | equivalent-mutant/flaky/statistical analysis | 在 generation-one property/mutation 主链之上继续提高可信度 |

## 21. 不建议采用的替代方案

### 21.1 在普通 run request 增加 `testMode` 和 `fixtures`

拒绝。它把生产后门做成 API 功能，purpose 可伪造，审计和部署隔离都不成立。

### 21.2 只在 Resource Gateway 重写 DSL，把 operator 替换成 mock operator

只适合作为过渡。它无法统一 compensation、nested graph、streaming、durable resume 和 function call，也会让运行证据指向改写后的 DSL 而不是原始业务 artifact。

### 21.3 直接把 `bloge-test` 作为生产依赖暴露

拒绝。`bloge-test` 是优秀的工程地基，但产品运行时需要版本化协议、权限、持久化、证据和隔离。可抽取通用 test-double primitives 到新的 runtime-safe 模块，不能把 JUnit-oriented harness 原样变成服务端控制面。

### 21.4 全部依赖真实 sandbox，避免 mock

拒绝。真实 sandbox 能验证 adapter contract，但速度、可用性、数据准备和错误注入都不可控，无法承载大规模 PR regression。正确策略是 mock contract 与 sandbox contract 分层。

### 21.5 把所有 operator 都 mock 掉，证明 DAG 拓扑即可

这就是现有 simulation 的安全预览价值，但不能代表业务正确性。至少被测主体和纯编排 primitive 必须真实执行，证据中要明确 REAL/MOCK 边界。

## 22. Definition of Done

这项能力进入工业可用至少满足：

1. 同一 suite 可从 UI、API、CLI 和 CI 运行且结果语义一致；
2. operator executable mode 确实调用冻结的 runtime binding；
3. 运行前能展示完整 EffectiveExecutionPlan；
4. unmatched、unused、ambiguous、schema-invalid fixture 均 fail closed；
5. nested/foreach/retry/compensation invocation 可稳定寻址；
6. production run API 无法携带或恢复 test control；
7. external write 默认 DENY，sandbox write 有 network/identity/journal 三重证明；
8. evidence 能区分 REAL、MOCK、REPLAY、SPY、DENY；
9. suite 绑定不可变 target/runtime/schema/fixture 指纹；
10. durable resume 不会因 fixture 丢失退化为 REAL；
11. 敏感 fixture 经过分类、脱敏、retention 和审计；
12. semantic coverage 可作为 gate policy 输入；
13. batch run 有租户配额、取消、超时、容量和故障恢复；
14. 有安全测试证明 test control 不能进入 production data plane；
15. 有 runbook 处理 fixture drift、evidence incomplete、sandbox 泄漏和 plan recovery failure。

## 23. 当前建议冻结的决策

| 决策 | 建议 | 可证伪条件 |
|---|---|---|
| 能力名称 | Execution Data Control Plane | 若只做画布临时 mock，不进入 API/CI/evidence，则名称过重 |
| 引擎入口 | 新增 `ExecutionOptions/ExecutionControlPlan` | 若 BLOGE 明确永远只支持短生命周期、无 nested/durable/function control，可缩小模型 |
| unmatched 默认 | FAIL | 仅纯 UI sample preview 可选择 `GENERATE_SCHEMA_SAMPLE`，且 evidence 明确降级 |
| production 隔离 | 目标态独立 test runtime deployment | 若部署环境极小，可暂时同进程，但必须 endpoint/profile/identity/network 四重隔离 |
| operator test | micro graph 执行真实 binding | 只有完全脱离 BLOGE runtime 的纯函数库才直接调用实现 |
| built-in function | 纯确定性不替换；环境依赖通过 runtime call site | 若所有 built-in 均经证明纯且确定性，可延后 function resolver |
| coverage | 业务语义 coverage | 代码 coverage 继续归 operator repo |
| evidence | 测试与生产 evidence 分级 | ANEKE 可消费二者，但不得混淆证明强度 |

## 24. 讨论需要继续收敛的核心问题

最重要的待确认不是 UI，而是隔离强度：

> 企业目标态是否接受“测试执行必须运行在独立 test runtime deployment，生产 runtime 在构建和启动配置上都不装载 fixture/test-double 能力”？

本文建议答案为“接受”。这会增加一个部署单元和部分运维成本，但换来的不是一般防误操作，而是对“测试数据控制反转不会改变生产业务行为”的结构性保证。若这个决策不冻结，后面的 API、token、feature flag 都只能降低风险，无法根治风险。

第二个问题现已按 ADR-002 落实：runtime binding 缺少版本化 composability manifest 时一律降级为 `OPAQUE_RUNTIME`；无状态和 READ_ONLY 不再自动授予认证。当前 v1 运行语义只放行 self-contained manifest 与内置 httpResource transport 边界，通用 dependency port 和 execution service 在真正可注入前继续 fail-closed。
