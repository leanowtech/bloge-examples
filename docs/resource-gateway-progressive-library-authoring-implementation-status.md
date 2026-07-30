# Resource Gateway 渐进式 Library Authoring 实现状态

> 状态：Implementation in progress
>
> 更新日期：2026-07-30
>
> 目标方案：[渐进式算子与 Built-in Function 库创作技术方案](resource-gateway-progressive-operator-function-library-authoring-technical-design.md)
>
> 说明：方案审计的 97 分评价设计成熟度，不代表本页所述功能已经实现。

## 1. 已完成：Canonical 地基、Stage 0、Stage 1 与 Stage 2.5 治理型 Fixture 后端

先把 Workbench 依赖的语法、编译和诊断协议做成可测试内核；随后完成持久化
draft、ETag 并发控制和 preview-fenced design catalog commit；当前已补齐样本推断审阅、
exact-draft operator contract test 和受限 function runner 的可体验垂直切片。

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
| Wire-schema parity | 机器 schema 对 operator-only、function-only、mixed、empty、null-only 文档执行真实校验；可选函数 schema 接受 API 的显式 `null` 表示 | machine-schema 测试 |
| Durable authoring draft | H2 持久化当前 draft 与不可变 revision history，source mode、author、服务端时间和完整 draft fingerprint 随 revision 保存 | repository H2 测试与应用启动测试 |
| ETag concurrency | save/preview/commit 必须携带最后观察到的 `If-Match` revision；并发创建和 stale writer 返回 `412`，无 last-write-wins | controller、service 与 HTTP 集成测试 |
| Preview-fenced commit | commit 重新读取 exact draft 并重新编译，同时校验 authoring、compiler、catalog、canonical fingerprint 和 target registry revision | source/catalog/canonical drift 负例及真实 registry commit 测试 |
| Commit audit receipt | 成功提交返回 draft/revision、四类 fingerprint、目标 library revision、canonical snapshot、预览证据、actor 与时间 | service 与 HTTP 集成测试 |
| Library Workbench | `/libraries/` 提供独立产品路由；Graph Author 的 Operator Library 入口可直接进入 | App route、Spring MVC forward 与打包配置测试 |
| 渐进式起始页 | Quick Create、Infer from Samples、Discover Existing Assets、Advanced Import 四入口；未实现路径明确标为 handoff，不伪装可用 | route/component 测试 |
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
| 受限 function runner | 只运行 BLOGE core inventory 中 exact-name、pure、无 execution-service 依赖并被本地 profile 允许的函数；未绑定和高风险函数 fail closed | `BOUND/UNBOUND/BLOCKED_BY_POLICY`、成功、断言失败、隐私和 stale revision 测试 |
| 图形化 Test Table | Operator/Function Builder 均可打开独立浮层；支持自动生成、JSON 编辑、case kind/assertion、增加/删除、单行/批量运行、逐行结果与 evidence 摘要 | component/API、真实 HTTP 测试；1440×900 与 390×844 浏览器验收 |
| 临时测试隐私与边界 | 最多 50 行、32 参数、256 KiB suite、512 KiB result、250 ms function timeout；response 固定 `payloadPersisted=false`，诊断不包含 arguments | machine schema、bounded service 与敏感参数诊断测试 |
| Governed fixture 协议 | 保存命令、payload-free receipt 与授权 material read 各自版本化；保存绑定 exact draft、asset fingerprint、payload fingerprint、不可变 revision 与不可改绑 lineage | 3 份机器 Schema、capability、HTTP contract 与 lineage bypass 测试 |
| Fixture 隔离与隐私 | 五维 enterprise scope、purpose/clearance、显式 JSON Pointer 与敏感键自动脱敏、256 KiB 上限、最长 30 天 retention | service 正负例、跨 scope 与低 clearance 测试 |
| 加密仓储与审计 | AES-256-GCM versioned key ring，完整 scope/draft/artifact AAD；fixture 与 payload-free 安全事件同事务；到期擦除密文并保留完整性 tombstone | H2 仓储、rollback、projection/commitment tamper 与真实 Spring profile 测试 |

旧的 operator-only library constructor、raw JSON/YAML validate/import endpoint、revision 和 registry
存储格式保持兼容。Stage 0 定向回归共 143 个测试通过，其中包含既有 raw import
控制器的 104 个用例；Stage 2.4 新增/扩展 14 个 operator/function service 与机器合同定向用例，
并由 Spring Boot 真实 HTTP lifecycle 用例覆盖四个测试端点、ETag 和 payload-free evidence。
Stage 2.5 新增 17 个 fixture service/repository/controller/schema 定向用例，并由完整
`test` profile 应用启动测试证明 vault、retention worker、controller 与 capability 同时装配。
Workbench 前端生产构建通过，36 个前端测试文件共 347 个用例全绿，其中包含样本解析、
完整推断状态流、结构化 Schema 无损往返以及 operator/function test table。

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

尚未实现的能力：

1. Governed fixture 服务端协议、加密仓储和 API 已闭环；Workbench 尚未提供把样本或
   临时测试行显式保存为 fixture 的图形入口；
2. operator runner 当前只证明 schema/mock/assertion 一致性，不调用 runtime binding；
   function runner 是受信 core inventory 的进程内快速反馈，不是 CPU/内存强隔离的生产证据；
3. diagnostic 已可点击定位，但还没有可审计的自动 Fix-it；测试 evidence 当前只在响应中，
   尚未持久化、签名、进入 publish gate 或自动管理 stale lifecycle；
4. `imports` 当前只保留声明，跨 library type resolution 会被明确拒绝；
5. preview impact 只描述当前 operator ref 与 registry revision 差异，不等同于 graph、
   publication 或运行时 binding 的全链路影响分析；
6. authoring draft 尚未按 tenant/organization/project/environment/region 隔离，也没有专属
   RBAC、审批、审计事件、指标、分布式限流和持久化配额；
7. commit 在单实例事务边界内重新校验所有栅栏，但 callable 全局冲突仍依赖应用层 registry
   快照，多副本并发写入尚无数据库唯一所有权约束；
8. 尚未建立 Builder、canonical API、VS Code、本地/远端 compiler 和 BLOGE runtime 的多实现 parity；
9. Workbench 已完成样本推断与测试浮层的真实浏览器 desktop/mobile 检查：operator
   `SCHEMA_CONTRACT/PASSED`、`trim` 的 `BOUND/PASSED`、自定义函数的
   `UNBOUND/NOT_RUN` 均通过；390×844 页面无横向溢出，表格局部滚动，Esc 恢复入口焦点，
   console 无 error/warning。尚无固定截图回归、任务计时和独立无障碍验收证据。

## 4. 目标差距

以下评分只用于迭代收敛，不等同于产品成熟度评分。按目标方案交付面加权，当前约完成
**86%**，剩余差距约 **14%**。

| 交付面 | 权重 | 当前完成 | 主要缺口 |
| --- | ---: | ---: | --- |
| Canonical 兼容与安全地基 | 15 | 14 | capability negotiation、compatible alias 的显式 owner/provenance model |
| Authoring model、grammar、compiler、source map | 20 | 19 | 跨库类型 resolution 与多实现 parity |
| Draft/preview/commit lifecycle | 12 | 11 | durable revision、ETag 和五重栅栏完成；缺 tenant-scope、多副本原子 ownership |
| 图形化 Workbench 与渐进披露 | 18 | 17 | Start/Tree/Builder/Preview/Readiness、autosave、冲突恢复、exact commit、样本审阅和测试表完成；缺可审计 Fix-it、任务计时和固定视觉回归 |
| Sample inference、confirmation、fixture/test | 10 | 9.7 | infer/confirmation、operator/function test 与 governed fixture 后端完成；缺 Workbench 保存入口、生产隔离 runner 与持久化签名 evidence |
| Discovery adapter 与 runtime parity | 8 | 3 | 已有 adapters；尚未统一 authoring fact projection |
| 企业级隔离、配额、审计、可观测性 | 10 | 5.5 | fixture 已有五维 scope、purpose/clearance、事务审计、加密和 retention；draft/catalog 仍缺同等级控制面 |
| 文档、golden、browser、parity 证据 | 7 | 6.3 | 文档、机器 fixture schema、compiler golden 和真实浏览器证据完成；缺固定视觉回归与跨实现 parity 证据 |
| **合计** | **100** | **86** | **差距 14%** |

当前数据库 registry 的 callable 冲突检查基于进程内快照，能保护单实例及普通 H2/JDBC 使用，但还不是多副本并发写入下的原子全局约束。工业化阶段仍需引入规范化 callable ownership 表、数据库唯一约束或可证明的串行化事务，不能仅依赖应用层 preflight。

兼容的同名 callable 目前会在 effective catalog 中折叠成一份定义，来源 library 仍可分别导出，但 catalog contract 尚不能显式表达多个 owner/alias。它和协议 capability negotiation 都保留为后续兼容性工作，不能视为本轮已经完成。

## 5. 下一迭代

下一步完成 Stage 2 的治理型测试资产闭环：

1. 提供显式 **Save as fixture**，将 sample 或临时 case 转成受审 test asset，绝不自动保存；
2. 将 operator runtime test 与 function test 下沉到独立 worker/container sandbox，硬限制 CPU、内存、网络、文件和 secret；
3. 持久化并签名 evidence，建立 artifact/suite/runtime drift 后的 stale lifecycle 和 publish gate；
4. 补可审计 Fix-it、键盘任务流、无障碍扫描与 60/30 秒任务计时；
5. 将 discovery adapters 收敛为统一 authoring fact projection；
6. 把 fixture 已有的 enterprise scope、RBAC、审计、加密和配额边界扩展到 draft/catalog。

Stage 1 Exit Gate 是新用户可只用 Builder 完成 pure operator 与 overload function 定义，
诊断可定位回字段，两个浏览器标签制造 stale preview 时旧标签提交被阻断，提交产物与当前
compiler golden 输出一致。
