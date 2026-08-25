# Resource Gateway 1.2.1 实施与验收台账

本文是 [`rg-evolution-design-1.2.1.md`](./rg-evolution-design-1.2.1.md) 的实施伴随文档。设计文档定义目标和约束；本文只记录可复现的实现证据，不以代码存在、局部测试或人工判断替代阶段出口验收。

## 1. 状态规则

| 状态 | 含义 |
|---|---|
| `NOT_STARTED` | 尚无实现或验收证据 |
| `IN_PROGRESS` | 已开始实现，但阶段出口尚未全部闭合 |
| `DEVELOPMENT_VERIFIED` | 仓库内实现和固定自动化分母全部通过；不代表企业环境正式验收 |
| `BLOCKED_EXTERNAL` | 仓库内工作已完成，但缺少真实环境、真实资产或责任人签署 |

状态只能由本表列出的固定证据推进。阶段内任一必选项缺失时，阶段不得标记为 `DEVELOPMENT_VERIFIED`。

## 2. 总体状态

| 阶段 | 设计目标 | 当前状态 | 当前判断 |
|---|---|---|---|
| 阶段零 | 收敛执行内核、描述符传输替换、控制面隔离、旧路径适配 | `IN_PROGRESS` | 执行模式、控制头协议、受控入口、生产双重拒绝、schema stand-in、visual 默认适配与无 binding descriptor 执行已形成开发证据；完整差分/安全/确定性测试台仍未完成 |
| 阶段一 | 逻辑契约、世界模型、剧情、编译下沉和资产持久化 | `NOT_STARTED` | 现有 Scenario/Mirror 资产不能自动等同于 1.2.1 定义的世界模型闭包 |
| 阶段二 | 有状态世界和函数返回值注入 | `NOT_STARTED` | 现有 mirror state 机制尚未按本方案出口标准验收 |
| 阶段三 | 线上沉淀、影响分析和存量迁移 | `NOT_STARTED` | 现有 replay/corpus 零件尚未形成本文要求的端到端闭环 |

## 3. 阶段零验收矩阵

### 3.1 实现切片

| 切片 | 交付物 | 状态 | 证明要求 |
|---|---|---|---|
| `S0-A` | 七态 `ExecutionMode`；编译期模式固定；`DESCRIPTOR_PROTOCOL` / `DESCRIPTOR_TRANSPORT` 显式运行分派 | `DEVELOPMENT_VERIFIED` | 60/60：模式分类、compiler 到 runtime 的动态多规则实际选择、混合规则 fingerprint 稳定、提取语义对拍、模式不一致失败关闭、legacy graph-contract 兼容 |
| `S0-P` | 四个 `X-BLOGE-Test-*` 控制头的纯协议解析 | `DEVELOPMENT_VERIFIED` | 19/19：strict base64url/UTF-8/JSON、大小和复杂度边界、多值拒绝、错误不泄漏、inline canonicalization |
| `S0-B` | 服务端执行目的铸造与受控入口 admission | `DEVELOPMENT_VERIFIED` | 七类 Body 入口在反序列化前按固定 `IntegrationOperation` 认证；bounded inline fixture 进入既有解析链；caller purpose 不可改写内核 purpose |
| `S0-C` | visual 设计期模拟适配统一内核 | `DEVELOPMENT_VERIFIED` | `SCHEMA_STANDIN` 经 server-owned Java hint 在 compiler/runtime/evidence 中显式闭合；visual-owned port 隔离 testing 类型；Spring 默认服务走统一内核；新旧路径对 stand-in、纯原语混合、逐节点 fixture 与输入不匹配保持语义等价；生产拒绝和超时仍在服务边界生效 |
| `S0-D` | 生产入口控制头拒绝/剥离及服务端二次拒绝 | `DEVELOPMENT_VERIFIED` | profile/服务端环境双判定、context-path 安全执行路由全集、真实 Spring Filter 链与 visual service 独立 403 拒绝；server-owned policy Bean 不可由普通业务 Bean 条件替换 |
| `S0-E` | 新旧路径等价对拍、确定性和架构约束 | `IN_PROGRESS` | 已有 visual 正常/输入不匹配差分及 adapter 十次语义确定性；完整正常/异常矩阵、N 次证据同指纹、禁非确定 API、ReDoS 与系统级零网络证明仍缺失 |

### 3.2 阶段出口

| 编号 | 必须满足的事实 | 当前证据 | 状态 |
|---|---|---|---|
| `S0-EXIT-01` | 无部署业务算子绑定时，描述符驱动的 `httpResource` 只替换传输并完整执行映射、URL、Header、协议和负载提取 | 图内 embedded binding、compiler registry 和 isolated engine registry 均为空时，run-scoped fail-closed binding 允许统一编译与执行；真实 `ResourceFixtureRuntime` 完成参数、URL、Header、Body、协议和负载管线，共享 registry 保持不变 | `MET` |
| `S0-EXIT-02` | 设计期模拟通过 adapter 进入统一内核 | Spring 默认 `VisualGraphSimulationService` 通过 visual-owned `VisualSimulationExecutor` 委托统一内核；旧四参数路径只保留为差分 oracle | `MET` |
| `S0-EXIT-03` | 控制走 Header，业务走 Body；有界 inline 可用，大负载只走引用 | Header admission 和 bounded inline fixture 已进入既有执行链；legacy Body 控制为兼容保留，Scenario/World Model 授权引用属于阶段一 | `PARTIAL` |
| `S0-EXIT-04` | 生产入口和服务端目的形成两道独立拒绝 | 入口 Filter 已覆盖已识别执行路由；visual 默认 adapter、visual service 与 TestExecution service 均保留 production 拒绝；尚缺所有执行模式的端到端旁路矩阵 | `PARTIAL` |
| `S0-EXIT-05` | 新内核与旧路径在节点/边上限、超时、逐节点 fixture 和混合分类上对等 | 已对拍 stand-in、纯原语混合、fixture 优先级和输入不匹配，并证明 kernel 超时中断；尚缺节点/边上限及完整异常矩阵 | `PARTIAL` |
| `S0-EXIT-06` | 模拟和描述符传输路径具备 SSRF 零网络证明，匹配器具备 ReDoS 负向证明 | 无 binding descriptor transport 已证明只经 `StubHttpRequestOperator` 并可观察完整渲染请求；尚缺 visual 系统级零网络旁路测试与 ReDoS 负向攻击矩阵 | `PARTIAL` |
| `S0-EXIT-07` | 编译等价、N 次重放确定性和禁非确定 API 架构规则通过 | 尚无阶段零固定测试台 | `NOT_MET` |
| `S0-EXIT-08` | 旧路径未被提前删除，公开 endpoint 和既有协议保持兼容 | 当前未删除旧路径 | `MET_PENDING_REGRESSION` |

阶段零只有 `S0-EXIT-01..08` 全部为 `MET`，且本项目与 Test Kit 的里程碑命令全绿时，才能标记为 `DEVELOPMENT_VERIFIED`。

## 4. 后续阶段出口摘要

### 4.1 阶段一

- 逻辑资源契约可由描述符投影初稿，并由人确认业务语义。
- 具体实现登记时必须证明输出满足逻辑契约。
- 世界片段只允许 BLOGE 纯原语，歧义匹配失败关闭，并可独立单元测试。
- `(WorldSlice + Scenario)` 确定性编译为现有 FixtureBundle 与断言，并保留双向来源映射。
- 契约兼容只在不兼容变更时使剧情失效。
- 大负载仅按授权引用解析，未授权引用在读取资产前失败。
- 逻辑契约、世界模型和剧情均采用不可变递增版本及稳定指纹持久化。
- 编译器通过结构性质、独立参考实现差分和往返三重验证。

### 4.2 阶段二

- 每个剧情拥有隔离、单写者、确定性排序、可序列化的世界状态。
- 写后读、跨剧情隔离和并发确定顺序具有固定自动化证明。
- 环境事实型、声明为非纯的自定义函数和纯函数强注入三类准入语义被严格区分。
- 纯函数强注入必须把证据上限降为探索级。

### 4.3 阶段三

- 生产观测只能经显式黄金捕获或受治理重放进入草稿，默认脱敏，晋升前人工复核。
- 静态影响图与运行时 fixture 消费互相校验，声明漂移可被发现。
- 存量测试套件只能单向抬升为剧情和世界模型草稿，旧协议继续兼容。
- 周期性真实环境重放能够检测世界保真度漂移。
- 两层变异门禁能发现“错误世界仍通过”的无效剧情。
- 系统测试床覆盖执行隔离边界故障注入和验证器反面对照。

## 5. 固定验证命令

聚焦反馈先运行受影响测试类；阶段切片提交前至少执行：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

涉及公开测试协议、客户端解析或 Schema 时，还必须执行：

```bash
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

截至 2026-08-25 的阶段零改造前基线：

```text
ExecutionControlCompilerTest        27/27
ResourceFixtureRuntimeTest           5/5
OperatorMicroGraphRunnerTest         4/4
合计                                36/36
```

该基线只证明改造前相关测试为绿色，不证明任何阶段出口已经完成。

阶段零新增开发证据：

```text
TestControlHeaderCodecTest          19/19
ExecutionControlCompilerTest        41/41
InvocationInventoryBuilderTest       5/5
ResourceFixtureRuntimeTest           9/9
OperatorMicroGraphRunnerTest        10/10
TestRunServiceTest                  44/44
GatewayGraphContractTestServiceTest 14/14
TestExecutionIngressAdapterTest     25/25
TestExecutionControllerTest         15/15
TestExecutionApiServiceTest         38/38
TestExecutionAuthenticationInterceptorIntegrationTest 22/22
TestRuntimeProfileIsolationTest     26/26
ExecutionControlBoundaryGuardFilterTest               309/309
ExecutionControlBoundaryGuardApplicationIntegrationTest 2/2
ExecutionControlBoundaryGuardNonProductionApplicationIntegrationTest 1/1
VisualGraphSimulationServiceTest    12/12
VisualGraphSimulationKernelIntegrationTest 7/7
VisualGraphSimulationSpringWiringTest       2/2
VisualGraphSimulationControllerTest  2/2
VisualGraphSimulationProductionAdmissionTest 7/7
VisualRuntimeBoundaryTest            1/1
ServerDeploymentPolicySpringWiringTest 4/4
ExecutionModeHintsTest               3/3
VisualSimulationPlanTest             3/3
VisualSimulationKernelAdapterTest    9/9
```

上述 60 项内核测试证明 `S0-A` 开发切片闭合；19 项协议测试证明 `S0-P` 纯解析协议闭合。S0-C 的 schema stand-in 内核聚焦集为 88/88：普通 output fixture 保持 `OUTPUT_LEVEL`，只有精确 site/rule 的 server-owned Java hint 才能冻结 `SCHEMA_STANDIN`，显式 `null` 仍可作为合法最终输出，真实算子不执行，证据降为探索级。6/6 port/hint 测试证明 visual-owned plan 不携带 testing/governance 类型，且多节点 hint 只能冻结精确 `SCHEMA_STANDIN`；9/9 adapter 测试证明单/多节点、共享 operatorRef、expected-input、纯原语、编译失败净化和十次语义确定性经过统一内核。新增 7/7 新旧路径集成对拍覆盖 stand-in、纯原语混合、持久化/请求 fixture 优先级、输入不匹配、生产拒绝及超时中断；2/2 真实 Spring 接线测试证明默认 `@Service` 由容器注入 kernel adapter，并在生产 evidence 下失败关闭。该切片联合聚焦集为 114/114。S0-B/S0-D 及相关隔离回归的串行聚焦集为 463/463，其中包含 3/3 mirror 事务时钟基线；另有 4/4 policy Spring wiring 测试证明 production profile 或服务端 production environment evidence 下，普通业务 Bean 注册非生产 policy 不能替换最终准入证据（冲突时 fail closed）。阶段一引用解析及阶段零完整差分/安全/确定性测试台尚未完成，因此阶段零出口仍保持部分满足。

S0-EXIT-01 的联合聚焦集为 127/127。新增证据覆盖：缺失 `httpResource` binding 时调用清单冻结为稳定且不可直接执行的外部边界；已有真实 binding 仍优先；其他缺失算子继续失败关闭；无 fixture 自动生成隐式 `DENY`；图内 embedded operator、compiler registry 与 isolated engine registry 同时为空时，run-scoped overlay 不修改共享 registry，`DESCRIPTOR_TRANSPORT` 仍经真实 descriptor 管线完成参数映射、URL、Header、Body、响应协议与 payload 提取并产生 `TRANSPORT_LEVEL` 可认证证据；缺描述符和协议拒绝保持确定性失败。阶段一引用解析及阶段零完整差分/安全/确定性测试台尚未完成，因此阶段零出口仍保持部分满足。

截至 2026-08-26，本切片最终串行里程碑结果：

```text
resource-gateway-examples clean verify  7047 tests / 0 failures / 0 errors / 28 skipped
resource-gateway-test-kit clean verify  1905 tests + 2 integration tests / all green
A1 protocol archive boundary            PASS
```

该全量结果证明当前仓库回归分母为绿色；它仍不能代替 `S0-EXIT-01..08` 的逐项事实验收。
