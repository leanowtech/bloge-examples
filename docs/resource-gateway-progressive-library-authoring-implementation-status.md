# Resource Gateway 渐进式 Library Authoring 实现状态

> 状态：Implementation in progress
>
> 更新日期：2026-07-30
>
> 目标方案：[渐进式算子与 Built-in Function 库创作技术方案](resource-gateway-progressive-operator-function-library-authoring-technical-design.md)
>
> 说明：方案审计的 97 分评价设计成熟度，不代表本页所述功能已经实现。

## 1. 已完成：Canonical 地基、Stage 0 与 Stage 1 生命周期

先把 Workbench 依赖的语法、编译和诊断协议做成可测试内核；随后完成持久化
draft、ETag 并发控制和 preview-fenced design catalog commit。图形化 Workbench 仍在实施。

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

旧的 operator-only library constructor、raw JSON/YAML validate/import endpoint、revision 和 registry
存储格式保持兼容。Stage 0 定向回归共 143 个测试通过，其中包含既有 raw import
控制器的 104 个用例；新增 lifecycle repository/service/controller 与真实 HTTP 闭环
共 8 个专门用例，并继续通过完整应用上下文回归。

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

1. 没有 Library Workbench、字段树、Builder、Fix-it 和 Readiness 可视化页面；autosave
   协议已经可用，但浏览器端尚未接入；
2. 没有 sample inference、fixture 生成/解析、operator/function test runner；
3. `imports` 当前只保留声明，跨 library type resolution 会被明确拒绝；
4. preview impact 只描述当前 operator ref 与 registry revision 差异，不等同于 graph、
   publication 或运行时 binding 的全链路影响分析；
5. authoring draft 尚未按 tenant/organization/project/environment/region 隔离，也没有专属
   RBAC、审批、审计事件、指标、分布式限流和持久化配额；
6. commit 在单实例事务边界内重新校验所有栅栏，但 callable 全局冲突仍依赖应用层 registry
   快照，多副本并发写入尚无数据库唯一所有权约束；
7. 尚未建立 Builder、canonical API、VS Code、本地/远端 compiler 和 BLOGE runtime 的多实现 parity；
8. 尚无浏览器端视觉与无障碍验收证据。

## 4. 目标差距

以下评分只用于迭代收敛，不等同于产品成熟度评分。按目标方案交付面加权，当前约完成
**58%**，剩余差距约 **42%**。

| 交付面 | 权重 | 当前完成 | 主要缺口 |
| --- | ---: | ---: | --- |
| Canonical 兼容与安全地基 | 15 | 14 | capability negotiation、compatible alias 的显式 owner/provenance model |
| Authoring model、grammar、compiler、source map | 20 | 19 | 跨库类型 resolution 与多实现 parity |
| Draft/preview/commit lifecycle | 12 | 11 | durable revision、ETag 和五重栅栏完成；缺 tenant-scope、多副本原子 ownership |
| 图形化 Workbench 与渐进披露 | 18 | 0 | Start/Tree/Builder/Preview/Readiness |
| Sample inference、confirmation、fixture/test | 10 | 1 | 只有 test ref 与 confirmation contract；缺 observed/confirmed 证据链及 runner |
| Discovery adapter 与 runtime parity | 8 | 3 | 已有 adapters；尚未统一 authoring fact projection |
| 企业级隔离、配额、审计、可观测性 | 10 | 4 | 可复用 registry/revision 基础；缺 authoring 专属控制面 |
| 文档、golden、browser、parity 证据 | 7 | 6 | 文档与 compiler golden 完成；缺浏览器与跨实现 parity 证据 |
| **合计** | **100** | **58** | **差距 42%** |

当前数据库 registry 的 callable 冲突检查基于进程内快照，能保护单实例及普通 H2/JDBC 使用，但还不是多副本并发写入下的原子全局约束。工业化阶段仍需引入规范化 callable ownership 表、数据库唯一约束或可证明的串行化事务，不能仅依赖应用层 preflight。

兼容的同名 callable 目前会在 effective catalog 中折叠成一份定义，来源 library 仍可分别导出，但 catalog contract 尚不能显式表达多个 owner/alias。它和协议 capability negotiation 都保留为后续兼容性工作，不能视为本轮已经完成。

## 5. 下一迭代

下一步进入 Stage 1 图形化垂直切片：

1. 以四种入口创建或导入 library draft；
2. 实现带稳定节点 identity 的 Library Tree、operator/type/function Builder；
3. 接入 canonical preview、source-map 跳转、diagnostic Fix-it 与 readiness；
4. 接入 debounce autosave、可见 save state、ETag conflict recovery 和 preview-fenced commit；
5. 加入 tenant/RBAC、配额和审计边界，不能让浏览器直接绕过治理调用 raw import。

Stage 1 Exit Gate 是新用户可只用 Builder 完成 pure operator 与 overload function 定义，
诊断可定位回字段，两个浏览器标签制造 stale preview 时旧标签提交被阻断，提交产物与当前
compiler golden 输出一致。
