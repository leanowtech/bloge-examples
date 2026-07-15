# BLOGE Framework Execution Control 原生能力需求

## 1. 文档目的

Resource Gateway 先用独立 test engine 和 `executeWithOperators` 验证 Execution Data Control Plane，但裸 `Map<String, ?>` 不能长期承担工业协议。本文定义需要下沉 BLOGE Framework 的执行控制能力、优先级和验收条件；fixture registry、治理 UI 与具体测试产品仍归 Resource Gateway。

核心边界：业务数据属于 `GraphContext`，执行控制属于不可变 `ExecutionOptions`；DSL 和 operator 不能读取、传播或修改控制计划。

## 2. 不变量

1. `ExecutionPurpose` 由可信入口和服务端 policy 铸造，不相信请求字段。
2. `PRODUCTION` purpose 的 control plan 必须为空，且在调度前验证。
3. artifact、runtime binding 和 control plan 在执行前冻结并指纹化。
4. resolver 对 primary、foreach、subgraph、compensation 和 function 使用同一身份模型。
5. 每次 REAL、MOCKED、DENIED 和 fallback 解析都进入原生 trace。
6. durable resume 必须恢复相同 plan fingerprint，不能回退 REAL。
7. 不把 fixture、secret 或未脱敏 payload写进普通日志。

## 3. P0: ExecutionOptions、Purpose 与 ControlPlan

建议新增不可变入口：

```java
public record ExecutionOptions(
        AuthorizedExecutionPurpose purpose,
        ExecutionControlPlan controlPlan,
        ExecutionServices services,
        TracePolicy tracePolicy
) {}

public enum ExecutionPurpose {
    PRODUCTION,
    OPERATOR_UNIT_TEST,
    SUBGRAPH_TEST,
    GRAPH_CONTRACT_TEST,
    REPLAY,
    SANDBOX
}

public interface GraphEngine {
    GraphResult execute(Graph graph, GraphContext context, ExecutionOptions options);
}
```

`AuthorizedExecutionPurpose` 不提供公共“从字符串构造”的信任路径，框架集成层必须传入由 endpoint + workload identity + policy 共同生成的实例。`ExecutionControlPlan` 只包含解析后的 invocation-site 决策和 plan fingerprint，不携带可执行脚本或运行期可变 registry。

兼容策略：现有 `execute` 使用 production empty options；`executeWithOperators` 标记为低级兼容入口，内部转换为显式 test purpose，未来 major 版本移除。production purpose 发现非空 plan 时抛出稳定错误 `BLOGE.CONTROL_PLAN.NOT_ALLOWED`。

**P0 验收**：

- 空 options 保持现有执行行为；
- production + non-empty plan 在任何 interceptor/node 前失败；
- control plan 不出现在 `GraphContext`、DSL 变量或 operator input；
- execution result 和 durable checkpoint 携带 purpose/plan fingerprint；
- 并发执行不能观察到其他 run 的 plan。

## 4. P1: Interceptor 顺序合同与引擎原生 Trace

当前应用可自由组合 interceptor，但 test double 与 cache/rate-limit/circuit-breaker/side-effect journal 的相对顺序会改变语义。框架需要声明阶段而不是依赖 list 顺序：

```text
ADMISSION
  -> CONTROL_RESOLUTION
  -> CACHE_LOOKUP
  -> RESILIENCE
  -> OPERATOR_INVOCATION
  -> SIDE_EFFECT_COMMIT
  -> CACHE_WRITE
  -> OBSERVATION
```

test runtime 可在配置层完全移除生产 cache、tenant limiter、production circuit breaker 和 commit adapter。若保留某阶段，MOCKED 结果默认禁止写 production cache、更新 production resilience 指标或提交 side effect。

原生 trace 至少记录：invocation-site identity、operator/runtime binding fingerprint、resolution、behavior、boundary、fidelity、attempt/correlation/occurrence、开始/结束时间、status、error code、输入输出摘要、fixture rule refs 和 consumption hit。trace listener 看到的是事实事件，不能改变解析结果。

**P1 验收**：架构测试能枚举实际 interceptor stages；乱序或重复关键阶段启动失败；MOCKED run 不能触发 production cache write、breaker mutation 或 side-effect commit；primary/compensation trace 结构一致。

## 5. P1: OperatorResolverChain

当前 node id map、embedded operator、registry 和 compensation 的解析规则分散，导致同一 fixture 在不同执行路径行为不一致。框架需要统一 resolver：

```java
public interface OperatorResolver {
    Resolution resolve(InvocationSite site, ResolutionContext context);
}

public interface OperatorResolverChain {
    Resolution resolveRequired(InvocationSite site, ResolutionContext context);
}
```

建议优先级：authorized control-plan exact site、execution-scoped provider、embedded binding、application registry。control-plan 同级歧义必须在 preflight 失败；运行中不得“最后一个覆盖”。`Resolution` 必须区分 REAL、TEST_DOUBLE、DENIED、UNRESOLVED，并携带来源和 fingerprint。

解析链必须覆盖 primary、foreach body、subgraph、stream、retry、compensation。node id 不是跨 graph path 的全局身份；补偿不能继续单独按 `operatorRef` 解析。

**P1 验收**：同一 `InvocationSite` 在 preflight 与 execute 得到相同 resolution；nested graph 相同 node id 不冲突；compensation/foreach conformance tests 通过；零命中、歧义和 plan fingerprint 漂移均 fail closed。

## 6. P2: ExecutionServices 与 FunctionCallSite

确定性不能只覆盖 operator。引擎需把执行期非业务服务收敛到 execution scope：

```java
public interface ExecutionServices {
    TimeSource timeSource();
    RandomSource randomSource();
    IdSource idSource();
    ExecutionIdentity identity();
    FeatureFlagSource featureFlags();
}
```

默认 production implementation 代理系统服务；测试 implementation 使用逻辑时钟和固定 seed。operator 通过 `OperatorContext.executionServices()` 使用，框架 built-in function 也必须使用同一 scope。

函数调用需要稳定 `FunctionCallSite`：artifact fingerprint、graph path、expression/source location、functionRef、call index、correlation key。DSL 编译器不能把无法替换的 function 实例永久捕获在 closure 中；函数解析应在 execution scope 完成并产生 trace。

**P2 验收**：固定 clock/seed 的重复运行 evidence 一致；生产实现不受影响；函数 REAL/SPY/DENY 和未来 RETURN 可寻址；函数控制不暴露给表达式本身。

## 7. Durable 与并发要求

- checkpoint 保存 purpose、plan fingerprint、service seed/clock state 和 consumption counters。
- resume 缺失原计划返回 `CONTROL_PLAN_UNAVAILABLE`，绝不转 REAL。
- 并行分支以 correlation key/lineage 消费 fixture；全局 occurrence 只在框架证明顺序时启用。
- retry attempt 由 scheduler 产生并进入 site identity，调用方不能伪造。
- stream trace 有 item/error/complete 序列和逻辑时间，不用真实 sleep 测 timeout。

## 8. 暂不下沉 Fixture SPI

Resource Gateway v1 先验证 selector、match、consumption、fidelity 和 evidence 语义。以下条件全部满足后才提炼通用 Fixture SPI：

1. 至少三类真实 graph 和两类非 HTTP operator 完成 dogfooding；
2. foreach、retry、compensation 寻址语义有 conformance test；
3. schema waiver、raw protocol derivation 和 DENY 未出现语义反复；
4. 协议至少经过一个外部调用方和一次兼容升级；
5. 安全审查证明 SPI 不允许任意代码或 production fallback。

在此之前，BLOGE 只接收解析后的 `ExecutionControlPlan` 和 resolver，不感知 Resource Gateway suite registry、UI、ANEKE workbook 或 fixture 存储。

## 9. 版本与迁移

| 能力 | 优先级 | 首个版本要求 | 兼容门禁 |
| --- | --- | --- | --- |
| ExecutionOptions/Purpose/ControlPlan | P0 | 新重载 + production empty default | 非空 production plan 必拒绝 |
| Interceptor stages/native trace | P1 | 可枚举阶段和事实事件 | 未声明关键阶段启动失败 |
| OperatorResolverChain | P1 | 覆盖主节点、nested、foreach、compensation | node-id map 仅兼容适配 |
| ExecutionServices/FunctionCallSite | P2 | time/random/id/identity/function trace | 固定 seed 可重复 |
| Fixture SPI | Deferred | RG 语义成熟后单独 ADR | 不接受任意脚本 |

Resource Gateway 在框架能力落地前保留独立 test engine adapter；切换条件是框架 conformance suite、证据字段和 production isolation tests 全绿，而不是仅 API 可编译。
