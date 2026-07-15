# Resource Gateway Execution Data Control Plane Stage 1 Verification

> 日期：2026-07-15
>
> 范围：统一测试执行内核、graph contract adapter、operator micro graph、F2/F3 resource fixture。
> 不包含：公共 testing API、持久化 test-run store、JUnit test-kit、画布 Run Operator、生产环境 profile/identity/network 隔离。

## 1. 实现清单

| 层 | 主要类型 | 已冻结行为 |
| --- | --- | --- |
| Domain | `InvocationSite`、`FixtureRule`、`FixtureBundle`、`EffectiveExecutionPlan`、`TestRunEvidence` | 版本化 wire model；时间/stream/replay 字段只预留不激活 |
| Planning | `SafetyPreflight`、`SelectorResolver`、`ExecutionControlCompiler` | 目标/fixture 指纹校验；零命中、歧义、危险正则、production purpose、外部 REAL/SPY 运行前拒绝 |
| Runtime | `TestRunService`、`TestDoubleFactory`、`IndependentTestEngineFactory`、`InvocationRecorder` | 每次运行独立 engine；`REAL/RETURN/THROW/DENY/SPY`；required consumption；node/edge trace |
| Resource fidelity | `ResourceFixtureRuntime`、`StubHttpRequestOperator` | F2 走真实 response protocol；F3 真实运行参数映射、URL 渲染、协议解析 |
| Operator L1 | `OperatorMicroGraphRunner` | 单节点 micro graph；`EXECUTABLE_UNIT` / `OPAQUE_RUNTIME` 诚实分类 |
| Evidence | `GraphArtifactFingerprint`、`ProtocolFingerprint`、`TestAssertionEvaluator` | target/fixture/plan 指纹链；断言容差；控制模式；脱敏 side-effect intent |
| Compatibility adapter | `GatewayGraphContractTestService` | 旧 endpoint/request/result 保持兼容，执行改为提交统一内核 |

## 2. 关键不变量

1. Test control 不进入 `GraphContext` 业务数据；只有编译后的 operator override 进入独立 engine。
2. 任何未配置的外部效应节点合成 `IMPLICIT_DENY`，不会 fallback 到真实依赖。
3. 同一 invocation site 的同优先级规则在运行前拒绝，运行期仍保留二次歧义守卫。
4. required fixture 未命中不是 warning，而是 `FIXTURE_UNUSED`。
5. inline、schema waiver、resource OUTPUT_LEVEL fixture 只能产生 `EXPLORATORY` 证据。
6. SPY 与 REAL 在 evidence 的 `nodeControlModes` 中可区分；side-effect journal 不包含原始幂等键和 payload。
7. artifact fingerprint 冻结 operator/schema/topology/graph I/O contract；存在 definition source 时还冻结源 payload digest。
8. planner 计算 binding fingerprint 时同时冻结实际 binding 对象；根图所有节点均从同一 frozen override map 执行，不会二次解析 latest registry。

## 3. 证据保真度

| Evidence fact | 能证明 | 不能证明 |
| --- | --- | --- |
| `OUTPUT_LEVEL` | 下游拿到指定结构时编排行为正确 | 被替换算子内部协议逻辑正确 |
| `PROTOCOL_DERIVED` | descriptor 的 response protocol 与 payloadPath 对 raw response 的解释真实执行 | 请求 URL/params 映射真实执行 |
| `TRANSPORT_LEVEL` | httpResource 请求映射、URL 渲染、response protocol、payload extraction 均真实执行 | 真实 provider 在 sandbox/production 的协议没有漂移 |
| `REAL` pure operator | 冻结 binding 在 BLOGE micro graph 内真实执行 | 内部隐藏网络、系统时间或全局状态可控 |
| `OPAQUE_RUNTIME` | 系统诚实识别当前不能签发可重复单测认证 | operator 实现错误或一定不安全 |

## 4. 聚焦验证

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=GatewayGraphContractTestServiceTest,ExecutionControlCompilerTest,TestRunServiceTest,ResourceFixtureRuntimeTest,OperatorMicroGraphRunnerTest,GraphArtifactFingerprintTest test
```

结果：37 tests，0 failures，0 errors，0 skipped。

| Test class | 验证重点 |
| --- | --- |
| `GatewayGraphContractTestServiceTest` | 旧 API 与 table suite 行为保持、draft/run/coverage |
| `ExecutionControlCompilerTest` | selector、指纹、歧义、reserved coordinate、external fail closed、regex policy |
| `TestRunServiceTest` | 五行为、unused/unmatched、证据分级、isolated engine、断言、SPY journal |
| `ResourceFixtureRuntimeTest` | BodyCode/BodyFlag/HttpStatus 协议派生差异、传输捕获、204 empty body |
| `OperatorMicroGraphRunnerTest` | pure executable、opaque external、httpResource transport conformance |
| `GraphArtifactFingerprintTest` | stable digest、definition source drift、direct-edge semantic drift |

最终模块门禁仍是：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

最终结果：1653 tests，0 failures，0 errors，34 conditional skips；Spring Boot JAR 打包成功，总耗时 1 分 23 秒。

## 5. 失败分类

| 状态/错误码 | 含义 | 调用方动作 |
| --- | --- | --- |
| `CONTROL_PLAN_REJECTED` | plan 不安全、不完整或歧义；graph 未启动 | 修正 selector、purpose、fingerprint 或 reserved 字段 |
| `FIXTURE_UNMATCHED` | 外部 site 没有 approved match，或 input 不符合 match | 补 fixture 或收紧/修正匹配条件 |
| `FIXTURE_UNUSED` | required rule 所期待路径没有发生 | 检查上游分支、规则条件或把 fixture 明确改为 optional |
| `ASSERTION_FAILED` | graph 已执行，但业务断言未满足 | 查看 node/edge trace 与 actual value |
| `EXECUTION_FAILED` | 真实 operator 或受控 THROW/DENY 失败 | 根据 node errorCode 定位业务失败或预期故障分支 |

## 6. Stage 2 入口条件

Stage 2 不能直接把 `TestRunService` 暴露为生产 controller。必须同时交付：test/staging-only bean 装配、server-minted purpose、
独立 test identity、egress deny policy、持久化 test-run store、payload governance、租户/配额、API compatibility tests、production
profile bean-absence tests、普通 run endpoint 拒绝 control 字段，以及全示例 suite dogfooding。完成这些前，Stage 1 内核只作为受控内部 adapter 使用。
