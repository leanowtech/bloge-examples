# Resource Gateway 1.2.1 S2-D 函数控制验证说明

本文记录 `S2-D` 的实现边界和可复现证据。目标是证明 BLOGE 表达式函数可以在 Resource Gateway 测试运行中被精确、确定性地控制，同时保留原函数治理事实和 payload-free 审计。本文不声明 `S2-E` 的生产隔离或对外协议已经完成。

## 1. 已实现能力

### 1.1 静态编译

- 从 BLOGE `CompiledGraph` 的类型化函数清单和 Resource Gateway Invocation Inventory 生成完整 `FunctionInvocationSite`。
- 支持根图、import、foreach、loop、嵌套图和同一子图的多路径复用。
- 对拍函数库声明与运行时 registry name、runtime name、pure、required execution services 和 fingerprint；事实漂移失败关闭。
- 将精确参数规则排在 wildcard fallback 之前；零命中、重复精确参数、多个 fallback 和宽匹配歧义均拒绝。
- 冻结 RETURN、THROW、DELAY、TIMEOUT、消费上下限和 `forcePureOverride`，运行时不重新解释 selector。

### 1.2 运行控制

- `FunctionControlRuntime` 按 run 创建，不跨运行共享消费计数或 observation。
- resolver 先调用原 `ExpressionFunctionResolver`，再包装返回函数；原函数名称、纯度和执行服务声明仍需匹配冻结事实。
- RETURN 和 DELAY 支持显式 JSON `null`；「返回 null」与「未声明返回值」是不同状态。
- DELAY 只使用 run-scoped `TimeSource`；TIMEOUT 产生确定性受控错误，不等待真实超时。
- 最大消费使用原子计数；超限、参数未命中、未满足最小消费和未规划调用点均失败关闭。
- DIRECT 调用继续委托原函数；必须替换的 CONTROLLED 调用不会回落到真实函数。

### 1.3 审计与证据

函数控制证据只保存：

- plan fingerprint 和 evidence ceiling；
- 调用点、原函数 fingerprint、运行事实 fingerprint 和控制模式；
- 每条规则的 minimum、maximum、used 和 status；
- invocation scope、参数、结果和错误的 fingerprint；
- occurrence、逻辑 duration 和降级原因。

证据不保存原始参数、返回值、错误文本或 schema。evidence fingerprint 同时绑定计划、控制 binding、消费摘要和 observation；不同计划即使没有调用，也不会得到相同 evidence fingerprint。

受控调用仍进入 `GovernedExecutionServices` 的 function usage audit。审计 callback 由运行所有者以私有闭包注入，不挂载到返回的 `ExpressionFunction`，自定义函数不能通过强转或公开接口伪造调用次数。

## 2. 证据等级

| 原函数与控制方式 | 函数证据上限 | 最终运行证据 |
|---|---|---|
| 已声明的环境事实型函数受控替换 | `CERTIFIABLE` | 仍受 fixture 来源、执行服务和其他控制项共同约束 |
| 已声明的外部查询型函数受控替换 | `CERTIFIABLE` | 仍受其他认证缺口约束 |
| 纯函数设置 `forcePureOverride=true` | `EXPLORATORY` | 服务端聚合后强制降为 `EXPLORATORY` |
| `UNKNOWN` 或 `LEGACY` 声明 | `PREVIEW` | 不得形成契约级证据 |

固定测试先证明同一 STORED 请求在无函数控制时为 `CERTIFIABLE`，再证明启用纯函数强注入后降为 `EXPLORATORY`。调用方不能提高该等级。

## 3. 统一运行路径

`CompiledTestRuntimeOptions` 只替换 `ExecutionServices` 的 resolver，保留同一 run 的 TimeSource、RandomSource、IdGenerator、IdentityProvider、FeatureFlagProvider 和 SecretProvider。`TestRunService` 支持：

1. 仅函数控制；
2. 仅 World State Session；
3. World State Session 与函数控制同时启用。

真实 DSL 测试通过 BLOGE `GraphLoader.loadArtifact` 获取函数清单，在 `TestRunService` 中执行纯函数控制。结果证明 DAG 节点收到替身值、真实函数零调用，并且 World State Session 仍由服务端创建和关闭。S2-D 验证时使用的内部函数证据记录已在 S2-E1 被版本化 `controlEvidenceProjection` 取代，不再作为第二套外部 metadata 协议暴露。

## 4. 失败语义

| 场景 | 结果 |
|---|---|
| 调用点不在冻结计划中 | `RG.FUNCTION.RUNTIME_SITE_UNPLANNED` |
| registry 或 resolver 返回事实漂移 | `RG.FUNCTION.RUNTIME_BINDING_DRIFT` |
| 参数无精确候选且无 fallback | `RG.FUNCTION.CONTROL_ARGUMENT_MISMATCH` |
| 使用次数超过 maximum | `RG.FUNCTION.CONTROL_EXHAUSTED` |
| 成功结束但未满足 minimum | `RG.FUNCTION.MINIMUM_UNCONSUMED` |
| DELAY 的治理时钟失败 | `RG.FUNCTION.CONTROL_DELAY_FAILED` |
| THROW / TIMEOUT | `RG.FUNCTION.CONTROL_THROW` / `RG.FUNCTION.CONTROL_TIMEOUT` |

图执行已经失败时，收尾不会用 minimum 错误覆盖主错误；证据仍保留 `MINIMUM_UNSATISFIED` 消费状态和失败 observation。

## 5. 验证结果

聚焦命令：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=FunctionControlPlaneTest,FunctionControlRuntimeTest,FunctionControlArchitectureTest,GovernedExecutionServicesTest,TestRunServiceTest,IndependentDurableTestEngineFactoryTest \
  test
```

结果：98 tests，0 failures，0 errors，0 skipped。

全量命令：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

结果：7371 tests，0 failures，0 errors，28 skipped。

另外，`git diff --check` 通过；函数控制主源码的架构测试禁止依赖 `FixtureRule`、`SelectorResolver`、`NodeSpec`，并禁止直接调用 `Thread.sleep`、`System.currentTimeMillis` 或 `System.nanoTime`。

## 6. 尚未闭合

以下内容不能仅从本阶段证据推导为已完成：

- 函数与状态 evidence 的稳定外部 JSON 协议和持久化已由 `S2-E1` 闭合，见 [S2-E1 验证说明](./rg-evolution-design-1.2.1-s2-control-evidence-verification.md)；
- 生产双层拒绝、真实 HTTP 精确引用、权威编译产物和能力探针已由 `S2-E2a` 闭合，见 [S2-E2a 验证说明](./rg-evolution-design-1.2.1-s2-governed-function-http-verification.md)；
- Test Kit 的协议解析、证据校验和 Resource Gateway/Test Kit 双项目最终里程碑仍待 `S2-E2b`。
