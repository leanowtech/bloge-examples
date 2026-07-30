# Resource Gateway 渐进式 Library Authoring 实现状态

> 状态：Implementation in progress
>
> 更新日期：2026-07-30
>
> 目标方案：[渐进式算子与 Built-in Function 库创作技术方案](resource-gateway-progressive-operator-function-library-authoring-technical-design.md)
>
> 说明：方案审计的 97 分评价设计成熟度，不代表本页所述功能已经实现。

## 1. 本轮已完成

本轮先修复 canonical 层会阻塞后续 Builder/compiler 的两个根问题。

| 能力 | 当前实现 | 验证 |
| --- | --- | --- |
| Function-only library | Java validator 和机器 schema 统一采用 `operators.size + builtInFunctions.size >= 1`，不再要求伪 operator | validator、controller、内存/数据库 registry 测试 |
| Callable identity | expression callable key 统一为 `function.name`；`namespace` 只保留 provenance/governance 语义 | callable contract 单元测试 |
| Callable fingerprint | 参数顺序、类型、schema、optional/variadic 和返回合同参与指纹；展示说明、示例和 namespace 不参与 | metadata/schema drift 测试 |
| Import preflight | 与系统默认函数或其他 library 的同名不兼容合同返回 `visual.library.functionCallableConflict` | MockMvc validate/import 测试 |
| Registry invariant | controller 被绕过时，内存与数据库 registry 仍拒绝同库重复、与系统默认函数冲突和跨库不兼容 callable | registry 单元与 H2 reload 测试 |
| Catalog safety | 相同 callable 合同去重；历史遗留不兼容数据被隔离并返回诊断，不再 first-wins 或 500 | effective catalog 测试 |
| Lifecycle parity | Function-only library 可完成 create、export、bundle import、revision diff、restore；函数合同变化进入 SemVer 治理 | controller 与 diff 测试 |
| Capability projection | capability catalog 以 `function.name` 检测冲突，不再被不同 namespace 绕过 | adapter/controller 测试 |
| Wire-schema parity | 机器 schema 对 operator-only、function-only、mixed、empty、null-only 文档执行真实校验；可选函数 schema 接受 API 的显式 `null` 表示 | machine-schema 测试 |

旧的 operator-only library constructor、raw JSON/YAML validate/import endpoint、revision 和 registry 存储格式保持兼容。

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

## 3. 目标差距

以下评分只用于迭代收敛，不等同于产品成熟度评分。按目标方案交付面加权，当前约完成 **24%**，剩余差距约 **76%**。

| 交付面 | 权重 | 当前完成 | 主要缺口 |
| --- | ---: | ---: | --- |
| Canonical 兼容与安全地基 | 15 | 14 | capability negotiation、compatible alias 的显式 owner/provenance model |
| Authoring model、grammar、compiler、source map | 20 | 0 | Stage 0 主体尚未实现 |
| Draft/preview/commit lifecycle | 12 | 0 | ETag、stale preview、原子 commit |
| 图形化 Workbench 与渐进披露 | 18 | 0 | Start/Tree/Builder/Preview/Readiness |
| Sample inference、confirmation、fixture/test | 10 | 0 | observed/confirmed 证据链 |
| Discovery adapter 与 runtime parity | 8 | 3 | 已有 adapters；尚未统一 authoring fact projection |
| 企业级隔离、配额、审计、可观测性 | 10 | 4 | 可复用 registry/revision 基础；缺 authoring 专属控制面 |
| 文档、golden、browser、parity 证据 | 7 | 3 | 本轮 canonical/wire-schema tests；compiler/browser 证据尚缺 |
| **合计** | **100** | **24** | **差距 76%** |

当前数据库 registry 的 callable 冲突检查基于进程内快照，能保护单实例及普通 H2/JDBC 使用，但还不是多副本并发写入下的原子全局约束。工业化阶段仍需引入规范化 callable ownership 表、数据库唯一约束或可证明的串行化事务，不能仅依赖应用层 preflight。

兼容的同名 callable 目前会在 effective catalog 中折叠成一份定义，来源 library 仍可分别导出，但 catalog contract 尚不能显式表达多个 owner/alias。它和协议 capability negotiation 都保留为后续兼容性工作，不能视为本轮已经完成。

## 4. 下一迭代

下一步进入 Stage 0 编译内核，不先堆 UI：

1. 定义 `bloge.visualLibraryAuthoring.v1` Java model 与 machine schema；
2. 实现无歧义 compact type grammar 和 function signature grammar；
3. 实现确定性 `AuthoringCompiler`、canonical normalization 与 source map；
4. 建立至少 20 组 golden vectors，覆盖 operator-only、function-only、mixed、named type、overload 和负向安全语料；
5. 提供 stateless preview API，继续复用现有 canonical validator/profile/impact。

该迭代 Exit Gate 是同一 authoring 输入产生字节级稳定 canonical 输出，所有 diagnostic 能回到 authoring path，旧 raw import 回归保持全绿。
