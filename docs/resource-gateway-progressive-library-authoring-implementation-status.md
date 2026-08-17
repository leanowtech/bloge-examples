# Resource Gateway 渐进式 Library Authoring 实现状态

> 状态：Implementation in progress
>
> 更新日期：2026-07-31
>
> 目标方案：[渐进式算子与 Built-in Function 库创作技术方案](resource-gateway-progressive-operator-function-library-authoring-technical-design.md)
>
> 说明：方案审计的 97 分评价设计成熟度，不代表本页所述功能已经实现。

## 1. 已完成：Canonical 地基、Stage 0/1、Stage 2 测试链、Stage 3 发现与 Stage 4 隔离切片

先把 Workbench 依赖的语法、编译和诊断协议做成可测试内核；随后完成持久化
draft、ETag 并发控制和 preview-fenced design catalog commit；当前已补齐样本推断审阅、
exact-draft operator contract test、进程隔离 function runner，以及 source-neutral
存量资产发现和 runtime parity 的可体验垂直切片。

| 能力 | 当前实现 | 验证 |
| --- | --- | --- |
| Authoring 合同 | `bloge.visualLibraryAuthoring.v1` Java model 与机器 Schema 支持 operator-only、function-only、mixed、named type、archetype、测试引用和 import 声明 | model、machine-schema、文档示例测试 |
| 安全解码 | JSON/YAML 统一进入有界 decoder；限制 5 MiB、深度、token、alias、集合和字符串；拒绝重复键、自定义 tag、递归 alias 与尾随正文 | 正向解析与攻击语料测试 |
| 类型与签名语法 | 有界 compact type parser 和 function signature parser；区分 optional 与 nullable；限制泛化复杂度、签名数和参数数 | parser 单元测试与 golden vectors |
| 确定性编译 | pure Java compiler 生成 canonical library、source map、diagnostic、confirmation request、readiness 与稳定 fingerprint | 同输入双编译字节一致测试 |
| Archetype 降低 | 九类 archetype 提供 effect、secret、binding、durable 等保守默认或待确认项 | compiler 正负向测试 |
| Stateless Preview API | `/preview` 接受 JSON/YAML，执行安全解码、编译、canonical 校验、目标 catalog 冲突预检、registry diff 与 readiness 合并 | service 与 MockMvc 测试 |
| 辅助 API | `/signature/parse` 提供即时签名诊断，`/catalogs` 返回语法、archetype、配额、feature flag 与 catalog fingerprint | MockMvc 与 capability 测试 |
| Source map | canonical path 可回溯到 authoring path，canonical 和冲突诊断可投影回用户输入位置 | compiler 与 service 测试 |
| Golden corpus | 固化 20 组黄金向量，覆盖 operator/function/mixed、类型、约束、overload、缺失事实、循环、冲突和安全负例 | 20 组向量固定 SHA-256 与诊断/readiness |
| 集成能力协商 | `/api/integration/capabilities` 声明 authoring 协议对象、端点及已实现/未实现 feature | capability contract 测试 |
| 使用资料 | 提供完整与 function-only YAML 示例、快速体验指南，并同步 canonical/Framework/VS Code 文档 | 示例实际经过 decoder/compiler 测试 |
| Function-only library | Java validator 和机器 schema 统一采用 `operators.size + builtInFunctions.size >= 1`，不再要求伪 operator | validator、controller、内存/数据库 registry 测试 |
| Callable identity | expression callable key 统一为 `function.name`；`namespace` 只保留 provenance/governance 语义 | callable contract 单元测试 |
| Callable fingerprint | 参数顺序、类型、schema、optional/variadic 和返回合同参与指纹；展示说明、示例和 namespace 不参与 | metadata/schema drift 测试 |
| Import preflight | 与系统默认函数或其他 library 的同名不兼容合同返回 `visual.library.functionCallableConflict` | MockMvc validate/import 测试 |
| Registry invariant | controller 被绕过时，内存与数据库 registry 仍拒绝同库重复、与系统默认函数冲突和跨库不兼容 callable | registry 单元与 H2 reload 测试 |
| Catalog safety | 相同 callable 合同去重；历史遗留不兼容数据被隔离并返回诊断，不再 first-wins 或 500 | effective catalog 测试 |
| Lifecycle parity | Function-only library 可完成 create、export、bundle import、revision diff、restore；函数合同变化进入 SemVer 治理 | controller 与 diff 测试 |
| Capability projection | capability catalog 以 `function.name` 检测冲突，不再被不同 namespace 绕过 | adapter/controller 测试 |
| 统一发现协议 | `bloge.visualAuthoringFactProjection.v1` 统一 source/projection fingerprint、声明/观察/runtime facts、dependency、parity、review 和 diagnostic；机器 Schema 固定 wire contract | projection machine-schema、稳定指纹与 diagnostic 序列化测试 |
| 五类 Source Adapter | Capability Catalog、AsyncAPI、OpenAPI、BLOGE DSL 与进程内 runtime inventory 进入同一投影；既有 source-specific endpoint 不改 request/response | 五路径 MockMvc 与 legacy controller 回归 |
| 保守草稿降低 | 只有可证明的声明合同生成 `bloge.visualLibraryAuthoring.v1`；DSL 只输出 topology/usage facts，不用 `any` 伪造 Schema；runtime implementation-only function 不生成空 function 草稿 | projector、DSL 和 runtime discovery 测试 |
| Runtime parity | operator/function 统一输出 `BOUND`、`DRIFTED`、`DOCUMENTED_ONLY`、`RUNTIME_DISCOVERED`、`BLOCKED_BY_POLICY`；只有 exact authoritative contract 允许 executable ready | operator/function exact-match、drift、unknown signature 和 policy 测试 |
| Framework function inventory | provider SPI 支持业务 runtime 输出 profile、实现指纹、purity、execution-service 依赖和可选权威合同；core provider 精确枚举 BLOGE 注册函数但因框架缺签名而停在 `RUNTIME_DISCOVERED` | provider 故障隔离、多实现歧义和 core inventory 测试 |
| Runtime-aware Preview | preview 固定 `runtimeInventoryFingerprint` 并返回逐资产 parity；全部精确绑定时进入 `RUNTIME_BOUND`，否则 production ready 为 false 但设计态可继续 | service exact authoritative function 与 unresolved gate 测试 |
| 有界同步发现 | 五类来源规范化后统一限制 10 MiB，超限返回 `413 RG.AUTHORING.DISCOVERY_SOURCE_LIMIT_EXCEEDED`，不回显来源正文 | controller 边界测试 |
| Wire-schema parity | 机器 schema 对 operator-only、function-only、mixed、empty、null-only 文档执行真实校验；可选函数 schema 接受 API 的显式 `null` 表示 | machine-schema 测试 |
| Durable authoring draft | H2 持久化当前 draft 与不可变 revision history，source mode、author、服务端时间和完整 draft fingerprint 随 revision 保存 | repository H2 测试与应用启动测试 |
| Enterprise-scoped draft | current/revision 表以 tenant、organization、project、environment、region、draft id 为复合主键；list/find/save/preview/infer/apply/commit 与 test/fixture/evidence 都只读取受信身份的完整作用域，同一 draft id 可被不同企业域独立使用 | H2 同名跨 scope、service、controller 与真实 HTTP lifecycle 测试 |
| Authoring RBAC 与可信归因 | READ、WRITE、COMMIT 分别绑定 `TEST_SUITE_READ`、`TEST_SUITE_WRITE`、`TEST_SCENARIO_PUBLISH`；scope 与 actor 来自 gateway authenticator，body 中兼容保留的 `actor` 不参与 savedBy/committedBy | 缺凭证、purpose 不匹配、body actor spoof 与可信 actor 测试 |
| Canonical library ownership | Workbench 首次提交新 library id 时在数据库唯一主键上原子 claim；同 scope 可继续修订，其他 scope 和并发抢占返回结构化冲突；已有 revision 但没有 ownership 的 legacy library 必须显式迁移，禁止静默认领 | 双 repository 实例唯一约束、跨 scope commit、legacy fail-closed 与 actor 归属测试 |
| ETag concurrency | save/preview/commit 必须携带最后观察到的 `If-Match` revision；并发创建和 stale writer 返回 `412`，无 last-write-wins | controller、service 与 HTTP 集成测试 |
| Preview-fenced commit | commit 重新读取 exact draft 并重新编译，同时校验 authoring、compiler、catalog、canonical fingerprint 和 target registry revision | source/catalog/canonical drift 负例及真实 registry commit 测试 |
| Commit audit receipt | 成功提交返回 draft/revision、四类 fingerprint、目标 library revision、canonical snapshot、预览证据、actor 与时间 | service 与 HTTP 集成测试 |
| Library Workbench | `/libraries/` 提供独立产品路由；Graph Author 的 Operator Library 入口可直接进入 | App route、Spring MVC forward 与打包配置测试 |
| 渐进式起始页 | Quick Create、Infer from Samples、Discover Existing Assets、Advanced Import 四入口；Discover 已提供 Runtime/DSL/Capability/AsyncAPI/OpenAPI 五标签和有效内置来源 | route/component 与五来源 API 测试 |
| 完整内置样例 | 客服分流、订单履约、风险决策三个样例均含 named types、operators、built-in functions 与 test refs | model/component 测试 |
| 结构化 Builder | Library Tree、named type field editor、九类 archetype、input/output tree/table、function overload、example/test ref 编辑器 | model/component 测试 |
| 权威 Preview UX | debounce autosave 后自动调用服务端 preview；展示 readiness、diagnostics、confirmation、canonical contract 与 exact commit receipt | Workbench 状态流测试 |
| 冲突恢复 | UI 可见 dirty/saving/saved/conflict/error 状态；`412` 后禁用预览并要求 Reload 权威 revision | Workbench ETag 冲突测试 |
| 视觉验收 | 1280×720 三栏无页面横向溢出；390×844 顺序折叠且只有资产树局部横向滚动；完整样例、operator 选择、autosave 和 `DESIGN_READY` preview 实机通过 | 本地打包 JAR + 真实浏览器 desktop/mobile 检查，console 无 error/warning |
| Multi-sample inference | 精确绑定 draft revision 与 operator port；最多 100 个 JSON 样本，输出稳定 candidate、observed facts、统计、拓宽原因、diagnostic 与 confirmation request | inferencer、decoder、machine-schema、controller 与真实 HTTP 测试 |
| 推断隐私边界 | 2 MiB/20,000 node/32 层及字段、数组、字符串上限；敏感值不进入 enum；原始 payload 不保存、不回显，`persistPayload=true` 明确拒绝 | 攻击边界、敏感值、错误语义和 HTTP 序列化测试 |
| 推断能力协商 | `/catalogs` 与 `/api/integration/capabilities` 声明 request/result object version、feature flag 与 revision-fenced endpoint | capability contract 测试 |
| 原子确认采用 | `POST .../infer/samples/apply` 重放原始 inference request、核对 evidence fingerprint、要求当前 confirmation 一一对应且值合法，再以 CAS 写入新 draft revision | applier、service、controller、严格 decoder 与真实 HTTP 测试 |
| Payload-free provenance | draft 保存 conservative/declared candidate、字段统计与人工决定，不保存 samples；同 target 新证据替换旧证据，手工改变声明 target 时自动失效 | repository round-trip、fingerprint、敏感样本与失效测试 |
| 图形化样本审阅 | Start 页和每个 operator Input/Output 均可启动；支持 JSON array/object/NDJSON、target 选择、candidate tree、observed fact、显式 confirmation queue、推荐项批量采用与原子 Apply | API/component/parser 状态流测试；真实浏览器 1440px、800px 与 390px 响应式验收 |
| 结构化 Schema 无损回显 | 推断得到的 object 不再降级为 `any`；字段树展开嵌套字段，同级重命名/required 编辑保留原始结构值 | model round-trip、Workbench apply 回显测试与真实服务持久化检查 |
| Exact-draft operator tests | 从当前未提交 canonical operator 自动生成 input/config/mock-output row；复用既有 assertion/schema 引擎，结果固定声明 `SCHEMA_CONTRACT` | explicit-definition service、artifact mismatch、机器 Schema 与前端状态流测试 |
| Function test protocol | `GOLDEN/NEGATIVE/BOUNDARY/REGRESSION` case 与 `EQUALS/RETURN_TYPE/EXPECT_ERROR` assertion；返回 binding/case status 和四层 fingerprint evidence | Java model、四份 request machine schema 与 protocol client 测试 |
| 进程隔离 function runner | 每行使用 one-shot JVM，只运行 BLOGE core inventory 中 exact-name、pure、无 execution-service 依赖的函数；双端 fingerprint attestation，64 MiB heap、96 MiB metaspace、16 MiB direct memory、250 ms watchdog、5 秒 supervisor kill、15 秒 suite deadline、并发 2 且饱和立即失败 | worker protocol/machine schema、真实子进程、fat JAR、资源耗尽后恢复、Spring HTTP lifecycle、`BOUND/UNBOUND/BLOCKED_BY_POLICY` 与 UI execution profile 测试 |
| 签名测试证据 | operator/function run 成功返回前，经 visual-owned access port 认证五维 enterprise scope，以 draft/canonical/artifact/runtime/suite/policy fingerprint、payload-free case summary 和 coverage 生成不可变 material fingerprint 与 detached seal；case id/test ref 只以稳定 SHA-256 伪名进入证据，签名或落库失败时 fail closed | service、JDBC、真实签名、重复 run、原文泄漏、跨 scope、投影篡改与正文篡改测试 |
| 实时 stale lifecycle | evidence read 每次验签并对当前 authoring/canonical/artifact/runtime/execution-profile/policy 重算 `CURRENT/STALE`，不信任持久化状态 | draft contract drift、逐项 stale reason、机器 Schema 与 HTTP 集成测试 |
| `TEST_EVIDENCED` gate | 对当前草稿每个 operator/function 使用最新一次证据；缺失、stale、latest failed、case/assertion coverage 不足和 function unbound 均阻断 | latest-run-wins、passing-to-blocked、逐资产 reason 与 HTTP 集成测试 |
| Evidence UX | 测试浮层将行级结果、`SIGNED CURRENT/STALE`、证据 fingerprint、draft gate 覆盖率和阻断原因分层展示；编辑任一测试行即清除旧治理状态 | component 与 API credential/purpose 测试 |
| 图形化 Test Table | Operator/Function Builder 均可打开独立浮层；支持自动生成、JSON 编辑、case kind/assertion、增加/删除、单行/批量运行、逐行结果与 evidence 摘要 | component/API、真实 HTTP 测试；1440×900 与 390×844 浏览器验收 |
| 临时测试隐私与边界 | 最多 50 行、32 参数、256 KiB suite、512 KiB result、250 ms function timeout；response 固定 `payloadPersisted=false`，诊断不包含 arguments | machine schema、bounded service 与敏感参数诊断测试 |
| Governed fixture 协议 | 保存命令、payload-free receipt 与授权 material read 各自版本化；保存绑定 exact draft、asset fingerprint、payload fingerprint、不可变 revision 与不可改绑 lineage | 3 份机器 Schema、capability、HTTP contract 与 lineage bypass 测试 |
| Fixture 隔离与隐私 | 五维 enterprise scope、purpose/clearance、显式 JSON Pointer 与敏感键自动脱敏、256 KiB 上限、最长 30 天 retention | service 正负例、跨 scope 与低 clearance 测试 |
| 加密仓储与审计 | AES-256-GCM versioned key ring，完整 scope/draft/artifact AAD；fixture 与 payload-free 安全事件同事务；到期擦除密文并保留完整性 tombstone | H2 仓储、rollback、projection/commitment tamper 与真实 Spring profile 测试 |
| 图形化 Fixture 保存 | 样本推断结果和 operator/function test row 均可显式打开 Save as fixture；必须确认 fixture id、classification、retention、redaction paths 和测试数据声明；脱敏输入按当前 payload 给出可解析 JSON Pointer 示例；成功态只显示版本、到期时间和 fingerprint | API/component 状态流、能力禁用、payload-free receipt 与生产构建测试；真实浏览器分别完成 operator row 与 sample 的加密保存，1440×900、390×844 无横向溢出且 console 无 error/warning |
| 图形化存量发现 | 起始页直接扫描五类来源，按 summary、asset facts、runtime parity、review queue 分层展示；有安全结构化草稿时一键进入 Builder；DSL 通过一次性同源 handoff 自动进入 Graph Author、投影并布局 | API/component/handoff 测试；真实浏览器证据见本页验收记录 |

旧的 operator-only library constructor、raw JSON/YAML validate/import endpoint、revision 和 registry
存储格式保持兼容。Stage 0 定向回归共 143 个测试通过，其中包含既有 raw import
控制器的 104 个用例；Stage 2.4 新增/扩展 14 个 operator/function service 与机器合同定向用例，
并由 Spring Boot 真实 HTTP lifecycle 用例覆盖四个测试端点、ETag 和 payload-free evidence。
Stage 2.5 新增 17 个 fixture service/repository/controller/schema 定向用例，并由完整
`test` profile 应用启动测试证明 vault、retention worker、controller 与 capability 同时装配。
Stage 2.6 的 worker、机器 Schema、Spring lifecycle 与能力协议定向回归共 66 个用例全绿；
完整 Resource Gateway `verify` 共执行 5,841 个测试，0 failure、0 error、10 skipped；
DSL topology-only review 的不可变集合回归修复又通过定向 controller 测试。
Workbench 前端生产构建通过，38 个前端测试文件共 357 个用例全绿，其中包含样本解析、
完整推断状态流、结构化 Schema 无损往返、operator/function test table 和显式 fixture 保存。
完整 frontend profile fat JAR 又经真实浏览器试跑：`trim` 在
`bloge-core-isolated-process.v1` 下返回 `PASSED` 并生成 evidence fingerprint，desktop
浮层无覆盖；390×844 下页面无整体横向溢出，宽表只在自身容器滚动，console 无
error/warning。Stage 2.7 进一步以 `support:classify-ticket` 完成桌面和 390×844
真实 Chrome 端到端复核：运行后显示 `SIGNED CURRENT`、evidence fingerprint、
`Draft gate 1/5` 与 `PASSED`；浮层不越出移动视口，页面无整体横向溢出，console 无
error/warning。Stage 3 使用 frontend profile fat JAR 完成五来源实测：runtime inventory
返回 6 个 operator 与 75 个 function，Capability Catalog 安全进入结构化 Builder；
BLOGE DSL 扫描得到 `supportRouting` 的 graph/operator/function/dependency facts，点击
**Open Graph Author** 后自动投影并布局为 2 nodes / 1 edge。1280×720 和 390×844
均无页面横向溢出，浏览器 console 为空。

## 2. 当前权威规则

```text
library is non-empty
  := nonNullOperators > 0 OR nonNullBuiltInFunctions > 0

callable key
  := builtInFunction.name

compatible duplicate
  := same callable key AND same callable fingerprint

incompatible duplicate
  := blocking diagnostic at API boundary
     + IllegalArgumentException at registry boundary
     + quarantine with structured warning at effective catalog assembly
```

三道防线是刻意设计的：

1. API preflight 给用户可定位的诊断；
2. registry 防止内部调用、恢复或未来入口绕过校验；
3. catalog 隔离历史脏数据中的歧义 callable，并用结构化 warning 暴露修复任务。

## 3. 当前边界

已经可依赖的能力：

1. 同一合法输入产生字节级稳定 canonical、source map、fingerprint 和诊断；
2. parser/compiler 不访问 registry，不执行函数或表达式；
3. 服务层预览明确绑定目标 catalog fingerprint，并暴露 callable 冲突；
4. feature flag 对未实现能力返回 `false`，客户端不需要猜测部署能力。
   当前能力探针明确返回 `visualLibraryAuthoringIsolatedFunctionTestWorker=true`、
   `visualLibraryAuthoringSignedTestEvidence=true`、
   `visualLibraryAuthoringTestEvidenceGate=true`、
   `visualLibraryAuthoringEnterpriseScopedDrafts=true` 和
   `visualLibraryAuthoringTrustedActorAttribution=true`；Stage 3 还明确返回
   `visualLibraryAuthoringDiscoveryFacts=true`、
   `visualLibraryAuthoringRuntimeParity=true` 和
   `visualLibraryAuthoringFrameworkFunctionInventory=true`。认证由 gateway integration adapter
   映射到 visual-owned access port，visual authoring 内核不反向依赖 gateway
   integration/testing 类型。

尚未实现的能力：

1. operator runner 当前只证明 schema/mock/assertion 一致性，不调用 runtime binding；
   function runner 已隔离受信 core inventory，但尚不允许客户自定义 binary function；后者需要
   container/cgroup/namespace/seccomp 或远端 worker，不能扩大本地 process profile；
2. diagnostic 已可点击定位，但还没有可审计的自动 Fix-it；测试 evidence 已持久化、签名并
   进入 `TEST_EVIDENCED` baseline，但该 baseline 刻意不等于 ANEKE publish gate，也尚未接入
   owner approval、breaking migration、SLA、secret policy 或 production runtime readiness；
3. `imports` 当前只保留声明，跨 library type resolution 会被明确拒绝；
4. preview impact 只描述当前 operator ref 与 registry revision 差异，不等同于 graph、
   publication 或运行时 binding 的全链路影响分析；
5. authoring draft 已完成五维 scope、purpose 隔离和可信 actor 归因，但还没有审批流、
   mutation outcome 审计事件、指标、分页、分布式限流和持久化配额；
6. Workbench commit 已用数据库唯一键保护 canonical library id 的跨 scope ownership；
   但 callable name 的跨 library 冲突仍依赖应用层 registry 快照，其他 legacy/admin
   catalog 写入口也尚未统一进入 ownership policy。ownership transfer、双人审批和自动迁移
   工具尚未交付；
7. 服务端 canonical candidate 与进程内 BLOGE runtime 已建立逐资产 parity，但尚未建立
   Builder、VS Code、本地/远端 compiler 的 cross-implementation golden parity；
   BLOGE core inventory 仍缺权威 function signature SPI，因此 core function 只能证明
   `RUNTIME_DISCOVERED`，不能升级成 `BOUND`；
8. Workbench 已完成样本推断、测试浮层和治理型 Fixture 保存的真实浏览器
   desktop/mobile 检查：operator
   `SCHEMA_CONTRACT/PASSED`、`trim` 的 `BOUND/PASSED`、自定义函数的
   `UNBOUND/NOT_RUN` 均通过；operator row 与 sample 均产生 encrypted、payload-free
   receipt；390×844 页面无横向溢出，表格局部滚动，Esc 恢复入口焦点，console 无
   error/warning。存量 DSL 还完成了一次性 `sessionStorage` handoff、自动 preview、
   auto-layout 和 mobile/desktop 端到端验收；handoff 最长 10 分钟、500,000 字符，
   读取后即删除，失败时回到显式 DSL import。尚无固定截图回归、任务计时和独立无障碍验收证据。

## 4. 目标差距

以下评分只用于迭代收敛，不等同于产品成熟度评分。按目标方案交付面加权，当前约完成
**95.1%**，剩余差距约 **4.9%**。

| 交付面 | 权重 | 当前完成 | 主要缺口 |
| --- | ---: | ---: | --- |
| Canonical 兼容与安全地基 | 15 | 14.2 | capability negotiation 已覆盖 discovery；仍缺 compatible alias 的显式 owner/provenance model |
| Authoring model、grammar、compiler、source map | 20 | 19 | 跨库类型 resolution 与多实现 parity |
| Draft/preview/commit lifecycle | 12 | 11.7 | durable revision、ETag、五重栅栏、五维 scope 和 library-id 原子 ownership 完成；缺受治理 ownership migration/transfer |
| 图形化 Workbench 与渐进披露 | 18 | 17.5 | 已增加发现、签名、新鲜度和 draft gate 的分层反馈；缺可审计 Fix-it、任务计时和固定视觉回归 |
| Sample inference、confirmation、fixture/test | 10 | 10 | infer/confirmation、operator/function test、governed fixture 与受信 core process runner 闭环完成；签名 evidence 计入治理交付面 |
| Discovery adapter 与 runtime parity | 8 | 7.6 | 五来源统一投影、runtime SPI、fail-closed parity 和 DSL 画布 handoff 已完成；缺 core 权威签名、异步大制品与跨 runtime golden |
| 企业级隔离、配额、审计、可观测性 | 10 | 8.3 | fixture/evidence/draft 已统一五维 scope 与 purpose；Workbench catalog commit 有 DB ownership；缺审批、结果审计、分页、持久配额、分布式限流和指标 |
| 文档、golden、browser、parity 证据 | 7 | 6.8 | 统一发现协议、Draw.io 架构图、操作说明与 desktop/mobile 实测完成；缺固定视觉回归与跨实现 parity 证据 |
| **合计** | **100** | **95.1** | **差距 4.9%** |

当前数据库 ownership 表原子保护的是 canonical `libraryId`，不是跨 library 的
function callable name。后者的冲突检查仍基于进程内 registry 快照，能保护单实例及普通
H2/JDBC 使用，但还不是多副本并发写入下的原子全局约束。工业化阶段仍需引入规范化
callable ownership 表、数据库唯一约束或可证明的串行化事务，不能仅依赖应用层 preflight。

兼容的同名 callable 目前会在 effective catalog 中折叠成一份定义，来源 library 仍可分别导出，但 catalog contract 尚不能显式表达多个 owner/alias。它和协议 capability negotiation 都保留为后续兼容性工作，不能视为本轮已经完成。

## 5. 下一迭代

下一步集中收敛 Stage 4：

1. 将 operator runtime test 下沉到同等级 worker；自定义 binary function 使用容器/远端 sandbox；
2. 把 `TEST_EVIDENCED` baseline 作为输入接入 ANEKE workbook/publish gate，保留 owner、migration、
   SLA、secret 和 production readiness 的独立治理权；
3. 推动 BLOGE framework 暴露权威 function signature inventory，并把业务 provider 的
   generation/attestation、inventory stale lifecycle 和目标环境 profile 选择补齐；
4. 为 legacy catalog 提供受治理 ownership migration/transfer，并把 callable ownership、
   mutation outcome audit、分页、持久配额、分布式限流和指标补齐；
5. 补可审计 Fix-it、键盘任务流、无障碍扫描、60/30 秒任务计时和固定视觉回归；
6. 将同步发现的大制品边界升级为带 quota、进度、取消、隔离解析与审计 receipt 的异步任务。

Stage 1 Exit Gate 是新用户可只用 Builder 完成 pure operator 与 overload function 定义，
诊断可定位回字段，两个浏览器标签制造 stale preview 时旧标签提交被阻断，提交产物与当前
compiler golden 输出一致。
