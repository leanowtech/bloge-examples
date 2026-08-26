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
| 阶段零 | 收敛执行内核、描述符传输替换、控制面隔离、旧路径适配 | `IN_PROGRESS` | 执行模式、控制头协议、受控入口、生产双重拒绝、schema stand-in、visual 默认适配、无 binding descriptor 执行及完整差分/安全/确定性测试台均已形成开发证据，双项目最终回归全绿；仅授权引用式大负载解析仍待阶段一闭合 |
| 阶段一 | 逻辑契约、世界模型、剧情、编译下沉和资产持久化 | `IN_PROGRESS` | 逻辑资源契约、无状态世界模型、纯 BLOGE 片段准入、隔离试跑、剧情资产模型及无副作用 FixtureBundle 编译已形成开发证据；运行期世界委托、三重 compiler oracle、引用解析及资产持久化尚未实现 |
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
| `S0-E` | 新旧路径等价对拍、确定性和架构约束 | `DEVELOPMENT_VERIFIED` | visual 固定差分矩阵、朴素独立编译 oracle、每模式 20 次编译、20 次内核重放、八类核心代码非确定 API 门禁、visual/descriptor 系统级零网络和 ReDoS 负向矩阵全部固定 |

### 3.2 阶段出口

| 编号 | 必须满足的事实 | 当前证据 | 状态 |
|---|---|---|---|
| `S0-EXIT-01` | 无部署业务算子绑定时，描述符驱动的 `httpResource` 只替换传输并完整执行映射、URL、Header、协议和负载提取 | 图内 embedded binding、compiler registry 和 isolated engine registry 均为空时，run-scoped fail-closed binding 允许统一编译与执行；真实 `ResourceFixtureRuntime` 完成参数、URL、Header、Body、协议和负载管线，共享 registry 保持不变 | `MET` |
| `S0-EXIT-02` | 设计期模拟通过 adapter 进入统一内核 | Spring 默认 `VisualGraphSimulationService` 通过 visual-owned `VisualSimulationExecutor` 委托统一内核；旧四参数路径只保留为差分 oracle | `MET` |
| `S0-EXIT-03` | 控制走 Header，业务走 Body；有界 inline 可用，大负载只走引用 | Header admission 和 bounded inline fixture 已进入既有执行链；legacy Body 控制为兼容保留，Scenario/World Model 授权引用属于阶段一 | `PARTIAL` |
| `S0-EXIT-04` | 生产入口和服务端目的形成两道独立拒绝 | 6 种固定执行模式分别证明：production Filter 在 DTO 反序列化前拒绝且下游编译/执行为零；绕过 Filter 后 production TestExecution service 在规划、registry、post-compile admission 和 executor 前独立拒绝；visual production service 在 kernel 前独立拒绝 | `MET` |
| `S0-EXIT-05` | 新内核与旧路径在节点/边上限、超时、逐节点 fixture 和混合分类上对等 | 14 项固定差分矩阵逐字段对拍 stand-in、纯原语混合、多节点分类、fixture 优先级/null/输入不匹配、输出覆盖、节点/边上限、校验/DSL 失败、双路径超时中断和异常净化 | `MET` |
| `S0-EXIT-06` | 模拟和描述符传输路径具备 SSRF 零网络证明，匹配器具备 ReDoS 负向证明 | descriptor 与 visual kernel 对本地可计数真实端点均保持零请求；危险嵌套量词/分组/交替/lookaround/backreference、超长/畸形 pattern 与超长 candidate 在匹配前拒绝；类文件规则禁止模拟关键类构造生产网络客户端 | `MET` |
| `S0-EXIT-07` | 编译等价、N 次重放确定性和禁非确定 API 架构规则通过 | 独立 oracle 对拍合法模式及失败关闭；每模式 20 次编译保持 plan/binding fingerprint 稳定且 planId 唯一；20 次运行保持语义与服务状态指纹稳定；八个阶段零核心类及关键嵌套类禁止直接时间、UUID、随机与并行流 API | `MET` |
| `S0-EXIT-08` | 旧路径未被提前删除，公开 endpoint 和既有协议保持兼容 | 旧路径仍保留；Resource Gateway 7145 项测试与 Test Kit 1905 项单测、2 项集成测试全绿，A1 协议归档边界通过 | `MET` |

阶段零只有 `S0-EXIT-01..08` 全部为 `MET`，且本项目与 Test Kit 的里程碑命令全绿时，才能标记为 `DEVELOPMENT_VERIFIED`。

## 4. 后续阶段出口摘要

### 4.1 阶段一

| 切片 | 交付物 | 状态 | 当前证据 |
|---|---|---|---|
| `S1-A` | 版本无关的逻辑资源契约、描述符投影、具体实现绑定与兼容性分析 | `DEVELOPMENT_VERIFIED` | 18/18：canonical fingerprint、Map/集合语义顺序归一化、防御拷贝、待确认投影、provider/API 版本隔离、结构化输出证明、输入/输出双向兼容、UNKNOWN 失败关闭与错误净化 |
| `S1-B` | 无状态世界模型、纯 BLOGE 世界片段、纯净校验和片段单测台 | `DEVELOPMENT_VERIFIED` | 41/41：不可变模型、版本化片段、稳定指纹、契约与 binding 一致性、AST/compiled-inventory 双重纯净准入、真实 BLOGE 隔离执行、FIRST/default、UNIQUE 歧义、20 次确定性重放、资源上限、超时线程终止、外部算子零执行和净化错误 |
| `S1-C` | Scenario、编译下沉、逻辑契约寻址与双向来源映射 | `IN_PROGRESS` | `S1-C1 + S1-C2a` 63/63：精确 target/world 引用、稳定 Scenario 指纹、assertion 无损映射、契约兼容，以及确定性 FixtureBundle lowering、规范契约标签、真实 selector 对拍、多节点复用、fail-closed delegate sentinel、无负载编译指纹和双向来源映射已闭合；运行期 `WORLD_DELEGATE` 与三重 oracle 尚未实现 |
| `S1-D` | 授权引用解析、分级负载托管与三类资产版本化持久化 | `NOT_STARTED` | 尚无读取前授权、不可变递增版本与跨重启证据 |

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
ExecutionControlCompilerTest        43/43
InvocationInventoryBuilderTest       5/5
ResourceFixtureRuntimeTest           9/9
OperatorMicroGraphRunnerTest        10/10
TestRunServiceTest                  45/45
TestRunServiceArchitectureTest       1/1
Stage0Exit06SecurityProofTest        4/4
Stage0Exit07FixedProofTest           3/3
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
VisualGraphSimulationKernelIntegrationTest 14/14
VisualGraphSimulationSpringWiringTest       2/2
VisualGraphSimulationControllerTest  2/2
VisualGraphSimulationProductionAdmissionTest 7/7
VisualRuntimeBoundaryTest            1/1
ServerDeploymentPolicySpringWiringTest 4/4
ExecutionModeHintsTest               3/3
VisualSimulationPlanTest             3/3
VisualSimulationKernelAdapterTest    9/9
```

上述 60 项内核测试证明 `S0-A` 开发切片闭合；19 项协议测试证明 `S0-P` 纯解析协议闭合。S0-C 的 schema stand-in 内核聚焦集为 88/88：普通 output fixture 保持 `OUTPUT_LEVEL`，只有精确 site/rule 的 server-owned Java hint 才能冻结 `SCHEMA_STANDIN`，显式 `null` 仍可作为合法最终输出，真实算子不执行，证据降为探索级。6/6 port/hint 测试证明 visual-owned plan 不携带 testing/governance 类型，且多节点 hint 只能冻结精确 `SCHEMA_STANDIN`；9/9 adapter 测试证明单/多节点、共享 operatorRef、expected-input、纯原语、编译失败净化和十次语义确定性经过统一内核。14/14 新旧路径固定差分矩阵覆盖 stand-in、纯原语混合、多节点分类、持久化/请求 fixture 优先级、null、输入不匹配、输出覆盖、节点/边上限、校验/DSL 失败、双路径超时中断和异常净化；2/2 真实 Spring 接线测试证明默认 `@Service` 由容器注入 kernel adapter，并在生产 evidence 下失败关闭。该差分切片联合聚焦集为 47/47。S0-B/S0-D 及相关隔离回归的串行聚焦集为 463/463，其中包含 3/3 mirror 事务时钟基线；另有 4/4 policy Spring wiring 测试证明 production profile 或服务端 production environment evidence 下，普通业务 Bean 注册非生产 policy 不能替换最终准入证据（冲突时 fail closed）。阶段零安全、差分、确定性和双项目全量回归均已闭合；当前唯一未闭合出口是 `S0-EXIT-03` 的授权引用式大负载解析，该能力由阶段一 `S1-D` 交付。

S0-EXIT-01 的联合聚焦集为 127/127。新增证据覆盖：缺失 `httpResource` binding 时调用清单冻结为稳定且不可直接执行的外部边界；已有真实 binding 仍优先；其他缺失算子继续失败关闭；无 fixture 自动生成隐式 `DENY`；图内 embedded operator、compiler registry 与 isolated engine registry 同时为空时，run-scoped overlay 不修改共享 registry，`DESCRIPTOR_TRANSPORT` 仍经真实 descriptor 管线完成参数映射、URL、Header、Body、响应协议与 payload 提取并产生 `TRANSPORT_LEVEL` 可认证证据；缺描述符和协议拒绝保持确定性失败。

S0-EXIT-07 的当前确定性切片联合聚焦集为 115/115。运行身份与 evidence 壁钟已收敛到显式不可变依赖，默认构造器只在边界绑定系统适配器；同一固定逻辑时钟请求连续运行 20 次，plan fingerprint、semantic projection、semantic result fingerprint 与 execution-service state fingerprint 全部逐次相同，而 runId 和包含运行事实的完整 evidence fingerprint 均保持唯一。类文件架构测试禁止 `TestRunService` 直接调用 `Instant.now`、`UUID.randomUUID`、`Math.random`、`ThreadLocalRandom.current` 或分配 `Random`。该证据尚未覆盖其余阶段零核心类及独立编译等价台，因此 `S0-EXIT-07` 仍为部分满足。

S0-EXIT-06 的固定安全台为 4/4，相关联合回归为 143/143。descriptor 与 visual kernel 测试均启动真实本地计数端点并将资源 URL 指向该端点，执行后请求数严格为零；`StubHttpRequestOperator` 已从继承生产 `HttpRequestOperator` 改为实现 Resource Gateway 应用层的最小 `HttpRequestTransport`，不再为测试构造真实 `HttpClient`，生产 Spring 构造器仍保持原 `HttpRequestOperator` 依赖。ReDoS 矩阵覆盖典型灾难回溯结构、超长/畸形 pattern 与超长 candidate，同时保留安全字符类和锚点表达式。类文件规则进一步证明 `VisualSimulationKernelAdapter`、stub 与 `ResourceFixtureRuntime` 不创建生产网络 transport。本切片 `resource-gateway-examples clean verify` 为 7104 tests / 0 failures / 0 errors。

S0-EXIT-07 的最终固定台为 3/3，相关联合回归为 129/129。测试内朴素 oracle 不调用生产 `ExecutionMode.resolve` 或 compiler 内部分类器，独立声明并逐字段对拍纯只读 real、schema stand-in、descriptor protocol、descriptor transport、output-level return 与 external implicit deny，同时覆盖 descriptor 混淆和畸形 fixture 的失败关闭。每个代表模式连续编译 20 次，plan/runtime-binding fingerprint 完全稳定，而默认 planId 保持唯一；显式 identity source 可确定性控制 ID 序列，null/blank 则以 `CONTROL_PLAN_ID_UNAVAILABLE` 拒绝。plan identity 与 run identity 均只在边界适配器使用系统 UUID/时间。类文件规则覆盖 `ExecutionControlCompiler`、`InvocationInventoryBuilder`、`SelectorResolver`、`SafetyPreflight`、`TestDoubleFactory`（含 observed operator）、`ResourceFixtureRuntime`、`VisualSimulationKernelAdapter` 和 `TestRunService`。

S0-EXIT-04 的固定生产旁路矩阵为 18/18，覆盖 real/read-only、schema stand-in、descriptor protocol、descriptor transport、output-level return 与 implicit deny。入口证据使用自定义 DTO 反序列化哨兵，证明 production Filter 在读取业务 Body 前拒绝，非生产下游 compiler/executor 计数为零；服务证据直接绕过 Filter，证明 production `TestExecutionApiService` 在目标规划、registry、post-compile admission 与 executor 前独立拒绝；visual 证据证明 production service 在 kernel adapter 前拒绝。所有失败响应均不含业务 payload、控制内容、模式或堆栈。该矩阵与既有边界/服务/visual 测试的联合聚焦集为 386/386；加入 S1-A 契约切片后的独立复跑为 404/404。

截至 2026-08-26，本切片最终串行里程碑结果：

```text
resource-gateway-examples clean verify  7145 tests / 0 failures / 0 errors / 28 skipped
resource-gateway-test-kit clean verify  1905 tests + 2 integration tests / all green
A1 protocol archive boundary            PASS
```

该全量结果证明当前仓库回归分母为绿色，并闭合 `S0-EXIT-08`。阶段零仍因 `S0-EXIT-03` 为 `PARTIAL` 而保持 `IN_PROGRESS`，不得提前标记为 `DEVELOPMENT_VERIFIED`。
